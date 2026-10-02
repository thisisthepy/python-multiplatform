package fixture.compose

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonxAdapter
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **What a live UI costs**: how much one composition accumulates over [PASSES] recompositions.
 *
 * `ce1de0c3` recorded the shape of the problem without a number -- *"a wrapper is allocated per
 * crossing and there is no interning, so n recompositions hold n Python callables"* -- and
 * `ComposableRenderTest.theHolderSurvivesARecompositionAndReleasesBothCallablesAtDisposal` proves it
 * for exactly n = 2, where "2" is indistinguishable from "the two different callables that were
 * deliberately passed". This measures the case that matters instead: **the same callable, passed
 * again on every pass**, which is what a UI that recomposes several times a second actually does.
 *
 * ### The three things counted, and why none of them alone is the answer
 *
 * | counter | what it moves for |
 * |---|---|
 * | [PythonCallableScope.liveCount] | one per wrapper the scope is holding open |
 * | `HandleTable.liveCount` | one per wrapper root, plus whatever an invocation left rooted |
 * | `sys.getrefcount` of the Python callable | one per **thunk** built over it, which is the reference Kotlin is holding |
 *
 * The refcount is the honest one -- it is Python's own accounting and cannot be fooled by a Kotlin
 * table that forgot to grow -- but it moves by one for a *thunk*, and `_callable_thunk` hands a plain
 * slot's callable over unwrapped, so for those it moves for the callable itself. The scope count is
 * the one to fix; the other two are there so that "the scope stopped growing" cannot pass while the
 * cost merely moved somewhere else.
 *
 * ### What counts as the same callable
 *
 * Not `is`. A `lambda:` written inline is a **new object on every pass** (`docs/archive/pythonx-adapter-design.md`
 * records this), so an identity test on the callable interns nothing at all for the spelling that
 * appears in every example in this module. The key is structural and is described where it is
 * computed, in `PythonxAdapter`'s `_intern_key`; what this file asserts is the consequence:
 * `Column(content=lambda: Text('hi'))` composed [PASSES] times holds **one** wrapper, and
 * `Column(content=_named)` holds one too.
 *
 * [aCapturedValueThatChangesIsADifferentCallableAndIsNotShared] is the other half and is not a
 * failure: two lambdas that close over different values do different things, and giving them one
 * wrapper would render the first pass's value forever.
 */
class RecompositionAccumulationTest {

    @BeforeTest
    fun installProducers() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        UpcallTable.install(ArtifactTable.fragments)
        check(UpcallBootstrap.publishToGlobals()) { "UpcallBootstrap.publishToGlobals() failed" }
        PythonxAdapter.install(PYTHONX_RAW_VALUE_CLASSES)
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    /**
     * **A named Python function in a composable `content` slot, [PASSES] passes.**
     *
     * The easiest case there is: `_content` is one object for the whole test, so even an `id()`-keyed
     * cache would answer here. It is stated first because a failure here and a failure in
     * [anInlineLambdaRecomposedManyTimesHoldsOneWrapper] mean different things -- this one says the
     * cache is not consulted at all, that one says the key is wrong for the spelling everyone writes.
     */
    @Test
    fun aNamedCallableRecomposedManyTimesHoldsOneWrapper() {
        Python3.exec(
            """
            import sys
            from androidx.compose.material3 import Text

            def _acc_named():
                Text('hi')

            _acc_base = sys.getrefcount(_acc_named)
            """.trimIndent(),
        )
        val measured = measure("_acc_named") { pass -> columnCalling("_acc_named", pass) }
        report("named function", measured)

        assertEquals(
            1, measured.wrappers,
            "the scope holds one wrapper per pass: ${measured.wrappers} after $PASSES recompositions",
        )
        assertEquals(
            1, measured.heldRefs,
            "Kotlin holds one Python reference per pass: ${measured.heldRefs} after $PASSES recompositions",
        )
        assertReusedTheRest(measured, crossings = PASSES)
        assertEveryPassDrew(measured)
        assertReleasedExactlyOnce(measured)
    }

    /**
     * **The spelling every example in this module uses**, and the one an `id()` key cannot serve:
     * `content=lambda: Text('hi')` builds a fresh function object on every pass.
     *
     * What it does *not* build fresh is the code object -- CPython stores it in the enclosing code's
     * constants, so `lambda`s from one source position share one `__code__` for the life of the
     * module. That, and an empty closure, is the whole of why this can be interned.
     */
    @Test
    fun anInlineLambdaRecomposedManyTimesHoldsOneWrapper() {
        Python3.exec(
            """
            import sys
            from androidx.compose.material3 import Text
            """.trimIndent(),
        )
        val measured = measure(null) { pass ->
            "# pass $pass\n" +
                "from androidx.compose.foundation.layout import Column\n" +
                "from androidx.compose.material3 import Text\n" +
                "Column(content=lambda: Text('hi'))"
        }
        report("inline lambda", measured)

        assertEquals(
            1, measured.wrappers,
            "an inline lambda is a new object per pass and was not interned: ${measured.wrappers} wrappers",
        )
        assertReusedTheRest(measured, crossings = PASSES)
        assertEveryPassDrew(measured)
        assertReleasedExactlyOnce(measured)
    }

    /**
     * **A plain (non-composable) callback slot**, which takes a different route through
     * `_callable_thunk`: a `(Boolean) -> Unit` whose callable already accepts its one argument is
     * handed to Kotlin *unwrapped*, so the reference Kotlin holds is to the callable itself.
     *
     * Stated separately because that branch is the one where a wrapper-level cache could look like it
     * worked while the refcount kept climbing -- there is no thunk in between to hide behind.
     */
    @Test
    fun aPlainCallbackSlotRecomposedManyTimesHoldsOneWrapper() {
        Python3.exec(
            """
            import sys

            _acc_seen = []

            def _acc_toggle(value):
                _acc_seen.append(value)

            _acc_base = sys.getrefcount(_acc_toggle)
            """.trimIndent(),
        )
        val measured = measure("_acc_toggle") { pass ->
            "# pass $pass\n" +
                "from androidx.compose.material3 import Checkbox\n" +
                "Checkbox(False, onCheckedChange=_acc_toggle)"
        }
        report("plain callback", measured)

        assertEquals(
            1, measured.wrappers,
            "the scope holds one wrapper per pass: ${measured.wrappers} after $PASSES recompositions",
        )
        assertEquals(
            1, measured.heldRefs,
            "Kotlin holds one Python reference per pass: ${measured.heldRefs} after $PASSES recompositions",
        )
        assertReusedTheRest(measured, crossings = PASSES)
        assertEveryPassDrew(measured)
        assertReleasedExactlyOnce(measured)
    }

    /**
     * **The limit, asserted rather than hoped for.** A lambda that closes over a value which changes
     * between passes is a *different* callable each time and must get a different wrapper: sharing one
     * would leave Compose calling the first pass's capture forever, which is a wrong frame rather than
     * a leak.
     *
     * So this test asserts accumulation -- one wrapper per distinct capture -- and that is the honest
     * statement of what interning does not solve. `docs`-level discussion of a release point that
     * would solve it belongs with the report; what belongs here is the number.
     */
    @Test
    fun aCapturedValueThatChangesIsADifferentCallableAndIsNotShared() {
        Python3.exec(
            """
            import sys
            from androidx.compose.material3 import Text

            def _acc_make(n):
                return lambda: Text('hi' * n)
            """.trimIndent(),
        )
        val measured = measure(null) { pass ->
            "# pass $pass\n" +
                "from androidx.compose.foundation.layout import Column\n" +
                "Column(content=_acc_make(${pass % DISTINCT_CAPTURES + 1}))"
        }
        report("capturing lambda over $DISTINCT_CAPTURES distinct values", measured)

        assertEquals(
            DISTINCT_CAPTURES, measured.wrappers,
            "expected one wrapper per distinct capture, not per pass",
        )
        assertReusedTheRest(measured, crossings = PASSES)
        assertEveryPassDrew(measured)

        // **The stale-frame guard, and the reason a capture is compared at all.** The three captures
        // draw three different amounts of ink, and every later pass has to draw the amount its own
        // capture calls for. A cache keyed on the code object alone would answer every pass with the
        // first capture's wrapper and this list would be twelve copies of one number -- which is a
        // wrong frame, not a leak, and no reference count anywhere would notice.
        val cycle = measured.inkPerPass.take(DISTINCT_CAPTURES)
        assertEquals(
            DISTINCT_CAPTURES, cycle.toSet().size,
            "the three captures did not draw three different things, so this cannot detect a stale one: $cycle",
        )
        assertEquals(
            List(PASSES) { cycle[it % DISTINCT_CAPTURES] },
            measured.inkPerPass,
            "a pass drew another capture's content, so a wrapper was shared between two callables",
        )
        assertReleasedExactlyOnce(measured)
    }

    /**
     * **The same callable in two different slots is two wrappers**, because the wrapper carries the
     * slot's invocation convention: a composable `content` threads a composer and a plain `onClick`
     * does not, and one object cannot be both. A cache keyed on the callable alone would hand the
     * `content` wrapper to `onClick` and invoke it with two arguments it has no slots for.
     */
    @Test
    fun oneCallableInTwoSlotShapesIsNotSharedBetweenThem() {
        Python3.exec(
            """
            import sys
            from androidx.compose.material3 import Text

            def _acc_both():
                Text('hi')

            _acc_base = sys.getrefcount(_acc_both)
            """.trimIndent(),
        )
        val measured = measure("_acc_both") { pass ->
            "# pass $pass\n" +
                "from androidx.compose.material3 import Button\n" +
                "Button(onClick=_acc_both, content=_acc_both)"
        }
        report("one callable, two slot shapes", measured)

        assertEquals(2, measured.wrappers, "expected one wrapper per slot shape, held across all passes")
        assertEquals(2, measured.heldRefs, "expected exactly two references held, one per slot shape")
        assertReusedTheRest(measured, crossings = 2 * PASSES)
        assertEveryPassDrew(measured)
        assertReleasedExactlyOnce(measured)
    }

    /**
     * **The precondition a release point between recompositions would need**, and it holds -- which
     * is not what was expected.
     *
     * The sweep worth wanting is: at the end of a Python pass, give back every wrapper the pass
     * neither created nor reused, because the body just re-ran and re-supplied everything still in
     * use. Its soundness rests on one claim -- *nothing Compose still holds goes untouched across a
     * pass* -- and the obvious way for that to fail is **skipping**. Interning makes it plausible:
     * reusing a wrapper hands `Column` the *same object* in its `content` slot, so
     * `composer.changed(content)` answers false, and a `Column` that skips keeps its children as they
     * are without re-composing them. A `content` that itself declares a `content` would then have its
     * inner wrapper left untouched while Compose was still holding it, and a sweep would release
     * something live.
     *
     * **It does not happen here.** The measured shape over [SHORT_PASSES] passes of
     * `Column(content=lambda: Column(content=_acc_inner))` is *two* crossings on every pass, not two
     * and then one, and the inner content is invoked once per pass throughout. Nothing is skipped, so
     * every live wrapper is re-supplied every pass and the sweep's precondition is satisfied for this
     * shape.
     *
     * ### What this does and does not license
     *
     * It licenses the sweep for the shapes measured here. It does **not** prove Compose can never
     * skip a subtree whose `content` came from Python -- `PythonComposition` is re-entered with a
     * changed `String` on every pass, which is the strongest possible invalidation, and a host that
     * recomposed it for a subtler reason might see `$composer.skipping` come out true. So this test
     * exists as the **guard** rather than as the sweep: it is what would go red first if that
     * changed, and it should be read before anything releases a wrapper before disposal.
     *
     * There is a second shape this test says nothing about: a slot Compose **retains across a
     * re-supply**. `LaunchedEffect(key) { block }` keeps the block it already has when `key` is
     * unchanged and drops the new one, so a pass that supplies a *different* wrapper -- a lambda
     * whose capture moved -- leaves the retained one untouched and still live. Nothing in this module
     * binds such a declaration today, so `RetainedSlotSweepPreconditionTest` measures it directly
     * against `remember(key)` instead (the mechanism `LaunchedEffect(key)` is built on) -- and finds
     * the shape unsafe for a same-pass sweep: see `PythonCallables.kt`'s "release point" section for
     * the numbers and the verdict.
     *
     * The reason to want it at all is
     * [aCapturedValueThatChangesIsADifferentCallableAndIsNotShared]: interning bounds a repeated
     * callable to one wrapper and leaves a callable whose capture changes at one per distinct value.
     */
    @Test
    fun everyLiveWrapperIsResuppliedOnEveryPassEvenWhenNested() {
        Python3.exec(
            """
            _acc_inner_calls = [0]

            def _acc_inner():
                _acc_inner_calls[0] += 1
            """.trimIndent(),
        )

        PythonCallableArena.resetCounters()
        val body = mutableStateOf(nested(0))
        val scene = ImageComposeScene(width = 200, height = 60, density = Density(1f)) {
            PythonComposition(body.value)
        }
        val servedPerPass = ArrayList<Int>(PASSES)
        val innerCallsPerPass = ArrayList<Int>(PASSES)
        try {
            var previouslyServed = 0
            scene.render()
            val arena = PythonCallableArena.latest ?: error("no arena was remembered")
            servedPerPass += arena.scope.liveCount + arena.scope.reuseCount
            innerCallsPerPass += pyInt("_acc_inner_calls[0]")
            previouslyServed = servedPerPass[0]
            for (pass in 1 until SHORT_PASSES) {
                body.value = nested(pass)
                Snapshot.sendApplyNotifications()
                scene.render()
                val served = arena.scope.liveCount + arena.scope.reuseCount
                servedPerPass += served - previouslyServed
                previouslyServed = served
                innerCallsPerPass += pyInt("_acc_inner_calls[0]")
            }
            println(
                "nested content: crossings per pass=$servedPerPass " +
                    "cumulative inner invocations=$innerCallsPerPass " +
                    "wrappers=${arena.scope.liveCount} reused=${arena.scope.reuseCount}",
            )
            assertEquals(
                List(SHORT_PASSES) { 2 }, servedPerPass,
                "a pass served fewer than the two wrappers Compose is holding, so a subtree was " +
                    "skipped and an end-of-pass sweep would release something live",
            )
            assertEquals(
                2, arena.scope.liveCount,
                "expected exactly the outer and inner wrappers to be held across all passes",
            )
            assertEquals(
                List(SHORT_PASSES) { it + 1 }, innerCallsPerPass,
                "the innermost content was not invoked exactly once per pass: $innerCallsPerPass",
            )
        } finally {
            scene.close()
        }
        assertEquals(
            2, PythonCallableArena.released,
            "both wrappers, released once each -- the inner one is still Compose's at the end",
        )
    }

    // ------------------------------------------------------------------ the harness

    /**
     * What one composition accumulated.
     *
     * @param wrappers [PythonCallableScope.liveCount] read **before** disposal, which is the number
     *   this whole file is about.
     * @param rootDelta `HandleTable.liveCount` above the baseline taken before the scene existed,
     *   also read before disposal.
     * @param heldRefs how far the callable's refcount rose above its own baseline, or `-1` when the
     *   test named no callable to watch (an inline lambda has no name to take a refcount of).
     * @param releasedAtDisposal what every [PythonCallableScope.close] reported, summed.
     * @param rootsAfterDisposal `HandleTable.liveCount` above the same baseline, after disposal.
     * @param refsAfterDisposal the watched callable's refcount above its baseline, after disposal.
     */
    private class Accumulation(
        val wrappers: Int,
        val reuses: Int,
        val rootDelta: Int,
        val heldRefs: Int,
        val releasedAtDisposal: Int,
        val rootsAfterDisposal: Int,
        val refsAfterDisposal: Int,
        val inkPerPass: List<Int>,
    )

    /**
     * Composes [source] once, then [PASSES] - 1 more times, and reads the counters before and after
     * disposal.
     *
     * Recomposition is forced by giving `PythonComposition` a source string that differs by a comment,
     * which is the only lever this harness has: `PythonComposition(source: String)` reads no state of
     * its own, so an unchanged `String` is a skipped composable and no pass happens at all. A comment
     * is used rather than different Python because the *body* has to stay the same -- the claim is
     * about one callable crossing repeatedly, not about several.
     *
     * @param watched the name of a Python global whose refcount to follow, or `null`.
     */
    private fun measure(watched: String?, source: (Int) -> String): Accumulation {
        PythonCallableArena.resetCounters()
        val baseRoots = HandleTable.liveCount
        val baseRefs = if (watched == null) 0 else pyInt("sys.getrefcount($watched)")

        val body = mutableStateOf(source(0))
        val scene = ImageComposeScene(width = 200, height = 60, density = Density(1f)) {
            PythonComposition(body.value)
        }
        val wrappers: Int
        val reuses: Int
        val rootDelta: Int
        val heldRefs: Int
        val ink = ArrayList<Int>(PASSES)
        try {
            ink += inkOfImage(scene.render())
            for (pass in 1 until PASSES) {
                body.value = source(pass)
                Snapshot.sendApplyNotifications()
                ink += inkOfImage(scene.render())
            }
            assertEquals(1, PythonCallableArena.created, "the arena was rebuilt instead of remembered")
            val arena = PythonCallableArena.latest ?: error("no arena was remembered")
            wrappers = arena.scope.liveCount
            reuses = arena.scope.reuseCount
            rootDelta = HandleTable.liveCount - baseRoots
            heldRefs = if (watched == null) -1 else pyInt("sys.getrefcount($watched)") - baseRefs
        } finally {
            scene.close()
        }
        return Accumulation(
            wrappers = wrappers,
            reuses = reuses,
            rootDelta = rootDelta,
            heldRefs = heldRefs,
            inkPerPass = ink,
            releasedAtDisposal = PythonCallableArena.released,
            rootsAfterDisposal = HandleTable.liveCount - baseRoots,
            refsAfterDisposal = if (watched == null) 0 else pyInt("sys.getrefcount($watched)") - baseRefs,
        )
    }

    /**
     * The other half of every case, and `agent-rules` §14's reason for existing: a scope that gave
     * back more than it held is not a leak, it is a double release, and it does not fail where it
     * happens.
     *
     * `PythonCallableScope.close` answers `0` for every call after the first, so
     * `releasedAtDisposal > wrappers` can only come from something releasing twice; a *reference*
     * released twice shows up instead as a refcount that ends below where it started, which is what
     * the last assertion is for.
     */
    private fun assertReleasedExactlyOnce(measured: Accumulation) {
        assertEquals(
            measured.wrappers, measured.releasedAtDisposal,
            "the arena reported releasing ${measured.releasedAtDisposal} of ${measured.wrappers} wrappers",
        )
        // Stated before the leak check and not after it, because the two are different failures and
        // the double release is the one that does not fail where it happens: a crossing served from
        // the intern table closes the reference the trampoline just took for a thunk nobody will
        // use, and a second close of the *cached* wrapper's own reference would land here as a
        // count below the baseline rather than above it.
        assertTrue(
            measured.refsAfterDisposal >= 0,
            "the callable's refcount ended ${-measured.refsAfterDisposal} below its baseline, " +
                "which is a reference released twice",
        )
        assertEquals(
            0, measured.refsAfterDisposal,
            "the callable's refcount did not come back to where it started",
        )
        assertEquals(
            0, measured.rootsAfterDisposal,
            "handles left rooted after the composition was disposed",
        )
    }

    /**
     * The counterpart to every "held one wrapper" assertion, and the one that stops the whole file
     * from being satisfiable by a `content=` that silently stopped crossing: [crossings] calls were
     * made, [Accumulation.wrappers] were built, and the difference was served from the scope's table.
     */
    private fun assertReusedTheRest(measured: Accumulation, crossings: Int) {
        assertEquals(
            crossings - measured.wrappers, measured.reuses,
            "expected $crossings crossings to be served by ${measured.wrappers} wrapper(s) and " +
                "${crossings - measured.wrappers} reuse(s), got ${measured.reuses} reuse(s)",
        )
    }

    /** Every pass drew something, which is what says the interned wrapper was still invoked. */
    private fun assertEveryPassDrew(measured: Accumulation) {
        assertTrue(
            measured.inkPerPass.all { it > 0 },
            "a pass drew nothing, so the reused wrapper was not invoked: ${measured.inkPerPass}",
        )
    }

    private fun report(what: String, measured: Accumulation) {
        println(
            "recomposition accumulation [$what] over $PASSES passes: " +
                "wrappers=${measured.wrappers} reused=${measured.reuses} " +
                "handleRoots=+${measured.rootDelta} " +
                "refcount=+${measured.heldRefs} " +
                "released=${measured.releasedAtDisposal} " +
                "rootsAfter=${measured.rootsAfterDisposal} refsAfter=${measured.refsAfterDisposal} " +
                "ink=${measured.inkPerPass}",
        )
    }

    /** A `content` that declares a `content`: two wrappers, one nested in the other. */
    private fun nested(pass: Int): String =
        "# pass $pass\n" +
            "from androidx.compose.foundation.layout import Column\n" +
            "Column(content=lambda: Column(content=_acc_inner))"

    private fun columnCalling(name: String, pass: Int): String =
        "# pass $pass\n" +
            "from androidx.compose.foundation.layout import Column\n" +
            "Column(content=$name)"

    /** Non-background pixels of one frame. `ComposableRenderTest` has the same three lines; the two
     * are not shared because a test helper shared between test classes is a third thing that can be
     * wrong. */
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

    private fun pyInt(expression: String): Int =
        Python3.import("__main__").getAttr("__dict__").let { globals ->
            Python3.eval(expression, PY_EVAL_INPUT, globals, globals).toString().toInt()
        }

    private companion object {
        /** More than the two `ComposableRenderTest` already covers, and small enough that a failure
         * prints a number a reader can hold: a `content` that accumulates shows up as 12. */
        const val PASSES = 12

        /** How many *different* captures [aCapturedValueThatChangesIsADifferentCallableAndIsNotShared]
         * cycles through. Fewer than [PASSES], so "one per distinct capture" and "one per pass" are
         * different numbers and the test can tell them apart. */
        const val DISTINCT_CAPTURES = 3

        /** Enough passes to see the second one behave differently from the first, which is all
         * [aSkippedSubtreeKeepsAWrapperThePassNeverTouched] needs. */
        const val SHORT_PASSES = 4

        /** `Py_eval_input`. */
        const val PY_EVAL_INPUT = 258
    }
}
