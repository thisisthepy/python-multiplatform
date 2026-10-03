# iosMain — rules

iOS-specific sources. Depends on `nativeMain`; CPython arrives as `Python.xcframework`.

## The framework ships no standard library

`Python.xcframework` contains the interpreter binary and headers and nothing else.
`Py_Initialize()` needs `PYTHONHOME` to point at the stdlib on disk, and there are two different
ways it fails to if that's wrong — which one you get depends on *what's running*, not just on what
`PYTHONHOME` says, and only one of them is easy to diagnose.

**A missing or empty prefix aborts.** `Py_Initialize()` calls `Py_FatalError()` and kills the
process with `Fatal Python error: Failed to import encodings module` on stderr. No Kotlin
exception, no interpreter left afterward, but at least it is fast and it says what happened.
`Python3.initialize()` runs a pre-flight check before `Py_Initialize()` is ever reached (see
`python.multiplatform.env.PythonHomeCheck`) that catches this shape of misconfiguration and turns
it into a catchable `IllegalStateException` naming the path instead.

**A `PYTHONHOME` under a sandboxed path can hang instead — with 0% CPU and nothing in the
console.** Reproduced directly (ROADMAP, 2026-08-13, "PYTHONHOME on an external volume hangs the
app instead of failing it"): the *installed simulator app* parked forever inside `open$NOCANCEL`
importing `encodings` from a path under `/Volumes/` — a blank window, no sandbox denial, no Python
error anywhere in the log. `PythonHomeCheck`'s own filesystem probe cannot promise to catch this
either: it reads the same directory through the same kind of syscall CPython's import machinery
does, and a path whose access is *parked* rather than *denied* can park the check identically.

**The test binary and an installed app get their stdlib differently.** The build extracts the
stdlib into `build/python-stdlib/`, and the simulator *test* task points `SIMCTL_CHILD_PYTHONHOME`
at it (`simctl` only forwards variables prefixed `SIMCTL_CHILD_`). That path is on this workspace's
external volume — fine for the test binary, which `simctl` launches with fewer sandbox restrictions
than an installed app, and exactly the shape that hangs an app rather than starting it.

An installed **app** carries its own prefix inside its bundle (SPEC L-11,
`docs/platforms/ios-app-bundle.md`):

- `stageIosPythonHome_<sdk>_<arch>` stages the stdlib for one slice; `tools/xcode/install-python.sh`,
  an Xcode Run Script phase, copies it to `<app>/python-multiplatform-home/`, copies the consumer's
  payload to `<app>/python/`, and wraps every `.so` as `Frameworks/<module>.framework` with a
  `.fwork` placeholder (Apple loads no loose binaries; CPython's `AppleFrameworkLoader` follows the
  placeholder).
- `IosPythonHome` (`PackagedPythonHome.ios.kt`) resolves `<resourcePath>/python-multiplatform-home`
  when `PYTHONHOME` is unset, checks it with `PythonHomeCheck`, and hands it to `Py_SetPythonHome`
  before `Py_Initialize()`. An environment `PYTHONHOME` still wins, which is what keeps the test task
  working.

## The consumer's payload

`PythonPayload.discoverStagedPayloadRoots` looks for `python/` under
`NSBundle.mainBundle.resourcePath` and puts it on `sys.path` if it is there. The Xcode phase above
puts it there, from `PYTHON_PAYLOAD_DIR` (a directory, e.g. the app's own Python sources) or
`PYTHON_PAYLOAD_TASK` (a Gradle task printing `PYTHON_PAYLOAD_DIR=`, e.g. `toolchain`'s
`stagePythonBundleIosForXcode`). It is copied with `rsync --delete`, which is why the stdlib does not
live in the same directory. Extension modules in the payload are wrapped as frameworks like the
stdlib's.

## No boundary, in either direction

Python and Kotlin share one binary. Downcalls are direct cinterop calls, and an upcall from
Python into Kotlin is a plain function call. The composition and calling-convention work that
shapes the Android and desktop designs has no counterpart here.
