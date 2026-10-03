package fixture.compose

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonxAdapter
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **`TextField(state = ...)` from Python** (issue #73): the state-based text field of Compose 1.11, the
 * one pythonx-compose's `TextField` design uses so that typing stays inside Compose.
 *
 * Everything here is reached through the walk under Kotlin names, with no fixture function between
 * Python and Compose:
 *
 * | Python | Kotlin |
 * |---|---|
 * | `TextFieldState()`, `TextFieldState('seed')` | the constructor `TextFieldState(initialText = "", initialSelection = TextRange(...))`, outside any composition |
 * | `rememberTextFieldState('abc')` | the `@Composable` of that name, inside one |
 * | `s.text` | the `CharSequence` property, read as a Python `str` |
 * | `s.setTextAndPlaceCursorAtEnd(...)`, `s.clearText()` | the two extension functions, as methods of the state's proxy |
 * | `TextField(state=s)` | material3's state overload |
 *
 * ### Red before the fix
 *
 * `from androidx.compose.foundation.text.input import TextFieldState` (or `rememberTextFieldState`)
 * finds no binding: the fixture did not walk the package, and once it does, the walker dropped the
 * constructor (a value-class parameter compiles to a synthetic bridge) and declined `text`
 * (`kotlin.CharSequence` had no boundary type). Each test therefore fails at its first `exec` with an
 * `ImportError`/`AttributeError`/`TypeError` from Python -- never at a pixel assertion, which is what
 * tells that red from a rendering regression.
 */
class TextFieldStateRenderTest {

    @BeforeTest
    fun installProducers() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        UpcallTable.install(ArtifactTable.fragments)
        check(UpcallBootstrap.publishToGlobals()) { "UpcallBootstrap.publishToGlobals() failed" }
        PythonxAdapter.install(PYTHONX_RAW_VALUE_CLASSES)
    }

    @AfterTest
    fun cleanup() {
        Python3.exec(
            """
            for _name in [n for n in globals() if n.startswith('_tfs')]:
                del globals()[_name]
            """.trimIndent(),
        )
        UpcallTable.clear()
    }

    /**
     * Made outside a composition, both ways (all defaults left out, and `initialText` given), read back
     * as `str`, written through the two extensions -- and drawn: the same `TextField(state=...)` body
     * over the empty state and over the written one gives different pixels.
     */
    @Test
    fun aStateMadeOutsideACompositionIsReadAsStrWrittenByItsExtensionsAndDrawn() {
        Python3.exec(
            """
            from androidx.compose.foundation.text.input import TextFieldState

            _tfs_empty = TextFieldState()
            assert type(_tfs_empty)._kotlin_type_name == 'androidx.compose.foundation.text.input.TextFieldState', \
                type(_tfs_empty)._kotlin_type_name
            assert type(_tfs_empty.text) is str, type(_tfs_empty.text)
            assert _tfs_empty.text == '', repr(_tfs_empty.text)

            _tfs_seeded = TextFieldState('seed')
            assert _tfs_seeded.text == 'seed', repr(_tfs_seeded.text)
            _tfs_seeded.clearText()
            assert _tfs_seeded.text == '', repr(_tfs_seeded.text)

            _tfs = TextFieldState()
            _tfs.setTextAndPlaceCursorAtEnd('hello from python')
            assert type(_tfs.text) is str and _tfs.text == 'hello from python', repr(_tfs.text)
            """.trimIndent(),
        )

        val empty = pixelsOf(textFieldOver("_tfs_empty"))
        val written = pixelsOf(textFieldOver("_tfs"))
        val moved = differing(empty, written)
        println("compose TextFieldState: ink empty ${inkOf(empty)} -> written ${inkOf(written)} px, $moved pixels differ")
        assertTrue(inkOf(empty) > 0, "TextField(state=...) never composed")
        assertTrue(moved > 10, "only $moved pixels differ, so the text written into the state never reached the field")
    }

    /**
     * The `initialSelection` slot: `TextRange` is a value class whose constructor is `internal`, so it
     * crosses as a handle made by its walked factory `TextRange(index: Int)` -- separate from the test
     * above so a failure here says it is this slot.
     *
     * Spelled by its table key, `TextRange__Int`. The bare name `TextRange` is not the factory's overload
     * set in Python: `TextRange.Zero` (a companion constant) makes `androidx.compose.ui.text.TextRange` a
     * module, and that module is what `from ... import TextRange` finds -- observed as
     * "'_PmModule_androidx_compose_ui_text_TextRange' object is not callable". That collision predates
     * issue #73 and is not this test's subject.
     */
    @Test
    fun theInitialSelectionCrossesAsATextRangeHandle() {
        Python3.exec(
            """
            from androidx.compose.foundation.text.input import TextFieldState
            from androidx.compose.ui.text import TextRange__Int

            _tfs_selected = TextFieldState('hello', TextRange__Int(2))
            assert _tfs_selected.text == 'hello', repr(_tfs_selected.text)
            _tfs_kw = TextFieldState(initialText='kw', initialSelection=TextRange__Int(0))
            assert _tfs_kw.text == 'kw', repr(_tfs_kw.text)
            """.trimIndent(),
        )
    }

    /**
     * Made inside a composition by `rememberTextFieldState`, drawn by `TextField(state=...)` in the same
     * body, then written from Python **outside** the composition: the state is Compose snapshot state,
     * so the next frame of the *same* scene shows the new text with no host call and no Python body
     * re-run being needed. Every value the body saw is the one remembered state (each reads the written
     * text).
     */
    @Test
    fun aRememberedStateIsDrawnAndAWriteFromPythonShowsOnTheNextFrame() {
        Python3.exec("_tfs_seen = []")
        val scene = ImageComposeScene(width = FIELD_WIDTH, height = FIELD_HEIGHT, density = Density(1f)) {
            PythonComposition(REMEMBERED)
        }
        val before: IntArray
        val after: IntArray
        try {
            before = pixelsOf(scene.render())
            Python3.exec(
                """
                assert len(_tfs_seen) >= 1, _tfs_seen
                assert type(_tfs_seen[0].text) is str and _tfs_seen[0].text == 'abc', repr(_tfs_seen[0].text)
                _tfs_seen[0].setTextAndPlaceCursorAtEnd('a much longer line of text')
                """.trimIndent(),
            )
            Snapshot.sendApplyNotifications()
            after = pixelsOf(scene.render())
            Python3.exec(
                """
                assert all(_s.text == 'a much longer line of text' for _s in _tfs_seen), \
                    [_s.text for _s in _tfs_seen]
                """.trimIndent(),
            )
        } finally {
            scene.close()
        }
        val moved = differing(before, after)
        println("compose rememberTextFieldState: ink ${inkOf(before)} -> ${inkOf(after)} px, $moved pixels differ")
        assertTrue(inkOf(before) > 0, "TextField(state=rememberTextFieldState(...)) never composed")
        assertTrue(moved > 10, "only $moved pixels differ, so the write from Python never reached the next frame")
    }

    private fun textFieldOver(stateName: String): String = """
        from androidx.compose.material3 import TextField
        TextField(state=$stateName)
    """.trimIndent()

    private fun pixelsOf(body: String): IntArray {
        val scene = ImageComposeScene(width = FIELD_WIDTH, height = FIELD_HEIGHT, density = Density(1f)) {
            PythonComposition(body)
        }
        try {
            return pixelsOf(scene.render())
        } finally {
            scene.close()
        }
    }

    private fun pixelsOf(image: Image): IntArray {
        val bitmap = Bitmap.makeFromImage(image)
        return IntArray(bitmap.width * bitmap.height) { bitmap.getColor(it % bitmap.width, it / bitmap.width) }
    }

    private fun inkOf(pixels: IntArray): Int = pixels.count { it != 0 }

    private fun differing(a: IntArray, b: IntArray): Int {
        assertEquals(a.size, b.size)
        return a.indices.count { a[it] != b[it] }
    }

    private companion object {
        /** Wide enough for material3's `TextField` and a line of text at density 1. */
        const val FIELD_WIDTH = 280
        const val FIELD_HEIGHT = 64

        val REMEMBERED = """
            from androidx.compose.foundation.text.input import rememberTextFieldState
            from androidx.compose.material3 import TextField
            _tfs_state = rememberTextFieldState('abc')
            _tfs_seen.append(_tfs_state)
            TextField(state=_tfs_state)
        """.trimIndent()
    }
}
