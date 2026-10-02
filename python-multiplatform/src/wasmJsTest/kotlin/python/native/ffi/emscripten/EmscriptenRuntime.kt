@file:JsModule("./cpython.mjs")

package python.native.ffi.emscripten

/**
 * What `cpython.mjs` hands out about the Emscripten instance itself, for tests that have to observe
 * the *runtime* rather than call the C API: the linear memory Kotlin shares with CPython, and the
 * raw export object of `python.wasm`.
 *
 * Test-only. `wasmJsMain` reaches CPython exclusively through `@WasmImport`; these two are JS
 * objects, so reading anything off them needs a `js(...)` helper, which the main source set allows
 * in exactly one file (see its README). Tests are not bound by that rule.
 */
external val wasmMemory: JsAny

external val wasmExports: JsAny

/**
 * The Emscripten `Module` itself. Tests reach `HEAPU32`, `wasmTable` and `addFunction` through it
 * -- the JS-side routes the measurement tests price the direct ones against.
 */
external val mod: JsAny
