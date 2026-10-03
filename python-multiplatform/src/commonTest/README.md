# Which tests run where, and why

Test placement in this module determines coverage: a test in `commonTest` runs on every target,
a test in a platform source set runs only there. That placement has drifted from "what is actually
platform-independent" before -- `PythonProxySource.install()` was exercised only in `desktopTest`
until the generated proxies were driven from the sample and found not to install on iOS/androidNative
at all (`9040a8ce`), and `GCLeakTest` used to be duplicated across five source sets because
`src/nativeTest` existed on disk but was never wired into the source-set hierarchy. This file
records the audit that followed: what runs on which of the six execution paths, why each
platform-only file actually needs to be there, and one promotion that was tried and reverted.

## The six execution paths and how they're wired

| Execution path | Gradle task | Source-set chain |
|---|---|---|
| desktop | `:python-multiplatform:desktopTest` | `desktopTest -> jvmTest -> commonTest` |
| desktop, free-threaded | same task, `-PpythonFreeThreaded=true` | same chain, linked against `libpython3.14t` instead of `libpython3.14` |
| iOS simulator | `:python-multiplatform:iosSimulatorArm64Test` | `iosSimulatorArm64Test -> nativeTest -> commonTest` |
| androidNative | `:python-multiplatform:androidNative{Arm64,X64}Test` (custom `adb`-driving task; `compileTestKotlinAndroidNativeArm64` for a device-free check) | `androidNative*Test -> artTest -> nativeTest -> commonTest` |
| Android (ART) | `connectedAndroidTest` (instrumented, needs a device/emulator) | `androidInstrumentedTest -> jvmTest -> commonTest` |
| Android (host JVM) | `testDebugUnitTest` / `testReleaseUnitTest` | `androidUnitTest -> commonTest` (default KMP wiring; does **not** go through `jvmTest`) |

Two things fall out of this that are easy to get backwards:

- **`jvmTest` has no files of its own.** It exists only to give `desktopTest` and
  `androidInstrumentedTest` a shared ancestor below `commonTest`. Anything placed there would run
  on both, but nothing currently is (see "not done" below for why `StringMarshallingTest` isn't).
- **`androidUnitTest` and `androidInstrumentedTest` are two separate compilations of `commonTest`**,
  not one target with two names. `androidUnitTest` runs on the host JVM, which cannot load the
  arm64/x86_64 `.so`, so the interpreter itself is unreachable there -- it exists to satisfy the
  `expect`/`actual` surface (`forceGC()` has an `actual` in both), not to run the object-model
  suite. `androidInstrumentedTest` is the one that reaches a real (or emulated) CPython, needs a
  connected device, and is out of scope for this pass per the task constraints.
- **`nativeTest` was not wired into this hierarchy until `9040a8ce`.** Everything below that used
  to have `iosSimulatorArm64Test`, `androidNativeX64Test` and `androidNativeArm64Test` sourced
  independently, each with its own copy of anything that needed an `actual`.

## Survey: what's in each source set

`commonTest` (runs on all six paths) holds the entire object-model suite: `Python3Test`,
`PyObjectTest`, `PyTypeTest`, every `types/*` test, `GCLeakTest`, `RefCountTest`,
`EvalCheckpointTest`, `OwnershipLeakTest`, the upcall/reflection suites
(`UpcallTrampolineTest`, `PendingCallTest`, `PythonProxySourceTest`, `HandleTableTest`,
`UpcallTableTest`), `GilParkingTest`, `EmbedApiLowLevelTest`, `UpcallBoundaryCostTest`,
`UpcallEntryTest`, the `overhead/Benchmark*` measurement harness, `PlatformIdentityTest`,
`PyValueLazyConversionTest`, `SmokeTest`, `VersionsTest`. This is the baseline the task's
341/303/316 numbers are built from.

Platform-only source sets, and why each file actually has to be there (not "happens to be"):

### `desktopTest` (16 files) -- audited file by file, none are commonTest candidates

| File | Why it cannot move |
|---|---|
| `AsyncCompletionProbeTest` | Its own KDoc: "Desktop only... wasm has no threads at all and this test could not be written for it in any form." Uses `java.lang.Thread`, `System.nanoTime()`. |
| `AsyncUpcallCancellationTest`, `AsyncUpcallDeliveryTest`, `AsyncUpcallEarlyCancellationTest` | `java.util.concurrent.{LinkedBlockingQueue,TimeUnit}` + `java.lang.Thread`. Each has a `nativeTest` counterpart (`AsyncUpcallNative*Test`) built on `NativeThread.kt` instead -- an intentional per-platform mirror, not an oversight. The native mirrors currently cover fewer cases (1 test each vs. 3 on desktop); that gap is real but is new test-writing, not a placement fix. |
| `PythonProxyInstallTest` | Same JVM concurrency primitives as the async tests above (`LinkedBlockingQueue`, `Thread`). No native/wasm mirror exists yet -- this is the test that would have caught `9040a8ce`'s bug had it existed sooner. Writing one needs `NativeThread`-based equivalents of `installLoopHarness`/`runOnLoopThread`; out of scope here (new tests, not a move). |
| `DesktopOnlyLeakTest.desktop.kt` | `java.lang.ref.Cleaner` -- JVM-only API, name says so. |
| `GCLeakTest.desktop.kt` | The `actual` half of `commonTest`'s `expect fun forceGC()` for this target. Platform glue, not a test. |
| `FreeThreadedGCGateTest`, `GCSchedulingMeasurementTest` | Pin CPython's GC-scheduling gates against **JVM process memory** specifically (`gc_should_collect_mem_usage` reads `task_info(TASK_VM_INFO)`, which tracks the JVM's footprint in this embedder). `GCSchedulingMeasurementTest.testReentrancyDuringCheckpoint` additionally calls `python.native.ffi.UpcallStub`, which only exists in `desktopMain`. |
| `CycleCollectionTest` | `sun.misc.Unsafe` via reflection (`Class.forName("sun.misc.Unsafe")`), `Class.forName("python.native.ffi.EmbedAPI_desktopKt")`. Has its own native mirror in `iosSimulatorArm64Test` (see below) and an ART mirror in `androidInstrumentedTest`. |
| `DesktopOverheadBenchmark` | Explicitly "the desktop half of the JNI/Panama overhead comparison," mirrored against Android's `JniOverheadBenchmark`. |
| `DesktopPythonTest` | Not platform-coupled by API, but redundant: `Python3.withPython { Py_GetVersion() }` is exactly `EmbedApiLowLevelTest.versionRoundTripsThroughTheCApi` (`commonTest`), and the version-prefix check duplicates `Python3Test.versionReportsTheConfiguredRelease` (`commonTest`). Left alone -- deleting a duplicate is a different kind of change than moving a misplaced one, and mixing the two was exactly what this pass was told not to do. |
| `ReachabilityMetadataTest` | `java.lang.invoke.MethodHandle`/`MethodType` reflection over the Panama bindings. |
| `SidecarLibraryLookupTest` | `System.getenv`, `System.mapLibraryName`, desktop's native-image sidecar-library resolution. |
| `StringMarshallingTest` | Calls `internedUtf8`/`encodeScratchUtf8`/`ffiReadUtf8`, which are `internal expect` in `jvmMain` (`ShapeDowncalls.kt`) with `actual`s in `desktopMain` and `androidMain` -- not visible outside the `jvmMain` subtree at all, so `commonTest` is not an option. It **could** move to the (currently empty) `jvmTest` to also cover Android, and there is already an near-identical hand-duplicated copy in `androidInstrumentedTest` (different test framework: `kotlin.test` here, raw `org.junit` + `@RunWith(AndroidJUnit4::class)` there, and missing this file's `testMultiSlotScratchReuseLimit` case). That consolidation needs a connected Android device to verify and was out of scope for this pass ("기기는 쓰지 마라"); noted here for the next one. |
| `UpcallEntryBridge.desktop.kt` | The `actual fun bindUpcallOrNull` for this target, built on `ctypes.CFUNCTYPE` over `UpcallStub` -- platform glue, not a test. |

**Conclusion for `desktopTest`: zero promotion candidates.** Every file either touches a JVM-only
API directly, reads a JVM-specific measurement (process memory, `MethodHandle`, `Unsafe`), or is
the `actual` half of an `expect` declared elsewhere. Nothing was moved.

### `iosSimulatorArm64Test` (1 file) after a promotion that was tried, reverted, then redone

- `AsyncioAvailabilityProbeTest` -- deliberately scoped to this one target by its own KDoc: it
  measures whether the iOS stdlib archive actually ships `_asyncio.so` (it does, despite the
  xcframework itself shipping no `lib-dynload`), and is kept out of `nativeTest` on purpose so a
  trap here (wasm's `import asyncio` kills the process rather than raising, per
  `AsyncUpcallPortabilityTest`) stays isolated to one file.
- `CycleCollectionTest` moved out of this source set entirely -- it now lives in `nativeTest` (see
  below). The first attempt (recorded here previously) moved it, ran
  `compileTestKotlinAndroidNativeArm64`, and got:

  ```
  e: .../nativeTest/kotlin/python/multiplatform/ref/CycleCollectionTest.kt:79:48 Candidate 'val CPointer<*>?.rawValue: NativePtr' is inapplicable because of a receiver type mismatch.
  e: .../nativeTest/kotlin/python/multiplatform/ref/CycleCollectionTest.kt:474:55 ...
  e: .../nativeTest/kotlin/python/multiplatform/ref/CycleCollectionTest.kt:548:55 ...
  ```

  All three sites called `platform.posix.pthread_self()?.rawValue?.toLong()`. On Darwin,
  `pthread_self()` returns `CPointer<pthread_t>?` (`pthread_t` is `struct _opaque_pthread_t *`), so
  `.rawValue` resolves. On Linux/Bionic (androidNative's libc), `pthread_t` is a plain unsigned
  integral typedef, not a pointer, so the same cinterop declaration has no `.rawValue` to call. This
  is a genuine POSIX ABI difference surfaced through Kotlin/Native's `platform.posix` bindings, not
  a copy-paste mistake -- desktop and iOS never had to confront it before because neither had an
  androidNative sibling sharing this file. That attempt was reverted, with a note on what it would
  take to retry: a `currentThreadId(): Long` `expect`/`actual` seam in `nativeMain` hiding the
  pointer-vs-integer difference behind one signature.

  That seam now exists (`python.multiplatform.currentThreadId`, `nativeMain/.../ThreadId.kt`) with
  a Darwin `actual` in `iosMain` (`pthread_self()?.rawValue?.toLong() ?: 0L`) and a Bionic `actual`
  in `artMain` (`pthread_self().convert()`) -- `internal`, since it exists for this test's benefit
  and not as library API. The file now uses it at all three sites instead of the raw
  `pthread_self()?.rawValue` calls, moved cleanly into `nativeTest`, and both
  `compileTestKotlinAndroidNativeArm64` and `compileTestKotlinAndroidNativeX64` succeeded.
  `iosSimulatorArm64Test`, `desktopTest` and `wasmJsNodeTest` still pass at the same 342/318/328
  counts as before the move (desktop unaffected since the file was never there; iOS and wasmJs
  identical because nothing in the file's assertions changed, only where the thread ID comes from).
  androidNative itself was not run -- no emulator in this pass, compile-only per instruction.

- `androidInstrumentedTest` (ART) separately carries its own `CycleCollectionTest.kt` and
  `StringMarshallingTest.kt` -- a third platform-specific mirror of the same two concepts, using
  JNI-reachable APIs instead of Panama or cinterop. Three independent implementations of "does the
  cycle collector reach across the FFI boundary" is the established pattern in this codebase for
  concepts whose *mechanism* differs materially per platform (Unsafe+MethodHandle reflection on
  desktop, `platform.posix`/cinterop on native, JNI on ART) even though the *behaviour* under test is
  the same. That is different from "the same code, copy-pasted because a source set was reachable
  and nobody noticed" -- which is what `GCLeakTest`'s pre-`9040a8ce` duplication was, and what this
  pass looked for and did not otherwise find.

## The three `CycleCollectionTest` copies, and the cost of them drifting

The section above argues the three copies are justified. They are -- and they also drifted, in the
one way that matters, within a day of the third one being written. This records what happened, what
it would take to merge them, and what to do until then.

### What drifted

`643eaed9` fixed an intermittent failure in the `nativeTest` copy: a test that closes a cycle ends
holding the proxy's last reference in a Kotlin/JVM object, and once `tearDown()` drops the handle
table's root that reference is owed to the platform's cleaner thread, which can only pay it once it
is given the GIL. `testDeallocOnAThreadCPythonCreated` is the only test in the file that gives the
GIL up mid-measurement (`_t.start()`/`_t.join()`), so a leftover landing there removes a reference
from the proxy type between its two readings, and the test reports a `tp_dealloc` imbalance that
never happened. The fix was to release each proxy under the GIL the test already holds
(`disposeProxy`), settle before the first reading, and **assert that nothing was owed** so that a
reintroduction fails loudly instead of intermittently.

That fix went into `nativeTest` only. The `androidInstrumentedTest` copy carries the same test and
the same three cycle-closing tests, and kept the debt for thirteen hours until it produced
`tp_dealloc ran on a CPython-created thread but did not balance the instance's reference to its
heap type expected:<5> but was:<2>` -- a drop of exactly three, which is exactly how many tests in
that file leave a proxy behind. Both copies now carry the fix.

The failure is rare: it needs an ART collection to fire *inside* the measuring test's `withGIL`,
after the first reading, so that the cleaner is still blocked when the worker hands the GIL over.
Twenty full-suite instrumented runs (ten each on API 26 and API 36) on the unfixed code produced
none. **The debt behind it is not rare**: with the disposals removed and the settle step left in,
the probe reports 3 owed on both API levels on every run. That asymmetry is the argument for
keeping the settle-and-assert step -- it turns a race that hides for twenty runs into a number that
is the same every time.

`desktopTest`'s copy also creates the debt (its `Node.close()` existed for this and was never
called) and now disposes of it too. It has no settle step, because it has nothing that could
observe one: no test in that file gives the GIL up between two readings. That is recorded in the
file's KDoc, along with the condition under which it stops being true.

### The copies are not the same test three times

| Test | desktop | native (iOS + androidNative) | ART |
|---|---|---|---|
| `testCycleCollectionByGC` | yes | yes | yes |
| `testHandleReleasedWhenProxyDiesWithoutCycle` | yes | yes | yes |
| `testHandleSurvivesWhenTraverseReportsNothing` (negative control) | **no** | yes | yes |
| `testDeallocOnAThreadCPythonCreated` | **no** | yes | yes |
| `testCycleCollectedOnAThreadCPythonCreated` | **no** | yes | yes |
| `traverseReportsTheHeldPointerForARegisteredClass` | no | no | yes (JNI-only concern) |

Desktop is missing the negative control and both foreign-thread tests. The foreign-thread pair is a
genuinely different question there -- a Panama upcall stub entered from a thread the JVM has never
seen is not the same mechanism as `AttachCurrentThreadAsDaemon` or an unattached Kotlin/Native
callback -- so that is new test-writing rather than a placement fix, and it is not done. It is,
however, the reason desktop currently cannot exhibit this flake, and the reason a settle step there
would be guarding nothing today.

### Can they be merged?

Most of the body could be, and less is missing than the three files suggest. Everything the tests
call into CPython with -- `PyObject_CallObject`, `PyRun_SimpleString`, `Py_DecRef`,
`PyImport_ImportModule`, `PyObject_GetAttrString`, `PyDict_SetItemString` -- is already an `expect`
in `commonMain/python/native/ffi/EmbedAPI.kt`, and `PyObject(addr.toNativePointer()!!, borrowed =
false)` already works unchanged on all three (the desktop copy reaches the same constructor through
`Class.forName` reflection, which nothing appears to require -- `Long.toNativePointer()` is a public
`actual` in `desktopMain` and the constructor is public; the other two call both directly).

Exactly three operations are not common, and they are the ones each copy hand-rolls differently:

1. **storing a handle into a proxy's relative type data** -- `sun.misc.Unsafe.putLong` on desktop, a
   `CPointer<LongVar>` store on native, `ProxyTypeFactory.setHandle` on ART;
2. **reading an object header's `ob_refcnt`** -- `Unsafe.getLong` (plus the free-threaded
   two-header form, which only desktop has to handle), a `LongVar` load, `bindings.obRefCnt`;
3. **the calling thread's identity** -- `currentThreadId()` (`nativeMain`, `internal`) versus
   `Thread.currentThread().id`.

(1) and (2) are already implemented inside every platform's `ProxyTypeFactory`/`bindings` --
`PyObject_GetTypeData` exists in all four `bindings` objects -- so promoting `setHandle`, `handleOf`
and an `obRefCnt` equivalent onto the `expect object ProxyTypeFactory` (which today declares only
`createProxyType`) is wiring, not new mechanism. (3) needs the `nativeMain` seam widened to a
`commonTest` `expect`.

Two things still block a straight move to `commonTest` afterwards, and both have precedent for how
to handle them:

- **wasmJs has no threads at all.** The two foreign-thread tests cannot exist there in any form, and
  `AsyncUpcallPortabilityTest` is the standing record of what happens when a wasm-hostile test is
  placed hopefully. This needs the `expect val` gate `GCLeakTest` already uses for
  `cleanerReleasesAutomatically`.
- **`androidUnitTest` is a second compilation of `commonTest` on a host JVM that cannot load the
  `.so`.** Anything moved has to compile there; it will not run against an interpreter.

The desktop free-threaded build is a third wrinkle: its `typeRefCount()` reads a different object
header (`ob_tid` at offset 0, count split across `ob_ref_local`/`ob_ref_shared`) and needs a
`PyGC_Collect()` on either side of the instantiation loop to materialise deferred references. That
belongs behind the `obRefCnt` seam rather than in the test, which is another argument for (2).

**Assessment: mergeable, worth doing, and not a small change** -- it moves three operations into
production `expect`/`actual` surface, which is a wider blast radius than the flake that prompted it.
Not attempted here.

### Until then: how to stop the three drifting again

- The settle-and-assert step is the drift detector, not just the fix. Both copies that can observe
  the debt now assert it is zero, with the measured non-zero value in the message, so removing a
  `disposeProxy` call fails deterministically on the next run instead of once every few dozen.
- **The invariant to carry to any new test in any of the three files:** if a test reads a reference
  count across a point where it releases the GIL, it must first settle pending finalisation and
  assert nothing was owed. Every test that closes a cycle must release its own proxy under the GIL
  it already holds.
- A fix to one copy is a fix to three. The three files are `desktopTest/`, `nativeTest/` and
  `androidInstrumentedTest/` under `kotlin/python/multiplatform/ref/CycleCollectionTest.kt`; the
  method names are deliberately identical across them (see the table above), so
  `grep -rn "<methodName>" python-multiplatform/src/*/kotlin/python/multiplatform/ref/CycleCollectionTest.kt`
  answers "did this land everywhere" in one command.

## Reverse direction: commonTest tests that could be meaningless on some platform

The established gate for this is `commonTest`'s `expect val` / `expect fun` pattern --
`GCLeakTest.kt` gates on `cleanerReleasesAutomatically` and an `expect class CollectorTestResult`
so wasm's promise-based finalisation and every other target's synchronous one can share one test
body. `WasmFinalizationTest` (`wasmJsTest`) is the standing control that proves the wasm adapter
actually awaits the returned `Promise`, so a regression can't silently stop running those
assertions.

No new case of this kind was found. The baseline run (341/303/316, all green) covers every
`commonTest` file across desktop, iOS simulator and wasmJs already, including the ones that touch
threading (`GilParkingTest`, `PendingCallTest`, `AsyncUpcallPortabilityTest`,
`PythonProxySourceTest`, `overhead/BenchmarkTest`, `RefCountTest`, `EvalCheckpointTest`) -- if one of
them were meaningless on a given target it would show up as a failure or a hang there, not as a
silent pass, and none did. `AsyncUpcallPortabilityTest` is itself the existing record of the one
trap this category has produced before: `import asyncio` killing the wasm instance outright rather
than raising, which is why the async upcall tests stay off `commonTest` and in per-platform files
instead.

## What this pass did not touch

- The `NativeThread`-based async upcall mirrors in `nativeTest` cover fewer cases than their
  `desktopTest` originals (1 test vs. 3, and `PythonProxyInstallTest` has no native mirror at all).
  Real coverage gap, but writing new tests is not a placement fix.
- `StringMarshallingTest`: `desktopTest` and `androidInstrumentedTest` hand-duplicate the same
  four cases, and the Android copy is missing `testMultiSlotScratchReuseLimit`. A `jvmTest`
  consolidation is plausible (the underlying `internal expect` already spans both) but needs a
  connected device to verify and was out of scope here.
- `DesktopPythonTest` duplicates two `commonTest` assertions already in `EmbedApiLowLevelTest` and
  `Python3Test`. Left in place; removing a duplicate is a different change from moving a misplaced
  one.

## Validation

```
rm -rf build/test-results/{desktopTest,iosSimulatorArm64Test,wasmJsNodeTest}
./gradlew :python-multiplatform:desktopTest :python-multiplatform:iosSimulatorArm64Test :python-multiplatform:wasmJsNodeTest --console=plain
./gradlew :python-multiplatform:compileTestKotlinAndroidNativeArm64 --console=plain
```

Result after the `CycleCollectionTest` revert: desktop 341/0 failed/1 skipped, iOS simulator
303/0, wasmJs 316/0 -- identical to the pre-existing baseline, since nothing was ultimately moved.
`compileTestKotlinAndroidNativeArm64` succeeds.
