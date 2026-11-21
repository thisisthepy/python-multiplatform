package python.multiplatform.ffi.pythonx

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.getThreadGILState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SPEC U-5e's thread claims (#164 item 13, pythonx-concurrent S7.11 and S7.12): each step of one
 * coroutine may run on a different thread, one CPython never saw included, and leaves that thread
 * detached; on a JVM virtual thread a step is one pinned call that completes and detaches the same way.
 *
 * Red before `PyCoroutineStep.kt` existed: `step` and `PyStep` did not resolve.
 */
class PyCoroutineStepThreadTest {

    private fun defineCoroutine(name: String) {
        Python3.exec(
            """
            import threading
            _stt = {'tids': []}
            class _SttPause:
                def __await__(self):
                    return (yield self)
            async def _stt_body():
                _stt['tids'].append(threading.get_ident())
                await _SttPause()
                _stt['tids'].append(threading.get_ident())
                await _SttPause()
                _stt['tids'].append(threading.get_ident())
                return 7
            _stt['$name'] = _stt_body()
            """.trimIndent(),
        )
    }

    private fun stepOn(thread: Thread, coro: PyObject, results: MutableList<PyStep>, depths: MutableList<Int>, failures: MutableList<Throwable>) {
        thread.start()
        thread.join(30_000)
        assertTrue(!thread.isAlive, "the stepping thread did not finish")
        failures.firstOrNull()?.let { throw AssertionError("a step failed on ${thread.name}: $it", it) }
        assertEquals(0, depths.last(), "the stepping thread must be detached after the step")
    }

    /**
     * The three threads stay alive (parked on [release]) until the ids are compared: `threading.get_ident()`
     * is `pthread_self`, and the OS gives a finished thread's id to the next one started, so three fresh
     * threads run one after the other can share an id (run 44901179235 saw two ids for three threads).
     * Each thread still steps alone: the next starts only after the previous one's step has completed.
     */
    @Test
    fun eachStepMayRunOnADifferentThreadCPythonNeverSawAndLeavesItDetached() = PythonTestFixture.withInterpreter {
        defineCoroutine("coro")
        val coro = PythonTestFixture.eval("_stt['coro']")
        val results = CopyOnWriteArrayList<PyStep>()
        val depths = CopyOnWriteArrayList<Int>()
        val failures = CopyOnWriteArrayList<Throwable>()
        val release = CountDownLatch(1)
        val threads = ArrayList<Thread>()
        try {
            repeat(3) { i ->
                val stepped = CountDownLatch(1)
                val t = Thread({
                    try {
                        results += coro.step()
                        depths += getThreadGILState().depth
                    } catch (t: Throwable) {
                        failures += t
                    } finally {
                        stepped.countDown()
                    }
                    // Detached by now (depth recorded above); parked so the id stays this thread's.
                    release.await()
                }, "step-thread-cpython-never-saw-$i")
                threads += t
                t.start()
                assertTrue(stepped.await(30, TimeUnit.SECONDS), "the stepping thread ${t.name} did not finish its step")
                failures.firstOrNull()?.let { throw AssertionError("a step failed on ${t.name}: $it", it) }
                assertEquals(0, depths.last(), "the stepping thread ${t.name} must be detached after the step")
            }
            assertIs<PyStep.Yielded>(results[0]).value.close()
            assertIs<PyStep.Yielded>(results[1]).value.close()
            val done = assertIs<PyStep.Returned>(results[2])
            assertEquals("7", done.value.toString())
            done.value.close()

            val tids = PythonTestFixture.eval("sorted(set(_stt['tids']))").toString()
            assertEquals(3, PythonTestFixture.eval("len(set(_stt['tids']))").toString().toInt(), "three steps on three threads: $tids")
            val testThread = PythonTestFixture.eval("threading.get_ident()").toString()
            assertEquals("False", PythonTestFixture.eval("$testThread in _stt['tids']").toString(), "no step ran on the test thread")
        } finally {
            release.countDown()
            for (t in threads) t.join(30_000)
        }
    }

    @Test
    fun aStepOnAVirtualThreadCompletesAndLeavesTheCarrierDetached() = PythonTestFixture.withInterpreter {
        if (Runtime.version().feature() < 21) return@withInterpreter
        defineCoroutine("vcoro")
        val coro = PythonTestFixture.eval("_stt['vcoro']")
        val results = CopyOnWriteArrayList<PyStep>()
        val depths = CopyOnWriteArrayList<Int>()
        val failures = CopyOnWriteArrayList<Throwable>()
        val t = Thread.ofVirtual().name("step-virtual").unstarted {
            try {
                results += coro.step()
                results += coro.step()
                results += coro.step()
                depths += getThreadGILState().depth
            } catch (t: Throwable) {
                failures += t
            }
        }
        stepOn(t, coro, results, depths, failures)
        assertIs<PyStep.Yielded>(results[0]).value.close()
        assertIs<PyStep.Yielded>(results[1]).value.close()
        val done = assertIs<PyStep.Returned>(results[2])
        assertEquals("7", done.value.toString())
        done.value.close()
        // the virtual thread was detached: a platform thread can attach at once
        val p = Thread { PythonTestFixture.eval("1 + 1") }
        p.start()
        p.join(30_000)
        assertTrue(!p.isAlive, "a platform thread could not attach after the virtual thread's steps")
    }
}
