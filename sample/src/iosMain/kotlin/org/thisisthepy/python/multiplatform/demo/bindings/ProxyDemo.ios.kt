package org.thisisthepy.python.multiplatform.demo.bindings

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.upcall.PythonProxySource
import python.native.ffi.UpcallEntry

/**
 * iOS has a boundary shim, and it is not `ctypes`.
 *
 * `UpcallEntry.publish` installs real `PyMethodDef`-backed builtins into a namespace, which is the
 * only route on this target: this project's `Python.framework` ships no `_ctypes` (it exports
 * `PyInit__abc` through `PyInit_time` and carries no `lib-dynload`), so `ctypes.CDLL(None)` --
 * desktop's whole approach -- cannot be used here in either direction.
 *
 * ### There used to be a bridge here, and there is not any more
 *
 * The version of this file that came with the sample wrote nine lines of Python between `publish`
 * and `install`: it captured the native `_pm_bind`/`_pm_resolve` under other names, synthesised
 * `_pm_invoke` out of `_pm_bind` because `publish` did not install one, and re-decoded
 * `_pm_resolve`'s argument because the generated support sent `bytes` where the `METH_O` entry
 * point read `str`. It was compile-checked only, and it was papering over a library defect: the
 * generated proxy module could not install on any target but desktop.
 *
 * Both halves are fixed in the library now -- `UpcallEntry.publish` installs `_pm_invoke`, and
 * `pm_resolve` accepts `str` and `bytes` alike -- and `PythonProxyInstallTest` has moved to
 * `commonTest`, where the iOS simulator runs it. So this is the whole of what a host owes on this
 * target: publish, then install.
 */
actual fun installPythonProxies(): String = try {
    val globals = Python3.import("__main__").dict
    if (!UpcallEntry.publish(globals.pointer)) {
        "the PyMethodDef bootstrap could not be published"
    } else {
        val source = PythonProxySource.install()
        // `_PmModule` is support scaffolding and `_pm_t_N` is a generated metaclass; neither is a
        // proxy for a Kotlin type, which is what this number is meant to say.
        val classes = source.lineSequence().count {
            it.startsWith("class ") && !it.startsWith("class _Pm") && !it.startsWith("class _pm_t_")
        }
        "installed over PyMethodDef: ${source.lineSequence().count()} lines, $classes proxy classes"
    }
} catch (t: Throwable) {
    "${t::class.simpleName}: ${t.message}"
}

/**
 * Not wired on iOS.
 *
 * The fast-path `await` ([awaitFastPathDemo]) is shared and runs here; this one needs a Kotlin
 * thread to resume the parked continuation while the interpreter drives its loop, and starting one
 * from Kotlin/Native means a `Worker` and a decision about which memory model the parked lambda
 * crosses. That is a real design question for the library rather than something to improvise in a
 * sample, so it says so instead of pretending.
 *
 * The library's own `PythonProxyNativeDeliveryTest` does answer it, with a bare `pthread_create`:
 * an `await` over a generated `async def` whose Kotlin body genuinely suspended resolves on the
 * iOS simulator, resumed from a thread the Kotlin/Native runtime never attached.
 */
actual fun awaitSuspendingDemo(): String =
    "not wired on iOS: resuming a parked continuation needs a Kotlin/Native worker, " +
        "which this sample does not start. The fast-path await above does run here."
