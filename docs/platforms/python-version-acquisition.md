# CPython Version Acquisition Research

## 1. Desktop Platforms (macOS, Linux, Windows)

The project currently uses `python-build-standalone` (maintained by Astral). For Python 3.14 and 3.15, this project continues to publish pre-built self-contained binaries. 

*   **Release Tags:** Releases are tagged by date (e.g., `20260807`).
*   **Asset Naming Scheme:** The asset naming follows the pattern: `cpython-<python-version>+<build-date>-<target>-<flavour>.<ext>`.
    *   Targets include: `aarch64-apple-darwin`, `x86_64-apple-darwin`, `x86_64-unknown-linux-gnu`, `x86_64-pc-windows-msvc`, `aarch64-pc-windows-msvc`.
    *   Flavours include: `install_only`, `install_only_stripped`, and `full` (which also includes debug symbols, pgo/lto variants). The archives are available in `.tar.gz` and `.tar.zst` formats.
*   **URL Pattern:** `https://github.com/astral-sh/python-build-standalone/releases/download/<release-tag>/<asset-name>`

## 2. Free-threaded Variants

`python-build-standalone` publishes free-threaded builds for Python 3.14 and 3.15.
*   **Naming:** The string `freethreaded` is injected into the asset name before the flavour, e.g., `cpython-3.14.7+20260807-aarch64-apple-darwin-freethreaded-install_only.tar.gz`.
*   **ABI and Libraries:** The ABI suffix is `t`, meaning the shared libraries will be named `libpython3.14t.dylib` or `libpython3.14t.so`.
*   **Availability:** They are available for all major desktop targets (macOS, Linux, Windows).

## 3. Android and iOS Support

*   **Android:** Prebuilt artifacts for Android are provided natively by python.org. These archives are plain NDK cross-compilations (e.g., CPython's own build tree). They can be found at `https://www.python.org/ftp/python/<version>/python-<version>-<arch>-linux-android.tar.gz`.
*   **iOS:** Prebuilt artifacts for iOS are provided by the BeeWare project via their `Python-Apple-support` repository, which are CPython's own iOS build layouts (e.g. `prefix: iOS/Frameworks/...`). They are located at `https://github.com/beeware/Python-Apple-support/releases`. The kivy toolchains are **not** the source of these artifacts.
*   **python-build-standalone Status:** `python-build-standalone` has moved from indygreg to Astral (`https://github.com/astral-sh/python-build-standalone`). It **does not** publish prebuilt binaries for `android` or `ios`. 
*   **Prebuilt-cpython Status:** The `python/prebuilt-cpython` repository is currently a planning repository containing no artifacts, so it is not a source.
*   **Conclusion:** We acquire Android artifacts directly from python.org, and iOS artifacts from BeeWare's Python-Apple-support, eliminating the need for kivy toolchain dependency.

## 4. Stable ABI and free-threaded builds

*   **The constraint:** the ~330 bindings target the CPython Stable ABI, and CPython's Limited API /
    `abi3` is not offered by free-threaded builds in 3.13 and 3.14 (3.15 adds `abi3t`, PEP 803).
*   **Why it does not block free-threading here:** the bindings are symbols resolved dynamically at run
    time and `Py_LIMITED_API` is not defined, so no compile-time ABI contract applies. 3.14t works on
    desktop with `-PpythonFreeThreaded=true` (the whole desktop suite: 236 tests, 0 failures, at the time
    of ROADMAP §9; SPEC T-2). An earlier version of this section concluded the opposite ("fails or crashes
    due to opaque structural changes", "wait for 3.15t") and was wrong; see
    [`../design/threading-and-abi.md`](../design/threading-and-abi.md) and
    [`../archive/threading-and-abi-3.15t-gate.md`](../archive/threading-and-abi-3.15t-gate.md).
*   **Scope:** free-threaded prebuilts exist only for the desktop targets (section 2); Android (python.org)
    and iOS (Python-Apple-support) publish none, so the flag is desktop-only and defaults to `false`
    (`gradle.properties`). The interpreter version is `pythonVersion` in `gradle.properties`.

## 5. Security and Integrity Verification

*   **Desktop:** `python-build-standalone` provides a `SHA256SUMS` file with every release. The build script verifies the downloaded archive against this manifest.
*   **Android (python.org) and iOS (python.org, 3.15+) — Sigstore verified.** This bullet used to say that "full Sigstore verification directly inside Gradle is unreasonable without shelling out to external tools (like `sigstore-python` or the `cosign` CLI)". **That was wrong**, and it is now implemented. `dev.sigstore:sigstore-java` does the whole verification in-process — certificate chain, Rekor inclusion and OIDC identity — with no external binary. python.org publishes a sibling `<archive>.sigstore` bundle for both Android tarballs and, from 3.15, the iOS XCframework. (The extension is `.sigstore`; `.sigstore.json` returns 404.) There is still no plain checksum manifest, so the lockfile continues to pin the digest as well.
*   **The signer identity is pinned, and has to be version-keyed.** Verifying a bundle *without* pinning an identity proves only that somebody holding a Sigstore certificate signed the bytes, which anyone can arrange. The build pins the Fulcio SAN and OIDC issuer of the actual CPython release manager, per <https://www.python.org/download/sigstore/>. That must be a map rather than a constant, because it changes per release series: 3.14/3.15 are `hugo@python.org` via `https://github.com/login/oauth`, whereas 3.12/3.13 are `thomas@python.org` via `https://accounts.google.com`. An unrecorded series is a hard build failure, never a silent skip.
*   **Opt-in — `-PverifyPythonSignatures=true`.** Off by default because `sigstore-java` pulls in grpc-netty-shaded, protobuf, bouncycastle and guava, and because fetching the TUF trust root needs network access, which would turn an offline build from working into failing. Gradle resolves the configuration lazily, so a default build downloads none of it (measured: a default `downloadPython_android_*` run mentions Sigstore zero times).
*   **Desktop (`python-build-standalone`) — Sigstore verified via GitHub Attestations.** The release carries 853 assets and the only non-archive among them is `SHA256SUMS`: there is no `.sigstore` file. Provenance is published through GitHub's *attestations* API instead, keyed by artifact **digest** rather than filename, and that endpoint is rate-limited to 60 requests/hour unauthenticated. Because of this rate limit and the bundle size (~212KB each), the build does not check these bundles into the repository. Instead, when `-PverifyPythonSignatures=true` is set, it fetches the DSSE bundle from the API, caches it locally as `.sigstore`, and uses `sigstore-java` to verify it against the pinned `astral-sh/python-build-standalone` workflow identity. Desktop is therefore protected by the lockfile, the release's own `SHA256SUMS`, and Sigstore.
*   **iOS (BeeWare, ≤ 3.14) — nothing exists to verify against.** The `Python-Apple-support` releases publish five `tar.gz` assets and nothing else: no checksums, no signatures, and no GitHub attestations. The lockfile pin is the only honest instrument available here, and no amount of build wiring changes that.
*   **The lockfile is not superseded by any of this.** The two gates prove different things — the lockfile says "these are the exact bytes this repository reviewed and pinned", Sigstore says "these are the bytes the release manager actually signed". The lockfile is also the only check that works offline and the only one covering every source, so it stays unconditional and Sigstore is layered on top of it.
*   **The verification is known to fail when it should**, which is the only thing that makes it worth having. Two negative controls were run against the real 3.14.7 Android bundle: flipping one base64 character in `messageSignature.signature` gave `KeylessVerificationException: Artifact signature was not valid`, and pointing the identity map at the 3.13 release manager gave `No provided certificate identities matched values in certificate`. Both failed the build with a non-zero exit.

## 6. WebAssembly

No distributor ships a `python.wasm` that exports `wasmExports` and `wasmMemory` to JavaScript, which the
`wasmJs` target needs. CPython is therefore **built**, not downloaded: `tools/wasm/build-cpython.sh` builds
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
    (the iOS names are XCFramework slices). Flavour: `CPythonFlavour.GIL` (default) or `FREE_THREADED`
    (desktop only).
*   **Value:** `Provider<Directory>` of the directory that directly contains `Python.h`, i.e. the `-I`
    directory: `<ver>/<target>[-freethreaded]/python/include/python3.14[t]` on desktop (Windows has no
    `python3.14` level), `<ver>/android-*/prefix/include/python3.14`,
    `<ver>/ios/Python.xcframework/<slice>/include/python3.14`.
*   **Task dependency:** the provider carries the matching `downloadPython_*` task; using it as a task
    input makes Gradle download and extract first.
*   **Only the configured flavour is extracted.** `-PpythonFreeThreaded=true` selects the free-threaded
    desktop tree; asking for the other flavour throws a `GradleException` naming that property
    (`isAvailable` tests it without throwing).

```kotlin
// consumer build.gradle.kts
evaluationDependsOn(":python-multiplatform")
val includes = project(":python-multiplatform").extensions.getByType<CPythonIncludeDirectories>()
tasks.register<Exec>("compileExt") {
    val inc = includes.includeDir("macos-aarch64")   // or ("linux-x86_64", CPythonFlavour.FREE_THREADED)
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
| `android-aarch64`, `android-x86_64` | `<target>/prefix/lib/libpython3.14.so` |
| `windows-x86_64` | `windows-x86_64/python/libs/python314.lib` (`python3.lib` is the stable-ABI one) |
| `windows-x86_64` free-threaded | `windows-x86_64-freethreaded/python/libs/python314t.lib` |
| macOS, Linux, iOS | none -- `isLinkRequired(target)` is false; `libraryDir`/`libraryFile` throw `GradleException` |

Android has no free-threaded build, so that combination throws. `isLinkAvailable(target, flavour)`
tests without throwing; the other flavour fails naming `-PpythonFreeThreaded`, as for `includeDir`.

## Published version

A consumer that builds against `python-multiplatform` must know which CPython it embeds (issue #61).
The answer is published three ways, all derived from `pythonVersion` / `pythonFreeThreaded` in
`gradle.properties`.

*   **Extension** `pythonMultiplatform` (`python.multiplatform.gradle.EmbeddedPythonVersion`):
    `pythonVersion` (`3.14.7`), `majorMinor` (`3.14`), `freeThreaded`.
*   **Gradle attributes** on every consumable `*Elements` configuration (so they land in the published
    module metadata): `org.thisisthepy.python.version` and `org.thisisthepy.python.free-threaded`
    (both `String`; the latter is `"true"`/`"false"`).
*   **Resource** `META-INF/python-multiplatform/python.properties` (`pythonVersion=`, `freeThreaded=`),
    inside the desktop jar and the Android AAR's `classes.jar`.

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
    attributes.attribute(Attribute.of("org.thisisthepy.python.version", String::class.java), "3.14.7")
}
```
