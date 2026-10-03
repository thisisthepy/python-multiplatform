package fixture.compose

import androidx.compose.ui.ImageComposeScene
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
 * **A constant held by an object reaches Compose and changes a layout** -- the half `80318c16` left
 * unproven.
 *
 * That commit taught the walker to bind what an `object` or a companion holds, and proved it where
 * the walker lives: two plugin tests assert that `AbsoluteAlignment.TopLeft` and `Alignment.Center`
 * arrive as zero-argument getters with the right body. What it could not say is that the value
 * survives the crossing -- a getter that returned the wrong singleton, or one whose handle never
 * resolved, would satisfy both of those and still be useless.
 *
 * ### Why arrangement rather than alignment
 *
 * An arrangement is observable without a probe. `Row(horizontalArrangement=...)` decides *where*
 * its children sit, so two arrangements put the same child at two different x positions in the same
 * scene, and the pixels say which one arrived. Alignment would need a measure policy of this test's
 * own to read it back, which is the shape `LayoutIdRenderTest` already carries for a different
 * reason; there is nothing to learn from a second copy of it.
 *
 * `Start` versus `End` is the pair, because they are opposite ends of the same axis: a wrapper that
 * ignored the argument entirely would place both at the same edge, and a wrapper that resolved some
 * *other* constant would have to resolve exactly the opposite one to pass both assertions.
 *
 * ### Two things this test had to get right, and both are facts rather than details
 *
 * **The constant is *read*, not called.** `Arrangement.Start` -- no parentheses. The adaptation
 * layer branches on [python.multiplatform.reflection.CallableKind]: a
 * [python.multiplatform.reflection.CallableKind.STATIC_GETTER] is invoked once by `PythonxAdapter`
 * itself and handed back as a value, so `Arrangement.$constant()` -- the spelling this test used
 * before the branch existed -- now raises `TypeError: '...' object is not callable` instead of
 * returning the right thing for the wrong reason. Read without being called, the value round-trips
 * as `Arrangement.Start` does in Kotlin.
 *
 * **The row is given a width.** A `Row` wraps its content, so with no width constraint there is no
 * spare space and every arrangement puts the child in the same place -- the `Start` assertion passed
 * for that reason before the width was added, which is exactly the kind of accidental pass the
 * opposite-end control exists to catch.
 */
class ObjectConstantRenderTest {

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

    /** `Arrangement.Start` keeps the child at the left edge. */
    @Test
    fun anArrangementConstantFromPythonPlacesTheChildAtTheStart() {
        val pixels = pixelsOf(rowWith("Start"))
        assertTrue(inkAt(pixels, 0, 20), "no ink at the left -- Arrangement.Start did not arrive")
        assertTrue(!inkAt(pixels, 60, SCENE), "ink at the right even though the arrangement was Start")
    }

    /** The control: `Arrangement.End` moves the same child to the other edge. Same scene, same
     * child, one constant different -- so this fails for a wrapper that ignores its argument. */
    @Test
    fun theOppositeArrangementConstantMovesTheChildToTheEnd() {
        val pixels = pixelsOf(rowWith("End"))
        assertTrue(inkAt(pixels, 60, SCENE), "no ink at the right -- Arrangement.End did not arrive")
        assertTrue(!inkAt(pixels, 0, 20), "ink at the left even though the arrangement was End")
    }

    /** The constant is fetched by the name the walker binds it under, through the adaptation layer,
     * exactly as an application would write it. */
    private fun rowWith(constant: String) = """
        from androidx.compose.foundation.layout import Row, Arrangement, width__Dp
        from androidx.compose.material3 import Text
        from fixture.compose import emptyModifier

        Row(
            modifier=width__Dp(emptyModifier(), 80.0),
            horizontalArrangement=Arrangement.$constant,
            content=lambda scope: Text('X'),
        )
    """.trimIndent()

    private fun pixelsOf(body: String): IntArray {
        val scene = ImageComposeScene(width = SCENE, height = 20, density = Density(1f)) {
            PythonComposition(body)
        }
        try {
            val bitmap = Bitmap.makeFromImage(scene.render())
            return IntArray(SCENE * 20) { bitmap.getColor(it % SCENE, it / SCENE) }
        } finally {
            scene.close()
        }
    }

    private fun inkAt(pixels: IntArray, xStart: Int, xEnd: Int): Boolean =
        (0 until 20).any { y ->
            (xStart until xEnd).any { x -> pixels[y * SCENE + x] != BACKGROUND }
        }

    private companion object {
        const val BACKGROUND = 0
        const val SCENE = 80
    }
}
