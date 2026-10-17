package fixture.compose

import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.FractionalThreshold
import androidx.compose.material.SwipeableState
import androidx.compose.material.swipeable
import androidx.compose.ui.Modifier
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.types.basic.PyString

/**
 * `Modifier.swipeable` (`androidx.compose.material.SwipeableKt.swipeable-pPrIpRY`), declined for the same
 * reason `anchoredDraggable` is (`docs/archive/pythonx-adapter-design.md` §9.2, §9.5): its type parameter `T` --
 * here on both `SwipeableState<T>` and the `anchors: Map<Float, T>` parameter -- has no name a cast could
 * spell. Fixed the same way: one wrapper per concrete `T`. `String` was chosen for the same reason
 * `anchoredDraggable`'s wrapper chose it -- the states a swipe moves between are discrete identifiers a
 * caller writes literally ("start", "end").
 *
 * ### `thresholds` is not crossed to Python at all -- confirmed why, not assumed
 *
 * `javap -p` on `SwipeableKt` (Compose Material 1.7.0 desktop jar; there is no sources jar for this
 * version) shows the real signature:
 *
 *     public static final <T> Modifier swipeable-pPrIpRY(
 *         Modifier, SwipeableState<T>, Map<Float, ? extends T> anchors, Orientation,
 *         boolean enabled, boolean reverseDirection, MutableInteractionSource,
 *         Function2<? super T, ? super T, ? extends ThresholdConfig> thresholds,
 *         ResistanceConfig, float velocityThreshold)
 *
 * `thresholds` is a **value-returning** function slot -- it must produce a `ThresholdConfig`, not
 * `Unit` -- and §10 of the design doc already measured that this dispatcher rejects a Python callable
 * for a value-returning slot (`aValueReturningFunctionSlotDoesNotYetAcceptAPythonCallable`, for a bare
 * `() -> Float`). Substituting a `PyObject` for `thresholds` here would hit the same rejection, and even
 * if the dispatcher accepted it, the callable would have to construct and return a `ThresholdConfig` --
 * another type Python cannot spell, on top of the boxed-return problem. So this wrapper does not route
 * `thresholds` to Python: [thresholdFraction] is an ordinary marshalled `Float`, and Kotlin closes over
 * it to build the lambda itself --
 *
 *     thresholds = { _, _ -> FractionalThreshold(thresholdFraction) }
 *
 * -- exactly what the task brief suggested ("코틀린 쪽에서 그 람다를 제공하고 파이썬에는 값만 받게
 * 하는 것"). No callable crosses the boundary for this slot at all; only a value does, which is the
 * shape every other parameter in this codebase already crosses without incident. The `(T, T)` inputs
 * `thresholds` is handed are discarded -- `FractionalThreshold` doesn't use them (it needs only the
 * total drag distance, supplied later by `computeThreshold`'s own `Density`/from/to floats) -- so fixing
 * the fraction ahead of time loses nothing `swipeable` itself would have used them for.
 *
 * `confirmStateChange: (T) -> Boolean` (`SwipeableState`'s own constructor parameter, not `swipeable`'s)
 * is a second value-returning slot, handled the same way [pythonAnchoredDraggableString] handles
 * `AnchoredDraggableState`'s `confirmValueChange`: [onValueChange] is invoked as a one-way notification
 * and this function always returns `true` from the Kotlin lambda, never routing a Python return value
 * back into Compose.
 *
 * ### What this function is
 *
 * A hand-written Kotlin function, bound by KSP as a plain top-level entry exactly the way
 * [pythonAnchoredDraggableString] and [pythonPointerInput] are -- not a generated binding of
 * `Modifier.swipeable`, which stays declined, correctly, for the reason above.
 *
 * ### Lifetime -- not handled, same as [pythonDraggable] and [pythonAnchoredDraggableString]
 *
 * [onValueChange] is held by the [SwipeableState] this function builds and is never closed here; each
 * call to this function leaks that one reference. Not fixed here, on purpose -- `DraggableLeakTest`
 * already pins the shape of this exact leak family and the reason interning cannot reach it.
 */
@OptIn(ExperimentalMaterialApi::class)
fun pythonSwipeableString(
    modifier: Modifier,
    initialValue: String,
    anchor1Value: String,
    anchor1Offset: Float,
    anchor2Value: String,
    anchor2Offset: Float,
    thresholdFraction: Float,
    onValueChange: PyObject,
): Modifier {
    val state = SwipeableState<String>(
        initialValue = initialValue,
        animationSpec = spring(),
        confirmStateChange = { newValue: String ->
            val pyStr = PyString.from(newValue)
            try {
                onValueChange(pyStr).close()
            } finally {
                pyStr.close()
            }
            true
        },
    )

    return modifier.swipeable(
        state = state,
        anchors = mapOf(
            anchor1Offset to anchor1Value,
            anchor2Offset to anchor2Value,
        ),
        orientation = Orientation.Horizontal,
        thresholds = { _, _ -> FractionalThreshold(thresholdFraction) },
    )
}
