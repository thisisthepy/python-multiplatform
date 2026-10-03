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
 * **A provided value is successfully read in a child composable** -- testing
 * `modifierLocalProvider` and `modifierLocalConsumer` generic wrappers.
 */
class ModifierLocalRenderTest {

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

    @Test
    fun aProvidedValueIsReadByTheConsumer() {
        Python3.exec(
            """
            _read_values = []
            def _on_read(value):
                _read_values.append(value)
            """.trimIndent(),
        )

        val body = """
            from fixture.compose import emptyModifier, pythonModifierLocalOfString, pythonModifierLocalProviderString, pythonModifierLocalConsumerString
            from androidx.compose.material3 import Text
            from androidx.compose.foundation.layout import Box

            _local = pythonModifierLocalOfString("default value")
            
            _m_parent = pythonModifierLocalProviderString(emptyModifier(), _local, "provided value")
            _m_child = pythonModifierLocalConsumerString(emptyModifier(), _local, _on_read)

            def _content():
                Text("Hello", modifier=_m_child)

            Box(modifier=_m_parent, content=_content)
        """.trimIndent()

        val before = pixelsOf(body)
        assertTrue(inkOf(before) > 0, "the Text never composed")

        Python3.exec(
            "assert len(_read_values) > 0, 'on_read was never invoked'",
        )
        Python3.exec(
            "assert 'provided value' in _read_values, 'Expected provided value but got: ' + repr(_read_values)",
        )
    }

    @Test
    fun unprovidedLocalYieldsDefaultValue() {
        Python3.exec(
            """
            _read_values = []
            def _on_read(value):
                _read_values.append(value)
            """.trimIndent(),
        )

        val body = """
            from fixture.compose import emptyModifier, pythonModifierLocalOfString, pythonModifierLocalConsumerString
            from androidx.compose.material3 import Text

            _local = pythonModifierLocalOfString("default value")
            
            _m = emptyModifier()
            _m = pythonModifierLocalConsumerString(_m, _local, _on_read)

            Text("Hello", modifier=_m)
        """.trimIndent()

        val before = pixelsOf(body)
        assertTrue(inkOf(before) > 0, "the Text never composed")

        Python3.exec(
            "assert len(_read_values) > 0, 'on_read was never invoked'",
        )
        Python3.exec(
            "assert _read_values[-1] == 'default value', 'Expected default value but got: ' + repr(_read_values[-1])",
        )
    }

    private fun pixelsOf(body: String): IntArray {
        val scene = ImageComposeScene(width = SCENE, height = SCENE, density = Density(1f)) {
            PythonComposition(body)
        }
        try {
            val bitmap = Bitmap.makeFromImage(scene.render())
            return IntArray(SCENE * SCENE) { bitmap.getColor(it % SCENE, it / SCENE) }
        } finally {
            scene.close()
        }
    }

    private fun inkOf(pixels: IntArray): Int = pixels.count { it != BACKGROUND }

    private companion object {
        const val BACKGROUND = 0
        const val SCENE = 80
    }
}
