package python.multiplatform.gradle.stubs

import python.multiplatform.gradle.model.DeclarationModel
import python.multiplatform.gradle.model.DeclaredParameter
import python.multiplatform.gradle.model.KotlinTypeModel
import python.multiplatform.gradle.model.ValueClassModel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #78: `TextRange(2)` and `TextRange.Zero`.
 *
 * Kotlin has a top-level factory `TextRange(index: Int)` (bound as the overload set `TextRange__Int`,
 * `TextRange__Int_Int`) and a companion constant `TextRange.Zero` (bound under the package
 * `androidx.compose.ui.text.TextRange`). At run time the module `...text.TextRange` is callable. The
 * stub of the parent package has to say both: one attribute `TextRange` whose type has `__call__`
 * (the overload set) and `Zero` -- a bare `def TextRange` hides `TextRange.Zero` from a checker.
 *
 * Red before the fix: the parent stub has `@overload def TextRange(...)` and no `Zero` anywhere in it.
 */
class CompanionFactoryStubTest {

    private val pkg = "androidx.compose.ui.text"
    private val range = KotlinTypeModel(
        "$pkg.TextRange",
        valueClass = ValueClassModel(KotlinTypeModel("kotlin.Long"), constructorIsPublic = false, propertyIsPublic = true),
    )
    private val int = KotlinTypeModel("kotlin.Int")

    private fun factory(binding: String, vararg names: String) = DeclarationModel(
        simpleName = "TextRange",
        owner = pkg,
        ownerIsClass = false,
        receiver = null,
        parameters = names.map { DeclaredParameter(it, int, false, "INT") },
        returnType = range,
        returnBoundaryTag = "OBJECT",
        bindingName = binding,
    )

    private val zero = DeclarationModel(
        simpleName = "Zero",
        owner = "$pkg.TextRange",
        ownerIsClass = true,
        receiver = null,
        parameters = emptyList(),
        returnType = range,
        returnBoundaryTag = "OBJECT",
        bindingName = "$pkg.TextRange.Zero",
        kind = "STATIC_GETTER",
    )

    private val declarations = listOf(
        factory("$pkg.TextRange__Int", "index"),
        factory("$pkg.TextRange__Int_Int", "start", "end"),
        zero,
    )

    private fun parentStub() = renderKotlinFqnStubs(declarations).getValue("androidx/compose/ui/text/__init__.pyi")

    @Test
    fun theFactoryNameIsOneAttributeWhoseTypeIsCallable() {
        val text = parentStub()
        assertTrue(Regex("""^TextRange: _TextRange_callable_module$""", RegexOption.MULTILINE).containsMatchIn(text), text)
        assertTrue("def __call__(self, index: int)" in text, text)
        assertTrue("def __call__(self, start: int, end: int)" in text, text)
        assertTrue("@_t.overload" in text, text)
    }

    @Test
    fun theCompanionConstantIsAMemberOfThatSameType() {
        val text = parentStub()
        val protocol = text.substringAfter("class _TextRange_callable_module").substringBefore("\nTextRange:")
        assertTrue(Regex("""^    Zero: """, RegexOption.MULTILINE).containsMatchIn(protocol), text)
    }

    @Test
    fun noBareDefOfTheBaseNameRemains() {
        val text = parentStub()
        assertFalse(Regex("""^def TextRange\(""", RegexOption.MULTILINE).containsMatchIn(text), text)
        assertFalse(Regex("""^def TextRange:""", RegexOption.MULTILINE).containsMatchIn(text), text)
    }

    @Test
    fun theExplicitTableKeySpellingsStayModuleFunctions() {
        val text = parentStub()
        assertTrue(Regex("""^def TextRange__Int\(index: int\)""", RegexOption.MULTILINE).containsMatchIn(text), text)
        assertTrue(Regex("""^def TextRange__Int_Int\(""", RegexOption.MULTILINE).containsMatchIn(text), text)
    }

    @Test
    fun theModulesOwnStubStillExistsForAnExplicitImport() {
        val files = renderKotlinFqnStubs(declarations)
        assertTrue("androidx/compose/ui/text/TextRange/__init__.pyi" in files, files.keys.toString())
    }

    @Test
    fun aFunctionWithNoModuleOfItsNameStaysAPlainOverloadSet() {
        val plain = renderKotlinFqnStubs(declarations.filter { it.kind != "STATIC_GETTER" })
            .getValue("androidx/compose/ui/text/__init__.pyi")
        assertTrue(Regex("""^@_t\.overload\ndef TextRange\(""", RegexOption.MULTILINE).containsMatchIn(plain), plain)
        assertFalse("_callable_module" in plain, plain)
    }
}
