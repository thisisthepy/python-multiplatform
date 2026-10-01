# wasmJsMain — rules

Kotlin/Wasm reaching CPython 3.14 built for `wasm32-emscripten`, through `@WasmImport` against a
**shared linear memory**. Not a JavaScript bridge: there is no JS frame in either the call path or
the data path.

Everything below is measured. The standalone `wasm-experiment/` that first took the measurements
was retired into this source set and `tools/wasm/` (it remains in git history: `git log --
wasm-experiment`); `docs/platforms/wasm-design.md` records how they were arrived at, including the several
conclusions that were wrong before they were run. The two claims the rest of this file stands on
that only the experiment used to prove -- the shared memory surviving growth, and the interpreter
carrying the `pyemscripten_2026_0` ABI -- are now `wasmJsTest/.../emscripten/WasmSharedMemoryGrowthTest`
and `WasmInterpreterAbiTest`.

## Never call `withScopedMemoryAllocator`

    // WRONG -- allocates from address 0 upward, on top of Emscripten's static data
    withScopedMemoryAllocator { allocator -> allocator.allocate(n) }

    // RIGHT -- CPython allocates, Kotlin dereferences what it was handed
    val address = Wasm.allocUtf8(value)      // goes through CPython's own malloc
    try { ... } finally { Wasm.freeUtf8(address) }

It is the only allocator Kotlin/Wasm offers, and it assumes it owns the whole memory. Measured: it
grew the memory to two pages and handed back `0x0`. On the memory we share with Emscripten that is
the null page and the static data region, so the first use corrupts CPython's heap.

This is this target's equivalent of desktop's "always `invokeExact`" — the one rule that is not a
preference.

`Pointer(addr)` is the way in. Its constructor is public and `loadByte`/`storeByte`/`loadInt`
compile to plain `i32.load`/`i32.store` with no allocator involved, so reading an address CPython
returned is free.

## Never let JSPI reach this embedding

`cpython.mjs` deletes `WebAssembly.promising` and `WebAssembly.Suspending` before it calls the
Emscripten factory. **Do not remove those two lines**, and do not restore the properties for the
lifetime of the process.

Emscripten decides whether to use JavaScript Promise Integration by **runtime feature detection**,
not at build time — CPython's link line has no `-sJSPI`. When it detects JSPI it turns the blocking
syscalls into suspending imports, and it installs the matching `WebAssembly.promising` wrapper on
exactly one export: `main`.

**This library never calls `main`.** Bring-up is `Python3.initialize()` issuing `Py_Initialize` as a
direct wasm call, like every other C API call. So with JSPI live there is no promising frame anywhere
on the stack and the first blocking syscall tears the process down:

    select.poll().poll(0)   ->  SuspendError: trying to suspend without WebAssembly.promising

That is not survivable and it does not look like what it is. The SuspendError unwinds CPython's C
frames without running `Py_END_ALLOW_THREADS`, so the outer `withGIL{}`'s `PyGILState_Release` then
hits `Py_FatalError: thread state ... must be current when releasing` → `abort()` → the wasm
`unreachable` opcode. What you see reported is `RuntimeError: unreachable`, three hops from the
cause, and the wasm suite dies as *"process exited unexpectedly"* rather than as a red test.

It cost this repo two sections of `docs/design/upcall-async-design.md` (§9.5, §14.4) to misdiagnose as
"wasm cannot do `asyncio`". It could not do `asyncio` because `selectors.py` calls
`select.poll().poll(0)` at import time to pick its `DefaultSelector` — which is also why `import
select` was fine and `import selectors` was not. §15 has the measurement.

Measured, same `python.wasm`, `select.poll().poll(0)`: Node 22 (no JSPI) returns `[]`; Node 26 (JSPI
on by default, and the Gradle runner's version) raises `SuspendError`. There is no V8 flag to turn
JSPI off on Node 26 — it is shipped, not experimental — so suppressing the feature detection is the
only lever. The synchronous path is the one this library wants anyway: every call arrives from
Kotlin as a plain synchronous wasm call, so a blocking syscall has to actually block.

**The known cost, recorded rather than hidden: this mutates a host intrinsic globally.** Under the
Node test runner that is contained — nothing else in the process wants JSPI, and the 344-test wasm
suite is the evidence. On a browser page shared with *another* wasm module that uses JSPI, deleting
these would break that module. The honest fix for such a page is an Emscripten build that does not
feature-detect, not a narrower delete, because `__maybe_poll_async` re-reads
`WebAssembly.promising` on every call.

**Measured in a browser now, and both halves of that paragraph are worth separating.** `:sample` has
a browser wasm target and it runs (ROADMAP §10). Same browser, Chromium 150:

| | `typeof WebAssembly.promising` |
|---|---|
| a page that does not load the app | `function` |
| the app's page, 20 s after load | `undefined` |

So the delete is real rather than a no-op — this browser ships JSPI and the property is gone
page-wide. Nothing on that page broke: Compose came up (`canvases=1`), skiko rendered, Kotlin/Wasm
ran. CPython, Kotlin/Wasm and skiko are three wasm modules on one page and **none of the other two
uses JSPI**, which is the whole reason it is survivable. The warning is unchanged in force; what has
changed is that its scope is now observed — it costs nothing until a page loads a JSPI consumer.

## The memory is shared because Kotlin *imports* it

Kotlin 2.4.20-Beta2 declares `intrinsics.memory` as an **import** (`min=0, max=none`) rather than
defining one. 2.4.10 and earlier exported one instead, which could not be made to work: an
Emscripten memory import and a Kotlin `@WasmImport` need each other's instantiation to come first.

A wasm memory import accepts any memory whose limits sit inside its own, and an unbounded import
accepts everything — so `intrinsics.memory` takes Emscripten's `Module.wasmMemory` unchanged.
**The whole data-path integration is that one substitution**, done in `build.gradle.kts` against
the generated `*.import-object.mjs`. Emscripten is built exactly as it would be alone: no
`-sIMPORTED_MEMORY`, no binary patching.

(There is now a second substitution, on the generated *entry* module, and it exists for upcalls
rather than for the memory — see "Upcalls work" below. This section used to say "the whole
integration" without qualification, and that stopped being true when `ProxyTypeFactory` was wired
up.)

Handing the memory over costs nothing, because Kotlin never uses it: `min_pages = 0`, all data
segments passive (they feed WasmGC arrays through `array.new_data`), and the page count was
measured at 0 across string interop, collections, exceptions, a 10 MiB `ByteArray`, a 100k-object
graph, the `ArrayBuffer` bridge and coroutines.

It also survives growth. CPython links `-sALLOW_MEMORY_GROWTH`, and the memory *will* grow
underneath Kotlin. A JS `TypedArray` view detaches when that happens; a wasm memory import does not.

## `Py_ssize_t` is 32-bit here, and it is the only ABI divergence

`EmbedAPI.kt` types `Py_ssize_t` as Kotlin `Long`, which is right on every other target. On wasm32
it is `i32`. **Fourteen functions are affected** — `PyList_New/Size/GetItem/SetItem/Insert`,
`PyTuple_New/Size/GetItem/GetSlice/SetItem`, `PyDict_Size`, `PySet_Size`, `PyObject_Size`,
`PyObject_Length` — and `EmbedAPI.wasmJs.kt` converts at the boundary through two named functions
rather than inline casts, because the two directions fail in opposite ways:

- `Int.pySsizeToLong()` **sign-extends**. `-1` is the error return of nearly every function in the
  group, and the unsigned widening that is *correct* for pointers here would turn it into
  4294967295 — a value that passes `if (n < 0)` and is then used as a length.
- `Long.toPySsize()` **range-checks instead of truncating**. `PyList_New(0x1_0000_0000)` would
  otherwise become `PyList_New(0)` and hand back an empty list: a wrong answer rather than a
  failure. Nothing outside `Int` can be legitimate — wasm32's whole address space is 4 GiB.

### Two guards, because the two halves fail differently

Getting the **declaration** wrong is not a compile error. Wasm import types are checked exactly and
at *instantiation*, so a mismatch is a `LinkError` that takes out the whole module: zero tests run
and the message names neither the function nor the type.

    ./gradlew :python-multiplatform:verifyWasmAbiSignatures

parses `python.wasm`'s own type, import, function and export sections and compares every
`@WasmImport` in `bindings.kt` against it. It runs before `wasmJsNodeTest` automatically. Deliberately
breaking one declaration produces:

    wasmJs ABI mismatch -- 1 problem(s). Each is a LinkError at instantiation ...
      PyList_Size: bindings.kt declares (i32) -> (i64), python.wasm has (i32) -> (i32)

This is the same shape as `generateDesktopReachabilityMetadata`: derived from the artefact that
decides, on every run, never from a checked-in copy of the answer. It also asserts that every
`external fun` in the file was parsed — an earlier version required an explicit return type and so
silently skipped the three `Unit`-returning declarations, which is precisely the "green test
measuring its own scope" failure ROADMAP §2 records.

Getting the **conversion** wrong links cleanly, passes every test, and is silently wrong for one
value in a million. That half is `wasmJsTest/.../WasmPySsizeTBoundaryTest`.

## Pointers are 32-bit, so widen through `UInt`

    actual inline fun NativePointer.toRawValue(): Long = toPlatformPointer().toUInt().toLong()

A bare `Int.toLong()` sign-extends. Any address at or above 2 GiB has bit 31 set, and Emscripten's
memory can grow to 4 GiB, so that would turn a valid pointer into a negative `Long` that no longer
round-trips.

## Strings: never `decodeToString()` on a large buffer

Measured, both routes ending with a Kotlin `String` built from the same C address:

| ns per read | 27 B | 4000 B |
|---|---|---|
| `CharArray` + `concatToString()` (ASCII) | **103** | 5 875 |
| `ByteArray` + `decodeToString()` | 405 | **58 271** |
| `UTF8ToString` through JS | 125 | 3 827 |

`decodeToString()` costs about 13 ns/byte on long input against 0.2 ns/byte on short. `Wasm`
therefore scans once — deciding length and ASCII-ness together — and decodes into a `CharArray`
either way.

**Do not assume a JS crossing costs a string copy.** Kotlin/Wasm compiles with
`builtins: ['js-string']`, so a Kotlin `String` *is* a JS string. The earlier claim in this
project's design notes that shared memory makes marshalling "disappear" was wrong for exactly that
reason: the copy it expected to save had never existed.

`char*` → `ByteArray` (the `PyBytes` case) is unambiguous, though: shared memory, no decode, and no
JS route that avoids a copy.

## No composed C shim, and none is wanted

The design once specified `pmp_getattr`, `pmp_exec`, `pmp_list_items` and friends to amortise JS
hops. The hops are gone, so composition has to justify itself on crossing count alone. Measured
against the real interpreter, reading one global 200,000 times:

| | ns |
|---|---|
| naive | 259.5 |
| + interned C strings | 185.4 |
| + module/dict hoisted | **65.2** |
| one crossing, direct `@WasmImport` | **2.9** |

75% comes off through pure-Kotlin optimisation. A composed shim could merge the two remaining calls
and save 2.9 ns. Same verdict ROADMAP §6 reached for desktop, with a wider margin — and it removes
a second build pipeline.

## String arguments: two routes, and the reason each is cheap is *not* the reason on Android

Every `actual` taking a `String` used to `malloc`, encode, copy and `free` per call. All 59
argument positions across 49 functions now go through one of two routes, chosen per argument the
way `desktopMain` and `androidMain` choose — and, checked against them, with the identical verdict
on all 59:

    Wasm.internedUtf8    repeated identifiers: attribute, method, module, type names, dict keys
    Wasm.scratchUtf8     arbitrary content: source text, messages, docs, paths, user data

**The judgement carries over; the reasoning behind it does not.** Measured here, 200 000
iterations, `WasmMarshallingOverheadTest`:

| ns per argument | `"version"` (7 chars) | `"some.deeply.qualified.module.name.with_a_long_attribute"` (54) |
|---|---|---|
| `malloc` + `encodeToByteArray` + copy + `free` (what this target used to do) | **82.7** | |
| `malloc` + `free` alone | 13.0 | |
| `encodeToByteArray` alone | 59.2 | |
| `Wasm.scratchUtf8` — reused buffer, direct write | **30.6** | **157.0** |
| `Wasm.internedUtf8` — cache hit | **22.2** | **26.4** |

And end to end, every row back to back in one run so that only the marshalling differs:

| `PyObject_GetAttrString(sys, "version")` | ns |
|---|---|
| through `bindings`, C string allocated per call | 288.8 |
| through `bindings`, scratch | 221.4 |
| through `bindings`, interned | 237.2 |
| **through the `actual`**, C string allocated per call | **258.0** |
| **through the `actual`**, interned | **229.2** |

Three things in there, two of them against what the other platforms would predict:

- **The expensive part was the intermediate `ByteArray`, not the encode.** `encodeToByteArray()`
  plus a copy is 59.2 ns; writing the same bytes straight into linear memory is what makes scratch
  30.6 ns. A WasmGC array allocation per C API call was most of the cost, and shared memory is what
  lets it go — the destination is CPython's own heap, so there is no staging buffer. That is the
  *opposite* of the original design note's claim that shared memory removes a string copy. It
  removes the allocation.
- **Interning's value is entirely in the string length, and the crossover is high.** For a
  7-character name it is worth 8 ns over scratch — nothing like Android's 15x (2238 → 148 ns).
  For a 54-character one it is worth **6x** (157.0 → 26.4), because a cache hit is flat in the
  length and an encode is linear in it. Both are true, and reporting only the short case would have
  argued interning is barely worth having.
- **The end-to-end gap is smaller than the standalone gap**, because the rest of
  `PyObject_GetAttrString` is CPython's own work. 258.0 → 229.2 ns through the `actual`.

One negative result, recorded because the obvious reading of it is wrong: `BenchmarkTest`'s shared
`PyObject_GetAttrString` row **did not move** across this change (376.6 ns before, 384.0 ns after).
That is a comparison between two whole suite runs on a machine doing other things, against a
per-call difference of ~29 ns; the in-run A/B rows above are the measurement, and the cross-run
figure is not usable for a change this size in either direction.

**Do not read `scratchUtf8`'s address as durable.** Four slots, rotating; the fifth call overwrites
the first. Four is chosen against the surface — the most any function here stages at once is three
(`PyErr_WarnExplicit`, `PyImport_ExecCodeModuleWithPathnames`). A re-entrant call that marshals four
more strings while these are live would overwrite them, exactly as on desktop. `allocUtf8`/`freeUtf8`
remains for buffers the caller owns, and `ProxyTypeFactory`'s type name is one.

## No `EXPORTED_FUNCTIONS` list is needed

`-sMAIN_MODULE` is `LINKABLE`, so Emscripten exports the whole Stable ABI and rejects an explicit
list. All 310 C symbols `EmbedAPI.kt` declares were confirmed present in the ABI build's 8287
exports. What the build *does* need is `wasmExports,wasmMemory` added to
`-sEXPORTED_RUNTIME_METHODS`; without them the exports are in the binary but unreachable from JS,
so there is nothing to hand `@WasmImport`. Neither setting is in PEP 783's ABI-sensitive list.

## Reclamation is automatic, through JS — and it is the only `js(…)` in this source set

The Kotlin/Wasm **stdlib** has no finalisation hook. That much was always true, and was checked
directly against `kotlin-stdlib-wasm-js-2.4.20-Beta2.klib`: no `FinalizationRegistry`, no `WeakRef`,
no `Cleaner`. The conclusion drawn from it — that a `PyObject` dropped without `close()` must leak
here — was wrong. The *host* has all three, and `registerCleaner` now reaches them.

`PyAutoCloseable.wasmJs.kt` hands the cleaner to a JS `FinalizationRegistry` as a `JsReference`,
with a small `CleanupState` (pointer, decref action, done flag) as the held value. The weakly
observed target is the cleaner itself, exactly as `Cleaner.register(this, …)` does on `desktopMain`:
`PyAutoCloseable` holds it and nothing else does, so it dies precisely when the wrapper does.

**Three facts had to be measured first, and the first one is what the design turns on.**

| | measured |
|---|---|
| Does `toJsReference()` *pin* the Kotlin object? | **No.** 200 objects, held from JS only by a `WeakRef` and a registry entry: `alive 0 / 200`. Had it pinned them, this design would be impossible. |
| Does `FinalizationRegistry` fire for a **WasmGC** object? | **Yes.** 200 / 200 callbacks. |
| Can a collection be observed without yielding to the host? | **No.** See below. |

The third is why this target's tests look different from every other target's. On Node 26 / V8 14.6,
with the same object dropped and `gc()` called at each step:

```
same job, after gc()          WeakRef ALIVE     registry callbacks 0
after one microtask           WeakRef ALIVE     registry callbacks 0
after two microtasks          WeakRef ALIVE     registry callbacks 0
after one macrotask           WeakRef CLEARED   registry callbacks 200
```

A `WeakRef` keeps its target alive for the job that created it, and the registry callback is
delivered as a *task*. `FinalizationRegistry.prototype.cleanupSome()`, which would have made it
synchronous, has been removed from V8 — `--harmony-weak-refs-with-cleanup-some` is rejected as an
unrecognised flag, and `setFlagsFromString` reports the same. **So there is no synchronous drain on
this platform, at all.** `commonTest`'s `collectorTest` exists for that: on every other target it is
a blocking loop, and here it is a chain of host turns. `GCLeakTest`'s three cases pass on this
target now, and they pass for the reason they pass elsewhere rather than because anything about them
was relaxed.

`forceGC()` is `globalThis.gc()`, which `node --expose-gc` installs; `build.gradle.kts` adds that
flag to the wasmJs test task's `nodeJsArgs`. Where it is absent, `forceGC()` degrades to a no-op and
the bounded loop gives up rather than pretending.

**This file is the only `js(…)` in `wasmJsMain`, and it has to be.** Everything else here is
`@WasmImport`, which is a wasm-to-wasm call with no JavaScript frame — but `@WasmImport` carries
primitives only, and what has to cross here is a *reference* to the Kotlin object whose reachability
is the whole question. There is no handle-table trick that avoids it. The cost is confined to
construction and destruction; nothing on the C API call path goes through it:

| ns / op | |
|---|---|
| `registerCleaner` + `close()` — the whole hook | **88.6** |
| `toJsReference()` alone — the externref crossing | 44.1 |

(50 000 iterations each, after an equal warm-up. Reading the *first* row recorded without a warm-up
gave 300 ns and made the second look three times cheaper than a superset of itself.)

`WasmCleanerStats` carries the observability half: `registered`, `released`, `finalized`, and
`outstanding = registered - released`, which is how many wrappers still hold a CPython reference.
`automatic` is false only on a host with no `FinalizationRegistry`, where `close()` is the sole
route and `outstanding` is a leak count rather than a live-object count.

One property this target has that the threaded ones do not: **the callback cannot arrive inside a
Python call.** JS tasks run only once the stack has unwound and every call into CPython here is
synchronous, so the decref never re-enters the interpreter from within another call — the hazard
ROADMAP §1 spent a section on.

## Upcalls work, and cycles are collected

`ProxyTypeFactory` builds a real `PyType_FromSpec` heap type with `tp_traverse`, `tp_clear` and
`tp_dealloc` filled by Kotlin. `wasmJsTest/.../WasmCycleCollectionTest` runs the same pair of cases
`desktopTest/CycleCollectionTest` does and both pass: a Kotlin↔Python cycle is broken by
`gc.collect()`, and 100 proxies that die *without* a cycle release their `HandleTable` entries and
leave the heap type's refcount exactly where it started.

The call path has no JavaScript in it. `@WasmExport` produces a raw wasm export;
`WebAssembly.Table.prototype.set` accepts it into CPython's own `__indirect_function_table` — a
funcref is a funcref whatever instance produced it — and the index **is** the C function pointer.
CPython reaches Kotlin through `call_indirect` at 3.1 ns, against 10.9 ns for the
`addFunction`-plus-JS-closure route the original design specified.

### Three things this costs, all of them structural

**1. The executable module must declare the trampolines. The library cannot.**
`@WasmExport` is honoured only in the compilation that produces the `.wasm`. Measured, not inferred:
the identical annotation on the identical function exports from `wasmJsTest` and does not from
`wasmJsMain`, whose klib is linked in — the test binary's export section came out holding
`startUnitTests` and nothing else. So an application has to write three delegating lines itself; see
`ProxyTypeExportNames`, and `wasmJsTest/.../ProxyTypeExports.kt`, which is that file. Having the
Gradle plugin generate it is the obvious next step and is not done.

**2. Registration is a JS startup step, so the build patches the entry module.**
A table index cannot be obtained from inside Kotlin, and `WebAssembly.Table.set` needs the funcref
out of `wasmInstance.exports`. `cpython.mjs` cannot fetch that itself — it is imported *by* Kotlin's
import object, so importing the entry module back would be an ES cycle across a top-level await. The
`doFirst` in `build.gradle.kts` therefore appends `pmpSetKotlinExports(exports)` to the generated
entry module. That is a **second** substitution; this file used to say the integration was one.

**3. Kotlin/Wasm has no `call_indirect`, so calling a C function *pointer* goes through JS.**
`tp_traverse` is handed a `visitproc` and `tp_dealloc` has to reach its type's `tp_free`; both are
table indices. `pmpCallVisit`/`pmpCallFree` in `cpython.mjs` do `table.get(i)(...)`. Both are cold —
inside a cyclic collection, or a proxy's deallocation — and neither is on the downcall path or on
the path CPython uses to *enter* Kotlin.

**`call_indirect` is statically typed and does not coerce.** A `(i32) -> i32` export called through a
`(i32, i32, i32) -> i32` slot raises `RuntimeError: null function or function signature mismatch` at
runtime, not compile time. Trampoline arity is a correctness requirement, which is why the three
signatures are written out in `ProxyType`'s documentation next to the C ones.

Prefer slots CPython invokes directly (`PyType_FromSpec`) over `PyMethodDef` entries: the latter go
through `_PyEM_TrampolineCall`, whose wasm fast path never installs on this toolchain pair (an
upstream defect — the `EM_JS` initialiser needs `wasmTable`/`wasmMemory` before they exist and the
`LinkError` is swallowed by a bare `catch`). The cost is symmetric, though — every C extension pays
the same JS frame, measured at 14 ns over `abs()`.

One incidental trap worth recording: **`Module.UTF8ToString` is not available.** This build's
`-sEXPORTED_RUNTIME_METHODS` lists `wasmExports` and `wasmMemory` and nothing else, deliberately,
because both are outside PEP 783's ABI-sensitive set. Reaching for any other Emscripten runtime
helper from the glue fails as `undefined is not a function` inside a wasm import, which arrives in
Kotlin as a bare `JsException` with nothing in it. `cpython.mjs` reads C strings off
`M.wasmMemory.buffer` itself.

## A browser bundle needs four things this library must hand it

Node and a browser differ in exactly one place — the **filesystem** — and in three build steps. All
four are the library's to supply; ROADMAP §10 has the reasoning for why none of them is the
consumer's problem to discover. `stageWasmBrowserRuntime` in `build.gradle.kts` is the task.

1. **`cpython.mjs` must be in the consumer's webpack context.** The *generated import object* of any
   module that links this library carries `import * as ... from './cpython.mjs'`, and nothing
   propagates the file — it is not in the wasmJs klib's resources either. Missing, webpack fails with
   `Module not found: Error: Can't resolve './cpython.mjs'` and that is the whole error.
2. **`python.mjs` and `python.wasm` must sit next to the page.** `cpython.mjs` loads the glue with
   `import(/* webpackIgnore: true */ "./python.mjs")` — dynamic so that `node:fs` is never resolved
   in a web bundle, and webpack-ignored because Emscripten's 567 KB of glue branches on `require`,
   `node:fs` and `import.meta.url` at runtime and must not be bundled.
3. **The standard library travels as `python3.<minor>.zip`.** There is no NODEFS in a browser.
   `cpython.mjs` fetches the zip into MEMFS at `/lib/python3<minor>.zip` during `preRun`, under
   `addRunDependency`, and sets **no `thisProgram`** so that `sys.prefix` stays `/`. That is what
   CPython's own `Tools/wasm/emscripten/web_example/python.worker.mjs` does.
4. **Two substitutions on the compile-sync output**, between the sync and webpack:
   `intrinsics.memory` → Emscripten's `wasmMemory`, and `pmpSetKotlinExports(exports)` into the
   generated entry module. **The second goes *before* `exports._start()`, not appended after it** —
   `_start()` is Kotlin `main()`, and an appended handoff runs after the application has finished.
   Appending was correct for this module's own test bundle, whose entry module has no `_start()`
   call, which is why it survived until an executable existed.

   **That last sentence is also why no test guards it.** `wasmJsBrowserTest` runs the *test* bundle,
   and reverting the placement produces a byte-identical file there — checked, and all ten browser
   tests stayed green. `patchKotlinWasmOutputForCPython` therefore asserts the ordering as a
   postcondition and refuses to write the file; `:sample`'s copy does the same, and that is the only
   build in the repository whose bundle can trip it.

## Running the tests

    ./gradlew :python-multiplatform:wasmJsNodeTest      # the suite
    ./gradlew :python-multiplatform:wasmJsBrowserTest   # what only a browser decides

The browser task is **not** a second run of the suite: it filters to
`python.multiplatform.browser.*` and `WasmSelectorsImportTest`, which are the cases whose subject is
webpack, the absence of a filesystem, or a host intrinsic. 4.5 s warm (16 s if the test bundle also
has to be recompiled and re-bundled). It needs a Chromium-family browser
and **skips with a message** when there is none, the same way a missing interpreter does; `CHROME_BIN`
overrides the probe list in `build.gradle.kts`. `karma.config.d/cpython.js` serves the interpreter to
karma — `webpackCopy` for the glue (the bundle's directory is a fresh temp path every run) and a
proxy for the document-relative stdlib zip.

Needs a CPython Emscripten build. The default location is `<repo>/.caches/wasm-runtime`, which
`./gradlew :python-multiplatform:buildWasmPython` (that is, `tools/wasm/build-cpython.sh`) fills;
`-PwasmPythonDir=` or `PMP_PYTHON_DIR` override it. The task **skips with a message** rather than
failing when it is absent. The script matches `pyemscripten_2026_0` (PEP 783) closely enough to load
a compiled PyPI wheel, and its `verify` step checks the stdlib zip as well as `python.wasm` -- the
zip the old out-of-repo build shipped predated its own patches, so under this library the
interpreter reported `PYEMSCRIPTEN_PLATFORM_VERSION = None`.

**An unpacked `python-multiplatform-wasm-runtime` zip works just as well, and that is what CI is
wired to use.** What the tests need is not the CPython build tree but the five files
`stageWasmBrowserRuntime` stages out of it — `python.wasm`, `python.mjs`, the stdlib zip and the two
glue modules. That was not true until the standard library stopped arriving through NODEFS: under
Node, `sys.path[0]` was `/lib/python314.zip`, a file nothing had ever created, and the stdlib was
really coming from entry 1 — the CPython **source checkout** beside the build directory, which no
artefact carries. `cpython.mjs` now installs the staged zip into MEMFS on both hosts, so

    unzip python-multiplatform-wasm-runtime-<version>.zip -d /tmp/rt
    ./gradlew :python-multiplatform:wasmJsNodeTest -PwasmPythonDir=/tmp/rt

runs the whole suite (344/0/0, measured) against nothing but a published artefact.

`-PrequireWasmRuntime=true` turns every one of those skips — the interpreter, and for the browser task
the absence of a Chromium-family browser — into a failure naming what was missing. Pass it whenever
a *caller* asked for the suite, so that a skip cannot be reported to them as success over zero
tests. `.github/workflows/wasm.yml` passes it; without it that workflow reported green having run
nothing, and did for months.

`verifyWasmAbiSignatures` runs first, automatically, and skips the same way. Run it alone when
`bindings.kt` changes — it is a second or two and it turns "0 tests ran, `LinkError`" into a line
naming the function.

Current state, re-measured rather than carried over from the paragraph above: **403 tests, 0
failed, 0 skipped** (`wasmJsNodeTest`), including the three `GCLeakTest` cases and the four
`WasmFinalizationTest` cases — see "Reclamation is automatic, through JS" above for how those pass.
The "214 tests, 3 failed" this line used to read was the state before that section's fix landed and
had drifted out of step with the "344/0/0" already recorded a few paragraphs up; both are stale now
that the suite has grown further, which is expected — re-run `wasmJsNodeTest` rather than trusting
either number.

`emsdk` is pinned to **5.0.3**, which is what `pyemscripten_2026_0` specifies. Installing another
version replaces `~/emsdk/upstream` **in place** — it is a global setting, not per-project.
