@file:OptIn(kotlin.wasm.ExperimentalWasmInterop::class)

package org.thisisthepy.python.multiplatform.demo

/**
 * The one `@WasmExport` this application owes the library.
 *
 * `python-multiplatform`'s `UpcallEntry` (`wasmJsMain/.../UpcallEntry.kt`) can only *use* an
 * exported function pointer that already exists in this compilation's export section --
 * `@WasmExport` is honoured only where the `.wasm` is produced, and a library klib linked into
 * this app does not carry its own. `wasmJsTest/.../UpcallExports.kt` is the identical file the
 * library's own test binary writes for itself; this is the sample's copy of the same three lines.
 *
 * Nothing here needs the three `pmp_tp_*` slots `ProxyTypeExports.kt` adds in the library's test
 * binary: those back `ProxyTypeFactory`'s cycle-collectable proxy type, which this sample's
 * generated proxies do not use (`PythonProxySource`'s own doc: the handle a constructed `Greeter`
 * carries is a bare `HandleTable` integer, not a `ProxyTypeFactory` instance).
 */
@kotlin.wasm.WasmExport("pmp_invoke")
fun pmpInvoke(selfPtr: Int, argsPtr: Int): Int =
    python.native.ffi.UpcallEntry.invokeMethod(selfPtr, argsPtr)
