package python.multiplatform

import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SPEC T-2a (issue #328): iOS links the project's own free-threaded `Python.xcframework` (3.15t),
 * the same archive the plugin's iOS consumer wiring stages.
 *
 * Red before the change: `embedsFreeThreadedPython` was `false` in `iosMain`, so `abiFlags` was `""`
 * and `IosPythonHome`/`PythonHomeCheck` looked for `lib/python3.15`, and the library's cinterop and
 * test binary used python.org's GIL framework (`Py_GIL_DISABLED` 0). (This replaces
 * `IosGilBuildTest`, which pinned the GIL build until #328.)
 */
class IosFreeThreadedBuildTest {

    @Test
    fun iosReportsTheFreeThreadedAbi() {
        val v = Versions.currentVersion
        assertEquals("t", v.abiFlags, "iOS embeds CPython ${v.compactVersionString}t (#328)")
        assertEquals(v.compactVersionString + "t", v.taggedVersionString)
    }

    @Test
    fun theLinkedInterpreterIsReallyFreeThreaded() = PythonTestFixture.withInterpreter {
        assertEquals(
            "1",
            PythonTestFixture.eval("__import__('sysconfig').get_config_var('Py_GIL_DISABLED')").toString(),
            "the iOS framework is a GIL build; iOS must embed the free-threaded one (#328)",
        )
    }
}
