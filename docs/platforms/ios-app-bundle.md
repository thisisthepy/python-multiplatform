# iOS: the standard library and the payload inside the app bundle

SPEC L-11, issue #59, ROADMAP §13.1.

`Python.xcframework` carries the interpreter and headers and no standard library, and an installed
app is launched with no `PYTHONHOME`. Until now only the simulator *test* binary had a stdlib
(`extractIosSimulatorStdlib` + `SIMCTL_CHILD_PYTHONHOME`), and that recipe does not carry over to an
app: a `PYTHONHOME` on this workspace's external volume parks the sandboxed app in `open()` at 0% CPU
(`python-multiplatform/src/iosMain/README.md`). This page records how an app now carries its own
prefix and payload, why, and how to check it.

## What happens

**Gradle** (`python-multiplatform/build.gradle.kts`, rules in the plugin's `IosPythonHomeLayout`).
One task per slice, `stageIosPythonHome_<sdk>_<arch>` (`iphoneos_arm64`, `iphonesimulator_arm64`,
`iphonesimulator_x86_64`), writes `python-multiplatform/build/python-ios-home/<sdk>-<arch>/`:

| Path | From `Python.xcframework/` |
|---|---|
| `home/lib/python<X.Y>/` | `lib/python<X.Y>/` (pure Python, every slice) merged with `<slice>/lib-<arch>/python<X.Y>/` (`lib-dynload/*.so`, `_sysconfigdata__ios_*`) |
| `dylib-Info-template.plist` | `build/iOS-dylib-Info-template.plist` (input to the Xcode phase; not shipped) |

Not copied: `__pycache__`, `libpython*.dylib`, and the top-level packages `test` (36 MB and 1,641 of
the 2,407 files of the 3.14 stdlib), `idlelib`, `tkinter`, `turtledemo` (no `_tkinter` on iOS) and
`ensurepip` (pip cannot run in an app). `stageIosPythonHomeForXcode` reads `EFFECTIVE_PLATFORM_NAME`
and `ARCHS` from the environment Xcode gives a Run Script phase, stages only that slice and prints:

```
PYTHON_HOME_DIR=<abs>/python-multiplatform/build/python-ios-home/iphonesimulator-arm64/home
PYTHON_DYLIB_INFO_TEMPLATE=<abs>/python-multiplatform/build/python-ios-home/iphonesimulator-arm64/dylib-Info-template.plist
```

A universal `ARCHS` (`arm64 x86_64`) is refused with the fix (`ONLY_ACTIVE_ARCH=YES` or `ARCHS=arm64`):
one prefix holds one `lib-dynload`. Upstream's `utils.sh` has the same limit without saying so.

**Xcode** (`tools/xcode/install-python.sh`, a Run Script phase after Copy Bundle Resources and before
Embed Frameworks; `ENABLE_USER_SCRIPT_SANDBOXING = NO`).

1. Runs Gradle as above. The output is captured, then parsed, then checked to be a directory — a
   failing Gradle stops the phase and never leaves `rsync` an empty source (issue #59's comment).
2. `rsync -a --delete` the prefix to `<app>/python-multiplatform-home/`.
3. The payload, if configured, to `<app>/python/` (`--delete`, without `__pycache__`):
   `PYTHON_PAYLOAD_DIR=<dir>` (a directory whose contents are the payload root — the app's own Python
   sources, or toolchain's `build/pythonStaging/ios/python`), or `PYTHON_PAYLOAD_TASK=<gradle task>`
   that prints `PYTHON_PAYLOAD_DIR=` under `-q` (toolchain's `:app:stagePythonBundleIosForXcode`).
   Neither set: no payload, and an old `<app>/python/` is removed.
4. Every `.so` under both directories becomes `Frameworks/<dotted.module>.framework/<dotted.module>`
   with an `Info.plist` from the template, a `<name>.fwork` placeholder where the `.so` was (its
   content is the framework binary's path relative to the app), and a `.origin` back-reference;
   each framework is signed with `EXPANDED_CODE_SIGN_IDENTITY` when there is one.

**Run time** (`python-multiplatform/src/iosMain/.../env/PackagedPythonHome.ios.kt`).
`Python3.initialize()` calls `applyPackagedPythonHome()` after the `PYTHONHOME` pre-flight check
(L-3) and before `Py_Initialize()`. `IosPythonHome.resolve()` takes the first of:

1. `PYTHONHOME` in the environment — nothing else happens. The simulator test task and an Xcode
   scheme variable keep working as before.
2. `<NSBundle.mainBundle.resourcePath>/python-multiplatform-home`, only if that directory exists.

A bundled prefix is checked with `PythonHomeCheck` (an `IllegalStateException` naming the path and
"the app bundle's resource directory"), converted with `Py_DecodeLocale` (UTF-8 on Apple platforms)
and passed to `Py_SetPythonHome`. `PythonPayload` then finds `<resourcePath>/python` and puts it on
`sys.path`, as before.

## Why this design

**Upstream's framework rule, not loose `.so` files.** iOS does not load a binary that is not inside a
framework on a device, and App Store validation rejects one. CPython 3.13+ on iOS has
`importlib.machinery.AppleFrameworkLoader`: for `foo.cpython-314-iphoneos.fwork` it reads the file,
joins it to `dirname(sys.executable)` and loads that binary. The script is upstream's
`Python.xcframework/build/utils.sh` `install_dylib`, with two changes: the template comes from the
Gradle staging (so the phase needs no path into the archive), and signing is skipped when Xcode
exports no identity instead of failing. The simulator would load a loose `.so`; the device would not,
so the simulator run uses the device's layout.

**`python-multiplatform-home/`, not upstream's `python/`.** Upstream's testbed sets
`config.home = <resourcePath>/python`. Here `python/` is the consumer's payload root
(`PythonPayload.PAYLOAD_ROOT`, L-6), and the payload copy is `rsync --delete`: one directory for
both would let each copy delete the other. The name is the one desktop already uses (L-9).

**Staged per slice by Gradle, copied by a thin script.** The stdlib merge (shared tree + one
`lib-<arch>` tree) and the exclusions are pure functions with unit tests
(`GP/IosPythonHomeLayoutTest.kt`); the script only copies, wraps and signs. The selection lives in the
Gradle plugin because the library build script already imports the plugin's public layout objects
(`CPythonIncludeLayout`) and a build script cannot be unit-tested.

**`Py_SetPythonHome`, not `setenv`.** Same reasoning as desktop: it sets CPython's path
configuration for this process only, and it is checked by the same `PythonHomeCheck` immediately
before. `PyConfig.home` is the non-deprecated route, but the library carries no `PyConfig` layout.

## Wiring an app

The sample's project is `iosApp/iosApp.xcodeproj` (product `PythonDemo.app`, bundle id
`org.thisisthepy.python.multiplatform.demo`). Its phase:

```sh
set -e
cd "$SRCROOT/.."
export PYTHON_PAYLOAD_DIR="$SRCROOT/../sample/python/src/main"
/bin/bash tools/xcode/install-python.sh
```

It replaced two phases: "Install Target Specific Python Standard Library", which rsynced the slice's
`lib/` — only `libpython3.14.dylib` — into `<app>/lib/`, and "Prepare Python Binary Modules", which
did the framework wrapping from a template copied in as a resource. `iosApp/iosApp/dylib-Info-template.plist`
is no longer copied into the app; the file itself is still in the project.

`sample/src/iosMain/app.xcodeproj` is not wired: it still references `../../dist/toolchain/...` and
a `ComposeApp` framework that this build does not produce, and has never been built here.

A consumer outside this repository cannot call `:python-multiplatform:stageIosPythonHomeForXcode`:
the iOS archive download lives in this repository's build script, not in the published plugin. That
is the next step if consumers are to get this without copying the sample.

## Checking it (completion criterion of #59)

The sample app on the simulator imports a stdlib module, a stdlib extension module and a payload
module from the installed bundle, launched with **no** `SIMCTL_CHILD_*` variable. All commands from
the repository root; Gradle needs JDK 21 (`JAVA_HOME`), and Xcode's phases inherit it.

```bash
export JAVA_HOME=/Users/ibrew/Library/Java/JavaVirtualMachines/jdk-21.0.12+8/Contents/Home
df -h /     # the installed app (~60 MB) goes into CoreSimulator on the internal disk

# 0. Stage once by hand and look at it
EFFECTIVE_PLATFORM_NAME=-iphonesimulator ARCHS=arm64 \
  ./gradlew -q :python-multiplatform:stageIosPythonHomeForXcode > .tmp/stage.out 2>&1; echo "EXIT=$?"
cat .tmp/stage.out          # two lines: PYTHON_HOME_DIR=..., PYTHON_DYLIB_INFO_TEMPLATE=...
ls python-multiplatform/build/python-ios-home/iphonesimulator-arm64/home/lib/python3.14/lib-dynload | head
test ! -e python-multiplatform/build/python-ios-home/iphonesimulator-arm64/home/lib/python3.14/test && echo "test/ excluded"
# must fail, naming ONLY_ACTIVE_ARCH:
EFFECTIVE_PLATFORM_NAME=-iphonesimulator ARCHS="arm64 x86_64" \
  ./gradlew -q :python-multiplatform:stageIosPythonHomeForXcode > .tmp/stage-universal.out 2>&1; echo "EXIT=$?"

# 1. Frameworks the Xcode project links against
./gradlew :sample:prepareIosFrameworks --console=plain > .tmp/prepare.log 2>&1; echo "EXIT=$?"

# 2. Build the app (flags from the last verified run, ROADMAP history 2026-08-13: actool cannot
#    compile the app icon against this machine's simulator runtimes, and -target avoids
#    -destination's eligibility check)
(cd iosApp && xcodebuild -project iosApp.xcodeproj -target iosApp -configuration Debug \
    -sdk iphonesimulator ARCHS=arm64 ONLY_ACTIVE_ARCH=YES SYMROOT="$PWD/build" \
    ASSETCATALOG_COMPILER_APPICON_NAME="" \
    CODE_SIGN_IDENTITY="-" CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM="" build) \
  > .tmp/xcodebuild.log 2>&1; echo "EXIT=$?"
grep -E "Installing the Python|Wrapped|error:" .tmp/xcodebuild.log

APP=iosApp/build/Debug-iphonesimulator/PythonDemo.app
ls "$APP/python-multiplatform-home/lib/python3.14/os.py" "$APP/python/example_py/__init__.py"
ls "$APP/python-multiplatform-home/lib/python3.14/lib-dynload" | grep -c '\.fwork$'   # 67 for 3.14
find "$APP/python-multiplatform-home" "$APP/python" -name '*.so' | wc -l               # 0
ls -d "$APP/Frameworks/_json.framework" "$APP/Frameworks/Python.framework"
codesign --verify --deep --strict "$APP" && echo SIGNED_OK

# 3. Install and launch with no SIMCTL_CHILD_* variable
UDID=$(xcrun simctl list devices booted | grep -oE '[0-9A-F-]{36}' | head -n 1); echo "$UDID"
env | grep '^SIMCTL_CHILD_' && echo "unset these first"     # must print nothing
xcrun simctl uninstall "$UDID" org.thisisthepy.python.multiplatform.demo
xcrun simctl install "$UDID" "$APP"
xcrun simctl launch --console-pty --terminate-running-process "$UDID" \
    org.thisisthepy.python.multiplatform.demo > .tmp/launch.log 2>&1 &
LAUNCH=$!
# wait for the DEMO block (Monitor/until-loop; no sleep in this shell), then:
kill "$LAUNCH"
grep '^DEMO' .tmp/launch.log
```

Pass: `DEMO 1 runtime` names `3.14.x · sys.platform=ios`, and `DEMO 8 bundle` is a `tuple:` whose
five entries are the payload's greeting as JSON (`"Hello from usage-example's Python payload 0.1.0"`),
`_json.__file__` ending `PythonDemo.app/python-multiplatform-home/lib/python3.14/lib-dynload/_json.cpython-314-iphonesimulator.fwork`,
`example_py.__file__` ending `PythonDemo.app/python/example_py/__init__.py`, `sys.prefix` ending
`PythonDemo.app/python-multiplatform-home`, and `sys.executable` ending `PythonDemo.app/PythonDemo`.

Fail shapes and what they mean:

| Seen | Meaning |
|---|---|
| No `DEMO` lines, 0% CPU (`sample <pid>` parked in `open$NOCANCEL`) | the app read a path outside its container — check `env` for a stray `PYTHONHOME` |
| `Fatal Python error: Failed to import encodings module` | no `python-multiplatform-home/` in the installed app; the phase did not run |
| `DEMO 8 bundle | ModuleNotFoundError: No module named '_json'` | the `.fwork` did not resolve; compare `sys.executable`'s directory with the app directory |
| `DEMO 8 bundle | ModuleNotFoundError: No module named 'example_py'` | `<app>/python/` missing: `PYTHON_PAYLOAD_DIR` not set in the phase |
