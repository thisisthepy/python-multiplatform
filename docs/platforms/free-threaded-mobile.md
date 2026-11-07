# Free-threaded CPython for Android and iOS

Status: build recipe written from the CPython 3.15.0 source; **not yet run in CI,
not yet run on a device or emulator.** Tracking issue: #163.

python-multiplatform gets its parallelism from free-threaded CPython (decision 8 in
`AGENTS.md`). Desktop uses the python.org 3.14t builds. No prebuilt 3.15t exists for
Android or iOS, so the workflow `.github/workflows/build-ft-mobile.yml` builds it with
CPython's own mobile tooling.

## What is built

| Platform | Host | Tooling | Output |
|---|---|---|---|
| Android | `aarch64-linux-android` | `Platforms/Android` | `python-3.15.0-aarch64-linux-android.tar.gz` |
| iOS | `arm64-apple-ios` (device), `arm64-apple-ios-simulator`, `x86_64-apple-ios-simulator` | `Platforms/Apple` | `python-3.15.0-iOS-XCframework.tar.gz` |

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

## Not verified

- The workflow has not run yet. Whether 3.15.0 configures and compiles with
  `--disable-gil` for these targets is unknown; free-threaded support on Android and iOS
  is not a documented CPython target.
- Nothing has been loaded on a device, emulator or simulator. No import of `_ssl`,
  `_ctypes` or extension modules, no GIL-off test run, no thread-safety check.
- Whether the prebuilt dependency tarballs and the Xcode on `macos-15` suit 3.15.0 is
  unconfirmed.
- Integration with the Gradle plugin's `PYTHONHOME` staging, with the Android
  `libpython` loading code, and with the iOS framework embedding (which expects
  `Python.framework` and a non-`t` library name in places) is not done.
- The x86_64 simulator slice is built only because the packaging step requires it.
