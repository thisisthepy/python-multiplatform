package python.multiplatform.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonCallables
import python.multiplatform.reflection.HandleTable
import python.native.ffi.PyLong_AsLongLong

/**
 * Starts the Python interpreter if it is not running yet, then composes [content].
 *
 * This is the first of the three host composables (`PythonLauncher`, [PythonWidget], [PythonAppView])
 * and its job is the interpreter start only: wrap the part of the UI that reaches Python in it.
 * Registering the composer with the Python runtime is **not** done here once for the whole tree: it
 * is done per Python call, by [withPythonComposer], each time a root or widget is composed, so that
 * the composer a bound `@Composable` sees is always the one of the composition that called it.
 */
@Composable
fun PythonLauncher(content: @Composable () -> Unit) {
    if (!Python3.isInitialized) {
        Python3.initialize()
    }
    content()
}

/**
 * The module [PythonAppView] and [PythonWidget] look in when none is named: Python's `__main__`.
 */
const val DEFAULT_PYTHON_MODULE: String = "__main__"

/**
 * Composes the Python application root found at `module.attribute`, and recomposes whenever Python
 * replaces it. The declarative app root: `PythonAppView(module = "main", attribute = "App")` for a
 * Python `main.App`.
 *
 * `attribute` is a Python callable taking no arguments, or a Compose [State] that Python holds whose
 * value is such a callable (or `None` for "nothing yet"); see [PythonAppView] with a `root` for the
 * semantics. The attribute is resolved **once**, when this enters the composition (and again only if
 * [module] or [attribute] change). Rebinding the module attribute afterwards is not observed;
 * replacing the root is a write into the state the attribute names, which is.
 *
 * [modifier] sizes and places the area the root draws into. The original design's `Surface` and
 * colour parameters are left out on purpose: that needs material3, which this module does not depend
 * on. Wrap the call in a `Surface` where one is wanted.
 */
@Composable
fun PythonAppView(
    modifier: Modifier = Modifier,
    module: String = DEFAULT_PYTHON_MODULE,
    attribute: String = "App",
) {
    val resolved = remember(module, attribute) { ResolvedAttribute(module, attribute) }
    PythonAppView(resolved.value, modifier)
}

/**
 * Composes the named Python composable `moduleName.composableName`: a callable taking no arguments,
 * or a Compose [State] that Python holds whose value is one. Same semantics as [PythonAppView] with
 * a `root`. Unlike the original design, no Kotlin `content` slot is passed to the callable, and the
 * `Surface` parameters are left out (see [PythonAppView]).
 */
@Composable
fun PythonWidget(
    composableName: String,
    modifier: Modifier = Modifier,
    moduleName: String = DEFAULT_PYTHON_MODULE,
) {
    val resolved = remember(moduleName, composableName) { ResolvedAttribute(moduleName, composableName) }
    PythonWidget(resolved.value, modifier)
}

/**
 * [PythonWidget] for a Python object the host already holds.
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
fun PythonWidget(root: PyObject, modifier: Modifier = Modifier) {
    val source = remember(root) { RootSource.of(root) }
    val current = source.current()
    Layout(
        content = {
            if (current != null) {
                key(current) {
                    PythonRoot(current)
                }
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        val width = placeables.maxOfOrNull { it.width } ?: constraints.minWidth
        val height = placeables.maxOfOrNull { it.height } ?: constraints.minHeight
        layout(width, height) {
            placeables.forEach { it.place(0, 0) }
        }
    }
}

/** [PythonWidget] with a `root`, under the app-root name. */
@Composable
private fun PythonAppView(root: PyObject, modifier: Modifier) {
    PythonWidget(root, modifier)
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
