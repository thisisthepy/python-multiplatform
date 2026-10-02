package fixture.compose

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import python.multiplatform.compose.PythonContent
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonCallables
import python.multiplatform.ffi.pythonx.PythonxAdapter
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.FunctionTable
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * **The host entry point** (python-multiplatform#18, for pythonx-compose#11's `@app`): the host says
 * *where* the Python root state lives, and from then on Python alone decides what is on screen.
 *
 * The root lives in a Compose `MutableState` that Python created and holds ([rootState]); Python
 * replaces the root by writing into it ([writeRoot]). The claim is that this write is *all* it takes:
 * the next frame shows the new root, and nothing on the Kotlin side was called to make it so. The
 * only thing the test does between the write and the frame is what a window's frame clock does on
 * its own -- deliver the snapshot's apply notification -- and the per-root call counters show that the
 * host's `PythonContent` was not re-entered by anything but that.
 *
 * The lifetime half follows `ComposableRenderTest.aDisposedCompositionGivesEveryPythonCallableBack`:
 * a named Python function crosses as a `content=` slot, its `sys.getrefcount` rises while composed,
 * and comes back to its baseline exactly -- above means a leak, below means a double release.
 */
class PythonContentRenderTest {

    @BeforeTest
    fun installBothProducers() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        UpcallTable.install(FunctionTable.fragments + ArtifactTable.fragments)
        check(UpcallBootstrap.publishToGlobals()) { "UpcallBootstrap.publishToGlobals() failed" }
        PythonProxySource.install()
        PythonxAdapter.install(PYTHONX_RAW_VALUE_CLASSES)
        Python3.exec(ROOTS)
    }

    @AfterTest
    fun cleanup() {
        Python3.exec("del _root_state")
        UpcallTable.clear()
    }

    /**
     * Declare, render, replace by a state write, render, replace back, render, dispose.
     *
     * Reached through `PythonContent(module, attribute)`, the spelling a host that knows only a module
     * name uses.
     */
    @Test
    fun aRootWrittenFromPythonIsDrawnOnTheNextFrameWithoutAHostCall() {
        Python3.exec("writeRoot(_root_state, _root_a)")
        val baseRoots = HandleTable.liveCount
        val baseInner = pyInt("sys.getrefcount(_inner)")

        val scene = ImageComposeScene(width = 200, height = 60, density = Density(1f)) {
            PythonContent(module = "__main__", attribute = "_root_state")
        }
        val inkA: Int
        val inkB: Int
        val inkABack: Int
        try {
            inkA = inkOfImage(scene.render())
            assertEquals(1, pyInt("_calls['a']"), "the declared root was not composed exactly once")
            assertEquals(0, pyInt("_calls['b']"), "a root that was never declared was composed")
            assertTrue(inkA > 0, "the declared root drew nothing")

            // A frame with no write is not a reason to run the root again: nothing here polls.
            scene.render()
            assertEquals(1, pyInt("_calls['a']"), "an idle frame re-ran the root, so something polls")

            // Python replaces the root. Nothing on the Kotlin side is called.
            Python3.exec("writeRoot(_root_state, _root_b)")
            inkB = inkOfImage(nextFrame(scene))
            assertEquals(1, pyInt("_calls['b']"), "the root Python wrote was not composed on the next frame")
            assertEquals(1, pyInt("_calls['a']"), "the replaced root was composed again")
            assertNotEquals(inkA, inkB, "the frame after the write drew the old root's content")
            val heldInner = pyInt("sys.getrefcount(_inner)")
            assertTrue(heldInner > baseInner, "nothing held _inner while root B was composed: $baseInner -> $heldInner")

            // And back.
            Python3.exec("writeRoot(_root_state, _root_a)")
            inkABack = inkOfImage(nextFrame(scene))
            assertEquals(2, pyInt("_calls['a']"), "switching back did not compose root A again")
            assertEquals(1, pyInt("_calls['b']"), "root B ran after it was replaced")
            assertEquals(inkA, inkABack, "switching back did not draw root A's content")
            // Root B's `content=` callable belongs to root B's composition, which just left.
            assertEquals(
                baseInner, pyInt("sys.getrefcount(_inner)"),
                "root B's content callable was not given back when root B was replaced",
            )
        } finally {
            scene.close()
        }
        println("python content: ink A=$inkA B=$inkB A again=$inkABack; calls=${pyStr("_calls")}")

        assertEquals(baseInner, pyInt("sys.getrefcount(_inner)"), "a Python reference outlived the composition")
        assertEquals(0, HandleTable.liveCount - baseRoots, "handles left rooted after the composition was disposed")
        assertEquals(0, PythonCallables.openScopeCount, "a callable scope was left open")
    }

    /**
     * The other spelling: the host already holds the Python object the state lives in. Disposal is
     * where root B's `content=` callable comes back here, since the root is never replaced.
     */
    @Test
    fun theHostCanPassThePythonStateObjectAndDisposalReleasesWhatTheRootPassed() {
        Python3.exec("writeRoot(_root_state, _root_b)")
        val baseRoots = HandleTable.liveCount
        val baseInner = pyInt("sys.getrefcount(_inner)")
        val state = Python3.import("__main__").getAttr("_root_state")

        val scene = ImageComposeScene(width = 200, height = 60, density = Density(1f)) {
            PythonContent(root = state)
        }
        val ink: Int
        val held: Int
        try {
            ink = inkOfImage(scene.render())
            held = pyInt("sys.getrefcount(_inner)")
        } finally {
            scene.close()
        }
        state.close()
        val after = pyInt("sys.getrefcount(_inner)")
        println("python content (state object): ink=$ink _inner refcount base=$baseInner held=$held disposed=$after")

        assertEquals(1, pyInt("_calls['b']"), "the root in the state was not composed")
        assertTrue(ink > 0, "the root in the state drew nothing")
        assertTrue(held > baseInner, "nothing held _inner for the composition: $baseInner -> $held")
        assertEquals(baseInner, after, "the composition did not give _inner back")
        assertEquals(0, HandleTable.liveCount - baseRoots, "handles left rooted after the composition was disposed")
        assertEquals(0, PythonCallables.openScopeCount, "a callable scope was left open")
    }

    /** A Python callable passed directly is a root that never changes -- no state involved. */
    @Test
    fun aPlainPythonCallableIsAFixedRoot() {
        val root = Python3.import("__main__").getAttr("_root_a")
        val scene = ImageComposeScene(width = 200, height = 60, density = Density(1f)) {
            PythonContent(root = root)
        }
        val ink: Int
        try {
            ink = inkOfImage(scene.render())
        } finally {
            scene.close()
        }
        root.close()
        assertEquals(1, pyInt("_calls['a']"), "the callable root was not composed exactly once")
        assertTrue(ink > 0, "the callable root drew nothing")
    }

    /**
     * What a window's frame clock does by itself: deliver the global snapshot's pending writes to the
     * recomposer, then draw. `ImageComposeScene` has no clock, so the test is the clock.
     */
    private fun nextFrame(scene: ImageComposeScene): Image {
        Snapshot.sendApplyNotifications()
        return scene.render()
    }

    private fun inkOfImage(image: Image): Int {
        val bitmap = Bitmap.makeFromImage(image)
        var ink = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getColor(x, y) != 0) ink++
            }
        }
        return ink
    }

    private fun pyInt(expression: String): Int = pyStr(expression).toInt()

    private fun pyStr(expression: String): String = Python3.import("__main__").getAttr("__dict__").let { globals ->
        Python3.eval(expression, PY_EVAL_INPUT, globals, globals).toString()
    }

    private companion object {
        /** `Py_eval_input`. */
        const val PY_EVAL_INPUT = 258

        /**
         * Two roots that draw visibly different amounts of ink, and count their own invocations so a
         * frame can be attributed to the root that drew it. Root B passes a named function as a
         * `content=` slot so its reference count can be followed across the composition's lifetime.
         */
        val ROOTS = """
            import sys
            from fixture.compose import rootState, writeRoot
            from androidx.compose.foundation.layout import Column
            from androidx.compose.material3 import Text

            _calls = {'a': 0, 'b': 0}

            def _root_a():
                _calls['a'] += 1
                Text('a')

            def _inner():
                Text('BBBBBBBBBBBBBBBB')

            def _root_b():
                _calls['b'] += 1
                Column(content=_inner)

            _root_state = rootState()
        """.trimIndent()
    }
}
