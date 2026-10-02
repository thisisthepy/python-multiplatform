package fixture.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.ui.Modifier
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.types.basic.PyFloat
import python.multiplatform.ffi.types.basic.PyString

/**
 * `Modifier.dragAndDropSource`, with Python told where a drag started.
 *
 * ### Why this is no longer `pythonPointerInput`'s technique
 *
 * Up to Compose 1.6 `dragAndDropSource` took a `suspend DragAndDropSourceScope.() -> Unit` block --
 * a `PointerInputScope` -- so this fixture forwarded every raw pointer event exactly as
 * [pythonPointerInput] does. Compose 1.11 removed that overload. The only public shapes left are
 * `dragAndDropSource(transferData: (Offset) -> DragAndDropTransferData?)` and the same with a
 * `drawDragDecoration`; gesture detection is internal (`DragAndDropSourceDefaults.DefaultStartDetector`:
 * a primary-button mouse drag, or a long press for touch). So the Python callback now sees the one
 * event the library still hands out: the drag start, as `("start", x, y)`.
 *
 * ### What is exercised here, and what is not
 *
 * A real pointer drag, recognised by Compose's own start detector, reaches a synchronous Python
 * callback. Returning `null` declines the transfer, so no OS-level drag session (`DragAndDropTransferData`
 * wrapping a `java.awt.datatransfer.Transferable`) is ever started -- that remains a different,
 * unmeasured claim. [drawDragDecoration] is a no-op for the same reason.
 *
 * ### Lifetime -- not handled, same as [pythonDraggable]
 *
 * The old suspend block gave a `finally` that ran on node detachment. The `transferData` lambda has no
 * such hook, so [onEvent] is held by the modifier and never closed here.
 */
@OptIn(ExperimentalFoundationApi::class)
fun pythonDragAndDropSource(modifier: Modifier, onEvent: PyObject): Modifier = modifier.dragAndDropSource(
    drawDragDecoration = { /* not exercised -- see this function's KDoc */ },
) { offset ->
    val typeArg = PyString.from("start")
    val xArg = PyFloat.from(offset.x.toDouble())
    val yArg = PyFloat.from(offset.y.toDouble())
    try {
        onEvent(typeArg, xArg, yArg).close()
    } finally {
        typeArg.close()
        xArg.close()
        yArg.close()
    }
    null
}
