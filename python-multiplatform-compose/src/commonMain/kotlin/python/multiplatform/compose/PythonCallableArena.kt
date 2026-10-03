package python.multiplatform.compose

import androidx.compose.runtime.RememberObserver
import python.multiplatform.ffi.pythonx.PythonCallableScope
import python.multiplatform.ffi.pythonx.PythonCallables

/**
 * Who holds the Python callables a composition passes into Kotlin (`content=lambda: ...`), and the one
 * hook that says when to let go of them.
 *
 * A Python callable given to a composable has to outlive the call that passed it -- Compose stores it
 * in the slot table and calls it on later recompositions -- so something must hold a Python
 * reference for it. The only thing whose lifetime *is* the composition's is a remembered value, and
 * [RememberObserver.onForgotten] is the only hook that reports Compose dropping it.
 *
 * | candidate holder | what it gets wrong |
 * |---|---|
 * | the composition **pass** | ends when the pass returns, and Compose calls a stored `content` on every later recomposition |
 * | the `PyObject`'s own cleaner | fires whenever the collector reaches the Kotlin wrapper, which is not a time and is not every platform |
 * | `HandleTable` alone | a strong root nothing gives back |
 * | **this** | `onRemembered` ... `onForgotten` is exactly the interval in which Compose may call the content |
 *
 * Use it as `remember { PythonCallableArena() }` and run the Python pass inside
 * `PythonCallables.withScope(arena.scope) { ... }`.
 *
 * `onAbandoned` closes the scope too: it is the case where the composition that created this was
 * discarded before it was applied, so `onForgotten` will never come. [PythonCallableScope.close] is
 * idempotent and answers how many callables it actually released (`0` on every later call), and
 * [released] accumulates that -- so a total higher than what crossed can only be a double release.
 *
 * Open so that a test harness can count the lifecycle; subclasses must call `super`.
 */
open class PythonCallableArena : RememberObserver {

    /** The scope Python callables crossing during this arena's passes are registered in. */
    val scope: PythonCallableScope = PythonCallables.newScope()

    /** What [PythonCallableScope.close] reported, summed over every close. */
    var released: Int = 0
        private set

    override fun onRemembered() {}

    override fun onForgotten() {
        released += scope.close()
    }

    override fun onAbandoned() {
        released += scope.close()
    }
}
