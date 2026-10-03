package python.multiplatform.env

/**
 * There is no staged payload directory on wasm, and there is no filesystem to stage one into.
 *
 * The standard library reaches this target by being preloaded into Emscripten's virtual filesystem
 * at *build* time (see `wasmJsMain/README.md` and `cpython.mjs`), which is also the only way a
 * consumer's Python could get here: the payload would have to be baked into that same image by the
 * bundling step, not discovered at run time. Nothing in `toolchain` produces a wasm bundle today —
 * `PythonStagingPlatform` has three destinations and wasm is not one of them — so returning an
 * empty list is the honest answer rather than a stub awaiting a directory that no build writes.
 */
internal actual fun discoverStagedPayloadRoots(): List<String> = emptyList()
