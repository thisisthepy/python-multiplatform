# WebAssembly (WASM) Feasibility and Design

This document details the feasibility and architectural design for supporting WebAssembly (WASM) in this Kotlin Multiplatform library, specifically addressing the challenges of embedding CPython 3.13 in a Kotlin/Wasm environment.

## 1. Kotlin/Wasm Target Reality

Based on the project's Kotlin version **2.0.20**:
- **Supported Targets:** Kotlin provides both `wasmJs` (for the browser and Node.js with JS host) and `wasmWasi` (for standalone Wasm environments). Both are classified as Alpha in 2.0.20 (though `wasmJs` matures in later 2.x releases).
- **JS Interop:** Kotlin/Wasm uses the WebAssembly Garbage Collection (WasmGC) proposal. It interoperates with JavaScript via `external` declarations and the `JsAny` type hierarchy.
  - **Strings:** Translated seamlessly between Kotlin `String` and JavaScript `string`. They are not passed as `const char*` raw pointers.
  - **Pointers & Opaque Handles:** WebAssembly linear memory pointers (like `PyObject*`) are simply 32-bit integers (`Int` or JS `Number`). They cannot be directly mapped to Kotlin's WasmGC objects. 
  - **Callbacks:** Kotlin lambdas or `@JsExport` functions are exposed as JavaScript function objects, not raw C function pointers.

## 2. The Two Candidate Approaches for `wasmJs`

Since Kotlin/Wasm and a WASM CPython build are separate modules that do not share linear memory, they must communicate through the JS host environment. 

### (a) Pyodide's High-Level JS API
This involves using `pyodide.runPython`, `pyodide.globals`, and `PyProxy` objects.
- **API Coverage:** Almost **none** of the ~330 `expect` C-API functions can be implemented this way. Pyodide abstracts away the C API. 
- **Object Model:** This approach completely destroys the `PyObject`/`PyType` model the library is built on. WASM would have to use `JsAny` wrappers of `PyProxy` instead of `NativePointer`.
- **Verdict:** **Impractical.** It would result in a fundamentally different architecture and semantics for WASM compared to Android, Desktop, and Native.

### (b) Raw CPython C Symbols Exported from a WASM Build
This involves reaching Emscripten's `EXPORTED_FUNCTIONS` (e.g., `Module._PyList_Size`), passing memory addresses as 32-bit integers.
- **API Coverage:** Can implement the entire `expect` surface, mapping 1-to-1 with the Stable ABI.
- **Object Model:** Preserves the exact same `NativePointer` and `PyObject` semantics as other platforms.
- **Per-Call Cost:** High. Every call must cross from Kotlin WasmGC -> JS -> Emscripten Linear Memory. However, since the signature census shows only 14 simple ABI shapes, this crossing can be tightly optimized.

## 3. Does Pyodide Export What We Need?

**No, a stock Pyodide build does not export the full Stable ABI.** 
Pyodide relies on Emscripten's dead-code elimination. Symbols not used by Pyodide's internal bindings or explicitly listed in `EXPORTED_FUNCTIONS` are stripped. Furthermore:
- **CPython 3.13 Status:** The `wasm32-emscripten` build was dropped from official CPython support in 3.13. (Pyodide maintains it out-of-tree). `wasm32-wasi` was promoted to Tier 2 support.
- **Custom Build Required:** To use the raw C API approach, we would need to generate a custom Emscripten build (or a custom Pyodide distribution) that explicitly adds our ~330 required C API symbols to `EXPORTED_FUNCTIONS` (or use `EMSCRIPTEN_KEEPALIVE`).

## 4. Pointer Width and `NativePointer`

WASM32 uses **32-bit pointers**. 
- The current abstraction `data class NativePointer(val address: Any)` uses `Any`. Passing a 32-bit integer pointer into `Any` will box it (as a `Double` on JS or a boxed primitive in WasmGC), adding allocation overhead.
- The `toRawValue(): Long` signature assumes 64-bit addresses. Since a 32-bit unsigned integer fits perfectly into a 64-bit `Long`, the abstraction mathematically holds. However, Kotlin/Wasm code implementing `toRawValue()` will need to cast the JS/Wasm 32-bit `Int` to a Kotlin `Long` carefully to avoid sign-extension bugs.

## 5. Upcalls on WASM (Python calling Kotlin)

Python expects a raw C function pointer (an integer address pointing to executable memory). We can preserve the "routing by data" design from other platforms, but the plumbing is different:
1. We write our ~4 static trampoline shapes in Kotlin and export them to JS using `@JsExport`.
2. In JS glue code, we use Emscripten's `addFunction(jsFunction, signature)` to inject the JS function into Emscripten's WebAssembly function table.
3. `addFunction` returns an integer (the table index). This integer *is* the C function pointer.
4. We pass this integer to CPython (e.g., in `tp_new` or `PyCFunction_NewEx`). When CPython calls it, Emscripten routes the call out to JS, which calls our Kotlin WasmGC function. The context pointer (`self` or `closure`) is passed as an integer, allowing us to route to the correct Kotlin object.

## 6. `wasmWasi`

**Separate implementations are required.** 
Pyodide and Emscripten rely entirely on JavaScript glue code. If the project targets non-browser WASM via `wasmWasi` (pairing with CPython's Tier 2 `wasm32-wasi`), there is no JS host. 
Currently, Kotlin/Wasm cannot dynamically link directly to an arbitrary C WebAssembly module without a JS host bridge, as the Wasm Component Model and native static linking are not yet mature enough in the Kotlin toolchain. Thus, `wasmWasi` is genuinely a second, entirely different port that is currently **blocked** by Kotlin/Wasm tooling limitations.

## 7. Recommendation and Staged Plan

**Recommendation:** Pursue **Approach (b) — Raw C Symbols via JS Bridge** for the `wasmJs` target. It is the only path that preserves the multiplatform object model. Ignore `wasmWasi` until Wasm Component Model linking matures in Kotlin.

**Caveat:** WASM will have permanently higher per-call overhead than Native/JVM due to the WasmGC ↔ JS ↔ Emscripten boundary crossings.

### Staged Plan
1. **Toolchain Customization:** Create a custom Pyodide/Emscripten build script that passes all ~330 `EmbedAPI` functions to Emscripten's `EXPORTED_FUNCTIONS`.
2. **Downcalls (Kotlin to Python):** Write a Kotlin `external` module that binds to `Module._Py*` functions on the Emscripten JS object. Implement `EmbedAPI.wasmJs.kt` to call these.
3. **Upcalls (Python to Kotlin):** Use Emscripten's `addFunction` via JS interop to register 4 static Kotlin `@JsExport` trampolines, returning function pointers to hand to Python.
4. **Pointer Optimization:** If boxing in `NativePointer(val address: Any)` proves too slow in WasmGC, consider refactoring the `expect` class to use a concrete `Long` across all platforms (which JVM/Native already use natively) to avoid allocations.

---

## Addendum: correcting two framing errors

### Kotlin/Native once had a wasm target, and it was removed

The analysis above assumed Kotlin/Wasm is the only route. That is true today, but not for a
fundamental reason.

Kotlin/Native used to ship a `wasm32` target built through LLVM, producing linear-memory
WebAssembly with a C ABI — the same memory model an Emscripten build of CPython uses. That target
could in principle have used `cinterop`, exactly as the iOS target does today. JetBrains
**deprecated it in Kotlin 1.8.20 and removed it in 1.9.20**, in favour of the Kotlin/Wasm
toolchain, which skips LLVM and is built on WasmGC.

The consequence is that the JS bridge is forced by *tooling*, not by WebAssembly itself:

- Kotlin/Wasm is WasmGC-based, so its memory model differs from C's linear memory.
- Kotlin/Wasm has no `cinterop` at all. `wasmJs` offers JS interop; `wasmWasi` offers WASI syscalls.

This project targets Kotlin 2.0.20, so the `wasm32` target is long gone and pinning to 1.9.10 is
not viable — the rest of the toolchain requires 2.0.x.

### The dependency is CPython's own WASM support, not Pyodide

Pyodide is a downstream distribution carrying its own patches. A library that intends to be a
standard multiplatform binding should build CPython from source for a WASM target rather than
depend on Pyodide.

Where CPython's own support actually stands:

| Target | CPython 3.13 | CPython 3.14 |
|---|---|---|
| `wasm32-wasi` | Tier 2 (officially supported) | Tier 2 |
| `wasm32-emscripten` | Not a PEP 11 platform | Tier 3, via PEP 776 |

[PEP 776](https://peps.python.org/pep-0776/) is Active, targets 3.14, and formalises Emscripten at
Tier 3. Three of its details bear directly on this design:

1. **Only static linking is supported.** The PEP states it is only supported to statically link the
   interpreter; dynamic linking is unsupported because `EM_JS` functions behave differently in
   dynamic builds. CPython therefore cannot be dynamically loaded into another WebAssembly module.
2. **The build produces an `.mjs` plus `.wasm` pair.** A JavaScript runtime layer is intrinsic to
   the Emscripten build; it is not something an integration can route around.
3. **No official python.org distribution is mandated** — binaries continue to come from downstream,
   i.e. Pyodide. Avoiding Pyodide as a *dependency* therefore means building CPython from source
   ourselves, not that an upstream binary exists to use instead.

Because Kotlin/Wasm cannot statically link C code, and CPython cannot be dynamically linked into
another module, the browser architecture is necessarily three parts: the CPython Emscripten module,
the Kotlin/Wasm module, and JavaScript coordinating them. That holds regardless of which approach
is taken.

`wasm32-wasi` is the better-supported target on the CPython side, but there is no JavaScript host to
act as glue, and Kotlin/Wasm's `wasmWasi` cannot link a C module. Bridging the two awaits the WASM
Component Model.

### Practical consequence

Official Emscripten support arrives in **CPython 3.14**. This project is pinned to 3.13, where
Emscripten has no PEP 11 status at all. WASM support therefore implies moving the embedded
interpreter to 3.14 or later.

---

# Why WASM is parked, and what it would look like

Recorded so the reasoning survives. Nothing here is implemented.

## The blocker is Kotlin's toolchain, not WebAssembly

Every other platform calls CPython directly — cinterop on iOS and androidNative, Panama on
desktop, JNI on Android. Kotlin/Wasm cannot: `wasmJs` offers only JS interop and `wasmWasi`
only WASI syscalls, and neither can link a C library.

Kotlin/Native once had a `wasm32` target that went through LLVM and emitted linear-memory
wasm — the same memory model Emscripten's CPython uses, which is exactly what would have made
cinterop work. It was deprecated in 1.8.20 and removed in 1.9.20. So the JS bridge is a
consequence of a toolchain decision, not of WebAssembly.

**The deeper barrier is memory, not calls.** Two wasm modules can call each other directly if
the host wires module B's exports to module A's imports at instantiation — JS is needed once,
not per call. But Kotlin/Wasm is WasmGC: its objects live on a managed heap, and it has no way
to read another module's linear memory. CPython's entire API is pointers into exactly that
memory. So JS is not a convenience in the call path — it is where the marshalling has to
happen.

JetBrains has a **Multiple Memory** proposal under consideration that would address this, and a
C-interop example PR against `kotlin-wasm-examples` was opened in March 2024. Neither has a
committed date, and neither appears on the published roadmap, so neither can be planned around.

## emscripten, not wasi

| | `wasm32-emscripten` | `wasm32-wasi` |
|---|---|---|
| CPython support | Tier 3 from 3.14 (PEP 776) | **Tier 2** |
| bridge between the two modules | **JS exists** | none — needs Component Model, which Kotlin does not emit |
| C extension modules | **dlopen works** | no dlopen in preview1; extensions must be linked in |
| matches this project | **browser; the sample already targets `wasmJs`** | server/edge |

The decisive row is the third. This project exists to share libraries, and on WASI a user could
not add a compiled package after the fact — the interpreter would have to be rebuilt. That
guts the goal on that platform, which outweighs WASI's better support tier.

The second row is nearly as decisive: WASI has no JS host, so there is no mechanism at all to
connect a Kotlin/Wasm module to a CPython module there.

## The shape it would take

Put our C code inside CPython's module, so it can handle pointers, and keep the boundary to
integers.

    +- Emscripten module (one linear memory) -----+
    |  CPython + our C shim                       |   pointers handled here
    +---------------------+-----------------------+
                          | i32 only
                    +-----+-----+
                    |  JS glue  |                     string copying only
                    +-----+-----+
                          |
                 +--------+---------+
                 |  Kotlin/Wasm     |
                 +------------------+

Pointers are 32-bit on wasm32, so a `PyObject*` fits in a Kotlin `Int`. The shim exposes
composed operations, the same ones measured on Android:

    int  pmp_getattr(int obj, int namePtr);              // lookup + error handling, one crossing
    int  pmp_exec(int codePtr);                          // __main__ + globals + run
    int  pmp_list_items(int list, int outPtr, int cap);  // bulk: fills a caller buffer
    int  pmp_str_utf8(int obj, int outLenPtr);
    void pmp_decref(int obj);
    int  pmp_scratch(void);                              // reusable buffer address, once

**Interning applies here and matters more.** A Kotlin string lives on the WasmGC heap and a C
string in linear memory, so copying costs a JS hop — but attribute and module names are
repeated literals, so caching the linear-memory address makes every call after the first cost
nothing. This is the same fix measured on Android at 2238 ns → 148 ns, against a hop that is
more expensive here than JNI's.

**Bulk is where composition earns its keep on this platform.** It was rejected on desktop and
superseded by interning on Android; here it is the only mitigation left, because
`Module.HEAP32.subarray(p, p+n)` fetches N items in one hop where per-element access would cost
N.

Nothing above changes the layering: `wasmJsMain` supplies `actual`s for the same EmbedAPI
`expect`s, and the object model does not move.

## What survives if the toolchain improves

If Multiple Memory lands, the C shim stays — composition is wanted for its own sake — and only
the JS glue is replaced by direct memory access. The `actual` signatures do not change. That
bounds the throwaway work to a few dozen lines of glue, which is the argument for this shape
over waiting indefinitely.

## Unresolved

- `NativePointer` holds `Any`, so a wasm32 `Int` boxes on every conversion, and
  `toRawValue(): Long` needs sign-extension care.
- CPython must be built with our shim and an explicit `EXPORTED_FUNCTIONS` list; a stock
  Pyodide build strips the C API by dead-code elimination. That is a second build pipeline.
- Upcalls: `addFunction(jsFn, sig)` gives a callable function pointer, but re-entering
  Kotlin/Wasm from it goes through JS. Unmeasured, and likely worse than every other platform.
- Lifetime: whether Kotlin/Wasm offers a GC hook equivalent to `Cleaner`/`createCleaner` has not
  been checked. Without one, only explicit `close()` works.

## Start condition

Not now. `wasmJsMain`'s `actual`s cannot be written until it is settled what they implement —
§1/§4 (GIL and automatic release) and §7 (upcall shape) are both in flight, and building against
them now means building twice.

## Packaging: a side module, against our own CPython

Two independent choices, easily conflated:

1. **How our C code is packaged** — statically linked into CPython, or a side module loaded by
   `dlopen` at import time.
2. **Where CPython comes from** — built by us with Emscripten, or Pyodide's distribution.

### Side module, on both counts of its own

| | statically linked | **side module** |
|---|---|---|
| changing one binding | rebuild all of CPython | rebuild our module |
| release cadence | tied to CPython's | independent |
| shape | a CPython fork, effectively | an ordinary extension module (`PyInit_*`) |
| runtime | no indirection | dynamic-link indirection (small) |

Having to rebuild the interpreter to change a binding is the same trap this project already hit
elsewhere — a `.def` edit needing a forced link and staging pass before it reached a device. An
extension module is what CPython already has a mechanism for; we are not doing anything unusual.

### Our own CPython, and no attempt at Pyodide compatibility

The earlier draft argued for keeping Pyodide compatibility in reach, on the grounds that Pyodide
*is* the package ecosystem on the web. That argument does not survive contact with how Pyodide
wheels are tagged: `cp312-cp312-pyodide_2024_0_wasm32` pins the Python version, the Emscripten
version **and** Pyodide's own ABI together. What you get is not "the Python ecosystem" but
"packages someone built for exactly this Pyodide release".

So the choice is not ecosystem-versus-no-ecosystem. It is whose tag to be locked to, and
targeting Pyodide would mean the web platform tracking their release cadence while every other
platform is pinned to 3.14 from source. That is a real cost for a conditional benefit.

The loss from building our own is narrower than it first appears:

| | on our own build |
|---|---|
| pure-Python wheels (`py3-none-any`) — no ABI tag | work unchanged |
| C extensions (numpy, pandas, lxml, cryptography) | must be built for our ABI |

And the second row is true of Pyodide too, for anything outside the list they happen to have
built. Pyodide's advantage is a prebuilt catalogue, not a different rule.

**Decision: our own Emscripten build of CPython 3.14, packaged against by a side module.**
Compiled third-party extensions are a known limitation on this platform, recorded rather than
solved.

### Correction: PEP 783 unbinds the tag from Pyodide

The section above is right about the decision and wrong about its cost. It was written from the
`pyodide_2024_0_wasm32` tag and concluded that any wheel compatibility means being locked to a
Pyodide release. That stopped being true.

[PEP 783 — Emscripten Packaging](https://peps.python.org/pep-0783/) is **Accepted**. It replaces
the Pyodide-owned tag with a standard platform tag series that PyPI accepts:

```
pyodide_2024_0_wasm32          Pyodide's tag, Pyodide's platform
pyemscripten_2026_0_wasm32     standard platform tag (PEP 783)
```

Three things follow, and they change the trade-off rather than the conclusion.

**The platform is versioned per Python feature release, not per distribution release.** The
current series is `pyemscripten_2024_0` (3.12), `pyemscripten_2025_0` (3.13), `pyemscripten_2026_0`
(3.14). The earlier worry — "the web platform tracks Pyodide's cadence while every other platform
is pinned to 3.14" — does not apply: the platform version *is* the Python version. Pinning to 3.14
from source and claiming `pyemscripten_2026_0` are the same act. The patch component exists as
an escape hatch the PEP hopes never to use.

**Compatibility is a build recipe, not a distribution.** The PEP states that the tags "can be used
by Python interpreters compiled and linked with the specified version of Emscripten and with the
specified ABI-sensitive flags". So our own build can claim the platform. The ABI-sensitive surface
the PEP enumerates:

| | |
|---|---|
| Emscripten compiler version | pinned per platform version |
| statically linked libraries | fixed set |
| stack unwinding ABI | fixed selection |
| dependency lookup handling | specified |
| `-pthread` | prohibited |
| `-sWASM_BIGINT` | required |

`-sWASM_BIGINT` is worth noting for us specifically: it is what makes an i64 cross the JS boundary
as a BigInt rather than being split into two i32s, which is the same `NativePointer` width question
§4 above raises. The platform requires the setting we would have wanted anyway.

**The full specification currently lives in Pyodide's documentation**, which the PEP references
normatively while noting the wording was chosen to be "more forwards compatible to a future where
the definition of the platform moves upstream". So Pyodide still *authors* the spec even though it
no longer *owns* the tag. That is a real residual coupling, but it binds a flag list, not a runtime.

#### What this changes

| | earlier draft | with PEP 783 |
|---|---|---|
| what the tag pins | Pyodide release | Python version + a published flag set |
| our own build + C-extension wheels | mutually exclusive | **compatible, if we match the flags** |
| Pyodide as a dependency | the price of wheels | not required |
| pure-Python wheels | work unchanged | work unchanged |

The decision stands — our own Emscripten CPython 3.14 — but the recorded limitation was
overstated. Compiled extensions are not lost by building our own; they are lost only if we build
with flags that diverge from `pyemscripten_2026_0`. **Matching that flag set should be a build
constraint from the start**, because retrofitting it means rebuilding the interpreter.

Evidence that the path is real rather than paper: [pypa/packaging #804](https://github.com/pypa/packaging/pull/804)
implements the tag handling, [pypi/warehouse #19804](https://github.com/pypi/warehouse/pull/19804)
adds PyPI support, [maturin #3163](https://github.com/pyo3/maturin/pull/3163) adds it to a build
backend, and [pydantic publishes Emscripten wheels to PyPI under it](https://pydantic.dev/articles/emscripten-wheels-pydantic).

#### Resolved: the flag list, read off the source rather than the rendered page

`pyodide.org/en/stable/development/abi.html` still returns 403 to automated fetching. The same
documents are in the repository and `raw.githubusercontent.com` serves them, which is how the
following was read. Sources are `github.com/pyodide/pyodide` at tag **`314.0.4`** —
`docs/development/abi/314.md`, `docs/development/abi/flags.md`, `Makefile.envs` — and
`github.com/pyodide/pyodide-build` `pyodide_build/config.py`.

`Makefile.envs` at that tag pins the whole platform in four lines:

```
PYODIDE_VERSION ?= 314.0.4
PYODIDE_ABI_VERSION ?= 2026_0
PYVERSION ?= 3.14.2
PYODIDE_EMSCRIPTEN_VERSION ?= 5.0.3
```

| | `pyemscripten_2026_0` (3.14) | for comparison, `2025_0` (3.13) |
|---|---|---|
| Emscripten | **5.0.3** | 4.0.9 |
| unwinding | `-fwasm-exceptions -sSUPPORT_LONGJMP=wasm`, at **compile and link** | same |
| `-sWASM_BIGINT` | required, but **default since Emscripten 4.0.0** — not typed explicitly | same |
| `-pthread` | prohibited; "if `-pthread` is used, the resulting libraries will not load" | same |
| dependency lookup | full `RPATH` support, and `RPATH` is the *only* mechanism | same |
| Rust | stable `1.93.0`; no nightly, no custom sysroot | nightly + custom sysroot |
| new static libs | `sqlite3` 3.39.0, `lzma`/xz 5.2.2 | — |

Statically linked into the main binary: `zlib`, `bzip2`, `sqlite3`, `lzma`, `zstd`, `libffi`,
`libhiwire`, and the Emscripten JS libraries (`libGL`, `libegl.js`, `libwebgl.js`, `libhtml5*.js`,
`libsdl.js`, `libwebsocket.js`, `libeventloop.js`, `liblz4`, the filesystem backends). **OpenSSL is
not** — Pyodide builds it as a shared side module (`libssl.so`/`libcrypto.so`, OpenSSL 1.1.1w)
found through `RPATH`.

Extension modules are side modules. Pyodide documents `-sSIDE_MODULE=2` plus an explicit
`-sEXPORTED_FUNCTIONS=["_PyInit_…"]` as the recommendation, though its own C default is
`-sSIDE_MODULE=1`. That is exactly the shape the packaging decision above already chose for our
shim.

**PEP 783 itself does not enumerate any of this.** It lists the ABI-sensitive *surface* and then
defers normatively to Pyodide's documentation for the values. It does specify how the tag is
derived, and that is the part that bites: `packaging` reads
`sysconfig.get_config_var("PYEMSCRIPTEN_PLATFORM_VERSION")`. **That variable does not exist
anywhere in CPython 3.14.2** — `grep -r PYEMSCRIPTEN` over the whole checkout returns nothing. So a
stock CPython Emscripten build cannot advertise the platform even if it matches the flags; the
variable is something Pyodide's build injects.

---

## Correction: the JS layer is not mandatory

Everything above assumes Kotlin/Wasm can only reach CPython through JavaScript — that crossings
cost a JS hop and that string data must be copied by JS between two isolated memories. Reading
the Kotlin compiler shows both halves of that are wrong on the `wasmJs` target. This section
records what was checked and where it stops.

### 1. Kotlin can call another wasm module's exports directly

`@WasmImport(module, name)` and `@WasmExport(name)` exist in the stdlib
(`libraries/stdlib/wasm/src/kotlin/wasm/Annotations.kt`), gated behind
`@ExperimentalWasmInterop` since 2.2. The annotation doc says the function is imported "without
type adapters". The compiler's own codegen test `callingWasmDirectly.kt` states the intent
outright:

> Here we pass export of another Wasm module to our import directly without JS layer.
> This enables strict type check without JS conversions.

So `pmp_getattr(i32, i32) -> i32` can be a direct wasm-to-wasm call. JS is still involved in
*instantiating* the modules and wiring the import, but not in the call. This is exactly the
narrow integer interface the shape above already proposes — it just costs less than assumed.

The constraint is the one that makes it work: no type adapters means wasm primitive types only.
No strings, no objects. Which is what the composed shim signature already looks like.

### 2. On `wasmJs`, Kotlin *imports* its linear memory

`WasmCompiledModuleFragment.createAndExportMemory` branches on a flag that
`wasmCompiler.kt` sets as `importWasmMemoryInsteadOfExport = isWasmJsTarget`:

| target | what the module does with its memory |
|---|---|
| `wasmWasi` | **defines and exports** it — `WasmExport.Memory("memory", …)`, and the source comments that the name is a WASI ABI convention |
| `wasmJs` | **imports** it, as `WasmImportDescriptor("intrinsics", "memory")`, declared with 0 initial pages |

That inverts the premise. A `wasmJs` module does not own a memory it defines; the host hands one
in. And `kotlin.wasm.unsafe.Pointer` is an `i32` into precisely that memory.

So the question "can Kotlin read CPython's `char*`" reduces to "can the host supply Emscripten's
memory as that import" — at the wasm level, both are just a `WebAssembly.Memory` in an import
object, and Emscripten exposes its own as `Module.wasmMemory`. **If that works, string
marshalling does not merely get cheaper, it disappears** — which matters more here than anywhere
else, because every measurement on Android and desktop found marshalling, not crossings, to be
the dominant cost.

### 3. The blocker is the allocator, not the memory

`kotlin/wasm/unsafe/MemoryAllocation.kt` creates the top-level allocator as
`ScopedMemoryAllocator(0, parent = null)` and extends it with `wasm_memory_grow`. **It starts at
address 0 and assumes it owns everything.** In Kotlin's own memory that is correct. In a memory
shared with Emscripten, address 0 is the null page and the low region holds Emscripten's static
data, so `withScopedMemoryAllocator` would hand out addresses CPython already owns and corrupt
the heap.

This does not sink the idea, but it bounds it:

- **Reading and writing CPython-owned buffers** — addresses that CPython allocated and handed
  back as `i32` — is safe.
- **Allocating from Kotlin inside the shared memory** is not, unless the allocator can be given
  a base address it does not currently accept.

Whether a `Pointer` can be constructed from an arbitrary `Int` returned by the shim, rather than
only from `allocate()`, has not been checked and decides whether the safe half is usable at all.

### 4. The impossible direction stays impossible, and is already handled

None of this changes that a WasmGC reference cannot be stored in linear memory — that is a
spec-level guarantee, not a maturity gap, so CPython can never hold a Kotlin object pointer in a
`PyObject` field. The answer there remains an index into a Kotlin-side table, which is what
`docs/design/object-lifetime.md` already specifies for Python→Kotlin. Kotlin/Wasm even blesses the
mechanism: `JsReference<T>` passes a Kotlin object as an opaque reference.

### What this changes

| | as recorded above | after reading the compiler |
|---|---|---|
| Kotlin → CPython call | JS hop | **direct wasm call** (`@WasmImport`) |
| string marshalling | JS copies between two memories | **possibly none** — if the memory is shared |
| Kotlin allocating in that memory | assumed fine | **unsafe** — allocator starts at 0 |
| CPython holding a Kotlin object | impossible | impossible (unchanged) |

The composed-shim design does not change shape. What changes is its cost, and the reason to build
it: composition was proposed here to amortise JS hops, and if the hops are gone it has to justify
itself on crossing count alone — the same argument that closed desktop composition in ROADMAP §6.

### Unverified, in the order that decides the design

1. Can the host supply Emscripten's `wasmMemory` as Kotlin's `("intrinsics", "memory")` import?
   The generated JS glue builds that import object; whether it can be overridden is unknown.
2. Can `Pointer` be constructed from an arbitrary `Int`? If not, the read-only half is unusable
   and only `@WasmImport` survives.
3. Is `("intrinsics", "memory")` stable? It is an internal name, not public API.
4. Does `@WasmImport` work when the exporting module is an Emscripten `MAIN_MODULE`, whose
   exports are ordinary wasm functions but whose instantiation Emscripten's own glue controls?

None of these needs code in this repo to answer; all four are answerable with a small standalone
experiment, and together they decide whether WASM looks like iOS or like the JS-bridge design.

---

## Measured: what the released toolchain actually does

The section above was written from the Kotlin compiler at master and got one thing wrong in a way
that matters. `wasm-experiment/` builds a trivial `wasmJs` module on Kotlin 2.4.10, runs it, and
parses the emitted binary. Findings, in the order they change the design.

### The memory import is master-only; the released direction is the opposite

`importWasmMemoryInsteadOfExport = isWasmJsTarget` is in the compiler source but **not in any
released version**. Parsing the 2.4.10 output: the only length-prefixed `intrinsics` import is
`intrinsics.tag` (the exception tag), there is no memory import, and the export section contains
`memory`. Same on 2.2.20.

So Kotlin does not import a memory today. It exports one.

**That inverts the plan, and the inverted plan is better** — because it needs nothing unreleased
on either side. Emscripten has `-sIMPORTED_MEMORY`, which makes the CPython module import
`env.memory` rather than define it. Instantiate Kotlin first, take `exports.memory`, grow it to
Emscripten's initial size, hand it over as `env.memory`. Both halves are shipped features.

### Kotlin's linear memory is empty, and stays empty

This is what makes handing it over safe, and it is measured rather than assumed:

```
pages at startup                 0
pages after string interop       0
pages after collections          0
pages after exception            0
pages after explicit allocate    2   (allocated at 0x0)
```

The module declares `min_pages = 0`, and all 40 of its data segments are **passive** — they
initialise WasmGC arrays through `array.new_data` and are never placed in linear memory. String
interop was the obvious suspect for a hidden consumer and it is not one; Kotlin strings are
WasmGC arrays and cross to JS as `externref`.

So a `wasmJs` module's linear memory is unused space that happens to be attached to it. Giving it
to Emscripten costs Kotlin nothing.

### The allocator hazard is real but avoidable

The last probe line confirms the source reading: `withScopedMemoryAllocator` grew the memory and
allocated at **address 0**, exactly on top of where Emscripten keeps its static data. In a shared
memory this corrupts the heap on first use.

The mitigation is not a workaround, it is the natural design: **CPython allocates, Kotlin only
reads.** `Pointer(addr)` has a public constructor and its `loadInt`/`storeByte` members compile to
plain `i32.load`/`i32.store` with no allocator involvement — confirmed by compiling and running
it. Every address we would ever dereference comes back from a C call.

`withScopedMemoryAllocator` must therefore never appear in `wasmJsMain`. That belongs in the
source-set README as a hard rule, in the same category as desktop's "always `invokeExact`".

### What the picture looks like now

| | earlier draft | measured |
|---|---|---|
| Kotlin → CPython call | JS hop | direct wasm call (`@WasmImport`) |
| who owns the shared memory | CPython, imported by Kotlin (master-only) | **Kotlin exports it, Emscripten imports it** |
| Kotlin reading `char*` | impossible | `Pointer(addr).loadByte()`, no JS, no allocator |
| string marshalling | dominant cost | **none, if the memory is shared** |
| CPython holding a Kotlin object | impossible | impossible (unchanged — handle index) |

If this holds end to end, WASM stops being the outlier and lands close to the iOS shape: no
boundary for data, a direct call for control, and a handle table for the one direction WasmGC
forbids. The composition argument weakens accordingly — it was proposed here to amortise JS hops,
and without them it must justify itself on crossing count alone, which is what closed desktop
composition in ROADMAP §6.

### Still unverified

1. **Does `@WasmImport` bind to an Emscripten module's export?** The mechanism is proven by the
   compiler's own `callingWasmDirectly.kt` codegen test, but against a hand-built module, not one
   whose instantiation Emscripten's glue controls. Needs `emsdk`, which is not installed here.
2. **Does Emscripten accept a memory that starts at 0 pages and is grown by JS before
   instantiation?** `-sIMPORTED_MEMORY` normally pairs with a memory JS created at the right size.
3. **Does anything in a larger Kotlin program touch linear memory?** The probe covers strings,
   collections and exceptions on a trivial module. Coroutines, `ByteArray` interop and the JS
   `ArrayBuffer` bridges are untested.

---

## Proven end to end: Kotlin and CPython-side C can share one memory

The section above listed three unverified items and said the first needed `emsdk`. It is
installed, both experiments are built and run, and both pass. `wasm-experiment/` reproduces them.

### Test A — `@WasmImport` binds to an Emscripten export

Kotlin called `add_two` compiled by `emcc` and got 42. The compiler emits a real wasm import,
`(import "./probeA-wrapper.mjs" "add_two" (func …))`, and wires the ES module into the import
object. The value supplied prints as `function 2() { [native code] }` — a raw wasm export, not a
JS wrapper — so the call is wasm-to-wasm with no JS frame, which is what the compiler's own
`callingWasmDirectly.kt` test intends.

### Test B — Emscripten imports the memory Kotlin exports

```
C: get_static_message() -> 0x400
Kotlin: readCStringAt(same address) -> "hello-from-the-cpython-side"      PASS
C: alloc_message() -> 0x10628
Kotlin: writeCStringAt(addr, "written-by-kotlin")
C: str_len(addr) -> 17, UTF8ToString(addr) -> "written-by-kotlin"         PASS
```

Both directions, static data and `malloc`'d heap. **No copying and no JS in the data path.**
Kotlin dereferences a C address with `Pointer(addr)` and gets the bytes C put there.

### The single blocker, and it is one value

Emscripten refused Kotlin's memory at first:

```
LinkError: memory import has no maximum limit, expected at most 4294967295
```

Kotlin emits `WasmLimits(0, null)` — no maximum — and wasm requires a supplied memory's limits to
sit inside the importer's, so an unbounded memory can never satisfy any Emscripten import.
Patching section 5 to `{min: 0, max: 32768}` made everything link and both directions work.
Nothing else about the pairing needed changing.

**That is a compiler-chosen constant, not a design problem.** It is worth a YouTrack issue; a
`-Xwasm-memory-maximum`-style knob, or simply emitting the wasm32 ceiling, would remove the need
to post-process the binary. Until then a build step can patch it, which is ugly but bounded — the
memory section is six bytes and nothing in the format holds an absolute offset.

### The constraint that shapes the design

A and B cannot be combined in one instantiation graph while Kotlin owns the memory:

```
Kotlin      @WasmImport          needs Emscripten's exports at instantiation time
Emscripten  -sIMPORTED_MEMORY    needs Kotlin's memory before that
```

Wasm imports are supplied up front, so that is a cycle. Three ways out:

1. **Kotlin imports the memory instead of exporting it.** No cycle — Emscripten instantiates
   first, then Kotlin receives both the memory and the functions. This is
   `importWasmMemoryInsteadOfExport = isWasmJsTarget`, present at master and in no release. It is
   the configuration this design wants, and the second reason to talk to JetBrains.
2. **JS trampolines for calls, shared memory for data.** Available today. Costs a JS hop per
   call and keeps the data path free — and the data path is the half that dominated every
   measurement on Android and desktop, so this captures most of the win.
3. Copy through JS, as originally designed.

### What §10 looks like now

| | original design | proven |
|---|---|---|
| control path | JS hop per call | direct wasm call, **or** JS trampoline if sharing memory |
| data path | JS copies between two memories | **none — one memory** |
| string marshalling | dominant cost, needs interning | **absent** |
| composition | needed to amortise JS hops | **weakly motivated** — same argument that closed §6 |
| CPython holding a Kotlin object | impossible | impossible — handle index, per `object-lifetime.md` |

WASM stops being the outlier. With option 2 it is roughly Android's shape with a cheaper data
path; with option 1 it is close to iOS.

### Rules this produces for `wasmJsMain`

- **`withScopedMemoryAllocator` must never be called.** It allocates at address 0, on top of
  Emscripten's static data. Measured: the probe grew the memory to 2 pages and handed back `0x0`.
  CPython allocates; Kotlin only dereferences addresses it was given.
- `Pointer(addr)` is the only way in, and its public constructor plus raw `i32.load`/`i32.store`
  members make that free.

### Resolved

- **Linear memory is strictly unused.** The probe was expanded to include large `ByteArray` allocations (10MB), large object graphs (100,000 items), `Uint8Array` JS ArrayBuffer bridges, and coroutines (`kotlinx.coroutines.GlobalScope.launch`). **None of them touched linear memory.** The page count stayed exactly at 0 until `withScopedMemoryAllocator` was explicitly called. This confirms that a shared linear memory design is perfectly safe and won't be corrupted by normal Kotlin runtime behavior.
- **JS trampoline cost is ~2.7x.** A microbenchmark running 10,000,000 iterations found a direct `@WasmImport` call took ~48.7 ms, while routing the call through a JS trampoline took ~132.9 ms (2.73x slower). This shows the JS boundary imposes measurable overhead, but since we eliminate copying overhead for strings and data, this cost is a worthwhile tradeoff to achieve the shared memory model.
- **Unbounded Memory bug.** Kotlin's compiler emits `WasmLimits(0, null)`, causing Emscripten to reject the imported memory. This is confirmed to be the only blocker. A draft YouTrack issue has been written to `docs/platforms/wasm-youtrack-issue.md` requesting a compiler flag (or a default Wasm32 ceiling) to fix this.

### Still open

- Upcalls. `addFunction` still routes through JS to re-enter WasmGC, and nothing here changes
  that; §7 still needs measuring before `wasmJsMain` is written.

### Correction: the coroutine reading was contaminated

The result above was nearly reported wrong. `runPromise` resolves after `main()` returns, so the
coroutine's page count was taken *after* the `withScopedMemoryAllocator` demo had already grown
the memory to 2 pages. The probe printed `pages inside coroutine 2` and that number said nothing
about coroutines — it was the allocator's growth, observed late.

Gating the allocator demo off and re-running gives the reading that means something:

```
pages at startup                 0
pages after string interop       0
pages after collections          0
pages after exception            0
pages after large ByteArray      0   (10 MiB)
pages after large obj graph      0   (100k strings)
pages after ArrayBuffer bridge   0
pages observed inside coroutine  0   <- uncontaminated
```

So the conclusion holds, and now for coroutines too: **nothing in ordinary Kotlin/Wasm touches
linear memory.** `MEASURE_ALLOCATOR` in the probe is off by default for this reason, and the
coroutine line must be read as meaningless in any run where it is on.

---

## Resolved upstream: 2.4.20-Beta2 imports the memory

The YouTrack issue drafted in `wasm-youtrack-issue.md` should not be filed. The thing it asks for
has already shipped, and in a better form than the request.

`importWasmMemoryInsteadOfExport = isWasmJsTarget` was read on the compiler's `master` branch and
recorded here as unreleased, on the evidence that 2.2.20 and 2.4.10 both export a memory. That was
right about those two versions and wrong about the conclusion: **2.4.20-Beta2 imports it.** The
experiment builds against it and the binary says so plainly:

```
imports   intrinsics.memory   (kind 0x02, memory)
          intrinsics.memory   (kind 0x03, global)
          intrinsics.tag
memory definitions            0        <- defines none of its own
non-function exports          none     <- no longer exports one
```

And the generated glue hands it in as an ordinary JavaScript value:

```js
intrinsics: {
    memory: new WebAssembly.Memory({ initial: 0 }),
    tag: wasmTag
},
```

Replacing that one expression with Emscripten's `Module.wasmMemory` is the whole integration. No
binary patching, no compiler flag, no `max` limit to negotiate — `patch-memory-max.py` becomes
history rather than a build step.

### It also removes the constraint that shaped the design

The instantiation cycle is gone. While Kotlin exported the memory, Emscripten had to import it
while Kotlin simultaneously needed Emscripten's exports through `@WasmImport`, and wasm supplies
imports up front, so the two could not be satisfied together. The recorded workaround was JS
trampolines for the calls, measured at 2.73x a direct call.

With Kotlin importing, the order resolves: instantiate Emscripten first, then hand Kotlin both the
memory and the functions. **Direct wasm-to-wasm calls and a shared linear memory at the same
time** — which is the configuration this design wanted and had written off as unavailable.

### What that leaves

The last structural obstacle to §10 is gone. Remaining work is ordinary: pin the Kotlin version at
2.4.20-Beta2 or later (the library targets 2.0.20 today, so this is a real upgrade with its own
cost), build CPython for `pyemscripten_2026_0`, write the composed shim, and supply
`Module.wasmMemory` in place of the placeholder above.

Two facts from earlier still hold and still constrain the shape. Kotlin never touches its linear
memory on its own — measured across strings, collections, exceptions, a 10 MiB `ByteArray`, a
100k-object graph, the `ArrayBuffer` bridge and coroutines — so handing it to Emscripten costs
nothing. And `withScopedMemoryAllocator` must never appear in `wasmJsMain`, because it allocates
from address 0 upward, on top of Emscripten's static data.

---

## Test C: run, and the cycle is gone

The section above predicted this from reading the binary. `wasm-experiment/` now builds and runs
it. `native/run.sh` reproduces; Test C is `native/combined-test.mjs`.

### The binary confirms the shape

Parsing `wasm-experiment.wasm` built on 2.4.20-Beta2:

```
import  './probeA-wrapper.mjs' . 'add_two'    kind=0  func         <- direct wasm call
import  'intrinsics' . 'tag'                  kind=4  tag
import  'intrinsics' . 'memory'               kind=3  global externref
import  'intrinsics' . 'memory'               kind=2  memory  min=0  max=none
memory definitions                            0
non-function exports                          none
```

`max=none` on the *import* is what makes this direction work. A wasm import must be satisfied by
a memory whose limits sit inside the declared ones, so an unbounded import accepts anything —
including Emscripten's bounded memory. The old direction was unsatisfiable for exactly the
mirror-image reason, and `patch-memory-max.py` existed to force it. It is no longer used.

### The integration is one substitution

`patch-import-object.py` rewrites one expression in the generated glue:

```js
intrinsics: { memory: new WebAssembly.Memory({ initial: 0 }), tag: wasmTag }
                    -> memory: <probeA-wrapper>.wasmMemory
```

Ordering comes free from ES modules. `wasm-experiment.import-object.mjs` imports the wrapper, the
wrapper has a top-level `await factory()`, so Emscripten is fully instantiated before Kotlin's
import object is built. Emscripten is compiled exactly as it would be alone — no
`-sIMPORTED_MEMORY`, no binary patching. Kotlin is the side that adapts.

### Both halves hold at once

```
Emscripten's memory object     Memory, 258 pages
Kotlin's intrinsics.memory     Memory, 258 pages          PASS  same buffer
Kotlin called cAddTwo(40, 2) -> 42                        PASS  @WasmImport, memory shared
C:  get_static_message() -> 0x400
Kotlin: readCStringAt(that address) -> "hello-from-the-cpython-side"   PASS
Kotlin: writeCStringAt(malloc'd addr, "written-by-kotlin")
C:  str_len -> 17, UTF8ToString -> "written-by-kotlin"     PASS
```

The value handed to the import still prints as `function 2() { [native code] }` — a raw wasm
export, no JS wrapper — so the control path is wasm-to-wasm with the memory shared.

### And it survives the memory growing

Not previously checked, and it is not optional: CPython links with `-sALLOW_MEMORY_GROWTH
-sINITIAL_MEMORY=20971520`, so the memory *will* grow underneath Kotlin in production. A JS
TypedArray view detaches when that happens. Kotlin holds the memory as a wasm import rather than
a view, and does not:

```
C: malloc(64 MiB) -> 0x10638,  258 pages -> 1026 pages
   PASS -- the shared memory actually grew
   PASS -- Kotlin still reads the pre-growth address correctly
   PASS -- Kotlin writes into memory that did not exist when it was instantiated
```

### Measured: the control path

10,000,000 calls of the same trivial C function, one process, Node 24, Emscripten 5.0.3.

| | ns/call |
|---|---|
| direct `@WasmImport` | **5.0** (stable across runs) |
| JS trampoline | 13.6 – 16.9 (2.7x – 3.4x) |

So removing the cycle is worth about 9–12 ns per crossing. That is the whole benefit of option 1
over option 2, and it is now available.

### Measured: the data path — and the earlier conclusion was wrong

Everything above said string marshalling "does not merely get cheaper, it disappears". **It does
not.** Both loops below run in Kotlin and end with a Kotlin `String` built from the same C
address; only the route differs.

| ns per read | 27 bytes | 4000 bytes |
|---|---|---|
| shared: scan to NUL, build nothing | 193 | 1 649 |
| shared: `ByteArray` copy, no decode | 399 | 4 875 |
| shared: `ByteArray` + `decodeToString()` | 405 | **58 271** |
| shared: `CharArray` + `concatToString()` (ASCII) | **103** | 5 875 |
| shared: `StringBuilder.append(Char)` per byte | 311 | 51 348 |
| copied: `UTF8ToString` through JS | 125 | **3 827** |

Two things account for it, and neither is the boundary:

1. **Kotlin/Wasm strings already *are* JS strings.** The generated glue compiles with
   `builtins: ['js-string']`. `UTF8ToString` produces a JS string, and handing that to Kotlin is
   not a conversion. The "copy through JS" path was assumed to pay for a copy that no longer
   exists.
2. **`ByteArray.decodeToString()` is pathological on long input** — 53 µs of the 58 µs figure at
   4000 bytes, about 13 ns per byte, against 0.2 ns per byte at 27 bytes. `concatToString()` on a
   `CharArray` costs a tenth of it. Worth a separate report upstream.

The honest rule that comes out of this is not "marshalling disappears" but:

- **`char*` → `String`: use the ASCII `CharArray` + `concatToString()` route, and fall back to
  `UTF8ToString` for anything long or non-ASCII.** Never `decodeToString()` on a large buffer.
- **`char*` → `ByteArray` (the `PyBytes` case): shared memory, unambiguously.** No decode, and no
  JS-side equivalent that avoids a copy.
- Shared memory is still what makes writing *into* C buffers free, which has no JS equivalent at
  all short of `Module.HEAPU8.set`.

### Measured: bulk, which is what the composed shim was for

1000 `i32` out of a C-owned array, 20,000 repetitions:

| | ns per element |
|---|---|
| shared memory, `Pointer.loadInt()` | **0.7** |
| through JS, `Module.HEAP32[a >> 2]` | 7.1 |

So the composition arithmetic is now fully determined. Walking a list without a shim means N
direct `PyList_GetItem` calls at **5.0 ns** of crossing each. With a shim it is one crossing plus
N shared reads at **0.7 ns**. **Composition buys ~4.3 ns per element and nothing else** — the
CPython-side work is identical either way.

ROADMAP §6 closed desktop composition at a 10% saving. Test D below measures the same question
against the real interpreter and answers it more sharply.

---

## Test D: it works against real CPython, and it closes the composition question

Tests A and C run against a 4 KB toy compiled by `emcc`. Test D runs against **CPython 3.14.2
built for `wasm32-emscripten`** — 10 MB of wasm, 8191 exports, `-sMAIN_MODULE`, a growable memory,
instantiated by Emscripten's own glue. The build is `Tools/wasm/emscripten` (PEP 776, Tier 3 from
3.14) under Emscripten 5.0.3; see the build notes below.

```
python.wasm exports        8191
CPython's memory           320 pages
Kotlin's intrinsics.memory 320 pages                                    PASS  same buffer

Kotlin: pyExec("answer = 6 * 7; greeting = ...") -> 0                   PASS
Kotlin: pyGlobalInt("answer") -> 42                                     PASS
Kotlin: pyGlobalString("greeting") -> "hello-from-cpython-42"           PASS
Kotlin: pyExec("bytearray(48 MiB)") -> 0,  320 -> 934 pages             PASS
        Kotlin still reads CPython correctly after the growth           PASS
        a pre-growth string still reads correctly                       PASS
```

Kotlin wrote the Python source **directly into CPython's heap** with `Pointer.storeByte` on an
address `malloc` returned, called `PyRun_SimpleString` as a direct wasm import, then dereferenced
`PyUnicode_AsUTF8`'s `char*` to get the string back. No copying, no JS in either direction, and it
survives the interpreter growing its own memory by 600 pages underneath.

### The one build change CPython needed, and it is not ABI-sensitive

The Stable ABI symbols are all there — `-sMAIN_MODULE` is `LINKABLE`, so Emscripten exports
everything and emits `EXPORTED_FUNCTIONS is not valid with LINKABLE set` for CPython's own list.
**This retires the recorded worry that "CPython must be built with an explicit
`EXPORTED_FUNCTIONS` list; a stock Pyodide build strips the C API by dead-code elimination."** The
supported CPython build already exports the whole surface.

What it does *not* do is let JavaScript reach them. `Module` carries 36 keys and none of the
interesting ones, so there is nothing to hand to `@WasmImport`. One flag fixes it:

```
-sEXPORTED_RUNTIME_METHODS=FS,callMain,ENV,HEAPU32,TTY,wasmExports,wasmMemory
                                                       ^^^^^^^^^^^^^^^^^^^^^^
```

After a relink, `Module.wasmExports.PyRun_SimpleString` prints as `function 4426() { [native
code] }` — a raw wasm export — and `Module.wasmMemory` is the `WebAssembly.Memory` Kotlin's
`intrinsics.memory` needs. Both are JS-glue settings and neither appears in PEP 783's
ABI-sensitive list, so this costs nothing against the platform tag.

### Composition, measured against the interpreter

Reading one global out of `__main__`, 200,000 times, on the same shared-memory direct-call setup.
Each row adds a pure-Kotlin optimisation:

| | ns | |
|---|---|---|
| naive | 259.5 | 2 `malloc` + 2 `free` + 2 string writes + 4 API calls |
| + interned C strings | 185.4 | names allocated once — the Android fix, for free here |
| + module/dict hoisted | **65.2** | 2 API calls — the floor |
| one crossing (`PyErr_Occurred`) | **2.9** | direct `@WasmImport`, real interpreter |

**75% of the naive cost is removable without shipping a single line of C.** What is left is 65 ns
of two CPython calls, of which the crossings are 5.8 ns — 9% of the floor, 2% of the naive figure.
A composed `pmp_getattr` could merge those two calls into one and save **2.9 ns**.

The bulk case ends the same way. Composition there buys the difference between a crossing and a
shared-memory read, 2.9 ns against 0.7 ns per element, and CPython does identical work either way.
The constraint that kept the case alive — `PySequence_Fast_ITEMS` is a macro over
`PyListObject->ob_item` and `abi3t` makes `PyObject` incomplete, so shared memory does *not* let
Kotlin walk a list's storage — turns out not to matter, because N calls at 2.9 ns is cheap.

**Verdict: no composed shim on WASM.** Same answer as ROADMAP §6 gave desktop, reached the same
way, and with a wider margin: desktop's composition was worth 10%, this is worth 1–2% after the
free wins are taken. The signatures sketched earlier — `pmp_getattr`, `pmp_exec`, `pmp_list_items`,
`pmp_str_utf8`, `pmp_decref`, `pmp_scratch` — were motivated by amortising JS hops, and the hops
are gone. What replaces them is ordinary `wasmJsMain` Kotlin: `@WasmImport` per Stable ABI
function, an interning cache for repeated names, and `Pointer` reads for everything else.

That also removes the second build pipeline. No extension module to build, version and ship
against a specific interpreter — which was the largest recurring cost in the plan.

### What is still not answered

Upcalls. `addFunction` re-entering WasmGC still goes through JS and is still unmeasured; §7's
shape has to settle before `wasmJsMain` is written. Nothing in Tests C or D touches it.

---

## Building CPython 3.14.2 for Emscripten: what worked

`/Volumes/macMini/wasm-build/build-cpython-emscripten.sh` reproduces it. The driver is CPython's
own `Tools/wasm/emscripten` (`build` runs configure-build-python → make-build-python → libffi →
mpdecimal → configure-host → make-host). It succeeded end to end on the first attempt against
Emscripten 5.0.3, and `python.sh --version` prints `Python 3.14.2`.

```
sysconfig.get_platform()                      emscripten-5.0.3-wasm32
sysconfig.get_config_var('PYEMSCRIPTEN_PLATFORM_VERSION')   None
Checked 114 modules (85 built-in, 6 shared, 17 n/a, 1 disabled, 5 missing, 0 failed on import)
python.wasm  10 MB      python.mjs  570 KB
```

### What the stock build passes, and how far it is from `pyemscripten_2026_0`

Read off the actual command lines, not the documentation:

| | stock CPython 3.14.2 | `pyemscripten_2026_0` |
|---|---|---|
| Emscripten | 5.0.3 (we pinned it) | **5.0.3** — matches |
| `-sWASM_BIGINT` | passed explicitly in `LDFLAGS_NODIST` | required; default since Emscripten 4.0 — matches |
| `-pthread` | absent; `configure` even reports `emcc accepts -pthread... no` | prohibited — matches |
| `-fPIC` | yes (required by `MAIN_MODULE`) | yes — matches |
| dynamic linking | `--enable-wasm-dynamic-linking` → `-sMAIN_MODULE`; extensions built `-shared -sSIDE_MODULE=1` | `MAIN_MODULE=1` / side modules — matches |
| **unwinding** | **nothing** — no `-fwasm-exceptions`, no `-sSUPPORT_LONGJMP=wasm` | **required at compile and link** — **DIVERGES** |
| **static libs** | zlib, bzip2, sqlite3, libffi, mpdecimal; **no lzma, no zstd** | + lzma 5.2.2, zstd — **DIVERGES** |
| **OpenSSL** | **absent** — "Could not build the ssl module" | shared side module, OpenSSL 1.1.1w — **DIVERGES** |
| platform variable | `PYEMSCRIPTEN_PLATFORM_VERSION` is **not defined anywhere in CPython 3.14.2** | `packaging` reads it to emit the tag — **DIVERGES** |

So the stock Tier 3 build is close but cannot claim the tag, and the gap is real work rather than a
flag flip: the unwinding ABI has to be added at both compile and link, `lzma`/`zstd`/OpenSSL have to
be built as ports or side modules, and something has to define `PYEMSCRIPTEN_PLATFORM_VERSION`.

A practical trap for whoever does it: the driver hardcodes `CFLAGS=-DPY_CALL_TRAMPOLINE
-sUSE_BZIP2` in its `configure` argv and appends user arguments *after*, and autoconf takes the
last assignment — so passing `CFLAGS=…` to add `-fwasm-exceptions` silently drops
`-DPY_CALL_TRAMPOLINE`. The full string has to be repeated.

**Not verified:** that the ABI-matching build succeeds, or that a PyPI `pyemscripten_2026_0` wheel
loads into it. Only the stock build was run.

### Environment note

Installing Emscripten 5.0.3 through `emsdk install` **replaces `~/emsdk/upstream` in place** — the
previously active 6.0.6 now reports as not installed and would have to be re-downloaded. The active
version is a global, shared setting, not per-project.

---

## Proven: the tag claim is real — a PyPI `pyemscripten_2026_0` wheel loads

The section above listed four divergences between the stock Tier 3 build and
`pyemscripten_2026_0` and marked the whole thing "not verified". It has now been built and
tested against an actual wheel. `/Volumes/macMini/wasm-build/build-cpython-abi.sh` reproduces
it, into `cpython314-abi/` so the stock build survives for comparison.

**Result: `pydantic_core-2.48.0-cp314-cp314-pyemscripten_2026_0_wasm32.whl`, downloaded from
PyPI, imports and runs.**

```
python 3.14.2 emscripten-5.0.3-wasm32
IMPORTED pydantic_core 2.48.0
  .../pydantic_core/_pydantic_core.cpython-314-wasm32-emscripten.so
validate_python(42) -> 42
raised ValidationError
```

The `.so` is 4.4 MB of Rust/PyO3. The `ValidationError` matters as much as the `42`: it is an
exception propagating out of the side module, so the unwinding ABI is exercised and not merely
linked.

### Only one of the four divergences actually gated it

| | matched? | did it gate the wheel? |
|---|---|---|
| unwinding ABI | **yes, this build** | **yes — this was the whole gate** |
| `PYEMSCRIPTEN_PLATFORM_VERSION` | **yes, via a one-line CPython patch** | no — gates *claiming* the tag, not loading |
| lzma, zstd | still missing | no |
| OpenSSL | still missing | no |

The negative control is exact. The same wheel on the stock build:

```
ImportError: could not load dynamic lib: .../_pydantic_core...so
LinkError: WebAssembly.Instance(): Import #204 "env" "__cpp_exception":
           tag import requires a WebAssembly.Tag
```

Reading the binaries says why before running anything. The wheel's `.so` imports a **wasm tag**:

```
tag      env.__cpp_exception          <- the side module needs it
memory   env.memory
table    env.__indirect_function_table
```

and the two interpreters differ in exactly that:

```
cpython314-abi/python.wasm   section id 13 (tag), exports  __cpp_exception, __c_longjmp
cpython314/python.wasm       no tag section, exports neither
```

So `-fwasm-exceptions -sSUPPORT_LONGJMP=wasm` is not a nice-to-have that affects code
generation quality — it is a link-level contract, and it is checked. `lzma`/`zstd`/OpenSSL are
ABI-sensitive in the Pyodide flag list but only bind wheels that link *them*; they cost the
stdlib modules `_lzma`, `_zstd`, `_hashlib`, `_ssl` and nothing else here.

The build-driver trap recorded earlier is real and was avoided by repeating the whole string.
The result is visible in the generated Makefile:

```
CONFIGURE_CFLAGS= -DPY_CALL_TRAMPOLINE -sUSE_BZIP2 -fwasm-exceptions -sSUPPORT_LONGJMP=wasm
CONFIGURE_LDFLAGS= -fwasm-exceptions -sSUPPORT_LONGJMP=wasm
```

### `PYEMSCRIPTEN_PLATFORM_VERSION`, and a trap that silently produces a wrong tag

`packaging` 26.3 implements PEP 783 as literally as the PEP describes:

```python
def _emscripten_platforms():
    v = sysconfig.get_config_var("PYEMSCRIPTEN_PLATFORM_VERSION")
    if v:
        yield f"pyemscripten_{v}_wasm32"
```

Before defining it, the interpreter loads the wheel but cannot claim the tag — its platform tag
is `emscripten_5_0_3_wasm32` and no `pyemscripten_*` tag is produced at all.

The obvious way to define it is a Makefile variable, because that is what `sysconfig
--generate-posix-vars` reads. **That silently produces the wrong tag.** `sysconfig` coerces any
value that parses as an integer, and Python accepts underscores inside integer literals:

```python
int('2026_0') == 20260      int('2025_0') == 20250      int('2024_0') == 20240
```

so the config var comes out as the integer `20260` and `packaging` emits
`pyemscripten_20260_wasm32` — a plausible-looking tag that matches nothing. Every version in
the PEP's series has this shape, so it is not specific to 2026.

`sysconfig` already has the opt-out — `_ALWAYS_STR`, which holds the two Apple deployment
targets for the same reason. Adding one name to it is the whole fix:

```python
_ALWAYS_STR = {
    'IPHONEOS_DEPLOYMENT_TARGET',
    'MACOSX_DEPLOYMENT_TARGET',
    'PYEMSCRIPTEN_PLATFORM_VERSION',   # int('2026_0') == 20260
}
```

After which the interpreter's first tag is exactly the wheel's:

```
config var = '2026_0'
first tag  = cp314-cp314-pyemscripten_2026_0_wasm32
wheel tag  = cp314-cp314-pyemscripten_2026_0_wasm32     MATCH
```

This is worth reporting upstream: CPython defines no way to set the variable, and the one
mechanism a packager would naturally reach for corrupts it without any error.

### What this changes for the plan

The earlier decision — our own Emscripten CPython 3.14, no Pyodide dependency — stands, and the
recorded limitation shrinks again. "Compiled third-party extensions are a known limitation on
this platform" is now false for anything published under the PEP 783 tag. The remaining
limitation is narrower and ordinary: packages that need `lzma`, `zstd` or OpenSSL need those
built, and packages not published for the tag still have to be built.

---

## Upcalls: measured, and better than every prediction in this document

§5 and §7 step 3 specify one mechanism — `@JsExport` the trampoline, wrap it in a JS closure,
hand it to `addFunction` — and "Unresolved" writes it off as "goes through JS, unmeasured, and
likely worse than every other platform". Tests E and F measure it, and measure an alternative
this document does not consider.

### There is no JS in the call path, because a funcref is a funcref

`@WasmExport` puts a Kotlin function in the **wasm** export section with no type adapters, so
what JS receives is an exported wasm function, not a closure:

```
String(raw.kotlin_upcall_add)  ->  function 5097() { [native code] }
```

`WebAssembly.Table.prototype.set` accepts any exported wasm function whatever instance produced
it, so it can go straight into Emscripten's `__indirect_function_table`, and Emscripten's
`call_indirect` reaches it directly. JS appears **once, at registration** — to put a funcref in
a table — and never in a call.

```
table.grow(1) -> 2;  table.set(2, kotlin_upcall_add) accepted
C: call_fp(2, 40, 2) -> 42
```

Test E, 10,000,000 indirect calls issued from inside C so no JS is in the timed loop:

| | ns/call | crossing |
|---|---|---|
| C → C (control, same module) | 0.7 | — |
| **C → Kotlin, `table.set`** | **3.1** | **2.4 ns** |
| C → Kotlin, `addFunction` + JS closure (the design's mechanism) | 10.9 | 10.2 ns |

The design's mechanism is **3.5x** the direct one. And the direct upcall at ~3 ns is the mirror
of the direct downcall at ~5 ns — the two directions cost the same order, which is the thing
this document assumed could not be true.

`addFunction` handed the wasm export directly works too, and deduplicates onto the entry
`table.set` already installed — so it is the same path, not a third one.

### Re-entrancy holds: the WasmGC heap is live inside a frame CPython pushed

The routing-by-data design needs the upcall to find a Kotlin object from a context integer and
write into linear memory. Both work from inside a C-pushed frame:

```
Kotlin registry[7] = "routed-through-a-cpython-frame"
C: call_fp(route, ctx=7, out=0x10628) -> 30
C: UTF8ToString(out) -> "routed-through-a-cpython-frame"
```

An unrouted context returns cleanly rather than trapping, and allocating on the WasmGC heap
inside the upcall is fine.

**Signatures must match exactly.** `call_indirect` is statically typed and does not coerce:
calling a `(i32)->i32` export through a `(i32,i32)->i32` slot raises `RuntimeError: null
function or function signature mismatch`. That is a runtime trap, not a compile error, so the
Kotlin trampoline's arity is a correctness requirement rather than a style question.

### Test F: real CPython calls Kotlin, through a real `PyMethodDef`

A toy's `call_indirect` is not what CPython does. CPython is built `-DPY_CALL_TRAMPOLINE`, and
`pycore_emscripten_trampoline.h` routes every `PyCFunction`, `getset` getter and setter through
`_PyEM_TrampolineCall`, which dispatches on arity. So the mechanism has to survive that.

It does. A Kotlin `@WasmExport` of shape `(PyObject*, PyObject*) -> PyObject*`, put in CPython's
own table and registered in a `PyMethodDef` that Kotlin builds in linear memory:

```
CPython's __indirect_function_table: Table, length 13518
table.grow(1) -> 13518;  table.set(13518, kotlin_pycfunction) accepted
Kotlin: installPyCallback("kotlin_cb", fp=13518) -> 0

Kotlin: pyExec("cb_result = kotlin_cb(41)") -> 0
Kotlin-side upcall counter: 0 -> 1
Kotlin: pyGlobalInt("cb_result") -> 42
type(kotlin_cb).__name__ = "builtin_function_or_method"
```

Python sees an ordinary builtin. Variadic arguments arrive intact, 1000 calls in a Python loop
all return correctly, and a bad argument raises rather than corrupting anything. The return
value is built by Kotlin calling `PyLong_FromLong` — **a downcall issued from inside an
upcall**, so both directions are live on one stack.

### But CPython's own wasm trampoline never installs, and that is an upstream defect

`Python/emscripten_trampoline.c` tries to replace its JS trampoline with a wasm one that
dispatches using `__builtin_wasm_test_function_pointer_signature` (a wasm-gc `ref.test`). A
four-argument Kotlin export matches none of the four shapes it tests, so it discriminates:

* wasm trampoline → all four `ref.test`s fail → `SystemError: Handler takes too many arguments`
* JS fallback → `wasmTable.get(func)(arg1, arg2, arg3)` → called anyway, fourth argument zero

```
kotlin_cb_4(1)  ->  "returned 9906512"
```

It was called. **The JS fallback is live**, and instrumenting the swallowed `catch` says why:

```
[instr] fell back: LinkError: WebAssembly.Instance(): Import #0 "env" "memory":
                   memory import must be a WebAssembly.Memory object
[instr] wasmTable is undefined | wasmMemory is undefined
```

The `EM_JS` initialiser runs while `python.mjs` is still evaluating, and needs `wasmTable` and
`wasmMemory` — which in this configuration are **exports** of `python.wasm`, assigned only after
instantiation. The `LinkError` is swallowed by a bare `catch (e) {}` and the JS fallback is kept
silently.

This is an instantiation cycle, not an ordering slip — the same shape as the one Kotlin's memory
import dissolved, pointing the other way. The trampoline module imports `env.memory` and
`env.__indirect_function_table` from the very module whose import object needs the trampoline.
`-sIMPORTED_MEMORY` was tried: it fixes the memory half and the failure moves to the table,

```
[instr] fell back: LinkError: Import #1 "env" "__indirect_function_table":
                   table import requires a WebAssembly.Table
[instr] wasmTable undefined wasmMemory object
```

and Emscripten has no imported-table setting to finish the job. So on this toolchain pair the
wasm trampoline cannot install at all.

**The consequence is symmetric, which is why it does not change the design.** Every
`PyCFunction` call in the build crosses a JS frame — a C extension's exactly as much as
Kotlin's. Measured like for like, `abs()` (a C builtin declared `METH_O`) against a Kotlin
`METH_O` function with the identical wasm signature:

| | ns/call |
|---|---|
| pure Python `def py_cb(x): return x+1` | 59 |
| C builtin `abs(x)` — `METH_O` | 73 |
| **Kotlin `kotlin_cb_o(x)` — `METH_O`** | **87** |
| | **14 ns attributable to the crossing** |
| Kotlin, `METH_VARARGS` | 161 |
| Kotlin, `METH_VARARGS` + tuple read | 183 |

The `METH_VARARGS` rows carry CPython's argument-tuple construction; that cost is identical for
a C extension and is not the crossing. 14 ns against `abs` matches Test E's `addFunction`
figure, as it should — both are the JS-frame path.

### What this means for §7

| platform | how Python re-enters the host |
|---|---|
| iOS | a plain function call |
| Android | a JNI callback |
| desktop | a Panama upcall stub |
| **WASM, slot reached by `call_indirect`** | **a plain `call_indirect` — ~3 ns** |
| **WASM, `PyMethodDef` today** | the same JS trampoline every C extension pays — ~14 ns over C |

**§7's design holds on WASM, and the caveat it carries is wrong.** "WASM will have permanently
higher per-call overhead than Native/JVM due to the WasmGC ↔ JS ↔ Emscripten boundary" describes
a boundary that is not in the path. Routing by data through a small set of static trampoline
shapes works unchanged; the context integer, the WasmGC lookup and the linear-memory write all
survive re-entry.

Two things change in step 3 of the staged plan:

* **`@WasmExport` and `WebAssembly.Table.set`, not `@JsExport` and `addFunction`.** Same number
  of moving parts, 3.5x cheaper, and JS touched once per trampoline rather than once per call.
* **Prefer slots CPython calls directly.** Type slots installed through `PyType_FromSpec` are
  invoked as ordinary `call_indirect`; `PyMethodDef` entries and getset descriptors go through
  `_PyEM_TrampolineCall`. Only the first gets the ~3 ns figure while the upstream defect stands.
  (The slot/trampoline split is read from `pycore_emscripten_trampoline.h`, which wraps exactly
  `_PyCFunction_TrampolineCall`, `descr_get_trampoline_call` and `descr_set_trampoline_call`;
  the ~3 ns figure for the direct path is Test E's measurement, not a measurement of a type slot.)

### Still open

Nothing structural. `wasmJsMain` can be written against §7 as amended. What is *not* answered:

- Whether a Kotlin exception thrown inside an upcall unwinds safely through CPython's frames.
  Every trampoline here returns normally or returns `NULL`.
- Reference-count ownership across the boundary, which is a contract question rather than a
  toolchain one.
- ~~Whether Kotlin/Wasm offers a GC hook equivalent to `Cleaner`/`createCleaner`.~~ **Answered.**
  The stdlib does not, but the *host* does, and `toJsReference()` reaches it without pinning the
  object it hands over — measured at `alive 0 / 200` with JS holding only a `WeakRef` and a
  `FinalizationRegistry` entry. `registerCleaner` is built on that. The second finding cost more:
  a collection is observable only across a **macrotask** boundary, because a `WeakRef` outlives the
  job that created it and the registry callback is delivered as a task, and
  `FinalizationRegistry.prototype.cleanupSome()` — which would have made it synchronous — has been
  removed from V8. See `src/wasmJsMain/README.md` and ROADMAP §10 for the numbers.


### Verification status of the wheel claim

**Demonstrated, reproducibly, as Test G in `native/run.sh`.** The wheel is the real PyPI file --
`pydantic_core-2.48.0-cp314-cp314-pyemscripten_2026_0_wasm32.whl`, 1,403,190 bytes, sha256
`4fc45a49334c54541cbc97bf416d9300b4b1d3b2840dfb079b1123dc3f9ef5a6`, matching
`https://pypi.org/pypi/pydantic-core/json` exactly. It contains a 4,408,639-byte
`_pydantic_core.cpython-314-wasm32-emscripten.so` (Rust/PyO3, the compiled extension).

Against the ABI build's own `python.sh` (no Kotlin involved -- this is a CPython Emscripten
loader question, not a Kotlin one):

```
pydantic_core imported OK, version: 2.48.0
validate_python("42") -> 42
raised: ValidationError
```

Against the stock build, same wheel, same `python.sh` harness, as the negative control:

```
ImportError: could not load dynamic lib: .../_pydantic_core.cpython-314-wasm32-emscripten.so
LinkError: WebAssembly.Instance(): Import #204 "env" "__cpp_exception": tag import requires a WebAssembly.Tag
```

identical to what the original report quoted. Comparing the two `python.wasm` binaries directly:
the stock build has no tag section and no `__cpp_exception` symbol, the ABI build has two tags and
the symbol -- and that difference is exactly what separates the pass above from the failure. The
unwinding ABI (`-fwasm-exceptions -sSUPPORT_LONGJMP=wasm`) is both present only in the ABI build
and the thing that gates loading a compiled `pyemscripten_2026_0` wheel; PEP 783's premise that
matching the ABI unlocks compiled extensions holds up on this tree, for this wheel.

Scope of what this settles: one wheel (pydantic-core), one path (import + a validator call +
exception raise). It does not check every C-API surface a compiled extension might touch, nor
whether other wheels on this tag rely on something the ABI build's flag set doesn't cover.
