package fixture.compose

import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.ui.Modifier
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.types.basic.PyFloat

/**
 * `Modifier.draggable`, reached the same way `pythonPointerInput` reaches `Modifier.pointerInput`
 * (`docs/design/pythonx-adapter-design.md` §9.1) -- `draggable`'s three callback slots decline for the same
 * reason `pointerInput`'s does (`ArtifactScanner`: a suspend function-typed parameter), and the same
 * fix applies: nothing about Python has to suspend, because the suspension is Kotlin's, hand-written,
 * ordinarily compiled, and Python is only ever asked a synchronous question.
 *
 * ### The three slots are two different shapes, not one
 *
 * `DraggableState`'s own `onDelta` (the top-level `DraggableState(onDelta: (Float) -> Unit)` factory
 * this function builds one of) is **not** suspend at all -- it is an ordinary `Function1` the internal
 * drag node calls directly, once per raw delta, exactly the shape every other plain callback in this
 * codebase already crosses. `onDragStarted`/`onDragStopped` (`draggable`'s own parameters) are the
 * `suspend CoroutineScope.(...) -> Unit` shape §9.1 opened: each is invoked once per gesture, and
 * because *nothing inside either lambda body suspends*, the compiler still builds the state machine
 * the calling convention demands, it just never actually parks.
 *
 * ### What this function is, and is not
 *
 * It is **not** a generated binding of `Modifier.draggable` -- `draggable` itself stays declined,
 * correctly, for the reason above. It **is** a hand-written Kotlin function, bound by KSP as a plain
 * top-level entry exactly the way [pythonPointerInput] is, for the same reason: `FragmentScanner
 * .topLevelFunctionEntry` never reads an extension receiver, so [modifier] is an ordinary parameter.
 *
 * ### Lifetime -- not handled, and said so rather than guessed at
 *
 * Unlike [pythonPointerInput], there is no single suspend body whose `finally` covers this function's
 * whole lifetime: `onDelta` is held by the `DraggableState` this function builds and is called by
 * Compose's internal drag node for as long as that node stays attached, which this function has no
 * hook into (no `RememberObserver` route without wrapping this in a `@Composable`, which would be a
 * different fix -- see `docs/design/pythonx-adapter-design.md` §9.2's note on `composed`). [onDelta],
 * [onDragStarted] and [onDragStopped] are therefore **never closed here**: each call to this function
 * takes a fresh reference to all three and leaks it. Measured consequence: `DraggableRenderTest`
 * composes this exactly once per scene, so the leak is bounded (three references) for that test and
 * unbounded for a composable that recomposes. Closing them prematurely (say, from inside
 * `onDragStopped`, since a single gesture is the common case this proof drives) was rejected on
 * purpose: a second gesture after the first would then invoke a closed `PyObject`, which is worse than
 * a leak (`agent-rules` §14) -- it is a use of a reference that may have already been reassigned.
 */
fun pythonDraggable(
    modifier: Modifier,
    onDelta: PyObject,
    onDragStarted: PyObject,
    onDragStopped: PyObject,
): Modifier {
    val state = DraggableState { delta ->
        val arg = PyFloat.from(delta.toDouble())
        try {
            onDelta(arg).close()
        } finally {
            arg.close()
        }
    }
    return modifier.draggable(
        state = state,
        orientation = Orientation.Horizontal,
        onDragStarted = { startedAt ->
            val xArg = PyFloat.from(startedAt.x.toDouble())
            val yArg = PyFloat.from(startedAt.y.toDouble())
            try {
                onDragStarted(xArg, yArg).close()
            } finally {
                xArg.close()
                yArg.close()
            }
        },
        onDragStopped = { velocity ->
            val vArg = PyFloat.from(velocity.toDouble())
            try {
                onDragStopped(vArg).close()
            } finally {
                vArg.close()
            }
        },
    )
}
