# 아키텍처

전체 그림과 방향별 상세는 [`PROJECT.md`](../../PROJECT.md) 에서 시작한다. 이 문서는 소스셋 계층,
타입 래퍼 구조, 계층 규칙, 테스트 계층을 다룬다. 의도·사양의 기준은 [`INTENT.md`](../INTENT.md),
[`SPEC.md`](../SPEC.md) 이고, 이 문서는 설계 기록이다.

## 소스셋 계층

`python-multiplatform/build.gradle.kts` 의 `sourceSets` 블록에서 **수동으로** 구성한다. 기본 계층
템플릿은 적용되지 않는다 (빌드 로그에 그 경고가 뜨는 이유다).

```
commonMain
 ├── jvmMain            중간 소스셋
 │    ├── androidMain   ART
 │    └── desktopMain   HotSpot
 ├── nativeMain
 │    ├── iosMain
 │    └── artMain       androidNativeArm64 / androidNativeX64
 └── wasmJsMain         wasm32-emscripten (ROADMAP §10)
```

`wasmJsMain` 은 **중간 소스셋 없이 `commonMain` 바로 아래에 둔다.** 공유할 상대가 없고(JNI·Panama·
cinterop 어느 쪽과도 겹치지 않는다), 바로 아래에 적힌 `expect inline fun` 제약을 피하기 위해서이기도
하다. `EmbedAPI.kt` 의 `expect inline fun`(현재 131개)이 그대로 `actual inline` 이 된다.

`jvmMain` 은 정식 중간 소스셋이므로 **여기에 `expect` 를 두고 두 리프에서 `actual` 을 제공하는 것이
가능하다.** 실험으로 확인했다.

단, **`expect inline fun` 과 조합하면 Kotlin 2.0.20 에서 `Internal error in file lowering` 컴파일러
크래시가 난다.** 마이그레이션 대상 함수에서 `inline` 을 떼면 해결된다. `EmbedAPI.kt` 의 `inline` 중
다수는 컴파일러가 이미 "인라인 이득 없음" 경고를 내고 있어, 떼는 데 실질적 손해가 없다. (크래시는
Kotlin 2.0.20 에서 관찰됐다. 빌드는 이후 Kotlin 2.4.20-Beta2 로 올라갔고(`gradle/libs.versions.toml`),
이 크래시를 그 버전에서 다시 확인하지는 않았다, 그래서 AGENTS.md §16 은 여전히 JVM 계열 통합 전에
확인하라고 한다.)

## FFI 계층

```
commonMain/kotlin/python/native/ffi/EmbedAPI.kt      expect 약 314개 (그중 `inline` 131개)
 ├── nativeMain/.../EmbedAPI.native.kt               cinterop  (iOS, androidNative)
 ├── androidMain/.../EmbedAPI.android.kt + bindings.kt   JNI
 ├── desktopMain/.../EmbedAPI.desktop.kt + bindings.kt + Panama.kt   Panama
 └── wasmJsMain/.../EmbedAPI.wasmJs.kt + bindings.kt + Wasm.kt   @WasmImport
```

`wasmJsMain` 의 `bindings.kt` 는 `object` 가 아니라 **`python.native.ffi.bindings` 패키지**다.
`@WasmImport` 는 최상위 `external fun` 에만 붙기 때문이다. 호출부 표기(`python.native.ffi.bindings.X`)는
desktop·native 와 동일하다.

포인터는 `NativePointer` (value class, `address: Any`) 로 통일한다. 플랫폼별 실체는 `Long`(JVM),
`CPointer<*>`(Native), `Int`(wasm32) 다. 데스크톱 FFM 은 포인터를 `ADDRESS` 가 아니라 `JAVA_LONG` 으로
넘기고 `invokeExact` 만 쓴다 (`desktopMain/README.md`).

`Any` 래핑 때문에 변환마다 박싱이 일어난다. `@HighOverheadNativeCall` 애노테이션이 비싼 변환 지점을
표시한다.

### CPython C 매크로는 바인딩할 수 없다

`PyLong_Check` 계열은 헤더의 `#define` 이라 심볼로 export 되지 않는다. `nm` 으로 확인했다. 그래서
`ffi/PyTypeChecks.kt` 가 common Kotlin 으로 11종을 구현한다, `PyObject_IsInstance` 와 캐시된 타입
객체를 쓰며, `expect`/`actual` 분기가 없으므로 네 플랫폼 동작이 구조적으로 동일하다.

## 타입 래퍼 계층

`commonMain/kotlin/python/multiplatform/ffi/` 아래. 설계 밑그림은 저장소 루트의
`python_for_kotlin_binding.mermaid` 이지만 **확정 스펙이 아니다.**

```
PyObject                     모든 래퍼의 기반. NativePointer 보유, PyAutoCloseable 상속
 ├── PyType                  인스턴스 캐시 보유
 ├── PyException             CPython 에러 인디케이터 ↔ Kotlin throwable (exceptions/)
 ├── 기본 타입               PyInt PyFloat PyBool PyString PyBytes PyNone PyComplex   (types/basic)
 ├── 컬렉션                  PyList PyTuple PyDict PySet PyFrozenSet                  (types/collections)
 ├── PyIterator / PyIterable, 제너레이터·코루틴                                        (types/iteration)
 ├── PyModule / PyPackage                                                             (types/modules)
 └── 호출 가능·코드·디스크립터·유틸리티 (PyFunction, PyMethod, PyCode, PyProperty, PyRange, PyWeakRef ...)
conversion/                  Context, ConversionStrategy, PyProxy, PyValue
```

### mermaid 와 어긋난 곳

Kotlin 제약 때문에 다이어그램대로 갈 수 없던 지점들:

- **`PyBool` 은 `PyInt` 를 상속하지 않는다.** 같은 제네릭 인터페이스(`PyProxy<T>`)를 서로 다른 타입
  인자로 두 번 구현할 수 없다.
- **`invoke` 오버로드 다발이 vararg 하나로 합쳐졌다.** Kotlin vararg 가 이미 그 역할을 한다.
- **`RAW` 와 `UNMANAGED` 를 분리했다.** mermaid 는 `naiveConvert` 하나가 둘을 담당하지만 그러면 두
  enum 값이 행동상 구분되지 않는다. 원저자가 `Strategy.kt` 에 남긴 설계 노트가 근거다.
- `toList()`/`toMap()` 은 `toNativeList()`/`toNativeMap()` 으로 이름을 바꿨다. Kotlin 표준 확장을
  가린다.

### JVM 시그니처 충돌 주의

`PyObject` 의 타입 프로퍼티는 `Type` (대문자 T, `val Type: PyType`) 이다. 소문자 `type` 프로퍼티와
별도의 `getType()` 메서드를 함께 두면 JVM 에서 접근자 `getType()` 이 **같은 시그니처로 충돌**하고, 모든 서브클래스가 Android·Desktop 에서
컴파일에 실패한다. Kotlin/Native 에는 그 규칙이 없어 JVM 타깃을 실제로 빌드하기 전까지 드러나지
않았다.

## 검증 수단

명령 목록과 어떤 소스셋을 덮는지는 AGENTS.md §15 가 기준이다 (`compileKotlinAndroidNativeArm64`,
`compileKotlinIosSimulatorArm64`, `compileKotlinDesktop`, 그리고 `ksp-fixtures` 세 모듈을 각각 따로).
테스트는 컴파일 검증만이 아니다, 데스크톱(`desktopTest`), iOS 시뮬레이터, 안드로이드 기기, wasm(Node)
에서 실제 CPython 을 구동하는 스위트가 있다 (SPEC §0 의 "Test path" 열).

`Python.framework` 에는 표준 라이브러리가 없어 `Py_Initialize()` 가
`Failed to import encodings module` 로 프로세스를 죽인다. 빌드가 배포 아카이브에서 stdlib 를 풀어
`PYTHONHOME` 을 잡아준다 (`extractIosSimulatorStdlib`). simctl 은 `SIMCTL_CHILD_` 접두사가 붙은
환경변수만 자식 프로세스로 전달하므로 그 이름으로도 함께 내보낸다.

## Layering and what may reference what

Four layers, and the arrows only point downward. This is a hard rule, not a preference.

```
python.multiplatform.ffi        PyObject, PyType, collections, conversion  -- the object model
        |  may call
        v
python.native.ffi               EmbedAPI: expect/actual over the C API
        |  may call (platform implementations only)
        v
python.native.ffi.bindings      JNI externals (Android) / Panama handles (desktop)
        |
        v
libpython3.14
```

**The object model must never reference `bindings`.** `PyObject` and friends call the EmbedAPI
functions in `python.native.ffi` and nothing below them. `bindings` is an implementation
detail that only `EmbedAPI.<platform>.kt` is allowed to know about, it does not even exist on
the native targets, which reach CPython through cinterop instead.

Concretely, this is correct:

```kotlin
// commonMain/ffi/PyObject.kt
import python.native.ffi.PyObject_GetAttrString

fun getAttr(name: String): PyObject { ... PyObject_GetAttrString(pointer, name) ... }
```

and this is not, even though it compiles on the JVM targets:

```kotlin
// WRONG -- the object model reaching into a platform-internal surface
fun getAttr(name: String) = PyObject(bindings.asmGetAttr(pointer.raw(), name))
```

### Consequence for composed operations

A composed call, one that does a whole binder operation natively and crosses the boundary
once, is still a platform implementation difference, so it belongs in the FFI layer, not in
the object model:

```kotlin
// commonMain/native/ffi/EmbedAPI.kt
expect fun PyObject_GetAttrComposed(o: NativePointer, name: String): NativePointer?

// androidMain -- composed: one crossing
actual fun PyObject_GetAttrComposed(o: NativePointer, name: String) =
    bindings.asmGetAttr(o.toPlatformPointer(), name).toNativePointer()

// nativeMain -- already in-process, nothing to compose
actual fun PyObject_GetAttrComposed(o: NativePointer, name: String) =
    PyObject_GetAttrString(o, name)
```

The object model then stays common and stays within its layer. Composition does not require
reopening `PyObject` as `expect`/`actual`.

## What the PyAutoCloseable split actually changed

Commit `0fae961a` (2025-12-20) has been described here as de-duplicating `PyObject`, and as
extracting lifetime management out of it. Both are wrong.

There was no duplication: every `actual` was a placeholder. The whole desktop implementation
was one line, the Android one carried a single commented `//actual external fun incRef()`, and
the real logic sat in a **comment block** in `commonMain` above the note *"Temporary commented
due to the error: Expected declaration cannot have a body. TODO: Move this to the actual
implementation."*

And lifetime management was already extracted. `PyObjectAutoCloseable` in `jvmMain` held the
`Cleaner`; `PyObject` never owned it.

What the commit actually did:

**1. Hoisted the base class from `jvmMain` into `commonMain` as `expect`/`actual`.**

```kotlin
// before -- a plain class in jvmMain, shared by android and desktop
abstract class PyObjectAutoCloseable(open val pointer: NativePointer, borrowed: Boolean): AutoCloseable

// after -- an expect in commonMain, with a leaf actual per platform
expect abstract class PyAutoCloseable(pointer: NativePointer) {
    abstract fun clean()
}
```

> 이 스니펫은 그 커밋 시점의 모양이다. 이후 `PyAutoCloseable` 은 `expect` 가 아닌 **commonMain 의
> 일반 추상 클래스**가 되었고(`python/multiplatform/ref/PyAutoCloseable.kt`), 플랫폼 차이는
> `expect interface PlatformCleaner` + `expect fun registerCleaner(pointer, closeAction)` 로 옮겨졌다.
> 아래 표가 현재의 플랫폼별 메커니즘이다.

**2. Gave it an abstract `clean()`, so the cleanup action comes from the subclass.** Before,
the base held a hardcoded (and commented-out) `//PyDecRef(this.pointer)`; after, it calls
`clean()` and `PyObject` supplies it.

**3. Split the single `jvmMain` implementation into per-platform leaves**, and this fixed a
latent crash. `jvmMain`'s version called `Cleaner.create()` unconditionally, and Android
inherited it while minSdk was 24. `java.lang.ref.Cleaner` does not exist below API 33, so
every `PyObject` construction on API 24-32 would have thrown `NoClassDefFoundError`. The new
`PyAutoCloseable.android.kt` branches on `SDK_INT` and falls back to `PhantomReference` +
`ReferenceQueue`.

**4. As a consequence, `PyObject` stopped being `expect`/`actual`.** Once the base class was
expressible in `commonMain`, `PyObject` could inherit it there and finally have real method
bodies, which is how the commented logic became code.

| platform | lifetime mechanism after the split |
|---|---|
| Kotlin/Native (iOS, androidNative) | `kotlin.native.ref.createCleaner` (`close()` 도 가능, 한 번만 실행) |
| Android API 33+ | `java.lang.ref.Cleaner` |
| Android API 26-32 (minSdk 26) | `PhantomReference` + `ReferenceQueue` + daemon thread |
| Desktop | `java.lang.ref.Cleaner` |
| wasmJs | JS 쪽 약한 참조 기반 레지스트리 (`PyAutoCloseable.wasmJs.kt`) |

(minSdk 가 24 였던 시절의 서술이다. 현재 minSdk 는 26 이고 API 26-32 가 `PhantomReference` 경로를 쓴다.)

The split is sound and stays. What was lost was incidental: `PyObject.android.kt` had been the
only place an `actual external fun` could go, and that was the hook for routing an operation
through a single composed JNI call. Measurement later put a number on it, 5.5x on one
`getAttr`, 11x on a 1000-element list conversion. Recovering it does not mean reverting the
commit; it means expressing composed operations in the FFI layer, as above. (The `PyObject_GetAttrComposed`
snippet is illustrative, not a function in the tree. For string-carrying calls, interning later beat
composition and replaced it, `marshalling-design.md`; composition remains only where crossing count
scales with N.)

## Test layering: low-level and assembled

Tests are split by which layer they exercise, and both layers must be covered on every
platform. This is not organisational tidiness, the gap between them hid a defect for the whole
of the object model's life.

**Low-level** tests call EmbedAPI functions one at a time: does this function return what its C
counterpart returns, does it report failure the documented way, does it leave the reference
count where it found it. `commonTest/.../python/native/ffi/EmbedApiLowLevelTest.kt` and, on
Android, everything under `androidInstrumentedTest` that reaches through `bindings`.

**Assembled** tests call `Python3` and the object model as an application would.
`commonTest/.../python/multiplatform/ffi/**` and
`androidInstrumentedTest/.../assembled/AssembledApiTest.kt`.

### Why both, everywhere

At the time, `commonTest` was entirely assembled and ran only on iOS and desktop. Android's
instrumented tests were entirely low-level (today `commonTest` also runs on Android and wasm; SPEC §0). So no test anywhere called the real object model on a device,
and it turned out `Python3.exec` crashes the process on its first call there, because several
functions it reaches still take a Kotlin `String` straight across JNI.

Fourteen green Android tests said nothing about that. A test that does not walk the path
callers walk proves only that some other path works.

The Android assembled tests were `@Ignore`d until ROADMAP §2 landed, because they crashed the
instrumentation process rather than failing, and a crashed runner takes every other test with
it. They were the acceptance check for that work; §2 is closed (213 tests per emulator, zero
failures, per ROADMAP §2) and `AssembledApiTest.kt` no longer carries an `@Ignore` on a test.

### Two things to know when adding tests here

**The interpreter is process-wide; no single test owns its lifecycle.** `DesktopPythonTest`
used to call `Py_Initialize()` and `Py_Finalize()` itself. Every class scheduled after it then
died inside `PyGILState_Ensure` → `new_threadstate`, reporting *zero* tests because the JVM
went down before any XML was written. It stayed invisible while nothing happened to run
afterwards. Go through `PythonTestFixture`, which initialises once per process.

**Read test counts from a cleaned results directory.** Gradle leaves XML from previous runs in
place, so a crashed run can report the previous run's numbers. A suite that "passes 111 tests"
while crashing is what stale XML looks like, delete `build/test-results/<target>/` when a
count needs to be trusted.
