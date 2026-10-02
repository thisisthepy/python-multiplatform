package fixture.compose

import androidx.compose.animation.core.spring
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.types.basic.PyString

/**
 * `Modifier.anchoredDraggable`, declined for having a generic type parameter `T` that Python cannot spell
 * (`docs/design/pythonx-adapter-design.md` §9.2). The fix is to provide a wrapper for a concrete type.
 *
 * We choose `String` as the concrete type for `T` because swipe UI states are typically discrete
 * identifiers like "start", "end", "settled", which map naturally to strings.
 *
 * ### What this function is
 *
 * It is a hand-written Kotlin function, bound by KSP as a plain top-level entry exactly the way
 * [pythonPointerInput] and [pythonLayoutIdString] are.
 *
 * ### Lifetime -- not handled, same as [pythonDraggable]
 *
 * [onConfirmValueChange] is held by the [AnchoredDraggableState] and never closed here, causing a leak
 * for each call to this function. This is the same unhandled lifetime issue noted in [pythonDraggable].
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun pythonAnchoredDraggableString(
    modifier: Modifier,
    initialValue: String,
    anchor1Value: String,
    anchor1Offset: Float,
    anchor2Value: String,
    anchor2Offset: Float,
    onConfirmValueChange: PyObject
): Modifier {
    val anchors = DraggableAnchors<String> {
        anchor1Value at anchor1Offset
        anchor2Value at anchor2Offset
    }
    
    val state = AnchoredDraggableState<String>(
        initialValue = initialValue,
        anchors = anchors,
        positionalThreshold = { totalDistance: Float -> totalDistance * 0.5f },
        velocityThreshold = { 100f },
        animationSpec = spring<Float>(),
        confirmValueChange = { newValue: String ->
            val pyStr = PyString.from(newValue)
            try {
                onConfirmValueChange(pyStr).close()
            } finally {
                pyStr.close()
            }
            true
        }
    )
    
    return modifier.anchoredDraggable<String>(
        state = state,
        orientation = Orientation.Horizontal
    )
}
