package python.multiplatform.ref

import python.multiplatform.BuildConfig
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.ReleaseCounter
import python.multiplatform.ffi.withGIL
import python.native.ffi.PyGC_Collect
import python.native.ffi.Py_MakePendingCalls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * What an embedder owes CPython that a script never has to think about (ROADMAP §9).
 *
 * CPython defers work to a checkpoint that only the evaluation loop reaches — `_Py_HandlePending`,
 * called from the `_CHECK_PERIODIC` uop that opens every Python-level frame. Nothing in the C API
 * reaches it on its own. A program that drives the interpreter entirely through `PyObject_Call`
 * and friends therefore never merges the free-threaded build's biased reference-counting queue,
 * never processes QSBR-deferred frees and never runs a scheduled cyclic collection.
 *
 * The consequence measured in §9 is that references handed back by the cleaner *stay* handed back
 * and never freed: the counts are correct, the memory is not returned. These tests pin the shape
 * of that, the fact that the obvious Stable ABI candidate does not fix it, and the cost of the one
 * that does.
 */
class EvalCheckpointTest {

    private companion object {
        const val OUTER = 1000

        /**
         * Consecutive collector turns with no release at all that count as "the backlog is gone".
         *
         * More than one because a host that delivers finalisation in batches has quiet turns in
         * the middle of a drain; three in a row it does not.
         */
        const val QUIET_TURNS_TO_SETTLE = 3

        /** Cap on the turns the drain may spend, so a host that never goes quiet still gets tested. */
        const val SETTLE_TURN_BUDGET = 40
    }

    /**
     * [OUTER] lists, each holding one reference to [target], returned in a list that is the only
     * thing keeping their wrappers alive.
     *
     * A separate function on purpose, and not an inlined `repeat` in the caller. `repeat` is
     * inline, so `val outer` becomes a local *of the caller's frame*, and Kotlin/Native does not
     * clear a local's slot when it goes out of scope -- the last wrapper the loop built stays
     * reachable from that slot for as long as the test body runs, and the collector is right not to
     * reclaim it. Measured before this was split out: iOS released 999 of 1000 in all 8 runs, with
     * the target's count resting at exactly one above where it started, and the wait burning its
     * whole budget every time. Desktop and wasmJs released all 1000 from the same source, so this
     * is Kotlin/Native's frame scanning rather than the shape of the loop -- the JVM's collector
     * uses JIT liveness and a wasmJs test body has already returned by the time the host turns run.
     *
     * Building them here means the frame holding that last reference has returned before anything
     * waits on the collector. Measured after: iOS releases all 1000 on the first attempt.
     */
    private fun buildOuterLists(listType: PyObject, target: PyObject): MutableList<PyObject> {
        val built = ArrayList<PyObject>(OUTER)
        repeat(OUTER) {
            val outer = listType()
            val append = outer.getAttr("append")
            // `append(...)` hands back a wrapper for the `None` it returned. Dropping it made the
            // setup owe the cleaner exactly OUTER releases that have nothing to do with the outer
            // lists -- the decoy the KDoc on the test records. Closed, not dropped.
            append(target).close()
            append.close()
            built.add(outer)
        }
        return built
    }

    private fun refCount(target: PyObject): Long {
        val sys = Python3.import("sys")
        val getrefcount = sys.getAttr("getrefcount")
        try {
            val n = getrefcount(target)
            try {
                return n.toString().toLong()
            } finally {
                n.close()
            }
        } finally {
            getrefcount.close()
            sys.close()
        }
    }

    /**
     * Drives the §9 scenario by hand, with automatic checkpoints switched off, so that each step
     * is attributable: the cleaner runs, the count does not move, `Py_MakePendingCalls` does not
     * move it either, and [Python3.drainPendingReleases] does.
     *
     * The free-threaded and default builds assert different things because they *are* different:
     * with the global lock there is no deferred-release queue at all and the cleaner's `Py_DecRef`
     * frees on the spot, which is its own invariant worth holding on to.
     *
     * ### Why the wait is written the way it is
     *
     * [ReleaseCounter] is process-wide, so "the cleaner released $OUTER references" is not by
     * itself a statement about *this* test's references, and on wasmJs that difference is the
     * difference between passing and failing. Finalisation there is a host task: nothing is
     * released until something yields to the engine, this loop is the first thing that does, and
     * so it is where every wrapper dropped earlier in the process gets paid off. The count reaches
     * $OUTER in the opening turns on other people's garbage and the loop stops before one outer
     * wrapper of ours has been collected.
     *
     * Measured before the fix: one failure in 20 full-suite wasmJs runs, always
     * `before: 1002, after: 1002` -- the guard above reporting the cleaner had released $OUTER
     * while the target's count had not moved by one. Thirty runs of this class alone never failed,
     * because in isolation there is far less owed. The largest single decoy was this test's own
     * setup: `append(target)` returns a wrapper for `None` and the loop below used to drop all
     * $OUTER of them, which is exactly the number the wait was looking for.
     *
     * Three things follow, and all three are here:
     *
     *  * every wrapper the setup creates is closed, so the setup owes nothing;
     *  * the loop drains what the *rest of the process* owes before it starts counting, and it
     *    does that while this test's wrappers are still held so they cannot be part of the drain;
     *  * where a reading exists that belongs to this test alone, the loop waits on that instead of
     *    on the counter. With the global lock a released outer wrapper frees its list and hands the
     *    target back a reference, so the target's own count says how many of *ours* the cleaner has
     *    reached. The free-threaded build has no such reading -- that it has none is the very thing
     *    the branch below asserts -- and there the drained counter is the evidence.
     */
    @Test
    fun testDrainPendingReleasesReclaimsWhatTheCleanerGaveBack(): CollectorTestResult {
        // No subject on a platform without a cleaner: nothing releases the wrappers, so the
        // question this test asks -- what the checkpoint does with what the cleaner gave back --
        // has nothing to act on. GCLeakTest fails there and names the real cause.
        if (!cleanerReleasesAutomatically) return collectorTest(0, { true }, { })
        if (!Python3.isInitialized) Python3.initialize()

        val previousInterval = Python3.autoDrainInterval
        Python3.autoDrainInterval = 0
        try {
            val builtins = Python3.import("builtins")
            val listType = builtins.getAttr("list")
            val target = listType()

            val refBefore = refCount(target)

            // Each outer list holds one reference to `target`. The wrappers are held until the
            // count has been sampled so that the build-up is deterministic; see the note on
            // GCLeakTest.testCascadingReleaseOnGC for why letting them die inside the loop races
            // the very mechanism under test.
            var held: MutableList<PyObject>? = buildOuterLists(listType, target)

            val refAfterBuild = refCount(target)
            assertTrue(
                refAfterBuild >= refBefore + OUTER,
                "the outer lists should have raised the target's count by $OUTER " +
                    "(before: $refBefore, after: $refAfterBuild)"
            )

            // Cost of the probe itself, in releases, so the wait loop below can tell the cleaner's
            // work apart from its own.
            val probeMark = ReleaseCounter.released
            refCount(target)
            val releasesPerProbe = ReleaseCounter.released - probeMark

            // Phase 0 of the loop: what the rest of the process still owes the cleaner, flushed
            // before anything is counted. `held` is deliberately still holding this test's
            // wrappers throughout, so they cannot be part of what gets flushed here.
            var settling = true
            var settleTurns = 0
            var quietTurns = 0
            val backlogStart = ReleaseCounter.released
            var lastReleased = backlogStart
            var backlogFlushed = 0

            // Phase 1: this test's own wrappers, counted from a standing start.
            var releasedBefore = 0
            var attempts = 0
            var cleanerReleases = 0
            var refNow = refAfterBuild

            // Driven rather than spun. On wasmJs the cleaner is a host finalisation callback that
            // arrives on a task, so a `while` loop here would ask 200 times inside one job and see
            // nothing; see `commonTest`'s CollectorTestResult.
            return collectorTest(maxAttempts = SETTLE_TURN_BUDGET + 200, attempt = {
                if (settling) {
                    val seen = ReleaseCounter.released
                    if (seen == lastReleased) {
                        quietTurns++
                    } else {
                        quietTurns = 0
                        lastReleased = seen
                    }
                    settleTurns++
                    if (quietTurns >= QUIET_TURNS_TO_SETTLE || settleTurns >= SETTLE_TURN_BUDGET) {
                        backlogFlushed = ReleaseCounter.released - backlogStart
                        settling = false
                        // Only now does anything of this test's become collectable, and only now
                        // does the counter start meaning something about it.
                        held!!.clear()
                        held = null
                        releasedBefore = ReleaseCounter.released
                    }
                    false
                } else {
                    refNow = refCount(target)
                    attempts++
                    cleanerReleases = ReleaseCounter.released - releasedBefore - attempts * releasesPerProbe
                    // With the global lock, `refNow` is the reading that belongs to this test and
                    // to nothing else, so it is not enough for the counter alone to be satisfied.
                    cleanerReleases >= OUTER &&
                        (BuildConfig.pythonFreeThreaded || refNow <= refBefore)
                }
            }, finish = {
                try {
                    val diagnosis =
                        "released by the cleaner: $cleanerReleases, attempts: $attempts, " +
                            "backlog flushed before counting started: $backlogFlushed, " +
                            "target count now: $refNow (was $refBefore before the build, " +
                            "$refAfterBuild after it)"

                    // Printed on every run, not only on failure: `backlog flushed` is how much the
                    // rest of the process still owed the cleaner when this test began, and it is
                    // the quantity that used to be silently counted as this test's own.
                    println("--- cleaner accounting --- $diagnosis")

                    assertTrue(
                        cleanerReleases >= OUTER,
                        "the cleaner has to have released the $OUTER outer wrappers before this test can " +
                            "say anything about what happened to the memory ($diagnosis). " +
                            "If this fires, the failure is in GC-driven release, not in checkpointing."
                    )

                    val refAfterCleaner = refCount(target)

                    if (BuildConfig.pythonFreeThreaded) {
                        assertEquals(
                            refAfterBuild,
                            refAfterCleaner,
                            "free-threaded: a Py_DecRef from a thread that does not own the object is " +
                                "queued to its owner rather than run, so $OUTER completed decrefs must " +
                                "leave the count exactly where it was. If this now differs, the runtime " +
                                "has started merging somewhere else and the rest of this test is stale."
                        )

                        // Py_MakePendingCalls is the Stable ABI function that looks like it should do
                        // this. It does not: it handles _PY_CALLS_TO_DO_BIT and _PY_SIGNALS_PENDING_BIT
                        // and returns, while the merge hangs off _PY_EVAL_EXPLICIT_MERGE_BIT, which only
                        // _Py_HandlePending clears.
                        val pendingResult = withGIL { Py_MakePendingCalls() }
                        assertEquals(0, pendingResult, "Py_MakePendingCalls should not have failed")
                        assertEquals(
                            refAfterBuild,
                            refCount(target),
                            "Py_MakePendingCalls must not be mistaken for a checkpoint: it does not merge " +
                                "the biased reference-counting queue"
                        )

                        Python3.drainPendingReleases()
                        val refAfterDrain = refCount(target)
                        assertTrue(
                            refAfterDrain < refAfterBuild,
                            "reaching an eval-loop checkpoint should have merged all $OUTER queued " +
                                "releases (before: $refAfterBuild, after: $refAfterDrain)"
                        )
                    } else {
                        // `<= refBefore`, not merely `< refAfterBuild`: every one of the $OUTER
                        // outer lists holds exactly one reference to the target, so a cleaner that
                        // frees on the spot puts the count back where it started and nowhere in
                        // between. The weaker form passed on one list out of a thousand, which is
                        // the shape a wrong-reason pass takes here.
                        assertTrue(
                            refAfterCleaner <= refBefore,
                            "with the global lock there is no deferred-release queue, so the cleaner's " +
                                "Py_DecRef should already have freed the outer lists " +
                                "(before the build: $refBefore, after it: $refAfterBuild, " +
                                "after the cleaner: $refAfterCleaner; $diagnosis)"
                        )
                    }

                    target.close()
                    listType.close()
                    builtins.close()
                } finally {
                    Python3.autoDrainInterval = previousInterval
                }
            })
        } catch (t: Throwable) {
            // Only reached if the *setup* above threw; the happy path restores the interval in
            // `finish`, which runs after the collector has been given its chances.
            Python3.autoDrainInterval = previousInterval
            throw t
        }
    }

    /**
     * A checkpoint must be cheap enough to ride on ordinary calls, which is the whole reason it is
     * a cached function object and not `exec("pass")` — the latter recompiles a module every time.
     *
     * The assertion is the ordering, not an absolute number: absolute nanoseconds are a property
     * of the machine, but "a cached call beats recompiling" is a property of the design and would
     * be violated the moment the checkpoint started compiling, importing or allocating.
     */
    @Test
    fun testCheckpointCostAgainstTheAlternatives() {
        if (!Python3.isInitialized) Python3.initialize()

        val previousInterval = Python3.autoDrainInterval
        Python3.autoDrainInterval = 0
        try {
            // Build the cached callable before timing anything.
            Python3.drainPendingReleases()

            val emptyScopeNs = timeNs(20_000) { withGIL { } }
            val drainNs = timeNs(20_000) { Python3.drainPendingReleases() }
            val execNs = timeNs(2_000) { Python3.exec("pass") }
            val makePendingNs = timeNs(20_000) { withGIL { Py_MakePendingCalls() } }
            val gcCollectNs = timeNs(100) { withGIL { PyGC_Collect() } }

            println(
                "\n--- eval-loop checkpoint cost (freeThreaded=${BuildConfig.pythonFreeThreaded}) ---\n" +
                    "withGIL { }                     ${emptyScopeNs.format()} ns/op\n" +
                    "Python3.drainPendingReleases()  ${drainNs.format()} ns/op\n" +
                    "withGIL { Py_MakePendingCalls } ${makePendingNs.format()} ns/op   (does NOT merge)\n" +
                    "Python3.exec(\"pass\")            ${execNs.format()} ns/op\n" +
                    "withGIL { PyGC_Collect() }      ${gcCollectNs.format()} ns/op   (merges every thread)\n"
            )

            assertTrue(
                drainNs < execNs,
                "a cached, already-compiled checkpoint should cost less than recompiling a module " +
                    "(drain: ${drainNs.format()} ns, exec(\"pass\"): ${execNs.format()} ns)"
            )
            assertTrue(
                drainNs < gcCollectNs,
                "a checkpoint should cost less than a full cyclic collection " +
                    "(drain: ${drainNs.format()} ns, PyGC_Collect: ${gcCollectNs.format()} ns)"
            )
        } finally {
            Python3.autoDrainInterval = previousInterval
        }
    }

    /**
     * The automatic path must not fire from a cleaner. It is suppressed there for two reasons —
     * the queue that needs merging belongs to the owning thread, and running Python on a cleaner is
     * what ROADMAP §1 turned into a deadlock — and neither is visible from the outside, so it is
     * pinned here by counting checkpoints while only cleaners are running.
     */
    @Test
    fun testCleanerActivityAloneTakesNoCheckpoint(): CollectorTestResult {
        // No subject on a platform without a cleaner: nothing releases the wrappers, so the
        // question this test asks -- what the checkpoint does with what the cleaner gave back --
        // has nothing to act on. GCLeakTest fails there and names the real cause.
        if (!cleanerReleasesAutomatically) return collectorTest(0, { true }, { })
        if (!Python3.isInitialized) Python3.initialize()

        val previousInterval = Python3.autoDrainInterval
        // Force the automatic path fully on: every outermost scope is a candidate.
        Python3.autoDrainInterval = 1
        try {
            val builtins = Python3.import("builtins")
            val listType = builtins.getAttr("list")
            val target = listType()

            // Drop 500 wrappers on the floor for the cleaner to pick up, then let it work without
            // this thread entering withGIL at all.
            repeat(500) { PyObject(target.pointer, borrowed = true) }

            val ranBefore = ReleaseCounter.ran
            val checkpointsBefore = Python3.CheckpointCounter.reached
            var attempts = 0

            return collectorTest(maxAttempts = 200, attempt = {
                attempts++
                ReleaseCounter.ran - ranBefore >= 100
            }, finish = {
                try {
                    val cleanerRuns = ReleaseCounter.ran - ranBefore
                    val checkpointsDuring = Python3.CheckpointCounter.reached - checkpointsBefore

                    assertTrue(
                        cleanerRuns > 0,
                        "no cleaner ran, so this test proved nothing (attempts: $attempts). " +
                            "That is the GCLeakTest.testCleanerMechanismRuns failure, not this one."
                    )
                    assertEquals(
                        0L,
                        checkpointsDuring,
                        "cleaner threads released $cleanerRuns references and must not have taken a " +
                            "single checkpoint while doing it (took: $checkpointsDuring)"
                    )

                    // And the same activity, seen from a thread that does enter Python, does take one.
                    val checkpointsBeforeUse = Python3.CheckpointCounter.reached
                    repeat(4) { withGIL { } }
                    assertTrue(
                        Python3.CheckpointCounter.reached > checkpointsBeforeUse,
                        "an ordinary caller entering withGIL after the cleaner has released something " +
                            "should take the checkpoint the cleaner declined"
                    )

                    target.close()
                    listType.close()
                    builtins.close()
                } finally {
                    Python3.autoDrainInterval = previousInterval
                }
            })
        } catch (t: Throwable) {
            Python3.autoDrainInterval = previousInterval
            throw t
        }
    }

    private fun timeNs(iterations: Int, block: () -> Unit): Double {
        repeat(iterations / 10 + 1) { block() }
        val elapsed = measureTime { repeat(iterations) { block() } }
        return elapsed.inWholeNanoseconds.toDouble() / iterations
    }

    private fun Double.format(): String {
        val scaled = (this * 100).toLong()
        return "${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
    }
}
