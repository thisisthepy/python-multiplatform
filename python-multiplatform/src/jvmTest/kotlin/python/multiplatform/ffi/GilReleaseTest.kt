package python.multiplatform.ffi

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC C-4 / T-1: the GIL is released once [Python3.initialize] returns (it saves the main thread
 * state), so a second Kotlin thread can run Python while the first waits in Kotlin.
 *
 * `jvmTest` is shared by `desktopTest` and `androidInstrumentedTest`, so one file covers both.
 * Modelled on `PyTypeCacheConcurrencyTest` (desktopTest) for the thread and latch shape and on
 * `PythonTestFixture.withInterpreter` for the interpreter set-up.
 *
 * Every wait is bounded. If the GIL were still held, the second thread would block in
 * `PyGILState_Ensure`, the bounded wait would time out and the assertion would fail; nothing
 * waits forever on the thread that is blocked.
 *
 * Negative control (deliberately not a test, it would hang on a GIL build): thread A waiting inside
 * `withGIL` WITHOUT `withoutGIL` keeps the GIL, so B could never get in.
 */
class GilReleaseTest {

    private val timeoutSeconds = 20L

    /** Runs `6 * 7` under the GIL on a new thread; the answer or failure lands in [result]/[error]. */
    private fun pythonThread(done: CountDownLatch, result: AtomicReference<String?>, error: AtomicReference<Throwable?>) =
        Thread {
            try {
                result.set(withGIL { PythonTestFixture.eval("6 * 7").toString() })
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                done.countDown()
            }
        }.apply { isDaemon = true }

    @Test
    fun secondThreadRunsPythonWhileTheInitialisingThreadWaitsInKotlin() = PythonTestFixture.withInterpreter {
        // The initialising thread is not inside withGIL here: it only waits in Kotlin.
        val done = CountDownLatch(1)
        val result = AtomicReference<String?>(null)
        val error = AtomicReference<Throwable?>(null)
        pythonThread(done, result, error).start()
        val finished = done.await(timeoutSeconds, TimeUnit.SECONDS)
        assertTrue(finished, "second thread could not run Python: the GIL is still held after initialisation")
        assertNull(error.get(), "second thread failed: ${error.get()}")
        assertEquals("42", result.get())
    }

    @Test
    fun withoutGilLetsAnotherThreadRunPythonWhileThisOneWaits() = PythonTestFixture.withInterpreter {
        val done = CountDownLatch(1)
        val result = AtomicReference<String?>(null)
        val error = AtomicReference<Throwable?>(null)
        val b = pythonThread(done, result, error)
        var observed = false
        withGIL {
            b.start()
            // Wait in Kotlin with the GIL given up; no Python call inside this block.
            observed = withoutGIL { done.await(timeoutSeconds, TimeUnit.SECONDS) }
            // The GIL is held again here, so B (if it was stuck) can only proceed after we leave.
        }
        b.join(TimeUnit.SECONDS.toMillis(timeoutSeconds))
        assertTrue(observed, "B did not finish while A waited in withoutGIL: withoutGIL did not release the GIL")
        assertNull(error.get(), "B failed: ${error.get()}")
        assertEquals("42", result.get())
    }
}
