package python.multiplatform.env

/**
 * `PYTHONHOME` is not a thing a Kotlin/Wasm embedder sets: the stdlib is baked into the virtual
 * filesystem Emscripten preloads at build time (see `cpython.mjs`), not looked up from an
 * environment variable at startup. Returning null here makes [PythonHomeCheck.verifyOrThrow]
 * a no-op on this target, which is the correct gate rather than a stub to fill in later.
 */
internal actual fun readEnvVar(name: String): String? = null

/** Unreachable: [readEnvVar] always returns null on this target, so [PythonHomeCheck] never calls this. */
internal actual fun pathIsAccessible(path: String): Boolean = true
