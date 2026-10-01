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

/**
 * **A real pointer event reaches a Python callback through `Modifier.dragAndDropSource`'s suspend
 * slot** -- the second of the three `docs/design/pythonx-adapter-design.md` §9.2 judges reachable by
 * `pointerInput`'s technique without attempting. `PythonDragAndDropSource.kt`'s KDoc has the reason
 * this one is not merely analogous: disassembling `DragAndDropSourceNode`'s constructor shows it
 * delegates the *same* `SuspendingPointerInputModifierNode` `Modifier.pointerInput` itself delegates
 * to, so this test is structured exactly like `PointerInputRenderTest`.
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
     * The positive claim: a press-and-release at the modifier's own 48x48 box invokes the Python
     * callback with real (type, x, y) triples, and the digit `Text` draws changes once it has.
     */
    @Test
    fun aRealPointerTapReachesThePythonCallbackThroughDragAndDropSource() {
        Python3.exec(
            """
            _dnd_events = []

            def _on_event(kind, x, y):
                _dnd_events.append((kind, x, y))
            """.trimIndent(),
        )

        val before = pixelsOf(BODY)
        assertTrue(inkOf(before) > 0, "the Text never composed, so there was nothing to tap")

        tapCentre()

        Python3.exec(
            "assert len(_dnd_events) >= 1, 'pythonDragAndDropSource never invoked the Python callback'",
        )
        Python3.exec(
            """
            kinds = [e[0] for e in _dnd_events]
            assert 'press' in kinds, 'no press event reached Python: ' + repr(_dnd_events)
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
            "compose input: dragAndDropSource tap -> ink ${inkOf(before)} -> ${inkOf(after)} px, " +
                "$moved pixels changed",
        )
        assertTrue(
            moved > 0,
            "no pixel changed after the tap, so the count the callback wrote never reached the render",
        )
    }

    /**
     * The negative control: a press-and-release well outside the 48x48 box invokes nothing.
     */
    @Test
    fun aTapThatMissesTheBoxInvokesNothing() {
        Python3.exec(
            """
            _dnd_events = []

            def _on_event(kind, x, y):
                _dnd_events.append((kind, x, y))
            """.trimIndent(),
        )
        tapAt(Offset(SCENE - 2f, SCENE - 2f))
        Python3.exec(
            "assert _dnd_events == [], 'a tap that missed the box invoked the callback: ' + repr(_dnd_events)",
        )
    }

    private fun tapCentre() = tapAt(Offset(BOX_CENTRE, BOX_CENTRE))

    private fun tapAt(at: Offset) {
        val scene = ImageComposeScene(width = SCENE, height = SCENE, density = Density(1f)) {
            PythonComposition(BODY)
        }
        try {
            scene.render()
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at)
            scene.sendPointerEvent(PointerEventType.Release, at)
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

        val BODY = """
            from fixture.compose import emptyModifier, pythonDragAndDropSource
            from androidx.compose.foundation.layout import size__Dp
            from androidx.compose.material3 import Text

            _m = pythonDragAndDropSource(size__Dp(emptyModifier(), 48.0), _on_event)
            Text(str(len(_dnd_events)), modifier=_m)
        """.trimIndent()
    }
}
