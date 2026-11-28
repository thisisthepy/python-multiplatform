package python.multiplatform.ffi.types.collections

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.conversion.PyProxy
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.types.iteration.PyIterator
import python.multiplatform.ffi.types.iteration.SnapshotIterator
import python.native.ffi.NativePointer
import python.native.ffi.PyNumber_And
import python.native.ffi.PyNumber_Or
import python.native.ffi.PyNumber_Subtract
import python.native.ffi.PySet_Add
import python.native.ffi.PySet_Clear
import python.native.ffi.PySet_Contains
import python.native.ffi.PySet_Discard
import python.native.ffi.PySet_New
import python.native.ffi.PySet_Pop
import python.native.ffi.PySet_Size
import python.native.ffi.PyTuple_New
import python.native.ffi.PyTuple_SetItem
import python.native.ffi.Py_DecRef
import python.native.ffi.Py_IncRef

/**
 * Wrapper around a Python `set` object: a [PyMutableIterable] and a Kotlin `MutableSet` (issue #524
 * items 3 and 4; the owner's mermaid: `PyMutableIterable <|-- PySet`, `MutableSet <|.. PySet`).
 * Elements are `Any?` on the way in and [PyObject]s on the way out.
 *
 * Backed by `PySet_New`/`PySet_Add`/`PySet_Discard`/`PySet_Contains`/
 * `PySet_Pop`/`PySet_Clear`/`PySet_Size` (the last backs [size] directly),
 * plus `PyNumber_Or`/`And`/`Subtract` for the
 * set-algebra operations (CPython dispatches `|`/`&`/`-` on `set` through
 * the generic number protocol) and `PySequence_Tuple` (via
 * [snapshotElements]) for iteration, since sets have no indexed access.
 */
open class PySet(pointer: NativePointer, borrowed: Boolean) :
    PyMutableIterable(pointer, borrowed), PyProxy<Set<Any?>>, MutableSet<Any?> {

    companion object {
        /** The `PyType` for `set` (`builtins.set`). */
        val TYPE: PyType by lazy {
            val emptyTuple = python.multiplatform.ffi.Python3.withPython { PyTuple_New(0) }
                ?: throw PyException.fromCurrentError() ?: PyException("Failed to build tuple() scratch buffer")
            val emptySet = python.multiplatform.ffi.Python3.withPython { PySet_New(emptyTuple) }
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(emptyTuple) }
            if (emptySet == null) throw PyException.fromCurrentError() ?: PyException("Failed to build set() to derive its type")
            deriveTypeAndRelease(emptySet)
        }

        /**
         * Builds a new Python `set` of [elements] (the mermaid's `+fromSet(set Set)`); a [PyObject] element is
         * stored as itself, any other value is converted as [PyMutableIterable] describes.
         */
        fun fromSet(elements: Set<*>): PySet {
            // PySet_New's `iterable` parameter is non-nullable here even though the empty-set
            // case is allowed at the C level -- build a scratch tuple to feed it either way.
            val tuplePtr = python.multiplatform.ffi.Python3.withPython { PyTuple_New(elements.size.toLong()) }
                ?: throw PyException.fromCurrentError() ?: PyException("Failed to allocate tuple() scratch buffer")
            // See PyList.fromList: the scratch tuple's release covers the fill loop too, since
            // nothing wraps it and an escape from the loop would strand it.
            val setPtr = try {
                elements.forEachIndexed { i, el ->
                    val item = newElementReference(el) // PyTuple_SetItem steals a reference: a new one is made for it
                    python.multiplatform.ffi.Python3.withPython { PyTuple_SetItem(tuplePtr, i.toLong(), item) }
                }
                python.multiplatform.ffi.Python3.withPython { PySet_New(tuplePtr) }
            } finally {
                python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(tuplePtr) } // scratch tuple, no longer needed once copied into the set
            }
            if (setPtr == null) throw PyException.fromCurrentError() ?: PyException("Failed to build set()")
            return PySet(setPtr, false)
        }
    }

    override var cachedNativeValue: Set<Any?>? = null
    override var cachedPyObjectValue: PyObject? = null

    /**
     * Snapshot conversion to a plain Kotlin set, recursively converting elements
     * ([python.multiplatform.ffi.conversion.ConversionStrategy.NATIVE]): the mermaid's `+toSet() Set`.
     */
    fun toSet(): Set<Any?> = snapshotElements(pointer).mapTo(LinkedHashSet()) { pyObjectToNative(it) }

    /** `self | other`, via `PyNumber_Or` (CPython dispatches `set.__or__` through the number protocol). */
    fun union(other: PySet): PySet {
        val result = python.multiplatform.ffi.Python3.withPython { PyNumber_Or(pointer, other.pointer) }
            ?: throw PyException.fromCurrentError() ?: PyException("set union failed")
        return PySet(result, false)
    }

    /** `self & other`, via `PyNumber_And`. */
    fun intersection(other: PySet): PySet {
        val result = python.multiplatform.ffi.Python3.withPython { PyNumber_And(pointer, other.pointer) }
            ?: throw PyException.fromCurrentError() ?: PyException("set intersection failed")
        return PySet(result, false)
    }

    /** `self - other`, via `PyNumber_Subtract`. */
    fun difference(other: PySet): PySet {
        val result = python.multiplatform.ffi.Python3.withPython { PyNumber_Subtract(pointer, other.pointer) }
            ?: throw PyException.fromCurrentError() ?: PyException("set difference failed")
        return PySet(result, false)
    }

    /** Removes and returns an arbitrary element (`PySet_Pop`), throwing if the set is empty. */
    fun pop(): PyObject {
        val item = python.multiplatform.ffi.Python3.withPython { PySet_Pop(pointer) } ?: throw PyException.fromCurrentError() ?: PyException("pop from an empty set")
        return PyObject(item, false) // PySet_Pop returns a new reference
    }

    override val size: Int
        get() = python.multiplatform.ffi.Python3.withPython { PySet_Size(pointer) }.toInt()

    override fun isEmpty(): Boolean = size == 0

    override fun iterator(): PyIterator = SnapshotIterator(snapshotElements(pointer)) { this@PySet.remove(it) }

    override fun contains(element: Any?): Boolean {
        val result = withPythonElement(element) { item -> python.multiplatform.ffi.Python3.withPython { PySet_Contains(pointer, item) } }
        if (result == -1) throw PyException.fromCurrentError() ?: PyException("Failed to check set membership")
        return result == 1
    }

    override fun containsAll(elements: Collection<Any?>): Boolean = elements.all { contains(it) }

    override fun add(element: Any?): Boolean = withPythonElement(element) { item -> addPointer(item) }

    private fun addPointer(item: NativePointer): Boolean {
        val present = python.multiplatform.ffi.Python3.withPython { PySet_Contains(pointer, item) }
        if (present == -1) throw PyException.fromCurrentError() ?: PyException("Failed to check set membership")
        if (present == 1) return false
        // PySet_Add does not steal a reference to `key`.
        if (python.multiplatform.ffi.Python3.withPython { PySet_Add(pointer, item) } != 0) throw PyException.fromCurrentError() ?: PyException("set.add() failed")
        return true
    }

    override fun addAll(elements: Collection<Any?>): Boolean {
        var changed = false
        for (e in elements) if (add(e)) changed = true
        return changed
    }

    override fun remove(element: Any?): Boolean {
        val rc = withPythonElement(element) { item -> python.multiplatform.ffi.Python3.withPython { PySet_Discard(pointer, item) } }
        if (rc == -1) throw PyException.fromCurrentError() ?: PyException("set.discard() failed")
        return rc == 1
    }

    override fun removeAll(elements: Collection<Any?>): Boolean {
        var changed = false
        for (e in elements) if (remove(e)) changed = true
        return changed
    }

    override fun retainAll(elements: Collection<Any?>): Boolean {
        var changed = false
        for (e in snapshotElements(pointer)) {
            if (elements.none { other -> withPythonElement(other) { pyEquals(it, e.pointer) } }) {
                remove(e)
                changed = true
            }
        }
        return changed
    }

    override fun clear() {
        if (python.multiplatform.ffi.Python3.withPython { PySet_Clear(pointer) } != 0) throw PyException.fromCurrentError() ?: PyException("set.clear() failed")
    }
}
