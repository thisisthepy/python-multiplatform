# YouTrack Issue Draft: Kotlin/Wasm (wasmJs) emits unbounded memory, preventing Emscripten interoperability

## Title
Wasm memory export has no maximum limit, breaking Emscripten `-sIMPORTED_MEMORY` compatibility

## Description

**Environment**
Kotlin version: 2.0.20 (and 2.4.10 tested)
Target: `wasmJs`

**Problem**
When building a `wasmJs` module, the Kotlin compiler generates a linear memory and exports it (e.g., `(memory (export "memory") 0)`). However, the memory is emitted with `WasmLimits(0, null)` (no maximum limit). 

This becomes a critical blocker when attempting to interoperate with C/C++ code compiled by Emscripten. If we try to share the linear memory by having Emscripten import Kotlin's memory (using `-sIMPORTED_MEMORY`), Emscripten's instantiation fails with:

```
LinkError: memory import has no maximum limit, expected at most 4294967295
```

WebAssembly requires that a supplied memory's limits fit within the importer's declared limits. Emscripten always declares a maximum limit for its memory (typically the Wasm32 ceiling of 4GB, or 65536 pages). Because Kotlin's memory is unbounded, it can never satisfy Emscripten's import requirements.

**Why this is important**
We are embedding CPython (compiled to WebAssembly via Emscripten) inside a Kotlin Multiplatform library. To avoid expensive JavaScript trampoline calls and data copying across the JS boundary, we need both modules to share a single linear memory. 

Our testing shows that Kotlin's WasmGC `wasmJs` module does not actively use the linear memory for its own allocations (except if explicitly using `withScopedMemoryAllocator`), making it safe to export and hand over to Emscripten. However, this single missing `max` limit in the Wasm memory section prevents the instantiation graph from linking. 

**Workaround**
Currently, we must manually patch the emitted `.wasm` binary's memory section (Section 5) to include a maximum limit, e.g., `{min: 0, max: 32768}`. Once patched, the modules link perfectly, and bidirectional memory sharing works without issues.

**Proposed Solution**
Please provide a way to specify the maximum memory pages for the emitted memory in `wasmJs`, or simply emit the Wasm32 ceiling (65536 pages) by default. For example, a compiler flag like `-Xwasm-memory-maximum=<pages>` would eliminate the need for brittle binary post-processing.
