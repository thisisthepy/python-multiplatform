package python.multiplatform.gradle.stubs

import python.multiplatform.gradle.model.DeclarationModel
import python.multiplatform.gradle.model.DeclaredParameter
import python.multiplatform.gradle.model.KotlinTypeModel
import python.multiplatform.gradle.model.ValueClassModel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #53: what a Kotlin `object`'s stub says.
 *
 * An object is a Python module (`androidx.compose.ui.Alignment`) whose attributes are its constants
 * and functions. Its nested types (`Alignment.Horizontal`) are **also** named under that module, and
 * the renderer used to refuse them: `androidx.compose.ui.Alignment` is a module, a type cannot be
 * both, so every constant's type fell back to `Any`. The nested types belong in the object's own
 * module -- `Alignment.Horizontal` is `androidx/compose/ui/Alignment/__init__.pyi`'s `Horizontal`.
 */
class ObjectStubTest {

    private val pkgUi = "androidx.compose.ui"
    private val pkgLayout = "androidx.compose.foundation.layout"
    private val alignment = "$pkgUi.Alignment"
    private val arrangement = "$pkgLayout.Arrangement"

    private val alignmentHorizontal = KotlinTypeModel("$alignment.Horizontal")
    private val arrangementBoth = KotlinTypeModel("$arrangement.HorizontalOrVertical")
    private val float = KotlinTypeModel("kotlin.Float")
    private val dp = KotlinTypeModel(
        "androidx.compose.ui.unit.Dp",
        valueClass = ValueClassModel(float, constructorIsPublic = true, propertyIsPublic = true),
    )

    private fun constant(binding: String, type: KotlinTypeModel) = DeclarationModel(
        simpleName = binding.substringAfterLast('.'),
        owner = binding.substringBeforeLast('.'),
        ownerIsClass = true,
        receiver = null,
        parameters = emptyList(),
        returnType = type,
        returnBoundaryTag = "OBJECT",
        bindingName = binding,
        kind = "STATIC_GETTER",
    )

    private fun function(binding: String, type: KotlinTypeModel, vararg params: DeclaredParameter) = DeclarationModel(
        simpleName = binding.substringAfterLast('.').substringBefore("__"),
        owner = binding.substringBeforeLast('.'),
        ownerIsClass = true,
        receiver = null,
        parameters = params.toList(),
        returnType = type,
        returnBoundaryTag = "OBJECT",
        bindingName = binding,
    )

    private val end = constant("$alignment.End", alignmentHorizontal)

    private fun file(files: Map<String, String>, module: String) =
        files.getValue(module.replace('.', '/') + "/__init__.pyi")

    // ---------------------------------------------------- constants: the declared type, not Any

    @Test
    fun anObjectConstantIsAnnotatedWithItsDeclaredNestedType() {
        val text = file(renderKotlinFqnStubs(listOf(end)), alignment)
        assertTrue(Regex("""^End: Horizontal$""", RegexOption.MULTILINE).containsMatchIn(text), text)
        assertFalse("End: _t.Any" in text, text)
    }

    @Test
    fun theNestedTypeIsAClassInTheObjectsOwnModule() {
        val text = file(renderKotlinFqnStubs(listOf(end)), alignment)
        assertTrue("class Horizontal:" in text, text)
        assertTrue("Kotlin: $alignment.Horizontal" in text, text)
    }

    @Test
    fun anotherModuleSpellsTheNestedTypeThroughTheObjectsModule() {
        val column = function(
            "$pkgLayout.Column",
            KotlinTypeModel("kotlin.Unit"),
            DeclaredParameter("horizontalAlignment", alignmentHorizontal, true, "OBJECT"),
        )
        val files = renderKotlinFqnStubs(listOf(end, column))
        val layout = file(files, pkgLayout)
        assertTrue("horizontalAlignment: $alignment.Horizontal = ..." in layout, layout)
        assertTrue("import $alignment\n" in layout, layout)
    }

    @Test
    fun theObjectItselfStaysAnyBecauseItsNameIsTheModule() {
        // `Alignment.Center: Alignment` -- the type is the module's own name; no class can stand there.
        val center = constant("$alignment.Center", KotlinTypeModel(alignment))
        val text = file(renderKotlinFqnStubs(listOf(center)), alignment)
        assertTrue("Center: _t.Any" in text, text)
    }

    @Test
    fun aNestedTypeWhoseNameAnObjectMemberTakesStaysAny() {
        // The module already defines `Horizontal` as a constant; a class of that name would shadow it.
        val clash = constant("$alignment.Horizontal", alignmentHorizontal)
        val text = file(renderKotlinFqnStubs(listOf(clash)), alignment)
        assertFalse("class Horizontal:" in text, text)
        assertTrue("Horizontal: _t.Any" in text, text)
    }

    // ---------------------------------------------------------------- functions of an object

    @Test
    fun anObjectFunctionAndItsOverloadsAreInTheObjectsStub() {
        val spacedByDp = function(
            "$arrangement.spacedBy__Dp", arrangementBoth,
            DeclaredParameter("space", dp, false, "FLOAT"),
        )
        val spacedByAligned = function(
            "$arrangement.spacedBy__Dp_Horizontal", KotlinTypeModel("$arrangement.Horizontal"),
            DeclaredParameter("space", dp, false, "FLOAT"),
            DeclaredParameter("alignment", alignmentHorizontal, false, "OBJECT"),
        )
        val start = constant("$arrangement.Start", KotlinTypeModel("$arrangement.Horizontal"))
        val text = file(renderKotlinFqnStubs(listOf(spacedByDp, spacedByAligned, start)), arrangement)
        assertTrue("def spacedBy__Dp(space: androidx.compose.ui.unit.Dp | float) -> HorizontalOrVertical:" in text, text)
        assertTrue("def spacedBy__Dp_Horizontal(" in text, text)
        // The Kotlin name reaches both, as `@overload`s in table-key order.
        assertTrue("@_t.overload\ndef spacedBy(space: androidx.compose.ui.unit.Dp | float) -> HorizontalOrVertical: ..." in text, text)
        assertTrue("Start: Horizontal" in text, text)
        assertFalse("_t.Any" in text, text)
    }

    // ------------------------------------------- an extension property on a type nested in an object

    /**
     * #68: `Icons.Default.Add`. `Default` is an object constant of `Icons` typed `Icons.Filled`, and `Add`
     * is an extension property declared in the package `...icons.filled` on that nested type. The stub
     * class is `Filled` in the object's own module `...icons.Icons` (#53), and `Add` is its property.
     *
     * Not red against develop at fa2558e3: #53 already resolves the receiver into the object's module,
     * and the regenerated Compose stubs (CI run 37098191598) carry `Default: Filled` and
     * `Filled.Add`. The marker comment of #68 came from the stubs built before #53. This pins it.
     */
    @Test
    fun anExtensionPropertyOnATypeNestedInAnObjectIsAttributeOfThatTypesClass() {
        val icons = "androidx.compose.material.icons.Icons"
        val filled = KotlinTypeModel("$icons.Filled")
        val default = constant("$icons.Default", filled)
        val add = DeclarationModel(
            simpleName = "Add",
            owner = "androidx.compose.material.icons.filled",
            ownerIsClass = false,
            receiver = filled,
            receiverBoundaryTag = "OBJECT",
            parameters = emptyList(),
            returnType = KotlinTypeModel("androidx.compose.ui.graphics.vector.ImageVector"),
            returnBoundaryTag = "OBJECT",
            bindingName = "androidx.compose.material.icons.filled.Add",
            kind = "GETTER",
        )
        val files = renderKotlinFqnStubs(listOf(default, add))
        val text = file(files, icons)
        assertTrue(Regex("""^Default: Filled$""", RegexOption.MULTILINE).containsMatchIn(text), text)
        assertTrue("class Filled:" in text, text)
        assertTrue("    def Add(self) -> androidx.compose.ui.graphics.vector.ImageVector:" in text, text)
        files.values.forEach { assertFalse("is not stubbed" in it, it) }
    }
}
