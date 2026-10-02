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
 * The Kotlin-FQN stub product of `docs/design/pyi-generation-design.md` §5.3: the modules
 * `PythonProxySource` injects into `sys.modules`, one `def` per table key, under the Kotlin names.
 * There is no second, renamed product (AGENTS.md §12 rule 1); `KotlinNamesOnlyStubTest` pins that.
 *
 * The *types* say what the boundary carries, not what the declaration says. `PythonProxySource`
 * marshals a `Dp` parameter as a raw float and a `Modifier` return as a `HandleTable` integer -- so
 * the stub says `float` and `int`, which is what that module actually accepts and returns.
 *
 * Everything asserted here is generated text.
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
     * **The receiver is positional-only, declared parameters are keyword-capable by their Kotlin name,
     * and the types are the boundary's.**
     *
     * The runtime accepts keyword arguments by Kotlin parameter name and honours Kotlin defaults, so
     * `padding__Dp(m, all=16)` is a valid call and the stub says so. `Dp` marshals as a raw float and
     * `Modifier` as a `HandleTable` integer, so those are the annotations. The Kotlin signature goes
     * in the docstring, which is where a reader wants it and where no checker can act on it.
     */
    @Test
    fun kotlinFqnParametersAreKeywordCapableAndCarryTheBoundarysOwnTypes() {
        val body = renderKotlinFqnStubs(paddingOverloads).getValue("androidx/compose/foundation/layout/__init__.pyi")
        assertTrue("def padding__Dp(receiver: int, /, all: float) -> int:" in body, body)
        // `padding(start, top, end, bottom)` has four defaulted parameters.
        assertTrue("def padding__Dp_Dp_Dp_Dp(receiver: int, /, start: float = ..., top: float = ..., end: float = ..., bottom: float = ...) -> int:" in body, body)
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
}
