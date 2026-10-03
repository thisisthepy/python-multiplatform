# The repositories around this one, and what each owes

`/Volumes/macMini/thisisthepy` holds several repositories that are meant to compose into one
product. Until now none of them referenced the others in writing — this repository's `docs/roadmap/ROADMAP.md`
mentions `pypackpack`, `toolchain`, `pythonx` and `chaquopy` exactly zero times — so this file
records the intended relationships, what each repository actually contains today, and what has to
be built before the parts can meet.

Everything here was read from the working copies on 2026-08-14. Claims that were inferred rather
than observed are marked as such.

---

## 1. The intended shape

    pypackpack (ppp)     the features: Python acquisition, dependency resolution, cross-compilation,
                         bundling. A Kotlin/JVM library that also ships as a GraalVM native CLI.

    toolchain            the Gradle interface to ppp, and nothing more than that.
                         A developer applies one plugin; toolchain translates the Gradle DSL into
                         ppp middleware calls.

    PythonMultiplatform  the binder: CPython embedded in Kotlin Multiplatform, the object model,
                         the FFI layer, upcalls, downcalls, the generated function table.

    pythonx-compose      Compose bound into Python, so a UI can be written in Python.

    PyREPL               reference implementation for turning a Kotlin namespace into `.pyi` stubs,
                         and — see below — the actual origin of `toolchain`.

The division of labour that matters: **ppp owns the work, toolchain owns the Gradle vocabulary,
this repository owns the language boundary.** A feature that acquires a Python distribution or
resolves a dependency belongs in ppp even if a Gradle task is what triggers it.

---

## 2. What is actually there

### `pypackpack`

Roughly 4,900 lines of Kotlin, a clikt CLI compiled to a GraalVM native image, with real `uv`
integration. `docs/SPEC.md` (483 lines, added in the most recent commit) marks every feature
`Implemented` / `Partially implemented` / `Not yet implemented`.

Working: `init`, `python use/list/find/install/uninstall`, `package add/remove/sync/tree`,
`target list/add/remove`, per-package dependency operations with platform markers, and `build`
driving Meson for C/C++.

Placeholder — verified by line count, all two to four lines: the whole `bundle/` tree, the whole
`deploy/` tree, every compile backend except Meson (Clang, MSVC, NDK, XCode, Emscripten, Cargo),
and all of `compile/middleware/{external,transcompile,minification}`.

It is already designed to be called from Gradle. `dependency/frontend/BaseInterface.kt` declares
`enum FrontendType { CLI, GRADLE }` and `Gradle.kt` returns the same middleware the CLI uses. The
stable surface is `dependency/middleware/BaseInterface.kt` — `initProject`, `addDependencies`,
`syncDependencies`, `addTargets`, `installPythonVersion`, and so on — plus
`compile/middleware/BaseInterface.kt`'s `compile`.

**But `packpack/build.gradle.kts` applies no `maven-publish` and declares no publication.** Nothing
outside that build can resolve it. Until that changes, "toolchain is the Gradle interface to ppp"
cannot be implemented at all.

### `toolchain`

A Kotlin Gradle plugin, id `org.thisisthepy.python.multiplatform`, about 1,240 lines. It registers
a `python` extension and three tasks. What the tasks do today: copy a local folder into
`src/main/assets/python`, copy `build/pythonLibraries` into `build/pythonBundle/libs`, zip that,
and shell out `uv install -r` — which is not a valid uv command.

**`toolchain` shares git history with `PyREPL`.** Commits `f806656`, `5f3f932`, `1b32d03`,
`654ef7c` exist in both with identical hashes; `dba17dd` extracted PyREPL's meta-generation out of
`app/build.gradle.kts` into `toolchain/.../meta/createMetaClass.kt`. So the pyi generator to study
is already here, in a mutated form — see §4.

The target API is `(플러그인예시)build.gradle.kts` at the repo root: 276 lines describing
`compileSdk`, per-platform declarations, `packaging { embedLevel, hotReload, codePush }`,
`buildTypes { compileLevel }`, `projectFlavors`, `buildFeatures { metaclass, compose }`, and source
sets carrying both `implementation("pyzmq")` and `integration("pycomposeui")` — the latter being a
Kotlin-dependent Python package that needs a `KLIBDEPENS` file in its wheel.

Of that spec the DSL data classes exist for roughly 60%, and **none of `hotReload`, `codePush`,
`buildTypes`, `projectFlavors`, `buildFeatures`, `metaDirs`, `libDirs` or `integration()` is read by
any task.** `PythonPlugin.kt` consumes only `sourceSets.*.dependencies.implementations`.
`dsl/PythonConfiguration.kt` is dead code — its extension is never registered.

`toolchain/usage-example/` does not apply the plugin, has no `python { }` block, and is commented
out of `settings.gradle.kts`. **There is currently no executable definition of the target API.**

`toolchain-legacy` is a different lineage: a fork of `kivy/kivy-ios` in pure Python that
cross-compiles CPython and native wheels for android/ios/macos/linux/mingw, last worked on in
February 2024. Nothing moved from it into `toolchain`; its job is what ppp is rebuilding in Kotlin
with uv, crossenv and meson instead of kivy recipes.

### `pythonx-compose`

Two generations coexist, and the one the notebook demonstrates is in neither of them.

`pythonx/compose/` is the chaquopy generation: a `Composable` decorator class, `ComposeApp`, and
material3 wrappers — 37 files of which 28 are empty. It binds through `jclass(...)` and
`from java import jclass`, and it reaches **AndroidX directly**:

    from androidx.compose.material3 import TextKt
    try:    self.__kotlin_composable = TextKt.Text
    except:
        for name, obj in TextKt.__dict__.items():
            if name.startswith("Text-"):
                self.__kotlin_composable = obj; break

That trailing `-` is Kotlin's value-class mangling suffix (`Text-fLXpl1I`), which chaquopy exposes
verbatim. There are 26 such scans across 8 files. It then calls the function passing Compose's
synthetic `$composer` and `$changed` by hand.

`pythonx/compose/lite/` is a desktop JVM generation using JPype and hand-written Kotlin wrappers.

`test/` still depends on chaquopy 15.0.0 and contains a stale in-repo prototype of this
repository's binder.

### `PyREPL`

The Kotlin-to-pyi converter lives inline in `app/build.gradle.kts`, 627 lines. It walks each Kotlin
source set's resolved artifacts, reads the jars with **ASM** (not kotlinx-metadata), and writes one
`__init__.pyi` per Java package under `src/<sourceSet>/generated/meta/`, with `...` bodies and a
JNI-ish type map. It drops private members, `<init>`, `Companion`, and **any member whose name
contains `-`** — that is, it discards the mangled ones rather than scanning for them. Collisions
between `commonMain` and a platform source set are resolved by renaming both and re-exporting.

It is stubs only; the runtime underneath is chaquopy `jclass`. Its purpose is IDE completion.

`toolchain`'s `createMetaClass.kt` is the same code mutated into a *runtime* generator: it emits
`.py` with `from java import *`, `__metaclass__ = jclass(__name__)`, real delegating bodies, and a
Kotlin-to-dunder name map. **For a chaquopy-free future, PyREPL's stub-only version is the one to
keep.**

### Not part of this chain

`Gemstone` belongs to a different organisation and does not depend on any of these; its README says
it is *scheduled* to adopt Python Multiplatform. `reference/` and `cpython/` are not repositories —
vendored material and prebuilt CPython artefacts.

---

## 3. The notebook is PyREPL's, not pythonx-compose's

`UI.ipynb` imports `pythonx.compose.runtime`, `pythonx.compose.material3`, `pythonx.compose.ui`
and `pythonx.compose.layout`, and documents signatures like `Text(text, color, font_size)`,
`Button(onclick, …)` — quoted as the notebook writes it; the 2024 library spells that parameter
`on_click` (`docs/archive/pythonx-adapter-design.md` §3 measured both). Which Python spelling
`pythonx` uses is pythonx-compose's decision: this repository's binder exposes the Kotlin name
`onClick` and its Pythonic alias `on_click` on the Kotlin-named module, and renames no namespace
(`docs/INTENT.md` §2.2, SPEC U-12) - plus `Column/Row/Spacer/TextField`. It also
requires `main.App` as a live object, `App.messages.getValue()/setValue()`, and
`main.App.update(NewComposable)` for hot-swapping the UI from a Jupyter cell.

**`main.App`, `App.update`, `DefaultIcons`, `Column`, `Row`, `Spacer` and `TextField` do not exist
in `pythonx-compose`.** They exist in PyREPL, under `app/src/androidMain/python/`, in a simpler
design that does **no name matching**: it binds hand-written Kotlin wrappers by exact `@JvmName`
(`Material3Kt.SimpleTextWidget`) and passes the composer explicitly.

So if the goal is "the notebook runs on our stack", the port target is PyREPL's `app/`, and
`pythonx-compose` is the more ambitious, largely unfinished generalisation of it.

---

## 4. What blocks the parts from meeting

Ordered so that each unblocks the next.

**1. `@Composable` cannot be an exposed callable.** The generator emits
`callable = { args -> Owner.fn(args[0] as X) }` and `ExposedCallable.callable` is typed
`(Array<Any?>) -> Any?`. A `@Composable` function can only be called from a `@Composable` context,
so a generated entry for a widget would not compile, and `BindingPolicy` has no `@Composable`
handling at all. Every widget in `pycomposeui` is `@Composable`. Nothing in `pythonx-compose` can
leave chaquopy until this is designed. *(read from source; not runtime-verified.)* This is the
smallest and most blocking item, and it is entirely inside this repository.

> **Closed — see `docs/archive/pythonx-adapter-design.md` §5.6.** `Text('hi')` written in Python now draws
> through the real `androidx.compose.material3.Text` (`:ksp-fixtures:compose`'s
> `ComposableRenderTest`: 71 non-background pixels against 0 for an empty body). The paragraph above
> is right that a *generated Kotlin* entry for a widget cannot compile, and that is not the route
> taken: an artefact composable's call site is emitted as **bytecode**, with `$composer`, `$changed`
> and `$default` exposed as ordinary slots, and the binding layer (`python_multiplatform.binding`,
> `PythonxAdapter.kt`) computes the mask. `BindingPolicy` does now
> have `@Composable` handling and what it does is **decline** — a composable in the consumer's *own
> source* has no compiled signature to call yet, which is a different problem from one in a jar.

**2. `packpack` must be publishable.** One file. Without it the stated architecture is
unimplementable.

**3. A Python-facing upcall module.** Today the only Python-side surface is the generated proxy
module plus, in a demo, raw `ctypes` pointers. `pythonx-compose` needs Kotlin namespaces to be
**importable**, so `_material3 = jclass("...Material3Kt")` becomes an ordinary import. Most of
this exists: generated modules are injected into `sys.modules` under their Kotlin fully-qualified
name. What is missing is a `sys.meta_path` finder so a name resolves on demand rather than only
after an eager `install()`, the `pythonx` prefix, and generated `.pyi` beside it.

> **Current (2026-10-03):** the finder exists — the binding layer `python_multiplatform.binding`
> installs a `sys.meta_path` finder that makes Kotlin-named modules importable on demand
> (`_Finder`/`_Loader` in `PythonxAdapter.kt`; SPEC U-8). The `pythonx` prefix is **not** this
> repository's to provide: the binder creates no `pythonx` module, and `pythonx` is a real package in
> pythonx-compose (`AGENTS.md` §12.2). The plugin's `.pyi` are emitted under Kotlin module paths only
> (SPEC B-7).

**4. One CPython acquisition path, not two.** This repository's `stagePythonHome` downloads from
python-build-standalone into a Gradle cache; ppp's `python install` downloads from this
repository's own GitHub releases into `~/.pypackpack/python/<version>`. They disagree on source and
destination, and ppp's own `list`/`find` disagree with its `install` about the destination.
Per the stated architecture the surviving one is ppp's.

**5. ppp's `bundle` stage**, at least bundle-type `resource`, which its SPEC defines as the handoff
format to python-multiplatform and toolchain. It is the missing seam between the two halves of the
system, and all four bundlers are three-line placeholders.

**6. Port PyREPL's pyi generator into toolchain**, replacing the chaquopy-coupled variant, and fix
its hardcoded `\\` path separators, which break on macOS and Linux.

**7. A `usage-example` that applies the plugin**, so the target API stops being only a document.

---

## 5. What each repository owes (Implementation Goals per Repository)

This section documents the current state, language/build system/distribution artifact, ecosystem goals, gaps to goal, and dependency direction for each of the 5 repositories in `/Volumes/macMini/thisisthepy/`, based on direct inspection of working trees on 2026-08-14.

### `PythonMultiplatform` (this repository)

- **Observed Current State**:
  - Embedded CPython FFI binder (`python-multiplatform/src/commonMain/kotlin/.../EmbedAPI.kt`, platform implementations in `EmbedAPI.desktop.kt`, `bindings.kt`, JNI/Panama/cinterop). **Correction, 2026-08-17: this line used to say "CPython 3.13" — stale.** `gradle.properties`'s `pythonVersion` (the single source of truth, read into `Versions.currentVersion` at `python-multiplatform/src/commonMain/kotlin/python/multiplatform/Versions.kt`) is `3.14.7`, and every platform's *observed runtime* agrees: desktop prints `runtime : 3.14.7` (`4ed98143:docs/roadmap/ROADMAP.md:2063`), Android prints `3.14.7` on both `pmp_api36` and `pmp_api26` (`4ed98143:docs/roadmap/ROADMAP.md:2073`), wasmJs prints `3.14.2` — its own, separately-pinned Emscripten build, not this line's concern (`docs/platforms/wasm-design.md`). See the version-mismatch check below.
  - Kotlin object model hierarchy (`python-multiplatform/src/commonMain/kotlin/.../PyObject.kt` and wrappers).
  - Code generators: KSP processor (`python-multiplatform-ksp/`) producing `FunctionTableFragment`s, and Gradle plugin (`python-multiplatform-gradle-plugin/src/main/kotlin/.../artifact/ArtifactScanner.kt`, `PythonArtifactBindingsTask.kt`; *corrected 2026-10-03 — there is no `ArtifactBindingGenerator.kt`*) scanning resolved dependency jars (ASM + `kotlin-metadata-jvm`) and klibs for `ArtifactTable` fragments, plus `.pyi` stubs under Kotlin module paths (`stubs/PythonStubsTask.kt`).
  - Multiplatform target support: Desktop JVM/Panama, Android JNI, iOS Native Cinterop, androidNativeArm64 (`:python-multiplatform:compileKotlinAndroidNativeArm64`).
  - CPython binary download and SHA-256 lockfile / Sigstore verification (`python-checksums.properties`, `python-multiplatform/build.gradle.kts`).
- **Language / Build System / Distribution**:
  - Kotlin Multiplatform (Kotlin `2.4.20-Beta2` pinned in `gradle/libs.versions.toml` as of 2026-10-03), Compose Multiplatform 1.11.1 for the sample and fixtures, Java 21, C/C++ FFI.
  - Gradle (`build.gradle.kts`, `settings.gradle.kts`, included build `python-multiplatform-gradle-plugin`).
  - Maven artifact publication (`id("maven-publish")` in `python-multiplatform/build.gradle.kts`, published under group `io.github.thisisthepy:python-multiplatform`).
- **Goal in Ecosystem**:
  - Core language boundary and binder between Kotlin Multiplatform and CPython.
  - Manages low-level FFI, object reference handles, upcall/downcall function tables, GIL lifecycle, and proxy injection into CPython `sys.modules`.
- **Gap to Goal** (status notes added 2026-10-03, read from code and SPEC, not re-run):
  - `@Composable`-capable callable shape: `ExposedCallable` typed `(Array<Any?>) -> Any?` cannot invoke `@Composable` functions taking synthetic `$composer` / `$changed` parameters. Needs dedicated `CallableKind` or opt-in annotation handling. — *Closed on desktop by bytecode thunks (`ComposableThunks.kt`), SPEC B-6; Android, iOS and wasm planned (SPEC N-2).*
  - Python-side `sys.meta_path` finder for lazy import resolution of Kotlin namespaces ~~(`pythonx.*` or FQCNs)~~ upon import. — *Exists for Kotlin FQNs in `python_multiplatform.binding` (SPEC U-8). `pythonx.*` is not the binder's to resolve (`AGENTS.md` §12.2).*
  - Opaque Kotlin object round-tripping for Compose `Composer` (`HandleTable` and `ObjectReference` exist, but runtime hand-off to Python needs verification). — *Done on desktop: one hand-written `@Composable` pushes the live composer as a handle onto the binding layer's stack (`push_composer`), proven by the `ksp-fixtures/compose` render tests.*
  - Lifetime and storage of Python callables passed into Kotlin across Compose recompositions (`content=lambda: ...`, `onClick=...`). — *Partly addressed: `PythonCallableScope` (`PythonCallables.kt`) holds the callables a composition hands to Kotlin and is closed by a Compose-side `RememberObserver` in `onForgotten`; several hand-written fixture wrappers (`pythonDraggable`, `pythonComposed`, the swipe/anchor wrappers) still leak their callbacks by design, pinned by `DraggableLeakTest`. Not verified here beyond reading the sources.*
  - Retire `stagePythonHome` in favor of `pypackpack`'s Python distribution management.
  - Hand `stageWasmBrowserRuntime` logic to `toolchain`.
- **Dependency Direction**:
  - `PythonMultiplatform` has no dependencies on other repos in the ecosystem.
  - `pythonx-compose` and consumer applications depend on `PythonMultiplatform` runtime and its Gradle/KSP bindings plugin.
- **Unverified**:
  - Behavior of WASM browser runtime under production web bundlers. (*2026-10-03:* the former `wasm-experiment/` is folded into the library — `wasmJsMain`/`wasmJsTest`, CPython built by `tools/wasm/build-cpython.sh` — commit `6e54f617`; the bundler question is unchanged.)

**Version check (2026-08-17): "3.14 runtime vs. 3.13 header, never checked" — could not find the
sentence, checked the claim anyway.** The literal sentence was searched for across every `.md` file
in this repository (`grep -rn` for Korean and English phrasings of "nobody/no one checked", "never
verified", "mismatch", combined with "header"/"헤더" and "runtime"/"런타임") and in the git history
of this file; it does not appear anywhere, verbatim or paraphrased. The nearest things on record are
this section's own now-corrected "CPython 3.13" line above (stale since the version became
configurable) and `4ed98143:docs/roadmap/ROADMAP.md:2033`, which already fixed a test fixture that hardcoded the string
"CPython 3.13" while `pythonVersion` had moved to 3.14.7. Neither is the sentence quoted, and neither
left the underlying question — do headers and runtime actually agree, per platform — checked. That
question was checked here, freshly, platform by platform:

| platform | header source | runtime source | agree? | how checked |
|---|---|---|---|---|
| desktop | none — Panama binds by symbol name at runtime (`java.lang.foreign.Linker`/`SymbolLookup`), no C header is compiled against | `libpython3.14.dylib`/`.so`/`.dll` extracted from the `python-build-standalone` archive pinned to `pythonVersion` (`python-checksums.properties`: `macos-aarch64-3.14.7-20260807`, etc.) | n/a — no header exists to disagree with the runtime | read `build.gradle.kts`'s desktop `jvm()` block; no `cinterop`/`headers()` call anywhere in it; `4ed98143:docs/roadmap/ROADMAP.md:1034-1038` records the same conclusion independently |
| iOS / androidNative (Kotlin/Native `cinterop`) | `$targetExtractDir/include/python$libVersion/Python.h` (Android) or the extracted `Python.xcframework/.../Headers` (iOS) — **read from the same extraction the runtime library comes from**, not from any vendored copy | same archive, same extraction, `libVersion`/`targetExtractDir` computed from the one `pythonVersion`/`libVersion` variables | **yes, by construction** — both paths are derived from the same Gradle variable, so they cannot independently drift for a given build | read `python-multiplatform/build.gradle.kts:1093-1128` (`targetIncludePath`, the `cinterops.create("python")` block); confirmed no `defFile`/hardcoded header path overrides it |
| Android JNI (`artMain`, `jni_onload.def`) | none — hand-written `extern` prototypes in `jni_onload.def`, no `#include <Python.h>`, pointers passed as `jlong` | `libpython$libVersion.so` linked with `-lpython$libVersion` from the same extraction tree | n/a for headers; symbol-level agreement already checked by earlier work (`nm`/`objdump` against the shipped `.so`, cited in `docs/roadmap/ROADMAP.md` around the 3.15 migration) | read `python-multiplatform/src/artMain/cinterop/jni_onload.def` and its `build.gradle.kts` cinterop block (no `headers()`/`includeDirs()` call) |
| wasmJs | Emscripten's own CPython 3.14.2 build, entirely separate toolchain (`docs/platforms/wasm-design.md`) | same 3.14.2 build (`Embedded CPython version: 3.14.2`, `docs/archive/wasm-design-experiment-log.md` "Building CPython 3.14.2 for Emscripten"; `runtime : 3.14.2` sample output, `4ed98143:docs/roadmap/ROADMAP.md:1488`) | yes, and deliberately a different minor-patch than the 3.14.7 native default — documented and reasoned about at length in `docs/platforms/wasm-design.md`, not an oversight |

**What is genuinely stale, and does not affect any of the above:** two artefacts in this repository
still carry CPython 3.13 and are not read by the default build. `python-multiplatform/src/nativeInterop/cinterop/include/patchlevel.h`
is a vendored header tree whose `PY_VERSION` reads `"3.13.0+"` — `git log` shows it landed with
`b184cba5` (pre-dates `pythonVersion` becoming a Gradle property) and `build.gradle.kts` never
references `nativeInterop/cinterop/include` at all; cinterop reads headers from the version-keyed
extraction tree instead (table above). `binary/` holds five committed CPython 3.13.0 archives from
`python-build-standalone` release `20241008` (`binary/download.md`); they are consumed only by the
special-cased branch `if (configuredPythonVersion == "3.13.0" && !pythonFreeThreaded)` in the
`desktopJar` task (`build.gradle.kts:1049`), i.e. only if a consumer explicitly builds with
`-PpythonVersion=3.13.0`. That legacy path has no `Py_LIMITED_API`/header dependency either (desktop
never compiles against headers), so even under `-PpythonVersion=3.13.0` there is no header/runtime
split — but `python-checksums.properties` has **no 3.13.0 entries for Android or iOS**, so that flag
would fail checksum verification on those two targets rather than silently mixing versions; this was
not run to confirm the failure mode, only the absence of pinned checksums.

**Correction to the paragraph above, made while verifying it: the 3.13 headers are not inert.** They
are not a *compiler* input — that part holds, `build.gradle.kts` never puts
`nativeInterop/cinterop/include` on an include path. But two test suites read that directory as their
source of truth about the C API: `EmbedApiSurfaceTest.kt:454` and
`JniCallConventionClassificationTest.kt:628` both open `src/nativeInterop/cinterop/include` and
derive, respectively, the deprecation schedule the `expect` surface is checked against and the JNI
call-convention classification. So the one place in this repository where a 3.13 header meets a 3.14
runtime is the evidence layer, not the build: a function whose deprecation or signature changed in
3.14 would be classified from 3.13's text and the tests would agree with themselves. This is the
mismatch the check set out to look for, in the one shape nobody had looked at, and it is recorded
rather than fixed here — refreshing the header tree is a separate change with its own verification.

**That separate change was made (2026-08-17), and it answered the open question: nothing this
project uses moved between 3.13 and 3.14.** The vendored `include/` tree was replaced with the
3.14.7 headers out of the build's own checksum-verified extraction
(`build/python-standalone/extracted/3.14.7/android-aarch64/prefix/include/python3.14/`; the four
`pyconfig-<platform>.h` files and the hand-written `pyconfig.h` dispatcher were re-derived from the
matching per-platform extractions, since CPython ships one `pyconfig.h` per build and this tree
serves four). The evidence base the two suites read grew from 1129 `PyAPI_FUNC` prototypes to 1207
and from 81 `Py_DEPRECATED` symbols to 101, and **every conclusion drawn from it was unchanged**:
the JNI classification reported byte-identical totals before and after
(`LEAF_PROVEN=5, RE_ENTRANT_BY_JNI_UPCALL=3, RE_ENTRANT_BY_SIGNATURE=293, UNDECIDED=65`; 34
GC-blocking declarations, 22 promoted, 12 quarantined), and `desktopTest` went 467/0/1 → 469/0/1,
the two added tests being the new guard below.

Why nothing moved, stated as a set intersection rather than as an impression: of the 6 prototypes
3.14 drops (`PyUnstable_InterpreterState_GetMainModule`, `_PyBytes_Join`, `_PyTrash_begin`,
`_PyTrash_end`, `_Py_InitializeMain`, `_Py_fopen_obj`), the 1 whose signature changed
(`_PyLong_NumBits`, `size_t` → `int64_t`), and the 23 newly marked `Py_DEPRECATED` (led by
`PyThread_exit_thread`, `_PyThreadState_UncheckedGet`, `_Py_HashPointer`, and the whole
`_PyUnicodeWriter_*` family), **none appears in the 312-name `expect` surface of `EmbedAPI.kt`, and
none appears among the 319 CPython prototypes `jni_onload.def` calls.** Every one of the 30 is a
private (`_Py`/`_PyUnstable`) symbol this project's abi3 self-rule already forbids. So the
3.13-vs-3.14 evidence gap was real but benign, and it is now closed rather than argued about.

The guard that keeps it closed is `VendoredHeaderVersionTest` (`src/desktopTest/.../
VendoredHeaderVersionTest.kt`): it compares `patchlevel.h`'s `PY_MAJOR/MINOR/MICRO_VERSION` against
`gradle.properties`'s `pythonVersion` and fails if they diverge, with a canary test so that a
regex that stops matching fails loudly instead of making the comparison vacuous. It was watched
failing on the pre-refresh tree (`expected:<3.1[4.7]> but was:<3.1[3.0]>`, sole failure in a
469-test run) before it was trusted. It compares versions, not contents — deliberately, since a
content comparison would need the extraction present and so would be unavailable exactly when the
extraction has not been run.

The rest of that tree is genuinely unreferenced and large: `git ls-files` counts 15,842 tracked files
under `src/nativeInterop/cinterop` (679 MB in `lib/`, 2.6 MB in `include/`), and of `lib/` only
`lib/desktop` is read, by the `-PpythonVersion=3.13.0` branch above. The Android CPython 3.13 standard
library trees under `lib/android/<abi>/python3.13/` are referenced by no build file, no Kotlin source
and no test; the runtime stdlib comes from the version-keyed extraction instead. Whether to remove
them is a repository-size decision, not a correctness one, and is left open.

**`Py_LIMITED_API` status, checked directly:** `grep -rn "Py_LIMITED_API"` across `.kts`/`.def` build
files finds it only inside vendored CPython header guards (`#if defined(Py_LIMITED_API) ...`) — it is
never passed as a compiler define anywhere (no `-DPy_LIMITED_API`, no `compilerOpts` setting it). This
independently confirms `4ed98143:docs/roadmap/ROADMAP.md:1031-1038`'s own finding, reached from the free-threading
investigation rather than this one: the project does **not** build against the Limited API / Stable
ABI in the `Py_LIMITED_API`-macro sense. "abi3" as used elsewhere in this repository's docs means a
self-imposed rule about which C API functions this project chooses to call (ones stable across
versions), not a `Py_LIMITED_API` compilation mode. So the "Stable ABI permits a header/runtime minor
mismatch" escape hatch the task brief raised does not apply here — but it is not needed, either,
because the table above shows headers and runtime are never independently sourced in the first place.

**Residual risk, marked as such because it was not run:** the table above is a build-configuration
argument (headers and runtime derive from one variable), corroborated by *observed* `sys.version`-
equivalent output on desktop and Android and by an existing wasmJs run — not by a from-scratch build
of `compileKotlinIosSimulatorArm64`/`compileKotlinAndroidNativeArm64` performed in this pass (this
check reused `docs/roadmap/ROADMAP.md`'s existing, dated build/test evidence rather than re-running Gradle). If a
future change hardcodes a header path or a library name outside the `libVersion`/`targetIncludePath`
variables audited above, this guarantee would silently stop holding; nothing currently tests for that
kind of regression directly (the closest is the version-agnostic assertion in `Python3Test.kt`'s
`versionReportsTheConfiguredRelease`, which catches a runtime drift but not a header-only one, since
no target here has a runtime check *of the header's declared version* — the headers have no
executable presence to assert against).

**Issues (checked 2026-08-16):** the only open issue against this repository is
[`python-multiplatform#4`](https://github.com/thisisthepy/python-multiplatform/issues/4), "Add Python/C API
expect declaration reclassification and actual definitions" (deadline noted in the issue as `25-07-24`, i.e.
already past). It has no checklist, just two prose tasks: (1) reclassify the `expect` declarations in
`EmbedAPI.kt`, excluding deprecated/soon-to-be-deprecated APIs and ones judged unnecessary, and (2) add
`actual` definitions for `androidMain`, `desktopMain`, `nativeMain`.

Cross-checked against code rather than assumed:

- The "335/336/336/315" difference claim was incorrect. Commit `4ac2eca6` proved that `grep` counted
  the numbers wrong: Kotlin block comments nest, and each file carries superseded pre-migration drafts
  inside comments, so `grep` scanned them as live code. When counted with comment-aware scanning,
  the difference between `expect` and `actual` members is **zero on every target** — it was empty before
  the commit and remained empty after. The miscount pattern (grep unable to see block nesting) has
  recurred three times in this repository (`EmbedAPI` initial draft, two parser canaries); documenting
  this as a pattern is valuable to prevent it again. `EmbedAPI.wasmJs.kt` exists too (not one of the
  three the issue named).
- The "reclassify to exclude deprecated APIs" half of the issue was resolved differently than specified.
  Rather than removing deprecated CPython APIs, the repository kept them and marked them `@Deprecated`
  in Kotlin: `PyEval_InitThreads` was removed (empty in CPython 3.9+), four functions kept marked
  `@Deprecated` without `ReplaceWith` (because exact replacements exist but blind swaps would be wrong
  in ways the compiler cannot catch — three return borrowed vs. new references, one takes an unnormalized
  triple), and three that CPython marks as "discouraged" (not deprecated) were left alone. Commit `4ac2eca6`
  adds a test that validates all three targets against a canonical set; the test passes at 466 cases
  across all five targets.
- Issue #4's `actual`-definition half was already complete before `4ac2eca6`; the commit's real work was
  reclassification, dead-code cleanup (three draft blocks removed from comments), and adding validation tests.

---

### `toolchain`

- **Observed Current State**:
  - Plugin project structure (`toolchain/src/main/kotlin/.../PythonPlugin.kt`) registering `python` extension and 3 tasks (`InstallDependenciesTask`, `BuildPythonArtifactTask`, `AssemblePythonPackageTask`).
  - Naive, self-contained task implementations in `bundle/` (`AssemblePythonPackageTask.kt`, `BuildPythonArtifactTask.kt`): copying local directory to `src/main/assets/python`, zipping to `build/pythonBundle/libs`, and executing `uv install -r` (which is an invalid `uv` command).
  - DSL definitions in `dsl/` (`DSLBuild.kt`, `DSLCore.kt`, `DSLPackaging.kt`, `DSLPlatforms.kt`, `PythonConfiguration.kt`) covering ~60% of target spec `(플러그인예시)build.gradle.kts`.
  - Mutated PyREPL generator `toolchain/src/main/kotlin/.../dependency/lang/createMetaClass.kt` producing runtime `.py` files using `from java import *`.
- **Language / Build System / Distribution**:
  - Kotlin (`kotlin-dsl`, `java-gradle-plugin`).
  - Gradle (`build.gradle.kts`, `settings.gradle.kts`).
  - Gradle Plugin published to Maven (`org.thisisthepy.python.multiplatform` plugin ID).
- **Goal in Ecosystem**:
  - Developer-facing Gradle DSL vocabulary (`python { ... }`) and nothing else.
  - Translates Gradle DSL configuration into `pypackpack` middleware API calls.
  - Owns Kotlin-to-`.pyi` stub generation (porting PyREPL's ASM-based stub generator).
  - Handles `integration()` dependency types with `KLIBDEPENS`.
- **Gap to Goal**:
  - **Delegate to `pypackpack`**: Currently delegates nothing to `pypackpack` because `pypackpack` is not published to Maven. All tasks must be rewritten to invoke `pypackpack` middleware.
  - **Delete redundant tasks**: Remove internal naive tasks (`InstallDependenciesTask`, `BuildPythonArtifactTask`, `AssemblePythonPackageTask`, and asset copy).
  - **Wire up DSL**: DSL blocks in `dsl/` (`hotReload`, `codePush`, `buildTypes`, `projectFlavors`, `buildFeatures`, `metaDirs`, `libDirs`, `integration()`) are not read by any task. `PythonConfiguration.kt` extension is dead code (never registered).
  - **Replace stub generator**: Replace runtime `createMetaClass.kt` with PyREPL's ASM-based build-time `.pyi` generator, fixing hardcoded Windows path separators (`\\`).
  - **Executable specification**: `usage-example/` does not apply the plugin and is commented out in `settings.gradle.kts`.
- **Dependency Direction**:
  - `toolchain` -> `pypackpack` (calls `pypackpack` JVM library API).
  - Applied by end-user Kotlin Multiplatform projects.
- **Unverified**:
  - Behavior of `integration()` configuration with `KLIBDEPENS` wheel metadata (only documented in DSL spec).

**Issues (checked 2026-08-16):** two open issues,
[`toolchain#2`](https://github.com/thisisthepy/toolchain/issues/2) ("[Todo] Kotlin Gradle Plugin and Build
Tools" — a checklist, sub-issue `pypackpack#2`) and
[`toolchain#1`](https://github.com/thisisthepy/toolchain/issues/1) ("[Todo] Toolchain-lite for python-only
users", parent `pypackpack#2`, one line: `tcl install pythonx-compose`). Full checklist-vs-code table is in
§5c. Three corrections to the bullets above, found by reading the current tree (`git log --oneline -12`
runs through `fb1dba7`, `30a064c`, `f7008eb`) rather than by re-deriving them:

- **The `createMetaClass.kt` generator this file described above no longer exists.** `git log` shows it
  deleted in `30a064c` ("Feat: Open the packagePython chain, delegate dependency install, and delete the
  meta generator"); `find . -iname "createMetaClass*"` in the working tree returns nothing. The "Observed
  Current State" bullet above describing it is stale as of that commit — left as written per this
  repository's convention of not erasing superseded claims, corrected here instead.
- **"Delete redundant tasks" (this file's own gap item) did not happen by deletion.** `InstallDependenciesTask`,
  `BuildPythonArtifactTask` and `AssemblePythonPackageTask` all still exist, under the same names. What
  changed is their bodies: `InstallDependenciesTask` no longer shells `uv install -r` (not a real `uv`
  verb) and instead calls `pypackpack`'s `dependency.backend.BaseInterface` directly (confirmed by reading
  `InstallDependenciesTask.kt`'s current source, which now imports
  `org.thisisthepy.python.multiplatform.packpack.dependency.backend.BaseInterface`). The naive
  copy-to-`src/main/assets/python`-then-zip step that lived inline in `PythonPlugin.kt`'s `afterEvaluate`
  was deleted (that part of the gap item is accurate), but the task classes were rewired, not removed.
- **"Wire up DSL" is now partially wrong.** `integration()` and `buildTypes` (`debug`/`release` selection
  via `-Ppython.buildType`) are wired as of `fb1dba7` and an earlier commit respectively — confirmed by
  reading `PythonPlugin.kt`'s `collectInstallDependencies` (folds `implementations + integrations`) and
  `resolveActiveBuildType`. `hotReload`, `codePush`, `buildFeatures` (`metaclass`/`compose`), `metaDirs`,
  `libDirs` and per-variant `platforms` (e.g. Android min-SDK) remain unwired — but `fb1dba7`'s commit
  message states this was tested, not assumed: registering `PythonConfiguration.kt`'s extension was tried
  and confirmed to compile and do nothing (`usage-example` never imports the DSL package that would reach
  it), and the rest have no corresponding concept in `pypackpack` to bind to yet. `PythonConfiguration.kt`
  dead-code claim re-confirmed by this agent: `grep -rn PythonConfiguration toolchain/src/main/kotlin`
  outside its own file returns nothing.

---

### `pypackpack`

- **Observed Current State**:
  - ~4,900 lines of Kotlin implementing CLI subcommands in `packpack/src/main/kotlin/.../cli/`: `init`, `python use/list/find/install/uninstall`, `package add/remove/sync/tree`, `target list/add/remove`, `add`/`remove`/`sync`/`tree` with target markers (`platform_system`, `platform_machine`).
  - `uv` integration (`dependency/backend/external/UV.kt`) for downloading uv and managing `DevEnv` / `CrossEnv` venvs.
  - `build` subcommand driving Meson CLI (`compile/backend/external/Meson.kt`) for C/C++ extension modules (`py.extension_module()`) and Python sources (`py.install_sources()`).
  - Architecture ready for dual usage (`dependency/frontend/BaseInterface.kt` has `enum FrontendType { CLI, GRADLE }` and `Gradle.kt`).
  - Specification in `docs/SPEC.md` marking feature statuses.
- **Language / Build System / Distribution**:
  - Kotlin JVM (Kotlin 2.3.0 / Java 21), Clikt CLI, Ktor client, Ktoml, zstd-jni.
  - Gradle (`packpack/build.gradle.kts`, GraalVM Native Image plugin `org.graalvm.buildtools.native`).
  - GraalVM native binary executable (`pypackpack`), intended to also publish as a Kotlin/JVM Maven library (`org.thisisthepy.python.multiplatform:packpack`).
- **Goal in Ecosystem**:
  - Owns all heavy lifting for Python packaging: Python distribution acquisition/management, dependency resolution (`uv`), cross-compilation environment (`crossenv`), C/C++/Rust extension compilation, bundling into resource/wheel formats.
  - Serves as both a standalone CLI and an internal library for `toolchain`.
- **Gap to Goal**:
  - **Publish to Maven**: `packpack/build.gradle.kts` lacks `maven-publish` plugin and publication block. Nothing outside can resolve `pypackpack` until published.
  - **Implement `bundle` stage**: All bundlers in `bundle/` (`BinaryBundler.kt`, `FatWheelBundler.kt`, `SingleWheelBundler.kt`, `WheelPatchBundler.kt`) are 2-4 line empty placeholders. Bundle type `resource` (needed by `PythonMultiplatform` and `toolchain`) must be implemented.
  - **Fix destination disagreement**: `python install` downloads to `<project>/.venv` or `<project>/<target>`, while `python list`/`find`/`uninstall` search `~/.pypackpack/python/<version>`.
  - **Auto-install build tools**: `Meson.installMeson()` exists but is never invoked before build execution.
  - **CLI pass-through flags**: `add`/`remove`/`sync`/`tree` hardcode `extraArgs = null`.
  - Non-Meson compiler backends (Clang, MSVC, NDK, XCode, Emscripten, Cargo) and compilation/minification middleware (Nuitka, Cython, Lpython) are empty placeholders.
- **Dependency Direction**:
  - `pypackpack` has no dependencies on `toolchain` or `pythonx-compose`.
  - `toolchain` depends on `pypackpack` as a Maven library.
- **Unverified**:
  - Wheel patch generation (`WheelPatchBundler.kt`) and incremental upload logic (placeholder files only).

**Issues (checked 2026-08-16):** four open issues —
[`pypackpack#2`](https://github.com/thisisthepy/pypackpack/issues/2) ("[Todo] PyPackPack Initial
Development", parent `toolchain#2`, subs `pypackpack#1`/`toolchain#1`),
[`pypackpack#1`](https://github.com/thisisthepy/pypackpack/issues/1) ("[Todo] Python Dependency
Management", parent `pypackpack#2`, sub `pypackpack#5`),
[`pypackpack#5`](https://github.com/thisisthepy/pypackpack/issues/5) ("Managing Multi-Platform Dependencies
in uv with Platform Markers", help wanted, a how-to guide rather than a checklist), and
[`pypackpack#12`](https://github.com/thisisthepy/pypackpack/issues/12) ("Ambiguous file name due to
duplicated name on entirely codebase", a naming-convention proposal). Full checklist-vs-code table in §5c.
Several "Gap to Goal" items above are now stale — this repository moved fast in the last two days
(`git log --oneline -12` runs `488bac0` back through `08353c7`, all dated 2026-08-15/16) — and their commit
messages are unusually explicit about what they do and do not close, so this agent read each rather than
inferring from diff stats:

- **"Publish to Maven" is done.** `d10ab76` ("Build: Publish packpack, so toolchain can finally depend on
  it") added `maven-publish` to `packpack/build.gradle.kts`; the commit message states
  `publishToMavenLocal` now produces `org.thisisthepy.python.multiplatform:packpack:0.1.0`. The one caveat
  the same commit records — CLI dependencies (Clikt, Ktor, zstd) leaking onto a library consumer's
  classpath because the CLI lived in the same module — was itself closed one commit later, `488bac0`
  ("Build: Split the CLI into its own module"): confirmed by `find . -iname BuildCommand.kt`, which now
  resolves under `cli/src/main/kotlin/.../cli/BuildCommand.kt`, not under `packpack/`.
- **"Implement `bundle` stage" is partially done, more precisely than "all four bundlers are
  placeholders."** `8d7b4b4` ("Feat: Implement the resource bundle...") implemented `ResourceBundler.kt`
  (310 lines, its own 328-line test file) — the one bundle type `python-multiplatform` and `toolchain`
  need per the commit message. `BinaryBundler.kt`, `FatWheelBundler.kt`, `SingleWheelBundler.kt` and
  `WheelPatchBundler.kt` are still placeholders (re-confirmed by this agent, unchanged). But
  `ResourceBundler` is not yet reachable from the CLI: `grep -n "ResourceBundler\|resource"
  cli/.../BuildCommand.kt` finds nothing, and `docs/SPEC.md` (pypackpack's own spec, current as of these
  commits) says outright "there is no `source`/`resource` bundle-type subcommand yet". So the class exists
  and is tested; the CLI seam to it does not.
- **The "destination disagreement" gap item is mostly fixed, with a named residual.** `5de8656` ("Fix:
  Close the three known defects...") added a `registry.properties` file bridging `install`'s
  project-relative writes and `find`/`list`/`uninstall`'s `~/.pypackpack/python/<version>` reads. `docs/SPEC.md`
  confirms this in its `python install` section. The same section still lists "Final placement after
  download/extraction is incomplete (marked `TODO` in code)" as an open limitation — the registry closes
  the *lookup* mismatch, not this separate placement TODO.
- **"Auto-install build tools" is done.** `isMesonInstalled()` used to be a stub returning `true`
  (per `8d7b4b4`'s commit message); `5de8656` made it probe `meson --version`/`ninja --version` for real
  and call `Meson.installMeson()` when the probe fails.
- **"CLI pass-through flags" is done, not just started.** `5de8656`'s message states `add`/`remove`/
  `sync`/`tree` forward unrecognized flags in all three command shapes, verified through the middleware to
  the per-target `uv` call — `docs/SPEC.md`'s dependency-management section documents the same allowlist
  (`parsePassthroughArgs` in `cli/CommandExtension.kt`) and a known ordering gotcha (`--target`'s greedy
  vararg swallows a passthrough flag placed after it).
- **Non-Meson compile backends and bundlers remain placeholders, re-confirmed.** `Clang.kt`, `MSVC.kt`,
  `NDK.kt`, `XCode.kt`, `Emscripten.kt`, `Cargo.kt`, `Nuitka.kt` are all still exactly 4 lines each
  (`wc -l`, checked by this agent, unchanged from what this file already said).
- **`pypackpack#12`'s file-naming complaint is unresolved.** The suggested rename
  (`BaseInterface.kt`/`DefaultInterface.kt` duplicated across `dependency/`, `compile/backend/`,
  `compile/middleware/`, `bundle/`, `deploy/` → `FrontendInterface.kt`/`MiddlewareInterface.kt`/
  `BackendInterface.kt`/`DefaultMiddleware.kt`/`DefaultBackend.kt`) has not been applied — `find . -iname
  "*Interface*.kt"` still returns the ambiguous names the issue complains about, unchanged.
- **`pypackpack#5`'s platform-marker guide describes something that already mostly works.** `MarkerPolicy`
  (`dependency/middleware/DefaultInterface.kt`) builds `platform_system == '...' and platform_machine ==
  '...'` markers per target and `--python-platform` is threaded through `tree`/`sync`/`add`/`remove`. The
  one place it does not hold: `docs/KNOWN_ISSUES.md` records that the marker `uv add` actually persists
  can come out as `platform_machine`/`sys_platform` instead of `platform_system`/`platform_machine`, which
  can make `remove --target` fail to find a dependency added with that same target. So the guide's
  approach is largely implemented, with one recorded, unfixed matching bug rather than being unbuilt.
- **`pypackpack#1`'s "Middleware Refactoring" item ("are `DevEnv`/`CrossEnv` redundant wrappers?") is
  still genuinely open**, not stale: reading `DevEnv.kt` now, its methods still follow "resolve project
  root → delegate to backend → print success/failure", i.e. still the shape the issue calls a simple
  wrapper. `CrossEnv.kt` (552 lines) has grown well past that shape, though — asymmetric, unresolved by
  this agent's reading, matches the issue's unchecked box.
- **`pypackpack#1`'s "settings.gradle.kts — [ ] uv" item could not be resolved to a concrete claim.** The
  issue text is a single unexplained bullet with no elaboration. `settings.gradle.kts` today has no
  mention of `uv` (checked directly); whether that is the intended scope of the checkbox, this agent could
  not determine from the issue alone — left open rather than guessed.

---

### `pythonx-compose`

- **Observed Current State**:
  - Intended Python UI framework wrapping Kotlin Compose Multiplatform.
  - Two unmerged legacy generations:
    1. Chaquopy branch (`pythonx/compose/`): 37 files (28 empty placeholders), using `from java import jclass` and scanning mangled AndroidX Kotlin symbols (`TextKt.Text-fLXpl1I`) via `__dict__` reflection.
    2. Desktop JVM branch (`pythonx/compose/lite/`): uses JPype with hand-written Kotlin wrappers.
  - Demo notebook `UI.ipynb` target API works against PyREPL's `app/src/androidMain/python/` implementation (`main.App`, `App.update`, `DefaultIcons`, `Column`, `Row`, `Spacer`, `TextField`), NOT `pythonx-compose`'s current codebase.
- **Language / Build System / Distribution**:
  - Python (3.8+).
  - `setuptools` (`pyproject.toml` building package `pycomposeui` version 0.0.1).
  - PyPI / wheel package (`pycomposeui`).
- **Goal in Ecosystem**:
  - Provide Pythonic Compose wrappers (`pythonx.compose.*`) for writing UIs in Python.
  - Adapt Kotlin Compose bindings generically via `__getattr__` and caching (mapping named arguments, threading composer, adapting `content=` lambdas) rather than hand-writing wrapper functions per widget.
- **Gap to Goal**:
  - Delete raw-AndroidX dynamic reflection branch (`TextKt.Text-fLXpl1I`) and JPype dependency.
  - Migrate runtime to `PythonMultiplatform`'s static upcall table and Python proxy injection (`sys.modules`), replacing `jclass`.
  - Port PyREPL's widget and runtime model (`app/src/androidMain/python/`) into `pythonx-compose`.
  - Implement generic Python adapter (`__getattr__` on `pythonx.compose.material3` resolving underlying `androidx.compose.material3` bindings lazily).
  - Replace string-based type name parsing in `remember_saveable` with `PythonMultiplatform`'s `PyValue` type tags.
- **Dependency Direction**:
  - `pythonx-compose` -> `PythonMultiplatform` (depends on `PythonMultiplatform`'s FFI binding surface and Python proxy module at runtime).
  - ~~Uses `.pyi` stubs emitted by `toolchain` / `PythonMultiplatform` plugin for editor autocompletion.~~ *Corrected 2026-10-03:* `PythonMultiplatform`'s plugin emits stubs for the Kotlin-named modules only (SPEC B-7). The Pythonic `pythonx.*` stubs, and the `pythonx-map.toml` manifest that would drive them, are `pythonx-compose`'s own product; it builds its API from `inspect.signature` and `python_multiplatform.describe` (`KotlinSurface.kt`).
- **Unverified**:
  - Full hot-reload integration with Jupyter notebook server outside `UI.ipynb` static cells.

**Issues (checked 2026-08-16):** `gh issue list --repo thisisthepy/pythonx-compose --state open` returns
nothing. There is no issue tracker source for this repository's gap list — everything in "Gap to Goal"
above is this document's own inference from reading the code, not sourced from an issue. Flagged here so
it is not mistaken for the issue-backed items in the other three repos.

---

### `toolchain-legacy`

- **Observed Current State**:
  - Legacy build toolchain source tree (`toolchain/` directory) derived from Kivy (`kivy-ios` and `python-for-android`), last worked on in February 2024.
  - Python CLI entry points in `setup.py`: `toolchain`, `toolchain_targetver`, `toolchain_targetos`.
  - Cross-compiles CPython 3.11 and native recipes (NumPy, OpenSSL, Kivy) for Android, iOS, macOS, Linux, and Windows (mingw).
  - Depends on external forks: `thisisthepy/toolchain-ios` and `thisisthepy/toolchain-android`.
- **Language / Build System / Distribution**:
  - Pure Python (3.9+).
  - `setup.py` (package name `toolchain`, version `3.11.0.1`).
  - PyPI package / Python CLI tool (`pip install git+https://...`).
- **Goal in Ecosystem**:
  - **Legacy reference implementation only.**
  - Kept for historical reference to understand pre-2024 CPython cross-compilation and recipe management.
  - Replaced entirely by `pypackpack` (cross-compilation and packaging in Kotlin via `uv`/`crossenv`/`meson`) and `toolchain` (Gradle plugin interface).
- **Gap to Goal**:
  - Deprecated/superseded. No active development or gap to close — retained for historical reference only.
- **Dependency Direction**:
  - None. Independent legacy Python CLI tool.
- **Unverified**:
  - Modern Xcode 15+ / NDK 26+ compatibility of legacy recipes (untested).

---

## 5b. The Python import surface — decided

Four questions were open about what Python code should look like. They are settled.

**A Kotlin fully-qualified name means the original Kotlin.** `import androidx.compose.material3`
reaches real AndroidX, not a wrapper of ours wearing its name.

This is reachable, and the mechanism is the one PyREPL already uses: **the Gradle plugin reads the
resolved dependency artefacts.** For each Kotlin source set it makes a resolvable copy of the
`implementation` and `api` configurations and walks the resulting jars with ASM. AndroidX is an
ordinary jar in that set, so it is covered like anything else.

The two producers are therefore split by *what they look at*, not by whether something is
reachable:

    KSP                 the consumer's own source — declarations it can see being compiled
    artefact walker     everything the build resolves — third-party jars, AndroidX included

Both run at build time under the same applied plugin. An earlier draft of this file claimed
AndroidX could never be reached because KSP does not see it; that conflated one producer's limit
with the system's.

What genuinely differs per platform is what a *jar* means. On JVM and Android the artefacts are
jars and the walker applies directly. On iOS, androidNative and wasm the artefacts are klibs, and
whether the same walk is possible there — and what a Kotlin declaration from a klib can be bound
to at runtime with no JVM underneath — is the open question, not AndroidX.

**Update — the walker now exists on one path, and both of those questions have answers.**
`python-multiplatform-gradle-plugin` gained `generatePythonArtifactBindings`, and
`ksp-fixtures/artifact` carries a resolved `junit:junit:4.13.2` through ASM, a generated
`FunctionTableFragment`, `UpcallTable` and `PythonProxySource` to
`from junit.runner.Version import id` answering `"4.13.2"`. ROADMAP §16 records it in full; three
things in it change what this section says:

- **The walker's fragments are a second aggregator (`ArtifactTable`), not additions to KSP's
  `FunctionTable`.** One `UpcallTable`, one fragment interface, one Python namespace — but
  `FunctionTable` keeps meaning "every module in this graph compiled with the processor", which is
  what `ksp-fixtures/app` asserts and what a consumer's own table should not silently outgrow.
  Install site: `UpcallTable.install(FunctionTable.fragments + ArtifactTable.fragments)`.
- **ASM alone does not reach `androidx.compose.material3`.** It reaches Java statics and Kotlin
  `@JvmStatic`s. A Kotlin *top-level* function compiles onto a package-private multi-file part
  (`kotlin/text/StringsKt__IndentKt`) behind a facade Kotlin cannot name, and its extension receiver
  is indistinguishable from an ordinary parameter in bytecode. The `@Metadata` *kind* is readable
  with ASM; the payload that carries the Kotlin names is `d1`/`d2` and needs `kotlin-metadata-jvm`.
  So AndroidX is reachable, as this section says — one library short of it, not one design short.
- **klib is the easier half, not the harder one.**
  `org.jetbrains.kotlin.library.abi.LibraryAbiReader`, already in the `kotlin-compiler-embeddable`
  this build pins, read `python-multiplatform-iosX64Main.klib` and returned 441 top-level
  declarations under their Kotlin qualified names, with `isSuspend`,
  `hasExtensionReceiverParameter`, value parameters and Kotlin (unerased) types. There is no facade
  problem because a klib records the Kotlin declaration; and since a generated fragment is Kotlin
  source compiled into the consumer's own binary with the klib on its compile classpath, the *call*
  is an ordinary Kotlin call with no reflection. ~~The open question that remains is not
  feasibility but cost: `@ExperimentalLibraryAbiReader`, and a ~60 MB compiler artefact on the
  plugin classpath versioned against the consumer's Kotlin rather than the plugin's.~~ **The cost
  question is answered too, and not by shrinking it — by moving it off the plugin's classpath.**
  ROADMAP §16e/16f: `KlibScanner` runs inside an isolated Gradle worker
  (`WorkerExecutor.scanKlibIsolated`) whose classpath is supplied per build rather than baked into
  the plugin jar, because calling it from the plugin's own classloader throws `NoSuchMethodError`
  against a real consumer's `kotlin-util-klib` — a correctness fix that also happens to keep the
  ~60 MB artefact off a build that never walks a klib. `PythonArtifactBindingsTask` is wired to it
  and covered by `KlibScannerTest` against two real klibs.

**Dynamic binding is a removed option, not a missing one.** The 2024 design document
(*PyComposeUI*, the open-source contest report) specifies a dynamic binder — Java/Kotlin
Reflection on the JVM target, `ctypes` and `pyobjc` on Kotlin/Native — with a generated meta
package existing only so an IDE can infer types against it. That is history. The binder was built
statically instead, and the reason is the same one §7 records for choosing a build-time table:
a GraalVM native image is a closed world and Kotlin/Native has no reflection, so a name that only
exists at runtime cannot be reached on the targets this library has to support.

So there is one axis, not two. Bindings are produced at build time, by KSP for the consumer's own
source and by an artefact walk for everything the build resolves. Anyone reading the 2024 document
will find a dynamic path described as the plan; it was dropped deliberately, and the meta package
that document treats as a typing aid is, here, generated beside bindings that are themselves
static.

**`pythonx.*` is ours.** Whatever we wrap or add lives under that prefix. Kotlin fully-qualified
names point at the original; `pythonx` points at our Pythonic layer. The two namespaces do not mix.
*(2026-10-03: "ours" means the ecosystem's — `pythonx` is a real package in pythonx-compose; this
repository's binder creates no `pythonx` module and renames no namespace, `AGENTS.md` §12.1–12.2; the
snake_case aliases it serves live on the Kotlin-named modules, SPEC U-12.)*

**The wrapping happens in Python, not in Kotlin.** `pythonx.compose.material3` is Python code that
uses the generated `androidx.compose.material3` bindings and presents a Pythonic API over them.
There is no hand-written Kotlin wrapper module in between — that is what the existing
`io.github.thisisthepy.pycomposeui` `RuntimeKt` and `Runtime_androidKt` are, and they are the shape
being moved away from.

This settles a question that otherwise looks hard. A `@Composable` function is, after compilation,
an ordinary function taking `$composer` and `$changed`; a binding generated from the artefact
exposes that signature as it is, and the Python wrapper passes the composer as a value — which is
what `pythonx-compose`'s `Composable` class already does. So nothing on the Kotlin side needs a
`@Composable` callable type, and the upcall trampolines do not have to preserve a `@Composable`
context.

**`pythonx` adapts generically; it does not wrap function by function.** Writing a Python wrapper
for every Composable is the cost that made the existing `pythonx-compose` 37 files of which 28 are
empty. The conversions are rules, not per-function decisions: a Pythonic name maps to the original,
the composer and change flags are threaded the same way every time, and a `content=` callable is
adapted the same way every time. So the layer should resolve on demand — a module `__getattr__`
that finds the corresponding binding, adapts it once and caches it — rather than enumerate.

That trades away IDE completion, which is exactly why stubs are generated from the same metadata.
*(Corrected 2026-10-03:)* this repository's plugin generates stubs for the Kotlin-named modules,
under Kotlin names (SPEC B-7); the stubs that carry the Pythonic names and signatures belong to
`pythonx` itself, in pythonx-compose
([`docs/archive/pyi-generation-pythonic-stubs.md`](../archive/pyi-generation-pythonic-stubs.md) is the
design that was removed from here).

**`JClass`/`JavaClass`, `KClass`/`KotlinClass`, `ObjcClass` work only where the platform has the
thing they name.** No stubs, no substitutes, no forced uniformity across targets — a target that
has no JVM has no `JClass`, and says so.

**`.pyi` generation belongs to the Gradle plugin**, the way PyREPL did it: read the resolved
artefacts at build time and emit stubs per source set. It is not a sibling of `PythonProxySource`,
which fills `sys.modules` at runtime; these are different products for different consumers, one for
the interpreter and one for the IDE. Long term that generator belongs to `toolchain` — see §5.

---

## 5c. Issue checklists against the code

§5 was originally written by reading working copies, and it never opened an issue tracker — this
repository's own `docs/roadmap/ROADMAP.md` mentions the other repos zero times, and the same was true of GitHub issues.
That is the gap this section closes. Issues read in full, 2026-08-16:

    gh issue list --repo thisisthepy/toolchain --state open
    gh issue list --repo thisisthepy/pypackpack --state open
    gh issue list --repo thisisthepy/python-multiplatform --state open
    gh issue list --repo thisisthepy/pythonx-compose --state open

`pythonx-compose` has none open. Seven issues came back across the other three repos, each read with
`gh issue view <n> --repo <repo>`, then checked against the working copy (not against the issue's own
description of itself). "✅ done" below means this agent found the corresponding code and, where the issue
or a commit message made a narrower claim, checked that narrower claim too — not just that a file with a
plausible name exists.

### `toolchain#2` — "[Todo] Kotlin Gradle Plugin and Build Tools"

| Checklist item | Issue's checkbox | Actual state (checked against code) |
|---|---|---|
| Create the basic structure of the plugin | ☑ checked | ✅ matches — `PythonPlugin.kt` registers the `python` extension and three tasks |
| Python version setup → Version Enum (alpha, rc, normal) | ☐ unchecked | ❌ matches — `compileSdk` is a plain `String` in `DSLCore.kt`; no enum anywhere |
| Build target platform setup → platform-specific min-SDK setting | ☐ unchecked | 🟡 partial — `AndroidPlatformExtension.androidSdk: Int` exists in `DSLPlatforms.kt` but no task reads it (per `fb1dba7`'s own commit message: "platforms has a partial hook... mapping them silently would be wrong") |
| Build target platform setup → check Kotlin-side enabled build target | ☐ unchecked | ❌ matches — no code found that inspects which KMP targets are enabled |
| Hot reload / Code Push → expose a direct run button | ☐ unchecked | ❌ matches — `HotReloadExtension`/`CodePushExtension` (`DSLPackaging.kt`) are pure data holders; `fb1dba7`: "hotReload and codePush have no backend concept in packpack" |
| SourceSet setup → `implementation` | ☐ unchecked | ✅ **stale checkbox** — wired since before `fb1dba7`; `collectInstallDependencies` reads `implementations` |
| SourceSet setup → `integration` | ☐ unchecked | ✅ **stale checkbox** — `fb1dba7` ("Feat: Make integration() install...") folds `integrations` into the same install list; it is treated identically to `implementation`, no `KLIBDEPENS` distinction (deliberately, per the same commit — nothing in `pypackpack` has that concept) |
| SourceSet setup → etcs (src/resource path, sourceset naming) | ☐ unchecked | ❌ matches — `SourceSetConfig.srcDirs`/`metaDirs`/`libDirs` (`DSLBuild.kt`) accept values via DSL but nothing reads them (`fb1dba7`: "metaDirs and libDirs have nothing in the resource bundler to bind to") |
| CompileLevel setup (debug/release, project flavors) | ☐ unchecked | 🟡 partial — `debug`/`release` selection via `resolveActiveBuildType`/`-Ppython.buildType` is wired; project flavors (`buildFeatures`) are not (`fb1dba7`: "buildFeatures has no consumer at all") |
| Automate the build process and integrate with KMP | ☐ unchecked | 🟡 largely done, not fully end-to-end verified by this agent — `buildTask`→`installTask`→`packageTask` chain runs and delegates real work to `pypackpack`'s `ResourceBundler` (`30a064c`); this agent did not run `usage-example` to confirm a full build |

### `toolchain#1` — "[Todo] Toolchain-lite for python-only users"

One line, no checklist: `tcl install pythonx-compose`. ❌ Not started — `find . -iname "*tcl*" -o -iname
"*lite*"` in the `toolchain` working copy (excluding `.git`/`build`) returns nothing.

### `pypackpack#2` — "[Todo] PyPackPack Initial Development"

| Checklist item | Issue's checkbox | Actual state (checked against code) |
|---|---|---|
| Create the basic structure of the plugin | ☐ unchecked | (parent bullet, not independently checkable — see sub-items) |
| Python version setup → Version Enum | ☐ unchecked | ❌ same as `toolchain#2` — no enum found in either repo |
| Build target platform setup → platform-specific min-SDK | ☐ unchecked | ❌ not found in `pypackpack` |
| Build target platform setup → check Kotlin-side enabled build target | ☑ checked | 🟡 not independently verified by this agent — no corroborating code found in the time available; recorded as unverified rather than disputed |
| Hot reload / Code Push → run button | ☐ unchecked | ❌ matches |
| SourceSet setup → implementation (python-only) | ☐ unchecked | 🟡 the underlying dependency-add path exists (`DependencyBackend.addDependencies`), but this is `toolchain`'s DSL concept, not `pypackpack`'s — checkbox sits on the wrong side of the boundary the ecosystem doc draws in §1 |
| SourceSet setup → integration (python+kotlin mixed) | ☐ unchecked | ❌ matches — no `KLIBDEPENS` handling anywhere in `pypackpack` (grepped, zero hits) |
| External tool detection → Nuitka | ☐ unchecked | ❌ matches — `Nuitka.kt` is 4 lines |
| External tool detection → Host Python | ☑ checked | ✅ matches — `python install` downloads a prebuilt CPython from `python-multiplatform`'s GitHub releases (`docs/SPEC.md`, confirmed) |
| External tool detection → MSVC, Clang | ☐ unchecked | ❌ matches — both 4-line placeholders |
| External tool detection → Poetry or UV | ☑ checked | ✅ matches — `dependency/backend/external/UV.kt` is a real, exercised integration |
| External tool detection → Crossenv or equivalent | ☐ unchecked | 🟡 partial — `CrossEnv.kt` exists at 552 lines and is exercised by `add`/`sync`/`tree` per-target commands, but "creating a dedicated venv per target" is explicitly listed as not-yet-implemented in `docs/SPEC.md` |
| Compilation modes → instant (pure python) | ☑ checked | 🟡 not verified as a selectable mode — `docs/SPEC.md` states the `--level` CLI flag is "accepted but ignored" for `build`, i.e. there's exactly one behavior today, which happens to look like `instant`, not a selection mechanism |
| Compilation modes → bytecode/mixed/native | ☐ unchecked | ❌ matches — `docs/SPEC.md` lists all three as "Not yet implemented (target)" |
| Build Tools → configure Nuitka | ☐ unchecked | ❌ matches |
| Build Tools → compatibility testing with Android | ☐ unchecked | ❌ not found |
| Build Tools → minification | ☐ unchecked | ❌ matches — `compile/middleware/minification/` has only the base interface |

### `pypackpack#1` — "[Todo] Python Dependency Management"

| Checklist item | Issue's checkbox | Actual state (checked against code) |
|---|---|---|
| Handle `pyproject.toml` → lossless modification | ☑ checked | ✅ matches — `utils/toml/TomlEditor.kt` + `TomlValue.kt` exist and are exercised (SPEC.md's `target add/remove`, `package remove` sections describe editing specific keys in place) |
| Fix `CrossEnv.kt` abstraction → encapsulate UV logic in backend | ☑ checked | ✅ plausible, not fully re-derived — `CrossEnv.kt`'s target-marker logic (`MarkerPolicy`) and per-target `uv` calls live in the middleware/backend layers this item describes; this agent did not diff against a pre-fix version to confirm the decoupling directly |
| Middleware Refactoring → is `DevEnv`/`CrossEnv` still a redundant wrapper? | ☐ unchecked | ✅ matches, genuinely still open — `DevEnv.kt` (240 lines) still follows "resolve project root → delegate to backend → print result" for every method, the shape the issue questions. `CrossEnv.kt` (552 lines) has grown well past a simple wrapper, which is itself an argument the two are no longer symmetric — unresolved either way |
| Target Platforms → research a cleaner architecture for platform-specific deps via UV | ☐ unchecked | 🟡 partial — `MarkerPolicy`/`Platforms.describeTarget` (`utils/Platforms.kt`) already implement a `platform_system`/`platform_machine` marker scheme and thread `--python-platform` through `tree`/`sync`, i.e. a pattern exists; whether it counts as the "research" this item asks for is a judgment call this agent did not make either way |
| `settings.gradle.kts` → `uv` | ☐ unchecked | ❓ could not resolve — the issue gives no elaboration beyond the single word "uv"; `settings.gradle.kts` today has no `uv` reference at all, but this agent could not determine what behavior the checkbox is asking for, so this is left open rather than guessed |

### `pypackpack#5` — "Managing Multi-Platform Dependencies in uv with Platform Markers" (help wanted, guide not checklist)

Not a checklist — a how-to document proposing `platform_system`/`platform_machine` PEP 508 markers per
target. 🟡 **largely already matches the shipped design**: `MarkerPolicy.markerForTarget` in
`dependency/middleware/DefaultInterface.kt` builds exactly `platform_system == '...' and platform_machine
== '...'`, and `--python-platform` is passed to `uv tree`/`uv add` per target. The one place code and guide
diverge in practice: `docs/KNOWN_ISSUES.md` records that the marker text `uv add` actually persists can
come back as `platform_machine`/`sys_platform` rather than `platform_system`/`platform_machine`, which
breaks `remove --target`'s re-derived-marker matching for some dependencies. So the guide's approach is
implemented, with one recorded, unfixed bug in marker-round-tripping.

### `pypackpack#12` — "Ambiguous file name due to duplicated name on entirely codebase" (question, not checklist)

❌ **Not addressed.** The issue proposes renaming `BaseInterface.kt`/`DefaultInterface.kt` (duplicated
across `dependency/`, `compile/backend/`, `compile/middleware/`, `bundle/`, `deploy/`) to
`FrontendInterface.kt`/`MiddlewareInterface.kt`/`BackendInterface.kt`/`DefaultMiddleware.kt`/
`DefaultBackend.kt`. `find . -iname "*Interface*.kt"` in the current tree still shows `BaseInterface.kt`
and `DefaultInterface.kt` repeated in all five domains named in the issue, unchanged.

### `python-multiplatform#4` — "Add Python/C API expect declaration reclassification and actual definitions"

No checklist, two prose tasks. ✅ **Closed** by commit `4ac2eca6` ("Fix: Remove the drafts stranded in
comments, and reclassify what CPython deprecated"). The "20-member gap" this document had reported
(335 `expect` vs 315 `actual` on desktop) was a `grep` counting artifact: Kotlin block comments nest, and
old drafts inside comments were counted as live code. Comment-aware scanning shows the difference is
**zero on all targets**. The `actual`-definition half was already complete; the commit's work was
reclassification (removing `PyEval_InitThreads`, annotating four replacements as `@Deprecated` without
`ReplaceWith` because exact replacements exist but blind swaps would be incorrect, leaving three CPython-discouraged
functions alone), deleting three dead draft blocks from comments, and adding a test validating all three
targets against a canonical surface (466 test cases, all passing).

### What is issue-sourced vs. this document's own inference

Per the task that produced this section: items in §5's existing "Gap to Goal" lists that also appear in an
issue above are issue-sourced, and are now cross-referenced from within §5 itself (see the "Issues" note in
each repository's block). Two kinds of mismatch, kept separate rather than merged:

- **In an issue, missing from §5's original gap lists**: `pypackpack#12`'s file-naming complaint;
  `pypackpack#1`'s `DevEnv`/`CrossEnv` redundancy question and its unexplained `settings.gradle.kts`/`uv`
  item; `toolchain#2`'s Python-version-enum and Kotlin-target-detection sub-items; `pypackpack#2`'s
  compile-mode checklist (`instant`/`bytecode`/`mixed`/`native`) and Android-build-compatibility item.
  These were not in this document before this pass — they are what "renders §5 incomplete" in the sense
  the task described, now folded into each repository's "Issues" note above rather than duplicated again
  here.
- **In §5's original gap lists, absent from any issue**: `PythonMultiplatform`'s `@Composable`-callable-shape
  gap (already closed per §4 item 1, and never was a `python-multiplatform` issue — it is `pythonx-compose`'s
  problem surfacing in this repo's code), its `sys.meta_path` lazy-import gap, and all of `pythonx-compose`'s
  "Gap to Goal" list. These are this document's own judgment calls, not sourced from a tracker — flagged in
  the `pythonx-compose` "Issues" note above since that repository has zero open issues to source anything
  from.

---

## 6. Where this file should live

It describes five repositories and sits in one of them, because that is the only one with an
active working copy and a documentation habit. If the organisation grows a place for cross-repo
documents, this belongs there, and what stays here is the last section's first entry.
