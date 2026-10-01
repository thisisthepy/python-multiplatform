package python.multiplatform.gradle.stubs

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `docs/design/pyi-generation-design.md` §3.6 and the naming rule it inherits: types, objects and
 * composables are PascalCase; functions, methods and parameters are snake_case.
 *
 * The half that is not obvious, and the reason this file exists rather than one `assertEquals` per
 * example: **the generator runs the rule forwards and the adapter has to run it backwards.** A stub
 * name that does not convert back to the Kotlin name it was made from is a name `pythonx`'s
 * `__getattr__` cannot resolve -- the stub would promise an attribute the runtime raises
 * `AttributeError` for. So the property under test is not "the rule produces this string", it is
 * "every name the generator emits survives the round trip", and the rule is *built* so that it does:
 * an underscore already in the Kotlin name is escaped by doubling, which is the one thing that makes
 * the inverse total (`foo_bar` and `fooBar` are different declarations and must not collide).
 */
class PythonNameConventionsTest {

    @Test
    fun aCamelCaseFunctionNameBecomesSnakeCase() {
        assertEquals("font_size", PythonNames.functionName("fontSize", isComposable = false))
        assertEquals("letter_spacing", PythonNames.functionName("letterSpacing", isComposable = false))
        assertEquals("padding", PythonNames.functionName("padding", isComposable = false))
        assertEquals("padding_values", PythonNames.functionName("paddingValues", isComposable = false))
    }

    /** A composable is PascalCase in Kotlin and stays PascalCase in Python -- `Text`, not `text`.
     * No composable currently reaches the model (`ArtifactScanner` declines them for their synthetic
     * `$composer` parameter), so this pins the rule rather than an observation. */
    @Test
    fun aComposableKeepsItsPascalCaseName() {
        assertEquals("Text", PythonNames.functionName("Text", isComposable = true))
        assertEquals("LazyColumn", PythonNames.functionName("LazyColumn", isComposable = true))
    }

    @Test
    fun aTypeNameIsPascalCaseAndUnchangedForAKotlinClass() {
        assertEquals("Modifier", PythonNames.typeName("Modifier"))
        assertEquals("PaddingValues", PythonNames.typeName("PaddingValues"))
    }

    /**
     * The round trip, stated as the rule rather than as a list of examples.
     *
     * `foo_bar` is the case that forces the escape to exist: without it its Python spelling would be
     * `foo_bar`, whose inverse is `fooBar` -- a different Kotlin declaration. Two Kotlin names would
     * collide on one Python name and the adapter would resolve the wrong one.
     */
    @Test
    fun everyEmittedNameConvertsBackToTheKotlinNameItCameFrom() {
        val kotlinNames = listOf(
            "padding", "fontSize", "letterSpacing", "paddingValues", "trimIndent",
            "sumMeters", "addMeters", "getPreference", "id", "useV2", "parseHTML",
            "foo_bar", "_leadingUnderscore", "already_snake", "x", "URL", "class", "from",
        )
        kotlinNames.forEach { kotlin ->
            val python = PythonNames.functionName(kotlin, isComposable = false)
            assertEquals(
                kotlin,
                PythonNames.kotlinNameOf(python),
                "'$kotlin' -> '$python' -> '${PythonNames.kotlinNameOf(python)}' does not round trip",
            )
        }
    }

    /** An underscore in the Kotlin name is doubled rather than passed through, which is what keeps
     * [PythonNames.kotlinNameOf] total. */
    @Test
    fun anUnderscoreInTheKotlinNameIsEscapedByDoubling() {
        assertEquals("foo__bar", PythonNames.functionName("foo_bar", isComposable = false))
        assertEquals("already__snake", PythonNames.functionName("already_snake", isComposable = false))
        assertEquals("foo_bar", PythonNames.kotlinNameOf("foo__bar"))
    }

    /** Parameters go through the same rule as functions -- `docs/design/pyi-generation-design.md` §3.6's
     * last paragraph, which `pythonx-compose`'s `text.py` already does by hand. */
    @Test
    fun parameterNamesUseTheSameRule() {
        assertEquals("font_size", PythonNames.parameterName("fontSize"))
        assertEquals("letter_spacing", PythonNames.parameterName("letterSpacing"))
    }

    /** A Python keyword cannot be a parameter name; the generator suffixes rather than drops, and
     * the suffix inverts. */
    @Test
    fun aParameterNamedLikeAPythonKeywordIsEscaped() {
        assertEquals("class_", PythonNames.parameterName("class"))
        assertEquals("from_", PythonNames.parameterName("from"))
        assertEquals("lambda_", PythonNames.parameterName("lambda"))
        assertEquals("class", PythonNames.kotlinNameOf("class_"))
    }
}
