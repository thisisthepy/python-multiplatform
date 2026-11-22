@file:OptIn(ExperimentalFoundationApi::class)

package fixture.notebook

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextRange
import fixture.notebook.NotebookHost.cell
import fixture.notebook.NotebookHost.differing
import fixture.notebook.NotebookHost.framesUntil
import fixture.notebook.NotebookHost.inkOf
import fixture.notebook.NotebookHost.nextFrame
import fixture.notebook.NotebookHost.pixelsOf
import fixture.notebook.NotebookHost.pyInt
import fixture.notebook.NotebookHost.pyStr
import fixture.notebook.NotebookRootTest.Companion.CELL_05
import python.multiplatform.compose.PythonAppView
import python.multiplatform.reflection.HandleTable
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Cells 31-33: the text field. The screen writes, the notebook reads (cell 33, "화면에서 값을 변경한 후
 * 출력해보세요" -- change it on screen, then print it).
 *
 * In the decided spelling (pythonx-compose INTENT 5.8, SPEC S5.5) the field is `TextField(state=...)`
 * over a `TextFieldState` from `remember_text_field_state("")`, so Compose owns the text buffer and the
 * input method's composing region and **nothing crosses into Python per keystroke**. This is
 * pythonx-compose #10's completion criterion, checked here against a real composition:
 *
 * 1. The cell declares the field; the host draws it; a click focuses it and it opens an input session.
 * 2. A Hangul input-method sequence -- ㅎ, 하, 한 composing, then 한 committed -- is delivered the way the
 *    desktop window delivers one ([InputMethodScene.inputMethodEvent]), a frame after each event. A
 *    counter of every Python function started ([NotebookHost.pythonFunctionsStartedDuring]) must stay at
 *    zero across all of it, and the root must not run again.
 * 3. Kotlin's own `TextFieldState` holds the composing text with its composing range during the
 *    sequence, and the committed text with no composing range after it; the notebook then reads
 *    `field.text == "한"`.
 * 4. Outside the composition, `field.set_text_and_place_cursor_at_end("x")` reaches the screen within
 *    [NotebookHost.MAX_FRAMES] frames.
 */
class NotebookTextFieldTest {

    @BeforeTest
    fun host() = NotebookHost.install()

    @Test
    fun cell32to33_aTextFieldKeepsInputMethodCompositionInComposeAndTheNotebookReadsTheResult() {
        val scene = InputMethodScene(WIDTH, HEIGHT) {
            PythonAppView(module = "pythonx.compose.runtime", attribute = "app_root")
        }
        try {
            scene.render()
            cell(CELL_05 + CELL_32)
            framesUntil({ scene.render() }) { pyInt("len(fields)") > 0 }
            assertTrue(pyInt("len(fields)") > 0, "cell 32: the redeclared Practice root never composed")
            val state = HandleTable.resolveRaw(pyStr("int(fields[-1]._pm_handle)").toLong()) as? TextFieldState
                ?: fail("fields[-1] is not a Kotlin TextFieldState: ${pyStr("type(fields[-1])")}")
            assertEquals("", state.text.toString())

            // 1. Focus, by a click on the field; keyboard traversal if the click did not do it.
            scene.click(Offset(FIELD_CENTRE_X, FIELD_CENTRE_Y))
            framesUntil({ scene.render() }) { scene.activeRequest != null }
            var focusedBy = "click"
            if (scene.activeRequest == null) {
                focusedBy = "focus traversal (the click did not focus the field)"
                scene.focusFirst()
                framesUntil({ scene.render() }) { scene.activeRequest != null }
            }
            if (scene.activeRequest == null) fail("the TextField never opened a text input session, so no input method can reach it")

            // 2-3. The Hangul sequence, with the Python-function counter on.
            val practiceRuns = pyInt("practice_calls[0]")
            val observed = ArrayList<Pair<String, TextRange?>>()
            val started = NotebookHost.pythonFunctionsStartedDuring {
                for ((committed, composing) in HANGUL_EVENTS) {
                    scene.inputMethodEvent(committed, composing)
                    nextFrame { scene.render() }
                    observed += state.text.toString() to state.composition
                }
            }
            println("notebook cell 32: focused by $focusedBy; IME states $observed; Python functions started: ${started.size}")
            assertEquals(
                emptyList(), started,
                "Python ran while the input method typed into TextField(state=...); every key should stay in Compose",
            )
            assertEquals(practiceRuns, pyInt("practice_calls[0]"), "typing re-ran the Practice root")
            assertEquals(
                listOf(
                    "ㅎ" to TextRange(0, 1),
                    "하" to TextRange(0, 1),
                    "한" to TextRange(0, 1),
                    "한" to null,
                ),
                observed,
                "the TextFieldState did not hold the composing text and its range, then the committed text",
            )

            // Cell 33: the notebook reads what the screen wrote.
            assertEquals("<class 'str'>", pyStr("type(fields[-1].text)"))
            assertEquals("한", pyStr("fields[-1].text"), "cell 33: the notebook does not read what was typed on screen")
            assertEquals("True", pyStr("all(_f.text == '한' for _f in fields)"), "the root saw more than one state")

            // 4. Written from the notebook, outside the composition.
            val before = pixelsOf(scene.render())
            cell("fields[-1].set_text_and_place_cursor_at_end('x')")
            val (after, frames) = framesUntil({ scene.render() }) { differing(before, it) > 10 }
            println("notebook cell 32: set_text_and_place_cursor_at_end moved ${differing(before, after)} px after $frames frame(s)")
            assertEquals("x", state.text.toString(), "the write from Python did not reach the Kotlin state")
            assertTrue(inkOf(before) > 0, "the field drew nothing")
            assertTrue(
                differing(before, after) > 10,
                "the text written from the notebook did not reach the screen within ${NotebookHost.MAX_FRAMES} frames",
            )
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 80

        /** Inside material3's `TextField` (280 x 56 by default), drawn at `padding(8)`. */
        const val FIELD_CENTRE_X = 148f
        const val FIELD_CENTRE_Y = 36f

        /** (committed, composing) per input-method event: a Hangul IME typing 한 (ㅎ, ㅏ, ㄴ). */
        val HANGUL_EVENTS = listOf(
            "" to "ㅎ",
            "" to "하",
            "" to "한",
            "한" to "",
        )

        /**
         * Cell 32 in the decided spelling: `TextField(state=..., modifier=Modifier.padding(8))` over a
         * `remember_text_field_state("")`, instead of `TextField(text_state=main.App.messages, padding=8)`.
         * `fields` and `practice_calls` are the test's handles on what the root made and how often it ran.
         */
        val CELL_32 = """
            from pythonx.compose.foundation.text.input import remember_text_field_state

            fields = []
            practice_calls = [0]

            @app
            @Composable
            def Practice():  # 텍스트 입력란
                practice_calls[0] += 1
                field = remember_text_field_state("")
                fields.append(field)
                TextField(
                    state=field,
                    modifier=Modifier.padding(8)
                )
        """.trimIndent()
    }
}
