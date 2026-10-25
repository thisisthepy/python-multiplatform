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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #308: `Card(modifier=Modifier)` -- a proxy **class** written where an object value goes --
 * is the class's registered empty value, the same call as `Card(modifier=Modifier.empty())`.
 *
 * This test registers `Modifier`'s empty factory itself (the library names no Compose type), then
 * renders `Card` three ways and requires the class spelling to draw exactly what the instance
 * spelling draws, and something (the content is real, not an empty frame).
 */
class ProxyClassArgumentRenderTest {

    @BeforeTest
    fun install() {
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

    @Test
    fun aModifierClassPassedToCardIsTheEmptyModifier() {
        val setup = """
            import python_multiplatform.binding as _pm_binding
            from fixture.compose import emptyModifier
            from androidx.compose.material3 import Card, Text
            _pm_binding.register_empty('androidx.compose.ui.Modifier', 'fixture.compose.emptyModifier')
            Modifier = type(emptyModifier())
        """.trimIndent()
        val viaClass = inkOf(setup + "\nCard(modifier=Modifier, content=lambda scope: Text('hi'))")
        val viaInstance = inkOf(setup + "\nCard(modifier=Modifier.empty(), content=lambda scope: Text('hi'))")
        println("compose render: Card(modifier=Modifier) -> $viaClass px, Card(modifier=Modifier.empty()) -> $viaInstance px")
        assertTrue(viaClass > 0, "Card(modifier=Modifier) drew nothing")
        assertEquals(viaInstance, viaClass, "the class spelling must draw what the instance spelling draws")
    }

    private fun inkOf(body: String): Int {
        val scene = ImageComposeScene(width = 200, height = 80, density = Density(1f)) {
            PythonComposition(body)
        }
        try {
            val bitmap = Bitmap.makeFromImage(scene.render())
            var ink = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (bitmap.getColor(x, y) != 0) ink++
            return ink
        } finally {
            scene.close()
        }
    }
}
