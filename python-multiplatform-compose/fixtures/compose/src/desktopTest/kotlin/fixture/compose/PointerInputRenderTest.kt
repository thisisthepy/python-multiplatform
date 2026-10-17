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
 * **A real pointer event reaches a Python callback through `Modifier.pointerInput`'s suspend slot**
 * -- the seventh declined `Modifier` extension `FunctionSlotBindingTest`/`ArtifactScannerTest` name,
 * and the one that is not merely a missing feature of the walker (see `PythonPointerInput.kt`'s KDoc
 * for the reason `pointerInput` itself must stay declined).
 *
 * ### What this test proves, and how
 *
 * `fixture.compose.pythonPointerInput` (hand-written Kotlin, `PythonPointerInput.kt`) is a coroutine
 * that suspends for real -- `awaitPointerEventScope { while (true) { awaitPointerEvent() } }` -- and
 * calls an ordinary, non-suspending Python callback once per event. This test drives a real
 * `Move`/`Press`/`Release` sequence through `ImageComposeScene.sendPointerEvent`, the same entry point
 * `CallbackDrivenRenderTest` uses for `Checkbox`, and checks two independent things:
 *
 * 1. **Python received the events**, asserted in Python against the list the callback appended to --
 *    the boundary carries no reader for an arbitrary Python list, so a Kotlin-side copy would be a
 *    second thing that could disagree with what the callback actually saw.
 * 2. **The state Python wrote is visible in a redraw.** `Text(str(len(_tap_events)))` reads the same
 *    list on every composition, so a *fresh* scene over the same body shows a different digit once
 *    the list is non-empty -- the same "second scene" shape `CallbackDrivenRenderTest` uses, and for
 *    the same reason: the scene that received the events does not itself recompose from a click that
 *    `PythonComposition`'s snapshot has no way to see.
 */
class PointerInputRenderTest {

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
    fun aRealPointerTapReachesThePythonCallbackThroughPointerInput() {
        Python3.exec(
            """
            _tap_events = []

            def _on_event(kind, x, y):
                _tap_events.append((kind, x, y))
            """.trimIndent(),
        )

        val before = pixelsOf(BODY)
        assertTrue(inkOf(before) > 0, "the Text never composed, so there was nothing to tap")

        tapCentre()

        Python3.exec(
            "assert len(_tap_events) >= 1, 'pythonPointerInput never invoked the Python callback'",
        )
        Python3.exec(
            """
            kinds = [e[0] for e in _tap_events]
            assert 'press' in kinds, 'no press event reached Python: ' + repr(_tap_events)
            """.trimIndent(),
        )
        // Every event this test sent landed inside the 48x48 box centred on (24, 24), so every
        // reported position must be within it -- proving the coordinates are the real tap, not a
        // stub value like (0.0, 0.0).
        Python3.exec(
            """
            for kind, x, y in _tap_events:
                assert 0.0 <= x <= 48.0 and 0.0 <= y <= 48.0, (
                    'event %s at (%r, %r) is outside the 48x48 target box' % (kind, x, y)
                )
            """.trimIndent(),
        )

        val after = pixelsOf(BODY)
        val moved = differing(before, after)
        println(
            "compose input: pointerInput tap -> ink ${inkOf(before)} -> ${inkOf(after)} px, " +
                "$moved pixels changed",
        )
        assertTrue(
            moved > 0,
            "no pixel changed after the tap, so the count the callback wrote never reached the render",
        )
    }

    /**
     * The negative control: a press-and-release well outside the 48x48 box invokes nothing. Without
     * this, a callback invoked by composition itself (rather than by the tap) would satisfy the test
     * above.
     */
    @Test
    fun aTapThatMissesTheBoxInvokesNothing() {
        Python3.exec(
            """
            _tap_events = []

            def _on_event(kind, x, y):
                _tap_events.append((kind, x, y))
            """.trimIndent(),
        )
        tapAt(Offset(SCENE - 2f, SCENE - 2f))
        Python3.exec(
            "assert _tap_events == [], 'a tap that missed the box invoked the callback: ' + repr(_tap_events)",
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

        /** `size__Dp(..., 48.0)` gives the tap target a fixed 48x48 box; its centre is 24. */
        const val BOX_CENTRE = 24f

        /** `Text` reads `_tap_events`'s length on every composition, which is what makes a fresh
         * scene show what the callback wrote -- the same shape `CHECKBOX`/`SWITCH` use in
         * `CallbackDrivenRenderTest`. The box is sized with the walked `size__Dp` so the tap target
         * is deterministic regardless of how wide the digit itself renders. */
        val BODY = """
            from fixture.compose import emptyModifier, pythonPointerInput
            from androidx.compose.foundation.layout import size__Dp
            from androidx.compose.material3 import Text

            _m = pythonPointerInput(size__Dp(emptyModifier(), 48.0), _on_event)
            Text(str(len(_tap_events)), modifier=_m)
        """.trimIndent()
    }
}
