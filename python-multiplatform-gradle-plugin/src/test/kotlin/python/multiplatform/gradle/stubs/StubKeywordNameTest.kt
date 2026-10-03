package python.multiplatform.gradle.stubs

import python.multiplatform.gradle.model.DeclarationModel
import python.multiplatform.gradle.model.KotlinTypeModel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A Kotlin declaration may be named like a Python keyword: Compose's `Shadow.None`. Written as
 * `None: Shadow` it makes the whole `.pyi` a syntax error, and mypy then checks nothing in that
 * module. Such a name cannot be written as an attribute in Python at all (`Shadow.None` does not
 * parse), so the stub leaves it out and says how to reach it.
 */
class StubKeywordNameTest {

    private val graphics = "androidx.compose.ui.graphics"
    private val shadow = KotlinTypeModel("$graphics.Shadow")

    private fun decl(binding: String, kind: String) = DeclarationModel(
        simpleName = binding.substringAfterLast('.'),
        owner = binding.substringBeforeLast('.'),
        ownerIsClass = false,
        receiver = null,
        parameters = emptyList(),
        returnType = shadow,
        returnBoundaryTag = "OBJECT",
        bindingName = binding,
        kind = kind,
    )

    private val keywordLine = Regex("""^\s*(None|True|False|class|def|from|import|lambda|pass)\s*[:(]""", RegexOption.MULTILINE)

    @Test
    fun aConstantNamedLikeAPythonKeywordIsNotWrittenAsAnAttribute() {
        val files = renderKotlinFqnStubs(listOf(decl("$graphics.Shadow.None", "STATIC_GETTER")))
        val text = files.values.joinToString("\n")
        assertFalse(keywordLine.containsMatchIn(text), text)
        assertTrue("getattr" in text && "None" in text, "the stub says how to reach it:\n$text")
    }

    @Test
    fun aFunctionNamedLikeAPythonKeywordIsNotWrittenAsADef() {
        val files = renderKotlinFqnStubs(listOf(decl("$graphics.Shadow.from", "FUNCTION")))
        val text = files.values.joinToString("\n")
        assertFalse(Regex("""^\s*def from\(""", RegexOption.MULTILINE).containsMatchIn(text), text)
    }
}
