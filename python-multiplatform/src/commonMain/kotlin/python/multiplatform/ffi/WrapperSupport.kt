package python.multiplatform.ffi

import python.multiplatform.ffi.types.basic.PyNone
import python.native.ffi.NativePointer
import python.native.ffi.PyDict_GetItemString
import python.native.ffi.PyErr_Clear
import python.native.ffi.PyErr_Occurred
import python.native.ffi.PyEval_GetBuiltins
import python.native.ffi.PyLong_AsInt
import python.native.ffi.PyLong_FromLongLong
import python.native.ffi.PyObject_CallObject
import python.native.ffi.PyObject_GetAttrString
import python.native.ffi.PyTuple_New
import python.native.ffi.PyTuple_SetItem
import python.native.ffi.PyUnicode_AsUTF8
import python.native.ffi.Py_DecRef
import python.native.ffi.Py_IncRef

/**
 * Shared plumbing for the wrapper types under `python.multiplatform.ffi.types`.
 *
 * Two jobs, both of which every wrapper would otherwise repeat:
 *
 * 1. **Reading a dunder attribute as a Kotlin value.** The Stable ABI has no
 *    struct-field accessors -- `PyFunction_GetCode`, `PyMethod_Function`,
 *    `PySlice_Unpack` and friends are all outside the limited API, and the
 *    struct layouts they read are deliberately opaque under `Py_LIMITED_API`.
 *    `PyObject_GetAttrString` is therefore the only supported route to
 *    `__name__`, `__func__`, `range.start`, `slice.stop` and the rest of it.
 *    Going through [PyObject.getAttr] would build a full wrapper (with a
 *    cleaner registration) plus a `PyObject_Str` crossing for a value that is
 *    immediately thrown away; these decode in place instead.
 *
 * 2. **Calling a builtin.** Several types (`slice`, `range`, ...) have no
 *    constructor function in this project's `EmbedAPI` surface, so they are
 *    built by calling the builtin of the same name. [callBuiltinStealing]
 *    concentrates the reference bookkeeping that this requires in one place.
 *
 * Every function here assumes nothing about the GIL: each acquires it via
 * [Python3.withPython], which nests.
 */

/**
 * `self.<attr>`, decoded as UTF-8 text.
 *
 * Throws if the attribute is missing or is not a `str` -- use
 * [attrAsStringOrNull] where either is a legitimate answer (`__doc__` is
 * `None` on an undocumented object, for instance).
 */
internal fun PyObject.attrAsString(attr: String): String = Python3.withPython {
    // PyObject_GetAttrString: new reference on success, null + AttributeError on failure.
    val attrPointer = PyObject_GetAttrString(pointer, attr)
        ?: throw pyErrorOrGeneric("Attribute '$attr' not found")
    try {
        // PyUnicode_AsUTF8 hands back a view of the str's own buffer -- nothing extra to release,
        // but the str itself must outlive the decode, which is why the release is in the finally.
        PyUnicode_AsUTF8(attrPointer) ?: throw pyErrorOrGeneric("Attribute '$attr' is not a str")
    } finally {
        Py_DecRef(attrPointer)
    }
}

/**
 * `self.<attr>` as UTF-8 text, or `null` when the attribute is absent or is
 * not a `str` (in particular when it is `None`).
 *
 * Both of those set CPython's error indicator, and this is the variant whose
 * caller has opted out of exceptions, so the indicator is cleared rather than
 * left pending -- see [PyObject.getAttrOrNull] for why leaving one set
 * corrupts whatever call runs next.
 */
internal fun PyObject.attrAsStringOrNull(attr: String): String? = Python3.withPython {
    val attrPointer = PyObject_GetAttrString(pointer, attr) ?: run {
        PyErr_Clear()
        return@withPython null
    }
    try {
        val decoded = PyUnicode_AsUTF8(attrPointer)
        if (decoded == null) PyErr_Clear()
        decoded
    } finally {
        Py_DecRef(attrPointer)
    }
}

/** `self.<attr>` as an `Int`. Throws if the attribute is missing or is not an integer. */
internal fun PyObject.attrAsInt(attr: String): Int = Python3.withPython {
    val attrPointer = PyObject_GetAttrString(pointer, attr)
        ?: throw pyErrorOrGeneric("Attribute '$attr' not found")
    try {
        val value = PyLong_AsInt(attrPointer)
        // -1 is both a perfectly good value and the failure return; only the error indicator
        // tells the two apart.
        if (value == -1 && PyErr_Occurred() != null) {
            throw pyErrorOrGeneric("Attribute '$attr' is not an int")
        }
        value
    } finally {
        Py_DecRef(attrPointer)
    }
}

/**
 * `self.<attr>` as an `Int`, or `null` when the attribute is `None`.
 *
 * `slice.start` and friends are `None` rather than absent when omitted, so
 * `None` is the expected answer here and maps to `null`; anything else that
 * is not an integer is still an error.
 */
internal fun PyObject.attrAsIntOrNull(attr: String): Int? = Python3.withPython {
    val attrPointer = PyObject_GetAttrString(pointer, attr) ?: run {
        PyErr_Clear()
        return@withPython null
    }
    try {
        if (attrPointer == PyNone.get().pointer) return@withPython null
        val value = PyLong_AsInt(attrPointer)
        if (value == -1 && PyErr_Occurred() != null) {
            throw pyErrorOrGeneric("Attribute '$attr' is neither an int nor None")
        }
        value
    } finally {
        Py_DecRef(attrPointer)
    }
}

/**
 * Reads `self.<attr>` and hands the resulting **new reference** to [wrap],
 * which adopts it (i.e. is expected to be a `PyXxx(pointer, borrowed = false)`
 * construction).
 *
 * Deliberately *not* routed through [adoptingNewReference]: [wrap] builds a
 * [python.multiplatform.ref.PyAutoCloseable], and that registers its cleaner
 * in its own constructor, before any subclass initialiser runs. A wrapper
 * that threw from `init` would therefore have already queued a release for
 * this pointer, and releasing it here as well is exactly the double-free
 * ROADMAP §1 is the account of.
 */
internal inline fun <T : PyObject> PyObject.attrAs(attr: String, wrap: (NativePointer) -> T): T {
    val attrPointer = Python3.withPython { PyObject_GetAttrString(pointer, attr) }
        ?: throw pyErrorOrGeneric("Attribute '$attr' not found")
    return wrap(attrPointer)
}

/**
 * Calls `builtins.<name>(*args)` and returns the **new reference** the call produced.
 *
 * [args] are **consumed**: each must be a new reference the caller owns, and
 * this takes ownership of every one of them on all paths -- they are handed
 * to the argument tuple, which steals them, and the tuple is released here.
 * Written that way because each caller is building throwaway arguments for a
 * single call and would otherwise have to unwind them itself across the four
 * separate ways this can fail.
 *
 * The builtin is looked up in the builtins *namespace* rather than through
 * `PyImport_ImportModule("builtins")` so that no module wrapper (and no
 * cleaner registration) is created for what is a single dictionary lookup.
 *
 * [args] is a plain array rather than a `vararg` because Kotlin prohibits a
 * value class as a `vararg` element type, and [NativePointer] is one.
 */
internal fun callBuiltinStealing(name: String, args: Array<NativePointer>): NativePointer = Python3.withPython {
    val argTuple = PyTuple_New(args.size.toLong())
    if (argTuple == null) {
        for (arg in args) Py_DecRef(arg)
        throw pyErrorOrGeneric("Failed to build the argument tuple for builtins.$name()")
    }
    try {
        for (i in args.indices) {
            // PyTuple_SetItem steals, and releases the value itself when it fails, so on failure
            // only the arguments not yet handed over are still ours to release.
            if (PyTuple_SetItem(argTuple, i.toLong(), args[i]) != 0) {
                for (j in i + 1 until args.size) Py_DecRef(args[j])
                throw pyErrorOrGeneric("Failed to populate the argument tuple for builtins.$name()")
            }
        }
        // PyEval_GetBuiltins and PyDict_GetItemString both return BORROWED references.
        val builtins = PyEval_GetBuiltins() ?: throw pyErrorOrGeneric("The builtins namespace is unavailable")
        val callable = PyDict_GetItemString(builtins, name)
            ?: throw pyErrorOrGeneric("builtins.$name is not available")
        PyObject_CallObject(callable, argTuple) ?: throw pyErrorOrGeneric("builtins.$name() failed")
    } finally {
        Py_DecRef(argTuple)
    }
}

/**
 * Builds one new `int` reference per element of [values], mapping `null` to a
 * new reference to `None`.
 *
 * The loop releases whatever it has already built if a later element fails:
 * between creating a reference and handing it to [callBuiltinStealing] it
 * belongs to no Kotlin object, so nothing else would ever release it.
 */
internal fun newIntsOrNone(vararg values: Int?): Array<NativePointer> = Python3.withPython {
    val built = ArrayList<NativePointer>(values.size)
    try {
        for (value in values) {
            built.add(
                if (value == null) {
                    // PyNone.get() holds a borrowed-then-increfed reference of its own; the
                    // caller of this function needs one it can hand away, so take another.
                    PyNone.get().pointer.also { Py_IncRef(it) }
                } else {
                    PyLong_FromLongLong(value.toLong()) ?: throw pyErrorOrGeneric("Failed to build a Python int")
                }
            )
        }
    } catch (t: Throwable) {
        for (pointer in built) Py_DecRef(pointer)
        throw t
    }
    built.toTypedArray()
}
