package python.multiplatform.ffi.pythonx

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.upcall.PendingCall
import python.multiplatform.reflection.CallableKind
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.FunctionTableFragment
import python.multiplatform.reflection.ReflectedClass
import python.multiplatform.reflection.TypeTag
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end desktop test for SPEC U-4a + U-5 + U-5c (#164 item 3):
 * collecting a Flow-like generic interface from Python using an `async def` collector adapted
 * through the `suspend` SAM `fun interface` slot, plus reading/writing a `StateFlow`-like generic
 * property and passing a Kotlin collector handle directly.
 */
class FlowCollectUpcallTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(FlowCollectTestFragment))
    }

    @AfterTest
    fun clear() {
        UpcallTable.install(emptyList())
    }

    @Test
    fun pythonAsyncCollectorCollectsKotlinFlowAndStateFlowReadsAndWrites() =
        PythonTestFixture.withInterpreter {
            PythonxAdapter.install()
            Python3.exec(
                """
                import asyncio
                import pythonx
                import flowtest

                _flow_loop = asyncio.new_event_loop()
                _collected = []

                async def _run():
                    state = flowtest.makeStateFlow(10)
                    assert state.value == 10, state.value
                    state.value = 42
                    assert state.value == 42, state.value

                    flow = flowtest.makeNumberFlow()
                    async def on_item(x):
                        await asyncio.sleep(0)
                        _collected.append(x)

                    with pythonx.callable_scope():
                        await flow.collect(on_item)

                    # Outside a callable_scope, passing a Python callable must raise RuntimeError.
                    try:
                        await flow.collect(on_item)
                        raise AssertionError("expected RuntimeError outside callable_scope")
                    except RuntimeError:
                        pass

                    # Passing a Kotlin collector handle directly works without a callable_scope.
                    kt_collector = flowtest.makeKotlinCollector()
                    await flow.collect(kt_collector)
                    return kt_collector.count

                _kt_count = _flow_loop.run_until_complete(_run())
                _flow_loop.close()
                """.trimIndent(),
            )
            assertEquals("[1, 2, 3]", PythonTestFixture.eval("_collected").toString())
            assertEquals("3", PythonTestFixture.eval("_kt_count").toString())
        }
}

private fun interface MiniFlowCollector<in T> {
    suspend fun emit(value: T)
}

private interface MiniFlow<out T> {
    suspend fun collect(collector: MiniFlowCollector<T>)
}

private class MiniMutableStateFlow<T>(var value: T) : MiniFlow<T> {
    override suspend fun collect(collector: MiniFlowCollector<T>) {
        collector.emit(value)
    }
}

private class CountingCollector : MiniFlowCollector<Any?> {
    var count: Long = 0L
    override suspend fun emit(value: Any?) {
        count += 1L
    }
}

private object FlowCollectTestFragment : FunctionTableFragment {
    override val moduleName: String = "test_flow_collect"

    override fun classes(): List<ReflectedClass> = listOf(
        ReflectedClass(
            name = "flowtest.MiniFlow",
            memberNames = listOf("flowtest.MiniFlow.collect"),
        ),
        ReflectedClass(
            name = "flowtest.MiniMutableStateFlow",
            memberNames = listOf("flowtest.MiniMutableStateFlow.value", "flowtest.MiniMutableStateFlow.value="),
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
            returnTypeName = "flowtest.MiniFlow",
        ) {
            object : MiniFlow<Long> {
                override suspend fun collect(collector: MiniFlowCollector<Long>) {
                    collector.emit(1L)
                    collector.emit(2L)
                    collector.emit(3L)
                }
            }
        },
        ExposedCallable(
            name = "flowtest.makeStateFlow",
            arity = 1,
            paramTypes = listOf(TypeTag.OBJECT),
            returnType = TypeTag.OBJECT,
            paramNames = listOf("initial"),
            paramTypeNames = listOf("kotlin.Any"),
            returnTypeName = "flowtest.MiniMutableStateFlow|flowtest.MiniFlow",
        ) { args ->
            MiniMutableStateFlow(args[0])
        },
        ExposedCallable(
            name = "flowtest.makeKotlinCollector",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.OBJECT,
            returnTypeName = "flowtest.CountingCollector|flowtest.MiniFlowCollector",
        ) {
            CountingCollector()
        },
        // Generic class member property getter (star projection) and setter (Any? cast):
        ExposedCallable(
            name = "flowtest.MiniMutableStateFlow.value",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.OBJECT,
            kind = CallableKind.GETTER,
            receiverTypeName = "flowtest.MiniMutableStateFlow",
            returnTypeName = "kotlin.Any",
        ) { args ->
            (args[0] as MiniMutableStateFlow<*>).value
        },
        ExposedCallable(
            name = "flowtest.MiniMutableStateFlow.value=",
            arity = 1,
            paramTypes = listOf(TypeTag.OBJECT),
            returnType = TypeTag.UNIT,
            kind = CallableKind.SETTER,
            receiverTypeName = "flowtest.MiniMutableStateFlow",
            paramNames = listOf("value"),
            paramTypeNames = listOf("kotlin.Any"),
            returnTypeName = "kotlin.Unit",
        ) { args ->
            (args[0] as MiniMutableStateFlow<Any?>).value = args[1]
            Unit
        },
        // Suspend member function with suspend SAM fun interface parameter (SPEC U-5c):
        ExposedCallable(
            name = "flowtest.MiniFlow.collect",
            arity = 1,
            paramTypes = listOf(TypeTag.OBJECT),
            returnType = TypeTag.UNIT,
            kind = CallableKind.METHOD,
            receiverTypeName = "flowtest.MiniFlow",
            paramNames = listOf("collector"),
            paramTypeNames = listOf("flowtest.MiniFlowCollector|kotlin.coroutines.SuspendFunction1(kotlin.Any)->kotlin.Unit"),
            returnTypeName = "kotlin.Unit",
            isSuspend = true,
        ) { args ->
            val flow = args[0] as MiniFlow<Any?>
            val collector = (args[1]).let { v ->
                if (v is kotlin.Function2<*, *, *>) {
                    MiniFlowCollector<Any?> { p0 -> (v as (suspend (Any?) -> Unit)).invoke(p0) }
                } else {
                    v as MiniFlowCollector<Any?>
                }
            }
            PendingCall.start { flow.collect(collector) }
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
