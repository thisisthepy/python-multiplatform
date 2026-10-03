package python.multiplatform.ffi.types.modules

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.pyErrorOrGeneric
import python.multiplatform.ffi.types.basic.PyNone
import python.multiplatform.ffi.types.collections.PyDict
import python.multiplatform.ffi.types.iteration.PyIterator
import python.native.ffi.NativePointer
import python.native.ffi.PyErr_Clear
import python.native.ffi.PyModule_GetDict
import python.native.ffi.PyModule_GetFilenameObject
import python.native.ffi.PyModule_GetName
import python.native.ffi.PyObject_GetIter
import python.native.ffi.PyObject_Length
import python.native.ffi.PyObject_Type

/**
 * Wrapper around a Python module object (whatever
 * [python.multiplatform.ffi.Python3.import] / `PyImport_ImportModule`
 * returns).
 *
 * Attribute access (`module.attr`) is already covered by the inherited
 * [PyObject.getAttr]/[PyObject.setAttr]; `name`/`dict`/`file` are instead
 * backed directly by `PyModule_GetName`/`PyModule_GetDict`/
 * `PyModule_GetFilenameObject` -- one FFI crossing each (`PyModule_GetName`
 * needs none at all beyond that, since it hands back an already-decoded
 * `const char*` rather than a `PyObject*` requiring a further
 * `PyUnicode_AsUTF8` call) instead of the two-crossing generic attribute
 * path (`PyObject_GetAttrString` + a `PyUnicode_AsUTF8`/`toString()` decode).
 * `doc` still goes through `getAttrOrNull("__doc__")`: there is no
 * `PyModule_GetDoc`-equivalent direct accessor in this ABI subset.
 */
open class PyModule(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed) {

    /** `module.__name__`, backed directly by `PyModule_GetName`. */
    val name: String
        get() = python.multiplatform.ffi.Python3.withPython { PyModule_GetName(pointer) }
            ?: throw PyException.fromCurrentError() ?: PyException("Failed to get the module's __name__")

    /** `module.__doc__`, or `null` if the module has none. */
    val doc: String?
        get() {
            val docObj = getAttrOrNull("__doc__") ?: return null
            if (PyNone.isNone(docObj)) {
                return null
            }
            return docObj.toString()
        }

    /** `module.__file__`, or `null` for built-in/frozen modules. */
    val file: String?
        get() {
            // PyModule_GetFilenameObject: new reference on success; NULL + AttributeError set
            // if the module has no __file__ (e.g. built-in/frozen modules) -- that failure mode
            // is expected here, so clear the indicator rather than propagate it as an exception.
            val filePtr = python.multiplatform.ffi.Python3.withPython { PyModule_GetFilenameObject(pointer) } ?: run {
                python.multiplatform.ffi.Python3.withPython { PyErr_Clear() }
                return null
            }
            val fileObj = PyObject(filePtr, false)
            if (PyNone.isNone(fileObj)) {
                return null
            }
            return fileObj.toString()
        }

    /** `module.__dict__`: the module's namespace, backed directly by `PyModule_GetDict`. */
    val dict: PyDict
        get() {
            // PyModule_GetDict returns a *borrowed* reference; borrowed = true here makes this
            // PyDict wrapper take its own, independent, incref'd reference to it.
            val dictPtr = python.multiplatform.ffi.Python3.withPython { PyModule_GetDict(pointer) }
                ?: throw PyException.fromCurrentError() ?: PyException("Failed to get the module's __dict__")
            return PyDict(dictPtr, true)
        }

    /**
     * Looks up [name] in this module's namespace, throwing [PyException] if
     * it is not defined -- equivalent to [PyObject.getAttr] but named to
     * match how one thinks about "a symbol from a module".
     */
    @Throws(PyException::class)
    fun get(name: String): PyObject = getAttr(name)
}

/**
 * Thin, curated view over the `builtins` module (the implicit namespace that
 * backs unqualified names like `len`, `print`, `type` in Python source).
 *
 * Left minimal on purpose: everything it would expose is already reachable
 * generically via [PyModule.get] on the `builtins` module returned by
 * [python.multiplatform.ffi.Python3.import]; this only exists as a
 * convenience surface for the handful of builtins the library itself leans
 * on internally (e.g. `len`, `iter`, `type` when implementing the bodies of
 * the collection/iterator wrappers).
 */
class Builtins(
    /**
     * The `builtins` module this view was constructed over.
     *
     * Public rather than private because none of the three functions below
     * actually goes through it (see the note on each), so anything else a
     * caller wants from `builtins` still has to be reachable:
     * `builtins.module.get("sorted")`.
     */
    val module: PyModule
) {
    /**
     * `len(obj)`.
     *
     * Implemented as `PyObject_Length` rather than as a call to the `len`
     * builtin. They are the same operation -- CPython's `builtins.len` is a
     * thin wrapper over exactly this -- but going direct costs one FFI
     * crossing and skips the Python-level call, the argument tuple and the
     * intermediate `int` object the call would otherwise produce.
     */
    fun len(obj: PyObject): Int {
        val length = python.multiplatform.ffi.Python3.withPython { PyObject_Length(obj.pointer) }
        // PyObject_Length returns -1 with the error indicator set for anything without __len__.
        if (length < 0) throw pyErrorOrGeneric("len() failed")
        return length.toInt()
    }

    /** `iter(obj)`. Uses `PyObject_GetIter` for the same reason [len] uses `PyObject_Length`. */
    fun iter(obj: PyObject): PyIterator {
        // PyObject_GetIter returns a new reference, which the PyIterator adopts.
        val iterPointer = python.multiplatform.ffi.Python3.withPython { PyObject_GetIter(obj.pointer) }
            ?: throw pyErrorOrGeneric("iter() failed")
        return PyIterator(iterPointer, borrowed = false)
    }

    /**
     * `type(obj)`.
     *
     * Returns a fresh wrapper owning its own reference, so the caller may
     * close it. [PyObject.Type] answers the same question with a cached,
     * interned [python.multiplatform.ffi.PyType] instead -- prefer that one
     * unless an independently-owned wrapper is actually wanted, and do not
     * close what it hands back.
     */
    fun type(obj: PyObject): PyObject {
        // PyObject_Type returns a new reference.
        val typePointer = python.multiplatform.ffi.Python3.withPython { PyObject_Type(obj.pointer) }
            ?: throw pyErrorOrGeneric("type() failed")
        return PyObject(typePointer, borrowed = false)
    }
}
