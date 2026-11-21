package python.multiplatform.ffi.pythonx

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.getThreadGILState
import python.multiplatform.ffi.types.basic.PyInt
import python.multiplatform.ffi.types.basic.PyNone
import python.native.ffi.PyErr_Occurred
import python.native.ffi.toRawValue
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC U-5e (#164, item 13): one step of a Python coroutine from Kotlin, `PyObject.step` and
 * `PyObject.throwInto`.
 *
 * Every test here was red before `PyCoroutineStep.kt` existed, for the plainest reason: `step`,
 * `throwInto` and `PyStep` did not resolve, so the file did not compile. The thread-free part of
 * U-5e lives in `commonTest` so every target that loads the interpreter runs it; the thread claims
 * are `PyCoroutineStepThreadTest` (desktop) and `PyCoroutineStepNativeTest` (Kotlin/Native).
 *
 * The awaitable the coroutines suspend on is a plain `__await__` that yields itself and returns what
 * is sent back, so each `Yielded` is a known object and each `Returned` a known value, with no loop.
 */
class PyCoroutineStepTest {

    private fun defineFixtures() {
        Python3.exec(
            """
            import contextvars, sys
            from concurrent.futures._base import CancelledError as _StCancelled
            _st = {}

            class _StPause:
                def __init__(self, tag):
                    self.tag = tag
                def __await__(self):
                    v = yield self
                    return v
            """.trimIndent(),
        )
    }

    private fun errorIndicatorIsClear(): Boolean = Python3.withPython { PyErr_Occurred() } == null

    @Test
    fun aCoroutineIsSteppedWithSendValuesUntilItsReturnValueArrivesAndNothingLeaks() =
        PythonTestFixture.withInterpreter {
            defineFixtures()
            Python3.exec(
                """
                _st['p1'] = _StPause('first')
                async def _st_body():
                    a = await _st['p1']
                    b = await _StPause(a + 1)
                    return a + b
                _st['coro'] = _st_body()
                """.trimIndent(),
            )
            val coro = PythonTestFixture.eval("_st['coro']")
            val baseline = PythonTestFixture.eval("sys.getrefcount(_st['p1'])").toString().toLong()
            val depthBefore = getThreadGILState().depth

            val s1 = assertIs<PyStep.Yielded>(coro.step())
            assertEquals(depthBefore, getThreadGILState().depth, "the step must leave the attachment as it found it")
            assertEquals("first", s1.value.getAttr("tag").toString())
            // The suspended `__await__` frame holds `self`, and the Yielded wrapper holds its own reference.
            val during = PythonTestFixture.eval("sys.getrefcount(_st['p1'])").toString().toLong()
            s1.value.close()
            val afterClose = PythonTestFixture.eval("sys.getrefcount(_st['p1'])").toString().toLong()
            assertEquals(1, during - afterClose, "closing the Yielded wrapper releases exactly its one reference")

            val s2 = assertIs<PyStep.Yielded>(coro.step(PyInt.from(1)))
            assertEquals("2", s2.value.getAttr("tag").toString(), "the sent value is what `await` returned inside the coroutine")
            s2.value.close()
            assertEquals(baseline, PythonTestFixture.eval("sys.getrefcount(_st['p1'])").toString().toLong(), "the first awaitable is back at its baseline count once its frame is over")

            val s3 = assertIs<PyStep.Returned>(coro.step(PyInt.from(40)))
            assertEquals(41L, s3.value.toString().toLong())
            s3.value.close()
            assertTrue(errorIndicatorIsClear(), "a StopIteration must not stay on the indicator")

            val again = assertIs<PyStep.Raised>(coro.step())
            assertEquals("RuntimeError", again.exception.type?.name)
            assertTrue("already awaited" in again.exception.errMsg, again.exception.errMsg)
            assertTrue(errorIndicatorIsClear())
            assertEquals(depthBefore, getThreadGILState().depth)
        }

    @Test
    fun eachStepRunsInsideTheGivenContextAndWritesStayInIt() =
        PythonTestFixture.withInterpreter {
            defineFixtures()
            Python3.exec(
                """
                _cv = contextvars.ContextVar('_cv', default='outer')
                _cv.set('outer')
                _st_ctx = contextvars.copy_context()
                _st_ctx.run(_cv.set, 'ctx')
                _st['seen'] = []
                async def _st_ctx_body():
                    _st['seen'].append(_cv.get())
                    _cv.set('inner')
                    await _StPause('p')
                    _st['seen'].append(_cv.get())
                    return _cv.get()
                _st['cc'] = _st_ctx_body()
                _st['cc2'] = _st_ctx_body()
                """.trimIndent(),
            )
            val ctx = PythonTestFixture.eval("_st_ctx")
            val coro = PythonTestFixture.eval("_st['cc']")

            val s1 = assertIs<PyStep.Yielded>(coro.step(context = ctx))
            s1.value.close()
            assertEquals("outer", PythonTestFixture.eval("_cv.get()").toString(), "the caller's context is untouched by a write inside the step")
            assertEquals("inner", PythonTestFixture.eval("_st_ctx[_cv]").toString(), "the write landed in the given context")
            val s2 = assertIs<PyStep.Returned>(coro.step(context = ctx))
            assertEquals("inner", s2.value.toString(), "the value set in the first step is visible in the second, same context")
            s2.value.close()
            assertEquals("['ctx', 'inner']", PythonTestFixture.eval("_st['seen']").toString())

            // Not a Context: refused before the coroutine runs.
            val fresh = PythonTestFixture.eval("_st['cc2']")
            val notAContext = PyInt.from(42)
            assertFailsWith<IllegalArgumentException> { fresh.step(context = notAContext) }
            assertEquals("['ctx', 'inner']", PythonTestFixture.eval("_st['seen']").toString(), "a refused step must not have run the coroutine")
            assertTrue(errorIndicatorIsClear())

            // No context: the step runs in the calling thread's current context, so the write stays there.
            val s3 = assertIs<PyStep.Yielded>(fresh.step())
            s3.value.close()
            assertEquals("inner", PythonTestFixture.eval("_cv.get()").toString(), "with no context given, the coroutine's write lands in the caller's context")
            val s4 = assertIs<PyStep.Returned>(fresh.step())
            s4.value.close()
            assertEquals("['ctx', 'inner', 'outer', 'inner']", PythonTestFixture.eval("_st['seen']").toString())
            Python3.exec("_cv.set('outer')")
        }

    @Test
    fun aKotlinThrowableThrownIntoTheCoroutineArrivesMappedAndTheOutcomeIsReported() =
        PythonTestFixture.withInterpreter {
            defineFixtures()
            Python3.exec(
                """
                _st['caught'] = None
                async def _st_catch_body():
                    try:
                        await _StPause('wait')
                    except _StCancelled as e:
                        _st['caught'] = (type(e).__name__, str(e), type(e.__cause__).__name__, str(e.__cause__))
                        raise ValueError('after cancel')
                async def _st_key_body():
                    try:
                        await _StPause('w')
                    except KeyError as e:
                        return 'got ' + e.args[0]
                async def _st_same_body():
                    try:
                        await _StPause('w')
                    except ValueError as e:
                        _st['same_id'] = id(e)
                        return 'same'
                async def _st_nocatch_body():
                    await _StPause('w')
                _st['c1'] = _st_catch_body()
                _st['c2'] = _st_key_body()
                _st['c3'] = _st_same_body()
                _st['c4'] = _st_nocatch_body()
                """.trimIndent(),
            )
            // A Kotlin CancellationException arrives as CancelledError, its Kotlin cause on __cause__ (U-2).
            val c1 = PythonTestFixture.eval("_st['c1']")
            assertIs<PyStep.Yielded>(c1.step()).value.close()
            val raised = assertIs<PyStep.Raised>(
                c1.throwInto(CancellationException("stop now", IllegalStateException("why"))),
            )
            assertEquals("ValueError", raised.exception.type?.name)
            assertEquals("after cancel", raised.exception.errMsg)
            assertFalse(raised.exception.isCancelledError())
            assertEquals("CancelledError", raised.exception.context?.type?.name, "the CancelledError is the __context__ of what the coroutine raised")
            assertEquals(
                "('CancelledError', 'stop now', 'RuntimeError', 'IllegalStateException: why')",
                PythonTestFixture.eval("_st['caught']").toString(),
            )
            assertTrue(errorIndicatorIsClear())

            // A Python exception object thrown in; the coroutine catches it and returns.
            val c2 = PythonTestFixture.eval("_st['c2']")
            assertIs<PyStep.Yielded>(c2.step()).value.close()
            val keyExc = PythonTestFixture.eval("KeyError('k')")
            val returned = assertIs<PyStep.Returned>(c2.throwInto(keyExc))
            assertEquals("got k", returned.value.toString())
            returned.value.close()

            // A PyException thrown back in is the original instance, not a copy (U-2's restoration).
            val c3 = PythonTestFixture.eval("_st['c3']")
            assertIs<PyStep.Yielded>(c3.step()).value.close()
            val same = assertIs<PyStep.Returned>(c3.throwInto(raised.exception))
            assertEquals("same", same.value.toString())
            same.value.close()
            assertEquals(
                raised.exception.value!!.pointer.toRawValue().toString(),
                PythonTestFixture.eval("_st['same_id']").toString(),
            )

            // Uncaught: the cancellation comes back out as a Raised that is the cancellation signal.
            val c4 = PythonTestFixture.eval("_st['c4']")
            assertIs<PyStep.Yielded>(c4.step()).value.close()
            val cancelled = assertIs<PyStep.Raised>(c4.throwInto(CancellationException("bye")))
            assertTrue(cancelled.exception.isCancelledError())
            val mapped = assertIs<CancellationException>(cancelled.exception.toKotlinException())
            assertEquals(cancelled.exception, mapped.cause)
            assertTrue(errorIndicatorIsClear())
        }

    @Test
    fun aFinishedCoroutineAnExhaustedGeneratorAndANonCoroutineAreReportedWithoutLeavingAnErrorIndicator() =
        PythonTestFixture.withInterpreter {
            defineFixtures()
            Python3.exec(
                """
                def _st_gen():
                    x = yield 1
                    return x
                async def _st_bad():
                    raise KeyError('x')
                _st['g'] = _st_gen()
                _st['bad'] = _st_bad()
                """.trimIndent(),
            )
            assertFailsWith<IllegalArgumentException> { PythonTestFixture.eval("42").step() }
            assertFailsWith<IllegalArgumentException> { PythonTestFixture.eval("42").throwInto(PythonTestFixture.eval("KeyError('k')")) }
            assertTrue(errorIndicatorIsClear())

            val g = PythonTestFixture.eval("_st['g']")
            val y = assertIs<PyStep.Yielded>(g.step())
            assertEquals("1", y.value.toString())
            y.value.close()
            val r = assertIs<PyStep.Returned>(g.step(PyInt.from(5)))
            assertEquals("5", r.value.toString())
            r.value.close()
            val exhausted = assertIs<PyStep.Returned>(g.step())
            assertTrue(PyNone.isNone(exhausted.value), "an exhausted generator reports Returned(None) again, as its StopIteration does")
            exhausted.value.close()

            val bad = PythonTestFixture.eval("_st['bad']")
            val first = assertIs<PyStep.Raised>(bad.step())
            assertEquals("KeyError", first.exception.type?.name)
            assertTrue(errorIndicatorIsClear(), "the Raised outcome clears the indicator")
            val second = assertIs<PyStep.Raised>(bad.step())
            assertEquals("RuntimeError", second.exception.type?.name)
            assertTrue("already awaited" in second.exception.errMsg, second.exception.errMsg)
            assertTrue(errorIndicatorIsClear())
            assertNull(first.exception.context, "the first failure had no exception being handled")
        }
}
