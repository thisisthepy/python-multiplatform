package python.multiplatform.ffi.upcall

/** `UpcallEntryBridge.desktop.kt` binds both names as `ctypes.CFUNCTYPE`s over Panama stubs. */
actual val publishesProxyEntryPoints: Boolean = true

/** A full CPython on a desktop JVM; `import asyncio` is ordinary. */
actual val proxyBootstrapSupportsAsyncio: Boolean = true
