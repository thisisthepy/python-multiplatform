package org.thisisthepy.python.multiplatform.demo.bindings

import python.multiplatform.ffi.Python3

/**
 * Android reaches the generated table the same way desktop does, and for the same reason: this
 * module applies the bindings plugin.
 *
 * It could not, before ROADMAP §13. KSP declares a minimum AGP and this build was below it, so
 * `com.google.devtools.ksp` died at configuration time in any module carrying an Android plugin
 * (`NoSuchMethodError: AndroidComponentsExtension.addKspConfigurations`). The demo's Python-facing
 * declarations therefore lived in a separate Android-free module and the install `actual` here
 * reported the version wall instead of doing anything. The AGP/Gradle bump removed both.
 *
 * That `actual` is gone from this file now, and not because Android lost the ability to write it:
 * `androidMain` *is* the Android target's own source set, so it could always name `FunctionTable`
 * directly, unlike `iosMain`. It is gone because the processor generates it (`@InstallsUpcallTable`
 * in `UpcallDemo.kt`), which removes the per-platform special case rather than the platform.
 */

/**
 * Python makes the call, through the same raw entry points the proxy module is built on.
 *
 * This used to resolve and invoke from *Kotlin* and say so, because "the boundary shim is
 * desktop-only today". That is no longer true: `androidMain`'s `UpcallEntry.publish` binds
 * `_pm_resolve` and `_pm_invoke` into `__main__` as `PyMethodDef`s whose `ml_meth` is a C shim in
 * `artMain/cinterop/jni_onload.def`, and [installPythonProxies] has already run by the time
 * anything on the demo screen can call this ([org.thisisthepy.python.multiplatform.demo.PythonDemo.start]
 * publishes before it returns).
 *
 * So this is now the same two-shape demonstration desktop gives -- a zero-argument entry and an
 * argument-carrying one -- with a JNI upcall under it instead of a Panama stub. It is written as
 * Python source rather than as Kotlin calls because that is the claim being made.
 */
actual fun callKotlinFromPython(): String = try {
    Python3.exec(
        """
        _pm_demo_raw = {}
        _pm_demo_raw['h'] = _pm_resolve('$UPCALL_ENTRY_NAME')
        _pm_demo_raw['args_h'] = _pm_resolve('$UPCALL_ARGS_ENTRY_NAME')
        if _pm_demo_raw['h'] != -1:
            _pm_demo_raw['value'] = _pm_invoke(_pm_demo_raw['h'], ())
            _pm_demo_raw['described'] = _pm_invoke(
                _pm_demo_raw['args_h'], ('presses x3 = ', 3)
            )
        """.trimIndent(),
    )
    val handle = evalToString("_pm_demo_raw['h']")
    if (handle == "-1") {
        "the name was not in the table (handle -1)"
    } else {
        "handle $handle -> ${evalToString("_pm_demo_raw['value']")}  ·  " +
            "with args -> ${evalToString("_pm_demo_raw['described']")}"
    }
} catch (t: Throwable) {
    "${t::class.simpleName}: ${t.message}"
}
