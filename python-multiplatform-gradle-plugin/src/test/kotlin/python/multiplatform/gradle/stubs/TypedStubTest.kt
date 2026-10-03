package python.multiplatform.gradle.stubs

import python.multiplatform.gradle.model.DeclarationModel
import python.multiplatform.gradle.model.DeclaredParameter
import python.multiplatform.gradle.model.KotlinTypeModel
import python.multiplatform.gradle.model.ValueClassModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #31: the stub names the Kotlin type a declaration says, instead of the `int` handle the
 * boundary carries.
 *
 * What is pinned here, all over generated text:
 *
 * - one stub class per bound Kotlin type, in the module of its own Kotlin package, and used in every
 *   annotation (`Modifier`, not `int`);
 * - an extension function is also reachable from its receiver's stub class, under both the Kotlin
 *   name and the overload-suffixed table key, the way the runtime attaches it
 *   (`PythonxAdapter._attach`);
 * - a value class over a primitive is `Dp | float` as a parameter and the raw primitive as a
 *   result, because that is what crosses; a value class the binder cannot open is a plain class;
 * - a Kotlin function type is `Callable[...]`, a nullable type `| None`;
 * - an overload set is `@overload`ed under its base name **in table-key order**, which is the order
 *   the binder's `_Overloads` tries candidates in (`ArtifactScanner.scanJar` sorts by table key);
 * - a required parameter after a defaulted one is keyword-only, as `inspect.signature` says
 *   (`KotlinSurface.kt`).
 */
class TypedStubTest {

    private val pkgUi = "androidx.compose.ui"
    private val pkgLayout = "androidx.compose.foundation.layout"
    private val pkgUnit = "androidx.compose.ui.unit"

    private val modifier = KotlinTypeModel("$pkgUi.Modifier")
    private val float = KotlinTypeModel("kotlin.Float")
    private val dp = KotlinTypeModel(
        "$pkgUnit.Dp",
        valueClass = ValueClassModel(float, constructorIsPublic = true, propertyIsPublic = true),
    )
    private val textUnit = KotlinTypeModel(
        "$pkgUnit.TextUnit",
        valueClass = ValueClassModel(KotlinTypeModel("kotlin.Long"), constructorIsPublic = false, propertyIsPublic = false),
    )
    private val paddingValues = KotlinTypeModel("$pkgLayout.PaddingValues")
    private val unit = KotlinTypeModel("kotlin.Unit")

    private fun p(name: String, type: KotlinTypeModel, tag: String, default: Boolean = false) =
        DeclaredParameter(name, type, default, tag)

    private fun ext(
        name: String,
        binding: String = "$pkgLayout.$name",
        owner: String = pkgLayout,
        vararg params: DeclaredParameter,
    ) = DeclarationModel(
        simpleName = name,
        owner = owner,
        ownerIsClass = false,
        receiver = modifier,
        receiverBoundaryTag = "OBJECT",
        parameters = params.toList(),
        returnType = modifier,
        returnBoundaryTag = "OBJECT",
        bindingName = binding,
    )

    private val padding = listOf(
        ext("padding", "$pkgLayout.padding__Dp_Dp_Dp_Dp",
            params = arrayOf(p("start", dp, "FLOAT", true), p("top", dp, "FLOAT", true), p("end", dp, "FLOAT", true), p("bottom", dp, "FLOAT", true))),
        ext("padding", "$pkgLayout.padding__Dp", params = arrayOf(p("all", dp, "FLOAT"))),
        ext("padding", "$pkgLayout.padding__PaddingValues", params = arrayOf(p("paddingValues", paddingValues, "OBJECT"))),
        ext("padding", "$pkgLayout.padding__Dp_Dp", params = arrayOf(p("horizontal", dp, "FLOAT", true), p("vertical", dp, "FLOAT", true))),
    )

    private val fillMaxWidth = ext("fillMaxWidth", params = arrayOf(p("fraction", float, "FLOAT", true)))

    private fun files(vararg d: DeclarationModel) = renderKotlinFqnStubs(d.toList())
    private fun file(files: Map<String, String>, module: String) =
        files.getValue(module.replace('.', '/') + "/__init__.pyi")

    // ------------------------------------------------------------------ classes in annotations

    @Test
    fun receiverAndReturnAreTheKotlinClassNotAnInt() {
        val layout = file(files(fillMaxWidth), pkgLayout)
        assertTrue(
            "def fillMaxWidth(receiver: androidx.compose.ui.Modifier, /, fraction: float = ...) -> androidx.compose.ui.Modifier:" in layout,
            layout,
        )
        assertFalse(": int" in layout || "-> int" in layout, layout)
        assertTrue("import androidx.compose.ui\n" in layout, layout)
    }

    @Test
    fun theClassIsStubbedInTheModuleOfItsOwnPackage() {
        val files = files(fillMaxWidth)
        val ui = file(files, pkgUi)
        assertTrue("class Modifier:" in ui, ui)
        assertTrue("\"\"\"Kotlin: androidx.compose.ui.Modifier\"\"\"" in ui, ui)
        // A class is never stubbed in the module of the function that merely mentions it.
        assertFalse("class Modifier" in file(files, pkgLayout))
    }

    @Test
    fun aTypeFromTheSameModuleIsReferencedBare() {
        val ui = file(files(fillMaxWidth), pkgUi)
        // The extension is attached to its receiver's class, which lives in `androidx.compose.ui`.
        assertTrue("-> Modifier" in ui, ui)
        assertFalse("androidx.compose.ui.Modifier" in ui.substringAfter("class _Modifier_fillMaxWidth").substringBefore("class Modifier:"), ui)
    }

    // ------------------------------------------------------- extension functions as methods

    @Test
    fun anExtensionIsAlsoAnAttributeOfItsReceiversClass() {
        val ui = file(files(fillMaxWidth), pkgUi)
        assertTrue("class _Modifier_fillMaxWidth(_t.Protocol):" in ui, ui)
        assertTrue("def __call__(self, fraction: float = ...) -> Modifier: ..." in ui, ui)
        assertTrue("    fillMaxWidth: _t.ClassVar[_Modifier_fillMaxWidth]" in ui, ui)
        // Callable both as `Modifier.fillMaxWidth(...)` and `m.fillMaxWidth(...)`: the attribute is a
        // callable object, not a function that would bind `self`.
        assertFalse("def fillMaxWidth(self" in ui, ui)
    }

    @Test
    fun theTableKeySpellingIsAlsoAnAttribute() {
        val ui = file(files(*padding.toTypedArray()), pkgUi)
        assertTrue("padding__Dp: _t.ClassVar[_Modifier_padding__Dp]" in ui, ui)
        assertTrue("padding__PaddingValues: _t.ClassVar[_Modifier_padding__PaddingValues]" in ui, ui)
        assertTrue("padding: _t.ClassVar[_Modifier_padding]" in ui, ui)
    }

    // ----------------------------------------------------------------------------- overloads

    /** The binder tries candidates in table-key order (the artefact table is sorted by name), and a
     * checker takes the first match, so the stub's order has to be the same one. */
    @Test
    fun anOverloadSetIsOverloadedUnderItsBaseNameInTableKeyOrder() {
        val files = files(*padding.toTypedArray())
        val layout = file(files, pkgLayout)
        val order = Regex("""@_t\.overload\ndef padding\((.*)\) -> """).findAll(layout).map { it.groupValues[1] }.toList()
        assertEquals(4, order.size, layout)
        assertTrue(order[0].contains("all: androidx.compose.ui.unit.Dp | float"), order.toString())
        assertTrue(order[1].contains("horizontal"), order.toString())
        assertTrue(order[2].contains("start"), order.toString())
        // Issue #131: the parameter is written under the keyword `inspect.signature` shows.
        assertTrue(order[3].contains("padding_values"), order.toString())
        // ... and the explicit table-key spellings are still there, one def each.
        assertTrue("def padding__Dp(receiver: androidx.compose.ui.Modifier, /, all:" in layout, layout)
        assertTrue("def padding__PaddingValues(" in layout, layout)

        val ui = file(files, pkgUi)
        val methodOrder = Regex("""class _Modifier_padding\(_t\.Protocol\):\n((?:    .*\n)+)""").find(ui)!!.groupValues[1]
        val calls = Regex("""def __call__\(self, (\w+)""").findAll(methodOrder).map { it.groupValues[1] }.toList()
        assertEquals(listOf("all", "horizontal", "start", "padding_values"), calls, ui)
    }

    /** Python's `int`/`float` cannot tell two Kotlin overloads apart, so a checker calls every later
     * one unreachable; the diagnostic is silenced on those, and never on the first. */
    @Test
    fun everyOverloadAfterTheFirstSilencesTheCheckersNeverMatchedDiagnostic() {
        val layout = file(files(*padding.toTypedArray()), pkgLayout)
        val defs = layout.lines().filter { it.startsWith("def padding(") }
        assertEquals(4, defs.size, layout)
        assertFalse("type: ignore" in defs[0], defs[0])
        assertTrue(defs.drop(1).all { it.endsWith("  # type: ignore[overload-cannot-match]") }, defs.toString())
        val ui = file(files(*padding.toTypedArray()), pkgUi)
        val calls = ui.substringAfter("class _Modifier_padding(").substringBefore("class _Modifier_padding__Dp").lines().filter { "def __call__" in it }
        assertEquals(4, calls.size, ui)
        assertFalse("type: ignore" in calls[0], calls[0])
        assertTrue(calls.drop(1).all { "type: ignore[overload-cannot-match]" in it }, calls.toString())
    }

    /** `androidx.compose.ui.graphics.Shadow` (a class module) and `...graphics.shadow` (a package) are
     * two paths on a case-sensitive filesystem and one on macOS or Windows, where the later write
     * silently replaces the earlier. */
    @Test
    fun pathsThatDifferOnlyByCaseAreReported() {
        val paths = listOf(
            "androidx/compose/ui/graphics/Shadow/__init__.pyi",
            "androidx/compose/ui/graphics/shadow/__init__.pyi",
            "androidx/compose/ui/graphics/__init__.pyi",
        )
        assertEquals(
            listOf(listOf("androidx/compose/ui/graphics/Shadow/__init__.pyi", "androidx/compose/ui/graphics/shadow/__init__.pyi")),
            caseCollidingPaths(paths),
        )
        assertEquals(emptyList(), caseCollidingPaths(paths.drop(1).take(1) + paths.drop(2)))
    }

    @Test
    fun aSingleDeclarationUnderAnOverloadSuffixStillGetsItsBaseName() {
        val only = listOf(padding[1])
        val layout = file(files(*only.toTypedArray()), pkgLayout)
        assertTrue("def padding(receiver: androidx.compose.ui.Modifier, /, all:" in layout, layout)
        assertFalse("@_t.overload" in layout, "one declaration is not an overload set: $layout")
    }

    // ----------------------------------------------------------------------------- value classes

    @Test
    fun aValueClassParameterIsTheClassOrItsRawPrimitiveAndItsResultIsTheRawPrimitive() {
        val takes = ext("padding", "$pkgLayout.padding__Dp", params = arrayOf(p("all", dp, "FLOAT")))
        val returns = DeclarationModel(
            simpleName = "defaultPadding", owner = pkgLayout, ownerIsClass = false, receiver = null,
            parameters = emptyList(), returnType = dp, returnBoundaryTag = "FLOAT", bindingName = "$pkgLayout.defaultPadding",
        )
        val files = files(takes, returns)
        val layout = file(files, pkgLayout)
        assertTrue("all: androidx.compose.ui.unit.Dp | float) ->" in layout, layout)
        assertTrue("def defaultPadding() -> float:" in layout, layout)
        val units = file(files, pkgUnit)
        assertTrue("class Dp:" in units, units)
        assertTrue("value class" in units && "kotlin.Float" in units, units)
    }

    @Test
    fun aValueClassTheBinderCannotOpenIsAnOrdinaryClass() {
        val takes = DeclarationModel(
            simpleName = "fontSize", owner = pkgLayout, ownerIsClass = false, receiver = null,
            parameters = listOf(p("size", textUnit, "OBJECT")), returnType = unit, returnBoundaryTag = "UNIT",
            bindingName = "$pkgLayout.fontSize",
        )
        val layout = file(files(takes), pkgLayout)
        assertTrue("def fontSize(size: androidx.compose.ui.unit.TextUnit) -> None:" in layout, layout)
    }

    // ------------------------------------------------------------------ function types, nullable

    @Test
    fun aFunctionTypeIsCallableAndANullableTypeIsOptional() {
        val function1 = KotlinTypeModel("kotlin.Function1", arguments = listOf(KotlinTypeModel("kotlin.Boolean"), unit))
        val function0 = KotlinTypeModel("kotlin.Function0", arguments = listOf(unit))
        val checkbox = DeclarationModel(
            simpleName = "Checkbox", owner = "androidx.compose.material3", ownerIsClass = false, receiver = null,
            parameters = listOf(
                p("checked", KotlinTypeModel("kotlin.Boolean"), "BOOLEAN"),
                p("onCheckedChange", function1.copy(isNullable = true), "OBJECT"),
                p("modifier", modifier, "OBJECT", default = true),
                p("content", function0, "OBJECT"),
            ),
            returnType = unit, returnBoundaryTag = "UNIT", bindingName = "androidx.compose.material3.Checkbox",
        )
        val body = file(files(checkbox), "androidx.compose.material3")
        assertTrue(
            "def Checkbox(checked: bool, on_checked_change: _t.Callable[[bool], None] | None, " +
                "modifier: androidx.compose.ui.Modifier = ..., *, content: _t.Callable[[], None]) -> None:" in body,
            body,
        )
    }

    // ------------------------------------------------------------------------------ inheritance

    @Test
    fun aClassExtendsItsNearestSupertypeWhenTheScannerKnowsOne() {
        val painter = KotlinTypeModel("androidx.compose.ui.graphics.painter.Painter")
        val bitmapPainter = KotlinTypeModel("androidx.compose.ui.graphics.painter.BitmapPainter")
        val make = DeclarationModel(
            simpleName = "loadPainter", owner = "androidx.compose.ui.graphics.painter", ownerIsClass = false, receiver = null,
            parameters = emptyList(), returnType = bitmapPainter, returnBoundaryTag = "OBJECT",
            bindingName = "androidx.compose.ui.graphics.painter.loadPainter",
            returnSupertypes = listOf(painter.qualifiedName),
        )
        val body = file(files(make), "androidx.compose.ui.graphics.painter")
        assertTrue("class BitmapPainter(Painter):" in body, body)
        assertTrue("class Painter:" in body, body)
    }

    // ---------------------------------------------------------------------- name collisions

    /** A Kotlin factory function and the class it makes share a name (`PaddingValues(...)`). One
     * Python module cannot define both, and the runtime resolves that name to the function. */
    @Test
    fun aClassWhoseNameIsAFunctionInItsModuleFallsBackToAny() {
        val factory = DeclarationModel(
            simpleName = "PaddingValues", owner = pkgLayout, ownerIsClass = false, receiver = null,
            parameters = listOf(p("all", dp, "FLOAT")), returnType = paddingValues, returnBoundaryTag = "OBJECT",
            bindingName = "$pkgLayout.PaddingValues",
        )
        val layout = file(files(factory), pkgLayout)
        assertTrue("def PaddingValues(all: androidx.compose.ui.unit.Dp | float) -> _t.Any:" in layout, layout)
        assertFalse("class PaddingValues" in layout, layout)
    }

    // ------------------------------------------------------------------------ Kotlin names only

    /**
     * Issue #131: the Kotlin names stay and the Pythonic aliases are added beside them -- as
     * `alias = kotlinName` at module level and as a second `ClassVar` of the same Protocol on the
     * receiver -- and nothing is emitted under `pythonx`.
     */
    @Test
    fun theTypedStubsCarryTheKotlinNamesAndThePythonicAliasesAndNothingUnderPythonx() {
        val all = files(*padding.toTypedArray(), fillMaxWidth)
        assertEquals(emptyList(), all.keys.filter { it.startsWith("pythonx/") })
        val text = all.keys.joinToString("\n") + all.values.joinToString("\n")
        assertFalse("pythonx" in text, text)
        val layout = file(all, pkgLayout)
        assertTrue(Regex("^def fillMaxWidth\\(", RegexOption.MULTILINE).containsMatchIn(layout), layout)
        assertTrue(Regex("^fill_max_width = fillMaxWidth${'$'}", RegexOption.MULTILINE).containsMatchIn(layout), layout)
        val ui = file(all, pkgUi)
        assertTrue("    fillMaxWidth: _t.ClassVar[_Modifier_fillMaxWidth]" in ui, ui)
        assertTrue("    fill_max_width: _t.ClassVar[_Modifier_fillMaxWidth]" in ui, ui)
        // `padding` has no other spelling: nothing is invented for it.
        assertFalse(Regex("^padding_\\w* = ", RegexOption.MULTILINE).containsMatchIn(layout), layout)
    }
}
