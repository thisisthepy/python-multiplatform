package python.multiplatform.ffi.pythonx

import python.multiplatform.reflection.CallableKind
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.FunctionTableFragment
import python.multiplatform.reflection.TypeTag

/**
 * A table shaped exactly the way the artefact walker shapes Compose, backed by Kotlin this
 * repository owns.
 *
 * ### Why a stand-in rather than the real jars
 *
 * `WalkedArtifactComposeModifierTest` (in `ksp-fixtures/artifact`) already proves that the walker
 * produces these entries out of `foundation-layout-desktop-1.6.11.jar` and that Compose's own
 * `padding` runs when Python calls them. What it cannot do is run anywhere but desktop, because it
 * needs a JVM classpath. The adapter under test here is `commonMain` Python, so its tests belong in
 * `commonTest` where all five targets run them -- which rules out a jar.
 *
 * So this fragment restates the *shape* and nothing else: every field
 * `WalkedArtifactComposeModifierTest.aWalkedEntryCarriesItsDeclarationAndNotOnlyItsTags` asserts
 * about the real `padding__Dp` is asserted about this one too ([shapeMatchesTheWalkedEntries]), and
 * the Kotlin behind it is [StubModifier] rather than `androidx.compose.ui.Modifier`. The names are
 * the real ones on purpose: `pythonx`'s seeded package map says `pythonx.compose` means
 * `androidx.compose`, and a fixture under a made-up package would not exercise it.
 *
 * ### What each entry is here to say
 *
 * | entry | what it exists for |
 * |---|---|
 * | `padding__Dp` / `__Dp_Dp` / `__Dp_Dp_Dp_Dp` / `__PaddingValues` | overload dispatch on count, on keyword names, and on argument type |
 * | `padding__Dp_Dp` / `__Dp_Dp_Dp_Dp` again | omitting a defaulted argument, including one in the *middle* of the list |
 * | `fillMaxHeight` | the same, under a name with no overload set: omission with no dispatcher involved |
 * | `size__Dp` | the second link of a chain, so the return really is a receiver again |
 * | `fillMaxWidth` | a receiver-only extension, reached under its Kotlin name and no other |
 * | `zIndex` | a genuine `kotlin.Float` parameter beside `Dp` ones: same tag, different declared type |
 * | `toURLString` | an acronym-bearing name, which once tested a snake_case rule and now only has to resolve as itself |
 * | `paddingFromBaseline__TextUnit` | the value-class reject list: a packed wrapper must refuse a raw number |
 * | `emptyModifier` | where a chain starts. Compose has no bound declaration for this; see [EMPTY_MODIFIER] |
 * | `Arrangement.Start` / `.End` | `kind = STATIC_GETTER`: a value behind a name, read as an attribute and not called |
 */
object ComposeShapedFragment : FunctionTableFragment {

    override val moduleName: String = "test_pythonx_compose"

    /**
     * Which overload actually ran, in call order.
     *
     * The dispatcher's decision is invisible from Python -- `padding(16)` and `padding(1, 2, 3, 4)`
     * are the same call site -- so this is how a test says *which* declaration was reached rather
     * than merely that something was.
     */
    val calls: MutableList<String> = mutableListOf()

    /**
     * The one declaration here that the real walker does not produce.
     *
     * `Modifier` as an *expression* is `Modifier.Companion`, an object instance, and the walker
     * binds functions. So the empty modifier a chain starts from has no bound name in the real
     * table, and `pythonx` cannot conjure one -- which is why the class-object spelling
     * (`Modifier.padding(...)`) needs an empty-instance factory registered by name and refuses
     * with a message rather than guessing when none is. This is that name for the fixture.
     */
    const val EMPTY_MODIFIER: String = "androidx.compose.ui.emptyModifier"

    private const val MODIFIER = "androidx.compose.ui.Modifier"

    private const val DP = "androidx.compose.ui.unit.Dp"

    private const val PADDING_VALUES = "androidx.compose.foundation.layout.PaddingValues"

    private const val TEXT_UNIT = "androidx.compose.ui.unit.TextUnit"

    private const val ARRANGEMENT = "androidx.compose.foundation.layout.Arrangement"

    private const val HORIZONTAL = "$ARRANGEMENT.Horizontal"

    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = EMPTY_MODIFIER,
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.OBJECT,
            paramNames = emptyList(),
            paramTypeNames = emptyList(),
            returnTypeName = MODIFIER,
        ) { StubModifier.EMPTY },
        ExposedCallable(
            name = "androidx.compose.ui.describeModifier",
            arity = 1,
            paramTypes = listOf(TypeTag.OBJECT),
            returnType = TypeTag.STRING,
            paramNames = listOf("modifier"),
            paramTypeNames = listOf(MODIFIER),
            returnTypeName = "kotlin.String",
        ) { args -> (args[0] as StubModifier).describe() },
        // padding, all four overloads, with the receiver in slot 0 exactly as the walker emits it.
        extension(
            name = "androidx.compose.foundation.layout.padding__Dp",
            paramNames = listOf("all"),
            paramTypes = listOf(TypeTag.FLOAT),
            paramTypeNames = listOf(DP),
            paramHasDefault = listOf(false),
        ) { receiver, args ->
            calls += "padding__Dp"
            receiver.plus("padding(${dp(args[0])})")
        },
        // The two `padding` overloads that declare defaults. Their bodies are written the way the
        // walker writes a defaulted body (`ArtifactScanner.presenceBranchedCall`): a `null` in a
        // defaulted slot means the argument was **not written at the call site**, and the branch
        // taken is a different Kotlin call rather than the same call with a substituted value. The
        // label records which arguments were actually passed, so a test can tell "omitted" from
        // "passed something that happens to equal the default" -- which is the whole claim.
        extension(
            name = "androidx.compose.foundation.layout.padding__Dp_Dp",
            paramNames = listOf("horizontal", "vertical"),
            paramTypes = listOf(TypeTag.FLOAT, TypeTag.FLOAT),
            paramTypeNames = listOf(DP, DP),
            paramHasDefault = listOf(true, true),
        ) { receiver, args ->
            calls += "padding__Dp_Dp"
            refuseIfNothingWritten("androidx.compose.foundation.layout.padding", args)
            receiver.plus("padding(${passed("h" to args[0], "v" to args[1])})")
        },
        extension(
            name = "androidx.compose.foundation.layout.padding__Dp_Dp_Dp_Dp",
            paramNames = listOf("start", "top", "end", "bottom"),
            paramTypes = listOf(TypeTag.FLOAT, TypeTag.FLOAT, TypeTag.FLOAT, TypeTag.FLOAT),
            paramTypeNames = listOf(DP, DP, DP, DP),
            paramHasDefault = listOf(true, true, true, true),
        ) { receiver, args ->
            calls += "padding__Dp_Dp_Dp_Dp"
            refuseIfNothingWritten("androidx.compose.foundation.layout.padding", args)
            receiver.plus(
                "padding(${passed("s" to args[0], "t" to args[1], "e" to args[2], "b" to args[3])})",
            )
        },
        // A defaulted declaration under a name that carries **no** overload set, which is the case
        // where omission has to work without any dispatcher being involved: `Modifier.fillMaxHeight`
        // is one declaration, so `fillMaxHeight()` reaches a `_Binding` and not an `_Overloads`.
        // The real `foundation-layout` declares it exactly this way (`fraction: Float = 1f`), and
        // the default being 1 rather than 0 is what makes omitting it observable at all.
        extension(
            name = "androidx.compose.foundation.layout.fillMaxHeight",
            paramNames = listOf("fraction"),
            paramTypes = listOf(TypeTag.FLOAT),
            paramTypeNames = listOf("kotlin.Float"),
            paramHasDefault = listOf(true),
        ) { receiver, args ->
            calls += "fillMaxHeight"
            receiver.plus("fillMaxHeight(${passed("f" to args[0])})")
        },
        extension(
            name = "androidx.compose.foundation.layout.padding__PaddingValues",
            paramNames = listOf("paddingValues"),
            paramTypes = listOf(TypeTag.OBJECT),
            paramTypeNames = listOf(PADDING_VALUES),
            paramHasDefault = listOf(false),
        ) { receiver, args ->
            calls += "padding__PaddingValues"
            receiver.plus("padding(${(args[0] as StubPaddingValues).label})")
        },
        ExposedCallable(
            name = "androidx.compose.foundation.layout.paddingValuesOf",
            arity = 1,
            paramTypes = listOf(TypeTag.FLOAT),
            returnType = TypeTag.OBJECT,
            paramNames = listOf("all"),
            paramTypeNames = listOf(DP),
            returnTypeName = PADDING_VALUES,
        ) { args -> StubPaddingValues("pv(${dp(args[0])})") },
        extension(
            name = "androidx.compose.foundation.layout.size__Dp",
            paramNames = listOf("size"),
            paramTypes = listOf(TypeTag.FLOAT),
            paramTypeNames = listOf(DP),
            paramHasDefault = listOf(false),
        ) { receiver, args ->
            calls += "size__Dp"
            receiver.plus("size(${dp(args[0])})")
        },
        extension(
            name = "androidx.compose.foundation.layout.fillMaxWidth",
            paramNames = emptyList(),
            paramTypes = emptyList(),
            paramTypeNames = emptyList(),
            paramHasDefault = emptyList(),
        ) { receiver, _ ->
            calls += "fillMaxWidth"
            receiver.plus("fillMaxWidth")
        },
        extension(
            name = "androidx.compose.ui.draw.zIndex",
            paramNames = listOf("zIndex"),
            paramTypes = listOf(TypeTag.FLOAT),
            // A genuine `kotlin.Float`, not a value class: the same TypeTag as `Dp` and a
            // different declared type, which is the whole reason `paramTypeNames` exists.
            paramTypeNames = listOf("kotlin.Float"),
            paramHasDefault = listOf(false),
        ) { receiver, args ->
            calls += "zIndex"
            receiver.plus("zIndex(${dp(args[0])})")
        },
        // A packed value class. `docs/design/kotlin-extensions-in-python.md` §2.4: raw 16 decodes as
        // `TextUnit.Unspecified`, silently. Nothing binds such a parameter today (TextUnit's
        // constructor is INTERNAL, so `resolveKotlinType` declines it), so this entry is
        // hypothetical -- it exists so the Python side's refusal has something to refuse.
        extension(
            name = "androidx.compose.foundation.layout.paddingFromBaseline__TextUnit",
            paramNames = listOf("top"),
            paramTypes = listOf(TypeTag.FLOAT),
            paramTypeNames = listOf(TEXT_UNIT),
            paramHasDefault = listOf(false),
        ) { receiver, args ->
            calls += "paddingFromBaseline__TextUnit"
            receiver.plus("paddingFromBaseline(${dp(args[0])})")
        },
        // The name the snake -> camel rule cannot invert: `toURLString` forward-converts to
        // `to_url_string`, and the reverse rule turns that back into `toUrlString`.
        ExposedCallable(
            name = "androidx.compose.ui.util.toURLString",
            arity = 1,
            paramTypes = listOf(TypeTag.STRING),
            returnType = TypeTag.STRING,
            paramNames = listOf("raw"),
            paramTypeNames = listOf("kotlin.String"),
            returnTypeName = "kotlin.String",
        ) { args -> "url:" + (args[0] as String) },
        // `kind = STATIC_GETTER`: what `ArtifactScanner.objectConstantCandidates` binds for
        // `Arrangement.Start` -- a value behind a name, not a function, and the walker never sees a
        // `val` and a `var` differently, so nothing here promises this is read once. `calls` records
        // one entry per Kotlin-side invocation, which is what a test needs to tell "read fresh every
        // time" apart from "resolved once and cached in the module dict", the fate every other
        // adapted name gets.
        ExposedCallable(
            name = "$ARRANGEMENT.Start",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.OBJECT,
            kind = CallableKind.STATIC_GETTER,
            paramNames = emptyList(),
            paramTypeNames = emptyList(),
            returnTypeName = HORIZONTAL,
        ) {
            calls += "Arrangement.Start"
            StubHorizontal("Start")
        },
        ExposedCallable(
            name = "$ARRANGEMENT.End",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.OBJECT,
            kind = CallableKind.STATIC_GETTER,
            paramNames = emptyList(),
            paramTypeNames = emptyList(),
            returnTypeName = HORIZONTAL,
        ) {
            calls += "Arrangement.End"
            StubHorizontal("End")
        },
        // Unwraps the handle a STATIC_GETTER read hands back, the same way `describeModifier` does
        // for a chain -- there is no other way from Python to tell `Arrangement.Start` from
        // `Arrangement.End` once each is a bare proxy instance.
        ExposedCallable(
            name = "androidx.compose.foundation.layout.describeHorizontal",
            arity = 1,
            paramTypes = listOf(TypeTag.OBJECT),
            returnType = TypeTag.STRING,
            paramNames = listOf("horizontal"),
            paramTypeNames = listOf(HORIZONTAL),
            returnTypeName = "kotlin.String",
        ) { args -> (args[0] as StubHorizontal).label },
    )

    private fun dp(value: Any?): String = (value as Double).toString()

    /**
     * The arguments a call **wrote**, in order, skipping the ones it left out.
     *
     * The stand-in for `ArtifactScanner.presenceBranchedCall`'s `when`: there, a `null` slot selects
     * a Kotlin call expression that does not mention the parameter at all, and the compiler supplies
     * the declared default. Here there is no declaration to take a default from, so what the fixture
     * records instead is *which arguments were written* -- `padding(h=8.0)` rather than
     * `padding(h=8.0, v=0.0)`. A test asserting on that cannot be satisfied by a body that passed
     * something equal to the default, which is the failure mode this whole change is about.
     */
    private fun passed(vararg arguments: Pair<String, Any?>): String = arguments
        .filter { it.second != null }
        .joinToString(", ") { "${it.first}=${dp(it.second)}" }

    /**
     * The branch a walked body has where writing *nothing* would not have compiled.
     *
     * `ArtifactScanner.applyDefaultOmission` refuses to generate a call for an omission set a
     * sibling overload would also accept, because Kotlin reports it as `Overload resolution
     * ambiguity` and a generated call has no argument left to disambiguate with. The refusal has to
     * be reachable rather than absent: `paramHasDefault` is per slot and cannot say "these two but
     * not both at once", so Python will happily send both sentinels and the body answers for it.
     *
     * Measured on the real jars: 14 of 401 generated branches, all of them the write-nothing one --
     * `padding`, `PaddingValues`, `WindowInsets`, `paddingFrom`, `paddingFromBaseline`,
     * `decodeToString`, `encodeToByteArray`, `toHexString`. `fillMaxHeight` below has no sibling and
     * therefore no such branch, which is why it is the fixture's witness for omitting everything.
     */
    private fun refuseIfNothingWritten(name: String, args: List<Any?>) {
        if (args.all { it == null }) {
            throw IllegalArgumentException(
                "$name: leaving out every argument is ambiguous with another overload of the same " +
                    "name; write at least one of them",
            )
        }
    }

    /**
     * One extension entry, with slot 0 spelled the way the walker spells it: counted in `arity`,
     * named `<receiver>`, typed with the receiver's Kotlin type name, and never defaulted.
     */
    private fun extension(
        name: String,
        paramNames: List<String>,
        paramTypes: List<TypeTag>,
        paramTypeNames: List<String>,
        paramHasDefault: List<Boolean>,
        body: (StubModifier, List<Any?>) -> StubModifier,
    ): ExposedCallable = ExposedCallable(
        name = name,
        arity = paramTypes.size + 1,
        paramTypes = listOf(TypeTag.OBJECT) + paramTypes,
        returnType = TypeTag.OBJECT,
        paramNames = listOf("<receiver>") + paramNames,
        paramTypeNames = listOf(MODIFIER) + paramTypeNames,
        returnTypeName = MODIFIER,
        isExtension = true,
        receiverTypeName = MODIFIER,
        paramHasDefault = listOf(false) + paramHasDefault,
    ) { args -> body(args[0] as StubModifier, args.drop(1)) }
}

/** What a `Modifier` is for this fixture: an ordered list of what was applied to it. */
class StubModifier private constructor(private val elements: List<String>) {

    fun plus(element: String): StubModifier = StubModifier(elements + element)

    fun describe(): String = if (elements.isEmpty()) "<empty>" else elements.joinToString(" -> ")

    companion object {
        val EMPTY: StubModifier = StubModifier(emptyList())
    }
}

/** An ordinary object parameter, so that two arity-2 overloads can be told apart by type alone. */
class StubPaddingValues(val label: String)

/** What `ComposeShapedFragment`'s `Arrangement.Start`/`.End` hand back: an opaque handle a test can
 * only tell apart by round-tripping through `describeHorizontal`, the same way a real `Horizontal`
 * would be. */
class StubHorizontal(val label: String)
