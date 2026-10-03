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
 * built by someone else's toolchain against PEP 783's ABI (pydantic-core 2.48.0, Rust/PyO3, from
 * PyPI) is `dlopen`ed as an Emscripten side module, links against `python.wasm`, and runs.
 *
 * Measured in the former `wasm-experiment/` (Test G) through CPython's own `python.sh`, with no
 * Kotlin in the process. Against a stock PEP 776 build (`python-multiplatform/scripts/wasm/build-cpython.sh stock`) the same
 * import fails at load:
 *
 *     LinkError: Import #204 "env" "__cpp_exception": tag import requires a WebAssembly.Tag
 *
 * The wheels are not in the repository. `python-multiplatform/scripts/wasm/build-cpython.sh wheels` downloads them by URL
 * with pinned sha256 into `.caches/wasm-wheels`, and the test task extracts them next to the test
 * bundle. Without them this test skips with that instruction, and under `-PrequireWasmRuntime=true`
 * it fails instead -- the same contract every other wasm prerequisite has.
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
            val message = "no pyemscripten_2026_0 wheels found in ${PMP_TEST_WHEELS_DIR?.toString()}. " +
                "Run python-multiplatform/scripts/wasm/build-cpython.sh wheels -- it downloads the pinned pydantic-core and " +
                "typing-extensions wheels there -- and re-run this task."
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
                        "    import pydantic_core as _pmp_pc\n" +
                        "    from pydantic_core import SchemaValidator as _pmp_SV, core_schema as _pmp_cs\n" +
                        "    _pmp_v = _pmp_SV(_pmp_cs.int_schema())\n" +
                        "    _pmp_wheel = [\n" +
                        "        _pmp_pc.__version__,\n" +
                        "        _pmp_pc._pydantic_core.__file__.rsplit('/', 1)[-1],\n" +
                        "        repr(_pmp_v.validate_python('42')),\n" +
                        "    ]\n" +
                        "    try:\n" +
                        "        _pmp_v.validate_python('not an int')\n" +
                        "        _pmp_wheel.append('no exception')\n" +
                        "    except _pmp_pc.ValidationError as e:\n" +
                        "        _pmp_wheel.append(type(e).__name__)\n" +
                        "except BaseException as e:\n" +
                        "    _pmp_wheel = ['FAILED ' + type(e).__name__ + ': ' + str(e)]\n" +
                        "finally:\n" +
                        "    _pmp_sys.path.remove(${pyStr(site)})\n",
                )
                assertEquals(0, status)
                val result = evalToString("' | '.join(_pmp_wheel)")
                runInMain("del _pmp_wheel, _pmp_sys")
                assertEquals(
                    "2.48.0 | _pydantic_core.cpython-314-wasm32-emscripten.so | 42 | ValidationError",
                    result,
                    "importing and calling the compiled pydantic-core wheel failed. A LinkError naming " +
                        "__cpp_exception or __c_longjmp means python.wasm lacks the pyemscripten_2026_0 " +
                        "unwinding ABI (see WasmInterpreterAbiTest).",
                )
            }
        }
    }

    private fun pyStr(value: String): String =
        "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
}
