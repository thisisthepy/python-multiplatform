package fixture.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.types.basic.PyFloat
import python.multiplatform.ffi.types.basic.PyString

/**
 * `Modifier.pointerInput` is one of the seven public top-level `Modifier` extensions
 * `ArtifactScannerTest`/`FunctionSlotBindingTest` still measure as declined, and the reason is not a
 * missing feature of the walker: `block` is `suspend PointerInputScope.() -> Unit`, whose *compiled*
 * shape is `Function2<PointerInputScope, Continuation<Unit>, Any?>`. Kotlin source refuses to assign
 * an ordinary `Function2` to a `suspend` function type at all, and even if it did not, a Python
 * callable cannot itself answer `COROUTINE_SUSPENDED` and be resumed later -- there is no coroutine
 * machinery on the CPython side of the boundary that could save and restore a JVM stack.
 *
 * ### What this function is, and is not
 *
 * It is **not** a generated binding of `Modifier.pointerInput` -- no artefact walker or KSP processor
 * produces the suspend lambda `pointerInput` wants, and nothing added here changes that; `pointerInput`
 * itself stays declined, correctly, for the reason above.
 *
 * It **is** a hand-written Kotlin `suspend` function -- compiled once, ordinarily, by `kotlinc`, the
 * same way `PythonComposition` is the one hand-written `@Composable` this module needs. The
 * suspension the JVM calling convention requires lives entirely in this function's own body
 * (`awaitPointerEventScope { while (true) { awaitPointerEvent() } }`), which is real Kotlin source
 * with a real suspension point the compiler builds a state machine for. Python is never asked to
 * suspend: [onEvent] is an ordinary, **non-suspending** callable, invoked synchronously once per
 * pointer event, exactly the shape `PythonCallables`/`UpcallTrampoline` already cross for every other
 * plain `Function0`..`Function5` slot in this codebase.
 *
 * So the boundary sees an *ordinary* function -- `pythonPointerInput(modifier, onEvent)`, both
 * parameters and the return type an object handle or `PyObject` -- and KSP binds it exactly as it
 * binds `emptyModifier` a few lines over: a plain top-level Kotlin function, reached from Python under
 * this module's own package name (`fixture.compose.pythonPointerInput`), not through `pythonx`'s
 * dynamic `androidx.*` loader and not through the artefact walker.
 *
 * ### Why a plain parameter and not an extension receiver
 *
 * `fun Modifier.pythonPointerInput(...)` would read naturally as `m.pythonPointerInput(cb)`, but
 * `FragmentScanner.topLevelFunctionEntry` calls a top-level declaration by its qualified name with
 * `function.parameters` alone -- it never reads `KSFunctionDeclaration.extensionReceiver`, so an
 * extension's receiver would silently vanish from the generated call. Nothing here changes that
 * (out of scope: `FragmentScanner` is shared infrastructure), so [modifier] is an ordinary first
 * parameter instead, the same shape [pythonPointerInput]'s caller already uses for
 * `androidx.compose.foundation.layout.size__Dp`, which is a walked *extension* rendered with an
 * explicit `<receiver>` slot by a different code path (`ArtifactScanner`, not `FragmentScanner`).
 *
 * ### Lifetime
 *
 * [onEvent] crosses as a fresh Python reference (`UpcallTrampoline.toKotlinObject`'s
 * `PyObject(value, borrowed = true)`), and this function is the only thing that can give it back --
 * unlike a `content=` slot there is no `PythonCallableScope`/`RememberObserver` in the loop here, so
 * the reference is released in a `finally` around the whole suspend body, which runs on ordinary
 * completion and on cancellation alike (Compose cancels this coroutine when the modifier leaves
 * composition or `pointerInput`'s key changes).
 *
 * ### What is not handled
 *
 * - Only the first [androidx.compose.ui.input.pointer.PointerEvent.changes] entry is marshalled.
 *   Multi-touch is out of scope for a proof that a single Python callback receives a single real tap.
 * - The `key1` `pointerInput` normally restarts on is fixed to [Unit], so this coroutine is started
 *   once per composition of the node and never restarted for a changed key. Nothing measured here
 *   needs restart semantics.
 */
fun pythonPointerInput(modifier: Modifier, onEvent: PyObject): Modifier = modifier.pointerInput(Unit) {
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
