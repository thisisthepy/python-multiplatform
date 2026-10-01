# Threading model and Stable ABI

## Decision

Parallelism will come from **free-threaded CPython**, not from per-interpreter GIL sub-interpreters.

The library ships **two flavours**, and the developer picks one by choosing which interpreter build
they depend on:

| Interpreter | Threading behaviour |
|---|---|
| `python3.X` (GIL) | Kotlin threads each attach a thread state and serialise on the GIL |
| `python3.Xt` (free-threaded) | Each Kotlin thread talks to its own free-running Python thread |

**Free-threading is supported from 3.15t onwards only.** Earlier versions are GIL-only, for the
reason set out below.

## Why not per-interpreter GIL

PEP 684 gives real parallelism on GIL builds by giving each sub-interpreter its own GIL. It was
evaluated and rejected for this project.

It is technically reachable. `Py_NewInterpreterFromConfig` is exported from libpython —
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

The blocker is not the ABI — it is **extension compatibility**. Per-interpreter GIL requires every C
extension to implement multi-phase init and declare `Py_mod_multiple_interpreters`; extensions that
do not simply fail to import. That would cost exactly the Python libraries this project exists to
share, in return for parallelism.

## Why 3.15t and not 3.14t

Free-threaded builds **do not support the Limited API or the Stable ABI** in 3.13 or 3.14. All ~330
bindings here target the Stable ABI, so a free-threaded 3.14 build cannot be used without abandoning
it and recompiling per Python version — which would defeat the version-parameterised acquisition
pipeline.

[PEP 803](https://peps.python.org/pep-0803/) — **Final**, targeting 3.15 — introduces `abi3t`, a
Stable ABI variant for free-threaded builds, with `abi3t` / `abi3.abi3t` wheel tags and forward
compatibility across all later versions. 3.15 is therefore the first release where free-threading
and the Stable ABI coexist.

### What abi3t requires of us

| Requirement | Our status |
|---|---|
| `PyObject` / `PyVarObject` become incomplete types; no field access | **Compatible.** Verified: `PyObject` appears only as `CPointer<PyObject>` and is never dereferenced. The only `.pointed` uses in the tree are commented-out `JNIEnv` code in `JniExport.kt`. |
| Extensions may not embed `PyObject` in their own structs | Compatible — nothing does. |
| `PyModExport` hook (PEP 793) instead of static `PyModuleDef` | Not yet relevant. We embed CPython rather than building an extension module. **It becomes relevant for the upcall work**, where Kotlin classes are exposed to Python. |
| No backwards compatibility with 3.14 or earlier | Accepted; free-threading is gated to 3.15t. |

## PEP 809 may collapse the two flavours into one

[PEP 809](https://peps.python.org/pep-0809/) — **Draft**, also targeting 3.15 — would replace `abi3`
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

## Prebuilt availability, as of the 20260807 python-build-standalone release

| Platform | GIL | Free-threaded |
|---|---|---|
| macOS arm64 / x86_64 | ✅ | ✅ (incl. `3.15.0rc1`) |
| Linux x86_64 | ✅ | ✅ (incl. `3.15.0rc1`) |
| Windows x86_64 | ✅ | ✅ (incl. `3.15.0rc1`) |
| Android (python.org) | ✅ | ❌ none published |
| iOS (Python-Apple-support) | ✅ | ❌ none published |

Desktop free-threaded builds exist today, including 3.15 release candidates. **Mobile has none**, so
enabling free-threading before mobile artefacts appear would split the threading model across
platforms — and with it the object-lifetime and thread-state design layered on top.

Switching the default requires all three of:

1. CPython 3.15 final, for `abi3t`
2. Free-threaded mobile artefacts from python.org and Python-Apple-support
3. Adequate free-threaded wheel coverage for the C extensions users care about

`gradle.properties` already carries `pythonFreeThreaded`, defaulting to `false`, so the switch is a
configuration change once those hold.

## What has to be built either way

The binding layer currently performs **no thread-state management at all**. There are no
`PyGILState_Ensure` / `PyGILState_Release` bindings, no calls to them, and `Python3.withPython` only
checks an initialisation flag. Calling into Python from any thread other than the initialising one is
therefore broken today, independently of GIL versus free-threading.

Free-threading does not remove this requirement: `PyGILState_Ensure` still exists there and still
attaches a thread state. What disappears is contention on a global lock, not the need for a thread
state. The work is nearly identical for both flavours, so building it now also prepares the
free-threaded path.

Two further consequences of free-threading, not yet addressed:

- Kotlin-side caches become concurrently accessed. `PyType.getInstance` currently memoises into a
  plain `mutableMapOf`, which is a data race the moment two Kotlin threads run Python in parallel.
- `PyGILState_Ensure` per call is expensive. Thread state should be cached per thread and acquired at
  entry and exit boundaries, not around every FFI crossing.
