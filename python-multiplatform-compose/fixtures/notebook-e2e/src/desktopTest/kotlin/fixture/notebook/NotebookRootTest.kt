package fixture.notebook

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import fixture.notebook.NotebookHost.cell
import fixture.notebook.NotebookHost.differing
import fixture.notebook.NotebookHost.framesUntil
import fixture.notebook.NotebookHost.inkOf
import fixture.notebook.NotebookHost.pixelsOf
import fixture.notebook.NotebookHost.pyInt
import fixture.notebook.NotebookHost.pyStr
import python.multiplatform.compose.PythonAppView
import python.multiplatform.compose.PythonWidget
import python.multiplatform.ffi.Python3
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The notebook's application-level scenarios (`UI.ipynb` cells 5-16): importing the app draws it, the
 * notebook and the screen share state, and **redeclaring the root changes the screen with no update
 * call** -- plus the negative checks that show the last one can fail.
 *
 * Every screen is compared pixel for pixel with the same composables drawn from Kotlin, so "the screen
 * changed" means "the screen now shows what the cell declared", not merely "something moved".
 */
class NotebookRootTest {

    @BeforeTest
    fun host() = NotebookHost.install()

    /**
     * Cell 5's imports, in the spellings pythonx-compose decided (its INTENT section 5): everything the
     * notebook imports that has a decided counterpart resolves against the installed wheel. The names it
     * leaves out are the gaps in this module's README (`remember_saveable`, the coroutine scopes, the
     * lower-case `modifier`).
     */
    @Test
    fun cell05_theImportsResolveFromTheInstalledWheel() {
        cell(CELL_05)
        cell(
            """
            import pythonx.compose.material3 as _nb_m3
            assert DefaultIcons is not None and Icon is not None and TextField is not None
            assert Column is _nb_m3.Column and Row is _nb_m3.Row and Spacer is _nb_m3.Spacer
            assert Modifier is not None and Alignment is not None and Arrangement is not None
            assert app is _nb_real_app and Composable(len) is len
            """,
        )
    }

    /**
     * Cells 6-7: `import main` declares the app, and the host -- configured once with
     * `PythonAppView(module = "pythonx.compose.runtime", attribute = "app_root")` -- draws it. `main.App` is the root on
     * screen. An idle frame does not run it again: nothing polls.
     */
    @Test
    fun cell06to07_importingMainDrawsTheAppItDeclares() {
        val expected = NotebookHost.controlPixels(W, H) { Text(INITIAL_MESSAGE) }
        val scene = NotebookHost.hostScene(W, H)
        try {
            val first = pixelsOf(scene.render())
            assertEquals("True", pyStr("main.App is _nb_rt.app_root.value"), "cell 7: main.App is not the declared root")
            assertEquals(1, pyInt("main.calls['App']"), "the declared root was not composed exactly once")
            assertTrue(inkOf(expected) > 0, "the Kotlin control drew nothing, so the comparison measures nothing")
            assertEquals(0, differing(first, expected), "main.App did not draw what Text(INITIAL_MESSAGE) draws from Kotlin")

            scene.render()
            assertEquals(1, pyInt("main.calls['App']"), "an idle frame ran the root again, so something polls")
        } finally {
            scene.close()
        }
    }

    /**
     * Cells 9-13: the notebook reads the state the screen shows (cell 10), writes it (cell 12) and the
     * screen follows, then restores it (cell 13) and the screen is back -- with no call into the host.
     * The root runs again because it read the state (a recomposition), not because anything was
     * redeclared. The other direction, screen to notebook, is `NotebookTextFieldTest`.
     */
    @Test
    fun cell09to13_theNotebookAndTheScreenShareOneState() {
        val initial = NotebookHost.controlPixels(W, H) { Text(INITIAL_MESSAGE) }
        val changed = NotebookHost.controlPixels(W, H) { Text(CHANGED_MESSAGE) }
        val scene = NotebookHost.hostScene(W, H)
        try {
            assertEquals(0, differing(pixelsOf(scene.render()), initial), "the app's first frame is not its initial message")

            // Cell 9: the state the root remembered is reachable as main.App.messages.
            assertEquals("True", pyStr("hasattr(main.App, 'messages')"), "cell 9: main.App.messages does not exist after the first frame")
            // Cell 10.
            cell("message_backup = main.App.messages.getValue()")
            assertEquals("<class 'str'>", pyStr("type(message_backup)"), "the state did not read back as a str")
            assertEquals(INITIAL_MESSAGE, pyStr("message_backup"), "cell 10 read something other than what is on screen")

            // Cell 12.
            cell("main.App.messages.setValue('$CHANGED_MESSAGE')")
            val (afterWrite, writeFrames) = framesUntil({ scene.render() }) { it.contentEquals(changed) }
            assertEquals(
                0, differing(afterWrite, changed),
                "cell 12: after $writeFrames frame(s) the screen does not show the message the notebook wrote",
            )
            assertEquals(2, pyInt("main.calls['App']"), "the write did not recompose the root exactly once")

            // Cell 13.
            cell("main.App.messages.setValue(message_backup)  # 복원")
            val (afterRestore, restoreFrames) = framesUntil({ scene.render() }) { it.contentEquals(initial) }
            assertEquals(
                0, differing(afterRestore, initial),
                "cell 13: after $restoreFrames frame(s) the screen does not show the restored message",
            )
            assertEquals(INITIAL_MESSAGE, pyStr("main.App.messages.getValue()"))
            assertEquals(INITIAL_MESSAGE, pyStr("main.App.messages.value"), ".value is not an alias of getValue()")
        } finally {
            scene.close()
        }
    }

    /**
     * Cells 15-16, the scenario issue #26 is about: a cell redeclares the root and the screen shows it.
     * The notebook's `main.App.update(NewUI)` is gone (pythonx-compose INTENT 5.1); `@app` on the new
     * function is the whole of it. Between the cell and the frame the test does only what a frame clock
     * does ([NotebookHost.nextFrame]).
     */
    @Test
    fun cell15to16_redeclaringTheRootChangesTheScreenWithNoUpdateCall() {
        val expected = NotebookHost.controlPixels(W, H) { NewUiControl() }
        val scene = NotebookHost.hostScene(W, H)
        try {
            scene.render()
            val seen = NotebookHost.redeclare(scene, CELL_05 + CELL_15, expected)
            println("notebook cell 15: ${seen.describe()}")
            assertTrue(inkOf(expected) > 0, "the Kotlin control drew nothing")
            assertTrue(seen.matchesExpected, "cell 15: the redeclared root is not on screen: ${seen.describe()}")
            assertEquals("True", pyStr("_nb_rt.app_root.value is NewUI"), "@app did not make NewUI the root")
            assertEquals(1, pyInt("main.calls['App']"), "the replaced root ran again")
        } finally {
            scene.close()
        }
    }

    /**
     * **Negative check, Python half.** `@app` replaced by a stub that returns the function without
     * writing `app_root` -- the root-replacement wiring disabled on the pythonx side. The same
     * observation [cell15to16_redeclaringTheRootChangesTheScreenWithNoUpdateCall] asserts on must now
     * report that nothing changed; if it did not, that test could not fail.
     */
    @Test
    fun negative_withAppStubbedOutTheRedeclarationDoesNotReachTheScreen() {
        val expected = NotebookHost.controlPixels(W, H) { NewUiControl() }
        val scene = NotebookHost.hostScene(W, H)
        try {
            scene.render()
            cell("_nb_rt.app = lambda root: root")
            val seen = NotebookHost.redeclare(scene, CELL_05 + CELL_15, expected)
            println("notebook negative (stubbed @app): ${seen.describe()}")
            assertFalse(seen.matchesExpected, "with @app stubbed out the screen still showed the redeclared root")
            assertEquals(0, seen.changedPixels, "with @app stubbed out the screen changed anyway: ${seen.describe()}")
        } finally {
            scene.close()
        }
    }

    /**
     * **Negative check, host half.** The host given the root *function* once (`PythonWidget(root =
     * main.App)`) instead of the state that holds it -- the root-replacement wiring disabled on the
     * Kotlin side. A redeclaration then writes a state no composition reads, and the screen stays.
     */
    @Test
    fun negative_aHostHandedTheRootOnceDoesNotFollowARedeclaration() {
        val expected = NotebookHost.controlPixels(W, H) { NewUiControl() }
        val fixedRoot = Python3.import("main").getAttr("App")
        val scene = ImageComposeScene(width = W, height = H, density = Density(1f)) { PythonWidget(root = fixedRoot) }
        try {
            scene.render()
            val seen = NotebookHost.redeclare(scene, CELL_05 + CELL_15, expected)
            println("notebook negative (fixed root): ${seen.describe()}")
            assertFalse(seen.matchesExpected, "a host that never reads app_root still showed the redeclared root")
            assertEquals(0, seen.changedPixels, "a host that never reads app_root changed anyway: ${seen.describe()}")
            assertEquals("True", pyStr("_nb_rt.app_root.value is NewUI"), "the redeclaration itself did not happen")
        } finally {
            scene.close()
            fixedRoot.close()
        }
    }

    internal companion object {
        const val W = 360
        const val H = 120

        /** `main.py`'s `INITIAL_MESSAGE`. */
        const val INITIAL_MESSAGE = "안녕하세요, Python 앱입니다."

        /** Cell 12's text. */
        const val CHANGED_MESSAGE = "UI 상태를 변경해보는 테스트입니다."

        /**
         * Cell 5 in the decided spellings: `app` beside `Composable`; no `remember_saveable`,
         * `DefaultCoroutineScope`, `MainCoroutineScope` (undecided, pythonx-compose INTENT 4.1 and SPEC
         * section 8) and no lower-case `modifier` (INTENT 5.4).
         */
        val CELL_05 = """
            from pythonx.compose.runtime import Composable, app
            from pythonx.compose.material3 import Icon, DefaultIcons, Text, TextField
            from pythonx.compose.material3 import Column, Row, Button, Card, Spacer
            from pythonx.compose.ui import Modifier, Alignment
            from pythonx.compose.layout import Arrangement

        """.trimIndent() + "\n"

        /** Cells 15-16: `NewUI`, declared with `@app` instead of `main.App.update(NewUI)`; `color=` is `Color(...)` (INTENT 5.5). */
        val CELL_15 = """
            from pythonx.compose.ui.graphics import Color

            @app
            @Composable
            def NewUI():
                Button(
                    on_click=lambda: None,
                    content=lambda: {
                        Text(f"버튼이 하나만 있는 화면으로 바꿔보자", color=Color(0xFFFFFFFF))
                    }
                )
        """.trimIndent()
    }
}

@androidx.compose.runtime.Composable
private fun NewUiControl() {
    Button(onClick = {}) { Text("버튼이 하나만 있는 화면으로 바꿔보자", color = Color(0xFFFFFFFF)) }
}
