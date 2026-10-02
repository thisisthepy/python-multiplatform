package python.multiplatform.gradle.stubs

import python.multiplatform.gradle.model.DeclarationModel
import python.multiplatform.gradle.model.DeclaredParameter
import python.multiplatform.gradle.model.KotlinTypeModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AGENTS.md section 12 rule 1: the binder never exports a Kotlin namespace under another name, and has
 * no feature that turns `androidx` into `pythonx`. Pythonic renaming is the `pythonx-compose`
 * package's job.
 *
 * These tests look at everything the generator can emit for a set of androidx declarations, not at
 * one function, so a renaming feature added anywhere in the stub pipeline fails them.
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

    @Test
    fun kotlinNamesAreNeverSnakeCased() {
        val all = everythingEmitted().values.joinToString("\n")
        assertTrue("def fillMaxWidth(" in all, all)
        assertTrue("fraction: float" in all, all)
        assertFalse("fill_max_width" in all, all)
        assertFalse("window_insets" in all, all)
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

    /** The receiver is positional-only; declared parameters are positional-or-keyword by their Kotlin
     * name, and a defaulted one carries `= ...`. */
    @Test
    fun declaredParametersAreKeywordCapableByTheirKotlinNameAndDefaultsAreMarked() {
        val b = body(decl("fillMaxWidth", parameters = listOf(param("fraction", default = true))))
        assertTrue("def fillMaxWidth(receiver: int, /, fraction: float = ...) -> int:" in b, b)
    }

    @Test
    fun aRequiredParameterHasNoDefaultMarker() {
        val b = body(decl("windowInsetsPadding", parameters = listOf(param("windowInsets"))))
        assertTrue("def windowInsetsPadding(receiver: int, /, windowInsets: float) -> int:" in b, b)
    }

    /** `in` is a Python keyword: it cannot be a keyword argument in `.pyi`, so it and everything
     * before it go positional-only, and the unwritable one is spelled `__a<index>`. No renamed
     * spelling (`in_`) is invented. */
    @Test
    fun aParameterNamedLikeAPythonKeywordForcesAPositionalOnlyPrefix() {
        val b = body(
            decl("scan", parameters = listOf(param("first"), param("in"), param("last", default = true))),
        )
        assertTrue("def scan(receiver: int, first: float, __a1: float, /, last: float = ...) -> int:" in b, b)
        assertFalse("in_" in b, b)
    }

    @Test
    fun anUnknownParameterNameListStaysAnonymousAndPositionalOnly() {
        val b = body(
            decl("assertEquals", parameters = listOf(param(null), param(null))).copy(parameterNamesKnown = false),
        )
        assertTrue("def assertEquals(receiver: int, __a0: float, __a1: float, /) -> int:" in b, b)
    }

    /** Python rejects a non-default parameter after a default one, so a Kotlin default that precedes
     * a required parameter cannot be marked; the stub stays valid and the parameter stays named. */
    @Test
    fun aDefaultBeforeARequiredParameterIsNotMarkedSoTheStubStaysSyntacticallyValid() {
        val b = body(decl("mix", parameters = listOf(param("a", default = true), param("b"))))
        assertTrue("def mix(receiver: int, /, a: float, b: float) -> int:" in b, b)
    }
}
