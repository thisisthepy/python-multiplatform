package python.native.ffi

import android.os.Build
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.upcall.TrampolineFragment
import python.multiplatform.ffi.upcall.UpcallTrampoline
import python.multiplatform.overhead.Benchmark
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What an Android upcall actually costs, and whether the attach it needs is paid once per thread
 * or once per call.
 *
 * The commit that landed the Android boundary named two costs and measured neither:
 *
 *  1. one JNI upcall per call, and
 *  2. an `AttachCurrentThreadAsDaemon`/`DetachCurrentThread` pair whenever Python calls from a
 *     thread ART has never seen -- which every `threading.Thread` is, because CPython creates
 *     bare pthreads.
 *
 * The second is the one that decides whether anything needs changing, and it cannot be settled by
 * reading `jni_onload.def`'s `pmp_attach` alone: that function only says *an* attach happens when
 * `GetEnv` fails. Whether the attachment survives to the next call is a property of the caller
 * *and* of ART, so it is observed here rather than argued.
 *
 * ### What it cost when it was per call, and what changed
 *
 * The first run of this test found the attach was paid on **every** call, and priced it:
 *
 * | | API 26 | API 36 |
 * |---|---|---|
 * | upcall, instrumentation thread | 1695 ns | 6693 ns |
 * | upcall, Python worker | 63293 ns | 31219 ns |
 * | attach, i.e. the difference | **61598 ns** | **24526 ns** |
 *
 * `pmp_attach` now keeps the attachment for the life of the thread and releases it from a
 * `pthread_key_create` destructor, so the worker figure should collapse onto the instrumentation
 * one. `UpcallThreadAttachTest` holds the correctness half of that change; this holds the price.
 *
 * ### How the attach is counted rather than inferred from a stopwatch
 *
 * `AttachCurrentThreadAsDaemon` builds a fresh `java.lang.Thread` for the pthread it attaches, and
 * `Thread.getId()` comes from a monotonic counter that is never reused. So the identity of the
 * thread the *Kotlin* side lands on is a direct readout of the attach: N calls from one Python
 * worker report N distinct ids if the attach is per call, and one id if it is per thread. That is
 * `probe.whichThread` -- the same fragment `UpcallEntryTest` uses to prove the attach branch is
 * taken at all -- read back as a Python `set`.
 *
 * A timing-only answer would have been ambiguous on an emulator; this one is not.
 *
 * ### Why everything is in one test method
 *
 * The downcall figures are here rather than borrowed from `overhead/BenchmarkTest`, because a
 * ratio between two *different runs* on an emulator is not a measurement. `commonMain/README.md`'s
 * companion rule applies to warmup too: an unwarmed first row once made a subset of the work look
 * cheaper than the whole of it in this repo, so every loop below is preceded by a warmup of the
 * same shape, and the Python-side loop overhead is measured and reported alongside so the boundary
 * cost can be read net of it.
 *
 * Results are printed rather than asserted on. A wall-clock threshold on an emulator would be a
 * flake generator; what *is* asserted is the structural fact (how many attaches N calls cost),
 * which is deterministic.
 */
class UpcallOverheadTest {

    private companion object {
        /** Calls per timed loop. Large enough to swamp `perf_counter_ns`, small enough for API 26. */
        const val N = 10_000

        /**
         * Same shape as the measured loop, run first.
         *
         * ### Why 100 000 and not the 3 000 this used to be
         *
         * At 3 000 this test reported a same-process warm-up state rather than a boundary cost.
         * The evidence is in this file's own history: the API 36 upcall row read `2982-5086` ns
         * and later `1032-1229` ns with **the same warmup and the same emulator** -- the only
         * thing that differed was how many tests ran before it. A number that moves by 4x on what
         * ran first is not measuring the boundary.
         *
         * `UpcallBoundaryCostTest` diagnosed the mechanism and `7e9c6b8c` fixed it there: it is
         * host JIT tier-up, not CPython-side state. The discriminator is that the rows with no
         * boundary in them -- the empty Python loop and the pure-Python callee -- are flat from
         * the first repetition, while only the rows crossing into host code move. On ART that
         * host is a JIT too, so the same fix applies here.
         *
         * ART is the tightest of the hosts swept in `ec53b6e5`: API 36 only reached its plateau
         * at roughly 90 000-100 000 calls, where desktop needed ~70 000 and wasm ~70 000. So
         * 100 000 is ART's requirement with little margin to spare, and that is why this constant
         * is 100 000 rather than something smaller that would do elsewhere.
         *
         * The cost is bounded by what it warms: three warmup loops of 100 000 calls, at the
         * ~1 us per call this test reports for the dearest of them, is a fraction of a second.
         *
         * This deliberately does **not** warm the worker thread's upcall path -- see
         * `_pm_worker_body`, whose first upcall is a measurement (`upcall_worker_first`), not
         * waste. The worker still benefits from this warmup, because JIT tier-up is a property of
         * the process rather than of the thread, which is what leaves the per-thread attach as
         * the only thing that row still has to pay.
         */
        const val WARMUP = 100_000

        /** How many calls the attach is counted over. */
        const val PROBE_CALLS = 64

        const val DOWNCALL_REFCOUNT = "Py_IncRef + Py_DecRef (2 JNI downcalls, a GIL scope each)"
        const val DOWNCALL_CALL = "PyObject_CallObject on a Python def (downcall, same shape)"
        const val TRAMPOLINE_ONLY = "UpcallTrampoline.invoke from Kotlin (no C shim, no JNI)"
    }

    @BeforeTest
    fun install() {
        PythonOnDevice.ensureInitialised()
        UpcallTable.install(listOf(TrampolineFragment, ThreadProbeFragment))
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
        if (PythonTestFixture.available) Python3.exec("_pm_bench = None")
    }

    private fun py(expression: String): String = PythonTestFixture.eval(expression).toString()

    private fun pyDouble(expression: String): Double = py(expression).toDouble()

    /**
     * Publishes the bootstrap and binds the two entries this measures, exactly as
     * `UpcallEntryTest` does: a name resolved once, an integer carried inside the callable's
     * `self` from then on. Binding is deliberately outside every timed loop -- it is a cold path.
     */
    private fun installBridge() {
        val globals: PyObject = PythonTestFixture.mainGlobals()
        assertTrue(UpcallEntry.publish(globals.pointer), "the upcall bootstrap could not be published")
        Python3.exec(
            """
            import threading, time

            _pm_add = _pm_bind(_pm_resolve('trampoline.add'))
            _pm_probe = _pm_bind(_pm_resolve('probe.whichThread'))

            def _pm_py_add(a, b):
                '''A pure-Python callee of the same shape: the floor a Kotlin one is measured against.'''
                return a + b

            def _pm_time_calls(fn, n):
                t0 = time.perf_counter_ns()
                for _ in range(n):
                    fn(3, 4)
                return (time.perf_counter_ns() - t0) / n

            def _pm_time_loop(n):
                '''The same loop with no call in it, so the call cost can be read net of Python's own.'''
                t0 = time.perf_counter_ns()
                for _ in range(n):
                    pass
                return (time.perf_counter_ns() - t0) / n

            def _pm_distinct_threads(calls):
                '''One entry per JVM thread the upcalls landed on. 1 == the attach was amortised.'''
                seen = set()
                for _ in range(calls):
                    seen.add(_pm_probe(0))
                return len(seen)

            _pm_bench = {}
            """.trimIndent(),
        )
    }

    /**
     * The three paths the design question is about, plus the downcall figures they are compared
     * against, all inside one interpreter and one warmup regime.
     */
    @Test
    fun anUpcallFromAPythonWorkerThreadPaysAnAttachPerCallRatherThanPerThread() =
        PythonTestFixture.withInterpreter {
            installBridge()

            // ---- Path 1: the instrumentation thread, which ART already knows ----------------
            Python3.exec(
                """
                _pm_time_calls(_pm_add, $WARMUP)
                _pm_time_calls(_pm_py_add, $WARMUP)
                _pm_time_loop($WARMUP)

                _pm_bench['loop_main'] = _pm_time_loop($N)
                _pm_bench['py_main'] = _pm_time_calls(_pm_py_add, $N)
                _pm_bench['upcall_main'] = _pm_time_calls(_pm_add, $N)
                _pm_bench['threads_main'] = _pm_distinct_threads($PROBE_CALLS)
                """.trimIndent(),
            )

            // ---- Paths 2 and 3: a threading.Thread, i.e. a pthread ART has never seen -------
            // The worker warms its own Python-side state on the pure-Python callee first, so the
            // first *upcall* it makes is the first time anything crosses into ART on this thread
            // and nothing else is cold at the same moment. If the attach were per thread, that one
            // call would carry it and `upcall_worker` would fall back to `upcall_main`.
            Python3.exec(
                """
                def _pm_worker_body(n):
                    _pm_time_calls(_pm_py_add, n)
                    _pm_bench['loop_worker'] = _pm_time_loop(n)
                    _pm_bench['py_worker'] = _pm_time_calls(_pm_py_add, n)

                    t0 = time.perf_counter_ns()
                    _pm_add(3, 4)
                    _pm_bench['upcall_worker_first'] = float(time.perf_counter_ns() - t0)

                    _pm_bench['upcall_worker'] = _pm_time_calls(_pm_add, n)
                    _pm_bench['threads_worker'] = _pm_distinct_threads($PROBE_CALLS)

                _pm_t = threading.Thread(target=_pm_worker_body, args=($N,))
                _pm_t.start()
                _pm_t.join()
                """.trimIndent(),
            )

            val loopMain = pyDouble("_pm_bench['loop_main']")
            val pyMain = pyDouble("_pm_bench['py_main']")
            val upcallMain = pyDouble("_pm_bench['upcall_main']")
            val threadsMain = py("_pm_bench['threads_main']").toInt()

            val loopWorker = pyDouble("_pm_bench['loop_worker']")
            val pyWorker = pyDouble("_pm_bench['py_worker']")
            val upcallWorkerFirst = pyDouble("_pm_bench['upcall_worker_first']")
            val upcallWorker = pyDouble("_pm_bench['upcall_worker']")
            val threadsWorker = py("_pm_bench['threads_worker']").toInt()

            // ---- The comparison basis: downcalls, same interpreter, same warmup regime -------
            val downcalls = measureDowncalls()

            report(
                loopMain, pyMain, upcallMain, threadsMain,
                loopWorker, pyWorker, upcallWorkerFirst, upcallWorker, threadsWorker,
                downcalls,
            )

            // ---- What is actually asserted --------------------------------------------------
            assertEquals(
                1, threadsMain,
                "the instrumentation thread is one ART thread throughout; $PROBE_CALLS upcalls " +
                    "reporting $threadsMain distinct ids would mean this probe measures something else",
            )
            assertEquals(
                1, threadsWorker,
                "every upcall from one Python worker thread should arrive on the same ART thread: " +
                    "the attach is paid once when the worker first crosses into ART and released " +
                    "by pmp_thread_exit_detach when the pthread dies. $threadsWorker distinct ids " +
                    "over $PROBE_CALLS calls means it is back to one attach per call",
            )
            assertTrue(
                upcallWorker < upcallWorkerFirst,
                "the steady-state upcall from a Python worker is not cheaper than that thread's " +
                    "first one, so nothing is being amortised: steady ${upcallWorker.ns()} vs " +
                    "first ${upcallWorkerFirst.ns()}",
            )
        }

    /**
     * The downcall side of the ratio, measured here so it shares this run's device, interpreter
     * and thermal state.
     *
     * Two of the four are the symmetric counterparts of the upcall rather than raw FFI: calling a
     * Python function from Kotlin is what a Kotlin→Python call of the same shape costs, and
     * calling [UpcallTrampoline] directly is the same upcall *minus* the C shim and the JNI
     * boundary -- so the difference between it and the Python-driven figure is what the boundary
     * itself adds, with the marshalling held constant.
     */
    private fun measureDowncalls(): Map<String, Double> {
        val pyAdd = PythonTestFixture.eval("_pm_py_add")
        val handle = UpcallTable.resolve("trampoline.add").raw

        // (3, 4), built once. PyTuple_SetItem steals, so the two PyLongs are the tuple's.
        val args = Python3.withPython {
            val tuple = PyTuple_New(2L) ?: error("PyTuple_New failed")
            PyTuple_SetItem(tuple, 0L, PyLong_FromLongLong(3L) ?: error("PyLong_FromLongLong failed"))
            PyTuple_SetItem(tuple, 1L, PyLong_FromLongLong(4L) ?: error("PyLong_FromLongLong failed"))
            tuple
        }

        val results = LinkedHashMap<String, Double>()
        // [WARMUP], not `N / 4`. These rows cross the same JNI boundary as the Python-driven ones
        // and warm on the same curve, and 2 500 was far down it -- `UpcallBoundaryCostTest` swept
        // its equivalent 40 times and read 653, 266, 266, 161 before it settled at ~134-141, i.e.
        // three tiers early at 2 500. The Python-driven and Kotlin-driven halves have to be warmed
        // alike, or the ratio this test exists to report is a ratio of two compilation states.
        fun record(name: String, block: () -> Unit) {
            results[name] = Benchmark.measure(warmupIterations = WARMUP, iterations = N, block = block)
        }

        try {
            // Balanced within one iteration, so the count is back where it started however many
            // times the loop runs -- including the warmup.
            val scratch = Python3.withPython { PyLong_FromLongLong(42L) } ?: error("PyLong_FromLongLong failed")
            try {
                record(DOWNCALL_REFCOUNT) {
                    Python3.withPython { Py_IncRef(scratch) }
                    Python3.withPython { Py_DecRef(scratch) }
                }
            } finally {
                Python3.withPython { Py_DecRef(scratch) }
            }

            record(DOWNCALL_CALL) {
                val r = Python3.withPython { PyObject_CallObject(pyAdd.pointer, args) }
                if (r != null) Python3.withPython { Py_DecRef(r) }
            }

            record(TRAMPOLINE_ONLY) {
                val r = UpcallTrampoline.invoke(handle, args.toRawValue())
                val p = r.toNativePointer()
                if (p != null) Python3.withPython { Py_DecRef(p) }
            }
        } finally {
            Python3.withPython { Py_DecRef(args) }
            pyAdd.close()
        }
        return results
    }

    /**
     * The sign is handled separately because the difference this reports can now be negative: with
     * the attach amortised, a Python worker's steady-state upcall is no longer reliably more
     * expensive than the instrumentation thread's, and formatting the two halves independently
     * printed that as "-1999.-50 ns".
     */
    private fun Double.ns(): String = "${sign(this)}${fmt(kotlin.math.abs(this))} ns"

    private fun sign(v: Double): String = if (v < 0) "-" else ""

    private fun report(
        loopMain: Double, pyMain: Double, upcallMain: Double, threadsMain: Int,
        loopWorker: Double, pyWorker: Double, upcallWorkerFirst: Double, upcallWorker: Double,
        threadsWorker: Int,
        downcalls: Map<String, Double>,
    ) {
        val downcallBasis = (downcalls[DOWNCALL_CALL] ?: 1.0).coerceAtLeast(1.0)
        val trampolineOnly = (downcalls[TRAMPOLINE_ONLY] ?: 1.0).coerceAtLeast(1.0)

        val lines = buildList {
            add("")
            add("--- Android upcall overhead (API ${Build.VERSION.SDK_INT}, ${Build.SUPPORTED_ABIS.firstOrNull()}) ---")
            add("iterations per loop: $N, warmup: $WARMUP")
            add("")
            add("Python-driven, instrumentation thread (ART already knows it)")
            add("  empty Python loop                     ${loopMain.ns()}")
            add("  pure-Python callee                    ${pyMain.ns()}")
            add("  upcall to Kotlin                      ${upcallMain.ns()}")
            add("  upcall net of loop                    ${(upcallMain - loopMain).ns()}")
            add("  distinct ART threads over $PROBE_CALLS calls  $threadsMain")
            add("")
            add("Python-driven, threading.Thread (a pthread ART has never seen)")
            add("  empty Python loop                     ${loopWorker.ns()}")
            add("  pure-Python callee                    ${pyWorker.ns()}")
            add("  first upcall on the thread            ${upcallWorkerFirst.ns()}")
            add("  upcall to Kotlin (steady state)       ${upcallWorker.ns()}")
            add("  upcall net of loop                    ${(upcallWorker - loopWorker).ns()}")
            add("  distinct ART threads over $PROBE_CALLS calls  $threadsWorker")
            add("")
            add("Attach")
            add("  worker minus instrumentation thread   ${(upcallWorker - upcallMain).ns()}")
            add("  amortised across calls?               ${if (threadsWorker == 1) "yes" else "no -- one attach/detach per call"}")
            add("")
            add("Comparison basis, same run")
            // See UpcallBoundaryCostTest for why the space is load-bearing: `padEnd` is a floor, and
            // the labels longer than it print glued to their value, which loses the row to any
            // whitespace-based reader.
            for ((name, ns) in downcalls) add("  ${name.padEnd(54)} ${ns.ns()}")
            add("")
            add("Ratios")
            add("  upcall (instrumentation thread) / downcall of same shape   ${fmt(upcallMain / downcallBasis)}x")
            add("  upcall (worker thread)          / downcall of same shape   ${fmt(upcallWorker / downcallBasis)}x")
            add("  upcall (instrumentation thread) / trampoline alone         ${fmt(upcallMain / trampolineOnly)}x")
            add("  upcall (worker thread)          / trampoline alone         ${fmt(upcallWorker / trampolineOnly)}x")
            add("-".repeat(72))
            add("")
        }
        // One println per line: logcat truncates a long single write, and these have to survive
        // into the XML the run is read from.
        for (line in lines) println(line)
    }

    private fun fmt(v: Double): String {
        val whole = v.toLong()
        val hundredths = ((v - whole) * 100).toLong()
        return "$whole.${hundredths.toString().padStart(2, '0')}"
    }
}
