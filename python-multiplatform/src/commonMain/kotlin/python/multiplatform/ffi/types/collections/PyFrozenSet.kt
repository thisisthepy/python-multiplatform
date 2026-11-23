package python.multiplatform.ffi.types.collections

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.conversion.PyProxy
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.types.iteration.PyIterable
import python.multiplatform.ffi.types.iteration.PyIterator
import python.multiplatform.ffi.types.iteration.SnapshotIterator
import python.native.ffi.NativePointer
import python.native.ffi.PyFrozenSet_New
import python.native.ffi.PyNumber_And
import python.native.ffi.PyNumber_Or
import python.native.ffi.PyNumber_Subtract
import python.native.ffi.PySet_Contains
import python.native.ffi.PySet_Size
import python.native.ffi.PyTuple_New
import python.native.ffi.PyTuple_SetItem
import python.native.ffi.Py_DecRef
import python.native.ffi.Py_IncRef

/**
 * Wrapper around a Python `frozenset` object. Not present in the mermaid
 * sketch at all, but explicitly requested by the task; modelled as the
 * read-only counterpart to [PySet] (a [PyIterable] and `Set<Any?>` rather than
 * a `PyMutableIterable`; issue #524 items 3 and 4), matching how `frozenset` relates to `set` in
 * Python itself.
 *
 * Backed by `PyFrozenSet_New`/`PySet_Contains`/`PySet_Size` (these read-only
 * query functions are shared between `set` and `frozenset` in the C API,
 * `PySet_Size` taking an "anyset" for exactly that reason), plus
 * `PyNumber_Or`/`And`/`Subtract` for the set-algebra operations, same as
 * [PySet].
 */
open class PyFrozenSet(pointer: NativePointer, borrowed: Boolean) :
    PyObject(pointer, borrowed), PyIterable, PyProxy<Set<Any?>>, Set<Any?> {

    companion object {
        /** The `PyType` for `frozenset` (`builtins.frozenset`). */
        val TYPE: PyType by lazy {
            val emptyTuple = python.multiplatform.ffi.Python3.withPython { PyTuple_New(0) }
                ?: throw PyException.fromCurrentError() ?: PyException("Failed to build tuple() scratch buffer")
            val emptyFrozenSet = python.multiplatform.ffi.Python3.withPython { PyFrozenSet_New(emptyTuple) }
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(emptyTuple) }
            if (emptyFrozenSet == null) throw PyException.fromCurrentError() ?: PyException("Failed to build frozenset() to derive its type")
            deriveTypeAndRelease(emptyFrozenSet)
        }

        /**
         * Builds a new Python `frozenset` of [elements] (the mermaid's `+fromSet(set Set)`); a [PyObject] element is
         * stored as itself, any other value is converted as [PyMutableIterable] describes.
         */
        fun fromSet(elements: Set<*>): PyFrozenSet {
            val tuplePtr = python.multiplatform.ffi.Python3.withPython { PyTuple_New(elements.size.toLong()) }
                ?: throw PyException.fromCurrentError() ?: PyException("Failed to allocate tuple() scratch buffer")
            // See PyList.fromList for why the release covers the fill loop.
            val setPtr = try {
                elements.forEachIndexed { i, el ->
                    val item = newElementReference(el) // PyTuple_SetItem steals a reference: a new one is made for it
                    python.multiplatform.ffi.Python3.withPython { PyTuple_SetItem(tuplePtr, i.toLong(), item) }
                }
                python.multiplatform.ffi.Python3.withPython { PyFrozenSet_New(tuplePtr) }
            } finally {
                python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(tuplePtr) } // scratch tuple, no longer needed once copied into the frozenset
            }
            if (setPtr == null) throw PyException.fromCurrentError() ?: PyException("Failed to build frozenset()")
            return PyFrozenSet(setPtr, false)
        }
    }

    override var cachedNativeValue: Set<Any?>? = null
    override var cachedPyObjectValue: PyObject? = null

    /**
     * Snapshot conversion to a plain Kotlin set, recursively converting elements
     * ([python.multiplatform.ffi.conversion.ConversionStrategy.NATIVE]): the mermaid's `+toSet() Set`.
     */
    fun toSet(): Set<Any?> = snapshotElements(pointer).mapTo(LinkedHashSet()) { pyObjectToNative(it) }

    /** `self | other`, via `PyNumber_Or`. */
    fun union(other: PyFrozenSet): PyFrozenSet {
        val result = python.multiplatform.ffi.Python3.withPython { PyNumber_Or(pointer, other.pointer) }
            ?: throw PyException.fromCurrentError() ?: PyException("frozenset union failed")
        return PyFrozenSet(result, false)
    }

    /** `self & other`, via `PyNumber_And`. */
    fun intersection(other: PyFrozenSet): PyFrozenSet {
        val result = python.multiplatform.ffi.Python3.withPython { PyNumber_And(pointer, other.pointer) }
            ?: throw PyException.fromCurrentError() ?: PyException("frozenset intersection failed")
        return PyFrozenSet(result, false)
    }

    /** `self - other`, via `PyNumber_Subtract`. */
    fun difference(other: PyFrozenSet): PyFrozenSet {
        val result = python.multiplatform.ffi.Python3.withPython { PyNumber_Subtract(pointer, other.pointer) }
            ?: throw PyException.fromCurrentError() ?: PyException("frozenset difference failed")
        return PyFrozenSet(result, false)
    }

    override val size: Int
        get() = python.multiplatform.ffi.Python3.withPython { PySet_Size(pointer) }.toInt()

    override fun isEmpty(): Boolean = size == 0

    override fun iterator(): PyIterator = SnapshotIterator(snapshotElements(pointer), onRemove = null)

    override fun contains(element: Any?): Boolean {
        val result = withPythonElement(element) { item -> python.multiplatform.ffi.Python3.withPython { PySet_Contains(pointer, item) } }
        if (result == -1) throw PyException.fromCurrentError() ?: PyException("Failed to check frozenset membership")
        return result == 1
    }

    override fun containsAll(elements: Collection<Any?>): Boolean = elements.all { contains(it) }
}
