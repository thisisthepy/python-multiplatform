package python.multiplatform.ffi.upcall

/** `nativeMain`'s `UpcallEntry.publish` installs both names as real `PyMethodDef`-backed builtins. */
actual val publishesProxyEntryPoints: Boolean = true

/** iOS and androidNative both ship a CPython whose `asyncio` imports; §11 measured the slow path. */
actual val proxyBootstrapSupportsAsyncio: Boolean = true
