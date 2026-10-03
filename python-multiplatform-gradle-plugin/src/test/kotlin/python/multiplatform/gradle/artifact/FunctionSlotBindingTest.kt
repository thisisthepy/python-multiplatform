package python.multiplatform.gradle.artifact

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The layer `a179b747` left closed: a function-typed parameter of a declaration that is **not** a
 * `@Composable`.
 *
 * ### What was in the way
 *
 * That commit taught the slot *name* to carry a whole signature, and taught `pythonx` to build a
 * `FunctionN` from a Python callable out of it. Both of those are shared by every producer. What was
 * not shared is how the two producers reach a parameter's type at all:
 *
 * | | how the slot's type is turned into a call |
 * |---|---|
 * | `@Composable` | [ComposableThunks] emits bytecode; a `CHECKCAST` to `kotlin/jvm/functions/FunctionN` needs no Kotlin name |
 * | everything else | generated **Kotlin source** casts `args[i]` to the declared type, so the type has to be *spellable* |
 *
 * `resolveKotlinType` -> `objectBoundaryTypeOrNull` answers "not spellable" for `kotlin.Function0`,
 * and it is right to: it looks the classifier up as a public class file, and Kotlin's built-in
 * function types have none anywhere -- they live in `.kotlin_builtins` metadata. So the parameter
 * was declined one layer before the slot grammar was ever consulted, and
 * `ArtifactScannerTest.composeModifierExtensionsSurviveBothGates` counted the cost: **44 of the 46
 * declined public top-level `Modifier` extensions declined for exactly that**, `clickable` and
 * `combinedClickable` among them.
 *
 * ### What opens it
 *
 * [ArtifactScanner.candidateFromFunction] now consults the same slot grammar
 * [ArtifactScanner.functionSlotTypeName] the composable path uses, and -- when it can also *write*
 * the Kotlin type -- supplies its own `OBJECT` boundary type rather than asking
 * `resolveKotlinType` for one. `objectBoundaryTypeOrNull` is untouched, so nothing else that has no
 * class file became bindable; the walker opted this one case in at the point it knows the JVM
 * descriptor, which is the fact the grammar needs and the type resolver never had.
 *
 * ### What still declines, and why it is not an omission
 *
 * | shape | example | why |
 * |---|---|---|
 * | `suspend` lambda | `Modifier.pointerInput(block:)` | its compiled type takes a `Continuation` and answers `COROUTINE_SUSPENDED`; a Python callable cannot be one, and Kotlin refuses to assign a `Function2` to a `suspend` function type at all |
 * | `@Composable` lambda inside a non-composable | `Modifier.composed(factory:)` | the generated fragment is compiled **without** the Compose plugin, so `@Composable () -> Unit` there is a `Function0` while the callee wants the lowered `Function3` |
 * | a type argument with no name | `Modifier.swipeable`'s `thresholds` | a type *parameter* has no spelling, which is the same reason the composable path returns `null` for it |
 *
 * A slot returning something other than `kotlin.Unit` is **not** in that table: it binds, carries its
 * return type in the name, and `pythonx._coerce` refuses to fill it with a message. That is
 * `a179b747`'s judgement and it is unchanged here -- and it is the same judgement for a
 * non-composable, for the same reason: `TypeTag` carries one `INT` for `Byte` through `Long`, so
 * neither side can say which boxed type the callee will cast the erased answer to. What differs is
 * only that the declaration around it is reachable, so a caller who wanted `onKeyEvent`'s *other*
 * arguments gets a refusal naming the slot instead of an `AttributeError` naming nothing.
 */
class FunctionSlotBindingTest {

    private val caches: File? = listOf(
        File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1"),
        File("/Volumes/macMini/caches/.gradle/caches/modules-2/files-2.1"),
    ).firstOrNull { it.isDirectory }

    private fun jarUnder(group: String, artifact: String): File? = caches?.resolve(group)?.resolve(artifact)
        ?.walkTopDown()?.firstOrNull { it.isFile && it.name.endsWith(".jar") && "sources" !in it.name }

    private val kotlinStdlibJar: File
        get() = File(Class.forName("kotlin.text.Regex").protectionDomain.codeSource.location.toURI())

    private fun composeClasspath(): List<File> = listOfNotNull(
        jarUnder("org.jetbrains.compose.material3", "material3-desktop"),
        jarUnder("org.jetbrains.compose.material", "material-desktop"),
        jarUnder("org.jetbrains.compose.foundation", "foundation-desktop"),
        jarUnder("org.jetbrains.compose.foundation", "foundation-layout-desktop"),
        jarUnder("org.jetbrains.compose.ui", "ui-desktop"),
        jarUnder("org.jetbrains.compose.ui", "ui-unit-desktop"),
        jarUnder("org.jetbrains.compose.ui", "ui-text-desktop"),
        jarUnder("org.jetbrains.compose.ui", "ui-graphics-desktop"),
        jarUnder("org.jetbrains.compose.ui", "ui-geometry-desktop"),
        jarUnder("org.jetbrains.compose.ui", "ui-util-desktop"),
        jarUnder("org.jetbrains.compose.runtime", "runtime-desktop"),
        jarUnder("org.jetbrains.compose.runtime", "runtime-saveable-desktop"),
        jarUnder("org.jetbrains.compose.animation", "animation-core-desktop"),
        jarUnder("org.jetbrains.compose.collection-internal", "collection-desktop"),
        jarUnder("org.jetbrains.compose.annotation-internal", "annotation-desktop"),
        kotlinStdlibJar,
    )

    /**
     * The acceptance criterion's declaration, out of the real jar: `Modifier.clickable`.
     *
     * Three separate facts, and each fails on its own:
     *
     * 1. it is **bound at all** -- before this it was declined with "no boundary type for parameter
     *    kotlin.Function0";
     * 2. its `onClick` slot carries the signature grammar `pythonx._function_slot` parses, not the
     *    bare classifier `kotlin.Function0` an ordinary object slot would carry;
     * 3. the generated body **casts** to a Kotlin function type, which is the part the composable
     *    path never needed and the part that decides whether the fragment compiles.
     */
    @Test
    fun clickableIsBoundAndItsOnClickCarriesTheSlotGrammar() {
        val foundation = jarUnder("org.jetbrains.compose.foundation", "foundation-desktop") ?: return
        val entries = ArtifactScanner.scanJar(foundation, listOf("androidx.compose.foundation"), composeClasspath())

        val clickable = entries.filter { it.name.substringAfterLast('.').substringBefore("__") == "clickable" }
        assertTrue(clickable.isNotEmpty(), "Modifier.clickable is not bound: ${entries.map { it.name }}")

        // Compose 1.6's `clickable(enabled, onClickLabel, role, onClick)` is binary-only (hidden) by
        // CMP 1.11; its source-visible successor adds a defaulted `interactionSource` before `onClick`.
        val simple = clickable.single {
            it.paramNames == listOf("<receiver>", "enabled", "onClickLabel", "role", "interactionSource", "onClick")
        }
        assertEquals("androidx.compose.ui.Modifier", simple.receiverTypeName)
        assertEquals("kotlin.Function0()->kotlin.Unit", simple.paramTypeNames.last(), "clickable.onClick")
        assertEquals("OBJECT", simple.paramTags.last(), "a callable crosses as a handle")
        // The slot has no default, so `pythonx` must be able to fill it -- exactly Column.content's
        // situation one producer over.
        assertEquals(false, simple.paramHasDefault.last(), "onClick declares no default")
        assertTrue(
            "as kotlin.Function0<kotlin.Unit>" in simple.lambdaBody,
            "the generated body must cast the handle to a Kotlin function type: ${simple.lambdaBody}",
        )
        // And no thunk: this is ordinary Kotlin source, unlike every composable binding.
        assertNull(simple.thunk, "a non-composable needs no bytecode thunk")
    }

    /**
     * A slot that forwards an argument, and one whose forwarded argument is **nullable**.
     *
     * The second is the one that says the cast is rendered from the declared `KmType` rather than
     * re-parsed out of the slot name. `Modifier.onFocusedBoundsChanged` takes
     * `(LayoutCoordinates?) -> Unit`, and the name drops nullability by design (it is a key, and
     * `pythonx` marshals `null` as `None` whatever the slot said). A cast rebuilt from that name
     * would be `Function1<LayoutCoordinates, Unit>`, which is **not** a subtype of
     * `Function1<LayoutCoordinates?, Unit>` -- `Function1` is contravariant in its argument -- and
     * would fail to compile in the generated fragment, one build after this walk.
     */
    @Test
    fun aForwardedArgumentKeepsItsDeclaredNullabilityInTheCast() {
        val ui = jarUnder("org.jetbrains.compose.ui", "ui-desktop") ?: return
        val entries = ArtifactScanner.scanJar(ui, listOf("androidx.compose.ui"), composeClasspath())

        val positioned = entries.single { it.name == "androidx.compose.ui.layout.onGloballyPositioned" }
        assertEquals(
            "kotlin.Function1(androidx.compose.ui.layout.LayoutCoordinates)->kotlin.Unit",
            positioned.paramTypeNames.last(),
        )
        assertTrue(
            "as kotlin.Function1<androidx.compose.ui.layout.LayoutCoordinates, kotlin.Unit>" in positioned.lambdaBody,
            positioned.lambdaBody,
        )

        val foundation = jarUnder("org.jetbrains.compose.foundation", "foundation-desktop") ?: return
        val focused = ArtifactScanner
            .scanJar(foundation, listOf("androidx.compose.foundation"), composeClasspath())
            .singleOrNull { it.name == "androidx.compose.foundation.onFocusedBoundsChanged" }
        assertNotNull(focused, "androidx.compose.foundation.onFocusedBoundsChanged is not bound")
        assertTrue(
            "as kotlin.Function1<androidx.compose.ui.layout.LayoutCoordinates?, kotlin.Unit>" in focused.lambdaBody,
            "the cast must keep the argument's `?`: ${focused.lambdaBody}",
        )
    }

    /**
     * The three shapes that stay declined, each with the reason spelled out rather than absent.
     *
     * `pointerInput` is the one `a179b747`'s open note named beside `clickable`, and the honest
     * finding is that it is a *different* limit: its `block` is `suspend PointerInputScope.() -> Unit`,
     * whose compiled type is `Function2<PointerInputScope, Continuation<Unit>, Any?>`. Kotlin will
     * not assign that to a `suspend` function type from source at all, and a Python callable has no
     * way to answer `COROUTINE_SUSPENDED`, so it is refused with a reason rather than bound into an
     * entry that cannot work.
     */
    @Test
    fun suspendComposableAndUnnameableFunctionSlotsAreDeclinedWithAReason() {
        val ui = jarUnder("org.jetbrains.compose.ui", "ui-desktop") ?: return
        val material = jarUnder("org.jetbrains.compose.material", "material-desktop") ?: return
        val classpath = composeClasspath()

        val uiDeclarations = ArtifactScanner.scanDeclarations(ui, listOf("androidx.compose.ui"), classpath)
        val pointerInput = uiDeclarations.filter { it.simpleName == "pointerInput" }
        assertTrue(pointerInput.isNotEmpty(), "pointerInput was not walked at all")
        // Through Compose 1.6 every overload's `block` was a suspend lambda. From Compose 1.8 the keyed
        // overloads take a `PointerInputEventHandler` (a fun interface) instead; only the key-less
        // overload still carries the suspend lambda. Until issue #38 the keyed ones also declined, on
        // their `Any?` keys -- an incidental reason, not this test's subject: #38 (SPEC B-1/U-10) makes
        // `kotlin.Any?` cross as an object handle, so a keyed overload, whose `block` is an
        // interface-typed object slot and not a function slot at all, now binds. What this test is
        // about still holds without exception: no overload with a function-typed slot binds, and
        // every overload that does not bind says why.
        assertTrue(
            pointerInput.all { declaration ->
                if (declaration.bindingName == null) {
                    declaration.declineReason != null
                } else {
                    declaration.parameters.none { it.type.qualifiedName.startsWith("kotlin.Function") }
                }
            },
            "a pointerInput overload with a function-typed slot bound, or one declined silently: " +
                "${pointerInput.map { it.bindingName to it.declineReason }}",
        )
        assertTrue(
            pointerInput.none { it.bindingName == null && it.declineReason == null },
            "every declined pointerInput overload must say why: ${pointerInput.map { it.declineReason }}",
        )
        assertTrue(
            pointerInput.any { it.declineReason?.contains("suspend") == true },
            "pointerInput's suspend-lambda overload must decline for it: ${pointerInput.map { it.declineReason }}",
        )

        val composed = uiDeclarations.filter { it.simpleName == "composed" && it.receiver != null }
        assertTrue(
            composed.isNotEmpty() && composed.all { it.declineReason?.contains("@Composable") == true },
            "Modifier.composed's factory is a composable lambda: ${composed.map { it.declineReason }}",
        )

        val swipeable = ArtifactScanner.scanDeclarations(material, listOf("androidx.compose.material"), classpath)
            .filter { it.simpleName == "swipeable" }
        assertTrue(
            swipeable.isNotEmpty() && swipeable.all { it.bindingName == null },
            "a slot over a type parameter has no spelling and must stay declined",
        )
    }

    /**
     * The composable path is **not** re-routed through the new one.
     *
     * Both now consult [ArtifactScanner.functionSlotTypeName], so the failure to guard against is a
     * composable slot losing its `@Composable` mark (which is what tells `pythonx` to thread a
     * composer) or gaining a source-level cast (which would be a `Function3` handed to a `Function0`
     * parameter). `Column` carries both risks at once: its `content` is lowered *and* its binding is
     * a thunk.
     */
    @Test
    fun theComposablePathStillBindsThroughAThunkAndKeepsItsMark() {
        val layout = jarUnder("org.jetbrains.compose.foundation", "foundation-layout-desktop") ?: return
        val column = ArtifactScanner
            .scanJar(layout, listOf("androidx.compose.foundation.layout"), composeClasspath())
            .single { it.name == "androidx.compose.foundation.layout.Column" }

        assertEquals(
            "kotlin.Function3@Composable(androidx.compose.foundation.layout.ColumnScope)->kotlin.Unit",
            column.paramTypeNames[column.paramNames.indexOf("content")],
        )
        assertNotNull(column.thunk, "a composable is still bound by bytecode, not by a source cast")
        assertTrue("as kotlin.Function" !in column.lambdaBody, "a thunk body casts nothing: ${column.lambdaBody}")
    }

    /**
     * `kotlin.system.measureTimeMillis`, which is the shape `:ksp-fixtures:artifact` calls from real
     * Python: the same `() -> Unit` slot `clickable.onClick` has, on a declaration that **invokes**
     * it rather than storing it for a composition to invoke later.
     *
     * Out of `kotlin-stdlib` rather than out of Compose on purpose. Every Compose callback is stored
     * -- `clickable` is `composed { }`, `drawBehind` is a node, `semantics` is an element -- so
     * nothing in those jars invokes a `() -> Unit` at the moment it is called, and a test that wants
     * to see the callback *run* outside a composition has to find a declaration that runs it.
     */
    @Test
    fun aStdlibDeclarationThatInvokesItsLambdaIsBound() {
        val entries = ArtifactScanner.scanJar(kotlinStdlibJar, listOf("kotlin.system"), listOf(kotlinStdlibJar))
        val measure = entries.single { it.name == "kotlin.system.measureTimeMillis" }
        assertEquals(listOf("block"), measure.paramNames)
        assertEquals("kotlin.Function0()->kotlin.Unit", measure.paramTypeNames.single())
        assertEquals("INT", measure.returnTag, "a Long return crosses as INT")
    }

    /**
     * The scorecard this change is measured by, in the same shape
     * `ArtifactScannerTest.composeModifierExtensionsSurviveBothGates` prints it.
     *
     * The number that matters is not "how many bind" but **how many of the ones that do not, do not
     * for a function-typed parameter** -- 44 of 46 before, 6 of 8 after, with 157 `Modifier`
     * extensions bound where there were 107. Counted per *declaration* here rather than per name, so
     * the numbers this test prints are the overload-level ones (50 bound, 15 declined) and the
     * name-level ones stay in `ArtifactScannerTest`.
     *
     * The assertion is a floor rather than an equality for that test's stated reason (a Compose bump
     * must not fail it), and the named declarations below are what a floor alone would not pin.
     */
    @Test
    fun mostFunctionTypedModifierExtensionsAreNoLongerDeclinedForBeingFunctionTyped() {
        val walked = listOfNotNull(
            jarUnder("org.jetbrains.compose.foundation", "foundation-layout-desktop"),
            jarUnder("org.jetbrains.compose.ui", "ui-unit-desktop"),
            jarUnder("org.jetbrains.compose.foundation", "foundation-desktop"),
            jarUnder("org.jetbrains.compose.ui", "ui-desktop"),
            jarUnder("org.jetbrains.compose.material", "material-desktop"),
            jarUnder("org.jetbrains.compose.material3", "material3-desktop"),
        )
        if (walked.size < 6) return
        val classpath = composeClasspath()

        val declarations = walked.flatMap {
            ArtifactScanner.scanDeclarations(it, listOf("androidx.compose"), classpath)
        }
        val modifierExtensions = declarations
            .filter { it.receiver?.qualifiedName == "androidx.compose.ui.Modifier" && !it.isComposable }
            .distinctBy { it.owner + "." + it.simpleName + it.parameters.map { p -> p.type.qualifiedName } }
        val functionTyped = modifierExtensions.filter { declaration ->
            declaration.parameters.any { it.type.qualifiedName.startsWith("kotlin.Function") }
        }
        val bound = functionTyped.filter { it.bindingName != null }
        val stillDeclined = functionTyped.filter { it.bindingName == null }

        println("function-typed Modifier extensions: ${bound.size} bound, ${stillDeclined.size} declined")
        stillDeclined.groupBy { it.declineReason ?: "(no reason recorded)" }.forEach { (reason, group) ->
            println("  ${group.map { it.simpleName }.distinct()}: $reason")
        }
        assertTrue(
            bound.size >= 25,
            "expected the function-typed Modifier surface to open; got ${bound.map { it.simpleName }}",
        )
        listOf("clickable", "combinedClickable", "semantics", "drawBehind", "onKeyEvent", "toggleable")
            .forEach { name ->
                assertTrue(
                    bound.any { it.simpleName == name },
                    "$name is still declined: " + stillDeclined.filter { it.simpleName == name }
                        .map { it.declineReason },
                )
            }
    }
}
