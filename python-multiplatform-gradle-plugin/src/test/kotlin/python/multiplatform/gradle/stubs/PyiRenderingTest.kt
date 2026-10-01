package python.multiplatform.gradle.stubs

import python.multiplatform.gradle.model.DeclarationModel
import python.multiplatform.gradle.model.DeclaredParameter
import python.multiplatform.gradle.model.KotlinTypeModel
import python.multiplatform.gradle.model.ValueClassModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two stub products of `docs/design/pyi-generation-design.md` §5.3, rendered from one model.
 *
 * | product | namespace | what it describes |
 * |---|---|---|
 * | Kotlin-FQN | `androidx.compose.foundation.layout`, `junit.runner.Version` | the modules `PythonProxySource` injects into `sys.modules` **today** -- one `def` per table key, and the boundary's own types |
 * | Pythonic | `pythonx.compose.layout` | what a user imports: snake_case, extension-as-method, `@overload`, the value-class allowlist |
 *
 * The split in what the *types* say is not decoration. `PythonProxySource.renderOne` emits
 * `def _pm_f_7(a0, a1)` and `UpcallTrampoline` marshals a `Dp` parameter as a raw float and a
 * `Modifier` return as a `HandleTable` integer -- so the Kotlin-FQN stub says `float` and `int`,
 * which is what that module actually accepts and returns. `docs/design/pyi-generation-design.md` §7 lists
 * the handle-to-proxy wrapping as not yet done; the Pythonic product is the surface that *will*
 * have it, and it is a separate product precisely so that the first one does not have to lie.
 *
 * Everything asserted here is generated text. The mypy runs that decided the *shape* are in the
 * design document; what this pins is that the generator emits that shape and keeps emitting it.
 */
class PyiRenderingTest {

    private val modifier = KotlinTypeModel("androidx.compose.ui.Modifier")
    private val dp = KotlinTypeModel(
        "androidx.compose.ui.unit.Dp",
        valueClass = ValueClassModel(KotlinTypeModel("kotlin.Float"), constructorIsPublic = true, propertyIsPublic = true),
    )
    private val paddingValues = KotlinTypeModel("androidx.compose.foundation.layout.PaddingValues")

    private fun tagOf(type: KotlinTypeModel): String = when (type.qualifiedName) {
        "kotlin.Float", "kotlin.Double" -> "FLOAT"
        "kotlin.Int", "kotlin.Long" -> "INT"
        "kotlin.String" -> "STRING"
        "kotlin.Unit" -> "UNIT"
        else -> type.valueClass?.let { tagOf(it.underlying) } ?: "OBJECT"
    }

    private fun padding(vararg params: Pair<String, KotlinTypeModel>, binding: String) = DeclarationModel(
        simpleName = "padding",
        owner = "androidx.compose.foundation.layout",
        ownerIsClass = false,
        receiver = modifier,
        receiverBoundaryTag = "OBJECT",
        parameters = params.map {
            DeclaredParameter(it.first, it.second, declaresDefault = params.size > 1, boundaryTag = tagOf(it.second))
        },
        returnType = modifier,
        returnBoundaryTag = "OBJECT",
        bindingName = binding,
    )

    private val paddingOverloads = listOf(
        padding("start" to dp, "top" to dp, "end" to dp, "bottom" to dp, binding = "androidx.compose.foundation.layout.padding__Dp_Dp_Dp_Dp"),
        padding("all" to dp, binding = "androidx.compose.foundation.layout.padding__Dp"),
        padding("paddingValues" to paddingValues, binding = "androidx.compose.foundation.layout.padding__PaddingValues"),
        padding("horizontal" to dp, "vertical" to dp, binding = "androidx.compose.foundation.layout.padding__Dp_Dp"),
    )

    private val manifest = StubManifest(
        modules = mapOf(
            "pythonx.compose.layout" to "androidx.compose.foundation.layout",
            "pythonx.compose.ui" to "androidx.compose.ui",
            "pythonx.compose.ui.unit" to "androidx.compose.ui.unit",
        ),
        rawPrimitiveValueClasses = setOf("androidx.compose.ui.unit.Dp"),
    )

    // ------------------------------------------------------------------- Kotlin-FQN product

    /**
     * The Kotlin-FQN stub describes exactly what `PythonProxySource.renderOne` publishes: a module
     * named by everything before the last dot of the table key, and one function named by the leaf.
     */
    @Test
    fun theKotlinFqnProductIsOneModulePerTableKeyPrefix() {
        val files = renderKotlinFqnStubs(paddingOverloads)
        val body = files.getValue("androidx/compose/foundation/layout/__init__.pyi")
        assertTrue("def padding__Dp(" in body, body)
        assertTrue("def padding__PaddingValues(" in body, body)
        assertFalse("def padding(" in body, "the bare name is not a table key: $body")
    }

    /**
     * **Positional-only, and the types are the boundary's.**
     *
     * `PythonProxySource.params` names the generated proxy's parameters `a0, a1, ...`, so the Kotlin
     * name is nowhere in the runtime object: a stub promising `padding__Dp(all=16)` would type-check
     * and then raise `TypeError`. The names are still emitted because they are what an editor shows,
     * and the `/` makes them non-binding.
     *
     * `Dp` marshals as a raw float (the walker unwraps the value class in the generated body) and
     * `Modifier` as a `HandleTable` integer, so those are the annotations. The Kotlin signature goes
     * in the docstring, which is where a reader wants it and where no checker can act on it.
     */
    @Test
    fun kotlinFqnParametersArePositionalOnlyAndCarryTheBoundarysOwnTypes() {
        val body = renderKotlinFqnStubs(paddingOverloads).getValue("androidx/compose/foundation/layout/__init__.pyi")
        assertTrue("def padding__Dp(receiver: int, all: float, /) -> int:" in body, body)
        assertTrue(
            "\"\"\"Kotlin: androidx.compose.ui.Modifier.padding(all: androidx.compose.ui.unit.Dp): " +
                "androidx.compose.ui.Modifier\"\"\"" in body,
            body,
        )
    }

    /** A static on a class gets the class's own module, because that is the `sys.modules` entry the
     * runtime creates for it: `junit.runner.Version.id` publishes onto `junit.runner.Version`. */
    @Test
    fun aStaticOnAClassGetsTheClassAsItsModule() {
        val id = DeclarationModel(
            simpleName = "id",
            owner = "junit.runner.Version",
            ownerIsClass = true,
            receiver = null,
            parameters = emptyList(),
            returnType = KotlinTypeModel("kotlin.String"),
            returnBoundaryTag = "STRING",
            bindingName = "junit.runner.Version.id",
        )
        val files = renderKotlinFqnStubs(listOf(id))
        assertEquals(listOf("junit/runner/Version/__init__.pyi"), files.keys.toList())
        assertTrue("def id() -> str:" in files.values.single(), files.values.single())
    }

    /** §3.2: a Java declaration has no parameter names in the bytecode, so the generator invents
     * none -- `__a0`, positional-only, rather than a keyword a caller could believe in. */
    @Test
    fun aJavaDeclarationGetsAnonymousPositionalParameters() {
        val assertEqualsDecl = DeclarationModel(
            simpleName = "assertEquals",
            owner = "org.junit.Assert",
            ownerIsClass = true,
            receiver = null,
            parameters = listOf(
                DeclaredParameter(null, KotlinTypeModel("kotlin.Long"), false, "INT"),
                DeclaredParameter(null, KotlinTypeModel("kotlin.Long"), false, "INT"),
            ),
            returnType = KotlinTypeModel("kotlin.Unit"),
            returnBoundaryTag = "UNIT",
            bindingName = "org.junit.Assert.assertEquals__Long_Long",
            parameterNamesKnown = false,
        )
        val body = renderKotlinFqnStubs(listOf(assertEqualsDecl)).values.single()
        assertTrue("def assertEquals__Long_Long(__a0: int, __a1: int, /) -> None:" in body, body)
    }

    /** A declaration the binder declined has no table key and therefore no runtime attribute; a stub
     * for it would promise a call that raises. §4.5's rule, applied generally. */
    @Test
    fun aDeclinedDeclarationIsNotStubbed() {
        val declined = padding("all" to dp, binding = "x").copy(bindingName = null, declineReason = "ambiguous overload group")
        assertEquals(emptyMap(), renderKotlinFqnStubs(listOf(declined)))
    }

    // --------------------------------------------------------------------- Pythonic product

    /**
     * §4.4, measured there against mypy 2.3.0 and rendered here: an operation on a class is an
     * **attribute whose type is a Protocol with an overloaded `__call__`**, which is the only shape
     * that satisfies `Modifier.padding(16)`, `m.padding(16)` and `def f(m: Modifier)` at once. The
     * metaclass of §4.2 is measured not to work in either CPython or mypy.
     */
    @Test
    fun anExtensionIsAClassVarProtocolOnItsReceiver() {
        val files = renderPythonicStubs(paddingOverloads, manifest)
        val ui = files.getValue("pythonx/compose/ui/__init__.pyi")
        assertTrue("class _Modifier_padding(Protocol):" in ui, ui)
        assertTrue("class Modifier:" in ui, ui)
        assertTrue("    padding: ClassVar[_Modifier_padding]" in ui, ui)
        assertFalse("metaclass" in ui, "§4.3: the metaclass shape does not work in mypy or in CPython")
    }

    /**
     * §3.6's load-bearing detail. mypy reports overlapping overloads only when the *return types*
     * disagree, and every `Modifier` extension returns `Modifier` -- so nothing warns, and the
     * checker silently takes the **first** match. `p(8)` matches `all: Dp | float` and also matches
     * `horizontal` positionally; arity-ascending is what puts the right one first.
     */
    @Test
    fun overloadsAreEmittedArityAscending() {
        val ui = renderPythonicStubs(paddingOverloads, manifest).getValue("pythonx/compose/ui/__init__.pyi")
        val arities = Regex("""def __call__\(self, ([^)]*)\) -> Modifier""").findAll(ui)
            .map { match -> if (match.groupValues[1].isBlank()) 0 else match.groupValues[1].split(",").size }
            .toList()
        assertEquals(arities.sorted(), arities, "overload order is part of the output: $ui")
        assertEquals(listOf(1, 1, 2, 4), arities, ui)
    }

    /**
     * §3.6, the second half of the same paragraph: the `@overload` order and the Python-side
     * dispatcher's resolution order "must come from one place in the generator". The dispatcher does
     * not exist yet, so the generator writes the order down as data for it rather than leaving the
     * two to be written twice and drift.
     */
    @Test
    fun theDispatchOrderIsEmittedBesideTheStubAndMatchesIt() {
        val files = renderPythonicStubs(paddingOverloads, manifest)
        val dispatch = files.getValue("pythonx/compose/ui/_pm_dispatch.json")
        assertEquals(
            """
            {
              "Modifier.padding": [
                "androidx.compose.foundation.layout.padding__Dp",
                "androidx.compose.foundation.layout.padding__PaddingValues",
                "androidx.compose.foundation.layout.padding__Dp_Dp",
                "androidx.compose.foundation.layout.padding__Dp_Dp_Dp_Dp"
              ]
            }
            """.trimIndent() + "\n",
            dispatch,
        )
    }

    /** §3.6: metadata carries `declaresDefaultValue` and not the value, and `= ...` is the stub
     * spelling for "has a default I cannot name". */
    @Test
    fun aParameterThatDeclaresADefaultIsSpelledWithEllipsis() {
        val ui = renderPythonicStubs(paddingOverloads, manifest).getValue("pythonx/compose/ui/__init__.pyi")
        assertTrue("def __call__(self, horizontal: Dp | float = ..., vertical: Dp | float = ...) -> Modifier: ..." in ui, ui)
    }

    /** §3.6's last paragraph: Kotlin parameter names go through the snake_case rule, which is what
     * `pythonx-compose`'s `text.py` already does by hand. */
    @Test
    fun pythonicParameterNamesAreSnakeCase() {
        val ui = renderPythonicStubs(paddingOverloads, manifest).getValue("pythonx/compose/ui/__init__.pyi")
        assertTrue("padding_values: PaddingValues" in ui, ui)
        assertFalse("paddingValues" in ui, ui)
    }

    /**
     * §3.5: Compose's trailing lambda has no Python equivalent, so a trailing `content` lambda is
     * forced to a keyword. Checked in §4.4's mypy run as
     * `Column(modifier=Modifier.size(4), content=lambda: Text("x"))`.
     */
    @Test
    fun aTrailingContentLambdaIsKeywordOnly() {
        val column = DeclarationModel(
            simpleName = "column",
            owner = "androidx.compose.foundation.layout",
            ownerIsClass = false,
            receiver = null,
            parameters = listOf(
                DeclaredParameter("modifier", modifier, declaresDefault = true, boundaryTag = "OBJECT"),
                DeclaredParameter(
                    "content",
                    KotlinTypeModel("kotlin.Function0", arguments = listOf(KotlinTypeModel("kotlin.Unit"))),
                    declaresDefault = false,
                    boundaryTag = "OBJECT",
                ),
            ),
            returnType = KotlinTypeModel("kotlin.Unit"),
            returnBoundaryTag = "UNIT",
            bindingName = "androidx.compose.foundation.layout.column",
        )
        val layout = renderPythonicStubs(listOf(column), manifest).getValue("pythonx/compose/layout/__init__.pyi")
        assertTrue(
            "def column(modifier: Modifier = ..., *, content: Callable[[], None]) -> None: ..." in layout,
            layout,
        )
    }

    /**
     * **Found by running mypy, not by reading the design.** A lone `@overload` is an error --
     * "Single overload definition, multiple required" -- and it is an error in the *consumer's* type
     * check, over a file they did not write. The first generated Compose stub produced 60 of them.
     *
     * §4.4's shape is unaffected: the operation is still a `ClassVar[Protocol]`, which is what makes
     * `Modifier.padding(16)` and `m.padding(16)` both resolve. Only the `@overload` decoration goes.
     */
    @Test
    fun aProtocolWithOneCallSignatureHasNoOverloadDecoration() {
        val single = listOf(padding("all" to dp, binding = "androidx.compose.foundation.layout.padding__Dp"))
        val ui = renderPythonicStubs(single, manifest).getValue("pythonx/compose/ui/__init__.pyi")
        assertTrue("class _Modifier_padding(Protocol):" in ui, ui)
        assertTrue("    def __call__(self, all: Dp | float) -> Modifier: ..." in ui, ui)
        assertFalse("@overload" in ui, "mypy rejects a single @overload outright: $ui")
    }

    /**
     * §7's third open item, answered by running the scan: "two Kotlin overloads can map to identical
     * Python signatures once `Dp | float` widening is applied. The generator must detect that and
     * drop or qualify."
     *
     * The real instance is Compose's second `WindowInsets` factory, whose four `Int` parameters are
     * all accepted by the `Dp | float` overload above it -- a checker's numeric tower makes `int`
     * acceptable wherever `float` is. mypy called it "signature 2 will never be matched". It is
     * dropped from this product and named in a comment, because it is still bound and still in the
     * Kotlin-FQN product under its table key.
     */
    @Test
    fun anOverloadNoCallCouldEverSelectIsDroppedAndSaidSo() {
        val int = KotlinTypeModel("kotlin.Int")
        val wide = padding("left" to dp, binding = "androidx.compose.foundation.layout.padding__Dp")
        val shadowed = padding("left" to int, binding = "androidx.compose.foundation.layout.padding__Int")
        val ui = renderPythonicStubs(listOf(wide, shadowed), manifest).getValue("pythonx/compose/ui/__init__.pyi")

        assertTrue("def __call__(self, left: Dp | float) -> Modifier: ..." in ui, ui)
        assertFalse("def __call__(self, left: int) -> Modifier: ..." in ui, "an unmatchable overload is an error in the consumer's own check: $ui")
        assertTrue("padding__Int is unreachable through this signature" in ui, ui)

        val dispatch = renderPythonicStubs(listOf(wide, shadowed), manifest)
            .getValue("pythonx/compose/ui/_pm_dispatch.json")
        assertFalse("padding__Int" in dispatch, "a dropped overload is not in the dispatch order either: $dispatch")
    }

    /**
     * Kotlin's "fake constructor" -- a top-level function whose name is a class's, which is how
     * Compose spells `PaddingValues(16.dp)` and `WindowInsets(...)`. In Python a class *is* callable,
     * so it belongs on the class as `__init__`; a module-level `def PaddingValues` beside
     * `class PaddingValues` is a redefinition, and mypy said so against the real jar
     * ("Name 'PaddingValues' already defined").
     */
    @Test
    fun aFactoryFunctionNamedAfterItsClassBecomesThatClassesInit() {
        val factory = DeclarationModel(
            simpleName = "PaddingValues",
            owner = "androidx.compose.foundation.layout",
            ownerIsClass = false,
            receiver = null,
            parameters = listOf(DeclaredParameter("all", dp, declaresDefault = false, boundaryTag = "FLOAT")),
            returnType = paddingValues,
            returnBoundaryTag = "OBJECT",
            bindingName = "androidx.compose.foundation.layout.PaddingValues__Dp",
        )
        val layout = renderPythonicStubs(listOf(factory), manifest).getValue("pythonx/compose/layout/__init__.pyi")
        assertTrue("class PaddingValues:" in layout, layout)
        assertTrue("    def __init__(self, all: Dp | float) -> None: ..." in layout, layout)
        assertFalse("def PaddingValues(" in layout, "a module-level def beside the class is a redefinition: $layout")
    }

    /** §6.1 measurement 2: deleting only `py.typed` turned every revealed type into `Any`. It is
     * emitted into the distribution's top-level *regular* package -- `pythonx` is a namespace
     * package and cannot carry it. */
    @Test
    fun theDistributionCarriesAPyTypedMarker() {
        val files = renderPythonicStubs(paddingOverloads, manifest)
        assertTrue("pythonx/compose/py.typed" in files.keys, files.keys.toString())
        assertEquals("", files.getValue("pythonx/compose/py.typed"))
        assertFalse("pythonx/py.typed" in files.keys, "pythonx is a namespace package -- §5.1")
    }

    /** With no manifest there is no Pythonic product at all: which Kotlin package a `pythonx` module
     * wraps is the Python package's design decision, and §5.2 shows it is not inferable
     * (`pythonx.compose.layout` drops `foundation.`). */
    @Test
    fun withNoManifestOnlyTheKotlinFqnProductIsEmitted() {
        assertEquals(emptyMap(), renderPythonicStubs(paddingOverloads, StubManifest.EMPTY))
        assertTrue(renderKotlinFqnStubs(paddingOverloads).isNotEmpty())
    }

    /** §4.5: 74 member extensions on `Modifier` need a dispatch receiver no shape has been chosen
     * for. A stub saying `Modifier.weight(1.0)` checks is a stub promising a call the runtime cannot
     * make -- so a declaration whose receiver class cannot be placed is left out, not guessed at. */
    @Test
    fun aReceiverWhoseModuleIsNotMappedIsNotStubbed() {
        val orphan = DeclarationModel(
            simpleName = "weight",
            owner = "some.unmapped.pkg",
            ownerIsClass = false,
            receiver = KotlinTypeModel("some.unmapped.pkg.RowScope"),
            parameters = listOf(DeclaredParameter("weight", KotlinTypeModel("kotlin.Float"), false, "FLOAT")),
            returnType = modifier,
            returnBoundaryTag = "OBJECT",
            bindingName = "some.unmapped.pkg.weight",
        )
        val files = renderPythonicStubs(listOf(orphan), manifest)
        assertTrue(files.keys.none { it.endsWith(".pyi") }, files.keys.toString())
    }

    @Test
    fun aStaticGetterIsRenderedAsAPropertyNotAFunction() {
        val staticGetter = DeclarationModel(
            simpleName = "Start",
            owner = "androidx.compose.foundation.layout.Arrangement",
            ownerIsClass = true,
            receiver = null,
            parameters = emptyList(),
            returnType = KotlinTypeModel("androidx.compose.foundation.layout.Arrangement.Horizontal"),
            returnBoundaryTag = "OBJECT",
            bindingName = "androidx.compose.foundation.layout.Arrangement.Start",
            kind = "STATIC_GETTER",
        )
        
        val manifestWithArrangement = StubManifest(
            modules = mapOf("pythonx.compose.layout" to "androidx.compose.foundation.layout"),
            rawPrimitiveValueClasses = emptySet(),
        )

        val files = renderPythonicStubs(listOf(staticGetter), manifestWithArrangement)
        val layout = files.getValue("pythonx/compose/layout/__init__.pyi")
        
        // Should not be a def
        assertFalse("def Start(" in layout, "STATIC_GETTER should not be a function: $layout")
        
        // Should be a variable annotation in the class body. Arrangement class should exist.
        assertTrue("class Arrangement:" in layout, layout)
        assertTrue("    Start: ClassVar[Arrangement.Horizontal]" in layout, layout)

        // The annotation is only worth emitting if the name it uses resolves. This module has no
        // receiver methods, which is the only thing that used to pull `ClassVar` into the import
        // line -- a module whose sole binding is a constant would have emitted an annotation
        // referring to a name it never imported.
        assertTrue(
            layout.lineSequence().any { it.startsWith("from typing import ") && "ClassVar" in it },
            layout,
        )
    }
}
