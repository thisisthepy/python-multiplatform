package python.multiplatform.ffi.pythonx

import python.multiplatform.ffi.PyObject
import python.multiplatform.reflection.CallableKind
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.FunctionTableFragment
import python.multiplatform.reflection.TypeTag

/**
 * A table shaped the way the artefact walker shapes **properties** (issue #38), backed by Kotlin this
 * repository owns -- the [ComposeShapedFragment] argument for why a stand-in rather than the jars.
 *
 * | entry | shape it restates |
 * |---|---|
 * | `mutableStateOf` | a generic function with `T` read as `kotlin.Any?`: `value` is an `OBJECT` slot declared `kotlin.Any`, `policy` a defaulted one; the return carries its ancestry (`MutableState<:State`) |
 * | `MutableState.value` / `MutableState.value=` | a member `var`: a `GETTER` (receiver in `args[0]`, arity 0) and a `SETTER` (receiver, then the value) |
 * | `State.value` | the same property declared on the supertype, as the real `State` declares it |
 * | `propshape.Base.label` | a member `val` declared **only** on a supertype, so a subtype proxy reaches it by ancestry alone |
 * | `propshape.boost` | an extension *function* on that supertype, for the same lookup |
 * | `Icons.Default` + `icons.filled.Add` | an object's constant, and a top-level **extension property** on the type that constant is (`AddKt.getAdd(Icons$Filled)`) |
 * | `describeState` / `describeVector` / `describeBase` | read back what Kotlin actually holds, since a proxy alone cannot say -- for a scalar (#69), its Kotlin class |
 * | `propshape.stateHolding` | a state Kotlin filled with a boxed scalar of the named kind, for the read-back direction of #69 |
 *
 * [calls] records every Kotlin-side invocation, which is how a test tells "described without reading"
 * apart from "read".
 */
object PropertyShapedFragment : FunctionTableFragment {

    override val moduleName: String = "test_property_shaped"

    val calls: MutableList<String> = mutableListOf()

    const val STATE = "androidx.compose.runtime.State"
    const val MUTABLE_STATE = "androidx.compose.runtime.MutableState"
    private const val POLICY = "androidx.compose.runtime.SnapshotMutationPolicy"
    private const val ANY = "kotlin.Any"
    const val BASE = "propshape.Base"
    const val DERIVED = "propshape.Derived"
    const val FILLED = "androidx.compose.material.icons.Icons.Filled"
    private const val IMAGE_VECTOR = "androidx.compose.ui.graphics.vector.ImageVector"

    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "androidx.compose.runtime.mutableStateOf",
            arity = 2,
            paramTypes = listOf(TypeTag.OBJECT, TypeTag.OBJECT),
            returnType = TypeTag.OBJECT,
            paramNames = listOf("value", "policy"),
            paramTypeNames = listOf(ANY, POLICY),
            returnTypeName = "$MUTABLE_STATE<:$STATE",
            paramHasDefault = listOf(false, true),
        ) { args ->
            calls += "mutableStateOf"
            StubMutableState(args[0])
        },
        ExposedCallable(
            name = "$STATE.value",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.OBJECT,
            kind = CallableKind.GETTER,
            paramNames = emptyList(),
            paramTypeNames = emptyList(),
            returnTypeName = ANY,
            receiverTypeName = STATE,
        ) { args ->
            calls += "State.value"
            (args[0] as StubMutableState).value
        },
        ExposedCallable(
            name = "$MUTABLE_STATE.value",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.OBJECT,
            kind = CallableKind.GETTER,
            paramNames = emptyList(),
            paramTypeNames = emptyList(),
            returnTypeName = ANY,
            receiverTypeName = MUTABLE_STATE,
        ) { args ->
            calls += "MutableState.value"
            (args[0] as StubMutableState).value
        },
        ExposedCallable(
            name = "$MUTABLE_STATE.value=",
            arity = 1,
            paramTypes = listOf(TypeTag.OBJECT),
            returnType = TypeTag.UNIT,
            kind = CallableKind.SETTER,
            paramNames = listOf("value"),
            paramTypeNames = listOf(ANY),
            returnTypeName = "kotlin.Unit",
            receiverTypeName = MUTABLE_STATE,
            paramHasDefault = listOf(false),
        ) { args ->
            calls += "MutableState.value="
            (args[0] as StubMutableState).value = args[1]
            Unit
        },
        ExposedCallable(
            name = "androidx.compose.runtime.describeState",
            arity = 1,
            paramTypes = listOf(TypeTag.OBJECT),
            returnType = TypeTag.STRING,
            paramNames = listOf("state"),
            paramTypeNames = listOf(STATE),
            returnTypeName = "kotlin.String",
        ) { args ->
            when (val held = (args[0] as StubMutableState).value) {
                null -> "null"
                is PyObject -> "python"
                is StubBase -> "kotlin:" + held.label
                // Issue #69: a Python scalar written into the `Any?` slot is a Kotlin box, and its
                // Kotlin class is what this reports -- `Int` and `Long` are different answers.
                is Int -> "Int:$held"
                is Long -> "Long:$held"
                is Double -> "Double:$held"
                is Boolean -> "Boolean:$held"
                is String -> "String:$held"
                else -> "kotlin:other"
            }
        },
        ExposedCallable(
            name = "propshape.stateHolding",
            arity = 1,
            paramTypes = listOf(TypeTag.STRING),
            returnType = TypeTag.OBJECT,
            paramNames = listOf("kind"),
            paramTypeNames = listOf("kotlin.String"),
            returnTypeName = "$MUTABLE_STATE<:$STATE",
        ) { args ->
            // A state whose value Kotlin put there itself, so the read-back direction of #69 is
            // tested for every boxed type Kotlin can hold, not only those Python can write.
            StubMutableState(
                when (val kind = args[0] as String) {
                    "Int" -> 7
                    "Long" -> 1L shl 40
                    "Short" -> 300.toShort()
                    "Byte" -> (-5).toByte()
                    "Double" -> 2.5
                    "Float" -> 1.5f
                    "Boolean" -> true
                    "String" -> "text"
                    "Char" -> 'c'
                    else -> error("no Kotlin value of kind $kind")
                },
            )
        },
        ExposedCallable(
            name = "propshape.makeDerived",
            arity = 1,
            paramTypes = listOf(TypeTag.STRING),
            returnType = TypeTag.OBJECT,
            paramNames = listOf("label"),
            paramTypeNames = listOf("kotlin.String"),
            returnTypeName = "$DERIVED<:$BASE",
        ) { args -> StubBase(args[0] as String) },
        ExposedCallable(
            name = "$BASE.label",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.STRING,
            kind = CallableKind.GETTER,
            paramNames = emptyList(),
            paramTypeNames = emptyList(),
            returnTypeName = "kotlin.String",
            receiverTypeName = BASE,
        ) { args ->
            calls += "Base.label"
            (args[0] as StubBase).label
        },
        ExposedCallable(
            name = "propshape.boost",
            arity = 2,
            paramTypes = listOf(TypeTag.OBJECT, TypeTag.INT),
            returnType = TypeTag.STRING,
            paramNames = listOf("<receiver>", "times"),
            paramTypeNames = listOf(BASE, "kotlin.Int"),
            returnTypeName = "kotlin.String",
            isExtension = true,
            receiverTypeName = BASE,
            paramHasDefault = listOf(false, false),
        ) { args ->
            calls += "boost"
            (args[0] as StubBase).label.repeat((args[1] as Long).toInt())
        },
        ExposedCallable(
            name = "androidx.compose.material.icons.Icons.Default",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.OBJECT,
            kind = CallableKind.STATIC_GETTER,
            paramNames = emptyList(),
            paramTypeNames = emptyList(),
            returnTypeName = FILLED,
        ) { StubIconSet("Filled") },
        ExposedCallable(
            name = "androidx.compose.material.icons.filled.Add",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.OBJECT,
            kind = CallableKind.GETTER,
            paramNames = emptyList(),
            paramTypeNames = emptyList(),
            returnTypeName = IMAGE_VECTOR,
            receiverTypeName = FILLED,
        ) { args ->
            calls += "Add"
            StubImageVector((args[0] as StubIconSet).style + ".Add")
        },
        ExposedCallable(
            name = "androidx.compose.ui.graphics.vector.describeVector",
            arity = 1,
            paramTypes = listOf(TypeTag.OBJECT),
            returnType = TypeTag.STRING,
            paramNames = listOf("vector"),
            paramTypeNames = listOf(IMAGE_VECTOR),
            returnTypeName = "kotlin.String",
        ) { args -> (args[0] as StubImageVector).name },
    )
}

/** What `mutableStateOf` hands back here: one slot Kotlin and Python both read and write. */
class StubMutableState(var value: Any?)

/** `propshape.Derived`, whose only property is declared on `propshape.Base`. */
class StubBase(val label: String)

/** `Icons.Filled`: the object an icon extension property is read on. */
class StubIconSet(val style: String)

/** What an icon property returns. */
class StubImageVector(val name: String)
