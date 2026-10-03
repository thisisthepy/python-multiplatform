package python.multiplatform.ffi.types.utilities

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.native.ffi.PyObject_GetItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Functional tests for the utility wrappers: [PyEllipsis], [PyRange] and
 * [PySlice].
 *
 * None of the three has a dedicated Stable ABI constructor available in this
 * project's `EmbedAPI` surface (`Py_Ellipsis` is a data symbol, and there is
 * no `PySlice_New`/`PyRange_New` declaration), so all three are expected to
 * go through the builtins namespace. These tests pin the *observable*
 * behaviour so that route can be changed later without changing the contract.
 */
class PyUtilitiesTest {

    @Test
    fun ellipsisIsTheInterpreterSingleton() = PythonTestFixture.withInterpreter {
        val ellipsis = PyEllipsis.get()
        assertSame(ellipsis, PyEllipsis.get(), "Ellipsis is a singleton; get() must not build a second wrapper")

        val fromPython = PythonTestFixture.eval("Ellipsis")
        try {
            // PyObject.equals compares the underlying pointer, which is the question here.
            assertEquals(fromPython, ellipsis, "get() must return the interpreter's own Ellipsis object")
        } finally {
            fromPython.close()
        }
        assertEquals("Ellipsis", ellipsis.toString())
    }

    @Test
    fun rangeExposesItsBoundsAndLength() = PythonTestFixture.withInterpreter {
        val range = PyRange.of(1, 10, 2)
        try {
            assertEquals(1, range.start)
            assertEquals(10, range.stop)
            assertEquals(2, range.step)
            assertEquals(5, range.size, "len(range(1, 10, 2)) == 5")
            assertEquals("range(1, 10, 2)", range.toString())
        } finally {
            range.close()
        }
    }

    @Test
    fun rangeDefaultsToStepOne() = PythonTestFixture.withInterpreter {
        val range = PyRange.of(0, 3)
        try {
            assertEquals(1, range.step)
            assertEquals(3, range.size)
        } finally {
            range.close()
        }
    }

    @Test
    fun rangeIteratesOverItsElements() = PythonTestFixture.withInterpreter {
        val range = PyRange.of(1, 10, 2)
        try {
            val seen = mutableListOf<String>()
            for (element in range) {
                seen.add(element.toString())
                element.close()
            }
            assertEquals(listOf("1", "3", "5", "7", "9"), seen)
        } finally {
            range.close()
        }
    }

    @Test
    fun emptyRangeHasNoElements() = PythonTestFixture.withInterpreter {
        val range = PyRange.of(5, 5)
        try {
            assertEquals(0, range.size)
            assertTrue(!range.iterator().hasNext())
        } finally {
            range.close()
        }
    }

    @Test
    fun sliceExposesItsThreeComponents() = PythonTestFixture.withInterpreter {
        val slice = PySlice.of(1, 10, 2)
        try {
            assertEquals(1, slice.start)
            assertEquals(10, slice.stop)
            assertEquals(2, slice.step)
        } finally {
            slice.close()
        }
    }

    @Test
    fun omittedSliceComponentsAreNoneAndReadBackAsNull() = PythonTestFixture.withInterpreter {
        val slice = PySlice.of(stop = 5)
        try {
            assertNull(slice.start, "an omitted slice bound is Python's None, which must map to null")
            assertEquals(5, slice.stop)
            assertNull(slice.step)
            assertEquals("slice(None, 5, None)", slice.toString())
        } finally {
            slice.close()
        }
    }

    @Test
    fun sliceActuallySlicesASequence() = PythonTestFixture.withInterpreter {
        val list = PythonTestFixture.eval("list(range(10))")
        val slice = PySlice.of(1, 10, 2)
        try {
            val resultPtr = Python3.withPython { PyObject_GetItem(list.pointer, slice.pointer) }
                ?: error("PyObject_GetItem returned null for list[slice]")
            // PyObject_GetItem returns a new reference; this wrapper adopts exactly that one.
            val result = PyObject(resultPtr, borrowed = false)
            try {
                assertEquals("[1, 3, 5, 7, 9]", result.toString())
            } finally {
                result.close()
            }
        } finally {
            slice.close()
            list.close()
        }
    }
}
