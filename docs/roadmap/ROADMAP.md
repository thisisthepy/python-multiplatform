# ROADMAP

**What is left to do in this repository, and nothing else.**

- **Behaviour status**: what is implemented, partial or planned, with the tests that assert it,
  is in [`../SPEC.md`](../SPEC.md). This file does not repeat it; it lists the work that remains and
  points at the test or document that proves the part already done.
- **History**: how each item was found, measured and closed, stays in git. The last full version
  of the old progress log is `4ed98143:docs/roadmap/ROADMAP.md`; the whole trail is
  `git log -p -- ROADMAP.md docs/roadmap/ROADMAP.md`.
- **Section numbers 1–16 are the old ones**: kept because other files cite them ("ROADMAP §9",
  "§15d", …). A closed section keeps its heading and one line saying where its evidence is. The old
  sub-sections (§2a…§16f) are mapped at the head of each section.
- Each remaining item says: what is left, its status (**open**, not started or not working;
  **partial**, part done, the rest named; **unverified**, could not be decided from code and tests),
  and the evidence path. Paths are relative to the repository root; `PM` = `python-multiplatform/src`,
  `GP` = `python-multiplatform-gradle-plugin/src`.
- Established against code and tests on 2026-10-03 (`develop` @ `5db4168c`) **without running
  Gradle**. A cited test is one that exists and asserts the thing; whether it passes in this revision
  was not observed here.

---

## 1. Release the GIL after initialisation

**Closed.** `Python3.initialize()` parks its thread state with `PyEval_SaveThread()` unconditionally
(`PM/commonMain/kotlin/python/multiplatform/ffi/Python3.kt`, `initialize`); background cleaners
release through `withGIL` (`PM/commonTest/.../ref/GCLeakTest.kt`).

- **1.1 A test that fails when a C API call runs without the GIL, partial.** Every C API call is
  supposed to hold the GIL (AGENTS.md §16), and three past regressions were exactly an unguarded call
  that only crashed once the GIL was really released. The only direct test,
  `PM/commonTest/.../ffi/GilParkingTest.kt`, asserts "does not crash". What is left is a guard that
  names the unguarded call (SPEC C-4). Note: SPEC C-4's sentence "Releasing the GIL after
  initialisation is not enabled" contradicts the code above.

## 2. Finish the Android JNI surface

**Closed.** 363 of 367 `external fun` are registered through `RegisterNatives`; the four exceptions
are deliberate (`ffiAllocUtf8`/`ffiFreeUtf8`/`ffiReadUtf8`, `echoCriticalNamed`). Evidence:
`PM/androidInstrumentedTest/kotlin/python/native/ffi/JniWiringTest.kt`,
`PM/desktopTest/kotlin/python/native/ffi/JniCallConventionClassificationTest.kt`, and the
`commonTest` suite running on device (`connectedDebugAndroidTest`, see SPEC §0).

## 3. Classify the remaining functions leaf vs re-entrant

**Closed.** The classification is derived on every desktop build by
`JniCallConventionClassificationTest`; the procedure is in `PM/androidMain/README.md` and
`docs/investigations/jni-call-convention-audit.md`. Measured reason no function was promoted to
`@CriticalNative`: promoting costs +16.54 ns on API 34 and +25.73 ns on API 36.

- **3.1 `JniWiringTest.bothListGetItemConventionsAgree` on a device, unverified.** The
  `PyList_GetItem` `@FastNative` twin was compile-verified when added; whether this assertion has
  passed on `pmp_api26` and `pmp_api36` is not recorded per test. Undecided registrations (65 at the
  last count) stay on ordinary JNI by default, which is the safe direction; no action is needed for
  them.

## 4. Automatic reference release

Closed for the ordinary case: wrappers release on GC on every target
(`PM/commonTest/.../ref/GCLeakTest.kt`, `OwnershipLeakTest.kt`, `PM/jvmTest/.../ref/PhantomCleanerRegistryTest.kt`,
`PM/androidInstrumentedTest/.../ref/AndroidCleanerPathTest.kt`), and cross-boundary cycles with a
Python-side edge are collected (§7, SPEC M-3).

- **4.1 Cycles that close entirely on the Kotlin side, open.** `HandleTable` holds every entry
  strongly by construction (`PM/commonMain/kotlin/python/multiplatform/reflection/HandleTable.kt`,
  KDoc "This table leaks by construction"), so a cycle with no Python-side edge for `tp_traverse` to
  report never collects. The mechanism (weak table entries, the proxy supplying the strength) is
  named but not worked through in `docs/design/object-lifetime.md`.
- **4.2 Wrappers that outlive `Py_Finalize()`, open (known limitation).** Their cleaners see
  `!Python3.isInitialized` and skip the release (`Python3.finalize`), which is safe at shutdown but
  leaves stale pointers if the interpreter is ever initialised again. No re-initialisation test
  exists; `finalize` is `internal` and documented as "will not be able to re-initialize".

## 5. Composition (조립)

String-carrying operations: **closed**, interning beats composing on every API level (`getAttr`
160 ns on API 36, 167 ns on API 26 interned; `docs/design/marshalling-design.md`).

- **5.1 Bulk iteration through a composed call, open (optional optimisation).** Composition pays
  only where crossings scale with N: `list → LongArray` of 1000 elements measured 11.0x faster
  composed on API 36 hardware (50 065.89 → 4 532.55 ns,
  `PM/androidInstrumentedTest/kotlin/python/native/ffi/CompositionBenchmark.kt`). The probe
  (`asmListToArray`, `PM/androidMain/.../bindings.kt`) exists; no production path uses it. Also left:
  `PM/artMain/kotlin/python/multiplatform/ffi/JniExport.kt` still exports composed `Python3`
  functions that nothing on the JVM side calls, decide whether to wire or delete them.

## 6. Composition on desktop, measured, and the answer is no

**Closed** (composing would buy ~10 % of an `exec`; not worth new native targets).

- **6.1 `DesktopOverheadBenchmark.stringMarshallingShareOfARealisticCall` is `@Ignore`d, open.**
  Running `exec` 200 000 times aborts the JVM inside `PyDict_New`; clearing the error indicator on
  failure paths recovered most of it but the abort's cause is not pinned
  (`PM/desktopTest/kotlin/python/native/ffi/DesktopOverheadBenchmark.kt`). Re-enable only once
  understood (SPEC X-1).

## 7. Python → Kotlin binder (upcalls)

Closed in its core: build-time table, KSP generator, one entry point per platform, generated proxies,
`tp_traverse` handle slot on all five targets, GraalVM native image (SPEC U-1…U-6, M-3, M-4;
`ksp-fixtures/app/src/desktopTest/`, `PM/commonTest/.../reflection/UpcallTableTest.kt`,
`PM/*Test/.../ref/CycleCollectionTest.kt`). Upcall cost is measured
(`PM/commonTest/.../reflection/UpcallOverheadTest.kt`, `PM/commonTest/.../ffi/upcall/GeneratedProxyCostTest.kt`).

- **7.1 Generated proxies exercised outside desktop, partial.** SPEC U-3: the full proxy behaviour
  (construct, methods, properties, companions) is asserted on desktop only; outside it there are
  `ksp-fixtures/app/src/androidNativeArm64Test/.../NativeSmokeTest.kt` and
  `ksp-fixtures/android/.../GeneratedAndroidTableTest.kt`. The KSP-generated-proxy cycle proof
  (`ksp-fixtures/app/src/desktopTest/.../RefHolderCycleCollectionTest.kt`) likewise exists on desktop
  only, because `ksp-fixtures/app` has no iOS or device leaf; other targets prove the slot with a
  hand-built subclass (`PM/nativeTest/.../CycleCollectionTest.kt`).
- **7.2 Extension functions as methods on KSP-generated proxies, partial.** SPEC U-7: asserted for a
  hand-written table (`PM/commonTest/.../pythonx/PythonxAdapterTest.kt`) and for walked jars
  (`ksp-fixtures/artifact/.../WalkedArtifactComposeModifierTest.kt`); not for a table KSP generated.
- **7.3 Incremental KSP, open (deferred design change).** A one-file change regenerates the
  module's whole `Fragment_<module>` (100 % dirty, measured with `ksp.incremental.log`). The only
  route is per-file fragments, which changes fragment naming and `UpcallTable`'s duplicate detection;
  `docs/design/upcall.md` §2.7.
- **7.4 Binary-size cost of blacklist exposure, open (product decision).** A table referencing every
  `public` declaration defeats dead-code elimination: ≈1.84 KB per entry in a stripped
  `androidNativeArm64` binary (200 synthetic entries, 368 640 bytes; `docs/design/upcall.md` §2.6).
  The measurement informs, but does not settle, whether that is acceptable.
- **7.5 Silent exclusions without a KSP warning, open.** A `suspend` function *type* in a signature
  now warns (`FragmentScanner.warnUnexposableType`); a declaration dropped by
  `BindingPolicy.isExposedFunctionShape` (an extension receiver, type parameters) is still dropped with
  no warning. `docs/design/upcall.md` §8 item 4.
- **7.6 Kotlin calling a Python override, open.** Python can subclass a proxy, but a Kotlin caller of a
  virtual method never reaches the Python override; nothing implements or asserts it
  (`docs/design/upcall.md` §4.4).
- **7.7 `BYTES` marshalling is per item, open.** `PyBytes_AsStringAndSize` is not declared in
  `EmbedAPI.kt`, so a `ByteArray` crosses one byte at a time; correct (NUL bytes survive, SPEC U-2) but
  slow (`docs/design/upcall.md` §3.3).
- **7.8 Fragment discovery from transitive and published artefacts, unverified.** Discovery is proven
  for project modules and a `.klib` (`NativeSmokeTest`), not for a fragment arriving through a
  transitive Maven dependency (`docs/design/upcall.md` §2.9).
- **7.9 Proxy-cost tables predate the 100 000-iteration warmup, open (re-measure).**
  `docs/design/upcall.md` §7.5/§7.6 absolutes were taken before the warmup fix; re-cut with
  `GeneratedProxyCostTest` through `./benchmarks/cost-table.sh`. Android/androidNative proxy-cost rows
  are missing.

## 7b. Finish `PyValue`

**Closed** for the eager and lazy paths (`PM/commonTest/.../conversion/ConversionTest.kt`,
`PyValueLazyConversionTest.kt`). `complex`, builtin subclasses, `memoryview` and user-defined classes
are refused by design (SPEC O-7).

- **7b.1 NATIVE conversion of a user-defined object, open (design question).** `pyObjectToNative`'s
  fallback returns `str(obj)`, which is lossy and one-way. Three candidates, `__dict__` projection,
  an upcall-table handle, or documenting NATIVE as builtins-only, are written at the branch
  (`PM/commonMain/kotlin/python/multiplatform/ffi/types/collections/CollectionSupport.kt`,
  `TODO(open design question)`), with what to measure first.

## 8. `jvmMain` unification

**Closed, nothing further recommended.** Of 314 common declarations only 3 have identical
Android/desktop bodies and only `toRawValue` was movable (now in
`PM/jvmMain/kotlin/python/native/ffi/EmbedAPI.jvm.kt`); the rest differ in real wiring (per-convention
JNI callee names). Re-opening it means redesigning Android's convention selection, not moving code.

## 9. Free-threading

3.14t runs the whole desktop suite with `-PpythonFreeThreaded=true` (opt-in; `gradle.properties`
default `pythonFreeThreaded=false`): 236 tests, 0 failures, 1 skipped when last recorded, the same
count as the GIL build (table at `4ed98143:docs/roadmap/ROADMAP.md`:1013; SPEC T-2). Deferred deallocation is
drained by the eval-loop checkpoint (`PM/commonTest/.../ref/EvalCheckpointTest.kt`,
`docs/investigations/gc-scheduling-investigation.md`); `FreeThreadedGCGateTest` guards the GC probe.
The 3.15 migration (`PyWeakref_GetRef`, the two removed functions) is done
(`PM/commonTest/kotlin/python/native/ffi/EmbedApiLowLevelTest.kt`), and 3.15.0rc1 checksums, including
free-threaded ones, are pinned in `python-checksums.properties`.

- **9.1 `autoDrainInterval`'s default on the GIL build, open (decision).** Today
  `if (BuildConfig.pythonFreeThreaded) 32 else 0` (`Python3.kt`). Off means a pure C-API embedder never
  runs the cyclic collector: 20 000+ cyclic objects left with the automatic path off against ~1 970
  with it on (`docs/investigations/gc-scheduling-investigation.md` §6–§7). On means a `__del__` or
  weakref callback can run at a `withGIL` exit the caller did not ask to yield from. The checkpoint
  costs ~128 ns over a 154.6 ns `withGIL` floor, ~4 ns amortised
  (`EvalCheckpointTest.testCheckpointCostAgainstTheAlternatives`). A one-line change once decided.
- **9.2 Free-threading beyond desktop, open (blocked upstream).** No free-threaded CPython prebuilt
  exists for Android (python.org ships `libpython3.14.so`/`3.15.so` only) or iOS (neither BeeWare
  nor python.org defines `Py_GIL_DISABLED`), checked 2026-08-12. SPEC N-1.

## 10. WASM

The target runs the shared suite under Node and a browser subset under Chromium; lifetimes, upcalls,
cycles, interning and `Py_ssize_t` are closed (`PM/wasmJsTest/`: `WasmFinalizationTest`,
`WasmCycleCollectionTest`, `WasmPySsizeTBoundaryTest`, `WasmBrowserRuntimeTest`,
`WasmSelectorsImportTest`); the plugin stages the runtime for consumers
(`GP/main/kotlin/python/multiplatform/gradle/WasmBrowserRuntimeStaging.kt`,
`GenerateWasmProxyExportsTask.kt`). The interpreter is built by `tools/wasm/build-cpython.sh`
(`docs/platforms/wasm-design.md`). Old §10c/§10d (browser test, CI split) are closed; their open
remainder is 10.1.

- **10.1 The wasm test suite does not run in CI, open.** `.github/workflows/wasm.yml` runs the
  `test` job only when the repository variable `WASM_RUNTIME_URL` is set. It is not set (observed
  2026-10-03: `gh variable list` empty; the latest `develop` run shows "WasmJS Node + browser tests:
  skipped", compile job green). What closes it is uploading the
  `python-multiplatform-wasm-runtime` zip (7.0 MB) somewhere a runner can fetch and setting the
  variable; see also 15.1.
- **10.2 `suspend` upcalls and `asyncio` on wasm, open.** SPEC U-5 is `planned` on wasm (no threads),
  and `import asyncio` traps the instance (`asyncio.run` wants `socketpair`); the sample skips
  section 7 there (`sample/src/wasmJsMain/.../bindings/ProxyDemo.wasmJs.kt`).
- **10.3 A wasm consumer must hand-write the `pmp_invoke` export, open.** `@WasmExport` is honoured
  only in the compilation that produces the final `.wasm`, so the library cannot export the general
  upcall dispatcher for a consumer; `GenerateWasmProxyExportsTask` generates only the three
  proxy-type slots. Without the three-line export (`PM/wasmJsTest/.../UpcallExports.kt` shows it),
  `UpcallBootstrap.publishToGlobals()` throws. Generating it from the plugin, as was done for the slots,
  is the obvious step.
- **10.4 A consumer's bundle in a real browser / production bundler, unverified.** The only browser
  execution evidence is the in-repo `:sample` and `wasmJsBrowserTest`. An external consumer
  (`consumer-plugin-android`) was verified to *build* `wasmJsBrowserDistribution` with the runtime
  staged by the plugin; it was never run in a browser, and no production bundler other than the
  Kotlin Gradle webpack setup was tried (also `docs/design/ecosystem.md` §5 "Unverified").
- **10.5 The wasm CPython build lacks lzma, zstd and OpenSSL, open.** Listed as "NOT DONE" in
  `tools/wasm/build-cpython.sh`'s header; ABI-sensitive per the Pyodide flag list, though they did not
  gate the one compiled wheel tested (`WasmCompiledWheelTest`).
- **10.6 The sample's wasm upcall line (`presses x3 = 0`) was never cross-checked against desktop,
  unverified, minor.**

## 11. Build wiring

**Closed.** Android staging tasks are wired by `configureEach` and `dependsOn`, no
configuration-time copies or `whenTaskAdded` remain (`python-multiplatform/build.gradle.kts`). Old
§11b (Android runs `commonTest`) is **closed**: `androidInstrumentedTest` depends on `commonTest`.

## 12. Smaller known items

Closed from the old list: Sigstore verification (`-PverifyPythonSignatures=true`), the Android
pre-release URL, `PyList.subList` (a live `PySubList`), the `TODO` triage, `runMain`/`runApp`
(`PM/commonTest/.../ffi/RunMainTest.kt`, `RunAppTest.kt`), Android `TMPDIR`
(`PythonBootstrapTest.initializeLeavesTempfileUsable`), the `EmbedAPI.kt` section order (sections
27–29 now sit in docs order), stale test headers, the sample revisit.

- **12.1 `Python3.finalize` should call `Py_FinalizeEx`, open.** It still calls `Py_Finalize()`
  (`Python3.kt`, `finalize`); `Py_FinalizeEx`'s `expect` and every `actual` already exist. Blocked
  only by finalisation being untestable in a suite that shares one interpreter (its only caller is
  `PM/artMain/.../ffi/JniExport.kt`).
- **12.2 `TMPDIR` for androidNative inside an app, and on an iOS device, open / unverified.** The
  androidNative suite passes only because `adb shell` sets `TMPDIR=/data/local/tmp`; a binary shipped
  in an app would have neither `/tmp` nor that variable. iOS passes on the simulator because `/tmp`
  is the host's; a device needs `NSTemporaryDirectory()`, never run. Probe: the `scriptPath…` cases
  of `RunAppTest`.
- **12.3 Linux and Windows desktop have never run the suite, open.** `.github/workflows/desktop.yml`
  has `os: [macos-latest]` only, and no log anywhere shows a Linux or Windows run (SPEC P-2, N-4). Adding
  the two runners to the matrix is the step.
- **12.4 CI on `develop` is not green, open (observed 2026-10-03).** All four platform workflows now
  run on GitHub Actions (the old "CI has never run" is closed). The latest `develop` Desktop CI run
  failed one test, `PyValueBytesConversionTest.bytesConversionCostIsPaidOncePerValueAndIsReportedAgainstStr`
  (a timing assertion: 1000 cached reads must cost less than one conversion), while the same commit's
  PR run passed, a timing assertion that is not stable on a shared runner. Decide whether such
  assertions belong in CI.
- **12.5 BeeWare's iOS archives (≤ 3.14) carry no signature or checksum, not fixable here.** The
  SHA-256 lockfile pin is the only integrity check for that source; python.org's signed XCframework
  replaces it from 3.15. python-build-standalone likewise publishes no per-file signature
  (`SHA256SUMS` only). `docs/platforms/python-version-acquisition.md` §5.

## 13. The sample, and the AGP version that shapes it

The sample runs on desktop, Android (both emulators), the iOS simulator and in a browser; the bindings
plugin applies to Android modules (AGP 8.10.1); `ksp-fixtures/android` exists; the generated
`installGeneratedUpcallTable` `actual` reaches intermediate source sets; the per-platform upcall
trampolines exist. GraalVM native image upcalls are verified by hand
(`docs/platforms/graal-native-image-verification.md`, `PM/desktopTest/.../ReachabilityMetadataTest.kt`).

- **13.1 The iOS app bundle carries no Python standard library, implemented, simulator proof
  open.** SPEC L-11, issue #59, `docs/platforms/ios-app-bundle.md`: per-slice staging
  (`stageIosPythonHome_*`, `stageIosPythonHomeForXcode`), `tools/xcode/install-python.sh` (stdlib to
  `<app>/python-multiplatform-home/`, payload to `<app>/python/`, `.so` wrapped as frameworks with
  `.fwork` placeholders) replacing the two broken phases of the old root `iosApp/` (now `sample/src/iosMain/app.xcodeproj`, #107), and `IosPythonHome` +
  `Py_SetPythonHome` at run time. Left: the installed-app run on the simulator with no
  `SIMCTL_CHILD_*` (the page's "Checking it"); a device run; and a way for consumers outside this
  repository to stage the stdlib (the iOS archive download is in this build script, not the plugin).
- **13.2 Native-image upcall verification as an automated test, open.** SPEC U-6/N-5: the procedure
  is manual (`:sample:nativeCompile`, Liberica NIK); only the reachability metadata is guarded
  automatically.
- **13.3 iOS sample section 7, slow path, open (sample only).** "not wired on iOS": resuming a parked
  continuation needs a Kotlin/Native worker the sample does not start
  (`sample/src/iosMain/.../bindings/ProxyDemo.ios.kt`). The library itself does it
  (`PM/nativeTest/.../AsyncUpcallNative*Test.kt`).
- **13.4 Targets compiled but never tested, open.** `iosArm64`, `iosX64`, `androidNativeX64` (SPEC
  P-1). For `iosX64`, `extractIosSimulatorStdlib` hardcodes `lib-arm64`
  (`python-multiplatform/build.gradle.kts`, the `…/ios-arm64_x86_64-simulator/lib-arm64/python$libVersion`
  copy); an x86_64 simulator needs `lib-x86_64`. Stale text nearby: that task's KDoc still says
  `lib/python3.13`, and `PM/iosMain/README.md` says the stdlib comes from BeeWare without the
  ≥ 3.15 python.org qualification.

## 14. Current state, gathered

**Removed.** It was a dated audit snapshot. Test counts are re-counted per AGENTS.md §15, behaviour
status lives in SPEC.md, and its open-item list (§14b) is redistributed: item 1 → 9.1, item 2 → 12.4
(CI now runs), item 3 → 12.3, item 4 → 12.5, item 5 → 4.1, item 11 → 13.1; items 6–10 were closed.

## 15. Can a consumer outside this repo actually use the library?

Yes, by Maven coordinates, on desktop and Android: all three components publish with one task
(`publishAllToMavenLocal`, root `build.gradle.kts`), the KSP processor publishes, the plugin's default
processor coordinates are correct, the Android AAR publishes (23.42 MB, test suite and headers
stripped), the root metadata jar is 382 KB, `PythonBootstrap` stages the Android stdlib
(`PM/androidInstrumentedTest/.../env/PythonBootstrapTest.kt`), and `stagePythonHome` stages the
desktop stdlib (`GP/test/kotlin/python/multiplatform/gradle/PythonHomeStagingTest.kt`, SPEC L-4).
Old §15a, §15b, §15d, §15f, §15g, §15h are closed; §15e item 4 is closed by §15h.

- **15.1 Nothing is published to a remote repository, open.** No publishing repository or signing is
  configured (`signing { }` is commented out in `python-multiplatform/build.gradle.kts`), and
  `repo1.maven.org/maven2/io/github/thisisthepy/python-multiplatform/` returns 404 (2026-10-03).
  Every consumer verification so far used `mavenLocal()`. 10.1 depends on this or on a release asset.
- **15.2 The KSP processor and plugin versions are hand-copied literals, open.** Root
  `build.gradle.kts` `allprojects { version = "3.13.0" }` (inherited by `python-multiplatform-ksp`) and
  `python-multiplatform-gradle-plugin/build.gradle.kts` `version = "3.13.0"` agree only by hand, while
  the library publishes `3.14.7-alpha01` from `gradle.properties`. The plugin bakes
  `DEFAULT_PROCESSOR_COORDINATES` from its own version, so the two literals drifting would reproduce
  the "processor coordinate that exists nowhere" defect with no configuration-time error. A check, or
  one source for both, is what is left.
- **15.3 `stagePythonHome` has run only on `macos-aarch64`, partial.** Linux-x86_64, macOS-x86_64 and
  Windows-x86_64 are pinned as string assertions in `PythonHomeStagingTest`; Windows' `Lib/`+`DLLs/`
  layout and `linux-aarch64` (no `libpython` in `desktopJar`, relies on `manager.loadFromSidecar`) have
  never executed.
- **15.4 A packaged desktop application carries no CPython prefix, implemented, end-to-end check
  pending (issue #60, SPEC L-9).** The plugin copies the staged prefix's stdlib and `libpython` into
  Compose Desktop's `prepare*AppResources` (so `createDistributable` and `package*`) as
  `python-multiplatform-home/`; at run time the library resolves it through
  `compose.application.resources.dir` (or `-Dpython.multiplatform.home`) and hands it to CPython with
  `Py_SetPythonHome`. A child-JVM test with `PYTHONHOME` removed covers the run-time half
  (`PM/desktopTest/.../env/PackagedPythonHomeLaunchTest.kt`); the packaged sample run is
  `docs/platforms/desktop-packaged-app.md`. Left: Conveyor and non-Compose jpackage need the
  property set by hand; no `.pyc` is shipped; only the build host's platform is packaged (jpackage
  builds for its host anyway).
- **15.5 The Android AAR duplicates the pure-Python stdlib per ABI, open (measured option, not
  taken).** 790 files / 11.91 MB are byte-identical between `arm64-v8a` and `x86_64`; de-duplicating
  saves 3.16 MB compressed but changes the published asset layout `<abi>/lib/python<X.Y>` that
  `PythonBootstrap` and consumers read.

## 16. Bindings from resolved artefacts (the artefact walker)

The walker binds jar declarations, statics, top-level functions, extensions, constructors, value
classes, defaults, callbacks, `@Composable`s, by generating Kotlin source (SPEC B-1, B-3, B-4, B-6;
`GP/test/.../artifact/ArtifactScannerTest.kt`, `ksp-fixtures/artifact/`, `ksp-fixtures/compose/`),
reads Kotlin metadata (`GP/main/.../artifact/KotlinMetadata.kt`), walks klibs through an isolated
worker (`KlibScanner.kt`, `KlibScanWorkAction.kt`), and takes several targets (`artifactTargets`,
`GP/test/.../ArtifactTargetsWiringTest.kt`). Consumers bootstrap `_pm_resolve`/`_pm_invoke` through
`UpcallBootstrap` (`PM/commonTest/.../ffi/upcall/UpcallBootstrapTest.kt`). Old §16a–§16d are
description, §16e/§16f's open remainder is below.

- **16.1 klib declarations bound at run time, partial.** SPEC B-2/N-3. `KlibScannerTest` reads real
  klibs and declines with reasons; `ksp-fixtures/klib-artifact` walks
  `androidNativeArm64CompileKlibraries` through the plugin, but the namespace it walks
  (`kotlinx.coroutines`) yields **zero** bindings by design, so a successful read and a silently
  failed one still produce the same empty `ArtifactTable`
  (`ksp-fixtures/klib-artifact/src/androidNativeArm64Test/.../WalkedKlibArtifactTableTest.kt`). And no
  Gradle task runs that fixture's tests, they are compiled and linked, and run only by pushing
  `test.kexe` by hand. Next: a namespace that yields at least one binding, and a run task.
- **16.2 The product klib path under a Kotlin version skew, open.** `KlibScannerTest` reads klibs
  from Kotlin/Native 2.0.20 in-process; reading this build's 2.4.20-Beta2 klibs in-process fails with
  `InvalidProtocolBufferException`. The worker path with a per-build reader classpath exists to avoid
  that, and nothing asserts it does.
- **16.3 `aar` files and project class directories are skipped silently, open.** Only `.jar` and
  `.klib` are walked (`GP/main/.../artifact/PythonArtifactBindingsTask.kt` KDoc and filter).
- **16.4 Member (instance) functions of jar classes are not bound, open.** The scanner drops
  non-`static` methods: "an instance method needs a receiver" (`ArtifactScanner.kt` KDoc table).
- **16.5 A value-returning function slot (`() -> Float`) does not accept a Python callable, open.**
  SPEC B-5; pinned red-by-design by `M3ProofRenderTest.aValueReturningFunctionSlotDoesNotYetAcceptAPythonCallable`.
- **16.6 `.pyi` stubs for handle-returning functions are not wrapped, open.** SPEC B-7.
- **16.7 Compose through Python on Android, iOS and wasm, open.** SPEC B-6/N-2: the render proofs
  (`ksp-fixtures/compose/src/desktopTest/`) are desktop only.
- **16.8 Two material3 components not reachable from Python, open.** The dynamic `ColorScheme`
  factory is Android-only. Since #73 the class name resolves to `ColorScheme`'s own constructor (its
  `Color` parameters no longer hide it), which needs every colour written out; nothing renders with one
  yet (`M3ProofRenderTest.colorSchemeResolvesToItsConstructorAndABareCallNamesItsOverloads`). `DropdownMenu`
  renders into a popup layer `ImageComposeScene` does not capture, so it is unverified rather than
  known broken (`M3ProofRenderTest.popupLayersAreNotCapturedByImageComposeScene`).

## 17. Owed to the ecosystem, and decisions waiting on the maintainer

From `docs/design/ecosystem.md` §5, "What each repository owes, PythonMultiplatform". Of its gaps,
the `@Composable` callable shape, the `sys.meta_path` finder (`_Finder` in
`PM/commonMain/kotlin/python/multiplatform/ffi/pythonx/PythonxAdapter.kt`, tested in
`PythonxAdapterTest.kt`), the `Composer` hand-off and Python-callable lifetime across recompositions
(`ksp-fixtures/compose/src/desktopTest/.../ComposableRenderTest.kt`, `RecompositionAccumulationTest.kt`)
are done on desktop; the other platforms are 16.7. The production-bundler question is 10.4.

- **17.1 Retire `stagePythonHome` in favour of pypackpack's Python distribution, open (cross-repo).**
  Two acquisition paths disagree on source and destination (`ecosystem.md` §4 item 4). The work lands
  in pypackpack first; this repository then removes `PythonHomeStaging.kt`. Needs the maintainer's go
  (AGENTS.md §7).
- **17.2 Hand `stageWasmBrowserRuntime` to toolchain, open (cross-repo).** Same shape as 17.1, for the
  wasm runtime staging (`WasmBrowserRuntimeStaging.kt`).
- **17.3 Where the generic `pythonx` adapter machinery belongs, decision.** It lives in
  `PM/commonMain/kotlin/python/multiplatform/ffi/pythonx/`, while INTENT §2.3 puts `pythonx` in
  pythonx-compose (SPEC "Outside intent" item 4). It no longer synthesises a `pythonx` module
  (it installs `python_multiplatform.binding`); whether it stays here as a service is unstated.
- **17.4 The committed CPython 3.13 trees, decision.** `python-multiplatform/src/nativeInterop/cinterop/lib`
  (15 555 tracked files, Android/desktop/iOS) and `binary/` (3.13.0 archives) are read only by the
  `-PpythonVersion=3.13.0` desktop branch; the Android 3.13 stdlib trees by nothing. INTENT §3 says the
  interpreter is not vendored. Removing them is a repository-size decision (`ecosystem.md` §5).
