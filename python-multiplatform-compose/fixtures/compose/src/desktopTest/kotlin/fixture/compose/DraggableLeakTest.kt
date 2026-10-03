package fixture.compose

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonxAdapter
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.FunctionTable
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DraggableLeakTest {

    @BeforeTest
    fun installBothProducers() {
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
     * **The leak `PythonDraggable`'s own documentation admits, measured instead of estimated.**
     *
     * Twelve recomposition passes hold twelve references to each of the three callables -- exactly
     * one per pass, linear and unbounded, released only when the composition is disposed. The
     * numbers are asserted as they are rather than as they should be: this test pins the current
     * behaviour, so when the lifetime is fixed it fails and has to be updated deliberately.
     *
     * **Interning does not reach this case, and the reason is structural.** A wrapper is interned by
     * a key Python computes, and the substitution that computes it happens for slots the artefact
     * scanner rewrites. `pythonDraggable` is hand-written and takes its callables as raw `PyObject`s,
     * so Python passes them straight through with no key -- there is nothing for the scope to intern
     * by. Registering them keylessly would not help either: they would accumulate exactly as
     * measured here, which is the shape `RecompositionAccumulationTest` already pins for the general
     * case.
     *
     * Closing it needs the release sweep that the interning work deliberately deferred -- give back
     * every wrapper a pass neither built nor re-used -- and that sweep is still waiting on the one
     * measurement it needs: a slot that ignores re-supply and holds, the shape a keyed effect has,
     * where a sweep would hand back something still in use. Releasing at the end of a gesture was
     * considered and rejected earlier for the same class of reason: the second gesture would use it
     * after close, which is worse than leaking.
     */
    @Test
    fun aRecomposedDraggableHoldsOneReferencePerPassPerCallback() {
        Python3.exec(
            """
            import sys
            from androidx.compose.material3 import Text
            
            def _acc_on_delta(delta):
                pass
            def _acc_on_drag_started(x, y):
                pass
            def _acc_on_drag_stopped(velocity):
                pass
                
            _acc_base_delta = sys.getrefcount(_acc_on_delta)
            _acc_base_started = sys.getrefcount(_acc_on_drag_started)
            _acc_base_stopped = sys.getrefcount(_acc_on_drag_stopped)
            """.trimIndent(),
        )
        val passes = 12
        val body = mutableStateOf(0)
        val scene = ImageComposeScene(width = 80, height = 80, density = Density(1f)) {
            PythonComposition(
                """
                from fixture.compose import emptyModifier, pythonDraggable
                from androidx.compose.foundation.layout import size__Dp
                from androidx.compose.material3 import Text
                
                _m = pythonDraggable(size__Dp(emptyModifier(), 48.0), _acc_on_delta, _acc_on_drag_started, _acc_on_drag_stopped)
                Text(str(${body.value}), modifier=_m)
                """.trimIndent()
            )
        }
        
        try {
            scene.render()
            for (pass in 1 until passes) {
                body.value = pass
                Snapshot.sendApplyNotifications()
                scene.render()
            }
        } finally {
            scene.close()
        }
        
        fun pyInt(expr: String): Int = Python3.import("__main__").getAttr("__dict__").let { globals ->
            Python3.eval(expr, 258, globals, globals).toString().toInt()
        }
        
        val refsDelta = pyInt("sys.getrefcount(_acc_on_delta)") - pyInt("_acc_base_delta")
        val refsStarted = pyInt("sys.getrefcount(_acc_on_drag_started)") - pyInt("_acc_base_started")
        val refsStopped = pyInt("sys.getrefcount(_acc_on_drag_stopped)") - pyInt("_acc_base_stopped")
        
        println("Draggable leak test: refsDelta=$refsDelta, refsStarted=$refsStarted, refsStopped=$refsStopped after $passes passes")
        
        // Pinned as measured, not as wanted. If these start coming back lower, the lifetime was
        // fixed -- see this test's doc for what that would take -- and the numbers here move with it.
        assertEquals(passes, refsDelta, "onDelta should hold one reference per pass until this is fixed")
        assertEquals(passes, refsStarted, "onDragStarted should hold one reference per pass until this is fixed")
        assertEquals(passes, refsStopped, "onDragStopped should hold one reference per pass until this is fixed")
    }
}
