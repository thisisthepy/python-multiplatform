package python.multiplatform.ffi

import python.multiplatform.ffi.exceptions.errors.PyTypeError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Functional tests for [PyType]: name/base-type introspection, MRO,
 * `isInstance`/`isSubtypeOf`, construction via `invoke`, and the `__dict__`
 * namespace.
 *
 * This header used to say the members were `TODO` stubs expected to fail with
 * [NotImplementedError]. They are all implemented now, so every test here is a
 * regression test and any failure is a real one.
 */
class PyTypeTest {

    @Test
    fun nameReflectsThePythonTypeName() = PythonTestFixture.withInterpreter {
        val intType = PythonTestFixture.eval("42").Type
        assertEquals("int", intType.name)

        val strType = PythonTestFixture.eval("'x'").Type
        assertEquals("str", strType.name)
    }

    @Test
    fun baseTypeOfIntIsObject() = PythonTestFixture.withInterpreter {
        val intType = PythonTestFixture.eval("42").Type
        assertEquals("object", intType.baseType.name)
    }

    @Test
    fun baseTypesOfBoolIncludesInt() = PythonTestFixture.withInterpreter {
        val boolType = PythonTestFixture.eval("True").Type
        val baseNames = boolType.baseTypes.map { it.name }
        assertTrue("int" in baseNames, "expected bool.__bases__ to include int, got $baseNames")
    }

    @Test
    fun mroOfBoolIncludesIntAndObject() = PythonTestFixture.withInterpreter {
        val boolType = PythonTestFixture.eval("True").Type
        val mroNames = boolType.mro.map { it.name }
        assertTrue(mroNames.containsAll(listOf("bool", "int", "object")), "expected bool.__mro__ to include bool/int/object, got $mroNames")
    }

    @Test
    fun isSubtypeOfReflectsPythonSubclassing() = PythonTestFixture.withInterpreter {
        val boolType = PythonTestFixture.eval("True").Type
        val intType = PythonTestFixture.eval("42").Type
        assertTrue(boolType.isSubtypeOf(intType), "bool should be a subtype of int in Python")
    }

    @Test
    fun isInstanceRecognisesActualInstances() = PythonTestFixture.withInterpreter {
        val intType = PythonTestFixture.eval("42").Type
        val fortyTwo = PythonTestFixture.eval("42")
        val hello = PythonTestFixture.eval("'hello'")
        assertTrue(intType.isInstance(fortyTwo))
        assertTrue(!intType.isInstance(hello))
    }

    @Test
    fun invokeConstructsANewInstance() = PythonTestFixture.withInterpreter {
        val intType = PythonTestFixture.eval("42").Type
        val result = intType.invoke(PythonTestFixture.eval("'7'"))
        assertEquals("7", result.toString())
    }

    @Test
    fun castRaisesForIncompatibleObjects() = PythonTestFixture.withInterpreter {
        val intType = PythonTestFixture.eval("42").Type
        val notAnInt = PythonTestFixture.eval("'not an int'")
        assertFailsWith<PyTypeError> {
            intType.cast(notAnInt)
        }
    }

    /**
     * `type.__dict__` is a **`mappingproxy`**, not a `dict`. Handing that pointer to a [PyDict]
     * is not merely inelegant, it is actively corrupting: `PyDict_Size`/`PyDict_Items` reject a
     * non-dict with `PyErr_BadInternalCall()`, so every read returns a sentinel (`-1`/`NULL`)
     * *and leaves the error indicator set* for whatever Python call runs next -- the same class
     * of silent poisoning the `*OrNull` helpers on [PyObject] exist to avoid.
     *
     * The names asserted here are the ones the probe class declares, so this fails both if the
     * mapping comes back empty (the sentinel path) and if it is somebody else's namespace.
     */
    @Test
    fun dictExposesTheTypesOwnNamespace() = PythonTestFixture.withInterpreter {
        Python3.exec(
            """
            class PyTypeDictProbe:
                probe_marker = 7
                def probe_method(self):
                    return 1
            py_type_dict_probe_instance = PyTypeDictProbe()
            """.trimIndent()
        )
        val probeType = PythonTestFixture.eval("py_type_dict_probe_instance").Type
        assertEquals("PyTypeDictProbe", probeType.name)

        val namespace = probeType.dict
        val keyNames = namespace.keys.map { it.toString() }
        assertTrue(
            keyNames.containsAll(listOf("probe_marker", "probe_method")),
            "expected PyTypeDictProbe.__dict__ to carry the names the class declares, got $keyNames"
        )
        assertEquals(
            keyNames.size, namespace.size,
            "size must agree with the entries actually readable; a mismatch means PyDict_Size " +
                "returned its -1 error sentinel"
        )
    }

    /**
     * A read of `__dict__` must not leave the error indicator set. If it does, the *next*
     * unrelated Python call is the one that fails, which is why this asserts on a call made
     * afterwards rather than on the read itself.
     */
    @Test
    fun readingDictLeavesNoPendingError() = PythonTestFixture.withInterpreter {
        val intType = PythonTestFixture.eval("42").Type
        val namespace = intType.dict
        assertTrue(namespace.size > 0, "int.__dict__ is not empty")
        // Would surface the leftover SystemError from PyErr_BadInternalCall if one were pending.
        assertEquals("3", PythonTestFixture.eval("1 + 2").toString())
    }
}
