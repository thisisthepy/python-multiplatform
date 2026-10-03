package python.multiplatform.ffi.pythonx

import python.multiplatform.reflection.CallableKind
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.FunctionTableFragment
import python.multiplatform.reflection.TypeTag

/**
 * A table shaped the way `ArtifactScanner` shapes a `@Composable`, backed by Kotlin this repository
 * owns.
 *
 * ### Why a stand-in
 *
 * Same judgement as [ComposeShapedFragment]'s, one step further along. The adapter under test is
 * `commonMain` Python, so its tests belong in `commonTest` where all five targets run them -- and a
 * real composable needs a JVM classpath, a Compose runtime, a live `Composer` and a generated thunk
 * class, none of which exist on Kotlin/Native. What is under test here is the *arithmetic*: which
 * `$default` bits a Python call sets, what goes in the slots it left out, and what `$changed` is.
 *
 * The real end of it -- `androidx.compose.material3.Text` drawing "hi" -- is
 * `:ksp-fixtures:compose`'s `ComposableRenderTest`, which is desktop-only for exactly those reasons.
 *
 * ### The shape, and why every field of it matters
 *
 * `Text(text, modifier = ..., color = ..., fontSize = ...)` reduced to four declared parameters and
 * the three synthetic groups a composable's JVM signature really carries
 * (`docs/archive/pythonx-adapter-design.md` §5.2):
 *
 * | slot | name | tag | why |
 * |---|---|---|---|
 * | 0 | `text` | `STRING` | required: the one argument `Text('hi')` writes |
 * | 1 | `modifier` | `OBJECT` | defaulted, reference: its absent value must be `null` |
 * | 2 | `color` | `INT` | defaulted, **value class erased to a primitive**: its absent value must be `0`, not `None`, or the thunk's `Number.intValue()` would raise |
 * | 3 | `fontSize` | `INT` | a second defaulted slot, so a mask with two bits set is distinguishable from one with either |
 * | 4 | `$composer` | `OBJECT` | the value Python cannot invent |
 * | 5 | `$changed` | `INT` | must arrive as `0` -- the conservative claim |
 * | 6 | `$default` | `INT` | the mask, which is the whole point |
 */
object ComposableShapedFragment : FunctionTableFragment {

    override val moduleName: String = "test_pythonx_composable"

    /** Every call, as `(text, modifier, color, fontSize, composer, changed, default)`. Recorded
     * rather than returned because "the default was used" and "0 was passed" are the same number at
     * the boundary -- the same trap [ComposeShapedFragment.calls] exists for. */
    val calls: MutableList<List<Any?>> = mutableListOf()

    /** Stands in for a live `androidx.compose.runtime.Composer`: an instance Python can only have
     * got from Kotlin, so a slot holding it proves the handle round-tripped. */
    object StubComposer

    private const val COMPOSER = "androidx.compose.runtime.Composer"

    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "androidx.compose.material3.Text",
            arity = 7,
            paramTypes = listOf(
                TypeTag.STRING, TypeTag.OBJECT, TypeTag.INT, TypeTag.INT,
                TypeTag.OBJECT, TypeTag.INT, TypeTag.INT,
            ),
            returnType = TypeTag.UNIT,
            kind = CallableKind.FUNCTION,
            paramNames = listOf("text", "modifier", "color", "fontSize", "\$composer", "\$changed", "\$default"),
            paramTypeNames = listOf(
                "kotlin.String", "androidx.compose.ui.Modifier", "kotlin.Int", "kotlin.Int",
                COMPOSER, "kotlin.Int", "kotlin.Int",
            ),
            returnTypeName = null,
            paramHasDefault = listOf(false, true, true, true, false, false, false),
            callable = { args ->
                calls += args.toList()
                Unit
            },
        ),
        // Where the composer comes from in a test: a plain declaration returning an object handle,
        // so the Python side gets one the only way it ever legitimately can -- out of Kotlin.
        ExposedCallable(
            name = "androidx.compose.runtime.stubComposer",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.OBJECT,
            kind = CallableKind.FUNCTION,
            returnTypeName = COMPOSER,
            callable = { StubComposer },
        ),
        // A **container**: the shape `androidx.compose.foundation.layout.Column` really has, reduced
        // the same way `Text` above is. Slot 1 is the one this fixture exists for -- a parameter the
        // walker reports as `kotlin.Function3@Composable`, which is a `@Composable ColumnScope.() ->
        // Unit` after the Compose plugin lowered it (measured: `Column`'s JVM descriptor takes
        // `Lkotlin/jvm/functions/Function3;` where metadata declares `kotlin.Function1`).
        ExposedCallable(
            name = "androidx.compose.foundation.layout.Column",
            arity = 5,
            paramTypes = listOf(TypeTag.OBJECT, TypeTag.OBJECT, TypeTag.OBJECT, TypeTag.INT, TypeTag.INT),
            returnType = TypeTag.UNIT,
            kind = CallableKind.FUNCTION,
            paramNames = listOf("modifier", "content", "\$composer", "\$changed", "\$default"),
            paramTypeNames = listOf(
                "androidx.compose.ui.Modifier", COMPOSABLE_FUNCTION3, COMPOSER, "kotlin.Int", "kotlin.Int",
            ),
            returnTypeName = null,
            paramHasDefault = listOf(true, false, false, false, false),
            callable = { args ->
                calls += args.toList()
                // Exactly what Compose does with a lowered `content`: an ordinary interface call
                // passing the scope, the composer and a `$changed` of its own. If the object in
                // slot 1 is not a `Function3` this is where it fails, which is the point.
                @Suppress("UNCHECKED_CAST")
                val content = args[1] as Function3<Any?, Any?, Any?, Unit>
                contents += content
                contentInvocations++
                // Deliberately **not** `args[2]`. Compose hands a content lambda the composer that
                // is current where the content runs, and a wrapper that ignored its own argument and
                // reused whatever `pythonx.push_composer` last saw would pass every test that used
                // one composer. A distinct object makes that shortcut fail.
                content(StubScope, InnerComposer, 0L)
                Unit
            },
        ),
        // A **plain** function-typed parameter on a composable: `Button`'s `onClick`, which is
        // `kotlin.Function0` in metadata *and* `Lkotlin/jvm/functions/Function0;` in the descriptor.
        // Nothing was lowered, so nothing pushes a composer and the callable takes no arguments --
        // which is the distinction `_function_slot` has to make and this entry is here to check.
        ExposedCallable(
            name = "androidx.compose.material3.Button",
            arity = 4,
            paramTypes = listOf(TypeTag.OBJECT, TypeTag.OBJECT, TypeTag.INT, TypeTag.INT),
            returnType = TypeTag.UNIT,
            kind = CallableKind.FUNCTION,
            paramNames = listOf("onClick", "\$composer", "\$changed", "\$default"),
            paramTypeNames = listOf(PLAIN_FUNCTION0, COMPOSER, "kotlin.Int", "kotlin.Int"),
            returnTypeName = null,
            paramHasDefault = listOf(false, false, false, false),
            callable = { args ->
                calls += args.toList()
                @Suppress("UNCHECKED_CAST")
                clicks += args[0] as Function0<Unit>
                Unit
            },
        ),
        // A **value callback**: `Slider`'s `onValueChange`, `(Float) -> Unit`. Plain like `onClick`
        // -- nothing is lowered, so no composer is threaded -- and unlike it in the one way that was
        // refused outright until now: the lambda is invoked *with something*, which has to reach
        // Python as a number rather than as a handle to a boxed one.
        //
        // Fired here rather than stored, with a value no default could produce, because "the
        // callback was received" and "the callback was told 0.25" are different claims and only the
        // second one is what makes an interactive control work.
        ExposedCallable(
            name = "androidx.compose.material3.Slider",
            arity = 5,
            paramTypes = listOf(TypeTag.FLOAT, TypeTag.OBJECT, TypeTag.OBJECT, TypeTag.INT, TypeTag.INT),
            returnType = TypeTag.UNIT,
            kind = CallableKind.FUNCTION,
            paramNames = listOf("value", "onValueChange", "\$composer", "\$changed", "\$default"),
            paramTypeNames = listOf(
                "kotlin.Float", FLOAT_CALLBACK, COMPOSER, "kotlin.Int", "kotlin.Int",
            ),
            returnTypeName = null,
            paramHasDefault = listOf(true, false, false, false, false),
            callable = { args ->
                calls += args.toList()
                @Suppress("UNCHECKED_CAST")
                val onValueChange = args[1] as Function1<Any?, Unit>
                valueChanges += onValueChange
                onValueChange(DRAGGED_TO)
                Unit
            },
        ),
        // A **predicate**: `(SheetValue) -> Boolean`, which `rememberModalBottomSheetState` really
        // declares (`confirmValueChange`). Bound as a slot and refused as a callable -- 18 of the 560
        // function-typed slots three Compose jars declare return something other than `Unit`
        // (`ComposableBindingTest.functionTypedSlotsCarryTheirArgumentAndReturnTypes`), and the
        // refusal has to be at the call rather than as a `ClassCastException` inside Compose.
        ExposedCallable(
            name = "androidx.compose.material3.rememberSheetState",
            arity = 4,
            paramTypes = listOf(TypeTag.OBJECT, TypeTag.OBJECT, TypeTag.INT, TypeTag.INT),
            returnType = TypeTag.UNIT,
            kind = CallableKind.FUNCTION,
            paramNames = listOf("confirmValueChange", "\$composer", "\$changed", "\$default"),
            paramTypeNames = listOf(PREDICATE_CALLBACK, COMPOSER, "kotlin.Int", "kotlin.Int"),
            returnTypeName = null,
            paramHasDefault = listOf(false, false, false, false),
            callable = { args ->
                calls += args.toList()
                Unit
            },
        ),
        // An **extension** composable, shaped the way `androidx.compose.material3.NavigationBarItem`
        // really is: `RowScope.NavigationBarItem(selected, onClick, modifier = …, enabled = …)`.
        //
        // The receiver is slot 0 of the binding and is **not** numbered by `$default` -- bit 0 is
        // `selected`, so `modifier` (slot 3, value parameter 2) is bit 2 and not bit 3. Measured out
        // of the real jar's bytecode by
        // `ComposableBindingTest.theDefaultBitOfAnExtensionComposableNumbersValueParametersAndNotSlots`;
        // this entry is where that measurement is checked on the four targets that have no jar.
        ExposedCallable(
            name = "androidx.compose.material3.NavigationBarItem",
            arity = 8,
            paramTypes = listOf(
                TypeTag.OBJECT, TypeTag.BOOLEAN, TypeTag.OBJECT, TypeTag.OBJECT, TypeTag.BOOLEAN,
                TypeTag.OBJECT, TypeTag.INT, TypeTag.INT,
            ),
            returnType = TypeTag.UNIT,
            kind = CallableKind.FUNCTION,
            isExtension = true,
            receiverTypeName = ROW_SCOPE,
            paramNames = listOf(
                "<receiver>", "selected", "onClick", "modifier", "enabled",
                "\$composer", "\$changed", "\$default",
            ),
            paramTypeNames = listOf(
                ROW_SCOPE, "kotlin.Boolean", PLAIN_FUNCTION0, "androidx.compose.ui.Modifier",
                "kotlin.Boolean", COMPOSER, "kotlin.Int", "kotlin.Int",
            ),
            returnTypeName = null,
            paramHasDefault = listOf(false, false, false, true, true, false, false, false),
            callable = { args ->
                calls += args.toList()
                Unit
            },
        ),
        // Where a `RowScope` comes from in a test, the same way [StubComposer] arrives: out of
        // Kotlin, because there is no other legitimate way for Python to hold one. A real one comes
        // from a real `Row`'s forwarded receiver, which is `ComposableRenderTest`'s to show.
        ExposedCallable(
            name = "androidx.compose.foundation.layout.stubRowScope",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.OBJECT,
            kind = CallableKind.FUNCTION,
            returnTypeName = ROW_SCOPE,
            callable = { StubRowScope },
        ),
    )

    /** Stands in for a live `RowScope`. */
    object StubRowScope

    /** The scope `NavigationBarItem` is declared on, and the key its binding is indexed under. */
    const val ROW_SCOPE: String = "androidx.compose.foundation.layout.RowScope"

    /** `Button.onClick`: `() -> Unit`, plain, nothing forwarded. */
    const val PLAIN_FUNCTION0: String = "kotlin.Function0()->kotlin.Unit"

    /** `Slider.onValueChange`: `(Float) -> Unit`, plain, one value forwarded. */
    const val FLOAT_CALLBACK: String = "kotlin.Function1(kotlin.Float)->kotlin.Unit"

    /** A slot whose lambda has to give something back, which is what cannot be written in Python. */
    const val PREDICATE_CALLBACK: String = "kotlin.Function1(kotlin.Boolean)->kotlin.Boolean"

    /** What [entries]'s `Slider` tells its `onValueChange`. Not a value any default produces, and
     * not one a float/double confusion would round to the same thing. */
    const val DRAGGED_TO: Float = 0.25f

    /** Every `onValueChange` Kotlin was handed, so a test can fire one after its scope closed. */
    val valueChanges: MutableList<Function1<Any?, Unit>> = mutableListOf()

    /** Every `content` Kotlin was handed, so a test can invoke **one** of them many times -- which is
     * the shape a recomposing composition really has, and the only way to separate what a crossing
     * costs from what an invocation costs. */
    val contents: MutableList<Function3<Any?, Any?, Any?, Unit>> = mutableListOf()

    /** The Kotlin type a `Column`'s content is scoped to, which is also the key `_BY_RECEIVER` hangs
     * `ColumnScope`'s extensions off -- so the *name* is what decides whether `Modifier.weight` is
     * reachable from the value the content is handed, not merely whether a value arrives. */
    const val COLUMN_SCOPE: String = "androidx.compose.foundation.layout.ColumnScope"

    /** What the walker emits for a slot whose declared `kotlin.Function1<ColumnScope, Unit>` was
     * lowered to a JVM `Function3` -- see `ArtifactScanner.functionSlotTypeName`. Measured against
     * the real `androidx.compose.foundation.layout.Column` by
     * `ComposableBindingTest.theContentSlotIsMarkedComposableAndAnOnClickIsNot`. */
    const val COMPOSABLE_FUNCTION3: String =
        "kotlin.Function3@Composable($COLUMN_SCOPE)->kotlin.Unit"

    /** Stands in for a `ColumnScope` instance: the receiver Compose threads into a lowered content
     * lambda as its first argument. Forwarded to Python as a proxy over a `HandleTable` handle, and
     * dropped by the thunk when the Python callable declares no parameter for it. */
    object StubScope

    /** The composer a container hands its content, distinct from [StubComposer] on purpose. */
    object InnerComposer

    /** How many times [entries]'s `Column` has invoked its `content`. Distinct from [calls]: a
     * container that received a content it never called would otherwise look identical. */
    var contentInvocations: Int = 0

    /** Every `onClick` Kotlin was handed, **not** invoked -- so that a test can call one after the
     * composition that produced it has gone, which is the lifetime question. */
    val clicks: MutableList<Function0<Unit>> = mutableListOf()
}
