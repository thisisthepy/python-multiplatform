# Specification

The behavioural contract of python-multiplatform. It must stay inside [`INTENT.md`](INTENT.md).

**How to read the status.**

| Status | Meaning |
|---|---|
| `implemented` | Tests in this repository assert the behaviour; the test files are cited. |
| `partial` | Some of it is asserted, or only on some platforms, or only against a synthetic fixture. What is missing is named. |
| `planned` | Intended, not asserted by any test. |

Status here was assigned from the test sources at the time of writing (2026-10). "Implemented" means
"a test asserts it", not "a test was observed passing in this revision" — this document was written
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
  `Status: partial` — the suite has never been observed running on either.
- **P-3** The interpreter is not vendored: Gradle downloads, verifies and extracts CPython per
  platform; the version is set in `gradle.properties`. `Status: implemented` (build logic;
  `GP/PythonHomeStagingTest.kt` covers the naming, URL, checksum and stamp decisions).

## 1. Interpreter lifecycle

- **L-1** `Python3.initialize()` starts CPython; it is idempotent; `Python3.isInitialized` reports it.
  `Python3.exec(source)` runs statements, `Python3.eval(expr, mode, globals, locals)` returns a
  `PyObject`, `Python3.import(name)` returns a `PyModule`. Python errors surface as `PyException`
  carrying the real Python exception (not a printed-and-cleared one).
  `Status: implemented` — `PM/commonTest/.../ffi/Python3Test.kt`, `SmokeTest.kt`, `VersionsTest.kt`.
- **L-2** `Python3.runMain` / `runApp` run a module or `-c` source with argv, map `sys.exit` status to
  a return value, restore argv, and do not finalize the shared interpreter.
  `Status: implemented` — `PM/commonTest/.../RunMainTest.kt`, `RunAppTest.kt`.
- **L-3** Before `Py_Initialize`, a `PYTHONHOME` pre-flight check turns a missing or unusable stdlib
  into a catchable `IllegalStateException` naming the path and version, instead of CPython's process
  abort. `Status: implemented` on desktop (`PM/desktopTest/.../env/PythonHomeCheckTest.kt`);
  smoke-tested on all targets (`PM/commonTest/.../PythonHomeCheckSmokeTest.kt`).
- **L-4** Desktop: applying the Gradle plugin `io.github.thisisthepy.python.multiplatform.bindings`
  registers `stagePythonHome`, which downloads the matching CPython, verifies it against the release
  `SHA256SUMS`, caches it machine-wide and sets `PYTHONHOME` on `run`/`test`. A user-set `PYTHONHOME`
  is never overridden; `pythonBindings { stagePythonHome.set(false) }` disables it.
  `Status: partial` — decisions are unit-tested (`GP/PythonHomeStagingTest.kt`); no end-to-end test
  of the task.
- **L-5** Android: `PythonBootstrap.initialize(context)` unpacks the stdlib shipped in the APK assets
  to app-private storage once (a stamp written last decides re-unpacking, including after APK
  upgrades), sets `PYTHONHOME` and starts the interpreter. `Status: implemented` (needs a device) —
  `PM/androidInstrumentedTest/.../env/PythonBootstrapTest.kt`, `PythonPayloadStagingTest.kt`.
- **L-6** A consumer's Python payload (jar resource on desktop, APK asset on Android) is extracted once
  and put on `sys.path` before the first import. `Status: implemented` on desktop
  (`PM/desktopTest/.../env/PythonPayloadTest.kt`) and Android (`PythonPayloadStagingTest.kt`).
- **L-7** iOS: the framework carries no stdlib, so `PYTHONHOME` must point at one; the test build
  extracts it. `Status: partial` — exercised by the shared suite on the simulator; no iOS-specific
  lifecycle test beyond `PM/iosSimulatorArm64Test/.../AsyncioAvailabilityProbeTest.kt`.
- **L-8** Initialisation sets `builtins.compiled` to an identity decorator (an existing one is kept), so `@compiled` needs no import on any platform (N-7, issue #42). `Status: implemented` — `PM/commonTest/.../ffi/BuiltinCompiledTest.kt`.
- **L-9a** (issue #74) Desktop packaging and loading of `libpython`: every `lib/**` entry of the desktop jar is a real
  file (never 0 bytes -- Gradle extracts the python-build-standalone symlink `libpython3.14.so` as an empty file, so
  symlinks are resolved and the real library is packed under the `System.mapLibraryName` name, no `.so.1.0`); linux
  libraries are stripped of debug info when an ELF-capable `strip` exists on the build host (best-effort). The
  classpath library is extracted to `<user cache>/python-multiplatform/<python version>/<platform>/` (macOS
  `~/Library/Caches`, Windows `%LOCALAPPDATA%`, else `$XDG_CACHE_HOME` or `~/.cache`; fallback
  `java.io.tmpdir/python-multiplatform-<user>`; override `-Dpython.multiplatform.cache`), never the working
  directory, and reused when its size matches. `Status: implemented` --
  `PM/desktopTest/.../ffi/DesktopJarLibrariesTest.kt`, `ExtractLibraryCacheTest.kt`.

## 2. Low-level C API (downcall surface)

- **C-1** The CPython Stable ABI is declared once as `expect` functions in `EmbedAPI.kt` (≈330) with an
  `actual` on every platform; no CPython-deprecated symbol is declared. `Status: implemented` —
  `PM/desktopTest/.../ffi/EmbedApiSurfaceTest.kt` (source scan), `PM/commonTest/.../ffi/EmbedApiLowLevelTest.kt`
  (round trips, borrowed vs. new references, error indicator).
- **C-2** Desktop calls go through `MethodHandle.invokeExact`, pointers as `JAVA_LONG`; every
  `FunctionDescriptor` linked is declared in the native-image reachability metadata.
  `Status: implemented` — `PM/desktopTest/.../ffi/ReachabilityMetadataTest.kt`.
- **C-3** Android binds through `RegisterNatives` with the calling convention chosen per function;
  re-entrant functions are never `@CriticalNative`. `Status: implemented` —
  `PM/desktopTest/.../ffi/JniCallConventionClassificationTest.kt`, `PM/androidInstrumentedTest/.../JniWiringTest.kt`.
- **C-4** Every C API call holds the GIL. `Status: partial` — enforced by convention and README rules;
  the only direct test (`PM/commonTest/.../GilParkingTest.kt`) asserts only "does not crash". Releasing
  the GIL after initialisation is not enabled.
- **C-5** A thread CPython creates can upcall into ART: it is attached once per thread and detached when
  it dies. `Status: implemented` on Android — `PM/androidInstrumentedTest/.../UpcallThreadAttachTest.kt`.
- **C-6** wasm: `Py_ssize_t` boundaries and the `-1` error return are handled at the 32-bit ABI.
  `Status: implemented` — `PM/wasmJsTest/.../WasmPySsizeTBoundaryTest.kt`.

## 3. Object model (Kotlin uses Python)

All in `PM/commonTest`, so they run wherever the interpreter loads.

- **O-1** `PyObject`: `getAttr`, `setAttr`, `delAttr`, `getAttrOrNull`, call (`invoke`), `equals` /
  `hashCode` / `toString` delegate to Python. `Status: implemented` — `PyObjectTest.kt`.
- **O-2** `PyType`: name, bases, MRO, `isSubtypeOf`, `isInstance`, construct by call, `cast` refuses an
  incompatible object. `Status: implemented` — `PyTypeTest.kt`.
- **O-3** `PyException` is a Kotlin `Throwable` carrying the Python exception type and message.
  `Status: implemented` — `PyExceptionTest.kt`.
- **O-4** Basic types `PyInt`, `PyFloat`, `PyBool`, `PyString`, `PyNone`, bytes round-trip with Kotlin
  values. `Status: implemented` — `PyBasicTypesTest.kt`, `PyValueBytesConversionTest.kt`.
- **O-5** Collections implement the Kotlin collection interfaces: `PyList` (`MutableList`, `fromList`,
  views), `PyDict` (`MutableMap`), `PySet` (`MutableSet`, set algebra, frozenset), `PyTuple` (`List`),
  plus iterators. `Status: implemented` — `PyListTest.kt`, `PyDictTest.kt`, `PySetTest.kt`,
  `PyTupleTest.kt`, `PyIteratorTest.kt`.
- **O-6** Modules, builtins, callables (bound methods, class/static methods), utilities (range, slice,
  ellipsis), import and GC helpers. `Status: implemented` — `PyModuleTest.kt`, `BuiltinsTest.kt`,
  `PyCallablesTest.kt`, `PyUtilitiesTest.kt`, `PyImportTest.kt`, `PyGCTest.kt`.
- **O-7** Conversion: `PyValue` with selectable conversion strategies (`withContext`), lazily cached
  snapshots invalidated on demand; buffer-view types and types with no Kotlin counterpart are refused.
  `Status: implemented` — `ConversionTest.kt`, `PyValueLazyConversionTest.kt`.

## 4. Object lifetime

- **M-1** A Kotlin wrapper owns one Python reference and releases it when the wrapper becomes
  unreachable — no `close()` needed; explicit release is idempotent and runs exactly once.
  `Status: implemented` — `PM/commonTest/.../ref/GCLeakTest.kt`, `RefCountTest.kt`,
  `OwnershipLeakTest.kt`, `DoubleReleaseTest.kt`, `EvalCheckpointTest.kt`.
- **M-2** On Android below API 33 (no `java.lang.ref.Cleaner`), a `PhantomReference` path does the same.
  `Status: implemented` — `PM/jvmTest/.../PhantomCleanerRegistryTest.kt`,
  `PM/androidInstrumentedTest/.../AndroidCleanerPathTest.kt`.
- **M-3** Reference cycles that cross the boundary (Python → Kotlin proxy → Python) are collected by
  Python's cyclic GC, including on threads CPython created and for Python subclasses of proxies.
  `Status: implemented` on desktop (`PM/desktopTest/.../ref/CycleCollectionTest.kt`,
  `ksp-fixtures/app/.../RefHolderCycleCollectionTest.kt`); `partial` elsewhere — tests exist for
  native, Android and wasm (`PM/nativeTest/.../CycleCollectionTest.kt`,
  `PM/androidInstrumentedTest/.../CycleCollectionTest.kt`, `PM/wasmJsTest/.../WasmCycleCollectionTest.kt`)
  but `docs/roadmap/ROADMAP.md` §7 records remaining per-target gaps.
- **M-4** Handles given to Python never alias a reused slot; owned results release when Python drops
  them. `Status: implemented` — `PM/commonTest/.../reflection/HandleTableTest.kt`,
  `ProxyHandleLifetimeTest.kt`, `OwnedResultLifetimeTest.kt`.

## 5. Upcalls (Python uses Kotlin)

- **U-1** A KSP processor generates, at build time, a function table for every `public` Kotlin
  declaration in a module; `@PythonInternal` excludes a class or member. Fragments from several
  modules aggregate; a name claimed twice is an error. No runtime reflection is used.
  `Status: implemented` — `PM/commonTest/.../reflection/UpcallTableTest.kt`,
  `ksp-fixtures/app/src/desktopTest/.../GeneratedTableTest.kt`, `python-multiplatform-ksp/src/test/.../SourceRenderingTest.kt`.
- **U-2** One entry point per platform marshals arguments (int, float, bool, str, bytes with NUL,
  objects), turns a Kotlin exception into a Python exception and a `Unit` return into `None`.
  `Status: implemented` — `PM/commonTest/.../UpcallEntryTest.kt`, `UpcallTrampolineTest.kt`,
  `PM/nativeTest/.../UpcallRawEntryPointTest.kt`.
- **U-3** Generated Python proxies let Python write ordinary Python against Kotlin: construct a class,
  call methods, get/set properties (a `private set` is read-only), read companion / static members.
  A Kotlin package is importable under its own name. `Status: implemented` on desktop —
  `PM/commonTest/.../upcall/PythonProxyInstallTest.kt`, `ksp-fixtures/app/.../GeneratedDeclarationKindsTest.kt`,
  `GeneratedStaticPropertyProxyTest.kt`; `partial` elsewhere — only `ksp-fixtures/app/.../NativeSmokeTest.kt`
  (androidNative) and `ksp-fixtures/android/.../GeneratedAndroidTableTest.kt` run outside desktop.
- **U-4** Declaration kinds: objects, companions, interfaces (not constructible), enum entries as
  statics, abstract classes (no constructor), nested classes. Not exposed: annotation classes,
  generic declarations, data-class synthetics. `Status: implemented` on desktop —
  `GeneratedDeclarationKindsTest.kt`.
- **U-5** `suspend` functions are awaitable from Python (`await g.greetNow(1)`); a function that never
  suspends completes without a Future; cancelling the Python future cancels the Kotlin coroutine.
  `Status: implemented` on desktop and Kotlin/Native — `PM/desktopTest/.../upcall/AsyncUpcallDeliveryTest.kt`,
  `AsyncUpcallCancellationTest.kt`, `AsyncUpcallEarlyCancellationTest.kt`, `PM/nativeTest/.../AsyncUpcallNative*Test.kt`,
  `ksp-fixtures/app/.../GeneratedSuspendTest.kt`; `planned` on wasm (no threads).
- **U-6** Upcalls work in a GraalVM native image (desktop). `Status: implemented` with manual
  verification — procedure and record in `docs/platforms/graal-native-image-verification.md`
  (`:sample:nativeCompile`, Liberica NIK), guarded automatically by `ReachabilityMetadataTest.kt` (C-2).
  Not automated as a test of the image itself.
- **U-7** A Kotlin extension function is a method on its receiver's proxy, so chains compose
  (`Modifier.padding(16).size(24)`). `Status: partial` — asserted against a hand-written,
  Compose-shaped table (`PM/commonTest/.../pythonx/PythonxAdapterTest.kt`
  `anExtensionIsAMethodOnItsReceiverAndTheChainComposes`) and against the real Compose jars through
  the artifact walker (`ksp-fixtures/artifact/.../WalkedArtifactComposeModifierTest.kt`); not
  asserted for KSP-generated proxies.
- **U-8** A function on a Kotlin-named module carries Kotlin's own surface and nothing else: the
  Kotlin declaration name, keyword arguments by **Kotlin parameter names**, Kotlin defaults for omitted
  parameters, overload sets under the base name, and its signature as public metadata
  (`inspect.signature`, `python_multiplatform.describe`; contract in `KotlinSurface.kt`'s KDoc).
  `describe(module, name)` describes any bound name, a named constant (`STATIC_GETTER`) included,
  without evaluating it (#36). A module's `dir()` lists its direct child packages/objects and reading
  one as an attribute imports it (#35) — Kotlin names only. No
  member or parameter is renamed; the binder creates no `pythonx` module and a real `pythonx` package
  on disk is what `import pythonx` loads. The answer is the same whichever installer
  (`PythonProxySource`, `PythonxAdapter`) ran first for a table. `Status: implemented` on desktop —
  `PM/desktopTest/.../pythonx/KotlinNamedSurfaceTest.kt`, `BinderNamespaceTest.kt`, `ksp-fixtures/compose/.../KotlinSignatureMetadataTest.kt`.
- **U-9** A Pythonic package can serve extra member names on a Kotlin proxy through one hook,
  `python_multiplatform.binding.add_member_resolver(fn)`, `fn(kotlin_type_name, requested_name,
  kotlin_member_names) -> kotlin_name | (kotlin_name, keyword_map) | None`, asked when no Kotlin member
  of that name exists — and, for a Kotlin-named member, only when a call passes keywords and only for
  its `{python_kw: kotlinParam}` keyword map (#34).
  The binder renames nothing itself (no resolver: `AttributeError`), and aliases are cached in the
  registry, not written on the proxy class (`dir()` stays Kotlin-only). Contract in `KotlinSurface.kt`'s
  KDoc. `Status: implemented` on desktop — `PM/desktopTest/.../pythonx/MemberResolverTest.kt`,
  `ksp-fixtures/compose/.../MemberResolverComposeTest.kt`.
  A resolver (or anything else) reads a member's declaration rows from the receiver type alone with
  `python_multiplatform.describe_member(kotlin_type_name, kotlin_member_name)`: `describe()`'s
  tuple-of-dicts for every extension overload or the property getter/setter that type's proxy serves
  under that name, supertypes included, invoking nothing; `AttributeError` for a name it does not
  serve (#54). `Status: implemented` — `PM/commonTest/.../pythonx/PythonxPropertyTest.kt`.
- **U-10** A Kotlin property is an attribute of its receiver's proxy (#38): a member `val`/`var` of a
  public class or interface reads (and, for a public setter, writes) as a Python `property`, an
  extension property's getter reads the same way on its receiver (`Icons.Default.Add`), and both are
  found on the type and then on every type the table says it is a. A property is never a module
  attribute and makes no package. `None` written for a slot with no default is Kotlin's `null` for a
  reference type; a Python object written into a `kotlin.Any?` slot is held by Kotlin as itself, and an
  `int` is refused there (it would cross as a handle). `Status: implemented` —
  `PM/commonTest/.../pythonx/PythonxPropertyTest.kt`, `ksp-fixtures/compose/.../PythonContentRenderTest.kt`,
  `MaterialIconsRenderTest.kt`.

## 6. Binding prebuilt libraries (Gradle plugin)

- **B-1** The artifact walker binds declarations from prebuilt **jars** (Kotlin metadata read with ASM,
  overloads suffixed, multi-file facades reachable) by generating Kotlin source that `kotlinc`
  compiles — never by JVM name lookup. KSP and the walker share one Python namespace.
  `Status: implemented` on desktop — `GP/artifact/ArtifactScannerTest.kt`,
  `ksp-fixtures/artifact/.../WalkedArtifactTableTest.kt`, `WalkedArtifactPythonImportTest.kt`.
  Beside functions and object constants it binds (#38): generic functions whose type parameters are
  unbounded and not reified, each read as `kotlin.Any?` and written out at the call
  (`mutableStateOf<kotlin.Any?>(...)`), a bounded or reified one still declined; member properties of
  public classes and interfaces that cross as object handles, as `GETTER` entries `Owner.prop` and,
  for a public setter with every class type parameter unbounded, `SETTER` entries `Owner.prop=`; and
  top-level extension property getters (`pkg.prop`, receiver in `receiverTypeName`). Properties take no
  part in overload naming or in a constructor's name check; a property key another binding already
  holds is declined, and so is a property whose receiver has a supertype missing from the consumer's compile classpath (kotlinc cannot build its member scope). `Status: implemented` — `GP/artifact/PropertyBindingTest.kt`.
  The public instance functions of a Kotlin-public `object` bind under the object's name like a
  static (`Arrangement.spacedBy`, #53), except `Any`'s members, ones that also exist as a
  `@JvmStatic` static, and `@Composable` ones (declined); an `internal` or file-private object is
  skipped. `Status: implemented` — `GP/artifact/ObjectMemberBindingTest.kt`.
- **B-2** The walker on **klibs** (Kotlin/Native libraries). `Status: partial` —
  `GP/artifact/KlibScannerTest.kt` and `ksp-fixtures/klib-artifact` assert that the scanned klib's
  declarations are declined with reasons; no klib declaration is bound at run time yet.
- **B-3** Default arguments can be omitted from Python (a call per subset of defaulted parameters,
  ambiguous omissions refused). `Status: implemented` — `GP/artifact/DefaultOmissionTest.kt`,
  `ksp-fixtures/artifact/.../WalkedArtifactDefaultOmissionTest.kt`, `PM/commonTest/.../PythonxDefaultsTest.kt`.
- **B-4** Value classes round-trip; only allow-listed ones (e.g. `Dp`) may be written as their raw
  primitive. `Status: implemented` — `GP/artifact/ComposableValueClassSlotTest.kt`,
  `WalkedArtifactPythonImportTest.kt`.
- **B-5** Python callables can fill Kotlin function-typed parameters (arity and callability checked).
  `Status: partial` — `ksp-fixtures/artifact/.../WalkedArtifactCallbackTest.kt`; a value-returning slot
  (`() -> Float`) does not yet accept a Python callable (`M3ProofRenderTest.kt`
  `aValueReturningFunctionSlotDoesNotYetAcceptAPythonCallable`).
- **B-6** `@Composable` functions are callable from Python inside a composition; a Python click reaches
  a Kotlin callback and the redraw shows it. `Status: implemented` on desktop —
  `ksp-fixtures/compose/src/desktopTest/` (`ComposableRenderTest.kt`, `M3ProofRenderTest.kt`,
  `CallbackDrivenRenderTest.kt`, pointer/drag render tests), `GP/artifact/ComposableBindingTest.kt`;
  `planned` on Android, iOS and wasm.
- **B-8** A host draws a Python-declared application root with
  `python.multiplatform.compose.PythonContent(root: PyObject)` or `PythonContent(module, attribute)`,
  from the `python-multiplatform-compose` module (`python-multiplatform` itself does not depend on
  Compose). The root is a Python callable, or a Compose `State` that Python holds whose value is that
  callable. The state is read inside the composition, so a Python write into it replaces the root on
  the next frame with no host call; Python callables a root passed into composables are released when
  that root is replaced or the composition is disposed. The entry point names no library.
  `Status: implemented` on desktop — `ksp-fixtures/compose/.../PythonContentRenderTest.kt`; the module
  compiles for Android, nothing runs there yet.
- **B-7** The plugin generates `.pyi` stubs for the Kotlin-named modules only, under Kotlin names
  (keyword parameters by Kotlin name, `= ...` for a Kotlin default, receiver positional-only). Types are
  the declared Kotlin types: one stub class per bound type in the module of its own package, `Dp | float`
  for a value class bound as its primitive, `Callable[...]` for a function type, `| None` for a nullable;
  an extension function is also a callable attribute of its receiver's class; an overload set is
  `@overload`ed under its base name in table-key order; a required parameter after a defaulted one is
  keyword-only like `inspect.signature`. It emits nothing under `pythonx` and renames nothing; a
  Pythonic stub product belongs to pythonx-compose. `Status: partial` — `GP/stubs/PyiRenderingTest.kt`,
  `GP/stubs/TypedStubTest.kt`, `GP/stubs/KotlinNamesOnlyStubTest.kt`,
  `ksp-fixtures/artifact/.../WalkedArtifactStubTest.kt`, `.../StubSignatureAgreesWithRuntimeTest.kt`,
  `tools/stubs/check-stubs.sh` (mypy over the Compose stubs). A class whose name is also a function in
  its module is `Any`. A property is a `@property` (with a setter for a `var`) of its receiver's stub
  class, its docstring carrying its table key after `Kotlin property: `; where that class has no stub,
  a comment with the same marker says so (`GP/stubs/PropertyStubTest.kt`). An object constant is annotated with its
  declared type even when that type is nested in the object (`Alignment.End: Horizontal`, a class of the
  object's own module; elsewhere `androidx.compose.ui.Alignment.Horizontal`); the object's own type
  stays `Any` (#53, `GP/stubs/ObjectStubTest.kt`).
- **B-8** CI generates the stubs over the Compose version the build resolves and publishes them
  (`.github/workflows/stubs.yml`): workflow artifact `kotlin-stubs` on every push to `develop`, with a
  README naming the Compose version and the commit, and `kotlin-stubs.zip` on every `v*` tag's release.
  `Status: partial` — the workflow could not be run where it was written; its YAML parses and its
  assemble step was executed locally.

## 7. Threading and builds

- **T-1** GIL builds: Kotlin threads attach a thread state and serialise on the GIL. `Status: partial`
  (see C-4).
- **T-2** Free-threaded builds selected with `-PpythonFreeThreaded=true` download a different CPython
  asset. `Status: partial` (desktop only, opt-in; default is `pythonFreeThreaded=false` in `gradle.properties`)
  — `GP/PythonHomeStagingTest.kt`, `PM/desktopTest/.../FreeThreadedGCGateTest.kt`, `VersionsTest.kt`.
  3.14t runs the whole desktop suite with `-PpythonFreeThreaded=true` (236 tests, 0 failures; ROADMAP §9).
  Only desktop has free-threaded prebuilts; Android and iOS do not. `Py_LIMITED_API` is not defined, so
  `abi3t` is not a blocker.
- **T-3** `python-multiplatform` publishes per-target, per-flavour CPython include directories as
  `Provider<Directory>` carrying the extraction task (`cpythonIncludeDirectories` extension; see
  `docs/platforms/python-version-acquisition.md` §7). `Status: partial` (only the configured flavour is
  extracted) — `GP/CPythonIncludeDirectoriesTest.kt`.
- **T-4** `python-multiplatform` publishes the CPython version it embeds: extension `pythonMultiplatform`,
  Gradle attributes `org.thisisthepy.python.version` / `org.thisisthepy.python.free-threaded` on every
  consumable `*Elements` configuration, and the resource `META-INF/python-multiplatform/python.properties`
  in the jar and AAR (see `docs/platforms/python-version-acquisition.md` "Published version") —
  `GP/EmbeddedPythonVersionTest.kt`.

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
  tool failure. `Status: partial` — the gate is asserted by
  `python-multiplatform-ksp/src/test/python/` (run with that module's `.venv`); it is **not yet run by the
  build** (design §6 step 1b). Rebinding and mixed containers are found by a second Pyrefly pass over a
  probed copy (design §6.1), which runs only when a file has a name assigned twice or a container
  display; `for` targets and comprehension variables are not probed.
- **N-8** TypedPython compilation to C (INTENT §1.4; design §4.3; #41). `@compiled` (a builtin name, no
  import — #42) and a first-line `# typedpython: compiled` mark functions; the compiler lowers them
  through the typed IR (`typedpython/ir.py`) to C against the CPython C API and builds an extension with
  the platform C compiler. Compiled functions return what CPython returns (IR contract: checked i64
  with deopt to the interpreted function, Python floor division/modulo, exact int/float comparison,
  CPython's exception types and messages; tracebacks are the one accepted difference). `Status: planned`.
- **N-9** TypedPython memory safety is proved on the IR before C is generated (#41; maintainer decision
  2026-10-03). Each property, and what happens when it cannot be proved:

  | Property proved on the IR | If not proved |
  |---|---|
  | No local read before assignment on every path | function not compiled; diagnostic names the local |
  | Every node's operand types match; no implicit conversion | not compiled; diagnostic |
  | No deopting node in an impure function | not compiled; diagnostic |
  | Array access in bounds, or a runtime bounds check kept | the check stays (never an unchecked access) |
  | Array parameters only indexed, stored, `len`-ed; no aliasing (entry guard) | not compiled; diagnostic |
  | OBJ references follow one ownership rule; no IR form for free, borrowed alias or NULL read | not compiled; diagnostic |
  | Integer overflow checked and promoted (deopt); an op marked `proven` must re-prove its interval fits i64 | not compiled; diagnostic `verify/proven-overflow` |
  | `Call.redo` only from an impure caller to a pure, may-deopt callee returning F64/BOOL/NONE | not compiled; diagnostic `verify/redo` |
  | `entry_globals` only in a closed function (no user code runs); a call of a function with entry globals is a deopt point in its caller unless it is a legal redo or the caller snapshots the same globals | not compiled; diagnostic `verify/entry-global-open` / `verify/deopt-impure` |

  Generated C reaches memory and `PyObject`s only through the runtime helpers. ASan/UBSan CI,
  differential tests against the interpreter and fuzzing back the proof. `Status: planned`.
- **N-10** TypedPython incremental compilation (#41; maintainer decision 2026-10-03). The unit is a module.
  Its cache key is the source hash, the interface hashes (typed signatures) of the modules it depends
  on, the compiler version, the target platform and the build flags; a body-only change leaves the
  interface hash unchanged, so dependent modules are not recompiled. C objects are cached per module.
  The Gradle task declares exact inputs and outputs so up-to-date checks and the build cache apply.
  Tests: changing one function body recompiles that module only; an unchanged rebuild spends ~0 s in
  the compile step. `Status: planned`.

---

## Outside intent — needs a decision

Findings where current behaviour is not covered by, or appears to conflict with, `INTENT.md`.
Unless marked resolved, nothing here was changed; each needs the maintainer's call.

Items 1–3 were resolved by removing the renaming (2026-10-02): stubs and runtime use Kotlin names
only, and the binder no longer creates a `pythonx` module (U-8, B-7).

4. **`pythonx` adapter machinery lives in this repository** (`PM/commonMain/.../ffi/pythonx/`). INTENT
   §2.3 places `pythonx` in pythonx-compose. Whether the generic adapter belongs here (as a service
   pythonx uses) or there is not stated.
