package python.multiplatform.reflection

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HandleTable] must be safe with no GIL: the ctypes `CFUNCTYPE` path releases it around
 * `_pm_release`, and a free-threaded build has none. Pure table, no interpreter needed.
 */
class HandleTableConcurrencyTest {

    private class Token(val owner: Int, val seq: Int)

    @Test
    fun concurrentRegisterResolveReleaseKeepsEveryHandleItsOwn() {
        val threads = 8
        val cycles = 20_000
        val before = HandleTable.liveCount
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val failure = AtomicReference<Throwable?>(null)

        repeat(threads) { id ->
            Thread {
                try {
                    start.await()
                    val held = ArrayList<Pair<ObjectReference, Token>>()
                    for (i in 0 until cycles) {
                        val token = Token(id, i)
                        val ref = HandleTable.register(token)
                        held.add(ref to token)
                        if (held.size >= 8) {
                            for ((r, t) in held) {
                                val got = HandleTable.resolve(r)
                                assertTrue(got === t, "thread $id resolved a foreign or stale object")
                            }
                            for ((r, _) in held) assertTrue(HandleTable.release(r), "release lost a live entry")
                            for ((r, _) in held) assertNull(HandleTable.resolve(r))
                            held.clear()
                        }
                    }
                    for ((r, _) in held) HandleTable.release(r)
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()
        done.await()

        failure.get()?.let { throw AssertionError("concurrent table use failed", it) }
        assertEquals(before, HandleTable.liveCount)
    }
}
