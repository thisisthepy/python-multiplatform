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
 * `AsyncUpcallDeliveryTest` (`desktopTest`) pinned that candidate (C)/(B) delivery works, but its
 * completing thread is a `java.util.concurrent`/`java.lang.Thread` -- unavailable outside the JVM
 * targets, which is why that file never moved. `docs/design/upcall-async-design.md` §10.6 records the
 * gap this closes: the delivery path itself is `commonMain` with no `expect`/`actual`, so every
 * target compiles the same source, but only desktop had ever been measured to *run* it.
 *
 * [NativeThread] is the portable replacement for the JVM thread: a bare `pthread_create`, entered
 * the same way a foreign C host or CPython's own worker threads are. The completer here plays
 * exactly the role `AsyncUpcallDeliveryTest`'s does -- wait for the loop to be running with a
 * `Future` outstanding, then resume the parked continuation under the GIL.
 */
class AsyncUpcallNativeDeliveryTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(NativeDeliveryFragment))
        NativeDeliveryFragment.parked.value = null
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
        NativeDeliveryFragment.parked.value = null
    }

    @Test
    fun pythonAwaitsASuspendedKotlinCallAndTheValueArrivesFromAThreadTheRuntimeNeverAttachedItself() =
        PythonTestFixture.withInterpreter {
            assertTrue(
                bindUpcallOrNull("native_delivery.doubleLater"),
                "the suspending entry is not in the table",
            )
            Python3.exec(
                """
                import asyncio
                _n = {}
                _n['loop'] = asyncio.new_event_loop()
                _n['returned'] = None
                _n['value'] = None
                _n['error'] = None

                async def _n_run(v):
                    r = _pm_bound(v)
                    _n['returned'] = type(r).__name__
                    if hasattr(r, '__await__'):
                        return await asyncio.wait_for(r, 20.0)
                    return r

                def _n_drive(v):
                    try:
                        _n['value'] = _n['loop'].run_until_complete(_n_run(v))
                    except BaseException as e:
                        _n['error'] = type(e).__name__ + ': ' + str(e)
                """.trimIndent(),
            )

            var sawLoopRunning = false
            var completerFailure: Throwable? = null
            val isRunning = PythonTestFixture.eval("_n['loop'].is_running")

            val completer = NativeThread {
                try {
                    // Same two-condition wait `AsyncUpcallDeliveryTest.runAwaitOnTheLoopThread`
                    // documents: `is_running()` alone can observe the brief window where
                    // `CFUNCTYPE`/the upcall itself has dropped the GIL, before the `Future`
                    // exists -- resuming there would race `AsyncUpcall.deliver` and silently take
                    // the fast path instead of exercising delivery.
                    var polls = 0
                    while (polls++ < 20_000) {
                        val handedOff = withGIL {
                            isRunning.invoke().toString() == "True" &&
                                PythonTestFixture.eval("_n.get('returned')").toString() != "None"
                        }
                        if (handedOff) {
                            sawLoopRunning = true
                            break
                        }
                        platform.posix.usleep(500u)
                    }
                    var waited = 0
                    while (NativeDeliveryFragment.parked.value == null && waited++ < 20_000) {
                        platform.posix.usleep(1_000u)
                    }
                    val resume = NativeDeliveryFragment.parked.value
                        ?: error("no suspending Kotlin call ever reached the fixture")
                    // Under the GIL, as PendingCall's contract requires -- the resumption runs
                    // the listener inline, which is where delivery happens.
                    withGIL { resume.invoke() }
                } catch (t: Throwable) {
                    completerFailure = t
                }
            }
            completer.start()

            Python3.exec("_n_drive(21)")
            completer.join()

            assertNull(completerFailure, "the native completer thread failed: $completerFailure")
            assertTrue(
                sawLoopRunning,
                "the completer never observed the loop running with a Future outstanding, so this " +
                    "run proves nothing about the case the design depends on",
            )
            assertEquals("None", PythonTestFixture.eval("_n['error']").toString(), "the coroutine raised")
            assertEquals(
                "Future",
                PythonTestFixture.eval("_n['returned']").toString(),
                "the boundary answered synchronously; this run never exercised the delivery path",
            )
            assertEquals("42", PythonTestFixture.eval("_n['value']").toString())
        }

    @Test
    fun theFastPathStillTakesNoAsyncioOnThisTargetEither() =
        PythonTestFixture.withInterpreter {
            assertTrue(bindUpcallOrNull("native_delivery.doubleNow"))
            Python3.exec(
                """
                import asyncio
                _f = {}
                async def _f_run():
                    r = _pm_bound(21)
                    _f['returned'] = type(r).__name__
                    if hasattr(r, '__await__'):
                        return await r
                    return r
                _f['value'] = asyncio.new_event_loop().run_until_complete(_f_run())
                """.trimIndent(),
            )
            assertEquals("42", PythonTestFixture.eval("_f['value']").toString())
            assertEquals(
                "int",
                PythonTestFixture.eval("_f['returned']").toString(),
                "an already-complete call must not be routed through asyncio on this target either",
            )
        }
}

private object NativeDeliveryFragment : FunctionTableFragment {

    override val moduleName: String = "test_native_async_delivery"

    /** The resumption a suspended fixture parked, exchanged with [NativeThread] via an atomic. */
    val parked = AtomicReference<(() -> Unit)?>(null)

    private suspend fun doubleLater(x: Long): Long = suspendCoroutine { c -> parked.value = { c.resume(x * 2) } }

    private suspend fun doubleNow(x: Long): Long = x * 2

    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "native_delivery.doubleLater",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.INT,
            isSuspend = true,
        ) { args -> PendingCall.start { doubleLater(args[0] as Long) } },
        ExposedCallable(
            name = "native_delivery.doubleNow",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.INT,
            isSuspend = true,
        ) { args -> PendingCall.start { doubleNow(args[0] as Long) } },
    )
}
