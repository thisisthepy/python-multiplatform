package python.multiplatform.ffi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `import selectors` used to take the whole Node process down on this target, and `import asyncio`
 * with it. docs/design/upcall.md 9.5 recorded that as `RuntimeError: unreachable` and left
 * the cause open; 15 has it now, and it was never about `selectors` or about wasm.
 *
 * Emscripten feature-detects JSPI at runtime and wraps only `main` in `WebAssembly.promising`.
 * This library never calls `main`, so on a JSPI-capable Node the blocking syscalls became
 * suspending imports with no promising frame on the stack. `selectors.py` calls
 * `select.poll().poll(0)` at import time to pick its DefaultSelector, so it was the first module
 * to hit one. `cpython.mjs` now deletes `WebAssembly.promising`/`Suspending` before booting
 * Emscripten, which puts the blocking syscalls back on their synchronous path.
 *
 * These assertions are cheap, but each one is a statement that the process is still alive: the
 * old failure was not an exception a test could catch, it was `abort()`. A regression here does
 * not show up as red -- it shows up as the whole wasm suite reporting as lost, which is exactly
 * what happened when this case first went into `commonTest`. It stays in `wasmJsTest` because
 * this is the only target where the JSPI boot decision exists at all.
 */
class WasmSelectorsImportTest {

    private fun setUp() {
        if (!Python3.isInitialized) Python3.initialize(silent = true)
    }

    /**
     * The syscall underneath the whole story. `import select` always worked -- importing the
     * module calls nothing -- so the discriminating observation is a *call*, not an import.
     */
    @Test
    fun theBlockingPollSyscallReturnsInsteadOfSuspending() {
        setUp()
        Python3.exec(
            """
            import select
            _pmp_probe = select.poll().poll(0)
            assert _pmp_probe == [], _pmp_probe
            assert select.select([], [], [], 0) == ([], [], []), 'select() did not return empty'
            """.trimIndent()
        )
    }

    /**
     * The module that used to kill the process, and the reason it did: `_can_use('poll')` runs at
     * import time. Asserting on `DefaultSelector` rather than on the bare import is deliberate --
     * it fixes that the real dispatch at the bottom of `selectors.py` ran to completion, so a
     * future `sys.modules` shim could not satisfy this test by standing in for the module.
     */
    @Test
    fun importingSelectorsPicksTheRealPollSelector() {
        setUp()
        Python3.exec(
            """
            import selectors
            assert selectors.DefaultSelector is selectors.PollSelector, selectors.DefaultSelector
            assert selectors.SelectSelector is not None
            """.trimIndent()
        )
    }

    /** `asyncio` only ever died by way of `selectors`, so it comes back with it. */
    @Test
    fun importingAsyncioSucceeds() {
        setUp()
        Python3.exec(
            """
            import asyncio, asyncio.events, asyncio.base_events
            assert asyncio.Future is not None
            assert asyncio.get_event_loop_policy() is not None
            """.trimIndent()
        )
    }

    /**
     * How far asyncio actually gets, which is not all the way -- and this test is here to keep
     * that boundary honest rather than to celebrate it.
     *
     * A coroutine driven by a loop that never opens a selector runs to completion, awaits a real
     * `asyncio.Future`, and takes `asyncio.sleep(0)`. That is the surface an async upcall needs.
     * What still does not work is the *default* loop: `BaseSelectorEventLoop.__init__` calls
     * `_make_self_pipe` -> `socket.socketpair()`, and Emscripten routes that through a Node
     * `require('ws')` that is not installed. That is a separate blocker from the JSPI one and it
     * is untouched here, so `asyncio.run()` is deliberately not asserted.
     */
    @Test
    fun aSelectorFreeLoopRunsACoroutineToCompletion() {
        setUp()
        Python3.exec(
            """
            import asyncio

            class _PmpMiniLoop(asyncio.AbstractEventLoop):
                def __init__(self):
                    self._ready = []
                    self._running = False
                def get_debug(self): return False
                def is_closed(self): return False
                def is_running(self): return self._running
                def create_future(self): return asyncio.Future(loop=self)
                def create_task(self, coro, **kw): return asyncio.Task(coro, loop=self)
                def call_soon(self, cb, *args, context=None):
                    h = asyncio.Handle(cb, args, self, context)
                    self._ready.append(h)
                    return h
                def call_exception_handler(self, ctx):
                    raise AssertionError('loop reported: ' + str(ctx.get('message')))
                def run_until_complete(self, fut):
                    self._running = True
                    asyncio.events._set_running_loop(self)
                    try:
                        fut = asyncio.ensure_future(fut, loop=self)
                        for _ in range(1000):
                            if fut.done():
                                break
                            ready, self._ready = self._ready, []
                            if not ready:
                                break
                            for h in ready:
                                h._run()
                    finally:
                        asyncio.events._set_running_loop(None)
                        self._running = False
                    return fut.result()

            async def _pmp_body():
                loop = asyncio.get_event_loop()
                f = loop.create_future()
                loop.call_soon(f.set_result, 42)
                v = await f
                await asyncio.sleep(0)
                return v * 2

            _pmp_loop = _PmpMiniLoop()
            asyncio.set_event_loop(_pmp_loop)
            _pmp_result = _pmp_loop.run_until_complete(_pmp_body())
            assert _pmp_result == 84, _pmp_result
            """.trimIndent()
        )
    }
}
