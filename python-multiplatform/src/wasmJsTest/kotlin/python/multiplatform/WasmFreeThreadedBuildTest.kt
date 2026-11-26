package python.multiplatform

import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SPEC T-1 (issue #175): wasmJs embeds the free-threaded CPython 3.15t with pthreads, built by
 * `python-multiplatform/scripts/wasm/build-cpython.sh` (Decision 10 a1, leader 2026-11-26).
 *
 * Red before the change: `embedsFreeThreadedPython` was `false` in `wasmJsMain`, so `abiFlags` was `""`,
 * and the staged runtime was the 3.14.2 GIL build. (This replaces `WasmGilBuildTest`, which pinned the
 * GIL build until #175.)
 */
class WasmFreeThreadedBuildTest {

    @Test
    fun wasmReportsTheFreeThreadedAbi() {
        val v = Versions.currentVersion
        assertEquals("t", v.abiFlags, "wasmJs embeds CPython ${v.compactVersionString}t (#175)")
        assertEquals(v.compactVersionString + "t", v.taggedVersionString)
    }

    @Test
    fun theLoadedInterpreterIsReallyFreeThreaded() = PythonTestFixture.withInterpreter {
        assertEquals(
            "1",
            PythonTestFixture.eval("__import__('sysconfig').get_config_var('Py_GIL_DISABLED')").toString(),
            "the staged python.wasm is a GIL build; it must be the free-threaded one (#175)",
        )
        assertEquals(
            "False",
            PythonTestFixture.eval("__import__('sys')._is_gil_enabled()").toString(),
            "the GIL is enabled at run time; the free-threaded build must run without it (#175)",
        )
    }
}
