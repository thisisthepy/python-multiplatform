package fixture.compose

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Bitmap
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonxAdapter
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.UpcallTable
import java.awt.Canvas
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A Python callback, driven by a real event** -- the third wall `ebe3365f` left standing.
 *
 * ### What was missing, and why it mattered
 *
 * Every render proof in this module so far is a *static rasterisation*: build a scene, call
 * `render()`, count pixels. That proves a composable was reached and drew, and it can say nothing at
 * all about a slot whose whole purpose is to be **called back** -- `Checkbox.onCheckedChange`,
 * `Slider.onValueChange`, `TextField.onValueChange`. `ComposableBindingTest` proves those slots are
 * *described* (`kotlin.Function1(kotlin.Boolean)->kotlin.Unit`) and `FunctionSlotBindingTest` proves
 * a Python callable can be *put* in one, and nothing anywhere proved one is ever *invoked*.
 *
 * ### What delivers the event
 *
 * `ImageComposeScene.sendPointerEvent`, which is on the same class the rasterisation tests already
 * use -- so this needs no new harness, no window and no display. A press and a release at the
 * component's centre is what Compose turns into a click, and `Checkbox` is the smallest bound
 * declaration that reacts to one with a value rather than with a `Unit`: the argument it hands back
 * is the thing to assert, because a callback invoked with the wrong value and a callback invoked at
 * all are different claims.
 *
 * ### Why the redraw is a second scene
 *
 * `Checkbox` is stateless: what it draws comes from its `checked` argument, which is Python's to
 * hold. Clicking it invalidates Compose's own interaction state, not `PythonComposition`'s -- so the
 * scene that received the click will not re-run the Python body, and asserting on its second frame
 * would be asserting on nothing. A **fresh** scene over the same body reads the Python variable the
 * callback wrote, which is the honest way round: the pixels prove what Python holds now, and the
 * `_events` list proves how it got there.
 */
class CallbackDrivenRenderTest {

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
        UpcallTable.clear()
    }

    /**
     * **The claim.** A `lambda v: ...` written in Python, in `Checkbox`'s `onCheckedChange` slot,
     * invoked by a pointer press and release, with `True` -- and the state it wrote showing up as
     * different pixels the next time the same body composes.
     *
     * Four assertions, and each one fails for a different reason:
     *
     * | assertion | what it rules out |
     * |---|---|
     * | the unclicked control draws something | the checkbox never composed, so nothing was there to click |
     * | `_events == [True]` after the click | the callback was never invoked, or was invoked with the wrong value -- a `Function1` handed a `Boolean` it forwards as `None` fails here |
     * | the two renders differ | Python holds the new state but nothing can see it |
     * | the untouched control does **not** differ | the difference came from the scene being built twice rather than from the click |
     */
    @Test
    fun clickingACheckboxInvokesThePythonCallbackAndTheStateItWroteRedraws() {
        Python3.exec(
            """
            _cb_events = []
            _cb_state = [False]

            def _cb_toggle(value):
                _cb_events.append(value)
                _cb_state[0] = value
            """.trimIndent(),
        )
        val unchecked = pixelsOf(CHECKBOX)
        assertTrue(inkOf(unchecked) > 0, "the Checkbox never composed, so there was nothing to click")

        // The control first, and before the click: composing the same body twice must not, on its
        // own, change a single pixel.
        assertEquals(0, differing(unchecked, pixelsOf(CHECKBOX)), "the render is not deterministic")

        clickCentre(CHECKBOX)
        // Asserted in Python, because the value to check is a Python one: the boundary carries no
        // reader for an arbitrary expression, and a Kotlin-side copy of `_cb_events` would be a
        // second thing that can disagree with the list the callback actually appended to.
        Python3.exec("assert _cb_events == [True], repr(_cb_events)")
        val checked = pixelsOf(CHECKBOX)
        val moved = differing(unchecked, checked)
        println(
            "compose input: Checkbox click -> on_checked_change(True), " +
                "ink ${inkOf(unchecked)} -> ${inkOf(checked)} px, $moved pixels changed",
        )
        assertTrue(
            moved > 100,
            "only $moved pixels changed after the click, so the state the callback wrote never reached Compose",
        )
    }

    /**
     * The negative control, stated apart so that the test above cannot pass by accident: a press and
     * a release **outside** the component invoke nothing. Without this, a callback invoked by
     * composition rather than by the click would satisfy every assertion above.
     */
    @Test
    fun aClickThatMissesTheComponentInvokesNothing() {
        Python3.exec(
            """
            _cb_events = []
            _cb_state = [False]

            def _cb_toggle(value):
                _cb_events.append(value)
                _cb_state[0] = value
            """.trimIndent(),
        )
        clickAt(CHECKBOX, Offset(SCENE - 2f, SCENE - 2f))
        Python3.exec("assert _cb_events == [], 'a click that missed the checkbox invoked its callback: ' + repr(_cb_events)")
    }

    /**
     * The same event, one declaration further on: `Switch` is a different composable with the same
     * `(Boolean) -> Unit` shape, so a mechanism that only worked for `Checkbox` -- a lucky slot
     * index, a `$default` mask that happened to line up -- fails here.
     */
    @Test
    fun theSameCallbackShapeIsDrivenOnASecondDeclaration() {
        Python3.exec(
            """
            _cb_events = []
            _cb_state = [False]

            def _cb_toggle(value):
                _cb_events.append(value)
                _cb_state[0] = value
            """.trimIndent(),
        )
        val before = pixelsOf(SWITCH)
        assertTrue(inkOf(before) > 0, "the Switch never composed")
        clickCentre(SWITCH)
        Python3.exec("assert _cb_events == [True], repr(_cb_events)")
        val after = pixelsOf(SWITCH)
        val moved = differing(before, after)
        println(
            "compose input: Switch click -> on_checked_change(True), " +
                "ink ${inkOf(before)} -> ${inkOf(after)} px, $moved pixels changed",
        )
        // Deliberately not an ink comparison: the thumb slides from one end of the track to the
        // other, which is a large change that leaves the total almost exactly where it was.
        assertTrue(moved > 100, "only $moved pixels changed, so the switch drew the same state twice")
    }

    /**
     * **The fourth wall, and the one `7d6c0a1` in `pythonx-compose` left standing:** a
     * `(String) -> Unit` slot, driven by real key input rather than by a pointer.
     *
     * ### Why this needs a key event and not another `sendPointerEvent`
     *
     * `Checkbox` and `Switch` above are driven end to end by a click alone -- the value the callback
     * receives is derived from the control's own toggled state, not from anything the test supplies.
     * `TextField`'s `onValueChange` carries the *typed* string, so the only way to drive it honestly
     * is to deliver characters, which is a different plumbing: `ImageComposeScene` exposes a second
     * entry point for it, `sendKeyEvent`, and it was never exercised anywhere in this module.
     *
     * ### What makes a character actually commit, found by decompiling (no sources jar for this
     * Compose version): three dead ends before this one
     *
     * `androidx.compose.ui.input.key.KeyEvent(key, type, codePoint, ...)` is the public factory for a
     * synthetic key event, and the first three shapes tried with it all reached the focused node --
     * `scene.sendKeyEvent` returned `true` for a bare `Modifier.onKeyEvent` -- and still left
     * `TextField`'s `onValueChange` uncalled, with `sendKeyEvent` itself returning `false`:
     *
     * 1. `KeyEventType.KeyDown` with a codepoint, on the theory that a "key down" is what typing is.
     *    `TextFieldKeyInput_desktopKt.isTypedEvent` (decompiled) does not look at this event's own
     *    `type` field at all for the character-commit path.
     * 2. Explicit focus via `FocusRequester.requestFocus()` in a `SideEffect`, in case the click's
     *    tap-to-focus gesture was the gap. Confirmed *not* the gap: `Modifier.onFocusChanged` on the
     *    same field reported `isFocused` going `false -> true`, and the key was still refused.
     * 3. More render passes, in case some `LaunchedEffect`-driven input-session start just hadn't run
     *    yet. A bare `LaunchedEffect(Unit) { }` in the same harness does run, so that theory falls too.
     *
     * `isTypedEvent-ZmokQxo`'s decompiled body is what actually decides it: it calls
     * `AwtEvents_desktopKt.getAwtEventOrNull` on the event, and returns `false` outright if that is
     * `null` or its own AWT `getID()` is not `400` (`KEY_TYPED`) -- checking the *wrapped native AWT
     * event*, never the Compose-level `type` this test's first three attempts were varying. The public
     * `KeyEvent(...)` factory takes a `nativeEvent: Any?` parameter defaulting to `null`, which is
     * exactly the gap: every synthetic event built without one is invisible to this check, regardless
     * of `type` or focus. [typedKey] supplies a real `java.awt.event.KeyEvent(..., KEY_TYPED, ...,
     * keyChar)` there, which is what `sendPointerEvent`-only attempts have no equivalent gap for --
     * that boundary takes structured Compose values directly and never asks whether something upstream
     * produced them.
     *
     * (`AwtKeyEvent(...).toComposeEvent()`, the obvious way to build that wrapped pair, does not
     * compile from this module: it is `internal` to `ui-desktop`'s own compilation -- public in the
     * class file, since Kotlin `internal` erases to public JVM bytecode, but rejected by the Kotlin
     * compiler's module check. [typedKey] builds the same shape by hand through the public factory
     * instead.)
     *
     * ### Why the field is clicked before it is typed into
     *
     * A key event has no target of its own -- `ImageComposeScene.sendKeyEvent` delivers it to whatever
     * currently holds focus, the same way a real window would. `BasicTextField` (which M3's `TextField`
     * wraps) requests focus on press the same way `Checkbox` and `Switch` already register clicks, so
     * the press-and-release this class already uses for a toggle is reused here for exactly the same
     * reason: to establish, mechanically, that this is the field the keystroke reaches, not composition
     * order or the only focusable node in the scene.
     *
     * ### Why this drives one keystroke, not two, inside a single scene
     *
     * A second keystroke inside the *same* scene was tried first, on the theory that it would prove
     * more (accumulation, not just a single character crossing). It fails, and not on anything this
     * class's mechanism is responsible for: M3's `TextField(value: String, onValueChange, ...)` copies
     * its *own* `value` parameter back onto the field's internal editable state on every recomposition
     * -- `textFieldValueState.copy(text = value)`, the standard "controlled" pattern -- and
     * `PythonComposition`'s [source] is only `exec`ed when *its own* composable scope recomposes.
     * Nothing about a Python callback mutating a plain Python list is visible to Compose's snapshot
     * system, so `PythonComposition` never recomposes on its own after `_tf_on_change` runs, and the
     * `value` fed to `TextField` stays whatever it was at the scene's first composition for the scene's
     * whole life. Confirmed by driving `'h'` then `'i'` in one scene: `_tf_events` comes back
     * `['h', 'i']`, not `['h', 'hi']` -- each keystroke lands on the *original* empty value, because
     * nothing ever told `TextField` the first one had happened. A parallel probe with a plain Kotlin
     * `mutableStateOf` in place of the Python list (not committed -- built to isolate this, then
     * discarded once the cause was confirmed) accumulates correctly in exactly the same scene, which is
     * what places the cause in the boundary between Python state and Compose's snapshot system, not in
     * key-event delivery. [twoKeystrokesAccumulateAcrossTwoFreshScenes] below shows the shape that
     * *does* work: the same one this class already uses for `Checkbox` and `Switch`, extended by one
     * more scene.
     */
    @Test
    fun typingIntoATextFieldInvokesThePythonCallbackWithTheStringAndTheNextRenderShowsIt() {
        Python3.exec(
            """
            _tf_events = []
            _tf_state = [""]

            def _tf_on_change(value):
                _tf_events.append(value)
                _tf_state[0] = value
            """.trimIndent(),
        )
        val empty = pixelsOf(TEXT_FIELD, width = FIELD_WIDTH, height = FIELD_HEIGHT)
        assertTrue(inkOf(empty) > 0, "the TextField never composed, so there was nothing to type into")

        typeOneCharacterInAFreshScene('h')

        // Asserted here, in Python, for the same reason `_cb_events` is asserted in Python above: the
        // boundary carries no reader for an arbitrary Python list, and a Kotlin-side copy would be a
        // second thing that could disagree with what the callback actually appended.
        Python3.exec("assert _tf_events == ['h'], repr(_tf_events)")
        Python3.exec("assert _tf_state[0] == 'h', repr(_tf_state[0])")

        val typed = pixelsOf(TEXT_FIELD, width = FIELD_WIDTH, height = FIELD_HEIGHT)
        val moved = differing(empty, typed)
        println(
            "compose input: TextField keystroke 'h' -> on_value_change, " +
                "ink ${inkOf(empty)} -> ${inkOf(typed)} px, $moved pixels changed",
        )
        assertTrue(
            moved > 10,
            "only $moved pixels changed after typing, so the string the callback wrote never reached the render",
        )
    }

    /**
     * The multi-character claim [typingIntoATextFieldInvokesThePythonCallbackWithTheStringAndTheNextRenderShowsIt]'s
     * KDoc says a single scene cannot make: two keystrokes, each in its **own** fresh scene, the same
     * unit [pixelsOf] and `clickAt` already use to observe what a callback wrote. The first scene reads
     * `_tf_state[0]` as `""` and types `'h'`; the second is built *after* that scene closes, so it reads
     * `_tf_state[0]` as `'h'` -- the value the first scene's callback wrote -- and types `'i'` onto it.
     * If `PythonComposition`'s "re-`exec` on every composition" claim is what makes accumulation
     * possible at all, this is where it has to show up: `_tf_events` accumulating to `['h', 'hi']`
     * across scenes is a different claim from a single scene accumulating on its own, and the test above
     * is the evidence that the second claim does not hold.
     */
    @Test
    fun twoKeystrokesAccumulateAcrossTwoFreshScenes() {
        Python3.exec(
            """
            _tf_events = []
            _tf_state = [""]

            def _tf_on_change(value):
                _tf_events.append(value)
                _tf_state[0] = value
            """.trimIndent(),
        )

        typeOneCharacterInAFreshScene('h')
        Python3.exec("assert _tf_state[0] == 'h', repr(_tf_state[0])")

        typeOneCharacterInAFreshScene('i')

        Python3.exec("assert _tf_events == ['h', 'hi'], repr(_tf_events)")
        Python3.exec("assert _tf_state[0] == 'hi', repr(_tf_state[0])")
    }

    /**
     * One scene, built fresh from whatever `_tf_state[0]` currently holds (the same `TEXT_FIELD` source
     * every `TextField` test in this class shares): focused by a click, typed into with one
     * `sendKeyEvent`, then closed.
     *
     * Neither `_tf_state[0]` nor `_tf_events` is asserted on here -- `scene.close()` was found
     * (empirically, via a real file `Python3.exec` wrote to, since `print()` writes to the
     * interpreter's own fd and Gradle's test capture does not see it) to invoke `_tf_on_change` one
     * more time on its way out, with the value **as it was before the just-typed character**: closing
     * a scene whose `TextField` still has a focused, freshly-typed character reverts that character
     * rather than confirming it, the same way a real IME cancels an uncommitted composing segment on
     * focus loss. Both callers need what the keystroke actually produced, not what teardown reverted it
     * to, so this function snapshots `_tf_state[0]` and `_tf_events` right after the keystroke and
     * restores both right after `close()` -- which is possible because `close()`'s extra call is always
     * exactly one more list append and one more list write, never more.
     */
    private fun typeOneCharacterInAFreshScene(char: Char) {
        val scene = ImageComposeScene(width = FIELD_WIDTH, height = FIELD_HEIGHT, density = Density(1f)) {
            PythonComposition(TEXT_FIELD)
        }
        try {
            scene.render()
            // Move first, then press-and-release: the same tap shape `clickAt` uses for Checkbox and
            // Switch, reused here to focus the field before anything is typed into it.
            val focusAt = Offset(FIELD_WIDTH / 4f, FIELD_HEIGHT / 2f)
            scene.sendPointerEvent(PointerEventType.Move, focusAt)
            scene.sendPointerEvent(PointerEventType.Press, focusAt)
            scene.sendPointerEvent(PointerEventType.Release, focusAt)
            scene.render()

            scene.sendKeyEvent(typedKey(char))
            scene.render()

            // Saved apart from both lists because `close()` below appends/overwrites once more, and
            // this function's callers both need what the keystroke actually produced.
            Python3.exec("_tf_typed_value = _tf_state[0]")
            Python3.exec("_tf_events_snapshot = list(_tf_events)")
        } finally {
            scene.close()
        }
        Python3.exec("_tf_state[0] = _tf_typed_value")
        Python3.exec("_tf_events[:] = _tf_events_snapshot")
    }

    /** A synthetic key event that commits [char] the way a real keystroke would: `type` is `Unknown`
     * (what `toComposeEvent` itself maps a `KEY_TYPED` AWT event to, since `401`/`402` are the only
     * ids that become `KeyDown`/`KeyUp` there) and `nativeEvent` is a real
     * `java.awt.event.KeyEvent(..., KEY_TYPED, ..., char)` -- see this method's enclosing test's KDoc
     * for why the `nativeEvent` is load-bearing and the other four fields are not. */
    @OptIn(InternalComposeUiApi::class)
    private fun typedKey(char: Char) = KeyEvent(
        key = Key(nativeKeyCode = char.code),
        type = KeyEventType.Unknown,
        codePoint = char.code,
        nativeEvent = java.awt.event.KeyEvent(
            KEY_EVENT_SOURCE,
            java.awt.event.KeyEvent.KEY_TYPED,
            System.currentTimeMillis(),
            0,
            java.awt.event.KeyEvent.VK_UNDEFINED,
            char,
        ),
    )

    private fun clickCentre(body: String) = clickAt(body, Offset(COMPONENT_CENTRE, COMPONENT_CENTRE))

    /** Builds the scene, lays it out with one render, delivers a press and a release at [at], and
     * lets the scene go. The frame after the click is deliberately not measured; see this class's
     * KDoc for why the redraw has to be a fresh scene. */
    private fun clickAt(body: String, at: Offset) {
        val scene = ImageComposeScene(width = SCENE, height = SCENE, density = Density(1f)) {
            PythonComposition(body)
        }
        try {
            scene.render()
            // Move first: a pointer that has never been anywhere has no position for the press to be
            // *in*, and Compose's tap detector wants an enter before a press.
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at)
            scene.sendPointerEvent(PointerEventType.Release, at)
            scene.render()
        } finally {
            scene.close()
        }
    }

    /**
     * Every pixel of one render of [body], so that two renders can be compared **position by
     * position** rather than by how much ink each contains.
     *
     * The difference matters for `Switch` and would have made this test a false negative: flipping it
     * slides a thumb from one end of the track to the other and recolours both, which moves hundreds
     * of pixels while changing the *total* ink by four. Counting ink would have called that "no
     * change"; counting disagreements calls it what it is.
     */
    private fun pixelsOf(body: String, width: Int = SCENE, height: Int = SCENE): IntArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            PythonComposition(body)
        }
        try {
            val bitmap = Bitmap.makeFromImage(scene.render())
            return IntArray(width * height) { bitmap.getColor(it % width, it / width) }
        } finally {
            scene.close()
        }
    }

    private fun inkOf(pixels: IntArray): Int = pixels.count { it != BACKGROUND }

    private fun differing(a: IntArray, b: IntArray): Int = a.indices.count { a[it] != b[it] }

    private companion object {
        /** `ImageComposeScene` clears to transparent black. */
        const val BACKGROUND = 0

        /** Wide enough for material3's 48dp minimum interactive size at density 1, with room around
         * it for [aClickThatMissesTheComponentInvokesNothing] to miss into. */
        const val SCENE = 80

        /** The component's centre, not the scene's. Content is placed at the scene's top-left, so a
         * 48dp interactive box occupies `(0,0)..(48,48)` and its middle is 24 -- measured, not
         * assumed: the ink a `Checkbox` leaves in this scene spans `(14,14)..(33,33)`, which is the
         * 20dp box centred on exactly that point. Clicking the *scene's* centre lands outside it and
         * invokes nothing, which is what [aClickThatMissesTheComponentInvokesNothing] now asserts
         * deliberately. */
        const val COMPONENT_CENTRE = 24f

        /** `checked` is read out of Python on every composition, which is what makes a fresh scene
         * show what the callback wrote. */
        val CHECKBOX = """
            from androidx.compose.material3 import Checkbox
            Checkbox(_cb_state[0], on_checked_change=_cb_toggle)
        """.trimIndent()

        val SWITCH = """
            from androidx.compose.material3 import Switch
            Switch(_cb_state[0], on_checked_change=_cb_toggle)
        """.trimIndent()

        /** M3's `TextField` has no minimum-size default anywhere near `Checkbox`'s 48dp -- its own
         * default width is intrinsic content plus padding, comfortably under 240dp -- so the shared
         * [SCENE] square is too narrow to hold one without clipping the area a click needs to land in.
         * Sized generously rather than measured exactly, since -- unlike [COMPONENT_CENTRE] -- nothing
         * here depends on the field's bounds matching the scene to the pixel: [focusAt] only needs to
         * land inside it. */
        const val FIELD_WIDTH = 240
        const val FIELD_HEIGHT = 56

        /** `value` is read out of Python on every composition, the same as `CHECKBOX`'s `checked` --
         * what makes a fresh scene show what the callback wrote. */
        val TEXT_FIELD = """
            from androidx.compose.material3 import TextField
            TextField(_tf_state[0], on_value_change=_tf_on_change)
        """.trimIndent()

        /** `java.awt.event.KeyEvent`'s constructor requires a non-null source `Component`; nothing
         * about this test displays it or reads any AWT-level property off it beyond `getID()` and
         * `getKeyChar()`, so an unattached `Canvas` satisfies the constructor and nothing else. */
        val KEY_EVENT_SOURCE: Canvas = Canvas()
    }
}
