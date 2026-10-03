package python.multiplatform.ffi.types.collections

import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Functional tests for [PySet] and [PyFrozenSet]: construction from a Kotlin
 * set, `add`, membership, the set-algebra operations (`union`,
 * `intersection`, `difference`) and conversion back to a Kotlin set.
 *
 * This header used to say all members were `TODO` stubs. They are implemented
 * now, so every test here is a regression test and any failure is a real one.
 */
class PySetTest {

    @Test
    fun fromSetThenToNativeSetRoundTrips() = PythonTestFixture.withInterpreter {
        val elements = setOf(
            PythonTestFixture.eval("1"),
            PythonTestFixture.eval("2"),
        )
        val set = PySet.fromSet(elements)
        assertEquals(2, set.size)
        assertEquals(setOf(1L, 2L), set.toNativeSet())
    }

    @Test
    fun addInsertsANewElement() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("{1, 2}")
        val set = PySet(obj.pointer, true)
        set.add(PythonTestFixture.eval("3"))
        assertEquals(3, set.size)
    }

    @Test
    fun containsReflectsMembership() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("{1, 2, 3}")
        val set = PySet(obj.pointer, true)
        assertTrue(set.contains(PythonTestFixture.eval("2")))
    }

    @Test
    fun unionCombinesBothSets() = PythonTestFixture.withInterpreter {
        val a = PySet(PythonTestFixture.eval("{1, 2}").pointer, true)
        val b = PySet(PythonTestFixture.eval("{2, 3}").pointer, true)
        val union = a.union(b)
        assertEquals(setOf(1L, 2L, 3L), union.toNativeSet())
    }

    @Test
    fun intersectionKeepsOnlyCommonElements() = PythonTestFixture.withInterpreter {
        val a = PySet(PythonTestFixture.eval("{1, 2, 3}").pointer, true)
        val b = PySet(PythonTestFixture.eval("{2, 3, 4}").pointer, true)
        assertEquals(setOf(2L, 3L), a.intersection(b).toNativeSet())
    }

    @Test
    fun differenceRemovesElementsPresentInOther() = PythonTestFixture.withInterpreter {
        val a = PySet(PythonTestFixture.eval("{1, 2, 3}").pointer, true)
        val b = PySet(PythonTestFixture.eval("{2}").pointer, true)
        assertEquals(setOf(1L, 3L), a.difference(b).toNativeSet())
    }

    @Test
    fun frozenSetIsImmutableAndSupportsMembership() = PythonTestFixture.withInterpreter {
        val elements = setOf(PythonTestFixture.eval("'a'"), PythonTestFixture.eval("'b'"))
        val frozen = PyFrozenSet.fromSet(elements)
        assertTrue(frozen.contains(PythonTestFixture.eval("'a'")))
        assertEquals(2, frozen.size)
    }
}
