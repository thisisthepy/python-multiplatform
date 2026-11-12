# Free-threaded CPython for Android and iOS

Status: built in CI and published as the GitHub release `cpython-3.15.0t-mobile`
(aarch64 Android run 39013371415, x86_64 Android run 43300549614 (#396), iOS run
39014667720); the SHA-256 of each archive is in the release's `SHA256SUMS` and pinned in
`python-checksums.properties`. Since #328 step 2 the library embeds these builds on Android
(arm64-v8a and x86_64), androidNative and iOS. **Not yet run on a device, emulator or
simulator.** Tracking issues: #163, #328.

python-multiplatform gets its parallelism from free-threaded CPython (decision 8 in
`AGENTS.md`). Desktop uses python-build-standalone's 3.15t builds. No prebuilt 3.15t exists
for Android or iOS, so the workflow `.github/workflows/build-ft-mobile.yml` builds it with
CPython's own mobile tooling.

## What is built

| Platform | Host | Tooling | Output |
|---|---|---|---|
| Android | `aarch64-linux-android` | `Platforms/Android` | `python-3.15.0t-aarch64-linux-android.tar.gz` |
| Android | `x86_64-linux-android` (#396) | `Platforms/Android` | `python-3.15.0t-x86_64-linux-android.tar.gz` |
| iOS | `arm64-apple-ios` (device), `arm64-apple-ios-simulator`, `x86_64-apple-ios-simulator` | `Platforms/Apple` | `python-3.15.0t-iOS-XCframework.tar.gz` |

The iOS tooling only builds an XCframework from all of its slices, so the simulator
hosts are built as well as the device. There is no cheaper arm64-only path in the
script.

## How `--disable-gil` is passed

Both scripts accept extra `configure` arguments after `--`
(`Platforms/Android/__main__.py`: `"args", nargs="*", help="Extra arguments to pass to
`configure`"`; `Platforms/Apple/__main__.py`: `args", nargs="*", help="Extra arguments
to pass to `configure`"`). The workflow uses the discrete steps so that the flag reaches
both the build Python and every host:

```sh
python3 Platforms/Android configure-build -- --disable-gil
python3 Platforms/Android make-build
python3 Platforms/Android configure-host aarch64-linux-android -- --disable-gil
python3 Platforms/Android make-host aarch64-linux-android
python3 Platforms/Android package aarch64-linux-android

python3 Platforms/Apple configure-build -- --disable-gil
python3 Platforms/Apple make-build
python3 Platforms/Apple configure-host iOS <host-triple> -- --disable-gil   # per host
python3 Platforms/Apple make-host iOS <host-triple>
python3 Platforms/Apple package iOS
```

`configure.ac` turns the flag into `#define Py_GIL_DISABLED 1` and appends `t` to
`ABIFLAGS`, so the library, include directory and standard library directory become
`libpython3.15t`, `include/python3.15t` and `lib/python3.15t`. `--disable-gil` needs
mimalloc, which is on by default.

The build Python is also built with `--disable-gil`. The cross-build only requires the
same major.minor version (`--with-build-python`), so a GIL build Python would probably
work too, but matching them removes a variable.

## Scripts

`.github/scripts/ft-mobile/`:

- `common.sh`: source download, `Py_GIL_DISABLED` check, sha256 recording.
- `build-android.sh [VERSION]`: unpacks `Python-<VERSION>.tgz`, installs the NDK the
  tooling pins (`ndk_version=` in `Platforms/Android/android-env.sh`, 27.3.13750724 in
  3.15.0), runs the steps above, then verifies the package.
- `build-ios.sh [VERSION]`: same for iOS, with the two edits below.

Local use is possible on a Linux or macOS machine with the prerequisites, but the heavy
build is meant for CI (`AGENTS.md` rule 10). Both scripts honour `FT_WORK` and `FT_OUT`
(default under `.tmp/`).

### Android notes

- `Platforms/Android package` lists files with `git ls-files`, which does not work in an
  unpacked tarball. `build-android.sh` runs `git init` and `git add Platforms/Android`
  in the source tree first.
- The package contains `prefix/include/python3.15t/` and `prefix/lib/libpython3.15t.so`
  plus `lib/python3.15t/`, OpenSSL and SQLite libraries, and the `android.py` helper.
- Dependencies (OpenSSL, SQLite, libffi, xz, bzip2, zstd) come from the
  `beeware/cpython-android-source-deps` releases, as in upstream.

### iOS notes

- Upstream `Platforms/Apple/__main__.py` computes `version_tag` as `3.15`, but a
  free-threaded framework installs `lib/python3.15t`. Unpatched, `package` fails when it
  copies the `.xcprivacy` files into `lib-<arch>/python3.15/lib-dynload`. `build-ios.sh`
  appends `t` to `version_tag`; this also names the symlink `lib/libpython3.15t.dylib`.
- `testbed/Python.xcframework/build/utils.sh` finds the standard library with
  `grep -E "^python3\.\d+$"`, which does not match `python3.15t`. `build-ios.sh` changes
  it to `^python3\.[0-9]+t?$`. Each edit is asserted, so an upstream change fails the
  build instead of skipping the patch.
- The framework and its binary are still named `Python` / `Python.framework/Python`;
  only the `lib` side carries the `t`. The `Py_GIL_DISABLED` define is in
  `Headers/pyconfig-<arch>.h`; `Headers/pyconfig.h` is a wrapper that includes it.
- Dependencies come from `beeware/cpython-apple-source-deps` releases. The runner needs
  a full Xcode (`macos-15`); the Xcode version is printed in the log.

## Verification built into the workflow

Each script fails the job unless:

- the build Python reports `Py_GIL_DISABLED == 1`;
- Android: `include/python3.15t/pyconfig.h` has `#define Py_GIL_DISABLED 1`,
  `lib/libpython3.15t*.so` exists and is an aarch64 ELF, `lib/python3.15t` exists;
- iOS: every slice has `Headers/pyconfig-<arch>.h` with `#define Py_GIL_DISABLED 1`,
  `lib/libpython3.15t.dylib`, and a `Python.framework/Python` binary (`lipo -info`
  is logged); the XCframework has `lib/python3.15t` and exactly two slices.

The sha256 of each archive is written to the job summary and the archives are uploaded
as workflow artifacts (`python-<version>t-android-aarch64`,
`python-<version>t-ios-xcframework`).

## How the library uses them (#328 step 2)

- `python-multiplatform/build.gradle.kts` downloads the three archives from the release
  (`downloadPython_android_aarch64`, `downloadPython_android_x86_64`, `downloadPython_ios`),
  checks each against `python-checksums.properties` (`android-aarch64-<v>-freethreaded`,
  `android-x86_64-<v>-freethreaded`, `ios-<v>-freethreaded`) and checks the unpacked headers:
  `PY_VERSION` equals `pythonVersion`, and every `pyconfig` header defines
  `Py_GIL_DISABLED 1` (the iOS `pyconfig.h` wrapper is skipped in favour of the
  `pyconfig-<arch>.h` it includes).
- Android unpacks under `extracted/<v>/android-<arch>-freethreaded/`, links androidNative
  with `-lpython3.15t` against `include/python3.15t`, names the JNI library
  `libmultiplatform_python3.15t.so` (what `manager.loadLibPython` asks for), and packages
  `libpython3.15t.so` and `assets/<abi>/lib/python3.15t/` into the AAR for arm64-v8a and x86_64.
- iOS compiles and links against the 3.15t `Python.xcframework`; the simulator test binary
  gets `lib/python3.15t/` from `Python.xcframework/lib/python3.15t` and the slice's
  `lib-arm64/python3.15t`. The plugin's iOS consumer wiring stages the same framework (SPEC T-2a).
- `embedsFreeThreadedPython` is `true` in `androidMain`, `artMain` and `iosMain`, so
  `Versions.abiFlags` is `t` and the stdlib, stamp and library names follow.

## Not verified

- Nothing has been loaded on a device, emulator or simulator. No import of `_ssl`,
  `_ctypes` or extension modules, no GIL-off test run, no thread-safety check. The tests
  that would show it are `AndroidFreeThreadedBuildTest` (instrumented),
  `AndroidNativeFreeThreadedBuildTest` and `IosFreeThreadedBuildTest`; they are compiled,
  not run.
- Whether the prebuilt dependency tarballs and the Xcode on `macos-15` suit 3.15.0 is
  unconfirmed beyond the build itself.
- The x86_64 simulator slice is built only because the packaging step requires it.
