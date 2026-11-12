# Threading model and Stable ABI

## Decision

Parallelism comes from **free-threaded CPython**, not from per-interpreter GIL sub-interpreters
(AGENTS.md §12.8).

Maintainer, #158 (2026-10-04): "Free-threaded is the default and the only build. The GIL build is
not kept as an option. Baseline is CPython 3.15t." Desktop moved first (leader, 2026-11-13: "flip the
desktop default to 3.15t now, before the #377 alpha03 release"); the other platforms follow, so today
the build differs by platform (SPEC T-1):

| Platform | Interpreter | Threading behaviour |
|---|---|---|
| Desktop JVM, `macosArm64`, `linuxX64` | `python3.15t` (free-threaded), the only desktop build | Each Kotlin thread talks to its own free-running Python thread |
| Android (ART, androidNative), iOS | `python3.15` (GIL) until #328 | Kotlin threads each attach a thread state and serialise on the GIL |
| wasmJs | GIL until #175 | single-threaded |

The per-platform fact is `embedsFreeThreadedPython` (commonMain `EmbeddedPythonBuild.kt`, an
`expect`/`actual`); `-PpythonFreeThreaded` is removed. The interpreter version is pinned by
`pythonVersion` in `gradle.properties` (3.15.0). Before #158 free-threading was a desktop opt-in, and
the whole desktop suite ran on 3.14.7t (236 tests, 0 failures, 1 skipped, ROADMAP §9; SPEC T-2); the
3.15t default has not yet been observed running the suite. How the earlier "from 3.15t"
conclusion was reached, and why it did not hold, is in
[`../archive/threading-and-abi-3.15t-gate.md`](../archive/threading-and-abi-3.15t-gate.md).

## Why not per-interpreter GIL

PEP 684 gives real parallelism on GIL builds by giving each sub-interpreter its own GIL. It was
evaluated and rejected for this project.

It is technically reachable. `Py_NewInterpreterFromConfig` is exported from libpython,
verified with `nm` on `libpython3.14.dylib`:

```
00000000003009bc T _Py_NewInterpreterFromConfig
```

and `PyInterpreterConfig` is only seven ints, so defining it on our side is not hard:

```c
typedef struct {
    int use_main_obmalloc;
    int allow_fork;
    int allow_exec;
    int allow_threads;
    int allow_daemon_threads;
    int check_multi_interp_extensions;
    int gil;                            // PyInterpreterConfig_OWN_GIL = 2
} PyInterpreterConfig;
```

The Stable ABI is a compile-time contract, not a runtime one, and this project resolves symbols
dynamically, so calling a non-limited symbol is possible.

The blocker is not the ABI, it is **extension compatibility**. Per-interpreter GIL requires every C
extension to implement multi-phase init and declare `Py_mod_multiple_interpreters`; extensions that
do not simply fail to import. That would cost exactly the Python libraries this project exists to
share, in return for parallelism.

## Stable ABI on free-threaded builds

CPython's Limited API / Stable ABI (`abi3`) is not offered by free-threaded builds in 3.13 and 3.14.
That does not block this project: the ~330 bindings are symbols resolved dynamically at run time, and `Py_LIMITED_API` is not defined anywhere in the build, so no
compile-time ABI contract is in play (SPEC T-2). What matters is that the symbols exist in the
free-threaded `libpython3.14t`, and the suite passing on it shows they do. The cost of the
free-threaded name changes is in the loader: such an install ships `libpython3.14t.dylib`,
`lib/python3.14t/` and `bin/python3.14t` only (`Versions.abiFlags` carries the `t`; ROADMAP §9).

[PEP 803](https://peps.python.org/pep-0803/) (`abi3t`, targeting 3.15) would make a Stable ABI
available on free-threaded builds for extension *modules*. What it asks of us if we ever adopt it:

| Requirement | Our status |
|---|---|
| `PyObject` / `PyVarObject` become incomplete types; no field access | **Compatible.** `PyObject` appears only as an opaque pointer and is never dereferenced. |
| Extensions may not embed `PyObject` in their own structs | Compatible, nothing does. |
| `PyModExport` hook (PEP 793) instead of static `PyModuleDef` | Not relevant: we embed CPython rather than building an extension module. It would matter if Kotlin classes were ever exposed as a CPython extension module. |

## PEP 809 may collapse the two flavours into one (forward-looking)

[PEP 809](https://peps.python.org/pep-0809/), **Draft**, also targeting 3.15, would replace `abi3`
with time-bound versioned ABIs (`abi2026`), each frozen for at least ten years with at least five
years of overlap. Crucially, a single extension compiled against `abi2026` supports **both**
free-threaded and GIL-enabled builds.

The two PEPs are not in conflict: PEP 803 explicitly frames `abi3t` as a transitional state ahead of
`abi2026`.

If PEP 809 lands, the plan simplifies:

| | PEP 803 only | With PEP 809 |
|---|---|---|
| Kotlin artefacts | two (abi3 / abi3t) | **one** (abi2026) |
| Interpreter distributions | two (`3.X` / `3.Xt`) | two (unchanged) |

Design for two artefacts, but keep the split shallow enough to collapse later.

## Prebuilt availability

Desktop (macOS arm64/x86_64, Linux x86_64, Windows x86_64) has GIL and free-threaded builds on the
python-build-standalone releases; the build uses only the `-freethreaded` archive (#158).
**Android (python.org) and iOS (Python-Apple-support) publish no free-threaded build**, so those
platforms stay GIL until the project's own builds land (#163, #328). Until then the threading model,
and the object-lifetime and thread-state design layered on top, are split across platforms; that is
the accepted cost of moving desktop first. The maintainer accepted the wheel-coverage cost on #158
("third-party cp315t / abi3t wheels lag the release for a while; pypackpack builds from sdist where
they are missing"). Table as of the 20260807 release, kept in the archive note above.

## Thread-state management

Every C API call holds an attached thread state (AGENTS.md §16, `commonMain/README.md`):
`withGIL { }` (`python/multiplatform/ffi/GILScope.kt`) calls `PyGILState_Ensure`/`Release` at the
outermost scope with a per-thread nesting counter, and `withoutGIL { }` wraps
`PyEval_SaveThread`/`RestoreThread` for long non-Python work. `Python3.withPython` goes through
`withGIL`. This is needed on **both** flavours: free-threading removes contention on a global lock,
not the requirement that a thread be attached before it touches any object.

Entry points reached from C (upcalls) take their own unconditional `PyGILState_Ensure` instead of
trusting the nesting counter (`UpcallTrampoline.attached`; reason in `commonMain/README.md`).

Consequences specific to free-threading, all recorded in ROADMAP §9 and
`docs/design/object-lifetime.md`:

- Deallocation of an object decref'd by a non-owning thread is deferred to the owner's eval-loop
  checkpoint; a pure C-API embedder never reaches one, so the library takes a periodic checkpoint
  itself (`maybeReachEvalCheckpoint`, `Python3.drainPendingReleases`; `Python3.autoDrainInterval` defaults on for free-threaded builds and off on the GIL build).
- Kotlin-side caches become concurrently accessed. `PyType.getInstance` still memoises into a plain
  `mutableMapOf` (`python/multiplatform/ffi/PyType.kt`), which relies on the caller holding the GIL;
  under free-threading that is a data race once two Kotlin threads create types in parallel. The
  desktop suite passing on 3.14t does not exercise that case.
- `PyGILState_Ensure` per call is expensive; the nesting counter keeps inner scopes cheap, and on a
  free-threaded build widening the outermost scope costs other threads nothing.
