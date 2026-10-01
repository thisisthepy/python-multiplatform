# 다운콜 설계 — Kotlin → Python

Kotlin 코드가 CPython Stable ABI 함수를 호출하는 방향. 반대 방향은
[`upcall-design.md`](upcall-design.md).

## 시그니처 shape 조사 결과

`EmbedAPI.kt` 의 약 330개 `expect` 선언을 전수 분석한 결과다.

| 항목 | 결과 |
|---|---|
| 전체 함수 | 330 |
| **구분되는 ABI shape** | **14** |
| 최대 인자 수 | 6 (`PyErr_WarnExplicit`) |
| 부동소수점 관련 함수 | 6개뿐 |
| 정수/부동소수 **혼합** 시그니처 | **0** |
| 가변인자 | **0** |
| 구조체 값 전달 | **0** |

부동소수점을 쓰는 함수는 `PyLong_FromDouble`, `PyLong_AsDouble`, `PyFloat_FromDouble`,
`PyFloat_AsDouble`, `PyFloat_GetMax`, `PyFloat_GetMin` 여섯 개가 전부다.

shape 를 가르는 기준은 arm64(AAPCS64)와 x86-64(SysV) 모두에서 같다 — **정수·포인터가 한 레지스터
뱅크, 부동소수점이 다른 뱅크**를 쓰므로, (정수형 인자 수, 부동형 인자 수, 반환 종류) 가 shape 다.
혼합 시그니처가 0개라 트램폴린의 C 프로토타입이 단순해진다.

**결론: 14개 트램폴린이면 CPython Stable ABI 전체를 덮는다.**

## 플랫폼별 메커니즘

### iOS / androidNative — cinterop

Kotlin/Native 가 헤더를 읽어 직접 호출 코드를 낸다. **오버헤드가 없고 손댈 것도 없다.** 이미 최적이다.

### Desktop — Panama

`desktopMain/.../Panama.kt` 가 `java.lang.foreign`(JDK 22+, 19~21 은 preview)과
`jdk.incubator.foreign`(JDK 16~18)을 **둘 다 리플렉션으로만** 접근한다. 어느 API 도 직접 import 하지
않으므로 **어떤 JDK 에서도 컴파일**되고, 런타임에 백엔드를 고른다.

리플렉션은 초기화 때 한 번만 돌고 이후는 캐시된 `MethodHandle` 을 쓴다.

**알려진 성능 문제**: 호출부 305곳이 `MethodHandle.invoke(...) as Long` 형태다. `invoke` 는 호출
지점에서 `asType` 변환을 하고, Kotlin 에서는 반환 타입이 `Any!` 로 보이므로 **호출마다 박싱/언박싱이
발생**한다. Panama 의 핵심 장점(JIT 인라인 가능, 할당 없는 다운콜)이 사라진다. `invokeExact` 는 호출
지점의 정적 시그니처가 핸들 타입과 정확히 일치해야 하는데, shape 14종으로 고정하면 그 조건을 맞추기
훨씬 쉬워진다.

### Android(ART) — shape 트램폴린 (미구현)

ART 는 런타임에 임의 시그니처의 네이티브 호출을 만들 수 없다. 그래서 shape 마다 트램폴린을 **미리
컴파일**해두고, 대상 함수 포인터를 첫 인자로 받는다:

```kotlin
// Kotlin/Native 로 컴파일해 .so 에 넣는다 (NDK 불필요)
@CName("...downcallJ_J")
fun downcallJ_J(fn: Long, a0: Long): Long =
    fn.toCPointer<CFunction<(Long) -> Long>>()!!.invoke(a0)
```

주소는 런타임에 무엇이든 될 수 있고, **고정되는 것은 시그니처 형태뿐**이다.

도달 방법은 두 단계로 나눈다:

| 단계 | 경로 | 비용 |
|---|---|---|
| 1 | 일반 JNI → 트램폴린 → 간접 호출 | JNI 전환 비용 포함 |
| 2 | ART 엔트리 포인트 패치 + `@CriticalNative` → 트램폴린 | 간접 분기 1회만 |

1단계는 에뮬레이터 없이 컴파일 검증이 되고, 2단계는 같은 트램폴린 코드를 그대로 쓰면서 도달 방법만
바꾼다. `@CriticalNative` 는 `JNIEnv` 도 `jclass` 도 GC 스레드 상태 전환도 없어 ART 에서 가장 빠른
네이티브 규약이다.

**LLVM JIT 은 필요 없다 — 다만 근거는 아래와 같다.**

ART 에는 FFM 을 위한 VM 지원이 없다. HotSpot 은 `downcallHandle` 호출 시 VM 이 스텁을 기계어로
생성해 주지만, ART 는 해 주지 않는다. 그래서 임의의 `FunctionDescriptor` 에 대해 인자를 레지스터에
배치하고 대상을 호출하는 기계어를 **누군가는 만들어야 한다.** PanamaPort 는 그것을 런타임에
`libLLVM.so` 로 만든다 — `_AndroidLinkerImpl.generateNativeDowncallStub`(292행)이 다운콜 경로에서
**조건 없이** 호출되며(938행), 업콜도 `generateNativeUpcallStub`(954행)으로 같다.

주목할 점은 그 생성 스텁도 **대상 함수 포인터를 첫 인자로 받는다**는 것이다:

```java
// _AndroidLinkerImpl.java:935
stub_descriptor = stub_descriptor.insertArgumentLayouts(0, WORD);  // leading function pointer
```

우리 `downcallII_I(fn, a0, a1)` 과 같은 구조다. 차이는 **생성 시점**뿐이다:

| | PanamaPort | 이 프로젝트 |
|---|---|---|
| 스텁 생성 | 런타임 (LLVM JIT) | **빌드 타임 (미리 컴파일)** |
| 커버 범위 | 임의 시그니처 | shape 14종 |
| `libLLVM.so` 의존 | 필요 | 불필요 |

즉 LLVM 이 필요 없는 이유는 "참조 구현도 안 쓰기 때문"이 아니라 **"시그니처를 14종으로 열거해 빌드
타임에 만들어 두기 때문"**이다.

(이전 판에서 `BulkLinker.requireNativeStub` 이 `false` 를 반환하므로 참조 구현도 스텁 없이 직접
패치한다고 서술했으나 이는 오독이었다. `BulkLinker` 는 `Unsafe` 모듈에 있는 PanamaPort 자체
부트스트랩 수단이며 — LLVM API 같은 내부 네이티브 함수를 바인딩한다 — `Core` 의 Linker 경로에서는
쓰이지 않는다.)

## JVM 통합 — Desktop + Android 를 한 벌로

현재 JVM 쪽은 330개 `actual` 이 **두 벌** 있다. 여기에 플랫폼별 `bindings` 선언까지 더하면 손으로
유지하는 선언이 1,300개에 가깝다. 바인딩 하나를 추가하려면 네 계층을 맞춰야 한다.

shape 를 도입하면 플랫폼별 표면이 **14개로 줄고** 330개 `actual` 은 `jvmMain` 에 한 벌만 남는다:

```
commonMain   expect fun PyList_Size(list: NativePointer): Long

jvmMain      actual fun PyList_Size(list) = downcallJ_J(sym.PyList_Size, list.toRawValue())
             internal expect fun downcallJ_J(fn: Long, a0: Long): Long     ← shape 14개
 ├── desktopMain  internal actual → 캐시된 Panama 핸들
 └── androidMain  internal actual → shape 트램폴린
```

**인터페이스나 추상 클래스를 쓰지 않는다.** `expect`/`actual` 이 같은 일을 하는 언어 기능이고,
인터페이스를 두면 호출마다 가상 디스패치가 붙는다. `expect`/`actual` 은 컴파일 타임에 확정된다.

검증된 절차:

1. 대상 함수의 commonMain `expect` 에서 `inline` 제거
2. `androidMain`/`desktopMain` 의 `actual` 삭제
3. `jvmMain` 에 `actual` 하나 + shape 함수 호출
4. shape 함수를 `internal expect` 로 두고 각 플랫폼이 `internal actual` 제공

함수 하나(`PyList_Size`)로 4개 타깃 전부 컴파일되는 것까지 실험으로 확인했다.

## GraalVM Native Image

Native Image 는 **FFM 다운콜을 빌드 타임에 등록**해야 한다. `reflect-config.json` 으로는 안 되고,
커스텀 `Feature` 의 `duringSetup` 단계에서 `RuntimeForeignAccess.registerForDowncall(descriptor, options)`
를 호출해야 하며, `-H:+ForeignAPISupport` 로 활성화하는 **실험적** 기능이다.

여기서도 shape 14종이 유리하다 — `Feature` 하나에서 `registerForDowncall` 을 14번 부르면 끝난다.
임의 시그니처를 런타임에 만드는 구조였다면 애초에 불가능하다.

우선순위는 낮다. 상세는 [`android-ffm-design.md`](../platforms/android-ffm-design.md).

## 스레드 상태

다운콜은 전부 GIL(또는 free-threaded 빌드의 스레드 상태)을 요구한다.
[`threading-and-abi.md`](threading-and-abi.md) 를 본다.

---

## 개정: Android 는 shape 트램폴린만으로 풀리지 않는다

위의 shape 설계는 **Desktop 에는 유효하지만 Android 에는 그대로 적용되지 않는다.** 설계 논의에서
드러난 사실들을 기록한다.

### 확인된 버그: 현재 Android JNI 배선이 어긋나 있다

`@CName` 이 만드는 C 함수는 선언한 Kotlin 인자만 받는다:

```
Java_python_native_ffi_bindings_PyList_1Size(jlong list)
```

그런데 `androidMain/bindings.kt` 는 이를 **일반 JNI 메서드**로 선언한다. 일반 JNI 메서드를 ART 는
이렇게 호출한다:

```
Java_..._PyList_1Size(JNIEnv* env, jobject thiz, jlong list)
```

**인자가 두 칸 밀린다.** `list` 자리에 `JNIEnv*` 가 들어간다. 확인 결과 `@CName` 익스포트 중
`JNIEnv` 를 받는 것은 0개이고, `@CriticalNative`/`@FastNative` 도 어디에도 없다.

즉 **Android JVM 경로는 컴파일만 되고 런타임에 동작하지 않는다.** 실행된 적이 없어 드러나지 않았다.

### ART 의 세 가지 호출 규약

| | 일반 JNI | `@FastNative` | `@CriticalNative` |
|---|---|---|---|
| 전환 오버헤드 | 115 ns | 35 ns | 25 ns |
| C 시그니처 | `(JNIEnv*, jobject, args…)` | 동일 | **`(args…)` 만** |
| 객체 인자·반환 | 가능 | 가능 | **원시 타입만** |
| 정적/인스턴스 | 둘 다 | 둘 다 | **정적만** |
| JVM 으로 콜백 | 가능 | 가능 | **불가** |
| 실행 중 GC | 허용 | **차단** | **차단** |

shape 트램폴린은 정적이고 `Long`/`Double` 만 주고받으므로 `@CriticalNative` 에 정확히 맞는다. 그리고
우리 `@CName` 익스포트가 `JNIEnv` 를 받지 않는다는 사실이 곧 `@CriticalNative` 규약과 일치하므로,
**애노테이션을 붙이는 것이 성능 개선인 동시에 위 버그의 수정**이다.

`@CriticalNative` 는 API 34 부터 공식 SDK 에 포함된다. minSdk 26 구간에서는 `RegisterNatives` 로
등록해야 한다 — `RegisterNatives` 는 호출 규약이 아니라 바인딩 수단이므로 둘은 대안 관계가 아니다.

### critical 계열은 업콜과 공존할 수 없다

`Linker.Option.critical()` 과 `@CriticalNative` 는 제약이 같다. 짧고 블로킹하지 않아야 하며,
**콜백으로 JVM 에 되돌아오면 안 된다.** 위반은 정의되지 않은 동작이고 런타임이 보호해 주지 않는다.

이 프로젝트는 **업콜이 핵심 요구사항**이므로 이 제약이 결정적이다:

| 함수 | critical |
|---|---|
| `PyList_Size`, `Py_IncRef`, `PyLong_FromLongLong` | 적용 가능 |
| `PyObject_Call`, `PyRun_String`, `PyImport_ImportModule` | **불가** — Python 코드를 실행하며 Kotlin 으로 되돌아올 수 있다 |

가장 뜨거운 호출들이 정확히 critical 을 못 쓰는 쪽이다. 그리고 **shape 는 이를 구분하지 못한다** —
`downcallII_I` 하나가 `PyList_GetItem`(가능)과 `PyObject_Call`(불가)을 함께 덮는다. 시그니처가
같아도 성격이 반대다.

### Desktop Panama 와 Android Panama 는 성능이 다르다

Desktop 과 Android 를 같은 방식으로 통일하려고 Android 에 FFM 을 얹어도, **API 형태만 통일되고 성능
특성은 통일되지 않는다.**

| | Desktop (HotSpot) | Android (PanamaPort 류) |
|---|---|---|
| 스텁 생성 | **VM 이 기계어로 생성** | ART 메서드 엔트리 포인트를 패치 |
| JIT 가시성 | `MethodHandle` 을 C2 가 인라인·특수화 | ART JIT 는 그만큼 못 함 |
| 중간 계층 | 없음 | `MethodHandle` + 자체 변환 계층 |
| 인자 마샬링 | VM 생성 코드 안 | Java/Kotlin 코드 |
| 전환 생략 | `Linker.Option.critical()` | `@CriticalNative` (대응됨) |

**전환 비용 자체는 대응되지만, 그 위 계층에서 갈린다.** Desktop 의 Panama 는 런타임의 일부이고,
Android 의 Panama 는 런타임인 척하는 라이브러리다. 같은 바닥 위에 계층이 더 있고 JIT 가 약하므로
Android 쪽이 더 빠를 수는 없다. 다만 **얼마나 다른지는 측정된 바 없다.**

우리 desktop 구현은 현재 `Linker.Option.critical()` 을 쓰지 않는다 —
`Panama.kt` 가 `downcallHandle(..., emptyOptions)` 로 기본 경로를 탄다.

### 미결정: 조립 단위의 범위

경계 통과가 비싸고 뜨거운 호출에는 critical 도 못 쓰므로, **통과 횟수 자체를 줄이는 것**이 확실한
접근이다. 원래 설계 의도가 이것이었다 — `bindings` 의 저수준 함수들을 `Python3.kt` 같은 **바인더
레벨 연산으로 조립**해 `artMain` 에서 내보내고, `androidMain` 은 그 굵은 단위를 호출한다.
`artMain/JniExport.kt` 의 `initialize`/`finalize`/`internalIsInitialized` 가 그 형태로 남아 있다.

조립 단위는 길고 업콜 가능성이 있으므로 일반 JNI 를 쓴다. 통과가 적으면 개당 115 ns 도 감당된다.
반대로 shape 트램폴린은 짧고 콜백이 없으므로 `@CriticalNative` 를 쓴다. **둘은 대립이 아니라 층이
다르다** — 조립이 통과 횟수를 줄이고, critical 이 남은 통과를 싸게 한다.

정해야 할 것은 **조립 단위를 어느 층위로 잡는가**이다. 지금 객체 모델(`PyObject`, `PyType`, 컬렉션)이
`commonMain` 에 있고 그 안에서 원시 `expect` 를 호출하므로, Android 에서 조립하려면 그 연산 자체가
플랫폼별로 갈라져야 한다. 후보:

1. 바인더 API 를 `expect`/`actual` 로 올리고, Android 는 `artMain` 조립 함수를 호출하는 얇은
   포워더가 된다 — 로직은 Kotlin/Native 에 한 벌
2. `commonMain` 에 로직을 두되 Android 만 별도 경로 — 중복 발생
3. 조립을 `Python3` 수준으로 한정하고 `PyObject`/컬렉션은 잘게 둔다 — 절충

이 결정 전에는 `EmbedAPI.jvm.kt` 통합을 진행할 수 없다. Android 가 조립 단위를 쓰면 잘게 쪼갠 330개
`expect` 를 구현하지 않게 되기 때문이다.

---

## Measured: what the JNI calling convention actually costs (2026-08-11)

Every overhead figure in this document before this section was quoted from external
benchmarks or inferred from source. This section is the first measurement taken in this
project, and it contradicts the assumption the Android design was built on.

### Method

`echo0`, `echoFast` and `echoNormal` are the same one-line C body — `jlong f(jlong x)
{ return x; }` — registered three times under the three conventions
(`artMain/cinterop/jni_onload.def`). The callee work is identical and negligible, so the
difference between them is the transition and nothing else. A pure-Kotlin identity call is
measured alongside as the floor and subtracted.

All variants are warmed up before any are timed, and rounds interleave all four, so drift
hits them equally. Best-of-7 rounds, 2,000,000 iterations each. `JniOverheadBenchmark`.

### Result — net of the Kotlin floor, ns/call

| convention | API 26 (Android 8.0) | API 34 (Android 14) |
|---|---|---|
| `@CriticalNative` | **1.86** | **24.43** |
| `@FastNative` | 38.58 | 1.99 |
| ordinary JNI | 45.56 | 8.05 |

The two devices are inverted. On API 26 `@CriticalNative` is ~24x cheaper than ordinary
JNI, which is what the design predicted. On API 34 it is the *most* expensive of the three
— 3x worse than ordinary JNI and 12x worse than `@FastNative`.

### What was ruled out

- **Measurement order.** An earlier version warmed and timed one variant at a time; whichever
  native variant went first looked anomalous. Interleaving changed nothing: API 26 stayed at
  1.86, API 34 stayed at 24.43.
- **GC starvation from the tight loop.** A `@CriticalNative` call blocks GC, so millions of
  them back to back could have been charging collector waits to the loop. Re-measured at
  20,000 iterations instead of 2,000,000: API 34 critical stayed at 26.49 net. Batch size is
  not the explanation.
- **Registration silently failing.** `argumentsArriveUnshifted` passes on both devices, so the
  critical convention *is* in effect on API 34 — arguments arrive unshifted. It is correct
  there, just slow.

### What is not known

Why API 34 is slow has not been established. The inversion is suspiciously symmetric — each
device looks like it honours exactly one of the two annotations on a fast path and routes the
other through a generic trampoline that is correct but slow. That is a hypothesis, not a
finding.

These are emulators under Apple Silicon virtualisation. Absolute nanoseconds say nothing about
real hardware, and the inversion itself may be an emulator artefact. **Before acting on this,
measure on physical devices at both API levels.**

### Consequence for the design

If this holds on hardware, the current design is optimal at minSdk and worst-case on modern
Android, which is backwards from where the users are. The fix would be to pick the convention
per API level. The annotation is compile-time, so that means declaring both variants and
dispatching on `Build.VERSION.SDK_INT` — a branch costing ~1-2ns to avoid ~22ns. Not done yet,
because it should not be built on emulator numbers alone.

### Re-measured on physical hardware — the inversion is real

The section above asked for physical-device confirmation before acting. Done: Samsung
SM-X910 (Galaxy Tab S9 Ultra), Android 16 / API 36, arm64-v8a, 8 cores. It is not an
emulator artefact, and hardware is *worse* than the emulator was.

Net of the Kotlin floor, ns/call:

| convention | API 26 (emu) | API 34 (emu) | **API 36 (hardware)** |
|---|---|---|---|
| `@CriticalNative` via RegisterNatives | **1.83** | 24.35 | **46.68** |
| `@CriticalNative` via name linking | **aborts the runtime** | 66.80 | 76.56 |
| `@FastNative` | 37.80 | **1.97** | **2.52** |
| ordinary JNI | 44.79 | 7.81 | 16.29 |

Three things are now settled.

**The binding method is not the cause.** The hypothesis was that explicitly-registered
critical natives lose the fast path on modern ART while name-linked ones keep it — Google's
advice to use RegisterNatives *before Android 12* reads as if the reverse holds after. So a
name-linked `@CriticalNative` echo was added, identical in every other way. It is *slower*
still: 66.80ns on API 34 and 76.56ns on API 36 against 24.35 and 46.68 for the registered
one. Hypothesis rejected. `@CriticalNative` is simply expensive on modern ART.

**Name linking is not merely slower below Android 12 — it is fatal.** On API 26 that call
aborted the ART runtime outright (`zygote64: runtime.cc:492] Runtime aborting...`) and took
the instrumentation process with it. Google's guidance turns out to be a hard requirement,
not a preference. The benchmark now guards that call behind `SDK_INT >= S`.

**`@FastNative` is the fast path on modern Android, and `@CriticalNative` is on old.** They
are cleanly inverted: ~2ns vs ~38ns on API 26, ~2.5ns vs ~47ns on API 36. Whatever the
underlying reason, the practical shape is unambiguous.

### Consequence: the current implementation is on the wrong path for real users

The 11 migrated functions use `@CriticalNative` via RegisterNatives. That is the best
available choice at minSdk 26 and the *worst* on the hardware people actually carry —
46.68ns where `@FastNative` costs 2.52ns, an 18x penalty.

The annotation is resolved at compile time, so the convention cannot be switched at runtime
for one declaration. The fix is to declare both variants and dispatch on
`Build.VERSION.SDK_INT`, paying one predictable branch (~1-2ns) to avoid ~44ns. `@FastNative`
still receives JNIEnv and jclass, so its exports need those leading parameters — meaning two
export sets in artMain, not one.

Not yet implemented. The crossover API level is also still unknown: it lies somewhere between
26 and 34, and picking the threshold well needs measurements at 28/30/31/32 that have not
been taken.

### The crossover, pinned: Android 14

Measured across five API levels plus hardware. Net of the Kotlin floor, ns/call, from
`JniOverheadBenchmark`:

| API | `@CriticalNative` | `@FastNative` | ordinary JNI |
|---|---|---|---|
| 26 (Android 8.0) | **1.86** | 38.69 | 45.33 |
| 30 (Android 11) | **-0.24** | 31.76 | 73.09 |
| 31 (Android 12) | **-0.02** | 24.29 | 45.97 |
| 33 (Android 13) | **-0.04** | 2.03 | 7.82 |
| 34 (Android 14) | 24.35 | **1.97** | 7.81 |
| 36 (Android 16, SM-X910 hardware) | 44.05 | **3.55** | 18.32 |

`@CriticalNative` is effectively free — at or below the measurement floor — from API 26
through 33, then collapses at 34 and stays collapsed on hardware. `@FastNative` moves the
other way: expensive through API 31, cheap from 33 onward. Two independent ART changes, and
they do not happen at the same release.

**The boundary is Android 14, not Android 12.** Google's guidance to bind `@CriticalNative`
through RegisterNatives *before Android 12* suggested 31 as the interesting version, and that
turned out to be about correctness, not cost: name-linked `@CriticalNative` aborts the runtime
below 12 and merely runs slow above it, while the registered form stays fast right through 33.

A real C API call behaves the same as the empty echo. `PyList_Size` on `sys.path` costs 2.22ns
under `@CriticalNative` on API 33 and 47.33ns on API 36, tracking the echo numbers closely.
`PyList_Size` is an O(1) header read, so the transition dominates and the convention choice is
not academic.

**Selection rule, from measurement:** `SDK_INT >= 34` → `@FastNative`, otherwise
`@CriticalNative`. Both are cheap at API 33, so a threshold placed there is forgiving in the
one place the data is closest.

### A build-wiring defect this work kept tripping over

Three separate times, an instrumented run failed because the APK did not contain what the
source tree said it should. `connectedDebugAndroidTest` does not reliably force
`linkAndroidNative*` or the `copyAndroidPythonBinaries` / `copyAndroidPythonAssets` staging,
so a changed `.def` or a cleaned `build/` produces an APK holding a stale — or entirely
missing — `libmultiplatform_python3.14.so` and no `libpython3.14.so` beside it. The symptom is
`UnsatisfiedLinkError`, which reads like a code error and is not one.

Until the task dependencies are fixed, run this before any instrumented test:

    ./gradlew :python-multiplatform:linkAndroidNativeArm64 :python-multiplatform:linkAndroidNativeX64 \
              :python-multiplatform:copyAndroidPythonBinaries :python-multiplatform:copyAndroidPythonAssets

### Final rule: the convention is a per-function decision, and correctness comes first

Picking by API level is only half of it. The two fast conventions are not merely faster —
they change what the callee is allowed to do:

- Both `@FastNative` and `@CriticalNative` **stop the garbage collector** for the duration of
  the call, so neither may wrap anything that runs for an unbounded time.
- `@CriticalNative` additionally receives **no JNIEnv**, so nothing beneath it can re-enter the
  runtime at all.

Plenty of CPython entry points can execute arbitrary Python: a module's top-level code during
import, a `__getattr__` or descriptor during attribute lookup, a `__del__` reached by dropping
the last reference, `site.py` during start-up, `atexit` handlers during teardown. Those are
unbounded in time, and once Kotlin callables are exposed to Python they re-enter the JVM —
which is exactly what `@CriticalNative` cannot support. This is the same reason
`Linker.Option.critical()` is a per-downcall option in Panama rather than a global switch.

So each function is classified, and the classification outranks the API-level choice:

| class | rule | of the 11 migrated |
|---|---|---|
| **leaf** — cannot execute Python | fastest convention for the API level | `Py_IsInitialized`, `PyList_Size`, `PyErr_Occurred`, `Py_GetVersion`, `PyLong_FromLongLong` |
| **re-entrant** — may run Python, may upcall, may block | **ordinary JNI, always** | `Py_Initialize`, `Py_Finalize`, `PyErr_Clear`, `PyRun_SimpleString`, `PyImport_ImportModule`, `PyObject_GetAttrString` |

`PyErr_Clear` looks like a leaf and is not: clearing the error drops the last reference to the
exception, and that can run a Python `__del__`. `PyObject_GetAttrString` looks like a field
read and is not, for the same kind of reason. When in doubt the call goes on the ordinary
path — a wrong guess there costs nanoseconds, while a wrong guess the other way is a crash
once upcalls exist.

Losing the fast path on the re-entrant half is not the tragedy it appears. Those calls do real
work — importing a module or running a statement dwarfs a 40ns transition — whereas the leaf
calls, where the transition genuinely dominates, are exactly the ones that keep it.

## Desktop vs Android, measured

Same benchmark shape on both sides: same warmup, same iteration count, best-of-7, a
pure-Kotlin identity call subtracted as the floor, and the same real C API call —
`PyList_Size` on `sys.path`.

| | floor | `PyList_Size` | net |
|---|---|---|---|
| **Desktop** (Apple M1, JDK 21, Panama) | 0.33 ns | **1015.95 ns** | **1015.61 ns** |
| Android API 36 (SM-X910, `@FastNative`) | 3.08 ns | 7.47 ns | 4.39 ns |
| Android API 33 (emulator, `@CriticalNative`) | 2.28 ns | 2.22 ns | ~0 ns |

Desktop is roughly **140x more expensive per call than Android hardware**, and the gap is
worse than it looks: the host is about 9x faster at the floor (0.33ns vs 3.08ns), so the FFI
call is slower on the machine that is faster at everything else.

The assumption running through this document — that Android is the platform with the
overhead problem and desktop is the reference — is backwards. Desktop is currently the
slowest FFI path in the project by two orders of magnitude.

### Why

None of this is inherent to Panama. It is the reflection-based backend:

    inline fun PyList_Size(list: Long): Long = PyList_SizeHandle.invoke(list) as Long

`invoke` rather than `invokeExact` forces an `asType` adaptation and boxes the argument and
the result on every call. Worse, the argument and return filters are themselves reflective
`MethodHandle.invoke` calls that build a `MemorySegment` per call via `ofAddress` and
`reinterpret`. A microsecond for an O(1) header read is what that chain costs.

The backend was written reflectively so the code would compile against `java.lang.foreign`
and `jdk.incubator.foreign` on any JDK, which was the right call for portability and the
wrong one for the hot path. The portability requirement does not extend to the per-call path
— only to how handles are *created*.

### Consequence

`invokeExact` was flagged as a concern long before this measurement and never quantified.
It is now: about 1000ns per call, against roughly 5-10ns for a properly linked Panama
downcall.

This makes desktop, not Android, the first thing to fix. The shape vocabulary already
committed is the lever: 14 fixed signatures make `invokeExact` reachable, because the call
site's static type is then known and constant. That work now has a measured payoff rather
than a suspected one.

### Fixed: 1015.95 ns -> 2.65 ns

Two changes, both confined to the desktop backend:

**Pointers are now described as `ValueLayout.JAVA_LONG`, not `ADDRESS`.** Both are 8 bytes and
travel in the same register on a 64-bit ABI, but describing them as addresses forced Panama to
hand back a `MemorySegment`, which the backend then unwrapped through per-call argument and
return filters -- reflective `MethodHandle` invocations of `ofAddress`, `reinterpret` and
`getString`. Describing them as longs deletes the filters outright.

**Call sites use `invokeExact`.** With no filters the handle's type is exactly
`(long, long, ...) -> long`, which matches the Kotlin call site's static type, and that is the
condition `invokeExact` requires. Kotlin accepted it directly with no signature-polymorphism
workaround, which had been the open question. 265 wrappers now use it.

Measured on the same machine, same benchmark:

| | before | after |
|---|---|---|
| `PyList_Size` on `sys.path` | 1015.95 ns/call | **2.65 ns/call** |
| net of the Kotlin floor | 1015.61 ns | **2.01 ns** |

About 380x, and desktop goes from the slowest FFI path in the project to the fastest — 2.65ns
against 7.47ns on Android hardware, on a host roughly 9x faster at the floor, so the two are
now in the same regime rather than separated by two orders of magnitude.

The reflective two-backend selection is untouched and still resolves `java.lang.foreign` or
`jdk.incubator.foreign` at startup. That was never the problem; letting reflection reach the
per-call path was. Reflection now runs once per symbol, at link time.

Strings still convert at the Kotlin level rather than through a handle filter: `withUtf8 { }`
allocates, passes the address as a long, and frees in a `finally`. Return values owned by
CPython are read without being freed, since freeing them would be a use-after-free.

### Separating the API level from the hardware

Until now the modern-Android numbers came from a Samsung tablet while the older ones came from
emulators on the M1 host, so "API 36 is slowest" mixed two variables. Running API 36 on an
emulator settles it.

Same API level, different machine — real `PyList_Size` call:

| API 36 | floor | `@CriticalNative` | `@FastNative` | ordinary JNI |
|---|---|---|---|---|
| emulator (M1 host) | 2.20 ns | 23.20 | **4.28** | 9.39 |
| hardware (SM-X910) | 3.22 ns | 46.75 | **7.24** | 19.25 |

Hardware is 1.5-2x the emulator across every row, and the floor moves by the same factor
(3.22 vs 2.20). That is the tablet's core being slower than the host, not anything about
API 36. The earlier reading that "API 36 is the slow one" was partly this artefact.

Same machine, different API level — emulators on the M1 host only:

| | `@CriticalNative` | `@FastNative` |
|---|---|---|
| API 26 | **1.85** | 40.15 |
| API 33 | **-0.04** | 2.03 |
| API 36 | 21.72 | **1.66** |

The collapse is still real and still API-driven: `@CriticalNative` goes from free at 33 to
21.72ns at 36 with the hardware held constant. So the crossover conclusion and the dispatch
threshold stand — hardware exaggerated the gap but did not create it.

### Desktop vs Android, corrected

The earlier "desktop 2.65ns vs Android 7.47ns" compared an M1 host against a tablet. On the
same host, for the same real call:

| | `PyList_Size` |
|---|---|
| Desktop (Panama, `invokeExact`) | **2.65 ns** |
| Android API 36 emulator (`@FastNative`) | **4.28 ns** |
| Android API 36 hardware (`@FastNative`) | 7.24 ns |

About 1.6x between the two paths on identical hardware, not the 2.8x the cross-machine
comparison implied. Both are now in the same regime, which is the part that matters.

## Composition: measured

Per-crossing cost has bottomed out — 2.65ns on desktop, 1.66-7.24ns on Android depending on
API level and hardware. The only lever left is crossing fewer times, which is the structure
the Android path was originally built around: `artMain` assembling a whole binder operation
and `androidMain` calling that assembly once, rather than `commonMain` driving raw C API
calls one crossing at a time.

`CompositionBenchmark` measures two cases against their per-call equivalents.

| | per-call | composed | |
|---|---|---|---|
| **API 36 hardware** | | | |
| `getAttr` (4 crossings → 1) | 3929.84 ns | **713.34 ns** | 5.5x |
| `list → LongArray`, 1000 elems (1001 → 1) | 50065.89 ns | **4532.55 ns** | 11.0x |
| **API 36 emulator** | | | |
| `getAttr` | 2270.23 ns | **474.67 ns** | 4.8x |
| `list → LongArray` | 28145.00 ns | **3705.63 ns** | 7.6x |
| **API 26 emulator** | | | |
| `getAttr` | 2431.34 ns | **244.70 ns** | 9.9x |
| `list → LongArray` | 4890.83 ns | 4113.13 ns | 1.2x |

### What the numbers actually say

**Composition wins, but not purely by removing crossings.** Four crossings at ~7ns is 28ns,
which does not explain 3929ns versus 713ns. The per-call `getAttr` allocates a C string
through `ffiAllocUtf8` — a malloc, a copy out of the jstring, and a matching free — where the
composed version borrows the jstring directly with `GetStringUTFChars` and releases it. Most
of the win is redundant work that disappears when the operation is expressed once on the
native side, and the crossing count is the smaller part. That is still a real argument for
composing, but the mechanism is not the one the crossing-count framing suggests.

**The bulk case is where crossing count genuinely dominates**, and it scales with N: 11x at
1000 elements on hardware. That is the case the current design handles worst, because
`commonMain` drives it element by element.

**API 26 barely benefits from composing the bulk case** — 1.2x. Its per-element crossings run
under `@CriticalNative`, which is free at that API level, so there was little to remove. This
is the mirror image of the convention result: composition pays most exactly where the
per-crossing cost is highest, which is modern Android, which is where the users are.

### Where this leaves the design

`PyObject` was `expect`/`actual` with an Android `actual` marked `external` — the hook for
exactly this — until commit 0fae961a (2025-12-20) folded it into a single `commonMain` class
along with `PyObject.desktop.kt` and `PyObject.native.kt`. That commit's real subject was
moving `PyAutoCloseable` to per-platform implementations, which was right and should stand;
losing the composition hook was collateral. Restoring `expect`/`actual` on `PyObject` while
keeping the `PyAutoCloseable` split is what reopens this path.

Windows cannot host a Kotlin/Native assembly library, so a composed desktop path would cover
macOS and Linux only, with Windows staying on direct Panama calls. At 2.65ns per crossing
that is a much smaller loss on desktop than the same gap would be on Android.

## One downcall, across all platforms

The upcall side has "One upcall, across all five platforms" in `upcall-design.md` — one shared
test, run on every target, min–max over several runs, quoted rows marked as quotes. The downcall
side had no equivalent: the benchmarks that exist are real and each one answers a question, but
they are scattered across five files with no shared shape, so "how expensive is a downcall on
platform X" had no single table to read.

### What exists today

| File | Source set | What it measures | Runs where |
|---|---|---|---|
| `commonTest/.../overhead/Benchmark.kt` | `commonTest` | Warmup-then-time harness (`run`/`measure`), not a benchmark itself | wherever the tests below run |
| `commonTest/.../overhead/BenchmarkTest.kt` | `commonTest` | Pointer boxing, raw refcount churn, string marshalling (`PyUnicode_FromString`/`AsUTF8`, three lengths), integer marshalling, `PyObject` wrapper cost, `PyObject_GetAttrString` | compiles for all six run paths below; **confirmed green** on desktop / iosSimulatorArm64 / wasmJsNode this run; reaches androidInstrumented and androidNativeArm64/X64 through the same `commonTest` dependency edge but neither ran here (no emulator this session) |
| `desktopTest/.../DesktopOverheadBenchmark.kt` | `desktopTest` | Panama transition cost (`PyList_Size` net of a pure-Kotlin floor); the string-marshalling-share-of-`exec` test is `@Ignore`d (destabilises the interpreter, see the file's own comment) | desktop only |
| `wasmJsTest/.../WasmMarshallingOverheadTest.kt` | `wasmJsTest` | `internedUtf8` vs `scratchUtf8` vs the old malloc/copy/free route, at two string lengths, both standalone and through a real `PyObject_GetAttrString` | wasmJs only |
| `androidInstrumentedTest/.../JniOverheadBenchmark.kt` | `androidInstrumentedTest` | JNI calling-convention transition cost: ordinary / `@FastNative` / `@CriticalNative`, on a trivial echo and on `PyList_Size` | Android/ART only, needs a device or emulator |
| `androidInstrumentedTest/.../CompositionBenchmark.kt` | `androidInstrumentedTest` | Per-call crossings vs one composed `artMain` call, for `getAttr` and `list → array`, plus a direct-buffer marshalling variant | Android/ART only, needs a device or emulator |

Two more files live next to these and share the word "overhead" or "Upcall" in their name but are
**not** downcall benchmarks — noted here only so a future reader does not double-count them:
`commonTest/.../reflection/UpcallOverheadTest.kt` prices the upcall dispatch table
(`UpcallTable.resolve` vs a cached handle), and `androidInstrumentedTest/.../UpcallOverheadTest.kt`
prices the Android upcall attach. Both are upcall-side and already covered by `upcall-design.md`.

`BenchmarkTest` reaches every one of the six execution paths KGP registers for this module
(`desktopTest`, `iosSimulatorArm64Test`, `iosX64Test`, `wasmJsNodeTest`, `androidInstrumentedTest`/
`connectedAndroidTest`, `androidNativeArm64Test`+`androidNativeX64Test`) because it sits in
`commonTest`, which every one of those depends on transitively. It is confirmed to run — not just
compile — on three of the six this pass: desktop, the iOS simulator, and wasmJs (see Validation
below). `iosX64Test` has no practical run path on Apple Silicon and nobody targets it; the
Android/ART and androidNative rows need a connected device or emulator, which this pass did not
have available (AGENTS.md's "에뮬레이터는 쓰지 마라" for this task).

### A shared table, picked to match the upcall table's columns

The upcall table's denominator column is `PyObject_CallObject` on a Python `def` — "a downcall of
the same shape" — plus `Py_IncRef`/`Py_DecRef` and an empty `withPython` scope as the two
boundary-cost brackets. Those three, plus one string-marshalling call, are exactly what
`UpcallBoundaryCostTest`'s `measureDowncalls()` (`commonTest`) already records **in the same run as
the upcall it prices** — so pulling them out as their own table is not a new measurement, it is the
existing "comparison basis" rows read on their own. The fourth column, string marshalling, comes
from `BenchmarkTest.testStringMarshalling`'s 8-character row, run in the same suite execution.

Desktop, iOS simulator and wasmJs are measured fresh here — three full-suite runs
(`:desktopTest :iosSimulatorArm64Test :wasmJsNodeTest`), same command as Validation below, each
with `build/test-results` cleared first so a task cannot report a stale XML. All three runs matched
the baseline exactly (360/0/1, 330/0/0, 340/0/0). Ranges are min–max over three runs for the first
three columns; the string-marshalling column is min–max over two of those three runs (the first
run's `BenchmarkTest` XML was not preserved before the second run overwrote it — its
`UpcallBoundaryCostTest` numbers were captured from console output before that happened, which is
why that row has three runs and this one has two).

androidNative and Android/ART rows are **quoted from `upcall-design.md`**, not re-measured — marked
† below, same convention that document uses for its own quoted rows. Cells with no prior
measurement of this exact quantity are left blank rather than approximated from a different one
(e.g. `CompositionBenchmark`'s composed `getAttr` is a different quantity — four crossings folded
into one — not a bare `PyUnicode_FromString`).

> **Four of this table's seven rows are quotes from another document and one column of it is
> superseded (below), which is what happens to a table assembled by hand.** The same four columns,
> cut for all six targets in one run with their conditions attached, are in
> [`cost-table.md`](../investigations/cost-table.md): `./benchmarks/cost-table.sh --runs 3` on a quiet machine fills
> it. It draws the first three columns from the same `measureDowncalls()` rows this table does and
> the fourth from the same `BenchmarkTest` row, so it is this table's recipe automated, not a
> different measurement.

| Platform | empty `withPython` scope | `Py_IncRef` + `Py_DecRef` | `PyObject_CallObject` (downcall, same shape as the upcall table's numerator) | `PyUnicode_FromString`, 8 chars |
|---|---|---|---|---|
| **desktop** (JVM 21.0.12, macOS arm64) | 51.78–52.60 ns | 250.48–257.99 ns | 260.27–265.81 ns | 185.13–191.00 ns |
| **iOS simulator** (arm64) | 701.18–723.85 ns | 1488.64–1543.92 ns | 1585.46–1655.40 ns | 3338.51–3490.55 ns |
| **wasmJs** (Node) | 24.66–25.15 ns | 45.23–46.98 ns | 96.53–100.95 ns | 101.55–103.22 ns |
| *androidNative* `pmp_api36`† | *998.73–1051.08 ns* | *2058.84–2167.44 ns* | *2194.26–2365.61 ns* | *4419.55–4706.72 ns* |
| *androidNative* `pmp_api26`‡ | *1193–1298 ns* | *(no prior measurement)* | *2636–5554 ns* | *(no prior measurement)* |
| *Android API 26* (ART)† | *(no prior measurement)* | *(no prior measurement)* | *1253.41–1295.79 ns* | *1404.00–1485.24 ns* |
| *Android API 36* (ART)† | *(no prior measurement)* | *(no prior measurement)* | *804.27–838.60 ns* | *1013.04–1197.93 ns* |

**Source for rows 1–4, 6–7:** `docs/investigations/cost-table.md` (commit `958c0082b294`), `UpcallBoundaryCostTest` columns 1–3 with warmup 100,000, min–max over 3 runs per target. Column 4 (`PyUnicode_FromString, 8 chars`) from `BenchmarkTest` same source, warmup 100,000.

† Rows 1, 2, 3, 4 and 6–7: Directly from cost-table.md. The Android/ART rows (6–7) record both upcall and downcall in the cost-table measurement, fixing the prior "not recorded" state.

‡ androidNative `pmp_api26`: Does not appear in cost-table.md (only `androidNativeArm64` on `pmp_api36` is there). **Awaiting re-measurement** under the same conditions as the cost-table harness.

**Prior values** (warmup 3,000): desktop empty scope 123.88–135.99 ns, Py_IncRef+Py_DecRef 321.69–334.10 ns, PyObject_CallObject 315.69–335.32 ns, PyUnicode_FromString 1129.95–1270.76 ns (note: this final column was superseded separately, see below).

> **The `PyUnicode_FromString, 8 chars` column has been updated.** It is sourced from
> `overhead/BenchmarkTest` with warmup now 100,000 (was 100), for the reason "The benchmark was
> measuring the benchmark" explains in `upcall-design.md`. The old figures here were 3–7x the warmed
> cost because they were swept at the old `100`-call warmup on desktop (1132–1146 ns cold vs 167–458 ns warm).
> The new column records the current cost at 100,000 warmup, from commit `958c0082b294`. The first three
> columns also come from `UpcallBoundaryCostTest` (already fixed at 100,000 warmup since `7e9c6b8c`), so
> all four columns are now consistent in warmup and source.

### Ratio consistency: mostly holds, desktop is the exception

> **Superseded as a source of numbers, kept as the record of how the defect was found.** Every figure
> in this section and the two that follow it was taken with `UpcallBoundaryCostTest`'s 3 000-call
> warmup, which "The benchmark was measuring the benchmark" below shows was too small by more than an
> order of magnitude. The reasoning is what still stands: these three sections are what established
> that the number moved for a repository-located reason and then that the reason was the benchmark's
> own methodology. Current figures are in `upcall-design.md`'s table.

`upcall-design.md`'s own upcall/downcall ratios, from the same `UpcallBoundaryCostTest`, are
reproduced by these three fresh runs (the test computes and prints its own ratio each time, so this
is a direct comparison, not a re-derivation):

| Platform | ratio recorded in `upcall-design.md` | ratio, this pass (3 runs) | consistent? |
|---|---|---|---|
| desktop | 2.49–2.89x | 2.13–2.34x | **no — see below** |
| iOS simulator | 1.33–1.43x | 1.38–1.42x | yes, nested inside the old range |
| wasmJs | 2.56–3.36x | 2.36–2.63x | mostly — top of the new range overlaps the bottom of the old one. **Superseded: since drifted to 1.58–1.68x, pinned to `4472f83a` and explained — see "wasmJs: closed too" below** |

**Desktop's ratio moved outside the previously recorded range, and it is the upcall side that
moved, not the downcall side.** This pass's downcall-same-shape figure (315.69–335.32 ns) sits at
the bottom of the old 315–527 ns range — consistent, just at its floor. This pass's upcall figure
(687.83–741.26 ns, read from the same `UpcallBoundaryCostTest` runs) sits *below* the old range's
floor of 861 ns entirely. So on this machine, at this commit (`65e1bf5d`), a desktop upcall through
`ctypes` measures cheaper than what `upcall-design.md`'s table recorded, while the downcall side did
not move. Two things separate the two tables and either could be the cause: `develop` has moved a
great deal since that table was captured (this session's own `git merge` pulled in 87 changed files,
including upcall-adjacent ones — see the merge log above), and the two tables were also captured on
different runs of the same machine, which is exactly the kind of thing this document elsewhere warns
is not comparable. Neither is ruled out here; this is reported as an open discrepancy, not resolved
into either explanation.

### Closed: repository drift, not run-to-run noise (2026-08-14)

The discrepancy above was tested directly rather than argued from a second reading. `537c1a0b`
(the commit the 861 ns floor was recorded on) and the current tip were each built in their own
freshly created `git worktree` — not reused, not incrementally recompiled — and
`:python-multiplatform:desktopTest` (the full suite, not a filtered subset — see the pitfall below)
was run three times against each, clearing `build/test-results` before every run:

| | run 1 | run 2 | run 3 |
|---|---|---|---|
| `537c1a0b`, fresh worktree | 870.48 ns | 808.72 ns | 869.12 ns |
| current tip, fresh worktree | 697.73 ns | 680.46 ns | 686.21 ns |

Same machine, same JDK (Temurin 21.0.12), same day. The two bands (809–870 ns vs. 680–698 ns) do
not overlap across six runs split into two isolated builds, which rules out ordinary run-to-run
variance as the explanation. Something in the repository between those two points changed the
measured number. `537c1a0b`'s figure also reproduces its own historical record (861–1313 ns) almost
exactly, which rules out a stale or miscalibrated re-measurement on this end.

**A methodology pitfall found along the way:** an initial attempt to bisect the ~75 commits in
between used `--tests UpcallBoundaryCostTest` to skip the other ~359 tests and go faster. That
filter alone moved the number into the 1300–1400 ns range at *every* commit tried, old and new
alike — it does not merely add noise, it changes which regime the measurement lands in. Re-running
the same checkouts with the unfiltered full suite reproduced the fast band again. The cause is
below; the practical lesson is that this benchmark's absolute number is not a property of the
timed code alone, so a comparison across two runs is only valid if both used the same suite scope
this document already required (Validation runs the full suite) — a filtered re-run of one
benchmark test is not equivalent to the run it is being compared against, even on the same commit.

**Bisection was attempted and did not converge to one commit; it converged to a window, and to a
mechanism.** Git bisect (full-suite grading, threshold at 750 ns) was run between `537c1a0b` and
the fast tip. Partway through, machine load reached 10–12 on this 8-core host — two Android
emulators and other concurrent agent activity were running, unrelated to this task and outside its
control (`ps aux` at the time confirmed it; this document's own environment notes elsewhere warn
that this machine hosts several worktrees' worth of parallel agent work). Under that load, repeated
measurements of the *same* checkout spanned both regimes (one commit read 672, 771, and 1076 ns
across three consecutive runs), which made single-commit attribution unreliable. What did hold up
under repetition was the window: commits at or before `4722cfe9` measured consistently in the slow
band (854–855 ns), and commits at or after `ec8ff389` measured consistently in the fast band (672,
700 ns, each confirmed in its own fresh clean worktree). That window —
`4722cfe9..ec8ff389` (~15 commits) — contains both commits this task flagged as candidates:
`6776329d` ("Perf: Price the layer users actually call, and stop a module read raising an exception
per access") and `d45071e7` (proxy handle lifetime). `975d3900` sits well outside this window and is
ruled out.

**Neither candidate touches the code this benchmark measures, which points at an indirect cause.**
`git diff <parent>..<commit>` for both `6776329d` and `d45071e7` shows changes confined to
`PythonProxySource` (the generated-proxy source emitter, its tests) and, for `d45071e7`, a doc-only
comment added to `HandleTable.kt`. Neither touches `UpcallTable.resolve`, `HandleTable.resolve`/
`release`, the non-suspend branch of `UpcallTrampoline.invoke`, or desktop's `UpcallStub` — the
actual call chain `UpcallBoundaryCostTest` times through `TrampolineFragment`. A commit that does
not touch the timed path cannot have made the timed path itself cheaper.

What it can do is change what runs *before* the timed path in the same process. `desktopTest` sets
no `forkEvery`, so Gradle's default applies: all ~360 tests in the suite run in one forked JVM, one
JIT compiler, one heap (confirmed by reading the `tasks.named<Test>("desktopTest")` block in
`build.gradle.kts` — no fork-per-test config exists). `6776329d`'s own commit title is "stop a
module read raising an exception per access" — replacing an exception-driven control-flow path
(expensive on the JVM: every throw captures a stack trace) with a direct one.
`GeneratedProxyCostTest`, touched by the same commit, drives `N = 10_000` iterations times 3 kept
times multiple rows of generated-proxy calls that exercise exactly that module-attribute path, and
it runs earlier in the same shared JVM process as `UpcallBoundaryCostTest`. Removing tens of
thousands of exception throws from an earlier test changes the shared process's GC pressure and
JIT tiering history by the time a later, unrelated benchmark in the same run gets timed — without
the later benchmark's own code path changing at all. That is a plausible, mechanism-level
explanation for why only desktop moved: iOS runs through XCTest's own process model and wasmJs
through a Node process with a different (V8) JIT, neither sharing HotSpot's C2 warm-up state the
way every desktop test in one Gradle-forked JVM does.

**This is not proven to the level of a single blamed commit** — machine contention prevented that —
but it is proven to the level that matters for the table: the number moved for a real, reproducible,
repository-located reason, not for noise, and the most likely mechanism is a same-process
warm-up artifact of the benchmark's own methodology rather than a genuine drop in the upcall
boundary's per-call cost. The open discrepancy above is closed on that basis, not left open.

**wasmJs has since drifted further, past even its own "consistent" reading above.** A fresh check
on the current tip (same machine, same session, two runs: 321.32 ns and 315.89 ns, tight) puts
wasmJs's upcall figure well below the 687–741 ns-equivalent range implied by this table's own
"this pass" ratio (2.36–2.63x against a ~192–205 ns downcall figure that still matches today's
run) — today's ratio is 1.26–1.67x. So the "yes, mostly consistent" verdict recorded for wasmJs
above was accurate for the commit it was measured on (`65e1bf5d`) and has since gone stale in the
same direction as desktop's did. iOS was not re-checked: this task's constraints rule out using the
iOS simulator, so its "yes, nested inside the old range" verdict above stands unverified rather
than reconfirmed.

### wasmJs: closed too — one commit, and the mechanism demonstrated rather than argued (2026-08-14)

wasmJs's drift is **repository drift, not noise**, it is pinned to the **single commit `4472f83a`**,
and — unlike desktop's, which stayed at the level of a plausible story — its mechanism was
reproduced by a controlled experiment. It is *not* a genuine change in what the wasm upcall boundary
costs.

The measurement protocol was the one the desktop section above used, with two changes forced by
this machine: runs at the two ends were **interleaved** rather than run in two blocks, and the
timed task was re-run with `--rerun` on `wasmJsNodeTest` alone rather than `--rerun-tasks`, so a
full Kotlin recompile does not finish moments before the benchmark starts. Every run is the **whole
wasm suite** (filtering changes the measured region — see the pitfall below), `build/test-results`
is cleared before each, and all of them are 344/0/0 (258/0/0 at `537c1a0b`, whose suite was smaller).

| commit | upcall | downcall, same shape | ratio | runs |
|---|---|---|---|---|
| `65e1bf5d` (parent) | 484.22–500.38 ns | 190.11–191.00 ns | 2.53–2.61x | 3 |
| **`4472f83a` (child)** | **306.66–315.82 ns** | **189.32–202.56 ns** | **1.52–1.66x** | 3 |
| `ec8ff389` | 482.03–507.38 ns | 197.87–204.10 ns | 2.38–2.53x | 3 |
| `4722cfe9` | 477.40–497.05 ns | 190.60–203.23 ns | 2.34–2.60x | 3 |
| `17f058ca` | 302.55–320.54 ns | 189.47–203.92 ns | 1.50–1.65x | 3 |
| `73f2af6b` | 312.50–322.11 ns | 188.95–213.49 ns | 1.46–1.66x | 3 |
| current tip | 355.82–373.21 ns | 222.07–226.88 ns | 1.58–1.64x | 4 (+4 earlier) |

`4472f83a` and its own parent are adjacent commits measured minutes apart in one worktree, and their
upcall bands do not touch. **The downcall column does not move anywhere in this table** — it is the
upcall side alone, exactly as on desktop.

**The window does *not* overlap desktop's.** Desktop's was `4722cfe9..ec8ff389`; both of those
endpoints measure in wasm's *slow* band and are indistinguishable from each other, so wasm did not
move across desktop's window at all. `4472f83a` sits after `ec8ff389`. The two platforms therefore
do **not** share a cause in `commonMain`'s marshalling or handle paths — which was the hypothesis
worth testing, and it is refuted rather than left open.

**The MEMFS change is not the cause either.** `3355851c` (stdlib zip installed into MEMFS instead
of resolving out of a CPython source checkout) was the obvious environmental confound, since it
lands after the desktop window. `73f2af6b` is its immediate parent and already measures 312–322 ns —
fully in the fast band. The transition happened before it.

**Why a commit that only adds a branch to the timed function made it measure cheaper.**
`4472f83a`'s only edit to the timed path is a `when` on `self` in `UpcallEntry.invokeMethod`, which
can only cost more, not less. What it also did was make `PythonProxySource.install()` succeed on
wasm for the first time. Before it, `GeneratedProxyCostTest` and `ProxyHandleLifetimeTest` printed
"no proxies are installable on this target, so there is nothing to measure" and drove **zero**
upcalls; after it, `GeneratedProxyCostTest` drives on the order of 270 000 calls
(`N = 10 000` × 3 kept × ~9 rows) straight through `UpcallEntry.invokeMethod` — earlier in the
**same Node process**, and therefore into the same V8 wasm tier-up state that
`UpcallBoundaryCostTest` is later timed in. The benchmark's own 3 000-iteration warmup does not
reach that state on its own.

That was tested, not just asserted. At the **current tip**, with nothing changed but
`GeneratedProxyCostTest`'s body short-circuited so it installs nothing and calls nothing — the
pre-`4472f83a` behaviour, same commit, same build, suite still 344/0/0 — the upcall figure went
back up:

| current tip | upcall | downcall | ratio |
|---|---|---|---|
| as it is | 355.82–373.21 ns | 222.07–226.88 ns | 1.58–1.64x |
| with `GeneratedProxyCostTest` inert | 459.67–473.79 ns | 187.24–192.16 ns | 2.36–2.53x |

One lever, at one commit, moves the number across the whole gap and lands it back on `65e1bf5d`'s
band (2.53–2.61x) and on this document's own "this pass" reading (2.36–2.63x). So the desktop
section's suspicion — that this benchmark reports a same-process warm-up artifact rather than a
boundary cost — is **confirmed on wasmJs by direct experiment**, on a different commit and a
non-overlapping window from desktop's. What the two platforms share is the methodology, not a
code path.

**The consequence for the table is that `UpcallBoundaryCostTest`'s absolute upcall figure is not a
property of the commit alone.** It is a property of the commit *and* of how much upcall traffic the
rest of the suite pushed through the same process first. Any future row should record the suite
composition it was taken with, or the number will "drift" again the next time an unrelated test
starts or stops exercising the boundary.

**One thing did not reproduce and is left as observed.** `537c1a0b`, the commit
`upcall-design.md`'s 703–1075 ns wasm row was recorded on, does not reproduce that row here: a
freshly created worktree at it measured 546.45 ns once and then 1476.85–1602.25 ns on four
subsequent runs (its trampoline figure was similarly unstable, 268–612 ns, while its downcall stayed
flat at 239–247 ns). That commit's suite is 258 tests rather than 344 and predates the MEMFS change,
so it is a different environment in two ways at once, and no attempt is made here to reconcile it.
The conclusion above rests on the adjacent-commit pair `65e1bf5d`/`4472f83a` and on the tip-vs-tip
experiment, neither of which depends on `537c1a0b` at all.

**Load, since these numbers depend on it.** Machine load average was 1.4–1.6 at the start and
stayed in the 1.7–3.2 band for every run quoted above. An earlier batch was thrown away: creating
the comparison worktree set Spotlight indexing its 16 190 files, which took load to 10.4 and made
the same commit read 546 ns then 1448–1677 ns. Those runs are not in this document. The machine also
had an Android emulator resident throughout, which is why the floor is ~1.5 rather than ~0.

### The benchmark was measuring the benchmark, and the count that fixes it is measured (2026-08-14)

The two sections above diagnosed `UpcallBoundaryCostTest` and stopped there: they established that
its absolute figure was a same-process warm-up artefact, pinned wasmJs's shift to one commit, and
attached a staleness note to the table. **The measurement itself was not changed**, so the next pass
would have re-derived the same non-number. This section changes it and shows the change works.

#### What is warming, decided by experiment rather than by plausibility

Both earlier sections reached for "JIT tiering" as a story. It is testable, and the obvious rival —
CPython-side state, meaning the specializing interpreter, free lists, the allocator, string
interning — makes an opposite prediction about one row that the report already prints.

The timed loops were run **40 times in succession** inside one run, on both hosts, in two suite
configurations. Per 10 000 calls:

| | rep 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | … plateau |
|---|---|---|---|---|---|---|---|---|---|
| desktop upcall, suite as-is | 801 | 744 | 704 | 705 | 676 | 602 | 593 | 537 | ~535 ns |
| desktop upcall, proxy test inert | 1307 | 716 | 696 | 660 | 548 | 539 | 558 | 554 | ~535 ns |
| desktop downcall (Kotlin-driven) | 475 | 264 | 264 | 212 | 141 | 141 | 140 | 143 | ~141 ns |
| wasmJs upcall, suite as-is | 300 | 313 | 317 | 290 | 301 | 285 | 305 | 278 | ~280 ns |
| wasmJs upcall, proxy test inert | 785 | 339 | 317 | 303 | 294 | 299 | 319 | 279 | ~280 ns |

**The two configurations converge on the same value and they converge from opposite sides.** So the
plateau belongs to the boundary and everything before it belongs to the suite. That alone settles
what the number should be.

**Two rows in the same sweep do not move at all**, and they are what identifies the mechanism: the
empty Python loop (8–9 ns desktop, 14–16 ns wasm) and the pure-Python callee (24–27 ns, 47–54 ns) are
flat from the very first rep, in every configuration, on both hosts. CPython-side state would move
those — they are pure interpreter work — and it does not. Only rows that cross into host code move.
It is host JIT tier-up: C2 on the JVM, V8's tiering on wasm.

The confirmation is on desktop, where the two runtimes can be told apart: CPython is a **native
dylib** there, so its own speed cannot depend on how warm the JVM is, and indeed desktop's
pure-Python rows are identical (8.44–8.59 ns) whether the class runs alone or inside the full suite.

#### The count, and why 100 000

Convergence needed 40 000 calls cold and 70 000 warm on desktop, and ~70 000 on wasm. The warm side
settles later, so it sets the requirement; 100 000 is that with margin. The Kotlin-driven rows were
on the same curve and worse off — they warmed `N / 4` = 2 500, and desktop's downcall row reads 653,
266, 266, 161 before settling at ~141 — so they use the same count now. Warming the Python-driven and
Kotlin-driven halves differently would make the headline ratio a ratio of two compilation states.

`GeneratedProxyCostTest` needed far less, and the reason is its shape: it warms all 19 rows before
timing any, so the shared `_pm_invoke` path already received 19 × 3 000 = 57 000 calls — just under
the knee. 5 000 puts it at 95 000. Measured, its raw rows moved from 3–7% solo-vs-suite disagreement
to 1–3.4%.

**`inline` on `Benchmark.measure` was tried and is worse.** The hypothesis was that one shared
`block()` call site goes megamorphic across every benchmark in the process. Inlining it made desktop's
downcall row need ~230 000 iterations to reach the plateau it reaches in ~50 000 through the shared
non-inlined loop, because each inlined copy is separate code that tiers up on its own. Reverted, and
recorded so it is not tried again.

#### The check the old table could not pass

| | full suite | proxy test short-circuited | this class alone (`--tests`) |
|---|---|---|---|
| desktop upcall, **3 000 warmup** | 673.91 ns | 536.75 ns | — |
| desktop upcall, **100 000 warmup** | 510–560 ns (11 runs) | 501–530 ns (3) | 506–550 ns (3) |
| wasmJs upcall, **3 000 warmup** | 322.03 ns | 291.67 ns | — |
| wasmJs upcall, **100 000 warmup** | 290–304 ns (7 runs) | 287–301 ns (3) | 321–353 ns (3) |

The middle column is the lever the wasmJs section above used to prove the defect existed. It used to
move desktop by 25% and wasmJs by 10%; it now moves neither outside its own run-to-run band. **That
is the fix.**

**The third column is honest about what is still not fixed, on one target.** Desktop passes it. wasmJs
does not, and the reason is not the boundary: filtered to this class alone, wasmJs's *pure-Python*
rows read 23.05 ns and 83.10 ns against the full suite's 15.04 ns and 47.22 ns — 55–74% slower, in
rows with no boundary in them. CPython is itself a wasm module under V8, so a Node process that lives
one second runs the interpreter's own bytecode slower than one that lives ten, and every row is
inflated together. Raising the warmup to 300 000 does not move it (solo read 318.83 and 326.21 ns
against 100 000's 320.54–337.42), which is what distinguishes a host-lifetime effect from a
call-count effect. **wasmJs's ratio columns do survive filtering** — 2.66–3.13x solo against
2.91–3.14x in the suite — so the ratio is the quantity to quote there, and the absolute column is a
full-suite figure that must be labelled as one. It is left in the table with that label rather than
deleted, because desktop's is now sound and dropping both would lose a real result.

#### What it costs

Measured on this machine, full suite, `build/test-results` cleared before each run:

| | 3 000 warmup | 100 000 warmup |
|---|---|---|
| `desktopTest`, total test time | 4.93–4.97 s | 5.10–5.20 s |
| …of which `UpcallBoundaryCostTest` | 0.036 s | 0.155–0.162 s |
| …of which `GeneratedProxyCostTest` | 0.553–0.571 s | 0.625–0.661 s |
| `wasmJsNodeTest`, wall clock | 4.31 s / 5.85 s | 4.43 s / 6.11 s |

**About +0.2 s on desktop (+4% of test time) and +0.1 s on wasm.** The warmup is bounded by the very
cost it is warming, so eight rows of 100 000 calls at ~500 ns is under a second by construction. The
wasm rows are wall clock because that runner reports 0.0 s per class in its XML.

Test counts are unchanged throughout: **desktop 360/0/1, wasmJs 344/0/0**, on every run quoted here.

**Load.** 1.14 at the start, 1.6–3.8 for every run quoted, `uptime` checked before each batch. No
other agent was running and no worktree was created during this pass, so the Spotlight problem the
previous section had did not arise. Two readings taken while load was transiently 6.25 (back-to-back
Gradle invocations) were timing measurements only, and are not among the per-call figures.

### What is deliberately not in the shared table

Each platform-only file above measures something the others cannot, and none of it collapses into
the four shared columns:

- **Desktop**: the Panama `invoke` → `invokeExact` migration (`DesktopOverheadBenchmark`,
  1015.95 ns → 2.65 ns for `PyList_Size`) is a desktop-only story — no other platform has an
  equivalent adaptation-vs-exact distinction.
- **Android/ART**: `JniOverheadBenchmark`'s ordinary/`@FastNative`/`@CriticalNative` comparison and
  `CompositionBenchmark`'s per-call-vs-composed figures are both `artMain`/`androidMain` concepts
  with no analogue on a target that has no separate calling convention to choose between.
- **wasmJs**: `WasmMarshallingOverheadTest`'s interned-vs-scratch-vs-malloc string routes are a
  wasm-linear-memory concept; the other platforms marshal strings through Panama or JNI, not through
  an explicit intern cache.

These are not merged into the shared table, and the files are not merged or deleted — the request
for this pass was a table, not a consolidation, and each file's platform-specific framing (its own
warmup counts, its own comparison baselines) is part of what makes its own number meaningful.

## A build defect that made all of this harder

Three separate instrumented runs failed with `UnsatisfiedLinkError` on symbols that were
demonstrably present in the freshly linked `.so`. The cause was in `build.gradle.kts`:

    linkTaskProvider.configure {
        copy { from(outputFile); into(...) }   // runs at CONFIGURATION time
    }

A bare `copy {}` inside a `configure {}` block executes while Gradle is configuring the build,
not when the link task runs — so every build staged the *previous* build's library into
`jniLibs`. Any change to the `.def` or to native sources needed two full builds before it
reached the device, and in between the failure looked exactly like a code bug. Moved into
`doLast`, so it stages what the link actually produced.
