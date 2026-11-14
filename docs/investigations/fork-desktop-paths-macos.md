# The fork's desktop paths on macOS (issue #191, investigation #242)

What python-multiplatform needs before a pythonx-compose app runs on the two desktop paths
`compose-multiplatform-core-extended` and `compose-multiplatform-extended` add: a GraalVM native
image and a Kotlin/Native executable, macOS arm64 first. Originally read on 2026-10-17 and refreshed
on 2026-11-01 after the fork published to Maven Central (#257, #287), the notebook E2E native-image
milestone landed (#246, #249, #251), and the `#191` decisions were recorded (2026-10-25).

## 1. What the fork publishes on Maven Central

The fork publishes its Compose libraries, window modules and Gradle plugins to Maven Central under
`org.thisisthepy.compose`:

- **Libraries (`1.11.1-ext.2`)**: `org.thisisthepy.compose.runtime:*`, `ui:*`, `foundation:*`,
  `material:*`, `material3:*`, `animation:*`, `components:*`, `org.thisisthepy.compose.androidx.lifecycle:*`,
  including `macosarm64` and `linuxx64` klibs alongside `desktop` (JVM), `android`, `ios*` and
  `wasmjs`. (`material-icons-core` stays on JetBrains `1.7.3`.)
- **Window modules (`1.11.1-ext.2`)**: `org.thisisthepy.compose.window:common`, `scene`,
  `graalvm-macos`, `graalvm-linux`, `native-macos`, `native-linux`.
- **Gradle plugins (`1.11.1-ext.3`)**: `org.thisisthepy.compose` and
  `org.thisisthepy.kotlin.native.desktop` (`compose-gradle-plugin:1.11.1-ext.3` on Maven Central).
- **Path selection in the fork's plugin**: `compose.desktop.application { output.set(ApplicationOutput.NativeImage) }`
  (with `windowing.set(ApplicationWindowing.Awt)` or `AwtFree`, and `nativeImage { ... }` registering
  `packageNativeImage`, `runNativeImageAgent`, `checkNativeImageNoAwt` and `packageApplication`), or
  `compose.desktop.nativeApplication { ... }` for Kotlin/Native (`createDistributableNative*`,
  `runNative*`, `packageKotlinNative`). Per decision 5 on #191, consumers select the path through the
  fork plugin's setting directly; `python-multiplatform` adds no duplicate path-selection DSL.

| | GraalVM native image | Kotlin/Native |
|---|---|---|
| Window layer | `org.thisisthepy.compose.window:graalvm-macos`, `graalvm-linux` (JVM lib, `@CFunction` + `upcalls.txt` table, no reflection) | `org.thisisthepy.compose.window:native-macos` (`macosArm64`: AppKit, Metal, `MetalSurface.kt`), `native-linux` (`linuxX64`) |
| Compose host | `org.thisisthepy.compose.window:scene` (`runComposeWindow`) | `scene` with `native-macos` / `native-linux` |
| Build | `org.thisisthepy.compose` `1.11.1-ext.3`: `compose.desktop.application { output.set(ApplicationOutput.NativeImage); nativeImage { ... } }`, `packageNativeImage`, `runNativeImageAgent` | `org.thisisthepy.kotlin.native.desktop` `1.11.1-ext.3`: `compose.desktop.nativeApplication { ... }`, `createDistributableNative*`, `runNative*` |

## 2. What this repository has today

- **Switched to the fork's Maven Central artefacts (#257, #287).** `gradle/libs.versions.toml` uses
  `org.thisisthepy.compose` `1.11.1-ext.3` (plugins) and `1.11.1-ext.2` (libraries). The artifact
  walker (`ArtifactScanner.kt`) resolves `org.thisisthepy.compose.*` coordinates alongside
  `org.jetbrains.compose.*`.
- **Targets.** `python-multiplatform` declares `jvm("desktop")`, Android, iOS, androidNative and wasm;
  `macosArm64` and `linuxX64` are the next interpreter targets to add (Phase A, decision 2;
  `mingwX64` in Phase C).
- **A native image runs Python and stages `PYTHONHOME` beside the executable (#246, SPEC L-9).**
  `pythonBindings.nativeImageDirectory` and `stageNativeImagePythonHome` copy the pruned
  `python-build-standalone` prefix (`lib/python<X.Y>/**` and `lib/libpython<X.Y>.*`) into
  `<nativeImageDirectory>/python-multiplatform-home`. At run time, inside a GraalVM native image
  (`org.graalvm.nativeimage.imagecode == "runtime"`), `PackagedPythonHome.resolve()` looks up
  `<directory of executable>/python-multiplatform-home` via `ProcessHandle.current().info().command()`
  and hands it to CPython with `Py_SetPythonHome`. `:sample:nativeCompile` and `:sample:runNativeUpcallDemo`
  stage and verify this layout with `JAVA_HOME` and `PYTHONHOME` unset (`docs/platforms/graal-native-image-verification.md`).
- **The upcall table on a native image.** Desktop upcalls are FFM (`Panama.kt`), registered by the
  generated `META-INF/native-image/org.thisisthepy/python-multiplatform/reachability-metadata.json`
  (`generateDesktopReachabilityMetadata`) plus `native-image/reachability-metadata.base.json` (which
  registers `python.native.ffi.UpcallTarget`, corrected in #244). That metadata ships in the jar, so
  any native-image build that has the jar on its classpath picks it up.
- **The notebook E2E runs in a macOS GraalVM native image (#249, #251, SPEC B-10).**
  `python-multiplatform-compose/fixtures/notebook-e2e` builds `notebook-e2e` with `notebookE2eNativeCompile`
  and runs all 20 `UI.ipynb` scenarios via `notebookE2eNativeRun` with `JAVA_HOME` and `PYTHONHOME`
  removed, `compose.main.dispatcher=coroutines` (`NotebookE2eMainDispatcherFactory`, no AWT), and an
  `otool -L` check forbidding `libjvm`. `.github/workflows/desktop.yml` (`notebook-e2e-native-image`)
  runs this job on macOS against Maven Central.

## 3. Remaining work by phase (per #191 decisions, 2026-10-25)

### Phase A (in progress)

1. **#251 (`desktop.yml`)**: `notebook-e2e-native-image` switched to Maven Central with the
   `COMPOSE_EXTENDED_VERSION` guard and GitHub Packages check removed.
2. **`:sample` as a native-image output**: `:sample` sets `pythonBindings.nativeImageDirectory` so
   `stageNativeImagePythonHome` stages `python-multiplatform-home/` beside `upcall-native-demo`, and
   `runNativeUpcallDemo` runs the executable with `JAVA_HOME` and `PYTHONHOME` removed.
3. **Kotlin/Native desktop interpreter targets (`macosArm64`, `linuxX64`)**: add `macosArm64()` and
   `linuxX64()` to `:python-multiplatform`, cinterop against the `python-build-standalone` headers
   already downloaded by `downloadPython_macos_aarch64` and `downloadPython_linux_x86_64`, link and
   load `libpython` dynamically beside the executable (decision 3), and wire `PackagedPythonHome` /
   `PythonPayload` for desktop K/N executables.

### Phase B

1. **AWT-free GraalVM notebook app**: run the notebook app in an AWT-free window (`windowing = AwtFree`).
2. **Kotlin/Native macOS app**: re-check `KlibScanner` (B-2) coverage for `@Composable` functions,
   constructors and properties over the fork's `macosarm64` klibs, add `macosArm64` to
   `:python-multiplatform-compose`, and run a K/N macOS app.

### Phase C

1. **Windows (`mingwX64`)** once the fork publishes Windows artefacts.
2. **Stub diff guard (`pythonx-compose #54`)** and the final acceptance criterion: the same
   `pythonx-compose` app running unchanged on upstream Compose, extended GraalVM and extended K/N
   desktop builds with the notebook E2E passing in CI.
