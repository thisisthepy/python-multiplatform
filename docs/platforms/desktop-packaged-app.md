# Desktop: the standard library in a packaged application

SPEC L-9, issue #60, ROADMAP §15.4.

During development `stagePythonHome` downloads a python-build-standalone prefix once per machine and
the plugin sets `PYTHONHOME` on every `JavaExec` and `Test` task (SPEC L-4). A packaged application
is started by a launcher, not by Gradle, and nothing put `PYTHONHOME` in its environment, so
`Py_Initialize()` aborted with `Failed to import encodings module`. This page records how a packaged
app now gets its prefix, why it was done this way, and how to check it.

## What happens

**Build time** (`python-multiplatform-gradle-plugin`, `PythonBindingsPlugin.configurePackagedPythonHome`).
Every Compose Desktop `prepare*AppResources` task, the `Sync` that `createDistributable`,
`packageDmg`/`packageMsi`/`packageDeb` and their `Release` variants take their resources from,
depends on `stagePythonHome` and copies part of the staged prefix into `python-multiplatform-home/`:

| Host | Copied (relative to the prefix) |
|---|---|
| macOS, Linux | `lib/python<X.Y>/**`, `lib/libpython<X.Y>.*` (`<X.Y>t` for a free-threaded build) |
| Windows | `Lib/**`, `DLLs/**`, `*.dll` |

`**/__pycache__/**`, `include/`, `bin/`, `lib/pkgconfig` and Tcl/Tk are not copied. For 3.14.7
macos-aarch64 that is 1,195 files and 39.6 MB (19.4 MB of it `libpython3.14.dylib`) of a 68.9 MB
prefix, measured on the staged prefix of this machine.

In the app image the directory is `$APPDIR/resources/python-multiplatform-home`; on macOS
`<App>.app/Contents/app/resources/python-multiplatform-home`, on Linux
`<app>/lib/app/resources/python-multiplatform-home`.

**Run time** (`python-multiplatform`). `PackagedPythonHome.resolve()` (`jvmMain`) takes the first of:

1. `PYTHONHOME` in the environment, then nothing else happens; CPython reads it itself.
2. The system property `python.multiplatform.home`, used even if the path does not exist, so that a
   wrong value is reported by name.
3. `<compose.application.resources.dir>/python-multiplatform-home`, only if that directory exists.
   Compose sets `-Dcompose.application.resources.dir=$APPDIR/resources` on every launcher it builds.

`Python3.initialize()` calls `applyPackagedPythonHome()` right after the `PYTHONHOME` pre-flight
check (SPEC L-3). On desktop it checks the resolved prefix with the same `PythonHomeCheck`
(throwing a catchable `IllegalStateException` that names the path and the property it came from)
and passes it to `Py_SetPythonHome` as a `wchar_t` string. Before that, when the bindings load,
`manager.loadLibPython` loads `libpython` from the same prefix instead of extracting the classpath
copy into the working directory.

## Why this design

**Into the app image, not a jar resource extracted at startup.** The consumer's `python/` payload is
a jar resource that `ClasspathPayload` extracts to a temp directory with a digest stamp. The same
mechanism for the stdlib was considered and not taken:

- The stdlib is 1,195 files / 39.6 MB uncompressed. Extracted from a jar it is shipped twice (once
  compressed in the app, once extracted per user), and the first launch pays for writing all of it.
  Put into the app image, it is on disk at install time: no extraction ever, so there is no stamp to
  check on each launch and no cold-start cost at all.
- The payload's cache lives under `java.io.tmpdir`, which operating systems clean up on their own
  schedule. A stamp that survives a partial clean-up which removed part of `encodings/` would be an
  abort at `Py_Initialize` rather than a re-extraction. (Not observed here; a reason not to take the
  risk for the one tree whose absence is fatal.)
- `libpython` has to be a file for `System.load` anyway. Loading it from the classpath copy means
  extracting 19.4 MB into the working directory and finding it through `java.library.path`, this
  repo's tests pass `-Djava.library.path=.`, a launched app has no such flag and may have `/` as its
  working directory. Loading it from the packaged prefix also pairs it with its own stdlib.
- jpackage builds an image for the host it runs on, so only one platform's prefix is ever packaged;
  the cost `PythonHomeStaging`'s KDoc measured against shipping four platforms' stdlibs in the
  *library* jar does not arise.

**Into Compose's resources task, not `nativeDistributions.appResourcesRootDir`.** That property is a
single directory the consumer may already use for their own files; setting it from the plugin would
replace theirs. Adding a `from` to the `Sync` that assembles the resources composes with whatever
the consumer configured, and matching the task by name keeps the plugin free of a compile-time
dependency on the Compose Gradle plugin (as the wasm wiring matches `wasmJsProcessResources`).

**`Py_SetPythonHome`, not an environment variable.** The launcher accepts `--java-options` and no
environment, and a JVM cannot set its own (`desktopMain/README.md`). `Py_SetPythonHome` is part of
the Stable ABI (deprecated since 3.11 in favour of `PyConfig.home`, whose struct layout the Stable
ABI does not promise) and writes CPython's path configuration directly, so `Py_Initialize()` uses it
whenever `PYTHONHOME` is unset.

## Other packagers

Anything that is not Compose's `createDistributable` (plain `jpackage`, Conveyor, an uber jar) gets
no copy from the plugin. Copy the same subset of the staged prefix (`stagePythonHome`'s output,
`~/.gradle/python-multiplatform/python-home/<version>+<release>/<platform>/python`) next to the app
and pass `-Dpython.multiplatform.home=<path>`; jpackage expands `$APPDIR` inside `--java-options`.

## Limits

- No `.pyc` is shipped. CPython compiles what it imports on first use and writes `__pycache__` into
  the app image where it can; where it cannot (a read-only install), it compiles on every launch.
- `pythonBindings { packagePythonHome.set(false) }` turns the copy off. `stagePythonHome.set(false)`
  does not: the packaged prefix must be the one the library was built against regardless of what a
  developer's own `PYTHONHOME` points at, so the task is registered (lazily) either way.
- The library needs `--enable-preview` on JDK 21 (`java.lang.foreign` is a preview API there) and
  `--enable-native-access=ALL-UNNAMED`. A packaged app gets JVM options only from
  `compose.desktop.application { jvmArgs(...) }`; the sample's `build.gradle.kts` sets them for
  `JavaExec` tasks only. Observed on 2026-10-03 (issue #77): the packaged sample on its bundled
  21.0.12 runtime, with neither flag, initialises CPython and runs every step; the only trace is
  the JDK's `WARNING: A restricted method in java.lang.foreign.Linker has been called`. The backend
  reaches `java.lang.foreign` reflectively (`Panama.kt`), so no class file of ours is a preview
  class file. Not a promise for later JDKs, where the restricted-method warning becomes an error.
- The bundled runtime is `jlink`ed and holds only the modules Compose was told about -- the sample's
  `runtime/Contents/Home/release` lists `java.base java.datatransfer java.xml java.prefs
  java.desktop java.logging jdk.crypto.ec`. The library therefore uses nothing outside `java.base`
  (SPEC L-10). It used `sun.misc.Unsafe` (module `jdk.unsupported`) to build the proxy heap type,
  which in the packaged app failed every proxy install with `ExceptionInInitializerError: null` and
  every Kotlin-namespace import after it with `No module named 'org'` (issue #77).

## How to check it

Run on the build host, from the repository root, with no `PYTHONHOME` in the launched app's
environment:

```bash
./gradlew :sample:createDistributable --console=plain > .tmp/dist.log 2>&1; echo "EXIT=$?"

# The prefix is in the image (macOS paths; Linux: sample/build/compose/binaries/main/app/<name>/lib/app/resources)
APP=sample/build/compose/binaries/main/app/org.thisisthepy.python.multiplatform.demo.app
ls "$APP/Contents/app/resources/python-multiplatform-home/lib"          # libpython3.14.dylib  python3.14
test -f "$APP/Contents/app/resources/python-multiplatform-home/lib/python3.14/encodings/__init__.py" && echo STDLIB_OK
find "$APP/Contents/app/resources/python-multiplatform-home" -name __pycache__ | wc -l   # 0

# Launch with PYTHONHOME removed; the sample prints its runtime line and runs asyncio before the window
env -u PYTHONHOME -u PYTHON_MULTIPLATFORM_LIBPYTHON \
    "$APP/Contents/MacOS/org.thisisthepy.python.multiplatform.demo" > .tmp/packaged-run.log 2>&1 &
```

Expected in `.tmp/packaged-run.log`: `runtime : 3.14.7 ...`, `proxies : installed: ...` (not
`ExceptionInInitializerError`), the `Greeter` lines, and the `await` lines (which import `asyncio`),
with no `Fatal Python error` and no `No module named`. The window stays open; close it or kill the process.
Running from `/` as the working directory (`cd / && env -u PYTHONHOME "$OLDPWD/$APP/..."`) also
checks that nothing is extracted into the working directory.
