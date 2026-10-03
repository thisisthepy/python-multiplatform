@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package python.multiplatform.ref

import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.js.toJsReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.overhead.Benchmark

/**
 * ROADMAP §10 -- what the `FinalizationRegistry` route actually does, measured on this target.
 *
 * `GCLeakTest` asks the shared question (does a dropped wrapper give its reference back?) on every
 * platform. This file exists for the parts that are only true here, and for the two facts the
 * design rests on that no shared test can state:
 *
 *  * a `JsReference` does **not** pin the Kotlin object it hands to JS, and
 *  * nothing on this platform can observe a collection without yielding to the host.
 *
 * The second is why every test here returns a `Promise`. `kotlin-test`'s wasm adapter awaits one
 * (`TeamcityAdapterWithPromiseSupport`), which was confirmed by a control that returned a rejected
 * promise and duly failed.
 */
private const val WRAPPERS = 200

/**
 * Crosses several macrotask boundaries, asking for a collection at each.
 *
 * A macrotask, not a microtask: measured on Node 26 / V8 14.6, a `WeakRef` created in a job is
 * still live after that job's microtask queue drains and is cleared only once the *task* ends. The
 * `FinalizationRegistry` callback is likewise posted as a task. Several of them because a
 * collection is not obliged to be complete, and one `gc()` sometimes leaves a tail.
 */
private fun yieldToCollector(): Promise<JsAny?> = js(
    """(async () => {
        const tick = () => new Promise(r => setTimeout(r, 0));
        for (let i = 0; i < 8; i++) {
            await tick();
            if (typeof globalThis.gc === 'function') { globalThis.gc(); globalThis.gc(); }
        }
        await tick();
        return null;
    })()"""
)

private fun makeWeakRegistry() {
    js(
        """{
        globalThis.__pmpProbeAlive = [];
    }"""
    )
}

private fun weaklyHold(target: JsAny) {
    js("globalThis.__pmpProbeAlive.push(new WeakRef(target));")
}

private fun weaklyHeldStillAlive(): Int =
    js("globalThis.__pmpProbeAlive.filter(w => w.deref() !== undefined).length")

private class Subject(val id: Int)

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

class WasmFinalizationTest {

    /**
     * The control for every async test in this file.
     *
     * An assertion inside a `.then { }` that nobody awaits is not an assertion -- the test would
     * end before the block ran and pass without measuring anything. `@AfterTest` maps to mocha's
     * `afterEach`, which runs only once the test's promise has settled, so this fails loudly if the
     * adapter ever stops awaiting. It was verified once from the other side as well, with a test
     * that returned `Promise.reject`: it failed, which is what proved the awaiting is real.
     */
    private var continuationRan = false

    @BeforeTest
    fun armControl() {
        continuationRan = false
    }

    @AfterTest
    fun theContinuationMustHaveRun() {
        assertTrue(
            continuationRan,
            "the returned Promise was not awaited, so every assertion inside .then { } was vacuous"
        )
    }

    /**
     * The load-bearing fact. If `toJsReference()` were a strong reference, registering a wrapper
     * with a `FinalizationRegistry` would pin it forever and `registerCleaner` could not be built
     * on one at all -- §10 assumed this was the risk and left the route unmeasured because of it.
     */
    @Test
    fun jsReferenceDoesNotPinTheKotlinObject(): Promise<JsAny?> {
        makeWeakRegistry()
        repeat(WRAPPERS) { weaklyHold(Subject(it).toJsReference()) }
        val aliveImmediately = weaklyHeldStillAlive()
        assertTrue(
            aliveImmediately == WRAPPERS,
            "the setup is wrong if the objects are already gone before any yield (alive: $aliveImmediately)"
        )
        return yieldToCollector().then<JsAny?> {
            continuationRan = true
            val alive = weaklyHeldStillAlive()
            assertTrue(
                alive == 0,
                "a Kotlin object handed to JS through toJsReference() was not collected once JS held " +
                    "it only weakly -- $alive of $WRAPPERS still alive. That would make JsReference a " +
                    "strong reference and the FinalizationRegistry route impossible."
            )
            null
        }
    }

    /**
     * The whole mechanism, end to end, on real `PyObject` wrappers: 200 of them are created over a
     * live Python object and **never closed**, and CPython's own refcount is what says whether the
     * references came back.
     */
    @Test
    fun droppedWrappersReleaseTheirReferenceWithoutClose(): Promise<JsAny?> {
        if (!Python3.isInitialized) Python3.initialize()
        assertTrue(
            WasmCleanerStats.automatic,
            "no FinalizationRegistry on this host, so there is no automatic reclamation to test"
        )

        val builtins = Python3.import("builtins")
        val listType = builtins.getAttr("list")
        val target = listType()

        val refBefore = getRefCount(target)
        val finalizedBefore = WasmCleanerStats.finalized
        repeat(WRAPPERS) { PyObject(target.pointer, borrowed = true) }
        val refAfterWrapping = getRefCount(target)

        assertTrue(
            refAfterWrapping >= refBefore + WRAPPERS,
            "wrapping should have raised the count by $WRAPPERS (before: $refBefore, after: $refAfterWrapping)"
        )

        return yieldToCollector().then<JsAny?> {
            continuationRan = true
            val finalizedDelta = WasmCleanerStats.finalized - finalizedBefore
            val refAfter = getRefCount(target)
            println(
                "WasmFinalizationTest: refcount $refBefore -> $refAfterWrapping -> $refAfter, " +
                    "collector-driven releases: $finalizedDelta, outstanding: ${WasmCleanerStats.outstanding}"
            )
            assertTrue(
                finalizedDelta > 0,
                "the FinalizationRegistry never ran a decref for the dropped wrappers " +
                    "(finalized delta: $finalizedDelta)"
            )
            assertTrue(
                refAfter < refAfterWrapping,
                "given that $finalizedDelta decrefs ran, the target's count should have dropped " +
                    "(after wrapping: $refAfterWrapping, after collection: $refAfter)"
            )
            target.close()
            listType.close()
            builtins.close()
            null
        }
    }

    /**
     * An explicit `close()` must still be the only decref, and the callback that arrives later must
     * find the flag set and do nothing. Getting this wrong is a double decref, which is a crash in
     * CPython rather than a failing assertion, so it is checked on its own.
     */
    @Test
    fun closeIsNotUndoneOrRepeatedByTheCollector(): Promise<JsAny?> {
        if (!Python3.isInitialized) Python3.initialize()

        val builtins = Python3.import("builtins")
        val listType = builtins.getAttr("list")
        val target = listType()

        val refBefore = getRefCount(target)
        repeat(WRAPPERS) { PyObject(target.pointer, borrowed = true).close() }
        val refAfterClosing = getRefCount(target)
        assertTrue(
            refAfterClosing == refBefore,
            "close() should have given every reference straight back (before: $refBefore, after: $refAfterClosing)"
        )

        return yieldToCollector().then<JsAny?> {
            continuationRan = true
            val refAfterCollection = getRefCount(target)
            assertTrue(
                refAfterCollection == refBefore,
                "the collector decref'd a wrapper that close() had already released -- the count " +
                    "fell below where close() left it (expected $refBefore, got $refAfterCollection)"
            )
            target.close()
            listType.close()
            builtins.close()
            null
        }
    }

    /**
     * What the hook costs at construction, which is where all of it is: nothing on the C API call
     * path goes through the registry.
     *
     * No assertion on the number -- it is whatever this machine does -- but the two rows next to
     * each other say how much of a wrapper's construction is the finalisation hook rather than the
     * reference it takes. The figures this produced when it was written are in
     * `src/wasmJsMain/README.md`.
     */
    @Test
    fun registrationOverhead() {
        continuationRan = true   // synchronous: there is no continuation for the control to check
        if (!Python3.isInitialized) Python3.initialize()
        val builtins = Python3.import("builtins")
        val listType = builtins.getAttr("list")
        val target = listType()

        var sink = 0

        // Warm up before either row is recorded. The first 50 000 registrations pay for tiering up
        // the `js_code` import and for the registry's own growth, and reading that as the cost of
        // registration made the second row look three times cheaper than the first when the two do
        // almost the same work.
        repeat(50_000) { registerCleaner(target.pointer) { }.close() }
        repeat(50_000) { sink = sink xor Subject(it).toJsReference().hashCode() }

        Benchmark.run("cleaner: registerCleaner + close (the whole hook)", iterations = 50_000) {
            registerCleaner(target.pointer) { }.close()
            sink = sink xor 1
        }
        var seed = 0
        Benchmark.run("cleaner: toJsReference() alone (the externref crossing)", iterations = 50_000) {
            sink = sink xor Subject(seed++).toJsReference().hashCode()
        }
        assertTrue(sink != Int.MIN_VALUE)

        Benchmark.printReport()
        target.close()
        listType.close()
        builtins.close()
    }
}
