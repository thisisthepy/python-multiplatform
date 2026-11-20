package python.multiplatform.ffi.pythonx

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.upcall.PendingCall
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.ffi.upcall.currentPendingCall
import python.multiplatform.reflection.CallableKind
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.FunctionTableFragment
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.ReflectedClass
import python.multiplatform.reflection.ReflectedClassKind
import python.multiplatform.reflection.TypeTag
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.bindUpcallOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end desktop test for SPEC U-5c (#164 item 3), on the real `kotlinx.coroutines` flow
 * types: a Python `async def` collector adapted through the `suspend` SAM `fun interface` slot
 * (`FlowCollector<in T>`, whose single abstract method `emit` is `suspend`), collecting
 * `flowOf(...)`, a `MutableStateFlow` and a `MutableSharedFlow`, plus the `MutableStateFlow.value`
 * read and write (U-4a) and a Kotlin collector handle passed where a callable is accepted.
 *
 * `kotlinx-coroutines-core` is a **desktopTest** dependency only (`python-multiplatform/
 * build.gradle.kts`): the library has none and must not grow one (docs/design/upcall.md section
 * 5.4). The table below is hand-written in the shape the KSP generator and the artifact walker
 * emit for this slot (`KsAdapters.suspendSamShapeOrNull`, `ArtifactScanner.suspendSamSlotOrNull`):
 * the read expression checks `v is kotlin.Function2<*, *, *>` and wraps `v` in a `FlowCollector`
 * lambda, otherwise casts, and the `suspend` member starts its body in a `PendingCall`.
 *
 * ### Cancellation (SPEC U-5b, #490; this test's second half)
 *
 * `Flow.collect` on a `SharedFlow` or `StateFlow` never returns on its own, so cancelling the
 * `asyncio.Future` from Python is the only way a collect ends. The body here does what an exposed
 * `suspend fun` that owns a `Job` is expected to do: `currentPendingCall()?.onCancelled { job.cancel() }`.
 * Two moments are tested, because the Kotlin coroutine is parked in two different places:
 *
 * 1. parked inside `collect`, waiting for the next emission (a `kotlinx` suspension, which the
 *    `Job` cancels directly);
 * 2. parked inside the adapter, while the Python collector is itself awaiting (`PythonSuspendFunction`'s
 *    `suspendCoroutine`, which no `Job` reaches): the adapter cancels the collector's asyncio task
 *    through the same `onCancelled` seam, the task's done callback resumes the Kotlin continuation
 *    with `CancellationException`, and the collect unwinds.
 *
 * What makes these tests able to fail: before this change the second cancellation left the Kotlin
 * coroutine parked for as long as the collector's `await` lasted (forever, for a gate nobody
 * resolves), with the in-flight `PyObject` reference held; and with `_pm_cancel` unbound, or the
 * `onCancelled` listener absent, the first cancellation never reaches the `Job` at all and the
 * collect keeps waiting while Python has already moved on. Every run is forced through the
 * suspended path: the `asyncio.Future` is cancelled before Kotlin completes, and the Kotlin-side
 * outcome is read only after the loop has finished.
 */
class FlowCollectUpcallTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(FlowCollectTestFragment, PythonCallables.Fragment))
        // Publishes `_pm_resolve`, `_pm_invoke`, `_pm_release` and `_pm_cancel` into `__main__`:
        // without `_pm_cancel`, `AsyncUpcall` arms no cancellation notice and `Future.cancel()`
        // is observed by Kotlin only at completion (`PythonProxySource.watchFunctionOrNull`).
        assertTrue(bindUpcallOrNull("flowtest.makeNumberFlow"), "test table is not installed")
        PythonProxySource.settleFunction()
        PythonxAdapter.install()
        FlowCollectTestFragment.reset()
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
        FlowCollectTestFragment.reset()
    }

    @Test
    fun pythonAsyncCollectorCollectsAColdFlowAndStateFlowReadsAndWrites() = PythonTestFixture.withInterpreter {
        val scope = PythonCallableScope()
        try {
            Python3.exec(
                """
                import asyncio
                import flowtest

                _fc = {}
                _fc_loop = asyncio.new_event_loop()
                _fc_collected = []

                async def _fc_run():
                    state = flowtest.makeStateFlow(10)
                    _fc['initial'] = state.value
                    state.value = 42
                    _fc['after_set'] = state.value

                    flow = flowtest.makeNumberFlow()
                    async def on_item(x):
                        await asyncio.sleep(0)
                        _fc_collected.append(x)

                    r = flow.collect(on_item)
                    _fc['returned'] = type(r).__name__
                    if hasattr(r, '__await__'):
                        await asyncio.wait_for(r, 10.0)

                    # A Kotlin collector handle where a callable is accepted: `emit` never
                    # suspends, so the whole collect completes inside the call (the fast path).
                    kt_collector = flowtest.makeKotlinCollector()
                    r2 = flow.collect(kt_collector)
                    _fc['kotlin_returned'] = type(r2).__name__
                    if hasattr(r2, '__await__'):
                        await asyncio.wait_for(r2, 10.0)
                    return kt_collector.count

                def _fc_main():
                    _fc['count'] = _fc_loop.run_until_complete(_fc_run())
                """.trimIndent(),
            )
            try {
                PythonCallables.withScope(scope) {
                    Python3.exec("_fc_main()")
                }
            } catch (e: PyException) {
                throw AssertionError("collecting the cold flow failed with ${e.type?.name}: ${e.errMsg}", e)
            }
            assertEquals("10", PythonTestFixture.eval("_fc['initial']").toString())
            assertEquals("42", PythonTestFixture.eval("_fc['after_set']").toString())
            assertEquals(
                "Future",
                PythonTestFixture.eval("_fc['returned']").toString(),
                "the async collector never suspended the collect, so the adapted path was not exercised",
            )
            assertEquals("[1, 2, 3]", PythonTestFixture.eval("_fc_collected").toString())
            assertEquals("NoneType", PythonTestFixture.eval("_fc['kotlin_returned']").toString())
            assertEquals("3", PythonTestFixture.eval("_fc['count']").toString())
            assertEquals(1, scope.liveCount, "one Python callable crossed, and the scope owns it until close")
            assertEquals(2, FlowCollectTestFragment.collectEnded, "two collects ran to the end")
            assertNull(FlowCollectTestFragment.lastCall?.failure)
        } finally {
            Python3.exec("_fc_loop.close()")
            scope.close()
        }
    }

    @Test
    fun passingAPythonCollectorOutsideAScopeRefusesByName() = PythonTestFixture.withInterpreter {
        val thrown = assertFailsWith<PyException> {
            Python3.exec(
                """
                import flowtest
                flowtest.makeNumberFlow().collect(lambda x: None)
                """.trimIndent(),
            )
        }
        assertTrue(
            thrown.message?.contains("no pythonx callable scope is open") == true,
            "expected the missing-scope refusal, got: ${thrown.message}",
        )
    }

    @Test
    fun cancellingTheFutureWhileCollectWaitsForTheNextEmissionCancelsTheJobAndEndsTheCollect() =
        PythonTestFixture.withInterpreter {
            val scope = PythonCallableScope()
            try {
                Python3.exec(
                    """
                    import asyncio
                    import flowtest

                    _sh = {}
                    _sh_loop = asyncio.new_event_loop()
                    _sh_items = []

                    async def _sh_run():
                        shared = flowtest.makeSharedFlow()
                        async def on_item(x):
                            await asyncio.sleep(0)
                            _sh_items.append(x)
                        task = asyncio.ensure_future(shared.collect(on_item))
                        _sh['returned'] = type(task).__name__
                        # The collect is parked inside `SharedFlow.collect`, waiting. One emission
                        # resumes it on this thread; the collector's coroutine then runs on the loop.
                        _sh['emitted'] = shared.tryEmit(7)
                        for _ in range(10):
                            await asyncio.sleep(0)
                        _sh['items_before_cancel'] = list(_sh_items)
                        # Parked again, waiting for a second emission that never comes.
                        _sh['cancelled'] = task.cancel()
                        # `_pm_watch`'s done callback runs on the next turn and tells Kotlin.
                        for _ in range(10):
                            await asyncio.sleep(0)
                        try:
                            await task
                            _sh['awaited'] = 'returned'
                        except asyncio.CancelledError:
                            _sh['awaited'] = 'CancelledError'

                    def _sh_main():
                        _sh_loop.run_until_complete(_sh_run())
                    """.trimIndent(),
                )
                try {
                    PythonCallables.withScope(scope) {
                        Python3.exec("_sh_main()")
                    }
                } catch (e: PyException) {
                    throw AssertionError("the shared-flow collect failed with ${e.type?.name}: ${e.errMsg}", e)
                }
                assertEquals("Future", PythonTestFixture.eval("_sh['returned']").toString())
                assertEquals("True", PythonTestFixture.eval("_sh['emitted']").toString(), "tryEmit found no collector")
                assertEquals("[7]", PythonTestFixture.eval("_sh['items_before_cancel']").toString())
                assertEquals("True", PythonTestFixture.eval("_sh['cancelled']").toString())
                assertEquals("CancelledError", PythonTestFixture.eval("_sh['awaited']").toString())

                // Read after the loop finished: nothing on the Kotlin side runs for this call any more.
                val call = assertNotNull(FlowCollectTestFragment.lastCall, "the collect never started a PendingCall")
                val job = assertNotNull(FlowCollectTestFragment.lastJob, "the collect never made a Job")
                assertTrue(call.isCancelled, "Future.cancel() did not reach PendingCall.cancel()")
                assertTrue(job.isCancelled, "the onCancelled listener did not cancel the Job")
                assertTrue(call.isDone, "the collect is still running after its Job was cancelled")
                assertIs<CancellationException>(
                    call.failure,
                    "the collect ended with ${call.failure ?: "a value"} instead of unwinding on the cancelled Job",
                )
                assertEquals(1, FlowCollectTestFragment.collectEnded, "the collect body did not finish exactly once")
                assertNull(call.cancellationFailure, "an onCancelled listener threw")
                assertNull(call.deliveryFailure, "delivering the cancelled outcome threw")
                assertEquals(1, scope.liveCount)
            } finally {
                Python3.exec("_sh_loop.close()")
                assertEquals(1, scope.close(), "the scope released a different number of callables than it held")
                assertEquals(0, scope.liveCount)
            }
        }

    @Test
    fun cancellingTheFutureWhileThePythonCollectorIsAwaitingCancelsTheCollectorTaskAndEndsTheCollect() =
        PythonTestFixture.withInterpreter {
            val scope = PythonCallableScope()
            val handlesBefore = HandleTable.liveCount
            try {
                Python3.exec(
                    """
                    import asyncio
                    import flowtest

                    _ic = {}
                    _ic_loop = asyncio.new_event_loop()
                    _ic_gate = None

                    async def _ic_run():
                        global _ic_gate
                        shared = flowtest.makeSharedFlow()
                        async def on_item(x):
                            global _ic_gate
                            _ic_gate = _ic_loop.create_future()
                            try:
                                await _ic_gate
                            except asyncio.CancelledError:
                                _ic['collector_cancelled'] = True
                                raise
                        task = asyncio.ensure_future(shared.collect(on_item))
                        _ic['emitted'] = shared.tryEmit(7)
                        for _ in range(10):
                            await asyncio.sleep(0)
                        # The collector is awaiting a gate nobody resolves; the Kotlin collect is
                        # parked inside the adapter, where no Job can reach it.
                        _ic['gate_open'] = _ic_gate is not None and not _ic_gate.done()
                        _ic['cancelled'] = task.cancel()
                        for _ in range(20):
                            await asyncio.sleep(0)
                        try:
                            await task
                            _ic['awaited'] = 'returned'
                        except asyncio.CancelledError:
                            _ic['awaited'] = 'CancelledError'
                        _ic['gate_cancelled'] = _ic_gate.cancelled()

                    def _ic_main():
                        _ic_loop.run_until_complete(_ic_run())
                    """.trimIndent(),
                )
                try {
                    PythonCallables.withScope(scope) {
                        Python3.exec("_ic_main()")
                    }
                } catch (e: PyException) {
                    throw AssertionError("the in-flight cancellation run failed with ${e.type?.name}: ${e.errMsg}", e)
                }
                assertEquals("True", PythonTestFixture.eval("_ic['emitted']").toString(), "tryEmit found no collector")
                assertEquals("True", PythonTestFixture.eval("_ic['gate_open']").toString(), "the collector was not mid-await when cancelled")
                assertEquals("True", PythonTestFixture.eval("_ic['cancelled']").toString())
                assertEquals("CancelledError", PythonTestFixture.eval("_ic['awaited']").toString())
                assertEquals(
                    "True",
                    PythonTestFixture.eval("_ic.get('collector_cancelled', False)").toString(),
                    "the collector's asyncio task was not cancelled when the Future was",
                )
                assertEquals("True", PythonTestFixture.eval("_ic['gate_cancelled']").toString())

                val call = assertNotNull(FlowCollectTestFragment.lastCall)
                val job = assertNotNull(FlowCollectTestFragment.lastJob)
                assertTrue(call.isCancelled)
                assertTrue(job.isCancelled)
                assertTrue(
                    call.isDone,
                    "the Kotlin collect is still parked inside the adapter after the collector's task was cancelled",
                )
                assertIs<CancellationException>(call.failure, "the collect ended with ${call.failure ?: "a value"}")
                assertEquals(1, FlowCollectTestFragment.collectEnded)
                assertNull(call.cancellationFailure, "an onCancelled listener threw")
                assertNull(call.deliveryFailure)
            } finally {
                Python3.exec("_ic_loop.close()")
                scope.close()
            }
            // Every root this run made is given back: the collector wrapper (by the scope), the
            // cancellation notice (by `_pm_watch`'s done callback and `resolve`), the in-flight
            // invocation (by `completeSuspendCall`), and the proxies (by their `__del__`, once the
            // coroutine's locals are gone).
            Python3.exec("import gc; _ic_gate = None; _ic.clear(); gc.collect()")
            assertEquals(handlesBefore, HandleTable.liveCount, "a cancelled in-flight collect left a rooted handle behind")
        }
}

/** A Kotlin `FlowCollector` handed to Python as a handle; counts what it was given. */
private class CountingCollector : FlowCollector<Any?> {
    var count: Long = 0L
    override suspend fun emit(value: Any?) {
        count += 1L
    }
}

private object FlowCollectTestFragment : FunctionTableFragment {
    override val moduleName: String = "test_flow_collect"

    @Volatile var lastJob: CompletableJob? = null
    @Volatile var lastCall: PendingCall? = null
    @Volatile var collectEnded: Int = 0

    fun reset() {
        lastJob = null
        lastCall = null
        collectEnded = 0
    }

    /**
     * What an exposed `suspend fun` that owns a `kotlinx` `Job` does with `PendingCall`'s
     * cancellation flag (SPEC U-5b): forward it. `withContext(job)` makes `job` the parent of the
     * collect, so `job.cancel()` resumes whatever `kotlinx` suspension the collect is parked in
     * with `CancellationException`, inline on the cancelling thread (there is no dispatcher).
     */
    private suspend fun collectUnderJob(flow: Flow<Any?>, collector: FlowCollector<Any?>) {
        val job = Job()
        lastJob = job
        val call = currentPendingCall()
        lastCall = call
        call?.onCancelled { job.cancel(CancellationException("the Python side cancelled this collect")) }
        try {
            withContext(job) { flow.collect(collector) }
        } finally {
            collectEnded += 1
            job.complete()
        }
    }

    override fun classes(): List<ReflectedClass> = listOf(
        ReflectedClass(
            name = "flowtest.Flow",
            memberNames = listOf("flowtest.Flow.collect"),
            kind = ReflectedClassKind.INTERFACE,
        ),
        ReflectedClass(
            name = "flowtest.FlowCollector",
            memberNames = emptyList(),
            kind = ReflectedClassKind.INTERFACE,
        ),
        ReflectedClass(
            name = "flowtest.MutableStateFlow",
            memberNames = listOf("flowtest.MutableStateFlow.value", "flowtest.MutableStateFlow.value="),
        ),
        ReflectedClass(
            name = "flowtest.MutableSharedFlow",
            memberNames = listOf("flowtest.MutableSharedFlow.tryEmit"),
        ),
        ReflectedClass(
            name = "flowtest.CountingCollector",
            memberNames = listOf("flowtest.CountingCollector.count"),
        ),
    )

    @Suppress("UNCHECKED_CAST")
    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "flowtest.makeNumberFlow",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.OBJECT,
            returnTypeName = "flowtest.Flow",
        ) {
            flowOf(1L, 2L, 3L)
        },
        ExposedCallable(
            name = "flowtest.makeStateFlow",
            arity = 1,
            paramTypes = listOf(TypeTag.OBJECT),
            returnType = TypeTag.OBJECT,
            paramNames = listOf("initial"),
            paramTypeNames = listOf("kotlin.Any"),
            returnTypeName = "flowtest.MutableStateFlow|flowtest.Flow",
        ) { args ->
            MutableStateFlow<Any?>(args[0])
        },
        ExposedCallable(
            name = "flowtest.makeSharedFlow",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.OBJECT,
            returnTypeName = "flowtest.MutableSharedFlow|flowtest.Flow",
        ) {
            // A buffer, so `tryEmit` from Python succeeds against a suspending collector and
            // resumes it inline instead of answering false.
            MutableSharedFlow<Any?>(extraBufferCapacity = 8)
        },
        ExposedCallable(
            name = "flowtest.makeKotlinCollector",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.OBJECT,
            returnTypeName = "flowtest.CountingCollector|flowtest.FlowCollector",
        ) {
            CountingCollector()
        },
        // Generic class member property getter (star projection) and setter (Any? cast), SPEC U-4a:
        ExposedCallable(
            name = "flowtest.MutableStateFlow.value",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.OBJECT,
            kind = CallableKind.GETTER,
            receiverTypeName = "flowtest.MutableStateFlow",
            returnTypeName = "kotlin.Any",
        ) { args ->
            (args[0] as MutableStateFlow<*>).value
        },
        ExposedCallable(
            name = "flowtest.MutableStateFlow.value=",
            arity = 1,
            paramTypes = listOf(TypeTag.OBJECT),
            returnType = TypeTag.UNIT,
            kind = CallableKind.SETTER,
            receiverTypeName = "flowtest.MutableStateFlow",
            paramNames = listOf("value"),
            paramTypeNames = listOf("kotlin.Any"),
            returnTypeName = "kotlin.Unit",
        ) { args ->
            (args[0] as MutableStateFlow<Any?>).value = args[1]
            Unit
        },
        ExposedCallable(
            name = "flowtest.MutableSharedFlow.tryEmit",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.BOOLEAN,
            kind = CallableKind.METHOD,
            receiverTypeName = "flowtest.MutableSharedFlow",
            paramNames = listOf("value"),
            paramTypeNames = listOf("kotlin.Long"),
            returnTypeName = "kotlin.Boolean",
        ) { args ->
            (args[0] as MutableSharedFlow<Any?>).tryEmit(args[1] as Long)
        },
        // Suspend member function with a suspend SAM fun interface parameter (SPEC U-5c), in the
        // shape `KsAdapters.suspendSamShapeOrNull` renders:
        ExposedCallable(
            name = "flowtest.Flow.collect",
            arity = 1,
            paramTypes = listOf(TypeTag.OBJECT),
            returnType = TypeTag.UNIT,
            kind = CallableKind.METHOD,
            receiverTypeName = "flowtest.Flow",
            paramNames = listOf("collector"),
            paramTypeNames = listOf("flowtest.FlowCollector|kotlin.coroutines.SuspendFunction1(kotlin.Any)->kotlin.Unit"),
            returnTypeName = "kotlin.Unit",
            isSuspend = true,
        ) { args ->
            val flow = args[0] as Flow<Any?>
            val collector = (args[1]).let { v ->
                if (v is kotlin.Function2<*, *, *>) {
                    FlowCollector<Any?> { p0 -> (v as (suspend (Any?) -> Unit)).invoke(p0) }
                } else {
                    v as FlowCollector<Any?>
                }
            }
            PendingCall.start { collectUnderJob(flow, collector) }
        },
        ExposedCallable(
            name = "flowtest.CountingCollector.count",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.INT,
            kind = CallableKind.GETTER,
            receiverTypeName = "flowtest.CountingCollector",
            returnTypeName = "kotlin.Long",
        ) { args ->
            (args[0] as CountingCollector).count
        },
    )
}
