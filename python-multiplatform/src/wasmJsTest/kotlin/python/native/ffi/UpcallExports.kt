@file:OptIn(kotlin.wasm.ExperimentalWasmInterop::class)

package python.native.ffi

/**
 * The one `@WasmExport` trampoline the upcall argument-marshalling path needs, alongside
 * `ProxyTypeFactory`'s three type slots in `wasmJsTest/.../ProxyTypeExports.kt`.
 *
 * **Here rather than in `wasmJsMain` because it has to be.** `@WasmExport` is honoured only in the
 * compilation that produces the `.wasm`; a library is compiled to a klib and linked in, and its
 * annotations do not reach the export section of whatever links it in -- measured on the identical
 * three functions this file's neighbour declares.
 *
 * So this is not test scaffolding. It is the file **an application embedding this library must
 * write for itself**, reproduced here so the suite exercises the path an application takes rather
 * than a private shortcut.
 *
 * The shape is not a style choice -- `PyCFunction`'s `(PyObject *self, PyObject *args)`, and
 * `call_indirect` does not coerce a mismatched arity or type.
 */
@kotlin.wasm.WasmExport("pmp_invoke")
fun pmpInvoke(selfPtr: Int, argsPtr: Int): Int = UpcallEntry.invokeMethod(selfPtr, argsPtr)
