package python.multiplatform.ffi

import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #161: the outermost [withGIL] on a JVM virtual thread throws before PyGILState_Ensure.
 * Red before the change: withGIL simply ran on the virtual thread.
 */
class VirtualThreadGILTest {

    private fun virtualThreadsAvailable() = Runtime.version().feature() >= 21

    @Test
    fun virtualThreadIsRefusedAndGilIsNotLeaked() = PythonTestFixture.withInterpreter {
        if (!virtualThreadsAvailable()) return@withInterpreter
        val entered = java.util.concurrent.atomic.AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val t = Thread.ofVirtual().start {
            try {
                withGIL { entered.set(true) }
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        t.join(30_000)
        val e = failure.get()
        assertNotNull(e, "withGIL on a virtual thread must throw")
        assertTrue(e is IllegalStateException, "got $e")
        assertTrue(e.message!!.contains("virtual thread"), e.message)
        assertTrue(e.message!!.contains("platform thread"), e.message)
        assertTrue(!entered.get(), "block must not run")

        // Ensure was never called, so the GIL is free: a platform thread completes promptly.
        val result = AtomicReference<Int?>(null)
        val p = Thread { result.set(withGIL { 42 }) }
        p.start()
        p.join(30_000)
        assertEquals(42, result.get(), "platform thread withGIL must complete (GIL not leaked)")
    }

    @Test
    fun platformThreadAndNestedScopesStillWork() = PythonTestFixture.withInterpreter {
        val result = AtomicReference<Int?>(null)
        val p = Thread { result.set(withGIL { withGIL { 1 } + withGIL { 2 } }) }
        p.start()
        p.join(30_000)
        assertEquals(3, result.get())
        assertEquals(7, withGIL { withGIL { 7 } })
    }
}
