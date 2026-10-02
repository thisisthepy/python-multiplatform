package fixture.compose

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
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

/**
 * **A real pointer drag reaches a Python callback through `Modifier.dragAndDropSource`.**
 *
 * Through Compose 1.6 this was a tap, forwarded raw by the modifier's suspend `PointerInputScope`
 * slot exactly as `PointerInputRenderTest` does. Compose 1.11 removed that slot; the only callback
 * left is `transferData(Offset)`, which Compose's own start detector invokes when a primary-button
 * mouse drag begins. `PythonDragAndDropSource.kt`'s KDoc has the details. A tap no longer reaches
 * Python by construction, so both cases drag.
 */
class DragAndDropSourceRenderTest {

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

    /**
     * The positive claim: a primary-button drag starting inside the modifier's own 48x48 box invokes
     * the Python callback with a real ("start", x, y) triple, and the digit `Text` draws changes once
     * it has.
     */
    @Test
    fun aRealPointerDragReachesThePythonCallbackThroughDragAndDropSource() {
        Python3.exec(
            """
            _dnd_events = []

            def _on_event(kind, x, y):
                _dnd_events.append((kind, x, y))
            """.trimIndent(),
        )

        val before = pixelsOf(BODY)
        assertTrue(inkOf(before) > 0, "the Text never composed, so there was nothing to drag")

        dragFrom(Offset(START_X, BOX_CENTRE))

        Python3.exec(
            "assert len(_dnd_events) >= 1, 'pythonDragAndDropSource never invoked the Python callback'",
        )
        Python3.exec(
            """
            kinds = [e[0] for e in _dnd_events]
            assert 'start' in kinds, 'no drag start reached Python: ' + repr(_dnd_events)
            """.trimIndent(),
        )
        Python3.exec(
            """
            for kind, x, y in _dnd_events:
                assert 0.0 <= x <= 48.0 and 0.0 <= y <= 48.0, (
                    'event %s at (%r, %r) is outside the 48x48 target box' % (kind, x, y)
                )
            """.trimIndent(),
        )

        val after = pixelsOf(BODY)
        val moved = differing(before, after)
        println(
            "compose input: dragAndDropSource drag -> ink ${inkOf(before)} -> ${inkOf(after)} px, " +
                "$moved pixels changed",
        )
        assertTrue(
            moved > 0,
            "no pixel changed after the drag, so the count the callback wrote never reached the render",
        )
    }

    /**
     * The negative control: the same drag, started well outside the 48x48 box, invokes nothing.
     */
    @Test
    fun aDragThatStartsOutsideTheBoxInvokesNothing() {
        Python3.exec(
            """
            _dnd_events = []

            def _on_event(kind, x, y):
                _dnd_events.append((kind, x, y))
            """.trimIndent(),
        )
        dragFrom(Offset(START_X, SCENE - 2f))
        Python3.exec(
            "assert _dnd_events == [], 'a drag outside the box invoked the callback: ' + repr(_dnd_events)",
        )
    }

    private fun dragFrom(start: Offset) {
        val scene = ImageComposeScene(width = SCENE, height = SCENE, density = Density(1f)) {
            PythonComposition(BODY)
        }
        try {
            scene.render()
            val held = PointerButtons(isPrimaryPressed = true)
            scene.sendPointerEvent(PointerEventType.Move, start)
            scene.sendPointerEvent(PointerEventType.Press, start, buttons = held, button = PointerButton.Primary)
            // Several move steps, well past the touch slop, so the start detector sees a drag.
            var x = start.x
            while (x < start.x + DRAG_DISTANCE) {
                x += STEP
                scene.sendPointerEvent(PointerEventType.Move, Offset(x, start.y), buttons = held)
                scene.render()
            }
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, start.y), button = PointerButton.Primary)
            scene.render()
        } finally {
            scene.close()
        }
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
            from fixture.compose import emptyModifier, pythonDragAndDropSource
            from androidx.compose.foundation.layout import size__Dp
            from androidx.compose.material3 import Text

            _m = pythonDragAndDropSource(size__Dp(emptyModifier(), 48.0), _on_event)
            Text(str(len(_dnd_events)), modifier=_m)
        """.trimIndent()
    }
}
