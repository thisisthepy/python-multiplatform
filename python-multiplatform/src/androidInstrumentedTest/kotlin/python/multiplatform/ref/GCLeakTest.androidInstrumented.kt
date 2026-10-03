package python.multiplatform.ref

/**
 * One-shot probe, evaluated on the first [forceGC] call, that records what it actually takes to
 * get ART to collect. It exists because the answer is not the one every other JVM gives, and the
 * suite spent a full run reading the consequence -- "no cleaner ran" -- as a cleaner-thread
 * problem when the collector had simply never run.
 *
 * `bare` counts how many `System.gc()` calls it takes to clear a weak canary; `escalated` counts
 * the same for `Runtime.getRuntime().gc()` + `System.runFinalization()`. Both are logged, so the
 * claim in [forceGC]'s comment is a measurement in logcat rather than an assertion in a comment.
 */
private val gcProbe: Unit = run {
    var bare = 0
    val c1 = java.lang.ref.WeakReference(Any())
    while (c1.get() != null && bare < 20) {
        System.gc()
        Thread.sleep(10)
        bare++
    }
    val bareCleared = c1.get() == null

    var escalated = 0
    val c2 = java.lang.ref.WeakReference(Any())
    while (c2.get() != null && escalated < 20) {
        Runtime.getRuntime().gc()
        System.runFinalization()
        Thread.sleep(10)
        escalated++
    }
    val escalatedCleared = c2.get() == null

    android.util.Log.i(
        "GCProbe",
        "bare System.gc(): cleared=$bareCleared after $bare attempts; " +
            "Runtime.gc()+runFinalization(): cleared=$escalatedCleared after $escalated attempts"
    )
}

/**
 * `actual` for `commonTest`'s `expect fun forceGC()`, for the instrumented (on-device)
 * compilation. `androidUnitTest` has its own copy: the two are separate compilations of the same
 * target, so each needs its own actual, and only this one ever runs against a live interpreter.
 *
 * Two things here differ from the obvious implementation, and both were paid for:
 *
 * 1. **`Runtime.getRuntime().gc()`, not `System.gc()`.** On Android `System.gc()` does not
 *    collect when you call it. libcore's implementation only records a request and defers the
 *    collection until the next `System.runFinalization()`, so a loop of bare `System.gc()` calls
 *    runs no collection at all -- which is exactly what the instrumented suite did, on both API
 *    levels, for the whole of GCLeakTest. `Runtime.gc()` goes straight to `Heap::CollectGarbage`.
 *    `runFinalization()` follows it so the reference queues are actually drained. See [gcProbe],
 *    which measures this rather than asserting it.
 *
 * 2. **The GIL is not touched.** An earlier version called `PyEval_SaveThread()` here and
 *    restored it afterwards. By the time a test body reaches this call the thread holds nothing:
 *    `Python3.initialize()` parked the main thread state and `withGIL` attaches per call through
 *    `PyGILState_Ensure`/`Release`. Mixing raw thread-state parking with the refcounted
 *    `PyGILState` scheme on one thread is what corrupted the interpreter on desktop and crashed
 *    the process inside `_PyObject_ClearFreeLists` during `Py_Finalize`; the desktop actual was
 *    fixed for that reason and this copy was left behind.
 *
 * Collection is not deterministic, so this stays best-effort and bounded: the caller loops until
 * it observes what it is waiting for or gives up.
 */
actual fun forceGC() {
    gcProbe
    val canary = java.lang.ref.WeakReference(Any())
    var attempts = 0
    while (canary.get() != null && attempts < 20) {
        Runtime.getRuntime().gc()
        System.runFinalization()
        Thread.sleep(10)
        attempts++
    }
    // The canary being gone means a collection happened; the cleaner still has to be scheduled
    // and run its action, which is a separate event. On the pre-API-33 PhantomReference path
    // that is a queue poll on a daemon thread, which needs longer than a Cleanable dispatch.
    Thread.sleep(100)
}

actual val cleanerReleasesAutomatically: Boolean = true

/**
 * This platform's finalisation runs on a thread, so a test can force a collection and watch for
 * the result inside one call. `Unit` is what a `@Test` returns here; see `commonTest`'s
 * [CollectorTestResult] for the target where it cannot be.
 */
actual typealias CollectorTestResult = Unit

actual fun collectorTest(
    maxAttempts: Int,
    attempt: () -> Boolean,
    finish: () -> Unit
): CollectorTestResult = runCollectorLoopBlocking(maxAttempts, attempt, finish)
