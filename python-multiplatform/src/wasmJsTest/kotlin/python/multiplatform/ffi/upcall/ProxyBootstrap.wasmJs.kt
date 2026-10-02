package python.multiplatform.ffi.upcall

/**
 * `wasmJsMain`'s `UpcallEntry.publish` installs all five names, as `PyCFunction`s over the single
 * `@WasmExport`ed `pmp_invoke` that an embedding application already declares -- told apart by the
 * op code boxed in each one's `self`, not by a second export.
 *
 * Flipped from `false` only after the ten synchronous contracts below were watched to pass under
 * `wasmJsNodeTest`; the constant is the claim, not the cause. See [publishesProxyEntryPoints]'s
 * "What the wasmJs row used to say, and why it was wrong" for the reasoning it replaced.
 */
actual val publishesProxyEntryPoints: Boolean = true

/**
 * Still `false`, but **no longer for the reason §9.5 gave**. `import asyncio` does not trap any
 * more: `docs/design/upcall.md` §5.8 found the trap was Emscripten's *runtime* JSPI detection
 * meeting a boot that never calls `main`, fixed it in `cpython.mjs`, and `WasmSelectorsImportTest`
 * now watches `selectors` and `asyncio` import cleanly on this target.
 *
 * What is missing is narrower, and it is not a trap. The test this constant gates builds the
 * *default* event loop and counts its `create_future`; `BaseSelectorEventLoop.__init__` calls
 * `_make_self_pipe` -> `socket.socketpair()`, which Emscripten routes through a Node `require('ws')`
 * that is not installed. That blocker is independent of JSPI -- it reproduces on a Node without it.
 *
 * A selector-free loop clears it, and `WasmSelectorsImportTest` runs a coroutine to completion over
 * one, so the surface an async upcall needs does exist here. Whether the library should *ship* such
 * a loop is an open decision (§15.7), not a measurement. The constant stays `false` until that is
 * made, rather than being flipped on a loop the tests supply for themselves.
 */
actual val proxyBootstrapSupportsAsyncio: Boolean = false
