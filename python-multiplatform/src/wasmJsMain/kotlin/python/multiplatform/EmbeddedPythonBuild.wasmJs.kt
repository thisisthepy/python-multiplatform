package python.multiplatform

/** wasmJs runs a GIL CPython built for wasm32-emscripten until #175. See the `expect` in commonMain. */
internal actual val embedsFreeThreadedPython: Boolean = false
