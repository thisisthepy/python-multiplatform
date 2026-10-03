@file:OptIn(kotlin.native.runtime.NativeRuntimeApi::class)
package python.multiplatform.ref

actual fun forceGC() {
    kotlin.native.runtime.GC.collect()
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
