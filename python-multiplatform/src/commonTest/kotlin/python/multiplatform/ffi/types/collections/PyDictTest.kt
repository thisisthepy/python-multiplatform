package python.multiplatform.ffi.types.collections

import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Functional tests for [PyDict]: construction from a Kotlin map, key lookup
 * (present and missing), insertion, removal, membership, and conversion back
 * to a Kotlin map.
 *
 * This header used to say all members were `TODO` stubs. They are implemented
 * now, so every test here is a regression test and any failure is a real one.
 */
class PyDictTest {

    @Test
    fun fromMapThenToNativeMapRoundTrips() = PythonTestFixture.withInterpreter {
        val map = mapOf(
            PythonTestFixture.eval("'a'") to PythonTestFixture.eval("1"),
            PythonTestFixture.eval("'b'") to PythonTestFixture.eval("2"),
        )
        val dict = PyDict.fromMap(map)
        assertEquals(2, dict.size)
        assertEquals(mapOf<Any?, Any?>("a" to 1L, "b" to 2L), dict.toNativeMap())
    }

    @Test
    fun getReturnsTheValueForAnExistingKey() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("{'x': 10, 'y': 20}")
        val dict = PyDict(obj.pointer, true)
        assertEquals("10", dict[PythonTestFixture.eval("'x'")]?.toString())
    }

    @Test
    fun getReturnsNullForAMissingKey() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("{'x': 10}")
        val dict = PyDict(obj.pointer, true)
        assertNull(dict[PythonTestFixture.eval("'missing'")])
    }

    @Test
    fun putInsertsANewEntry() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("{}")
        val dict = PyDict(obj.pointer, true)
        dict.put(PythonTestFixture.eval("'new_key'"), PythonTestFixture.eval("42"))
        assertEquals(1, dict.size)
    }

    @Test
    fun removeDeletesAnEntry() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("{'x': 1}")
        val dict = PyDict(obj.pointer, true)
        dict.remove(PythonTestFixture.eval("'x'"))
        assertTrue(dict.isEmpty())
    }

    @Test
    fun containsKeyReflectsMembership() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("{'present': 1}")
        val dict = PyDict(obj.pointer, true)
        assertTrue(dict.containsKey(PythonTestFixture.eval("'present'")))
        assertTrue(!dict.containsKey(PythonTestFixture.eval("'absent'")))
    }
}
