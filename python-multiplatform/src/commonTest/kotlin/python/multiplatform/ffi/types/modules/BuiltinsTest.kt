package python.multiplatform.ffi.types.modules

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import python.multiplatform.ffi.exceptions.PyException

/**
 * Functional tests for [Builtins], the curated view over the `builtins`
 * module that the library's own wrapper bodies lean on.
 */
class BuiltinsTest {

    private fun builtins(): Builtins = Builtins(Python3.import("builtins"))

    @Test
    fun lenCountsASequence() = PythonTestFixture.withInterpreter {
        val b = builtins()
        val list = PythonTestFixture.eval("[1, 2, 3]")
        try {
            assertEquals(3, b.len(list))
        } finally {
            list.close()
            b.module.close()
        }
    }

    @Test
    fun lenRejectsAnObjectWithoutALength() = PythonTestFixture.withInterpreter {
        val b = builtins()
        val number = PythonTestFixture.eval("42")
        try {
            assertFailsWith<PyException>("len(42) raises TypeError in Python and must surface as a PyException") {
                b.len(number)
            }
        } finally {
            number.close()
            b.module.close()
        }
    }

    @Test
    fun iterDrivesASequenceToExhaustion() = PythonTestFixture.withInterpreter {
        val b = builtins()
        val list = PythonTestFixture.eval("[1, 2, 3]")
        try {
            val iterator = b.iter(list)
            try {
                val seen = mutableListOf<String>()
                while (iterator.hasNext()) {
                    val element = iterator.next()
                    seen.add(element.toString())
                    element.close()
                }
                assertEquals(listOf("1", "2", "3"), seen)
            } finally {
                iterator.close()
            }
        } finally {
            list.close()
            b.module.close()
        }
    }

    @Test
    fun typeReportsTheObjectsType() = PythonTestFixture.withInterpreter {
        val b = builtins()
        val number = PythonTestFixture.eval("42")
        try {
            val type = b.type(number)
            try {
                assertEquals("<class 'int'>", type.toString())
                assertTrue(type == number.Type, "builtins.type(x) must be the same object as x's PyType")
            } finally {
                type.close()
            }
        } finally {
            number.close()
            b.module.close()
        }
    }
}
