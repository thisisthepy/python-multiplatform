package python.native.ffi

import python.multiplatform.Versions
import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopPythonTest {
    /**
     * Proves the desktop chain works end to end: the library loads, the interpreter starts, and
     * the version comes back through Panama.
     *
     * Goes through the shared fixture, and deliberately does NOT finalise. An earlier version
     * called `Py_Initialize()` and `Py_Finalize()` itself, which finalised the interpreter
     * mid-suite -- every test class scheduled after it then crashed inside `PyGILState_Ensure`
     * -> `new_threadstate`, reporting zero tests because the JVM died before any XML was
     * written. It went unnoticed while nothing happened to run afterwards.
     *
     * The interpreter is process-wide state. No single test owns its lifecycle.
     */
    @Test
    fun testPythonEndToEnd() {
        assertTrue(PythonTestFixture.available, "CPython could not be initialized: ${PythonTestFixture.failureReason}")
        val version = python.multiplatform.ffi.Python3.withPython { Py_GetVersion() }
        // The configured version, not a literal -- see EmbedApiLowLevelTest.
        val expected = Versions.currentVersion.compactVersionString
        assertTrue(
            version?.startsWith(expected) == true,
            "Expected version to start with $expected, got $version"
        )
    }
}
