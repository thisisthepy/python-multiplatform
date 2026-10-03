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
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `AsyncUpcallCancellationTest` (`desktopTest`) pinned §9.1/§9.2's guard: a Kotlin body that does
 * *not* check [ensureActive] runs to real completion after Python has already cancelled the
 * `Future` it was headed for, and `_pm_settle`'s own `done()` recheck (not the completing thread's
 * earlier one -- §9.2 measured that the earlier check alone is not a guarantee) is what keeps that
 * from raising `InvalidStateError` inside the loop. This is the same race with [NativeThread]
 * standing in for the JVM completer thread, distinct from `AsyncUpcallNativeEarlyCancellationTest`:
 * that one is about a *cooperating* body noticing cancellation early, this one is about a
 * *non-cooperating* body's late completion being dropped quietly rather than disturbing the loop.
 */
class AsyncUpcallNativeCancellationTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(NativeCancelFragment))
        NativeCancelFragment.parked.value = null
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
        NativeCancelFragment.parked.value = null
    }

    @Test
    fun aCompletionLandingOnACancelledFutureIsDroppedInsteadOfRaisingInsideTheLoop() =
        PythonTestFixture.withInterpreter {
            assertTrue(bindUpcallOrNull("native_cancel.doubleLater"))
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

                async def _c_run():
                    r = _pm_bound(21)
                    _c['returned'] = type(r).__name__
                    if not hasattr(r, '__await__'):
                        return
                    r.cancel()
                    _c['cancel_called'] = True
                    try:
                        await r
                    except BaseException as e:
                        _c['awaited'] = type(e).__name__
                    for _ in range(4000):
                        if _c['delivered']:
                            break
                        await asyncio.sleep(0.005)
                    for _ in range(20):
                        await asyncio.sleep(0.005)

                def _c_drive():
                    _c['loop'].run_until_complete(_c_run())
                """.trimIndent(),
            )

            var completerFailure: Throwable? = null

            val completer = NativeThread {
                try {
                    var polls = 0
                    var ready = false
                    while (polls++ < 20_000) {
                        if (withGIL { PythonTestFixture.eval("_c.get('cancel_called', False)").toString() } == "True") {
                            ready = true
                            break
                        }
                        platform.posix.usleep(1_000u)
                    }
                    if (!ready) error("Python never reached `_c['cancel_called']`")

                    var waited = 0
                    while (NativeCancelFragment.parked.value == null && waited++ < 20_000) {
                        platform.posix.usleep(1_000u)
                    }
                    val resume = NativeCancelFragment.parked.value
                        ?: error("no suspending Kotlin call ever reached the fixture")
                    // Under the GIL, as PendingCall's contract requires: the resumption runs the
                    // listener inline, which is where delivery -- and the settle guard -- happens.
                    withGIL { resume.invoke() }
                    withGIL { Python3.exec("_c['delivered'] = True") }
                } catch (t: Throwable) {
                    completerFailure = t
                }
            }
            completer.start()

            Python3.exec("_c_drive()")
            completer.join()

            assertNull(completerFailure, "the native completer thread failed: $completerFailure")
            assertEquals(
                "Future",
                PythonTestFixture.eval("_c['returned']").toString(),
                "the boundary answered synchronously, so this run never reached the cancellation case",
            )
            assertEquals(
                "CancelledError",
                PythonTestFixture.eval("_c['awaited']").toString(),
                "cancelling the Future has to reach the awaiting coroutine as CancelledError",
            )
            assertTrue(
                PythonTestFixture.eval("_c['delivered']").toString() == "True",
                "the Kotlin coroutine never completed, so nothing was ever delivered to the cancelled Future",
            )
            val errorCount = PythonTestFixture.eval("len(_c['loop_errors'])").toString().toInt()
            assertEquals(0, errorCount, "delivering to a cancelled Future disturbed the event loop")

            // The abandoned delivery must not leave Python's error indicator set for the next
            // upcall to inherit -- this repo has been bitten by exactly that shape twice before
            // (PyObject.getAttrOrNull's own comment records the second time).
            val pending = withGIL { python.native.ffi.PyErr_Occurred() }
            assertNull(pending, "the abandoned delivery left Python's error indicator set")
        }
}

private object NativeCancelFragment : FunctionTableFragment {

    override val moduleName: String = "test_native_cancel"

    val parked = AtomicReference<(() -> Unit)?>(null)

    /** Deliberately non-cooperative: no [ensureActive], so cancellation can only be noticed late. */
    private suspend fun doubleLater(x: Long): Long = suspendCoroutine { c -> parked.value = { c.resume(x * 2) } }

    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "native_cancel.doubleLater",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.INT,
            isSuspend = true,
        ) { args -> PendingCall.start { doubleLater(args[0] as Long) } },
    )
}
