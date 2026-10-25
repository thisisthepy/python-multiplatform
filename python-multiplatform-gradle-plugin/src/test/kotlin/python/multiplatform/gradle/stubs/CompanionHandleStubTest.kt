package python.multiplatform.gradle.stubs

import python.multiplatform.gradle.model.DeclarationModel
import python.multiplatform.gradle.model.KotlinTypeModel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #322: a companion handle (`Owner.Companion`) is stubbed as a class attribute of the owner's
 * stub class. A module-level getter would make `Owner` a module of its own, and a module takes the
 * name away from the class: the stub then types every `Owner` as `Any`.
 *
 * Red before the change: the handle is a module attribute of `...ui.Modifier`, the class `Modifier`
 * is dropped and `padding(self: Modifier)` is annotated `Any`.
 */
class CompanionHandleStubTest {

    private val pkg = "androidx.compose.ui"
    private val modifier = "$pkg.Modifier"

    private val handle = DeclarationModel(
        simpleName = "Companion",
        owner = modifier,
        ownerIsClass = true,
        receiver = null,
        parameters = emptyList(),
        returnType = KotlinTypeModel(modifier),
        returnBoundaryTag = "OBJECT",
        kind = "STATIC_GETTER",
        bindingName = "$modifier.Companion",
        isCompanionHandle = true,
    )

    private val extension = DeclarationModel(
        simpleName = "tag",
        owner = pkg,
        ownerIsClass = false,
        receiver = KotlinTypeModel(modifier),
        parameters = emptyList(),
        returnType = KotlinTypeModel(modifier),
        returnBoundaryTag = "OBJECT",
        receiverBoundaryTag = "OBJECT",
        kind = "FUNCTION",
        bindingName = "$pkg.tag",
    )

    private val stubs get() = renderKotlinFqnStubs(listOf(handle, extension))

    @Test
    fun theOwnerClassStubKeepsItsNameAndCarriesTheCompanionAttribute() {
        val body = stubs.getValue("androidx/compose/ui/__init__.pyi")
        assertTrue("class Modifier:" in body, body)
        assertTrue("    Companion: Modifier" in body, body)
    }

    @Test
    fun theOwnerIsNotAModuleSoItsClassIsNotDropped() {
        assertFalse(stubs.keys.any { it.startsWith("androidx/compose/ui/Modifier/") }, stubs.keys.toString())
        val body = stubs.getValue("androidx/compose/ui/__init__.pyi")
        assertFalse("receiver: _t.Any" in body, body)
    }
}
