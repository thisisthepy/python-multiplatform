# 다운콜 설계 — Kotlin → Python

Kotlin 코드가 CPython Stable ABI 함수를 호출하는 방향. 반대 방향은
[`upcall.md`](upcall.md).

> **현재 상태 (2026-10-03 기준, 코드로 확인).** 플랫폼별 다운콜 경로는 각 `EmbedAPI.<platform>.kt` +
> `bindings.kt` 가 직접 구현한다: 데스크톱은 Panama `MethodHandle` + `invokeExact` (포인터는
> `JAVA_LONG`), Android 는 `RegisterNatives` 로 등록한 JNI (함수별·API 레벨별 호출 규약), Native 는
> cinterop. 아래 "shape 트램폴린" 14종은 **어휘(vocabulary)로 구현되어 있으나**(`jvmMain/.../ShapeDowncalls.kt`
> 의 `expect` + 데스크톱·Android·Native 구현) 330개 `actual` 을 `jvmMain` 한 벌로 합치는 통합에는
> **쓰이지 않았다** — 소스에서 shape 함수를 부르는 곳은 데스크톱 `ProxyTypeFactory` 의 `tp_traverse`/
> `tp_free` 호출 두 곳뿐이다. 이 문서의 shape 설계 절은 그 계획과 근거의 기록이다.

## 시그니처 shape 조사 결과

`EmbedAPI.kt` 의 약 330개 `expect` 선언을 전수 분석한 결과다 (조사 시점의 수. 현재 `expect` 선언은 314줄로 센다).

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

**성능 문제는 해결되었다.** 초기에는 호출부 305곳이 `MethodHandle.invoke(...) as Long` 이었고 호출마다
`asType` 변환과 박싱이 일어나 `PyList_Size` 한 번이 약 1016 ns 였다. 포인터를 `ADDRESS` 대신
`ValueLayout.JAVA_LONG` 으로 기술하고 호출부를 `invokeExact` 로 바꿔 **2.65 ns** 가 되었다 (아래
"Fixed: 1015.95 ns -> 2.65 ns"). 현재 `desktopMain/.../bindings.kt` 에는 `invokeExact` 315곳이 있고
`.invoke(` 는 0곳이다 (AGENTS.md §16: 무조건 `invokeExact`). 리플렉션은 핸들을 **만들 때** 심볼당 한 번만
돈다. `Linker.Option.critical()` 은 쓰지 않는다 (`downcallHandle(..., emptyOptions)`).

### Android(ART) — shape 트램폴린 (구현됨, 단 주 경로는 아니다)

ART 는 런타임에 임의 시그니처의 네이티브 호출을 만들 수 없다. 그래서 shape 마다 트램폴린을 **미리
컴파일**해두고, 대상 함수 포인터를 첫 인자로 받는다:

```kotlin
// Kotlin/Native 로 컴파일해 .so 에 넣는다 (NDK 불필요)
@CName("...downcallJ_J")
fun downcallJ_J(fn: Long, a0: Long): Long =
    fn.toCPointer<CFunction<(Long) -> Long>>()!!.invoke(a0)
```

주소는 런타임에 무엇이든 될 수 있고, **고정되는 것은 시그니처 형태뿐**이다.

도달 방법은 두 단계로 계획했다:

| 단계 | 경로 | 상태 |
|---|---|---|
| 1 | 일반 JNI → 트램폴린 → 간접 호출 | **구현됨.** `androidMain/.../bindings.kt` 의 `downcall*` `external fun` 14개가 `jni_onload.def` 에서 등록되고, Kotlin/Native 트램폴린(`nativeMain/.../EmbedAPI.native.kt`)이 함수 포인터를 직접 호출한다. `ShapeDowncalls.android.kt` 가 감싼다 |
| 2 | ART 엔트리 포인트 패치 + `@CriticalNative` → 트램폴린 | **하지 않았다.** `@CriticalNative` 는 API 34+ 에서 오히려 비싸다고 실측됐고(아래), 호출 규약은 함수별로 고르는 것으로 정리됐다 |

실제 Android 바인딩은 shape 를 거치지 않고 **함수마다** `RegisterNatives` 로 등록한 JNI 를 쓴다
(`androidMain/README.md`). shape 트램폴린은 `ffiSymbol` 로 얻은 임의 주소를 부르는 일반 수단으로
남아 있다.

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

우리 `downcallII_I(fn, a0, a1)` 과 같은 구조다. 차이는 **생성 시점**뿐이다 (PanamaPort 는 의존성으로 쓰지
않는다 — AGENTS.md §12.9. 아래는 설계 근거로 읽은 분석이다; [`android-ffm-design.md`](../archive/android-ffm-design.md)):

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

## JVM 통합 — Desktop + Android 를 한 벌로 (계획; 미수행)

계획은 이랬다. 당시 JVM 쪽은 330개 `actual` 이 **두 벌** 있고 플랫폼별 `bindings` 선언까지 더하면 손으로
유지하는 선언이 1,300개에 가까웠다. shape 를 도입하면 플랫폼별 표면이 14개로 줄고 330개 `actual` 은
`jvmMain` 에 한 벌만 남는다:

```
commonMain   expect fun PyList_Size(list: NativePointer): Long

jvmMain      actual fun PyList_Size(list) = downcallJ_J(sym.PyList_Size, list.toRawValue())
             internal expect fun downcallJ_J(fn: Long, a0: Long): Long     ← shape 14개
 ├── desktopMain  internal actual → 캐시된 Panama 핸들
 └── androidMain  internal actual → shape 트램폴린
```

**인터페이스나 추상 클래스를 쓰지 않는다.** `expect`/`actual` 이 같은 일을 하는 언어 기능이고,
인터페이스를 두면 호출마다 가상 디스패치가 붙는다.

실험으로 확인한 절차(함수 하나 `PyList_Size` 로 4개 타깃 컴파일): commonMain `expect` 에서 `inline`
제거 → 플랫폼 `actual` 삭제 → `jvmMain` 에 `actual` 하나 → shape 함수를 `internal expect`.

**현재 상태: 이 통합은 진행되지 않았다.** `jvmMain` 에는 shape 어휘(`ShapeDowncalls.kt`)와 문자열
마샬링 `expect`(`internedUtf8` 등)만 있고, 330개 `actual` 은 여전히 `desktopMain` 과 `androidMain` 에 각각
있다. 이유는 아래 실측들이다: 데스크톱은 `invokeExact` 로 2.65 ns 에 도달해 shape 가 필요 없었고, Android 는
함수별 호출 규약 선택이 shape 단일화보다 비용을 크게 좌우했다. `expect inline fun` + 중간 소스셋 조합의
컴파일러 크래시 제약(AGENTS.md §16)도 그대로 남아 있다.

## GraalVM Native Image

Native Image 는 **FFM 다운콜을 빌드 타임에 등록**해야 한다. `reflect-config.json` 으로는 안 되고,
`RuntimeForeignAccess.registerForDowncall(descriptor, options)` 를 쓰는 등록(여기서는 reachability
메타데이터)이 필요하며, `-H:+ForeignAPISupport` 로 활성화하는 **실험적** 기능이다. 데스크톱이 연결하는
모든 `FunctionDescriptor` 가 그 메타데이터에 선언되어 있는지는 `ReachabilityMetadataTest.kt` 가 지킨다
(SPEC C-2). `JAVA_LONG`/shape 로 시그니처 종류가 한정되는 것이 등록을 단순하게 한다.

이것은 더 이상 "우선순위 낮음"이 아니다 — 업콜이 네이티브 이미지에서 동작해야 한다는 것은 이 저장소의
결정이다 (AGENTS.md §13). 절차와 마지막 검증 기록은
[`graal-native-image-verification.md`](../platforms/graal-native-image-verification.md) (SPEC U-6: 수동 검증,
이미지 자체를 테스트하는 자동화는 아직 없음).

## 스레드 상태

다운콜은 전부 GIL(또는 free-threaded 빌드의 스레드 상태)을 요구한다 — `withGIL { }`
(`python/multiplatform/ffi/GILScope.kt`). [`threading-and-abi.md`](threading-and-abi.md) 를 본다.

---

## 개정: Android 는 shape 트램폴린만으로 풀리지 않는다

위의 shape 설계는 **Desktop 에는 유효하지만 Android 에는 그대로 적용되지 않는다.** 설계 논의에서
드러난 사실과, 그 결과 실제로 택한 방식을 기록한다.

### 과거의 JNI 배선 버그 (수정됨)

초기에는 `@CName` 으로 만든 C 함수(`JNIEnv`/`jclass` 를 받지 않음)를 일반 JNI 메서드처럼 이름으로
연결해서, ART 가 넘기는 `JNIEnv*`·`jclass` 때문에 **인자가 두 칸 밀렸다.** 지금은 `JNI_OnLoad`
(`artMain`)가 `RegisterNatives` 로 모든 `external fun` 을 등록한다 (367개 중 363개; 예외 4개는 의도적 —
ROADMAP §2). 이름 기반 연결은 API 26 에서 런타임을 abort 시키기도 한다. 초안의 진단·표는
[`../archive/downcall-design-android-first-diagnosis.md`](../archive/downcall-design-android-first-diagnosis.md).

**호출 규약은 per-API-level 이면서 per-function 이다.** 전환 비용은 아래 "Measured" 절의 실측을 따른다
(API 34 를 경계로 `@CriticalNative` 와 `@FastNative` 가 뒤집힌다). `@CriticalNative` 는 `RegisterNatives`
로만 등록하고, 정적·원시 타입만, 그리고 JVM 으로 되돌아올 수 없는 함수에만 쓴다.

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

### 조립 단위의 범위 — 정해졌다

경계 통과가 비싸고 뜨거운 호출에는 critical 도 못 쓰므로 **통과 횟수 자체를 줄이는 것**이 접근이었다 —
`bindings` 의 저수준 함수들을 바인더 레벨 연산으로 조립해 `artMain` 에서 내보내고 `androidMain` 은 그
굵은 단위를 한 번 부른다 (`artMain` 의 `asmGetAttr` 등; 조립 단위는 길고 업콜 가능성이 있으므로 일반
JNI, shape 계열은 짧고 콜백이 없으므로 규약을 함수별로 선택).

초안은 세 후보를 두고 결정을 미뤘다(바인더 API 를 `expect`/`actual` 로 올림 / `commonMain` 에 두고 Android
만 별도 경로 / `Python3` 수준만 조립). **택한 것은 어느 것도 아니다**: 조립은 **FFI 층의 `expect`/`actual`**
(`EmbedAPI`)에 두고 객체 모델은 공통으로 둔다 — 객체 모델은 `bindings` 를 참조하지 않는다
(`architecture.md` "Consequence for composed operations", `commonMain/README.md`). 그리고 **문자열을
나르는 연산에서는 조립보다 interning 이 더 싸고 일반적**이라 그쪽으로 갔다
([`marshalling-design.md`](marshalling-design.md)); 조립이 남는 곳은 통과 횟수가 N 에 비례하는 일괄
연산(1000원소 리스트 변환 11배)이다. 데스크톱에서는 조립을 하지 않기로 닫혔다 (ROADMAP §6: 마샬링이
호출의 10.5%).

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
Android, which is backwards from where the users are. The fix is to pick the convention per API
level. The annotation is compile-time, so that means declaring both variants and dispatching on
`Build.VERSION.SDK_INT` — a branch costing ~1-2ns to avoid ~22ns. (It held on hardware, below, and
was then built: `bindings.preferFastNative`.)

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

**Implemented** (`bindings.preferFastNative = SDK_INT >= 34`, `androidMain/.../bindings.kt`; the
crossover is pinned in the next section, and `dispatchPicksTheFasterConventionOnThisDevice` re-measures
it on whatever device runs it).

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

The copy tasks are now wired as dependencies of the assets/jniLibs merge tasks
(`python-multiplatform/build.gradle.kts`, `copyAndroidPythonBinaries` / `copyAndroidPythonAssets`);
I did not verify whether `connectedDebugAndroidTest` now forces the `linkAndroidNative*` tasks too.
If an instrumented run fails with `UnsatisfiedLinkError`, run this first:

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

> The first two tables below are the **pre-fix** desktop numbers (reflective `invoke`); the desktop
> row is corrected by "Fixed: 1015.95 ns -> 2.65 ns" and "Desktop vs Android, corrected" further down.

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
exactly this — until commit 0fae961a (2025-12-20) folded it into a single `commonMain` class.
That commit's real subject was moving `PyAutoCloseable` to per-platform implementations, which was
right and should stand; losing the composition hook was collateral. The resolution was **not** to
restore `expect`/`actual` on `PyObject`: composition lives in the FFI layer (`architecture.md`), and
for string-carrying operations interning replaced it (`marshalling-design.md`).

Windows cannot host a Kotlin/Native assembly library, so a composed desktop path would cover
macOS and Linux only, with Windows staying on direct Panama calls. At 2.65ns per crossing
that is a much smaller loss on desktop than the same gap would be on Android.

## One downcall, across all platforms

The upcall side has §7.1 "One upcall, per platform" in [`upcall.md`](upcall.md#71-one-upcall-per-platform) — one shared
test, run on every target, min–max over several runs, quoted rows marked as quotes. The downcall
side had no equivalent: the benchmarks that exist are real and each one answers a question, but
they are scattered across five files with no shared shape, so "how expensive is a downcall on
platform X" had no single table to read.

### What exists today

| File | Source set | What it measures | Runs where |
|---|---|---|---|
| `commonTest/.../overhead/Benchmark.kt` | `commonTest` | Warmup-then-time harness (`run`/`measure`), not a benchmark itself | wherever the tests below run |
| `commonTest/.../overhead/BenchmarkTest.kt` | `commonTest` | Pointer boxing, raw refcount churn, string marshalling (`PyUnicode_FromString`/`AsUTF8`, three lengths), integer marshalling, `PyObject` wrapper cost, `PyObject_GetAttrString` | compiles for all six run paths below; **confirmed green** on desktop / iosSimulatorArm64 / wasmJsNode when the table was first cut; reaches androidInstrumented and androidNativeArm64/X64 through the same `commonTest` dependency edge but neither ran here (no emulator this session) |
| `desktopTest/.../DesktopOverheadBenchmark.kt` | `desktopTest` | Panama transition cost (`PyList_Size` net of a pure-Kotlin floor); the string-marshalling-share-of-`exec` test is `@Ignore`d (destabilises the interpreter, see the file's own comment) | desktop only |
| `wasmJsTest/.../WasmMarshallingOverheadTest.kt` | `wasmJsTest` | `internedUtf8` vs `scratchUtf8` vs the old malloc/copy/free route, at two string lengths, both standalone and through a real `PyObject_GetAttrString` | wasmJs only |
| `androidInstrumentedTest/.../JniOverheadBenchmark.kt` | `androidInstrumentedTest` | JNI calling-convention transition cost: ordinary / `@FastNative` / `@CriticalNative`, on a trivial echo and on `PyList_Size` | Android/ART only, needs a device or emulator |
| `androidInstrumentedTest/.../CompositionBenchmark.kt` | `androidInstrumentedTest` | Per-call crossings vs one composed `artMain` call, for `getAttr` and `list → array`, plus a direct-buffer marshalling variant | Android/ART only, needs a device or emulator |

Two more files live next to these and share the word "overhead" or "Upcall" in their name but are
**not** downcall benchmarks — noted here only so a future reader does not double-count them:
`commonTest/.../reflection/UpcallOverheadTest.kt` prices the upcall dispatch table
(`UpcallTable.resolve` vs a cached handle), and `androidInstrumentedTest/.../UpcallOverheadTest.kt`
prices the Android upcall attach. Both are upcall-side and already covered by `upcall.md`.

`BenchmarkTest` reaches every one of the six execution paths KGP registers for this module
(`desktopTest`, `iosSimulatorArm64Test`, `iosX64Test`, `wasmJsNodeTest`, `androidInstrumentedTest`/
`connectedAndroidTest`, `androidNativeArm64Test`+`androidNativeX64Test`) because it sits in
`commonTest`, which every one of those depends on transitively. It is confirmed to run — not just
compile — on three of the six this pass: desktop, the iOS simulator, and wasmJs (see Validation
below). `iosX64Test` has no practical run path on Apple Silicon and nobody targets it; the
Android/ART and androidNative rows need a connected device or emulator, which this pass did not
have available.

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

androidNative and Android/ART rows are **quoted from `upcall.md`**, not re-measured — marked
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
> measuring the benchmark" explains in `upcall.md`. The old figures here were 3–7x the warmed
> cost because they were swept at the old `100`-call warmup on desktop (1132–1146 ns cold vs 167–458 ns warm).
> The new column records the current cost at 100,000 warmup, from commit `958c0082b294`. The first three
> columns also come from `UpcallBoundaryCostTest` (already fixed at 100,000 warmup since `7e9c6b8c`), so
> all four columns are now consistent in warmup and source.

### Warmup: what the boundary benchmarks must do

The first version of the shared table was taken with `UpcallBoundaryCostTest`'s 3 000-call warmup,
and its absolute upcall figure then *drifted* between commits (desktop 861 ns → ~690 ns, wasmJs
~490 ns → ~310 ns) without any change to the timed code. The investigation (full record in
[`../archive/downcall-design-benchmark-drift.md`](../archive/downcall-design-benchmark-drift.md))
established:

- The figure was a **same-process warm-up artefact**, not a boundary cost. Anything that pushed
  upcall traffic through the shared JVM / Node process earlier in the suite (a proxy cost test
  starting to install proxies; one commit on wasmJs, `4472f83a`) moved the number by 25% on desktop
  and ~35% on wasmJs. Confirmed by direct experiment (short-circuiting `GeneratedProxyCostTest`
  moved it back).
- It is **host JIT tier-up** (C2 on the JVM, V8 on wasm), not CPython-side state: the pure-Python
  rows (empty loop 8–9 ns desktop; callee 24–27 ns) are flat from the first repetition, only rows
  that cross into host code move. 40 repetitions plateau at ~535 ns (desktop upcall), ~141 ns
  (desktop Kotlin-driven downcall), ~280 ns (wasmJs upcall).
- **Convergence needs 40 000 calls cold and ~70 000 warm, so the warmup is 100 000** (and
  `GeneratedProxyCostTest` 5 000 per row, because it shares `_pm_invoke` across 19 rows). Cost:
  about +0.2 s on desktop (+4% of suite time).
- With 100 000 warmup the "proxy test short-circuited" lever no longer moves desktop (501–530 vs
  510–560 ns) or wasmJs (287–301 vs 290–304 ns) outside run-to-run noise. That is the fix.
- On wasmJs the **absolute** column still does not survive filtering (`--tests` this class alone: pure
  Python rows 55–74% slower, because a short-lived Node process runs CPython-under-V8 colder); the
  **ratio** columns do (2.66–3.13x solo vs 2.91–3.14x in the suite). Quote wasmJs absolutes as
  full-suite figures.
- `inline` on `Benchmark.measure` was tried and is worse (needs ~230 000 iterations for the same
  plateau). Do not retry.
- Rules that follow: a measurement is **full suite, single task, quiet machine** (`uptime` first);
  filtering with `--tests` changes the measured region; record the suite composition with the
  number; never run two measurement sessions concurrently (load 10–12 made one commit read 672–1076 ns).

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
