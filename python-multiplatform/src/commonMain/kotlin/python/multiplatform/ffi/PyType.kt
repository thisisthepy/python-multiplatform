package python.multiplatform.ffi

import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.exceptions.errors.PyTypeError
import python.multiplatform.ffi.types.collections.PyDict
import python.multiplatform.ffi.types.iteration.PyIterator
import python.native.ffi.NativePointer
import python.native.ffi.PyDict_New
import python.native.ffi.PyDict_Update
import python.native.ffi.PyErr_Clear
import python.native.ffi.PyObject_GetAttrString
import python.native.ffi.PyObject_GetIter
import python.native.ffi.PyObject_IsInstance
import python.native.ffi.PyObject_Type
import python.native.ffi.PyTuple_GetItem
import python.native.ffi.PyTuple_Size
import python.native.ffi.PyType_GetName
import python.native.ffi.PyType_IsSubtype
import python.native.ffi.PyUnicode_AsUTF8
import python.native.ffi.Py_DecRef
import python.native.ffi.Py_IncRef


class PyType private constructor(pointer: NativePointer): PyObject(pointer, false) {
    companion object {
        private val cachedObjects: MutableMap<NativePointer, PyType> = mutableMapOf()

        /**
         * The [PyType] for [pointer], which the caller must hand over as a **new reference**.
         *
         * Ownership is settled here in every case, which it previously was not:
         *
         * - cache miss: the constructed [PyType] adopts the reference and, being cached for the
         *   life of the interpreter, holds it forever.
         * - cache hit: the cached instance already owns one, so the caller's is released here.
         *   It used to be dropped on the floor instead, on the argument that types are immortal
         *   builtins -- true of `int` and `list`, false of every user-defined class, which then
         *   leaked one reference per `PyObject.Type` read.
         * - not a type at all: rejected *before* anything is constructed, so no cleaner has been
         *   registered for [pointer] and the caller's `adoptingNewReference` is the only thing
         *   that will release it. Validating inside `init` instead would leave a queued release
         *   behind on the half-built object and turn that into a double free.
         */
        fun getInstance(pointer: NativePointer): PyType {
            cachedObjects[pointer]?.let { cached ->
                releaseUnownedReference(pointer)
                return cached
            }
            if (!isTypeObject(pointer)) throw PyException("Object is not a type")
            val created = PyType(pointer)
            cachedObjects[pointer] = created
            return created
        }

        private fun isTypeObject(pointer: NativePointer): Boolean {
            // A "type" in the CPython sense is an object whose own type ("meta-type")
            // is `type` (or, in principle, a metaclass derived from it -- but the
            // FFI surface here has no PyType_Check/PyType_IsSubtype-friendly handle
            // on the builtin `type` object itself to test against, so this checks
            // the meta-type's __name__ instead of `pointer`'s own __name__, which
            // is the bug this fixes: reading `pointer.__name__` rejects every
            // legitimate type, since e.g. `int.__name__` is "int", never "type").
            val metaTypePointer: NativePointer = python.multiplatform.ffi.Python3.withPython { PyObject_Type(pointer) } ?: run {
                // PyObject_Type basically never fails (every live object has a
                // type), but if it somehow did, it would leave the error
                // indicator set; this reports "not a type" rather than
                // propagating, so the indicator must be cleared here too --
                // otherwise it would silently poison whatever Python call runs
                // next (see the *OrNull helpers on PyObject for the same class
                // of bug).
                python.multiplatform.ffi.Python3.withPython { PyErr_Clear() }
                return false
            }
            // Both new references below are released in a finally, so a throw out of the decode
            // (or out of withPython, after finalisation) cannot strand either of them.
            try {
                val metaTypeNamePointer: NativePointer? =
                    python.multiplatform.ffi.Python3.withPython { PyObject_GetAttrString(metaTypePointer, "__name__") }
                if (metaTypeNamePointer == null) {
                    python.multiplatform.ffi.Python3.withPython { PyErr_Clear() }
                    return false
                }
                val metaTypeName: String? = try {
                    python.multiplatform.ffi.Python3.withPython { PyUnicode_AsUTF8(metaTypeNamePointer) }
                } finally {
                    python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(metaTypeNamePointer) }
                }
                return metaTypeName == "type"
            } finally {
                python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(metaTypePointer) }
            }
        }
    }

    val name: String by lazy {
        // PyType_GetName: new reference on success.
        val namePtr: NativePointer = python.multiplatform.ffi.Python3.withPython { PyType_GetName(pointer) } ?: throw pyErrorOrGeneric("Failed to get the type's name")
        val nameStr: String? = try {
            python.multiplatform.ffi.Python3.withPython { PyUnicode_AsUTF8(namePtr) }
        } finally {
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(namePtr) }
        }
        nameStr ?: throw pyErrorOrGeneric("Failed to decode the type's name")
    }

    val baseType: PyType by lazy {
        // PyObject_GetAttrString: new reference; getInstance() takes ownership
        // of it when not already cached (see the comment on `getInstance`).
        val attrPtr: NativePointer = python.multiplatform.ffi.Python3.withPython { PyObject_GetAttrString(pointer, "__base__") }
            ?: throw pyErrorOrGeneric("Failed to get __base__")
        // `object.__base__` is None, which getInstance rejects -- so this reference genuinely
        // does need releasing on the throw path, not merely in theory.
        attrPtr.adoptingNewReference { getInstance(it) }
    }

    /** `type.__bases__`, i.e. the type's immediate (non-transitive) base classes. */
    val baseTypes: List<PyType>
        get() = tupleAttrAsTypes("__bases__")

    /** Method Resolution Order, i.e. `type.__mro__` -- the linearised lookup order for attributes. */
    val mro: List<PyType> by lazy {
        tupleAttrAsTypes("__mro__")
    }

    /** Reads a tuple-valued attribute (e.g. `__bases__`, `__mro__`) off this type and wraps each entry as a [PyType]. */
    private fun tupleAttrAsTypes(attrName: String): List<PyType> {
        // PyObject_GetAttrString: new reference to the tuple.
        val tuplePointer: NativePointer = python.multiplatform.ffi.Python3.withPython { PyObject_GetAttrString(pointer, attrName) }
            ?: throw pyErrorOrGeneric("Failed to get $attrName")
        try {
            val size = python.multiplatform.ffi.Python3.withPython { PyTuple_Size(tuplePointer) }
            if (size == -1L) throw pyErrorOrGeneric("Failed to get the size of $attrName")

            val list = ArrayList<PyType>(size.toInt())
            for (i in 0 until size) {
                // PyTuple_GetItem returns a *borrowed* reference; getInstance()
                // requires a new one, so give it its own +1. It settles that
                // reference either way now -- adopting it on a cache miss and
                // releasing it on a cache hit.
                val itemPointer = python.multiplatform.ffi.Python3.withPython { PyTuple_GetItem(tuplePointer, i) }
                    ?: throw pyErrorOrGeneric("Failed to get $attrName[$i]")
                python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_IncRef(itemPointer) }
                list.add(itemPointer.adoptingNewReference { getInstance(it) })
            }
            return list
        } finally {
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(tuplePointer) }
        }
    }

    /**
     * The type's own namespace, as a **snapshot copy** of `type.__dict__`.
     *
     * Two things force the copy rather than a wrapper over the pointer `__dict__` returns.
     *
     * A type's `__dict__` is a **`mappingproxy`**, not a `dict`. Wrapping that pointer in a
     * [PyDict] does not fail loudly, it fails silently and then corrupts: `PyDict_Size` and
     * `PyDict_Items` reject a non-dict with `PyErr_BadInternalCall()`, returning `-1`/`NULL`
     * *and leaving the error indicator set*, so the call that reports the problem is some
     * unrelated Python call further down. That was measured, not reasoned about -- reading
     * `int.__dict__` gave `size == -1` and the next `Python3.eval` in the suite died with
     * `Objects/dictobject.c:4248: bad argument to internal function`.
     * `PyTypeTest.readingDictLeavesNoPendingError` is the guard against it coming back.
     *
     * `PyType_GetDict()`, which an earlier note here suggested instead, is not an option: it is
     * declared in `cpython/object.h`, i.e. outside the Limited API, so it is not in the Stable
     * ABI this binding restricts itself to. `PyObject_GetAttrString` + `PyDict_Update` are, and
     * `mappingproxy` supports the `keys()`/`__getitem__` protocol `PyDict_Update` needs.
     *
     * A snapshot loses nothing a live view would have given: `mappingproxy` is read-only, so
     * mutation through this wrapper was never going to reach the type. It *is* a point-in-time
     * copy, so re-read the property after `setattr` on the type.
     */
    val dict: PyDict by lazy {
        // PyObject_GetAttrString: new reference to the mappingproxy; released unconditionally
        // below, since only the copy outlives this block.
        val proxyPointer: NativePointer = python.multiplatform.ffi.Python3.withPython { PyObject_GetAttrString(pointer, "__dict__") }
            ?: throw pyErrorOrGeneric("Failed to get __dict__")
        try {
            val copyPointer: NativePointer = python.multiplatform.ffi.Python3.withPython { PyDict_New() }
                ?: throw pyErrorOrGeneric("Failed to allocate a dict to copy __dict__ into")
            // The copy is a bare pointer until PyDict adopts it, and PyDict_Update runs arbitrary
            // Python (mappingproxy.keys(), __getitem__) and so can fail -- see ROADMAP §4.
            copyPointer.adoptingNewReference {
                if (python.multiplatform.ffi.Python3.withPython { PyDict_Update(it, proxyPointer) } != 0) {
                    throw pyErrorOrGeneric("Failed to copy __dict__ into a dict")
                }
                PyDict(it, false)
            }
        } finally {
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(proxyPointer) }
        }
    }

    /** `python.multiplatform.ffi.Python3.withPython { PyType_IsSubtype(this, other) }`, i.e. Python's `issubclass(self, other)`. */
    fun isSubtypeOf(other: PyType): Boolean = python.multiplatform.ffi.Python3.withPython { PyType_IsSubtype(pointer, other.pointer) } != 0

    /**
     * Attempts to view [obj] as an instance of this type, raising [PyException]
     * (mirroring a Python `TypeError`) if it is not an instance of, or convertible to, this type.
     */
    @Throws(PyException::class)
    fun cast(obj: PyObject): PyObject {
        if (!isInstance(obj)) {
            throw PyTypeError("Object is not an instance of '$name'")
        }
        return obj
    }

    /** `python.multiplatform.ffi.Python3.withPython { PyObject_IsInstance(obj, this) }`, i.e. Python's `isinstance(obj, self)`. */
    fun isInstance(obj: PyObject): Boolean {
        val result = python.multiplatform.ffi.Python3.withPython { PyObject_IsInstance(obj.pointer, pointer) }
        if (result < 0) throw pyErrorOrGeneric("isinstance() check failed")
        return result != 0
    }

    /** `PyObject_GetIter` applied to an instance of this type, when this type describes an iterable. */
    fun getIterator(): PyIterator {
        // Constructs a fresh, no-argument instance of this type and returns its iterator.
        val instance = invoke()
        val iterPointer = python.multiplatform.ffi.Python3.withPython { PyObject_GetIter(instance.pointer) }
            ?: throw pyErrorOrGeneric("Instances of '$name' are not iterable")
        return PyIterator(iterPointer, false)
    }

    /**
     * Calls this type object, i.e. constructs a new instance: `self(*args, **kwargs)`.
     *
     * Type objects are themselves callable -- `self(...)` runs `type.__call__`, i.e. `__new__`
     * followed by `__init__` -- so this just names the operation; the arity-specific [invoke]
     * overloads and [call] inherited from [PyObject] do the work.
     */
    @Throws(PyException::class)
    override fun call(args: Array<out PyObject>, kwargs: Map<String, PyObject>): PyObject =
        super.call(args, kwargs)

    /** `type.__new__(self)` -- allocates (but does not initialise) a new instance of this type. */
    fun __new__(): PyObject {
        // `__new__` is a static method taking the class as its first argument;
        // there's no dedicated tp_new FFI entry point here, so go through the
        // normal attribute + call path (`type.__new__(self)`).
        return getAttr("__new__").invoke(this)
    }

    /** `type.__init__(obj, *args)` -- initialises an already-allocated [obj] in place. */
    fun __init__(obj: PyObject, vararg args: PyObject) {
        obj.getAttr("__init__").invoke(*args)
    }

    override fun hashCode(): Int {
        return super.hashCode()
    }
}