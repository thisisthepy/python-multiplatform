package org.thisisthepy.python.multiplatform.demo.bindings

import python.multiplatform.ffi.Python3

/**
 * wasmJs's boundary shim is `UpcallEntry.publish`: five `PyCFunction` objects over the single
 * `@WasmExport`ed `pmp_invoke` this app declares in `WasmExports.kt`, told apart by an op code
 * boxed into each one's `self` rather than by five separate exports. `_pm_resolve` and
 * `_pm_invoke` are the two of those five this demo calls by name -- the same pair
 * `ProxyDemo.wasmJs.kt`'s [installPythonProxies] publishes, and by the time this function can be
 * clicked, [PythonDemo.start][org.thisisthepy.python.multiplatform.demo.PythonDemo.start] has
 * already run it.
 *
 * Written as Python source rather than as Kotlin calls, like Android's -- both bootstraps publish
 * `_pm_resolve`/`_pm_invoke` directly (no separate zero-argument stub the way desktop's `ctypes`
 * bridge needs one), so the same two-call shape demonstrates the same claim: Python resolves a
 * name against the generated table and invokes it, with no JavaScript in the call.
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
