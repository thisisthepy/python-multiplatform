package python.multiplatform.ffi.upcall

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.withGIL
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.bindUpcallOrNull
import python.native.ffi.toRawValue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The half of cancellation `docs/design/upcall.md` §5.6 left undone: **early notice**.
 *
 * §9.3 established what cooperative cancellation can and cannot be here. `PendingCall.cancel()` is
 * a flag a body reaches through `ensureActive()`; it cannot stop a coroutine, because the
 * continuation that represents the body's current suspension point belongs to whoever created the
 * suspension and the stdlib offers no way to reach it. That much works. What did *not* work is that
 * the flag was only ever set from `AsyncUpcall.resolve` -- **at completion** -- so a body checking
 * `ensureActive()` in a loop kept looping for a result nobody would read, and only learned it had
 * been abandoned at the exact moment the news stopped being useful.
 *
 * That is the gap this pins. The design doc named the pieces it would take:
 *
 * > (a) `_pm_release` 와 같은 `(long) -> int` 스텁 하나, (b) `Future` 에 실어 보낼 `PendingCall`
 * > 핸들, (c) 다섯 타깃의 바인딩, (d) 그 핸들의 수명 관리
 *
 * ### What makes the observation a real one
 *
 * The assertion that matters is read **while the coroutine is still suspended**: `isCancelled` true
 * and `isDone` false, on a call whose continuation has not been resumed even once since the
 * cancellation. A test that only checked the flag afterwards would pass against the old behaviour
 * too, because the old behaviour set exactly that flag -- just too late for anybody to act on. The
 * two are told apart by *when*, so `when` is what is asserted.
 *
 * Every run is forced onto the suspended path first (`type(r).__name__ == 'Future'`), the same
 * guard `AsyncUpcallDeliveryTest` and `AsyncUpcallCancellationTest` use: the fast path would satisfy
 * a value assertion while exercising nothing.
 *
 * ### And the handle it costs
 *
 * Carrying the notice means Python holds a [HandleTable] handle on the [PendingCall] for as long as
 * the `Future` is unsettled, and that table **leaks by construction** -- it is a GC root nothing
 * reclaims on its own. So the reclamation is asserted on both outcomes, cancelled and completed
 * normally, and the fast path is asserted to register nothing at all: a `suspend fun` that never
 * suspends must not start paying for machinery it cannot use.
 */
class AsyncUpcallEarlyCancellationTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(AsyncTrampolineFragment))
        AsyncTrampolineFragment.parked.clear()
        AsyncTrampolineFragment.started.clear()
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
        AsyncTrampolineFragment.parked.clear()
        AsyncTrampolineFragment.started.clear()
    }

    @Test
    fun cancellingTheFutureReachesEnsureActiveWhileTheKotlinCallIsStillSuspended() =
        PythonTestFixture.withInterpreter {
            bindTicker()
            installEarlyCancelHarness()

            val outcome = runEarlyCancellation()

            assertNull(outcome.completerFailure, "the Kotlin observer thread failed: ${outcome.completerFailure}")
            assertEquals(
                "Future",
                outcome.returned,
                "the boundary answered synchronously, so this run never reached the cancellation case",
            )

            // The whole point. Read on a call that has not been resumed since Python cancelled, so
            // "cancelled" here cannot be a completion-time observation wearing the same name.
            assertTrue(
                outcome.cancelledWhileSuspended,
                "Kotlin did not learn about the cancellation until the call completed -- the flag was " +
                    "${if (outcome.cancelledAtObservation) "set" else "clear"} and isDone was " +
                    "${outcome.doneAtObservation} when Python had already cancelled and yielded to its loop",
            )

            // ... and the cooperation point actually fires on the next tick, rather than the body
            // running on to the ceiling for a result nobody will read.
            assertTrue(
                outcome.failure is CancellationException,
                "ensureActive() did not throw on the tick after the cancellation; the body ended with " +
                    "${outcome.failure ?: "no failure at all"}",
            )
            assertTrue(outcome.doneAfterOneTick, "one resumption was not enough to end the cancelled body")

            assertEquals("CancelledError", outcome.awaited, "the await did not see the cancellation")
            assertEquals(emptyList(), outcome.loopErrors, "the early notice disturbed the event loop")
        }

    @Test
    fun theHandleCarryingTheNoticeIsReclaimedWhenTheCallIsCancelled() =
        PythonTestFixture.withInterpreter {
            bindTicker()
            installEarlyCancelHarness()

            val before = HandleTable.liveCount
            val outcome = runEarlyCancellation()

            assertEquals("Future", outcome.returned, "this run never reached the cancellation case")
            assertTrue(outcome.cancelledWhileSuspended, "no early notice was delivered, so no handle was in play")

            // Read on the observer thread the instant the notice landed, before the coroutine was
            // resumed at all. Nothing on the Kotlin side has run for this call since `deliver`
            // registered the handle, so a count back at the baseline here can only mean the Python
            // side gave it back -- which is what `Future.add_done_callback` is being used for.
            assertEquals(
                before,
                outcome.liveHandlesAtNotice,
                "the handle carrying the cancellation notice was not released by the Future's done callback",
            )
            assertEquals(before, HandleTable.liveCount, "a cancelled call left a rooted handle behind")
        }

    @Test
    fun theHandleCarryingTheNoticeIsReclaimedWhenTheCallCompletesNormally() =
        PythonTestFixture.withInterpreter {
            // The outcome that is not cancellation at all. `add_done_callback` fires on an ordinary
            // settle too, which is the reason one callback can own the handle for both outcomes --
            // and the reason a leak here would be silent: nothing about a successful await looks
            // wrong from Python.
            assertTrue(bindUpcallOrNull("async.doubleLater"), "the suspending entry is not in the table")
            Python3.exec("_pm_later = lambda *a, _h=_pm_h: _pm_invoke(_h, a)")
            installEarlyCancelHarness()

            val before = HandleTable.liveCount
            val outcome = runNormalCompletion()

            assertNull(outcome.completerFailure, "the Kotlin completer thread failed: ${outcome.completerFailure}")
            assertEquals("Future", outcome.returned, "this run never reached the delivery path")
            assertEquals("42", outcome.value, "the value did not arrive")

            // Read from the completer thread while the Future was still outstanding. Without this
            // the reclamation assertion below would also hold for a build that never registered a
            // handle at all, which is a different statement entirely.
            assertEquals(
                before + 1,
                outcome.liveHandlesWhileOutstanding,
                "no handle was rooted for the outstanding call, so nothing here was ever reclaimed",
            )
            assertEquals(before, HandleTable.liveCount, "a normally completed call left a rooted handle behind")
            assertEquals(emptyList(), outcome.loopErrors)
        }

    @Test
    fun theFastPathRegistersNoHandleAtAll() =
        PythonTestFixture.withInterpreter {
            // The cost of the early-notice path has to stay on the path that uses it. A body that
            // never reaches a suspension point gets no Future, so there is nothing to hang a
            // done-callback on and nothing to hand a handle to -- and registering one anyway would
            // put a HandleTable write and a Python attribute lookup on the common case.
            val before = HandleTable.liveCount
            // Held for the duration of the call so no cleaner frees the tuple mid-flight.
            val args = PythonTestFixture.eval("(21,)")
            val raw = UpcallTrampoline.invoke(UpcallTable.resolve("async.doubleNow").raw, args.pointer.toRawValue())
            assertTrue(raw != 0L, "the fast path returned NULL")
            assertEquals(
                before,
                HandleTable.liveCount,
                "an already-complete suspending call registered a handle it can never be told to drop",
            )
        }

    // ------------------------------------------------------------------------------------ harness

    private class EarlyOutcome(
        val returned: String,
        val awaited: String,
        val cancelledAtObservation: Boolean,
        val doneAtObservation: Boolean,
        val doneAfterOneTick: Boolean,
        val failure: Throwable?,
        val liveHandlesAtNotice: Int,
        val loopErrors: List<String>,
        val completerFailure: Throwable?,
    ) {
        /** Cancellation seen by a call that had not finished -- the distinction the fix is about. */
        val cancelledWhileSuspended: Boolean get() = cancelledAtObservation && !doneAtObservation
    }

    private class CompletionOutcome(
        val returned: String,
        val value: String,
        val liveHandlesWhileOutstanding: Int,
        val loopErrors: List<String>,
        val completerFailure: Throwable?,
    )

    private fun bindTicker() {
        assertTrue(bindUpcallOrNull("async.tickUntilCancelled"), "the ticking entry is not in the table")
        // Rebound through a default argument for the reason `AsyncUpcallCancellationTest` records:
        // the lambda `bindUpcallOrNull` leaves behind reads the *global* `_pm_h` at call time.
        Python3.exec("_pm_tick = lambda *a, _h=_pm_h: _pm_invoke(_h, a)")
    }

    /**
     * The Python half.
     *
     * `cancel()` settles the `Future` synchronously but its done callbacks are scheduled with
     * `call_soon`, so the notice reaches Kotlin on the loop's next turn rather than inside `cancel()`
     * itself. The two `sleep(0)`s are that turn, and they are also what hands the GIL to the observer
     * thread. `_e['notified']` is set only after them, so a Kotlin observer that waits for it is
     * reading a state the loop has already finished producing rather than racing it.
     */
    private fun installEarlyCancelHarness() {
        Python3.exec(
            """
            import asyncio
            _e = {}
            _e['loop'] = asyncio.new_event_loop()
            _e['loop_errors'] = []
            _e['returned'] = None
            _e['awaited'] = None
            _e['value'] = None
            _e['notified'] = False
            _e['released'] = False

            def _record_early(loop, context):
                exc = context.get('exception')
                _e['loop_errors'].append(
                    type(exc).__name__ + ': ' + str(exc) if exc is not None else str(context.get('message'))
                )

            _e['loop'].set_exception_handler(_record_early)

            async def _early_run():
                r = _pm_tick()
                _e['returned'] = type(r).__name__
                if not hasattr(r, '__await__'):
                    return
                r.cancel()
                # Two turns of the loop: `Future.cancel` only *schedules* its done callbacks.
                await asyncio.sleep(0)
                await asyncio.sleep(0)
                _e['notified'] = True
                # Keep the loop turning -- and the GIL circulating -- while Kotlin reads its side
                # and then ticks the body once.
                for _ in range(4000):
                    if _e['released']:
                        break
                    await asyncio.sleep(0.005)
                try:
                    await r
                except BaseException as ex:
                    _e['awaited'] = type(ex).__name__
                for _ in range(20):
                    await asyncio.sleep(0.005)

            async def _complete_run():
                r = _pm_later(21)
                _e['returned'] = type(r).__name__
                if not hasattr(r, '__await__'):
                    return
                _e['notified'] = True
                _e['value'] = await asyncio.wait_for(r, 20.0)
                # The done callback that gives the handle back is scheduled, not immediate, so the
                # loop has to be given a turn after the await resolves or the reclamation would be
                # asserted before it could possibly have happened.
                for _ in range(20):
                    await asyncio.sleep(0.005)

            def _drive_early():
                _e['loop'].run_until_complete(_early_run())

            def _drive_complete():
                _e['loop'].run_until_complete(_complete_run())
            """.trimIndent(),
        )
    }

    /**
     * Runs the cancelling coroutine on this thread while a Kotlin thread watches the call it
     * abandoned.
     *
     * The observer does three things in order, and the order is the test: wait until Python has
     * cancelled *and* let its loop run the resulting callbacks; read the Kotlin side of the call
     * **without resuming it**; then resume it exactly once and read it again.
     */
    private fun runEarlyCancellation(): EarlyOutcome {
        var completerFailure: Throwable? = null
        var cancelledAtObservation = false
        var doneAtObservation = false
        var doneAfterOneTick = false
        var failure: Throwable? = null
        var liveHandlesAtNotice = -1

        val observer = Thread {
            try {
                val call = AsyncTrampolineFragment.started.poll(30, TimeUnit.SECONDS)
                    ?: throw AssertionError("no suspending Kotlin call ever reached the fixture")

                var polls = 0
                var notified = false
                while (polls++ < 30_000) {
                    if (withGIL { PythonTestFixture.eval("_e['notified']").toString() } == "True") {
                        notified = true
                        break
                    }
                    Thread.sleep(1)
                }
                if (!notified) throw AssertionError("Python never reached `_e['notified']`")

                // Nothing has resumed this call since it suspended, so both readings describe a
                // coroutine that is still parked in the fixture.
                cancelledAtObservation = call.isCancelled
                doneAtObservation = call.isDone
                liveHandlesAtNotice = HandleTable.liveCount

                // Exactly one tick. `ensureActive()` runs immediately after the resumption, so a
                // body that was told in time ends here and one that was not parks again.
                val resume = AsyncTrampolineFragment.parked.poll(30, TimeUnit.SECONDS)
                    ?: throw AssertionError("the ticking body never parked a resumption")
                withGIL { resume.invoke() }

                doneAfterOneTick = call.isDone
                failure = call.failure
                withGIL { Python3.exec("_e['released'] = True") }
            } catch (t: Throwable) {
                completerFailure = t
                // The driving coroutine waits on this flag; leaving it unset would hang the loop
                // for its full 20 s budget and bury the real failure under a timeout.
                try {
                    withGIL { Python3.exec("_e['released'] = True") }
                } catch (_: Throwable) {
                }
            }
        }
        observer.name = "kotlin-early-cancel-observer"
        observer.start()

        Python3.exec("_drive_early()")
        observer.join(90_000)

        return EarlyOutcome(
            returned = PythonTestFixture.eval("_e['returned']").toString(),
            awaited = PythonTestFixture.eval("_e['awaited']").toString(),
            cancelledAtObservation = cancelledAtObservation,
            doneAtObservation = doneAtObservation,
            doneAfterOneTick = doneAfterOneTick,
            failure = failure,
            liveHandlesAtNotice = liveHandlesAtNotice,
            loopErrors = loopErrors(),
            completerFailure = completerFailure,
        )
    }

    /** The other outcome: an ordinary suspended call that is delivered and awaited normally. */
    private fun runNormalCompletion(): CompletionOutcome {
        var completerFailure: Throwable? = null
        var liveHandlesWhileOutstanding = -1

        val completer = Thread {
            try {
                var polls = 0
                var notified = false
                while (polls++ < 30_000) {
                    if (withGIL { PythonTestFixture.eval("_e['notified']").toString() } == "True") {
                        notified = true
                        break
                    }
                    Thread.sleep(1)
                }
                if (!notified) throw AssertionError("Python never reached `_e['notified']`")
                // Python has the Future and the coroutine has not been resumed, so whatever the
                // boundary rooted for this call is rooted now and nothing has given it back.
                liveHandlesWhileOutstanding = HandleTable.liveCount

                val resume = AsyncTrampolineFragment.parked.poll(30, TimeUnit.SECONDS)
                    ?: throw AssertionError("no suspending Kotlin call ever reached the fixture")
                withGIL { resume.invoke() }
            } catch (t: Throwable) {
                completerFailure = t
            }
        }
        completer.name = "kotlin-normal-completer"
        completer.start()

        Python3.exec("_drive_complete()")
        completer.join(90_000)

        return CompletionOutcome(
            returned = PythonTestFixture.eval("_e['returned']").toString(),
            value = PythonTestFixture.eval("_e['value']").toString(),
            liveHandlesWhileOutstanding = liveHandlesWhileOutstanding,
            loopErrors = loopErrors(),
            completerFailure = completerFailure,
        )
    }

    private fun loopErrors(): List<String> {
        val count = PythonTestFixture.eval("len(_e['loop_errors'])").toString().toInt()
        return (0 until count).map { PythonTestFixture.eval("_e['loop_errors'][$it]").toString() }
    }
}
