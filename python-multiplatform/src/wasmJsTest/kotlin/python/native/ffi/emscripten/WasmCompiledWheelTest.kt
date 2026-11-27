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
 * tag: a runtime without the pyemscripten_2026_0 unwinding ABI fails it at link time. It is C, so it
 * does not exercise `__cpp_exception`. This replaces the cp314 pydantic-core wheel the 3.14.2 runtime
 * loaded (Decision 10 (c) c1, leader 2026-11-26).
 *
 * The wheels are not in the repository. `python-multiplatform/scripts/wasm/build-cpython.sh wheels`
 * downloads the pinned ones with their sha256 into `.caches/wasm-wheels` (or `-PwasmWheelsDir`), and
 * the test task extracts them next to the test bundle. Without them this test skips with that
 * instruction, and under `-PrequireWasmRuntime=true` it fails instead -- the same contract every
 * other wasm prerequisite has.
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
                "fetch the pinned wasmdemo wheel with python-multiplatform/scripts/wasm/build-cpython.sh wheels " +
                "(or point -PwasmWheelsDir at a directory holding it; pypackpack #82)."
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

    private fun pyStr(value: String): String =
        "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
}
