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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A real drag gesture reaches Python through `Modifier.draggable`'s suspend slots** -- one of the
 * three `docs/design/pythonx-adapter-design.md` §9.2 judges "the same suspend-lambda shape as `pointerInput`"
 * without attempting. `fixture.compose.pythonDraggable` (`PythonDraggable.kt`) is the same technique
 * §9.1 already proved for `pointerInput`, applied to `draggable`'s three callback slots: a real
 * `DraggableState` and two real `suspend CoroutineScope.(...) -> Unit` lambdas, hand-written and
 * ordinarily compiled, each calling a plain non-suspending Python callback synchronously.
 *
 * `draggable`'s three slots are not one suspend shape but two: `onDelta` (the [DraggableState]
 * factory's parameter) is a plain, non-suspend `(Float) -> Unit` called many times per gesture, while
 * `onDragStarted`/`onDragStopped` are the `suspend CoroutineScope.(...) -> Unit` shape `pointerInput`'s
 * technique targets, called once each per gesture. All three are exercised here.
 */
class DraggableRenderTest {

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
     * The positive claim: a real horizontal drag across the modifier's own box invokes
     * `onDragStarted` with a real position, `on_delta` one or more times with real (non-zero, not
     * stub) values that sum to the direction actually dragged, and `onDragStopped` exactly once --
     * and the accumulated total Python held shows up as a different digit in a fresh scene.
     */
    @Test
    fun aRealDragReachesThePythonCallbacksThroughDraggable() {
        Python3.exec(
            """
            _drag_total = [0.0]
            _delta_events = []
            _started_events = []
            _stopped_events = []

            def _on_delta(delta):
                _delta_events.append(delta)
                _drag_total[0] += delta

            def _on_drag_started(x, y):
                _started_events.append((x, y))

            def _on_drag_stopped(velocity):
                _stopped_events.append(velocity)
            """.trimIndent(),
        )

        val before = pixelsOf(BODY)
        assertTrue(inkOf(before) > 0, "the Text never composed, so there was nothing to drag")

        dragAcrossBox()

        Python3.exec(
            "assert len(_started_events) == 1, 'onDragStarted fired ' + str(len(_started_events)) + ' times'",
        )
        // The started position must be a real coordinate inside the 48x48 box the drag began in, not
        // a stub value like (0.0, 0.0).
        Python3.exec(
            """
            sx, sy = _started_events[0]
            assert 0.0 <= sx <= 48.0 and 0.0 <= sy <= 48.0, (
                'onDragStarted fired with (%r, %r), outside the 48x48 box' % (sx, sy)
            )
            """.trimIndent(),
        )
        Python3.exec(
            "assert len(_delta_events) >= 1, 'on_delta was never invoked'",
        )
        // A drag that moved strictly rightward must accumulate a strictly positive total; a stub that
        // always reported 0.0 would leave this at zero.
        Python3.exec(
            "assert _drag_total[0] > 0.0, 'accumulated delta was not positive: ' + repr(_drag_total[0])",
        )
        Python3.exec(
            "assert len(_stopped_events) == 1, 'onDragStopped fired ' + str(len(_stopped_events)) + ' times'",
        )
        Python3.exec(
            "assert isinstance(_stopped_events[0], float), 'onDragStopped got a non-float: ' + repr(_stopped_events[0])",
        )

        val after = pixelsOf(BODY)
        val moved = differing(before, after)
        println(
            "compose input: draggable drag -> ink ${inkOf(before)} -> ${inkOf(after)} px, " +
                "$moved pixels changed",
        )
        assertTrue(
            moved > 0,
            "no pixel changed after the drag, so the total the callback wrote never reached the render",
        )
    }

    /**
     * The negative control: a press and release **outside** the draggable's own box, with no
     * movement inside it at all, invokes none of the three callbacks -- the same "off target invokes
     * nothing" shape `PointerInputRenderTest` uses for `pointerInput`.
     */
    @Test
    fun aPressOutsideTheBoxInvokesNothing() {
        Python3.exec(
            """
            _drag_total = [0.0]
            _delta_events = []
            _started_events = []
            _stopped_events = []

            def _on_delta(delta):
                _delta_events.append(delta)
                _drag_total[0] += delta

            def _on_drag_started(x, y):
                _started_events.append((x, y))

            def _on_drag_stopped(velocity):
                _stopped_events.append(velocity)
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
            "assert _started_events == [] and _delta_events == [] and _stopped_events == [], " +
                "'a press outside the box invoked draggable: ' + repr((_started_events, _delta_events, _stopped_events))",
        )
    }

    private fun dragAcrossBox() {
        val scene = ImageComposeScene(width = SCENE, height = SCENE, density = Density(1f)) {
            PythonComposition(BODY)
        }
        try {
            scene.render()
            scene.sendPointerEvent(PointerEventType.Move, Offset(START_X, BOX_CENTRE))
            scene.sendPointerEvent(PointerEventType.Press, Offset(START_X, BOX_CENTRE))
            // Several move steps, well past the default touch slop, so the gesture is recognised as
            // a drag rather than swallowed as a click.
            var x = START_X
            while (x < START_X + DRAG_DISTANCE) {
                x += STEP
                scene.sendPointerEvent(PointerEventType.Move, Offset(x, BOX_CENTRE))
            }
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, BOX_CENTRE))
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

        /** `size__Dp(..., 48.0)` gives the drag target a fixed 48x48 box; its vertical centre is 24. */
        const val BOX_CENTRE = 24f
        const val START_X = 4f
        const val DRAG_DISTANCE = 40f
        const val STEP = 8f

        /** `Text` reads `_drag_total`'s (truncated) value on every composition, which is what makes a
         * fresh scene show what the callback wrote -- the same shape `PointerInputRenderTest` uses. */
        val BODY = """
            from fixture.compose import emptyModifier, pythonDraggable
            from androidx.compose.foundation.layout import size__Dp
            from androidx.compose.material3 import Text

            _m = pythonDraggable(size__Dp(emptyModifier(), 48.0), _on_delta, _on_drag_started, _on_drag_stopped)
            Text(str(int(_drag_total[0])), modifier=_m)
        """.trimIndent()
    }
}
