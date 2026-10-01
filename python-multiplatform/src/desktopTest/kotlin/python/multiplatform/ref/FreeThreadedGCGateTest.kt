package python.multiplatform.ref

import python.multiplatform.BuildConfig
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the mechanism behind `docs/investigations/gc-scheduling-investigation.md` §8: why an eval-loop checkpoint
 * that demonstrably runs reclaims nothing on the free-threaded build, when the same checkpoint --
 * or three of them -- suffices with the global lock.
 *
 * It is not the checkpoint. Free-threaded, `_Py_RunGC` re-asks `gc_should_collect` *after* the
 * checkpoint has already read the scheduled bit, and it is that second question that answers no.
 * The relevant asymmetry in CPython 3.14.7, verified against the tagged source:
 *
 * | | global lock (`Python/gc.c`) | free-threaded (`Python/gc_free_threading.c`) |
 * |---|---|---|
 * | schedules on | `generations[0].count > threshold`, nothing else (`gc.c:1866`) | `gc_should_collect` (`:2153`) |
 * | re-checked when the checkpoint runs it | `gc_select_generation`, generation 0 on count alone (`gc.c:1258`) | `gc_should_collect` **again** (`:2328`) |
 * | process-memory gate | **none** -- `gc.c` has no `last_mem` and never calls `get_process_mem_usage` | `gc_should_collect_mem_usage` (`:2080`) |
 * | `long_lived_total / 4` | oldest generation only (`gc.c:1300`) | every generation-0 decision (`:2131`) |
 *
 * `gc_should_collect_mem_usage` compares the **whole process's** `phys_footprint`
 * (`task_info(TASK_VM_INFO)`, `:2010`) against `last_mem`, which is written only after a
 * collection (`:2301`). In a JVM-hosted embedder that number is JVM memory: ~20,000 small Python
 * lists cannot move it by the required `last_mem / 10`, so the gate stays shut no matter how many
 * checkpoints are taken, and it re-shuts itself by zeroing `young.count` into `deferred_count`
 * (`:2109`) on the way out. That is also the §8b latch -- once the JVM's footprint plateaus, the
 * condition can never become true again from the Python side.
 *
 * Both tests below are matched pairs: each manipulates something that the free-threaded source
 * says is load-bearing and the global-lock source says is irrelevant, and asserts exactly that
 * split. If CPython changes either gate, they fail and say which one.
 */
class FreeThreadedGCGateTest {

    private val cyclesCreated = 10_000
    private val objectsPerCycle = 2
    private val totalObjects = cyclesCreated * objectsPerCycle

    /** Same bound the GIL build is held to in [GCSchedulingMeasurementTest]: half the garbage. */
    private val reclaimedBound = totalObjects / 2

    private fun countTrackedObjects(gc: PyObject): Int {
        val getObjects = gc.getAttr("get_objects")
        val objects = getObjects.invoke()
        val lenAttr = objects.getAttr("__len__")
        val len = lenAttr.invoke()
        val count = len.toString().toInt()
        len.close(); lenAttr.close(); objects.close(); getObjects.close()
        return count
    }

    /**
     * `gc.collect()`.
     *
     * Free-threaded this also **resets `last_mem` to the current process footprint**
     * (`gc_free_threading.c:2301`, reached because `_Py_GC_REASON_MANUAL` skips the gate at
     * `:2328`), which is what makes the arms below independent of each other: every arm starts
     * from a freshly pinned memory baseline.
     */
    private fun forceCollect(gc: PyObject) {
        val collect = gc.getAttr("collect")
        collect.invoke().close()
        collect.close()
    }

    /** `gc.get_stats()[0]['collections']` -- incremented only *past* the gate at
     *  `gc_free_threading.c:2328`, so it counts collections that actually ran, not bits set. */
    private fun gen0Collections(gc: PyObject): Long {
        val f = gc.getAttr("get_stats")
        val t = f.invoke()
        val s = t.toString()
        t.close(); f.close()
        val i = s.indexOf("'collections':")
        return s.substring(i + 14).trimStart().takeWhile { it.isDigit() }.toLong()
    }

    private fun setThreshold(t0: Int, t1: Int, t2: Int) {
        Python3.exec("import gc; gc.set_threshold($t0, $t1, $t2)")
    }

    /** §8's workload: [n] unreachable two-element `list` cycles, entirely through the C API. */
    private fun buildAndDropCycles(builtins: PyObject, n: Int = cyclesCreated) {
        val listType = builtins.getAttr("list")
        try {
            repeat(n) {
                val a = listType()
                val b = listType()
                val aAppend = a.getAttr("append")
                val bAppend = b.getAttr("append")
                aAppend(b).close(); bAppend(a).close()
                aAppend.close(); bAppend.close(); a.close(); b.close()
            }
        } finally {
            listType.close()
        }
    }

    /** Runs the workload from a pinned baseline and reports what the checkpoint alone reclaimed. */
    private fun arm(gc: PyObject, builtins: PyObject, label: String, ballastMidway: Boolean = false): Int {
        forceCollect(gc)
        val initial = countTrackedObjects(gc)
        val collectionsBefore = gen0Collections(gc)
        buildAndDropCycles(builtins, cyclesCreated / 2)
        if (ballastMidway) {
            // b'x' * n memsets, so the pages are really faulted in and phys_footprint moves.
            Python3.exec("__pm_ballast = b'x' * (400 * 1024 * 1024)")
        }
        buildAndDropCycles(builtins, cyclesCreated / 2)
        val residue = countTrackedObjects(gc) - initial
        println(
            "GATE[$label] freeThreaded=${BuildConfig.pythonFreeThreaded} residue=$residue/$totalObjects " +
                "collectionsThatActuallyRan=${gen0Collections(gc) - collectionsBefore}"
        )
        forceCollect(gc)
        return residue
    }

    /**
     * `gc.set_threshold(_, 0, _)` -- a setting about **generation 1** -- is what decides whether
     * **generation 0** is collectable at all on the free-threaded build.
     *
     * `gc_free_threading.c:2126` short-circuits `gc_should_collect` to `true` when
     * `old[0].threshold == 0`, skipping both `long_lived_total/4` and the process-memory gate.
     * Nothing in `gc.c` reads that field while scheduling, so the same change must be a no-op with
     * the global lock. Run A/B/A in one JVM so the §8b latch cannot account for the difference.
     *
     * Measured (3 consecutive JVMs, identical to the object): free-threaded 20,000 / **1,318** /
     * 20,000; global lock 1,968 / 1,966 / 1,970.
     *
     * This is the assertion §8 could not make. Free-threaded had no bound at all before -- the
     * residue was bimodal, so any bound would have been a coin flip -- and it now has the same one
     * the GIL build is held to, conditional on opening the gate the source names.
     */
    @Test
    fun generationOneThresholdDecidesWhetherTheFreeThreadedCheckpointCanCollect() =
        PythonTestFixture.withInterpreter {
            val previous = Python3.autoDrainInterval
            try {
                Python3.autoDrainInterval = 32
                val builtins = Python3.import("builtins")
                val gc = Python3.import("gc")
                try {
                    setThreshold(2000, 10, 10)
                    val defaultBefore = arm(gc, builtins, "default-thresholds")
                    setThreshold(2000, 0, 0)
                    val bypassed = arm(gc, builtins, "old0-threshold-zero")
                    setThreshold(2000, 10, 10)
                    val defaultAfter = arm(gc, builtins, "default-thresholds-again")

                    // Holds on both builds, for different reasons: free-threaded because :2126
                    // skips the gates, GIL-side because there was never a gate to skip.
                    assertTrue(
                        bypassed < reclaimedBound,
                        "with gc.set_threshold(2000, 0, 0) the eval-loop checkpoint should reclaim " +
                            "the bulk of $totalObjects dropped objects, but $bypassed remained. " +
                            "Free-threaded that means gc_free_threading.c:2126 no longer " +
                            "short-circuits gc_should_collect on old[0].threshold == 0"
                    )

                    if (!BuildConfig.pythonFreeThreaded) {
                        // gc.c:1866 schedules on generations[0].count alone and gc.c:1258 selects
                        // generation 0 on count alone, so generation 1's threshold cannot matter.
                        assertTrue(
                            defaultBefore < reclaimedBound && defaultAfter < reclaimedBound,
                            "on the build with the global lock generation 1's threshold is not " +
                                "read while scheduling generation 0, so all three arms should " +
                                "reclaim alike, but the default arms left $defaultBefore and " +
                                "$defaultAfter against $bypassed for the bypassed arm"
                        )
                    }
                } finally {
                    setThreshold(2000, 10, 10)
                    gc.close(); builtins.close()
                }
            } finally {
                Python3.autoDrainInterval = previous
            }
        }

    /**
     * Free-threaded, whether the Python cyclic collector runs is a function of the **JVM's**
     * memory, not of Python's.
     *
     * `gc_should_collect_mem_usage` (`gc_free_threading.c:2080`) asks whether the *whole process*
     * footprint has grown by more than `last_mem / 10` since the last collection. So faulting in
     * 400 MB of ballast partway through the workload -- changing no threshold, allocating no extra
     * Python container, touching nothing the collector tracks -- should open the gate, and
     * dropping the ballast should shut it again. `gc.c` has no such gate, so the identical
     * manipulation must be a no-op with the global lock.
     *
     * Measured (3 consecutive JVMs, identical to the object), residue out of 20,000:
     *
     * | arm | free-threaded | global lock |
     * |---|---:|---:|
     * | baseline | 20,000 (0 collections) | 1,972 (9 collections) |
     * | 400 MB faulted in midway | **9,754 (1 collection)** | 1,988 (9 collections) |
     * | ballast dropped | 20,000 (0 collections) | 1,968 (9 collections) |
     *
     * The 400 MB figure assumes the test JVM's footprint is under ~4 GB, so that 400 MB clears the
     * `last_mem / 10` bar; the failure message says so, because a much larger heap would make this
     * test wrong rather than the mechanism wrong.
     */
    @Test
    fun freeThreadedSchedulingTracksWholeProcessFootprintNotThePythonHeap() =
        PythonTestFixture.withInterpreter {
            val previous = Python3.autoDrainInterval
            try {
                Python3.autoDrainInterval = 32
                val builtins = Python3.import("builtins")
                val gc = Python3.import("gc")
                try {
                    setThreshold(2000, 10, 10)
                    val baseline = arm(gc, builtins, "baseline")
                    val withBallast = arm(gc, builtins, "400MB-faulted-in-midway", ballastMidway = true)
                    Python3.exec("del __pm_ballast")
                    val dropped = arm(gc, builtins, "ballast-dropped")

                    if (BuildConfig.pythonFreeThreaded) {
                        // The whole finding: JVM memory growth, and nothing else, collected Python
                        // cycles. Deliberately not a tight bound -- how much a single collection
                        // takes depends on where in the workload the footprint crossed the bar.
                        assertTrue(
                            withBallast < baseline,
                            "faulting in 400 MB of process memory partway through the workload " +
                                "should have opened gc_should_collect_mem_usage " +
                                "(gc_free_threading.c:2100) and let the checkpoint collect, but " +
                                "the ballast arm left $withBallast against the baseline's " +
                                "$baseline out of $totalObjects. Either that gate is gone, or " +
                                "this JVM's footprint is large enough (>~4 GB) that 400 MB no " +
                                "longer clears last_mem/10"
                        )
                    } else {
                        // gc.c never calls get_process_mem_usage and has no last_mem field at all.
                        assertTrue(
                            baseline < reclaimedBound && withBallast < reclaimedBound && dropped < reclaimedBound,
                            "the build with the global lock has no process-memory gate, so all " +
                                "three arms should reclaim alike, but they left $baseline / " +
                                "$withBallast / $dropped out of $totalObjects"
                        )
                    }
                } finally {
                    Python3.exec("globals().pop('__pm_ballast', None)")
                    gc.close(); builtins.close()
                }
            } finally {
                Python3.autoDrainInterval = previous
            }
        }
}
