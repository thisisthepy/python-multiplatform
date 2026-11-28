@file:JsModule("./cpython-config.mjs")

package python.native.ffi.emscripten

/**
 * SPEC T-8: whether the staged runtime's `python.wasm` has a shared memory, as the staging read it
 * from the module's memory import. Generated into every `cpython-config.mjs`, the test bundle's and
 * a consumer's alike.
 */
external val SHARED_MEMORY: JsBoolean
