package fixture.artifact

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonCallables
import python.multiplatform.ffi.pythonx.PythonxAdapter
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.FunctionTable
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #164 items 4 and 5 (pythonx-concurrent SPEC S12), end to end on the real `kotlinx-coroutines-core`
 * jar walked by the artefact walker (this fixture's `artifactIncludePackages`), with no hand-written
 * table:
 *
 * - item 4, opaque Kotlin objects as arguments: a `CoroutineDispatcher` and `Job`s are held in Python
 *   as handles and passed back into Kotlin parameters (`Job(parent)`, `dispatch(context, ...)`,
 *   `isDispatchNeeded(context)`), and `Job.cancel()` reaches the same Kotlin object;
 * - item 5, a Python callable as a Kotlin `Runnable` invoked from any Kotlin thread: a Python
 *   callable fills `CoroutineDispatcher.dispatch`'s `block: Runnable`, and the dispatcher's own
 *   worker threads run it after `dispatch` has returned, with the binder attaching each thread
 *   (`PythonFunction.invoke` runs under `withGIL`). Inside an open [PythonCallables] scope the scope
 *   holds the callable (B-5a), so those tests keep their scope open until every callback has run;
 *   with no scope open it is a one-shot released after its run (B-5c), which is how
 *   pythonx-concurrent's `KotlinEventLoop` posts.
 *
 * Red before: `Job.cancel` did not exist (the walker declined `cancel(cause: CancellationException? =
 * null)` because `java.util.concurrent.CancellationException` is in no walked jar), and before this
 * fixture walked `kotlinx.coroutines` none of these names existed in Python.
 */
class WalkedArtifactCoroutinesTest {

    @BeforeTest
    fun installBothProducers() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        UpcallTable.install(FunctionTable.fragments + ArtifactTable.fragments)
        check(UpcallBootstrap.publishToGlobals()) { "UpcallBootstrap.publishToGlobals() failed" }
        PythonProxySource.install()
        PythonxAdapter.install(PYTHONX_RAW_VALUE_CLASSES)
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    @Test
    fun aDispatcherAndJobsCrossBackIntoKotlinAsTheSameObjects() {
        Python3.exec(
            """
            from kotlinx.coroutines import Dispatchers, Job
            _parent = Job()
            _child = Job(_parent)
            assert _child.parent == _parent, 'Job(parent) did not receive the parent handle: ' + repr(_child.parent)
            assert Dispatchers.Default.isDispatchNeeded(_parent) is True
            assert not _child.isCancelled
            # `cancel(cause: CancellationException? = null)`: a member's default is omittable (B-5b).
            _parent.cancel()
            assert _parent.isCancelled, 'Job.cancel() did not reach the Kotlin Job'
            assert _child.isCancelled, 'cancelling the parent handle did not cancel the child made from it'
            """.trimIndent(),
        )
    }

    @Test
    fun aPythonCallableRunsAsTheRunnableOfADispatcherWorkerThread() {
        PythonCallables.withScope(PythonCallables.newScope()) {
            Python3.exec(
                """
                import threading
                from kotlinx.coroutines import Dispatchers, Job
                _done = threading.Event()
                _seen = []
                def _body():
                    _seen.append(threading.get_ident())
                    _done.set()
                Dispatchers.Default.dispatch(Job(), _body)
                assert _done.wait(30), 'the dispatcher never ran the Python callable'
                assert _seen[0] != threading.get_ident(), 'the callable ran on the calling thread, not a worker'
                """.trimIndent(),
            )
        }
    }

    @Test
    fun manyPythonCallablesRunConcurrentlyOnDefaultAndIoWorkers() {
        PythonCallables.withScope(PythonCallables.newScope()) {
            Python3.exec(
                """
                import threading
                from kotlinx.coroutines import Dispatchers, Job
                _n = 400
                _lock = threading.Lock()
                _all = threading.Event()
                _count = [0]
                _threads = set()
                def _body():
                    with _lock:
                        _count[0] += 1
                        _threads.add(threading.get_ident())
                        if _count[0] == _n:
                            _all.set()
                _ctx = Job()
                for i in range(_n):
                    (Dispatchers.Default if i % 2 == 0 else Dispatchers.IO).dispatch(_ctx, _body)
                assert _all.wait(60), 'only %d of %d callables ran' % (_count[0], _n)
                assert threading.get_ident() not in _threads
                """.trimIndent(),
            )
        }
    }

    /**
     * SPEC B-5c: no scope open, which is the thread pythonx-concurrent posts from. Each callable is a
     * one-shot: it runs on a worker and is then released, callable and root both, with nothing
     * holding it open. Red before: the crossing refused with "no pythonx callable scope is open".
     */
    @Test
    fun aCallablePostedWithNoScopeOpenRunsOnceAndReleasesEverything() {
        assertEquals(0, PythonCallables.openScopeCount, "this test needs no scope open")
        val baseline = settledBaseline()
        Python3.exec(
            """
            import gc, threading, time, weakref
            from kotlinx.coroutines import Dispatchers, Job
            _n = 64
            _done = threading.Semaphore(0)
            class _Posted:
                def __init__(self):
                    self.calls = 0
                def __call__(self):
                    self.calls += 1
                    _done.release()
            _posted = [_Posted() for _ in range(_n)]
            _weak = [weakref.ref(p) for p in _posted]
            _ctx = Job()
            for p in _posted:
                Dispatchers.Default.dispatch(_ctx, p)
            for _ in range(_n):
                assert _done.acquire(timeout=30), 'a posted callable never ran'
            assert all(p.calls == 1 for p in _posted), 'a one-shot ran other than once'
            del _posted, p, _ctx
            # The release runs on the worker after the callable returns, so it may trail _done.
            _deadline = time.monotonic() + 30
            while any(w() is not None for w in _weak) and time.monotonic() < _deadline:
                gc.collect()
                time.sleep(0.01)
            assert all(w() is None for w in _weak), 'a one-shot kept its callable after it ran'
            """.trimIndent(),
        )
        val deadline = System.nanoTime() + 30_000_000_000L
        while (settledBaseline() > baseline && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(
            HandleTable.liveCount <= baseline,
            "one-shots that ran must root nothing: ${HandleTable.liveCount} against $baseline",
        )
    }

    /**
     * #509 review F2: a callable that raises on a dispatcher worker. The exception goes to the
     * worker's uncaught-exception handler, the one-shot is still released, and the dispatcher keeps
     * running later callables (more failures than the pool has workers, then a good one).
     */
    @Test
    fun aCallableThatRaisesOnTheWorkerLeavesTheDispatcherWorking() {
        Python3.exec(
            """
            import gc, threading, time, weakref
            from kotlinx.coroutines import Dispatchers, Job
            _bad_ran = threading.Semaphore(0)
            class _Bad:
                def __call__(self):
                    _bad_ran.release()
                    raise ValueError('raised on purpose on a dispatcher worker')
            _bads = [_Bad() for _ in range(64)]
            _bad_weak = [weakref.ref(b) for b in _bads]
            for b in _bads:
                Dispatchers.Default.dispatch(Job(), b)
            del _bads, b
            for _ in range(64):
                assert _bad_ran.acquire(timeout=30), 'a raising callable never ran'
            _good = threading.Event()
            Dispatchers.Default.dispatch(Job(), lambda: _good.set())
            assert _good.wait(30), 'the dispatcher stopped running callables after ones that raised'
            """.trimIndent(),
        )
        // The one-shot gives its reference back in `finally` (B-5c), but each raised exception's
        // traceback holds the thunk's frames, and through them the callable, until the Kotlin
        // `PyException` the worker handed to its uncaught-exception handler is collected (its cleaner
        // drops the exception). So the check waits for the JVM's collection as well as Python's.
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            System.gc()
            Thread.sleep(20)
            Python3.exec("gc.collect()\n_bad_left = sum(w() is not None for w in _bad_weak)")
            if (Python3.eval("_bad_left").toString() == "0") return
        }
        throw AssertionError("one-shots that raised kept ${Python3.eval("_bad_left")} of 64 callables after the JVM collected their exceptions")
    }

    /** [HandleTable.liveCount] with Python's garbage (proxies whose `__del__` releases a root) collected. */
    private fun settledBaseline(): Int {
        Python3.exec("import gc\ngc.collect()")
        return HandleTable.liveCount
    }
}
