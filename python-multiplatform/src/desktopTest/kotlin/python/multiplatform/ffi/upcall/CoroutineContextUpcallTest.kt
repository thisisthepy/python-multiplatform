package python.multiplatform.ffi.upcall

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.FunctionTableFragment
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.TypeTag
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.bindUpcallOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
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
            assertTrue(bindUpcallOrNull("ctxtest.dispatchAndReportThread"))
            val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "kotlin-dispatcher-worker") }
            val interceptor = ExecutorInterceptor(executor)
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
                            r = _pm_bound()
                            _disp['returned'] = type(r).__name__
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
                Python3.exec("_disp_loop.close()")
                executor.shutdownNow()
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

    private suspend fun readElement(): String =
        coroutineContext[TestJobElement]?.label ?: "<none>"

    private suspend fun dispatchAndReportThread(): String {
        // Yield once through the ContinuationInterceptor so we land on the executor's thread.
        suspendCoroutine<Unit> { c -> c.resumeWith(Result.success(Unit)) }
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
    )
}
