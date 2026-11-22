package python.multiplatform.ffi.pythonx

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.reflection.HandleTable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * SPEC B-5c (#164 item 5): a Python callable crossing into a `Runnable` / `() -> Unit` slot with no
 * [PythonCallableScope] open is a one-shot. It runs once, gives back its callable and its
 * [HandleTable] root after that run (returned or raised), and refuses every later or concurrent
 * invocation by name.
 *
 * The callable is an instance with `__call__` watched through a `weakref`, so "given back" is read
 * from the object really being freed rather than from a reference count, which free-threaded
 * CPython defers for functions.
 *
 * Red before: [PythonCallables.newFunction] refused every crossing with no scope open ("no pythonx
 * callable scope is open"), so nothing here got a wrapper.
 */
class PythonRunOnceTest {

    @BeforeTest
    fun up() {
        check(PythonTestFixture.available) { "CPython could not be initialized (${PythonTestFixture.failureReason})" }
        check(PythonCallables.current == null) { "a scope is open on the test thread; B-5c needs none" }
        Python3.exec(
            """
            import gc, threading, weakref
            class _RunOnceProbe:
                def __init__(self, fail=False):
                    self.calls = 0
                    self.fail = fail
                    self.lock = threading.Lock()
                def __call__(self):
                    with self.lock:
                        self.calls += 1
                    if self.fail:
                        raise ValueError('raised on purpose by the one-shot probe')
            def _run_once_new(fail=False):
                global _ro_probe, _ro_weak
                _ro_probe = _RunOnceProbe(fail)
                _ro_weak = weakref.ref(_ro_probe)
            """.trimIndent(),
        )
    }

    @AfterTest
    fun down() {
        HandleTable.releaseAll()
    }

    /** Crosses `_ro_probe` with no scope open and drops Python's own reference to it. */
    private fun crossProbe(fail: Boolean = false): () -> Unit {
        Python3.exec("_run_once_new(${if (fail) "True" else "False"})")
        // Python3.eval answers a new reference, which newFunction takes ownership of (as it does the
        // trampoline's reference in the real crossing).
        val raw = PythonCallables.newFunction(Python3.eval("_ro_probe"), 0, false, "", "")
        Python3.exec("del _ro_probe")
        @Suppress("UNCHECKED_CAST")
        return HandleTable.resolveRaw(raw) as () -> Unit
    }

    /** Whether the probe object is gone, after a collection for the deferred frees of free-threading. */
    private fun probeFreed(): Boolean {
        Python3.exec("gc.collect()")
        return Python3.eval("_ro_weak() is None").toString() == "True"
    }

    private fun probeFreedAfterJvmCollection(): Boolean {
        repeat(100) {
            System.gc()
            Thread.sleep(20)
            if (probeFreed()) return true
        }
        return false
    }

    /** Runs [run] expecting a failure, and keeps only its message: the exception object itself must not
     * stay reachable from this frame, or the JVM would never collect it. */
    private fun errorMessageOf(run: () -> Unit): String = try {
        run()
        throw AssertionError("expected the one-shot's run to fail")
    } catch (e: AssertionError) {
        throw e
    } catch (t: Throwable) {
        t.message ?: t.toString()
    }

    private fun calls(): Int = Python3.eval("_ro_weak().calls if _ro_weak() is not None else -1").toString().toInt()

    @Test
    fun aUnitCallableCrossingWithNoScopeRunsOnceThenReleasesItsCallableAndRoot() {
        val baseline = HandleTable.liveCount
        val run = crossProbe()
        assertEquals(baseline + 1, HandleTable.liveCount, "the one-shot is reached through one root")
        assertTrue(Python3.eval("_ro_weak() is not None").toString() == "True", "the one-shot must hold the callable until it runs")

        Python3.exec("_ro_keep = _ro_weak()")
        run()
        assertEquals(1, calls(), "the callable did not run exactly once")
        Python3.exec("_ro_keep = None")

        assertTrue(probeFreed(), "the one-shot kept the callable after its run")
        assertEquals(baseline, HandleTable.liveCount, "the one-shot kept its HandleTable root after its run")
    }

    @Test
    fun aSecondInvocationRaisesByNameAndDoesNotRunTheCallableAgain() {
        val run = crossProbe()
        Python3.exec("_ro_keep = _ro_weak()")
        run()
        val refusal = assertFailsWith<IllegalStateException> { run() }
        assertTrue(
            refusal.message?.contains("one-shot") == true && refusal.message?.contains("already ran") == true,
            "the second run must say the one-shot already ran, got: ${refusal.message}",
        )
        assertEquals(1, calls(), "a second invocation reached the callable")
        Python3.exec("_ro_keep = None")
    }

    @Test
    fun concurrentInvocationsRunTheCallableExactlyOnce() {
        repeat(20) { round ->
            val baseline = HandleTable.liveCount
            val run = crossProbe()
            Python3.exec("_ro_keep = _ro_weak()")
            val start = CountDownLatch(1)
            val refused = AtomicInteger()
            val unexpected = ConcurrentLinkedQueue<Throwable>()
            val invokers = List(16) {
                thread {
                    start.await()
                    try {
                        run()
                    } catch (e: IllegalStateException) {
                        if (e.message?.contains("one-shot") == true) refused.incrementAndGet() else unexpected.add(e)
                    } catch (t: Throwable) {
                        unexpected.add(t)
                    }
                }
            }
            start.countDown()
            invokers.forEach { it.join() }
            assertTrue(unexpected.isEmpty(), "round $round: unexpected failures ${unexpected.map { it.toString() }.distinct()}")
            assertEquals(1, calls(), "round $round: the callable ran other than once")
            assertEquals(15, refused.get(), "round $round: every other invocation must refuse")
            Python3.exec("_ro_keep = None")
            assertTrue(probeFreed(), "round $round: the callable was not released")
            assertEquals(baseline, HandleTable.liveCount, "round $round: the root was not released exactly once")
        }
    }

    @Test
    fun aCallableThatRaisesIsReleasedAndItsErrorReachesTheInvoker() {
        val baseline = HandleTable.liveCount
        val run = crossProbe(fail = true)
        Python3.exec("_ro_keep = _ro_weak()")
        val message = errorMessageOf(run)
        assertTrue(message.contains("raised on purpose"), "the invoker must get the Python error, got: $message")
        assertEquals(1, calls())
        val again = assertFailsWith<IllegalStateException> { run() }
        assertTrue(again.message?.contains("one-shot") == true, "a raised run still counts as the one run")
        assertEquals(1, calls())
        Python3.exec("_ro_keep = None")
        // The one-shot released its reference in `finally`, but the raised exception's traceback holds
        // `__call__`'s frame, and through `self` the probe, until the Kotlin `PyException` is collected
        // and its cleaner drops the exception. So this waits for the JVM's collection too.
        assertTrue(probeFreedAfterJvmCollection(), "a callable that raised was not released")
        assertEquals(baseline, HandleTable.liveCount, "a callable that raised kept its root")
    }

    @Test
    fun withNoScopeASlotThatTakesArgumentsOrIsComposableIsStillRefused() {
        Python3.exec("_run_once_new()")
        val withArgument = assertFailsWith<IllegalStateException> {
            PythonCallables.newFunction(Python3.eval("_ro_probe"), 1, false, "INT", "")
        }
        assertTrue(withArgument.message?.contains("no pythonx callable scope is open") == true, "${withArgument.message}")
        val composable = assertFailsWith<IllegalStateException> {
            PythonCallables.newFunction(Python3.eval("_ro_probe"), 2, true, "", "")
        }
        assertTrue(composable.message?.contains("no pythonx callable scope is open") == true, "${composable.message}")
    }

    @Test
    fun insideAScopeAUnitCallableIsNotOneShot() {
        Python3.exec("_run_once_new()\n_ro_keep = _ro_probe")
        val scope = PythonCallables.newScope()
        val run = PythonCallables.withScope(scope) {
            @Suppress("UNCHECKED_CAST")
            HandleTable.resolveRaw(PythonCallables.newFunction(Python3.eval("_ro_probe"), 0, false, "", "")) as () -> Unit
        }
        run()
        run()
        assertEquals(2, calls(), "a scoped callable is not a one-shot (B-5a)")
        assertEquals(1, scope.close())
        Python3.exec("_ro_keep = None\n_ro_probe = None")
    }
}
