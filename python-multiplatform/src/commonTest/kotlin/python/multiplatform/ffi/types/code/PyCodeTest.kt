package python.multiplatform.ffi.types.code

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.exceptions.errors.PyValueError
import python.multiplatform.ffi.types.basic.PyString
import python.multiplatform.ffi.types.iteration.PyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #524: [PyCell], [PyCode] and [PyFrame], empty classes at the baseline and until this change. Red
 * before: none of these members existed, so this file did not compile.
 */
class PyCodeTest {

    private fun define() = Python3.exec(
        """
        import sys

        def _tc_outer():
            v = 'kept'
            def inner():
                return v
            return inner

        _tc_closure = _tc_outer()

        def _tc_signature(a, b, /, c, *, d, e=1):
            z = a
            return z

        def _tc_frames():
            local_x = 3
            yield local_x
        """.trimIndent(),
    )

    @Test
    fun aClosureCellReadsAndWritesTheCapturedVariable() = PythonTestFixture.withInterpreter {
        define()
        PythonTestFixture.eval("_tc_closure.__closure__[0]").use { raw ->
            PyCell.from(raw).use { cell ->
                assertFalse(cell.isEmpty)
                assertEquals("kept", cell.contents.use { it.toString() })
                PyString.from("new").use { cell.contents = it }
                assertEquals("new", PythonTestFixture.eval("_tc_closure()").use { it.toString() })
            }
        }
    }

    @Test
    fun anEmptyCellRaisesValueErrorAndCanBeFilledAndCleared() = PythonTestFixture.withInterpreter {
        PyCell.of().use { cell ->
            assertTrue(cell.isEmpty)
            assertFailsWith<PyValueError> { cell.contents }
            PyString.from("x").use { cell.contents = it }
            assertEquals("x", cell.contentsOrNull()?.use { it.toString() })
            cell.clear()
            assertTrue(cell.isEmpty)
        }
        PyString.from("y").use { value -> PyCell.of(value) }.use { assertEquals("y", it.contents.use { c -> c.toString() }) }
    }

    @Test
    fun aCodeObjectDescribesItsSignatureAndNames() = PythonTestFixture.withInterpreter {
        define()
        PythonTestFixture.eval("_tc_signature.__code__").use { raw ->
            PyCode.from(raw).use { code ->
                assertEquals("_tc_signature", code.name)
                assertEquals("_tc_signature", code.qualifiedName)
                assertEquals("<string>", code.filename)
                assertTrue(code.firstLineNumber > 0)
                assertEquals(3, code.argumentCount)
                assertEquals(2, code.positionalOnlyArgumentCount)
                assertEquals(2, code.keywordOnlyArgumentCount)
                assertEquals(listOf("a", "b", "c", "d", "e", "z"), code.localNames)
                assertFalse(code.isGenerator || code.isCoroutine || code.isAsyncGenerator)
            }
        }
        PythonTestFixture.eval("_tc_outer.__code__").use { raw -> PyCode.from(raw) }.use { assertEquals(listOf("v"), it.cellNames) }
        PythonTestFixture.eval("_tc_closure.__code__").use { raw -> PyCode.from(raw) }.use {
            assertEquals(listOf("v"), it.freeNames)
            assertEquals("_tc_outer.<locals>.inner", it.qualifiedName)
        }
    }

    @Test
    fun aPausedFramesCodeLineAndLocalsAreReadable() = PythonTestFixture.withInterpreter {
        define()
        PythonTestFixture.eval("_tc_frames()").use { raw -> PyGenerator.from(raw) }.use { gen ->
            gen.next().close()
            assertNotNull(gen.frame).use { frame ->
                assertEquals("_tc_frames", frame.code.use { it.name })
                assertNotNull(frame.lineNumber)
                assertTrue(frame.lastInstruction >= 0)
                val local = frame.locals.use { locals ->
                    PyString.from("local_x").use { key -> locals.getAttr("__getitem__").use { it(key) } }
                }
                assertEquals("3", local.use { it.toString() })
                assertEquals("True", frame.globals.use { g -> PyString.from("_tc_frames").use { key -> g.getAttr("__contains__").use { it(key) } } }.use { it.toString() })
            }
        }
    }

    @Test
    fun theCurrentFrameHasACallerChainAndBuiltins() = PythonTestFixture.withInterpreter {
        define()
        PythonTestFixture.eval("sys._getframe()").use { raw -> PyFrame.from(raw) }.use { frame ->
            frame.builtins.close()
            assertNotNull(frame.code).close()
        }
        PythonTestFixture.eval("[]").use { assertFailsWith<IllegalArgumentException> { PyFrame.from(it) } }
    }
}
