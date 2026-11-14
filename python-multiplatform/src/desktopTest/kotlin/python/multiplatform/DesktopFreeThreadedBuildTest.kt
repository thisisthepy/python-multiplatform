package python.multiplatform

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SPEC T-1 (issue #158): desktop embeds the free-threaded CPython 3.15t, and only that build.
 *
 * The maintainer's decision on #158 (2026-10-04): "Free-threaded is the default and the only build.
 * The GIL build is not kept as an option. Baseline is CPython 3.15t." Desktop moved first (leader,
 * 2026-11-13); Android and iOS follow under #328.
 *
 * Red before the change: the desktop build was GIL unless `-PpythonFreeThreaded=true` was passed,
 * so `abiFlags` was `""`, the tagged version was `3.15`, the interpreter reported
 * `Py_GIL_DISABLED = 0` and `autoDrainInterval` defaulted to 0. Each assertion below fails on that
 * build with its own message.
 *
 * What this cannot find: what Android, androidNative, iOS and wasm embed. Their literal values are
 * pinned in their own test source sets (`AndroidFreeThreadedBuildTest`,
 * `AndroidNativeFreeThreadedBuildTest`, `IosFreeThreadedBuildTest`, `WasmGilBuildTest`).
 */
class DesktopFreeThreadedBuildTest {

    @Test
    fun desktopReportsTheFreeThreadedAbi() {
        val v = Versions.currentVersion
        assertEquals("t", v.abiFlags, "desktop embeds CPython ${v.compactVersionString}t (#158)")
        assertEquals(v.compactVersionString + "t", v.taggedVersionString)
    }

    @Test
    fun theLoadedInterpreterIsReallyFreeThreaded() = PythonTestFixture.withInterpreter {
        // The claim above is only worth something if the libpython that `manager.loadLibPython`
        // actually loaded agrees with it. `Py_GIL_DISABLED` is a build-time fact of that library;
        // `sys._is_gil_enabled()` is not asserted, because an extension module without
        // `Py_mod_gil` re-enables the GIL at run time on a free-threaded build.
        assertEquals(
            "1",
            PythonTestFixture.eval("__import__('sysconfig').get_config_var('Py_GIL_DISABLED')").toString(),
            "the desktop libpython is a GIL build; desktop must embed the free-threaded one (#158)",
        )
    }

    @Test
    fun theAutomaticCheckpointIsOnByDefault() {
        // `Python3.autoDrainInterval` is a mutable default that other tests save and restore;
        // a positive value is what the free-threaded default means (Python3.kt).
        assertTrue(
            Python3.autoDrainInterval > 0,
            "autoDrainInterval=${Python3.autoDrainInterval}: the free-threaded default turns the checkpoint on",
        )
    }
}
