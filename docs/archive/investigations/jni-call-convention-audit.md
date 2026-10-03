> **Superseded** by [docs/investigations/jni-call-convention-audit.md](../../investigations/jni-call-convention-audit.md) on 2026-10-03, the conclusion, numbers and reproduction steps live there; this is the full original narrative, kept for history.

# JNI Calling Convention Audit

Android binds CPython through `RegisterNatives`. Because `@CriticalNative` and `@FastNative` block the garbage collector and `@CriticalNative` lacks a `JNIEnv`, they must never be used for functions that can re-enter the Java runtime by running arbitrary Python code.

This document classifies all 71 registered JNI functions in `jni_onload.def`.

> ## Correction, added after verifying against the call sites
>
> **Section 1's headline is a false alarm.** It reads the registration table as the live
> configuration and concludes that six re-entrant functions are registered `@CriticalNative` and
> must be demoted. They are not live.
>
> `jni_onload.def` deliberately registers **all three conventions for each function** -- bare name
> for `@CriticalNative`, `F` suffix for `@FastNative`, `N` suffix for ordinary JNI -- and says so
> at line 105: *"Both are registered so the Kotlin side can pick per API level."* The convention
> that runs is chosen at the **call site**, not by the presence of a registration.
>
> Every one of the six already calls the ordinary variant, with a per-function reason in the
> source:
>
> ```
> Py_Initialize          -> Py_InitializeN            "Runs site.py ... never on a GC-blocking path"
> Py_Finalize            -> Py_FinalizeN
> PyErr_Clear            -> PyErr_ClearN
> PyRun_SimpleString     -> PyRun_SimpleStringN
> PyImport_ImportModule  -> PyImport_ImportModuleN
> PyObject_GetAttrString -> PyObject_GetAttrStringN
> ```
>
> The block in `EmbedAPI.android.kt` that appears to call the bare `@CriticalNative` names is
> **inside a `/** */` comment** -- dead code kept from before the migration. Reading it as live is
> what produced the finding.
>
> **Section 2 is a different matter and may be real.** Its promotion targets (`Py_IncRefN`,
> `PyEval_SaveThreadN`, and the rest) are registered *only* under `N`; there is no
> `@CriticalNative` registration to switch to. Acting on it means adding registrations as well as
> moving call sites, which is genuine work rather than a relabel.
>
> Two things to check before acting on it. The cost claim -- "~45ns on API 26-31, only ~2-4ns on
> API 34+" -- points the opposite way from what this repo measured: `@CriticalNative`'s net cost
> is near zero up to API 33 and rises sharply after (24.35ns on 34, 44.05ns on 36 hardware). If
> those measurements hold, promotion helps old devices and *hurts* new ones, which inverts the
> recommendation. And "Unresolved entries: none" overstates the confidence available from reading
> names and documentation alone; the classification below is a starting point for a human, not a
> settled answer.
>
> **What survives is the classification itself**, which is the useful part: it is the rule for
> choosing a convention when a *new* call site is written, and that is where the crash ROADMAP §3
> warns about would actually be introduced.
>
> Section 2 was checked against those measurements and **rejected**; see *Resolution* at the end of
> this document for the judgement, the three classifications in it that are wrong, and the one
> change that was made instead.


## 1. Mismatches

These functions are currently registered as `@CriticalNative` (no suffix) or `@FastNative` (F suffix), but they can execute arbitrary Python code. These are latent crashes and must be demoted to ordinary JNI.

- **`Py_Initialize`** / **`Py_InitializeF`**
  - **Re-entry path**: Imports the `site` module during startup by default, which executes module-level Python code.
- **`Py_Finalize`** / **`Py_FinalizeF`**
  - **Re-entry path**: Invokes `atexit` callbacks and executes `__del__` methods of surviving objects during teardown.
- **`PyErr_Clear`** / **`PyErr_ClearF`**
  - **Re-entry path**: Decrements the refcount of the current exception object, type, and traceback. If the refcount drops to zero, `__del__` is called.
- **`PyRun_SimpleString`** / **`PyRun_SimpleStringF`**
  - **Re-entry path**: Parses, compiles, and executes the provided string in the `__main__` module.
- **`PyImport_ImportModule`** / **`PyImport_ImportModuleF`**
  - **Re-entry path**: Executes the top-level Python code of the module being imported.
- **`PyObject_GetAttrString`** / **`PyObject_GetAttrStringF`**
  - **Re-entry path**: Attribute lookup dispatches to `__getattribute__`, `__getattr__`, or descriptor `__get__` methods written in user code.

## 2. Safe Promotions

These functions are registered as ordinary JNI (with the `N` suffix or implicitly ordinary) but are provably leaves or non-blocking, and therefore safe to promote. Given that `@CriticalNative` is practically free on API 26-33 but costs ~24ns net on API 34+ (while `@FastNative` is ~2ns on 34+), the value of promoting these depends on the target device.

- **`Py_IncRefN`, `Py_NewRefN`, `Py_XNewRefN`**
  - **Argument for promotion**: These simply increment the `ob_refcnt` struct field. They do not trigger deallocations, do not allocate memory (no GC), and do not call Python code. Safe for `@CriticalNative`.
- **`PyGILState_ReleaseN`, `PyEval_SaveThreadN`**
  - **Argument for promotion**: Unlocking the GIL mutex does not block and does not invoke Python code. Safe for `@CriticalNative`.
- **`PyGILState_GetThisThreadStateN`, `PyThreadState_GetDictN`**
  - **Argument for promotion**: These only read from Thread Local Storage or read a pointer from a struct. No Python execution. Safe for `@CriticalNative`.
- **`PyEval_InitThreadsN`**
  - **Argument for promotion**: Initializes locking (or is a no-op). Does not run Python code. Safe for `@CriticalNative`.
- **`asmListToArray`**
  - **Argument for promotion**: This composed function only calls `PyList_Size` and `PyList_GetItem`, both of which are O(1) leaf array reads that do not mutate refcounts. Because it accesses a Java `jlongArray`, it still requires `JNIEnv`, so it cannot be `@CriticalNative`. It is safe to promote to `@FastNative`.

## 3. Full Classification Table

| Function Name | Current Convention | Should Be | Can Reach Python? (Mechanism) |
|---|---|---|---|
| `Py_Initialize` | Critical | Ordinary | Yes. Imports `site` module during startup. |
| `Py_IsInitialized` | Critical | Critical | No. Reads a global boolean. |
| `Py_Finalize` | Critical | Ordinary | Yes. Runs `atexit` callbacks and `__del__` on remaining objects. |
| `PyErr_Clear` | Critical | Ordinary | Yes. Decrements exception refcount; dropping to 0 runs `__del__`. |
| `PyLong_FromLongLong` | Critical | Critical | No. Primitive `malloc`, does not trigger cyclic GC. |
| `PyList_Size` | Critical | Critical | No. Reads `ob_size` struct field. |
| `PyRun_SimpleString` | Critical | Ordinary | Yes. Executes arbitrary Python string. |
| `Py_GetVersion` | Critical | Critical | No. Returns static string pointer. |
| `PyImport_ImportModule` | Critical | Ordinary | Yes. Executes top-level module code. |
| `PyObject_GetAttrString` | Critical | Ordinary | Yes. Invokes `__getattr__` or `__get__`. |
| `PyErr_Occurred` | Critical | Critical | No. Reads thread state. |
| `echo0` | Critical | Critical | No. Probe, returns argument. |
| `echo1` | Critical | Critical | No. Probe, returns argument. |
| `echo2` | Critical | Critical | No. Probe, returns argument. |
| `echoNormal` | Ordinary | Ordinary | No. Probe, requires JNIEnv. |
| `echoFast` | FastNative | FastNative | No. Probe, requires JNIEnv. |
| `PyList_SizeFast` | FastNative | FastNative | No. Leaf read. |
| `PyList_SizeNormal` | Ordinary | Ordinary | No. Leaf read. |
| `Py_InitializeF` | FastNative | Ordinary | Yes. Imports `site` module during startup. |
| `Py_IsInitializedF` | FastNative | FastNative | No. Reads a global boolean. |
| `Py_FinalizeF` | FastNative | Ordinary | Yes. Runs `atexit` callbacks and `__del__`. |
| `PyErr_ClearF` | FastNative | Ordinary | Yes. Decrements exception refcount; dropping to 0 runs `__del__`. |
| `PyLong_FromLongLongF` | FastNative | FastNative | No. Primitive `malloc`, does not trigger cyclic GC. |
| `PyList_SizeF` | FastNative | FastNative | No. Reads `ob_size` struct field. |
| `PyRun_SimpleStringF` | FastNative | Ordinary | Yes. Executes arbitrary Python string. |
| `Py_GetVersionF` | FastNative | FastNative | No. Returns static string pointer. |
| `PyImport_ImportModuleF` | FastNative | Ordinary | Yes. Executes top-level module code. |
| `PyObject_GetAttrStringF` | FastNative | Ordinary | Yes. Invokes `__getattr__` or `__get__`. |
| `PyErr_OccurredF` | FastNative | FastNative | No. Reads thread state. |
| `PyList_GetItemRaw` | Critical | Critical | No. Borrows reference from array index (leaf). |
| `ffiDirectBufferAddress` | Ordinary | Ordinary | No. Uses JNIEnv. |
| `asmExec` | Ordinary | Ordinary | Yes. Composes `PyRun_String`. |
| `asmGetAttr` | Ordinary | Ordinary | Yes. Composes `PyObject_GetAttrString` and `PyErr_Clear`. |
| `asmListToArray` | Ordinary | FastNative | No. Only calls leaf functions (`PyList_Size`, `PyList_GetItem`). |
| `Py_InitializeN` | Ordinary | Ordinary | Yes. Imports `site` module during startup. |
| `Py_FinalizeN` | Ordinary | Ordinary | Yes. Runs `atexit` callbacks and `__del__`. |
| `PyErr_ClearN` | Ordinary | Ordinary | Yes. Decrements exception refcount; dropping to 0 runs `__del__`. |
| `PyRun_SimpleStringN` | Ordinary | Ordinary | Yes. Executes arbitrary Python string. |
| `PyImport_ImportModuleN` | Ordinary | Ordinary | Yes. Executes top-level module code. |
| `PyObject_GetAttrStringN` | Ordinary | Ordinary | Yes. Invokes `__getattr__` or `__get__`. |
| `Py_DecRefN` | Ordinary | Ordinary | Yes. Reaching refcount 0 deallocates and runs `__del__`. |
| `Py_IncRefN` | Ordinary | Critical | No. Only increments struct field `ob_refcnt`. |
| `Py_NewRefN` | Ordinary | Critical | No. Only increments struct field `ob_refcnt`. |
| `Py_XNewRefN` | Ordinary | Critical | No. Only increments struct field `ob_refcnt`. |
| `PyGILState_EnsureN` | Ordinary | Ordinary | No, but blocks. Blocking while suspending JVM GC risks deadlock. |
| `PyGILState_ReleaseN` | Ordinary | Critical | No. Unlocks mutex (non-blocking). |
| `PyGILState_GetThisThreadStateN` | Ordinary | Critical | No. Reads TLS. |
| `PyEval_SaveThreadN` | Ordinary | Critical | No. Unlocks GIL mutex (non-blocking). |
| `PyEval_RestoreThreadN` | Ordinary | Ordinary | No, but blocks waiting for GIL. Risks deadlock if GC is suspended. |
| `PyEval_InitThreadsN` | Ordinary | Critical | No. Initializes locking. |
| `PyThreadState_GetDictN` | Ordinary | Critical | No. Reads struct pointer. |
| `PyTuple_NewN` | Ordinary | Ordinary | Yes (inferred). GC tracked container allocation can trigger cyclic GC, which can run `__del__`. |
| `PyTuple_SetItemN` | Ordinary | Ordinary | Yes. Overwriting an existing item drops its refcount, potentially running `__del__`. |
| `PyDict_NewN` | Ordinary | Ordinary | Yes (inferred). GC tracked container allocation can trigger cyclic GC. |
| `PyDict_SetItemStringN` | Ordinary | Ordinary | Yes. Key hashing can invoke `__hash__` / `__eq__`, replacing values decrefs. |
| `PyDict_SetItemN` | Ordinary | Ordinary | Yes. Key hashing can invoke `__hash__` / `__eq__`, replacing values decrefs. |
| `PyDict_GetItemStringN` | Ordinary | Ordinary | Yes. Dictionary lookup can invoke `__eq__` on keys. |
| `PyUnicode_AsUTF8` | Critical | Critical | No. Primitive string allocation/cache. |
| `PyUnicode_AsUTF8F` | FastNative | FastNative | No. Primitive string allocation/cache. |
| `PyUnicode_FromString` | Critical | Critical | No. Primitive string allocation/cache. |
| `PyUnicode_FromStringF` | FastNative | FastNative | No. Primitive string allocation/cache. |
| `PyImport_AddModuleRefN` | Ordinary | Ordinary | Yes. Dict lookup/insertion can invoke `__eq__` or GC. |
| `PyRun_StringN` | Ordinary | Ordinary | Yes. Executes arbitrary Python code. |
| `PyObject_StrN` | Ordinary | Ordinary | Yes. Invokes `__str__`. |
| `PyObject_IsTrueN` | Ordinary | Ordinary | Yes. Invokes `__bool__` or `__len__`. |
| `PyObject_CallNoArgsN` | Ordinary | Ordinary | Yes. Invokes `__call__`. |
| `PyObject_CallObjectN` | Ordinary | Ordinary | Yes. Invokes `__call__`. |
| `testUpcallPrimitive` | Ordinary | Ordinary | Yes (JNI upcall). Explicitly calls into Java runtime. |
| `testUpcallString` | Ordinary | Ordinary | Yes (JNI upcall). Explicitly calls into Java runtime. |
| `testUpcallUnattached` | Ordinary | Ordinary | Yes (JNI upcall in new thread). |
| `testThreadCreateFloor` | Ordinary | Ordinary | No (thread floor probe), but uses ordinary. |
| `testAttachWithoutDetach` | Ordinary | Ordinary | Yes (attaches a pthread and calls back into Java). |

## 4. Rules of Thumb

Based on CPython's documented semantics, the following rules apply when classifying new functions:

1. **Any decref is re-entrant**: If a function can drop a reference count (e.g. `Py_DecRef`, `PyTuple_SetItem` dropping the overwritten item, `PyDict_SetItem`, `PyErr_Clear`), that reference can hit zero, deallocate the object, and execute arbitrary Python `__del__` methods.
2. **GC allocations are re-entrant**: Allocating GC-tracked containers (tuples, lists, dicts, instances) using functions like `PyTuple_New` or `PyDict_New` can cross the allocation threshold and trigger the cyclic garbage collector. The cyclic GC reclaims unreachable cycles, executing `__del__` on them.
3. **Blocking requires ordinary JNI**: Functions that acquire the GIL (`PyGILState_Ensure`, `PyEval_RestoreThread`) do not run Python, but they *must* stay on ordinary JNI. A thread waiting for a lock while marked `@CriticalNative` prevents the JVM from reaching a safepoint, which deadlocks the entire process if the thread holding the GIL triggers a JVM garbage collection.
4. **Dictionary lookups are re-entrant**: Even reading from a dictionary (`PyDict_GetItemString`) can invoke user code if custom keys exist that define `__eq__` or `__hash__`.
5. **Non-GC allocations are leaves**: Allocating primitives (e.g. `PyLong_FromLongLong`, `PyUnicode_FromString`) uses standard memory allocation (`malloc`) and does not participate in cyclic GC, making them pure leaves.
6. **Increments are leaves**: Functions that strictly increase reference counts (`Py_IncRef`, `Py_NewRef`) do not deallocate and thus do not trigger callbacks.

## Report Summary

- **Total Functions Classified**: 71 registrations.
- **Mismatches Found**: 12 (6 unique C functions exposed as both Critical and Fast). These are the dangerous ones: `Py_Initialize`, `Py_Finalize`, `PyErr_Clear`, `PyRun_SimpleString`, `PyImport_ImportModule`, and `PyObject_GetAttrString`. They can run arbitrary Python and must be moved to ordinary JNI.
- **Safe Promotions**: 8 functions (`Py_IncRefN`, `Py_NewRefN`, `Py_XNewRefN`, `PyGILState_ReleaseN`, `PyEval_SaveThreadN`, `PyGILState_GetThisThreadStateN`, `PyThreadState_GetDictN`, `PyEval_InitThreadsN`) can safely be moved from Ordinary to Critical, and `asmListToArray` to FastNative.
- **Cost of Being Wrong**:
  - Promoting a re-entrant function to `@CriticalNative` is a fatal crash when upcalls exist.
  - Leaving a leaf as Ordinary wastes ~45ns per call on API 26-31, but only ~2-4ns on modern API 34+. Safe promotions are therefore an optimization mostly relevant for older devices or high-volume loops, and should never override correctness.
- **Unresolved Entries**: None. All 71 registrations were resolved confidently based on documented CPython semantics (e.g. GC thresholds on container allocation).

  **This line is wrong, and it is the reason the audit is no longer the source of truth.** Header
  prototypes can condemn a function and never clear one: `void Py_Initialize(void)` and
  `int PyRun_SimpleString(const char*)` name no `PyObject *` and both run arbitrary Python. A
  re-derivation over the table as it stands leaves **65 undecided**, and the confidence recorded
  here is what put three re-entrant functions on the safe-to-promote list above.
  `JniCallConventionClassificationTest` now derives the classification on every desktop build and
  asserts that bucket stays non-empty, with those two functions named in it.

---

# Resolution

Written after checking section 2 against this repository's own measurements and against the
registration table as it stands at 187 entries (the audit read 71).

**The table holds 366 today**, so this resolution is itself a snapshot, roughly 180 registrations
have never been classified by any hand-written pass. That is why the classification moved into
`JniCallConventionClassificationTest`, which re-derives it from `bindings.kt`, `jni_onload.def` and
the bundled CPython headers on every desktop build. Its re-derivation agrees with the judgement
recorded here, the eight GC-blocking call sites reproduce exactly, no function was wrongly
promoted, and all 366 prologues match their convention, so what was missing was never correctness,
only a way for it to stay correct.

## The promotion to `@CriticalNative` is rejected

Section 2 argues its eight promotions are worth taking because `@CriticalNative` costs "~45ns on
API 26-31, only ~2-4ns on API 34+". That is backwards. Measured here, net of the Kotlin floor
(`docs/design/downcall-design.md`, `androidMain/README.md`), the *change* a promotion buys is:

| API | ordinary → `@FastNative` | ordinary → `@CriticalNative` |
|---|---|---|
| 26 | −6.64 | −43.47 |
| 30 | −41.33 | −73.33 |
| 31 | −21.68 | −45.99 |
| 33 | −5.79 | −7.86 |
| 34 | −5.84 | **+16.54** |
| 36 | −14.77 | **+25.73** |

`@CriticalNative` is a **loss** on the two newest levels measured. Taking section 2 as written
would slow every one of those eight functions down on any device from Android 14 onward.

It could be had behind the existing `preferFastNative` branch, critical below 34, ordinary above.
That is not being done, for three reasons.

**It is not a relabel.** `@CriticalNative` receives no `JNIEnv` or `jclass`, so it cannot reuse the
`f_*N` wrappers, which all have them. Each promotion needs a new table entry pointing at the raw
CPython symbol, a new `@CriticalNative` declaration, and a branch at the call site. Compare with a
`@FastNative` promotion, which is one annotation: the C signature is already right and the
registration name does not change.

**Three of the eight cannot be promoted at all.** They are not leaves:

| | audit says | actually |
|---|---|---|
| `PyGILState_Release` | "unlocks mutex (non-blocking)" | the release that drops the counter to zero deletes the thread state; clearing a thread state decrefs its dict and exception state, so `__del__` runs |
| `PyThreadState_GetDict` | "reads struct pointer" | allocates the thread dict with `PyDict_New` on first call per thread, a GC-tracked allocation, so rule 2 applies |
| `PyEval_InitThreads` | "initializes locking" | a no-op retained for the stable ABI (removed from the C API in 3.13; still exported by the bundled `libpython`, confirmed with `nm -D`). Promoting a no-op buys the convention delta and nothing else |

That leaves `Py_IncRef`, `Py_NewRef`, `Py_XNewRef`, `PyGILState_GetThisThreadState` and
`PyEval_SaveThread` as genuine leaves, and of those, only the three refcount operations are on a
path called often enough for tens of nanoseconds to accumulate.

**The audit's own confidence claim does not hold.** "Unresolved entries: none" is not available
from reading names and documentation, which is exactly how the three above were misread.

## What was changed instead: `PyList_GetItem`

The static sweep found one place where the current configuration contradicts the measurements, and
it is a demotion rather than a promotion.

`PyList_GetItemRaw` was registered `@CriticalNative` with **no `@FastNative` twin and no
`preferFastNative` branch**, the only declaration in `bindings.kt` that ignores the device axis.
It is also the per-element call of bulk list iteration, so the penalty multiplies by N. ROADMAP §5
measured `list → LongArray`, 1000 elements, at 50065.89 ns on API 36 hardware: 50 ns per element,
against a `@CriticalNative` net cost of 44.05 ns on that device. That 50 ns also covers the loop and
the `toNativePointer` conversion, so 44.05 is an upper bound on the convention's share rather than
an attribution, but it is an upper bound of 88%, which is enough to act on.

So a `@FastNative` twin was added (`f_PyList_GetItemRaw` in `jni_onload.def`,
`PyList_GetItemRawF` in `bindings.kt`) and the call site now branches on `preferFastNative`, the
same shape as `PyList_Size` directly above it. Predicted effect on API 36: 44.05 → 3.55 ns per
element. Nothing else was promoted or demoted.

This is compile-verified only. `JniWiringTest.bothListGetItemConventionsAgree` asserts the two
registrations return identical pointers at every index of `sys.path`, which catches both a missing
table entry and a wrapper written without the leading `JNIEnv*, jclass`; it has not been run,
because the device was in use.

It also narrows §5. If a large share of the 11x bulk-iteration win came from the per-element call
running under the slowest available convention on that device, then part of the remaining case for
composition was paying for a configuration mistake rather than for crossings.

## The registrations added since the audit: 116, all ordinary, no defects

The audit read 71 entries. The table has 187, 74 added at `9abc8edd`, 42 at `aea09585`, none
removed.

**Every one of the 116 is registered `N`-suffixed and declared plain `@JvmStatic external fun`.**
Not one has a `@CriticalNative` or `@FastNative` twin registered, so no call site can be reaching a
GC-blocking variant of them: there is none to reach. The failure mode ROADMAP §3 warns about cannot
be present in the new surface. Verified by extracting the table names from all three revisions and
parsing the annotations in `bindings.kt`.

Classifying the 42 anyway, since the point of the exercise is the rule for the *next* one. All 42
are string-carrying; 31 are re-entrant and correctly ordinary:

| mechanism | functions |
|---|---|
| executes module or arbitrary code | `PyImport_ExecCodeModuleN`, `PyImport_ExecCodeModuleExN`, `PyImport_ExecCodeModuleWithPathnamesN`, `PyImport_ImportModuleLevelN`, `PyImport_ImportModuleNoBlockN` †, `PyImport_ImportFrozenModuleN`, `Py_CompileStringN` |
| runs a Python-level hook | `PyObject_HasAttrStringN`, `PyObject_HasAttrStringWithErrorN` (`__getattr__`), `PyMapping_GetItemStringN`, `PyMapping_SetItemStringN`, `PyMapping_HasKeyStringN`, `PyMapping_HasKeyStringWithErrorN` (`__getitem__`/`__contains__`), `PySequence_FastN` (`__iter__`), `PyUnicode_TranslateN` (`__getitem__` on the table), `PySys_AuditTupleN` (audit hooks), `PyErr_WarnExplicitN` (the `warnings` machinery) |
| Python codec lookup / error handler | `PyUnicode_AsEncodedStringN`, `PyUnicode_FromEncodedObjectN`, `PyUnicode_DecodeLocaleN`, `PyUnicode_EncodeLocaleN`, `PyUnicode_DecodeFSDefaultN` |
| drops a reference, or allocates GC-tracked | `PyDict_DelItemStringN`, `PySys_SetObjectN`, `PyErr_NewExceptionN`, `PyErr_NewExceptionWithDocN`, `PyErr_SetFromErrnoWithFilenameN`, `PyErr_SyntaxLocationN`, `PyErr_SyntaxLocationExN`, `PyUnicodeTranslateError_SetReasonN` |
| never returns | `Py_FatalErrorN` |

† `PyImport_ImportModuleNoBlockN` has since been unregistered: CPython 3.15 removed
`PyImport_ImportModuleNoBlock`, which had been an exact alias of `PyImport_ImportModule` since 3.3
(ROADMAP §9). The counts above are left as they were read, since this section records what the
table held at `9abc8edd`/`aea09585` rather than what it holds now. The same migration also
unregistered `PySys_ResetWarnOptionsN` and renamed `PyWeakref_GetObjectN` to `PyWeakref_GetRefN`;
none of the three changed anyone's convention, and the replacement is registered ordinary like the
function it replaces.

The remaining 11 are leaves on the success path: `PyBytes_FromStringN`, `PyBytes_AsStringN`,
`PyByteArray_AsStringN`, `PyUnicode_EqualToUTF8N`, `PyUnicode_CompareWithASCIIStringN`,
`PyUnicode_InternFromStringN`, `PySys_GetObjectN`, `PyImport_GetMagicTagN`, `PyEval_GetFuncNameN`,
`PyEval_GetFuncDescN`, `Py_EnterRecursiveCallN`. None of them is promoted, and none should be:
every one is cold error-reporting or introspection surface, where the whole available win is under
40 ns on a call that happens once. Step 4 of the decision procedure in `androidMain/README.md`
covers exactly this case.

## The eight live GC-blocking call sites

Outside the `/** */` block of dead pre-migration code, `EmbedAPI.android.kt` reaches a
`@CriticalNative` or `@FastNative` binding in eight places. All eight are leaves on the success
path and all eight can allocate a GC-tracked exception on the failure path:

| call site | success path | failure path |
|---|---|---|
| `Py_IsInitialized` | reads a global | - |
| `Py_GetVersion` | returns a static `const char*` | - |
| `PyErr_Occurred` | reads the thread state, borrowed | - |
| `PyLong_FromLongLong` | non-GC allocation | preallocated `MemoryError` |
| `PyUnicode_FromString` | non-GC allocation | `UnicodeDecodeError` on malformed UTF-8 |
| `PyUnicode_AsUTF8` | caches the UTF-8 form | `UnicodeEncodeError` on lone surrogates |
| `PyList_Size` | reads `ob_size` | `PyErr_BadInternalCall` on a non-list |
| `PyList_GetItem` | indexes the item array | `IndexError` out of range |

That second column is second-order and conditional, the exception instance is GC-tracked, so it
can cross the collection threshold, so the cyclic collector can run a `__del__`, which with upcalls
live can be Kotlin. It is not a live defect and it is not a reason to demote these eight, which are
the measured hot path. It is the reason step 4 of the decision procedure does not pretend "provably
a leaf" is reachable: the honest promotion test is *leaf on the success path, and measured worth
it*, not *cannot run Python*.
