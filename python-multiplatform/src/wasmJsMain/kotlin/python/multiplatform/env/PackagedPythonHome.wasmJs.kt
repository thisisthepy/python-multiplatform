package python.multiplatform.env

/** No-op: the wasm runtime's stdlib is baked into its virtual filesystem. */
internal actual fun applyPackagedPythonHome() = Unit
