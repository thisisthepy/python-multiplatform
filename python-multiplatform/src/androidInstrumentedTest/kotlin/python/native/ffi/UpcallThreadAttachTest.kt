package python.native.ffi

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Android's half of `UpcallEntryTest`'s (`commonTest`) contract resolves through the same
 * `_pm_resolve`/`_pm_bind`/`_pm_release` `PyMethodDef` bootstrap as `nativeTest` -- see
 * [UpcallEntry.publish] -- but with `androidMain`'s JNI inversion underneath: the entry points are
 * C functions in `artMain/cinterop/jni_onload.def` and *they* call *Kotlin*, through
 * [UpcallCallbacks].
 *
 * The one case only this target has -- an upcall arriving on a thread ART has never seen -- has no
 * `commonTest` counterpart (desktop, Kotlin/Native and wasmJs all reach Kotlin without a runtime
 * boundary at all, so a `threading.Thread` there is just another thread) and stays here.
 */
actual fun bindUpcallOrNull(name: String): Boolean {
    val globals: PyObject = PythonTestFixture.mainGlobals()
    check(UpcallEntry.publish(globals.pointer)) { "the upcall bootstrap could not be published" }
    Python3.exec("_pm_h = _pm_resolve('$name')")
    val resolved = PythonTestFixture.eval("_pm_h").toString() != "-1"
    Python3.exec(if (resolved) "_pm_bound = _pm_bind(_pm_h)" else "_pm_bound = None")
    return resolved
}

/** Releases through `_pm_release`, the same `PyMethodDef` a proxy's `tp_dealloc` would call. */
actual fun releaseUpcallHandle(handle: Long): Int =
    PythonTestFixture.eval("_pm_release($handle)").toString().toInt()

class UpcallThreadAttachTest {

    private companion object {
        /** Upcalls per worker, when the question is how many attaches they cost. */
        const val CALLS_PER_WORKER = 64

        /** Workers alive at the same time in [manyConcurrentWorkersAreEachAttachedOnceAndAllReleased]. */
        const val CONCURRENT_WORKERS = 32

        /**
         * How long a released attachment is waited for.
         *
         * `threading.Thread.join()` is not the moment the pthread exits: CPython releases the
         * join lock in `_bootstrap_inner`'s `finally`, and the thread's TSD destructors -- which
         * is where the detach lives -- run after that, on the way out of the pthread. So the
         * detach is polled for rather than assumed to have happened by the time `join()` returns.
         * A deadline rather than a fixed sleep, so a slow emulator costs time and not a flake.
         */
        const val RELEASE_TIMEOUT_MS = 15_000L
    }

    @BeforeTest
    fun install() {
        PythonOnDevice.ensureInitialised()
        UpcallTable.install(listOf(ThreadProbeFragment))
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    /** Evaluates [expression] in `__main__` and returns its `str()`. */
    private fun py(expression: String): String = PythonTestFixture.eval(expression).toString()

    private fun pyLong(expression: String): Long = py(expression).toLong()

    private fun pyInt(expression: String): Int = py(expression).toInt()

    /**
     * How many of the `java.lang.Thread` peers recorded so far a collection cannot reclaim.
     *
     * This is the instrument the release assertions read, and it is deliberately *not*
     * `ThreadGroup.enumerate`. That was tried first and is unusable: on API 26 it does not report
     * the peer of an attached native thread even while that thread is running and upcalling, so
     * "the id is not in the list" would have meant nothing and every release assertion would have
     * passed vacuously. The positive control in
     * [theAttachmentIsReleasedWhenThePythonWorkerThreadDies] is what caught that, and it is kept
     * for the same reason on this instrument.
     *
     * Reachability works on both levels because it reads the thing that actually matters. ART
     * holds an attached thread's peer as a GC root (`tlsPtr_.opeer`) and releases it in
     * `Thread::Destroy`, so a peer that survives a collection is a peer that is still attached --
     * which is also the cost of keeping one, stated directly.
     */
    private fun unreclaimedPeers(): Int = UpcallTarget.liveTrackedCount()

    /**
     * Collects until [expected] tracked peers remain, or the deadline passes; returns what was
     * actually left. A collection is asked for rather than waited for, because the peer only
     * becomes unreachable at detach and nothing else in the process is allocating.
     */
    private fun awaitUnreclaimedPeers(expected: Int): Int {
        val deadline = System.nanoTime() + RELEASE_TIMEOUT_MS * 1_000_000L
        while (true) {
            Runtime.getRuntime().gc()
            System.runFinalization()
            val live = unreclaimedPeers()
            if (live <= expected || System.nanoTime() >= deadline) return live
            Thread.sleep(50)
        }
    }

    /**
     * The case Android has and no other platform does: an upcall arriving on a thread ART has
     * never seen.
     *
     * Desktop and Kotlin/Native reach Kotlin without a runtime boundary at all, so a
     * `threading.Thread` is just another thread there. On ART the entry point has to obtain a
     * `JNIEnv` before it can call anything, and `GetEnv` fails on a bare pthread -- which is what
     * every `threading.Thread` is. That is the `AttachCurrentThreadAsDaemon` branch of
     * `pmp_attach`, and without it every upcall from a Python worker thread would silently return
     * `NULL`.
     *
     * The thread identity is recorded from *inside* the Kotlin the upcall lands in, so a pass
     * cannot come from the work having been re-dispatched back onto the instrumentation thread.
     */
    @Test
    fun anUpcallArrivesOnAThreadCPythonCreatedRatherThanFailingToFindTheJvm() =
        PythonTestFixture.withInterpreter {
            assertTrue(bindUpcallOrNull("probe.whichThread"))
            val instrumentationThread = Thread.currentThread().id
            ThreadProbeFragment.lastThreadId = -1L
            Python3.exec(
                """
                import threading
                _pm_worker_result = None

                def _pm_worker():
                    global _pm_worker_result
                    _pm_worker_result = _pm_bound(0)

                _pm_t = threading.Thread(target=_pm_worker)
                _pm_t.start()
                _pm_t.join()
                """.trimIndent(),
            )

            val reported = py("_pm_worker_result").toLong()
            assertNotEquals(
                -1L, reported,
                "the upcall never reached Kotlin from the Python worker thread",
            )
            assertEquals(
                reported, ThreadProbeFragment.lastThreadId,
                "the value Python read back is not the one the upcall recorded",
            )
            assertNotEquals(
                instrumentationThread, reported,
                "the upcall was served on the instrumentation thread, so the attach branch was never taken",
            )
        }

    /**
     * What actually happens when an attached thread exits without detaching -- the fact the
     * per-thread attach is built on, run rather than read.
     *
     * `jni_onload.def` used to assert, in a comment, that ART aborts the process for this, and
     * that assertion is why every upcall from a Python worker paid an attach *and* a detach.
     * `Thread::ThreadExitCallback` says otherwise: the `LOG(FATAL)` needs a second invocation of
     * the callback, the only thing that arms one is a `pthread_setspecific` inside an `#else`
     * branch Android does not compile, and bionic clears a key's value before running its
     * destructor. So on Android the abort should be unreachable. Should is not is, and being wrong
     * here means shipping a process abort, so the case is executed.
     *
     * Reaching the assertions at all is the result: this method runs in the same process as the
     * thread that leaked, so an abort would take the whole instrumentation run with it, not fail
     * one test.
     *
     * The second half is why the detach is still mandatory. Surviving is not the same as being
     * free -- the peer stays in the thread list, a GC root with a recorded stack the pthread has
     * already unmapped -- so the fix is to move the detach to thread death, not to drop it.
     */
    @Test
    fun aThreadThatExitsWithoutDetachingLeaksItsPeerRatherThanAbortingArt() {
        UpcallTarget.clearTracked()
        val leaked = bindings.testAttachWithoutDetach(0L)
        assertNotEquals(
            -1L, leaked,
            "the probe thread never attached, so it cannot say anything about exiting attached",
        )
        // Reaching here at all is the first result: ART logged its warning and carried on, so the
        // abort the old comment promised is not what happens and the detach can be moved.
        assertEquals(
            1, awaitUnreclaimedPeers(0),
            "the peer of a pthread that exited without detaching became reclaimable on its own, " +
                "so ART released the attachment unprompted and pmp_thread_exit_detach is buying " +
                "nothing -- which is worth knowing, because it is not what Thread::Destroy does",
        )
        UpcallTarget.clearTracked()
    }

    /**
     * How many attaches N upcalls from one Python worker cost.
     *
     * `AttachCurrentThreadAsDaemon` builds a fresh `java.lang.Thread` for the pthread, and
     * `Thread.getId()` comes from a counter that is never reused, so the identity of the thread
     * the Kotlin side lands on is a direct count of the attaches: N distinct ids means one attach
     * per call, one id means one per thread. That is what makes this structural rather than a
     * stopwatch reading -- `UpcallOverheadTest` prices the difference, this decides it.
     *
     * Two workers, not one, because "one id" on its own has a second explanation: a *shared*
     * attachment, e.g. an env cached in a global rather than in thread-local storage, which would
     * be a correctness bug (one thread's JNI local frame handed to another). Two workers must
     * report two different ids, and neither may be the instrumentation thread's.
     */
    @Test
    fun aPythonWorkerThreadIsAttachedOncePerThreadRatherThanOncePerCall() =
        PythonTestFixture.withInterpreter {
            assertTrue(bindUpcallOrNull("probe.whichThread"))
            val instrumentationThread = Thread.currentThread().id
            Python3.exec(
                """
                import threading

                _pm_ids_a = []
                _pm_ids_b = []

                def _pm_collect(sink):
                    for _ in range($CALLS_PER_WORKER):
                        sink.append(_pm_bound(0))

                _pm_ta = threading.Thread(target=_pm_collect, args=(_pm_ids_a,))
                _pm_ta.start(); _pm_ta.join()
                _pm_tb = threading.Thread(target=_pm_collect, args=(_pm_ids_b,))
                _pm_tb.start(); _pm_tb.join()
                """.trimIndent(),
            )

            val distinctA = pyInt("len(set(_pm_ids_a))")
            val distinctB = pyInt("len(set(_pm_ids_b))")
            val idA = pyLong("_pm_ids_a[0]")
            val idB = pyLong("_pm_ids_b[0]")

            assertEquals(
                1, distinctA,
                "$CALLS_PER_WORKER upcalls from one Python worker landed on $distinctA distinct " +
                    "ART threads, so the attach is still being paid per call rather than per thread",
            )
            assertEquals(
                1, distinctB,
                "the second worker's $CALLS_PER_WORKER upcalls landed on $distinctB distinct ART threads",
            )
            assertNotEquals(
                idA, idB,
                "two different Python worker threads reported the same ART thread, so the " +
                    "attachment is shared between them rather than held per thread",
            )
            assertNotEquals(
                instrumentationThread, idA,
                "the upcall was served on the instrumentation thread, so nothing was attached at all",
            )
        }

    /**
     * The other half of amortising the attach: it has to be given back when the pthread dies.
     *
     * Keeping an attachment costs an ART `Thread`, a `java.lang.Thread` peer that is a GC root, a
     * JNI local reference table and an entry in the thread list that every `SuspendAll` walks.
     * None of that is reclaimed by the pthread exiting on its own -- ART only logs
     * *"Native thread exiting without having called DetachCurrentThread"* and hands the runtime's
     * own `Thread` back to itself, leaving the peer live and its recorded stack pointing at memory
     * the pthread has already unmapped. So a per-thread attach is only safe if something detaches
     * at thread death, and this is that claim, asserted rather than argued: the peers the
     * finished workers were attached to must leave the thread list.
     */
    @Test
    fun theAttachmentIsReleasedWhenThePythonWorkerThreadDies() =
        PythonTestFixture.withInterpreter {
            assertTrue(bindUpcallOrNull("probe.trackThread"))
            UpcallTarget.clearTracked()

            // The worker upcalls once and then parks, so it is observably attached *while* the
            // instrument is being read. Without this control, "the peer was reclaimed" has a
            // second explanation -- that the peer was never held in the first place -- and the
            // release assertions below would pass on an instrument that cannot see anything. That
            // is not hypothetical: it is exactly how the first version of this test, which read
            // ThreadGroup.enumerate, passed on API 26 while measuring nothing.
            Python3.exec(
                """
                import threading

                _pm_go = threading.Event()
                _pm_ready = threading.Event()
                _pm_id_held = []

                def _pm_hold_open():
                    _pm_id_held.append(_pm_bound(0))
                    _pm_ready.set()
                    _pm_go.wait()

                _pm_th = threading.Thread(target=_pm_hold_open)
                _pm_th.start()
                _pm_ready.wait()
                """.trimIndent(),
            )
            val held = pyLong("_pm_id_held[0]")
            assertEquals(
                1, awaitUnreclaimedPeers(0),
                "the peer of ART thread $held was reclaimed while its Python worker is still " +
                    "running and attached, so this test cannot tell a released attachment from " +
                    "an unheld one and neither assertion below means anything",
            )

            Python3.exec("_pm_go.set()\n_pm_th.join()")
            assertEquals(
                0, awaitUnreclaimedPeers(0),
                "the peer of ART thread $held was still unreclaimable ${RELEASE_TIMEOUT_MS}ms " +
                    "after its Python worker finished, so the attachment outlives the pthread",
            )

            // And again over a run of threads, because one detach working is not the same as the
            // destructor being armed on every attach.
            UpcallTarget.clearTracked()
            Python3.exec(
                """
                _pm_ids_seq = []

                def _pm_touch(sink):
                    sink.append(_pm_bound(0))

                for _ in range(8):
                    _t = threading.Thread(target=_pm_touch, args=(_pm_ids_seq,))
                    _t.start(); _t.join()
                """.trimIndent(),
            )
            assertEquals(
                8, pyInt("len(set(_pm_ids_seq))"),
                "the eight workers did not land on eight distinct ART threads",
            )
            assertEquals(
                0, awaitUnreclaimedPeers(0),
                "of eight finished Python workers, some ART peers were still unreclaimable " +
                    "${RELEASE_TIMEOUT_MS}ms later, so the detach is not armed on every attach",
            )
        }

    /**
     * The scale question a per-thread attach raises: what a *pool* of Python workers costs while
     * they are all alive at once.
     *
     * Peak simultaneous attachments are the same either way -- per-call attaching still has every
     * concurrently-upcalling thread attached at the same instant -- so what changes is how long
     * each one is resident, which is what makes "hundreds of Python workers" worth checking rather
     * than assuming. [CONCURRENT_WORKERS] threads are held alive together on a barrier, each with
     * its own attachment, and then all of them have to be released.
     */
    @Test
    fun manyConcurrentWorkersAreEachAttachedOnceAndAllReleased() =
        PythonTestFixture.withInterpreter {
            assertTrue(bindUpcallOrNull("probe.trackThread"))
            UpcallTarget.clearTracked()
            Python3.exec(
                """
                import threading

                _pm_lock = threading.Lock()
                _pm_ids_many = []
                _pm_gate = threading.Barrier($CONCURRENT_WORKERS)

                def _pm_hold():
                    ids = set()
                    for _ in range(8):
                        ids.add(_pm_bound(0))
                    _pm_gate.wait()          # every worker is attached at this instant
                    for _ in range(8):
                        ids.add(_pm_bound(0))
                    with _pm_lock:
                        _pm_ids_many.append((len(ids), ids.pop()))

                _pm_pool = [threading.Thread(target=_pm_hold) for _ in range($CONCURRENT_WORKERS)]
                for _t in _pm_pool: _t.start()
                for _t in _pm_pool: _t.join()
                """.trimIndent(),
            )

            assertEquals(
                CONCURRENT_WORKERS, pyInt("len(_pm_ids_many)"),
                "not every worker completed its upcalls",
            )
            assertEquals(
                1, pyInt("max(n for n, _ in _pm_ids_many)"),
                "a worker saw more than one ART thread across the barrier, so its attachment did " +
                    "not survive being contended by ${CONCURRENT_WORKERS - 1} others",
            )
            assertEquals(
                CONCURRENT_WORKERS, pyInt("len(set(i for _, i in _pm_ids_many))"),
                "two workers shared one ART thread",
            )

            assertEquals(
                0, awaitUnreclaimedPeers(0),
                "of $CONCURRENT_WORKERS finished Python workers, some ART peers were still " +
                    "unreclaimable ${RELEASE_TIMEOUT_MS}ms later, so a pool of workers accumulates " +
                    "attachments rather than giving them back",
            )
        }
}

/**
 * An exposed callable that reports the JVM thread it was executed on.
 *
 * Test-owned, and that is the point: nothing in production code needs a hook for this, because the
 * lambda *is* where the upcall lands. `CycleCollectionTest.TraverseThreadProbe` records the same
 * fact the same way for `tp_traverse`.
 *
 * Without it, "the worker's result came back" would only show the call completed, not that it was
 * served on a thread ART had never seen -- which is the whole claim `pmp_attach`'s
 * `AttachCurrentThreadAsDaemon` branch exists to support on this path.
 */
object ThreadProbeFragment : python.multiplatform.reflection.FunctionTableFragment {
    override val moduleName: String = "test_android_upcall_probe"

    @Volatile
    var lastThreadId: Long = -1L

    override fun entries(): List<python.multiplatform.reflection.ExposedCallable> = listOf(
        python.multiplatform.reflection.ExposedCallable(
            name = "probe.whichThread",
            arity = 1,
            paramTypes = listOf(python.multiplatform.reflection.TypeTag.INT),
            returnType = python.multiplatform.reflection.TypeTag.INT,
        ) {
            Thread.currentThread().id.also { lastThreadId = it }
        },
        /**
         * The same reading, plus a weak record of the peer the upcall landed on, so a later
         * collection can be asked whether that attachment is still held. Delegates to
         * [UpcallTarget] rather than keeping a second list, because the JNI probe for a thread
         * that never detaches has to reach the same one from C.
         */
        python.multiplatform.reflection.ExposedCallable(
            name = "probe.trackThread",
            arity = 1,
            paramTypes = listOf(python.multiplatform.reflection.TypeTag.INT),
            returnType = python.multiplatform.reflection.TypeTag.INT,
        ) {
            UpcallTarget.trackCurrentThread().also { lastThreadId = it }
        },
    )
}
