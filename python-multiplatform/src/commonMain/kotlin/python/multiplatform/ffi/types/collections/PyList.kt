package python.multiplatform.ffi.types.collections

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.conversion.PyProxy
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.types.iteration.PyIterator
import python.native.ffi.NativePointer
import python.native.ffi.PyList_Append
import python.native.ffi.PyList_Insert
import python.native.ffi.PyList_Reverse
import python.native.ffi.PyList_Size
import python.native.ffi.PyList_Sort
import python.native.ffi.PyLong_FromLongLong
import python.native.ffi.PyObject_DelItem
import python.native.ffi.PyObject_GetItem
import python.native.ffi.PyObject_SetItem
import python.native.ffi.PySequence_Contains
import python.native.ffi.PySequence_List
import python.native.ffi.PyTuple_New
import python.native.ffi.PyTuple_SetItem
import python.native.ffi.Py_DecRef
import python.native.ffi.Py_IncRef

/**
 * Wrapper around a Python `list` object: a [PyMutableIterable] and a Kotlin `MutableList` (issue #524
 * items 3 and 4; the owner's `python_for_kotlin_binding.mermaid`: `PyMutableIterable <|-- PyList`,
 * `MutableList <|.. PyList`).
 *
 * Elements are `Any?`: [add], [set], [contains], [indexOf], [remove] take any value
 * [PyMutableIterable] describes; [get], [set], [removeAt] and iteration answer [PyObject]s.
 *
 * [size] and [add] (`list.insert`) are backed directly by `PyList_Size` and `PyList_Insert`. Element
 * access/mutation (`get`/`set`/`removeAt`) goes through the generic sequence/object protocol
 * (`PyObject_GetItem`/`SetItem`/`DelItem` with an integer index) rather than
 * `PyList_GetItem`/`SetItem`: those two are C-array-index-only and `PyList_SetItem` steals the
 * reference it is given.
 */
open class PyList(pointer: NativePointer, borrowed: Boolean) :
    PyMutableIterable(pointer, borrowed), PyProxy<List<Any?>>, MutableList<Any?> {

    companion object {
        /** The `PyType` for `list` (`builtins.list`). */
        val TYPE: PyType by lazy {
            val emptyTuple = python.multiplatform.ffi.Python3.withPython { PyTuple_New(0) }
                ?: throw PyException.fromCurrentError() ?: PyException("Failed to build tuple() scratch buffer")
            val emptyList = python.multiplatform.ffi.Python3.withPython { PySequence_List(emptyTuple) }
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(emptyTuple) }
            if (emptyList == null) throw PyException.fromCurrentError() ?: PyException("Failed to build list() to derive its type")
            deriveTypeAndRelease(emptyList)
        }

        /**
         * Builds a new Python `list` of [elements], in order (the mermaid's `+fromList(list List) PyList$`).
         * A [PyObject] element is stored as itself (a reference to it); any other value is converted
         * as [PyMutableIterable] describes.
         */
        fun fromList(elements: List<*>): PyList {
            val tuplePtr = python.multiplatform.ffi.Python3.withPython { PyTuple_New(elements.size.toLong()) }
                ?: throw PyException.fromCurrentError() ?: PyException("Failed to allocate tuple() scratch buffer")
            // The scratch tuple is owned by nobody until it is released here, so the release
            // covers the fill loop as well as the conversion.
            val listPtr = try {
                elements.forEachIndexed { i, el ->
                    // PyTuple_SetItem steals the reference to its 3rd argument: a new one is made for it.
                    val item = newElementReference(el)
                    python.multiplatform.ffi.Python3.withPython { PyTuple_SetItem(tuplePtr, i.toLong(), item) }
                }
                python.multiplatform.ffi.Python3.withPython { PySequence_List(tuplePtr) }
            } finally {
                python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(tuplePtr) } // scratch tuple, no longer needed once copied into the list
            }
            if (listPtr == null) throw PyException.fromCurrentError() ?: PyException("Failed to build list()")
            return PyList(listPtr, false)
        }
    }

    override var cachedNativeValue: List<Any?>? = null
    override var cachedPyObjectValue: PyObject? = null

    /**
     * Snapshot conversion to a plain Kotlin list, recursively converting elements
     * ([python.multiplatform.ffi.conversion.ConversionStrategy.NATIVE]): the mermaid's `+toList() List`.
     * A member, so it is what `pyList.toList()` calls (the standard library's `Iterable.toList()`, which
     * would copy the `PyObject` elements, is reachable as `Iterable<Any?>.toList()` by a cast).
     */
    fun toList(): List<Any?> = indices.map { pyObjectToNative(get(it)) }

    /** `list.sort()`, backed by `PyList_Sort`. */
    fun sort() {
        if (python.multiplatform.ffi.Python3.withPython { PyList_Sort(pointer) } != 0) throw PyException.fromCurrentError() ?: PyException("list.sort() failed")
    }

    /** `list.reverse()`, backed by `PyList_Reverse`. */
    fun reverse() {
        if (python.multiplatform.ffi.Python3.withPython { PyList_Reverse(pointer) } != 0) throw PyException.fromCurrentError() ?: PyException("list.reverse() failed")
    }

    override val size: Int
        get() = python.multiplatform.ffi.Python3.withPython { PyList_Size(pointer) }.toInt()

    private fun indexKey(index: Int): NativePointer =
        python.multiplatform.ffi.Python3.withPython { PyLong_FromLongLong(index.toLong()) } ?: throw PyException.fromCurrentError() ?: PyException("Failed to build index object")

    override fun get(index: Int): PyObject {
        val key = indexKey(index)
        val item = python.multiplatform.ffi.Python3.withPython { PyObject_GetItem(pointer, key) }
        python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(key) } // scratch index object, new reference, only needed for the lookup itself
        if (item == null) throw PyException.fromCurrentError() ?: PyException("list index out of range: $index")
        return PyObject(item, false) // PyObject_GetItem ("o[key]") returns a new reference
    }

    override fun set(index: Int, element: Any?): PyObject {
        val previous = get(index)
        val key = indexKey(index)
        // PyObject_SetItem does not steal a reference to `element`.
        val rc = withPythonElement(element) { item -> python.multiplatform.ffi.Python3.withPython { PyObject_SetItem(pointer, key, item) } }
        python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(key) }
        if (rc != 0) throw PyException.fromCurrentError() ?: PyException("Failed to set list index $index")
        return previous
    }

    override fun removeAt(index: Int): PyObject {
        val previous = get(index)
        val key = indexKey(index)
        val rc = python.multiplatform.ffi.Python3.withPython { PyObject_DelItem(pointer, key) }
        python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(key) }
        if (rc != 0) throw PyException.fromCurrentError() ?: PyException("Failed to remove list index $index")
        return previous
    }

    override fun add(element: Any?): Boolean {
        // PyList_Append does not steal a reference to `item` (it increfs internally),
        // unlike PyList_SetItem/PyTuple_SetItem.
        if (withPythonElement(element) { item -> python.multiplatform.ffi.Python3.withPython { PyList_Append(pointer, item) } } != 0) {
            throw PyException.fromCurrentError() ?: PyException("Failed to append to list")
        }
        return true
    }

    override fun add(index: Int, element: Any?) {
        // PyList_Insert does not steal a reference to `item`, same as PyList_Append.
        if (withPythonElement(element) { item -> python.multiplatform.ffi.Python3.withPython { PyList_Insert(pointer, index.toLong(), item) } } != 0) {
            throw PyException.fromCurrentError() ?: PyException("list.insert($index, ...) failed")
        }
    }

    override fun addAll(index: Int, elements: Collection<Any?>): Boolean {
        if (elements.isEmpty()) return false
        elements.forEachIndexed { i, el -> add(index + i, el) }
        return true
    }

    override fun addAll(elements: Collection<Any?>): Boolean {
        if (elements.isEmpty()) return false
        for (el in elements) add(el)
        return true
    }

    override fun clear() {
        // No PyList_Clear in this ABI subset either; pop from the end via the generic protocol.
        while (size > 0) removeAt(size - 1)
    }

    override fun isEmpty(): Boolean = size == 0

    override fun remove(element: Any?): Boolean {
        val idx = indexOf(element)
        if (idx == -1) return false
        removeAt(idx)
        return true
    }

    override fun removeAll(elements: Collection<Any?>): Boolean {
        var changed = false
        for (e in elements) {
            while (remove(e)) changed = true
        }
        return changed
    }

    override fun retainAll(elements: Collection<Any?>): Boolean {
        var changed = false
        var i = size - 1
        while (i >= 0) {
            val el = get(i)
            if (elements.none { other -> withPythonElement(other) { pyEquals(it, el.pointer) } }) {
                removeAt(i)
                changed = true
            }
            i--
        }
        return changed
    }

    override fun contains(element: Any?): Boolean {
        val result = withPythonElement(element) { item -> python.multiplatform.ffi.Python3.withPython { PySequence_Contains(pointer, item) } }
        if (result == -1) throw PyException.fromCurrentError() ?: PyException("Failed to check list membership")
        return result == 1
    }

    override fun containsAll(elements: Collection<Any?>): Boolean = elements.all { contains(it) }

    override fun indexOf(element: Any?): Int = withPythonElement(element) { item ->
        for (i in 0 until size) {
            if (pyEquals(get(i).pointer, item)) return@withPythonElement i
        }
        -1
    }

    override fun lastIndexOf(element: Any?): Int = withPythonElement(element) { item ->
        for (i in size - 1 downTo 0) {
            if (pyEquals(get(i).pointer, item)) return@withPythonElement i
        }
        -1
    }

    /**
     * Index-based cursor over `this` list, driven entirely from the Kotlin
     * side via [get]/[set]/[add]/[removeAt] rather than a live raw CPython
     * iterator -- mutating a Python collection while iterating it directly
     * (via `PyIter_Next` on a `list_iterator`) is undefined behaviour in
     * CPython, so `remove()`/`add()` here never touch one.
     */
    private inner class ListItr(startIndex: Int) : MutableListIterator<Any?>, PyIterator {
        private var cursor = startIndex
        private var lastReturned = -1

        override fun hasNext(): Boolean = cursor < size
        override fun hasPrevious(): Boolean = cursor > 0
        override fun nextIndex(): Int = cursor
        override fun previousIndex(): Int = cursor - 1

        override fun next(): PyObject {
            if (!hasNext()) throw NoSuchElementException()
            val el = get(cursor)
            lastReturned = cursor
            cursor++
            return el
        }

        override fun previous(): PyObject {
            if (!hasPrevious()) throw NoSuchElementException()
            cursor--
            lastReturned = cursor
            return get(cursor)
        }

        override fun remove() {
            check(lastReturned != -1) { "next()/previous() has not been called, or remove()/add() already called" }
            removeAt(lastReturned)
            if (lastReturned < cursor) cursor--
            lastReturned = -1
        }

        override fun set(element: Any?) {
            check(lastReturned != -1) { "next()/previous() has not been called, or remove()/add() already called" }
            this@PyList.set(lastReturned, element)
        }

        override fun add(element: Any?) {
            this@PyList.add(cursor, element)
            cursor++
            lastReturned = -1
        }
    }

    override fun iterator(): PyIterator = ListItr(0)
    override fun listIterator(): MutableListIterator<Any?> = ListItr(0)
    override fun listIterator(index: Int): MutableListIterator<Any?> = ListItr(index)

    private class PySubList(
        private val list: PyList,
        private val fromIndex: Int,
        private var toIndex: Int
    ) : AbstractMutableList<Any?>() {
        
        init {
            if (fromIndex < 0 || toIndex > list.size) {
                throw IndexOutOfBoundsException("fromIndex: $fromIndex, toIndex: $toIndex, size: ${list.size}")
            }
            if (fromIndex > toIndex) {
                throw IllegalArgumentException("fromIndex: $fromIndex > toIndex: $toIndex")
            }
        }

        override val size: Int
            get() = toIndex - fromIndex

        override fun get(index: Int): PyObject {
            if (index < 0 || index >= size) throw IndexOutOfBoundsException("index: $index, size: $size")
            return list[fromIndex + index]
        }

        override fun set(index: Int, element: Any?): PyObject {
            if (index < 0 || index >= size) throw IndexOutOfBoundsException("index: $index, size: $size")
            return list.set(fromIndex + index, element)
        }

        override fun add(index: Int, element: Any?) {
            if (index < 0 || index > size) throw IndexOutOfBoundsException("index: $index, size: $size")
            list.add(fromIndex + index, element)
            toIndex++
        }

        override fun removeAt(index: Int): PyObject {
            if (index < 0 || index >= size) throw IndexOutOfBoundsException("index: $index, size: $size")
            val removed = list.removeAt(fromIndex + index)
            toIndex--
            return removed
        }
    }

    override fun subList(fromIndex: Int, toIndex: Int): MutableList<Any?> {
        return PySubList(this, fromIndex, toIndex)
    }
}
