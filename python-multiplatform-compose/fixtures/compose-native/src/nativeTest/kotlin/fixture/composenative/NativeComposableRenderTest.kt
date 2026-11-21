package fixture.composenative

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
 * #191 on Kotlin/Native desktop: Python draws material3 composables whose bindings come from the
 * walked klibs. `Text` (about 15 defaulted parameters) and `Button` (8) are past B1's limit of 6, so
 * each call is the B3 compiler plugin's rewrite; if the plugin did not run, the marker function in
 * the generated fragment throws on the first call and these tests fail with its message.
 */
class NativeComposableRenderTest {

    @BeforeTest
    fun install() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        UpcallTable.install(ArtifactTable.fragments)
        check(UpcallBootstrap.publishToGlobals()) { "UpcallBootstrap.publishToGlobals() failed" }
        PythonxAdapter.install(setOf("androidx.compose.ui.unit.Dp"))
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    @Test
    fun pythonDrawsAMaterial3TextThroughTheCompilerPlugin() {
        val drawn = inkOf("from androidx.compose.material3 import Text\nText('hi')")
        val blank = inkOf("pass")
        println("compose-native render: Text('hi') -> $drawn px, empty body -> $blank px")
        assertEquals(0, blank, "an empty composition must draw nothing, or the measurement is not measuring")
        assertTrue(drawn > 0, "Text('hi') drew nothing")
    }

    @Test
    fun moreTextIsMoreInk() {
        val short = inkOf("from androidx.compose.material3 import Text\nText('hi')")
        val long = inkOf("from androidx.compose.material3 import Text\nText('hi hi hi hi hi')")
        assertTrue(long > short, "expected more ink for more text: $short vs $long")
    }

    @Test
    fun aButtonWithAPythonContentLambdaDraws() {
        val drawn = inkOf(
            "from androidx.compose.material3 import Button, Text\n" +
                "Button(onClick=lambda: None, content=lambda: Text('ok'))",
        )
        assertTrue(drawn > 0, "Button(...) drew nothing")
    }

    private fun inkOf(body: String): Int {
        val scene = ImageComposeScene(width = 200, height = 60, density = Density(1f)) {
            PythonComposition(body)
        }
        try {
            val bitmap = Bitmap.makeFromImage(scene.render())
            var ink = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    if (bitmap.getColor(x, y) != 0) ink++
                }
            }
            return ink
        } finally {
            scene.close()
        }
    }
}
