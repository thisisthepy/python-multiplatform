package python.multiplatform

import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SPEC T-2 (issue #328): androidNative (`androidNativeArm64`, `androidNativeX64`) links the
 * free-threaded CPython 3.15t, the same build as Android (ART).
 *
 * Red before the change: `embedsFreeThreadedPython` was `false` in `artMain`, so `abiFlags` was `""`,
 * and the cinterop compiled against `include/python3.15` and linked `-lpython3.15`, python.org's GIL
 * build. (This replaces `AndroidNativeGilBuildTest`, which pinned the GIL build until #328.)
 */
class AndroidNativeFreeThreadedBuildTest {

    @Test
    fun androidNativeReportsTheFreeThreadedAbi() {
        val v = Versions.currentVersion
        assertEquals("t", v.abiFlags, "androidNative links CPython ${v.compactVersionString}t (#328)")
        assertEquals(v.compactVersionString + "t", v.taggedVersionString)
    }

    @Test
    fun theLinkedInterpreterIsReallyFreeThreaded() = PythonTestFixture.withInterpreter {
        assertEquals(
            "1",
            PythonTestFixture.eval("__import__('sysconfig').get_config_var('Py_GIL_DISABLED')").toString(),
            "the androidNative libpython is a GIL build; it must be the free-threaded one (#328)",
        )
    }
}
