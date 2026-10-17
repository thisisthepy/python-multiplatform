package fixture.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonCallables
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

/**
 * **The measurement `PythonCallables.kt`'s "release point" section names as missing**: a slot Compose
 * retains across a re-supply -- the shape `remember(key) { value }` and `LaunchedEffect(key) { block }`
 * both have -- fed a callable whose capture changes every pass, so a *different* wrapper crosses on
 * every pass while the retaining slot goes on handing out the first one.
 *
 * ### Why this drives `pythonx.runtime.newFunction` directly instead of through a walked composable
 *
 * No walked declaration in this fixture's `androidx.compose.material3`/`foundation.layout` surface
 * retains across a re-supply -- `Column`'s `content` is invoked with whatever was passed on *every*
 * pass, which is exactly what
 * `RecompositionAccumulationTest.everyLiveWrapperIsResuppliedOnEveryPassEvenWhenNested` measured, and
 * that KDoc says outright: "nothing in this module binds such a declaration today." Nothing here adds
 * one either -- a retaining composable reachable *from Python* would need `FragmentScanner` to thread a
 * `$composer`, which is `ArtifactScanner`/`ComposableThunks`' job and is wired only for the androidx
 * packages this module walks.
 *
 * `pythonx.runtime.newFunction` is what any walked call site's crossing calls under the hood
 * (`PythonxAdapter._make_function`), and `PythonxAdapter.install(PYTHONX_RAW_VALUE_CLASSES)` -- already needed here for nothing
 * more than its dynamic `pythonx.*` package finder -- registers `PythonCallables.Fragment` as part of
 * installing, which is what makes `from pythonx.runtime import newFunction` resolve at all
 * (`PythonCallables.Fragment` is `internal` to `:python-multiplatform`, so this file cannot register it
 * directly the way `PythonCallablesProxyArgumentTest` does there). Calling it here, once per pass, with
 * a real capture-bearing Python closure, and feeding the resulting handle into a real `remember(key)`,
 * produces the same crossing a retaining walked declaration would produce without one having to exist.
 */
class RetainedSlotSweepPreconditionTest {

    @BeforeTest
    fun install() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        UpcallTable.install(FunctionTable.fragments + ArtifactTable.fragments)
        check(UpcallBootstrap.publishToGlobals()) { "UpcallBootstrap.publishToGlobals() failed" }
        PythonxAdapter.install(PYTHONX_RAW_VALUE_CLASSES)
        // `PythonProxySource.install()` renders its Python source from whatever is registered in
        // `UpcallTable` *at the moment it is called* -- called again here, after `PythonxAdapter
        // .install()` has registered `PythonCallables.Fragment`, is what actually gives
        // `pythonx.runtime` a module: called only once, before that registration (the order
        // `DraggableLeakTest` uses, where it does not matter because nothing there touches
        // `pythonx.runtime`), `from pythonx.runtime import newFunction` raises `ModuleNotFoundError`.
        PythonProxySource.install()
        RetainedHandleProbe.current = 0L
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    /**
     * **The number the sweep's precondition was waiting on.** [PASSES] passes, a constant retention
     * key, and a callable whose capture is the pass number -- so every pass crosses a genuinely
     * different wrapper (`aCapturedValueThatChangesIsADifferentCallableAndIsNotShared`'s shape) while
     * `remember("fixed")` keeps handing out pass 0's.
     *
     * Three things are measured, not assumed:
     *
     * 1. [RetainedHandleProbe.current] stays pass 0's handle for all [PASSES] passes -- confirms
     *    `remember(key)` really ignores the re-supply, in this exact Compose runtime, for this exact
     *    shape.
     * 2. [PythonCallableScope.liveCount] grows by exactly one every pass and [PythonCallableScope
     *    .reuseCount] stays zero -- confirms pass 0's wrapper is *never touched again* after pass 0:
     *    not rebuilt (its capture never recurs) and not reused (its key is never asked for again).
     * 3. Pass 0's Python callable's own refcount, read immediately after it crossed and again after
     *    every later pass has crossed and been discarded, is unchanged -- confirms the reference
     *    [PythonCallableScope] is holding for it is still there, not doubled and not dropped.
     *
     * (1) and (2) together are the sweep's failure case stated in counters: the wrapper a same-pass
     * "untouched" test would flag for release ((2)) is exactly the one something still needs ((1)).
     * (3) is why that release would be worse than a leak (`agent-rules` §14) rather than merely
     * redundant -- the reference is real and single, not already gone.
     */
    @Test
    fun aRetainingSlotKeepsPassZerosWrapperWhileLaterPassesCrossAndGoUntouched() {
        Python3.exec(
            """
            import sys

            from python.multiplatform.ffi.pythonx.PythonCallables import newFunction as _rs_new_function

            _rs_calls = []
            _rs_bodies = []

            def _rs_make(n):
                def _cb():
                    _rs_calls.append(n)
                return _cb
            """.trimIndent(),
        )

        val scope = PythonCallables.newScope()
        val handles = ArrayList<Long>(PASSES)
        val retainedAfterPass = ArrayList<Long>(PASSES)
        val liveAfterPass = ArrayList<Int>(PASSES)
        val reusedAfterPass = ArrayList<Int>(PASSES)

        // Every pass builds and crosses a genuinely different callable -- a distinct capture, hence
        // a distinct intern key -- which is what "a pass that supplies a different wrapper" means.
        // The crossing happens unconditionally, the same way a Python argument expression is always
        // evaluated before the call it is an argument to, regardless of what the callee goes on to
        // do with it.
        fun crossOnePass(pass: Int): Long {
            Python3.exec(
                """
                _rs_body = _rs_make($pass)
                _rs_bodies.append(_rs_body)
                _rs_body = None
                ${if (pass == 0) "_rs_base0 = sys.getrefcount(_rs_bodies[0])" else ""}
                _rs_handle_$pass = _rs_new_function(_rs_bodies[$pass], 0, False, '', 'capture:$pass')
                assert _rs_handle_$pass != 0, 'expected a real wrapper handle'
                ${if (pass == 0) "_rs_held0 = sys.getrefcount(_rs_bodies[0])" else ""}
                """.trimIndent(),
            )
            return pyLong("_rs_handle_$pass")
        }

        // Pass 0's handle has to exist *before* `ImageComposeScene` is constructed -- its content
        // lambda closes over `handleState`, and construction performs the first composition using
        // whatever `handleState.value` already is at that moment. Measured: getting this order wrong
        // (constructing the scene with a placeholder `0L` and setting the real value afterward) is
        // indistinguishable from "`remember(key)` never retains anything" -- every later pass reads
        // back `0L` too, because `remember` locked onto the *placeholder* as pass 0's value and every
        // later pass has the same (unchanged) key. `RecompositionAccumulationTest.measure` avoids this
        // the same way, initialising `mutableStateOf(source(0))` before constructing its scene.
        val scope0Handle = PythonCallables.withScope(scope) { crossOnePass(0) }
        handles += scope0Handle

        // One composition, recomposed [PASSES] - 1 more times -- `remember(key)` only retains
        // *across recompositions of the same composition*; a fresh `ImageComposeScene` per pass would
        // have no slot table to retain anything in, and every pass would look like "first
        // composition" instead.
        val handleState = mutableStateOf(scope0Handle)
        val scene = ImageComposeScene(width = 4, height = 4, density = Density(1f)) {
            RetainInProbe(key = "fixed", handle = handleState.value)
        }

        try {
            PythonCallables.withScope(scope) {
                scene.render()
                retainedAfterPass += RetainedHandleProbe.current
                liveAfterPass += scope.liveCount
                reusedAfterPass += scope.reuseCount

                for (pass in 1 until PASSES) {
                    val handle = crossOnePass(pass)
                    handles += handle

                    // From pass 1 on, `key` hasn't changed, so `remember` hands back what pass 0
                    // stored and this pass's handle is dropped on the floor -- by Compose, not by
                    // anything here.
                    handleState.value = handle
                    Snapshot.sendApplyNotifications()
                    scene.render()
                    retainedAfterPass += RetainedHandleProbe.current
                    liveAfterPass += scope.liveCount
                    reusedAfterPass += scope.reuseCount
                }
            }

            // Both read through the same expression (`_rs_bodies[0]`, never the reassigned-every-pass
            // `_rs_body` local) -- comparing refcounts of two *different* Python expressions is what
            // made this measurement look like a premature release the first time it was run.
            val after0 = pyInt("sys.getrefcount(_rs_bodies[0])")
            val base0 = pyInt("_rs_base0")
            val held0 = pyInt("_rs_held0")

            println(
                "retained-slot precondition: handles=$handles retained=$retainedAfterPass " +
                    "live=$liveAfterPass reused=$reusedAfterPass " +
                    "refcount(base0=$base0 held0=$held0 after0=$after0)",
            )

            // (1) Real Compose kept returning pass 0's handle throughout.
            assertEquals(
                List(PASSES) { handles[0] }, retainedAfterPass,
                "remember(key) returned a later pass's handle even though key never changed",
            )
            // (2) Every pass nonetheless built its own wrapper -- pass 0's was touched on pass 0 and
            // never again, because every capture (and so every intern key) was distinct.
            assertEquals(List(PASSES) { it + 1 }, liveAfterPass, "expected one new wrapper per pass")
            assertEquals(List(PASSES) { 0 }, reusedAfterPass, "expected no key to ever be reused")
            // (3) Pass 0's Python reference is exactly one over baseline, both right after it crossed
            // and after every later pass has crossed and discarded its own.
            assertEquals(1, held0 - base0, "the crossing did not take exactly one Python reference")
            assertEquals(
                held0, after0,
                "pass 0's reference count moved while later passes crossed and discarded their own -- " +
                    "either it was released early (worse than a leak) or double-counted",
            )
        } finally {
            scene.close()
            scope.close()
        }
    }

    private fun pyInt(expression: String): Int =
        Python3.import("__main__").getAttr("__dict__").let { globals ->
            Python3.eval(expression, PY_EVAL_INPUT, globals, globals).toString().toInt()
        }

    /** [pyInt], widened: a `HandleTable` root is a full 64-bit pointer-shaped value and routinely
     * exceeds [Int.MAX_VALUE], unlike the small refcounts and pass indices [pyInt] reads elsewhere
     * in this file. */
    private fun pyLong(expression: String): Long =
        Python3.import("__main__").getAttr("__dict__").let { globals ->
            Python3.eval(expression, PY_EVAL_INPUT, globals, globals).toString().toLong()
        }

    private companion object {
        /** Enough passes to distinguish "retained once" from "retained coincidentally": if retention
         * broke down on, say, pass 2, one or two passes would not show it. */
        const val PASSES = 6

        /** `Py_eval_input`. */
        const val PY_EVAL_INPUT = 258
    }
}

/** What [RetainInProbe] published on its most recent composition. A plain var and not a
 * `mutableStateOf` on purpose: nothing here reads it back *inside* a composition, so it needs no
 * snapshot semantics, only a way for the test driver outside the composition to see what `remember`
 * decided. */
private object RetainedHandleProbe {
    var current: Long = 0L
}

/**
 * The retaining slot itself: `remember(key) { handle }` is the whole of `LaunchedEffect(key) { block }`'s
 * own retention mechanism (`LaunchedEffectImpl` is built inside exactly this call), applied directly to
 * the handle a crossing produced instead of to a suspend block -- coroutines are not what is being
 * measured here, only whether the *value* Compose is asked to remember is the one it goes on returning.
 */
@Composable
private fun RetainInProbe(key: String, handle: Long) {
    RetainedHandleProbe.current = remember(key) { handle }
}
