package python.multiplatform.gradle.stubs

import python.multiplatform.gradle.model.DeclarationModel
import python.multiplatform.gradle.model.DeclaredParameter
import python.multiplatform.gradle.model.KotlinTypeModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #44: no two emitted stub paths may be equal under case folding. `androidx.compose.ui.graphics`
 * has a class module `Shadow` and a package `shadow`; on macOS and Windows those are one directory.
 *
 * The rule: a class module whose path collides case-insensitively with a sibling (or an ancestor of
 * one) is emitted inside its parent package's `__init__.pyi`, as the class, with its functions as
 * static methods. Modules that do not collide keep their own `<Class>/__init__.pyi`.
 */
class StubCaseLayoutTest {

    private val graphics = "androidx.compose.ui.graphics"
    private val unit = KotlinTypeModel("kotlin.Unit")
    private val float = KotlinTypeModel("kotlin.Float")

    private fun fn(binding: String, vararg params: DeclaredParameter) = DeclarationModel(
        simpleName = binding.substringAfterLast('.'),
        owner = binding.substringBeforeLast('.'),
        ownerIsClass = false,
        receiver = null,
        parameters = params.toList(),
        returnType = unit,
        returnBoundaryTag = "UNIT",
        bindingName = binding,
    )

    private val blur = DeclaredParameter("radius", float, false, "FLOAT")

    /** The Compose shape: a class module `Shadow` and a package `shadow`, plus an unrelated class module. */
    private val declarations = listOf(
        fn("$graphics.Shadow.create", blur),
        fn("$graphics.Shadow.Companion.default"),
        fn("$graphics.shadow.drawShadow", blur),
        fn("$graphics.Color.fromArgb", blur),
        fn("$graphics.draw"),
    )

    private val files get() = renderKotlinFqnStubs(declarations)

    /** Red against the renderer before #44: `Shadow/__init__.pyi` and `shadow/__init__.pyi` are both emitted. */
    @Test
    fun noTwoEmittedPathsAreEqualUnderCasefold() {
        val paths = files.keys
        val clashes = paths.groupBy { it.lowercase() }.values.filter { it.size > 1 }
        assertEquals(emptyList(), clashes, paths.sorted().toString())
        // Directories too: `Shadow/` without an `__init__.pyi` would still collide with `shadow/`.
        val directories = paths.flatMap { path ->
            val parts = path.split('/').dropLast(1)
            parts.indices.map { parts.take(it + 1).joinToString("/") }
        }.toSet()
        assertEquals(emptyList(), directories.groupBy { it.lowercase() }.values.filter { it.size > 1 }, directories.toString())
    }

    @Test
    fun theCollidingClassModuleIsTheClassInItsParentsInit() {
        val parent = files.getValue("androidx/compose/ui/graphics/__init__.pyi")
        assertTrue("class Shadow:" in parent, parent)
        assertTrue("    @staticmethod\n    def create(radius: float) -> None:" in parent, parent)
        // A nested class module goes into the nested class.
        assertTrue("    class Companion:" in parent, parent)
        assertTrue("        @staticmethod\n        def default() -> None:" in parent, parent)
        assertTrue("def draw() -> None:" in parent, parent)
    }

    @Test
    fun thePackageKeepsItsOwnPath() {
        val shadow = files.getValue("androidx/compose/ui/graphics/shadow/__init__.pyi")
        assertTrue("def drawShadow(radius: float) -> None:" in shadow, shadow)
        assertFalse("create" in shadow, shadow)
    }

    @Test
    fun aClassModuleThatDoesNotCollideKeepsItsOwnPath() {
        val color = files.getValue("androidx/compose/ui/graphics/Color/__init__.pyi")
        assertTrue("def fromArgb(radius: float) -> None:" in color, color)
        assertFalse("class Color:" in files.getValue("androidx/compose/ui/graphics/__init__.pyi"))
    }

    @Test
    fun withoutACollisionNothingMoves() {
        val noPackage = renderKotlinFqnStubs(declarations.filter { ".shadow." !in it.bindingName!! })
        assertTrue("androidx/compose/ui/graphics/Shadow/__init__.pyi" in noPackage.keys, noPackage.keys.toString())
    }
}
