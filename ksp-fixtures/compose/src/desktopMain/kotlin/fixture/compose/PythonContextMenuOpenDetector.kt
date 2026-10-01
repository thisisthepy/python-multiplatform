package fixture.compose

import androidx.compose.foundation.contextMenuOpenDetector
import androidx.compose.ui.Modifier
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.types.basic.PyFloat

/**
 * `Modifier.contextMenuOpenDetector` -- `docs/design/pythonx-adapter-design.md` §9's final declined declaration.
 *
 * It declined because its `key` parameter is typed `Any?`, and `resolveKotlinType` has no boundary type
 * for a bare `kotlin.Any` parameter (no hint of which `TypeTag` an arbitrary Python value crossing there
 * should marshal as).
 *
 * This function bypasses the rejection by taking no `key` parameter from Python and passing `Unit`
 * to the underlying Kotlin declaration. Like `pythonPointerInput` and `pythonDraggable`, this is a
 * hand-written Kotlin function bound by KSP as a plain top-level entry.
 *
 * ### Lifetime
 *
 * Like `pythonDraggable`, `onPointerDown` is captured by the generated event handler and is never
 * closed here. It is leaked for the lifetime of the modifier.
 */
import androidx.compose.foundation.ExperimentalFoundationApi

@OptIn(ExperimentalFoundationApi::class)
fun pythonContextMenuOpenDetector(
    modifier: Modifier,
    enabled: Boolean,
    onPointerDown: PyObject,
): Modifier = modifier.contextMenuOpenDetector(
    key = Unit,
    enabled = enabled,
    onOpen = { offset ->
        val xArg = PyFloat.from(offset.x.toDouble())
        val yArg = PyFloat.from(offset.y.toDouble())
        try {
            onPointerDown(xArg, yArg).close()
        } finally {
            xArg.close()
            yArg.close()
        }
    }
)
