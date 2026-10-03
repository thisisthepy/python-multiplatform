package python.multiplatform.ffi.types.collections

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.adoptingNewReference
import python.multiplatform.ffi.conversion.PyProxy
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.types.iteration.PyIterator
import python.native.ffi.NativePointer
import python.native.ffi.PyObject_GetIter
import python.native.ffi.PySequence_Contains
import python.native.ffi.PyTuple_GetItem
import python.native.ffi.PyTuple_GetSlice
import python.native.ffi.PyTuple_New
import python.native.ffi.PyTuple_SetItem
import python.native.ffi.PyTuple_Size
import python.native.ffi.Py_IncRef

/**
 * Wrapper around a Python `tuple` object, adopting the read-only
 * `List<PyObject>` per the mermaid sketch (`List <|.. PyTuple`); tuples are
 * immutable in Python so `MutableList` would be misleading.
 *
 * Backed directly by `PyTuple_New`/`PyTuple_GetItem`/`PyTuple_Size` --
 * unlike [PyList], the Stable ABI subset here does have dedicated
 * tuple-specific entry points for construction and element access.
 * `PyTuple_SetItem` also exists, but per CPython's own contract it is only
 * safe to use while building a tuple that has not yet been exposed/published
 * anywhere else -- it is exposed here only for [fromList]'s internal use,
 * not as a public mutator (tuples stay immutable from the outside).
 */
open class PyTuple(pointer: NativePointer, borrowed: Boolean) :
    PyObject(pointer, borrowed), PyProxy<List<Any?>>, List<PyObject> {

    companion object {
        /** The `PyType` for `tuple` (`builtins.tuple`). */
        val TYPE: PyType by lazy {
            val emptyTuple = python.multiplatform.ffi.Python3.withPython { PyTuple_New(0) }
                ?: throw PyException.fromCurrentError() ?: PyException("Failed to build tuple() to derive its type")
            deriveTypeAndRelease(emptyTuple)
        }

        /** Builds a new Python `tuple` containing (references to) [elements], in order. */
        fun fromList(elements: List<PyObject>): PyTuple {
            val ptr = python.multiplatform.ffi.Python3.withPython { PyTuple_New(elements.size.toLong()) }
                ?: throw PyException.fromCurrentError() ?: PyException("Failed to allocate tuple")
            // Nothing owns the tuple until the wrapper on the last line adopts it, so an escape
            // from the fill loop has to release it here or it is lost for good.
            return ptr.adoptingNewReference {
                elements.forEachIndexed { i, el ->
                    // PyTuple_SetItem steals the reference to its 3rd argument -- incref first so
                    // `el`'s own, independently-managed reference stays valid afterwards.
                    python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_IncRef(el.pointer) }
                    python.multiplatform.ffi.Python3.withPython { PyTuple_SetItem(it, i.toLong(), el.pointer) }
                }
                PyTuple(it, false) // PyTuple_New already returned a new/owned reference
            }
        }
    }

    override var cachedNativeValue: List<Any?>? = null
    override var cachedPyObjectValue: PyObject? = null

    /** Snapshot conversion to a plain Kotlin list, recursively converting elements ([python.multiplatform.ffi.conversion.ConversionStrategy.NATIVE]). */
    fun toNativeList(): List<Any?> {
        val count = python.multiplatform.ffi.Python3.withPython { PyTuple_Size(pointer) }
        val result = ArrayList<Any?>(count.toInt())
        for (i in 0 until count) {
            // Borrowed reference, valid while `pointer` (this tuple) is alive; wrap it with its
            // own incref'd reference before handing it to the recursive converter.
            val itemPtr = python.multiplatform.ffi.Python3.withPython { PyTuple_GetItem(pointer, i) }!!
            result.add(pyObjectToNative(PyObject(itemPtr, true)))
        }
        return result
    }

    override val size: Int
        get() = python.multiplatform.ffi.Python3.withPython { PyTuple_Size(pointer) }.toInt()

    override fun get(index: Int): PyObject {
        val itemPtr = python.multiplatform.ffi.Python3.withPython { PyTuple_GetItem(pointer, index.toLong()) }
            ?: throw PyException.fromCurrentError() ?: PyException("tuple index out of range: $index")
        // Borrowed reference -- incref so this new wrapper owns its own, independent reference.
        return PyObject(itemPtr, true)
    }

    override fun isEmpty(): Boolean = size == 0

    override fun iterator(): Iterator<PyObject> {
        val iterPtr = python.multiplatform.ffi.Python3.withPython { PyObject_GetIter(pointer) }
            ?: throw PyException.fromCurrentError() ?: PyException("Failed to get iterator for tuple")
        return PyIterator(iterPtr, false) // PyObject_GetIter returns a new reference
    }

    override fun contains(element: PyObject): Boolean {
        val result = python.multiplatform.ffi.Python3.withPython { PySequence_Contains(pointer, element.pointer) }
        if (result == -1) throw PyException.fromCurrentError() ?: PyException("Failed to check tuple membership")
        return result == 1
    }

    override fun containsAll(elements: Collection<PyObject>): Boolean = elements.all { contains(it) }

    override fun indexOf(element: PyObject): Int {
        val count = python.multiplatform.ffi.Python3.withPython { PyTuple_Size(pointer) }
        for (i in 0 until count) {
            val itemPtr = python.multiplatform.ffi.Python3.withPython { PyTuple_GetItem(pointer, i) }!!
            if (pyEquals(itemPtr, element.pointer)) return i.toInt()
        }
        return -1
    }

    override fun lastIndexOf(element: PyObject): Int {
        val count = python.multiplatform.ffi.Python3.withPython { PyTuple_Size(pointer) }
        for (i in count - 1 downTo 0) {
            val itemPtr = python.multiplatform.ffi.Python3.withPython { PyTuple_GetItem(pointer, i) }!!
            if (pyEquals(itemPtr, element.pointer)) return i.toInt()
        }
        return -1
    }

    private inner class TupleIterator(startIndex: Int) : ListIterator<PyObject> {
        private var cursor = startIndex
        override fun hasNext(): Boolean = cursor < size
        override fun hasPrevious(): Boolean = cursor > 0
        override fun nextIndex(): Int = cursor
        override fun previousIndex(): Int = cursor - 1
        override fun next(): PyObject {
            if (!hasNext()) throw NoSuchElementException()
            return get(cursor++)
        }
        override fun previous(): PyObject {
            if (!hasPrevious()) throw NoSuchElementException()
            return get(--cursor)
        }
    }

    override fun listIterator(): ListIterator<PyObject> = TupleIterator(0)
    override fun listIterator(index: Int): ListIterator<PyObject> = TupleIterator(index)

    override fun subList(fromIndex: Int, toIndex: Int): List<PyObject> {
        val slicePtr = python.multiplatform.ffi.Python3.withPython { PyTuple_GetSlice(pointer, fromIndex.toLong(), toIndex.toLong()) }
            ?: throw PyException.fromCurrentError() ?: PyException("Failed to slice tuple[$fromIndex:$toIndex]")
        return PyTuple(slicePtr, false) // PyTuple_GetSlice returns a new reference
    }
}
