package python.multiplatform.ffi.pythonx

import python.multiplatform.reflection.CallableKind
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.FunctionTableFragment
import python.multiplatform.reflection.TypeTag

/**
 * A table shaped the way the artefact walker shapes **member functions** (issue #215), backed by Kotlin
 * this repository owns -- the [ComposeShapedFragment] argument for why a stand-in rather than the jars.
 *
 * | entry | shape it restates |
 * |---|---|
 * | `methshape.newCounter` | a factory returning a handle, with its ancestry (`Counter<:Base`) |
 * | `Counter.add__Int` / `Counter.add__Int_String` | an overloaded member: `METHOD`, receiver in `args[0]`, uncounted in `arity` |
 * | `Counter.reset` and the extension `methshape.reset` | a member and an extension of one name on one type: Kotlin calls the member |
 * | `Counter.stopNow` | a member whose Pythonic name (`stop_now`) differs |
 * | `Base.label` | a member declared only on a supertype |
 * | `Counter.count` | a member property, so the count can be read back |
 *
 * [calls] records every Kotlin-side invocation.
 */
object MethodShapedFragment : FunctionTableFragment {

    override val moduleName: String = "test_method_shaped"

    val calls: MutableList<String> = mutableListOf()

    const val COUNTER = "methshape.Counter"
    const val BASE = "methshape.Base"

    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "methshape.newCounter",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.OBJECT,
            paramNames = emptyList(),
            paramTypeNames = emptyList(),
            returnTypeName = "$COUNTER<:$BASE",
        ) { StubCounter() },
        ExposedCallable(
            name = "$COUNTER.add__Int",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.INT,
            kind = CallableKind.METHOD,
            paramNames = listOf("step"),
            paramTypeNames = listOf("kotlin.Int"),
            returnTypeName = "kotlin.Int",
            receiverTypeName = COUNTER,
            paramHasDefault = listOf(false),
        ) { args ->
            calls += "Counter.add(Int)"
            val counter = args[0] as StubCounter
            counter.count += (args[1] as Long).toInt()
            counter.count.toLong()
        },
        ExposedCallable(
            name = "$COUNTER.add__Int_String",
            arity = 2,
            paramTypes = listOf(TypeTag.INT, TypeTag.STRING),
            returnType = TypeTag.STRING,
            kind = CallableKind.METHOD,
            paramNames = listOf("step", "label"),
            paramTypeNames = listOf("kotlin.Int", "kotlin.String"),
            returnTypeName = "kotlin.String",
            receiverTypeName = COUNTER,
            paramHasDefault = listOf(false, false),
        ) { args ->
            calls += "Counter.add(Int, String)"
            val counter = args[0] as StubCounter
            counter.count += (args[1] as Long).toInt()
            (args[2] as String) + counter.count
        },
        ExposedCallable(
            name = "$COUNTER.reset",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.UNIT,
            kind = CallableKind.METHOD,
            paramNames = emptyList(),
            paramTypeNames = emptyList(),
            returnTypeName = "kotlin.Unit",
            receiverTypeName = COUNTER,
        ) { args ->
            calls += "Counter.reset"
            (args[0] as StubCounter).count = 0
            Unit
        },
        ExposedCallable(
            name = "methshape.reset",
            arity = 1,
            paramTypes = listOf(TypeTag.OBJECT),
            returnType = TypeTag.UNIT,
            paramNames = listOf("<receiver>"),
            paramTypeNames = listOf(COUNTER),
            returnTypeName = "kotlin.Unit",
            isExtension = true,
            receiverTypeName = COUNTER,
            paramHasDefault = listOf(false),
        ) { _ ->
            calls += "extension reset"
            Unit
        },
        ExposedCallable(
            name = "$COUNTER.stopNow",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.STRING,
            kind = CallableKind.METHOD,
            paramNames = emptyList(),
            paramTypeNames = emptyList(),
            returnTypeName = "kotlin.String",
            receiverTypeName = COUNTER,
        ) { _ ->
            calls += "Counter.stopNow"
            "stopped"
        },
        ExposedCallable(
            name = "$BASE.label",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.STRING,
            kind = CallableKind.METHOD,
            paramNames = emptyList(),
            paramTypeNames = emptyList(),
            returnTypeName = "kotlin.String",
            receiverTypeName = BASE,
        ) { _ ->
            calls += "Base.label"
            "base"
        },
        ExposedCallable(
            name = "$COUNTER.count",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.INT,
            kind = CallableKind.GETTER,
            paramNames = emptyList(),
            paramTypeNames = emptyList(),
            returnTypeName = "kotlin.Int",
            receiverTypeName = COUNTER,
        ) { args -> (args[0] as StubCounter).count.toLong() },
    )
}

/** `methshape.Counter`, which is a `methshape.Base`. */
class StubCounter(var count: Int = 0)
