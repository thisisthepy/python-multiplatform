package fixture.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.types.basic.PyFloat
import python.multiplatform.ffi.types.basic.PyString

/**
 * `Modifier.dragAndDropSource`, reached the same way `pythonPointerInput` reaches
 * `Modifier.pointerInput` (`docs/design/pythonx-adapter-design.md` §9.1) -- confirmed to be the *same*
 * underlying mechanism, not just the same declared shape, by disassembling
 * `DragAndDropSourceNode`'s constructor (no sources jar for this Compose version): it `delegate`s a
 * `SuspendingPointerInputFilterKt.SuspendingPointerInputModifierNode(dragAndDropSourceHandler)` --
 * the exact same node `Modifier.pointerInput` itself delegates to. `dragAndDropSource`'s `block`
 * parameter is a `PointerInputScope` (`DragAndDropSourceScope` extends it) started and cancelled on
 * the same node lifetime `pythonPointerInput` already relies on, so [pythonPointerInput]'s technique
 * -- and its `finally`-based lifetime -- carries over verbatim, not just by analogy.
 *
 * ### What is exercised here, and what is not
 *
 * This proves the same claim §9.1 proves for `pointerInput`: a real pointer event, delivered while
 * this modifier's suspend slot is active, reaches a synchronous Python callback. It does **not**
 * exercise [androidx.compose.foundation.draganddrop.DragAndDropSourceScope.startTransfer] or
 * [drawDragDecoration] doing anything real -- starting an actual OS-level drag session needs a
 * `DragAndDropTransferData` wrapping a `java.awt.datatransfer.Transferable`, which is a real drag
 * payload and a different, unmeasured claim from "the suspend slot's events reach Python". Not
 * attempted here: [drawDragDecoration] is a hardcoded no-op, and [onEvent] never calls
 * `startTransfer`.
 *
 * ### Lifetime
 *
 * Same shape as [pythonPointerInput]: [onEvent] is released in the `finally` around the whole suspend
 * body, which runs on both ordinary completion and cancellation (node detachment), because this is
 * the same `SuspendingPointerInputModifierNode` coroutine [pythonPointerInput] already relies on for
 * that guarantee.
 */
@OptIn(ExperimentalFoundationApi::class)
fun pythonDragAndDropSource(modifier: Modifier, onEvent: PyObject): Modifier = modifier.dragAndDropSource(
    drawDragDecoration = { /* not exercised -- see this function's KDoc */ },
) {
    try {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                val type = when (event.type) {
                    PointerEventType.Press -> "press"
                    PointerEventType.Release -> "release"
                    PointerEventType.Move -> "move"
                    else -> "other"
                }
                val typeArg = PyString.from(type)
                val xArg = PyFloat.from(change.position.x.toDouble())
                val yArg = PyFloat.from(change.position.y.toDouble())
                try {
                    onEvent(typeArg, xArg, yArg).close()
                } finally {
                    typeArg.close()
                    xArg.close()
                    yArg.close()
                }
            }
        }
    } finally {
        onEvent.close()
    }
}
