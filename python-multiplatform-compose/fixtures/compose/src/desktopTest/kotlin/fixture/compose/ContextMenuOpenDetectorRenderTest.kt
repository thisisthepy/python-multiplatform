package fixture.compose

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
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

class ContextMenuOpenDetectorRenderTest {

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
    fun rightClickTriggersContextMenuOpenDetector() {
        Python3.exec(
            """
            _events = []
            def _on_pointer_down(x, y):
                _events.append((x, y))
            """.trimIndent(),
        )

        val scene = ImageComposeScene(width = 80, height = 80, density = Density(1f)) {
            PythonComposition(
                """
                from fixture.compose import emptyModifier, pythonContextMenuOpenDetector
                from androidx.compose.foundation.layout import size__Dp
                from androidx.compose.material3 import Text
                
                _m = pythonContextMenuOpenDetector(size__Dp(emptyModifier(), 48.0), True, _on_pointer_down)
                Text("Box", modifier=_m)
                """.trimIndent()
            )
        }
        try {
            scene.render()
            val at = Offset(24f, 24f)
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(
                PointerEventType.Press,
                at,
                buttons = PointerButtons(isSecondaryPressed = true)
            )
            scene.sendPointerEvent(
                PointerEventType.Release,
                at,
                buttons = PointerButtons(isSecondaryPressed = true)
            )
            scene.render()
        } finally {
            scene.close()
        }

        Python3.exec(
            """
            assert len(_events) == 1, 'on_pointer_down fired ' + str(len(_events)) + ' times'
            sx, sy = _events[0]
            assert sx == 24.0 and sy == 24.0, 'on_pointer_down fired with wrong coordinates'
            """.trimIndent(),
        )
    }

    @Test
    fun leftClickDoesNotTriggerContextMenuOpenDetector() {
        Python3.exec(
            """
            _events = []
            def _on_pointer_down(x, y):
                _events.append((x, y))
            """.trimIndent(),
        )

        val scene = ImageComposeScene(width = 80, height = 80, density = Density(1f)) {
            PythonComposition(
                """
                from fixture.compose import emptyModifier, pythonContextMenuOpenDetector
                from androidx.compose.foundation.layout import size__Dp
                from androidx.compose.material3 import Text
                
                _m = pythonContextMenuOpenDetector(size__Dp(emptyModifier(), 48.0), True, _on_pointer_down)
                Text("Box", modifier=_m)
                """.trimIndent()
            )
        }
        try {
            scene.render()
            val at = Offset(24f, 24f)
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at) // Left click
            scene.sendPointerEvent(PointerEventType.Release, at)
            scene.render()
        } finally {
            scene.close()
        }

        Python3.exec(
            "assert len(_events) == 0, 'left click should not trigger contextMenuOpenDetector'",
        )
    }
}
