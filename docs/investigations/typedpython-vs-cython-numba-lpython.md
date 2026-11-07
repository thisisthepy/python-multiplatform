# TypedPython against Cython, numba and LPython (design-level comparison)

Requested by the user (2026-10-04, via the leader): compare the current TypedPython design with
Cython, numba and LPython **logically first**; measurements come later (the harness is on
`feat/bench-cython-numba`, not yet measured). Issues: #41, #125.

Evidence tags: **[doc]** stated in the tool's own documentation (links at the end), **[src]** read in
this repository's code, **[measured]** a number from our own runs (2026-10-03 quiet window, develop
e888e7f5, macOS arm64), **[inferred]** reasoning, not verified.

## Conclusion

**Where TypedPython wins**

1. **Deployment.** It is ahead-of-time C that becomes a CPython extension, built with the target's own C compiler. So it runs on iOS, on Android, and next to a GraalVM native image. [src] [doc]
   - numba compiles at run time through LLVM, and its AOT path (`numba.pycc`) is deprecated with no replacement yet [doc]. It needs LLVM on the device, which rules out iOS. [inferred]
   - LPython AOT produces standalone binaries, and its `@lpython` JIT needs a C compiler at run time [doc]. Neither fits an app that embeds CPython. [inferred]
   - Cython has the same deployment model as TypedPython. [doc]
2. **CPython identity by default.** The others give up semantics either by default or once you type the code.
   - What TypedPython keeps:
     - int overflow promotes to a big int, via deopt [src];
     - bounds checks stay unless proven [src];
     - exceptions are CPython's own [src];
     - signals and GIL hand-off work since #141 phase 1 [src].
   - numba: integers are fixed width and wrap; bounds checks are off by default; globals are frozen at compile time. [doc]
   - Cython: typed C integers wrap unless `overflowcheck=True` (off by default). [doc]
   - LPython: requires its own fixed-width types (`i32`, `f64`). [doc]
3. **No new syntax, gradual by function.**
   - TypedPython takes types from ordinary annotations plus Pyrefly's inference. A function it cannot prove stays interpreted, with a reason. [src]
   - Cython needs `cdef` or `cython.*` types to leave the object path. [doc]
   - LPython needs its own type names. [doc]
   - numba needs no annotations, but rejects code outside its subset with a `TypingError`. [doc]
4. **Kotlin interop inside compiled code.** No other tool has a counterpart. [src]

**Where TypedPython loses, and the design item that would close each gap**

| Gap | Who wins it | Closing design item | Identity cost |
|---|---|---|---|
| Per-call overhead on small leaf functions (spectral-norm's `eval_a`): `tp_enter_call`/`tp_leave_call` on every call, probably no inlining | numba and Cython inline small functions [inferred] | Depth precheck plus a fast entry for callees outside every cycle, with a fallback to the per-call path (#41 plan), then let the C compiler inline | none |
| Checked i64 arithmetic in hot integer code | numba, Cython typed, LPython (unchecked) | Interval proofs that drop provably safe checks (for example `i + j` with both below `len(list)`), and a shift for a floor division by a power of two of a proven non-negative value | none |
| Lists are copied in and written back, O(n) per call | numba (numpy arrays) and Cython (memoryviews) are zero-copy [doc] | Accept buffer-protocol arrays (`array.array`, numpy, memoryview) as zero-copy `ArrayParam`. Since no user code runs while the array is live (the existing contract), direct access is observably the same | none |
| Strings and dicts (wordfreq is 1.1x [measured]) | numba `typed.Dict` [doc], Cython C-level code [inferred] | An exact-type `dict[str, int]` and `str` fast path that calls the same C API (`PyDict_GetItem`/`SetItem` on exact `dict` and exact `str` keys, so no user `__hash__` or `__eq__` runs) | none |
| Object allocation (binary-trees is 1.7x [measured]) | Cython `@cython.freelist` [doc]; numba keeps objects out of CPython's GC [doc] | N-12 virtual objects (#143) | **GC timing**, a user decision |
| Float reductions are not vectorised | numba `fastmath=True`, Cython `-ffast-math` (both opt-in) [doc] | None that keeps identity: reassociating float sums changes results. Integer reductions and element-wise loops still vectorise. An opt-in `fastmath` would break identity, so it is a user decision | **yes**, if offered |
| Parallel loops | numba `parallel=True`/`prange`, Cython `prange` with OpenMP [doc] | After free-threaded support: parallel loops whose result is order-independent (element-wise), or reductions in a fixed order | none if restricted that way |

Defaults are on the same footing: numba's `fastmath`, `parallel` and `nogil` are all off by default
[doc], and Cython's default directives keep Python semantics (`boundscheck=True`, `wraparound=True`,
`cdivision=False`) [doc]. Most of the "they are faster" headline numbers come from opting out of
semantics.

## 1. Where type information comes from, and what happens when it is missing

| | TypedPython | Cython | numba | LPython |
|---|---|---|---|---|
| Source | Ordinary annotations plus Pyrefly's static inference [src] | `cdef`/`cython.*` declarations; in pure mode, `float` becomes a C double while `int` stays a Python object [doc] | Specialised at run time from argument types, with inference over the bytecode [doc] | Mandatory annotations in its own types (`i32`, `f64`, `list[i32]`) [doc] |
| When it cannot type something | The function stays interpreted, with a diagnostic; a guard failure at run time deopts that one call [src] | Compiles anyway on the generic object path (slow, but correct) [doc] | `TypingError`: the function does not compile [doc] | Compile error [doc] |
| Granularity | Per function, and per call when deopting [src] | Per variable | Per function, per argument signature | Per program |

## 2. Code generation path and when compilation happens

| | Path | When | Mobile / GraalVM-embedded CPython |
|---|---|---|---|
| TypedPython | Typed IR, then C, then the target's C compiler, giving a CPython extension module [src] | AOT, incremental per module [src] | Yes: NDK, Xcode, desktop clang [src] |
| Cython | C, then a C compiler, giving an extension module [doc] | AOT | Yes (Kivy and BeeWare ship Cython extensions) [inferred] |
| numba | LLVM IR, JIT through llvmlite [doc] | At the first call per signature; `cache=True` stores it on disk [doc]; `numba.pycc` AOT is deprecated [doc] | No on iOS (JIT, LLVM on the device) [inferred] |
| LPython | ASR, then LLVM, C, C++, WASM or x86 [doc] | AOT standalone binaries, or the `@lpython` JIT (C backend only at present) [doc] | Not as part of an embedded CPython [inferred] |

## 3. Object model

- **TypedPython** [src]
  - Unboxing: scalars (`int` as i64, `float` as double, `bool`) are unboxed.
  - Arrays: `list[float]` and `list[int]` parameters become native arrays through copy-in and write-back, and local arrays are native.
  - Classes: `__slots__` classes (N-11) are real objects with direct slot access.
  - Everything else is an opaque object.
  - Refcounting: every object operation does a real incref or decref.
  - Not covered: no dict or str fast path.
- **Cython**
  - Unboxing: C types where declared. [doc]
  - Classes: `cdef class` objects are real CPython objects with C fields. [doc]
  - Arrays: memoryviews give zero-copy access to buffers. [doc]
  - Refcounting: Cython's own code generation does it. [doc]
  - Lists and dicts go through the C API. [inferred]
- **numba**
  - Its data lives outside CPython: arrays are managed by NRT, and `typed.List` and `typed.Dict` are numba's own types. [doc]
  - Classes: `jitclass` is experimental. [doc]
  - Lists: reflected Python lists are pending deprecation. [doc]
  - Interop: Python objects appear only at the boundary or in `objmode`. [doc]
- **LPython**
  - Its own value types, with lists and dicts of fixed element types. [doc]
  - Interop: CPython objects are reached through `@pythoncall`. [doc]

## 4. CPython identity

| | TypedPython | Cython (typed) | numba | LPython |
|---|---|---|---|---|
| Integer overflow | Promotes to a big int (deopt) [src] | Wraps; `overflowcheck` is off by default [doc] | Fixed width, wraps [doc] | Fixed width (`i32`/`i64`) [doc] |
| Bounds | Checked unless proven [src] | `boundscheck=True` by default [doc] | Off by default [doc] | Not documented [doc gap] |
| Division by zero | CPython's `ZeroDivisionError` [src] | `cdivision=False` by default [doc] | `error_model='python'` by default [doc] | Not documented |
| Globals | Read live, or through an entry snapshot plus a guard (closed functions) [src] | Live | **Frozen at compile time** [doc] | Not applicable |
| Signals, other threads | Polled since #141 phase 1; deferred while a snapshot is live [src] | Not polled unless the code calls `PyErr_CheckSignals` [inferred] | Not polled; `nogil=True` releases the GIL [doc] | Not applicable |
| Trace and profile | No events (#142) [src] | `linetrace`/`profile` directives, off by default [doc] | None [inferred] | Not applicable |
| GC timing | Unchanged for real objects; N-12 would change it [src] | Unchanged [inferred] | Its data is not GC-tracked [inferred] | Not applicable |
| Exceptions | CPython's own; arrays write back partial updates [src] | CPython's own | Limited (constant arguments); memory allocated in a function that raises leaks [doc] | Not documented |

## 5. Loops and arrays

- Bounds-check elimination:
  - TypedPython uses interval proofs that the verifier checks again (`proven` ops). [src]
  - Cython and numba drop all bounds checks when told to (`boundscheck=False` or the default). [doc]
- Vectorisation:
  - All four leave it to the backend (clang or LLVM). [inferred]
  - TypedPython builds with `-ffp-contract=off -fno-fast-math` for bit-identical floats, so float reductions do not vectorise, and neither do they under numba's or Cython's defaults. [src] [inferred]
- Parallelism:
  - numba and Cython have `prange`. [doc]
  - TypedPython has none yet; it needs the free-threaded build first (AGENTS.md §12.8).

## 6. Call boundary cost

- **Python → compiled code**
  - TypedPython: a vectorcall wrapper checks argument types exactly and copies arrays in. The copy is O(n) per call, which is a real cost for many small calls on large lists. [src]
  - Cython: a C call, plus unboxing, plus buffer acquisition for memoryviews. [inferred]
  - numba: a dispatcher that typechecks the arguments against the compiled signatures. [doc] [inferred]
- **compiled code → Python**
  - TypedPython and Cython: a C API vectorcall. [src] [inferred]
  - numba: only through `objmode` blocks. [doc]
  - LPython: through `@pythoncall`. [doc]
- **compiled → compiled**
  - TypedPython: a direct C call, but with depth counting on every call. That is the spectral-norm finding. [src]
  - Cython: a direct C call. [inferred]
  - numba: calls between jitted functions inline through LLVM. [inferred]

## 7. The three numeric benchmarks: theoretical ceilings

The measured numbers are interleaved medians from the 2026-10-03 quiet window, speedup against CPython 3.13 with compiled times. [measured]

| benchmark | TypedPython now | Rust | Node | Ceiling for numba, Cython typed and LPython [inferred] | What TypedPython needs to reach it |
|---|---|---|---|---|---|
| nbody | 31.3x (0.129 s) | 0.059 s | 0.117 s | About C speed, near Rust. Short arrays and float math, no reductions across bodies | Find the remaining 2x to Rust first. Candidates: bounds checks not removed on `pos[i]`-style indexing, no inlining across helper calls, and the call per step. Needs the #125-style profile; not verified |
| spectral-norm | 6.3x (0.518 s) | 0.027 s | 0.068 s | About C speed: `eval_a` inlined, integer arithmetic unchecked, the floor division a shift | (1) the depth precheck and fast entry; (2) inlining of `eval_a`; (3) the interval proof `i + j < 2n`, plus a shift for `// 2` once the value is proven non-negative. The `(i+j)*(i+j+1)` product cannot be proven safe without a bound on `n`, so its check stays, but a predicted branch costs little |
| fannkuch-redux | 23.5x (0.250 s) | 0.249 s | 0.261 s | About C speed | Already at Rust parity [measured]; nothing needed |

## LPython: development state (checked 2026-10-04)

- The README says "currently in alpha stage and under heavy development". [doc]
- Latest release: v0.22.0 (2024-07-08). Last push to `main`: 2025-12-11. 580 open issues. [src: GitHub API]
- It compiles "a subset of Python" with mandatory LPython type annotations. [doc]
- Backends: LLVM, C, C++, WASM, Julia, x86. [doc]
- The `@lpython` JIT supports only the C backend at present. [doc]
- It reaches CPython libraries through `@pythoncall`. [doc]

Assessment [inferred]: the project is active but slow, a long way from a stable 1.0. It is not built to run inside an embedded CPython, so it is not a candidate backend for this repository. The design doc's earlier note (§4.3.3 "upstream activity moved to LFortran") still matches the release history: no release since 2024-07.

## Sources

- numba: [JIT options](https://numba.readthedocs.io/en/stable/reference/jit-compilation.html), [deviations from Python semantics](https://numba.readthedocs.io/en/stable/reference/pysemantics.html), [deprecation notices](https://numba.readthedocs.io/en/stable/reference/deprecation.html)
- Cython: [compiler directives](https://cython.readthedocs.io/en/latest/src/userguide/source_files_and_compilation.html)
- LPython: [repository README](https://github.com/lcompilers/lpython), [2023 announcement](https://lpython.org/blog/2023/07/lpython-novel-fast-retargetable-python-compiler/), GitHub API for release and push dates
- TypedPython: `python-multiplatform-ksp/src/main/python/typedpython/` (`ir.py`, `cgen.py`, `runtime/tp_runtime.h`, `cbuild.py`), `docs/design/typedpython.md` §4.3
