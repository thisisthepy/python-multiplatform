package org.thisisthepy.python.multiplatform.demo.bindings

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.upcall.PythonProxySource
import python.native.ffi.UpcallEntry

/**
 * wasmJs's bootstrap is the newest of the five, and the reason this file could be written at all
 * is `4472f83a`: `UpcallEntry.publish` installs `_pm_resolve` *and* `_pm_invoke` -- unlike
 * Android's history (`ProxyDemo.android.kt`'s doc), wasmJs never shipped a `publish` that left
 * `_pm_invoke` out, because it was written after that gap was already known. What makes it
 * possible with only one `@WasmExport` is `self`, not a second export -- see `UpcallEntry`'s own
 * class doc ("one export is enough for the whole bootstrap") and `WasmExports.kt` next to
 * `UpcallDemo.wasmJs.kt`.
 *
 * So this is the same two lines Android and iOS own: publish, then install.
 */
actual fun installPythonProxies(): String = try {
    val globals = Python3.import("__main__").dict
    if (!UpcallEntry.publish(globals.pointer)) {
        "the PyCFunction bootstrap could not be published"
    } else {
        val source = PythonProxySource.install()
        // `_PmModule` is support scaffolding and `_pm_t_N` is a generated metaclass; neither is a
        // proxy for a Kotlin type, which is what this number is meant to say.
        val classes = source.lineSequence().count {
            it.startsWith("class ") && !it.startsWith("class _Pm") && !it.startsWith("class _pm_t_")
        }
        "installed over PyCFunction (self as dispatcher): ${source.lineSequence().count()} lines, " +
            "$classes proxy classes"
    }
} catch (t: Throwable) {
    "${t::class.simpleName}: ${t.message}"
}

/**
 * Not wired on wasmJs, and not fixable by starting a thread the way desktop's and Android's do.
 *
 * `import asyncio` traps this wasm instance rather than raising
 * (`AsyncUpcallPortabilityTest`, `docs/design/upcall-async-design.md` §9.5, and
 * `ProxyBootstrap.wasmJs.kt`'s `proxyBootstrapSupportsAsyncio = false`) -- the process goes down
 * with it, not just the call. So this function must never run `import asyncio`, and neither may
 * [awaitFastPathDemo] (shared, `ProxyDemo.kt`) be reached from this target: `ui/App.kt` gates
 * section 7 off on wasmJs before either demo function is called, rather than this one reporting a
 * failure after the fact -- there would be no process left to report it from.
 */
actual fun awaitSuspendingDemo(): String =
    "not available on wasmJs: `import asyncio` traps this wasm instance instead of raising " +
        "(docs/design/upcall-async-design.md §9.5), so this demo never calls it. The synchronous surface " +
        "above -- sections 5 and 6 -- is what `4472f83a` bought back on this target; `await` is not."
