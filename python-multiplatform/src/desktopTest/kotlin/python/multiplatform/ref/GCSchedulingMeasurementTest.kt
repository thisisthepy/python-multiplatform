package python.multiplatform.ref

import python.multiplatform.BuildConfig
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins ROADMAP §9 / `docs/investigations/gc-scheduling-investigation.md` §1 and §7 on the **default (GIL)**
 * build specifically: `_PY_GC_SCHEDULED_BIT` is read only by the eval-loop checkpoint, on both
 * builds, so an embedder that never runs Python bytecode never runs the cyclic collector either
 * -- with or without free threading. `autoDrainInterval` is saved and restored around each test;
 * the default (0 on this build) is never changed by running this file.
 *
 * Also runs, unmodified, on `-PpythonFreeThreaded=true`, where the automatic checkpoint reliably
 * fires but does not reliably reclaim: under this heavy single-thread, C-API-only workload the
 * residue swings between the GIL build's ~1,970 and the full ~20,000, run to run, so only the GIL
 * build gets a bound here. That swing is not a mystery any more -- it tracks the *JVM's* memory
 * footprint, see `docs/investigations/gc-scheduling-investigation.md` §8d and [FreeThreadedGCGateTest], which
 * does bound the free-threaded case once the responsible gate is opened. What *is* asserted on
 * both builds is that the residue is ordinary collectable cyclic garbage -- see
 * [assertResidueIsOrdinaryCollectableGarbage] and the KDoc on [measureCyclicGarbageWithAutoDrain].
 *
 * **Do not restore the inline `gc.get_objects()` chain.** Every temporary in it has to be closed;
 * the list holds the whole heap. See [countTrackedObjects].
 */
class GCSchedulingMeasurementTest {

    /** Each iteration below leaves one `a`/`b` cycle unreachable, i.e. 2 objects per round. */
    private val cyclesCreated = 10_000
    private val objectsPerCycle = 2

    /** Slack for interpreter churn around the measurement itself; the observed range was -13..+2. */
    private val residueTolerance = cyclesCreated * objectsPerCycle / 100

    /**
     * `len(gc.get_objects())`, releasing every temporary it creates.
     *
     * The closes are the whole point. `gc.get_objects()` returns a list holding a **strong
     * reference to every tracked object in the interpreter**, so leaking it roots the very garbage
     * the next measurement is trying to see. This chain used to be written inline as
     * `gc.getAttr("get_objects").invoke().getAttr("__len__").invoke()`, which closed nothing, and
     * that leaked list is what produced this file's earlier claim that a trailing `gc.collect()`
     * could not reclaim the free-threaded residue -- the list was holding it. With the list
     * released, a trailing collection reclaims essentially all of it on both builds, which is now
     * asserted rather than asserted against. See [assertResidueIsOrdinaryCollectableGarbage].
     */
    private fun countTrackedObjects(gc: python.multiplatform.ffi.PyObject): Int {
        val getObjects = gc.getAttr("get_objects")
        val objects = getObjects.invoke()
        val lenAttr = objects.getAttr("__len__")
        val len = lenAttr.invoke()
        val count = len.toString().toInt()
        len.close()
        lenAttr.close()
        objects.close()
        getObjects.close()
        return count
    }

    /** `gc.collect()`, releasing the bound method as well as the result. */
    private fun forceCollect(gc: python.multiplatform.ffi.PyObject) {
        val collect = gc.getAttr("collect")
        collect.invoke().close()
        collect.close()
    }

    /**
     * Asserts that whatever the checkpoint did **not** reclaim is still ordinary cyclic garbage
     * that an explicit collection takes: after [forceCollect] the tracked-object count is back
     * where it started, to within [residueTolerance].
     *
     * This is the part of the behaviour that is stable enough to pin. Measured on both builds
     * across every scope shape and checkpoint count tried (60+ repetitions, `perCall` /
     * `perRound` / `per100Rounds` / one scope for the whole workload, 0 to 2,000 checkpoints), the
     * post-collection residue landed between -13 and +2 objects out of ~20,000. How much the
     * *checkpoint alone* reclaims is not stable free-threaded and is deliberately not bounded --
     * see the KDoc on [measureCyclicGarbageWithAutoDrain].
     */
    private fun assertResidueIsOrdinaryCollectableGarbage(
        gc: python.multiplatform.ffi.PyObject,
        initial: Int,
        residueBefore: Int,
        label: String,
    ) {
        forceCollect(gc)
        val residueAfter = countTrackedObjects(gc) - initial
        println("$label: residue before forced collect=$residueBefore, after=$residueAfter")
        assertTrue(
            residueAfter in -residueTolerance..residueTolerance,
            "$label: an explicit gc.collect() should have reclaimed the whole residue, but " +
                "$residueAfter net objects remain (before the collection: $residueBefore). " +
                "Every cycle this test drops is plain cyclic garbage, so anything the collector " +
                "cannot take once it actually runs means something is still holding a reference " +
                "to it -- historically that was this test's own leaked gc.get_objects() list"
        )
    }

    private fun buildAndDropCycles(builtins: python.multiplatform.ffi.PyObject) {
        val listType = builtins.getAttr("list")
        try {
            repeat(cyclesCreated) {
                val a = listType()
                val b = listType()
                val aAppend = a.getAttr("append")
                val bAppend = b.getAttr("append")
                aAppend(b).close()
                bAppend(a).close()
                aAppend.close()
                bAppend.close()
                a.close()
                b.close()
            }
        } finally {
            listType.close()
        }
    }

    @Test
    fun measureCyclicGarbageWithoutAutoDrain() = PythonTestFixture.withInterpreter {
        // GIL build or not, setting to 0 explicitly.
        val previousInterval = Python3.autoDrainInterval
        try {
            Python3.autoDrainInterval = 0

            val builtins = Python3.import("builtins")
            val gc = Python3.import("gc")
            forceCollect(gc)
            val initial = countTrackedObjects(gc)

            buildAndDropCycles(builtins)

            val after = countTrackedObjects(gc)

            val delta = after - initial
            println("--- MEASUREMENT 1: Interval 0 --- initial=$initial after=$after delta=$delta")

            // With no checkpoint ever reached, _PY_GC_SCHEDULED_BIT is never read and the cyclic
            // collector never runs: every one of the 10,000 cycles (2 objects each) should still
            // be alive. Some slack below the theoretical maximum for interpreter-internal churn
            // (interned small ints, etc.) that is not part of what this test creates.
            val expectedMinimum = (cyclesCreated * objectsPerCycle * 3) / 4
            assertTrue(
                delta >= expectedMinimum,
                "expected the $cyclesCreated dropped cycles (~${cyclesCreated * objectsPerCycle} objects) " +
                    "to still be alive with autoDrainInterval=0, but only $delta net objects remained -- " +
                    "the cyclic collector should not have run at all without a checkpoint"
            )

            // ...and that they are alive only because nothing collected them, not because
            // something still refers to them. Without this, "the collector never ran" and "the
            // measurement itself is holding the garbage" look identical -- which is exactly the
            // confusion the leaked gc.get_objects() list used to create here.
            assertResidueIsOrdinaryCollectableGarbage(gc, initial, delta, "MEASUREMENT 1")

            gc.close()
            builtins.close()
        } finally {
            Python3.autoDrainInterval = previousInterval
        }
    }

    /**
     * The residue bound is pinned on the GIL build only, where it is stable at ~1,920-1,970 out of
     * ~20,000 in every configuration measured.
     *
     * Free-threaded (`-PpythonFreeThreaded=true`, where `autoDrainInterval = 32` is already the
     * shipped default rather than something this test turns on) it is not stable, and no bound is
     * asserted. `CheckpointCounter.reached` climbs by an identical, deterministic amount on every
     * run, but the residue does not: measured 20,000 -- i.e. nothing reclaimed at all -- in 10 of
     * 12 repetitions, and 7,427 / 11,523 in the other two. The same configuration has also been
     * seen to **latch** within one JVM, reclaiming on the first repetitions and then never again
     * on the later ones. Any upper bound on this number would be a coin flip, so free-threaded
     * asserts only that the mechanism engages -- plus the one thing that *is* stable, below.
     *
     * ### What replaced the previous explanation
     *
     * This KDoc used to add that "a trailing explicit `gc.collect()` finds nothing further to
     * reclaim, so the residue is not simply garbage waiting for the next checkpoint", and
     * concluded from that a difference in the free-threaded collector's *reclaim* behaviour. That
     * was an artifact of this test's own measurement: the `gc.get_objects()` chain closed none of
     * its temporaries, and the leaked list holds a strong reference to every tracked object, so
     * the "residue" it reported was partly being held by the previous measurement. With the
     * temporaries released ([countTrackedObjects]), a trailing `gc.collect()` reclaims the residue
     * essentially completely on **both** builds -- 60+ repetitions, post-collection residue
     * between -13 and +2 -- and that is now asserted by
     * [assertResidueIsOrdinaryCollectableGarbage].
     *
     * So the residue *is* collectable garbage. What differs free-threaded is what makes the
     * collector run, not what it can reclaim.
     *
     * ### Why no bound is asserted free-threaded
     *
     * That difference is root-caused in `docs/investigations/gc-scheduling-investigation.md` §8d: free-threaded,
     * `_Py_RunGC` re-asks `gc_should_collect` after the checkpoint has read the scheduled bit, and
     * `gc_should_collect_mem_usage` (`Python/gc_free_threading.c:2080`) gates generation 0 on
     * whether the **whole process's** memory footprint has grown by more than a tenth since the
     * last collection. In this JVM that number is JVM memory, so the outcome here depends on the
     * host process's allocation history rather than on anything this test does -- which is exactly
     * why it is bimodal, and why bounding it would be a coin flip rather than a regression test.
     *
     * The bound that *can* be asserted free-threaded, once that gate is deliberately opened, is in
     * [FreeThreadedGCGateTest], which also pins the mechanism itself on both builds.
     */
    @Test
    fun measureCyclicGarbageWithAutoDrain() = PythonTestFixture.withInterpreter {
        val previousInterval = Python3.autoDrainInterval
        try {
            Python3.autoDrainInterval = 32

            val builtins = Python3.import("builtins")
            val gc = Python3.import("gc")
            forceCollect(gc)
            val initial = countTrackedObjects(gc)
            val reachedBefore = Python3.CheckpointCounter.reached

            buildAndDropCycles(builtins)

            val reachedDelta = Python3.CheckpointCounter.reached - reachedBefore
            val after = countTrackedObjects(gc)

            val delta = after - initial
            println(
                "--- MEASUREMENT 2: Interval 32 (freeThreaded=${BuildConfig.pythonFreeThreaded}) --- " +
                    "initial=$initial after=$after delta=$delta checkpointsReached=$reachedDelta"
            )

            assertTrue(
                reachedDelta > 0,
                "autoDrainInterval=32 should have taken at least one automatic checkpoint over " +
                    "$cyclesCreated outermost withGIL scopes, but none were reached"
            )

            if (!BuildConfig.pythonFreeThreaded) {
                // Each outermost withGIL scope inside buildAndDropCycles counts down; at interval
                // 32 that is thousands of checkpoints over 10,000 rounds, each one able to run the
                // scheduled collection. The residue should be bounded by roughly one interval's
                // worth of not-yet-collected garbage, nowhere near the ~20,000 objects interval 0
                // leaves. See `docs/investigations/gc-scheduling-investigation.md` §7 -- measured here at 1,970.
                val upperBound = cyclesCreated * objectsPerCycle / 2
                assertTrue(
                    delta < upperBound,
                    "expected autoDrainInterval=32 to keep cyclic garbage bounded to a small " +
                        "residue on the GIL build, but $delta net objects remained after " +
                        "$cyclesCreated dropped cycles -- the automatic checkpoint should have " +
                        "reclaimed almost all of them"
                )
            }

            // Asserted on *both* builds, and the strongest thing that can be said free-threaded:
            // whatever the checkpoint left behind is still plain collectable cyclic garbage. This
            // is what the previous version of this file explicitly denied, on the strength of a
            // leaked gc.get_objects() list. If free threading ever really did leave something the
            // collector cannot take, this fails and says so.
            assertResidueIsOrdinaryCollectableGarbage(gc, initial, delta, "MEASUREMENT 2")

            gc.close()
            builtins.close()
        } finally {
            Python3.autoDrainInterval = previousInterval
        }
    }

    private fun installBridge() {
        Python3.exec(
            """
            import ctypes

            _pm_resolve = ctypes.CFUNCTYPE(ctypes.c_long, ctypes.c_char_p)(${python.native.ffi.UpcallStub.resolveHandleStubAddr})
            _pm_invoke = ctypes.CFUNCTYPE(ctypes.py_object, ctypes.c_long, ctypes.py_object)(
                ${python.native.ffi.UpcallStub.invokeWithArgsStubAddr}
            )

            class _PmBound:
                __slots__ = ('_handle',)
                def __init__(self, name):
                    self._handle = _pm_resolve(name.encode('utf-8'))
                def __call__(self, *args):
                    return _pm_invoke(self._handle, args)
            """.trimIndent()
        )
    }

    @Test
    fun testReentrancyDuringCheckpoint() = PythonTestFixture.withInterpreter {
        python.multiplatform.reflection.UpcallTable.install(listOf(python.multiplatform.ffi.upcall.TrampolineFragment))
        try {
            installBridge()
            Python3.exec("""
                import sys
                class DyingObject:
                    def __del__(self):
                        print("Python __del__ running!")
                        sys.stdout.flush()
                        _PmBound('trampoline.discard')(1)
                        print("Python __del__ finished!")
                        sys.stdout.flush()
            """.trimIndent())
            
            // Create DyingObject cycle using C API so GC doesn't run yet
            val main = Python3.import("__main__")
            val dyingClass = main.getAttr("DyingObject")
            val a = dyingClass()
            a.setAttr("cycle", a)
            a.close()
            dyingClass.close()
            main.close()
            
            // Allocate enough lists from C API to schedule GC
            val builtins = Python3.import("builtins")
            val listType = builtins.getAttr("list")
            for (i in 1..20000) {
                listType().close()
            }
            listType.close()
            builtins.close()
            
            println("Triggering PyGC_Collect from Kotlin...")
            python.multiplatform.ffi.withGIL {
                python.native.ffi.PyGC_Collect()
            }
            println("PyGC_Collect finished.")
        } finally {
            python.multiplatform.reflection.UpcallTable.clear()
            python.multiplatform.reflection.HandleTable.releaseAll()
        }
    }
}
