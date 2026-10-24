# Specification

The behavioural contract of python-multiplatform. It must stay inside [`INTENT.md`](INTENT.md).

**How to read the status.**

| Status | Meaning |
|---|---|
| `implemented` | Tests in this repository assert the behaviour; the test files are cited. |
| `partial` | Some of it is asserted, or only on some platforms, or only against a synthetic fixture. What is missing is named. |
| `planned` | Intended, not asserted by any test. |

Status here was assigned from the test sources at the time of writing (2026-10). "Implemented" means
"a test asserts it", not "a test was observed passing in this revision", this document was written
without running Gradle. Tests that only print `SKIP` when no interpreter is available are not counted
as evidence. Paths are relative to the repository root; `PM` = `python-multiplatform/src`,
`GP` = `python-multiplatform-gradle-plugin/src/test/kotlin/python/multiplatform/gradle`.

A behaviour change starts here: edit the item (or add one marked `planned`), write the test, watch it
fail, then implement.

---

## 0. Platforms

| Platform | Kotlin target(s) | Downcall mechanism | Upcall entry | Test path |
|---|---|---|---|---|
| Desktop JVM (macOS; Linux/Windows wired, unverified) | `jvm("desktop")` | Panama FFM, `invokeExact` | FFM upcall stub | `desktopTest → jvmTest → commonTest` |
| Android (ART) | `androidTarget` | JNI via `RegisterNatives` | JNI upcall | `androidInstrumentedTest → jvmTest → commonTest` (device) |
| iOS | `iosArm64`, `iosSimulatorArm64`, `iosX64` | cinterop | `@CName` symbol | `iosSimulatorArm64Test → nativeTest → commonTest` |
| Android native | `androidNativeArm64`, `androidNativeX64` | cinterop | `@CName` symbol | `androidNativeArm64Test → artTest → nativeTest → commonTest` (device, via adb) |
| Web (experimental) | `wasmJs` (Node, browser) | JS / Emscripten CPython | `@WasmExport` | `wasmJsTest` (Node suite; browser subset) |

- **P-1** The library compiles for every target above. `Status: implemented` for desktop, Android,
  iosSimulatorArm64, androidNativeArm64 and wasmJs (each has a test source set). `partial` for
  `iosArm64`, `iosX64`, `androidNativeX64` (compile-wired, no tests run there).
- **P-2** Linux and Windows desktop: the build downloads and links the right CPython archive.
  `Status: partial`, the suite has never been observed running on either.
- **P-3** The interpreter is not vendored: Gradle downloads, verifies and extracts CPython per
  platform; the version is set in `gradle.properties`. `Status: implemented` (build logic;
  `GP/PythonHomeStagingTest.kt` covers the naming, URL, checksum and stamp decisions).

## 1. Interpreter lifecycle

- **L-1** `Python3.initialize()` starts CPython; it is idempotent; `Python3.isInitialized` reports it.
  `Python3.exec(source)` runs statements, `Python3.eval(expr, mode, globals, locals)` returns a
  `PyObject`, `Python3.import(name)` returns a `PyModule`. Python errors surface as `PyException`
  carrying the real Python exception (not a printed-and-cleared one).
  `Status: implemented`, `PM/commonTest/.../ffi/Python3Test.kt`, `SmokeTest.kt`, `VersionsTest.kt`.
- **L-2** `Python3.runMain` / `runApp` run a module or `-c` source with argv, map `sys.exit` status to
  a return value, restore argv, and do not finalize the shared interpreter.
  `Status: implemented`, `PM/commonTest/.../RunMainTest.kt`, `RunAppTest.kt`.
- **L-3** Before `Py_Initialize`, a `PYTHONHOME` pre-flight check turns a missing or unusable stdlib
  into a catchable `IllegalStateException` naming the path and version, instead of CPython's process
  abort. `Status: implemented` on desktop (`PM/desktopTest/.../env/PythonHomeCheckTest.kt`);
  smoke-tested on all targets (`PM/commonTest/.../PythonHomeCheckSmokeTest.kt`).
- **L-4** Desktop: applying the Gradle plugin `io.github.thisisthepy.python.multiplatform.bindings`
  registers `stagePythonHome`, which downloads the matching CPython, verifies it against the release
  `SHA256SUMS`, caches it machine-wide and sets `PYTHONHOME` on `run`/`test`. A user-set `PYTHONHOME`
  is never overridden; `pythonBindings { stagePythonHome.set(false) }` disables it.
  `Status: partial`, decisions are unit-tested (`GP/PythonHomeStagingTest.kt`); no end-to-end test
  of the task.
- **L-5** Android: `PythonBootstrap.initialize(context)` unpacks the stdlib shipped in the APK assets
  to app-private storage once (a stamp written last decides re-unpacking, including after APK
  upgrades), sets `PYTHONHOME` and starts the interpreter. `Status: implemented` (needs a device),
  `PM/androidInstrumentedTest/.../env/PythonBootstrapTest.kt`, `PythonPayloadStagingTest.kt`.
- **L-6** A consumer's Python payload (jar resource on desktop, APK asset on Android) is extracted once
  and put on `sys.path` before the first import. `Status: implemented` on desktop
  (`PM/desktopTest/.../env/PythonPayloadTest.kt`) and Android (`PythonPayloadStagingTest.kt`).
- **L-7** iOS: the framework carries no stdlib, so `PYTHONHOME` must point at one; the test build
  extracts it. `Status: partial`, exercised by the shared suite on the simulator; no iOS-specific
  lifecycle test beyond `PM/iosSimulatorArm64Test/.../AsyncioAvailabilityProbeTest.kt`. An installed
  app carries its own stdlib: L-11.
- **L-8** Initialisation sets `builtins.compiled` to an identity decorator (an existing one is kept), so `@compiled` needs no import on any platform (N-7, issue #42). `Status: implemented`, `PM/commonTest/.../ffi/BuiltinCompiledTest.kt`.
- **L-9** Desktop: a packaged application starts with no `PYTHONHOME` in its environment (issue #60,
  ROADMAP §15.4). The Gradle plugin copies the prefix `stagePythonHome` stages into Compose Desktop's
  application resources (every `prepare*AppResources` task, so `createDistributable` and the
  `package*` tasks built on it) under `python-multiplatform-home/`: the standard library
  (`lib/python<X.Y>/`, or `Lib/` and `DLLs/` on Windows) and the shared library, without
  `__pycache__`, headers, `bin/` or Tcl/Tk. `pythonBindings { packagePythonHome.set(false) }` turns
  this off. At run time, before `Py_Initialize`, desktop takes the first of: `PYTHONHOME` from the
  environment (CPython reads it itself and nothing else happens); the system property
  `python.multiplatform.home`; `<compose.application.resources.dir>/python-multiplatform-home` when
  that directory exists; and, inside a GraalVM native image only (`org.graalvm.nativeimage.imagecode`
  is `runtime`; on a JVM the executable is `java`), `<directory of the executable>/python-multiplatform-home`
  when that directory exists (#246). The plugin stages that one with `stageNativeImagePythonHome`, a
  copy of the same pruned prefix into `<pythonBindings.nativeImageDirectory>/python-multiplatform-home`,
  which does nothing unless `nativeImageDirectory` is set. A prefix taken from a property or a directory goes through L-3's check (a catchable
  `IllegalStateException` naming the path and where it came from), is handed to CPython with
  `Py_SetPythonHome`, and is where `libpython` is loaded from when it contains one.
  `Status: partial`, `GP/PackagedPythonHomeTest.kt` (what is copied, which tasks, the switch);
  `PM/desktopTest/.../env/PackagedPythonHomeTest.kt` (resolution order, `wchar_t` encoding, library
  lookup) and `PackagedPythonHomeLaunchTest.kt` (a child JVM with `PYTHONHOME` removed imports `json`
  from the prefix a property names, or from a Compose resources directory). The
  `createDistributable` run itself is a manual check,
  `docs/platforms/desktop-packaged-app.md`.
- **L-9a** (issue #74) Desktop packaging and loading of `libpython`: every `lib/**` entry of the desktop jar is a real
  file (never 0 bytes -- Gradle extracts the python-build-standalone symlink `libpython3.14.so` as an empty file, so
  symlinks are resolved and the real library is packed under the `System.mapLibraryName` name, no `.so.1.0`); desktop
  archives are python-build-standalone's `install_only_stripped` flavour (issue #86; URL and SHA-256 pinned in
  `python-checksums.properties`), so no linux `libpython*.so` in the jar has a `.debug_*` section (a stripped
  3.14.7 x86_64 library is 33,106,704 bytes against 251,884,016 unstripped); no host `strip` is involved. The
  classpath library is extracted to `<user cache>/python-multiplatform/<python version>/<platform>/` (macOS
  `~/Library/Caches`, Windows `%LOCALAPPDATA%`, else `$XDG_CACHE_HOME` or `~/.cache`; fallback
  `java.io.tmpdir/python-multiplatform-<user>`; override `-Dpython.multiplatform.cache`), never the working
  directory, and reused when its size matches. The same holds for the plugin's staged prefix and the packaged
  `python-multiplatform-home`: `stagePythonHome` extracts the archive itself and materialises every symlink/hard
  link as a copy of its in-archive target (Gradle's `tarTree` yields 0-byte files), the staging stamp changed so
  old prefixes are re-extracted, and the packaged copy fails the build on any 0-byte `libpython*`.
  `Status: implemented` -- `PM/desktopTest/.../ffi/DesktopJarLibrariesTest.kt`, `ExtractLibraryCacheTest.kt`,
  `GP/StagedPrefixLinksTest.kt`.
- **L-10** Desktop: the library uses no JDK module beyond `java.base`, so it runs on a `jlink`ed
  runtime such as the one Compose Desktop's `createDistributable` bundles (issue #77). In particular
  native memory is reached through `java.lang.foreign`, never `sun.misc.Unsafe` (module
  `jdk.unsupported`): the proxy heap type (`ProxyTypeFactory.createProxyType`, `_pm_proxy_base`) is
  built, and its handle slot read and cleared on deallocation, on a runtime limited to `java.base`.
  `Status: implemented`, `PM/desktopTest/.../ffi/JlinkedRuntimeProxyTypeTest.kt` (a child JVM with
  `--limit-modules java.base` installs the proxy base, and a dropped subclass instance releases its
  handle). The packaged sample's own log is a manual check,
  `docs/platforms/desktop-packaged-app.md`.
- **L-11** iOS: an installed app starts with no `PYTHONHOME` in its environment, and
  `Python.xcframework` carries no standard library (issue #59, ROADMAP §13.1). The library build
  stages one prefix per slice (`iphoneos-arm64`, `iphonesimulator-arm64`, `iphonesimulator-x86_64`):
  the shared `Python.xcframework/lib/python<X.Y>/` merged with that slice's
  `lib-<arch>/python<X.Y>/` (`lib-dynload/`, `_sysconfigdata`), without `__pycache__`,
  `libpython*.dylib`, or the top-level packages `test`, `idlelib`, `tkinter`, `turtledemo` and
  `ensurepip`; `stageIosPythonHomeForXcode` picks the slice from Xcode's `EFFECTIVE_PLATFORM_NAME`
  and `ARCHS` and prints `PYTHON_HOME_DIR=` and `PYTHON_DYLIB_INFO_TEMPLATE=`. An Xcode Run Script
  phase (`python-multiplatform-gradle-plugin/src/main/resources/xcode/install-python.sh`, after Copy Bundle Resources and before Embed Frameworks)
  copies that prefix into `<app>/python-multiplatform-home/` and the consumer's payload (L-6) into
  `<app>/python/`, and moves every `.so` under either into `Frameworks/<dotted.name>.framework`,
  leaving a `.fwork` placeholder, as CPython's `AppleFrameworkLoader` and Apple's rule against loose
  binaries require. At run time, before `Py_Initialize`, iOS takes the first of: `PYTHONHOME` from
  the environment (CPython reads it itself and nothing else happens);
  `<NSBundle.mainBundle.resourcePath>/python-multiplatform-home` when that directory exists. A
  bundled prefix goes through L-3's check (a catchable `IllegalStateException` naming the path and
  where it came from) and is handed to CPython with `Py_SetPythonHome`. With neither, nothing is set
  and behaviour is as before. `Status: partial`, `GP/IosPythonHomeLayoutTest.kt` (slice choice,
  source directories, file selection, a staged fake tree); `PM/iosSimulatorArm64Test/.../env/IosPythonHomeTest.kt`
  (resolution order; the simulator test task's `PYTHONHOME` still wins). The installed-app run on the
  simulator is a manual check, `docs/platforms/ios-app-bundle.md`.
- **L-12** The dev agent (#233; toolchain `docs/design/live-connection.md`, option B): when the launcher sets
  `PYTHON_MULTIPLATFORM_DEV_AGENT` to `port=<n>;token=<t>[;overlay=<dir>]` **and** an installed payload root
  holds the marker `python_multiplatform_devagent.enable`, `Python3.initialize()` loads the embedded agent
  (`src/commonMain/python/python_multiplatform_devagent.py`, generated into Kotlin by `generateDevAgentSource`)
  as the module `python_multiplatform_devagent` and it listens on 127.0.0.1 only. The marker is the build-time
  switch: this library is one artifact for debug and release, so the app's build decides, and toolchain stages
  the marker into debug variants only. Without it the variable is ignored with a warning and no port opens. The
  token must be at least 43 characters (`secrets.token_urlsafe(32)`), or the agent refuses to start. The listening
  socket cannot be bound by another socket while it listens: `SO_EXCLUSIVEADDRUSE` on Windows; on POSIX,
  `SO_REUSEADDR` only so that a restarted app can rebind the launcher's port past TIME_WAIT. A connection's first
  message must be `hello` with the token. Then, one JSON object per line: `execute` runs code in `__main__`'s
  persistent namespace, streams stdout and stderr, returns the last expression's value and reports errors with
  their traceback; `complete` completes from that namespace; `push` writes `.py` files (relative `/`-separated paths with no
  `.`, `..`, backslash or drive, resolving inside the overlay, on every OS) into a writable overlay first on `sys.path` and re-imports the loaded ones, children first, through `typedpython.importgate` when the
  app has it (N-7b), keeping the old module when a re-import is refused or fails. A malformed request is printed
  and does not stop the app. `Status: partial`: desktop
  (`PM/desktopTest/.../env/PythonDevAgentTest.kt`: the embedded agent answers over loopback; a child JVM with the
  marker and the variable has an agent on that port, one without the variable has none, one without the marker
  (a release build) opens no port with the variable set, and a short token opens no port; the protocol, token
  length, port takeover and restart: `python-multiplatform/src/test/python/test_devagent.py`, CI `Dev agent
  protocol tests` on macOS and the `dev-agent` job on ubuntu and windows (#238; with `SO_EXCLUSIVEADDRUSE`
  removed, the Windows takeover test fails, checked once); toolchain stages the marker for `enableHotReload`
  builds and passes the variable to the desktop run tasks (toolchain#105), but nothing sets the variable on
  Android or iOS yet (toolchain's M2, M3); no interrupt; `execute` runs on the agent's thread (no main-thread dispatch yet).

## 2. Low-level C API (downcall surface)

- **C-1** The CPython Stable ABI is declared once as `expect` functions in `EmbedAPI.kt` (≈330) with an
  `actual` on every platform; no CPython-deprecated symbol is declared. `Status: implemented`,
  `PM/desktopTest/.../ffi/EmbedApiSurfaceTest.kt` (source scan), `PM/commonTest/.../ffi/EmbedApiLowLevelTest.kt`
  (round trips, borrowed vs. new references, error indicator).
- **C-2** Desktop calls go through `MethodHandle.invokeExact`, pointers as `JAVA_LONG`; every
  `FunctionDescriptor` linked is declared in the native-image reachability metadata.
  `Status: implemented`, `PM/desktopTest/.../ffi/ReachabilityMetadataTest.kt`.
- **C-3** Android binds through `RegisterNatives` with the calling convention chosen per function;
  re-entrant functions are never `@CriticalNative`. `Status: implemented`,
  `PM/desktopTest/.../ffi/JniCallConventionClassificationTest.kt`, `PM/androidInstrumentedTest/.../JniWiringTest.kt`.
- **C-4** Every C API call holds the GIL. `Status: partial`, enforced by convention and README rules;
  the only direct test (`PM/commonTest/.../GilParkingTest.kt`) asserts only "does not crash". Releasing
  the GIL after initialisation is not enabled.
- **C-5** A thread CPython creates can upcall into ART: it is attached once per thread and detached when
  it dies. `Status: implemented` on Android, `PM/androidInstrumentedTest/.../UpcallThreadAttachTest.kt`.
- **C-6** wasm: `Py_ssize_t` boundaries and the `-1` error return are handled at the 32-bit ABI.
  `Status: implemented`, `PM/wasmJsTest/.../WasmPySsizeTBoundaryTest.kt`.

## 3. Object model (Kotlin uses Python)

All in `PM/commonTest`, so they run wherever the interpreter loads.

- **O-1** `PyObject`: `getAttr`, `setAttr`, `delAttr`, `getAttrOrNull`, call (`invoke`), `equals` /
  `hashCode` / `toString` delegate to Python. `Status: implemented`, `PyObjectTest.kt`.
- **O-2** `PyType`: name, bases, MRO, `isSubtypeOf`, `isInstance`, construct by call, `cast` refuses an
  incompatible object. `Status: implemented`, `PyTypeTest.kt`.
- **O-3** `PyException` is a Kotlin `Throwable` carrying the Python exception type and message.
  `Status: implemented`, `PyExceptionTest.kt`.
- **O-4** Basic types `PyInt`, `PyFloat`, `PyBool`, `PyString`, `PyNone`, bytes round-trip with Kotlin
  values. `Status: implemented`, `PyBasicTypesTest.kt`, `PyValueBytesConversionTest.kt`.
- **O-5** Collections implement the Kotlin collection interfaces: `PyList` (`MutableList`, `fromList`,
  views), `PyDict` (`MutableMap`), `PySet` (`MutableSet`, set algebra, frozenset), `PyTuple` (`List`),
  plus iterators. `Status: implemented`, `PyListTest.kt`, `PyDictTest.kt`, `PySetTest.kt`,
  `PyTupleTest.kt`, `PyIteratorTest.kt`.
- **O-6** Modules, builtins, callables (bound methods, class/static methods), utilities (range, slice,
  ellipsis), import and GC helpers. `Status: implemented`, `PyModuleTest.kt`, `BuiltinsTest.kt`,
  `PyCallablesTest.kt`, `PyUtilitiesTest.kt`, `PyImportTest.kt`, `PyGCTest.kt`.
- **O-7** Conversion: `PyValue` with selectable conversion strategies (`withContext`), lazily cached
  snapshots invalidated on demand; buffer-view types and types with no Kotlin counterpart are refused.
  `Status: implemented`, `ConversionTest.kt`, `PyValueLazyConversionTest.kt`.

## 4. Object lifetime

- **M-1** A Kotlin wrapper owns one Python reference and releases it when the wrapper becomes
  unreachable, no `close()` needed; explicit release is idempotent and runs exactly once.
  `Status: implemented`, `PM/commonTest/.../ref/GCLeakTest.kt`, `RefCountTest.kt`,
  `OwnershipLeakTest.kt`, `DoubleReleaseTest.kt`, `EvalCheckpointTest.kt`.
- **M-2** On Android below API 33 (no `java.lang.ref.Cleaner`), a `PhantomReference` path does the same.
  `Status: implemented`, `PM/jvmTest/.../PhantomCleanerRegistryTest.kt`,
  `PM/androidInstrumentedTest/.../AndroidCleanerPathTest.kt`.
- **M-3** Reference cycles that cross the boundary (Python → Kotlin proxy → Python) are collected by
  Python's cyclic GC, including on threads CPython created and for Python subclasses of proxies.
  `Status: implemented` on desktop (`PM/desktopTest/.../ref/CycleCollectionTest.kt`,
  `python-multiplatform-ksp/fixtures/app/.../RefHolderCycleCollectionTest.kt`); `partial` elsewhere, tests exist for
  native, Android and wasm (`PM/nativeTest/.../CycleCollectionTest.kt`,
  `PM/androidInstrumentedTest/.../CycleCollectionTest.kt`, `PM/wasmJsTest/.../WasmCycleCollectionTest.kt`)
  but `docs/roadmap/ROADMAP.md` §7 records remaining per-target gaps.
- **M-4** Handles given to Python never alias a reused slot; owned results release when Python drops
  them. `Status: implemented`, `PM/commonTest/.../reflection/HandleTableTest.kt`,
  `ProxyHandleLifetimeTest.kt`, `OwnedResultLifetimeTest.kt`.

## 5. Upcalls (Python uses Kotlin)

- **U-1** A KSP processor generates, at build time, a function table for every `public` Kotlin
  declaration in a module; `@PythonInternal` excludes a class or member. Fragments from several
  modules aggregate; a name claimed twice is an error. No runtime reflection is used.
  `Status: implemented`, `PM/commonTest/.../reflection/UpcallTableTest.kt`,
  `python-multiplatform-ksp/fixtures/app/src/desktopTest/.../GeneratedTableTest.kt`, `python-multiplatform-ksp/src/test/.../SourceRenderingTest.kt`.
- **U-2** One entry point per platform marshals arguments (int, float, bool, str, bytes with NUL,
  objects), turns a Kotlin exception into a Python exception and a `Unit` return into `None`.
  `Status: implemented`, `PM/commonTest/.../UpcallEntryTest.kt`, `UpcallTrampolineTest.kt`,
  `PM/nativeTest/.../UpcallRawEntryPointTest.kt`.
- **U-3** Generated Python proxies let Python write ordinary Python against Kotlin: construct a class,
  call methods, get/set properties (a `private set` is read-only), read companion / static members.
  A Kotlin package is importable under its own name. `Status: implemented` on desktop,
  `PM/commonTest/.../upcall/PythonProxyInstallTest.kt`, `python-multiplatform-ksp/fixtures/app/.../GeneratedDeclarationKindsTest.kt`,
  `GeneratedStaticPropertyProxyTest.kt`; `partial` elsewhere, only `python-multiplatform-ksp/fixtures/app/.../NativeSmokeTest.kt`
  (androidNative) and `python-multiplatform-ksp/fixtures/android/.../GeneratedAndroidTableTest.kt` run outside desktop.
- **U-4** Declaration kinds: objects, companions, interfaces (not constructible), enum entries as
  statics, abstract classes (no constructor), nested classes. Not exposed: annotation classes,
  generic declarations, data-class synthetics. `Status: implemented` on desktop,
  `GeneratedDeclarationKindsTest.kt`.
- **U-5** `suspend` functions are awaitable from Python (`await g.greetNow(1)`); a function that never
  suspends completes without a Future; cancelling the Python future cancels the Kotlin coroutine.
  `Status: implemented` on desktop and Kotlin/Native, `PM/desktopTest/.../upcall/AsyncUpcallDeliveryTest.kt`,
  `AsyncUpcallCancellationTest.kt`, `AsyncUpcallEarlyCancellationTest.kt`, `PM/nativeTest/.../AsyncUpcallNative*Test.kt`,
  `python-multiplatform-ksp/fixtures/app/.../GeneratedSuspendTest.kt`; `planned` on wasm (no threads).
- **U-6** Upcalls work in a GraalVM native image (desktop). `Status: implemented` with manual
  verification, procedure and record in `docs/platforms/graal-native-image-verification.md`
  (`:sample:nativeCompile`, Liberica NIK), guarded automatically by `ReachabilityMetadataTest.kt` (C-2).
  Not automated as a test of the image itself.
- **U-7** A Kotlin extension function is a method on its receiver's proxy, so chains compose
  (`Modifier.padding(16).size(24)`). `Status: partial`, asserted against a hand-written,
  Compose-shaped table (`PM/commonTest/.../pythonx/PythonxAdapterTest.kt`
  `anExtensionIsAMethodOnItsReceiverAndTheChainComposes`) and against the real Compose jars through
  the artifact walker (`python-multiplatform-gradle-plugin/fixtures/artifact/.../WalkedArtifactComposeModifierTest.kt`); not
  asserted for KSP-generated proxies.
- **U-8** A function on a Kotlin-named module carries Kotlin's surface: the Kotlin declaration name,
  keyword arguments by **Kotlin parameter names**, Kotlin defaults for omitted parameters, overload sets
  under the base name, and its signature as public metadata (`inspect.signature`,
  `python_multiplatform.describe`; contract in `KotlinSurface.kt`'s KDoc) - and, beside each Kotlin
  name, its Pythonic alias (U-12).
  An `int` reaches an integral parameter only inside that Kotlin type's range, and when an overload
  set still ties, the candidate whose integral parameters are each argument's Kotlin literal type
  (`Int` if it fits in 32 bits, else `Long`) is called, as `kotlinc` resolves `Color(0xFFFFFFFF)` to
  `Color(Long)` and `Color(0x11223344)` to `Color(Int)` (#146). `Status: implemented`,
  `PM/commonTest/.../pythonx/PythonxAdapterTest.kt` `anIntArgumentPicksTheOverloadItsKotlinLiteralWould`.
  `describe(module, name)` describes any bound name, a named constant (`STATIC_GETTER`) included,
  without evaluating it (#36). A module's `dir()` lists its direct child packages/objects and reading
  one as an attribute imports it (#35) - Kotlin names only (the U-12 aliases are served, not listed).
  No namespace is renamed; the binder creates no `pythonx` module and a real `pythonx` package
  on disk is what `import pythonx` loads. The answer is the same whichever installer
  (`PythonProxySource`, `PythonxAdapter`) ran first for a table. **A name that is both a function and a
  module is callable** (#78): Kotlin has `TextRange(2)` (a top-level factory) and `TextRange.Zero` (a
  companion constant), so the module `androidx.compose.ui.text.TextRange` is itself callable, calling it
  runs the function or overload set of that name in its parent package, while its attributes stay the
  companion's constants and functions. One rule with the class case (#73): a Kotlin name that is a
  constructor or factory is what calling it does, whatever else lives under that name. `Status:
  implemented` on desktop,
  `PM/desktopTest/.../pythonx/KotlinNamedSurfaceTest.kt`, `BinderNamespaceTest.kt`, `python-multiplatform-compose/fixtures/compose/.../KotlinSignatureMetadataTest.kt`.
- **U-9** A Pythonic package can serve extra member names on a Kotlin proxy through one hook,
  `python_multiplatform.binding.add_member_resolver(fn)`, `fn(kotlin_type_name, requested_name,
  kotlin_member_names) -> kotlin_name | (kotlin_name, keyword_map) | None`, asked when no Kotlin member
  of that name exists, and, for a Kotlin-named member, only when a call passes keywords and only for
  its `{python_kw: kotlinParam}` keyword map (#34).
  Resolvers are asked before the binder's own U-12 alias, so a resolver still decides a name it
  answers; with none answering, the U-12 alias is served, and a name that is no alias is an
  `AttributeError`. Aliases are cached in the registry, not written on the proxy class (`dir()` stays
  Kotlin-only). Contract in `KotlinSurface.kt`'s
  KDoc. `Status: implemented` on desktop, `PM/desktopTest/.../pythonx/MemberResolverTest.kt`,
  `python-multiplatform-compose/fixtures/compose/.../MemberResolverComposeTest.kt`.
  A resolver (or anything else) reads a member's declaration rows from the receiver type alone with
  `python_multiplatform.describe_member(kotlin_type_name, kotlin_member_name)`: `describe()`'s
  tuple-of-dicts for every extension overload or the property getter/setter that type's proxy serves
  under that name, supertypes included, invoking nothing; `AttributeError` for a name it does not
  serve (#54). `Status: implemented`, `PM/commonTest/.../pythonx/PythonxPropertyTest.kt`.
- **U-10** A Kotlin property is an attribute of its receiver's proxy (#38): a member `val`/`var` of a
  public class or interface reads (and, for a public setter, writes) as a Python `property`, an
  extension property's getter reads the same way on its receiver (`Icons.Default.Add`), and both are
  found on the type and then on every type the table says it is a. A property is never a module
  attribute and makes no package. `None` written for a slot with no default is Kotlin's `null` for a
  reference type; a Kotlin proxy written into a `kotlin.Any?` slot is the Kotlin object, and any other
  Python object is held by Kotlin as itself. A Python **scalar** written into a `kotlin.Any`/`kotlin.Any?`
  slot, a function argument or a property write, is boxed into the Kotlin type it means (#69): `bool`
  → `kotlin.Boolean` (checked before `int`); `int` → `kotlin.Int` when it fits in 32 bits, else
  `kotlin.Long`, and outside 64 bits refused with a reason; `float` → `kotlin.Double`; `str` →
  `kotlin.String`. `None` is `null` for `Any?`; for a non-null `Any` it is Kotlin's own refusal (the
  table does not carry nullability). Reading a `kotlin.Any`/`kotlin.Any?` value back, a property, a
  function result, or a callback argument, gives the Python scalar for a Kotlin `Int`/`Long`/`Short`/`Byte`
  (`int`), `Double`/`Float` (`float`), `Boolean` (`bool`), `String`/`Char` (`str`), and a proxy for any
  other Kotlin object. `Status: implemented`,
  `PM/commonTest/.../pythonx/PythonxPropertyTest.kt`, `python-multiplatform-compose/fixtures/compose/.../PythonAppViewRenderTest.kt`,
  `AnySlotScalarRenderTest.kt`, `MaterialIconsRenderTest.kt`.
- **U-11** A KSP-bound function, method or property whose declared return type is a Kotlin class that
  has a generated proxy returns an instance of **that class's proxy** (#94), not a generic owner: its
  methods and properties work and `isinstance(result, TheClass)` holds. The class is found by the declared
  return type's Kotlin name; a return type with no generated proxy stays a generic owner object. The
  result's ownership is the same as for any owned result. `Status: implemented`,
  `python-multiplatform-ksp/fixtures/app/.../KspClassResultProxyTest.kt` (red until #94 lands).
- **U-12** Pythonic names (#131). On a Kotlin-named module every declaration, proxy member and keyword
  parameter is reachable by its Kotlin name **and** by its Pythonic name; the Kotlin name keeps
  working. The namespace is never converted: `androidx.compose.material3` is a module under exactly
  that name, and no package segment gets an alias (AGENTS §12.1, §12.2).
  - **Rule** (pythonx-compose 0.1.0a1's `_reexport.py`, character for character; `python_multiplatform.
    python_name`/`snake_case`): a name starting upper-case is unchanged - types, objects, composables,
    PascalCase constants (`Checkbox`, `Modifier`, `Alignment.End`). Any other name is snake_case, a run
    of capitals counting as one word (`rememberTextFieldState` → `remember_text_field_state`,
    `toURLString` → `to_url_string`, `zIndex` → `z_index`). An explicit overload key converts its base
    and keeps its `__Types` suffix (`paddingFromBaseline__TextUnit` → `padding_from_baseline__TextUnit`).
    A keyword is `snake_case` of its Kotlin parameter name (`onCheckedChange=` → `on_checked_change=`).
  - **Scope.** Module functions, overload base names, explicit overload keys, lower-case-first module
    constants, functions and constants of a Kotlin `object` (a module here: `Arrangement.spacedBy` →
    `Arrangement.spaced_by`), extension methods and properties on a proxy (read and write:
    `state.selected_index = 2` writes `selectedIndex`), and the keyword parameters of every function,
    method and constructor-shaped function (`TextFieldState(initial_text=...)`). Served by the binding
    layer (`PythonxAdapter`) for names and keywords, and by the proxy layer (`PythonProxySource`) alone
    for module names and keywords of what it renders. Not converted: classes rendered by KSP
    (`PythonProxySource.renderClass`), whose constructors and methods are positional and whose members
    keep their Kotlin names.
  - **Collisions.** An alias is served only when it is unambiguous in its namespace - one module (its
    bound names and child packages), one proxy type (its members and every supertype's), one
    declaration's parameters. An alias that is already a Kotlin name there belongs to that Kotlin name
    (`foo_bar` is the Kotlin `foo_bar`, never `fooBar`); an alias two Kotlin names map to (`toURL`,
    `toUrl` → `to_url`) is not served at all, and both stay reachable by their Kotlin names. Decided
    from the table, independent of order. Writing both spellings of one parameter is "two values".
  - **Listing.** `dir()` of a module and of a proxy lists Kotlin names only, so each declaration is
    listed once; an alias is resolved on a miss and never written onto a proxy class. A layer that
    converts `dir()` by the same rule (pythonx-compose 0.1.0a1's `_name_table`) therefore sees no
    collision.
  - **Metadata.** `inspect.signature` shows each parameter under its Pythonic keyword (the Kotlin name
    where no alias is served) - it is the call surface Python tools read; `describe()` keeps the Kotlin
    declaration's names in `name` and adds the Pythonic keyword as `python_name`;
    `describe(module, alias)` and `describe_member(type, alias)` describe the Kotlin declaration the
    alias is served for.
  - **Stubs** (B-7) carry the same names.
  `Status: implemented` on desktop (tests written first, not yet run at the time of writing) -
  `PM/commonTest/.../pythonx/PythonicNameTest.kt`,
  `PythonxAdapterTest.kt` (`theNameRuleConvertsKotlinToPythonAndEitherSpellingReachesOneDeclaration`,
  `everyBoundNameSurvivesTheRoundTripThroughItsPythonicName`), `PM/desktopTest/.../pythonx/KotlinNamedSurfaceTest.kt`,
  `PythonxComposeCompatibilityTest.kt`, `MemberResolverTest.kt`, and the Compose render tests written
  with Pythonic names (`python-multiplatform-compose/fixtures/compose/.../M3ProofRenderTest.kt`, `CallbackDrivenRenderTest.kt`,
  `TextFieldStateRenderTest.kt`).

## 6. Binding prebuilt libraries (Gradle plugin)

- **B-1** The artifact walker binds declarations from prebuilt **jars** (Kotlin metadata read with ASM,
  overloads suffixed, multi-file facades reachable) by generating Kotlin source that `kotlinc`
  compiles, never by JVM name lookup. KSP and the walker share one Python namespace.
  `Status: implemented` on desktop, `GP/artifact/ArtifactScannerTest.kt`,
  `python-multiplatform-gradle-plugin/fixtures/artifact/.../WalkedArtifactTableTest.kt`, `WalkedArtifactPythonImportTest.kt`.
  Beside functions and object constants it binds (#38): generic functions whose type parameters are
  unbounded and not reified, each read as `kotlin.Any?` and written out at the call
  (`mutableStateOf<kotlin.Any?>(...)`), a bounded or reified one still declined; member properties of
  public classes and interfaces that cross as object handles, as `GETTER` entries `Owner.prop` and,
  for a public setter with every class type parameter unbounded, `SETTER` entries `Owner.prop=`; and
  top-level extension property getters (`pkg.prop`, receiver in `receiverTypeName`). Properties take no
  part in overload naming or in a constructor's name check; a property key another binding already
  holds is declined, and so is a property or an extension function whose receiver has a supertype missing from the consumer's compile classpath (kotlinc cannot build its member scope; the reason names the missing supertype, #66). `Status: implemented`, `GP/artifact/PropertyBindingTest.kt` (properties and, #66, extension functions).
  The public instance functions of a Kotlin-public `object` bind under the object's name like a
  static (`Arrangement.spacedBy`, #53), except `Any`'s members, ones that also exist as a
  `@JvmStatic` static, and `@Composable` ones (declined); an `internal` or file-private object is
  skipped. `Status: implemented`, `GP/artifact/ObjectMemberBindingTest.kt`.
  The public, non-suspend **member functions** of a Kotlin-public `class`, `interface` or `enum class`
  that crosses as an object handle bind as `METHOD` entries `Owner.name` (#215), the shape KSP's
  members already have: `args[0]` is the receiver, uncounted in `arity`, and the generated call is
  `(args[0] as Owner).name(...)`. The binding layer serves them as methods of the receiver's proxy
  (supertypes included), ahead of an extension of the same name, because Kotlin resolves a member
  first, and never as a module attribute; they take no part in a constructor's name check. Overloads
  within one class are told apart as functions are (`Owner.add__Int`). Declined, each with its reason:
  a `suspend` member, a `@Composable` member, a member of a generic class (not yet), and a member that
  shares its name with a property of the class (the property is kept). `Any`'s members, statics and
  synthetic methods are not bound. A member's default arguments are not omittable yet: Python passes
  every argument. A member's stub is a method of the receiver's stub class whose docstring carries
  `Kotlin member: <key>` (or a comment with that marker where the class has no stub). Klibs bind no
  class member of any kind (B-2). `Status: implemented` on desktop (the walker reads jars),
  `GP/artifact/MemberFunctionBindingTest.kt`, `GP/stubs/MemberFunctionStubTest.kt`,
  `python-multiplatform-gradle-plugin/fixtures/artifact/.../WalkedArtifactMemberFunctionTest.kt`
  (and `WalkedArtifactStubTest`'s key check); the binding-layer half,
  `PM/commonTest/.../pythonx/PythonxMethodTest.kt`, also passes on Kotlin/Native (iOS simulator).
  Not verified: a GraalVM native image (§13); the entries are generated lambdas with no reflection.
  A public constructor of a public, non-abstract, non-generic class binds under the class's own name
  (`pkg.Class`), including one with a value-class parameter, which `kotlinc` compiles to a private
  `<init>` behind a public synthetic bridge ending in `DefaultConstructorMarker` (the descriptor
  `@Metadata` records); a `@Deprecated(level = HIDDEN)` constructor, whose bridge carries that
  annotation, is skipped (#73). A declared `kotlin.CharSequence` **result**, of a function, a property
  getter or an object constant, crosses as a Python `str` (the generated body returns Kotlin's
  `toString()` of it; `null` stays `None` for `CharSequence?`); a `CharSequence` parameter is still
  declined (#73). `Status: implemented`, `GP/artifact/TextStateBindingTest.kt`,
  `python-multiplatform-compose/fixtures/compose/.../TextFieldStateRenderTest.kt`.
- **B-2** The walker on **klibs** (Kotlin/Native libraries). `Status: partial`. It reads
  top-level functions and, since #237, the **member functions** of the klib's classes; no class
  constructor or property yet. A member binds as #215's `METHOD` `Owner.name` (receiver in `args[0]`,
  `(args[0] as Owner).name(...)`, served on the proxy, never a module attribute) when its class can
  cross as a handle and is an interface, a final class or an enum class: the pinned compiler's klib
  ABI records modality but no visibility, so a member of an open, abstract or sealed class, which
  may be `protected`, is declined with that reason, and an `object`'s functions (statics on the jar
  side, #53) are declined too. Also declined, with reasons: members of a generic class, `suspend`,
  `@Deprecated`, `@PublishedApi`, `@Composable`, member extensions and context receivers, a member
  named like a property, data-class `copy`/`componentN`, and anything without a klib boundary type;
  `Any`'s members and an enum's static `valueOf`/`values`/`entries` are not bound. From
  `kotlinx-coroutines-core` this binds `CompletableJob.complete`, `Job.start`,
  `Semaphore.tryAcquire`/`release` and others; `GP/artifact/KlibMemberFunctionTest.kt`, and on the iOS
  simulator `WalkedKlibArtifactMemberFunctionTest`.
  Since #234 a **Kotlin object crosses as a handle**: a type whose class is a public, non-generic,
  non-value `class`, `interface`, `enum class` or `object` of the walked klib is an `OBJECT` slot
  or result, non-null or nullable, cast in generated source as the jar producer casts one, and a
  handle result carries its ancestry from the klib's supertypes. Handles are the common
  trampoline's and `HandleTable`'s, under the jar side's rules: passing one back into Kotlin takes
  no second slot, and dropping it releases it exactly once. Still declined, each with its reason:
  a type of another klib (`kotlin.coroutines.CoroutineContext`), a class of package `kotlin` other
  than the boundary's primitives, an extension (the pinned compiler's klib ABI has no receiver type),
  and any `@Deprecated` declaration (the ABI has no level, and a `HIDDEN` one, such as
  `kotlinx.coroutines.Job0`, cannot be called from source). From `kotlinx-coroutines-core` this binds
  `Job`, `SupervisorJob`, `MainScope`, `Mutex`, `Semaphore`, `newSingleThreadContext` and
  `newFixedThreadPoolContext`. `GP/artifact/KlibScannerTest.kt`, `GP/artifact/KlibObjectHandleTest.kt`;
  `python-multiplatform-gradle-plugin/fixtures/klib-artifact` builds `androidNativeArm64` and
  `iosSimulatorArm64` from one table in `nativeMain`, and its suite (`WalkedKlibArtifactHandleTest`,
  `WalkedKlibArtifactTableTest`, `WalkedKlibArtifactPythonImportTest`) runs through Gradle on the iOS
  simulator (`iosSimulatorArm64Test`, and on CI in `ios.yml`'s `klib-fixture` job, #240); on
  androidNative it is linked by the build and run by hand.
- **B-3** Default arguments can be omitted from Python (a call per subset of defaulted parameters,
  ambiguous omissions refused). `Status: implemented`, `GP/artifact/DefaultOmissionTest.kt`,
  `python-multiplatform-gradle-plugin/fixtures/artifact/.../WalkedArtifactDefaultOmissionTest.kt`, `PM/commonTest/.../PythonxDefaultsTest.kt`.
- **B-4** Value classes round-trip; only allow-listed ones (e.g. `Dp`) may be written as their raw
  primitive. `Status: implemented`, `GP/artifact/ComposableValueClassSlotTest.kt`,
  `WalkedArtifactPythonImportTest.kt`. A value class is opened as its raw primitive only when both its
  constructor and its underlying property are public; otherwise (`TextUnitType`) it crosses as a boxed
  handle, so a parameter of it takes the boxed value of its own class
  (`TextUnit(30, TextUnitType.Sp)`) and a raw number is refused (#168,
  `python-multiplatform-gradle-plugin/fixtures/artifact/.../WalkedArtifactValueClassBoxTest.kt`).
- **B-5** Python callables can fill Kotlin function-typed parameters (arity and callability checked).
  `Status: partial`, `python-multiplatform-gradle-plugin/fixtures/artifact/.../WalkedArtifactCallbackTest.kt`; a value-returning slot
  (`() -> Float`) does not yet accept a Python callable (`M3ProofRenderTest.kt`
  `aValueReturningFunctionSlotDoesNotYetAcceptAPythonCallable`).
  A parameter typed as a **functional interface** (a Java interface with one abstract method, such as
  `java.lang.Runnable`, or a Kotlin `fun interface`) whose method returns `Unit`/`void` and which is
  not generic takes a Python callable as well as a Kotlin handle (#216). Its slot type name is the
  interface, `|`, and the equivalent `kotlin.FunctionN` (`java.lang.Runnable|kotlin.Function0()->kotlin.Unit`):
  the binding layer makes a callable into that function type and checks a handle against the
  interface, and generated source converts a wrapper with a SAM lambda (`java.lang.Runnable { f() }`)
  and casts anything else, with no reflection. A JDK interface is read from the JDK the build runs
  on, any other from the consumer's jars. The stub shows the slot as a `Callable`. A functional
  interface whose method returns a value, or a generic one, is left as it was: a handle parameter
  (Compose's `Alignment` is one). `Status: implemented` on desktop, `GP/artifact/SamParameterBindingTest.kt`,
  `python-multiplatform-gradle-plugin/fixtures/artifact/.../WalkedArtifactSamParameterTest.kt`.
- **B-6** `@Composable` functions are callable from Python inside a composition; a Python click reaches
  a Kotlin callback and the redraw shows it. `Status: implemented` on desktop,
  `python-multiplatform-compose/fixtures/compose/src/desktopTest/` (`ComposableRenderTest.kt`, `M3ProofRenderTest.kt`,
  `CallbackDrivenRenderTest.kt`, pointer/drag render tests), `GP/artifact/ComposableBindingTest.kt`;
  `planned` on Android, iOS and wasm.
- **B-8** A host draws a Python-declared application root with
  `python.multiplatform.compose.PythonAppView(modifier, module, attribute)` (or `PythonWidget(composableName, modifier, moduleName)` or `PythonWidget(root: PyObject)`, inside a `PythonLauncher { }` that starts the interpreter),
  from the `python-multiplatform-compose` module (`python-multiplatform` itself does not depend on
  Compose). The root is a Python callable, or a Compose `State` that Python holds whose value is that
  callable. The state is read inside the composition, so a Python write into it replaces the root on
  the next frame with no host call; Python callables a root passed into composables are released when
  that root is replaced or the composition is disposed. The entry point names no library.
  `Status: implemented` on desktop, `python-multiplatform-compose/fixtures/compose/.../PythonAppViewRenderTest.kt`; the module
  compiles for Android, nothing runs there yet.
- **B-9** A Python root keeps state across the recreation of its composition with
  `python.multiplatform.compose.rememberSaveableWrapper(initial)` (the pycomposeui function of that name),
  callable from Python inside a composition. A Python `int` (32 bit) becomes `rememberSaveable { mutableIntStateOf }`,
  a larger one `mutableLongStateOf`, a `float` `mutableDoubleStateOf`, a `bool` or `str` `mutableStateOf`; the
  returned `MutableState` is read and written through `.value`. A value written from Python survives
  `SaveableStateRegistry.performSave()` and a new composition over a registry built from the saved map; without
  a registry the new composition starts at `initial`. Any other value (a Python `list`) raises an error that
  names its type. `Status: implemented` on desktop (#174),
  `python-multiplatform-compose/fixtures/compose/.../RememberSaveableRenderTest.kt`.
- **B-7** The plugin generates `.pyi` stubs for the Kotlin-named modules only, under Kotlin names
  plus their U-12 Pythonic aliases (`fill_max_width = fillMaxWidth` beside each module `def`, a second
  `ClassVar` or `@property` on a receiver's class, a second member of a callable module), parameters
  written under the keyword `inspect.signature` shows (the Pythonic one; a stub can name a parameter
  once, so a Kotlin keyword type-checks as an error although it runs), `= ...` for a Kotlin default,
  receiver positional-only; a class module folded for a case-insensitive filesystem (#44) carries no
  aliases. Types are
  the declared Kotlin types: one stub class per bound type in the module of its own package, `Dp | float`
  for a value class bound as its primitive, `Callable[...]` for a function type, `| None` for a nullable;
  an extension function is also a callable attribute of its receiver's class; an overload set is
  `@overload`ed under its base name in table-key order; a required parameter after a defaulted one is
  keyword-only like `inspect.signature`. It emits nothing under `pythonx` and converts no namespace. `Status: partial` - `GP/stubs/PyiRenderingTest.kt`,
  `GP/stubs/TypedStubTest.kt`, `GP/stubs/KotlinNamesOnlyStubTest.kt`,
  `python-multiplatform-gradle-plugin/fixtures/artifact/.../WalkedArtifactStubTest.kt`, `.../StubSignatureAgreesWithRuntimeTest.kt`,
  `GP/stubs/CompanionFactoryStubTest.kt` (#78: a name that is both a function and a Kotlin-named
  module is stubbed in its parent package as one attribute whose type has `__call__` (the function or
  its `@overload`s) and the module's constants and functions as members, never as a bare `def`, which
  would hide `TextRange.Zero` from a checker),
  `.github/scripts/stubs/check-stubs.sh` (mypy over the Compose stubs). A class whose name is also a function in
  its module (Kotlin's factory idiom, `kotlinx.coroutines.Job` and `fun Job()`) is stubbed under the
  stub-private name `_<Name>` (#217): the function keeps `Job`, as at run time, and every use of the
  type, the factory's result included, is `_Job`, a class with the type's properties and member
  functions; nothing at run time is named `_Job`. Except that a class's own bound constructor is that class's `__init__`
  (`@overload`ed for several), so the class keeps its stub, members and properties; a single one's
  docstring carries its table key after `Kotlin constructor: `; its `__`-suffixed table-key spellings
  stay module functions; a value class bound as its primitive keeps its constructor as a module function
  returning the primitive (#73, `GP/artifact/TextStateBindingTest.kt`,
  `python-multiplatform-gradle-plugin/fixtures/artifact/.../WalkedArtifactStubTest.kt`). In Python the class name is the constructor,
  as `Typography(...)` already was (`M3ProofRenderTest.kt`). A property is a `@property` (with a setter for a `var`) of its receiver's stub
  class, its docstring carrying its table key after `Kotlin property: `; where that class has no stub,
  a comment with the same marker says so (`GP/stubs/PropertyStubTest.kt`). An object constant is annotated with its
  declared type even when that type is nested in the object (`Alignment.End: Horizontal`, a class of the
  object's own module; elsewhere `androidx.compose.ui.Alignment.Horizontal`); the object's own type
  stays `Any` (#53, `GP/stubs/ObjectStubTest.kt`). An extension property whose receiver is a type nested in such an object is a `@property` of that nested class in the object's module (`Icons.Default: Filled`, `Filled.Add: ImageVector`, #68), not a marker comment in the package module.
  A stub class lists **every** Kotlin supertype that has a stub class as a base, not only the first
  (`Arrangement.HorizontalOrVertical(Horizontal, Vertical)`, so a `Vertical` slot accepts it): `Any`
  and `java.lang.*` are dropped, and a base already implied by another listed base's known ancestry
  is dropped, so the order is a valid Python MRO (#71, `GP/stubs/SupertypeStubTest.kt`, and the
  `Column`/`Row` `SpaceBetween` lines of `.github/scripts/stubs/consumer.py`).
- **B-8** CI generates the stubs over the Compose version the build resolves and publishes them
  (`.github/workflows/stubs.yml`): workflow artifact `kotlin-stubs` on every push to `develop`, with a
  README naming the Compose version and the commit, and `kotlin-stubs.zip` on every `v*` tag's release.
  `Status: partial`, the workflow could not be run where it was written; its YAML parses and its
  assemble step was executed locally.
- **B-9** When a configuration named `typedpythonStubs` exists (toolchain's plugin creates it), every
  stubs task's output directory is added to it, with the task dependency travelling with the files, in
  either plugin application order. Without that configuration nothing happens and none is created.
  `Status: implemented`, `GP/TypedPythonStubsWiringTest.kt` (a `ProjectBuilder` project; the toolchain
  side that consumes the configuration is toolchain#23 and is not exercised here).
- **B-10** pythonx-compose's `UI.ipynb` scenarios run end to end on desktop (#26), in the spellings
  pythonx-compose decided: with the pythonx-compose **wheel** installed (a path or version input; no
  wheel fails every scenario by name unless `-PnotebookE2e.skip=true` disables them) and the host
  drawing `PythonAppView(module = "pythonx.compose.runtime", attribute = "app_root")` (B-8): `import main` draws the root it
  declares; the notebook reads and writes the state the screen shows and the screen follows within four
  frames; a cell that redeclares the root with `@app` puts it on screen within four frames with no
  update call, and disabling the replacement wiring (a stub `@app`, or a host handed the root once)
  leaves the screen unchanged; each practice cell (Text, Button, Card, Icon, Column, Row, Arrangement,
  Spacer) draws exactly what the same composables draw from Kotlin; `TextField(state=...)` takes a
  Hangul input-method composition with no Python function started and the root not rerun, holds the
  composing range in Compose, and the notebook reads the committed text; a write from Python outside
  the composition reaches the screen within four frames. Notebook content with no decided or Kotlin
  counterpart is listed, not imitated. `Status: implemented` on desktop: `python-multiplatform-compose/fixtures/notebook-e2e/` (20 tests, and a
  README with the cell map, the wheel commands and the gaps), 20 passed, 0 failed against the
  pythonx-compose `develop` wheel on 2026-10-04 (after #146, #168 and pythonx-compose #88), and again
  with the notebook's own state spelling (`main.App.messages.getValue()` / `setValue(...)`, #174)
  against pythonx-compose f6dd3e2. CI runs it on every push to `develop`
  (`desktop.yml`, job `notebook-e2e`, #213), against a wheel built from pythonx-compose `develop`.
  **In a GraalVM native image on macOS** (#249, #191's first milestone): `NotebookE2eMain` runs the
  same 20 scenarios by direct calls, and `notebookE2eNativeRun` requires 20 passed with `JAVA_HOME`
  and `PYTHONHOME` removed and no `libjvm` linked (only system frameworks); the prefix
  (`stageNativeImagePythonHome`, L-9), the wheel and the app are staged beside the executable. 20
  passed, 0 failed on 2026-10-17 (Liberica NIK 25.0.4.1, a 129 MB image), also from a relocated copy
  under `env -i`. The image takes `ui`, `ui-text` and `foundation` from compose-multiplatform-core-extended
  (`composeExtended.*` in `gradle.properties`, one place), because upstream Compose dispatches its
  main-thread work to skiko's Swing queue, which starts AWT, and AWT in an image needs the JDK's
  `libawt`, which links `libjvm`; the fork sends it to `Dispatchers.Main` when
  `compose.main.dispatcher=coroutines`, and the fixture supplies a single-thread one with no AWT.
  `desktopTest` keeps upstream Compose. Not on CI yet: the fork's artefacts are in mavenLocal only.
- **B-11** (#192) The artifact walker skips every declaration whose package has an `internal` segment
  (`*.internal` and `*.internal.*`, as in `androidx.compose.ui.internal`), on jars and on klibs. That is the
  Kotlin `.api` dump convention: a library that puts a declaration in such a package has declared it
  non-public API even though it is public in bytecode. The walker also honours
  `pythonBindings.excludePackages`, with the namespace matching the KSP side uses (a package equal to an
  entry, or below it). A skipped declaration gets no binding and no `.pyi` stub, and no declined
  `DeclarationModel` either: it is not part of the library's surface. The rule is a blacklist (decision 12.7
  in `AGENTS.md`): everything else that is public stays bound; it is not a list of what to expose. A segment
  must equal `internal` exactly (`internals` and `internal_api` are ordinary packages).
  `Status: implemented`, `GP/artifact/InternalPackageExclusionTest.kt`.

## 7. Threading and builds

- **T-1** GIL builds: Kotlin threads attach a thread state and serialise on the GIL. `Status: partial`
  (see C-4).
- **T-2** Free-threaded builds selected with `-PpythonFreeThreaded=true` download a different CPython
  asset. `Status: partial` (desktop only, opt-in; default is `pythonFreeThreaded=false` in `gradle.properties`)
 , `GP/PythonHomeStagingTest.kt`, `PM/desktopTest/.../FreeThreadedGCGateTest.kt`, `VersionsTest.kt`.
  3.14t runs the whole desktop suite with `-PpythonFreeThreaded=true` (236 tests, 0 failures; ROADMAP §9).
  Only desktop has free-threaded prebuilts; Android and iOS do not. `Py_LIMITED_API` is not defined, so
  `abi3t` is not a blocker.
- **T-3** `python-multiplatform` publishes per-target, per-flavour CPython include directories as
  `Provider<Directory>` carrying the extraction task (`cpythonIncludeDirectories` extension; see
  `docs/platforms/python-version-acquisition.md` §7). `Status: partial` (only the configured flavour is
  extracted), `GP/CPythonIncludeDirectoriesTest.kt`.
- **T-4** `python-multiplatform` publishes the CPython version it embeds: extension `pythonMultiplatform`,
  Gradle attributes `org.thisisthepy.python.version` / `org.thisisthepy.python.free-threaded` on every
  consumable `*Elements` configuration, and the resource `META-INF/python-multiplatform/python.properties`
  in the jar and AAR (see `docs/platforms/python-version-acquisition.md` "Published version"),
  `GP/EmbeddedPythonVersionTest.kt`.
- **T-5** Every acquired CPython reports the pinned version: `PY_VERSION` in each target's `patchlevel.h`
  equals `pythonVersion`, and a `downloadPython_*` task fails the build, naming the target and both
  versions, when it does not (issue #47: iOS shipped 3.14.6 under `pythonVersion=3.14.7`). The iOS
  extraction is stamped with its lock key so bumping the pinned archive discards the old tree.
  `Status: implemented`, `GP/AcquiredHeaderVersionTest.kt` (the real-tree case needs the trees from
  `downloadAllPythonBuilds`; it skips when nothing was acquired).

## 8. Measurement

- **X-1** Measurement tests record the cost of FFI calls, upcalls, string marshalling and generated
  proxies per platform; `docs/investigations/cost-table.md` is rendered from them.
  `Status: implemented` as instrumentation (`PM/commonTest/.../BenchmarkTest.kt`,
  `UpcallOverheadTest.kt`, `GeneratedProxyCostTest.kt`, `PM/desktopTest/.../DesktopOverheadBenchmark.kt`,
  `PM/androidInstrumentedTest/.../JniOverheadBenchmark.kt`, `PM/wasmJsTest/.../WasmMarshallingOverheadTest.kt`).
  These record numbers; they assert no thresholds. One is `@Ignore`d
  (`DesktopOverheadBenchmark.stringMarshallingShareOfARealisticCall`, aborts after 200k calls).

## 9. Planned

- **N-1** Sub-interpreter-free parallelism on free-threaded CPython on every platform (today desktop only,
  opt-in 3.14t; see T-2 and ROADMAP §9). `Status: partial`.
- **N-2** Compose through Python on Android, iOS and wasm. `Status: planned`.
- **N-3** Binding Kotlin/Native klib declarations at run time (B-2). `Status: planned`.
- **N-4** Linux and Windows desktop runs in CI (P-2). `Status: planned`.
- **N-5** Native-image upcall verification as an automated test (U-6). `Status: planned`.
- **N-6** Compose state created and written from Python through the binder:
  `androidx.compose.runtime.mutableStateOf(x)` callable, and `.value` of the returned `MutableState`
  readable and writable on its proxy. Implemented by #38 (B-1, U-10); B-8's test now writes its root
  through `state.value = root` and the KSP-bound stand-in is gone. `Status: implemented`.
- **N-7** TypedPython soundness gate (INTENT §1.4; `docs/design/typedpython.md` §4.2): checking a set of
  user Python files reports every Pyrefly type error, the Any-leak kinds (`implicit-any-parameter`,
  `unannotated-return`, `unknown-variable-type`, `no-any-return-implicit`, `no-any-return-explicit`,
  `explicit-any`) and any user-code expression of type `Any` outside `cast()` (`any-flow`) as errors;
  the forbidden list (`eval`/`exec`, computed-name `getattr`/`setattr`/`delattr`, patching an imported
  module or a module-level class, rebinding an unannotated name to a different type, and a list/set/dict
  display whose inferred element type is a union) as warnings in `checked` mode and errors in
  `compiled` mode. Nothing
  inside dependencies is reported. `python -m typedpython check` exits 0 / 1 / 2 for clean / errors /
  tool failure. `Status: implemented`, asserted by `python-multiplatform-ksp/src/test/python/`. It runs in
  every consumer build: `python-multiplatform-ksp` publishes the gate as `typedpython-gate@zip` (#212) and
  toolchain's `typedpythonCheck` installs it with pinned hashes before each `buildPython` (toolchain #91;
  #20). Rebinding and mixed containers are found by a second Pyrefly pass over a
  probed copy (design §6.1), which runs only when a file has a name assigned twice or a container
  display; `for` targets and comprehension variables are not probed.
- **N-7b** The type gate also runs when a module is imported in development mode (maintainer decision
  2026-10-04, agent-first): in a notebook cell, a hot reload or a debug run, importing a project module
  first checks it with the N-7 gate, and a type error stops the import with the gate's diagnostics before
  any of the module's code runs. Release builds rely on the build gate (#20) and skip it. Results are cached
  by file content, so an unchanged module is not checked again. There are no per-call runtime type checks:
  compiled and interpreted code behave exactly as CPython does (N-8). Form: `typedpython.importgate.install(source_roots)`
  puts a meta path finder first on `sys.meta_path` (`uninstall()` removes it; `install_from_env()` installs it
  when `TYPEDPYTHON_IMPORT_GATE` lists source roots). Only modules whose source file lies under a root are
  checked; the stdlib and dependencies never are. A gate error raises `ImportGateError` (an `ImportError`
  with `.diagnostics`) before the module runs. Results are cached in memory by file content hash, failures
  included. `Status: implemented`, `python-multiplatform-ksp/src/test/python/test_importgate.py`.
- **N-8** TypedPython compilation to C (INTENT §1.4; design §4.3; #41). `@compiled` (a builtin name, no
  import, #42) and a first-line `# typedpython: compiled` mark functions; the compiler lowers them
  through the typed IR (`typedpython/ir.py`) to C against the CPython C API and builds an extension with
  the platform C compiler. Compiled functions return what CPython returns (IR contract: checked i64
  with deopt to the interpreted function, Python floor division/modulo, exact int/float comparison,
  CPython's exception types and messages; tracebacks are the one accepted difference). Call cycles
  (direct and mutual recursion) are compiled: every compiled function counts its frame (`tp_enter_call` /
  `tp_leave_call`, added to the Python frame depth and compared with `sys.getrecursionlimit()`), so recursion
  past the limit raises `RecursionError('maximum recursion depth exceeded')` where CPython does, and a C-stack
  guard keeps the process alive at any limit (#57). Compiled loops check the eval breaker so signals
  (`KeyboardInterrupt`), pending calls and GIL hand-off reach Python during long compiled work (#141): a
  `while` loop counts down once per iteration; a `for ... in range(...)` loop, whose trip count is known,
  runs in chunks of `TP_POLL_INTERVAL` iterations and polls once per chunk (#206), with `break`,
  `continue` and `return` meaning exactly what they mean without chunking (trip counts just below, at and
  above a multiple of the interval, negative steps, empty ranges and ranges spanning the i64 limits all
  behave as CPython's `range`). Functions holding an array copy-in or entry-globals snapshot do not poll.
  `Status: implemented` for desktop host builds
  (`test_pipeline.py`, `test_cgen*.py`, `test_recursion.py`, `test_eval_breaker.py`, `test_fast_entry.py`,
  `test_free_threaded.py`; free-threaded 3.15t, #159). Not yet: shipping compiled modules in an app through
  pypackpack (#25), methods of fixed-layout classes (#155). Native ints in impure functions are N-13 (#232).
- **N-9** TypedPython memory safety is proved on the IR before C is generated (#41; maintainer decision
  2026-10-03). Each property, and what happens when it cannot be proved:

  | Property proved on the IR | If not proved |
  |---|---|
  | No local read before assignment on every path | function not compiled; diagnostic names the local |
  | Every node's operand types match; no implicit conversion | not compiled; diagnostic |
  | No deopting node in an impure function | not compiled; diagnostic |
  | Array access in bounds, or a runtime bounds check kept | the check stays (never an unchecked access) |
  | Array parameters only indexed, stored, `len`-ed; no aliasing (entry guard) | not compiled; diagnostic |
  | Integer ops proven safe by the verifier's intervals (constants, `len`, `range` variables, guards such as `if x < e` / `while lo < hi`, joined local assignments, `//` and `%` by constants) | emitted unchecked (a shift/mask for `//`/`%` by a power of two of a non-negative value); the sanitizer CI run builds with `TP_CHECK_PROVEN` and turns a wrong proof into a SystemError (#148) |
  | `array.array[float]` / `array.array[int]` parameters (exact `array.array`, typecode `d` or `q`/`l`, 8-byte items) are read and written in place: the buffer export is held for the call and released on every exit, and every element access is one relaxed atomic 8-byte load or store, so each access is atomic like the interpreter's `a[i]`; no copy-in and no limit on the number of buffers. Open (#149): while the export is held, another thread's resize of that array raises BufferError. numpy arrays: see the next row | anything else: object path |
  | numpy `npt.NDArray[np.float64]` / `NDArray[np.int64]` parameters (exact `numpy.ndarray`, 1-D, C-contiguous, native byte order, writable when stored to) take the same buffer path (#220, step 1) when the function accesses the array purely by element (`x[i]`, `len(x)`); a function that uses it as a whole object (`np.sum(x)`, `x.T`, passing `x` on, a slice) compiles as before with the parameter an opaque object. Other dtypes, including bool, are opaque objects. Results follow the interpreter: a value derived from an element is a numpy scalar there, so it is boxed as `np.float64` / `np.int64` / `np.bool` when it escapes (return, `Box`), decided per value at run time by a taint flag; a function declared `-> float` / `int` / `bool` that holds a numpy array returns the interpreter's object. `float(v)` of such a value is a plain float. An out-of-range index raises numpy's `IndexError("index i is out of bounds for axis 0 with size n")`. Arithmetic on a value derived from an element follows numpy's scalar semantics (#220, step 2): float `/`, `//`, `%` never raise ZeroDivisionError (inf / nan, with numpy's result bits and sign rules, `//` and `%` as numpy's `divmod`), int64 `//` and `%` by zero give 0, int64 `/` converts both operands to double. numpy's RuntimeWarning (category and text exactly as numpy's, e.g. `overflow encountered in scalar add`, `divide by zero encountered in scalar divide`, `invalid value encountered in scalar floor_divide`) is emitted once per flagged operation, with the source file and line of the operation. A pure function does not emit it itself: on any floating-point exception (overflow, invalid, divide by zero) or int64 wrap or division by zero it deopts, so the interpreter emits it; an impure function cannot deopt after an effect, so the compiled code emits the warning (`PyErr_WarnExplicitObject`, honouring `warnings` filters and the module's `__warningregistry__`). Float `+ - * / // %` on such values is compiled in both kinds of function, including a store of the result into the array (`x[i] -= d * m`); int64 `/ // %` is compiled in a pure function only. Int64 `+ - *` and `// %` of an element in an impure function (a native INT would have to be a numpy scalar) stay on the object path | anything else: object path |
  | A function holding an array parameter runs no user code: closed, and no slot read or `C(args)` whose slow path runs user code, in its whole call tree (#203) | not compiled; diagnostic |
  | OBJ references follow one ownership rule; no IR form for free, borrowed alias or NULL read | not compiled; diagnostic |
  | Integer overflow checked and promoted (deopt); an op marked `proven` must re-prove its interval fits i64 | not compiled; diagnostic `verify/proven-overflow` |
  | `Call.redo` only from an impure caller to a pure, may-deopt callee returning F64/BOOL/NONE | not compiled; diagnostic `verify/redo` |
  | `entry_globals` only in a closed function (no user code runs); a call of a function with entry globals is a deopt point in its caller unless it is a legal redo or the caller snapshots the same globals | not compiled; diagnostic `verify/entry-global-open` / `verify/deopt-impure` |

  Generated C reaches memory and `PyObject`s only through the runtime helpers. ASan/UBSan CI,
  differential tests against the interpreter and fuzzing back the proof. `Status: implemented`
  (`verify.py`; `test_verify*.py`, including the mutation fuzzing; the ASan/UBSan CI job, which also
  re-checks every proven op with `TP_CHECK_PROVEN`).
- **N-10** TypedPython incremental compilation (#41; maintainer decision 2026-10-03). The unit is a module.
  Its cache key is the source hash, the interface hashes (typed signatures) of the modules it depends
  on, the compiler version, the target platform and the build flags; a body-only change leaves the
  interface hash unchanged, so dependent modules are not recompiled. C objects are cached per module.
  The Gradle task declares exact inputs and outputs so up-to-date checks and the build cache apply.
  Tests: changing one function body recompiles that module only; an unchanged rebuild spends ~0 s in
  the compile step. `Status: partial`: the per-module cache and its key are implemented in Python
  (`incremental.py`, `pipeline.compile_project`; `test_incremental.py`, `test_pipeline_incremental.py`);
  the Gradle task with declared inputs and outputs does not exist yet, because compiled modules are built
  through pypackpack's compile slot (#25, #113).
- **N-11** TypedPython fixed-layout classes (M2b, 2026-11-21; design §4.3.3). A class compiles to fixed
  field access only when its layout is fixed **in CPython too**: it declares `__slots__` listing exactly
  its annotated instance fields, has no base but `object`, no metaclass, no decorator but `@compiled`,
  and (gate, compiled mode) nothing in the project subclasses it or assigns its class attributes. The
  compiler never adds `__slots__` itself, that would change what CPython does (`__dict__`, `vars()`,
  adding attributes). What is compiled, keeping CPython's results:
  - field reads/writes on a value guarded `type(x) is C` go straight to the slot (the member descriptor's
    offset, captured and checked at module init); an unset slot raises CPython's AttributeError; values
    are objects (no unboxing of fields in impure code);
  - a slot read is not an effect (no user code runs: the attribute is a member descriptor, checked at
    init, and the exact-type guard rules out subclasses), so a function that only reads fields can stay pure;
  - `C(args)` whose `__init__` only assigns each slot once from its parameters is lowered to allocate and
    fill the slots directly (the same observable result as running that `__init__`); any other `__init__`
    is called as an object;
  - methods compile like functions with `self` guarded `type(self) is C`; anything else on the class stays
    interpreted;
  - a function that writes a slot is never "closed" (no `entry_globals` snapshot): overwriting a slot can
    drop the last reference to the old value, and its `__del__` is user code that can rebind globals. This
    is stricter than the IR contract requires, on purpose;
  - a change after init keeps CPython's results: every slot access checks `type(x) is C` and the
    class's `tp_version_tag` against the one captured at init (a tag is never reused; 0 never matches),
    and every `C(args)` checks that the module global still is the captured class (a module-dict
    watcher, or a dict lookup per call when no watcher id is free). On a mismatch an impure function
    takes the generic path (`getattr`/`setattr`/calling the current global) and a pure one deopts.
    A class that defines `__getattr__`, `__getattribute__`, `__setattr__` or `__delattr__` is not
    compiled.
  A class outside these rules stays interpreted with a reason. Plain instance methods of such a class
  compile too (#155): `self` is guarded `type(self) is C` like a `Param.cls` parameter, so a subclass
  instance or a foreign `self` runs the original function; the class dict holds a descriptor whose
  bound method behaves like a function's, and a compiled method calling a method of a compiled class is
  a direct C call under the same per-access class checks. Decorated methods (static, class, property),
  dunders and methods that use `super` stay interpreted with a reason.
  `Status: implemented (module-level functions and plain instance methods)`.
- **N-12** TypedPython object cost: virtual objects, refcount elision, region allocation (design §4.3.4).
  Every execution of compiled code must be indistinguishable from some valid CPython execution. An object
  of an N-11 class without `__del__` and without a `__weakref__` slot may live as a C struct (a virtual
  object) and is turned into a real object (materialised) just before any observation point:
  - leaving the function, or being stored into a global, a container or a real object's field;
  - being passed to unknown code, or reaching `id()` or the default `hash`/`repr`;
  - **any call into unknown code while it is alive**, because `gc.get_objects()` and `tracemalloc` see it
    without being passed it. At such a call every live virtual object is materialised, in allocation order.
  `is`, `type()` and `isinstance` on virtual objects are decided statically. An increment/decrement pair is
  elided only when another owned reference keeps the object alive throughout and no unknown code runs in
  between, so every refcount that can be observed is exact. **On free-threaded 3.15t (no GIL) both
  borrowed field reads and pair elision apply only to objects proven thread-local** (never escaped to a
  global, a container, another thread or unknown code; #193, decision D5): another thread could otherwise
  write the slot and free the child between the read and its use. Every other field read takes a real
  reference, or reads inside CPython's per-object critical section (`Py_BEGIN_CRITICAL_SECTION`) when a
  sequence of reads must be consistent. A free-threaded stress test (one thread reading a field, another
  replacing it) under ASan/TSan is part of N-12's tests. Virtual objects whose count is unbounded live in
  a region tied to one activation (the innermost scope that bounds their lifetime) and are freed together;
  real objects are never placed in a region. A failed region allocation raises `MemoryError` where CPython
  would. The verifier proves non-escape, materialisation before every observation point, a keeping
  reference for every elided pair, and region lifetimes; anything unproved compiles as N-11. Differential
  tests include `id`, `gc.get_objects` count and order, `gc.get_referrers`, `sys.getrefcount`,
  `tracemalloc`, `is` between virtual and real objects, and `MemoryError` during construction.
  **Outside the identity: when the cyclic garbage collector runs** (user decision, 2026-10-04). Not
  allocating changes when collections are scheduled, and so when finalisers, weakref callbacks and
  `gc.callbacks` of *unrelated* cyclic garbage run. The language reference leaves this to the
  implementation ("An implementation is allowed to postpone garbage collection or omit it
  altogether", Data model §3.1), and CPython itself changed it in 3.14 (incremental GC). Everything
  else above stays exact. `Status: planned (design)`.
- **N-13** TypedPython native ints in impure functions (#232; INTENT §1.4: compiled code computes what
  CPython computes). An impure function cannot deopt after an effect, so its `int` locals were CPython
  objects and every `+`, `//`, `%` and comparison was a generic `PyNumber_*` / `PyObject_RichCompare`
  call, slower than CPython 3.15's specialising interpreter. The IR has a type `INT` for them: a Python
  int that is **either a native i64 or, after an overflow, a PyLong carried for the rest of that value's
  life**. A function never deopts because of an `INT`; it promotes in place.
  - **Where an `INT` is.** An `int`-typed local of an impure function (not a `range` target, not a
    parameter) whose every assignment lowers to an `INT` (an arithmetic result on `INT`/i64 operands, an
    int literal, a native i64 value widened by `ToInt`, or the result of a redo `Call`, below). A local
    that is also assigned from an object (a call of an unknown callable, a global, `int(x)`) stays an
    object, as before. An `INT` is created only inside a function; parameters, returns and entry globals
    stay i64 or object, so the signature and the entry guards are unchanged.
  - **Arithmetic.** `+ - * // % << >>` and unary `-` and `+` on `INT` operands run natively when both
    operands are native and the result fits i64. When an operand is a PyLong, or the native result does
    not fit (`2**63-1 + 1`, `-2**63 - 1`, `2**62 * 4`, `-(-2**63)`, `-2**63 // -1`, a left shift past
    bit 63), the operation is CPython's own `PyNumber_*` on the two values as exact ints, and the result
    is native again if it fits i64, else a PyLong. The result is bit-for-bit what the interpreter
    computes, including after side effects; nothing is redone and nothing deopts. `//` and `%` use
    floor semantics (sign of the divisor), a zero divisor raises CPython's `ZeroDivisionError` with the
    running interpreter's message, a negative shift count raises `ValueError('negative shift count')`,
    a shift by a huge count raises CPython's `OverflowError` / `MemoryError`. `/` of ints is not an
    `INT` operation: both operands are boxed and `PyNumber_TrueDivide` runs (the result is a float
    object).
  - **Comparison.** `==`, `!=`, `<`, `<=`, `>`, `>=` of two `INT`s is a native compare when both are
    native, else the exact comparison of the two ints; the result is a bool. `if x:` on an `INT` is
    `x != 0`.
  - **Boxing.** An `INT` becomes an object only at an object boundary (an argument of an object call,
    a store into a container, an f-string field, a return value, an operand of an object operation):
    a native value is boxed with `PyLong_FromLongLong`, a promoted one is the PyLong it already is. A
    constant used as an object is built once, at module exec, and read from module state (a new
    reference per use), not boxed per use.
  - **Calls.** A direct call from an impure function of a pure compiled callee that may deopt and
    returns i64 is a redo call (SPEC N-8, `Call.redo`): the callee's deopt, including an argument that is
    a promoted `INT` (it does not fit the callee's i64 parameter), is absorbed by running the
    interpreted callee on the boxed arguments (unobservable, the callee is pure). The result is an `INT`:
    exactly an `int` object (a native or a promoted value); any other object raises `SystemError`
    (`typedpython: the interpreted redo of f did not return an int`), as the existing redo does.
  - **Globals and builtins.** A module global or builtin read in an impure function (`Global`) is cached
    per name in module state and revalidated by the dict-watcher epoch (N-11): the cached object is used
    while no watched key of the module dict (or of the builtins dict) has changed, and any change to a
    cached name, a clear, a rebinding of `__builtins__`, or a failed watcher registration sends every
    read back to the full lookup (`tp_global`). The cache holds a borrowed pointer and takes its own
    reference on each hit, so it extends no lifetime: a rebound function, type or module is freed, and
    the `__del__` of anything it holds runs, exactly when CPython would run them (free-threaded builds
    take the reference with `PyUnstable_TryIncRef`, CPython 3.14+). The result is what LOAD_GLOBAL would
    return at that moment; a name that is unbound still raises `NameError`. This also removes the
    per-call creation of the `"__builtins__"` string.
  - **Cost rule (#150).** An `INT` operation (`BinOp`, `Compare`, `UnaryOp` on `INT` operands, a `Call`
    whose arguments are all native or `INT`) counts as native work, so an impure function whose loops do
    arithmetic on `INT`s is compiled. This includes such an operation directly in the body of a loop over an
    object (`for x in obj`, `for _ in range(n)` with an object `n`), which the rule otherwise ignores; float
    and array work in an object loop still does not count. A loop that does only object operations is
    still rejected.
    wordfreq's `word_of` and `main` are compiled.
  - **Proof.** The verifier checks the types of every `INT` node (an `INT` operation takes `INT`
    operands and gives an `INT`; `proven` is never set on one; an `INT` flows into an object slot only
    through `Box`; a `INT` local is declared and definitely assigned like any other) and the call rule
    above. The generated C releases an `INT`'s PyLong on every exit, as for any owned reference.
  - **Tests.** `test_native_int_impure.py`: i64 locals in impure functions; the i64 boundary for add,
    sub, mul, neg, floordiv, mod and shifts, after side effects and with no deopt, each equal to the
    interpreter; constants pre-boxed; global and builtin reads cached and invalidated by rebinding;
    wordfreq's `word_of` and `main` compile and print what CPython prints. `Status: implemented`.
- **N-14** TypedPython tracing, profiling and monitoring (#142; design §4.3.4 마.3). A compiled function
  has no Python frame, so at entry it checks whether tracing is active and, if so, runs the interpreted
  original (`__typedpython_interpreted__`, the deopt path), so the events are CPython's own. Active means:
  the calling thread has a `sys.settrace` / `sys.setprofile` function (per thread, as in CPython), or any
  `sys.monitoring` tool (ids 0..5) has a global event (any event type). A direct call from an impure compiled
  function to a compiled callee re-checks, because user code in the caller may have started tracing; a pure
  function runs no user code, so its callees are not re-checked. Local events (`set_local_events`, which pdb
  3.14+ and debugpy use for breakpoints) are read from the original function's code object
  (`_co_monitoring`, first member `local_monitors`); the layout is mirrored for CPython 3.14 and 3.15 and any
  other version stops the build with the `#error` "typedpython: tp_traced reads the 3.15 _co_monitoring
  layout". Cost: one public C-API call (`PyMonitoring_EnterScope`, version compare), two `PyThreadState`
  member loads and a pointer load plus a short byte scan; the Python-level query runs only when the global
  monitoring events changed. Known difference: `sys.monitoring` turned on while a compiled function is
  running produces events for that function from its next call on (CPython instruments the running frame;
  `settrace` agrees with CPython here, it affects new frames only). A fallback run counts as a deopt in
  `__typedpython_deopts__`, and clearing the events returns calls to the compiled path.
  `Status: implemented` -- `test_tracing.py` (events equal between compiled and
  interpreted for settrace, setprofile and monitoring; the `known_difference` test pins the one difference; local-only tools and their clearing).

---

## Outside intent, needs a decision

Findings where current behaviour is not covered by, or appears to conflict with, `INTENT.md`.
Unless marked resolved, nothing here was changed; each needs the maintainer's call.

Items 1–2 were resolved on 2026-10-02: stubs no longer turn `androidx.*` into `pythonx.*`, and the
binder no longer creates a `pythonx` module (U-8, B-7). Item 3 (member names in snake_case) was
answered by the maintainer the other way: converting member and parameter names is python-multiplatform's
own feature, and only namespaces are never converted. It was removed with items 1–2 by mistake and
restored by #131 (U-12).

4. **`pythonx` adapter machinery lives in this repository** (`PM/commonMain/.../ffi/pythonx/`). INTENT
   §2.3 places `pythonx` in pythonx-compose. Whether the generic adapter belongs here (as a service
   pythonx uses) or there is not stated.
