package python.multiplatform.ref

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.native.ffi.NativePointer
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The direction [GCLeakTest] cannot see.
 *
 * `GCLeakTest` asks whether a dropped wrapper's reference eventually comes back, and every one of
 * its assertions is a *lower* bound: the count fell, a cleaner ran, the delta is positive. A
 * mechanism that gives the same reference back **twice** satisfies all of them. That is not a
 * hypothetical failure in this repository — a wrapper taking ownership of a pointer it had only
 * borrowed produced exactly one extra release per wrapper, corrupted CPython's free lists, and
 * surfaced as a segfault in a test that had nothing to do with the code at fault. It is the reason
 * ROADMAP §1 looked unfixable for three attempts.
 *
 * So these cases pin counts from **both** sides:
 *
 *  * a leak shows up as a count that never comes back down;
 *  * a double free shows up as a count that goes *below* where it started.
 *
 * The target carries [BALLAST] extra references throughout. Without them, a double free of
 * [WRAPPERS] references would drive the count to zero, CPython would free an object this test is
 * still holding, and the failure would arrive as a crash somewhere else instead of as a readable
 * assertion here — which is precisely the failure mode being guarded against, and a test that
 * reproduces it uncontrolled is worth less than one that reports it.
 */
private const val WRAPPERS = 100
private const val BALLAST = 500

/**
 * A target object whose reference count is inflated by [BALLAST], plus the means to read it.
 *
 * `sys.getrefcount` takes a reference of its own for the argument slot, so the absolute number is
 * meaningless; only differences between two readings from this same helper are.
 */
private class BallastedTarget {
    private val builtins = Python3.import("builtins")
    private val listType = builtins.getAttr("list")
    private val sys = Python3.import("sys")
    private val getrefcount = sys.getAttr("getrefcount")

    val target: PyObject = listType()

    /** A Python list holding [target] [BALLAST] times, so the count can fall a long way safely. */
    private val ballast: PyObject = listType()

    init {
        val append = ballast.getAttr("append")
        try {
            repeat(BALLAST) { append(target).close() }
        } finally {
            append.close()
        }
    }

    fun refCount(): Long {
        val n = getrefcount(target)
        try {
            return n.toString().toLong()
        } finally {
            n.close()
        }
    }

    /** Order matters: the ballast list has to die before the target it holds. */
    fun release() {
        ballast.close()
        target.close()
        getrefcount.close()
        sys.close()
        listType.close()
        builtins.close()
    }
}

/**
 * Takes [count] references and gives them all back by hand, then drops the closed wrappers.
 *
 * Returns nothing: every wrapper is unreachable when this returns, which is what puts the closed
 * wrappers in front of the collector. Building them in the caller's own frame would let a local
 * slot keep them alive on some JVMs.
 */
private fun takeAndReturnByHand(target: PyObject, count: Int, afterWrapping: () -> Unit) {
    val held = ArrayList<PyObject>(count)
    repeat(count) { held.add(PyObject(target.pointer, borrowed = true)) }
    afterWrapping()
    held.forEach { it.close() }
    held.clear()
}

/** Takes [count] references and drops the wrappers without closing them. */
private fun takeAndDrop(target: PyObject, count: Int) {
    repeat(count) { PyObject(target.pointer, borrowed = true) }
}

/**
 * A cross-thread counter for release actions.
 *
 * `@Volatile` rather than an atomic because each instance is written by exactly one releaser — the
 * cleaner thread for a dropped cleaner, the test thread for an explicit `close()` — so there is no
 * read-modify-write to lose. Two counters, never one shared between two cleaners, for that reason.
 */
private class ReleaseCount {
    @Volatile
    var value: Int = 0
}

/** Registers a counting release and drops the cleaner, leaving it to the collector. */
private fun registerAndDrop(pointer: NativePointer, count: ReleaseCount) {
    registerCleaner(pointer) { count.value = count.value + 1 }
}

/** Registers a counting release, runs it by hand, and drops the cleaner. */
private fun registerCloseAndDrop(pointer: NativePointer, count: ReleaseCount) {
    registerCleaner(pointer) { count.value = count.value + 1 }.close()
}

class DoubleReleaseTest {

    @Test
    fun closedWrappersAreNotReleasedAgainByTheCollector(): CollectorTestResult {
        if (!Python3.isInitialized) Python3.initialize()
        val fixture = BallastedTarget()

        val baseline = fixture.refCount()
        var afterWrapping = baseline
        takeAndReturnByHand(fixture.target, WRAPPERS) { afterWrapping = fixture.refCount() }

        assertTrue(
            afterWrapping == baseline + WRAPPERS,
            "$WRAPPERS borrowed wrappers should take exactly $WRAPPERS references " +
                "(baseline: $baseline, after wrapping: $afterWrapping)"
        )
        val afterClosing = fixture.refCount()
        assertEquals(
            baseline, afterClosing,
            "closing every wrapper by hand should give back exactly what was taken " +
                "(baseline: $baseline, after closing: $afterClosing)"
        )

        // From here the closed wrappers are unreachable and their cleaners are the only thing that
        // could still touch this count. Correct behaviour is that nothing happens at all, so the
        // loop waits for nothing and simply gives the collector every attempt.
        // The sensitivity of what follows was checked rather than assumed: with three extra
        // `Py_DecRef`s injected here, the assertion below fired with `baseline: 502, lowest seen:
        // 499`. The line was removed again; this comment is what it was for.
        var lowest = afterClosing
        var last = afterClosing
        return collectorTest(maxAttempts = 20, attempt = {
            last = fixture.refCount()
            if (last < lowest) lowest = last
            false
        }, finish = {
            assertEquals(
                baseline, lowest,
                "the collector released references that close() had already given back " +
                    "(baseline: $baseline, lowest seen: $lowest). Every wrapper here was closed by " +
                    "hand, so a second release is a double free: it hands CPython back a reference " +
                    "it no longer owns, and the object it frees is still in use. GCLeakTest cannot " +
                    "see this -- all of its assertions are satisfied by releasing too much."
            )
            assertEquals(
                baseline, last,
                "the count did not settle back at the baseline (baseline: $baseline, last: $last)"
            )
            fixture.release()
        })
    }

    @Test
    fun droppedWrappersComeBackWithoutOverShooting(): CollectorTestResult {
        if (!Python3.isInitialized) Python3.initialize()
        val fixture = BallastedTarget()

        val baseline = fixture.refCount()
        takeAndDrop(fixture.target, WRAPPERS)
        val afterWrapping = fixture.refCount()

        assertTrue(
            afterWrapping > baseline,
            "wrapping should have raised the count (baseline: $baseline, after: $afterWrapping)"
        )

        var lowest = afterWrapping
        var last = afterWrapping
        var attempts = 0
        return collectorTest(maxAttempts = 50, attempt = {
            last = fixture.refCount()
            if (last < lowest) lowest = last
            attempts++
            last <= baseline
        }, finish = {
            // The leak direction. Bounded below by the baseline rather than pinned to it: the
            // collector is under no obligation to have reached all $WRAPPERS wrappers within the
            // attempts allowed, and GCLeakTest already carries the strict version of this question.
            assertTrue(
                last < afterWrapping,
                "no dropped wrapper gave its reference back in $attempts attempts " +
                    "(after wrapping: $afterWrapping, last: $last). Nothing here was closed by " +
                    "hand, so these references are reachable only through the collector."
            )
            // The double-free direction, and the reason this test exists next to GCLeakTest.
            assertTrue(
                lowest >= baseline,
                "the count fell BELOW where it started (baseline: $baseline, lowest seen: $lowest, " +
                    "wrappers: $WRAPPERS). Only $WRAPPERS references were ever taken, so a count " +
                    "below the baseline means at least one was given back twice."
            )
            fixture.release()
        })
    }

    @Test
    fun everyReleaseActionRunsExactlyOnce(): CollectorTestResult {
        // One layer below the two cases above: the platform cleaner itself, with a counting action
        // in place of a decref. This is the same contract stated where it can be read directly
        // rather than inferred from a refcount, and -- because the action never touches CPython --
        // it is the one form of the question that a broken implementation can fail without also
        // corrupting the interpreter.
        if (!Python3.isInitialized) Python3.initialize()
        val sys = Python3.import("sys")

        val collected = ReleaseCount()
        val closedByHand = ReleaseCount()
        registerAndDrop(sys.pointer, collected)
        registerCloseAndDrop(sys.pointer, closedByHand)

        assertEquals(1, closedByHand.value, "close() should release immediately, not eventually")

        return collectorTest(maxAttempts = 50, attempt = {
            collected.value > 0
        }, finish = {
            assertTrue(
                collected.value >= 1,
                "a dropped cleaner's action never ran; its reference would leak"
            )
            assertEquals(
                1, collected.value,
                "a dropped cleaner's action ran ${collected.value} times; as a decref that is a " +
                    "double free"
            )
            assertEquals(
                1, closedByHand.value,
                "an action already run by close() was run again by the collector " +
                    "(${closedByHand.value} times in total)"
            )
            sys.close()
        })
    }
}
