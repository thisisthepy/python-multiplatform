@file:OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)

package fixture.notebook

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.text.input.TextEditingScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface

/**
 * An offscreen scene that, unlike `ImageComposeScene`, lets a test act as the **input method**.
 *
 * ### Why not `ImageComposeScene`
 *
 * In Compose Multiplatform 1.11.1 `ImageComposeScene` builds its `ComposeScene` with
 * `CanvasLayersComposeScene(..., platformContext = <PlatformContext.Empty delegate>)` (read from
 * `ImageComposeScene.class` and `ImageComposeScene$_platformContext$1.class` in `ui-desktop-1.11.1.jar`).
 * `PlatformContext.Empty` never hands out the `PlatformTextInputMethodRequest` a focused text field
 * starts an input session with, and `ImageComposeScene` has no parameter to replace the context. Its
 * public API offers `sendKeyEvent` only -- key presses, not input-method composition.
 *
 * ### What this does instead
 *
 * The same `CanvasLayersComposeScene` `ImageComposeScene` wraps (`@InternalComposeUiApi`), drawn into a
 * Skia raster surface exactly as `ImageComposeScene.render` does, with a [PlatformContext] whose
 * `startInputMethod` records the request a focused field starts. [inputMethodEvent] then delivers an
 * input-method event the way the desktop window does: `InputMethodSession.inputMethodTextChanged` in
 * the same jar turns an AWT `InputMethodEvent` into one `request.editText { ... }` call that does
 * `commitText(committed, 1)` and then, if there is composing text, `setComposingText(composing, 1)`.
 * That is reproduced here call for call. What is *not* exercised is the AWT layer above it (decoding an
 * `InputMethodEvent`'s attributed text into those two strings), which needs a window.
 */
internal class InputMethodScene(
    private val width: Int,
    private val height: Int,
    content: @Composable () -> Unit,
) : AutoCloseable {

    private val requests = ArrayList<PlatformTextInputMethodRequest>()

    /** The input session a focused text field started, if one is open. */
    val activeRequest: PlatformTextInputMethodRequest? get() = requests.lastOrNull()

    private val focusedWindow = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = IntSize(width, height)
    }

    private val context = object : PlatformContext by PlatformContext.Empty() {
        override val windowInfo: WindowInfo get() = focusedWindow

        override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
            requests += request
            try {
                awaitCancellation()
            } finally {
                requests -= request
            }
        }
    }

    val scene: ComposeScene = CanvasLayersComposeScene(
        Density(1f),
        LayoutDirection.Ltr,
        IntSize(width, height),
        Dispatchers.Unconfined,
        context,
    )

    private val surface = Surface.makeRasterN32Premul(width, height)

    init {
        scene.setContent(content)
    }

    /** `ImageComposeScene.render()`'s body: clear, draw the scene at time zero, snapshot. */
    fun render(): Image {
        surface.canvas.clear(0)
        scene.render(surface.canvas.asComposeCanvas(), 0L)
        return surface.makeImageSnapshot()
    }

    /** A primary-button click at [at], as `ImageComposeScene.sendPointerEvent` delivers one. */
    fun click(at: Offset) {
        scene.sendPointerEvent(PointerEventType.Move, at)
        scene.sendPointerEvent(PointerEventType.Press, at)
        scene.sendPointerEvent(PointerEventType.Release, at)
    }

    /** Keyboard-style focus traversal into the first focusable, the fallback when a click did not focus. */
    fun focusFirst(): Boolean = scene.focusManager.takeFocus(FocusDirection.Next)

    /**
     * One input-method event: [committed] text committed and [composing] text left in composition --
     * the two strings the desktop window reads from an AWT `InputMethodEvent`.
     */
    fun inputMethodEvent(committed: String, composing: String) {
        val request = checkNotNull(activeRequest) { "no text input session is open: the field is not focused" }
        val edit = checkNotNull(request.editText) { "the input session offers no editText" }
        val event: TextEditingScope.() -> Unit = {
            commitText(committed, 1)
            if (composing.isNotEmpty()) setComposingText(composing, 1)
        }
        edit(event)
    }

    override fun close() {
        scene.close()
        surface.close()
    }
}
