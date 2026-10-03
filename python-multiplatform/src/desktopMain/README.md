# desktopMain — rules

JVM on macOS, Linux and Windows, reaching CPython through Project Panama.

## Always `invokeExact`, never `invoke`

    // WRONG -- asType adaptation plus boxing of argument and result, on every call
    inline fun PyList_Size(list: Long): Long = PyList_SizeHandle.invoke(list) as Long

    // RIGHT
    inline fun PyList_Size(list: Long): Long = PyList_SizeHandle.invokeExact(list) as Long

This is not a micro-optimisation. Converting the bulk of these wrappers took a real C API call
(`PyList_Size` on `sys.path`) from **1015.95 ns to 2.65 ns** — about 380x. Before that, desktop
was the slowest FFI path in the project by two orders of magnitude, on the machine that is
fastest at everything else.

`invokeExact` requires the call site's static signature to match the handle's type exactly, and
it fails at runtime with `WrongMethodTypeException` rather than at compile time. A green build
proves nothing; the tests are the check.

## Describe pointers as `JAVA_LONG`, not `ADDRESS`

Both are 8 bytes and travel in the same register on a 64-bit ABI. `ADDRESS` makes Panama hand
back a `MemorySegment`, which then has to be unwrapped through per-call argument and return
filters — and those filters were reflective `MethodHandle` invocations building a segment on
every call. `JAVA_LONG` deletes them, and it is what makes the handle type
`(long, long, ...) -> long` so that `invokeExact` is reachable at all.

## Reflection belongs at link time, never per call

The backend resolves `java.lang.foreign` or `jdk.incubator.foreign` reflectively so the code
compiles against any JDK without importing either. That is correct and should stay. What must
never happen is reflection reaching the per-call path — that was the other half of the 1015 ns.
Resolve handles once per symbol at startup; after that a call is a handle invocation and
nothing else.

## String ownership

`withUtf8 { }` allocates, passes the address, and frees in a `finally`. Values owned by CPython
(`Py_GetVersion`, `PyUnicode_AsUTF8`) are read and **not** freed — freeing them is a
use-after-free.

Desktop's string marshalling costs about 200 ns per string, measured at 606.74 ns (10.5%) of a
whole `exec` call. That is roughly 12x cheaper than Android's, because Panama allocates
off-heap without crossing the boundary at all.

## Composition is not used here

Composing binder operations natively was measured and rejected for desktop: it would buy ~10%
and cost macOS/Linux Kotlin/Native targets, a packaging path, and permanent Windows asymmetry.
See ROADMAP §6. Android is different — there the same measurement gives 1.8x.

## Never hand-edit `reachability-metadata.json`

It is generated at build time by `generateDesktopReachabilityMetadata`, from `bindings.kt`,
`ShapeDowncalls.desktop.kt` and `Panama.kt`. There is no copy in `src/desktopMain/resources`; only
the non-derivable half (`reflection`, and any `resources` globs) is checked in, at
`python-multiplatform/native-image/reachability-metadata.base.json`.

Under GraalVM's closed world, a `FunctionDescriptor` that is not declared there does not fail the
build — it fails on the first call, with `MissingForeignRegistrationError`. So every new descriptor
has to reach the metadata by construction:

- A new CPython entry point is a `find("sym", ReturnType, Params...)` line in `bindings.kt`, and
  its shape is picked up automatically. A carrier type not in the generator's token table fails the
  build rather than being guessed at.
- A new **upcall** stub builder in `Panama.kt` must carry a `// @UpcallShape(returnType = "...",
  parameterTypes = [...])` marker directly above it; its descriptor is assembled reflectively and
  cannot be read out of the code. The generator refuses to run without the marker.

`ReachabilityMetadataTest` checks the shipped file from the other direction — against the
`MethodHandle.type()`s the loaded classes actually built — so a parser that stops recognising a
declaration form surfaces as a failing test, not as a binary that dies at a customer.

## The stdlib is staged by the Gradle plugin, and `PYTHONHOME` is set when the JVM starts

`desktopJar` carries `libpython` for four platforms under `lib/<platform>/` and **no standard
library** — verified, not assumed: the published jar's `lib/` tree is 14 entries, all of them
shared libraries. `Py_Initialize()` therefore dies with `Failed to import encodings module` until
`PYTHONHOME` names a prefix that has one.

Supplying that prefix is `python-multiplatform-gradle-plugin`'s `stagePythonHome` task, not a
runtime helper, and the reason is specific to this platform rather than a preference. **A JVM
cannot set an environment variable for itself.** CPython reads `PYTHONHOME` with `getenv(3)`;
`System.getenv` is an immutable snapshot taken at start-up, and mutating it (reflectively or
otherwise) does not touch the native environment CPython reads. Android's `PythonBootstrap` calls
`Os.setenv`; there is no such call here. A runtime helper would have to reach libc `setenv`
through Panama — a different symbol on Windows (`_putenv_s`) — and would then be setting a value
`PythonHomeCheck` could no longer read. Setting it as the child process is launched, which is what
Gradle's `environment(...)` does and what this repo's own `desktopTest` already does, leaves
CPython and `PythonHomeCheck` reading the same value from the same place.

The staged prefix is shared per machine (under the Gradle user home), keyed by version + upstream
release + platform, and stamped after the last extracted byte — so an interrupted extraction is
never mistaken for a finished one. See ROADMAP §15h.

## A packaged app names its prefix in-process, with `Py_SetPythonHome` (SPEC L-9)

The section above is about `PYTHONHOME` **the environment variable**. A packaged application
(`createDistributable`, an installer, `java -jar`) is started by a launcher that takes
`--java-options` and no environment, so it gets the prefix another way:

- The plugin copies the staged prefix's stdlib and `libpython` (no `__pycache__`, headers, `bin/`,
  Tcl/Tk) into Compose Desktop's application resources as `python-multiplatform-home/`, i.e.
  `$APPDIR/resources/python-multiplatform-home` in the app image.
- At run time `PackagedPythonHome` (`jvmMain`) resolves: environment `PYTHONHOME` (wins; nothing
  else happens) → `-Dpython.multiplatform.home` → `<compose.application.resources.dir>/python-multiplatform-home`
  if it exists. `applyPackagedPythonHome` checks the result with `PythonHomeCheck` and hands it to
  `Py_SetPythonHome` before `Py_Initialize()`. That writes CPython's path configuration directly;
  it is not an environment variable, so there is nothing `PythonHomeCheck` could fail to see.
- `manager.loadLibPython` loads `libpython` from that prefix ahead of the classpath copy. The
  classpath copy is extracted into the *working directory* and loaded via `java.library.path`,
  which this repo's tests make work with `-Djava.library.path=.` and a launched app does not have.

`Py_SetPythonHome` is deprecated since 3.11 in favour of `PyConfig.home`, but `PyConfig` is a
struct whose layout the Stable ABI does not promise, and the function is in the Stable ABI, so its
symbol stays exported. It takes `wchar_t *` — UTF-32 here, UTF-16 on Windows — encoded by hand in
`encodeWideString` rather than through `Py_DecodeLocale`, which before pre-initialisation decodes
with the C locale (the ANSI code page on Windows). The buffer is never freed (a few hundred bytes,
once per process): the API asks for static storage.

Not shipped: bytecode. python-build-standalone's `install_only` prefix has no `.pyc`, and the
staged one's `__pycache__` reflects whatever ran against it, so neither is packaged; CPython writes
`__pycache__` into the app image on first use where it can. See
`docs/platforms/desktop-packaged-app.md`.

## The consumer's own Python is a classpath resource, and `python/` collides with our own package

`toolchain`'s `stagePythonBundleDesktop` puts a consumer's payload at the **root of the jar** as
`python/`. `PythonPayload`/`ClasspathPayload` is what reads it: a `file:` classpath entry is used
where it lies, a `jar:` one is extracted to `<tmp>/python-multiplatform-payload/<digest>` under the
same completion-marker discipline `PythonBootstrap.stageStdlib` uses, and the result goes on
`sys.path[0]` from inside `Python3.initialize()`.

**Do not match on the name `python/`.** This library's own top-level Kotlin package *is* `python`,
so `python/multiplatform` and `python/native` class files answer `getResources("python/")` on every
classpath this library is on — the build output directory during a Gradle build, and
`python-multiplatform.jar` itself once published. Name-based discovery therefore puts a directory of
`.class` files at `sys.path[0]` in every consuming application, and extracts the whole library jar
to do it. Observed, not theorised: the first run of `PythonPayloadTest` returned
`build/classes/kotlin/desktop/{main,test}/python`. Discovery asks what is *in* the root instead — at
least one immediate child that is a `.py`/`.pyc`, or a directory with an `__init__` in it, which is
exactly what `ResourceBundler` produces.

Extraction is unavoidable here for the same reason it is on Android: CPython's importer opens
modules with `open(2)` and cannot read a zip entry. Measured on this machine, 203 files: cold
extraction 36 ms, stamp hit 1.4 ms, `sys.path` prepend 0.2 ms.

This is **not** `PYTHONHOME`. That names the standard library prefix, is read by `getenv(3)` before
`Py_Initialize()`, and is set by the Gradle plugin as the process starts because a JVM cannot set an
environment variable for itself. The payload is the application's own code and goes on a list that
does not exist until `Py_Initialize()` has built it.

## CPython is loaded from disk, not from the image

`manager.kt` extracts `libpython` from a classpath resource, but a native image bakes registered
resources into the image heap: that copy was 19.4 MB of a 35.4 MB binary, written straight back out
to a temp file at startup. The library resource is therefore *not* registered, and `manager.kt`
falls back to `$PYTHONHOME/lib/` (`PYTHON_MULTIPLATFORM_LIBPYTHON` overrides an absolute path).
`PYTHONHOME` has to point at a real prefix regardless — `Py_Initialize` needs the matching stdlib —
so nothing is lost, and the binary is 16.7 MB. Loading the library out of the same prefix as the
stdlib also removes the version-skew hazard the jar copy had.
