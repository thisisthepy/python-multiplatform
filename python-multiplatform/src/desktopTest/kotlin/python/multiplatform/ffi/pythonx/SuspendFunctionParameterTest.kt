package python.multiplatform.ffi.pythonx

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
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.startCoroutine
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SPEC U-5a (#164, item 7): `suspend` function-typed parameters (`suspend (A1, ..., AN) -> R`).
 *
 * Covers:
 * - fast path (non-awaitable Python callable returning a value or Unit);
 * - async path (`async def` Python callable awaited on a running asyncio loop, returning Long/String/Unit);
 * - exception mapping (`asyncio.CancelledError` -> Kotlin `CancellationException`, other errors -> `PyException`);
 * - scope requirement (fails with `IllegalStateException` when no `PythonCallableScope` is open);
 * - in-flight safety (closing the `PythonCallableScope` while an `await` is still in flight does not crash
 *   or release the callable before the invocation finishes).
 */
class SuspendFunctionParameterTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(SuspendFnFragment, PythonCallables.Fragment))
        assertTrue(bindUpcallOrNull("suspendfn.runLong"), "test table is not installed")
        PythonxAdapter.install()
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
    }

    @Test
    fun aNonAwaitablePythonCallableTakesTheFastPathWithoutSuspending() = PythonTestFixture.withInterpreter {
        val scope = PythonCallableScope()
        try {
            PythonCallables.withScope(scope) {
                Python3.exec(
                    """
                    import suspendfn
                    _sf_fast = suspendfn.runLong(lambda x: x * 3, 7)
                    """.trimIndent(),
                )
            }
            // Note: runLong is itself a suspend function in the table; because the lambda never
            // suspended, the fast path returned the plain int 21 directly.
            assertEquals("21", PythonTestFixture.eval("_sf_fast").toString())
        } finally {
            scope.close()
        }
    }

    @Test
    fun passingASuspendCallableOutsideAScopeRefusesWithIllegalStateException() = PythonTestFixture.withInterpreter {
        val thrown = assertFailsWith<PyException> {
            Python3.exec(
                """
                import suspendfn
                suspendfn.runLong(lambda x: x + 1, 1)
                """.trimIndent(),
            )
        }
        assertTrue(
            thrown.message?.contains("no pythonx callable scope is open") == true,
            "expected missing scope error, got: ${thrown.message}",
        )
    }

    @Test
    fun anAsyncDefPythonCallableIsAwaitedByKotlinAndReturnsItsValue() = PythonTestFixture.withInterpreter {
        val scope = PythonCallableScope()
        try {
            PythonProxySource.settleFunction()
            Python3.exec(
                """
                import asyncio, suspendfn

                _sf_async = {}
                _sf_loop = asyncio.new_event_loop()

                async def _py_compute(x):
                    await asyncio.sleep(0.01)
                    return x * 2

                async def _driver():
                    res = suspendfn.runLong(_py_compute, 21)
                    if hasattr(res, '__await__'):
                        return await asyncio.wait_for(res, 10.0)
                    return res

                def _run():
                    _sf_async['value'] = _sf_loop.run_until_complete(_driver())
                """.trimIndent(),
            )
            PythonCallables.withScope(scope) {
                Python3.exec("_run()")
            }
            assertEquals("42", PythonTestFixture.eval("_sf_async['value']").toString())
        } finally {
            Python3.exec("_sf_loop.close()")
            scope.close()
        }
    }

    @Test
    fun aPythonCancelledErrorInsideASuspendCallableMapsToKotlinCancellationException() =
        PythonTestFixture.withInterpreter {
            val scope = PythonCallableScope()
            var observedFailure: Throwable? = null
            SuspendFnFragment.onObservedFailure = { observedFailure = it }
            try {
                PythonProxySource.settleFunction()
                Python3.exec(
                    """
                    import asyncio, suspendfn

                    _sf_cancel = {}
                    _sf_loop = asyncio.new_event_loop()

                    async def _py_cancel():
                        await asyncio.sleep(0)
                        raise asyncio.CancelledError('py cancelled')

                    async def _driver():
                        res = suspendfn.runUnitCatching(_py_cancel)
                        if hasattr(res, '__await__'):
                            return await asyncio.wait_for(res, 10.0)
                        return res

                    def _run_cancel():
                        _sf_cancel['result'] = _sf_loop.run_until_complete(_driver())
                    """.trimIndent(),
                )
                PythonCallables.withScope(scope) {
                    Python3.exec("_run_cancel()")
                }
                assertIs<CancellationException>(
                    observedFailure,
                    "CancelledError from Python async callable must reach Kotlin as CancellationException, got $observedFailure",
                )
            } finally {
                SuspendFnFragment.onObservedFailure = null
                Python3.exec("_sf_loop.close()")
                scope.close()
            }
        }

    @Test
    fun closingTheCallableScopeWhileAnAwaitIsInFlightStillCompletesSafely() =
        PythonTestFixture.withInterpreter {
            val scope = PythonCallableScope()
            try {
                PythonProxySource.settleFunction()
                Python3.exec(
                    """
                    import asyncio, suspendfn

                    _sf_close = {}
                    _sf_loop = asyncio.new_event_loop()
                    _sf_gate = None

                    async def _py_wait():
                        global _sf_gate
                        _sf_gate = _sf_loop.create_future()
                        return await _sf_gate

                    _sf_coro_handle = None
                    """.trimIndent(),
                )
                // Wrap the Python callable inside the scope, start the Kotlin suspend call, then
                // close the scope while the Python coroutine is still suspended on _sf_gate.
                var startedLambda: (suspend () -> Any?)? = null
                PythonCallables.withScope(scope) {
                    val pyWait = PythonTestFixture.eval("_py_wait")
                    val handle = PythonCallables.newSuspendFunction(
                        body = pyWait,
                        arity = 0,
                        argTags = "",
                        returnTypeName = "kotlin.Long",
                        internKey = "",
                    )
                    @Suppress("UNCHECKED_CAST")
                    startedLambda = HandleTable.resolveRaw(handle) as (suspend () -> Any?)
                }

                var result: Result<Any?>? = null
                // Drive _sf_loop on a background thread while the Kotlin coroutine invokes startedLambda.
                Python3.exec(
                    """
                    import threading, asyncio
                    _sf_ready_ev = threading.Event()
                    def _sf_loop_main():
                        asyncio.set_event_loop(_sf_loop)
                        _sf_loop.call_soon(_sf_ready_ev.set)
                        _sf_loop.run_forever()
                    _sf_thread = threading.Thread(target=_sf_loop_main, daemon=True)
                    _sf_thread.start()
                    _sf_ready_ev.wait(timeout=10.0)
                    """.trimIndent(),
                )
                // Set _sf_loop as the delivery loop so the Kotlin coroutine can submit to it.
                Python3.exec("import python_multiplatform as _pm; _pm.set_delivery_loop(_sf_loop)")
                val doneLatch = java.util.concurrent.CountDownLatch(1)
                val lambda = startedLambda!!
                val block: suspend () -> Any? = { lambda.invoke() }
                block.startCoroutine(
                    Continuation(EmptyCoroutineContext) { r ->
                        result = r
                        doneLatch.countDown()
                    },
                )

                // Wait until _sf_gate is created (meaning _py_wait is suspended in flight).
                var polls = 0
                while (polls++ < 2000 && PythonTestFixture.eval("_sf_gate is None").toString() == "True") {
                    Thread.sleep(5)
                }
                assertEquals("False", PythonTestFixture.eval("_sf_gate is None").toString())

                // Close the scope while the await is still in flight!
                scope.close()

                // Now resolve _sf_gate on _sf_loop and verify the Kotlin continuation completes with 99L.
                Python3.exec("_sf_loop.call_soon_threadsafe(_sf_gate.set_result, 99)")
                assertTrue(doneLatch.await(10, java.util.concurrent.TimeUnit.SECONDS), "in-flight await did not resume")
                assertEquals(99L, result?.getOrThrow())
            } finally {
                Python3.exec(
                    """
                    import python_multiplatform as _pm
                    _pm.set_delivery_loop(None)
                    _sf_loop.call_soon_threadsafe(_sf_loop.stop)
                    _sf_thread.join(timeout=5.0)
                    _sf_loop.close()
                    """.trimIndent(),
                )
                scope.close()
            }
        }
}

private object SuspendFnFragment : FunctionTableFragment {
    override val moduleName: String = "test_suspend_fn_param"

    var onObservedFailure: ((Throwable) -> Unit)? = null

    @Suppress("UNCHECKED_CAST")
    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "suspendfn.runLong",
            arity = 2,
            paramTypes = listOf(TypeTag.OBJECT, TypeTag.INT),
            returnType = TypeTag.INT,
            isSuspend = true,
            paramNames = listOf("block", "x"),
            paramTypeNames = listOf("kotlin.coroutines.SuspendFunction1(kotlin.Long)->kotlin.Long", "kotlin.Long"),
            returnTypeName = "kotlin.Long",
            paramHasDefault = listOf(false, false),
        ) { args ->
            val block = args[0] as (suspend (Long) -> Long)
            val x = args[1] as Long
            PendingCall.start { block(x) }
        },
        ExposedCallable(
            name = "suspendfn.runUnitCatching",
            arity = 1,
            paramTypes = listOf(TypeTag.OBJECT),
            returnType = TypeTag.UNIT,
            isSuspend = true,
            paramNames = listOf("block"),
            paramTypeNames = listOf("kotlin.coroutines.SuspendFunction0()->kotlin.Unit"),
            returnTypeName = "kotlin.Unit",
            paramHasDefault = listOf(false),
        ) { args ->
            val block = args[0] as (suspend () -> Unit)
            PendingCall.start {
                try {
                    block()
                } catch (t: Throwable) {
                    onObservedFailure?.invoke(t)
                }
            }
        },
    )
}
