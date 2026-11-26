package python.multiplatform

/**
 * wasmJs embeds free-threaded CPython 3.15t built for wasm32-emscripten with pthreads
 * (`scripts/wasm/build-cpython.sh`, #175). See the `expect` in commonMain.
 */
internal actual val embedsFreeThreadedPython: Boolean = true
