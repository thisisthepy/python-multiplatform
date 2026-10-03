package org.thisisthepy.python.multiplatform.demo

import org.thisisthepy.python.multiplatform.demo.bindings.DemoCounter
import org.thisisthepy.python.multiplatform.demo.bindings.UPCALL_ENTRY_NAME
import org.thisisthepy.python.multiplatform.demo.bindings.callKotlinFromPython
import org.thisisthepy.python.multiplatform.demo.bindings.installCtypesBridge
import org.thisisthepy.python.multiplatform.demo.bindings.installGeneratedUpcallTable
import org.thisisthepy.python.multiplatform.demo.bindings.upcallTableSummary

/** The full path: a table KSP generated, reached from Python through `ctypes`. */
actual object UpcallDemo {
    actual val available: Boolean = true
    actual val entryName: String = UPCALL_ENTRY_NAME

    /**
     * `installGeneratedUpcallTable` is generated (`@InstallsUpcallTable`); the `ctypes` bridge
     * beside it is the desktop-only boundary shim and stays hand-written. Separating the two is
     * what let the install half be generated for every target at once.
     */
    actual fun install() {
        installGeneratedUpcallTable()
        installCtypesBridge()
    }

    actual fun press() = DemoCounter.press()
    actual fun callFromPython(): String = callKotlinFromPython()
    actual fun tableSummary(): String = upcallTableSummary()
    // Fully qualified: the member would otherwise shadow the imported top-level function and
    // recurse into itself.
    actual fun optOutHeld(): Boolean = org.thisisthepy.python.multiplatform.demo.bindings.optOutHeld()
}
