package python.native.ffi.emscripten

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.overhead.Benchmark

/**
 * What it costs CPython to call Kotlin, by route -- the measurement behind the choice the upcall
 * runtime made on this target (`@WasmExport` + `Table.set`, `cpython.mjs`'s `pmpRegisterUpcall`)
 * over the one the original design specified (`addFunction` around a JS closure).
 *
 * Formerly Tests E and F of the standalone `wasm-experiment/`. Test E timed both routes from inside
 * C with a hand-written `call_fp_n` loop in a 4 KB toy -- 3.1 against 10.9 ns -- which a real
 * interpreter has no equivalent of. Here the caller is Python, as it is in production, and the
 * callee is an ordinary `PyCFunction`, so every row also carries CPython's own call machinery and
 * `PY_CALL_TRAMPOLINE`'s JS fallback (see `WasmCallTrampolineTest`). That is the honest figure: it
 * is what an upcall costs, rather than what the crossing alone costs.
 *
 * Rows, all `f(i)` in the same Python loop:
 *
 *  * pure Python `def f(x): return x + 1` and the C builtin `abs` -- scale, nothing crosses;
 *  * the same Kotlin export, `pmp_test_bare`, put in CPython's table two ways.
 *
 * The full library upcall -- `pmp_invoke` through `UpcallTrampoline` with argument conversion -- is
 * `commonTest/.../UpcallBoundaryCostTest`'s, and is not repeated here.
 *
 * Asserted: both routes reach Kotlin and return what it built, and the library's route is not the
 * more expensive of the two -- the `addFunction` route is a strict superset of it (the same table
 * entry, called from a JS closure that `addFunction` wraps in a further wasm thunk).
 */
class WasmUpcallRouteOverheadTest {

    @AfterTest
    fun report() {
        Benchmark.printReport()
    }

    @Test
    fun theTableSetRouteTheLibraryUsesIsNoDearerThanAddFunctionAroundAJsClosure() {
        PythonTestFixture.withInterpreter {
            Python3.withPython {
                val direct = TestUpcalls.register("pmp_test_bare")
                assertTrue(direct > 0, "pmpRegisterUpcall(\"pmp_test_bare\") failed with code $direct")
                val viaClosure = addFunctionAroundTableEntry(mod, direct)
                assertTrue(viaClosure > 0 && viaClosure != direct, "addFunction returned $viaClosure")
                assertEquals(0, TestUpcalls.install("_pmp_up_direct", direct))
                assertEquals(0, TestUpcalls.install("_pmp_up_closure", viaClosure))

                val n = 1_000_000
                val before = TestUpcalls.entered
                assertEquals(
                    0,
                    runInMain(
                        "import time as _pmp_time\n" +
                            "def _pmp_py(x):\n" +
                            "    return x + 1\n" +
                            "def _pmp_bench(f, n):\n" +
                            "    t = _pmp_time.perf_counter_ns()\n" +
                            "    for i in range(n):\n" +
                            "        f(i)\n" +
                            "    return (_pmp_time.perf_counter_ns() - t) / n\n" +
                            "_pmp_fs = (_pmp_py, abs, _pmp_up_direct, _pmp_up_closure)\n" +
                            "for _f in _pmp_fs:\n" +
                            "    _pmp_bench(_f, 20000)\n" +
                            "_pmp_t = [_pmp_bench(_f, $n) for _f in _pmp_fs]\n" +
                            "_pmp_ok = (_pmp_up_direct(1), _pmp_up_closure(1)) == (0, 0)\n",
                    ),
                )
                try {
                    assertEquals("True", evalToString("_pmp_ok"), "both routes must return what Kotlin built")
                    assertEquals(
                        before + 2 * (20_000 + n) + 2, TestUpcalls.entered,
                        "every call through either route must have entered the Kotlin body",
                    )
                    val t = (0 until 4).map { evalToString("_pmp_t[$it]")!!.toDouble() }
                    Benchmark.record("upcall from Python: pure Python def (scale)", n, t[0])
                    Benchmark.record("upcall from Python: C builtin abs() (scale)", n, t[1])
                    Benchmark.record("upcall from Python: Kotlin via Table.set (library)", n, t[2])
                    Benchmark.record("upcall from Python: Kotlin via addFunction + JS closure", n, t[3])
                    assertTrue(
                        t[2] <= t[3],
                        "the Table.set route (${t[2]} ns) came out dearer than addFunction around a JS " +
                            "closure (${t[3]} ns), which is a strict superset of it. Either the " +
                            "measurement is broken or pmpRegisterUpcall no longer puts the raw export " +
                            "in the table.",
                    )
                } finally {
                    runInMain("del _pmp_time, _pmp_py, _pmp_bench, _pmp_fs, _pmp_t, _pmp_ok, _f, _pmp_up_direct, _pmp_up_closure")
                }
            }
        }
    }
}

/**
 * The route `docs/platforms/wasm-design.md` originally specified: a JS closure handed to Emscripten's
 * `addFunction`, which compiles a wasm thunk around it and returns a new table index. The closure
 * calls the very table entry the library's route uses, so the two differ by the JS hop and nothing
 * else.
 */
private fun addFunctionAroundTableEntry(module: JsAny, index: Int): Int =
    js("module.addFunction((s, a) => module.wasmTable.get(index)(s, a), 'iii')")
