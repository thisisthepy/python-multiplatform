package python.multiplatform.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonCallables
import python.multiplatform.reflection.HandleTable
import python.native.ffi.PyLong_AsLongLong

/**
 * Composes the Python application root found at [root], and recomposes whenever Python replaces it.
 *
 * [root] is one of:
 *
 * - **a Compose [State] that Python holds** -- the Python proxy of a Kotlin `State` / `MutableState`
 *   (anything carrying the binder's `_pm_handle` for one). Its `value` is the root: a Python callable
 *   taking no arguments, or `None`/`null` for "nothing yet". The value is read *inside* the
 *   composition, so Compose subscribes to it, and Python replacing the root is an ordinary state write
 *   (`state.value = Root`): the next frame composes the new root and nothing on the Kotlin side is
 *   called to make that happen.
 * - **a Python callable** -- a root that never changes.
 *
 * The root is called with this composition's composer available to the binding layer
 * ([withPythonComposer]), so it may call any bound `@Composable`. Python callables it passes into
 * composables (`content=lambda: ...`) are held for as long as *that root* is composed
 * ([PythonCallableArena]): replacing the root, or disposing the composition, gives them back.
 * Replacing the root also discards the old root's composition state (its `remember`ed values), as
 * calling a different composable would -- a redeclared root is a new function.
 *
 * The binder names no library here: what the root state is called, and which package declares it,
 * is the host's and that package's business.
 *
 * [root] is the caller's: it is neither consumed nor closed.
 */
@Composable
fun PythonContent(root: PyObject) {
    val source = remember(root) { RootSource.of(root) }
    val current = source.current()
    if (current != null) {
        key(current) {
            PythonRoot(current)
        }
    }
}

/**
 * [PythonContent] for a root state that lives at `module.attribute` -- `PythonContent("main", "app")`
 * for a Python `main.app`.
 *
 * The attribute is resolved **once**, when this enters the composition (and again only if [module]
 * or [attribute] change). Rebinding the module attribute afterwards is not observed; replacing the
 * root is a write into the state the attribute names, which is.
 */
@Composable
fun PythonContent(module: String, attribute: String) {
    val resolved = remember(module, attribute) { ResolvedAttribute(module, attribute) }
    PythonContent(resolved.value)
}

/**
 * One root, composed. Its own composable so that the root's composition -- and the arena holding the
 * Python callables it passed -- is keyed by the root and leaves when the root is replaced.
 *
 * A Python read of a Compose state during the call is recorded against this function's recompose
 * scope like any other read, so a root that reads `state.value` is called again when that changes.
 */
@Composable
private fun PythonRoot(root: PyObject) {
    val arena = remember { PythonCallableArena() }
    // The composer call outermost: Compose forbids a try/finally around a composable invocation,
    // and `withScope`'s is one once inlined.
    withPythonComposer {
        PythonCallables.withScope(arena.scope) {
            root().close()
        }
    }
}

/** Where the current root comes from. */
private sealed class RootSource {

    /** The root as of this read. Called during composition, which is what subscribes to a [State]. */
    abstract fun current(): PyObject?

    class Fixed(private val root: PyObject) : RootSource() {
        override fun current(): PyObject = root
    }

    class FromState(private val state: State<*>) : RootSource() {
        override fun current(): PyObject? = when (val value = state.value) {
            null -> null
            is PyObject -> value
            else -> throw IllegalStateException(
                "the Python root state holds a ${value::class.simpleName}, not a Python callable",
            )
        }
    }

    companion object {
        fun of(root: PyObject): RootSource {
            val handle = root.getAttrOrNull(HANDLE_ATTRIBUTE)
            if (handle != null) {
                val raw = try {
                    Python3.withPython { PyLong_AsLongLong(handle.pointer) }
                } finally {
                    handle.close()
                }
                return when (val kotlin = HandleTable.resolveRaw(raw)) {
                    is State<*> -> FromState(kotlin)
                    null -> throw IllegalArgumentException("the Python root refers to a released Kotlin object")
                    else -> throw IllegalArgumentException(
                        "the Python root is a ${kotlin::class.simpleName}; expected a Compose State or a Python callable",
                    )
                }
            }
            require(root.isCallable) { "the Python root is neither a Compose State nor a Python callable" }
            return Fixed(root)
        }

        /** The attribute the binder's proxy of a Kotlin object carries its handle under. */
        private const val HANDLE_ATTRIBUTE = "_pm_handle"
    }
}

/** `module.attribute`, held for as long as the composition remembers it. */
private class ResolvedAttribute(module: String, attribute: String) : RememberObserver {

    val value: PyObject = Python3.import(module).let { imported ->
        try {
            imported.getAttr(attribute)
        } finally {
            imported.close()
        }
    }

    override fun onRemembered() {}

    override fun onForgotten() {
        value.close()
    }

    override fun onAbandoned() {
        value.close()
    }
}
