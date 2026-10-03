package org.thisisthepy.python.multiplatform.demo

import org.thisisthepy.python.multiplatform.demo.bindings.DemoCounter
import org.thisisthepy.python.multiplatform.demo.bindings.UPCALL_ENTRY_NAME
import org.thisisthepy.python.multiplatform.demo.bindings.callKotlinFromPython
import org.thisisthepy.python.multiplatform.demo.bindings.installGeneratedUpcallTable
import org.thisisthepy.python.multiplatform.demo.bindings.upcallTableSummary

/**
 * The table is generated and installed here exactly as on desktop and iOS -- same processor, same
 * fragments, discovered through this module's own Android compilation.
 *
 * This used to be a stub whose every member returned "unavailable on Android", because the
 * bindings plugin could not be applied to a module carrying an Android plugin at the AGP this
 * build pinned. ROADMAP §13 moved AGP past KSP's minimum and the stub is gone. The call itself is
 * made from Python now too: ART's boundary shim closed the two C gaps it had, and
 * `bindings/UpcallDemo.android.kt`'s [callFromPython] resolves and invokes through
 * `_pm_resolve`/`_pm_invoke` the same way desktop's `ctypes` bridge does.
 */
actual object UpcallDemo {
    actual val available: Boolean = true
    actual val entryName: String = UPCALL_ENTRY_NAME
    actual fun install() = installGeneratedUpcallTable()
    actual fun press() = DemoCounter.press()
    actual fun callFromPython(): String = callKotlinFromPython()
    actual fun tableSummary(): String = upcallTableSummary()
    // Fully qualified: the member would otherwise shadow the imported top-level function and
    // recurse into itself.
    actual fun optOutHeld(): Boolean = org.thisisthepy.python.multiplatform.demo.bindings.optOutHeld()
}
