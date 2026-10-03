package python.multiplatform.ffi.types.iteration

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.exceptions.PyException
import python.native.ffi.NativePointer
import python.native.ffi.PyErr_Occurred
import python.native.ffi.PyIter_Next

/**
 * Wraps a Python iterator object (anything `iter()` can return, i.e. an
 * object exposing `__next__`) as a Kotlin [Iterator].
 *
 * The mermaid sketch also lists a `remove()` method on this interface, but
 * that has no counterpart in Python's iterator protocol (there is no
 * generic "remove the element I just yielded" operation -- mutation, where
 * supported at all, happens through the underlying container, e.g.
 * [python.multiplatform.ffi.types.collections.PyList.removeAt]). It is
 * intentionally omitted here rather than faked.
 */
class PyIterator(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed), Iterator<PyObject> {

    /** One-slot lookahead buffer filled by [PyIter_Next]; `null` once exhausted (and not mid-[hasNext] check). */
    private var lookahead: PyObject? = null
    private var exhausted = false

    /** Backed by `PyIter_Check` + a one-slot lookahead buffer filled by `PyIter_Next`. */
    override fun hasNext(): Boolean {
        if (exhausted) return false
        if (lookahead != null) return true

        val next = python.multiplatform.ffi.Python3.withPython { PyIter_Next(pointer) }
        if (next == null) {
            // python.multiplatform.ffi.Python3.withPython { PyIter_Next() } returning NULL is ambiguous by itself -- it means either "exhausted"
            // or "an error occurred while producing the next element". Disambiguate via the
            // error indicator rather than assuming exhaustion.
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
}
