package python.multiplatform.ffi.upcall

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.withGIL
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.FunctionTableFragment
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.TypeTag
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.bindUpcallOrNull
import kotlin.concurrent.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `AsyncUpcallEarlyCancellationTest` (`desktopTest`) pinned §10's early-notice path --
 * `Future.cancel()` reaching `ensureActive()` before the call completes, through `_pm_cancel` --
 * but its observer thread is a `java.lang.Thread`. `_pm_cancel` itself has no JVM dependency: it
 * is a `PyMethodDef` `UpcallEntry.publish` installs identically on every native target
 * (`docs/design/upcall.md` §5.6's table lists `nativeMain` once, "iOS·androidNative
 * 공용"). This is that path exercised with [NativeThread] standing in for the JVM thread, so the
 * `desktop` cell in §10.6's "확인하지 않은 것" list stops being the only one that says "measured".
 *
 * The assertion that matters is the same one the desktop test's docstring calls out: `isCancelled`
 * has to be read **while the coroutine is still suspended**, not after it happens to finish --
 * the old (pre-§10) behaviour set the same flag, just too late to be early notice at all.
 */
class AsyncUpcallNativeEarlyCancellationTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(NativeEarlyCancelFragment))
        NativeEarlyCancelFragment.parked.value = null
        NativeEarlyCancelFragment.started.value = null
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
        NativeEarlyCancelFragment.parked.value = null
        NativeEarlyCancelFragment.started.value = null
    }

    @Test
    fun cancellingTheFutureReachesEnsureActiveWhileTheKotlinCallIsStillSuspendedOnAThreadTheRuntimeNeverAttachedItself() =
        PythonTestFixture.withInterpreter {
            assertTrue(bindUpcallOrNull("native_early_cancel.tickUntilCancelled"))
            Python3.exec(
                """
                import asyncio
                _e = {}
                _e['loop'] = asyncio.new_event_loop()
                _e['loop_errors'] = []
                _e['returned'] = None
                _e['awaited'] = None
                _e['notified'] = False
                _e['released'] = False

                def _record(loop, context):
                    exc = context.get('exception')
                    _e['loop_errors'].append(
                        type(exc).__name__ + ': ' + str(exc) if exc is not None else str(context.get('message'))
                    )
                _e['loop'].set_exception_handler(_record)

                async def _e_run():
                    r = _pm_bound()
                    _e['returned'] = type(r).__name__
                    if not hasattr(r, '__await__'):
                        return
                    r.cancel()
                    # Future.cancel() only *schedules* its done callbacks -- two turns of the loop
                    # is what AsyncUpcallEarlyCancellationTest's harness uses to let them run.
                    await asyncio.sleep(0)
                    await asyncio.sleep(0)
                    _e['notified'] = True
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

                def _e_drive():
                    _e['loop'].run_until_complete(_e_run())
                """.trimIndent(),
            )

            var completerFailure: Throwable? = null
            var cancelledAtObservation = false
            var doneAtObservation = false
            var doneAfterOneTick = false
            var failure: Throwable? = null

            val observer = NativeThread {
                try {
                    var waitedForStart = 0
                    while (NativeEarlyCancelFragment.started.value == null && waitedForStart++ < 30_000) {
                        platform.posix.usleep(1_000u)
                    }
                    val call = NativeEarlyCancelFragment.started.value
                        ?: error("no suspending Kotlin call ever reached the fixture")

                    var polls = 0
                    var notified = false
                    while (polls++ < 30_000) {
                        if (withGIL { PythonTestFixture.eval("_e['notified']").toString() } == "True") {
                            notified = true
                            break
                        }
                        platform.posix.usleep(1_000u)
                    }
                    if (!notified) error("Python never reached `_e['notified']`")

                    // Nothing has resumed this call since it suspended, so both readings describe
                    // a coroutine that is still parked in the fixture.
                    cancelledAtObservation = call.isCancelled
                    doneAtObservation = call.isDone

                    var waited = 0
                    while (NativeEarlyCancelFragment.parked.value == null && waited++ < 30_000) {
                        platform.posix.usleep(1_000u)
                    }
                    val resume = NativeEarlyCancelFragment.parked.value
                        ?: error("the ticking body never parked a resumption")
                    withGIL { resume.invoke() }

                    doneAfterOneTick = call.isDone
                    failure = call.failure
                    withGIL { Python3.exec("_e['released'] = True") }
                } catch (t: Throwable) {
                    completerFailure = t
                    try {
                        withGIL { Python3.exec("_e['released'] = True") }
                    } catch (_: Throwable) {
                    }
                }
            }
            observer.start()

            Python3.exec("_e_drive()")
            observer.join()

            assertNull(completerFailure, "the native observer thread failed: $completerFailure")
            assertEquals(
                "Future",
                PythonTestFixture.eval("_e['returned']").toString(),
                "the boundary answered synchronously, so this run never reached the cancellation case",
            )
            assertTrue(
                cancelledAtObservation && !doneAtObservation,
                "Kotlin did not learn about the cancellation until the call completed -- the flag " +
                    "was ${if (cancelledAtObservation) "set" else "clear"} and isDone was " +
                    "$doneAtObservation when Python had already cancelled and yielded to its loop",
            )
            assertTrue(
                failure is CancellationException,
                "ensureActive() did not throw on the tick after the cancellation; the body ended " +
                    "with ${failure ?: "no failure at all"}",
            )
            assertTrue(doneAfterOneTick, "one resumption was not enough to end the cancelled body")
            assertEquals(
                "CancelledError",
                PythonTestFixture.eval("_e['awaited']").toString(),
                "the await did not see the cancellation",
            )
            val errorCount = PythonTestFixture.eval("len(_e['loop_errors'])").toString().toInt()
            assertEquals(0, errorCount, "the early notice disturbed the event loop")
        }
}

private object NativeEarlyCancelFragment : FunctionTableFragment {

    override val moduleName: String = "test_native_early_cancel"

    val parked = AtomicReference<(() -> Unit)?>(null)
    val started = AtomicReference<PendingCall?>(null)

    private const val TICK_CEILING = 1000L

    private suspend fun tickUntilCancelled(): Long {
        var ticks = 0L
        while (ticks < TICK_CEILING) {
            suspendCoroutine { c: kotlin.coroutines.Continuation<Unit> -> parked.value = { c.resume(Unit) } }
            ticks++
            ensureActive()
        }
        return ticks
    }

    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "native_early_cancel.tickUntilCancelled",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.INT,
            isSuspend = true,
        ) { PendingCall.start { tickUntilCancelled() }.also { started.value = it } },
    )
}
