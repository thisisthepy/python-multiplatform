package python.multiplatform.ffi.types.collections

import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Functional tests for [PyTuple]: construction from a Kotlin list, indexing,
 * size, `subList`, iteration order and conversion to a Kotlin `List`.
 *
 * This header used to say all members were `TODO` stubs. They are implemented
 * now, so every test here is a regression test and any failure is a real one.
 */
class PyTupleTest {

    @Test
    fun fromListThenToNativeListRoundTrips() = PythonTestFixture.withInterpreter {
        val elements = listOf(
            PythonTestFixture.eval("'a'"),
            PythonTestFixture.eval("'b'"),
        )
        val tuple = PyTuple.fromList(elements)
        assertEquals(2, tuple.size)
        assertEquals(listOf("a", "b"), tuple.toNativeList())
    }

    @Test
    fun getReturnsElementAtIndex() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("(10, 20, 30)")
        val tuple = PyTuple(obj.pointer, true)
        assertEquals("20", tuple[1].toString())
    }

    @Test
    fun sizeMatchesPythonLen() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("(1, 2, 3, 4)")
        val tuple = PyTuple(obj.pointer, true)
        assertEquals(4, tuple.size)
    }

    @Test
    fun subListReturnsAContiguousSlice() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("(1, 2, 3, 4, 5)")
        val tuple = PyTuple(obj.pointer, true)
        val sub = tuple.subList(1, 3)
        assertEquals(listOf("2", "3"), sub.map { it.toString() })
    }

    @Test
    fun iterationVisitsAllElementsInOrder() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("(1, 2, 3)")
        val tuple = PyTuple(obj.pointer, true)
        assertEquals(listOf("1", "2", "3"), tuple.map { it.toString() })
    }
}
