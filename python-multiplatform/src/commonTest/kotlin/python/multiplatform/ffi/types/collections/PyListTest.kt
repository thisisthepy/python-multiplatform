package python.multiplatform.ffi.types.collections

import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Functional tests for [PyList]: construction, indexing, iteration, mutation
 * (`add`/`removeAt`/`sort`) and conversion to/from Kotlin collections.
 *
 * The second half is all `subList`, because it returns a live view rather
 * than a copy: writes through the view have to reach the backing Python list
 * and writes to the list have to be visible through the view, out-of-range
 * bounds have to be rejected rather than clamped, and a nested `subList` has
 * to resolve its indices against the original.
 *
 * This header used to say every member was a `TODO("Not yet implemented")`
 * stub expected to fail with [NotImplementedError]. They are all implemented
 * now, so every test here is a regression test and any failure is a real one.
 */
class PyListTest {

    @Test
    fun fromListThenToNativeListRoundTrips() = PythonTestFixture.withInterpreter {
        val elements = listOf(
            PythonTestFixture.eval("1"),
            PythonTestFixture.eval("2"),
            PythonTestFixture.eval("3"),
        )
        val list = PyList.fromList(elements)
        assertEquals(3, list.size)
        assertEquals(listOf(1L, 2L, 3L), list.toNativeList())
    }

    @Test
    fun getReturnsElementAtIndex() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("[10, 20, 30]")
        val list = PyList(obj.pointer, true)
        assertEquals("20", list[1].toString())
    }

    @Test
    fun iterationVisitsAllElementsInOrder() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("[1, 2, 3]")
        val list = PyList(obj.pointer, true)
        val seen = list.map { it.toString() }
        assertEquals(listOf("1", "2", "3"), seen)
    }

    @Test
    fun addAppendsAnElement() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("[1, 2]")
        val list = PyList(obj.pointer, true)
        list.add(PythonTestFixture.eval("3"))
        assertEquals(3, list.size)
    }

    @Test
    fun removeAtDeletesTheElement() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("[1, 2, 3]")
        val list = PyList(obj.pointer, true)
        list.removeAt(0)
        assertEquals(2, list.size)
    }

    @Test
    fun sortOrdersElementsAscending() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("[3, 1, 2]")
        val list = PyList(obj.pointer, true)
        list.sort()
        assertEquals(listOf(1L, 2L, 3L), list.toNativeList())
    }

    @Test
    fun isEmptyReflectsSize() = PythonTestFixture.withInterpreter {
        val list = PyList.fromList(emptyList())
        assertTrue(list.isEmpty())
    }

    @Test
    fun subListViewModificationAffectsOriginal() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("[1, 2, 3, 4, 5]")
        val list = PyList(obj.pointer, true)
        val sub = list.subList(1, 4)
        assertEquals(3, sub.size)
        
        sub[0] = PythonTestFixture.eval("9")
        assertEquals(listOf(1L, 9L, 3L, 4L, 5L), list.toNativeList())
        
        sub.add(PythonTestFixture.eval("10"))
        assertEquals(4, sub.size)
        assertEquals(listOf(1L, 9L, 3L, 4L, 10L, 5L), list.toNativeList())

        sub.removeAt(1)
        assertEquals(3, sub.size)
        assertEquals(listOf(1L, 9L, 4L, 10L, 5L), list.toNativeList())
    }

    @Test
    fun subListOriginalModificationAffectsView() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("[1, 2, 3, 4, 5]")
        val list = PyList(obj.pointer, true)
        val sub = list.subList(1, 4) // [2, 3, 4]
        
        list[2] = PythonTestFixture.eval("9")
        assertEquals(listOf(2L, 9L, 4L), sub.map { pyObjectToNative(it) })
        
        // Changing the size of the original list might cause undefined behavior in Kotlin's subList
        // but we should at least test that getting elements respects the current view if size didn't change
        // concurrent modification exception is expected for structural changes but we won't test CMEs strictly unless required.
    }

    @Test
    fun subListOutOfBounds() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("[1, 2, 3]")
        val list = PyList(obj.pointer, true)
        
        var threw = false
        try {
            list.subList(-1, 2)
        } catch (e: IndexOutOfBoundsException) { threw = true }
        assertTrue(threw)

        threw = false
        try {
            list.subList(0, 4)
        } catch (e: IndexOutOfBoundsException) { threw = true }
        assertTrue(threw)

        threw = false
        try {
            list.subList(2, 1)
        } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun subListEmptyRange() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("[1, 2, 3]")
        val list = PyList(obj.pointer, true)
        val sub = list.subList(1, 1)
        
        assertEquals(0, sub.size)
        assertTrue(sub.isEmpty())
        
        sub.add(PythonTestFixture.eval("9"))
        assertEquals(1, sub.size)
        assertEquals(listOf(1L, 9L, 2L, 3L), list.toNativeList())
    }

    @Test
    fun subListNested() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("[1, 2, 3, 4, 5]")
        val list = PyList(obj.pointer, true)
        val sub1 = list.subList(1, 5) // [2, 3, 4, 5]
        val sub2 = sub1.subList(1, 3) // [3, 4]
        
        assertEquals(2, sub2.size)
        sub2[0] = PythonTestFixture.eval("9")
        
        assertEquals(listOf(1L, 2L, 9L, 4L, 5L), list.toNativeList())
    }
}
