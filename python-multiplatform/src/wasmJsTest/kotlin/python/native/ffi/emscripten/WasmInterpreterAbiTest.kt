package python.native.ffi.emscripten

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture

/**
 * The interpreter under test is the one `tools/wasm/build-cpython.sh` produces: CPython matched to
 * PEP 783's `pyemscripten_2026_0`, so that a compiled PyPI wheel with that tag can load.
 *
 * A stock PEP 776 build differs in exactly the two places asserted here, and both fail silently
 * rather than loudly:
 *
 *  * **the unwinding ABI.** Without `-fwasm-exceptions -sSUPPORT_LONGJMP=wasm` at compile *and*
 *    link, `python.wasm` carries no tag section, and every `pyemscripten_2026_0` side module fails
 *    at load with `LinkError: ... "__cpp_exception": tag import requires a WebAssembly.Tag`. That is
 *    the one difference that actually gated wheel loading in `wasm-experiment/`.
 *  * **the platform tag claim.** `PYEMSCRIPTEN_PLATFORM_VERSION` does not exist in CPython 3.14.2.
 *    The build adds it, and must also add it to `sysconfig._ALWAYS_STR`: Python accepts
 *    underscores in integer literals, so without that `int('2026_0') == 20260` and `packaging`
 *    emits `pyemscripten_20260_wasm32`, a tag no wheel carries.
 *
 * Nothing else in this suite would notice either regression: the Stable ABI calls all link and run
 * the same on a stock build relinked with `wasmExports,wasmMemory`.
 */
class WasmInterpreterAbiTest {

    @Test
    fun theInterpreterExportsTheWasmExceptionTagsSideModulesImport() {
        PythonTestFixture.withInterpreter {
            assertTrue(
                exportsWasmTag("__cpp_exception"),
                "python.wasm exports no __cpp_exception tag: it was not built with " +
                    "-fwasm-exceptions, and no pyemscripten_2026_0 wheel can load into it",
            )
            assertTrue(
                exportsWasmTag("__c_longjmp"),
                "python.wasm exports no __c_longjmp tag: it was not built with -sSUPPORT_LONGJMP=wasm",
            )
        }
    }

    @Test
    fun theInterpreterClaimsThePyemscripten2026PlatformAsAString() {
        PythonTestFixture.withInterpreter {
            Python3.withPython {
                assertEquals("emscripten", evalToString("__import__('sys').platform"))
                val v = "__import__('sysconfig').get_config_var('PYEMSCRIPTEN_PLATFORM_VERSION')"
                assertEquals(
                    "str", evalToString("type($v).__name__"),
                    "PYEMSCRIPTEN_PLATFORM_VERSION must stay a str; an int means sysconfig._ALWAYS_STR " +
                        "was not patched and the tag reads pyemscripten_20260",
                )
                assertEquals("2026_0", evalToString(v))
            }
        }
    }
}
