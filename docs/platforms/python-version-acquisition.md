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

## 4. Stable ABI Implications (PEP 803)

*   **The Constraint:** This project relies entirely on the CPython Stable ABI for its ~330 bindings.
*   **Python 3.13 & 3.14:** The traditional Stable ABI (`abi3`) **does not** support free-threaded (GIL-disabled) builds. If we attempt to use a free-threaded 3.14 build, our bindings relying on `abi3` will fail or crash due to opaque structural changes in `PyObject`.
*   **Python 3.15 (PEP 803):** Python 3.15 introduces `abi3t`, a new variant of the Stable ABI specifically for free-threaded builds.
*   **Conclusion:** We cannot use free-threaded variants with the Stable ABI in 3.14. We must wait for (or test against) 3.15 to utilize `abi3t` for our bindings.

## 5. Security and Integrity Verification

*   **Desktop:** `python-build-standalone` provides a `SHA256SUMS` file with every release. The build script verifies the downloaded archive against this manifest.
*   **Android (python.org) and iOS (python.org, 3.15+) — Sigstore verified.** This bullet used to say that "full Sigstore verification directly inside Gradle is unreasonable without shelling out to external tools (like `sigstore-python` or the `cosign` CLI)". **That was wrong**, and it is now implemented. `dev.sigstore:sigstore-java` does the whole verification in-process — certificate chain, Rekor inclusion and OIDC identity — with no external binary. python.org publishes a sibling `<archive>.sigstore` bundle for both Android tarballs and, from 3.15, the iOS XCframework. (The extension is `.sigstore`; `.sigstore.json` returns 404.) There is still no plain checksum manifest, so the lockfile continues to pin the digest as well.
*   **The signer identity is pinned, and has to be version-keyed.** Verifying a bundle *without* pinning an identity proves only that somebody holding a Sigstore certificate signed the bytes, which anyone can arrange. The build pins the Fulcio SAN and OIDC issuer of the actual CPython release manager, per <https://www.python.org/download/sigstore/>. That must be a map rather than a constant, because it changes per release series: 3.14/3.15 are `hugo@python.org` via `https://github.com/login/oauth`, whereas 3.12/3.13 are `thomas@python.org` via `https://accounts.google.com`. An unrecorded series is a hard build failure, never a silent skip.
*   **Opt-in — `-PverifyPythonSignatures=true`.** Off by default because `sigstore-java` pulls in grpc-netty-shaded, protobuf, bouncycastle and guava, and because fetching the TUF trust root needs network access, which would turn an offline build from working into failing. Gradle resolves the configuration lazily, so a default build downloads none of it (measured: a default `downloadPython_android_*` run mentions Sigstore zero times).
*   **Desktop (`python-build-standalone`) — Sigstore verified via GitHub Attestations.** The release carries 853 assets and the only non-archive among them is `SHA256SUMS`: there is no `.sigstore` file. Provenance is published through GitHub's *attestations* API instead, keyed by artifact **digest** rather than filename, and that endpoint is rate-limited to 60 requests/hour unauthenticated. Because of this rate limit and the bundle size (~212KB each), the build does not check these bundles into the repository. Instead, when `-PverifyPythonSignatures=true` is set, it fetches the DSSE bundle from the API, caches it locally as `.sigstore`, and uses `sigstore-java` to verify it against the pinned `astral-sh/python-build-standalone` workflow identity. Desktop is therefore protected by the lockfile, the release's own `SHA256SUMS`, and Sigstore.
*   **iOS (BeeWare, ≤ 3.14) — nothing exists to verify against.** The `Python-Apple-support` releases publish five `tar.gz` assets and nothing else: no checksums, no signatures, and no GitHub attestations. The lockfile pin is the only honest instrument available here, and no amount of build wiring changes that.
*   **The lockfile is not superseded by any of this.** The two gates prove different things — the lockfile says "these are the exact bytes this repository reviewed and pinned", Sigstore says "these are the bytes the release manager actually signed". The lockfile is also the only check that works offline and the only one covering every source, so it stays unconditional and Sigstore is layered on top of it.
*   **The verification is known to fail when it should**, which is the only thing that makes it worth having. Two negative controls were run against the real 3.14.7 Android bundle: flipping one base64 character in `messageSignature.signature` gave `KeylessVerificationException: Artifact signature was not valid`, and pointing the identity map at the 3.13 release manager gave `No provided certificate identities matched values in certificate`. Both failed the build with a non-zero exit.
