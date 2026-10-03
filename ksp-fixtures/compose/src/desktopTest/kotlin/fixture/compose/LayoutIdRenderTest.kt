package fixture.compose

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Bitmap
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonxAdapter
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.FunctionTable
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **A real string value, tagged from Python, reaches `Measurable.layoutId`** -- `layoutId` declines
 * for a reason unrelated to suspend (`docs/archive/pythonx-adapter-design.md` §9's table: its one non-`Modifier`
 * parameter is `kotlin.Any`, which `resolveKotlinType` has no `TypeTag` for). `fixture.compose
 * .pythonLayoutIdString` (`PythonLayoutId.kt`) fixes the parameter's tag the same way every other
 * concretely-typed hand-written wrapper in this module does.
 *
 * ### Why the probe is a `Layout` built in this test, not a Python-callable composable
 *
 * The boundary carries no reader for [androidx.compose.ui.layout.Measurable.layoutId] itself, so
 * something has to *act* on it to make it observable. `PythonLayoutId.kt`'s KDoc records why that
 * something cannot be a hand-written `@Composable` Python calls (`BindingPolicy.isComposable` excludes
 * every `@Composable` top-level function from KSP's plain-function path, confirmed by a first attempt
 * failing to bind at all: `cannot import name 'pythonLayoutIdProbe'`). It can be a `Layout` in *this*
 * file instead, wrapping [PythonComposition] the same way `ImageComposeScene`'s own content lambda
 * already does in every other render test here -- `PythonComposition`'s Python body composes exactly
 * one tagged `Text`, so that `Layout`'s single measurable carries the `layoutId` Python's call to
 * `pythonLayoutIdString` produced.
 *
 * It places that one child at x=0 if its `layoutId` equals [expectTag] (an argument *of this Kotlin
 * test*, not visible to Python) or at x=60 -- outside a 20dp child's reach at x=0..20 -- if not. A stub
 * that always tagged with a fixed string, or dropped the string Python actually sent, would place the
 * ink at the wrong x for at least one of the two cases below.
 */
class LayoutIdRenderTest {

    @BeforeTest
    fun installBothProducers() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        UpcallTable.install(FunctionTable.fragments + ArtifactTable.fragments)
        check(UpcallBootstrap.publishToGlobals()) { "UpcallBootstrap.publishToGlobals() failed" }
        PythonProxySource.install()
        PythonxAdapter.install(PYTHONX_RAW_VALUE_CLASSES)
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    /** The positive claim: tagging with `"python-tag"` from Python and probing for the same string
     * places the ink at the left (x in [0,20)). */
    @Test
    fun aMatchingLayoutIdPlacesTheChildAtTheLeft() {
        val pixels = pixelsOf(taggedBody("python-tag"), expectTag = "python-tag")
        assertTrue(inkAt(pixels, 0, 20), "no ink in the left region -- the matching tag was not placed there")
        assertTrue(!inkAt(pixels, 60, 80), "ink in the right region even though the tag matched")
    }

    /** The negative control: tagging with one string and probing for a **different** one places the
     * ink at the right (x in [60,80)) instead -- ruling out a stub that always reports a match, or one
     * that silently drops the string Python actually sent. */
    @Test
    fun aMismatchedLayoutIdPlacesTheChildAtTheRight() {
        val pixels = pixelsOf(taggedBody("python-tag"), expectTag = "a-different-tag")
        assertTrue(inkAt(pixels, 60, 80), "no ink in the right region -- a mismatched tag was not detected")
        assertTrue(!inkAt(pixels, 0, 20), "ink in the left region even though the tag did not match")
    }

    private fun taggedBody(tag: String) = """
        from fixture.compose import emptyModifier, pythonLayoutIdString
        from androidx.compose.foundation.layout import size__Dp
        from androidx.compose.material3 import Text

        _m = pythonLayoutIdString(size__Dp(emptyModifier(), 20.0), "$tag")
        Text("X", modifier=_m)
    """.trimIndent()

    /**
     * [expectTag] is read by *this* Kotlin measure policy, never crossed to Python -- it is what
     * makes the mismatch case in [aMismatchedLayoutIdPlacesTheChildAtTheRight] a control rather than
     * a second copy of the same claim.
     */
    private fun pixelsOf(body: String, expectTag: String): IntArray {
        val scene = ImageComposeScene(width = SCENE, height = SCENE, density = Density(1f)) {
            Layout(content = { PythonComposition(body) }) { measurables, constraints ->
                val measurable = measurables.first()
                val placeable = measurable.measure(constraints)
                val x = if (measurable.layoutId == expectTag) 0 else 60
                layout(SCENE, SCENE) {
                    placeable.placeRelative(x, 0)
                }
            }
        }
        try {
            val bitmap = Bitmap.makeFromImage(scene.render())
            return IntArray(SCENE * SCENE) { bitmap.getColor(it % SCENE, it / SCENE) }
        } finally {
            scene.close()
        }
    }

    /** The positive claim: tagging with an integer from Python and probing for the same int
     * places the ink at the left (x in [0,20)). */
    @Test
    fun aMatchingLayoutIdIntPlacesTheChildAtTheLeft() {
        val pixels = pixelsOfInt(taggedBodyInt(42), expectTag = 42)
        assertTrue(inkAt(pixels, 0, 20), "no ink in the left region -- the matching tag was not placed there")
        assertTrue(!inkAt(pixels, 60, 80), "ink in the right region even though the tag matched")
    }

    /** The negative control for int tag. */
    @Test
    fun aMismatchedLayoutIdIntPlacesTheChildAtTheRight() {
        val pixels = pixelsOfInt(taggedBodyInt(42), expectTag = 99)
        assertTrue(inkAt(pixels, 60, 80), "no ink in the right region -- a mismatched tag was not detected")
        assertTrue(!inkAt(pixels, 0, 20), "ink in the left region even though the tag did not match")
    }

    private fun taggedBodyInt(tag: Int) = """
        from fixture.compose import emptyModifier, pythonLayoutIdInt
        from androidx.compose.foundation.layout import size__Dp
        from androidx.compose.material3 import Text

        _m = pythonLayoutIdInt(size__Dp(emptyModifier(), 20.0), $tag)
        Text("X", modifier=_m)
    """.trimIndent()

    private fun pixelsOfInt(body: String, expectTag: Int): IntArray {
        val scene = ImageComposeScene(width = SCENE, height = SCENE, density = Density(1f)) {
            Layout(content = { PythonComposition(body) }) { measurables, constraints ->
                val measurable = measurables.first()
                val placeable = measurable.measure(constraints)
                val x = if (measurable.layoutId == expectTag) 0 else 60
                layout(SCENE, SCENE) {
                    placeable.placeRelative(x, 0)
                }
            }
        }
        try {
            val bitmap = Bitmap.makeFromImage(scene.render())
            return IntArray(SCENE * SCENE) { bitmap.getColor(it % SCENE, it / SCENE) }
        } finally {
            scene.close()
        }
    }

    /** Whether any pixel in the vertical band `[xStart, xEnd)` is not background -- the child is
     * placed at y=0, so any row in that band suffices. */
    private fun inkAt(pixels: IntArray, xStart: Int, xEnd: Int): Boolean =
        (0 until SCENE).any { y ->
            (xStart until xEnd).any { x -> pixels[y * SCENE + x] != BACKGROUND }
        }

    private companion object {
        const val BACKGROUND = 0
        const val SCENE = 80
    }
}
