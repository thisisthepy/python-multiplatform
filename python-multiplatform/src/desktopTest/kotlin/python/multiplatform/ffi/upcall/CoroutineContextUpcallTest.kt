package python.multiplatform.ffi.upcall

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
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
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.suspendCoroutine
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * SPEC U-5b (#164, item 1): passing a caller-supplied `CoroutineContext` (including a
 * `ContinuationInterceptor` that dispatches to another thread and a `Job`-like element) to an
 * exposed `suspend` function from Python via `python_multiplatform.use_coroutine_context` /
 * `set_coroutine_context`, without adding any `kotlinx.coroutines` dependency.
 */
class CoroutineContextUpcallTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(CoroutineContextTestFragment))
        assertTrue(bindUpcallOrNull("ctxtest.readElement"))
        PythonProxySource.settleFunction()
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
    }

    @Test
    fun useAndSetCoroutineContextValidateHandlesAndOrderPriority() = PythonTestFixture.withInterpreter {
        val ctx1 = HandleTable.register(TestJobElement("job-1")).raw
        val ctx2 = HandleTable.register(TestJobElement("job-2")).raw
        Python3.exec(
            """
            import contextvars, python_multiplatform as _pm
            _cc = {}
            try:
                _cc['initial'] = _pm.get_coroutine_context() is None
                _prev = _pm.set_coroutine_context($ctx1)
                _cc['prev_none'] = _prev is None
                _cc['after_set'] = _pm.get_coroutine_context() == $ctx1
                with _pm.use_coroutine_context($ctx2):
                    _cc['in_with'] = _pm.get_coroutine_context() == $ctx2
                    _copied = contextvars.copy_context()
                _cc['after_with'] = _pm.get_coroutine_context() == $ctx1
                _cc['in_copied'] = _copied.run(_pm.get_coroutine_context) == $ctx2
                _pm.set_coroutine_context(None)
                _cc['cleared'] = _pm.get_coroutine_context() is None
                try:
                    _pm.set_coroutine_context("not-a-context")
                    _cc['bad'] = 'accepted'
                except TypeError:
                    _cc['bad'] = 'TypeError'
            finally:
                _pm.set_coroutine_context(None)
            """.trimIndent(),
        )
        assertEquals("True", PythonTestFixture.eval("_cc['initial']").toString())
        assertEquals("True", PythonTestFixture.eval("_cc['prev_none']").toString())
        assertEquals("True", PythonTestFixture.eval("_cc['after_set']").toString())
        assertEquals("True", PythonTestFixture.eval("_cc['in_with']").toString())
        assertEquals("True", PythonTestFixture.eval("_cc['after_with']").toString())
        assertEquals("True", PythonTestFixture.eval("_cc['in_copied']").toString())
        assertEquals("True", PythonTestFixture.eval("_cc['cleared']").toString())
        assertEquals("TypeError", PythonTestFixture.eval("_cc['bad']").toString())
    }

    @Test
    fun aJobElementConfiguredViaUseCoroutineContextIsVisibleInCoroutineContext() =
        PythonTestFixture.withInterpreter {
            assertTrue(bindUpcallOrNull("ctxtest.readElement"))
            val jobHandle = HandleTable.register(TestJobElement("my-parent-job")).raw
            Python3.exec(
                """
                import python_multiplatform as _pm
                with _pm.use_coroutine_context($jobHandle):
                    _seen_job = _pm_bound()
                """.trimIndent(),
            )
            assertEquals("my-parent-job", PythonTestFixture.eval("_seen_job").toString())
        }

    @Test
    fun aContinuationInterceptorDispatchesToAnotherThreadAndDeliversResultWithGil() =
        PythonTestFixture.withInterpreter {
            // Deterministic cross-thread path: the dispatched first slice waits on `gate`, which the
            // Python side opens only after the upcall returned. The call therefore cannot complete
            // inline before PendingCall.start returns (the SPEC U-5 fast path), so it must be a Future.
            val gate = CountDownLatch(1)
            CoroutineContextTestFragment.gate = gate
            val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "kotlin-dispatcher-worker") }
            val interceptor = ExecutorInterceptor { task ->
                executor.execute {
                    check(gate.await(10, TimeUnit.SECONDS)) { "the test never opened the gate" }
                    task.run()
                }
            }
            assertTrue(bindUpcallOrNull("ctxtest.releaseGate"))
            Python3.exec("_pm_open_gate = _pm_bound")
            assertTrue(bindUpcallOrNull("ctxtest.dispatchAndReportThread"))
            Python3.exec("_pm_dispatch = _pm_bound")
            val ctxHandle = HandleTable.register(interceptor + TestJobElement("dispatched-job")).raw
            val callerThreadName = Thread.currentThread().name
            try {
                Python3.exec(
                    """
                    import asyncio, python_multiplatform as _pm
                    _disp = {}
                    _disp_loop = asyncio.new_event_loop()

                    async def _run_disp():
                        with _pm.use_coroutine_context($ctxHandle):
                            r = _pm_dispatch()
                            _disp['returned'] = type(r).__name__
                            _pm_open_gate()
                            if hasattr(r, '__await__'):
                                return await asyncio.wait_for(r, 10.0)
                            return r

                    _disp['result'] = _disp_loop.run_until_complete(_run_disp())
                    """.trimIndent(),
                )
                assertEquals("Future", PythonTestFixture.eval("_disp['returned']").toString())
                val result = PythonTestFixture.eval("_disp['result']").toString()
                assertTrue(
                    result.startsWith("kotlin-dispatcher-worker|dispatched-job"),
                    "expected execution on dispatcher worker thread with Job element, got: $result",
                )
                assertNotEquals(callerThreadName, result.substringBefore('|'))
            } finally {
                gate.countDown()
                Python3.exec("_disp_loop.close()")
                executor.shutdownNow()
                CoroutineContextTestFragment.gate = null
            }
        }

    @Test
    fun immediateInterceptorStressTestNeverLosesCompletion() =
        PythonTestFixture.withInterpreter {
            assertTrue(bindUpcallOrNull("ctxtest.dispatchAndReportThread"))
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "kotlin-stress-worker").apply { isDaemon = true }
            }
            val combined: CoroutineContext = ExecutorInterceptor(executor) + TestJobElement("stress-job")
            val ctxRef = HandleTable.register(combined)
            val ctxHandle = ctxRef.raw
            try {
                Python3.exec(
                    """
                    import asyncio
                    import inspect
                    import python_multiplatform as _pm

                    _stress_loop = asyncio.new_event_loop()
                    _stress_count = 0

                    async def _run_stress():
                        global _stress_count
                        with _pm.use_coroutine_context($ctxHandle):
                            for _ in range(2000):
                                r = _pm_bound()
                                # The worker can finish before start() returns; the
                                # call then completes inline and hands back the value
                                # itself (SPEC U-5), not a Future.
                                val = await asyncio.wait_for(r, 1.0) if inspect.isawaitable(r) else r
                                assert val.startswith("kotlin-stress-worker|stress-job"), val
                                _stress_count += 1

                    _stress_loop.run_until_complete(_run_stress())
                    """.trimIndent(),
                )
                assertEquals("2000", PythonTestFixture.eval("_stress_count").toString())
            } finally {
                Python3.exec("_stress_loop.close()")
                executor.shutdownNow()
                HandleTable.release(ctxRef)
            }
        }

    @Test
    fun cancelAndOnCancelledRaceRunsListenerExactlyOnceWithoutCme() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(500) {
                var parked: Continuation<Any?>? = null
                val call = PendingCall.start(EmptyCoroutineContext) {
                    suspendCoroutine<Any?> { c -> parked = c }
                }
                val startLatch = java.util.concurrent.CountDownLatch(1)
                val invocations = java.util.concurrent.atomic.AtomicInteger(0)
                val f1 = pool.submit {
                    startLatch.await()
                    call.cancel()
                }
                val f2 = pool.submit {
                    startLatch.await()
                    call.onCancelled { invocations.incrementAndGet() }
                }
                startLatch.countDown()
                f1.get()
                f2.get()
                assertEquals(1, invocations.get(), "onCancelled listener must run exactly once")
                assertEquals(null, call.cancellationFailure)
                // Clean up the suspended coroutine.
                parked?.resumeWith(Result.success(Unit))
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun concurrentCancelCallsRunOnCancelledListenersExactlyOnce() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(500) {
                var parked: Continuation<Any?>? = null
                val call = PendingCall.start(EmptyCoroutineContext) {
                    suspendCoroutine<Any?> { c -> parked = c }
                }
                val invocations = java.util.concurrent.atomic.AtomicInteger(0)
                call.onCancelled { invocations.incrementAndGet() }
                val startLatch = java.util.concurrent.CountDownLatch(1)
                val f1 = pool.submit<Boolean> {
                    startLatch.await()
                    call.cancel()
                }
                val f2 = pool.submit<Boolean> {
                    startLatch.await()
                    call.cancel()
                }
                startLatch.countDown()
                val r1 = f1.get()
                val r2 = f2.get()
                assertTrue(r1 != r2, "exactly one concurrent cancel() must return true")
                assertEquals(1, invocations.get(), "listener must run exactly once across concurrent cancels")
                parked?.resumeWith(Result.success(Unit))
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun zeroArgStartInsideWithoutGilInAnUpcallGetsEmptyContextWhilePlainStartSeesAmbient() =
        PythonTestFixture.withInterpreter {
            assertTrue(bindUpcallOrNull("ctxtest.readInsideAndOutsideWithoutGil"))
            val ctxRef = HandleTable.register(TestJobElement("process-wide-job"))
            val ctxHandle = ctxRef.raw
            try {
                // The nested start runs inside an upcall (thread attached by UpcallTrampoline.attached),
                // so PyEval_SaveThread in withoutGIL is legal. The old depth-based guard would still
                // read the Python context here (depth > 0) and call into CPython without a thread state.
                Python3.exec(
                    """
                    import python_multiplatform as _pm
                    _pm.set_coroutine_context($ctxHandle)
                    try:
                        _seen_both = _pm_bound()
                    finally:
                        _pm.set_coroutine_context(None)
                    """.trimIndent(),
                )
                assertEquals(
                    "outside=process-wide-job;inside=<empty>",
                    PythonTestFixture.eval("_seen_both").toString(),
                )
            } finally {
                HandleTable.release(ctxRef)
            }
        }
}

private class TestJobElement(val label: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TestJobElement>
}

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

private object CoroutineContextTestFragment : FunctionTableFragment {
    override val moduleName: String = "test_coroutine_context"

    /** Opened by the test through `ctxtest.releaseGate`; null outside the gated test. */
    @Volatile
    var gate: CountDownLatch? = null

    private suspend fun readElement(): String =
        coroutineContext[TestJobElement]?.label ?: "<none>"

    private suspend fun dispatchAndReportThread(): String {
        // `startCoroutine` already dispatches the first slice onto the executor's thread.
        val threadName = Thread.currentThread().name
        val jobLabel = coroutineContext[TestJobElement]?.label ?: "<none>"
        return "$threadName|$jobLabel"
    }

    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "ctxtest.readElement",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.STRING,
            isSuspend = true,
        ) { PendingCall.start { readElement() } },
        ExposedCallable(
            name = "ctxtest.dispatchAndReportThread",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.STRING,
            isSuspend = true,
        ) { PendingCall.start { dispatchAndReportThread() } },
        ExposedCallable(
            name = "ctxtest.releaseGate",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.STRING,
        ) {
            gate?.countDown()
            "released"
        },
        ExposedCallable(
            name = "ctxtest.readInsideAndOutsideWithoutGil",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.STRING,
        ) {
            // Synchronous entry: runs on the upcall thread with inUpcall == true.
            val outside = PendingCall.start { coroutineContext[TestJobElement]?.label ?: "<empty>" }
            val inside = python.multiplatform.ffi.withoutGIL {
                PendingCall.start { coroutineContext[TestJobElement]?.label ?: "<empty>" }
            }
            check(outside.isDone && inside.isDone)
            "outside=${outside.value};inside=${inside.value}"
        },
    )
}
