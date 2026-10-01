# 업콜 설계 — Python → Kotlin

Python 코드가 Kotlin 함수·클래스를 가져다 실행하고, 인스턴스화하고, 상속하는 방향. 반대 방향은
[`downcall-design.md`](downcall-design.md).

무엇을 노출할지는 [`binding-policy.md`](binding-policy.md).

**현재 미구현이다.** 이 문서는 확정된 설계와, 아직 열려 있는 지점을 구분해 기록한다.

## 런타임 리플렉션을 쓰지 않는다

이유가 두 겹이고, 두 번째가 더 근본적이다.

1. **GraalVM Native Image** 는 closed-world 라 런타임 리플렉션이 등록 없이는 동작하지 않는다.
2. **Kotlin/Native 에는 리플렉션이 사실상 없다.** `::class.simpleName` 정도이고 멤버 열거가 안 된다.
   iOS 와 androidNative 는 GraalVM 과 무관하게 애초에 리플렉션 경로가 없다.

멀티플랫폼 라이브러리인 이상 **빌드 타임에 생성한 테이블 말고는 선택지가 없다.**

## 구조

```
Python 사용자 코드        obj.baz(1, 2)
        ↓
프록시 타입              우리가 PyType_FromSpec 으로 만든 힙 타입.
                        인스턴스 데이터에 핸들을 담고 있다.
        ↓
업콜 진입점 (플랫폼당 1개)  invoke(handle, args)
        ↓
Kotlin 함수 테이블        핸들 → 이미 해석된 호출 대상
```

Python 사용자 코드에는 문자열도 정수도 드러나지 않는다.

## 핸들 해석은 Kotlin 이, 캐시는 프록시 객체가

ObjC 가 빠른 이유는 테이블이 있어서가 아니라, **셀렉터가 인터닝된 포인터라 매번 문자열을 찾지 않기
때문**이다. `objc_msgSend` 는 클래스 메서드 캐시에서 몇 개 명령어로 IMP 를 찾아 점프한다.

같은 성질을 이렇게 얻는다:

| 시점 | 하는 일 |
|---|---|
| 최초 1회 | Python 이 이름(`"Bar.baz"`)을 넘기고, **Kotlin 이 테이블에서 해석**해 핸들을 돌려준다 |
| 이후 매 호출 | 프록시 객체가 자기 인스턴스 데이터에 담아둔 핸들을 그대로 넘긴다 |

핵심은 **호출마다 문자열을 넘기면 어떤 테이블 설계로도 ObjC 수준에 도달할 수 없다**는 것이다:

| 방식 | 호출당 비용 |
|---|---|
| 매번 문자열 전달 | `PyUnicode` → UTF-8 변환(할당) + FFI 경계 + 해시 + 문자열 비교 → 수백 ns |
| 최초만 해석, 이후 핸들 | FFI 경계 + 테이블 조회 → 10~20ns 목표 |

테이블 항목은 `KFunction.call` 이 아니어야 한다. `KFunction.call` 은 호출마다 인자 개수와 타입을
다시 검사하고 박싱해 50~100ns 가 든다. JVM 에서는 `MethodHandle`, Kotlin/Native 에서는 생성된 함수
참조를 쓴다. 후자는 컴파일 타임에 직접 호출로 낮아진다.

## 왜 트램폴린이 적어도 되는가

CPython 콜백 규약은 **전부 관련 객체를 인자로 넘긴다.** 헤더에서 확인했다:

| 슬롯 | 시그니처 | 식별 정보 |
|---|---|---|
| `PyCFunction` | `(PyObject *self, PyObject *args)` | `self` |
| `destructor` | `(PyObject *self)` | `self` |
| `getter` / `setter` | `(PyObject *self, void *closure)` | **명시적 closure 인자** |
| `initproc` | `(PyObject *self, args, kwargs)` | `self` |
| `newfunc` | `(PyTypeObject *type, args, kwargs)` | `type` |

즉 **라우팅을 함수 포인터가 아니라 데이터로 한다.** 소수의 공유 트램폴린이 무제한의 Kotlin 대상을
서비스할 수 있고, **런타임 코드 생성(LLVM JIT, libffi 클로저)이 필요 없다.** JPype·PyO3·JEP 가 전부
이 방식이다.

업콜 shape 는 4종으로 수렴하며, 그 4종은 다운콜의 14종에 **이미 포함**된다. 업콜 때문에 추가되는
트램폴린이 없다.

런타임 코드 생성이 실제로 필요해지는 경우는 하나뿐이다 — **컨텍스트 인자가 전혀 없는 순수 C 콜백**
(`qsort` 비교자 등)에 Kotlin 함수 포인터를 넘겨야 할 때. CPython 객체 모델에는 해당이 없다.

## 상속과 인스턴스화

`PyType_FromSpec` 으로 힙 타입을 만들면:

- Python 이 그 타입을 상속하면 `tp_new`/`tp_init` 은 우리 공유 트램폴린을 그대로 물려받는다
- 오버라이드한 메서드는 그냥 Python 함수다
- Kotlin 이 가상 메서드를 부를 때는 `PyObject_CallMethod` 가 일반 MRO 조회를 한다

**새 네이티브 스텁이 하나도 추가되지 않는다.**

## 플랫폼별 진입점

진입점 뒤는 전부 같은 테이블이다.

| 플랫폼 | 진입 방법 |
|---|---|
| Desktop | FFM `Linker.upcallStub` |
| Android | `PyMethodDef` whose `ml_meth` is a C shim in `jni_onload.def`, calling Kotlin/JVM through JNI |
| iOS / androidNative | `PyMethodDef` + `PyCMethod_New` (`@CName` 심볼은 androidNative 에서만 유효) |

원래 여기에는 "iOS 는 Python 과 Kotlin/Native 가 같은 바이너리이므로 `ctypes.CDLL(None)` 으로 `@CName`
심볼에 바로 닿는다"고 적혀 있었다. **측정해 보니 iOS 에서는 양쪽 절반이 모두 성립하지 않는다.**
[아래 표](#what-each-platform-still-owes)에 측정값이 있다. 실제 경로는
`nativeMain` 의 `python.native.ffi.UpcallEntry` 이며, C 글루가 필요 없다는 결론 자체는 유지된다 —
`PyMethodDef` 를 Kotlin/Native 가 직접 채우기 때문이다.

---

## The trampoline that carries arguments

Written for desktop, settled for everyone. `python.multiplatform.ffi.upcall.UpcallTrampoline` is
`commonMain` and depends on nothing platform-specific; what each platform still owes is only the
few lines that give Python the *address* of an entry point.

Before this, the table carried arity and a per-argument `TypeTag` and nothing read them. Both
desktop stubs were `(long) -> long`, so the only entries Python could reach were zero-argument ones
returning a `Long` — Python could call Kotlin but could not pass it anything (ROADMAP §13).

### The shape count is one, and it was already implied

Counted the way the downcall side was counted, and it converges harder: **argument passing needs
exactly one new C shape.**

    long pm_invoke(long callableHandle, PyObject *args) -> PyObject *      // (long, long) -> long

Arity and types travel inside the tuple and inside the table entry, never in the C signature. A
stub specialised per signature would need one per `(arity, tag-vector)` — unbounded, and
impossible to pre-generate for a closed world. This is the same argument as
[왜 트램폴린이 적어도 되는가](#왜-트램폴린이-적어도-되는가), applied one level down.

The remaining CPython slots were already in the vocabulary, which is why the total is one and not
five:

| Slot | C signature | Carrier shape | Status |
|---|---|---|---|
| `PyCFunction` | `PyObject *(PyObject *self, PyObject *args)` | `(long, long) -> long` | **added** |
| `getter` | `PyObject *(PyObject *self, void *closure)` | `(long, long) -> long` | same one |
| `initproc` / `setter` / `traverse` | `int(long, long, long)` | `(long, long, long) -> int` | already there |
| `inquiry` (`tp_clear`) | `int(PyObject *self)` | `(long) -> int` | already there |
| `destructor` | `void(PyObject *self)` | `(long) -> void` | already there |
| name → handle | `long(const char *)` | `(long) -> long` | already there |

`newfunc` (`PyObject *(type, args, kwargs)`) is the only one still unaccounted for, and only if
`tp_new` is ever bound directly rather than reached through `PyCFunction`.

The `PyCFunction` correspondence is deliberate: when the generated proxy type lands, `self` takes
the callable handle's place in the same stub and **no new shape appears then either**.

### What crosses, per tag

The `args: Array<Any?>` an `ExposedCallable` receives has exactly one representation per `TypeTag`,
which is what `python-multiplatform-ksp`'s `TypeShape.castExpression` already narrows from:

| `TypeTag` | Python side | Kotlin side |
|---|---|---|
| `INT` | `int` | `Long` (a declared `Int`/`Short`/`Byte` is narrowed by generated code) |
| `FLOAT` | `float` | `Double` |
| `BOOLEAN` | `bool` | `Boolean` — a distinct Python type, so not folded into `INT` |
| `STRING` | `str` | `String` |
| `BYTES` | `bytes` | `ByteArray` |
| `UNIT` | `None` | `Unit` |
| `OBJECT` | `int` handle, **or** any Python object | the Kotlin instance from `HandleTable`, **or** a `PyObject` |
| — | `None` | `null`, whatever the tag |

`OBJECT` is the one tag that is two things, and it is resolved by what Python actually sent rather
than by the tag: Python cannot hold a Kotlin reference on any target, so a Kotlin object crosses as
an `ObjectReference` integer; anything else is a Python object and reaches a parameter declared as
`PyObject`.

`BYTES` is correct and slow — an item at a time through `PyObject_GetItem`, because
`PyBytes_AsString` is bound here as a NUL-terminated UTF-8 *string* read and destroys exactly the
payloads `ByteArray` exists for. `PyBytes_AsStringAndSize` is in no platform's `EmbedAPI` yet; when
it is, both directions collapse to one call.

### Two conventions that are not negotiable

- **Arguments are borrowed.** `PyTuple_GetItem` lends, so a `PyObject` built over one takes
  `borrowed = true`. `borrowed = false` gives back a reference nobody ever took — one per call —
  and the free lands somewhere unrelated. That is the bug that crashed this repo twice.
- **The result is a new reference.** Python takes ownership. Measured end to end through each
  platform's real entry point (`UpcallEntryTest.theReferenceReturnedToPythonIsTakenOverExactlyOnce`,
  `commonTest`): a hundred calls move the returned object's refcount by zero.

And one that is not about references: **nothing may be thrown out of a trampoline.** The return
path is C; a Kotlin exception crossing a Panama upcall stub terminates the VM and on Kotlin/Native
terminates the process. Every failure leaves as `NULL` with the error indicator set.

### The GIL is not the caller's to promise

`withGIL` skips `PyGILState_Ensure` when the thread's nesting depth is already non-zero. That is
right for Kotlin-side code and **wrong for an entry point from C**, because C may have dropped the
GIL inside a scope that is still open. `ctypes.CFUNCTYPE` does precisely that — it releases the GIL
around the foreign call, unlike `PYFUNCTYPE` — so the first version of this trampoline segfaulted
in `_PyThreadState_GET` (`PyErr_Occurred+0x1c`) on a thread whose own counter said it held the GIL.

A trampoline therefore takes its own `PyGILState_Ensure`/`Release` pair unconditionally. Every
platform's entry point needs this, not just desktop's.

### What each platform still owes

Only the address-publishing step; the marshalling is shared.

| Platform | What is needed | Cost |
|---|---|---|
| **Desktop** | done — `Panama.createUpcallStubII_L`, `UpcallStub.invokeWithArgsStubAddr` | — |
| **iOS / androidNative** | done — `python.native.ffi.UpcallEntry` (`nativeMain`), a `PyMethodDef` whose `ml_meth` is a `staticCFunction` over `UpcallTrampoline.invoke` and whose `self` carries the handle. `UpcallEntryTest` (`nativeTest`) runs it on the iOS simulator and, since `androidNativeArm64Test` exists, on `pmp_api26` and `pmp_api36` as well | cheapest of the three |
| **Android** | done — `python.native.ffi.UpcallEntry` / `UpcallCallbacks` (`androidMain`) plus the `pmp_upcall_*` shims in `artMain/cinterop/jni_onload.def`. `UpcallEntryTest` (`androidInstrumentedTest`) runs it on `pmp_api26` and `pmp_api36` | one shim per shape, plus a JNI upcall per call |
| **wasm** | `@WasmExport` on the entry point plus `Table.set` to publish it, measured at 3.1 ns/call in `wasm-experiment` | already proven |

Nothing in that list touches `UpcallTrampoline`; each is the platform's existing upcall mechanism
pointed at it.

The "Cost" column above is per-platform prose, not a comparison. For one measured next to the others
— desktop, iOS, wasmJs and Android in the same units, from the same test — see "One upcall, across
all five platforms" below. In particular the wasm row's **3.1 ns is the bare `call_indirect`
mechanism with no arguments** and is not what an upcall carrying an argument tuple costs.

### Android's boundary runs the other way round, and `RegisterNatives` is not it

The Android row above used to read "a JNI `static jlong` native method registered with
`RegisterNatives`, and a C shim of `PyCFunction` shape that calls it". `RegisterNatives` binds a
JVM `external fun` to a C function — it is the **downcall** direction, and no arrangement of it
lets C call Kotlin. What Python needs here is the opposite: a `PyMethodDef`'s `ml_meth` has to be a
real C function pointer, and Kotlin/JVM on ART can produce none.

So on Android the entry points are C functions in `artMain/cinterop/jni_onload.def`
(`pmp_upcall_invoke_meth` and the `_pm_resolve` / `_pm_bind` / `_pm_release` trio), and *they* call
Kotlin, with `CallStaticLongMethod` against `python/native/ffi/UpcallCallbacks` — a class looked up
once in `JNI_OnLoad` and held as a global ref. `RegisterNatives` still appears, but only for the
one cold `upcallPublish(long)` that installs the bootstrap into a namespace dict.

That is not a new mechanism. It is exactly the inversion the cycle-collecting proxy type already
makes for `tp_traverse`/`tp_clear` (`ProxyCallbacks`), down to reusing `pmp_attach` — so the third
consequence falls out with it: **a Python worker thread is a bare pthread ART has never seen**.
Every `threading.Thread` is one, so `GetEnv` fails and the shim must
`AttachCurrentThreadAsDaemon`. `UpcallEntryTest.anUpcallArrivesOnAThreadCPythonCreatedRatherThanFailingToFindTheJvm`
runs the upcall inside a `threading.Thread` and asserts the thread it landed on is not the
instrumentation thread, so a pass cannot come from `GetEnv` having succeeded after all.

#### The attach is paid once per thread, and the reason it used to be once per call was wrong

This paragraph used to end "and detach again on the way out. Skipping the detach is not an option —
ART aborts the process when a thread it knows about exits without detaching." The first run of
`UpcallOverheadTest` priced that decision, and it was the most expensive thing on the Android upcall
path by an order of magnitude:

| per upcall | API 26 | API 36 |
|---|---|---|
| instrumentation thread (ART already knows it) | 1307–1695 ns | 5307–6693 ns |
| Python worker thread, steady state | 59712–63293 ns | 19820–31219 ns |
| **the attach/detach pair** | **58405–61598 ns** | **14514–24526 ns** |
| upcall / downcall of the same shape, worker | 44.59–47.93x | 11.69–13.23x |

(Two runs per device, in each table below; where they differ the range is given, because a single
emulator reading is not a measurement.)

The justification does not survive contact with ART. `Thread::ThreadExitCallback`
(`runtime/thread.cc`, same shape in `android-8.0.0_r1` and in `main`) logs a warning on its first
invocation and only reaches `LOG(FATAL)` on a second one — and the only thing that arms a second
invocation is a `pthread_setspecific` in an `#else` branch that Android does not compile, because
on Android it restores `__get_tls()[TLS_SLOT_ART_THREAD_SELF]` instead. Bionic clears a key's value
before running its destructor and never re-reads it, so the callback fires at most once and the
abort is unreachable.

Read, then run: `UpcallThreadAttachTest.aThreadThatExitsWithoutDetachingLeaksItsPeerRatherThanAbortingArt`
attaches a bare pthread as a daemon, lets it exit without detaching, and continues — on both
`pmp_api26` and `pmp_api36`, with no `Native thread exiting without having called
DetachCurrentThread` line in logcat at all.

**The detach is still mandatory, for a different reason.** The same test then asks whether the peer
became reclaimable, and it did not: an attachment that is never given back leaves a
`java.lang.Thread` in the thread list as a GC root, with a recorded stack the pthread has already
unmapped. So the detach moved from call exit to *thread death* — a `pthread_key_create` destructor
(`pmp_thread_exit_detach`), which is the case ART's own warning text points at. It defers to its
second invocation rather than detaching on its first, so it cannot run before ART's own exit
callback whatever order bionic visits the keys in.

Re-measured, same test, same devices:

| per upcall | API 26 | API 36 |
|---|---|---|
| instrumentation thread | 1280–1327 ns | 2982–5086 ns |
| Python worker thread, steady state | **1209–1329 ns** | **2301–3086 ns** |
| worker minus instrumentation thread | −118 to +49 ns | −681 to −2000 ns |
| upcall / downcall of the same shape, worker | 44.59–47.93x → **0.99–1.09x** | 11.69–13.23x → **2.00–2.34x** |

The attach is no longer visible in the steady state at all: on both devices the difference between
a Python worker and a thread ART already knows now straddles zero, i.e. it is inside the run-to-run
spread rather than being a cost. On API 26 an upcall from a Python worker is the same price as a
downcall of the same shape.

> **Both blocks of absolute figures above and below are superseded: `UpcallOverheadTest` now warms
> 100 000 calls, not 3 000, and every number in this section was taken at 3 000.** They are kept as
> the record of how the defect was found, not as current costs, and the rows below are quoted from
> `409da6fc` and its five-suite re-run rather than re-measured here.
>
> **Re-run unchanged over five full suites per emulator, this table's absolute figures have fallen
> a long way and its API 26 conclusion has weakened.** At the time, `UpcallOverheadTest` still warmed
> 3 000 calls, and since `09bf2397` put `commonTest` on the device it ran *after*
> `UpcallBoundaryCostTest` had driven several hundred thousand upcalls through the same ART process,
> so ART's JIT was warm before it started. That is the observation the warmup fix was made from: the
> two rows below differ by a factor of four with **the same warmup and the same emulator**, and the
> only variable was how much ran first.
>
> | re-run, 5 suites each | API 26 | API 36 |
> |---|---|---|
> | instrumentation thread | 1085–1273 ns (was 1280–1327) | 1032–1229 ns (was 2982–5086) |
> | Python worker, steady state | 1249–1334 ns (was 1209–1329) | 982–1219 ns (was 2301–3086) |
> | worker minus instrumentation | **+43 to +249 ns** (was −118 to +49) | **−50 to +12 ns** (was −681 to −2000) |
> | first upcall on a fresh worker | 88 250–242 375 ns | 41 208–611 416 ns |
>
> **On API 36 the claim survives and sharpens** — the gap is −50 to +12 ns, tight around zero.
> **On API 26 it no longer straddles zero**: five runs out of five put the worker above the
> instrumentation thread, by 43–249 ns. That is small against a ~1250 ns call and it does not restore
> the per-call attach this section disproved — the old 44.59–47.93x is long gone — but the honest
> statement for API 26 is now "a worker costs a little more", not "the difference is inside the
> spread". The first upcall on a fresh worker remains enormous and enormously variable, which is
> where the attach actually shows up. Read these rows against each other, not against the boundary
> table's, and see that table's warmup footnote.
>
> **What survives the warmup change and what does not.** The *differences* and *ratios* here are
> within-run comparisons — worker against instrumentation thread, upcall against downcall, both
> measured in the same process at the same warmup — so raising the warmup moves both sides together
> and they are the quotable quantities, exactly as they were for wasm in `7e9c6b8c`. The absolute
> nanosecond columns are not: they were read three tiers early and are expected to fall when this
> section is re-measured at 100 000. The structural assertion the test actually enforces — one ART
> thread per Python worker, not one per call — is a count and is unaffected by either.

**The first upcall on each worker still pays the attach in full** — 56–142 µs, larger than the
per-call charge it replaced — and that is the honest shape of the cost now: once per thread, not
once per call. A worker that upcalls once is no better off than before; a worker that upcalls in a
loop is 20–50x better off.

#### What holding the attachments costs, and why it is not a new risk

Keeping a thread attached makes ART's collector responsible for it: the peer is a GC root, the
thread carries a JNI local reference table, and every `SuspendAll` walks the thread list. So "what
if there are hundreds of Python workers?" is a fair question to ask of this change.

It is largely the wrong question, because **peak simultaneous attachments do not change.** Under
the per-call scheme every worker that is upcalling at a given instant is attached at that instant;
what the destructor changes is how long each attachment is *resident*, not how many can exist at
once. The ceiling either way is the number of live Python threads, which the application already
pays for in pthread stacks.

What does change is that an attachment now outlives the call, so the release has to be reliable.
`UpcallThreadAttachTest.manyConcurrentWorkersAreEachAttachedOnceAndAllReleased` holds 32 Python
workers on a `threading.Barrier` so that all 32 are attached simultaneously, checks each one sees a
single ART thread across the barrier and that no two share one, and then requires every peer to
become reclaimable once the workers finish. Green on both devices.

The instrument for that matters as much as the result. It reads GC reachability of the peer, not
`ThreadGroup.enumerate`, because the first version did use `enumerate` and it is **unusable on API
26**: it does not report the peer of an attached native thread even while that thread is running and
upcalling, so every release assertion passed while measuring nothing. A positive control — assert
the peer is *un*reclaimable while its worker is deliberately parked mid-upcall — is what caught it,
and it is kept in the test for that reason.

The GIL rule is unchanged and is what makes this safe: CPython holds the GIL when it calls a
`PyCFunction`, so the C shims take nothing, and `UpcallTrampoline` still takes its own
`PyGILState_Ensure`/`Release` pair unconditionally on the Kotlin side. `PyGILState_Ensure` is bound
as **ordinary JNI** on Android (`PyGILState_EnsureN`), which is what keeps a thread blocked on the
GIL from stalling the JVM collector — a `@FastNative` or `@CriticalNative` binding of it would
deadlock the two runtimes against each other the moment upcalls exist. `androidMain/README.md`
step 3 says this in the abstract; this is the path that makes it concrete.

Cost, which the row's "one shim per shape" understated: one JNI upcall per call, plus an attach the
*first* time Python calls from a thread it created. Both are now measured — see the table above and
`UpcallOverheadTest`. Neither is on the marshalling path.

### One upcall, across all five platforms

Everything above prices Android, because Android had a question the others do not: whether the
attach a Python worker needs is paid once per thread or once per call. That left the upcall path
measured on exactly one target, while the downcall path has been comparable across all of them since
`overhead/BenchmarkTest` — so "is the upcall expensive?" had no answer that could be given per
platform.

`UpcallBoundaryCostTest` (`commonTest`) is `UpcallOverheadTest` with the attach half removed: same
loop shape, the same three baselines measured in the same run (plus the two controls the next heading
introduces), and the same printed-not-asserted policy. The one thing it no longer inherits is the
warmup count — it ran 10 000 iterations after 3 000 of warmup, copied from the Android test, and
3 000 turned out to be the defect rather than a detail. It is now 10 000 after **100 000**. It sits
in `commonTest` because every seam it needs is already common — `bindUpcallOrNull` (the per-platform
binding step `UpcallEntryTest` introduced), `UpcallTrampoline`, and the `expect` C API — so one copy
runs on every target.

> **This table is hand-cut and is a record of the round it was taken in.** The same rows, taken by
> a harness that runs all six targets from one command and writes the conditions next to the
> numbers, are in [`cost-table.md`](../investigations/cost-table.md) — re-cut with
> `./benchmarks/cost-table.sh --runs 3` on a quiet machine. Prefer that one when the two disagree:
> it records the commit, the warmup, the load average and the device identity of its own cut, and
> this one cannot, because those were transcribed by hand and only partly.

| Platform | upcall | downcall, same shape | trampoline alone | upcall / downcall | upcall / trampoline |
|---|---|---|---|---|---|
| **desktop** (JVM 21.0.12, macOS arm64) | 542.84–576.45 ns | 260.27–265.81 ns | 170.44–247.42 ns | 2.04–2.20x | 5.54–6.46x |
| **wasmJs** (Node) | 302.41–310.72 ns | 96.53–100.95 ns | 149.50–164.88 ns | 3.07–3.14x | 2.39–2.46x |
| **iOS simulator** (iOS 26.2, arm64) | 2253.20–2312.97 ns | 1585.46–1655.40 ns | 2794.42–2843.16 ns | 1.39–1.42x | 1.14–1.15x |
| **androidNative** (`pmp_api36`, arm64) | 3348.75–3443.23 ns | 2194.26–2365.61 ns | 3957.01–4069.40 ns | 1.45–1.54x | 1.19–1.20x |
| **androidNative** (`pmp_api26`, arm64) | 3234–3310 ns † | 2470–2554 ns | 2731–3228 ns | 1.28–1.32x | 1.02–1.20x |
| **Android ART** (`pmp_api36`, arm64) | 995.61–1154.10 ns ‡ | 804.27–838.60 ns | 1100.87–1167.17 ns | 1.18–1.43x | 1.10–1.29x |
| **Android ART** (`pmp_api26`, arm64) | 1196.52–1272.28 ns ‡ | 1253.41–1295.79 ns | 1800.86–1863.97 ns | 0.92–1.01x | 1.05–1.18x |

_For rows 1–4 and 6–7: Source is `docs/investigations/cost-table.md` (commit `958c0082b294`), `UpcallBoundaryCostTest` with warmup 100,000, min–max over 3 runs per target (except desktop, wasmJs, iosSimulatorArm64 and androidNativeArm64 cut from cut-hostless, artApi36 from cut-api36, artApi26 from cut-api26 — see that document's "Conditions" table for per-cut details)._

**Prior values (warmup 3,000):** desktop 510–560 ns, wasmJs 290–304 ns, iOS simulator 2266–2301 ns, androidNative pmp_api36 3228–3275 ns, androidNative pmp_api26 3234–3310 ns, ART pmp_api36 944–1063 ns, ART pmp_api26 1146–1233 ns.

† androidNative `pmp_api26`: This row was re-measured to 100,000 warmup but does not appear in `cost-table.md` (only `androidNativeArm64` on `pmp_api36` is there). **Still awaiting re-measurement** under the same conditions as the cost-table harness.

‡ **Android ART rows (`artApi26`, `artApi36`):** Marked "failed" in cost-table.md. Those failures are in `PhantomCleanerRegistryTest` (reference-release path), not the benchmark rows themselves. The benchmark measurements of boundary cost are valid. Failures were fixed in `c180c46f`.

**Every row is now measured at a warmup large enough to mean anything; getting there is why the table
was re-cut twice.** `UpcallBoundaryCostTest` warmed 3 000 calls per row
and every figure it had ever published was taken before the host JIT had finished tiering up, so the
number it printed was a function of how much upcall traffic the *rest of the suite* had pushed
through the same process first. The warmup is now 100 000, chosen from a measured convergence point
rather than picked; the counts, the sweep behind them and the check below are in
[`downcall-design.md`](downcall-design.md) under "The benchmark was measuring the benchmark".

**These desktop and wasmJs figures agree between a full suite and a suite with the boundary's biggest
other consumer removed** — the exact comparison that used to break them. Running the whole suite with
`GeneratedProxyCostTest` short-circuited, so it drives none of its ~270 000 calls, moves desktop's
upcall row from 510–560 ns to 501–530 ns and wasmJs's from 290–304 ns to 287–301 ns; both overlap.
Under the old 3 000-call warmup the same lever moved desktop from 674 ns to 537 ns and wasmJs from
322 ns to 292 ns. **13 desktop runs and 12 wasmJs runs of the full suite, 3 of each with the lever
pulled.**

A desktop `--tests`-filtered run of this class alone reads 506–550 ns, inside the full-suite band, and
its pure-Python control rows are identical to the full suite's (8.4–8.6 ns against 8.4 ns). **wasmJs
is the exception and it is not fixable from inside this test**: filtered to this class alone it reads
321–353 ns, but its *pure-Python* rows — an empty Python loop and a Python callee, with no boundary
anywhere in them — read 23.1 ns and 83.1 ns against the full suite's 15.0 ns and 47.2 ns. CPython is
itself a wasm module there, so a short-lived Node process runs the interpreter's own bytecode 55–74%
slower and inflates numerator and denominator alike. Tripling the warmup to 300 000 does not move it,
which is what says the residual is host-lifetime-bound rather than call-count-bound. **wasmJs's ratio
columns survive the filtering (2.66–3.13x filtered against 2.91–3.14x in the suite); its absolute
columns are a full-suite figure and are only meaningful as one.**

**The five device configurations were checked for the same effect and none of them has it, so wasmJs
remains the only exception.** Three filtered runs of this class alone against the five full-suite
runs above:

| filtered vs full suite | upcall, filtered | upcall, full suite | empty Python loop | pure-Python callee |
|---|---|---|---|---|
| iOS simulator | 2320–2322 ns | 2266–2301 ns | 9.9–11.4 vs 9.9–10.3 ns | 36.8–38.0 vs 36.8–38.3 ns |
| androidNative `pmp_api36` | 3275–3294 ns | 3228–3275 ns | 17.21 vs 17.22 ns | 48.9–58.2 vs 48.9–74.2 ns |
| androidNative `pmp_api26` | 3245–3280 ns | 3234–3310 ns | 20.0–23.6 vs 20.0–20.6 ns | 51.7–55.3 vs 51.7–62.0 ns |
| ART `pmp_api36` | 996–1031 ns | 944–1063 ns | 17.2–19.3 vs 17.2–22.8 ns | 49.0–60.0 vs 49.2–63.3 ns |
| ART `pmp_api26` | 1118–1171 ns | 1146–1233 ns | 20.0–20.1 vs 20.0–21.5 ns | 51.7–63.4 vs 51.7–65.6 ns |

**The control columns are the test.** On wasmJs the pure-Python rows moved 55–74% under filtering,
which is what proved the whole interpreter had slowed down; here they are unchanged everywhere, to
the last significant figure on `pmp_api36`'s empty loop (17.21 against 17.22 ns). The upcall columns
overlap their full-suite bands in four of five cases and miss by 1% on iOS (2320–2322 against
2266–2301). **So the device rows' absolute values are quotable as absolute values**, unlike wasmJs's,
and the reason is structural: on these five, CPython is native code the host is merely executing, not
a wasm module the host must itself compile.

‡ **Each platform threw exactly one outlying run out of eleven, and both are quoted here rather than
dropped**, for the reason `pmp_api26`'s row below is kept — the min–max convention exists to show
when a host's spread is larger than the effect. Desktop's outlier is confined to the GIL-held
trampoline row: 10 of 11 runs sit at 76–91 ns and one read 186.81 ns, which is the single run behind
that ratio column's other value, 2.89x against 5.62–7.02x for the rest. wasmJs's is the opposite
shape — one run inflated *every* boundary row at once (upcall 432.55 ns, downcall 144.64 ns,
trampoline 264.44 ns) while the empty GIL scope stayed at 24.67 ns; the eleven other runs give the bands
above and three deliberate re-runs immediately afterwards read 298.27, 301.73 and 304.47 ns. **Its
ratio column was unaffected — 2.99x, inside the band** — because numerator and denominator moved
together, which is the case for preferring the ratio on that target.

**Every device row has now been re-measured at the 100 000 warmup.** No row in this table is a
3 000-warmup reading any more, so the ¶ and † marks that carried that caveat are gone from it. The
three Kotlin/Native rows barely moved; the two ART rows moved a great deal. That split is the
finding.

| re-measured at 100 000 | upcall, old → new | downcall, old → new |
|---|---|---|
| iOS simulator (26.2, arm64) | 2263–2502 → **2266–2301 ns** | 1599–1826 → **1599–1610 ns** |
| androidNative `pmp_api36` | 3339–3598 → **3228–3275 ns** | 2170–2222 → **2155–2229 ns** |
| androidNative `pmp_api26` | 3314–4140 → **3234–3310 ns** | 2636–5554 → **2470–2554 ns** |

Every new band sits inside or just below its old one, and every one is narrower — `pmp_api36`'s
downcall column is the extreme case, moving from 2170–2222 to 2155–2229, i.e. not moving at all.
Compare what the same change did to the hosts with a JIT: desktop's upcall row fell 674 → 537 ns,
wasmJs's 322 → 292 ns, and ART `pmp_api36`'s 2301–3086 → 944–1063 ns. **The three Kotlin/Native
targets did not fall; all three JIT hosts did.** That is what the tier-up diagnosis predicts rather
than merely permits, and it is the check that separates the two explanations. The defect `7e9c6b8c`
fixed was real, and it is a property of the host's compiler, not of the boundary.

**But "did not fall" is not the same as "needed no warmup", and the sweep below says so.** It would
be easy to read the flat Kotlin/Native rows as meaning 3 000 was always enough there. It was not:
from cold, androidNative's first 10 000 upcalls read ~1.3x the plateau. The old figures landed near
the plateau anyway because the rest of the suite had already driven the boundary before this test was
timed — which is the *same* dependence on what ran first, just with a smaller amplitude. The old
Kotlin/Native rows were right by luck rather than by margin, and that is why they are replaced rather
than kept. The old ART rows were not right at all.

> **`7e9c6b8c` raised the warmup in `UpcallBoundaryCostTest` and nowhere else. That has since been
> carried to the two benchmarks that were left behind.** The observation was this: in that round's
> ART logcat, alongside this file's `warmup: 100000`, `UpcallOverheadTest` printed `warmup: 3000`,
> and its API 36 instrumentation-thread row read 1032–1229 ns where `409da6fc` recorded
> 2982–5086 ns — **at the same warmup count and on the same emulator.** Nothing about that test had
> changed; what changed is that `09bf2397` put the whole of `commonTest` on the device, so
> `UpcallBoundaryCostTest`'s own several hundred thousand upcalls ran *before* it and left ART's JIT
> warm. That is precisely the "the number is a function of what ran first" failure this section
> documents, in a test the table does not quote but other sections do.
>
> `UpcallOverheadTest` now uses 100 000 for both its Python-driven loops and its Kotlin-driven
> downcall rows — the latter were on `N / 4`, i.e. 2 500 — and `overhead/BenchmarkTest`, which runs on
> all six targets, now uses 100 000 uniformly in place of a mix of 100 and `Benchmark`'s 1 000
> default. The value comes from the sweep immediately below: ART `pmp_api36` is the tightest path
> measured and only reaches its plateau at ~90 000–100 000.
>
> **The coroutine/proxy `warmup: 5000` is not an instance of this defect and is deliberately left
> alone.** `GeneratedProxyCostTest` warms nineteen rows before it times any, so the shared
> `_pm_invoke` path receives 19 x 5 000 before the first measurement — which is past the same knee.
> `7e9c6b8c` justified that number when it set it; it is not a leftover.

#### Is 100 000 enough on a device? Swept, not assumed — and on one target it only just is

`WARMUP`'s doc justifies 100 000 from a sweep run on desktop and wasmJs only (40 000 cold and 70 000
warm on desktop, 70 000 on wasm). Devices are slower, so the number was extrapolated rather than
checked. Sweeping them the same way — 40 consecutive reps of 10 000 calls, from cold, nothing warmed
before the first — gives the calls-to-plateau below. The first-rep ratio is that rep divided by the
mean of reps 21–40.

| target | rep 1 / plateau, Python-driven upcall | flat from | margin at 100 000 |
|---|---|---|---|
| iOS simulator | 1.01x | ~10 000 | 10x |
| androidNative `pmp_api36` | 1.27x | ~40 000 | 2.5x |
| androidNative `pmp_api26` | 1.30x | ~30 000 | 3.3x |
| ART `pmp_api26` | 1.93x | ~20 000, noisily | 5x |
| **ART `pmp_api36`** | **8.56x** | **~90 000–100 000** | **~1x — none** |

**`pmp_api36`'s ART row is the one to act on.** Its sweep runs 9194.8, 3218.9, 2288.7, 1648.3,
1218.3, 1173.3, 1129.5, 1532.7, 1057.8, 1044.6 ns, against a plateau of 1074.2 — so it is still 9%
high at 70 000 calls and only arrives around 90 000–100 000. **The constant is not wrong there, but it
has no margin**, and a slower device, a colder emulator or a busier host would read the boundary
before it settles. Its Kotlin-driven rows need less: the downcall is flat from ~80 000 and the
GIL-held trampoline from ~50 000, so the Python-driven upcall sets the requirement, as it does
everywhere else.

The two control rows behave on every device exactly as they did on the two hosts, which is what says
the sweep is measuring the boundary rather than the interpreter: the empty Python loop and the
pure-Python callee are flat from the very first 10 000 calls on all five device configurations
(iOS 10.5 → 10.2 ns and 37.9 → 38.0 ns; ART `pmp_api36` 17.3 → 17.3 ns and 49.7 → 50.4 ns).

**`UpcallBoundaryCostTest`'s own number is not changed here.** Raising it is a change to a benchmark
and would invalidate the table it was measured for; this pass was a re-measurement. What the sweep
establishes is the fact needed to decide: 100 000 covers every device path measured, and covers
`pmp_api36` on ART by approximately nothing.

**This sweep is what set the value for the two benchmarks that were still on the old warmup.** ART
`pmp_api36`'s ~90 000–100 000 is the largest requirement of the six configurations, so it is the one
that fixes the constant, and it is why `UpcallOverheadTest` — which runs only on ART, i.e. only on
the tightest host — starts at 100 000 rather than at desktop's ~70 000. The desktop sweep of
`overhead/BenchmarkTest` agrees independently: its worst row, `PyUnicode_AsUTF8 (8 chars)`, reads
557 ns at a 40 000 warmup and 78–84 ns at 70 000 and above, a 7x step in one interval, so 70 000 is
the desktop knee there too and 100 000 is that with margin.

Ranges are min–max over five runs of the whole suite (four for wasmJs); a single reading is not a
measurement. **The two ART rows are no longer quoted from another test.** They used to be — the †
mark meant "taken from `UpcallOverheadTest` via commit `409da6fc`", which is why three of their five
columns read *not recorded*. Since `09bf2397` put `commonTest` on the device, `UpcallBoundaryCostTest`
itself runs on ART, so all five columns are now filled from the same test, the same run and the same
thread as every other row in the table. The two ART rows are two different emulators, not two runs of
one, and so are the two androidNative rows — the same two emulators, in fact, which is why the ART
and Kotlin/Native figures for one API level can be read against each other.

**Those ART rows are measured on the instrumentation thread, and that is now the right thread to
measure.** The old † rows quoted the *Python worker thread, steady state* figure instead, because a
worker is the thread ART has never seen and has to attach. The section above is what makes the
instrumentation thread the fair choice: with the attach amortised, the two are close enough that
choosing between them does not decide the row. This round's `UpcallOverheadTest`, re-run five times
per emulator, puts the worker −50 to +12 ns from the instrumentation thread on API 36 and +43 to
+249 ns on API 26 — so on API 26 the instrumentation thread is now the *cheaper* of the two by a
little, and the ART row above is that much optimistic against a `threading.Thread` caller. The first
upcall on a fresh worker is still expensive (88 250–611 416 ns across the two devices) and is still
the number to quote for attach, not for a call.

**What replacing them changed, and it is not small on API 36.** The old † row said 2301–3086 ns; the
same target now reads 944–1063 ns. API 26 barely moved — 1209–1329 → 1146–1233 ns. The asymmetry is
the tier-up story again, and this time on the target that most nearly settles it: **ART has a JIT, so
it belongs with desktop and wasmJs rather than with the three ahead-of-time rows, and it moved like
them.** Every host in this table with a JIT fell when the warmup was raised; no host without one did.

※ **`pmp_api26`'s androidNative row is noisy and is left noisy — but it is a different single-run
outlier each time.** In the 3 000-warmup round, one of five runs read 5554 ns for the downcall and
4863 ns for the trampoline where the other four sat at 2636–3245 and 2843–3672, widening its ratio
columns to 0.62–1.34x and 0.71–1.20x. In the 100 000-warmup round that outlier is gone — the downcall
column tightened to 2470–2554 ns — and a new one appeared in a different row: run 5's GIL-held
trampoline read 3227.87 ns where the other four sat at 2731–2761, and that one run is the whole of
what widens the `upcall / trampoline` column from 1.16–1.18x to 1.02–1.20x. Neither is dropped,
because the min–max convention here exists precisely to show that an emulator's spread can be larger
than the effect being measured. **That the outlier moves rows between rounds is the point**: it is
the host, not the row. Read `pmp_api36`'s row for the shape and `pmp_api26`'s for how much confidence
an emulator supports.

**The history that produced the two roman rows.** This table's original absolute figures were shown
to depend on the rest of the suite rather than on the commit: desktop's 861–1313 ns and wasmJs's
703–1075 ns both fell on re-measurement, in each case with the downcall column standing still. On
wasmJs the cause was pinned to one commit (`4472f83a`) and demonstrated — it made generated proxies
installable there, so `GeneratedProxyCostTest` went from driving zero upcalls to driving ~270 000 of
them through the same Node process *before* `UpcallBoundaryCostTest` is timed, and short-circuiting
that one test put the figure back. Desktop moved for a repository-located reason too, in a different
and non-overlapping window.

That was diagnosed but not fixed, so the numbers stayed unusable for two more passes. It is fixed
now, and the fix is the warmup count: the same lever no longer moves either platform's figure. The
bisections, the controlled experiment, the 40-rep sweep that identified host JIT tier-up as the
mechanism and ruled CPython-side state out, and the cost of the change are all in
[`downcall-design.md`](downcall-design.md) under "Ratio consistency" and "The benchmark was measuring
the benchmark".

**The androidNative row was empty because the target had no test *run* task**, only
`androidNativeArm64TestBinaries` — KGP registers an execution task only where it knows how to reach
a host (`KotlinNativeTest` for the build machine, `KotlinNativeSimulatorTest` for simctl), and an
Android device is neither. `commonTest` compiled for the target on every build and had never once
been executed on it.

`:python-multiplatform:androidNativeArm64Test` (see `build.gradle.kts`) is that missing task. It
pushes `test.kexe` to `/data/local/tmp`, pushes the CPython prefix beside it because
`Py_Initialize()` aborts the process rather than failing without a standard library, runs the binary
under the Kotlin/Native runner's TeamCity logger, and parses the service messages back into JUnit XML
under `build/test-results/androidNativeArm64Test/` so this target is counted the same way as every
other. With no serial given it runs on every connected device whose ABI matches, which is where the
two rows above come from. The figures are five runs of the whole 252-test suite on each.

**Nothing new broke when the suite finally ran there** — 252 tests, 0 failures, on both emulators,
in all five runs. That is worth stating because the precedent pointed the other way: ROADMAP §11b
attached this same suite to Android/ART for the first time and it died at the 2nd test and again at
the 12th, surfacing two real defects. androidNative shares `nativeMain` with iOS, and iOS has been
running the suite all along, so the shared code was already exercised; what had never been exercised
was `artMain` and the androidNative `cinterop` bindings, and those came up clean.

#### The trampoline column needed a control before it meant anything

"Upcall minus trampoline is the boundary" is only true if the two differ by the boundary alone, and
in the obvious arrangement they do not. `UpcallTrampoline` takes its own `PyGILState_Ensure`/
`Release` pair **unconditionally** — see "The GIL is not the caller's to promise" — so driving it
from a bare Kotlin thread pays a real GIL acquisition per call, while Python drives it already
holding the GIL. The first run made that concrete: on iOS the trampoline alone came out *more*
expensive than the whole upcall through it, i.e. the boundary priced negative (0.77–0.81x).
**Re-measuring at the 100 000 warmup reproduces it, and on every device configuration rather than
just iOS**: five runs each give 0.79–0.82x on the iOS simulator, 0.82–0.85x and 0.77–0.78x on the two
androidNative emulators, and 0.86–0.95x and 0.65–0.70x on the same two under ART. The negative price
is a property of the unconditional GIL pair, not of the old warmup — and the reason it never shows on
desktop or wasmJs is visible in the table below: their GIL scope is 24–57 ns against 276–1221 ns on a
device, so the uncontrolled acquisition is a rounding error there and the dominant term here.

So it is measured twice, differing in exactly that one thing, with an empty `Python3.withPython { }`
beside them for scale:

| | desktop | wasmJs | iOS simulator | androidNative `pmp_api36` | androidNative `pmp_api26` | ART `pmp_api36` | ART `pmp_api26` |
|---|---|---|---|---|---|---|---|
| trampoline, caller holding nothing | 164–181 ns | 146–164 ns | 2794–2862 ns | 3789–3893 ns | 4174–4267 ns | 1093–1148 ns | 1693–1822 ns |
| trampoline, GIL already held | 76–91 ns ‡ | 122–129 ns | 1962–1990 ns | 2699–2760 ns | 2731–3228 ns ※ | 793–907 ns | 1012–1087 ns |
| `Python3.withPython { }`, empty | 50–57 ns | 24–28 ns | 720–731 ns | 932–1002 ns | 1112–1221 ns | 276–303 ns | 498–549 ns |

The **GIL-held** row is the one the table above uses, because it is the one the Python-driven
numerator is comparable with.

All seven columns are now at the 100 000 warmup. The re-measurement changed these
rows by more than it changed the headline: desktop's empty `withPython` scope went from 124–152 ns to
50–57 ns and wasmJs's from 79–81 ns to 24–28 ns, because a GIL round trip is the cheapest thing in
the report and therefore the thing an unwarmed loop overstates most. **This is why the old table's
"boundary priced negative" problem looked worse than it was** — one of the two rows being subtracted
was further from its steady state than the other.

The ordering also flipped on wasmJs and the flip is real: its GIL-held trampoline (122–129 ns) is now
*more* expensive than desktop's (76–91 ns) even though its empty GIL scope is half the price
(24–28 ns against 50–57 ns), so what wasm pays for is the trampoline body rather than the GIL.

androidNative is the second target to make the case for this control on its own: without it the
boundary prices negative there too (0.86–0.88x on `pmp_api36`, 0.68–0.86x on `pmp_api26`), for the
same reason as iOS and not because anything about the boundary differs. The gap between its two
trampoline rows — roughly a microsecond — is one uncontended GIL round trip on an emulator, and it
is charged to downcalls exactly as much as to upcalls.

#### Where the differences actually come from

**iOS has the highest absolute upcall of the three originally measured here and the lowest ratio,
and neither is about the boundary.** (androidNative, added later, is higher still on both counts of
absolute cost — see below; the explanation is the same one, which is the point.)
There is no runtime boundary on that target at all — a `PyMethodDef` whose `ml_meth` is a
`staticCFunction` in the same binary. What is expensive is the per-call scaffolding every C API call
shares: an empty `withPython` scope costs 725–982 ns there against 50–57 ns on desktop and 24–28 ns
on wasm, and `Py_IncRef + Py_DecRef` — two calls, two GIL scopes, no marshalling — costs 1551–2032 ns
against 110–120 ns and 44–49 ns. (iOS's own figures are the old unwarmed ones and will come down when
that target is re-measured; the gap is wide enough that the conclusion survives, but the multiple is
not quotable.) The denominator is inflated by the same thing as the numerator,
which is exactly why the ratio comes out near 1: an iOS upcall is barely more than an iOS downcall,
and both are expensive for a reason that has nothing to do with upcalls. This is where the work is
if the iOS number is to move, not in `UpcallEntry`.

**Desktop is the opposite shape.** Its trampoline with the GIL held is 76–91 ns, and the
Python-driven figure is 510–560 ns, so roughly 420–480 ns per call is the Panama upcall stub plus the
`ctypes` shim in front of it — a real boundary, and the largest one measured here in *relative*
terms (5.62–7.02x). Both halves came down when the warmup was fixed and the *ratio went up*, because
the trampoline row had further to fall than the Python-driven one; the shape of the conclusion is
unchanged and its size is larger than previously recorded. One caveat is genuine and is not measured
away: on desktop `_pm_bound` is a
Python `lambda *a: _pm_invoke(_pm_h, a)` over a `ctypes.CFUNCTYPE`, where the other targets bind a
`PyCFunction` directly. Desktop therefore pays one extra Python call (26.7–27.2 ns, printed as the
pure-Python callee) plus ctypes' own argument conversion inside every figure in its row. **Desktop's
row is an upper bound on its boundary cost**, not a measurement of the boundary alone.

**wasm's 3.1 ns is not this number, and was never claiming to be.** The "already proven" row in the
platform table quotes `wasm-experiment`'s `call_indirect` figure, which is the *mechanism* with no
arguments. The argument-passing path measured here is 290–304 ns, of which 122–129 ns is the
trampoline; the remaining ~170–180 ns is the Python-side callable plus the crossing with a real
argument tuple. Two orders of magnitude between the two is not a contradiction — they measure
different things — but the 3.1 ns figure must not be quoted for an upcall that carries arguments.

**wasm's scaffolding is the cheapest and by far the most stable of the three** (24.47–27.52 ns for an
empty scope across eleven runs), which follows from there being no OS thread machinery under it. That
stability is within one suite scope, though, and does not extend across scopes: the same row reads
34–37 ns when this class is run on its own, for the process-lifetime reason the table's note gives.

**androidNative is iOS's shape, moved onto an emulator.** It has the highest absolute upcall
measured here (3.3–4.1 µs) and, on `pmp_api36`, a ratio of 1.52–1.62x — and neither figure is about
the boundary, because on this target there is no boundary either: the same `nativeMain`
`PyMethodDef` whose `ml_meth` is a `staticCFunction` in the same binary. What it shares with iOS is
the reason both are expensive: an empty `withPython` scope costs 937–1010 ns here against 50–57 ns
on desktop, and `Py_IncRef + Py_DecRef` costs 2010–2132 ns against 110–120 ns. (As with iOS, the
androidNative side of both comparisons is an old unwarmed reading.) The per-call
scaffolding is the whole story on both native targets, and it is where the work is if either number
is to move.

Two differences from iOS are worth keeping separate from that. Its ratio sits *above* iOS's
1.33–1.43x rather than at it, because its downcall denominator (2170–2222 ns) is cheaper relative to
its upcall than iOS's is — the numerator is inflated by scaffolding on both, the denominator less so
here. And these figures are from an emulator on Apple Silicon, not hardware: `pmp_api26`'s spread
(above) is the honest width of that, and no androidNative figure here should be quoted as a
device number.

Nothing in the test asserts a duration. A wall-clock threshold on a shared build machine, an
emulator or a Node host is a flake generator, and this repo has had exactly that failure; what is
asserted is structural — the name bound, the loop demonstrably reached Kotlin, and every figure came
back positive rather than zero from a clock with no resolution, which is the failure mode that would
otherwise report the upcall as free.

### What a user actually calls: the generated proxy, priced against that boundary

Everything above measures `_pm_invoke(handle, args)` called straight from Python. **Nobody writes
that.** What a user writes is `demo.calc.ping()`, `Counter(10)`, `c.increment(5)`, `c.value`,
`Counter.created`, `demo.calc.tally`, `await g.fetch()` — and each of those goes through a layer of
generated Python (`PythonProxySource`) that the boundary figure says nothing about: a global lookup,
a bound-method dispatch, an argument tuple built one element longer to carry the receiver, a
`property` descriptor, a metaclass descriptor, or an `async def` wrapper with a `hasattr` in it. That
layer had never been measured.

`GeneratedProxyCostTest` (`commonTest`) is `UpcallBoundaryCostTest`'s design applied one level up:
every baseline in the same run, identical warmup in identical shape, nothing asserting a duration.
Every row is a zero-argument Python function called by one shared loop, so each pays exactly one
identical call frame; the row **net of that floor** is the body. **Each proxy row is priced against
the raw `_pm_invoke` call it wraps**, measured through the same object the proxy calls through, so
the difference is the generated layer and nothing else.

One departure from `UpcallBoundaryCostTest`: each row is the **minimum of three timed loops**, not
one. The quantity here is a *difference* of tens of nanoseconds sitting on a boundary of hundreds,
and wall-clock noise is one-sided — it lands entirely in that difference. With a single loop per
row, four consecutive runs put the instance-method delta at −17, −31, −60 and −71 ns, i.e. the proxy
came out cheaper than the boundary it wraps, every time.

Ranges are min–max over three runs. **The last row of each table is this report's own resolution**,
and it is measured rather than assumed: two of the raw rows are the *same call* (`_pm_invoke(h, ())`,
arity zero, different handle), so whatever they differ by is what cannot be resolved. A delta below
it is not a number.

| Surface a user writes | raw boundary | through the proxy | ratio | the proxy layer adds |
|---|---|---|---|---|
| **desktop** (JVM 21.0.12, macOS arm64) | | | | |
| `demo.calc.ping()` module function | 452–475 ns | 468–485 ns | 1.00–1.03x | +5…+17 ns |
| `c.increment(5)` instance method | 514–558 ns | 537–573 ns | 1.02–1.05x | +14…+27 ns |
| `c.value` property read | 495–527 ns | 509–542 ns | 1.02–1.03x | +14…+19 ns |
| `c.label = 'x'` property write | 522–548 ns | 569–609 ns | 1.09–1.11x | +47…+62 ns |
| `Counter.created` static read (metaclass) | 449–485 ns | 480–510 ns | 1.01–1.07x | +6…+34 ns |
| `Counter.created = 12` static write | 466–507 ns | 511–573 ns | 1.07–1.13x | +34…+66 ns |
| `demo.calc.tally` top-level read | 449–485 ns | 484–521 ns | 1.03–1.07x | +14…+36 ns |
| `demo.calc.tally = 9` top-level write | 466–507 ns | 520–553 ns | 1.08–1.11x | +44…+54 ns |
| `Counter(10)` constructor | 682–761 ns | 588–627 ns | — | *not separable, see below* |
| *resolution of this table* | | | | *−3…+9 ns* |
| **iOS simulator** (arm64) | | | | |
| `demo.calc.ping()` module function | 1801–1890 ns | 1829–1895 ns | 1.00–1.01x | +6…+28 ns |
| `c.increment(5)` instance method | 2901–2987 ns | 2895–3029 ns | 0.98–1.01x | −44…+42 ns |
| `c.value` property read | 2504–2601 ns | 2510–2620 ns | 0.99–1.00x | −3…+19 ns |
| `c.label = 'x'` property write | 2832–2940 ns | 2925–3092 ns | 1.03–1.05x | +92…+153 ns |
| `Counter.created` static read (metaclass) | 1838–1931 ns | 1906–1982 ns | 1.01–1.03x | +28…+68 ns |
| `Counter.created = 12` static write | 2180–2315 ns | 2277–2365 ns | 1.02–1.04x | +50…+103 ns |
| `demo.calc.tally` top-level read | 1838–1931 ns | 1927–1981 ns | 1.02–1.05x | +48…+99 ns |
| `demo.calc.tally = 9` top-level write | 2180–2315 ns | 2286–2363 ns | 1.02–1.04x | +48…+105 ns |
| `Counter(10)` constructor | 2591–2724 ns | 2769–2864 ns | 1.05–1.06x | +140…+177 ns |
| *resolution of this table* | | | | *+15…+79 ns* |

**The headline is that the boundary is the whole cost.** The boundary was 861–1313 ns on desktop and
2263–2502 ns on the simulator when this table was taken, and the generated layer on top of it is tens
of nanoseconds — 1.00x to 1.13x, and on the simulator half the rows are inside the table's own
resolution. The sugar is not where the money goes, and a user who bypassed the proxies to call
`_pm_invoke` by hand would recover almost nothing.

Those two boundary figures are the ones current when this table was measured, and the section above
has since re-measured both — desktop to 510–560 ns and the simulator to 2266–2301 ns. **The
conclusion is unchanged and the arithmetic is not**: this table's own columns were taken in the older
round at that section's older warmup, so it may be read internally (raw against proxy, which is what
the ratio column is) but its absolute figures must not be mixed with the re-measured table's.

**The await fast path is the same story**, which is the whole claim §8.6 makes for it:

| | desktop | iOS simulator |
|---|---|---|
| `_pm_invoke(h, (21,))` in the same coroutine loop, no await | 561–617 ns | 3025–3055 ns |
| `await` a **pure-Python** coroutine that never suspends | 67–70 ns | 84.5–84.8 ns |
| `await demo.calc.doubleNow(21)` through the generated `async def` | 618–647 ns | 3186–3239 ns |
| ratio, and what the `async def` wrapper adds | 1.03–1.10x, +24…+57 ns | 1.04–1.06x, +152…+185 ns |
| `asyncio.Future`s constructed during the timed loops | **0** | **0** |

The zero is asserted, not observed in passing: it is the only way to tell from Python which path was
taken, because the generated `async def` deliberately makes the two indistinguishable to its caller,
and a slow-path figure would be a different measurement wearing the same label. The pure-Python row
is the ceiling on what the wrapper can cost — an `async def` that never suspends is one coroutine
frame — and both targets land under it.

**`install()` is a startup cost, not a per-call one**, and it is small enough not to need managing.
For this repository's 11-entry, 2-class fixture table it renders 306 lines / 12.4 kB:

| | desktop | iOS simulator |
|---|---|---|
| `PythonProxySource.render()` — Kotlin string building | 226–271 µs | 606–664 µs |
| `Python3.exec` of the result | 816–927 µs | 2481–2532 µs |
| `install()` end to end | 1.06–1.20 ms | 3.09–3.20 ms |

Roughly a millisecond on desktop and three on the simulator, once per process. Compiling and
executing the Python is 75–80% of it on both; rendering the text is the cheap half. It scales with
the size of the table rather than with anything else, so an application exposing ten times this
surface should expect ten times this — still tens of milliseconds, against a `Py_Initialize` that
already costs more.

#### The one row that was expensive, and why

A **top-level `val`/`var` read cost 551–587 ns more than the boundary call underneath it** on
desktop — 2.16–2.29x, against +6…+34 ns for the metaclass descriptor doing the identical job for a
class static. That is fifty times its sibling for the same work, and it was reproducible to within
±20 ns across every run.

The cause is where CPython looks first. `PythonProxySource` answered a module attribute with a
`__getattr__` hook on a `ModuleType` subclass, and `__getattr__` is the **fallback** hook: it is
consulted only after normal attribute lookup has already failed, and on a module "already failed"
means `module_getattro` has run to completion and *raised* — building the fully formatted
`module 'demo.calc' has no attribute 'tally'` `AttributeError`, `__spec__` inspection and all — for
the hook to catch and throw away. One raised-and-discarded exception on every read of every exposed
top-level property.

A standalone probe of the three available shapes, net of an empty call:

| | ns per read |
|---|---|
| plain module attribute (the floor) | 14 ns |
| PEP 562 module-dict `__getattr__` | 214 ns |
| type-level `__getattr__` hook (what was there) | 569 ns |

The fix is not PEP 562's middle answer but the shape the class statics already used: a `property` —
a **data** descriptor, consulted *ahead* of the instance dict and in both directions — on a
`ModuleType` subclass generated **per module**. Per module because the descriptor is named after the
Kotlin declaration, and one shared type would answer `demo.calc.tally` on every other proxy module
too; that is the same price the metaclass already pays for a class's statics, and it buys the same
thing. It also removes the `__setattr__` hook outright, because a data descriptor answers the write
half as well.

| desktop, net of the boundary | before | after |
|---|---|---|
| `demo.calc.tally` read | +551…+587 ns (2.16–2.29x) | +14…+36 ns (1.03–1.07x) |
| `demo.calc.tally = 9` write | +109…+136 ns (1.21–1.29x) | +44…+54 ns (1.08–1.11x) |

`PythonProxyInstallTest` passes unchanged across the change — including the assertion that a
read-only top-level property *refuses* assignment, which the setter-less descriptor answers with a
message naming the Kotlin declaration exactly as the old `__setattr__` branch did. **Nothing about
the two shapes differs in behaviour**, both read as correct, and no amount of reading the generated
source says which one costs fifty times the other. The measurement is what found it, which is the
argument for having it.

#### Two things this table cannot say

**The constructor row is not reportable on desktop.** `Counter(10)` and its raw counterpart both
root a Kotlin object in `HandleTable` per call and nothing releases it, so each is measured against a
table that grows underneath it — the test prints the count, and it ends the run at **78 002 live
handles** from those two rows alone. On desktop the raw baseline moved
between 511 and 775 ns across runs — larger than the effect — and the delta swings either side of
zero; on the simulator it is stable at +140…+177 ns, which is what a `type.__call__` plus an
`__init__` frame plus one attribute store should cost. The desktop figure is a property of the
fixture, not of the constructor proxy.

**Half the simulator's rows are inside its own resolution** (+15…+79 ns), which is why the
`resolution` row exists. The simulator's boundary is 2.5–3 µs and its per-row variation is ~80 ns, so
a +20 ns proxy layer is genuinely unmeasurable there. Desktop resolves to ±10 ns and is where the
small deltas can be read.

#### wasmJs was absent from these tables, and that judgement did not survive being tested

This section read: "**There are no generated proxies on wasmJs, and there structurally cannot be.**
Nothing crosses into Python on that target but an already-*bound* callable — `UpcallEntry.bind`
builds one over the single `@WasmExport`ed `pmp_invoke`, and resolution happens in Kotlin — so
`_pm_resolve`/`_pm_invoke` do not exist in `__main__` and `PythonProxySource.install()` refuses at
its own entry-point guard."

Every clause of that was true except the one it turned on. The `@WasmExport` constraint is real and
unchanged; what was wrong is the assumption that a second entry point needs a second export. A
`PyCFunction` carries a **`self`** as well as a function pointer, and `PyCFunction_NewEx` mints a
fresh object per call, so one exported pointer already backs arbitrarily many distinct Python
callables — which is exactly what `UpcallEntry.bind` had been doing with a `CallableHandle` all
along. Putting an op code where the handle would sit turns the same pointer into a dispatcher, and
all five names land with no new export. `docs/design/upcall-async-design.md` §14 has the measurement.

wasmJs now fills the sync rows of this table for real: module function +17 ns (1.05x), instance
method +18 ns (1.05x), property read +17 ns (1.06x), constructor +351 ns (1.48x), against a boundary
of ~300 ns. The one half that did not follow is async: `import asyncio` **traps the instance** on
this wasm build rather than raising, so the await row alone stays behind
`proxyBootstrapSupportsAsyncio` — the precedent `AsyncUpcallPortabilityTest` exists to record.

Android and androidNative are absent for a different and weaker reason: both run this file (it is
`commonTest`), but doing so needs an emulator, and this pass had none available. Their rows are
missing, not zero.

### The `@CName` + `ctypes.CDLL(None)` route, measured

The row above used to read "`@CName` symbol, reached from Python via `ctypes.CDLL(None)`; one
binary, so the symbol is already in the global table". Both halves were checked when the entry
point was actually built, and on iOS **neither one holds**.

*Is the `@CName` symbol in the binary?* `nm` on each artifact this project produces, for the same
three functions (`pm_upcall_invoke`, `pm_upcall_resolve`, `pm_upcall_release_object`):

| Binary | Result |
|---|---|
| androidNative `libmultiplatform_python3.14.so` | **exported** (`T` in `nm -D`) |
| iOS `PythonMultiplatform.framework` | **absent** — a Kotlin/Native framework's export set is the Objective-C surface plus the Konan runtime, and a `@CName` alias is not in it |
| Kotlin/Native test executable, either target | **absent**, and absent from the per-file caches as well, so it is never emitted rather than dropped at link time |

*Can Python call an address at all?* Only where `_ctypes` exists. This project's iOS
`Python.framework` exports `PyInit__abc` … `PyInit_time` and ships no `lib-dynload` at all, so
`import ctypes` raises `ModuleNotFoundError` there. Android's CPython does ship `_ctypes.so`.

So the route is real for androidNative and unavailable on iOS in both directions at once. The
`@CName` functions are kept because they are what a C host embeds against and what `ctypes`
reaches on Android, and `UpcallEntry.invokeAddress` publishes the same addresses without
depending on the link — `UpcallEntryTest.theRawEntryPointsAreCallableCFunctionsOfTheDocumentedShape`
calls all three through the C ABI that way.

What replaces it is not a workaround but the destination: a `PyMethodDef` is what the generated
proxy type installs anyway, and desktop's `ctypes` shim (in `UpcallEntryTest`, `commonTest`) exists
precisely to stand in for a `PyCFunction` slot. Kotlin/Native fills the struct itself, so the claim
that no C glue is needed survives — it was the reason for the claim that did not.

## 테이블 생성 — KSP

- 우리가 **KSP 프로세서를 아티팩트로 배포**한다
- 사용자가 자기 빌드에 `ksp(...)` 를 추가한다 — **사용자 모듈에서도 KSP 가 돌아야 한다.** 사용자의
  Kotlin 클래스를 Python 에 노출하는 것이 목표이므로 필수다
- 모듈마다 자기 테이블 조각을 생성한다

### 미해결: 모듈 조각 수집

조각들을 런타임에 모으는 방법이 플랫폼마다 갈린다.

| 플랫폼 | 사정 |
|---|---|
| JVM (Desktop/Android) | `ServiceLoader` 사용 가능 |
| **Kotlin/Native (iOS)** | **`ServiceLoader` 없음.** 최상위 `object` 는 지연 초기화되므로, 아무도 참조하지 않는 조각은 **초기화 자체가 일어나지 않아 등록되지 않는다** |

후보:

1. **생성된 애그리게이터** — 최종 앱 모듈의 KSP 가 모든 조각을 아는 통합 객체를 만들고 명시적으로
   참조한다. 컴파일 타임에 확정되므로 트리 쉐이킹과도 맞는다. **다만 앱 모듈에도 KSP 가 붙어야 하고,
   라이브러리는 조각만 만들고 앱이 모으는 2단계 구조가 된다.**
2. `@EagerInitialization` — Kotlin/Native 에 있으나 실험적이고 트리 쉐이킹을 무력화할 위험이 있다
3. 사용자에게 명시적 등록 호출을 요구 — 가장 단순하지만 수동이다

**1번이 유력하다.**

### 미해결: 트리 쉐이킹

테이블이 모든 `public` 을 참조하면 Native Image 의 데드코드 제거가 무력화되어 바이너리가 부푼다.

이 문제는 **업콜에만 해당한다.** 다운콜은 CPython 심볼을 찾는 구조라 무관하다.

방향으로 제시된 것: 테이블에 `KFunction<*>` 참조 대신 **람다(함수 리터럴)** 를 넣으면 도달 가능성
분석이 개별 함수 단위로 이뤄질 수 있다. 위의 애그리게이터 방식(모듈 단위 분리)도 같은 문제를 완화한다.

확정된 답은 아직 없다. 업콜 구현에 착수할 때 결론을 내야 한다.

## 스레드 상태

업콜은 Python 스레드에서 Kotlin 으로 들어오므로, 반대 방향의 스레드 상태 문제가 있다.
[`threading-and-abi.md`](threading-and-abi.md) 를 본다.

---

## Cycle collection is part of the table's job

Settled after the fact, and it changes what the generator emits.

A Python proxy holding a Kotlin object that holds a Python object forms a cycle neither
collector can break on its own: CPython's traversal stops at the opaque handle, and the JVM sees
the handle map as a live root. Reference counting cannot help — it never frees cycles in any
language, which is why CPython carries a cyclic collector at all.

The fix is to make the Kotlin side's Python references visible to `tp_traverse`, so the proxy's
traverse reaches through the handle and enumerates the `PyObject`-typed fields of the Kotlin
object. KSP already walks every exposed class to build the call table and knows those field
types at that point, so a traverse function per class is one more generated entry rather than a
new mechanism.

This is why the reflection-free, build-time table matters beyond dispatch cost: a
reflection-based binding cannot afford this, which is why mature ones document "do not create
cycles" instead of solving it.

Full mechanism, and the three parts that remain hard — traverse running during collection,
`tp_clear` having to mutate Kotlin state, and cycles that close on the Kotlin side — are in
`docs/design/object-lifetime.md`.
