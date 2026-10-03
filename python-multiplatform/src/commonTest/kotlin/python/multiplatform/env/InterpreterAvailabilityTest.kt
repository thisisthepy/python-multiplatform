package python.multiplatform.env

import python.multiplatform.Versions
import python.multiplatform.ffi.Python3
import python.native.ffi.Py_GetVersion
import python.native.ffi.Py_IsInitialized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


/**
 * Establishes whether a real CPython interpreter can be brought up inside the test binary.
 *
 * Everything else in the suite depends on the answer: without a live interpreter only the
 * pointer-level measurements can run, and every functional test has to be skipped.
 *
 * The embedded `Python.framework` contains no standard library, so `PYTHONHOME` must point at
 * a prefix holding `lib/python3.14`. The build wires that up for simulator test runs — see
 * `extractIosSimulatorStdlib` in `build.gradle.kts`.
 */
class InterpreterAvailabilityTest {

    @Test
    fun interpreterInitialises() {
        Python3.initialize(silent = false)

        assertEquals(1, Py_IsInitialized(), "Py_IsInitialized() should report 1 after initialize()")
        assertTrue(Python3.isInitialized, "Python3.isInitialized should be true after initialize()")
    }

    @Test
    fun interpreterReportsItsVersion() {
        Python3.initialize(silent = true)

        val version = Py_GetVersion()
        println("Embedded CPython version: $version")
        // The configured version, not a literal -- see EmbedApiLowLevelTest for why.
        val expected = Versions.currentVersion.compactVersionString
        assertTrue(
            version != null && version.startsWith(expected),
            "expected a $expected.x version string, got: $version"
        )
    }
}
