package python.multiplatform.ref

/**
 * `actual` for the local (JVM) unit-test compilation of the Android target. The native library is
 * not loadable here, so nothing in this compilation ever reaches a live interpreter -- but the
 * declaration still has to exist, and it must not carry the GIL-parking mistake the instrumented
 * copy did. See `GCLeakTest.androidInstrumented.kt` for both reasons.
 */
actual fun forceGC() {
    val canary = java.lang.ref.WeakReference(Any())
    var attempts = 0
    while (canary.get() != null && attempts < 20) {
        Runtime.getRuntime().gc()
        System.runFinalization()
        Thread.sleep(10)
        attempts++
    }
    Thread.sleep(50)
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
