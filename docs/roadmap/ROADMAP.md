# ROADMAP

What is not done yet, why it matters, and what is known about it. Ordered by what blocks what,
not by size.

Items are written so the reason survives without the conversation that produced it. Where a
claim came from a measurement, the number is here; where it is a judgement, it says so.

**Current test state — see §14a for the six run paths, how each is counted, and the numbers as
last verified (2026-08-13).** The table that used to be here (`desktopTest` 107/106,
`iosSimulatorArm64Test` 104/103, `GCLeakTest` "red on purpose") was stale on every figure: §1 is
closed and `GCLeakTest` passes now, and re-running the two JVM/native suites today gives
`desktopTest` 327/0 (1 skipped) and `iosSimulatorArm64Test` 289/0. A frozen number rots the moment
someone adds a test, which is exactly what happened here — §14a gives the counting recipe instead
of a number to carry forward uncorrected.

---

## 1. Release the GIL after initialisation

**Closed.** `Python3.initialize()` parks its thread state with `PyEval_SaveThread()`, and both
suites are green with it enabled — desktop 164, iOS 157, zero failures. `GCLeakTest` passes for the
first time.

The cause was never the runtime. Several tests wrapped a pointer they had only been lent with
`borrowed = false`, so two wrappers owned one pointer and both decremented it. That double-free
corrupted CPython's free lists and surfaced later in an unrelated test. With parking off the
cleaner thread blocked forever, so the extra decrements queued and never ran — which is exactly
why turning parking on appeared to *cause* the crash. Two earlier attempts read it that way and
reverted.

**It unblocked §4, multithreaded use, and made three latent bugs visible** (a GIL-less
`PyType_FromSpec` call among them). Expect more of that shape: anything that only worked because
the main thread never let go.

**State, checked against the code rather than carried forward: enabled, unconditionally.**
`Python3.kt`'s `initialize()` calls `PyEval_SaveThread()` on every call with no flag guarding it,
and the comment at that call site points here for the history. An earlier draft of this section
said "disabled ... with the reason in a comment there" directly under the "Closed" declaration
above — that line described an intermediate state from partway through the debugging history
below and was never updated when the section was actually closed. Anyone reading only the opening
paragraph and that one line together would have concluded the opposite of what the code does.

`Py_Initialize()` leaves its calling thread holding the GIL. Until that thread parks its state
with `PyEval_SaveThread()`, no other thread can attach, and any thread that tries blocks
forever in `PyGILState_Ensure`.

That is not theoretical. On desktop `GCLeakTest` reports `cleanup actions started: 1, reached
Py_DecRef: 0` — the cleaner thread starts one release, blocks, and never processes another.

**Correction: "on any platform" was wrong.** Running the same test on the iOS simulator gives
`started: 101002, reached Py_DecRef: 101001`. The cleaners run there and they do reach the
decref; the target's count still does not move (202 before, 202 after). So iOS is not blocked on
the GIL, and fixing §1 will not turn it green. One test name is covering two unrelated defects,
and either the iOS decrefs land on objects other than the one being measured or the measurement
itself is wrong. Nobody has looked yet.

**The recorded diagnosis is wrong, or at least incomplete.** Enabling the parking call was tried
again today and the result rules out the explanation this section gives.

```
desktopTest with PyEval_SaveThread() enabled
  0 tests run, JVM aborts (exit 134)
  SIGSEGV in libpython3.14.dylib  _TAIL_CALL_DICT_MERGE
                                  <- _PyFunction_Vectorcall
                                  <- PyObject_CallMethodObjArgs
  Java frame: EmbedAPI_desktopKt.PyImport_ImportModule
              <- EmbedApiLowLevelTest.importReturnsANewReferenceAndMissingModulesReportFailure
```

That call site is this:

```kotlin
val sys = Python3.withPython { PyImport_ImportModule("sys") }
```

**It is already inside the guard.** So "find the C API call that is outside `withGIL`" is not a
sufficient theory, and an audit of call sites will not find the bug. `withGIL` itself reads
correctly — depth-counted `PyGILState_Ensure`/`PyGILState_Release`, outermost acquires — so the
next attempt has to look at the *interaction*: what `PyGILState_Ensure` does on a thread whose
state was parked with `PyEval_SaveThread`, and whether the initialising thread and the calling
thread are the same one. Note this repo has already been bitten once by mixing the raw
thread-state scheme with the refcounted one.

The crash frame is inside CPython's import machinery, so the GIL is acquired far enough to start
executing Python before something goes wrong. That is a different shape from "no GIL held".

The older explanation, kept because it is still part of the picture: every C API call outside
`withGIL` becomes a
segfault far from its cause: closing the gap in `BenchmarkTest.testAttributeAccess`
(`PyImport_Import`) moved the crash from `PyImport_Import` to `_PyObject_Malloc`, with no Java
frame naming the new site.

Doing this properly needs a pass over every call site rather than a one-line change. Two things
make that cheaper than the last attempt: `ReleaseCounter` distinguishes "cleaner never ran" from
"cleaner ran and could not release", and the suites are large enough now to localise a
regression.

**Second attempt, and what it bought.** An audit of `commonMain` concluded the sweep was
complete and enabled the line. It was not: `DesktopOverheadBenchmark` in `desktopTest` called
`PyImport_ImportModule`, `PyObject_GetAttrString` and `PyList_Size` bare, which was harmless
only while `initialize()` kept the GIL to itself. Those are now guarded — a real gap closed and
kept.

With them fixed the crash moved to `_TAIL_CALL_DICT_MERGE`, which is CPython's `DICT_MERGE`
opcode: **Python bytecode executing without the GIL**, i.e. something that runs Python code
rather than merely touching an object. Parked again at that point, with the suite back to
118/117.

One caveat on that last frame: two other agents were editing the tree concurrently, one of them
in `ffi/conversion/`. An unguarded call only crashes once the GIL is actually released, so a
gap introduced by in-flight work would look exactly like a gap that was always there. The next
attempt should start from a quiet tree.

Pattern worth stating plainly, because it has now repeated three times: each fix moves the
crash somewhere further from its cause. Grep for the EmbedAPI function names across every source
set — `desktopTest` and `commonTest` included, which is where both misses have been so far —
rather than auditing by reading.

Note that free-threaded builds do **not** remove this requirement. Dropping the global lock
removes contention, not the rule that a thread must be attached before touching any object,
`Py_IncRef` included.

## 2. Finish the Android JNI surface

**Closed, on the measure the section itself set.** Both emulators run 213 tests with zero
failures, and the registration surface is complete: 363 of 367 `external fun` are bound through
`RegisterNatives`, with the four exceptions deliberate — `ffiAllocUtf8`/`ffiFreeUtf8`/`ffiReadUtf8`
are hand-written with the correct `JNIEnv*`/`jclass` prologue and cannot move into C because the
buffers belong to Kotlin/Native, and `echoCriticalNamed` exists in order to be name-linked, so
registering it would delete the measurement.

Descriptor consistency is checked against the compiled bytecode rather than the Kotlin source —
`javap` on `bindings.class` joined to the table by name, zero mismatches — and there are no
duplicate wrappers or table names, which is a failure that broke cinterop once during this work.

**The history below is why this took three passes**, and it is worth keeping. The section was
closed once on `AssembledApiTest` passing, which measured its own scope: those 19 tests only
exercised the 71 functions that had been registered. Wiring `commonTest` into Android (§11b) took
discovery from 19 tests to 168 and the suite died on its 2nd, then its 12th. Two defects behind
that had been invisible because nothing on Android had ever run those tests — an unpackaged stdlib
and a `RegisterNatives` count hardcoded to 138 against a table of 145.

**Reopened once. Marking it closed the first time was wrong.**

It was closed on the strength of `AssembledApiTest` passing — 19 tests on `pmp_api26` and
`pmp_api36`, 0 failed. That was true and it did not mean what it was taken to mean. Those 19 tests
only ever exercised the 71 functions that had been registered. **The passing acceptance test
measured its own scope.**

Connecting `commonTest` to Android (§11b) raised discovery from 19 tests to 168 per device and the
suite died on the **2nd** one, with a hard SIGSEGV on both API levels. Then, after that fix, on the
12th:

```
PyImport_AddModule        fault addr 0xc24110e8   EmbedAPI_androidKt -> PythonTestFixture.mainGlobals
PyErr_GetRaisedException  fault addr 0xc2411160   PyException.fromCurrentError -> PyObject.getAttr
```

Both truncated pointers, which is the signature of the name-linked `@CName` fallback described
below. An unregistered call does not fail cleanly; it corrupts a pointer and kills the process.

**The real numbers**, recounted from the two files rather than carried forward (parse
`external fun` out of `androidMain/.../bindings.kt`, parse the `JNINativeMethod` table out of
`artMain/cinterop/jni_onload.def`, join on the method name and compare JNI signatures):

```
                          was      now
bindings.kt external fun  369      365
registered                145      187
unregistered              224      178
signature mismatches        0        0     (all 187 entries agree with their Kotlin declaration)
```

**The string half of it is finished.** Declarations that still carry a `jstring` across the
boundary went from **52 to 6**, and of those six none is a defect:

- `asmExec`, `asmGetAttr`, `testUpcallString` are registered *with* a jstring signature and their
  C wrappers use `GetStringUTFChars` — that is the composition-probe design, not the broken path
- `ffiAllocUtf8`, `ffiReadUtf8` are name-linked but hand-written in `artMain/JNIOnLoadExporter.kt`
  with the correct `JNIEnv*`/`jclass` prologue, so the convention matches
- `ffiSymbolRaw` is the one genuine leftover on the name-linked `@CName` path. It has no caller
  anywhere in `src/` — `jvmMain`'s `ffiSymbol` that wraps it is itself unreferenced — so it is a
  landmine rather than a live crash. Fixing it means a `dlsym` wrapper taking a jlong, and it
  belongs with whatever revives the shape vocabulary on Android.
  *(Done — see below. It is `ffiSymbolRawN(name: Long)` now, and the shape vocabulary went with it.)*

The 42 functions migrated in this pass are listed with their per-argument intern/scratch
judgement in `docs/design/marshalling-design.md`. Every symbol they bind was checked to exist in the
shipped `libpython3.14.so` before registering (`llvm-nm -D --defined-only`), and the linked
`libmultiplatform_python3.14.so` has no unresolved `Py*` symbol.

### The unregistered surface is now empty

The 178 that were left are registered. Recounted the same way, against the compiled class this
time rather than against the Kotlin source — `javap -p -s` on `build/tmp/kotlin-classes/debug/
python/native/ffi/bindings.class`, joined to the `JNINativeMethod` table on the method name:

```
                             before    after
bindings.kt external fun       369      367     (two dead declarations removed)
registered                     191      363
unregistered                   178        4
descriptor mismatches            0        0     (all 363 agree with the compiled bytecode)
non-static registrations         0        0
```

The four that remain are deliberate, and none of them is on the broken path:

- `ffiAllocUtf8`, `ffiFreeUtf8`, `ffiReadUtf8` — name-linked, but hand-written in
  `artMain/.../JNIOnLoadExporter.kt` with the correct `JNIEnv*`/`jclass` prologue and a real
  `jstring`. They cannot move into `jni_onload.def`: the buffers they allocate and read belong to
  Kotlin/Native.
- `echoCriticalNamed` — the probe that exists *to* be name-linked, so that a benchmark can compare
  name-based linking against `RegisterNatives` at the same calling convention. Registering it would
  delete the measurement.

Two declarations were removed rather than registered, both provably uncalled and both already
having a registered equivalent: `PyObject_Call` (superseded by `PyObject_CallN`) and
`PyList_GetItem` (superseded by the `PyList_GetItemRaw` / `PyList_GetItemRawF` pair).

**All 157 CPython functions in this pass were unreachable from `commonMain`/`commonTest`.** That
is not a reason it was safe to leave them — it is the finding that the earlier passes had already
covered the whole reachable surface, so what remained was landmines only. The check: take the 312
`expect fun` in `EmbedAPI.kt`, keep the 109 named anywhere in `commonMain`, `commonTest`,
`androidInstrumentedTest` or `jvmMain`, map each through its `EmbedAPI.android.kt` actual to the
`bindings.*` it calls, and intersect. The intersection with this pass is empty; the same procedure
returns `true` for `PyDict_Clear`, `PyObject_Type` and `PyList_Append`, which were registered in
the previous pass, so the procedure does find reachable functions when they exist.

`docs/investigations/android-unregistered-surface.md` is kept for the call-graph traces, but its counts and its
"71 reachable" framing are historical.

The shape vocabulary went with it. The 14 `downcall_*` trampolines and `ffiSymbolRaw` used to reach
`nativeMain`'s `@CName` exports by name, which is the same broken path and worse — a shape
function's first argument is the *target function pointer*, so a shifted argument register is a
wild call rather than a bad `PyObject*`. The indirect call is done in `jni_onload.def` now, which
removes the fallback and the hop through Kotlin/Native at once. `ffiSymbolRaw` became
`ffiSymbolRawN(name: Long)` over a `dlopen(NULL, RTLD_NOW)` handle, which is the jlong-taking
`dlsym` wrapper this section asked for above.

Verification actually run: `compileKotlinAndroidNativeArm64`, `compileDebugKotlinAndroid` and
`linkMultiplatform_python3.14DebugSharedAndroidNativeArm64` all clean; the linked
`libmultiplatform_python3.14.so` has 317 undefined `Py*` symbols and every one of them is defined
in the shipped `libpython3.13.so` (`objdump -T`); desktop 171 tests, 0 failed.

~~**Not yet run: the device suite.** §2's own measure is how far the 168 `commonTest` cases get on
`pmp_api26`/`pmp_api36`, and that has not been re-run since this change — another agent held the
emulators. The registration surface is complete and internally consistent; whether the suite
advances past the 12th case is unmeasured. **Do not close this item on the strength of the
consistency check alone** — that is the same mistake as closing it on `AssembledApiTest`.~~
**Since run, repeatedly, and past far more than the 12th case.** The "Android had no usable
temporary directory" entry below records `connectedDebugAndroidTest` at 492/3 then 493/0 on
`pmp_api26`; §14b item 7 records `AndroidCleanerPathTest` on both `pmp_api26` and `pmp_api36`; §7's
"Android landed next" records `UpcallEntryTest` at 251/0 on both API levels. The device suite is
not merely unblocked — it has run clean, several times, on both emulators.

Earlier in this section: four were migrated first (`PyImport_AddModule`, `PyErr_SetString`,
`PyObject_SetAttrString`, `PyObject_DelAttrString`), which moved the suite from 10 tests to 12.
**Progress on this item is measured by how far the 168 get, not by whether a chosen test passes** —
that is the mistake that closed it prematurely.

The history below is kept because the three failure modes it records are exactly what the two
crashes above are, and because §3 still has to classify whatever §2 binds.

**Was:** 39 of 380 `external fun` declarations bound through `RegisterNatives`. The rest kept the
original wiring, which does not work.

The original wiring is broken three ways, all measured or read off the code:

- arguments arrive shifted, because the `@CName` exports declare no `JNIEnv*`/`jobject` while
  ART's ordinary convention passes them (`JniWiringTest` proved this on device: sent
  `0x5A5A12345678`, received a pointer)
- 78 exports take or return a Kotlin/Native `String`, which is not a JVM `jstring`
- 182 declarations use `JNIPointer?`, a boxed `java.lang.Long` on the JVM, against a raw `jlong`

The 39 that work do so through `JNI_OnLoad` → `RegisterNatives`, binding CPython's own C
functions directly with no trampoline. Migrating the rest is mechanical **except** for one
judgement per function — see §3.

**This is not a latent problem — the production API is unusable on Android today.** Calling
`Python3.exec` crashes the process on its very first call, on both API 26 and API 36, because
the functions it reaches (`PyImport_AddModuleRef`, `PyObject_GetAttrString`, `PyRun_String`)
still take a Kotlin `String` straight across JNI. Every Android test that passes does so by
going through `bindings` directly and reconstructing the path by hand; none of them had ever
called the real object model, so nothing caught it. A benchmark added against `Python3.exec`
found it immediately.

The string-marshalling work (interning) converted three functions. The rest of the several
hundred still carry the original wiring.

## 3. Classify the remaining functions leaf vs re-entrant

**Closed.** The classification is in `docs/investigations/jni-call-convention-audit.md`; the decision procedure a
new call site has to pass is in `androidMain/README.md`.

`@CriticalNative` and `@FastNative` both stop the collector for the call, and `@CriticalNative`
has no `JNIEnv`, so nothing under it can re-enter the runtime. A function that can execute
arbitrary Python — a module's top-level code, a `__getattr__`, a `__del__` reached by dropping
the last reference — must therefore stay on ordinary JNI.

**No function was promoted, and that is the finding.** The audit proposed eight promotions to
`@CriticalNative` on the argument that it is expensive below API 34 and cheap above. Measured here
it is the reverse: promoting costs **+16.54 ns on API 34 and +25.73 ns on 36**, against savings of
43-73 ns on 26-31. Three of the eight are not leaves either — `PyGILState_Release` deletes the
thread state on the last release and runs `__del__` through it, `PyThreadState_GetDict` allocates
the thread dict lazily, and `PyEval_InitThreads` is a no-op kept for the stable ABI.

The 116 registrations added since the audit (71 → 187) are **all ordinary, with no `@CriticalNative`
or `@FastNative` twin registered for any of them**, so the crash this section warns about cannot be
reached through the new surface.

**The table is at 366 now, and that claim was re-derived rather than carried forward** — it still
holds, and so does every other substantive claim in this section. What did not hold is the numbers:
the audit classified 71 by hand, this section quoted 187, and roughly 180 registrations had never
been classified by anything. `JniCallConventionClassificationTest` derives the classification on
every desktop build, so the count stops being something a document has to remember. It also records
what the audit could not: **65 registrations are undecided**, because a header prototype can condemn
a function and never clear one.

One change came out of it, in the opposite direction to the audit's: `PyList_GetItem` was pinned to
`@CriticalNative` with no `preferFastNative` branch — the only binding ignoring the device axis, and
the per-element call of bulk iteration, where §5 measured 50 ns per element on API 36 against a
`@CriticalNative` net cost of 44.05 ns there. It now has a `@FastNative` twin and branches like
every other pair. Compile-verified; the device assertion
(`JniWiringTest.bothListGetItemConventionsAgree`) is written but has not been run.

The default is ordinary JNI, and the promoted set stays enumerated rather than derived: every C API
function can fail, and CPython reports failure by allocating a GC-tracked exception, so "provably
cannot run Python" is not a property any of them has. Guessing wrong toward ordinary costs 6-41 ns;
guessing wrong the other way is a crash once upcalls exist.

**Was:** depends on §2. Of the 11 migrated at that point, 5 were leaves and 6 re-entrant;
`PyErr_Clear` and `PyObject_GetAttrString` both look like leaves and are not.

## 4. Automatic reference release

**Closed.** A thousand Python lists holding a target object are dropped with no explicit
`close()`, Kotlin's collector takes the wrappers, and the target's count falls as the lists are
destroyed. Verified on both collectors — desktop through `java.lang.ref.Cleaner`, iOS through
Kotlin/Native's `createCleaner`. Android below API 33 uses the `PhantomReference` path, which is
now covered on every desktop build — the loop moved to `jvmMain` because nothing in it is
Android-specific and `desktopTest` can reach it there. It could not be tested where it lived: both
emulators are API 36, so they take the `Cleaner` branch and no test in the repository reached the
other one. It held a defect the whole time, and the shape of that defect is the argument for the
move — the drain loop caught `InterruptedException` alone, so one throwing release killed the
thread and every later release leaked in silence.

**One thing still cannot be freed**: wrappers outliving `Py_Finalize()` are skipped
deliberately, leaving stale pointers if the interpreter is restarted.

~~And cross-boundary cycles through a *real* application proxy still leak — §7's `tp_traverse`
slot wiring exists and is tested on every target (`ProxyTypeFactory`), but nothing yet makes a
production-generated proxy class an instance of that type, so the mechanism and the object model
it needs to protect are not yet connected. See §7's "What is not done".~~ **Closed by
`work/handleslot` (`99acd830`, 2026-08-18, merged `a1fb825c`)** — `PythonProxySource.renderClass`
now bases a generated class on `_PmGcObject`, which subclasses `_pm_proxy_base`
(`ProxyTypeFactory.createProxyType()`'s published type), whenever `ReflectedClass.hasTraverse` is
set. Production proxies are instances of the traversable type; see §7's account (the paragraph
after `CycleCollectionTest.aPythonSubclassOfTheProxyTypeSurvivesBeingUntrackedTwice`) and §14b
item 5. Cycles that close entirely on the Kotlin side (no Python-side edge for `tp_traverse` to
report) are the part that remains open — `docs/design/object-lifetime.md` has it.

**The third — the exception window — was not structural, and is closed.** "A raw pointer leaks
if an exception lands between the C call returning it and the wrapper taking ownership"
described real bugs, not a property of the architecture. `OwnershipLeakTest` forces two of them
and measures the count; both were red before the fix by exactly the number of attempts — **+50
references over 50 attempts in each case**, one lost per attempt, and no collector can reach
them because no Kotlin object ever owned them.

- `PyDict.fromMap` allocated the dict and populated it afterwards. One unhashable key makes
  `PyDict_SetItem` fail partway, and the half-built dict — by then holding a reference to every
  key and value already stored — was still a bare pointer. Measured on both the stored value and
  the already-stored key: +50 each.
- `PyType.getInstance` is handed a new reference on every call and, on a **cache hit**, dropped
  it. The comment argued this was harmless because types are immortal — true of `int` and
  `list`, false of every user-defined class, which leaked one reference per `PyObject.Type`
  read. Measured against a class defined in Python: +50 over 50 reads.

The fix is `adoptingNewReference` in `PyObject.kt` — release the reference if, and only if, the
block that was going to adopt it throws — plus `try/finally` at the sites that hold a new
reference across a second C call: `repr`, `toString`, `richCompare`, `PyType.name`,
`snapshotElements`, `PyDict.snapshotEntries`, the four `fromList`/`fromSet` scratch tuples,
`deriveTypeAndRelease` (which abandoned its scratch instance outright when `PyObject_Type`
failed), `PyException.messageOf` and `isNoneObject`. `PyException.fromExceptionInstance` now
wraps the exception instance **first**, so a failure while building the message or walking
`__context__`/`__cause__` can no longer strand the exception itself — in the one path that
exists to report failures.

One rule came out of it, and it is written on the helper: **it must never wrap a constructor
that can throw.** `PyAutoCloseable` registers the cleaner in its own constructor, before any
subclass initialiser runs, so a wrapper that throws from `init` has *already* queued a release;
releasing again would be the double free of §1. `PyType.getInstance` therefore validates before
it constructs rather than from `init`.

Only the two above are proven by a red-then-green test; the rest are windows that no current
call site can force (they need `PyUnicode_AsUTF8` or `PyTuple_GetItem` to fail on a live
object). They are fixed as structure, not as measured leaks, and are marked as such here.

**Was:** depends on §1.

**Closed.** Both the explicit and automatic (GC-driven) release paths are fully functional on JVM and Kotlin/Native. The block recorded in §1 (GIL locking on parked thread) was resolved, unblocking the background cleaners.

Measurements prove that GC-driven release actually drops CPython reference counts:
- `GCLeakTest.testReferenceCountDecreasesOnGC` verifies a simple single-object wrapper lifecycle.
- `GCLeakTest.testCascadingReleaseOnGC` verifies complex nested structures: appending a target object to 1000 Python lists, then dropping the Kotlin wrappers for those outer lists. The Kotlin GC correctly collects the list wrappers, their cleaners call `Py_DecRef` on the lists, which cascades into CPython freeing the lists and decrementing the target object's reference count.

**Platform Differences:**
- **JVM (`desktopMain`)**: Uses `java.lang.ref.Cleaner`, driven by a dedicated background thread.
- **Kotlin/Native (`iosMain`, etc)**: Uses `kotlin.native.ref.createCleaner`, which runs on a dedicated worker thread, allowing asynchronous non-blocking cleanup.
- **Android (`androidMain`)**: Uses `Cleaner` where available (API 33+), with a fallback to `PhantomReference` requiring background polling on older devices. (Testing on device requires `androidInstrumentedTest` setup — see §11b).

**What fundamentally cannot be released in this architecture:**
1. ~~**Uncaught exceptions during FFI allocation**: If Kotlin code calls a C-API function that returns a new reference (e.g., `PyObject_GetAttrString`), and a Kotlin exception disrupts the control flow *before* that raw pointer is wrapped in `PyObject(..., borrowed = false)` or explicitly `Py_DecRef`'d via a `finally` block, the CPython reference leaks permanently.~~ **Closed — see above.** This was a list of missing `finally` blocks, not a limit of the architecture.
2. **Post-Finalize GC**: When `Py_Finalize()` executes, it frees CPython's heap. If Kotlin wrappers are GC'd *after* this, their cleaners see `!Python3.isInitialized` and exit early to avoid segfaults. While safe for shutdown, if the interpreter is later re-initialized via `Py_Initialize()`, those dangling wrappers will retain pointers that either point to unmapped memory or alias newly allocated CPython objects.
3. **Cross-boundary cycles**: A Python object holding an upcall proxy to a Kotlin object, which in turn holds a `PyObject` pointing back to the Python object. Neither language's GC can trace through the other, leading to a permanent leak unless broken manually or addressed via `tp_traverse` (see §7).

## 5. Composition (조립)

**Measured, on device:**

| | per-call | composed | |
|---|---|---|---|
| `getAttr`, API 36 hardware | 3929.84 ns | 713.34 ns | 5.5x |
| `list → LongArray` 1000 elems, API 36 hardware | 50065.89 ns | 4532.55 ns | 11.0x |
| `getAttr`, API 26 emulator | 2431.34 ns | 244.70 ns | 9.9x |
| `list → LongArray`, API 26 emulator | 4890.83 ns | 4113.13 ns | 1.2x |

Note what this does and does not say. Four crossings at ~7ns is 28ns, which does not explain
3929 against 713 — most of the single-call win is the `ffiAllocUtf8` malloc/copy/free round trip
disappearing, not the crossing count. The crossing count genuinely dominates only in the bulk
case, where it scales with N.

API 26 barely benefits in the bulk case because its per-element crossings run under
`@CriticalNative` and are already free there. Composition pays most where per-crossing cost is
highest, which is modern Android.

**State:** `artMain/JniExport.kt` still exports composed `Python3` functions
(`initialize`, `finalize`, `internalIsInitialized`, …) and **nothing on the JVM side calls
them** — the `external fun` declarations that used to live in `PyObject.android.kt` were
removed by `0fae961a`. Probes exist (`asmGetAttr`, `asmListToArray`, `asmExec`) and are
benchmarked, but the production path does not use composition anywhere.

Composed operations belong in the FFI layer as `expect`/`actual`, not in the object model —
`PyObject` must not reference `bindings` (see `docs/design/architecture.md`). Restoring composition
therefore does **not** require reopening `PyObject` as `expect`/`actual`.

`Python3.exec` was measured too, and it contradicts the reasoning above:

| | per-call | composed | |
|---|---|---|---|
| `exec("x = 1 + 1")`, API 36 emulator | 17799.46 ns | 10123.94 ns | **1.76x** |
| `exec("x = 1 + 1")`, API 26 emulator | 17798.43 ns | 9570.26 ns | **1.86x** |

Composing a realistic binder call — one that parses, compiles and executes Python — still
nearly halves it. The prediction beforehand was "a few percent", on the argument that Python's
own work would dominate. It does not: about 7.7 µs of the 17.8 µs is boundary cost.

The mechanism is visible in the numbers. 7.7 µs over ~11 crossings would be 700 ns each, two
orders of magnitude above the 7 ns a crossing actually costs. And the per-call figure is
identical on API 26 and API 36 (17798 vs 17799) despite those devices differing by 20x in
per-crossing cost. So this is not crossings — it is the three `ffiAllocUtf8` round trips, each
a JNI call plus `GetStringUTFChars` plus malloc plus copy plus free.

Which means composition is worth extending past the object model, and that **string-carrying
operations are the ones to compose first**, regardless of how much real work they do.

### Most of it can be had without composing at all

`getAttr`, measured three ways on the same device:

| | API 36 | API 26 |
|---|---|---|
| per-call (`ffiAllocUtf8`) | 2238.25 ns | 2488.62 ns |
| composed (`asmGetAttr`) | 463.60 ns — 4.83x | 243.08 ns — 10.24x |
| **direct buffer, no composition** | **425.90 ns — 5.26x** | 656.81 ns — 3.79x |

On modern Android the marshalling fix alone **beats** composition, and it needs none of the
machinery: no `artMain` export, no per-operation composed function, no JVM-side wiring. One
`DirectByteBuffer` per thread, its address taken once, and every call afterwards is encode-and-
copy inside the JVM followed by passing a `long`. It applies to every string-carrying operation
automatically rather than one at a time.

This is the same thing Panama does on desktop, which is why desktop's marshalling is ~200 ns
per string against Android's ~2500 ns. PanamaPort would have brought it too — its
`allocateFrom(String)` runs through `AndroidUnsafe.allocateMemory` → `sun.misc.Unsafe`, a JVM
intrinsic with no crossing. The earlier decision to skip PanamaPort was argued on downcall stubs
alone and missed that its memory model was the part that mattered here.

API 26 still favours composing, by roughly 2.7x. But `@CriticalNative` is already free at that
level, so old devices were never the ones paying for crossings, and they are a shrinking share.

**So §5 shrinks.** Composition stays justified only where crossing count genuinely dominates —
bulk iteration, which scales with N (11x at 1000 elements). For single string-carrying calls,
fix the marshalling instead.

### Interning settles it, on every API level

The direct-buffer path lost to composition on API 26, and the reason was not crossings — both
make exactly one ordinary-JNI call there. It was where the string gets encoded: composition
hands the jstring to `GetStringUTFChars` and encodes natively, while the direct-buffer path
encodes in Java, and API 26's ART is much worse at that.

Attacking the encode rather than the crossing:

| `getAttr`, ns | API 36 | API 26 |
|---|---|---|
| composed | 528.85 | 255.19 |
| direct buffer + `toByteArray` | 694.03 | 800.96 |
| direct buffer + ASCII fast path | 373.18 | 248.94 |
| **interned C string** | **160.25** | **167.33** |

**Interning wins on both, and wins uniformly** — 160 against 167 ns, where composition differs
by 2x between the same two devices. It is 3.3x better than composing on API 36 and 1.5x better
on API 26, so the API-26-versus-modern split disappears entirely.

That is not surprising in hindsight: attribute and module names are repeated literals, so the
encode is pure waste after the first time. CPython interns its own strings for the same reason.

The ASCII fast path alone already matches or beats composition on both levels (373 vs 528,
249 vs 255), which makes it the right fallback for strings that are not worth caching.

**Design consequence.** The `expect`/`actual` memory abstraction should be built around
`String → cached C string address`, not around a general `Arena`. A bound on the cache is
required — callers can pass arbitrary strings — with the ASCII path handling everything that
misses. `Arena`-style allocation is still needed for non-string buffers, but it is not the
centre of the API.

**This closes composition for string-carrying operations at every API level.** What remains of
§5 is bulk iteration only, where crossing count genuinely scales with N.

## 6. Composition on desktop — measured, and the answer is no

**Measured.** The same question Android answered at 1.8x, asked on desktop without building a
composed path: run `exec`'s three C calls with the C strings allocated per iteration, then with
them allocated once. The gap is what composing could remove.

| | ns |
|---|---|
| `exec("x = 1 + 1")`, strings allocated per call | 5768.04 |
| same, strings hoisted out of the loop | 5161.29 |
| **marshalling share** | **606.74 (10.5% of the call, 1.12x)** |

Against Android's 43% and 1.8x. The difference is what the two platforms do to produce a C
string: Android's `ffiAllocUtf8` is a JNI round trip — a crossing, then `GetStringUTFChars`,
then malloc, copy, free — at roughly 2.5 µs per string. Desktop's `withUtf8` is a Panama
`allocateFrom` with no crossing at all, about 200 ns per string.

So composing on desktop would buy ~10%, and cost new Kotlin/Native targets for macOS and Linux,
a packaging path for the resulting library, and a permanent asymmetry where Windows cannot
participate. **Not worth it.** This item is closed unless something changes the premise.

That cheaper win has since been taken: all 45 string-carrying wrappers moved to `invokeExact`,
so `bindings.kt` now has 310 exact calls and zero inexact ones. Marshalling went from
606.74 ns to **440.03 ns** (10.5% → 8.1% of the call). Kotlin passes the `Long` out of
`withUtf8`'s lambda as a primitive `long`, so no descriptor changes were needed.

The benchmark that produced those numbers is now `@Ignore`d. Running `exec` 200,000 times left
the JVM aborting inside `PyDict_New` and took the suite from 108 tests down to 2. Clearing the
error indicator on its failure paths recovered most of that — a pending exception was part of
it — but an abort still followed the run, and the cause is not pinned. Re-enable only after
that is understood.

Also worth recording for whoever revisits this: the Stable ABI closes the obvious bulk shortcut
regardless. `PySequence_Fast_ITEMS` is a macro reading `PyListObject->ob_item`, and `abi3t`
(3.15 free-threaded) makes `PyObject` an incomplete type, so there is no supported way to
extract list items in one call without shipping our own native code.

## 7. Python → Kotlin binder (upcalls)

**Both halves now exist, and the design premise is proven rather than argued.**

The runtime is in `reflection/` — `HandleTable` (slot plus generation, so a released handle
cannot alias onto whatever takes its slot), `UpcallTable`, `ExposedCallable`, `ObjectReference`.
The generator is `python-multiplatform-ksp/`, wired into a user module by
`python-multiplatform-gradle-plugin/` (one `id(...)`, no per-target `add("ksp<Target>", ...)`).
Fixture modules under `ksp-fixtures/` run 35 tests against a table KSP actually generated, not a
hand-written one — 29 on desktop, and 6 more from `ksp-fixtures/android`, the fixture that carries
an Android plugin (§13).

**It survives a GraalVM native image**, which is the condition the whole design was chosen for:

```
KOTLIN: table = 19 entries, 3 classes, from io_github_thisisthepy_sample
PYTHON: resolved handle = 4294967314
PYTHON: invoke result = 7
PYTHON: @PythonInternal entry resolves to -1
PYTHON: UPCALL_OK
```

**Re-verified after the upcall runtime, the KSP table and the artefact walker landed** — the claim
above was made before all three, so it was worth re-running rather than carrying forward:

```
KOTLIN: table = 41 entries, 4 classes, from io_github_thisisthepy_sample
PYTHON: invoke result = 7
PYTHON: invoke_args result = 'presses x3 = 21'
PYTHON: @PythonInternal entry resolves to -1
PYTHON: UPCALL_OK / PROXY_OK
```

19 entries became 41 because `ExposedToPython.kt` grew; the exposure policy still refuses
`@PythonInternal` with `-1` under the closed world, which is the part that had to hold. The
procedure, including the toolchain path and why `JAVA_HOME` must stay on JDK 21 while native-image
runs on 25, is in `docs/platforms/graal-native-image-verification.md`.

Python builds a function pointer with `ctypes`, resolves a Kotlin declaration by name through
`HandleTable`, and calls back into Kotlin — inside a closed world where runtime reflection is
forbidden. Every wall hit getting there was metadata or wiring; the lookup and invoke path itself
needed no reflection registration, because it uses none. `sample` carries the build path
(`nativeCompile`, `runNativeUpcallDemo`, Liberica NIK).

**The table it resolves against is generated now, not hand-written.** `NativeImageMain.kt` used to
carry its own `FunctionTableFragment` returning a constant `42`, with a comment saying the
generator did not exist yet — and a hand-written fragment passes whether or not KSP ever runs, so
the check measured less than it appeared to. It installs `python.multiplatform.generated.FunctionTable`
— generated from `:sample`'s own sources — instead, and the number Python reads back (`7`) is one the process
produced by calling the Kotlin object seven times, so a stub returning a constant cannot satisfy
it. The `-1` line is the same check applied to `@PythonInternal`: an opted-out declaration must
not resolve.

**The metadata is generated, not maintained.** `reachability-metadata.json` was a checked-in file,
and a checked-in file rots without saying so: a native image links a `FunctionDescriptor` the
metadata does not declare, builds clean, and dies on the first call with
`MissingForeignRegistrationError`. It had already rotted — it declared one upcall descriptor while
`Panama` builds three, so `ProxyTypeFactory` (the `tp_traverse` wiring below) would have failed in
any image that reached it. `generateDesktopReachabilityMetadata` now derives the `foreign` section
from `bindings.kt`, `ShapeDowncalls.desktop.kt` and `Panama.kt`, and fails the build on a
declaration form it cannot read rather than dropping the descriptor. `ReachabilityMetadataTest`
re-checks the result against the `MethodHandle.type()`s the loaded classes actually produce, and
`runNativeUpcallDemo` now asserts on the `UPCALL_OK` marker instead of on the exit status. The
failure mode was confirmed by deliberately mis-declaring one descriptor and watching the binary
die at runtime, not argued from the documentation.

**The binary is 16.7 MB, down from 35.4 MB.** The largest single item in the image was CPython
itself: a registered resource is baked into the image heap as a `byte[]`, so the binary carried
19.4 MB of `libpython` only so that `manager.kt` could write it back out to a temporary file at
startup. It has to be on disk anyway — `PYTHONHOME` must point at a prefix with a matching stdlib
for `Py_Initialize` to get past `encodings` — so `manager.kt` loads it from that prefix when the
classpath copy is absent (`PYTHON_MULTIPLATFORM_LIBPYTHON` overrides), and the resource
registration is gone.

**What is not done**, and should not be read as done:

- ~~`tp_traverse` functions are generated and tested, but nothing wires them into CPython's actual
  `tp_traverse` slot, and `tp_clear` and Kotlin-side cycle closing are untouched.~~ **The slot
  wiring is closed** — `work/cycles` (`83233767`, merged `aa5436d1`, 2026-08-14) built
  `ProxyTypeFactory.createProxyType()` on every target: a real `PyType_FromSpec` heap type with
  `Py_TPFLAGS_HAVE_GC`, `tp_traverse`/`tp_clear`/`tp_dealloc` slots that call the KSP-generated
  traverse function through `ClassLookup`, and `tp_dealloc` releasing the `HandleTable` entry.
  `CycleCollectionTest` (desktop, Kotlin/Native, ART) and `WasmCycleCollectionTest` build a real
  `P -> proxy -> handle -> Kotlin -> PyObject -> P` cycle, drop it, call `gc.collect()`, and check
  the handle is gone — re-verified on this tree 2026-08-18 (both tests pass; see this section's
  audit entry below). ~~The production proxy classes `PythonProxySource` renders never construct an
  instance of `ProxyTypeFactory`'s type.~~ **Closed on desktop first** (`work/handleslot`,
  2026-08-18), **and by the end of the same day on every other target too — see the "Both are
  closed too" and "Closed on iOS and androidNative too" entries below; this heading undersold the
  scope even as it was being written.** `dbf54e21` (same day, `work/untrack`) had already answered the risk this item used
  to end on — a Python subclass of the heap type survives being `PyObject_GC_UnTrack`ed twice, so
  `subtype_dealloc`'s own untrack ahead of `ProxyType.tp_dealloc`'s is safe — and had also found
  that inheritance alone buys nothing: the handle has to reach the type's own storage before a
  `tp_traverse` inherited through a Python subclass has anything to read, and nothing wrote it
  there. That is what closes here, on desktop only:

  - `ProxyTypeFactory` (desktop) grew a `Py_tp_members` slot: one `PyMemberDef` named `_pm_handle`,
    `Py_T_LONG`, carrying `Py_RELATIVE_OFFSET` (CPython 3.12+, exactly the flag documented for a
    type extending storage under multiple/deep inheritance — precisely this case). Offset 0 is the
    same base `PyObject_GetTypeData` already computes for `tp_traverse`/`takeHandle`, so
    `self._pm_handle = ...` on an instance of this type — including a further Python-level
    subclass — writes the C slot directly, with **zero change** to the constructor line
    `PythonProxySource` already renders.
  - `ProxyTypeFactory.installGcBase()` (new `expect`/`actual`, `false` on every target but desktop)
    publishes the type into `__main__` as `_pm_proxy_base` via `PyObject_SetAttrString` — the type's
    own address is a valid `PyObject*`, so this needs no `ctypes` round trip (unlike the
    proof-of-concept in `dbf54e21`'s test) and nothing new for wasm's missing `ctypes` to block.
  - `PythonProxySource` gained `_PmGcObject`: `_pm_proxy_base` subclassed if the bootstrap set it,
    else `_PmObject` (today's behaviour, unchanged, on every non-desktop target). `renderClass` uses
    it instead of `_PmObject` exactly when `cls.hasTraverse` is true **and** the class has no
    metaclass — `_pm_owned_new` only ever adds `_PmObject` to a metaclassed class's bases, and the
    two owners cannot be combined (`class _PmGcObject(_pm_gc_base, _PmObject):` is a real
    `TypeError: multiple bases have instance lay-out conflict`, both being solid bases). A class
    with no traversable `PyObject` field, or one with a metaclass, renders exactly as it did before
    — `RefHolderCycleCollectionTest.anOrdinaryClassWithNoPyObjectFieldsKeepsRenderingOnTheOldOwner`
    pins `class Counter(_PmObject):` unchanged.
  - Proved against a *real* KSP-exposed class, not the hand-built proxy the existing cycle tests
    construct through direct C-API calls: `ksp-fixtures:app`'s new `RefHolderCycleCollectionTest`
    installs the generated table, `exec`s the real generated `fixture.library.RefHolder` proxy, and
    builds `_rh -> (opaque handle) -> Kotlin field -> [a plain list] -> _rh` — a cycle whose only
    edge back to `_rh` lives on the Kotlin heap, unlike `ProxyHandleLifetimeTest`'s existing
    `c.box -> box -> c`, which closes entirely through ordinary Python attributes `subtype_traverse`
    already walks and so does not exercise this gap at all. Red before the fix
    (`HandleTable.liveCount` stayed at `baseline + 1` after `gc.collect()` — the leak, observed, not
    inferred), green after (three tests, 0 failed).
  - ~~A real, pre-existing, and unrelated bug surfaced while building that test and is **not fixed
    here**: `_pm_unwrap` unwraps *any* `_PmObject`-derived argument to its raw `HandleTable` handle
    before a call, which is correct for an owned-result argument but wrong for a `PyObject`-typed
    Kotlin parameter — `_rh.primary = _rh` (as opposed to the list-wrapped version the test uses)
    resolves to the underlying Kotlin object on the way in and fails the property's own cast
    (`ClassCastException`, observed). Filed here rather than fixed because it is orthogonal to the
    handle slot and was not this task's spec.~~ **Fixed** (`886a8e8f`): `PythonProxySource.argValues`
    now unwraps a `TypeTag.OBJECT` argument only when the parameter's *declared* type is not
    `python.multiplatform.ffi.PyObject`, read off `ExposedCallable.paramTypeNames[i]` — the same
    field the return direction already used for this ambiguity — instead of testing the value's
    shape. `ProxyObjectArgumentTest` (`ksp-fixtures:app`) pins both `_rh.primary = _rh` and the
    mirror-image case a base-class test had been hiding by accident (a traversable class's proxy is
    never an `_PmObject`, so testing only that base meant every proxy of a class with a `PyObject`
    field skipped unwrapping regardless of what its parameter declared).
    One instance was left latent and has since been fixed too, in this branch:
    `PythonCallables.Fragment`'s `pythonx.runtime.newFunction` declared its `body` slot
    `paramTypeNames[0] = "kotlin.Any"` while its callable does `args[0] as PyObject` — the same
    defect from the other type name, reachable only through a caller that does not exist in this
    repository (`PythonxAdapter._make_function` calls `newFunction` by resolved handle directly,
    never through a `PythonProxySource`-rendered wrapper). Reproduced deliberately by building that
    caller (`PythonCallablesProxyArgumentTest`, `desktopTest`: registers `PythonCallables.Fragment`
    into the same `UpcallTable` as a real proxy class and calls the rendered
    `pythonx.runtime.newFunction` with a proxy in `body`) — before the fix it raised
    `RuntimeError: class ...ProxyCounter cannot be cast to class ...PyObject`, observed, not
    inferred. Fixed by declaring `body` as `python.multiplatform.ffi.PyObject`;
    `PythonxTableSourceTest` pins the corrected row. Checked before committing to the fix: nothing
    dispatches this entry through `PythonxAdapter`'s own `_coerce`/`_TABLE` allowlist (it is always
    invoked by literal resolved handle), so the changed declared type does not move any Python-side
    behaviour that exists today — it only fixes the row a future `_coerce`-routed caller would see.
  - **Left exactly where it was** on Android, iOS, androidNative and wasm: `installGcBase()` is a
    literal `false`, `PythonProxySource` still falls back to `_PmObject` there, and nothing about
    those four targets' `ProxyTypeFactory` changed. What each one still needs, per `ProxyTypeFactory`'s
    now-updated class doc: Android already has `ProxyTypeFactory.setHandle` (a native function a
    generated `__init__` could call — option (B)) but no Python-visible base to subclass and no
    `__init__` rewrite to call it; iOS/androidNative's shared `nativeMain` factory has neither a
    `Py_tp_members` slot nor a setter, and would need `PyMemberDef`/`PyType_Slot` filled in by
    cinterop-generated structs rather than `sun.misc.Unsafe`, which is most of why desktop went
    first; wasm has the `PyObject_SetAttrString` publication route available (no `ctypes` needed,
    same as desktop) but is missing the `Py_tp_members` slot itself, and its `PyMemberDef`/
    `PyType_Slot` are 20/8-byte 32-bit structs, not the 64-bit layout desktop's `Unsafe` code
    assumes — the pointer-width trap `dbf54e21` already paid for once.
  - Verified: `desktop 485/0`, `wasm 440/0` (browser 10, node 430), `ksp-fixtures:app:desktopTest`
    64→67/0 (three new tests), `compileKotlinAndroidNativeArm64` and `compileDebugKotlinAndroid`
    both green — `git diff --stat` touches only the five `ProxyTypeFactory.kt` files,
    `PythonProxySource.kt`, and the one new test file.
  - **Closed on iOS and androidNative too** (`work/hs-native`, 2026-08-18), confirming the desktop
    doc's own prediction that the two cinterop targets would be *easier* than desktop rather than
    harder: `PyType_Spec`/`PyType_Slot`/`PyMemberDef` come out of cinterop as real structs with
    named fields, so `nativeMain`'s `ProxyTypeFactory.createProxyType()` fills in a `Py_tp_members`
    slot — `_pm_handle`, `Py_T_LONG`, `Py_RELATIVE_OFFSET` — by field assignment instead of
    `sun.misc.Unsafe` offset arithmetic, and `installGcBase()` publishes the type into `__main__`
    exactly as desktop's does (`PyObject_SetAttrString` on the type's own address, no `ctypes`).
    Because `nativeMain` is shared, this is one implementation for both targets rather than two.
  - Proved with a native test rather than a KSP fixture — `ksp-fixtures/app` has no iOS leaf, so
    the RefHolder-through-KSP route the desktop proof used does not reach this platform. Instead
    `nativeTest/.../CycleCollectionTest.kt` gained
    `aHandleWrittenFromPythonClosesACycleThroughAKotlinField`: it publishes `_pm_proxy_base`,
    defines a Python subclass (`__slots__ = ()`, so no `__dict__` to fall back to), and writes
    `self._pm_handle = h` — the exact line a generated `__init__` emits — then reads the C slot
    directly (`PyObject_GetTypeData`) to confirm the write landed there rather than merely
    succeeding as an ordinary attribute, before closing a cycle through a Kotlin field and
    collecting it. Red before the fix (`installGcBase()` returning `false` fails the first
    assertion), green after — `iosSimulatorArm64Test` 429/0 (verified as the same run, not
    inferred: the new test's own `<testcase>` entry carries no failure or error node), `desktop
    500/0` unchanged, `compileKotlinAndroidNativeArm64` and `compileTestKotlinAndroidNativeArm64`
    both green (androidNative has no test device attached in this environment, so only compiled,
    per this file's own standing note on §11.1's device constraint). `git diff --stat` touches only
    `ProxyTypeFactory.kt` (nativeMain) and the one test file.
  - ~~**Still open**: Android and wasm, per the per-target notes above — unchanged by this round.~~
    **Both are closed too** (`a5387511` for wasm, `1544f095` for Android — both landed before the
    iOS/androidNative round above, and this section was never updated to carry them forward; caught
    while auditing this file against the code rather than the commit log). Verified by reading the
    current source, not by re-running the commits' own numbers: `wasmJsMain/ProxyTypeFactory.kt`'s
    `createProxyType()` fills the `Py_tp_members` slot with `PY_T_LONGLONG` for `_pm_handle` (eight
    bytes on wasm32, where a plain `long` is only four — the corruption `a5387511` traces losing the
    top half of the handle on assignment), and its `installGcBase()` publishes `_pm_proxy_base` via
    `PyObject_SetAttrString` exactly as desktop's does, no longer a literal `false`.
    `artMain/cinterop/jni_onload.def` declares the same member (`"_pm_handle", Py_T_LONG,
    Py_RELATIVE_OFFSET`, wired at `Py_tp_members` slot 3) as a real C struct compiled against the
    JNI headers, and `androidMain/ProxyTypeFactory.kt`'s `installGcBase()` is a real
    `createProxyType()` + `PyObject_SetAttrStringN` call, not a stub. `Py_T_LONG` rather than
    `Py_T_LONGLONG` is correct here and not the wasm bug repeating: Android is a 64-bit-only target,
    where C `long` is already eight bytes.
    **So the handle slot is now real on all five targets** (desktop, wasm, Android, iOS,
    androidNative). The one gap left is the one `581ec772` already named:
    androidNative compiles (`compileKotlinAndroidNativeArm64`,
    `compileTestKotlinAndroidNativeArm64`) but has never run its tests on a device or emulator in
    this environment — nothing attached, same standing constraint as §11.1. Desktop, wasm, Android
    (emulator) and iOS simulator all have a green test run cited in this section; androidNative does
    not.
- ~~Companion-object members, interfaces, enums and annotation classes are not exposed.~~
  **Closed**, except annotation classes, which are now deliberately excluded — see below.
- ~~The aggregator uses `Dependencies.ALL_FILES`, correct but reprocessed every build.~~
  **Measured, and the aggregator turned out not to be the cause** — see below.
- ~~No convenience Gradle plugin; user modules wire KSP per target by hand.~~
  **Closed:** `python-multiplatform-gradle-plugin/`, applied by id. ~~**Except on Android**, where
  applying it is a configuration-time crash until AGP moves to 8.10.~~ **Fixed too, same day** —
  `gradle/libs.versions.toml` pins AGP `8.10.1` (KSP 2.3.11's declared minimum) and Gradle `8.11.1`;
  see "The convenience plugin could not be applied to an Android module — closed" in §13.

**Was:** entirely unimplemented — `ClassLookup.kt`, `ObjectReference.kt` and `ReflectedClass.kt`
held 1–3 lines each, and this was README's only unchecked box.

Design is settled in `docs/design/upcall-design.md`: build-time generated function table (runtime
reflection is impossible on Kotlin/Native and under GraalVM's closed world), blacklist exposure
(all `public`, minus an opt-out annotation), name resolved once with the handle cached in the
Python proxy's instance data, KSP running in user modules too.

**Module fragment collection is settled** and demonstrated, not inferred. Library modules
generate fragments into a well-known package; the app module's KSP finds them with
`getDeclarationsFromPackage` and emits an aggregator holding explicit references. No
`ServiceLoader`, no reflection, no `@EagerInitialization`. A three-module experiment under
`ksp-fixtures/` compiles and runs, discovering fragments across a module boundary. Two caveats
carried over: `.klib` discovery on a Native target is inferred rather than tested, and
`getDeclarationsFromPackage` is `@KspExperimental`, so keep that step swappable. See
`docs/design/upcall-table-design.md`.

**The generator exists now.** `python-multiplatform-ksp/` is the shipped KSP processor (library
role emits a fragment, app role emits a fragment for itself then aggregates every `Fragment_*`
it can see). `ksp-fixtures/{library,app}` is the TDD harness: `GeneratedTableTest` (11 tests, JVM)
runs `UpcallTableTest`'s exact scenarios — constructor/method/getter/setter through
`HandleTable`, `@PythonInternal` exclusion, narrow `Int`/`Float` boundary widening, `tp_traverse`
field detection — against a table KSP generated rather than a hand-written fragment. `.klib`
discovery is no longer inferred: the same pair on `androidNativeArm64` found the library's
fragment from its compiled `.klib` and linked a real test binary (compiled and linked only, not
run — no device in this workspace). See `docs/design/upcall-table-design.md` for the full account,
including the one place the doc's own sketch was wrong (fragments must be `public`, not
`internal` — Kotlin enforces `internal` per module, and the app module compiling generated code
that references a library's fragment is a different module even inside one Gradle build).

**Tree shaking is not solved, but it is now measured.** A table referencing every `public`
declaration does defeat dead-code elimination, and that is inherent to blacklist exposure plus
static linking. 200 synthetic exposed functions cost ≈1.84 KB/entry in a stripped
`androidNativeArm64` binary (368,640 bytes total) — a floor, since the synthetic functions were
trivial one-liners and a real function's body adds its own size on top. Whether that floor plus a
real library's bodies is acceptable is a product judgement the measurement informs but does not
settle. See `docs/design/upcall-table-design.md` §4.

**The declaration surface is now the whole one, and what is left out is left out on purpose.**
Companion members and `object` members become receiver-less entries under the *owner's* name
(`Owner.member`, two new `CallableKind`s: `STATIC_GETTER`/`STATIC_SETTER`); interfaces get entries
and no constructor, so a Kotlin object reaching Python through a handle is callable through the
interface even when its concrete class does not redeclare the member; an `enum class` gets one
`STATIC_GETTER` per entry plus `name`, `ordinal` and `valueOf`, and its `ReflectedClass` carries
`enumEntryNames` so the Python side can build an `enum.Enum` mirror without the boundary having to
marshal a collection. Nested declarations and top-level properties came along with it.
**Annotation classes are exposed nowhere**: applying one is a compile-time act and reading one
back needs runtime reflection, which is the single thing this design cannot have, so an instance
Python could construct would have nothing to attach to. `values()`/`entries` are out for the same
kind of reason — they return collections the boundary cannot carry.

Three latent generator bugs surfaced while widening the surface, each of which produced a
*generated file that did not compile* rather than anything the processor could detect: a cast to a
generic type without its arguments (`args[0] as kotlin.collections.List`), a constructor entry for
an abstract class, and a declaration over a type parameter (`args[0] as T`). Parameter types now
render their arguments; abstract, sealed and `inner` classes get no constructor entry; generic
declarations are not exposed at all. `data class` `copy`/`componentN` are dropped too — KSP does
report them (it does not report `equals`/`hashCode`/`toString`), and `binding-policy.md` already
said compiler-generated members stay out.

**Incremental aggregation: measured, and the aggregator was never the problem.** KSP's own dirty
set (`ksp.incremental.log=true`, `build/kspCaches/.../kspDirtySet.log`) reports **100% dirty on a
one-file change in a two-file module both before and after** narrowing the aggregator's
`Dependencies` from `ALL_FILES` to the fragment files it actually reads. The cause is one level
down: a module's own `Fragment_<module>` is legitimately an aggregating output over *every* source
file — blacklist exposure means any file can add an entry — so any change regenerates it, and KSP
then marks every file that maps to it dirty. `ALL_FILES` on the aggregator stays, because the
narrower form buys nothing measurable and its safety under a classpath-only change was never
established. The only route to real incrementality is per-file fragments (one isolating output per
source file), which changes fragment naming, `UpcallTable`'s per-module idempotency and duplicate
detection — a design change, not a tweak. Recorded in `docs/design/upcall-table-design.md` §11.5.

**Cycle collection: the generator's half is done, and so — separately — is the slot-wiring half.**
`tp_traverse` on the Python proxy must reach through the handle into the Kotlin object's
`PyObject`-typed fields, which means the generator emits a traverse function per exposed class
alongside the call entries. That part is implemented and tested (`FragmentScanner` detects
`PyObject`-typed — including subclass-typed — fields via `isPyObjectType`, emits
`traverse = { obj, visit -> ... }`, and `ReflectedClass.hasTraverse` / `.traverse(...)` are
exercised end to end in `ksp-fixtures`). This section used to say the *rest* — the proxy type's
`tp_traverse` slot wiring, `tp_clear` mutating Kotlin state — was untouched. It was written before
`work/cycles` (merged `aa5436d1`, 2026-08-14): `ProxyTypeFactory.createProxyType()` now builds a
real `Py_TPFLAGS_HAVE_GC` heap type on every target with those two slots (plus `tp_dealloc`) wired
to exactly this generated function, and `CycleCollectionTest`/`WasmCycleCollectionTest` prove a
real cycle collects (re-run on this tree 2026-08-18: both pass). ~~Re-verifying that claim for this
task surfaced the part that is genuinely still open and is *not* what this section used to
describe: **nothing routes a production proxy through that type.** `PythonProxySource` — the
renderer `install()` actually `exec`s — builds every class on a plain Python `_PmObject` base, and
`ProxyTypeFactory.createProxyType()` is called from no non-test code on any target.~~ **Closed by
`work/handleslot` (`99acd830`, 2026-08-18, merged `a1fb825c`), the same day this was written.**
`PythonProxySource.renderClass` now emits `class $className(_PmGcObject):` instead of `_PmObject`
whenever `ReflectedClass.hasTraverse` is set and there is no metaclass
(`PythonProxySource.kt:1168`, `val gcEligible = metaclassName == null && cls.hasTraverse`), and
`_PmGcObject` subclasses `_pm_proxy_base`, which every platform's `ProxyTypeFactory.createProxyType()`
publishes into `__main__` (`ProxyTypeFactory.kt:23`, and the per-platform `actual`s at
`desktopMain/.../ProxyTypeFactory.kt:253`, `androidMain/.../ProxyTypeFactory.kt:174`,
`nativeMain/.../ProxyTypeFactory.kt:345`, `wasmJsMain/.../ProxyTypeFactory.kt:311`) — see §14b
item 5 for the full account, including the `subtype_dealloc` double-untrack test. Cycles that close
on the Kotlin side (the handle table holding weakly) are separately still untouched, as
`docs/design/object-lifetime.md` already recorded.

Cost is not yet measured. The table lookup is an array index and is not the expense; the
boundary is. Note that iOS and androidNative have no boundary here at all — Python and Kotlin
share one binary — so this is a JVM-only cost.

## 7b. Finish `PyValue`

**Closed for the eager path.** `PyContext` was already complete — `withContext` restores the
strategy even on throw, and all five `ConversionStrategy` variants dispatch. `PyValue`/`PyProxy`
now is too: `toKotlin`, `toKotlinOrNull` and `toPython` are implemented, no TODO stub remains in
`PyProxy.kt`, and `ConversionTest` passes on desktop (in the 118-test run) and on the iOS
simulator (111 tests).

**Closed for the lazy path too.** The per-type lifetime rule this section named as the last
missing piece is written — `docs/design/object-lifetime.md`, "Conversion caching, and where it stops",
one row per source type, derived from what each conversion actually returns rather than from what
the type looks like — and the cache is implemented against it:

- scalars and the decoded `String` copy themselves out of CPython and are cached; a converted
  container is cached as a **snapshot**, independent of Python memory precisely because it is a
  copy, with `invalidateNativeCache()` as the stated way to re-read a container that has since
  been mutated;
- `memoryview` is **refused**, because a copy still would not carry its format, shape and strides.
  `bytes` and `bytearray` were refused alongside it on the ground that their native form is a
  pointer into the object's own buffer, until a `ByteArray` copy showed that ground does not reach
  them — both convert now, `bytearray` as a snapshot;
- a bare `NativePointer` — what `ConversionStrategy.RAW` returns — is rejected by `PyValue`'s
  constructor: it is an address, not a reference, and storing it would fail only once the address
  had been reused;
- `isIndependentOfPythonMemory` is the rule as code, and every store to `cachedNativeValue` on
  the conversion path goes through it.

`PyValueLazyConversionTest` (13 tests, commonTest) is the coverage `ConversionTest` never had:
lazy conversion of an *untyped* source for all nine builtins the walk understands, a cached value
outliving the release of its source with the heap churned underneath it, the snapshot going stale
and then being invalidated, and the refusals — buffers, user-defined classes, builtin subclasses,
`complex`, `None` — each asserted to leave the cache empty rather than throw an NPE.

**The lazy path also caught a borrowed reference.** `PyContext.proxyConvert` handed the `PyValue`
the *caller's* wrapper whenever `typedWrap` had no dedicated wrapper to build, so the `PyValue`
owned nothing of its own and dangled as soon as the caller closed that wrapper. Measured as a
missing `+1` on `sys.getrefcount` (3 where 4 was required) and fixed by taking an independent
reference on that path.

**Was:** `PyValue`/`PyProxy` incomplete — `toKotlin()` and `toPython()` were TODO stubs ending in
`cachedNativeValue!!`, so a cache miss was an NPE. It was said to work only because the basic
types bypassed it — `PyInt`, `PyFloat` and the rest convert inside their own `cachedNativeValue`
accessors and never reach the stub, so `TYPED` conversion of a builtin succeeded while anything
without a dedicated wrapper would fail. Both halves are gone: no `!!` on that field remains in the
library sources, and an untyped source now converts through the generic walk (observed, per
builtin).

**What is still not converted**, refused rather than guessed at: `memoryview`, subclasses of
builtins (the dispatch is by exact type, mirroring `PyLong_Check` rather than `isinstance`),
`complex`, and any user-defined class — `TYPED` stops at the `PyObject` for those by design.

`bytes` was on that list with the note that a `ByteArray` copy would also be correct and was not
implemented. It is implemented now, and `bytearray` with it: once the conversion is a copy, the
buffer-pointer objection stops applying to either, so refusing one and not the other would have
been arbitrary.

`complex` was on that list under the subclasses-of-builtins reason, and that reason was wrong for
it. `complex` **is** an exact type and `PyComplex` exists; it is refused only because neither
`typedWrap` nor `pyObjectToNative` carries an entry for it. It stays refused — there is no Kotlin
counterpart type to convert into — but not for the reason given here.

## 8. `jvmMain` unification

**No longer a performance item.** `invokeExact` was reached on desktop without it (1015.95 ns →
2.65 ns), so what remains is maintenance debt: 380 Android + 315 desktop hand-written
declarations.

The duplication has caused real bugs — desktop's `find()` carried the symbol name separately
from the `actual`, which is how `Py_RunMain` came to be bound to `Py_FinalizeEx`.

**Attempted at scale (2026-08-18, `work/jvmmain`) and the "~330 → 14 shapes" estimate above does
not hold.** `EmbedAPI.android.kt`/`EmbedAPI.desktop.kt` were parsed (not grepped — a hand-rolled
parser that walks brace/paren balance and `if`/`else` continuations, checked against the 314
`commonMain` `expect` declarations) and every `actual` body compared by exact text after
normalizing whitespace. Of 314 common declarations, **3 have literally identical bodies**:
`toAddressValue`, `toRawValue`, `AddressValue.toNativePointer`. The other 311 differ for a
structural reason, not a cosmetic one — Android's JNI dispatch needs a symbol name per calling
convention (`PyErr_ClearN`/`PyErr_ClearF` vs desktop's unsuffixed Panama handle `PyErr_Clear`) and
often a `preferFastNative` branch besides, so almost every wrapper's body names a different callee
on each platform. That is "different wiring" in exactly the sense this document already warns
about — not a case the recipe was wrong to rule out, but evidence the duplication is smaller than
it looks from the outside.

Of the 3 identical wrappers, only **1 was actually movable**: `toRawValue`, moved to
`jvmMain/kotlin/python/native/ffi/EmbedAPI.jvm.kt` along with the `toPlatformPointer()` helper it
calls (itself hoisted, since `jvmMain` code cannot reference a symbol that only exists in a leaf
source set — `androidMain`'s `toPlatformPointer(): JNIPointer` and desktop's `toPlatformPointer():
Long` are the same behavior under the `JNIPointer = Long` typealias, so hoisting changed nothing
observable). The other two, despite identical wrapper text, both construct or unwrap
`NativeAddressValue` — a class whose `toString()` genuinely differs per platform
(`"JNIPointer(raw=0x…)"` on Android vs `"NativeAddress(0x…)"` on desktop, and Android's uses signed
`ptr.toString(16)` where desktop's uses `ptr.toULong().toString(16)`). Hoisting the wrapper would
require also hoisting that class, which would force one platform's `toString()` to change —
disallowed by "동작이 바뀌면 안 된다." They stay duplicated, correctly.

**The recipe (remove `inline` from the `commonMain` `expect`, delete the two platform `actual`s,
add one in `jvmMain`) is confirmed safe, not just asserted.** It was applied to `toRawValue` and
compiled clean on both `compileKotlinDesktop` and `compileDebugKotlinAndroid`. The Kotlin 2.0.20
`Internal error in file lowering` crash this section used to warn about **did not reproduce even
with `inline` deliberately left in place** on this function (tested and reverted) — for this
specific case at least, the risk turned out to be smaller than documented, though the sample size
is one trivial no-argument function and the crash may still be real for a shape not tried here.
Test counts unchanged (`desktopTest` 485/0/1, `ksp-fixtures:app:desktopTest` 64/0 — note these are
higher than a `484/0/1` figure recorded elsewhere in this document, which was stale before this
task started, not a regression this task caused; verified by re-running the suite with the change
stashed out). `compileKotlinAndroidNativeArm64` unaffected (this item is JVM-only).

## 9. Free-threading

**Nothing is being waited on. It builds, it runs, and what it breaks is now known.**
`./gradlew :python-multiplatform:desktopTest -PpythonFreeThreaded=true` runs the whole desktop
suite against `cpython-3.14.7+20260807-<target>-freethreaded-install_only`:

| build | tests | failing | skipped |
|---|---|---|---|
| default (GIL) 3.14.7 | 236 | 0 | 1 |
| free-threaded 3.14.7 | 236 | 0 | 1 |

This section used to say "3.15t" and "waiting on upstream"; neither was true. 3.14 free-threaded
is what was measured here, and those artefacts have been on the python-build-standalone release
all along.

**It is now green on both.** It used to report two failures. Deferred deallocation was the first
and is fixed by the checkpoint below. `CycleCollectionTest` was the second, and it was written off
twice — first as a premise the runtime does not honour, then as a queued-decrement problem a
checkpoint would flush. Both were wrong, and it is fixed; see below and
`docs/investigations/gc-scheduling-investigation.md`.

### The flag did nothing at all until three silent defects were fixed

None of these produced an error message; all three made the build quietly do something other
than what was asked.

- **Extraction was not keyed by what it extracted.** Every unpack is guarded by "is the
  destination directory empty?", and the destination was a flat `extracted/<platform>`. Passing
  `-PpythonFreeThreaded=true` therefore downloaded the free-threaded archive, verified its
  checksum, and then *discarded* it, because the GIL build had already filled the directory. The
  build then ran the GIL interpreter while every log line named the free-threaded tarball. The
  identical hazard applied to `-PpythonVersion`. Extraction is now
  `extracted/<version>/<platform>[-freethreaded]` (`extractedDir`, `desktopFlavourSuffix`).
- **A free-threaded install renames everything looked up by name.** It ships
  `libpython3.14t.dylib`, `lib/python3.14t/` and `bin/python3.14t`, and does *not* ship the
  un-suffixed names. `manager.loadLibPython` asked for `libpython3.14.dylib`, which such an
  install does not contain. `Versions.abiFlags` / `taggedVersionString` now carry the `t`, fed by
  a new `BuildConfig.pythonFreeThreaded`.
- **`updatePythonChecksums` would have destroyed the lockfile.** It wrote `"\\n"` — a literal
  backslash and an `n`, not a newline — so the whole file came out as one physical line beginning
  with `#`, which `Properties` reads as a single comment. One run would have deleted every
  checksum and left the next build failing "Missing checksum" for all of them. The committed file
  was intact only because nobody had run the task since it was written.

### Free-threading defers deallocation to the owning thread, and a pure embedder never gets there

`GCLeakTest.testCascadingReleaseOnGC` used to fail. This is the important finding in this section,
and it is the free-threading counterpart of §1: turning the feature on exposed an assumption, not a
typo. **It now passes, unmodified**, because the library reaches the checkpoint the runtime is
waiting for. The diagnosis below stands; the last subsection records the fix.

The test builds 1000 Python lists each holding one target object, drops the Kotlin wrappers, and
waits for the cleaner to release them. Measured on the free-threaded build (the cleaner is
confirmed to have run — `ReleaseCounter.released` rose by 2003):

| after | `sys.getrefcount(target)` |
|---|---|
| 1000 lists built, each holding `target` | 1002 |
| 50 forced JVM GCs; cleaner called `Py_DecRef` 2003 times | 1002 |
| 500 further C API round trips on the owning thread | 1002 |
| 2 s of wall clock | 1002 |
| `Python3.exec("pass")` — one trivial bytecode frame | **2** |

So the decrements happened, and the objects were not freed. Not time, and not C API traffic:
entering the eval loop *once*, for a body that does nothing, released all thousand at once. That
is the signature of the free-threaded build's biased reference counting — a decref from a thread
that does not own the object cannot run `tp_dealloc` there, so the object is queued to its owner,
and the owner drains that queue at an eval-loop checkpoint.

Under the GIL there is no such queue and the cleaner's decref frees immediately. The consequence
for this library is specific and unpleasant: **an embedder that drives CPython entirely through
the C API never reaches a checkpoint, so memory released by the cleaner is never actually
reclaimed.** It is not a leak in the sense of a lost pointer — the refcount is correct and one
line of Python flushes it — but a Kotlin process that only ever calls `PyObject_Call` will grow
without bound.

### The checkpoint is the fix, and the Stable ABI does not offer a way to reach one without bytecode

The open question above was *what to call to force a drain, and from where*. Both halves have
answers now, and the first one is a "no" that had to be established before the second made sense.

**CPython exposes no non-bytecode way to merge the queue on the current thread.** The merge lives
behind `_PY_EVAL_EXPLICIT_MERGE_BIT`, and `Python/ceval_gil.c` clears that bit in exactly one
function:

```c
int _Py_HandlePending(PyThreadState *tstate) {
    ...
#ifdef Py_GIL_DISABLED
    if ((breaker & _PY_EVAL_EXPLICIT_MERGE_BIT) != 0) {
        _Py_unset_eval_breaker_bit(tstate, _PY_EVAL_EXPLICIT_MERGE_BIT);
        _Py_brc_merge_refcounts(tstate);
    }
    if (_Py_qsbr_should_process(((_PyThreadStateImpl *)tstate)->qsbr)) {
        _PyMem_ProcessDelayed(tstate);
    }
#endif
    if ((breaker & _PY_GC_SCHEDULED_BIT) != 0) { ... _Py_RunGC(tstate); }
    ...
}
```

`_Py_HandlePending` has one caller family: the `_CHECK_PERIODIC` / `_CHECK_PERIODIC_IF_NOT_YIELD_FROM`
uops in `Python/bytecodes.c`, which open every Python-level frame (`RESUME`) and close every call
instruction. There is no public entry point.

`Py_MakePendingCalls` is the obvious Stable ABI candidate and it **does not work**. Read the
source and it cannot: it forwards to `_PyEval_MakePendingCalls`, which handles `handle_signals`
and `make_pending_calls` and returns — the merge bit is not among them. Measured, on the
free-threaded build with 1000 releases queued, it returns 0 and leaves `sys.getrefcount` at
1002. `EvalCheckpointTest` asserts exactly that, so the day CPython changes its mind, the test
says so.

`PyGC_Collect` **does** work, and is the only Stable ABI function that does, because
`gc_collect_internal` stops the world and walks every thread state:

```c
_Py_FOR_EACH_TSTATE_BEGIN(interp, p) {
    _PyObject_MergePerThreadRefcounts((_PyThreadStateImpl *)p);
    merge_queued_objects((_PyThreadStateImpl *)p, state);   //  <-- the drain
}
```

That is the one option that reclaims on behalf of a thread *other* than the caller. It is also
the most expensive thing here by three orders of magnitude, because it costs a heap walk rather
than a queue pop. Both functions are now bound (`Py_MakePendingCalls`, `PyGC_Collect`); both are
in `Misc/stable_abi.toml`.

So the drain has to be an eval-loop entry, and the only question left is how cheap one can be
made. `exec("pass")` recompiles a module every time. A *cached* zero-argument Python function
whose body is `pass`, called through `PyObject_CallNoArgs`, does not — and it still reaches the
checkpoint after specialisation, because `RESUME_CHECK` deopts back to `RESUME` whenever
`eval_breaker != version`, and the merge bit makes them differ. Measured on the free-threaded
build (`EvalCheckpointTest.testCheckpointCostAgainstTheAlternatives`, macOS arm64):

| | ns/op | |
|---|---:|---|
| `withGIL { }` — attach and detach, nothing else | 154.6 | the floor |
| `Python3.drainPendingReleases()` | 283.0 | **the checkpoint, ~128 ns over the floor** |
| `withGIL { Py_MakePendingCalls() }` | 183.1 | cheap, and does not merge |
| `Python3.exec("pass")` | 6 458.9 | 23× — it recompiles |
| `withGIL { PyGC_Collect() }` | 256 447.1 | 906× — heap walk, but drains every thread |

**Where it is called from.** Not the cleaner — that was ruled out before the mechanism was known
and the measurement confirms the instinct was right for a second reason: the queue belongs to the
thread that *owns* the object, and the cleaner owns nothing, so a checkpoint taken there would run
Python on a cleaner thread (§1's deadlock) and drain an empty queue for it.
`EvalCheckpointTest.testCleanerActivityAloneTakesNoCheckpoint` pins that suppression.

It rides on the next ordinary call instead. `withGIL` takes a checkpoint at its outermost entry,
behind two gates: at most one per `Python3.autoDrainInterval` outermost scopes (32), and skipped
entirely unless `ReleaseCounter.released` has moved since the last one, so a workload that drops
no wrappers pays a field compare. Amortised that is ~4 ns on a 155 ns scope. `drainPendingReleases()`
is public for anyone who wants to force one, and `autoDrainInterval = 0` turns the automatic path
off.

The automatic path defaults **on for free-threaded builds and off otherwise**, which is why the
default suite's numbers are unchanged. One guard is worth naming: the checkpoint declines to run
when `PyErr_Occurred()` is non-null. Several call sites read the error indicator *after* their
`withPython { }` scope closes — `PyObject.getAttr` is one — and entering the eval loop can replace
it, since a pending signal handler or a finaliser raising is enough.

**This is not only a free-threading problem.** `_PY_GC_SCHEDULED_BIT` is cleared in the same
function, and `_Py_ScheduleGC` is how allocation triggers a collection on *both* builds since 3.12.
An embedder that never reaches a checkpoint therefore never runs the cyclic collector either, on
either build. That is not what §9 is about and nothing here depends on it, but it is the same root
cause, and `drainPendingReleases()` covers it for anyone who turns the automatic path on.

### Heap types are deferred-reference-counted, and a collection is what makes the count readable

This was the last free-threaded failure, and it is fixed.
`CycleCollectionTest.testHandleReleasedWhenProxyDiesWithoutCycle` had two independent defects, and
its own guard assertion caught both — the one documented as "not decoration: it is what proves the
probe reads a real refcount". It refused to proceed twice, exactly as designed.

- **The probe read eight bytes at offset 0 of `PyObject`.** Measured on 3.14.7: on the default
  build that word is `ob_refcnt` and steps with the count; free-threaded it is `ob_tid` and never
  moves, while the count is `ob_ref_local` (a `uint32` at +12) plus `ob_ref_shared >> 2` (at +16).
  The failure reported `6171668704` for both "before" and "alive" — a thread id, read as a
  refcount. The probe now branches on `BuildConfig.pythonFreeThreaded`.
- **Fixing the offset was necessary and not sufficient.** With the right fields, "before" and
  "alive" still read identically — `1152921504606846978` — because a free-threaded heap type
  carries `_PyGC_BITS_DEFERRED` and `PyType_GenericAlloc`'s `_Py_INCREF_TYPE` is a no-op for a
  deferred type on its owning thread. Measured on the proxy type: `ob_gc_bits == 0x41`
  (`TRACKED | DEFERRED`) and `ob_ref_shared == 0x3ffffffffffffffd`, a shared count of
  `PY_SSIZE_T_MAX / 8` — the deferred sentinel. Creating 100 instances left all six header words
  bit-identical.

**The conclusion this section used to draw from that is what was wrong.** It said the invariant
"each live instance of a heap type holds one reference to that type" *does not exist* on a
free-threaded build, and that no change to this library could repair the test. It does exist. It is
simply not **observable** until a collection materialises the deferred references — measured, after
one `PyGC_Collect()` the same 100 instances show up as exactly `100 << 2` on `ob_ref_shared`, and
`tp_dealloc` then gives back exactly 100, returning the total to its pre-instantiation value to the
unit. The test now takes a collection on either side of its instantiation loop, free-threaded only.
**The assertions are unchanged** — the same "rises by exactly 100" and "returns to exactly where it
started" hold on both builds.

**A checkpoint is not the fix here, and this is worth keeping straight.** The obvious guess, given
the rest of this section, is that the missing decrements were queued by biased reference counting
and that `drainPendingReleases()` would flush them. Measured: it does not. The header is
bit-identical before and after a checkpoint, because there is nothing queued to merge — the
increments were never made. Deferred reference counting and BRC queueing are different mechanisms
with different remedies, and only the second is what §9's checkpoint is for.

This also revisits what this section previously claimed about `abi3t`. The library proper is clean —
`ProxyTypeFactory` writes only into memory it obtained from `PyObject_GetTypeData` — but that test
does read inside a `PyObject`, and it is the one place that would have to change. That is a
property of using a direct probe, not of the invariant; counting through `sys.getrefcount` would
also work, at the price of the argument's own temporary reference.

The full trace, with the header dumps, is in `docs/investigations/gc-scheduling-investigation.md`. That document
also records the half of this that is **not** free-threading-specific: `_Py_ScheduleGC` only sets
`_PY_GC_SCHEDULED_BIT`, so a pure C API embedder never runs the cyclic collector on *either* build.

### What each platform can actually get

Checked against the live release listings, 2026-08-12:

| target | free-threaded prebuilt | source |
|---|---|---|
| desktop (macOS/Linux/Windows) | **yes** | `python-build-standalone` `20260807`, `…-freethreaded-install_only`, for 3.14.7 and 3.15.0rc1 alike |
| Android | no | python.org's `python-<ver>-<arch>-linux-android.tar.gz` contains `libpython3.14.so` / `libpython3.15.so` only — checked in both 3.14.7 and 3.15.0rc1 |
| iOS | no | neither BeeWare's Python-Apple-support nor python.org's XCframework defines `Py_GIL_DISABLED` |

So free-threading is a desktop-only capability for as long as that holds, and the flag should stay
off by default.

### `abi3t` is not a blocker, because nothing here asks for the Limited API

`abi3t` is Final for 3.15 (PEP 803), and 3.14 free-threaded has no Limited API at all. That was
recorded as the reason to wait. It is not one: `Py_LIMITED_API` is never defined anywhere in this
build — desktop binds symbols by name at runtime through Panama, and the native targets cinterop
against the full headers. "abi3" in this codebase means a self-imposed rule about *which*
functions to call, not a compilation mode. The rule is what keeps one binding working across
versions, and it is unaffected.

### 3.15.0rc1: the three functions are migrated, on every target

`-PpythonVersion=3.15.0rc1` gives **208 tests, 0 failures, 1 skipped** on desktop — the same as
the default. The first attempt failed 4, all of them assertions hardcoding `"3.14"` against the
reported interpreter version (`EmbedApiLowLevelTest`, `InterpreterAvailabilityTest`,
`Python3Test`, `DesktopPythonTest`). They now compare against
`Versions.currentVersion.compactVersionString`, which is strictly stronger: it checks that the
interpreter loaded is the one the build configured, instead of pinning a release line that has to
be hand-edited on every bump. (Two other `"3.14"` literals nearby are `math.pi`, left alone.)

`compileKotlinAndroidNativeArm64` and `compileKotlinIosSimulatorArm64` both fail on 3.15, with the
*same* four errors, from three functions 3.15 removed:

| removed in 3.15 | replacement |
|---|---|
| `PySys_ResetWarnOptions` | none — the `PyConfig` API covers it |
| `PyImport_ImportModuleNoBlock` | `PyImport_ImportModule` (an alias since 3.3) |
| `PyWeakref_GetObject` | `PyWeakref_GetRef` — **present in 3.14 too**, so the migration can be made without dropping 3.14 |

Why desktop does not notice: the symbols are **still exported from the shared library** — checked
with `nm` on `libpython3.15.dylib`, all three are there — and were removed only from the headers.
Panama resolves by symbol name at run time, so nothing breaks; cinterop and the hand-written C in
`jni_onload.def` compile against headers, so they do.

That made the porting cost concrete rather than open-ended: three functions, each appearing in
the `commonMain` `expect`, four platform `actual`s, `jni_onload.def` (declaration, thunk and table
entry) and the wasmJs `@WasmImport` block. Roughly 20 sites, no behavioural change on 3.14.

**Done.** All three were still present when this was picked up, and all three are now migrated:

| was | is now |
|---|---|
| `PySys_ResetWarnOptions` | removed outright — there is no C replacement, and the documented migration (configure `sys.warnoptions` through `PyConfig`, or clear it from Python) is not a binding |
| `PyImport_ImportModuleNoBlock` | kept as a `@Deprecated` **plain `commonMain` function** forwarding to `PyImport_ImportModule`, with no C symbol behind it. It had been an exact alias since 3.3, so forwarding is behaviour-preserving rather than an approximation, and existing source still compiles through the bump |
| `PyWeakref_GetObject` | replaced by `PyWeakref_GetRef`, which 3.13 and 3.14 also export, so nothing was given up to gain it |

**The out-parameter is the part that was not free.** `PyWeakref_GetRef` is
`int f(PyObject *ref, PyObject **pobj)` — a status *and* a write-through slot, where the removed
function was a single return. Three of the four targets already had somewhere to put that slot and
needed no new machinery: `jni_onload.def`'s thunk uses a C local (so the JNI boundary still carries
only primitives, per `androidMain/README.md`), cinterop uses `memScoped`, and wasmJs takes four
bytes from CPython's own heap through the `malloc`/`free` it already imports. Desktop had nothing —
Panama here reaches native memory only through `allocateUtf8String`/`readUtf8String`, neither of
which can carry a pointer, since a pointer is not NUL-terminated. So `Panama` gained
`allocatePointerSlot`/`readPointerSlot`/`freePointerSlot`, built entirely from handles the modern
backend already had (`malloc` plus the same `MemorySegment.copy` that backs `allocateUtf8Freeable`,
run in the other direction). **No new reflective lookups**, so no new JDK-version surface. This will
be reused: CPython is converting more borrowed-reference getters to the same shape
(`PyDict_GetItemRef`, `PyObject_GetOptionalAttr`).

The JDK 16-18 incubator backend throws instead. Allocating there is easy; *reading* is not, and the
class that could (`MemoryAccess`) changed shape across 16, 17 and 18. A guessed read yields a wrong
`PyObject *`, i.e. a use-after-free landing somewhere unrelated — so it refuses at the allocation
step, before anything has been allocated. That branch is already documented as an unverified
best-effort mirror, and JDK 19+ resolves the modern one.

Signature at the Kotlin level is `PyWeakref_GetRef(ref): NativePointer?` — a new strong reference,
or null. C's `0` (dead) and `-1` (error) both arrive as null because an out-parameter does not
survive the JNI boundary, but **no information is lost**: the error case sets the error indicator
and the dead case does not, so `PyErr_Occurred` separates them.

Three tests in `EmbedApiLowLevelTest` (`commonTest`, so they run on desktop, iOS and Android):
`PyWeakref_GetRef` on a live referent returns the referent *and* raises its reference count by one,
which is what distinguishes the new function from the borrowed-reference one it replaces — an
implementation still wired to `PyWeakref_GetObject` would pass an identity check and then corrupt
the heap on the caller's matching `Py_DecRef`; a dead referent returns null with a *clear* error
indicator; and the `ImportModuleNoBlock` shim resolves to the same module object as the function it
aliases.

Writing them turned up a trap worth recording: the first version built its referent from
`types.SimpleNamespace`, which is **not weakly referenceable** (nor are `int`, `str`, `tuple`,
`list` or `dict`; `set` and `frozenset` are). `PyWeakref_NewRef` answered by returning null and
raising `TypeError` — and because every class in the binary shares one interpreter, the unhandled
indicator then took down two *unrelated* tests, which is what surfaced first. The assertions now
clear the indicator before failing, so a failure in this file can no longer be mistaken for a
failure elsewhere.

Verified: `compileKotlinAndroidNativeArm64`, `compileKotlinIosSimulatorArm64`,
`compileDebugKotlinAndroid` and `compileKotlinWasmJs` all compile; `desktopTest` **236 tests,
0 failures, 1 skipped** and `iosSimulatorArm64Test` **222 tests, 0 failures** (both counted from a
cleaned `build/test-results/`). The `jni_onload.def` invariants hold — no duplicate thunk or table
name, registration count still taken with `sizeof`, and every table entry still has a matching
`external fun` and vice versa.

**The default stays 3.14.7.** A release candidate is not a default, and this migration is
version-neutral: `PyWeakref_GetRef` exists on 3.14, so none of it waits for the bump.

### iOS: the source has to change with the version, and 3.15 is the switchover

python.org began publishing an official iOS `Python.xcframework` with 3.15 —
`ftp/python/3.15.0/python-3.15.0rc1-iOS-XCframework.tar.gz`, first appearing at 3.15.0b1.
BeeWare's Python-Apple-support, the only previous source, stops at `3.14-b10` and has no 3.15
release. The two do not overlap, so this is a hard switch on the version rather than a
preference: **3.14 and earlier can only come from BeeWare, 3.15 and later only from python.org.**
`iosFromPythonOrg` in the build now picks between them at ≥ 3.15, and both archives are pinned in
`python-checksums.properties`.

Swapping is otherwise free, because the trees are layout-compatible everywhere this build reaches
into them — `Python.xcframework/<abi>/Python.framework/Headers`, `-F …/<abi>`,
`Python.xcframework/lib/pythonX.Y` and `…/<abi>/lib-arm64/pythonX.Y` all exist in both. The
differences are in parts nothing reads: BeeWare adds `platform-config/` (cross-compilation
sysconfig data for building wheels) and a `VERSIONS` file. The header-set differences
(`module.modulemap`, `lock.h`, `monitoring.h`, `typeslots.h` on one side; `pyabi.h`, `slots.h`,
`slots_generated.h` on the other) are 3.14-vs-3.15, not packaging.

Neither source publishes checksums this build can use — BeeWare publishes none at all, python.org
publishes sigstore material that is not reasonable to verify in Gradle — so both stay pinned by
the local lockfile.

The swap was verified as far as it could be while 3.15 is not the default:
`cinteropPythonIosSimulatorArm64` **succeeds** against the python.org framework, so its headers and
`Python.framework` are consumed exactly like BeeWare's. The build then failed in
`compileKotlinIosSimulatorArm64` — on the three removed functions above, with byte-identical errors
to `androidNativeArm64`, i.e. in shared `nativeMain` source and not in anything iOS-specific.
`compileKotlinIosSimulatorArm64` on the default 3.14 (BeeWare) still passes, so no regression was
introduced for the version actually in use. **That compile blocker is now gone** (see above), which
leaves the acquisition path as the thing to be sure of.

#### The acquisition path is ready; four smaller things are not

Re-read against the build on 2026-08-12. The switchover itself is wired and pinned:

* the predicate is `python-multiplatform/build.gradle.kts`, `iosFromPythonOrg` — it splits the
  configured version and compares major/minor only, so the `rc1` suffix on `3.15.0rc1` does not
  reach `toInt()` and the gate answers correctly;
* `pythonOrgReleaseDir` strips the pre-release suffix separately, because python.org publishes
  `3.15.0rc1` **under `ftp/python/3.15.0/`** while the archive keeps the full name;
* the extraction directory is keyed by version, so 3.14 and 3.15 trees cannot collide;
* `python-checksums.properties` already pins `ios-3.15.0rc1-pythonorg`, both Android ABIs, and all
  four desktop platforms at 3.15.0rc1. A plain `-PpythonVersion=3.15.0rc1` downloads nothing
  unpinned.

What a bump would still hit, none of it iOS-specific and none of it large:

1. **Free-threading has no 3.15 checksums.** There are `-freethreaded` entries for 3.14.7 only, so
   `-PpythonVersion=3.15.0rc1 -PpythonFreeThreaded=true` fails on a missing checksum. Consistent
   with the table above — 3.15 free-threading is desktop-only anyway — but it is a lockfile gap,
   not a deliberate refusal.
2. ~~**Three Android *instrumented* tests still hardcode `"3.14"`**, plus
   `PythonOnDevice.PYTHON_DIR`~~ — **fixed.** The earlier pass that moved four assertions onto
   `Versions.currentVersion.compactVersionString` covered the desktop and common tests and missed
   these, because nothing compiles them without an SDK configured. The three assertions now derive
   from `compactVersionString` and `PYTHON_DIR` from `taggedVersionString`, matching what
   `MainActivity` already did — and `taggedVersionString` additionally carries the `t` suffix for a
   free-threaded build, which the literal could not express.
   **Compile-verified only** (`compileDebugAndroidTestKotlinAndroid`); not run, because the
   emulators were reserved for other work.
3. **The two python.org paths disagree about pre-release layout.** iOS strips the suffix to build
   the ftp directory; the Android download uses the configured version verbatim
   (`ftp/python/3.15.0rc1/…`). A checksum is pinned for the Android archive, so that URL evidently
   resolves today — but only one of the two can be right in general.
4. ~~**The iOS *app* packaging path has no producer.**~~ — **half of this was wrong, and the other
   half is worse than stated.** The *framework* half has a producer and did when this was written:
   `sample/build.gradle.kts`'s `prepareIosFrameworks` is a `Sync` from
   `:python-multiplatform:downloadPython_ios`'s extraction into `sample/build/xcode-frameworks`, and
   every `link*Ios*` task depends on it. Running it populates `Python.xcframework` exactly as both
   the Gradle `linkerOpts` and the Xcode project expect.

   What has no producer is the **standard library** half, and that is what actually stops the app —
   see §13's "The sample runs on iOS" below. The Xcode project's "Install Target Specific Python
   Standard Library" phase rsyncs from
   `Python.xcframework/ios-arm64_x86_64-simulator/lib/`, which in this distribution holds
   `libpython3.14.dylib` **and nothing else**; the stdlib lives in `Python.xcframework/lib/python3.14`
   plus `ios-arm64_x86_64-simulator/lib-arm64/python3.14`, which is precisely the union
   `extractIosSimulatorStdlib` builds for the *test* path. So the app bundle has never carried a
   stdlib, and nothing in the app path sets `PYTHONHOME` either. "iOS works" still should not be read
   as covering the app — but the missing piece is the stdlib, not the framework.

Also stale, and cheap to correct when touched: `iosMain/README.md` still says the stdlib comes from
BeeWare unqualified, and `extractIosSimulatorStdlib`'s KDoc still says `lib/python3.13`. Neither
affects behaviour — the code is `$libVersion`-derived — and `extractIosSimulatorStdlib` does hardcode
the simulator slice name and `lib-arm64`, which is correct for the arm64 host but would need
`lib-x86_64` for an x86_64 simulator.

## 10. WASM

**There is no JS bridge, and the interpreter is running.** Every framing this section previously
carried — that Kotlin/Wasm cannot reach C, that the data path must be copied through JS, that
direct calls and shared memory cannot be had together — is disproved by running code.
`wasm-experiment/` reproduces all of it; `docs/platforms/wasm-design.md` has the detail.

**Test D — Kotlin/Wasm against CPython 3.14.2, built here for `wasm32-emscripten`:**

```
python.wasm exports  8191      Kotlin's intrinsics.memory  ==  CPython's memory
Kotlin: pyExec("answer = 6 * 7; greeting = ...")        -> 0        PASS
Kotlin: pyGlobalInt("answer")                           -> 42       PASS
Kotlin: pyGlobalString("greeting") -> "hello-from-cpython-42"       PASS
Kotlin: pyExec("bytearray(48 MiB)")   320 -> 934 pages, reads still correct   PASS
```

Kotlin wrote the Python source straight into CPython's heap and dereferenced
`PyUnicode_AsUTF8`'s `char*` out of it. Direct wasm-to-wasm calls, one linear memory, no copying
and no JS in either direction — and it survives the interpreter growing its memory by 600 pages.

What made it possible: Kotlin **2.4.20-Beta2 imports** its linear memory instead of exporting it.
That reverses the ownership behind the instantiation cycle, so Emscripten instantiates first and
Kotlin receives both the memory and the exports. The integration is one substitution in the
generated glue — `intrinsics.memory` from a placeholder `new WebAssembly.Memory({initial: 0})` to
`Module.wasmMemory`. `patch-memory-max.py` and the YouTrack issue it was going to justify are both
obsolete: the memory *import* declares no maximum, so any memory satisfies it.

**Composition is closed, the same way §6 closed it on desktop.** Reading a global from `__main__`,
measured against the real interpreter:

| | ns |
|---|---|
| naive | 259.5 |
| + interned C strings | 185.4 |
| + module/dict hoisted | **65.2** |
| one crossing (`PyErr_Occurred`, direct) | **2.9** |

75% of the naive cost comes off with pure Kotlin. What is left is CPython's own work; a composed
`pmp_getattr` could merge two calls into one and save 2.9 ns. Bulk ends the same way — 2.9 ns per
crossing against 0.7 ns per shared-memory read. **No C shim, and therefore no second build
pipeline.**

**Rules for `wasmJsMain`**, both measured:

- **`withScopedMemoryAllocator` must never be called.** It allocates from address `0x0`, on top of
  Emscripten's static data. CPython allocates; Kotlin only dereferences addresses it was handed.
- **`char*` → `String`: `CharArray` + `concatToString()` for ASCII, Emscripten's `UTF8ToString`
  otherwise. Never `ByteArray.decodeToString()` on a large buffer** — 13 ns/byte at 4 KB, ten times
  `concatToString`. The earlier claim that "string marshalling disappears" was wrong: Kotlin/Wasm
  strings *are* JS strings under the `js-string` builtins, so the JS route was never paying for the
  copy the design assumed it paid for.

**The CPython build works and does not yet claim the platform tag.** PEP 783's
`pyemscripten_2026_0` pins Emscripten **5.0.3** (Pyodide `Makefile.envs` at tag `314.0.4`), which
is what was used. The stock Tier 3 build (`Tools/wasm/emscripten`, PEP 776) already matches on
`-sWASM_BIGINT`, no `-pthread`, `-fPIC` and `MAIN_MODULE`/`SIDE_MODULE`. It diverges on the
unwinding ABI (`-fwasm-exceptions -sSUPPORT_LONGJMP=wasm` absent), on static libs (no lzma, zstd or
OpenSSL), and on `PYEMSCRIPTEN_PLATFORM_VERSION`, which does not exist anywhere in CPython 3.14.2 —
so a stock build cannot advertise the tag even where the flags line up. Also settled:
`-sMAIN_MODULE` is `LINKABLE` and exports all 8191 symbols, so **no custom `EXPORTED_FUNCTIONS`
list is needed** for the Stable ABI. Only `wasmExports` and `wasmMemory` have to be added to
`-sEXPORTED_RUNTIME_METHODS`, and neither is ABI-sensitive.

### The target is on, and `commonTest` runs on it

`wasmJs` is a real target now — a leaf directly under `commonMain`, beside `jvmMain` and
`nativeMain`. It is not a partial bring-up: **all 310 C symbols have `actual`s**, and the whole
shared object-model suite runs against a live interpreter.

```
:python-multiplatform:wasmJsNodeTest    238 tests, 0 failed, 0 skipped
                    desktopTest, unchanged    233 tests, 0 failed, 1 skipped
Embedded CPython version: 3.14.2 ... [Clang 23.0.0git]   on wasm32-emscripten
```

(190 when the target first came up, then 214 with 3 failing, then 234 with the same 3. The three
were always `GCLeakTest`'s; they pass now — see "Lifetimes" below — and the four added with them are
`WasmFinalizationTest`'s.)

Nothing skipped: `PythonTestFixture.available` was true, so `Python3.initialize()` brought CPython
up through an ordinary `@WasmImport` call to `Py_Initialize` — the library's own bring-up path,
rather than a JS-side `Py_InitializeEx` as in `wasm-experiment/`.

Three questions the experiment did not have to answer, and how they came out:

* **`Py_ssize_t` is 32-bit on wasm32**, and `EmbedAPI.kt` types it as `Long`. Fourteen functions are
  affected (`PyList_*`, `PyTuple_*`, `PyDict_Size`, `PySet_Size`, `PyObject_Size/Length`) and
  `EmbedAPI.wasmJs.kt` converts at the boundary. This is the **only** ABI divergence across the
  whole surface — and it is not a compile error, it is a `LinkError` at instantiation, so
  `bindings.kt`'s types are read off `python.wasm`'s own type section rather than transliterated
  from the `expect`s.
* **All 310 symbols really are exported**, checked against the ABI build's 8287 — which confirms the
  `-sMAIN_MODULE`/`LINKABLE` reading above on the surface this library actually needs.
* **The Gradle integration is the one substitution** the design predicted. The compiler emits
  `intrinsics: { memory: new WebAssembly.Memory({ initial: 0 }), … }` into `*.import-object.mjs`;
  a `doFirst` on the test task rewrites that to Emscripten's `wasmMemory` and stages
  `python.mjs`/`python.wasm` beside the bundle. No binary patching, no `-sIMPORTED_MEMORY`.
  *(There are two now. Upcalls need a second one, on the generated entry module — see below.)*

### Lifetimes: closed. The candidate route was measured, and it holds

The stdlib has no finalisation hook — that part was right, and re-checking
`kotlin-stdlib-wasm-js-2.4.20-Beta2.klib` still turns up no `FinalizationRegistry`, no `WeakRef` and
no `Cleaner`. **The conclusion drawn from it was wrong.** The *host* has all three, and a
`JsReference` reaches them. `registerCleaner` is built on one now, and `GCLeakTest`'s three cases
pass:

```
:python-multiplatform:wasmJsNodeTest    238 tests, 0 failed, 0 skipped
                    desktopTest          233 tests, 0 failed, 1 skipped
```

(was 234 / 3 failed — the three were `GCLeakTest`'s, and the four added are `WasmFinalizationTest`.)

**The question that decided it: is `JsReference` a strong reference?** If handing a Kotlin object to
JS pinned it, registering it with a `FinalizationRegistry` would keep it alive forever and there
would be no route at all. Measured, not reasoned:

| | measured |
|---|---|
| 200 Kotlin objects handed over with `toJsReference()`, held from JS only by a `WeakRef` and a registry entry | **`alive 0 / 200`** — not strong |
| `FinalizationRegistry` callbacks for those WasmGC objects | **200 / 200** |
| 200 real `PyObject` wrappers dropped without `close()`, judged by CPython's own refcount | **`2 → 202 → 2`** |

**The second finding cost more than the first: nothing on this platform can observe a collection
without yielding to the host.** Node 26 / V8 14.6, same object, `gc()` at each step:

```
same job, after gc()      WeakRef ALIVE     registry callbacks 0
one microtask             WeakRef ALIVE     registry callbacks 0
two microtasks            WeakRef ALIVE     registry callbacks 0
one macrotask             WeakRef CLEARED   registry callbacks 200
```

A `WeakRef` keeps its target alive for the job that created it, and the registry callback is
delivered as a task. `FinalizationRegistry.prototype.cleanupSome()` would have made it synchronous
and **has been removed from V8** — `--harmony-weak-refs-with-cleanup-some` is rejected as an
unrecognised flag, from the command line and from `v8.setFlagsFromString` alike. So the three
`GCLeakTest` cases could not have passed no matter what the mechanism was: a synchronous test body
is one job, and a job that has not ended sees nothing. That is why they read as "wasm has no
finalisation hook" — the hook was the first problem, and the harness was the second.

`commonTest` therefore drives them through `collectorTest`, which is a blocking loop on every target
whose finalisation runs on a thread and a chain of host turns on `wasmJs`. **No assertion changed.**
`kotlin-test`'s wasm adapter awaits a returned `Promise` (`TeamcityAdapterWithPromiseSupport`),
confirmed by a control that returned `Promise.reject` and duly failed; `WasmFinalizationTest` keeps a
standing `@AfterTest` control so a regression cannot silently turn those assertions into ones nobody
runs. `forceGC()` is `globalThis.gc()`, with `--expose-gc` added to the test task's `nodeJsArgs`.

Two consequences worth carrying forward:

- **`PyAutoCloseable.wasmJs.kt` is the only `js(…)` in `wasmJsMain`, and it has to be.** Every other
  declaration this target makes is a `@WasmImport`, which carries primitives only — and what must
  cross here is a *reference* to the object whose reachability is the question. The cost is confined
  to construction: `registerCleaner` + `close()` is **88.6 ns**, of which the two `toJsReference()`
  crossings are **44.1 ns** each; nothing on the C API call path touches it.
- **The callback cannot arrive inside a Python call.** JS tasks run only after the stack unwinds and
  every call into CPython here is synchronous, so the decref never re-enters the interpreter from
  within another call. That is §1's hazard, and this target does not have it.

`WasmCleanerStats` (`registered`, `released`, `finalized`, `outstanding`) is the observability half,
and stays useful on a host with no `FinalizationRegistry`, where `outstanding` is a leak count.

Kotlin/Native once had a `wasm32` target that could have shared CPython's linear memory; it was
deprecated in 1.8.20 and removed in 1.9.20. That history no longer costs anything — `@WasmImport`
plus an imported memory reaches the same place.

### Upcalls: closed. CPython calls Kotlin, and cycles are collected

`ProxyTypeFactory` builds a real `PyType_FromSpec` heap type with `tp_traverse`, `tp_clear` and
`tp_dealloc` filled by Kotlin `@WasmExport`s installed into CPython's own
`__indirect_function_table`. `wasmJsTest/.../WasmCycleCollectionTest` runs the same two cases
`desktopTest/CycleCollectionTest` does, and both pass: a Kotlin↔Python cycle is broken by
`gc.collect()`, and 100 proxies that die *without* a cycle release their `HandleTable` entries and
leave the heap type's own refcount exactly where it started.

The measurement this section already had — 3.1 ns through `call_indirect`, against 10.9 ns for the
`addFunction`-and-JS-closure route §5 specified — was the mechanism. What this pass added was the
wiring, and it turned up three constraints worth carrying forward:

- **`@WasmExport` is honoured only in the compilation that produces the `.wasm`.** Measured, not
  inferred: the identical annotation on the identical function exports from `wasmJsTest` and does
  not from `wasmJsMain`, whose klib is linked in — the test binary's export section came out with
  `startUnitTests` in it and nothing else. **A library cannot export its own trampolines.** The
  executable module must declare three delegating lines; `wasmJsTest/.../ProxyTypeExports.kt` was
  that file, kept in the suite precisely so the tests exercise the path an application takes.
  ~~Generating it from `python-multiplatform-gradle-plugin` is the obvious next step and is not
  done.~~ **Closed 2026-08-18 (`112b54d0`).** `GenerateWasmProxyExportsTask`
  (`python-multiplatform-gradle-plugin/src/main/kotlin/python/multiplatform/gradle/GenerateWasmProxyExportsTask.kt`)
  generates the three delegating lines from a slot list; `PythonBindingsPlugin.kt:710` registers it
  and wires its output into `wasmJsMain` automatically. `ProxyTypeExports.kt` was deleted in the
  same commit — see §14b item 8.
- **Registration needs the Kotlin instance's raw exports, and only the generated entry module has
  them.** `cpython.mjs` cannot fetch them: it is imported *by* Kotlin's import object, so importing
  the entry module back would be an ES cycle across a top-level await. So `build.gradle.kts` appends
  `pmpSetKotlinExports(exports)` to the entry module. This target now has **two** generated-file
  substitutions, not the one this section used to advertise.
- **Kotlin/Wasm has no `call_indirect`,** so calling a C function *pointer* — `visitproc`, `tp_free`
  — goes through `WebAssembly.Table.get` in JS. Both are cold (a cyclic collection, a deallocation);
  neither the downcall path nor CPython's path *into* Kotlin touches JavaScript.

Also recorded because it cost time: `Module.UTF8ToString` is not available. This build exports
`wasmExports` and `wasmMemory` and nothing else from the Emscripten runtime, deliberately, so any
other helper fails as `undefined is not a function` inside a wasm import and arrives in Kotlin as an
empty `JsException`.

### Interning: taken, and the answer here is not Android's

All 59 string-argument positions across 49 `actual`s go through `Wasm.internedUtf8` or
`Wasm.scratchUtf8` now, with the per-argument judgement identical to `desktopMain`'s and
`androidMain`'s on all 59. Measured at 200 000 iterations:

| ns per argument | 7-char name | 54-char name |
|---|---|---|
| `malloc` + `encodeToByteArray` + copy + `free` (what it did) | **82.7** | |
| `malloc` + `free` alone | 13.0 | |
| `encodeToByteArray` alone | 59.2 | |
| `Wasm.scratchUtf8` | **30.6** | **157.0** |
| `Wasm.internedUtf8` (hit) | **22.2** | **26.4** |

`PyObject_GetAttrString` through the `actual`, both rows back to back in one run so that only the
marshalling differs: **258.0 → 229.2 ns**.

**The rule transferred; the reasoning did not, and neither did the size of the win.** Three
findings:

- The expensive part was the **intermediate WasmGC `ByteArray`**, not the encode. Writing UTF-8
  straight into linear memory is what takes 82.7 ns to 30.6. Shared memory is what makes that
  possible — the destination is CPython's own heap, so there is no staging buffer — which is the
  *opposite* of the original design note's claim that shared memory removes a string copy. It
  removes the allocation.
- **Interning's whole value is in the string length.** For a 7-character name it beats scratch by
  8 ns, nothing like Android's 15x (2238 → 148). For a 54-character one it beats it by **6x**
  (157.0 → 26.4), because a cache hit is flat in the length and an encode is linear. Reporting only
  the short case — which is what the first measurement here did — would have argued interning is
  barely worth having.
- Negative result worth keeping: `BenchmarkTest`'s shared `PyObject_GetAttrString` row **did not
  move** across this change (376.6 → 384.0 ns). That is two whole suite runs compared against a
  ~29 ns per-call difference; it is not a usable A/B at this size, in either direction. The in-run
  rows are the measurement.

### `Py_ssize_t`: two guards, because the two halves fail differently

The declaration half — `bindings.kt` against `python.wasm` — is derived now rather than remembered.
`./gradlew :python-multiplatform:verifyWasmAbiSignatures` parses the wasm binary's type, import,
function and export sections and compares all 316 `@WasmImport` declarations against it, plus the 3
glue functions against `cpython.mjs`. It runs before `wasmJsNodeTest`. Confirmed by deliberately
mis-declaring one and watching it fail with the function named:

```
PyList_Size: bindings.kt declares (i32) -> (i64), python.wasm has (i32) -> (i32)
```

Same shape as `generateDesktopReachabilityMetadata` (§7): read the artefact that decides, every
build, never a checked-in copy of the answer. The task also asserts that every `external fun` in the
file was parsed — the first version required an explicit return type and so silently skipped the
three `Unit`-returning declarations, which is §2's "green test measuring its own scope" again.

The conversion half links cleanly and is silently wrong, so it needed a different guard.
`EmbedAPI.wasmJs.kt` names the two directions instead of casting inline — `Int.pySsizeToLong()`
sign-extends, because -1 is the error return and the unsigned widening that is correct for pointers
here would turn it into 4294967295; `Long.toPySsize()` range-checks rather than truncating, so
`PyList_New(0x1_0000_0000)` fails instead of quietly becoming `PyList_New(0)`.
`WasmPySsizeTBoundaryTest` covers both directions and the whole `Int` range.

### The sample runs in a real browser, and the gap that stopped it was the library's

`c327bb54` turned on `:sample`'s wasmJs target. It compiled, and the library's own wasm suite covers
the mechanism, but **the app had never come up in a browser** — and the report that closed that
round said only that in-browser confirmation was blocked on "a Gradle staging gap that is identified
and is not a library defect". Neither half of that sentence survived being checked. The gap was
recorded nowhere in the repository, and it *is* a library defect.

It is running now. Chromium 150 (headless, software WebGL), served over HTTP from
`sample/build/dist/wasmJs/developmentExecutable`, console verbatim:

```
INFO: Python initialized successfully!
runtime : 3.14.2  ·  sys.platform=emscripten  ·  Web emscripten (wasm32) / Wasm Kotlin/Wasm
eval    : sum(kotlin_numbers) * 2 -> int: 56
table   : 42 entries, 4 classes, from io_github_thisisthepy_sample
upcall  : handle 4294967325 -> 0  ·  with args -> presses x3 = 0
proxies : installed over PyCFunction (self as dispatcher): 519 lines, 1 proxy classes
Greeter('Kotlin').greet(2)  ->  hello Kotlin! hello Kotlin!
Greeter.PUNCTUATION         ->  !
await   : skipped on wasmJs -- import asyncio traps this target, see ProxyDemo.wasmJs.kt
```

Sections 1-6, all of them, in the browser. Section 7 stays gated, untouched: `asyncio.run` still
wants `socketpair` → `ws`. The DOM after the run holds one `<canvas width="714" height="431">` and
skiko logs `GPU stall due to ReadPixels`, so **Compose rendered as well** — CPython, Kotlin/Wasm and
skiko are three wasm modules on one page.

**Four things were missing, and the first is what the failure looked like:**

```
Module not found: Error: Can't resolve './cpython.mjs' in
  '<root>/build/wasm/packages/PythonMultiplatformMobile-sample/kotlin'
```

1. **`cpython.mjs` does not reach a consumer's webpack context.** It is a resource of
   `python-multiplatform`'s `wasmJsMain`, and the *generated import object* of anything that links
   the library carries `import * as ... from './cpython.mjs'` — because `bindings.kt` declares
   `@WasmImport(MODULE, ...)` against it. Nothing propagates it. Checked rather than assumed: the
   library's wasmJs klib (`build/classes/kotlin/wasmJs/main/default/resources`) carries no
   `cpython.mjs`, so there is no artefact a consumer could unpack it from either.
2. **`python.mjs`/`python.wasm` are staged only next to the *library's own* test bundle**, by a
   `doFirst` on `KotlinJsTest`.
3. **The stdlib had no browser route at all.** `cpython.mjs` reached it through NODEFS mounted over
   the real build directory, plus `import fs from "node:fs"` — which in a browser bundle is not a
   branch that is never taken but a webpack resolution failure.
4. **The `@WasmExport` handoff was inserted in the wrong place for an executable.** See below; that
   one is a genuine bug, not a missing copy step.

**This is the library's, on the measure that decides it: an external consumer meets all four
identically.** None is specific to the sample, and none is fixable from the consumer side without
knowing three library internals — the glue file's existence, the `intrinsics.memory` placeholder,
and the `wasmInstance.exports` handoff. The split as implemented:

| | where |
|---|---|
| browser-capable `cpython.mjs` (env branch, webpackIgnore imports, stdlib zip into MEMFS) | library source |
| staging `cpython.mjs` + `python.mjs` + `python.wasm` + `python3.14.zip` + generated config | library, `stageWasmBrowserRuntime` |
| adding that directory to `wasmJsMain.resources`, patching the compile-sync output | `:sample` |

The browser stdlib route is CPython's own: `Tools/wasm/emscripten/web_example/python.worker.mjs`
fetches `python3.<minor>.zip` into MEMFS at `/lib/python3<minor>.zip` under `addRunDependency`, and
sets **no `thisProgram`**, so `sys.prefix` stays `/`. `cpython.mjs` does the same, reading the
version out of the running interpreter rather than from a string that would then need keeping in
step.

### Appending the upcall handoff works only for a test bundle

The one real bug in that list, and the browser is what surfaced it. The entry-module patch appended

```js
import { pmpSetKotlinExports } from './cpython.mjs';
pmpSetKotlinExports(exports);
```

to the end of the generated entry module. That is correct for the library's own test bundle, whose
entry module ends at `setWasmExports(wasmExports)` — its runner calls `startUnitTests` later, from
outside. An **executable** bundle does not end there. `binaries.executable()` makes the entry module
finish with `exports._start()`, which *is* Kotlin `main()`, so the handoff ran after the whole
application had already executed:

```
upcall  : PyException: name '_pm_resolve' is not defined
proxies : IllegalStateException: could not register wasm export 'pmp_invoke' as an upcall entry
          point (pmpRegisterUpcall returned -1)
PyException: No module named 'org'
```

`-1` is `kotlinExports === null`. Sections 1-4 passed in that same run, which is why nothing about
the diagnosis was visible from the code: the memory substitution, the interpreter, the C API and the
generated table were all fine. The fix inserts the call *before* `exports._start()`; the `import`
stays at the top of the file, where hoisting already put it.

Worth stating as a pattern rather than as one fix: **a test bundle and an executable bundle differ in
the generated entry module, and every wasm verification this repo had was of the first kind.**

### The JSPI warning: the premise holds, the consequence did not fire here

`wasmJsMain/README.md` warns that deleting `WebAssembly.promising` and `WebAssembly.Suspending`
mutates a host intrinsic globally, and that on a browser page shared with another wasm module using
JSPI this would break that module. The browser run is the first chance to check it. Measured on both
sides, same browser:

| | `typeof WebAssembly.promising` |
|---|---|
| a page that does not load the app | `function` |
| the app's page, 20 s after load | `undefined` |

So the delete is **not** a no-op here — Chromium 150 ships JSPI, and the property really is gone
page-wide. What did *not* happen is the breakage: Compose came up on the same page (`canvases=1`),
skiko rendered, and Kotlin/Wasm ran, because none of those three uses JSPI. The warning stands as
written — it is about a page that loads a JSPI consumer, and this page does not — but its cost is now
bounded by observation rather than by argument.

### What is still open

- ~~**The wiring is not in `python-multiplatform-gradle-plugin`, so an external consumer still meets
  the hole.**~~ **Closed, and this bullet outlived it.** `WasmBrowserRuntimeStaging.kt` is in the
  plugin and compiled, and "The plugin wiring is closed, and it needed the runtime to become a Maven
  artefact first" below records the verification against an external consumer. The bullet was never
  struck when that landed — the same failure this document keeps finding elsewhere, an item read as
  open because nobody went back to close it. The original text follows for the history.

  `:sample` carries ~50 lines that are a copy of `patchKotlinWasmOutputForCPython`,
  because one Gradle build script's functions are not visible to another project's build script.
  Moving it into the plugin needs the staged runtime to be a *published artefact* first, and the
  wasm CPython build is published nowhere — it is a local directory named by `-PwasmPythonDir`. That
  is the same shape as §15e item 4 (`StagePythonHomeTask`), which solved the desktop version of
  exactly this problem, and it is the obvious next step.
- ~~**`wasmJsBrowserTest` (karma) is not wired.**~~ **Wired, and it catches three of the four.**
  See §10c below for what it does and does not guard, and for the one item that had to be closed a
  different way.
- The upcall line reads `presses x3 = 0`, which is what that code prints for a freshly constructed
  counter; it was not cross-checked against a desktop run in this pass.

### 10c. `wasmJsBrowserTest`, and the one defect a browser test cannot express

**Ten tests, 4.5 s, Chromium 150 headless, and a build failure when the browser route breaks.**
`wasmJs` declares `browser { testTask { useKarma { useChromeHeadless() } } }` alongside `nodejs()`,
and the task runs a deliberately small filter rather than the suite twice:

| | why it is in the browser run |
|---|---|
| `python.multiplatform.browser.WasmBrowserRuntimeTest` (6) | the claims only a browser can make |
| `python.multiplatform.ffi.WasmSelectorsImportTest` (4) | JSPI deletion is a *host* decision, and its regression mode is `abort()` rather than a red test |

The other ~344 already run under Node against the same Kotlin and the same interpreter; running them
again would buy nothing but time. What the browser adds is webpack, HTTP and no filesystem, so the
new class asserts exactly those: that the host is a browser at all (so nothing below can pass
vacuously), that `cpython.mjs` survives bundling, that `sys.prefix` is `/` and `json.__file__` is
inside `/lib/python314.zip` (the MEMFS route, which has no Node equivalent), that a `PyObject` round
trip still reads Emscripten's memory through the *bundled* import object, and that
`WebAssembly.promising` is gone while `select.poll()` still returns.

Three pieces of plumbing were needed, and each is a browser fact:

- **`webpackCopy`.** `cpython.mjs` loads the glue with `import(/* webpackIgnore */ './python.mjs')`,
  so the *browser* resolves that specifier — relative to the bundle, which karma-webpack writes to a
  temp directory with a fresh name every run. A fixed URL cannot work; the files have to be copied
  next to the bundle, which is what `kotlin-web-helpers`' `webpackCopy` hook does.
- **A proxy for the stdlib zip.** `STDLIB_ZIP_URL` is document-relative because in a real
  distribution the zip sits next to `index.html`. karma's document is `/context.html` at the server
  root, so the proxy is what makes karma's page look like that distribution.
- **`CHROME_BIN`, resolved in Gradle from one list**, with a skip-and-say-so when nothing is found —
  the same contract a missing CPython Emscripten build already has. On this machine the browser is
  Naver Whale, which reports `HeadlessChrome/150.0.0.0`; that is what "Chromium 150" above was.

**Verified by breaking it, not by watching it pass.** Three reverts, three failures:

| reverted | how it failed |
|---|---|
| the dynamic `node:fs` import → static, as it was before §10 | `UnhandledSchemeError: Reading from "node:fs" is not handled by plugins` — webpack, before a test runs |
| the staged stdlib zip | `404: /python3.14.zip`, zero tests reported: bring-up dies inside `addRunDependency` |
| the `pmpSetKotlinExports` handoff | `IllegalStateException: ... pmpRegisterUpcall returned -1` — §10's own message |

**And one that did not fail, which is the finding.** Reverting the entry-module *ordering* fix — the
one real bug §10 found — changed nothing: all ten tests stayed green. The reason is structural
rather than a gap in the filter. A **test** bundle's generated entry module contains no
`exports._start()` at all (`grep -c _start` → 0), so the fixed and reverted forms of
`handedOffEntryModule` produce a byte-identical file. The bug is only expressible in an
**executable** bundle, and the library builds none.

So that item is closed by a postcondition instead: both `patchKotlinWasmOutputForCPython` and
`:sample`'s copy now refuse to write an entry module in which the handoff follows `_start()`.
Checked the same way — reverting `:sample`'s placement fails
`:sample:wasmJsBrowserDevelopmentWebpack` with the ordering message, on the real executable bundle.

~~**Not added to `.github/workflows/wasm.yml`, and the reason applies to the job already there.**~~
**Superseded — see §10d.** The diagnosis was right (the runner has no CPython Emscripten build, so
every wasm test task's `onlyIf` skipped and `wasmJsNodeTest` reported green having run zero tests)
but its conclusion — wait for the build to be published — turned out to be one measurement away from
unnecessary. The five staged files are enough; NODEFS was never carrying the standard library on
purpose.

### The plugin wiring is closed, and it needed the runtime to become a Maven artefact first

The item above used to read "the wiring is not in `python-multiplatform-gradle-plugin`, so an
external consumer still meets the hole," gated on the runtime becoming a *published artefact* —
the same shape as §15e item 4 (`StagePythonHomeTask`), which solved the desktop version of exactly
this problem. Both halves are done now.

**(b) — is the wasm CPython build publishable?** Checked rather than assumed. `build-cpython-abi.sh`
hand-patches CPython 3.14.2's own source (`Lib/sysconfig/__init__.py`, the generated `Makefile`) to
carry `-fwasm-exceptions -sSUPPORT_LONGJMP=wasm` and a `PYEMSCRIPTEN_PLATFORM_VERSION` that does not
exist upstream, plus `wasmExports`/`wasmMemory` in `EXPORTED_RUNTIME_METHODS` — the one addition
that exists *only* so this library's `@WasmImport` declarations have something to bind to. PEP 783's
`pyemscripten_2026_0` tag is real, accepted, and PyPI-validated (confirmed via web search), and
Pyodide's own build tooling knows how to produce wheels for it — but nothing distributes a
`python.wasm` with the JS-reachable exports this library needs, and no distributor has a reason to.
So there is no URL for a `StagePythonHomeTask`-style download to point at; the desktop pattern does
not transfer as-is.

The candidate the earlier pass named — bundle it in a library artefact — does transfer, and the
size argument favours it more cleanly than it did for desktop. Desktop's `PythonHomeStaging` chose
*not* to embed specifically because one artefact would carry all four platforms' payload for every
consumer (87.7 MB of `libpython`, of which any one consumer uses a quarter) — the defect §15d had
already found and fixed once. wasm has exactly one platform, so there is no multiplication: a
wasmJs consumer needs precisely this payload, once. Measured rather than guessed:
`stageWasmBrowserRuntime`'s output directory is 13 MB (`python.wasm` 9.2 MB, `python3.14.zip`
3.6 MB, `python.mjs` 0.56 MB, the two `cpython*.mjs` glue files); zipped, 7.0 MB — smaller than
§15d's post-fix 23 MB Android AAR.

`python-multiplatform/build.gradle.kts` now zips that directory (`wasmBrowserRuntimeZip`) and
publishes it as its own artefact ID, `io.github.thisisthepy:python-multiplatform-wasm-runtime`, at
the *library's* version (not the plugin's — see the coordinate-derivation comment in
`generateCoordinates`, `python-multiplatform-gradle-plugin/build.gradle.kts`), so resolving the
library and resolving the runtime it needs can never drift into two hand-copied version literals.
`./gradlew :python-multiplatform:publishWasmRuntimePublicationToMavenLocal` (folded into the
existing `publishAllToMavenLocal`) lands it at
`~/.m2/repository/io/github/thisisthepy/python-multiplatform-wasm-runtime/`.

**(a) — the plugin.** `python-multiplatform-gradle-plugin` gained
`WasmBrowserRuntimeStaging.kt`: `StageWasmBrowserRuntimeTask` resolves the runtime coordinate from
a *detached* configuration and unzips it, and `PythonBindingsPlugin` registers it unconditionally
but only resolves it — no network access, no `mavenLocal` lookup — when a `wasmJsProcessResources`
or `wasmJs*Webpack` task actually exists to depend on it (`tasks.matching`, a live view, the same
trick `isBindingKspConfiguration` already relies on). The three substitutions `:sample` used to
hand-copy — `wasmJsMain.resources.srcDir`, the `intrinsics.memory` placeholder, the upcall
handoff — are all here now, matched by task *name* rather than by Kotlin Gradle Plugin task type
(`org.gradle.language.jvm.tasks.ProcessResources` is Gradle's own class, not the KGP's, so casting
`wasmJsProcessResources` to it does not add that dependency) — consistent with why `TEST_WORD`
matches KSP configurations by name in the first place: this plugin's `kotlin-dsl` classpath does
not carry the Kotlin Multiplatform Gradle plugin at all.

One correction made against real friction rather than assumed up front: the patch's `modulePrefix`
(`"${rootProject.name}-${project.name}"`, copied from `:sample`, which is a subproject) is wrong for
a **single-module** consumer, where the `wasmJs()` target sits on the root project itself — Kotlin
does not double the name there (`consumer-plugin-android.mjs`, not
`consumer-plugin-android-consumer-plugin-android.mjs`). First attempt failed with "No
*.import-object.mjs in .../consumer-plugin-android-consumer-plugin-android/kotlin"; fixed by
deriving the name from `project == project.rootProject` instead of assuming the subproject shape.

A second correction, found the same way: a consumer whose reachable wasmJs code never calls into
`python-multiplatform` at all has its `@WasmImport` declarations dead-code-eliminated along with the
`./cpython.mjs` import that names them — `consumer-plugin-android`'s KSP-generated `FunctionTable`
wraps plain Kotlin functions with no path into `bindings.kt`, and hit exactly this. The prior copies
of this patch (library, `:sample`) throw when that import is missing, because both always reach the
FFI. The plugin's copy logs and returns instead — nothing to patch is a legitimate outcome for a
generic external consumer, not a broken one.

**Verified against `/Volumes/macMini/consumer-plugin-android`, an external consumer that resolves
everything by Maven coordinates** (`io.github.thisisthepy:python-multiplatform:3.14.7-alpha01`, the
plugin `3.13.0`) **with no `-PwasmPythonDir` of its own**:

- With no reachable Python call (the baseline fixture), `wasmJsBrowserDevelopmentWebpack` succeeds,
  patch skipped, logged as such.
- With one added (`fun main() { ... Python3::initialize ... }`, forcing DCE to retain the FFI
  surface), the same task's log reads:
  ```
  Pointed consumer-plugin-android.import-object.mjs's intrinsics.memory at Emscripten's wasmMemory
  Handed consumer-plugin-android.mjs's wasm exports to cpython.mjs for upcall registration
  ```
  — both substitutions the library's own build performs on itself, now performed by the plugin on a
  project three artefact resolutions away from this repository.
- `wasmJsBrowserDistribution` carries the full runtime — `python.wasm`, `python.mjs`,
  `python3.14.zip`, `cpython.mjs`, `cpython-config.mjs` — next to the consumer's own bundle, staged
  there by nothing the consumer wrote.

Not verified: actually running the resulting bundle in a browser. In scope for this pass was
compilation only (desktop and wasmJs; no device, no simulator); §10's own browser run above is still
the only execution evidence this repository has.

Repository baselines unchanged by this — desktop 360/0/1, `ksp-fixtures` 64/0, wasmJs 344/0/0, all
re-measured after the change rather than assumed carried over.

### 10d. The wasm CI job ran zero tests and reported green, and the fix was one measurement away

**The job did nothing, and said nothing about doing nothing.** `.github/workflows/wasm.yml` asked a
runner for `wasmJsNodeTest`; the runner has no CPython Emscripten build; the task's `onlyIf` skipped
it; the job went green. Reproduced before changing anything —
`./gradlew :python-multiplatform:wasmJsNodeTest -PwasmPythonDir=/nonexistent/cpython-emscripten` →
`BUILD SUCCESSFUL`, exit 0, `build/test-results` not even created. (On this machine the *default*
path exists, so reproducing the runner needs the property pointed somewhere absent; running "without
`-PwasmPythonDir`" reproduces the developer, not the runner.)

**What the guard was actually reading, and whether the published zip could satisfy it.** Three
guards, three markers, all under `wasmPythonDir`: the `KotlinJsTest` tasks want `python.mjs`,
`verifyWasmAbiSignatures` and `stageWasmBrowserRuntime` want `python.wasm`. The file *list* in
`python-multiplatform-wasm-runtime` matches what the test staging copies exactly — `python.mjs`,
`python.wasm`, `python3.14.zip`, plus the two glue modules. So the zip looked sufficient. It was not,
and the reason is the finding:

    ./gradlew :python-multiplatform:wasmJsNodeTest -PwasmPythonDir=<unpacked zip>
    → 344 tests, 344 failed: "CPython could not be initialized in this environment"
    → node, directly: Fatal Python error: Failed to import encodings module

**Node was never getting the standard library from anything an artefact could carry.** Printed from
a run against the real build directory:

    sys.path = ['/lib/python314.zip',
                '/Volumes/macMini/wasm-build/cpython314-abi/Lib',           <-- the source checkout
                '.../cross-build/wasm32-emscripten/build/python/build/lib.emscripten-...']

Entry 0 is a file **nothing had ever created** — the browser branch writes it into MEMFS, the Node
branch did not — and the interpreter was silently falling through to entry 1, the CPython *source
checkout*, which is neither in the build directory, nor in the zip, nor anywhere a consumer could
get it. Every wasm test in this repository had been depending on it.

So `cpython.mjs` now installs the staged zip on both hosts (`installStdlibZip`, read with `fs` under
Node and `fetch` in a browser), and sets `PYTHONHOME=/` — which both answers getpath (no more
`Could not find platform independent libraries <prefix>` when `PYTHON_DIR` is an unpacked artefact
with no `python.sh`) and stops a host shell's `PYTHONHOME`, plausibly pointing at a *desktop* CPython,
from aiming the wasm interpreter at the wrong ABI. Node and browser now resolve the stdlib
identically: `sys.prefix == '/'`, `json.__file__` inside `/lib/python314.zip`.

**Result: the published artefact alone runs the suite.** Unpack
`python-multiplatform-wasm-runtime-3.14.7-alpha01.zip` (7.0 MB, mavenLocal) into an empty directory,
point `-PwasmPythonDir` at it, and `wasmJsNodeTest` + `wasmJsBrowserTest` are **344/0/0 and 10/0/0** —
the recorded baselines, from five files and no CPython checkout. Desktop re-measured at 360/0/1.

**And a skip can no longer be reported as success.** `-PrequireWasmRuntime=true` (`wasmRuntimePresent` in
`python-multiplatform/build.gradle.kts`) turns every one of those `onlyIf` skips into a failure
naming the path it looked for — including `wasmJsBrowserTest`'s *other* skip, a missing
Chromium-family browser, which is the same defect by a different route. The message is logged before
it is thrown because Gradle wraps an exception raised from an `onlyIf` predicate and reports only
`Could not evaluate spec for 'Task satisfies onlyIf spec'` — checked, not assumed: the first version
of this printed exactly that and nothing else, which would have traded a green tick that says nothing
for a red one that says nothing.

**What is still open: the runner has nowhere to download the zip from.** It has never been pushed to
a remote repository — `publishWasmRuntimePublicationToMavenLocal` is the only publish that has ever
run — and the two obvious candidates are both circular: a runner cannot `publishToMavenLocal` the
zip, nor depend on `wasmBrowserRuntimeZip`, because building it needs the very CPython Emscripten
directory the runner lacks. Nor can the runner build one: `build-cpython-abi.sh` needs emsdk 5.0.3, a
CPython 3.14.2 checkout and two hand-patches, and no upstream ships a `python.wasm` with
`wasmExports`/`wasmMemory` exported.

The workflow is therefore split, and the split is the honest part:

| job | runs | claims |
|---|---|---|
| `compile` | always | `compileKotlinWasmJs` + `wasmJsTestClasses`. Real coverage a runner can deliver, and it is *named* "no interpreter" |
| `test` | only when the repository variable `WASM_RUNTIME_URL` is set | the real suite, with `-PrequireWasmRuntime=true` and a floor assertion on the result XML counts (344 / 10) |

When the variable is unset the `test` job does not run at all — grey in the checks list, which is a
different claim from green — and `compile` writes a step summary plus a `::warning` saying which
coverage is absent and what to set. **One upload of the 7.0 MB zip to a release asset closes it**;
until then nothing in this workflow reports success over an empty run.

Not verified: the workflow on a real runner (no CI run is possible from here) and the
missing-browser branch of `-PrequireWasmRuntime=true` (this machine has Whale, and the probe list finds
it). Everything else above was reproduced locally against a pristine unpacked zip, including the
fetch-and-unpack step run verbatim out of the YAML.

## 11. Build wiring

**Closed.** The dependencies are declared now, and the graph was read rather than assumed:
`./gradlew :python-multiplatform:connectedDebugAndroidTest --dry-run` plans 93 tasks, and every
staging step is in it, in the right order:

```
16, 20  linkMultiplatform_python3.14DebugSharedAndroidNativeArm64 / X64   (before preBuild at 23)
49      copyAndroidPythonAssets        (before generateDebugAndroidTestAssets 52, merge…Assets 58)
76, 81  linkAndroidNativeArm64 / X64
82      copyAndroidPythonBinaries      (before mergeDebugJniLibFolders 83, …AndroidTest… 86)
```

`:sample:assembleDebug --dry-run` pulls the same four in, so the app path is covered too, not
just the instrumented tests. The manual pre-step this section used to prescribe is no longer
needed.

Three edges do the work, all of them lazy-safe:

- `copyAndroidPythonBinaries` declares `dependsOn(linkAndroidNativeArm64, linkAndroidNativeX64,
  downloadAllPythonBuilds)`
- `tasks.configureEach` attaches it to every `merge*JniLibFolders` / `merge*NativeLibs`, and
  attaches `copyAndroidPythonAssets` to every task whose name ends in `Assets`. This is
  `configureEach`, not the `whenTaskAdded` that used to miss tasks registered later — which is
  how the stdlib went unpackaged and `Py_Initialize()` aborted.
- `preBuild.dependsOn(linkTaskProvider)` for each shared-library binary, so the `.so` is relinked
  and staged into `build/android/<type>/jniLibs/<abi>/` before AGP looks there.

The configuration-time `copy {}` that staged the *previous* build's library was fixed earlier by
moving it into `doLast`; that fix is still in place and is the only `copy {}` in the script that
is not already inside a task action (checked: lines 201, 245, 281 and 475 are all `doLast`).

**One hardcoded count of the same family was removed.** `jni_onload.def` still carried
`#define NUM_METHODS 127` after `RegisterNatives` had been switched to `sizeof(methods) /
sizeof(methods[0])`. It was dead, but it is exactly the constant that once drifted out of step
with the table and silently bound a prefix of it, so it is gone and a comment says why.

No other drifting constant or configuration-time side effect was found in
`python-multiplatform/build.gradle.kts` or `sample/build.gradle.kts`; there are no
`whenTaskAdded` uses left in the repository.

## 11b. Android does not run the object-model tests

**Closed.** `androidInstrumentedTest` depends on `commonTest` and both emulators run the full
suite: 176 tests each, where discovery used to be 19. ~~Eight failures remain, tracked in §2.~~
**Those eight are fixed too** — §2's own closing measurement, reached after this section's, has
both emulators at 213 tests with zero failures.

Wiring it up immediately paid for itself — the suite died on its 2nd test, then its 12th, and the
two defects behind that (an unpackaged stdlib and a hardcoded `RegisterNatives` count) had been
invisible because nothing on Android had ever executed those tests.

**Was:** the largest hole in the suite.

`commonTest` holds 111 tests over `PyObject`, `PyDict`, `PyList`, conversion, exceptions and
refcounting. They reach desktop and iOS through the source-set graph:

    jvmTest.dependsOn(commonTest)
    desktopTest.dependsOn(jvmTest)

`androidInstrumentedTest` has no such edge, so **none of the 111 run on Android**. Its entire
coverage is 19 instrumented tests — 14 of JNI wiring and benchmarks, 5 of `AssembledApiTest`.

That is the wrong platform to under-test. §2 was exactly a case of the object model being broken
on Android alone — the production API crashed on the first `Python3.exec` — and the only thing
that caught it was an acceptance test written by hand for the purpose. The 111 would very likely
have caught it earlier.

They cannot be `androidUnitTest`: the native library is not loadable there, so they have to run
instrumented. Whether `kotlin.test` annotations are discovered by the AndroidJUnit4 runner, and
how the interpreter gets initialised on device, are the two things to establish.

**Expect failures when this lands.** These tests have never run on Android; whatever they report
is the actual state of the Android object model, and that is the point of doing it.

## 12. Smaller known items

- ~~**Download integrity**: python.org's Sigstore bundles are not checked.~~ **Done for every
  archive that publishes one.** `-PverifyPythonSignatures=true` verifies the sibling
  `<archive>.sigstore` in-process with `dev.sigstore:sigstore-java`, pinning the release manager's
  Fulcio SAN and OIDC issuer — both Android tarballs, and from 3.15 the iOS XCframework. The claim
  this entry rested on, that Sigstore verification needs an external CLI and is therefore
  unreasonable inside Gradle, was false; `docs/platforms/python-version-acquisition.md` §5 is corrected.
  Three things worth keeping:
  - **The identity is a version→signer map, not a constant.** 3.14/3.15 are `hugo@python.org` via
    GitHub; 3.12/3.13 are `thomas@python.org` via Google. A constant would keep passing on the
    series it was written for and invite loosening on any other. An unrecorded series fails hard
    rather than skipping.
  - **Two negative controls were run, not just the happy path.** Flipping one base64 character in
    the bundle's signature gave `Artifact signature was not valid`; pointing the map at the wrong
    release manager gave `No provided certificate identities matched values in certificate`. Both
    failed the build with a non-zero exit. Without the identity pin the second case would pass,
    which is the whole reason the pin is there.
  - **It is opt-in, and the SHA-256 lockfile is untouched.** `sigstore-java` drags in
    grpc-netty-shaded/protobuf/bouncycastle and needs the network for its TUF root, so a default
    build resolves none of it (measured: zero mentions, exit 0). The two gates prove different
    things — lockfile "the bytes we reviewed", Sigstore "the bytes the release manager signed" —
    and only the lockfile works offline or covers every source.

  Still uncovered, for reasons not fixable here: **python-build-standalone** publishes no sibling
  signature at all (853 assets, `SHA256SUMS` the only non-archive; provenance lives in GitHub's
  attestations API, keyed by digest rather than filename and rate-limited to 60/hour unauthenticated),
  and **BeeWare's Python-Apple-support** (iOS ≤ 3.14) publishes five tar.gz assets and nothing
  else — no checksums, no signatures, no attestations. Desktop is still checked against the
  release's own `SHA256SUMS`; for BeeWare the lockfile pin is the only instrument that exists.
  The lockfile stays keyed by version *and* flavour, so a `-freethreaded` or a 3.15 archive is a
  separate entry and cannot be silently accepted under an existing key.
- **Fixed in passing: the Android download URL 404'd on every pre-release.** It built
  `ftp/python/$version/`, but python.org publishes `3.15.0rc1` under `3.15.0/`. It stayed
  invisible because the archives were already in the download directory so the fetch was skipped;
  adding the `.sigstore` fetch surfaced it on the first run. `pythonOrgReleaseDir`, which the iOS
  task already used, is now declared above both tasks and used by both.
- ~~**`PyList.subList`** returns a copy, not a live view.~~ **Stale — it returns `PySubList`,
  which delegates `get`/`set`/`add`/`removeAt` to the backing list, i.e. it is a live view.**
  **`pyObjectToNative`**'s fallback branch is still not fully native; the open question is now
  written out at the branch itself (three candidate answers, and what to measure first).
- ~~**~50 `TODO` markers** remain in `commonMain`~~ **— triaged. 18 remained, not ~50; 15 are
  closed, 2 are sharpened open questions, and the work items are the four bullets below.** The
  `Py_IncRef`-in-`PyObject.init` question named here is answered in place: `borrowed = true` is a
  statement that the wrapper must obtain its own reference, and both directions of getting it
  wrong have now been paid for (§1's double free, §4's dropped reference, and the fixture leak
  below). Two bugs came out of the pass:
  - **`PyType.dict` handed a `mappingproxy` to `PyDict`.** `type.__dict__` is not a `dict`, and
    `PyDict_Size`/`PyDict_Items` reject a non-dict with `PyErr_BadInternalCall()` — returning
    `-1`/`NULL` *and leaving the error indicator set*. Measured: `int.__dict__` reported
    `size == -1`, and the next unrelated `Python3.eval` in the same suite died with
    `Objects/dictobject.c:4248: bad argument to internal function`. It now copies through
    `PyDict_New` + `PyDict_Update` into a real dict. `PyType_GetDict()`, which the code comment
    proposed instead, is **not** an option: it is declared in `cpython/object.h`, outside the
    Limited API, so it is not in the Stable ABI subset this binding restricts itself to.
  - **`PythonTestFixture.mainGlobals()` leaked one reference per call**, wrapping
    `PyObject_GetAttrString`'s new reference with `borrowed = true`. Measured at exactly +50 over
    50 calls — the inverse of §1's defect, in the fixture every functional test is built on.
    `OwnershipLeakTest.theTestFixtureDoesNotLeakMainGlobals` is the guard.
- ~~**`Python3.runMain` is not usable as written**~~ **— done. It runs a module as `__main__`
  through `runpy`, and the interpreter is alive when it returns.**

  `fun runMain(moduleName: String, args: List<String> = emptyList()): Int`. The design question
  this entry left open — what "run a module" should mean for an embedded interpreter that has to
  survive the call — is answered as: *the module executes in a fresh `__main__` namespace, sees
  the argv it was given, and its exit status comes back as a value.*

  **Why not the two obvious candidates.**
  - `Py_RunMain()` is declared in `cpython/pylifecycle.h` — a lifecycle function, and **not part
    of the Limited API** this binding otherwise targets. Its contract is to run whatever
    `PyConfig.run_command`/`run_module`/`run_filename` names *and then finalize Python*; there is
    no mode in which it leaves the runtime standing. Nothing an embedder calls can be built on it.
    (Checked in the vendored 3.14.7 header, `include/cpython/pylifecycle.h:28`.)
  - `PyImport_ImportModule(name)` fails the requirement twice: the body runs under the module's
    *own* `__name__`, so the `if __name__ == "__main__":` block that is usually the entire point
    never fires, and the result is cached in `sys.modules`, so a second call runs nothing at all.
    `RunMainTest.runMainRunsTheModuleAgainOnASecondCall` fixes the second half of that.

  **What it does instead: `runpy.run_module(name, run_name="__main__", alter_sys=True)`** — the
  standard library's own answer, and the machinery `python -m` itself goes through
  (`runpy._run_module_as_main` shares `_get_module_details`/`_run_code` with `run_module`). It
  touches no lifecycle function, so nothing on this path can finalize anything. Read out of the
  vendored `runpy.py`, `alter_sys=True` puts the execution inside two context managers:
  `_TempModule("__main__")`, which installs a **fresh** module as `sys.modules["__main__"]` for
  the duration and puts the previous one back in `__exit__`; and `_ModifiedArgv0(spec.origin)`,
  which points `sys.argv[0]` at the module's file and restores it. Both are `with` blocks, so the
  restore happens on the raising path too. That is what makes the embedder's own `__main__` — the
  one `Python3.exec` writes into — neither read nor written by the module.

  **`sys.argv` is set for the duration and put back.** It has to be set, because
  `_ModifiedArgv0.__enter__` *reads* `sys.argv[0]` before writing it, and an embedded interpreter
  is not guaranteed a usable one (the same soft spot the old body fell into from the other side).
  It has to be restored, because it is process-global state the caller did not ask to have
  rewritten — a module run is a nested activity here, not the process's reason for existing.
  Guards: `RunMainTest.theModuleSeesTheArgumentsItWasGivenAndArgvIsRestoredAfterwards` and
  `.argvIsRestoredEvenWhenTheModuleRaises`.

  **`sys.exit()` is a return value, not an exception.** Decided, not inherited: a module written
  to be run as `__main__` uses `sys.exit(n)` to report a status, and CPython's own command line
  treats it that way — `Py_RunMain` catches `SystemExit` and turns it into the process exit status
  rather than printing a traceback. The half an embedder must *not* inherit is the process exit.
  So `sys.exit()`/`sys.exit(None)`/falling off the end → `0`; `sys.exit(n)` for integer `n` → `n`;
  `sys.exit(x)` for anything else → `x` written to `sys.stderr` and `1` returned, mirroring
  `_Py_HandleSystemExit`. Everything *other* than `SystemExit` propagates as a `PyException`
  carrying the module's real type, message and traceback, with the interpreter still usable.

  **Implementation note.** The Python half is a cached module-level `def` compiled once with
  `Py_CompileString`/`PyEval_EvalCode` into a private globals dict — the same shape as the
  eval-loop checkpoint helper in the same file, including relying on frame setup falling back to
  the interpreter's builtins when the globals mapping has none. It is Python rather than C API
  calls because every line of it is a `try`/`finally` or an `except`, and telling `SystemExit`
  apart through the C API would need a `PyExc_SystemExit` handle this binding exposes no
  accessor for. `Python3.finalize()` drops the cached pointer alongside `checkpointCallable`.

  **Tests: 12 new in `RunMainTest`**, plus the old refusal guard
  `Python3Test.runMainRefusesRatherThanFinalizingTheSharedInterpreter` rewritten as
  `.runMainRunsAModuleWithoutFinalizingTheSharedInterpreter` (the refusal is gone; the property
  it protected is kept). Desktop 485 → 497, 0 failures,
  1 skipped; `ksp-fixtures:app` 64/0 unchanged. **Red phase observed**: all 12 failed with
  `java.lang.UnsupportedOperationException: Python3.runMain is not implemented...` before the
  body landed. The one red phase still not runnable is the *pre-refusal* body's — `Py_RunMain()`
  with no `PyConfig.run_*` set enters the REPL on the process's stdin and finalizes the shared
  interpreter, hanging the worker or crashing every class scheduled after it.

  The tests register their modules through an in-memory meta-path finder rather than files,
  because `runpy` resolves via `importlib.util.find_spec` and then `spec.loader.get_code(...)` —
  a `sys.modules` entry is not enough — and because an in-memory finder runs identically on iOS
  and Android, where the suite has no writable path it can agree on.
- ~~**`Python3.runApp` still refuses, and the reason recorded here was the smaller half of
  it.**~~ **— done. It parses a deliberately reduced subset of CPython's command line and runs
  it in an interpreter that survives the call.**

  This entry used to say the blocker was that `Py_BytesMain` takes `(int argc, char **argv)` and
  so needs an array-of-C-strings marshalling path that each of the four platforms does
  differently. That is true and it is the cheap half. **The half that was missed: `Py_BytesMain`
  finalizes the interpreter.** It is the CLI's `main`, so it reaches `Py_RunMain()` — same header,
  same contract. Marshalling the argv would have bought a function with exactly the defect
  `runMain` was fixed to avoid, which is why "one marshalling path away from working" was the
  wrong description, and why `Py_BytesMain` is still never called anywhere in this binding.

  **The decided scope**, `fun runApp(args: List<String>): Int`, parsing `args` (the slice
  `Py_BytesMain` would see as `argv[1:]`) itself rather than reimplementing CPython's parser
  against a `PyConfig` this binding does not carry:

  - `-c <cmd>` runs `cmd` in the embedder's own persistent `__main__` — the same namespace `exec`
    writes into — with `sys.argv[0]` set to `"-c"`, matching CPython (verified against the system
    interpreter: `python3 -c "...sys.argv..." a b` → `['-c', 'a', 'b']`).
  - `-m <module>` is a straight delegation to `runMain`, which already *is* `python -m`'s
    implementation. Nothing new was written for this branch.
  - a bare script path is read via Python's own `open()` — not a new per-platform Kotlin file API,
    since `nativeMain` has none of its own and CPython's already behaves identically on every
    target — and run in the same persistent `__main__`. `sys.argv[0]` is the path exactly as
    given, unresolved (verified: `cd /tmp && python3 argvtest.py` → `sys.argv[0] ==
    'argvtest.py'`, not an absolute path).
  - `--` followed by a path forces that token to be read as a script path even if it looks like an
    option, mirroring CPython's own observed behaviour (`python3 -- -c args` tries to open a file
    literally named `-c`, it does not run `-c` as a command).
  - `-` (stdin) and anything else starting with `-` — `-E -I -S -s -B -O -OO -X -W -u -P` among
    them — are refused **by name**, not silently ignored: they are `PyConfig` fields that only
    mean something *before* `Py_Initialize()`, and this interpreter is already running by the time
    `runApp` is called. Silently ignoring `-I` would run the caller's code under a security
    posture (isolation) it did not actually get, which is worse than a loud refusal.
  - empty `args` is refused the same way — CPython's own default with nothing on the command line
    is also to read from stdin.

  `sys.exit(n)` is a return value rather than a process exit, `sys.argv` is set for the duration
  and restored afterwards (on both the returning and the raising path), and any other exception
  propagates as a `PyException` with the interpreter left usable — the identical contract
  `runMain` already has, applied to the `-c` and script-path paths through a second small Python
  helper (`__pmp_run_toplevel__`/`__pmp_run_file__`, compiled once and cached the same way as
  `runMain`'s `__pmp_run_module__`). `-m` needs no such helper: it calls `runMain` directly.

  **Tests: 18 in `RunAppTest`**, replacing `Python3Test.runAppRefusesRatherThanSilentlyDoingNothing`
  (superseded, per its own doc comment). Desktop 508 → 526, 0 failures, 1 skipped;
  `compileKotlinAndroidNativeArm64` and `wasmJsTest` both clean (wasmJsNodeTest 460 → 468, 0
  failures). **Red phase observed**: 15 of 18 new tests failed against the old refusing stub with
  `java.lang.UnsupportedOperationException: Python3.runApp is not implemented yet: ...`; the
  remaining 3 assert only the refusal shape itself (exception type, or a message check that the
  stub's own "returned silently without running ..." text happened to satisfy), so they could not
  distinguish red from green and are not evidence either way — the other 15 are.

  **Three commented-out places, not two**: `EmbedAPI.native.kt` also carries a full `Py_BytesMain`
  `actual` — with `memScoped`/`allocArray` marshalling already written — that *looks* live at
  lines 67-74 but sits inside the nested block comment spanning lines 46-128, so it compiles to
  nothing. Kotlin block comments nest, and both `EmbedAPI.kt` (lines 28-249) and
  `EmbedAPI.native.kt` open one at the top that swallows an entire duplicate "Section 1"; the live
  declarations are the later copies. Read either file with a nesting-aware scan before concluding
  a declaration exists, or the duplicate `Py_FinalizeEx`/`Py_RunMain` pairs will mislead.
- **Android had no usable temporary directory, and `tempfile` is not optional plumbing.** Found by
  running `:python-multiplatform:connectedDebugAndroidTest` on `pmp_api26` after `runApp` landed:
  492 tests, 3 failures, all `[Errno 2] No usable temporary directory found in ['/tmp',
  '/var/tmp', '/usr/tmp', '/']`. `tempfile` asks `os.environ` for `TMPDIR`/`TEMP`/`TMP`, then
  falls back to those hardcoded paths and finally `os.getcwd()`; none of the paths exist in an app
  sandbox and an Android process's cwd is `/`. `PythonBootstrap.initialize` now sets `TMPDIR` to
  `context.cacheDir` through the same `Os.setenv` `PYTHONHOME` uses — the cache directory
  specifically, because that is the one Android may reclaim under storage pressure — and leaves an
  embedder's own `TMPDIR` alone if one is already set. `PythonBootstrapTest
  .initializeLeavesTempfileUsable` pins it; 493/0 after.

  **iOS and androidNative were checked rather than fixed, and neither needed fixing.** The three
  `RunAppTest` cases that failed on Android are the ones that write and run a temporary script, so
  they are the probe: `iosSimulatorArm64Test` 457/0 with `RunAppTest` 19/19 including both
  `scriptPath...` cases, and `androidNativeArm64Test` 460/0 on `emulator-5554`. Both re-run on
  this tree rather than taken from a report.

  Why each is unaffected, which is what stops this from being luck:
  - **iOS**, on the simulator, resolves `/tmp` to the host's, so `tempfile`'s hardcoded first
    candidate exists. **This says nothing about a device.** A real iOS app is sandboxed the way an
    Android app is, and the answer there is `NSTemporaryDirectory()`; nothing in this repository
    has run on one, so the entry below about the iOS app bundle owns that question.
  - **androidNative** runs its tests through `adb shell`, which sets `TMPDIR=/data/local/tmp` in
    the environment CPython inherits. That is a property of the *test harness*, not of the target:
    an androidNative binary shipped inside an app would have neither `/tmp` nor that variable, and
    would need the same fix `PythonBootstrap` applies. Recorded here rather than left implied,
    because "the suite is green" would otherwise read as "the target is covered".

- **`Python3.finalize` reports no error detail**, and cannot: `Py_Finalize()` returns void and
  there is no interpreter left to hold an error indicator afterwards. The one improvement
  available is `Py_FinalizeEx()`'s `int` (0, or -1 when flushing buffered data failed). Left
  undone because finalization is untested — its only caller is `artMain/JniExport.kt`, and a test
  that exercises it destroys the interpreter the rest of the suite shares.
  **The FFI half of it is already done, so what remains is one line, not four platforms**:
  `Py_FinalizeEx` has a live `expect` and a live `actual` on desktop, androidNative/iOS, Android
  and wasmJs (`EmbedAPI.{desktop,native,android,wasmJs}.kt`), plus the desktop `MethodHandle` and
  the Android `RegisterNatives` entry. Only the untestability above still blocks it, which is why
  it is still open rather than done in passing.
- **`EmbedAPI.kt`'s section numbers are append order, not the C API docs' chapter order.**
  Sections 1–26 follow the docs; 27 (Type Objects), 28 (Tuple Objects) and 29 (Module Objects)
  were appended as needed. Documented target order: Type before Integer Objects (§16), Tuple
  before List Objects (§22), Module before Iterator Objects (§25). It is a ~370-line pure-comment
  move with no behavioural effect, so it should be done alone, on a quiet tree, or not at all.
- ~~**Several `commonTest` file headers still describe their subjects as `TODO` stubs "expected to
  fail with `NotImplementedError`"**~~ **— done.** The ten named here (`PyObjectTest`,
  `Python3Test`, `PyBasicTypesTest`, `PyModuleTest`, `PyDictTest`, `PyIteratorTest`, `PySetTest`,
  `PyTupleTest`, `PyListTest`, `ConversionTest`) now say what they actually cover and that a
  failure is real. A sweep of the *rest* of the test tree found five more of the same shape, which
  this entry had not counted:
  - `PyExceptionTest` claimed the exec/eval sites "do not yet call `PyException.fromCurrentError`"
    and that its `type`-asserting cases were expected to fail. They all route through
    `pyErrorOrGeneric`, and `Python3.exec` is built on `PyRun_String` specifically so the error
    indicator survives to be read — the opposite of what the header said.
  - `RefCountTest` said `GCLeakTest` was "blocked by the GIL still being held by the initialising
    thread". §1 is closed; `initialize()` parks with `PyEval_SaveThread()` and `GCLeakTest` passes.
  - `PythonTestFixture` named "CPython 3.13"; `pythonVersion` is 3.14.7 and the fixture is
    version-agnostic, so it no longer names one.
  - `DesktopOverheadBenchmark` (desktopTest) said desktop "currently reaches Panama through
    `MethodHandle.invoke` rather than `invokeExact`". That migration is done and this benchmark is
    what measured it — 1015.95 ns → 2.65 ns.
  - `ConversionTest`'s inline `// Once implemented: RAW should hand back a PyObject/pointer-ish
    value, NATIVE a Kotlin List` described the shipped behaviour as future work.

  Headers checked and left alone because they were already accurate: `HandleTableTest` and
  `UpcallTableTest` carry red-phase *notes* that explicitly distinguish "failed to compile before
  the implementation existed" from a regression, which is still the right thing to tell a reader.
  Comment-only throughout: desktop stayed at 236 tests, 0 failures, 1 skipped.
- ~~**No CI.** The README badges point at a different repository.~~ **Partly stale.** Four
  workflow files now exist (`.github/workflows/{desktop,ios,android-native,wasm}.yml`) and
  README's badges no longer point at a different repository — it carries an explicit note instead
  of a badge, with the reason (see §14b). What has not changed: `gh api
  repos/thisisthepy/python-multiplatform/actions/workflows/<file>.yml` 404s for all four, i.e.
  **none of them has ever run on GitHub Actions**, because none of the commits that added or
  touched them has been pushed to a branch GitHub runs workflows from. See §14b. (Re-checked
  2026-08-14: `desktop.yml` still 404s. Nothing here is fixable in a worktree — it needs a push.)
- ~~**Sample app** has not been revisited since the object model landed.~~ **Done — see §13.**

## 13. The sample, and the AGP version that shapes it

The sample now shows four things, each on the real API: the embedded interpreter (`Python3.version`
against `currentPlatform`), a Kotlin-built `PyList` of `PyInt` published into `__main__` and
evaluated by Python, a Python-side `ctypes` call into a Kotlin declaration resolved by name, and
the contents of the generated table read back off `UpcallTable`. Measured on `:sample:run`:

```
runtime : 3.14.7  ·  sys.platform=darwin  ·  MacOS 26.5.1 (aarch64) / JVM 21.0.12
eval    : sum(kotlin_numbers) * 2 -> int: 56
table   : 19 entries, 3 classes, from io_github_thisisthepy_sample
upcall  : Python called Kotlin through handle 4294967314 and got 0
```

and, since the AGP bump below, the same four sections on Android — observed on `pmp_api36` and on
`pmp_api26`, which is `minSdk`:

```
1  3.14.7  ·  sys.platform=android  ·  Android 16 (SDK 36, aarch64) / ART VM 0.9
3  table hit: handle 4294967317 -> 3   (Kotlin-side call; the boundary shim is desktop-only today)
4  22 entries, 4 classes, from io_github_thisisthepy_sample
   @PythonInternal held: the annotated member is absent from the table
```

Twenty-two rather than desktop's nineteen because the Android compilation also scans
`MainActivity`; the module name is the same one, since it is one module now.

Section 3's parenthesis is out of date as of `docs/design/upcall-async-design.md` §13: ART does have a
boundary shim, the two C gaps in it are closed, and that section now reads
`handle 4294967327 -> 0  ·  with args -> presses x3 = 0` from a call **Python** makes through
`_pm_resolve`/`_pm_invoke`. Sections 5-7 run on both emulators as well.

`:sample:run` did not work before this and it was not the sample's fault twice over: the task had
no `PYTHONHOME`, so `Py_Initialize` could not find `encodings`, and a project dependency resolves
to class directories rather than to `desktopJar`, so `manager.loadLibPython` found no bundled
`libpython` either. Both are wired in `sample/build.gradle.kts` now, the second by falling back to
`$PYTHONHOME/lib` — which is the fallback `manager.kt` already had, it just had nowhere to look.

**What was there before:** the Compose template screen (a button, an image, the platform name),
and a `desktopMain` `main()` doing raw `PyLong_FromLongLong`/`PyRun_SimpleString` with a pointer
round-tripped through a `Double`. It compiled. Nothing in it touched the object model, and
`PyRun_SimpleString` is the call `Python3.exec` exists to avoid (it calls `PyErr_Print`, which
clears the error indicator before anything can read it).

### The sample runs on iOS — first time, and it took two fixes that are not in the repo

**Status: verified on the simulator (iPhone 17, iOS 26.2, `iosSimulatorArm64`), 2026-08-13.** All
seven sections produce output and the Compose UI renders. Before this the iOS sample had only ever
been *compiled*; two earlier attempts stopped before launch, and the stale
`iosApp/build/Debug-iphonesimulator/PythonDemo.app` they left behind had no executable in it.

Neither fix is committed, because both are packaging work rather than one-line corrections, and
guessing at them is how the last two attempts produced a bundle that looked built and was not.
Reproduction, in full:

```bash
./gradlew :sample:prepareIosFrameworks :python-multiplatform:extractIosSimulatorStdlib

# (1) actool cannot compile the app icon -- see below
cd iosApp && xcodebuild -project iosApp.xcodeproj -target iosApp -configuration Debug \
    -sdk iphonesimulator ARCHS=arm64 SYMROOT="$PWD/build" \
    ASSETCATALOG_COMPILER_APPICON_NAME="" \
    CODE_SIGN_IDENTITY="-" CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM="" build

# (2) the bundle carries no stdlib -- see §9 item 4
rsync -a ../python-multiplatform/build/python-stdlib/ios-simulator/lib/python3.14 \
    build/Debug-iphonesimulator/PythonDemo.app/lib/

xcrun simctl install "$UDID" build/Debug-iphonesimulator/PythonDemo.app
SIMCTL_CHILD_PYTHONHOME=$(xcrun simctl get_app_container "$UDID" \
    org.thisisthepy.python.multiplatform.demo) \
  xcrun simctl launch --console-pty "$UDID" org.thisisthepy.python.multiplatform.demo
```

**`PYTHONHOME` on an external volume hangs the app instead of failing it.** This is the finding
worth carrying forward. `iosMain/README.md` and `extractIosSimulatorStdlib` both point
`SIMCTL_CHILD_PYTHONHOME` at `python-multiplatform/build/python-stdlib/ios-simulator`, and for the
*test* binary that works — 328 tests, 0 failures, on the same simulator, in the same session. Handed
to the *app* it does not fail, it **blocks forever**: 0.0% CPU, a blank white window, an empty
`--console-pty` capture, and no sandbox denial or Python error anywhere in `simctl spawn … log show`
(only ordinary UIKit chatter). `sample`(1) on the process — all 1554 samples on one stack:

```
kfun:...PythonDemo#start()               PythonDemo.kt:45
kfun:python.multiplatform.ffi.Python3#initialize(kotlin.Boolean)   Python3.kt:45
Py_InitializeEx -> Py_InitializeFromConfig -> init_interp_main
  -> _PyUnicode_InitEncodings -> _PyCodec_InitRegistry
  -> PyImport_ImportModule -> ... -> import_find_and_load
  -> os_listdir -> __opendir2 -> open$NOCANCEL   <- parked here
```

That is the `encodings` import, parked in `open()` on a directory under `/Volumes/`. This repo's
workspace is on an external SSD (see AGENTS.md), so the documented recipe reaches a path a
sandboxed simulator *app* cannot open, while a simctl-spawned *test* binary can. Note what the
symptom is not: the failure this section and `iosMain/README.md` both warn about is
`Fatal Python error: Failed to import encodings module`, an abort. A hang looks like "the app
launched and Compose is slow", which is why it is worth writing down. Staging the stdlib inside the
app bundle and pointing `PYTHONHOME` at the installed container clears it.

**`actool` refuses to compile the app icon on this machine.** The installed simulator runtimes stop
at iOS 26.2 (`23C54`) and the only simulator SDK is 26.5 (`23F81a`), so:

```
error: No simulator runtime version from ["21A328", ..., "23C54"] available to use with
       iphonesimulator SDK version 23F81a
```

Bisected: the trigger is `--output-partial-info-plist` *together with* `--app-icon`; either alone
compiles. `ASSETCATALOG_COMPILER_APPICON_NAME=""` skips it and costs only the springboard icon.
Environment, not this project — but it is the wall the build hits first, and
`xcodebuild -showdestinations` also intermittently reports *no* eligible simulators for the scheme
because of the same missing platform, which makes `-destination` unusable and `-target` the way in.

**The seven sections, observed.** `MainViewController.kt` gained a `dumpDemoSections()` that prints
what each card would show: `simctl` can install and launch but cannot tap, and sections 2 and 5-7
are behind buttons. Without it a run can only be said to have exercised the four cards that render
eagerly — which is most of how "iOS compiles" and "iOS runs" stayed indistinguishable.

| | iOS simulator (observed) | desktop JVM, same day (`:sample:runNativeImageUpcallDemo`) |
|---|---|---|
| 1 | `3.14.6 · sys.platform=ios · iOS 26.2 (SDK 260200, arm64) / Native` | `3.14.7 · sys.platform=darwin · MacOS … / JVM 21` |
| 2 | `int: 56` | same expression, not printed by that entry point |
| 3 | `table hit: handle 4294967325 -> 1` (Kotlin-side call) | `resolved handle 4294967324`, `invoke result = 7`, `presses x3 = 21` (Python-side) |
| 4 | `42 entries, 4 classes, from io_github_thisisthepy_sample`; `@PythonInternal held` | `41 entries, 4 classes`; `@PythonInternal entry resolves to -1` |
| 5 | `installed over PyMethodDef: 519 lines, 1 proxy classes`; `Greeter('Kotlin').greet(2) -> hello Kotlin! hello Kotlin!`; `g.greetings = 99 -> AttributeError (private set held)` | `509 lines, 1 proxy classes`; same three lines |
| 6 | `Greeter.forget() -> 100, then built=0`; `Greeter('x').built -> AttributeError` | identical |
| 7 fast | `await g.greetNow(1) -> hello fast path!`, `Futures created -> 0` | identical |
| 7 slow | **`not wired on iOS`** | `hello slow path! hello slow path!`, `Futures created -> 1` |

**Nothing diverged that was not already known to diverge.** Sections 5 and 6 are character-for-character
what desktop prints, which is the result worth having: the generated proxy module, the metaclass
companion and the private-setter refusal all behave the same over a `PyMethodDef` shim as over
`ctypes`. The only gap is section 7's slow path, and `ProxyDemo.ios.kt` already says why — resuming a
parked continuation needs a Kotlin/Native worker the sample does not start. The library's own
`PythonProxyNativeDeliveryTest` does it with a bare `pthread_create` and passes here, so this is a
sample limitation, not a platform one.

The two count differences are both benign and neither is a defect: 42 vs 41 table entries and 519 vs
509 generated lines follow from the per-target source sets KSP scans (`iosMain` has
`MainViewController.kt`, `desktopMain` has `main.kt` and `NativeImageMain.kt`) — the *proxy class*
count, which is what the generated module is actually judged on, is 1 on both. `docs/design/upcall-async-design.md`
§13.5 records `466 lines, 2 proxy classes` for Android; that measurement is older than these two and
was not re-taken here, so the difference is not evidence of anything yet.

**Unlike every previous run of this sample, no library defect fell out of it.** Both blockers were
in packaging and in the toolchain. That is itself the report: the iOS half of the object model,
the upcall table, the generated proxy module and the fast-path `await` all work in an app, not only
under the test runner.

### The convenience plugin could not be applied to an Android module — closed

This was the finding, and it was a property of the repo rather than of the sample.

```
java.lang.NoSuchMethodError: 'void com.android.build.api.variant
    .AndroidComponentsExtension.addKspConfigurations(boolean)'
  at com.google.devtools.ksp.gradle.KspConfigurations$3$1.execute(KspConfigurations.kt:114)
```

KSP 2.3.11 declares `MINIMUM_SUPPORTED_AGP_VERSION = 8.10.0` (read off its `agpUtils` class), and
this build pinned AGP 8.5.2, whose `AndroidComponentsExtension` has no such method (`javap` on
`gradle-api-8.5.2.jar`). So `id("io.github.thisisthepy.python.multiplatform.bindings")` — which
applies `com.google.devtools.ksp` — died at configuration time in any module carrying an Android
plugin, which is **every Android consumer of the plugin, not just this sample.** `ksp-fixtures`
never hit it because neither fixture module applied an Android plugin: the fixtures were verifying
their own shape rather than the plugin's advertised one, the same failure mode §2's
`AssembledApiTest` had.

**Two versions moved, and nothing else had to.**

| | was | now | why |
|---|---|---|---|
| AGP | 8.5.2 | **8.10.1** | KSP's declared minimum is 8.10.0 |
| Gradle | 8.9 | **8.11.1** | AGP 8.10's own minimum |

Kotlin (2.4.20-Beta2), KSP (2.3.11) and the Compose Multiplatform plugin (1.6.11) are untouched —
the bump needed no chain beyond those two. `python-multiplatform`'s Android wiring is the part
that was expected to complain, since it hangs hand-written `Copy` tasks, a `tasks.configureEach`
name match on `merge*JniLibFolders`/`*NativeLibs`, and a `preBuild.dependsOn(linkTaskProvider)`
off AGP internals — none of it needed changing. `compileSdk` stays at 34.

`:sample` applies the bindings plugin directly now and `:sample-bindings` is gone; its sources moved
back under `sample/src/*/kotlin/.../demo/bindings/`. The Android `UpcallDemo` actual, which used to
answer "unavailable on Android" to every member, is an ordinary one-line delegation like the others.

Two things the fold-back exposed that the split had hidden:

- **`androidMain` can name the generated table; `iosMain` cannot.** `androidMain` *is* the Android
  target's source set, compiled together with each variant's KSP output, so its `actual` names
  `FunctionTable` directly. `iosMain` is an intermediate source set the generating leaves depend
  on, so it still needs one `actual` per leaf. Same processor, opposite answer, and the difference
  is which side of the compilation the source set sits on. (Both `actual`s are generated now —
  see "Two smaller things the sample found" below — but the asymmetry that made them necessary is
  unchanged.)
- **Exposure is a blacklist, so applying the plugin offers the whole module to Python — including
  Compose.** A `@Composable` may only be called from another composable and a generated entry is an
  ordinary lambda, so a scanned `@Composable` is a compile failure of *generated* code. `:sample`
  therefore sets `excludePackages` to keep `...demo.ui` out. A separate module made this invisible;
  any real app has UI in the same module as its bindings.

### `ksp-fixtures/android`, and the second defect it found immediately

`ksp-fixtures/android` is the fixture that carries `com.android.library` — the one thing neither
existing fixture does. It applies the bindings plugin, exposes the same declaration shapes the
desktop fixture uses, and runs 6 tests against the generated `FunctionTable` as a plain JVM unit
test (`testDebugUnitTest`, no device). If the AGP/KSP pair ever drifts apart again it stops
configuring, which is the failure worth having.

It earned itself on the first run, by failing for an unrelated reason:

```
6 tests completed, 5 failed
```

The plugin decided which KSP configurations to put the processor on with `name.endsWith("Test")`.
That holds for Kotlin target names (`kspDesktopTest`), but **AGP names its source sets with the
build type last**:

```
main          kspAndroid              kspAndroidDebug              kspAndroidRelease
unit test     kspAndroidTest          kspAndroidTestDebug          kspAndroidTestRelease
instrumented  kspAndroidAndroidTest   kspAndroidAndroidTestDebug   kspAndroidAndroidTestRelease
testFixtures  kspAndroidTestFixtures  kspAndroidTestFixturesDebug  kspAndroidTestFixturesRelease
```

so the suffix check caught three of those nine. The processor ran over the *test* sources on
`kspAndroidTestDebug` and emitted a second `Fragment_<module>` and a second `FunctionTable` into
the test compilation, where they shadowed the real ones from `main` — a compilation's own
generated sources win over its classpath. The table the test read held the test class's own
members and nothing the module exposes.

This is exactly the duplicate-fragment hazard the plugin's own doc comment describes, arriving
through a name shape that comment did not anticipate. `Test` is now matched as a camel-case *word*
(`(?:^|[a-z0-9])Test(?:[A-Z]|$)`), not as a suffix, so a flavour or target named `testing` still
keeps its processor. `WiringTest` pins the full observed Android configuration list.

**Nothing without an Android plugin could have found this**, which is the point of the fixture.

### ~~Two smaller things the sample found~~ — both closed

Neither existing fixture had the *shape* to catch either one, which is the part worth keeping.
`ksp-fixtures` grew that shape first, and both defects reproduced there before anything was fixed.

#### The generated table is reachable only from the source set of the target that generated it

KSP writes `FunctionTable` into `iosSimulatorArm64Main` and its siblings, so `iosMain` — which
those leaves depend on — cannot name it, exactly as `commonMain` cannot. The rule is about
*intermediate* source sets, not about "not being `commonMain`": `androidMain` is the Android
target's own source set and names `FunctionTable` directly, which the fold-back above made visible.

**The constraint has not moved and cannot.** A fragment scan has to see the target's own
declarations — Android's table is three entries larger than desktop's because that compilation also
scans `MainActivity` — so generating into `commonMain` through `kspCommonMainMetadata`, the usual
KMP answer, would shrink the exposed surface to whatever happens to be common. What moved is *who
writes the `actual`*:

```kotlin
// commonMain, and nowhere else in the repository
@InstallsUpcallTable
expect fun installGeneratedUpcallTable()
```

`python-multiplatform-ksp` finds the annotated `expect` — a leaf compilation sees its whole source
set closure, the same fact that lets the fragment scanner pick up common declarations — and emits
one `actual` per leaf, carrying `@PythonInternal` so the generated function does not itself end up
in the table. `sample` lost its three identical `InstallTable.ios*.kt` files and gained nothing in
their place. Android's hand-written `actual` is gone too, not because Android needed one but
because a per-platform special case is precisely what shared code cannot be written against.

`expect`/`actual` rather than a common interface plus a runtime registry because the reference
chain has to stay static: §7 keeps every fragment reachable to the Kotlin/Native linker by naming
it explicitly, with no `ServiceLoader` and no `@EagerInitialization`, and a generated object that
nothing names is dead code the linker may drop. The generated `actual` is that name.

`ksp-fixtures/app` gained a second Native leaf (`androidNativeX64`) purely so the default hierarchy
would give it an intermediate `androidNativeMain` — `iosMain`'s shape, without Xcode — plus a
`commonMain` that installs the table. Written the obvious way first, it failed exactly as the
sample did:

```
e: ksp-fixtures/app/src/commonMain/kotlin/fixture/app/TableInstall.kt:4:39
    Unresolved reference 'FunctionTable'.
```

Neither existing fixture could have produced that: both reached the table from `desktopTest`, a
*leaf* compilation, where it resolves fine. `ksp-fixtures/android` carries the same seam, because
KSP runs once per AGP *variant* there and that is where a generated `actual` could go missing or
land twice.

#### A `var` with a `private set` generated a fragment that did not compile

`FragmentScanner` decided on `property.isMutable` alone and emitted a `STATIC_SETTER`/`SETTER`
assigning to it. The ROADMAP recorded this read off the scanner rather than observed; putting the
shape into `ksp-fixtures/library` produced it on the first run:

```
e: Fragment_..._library.kt:58  Cannot access 'topLevelPrivateSet': it is private in file.
e: Fragment_..._library.kt:442 Cannot access 'privateSet': it is private in 'RestrictedSetters'.
e: Fragment_..._library.kt:458 Cannot access 'protectedSet': it is protected in 'RestrictedSetters'.
```

Three errors from four properties, and **the fourth is the one that matters.** `internal set`
compiled: the fragment is generated into the same compilation as the sources it scans and
`internal` is enforced per Kotlin *module*, so the assignment is legal there — and only there.
Fixing what the build complained about would have left a table entry describing a write no
consuming module could perform. `BindingPolicy.isExposedSetter` therefore reads the *setter's*
modifiers and treats `private`, `protected` and `internal` alike. The read side is untouched: a
`var` with a restricted setter is a read-only property to Python, which is what it already is to
every Kotlin caller outside its module.

### ~~What the sample still cannot show~~ — the trampoline exists now

**What it was.** `UpcallStub` was desktop-only and both its stubs were `(long) -> long`, so the
entry the demo called took no arguments and returned a `Long`. The *table* had carried arity and
per-argument `TypeTag`s from the day it was written; nothing read them. Python could call Kotlin
and could not pass it anything.

**What it is.** `python.multiplatform.ffi.upcall.UpcallTrampoline` marshals both directions and is
`commonMain` — the argument handling was never platform-specific, only the address publishing was.
Desktop reaches it through one new Panama stub. Python now calls Kotlin with real arguments of
every marshalled tag, constructs a Kotlin object and calls a method on it, and gets a Python
exception when the Kotlin side raises: `python.native.ffi.UpcallArgumentsTest`, which drives the
whole path from inside the interpreter, and `UpcallTrampolineTest` (commonTest, so it compiles for
every target) which drives the marshaller directly. Desktop: 233 tests, 0 failed.

Three things the work settled, each recorded in `docs/design/upcall-design.md`:

- **One shape, not a family.** Argument passing needed exactly one new C shape,
  `(long, long) -> long`. Arity and types ride in the tuple and in the table entry, never in the C
  signature, so the stub count does not grow with the exposed surface. It is also, exactly,
  `PyCFunction` — so the generated proxy type will not need a new shape either. Every other CPython
  slot shape was already in `Panama`'s vocabulary.
- **`ctypes.CFUNCTYPE` releases the GIL.** The first version segfaulted in `_PyThreadState_GET`
  (`PyErr_Occurred+0x1c`) on a thread whose own `withGIL` depth counter said it held the GIL. That
  counter records scopes *Kotlin* opened, and C is free to have dropped the GIL inside one. An
  entry point reached from C must take its own `PyGILState_Ensure` unconditionally — which applies
  to every platform's entry point, not only this one.
- **`bytes` has no fast route in this ABI subset.** `PyBytes_AsString` is bound as a
  NUL-terminated UTF-8 *string* read, which destroys exactly the payloads `ByteArray` exists for,
  and `PyBytes_AsStringAndSize` is in no platform's `EmbedAPI`. The trampoline goes an item at a
  time — correct, and the slowest path across this boundary by a wide margin.

**iOS and androidNative have it too.** `python.native.ffi.UpcallEntry` (`nativeMain`, so both
targets get it at once) publishes the same trampoline, and `UpcallEntryTest` (`nativeTest`) drives
it from inside the interpreter: iOS simulator 230 tests, 0 failed, 8 of them new; androidNative
compiles main and test. Desktop is unchanged at 236, 0 failed, 1 skipped.

**androidNative now runs that suite rather than only compiling it.** `androidNativeArm64Test`
(`python-multiplatform/build.gradle.kts`) pushes the Kotlin/Native test binary and the CPython
prefix to `/data/local/tmp` and parses the runner's TeamCity output into JUnit XML, so the target is
counted like every other: **252 tests, 0 failed, on `pmp_api26` and `pmp_api36`, over five runs
each.** Nothing new broke — unlike §11b, where attaching this suite to ART for the first time killed
it at the 2nd test and again at the 12th — and the reason is that `nativeMain` was already being
exercised by the iOS simulator. What had never been executed was `artMain` and the androidNative
`cinterop` bindings, and those came up clean. The empty androidNative row in
`docs/design/upcall-design.md`'s five-platform upcall table is filled in from those runs.

It is *not* the `@CName` + `ctypes.CDLL(None)` route this document and `docs/design/upcall-design.md`
both predicted, and the reason is two independent measurements rather than a preference:

- `@CName` symbols are exported from the androidNative `.so` (`T` in `nm -D`) and **are not
  present at all** in the iOS framework or in either target's test executable — not stripped at
  link time, never emitted.
- This project's iOS `Python.framework` carries **no `_ctypes`** (no `lib-dynload`, and its
  `PyInit_*` exports stop at `time`), so `import ctypes` raises there regardless.

What it uses instead is a real `PyMethodDef` whose `ml_meth` is a `staticCFunction` and whose
`self` carries the callable handle — i.e. the `PyCFunction` slot the shape was chosen to be, and
what the generated proxy type installs anyway. The desktop `ctypes` shim was standing in for
exactly this. The `@CName` functions are kept: they are the C-host entry point, they are what
`ctypes` reaches on Android, and `UpcallEntry.invokeAddress` hands out their addresses regardless
of what the linker did with the name.

`nativeTest` had to be wired into the source-set hierarchy for that test to exist — the directory
was there and nothing pointed at it, so its one file was dead source and had been copied
byte-for-byte into all five native target test source sets. Those copies are gone.

**Android landed next, and not the way this section predicted.** The prediction was "a
`RegisterNatives` method behind a `PyCFunction` shim". `RegisterNatives` binds a JVM `external fun`
to a C function — the *downcall* direction — and no arrangement of it lets C call Kotlin, which is
what a `PyMethodDef` slot needs. The boundary is inverted instead, exactly as the proxy type's
`tp_traverse`/`tp_clear` already are: the entry points are C functions in
`artMain/cinterop/jni_onload.def` and *they* call `python/native/ffi/UpcallCallbacks` through
`CallStaticLongMethod`, reusing `pmp_attach` so an upcall arriving on a `threading.Thread` — a bare
pthread ART has never seen — attaches and detaches rather than silently returning `NULL`.
`RegisterNatives` survives only for the one cold `upcallPublish(long)` that installs the bootstrap.
`UpcallEntryTest` (`androidInstrumentedTest`) is green on `pmp_api26` and `pmp_api36`, 251 tests
each, 0 failed; `docs/design/upcall-design.md`'s "Android's boundary runs the other way round" has the rest.

~~**What is still open.** wasm — a `@WasmExport` plus `Table.set` (3.1 ns, measured in §11).
Per-platform detail is in `docs/design/upcall-design.md`'s "What each platform still owes".~~ **Closed.**
wasm's general upcall entry (`UpcallEntry.kt`, `wasmJsMain/kotlin/python/native/ffi/`) publishes
five `PyCFunction` objects over the one `@WasmExport`/`Table.set` shape this note describes, and
`docs/design/upcall-design.md:207` now marks that row "already proven" rather than owed. The generated
proxy type that would let Python write `obj.method(x)` instead of going through `_pm_bind` is §7's
remaining half.

## 14. Current state, gathered

This document accumulates section by section, in the order work happened, so the same fact ends
up recorded in two or three places over time and a reader has to piece the current picture back
together. This section is that picture, as of a documentation audit on 2026-08-13. It adds no new
finding of its own — every number and every item below is sourced from a section above, or from
running the code — it exists so the state doesn't have to be reassembled by reading the whole file.

### 14a. Test counts across the six run paths, and how to reproduce them

Numbers rot the moment someone adds a test, so what matters here is the *recipe*: clean the
target's `build/test-results/` directory first (a crashed run leaves the previous run's XML behind
and a naive count reports the old, larger number as if it were current — see AGENTS.md), run the
task with output redirected to a file so the exit code is real, then sum the `tests`/`failures`/
`skipped` attributes off every `<testsuite>` root under the result directory. All of the JVM and
Native targets share that shape; Android's instrumented target does not (below).

| # | path | task | results land in | needs a device/emulator? |
|---|---|---|---|---|
| 1 | desktop | `:python-multiplatform:desktopTest` | `python-multiplatform/build/test-results/desktopTest/*.xml` | no |
| 2 | iOS simulator | `:python-multiplatform:iosSimulatorArm64Test` | `python-multiplatform/build/test-results/iosSimulatorArm64Test/*.xml` | boots the simulator (needs Xcode licence acceptance), not a physical device |
| 3 | Android (ART) | `:python-multiplatform:connectedDebugAndroidTest` | AGP's own convention, **not** `build/test-results`: `python-multiplatform/build/outputs/androidTest-results/connected/debug/<deviceName>/TEST-*.xml`, one folder per attached device | **yes** |
| 4 | androidNative | `:python-multiplatform:androidNativeArm64Test` / `androidNativeX64Test` | `python-multiplatform/build/test-results/androidNative<Abi>Test/*.xml` — the task pushes the compiled test binary to `/data/local/tmp` and translates the Kotlin/Native runner's TeamCity output into this JUnit XML itself (`build.gradle.kts`, the `androidNative${targetSuffix}Test` task registration) | **yes** |
| 5 | wasmJs | `:python-multiplatform:wasmJsNodeTest` | `python-multiplatform/build/test-results/wasmJsNodeTest/*.xml` | no (runs under Node) |
| 6 | fixtures | `:ksp-fixtures:app:desktopTest`, `:ksp-fixtures:library:desktopTest`, `:ksp-fixtures:android:testDebugUnitTest` | `ksp-fixtures/<module>/build/test-results/<task>/*.xml` | no — `android`'s is a plain JVM unit test (`testDebugUnitTest`), not the instrumented one |

A one-line counter, given a directory of result XML:

```python
import glob, xml.etree.ElementTree as ET
files = glob.glob("<results-dir>/*.xml")
tests = sum(int(ET.parse(f).getroot().attrib.get("tests", 0)) for f in files)
failures = sum(int(ET.parse(f).getroot().attrib.get("failures", 0)) for f in files)
skipped = sum(int(ET.parse(f).getroot().attrib.get("skipped", 0)) for f in files)
```

### 14a-1. The state at the deadline — 2026-08-24, every path re-run from a clean results directory

Taken in one pass on `develop` @ `115370e6`, `--rerun` throughout, each results directory deleted
before its task ran. This exists because the failure this document keeps having is not a wrong
number, it is a number nobody re-took: the entry below is from 2026-08-13 and every sweep since
has found something stale.

| path | count | fail | note |
|---|---|---|---|
| `:python-multiplatform:desktopTest` | **529** | 0 | 1 skipped |
| `:python-multiplatform:wasmJsNodeTest` | **471** | 0 | |
| `:python-multiplatform:wasmJsBrowserTest` | **10** | 0 | |
| `:python-multiplatform:iosSimulatorArm64Test` | **457** | 0 | |
| `:python-multiplatform:connectedDebugAndroidTest` | **495** | 0 | `pmp_api26` |
| `:python-multiplatform:androidNativeArm64Test` | **460** | 0 | `pmp_api26`, on device |
| `:python-multiplatform-gradle-plugin:test` | **175** | 0 | |
| `:ksp-fixtures:app:desktopTest` | **70** | 0 | |
| `:ksp-fixtures:compose:desktopTest` | **99** | 0 | |
| `:ksp-fixtures:artifact:desktopTest` | **36** | 0 | |
| `:ksp-fixtures:android:connectedDebugAndroidTest` | **8** | 0 | first device run was 2026-08-20 |
| `compileKotlinAndroidNativeArm64` | — | — | no `e:` lines |

**`ksp-fixtures:artifact` is in that list for a reason.** It was red on `develop` for days because
the verification set in use was plugin + app + compose, and two failures sat behind that gap: a
pinned list that `e2d75100` outdated, and a stub-shape canary that `ac8e4708` outdated. All three
fixture modules are the set; AGENTS.md now says so.

**Sibling repositories, same pass:** `toolchain` 98 + `tcl` 9, `pypackpack` `packpack` 135 +
`cli` 26, `pythonx-compose` 71 — all 0 failures.

**The three external consumers, which no in-repo test can stand in for**, run against a
`publishToMavenLocal` of all three producers: `consumer-plugin-test` and `consumer-plugin-android`
build clean, and `consumer-test` runs and prints `Result from Python: 56`.

**As last verified, 2026-08-13**, immediately after merging `develop` into this branch, from a
cleaned results directory, for the five paths that need no device (§14b explains why 3 and 4 were
not run here — this audit was instructed not to use devices):

| path | tests | failures | skipped |
|---|---|---|---|
| desktop | 327 | 0 | 1 |
| iOS simulator | 289 → **328** | 0 | 0 |
| wasmJs | 302 | 0 | 0 |
| fixtures: `ksp-fixtures:app` | 64 | 0 | 0 |
| fixtures: `ksp-fixtures:library` | 0 | 0 | 0 (no test sources — exercised through `app`, not standalone) |
| fixtures: `ksp-fixtures:android` | 8 | 0 | 0 |

The iOS row carries two figures because it was counted twice on 2026-08-13 from two branches: 289 by
the audit above, and **328, 0 failures, 0 skipped** re-counted from a cleaned results directory on
`work/gil` after merging `develop`, alongside the iOS sample run in §13. Both are real; the second
is the one that includes `develop`'s `ProxyHandleLifetimeTest`. This is exactly the rot §14a exists
to prevent, so: count it yourself, and say which tree you counted.

Android (ART) and androidNative were **not** re-run for this audit — both need a connected device
or emulator, which this pass was told not to use. Numbers for those two paths appear at several
points earlier in this document, taken at different times for different subsets of the suite, and
none of them should be read as "the current total": §2's own closing measurement (213 tests, 0
failed, the measure §2 itself set), §11b's `commonTest`-on-Android wiring (176 tests per device,
8 failures tracked in §2), the upcall suite specifically (`UpcallEntryTest`, 251 tests per device,
0 failed, §13), and androidNative's equivalent (252 tests, 0 failed, over five runs, §13). These
are different checkpoints in the same section's history, not five inconsistent measurements of one
number. Whoever next has a device or emulator available should run
`connectedDebugAndroidTest` and the two `androidNative*Test` tasks and replace this paragraph with
one number each, dated.

### 14b. Remaining open items

Gathered from across this document rather than newly found, except where noted. Each item names
what is blocking it and what the next concrete step is.

1. **`autoDrainInterval`'s default on the GIL build.** *(Requested explicitly for this audit.)*
   Not actually blocked on anything technical — the evidence is in §9 and
   `docs/investigations/gc-scheduling-investigation.md` §6-§7, and a decision has already been implemented in code
   (`Python3.kt`: `if (BuildConfig.pythonFreeThreaded) 32 else 0`). What is open is whether "off"
   is the right *default* to ship, not whether the mechanism works. The case for leaving it off:
   turning it on lets a `__del__`/weakref callback/pending call run inside a `withGIL` scope exit
   the caller never asked to yield from — a behavioural change, not a free one. The case for
   turning it on: measured, a GIL-build embedder that only calls the C API directly never runs the
   cyclic collector either (§9, "This is not only a free-threading problem"; the accumulation
   experiment in §9 reports 20,000+ cyclic objects with the automatic path off, against ~1,970
   left over with it on), so "off by default" is not free of consequences — it is a silent leak of
   a different kind, traded for not surprising the caller.
   **(a) blocking it:** nothing technical; a product judgement between "surprise a caller with a
   side effect" and "leak cycles silently by default" is what remains.
   **(b) next step:** decide, and if the answer changes, it is a one-line change at
   `Python3.kt`'s `autoDrainInterval` declaration — the reasoning to cite either way is already
   written on that property's KDoc and in `docs/investigations/gc-scheduling-investigation.md` §6.

2. **CI has never run.** *(Requested explicitly for this audit.)* Four workflow files exist
   (`.github/workflows/{desktop,ios,android-native,wasm}.yml`) but `gh api
   repos/thisisthepy/python-multiplatform/actions/workflows/<file>.yml` returns 404 for all four —
   confirmed during this audit. **(a) blocking it:** none of the commits that added these files has
   reached a branch or pull request GitHub Actions runs against; they exist only on local branches
   (this one included) so far. **(b) next step:** push and open a PR, or merge to `main`, and watch
   the first run. README's badge section already anticipates this correctly (see §14c) — no README
   change is needed once CI goes green, only adding the badge it already describes how to add.

3. **The suite has never been run on Linux or Windows.** *(Requested explicitly for this audit;
   newly documented — this document did not previously record it as an open item anywhere.)* The
   `desktop` target is a `jvm("desktop")` target, not separate Kotlin/Native targets per OS, and
   README/§9's platform table both claim macOS, Linux and Windows support through
   `python-build-standalone` archives. Nothing found in this repository — no CI run, no log, no
   note elsewhere in this document — shows the desktop suite having actually executed on anything
   but macOS. **(a) blocking it:** access to a Linux and a Windows machine (or CI runners for
   both) — this audit had neither and did not attempt it. **(b) next step:** the cheapest path is
   item 2 above: if `.github/workflows/desktop.yml` already includes Linux/Windows runners (not
   checked in this pass), getting CI running once resolves both items together; if it only runs
   macOS runners today, it needs those OSes added before this item closes.

4. **BeeWare's iOS (≤3.14) distribution has no signature, so Sigstore verification is impossible
   for it.** *(Requested explicitly for this audit.)* Already documented in §12: BeeWare's
   Python-Apple-support publishes five `.tar.gz` assets and nothing else — no checksums, no
   signatures, no attestations, checked against the live release listing. **(a) blocking it:**
   nothing this repository controls — the upstream project does not publish signing material of
   any kind, for any version. **(b) next step:** none available today beyond what already exists,
   the SHA-256 lockfile pin (`python-checksums.properties`) as the only integrity check for this
   one source. This stops mattering going forward rather than getting fixed: python.org's iOS
   XCframework, which *does* publish Sigstore material and *is* already wired up (§12), becomes the
   only source at 3.15 and later (§9, "3.15: the source has to change with the version"), so this
   item is scoped to versions ≤3.14 by construction and shrinks as the default version moves.

5. ~~`tp_traverse` is generated but not wired into CPython's actual `tp_traverse` slot; `tp_clear`
   and Kotlin-side cycle closing are untouched.~~ ~~`PythonProxySource` never constructs an instance
   of that type; every rendered class was a plain Python `_PmObject` that could not carry a custom
   `tp_traverse`.~~ **Both closed.** `work/cycles` (merged `aa5436d1`, 2026-08-14) wired the
   generated per-class traverse function into `ProxyTypeFactory`'s `PyType_FromSpec` slot table
   with `tp_clear` and `tp_dealloc` alongside it. `work/handleslot` (`99acd830`, 2026-08-18, merged
   `a1fb825c`) then closed the renderer gap: `PythonProxySource.renderClass` now emits
   `class $className(_PmGcObject):` instead of `_PmObject` whenever `ReflectedClass.hasTraverse`
   is set and there is no metaclass (`PythonProxySource.kt:1168`,
   `val gcEligible = metaclassName == null && cls.hasTraverse`), and `_PmGcObject`'s `_pm_handle`
   is a member descriptor over the C type's reserved slot rather than a Python attribute
   (`PythonProxySource.kt:728-758`), so `self._pm_handle = h` in the generated `__init__`/`_pm_own`
   writes straight into the storage `tp_traverse`/`tp_clear` read — no call site needed to change.
   Same-day follow-ups extended this from desktop-only to every target: `a5387511` (wasm, merged
   `b479b407`), `1544f095` (Android, merged `0c41874d`), `581ec772` (the two cinterop/native
   targets, merged `e063501f`) — all four ancestors of `develop` as of 2026-08-18.
   The `subtype_dealloc`/`PyObject_GC_UnTrack` double-untrack question is answered, not assumed:
   `CycleCollectionTest.aPythonSubclassOfTheProxyTypeSurvivesBeingUntrackedTwice`
   (`desktopTest/.../ref/CycleCollectionTest.kt:658`) builds a Python subclass of the generated
   proxy heap type, confirms `gc.is_tracked()` on the instance so the second untrack is actually
   exercised, and drives it through `del` + `gc.collect()`. It passes: CPython's own vendored
   header says so (`internal/pycore_gc.h`: *"See also the public PyObject_GC_UnTrack() which accept
   an object which is not tracked"* — the internal variant asserts, the public one this code calls
   is defined to tolerate it), and the test exercises rather than just cites that guarantee.
   **What is still open:** Kotlin-side cycle closing — the handle table holds every entry with a
   strong reference by construction (`HandleTable.kt:25`, "this table leaks by construction... a
   strong entry here is a[nother reference in the cycle]"), so a cycle that runs entirely through
   Kotlin objects (no Python-side edge for `tp_traverse` to report) is still not collectible.
   `docs/design/object-lifetime.md` has the mechanism and names the hard parts.

6. **`PyValue`'s lazy conversion path.** (§7b) **Closed.** The per-type lifetime rule this item
   asked for is written (`docs/design/object-lifetime.md`, "Conversion caching, and where it stops"), the
   cache is implemented against it and enforced by `isIndependentOfPythonMemory`, and
   `PyValueLazyConversionTest` covers the path `ConversionTest` never touched. Refusing `bytes`
   outright rather than converting it to a `ByteArray` copy is the one piece of the type table
   left unimplemented, and it is recorded as such in §7b rather than as a gap here.

7. **`ksp-fixtures/android`'s `jvmTest` 8 tests on device & API Level Path Assertions.** **Closed.**
   - **API Level Path Assertions (`AndroidCleanerPathTest.kt`):** An instrumented test suite in `python-multiplatform/src/androidInstrumentedTest` reads `Build.VERSION.SDK_INT` and asserts exact path selection and runtime behavior across API levels:
     - On **API 26** (`pmp_api26` - Android 8.0 < 33): `cleanable` field is `null` (reflecting on missing `java.lang.ref.Cleaner` throws `NoClassDefFoundError`), `fallback` (`PhantomCleanerRegistry.Cleanable`) is populated, `PhantomCleanerRegistry.outstanding` increments by 1, `PhantomCleanerRegistry.draining` is `true`, and `PhantomCleanerRegistry.drained` increments upon GC reclamation. This verified that the `PhantomReference` fallback path is reached and fully functional on API < 33. The premise that "needs new hardware" was incorrect: the `pmp_api26` AVD was already present and executed the fallback path.
     - On **API 36** (`pmp_api36` - Android 16 >= 33): `cleanable` (`java.lang.ref.Cleaner.Cleanable`) is populated, `fallback` is `null`, and `PhantomCleanerRegistry` is completely bypassed (`drained` and `outstanding` do not increment).
   - **`ksp-fixtures/android` Device Verification:** Configured `androidInstrumentedTest` source set and `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` in `ksp-fixtures/android/build.gradle.kts` and added `src/androidInstrumentedTest/kotlin/fixture/android/GeneratedAndroidTableTest.kt`. All 8 upcall table tests ran on device (`pmp_api26`) and passed (8/0/0).

8. ~~wasm's `ProxyTypeExports.kt`-shaped trampoline generation is manual.~~ **Closed 2026-08-18 (`112b54d0`), plugin wiring completed.**
   `GenerateWasmProxyExportsTask` (`python-multiplatform-gradle-plugin`) generates the three `@WasmExport` proxy type slot trampolines (`pmp_tp_traverse`, `pmp_tp_clear`, `pmp_tp_dealloc`) from `WasmProxyExportsRendering.kt`. The hand-written test fixture `ProxyTypeExports.kt` was deleted in `112b54d0` and replaced by the task output in `:python-multiplatform:wasmJsTest`. `PythonBindingsPlugin` now automatically registers `generateWasmProxyExports` and wires its output directory to `wasmJsMain` for consumers (including `:sample`). Note that `UpcallExports.kt` / `WasmExports.kt` remain hand-written for `pmp_invoke` (the general upcall dispatcher), which is distinct from the CPython proxy type slots.


9. ~~`jvmMain` unification (§8) is verified but not applied.~~ **Attempted at scale, 2026-08-18
   (`work/jvmmain`).** The premise — ~330 duplicated `actual`s collapsing into ~14 shared shape
   functions — did not survive contact with an exact per-declaration comparison: of 314 common
   `EmbedAPI.android.kt`/`EmbedAPI.desktop.kt` declarations, only 3 have literally identical
   bodies, because Android's JNI dispatch names a different callee per calling convention
   (`PyErr_ClearN`/`PyErr_ClearF` vs desktop's unsuffixed Panama handle) on almost every wrapper —
   real wiring divergence, not cosmetic duplication. Of those 3, only 1 (`toRawValue`) was actually
   movable without changing behavior; the other 2 depend on `NativeAddressValue`, whose `toString()`
   differs by platform, so hoisting them would force a behavior change. `toRawValue` and its
   `toPlatformPointer()` helper now live in `jvmMain/kotlin/python/native/ffi/EmbedAPI.jvm.kt`. The
   recipe itself (remove `inline` from the `commonMain` `expect`, single `actual` in `jvmMain`) is
   confirmed safe — compiles clean on both JVM leaves, and the documented Kotlin 2.0.20
   `Internal error in file lowering` crash did not reproduce even with `inline` deliberately left
   in for this function, tested and reverted. **(a) blocking it:** nothing left to do — the
   remaining 380+315 duplicated declarations are duplicated because they are not, in fact, the same
   code. **(b) next step:** none recommended; re-opening this would mean unifying the JNI
   convention-selection machinery itself (giving Android's dispatch the same unsuffixed names
   desktop uses), which is a design change to `bindings.kt`'s calling-convention selection, not a
   mechanical `jvmMain` move, and is not scoped here.

10. ~~**`Python3.runMain` and `Python3.runApp` are landmines, not working functionality**~~
    **— done, both of them.** (§12) `runMain` runs a module via
    `runpy.run_module(name, run_name="__main__", alter_sys=True)`, in a fresh `__main__`
    namespace, with `sys.argv` set for the duration and restored, and `sys.exit(n)` reported as
    its `Int` return rather than acted on. `runApp(args: List<String>): Int` parses the reduced
    CLI subset decided in §12 — `-c`, `-m` (delegates to `runMain`), a script path, `--` followed
    by one, with `-` and every pre-init-only `PyConfig` option (`-E -I -S -s -B -O -OO -X -W -u
    -P` among them) refused by name — and runs it with the same `sys.exit`/`sys.argv` contract.
    Neither path is a lifecycle function, so the interpreter survives both. `Py_BytesMain` is
    still never called: it reaches `Py_RunMain()` and finalizes, which is what made "one
    marshalling path away from working" the wrong description of what `runApp` needed. §12 has
    the detail.

11. **The iOS app bundle carries no Python standard library** — restated, because the previous
    wording ("the iOS *app* packaging path has no producer … no Gradle task creates it") was wrong
    about which piece is missing. `prepareIosFrameworks` *does* stage
    `sample/build/xcode-frameworks/Python.xcframework`, and the Xcode `link*Ios*` tasks depend on it.
    What is missing is the stdlib: the Xcode "Install Target Specific Python Standard Library" phase
    rsyncs from a directory that in this distribution holds only `libpython3.14.dylib`, and nothing
    in the app path sets `PYTHONHOME`. The app therefore cannot start from its own bundle. (§9 item
    4, §13 "The sample runs on iOS") **(a) blocking it:** two open questions rather than missing
    work — whether the stdlib should be staged by Gradle or by the existing Xcode phase with
    corrected source paths, and whether `PYTHONHOME` should come from the environment (as the tests
    do) or be derived on iOS from `NSBundle.mainBundle.bundlePath`, which would touch `iosMain` and
    has to not break the test task that sets the variable explicitly. Note also that the
    "Prepare Python Binary Modules" phase rewrites `lib-dynload/*.so` into `.fwork` placeholders, so
    a bundle-hosted stdlib needs BeeWare's `.fwork` importer or that phase disabled. **(b) next
    step:** decide the `PYTHONHOME` question first; the staging is mechanical once it is answered,
    and §13 records the exact `rsync` + `simctl` sequence that is known to work by hand.

### 14c. What this audit checked and could not confirm

Left as **unconfirmed** rather than guessed:

- Whether `.github/workflows/desktop.yml` includes Linux and Windows runners (relevant to item 3
  above) — not opened during this pass.
- The Maven Central / JitPack publication status implied by README's "Use Pre-Built Package"
  section: `curl` against `repo1.maven.org/maven2/io/github/thisisthepy/` returned 404 (empty
  directory listing) and JitPack's own build API reports no build record for
  `com.github.thisisthepy:python-multiplatform-mobile` — both consistent with *nothing having ever
  been published*, but a negative result from two APIs is not the same as a documented decision
  not to publish, so README now says what was actually observed rather than asserting either way.
  See §14d.
- Whether `PyList.subList`'s live-view behaviour (§12) and every other claim in §12's TODO triage
  still hold — re-read for internal consistency during this pass, not re-verified against the code
  a second time; nothing in that section contradicted what this audit found elsewhere.
- Whether the API-26 emulator this repo already runs against exercises the `PhantomReference`
  fallback (item 7 above) or something else — not traced through `Cleaner`'s own
  `Build.VERSION.SDK_INT` gate during this pass.

### 14d. README corrections made in this pass

Documentation only — nothing in `python-multiplatform/`, `sample/`, `ksp-fixtures/` or any test
was touched. For traceability, since some of these were load-bearing enough to be worth naming:

- **The "Usage" example did not match this library's API at all** — `Python3Library()`, `Pointer`,
  force-unwrapped `python`/`py`/`mathModule` variables declared nowhere. Replaced with the pattern
  `sample/src/commonMain/.../PythonDemo.kt` actually runs: `Python3.initialize()`, `PyList.fromList`
  + `PyInt.from` to publish a Kotlin list into `__main__`, `Python3.eval`/`Python3.exec` to read it
  back. This was very likely the most misleading single passage in either document — a reader
  trying it verbatim could not get past the first line.
- **"Build Manually" cloned the wrong repository** (`python-multiplatform-mobile`, with `@branch`
  syntax that is not valid `git clone` syntax anyway) and described a `/composeApp` module this
  repository does not have. Corrected to this repo's actual remote and its actual two relevant
  modules (`python-multiplatform/`, `sample/`).
- **"Use Pre-Built Package" asserted a Maven Central / JitPack release that could not be confirmed
  to exist** (§14c) and quoted a version number (`0.0.1`) that does not match what the build
  actually produces. Reworded to state what was checked and found, rather than presenting
  instructions as fact.
- **The CI badge situation, checked against README's own text: already accurate**, and did not
  need correcting — README already carries a comment explaining why the badge is omitted rather
  than a badge pointing somewhere wrong. This is the one place this audit found README *ahead of*
  ROADMAP rather than behind it (§12's old "badges point at a different repository" line was the
  stale one, fixed above).
- Left alone as already accurate: the "Supporting multiplatforms" list's entries for Android, iOS
  and macOS (only Linux/Windows got the untested caveat added), and the Template ToDo list's first
  three lines.

## 15. Can a consumer outside this repo actually use the library?

§14c/§14d established that nothing is published anywhere yet. This section is the follow-up that
was still open: given that gap, does *local* publishing even work, or would a real release hit
more breakage on top of "not published"? Tested by publishing all three components to
`mavenLocal()` and building a genuine external consumer project outside this repo
(`/Volumes/macMini/consumer-test` and `/Volumes/macMini/consumer-plugin-test`, both scratch
directories, not part of this repository) against it.

### 15a. Publishing coverage before this pass: 1 of 3 components

The library ships as three separately-consumed pieces — `python-multiplatform` (the library
itself), `python-multiplatform-gradle-plugin` (the convenience Gradle plugin, `id("io.github.
thisisthepy.python.multiplatform.bindings")`), `python-multiplatform-ksp` (the KSP processor the
plugin wires in by default). A consumer needs all three reachable from *some* Maven repository —
`in-repo` project substitution (`projects.pythonMultiplatformKsp`, used by `ksp-fixtures` and
`sample`) hides gaps in this because it never resolves anything by coordinates at all.

- `python-multiplatform`: `maven-publish` applied, `./gradlew tasks --all` lists a full set of
  `publish<Target>PublicationToMavenLocal` tasks. **Working**, with one target-level gap — see
  §15d.
- `python-multiplatform-gradle-plugin`: `maven-publish` + `kotlin-dsl` applied — but this build is
  a separate Gradle build, brought in only via `pluginManagement { includeBuild(...) }` in the
  root `settings.gradle.kts`. That wires plugin-id *resolution* inside this repo; it does not add
  the build's tasks to the root task graph. `./gradlew tasks --all` from the repo root lists no
  `python-multiplatform-gradle-plugin:*` tasks at all — a coordinator following only the root
  build's task list would conclude this component has no publishing story. It has to be published
  from inside its own directory: `cd python-multiplatform-gradle-plugin && ../gradlew
  publishToMavenLocal`. **Working once you know to look there** — worth a line in a future
  RELEASING.md, not a code fix (the separate-build shape is deliberate, see the build file's own
  comment on why the plugin needs KSP on its runtime classpath but not its compile classpath).
- `python-multiplatform-ksp`: **no publishing plugin applied at all** before this pass — plain
  `kotlin("jvm")`, nothing else. `./gradlew tasks --all` confirms zero publish tasks. This is the
  one true gap: `python-multiplatform-gradle-plugin`'s `generateCoordinates` task bakes
  `DEFAULT_PROCESSOR_COORDINATES = "io.github.thisisthepy:python-multiplatform-ksp:<version>"`
  into the plugin — the coordinate the plugin hands KSP when a consumer doesn't override
  `pythonBindings { processor.set(...) }` — and until now that coordinate named an artifact that
  could never exist anywhere. **Fixed**: `python-multiplatform-ksp/build.gradle.kts` now applies
  `maven-publish` with a `MavenPublication` built `from(components["java"])`; default
  group/artifactId/version already matched what the generated coordinates expect, so no other
  change was needed. Verified — `./gradlew :python-multiplatform-ksp:publishToMavenLocal` now
  produces `~/.m2/repository/io/github/thisisthepy/python-multiplatform-ksp/<version>/`.

### 15b. A real bug the in-repo build could never surface: `generateCoordinates` baked `"null"` as the group

Publishing the fix from §15a exposed a second, independent bug, and it is the one worth reading
carefully — nothing in this repository's own build could ever have caught it, because nothing in
this repository resolves the processor by its published coordinates.

`python-multiplatform-gradle-plugin/build.gradle.kts` generates `ProcessorCoordinates.kt` from:

    val generateCoordinates = tasks.register("generateCoordinates") {
        val coordinates = "$group:python-multiplatform-ksp:$version"
        ...

This reads as project-scoped (the file's own doc comment says "this build's own group and
version"), but the lambda passed to `tasks.register(...)` has `Task` as its receiver, not
`Project`. `Task` has a `group: String?` property of its own — a task's category label (`"build"`,
`"verification"`, ...), always null on a task nobody assigned one to — and it shadows
`Project.group` inside that block. `Task` has no `version` property, so `$version` fell through to
`Project.version` by coincidence and looked correct. The generated constant was therefore:

    internal const val DEFAULT_PROCESSOR_COORDINATES: String = "null:python-multiplatform-ksp:3.13.0"

Every external consumer relying on the default (i.e. not setting `pythonBindings { processor.set
(...) }`, which the plugin's own doc comment calls out as the in-repo-only override) would apply
the plugin, add no KSP dependency of their own, and get:

    Could not find null:python-multiplatform-ksp:3.13.0.
    Searched in the following locations:
      - file:/Users/.../.m2/repository/null/python-multiplatform-ksp/3.13.0/....pom
      - https://repo.maven.apache.org/maven2/null/python-multiplatform-ksp/3.13.0/....pom

This reproduced with a real external Gradle project (`/Volumes/macMini/consumer-plugin-test`)
applying `id("io.github.thisisthepy.python.multiplatform.bindings") version "3.13.0"` from
`mavenLocal()` — `:kspKotlin` failed with exactly that message on the first attempt.

**Fixed**: qualified both reads as `project.group` / `project.version`. Re-published, re-ran the
same external consumer with no other change — `:kspKotlin` now runs and generates the fragment
table. Both fixes are isolated to `python-multiplatform-gradle-plugin/build.gradle.kts` and
`python-multiplatform-ksp/build.gradle.kts`; nothing in `python-multiplatform/`, `sample/`, or
`ksp-fixtures/` was touched, and `desktopTest` stayed at 352/0 (1 skipped) throughout — see §14a
for the counting recipe, this run just re-applies it.

### 15c. The library itself, once published, works exactly as advertised for a plain JVM consumer

With `python-multiplatform` published to `mavenLocal()`, a from-scratch external Gradle project
(`/Volumes/macMini/consumer-test`, plain `kotlin("jvm")` + `application`, *not* Kotlin
Multiplatform) depending on `io.github.thisisthepy:python-multiplatform-desktop:<version>` and
running README's own "Usage" example verbatim:

- **Compiled clean** against the published artifact — the object-model API (`Python3`, `PyObject`,
  `PyList`, `PyInt`) resolves with no extra repositories beyond `mavenLocal()` + `mavenCentral()`.
- **Ran and produced the correct result** (`Result from Python: 56`, i.e. `sum([2,3,5,7,11]) * 2`)
  once `PYTHONHOME` was pointed at a real CPython prefix. The native `libpython` shared library
  itself *is* bundled in the published jar (`tasks.withType<Jar>` in `python-multiplatform/
  build.gradle.kts` copies it under `lib/<platform>/`) and loads correctly from the classpath with
  no extra wiring — `manager.loadLibPython()` found it without complaint. Only the interpreter's
  standard library (`encodings`, `lib/python3.14/`, ...) is missing; `Py_Initialize()` fails with
  `Fatal Python error: Failed to import encodings module` until `PYTHONHOME` supplies it.

So the object model, the FFI layer, the upcall marshalling, and the native-library packaging are
all consumer-ready today. **The one remaining blocker for a plain-JVM consumer is CPython
distribution**, and it is a real one:

- `desktopMain/README.md` already documents that `PYTHONHOME` must point at a matching stdlib
  prefix and that this is deliberate (the jar carries the loader library, not the interpreter
  tree) — so this is a known, accepted design point, not an oversight.
  But **there is no published or documented way for an external consumer to obtain that prefix.**
  Every path that produces one today (`downloadAllPythonBuilds` and its per-platform
  `downloadPython_*` tasks) is a task defined in `python-multiplatform/build.gradle.kts` itself,
  reachable only by building this repository from source. A consumer who adds the Maven dependency
  and follows README's own "Usage" section hits `Failed to import encodings module` with no next
  step documented anywhere.
- This was proven the direct way, not inferred: pointing the external consumer's `PYTHONHOME` at
  this repo's own `python-multiplatform/build/python-standalone/extracted/<version>/<platform>/
  python` (a path that only exists because this repo's own build already ran
  `downloadAllPythonBuilds`) made the same consumer project run correctly. That confirms the gap
  is exactly "no distribution channel for the stdlib prefix" and nothing else — once a consumer
  has *a* correct prefix from *any* source, everything downstream of it (native loading, object
  model, upcalls) already works.
- **Next step, not done here** (this pass fixed what was cheaply and safely fixable; packaging and
  shipping a CPython distribution is a real design decision, not a one-line fix): either (a)
  publish a small companion artifact/archive per platform that a consumer's build can unpack into
  a `PYTHONHOME`-shaped directory, mirroring what `downloadAllPythonBuilds` already assembles from
  `python-build-standalone`, or (b) document, in README's "Use Pre-Built Package" section, the
  exact python-build-standalone release URL and unpack layout a consumer needs to reproduce by
  hand. (a) is better for anyone who isn't willing to hand-roll it; (b) is the cheaper stopgap.

### 15d. `androidTarget` has no Maven publication at all

**Closed.** `androidTarget` calls `publishLibraryVariants("release")`,
`publishAndroidReleasePublicationToMavenLocal` exists, and an Android app project outside this
repository resolves the library by coordinates, builds an APK, and **runs CPython on both
emulators** — see the end of this section for the exact line it printed.

**Was:** `./gradlew tasks --all` listed `publish<Target>PublicationToMavenLocal` for every one of
`python-multiplatform`'s targets (`desktop`, `iosArm64`, `iosSimulatorArm64`, `iosX64`,
`androidNativeArm64`, `androidNativeX64`, `wasmJs`) **except `androidTarget`.** Kotlin Multiplatform
only auto-creates a Maven publication for an Android target when the target block calls
`publishLibraryVariants(...)`, and that block never did. Practical effect: **an Android app consumer
could not get this library from Maven at all**, published or not — orthogonal to §14c's "nothing is
published" finding, and it would have survived the fix for that one.

It was left open because turning the line on packages a release AAR carrying per-ABI native `.so`s
and a full CPython stdlib in `assets/`, and the size of that was a design question rather than a
mechanical fix. So the first thing this pass did was stop guessing at it.

#### The measurement, before anything was decided

`bundleReleaseAar` with `publishLibraryVariants("release")` on and nothing else changed:
**40.10 MB on disk, 137.91 MB of entries across 5898 files.**

| | compressed | raw | files |
|---|---|---|---|
| `assets/<abi>/lib/**/test` | 7.11 MB ×2 ABIs | 32.30 MB ×2 | 1790 ×2 |
| `jni/x86_64`, `jni/arm64-v8a` | 5.31 + 5.09 MB | 14.97 + 15.29 MB | 7 + 7 |
| `assets/<abi>/lib/**` (pure Python, rest) | 3.16 MB ×2 | 11.91 MB ×2 | 790 ×2 |
| `assets/<abi>/lib/**/lib-dynload` | 2.46 + 2.37 MB | 7.59 + 7.30 MB | 68 + 68 |
| `assets/<abi>/include` | 0.45 MB ×2 | 1.91 MB ×2 | 289 ×2 |
| **classes, manifest, R.txt** | **0.49 MB** | 0.52 MB | 5 |

The library is 0.49 MB of a 40 MB artifact. Two more facts fell out of measuring rather than
reading, and both decided the choice below:

- **The two ABIs' stdlibs are the same tree.** `diff -rq` over
  `assets/arm64-v8a/lib/python3.14` against `assets/x86_64/lib/python3.14` reports differences in
  exactly five files (`_sysconfigdata__*`, `_sysconfig_vars__*`, `build-details.json`) plus
  `lib-dynload`. Everything else — 790 files, 11.91 MB — is duplicated byte for byte.
- **An App Bundle strips `lib/<abi>/` per device and never strips `assets/`.** So the ABI half of
  the payload is already handled by tooling the consumer has, and the duplicated half is not.

#### The three options, and why (A) won

- **(A) ship everything.** The consumer adds one dependency and it works. Size is the price.
- **(B) leave the stdlib out and let the consumer fetch it.** Rejected on what the code actually
  is, not on taste: every path that produces a CPython prefix today
  (`downloadAllPythonBuilds`, `downloadPython_android_*`) is a task in
  `python-multiplatform/build.gradle.kts`, reachable only by building this repo, and
  `python-multiplatform-gradle-plugin` — the one thing a consumer applies — does KSP wiring and
  nothing else (`PythonBindingsPlugin.kt` is 186 lines, all of it configuration names and a
  reflective `ksp { arg(...) }`). Giving it a download/verify/stage path is a feature, not a
  switch. And it would not buy what it appears to: on desktop, `PYTHONHOME` can point anywhere on
  the machine, which is why §15c's gap is a *distribution* gap; on Android the stdlib has to end
  up **inside the APK**, so a consumer-side download stages the same ~26 MB of assets. The size
  moves, it does not shrink. Worth doing for §15c item 4; it does not answer §15d.
- **(C) split per ABI.** Wrong lever, per the measurement above: `assets/` is 26.3 MB of the 37 MB
  of entries and is exactly the part no ABI split touches, while `jni/` — the part a split would
  address — is already stripped from the installed app by AGP. It also forces consumers into
  flavour/variant matching for a library that has none.

So (A), with the payload that is *provably* never read on a device removed.

#### What was removed, and what deliberately was not

`copyAndroidPythonAssets` no longer stages two things:

- `include/python3.14` — CPython's C headers. cinterop reads them from the extraction tree
  (`targetIncludePath`), never from assets; no Kotlin source in `androidMain`, `artMain`,
  `androidInstrumentedTest` or `sample` opens an asset under `include/`.
- `test` — CPython's own regression suite, 32.30 MB raw per ABI and **38% of the AAR**. Nothing in
  this repository imports it.

Not removed, and the distinction matters: `lib-dynload` is the compiled extension modules and is
the only genuinely per-ABI part of the tree; `idlelib`, `ensurepip`, `tkinter` and `turtle` are
dead weight for most embedders but are ordinary stdlib a consumer may legitimately import. "CPython's
own test suite" and "C headers" are provable claims. "Nobody wants `tkinter`" is not.

The duplicated 11.91 MB of pure Python is left duplicated too. De-duplicating it would save 3.16 MB
compressed and change the published asset layout (`<abi>/lib/python<X.Y>`) that `MainActivity`,
`PythonOnDevice` and now README's own Android section all depend on — a breaking change to the
consumer contract for 14% of the artifact. Recorded as a measured option, not taken.

**Result: 40.10 MB → 23.42 MB** (22.04 MB of entries, 1740 files). A `Copy` never deletes what it
stopped copying and this directory is an AGP asset source root, so the task now clears it first —
without that, both trees would have kept shipping on every machine that had built once before.

#### A second, larger defect the publication exposed: an 87 MB metadata jar

Publishing to `mavenLocal` produced `python-multiplatform-3.14.7-alpha01.jar` — the `allMetadataJar`,
the artifact at the **root coordinate every Kotlin Multiplatform consumer resolves to compile
against `commonMain`** — at **87.4 MB, of which 83.0 MB was four host platforms' `libpython`**
(`lib/linux-x86_64` alone: 64.69 MB compressed, 240.23 MB raw) against 0.3 MB of actual metadata.

The cause is a scope that reads as narrower than it is: `tasks.withType<Jar> { from(extractedDir) {
... } }` sits *inside* the `jvm("desktop") { ... }` block, but `tasks` is the **project's** task
container, so it matched `allMetadataJar` too. Only the CPython libraries are now scoped to
`desktopJar`; the licence stays on every jar. `desktopJar` is unchanged at 87.7 MB, which is what
§15c verified a desktop consumer loads `libpython` out of. The root artifact is **382 KB**.

The sources jars never picked the libraries up (`desktopSourcesJar` 218 KB, `androidReleaseSourcesJar`
212 KB, neither containing a `lib/` entry), so the narrowing changes exactly one artifact.

#### Verified against a real external consumer

`/Volumes/macMini/consumer-android-test` — a plain `com.android.application` + `org.jetbrains.
kotlin.android` project outside this repository, no composite include, `mavenLocal()` first — depends
on the **root** coordinate `io.github.thisisthepy:python-multiplatform:3.14.7-alpha01` and lets
Gradle module metadata redirect it:

```
\--- io.github.thisisthepy:python-multiplatform:3.14.7-alpha01
     \--- io.github.thisisthepy:python-multiplatform-android:3.14.7-alpha01
```

Its `MainActivity` unpacks `assets/<abi>/lib/python3.14` to `filesDir`, sets `PYTHONHOME`, calls
`Python3.initialize()`, hands Python a `PyList` of `PyInt` built in Kotlin, and evaluates
`sum(kotlin_numbers) * 2`. `assembleDebug` produced a 26.8 MB universal APK, and on **both**
emulators:

```
API 36  I PyConsumer: PYCONSUMER_OK abi=arm64-v8a version=3.14.7 platform=android result=int:56
API 26  I PyConsumer: PYCONSUMER_OK abi=arm64-v8a version=3.14.7 platform=android result=int:56
```

Compiled, assembled, installed and **ran** — the JNI surface, the object model and the stdlib
packaging all work from a published artifact, with the app doing nothing the AAR does not document.

**Still open after this**, and not a blocker for the above: the bindings plugin is untested from
outside for an Android consumer — §15b verified it against a plain JVM project only.

~~The Android consumer must write the asset-unpacking and `PYTHONHOME` code itself~~ —
**closed, §15f.**

### 15f. The recipe became a helper, and the third copy of it was in this repository

§15d left the host app writing the bootstrap by hand, and named a `PythonBootstrap` helper as the
better answer. Doing it produced the argument for it that the README paragraph could not make:
**there were three copies of that recipe, and all three were wrong about the same thing.**

| copy | lines it owned | how it decided "already unpacked" |
|---|---|---|
| `sample`'s `MainActivity` | 55 | `target.exists() && target.list()?.isNotEmpty()` |
| `/Volumes/macMini/consumer-android-test`'s `MainActivity` | 26 | `File(target, "encodings").isDirectory` |
| `PythonOnDevice`, this repo's own instrumentation fixture | 35 | `File(target, "encodings").isDirectory` |

Every one of them probes the *result* for a single entry. A copy interrupted partway — the app
killed during a cold start, the device out of space — satisfies all three probes with an incomplete
stdlib, and the next launch accepts it. The failure then surfaces as an `ImportError` for whichever
module sorted after the interruption, nowhere near the cause. Three authors writing from the same
README paragraph produced the same defect three times; that is the case for a helper rather than for
a better paragraph.

`PythonBootstrap` (`androidMain/kotlin/python/multiplatform/env/PythonBootstrap.kt`) writes a stamp
— `python-multiplatform <version> <abi> <versionCode> <lastUpdateTime>` — **after** the last byte of
the last file, and deletes it *before* starting a rewrite. A partial tree therefore has no stamp,
and a stamp is never valid for a tree that is currently being replaced. The `lastUpdateTime`
component is what makes an APK upgrade restage: `filesDir` survives the install, so the previous
build's tree is still sitting there and its Python version may well be identical, which is exactly
the case a version-only check misses.

It deliberately does **no** `PYTHONHOME` validation of its own. `Python3.initialize` already runs
`PythonHomeCheck` (landed in `7bf08ca6`) on every call; a second implementation of that rule is a
second thing that can drift from the layout `Py_Initialize()` actually wants. This produces the
layout, that verifies it, and `PythonBootstrapTest.stagedPrefixIsOneThatPythonHomeCheckAccepts` runs
one against the other instead of asserting each separately. A second test asserts the value the
bootstrap sets is the value the check reads — on Android those are the same environment, because
libcore's single-argument `System.getenv` delegates to `Os.getenv` rather than to the JVM's cached
no-arg map.

#### Cost: 804 files, 20,147,280 bytes

Measured twice over, and the two disagree in a way worth keeping. From `onCreate` of the external
consumer app — fresh install, then relaunch, which is what a host app actually pays:

| | API 26 | API 36 |
|---|---|---|
| first launch (unpacks) | 237 ms | 480 ms |
| every later launch (stamp check) | 4 ms | 28 ms |

From `PythonBootstrapTest`, inside an instrumentation process that is already warm and whose page
cache is already hot:

| | API 26 | API 36 |
|---|---|---|
| first (unpacks) | 71–74 ms | 95–103 ms |
| subsequent (stamp check) | 1 ms | 1 ms |

The in-process numbers are 2–5x optimistic, so **quote the cold-start ones**. Either way it is a
factor of 17–60, which is the entire point: the alternative to a skip that can be trusted is 20 MB
of asset decompression on every cold start.

#### The copy loop was measured, not argued

It departs from the hand-written one in two ways, so
`PythonBootstrapTest.optimisedCopyIsMeasuredAgainstTheHandWrittenOne` runs both, alternating, two
samples each:

| | API 26 | API 36 |
|---|---|---|
| hand-written (`list()` per entry, `copyTo`'s 8 KB buffer) | 192 / 192 ms | 324 / 324 ms |
| `PythonBootstrap` (failed `open()` classifies, one 64 KB buffer) | 124 / 113 ms | 150 / 167 ms |

The larger half is the classification. The obvious loop asks `assets.list(child)` of every entry to
decide whether it is a directory — a native directory lookup per entry, 800-odd of them, *on top of*
the `open()` each file needs anyway. Opening first and reading `FileNotFoundException` as "directory"
folds the question into work that had to happen regardless, so only the directories pay anything
extra.

That behaviour is **not** in `AssetManager`'s documented contract, and AssetManager was
reimplemented wholesale in API 28, so `openBasedDirectoryDetectionAgreesWithListBased` re-derives
both classifications on device on whatever API level is running rather than trusting they agree, and
the benchmark asserts both loops wrote the same 804 files. Nothing is asserted about the *margin*:
an emulator's disk is the host's SSD behind qemu and is not a stable enough clock to fail a build
on.

Worth recording for whoever revisits this: reading the APK as a ZIP (`ZipFile(applicationInfo
.sourceDir)`, prefix-filtered, one pass, no recursion and no per-entry classification at all) is
very likely faster still. It was not taken because it has to handle `splitSourceDirs` for App
Bundles, and 100 ms once per install did not justify that surface.

### 15e. Summary — corrected punch list for "can a consumer use this"

1. ~~`python-multiplatform-ksp` has no publishing plugin~~ **fixed this pass.**
2. ~~`generateCoordinates` bakes `"null"` as the processor's group~~ **fixed this pass** — was
   silently broken for every external consumer of the default coordinates since the day this task
   was written; the in-repo build can never exercise this path.
3. ~~`python-multiplatform-gradle-plugin` publishes fine but is invisible to `./gradlew tasks` from
   the repo root because it's a separate included build — needs a documented publish step, not a
   code change.~~ **fixed, §15g** — one root task (`publishAllToMavenLocal`) now reaches all
   three; found while doing it that the three publish under two different versions, not one.
4. ~~No distribution channel exists for the CPython stdlib prefix a consumer's `PYTHONHOME` needs —
   confirmed to be the *only* remaining blocker for a plain-JVM consumer once 1–2 are fixed.
   Packaging/documenting one is real follow-up work, not done here. **Android is not affected**:
   §15d ships the stdlib inside the AAR, and an external Android consumer now runs. This item is
   desktop-only.~~
   — **fixed, §15h.** It was confirmed to be the *only* remaining blocker for a plain-JVM consumer
   once 1–2 were fixed, and it is now a `stagePythonHome` task on the bindings plugin: §15c's own
   external consumer runs, printing the same `56`, with no `PYTHONHOME` set by hand and nothing
   added to `desktopJar`. **Android was never affected** — §15d ships the stdlib inside the AAR.
5. ~~`androidTarget` has no Maven publication at all~~ **fixed, §15d** — `publishLibraryVariants
   ("release")`, the AAR trimmed 40.10 MB → 23.42 MB on measurement rather than taste, and verified
   end to end by an external Android app that resolves by coordinates and runs CPython on both
   emulators.
6. New, found while doing 5: the **root** Kotlin Multiplatform artifact was 87.4 MB because a
   project-wide `tasks.withType<Jar>` in the desktop target block put four host platforms' libpython
   into `allMetadataJar`. **Fixed, §15d** — 382 KB now. This one hits *every* KMP consumer on every
   platform, not only Android.
7. ~~The host app has to hand-write the asset unpack and `PYTHONHOME` setup~~ **fixed, §15f** —
   `PythonBootstrap` in `androidMain`, one call, and the three hand-written copies (`sample`,
   `PythonOnDevice`, the external consumer app) all call it now. Still open for Android
   specifically: the bindings plugin has never been exercised from an external *Android* consumer —
   §15b covered a plain JVM one.

None of §1–7 were guessed at: each was reproduced against a real external Gradle project outside
this repository (`/Volumes/macMini/consumer-test`, `/Volumes/macMini/consumer-plugin-test`,
`/Volumes/macMini/consumer-android-test`) rather than inferred from reading the build scripts alone.

### 15f. The bindings plugin, exercised from an external *Android* consumer — item 7 above, closed

§15b verified the plugin against a plain JVM consumer only. §15d's Android consumer used the
*library* by coordinates but applied no plugin at all — it hand-wrote its own KSP wiring, so it
never exercised `id("io.github.thisisthepy.python.multiplatform.bindings")`'s Android path: the
`Test`-word configuration-name matching (this doc's own `PythonBindingsPlugin.kt` comment calls
out `kspAndroidTestDebug` as the configuration that broke before that regex existed), the
`com.android.library`/`com.android.application` role inference, and the reflective `ksp {
arg(...) }` call against whatever KSP extension shape a consumer's KSP version actually exposes.
None of that is reachable through `ksp-fixtures/android`, which is an in-repo project-substitution
consumer, not one resolving the plugin by coordinates.

**Setup**: all three components published to `mavenLocal()` from this worktree —

| Component | Coordinates | Published from |
|---|---|---|
| library (root, desktop, android) | `io.github.thisisthepy:python-multiplatform:3.14.7-alpha01`, `...-desktop:...`, `...-android:...` | repo root (`-PpythonVersion=3.14.7`, matching what was already extracted) |
| KSP processor | `io.github.thisisthepy:python-multiplatform-ksp:3.13.0` | repo root |
| plugin implementation | `io.github.thisisthepy:python-multiplatform-gradle-plugin:3.13.0` | `python-multiplatform-gradle-plugin/` (its own build — §15a already noted this has to be done from inside that directory) |
| plugin marker (what `id(...)` actually resolves) | `io.github.thisisthepy.python.multiplatform.bindings:io.github.thisisthepy.python.multiplatform.bindings.gradle.plugin:3.13.0` | same |

Confirmed the marker's POM depends on exactly `io.github.thisisthepy:python-multiplatform-gradle-plugin:3.13.0`, and the KSP processor's own POM group/artifact/version match `DEFAULT_PROCESSOR_COORDINATES` verbatim — §15b's fix holds under a real Android consumer, not just the plain-JVM one it was verified against.

**Consumer**: `/Volumes/macMini/consumer-plugin-android`, outside this repository, `pluginManagement { repositories { mavenLocal(); ... } }`, a single Kotlin Multiplatform module (`jvm("desktop")` + `androidTarget()`, `com.android.library`) applying the plugin by id and version, with `pythonBindings { role.set("app"); moduleName.set("consumer_override"); excludePackages.set(listOf("com.example.consumer.internal")) }` — deliberately exercising the override knobs item 3 of the task asked about, not just the defaults §15b already covered.

**What ran, in the order a consumer would hit it:**

1. `./gradlew tasks` — plugin applies and configures cleanly against `mavenLocal()`, no coordinate-resolution error at configuration time (the `dependencies.addLater` wiring is lazy, so this step doesn't yet prove the processor dependency resolves — that's step 2).
2. `./gradlew desktopTest` — `kspKotlinDesktop` ran, generated `python.multiplatform.generated.FunctionTable` and `Fragment_consumer_override.kt` into `build/generated/ksp/desktop/desktopMain/`, compiled, and a hand-written test installed the table and **invoked `add(3, 4)` through it, getting `7L` back** — the full round trip, not just generation. `@PythonInternal` on one function and `excludePackages` on a whole package both worked: neither's function resolved in the table.
3. `./gradlew assembleRelease assembleDebug` — `kspReleaseKotlinAndroid` and `kspDebugKotlinAndroid` ran (the `Test`-word regex correctly left `kspDebugUnitTestKotlinAndroid` at `NO-SOURCE`, i.e. unconfigured, exactly as the plugin's doc comment says it should), both AARs built, and the Android leaf's own `FunctionTable`/`Fragment_consumer_override.kt` generated at `build/generated/ksp/android/androidRelease/`.
4. `desktopTest`'s 3 assertions passed: module aggregation, the override name, the invocation, and the package-level exclusion. No emulator was used — compile and assemble only, per this task's constraint.

**Nothing in this path needed a code fix.** §15a/§15b's earlier fixes (KSP publishing, the `null:` group bug) were what made step 2 possible at all; from an Android angle specifically, the plugin's `Test`-word matching and role inference — the two things §13 had already hardened against `ksp-fixtures/android` — held against a real external Android module too.

**What *did* turn up, and is not a code bug but was undocumented until now**: the consumer's own Kotlin Gradle Plugin version has to clear two independent floors, found by deliberately trying versions other than this repo's own pinned one (`libs.versions.kotlin` = `2.4.20-Beta2`):

- **Kotlin 2.1.0**: `kspKotlinDesktop` fails at configuration time with a bare
  `'org.gradle.api.provider.Property ... .getJvmDefault()'` error — no stack trace pointing at
  KSP, no mention of a version mismatch. KSP 2.3.11's Gradle plugin calls a Kotlin Gradle Plugin
  API that Kotlin 2.1.0's KGP does not have.
- **Kotlin 2.2.20** (past the floor above): `kspKotlinDesktop` now succeeds, but
  `compileKotlinDesktop` fails compiling the *generated* fragment — `Class
  'python.multiplatform.reflection.TypeTag' was compiled with an incompatible version of Kotlin.
  The actual metadata version is 2.4.0, but the compiler version 2.2.0 can read versions up to
  2.3.0.` This is unrelated to KSP: the published library itself was compiled with this repo's
  Kotlin version, and a consumer's compiler has to be new enough to *read* that metadata,
  independent of whatever KSP requires.
- **Kotlin 2.4.20-Beta2** (this repo's own version): clears both, confirmed above.

Neither failure names a required version or points at the other component — a consumer hits a
compiler internals error, not a diagnostic. **Fixed**: documented as a KDoc block on
`PythonBindingsPlugin` (`python-multiplatform-gradle-plugin/src/main/kotlin/python/multiplatform/gradle/PythonBindingsPlugin.kt`)
naming both floors and the exact errors each produces, so the next person reading the plugin's own
source finds this before hitting either error blind. A runtime version check was deliberately not
added: the two floors are two different projects' compatibility windows (KSP's plugin API surface,
and this library's own build's Kotlin version), neither owned by this plugin, and asserting a
specific range here would drift out of sync with `libs.versions.toml` the first time either
changes. No compatibility matrix beyond "match this repo's own pinned version" exists or was
produced by this pass.

**Verified not to have regressed anything in-repo**: `:python-multiplatform:desktopTest` stayed at
360/0 (1 skipped) and `:ksp-fixtures:app:desktopTest` at 64/0 after publishing and after the KDoc
change, and `:sample:compileKotlinDesktop` still compiles — the only file this pass changed inside
the repository is the doc comment above.

Updated item 7 of §15e's punch list: the bindings plugin **has** now been exercised from an
external Android consumer, resolving by coordinates, generating and invoking a real
`FunctionTable`, with the override knobs (`role`, `moduleName`, `excludePackages`) all confirmed
working — not just the defaults. What remains open from item 7 is unchanged: the host app must
still hand-write asset unpacking and `PYTHONHOME` setup.

### 15g. Publishing all three components took three different commands — now it takes one

§15a already named the shape of the problem: `python-multiplatform-gradle-plugin` is a separate
included build (`pluginManagement { includeBuild(...) }` in the root `settings.gradle.kts`), so its
tasks never join the root task graph and `./gradlew tasks --all` from the repo root shows none of
them. This section closes item 3 of §15e's punch list and records what publishing all three
actually required, before and after.

#### What three commands were needed, before this pass

| Component | Command | Where it has to run |
|---|---|---|
| `python-multiplatform` | `./gradlew :python-multiplatform:publishToMavenLocal` | repo root |
| `python-multiplatform-ksp` | `./gradlew :python-multiplatform-ksp:publishToMavenLocal` | repo root |
| `python-multiplatform-gradle-plugin` | `../gradlew publishToMavenLocal` | **inside** `python-multiplatform-gradle-plugin/` — it is a separate Gradle build, not a subproject; running it from the root resolves nothing |

Confirmed directly, not inferred from the build scripts: `./gradlew :python-multiplatform:tasks
--all` and `:python-multiplatform-ksp:tasks --all` each list their own `publishToMavenLocal`, and
`cd python-multiplatform-gradle-plugin && ../gradlew tasks --all` lists a third, separate
`publishToMavenLocal` that owns two publications (`pluginMaven`, the implementation jar, and
`pythonMultiplatformBindingsPluginMarkerMaven`, the artifact `id(...)` actually resolves against).
`./gradlew tasks --all` from the repo root shows the plugin build compiling
(`:python-multiplatform-gradle-plugin:compileKotlin`, pulled in because `includeBuild` makes the
plugin's classes available to resolve the plugin id) but exactly one `publish*` line total across
the whole root output — the plugin's publishing tasks are not among it.

#### Why the three versions aren't the two the earlier record implied

§15f's setup table reads as if `python-multiplatform-ksp:3.13.0` and
`python-multiplatform-gradle-plugin:3.13.0` were a one-off, `-PpythonVersion=3.14.7` having been
passed for the library alone in that session. Re-run from a clean checkout with **no** version
flags at all (`git status` clean, no `-P` arguments), the same split reproduces:

```
python-multiplatform*        3.14.7-alpha01   (7 artifacts: root + 6 targets)
python-multiplatform-ksp     3.13.0
python-multiplatform-gradle-plugin (+ marker)  3.13.0
```

The cause is in the repo, not the invocation — `gradle.properties` commits `pythonVersion=3.14.7`
at the root:

- `python-multiplatform/build.gradle.kts` reads it: `val configuredPythonVersion =
  project.findProperty("pythonVersion")?.toString() ?: project.rootProject.version.toString()`,
  then sets its own `version = "$pythonVersion-alpha01"` — i.e. `3.14.7-alpha01`. This is the only
  one of the three that consults `pythonVersion` at all.
- `python-multiplatform-ksp/build.gradle.kts` sets no `version` of its own, so it falls through to
  the root `build.gradle.kts`'s `allprojects { version = "3.13.0" }` — a literal, commented
  "Official Python release version", that nothing keeps in step with `gradle.properties`.
- `python-multiplatform-gradle-plugin/build.gradle.kts` is a separate build that `allprojects`
  never reaches. It hardcodes its own literal, `version = "3.13.0"`, next to a comment claiming
  it's "kept in step with the root build's version" — true only because both were last edited by
  hand at the same time. Nothing checks that they still agree.

**Judgment: not intended, in the sense that matters.** It's defensible that
`python-multiplatform-ksp` and `python-multiplatform-gradle-plugin` — pure Kotlin/Gradle tooling,
neither one embeds CPython — shouldn't move every time `pythonVersion` changes the way the library
does. But that's not what's actually happening: they're not deliberately decoupled from
`pythonVersion`, they're just two hand-copied literals that happen to still match each other and
happen to still match `pythonVersion`'s old default (3.13.0 was this repo's original embedded
version before `gradle.properties` moved to 3.14.7). The next `pythonVersion` bump moves the
library's published version and silently leaves the other two exactly where they are, with no
comment or check pointing that out at the moment it happens. Worse, the plugin and the ksp
processor are only synchronized with *each other* by the same hand-copy discipline — §15b's fix
depends on `DEFAULT_PROCESSOR_COORDINATES` (generated from the plugin build's own `project.version`)
naming a coordinate that actually exists at `python-multiplatform-ksp`'s published version; if
either literal is edited without the other, that silently reproduces §15a's original "could never
exist anywhere" failure, and nothing at configuration time would catch it — only a consumer's
`:kspKotlin` resolution would, exactly as it did before the original fix. Not fixed here (no
version was bumped, per this task's constraint) — recorded as the sharp edge in the checklist
below.

#### The fix: one root task, no change to what or how anything publishes

`build.gradle.kts` (repo root) now registers:

```kotlin
tasks.register("publishAllToMavenLocal") {
    group = "publishing"
    dependsOn(":python-multiplatform:publishToMavenLocal")
    dependsOn(":python-multiplatform-ksp:publishToMavenLocal")
    dependsOn(gradle.includedBuild("python-multiplatform-gradle-plugin").task(":publishToMavenLocal"))
}
```

`gradle.includedBuild(name).task(path)` is the documented way to depend on a task in an included
build from the including build; nothing about `pluginManagement { includeBuild(...) }` or any
publication's own configuration changed. Each component still owns its existing `maven-publish`
setup exactly as before.

#### Verified: cleared `~/.m2/repository/io/github/thisisthepy` entirely, ran the one task

`rm -rf ~/.m2/repository/io/github/thisisthepy` (this also removed the plugin marker, which lives
under the nested `io/github/thisisthepy/python/multiplatform/bindings` group, not under the
component artifact IDs), then `./gradlew publishAllToMavenLocal` from the repo root —
`BUILD SUCCESSFUL`, confirmed by exit code from a non-piped foreground run. Afterward, all of it was
back:

- `python-multiplatform` root + 6 target artifacts (`-android`, `-androidnativearm64`,
  `-androidnativex64`, `-desktop`, `-iosarm64`, `-iossimulatorarm64`, `-iosx64`, `-wasm-js`) at
  `3.14.7-alpha01`.
- `python-multiplatform-ksp` at `3.13.0`.
- `python-multiplatform-gradle-plugin` (`pluginMaven`) at `3.13.0`.
- The plugin marker, `io.github.thisisthepy.python.multiplatform.bindings:io.github.thisisthepy.
  python.multiplatform.bindings.gradle.plugin`, at `3.13.0` — the artifact `id(...)` actually
  resolves against, and the one that's easiest to forget because it lives under a different group
  path than every other artifact here.

No regression: `build/test-results` cleared first, then `:python-multiplatform:desktopTest` +
`:ksp-fixtures:app:desktopTest` — 360/0 (1 skipped) and 64/0, unchanged from baseline. Only
`build.gradle.kts` (repo root) was touched; nothing under `python-multiplatform/`,
`python-multiplatform-ksp/`, or `python-multiplatform-gradle-plugin/` changed.

#### Release checklist

1. **Decide what actually moved:**
   - Embedded CPython changed → bump `pythonVersion` in `gradle.properties`. This alone
     re-versions `python-multiplatform`'s publications (`<pythonVersion>-alphaNN`). It does
     **not** touch `python-multiplatform-ksp` or `python-multiplatform-gradle-plugin` — they have
     no code path that reads `pythonVersion` at all.
   - The KSP processor's own code changed → `python-multiplatform-ksp` has no `version` of its
     own today; it inherits the root's `allprojects { version = "3.13.0" }` literal. Giving it an
     independent version means adding a `version = ...` line to
     `python-multiplatform-ksp/build.gradle.kts` — it doesn't have one to edit yet.
   - The plugin's own code changed → hand-edit `version` in
     `python-multiplatform-gradle-plugin/build.gradle.kts`. Nothing derives it.
2. **If the ksp processor's version moves independently of the plugin build's version**, update
   both by hand and verify they still agree: `generateCoordinates`
   (`python-multiplatform-gradle-plugin/build.gradle.kts`) bakes
   `${project.group}:python-multiplatform-ksp:${project.version}` where `project.version` is the
   *plugin build's own* version — not the ksp project's. They're equal today only because both
   literals say `"3.13.0"`. Letting them drift silently reproduces §15a's original defect: no
   error at configuration time, only at a consumer's `:kspKotlin` resolution.
3. **Publish with `./gradlew publishAllToMavenLocal`** (this pass), not three separate commands.
4. **Verify against a real external consumer**, per §15a/§15d/§15f's method — the in-repo build
   resolves everything by project substitution and cannot catch a coordinate or version mismatch
   between components.
5. **Before a non-local release**, clear `~/.m2/repository/io/github/thisisthepy`, run
   `publishAllToMavenLocal`, and check the produced version directories against what
   `DEFAULT_PROCESSOR_COORDINATES` and the plugin marker's POM declare — the check this section
   just ran, repeated.
### 15h. Desktop's stdlib distribution — §15e item 4, closed

§15c found a plain JVM consumer that compiled, loaded `libpython` off the classpath with no wiring
at all, and then died in `Py_Initialize()` — `Failed to import encodings module` — because nothing
anywhere gives it a standard library. §15e recorded that as the last blocker for a desktop
consumer. This closes it.

#### First: what is actually in `desktopJar`, because the plan depended on it

The task this started from assumed the stdlib might already be in the jar, leaving only "how does
a consumer get it out". **It is not.** The published
`python-multiplatform-desktop-3.14.7-alpha01.jar` is 87.7 MB and its `lib/` tree is **14 entries,
every one a shared library**:

    lib/linux-x86_64/libpython3.14.so.1.0     251,884,016 raw   <- 64 MB of the jar on its own
    lib/macos-aarch64/libpython3.14.dylib      19,394,064
    lib/macos-x86_64/libpython3.14.dylib       20,172,552
    lib/windows-x86_64/python314.dll            6,589,440   (+ python3.dll, 2x vcruntime)

So the jar carries four platforms' *interpreters* and zero platforms' *standard library*. Any
option that "unpacks it from the jar" first has to put it there.

#### What putting it there would cost, measured

Compressed, per platform, from the trees `downloadAllPythonBuilds` already extracts:

| platform | full stdlib, zipped | per-platform native part |
|---|---|---|
| macos-aarch64 | 8.12 MB | `lib-dynload` + `config-*` + `_sysconfigdata*`: 0.12 MB |
| macos-x86_64 | 7.12 MB | — |
| linux-x86_64 | 8.23 MB | 1.22 MB |
| windows-x86_64 | 20.11 MB (`Lib` + `DLLs`, `.pdb` excluded) | `DLLs` alone 8.36 MB |
| **all four** | **43.5 MB** | |

Two facts fell out of measuring rather than reading:

- **The pure-Python stdlib is platform-independent on desktop.** `diff -rq` of macOS-aarch64's
  `lib/python3.14` against Linux-x86_64's reports **28 differences across a 1286-file tree**, and
  all of them are accounted for: `__pycache__`, `_sysconfigdata__*`/`_sysconfig_vars__*`,
  `config-3.14-*`, `build-details.json`, two `lib-dynload` modules, and pip's own `RECORD` and
  `direct_url.json`. De-duplicating on that basis would cut 43.5 MB to roughly 15 MB.
- **The `install_only` distributions already omit CPython's test suite**, so the 38%-of-the-AAR
  saving §15d found on Android does not exist here — there is nothing equivalent left to strip.

Even de-duplicated, that is 15 MB added to an artifact **every desktop consumer resolves, to carry
payload of which each one uses a quarter**. That is precisely the shape of the defect §15d
diagnosed when `allMetadataJar` reached 87.4 MB, and the fix there was to narrow the scope, not to
accept the size.

#### The deciding constraint is not size, though: a JVM cannot set its own environment

This is what actually rules out a desktop `PythonBootstrap`, and it is not a preference.

CPython reads `PYTHONHOME` with `getenv(3)`, from the native process environment. Android's
`PythonBootstrap` (§15f) sets it with `Os.setenv` and that is why the runtime shape works there.
**The JVM has no equivalent.** `System.getenv` is an immutable snapshot taken at start-up;
mutating it by reflection changes the JVM's cached map and does *not* touch the environment CPython
reads. A desktop helper would therefore have to call libc `setenv` through Panama — a different
symbol on Windows (`_putenv_s`) — and would then have set a value that `PythonHomeCheck` (`7bf08ca6`,
which reads `System.getenv`) could no longer see, on exactly the check that exists to catch a bad
`PYTHONHOME` before `Py_Initialize()` aborts the process uncatchably.

Setting the variable **as the child process is launched** has neither problem: CPython and
`PythonHomeCheck` read the same value from the same place. That is already how this repository's
own `desktopTest` works.

The C API offers no way round it either: `Py_SetPythonHome` was removed in 3.13, and
`PyConfig.home` needs the struct layout the Stable ABI deliberately does not promise — the reason
`Python3.initialize` uses `Py_Initialize()` rather than `Py_InitializeFromConfig` in the first
place.

#### The options, and why (B) won

- **(A) ship the stdlib in `desktopJar` and unpack it at runtime.** Rejected on both counts above:
  +15 MB (de-duplicated) or +43.5 MB (not) on a shared artifact, *and* it cannot set `PYTHONHOME`
  without the `setenv` route and the `PythonHomeCheck` divergence that comes with it.
- **(B) the Gradle plugin stages a prefix.** Taken. §15d rejected this for Android on the grounds
  that the stdlib has to end up inside the APK regardless, so a consumer-side download moves the
  size rather than removing it. **Neither half transfers to desktop**: a desktop `PYTHONHOME` may
  name any path on the machine, so the prefix is fetched once *per machine* and shared by every
  project on it, and nothing is added to any artifact.
- **(C) document the python-build-standalone URL and let the consumer unpack it by hand.** This is
  the status quo §15c called a blocker. It also asks the consumer to keep a version, an upstream
  release tag and a target triple in step with the `libpython` inside `desktopJar` by hand; a
  mismatch pairs a stdlib and an interpreter that disagree about ABI while both calling themselves
  3.14.7.

#### What it does

`stagePythonHome` (`python-multiplatform-gradle-plugin`) downloads the same asset
`downloadPython_*` does, verifies it against the release's `SHA256SUMS`, extracts it into a shared
cache under the Gradle user home, and sets `PYTHONHOME` on every `JavaExec` and `Test` task. The
consumer's whole diff is one `plugins { }` line.

Three details are inherited from §15f rather than rediscovered:

- **A stamp written after the last byte, deleted before a rewrite.** The root build's own
  extraction step skips when `extractDir.list()` is non-empty — the same "probe the result" defect
  §15f found in three hand-written Android copies. Verified by observation, not by reading: the
  task restages when the stamp is removed, when the stamp is stale, and when the stamp is intact
  but `lib/python3.14/os.py` has been deleted underneath it.
- **The staged tree is validated against `PythonHomeCheck`'s own marker**, not a second notion of
  "usable prefix". `stdlibMarkerRelativePath` returns the file `PythonHomeCheck.diagnose` probes.
- **The archive is downloaded to `.part` and renamed on completion**, and a cached archive is
  re-verified rather than trusted, so an interrupted download cannot present as an intact one.

Two things it deliberately does **not** do. It never overrides a `PYTHONHOME` the consumer already
set — the task is not even registered in that case — because §15c's entire finding is that setting
it by hand is what consumers have had to do, and silently replacing a working conda or system
prefix on a library upgrade would be worse than the gap. And it reads that variable through
`providers.environmentVariable` rather than `System.getenv`: the latter reads the *daemon's*
environment, so `PYTHONHOME=/their/prefix ./gradlew run` against a daemon started without it would
have been told the user had set nothing, and would have overridden them on the very task they were
configuring.

#### Verified against §15c's own external consumer

`/Volumes/macMini/consumer-test`, unchanged except for deleting its hand-set `PYTHONHOME` and
adding the plugin line:

    before  Fatal Python error: Failed to import encodings module   (the §15c gap, reproduced)
    after   Result from Python: 56                                  (no PYTHONHOME set anywhere)

`56` is `sum([2,3,5,7,11]) * 2` — the same value §15c got only after pointing `PYTHONHOME` at this
repository's own build directory. The staged prefix is 1698 files, 69 MB, from a 26 MB archive.

A second run with `PYTHONHOME` exported was confirmed to stage nothing, register no task, and
still print `56`.

#### Cost

Task time from Gradle's own `--profile`, over two independent cold/warm pairs with the cache
deleted between them:

| | `stagePythonHome` |
|---|---|
| first build on a machine (download 26 MB, SHA-256 verify, extract 1698 files) | **1.507 s** / **1.939 s** |
| archive cached, prefix rebuilt (verify + extract only) | 0.770 s |
| every later build (stamp + marker check) | **0.014 s** / **0.037 s** |

Between 40x and 107x depending which pair is read; nothing here rests on the margin. What holds is
the order of magnitude, and that the first build is paid **once per machine, not once per
project** — the cache is keyed by version + upstream release + platform under the Gradle user
home, and the next in-repo consumer to ask for it (`:ksp-fixtures:app:desktopTest`, which applies
this plugin) staged nothing at all. The download half of the cold number is this machine's link to
GitHub and should not be read as portable; the 0.770 s local half is the one that generalises.

#### What could not be checked from this machine

Worth stating plainly, because a *distribution* problem is by definition about the hosts you are
not sitting at.

- **Only `macos-aarch64` was executed.** `linux-x86_64`, `macos-x86_64` and `windows-x86_64` have
  their URL, asset name, triple and prefix layout pinned as string assertions in
  `PythonHomeStagingTest` — a wrong triple for Windows is a 404 at a consumer's first build, and
  asserting the string is the only way to catch it here.
- **Windows is the least covered of the four.** Its prefix has a different shape (`Lib/` and
  `DLLs/`, not `lib/python3.14/`), which is handled in `stdlibMarkerRelativePath` and matches the
  second layout `PythonHomeCheck` already probes — but no Windows host ran it.
- **`linux-aarch64` is staged but has never been run.** `desktopJar` carries no `libpython` for it;
  it can only work because `manager.loadFromSidecar` falls back to loading the interpreter out of
  `PYTHONHOME` when the classpath has none for the running platform. That path is real and
  deliberate (it is what the GraalVM native image already relies on), but the combination is
  untested.
- **Packaged distribution is still open.** This puts a prefix on a *developer's* machine and points
  `run`/`test` at it. An application shipped to an end user still needs its packaging step
  (`jpackage`, Conveyor, an installer) to carry a prefix and set `PYTHONHOME`; the staged directory
  is a reasonable thing for such a step to copy, but nothing here does it.

---

## 16. Bindings from resolved artefacts — the second producer, punched through on one path

`docs/design/ecosystem.md` §5b settles that bindings are produced at build time by **two** producers, split
by what they look at:

    KSP                the consumer's own source — declarations it can see being compiled
    artefact walker    everything the build resolves — third-party jars, AndroidX included

Only the first existed. This section is the second, built to the smallest shape that reaches all the
way to a Python `import` rather than to the largest shape that could be written.

### 16a. What runs today

`python-multiplatform-gradle-plugin` gained `generatePythonArtifactBindings`
(`python.multiplatform.gradle.artifact`), registered when a consumer sets three properties:

    pythonBindings {
        artifactConfiguration.set("desktopCompileClasspath")
        artifactSourceSet.set("desktopMain")
        artifactIncludePackages.set(listOf("junit.runner", "junit.framework"))
    }

It walks the resolved jars with ASM, emits one `FunctionTableFragment` per contributing artefact into
`python.multiplatform.generated.artifacts`, and an `ArtifactTable` listing them. The verified path,
end to end, is `ksp-fixtures/artifact`:

    junit-4.13.2.jar -> ASM -> ArtifactFragment_junit_junit_4_13_2 -> UpcallTable
        -> PythonProxySource -> `from junit.runner.Version import id` -> "4.13.2"

`"4.13.2"` is JUnit's own version string, compiled into `junit/runner/Version.class`; no generator or
fixture can produce it, which is why it is the value the test reads back.

`artifactIncludePackages` being empty leaves the whole thing unregistered — no task, no configuration
resolution, no generated directory. A build that only wants KSP pays nothing.

### 16b. Where the walker's output joins KSP's, and why it is a second list

Same `UpcallTable`, same `FunctionTableFragment`, same Python surface. **Two aggregators.**
`ArtifactTable` is not merged into KSP's `FunctionTable`, for three reasons:

1. **`FunctionTable` already means something.** `PackageScanFragmentDiscovery` builds it from
   `Resolver.getDeclarationsFromPackage(...generated.fragments)` — "every module in this build graph
   compiled with the processor". `ksp-fixtures/app`'s `CommonInstallTest` asserts that set exactly.
   Merging would silently change a shipped contract and make a consumer's Python namespace grow when
   they added an unrelated dependency.
2. **The ordering would have to be built the wrong way round.** For KSP to *see* a walked fragment,
   the walker would have to run before `kspKotlin<Target>` and land its source where
   `getDeclarationsFromPackage` looks. That API's behaviour for declarations present only in the
   source being processed is undocumented, and a fragment it silently missed would vanish from the
   table with no build failure anywhere.
3. **Which artefacts to bind is a consumer's decision and should be visible at the install site.**

So the install site is where they meet:

    UpcallTable.install(FunctionTable.fragments + ArtifactTable.fragments)

`ArtifactTable.registerInto()` is the additive form, for a caller who has already installed.

### 16c. The filters, judged against `PyREPL`'s

`PyREPL`'s generator (`app/build.gradle.kts`, 627 lines) is the reference implementation and its
filters were re-derived rather than copied, because it emits `.pyi` **stubs** and this emits **calls**.
A stub never has to compile.

| PyREPL | here | why |
|---|---|---|
| not `private`/`protected` | **not enough** — must be `public` | PyREPL keeps package-private members. Generated Kotlin lives in another package and cannot call one. `kotlin.text.StringsKt__IndentKt` is exactly that shape |
| drop `<init>` | kept | a constructor needs a `ReflectedClass` and a receiver handle |
| drop `Companion` | subsumed | only statics are bound, and `Companion` is an instance field |
| drop names containing `-` | **kept, and load-bearing for a different reason** | see below |
| (none) | drop non-`static` | an instance method has nowhere to get a receiver yet |
| (none) | drop unbindable types | see 16d |
| (none) | drop ambiguous overloads | see below |

**The `-` filter turns out to be load-bearing, and PyREPL's instinct was right.** A hyphen is Kotlin's
value-class mangling suffix. It cannot simply be stripped, and — more importantly — it cannot be
replaced by a type check, because *a value class erases to the type it wraps*: `Duration` is a `long`,
so `getInWholeSeconds-impl(J)J` passes any descriptor-level filter while meaning something entirely
different from `long -> long`. The mangled name is the only place the bytecode still admits it.

**Overloads are dropped entire, not arbitrated.** This is the one place the walker diverges from
`FragmentScanner.distinctByName`, which keeps the first. That rule is right for Kotlin source, where
the collisions are duplicate *views* of one declaration (an instance and a companion property of the
same name, an `expect`/`actual` pair seen twice). In a jar they are genuinely different functions:
`org.junit.Assert.assertEquals` has eight the walker could bind, and picking one — deterministically or
not — means `assertEquals(3, 3)` from Python calls whichever the sort order happened to put first,
which for that method is the deprecated `(double, double)` that always fails. Ambiguity is counted over
what *would be bound*, so an overload the type filter already declined does not make its sibling
ambiguous (`BaseTestRunner.getFilteredTrace` has two overloads, one taking `Throwable`, and is kept).

The whole of `junit-4.13.2.jar` — 380-odd classes — yields **seven** bindings under these rules. That
smallness is the design, not a bug, and `ArtifactScannerTest` pins the exact list.

### 16d. The wall: ASM is not enough for a *Kotlin* jar

The walker binds only declarations callable from Kotlin **by their JVM shape** — a Java static, or a
Kotlin `@JvmStatic`. A Kotlin top-level function is not one, and this is not a policy choice:

- `kotlin.text.trimIndent` compiles to a public static on `kotlin/text/StringsKt__IndentKt`, which is
  **package-private**. Generated Kotlin in another package cannot call it.
- The public name is the facade `kotlin/text/StringsKt`, which declares no methods of its own (it
  inherits them) and which **Kotlin cannot name at all** — there is no `StringsKt` in the Kotlin
  namespace, only `kotlin.text.trimIndent`.
- Even given the name, the bytecode does not say that `trimIndent`'s first parameter is an *extension
  receiver* rather than an ordinary argument.

The `@Metadata` *kind* is readable with ASM alone (`kotlin.Metadata` is `RUNTIME`-retained), and
`ArtifactScannerTest` pins it against the real `kotlin-stdlib`: `StringsKt` is `k=4` (multi-file
facade), `StringsKt__IndentKt` is `k=5` (multi-file part), `Regex` is `k=1`, `junit.runner.Version` has
none. The *payload* — names, receivers, property/function, value classes, nullability — is in `d1`/`d2`
and needs **`kotlin-metadata-jvm`**.

**`androidx.compose.material3` is on the far side of that line.** §5b is right that the artefact walker
is the mechanism that reaches it; what this pass establishes is that reaching it needs a metadata
reader, not more descriptor cases.

**A `kotlin-metadata-jvm` reader has since been written** (`python-multiplatform-gradle-plugin/.../
artifact/KotlinMetadata.kt`) and `pythonx.compose.material3` is a real, walked module today —
`ksp-fixtures/compose`'s `M3ProofRenderTest.kt` imports from it and drives components through it, not
through a hand-written wrapper. §16 above is otherwise silent on this, so it is recorded here rather
than left implied: reaching material3 is done, generating **calls** for it (not just names) needed the
reader this section says it needed, and it now has one.

**Whether those bindings actually draw anything was audited component by component
(`8813d8f1`, `1363b445`, `c6f73da2`, 2026-08-18), against 27 components downstream that had been
zero-byte stubs.** Verified against `M3ProofRenderTest.kt` and `docs/design/pythonx-adapter-design.md` §10
(both updated by the same round) rather than the commit messages alone: 24 of the 27 render
correctly, in three batches — ten (`horizontalDividerDrawsALine...` through
`tabRowComposesItsTabsAnd...`), ten more (`checkboxRendersItsChecked...` through
`searchBarComposesItsContent`), and four more found on re-examination of the seven originally called
unjudgeable — `DatePicker`, `TimePicker` and `SwipeToDismissBox` have positive assertions in
`stateObjectsArePassedToComposables` (each driven through the `remember_*_state` factory already
present in the walked table under its snake-cased name); the fourth, `BottomSheet`, is not a distinct
assertion in that test file but is named explicitly in §10 item 2 of the design doc as bound the same
way, which is where the "four" the commit message counts is actually enumerated. Three remain
genuinely unreachable, each pinned by its own test rather than inferred: `DropdownMenu` composes into
a Popup layer `ImageComposeScene` never captures (`popupLayersAreNotCapturedByImageComposeScene` — 0
pixels, observed); the dynamic `ColorScheme` factory is Android-only and not in the desktop jar at
all, and the class name itself resolves to a proxy type whose constructor wants a handle rather than
colour arguments, which is the error actually raised
(`colorSchemeResolvesToItsProxyTypeRatherThanAnythingCallable`); `Typography` and the shape scheme are
not declined by any filter — they are simply absent from the walked table, because `ArtifactScanner`
does not collect class constructors, which is a scanner gap rather than a property of either type.

**Of the three Compose modifiers declined for an unspellable generic type parameter
(`docs/design/pythonx-adapter-design.md` §9.4: `swipeable`, `modifierLocalProvider`, `anchoredDraggable` —
`SwipeableState<T>`, `ProvidableModifierLocal<T>`, `AnchoredDraggableState<T>`), two are now bound.**
Verified by reading the wrappers, not just the design doc: `fixture.compose
.pythonAnchoredDraggableString` (`ksp-fixtures/compose/.../PythonAnchoredDraggable.kt`, `8d4fd6a4`) and
`fixture.compose.pythonSwipeableString` (`.../PythonSwipeable.kt`, `f3b680fa`) each fix the generic
parameter to a concrete `String`, exactly the move the identifier wrapper made for `layoutId`'s
`kotlin.Any` — one hand-written wrapper per concrete type, not a fix to the underlying generic
surface, which both files' own KDoc says outright. `modifierLocalProvider` is the one of the three
still declined; §9.5 of the design doc says so and nothing in this codebase contradicts it.

Also unbound today, and for the same "no Kotlin type name to cast to" reason (`boundaryTypeOf` returns
`null` rather than falling back to `TypeTag.OBJECT`): every parameter or return that is not a
primitive, `String`, `ByteArray` or `void`. `Ljava/util/List;` has no Kotlin spelling, a
`Ljava/lang/Object;` cast checks nothing, and a value-class descriptor lies. Instance methods,
constructors and fields are unbound too — those need a `ReflectedClass` and a receiver handle, which is
`PythonProxySource`'s existing class-rendering path and a separate step.

### 16e. klib — implemented and wired, and this heading was stale for longer than the work took

§5b left this open: "On iOS, androidNative and wasm the artefacts are klibs, and whether the same walk
is possible there — and what a Kotlin declaration from a klib can be bound to at runtime with no JVM
underneath — is the open question."

Both halves were investigated against this repository's own klibs. Observed, not inferred:

**Reading is solved, by an API that already ships with the Kotlin this build uses.** A `.klib` is a zip
whose `default/linkdata/package_<fqName>/*.knm` entries are protobuf-serialised Kotlin metadata — the
package inventory is readable from the directory names alone, and the declarations need a decoder.
`org.jetbrains.kotlin.library.abi.LibraryAbiReader` in `kotlin-compiler-embeddable` (the same
`2.4.20-Beta2` this build pins; the API carries `@ExperimentalLibraryAbiReader`) is that decoder. Run
against `python-multiplatform-iosX64Main-3.14.7-alpha01.klib` it returned the manifest
(`platform=NATIVE, platformTargets=[Native(name=ios_x64)]`) and **441 top-level declarations** with
proper Kotlin qualified names — `python.multiplatform.ffi/withGIL`, `python.multiplatform.ffi/PyObject`
— already sorted into `AbiClass` and `AbiFunction`.

**And it answers exactly what ASM could not.** `AbiFunction` exposes `isSuspend`,
`hasExtensionReceiverParameter`, `isInline`, `isConstructor`, `valueParameters: List<AbiValueParameter>`
and `returnType: AbiType` — Kotlin types, not erased JVM descriptors. There is no facade problem
because there is no facade: a klib records the Kotlin declaration.

**The call side is easier too.** A generated fragment is Kotlin source compiled into the consumer's own
binary, and the klib is on its compile classpath, so calling a klib declaration is an ordinary Kotlin
call — no reflection, nothing that a closed world or a missing JVM would break. The JVM path is the
awkward one, not the Native path.

**Both of the reasons this section gave for not doing it were solved by the way it was eventually
done, and the section was never updated.** Checked against the code rather than against this
document:

- `KlibScanner` (`python-multiplatform-gradle-plugin/.../artifact/KlibScanner.kt`) does the walk,
  `@OptIn(ExperimentalLibraryAbiReader::class)` and all.
- It is **not** on the plugin's own classpath at run time. `PythonArtifactBindingsTask` and
  `PythonStubsTask` both reach it through `WorkerExecutor.scanKlibIsolated`, which runs
  `KlibScanWorkAction` in an isolated worker classloader whose classpath (`klibReaderClasspath`)
  is supplied per build. `PythonArtifactBindingsTask`'s KDoc records why: calling `KlibScanner`
  in the task's own classloader throws `NoSuchMethodError` in a real build. That worker boundary
  is the interface seam this section asked for, and it is stronger than one — an interface would
  still have put the 60 MB artefact on one classpath, and this does not.
- `KlibScannerTest` covers it against two real klibs: `kotlin-stdlib`'s, out of the pinned
  Kotlin/Native distribution, and `kotlinx-coroutines-core-androidnativearm64:1.8.1`, a real
  third-party download.

What is genuinely still open is narrower than what was written here, and worth stating exactly:

- **Nothing distinguishes "read the klib and found nothing bindable" from "failed to read it".**
  This is the sharp form of the item below, and it was found while trying to close it.
  `:ksp-fixtures:klib-artifact` *is* the product-path exercise — it walks
  `androidNativeArm64CompileKlibraries` through `PythonBindingsPlugin`, therefore through
  `WorkerExecutor.scanKlibIsolated` and its per-build reader classpath, against klibs this build
  produced with Kotlin `2.4.20-Beta2`. `:generatePythonArtifactBindings` and `:generatePythonStubs`
  both succeed on it (`EXIT=0`, 2026-08-23).

  But the output is an **empty** `ArtifactTable.fragments`, and empty is the *correct* answer for
  the namespace it walks: `WalkedKlibArtifactTableTest` pins zero deliberately, because
  `kotlinx.coroutines`' public surface is `CoroutineScope`/`Job`/`Flow`-typed extensions plus
  `@PublishedApi internal` declarations, none of them bindable. The stub task emits nothing either.
  **A successful run and a silently failed read therefore produce identical artefacts**, and the
  green build is not the assertion it looks like.

  **(a) blocking it:** nothing. **(b) next step, and it is cheap:** give that fixture one namespace
  that yields at least one binding, or assert that the scan's *decline* list is non-empty. Either
  separates the two cases, and neither needs the TestKit machinery the next item describes.

- **The in-process test path is pinned to one Kotlin version.** `KlibScannerTest` reads klibs from
  `kotlin-native-prebuilt-macos-aarch64-2.0.20`, and calls `KlibScanner` directly rather than
  through a worker, because a test JVM has no worker to hand a classpath to. Reading a klib
  produced by a *newer* Kotlin than the one the plugin was compiled against fails there with
  `org.jetbrains.kotlin.protobuf.InvalidProtocolBufferException` — observed while attempting this
  section, against this repository's own `2.4.20-Beta2` klibs. **This is a property of the test
  path, not of the product path**, which is exactly what `klibReaderClasspath` exists to avoid;
  but nothing currently asserts that the product path survives a version skew the test path does
  not.

  Driving the worker path from JUnit is not the route to that assertion: `ProjectBuilder`'s
  `WorkerExecutor` is a stub whose `submit()` throws `UnsupportedOperationException`, so it would
  take `gradleTestKit()`, a temporary project directory and a `GradleRunner` run — infrastructure
  this plugin has none of, for one assertion. The fixture route above exercises the same code for
  a fraction of that.
- A Native consumer needs the per-platform `_pm_resolve`/`_pm_invoke` bootstrap — **closed**, see
  16f item 2 and `UpcallBootstrap`.

### 16f. What is verified, and what is not

Verified: one target (`desktop`/JVM), one configuration (`desktopCompileClasspath`), one source set
(`desktopMain`), one artefact (`junit:junit:4.13.2`), on this host.

Not verified, and each is a real next step rather than a caveat:

1. ~~**More than one target.** `artifactConfiguration`/`artifactSourceSet` are single-valued and named by
   hand.~~ **The wiring half is done; what is left is not the wiring.** `artifactTargets`
   (`MapProperty<String, String>`, configuration name → source set name) makes the answer plural.
   The singular pair still works and, when it is the only pair, still gets the unsuffixed
   `generatePythonArtifactBindings`/`generatePythonStubs` names an existing build already invokes;
   every additional pair gets a task named after its source set, which is what already distinguishes
   the output directories. Setting both folds the singular pair into the map rather than registering
   it twice. `ArtifactTargetsWiringTest` pins all three properties; neutering the map read fails
   exactly the two multi-pair cases and leaves the back-compat one green.

   Deriving the map is still refused, for the reason this entry gave and one more measured since:
   this plugin carries no KGP types (the constraint that made `TEST_WORD` a name matcher), and
   Android's variant-aware configuration names do not follow the `<target>CompileClasspath` →
   `<target>Main` convention a derivation would have to assume. The caller writes the map.

   **(a) what is still blocking a second target end-to-end:** not this wiring. `ksp-fixtures/artifact`
   has one target on purpose — "the walker reads jars, and jars are what a JVM target resolves"
   (that module's `build.gradle.kts`). A second *JVM* target would exercise the plural wiring and
   prove nothing about the walk; a second *real* target is a Kotlin/Native one, and walking its
   `.klib` is 16e -- which turned out to be implemented and wired through an isolated worker
   already, as that section now records. **(b) next step:** a fixture module with a Kotlin/Native
   target and a klib on its compile classpath, not more scanner work.
2. ~~**`PythonProxySource` needs `_pm_resolve`/`_pm_invoke` bound**, which is per-platform and, in
   this repository, exists only in `python-multiplatform`'s own **test** source
   (`UpcallEntryBridge.desktop.kt`). `ksp-fixtures/artifact` rebuilds the two `ctypes.CFUNCTYPE`s by
   hand from the public `UpcallStub` addresses. A consumer has no supported route to this today; that
   is the gap, not the fixture's workaround.~~ **Closed for desktop, and the same route now compiles
   for every other in-repo target.** `python.multiplatform.ffi.upcall.UpcallBootstrap` (`commonMain`,
   `expect object { fun publishToGlobals(): Boolean }`) is the consumer-facing entry point that did
   not exist before. `WalkedArtifactPythonImportTest` calls it and the hand-rolled
   `ctypes.CFUNCTYPE`/`UpcallStub` workaround is gone from that file — `git log -p` on this commit is
   the proof, and `UpcallBootstrapTest` (`commonTest`) pins the contract (`_pm_resolve`, `_pm_invoke`,
   `_pm_release`, `_pm_cancel` all present after one call; a second call rebinds cleanly rather than
   refusing or failing).

   What each `actual` does was mostly already sitting in production code, unused by anything a
   consumer could reach:

   | target | `actual` | what it calls |
   |---|---|---|
   | desktop | new (`UpcallBootstrap.desktop.kt`) | `ctypes.CFUNCTYPE` over `UpcallStub`'s Panama stubs — the same four addresses the removed workaround used, just no longer hand-assembled per call site |
   | androidNative, iOS (`nativeMain`) | new, one line | `python.native.ffi.UpcallEntry.publish` — a complete `PyMethodDef` bootstrap that has existed in `nativeMain` production code all along; nothing outside this module's own tests ever called it |
   | Android/ART (`androidMain`) | new, one line | the same `UpcallEntry.publish`, backed by the JNI shim already built into `jni_onload.def` — ships inside the library's own `.so`, no extra consumer wiring |
   | wasmJs | new, one line | `python.native.ffi.UpcallEntry.publish` (`wasmJsMain`) over the one `@WasmExport`ed dispatcher |

   **wasmJs has one real remaining requirement, and it is the consumer's, not the library's.**
   `@WasmExport` is honoured only in the compilation that produces the final `.wasm`, so the library
   cannot declare `pmp_invoke` on a consumer's behalf — every wasmJs consumer must still write the
   three-line delegating export `wasmJsTest/UpcallExports.kt` shows, or `UpcallEntry.publish` throws
   `IllegalStateException` at the `pmpRegisterUpcall` call inside `publishToGlobals()`. This is
   structural (`docs/design/upcall-async-design.md` §12.4's finding still holds for the export itself, even
   though the *bootstrap* it blocked is no longer the gap) and not something a future revision of
   this entry can close without changing what `@WasmExport` means.

   No entry point published by any target other than desktop's is a stub: every `actual` above
   delegates to code this repository already built, tested from its own suite, and shipped — only the
   one-line public wrapper and (for wasmJs) the `commonMain` `expect`/desktop `actual` pair were
   missing. `androidMain`'s and `nativeMain`'s `UpcallBootstrap.*.kt` files are ~310 bytes because
   `UpcallEntry.publish` already does the real work; that is the same shape `UpcallBootstrap.kt`
   itself is, not a sign either is unfinished.
3. **The generated source directory is wired reflectively.** `kotlin.sourceSets.getByName(name).kotlin
   .srcDir(task)` goes through `Class.getMethod`, for the same reason `setKspArg` does. Both ends of
   the chain are Gradle types (`NamedDomainObjectContainer`, `SourceDirectorySet`); only `getKotlin()`
   is reflected. A KGP change there fails loudly at configuration time.
4. ~~**No `aar`, no klib, no project classes directory.** Anything that is not a `.jar` is skipped
   silently.~~ **The klib half closed by 16e.** `PythonArtifactBindingsTask` now routes `.klib`
   files through `KlibScanner` via the isolated worker (`PythonArtifactBindingsTask.kt:118-131`,
   `filter { it.name.endsWith(".jar") || it.name.endsWith(".klib") }`); its own KDoc states the
   remaining scope precisely: "A resolved artefact that is not a `.jar` or `.klib` — an `aar`, a
   project's classes directory — is skipped silently" (`PythonArtifactBindingsTask.kt:28-29`).
   `aar` and project classes directories remain genuinely unhandled.
