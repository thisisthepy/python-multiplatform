package python.multiplatform.ffi

import python.native.ffi.*
import kotlin.test.Test

class GilParkingTest {

    @Test
    fun parkThenReenterSameThreadIsolatesTheFailure() {
        println("--- START OF GilParkingTest ---")
        val ready = PythonTestFixture.available
        println("Fixture ready: $ready")

        // Step 1: bare re-entry, no Python C API call at all inside the guard except one that
        // reads state without touching bytecode.
        println("Executing Step 1...")
        withGIL {
            val after = PyGILState_GetThisThreadState()
            println("after Ensure: $after")
        }
        println("Step 1 completed successfully.")

        // Step 2: a pure-C call that cannot execute Python bytecode.
        println("Executing Step 2...")
        withGIL {
            Py_IsInitialized()
        }
        println("Step 2 completed successfully.")

        // Step 3: the exact call that crashed today.
        println("Executing Step 3...")
        withGIL {
            PyImport_ImportModule("sys")
        }
        println("Step 3 completed successfully.")

        // Step 4 (only if Step 3 crashes): bypass import_ensure_initialized's Python-level path
        // entirely, staying in pure C, to see whether a plain cache lookup alone is safe:
        println("Executing Step 4...")
        withGIL {
            val modules = PySys_GetObject("modules")
            PyDict_GetItemString(modules!!, "sys")
        }
        println("Step 4 completed successfully.")
        
        println("--- END OF GilParkingTest ---")
    }
}
