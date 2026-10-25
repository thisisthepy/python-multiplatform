# python-multiplatform @VERSION@: desktop preview

A desktop-only preview, published as files on this GitHub Release rather than to Maven Central.
Android, iOS and wasm are not part of it.

## What is attached

| File | What it is |
|---|---|
| `python-multiplatform-maven-repo-@VERSION@.zip` | A Maven repository (`maven-repo/org/thisisthepy/...`): the library's desktop artifact (it carries `libpython` for macOS arm64/x86_64, Linux x86_64 and Windows x86_64), the KSP processor with its `typedpython-gate` zip, and the Gradle plugin `org.thisisthepy.python.multiplatform.bindings` |
| `typedpython-0.1.0-py3-none-any.whl` | TypedPython, the compiler from annotated Python to C extension modules |
| `python-multiplatform-ksp-@VERSION@-typedpython-gate.zip` | The TypedPython gate bundle on its own (also inside the repository zip) |
| `python-multiplatform-examples-@VERSION@.zip` | The two examples below |
| `SHA256SUMS` | Checksums of all of the above |

Check the downloads first:

```bash
shasum -a 256 -c SHA256SUMS
```

## Example 1: call Python from a Kotlin desktop app

Unpack the repository and the examples side by side:

```bash
unzip python-multiplatform-maven-repo-@VERSION@.zip
unzip python-multiplatform-examples-@VERSION@.zip
cd examples/kotlin-hello
gradle run -PpmpRepo=../../maven-repo
```

Use Gradle 8.11 or newer with JDK 21 or newer. `-PpmpRepo` points at the unpacked `maven-repo`
directory (the default is `../maven-repo`). The first `run` downloads the CPython build this release
was made against and sets `PYTHONHOME` for it. Expected output:

```text
Python 3.15.0 from Kotlin
{"sum": 45}
Kotlin sees: 42
```

## Example 2: compile Python with TypedPython

TypedPython needs CPython 3.15t (free-threaded) and a C compiler (Xcode command line tools on macOS,
`gcc` on Linux).

```bash
uv venv -p 3.15t .venv
uv pip install --python .venv/bin/python typedpython-0.1.0-py3-none-any.whl
.venv/bin/typedpython demo examples/typedpython/kernels.py 400000
```

`demo` compiles the functions it can prove safe, runs `main()` interpreted and compiled, and checks
that both print the same thing. It lists the compiled functions (`dot`, `collatz_steps`) and the
reason any function stayed interpreted.

## Not covered by this preview

- Android, iOS and wasm artifacts, and publishing to Maven Central.
- The wasm runtime zip (it needs a CPython Emscripten build).
- Linux arm64 and Windows arm64 desktops have no `libpython` in the desktop artifact.
