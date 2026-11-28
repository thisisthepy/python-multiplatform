package fixture.compose.window

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.text.TextRange
import fixture.compose.PythonComposition
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonxAdapter
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.event.InputMethodEvent
import java.awt.event.MouseEvent
import java.text.AttributedString
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * #191 (carried over from #172 item 5): input-method composition into a Python `TextField(state=...)`
 * through a **real desktop window**, so Compose's AWT layer is the one decoding each
 * `java.awt.event.InputMethodEvent` (its attributed text and committed-character count) into
 * `commitText` / `setComposingText`. `notebook-e2e`'s `InputMethodScene` reproduces the calls below
 * that layer offscreen; this is the layer itself.
 *
 * The window is a Swing `JFrame` holding a `ComposePanel`. The events go to the component Compose
 * registers its `InputMethodListener` on (`ComposeSceneMediator.contentComponent`), found by that
 * listener rather than by an internal class name. Focus is a mouse press and release on the field,
 * delivered to the same component. The sequence is a Hangul IME typing 한 (ㅎ, 하, 한 composing, then
 * 한 committed), and the Kotlin `TextFieldState` Python created must hold each composing text with its
 * range, then the committed text with none.
 *
 * Runs only in `desktopWindowTest` (a display is required; `desktopTest` is headless and excludes this
 * package).
 */
class RealWindowInputMethodTest {

    @BeforeTest
    fun installProducers() {
        check(!GraphicsEnvironment.isHeadless()) { "desktopWindowTest needs a display (java.awt.headless is set)" }
        Python3.initialize(silent = true)
        UpcallTable.clear()
        UpcallTable.install(ArtifactTable.fragments)
        check(UpcallBootstrap.publishToGlobals()) { "UpcallBootstrap.publishToGlobals() failed" }
        PythonxAdapter.install(setOf("androidx.compose.ui.unit.Dp"))
    }

    @AfterTest
    fun cleanup() {
        Python3.exec("globals().pop('_ime_state', None)")
        UpcallTable.clear()
    }

    @Test
    fun aRealWindowDecodesInputMethodEventsIntoAPythonTextField() {
        Python3.exec("from androidx.compose.foundation.text.input import TextFieldState\n_ime_state = TextFieldState()")
        val state = HandleTable.resolveRaw(pyStr("int(_ime_state._pm_handle)").toLong()) as? TextFieldState
            ?: fail("_ime_state is not a Kotlin TextFieldState")

        lateinit var frame: JFrame
        onEdt {
            frame = JFrame("RealWindowInputMethodTest")
            val panel = ComposePanel()
            panel.setContent {
                PythonComposition("from androidx.compose.material3 import TextField\nTextField(state=_ime_state)")
            }
            frame.contentPane.add(panel)
            frame.setSize(WIDTH, HEIGHT)
            frame.isVisible = true
        }
        try {
            val target = waitFor("Compose's input-method component") { onEdt { inputMethodComponent(frame) } }

            // Focus the field: a click inside it, on the component that carries Compose's listeners.
            onEdt {
                target.requestFocusInWindow()
                for (id in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
                    target.dispatchEvent(
                        MouseEvent(target, id, System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK, FIELD_X, FIELD_Y, 1, false, MouseEvent.BUTTON1),
                    )
                }
            }
            waitFor("the TextField to open an input session (getInputMethodRequests)") {
                onEdt { target.inputMethodRequests }
            }

            val observed = ArrayList<Pair<String, TextRange?>>()
            for ((text, committed) in HANGUL_EVENTS) {
                onEdt {
                    target.dispatchEvent(
                        InputMethodEvent(
                            target, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                            AttributedString(text).iterator, committed, null, null,
                        ),
                    )
                }
                val expected = if (committed == 0) text to TextRange(0, text.length) else text to null
                waitFor("the state to show $expected") {
                    (state.text.toString() to state.composition).takeIf { it == expected }
                }
                observed += state.text.toString() to state.composition
            }
            println("real-window IME: states $observed")
            assertEquals(
                listOf("ㅎ" to TextRange(0, 1), "하" to TextRange(0, 1), "한" to TextRange(0, 1), "한" to null),
                observed,
            )
            assertEquals("한", pyStr("_ime_state.text"), "Python does not read what the input method typed")
        } finally {
            onEdt { frame.dispose() }
        }
    }

    /** The component Compose registered its `InputMethodListener` on, or `null` before it exists. */
    private fun inputMethodComponent(root: Container): JComponent? {
        val pending = ArrayDeque<Component>(listOf(root))
        while (pending.isNotEmpty()) {
            val c = pending.removeFirst()
            if (c is JComponent && c.inputMethodListeners.isNotEmpty()) return c
            if (c is Container) pending.addAll(c.components)
        }
        return null
    }

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun <T : Any> waitFor(what: String, probe: () -> T?): T {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            probe()?.let { return it }
            Thread.sleep(20)
        }
        fail("timed out after 10 s waiting for $what")
    }

    private fun pyStr(expression: String): String = Python3.import("__main__").getAttr("__dict__").let { globals ->
        Python3.eval(expression, PY_EVAL_INPUT, globals, globals).toString()
    }

    private companion object {
        const val WIDTH = 360
        const val HEIGHT = 140
        /** Inside material3's TextField (280 x 56) at the panel's top-left. */
        const val FIELD_X = 120
        const val FIELD_Y = 28
        const val PY_EVAL_INPUT = 258

        /** (text, committed character count) per event: a Hangul IME typing 한. */
        val HANGUL_EVENTS = listOf("ㅎ" to 0, "하" to 0, "한" to 0, "한" to 1)
    }
}
