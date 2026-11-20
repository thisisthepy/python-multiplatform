package python.multiplatform.ffi.pythonx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.upcall.PendingCall
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.ffi.withGIL
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.FunctionTableFragment
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.TypeTag
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.bindUpcallOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SPEC U-5d (#164, item 2): `suspend fun PyObject.await()` and the `PyAwait.submit` primitive under it.
 *
 * Every test here was red before `PyAwait.kt` existed, for the plainest reason: neither `await()` nor
 * `PyAwait` resolved, so the file did not compile. Each test pins one clause of U-5d:
 *
 * - a Python coroutine awaited from a Kotlin coroutine started on a thread CPython never saw, with the
 *   continuation resumed on the loop thread and nothing left in `HandleTable` or the binding's maps;
 * - `PendingCall.cancel()` from Kotlin reaching `Task.cancel()` exactly once, and mapping back as
 *   `CancellationException` caused by the `CancelledError`;
 * - a cancel that lands before the task exists (the loop thread is parked while both are queued);
 * - a Python exception arriving as the `PyException` itself;
 * - awaiting inside an upcall on the loop thread: Python gets a `Future`, nothing deadlocks;
 * - a `ContinuationInterceptor` taking the continuation off the loop thread;
 * - a `kotlinx` `Job` cancellation reaching the task through `PyAwait.submit` + `cancel()`;
 * - the refusals that happen before anything is scheduled.
 *
 * The delivery loop runs on a Python `threading.Thread` and is named with `set_delivery_loop`, the way a
 * host with a Kotlin caller and no running loop on that caller's thread would name it (U-5, item 10).
 */
class PyAwaitTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(AwaitTestFragment, PythonCallables.Fragment))
        assertTrue(bindUpcallOrNull("awaittest.doubleViaPython"), "test table is not installed")
        PythonxAdapter.install()
        PythonProxySource.settleFunction()
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
    }

    @Test
    fun aPythonCoroutineIsAwaitedFromAKotlinCoroutineStartedOnANonPythonThread() =
        PythonTestFixture.withInterpreter {
            startDeliveryLoopThread()
            try {
                Python3.exec(
                    """
                    async def _aw_double(x):
                        await asyncio.sleep(0.01)
                        _aw['task_tid'] = threading.get_ident()
                        return x * 2
                    """.trimIndent(),
                )
                val coro = PythonTestFixture.eval("_aw_double(21)")
                val baseline = HandleTable.liveCount
                val done = CountDownLatch(1)
                var call: PendingCall? = null
                var resumedOn = -1L
                var starterFailure: Throwable? = null
                val starter = Thread({
                    try {
                        val started = PendingCall.start(EmptyCoroutineContext) {
                            val value = coro.await()
                            resumedOn = pythonThreadIdent()
                            value.toString().toLong()
                        }
                        call = started
                        started.onCompleted { done.countDown() }
                    } catch (t: Throwable) {
                        starterFailure = t
                    }
                }, "kotlin-thread-cpython-never-saw")
                starter.start()
                starter.join(10_000)
                starterFailure?.let { throw it }
                assertTrue(done.await(10, TimeUnit.SECONDS), "the await never resumed")

                val completed = call!!
                completed.failure?.let { throw AssertionError("await failed: $it", it) }
                assertEquals(42L, completed.value)
                val loopTid = PythonTestFixture.eval("_aw['loop_tid']").toString().toLong()
                assertEquals(loopTid, resumedOn, "the continuation must resume on the loop thread")
                assertEquals(loopTid, PythonTestFixture.eval("_aw['task_tid']").toString().toLong())
                assertEquals(baseline, HandleTable.liveCount, "the pending handle was not released")
                assertEquals("0", PythonTestFixture.eval("len(_aw_binding._SUSPEND_TASKS) + len(_aw_binding._SUSPEND_LOOPS)").toString())
            } finally {
                stopDeliveryLoopThread()
            }
        }

    @Test
    fun cancellingThePendingCallFromKotlinCancelsTheAsyncioTaskExactlyOnce() =
        PythonTestFixture.withInterpreter {
            startDeliveryLoopThread()
            try {
                Python3.exec(
                    """
                    _aw['cancelled'] = 0
                    async def _aw_wait_forever():
                        _aw['started'] = True
                        try:
                            await _aw_loop.create_future()
                        except asyncio.CancelledError:
                            _aw['cancelled'] += 1
                            raise
                    """.trimIndent(),
                )
                val coro = PythonTestFixture.eval("_aw_wait_forever()")
                val baseline = HandleTable.liveCount
                val done = CountDownLatch(1)
                val call = PendingCall.start(EmptyCoroutineContext) { coro.await() }
                call.onCompleted { done.countDown() }
                assertFalse(call.isDone, "await() must suspend")
                waitUntil("the Python task never started") {
                    PythonTestFixture.eval("_aw.get('started', False)").toString() == "True"
                }

                assertTrue(call.cancel(), "the first cancel is the transition")
                assertTrue(done.await(10, TimeUnit.SECONDS), "cancelling the call did not end the await")
                assertTrue(call.isCancelled)
                val failure = assertIs<CancellationException>(call.failure)
                val cause = assertIs<PyException>(failure.cause, "the Python CancelledError must be the cause")
                assertEquals("CancelledError", cause.type?.name)
                assertFalse(call.cancel(), "a second cancel is not a transition")
                assertEquals("1", PythonTestFixture.eval("_aw['cancelled']").toString(), "Task.cancel() reached the coroutine exactly once")
                assertEquals(baseline, HandleTable.liveCount, "the pending handle was not released")
                assertEquals("0", PythonTestFixture.eval("len(_aw_binding._SUSPEND_TASKS) + len(_aw_binding._SUSPEND_LOOPS)").toString())
            } finally {
                stopDeliveryLoopThread()
            }
        }

    @Test
    fun aCancelThatLandsBeforeTheTaskExistsCancelsTheTaskTheLoopIsAboutToCreate() =
        PythonTestFixture.withInterpreter {
            startDeliveryLoopThread()
            try {
                Python3.exec(
                    """
                    _aw['started'] = False
                    async def _aw_never_runs():
                        _aw['started'] = True
                        await _aw_loop.create_future()

                    # Park the loop thread inside a callback: everything queued from now on, the submit's
                    # `_schedule` and then the cancel, waits behind it in the loop's one FIFO.
                    _aw_block = threading.Event()
                    _aw_loop.call_soon_threadsafe(lambda: _aw_block.wait(10.0))
                    """.trimIndent(),
                )
                val coro = PythonTestFixture.eval("_aw_never_runs()")
                val done = CountDownLatch(1)
                val call = PendingCall.start(EmptyCoroutineContext) { coro.await() }
                call.onCompleted { done.countDown() }
                assertFalse(call.isDone)
                // The task does not exist yet: the loop thread is still parked.
                assertEquals("0", PythonTestFixture.eval("len(_aw_binding._SUSPEND_TASKS)").toString())
                assertTrue(call.cancel())
                Python3.exec("_aw_block.set()")

                assertTrue(done.await(10, TimeUnit.SECONDS), "the cancel queued before the task existed was lost")
                assertIs<CancellationException>(call.failure)
                assertEquals("False", PythonTestFixture.eval("_aw['started']").toString(), "a task cancelled before its first step never runs its body")
                assertEquals("0", PythonTestFixture.eval("len(_aw_binding._SUSPEND_TASKS) + len(_aw_binding._SUSPEND_LOOPS)").toString())
            } finally {
                stopDeliveryLoopThread()
            }
        }

    @Test
    fun aPythonExceptionResumesTheKotlinCoroutineWithThePyException() =
        PythonTestFixture.withInterpreter {
            startDeliveryLoopThread()
            try {
                Python3.exec(
                    """
                    async def _aw_raise():
                        await asyncio.sleep(0)
                        raise ValueError('boom')
                    """.trimIndent(),
                )
                val coro = PythonTestFixture.eval("_aw_raise()")
                val done = CountDownLatch(1)
                val call = PendingCall.start(EmptyCoroutineContext) { coro.await() }
                call.onCompleted { done.countDown() }
                assertTrue(done.await(10, TimeUnit.SECONDS), "the failing await never resumed")
                val failure = assertIs<PyException>(call.failure)
                assertEquals("ValueError", failure.type?.name)
                assertTrue(failure.errMsg.contains("boom"), "expected the Python message, got: ${failure.errMsg}")
                assertFalse(call.isCancelled)
                assertEquals("0", PythonTestFixture.eval("len(_aw_binding._SUSPEND_TASKS) + len(_aw_binding._SUSPEND_LOOPS)").toString())
            } finally {
                stopDeliveryLoopThread()
            }
        }

    @Test
    fun awaitingFromInsideAnUpcallOnTheLoopThreadHandsPythonAFutureAndDoesNotDeadlock() =
        PythonTestFixture.withInterpreter {
            // No delivery loop is configured: the loop that matters is the one running on the calling
            // thread, inside `run_until_complete` below, and that is the thread the upcall arrives on.
            Python3.exec(
                """
                import asyncio, awaittest
                _aw_in = {}
                _aw_in_loop = asyncio.new_event_loop()

                async def _aw_inner():
                    await asyncio.sleep(0.01)
                    return 21

                async def _aw_outer():
                    r = awaittest.doubleViaPython(_aw_inner())
                    _aw_in['returned'] = type(r).__name__
                    if hasattr(r, '__await__'):
                        return await asyncio.wait_for(r, 10.0)
                    return r
                """.trimIndent(),
            )
            try {
                Python3.exec("_aw_in['value'] = _aw_in_loop.run_until_complete(_aw_outer())")
            } catch (e: PyException) {
                throw AssertionError("the upcall that awaited Python failed with ${e.type?.name}: ${e.errMsg}", e)
            } finally {
                Python3.exec("_aw_in_loop.close()")
            }
            assertEquals("Future", PythonTestFixture.eval("_aw_in['returned']").toString(), "await() always suspends, so Python must see a Future")
            assertEquals("42", PythonTestFixture.eval("_aw_in['value']").toString())
        }

    @Test
    fun theContinuationIsDispatchedThroughTheCallersInterceptorNotRunOnTheLoopThread() =
        PythonTestFixture.withInterpreter {
            startDeliveryLoopThread()
            val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "await-dispatcher") }
            try {
                Python3.exec(
                    """
                    async def _aw_one():
                        await asyncio.sleep(0.01)
                        return 1
                    """.trimIndent(),
                )
                val coro = PythonTestFixture.eval("_aw_one()")
                val done = CountDownLatch(1)
                val call = PendingCall.start(ExecutorInterceptor(executor)) {
                    val value = coro.await()
                    // Read where the continuation actually ran, as both Kotlin and Python see it.
                    Thread.currentThread().name + "|" + pythonThreadIdent() + "|" + value
                }
                call.onCompleted { done.countDown() }
                assertTrue(done.await(10, TimeUnit.SECONDS), "the dispatched await never completed")
                call.failure?.let { throw AssertionError("await failed: $it", it) }
                val (threadName, tid, value) = (call.value as String).split("|")
                assertEquals("await-dispatcher", threadName)
                assertNotEquals(PythonTestFixture.eval("_aw['loop_tid']").toString(), tid, "the continuation must not run on the loop thread")
                assertEquals("1", value)
            } finally {
                executor.shutdownNow()
                stopDeliveryLoopThread()
            }
        }

    @Test
    fun aKotlinxJobCancellationReachesTaskCancelThroughTheSubmitPrimitive() =
        PythonTestFixture.withInterpreter {
            startDeliveryLoopThread()
            try {
                Python3.exec(
                    """
                    _aw['cancelled'] = 0
                    async def _aw_job_wait():
                        _aw['started'] = True
                        try:
                            await _aw_loop.create_future()
                        except asyncio.CancelledError:
                            _aw['cancelled'] += 1
                            _aw['cancelling'] = asyncio.current_task().cancelling()
                            raise
                    """.trimIndent(),
                )
                val coro = PythonTestFixture.eval("_aw_job_wait()")
                var submitted: PyAwait? = null
                var firstCancel: Boolean? = null
                var outcome: Result<PyObject>? = null
                // `outcome` is written inside the completion callback, after `PyAwait.isDone` turned
                // true, so the latch (not the flag) is what makes it visible here.
                val reported = CountDownLatch(1)
                runBlocking {
                    val job = launch(Dispatchers.Default) {
                        suspendCancellableCoroutine<PyObject> { cont ->
                            val pyAwait = PyAwait.submit(coro) { result ->
                                outcome = result
                                reported.countDown()
                                cont.resumeWith(result)
                            }
                            submitted = pyAwait
                            cont.invokeOnCancellation { firstCancel = pyAwait.cancel() }
                        }
                    }
                    waitUntil("the Python task never started") {
                        PythonTestFixture.eval("_aw.get('started', False)").toString() == "True"
                    }
                    job.cancelAndJoin()
                }
                val pyAwait = assertNotNull(submitted)
                assertEquals(true, firstCancel, "the first cancel of an in-flight await hands Task.cancel() to the loop")
                assertTrue(reported.await(10, TimeUnit.SECONDS), "the task's done callback never reported")
                assertTrue(pyAwait.isDone)
                assertFalse(pyAwait.cancel(), "a cancel after completion does nothing")
                val failure = assertIs<CancellationException>(outcome?.exceptionOrNull())
                assertEquals("CancelledError", assertIs<PyException>(failure.cause).type?.name)
                assertEquals("1", PythonTestFixture.eval("_aw['cancelled']").toString(), "Task.cancel() reached the coroutine exactly once")
                assertEquals("1", PythonTestFixture.eval("_aw['cancelling']").toString(), "one Task.cancel() request, not two")
                assertEquals("0", PythonTestFixture.eval("len(_aw_binding._SUSPEND_TASKS) + len(_aw_binding._SUSPEND_LOOPS)").toString())
            } finally {
                stopDeliveryLoopThread()
            }
        }

    @Test
    fun aSecondCancelWhileTheTaskIsStillCancellingHandsNothingToTheLoop() =
        PythonTestFixture.withInterpreter {
            startDeliveryLoopThread()
            try {
                Python3.exec(
                    """
                    _aw['started'] = False
                    _aw_hold = threading.Event()
                    async def _aw_slow_cancel():
                        _aw['started'] = True
                        try:
                            await _aw_loop.create_future()
                        except asyncio.CancelledError:
                            # Park the loop thread inside the handler: the task is cancelling, not done.
                            _aw_hold.wait(10.0)
                            _aw['cancelling'] = asyncio.current_task().cancelling()
                            raise
                    """.trimIndent(),
                )
                val coro = PythonTestFixture.eval("_aw_slow_cancel()")
                val reported = CountDownLatch(1)
                val pyAwait = PyAwait.submit(coro) { reported.countDown() }
                waitUntil("the Python task never started") {
                    PythonTestFixture.eval("_aw.get('started', False)").toString() == "True"
                }
                assertTrue(pyAwait.cancel(), "the first cancel is handed to the loop")
                assertFalse(pyAwait.cancel(), "a second cancel, loop still open, hands nothing on")
                assertFalse(pyAwait.cancel(), "and neither does a third")
                Python3.exec("_aw_hold.set()")
                assertTrue(reported.await(10, TimeUnit.SECONDS), "the await never ended")
                assertEquals("1", PythonTestFixture.eval("_aw['cancelling']").toString(), "Task.cancel() was requested once")
            } finally {
                Python3.exec("if '_aw_hold' in globals(): _aw_hold.set()")
                stopDeliveryLoopThread()
            }
        }

    @Test
    fun aCancelOnAnAwaitWhoseLoopClosedBeforeAnyCancelEndsItWithCancellationException() =
        PythonTestFixture.withInterpreter {
            startDeliveryLoopThread()
            try {
                parkAndStopLoop()
                val coro = PythonTestFixture.eval("_aw_stranded_coro")
                val baseline = HandleTable.liveCount
                var outcome: Result<PyObject>? = null
                val reported = CountDownLatch(1)
                val pyAwait = PyAwait.submit(coro) { outcome = it; reported.countDown() }
                assertFalse(pyAwait.isDone)
                closeParkedLoop()

                assertTrue(pyAwait.cancel(), "a cancel on a closed loop ends the await locally")
                assertTrue(reported.await(10, TimeUnit.SECONDS), "the stranded continuation was never resumed")
                assertStrandedEnd(outcome, pyAwait, baseline)
            } finally {
                Python3.exec("_aw_stranded_coro.close()")
                stopDeliveryLoopThread()
            }
        }

    @Test
    fun aCancelQueuedBeforeTheLoopClosedStillResumesTheContinuationExactlyOnce() =
        PythonTestFixture.withInterpreter {
            startDeliveryLoopThread()
            try {
                parkAndStopLoop()
                val coro = PythonTestFixture.eval("_aw_stranded_coro")
                val baseline = HandleTable.liveCount
                val results = java.util.concurrent.CopyOnWriteArrayList<Result<PyObject>>()
                val reported = CountDownLatch(1)
                val pyAwait = PyAwait.submit(coro) { results += it; reported.countDown() }
                // The loop is still open (parked): the cancel is queued with call_soon_threadsafe,
                // behind the submit's `_schedule`, and neither can run before the loop exits.
                assertTrue(pyAwait.cancel(), "the first cancel is handed to the open loop")
                assertFalse(pyAwait.isDone)
                // Now the loop exits (stopped from inside the parked callback) and is closed;
                // `close()` drops the queued `_schedule` and cancel, so the done callback never runs.
                closeParkedLoop()
                assertFalse(pyAwait.isDone, "nothing on the closed loop can end the await by itself")

                assertTrue(pyAwait.cancel(), "a later cancel finds the loop closed and ends the await")
                assertTrue(reported.await(10, TimeUnit.SECONDS), "the stranded continuation was never resumed")
                assertFalse(pyAwait.cancel(), "the await is over; nothing resumes twice")
                assertEquals(1, results.size, "the continuation must be resumed exactly once")
                assertStrandedEnd(results.single(), pyAwait, baseline)
            } finally {
                Python3.exec("_aw_stranded_coro.close()")
                stopDeliveryLoopThread()
            }
        }

    @Test
    fun aReceiverWithoutAwaitOrNoLoopFailsBeforeAnythingIsScheduled() =
        PythonTestFixture.withInterpreter {
            Python3.exec("import python_multiplatform as _pm, python_multiplatform.binding as _aw_binding; _pm.set_delivery_loop(None)")
            val baseline = HandleTable.liveCount

            val notAwaitable = PendingCall.start(EmptyCoroutineContext) { PythonTestFixture.eval("42").await() }
            assertTrue(notAwaitable.isDone, "the refusal happens before the suspension")
            assertIs<IllegalArgumentException>(notAwaitable.failure)

            Python3.exec(
                """
                async def _aw_unused():
                    return 1
                _aw_coro = _aw_unused()
                """.trimIndent(),
            )
            try {
                val noLoop = PendingCall.start(EmptyCoroutineContext) { PythonTestFixture.eval("_aw_coro").await() }
                assertTrue(noLoop.isDone, "the refusal happens before the suspension")
                val failure = assertIs<IllegalStateException>(noLoop.failure)
                assertTrue(failure.message?.contains("no running event loop") == true, "unexpected message: ${failure.message}")
            } finally {
                // Never scheduled, so close it ourselves rather than leave a "never awaited" warning.
                Python3.exec("_aw_coro.close()")
            }
            assertEquals(baseline, HandleTable.liveCount, "a refused await must register nothing")
            assertEquals("0", PythonTestFixture.eval("len(_aw_binding._SUSPEND_TASKS) + len(_aw_binding._SUSPEND_LOOPS)").toString())
        }

    // ------------------------------------------------------------------------------- helpers

    /** A loop on a Python thread, named as the delivery loop; `_aw` is the dict the tests write into. */
    private fun startDeliveryLoopThread() {
        Python3.exec(
            """
            import asyncio, threading, python_multiplatform as _pm
            import python_multiplatform.binding as _aw_binding
            _aw = {}
            _aw_loop = asyncio.new_event_loop()
            _aw_ready = threading.Event()

            def _aw_main():
                _aw['loop_tid'] = threading.get_ident()
                asyncio.set_event_loop(_aw_loop)
                _aw_loop.call_soon(_aw_ready.set)
                _aw_loop.run_forever()

            _aw_thread = threading.Thread(target=_aw_main, name='await-delivery-loop', daemon=True)
            _aw_thread.start()
            if not _aw_ready.wait(timeout=10.0):
                raise RuntimeError('the delivery loop thread did not start')
            _pm.set_delivery_loop(_aw_loop)
            """.trimIndent(),
        )
    }

    /**
     * Parks the loop thread inside a callback that has already asked the loop to stop, and returns
     * once it is parked. Whatever is queued from now on (the submit's `_schedule`, a cancel) is not in
     * that iteration's snapshot, so it stays queued when `run_forever` returns: the order the
     * `BaseEventLoop.close()` race needs, with no timing in it. [closeParkedLoop] ends it.
     */
    private fun parkAndStopLoop() {
        Python3.exec(
            """
            _aw_block = threading.Event()
            _aw_parked = threading.Event()
            def _aw_park():
                _aw_loop.stop()
                _aw_parked.set()
                _aw_block.wait(10.0)
            _aw_loop.call_soon_threadsafe(_aw_park)
            if not _aw_parked.wait(10.0):
                raise RuntimeError('the loop thread never parked')
            async def _aw_stranded():
                await _aw_loop.create_future()
            _aw_stranded_coro = _aw_stranded()
            """.trimIndent(),
        )
    }

    /** Lets the parked loop thread return from `run_forever`, then closes the loop (drops what was queued). */
    private fun closeParkedLoop() {
        Python3.exec(
            """
            _aw_block.set()
            _aw_thread.join(10.0)
            if _aw_thread.is_alive():
                raise RuntimeError('the loop thread did not exit')
            _aw_loop.close()
            """.trimIndent(),
        )
    }

    private fun assertStrandedEnd(outcome: Result<PyObject>?, pyAwait: PyAwait, baseline: Int) {
        val failure = assertIs<CancellationException>(outcome?.exceptionOrNull())
        assertEquals("the delivery loop is closed", failure.message)
        assertEquals(null, failure.cause, "a closed loop has no Python CancelledError to blame")
        assertTrue(pyAwait.isDone)
        assertEquals(baseline, HandleTable.liveCount, "the pending handle was not released")
        assertEquals("0", PythonTestFixture.eval("len(_aw_binding._SUSPEND_TASKS) + len(_aw_binding._SUSPEND_LOOPS)").toString())
    }

    private fun stopDeliveryLoopThread() {
        Python3.exec(
            """
            import python_multiplatform as _pm
            _pm.set_delivery_loop(None)
            if '_aw_block' in globals():
                _aw_block.set()
            if '_aw_loop' in globals() and not _aw_loop.is_closed():
                _aw_loop.call_soon_threadsafe(_aw_loop.stop)
                _aw_thread.join(timeout=5.0)
                _aw_loop.close()
            """.trimIndent(),
        )
    }

    /** `threading.get_ident()` of the calling thread, attaching it if it never touched Python. */
    private fun pythonThreadIdent(): Long = withGIL {
        Python3.import("threading").getAttr("get_ident").invoke().toString().toLong()
    }

    private fun waitUntil(message: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError(message)
            Thread.sleep(5)
        }
    }
}

/** Dispatches every resumption onto [executor]; no gate, unlike `CoroutineContextUpcallTest`'s. */
private class ExecutorInterceptor(
    private val executor: java.util.concurrent.Executor,
) : AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> =
        object : Continuation<T> {
            override val context: CoroutineContext = continuation.context
            override fun resumeWith(result: Result<T>) {
                executor.execute { continuation.resumeWith(result) }
            }
        }
}

/**
 * One exposed `suspend fun` that awaits the Python awaitable it is given and doubles the result: the
 * "await inside an upcall on the loop thread" case of U-5d.
 */
private object AwaitTestFragment : FunctionTableFragment {
    override val moduleName: String = "test_py_await"

    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "awaittest.doubleViaPython",
            arity = 1,
            paramTypes = listOf(TypeTag.OBJECT),
            returnType = TypeTag.INT,
            isSuspend = true,
            paramNames = listOf("awaitable"),
            paramTypeNames = listOf("python.multiplatform.ffi.PyObject"),
            returnTypeName = "kotlin.Long",
            paramHasDefault = listOf(false),
        ) { args ->
            val awaitable = args[0] as PyObject
            PendingCall.start { awaitable.await().toString().toLong() * 2 }
        },
    )
}
