package python.multiplatform.ffi

import python.native.ffi.Panama
import java.lang.invoke.MethodHandle
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #161 option B: on a JVM virtual thread the outermost [withGIL] runs pinned to its OS carrier,
 * so a virtual thread that parks inside the scope resumes on the same OS thread that called
 * `PyGILState_Ensure`, and `Release` runs there too.
 *
 * Red without the pin (`runOnPinnedCarrier` calling its body directly): 16 virtual threads that each
 * park several times inside the scope are rescheduled onto other carriers, so the OS thread recorded
 * after a park differs from the one recorded at entry.
 */
class VirtualThreadGILTest {

    private fun virtualThreadsAvailable() = Runtime.version().feature() >= 21

    /** The OS thread the caller runs on right now (`pthread_self`), through the same Panama path. */
    private val pthreadSelf: MethodHandle by lazy {
        val long = Long::class.javaPrimitiveType!!
        Panama.findSymbol("pthread_self", long, arrayOf())
    }

    private fun osThread(): Long = pthreadSelf.invokeExact() as Long

    @Test
    fun aVirtualThreadParkingInsideWithGilStaysOnOneOsThread() = PythonTestFixture.withInterpreter {
        if (!virtualThreadsAvailable() || System.getProperty("os.name").startsWith("Windows")) return@withInterpreter
        val moved = ConcurrentLinkedQueue<String>()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val threads = List(16) { k ->
            Thread.ofVirtual().unstarted {
                try {
                    withGIL {
                        val entry = osThread()
                        repeat(5) { round ->
                            if (round % 2 == 0) Thread.sleep(2) else LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2))
                            val now = osThread()
                            if (now != entry) moved += "thread $k round $round: $entry -> $now"
                        }
                        // still attached and usable on the same carrier
                        check(PythonTestFixture.eval("6 * 7").toString() == "42")
                    }
                } catch (t: Throwable) {
                    failures += t
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(60_000) }
        assertTrue(threads.none { it.isAlive }, "a virtual thread did not finish")
        assertTrue(failures.isEmpty(), "failures: ${failures.map { it.toString() }}")
        assertTrue(moved.isEmpty(), "a virtual thread changed OS thread inside withGIL: ${moved.take(5)}")
    }

    @Test
    fun anExceptionInsideAPinnedScopeReachesTheCaller() = PythonTestFixture.withInterpreter {
        if (!virtualThreadsAvailable()) return@withInterpreter
        var seen: Throwable? = null
        val t = Thread.ofVirtual().start {
            try {
                withGIL { throw IllegalArgumentException("from inside") }
            } catch (e: IllegalArgumentException) {
                seen = e
            }
        }
        t.join(30_000)
        assertEquals("from inside", seen?.message)
        // the GIL was released on the way out: a platform thread can take it at once
        val p = Thread { withGIL { PythonTestFixture.eval("1 + 1") } }
        p.start()
        p.join(30_000)
        assertTrue(!p.isAlive, "the GIL was leaked by the failed pinned scope")
    }

    @Test
    fun nestedScopesAndPlatformThreadsAreUnchanged() = PythonTestFixture.withInterpreter {
        val inner = withGIL { withGIL { PythonTestFixture.eval("2 + 3").toString() } }
        assertEquals("5", inner)
        if (!virtualThreadsAvailable()) return@withInterpreter
        var nested = ""
        val t = Thread.ofVirtual().start { nested = withGIL { withGIL { PythonTestFixture.eval("3 * 3").toString() } } }
        t.join(30_000)
        assertEquals("9", nested)
    }
}
