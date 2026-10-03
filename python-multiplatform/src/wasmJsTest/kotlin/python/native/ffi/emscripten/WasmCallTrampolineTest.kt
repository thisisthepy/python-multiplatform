package python.native.ffi.emscripten

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture

/**
 * Which of CPython's two `PY_CALL_TRAMPOLINE` implementations is live, and that the library's upcall
 * export is callable through either.
 *
 * Every `PyMethodDef` call on this build goes through `_PyEM_TrampolineCall`
 * (`Include/internal/pycore_emscripten_trampoline.h`) -- a C extension's as much as Kotlin's. There
 * are two implementations of it:
 *
 *  * **the wasm one** (`Python/emscripten_trampoline_inner.c`, a separate wasm module CPython
 *    instantiates at start-up). It `ref.test`s the table entry against four signatures, `(i32)` to
 *    `(i32, i32, i32)` all `-> i32`, and raises `SystemError("Handler takes too many arguments")`
 *    when none matches. No JS frame.
 *  * **the JS fallback**, `wasmTable.get(func)(arg1, arg2, arg3)` -- which calls *anything*, padding
 *    a missing argument with `undefined`, i.e. `0`. One JS frame per call.
 *
 * Upstream intends the wasm one. On this toolchain pair it never installs: the `EM_JS` initialiser
 * that swaps it in needs `wasmTable`/`wasmMemory`, which in a `-sMAIN_MODULE` build are *exports*
 * of `python.wasm` and do not exist until after instantiation; the resulting `LinkError` is
 * swallowed by a bare `catch (e) {}`. Found by the former `wasm-experiment/` (Test F), which was
 * the only thing that could tell the two apart -- nothing else observable differs.
 *
 * What the library relies on, and what this pins:
 *
 *  1. **`pmp_invoke` has an arity the wasm trampoline accepts.** It is the one function pointer
 *     every `UpcallEntry` `PyMethodDef` holds. Today the JS fallback would call it whatever its
 *     arity, so a wrong one would go unnoticed -- until the upstream defect is fixed, at which point
 *     every upcall would start raising `SystemError`. This half must keep passing either way.
 *  2. **The JS fallback is what is live today.** `src/wasmJsMain/README.md` prices every
 *     `PyMethodDef` call at one JS frame (14 ns over `abs()`) and recommends `PyType_FromSpec`
 *     slots over `PyMethodDef` entries for that reason. If CPython fixes the defect, this half
 *     goes red -- and that is the signal to re-measure and rewrite those sections, not to delete
 *     the assertion.
 */
class WasmCallTrampolineTest {

    @Test
    fun theLibrarysUpcallExportHasAnArityTheWasmTrampolineAccepts() {
        PythonTestFixture.withInterpreter {
            Python3.withPython {
                // The same registration `UpcallEntry` performs. It appends one table entry; the
                // index is a valid function pointer to the identical funcref.
                val index = TestUpcalls.register("pmp_invoke")
                assertTrue(index > 0, "pmpRegisterUpcall(\"pmp_invoke\") failed with code $index")
                val arity = tableEntryArity(mod, index)
                assertTrue(
                    arity in 1..3,
                    "pmp_invoke takes $arity parameters. CPython's wasm trampoline accepts only " +
                        "(i32) .. (i32, i32, i32) -> i32; today's JS fallback hides a mismatch, and " +
                        "the day upstream fixes PY_CALL_TRAMPOLINE every upcall would raise SystemError.",
                )
                assertEquals(2, arity, "pmp_invoke is PyCFunction's (self, args) -- METH_VARARGS")
            }
        }
    }

    @Test
    fun aFourArgumentHandlerIsCalledAnywayBecauseTheJsFallbackTrampolineIsLive() {
        PythonTestFixture.withInterpreter {
            Python3.withPython {
                val index = TestUpcalls.register("pmp_test_arity4")
                assertTrue(index > 0, "pmpRegisterUpcall(\"pmp_test_arity4\") failed with code $index")
                assertEquals(4, tableEntryArity(mod, index), "the discriminator must really take four")
                assertEquals(0, TestUpcalls.install("_pmp_arity4", index))

                val before = TestUpcalls.entered
                assertEquals(
                    0,
                    runInMain(
                        "try:\n" +
                            "    _pmp_r4 = 'returned ' + repr(_pmp_arity4(1))\n" +
                            "except BaseException as e:\n" +
                            "    _pmp_r4 = type(e).__name__ + ': ' + str(e)\n",
                    ),
                )
                val outcome = evalToString("_pmp_r4")
                runInMain("del _pmp_r4, _pmp_arity4")

                if (outcome != null && "Handler takes too many arguments" in outcome) {
                    throw AssertionError(
                        "CPython's WASM trampoline is live (_pmp_arity4(1) -> $outcome). The upstream " +
                            "PY_CALL_TRAMPOLINE defect this target was measured under is fixed in this " +
                            "build: PyMethodDef calls no longer cross a JS frame. Re-measure and rewrite " +
                            "src/wasmJsMain/README.md (\"Prefer slots CPython invokes directly\") and " +
                            "docs/platforms/wasm-design.md, then flip this assertion.",
                    )
                }
                assertEquals("returned 4", outcome, "a four-parameter handler was not reached the way the JS fallback reaches it")
                assertEquals(before + 1, TestUpcalls.entered, "the Kotlin body ran exactly once")
                // `wasmTable.get(func)(self, args, NULL)`: self is the PyCFunction's (none was
                // bound), the tuple is real, kwargs is NULL, and the fourth was never passed.
                assertEquals(0, TestUpcalls.lastArity4[0], "self")
                assertTrue(TestUpcalls.lastArity4[1] != 0, "args must be the argument tuple")
                assertEquals(0, TestUpcalls.lastArity4[2], "kwargs is NULL for METH_VARARGS")
                assertEquals(0, TestUpcalls.lastArity4[3], "the JS fallback passes three arguments; the fourth reads 0")
            }
        }
    }
}

/** `length` of a wasm exported function is its parameter count. */
private fun tableEntryArity(module: JsAny, index: Int): Int = js("module.wasmTable.get(index).length")
