package python.multiplatform.ffi.types.iteration

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.exceptions.PyException
import python.native.ffi.NativePointer
import python.native.ffi.PyErr_Occurred
import python.native.ffi.PyIter_Next

/**
 * Iteration over a [PyIterable] (issue #524 item 3): the mermaid's `<<interface>> PyIterator` with
 * `+hasNext() Boolean`, `+next() PyObject`, `+remove() void`, realising Kotlin's `Iterator`.
 *
 * A [MutableIterator] so that one iterator type serves both the read-only [PyIterable] and the
 * mutable `PyMutableIterable` (whose `MutableCollection.iterator()` must be mutable). [remove] removes
 * the last element returned from the underlying container where the container supports it (a list,
 * a set) and throws [UnsupportedOperationException] where it does not (a tuple, a plain Python
 * iterator, which has no such operation in Python's protocol).
 */
interface PyIterator : MutableIterator<Any?> {
    override fun hasNext(): Boolean
    override fun next(): PyObject
    override fun remove()
}

/**
 * A Python iterator object (anything `iter()` returns, an object with `__next__`) as a [PyIterator].
 * [remove] is unsupported: Python's iterator protocol has no "remove what I just yielded".
 */
class PyIteratorObject(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed), PyIterator {

    /** One-slot lookahead buffer filled by [PyIter_Next]; `null` once exhausted (and not mid-[hasNext] check). */
    private var lookahead: PyObject? = null
    private var exhausted = false

    /** Backed by a one-slot lookahead buffer filled by `PyIter_Next`. */
    override fun hasNext(): Boolean {
        if (exhausted) return false
        if (lookahead != null) return true

        val next = python.multiplatform.ffi.Python3.withPython { PyIter_Next(pointer) }
        if (next == null) {
            // PyIter_Next returning NULL means either "exhausted" or "an error occurred while producing
            // the next element". Disambiguate via the error indicator rather than assuming exhaustion.
            val error = python.multiplatform.ffi.Python3.withPython { PyErr_Occurred() }
            if (error != null) {
                throw PyException.fromCurrentError() ?: PyException("Iterator raised an error")
            }
            exhausted = true
            return false
        }

        lookahead = PyObject(next, false) // PyIter_Next returns a new reference
        return true
    }

    /** Backed by `PyIter_Next`; throws [NoSuchElementException] once the iterator is exhausted. */
    override fun next(): PyObject {
        if (!hasNext()) throw NoSuchElementException("Iterator is exhausted")
        val result = lookahead!!
        lookahead = null
        return result
    }

    override fun remove() {
        throw UnsupportedOperationException("a Python iterator has no remove(); remove through the container")
    }
}

/**
 * A [PyIterator] over a snapshot of [elements] (each an owned [PyObject]); [onRemove] removes the
 * last returned element from the live container, or is `null` when the container cannot remove.
 */
internal class SnapshotIterator(
    private val elements: List<PyObject>,
    private val onRemove: ((PyObject) -> Unit)?,
) : PyIterator {
    private var index = 0
    private var lastReturned: PyObject? = null

    override fun hasNext(): Boolean = index < elements.size

    override fun next(): PyObject {
        if (!hasNext()) throw NoSuchElementException()
        val element = elements[index++]
        lastReturned = element
        return element
    }

    override fun remove() {
        val remover = onRemove ?: throw UnsupportedOperationException("this container is immutable")
        val element = lastReturned ?: throw IllegalStateException("next() has not been called, or remove() already called")
        remover(element)
        lastReturned = null
    }
}
