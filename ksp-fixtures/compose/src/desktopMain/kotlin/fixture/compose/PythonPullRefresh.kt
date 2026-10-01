package fixture.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.types.basic.PyFloat

/**
 * Test harness for `pullRefresh`'s callback overload —
 * `androidx.compose.material.pullrefresh.PullRefreshKt.pullRefresh(Modifier,
 * onPull: (Float) -> Float, onRelease: suspend (Float) -> Float, enabled: Boolean)`.
 *
 * `docs/design/pythonx-adapter-design.md` §9.4 identifies this overload as requiring a **real nested-scroll
 * parent/child tree**: `onPull`/`onRelease` are fired by `NestedScrollConnection` callbacks from a
 * scrollable descendant's pre-scroll/pre-fling reports, not by pointer events the modifier's own
 * body awaits directly. This function is that harness.
 *
 * ### How the callbacks are bridged
 *
 * `onPull: (Float) -> Float` must return the amount consumed. Per §9.6's settled shape for
 * value-returning slots, **Kotlin owns the return value**: the lambda always returns `pullAvailable`
 * (consuming everything offered), while calling [onPull] as a one-way notification to Python.
 *
 * `onRelease: suspend (Float) -> Float` is a suspend slot with a return value. The same shape
 * applies: Kotlin suspends, returns `0f`, and fires a one-way notification to Python. Python never
 * suspends — the Kotlin lambda body is the only thing that suspends, exactly the way
 * `pythonPointerInput`/`pythonDraggable` handle their own suspend slots.
 *
 * ### Why the scene is built here, not from a Python BODY string
 *
 * A scrollable descendant requires `rememberScrollState()` + `verticalScroll` or `LazyColumn`,
 * neither of which is in `artifactIncludePackages`. Building the nested-scroll tree in this
 * `@Composable` avoids walking those packages, keeping the scope of what the walker covers intact.
 *
 * ### Infinite-animation trap — deliberately avoided
 *
 * §10 of the design doc confirmed that a component that animates forever (`CircularProgressIndicator`
 * with no deterministic end state) prevents `ImageComposeScene.render()` from returning.
 * `PullRefreshIndicator` contains such an animation. **This harness does not use
 * `PullRefreshIndicator`** — `pullRefresh` (the Modifier) and the indicator are separate APIs,
 * and the callback-reaching proof needs only the former.
 *
 * ### Lifetime
 *
 * [onPull] and [onRelease] are held for the lifetime of the lambdas passed to `Modifier.pullRefresh`;
 * `PullRefreshNestedScrollConnection` holds them for as long as the modifier is attached.
 * They are not closed here, matching the same unfixed lifetime acknowledged in `pythonDraggable`,
 * `pythonAnchoredDraggableString`, and `pythonSwipeableString`. The test that uses this harness
 * closes the PyObjects itself in a `finally` block.
 */
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun PythonPullRefreshHarness(onPull: PyObject, onRelease: PyObject, modifier: Modifier = Modifier.fillMaxSize()) {
    Box(
        modifier = modifier
            .background(Color.White)
            .pullRefresh(
                onPull = { pullAvailable: Float ->
                    // Notify Python — one-way, return value stays in Kotlin.
                    // pullAvailable > 0 when dragging downward at the top of the scrollable child.
                    val arg = PyFloat.from(pullAvailable.toDouble())
                    try {
                        onPull(arg).close()   // borrow: arg is borrowed by onPull; .close() is on the *result*
                    } finally {
                        arg.close()           // release our own arg reference
                    }
                    pullAvailable             // consume everything offered
                },
                onRelease = { velocity: Float ->
                    // suspend slot — Kotlin suspends here; Python is a synchronous notification.
                    val arg = PyFloat.from(velocity.toDouble())
                    try {
                        onRelease(arg).close()
                    } finally {
                        arg.close()
                    }
                    0f                        // return value: Kotlin provides, Python does not
                },
                enabled = true,
            ),
    ) {
        // The scrollable descendant whose nested-scroll callbacks drive onPull/onRelease.
        // LazyColumn is always at the top of its content on first render (no initial scroll),
        // so a downward drag immediately produces an over-scroll that reaches onPull.
        // Using 200 items so the column definitely overflows, ensuring scrollState.canScrollBackward
        // is false when at the top.
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(200) { index ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                        .background(if (index % 2 == 0) Color.LightGray else Color.Gray),
                )
            }
        }
    }
}
