package python.multiplatform.ffi.types.iteration

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.exceptions.errors.PyStopAsyncIteration
import python.multiplatform.ffi.exceptions.errors.PyStopIteration
import python.multiplatform.ffi.types.basic.PyInt
import python.multiplatform.ffi.types.basic.PyNone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * #524: [PyGenerator], [PyCoroutine] and [PyAsyncGenerator], which were empty classes at the
 * baseline (f33bcef8) and until this change. Red before: none of these members existed, so this file
 * did not compile.
 */
class PyGeneratorTest {

    private fun define() = Python3.exec(
        """
        def _tg_gen():
            x = yield 1
            yield x * 2
            return 'done'

        def _tg_catching():
            try:
                yield 1
            except ValueError:
                yield 'caught'

        async def _tg_coro():
            return 42

        class _TgAwaitable:
            def __await__(self):
                value = yield 'tick'
                return value

        async def _tg_awaiting():
            return await _TgAwaitable()

        async def _tg_agen():
            yield 1
            yield 2
        """.trimIndent(),
    )

    private inline fun <T : PyObject, R> wrapped(expression: String, wrap: (PyObject) -> T, block: (T) -> R): R {
        define()
        val raw = PythonTestFixture.eval(expression)
        try {
            return wrap(raw).use(block)
        } finally {
            raw.close()
        }
    }

    /** Runs one step of an awaitable by hand and returns its result (the `StopIteration` value). */
    private fun drive(awaitable: PyObject): String? = awaitable.use {
        try {
            it.getAttr("send").use { send -> send(PyNone.get()) }.close()
            fail("the awaitable suspended instead of finishing")
        } catch (e: PyStopIteration) {
            e.returnValue?.use { value -> value.toString() }
        }
    }

    @Test
    fun aGeneratorYieldsSendsAndReturnsThroughStopIteration() = PythonTestFixture.withInterpreter {
        wrapped("_tg_gen()", PyGenerator::from) { gen ->
            assertEquals("_tg_gen", gen.name)
            assertEquals("_tg_gen", gen.qualifiedName)
            assertTrue(gen.code.use { it.isGenerator })
            assertEquals("1", gen.send().use { it.toString() })
            assertTrue(gen.isSuspended)
            assertNotNull(gen.frame).close()
            assertEquals("10", PyInt.from(5L).use { five -> gen.send(five) }.use { it.toString() })
            val end = assertFailsWith<PyStopIteration> { gen.next() }
            assertEquals("done", end.returnValue?.use { it.toString() })
            assertNull(gen.frame, "a finished generator has no frame")
            assertFalse(gen.isRunning)
        }
    }

    @Test
    fun throwIntoRaisesAtThePausedYield() = PythonTestFixture.withInterpreter {
        wrapped("_tg_catching()", PyGenerator::from) { gen ->
            gen.next().close()
            val error = PythonTestFixture.eval("ValueError('x')")
            val answer = error.use { gen.throwInto(it) }
            assertEquals("caught", answer.use { it.toString() })
        }
    }

    @Test
    fun finishClosesTheGenerator() = PythonTestFixture.withInterpreter {
        wrapped("_tg_gen()", PyGenerator::from) { gen ->
            gen.next().close()
            gen.finish()
            assertFailsWith<PyStopIteration> { gen.next() }
        }
    }

    @Test
    fun fromRefusesWhatIsNotAGenerator() = PythonTestFixture.withInterpreter {
        val list = PythonTestFixture.eval("[1, 2]")
        list.use { assertFailsWith<IllegalArgumentException> { PyGenerator.from(it) } }
    }

    @Test
    fun aCoroutineRunsToItsResult() = PythonTestFixture.withInterpreter {
        wrapped("_tg_coro()", PyCoroutine::from) { coro ->
            assertEquals("_tg_coro", coro.name)
            assertTrue(coro.code.use { it.isCoroutine })
            val end = assertFailsWith<PyStopIteration> { coro.send() }
            assertEquals("42", end.returnValue?.use { it.toString() })
        }
    }

    @Test
    fun aCoroutineSuspendsOnWhatItAwaitsAndResumesWithTheSentValue() = PythonTestFixture.withInterpreter {
        wrapped("_tg_awaiting()", PyCoroutine::from) { coro ->
            assertEquals("tick", coro.send().use { it.toString() })
            assertTrue(coro.isSuspended)
            assertNotNull(coro.awaiting).close()
            val end = assertFailsWith<PyStopIteration> { PyInt.from(7L).use { coro.send(it) } }
            assertEquals("7", end.returnValue?.use { it.toString() })
        }
    }

    @Test
    fun anAsyncGeneratorStepsThroughAwaitablesAndEndsWithStopAsyncIteration() = PythonTestFixture.withInterpreter {
        wrapped("_tg_agen()", PyAsyncGenerator::from) { agen ->
            assertEquals("_tg_agen", agen.name)
            assertTrue(agen.code.use { it.isAsyncGenerator })
            assertEquals("1", drive(agen.next()))
            assertEquals("2", drive(agen.next()))
            assertFailsWith<PyStopAsyncIteration> { drive(agen.next()) }
            assertNull(drive(agen.finish()))
        }
    }
}
