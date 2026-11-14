# CPython Version Acquisition Research

## 1. Desktop Platforms (macOS, Linux, Windows)

The project currently uses `python-build-standalone` (maintained by Astral). For Python 3.14 and 3.15, this project continues to publish pre-built self-contained binaries. 

*   **Release Tags:** Releases are tagged by date (e.g., `20260807`).
*   **Asset Naming Scheme:** The asset naming follows the pattern: `cpython-<python-version>+<build-date>-<target>-<flavour>.<ext>`.
    *   Targets include: `aarch64-apple-darwin`, `x86_64-apple-darwin`, `x86_64-unknown-linux-gnu`, `x86_64-pc-windows-msvc`, `aarch64-pc-windows-msvc`.
    *   Flavours include: `install_only`, `install_only_stripped`, and `full` (which also includes debug symbols, pgo/lto variants). The archives are available in `.tar.gz` and `.tar.zst` formats.
*   **Flavour acquired: `install_only_stripped`** (issue #86). The plain `install_only` linux `libpython3.14.so.1.0`
    carries debug info (251,884,016 bytes); the `install_only_stripped` one is 33,106,704 bytes with no `.debug_*`
    section (it keeps `.symtab`, so `file` still says "not stripped"). The stripped flavour exists for every desktop
    target and both GIL and free-threaded builds, for 3.14.7 and 3.15.0rc1 in release `20260807`, so no platform falls
    back. The archives are pinned in `python-checksums.properties` and agree with the release's `SHA256SUMS`; the
    desktop jar's build check (`DesktopJarLibrariesTest`) fails if a linux library in it has `.debug_info`. A host
    `strip` (absent or ELF-blind on macOS) is not used.
*   **URL Pattern:** `https://github.com/astral-sh/python-build-standalone/releases/download/<release-tag>/<asset-name>`

## 2. Free-threaded Variants

`python-build-standalone` publishes free-threaded builds for Python 3.14 and 3.15.
*   **Naming:** The string `freethreaded` is injected into the asset name before the flavour, e.g., `cpython-3.14.7+20260807-aarch64-apple-darwin-freethreaded-install_only.tar.gz`.
*   **ABI and Libraries:** The ABI suffix is `t`, meaning the shared libraries will be named `libpython3.14t.dylib` or `libpython3.14t.so`.
*   **Availability:** They are available for all major desktop targets (macOS, Linux, Windows).
*   **Acquired: the free-threaded build only, on desktop** (issue #158; maintainer 2026-10-04: "Free-threaded
    is the default and the only build. The GIL build is not kept as an option. Baseline is CPython 3.15t";
    leader 2026-11-13: "flip the desktop default to 3.15t now"). Every desktop `downloadPython_*` task fetches
    `...-freethreaded-install_only_stripped.tar.gz` and unpacks it under `<ver>/<target>-freethreaded/`.
    The `-PpythonFreeThreaded` property that used to choose between the two is removed; passing it fails the
    build. Android and iOS (section 3) embed the project's own 3.15t builds since #328.

## 3. Android and iOS Support

*   **Acquired: the project's own free-threaded 3.15t builds, and only those** (#328 step 2; maintainer on
    #158, 2026-10-04: "Free-threaded is the default and the only build. The GIL build is not kept as an
    option"; leader decision 2026-11-13 on #328: no opt-in flag on mobile; the owner approved the x86_64
    Android 3.15t build the same day, so arm64-v8a and x86_64 both stay supported). They are built by
    `.github/workflows/build-ft-mobile.yml` (#163, `free-threaded-mobile.md`) and published on the GitHub
    release `cpython-<version>t-mobile` of `thisisthepy/python-multiplatform`:
    `python-<version>t-aarch64-linux-android.tar.gz`, `python-<version>t-x86_64-linux-android.tar.gz` and
    `python-<version>t-iOS-XCframework.tar.gz`, at
    `https://github.com/thisisthepy/python-multiplatform/releases/download/cpython-<version>t-mobile/<asset>`.
*   **Layout.** Android: `prefix/lib/libpython3.15t.so`, `prefix/include/python3.15t/`,
    `prefix/lib/python3.15t/` (with `config-3.15t-<arch>-linux-android/`), unpacked under
    `<ver>/android-<arch>-freethreaded/`. iOS: `Python.xcframework/<slice>/Python.framework/Headers`
    (`pyconfig.h` wraps `pyconfig-<arch>.h`), `Python.xcframework/lib/python3.15t/` and
    `<slice>/lib-<arch>/python3.15t/`, unpacked under `<ver>/ios/`.
*   **Checks.** The lockfile pin (section 5), `PY_VERSION` against `pythonVersion` (issue #47), and
    `Py_GIL_DISABLED 1` in every `pyconfig` header of the unpacked tree.
*   **Not acquired any more:** python.org's GIL Android archives and GIL iOS XCframework (3.15), and BeeWare's
    `Python-Apple-support` (3.14 and earlier, GIL). Neither publishes a free-threaded mobile build, and
    the GIL build is not kept (#158). Their pins and the `pythonAppleSupportBuild` property are removed.
*   **python-build-standalone Status:** `python-build-standalone` has moved from indygreg to Astral (`https://github.com/astral-sh/python-build-standalone`). It **does not** publish prebuilt binaries for `android` or `ios`.
*   **Prebuilt-cpython Status:** The `python/prebuilt-cpython` repository is currently a planning repository containing no artifacts, so it is not a source.

## 4. Stable ABI and free-threaded builds

*   **The constraint:** the ~330 bindings target the CPython Stable ABI, and CPython's Limited API /
    `abi3` is not offered by free-threaded builds in 3.13 and 3.14 (3.15 adds `abi3t`, PEP 803).
*   **Why it does not block free-threading here:** the bindings are symbols resolved dynamically at run
    time and `Py_LIMITED_API` is not defined, so no compile-time ABI contract applies. 3.14t worked on
    desktop when it was an opt-in (the whole desktop suite: 236 tests, 0 failures, at the time of ROADMAP
    §9; SPEC T-2); desktop now embeds 3.15t only (section 2). An earlier version of this section concluded the opposite ("fails or crashes
    due to opaque structural changes", "wait for 3.15t") and was wrong; see
    [`../design/threading-and-abi.md`](../design/threading-and-abi.md) and
    [`../archive/threading-and-abi-3.15t-gate.md`](../archive/threading-and-abi-3.15t-gate.md).
*   **Scope:** free-threaded prebuilts from third parties exist only for the desktop targets (section 2);
    Android and iOS use the project's own 3.15t builds (section 3, #163, #328), and wasm stays GIL until
    #175. Which build each platform embeds is the library's per-platform `embedsFreeThreadedPython`
    (SPEC T-1). The interpreter version is `pythonVersion` in
    `gradle.properties`.

## 5. Security and Integrity Verification

*   **Desktop:** `python-build-standalone` provides a `SHA256SUMS` file with every release. The build script verifies the downloaded archive against this manifest.
*   **Android and iOS (the project's `cpython-<version>t-mobile` release): the lockfile pin.** The release
    carries a `SHA256SUMS` and no Sigstore bundle; the pins `android-aarch64-<v>-freethreaded`,
    `android-x86_64-<v>-freethreaded` and `ios-<v>-freethreaded` were checked against it and against a
    local `shasum` when they were written (2026-11-13). The build checks the lockfile only.
*   **python.org's Android and iOS archives used to be Sigstore verified** through their sibling
    `<archive>.sigstore` bundles, with the release manager's identity pinned per series. Those archives are
    GIL builds and are no longer acquired (section 3), so that verification path and its identity map
    were removed from the build. The record is in git history.
*   **Opt-in, `-PverifyPythonSignatures=true`.** Off by default because `sigstore-java` pulls in grpc-netty-shaded, protobuf, bouncycastle and guava, and because fetching the TUF trust root needs network access, which would turn an offline build from working into failing. Gradle resolves the configuration lazily, so a default build downloads none of it.
*   **Desktop (`python-build-standalone`), Sigstore verified via GitHub Attestations.** The release carries 853 assets and the only non-archive among them is `SHA256SUMS`: there is no `.sigstore` file. Provenance is published through GitHub's *attestations* API instead, keyed by artifact **digest** rather than filename, and that endpoint is rate-limited to 60 requests/hour unauthenticated. Because of this rate limit and the bundle size (~212KB each), the build does not check these bundles into the repository. Instead, when `-PverifyPythonSignatures=true` is set, it fetches the DSSE bundle from the API, caches it locally as `.sigstore`, and uses `sigstore-java` to verify it against the pinned `astral-sh/python-build-standalone` workflow identity. Desktop is therefore protected by the lockfile, the release's own `SHA256SUMS`, and Sigstore.
*   **The lockfile is not superseded by any of this.** The two gates prove different things, the lockfile says "these are the exact bytes this repository reviewed and pinned", Sigstore says "these are the bytes the release manager actually signed". The lockfile is also the only check that works offline and the only one covering every source, so it stays unconditional and Sigstore is layered on top of it.
*   **The verification is known to fail when it should**, which is the only thing that makes it worth having. Two negative controls were run against the real 3.14.7 Android bundle (python.org, before #328 removed that source): flipping one base64 character in `messageSignature.signature` gave `KeylessVerificationException: Artifact signature was not valid`, and pointing the identity map at the 3.13 release manager gave `No provided certificate identities matched values in certificate`. Both failed the build with a non-zero exit.

## 6. WebAssembly

No distributor ships a `python.wasm` that exports `wasmExports` and `wasmMemory` to JavaScript, which the
`wasmJs` target needs. CPython is therefore **built**, not downloaded: `python-multiplatform/scripts/wasm/build-cpython.sh` builds
CPython 3.14.2 for `wasm32-emscripten` (Emscripten 5.0.3, matched to the `pyemscripten_2026_0` platform of
PEP 783) under the git-ignored `.caches/`, and `python-multiplatform-wasm-runtime` is the published zip of
the result. This is deliberately a different patch release from the native `pythonVersion`; see
[`wasm-design.md`](wasm-design.md).

## 7. Include directories as a build output

Compiling a C extension against the embedded CPython needs that CPython's headers, not the host's.
`python-multiplatform` exposes them as a project extension named `cpythonIncludeDirectories`
(`python.multiplatform.gradle.CPythonIncludeDirectories`, in the Gradle plugin module).

*   **Key:** `(target, flavour)`. Targets: `macos-aarch64`, `macos-x86_64`, `linux-x86_64`,
    `windows-x86_64`, `android-aarch64`, `android-x86_64`, `ios-arm64`, `ios-arm64_x86_64-simulator`
    (the iOS names are XCFramework slices). Flavour: `CPythonFlavour.FREE_THREADED` or `GIL`; the default
    is the one the library embeds for that target, `CPythonIncludeLayout.embeddedFlavour(target)`:
    `FREE_THREADED` on every target (desktop #158, Android and iOS #328). Android and iOS have no `GIL`
    layout at all. (Making the default follow the embedded build, rather than `GIL` everywhere, is an
    agent proposal, not confirmed: with `GIL` the documented `includeDir("macos-aarch64")` would throw
    on every build.)
*   **Value:** `Provider<Directory>` of the directory that directly contains `Python.h`, i.e. the `-I`
    directory: `<ver>/<target>[-freethreaded]/python/include/python3.14[t]` on desktop (Windows has no
    `python3.14` level), `<ver>/android-*-freethreaded/prefix/include/python3.15t`,
    `<ver>/ios/Python.xcframework/<slice>/Python.framework/Headers`.
*   **Task dependency:** the provider carries the matching `downloadPython_*` task; using it as a task
    input makes Gradle download and extract first.
*   **Only the embedded flavour is extracted.** Every target extracts the free-threaded tree only; asking for
    the other flavour throws a `GradleException` naming #158 (desktop) or #328 (Android, iOS);
    `isAvailable` tests it without throwing.

```kotlin
// consumer build.gradle.kts
evaluationDependsOn(":python-multiplatform")
val includes = project(":python-multiplatform").extensions.getByType<CPythonIncludeDirectories>()
tasks.register<Exec>("compileExt") {
    val inc = includes.includeDir("macos-aarch64")   // the free-threaded tree, .../include/python3.15t
    inputs.dir(inc)
    commandLine("cc", "-I${inc.get().asFile}", "-c", "ext.c")
}
```

### Link libraries

The same extension exposes the library to link against (issue #56):
`libraryDir(target, flavour)` (`Provider<Directory>`, the `-L` directory) and
`libraryFile(target, flavour)` (`Provider<RegularFile>`), both carrying the `downloadPython_*` task.

| Target | Link library (under `<ver>/`) |
|---|---|
| `android-aarch64`, `android-x86_64` | `<target>-freethreaded/prefix/lib/libpython3.15t.so` |
| `windows-x86_64` | `windows-x86_64/python/libs/python314.lib` (`python3.lib` is the stable-ABI one) |
| `windows-x86_64` free-threaded | `windows-x86_64-freethreaded/python/libs/python314t.lib` |
| macOS, Linux, iOS | none -- `isLinkRequired(target)` is false; `libraryDir`/`libraryFile` throw `GradleException` |

Android has no GIL build since #328, so that combination throws. On Windows the default is the
free-threaded `python315t.lib`; the GIL one is not extracted. `isLinkAvailable(target, flavour)`
tests without throwing; the other flavour fails naming #158, as for `includeDir`.

## Published version

A consumer that builds against `python-multiplatform` must know which CPython it embeds (issue #61).
The answer is published three ways. The version is `pythonVersion` in `gradle.properties`; whether
the build is free-threaded differs by platform (desktop 3.15t since #158, Android, androidNative and
iOS 3.15t since #328, wasm GIL until #175), so each published artefact states its own.

*   **Extension** `pythonMultiplatform` (`python.multiplatform.gradle.EmbeddedPythonVersion`):
    `pythonVersion` (`3.15.0`), `majorMinor` (`3.15`), `freeThreaded`. One value per project: the
    free-threaded one every non-wasm platform embeds (`true`; agent proposal, not confirmed). For any other platform, read its variant's
    attribute or its resource.
*   **Gradle attributes** on every consumable `*Elements` configuration (so they land in the published
    module metadata): `org.thisisthepy.python.version` and `org.thisisthepy.python.free-threaded`
    (both `String`; the latter is `"true"`/`"false"`). The free-threaded one is per variant: `"true"` on
    `desktop*`, `macosArm64*`, `linuxX64*`, `android*` (the AAR and androidNative) and `ios*` variants,
    `"false"` on the wasm variants, and absent on shared metadata variants, which carry no CPython of their own (the
    metadata rule is an agent proposal, not confirmed).
*   **Resource** `META-INF/python-multiplatform/python.properties` (`pythonVersion=`, `freeThreaded=`),
    inside the desktop jar (`freeThreaded=true`, from `generateDesktopPythonProperties`) and the Android
    AAR's `classes.jar` (`freeThreaded=true` since #328, from `generateAndroidPythonProperties`).

```kotlin
// composite build (includeBuild): read the extension
evaluationDependsOn(":python-multiplatform")
val embedded = project(":python-multiplatform").extensions.getByType<EmbeddedPythonVersion>()
println(embedded.majorMinor)

// published artefact: read the resource from the classpath
val text = Thread.currentThread().contextClassLoader
    .getResourceAsStream(EmbeddedPythonVersion.RESOURCE_PATH)!!.use { it.readBytes().decodeToString() }
val embedded = EmbeddedPythonVersion.parse(text)

// published artefact: require a matching variant through the attributes
configurations.named("desktopRuntimeClasspath") {
    attributes.attribute(Attribute.of("org.thisisthepy.python.version", String::class.java), "3.15.0")
}
```
