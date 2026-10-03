package python.multiplatform.ffi.types.iteration

import python.multiplatform.ffi.PythonTestFixture
import python.native.ffi.PyObject_GetIter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Functional tests for [PyIterator]: obtaining one via `PyObject_GetIter` and
 * driving it to exhaustion, including the empty-iterable case where
 * [PyIterator.hasNext] must be false on the first ask.
 *
 * This header used to say [PyIterator.hasNext] and [PyIterator.next] were
 * `TODO` stubs. Both are implemented now, so every test here is a regression
 * test and any failure is a real one.
 */
class PyIteratorTest {

    @Test
    fun iteratesOverAllElementsThenStops() = PythonTestFixture.withInterpreter {
        val listObj = PythonTestFixture.eval("[1, 2, 3]")
        val iterPtr = python.multiplatform.ffi.Python3.withPython { PyObject_GetIter(listObj.pointer) } ?: error("PyObject_GetIter returned null for a list")
        val iterator = PyIterator(iterPtr, false)

        val seen = mutableListOf<String>()
        while (iterator.hasNext()) {
            seen.add(iterator.next().toString())
        }
        assertEquals(listOf("1", "2", "3"), seen)
        assertFalse(iterator.hasNext(), "iterator should report exhausted once all elements are consumed")
    }

    @Test
    fun emptyIterableHasNoNextImmediately() = PythonTestFixture.withInterpreter {
        val listObj = PythonTestFixture.eval("[]")
        val iterPtr = python.multiplatform.ffi.Python3.withPython { PyObject_GetIter(listObj.pointer) } ?: error("PyObject_GetIter returned null")
        val iterator = PyIterator(iterPtr, false)
        assertTrue(!iterator.hasNext())
    }
}
