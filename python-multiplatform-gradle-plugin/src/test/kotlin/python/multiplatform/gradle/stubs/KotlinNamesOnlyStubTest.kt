package python.multiplatform.gradle.stubs

import python.multiplatform.gradle.model.DeclarationModel
import python.multiplatform.gradle.model.DeclaredParameter
import python.multiplatform.gradle.model.KotlinTypeModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AGENTS.md section 12 rule 1: the binder never exports a Kotlin **namespace** under another name, and
 * has no feature that turns `androidx` into `pythonx`. Member and parameter names are another matter
 * (issue #131): the runtime serves each lower-case-first name under its snake_case alias as well, and
 * the stub says so -- the Kotlin `def` stays, the alias is an assignment beside it, and a parameter is
 * written under the keyword `inspect.signature` shows.
 *
 * These tests look at everything the generator can emit for a set of androidx declarations, not at
 * one function, so a namespace-renaming feature added anywhere in the stub pipeline fails them.
 */
class KotlinNamesOnlyStubTest {

    private val modifier = KotlinTypeModel("androidx.compose.ui.Modifier")
    private val float = KotlinTypeModel("kotlin.Float")

    private fun decl(
        name: String,
        owner: String = "androidx.compose.foundation.layout",
        parameters: List<DeclaredParameter> = emptyList(),
        binding: String = "$owner.$name",
    ) = DeclarationModel(
        simpleName = name,
        owner = owner,
        ownerIsClass = false,
        receiver = modifier,
        receiverBoundaryTag = "OBJECT",
        parameters = parameters,
        returnType = modifier,
        returnBoundaryTag = "OBJECT",
        bindingName = binding,
    )

    private fun param(name: String?, default: Boolean = false) = DeclaredParameter(name, float, default, "FLOAT")

    private val declarations = listOf(
        decl("fillMaxWidth", parameters = listOf(param("fraction", default = true))),
        decl("windowInsetsPadding", parameters = listOf(param("windowInsets"))),
    )

    /** Every file the stub pipeline emits for [declarations]: `PythonStubsTask` writes exactly this. */
    private fun everythingEmitted(): Map<String, String> = renderKotlinFqnStubs(declarations)

    @Test
    fun theStubLivesUnderTheKotlinModulePathAndNothingLivesUnderPythonx() {
        val files = everythingEmitted()
        assertTrue("androidx/compose/foundation/layout/__init__.pyi" in files, files.keys.toString())
        val renamed = files.keys.filter { it.startsWith("pythonx/") }
        assertEquals(emptyList(), renamed, "the binder must not emit a pythonx namespace")
        assertFalse(files.keys.any { it.endsWith("py.typed") || it.endsWith("_pm_dispatch.json") }, files.keys.toString())
    }

    /** The Kotlin name is the `def`; its Pythonic alias is the same object, as the runtime serves it. */
    @Test
    fun aKotlinNameKeepsItsDefAndGainsItsPythonicAlias() {
        val all = everythingEmitted().values.joinToString("\n")
        assertTrue("def fillMaxWidth(" in all, all)
        assertTrue("fraction: float" in all, all)
        assertTrue(Regex("^fill_max_width = fillMaxWidth${'$'}", RegexOption.MULTILINE).containsMatchIn(all), all)
        assertTrue(Regex("^window_insets_padding = windowInsetsPadding${'$'}", RegexOption.MULTILINE).containsMatchIn(all), all)
        // A parameter has one name in a stub: the Pythonic keyword.
        assertTrue("window_insets: float" in all, all)
        assertFalse("windowInsets: float" in all, all)
        // The namespace is not converted: the module path is the Kotlin package.
        assertFalse(Regex("^def fill_max_width\\(", RegexOption.MULTILINE).containsMatchIn(all), all)
    }

    /**
     * The collision policy (`pythonicAliases`, the runtime's `pythonic_aliases`): an alias two Kotlin
     * names map to is not written, and an alias that is another declaration's Kotlin name belongs to it.
     */
    @Test
    fun anAmbiguousAliasIsNotWrittenAndAKotlinNameOwnsItsSpelling() {
        val text = renderKotlinFqnStubs(
            listOf(decl("toURL"), decl("toUrl"), decl("fooBar"), decl("foo_bar")),
        ).values.joinToString("\n")
        assertFalse(Regex("^to_url = ", RegexOption.MULTILINE).containsMatchIn(text), text)
        assertFalse(Regex("^foo_bar = ", RegexOption.MULTILINE).containsMatchIn(text), text)
        assertTrue(Regex("^def foo_bar\\(", RegexOption.MULTILINE).containsMatchIn(text), text)
        assertTrue(Regex("^def toURL\\(", RegexOption.MULTILINE).containsMatchIn(text), text)
    }

    @Test
    fun theStubNeverMentionsAPythonxModule() {
        val files = everythingEmitted()
        val all = files.keys.joinToString("\n") + "\n" + files.values.joinToString("\n")
        assertFalse("pythonx" in all, all)
    }

    // ------------------------------------------------- signatures: keyword names and defaults

    private fun body(vararg d: DeclarationModel) =
        renderKotlinFqnStubs(d.toList()).getValue("androidx/compose/foundation/layout/__init__.pyi")

    /** The receiver is positional-only; declared parameters are positional-or-keyword by their
     * Pythonic keyword (the Kotlin name where they have none), and a defaulted one carries `= ...`. */
    @Test
    fun declaredParametersAreKeywordCapableByTheirKotlinNameAndDefaultsAreMarked() {
        val b = body(decl("fillMaxWidth", parameters = listOf(param("fraction", default = true))))
        assertTrue("def fillMaxWidth(receiver: androidx.compose.ui.Modifier, /, fraction: float = ...) -> androidx.compose.ui.Modifier:" in b, b)
    }

    @Test
    fun aRequiredParameterHasNoDefaultMarker() {
        val b = body(decl("windowInsetsPadding", parameters = listOf(param("windowInsets"))))
        assertTrue("def windowInsetsPadding(receiver: androidx.compose.ui.Modifier, /, window_insets: float) -> androidx.compose.ui.Modifier:" in b, b)
    }

    /** `in` is a Python keyword: it cannot be a keyword argument in `.pyi`, so it and everything
     * before it go positional-only, and the unwritable one is spelled `__a<index>`. No renamed
     * spelling (`in_`) is invented. */
    @Test
    fun aParameterNamedLikeAPythonKeywordForcesAPositionalOnlyPrefix() {
        val b = body(
            decl("scan", parameters = listOf(param("first"), param("in"), param("last", default = true))),
        )
        assertTrue("def scan(receiver: androidx.compose.ui.Modifier, first: float, __a1: float, /, last: float = ...) -> androidx.compose.ui.Modifier:" in b, b)
        assertFalse("in_" in b, b)
    }

    @Test
    fun anUnknownParameterNameListStaysAnonymousAndPositionalOnly() {
        val b = body(
            decl("assertEquals", parameters = listOf(param(null), param(null))).copy(parameterNamesKnown = false),
        )
        assertTrue("def assertEquals(receiver: androidx.compose.ui.Modifier, __a0: float, __a1: float, /) -> androidx.compose.ui.Modifier:" in b, b)
    }

    /** Python rejects a non-default parameter after a default one, and `inspect.signature` on the
     * binder's own callable makes such a parameter keyword-only (`KotlinSurface.kt`): so does the
     * stub, and the default stays marked. */
    @Test
    fun aRequiredParameterAfterADefaultIsKeywordOnlyLikeTheRuntimeSignature() {
        val b = body(decl("mix", parameters = listOf(param("a", default = true), param("b"))))
        assertTrue("def mix(receiver: androidx.compose.ui.Modifier, /, a: float = ..., *, b: float) -> androidx.compose.ui.Modifier:" in b, b)
    }
}
