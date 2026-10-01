package python.multiplatform.ffi.upcall

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.withGIL
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.PyErr_Occurred
import python.native.ffi.bindUpcallOrNull
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What happens when the Python side gives up on a call that Kotlin is still running --
 * `docs/design/upcall-async-design.md` §8.6's first open question, which that section deliberately refused
 * to answer because it had not been measured.
 *
 * The shape of the problem is that the two halves of a suspended call are owned by different
 * languages. `Future.cancel()` is Python's, and it is *complete* the moment it is called: the
 * awaiting coroutine gets its `CancelledError` and the `Future` is settled forever. The Kotlin
 * coroutine knows nothing about any of that and keeps running, so its completion arrives at a
 * `Future` that will not take it. `set_result` on a cancelled `Future` raises `InvalidStateError`.
 *
 * The question worth asking is not whether that raise happens -- it does -- but **where it lands and
 * what it damages**. This repo has twice been bitten by a Python error indicator left set by one
 * call and then charged to an unrelated later one (`PyObject.getAttrOrNull`'s own comment records
 * the second time), so the assertions below are about the blast radius, not about the raise:
 *
 * - the loop's exception handler, which is where a callback that raises actually surfaces;
 * - the error indicator on the completing thread once delivery has returned;
 * - a subsequent, logically unconnected upcall.
 *
 * Every one of these is checked in a run that is *forced* to take the suspended path: a run where
 * the boundary answered synchronously would satisfy all of them while exercising nothing, so
 * `type(r).__name__` is asserted to be `Future` first, exactly as `AsyncUpcallDeliveryTest` does.
 */
class AsyncUpcallCancellationTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(AsyncTrampolineFragment))
        AsyncTrampolineFragment.parked.clear()
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
        AsyncTrampolineFragment.parked.clear()
    }

    @Test
    fun aCompletionLandingOnACancelledFutureIsDroppedInsteadOfRaisingInsideTheLoop() =
        PythonTestFixture.withInterpreter {
            bindBoth()
            installCancellationHarness()

            val outcome = runCancelledAwait()

            assertNull(outcome.completerFailure, "the Kotlin completer thread failed: ${outcome.completerFailure}")
            assertEquals(
                "Future",
                outcome.returned,
                "the boundary answered synchronously, so this run never reached the cancellation case",
            )
            assertEquals(
                "CancelledError",
                outcome.awaited,
                "cancelling the Future has to reach the awaiting coroutine as CancelledError",
            )
            assertTrue(
                outcome.delivered,
                "the Kotlin coroutine never completed, so nothing was ever delivered to the cancelled Future",
            )

            // The observation this test exists for. A completion that cannot be delivered must end
            // quietly: `set_result` on a cancelled Future raises `InvalidStateError`, and scheduling
            // it unguarded means the loop runs a callback that throws, which asyncio reports through
            // `call_exception_handler` -- a failure the application never asked for and cannot act
            // on, since the value it describes was already abandoned.
            assertEquals(
                emptyList(),
                outcome.loopErrors,
                "delivering to a cancelled Future disturbed the event loop",
            )
        }

    @Test
    fun aCancelledDeliveryLeavesNoErrorIndicatorForTheNextCallToInherit() =
        PythonTestFixture.withInterpreter {
            bindBoth()
            installCancellationHarness()

            val outcome = runCancelledAwait()
            assertEquals("Future", outcome.returned, "this run never reached the cancellation case")
            assertTrue(outcome.delivered)

            // Read on this thread, after the completing thread has finished with it. An indicator
            // left set here is not a latent problem: the very next C API call charges it to whatever
            // it was doing.
            val pending = withGIL { PyErr_Occurred() }
            assertNull(pending, "the abandoned delivery left Python's error indicator set")

            // And the same statement made from Python, which is where it would actually be paid: an
            // unrelated entry, called after the cancellation, through the same boundary.
            Python3.exec("_c['after'] = _pm_now(5)")
            assertEquals(
                "10",
                PythonTestFixture.eval("_c['after']").toString(),
                "an upcall made after an abandoned delivery did not behave normally",
            )
        }

    @Test
    fun aCancellationThatArrivesAfterKotlinScheduledTheDeliveryIsStillDroppedQuietly() =
        PythonTestFixture.withInterpreter {
            bindBoth()
            installCancellationHarness()

            // The case the check on the Kotlin side cannot cover. There, `Future.done()` is read on
            // the completing thread and the settle runs later on the loop thread, so a cancellation
            // landing between the two passes the check and then finds the Future settled anyway.
            // This run forces exactly that order: Kotlin completes and schedules while the Python
            // coroutine is holding the loop, so the callback is queued but cannot run; only then is
            // the Future cancelled. The check on the Kotlin side has already said "not done".
            val outcome = runCancelledAwait(driver = "_drive_late_cancel", completerWaitsFor = "_c.get('go', False)")

            assertNull(outcome.completerFailure, "the Kotlin completer thread failed: ${outcome.completerFailure}")
            assertEquals("Future", outcome.returned, "this run never reached the cancellation case")
            assertTrue(outcome.delivered, "Kotlin never scheduled a delivery, so the race never happened")
            assertEquals("CancelledError", outcome.awaited)
            assertEquals(
                emptyList(),
                outcome.loopErrors,
                "the queued delivery raised when it finally ran; only the guard inside the scheduled " +
                    "callback can cover this ordering",
            )
        }

    // ------------------------------------------------------------------------------------ harness

    private class CancelOutcome(
        val returned: String,
        val awaited: String,
        val delivered: Boolean,
        val loopErrors: List<String>,
        val completerFailure: Throwable?,
    )

    /**
     * Binds two entries at once: the suspending entry the cancellation is aimed at, and an unrelated
     * one used afterwards to show the boundary is still healthy.
     *
     * Each is rebound through a default argument rather than copied out of `_pm_bound`.
     * `bindUpcallOrNull` leaves behind `lambda *a: _pm_invoke(_pm_h, a)`, which reads the *global*
     * `_pm_h` at call time, so aliasing it and binding a second name silently repoints the first
     * alias at the second entry. The first draft of this test did exactly that and the run reported
     * `int` where it expected `Future` -- it had called the fast-path entry twice.
     */
    private fun bindBoth() {
        assertTrue(bindUpcallOrNull("async.doubleLater"), "the suspending entry is not in the table")
        Python3.exec("_pm_later = lambda *a, _h=_pm_h: _pm_invoke(_h, a)")
        assertTrue(bindUpcallOrNull("async.doubleNow"), "the fast-path entry is not in the table")
        Python3.exec("_pm_now = lambda *a, _h=_pm_h: _pm_invoke(_h, a)")
    }

    /**
     * The Python half: cancel the `Future` the boundary handed back *before* Kotlin finishes, then
     * keep the loop alive long enough for anything the completion scheduled to actually run.
     *
     * The exception handler is installed because the failure mode being measured is invisible
     * otherwise -- a callback that raises inside a loop does not propagate anywhere, it is handed to
     * `call_exception_handler`, whose default implementation logs and returns. A test that only
     * looked at the `await` would see a perfectly ordinary `CancelledError` and conclude nothing was
     * wrong.
     */
    private fun installCancellationHarness() {
        Python3.exec(
            """
            import asyncio
            _c = {}
            _c['loop'] = asyncio.new_event_loop()
            _c['loop_errors'] = []
            _c['returned'] = None
            _c['awaited'] = None
            _c['delivered'] = False

            def _record(loop, context):
                exc = context.get('exception')
                _c['loop_errors'].append(
                    type(exc).__name__ + ': ' + str(exc) if exc is not None else str(context.get('message'))
                )

            _c['loop'].set_exception_handler(_record)

            async def _cancel_run():
                r = _pm_later(21)
                _c['returned'] = type(r).__name__
                if not hasattr(r, '__await__'):
                    return
                r.cancel()
                _c['cancel_called'] = True
                try:
                    await r
                except BaseException as e:
                    _c['awaited'] = type(e).__name__
                # Kotlin sets 'delivered' once its completion has been handed off, so the loop is
                # still turning when whatever that handed off gets its chance to run.
                for _ in range(4000):
                    if _c['delivered']:
                        break
                    await asyncio.sleep(0.005)
                for _ in range(20):
                    await asyncio.sleep(0.005)

            async def _late_cancel_run():
                r = _pm_later(21)
                _c['returned'] = type(r).__name__
                if not hasattr(r, '__await__'):
                    return
                # Let Kotlin finish while this coroutine keeps the loop to itself. The spin does
                # *not* await, so the loop's ready queue is never drained and whatever Kotlin
                # schedules sits in it -- but CPython still drops the GIL every switch interval, so
                # the completer thread makes progress. That is the whole point: the delivery is
                # already queued when the cancellation below happens.
                _c['go'] = True
                spins = 0
                while not _c['delivered'] and spins < 200000000:
                    spins += 1
                _c['spins'] = spins
                r.cancel()
                try:
                    await r
                except BaseException as e:
                    _c['awaited'] = type(e).__name__
                for _ in range(40):
                    await asyncio.sleep(0.005)

            def _drive_cancel():
                _c['loop'].run_until_complete(_cancel_run())

            def _drive_late_cancel():
                _c['loop'].run_until_complete(_late_cancel_run())
            """.trimIndent(),
        )
    }

    /**
     * Runs the cancelling coroutine on this thread while a Kotlin thread completes the call it
     * abandoned.
     *
     * The completer waits for `_c['cancel_called']` rather than for the loop merely running, for the
     * same reason `AsyncUpcallDeliveryTest`'s completer waits for two conditions: resuming earlier
     * would let `PendingCall.isDone` be true by the time `AsyncUpcall.deliver` looks, the fast path
     * would be taken, and there would be no `Future` to cancel at all.
     */
    private fun runCancelledAwait(
        driver: String = "_drive_cancel",
        completerWaitsFor: String = "_c.get('cancel_called', False)",
    ): CancelOutcome {
        var completerFailure: Throwable? = null

        val completer = Thread {
            try {
                var polls = 0
                var ready = false
                while (polls++ < 20_000) {
                    if (withGIL { PythonTestFixture.eval(completerWaitsFor).toString() } == "True") {
                        ready = true
                        break
                    }
                    Thread.sleep(1)
                }
                if (!ready) throw AssertionError("Python never reached `$completerWaitsFor`")

                val resume = AsyncTrampolineFragment.parked.poll(20, TimeUnit.SECONDS)
                    ?: throw AssertionError("no suspending Kotlin call ever reached the fixture")
                // Under the GIL, as `PendingCall`'s contract requires: the resumption runs the
                // listener inline, which is where delivery happens.
                withGIL { resume.invoke() }
                withGIL { Python3.exec("_c['delivered'] = True") }
            } catch (t: Throwable) {
                completerFailure = t
            }
        }
        completer.name = "kotlin-cancel-completer"
        completer.start()

        Python3.exec("$driver()")
        completer.join(60_000)

        val errorCount = PythonTestFixture.eval("len(_c['loop_errors'])").toString().toInt()
        val loopErrors = (0 until errorCount).map { PythonTestFixture.eval("_c['loop_errors'][$it]").toString() }

        return CancelOutcome(
            returned = PythonTestFixture.eval("_c['returned']").toString(),
            awaited = PythonTestFixture.eval("_c['awaited']").toString(),
            delivered = PythonTestFixture.eval("_c['delivered']").toString() == "True",
            loopErrors = loopErrors,
            completerFailure = completerFailure,
        )
    }
}
