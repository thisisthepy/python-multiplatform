package python.multiplatform.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import python.multiplatform.ffi.Python3
import python.multiplatform.reflection.HandleTable

/**
 * Runs [block] -- Python code that may call bound `@Composable`s -- with this composition's composer
 * made available to the binding layer.
 *
 * `$composer` is not a value but a *position*: `currentComposer` resolves to the composer parameter of
 * the enclosing composable, so the only way to obtain one is to be inside a composition. Everything
 * else about calling a composable from Python (which arguments were written, the `$default` mask,
 * `$changed`) is arithmetic the binding layer does in Python (`python_multiplatform.binding`'s
 * `_bind_composable`), which is why this is the one thing Kotlin has to supply.
 *
 * The composer crosses as an ordinary `TypeTag.OBJECT` handle, pushed onto the binding layer's
 * composer stack for exactly the duration of [block] and released after it, so its lifetime is this
 * call's: `push_composer` retains nothing.
 *
 * [block] is not `@Composable`: it composes through Python, not through Kotlin call sites.
 */
@Composable
fun <T> withPythonComposer(block: () -> T): T {
    val reference = HandleTable.register(currentComposer)
    try {
        Python3.exec("import $BINDING_MODULE as _pm_binding\n_pm_binding.push_composer(${reference.raw})")
        try {
            return block()
        } finally {
            Python3.exec("import $BINDING_MODULE as _pm_binding\n_pm_binding.pop_composer()")
        }
    } finally {
        HandleTable.release(reference)
    }
}

/** The binder's own binding layer (`KotlinSurface`'s `BINDING_MODULE`). Not a library name. */
private const val BINDING_MODULE = "python_multiplatform.binding"
