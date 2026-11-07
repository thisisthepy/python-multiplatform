package python.multiplatform.ffi

import platform.posix.usleep
import python.multiplatform.ffi.upcall.NativeThread
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * SPEC C-4 / T-1 on Kotlin/Native (iOS simulator, androidNative): the GIL is released once
 * [Python3.initialize] returns, so a second thread can run Python while the first waits in Kotlin.
 * The mirror of `jvmTest`'s `GilReleaseTest`.
 *
 * Modelled on `AsyncUpcallNativeDeliveryTest` (nativeTest): same [NativeThread] (a bare
 * `pthread_create`) and the same `PythonTestFixture` interpreter set-up.
 *
 * Every wait is bounded by [waitFor]. If the GIL were still held the second thread would block in
 * `PyGILState_Ensure`; the bounded wait expires and the assertion fails. A thread that never got in
 * is deliberately not joined (that join would hang); the process ends the test run.
 *
 * Negative control (not a test, it would hang on a GIL build): thread A waiting inside `withGIL`
 * without `withoutGIL` keeps the GIL, so B could never get in.
 */
class GilReleaseNativeTest {

    private val timeout = 20.seconds

    private fun waitFor(flag: AtomicInt): Boolean {
        val mark = TimeSource.Monotonic.markNow()
        while (flag.value == 0) {
            if (mark.elapsedNow() > timeout) return false
            usleep(1000u)
        }
        return true
    }

    private class Outcome {
        val done = AtomicInt(0)
        val result = AtomicReference<String?>(null)
        val error = AtomicReference<String?>(null)
    }

    private fun pythonThread(o: Outcome) = NativeThread {
        try {
            o.result.value = withGIL { PythonTestFixture.eval("6 * 7").toString() }
        } catch (t: Throwable) {
            o.error.value = t.toString()
        } finally {
            o.done.value = 1
        }
    }

    @Test
    fun secondThreadRunsPythonWhileTheInitialisingThreadWaitsInKotlin() = PythonTestFixture.withInterpreter {
        val o = Outcome()
        val b = pythonThread(o)
        b.start()
        val finished = waitFor(o.done)
        assertTrue(finished, "second thread could not run Python: the GIL is still held after initialisation")
        b.join()
        assertNull(o.error.value, "second thread failed: ${o.error.value}")
        assertEquals("42", o.result.value)
    }

    @Test
    fun withoutGilLetsAnotherThreadRunPythonWhileThisOneWaits() = PythonTestFixture.withInterpreter {
        val o = Outcome()
        val b = pythonThread(o)
        var observed = false
        withGIL {
            b.start()
            // Wait in Kotlin with the GIL given up; no Python call inside this block.
            observed = withoutGIL { waitFor(o.done) }
        }
        assertTrue(observed, "B did not finish while A waited in withoutGIL: withoutGIL did not release the GIL")
        b.join()
        assertNull(o.error.value, "B failed: ${o.error.value}")
        assertEquals("42", o.result.value)
    }
}
