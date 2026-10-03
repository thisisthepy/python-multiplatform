package python.multiplatform.ref

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.ReleaseCounter
import kotlin.test.Test
import kotlin.test.assertTrue

expect fun forceGC()

/**
 * Whether this platform can release a dropped wrapper without an explicit `close()`.
 *
 * True everywhere now. It was false on wasmJs until ROADMAP §10's lifetime work: Kotlin/Wasm's
 * stdlib has no `FinalizationRegistry`, no `WeakRef` and no `Cleaner` -- checked against the klib,
 * not inferred -- but JS has all three, and a `JsReference` reaches them without pinning the object
 * it hands over. See `wasmJsMain/.../PyAutoCloseable.wasmJs.kt`.
 */
expect val cleanerReleasesAutomatically: Boolean

/**
 * What a collector-driven test returns.
 *
 * `Unit` on every platform whose finalisation runs on a thread, so a test can spin and watch. On
 * wasmJs it is a `Promise`: the host engine delivers a `FinalizationRegistry` callback as a *task*,
 * and clears a `WeakRef` only once the job that created it has ended, so **nothing** is observable
 * from inside a single synchronous test body there. Spinning in a loop on that target measures a
 * turn of the event loop that cannot advance -- which is what these three cases used to do, and why
 * they read as "wasm has no finalisation hook" long after one had been wired up.
 *
 * `kotlin-test`'s wasm adapter awaits a returned `Promise`; that was verified with a test returning
 * `Promise.reject`, which failed. `WasmFinalizationTest` keeps a standing control for it so a
 * regression cannot turn these assertions into ones nobody runs.
 */
expect class CollectorTestResult

/**
 * Gives the collector up to [maxAttempts] chances, stopping as soon as [attempt] returns true, and
 * then runs [finish] exactly once.
 *
 * [attempt] both samples and decides; [finish] holds the assertions. The split exists so the loop
 * can be a blocking one on most targets and a chain of host turns on wasmJs, without either
 * version knowing what is being waited for.
 */
expect fun collectorTest(
    maxAttempts: Int,
    attempt: () -> Boolean,
    finish: () -> Unit
): CollectorTestResult

/** The blocking driver, shared by every `actual` whose platform can observe a collection in-call. */
internal fun runCollectorLoopBlocking(maxAttempts: Int, attempt: () -> Boolean, finish: () -> Unit) {
    var i = 0
    while (i < maxAttempts) {
        forceGC()
        if (attempt()) break
        i++
    }
    finish()
}

private const val WRAPPERS = 200

/**
 * Creates [count] borrowed wrappers over [target] and returns without keeping any of them.
 * Each one takes a reference in its constructor, so the count rises by [count]; every wrapper
 * is unreachable the moment this returns, so the cleaner should give all of them back.
 */
private fun makeWrappers(target: PyObject, count: Int) {
    repeat(count) { PyObject(target.pointer, borrowed = true) }
}

private fun getRefCount(target: PyObject): Long {
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

class GCLeakTest {
    
    @Test
    fun testCleanerMechanismRuns(): CollectorTestResult {
        if (!Python3.isInitialized) Python3.initialize()

        val builtins = Python3.import("builtins")
        val listType = builtins.getAttr("list")
        val testList = listType()

        val ranBefore = ReleaseCounter.ran
        val releasedBefore = ReleaseCounter.released

        makeWrappers(testList, WRAPPERS)

        var ranAfter = ReleaseCounter.ran
        var releasedAfter = ReleaseCounter.released
        var attempts = 0

        // Wait for the cleaners, however this platform delivers them.
        return collectorTest(maxAttempts = 50, attempt = {
            ranAfter = ReleaseCounter.ran
            releasedAfter = ReleaseCounter.released
            attempts++
            releasedAfter != releasedBefore
        }, finish = {
            val ranDelta = ranAfter - ranBefore
            val releasedDelta = releasedAfter - releasedBefore

            // Deltas, not absolutes. ReleaseCounter is process-wide, so `ran > released` can be true
            // from residue left by earlier tests -- which is precisely the mistake that hid this bug
            // for so long, when a global 101002 was read as "the cleaners for this test ran".
            // The deadlock signature is that cleaners START and NONE arrive -- releasedDelta stays at
            // zero while ranDelta climbs. Requiring the two to be equal instead was too strict: the
            // counters are sampled separately, so a cleaner in flight at that moment shows up as a
            // difference of one and says nothing about a deadlock. iOS failed here at 12 started and
            // 11 released, which is the mechanism working, not stalling.
            if (ranDelta > 0 && releasedDelta == 0) {
                assertTrue(
                    false,
                    "Cleaners started and none reached Py_DecRef. (ranDelta: $ranDelta, releasedDelta: $releasedDelta; " +
                    "absolute ran: $ranAfter, released: $releasedAfter). " +
                    "This implies the cleaner thread is deadlocked in PyGILState_Ensure because Python3.initialize() still holds the GIL (ROADMAP §1)."
                )
            } else {
                assertTrue(
                    ranDelta > 0,
                    "No cleaner ran for this test's wrappers. (ranBefore: $ranBefore, ranAfter: $ranAfter, " +
                    "attempts: $attempts). " +
                    "Note this fires on desktop as well as on iOS, so do not read it as an iOS-only symptom. " +
                    "Two causes are consistent with it and this test cannot yet tell them apart: the collector " +
                    "never considered the wrappers unreachable, or it did and the cleaner runs on a background " +
                    "thread that the tight forceGC() loop -- which finishes in microseconds -- gave no chance to " +
                    "wake up. " +
                    "What it is NOT is the §1 GIL deadlock: that shows up as ranDelta > 0 with releasedDelta == 0, handled above. " +
                    "The large absolute counts here are the whole process's explicit close() calls from earlier " +
                    "tests, which is why only the deltas are trusted."
                )
            }

            testList.close()
            listType.close()
            builtins.close()
        })
    }

    @Test
    fun testReferenceCountDecreasesOnGC(): CollectorTestResult {
        if (!Python3.isInitialized) Python3.initialize()

        val builtins = Python3.import("builtins")
        val listType = builtins.getAttr("list")
        val testList = listType()
        
        val refBefore = getRefCount(testList)
        makeWrappers(testList, WRAPPERS)
        val refAfterLoop = getRefCount(testList)
        
        assertTrue(
            refAfterLoop > refBefore,
            "wrapping should have raised the count (before: $refBefore, after wrapping: $refAfterLoop)"
        )
        
        // getRefCount closes wrappers of its own on every call, and those closes go through the
        // same counter as the cleaner's. Measure that cost once rather than hardcoding it, so the
        // arithmetic below cannot drift silently when the helper changes.
        val probeBefore = ReleaseCounter.released
        getRefCount(testList)
        val closesPerProbe = ReleaseCounter.released - probeBefore

        val releasedBefore = ReleaseCounter.released

        var refAfterGC = refAfterLoop
        var releasedAfter = ReleaseCounter.released
        var attempts = 0

        return collectorTest(maxAttempts = 50, attempt = {
            refAfterGC = getRefCount(testList)
            releasedAfter = ReleaseCounter.released
            attempts++
            refAfterGC != refAfterLoop
        }, finish = {
            // Subtract the helper's own closes, leaving only what the cleaner contributed.
            val expectedLoopCloses = attempts * closesPerProbe
            val releasedDelta = releasedAfter - releasedBefore - expectedLoopCloses

            if (releasedDelta <= 0) {
                assertTrue(
                    false,
                    "Cannot verify if target's count drops because no GC decrefs actually completed for the target wrappers. " +
                    "Either the cleaner thread is deadlocked (Desktop) or the tight loop finished before background cleaners could run (iOS)."
                )
            }

            assertTrue(
                refAfterGC < refAfterLoop,
                "Given that a decref ran for the wrappers (releasedDelta: $releasedDelta), the target's count should actually drop. " +
                "(before GC: $refAfterLoop, after GC: $refAfterGC)"
            )

            testList.close()
            listType.close()
            builtins.close()
        })
    }

    @Test
    fun testCascadingReleaseOnGC(): CollectorTestResult {
        if (!Python3.isInitialized) Python3.initialize()

        val builtins = Python3.import("builtins")
        val listType = builtins.getAttr("list")
        val testTarget = listType() // the object we will track
        
        val refBefore = getRefCount(testTarget)
        
        // Create 1000 lists, each containing testTarget.
        //
        // The wrappers are held in `held` until the count has been sampled, and only then
        // dropped. Letting them fall out of scope inside the loop looks simpler and races the
        // thing this test exists to prove: once GC-driven release actually works, the collector
        // reclaims some of them *during* the loop. On Android API 36 that read 931 instead of
        // 1002 -- the build-up assertion failing precisely because the mechanism under test had
        // started working. Holding them makes the setup deterministic and moves the whole
        // question to where it belongs, after the references are dropped.
        val WRAPPERS_LARGE = 1000
        var held: MutableList<PyObject>? = ArrayList(WRAPPERS_LARGE)
        repeat(WRAPPERS_LARGE) {
            val wrapperList = listType()
            val appendMethod = wrapperList.getAttr("append")
            // The call returns a wrapper for `None`. Closed rather than dropped: WRAPPERS_LARGE
            // dropped wrappers are a debt to the cleaner that outlives this test, and on a target
            // where finalisation only arrives on a host turn the next test to yield pays it and
            // counts it as its own. EvalCheckpointTest records what that cost.
            appendMethod(testTarget).close() // inner object refcount++
            appendMethod.close()
            // We do NOT close wrapperList -- it is released by the collector, not by hand.
            held!!.add(wrapperList)
        }

        val refAfterLoop = getRefCount(testTarget)
        assertTrue(
            refAfterLoop >= refBefore + WRAPPERS_LARGE,
            "target count should have risen by $WRAPPERS_LARGE (before: $refBefore, after: $refAfterLoop)"
        )

        // Drop the only strong references to the outer lists. Everything below is the
        // collector's work.
        held!!.clear()
        held = null

        var refAfterGC = refAfterLoop
        var attempts = 0

        return collectorTest(maxAttempts = 50, attempt = {
            refAfterGC = getRefCount(testTarget)
            attempts++
            refAfterGC != refAfterLoop
        }, finish = {
            assertTrue(
                refAfterGC < refAfterLoop,
                "target count should drop as outer lists are GC'd (before GC: $refAfterLoop, after GC: $refAfterGC)"
            )

            testTarget.close()
            listType.close()
            builtins.close()
        })
    }
}
