package python.native.ffi.emscripten

import python.native.ffi.Wasm

/** Current size of the linear memory Kotlin and CPython share, in bytes. */
fun sharedMemoryBytes(): Double = memoryBytes(wasmMemory)

private fun memoryBytes(memory: JsAny): Double = js("memory.buffer.byteLength")

/** `true` when `python.wasm` exports a `WebAssembly.Tag` called [name]. */
fun exportsWasmTag(name: String): Boolean = isTag(wasmExports, name.toJsString())

private fun isTag(exports: JsAny, name: JsString): Boolean =
    js("typeof WebAssembly.Tag === 'function' && exports[name] instanceof WebAssembly.Tag")

/**
 * Runs [source] in `__main__` through `PyRun_SimpleString`, with the bytes written straight into
 * CPython's heap. Returns PyRun's status: 0 on success, -1 with the traceback already printed.
 * Caller holds the GIL.
 */
fun runInMain(source: String): Int {
    val buf = Wasm.allocUtf8(source)
    try {
        return python.native.ffi.bindings.PyRun_SimpleString(buf)
    } finally {
        Wasm.freeUtf8(buf)
    }
}

/** Borrowed `PyObject*` of `__main__.<name>`, or 0. Caller holds the GIL. */
fun mainGlobal(name: String): Int {
    val module = python.native.ffi.bindings.PyImport_AddModule(Wasm.internedUtf8("__main__"))
    if (module == 0) return 0
    val dict = python.native.ffi.bindings.PyModule_GetDict(module)
    if (dict == 0) return 0
    return python.native.ffi.bindings.PyDict_GetItemString(dict, Wasm.internedUtf8(name))
}

/**
 * `str(<expression>)` evaluated in `__main__`, read back through `PyUnicode_AsUTF8`'s `char*`
 * dereferenced in shared memory. `null` if evaluation failed. Caller holds the GIL.
 */
fun evalToString(expression: String): String? {
    if (runInMain("__pmp_probe = str($expression)") != 0) return null
    val obj = mainGlobal("__pmp_probe")
    if (obj == 0) return null
    val utf8 = python.native.ffi.bindings.PyUnicode_AsUTF8(obj)
    val out = if (utf8 == 0) null else Wasm.readUtf8String(utf8)
    runInMain("del __pmp_probe")
    return out
}
