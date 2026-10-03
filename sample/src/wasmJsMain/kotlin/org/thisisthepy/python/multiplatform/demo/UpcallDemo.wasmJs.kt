package org.thisisthepy.python.multiplatform.demo

import org.thisisthepy.python.multiplatform.demo.bindings.DemoCounter
import org.thisisthepy.python.multiplatform.demo.bindings.UPCALL_ENTRY_NAME
import org.thisisthepy.python.multiplatform.demo.bindings.callKotlinFromPython
import org.thisisthepy.python.multiplatform.demo.bindings.installGeneratedUpcallTable
import org.thisisthepy.python.multiplatform.demo.bindings.upcallTableSummary

/**
 * The table is generated and installed here exactly as on every other target -- same processor,
 * discovered through this leaf target's own KSP output. The boundary shim (`_pm_resolve` /
 * `_pm_invoke` published as `PyCFunction`s over one `@WasmExport`) is not bound yet at [install]
 * time; like Android and iOS it is bound inside `installPythonProxies`, which
 * [org.thisisthepy.python.multiplatform.demo.PythonDemo.start] runs immediately afterwards and
 * before it returns -- so by the time anything on the demo screen can call [callFromPython], the
 * bootstrap this file's `bindings/UpcallDemo.wasmJs.kt` actual depends on is already in place.
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
