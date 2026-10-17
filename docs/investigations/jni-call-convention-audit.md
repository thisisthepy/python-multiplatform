# JNI calling convention audit

Android binds CPython through `RegisterNatives`. `@CriticalNative` and `@FastNative` both stop the
ART collector for the duration of the call, and `@CriticalNative` receives no `JNIEnv`. So neither
may be used for a function that can re-enter the runtime, which means any function that can run
Python (`__del__`, `__getattr__`, a module's top-level code), with upcalls live, that Python can
be Kotlin.

## Conclusion

1. **No re-entrant function is live under a GC-blocking convention.** The audit's headline
   ("six re-entrant functions registered `@CriticalNative`, demote them") was a false alarm: it
   read a block-commented region of dead pre-migration code in `EmbedAPI.android.kt` as live, and
   it read the registration table as the configuration. `jni_onload.def` registers all three
   conventions per function (bare name = `@CriticalNative`, `F` = `@FastNative`, `N` = ordinary);
   the convention that runs is chosen at the call site, and the six already call the `N` variant.
2. **The proposed promotion to `@CriticalNative` is rejected.** It is a loss on API 34+ (table
   below), is not a relabel (needs a new raw-symbol table entry, declaration and call-site
   branch), and three of its eight targets are not leaves.
3. **One change was made instead:** `PyList_GetItemRaw` got a `@FastNative` twin
   (`PyList_GetItemRawF`) and a `preferFastNative` branch; it had been the only declaration that
   ignored the device axis, on the per-element path of bulk list iteration.
4. **The classification is now derived, not written.** The hand-written table in the original
   audit (71 rows) went stale on the next commit and its "Unresolved entries: none" claim was
   wrong (header prototypes can condemn a function but never clear one: `void Py_Initialize(void)`
   and `int PyRun_SimpleString(const char*)` mention no `PyObject *` and both run arbitrary
   Python). A test re-derives it on every desktop build.

**Status: acted on.**
- Classification and its guards: `python-multiplatform/src/desktopTest/.../native/ffi/JniCallConventionClassificationTest.kt`
  (`noLiveCallSiteReachesAGcBlockingBindingOutsideThePromotedSet`,
  `everyGcBlockingDeclarationIsEitherPromotedOrQuarantined`, `quarantinedDeclarationsHaveNoCallSiteAnywhere`,
  `criticalNativeRegistrationsTakeNoJniEnvAndTheOtherTwoDo`, `theSignatureRuleDoesNotClaimToProveLeafness`).
- Decision procedure for a new call site: `androidMain/README.md`, "Choosing a convention for a new
  call site" (five steps, enforced by the test above).
- `PyList_GetItemRawF`: `androidMain/.../bindings.kt`; branch in `EmbedAPI.android.kt`
  (`preferFastNative`); device check `androidInstrumentedTest/.../JniWiringTest.kt`
  `bothListGetItemConventionsAgree`. SPEC C-3 is the contract.
- The `PyList_GetItemRawF` change was compile-verified only when written; whether
  `bothListGetItemConventionsAgree` has been run on a device since is not recorded here.

## The eight live GC-blocking call sites

All eight are leaves on the success path; all can allocate a GC-tracked exception on the failure
path. That second column is conditional and second-order (the exception instance is GC-tracked, so
it can cross the collection threshold, so the collector can run a `__del__`); it is not a reason
to demote them, they are the measured hot path. It is why "provably a leaf" is not the promotion
test, the honest test is *leaf on the success path, and measured worth it*.

| call site | success path | failure path |
|---|---|---|
| `Py_IsInitialized` | reads a global | - |
| `Py_GetVersion` | static `const char*` | - |
| `PyErr_Occurred` | reads thread state, borrowed | - |
| `PyLong_FromLongLong` | non-GC allocation | preallocated `MemoryError` |
| `PyUnicode_FromString` | non-GC allocation | `UnicodeDecodeError` |
| `PyUnicode_AsUTF8` | caches UTF-8 form | `UnicodeEncodeError` on lone surrogates |
| `PyList_Size` | reads `ob_size` | `PyErr_BadInternalCall` |
| `PyList_GetItem` | indexes item array | `IndexError` |

## Numbers

Change in per-call cost, net of the Kotlin floor, from moving a function off ordinary JNI
(source: `docs/design/downcall-design.md`, `androidMain/README.md`; ART emulators / device per those
documents, conditions are in them, not re-measured here):

| API | ordinary → `@FastNative` (ns) | ordinary → `@CriticalNative` (ns) |
|---|---|---|
| 26 | −6.64 | −43.47 |
| 30 | −41.33 | −73.33 |
| 31 | −21.68 | −45.99 |
| 33 | −5.79 | −7.86 |
| 34 | −5.84 | **+16.54** |
| 36 | −14.77 | **+25.73** |

`@CriticalNative` is a loss on API 34 and 36. `PyList_GetItemRaw` motivation: ROADMAP §5 measured
`list → LongArray`, 1000 elements, at 50065.89 ns on API 36 hardware (50 ns/element) against a
`@CriticalNative` net cost of 44.05 ns there (an upper bound on the convention's share, since the
50 ns also covers the loop and pointer conversion). Predicted after the twin: 44.05 → 3.55 ns per
element on API 36, a prediction, not a recorded measurement.

## The three rejected promotions that were not leaves

| | audit said | actually |
|---|---|---|
| `PyGILState_Release` | "unlocks mutex (non-blocking)" | the release that drops the counter to zero deletes the thread state; clearing it decrefs its dict and exception state, so `__del__` runs |
| `PyThreadState_GetDict` | "reads struct pointer" | allocates the thread dict with `PyDict_New` on first call per thread, a GC-tracked allocation |
| `PyEval_InitThreads` | "initializes locking" | a no-op kept for the stable ABI; promoting it buys only the convention delta |

The genuine leaves among the eight are `Py_IncRef`, `Py_NewRef`, `Py_XNewRef`,
`PyGILState_GetThisThreadState`, `PyEval_SaveThread`; only the three refcount operations are hot
enough for tens of nanoseconds to accumulate.

## Rules of thumb for a new function

1. Any decref is re-entrant (the count can hit zero → `__del__`).
2. Allocating a GC-tracked container (`PyTuple_New`, `PyDict_New`, instances) can cross the
   collection threshold and run `__del__` on unreachable cycles.
3. Anything that blocks (`PyGILState_Ensure`, `PyEval_RestoreThread`) stays ordinary: a thread
   waiting for a lock while marked `@CriticalNative` keeps the JVM from a safepoint, and deadlocks
   if the GIL holder triggers a JVM GC.
4. Dict lookups are re-entrant (`__eq__`/`__hash__` on keys).
5. Non-GC allocation (`PyLong_FromLongLong`, `PyUnicode_FromString`) is a leaf on success.
6. Pure increments (`Py_IncRef`, `Py_NewRef`) are leaves.

## Registrations added after the audit

The audit read 71 registrations; the table later held 187 (116 added in `9abc8edd` and
`aea09585`) and 366 when the resolution was written. All 116 are registered `N`-suffixed with a
plain `@JvmStatic external fun` and no `@CriticalNative`/`@FastNative` twin, so no call site can
reach a GC-blocking variant of them. Of the 42 string-carrying ones, 31 are re-entrant (run module
or arbitrary code, a Python-level hook, a codec lookup, drop a reference, or never return) and 11
are leaves on success (`PyBytes_FromStringN`, `PyBytes_AsStringN`, `PyByteArray_AsStringN`,
`PyUnicode_EqualToUTF8N`, `PyUnicode_CompareWithASCIIStringN`, `PyUnicode_InternFromStringN`,
`PySys_GetObjectN`, `PyImport_GetMagicTagN`, `PyEval_GetFuncNameN`, `PyEval_GetFuncDescN`,
`Py_EnterRecursiveCallN`); none should be promoted (cold error/introspection surface, whole win
under 40 ns on a call that happens once). Later unregistrations (CPython 3.15 removals, per ROADMAP
§9): `PyImport_ImportModuleNoBlockN`, `PySys_ResetWarnOptionsN`; `PyWeakref_GetObjectN` became
`PyWeakref_GetRefN`.

## How to reproduce

Classification (desktop, source-level, no device):

```bash
./gradlew :python-multiplatform:desktopTest --tests 'python.native.ffi.JniCallConventionClassificationTest' \
    --console=plain > .tmp/jni-classification.log 2>&1; echo "EXIT=$?"
```

Per-convention costs: `androidInstrumentedTest/.../native/ffi/JniOverheadBenchmark.kt` on
`pmp_api26` and `pmp_api36` (harness: `./benchmarks/cost-table.sh`, see
[`cost-table.md`](cost-table.md)).

The full original, with the 71-row table, the false-alarm reasoning and the 42-function breakdown,
is [`docs/archive/investigations/jni-call-convention-audit.md`](../archive/investigations/jni-call-convention-audit.md).
