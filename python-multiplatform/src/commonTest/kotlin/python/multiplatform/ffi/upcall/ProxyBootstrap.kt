package python.multiplatform.ffi.upcall

/**
 * Whether this target's upcall bootstrap publishes the two raw entry points the *generated proxy
 * module* needs -- `_pm_resolve` and `_pm_invoke` -- into `__main__`.
 *
 * This is a per-target constant rather than something read back out of Python at run time, and
 * that is the whole point of it. Reading `'_pm_invoke' in globals()` would make
 * [PythonProxyInstallTest] agree with whatever the bootstrap happens to do today: a target that
 * silently stopped publishing would take the "documented refusal" branch and stay green. That is
 * exactly the failure this file exists because of -- `PythonProxyInstallTest` lived in
 * `desktopTest` alone, so the four other targets were never asked the question at all, and
 * `UpcallEntry.publish` and [PythonProxySource] disagreed about the bootstrap for as long as the
 * generator has existed.
 *
 * `true` on the targets whose boundary shim publishes both names:
 *
 * | target | bootstrap |
 * |---|---|
 * | desktop | `ctypes.CFUNCTYPE` over the Panama upcall stubs in `UpcallStub` |
 * | iOS, androidNative | `PyMethodDef`s installed by `nativeMain`'s `UpcallEntry.publish` |
 * | Android/ART | the same `PyMethodDef`s, with C shims in `jni_onload.def` behind `ml_meth` |
 *
 * ### What the ART row cost, and why the note that used to be here was wrong
 *
 * It said one C function was missing: a `pmp_upcall_invoke_free_meth` beside the existing
 * `pmp_upcall_invoke_meth`, reading the handle out of `args[0]` and the tuple out of `args[1]` and
 * published as `_pm_invoke`. That was true, and it was not the whole of it.
 *
 * With only that added, every test in [PythonProxyInstallTest] got *past* the generated module's
 * entry-point guard and failed one line later -- on `pmp_api26` and `pmp_api36` alike, 11 of 11 --
 * with `TypeError: bad argument type for built-in operation`. `pmp_upcall_resolve_meth` read its
 * argument with `PyUnicode_AsUTF8` alone, and [PythonProxySource]'s `_pm_lookup` sends `bytes`,
 * because desktop reaches its resolver through `ctypes.CFUNCTYPE(c_long, c_char_p)` and `c_char_p`
 * refuses a `str` outright. `nativeMain`'s `pmResolveMethod` had already been taught both spellings
 * for exactly that reason; the ART shim had not, and nothing had ever asked it to -- the only caller
 * it had, `androidInstrumentedTest`'s `bindUpcallOrNull`, passes a `str`.
 *
 * The note could not have found that, because it was written from reading and the second half only
 * exists at run time. It took an emulator, which is the same shape of gap this file exists to close.
 *
 * | wasmJs | five `PyCFunction`s over the single `@WasmExport`ed `pmp_invoke`, told apart by `self` |
 *
 * ### What the wasmJs row used to say, and why it was wrong
 *
 * It was `false`, on this reasoning: nothing crosses into Python on that target but a *bound*
 * callable, so publishing `_pm_resolve`/`_pm_invoke` would need **new `@WasmExport`s** -- and since
 * `@WasmExport` is honoured only in the compilation that produces the `.wasm`, the library could
 * never declare them; only the embedding application could, in the three-line
 * `wasmJsTest/UpcallExports.kt` shape.
 *
 * Every clause of that is true except the one it turns on. A `PyCFunction` is a function pointer
 * **and a `self`**, and `PyCFunction_NewEx` mints a new object per call, so one exported pointer
 * already backs as many distinct Python callables as you care to build -- which is precisely what
 * `UpcallEntry.bind` had been doing with a `CallableHandle` all along. Reserving five negative
 * values `self` can never legitimately hold (a live handle is non-negative, and `-1` is
 * `CallableHandle.NONE`) makes the one export a dispatcher, and the five names land with **no new
 * export and no change to what an embedding application writes**. See `wasmJsMain`'s `UpcallEntry`.
 *
 * What did not move is the async half: `import asyncio` traps this wasm instance rather than
 * raising, so a proxy whose Kotlin body genuinely suspends still cannot be awaited there. That is
 * [proxyBootstrapSupportsAsyncio], deliberately a second constant -- collapsing the two would make
 * "no proxies at all" and "no *awaitable* proxies" the same statement, and they are not.
 */
expect val publishesProxyEntryPoints: Boolean

/**
 * Whether a test on this target may `import asyncio` at all.
 *
 * `true` everywhere but wasmJs, where `import asyncio` **traps the wasm instance** -- killing the
 * Node process rather than raising a Python exception, so neither Kotlin nor Python can catch it.
 * `AsyncUpcallPortabilityTest` records how that was found (a `commonTest` case took the whole suite
 * down with it) and `docs/design/upcall.md` §5.8 has the import-by-import measurement.
 *
 * Separate from [publishesProxyEntryPoints] because the two answers came apart the moment wasmJs
 * grew a bootstrap. Every *synchronous* generated proxy -- constructor, method, property, static --
 * runs there now; what still cannot run is anything that needs an event loop. A single constant
 * would have to be `false`, and would then silently stop asserting the ten contracts that do hold.
 *
 * A test guarded by this is **not skipped** in the sense a target with no bootstrap is: there is no
 * "documented refusal" to assert here, because the failure is a process death rather than an error
 * anything can observe. The honest shape is to not run it, and to say so here.
 */
expect val proxyBootstrapSupportsAsyncio: Boolean
