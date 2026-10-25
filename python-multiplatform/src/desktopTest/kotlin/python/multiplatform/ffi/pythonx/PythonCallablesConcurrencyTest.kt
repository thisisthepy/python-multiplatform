package python.multiplatform.ffi.pythonx

import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.types.basic.PyInt
import python.multiplatform.reflection.HandleTable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Threading rules of [PythonCallables] and [PythonCallableScope] (issue #301): the ambient stack is
 * per thread, and one scope's state is safe against concurrent add, reuse and close.
 */
class PythonCallablesConcurrencyTest {

    @BeforeTest
    fun up() {
        check(PythonTestFixture.available) { "CPython could not be initialized (${PythonTestFixture.failureReason})" }
    }

    @AfterTest
    fun down() {
        HandleTable.releaseAll()
    }

    /** (i) Two threads each open their own scope many times: no "out of order", `current` is their own. */
    @Test
    fun twoThreadsOpeningScopesConcurrentlyKeepTheirOwnStacks() {
        val errors = ConcurrentLinkedQueue<Throwable>()
        val start = CountDownLatch(1)
        val workers = List(2) {
            thread {
                try {
                    start.await()
                    repeat(20_000) {
                        val outer = PythonCallables.newScope()
                        val inner = PythonCallables.newScope()
                        PythonCallables.withScope(outer) {
                            check(PythonCallables.current === outer) { "current is not this thread's outer scope" }
                            PythonCallables.withScope(inner) {
                                check(PythonCallables.current === inner) { "current is not this thread's inner scope" }
                                check(PythonCallables.openScopeCount == 2) { "openScopeCount ${PythonCallables.openScopeCount}" }
                            }
                            check(PythonCallables.current === outer) { "current after inner is not outer" }
                        }
                        check(PythonCallables.current == null) { "a scope is left open" }
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        start.countDown()
        workers.forEach { it.join() }
        assertTrue(errors.isEmpty(), "threads failed: ${errors.map { it.message }.distinct()}")
    }

    /** (ii) add/reuse from many threads on one scope while another closes it. */
    @Test
    fun concurrentAddAndReuseAgainstCloseReleasesEveryRootOnce() {
        val baseline = HandleTable.liveCount
        repeat(30) { round ->
            val scope = PythonCallables.newScope()
            val unexpected = ConcurrentLinkedQueue<Throwable>()
            val closedFailures = AtomicInteger()
            val closeTotal = AtomicInteger()
            val start = CountDownLatch(1)
            val adders = List(6) { id ->
                thread {
                    start.await()
                    for (n in 0 until 200) {
                        val key = if (n % 2 == 0) "shared-${n % 10}" else "own-$id-$n"
                        try {
                            PythonCallables.withScope(scope) {
                                val found = PythonCallables.findFunction(key)
                                if (found == 0L || found == python.multiplatform.reflection.ObjectReference.NONE_RAW) {
                                    PythonCallables.newFunction(PyInt.from(n.toLong()), 0, false, "", key)
                                }
                            }
                        } catch (e: IllegalStateException) {
                            if (e.message?.contains("closed") == true) closedFailures.incrementAndGet()
                            else unexpected.add(e)
                        } catch (t: Throwable) {
                            unexpected.add(t)
                        }
                    }
                }
            }
            val closers = List(2) {
                thread {
                    try {
                        start.await()
                        Thread.sleep(0, 200_000 * (round % 4 + 1))   // nanos must stay below 1_000_000
                        closeTotal.addAndGet(scope.close())
                    } catch (t: Throwable) {
                        unexpected.add(t)
                    }
                }
            }
            start.countDown()
            (adders + closers).forEach { it.join() }
            // A late add that lost the race to close() is rejected, not stored; stragglers closed.
            assertTrue(unexpected.isEmpty(), "unexpected failures: ${unexpected.map { it.toString() }.distinct()}")
            assertTrue(scope.isClosed)
            assertEquals(0, scope.liveCount, "close left bodies behind")
            assertEquals(0, scope.close(), "a second close released something")
            assertEquals(baseline, HandleTable.liveCount, "round $round leaked or over-released roots")
        }
    }

    /** (iii) A thread with no scope sees no scope while another thread has one open. */
    @Test
    fun aThreadWithNoScopeSeesNoneWhileAnotherHasOneOpen() {
        val scope = PythonCallables.newScope()
        val opened = CountDownLatch(1)
        val observed = CountDownLatch(1)
        val owner = thread {
            PythonCallables.withScope(scope) {
                opened.countDown()
                observed.await()
            }
        }
        opened.await()
        var seen: PythonCallableScope? = scope
        var count = -1
        var failure: Throwable? = null
        thread {
            seen = PythonCallables.current
            count = PythonCallables.openScopeCount
            try {
                PythonCallables.newFunction(PyInt.from(1L), 0, false, "", "k")
            } catch (t: Throwable) {
                failure = t
            }
        }.join()
        observed.countDown()
        owner.join()
        assertNull(seen, "this thread saw another thread's scope")
        assertEquals(0, count)
        assertTrue(failure is IllegalStateException && failure!!.message!!.contains("no pythonx callable scope"), "got $failure")
        assertEquals(0, scope.liveCount)
    }
}
