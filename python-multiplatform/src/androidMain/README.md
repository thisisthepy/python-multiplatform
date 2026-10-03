# androidMain — rules

The JVM half of Android. Reaches CPython through JNI into the Kotlin/Native library built from
`artMain` + `nativeMain`.

## The host app's bootstrap belongs here, not in the README

`PythonBootstrap` unpacks the stdlib out of the AAR's assets, sets `PYTHONHOME` and calls
`Python3.initialize`. It exists because the same ~30–55 lines had been hand-written three times —
`sample`'s `MainActivity`, the external consumer app of ROADMAP §15d, and this repo's own
`PythonOnDevice` fixture — from one README paragraph, and **all three got the same thing wrong**:
each decided "already unpacked" by probing one entry of the result, which a copy interrupted
partway through satisfies. See ROADMAP §15f.

Two rules follow for anything added here:

- **Do not re-validate `PYTHONHOME`.** `Python3.initialize` runs `PythonHomeCheck` on every call.
  This source set *produces* the layout; that check verifies it. A second copy of the rule is a
  second thing that can drift from what `Py_Initialize()` actually wants, and
  `PythonBootstrapTest.stagedPrefixIsOneThatPythonHomeCheckAccepts` is what keeps the two honest.
- **Completion markers, not result probes.** The stamp goes in after the last byte and comes out
  before a rewrite starts, so a partial tree cannot look complete. `encodings/ exists` is a
  property of a half-finished copy too.

`AssetManager.open()` throwing `FileNotFoundException` is how a directory is detected — it folds
the classification into the `open()` the file needed anyway and is worth ~2x over a `list()` per
entry (192→124 ms on API 26, 324→150 ms on API 36, for 804 files / 20.1 MB). It is **not** in
`AssetManager`'s documented contract, and AssetManager was reimplemented wholesale in API 28, so
`openBasedDirectoryDetectionAgreesWithListBased` re-derives both classifications on device on
whatever API level is running rather than trusting it.

## The consumer's payload is a second tree, with a second stamp

`assets/python/` is the consumer's own Python — `toolchain`'s `stagePythonBundleAndroid` registers
its staging root as an AGP asset source. `PythonBootstrap.stagePayload` unpacks it to
`<filesDir>/python` and registers it; `Python3.initialize` puts it on `sys.path[0]`.

Kept separate from `stageStdlib` rather than merged into it, for two reasons:

- **The stdlib is ABI-scoped and the payload is not.** `assets/<abi>/lib/python<X.Y>` exists per ABI
  because of `lib-dynload`'s native extension modules. A resource payload is source and data,
  identical on every ABI; giving it an ABI directory ships it once per ABI in the APK.
- **They change on different schedules.** One stamp covering both re-unpacks 800-odd stdlib files
  because one application module was edited.

Same discipline otherwise: the stamp comes out before the rewrite and goes in after the last byte,
and it carries `versionCode` + `lastUpdateTime` so an upgrade restages over a surviving `filesDir`.

**An app that brings CPython up without `PythonBootstrap.initialize` gets no payload.** Reading an
APK's assets needs a `Context` and `Python3.initialize` has none, so staging and installing are two
steps: `PythonBootstrap` does the half that needs a `Context`, exactly as it already does for
`PYTHONHOME`.

## Bind through `RegisterNatives`, not symbol names

`JNI_OnLoad` (in `artMain`) registers CPython's own C functions directly against the
declarations here. Two reasons, both measured:

- Name-based linking of a `@CriticalNative` method **aborts the ART runtime** below Android 12
  (`zygote64: runtime.cc:492] Runtime aborting...`), and is merely slow above it — 76.42 ns
  against 46.55 ns for the registered form on API 36 hardware.
- Registration removes the dependence on JNI name mangling, which is what produced the original
  argument-shift bug in the first place.

### Every `external fun` here needs a table entry — no exceptions by reachability

An unregistered declaration does not fail cleanly when it is finally reached. It falls back to a
`@CName` export in `nativeMain` that has no `JNIEnv*`/`jclass` prologue, so the arguments arrive
shifted by two registers, and the process dies on a truncated pointer (ROADMAP §2 records two:
`PyImport_AddModule` at `0xc24110e8`, `PyErr_GetRaisedException` at `0xc2411160`). "Nothing calls
it today" is not a defence — it is a description of when the crash happens, not whether.

There are exactly four deliberate exceptions and they are named in ROADMAP §2. Adding a fifth
means writing down why.

Two mechanical rules fall out of this:

- **Pointers cross as `Long`, never as `JNIPointer?`.** A nullable `Long` is a boxed
  `java.lang.Long` on the JVM, and no C function taking `jlong` can be registered against that
  descriptor. Return `Long` and convert with `.toNativePointer()` on the Kotlin side.
- **Do not define a wrapper twice.** cinterop rejects the redefinition, and the error points at
  the `.def` rather than at the merge that caused it. Before adding one:

  ```bash
  grep -oE "^static [a-z]+ (f_[A-Za-z0-9_]+)" jni_onload.def | awk '{print $3}' | sort | uniq -d
  ```

To audit the whole surface, join the compiled class against the table — the class, not the Kotlin
source, because the descriptor the JVM will match on is the one in the bytecode:

```bash
javap -p -s build/tmp/kotlin-classes/debug/python/native/ffi/bindings.class
```

## Pick the calling convention per API level *and* per function

Two independent axes. Correctness first.

**Axis 1 — what the function may do.** `@FastNative` and `@CriticalNative` both stop the
collector for the duration of the call, and `@CriticalNative` has no `JNIEnv`, so nothing under
it can re-enter the runtime. Anything that can execute arbitrary Python — a module's top-level
code during import, a `__getattr__`, a `__del__` reached by dropping the last reference —
stays on ordinary JNI at every API level. `PyErr_Clear` and `PyObject_GetAttrString` both look
like leaves and are not.

When in doubt, ordinary JNI. A wrong guess that way costs nanoseconds; the other way is a crash
once upcalls exist.

**Axis 2 — what the device fast-paths.** Measured across five API levels, net of the Kotlin
floor, ns/call:

| API | `@CriticalNative` | `@FastNative` | ordinary JNI |
|---|---|---|---|
| 26 | **1.86** | 38.69 | 45.33 |
| 30 | **-0.24** | 31.76 | 73.09 |
| 31 | **-0.02** | 24.29 | 45.97 |
| 33 | **-0.04** | 2.03 | 7.82 |
| 34 | 24.35 | **1.97** | 7.81 |
| 36 (hardware) | 44.05 | **3.55** | 18.32 |

Hence `bindings.preferFastNative = SDK_INT >= 34`. `dispatchPicksTheFasterConventionOnThisDevice`
re-measures both on whatever device runs it and fails if the flag disagrees, so a future release
moving the boundary shows up as a test failure rather than silent slowness.

Read the table in the direction that matters when adding a call site — how much a promotion is
worth, relative to leaving it ordinary:

| API | ordinary → `@FastNative` | ordinary → `@CriticalNative` |
|---|---|---|
| 26 | −6.64 | −43.47 |
| 30 | −41.33 | −73.33 |
| 31 | −21.68 | −45.99 |
| 33 | −5.79 | −7.86 |
| 34 | −5.84 | **+16.54** |
| 36 | −14.77 | **+25.73** |

`@FastNative` is a win on every level measured. `@CriticalNative` is the larger win below 34 and a
**loss** above it, so it is only ever correct behind the `preferFastNative` branch, never pinned.
That is the opposite of what a promotion audit written from ART's documentation will tell you.

## Choosing a convention for a new call site

Ordinary JNI is the default. A promotion has to survive all five steps.

**1. Can it drop a reference?** → ordinary. Stop.

Any refcount reaching zero deallocates and runs `__del__`, which is arbitrary Python. This catches
far more than the names suggest: every setter that overwrites (`PyTuple_SetItem`, `PyList_SetItem`,
`PyDict_SetItem*`, `PySys_SetObject`), every delete, `PyErr_Clear`, `Py_Finalize` — and
`PyGILState_Release`, because the release that drops the counter to zero deletes the thread state,
and clearing a thread state decrefs its dict and exception state. A function that looks pure and
touches a refcount is re-entrant.

**2. Can it allocate a GC-tracked container, or reach a Python-level hook?** → ordinary.

Allocating a tuple, list, dict, set or instance can cross the collection threshold, and the cyclic
collector runs `__del__` on what it reclaims. Hooks reached by ordinary-looking calls: `__getattr__`
and descriptor `__get__` on any attribute access, `__hash__`/`__eq__` on any dict or set operation
*including lookups*, `__iter__`/`__next__` inside `PySequence_Fast`, `__getitem__` inside
`PyUnicode_Translate`, module top-level code on any import, a Python codec on any encode/decode
that takes an encoding name, and `warnings.showwarning` inside `PyErr_WarnExplicit`.
`PyThreadState_GetDict` belongs here too — it allocates the thread dict lazily on first call per
thread, and is not the struct read its name implies.

**3. Can it block?** → ordinary. `PyGILState_Ensure`, `PyEval_RestoreThread`. A thread waiting for
a lock under a GC-blocking convention never reaches a safepoint, so the JVM collector stalls behind
whatever holds the GIL.

**4. Everything that is left can still raise.** → ordinary unless the win has been *measured* on
this call.

There is no C API function that cannot fail, and CPython reports failure by constructing an
exception instance, which is GC-tracked, which lands back in step 2. So step 4 never returns
"provably a leaf" — it returns "leaf on the success path, second-order re-entrant on the error
path". That is why the promoted set is short and enumerated rather than derived from a rule: it is
the set where a benchmark showed the convention *is* the call cost, not the set that passed an
argument.

**5. If promoted, register both twins and branch on `preferFastNative`.** Never pin one convention.
The C wrapper for the `@FastNative` twin is the same body with a leading `JNIEnv*, jclass`; the
`@CriticalNative` one must have neither. See `artMain/cinterop/jni_onload.def`.

### The five steps are enforced, not just written down

`JniCallConventionClassificationTest` (desktopTest — it reads sources, so it needs no device)
re-derives the classification on every run from `bindings.kt`, `artMain/cinterop/jni_onload.def` and
the CPython headers bundled in `src/nativeInterop/cinterop/include`, and fails if a call site reaches
a GC-blocking binding that is not in its enumerated promoted set.

Two things it is deliberately not:

- **It does not decide promotions.** The promoted set is a `Map` in the test with a reason per entry,
  because step 4 has no derivable answer — every C API function can fail, and failure allocates a
  GC-tracked exception. What the test enforces is that the set stays *enumerated*: a new
  `@FastNative`/`@CriticalNative` declaration fails the build until someone writes down why.
- **It does not claim to prove leafness.** A header prototype can condemn a function — a `PyObject *`
  crossing it means a decref, a slot, or a GC-tracked allocation is reachable — but nothing in the
  headers clears one. `Py_Initialize` and `PyRun_SimpleString` mention no `PyObject *` at all and both
  run arbitrary Python, so everything the rule cannot condemn lands in an **UNDECIDED** bucket that
  is barred from promotion. The bucket is currently 65 of 366 registrations. The audit that reported
  "unresolved entries: none" put three re-entrant functions on its safe-to-promote list.

The counts are printed by the run rather than written here, for the reason this test exists: the
audit document classified 71 registrations, its own follow-up re-read the table at 187, and the table
holds 366 today.

### Why the default is ordinary

| guessed wrong toward | costs |
|---|---|
| ordinary | 5.8 ns (API 33/34) to 41 ns (API 30) per call — measurable, and bounded |
| a promotion | the collector stalls for as long as the Python runs; under `@CriticalNative` there is no `JNIEnv`, so any upcall that Python reaches has none either |

Only the first of those shows up in a test. The second needs a device, live upcalls, *and* the
cyclic collector to fire inside that particular call — so it is found in production, not in CI.

## The boundary carries primitives only

`Long`, `Int`, `Double`, `Unit`. No `String`, no nullable `JNIPointer?` — a nullable `Long` is a
boxed `java.lang.Long` on the JVM against a raw `jlong` on the other side. Strings cross as
addresses; null pointers cross as `0L`. Conversion happens here, in `EmbedAPI.android.kt`.

## String marshalling is the expensive part, not crossings

Composing `Python3.exec` natively nearly halves it — 17.8 µs to 10.1 µs. That is **not** the
crossing count: 7.7 µs over ~11 crossings would be 700 ns each, against the ~7 ns a crossing
actually costs, and the per-call figure is identical on API 26 and API 36 despite a 20x
difference in per-crossing cost. It is the three `ffiAllocUtf8` round trips, each a JNI call
plus `GetStringUTFChars` plus malloc plus copy plus free.

So string-carrying operations are the ones worth composing, regardless of how much real work
they do.

## `System.gc()` does not collect on Android

Measured on both emulators, reproduced on three consecutive runs (`GCProbe` in
`androidInstrumentedTest/.../GCLeakTest.androidInstrumented.kt` logs it every run):

```
bare System.gc():                     canary cleared = false after 20 attempts
Runtime.gc() + System.runFinalization(): canary cleared = true  after 1 attempt
```

libcore's `System.gc()` records a request and defers the collection until the next
`System.runFinalization()`, so a loop of bare `System.gc()` calls runs **no collection at all**.
On HotSpot the same loop collects, which is why the desktop suite never saw this.

Anything on Android that waits for the collector — every GC-driven release test — must use
`Runtime.getRuntime().gc()` followed by `System.runFinalization()`. The whole of `GCLeakTest`
failed on both API levels for this reason, and the failure reads as "no cleaner ran", which
points at the cleaner threads rather than at the collector that never started.

## The instrumentation runner must park the GIL, not hold it

`Py_Initialize()` returns with the GIL held by its caller. `Python3.initialize()` parks that
thread state with `PyEval_SaveThread()` immediately afterwards, and that parking is what lets a
cleaner thread attach through `PyGILState_Ensure` and call `Py_DecRef` (ROADMAP §1).

`PythonInstrumentationRunner` used to bring the interpreter up with a bare `Py_Initialize()`,
which skipped the parking — and then made the omission permanent, because `Python3.isInitialized`
is seeded from `Py_IsInitialized()` when the object is first touched, so it latched to `true` and
`Python3.initialize()` returned early for the rest of the process. The instrumentation thread,
which is also the thread every test body runs on, held the GIL for the whole run.

Two consequences worth knowing, because neither looks like a GIL problem:

- Cleaners block on their first `PyGILState_Ensure` and stay blocked, so the *delta* counters
  in `GCLeakTest` read zero. `ranDelta > 0 && releasedDelta == 0` only catches a cleaner that
  gets stuck **during** the test; one stuck in an earlier test is indistinguishable from
  "nothing was collected".
- Instrumented tests that reach `bindings` directly ran fine only because that thread happened
  to hold the GIL. Once it is parked they must attach for themselves —
  `PythonOnDevice.attach()`/`detach()` in a `@Before`/`@After` pair, which is what
  `CompositionBenchmark`, `JniOverheadBenchmark` and `JniWiringTest` now do.
