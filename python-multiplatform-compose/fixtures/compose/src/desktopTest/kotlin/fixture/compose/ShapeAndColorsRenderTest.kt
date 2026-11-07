package fixture.compose

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Bitmap
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonxAdapter
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #307: `Button(shape=RoundedCornerShape(...), colors=ButtonDefaults.buttonColors(...))` and the
 * `Card` equivalent, written from Python.
 *
 * Two bindings are needed. `androidx.compose.foundation.shape` is walked (`RoundedCornerShape`), and
 * `ButtonDefaults.buttonColors` / `CardDefaults.cardColors`, `@Composable` member functions of a Kotlin
 * `object` that return a value, bind through a thunk that loads the object's `INSTANCE` and calls the
 * member (`ThunkSpec.onObjectInstance`).
 *
 * What is observed is pixels, so that a shape or a colour that never reached Compose fails: with an
 * empty content a `Button` is a plain surface, so its centre pixel is the container colour and the
 * corner pixel is transparent only when the corner radius is large. The radius-zero control keeps the
 * corner assertion from passing for a reason other than the shape.
 */
class ShapeAndColorsRenderTest {

    @BeforeTest
    fun installProducers() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        UpcallTable.install(ArtifactTable.fragments)
        check(UpcallBootstrap.publishToGlobals()) { "UpcallBootstrap.publishToGlobals() failed" }
        PythonxAdapter.install(PYTHONX_RAW_VALUE_CLASSES)
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    @Test
    fun buttonTakesARoundedCornerShapeAndButtonDefaultsColours() {
        val round = frame(buttonBody(radiusDp = 20.0))
        val square = frame(buttonBody(radiusDp = 0.0))
        println("compose render: Button(shape=RoundedCornerShape(20dp)) corner=${square.topLeftOf(RED)} centre=${round.pixel(25, 15)}; " +
            "radius 0 corner=${square.pixel(0, 0)}")
        assertEquals(RED, round.pixel(25, 15), "the container colour of buttonColors(container_color=Red) did not reach the Button")
        // The square control's painted box is where the component sits; its corner must be red there
        // (control) and not red in the rounded frame.
        val (cx, cy) = square.topLeftOf(RED)
        assertEquals(RED, square.pixel(cx, cy), "control: a square button must paint its corner")
        assertTrue(round.pixel(cx, cy) != RED, "a 20dp corner radius must leave the corner pixel unpainted")
    }

    @Test
    fun cardTakesARoundedCornerShapeAndCardDefaultsColours() {
        val round = frame(cardBody(radiusDp = 20.0))
        val square = frame(cardBody(radiusDp = 0.0))
        println("compose render: Card(shape=RoundedCornerShape(20dp)) red=${round.count(RED)} corner=${square.topLeftOf(RED)}; " +
            "radius 0 corner=${square.pixel(0, 0)}")
        assertTrue(round.count(RED) > 0, "the container colour of cardColors(container_color=Red) did not reach the Card")
        // The square control's painted box is where the component sits; its corner must be red there
        // (control) and not red in the rounded frame.
        val (cx, cy) = square.topLeftOf(RED)
        assertEquals(RED, square.pixel(cx, cy), "control: a square card must paint its corner")
        assertTrue(round.pixel(cx, cy) != RED, "a 20dp corner radius must leave the corner pixel unpainted")
    }

    private fun buttonBody(radiusDp: Double) = """
        from androidx.compose.material3 import Button, ButtonDefaults
        from androidx.compose.foundation.shape import RoundedCornerShape__Dp
        from androidx.compose.ui.graphics import Color__Int_Int_Int_Int as _Color
        Button(
            on_click=lambda: None,
            shape=RoundedCornerShape__Dp($radiusDp),
            colors=ButtonDefaults.buttonColors(container_color=_Color(255, 0, 0)),
            content=lambda: None,
        )
    """.trimIndent()

    private fun cardBody(radiusDp: Double) = """
        from androidx.compose.material3 import Card, CardDefaults, Text
        from androidx.compose.foundation.shape import RoundedCornerShape__Dp
        from androidx.compose.ui.graphics import Color__Int_Int_Int_Int as _Color
        Card(
            shape=RoundedCornerShape__Dp($radiusDp),
            colors=CardDefaults.cardColors(container_color=_Color(255, 0, 0)),
            content=lambda scope: Text('hi hi hi hi hi hi'),
        )
    """.trimIndent()

    private class Frame(val width: Int, val pixels: IntArray) {
        fun pixel(x: Int, y: Int) = pixels[y * width + x]
        fun count(color: Int) = pixels.count { it == color }

        /** Top-left of the box the colour covers: where the component sits, which a minimum touch
         * target can inset from (0, 0). */
        fun topLeftOf(color: Int): Pair<Int, Int> {
            val idx = pixels.indices.filter { pixels[it] == color }
            check(idx.isNotEmpty()) { "no pixel of the colour" }
            return idx.minOf { it % width } to idx.minOf { it / width }
        }
    }

    private fun frame(body: String): Frame {
        val scene = ImageComposeScene(width = 200, height = 60, density = Density(1f)) {
            PythonComposition(body)
        }
        try {
            val bitmap = Bitmap.makeFromImage(scene.render())
            return Frame(bitmap.width, IntArray(bitmap.width * bitmap.height) { bitmap.getColor(it % bitmap.width, it / bitmap.width) })
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
    }
}
