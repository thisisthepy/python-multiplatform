# The fork's desktop paths on macOS (issue #191, investigation #242)

What python-multiplatform needs before a pythonx-compose app runs on the two desktop paths
`compose-multiplatform-core-extended` and `compose-multiplatform-extended` add: a GraalVM native
image and a Kotlin/Native executable, macOS arm64 first. Read on 2026-10-17 from the fork's
`origin/extended` branches (core `61906995435`, plugin fork `fca1473a`), `~/.m2/repository`, and
this repository's `develop` (`af4debac`). Nothing here was built; every fact cites where it was read.

## 1. What the fork has on macOS

| | GraalVM native image | Kotlin/Native |
|---|---|---|
| Fork's claim | `extended/README.md` (core, line 12): "macOS works on both: the Kotlin/Native window (AppKit and Metal) and the GraalVM native-image window." | same line |
| Window layer | `extended/window/graalvm/graalvm-macos` (JVM lib, AppKit through `@CFunction`, `upcalls.txt` table, no reflection) | `extended/window/native/macos` (`macosArm64`: AppKit, Metal, `MetalSurface.kt`) |
| Compose host | **none for this path yet**: `extended/window/scene` (JVM and `macosArm64`) hosts a Compose scene through `runComposeWindow(platform, config, surfaceFor, content)` (`scene/src/.../ComposeWindow.kt:285`), but `graalvm-macos` provides no `WindowSurface` to draw into | `scene` with `native/macos`'s `MetalSurface` |
| Measured with Compose content | `extended/native-image/hello` (plugin fork): Material 3 in an **AWT** window (`windowing = Awt`), 82 MB, "draws the same content through Skia offscreen into a PNG byte for byte the one the JVM run draws" (`extended/native-image/README.md`, 2026-10-02) | no Gradle demo in the fork; compose-rust's Amper renderer (`darkpyonix/compose-rust/renderer/macos`) consumes `native-macos` |
| AWT-free demo | `extended/native-image/hello-awtfree-link`: opens, pumps and presents an AppKit window; **draws no Compose** | none |
| Build | the plugin fork's `org.jetbrains.compose` `1.11.1-extended-dev`: `compose.desktop.application { nativeImage { graalvmHome; skikoStaticDirectory } }`, `packageNativeImage`, `runNativeImageAgent`; skiko as a static archive (`extended/skiko/build-skiko-static-jvm.sh`) | `extended/gradle-plugin-kn`: `createDistributableNative...`, `runNative...`, `packageKotlinNative` |

**Published to mavenLocal** (JetBrains coordinates kept, as #191 says):

- `org.jetbrains.compose:ui*`, `foundation*`, `ui-text*` at `1.11.1001` (2026-10-17), including the
  `macosarm64` klibs of `ui` and `foundation`. No `material3` macOS klib.
- `org.jetbrains.compose:compose-gradle-plugin:1.11.1-extended-dev`.
- `org.thisisthepy.compose.window:*:0.1.0`: `common`, `graalvm-macos`, `graalvm-linux`, `native-macos`. Not
  `scene`, and not `native-linux`.
- `org.jetbrains.skiko:skiko-awt:0.144.6.1`.
- No publish script producing `1.11.1001` was found in either fork repository; the "Extended window
  publish" workflow and compose-rust's `scripts/publish-window.sh` publish the window modules.

## 2. What this repository has

- **Targets.** `python-multiplatform` declares `jvm("desktop")`, Android, iOS, androidNative and wasm;
  no `macosArm64`, `linuxX64` or `mingwX64` (`python-multiplatform/build.gradle.kts`; the plugin's
  `hostDesktopPlatform` knows macOS only as a JVM host). The sample has the same set.
- **A native image already runs Python** (`docs/platforms/graal-native-image-verification.md`,
  2026-08-17, `4687070a`): `:sample:nativeCompile` (an `Exec` of Liberica NIK 25.0.4's `native-image`,
  `--no-fallback --enable-native-access=ALL-UNNAMED`) builds a headless `NativeImageMainKt`; 28
  downcalls and 5 upcalls registered, `UPCALL_OK`/`PROXY_OK`, no `libjvm`, `libpython` `dlopen`ed.
  It is **headless on purpose**: the sample's `application {}` needs AWT. Not automated, not re-run.
- **The upcall table on a native image.** Desktop upcalls are FFM (`Panama.kt`), registered by the
  generated `META-INF/native-image/org.thisisthepy/python-multiplatform/reachability-metadata.json`
  (`generateDesktopReachabilityMetadata`) plus `native-image/reachability-metadata.base.json`. That
  metadata ships in the jar, so any native-image build that has the jar on its classpath picks it up.
  (The verification note says no reflection registration is needed, while its own log shows 1,822
  types registered and the base file registers `UpcallTarget`; the note should be corrected.)
- **PYTHONHOME.** `stagePythonHome` sets `PYTHONHOME` for `JavaExec`/`Test` tasks and copies the prefix
  into Compose's `prepare*AppResources`; the native demo is given `PYTHONHOME` by its run task. A
  native image has no `java.home` and no packaged-resources directory, so nothing stages a prefix
  **beside the executable** or finds it from there at start.
- **The notebook E2E** (`python-multiplatform-compose/fixtures/notebook-e2e`) needs no window: it
  draws `PythonAppView` into an offscreen `ImageComposeScene` (`NotebookHost.kt:132`) and compares
  pixels. It is a JVM `desktopTest` suite with `compose.desktop.currentOs` (Skia natives) and
  `java.awt.headless=true`; no `java.awt`/`javax.swing` import.

## 3. What each path needs from this side

### GraalVM native image

1. **A desktop app built by the plugin fork's `packageNativeImage`** rather than the sample's hand-made
   `Exec`: the fork already solves Compose's own native-image configuration and static skiko. The
   generated upcall table and FFM registrations come along in the jar's `META-INF/native-image`, so
   **the upcall table carries over unchanged** (build-time table, no reflection: AGENTS.md §12.3, §13).
   To check: that the fork's plugin passes `--enable-native-access=ALL-UNNAMED` (or lets an app add it)
   and does not set `--no-fallback`-incompatible options that hide a missing FFM registration.
2. **PYTHONHOME beside the executable.** A task that stages the prefix and the installed wheels next to
   `build/compose/native-image/main/<name>`, and a runtime lookup (`ProcessHandle`'s executable path, a
   sibling `python/` directory) when `PYTHONHOME` is unset, like `IosPythonHome` does for an app bundle.
   **Staging does not carry over as is**: today's paths are a Gradle task's environment and the JVM
   packaging's resources directory.
3. **The walker over the fork's jars.** Same public API, so the walk must produce the same bindings;
   guarded by the stub diff of pythonx-compose #54.
4. **Selecting the path.** A Gradle plugin option (upstream Compose by default, the fork's native image
   as a choice), #191's first bullet.

### Kotlin/Native

1. **An interpreter target**: `macosArm64` in `python-multiplatform` (cinterop against the macOS
   python-build-standalone prefix the desktop JVM already downloads, a static or shipped
   `libpython`), with the native `HandleTable`/trampoline path iOS and androidNative already use.
   This is new work, not a switch.
2. **The klib walker** (B-2) over the fork's `macosarm64` klibs: since #234/#237 it carries handles and
   interface members, but not constructors, properties or composables, and Compose's surface is mostly
   composables and extensions; the K/N path needs the walker to reach about where the jar walker is.
3. **PYTHONHOME** beside the executable, as for the native image.
4. `material3` for `macosArm64`: no fork klib is published (the fork says only `ui` and `foundation`
   differ from JetBrains; JetBrains' own `material3` macOS klib would have to resolve beside them).

## 4. First milestone: the notebook E2E in a macOS GraalVM native image

The E2E draws offscreen, so the milestone needs **no window** and none of the AWT-free pieces:

1. A `notebook-e2e` native-image entry point (a `main` running the existing scenarios and printing a
   pass/fail line per scenario, the way the fork's `hello` self-checks through `HELLO_SELF_CHECK`),
   because a JUnit `desktopTest` does not run inside an image.
2. Built with the plugin fork's `packageNativeImage` (`windowing = Awt`, which is what the fork measured;
   the scenes are offscreen either way), `--enable-native-access=ALL-UNNAMED`, the fork's static skiko.
3. PYTHONHOME and the pythonx-compose wheel staged beside the executable (§3, GraalVM 2).
4. Pass: the same 20 scenarios as the JVM run (pixel comparisons included), the binary run alone from
   an empty directory with `JAVA_HOME` unset, and `otool -L` showing no `libjvm`. Then a CI job on a
   macOS runner, as `notebook-e2e` has on the JVM (#213).

Milestone 2 is the same app in the AWT-free window, once the fork has the pieces in §5. Kotlin/Native
follows the interpreter target (§3, K/N 1).

## 5. Requests for the fork (through the darkpyonix lead)

1. **Publish `org.thisisthepy.compose.window:scene`** (its `module.yaml` already enables publishing;
   it is missing from mavenLocal and from the "Extended window publish" workflow's output).
2. **A `WindowSurface` for `graalvm-macos`** (Metal or GL into a Skia surface, as `native/macos`'s
   `MetalSurface`), so `runComposeWindow` can host Compose content on the GraalVM path; and a demo that
   draws Compose through it (`hello-awtfree-link` draws nothing today).
3. **A documented, reproducible publish of `1.11.1001`** (no script producing that version was found)
   and one version line for the Compose libraries, the window modules and the Gradle plugin, so a
   consumer pins one set.
4. **The native-image flags an app can add**: confirm that `packageNativeImage` lets an app pass
   `--enable-native-access=ALL-UNNAMED` and merges a library's `META-INF/native-image` metadata (FFM
   `foreign` registrations included).
5. **A hook to stage extra files beside the image** (a prefix directory), or confirmation that a
   consumer task after `packageNativeImage` is the supported way.
6. For Kotlin/Native: whether `material3` for `macosArm64` is expected to come from JetBrains, and a
   Gradle (not Amper-only) example of `scene` plus `native-macos` hosting Compose content.

## 6. Open questions

- Whether CPython's own native-image behaviour holds with the fork's Compose in the same image (one
  process, Skia and libpython both `dlopen`ed or linked); not tried.
- Where the native-image E2E runs in CI: GitHub's macOS runners have no GraalVM by default, and the
  fork's static skiko must be built or cached.
