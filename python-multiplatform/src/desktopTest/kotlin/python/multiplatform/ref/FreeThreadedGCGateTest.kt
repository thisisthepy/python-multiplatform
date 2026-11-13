package python.multiplatform.ref

import python.multiplatform.embedsFreeThreadedPython
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
 *
 * ### CPython 3.15 removed the process-memory gate
 *
 * Desktop embeds 3.15.0t since issue #158, and [freeThreadedSchedulingTracksWholeProcessFootprintNotThePythonHeap]
 * did what it was written to do: it failed when the gate changed. At the v3.15.0 tag
 * `gc_should_collect_mem_usage`, `get_process_mem_usage`, `last_mem` and `deferred_count` are gone
 * (`Python/gc_free_threading.c`, `Include/internal/pycore_interp_structs.h`), and
 * `gc_should_collect` (`:1996`) returns true once `young.count` clears the threshold and
 * `long_lived_total / 4` (`:2009`). The `old[0].threshold == 0` short circuit is still there
 * (`:2004`), and `gc_collect_main` still re-asks `gc_should_collect` for a heap-triggered
 * collection (`:2217`). The table above is the 3.14.7 record; the first test's assertions hold on
 * both, the second asserts the 3.15 shape.
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
     * It resets `young.count` and sets `long_lived_total` to what survived, which is what makes
     * the arms below independent of each other: every arm starts from the same scheduling state.
     * On 3.14t it also reset `last_mem` to the current process footprint (`gc_free_threading.c:2301`
     * at 3.14.7), the memory baseline the second test's 3.14 form depended on.
     */
    private fun forceCollect(gc: PyObject) {
        val collect = gc.getAttr("collect")
        collect.invoke().close()
        collect.close()
    }

    /** `gc.get_stats()[0]['collections']` -- incremented only *past* the gate at
     *  `gc_free_threading.c:2217` (v3.15.0; `:2328` at 3.14.7), so it counts collections that
     *  actually ran, not bits set. */
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
            "GATE[$label] freeThreaded=${embedsFreeThreadedPython} residue=$residue/$totalObjects " +
                "collectionsThatActuallyRan=${gen0Collections(gc) - collectionsBefore}"
        )
        forceCollect(gc)
        return residue
    }

    /**
     * `gc.set_threshold(_, 0, _)` -- a setting about **generation 1** -- is what decides whether
     * **generation 0** is collectable at all on the free-threaded build.
     *
     * `gc_free_threading.c:2004` (v3.15.0; `:2126` at 3.14.7) short-circuits `gc_should_collect` to `true` when
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

                    // Holds on both builds, for different reasons: free-threaded because :2004
                    // skips the gates, GIL-side because there was never a gate to skip.
                    assertTrue(
                        bypassed < reclaimedBound,
                        "with gc.set_threshold(2000, 0, 0) the eval-loop checkpoint should reclaim " +
                            "the bulk of $totalObjects dropped objects, but $bypassed remained. " +
                            "Free-threaded that means gc_free_threading.c:2004 (v3.15.0) no longer " +
                            "short-circuits gc_should_collect on old[0].threshold == 0"
                    )

                    if (!embedsFreeThreadedPython) {
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
     * Whether the Python cyclic collector runs must not depend on the **JVM's** memory.
     *
     * Faulting in 400 MB of ballast partway through the workload changes no threshold, allocates
     * no extra Python container and touches nothing the collector tracks. On 3.14t that opened
     * `gc_should_collect_mem_usage` (`gc_free_threading.c:2080` at 3.14.7), which asked whether
     * the *whole process* footprint had grown by more than `last_mem / 10` since the last
     * collection. Measured on 3.14t (3 consecutive JVMs, identical to the object), residue out of
     * 20,000:
     *
     * | arm | free-threaded 3.14t | global lock |
     * |---|---:|---:|
     * | baseline | 20,000 (0 collections) | 1,972 (9 collections) |
     * | 400 MB faulted in midway | **9,754 (1 collection)** | 1,988 (9 collections) |
     * | ballast dropped | 20,000 (0 collections) | 1,968 (9 collections) |
     *
     * 3.15.0 deleted that gate (see the class KDoc), so on the 3.15t desktop embeds the ballast
     * must be a no-op, as it always was with the global lock. Each arm starts from a forced
     * collection and runs the identical workload, so the scheduling inputs that remain
     * (`young.count`, the threshold, `long_lived_total`) are the same in all three and so is the
     * outcome. What is asserted free-threaded is exactly that: the ballast arm and the
     * ballast-dropped arm land within a quarter of the workload of the baseline. The bound is not
     * "everything was reclaimed", because whether `long_lived_total / 4` lets this process collect
     * at all depends on how many objects the test JVM's interpreter already holds, which this test
     * does not control.
     *
     * What it still catches: a process-memory gate coming back. The 3.14t row above differs from
     * its own baseline by 10,246 objects, more than twice the 5,000 allowed.
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

                    if (embedsFreeThreadedPython) {
                        // 3.15: no process-memory input, so the ballast must not change the
                        // outcome. A quarter of the workload absorbs a collection landing a few
                        // objects earlier or later in one arm; it does not absorb 3.14t's 10,246.
                        val tolerance = totalObjects / 4
                        assertTrue(
                            kotlin.math.abs(withBallast - baseline) < tolerance &&
                                kotlin.math.abs(dropped - baseline) < tolerance,
                            "free-threaded 3.15.0 has no process-memory gate (gc_should_collect, " +
                                "gc_free_threading.c:1996), so faulting in and dropping 400 MB of " +
                                "process memory should not change what the checkpoint collects, but " +
                                "the arms left $baseline / $withBallast / $dropped out of " +
                                "$totalObjects (allowed spread: $tolerance). A gate on whole-process " +
                                "memory, like 3.14's gc_should_collect_mem_usage, is back"
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
