package python.native.ffi

import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.withGIL
import python.multiplatform.reflection.UpcallTable

/**
 * wasmJs's half of `UpcallEntryTest`'s (`commonTest`) contract.
 *
 * Everything crosses through the one `@WasmExport` `pmp_invoke` reaches by `call_indirect`
 * (`Table.set`, 3.1 ns/call, per ROADMAP §11) -- both the five entry points [UpcallEntry.publish]
 * installs and the bound callable below, which differ only in what is boxed in the `PyCFunction`'s
 * `self`. See [UpcallEntry]'s "One export is enough for the whole bootstrap".
 *
 * Resolution is still done in Kotlin here rather than through the published `_pm_resolve`, because
 * that is what this bridge is *for*: `UpcallEntryTest` asks whether [UpcallEntry.bind] produces a
 * working callable, and routing the name through Python first would turn it into a test of
 * `_pm_resolve` instead. `PythonProxyInstallTest` exercises the published pair, which is the other
 * contract.
 *
 * Every raw C API call here goes through [withGIL], not as a style preference: `UpcallEntry`'s own
 * `PyGILState_Ensure`/`Release` pair genuinely detaches this thread once its nesting count returns
 * to zero, unlike a build where the main thread stays implicitly attached forever.
 */
actual fun bindUpcallOrNull(name: String): Boolean {
    withGIL {
        val globals = PythonTestFixture.mainGlobals()
        check(UpcallEntry.publish(globals.pointer)) { "the upcall bootstrap could not be published" }
    }
    val handle = UpcallTable.resolve(name)
    if (!handle.isValid) {
        withGIL { python.multiplatform.ffi.Python3.exec("_pm_bound = None") }
        return false
    }
    val callable = UpcallEntry.bind(handle.raw)
        ?: error("UpcallEntry.bind returned null for a handle UpcallTable.resolve just returned")
    withGIL {
        val globals = PythonTestFixture.mainGlobals()
        try {
            check(PyDict_SetItemString(globals.pointer, "_pm_bound", callable) == 0) {
                "could not install '_pm_bound' into __main__'s globals"
            }
        } finally {
            // PyDict_SetItemString takes its own reference; this one was ours to give up.
            Py_DecRef(callable)
        }
    }
    return true
}

/**
 * Drops the [python.multiplatform.reflection.HandleTable] root behind [handle] through the
 * published `_pm_release` -- the same `PyCFunction` a generated proxy's `__del__` calls.
 *
 * Routed through Python rather than straight to the marshaller so that this target checks the
 * *published* release the way `nativeTest`'s counterpart does. It called
 * `UpcallTrampoline.releaseObject` directly for as long as there was no published one to call.
 */
actual fun releaseUpcallHandle(handle: Long): Int =
    withGIL { PythonTestFixture.eval("_pm_release($handle)").toString().toInt() }
