package python.multiplatform.gradle.stubs

import python.multiplatform.gradle.model.DeclarationModel
import python.multiplatform.gradle.model.DeclaredParameter
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

    @Test
    fun classWithCompanionHandleInstanceMembersAndCompanionConstantsIsFoldedIntoClassStub() {
        val m3Pkg = "androidx.compose.material3"
        val designSystem = "$m3Pkg.Material3DesignSystem"
        val designSystemType = KotlinTypeModel(designSystem)
        val colorSchemeType = KotlinTypeModel("$m3Pkg.ColorScheme")

        val companionHandle = DeclarationModel(
            simpleName = "Companion",
            owner = designSystem,
            ownerIsClass = true,
            receiver = null,
            parameters = emptyList(),
            returnType = designSystemType,
            returnBoundaryTag = "OBJECT",
            kind = "STATIC_GETTER",
            bindingName = "$designSystem.Companion",
            isCompanionHandle = true,
        )

        val dark = DeclarationModel(
            simpleName = "Dark",
            owner = designSystem,
            ownerIsClass = true,
            receiver = null,
            parameters = emptyList(),
            returnType = designSystemType,
            returnBoundaryTag = "OBJECT",
            kind = "STATIC_GETTER",
            bindingName = "$designSystem.Dark",
        )

        val light = DeclarationModel(
            simpleName = "Light",
            owner = designSystem,
            ownerIsClass = true,
            receiver = null,
            parameters = emptyList(),
            returnType = designSystemType,
            returnBoundaryTag = "OBJECT",
            kind = "STATIC_GETTER",
            bindingName = "$designSystem.Light",
        )

        val method = DeclarationModel(
            simpleName = "validate",
            owner = designSystem,
            ownerIsClass = true,
            receiver = designSystemType,
            receiverBoundaryTag = "OBJECT",
            parameters = emptyList(),
            returnType = KotlinTypeModel("kotlin.Unit"),
            returnBoundaryTag = "UNIT",
            kind = "METHOD",
            bindingName = "$designSystem.validate",
        )

        val property = DeclarationModel(
            simpleName = "colorScheme",
            owner = designSystem,
            ownerIsClass = true,
            receiver = designSystemType,
            receiverBoundaryTag = "OBJECT",
            parameters = emptyList(),
            returnType = colorSchemeType,
            returnBoundaryTag = "OBJECT",
            kind = "GETTER",
            bindingName = "$designSystem.colorScheme",
        )

        val usage = DeclarationModel(
            simpleName = "useSystem",
            owner = m3Pkg,
            ownerIsClass = false,
            receiver = null,
            parameters = listOf(DeclaredParameter("system", designSystemType, declaresDefault = false, boundaryTag = "OBJECT")),
            returnType = designSystemType,
            returnBoundaryTag = "OBJECT",
            kind = "FUNCTION",
            bindingName = "$m3Pkg.useSystem",
        )

        val files = renderKotlinFqnStubs(listOf(companionHandle, dark, light, method, property, usage))
        assertFalse(files.keys.any { it.startsWith("androidx/compose/material3/Material3DesignSystem") }, files.keys.toString())

        val body = files.getValue("androidx/compose/material3/__init__.pyi")
        assertTrue("class Material3DesignSystem:" in body, body)
        assertTrue("    Dark: Material3DesignSystem" in body, body)
        assertTrue("    Light: Material3DesignSystem" in body, body)
        assertTrue("    def validate(self) -> None:" in body, body)
        assertTrue("    @property\n    def colorScheme(self) -> ColorScheme:" in body, body)
        assertTrue("def useSystem(system: Material3DesignSystem) -> Material3DesignSystem:" in body, body)
        assertFalse("system: _t.Any" in body, body)
        assertFalse("-> _t.Any" in body, body)
    }
}
