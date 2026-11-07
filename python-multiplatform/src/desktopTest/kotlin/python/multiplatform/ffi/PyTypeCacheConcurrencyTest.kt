package python.multiplatform.ffi

import python.multiplatform.ffi.utils.PyGC
import python.native.ffi.NativePointer
import python.native.ffi.PyList_GetItem
import python.native.ffi.Py_IncRef
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [PyType.getInstance] under concurrent first-use of many distinct type objects (issue #160).
 *
 * Before the copy-on-write cache the memo was a plain `mutableMapOf`. Two threads putting at once
 * could lose an entry (a later lookup then builds a second [PyType] for the same pointer, so
 * identity differs across threads) or corrupt the map outright (an exception). That needs real
 * parallel writers, so it can fail only on a free-threaded CPython (`-PpythonFreeThreaded=true`);
 * under the default GIL, getInstance's own `withPython` calls serialise most of the interleaving
 * and the test is a regression guard rather than a reproducer.
 *
 * Asserted: no thread throws; for each type every thread was handed the same instance; a final
 * lookup returns that same instance; no type's refcount fell below its starting value (a lost-race
 * [PyType] must release through its own cleaner, never by hand).
 */
class PyTypeCacheConcurrencyTest {

    private val typeCount = 200
    private val threadCount = 8
    private val rounds = 5

    @Test
    fun concurrentGetInstanceYieldsOneInstancePerType() = PythonTestFixture.withInterpreter {
        val classes = PythonTestFixture.eval("[type('PmpCacheC%d' % i, (), {}) for i in range($typeCount)]")
        val pointers: List<NativePointer> = withGIL {
            List(typeCount) { PyList_GetItem(classes.pointer, it.toLong())!! }
        }
        // Hold one extra reference per class for the whole test so the starting count is stable.
        val holders = withGIL { pointers.map { PyObject(it, true) } }
        val startCounts = holders.map { PyGC.refCount(it) }

        val seen = Array(threadCount) { arrayOfNulls<PyType>(typeCount) }
        val failure = AtomicReference<Throwable?>(null)
        val ready = CountDownLatch(threadCount)
        val go = CountDownLatch(1)
        val threads = List(threadCount) { t ->
            Thread {
                try {
                    ready.countDown()
                    go.await()
                    repeat(rounds) {
                        // Different start offset per thread so threads collide on different keys.
                        for (k in 0 until typeCount) {
                            val i = (k + t * 17) % typeCount
                            // A fresh new reference each call, as adoptingNewReference callers supply.
                            withGIL { Py_IncRef(pointers[i]) }
                            val got = PyType.getInstance(pointers[i])
                            val first = seen[t][i]
                            if (first == null) seen[t][i] = got
                            else if (first !== got) throw AssertionError("thread $t saw two instances for type $i")
                        }
                    }
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                }
            }.apply { isDaemon = true; start() }
        }
        ready.await()
        go.countDown()
        threads.forEach { it.join(120_000) }
        assertTrue(threads.none { it.isAlive }, "a worker did not finish")
        assertNull(failure.get(), "a worker failed: ${failure.get()}")

        for (i in 0 until typeCount) {
            withGIL { Py_IncRef(pointers[i]) }
            val final = PyType.getInstance(pointers[i])
            for (t in 0 until threadCount) assertSame(final, seen[t][i], "type $i: thread $t holds a different instance")
        }

        for (i in 0 until typeCount) {
            val now = PyGC.refCount(holders[i])
            assertTrue(now >= startCounts[i], "type $i refcount fell from ${startCounts[i]} to $now (double free)")
        }
        assertEquals(typeCount, holders.size)
    }
}
