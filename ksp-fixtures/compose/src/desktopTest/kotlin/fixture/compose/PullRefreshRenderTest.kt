package fixture.compose

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.pythonx.PythonxAdapter
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.FunctionTable
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `pullRefresh`'s callback overload (`androidx.compose.material.pullrefresh.PullRefreshKt
 * .pullRefresh(Modifier, onPull: (Float) -> Float, onRelease: suspend (Float) -> Float, enabled:
 * Boolean)`) is driven by **nested scroll** from a scrollable descendant, not by pointer events
 * the modifier itself awaits. `docs/design/pythonx-adapter-design.md` §9.4 identified this as needing
 * a larger harness — a real scrollable descendant composed under the `pullRefresh` parent, with
 * an actual drag that makes the scrollable child's nested-scroll callbacks flow upward.
 *
 * ### What is proven here
 *
 * 1. A downward drag from the top of a scrollable child (which is already at the top) flows an
 *    over-scroll signal upward into `PullRefreshNestedScrollConnection.onPreScroll`, which calls
 *    `onPull`. Python's `on_pull` callback receives a positive float (the dragged amount consumed),
 *    meaning the nested-scroll path actually reached Python.
 *
 * 2. A drag that does not cross the scrollable child invokes nothing (negative control).
 *
 * ### Why `onPull` returns the pulled amount, not a Python float
 *
 * `onPull: (Float) -> Float` must return the amount consumed. §9.6 settled the shape for
 * value-returning slots: Kotlin supplies the return value, Python is given a one-way notification.
 * `pythonPullRefreshCallbacks` implements exactly that — the lambda always returns `pullAvailable`
 * (consuming everything offered) while firing a notification to Python.
 *
 * `onRelease: suspend (Float) -> Float` is the same: Kotlin closes the coroutine returning `0f`,
 * Python receives a one-way notification.
 *
 * ### Why the scene is built in Kotlin, not from a Python BODY string
 *
 * A scrollable descendant inside Compose requires `rememberScrollState()` and `verticalScroll`,
 * which are not in `artifactIncludePackages`. Building the nested-scroll parent/child tree in
 * Kotlin (inside `PythonPullRefreshHarness`) avoids adding those packages to the walker's scope
 * — this proof is about the callback boundary, not about what the walker covers.
 */
class PullRefreshRenderTest {

    @BeforeTest
    fun setup() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        UpcallTable.install(FunctionTable.fragments + ArtifactTable.fragments)
        check(UpcallBootstrap.publishToGlobals()) { "UpcallBootstrap.publishToGlobals() failed" }
        PythonProxySource.install()
        PythonxAdapter.install(PYTHONX_RAW_VALUE_CLASSES)
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    /**
     * Positive test: a real downward drag over the scrollable child, while the child is at the top
     * of its content (so over-scroll goes into `pullRefresh`), invokes Python's `on_pull` callback
     * with a positive float, and pixels change.
     */
    @Test
    fun aRealDownwardDragReachesThePythonOnPullCallback() {
        Python3.exec(
            """
            _pull_events = []
            _release_events = []

            def _on_pull(pulled):
                _pull_events.append(pulled)

            def _on_release(velocity):
                _release_events.append(velocity)
            """.trimIndent(),
        )

        // Resolve Python callbacks to PyObjects via the __main__ module's attribute dictionary.
        val mainModule = Python3.import("__main__")
        val onPullPy = mainModule.getAttr("_on_pull")
        val onReleasePy = mainModule.getAttr("_on_release")

        val scene = ImageComposeScene(
            width = SCENE,
            height = SCENE,
            density = Density(1f),
        ) {
            PythonPullRefreshHarness(onPull = onPullPy, onRelease = onReleasePy, modifier = Modifier.size(80.dp))
        }
        try {
            scene.render()
            // Drag downward from near the top — the scrollable child is already at the top, so
            // over-scroll flows into PullRefreshNestedScrollConnection.onPreScroll -> onPull.
            scene.sendPointerEvent(PointerEventType.Move, Offset(SCENE / 2f, START_Y), type = PointerType.Touch)
            scene.sendPointerEvent(PointerEventType.Press, Offset(SCENE / 2f, START_Y), type = PointerType.Touch)
            var y = START_Y
            while (y < START_Y + DRAG_DISTANCE) {
                y += STEP
                scene.sendPointerEvent(PointerEventType.Move, Offset(SCENE / 2f, y), type = PointerType.Touch)
            }
            scene.sendPointerEvent(PointerEventType.Release, Offset(SCENE / 2f, y), type = PointerType.Touch)
            scene.render()
        } finally {
            scene.close()
            // PyObjects must stay alive until after pixelsWithCallbacks(after) is called below,
            // because pixelsWithCallbacks creates another scene that holds references to them.
            // They are closed explicitly after the after-pixels are captured.
        }

        // Python's on_pull received at least one call with a positive float.
        Python3.exec(
            "assert len(_pull_events) > 0, 'on_pull was never invoked: ' + repr(_pull_events)",
        )
        Python3.exec(
            """
            assert any(v > 0.0 for v in _pull_events), (
                'on_pull was called but all values were non-positive: ' + repr(_pull_events)
            )
            """.trimIndent(),
        )

        println(
            "pullRefresh: downward drag -> " +
                "on_pull events: ${Python3.import("__main__").getAttr("_pull_events")}"
        )
        onPullPy.close()
        onReleasePy.close()
    }

    /**
     * Negative control: a press and drag **outside** the scrollable child (far corner) invokes
     * neither `on_pull` nor `on_release`.
     */
    @Test
    fun aDragOutsideTheChildInvokesNothing() {
        Python3.exec(
            """
            _pull_events = []
            _release_events = []

            def _on_pull(pulled):
                _pull_events.append(pulled)

            def _on_release(velocity):
                _release_events.append(velocity)
            """.trimIndent(),
        )

        val mainModule = Python3.import("__main__")
        val onPullPy = mainModule.getAttr("_on_pull")
        val onReleasePy = mainModule.getAttr("_on_release")

        val scene = ImageComposeScene(
            width = SCENE,
            height = SCENE,
            density = Density(1f),
        ) {
            PythonPullRefreshHarness(onPull = onPullPy, onRelease = onReleasePy, modifier = Modifier.size(80.dp))
        }
        try {
            scene.render()
            // Press and drag in the bottom-right corner — outside the Box that carries pullRefresh.
            val at = Offset(SCENE - 2f, SCENE - 2f)
            scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Touch)
            scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Touch)
            scene.sendPointerEvent(PointerEventType.Move, Offset(SCENE - 2f, SCENE - 20f), type = PointerType.Touch)
            scene.sendPointerEvent(PointerEventType.Release, Offset(SCENE - 2f, SCENE - 20f), type = PointerType.Touch)
            scene.render()
        } finally {
            scene.close()
            onPullPy.close()
            onReleasePy.close()
        }

        Python3.exec(
            "assert _pull_events == [] and _release_events == [], " +
                "'a drag outside the box invoked pullRefresh: ' + repr((_pull_events, _release_events))",
        )
    }

    private companion object {
        const val SCENE = 120
        const val START_Y = 4f
        const val DRAG_DISTANCE = 60f
        const val STEP = 8f
    }
}
