@file:OptIn(kotlin.native.concurrent.ObsoleteWorkersApi::class)

package python.multiplatform.ffi.upcall

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.getThreadGILState
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
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SPEC U-5's Kotlin/Native thread rule (#164 item 11, pythonx-concurrent S9.6; leader decision
 * 2026-11-21, in 1.0): the supported way to resume a parked continuation on iOS and androidNative
 * is any thread that holds `withGIL` while it resumes. [AsyncUpcallNativeDeliveryTest] proves it
 * for a bare `pthread` the runtime never attached; this file proves it for the thread an
 * application actually has, a Kotlin/Native [Worker], which is also what a `kotlinx.coroutines`
 * dispatcher runs on. The completer here plays exactly the role the pthread plays there: wait for
 * the loop to be running with a `Future` outstanding, resume under the GIL, report its attachment
 * depth afterwards.
 *
 * Red before this file: nothing in `nativeTest` started a `Worker`, so the claim the sample's
 * `awaitSuspendingDemo` on iOS rests on was never observed.
 */
class AsyncUpcallWorkerDeliveryTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(WorkerDeliveryFragment))
        WorkerDeliveryFragment.parked.value = null
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
        WorkerDeliveryFragment.parked.value = null
    }

    @Test
    fun pythonAwaitsASuspendedKotlinCallAndAKotlinNativeWorkerResumesItUnderTheGil() =
        PythonTestFixture.withInterpreter {
            assertEquals(true, bindUpcallOrNull("worker_delivery.doubleLater"), "the suspending entry is not in the table")
            Python3.exec(
                """
                import asyncio
                _w = {}
                _w['loop'] = asyncio.new_event_loop()
                _w['returned'] = None
                _w['value'] = None
                _w['error'] = None

                async def _w_run(v):
                    r = _pm_bound(v)
                    _w['returned'] = type(r).__name__
                    if hasattr(r, '__await__'):
                        return await asyncio.wait_for(r, 20.0)
                    return r

                def _w_drive(v):
                    try:
                        _w['value'] = _w['loop'].run_until_complete(_w_run(v))
                    except BaseException as e:
                        _w['error'] = type(e).__name__ + ': ' + str(e)
                """.trimIndent(),
            )
            val isRunning = PythonTestFixture.eval("_w['loop'].is_running")

            val worker = Worker.start(name = "kotlin-native-completer")
            // The job may capture nothing (Worker.execute's rule); what it needs travels through the
            // producer, and the fragment is an object.
            val report = worker.execute(TransferMode.SAFE, { isRunning }) { isRunning ->
                try {
                    // The same two-condition wait as the pthread test: the loop running *and* the
                    // Future already handed out, or the resume would race `AsyncUpcall.deliver` and
                    // silently take the fast path.
                    var sawHandoff = false
                    var polls = 0
                    while (polls++ < 20_000) {
                        val handedOff = withGIL {
                            isRunning.invoke().toString() == "True" &&
                                PythonTestFixture.eval("_w.get('returned')").toString() != "None"
                        }
                        if (handedOff) {
                            sawHandoff = true
                            break
                        }
                        platform.posix.usleep(500u)
                    }
                    var waited = 0
                    while (WorkerDeliveryFragment.parked.value == null && waited++ < 20_000) {
                        platform.posix.usleep(1_000u)
                    }
                    val resume = WorkerDeliveryFragment.parked.value
                        ?: error("no suspending Kotlin call ever reached the fixture")
                    // Under the GIL, as PendingCall's contract requires: the resumption runs the
                    // delivery listener inline, on this Worker thread.
                    withGIL { resume.invoke() }
                    "handoff=$sawHandoff depth=${getThreadGILState().depth}"
                } catch (t: Throwable) {
                    "failed: ${t::class.simpleName}: ${t.message}"
                }
            }

            Python3.exec("_w_drive(21)")
            val outcome = report.result
            worker.requestTermination().result

            assertEquals("handoff=true depth=0", outcome, "the Worker must observe the hand-off, resume, and be detached afterwards")
            assertEquals("None", PythonTestFixture.eval("_w['error']").toString(), "the coroutine raised")
            assertEquals(
                "Future",
                PythonTestFixture.eval("_w['returned']").toString(),
                "the boundary answered synchronously; this run never exercised the delivery path",
            )
            assertEquals("42", PythonTestFixture.eval("_w['value']").toString())
        }
}

private object WorkerDeliveryFragment : FunctionTableFragment {

    override val moduleName: String = "test_worker_async_delivery"

    /** The resumption a suspended fixture parked, exchanged with the Worker via an atomic. */
    val parked = AtomicReference<(() -> Unit)?>(null)

    private suspend fun doubleLater(x: Long): Long = suspendCoroutine { c -> parked.value = { c.resume(x * 2) } }

    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "worker_delivery.doubleLater",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.INT,
            isSuspend = true,
        ) { args -> PendingCall.start { doubleLater(args[0] as Long) } },
    )
}
