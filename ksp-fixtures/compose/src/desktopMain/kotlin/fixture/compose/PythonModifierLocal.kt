package fixture.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.modifier.ProvidableModifierLocal
import androidx.compose.ui.modifier.modifierLocalConsumer
import androidx.compose.ui.modifier.modifierLocalOf
import androidx.compose.ui.modifier.modifierLocalProvider
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.types.basic.PyString

/**
 * `Modifier.modifierLocalProvider`, declined for having a generic type parameter `T` that Python cannot spell
 * (`docs/design/pythonx-adapter-design.md` §9.2). The fix is to provide a wrapper for a concrete type.
 *
 * We choose `String` as the concrete type for `T`.
 *
 * ### What this function is
 *
 * It is a hand-written Kotlin function, bound by KSP as a plain top-level entry exactly the way
 * [pythonAnchoredDraggableString] and [pythonPointerInput] are.
 *
 * ### Lifetime -- not handled
 *
 * [onRead] is held by the [modifierLocalConsumer] block and never closed here, causing a leak
 * for each call to this function. This is the same unhandled lifetime issue noted in [pythonDraggable].
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun pythonModifierLocalOfString(defaultValue: String): ProvidableModifierLocal<String> {
    return modifierLocalOf { defaultValue }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun pythonModifierLocalProviderString(
    modifier: Modifier,
    local: ProvidableModifierLocal<String>,
    value: String
): Modifier {
    return modifier.modifierLocalProvider(local) { value }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun pythonModifierLocalConsumerString(
    modifier: Modifier,
    local: ProvidableModifierLocal<String>,
    onRead: PyObject
): Modifier {
    return modifier.modifierLocalConsumer {
        val readValue = local.current
        val pyStr = PyString.from(readValue)
        try {
            onRead(pyStr).close()
        } finally {
            pyStr.close()
        }
    }
}
