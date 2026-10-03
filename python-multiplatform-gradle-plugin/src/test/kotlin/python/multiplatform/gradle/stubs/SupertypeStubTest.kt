package python.multiplatform.gradle.stubs

import python.multiplatform.gradle.model.DeclarationModel
import python.multiplatform.gradle.model.KotlinTypeModel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #71: a stub class lists every Kotlin supertype that has a stub class, in a valid MRO.
 * `Arrangement.HorizontalOrVertical` is both a `Horizontal` and a `Vertical`; listing only the first
 * made mypy reject it in a `Vertical` slot.
 */
class SupertypeStubTest {

    private val pkg = "androidx.compose.foundation.layout"
    private val arrangement = "$pkg.Arrangement"

    private fun producing(binding: String, type: String, supertypes: List<String>) = DeclarationModel(
        simpleName = binding.substringAfterLast('.'),
        owner = binding.substringBeforeLast('.'),
        ownerIsClass = true,
        receiver = null,
        parameters = emptyList(),
        returnType = KotlinTypeModel(type),
        returnBoundaryTag = "OBJECT",
        bindingName = binding,
        returnSupertypes = supertypes,
    )

    private fun text(vararg d: DeclarationModel, module: String = arrangement) =
        renderKotlinFqnStubs(d.toList()).getValue(module.replace('.', '/') + "/__init__.pyi")

    @Test
    fun aClassListsEveryKotlinSupertypeThatHasAStubClass() {
        val both = producing(
            "$arrangement.SpaceBetween", "$arrangement.HorizontalOrVertical",
            listOf("$arrangement.Horizontal", "$arrangement.Vertical", "kotlin.Any", "java.lang.Object"),
        )
        val body = text(both)
        assertTrue("class HorizontalOrVertical(Horizontal, Vertical):" in body, body)
        assertTrue("class Horizontal:" in body, body)
        assertTrue("class Vertical:" in body, body)
        assertFalse("object" in body.substringAfter("class HorizontalOrVertical").substringBefore(":"), body)
    }

    @Test
    fun aBaseAnotherListedBaseAlreadyImpliesIsDroppedSoTheMroIsValid() {
        // Sub is a Base and a Mid, and Mid is a Base: `class Sub(Base, Mid)` is an MRO error in Python.
        val sub = producing("$arrangement.makeSub", "$arrangement.Sub", listOf("$arrangement.Base", "$arrangement.Mid"))
        val mid = producing("$arrangement.makeMid", "$arrangement.Mid", listOf("$arrangement.Base"))
        val body = text(sub, mid)
        assertTrue("class Sub(Mid):" in body, body)
        assertTrue("class Mid(Base):" in body, body)
        assertFalse("class Sub(Base" in body, body)
    }
}
