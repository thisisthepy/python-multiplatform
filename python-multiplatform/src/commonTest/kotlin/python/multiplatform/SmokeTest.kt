package python.multiplatform

import python.native.ffi.Py_IsInitialized
import kotlin.test.Test
import kotlin.test.assertTrue


/**
 * The narrowest possible check that the test harness is wired end to end:
 * the test binary links against the embedded CPython and can call into it.
 *
 * [Py_IsInitialized] is one of the few C API functions that is safe to call
 * before `Py_Initialize()`, so it verifies linkage without requiring a
 * usable `PYTHONHOME`.
 */
class SmokeTest {
    @Test
    fun embeddedPythonIsLinked() {
        val state = Py_IsInitialized()
        assertTrue(state == 0 || state == 1, "Py_IsInitialized() returned $state, expected 0 or 1")
    }
}
