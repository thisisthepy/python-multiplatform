# Intent

This file says **why python-multiplatform exists, what it is for, and what it deliberately is not.**
It is the boundary for [`SPEC.md`](SPEC.md): the spec may describe only behaviour that serves the
intent below. Anything else needs a decision from the maintainer first.

Sources: the maintainer's statements (quoted, in the original Korean where they were given that way),
the user-authored `python_for_kotlin_binding.mermaid` and `sample/` app, and the design decisions in
`docs/design/`. Lines that are inference rather than a maintainer statement are marked.

---

## 1. Purpose

> "CPython 3.13 을 Kotlin Multiplatform 에 임베딩하여 Kotlin ↔ Python 상호운용"
>, *Embed CPython in Kotlin Multiplatform and provide Kotlin ↔ Python interoperability.*

python-multiplatform is the **language boundary** of the thisisthepy ecosystem. It puts a real
CPython interpreter inside a Kotlin Multiplatform application so that:

1. **Kotlin can use Python** (downcall) through a Kotlin-idiomatic, typed object model in which
   Python objects behave like Kotlin collections and values (`PyList` is a `MutableList`, `PyDict` a
   `MutableMap`, `PyException` a `Throwable`), as sketched in `python_for_kotlin_binding.mermaid`.
2. **Python can use Kotlin** (upcall): Python code imports Kotlin packages, constructs Kotlin
   classes, calls their methods and reads their properties, written the way Python is normally
   written (`Greeter('Kotlin').greet(2)` in `sample/`).
3. **The same code runs on every target** a Kotlin Multiplatform app ships to: desktop JVM, Android,
   iOS, Android native, and the web (wasm, experimental).
4. **The project's own Python is statically typed by default** (TypedPython). The maintainer's goal:

   > "파이썬을 컴파일 언어 및 타입 안정화, 정적 타입 검사, 컴파일 타임 타입검사를 지원할 수 있도록"
   >, *Support Python as a compiled language with type stability, static type checking and
   > compile-time type checking*, so that Python stays usable as AI-friendly languages such as Rust
   > and TypeScript gain ground.

   > "TypedPython을 별도 레포가 아니라 PythonMultiplatform의 기본 동작이 될거야"
   >, *TypedPython is not a separate repository; it will be PythonMultiplatform's default behaviour.*

   The maintainer chose the scope "검사만 기본, 컴파일은 opt-in" (2026-10-02): every user Python source
   is **checked** by default; **compiling** it to native code is opt-in. The compiled code still runs on
   the embedded CPython, as an extension module (`docs/design/typedpython.md`).

   > The maintainer also asked to *explore* GPU kernels written in Python without a separate language
   > ("GPU 관련 최적화까지도 별도 언어 없이 지원할 방안을 모색해보자"). That is an exploration, not yet
   > a commitment; it is recorded in the design note only.

The CPython version moved on from 3.13 (the build pins 3.14.x today and offers opt-in free-threaded 3.14t builds
on desktop only); the intent, embed **CPython itself**, not a re-implementation, is unchanged.

## 2. Principles

### 2.1 Upcalls must survive a closed world

> "업콜은 GraalVM 네이티브 이미지에서도 동작해야 한다, JVM 에서만 통과하는 업콜은 완료가 아니다."
>, *Upcalls must also work in a GraalVM native image. An upcall that passes only on the JVM is not
> complete.*

Kotlin/Native has no useful runtime reflection and GraalVM native image is closed-world. Therefore
the Python → Kotlin direction is driven by a **function table generated at build time**, never by
runtime reflection or name lookup.

### 2.2 Kotlin names are Kotlin names

> "바인더는 어떠한 경우에도 코틀린 쪽 네임스페이스를 다른 이름으로 바꿔서 내보내서는 안된다."
>, *The binder must never, in any case, export a Kotlin namespace under a different name.*

A Kotlin package is importable from Python under its own fully-qualified name and nothing else.
`androidx.compose.ui` in Python means the original Kotlin `androidx.compose.ui`.

### 2.3 Pythonic layers are real Python packages, built on top

> "pythonx 에는 실제 패키지가 존재해야 하고, pythonx 의 코드는 androidx 모듈을 가져와서 pythonic 하게
> 사용 가능하도록 구조를 수정"
>, *pythonx must exist as a real package, and its code imports the androidx modules and makes them
> usable in a Pythonic way.*

`pythonx` lives in the separate **pythonx-compose** repository. This repository supplies what such
a package needs, importable Kotlin packages, Kotlin extension functions reachable as Python methods,
type stubs, and must not stand in for it or prevent its files from loading.

### 2.4 Kotlin idioms appear as Python idioms

A Kotlin **extension function appears in Python as a method on the receiver's proxy**:
`Modifier.padding(16.dp)` in Kotlin is a method call on a `Modifier` proxy in Python, so chains such as
`Modifier.padding(16).size(24)` compose (`docs/design/kotlin-extensions-in-python.md`).

### 2.5 The cost of crossing is measured, not assumed

Every boundary mechanism is accompanied by measurement tests (per-call FFI cost, pointer boxing,
reference-count round trips, string marshalling, collection conversion). Overhead is a design input.

### 2.6 One table, every platform

Behind the per-platform entry point, every platform uses the same generated function table. There is
no per-platform Python → Kotlin binding code to maintain.

> Inferred, confirm with the maintainer: this "one mechanism everywhere" property is a goal, not
> only an implementation detail. It is stated as a decision in `PROJECT.md` and `docs/design/upcall.md`.

## 3. What it deliberately is NOT

| Not this | Why |
|---|---|
| A binder that renames namespaces (`androidx` → `pythonx`, or any other) | §2.2 |
| A replacement for the `pythonx` packages | §2.3, they are real packages in their own repository |
| Runtime-reflection or name-lookup based binding | §2.1, impossible under Kotlin/Native and native image |
| A hand-written wrapper per Kotlin function or class | Generic adaptation plus generated tables; per-declaration wrappers were tried in 2024 and rotted (`docs/archive/pythonx-adapter-design.md`) |
| A Python re-implementation, transpiler, or JVM-hosted Python | It embeds CPython itself, so C extensions keep working. TypedPython (§1.4) does not change this: checked code is ordinary Python, and compiled code is a CPython extension module, not a translation to another runtime |
| A build tool, dependency resolver or packager | That is pypackpack (the work) and toolchain (the Gradle vocabulary) |
| A sub-interpreter parallelism model | Rejected: extensions that do not declare multi-interpreter support fail to import; parallelism comes from free-threaded CPython |
| A vendored interpreter | The build downloads and verifies CPython per platform |
| Stubbed-out uniformity (`JClass` / `KClass` / `ObjcClass` faked where the platform lacks them) | Each works only where the platform really has it |

> Inferred, confirm with the maintainer: the build-tool and packager row is taken from
> `docs/design/ecosystem.md` ("ppp owns the work, toolchain owns the Gradle vocabulary, this
> repository owns the language boundary"), which states the intended division of labour rather than
> quoting the maintainer.

## 4. Who it is for

- Kotlin Multiplatform developers who want to use Python libraries from Kotlin on every target.
- Python developers who want to drive Kotlin libraries, Compose UI in particular, from Python,
  through pythonx-compose.
- The sibling thisisthepy projects (pythonx-compose, toolchain, pypackpack, torchnative, Gemstone)
  that build on this boundary.

> Inferred, confirm with the maintainer: the audience list is derived from the sample app, the
> README and `docs/design/ecosystem.md`; it was not stated as a list.
