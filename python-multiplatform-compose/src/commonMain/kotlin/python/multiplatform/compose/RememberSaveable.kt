package python.multiplatform.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import python.multiplatform.ffi.PyObject

/**
 * `rememberSaveable { mutableStateOf(initial) }` for a value Python holds, so that a Python screen's
 * state survives what `rememberSaveable` survives (recreating an activity, process death with a
 * saved instance state, a `SaveableStateRegistry` saved and restored).
 *
 * Follows `rememberSaveableWrapper(init, type)` of the original pycomposeui
 * (`Runtime.android.kt`), which dispatched on a type name Python passed along. Here the value alone
 * is passed: a Python scalar written into a `kotlin.Any?` slot arrives boxed, so its runtime type is
 * the type name.
 *
 * | Python value | Kotlin type | State created |
 * |---|---|---|
 * | `int` that fits 32 bits | `Int` | `mutableIntStateOf` |
 * | larger `int` | `Long` | `mutableLongStateOf` |
 * | `float` | `Double` | `mutableDoubleStateOf` |
 * | `bool` | `Boolean` | `mutableStateOf` |
 * | `str` | `String` | `mutableStateOf` |
 *
 * Any other value throws [IllegalArgumentException] naming its type (`list` for a Python list): like the original, only these
 * are supported, because only these are saveable without a custom `Saver`.
 *
 * The returned state is Compose's own: Python reads and writes its `value`, and a write is saved by
 * the next `SaveableStateRegistry.performSave`. Call it inside a composition only.
 */
@Composable
fun rememberSaveableWrapper(initial: Any?): MutableState<*> = when (initial) {
    is Int -> rememberSaveable { mutableIntStateOf(initial) }
    is Long -> rememberSaveable { mutableLongStateOf(initial) }
    is Double -> rememberSaveable { mutableDoubleStateOf(initial) }
    is Boolean -> rememberSaveable { mutableStateOf(initial) }
    is String -> rememberSaveable { mutableStateOf(initial) }
    else -> throw IllegalArgumentException(
        "rememberSaveableWrapper supports Int, Long, Double, Boolean and String, not " + typeNameOf(initial),
    )
}

/** The type of [value] as Python spells it for a Python object (`list`), as Kotlin does otherwise. */
private fun typeNameOf(value: Any?): String = when (value) {
    null -> "null"
    is PyObject -> value.Type.getAttr("__name__").toString()
    else -> value::class.qualifiedName ?: value::class.simpleName ?: "unknown"
}
