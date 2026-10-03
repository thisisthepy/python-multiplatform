# WebAssembly (`wasmJs`) design

How CPython runs under Kotlin/Wasm in this library, why it has this shape, and what was measured to
get there. The rules a contributor must follow are in
[`python-multiplatform/src/wasmJsMain/README.md`](../../python-multiplatform/src/wasmJsMain/README.md);
the behavioural contract is `docs/SPEC.md` (§0, C-6, M-3, U-5). This document is the design record.
The chronological experiment log it was distilled from — including the stretch where wasm was
"parked" — is [`../archive/wasm-design-experiment-log.md`](../archive/wasm-design-experiment-log.md).

**Status: integrated and experimental.** `wasmJs` is a Kotlin target (`python-multiplatform/build.gradle.kts`),
`src/wasmJsMain` implements the `EmbedAPI` `actual`s, and `src/wasmJsTest` runs the suite on Node
(`wasmJsNodeTest`, the whole suite) and on a browser (`wasmJsBrowserTest`, a filtered subset). `:sample`
has a browser wasm target. `wasmWasi` is not a target (when last assessed it was blocked by Kotlin/Wasm tooling, see the table below).

## Decisions

| Decision | Choice | Why |
|---|---|---|
| How Kotlin reaches CPython | Raw Stable ABI symbols as `@WasmImport`s against the Emscripten module's own wasm exports — **no JS in the call path** | Preserves the exact object model (`NativePointer`, `PyObject`) of every other target; a high-level Pyodide-style JS API (`pyodide.runPython`, `PyProxy`) would replace the whole object model with `JsAny` wrappers and covers almost none of the ~330 C functions |
| Memory | **One shared linear memory.** Kotlin imports Emscripten's `Module.wasmMemory` as its `intrinsics.memory` | Needs Kotlin ≥ 2.4.20-Beta2, whose `wasmJs` modules **import** `intrinsics.memory` (`min=0, max=none`). Releases up to 2.4.10 exported an unbounded memory, which Emscripten cannot import — the earlier plan to patch the `.wasm` memory section (and a YouTrack issue) is obsolete ([`../archive/wasm-youtrack-issue.md`](../archive/wasm-youtrack-issue.md)) |
| Where CPython comes from | **Our own Emscripten build**, `tools/wasm/build-cpython.sh`: CPython **3.14.2**, Emscripten **5.0.3**, matched to the `pyemscripten_2026_0` platform of PEP 783 | No distributor ships a `python.wasm` exporting `wasmExports,wasmMemory`. The version is deliberately not the 3.14.x pinned for native targets (`pythonVersion` in `gradle.properties`); see `docs/design/ecosystem.md` |
| C shim / composition | **None** | The hops that composition amortised are gone; measured gain ≤ 2.9 ns per operation (below). It also removes a second build pipeline |
| `EXPORTED_FUNCTIONS` list | **Not needed** | `-sMAIN_MODULE` is `LINKABLE`, so the whole Stable ABI is exported (all 310 C symbols `EmbedAPI.kt` declared at the time were found in the build's 8287 exports). The build adds `wasmExports,wasmMemory` to `-sEXPORTED_RUNTIME_METHODS`, which is not ABI-sensitive |
| Upcalls (Python → Kotlin) | `@WasmExport` trampolines placed in CPython's `__indirect_function_table` with `WebAssembly.Table.set`; the table index is the C function pointer. Routing is by data (a handle in `self`/`closure`), through the generated table, as on every platform | A funcref is a funcref: `call_indirect` reaches the Kotlin export directly. Measured 3.1 ns vs 10.9 ns for the `addFunction` + JS-closure route |
| Reclamation | `registerCleaner` over a JS `FinalizationRegistry` + `WeakRef`, reached through `toJsReference()` | The Kotlin/Wasm stdlib has no finalisation hook, but the host does; `toJsReference()` does not pin the object (200 objects, `alive 0 / 200`) |
| `Py_ssize_t` | 32-bit; handled at the boundary | The only ABI divergence of wasm32 (SPEC C-6, `WasmPySsizeTBoundaryTest`) |
| `wasmWasi` | Not a target | Kotlin/Wasm cannot link a C module there and WASI has no JS host to glue them; CPython-on-WASI has no `dlopen`, so users could not add compiled packages, which defeats the purpose |

### Why the architecture is what it is

- Kotlin/Wasm is **WasmGC**: a Kotlin object cannot live in linear memory, so CPython can never hold
  a Kotlin object pointer. The answer is the same handle table as everywhere
  ([`object-lifetime.md`](../design/object-lifetime.md)). Kotlin reads CPython's memory with
  `Pointer(addr)` (plain `i32.load`/`i32.store`).
- Kotlin never uses its own linear memory (0 pages across strings, collections, exceptions, a 10 MiB
  `ByteArray`, a 100k-object graph, the `ArrayBuffer` bridge and coroutines), so handing the memory to
  Emscripten costs nothing. The one exception is `withScopedMemoryAllocator`, which allocates from
  address 0 on top of Emscripten's static data: **it must never be called** (CPython allocates,
  Kotlin dereferences).
- A wasm memory *import* survives CPython growing the memory underneath Kotlin; a JS `TypedArray`
  view would detach.
- Kotlin/Wasm has no `call_indirect`, so *calling* a C function pointer (`tp_traverse`'s `visitproc`,
  `tp_free`) goes through two small functions in `cpython.mjs` (`pmpCallVisit`, `pmpCallFree`). Both are
  cold paths.
- The generated entry module is patched at build time (`patchKotlinWasmOutputForCPython`): one
  substitution for the memory, one for the upcall handoff (`pmpSetKotlinExports(exports)`, before
  `exports._start()`). An application must also declare the three `@WasmExport` trampolines itself —
  `@WasmExport` is honoured only in the compilation that produces the `.wasm` (`ProxyTypeExportNames`;
  generating that file from the Gradle plugin is not done).
- **JSPI must not reach the embedding.** `cpython.mjs` deletes `WebAssembly.promising` /
  `WebAssembly.Suspending`; with JSPI live the first blocking syscall (`select.poll().poll(0)`, which
  `selectors.py` does at import) fails with `SuspendError` and surfaces as `RuntimeError: unreachable`.
  See the wasmJsMain README for the cost of this global mutation.

## Measurements

All from the experiment and the tests that replaced it (Node 24 / Emscripten 5.0.3 unless stated;
current figures: run `WasmCrossingOverheadTest`, `WasmUpcallRouteOverheadTest`,
`WasmMarshallingOverheadTest`; `docs/investigations/cost-table.md` has the cross-platform table).

| Question | Result |
|---|---|
| Direct `@WasmImport` vs JS trampoline, 10 M calls of a trivial C function | **5.0 ns** vs 13.6–16.9 ns |
| One real crossing on CPython (`PyErr_Occurred`) | 2.9 ns |
| Bulk read, 1000 `i32`: shared memory `Pointer.loadInt()` vs `Module.HEAP32` | 0.7 vs 7.1 ns per element |
| Composition against the interpreter, reading one global (200 000×): naive / + interned strings / + hoisted module+dict | 259.5 / 185.4 / **65.2** ns; a composed call would save 2.9 ns of that |
| Upcall, 10 M indirect calls issued from C: `table.set` vs `addFunction` + JS closure vs C→C control | **3.1** vs 10.9 vs 0.7 ns |
| Kotlin `METH_O` function vs C builtin `abs` through `PyMethodDef` | 87 vs 73 ns (14 ns is the JS frame CPython's own trampoline imposes — see below) |
| String argument, `"version"` (7 chars) / 54 chars: scratch vs interned | 30.6 / 157.0 ns vs 22.2 / 26.4 ns (`WasmMarshallingOverheadTest`, 200 000 iterations) |
| `char*` → `String`, 27 / 4000 bytes: ASCII `CharArray`+`concatToString()` vs `ByteArray`+`decodeToString()` | 103 / 5 875 ns vs 405 / **58 271** ns — never `decodeToString()` a large buffer |

Corrections that came out of measuring (they changed the design):

- Shared memory does **not** make string marshalling "disappear". Kotlin/Wasm strings already are JS
  strings, and what shared memory removes is the intermediate WasmGC `ByteArray` allocation (82.7 ns
  → 30.6 ns per short argument), not a copy.
- Interning is worth much more for long names than short ones (a cache hit is flat in length, an encode
  is linear). The marshalling rules for the other platforms are in
  [`marshalling-design.md`](../design/marshalling-design.md); wasm uses the same two primitives
  (`Wasm.internedUtf8`, `Wasm.scratchUtf8`, four scratch slots).
- CPython's own wasm trampoline for `PyMethodDef` / getset calls never installs on this toolchain pair
  (its `EM_JS` initialiser needs `wasmTable`/`wasmMemory` before they exist and the `LinkError` is
  swallowed by a bare `catch`). Every C extension pays the same JS frame, so the cost is symmetric.
  `WasmCallTrampolineTest` pins this and goes red the day upstream fixes it. Prefer type slots installed
  through `PyType_FromSpec`, which CPython invokes by plain `call_indirect`.

## The interpreter build and the platform tag

`tools/wasm/build-cpython.sh` builds CPython 3.14.2 with CPython's own `Tools/wasm/emscripten` driver
plus the changes that make it claim `pyemscripten_2026_0` (PEP 783):

| | stock Tier 3 build | this build |
|---|---|---|
| Unwinding ABI `-fwasm-exceptions -sSUPPORT_LONGJMP=wasm` at compile **and** link | absent | **present — this is the one that gates loading a compiled wheel** (the side module imports the `__cpp_exception` tag) |
| `PYEMSCRIPTEN_PLATFORM_VERSION` | not defined anywhere in CPython 3.14.2 | defined via a patch to `sysconfig` (`_ALWAYS_STR`; otherwise `int('2026_0') == 20260` silently yields the tag `pyemscripten_20260_wasm32`) |
| `wasmExports,wasmMemory,wasmTable` in `-sEXPORTED_RUNTIME_METHODS` | no | yes (not ABI-sensitive) |
| `lzma`, `zstd`, OpenSSL | missing | **still missing** — ABI-sensitive per Pyodide's flag list but they did not gate the wheel tested; costs `_lzma`, `_zstd`, `_hashlib`, `_ssl` |

Evidence: a real PyPI wheel, `pydantic_core-2.48.0-cp314-cp314-pyemscripten_2026_0_wasm32.whl`, imports
and runs on this build (`validate_python(42) -> 42`, a `ValidationError` raised and caught — the
exception exercises the unwinding ABI), and fails with `tag import requires a WebAssembly.Tag` on the
stock build. This is one wheel and one code path; it does not show that every compiled extension
loads. `WasmCompiledWheelTest` runs it (`tools/wasm/build-cpython.sh wheels` fetches the pinned wheels);
`WasmInterpreterAbiTest` checks the claims, with `tools/wasm/build-cpython.sh stock` as the negative
control. Emscripten is pinned to 5.0.3 because that is what the platform specifies, and installing it
replaces `~/emsdk/upstream` in place (a global setting).

Platform notes that still hold: PEP 776 makes `wasm32-emscripten` Tier 3 from CPython 3.14 and supports
only static linking of the interpreter (CPython cannot be dynamically loaded into another module); the
build yields a `.mjs` + `.wasm` pair, so a JS layer exists for instantiation but not for calls. Pure-Python
wheels work unchanged; compiled third-party extensions load if published for the PEP 783 tag.

## Browser

Node and a browser differ in the filesystem and three build steps, all supplied by the library
(`stageWasmBrowserRuntime`): `cpython.mjs` in the consumer's webpack context, `python.mjs`/`python.wasm`
beside the page, the standard library as `python3.<minor>.zip` installed into MEMFS (no NODEFS in a
browser; no `thisProgram`, so `sys.prefix` stays `/`), and the two substitutions on the compile-sync output.
Details and the postcondition that guards the entry-module ordering are in the wasmJsMain README.

## Not verified / open

- Whether a Kotlin exception thrown inside an upcall unwinds safely through CPython's frames: every
  trampoline in the experiment returned normally or returned `NULL`.
- Whether compiled wheels other than pydantic-core load.
- Compose (`@Composable` from Python) on wasm is `planned` (SPEC B-6, N-2); `suspend` upcalls are
  `planned` on wasm because there are no threads (SPEC U-5).
- Absolute wasmJs nanoseconds depend on suite scope and host lifetime (a filtered run is 55–74%
  slower in pure-Python rows); quote ratios, or full-suite figures labelled as such
  ([`downcall-design.md`](../design/downcall-design.md), "Warmup").
- Test count: the wasmJsMain README records 403 tests, 0 failures, 0 skipped on `wasmJsNodeTest` and says
  to re-run rather than trust the number; I did not re-run it.
