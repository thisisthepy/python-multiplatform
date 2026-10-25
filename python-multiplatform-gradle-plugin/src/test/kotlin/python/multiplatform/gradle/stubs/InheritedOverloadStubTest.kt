package python.multiplatform.gradle.stubs

import python.multiplatform.gradle.model.DeclarationModel
import python.multiplatform.gradle.model.DeclaredParameter
import python.multiplatform.gradle.model.KotlinTypeModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A subclass overriding one overload of an inherited name must still declare every inherited overload,
 * or a type checker reports an incompatible override (CornerBasedShape.copy and RoundedCornerShape.copy).
 */
class InheritedOverloadStubTest {

    private val pkg = "fixture"
    private val base = KotlinTypeModel("$pkg.Base")
    private val sub = KotlinTypeModel("$pkg.Sub")
    private val int = KotlinTypeModel("kotlin.Int")
    private val string = KotlinTypeModel("kotlin.String")

    private fun producing(binding: String, type: KotlinTypeModel, supertypes: List<String>) = DeclarationModel(
        simpleName = binding.substringAfterLast('.'), owner = pkg, ownerIsClass = false, receiver = null,
        parameters = emptyList(), returnType = type, returnBoundaryTag = "OBJECT", bindingName = binding,
        returnSupertypes = supertypes,
    )

    private fun method(owner: KotlinTypeModel, binding: String, param: DeclaredParameter) = DeclarationModel(
        simpleName = binding.substringAfterLast('.').substringBefore("__"), owner = owner.qualifiedName,
        ownerIsClass = true, receiver = owner, receiverBoundaryTag = "OBJECT", parameters = listOf(param),
        returnType = owner, returnBoundaryTag = "OBJECT", bindingName = binding, kind = "METHOD",
    )

    private val intParam = DeclaredParameter("a", int, declaresDefault = false, boundaryTag = "INT")
    private val stringParam = DeclaredParameter("b", string, declaresDefault = false, boundaryTag = "STRING")

    @Test
    fun aSubclassOverridingOneOverloadStillDeclaresTheInheritedOne() {
        val files = renderKotlinFqnStubs(
            listOf(
                producing("$pkg.makeBase", base, emptyList()),
                producing("$pkg.makeSub", sub, listOf("$pkg.Base")),
                method(base, "$pkg.Base.copy__Int", intParam),
                method(base, "$pkg.Base.copy__String", stringParam),
                method(sub, "$pkg.Sub.copy__Int", intParam),
            ),
        )
        val body = files.getValue("fixture/__init__.pyi")
        val subBody = body.substringAfter("class Sub(Base):")
        val baseNameDefs = Regex("@_t\\.overload\\s+def copy\\(([^\\n]*)").findAll(subBody).map { it.groupValues[1] }.toList()
        assertEquals(2, baseNameDefs.size, body)
        assertTrue(baseNameDefs[0].contains("a: ") && baseNameDefs[1].contains("b: "), body)
    }
}
