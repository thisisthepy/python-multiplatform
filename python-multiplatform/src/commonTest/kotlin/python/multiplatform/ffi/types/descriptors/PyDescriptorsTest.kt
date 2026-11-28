package python.multiplatform.ffi.types.descriptors

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.exceptions.errors.PyAttributeError
import python.multiplatform.ffi.types.basic.PyInt
import python.multiplatform.ffi.types.basic.PyString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * #524: [PyMemberDescriptor], [PyGetSetDescriptor] and [PyProperty], empty classes at the baseline and
 * until this change. Red before: none of these members existed, so this file did not compile.
 */
class PyDescriptorsTest {

    private fun define() = Python3.exec(
        """
        class _TdSlots:
            __slots__ = ('x',)

        def _td_function():
            pass

        class _TdProps:
            def __init__(self):
                self._v = 1

            @property
            def v(self):
                "the v property"
                return self._v
        """.trimIndent(),
    )

    @Test
    fun aMemberDescriptorReadsWritesAndDeletesASlot() = PythonTestFixture.withInterpreter {
        define()
        PythonTestFixture.eval("_TdSlots.__dict__['x']").use { raw -> PyMemberDescriptor.from(raw) }.use { member ->
            assertEquals("x", member.name)
            assertEquals("_TdSlots", member.ownerClass.name)
            PythonTestFixture.eval("_TdSlots()").use { instance ->
                PyInt.from(4L).use { member.set(instance, it) }
                assertEquals("4", member.get(instance).use { it.toString() })
                member.delete(instance)
                assertFailsWith<PyAttributeError> { member.get(instance) }
            }
        }
    }

    @Test
    fun aGetSetDescriptorReachesAComputedAttribute() = PythonTestFixture.withInterpreter {
        define()
        PythonTestFixture.eval("type(_td_function).__dict__['__name__']").use { raw -> PyGetSetDescriptor.from(raw) }.use { getset ->
            assertEquals("__name__", getset.name)
            assertEquals("function", getset.ownerClass.name)
            PythonTestFixture.eval("_td_function").use { function ->
                assertEquals("_td_function", getset.get(function).use { it.toString() })
                PyString.from("renamed").use { getset.set(function, it) }
            }
            assertEquals("renamed", PythonTestFixture.eval("_td_function.__name__").use { it.toString() })
        }
    }

    @Test
    fun aPropertyRunsItsGetterAndRefusesAMissingSetter() = PythonTestFixture.withInterpreter {
        define()
        PythonTestFixture.eval("_TdProps.__dict__['v']").use { raw -> PyProperty.from(raw) }.use { property ->
            assertNotNull(property.getter).close()
            assertNull(property.setter)
            assertEquals("the v property", property.doc)
            PythonTestFixture.eval("_TdProps()").use { instance ->
                assertEquals("1", property.get(instance).use { it.toString() })
                assertFailsWith<PyAttributeError> { PyInt.from(2L).use { property.set(instance, it) } }
            }
        }
    }

    @Test
    fun aPropertyBuiltFromKotlinGainsASetter() = PythonTestFixture.withInterpreter {
        define()
        val getter = PythonTestFixture.eval("lambda self: self._v")
        val setter = PythonTestFixture.eval("lambda self, value: setattr(self, '_v', value)")
        val property = getter.use { g -> PyProperty.of(getter = g) }.use { p -> setter.use { s -> p.withSetter(s) } }
        property.use {
            assertNotNull(it.setter).close()
            PythonTestFixture.eval("_TdProps()").use { instance ->
                PyInt.from(9L).use { nine -> it.set(instance, nine) }
                assertEquals("9", it.get(instance).use { v -> v.toString() })
            }
        }
        PythonTestFixture.eval("1").use { assertFailsWith<IllegalArgumentException> { PyProperty.from(it) } }
    }
}
