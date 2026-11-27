package python.native.ffi.emscripten

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture

/**
 * A real compiled `pyemscripten_2026_0` wheel loads into the interpreter this library runs, and
 * Python code calls into it.
 *
 * `WasmInterpreterAbiTest` asserts the two properties of `python.wasm` that make this possible --
 * the exported wasm exception tags, and the platform tag claim. This is the claim itself: a wheel
 * built by another toolchain against that ABI is `dlopen`ed as an Emscripten side module, links
 * against `python.wasm`, and runs.
 *
 * The wheel is `wasmdemo-0.1.0-cp315-cp315t-pyemscripten_2026_0_wasm32.whl`, built by pypackpack's
 * Emscripten adapter (pypackpack #82, PR #180; handed over by toolchain 2026-11-27) with this
 * repository's Emscripten 5.0.3 against the 3.15t headers, `-fwasm-exceptions -sSUPPORT_LONGJMP=wasm
 * -pthread -fPIC -sSIDE_MODULE=1`. Its C module `wasmdemo.wmod` has `answer()` (returns 42) and
 * `unwind()`, which returns 7 through `setjmp`/`longjmp` and so imports `python.wasm`'s `__c_longjmp`
 * tag: a runtime without the pyemscripten_2026_0 unwinding ABI fails it at link time. It is C, so
 * `__cpp_exception` is covered by the second fixture, `wasmcpp` ([aCompiledCppWheelThrowsAndCatchesInsideTheInterpreter]).
 * This replaces the cp314 pydantic-core wheel the 3.14.2 runtime
 * loaded (Decision 10 (c) c1, leader 2026-11-26).
 *
 * The wheels are test fixtures checked in under `python-multiplatform/src/wasmJsTest/resources/wheels/`
 * (leader, 2026-11-27), whose README records how each was built; [PINNED_WHEELS] pins their sha256 and
 * [theCheckedInWheelsAreTheOnesTheReadmeDescribes] checks it. The test task extracts them next to the
 * test bundle (`-PwasmWheelsDir` overrides the directory). Without them this test skips, and under
 * `-PrequireWasmRuntime=true` it fails instead -- the same contract every other wasm prerequisite has.
 */
class WasmCompiledWheelTest {

    @Test
    fun aCompiledPyemscriptenWheelLoadsAndRunsInTheInterpreter() {
        val required = PMP_TEST_WHEELS_REQUIRED
            ?: fail(
                "the test bundle's cpython-config.mjs carries no PMP_TEST_WHEELS_REQUIRED. The " +
                    "KotlinJsTest doFirst in python-multiplatform/build.gradle.kts is what stages the " +
                    "wheels and writes that knob; without it this test could only ever skip.",
            )
        val site = PMP_TEST_SITE_PACKAGES?.toString()
        if (site == null) {
            val message = "no cp315t pyemscripten_2026_0 wheel in ${PMP_TEST_WHEELS_DIR?.toString()}: " +
                "the fixtures live in python-multiplatform/src/wasmJsTest/resources/wheels (pypackpack #82)."
            if (required.toBoolean()) {
                fail("$message -PrequireWasmRuntime=true was passed, so this is a failure rather than a skip.")
            }
            println("SKIPPING WasmCompiledWheelTest: $message")
            return
        }
        PythonTestFixture.withInterpreter {
            Python3.withPython {
                val status = runInMain(
                    "import sys as _pmp_sys\n" +
                        "_pmp_sys.path.insert(0, ${pyStr(site)})\n" +
                        "try:\n" +
                        "    import wasmdemo.wmod as _pmp_m\n" +
                        "    _pmp_wheel = [\n" +
                        "        _pmp_m.__file__.rsplit('/', 1)[-1],\n" +
                        "        repr(_pmp_m.answer()),\n" +
                        "        repr(_pmp_m.unwind()),\n" +
                        "        repr(_pmp_sys._is_gil_enabled()),\n" +
                        "    ]\n" +
                        "except BaseException as e:\n" +
                        "    _pmp_wheel = ['FAILED ' + type(e).__name__ + ': ' + str(e)]\n" +
                        "finally:\n" +
                        "    _pmp_sys.path.remove(${pyStr(site)})\n",
                )
                assertEquals(0, status)
                val result = evalToString("' | '.join(_pmp_wheel)")
                runInMain("del _pmp_wheel, _pmp_sys")
                assertEquals(
                    "wmod.cpython-315t-wasm32-emscripten.so | 42 | 7 | False",
                    result,
                    "importing and calling the compiled wasmdemo wheel failed. A LinkError naming " +
                        "__c_longjmp means python.wasm lacks the pyemscripten_2026_0 unwinding ABI " +
                        "(see WasmInterpreterAbiTest); 'True' at the end means the import re-enabled the GIL.",
                )
            }
        }
    }

    /**
     * The C++ fixture: `wasmcpp.wcpp.catch_it()` throws `std::runtime_error("boom 3")` inside the
     * side module and returns the message it caught. That imports `python.wasm`'s `__cpp_exception`
     * tag and libc++abi's `__cxa_throw` / `__cxa_allocate_exception`, which the C fixture above never
     * touches: a runtime built without `-fwasm-exceptions`, or one whose tag the side module cannot
     * link against, fails here (a LinkError at import, or an abort at the throw) while `wasmdemo`
     * still passes. Built by toolchain with the same adapter and flags (pypackpack 2f3ddd0, 2026-11-27).
     */
    @Test
    fun aCompiledCppWheelThrowsAndCatchesInsideTheInterpreter() {
        val site = PMP_TEST_SITE_PACKAGES?.toString()
        if (site == null) {
            if (PMP_TEST_WHEELS_REQUIRED?.toBoolean() == true) {
                fail("no wheels were staged although -PrequireWasmRuntime=true was passed")
            }
            println("SKIPPING aCompiledCppWheelThrowsAndCatchesInsideTheInterpreter: no wheels staged")
            return
        }
        PythonTestFixture.withInterpreter {
            Python3.withPython {
                val status = runInMain(
                    "import sys as _pmp_sys\n" +
                        "_pmp_sys.path.insert(0, ${pyStr(site)})\n" +
                        "try:\n" +
                        "    import wasmcpp.wcpp as _pmp_m\n" +
                        "    _pmp_wheel = [\n" +
                        "        _pmp_m.__file__.rsplit('/', 1)[-1],\n" +
                        "        _pmp_m.catch_it(),\n" +
                        "        repr(_pmp_sys._is_gil_enabled()),\n" +
                        "    ]\n" +
                        "except BaseException as e:\n" +
                        "    _pmp_wheel = ['FAILED ' + type(e).__name__ + ': ' + str(e)]\n" +
                        "finally:\n" +
                        "    _pmp_sys.path.remove(${pyStr(site)})\n",
                )
                assertEquals(0, status)
                val result = evalToString("' | '.join(_pmp_wheel)")
                runInMain("del _pmp_wheel, _pmp_sys")
                assertEquals(
                    "wcpp.cpython-315t-wasm32-emscripten.so | boom 3 | False",
                    result,
                    "importing the compiled wasmcpp wheel or catching its C++ exception failed. A LinkError " +
                        "naming __cpp_exception means python.wasm does not export the C++ exception tag " +
                        "(see WasmInterpreterAbiTest).",
                )
            }
        }
    }

    /**
     * The fixtures are what their README says they are: every pinned wheel is present with its
     * sha256, and nothing unpinned sits beside them. A rebuilt wheel (new emsdk or ABI) changes the
     * hash, so this fails until the README and [PINNED_WHEELS] are updated with the new provenance.
     */
    @Test
    fun theCheckedInWheelsAreTheOnesTheReadmeDescribes() {
        val dir = PMP_TEST_WHEELS_DIR?.toString() ?: fail("the test bundle's cpython-config.mjs carries no PMP_TEST_WHEELS_DIR")
        PythonTestFixture.withInterpreter {
            Python3.withPython {
                assertEquals(
                    0,
                    runInMain(
                        "import hashlib as _pmp_h, os as _pmp_os\n" +
                            "_pmp_d = ${pyStr(dir)}\n" +
                            "_pmp_found = sorted(n + ' ' + _pmp_h.sha256(open(_pmp_os.path.join(_pmp_d, n), 'rb').read()).hexdigest()\n" +
                            "    for n in _pmp_os.listdir(_pmp_d) if n.endswith('.whl'))\n",
                    ),
                )
                val found = evalToString("'\\n'.join(_pmp_found)")
                runInMain("del _pmp_h, _pmp_os, _pmp_d, _pmp_found")
                assertEquals(
                    PINNED_WHEELS.entries.sortedBy { it.key }.joinToString("\n") { "${it.key} ${it.value}" },
                    found,
                    "the wheels in $dir are not the pinned fixtures; update resources/wheels/README.md and " +
                        "PINNED_WHEELS together when a fixture is rebuilt",
                )
            }
        }
    }

    private fun pyStr(value: String): String =
        "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

    private companion object {
        /** File name to sha256; the same values as `resources/wheels/README.md`. */
        val PINNED_WHEELS = mapOf(
            "wasmdemo-0.1.0-cp315-cp315t-pyemscripten_2026_0_wasm32.whl" to
                "df57a28e3a61f4eb8bc5fe84e4a09bdc76e8e720bd6c348345c3fb86c772298a",
            "wasmcpp-0.1.0-cp315-cp315t-pyemscripten_2026_0_wasm32.whl" to
                "6a41eb2a1be0421fcf35c862aefeacb11bbdbfc80f34ac2881d739df9a3c96ef",
        )
    }
}
