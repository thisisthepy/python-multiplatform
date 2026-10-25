package fixture.compose

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
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

class AnchoredDraggableRenderTest {

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
    fun aRealDragReachesThePythonCallbacksThroughAnchoredDraggable() {
        Python3.exec(
            """
            _confirm_events = []

            def _on_confirm(value):
                _confirm_events.append(value)
                return True
            """.trimIndent(),
        )

        val before = pixelsOf(BODY)
        assertTrue(inkOf(before) > 0, "the Text never composed, so there was nothing to drag")

        val scene = ImageComposeScene(width = SCENE, height = SCENE, density = Density(1f)) {
            PythonComposition(BODY)
        }
        try {
            scene.render()
            scene.sendPointerEvent(PointerEventType.Move, Offset(START_X, BOX_CENTRE))
            scene.sendPointerEvent(PointerEventType.Press, Offset(START_X, BOX_CENTRE))
            var x = START_X
            while (x < START_X + DRAG_DISTANCE) {
                x += STEP
                scene.sendPointerEvent(PointerEventType.Move, Offset(x, BOX_CENTRE))
            }
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, BOX_CENTRE))
            scene.render() // Wait for the snap animation to settle
        } finally {
            scene.close()
        }

        // The drag crosses the positional threshold and triggers confirmValueChange to the new anchor
        Python3.exec(
            "assert len(_confirm_events) > 0, 'on_confirm was never invoked'",
        )
        Python3.exec(
            "assert 'end' in _confirm_events, 'did not try to settle at end anchor: ' + repr(_confirm_events)",
        )

        val after = pixelsOf(BODY)
        val moved = differing(before, after)
        println(
            "compose input: anchoredDraggable drag -> ink ${inkOf(before)} -> ${inkOf(after)} px, " +
                "$moved pixels changed",
        )
        assertTrue(
            moved > 0,
            "no pixel changed after the drag, so the component did not move",
        )
    }

    @Test
    fun aPressOutsideTheBoxInvokesNothing() {
        Python3.exec(
            """
            _confirm_events = []

            def _on_confirm(value):
                _confirm_events.append(value)
                return True
            """.trimIndent(),
        )
        val scene = ImageComposeScene(width = SCENE, height = SCENE, density = Density(1f)) {
            PythonComposition(BODY)
        }
        try {
            scene.render()
            val at = Offset(SCENE - 2f, SCENE - 2f)
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at)
            scene.sendPointerEvent(PointerEventType.Move, Offset(SCENE - 20f, SCENE - 2f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(SCENE - 20f, SCENE - 2f))
            scene.render()
        } finally {
            scene.close()
        }
        Python3.exec(
            "assert _confirm_events == [], " +
                "'a press outside the box invoked anchoredDraggable: ' + repr(_confirm_events)",
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

    private fun differing(a: IntArray, b: IntArray): Int = a.indices.count { a[it] != b[it] }

    private companion object {
        const val BACKGROUND = 0
        const val SCENE = 80

        const val BOX_CENTRE = 24f
        const val START_X = 4f
        const val DRAG_DISTANCE = 40f
        const val STEP = 8f

        val BODY = """
            from fixture.compose import emptyModifier, pythonAnchoredDraggableString
            from androidx.compose.foundation.layout import size__Dp
            from androidx.compose.material3 import Text

            _m = pythonAnchoredDraggableString(size__Dp(emptyModifier(), 48.0), "start", "start", 0.0, "end", 40.0, _on_confirm)
            Text(str(len(_confirm_events)), modifier=_m)
        """.trimIndent()
    }
}
