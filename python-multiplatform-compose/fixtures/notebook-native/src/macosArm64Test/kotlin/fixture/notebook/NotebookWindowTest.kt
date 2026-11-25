package fixture.notebook

import fixture.notebook.NotebookHost.cell
import fixture.notebook.NotebookHost.pyInt
import fixture.notebook.NotebookRootTest.Companion.CELL_05
import fixture.notebook.NotebookRootTest.Companion.CELL_15
import fixture.notebook.NotebookRootTest.Companion.CHANGED_MESSAGE
import fixture.notebook.NotebookRootTest.Companion.INITIAL_MESSAGE
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import org.thisisthepy.compose.window.macos.MacosWindowPlatform
import org.thisisthepy.compose.window.window
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import python.multiplatform.compose.PythonAppView
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #191, the Kotlin/Native desktop path: the notebook's application in a real window, the one the fork
 * provides (`org.thisisthepy.compose.window:native-macos`, AppKit and Metal, no JVM), rather than the
 * offscreen `ImageComposeScene` the other notebook-native tests draw into (SPEC B-10a).
 *
 * The host is the same line as everywhere else, `PythonAppView(module = "pythonx.compose.runtime",
 * attribute = "app_root")`, handed to the window as its content. What the window shows is read the way
 * a screen reader reads it: the window walks its own accessibility elements in process (a reader
 * outside the process needs a trust a CI runner has not given). So "on screen" here means "in the
 * window the system shows, as the system describes it", not pixels compared with a control.
 *
 * - `import main` puts the app's initial message in the window, and the root is composed once.
 * - Cell 12's state write reaches the window with no host call, and the text it replaced is gone.
 * - Cell 15's `@app` redeclaration replaces the root in the window.
 * - Every frame the window presented was drawn at the window's size.
 *
 * Each "reaches the window" is checked against its own absence first (the changed message is not in
 * the window before cell 12, the redeclared root's text not before cell 15), so a reader that saw
 * every string, or none, fails the test.
 */
class NotebookWindowTest {

    @BeforeTest
    fun host() = NotebookHost.install()

    @Test
    fun theNotebookAppRunsInTheForksAppKitWindow() {
        val application = NSApplication.sharedApplication()
        // An executable outside a bundle is not, by default, something the system shows in front.
        application.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
        val platform = MacosWindowPlatform {
            PythonAppView(module = "pythonx.compose.runtime", attribute = "app_root")
        }
        val listener = object : WindowListener {
            override fun onEvent(event: WindowEvent) {}
        }
        assertTrue(platform.open(WindowConfig(TITLE, 520, 360), listener), "the fork's AppKit window did not open")
        application.activateIgnoringOtherApps(true)
        try {
            val firstFrames = turnsUntil(platform) { (platform.resizeStats?.presented ?: 0L) >= 10L }
            val presented = platform.resizeStats?.presented ?: 0L
            assertTrue(presented >= 10L, "the window presented $presented frame(s) in $firstFrames turns")

            // Asking is what tells the window a reader is listening; it describes itself from the
            // next frame on.
            platform.accessibilitySnapshot()
            val initialTurns = turnsUntil(platform) { INITIAL_MESSAGE in readerTexts(platform) }
            assertTrue(
                INITIAL_MESSAGE in readerTexts(platform),
                "main.App's initial message is not in the window after $initialTurns turns; a reader found ${readerTexts(platform)}",
            )
            assertEquals(1, pyInt("main.calls['App']"), "the root was not composed exactly once in the window")
            assertFalse(CHANGED_MESSAGE in readerTexts(platform), "cell 12's text is in the window before cell 12 ran")

            // Cell 12: the notebook writes the state the window shows.
            cell("main.App.messages.setValue('$CHANGED_MESSAGE')")
            val writeTurns = turnsUntil(platform) { CHANGED_MESSAGE in readerTexts(platform) }
            val afterWrite = readerTexts(platform)
            assertTrue(CHANGED_MESSAGE in afterWrite, "cell 12's write is not in the window after $writeTurns turns; a reader found $afterWrite")
            assertFalse(INITIAL_MESSAGE in afterWrite, "the message cell 12 replaced is still in the window")
            assertEquals(2, pyInt("main.calls['App']"), "the write did not recompose the root exactly once")

            // Cells 15-16: `@app` on a new function replaces the root, with no update call.
            assertFalse(NEW_UI_TEXT in readerTexts(platform), "the redeclared root's text is in the window before cell 15 ran")
            cell(CELL_05 + CELL_15)
            val redeclareTurns = turnsUntil(platform) { NEW_UI_TEXT in readerTexts(platform) }
            val afterRedeclare = readerTexts(platform)
            assertTrue(NEW_UI_TEXT in afterRedeclare, "cell 15's root is not in the window after $redeclareTurns turns; a reader found $afterRedeclare")
            assertFalse(CHANGED_MESSAGE in afterRedeclare, "the replaced root's text is still in the window")

            val stats = platform.resizeStats
            println(
                "notebook window: ${stats?.presented} frames presented, ${stats?.stale} at another size; " +
                    "turns: first frames $firstFrames, initial $initialTurns, write $writeTurns, redeclare $redeclareTurns",
            )
            assertEquals(0L, stats?.stale ?: -1L, "a frame was presented at a size other than the window's")
        } finally {
            platform.close()
        }
    }

    /** Every name, label and value a reader is told about the elements of this test's window. */
    private fun readerTexts(platform: MacosWindowPlatform): List<String> =
        platform.accessibilitySnapshot().window(TITLE).drop(1)
            .flatMap { listOf(it.title, it.label, it.value) }
            .filter { it.isNotEmpty() }

    /**
     * Turns of the window's loop, each asking for a frame, until [done] or [MAX_TURNS]. Returns how
     * many it took. A turn waits up to 16 ms for the system's events, so [MAX_TURNS] is about 4 s.
     */
    private fun turnsUntil(platform: MacosWindowPlatform, done: () -> Boolean): Int {
        var turns = 0
        while (turns < MAX_TURNS && !done()) {
            platform.requestFrame()
            platform.pump(16)
            turns++
        }
        return turns
    }

    private companion object {
        const val TITLE = "notebook-native window"
        const val MAX_TURNS = 240

        /** The text inside cell 15's `NewUI` button. */
        const val NEW_UI_TEXT = "버튼이 하나만 있는 화면으로 바꿔보자"
    }
}
