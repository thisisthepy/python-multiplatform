package python.multiplatform.ffi.upcall

/**
 * Bootstraps the Python environment with the upcall entry points required by [PythonProxySource].
 *
 * This is the consumer-facing route ROADMAP §16f names as the gap: every `actual` below is a thin
 * wrapper over a per-platform bootstrap (`UpcallStub`'s Panama stubs on desktop,
 * `python.native.ffi.UpcallEntry.publish` everywhere else) that already existed in production code
 * but that nothing outside this module's own tests could reach.
 *
 * ### Lifetime and re-entrancy
 *
 * - **The published names do not outlive the interpreter they were published into.** They are
 *   entries in `__main__`'s dict, and `Py_Finalize()` tears that dict down along with everything
 *   else. The underlying addresses -- the Panama upcall stub on desktop, the `staticCFunction` on
 *   native, the `PyMethodDef`s on Android/ART, the `@WasmExport` table entry on wasmJs -- are process-
 *   lifetime (desktop: `Arena.global()`; native/Android: `nativeHeap`/heap allocations "never freed";
 *   wasmJs: the wasm instance's own indirect function table) and survive a finalize/re-initialize
 *   cycle intact, but the Python-side bindings to them do not. A caller that finalizes and
 *   re-initializes the interpreter must call [publishToGlobals] again before installing any proxy;
 *   there is nothing to double-free or dangle on the Kotlin side either way.
 * - **Calling this twice is safe and cheap.** Every `actual`'s install step is
 *   `PyDict_SetItemString`, an unconditional overwrite rather than a "define once" guard, so a
 *   second call rebinds each name to the same underlying entry point rather than refusing or
 *   raising. `UpcallBootstrapTest` (`commonTest`) pins this.
 * - **What is not covered by a test:** the finalize/re-initialize interaction itself.
 *   `Python3.finalize` is `internal`, its only caller is `artMain/JniExport.kt`, and this test suite
 *   shares one interpreter across every test in the binary (`PythonTestFixture`) -- a test that
 *   finalized it would take every other test in the suite down too. The claim above follows from
 *   reading `Python3.finalize` (it tears down `__main__` via `Py_Finalize()`, `initialize()` rebuilds
 *   a fresh one) and from where each address lives, not from an executed test.
 */
expect object UpcallBootstrap {
    /**
     * Publishes `_pm_resolve`, `_pm_invoke`, `_pm_release`, and `_pm_cancel` to the current
     * Python `__main__` module's globals.
     * @return true if successful.
     */
    fun publishToGlobals(): Boolean
}
