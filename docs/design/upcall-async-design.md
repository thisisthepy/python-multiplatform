# 비동기 업콜 설계 — Python 이 Kotlin 의 `suspend` 를 부를 수 있는가

`docs/design/upcall-design.md` 가 만든 업콜 경로는 다섯 플랫폼에 다 붙었지만, **노출할 수 있는 것은
일반 함수뿐이다.** 이 문서는 왜 그런지, 무엇을 대신 할 수 있는지, 후보 중 무엇을 골랐는지,
그리고 지금 무엇까지 구현되어 있는지를 정리한다.

이 문서에서 **측정** 이라고 적힌 것은 이 저장소에서 실제로 돌려 본 것이다. **추론** 이라고 적힌
것은 코드와 문서를 읽어 얻은 것이고, 기기가 없어 확인하지 못했다. 둘을 섞지 않는다.

---

## 1. 막는 것은 구현이 아니라 C 의 호출 규약이다

    CPython 이 C 함수 포인터를 부른다        ml_meth: PyObject *(PyObject *self, PyObject *args)
      → 그 C 스택 프레임은 PyObject * 를 동기적으로 반환해야 한다
      → 중간에 suspend 하면 반환할 것이 없다

`runBlocking` 은 해결이 아니다. 그 스레드를 다시 붙잡는 것이므로 얻는 것이 없고, **Android 에서는
특히 나쁘다** — 업콜이 도착한 스레드는 CPython 이 만든 pthread 이고(`docs/design/upcall-design.md`
"Android's boundary runs the other way round"), 그것을 막으면 답을 기다리는 인터프리터 쪽이 함께
멈춘다. 즉 자기가 기다리는 대상을 자기가 막는 형태가 된다.

그러므로 남는 선택지는 하나뿐이다: **다른 것을 반환하고, 값은 나중에 전달한다.**

---

## 2. KSP 가 `suspend` 를 어떻게 다뤘는가 — 측정 (§8 에서 바뀐다)

> **이 절은 과거형이다.** §8 이 이 동작을 바꿨다. 여기 남겨 두는 이유는 무엇이 바뀌었는지를
> 대조할 기준이 필요하기 때문이다. `GeneratedSuspendTest` 는 더 이상 이 표를 고정하지 않는다.

`BindingPolicy.isExposedFunctionShape` 한 줄이 전부였다.

    if (Modifier.SUSPEND in function.modifiers) return false

**조용히 건너뛴다.** 깨지지도, 잘못된 코드를 만들지도 않는다. 로그도 없다.

`ksp-fixtures/library/.../Suspending.kt` 를 넣고 생성된 조각을 직접 읽어 확인했다
(`Fragment_io_github_thisisthepy_ksp_fixtures_library.kt`). 각 형태마다 같은 스코프에 비-suspend
대조군을 두었으므로, "스코프 전체가 빠져서 통과"하는 경우와 구분된다.

| 선언 | 결과 | 대조군 |
|---|---|---|
| 최상위 `suspend fun` | 항목 없음 | `blockingTopLevel` 있음 |
| 클래스 멤버 `suspend fun` | 항목 없음. **클래스는 그대로 노출되고 생성자도 남는다** | `fetchBlocking` 있음 |
| companion 의 `suspend fun` | 항목 없음 | `createBlocking` 있음 |
| interface 의 `suspend fun` | 항목 없음 | `describe` 있음 |
| `object` 의 `suspend fun` | 항목 없음 | `pingBlocking` 있음 |
| `ReflectedClass.memberNames` | 빠진 이름은 여기에도 없다 | — |

사용자 입장에서 보이는 것은 Python 쪽 `AttributeError` 하나뿐이고, **그 함수가 보였지만 거부되었다는
신호는 어디에도 없다.** 이것이 이 동작의 실제 비용이다.

`ksp-fixtures/app/src/desktopTest/.../GeneratedSuspendTest.kt` 가 이 표를 고정한다. 비동기 규약이
생기면 이 테스트들이 깨지도록 되어 있다 — 침묵이 소리 없이 다른 침묵으로 바뀌지 않게 하는 것이
목적이다.

### 2.1 그런데 `suspend` **타입** 은 걸러지지 않는다 — 측정

정책이 읽는 것은 *선언* 의 modifier 다. `val h: suspend (Long) -> Long` 에는 그 modifier 가 없다.
그래서 다음 셋은 전부 노출된다:

| 선언 | 생성된 것 |
|---|---|
| `val suspendingHandler: suspend (Long) -> Long` | `STATIC_GETTER`, `TypeTag.OBJECT` |
| `fun makeHandler(): suspend (Long) -> Long` | `FUNCTION`, 반환 `TypeTag.OBJECT` |
| `fun runsHandler(h: suspend (Long) -> Long)` | 파라미터 `TypeTag.OBJECT`, 캐스트는 아래 |

파라미터 캐스트로 생성된 것은 소스에 선언이 존재하지 않는 분류자다:

    args[0] as kotlin.coroutines.SuspendFunction1<kotlin.Long, kotlin.Long>

**그런데 이것은 컴파일된다** — `:ksp-fixtures:library:compileKotlinDesktop` 과
`compileKotlinAndroidNativeArm64` 둘 다 `e:` 없이 통과한다. 컴파일만이 아니라 런타임 캐스트도
통과한다(`GeneratedSuspendTest.theGeneratedCastToASuspendingFunctionTypeHoldsAtRuntime`).

다만 **이것은 우회로가 아니다.** 건너간 것은 불투명한 핸들이고, 그 핸들을 부를 항목은 테이블
어디에도 없다. Python 은 그것을 들고 있다가 Kotlin 에 돌려주는 것 말고 할 수 있는 일이 없다.

---

## 3. 후보 셋

Kotlin 쪽에서 셋이 공유하는 절반은 같다: **동기 프레임 안에서 코루틴을 시작하고, 결과를 나중에
주소를 매길 수 있는 곳에 세워 둔다.** 다른 것은 그 결과가 Python 에 도달하는 방법뿐이다.

### (A) 핸들 + 폴링

업콜이 즉시 작업 핸들을 반환하고, Python 이 `is_done(h)` / `result(h)` 로 묻는다.

**무엇이 막는가**

1. **wasm 에서는 반드시 데드락한다 — 추론.** wasmJs 에는 스레드가 없다. Kotlin 코루틴의 재개는 JS
   마이크로태스크 큐를 통해서만 일어나고, CPython 인터프리터 자체가 같은 JS 스레드 위에서 돈다.
   `while not is_done(h): pass` 는 JS 이벤트 루프에 제어를 돌려주지 않으므로, 재개가 큐에 들어간
   채 영원히 실행되지 않는다. 이것은 폴링이 느리다는 문제가 아니라 **폴링이 진행을 막는다** 는
   문제다. 기기 없이 확인할 수 없어 추론으로 표시한다.
2. **KSP 가 `Deferred<T>` 를 노출할 수 없다 — 측정.** `BindingPolicy.hasRenderableSignature` 는
   타입 파라미터가 있는 선언을 전부 거부한다(`Box<T>` 로 이미 고정되어 있다). 그러므로
   `Deferred.await`, `Job.isCompleted` 같은 것을 그대로 테이블에 올릴 수 없고, 제네릭이 아닌 전용
   타입을 손으로 써야 한다. 막는 것은 아니지만 설계 비용이다.
3. `kotlinx.coroutines` 는 `python-multiplatform` 의 의존성이 아니다 — 측정
   (`gradle/libs.versions.toml` 에 좌표는 있으나 `sample` 의 swing 아티팩트에서만 쓰인다).
4. JVM·Native 에서는 동작하지만 코어 하나를 태운다. GIL 을 쥔 채 도는 폴링 루프는 스위치 간격마다
   놓았다 다시 잡으므로, 다른 Python 스레드까지 함께 느려진다.

### (B) 콜백 (다운콜)

Python 이 콜러블을 넘기고, 완료 시 Kotlin 이 그것을 부른다.

**무엇이 막는가 — 그리고 막지 않는 것**

- **완료 스레드가 GIL 을 쥘 수 있는가: 쥘 수 있다 — 측정.**
  `AsyncCompletionProbeTest.aKotlinCreatedThreadCanResolveAnAsyncioFutureTheInterpreterIsWaitingOn`
  이 이것을 직접 보인다. **Kotlin 이 만든** 스레드(CPython 이 만든 것이 아니므로 스레드 상태를
  받은 적이 없다)가 `withGIL` 로 GIL 을 얻고 Python 을 호출한다. 그것도 인터프리터의 메인
  스레드가 `loop.run_until_complete` 안에 들어가 있는 동안에 — 테스트는 그 스레드가
  `loop.is_running()` 이 `True` 인 것을 **직접 관측한 뒤에만** 통과하도록 되어 있다. 처음 판은
  이 조건 없이 4 ms 만에 통과했고, 그것은 경합 없는 경로를 통과한 것이라 아무 의미가 없었다.
- **Android 에서 ART attach 가 필요한가: 필요 없다 — 추론.** `409da6fc` 가 다룬 것은 **업콜**
  방향이다. 거기서 스레드는 CPython 이 만든 맨 pthread 라 ART 가 본 적이 없었다. 여기서는 완료
  스레드가 Kotlin/JVM 이 만든 스레드이므로 ART 가 이미 알고 있고, 필요한 것은
  `PyGILState_Ensure` 뿐이다. 즉 방향이 반대라 그 문제가 재발하지 않는다. 에뮬레이터를 쓰지
  않았으므로 추론이다.
- **진짜 어려운 점은 GIL 이 아니라 재진입이다.** 콜백은 완료 스레드 위에서 *즉시* 실행되므로,
  Python 사용자 코드가 메인 스레드가 하던 일과 동시에 임의의 지점에서 돈다. 이것은 Python
  `threading.Thread` 가 이미 하는 일과 같으므로 새로운 위험은 아니지만, **동기화 책임이 전부
  사용자에게 간다.** 그리고 코루틴이 동기적으로 완료되면(§5 의 빠른 경로) 콜백은 업콜 프레임
  *안에서* 실행된다 — GIL 을 이미 쥔 채로, C 가 위에 있는 상태에서. `PyGILState_Ensure` 는
  재진입 가능하므로 안전하지만, 규약이 그것을 명시해야 한다.
- Kotlin 이 Python 콜러블의 참조를 오래 들고 있어야 한다. 그 참조를 언제 놓는가는 이미 풀린
  문제다 — `PyObject` 의 지연 해제 큐와 eval 체크포인트(`GILScope.kt`,
  `Python3.drainPendingReleases`)가 그 일을 한다. 새 메커니즘이 아니다.

### (C) `asyncio` 연동

Kotlin 이 `Future` 를 만들어 Python 이벤트 루프에 넘기고, 완료 시
`loop.call_soon_threadsafe(fut.set_result, v)` 로 해소한다.

**과제가 지목한 질문 — `call_soon_threadsafe` 가 이 ABI 부분집합에 있는가: 있다 — 측정.**

특별한 진입점이 필요하지 않다. 필요한 것은 전부 이미 `EmbedAPI.kt` 에 있는 일반 호출뿐이다:

| 필요한 것 | `EmbedAPI` |
|---|---|
| `import asyncio` | `PyImport_ImportModule` ✔ |
| `loop.call_soon_threadsafe`, `fut.set_result`, ... | `PyObject_GetAttrString` ✔ |
| 그것들을 호출 | `PyObject_Call` / `PyObject_CallObject` / `PyObject_CallNoArgs` ✔ |

`AsyncCompletionProbeTest.everyApiThisNeedsIsAlreadyBoundSoAnAsyncioConventionAddsNoNewBinding`
이 이 경로를 그대로 밟아 `new_event_loop` / `create_future` / `call_soon_threadsafe` /
`run_until_complete` / `set_result` / `set_exception` / `done` / `result` / `cancel` 이 전부 도달
가능함을 확인한다. **(C) 는 새 바인딩을 하나도 추가하지 않는다.**

참고로 다른 경로였을 `Py_AddPendingCall` 은 Stable ABI 에는 있지만(`test_stable_abi_ctypes.py` 에
등재) **`EmbedAPI` 에는 바인딩되어 있지 않다** — 측정. 그리고 그것은 C 함수 포인터를 받으므로
`(long) -> int` 트램폴린 shape 이 하나 더 필요하다. 이미 `Panama` 의 어휘 안에 있는 shape 이지만,
(C) 가 그것 없이 되므로 필요하지 않다.

**무엇이 막는가**

- 루프가 **돌고 있어야** 한다. Python 메인 스레드가 `asyncio.run()` 안에 있지 않으면
  `call_soon_threadsafe` 는 큐에 넣기만 하고 아무도 꺼내지 않는다. 닫힌 루프면 예외를 던진다.
  즉 (C) 는 **애플리케이션이 async 로 짜여 있을 것을 요구한다.**
- wasm: 위 요구가 바로 그 플랫폼의 조건이다(§4).

---

## 4. wasm 이 실제로 결정한다

세 후보 모두 JVM·Native 에서는 동작한다. 갈리는 것은 wasm 이고, 그 이유는 하나다: **wasmJs 에는
스레드가 없고, Python 과 Kotlin 이 같은 JS 스레드를 공유한다.**

그래서 wasm 에서는 셋 다 같은 전제를 요구한다 — **Python 이 호스트 이벤트 루프에 제어를 돌려줘야
한다.** 그 전제 위에서:

| 후보 | wasm |
|---|---|
| (A) 폴링 | **성립하지 않는다.** 폴링 루프가 제어를 돌려주지 않으므로 전제를 스스로 깬다 |
| (B) 콜백 | 성립한다. Python 이 돌아간 뒤 마이크로태스크에서 콜백이 실행된다 |
| (C) asyncio | 성립한다. 단 루프가 JS 에 양보해야 하므로 재래식 selector 루프로는 부족하다 |

이것은 전부 추론이다. 이 워크스페이스에서 wasm 은 컴파일 검증만 하고 있고
(`docs/platforms/wasm-design.md` §"Still open"), 실행 경로를 만들지 않았다.

---

## 5. 고른 것: (C), 그리고 (B) 는 그 구현 수단이다

**(C) 를 목표 규약으로 하고, (B) 를 그 내부 메커니즘으로 쓴다. (A) 는 버린다.**

근거는 셋이다.

1. **(A) 는 wasm 에서 성립하지 않는다.** 나머지 넷에서 동작하는 규약을 만들고 wasm 만 예외로
   두는 것은, 이 저장소가 다섯 타깃을 하나의 규약으로 유지해 온 방식과 맞지 않는다.
2. **(C) 는 새 바인딩을 요구하지 않는다 — 측정.** 이것은 예상이 아니라 확인이다. 반면 (A) 는
   제네릭이 아닌 전용 타입과 그 멤버들을 테이블에 새로 올려야 한다.
3. **(C) 는 기계적으로 (B) 다.** `call_soon_threadsafe` 를 부르는 것은 완료 스레드가 Python
   콜러블을 부르는 다운콜이고, 그것이 정확히 (B) 다. 즉 둘 중 하나를 고르는 문제가 아니라,
   (B) 를 만들면 (C) 가 그 위에 얹히는 관계다. 사용자에게 노출하는 표면만 (C) 로 정한다.
   `await kotlin_fn(x)` 는 Python 사용자가 이미 아는 문법이고, 콜백은 아니다.

**빠른 경로는 규약보다 앞에 온다.** `suspend` 는 시그니처이지 suspend 하겠다는 약속이 아니다.
실제 suspension 지점에 닿지 않는 `suspend fun` 은 **`start` 가 반환하기 전에 이미 완료된다**
(`PendingCallTest.aBlockThatNeverSuspendsIsAlreadyCompleteBeforeStartReturns`, 측정). 그런 호출은
`Future` 도 이벤트 루프도 없이 실제 값을 그대로 반환하면 된다. 규약은 그 경로를 먼저 태우고,
정말로 suspend 한 경우에만 `Future` 를 만든다.

### 뒤집어지면 이 결론도 뒤집힌다

- wasm 실행 경로가 열렸는데 asyncio 루프를 JS 에 양보시킬 방법이 없다면, (C) 는 wasm 에서
  (B) 로 내려앉는다 — 그때는 (B) 를 공개 표면으로 올리는 편이 낫다.
- Android 에서 완료 스레드가 실제로 ART attach 를 요구하는 것으로 밝혀지면, §3(B) 의 "방향이
  반대라 재발하지 않는다"가 무너지고 `409da6fc` 의 pthread 키 destructor 를 다운콜 쪽에도
  복제해야 한다.

---

## 6. 구현된 것과 구현하지 않은 것

### 구현: `PendingCall` — 셋이 공유하는 절반

`python-multiplatform/src/commonMain/kotlin/python/multiplatform/ffi/upcall/PendingCall.kt`

동기 프레임 안에서 코루틴을 시작하고, 결과를 세워 두고, 하나의 리스너에게 전달한다.
`PendingCallTest`(commonTest, 9개) 가 고정한다.

- **`kotlinx.coroutines` 의존성이 없다.** `startCoroutine` 과 `Continuation` 은 `kotlin-stdlib`
  common 이다. 노출되는 `suspend fun` 은 *사용자의* 것이고, 그것이 쓰는 디스패처는 이미 사용자
  클래스패스에 있다. 시작하는 데는 stdlib 만 있으면 된다.
- **`EmptyCoroutineContext`.** 디스패처가 없으므로 본문은 실제 suspension 까지 호출 스레드에서
  돈다. §5 의 빠른 경로가 여기서 나온다. 대가도 여기서 나온다 — 첫 suspension 이전의 작업은
  **C 프레임 안에서, GIL 을 쥔 채** 돈다.
- **아무것도 밖으로 던지지 않는다.** 본문의 예외도 리스너의 예외도 필드에 세워 둔다. 위에 C 가
  있는 프레임이므로 `UpcallTrampoline` 과 같은 규칙이다.
- **새 핸들 기계가 없다.** `PendingCall` 은 평범한 Kotlin 객체이므로 `HandleTable` 이 이미
  세대 태그가 붙은 핸들을 주고, `UpcallTrampoline.releaseObject` 가 이미 Python 쪽 해제 수단을
  준다.
- **동기화하지 않는다.** 경계의 나머지와 같은 규약을 지키는 조건에서다: 모든 변경은 GIL 을 쥔
  스레드에서 일어난다. 이것은 추가 부담이 아니다 — Python 에 도달하려는 완료는 어차피 GIL 을
  잡아야 하므로, 그 스코프가 이미 이 필드들을 덮는다.

### 전달 절반 — §8 에서 구현됐다

이 절이 "아무것도 붙어 있지 않다"고 적었던 `onCompleted` 자리에 이제 (C) 가 붙어 있다. §8 을 보라.

---

## 7. 다음에 확인해야 할 것 (순서대로)

1. **Android 에뮬레이터에서 (B) 의 완료 스레드.** Kotlin 스레드가 `PyGILState_Ensure` 로 들어가
   Python 콜러블을 부를 때 ART attach 가 필요한가. §3(B) 의 추론이 맞는지가 이것 하나에 달려
   있다. `UpcallThreadAttachTest` 옆에 두면 된다. **§8 이 이것을 바꾸지 못했다** — 완료 경로는
   전부 `commonMain` 이라 다섯 타깃이 같은 소스를 컴파일하지만, 실행해 본 것은 desktop 하나다.
2. **동기 완료 비율.** §5 의 빠른 경로가 실제로 얼마나 자주 타는가. `PendingCall.isDone` 을
   `start` 직후에 세는 것으로 측정 가능하고, 이 값이 높으면 규약의 복잡도 대부분을 드문 경로로
   미룰 수 있다. §8.4 가 이 질문을 하나 더 늘렸다 — 빠른 경로는 "suspend 하지 않은 호출"보다
   **넓다.**
3. ~~**wasm 실행 경로.** §4 가 전부 추론이다.~~ **§9.5 가 측정했다** — wasmJs 에는 실행 경로가
   있었고(`wasmJsNodeTest`, 273개), (C) 는 `import asyncio` 가 trap 하는 지점에서 끝난다.
   남은 추론은 "루프가 도는 상태에서의 교착" 하나뿐이다.
4. **`suspend` 가 걸러졌음을 사용자에게 알리는 것.** §2 의 침묵은 §8 이 덮지 못하는 형태
   (확장 수신자를 가진 `suspend fun`, 제네릭 `suspend fun`) 에 **그대로 남아 있다.** KSP 경고가
   맞는 자리다.
5. ~~**취소.** §8 의 범위에서 빠졌다.~~ **§9.1–9.3 이 답했고, 이른 통지는 §10 이 만들었다.**
   남은 것은 §10.5 의 두 가지 — 다섯 타깃 중 desktop 외의 실행 확인, 그리고 루프가 죽어 done 콜백이
   돌지 못한 경우의 회수.

---

## 8. 전달 절반: 구현된 것 — 측정

§5 가 고른 것을 그대로 만들었다. 표면은 (C), 메커니즘은 (B), 빠른 경로가 규약보다 앞에 온다.

    result = await kotlin_async_fn(x)

`AsyncUpcallDeliveryTest`(desktopTest, 7개) 가 이것이 실제로 도는 것을 고정한다.

### 8.1 KSP: 버리는 대신 다른 본문을 만든다

`BindingPolicy` 에서 `Modifier.SUSPEND` 거부 한 줄을 지웠다. 그 자리에 `isSuspending` 이 있고,
`FragmentScanner.functionBody` 가 그것을 읽어 본문 모양만 바꾼다:

    // 기존
    { args -> fixture.library.blockingTopLevel(args[0] as Long) }
    // suspend
    { args -> python.multiplatform.ffi.upcall.PendingCall.start { fixture.library.suspendingTopLevel(args[0] as Long) } }

`PendingCall.start` 가 받는 것은 `suspend () -> Any?` 이므로 저 중괄호가 suspend 람다이고,
**`kotlinx.coroutines` 없이 stdlib 만으로 컴파일된다.** 생성된 조각을 직접 읽어 확인했다
(`Fragment_io_github_thisisthepy_ksp_fixtures_library.kt`, 5개 항목).

바뀌지 않은 것이 바뀐 것만큼 중요하다. `kind` 는 그대로다 — suspending 멤버는 여전히 `METHOD` 이고
`args[0]` 이 수신자다. `returnType` 도 그대로 **선언된 반환 타입**이다(`PendingCall` 이 아니라).
그래야 빠른 경로가 동기 항목과 똑같이 마샬링한다.

### 8.2 `CallableKind` 가 아니라 플래그인 이유

`ExposedCallable.isSuspend: Boolean` 을 새로 뒀다. `CallableKind` 에 `SUSPEND_*` 변종을 추가하는
쪽은 택하지 않았다. 두 축이 직교하기 때문이다 — `kind` 가 답하는 것은 *인자가 어디 있는가*,
`isSuspend` 가 답하는 것은 *무엇이 돌아오는가* 이고, 하나로 접으면 모든 `hasReceiver` 판단을
변종마다 한 번씩 다시 써야 한다. suspending 멤버 함수 하나만으로도 그 값이 드러난다.

### 8.3 런타임 경로

`AsyncUpcall.deliver` 가 `UpcallTrampoline.invoke` 의 `entry.isSuspend` 분기에 붙어 있다.

| 경우 | 반환 | 비용 |
|---|---|---|
| 이미 완료 · 성공 | 실값 (`marshalResult`) | `asyncio` import 조차 없다 |
| 이미 완료 · 실패 | `NULL` + 에러 지시자 | 동기 실패와 완전히 같은 경로 |
| 진짜 suspend | `asyncio.Future` (새 참조) | `get_running_loop` + `create_future` |

완료 시:

    withGIL { loop.call_soon_threadsafe(future.set_result | set_exception, ...) }

- **완료 스레드가 자기 GIL 스코프를 취한다.** `withGIL` 이고 `UpcallTrampoline.attached` 가 아니다 —
  여기는 C 진입점이 아니므로 그 스레드의 중첩 깊이는 자기 자신의 정직한 기록이다. CPython 이 본 적
  없는 완료 스레드는 깊이 0 이라 진짜 `PyGILState_Ensure` 를 타고, 나중 업콜 안에서 오는 완료는
  그 업콜의 `attached` 가 세운 깊이 아래에서 올바르게 건너뛴다.
- **`Future` 참조는 둘이 나눠 가진다.** Kotlin 래퍼가 `create_future` 가 준 것을 갖고, Python 은
  증가분 하나를 받는다(트램폴린의 "결과는 새 참조" 규약 그대로). 래퍼가 살아 있는 이유는 완료
  람다가 그것을 캡처하고, 람다는 `PendingCall` 이, `PendingCall` 은 중단된 continuation 을 쥔
  쪽이 잡고 있기 때문이다. 완료가 발사되면 `PendingCall` 이 리스너를 놓고 둘 다 회수된다.
- **새 바인딩이 하나도 없다.** §3(C) 가 예고한 대로다.

### 8.4 빠른 경로는 "suspend 하지 않은 호출"보다 넓다 — 측정

테스트를 처음 쓸 때 관측한 것이고, 예상하지 못했다. 완료 스레드가 업콜 프레임이 아직 돌고 있는
동안 continuation 을 재개하면 — `CFUNCTYPE` 이 업콜 자체를 위해 GIL 을 놓는 순간이 그 틈이다 —
`deliver` 가 `isDone` 을 볼 때 이미 참이다. **그러면 `Future` 는 만들어지지 않고 빠른 경로가
탄다.** 틀린 동작이 아니다(코루틴은 정말로 끝났다). 다만 §7.2 의 "동기 완료 비율" 은 정적인
성질이 아니라 **경합의 함수**라는 뜻이고, 그 경로를 일부러 밟으려는 테스트는 `Future` 가
건너간 것을 직접 관측해야 한다. `AsyncUpcallDeliveryTest` 는 `type(r).__name__` 을 Python 쪽에서
기록해 그것을 단언한다 — 값만 보면 두 경로가 구별되지 않는다.

### 8.5 플랫폼

| 타깃 | 컴파일 | 실행 |
|---|---|---|
| desktop | ✔ | ✔ — `await` · 예외 · 빠른 경로 전부 관측 |
| androidNativeArm64 | ✔ | 미확인 (기기 없음) — §11.5 도 그대로 |
| iosSimulatorArm64 | ✔ | ~~미확인~~ **✔ — §11 이 채웠다.** 전달·이른 취소 통지·조용한 드롭 셋 다 통과 |
| wasmJs | ✔ | **성립하지 않을 것이다 — 추론.** 아래 (§9.5 가 뒤집었다: 교착이 아니라 `import asyncio` 자체가 트랩) |
| android (JVM) | **미확인** — 이 워크스페이스에 Android SDK 가 없어 태스크가 구성조차 안 된다 |

전달 코드는 전부 `commonMain` 이고 `expect`/`actual` 이 하나도 없다. 즉 컴파일된 다섯 소스가
같은 소스다. 그것이 보장하는 것은 타입이 맞는다는 것뿐이고, GIL 취득 동작이 각 타깃에서 같다는
보장은 아니다.

**wasm 은 §4 가 예고한 그 지점에서 막힌다.** wasmJs 에는 스레드가 없으므로 완료는 JS
마이크로태스크로만 온다. 그런데 Python 이벤트 루프는 같은 스레드에서 `run_until_complete` 안에
있고, 재래식 selector 루프는 JS 에 제어를 돌려주지 않는다. 그러면 Kotlin 의 재개가 루프 뒤에
줄을 서고, 루프는 그 재개를 기다린다 — (A) 를 기각한 것과 같은 형태의 교착이다. 이것은
**추론이다.** 이 워크스페이스에 wasm 실행 경로가 없어(`docs/platforms/wasm-design.md` "Still open")
확인할 수 없었고, 확인하지 않은 것을 확인했다고 적지 않는다.

### 8.6 이번 범위에서 뺀 것 — **§9 가 앞의 둘을 채웠다**

- **취소.** ~~`Future.cancel` 이 도달 가능하다는 것만 §3(C) 에서 확인돼 있고~~ **§9.1–9.3 이 답했다.**
  아래 원문은 §9 가 무엇을 바꿨는지 대조하기 위해 남긴다.

  > `Future.cancel` 이 도달 가능하다는 것만 §3(C) 에서 확인돼 있고, 그것을 Kotlin
  > 코루틴의 취소로 옮기는 경로는 만들지 않았다. 그러므로 지금 Python 쪽에서 `Future` 를
  > 취소해도 **Kotlin 쪽 작업은 계속 돈다.** 그 뒤 완료가 도착했을 때 무슨 일이 일어나는지는
  > **테스트하지 않았고 여기서 단정하지 않는다.**

- **Python 쪽 프록시 생성.** ~~Python 모듈 생성 자체가 아직 없으므로 생성되지는 않는다.~~
  **§9.4 가 만들었다.**
- **실행 중인 루프가 없을 때.** 실패한다(`RuntimeError: no running event loop`), 그리고 그때
  **코루틴은 이미 시작된 뒤다** — suspend 할지 여부는 시작해 봐야 알기 때문이다. 그 재개는
  버려진다. 이것은 (C) 가 "애플리케이션이 async 로 짜여 있을 것"을 요구한다는 §3(C) 의 대가를
  구체적으로 치르는 지점이고, 숨기지 않았다
  (`aSuspendingEntryThatSuspendsWithNoRunningLoopFailsInsteadOfReturningSomethingUnusable`).
- **`suspend` 타입 누수.** §2.1 은 그대로다. `val h: suspend (Long) -> Long` 은 여전히 `OBJECT`
  핸들로 건너가고, 그것을 부를 항목은 테이블에 없다.

---

## 9. 취소와 프록시 생성 — 측정

§8.6 이 미룬 둘을 채웠다. 그리고 그 과정에서 **§4·§8.5 의 wasm 판단이 틀렸다는 것**이 나왔다 —
틀린 방향이 아니라, 예측한 것보다 훨씬 이른 지점에서 훨씬 나쁘게 깨진다(§9.5).

### 9.1 고치기 전에 무슨 일이 났는가 — 측정

`AsyncUpcallCancellationTest` 를 먼저 쓰고, 고치기 전에 돌렸다. 관측된 것:

| 관측 대상 | 결과 |
|---|---|
| `await` 가 받은 것 | `CancelledError` — 정상 |
| 루프의 `call_exception_handler` | **`InvalidStateError: invalid state` 1건** |
| 완료 스레드의 에러 지시자 | **깨끗하다** |
| 그 다음의 무관한 업콜 | **정상 동작** |

즉 **오염은 없었다.** 이 저장소가 두 번 당한 "에러 지시자가 남아 무관한 다음 호출을 죽인다" 는
일어나지 않았고, 그것을 추측이 아니라 두 가지 방법으로 확인했다 — 완료 뒤 `PyErr_Occurred()` 를
직접 읽었고, 그 다음 파이썬에서 별개의 항목을 호출해 정상 결과를 받았다.

일어난 것은 하나뿐이다: **루프 콜백 안에서 터진 예외**. `call_soon_threadsafe` 는 예약만 하므로
`set_result` 는 나중에 루프 스레드에서 실행되고, 취소된 `Future` 에 대해 `InvalidStateError` 를
던진다. asyncio 는 그것을 `call_exception_handler` 로 보낸다 — 기본 핸들러면 stderr 로그다.
**애플리케이션이 잘못한 것이 없고 대응할 수도 없는 실패 보고**이며, 값은 일부러 버린 값이다.

### 9.2 안전하게 만든 방법 — 그리고 왜 검사 하나로는 부족한가

둘을 넣었고, **보장인 것은 둘째뿐이다.**

1. 예약 전에 `Future.done()` 을 본다. 싸고 흔한 경우를 걷어낸다. **보장이 아니다** — 이 검사는
   완료 스레드에서 일어나고 콜백은 나중에 루프 스레드에서 실행되므로, 그 사이에 취소가 끼면
   검사를 통과한 뒤에 실패한다.
2. 예약하는 것을 `set_result` 가 아니라 `_pm_settle` 로 바꿨다. 이 함수가 **콜백 안에서 다시**
   `done()` 을 본다. 순서와 무관하게 성립하는 것은 이쪽이다.

둘 다 필요하다는 것을 단언이 아니라 실험으로 확인했다. `_pm_settle` 의 `done()` 가드만 지우고
돌리면 **경합 테스트 하나만 빨개진다**(`InvalidStateError: invalid state`) — 나머지 취소 테스트
둘은 1번 검사가 잡으므로 초록으로 남는다. 즉 1번은 2번을 대체하지 못한다.

그 경합 테스트는 순서를 우연에 맡기지 않는다. 파이썬 코루틴이 `await` 하지 않고 스핀하는 동안
Kotlin 이 완료·예약하게 만든다 — 루프의 ready 큐는 코루틴이 루프를 쥐고 있어 비워지지 않고,
CPython 은 스위치 간격마다 GIL 을 놓으므로 완료 스레드는 진행한다. **예약이 이미 큐에 들어간 뒤에**
취소가 일어나므로, Kotlin 쪽 검사는 이미 "안 끝났다" 라고 답한 상태다.

### 9.3 Kotlin 쪽 취소 — 되는 것과 안 되는 것

**`PendingCall` 은 코루틴을 강제로 취소할 수 없다. 구현이 빠진 것이 아니라 구조적으로 불가능하다.**

`start` 가 `startCoroutine` 에 넘기는 `Continuation` 은 코루틴의 **완료(completion)** 이고, 본문
전체가 끝났을 때 딱 한 번 재개된다. 본문이 지금 멈춰 있는 **중단 지점의 continuation 은 완전히
다른 객체**이고, 그것을 쥔 쪽은 중단을 만든 사람 — 사용자의 `suspendCoroutine`, 그들의 디스패처,
또는 이 라이브러리가 의존하지 않는 `kotlinx.coroutines` 다. `PendingCall` 은 그것을 본 적이 없고,
stdlib 에는 그것에 닿는 수단이 없다. continuation 을 두 번 재개하는 것은 정의되지 않은 동작이라
뒷문도 없다. 강제 취소는 `Job` 트리가 하는 일이고, 그것을 만드는 것이 `kotlinx.coroutines` 다.

**되는 것: 협조적 취소.** 코루틴의 `CoroutineContext` 를 `EmptyCoroutineContext` 에서
`PendingCall` 자신으로 바꿨다(`PendingCall` 이 `CoroutineContext.Element` 다). 그러면 본문이
`coroutineContext[PendingCall]` 로 자기 호출에 닿고, `ensureActive()` 가 그 한 줄 형태다.

    suspend fun slowSum(n: Long): Long {
        var total = 0L
        for (i in 0 until n) { ensureActive(); total += step(i) }
        return total
    }

이것은 `kotlinx.coroutines` 와 **같은 계약**이다. 거기서도 취소는 협조적이고, 검사하지 않는 본문은
거기서도 취소되지 않는다. 다른 점은 거기서는 라이브러리의 suspend 함수들이 대신 검사해 준다는
것뿐이고, 여기에는 그런 함수가 없다.

디스패처를 추가한 것이 아니라는 점이 중요하다. `ContinuationInterceptor` 가 아닌 element 는 본문이
어디서 도는지 바꾸지 못하므로, **§5 의 빠른 경로는 그대로다.**

~~**되지 않는 것: 이른 통지.**~~ **§10 이 만들었다.** 아래 원문은 무엇이 바뀌었는지 대조하기 위해 남긴다.

> **되지 않는 것: 이른 통지.** `cancel()` 을 파이썬이 취소하는 그 순간에 부를 방법이 없다. Kotlin 이
> Python 의 취소를 알 수 있는 지점은 완료 시점뿐이고, 그때는 이미 늦다. 그러려면 Python→Kotlin 호출이
> 하나 더 필요하다 — 구체적으로는 (a) `_pm_release` 와 같은 `(long) -> int` 스텁 하나(모양이 이미
> 있으므로 새 스텁 *형태* 는 아니다), (b) `Future` 에 실어 보낼 `PendingCall` 핸들, (c) 다섯 타깃의
> 바인딩, (d) 그 핸들의 수명 관리. **이번 범위에서 하지 않았고, 되는 척하지 않는다.**

협조하지 않는 본문이 끝까지 도는 것은 §10 이후에도 그대로다. 바뀐 것은 **협조하는 본문이 언제
멈추는가** 뿐이다.

### 9.4 파이썬 프록시 생성 — 런타임 `exec`, 빌드 타임 리소스가 아니라

`PythonProxySource.render` 가 만든다. 두 후보 중 **런타임 렌더 + `exec`** 를 골랐다.

1. **`.py` 를 놓을 자리가 없다.** Kotlin/Native 에는 `getResourceAsStream` 이 없고, iOS·androidNative·
   wasm 의 `sys.path` 는 `src/nativeInterop/cinterop/lib/...` 의 플랫폼별 CPython 트리를 가리킨다.
   생성 파일 하나를 거기에 넣고 첫 import 전에 `sys.path` 에 올리는 것은 이 저장소가 무엇에 대해서도
   풀지 않은 플랫폼별 패키징 문제다. `Python3.exec` 는 다섯 타깃에서 이미 된다.
2. **KSP 는 무엇이 설치될지 모른다.** fragment 는 Kotlin 모듈 단위이고, 테이블은 런타임에
   `UpcallTable.install` 이 조립한다. 애플리케이션이 부분집합만 설치할 수 있다(이 저장소의 테스트가
   전부 그렇게 한다). 빌드 타임 파이썬은 존재하지 않을 수도 있는 테이블을 기술하게 되고, 어차피
   런타임에 klib 들을 가로질러 재집계해야 한다 — 그것이 바로 이것이고, 정보만 더 적다.
3. **`ExposedCallable` 이 이미 다 들고 있다.** `isSuspend`·`arity`·`kind` 가 정확히 입력이고, KSP 가
   이미 싣고 있다. KSP 에서 파이썬까지 뽑으면 그 지식이 **서로 어긋날 수 있는 두 생성기**로 복제된다.

빌드 타임 안(案)에서 실제로 가치 있던 부분은 지켰다: `render` 는 **엔트리 → 소스 텍스트의 순수
함수**라서 인터프리터 없이 `commonTest`(`PythonProxySourceTest`)가 고정하고, 생성물은 동작이 아니라
읽을 수 있는 산출물이다. CPython 이 필요한 것은 `install` 뿐이고 그것은 `exec` 밖에 하지 않는다.

생성되는 모양:

    async def _pm_f_0(a0):
        _pm_r = _pm_invoke(_pm_h_0, (a0,))
        if hasattr(_pm_r, '__await__'):
            return await _pm_r
        return _pm_r

`_await_kotlin` 이 손으로 적어두었던 그 모양이다 — **awaitable 일 때만 await**. 이름은
`sys.modules` 주입으로 Kotlin 패키지 트리에 실어서 `from demo.calc import doubleLater` 가 되게 했다;
import 훅이 필요 없는 이유는 CPython 이 finder 를 보기 전에 `sys.modules` 를 먼저 보기 때문이다.

**렌더하지 않는 것:** `CallableKind.FUNCTION` 만 만든다. `METHOD`/`GETTER`/`SETTER` 는 수신자가
프록시 *인스턴스* 에서 와야 하고, `STATIC_GETTER`/`STATIC_SETTER` 는 호출이 아니라 속성이며,
`CONSTRUCTOR` 는 프록시 인스턴스를 돌려줘야 한다. 셋 다 §7 의 `PyType_FromSpec` 타입의 몫이다.

빠른 경로가 실제로 공짜인 것도 확인했다. 생성된 프록시는 두 경로를 호출자에게서 감추므로
`type(r).__name__` 을 볼 수 없다 — 대신 루프의 `create_future` 를 세었고, 이것이 더 강한 진술이다:
빠른 경로에서 `Future` 는 **반환되지 않은 것이 아니라 만들어지지도 않는다**(`created == 0`).

### 9.5 wasm — §4·§8.5 의 추론을 측정이 뒤집었다

> **§15 가 이 절의 진단을 뒤집었다.** 아래의 *관측*(무엇이 죽었는가)은 맞다. *원인*은 틀렸다 —
> `RuntimeError: unreachable` 은 3차 증상이었고, 진짜 원인은 Emscripten 의 **런타임** JSPI 감지와
> `main` 을 부르지 않는 우리 부트 코드가 어긋난 것이다. `cpython.mjs` 에서 고쳤고, 지금은
> `import selectors` 도 `import asyncio` 도 통과한다. 아래의 "첫 걸음에서 없다" 는 **틀렸다.**

§4 와 §8.5 는 wasmJs 에서 (C) 가 **교착**으로 실패할 것이라고 추론했다. 그게 아니다. 훨씬 이르다.

    import json  OK    import math   OK    import select     OK
    import socket OK   import contextvars OK
    import selectors                    RuntimeError: unreachable   <- trap
    import asyncio{,.events,.base_events}  trap (selectors 를 거쳐서)

**`import asyncio` 가 wasm 인스턴스를 trap 시켜 Node 프로세스를 죽인다.** 파이썬 예외가 아니므로
Kotlin 에서도 Python 에서도 잡을 수 없다. 그러므로 wasmJs 에서 (C) 는 느리거나 교착하는 것이 아니라
**첫 걸음에서 없다**. §8.5 가 따진 교착은 거기까지 가지 못해서 도달 불가능하다.

이것이 어떻게 발견됐는지도 적어 둔다: "루프 없이 suspend 하면 명확히 실패하는가" 를 `commonTest` 에
넣었더니 wasm 스위트 전체가 프로세스째 죽었다. 그래서 그 케이스는 `commonTest` 에 **둘 수 없다**.
`AsyncUpcallPortabilityTest` 에는 asyncio 를 건드리기 전에 반환하는 두 경로만 남겼고 — 빠른 경로와
suspend 이전 실패 — 그 둘은 다섯 타깃에서 실제로 돈다. wasm 애플리케이션이 의지할 수 있는 것도
정확히 그 둘이다.

**뒤집지 않은 것:** 루프가 도는 상태에서 진짜로 suspend 했을 때의 교착은 여전히 추론이다. 그 실험은
프로세스를 죽이거나 매달리므로 하지 않았고, 하지 않은 것을 했다고 적지 않는다.

---

## 10. 이른 통지 — 측정

§9.3 이 "되지 않는다" 고 적은 것을, §9.3 이 지목한 그 모양 그대로 만들었다. 결과부터:
**파이썬이 `Future.cancel()` 하면 Kotlin 코루틴은 완료를 기다리지 않고 다음 `ensureActive()` 에서
멈춘다.**

`AsyncUpcallEarlyCancellationTest`(desktopTest, 4개) 가 이것을 고정한다.

### 10.1 고치기 전에 빨갰다 — 측정

먼저 테스트를 쓰고 구현 전에 돌렸다. 실패 메시지가 그대로 옛 동작의 서술이다:

    Kotlin did not learn about the cancellation until the call completed
    -- the flag was clear and isDone was false
       when Python had already cancelled and yielded to its loop

**단언의 핵심은 `isCancelled` 가 아니라 그것을 *언제* 읽었는가다.** 옛 동작도 결국 같은 플래그를
세웠다 — 완료 시점에, `AsyncUpcall.resolve` 의 `done()` 검사가. 그러므로 나중에 읽는 테스트는
고치기 전에도 초록이다. 이 테스트는 **취소 이후 한 번도 재개되지 않은 호출** 에 대해
`isCancelled && !isDone` 을 읽는다. 그 조합은 옛 동작에서 성립할 수 없다.

두 번째 단언은 협조 지점이 실제로 발사되는가다: 통지 이후 **딱 한 번** 재개시키고, 그 틱에서
`ensureActive()` 가 던져 본문이 끝나는 것(`failure is CancellationException`, `isDone`)을 본다.
고치기 전에는 본문이 다시 park 했다.

### 10.2 만든 경로

    Python:  fut.cancel()
      → Future 의 done 콜백 (call_soon 으로 예약됨)
      → _pm_cancel(handle)                     (long) -> int
      → UpcallTrampoline.cancelCall            PyGILState_Ensure 무조건
      → HandleTable.resolveRaw → PendingCall.cancel()
      → 본문의 다음 ensureActive() 가 던진다

§9.3 이 적어 둔 네 가지를 그대로 따랐고, 두 가지가 예상보다 싸게 끝났다.

| §9.3 이 필요하다고 한 것 | 실제 |
|---|---|
| (a) `(long) -> int` 스텁 | `_pm_release` 와 **같은 모양**이라 새 스텁 형태 없음. 각 타깃에서 이미 있는 빌더를 한 번 더 부른다 |
| (b) `Future` 에 실을 핸들 | `HandleTable` 핸들. **속성으로 붙이지 않고** done 콜백의 기본 인자로 캡처했다 — `asyncio.Future` 가 임의 속성을 받는지에 의존하지 않게 된다 |
| (c) 다섯 타깃의 바인딩 | `_pm_cancel` 하나. 해제는 `_pm_release` 를 **재사용**한다 (핸들이 평범한 객체 핸들이므로) |
| (d) 핸들 수명 | §10.3 |

바인딩이 붙은 곳: desktop(`UpcallStub.cancelCallStubAddr`), nativeMain(`_pm_cancel` `PyMethodDef`,
iOS·androidNative 공용), android(`jni_onload.def` 의 `pmp_upcall_cancel_meth` + `UpcallCallbacks.cancel`).
wasmJs 는 `_pm_release` 조차 Python 에 게시하지 않고 Kotlin 에서 직접 부르는 구조라 붙이지 않았다 —
§9.5 대로 그 타깃에서는 `import asyncio` 가 애초에 trap 한다.

**바인딩이 없는 호스트는 깨지지 않는다.** `AsyncUpcall.armCancellationNotice` 가 `_pm_cancel` 과
`_pm_release` 가 `__main__` 에 있는지 먼저 보고, 없으면 **핸들을 등록하지도 않는다.** 그런 타깃은
§9 의 동작(완료 시점 관측)을 그대로 유지하고 아무것도 새지 않는다. 콜백 안에서 `NameError` 가 나는
쪽을 고르지 않은 이유는, 그것이 `call_exception_handler` 로 가서 아무도 보지 않고 아무도 고칠 수 없는
로그가 되기 때문이다 — §9.1 이 이미 한 번 겪은 실패 형태다.

### 10.3 핸들 수명 — 놓는 쪽이 둘인 것이 설계다

`add_done_callback` 이 **취소·성공·실패 전부에서** 불린다는 점을 그대로 썼다. 그래서 통지와 해제가
한 콜백이다:

    def _pm_done(_f, _h=_handle):
        try:
            if _f.cancelled():
                _pm_cancel(_h)
        finally:
            _pm_release(_h)

여기에 Kotlin 쪽 해제를 하나 더 뒀다 — `AsyncUpcall.resolve` 의 `finally`. **중복이 아니라 서로 다른
구멍을 막는다.**

| 놓는 쪽 | 그것만이 막는 경우 |
|---|---|
| Python done 콜백 | **협조하지 않는 본문이 취소된 경우.** 코루틴이 영원히 안 끝나므로 Kotlin 완료 경로가 돌 기회가 없다 |
| Kotlin `resolve` | **콜백이 돌기 전에 루프가 멈춘 경우.** 예약만 되고 실행되지 않은 done 콜백은 아무것도 놓지 않는다 |

둘 중 무엇이 먼저 와도 되고, 서로를 알 필요도 없다. `HandleTable` 의 해제가 세대 기반이라 **두 번째는
no-op** 이고, 슬롯이 이미 남에게 재발급된 뒤라도 세대가 어긋나 남의 것을 놓지 않는다. 같은 이유로
늦게 도착한 `_pm_cancel(stale)` 도 아무 일도 하지 않는다.

**여전히 회수되지 않는 것:** `Future` 가 영원히 settle 되지 않고 코루틴도 영원히 끝나지 않는 호출.
그 호출은 continuation 자체를 이미 흘린 상태이고, 그것을 알아채는 것은 핸들 기계의 일이 아니다.

### 10.4 누수 확인 — 양쪽 다 측정

`HandleTable.liveCount` 로 본다.

| 경우 | 관측 |
|---|---|
| 진짜 suspend 한 호출이 미결인 동안 | `baseline + 1` — 핸들이 실제로 등록된다 |
| 취소된 뒤, **코루틴이 아직 안 끝난 시점** | `baseline` — Python done 콜백이 이미 놓았다 |
| 취소 → 본문 종료까지 끝난 뒤 | `baseline` |
| 정상 완료 뒤 | `baseline` |
| **빠른 경로** | `baseline` — 등록 자체가 없다 |

두 번째 줄이 이 표에서 가장 정보량이 많다. 그 시점에는 Kotlin 쪽에서 이 호출에 대해 아무것도 실행된
적이 없으므로, **회수한 것이 Python 쪽이라는 것이 그 하나로 확정된다.** 첫 줄이 없으면 나머지가
"핸들을 애초에 안 만들었다" 와 구별되지 않으므로, 미결 중의 `baseline + 1` 을 함께 단언한다.

### 10.5 빠른 경로는 건드리지 않았다

핸들 등록은 `deliver` 의 `isDone` 조기 반환 **뒤에** 있다. 즉 suspend 하지 않은 호출은 `HandleTable`
쓰기도, `__main__` 조회도, `add_done_callback` 도 지나지 않는다. 셋으로 확인했다:

- `theFastPathRegistersNoHandleAtAll` — `liveCount` 불변
- `AsyncUpcallDeliveryTest.aSuspendingEntryThatNeverSuspendsHandsBackTheRealValueAndBuildsNoFuture`
  — **실행 중인 루프가 아예 없는 스레드에서** 통과한다. asyncio 에 손을 댔다면 실패했을 것이다
- `PythonProxyInstallTest.theSameGeneratedProxyBuildsNoFutureWhenTheKotlinBodyNeverSuspends`
  — `create_future` 호출 수 `0`

느린 경로는 `__main__` 조회 두 번(`_pm_cancel`, `_pm_release`)과 `_pm_watch` 호출 하나가 늘었다.
`runningLoop()` 와 `settleFunction()` 이 이미 같은 종류의 조회를 하고 있고, 진짜로 suspend 한 호출은
속성 조회를 세는 처지가 아니다(§8.3 과 같은 논거).

### 10.6 확인하지 않은 것 — §11 이 iOS 를 채웠다

- ~~**desktop 외의 실행.**~~ **§11 이 iOS 시뮬레이터로 채웠다.** 아래 원문은 대조용으로 남긴다.

  > 통지 경로는 `expect`/`actual` 이 하나도 없는 `commonMain` 이고 바인딩만 타깃별이다.
  > androidNative(cinterop 포함)와 wasmJs 는 **컴파일만** 확인했다. iOS·Android 기기에서 돌려보지
  > 않았다 — §7.1 이 (B) 의 완료 스레드에 대해 남겨 둔 질문이 여기에도 그대로 남는다.

  androidNative 는 여전히 컴파일만이다 — 실행하려면 에뮬레이터가 필요하고, 이번 회차에는 다른
  작업이 쓰고 있어 쓰지 않았다(`AGENTS.md` 의 규정). Android(JVM/ART)·iOS 실기기는 여전히 미확인.
- **루프가 죽은 채로 남은 핸들.** Kotlin 쪽 `finally` 해제가 그것을 막도록 되어 있지만, 그 경우를
  일부러 만들어 관측하지는 않았다.

---

## 11. desktop 밖에서의 실행 — 측정 (iOS 시뮬레이터)

§10.6 이 남긴 질문: 통지 경로(그리고 그 아래 전달 경로 전체)가 desktop 이 아닌 타깃에서 **실제로
도는가.** 컴파일이 아니라 실행이다.

### 11.1 iOS 에 `asyncio` 가 있는가 — 측정, 그리고 기존 문서의 유추는 틀렸다

`docs/design/upcall-design.md` §"Can Python call an address at all?" 는 "이 프로젝트의 iOS
`Python.framework` 는 `_ctypes` 도, `lib-dynload` 도 없다"고 적었고, §4·§8.5 는 그 문장에 기대어
iOS 의 `asyncio` 가능성을 유추만 했다(직접 확인은 하지 않았다). 그 유추는 **성립하지 않는다** —
"`Python.framework` 가 `lib-dynload` 를 안 담고 있다"는 진술 자체는 맞지만, 그것이 가리키는 대상은
앱이 링크하는 **xcframework 바이너리**(헤더 + 인터프리터 심볼뿐)이고, `asyncio` 가 실제로 찾는
곳은 **별도로 풀리는 BeeWare stdlib 아카이브**(`extractIosSimulatorStdlib` 가
`build/python-stdlib/ios-simulator` 에 푸는 것, `PYTHONHOME` 이 가리키는 자리)다. 둘은 다른 것이고,
후자를 직접 열어 확인했다:

    build/python-stdlib/ios-simulator/lib/python3.14/asyncio/               (순수 파이썬, 전부 있음)
    build/python-stdlib/ios-simulator/lib/python3.14/lib-dynload/
        _asyncio.cpython-314-iphonesimulator.so
        _socket.cpython-314-iphonesimulator.so
        select.cpython-314-iphonesimulator.so

파일이 있다는 것과 실제로 동작한다는 것은 다른 진술이므로, `AsyncioAvailabilityProbeTest`
(`iosSimulatorArm64Test`, 자기 파일 하나로 격리 — wasmJs 의 트랩과 같은 모양이 여기서도 날 경우
번지는 범위를 파일 하나로 막기 위해서다)로 직접 돌렸다:

```python
import asyncio
async def _pmp_probe(): return 41 + 1
_pmp_result = asyncio.new_event_loop().run_until_complete(_pmp_probe())
```

**통과한다.** import 도, `run_until_complete` 도 트랩 없이 정상 동작한다 — wasmJs 와 달리 iOS 는
이 지점에서 막히지 않는다. `_ctypes` 유추가 `asyncio` 에는 적용되지 않는다는 것이 이제 유추가
아니라 측정이다.

### 11.2 그런데 데스크톱의 세 테스트는 "그대로 옮길 수 있는" 것이 아니었다

`AsyncUpcallDeliveryTest`·`AsyncUpcallCancellationTest`·`AsyncUpcallEarlyCancellationTest` 를
검토한 결과, 셋 다 `commonTest`/`nativeTest` 로 옮길 수 없었다. 이유는 `asyncio` 가 아니라
**완료/관찰 스레드를 만드는 방법**이다 — 셋 다 `java.lang.Thread` 와
`java.util.concurrent.{LinkedBlockingQueue,TimeUnit}` 을 쓰고, 이것은 JVM 전용이라 Kotlin/Native
어디에도 없다. `asyncio` 만 봤다면 "옮길 수 있다"고 잘못 판단했을 것이다 — 이 저장소가 이미 가진
포터블한 부분(`AsyncUpcallPortabilityTest`, `commonTest`)이 정확히 asyncio 를 건드리기 전에
끝나는 두 경로만 남긴 이유이기도 하다: 그 두 경로는 스레드가 아예 필요 없다.

그래서 데스크톱 파일을 옮기는 대신, 같은 주장을 **새로 만든 iOS/androidNative 쪽 완료 스레드**로
다시 검증했다.

### 11.3 `NativeThread` — JVM 스레드의 자리를 대신하는 것, 그리고 왜 `Worker` 가 아닌가

`nativeTest/.../NativeThread.kt`: `pthread_create` 를 직접 부르는 얇은 래퍼
(`StableRef` + `staticCFunction` 트램폴린). `kotlin.native.concurrent.Worker` 를 쓰지 않은 이유는
이 테스트들이 답해야 하는 질문 자체가 "Kotlin/Native 런타임이 **스스로 붙인 적 없는** 스레드에서
완료가 와도 되는가"이기 때문이다 — `Worker` 는 Kotlin/Native 자신의 스레드 기계이므로 그 질문을
우회한다. 순수 `pthread_create` 는 `CycleCollectionTest.testDeallocOnAThreadCPythonCreated` 가 이미
같은 모양으로 측정해 둔 것과 정확히 같은 처지의 스레드다 — 거기서는 CPython 이 만든 pthread 가
`tp_dealloc` 슬롯을 불렀고, 여기서는 이 테스트가 만든 pthread 가 `PendingCall` 의 리스너를 부른다.

### 11.4 측정한 것 — 셋, 전부 iOS 시뮬레이터에서 통과

| 테스트 (`nativeTest`) | 데스크톱 대응 | 무엇을 다시 확인했나 | 결과 |
|---|---|---|---|
| `AsyncUpcallNativeDeliveryTest.pythonAwaitsASuspendedKotlinCallAndTheValueArrivesFromAThreadTheRuntimeNeverAttachedItself` | `AsyncUpcallDeliveryTest.pythonAwaitsASuspendedKotlinCallAndTheValueArrivesFromAKotlinThread` | (C)/(B) 의 실제 전달: 루프가 도는 동안 낯선 스레드가 GIL 을 얻어 `call_soon_threadsafe` 로 `Future` 를 해소 | **통과** |
| `AsyncUpcallNativeDeliveryTest.theFastPathStillTakesNoAsyncioOnThisTargetEither` | `theSameAwaitExpressionTakesTheShortcutWhenTheKotlinBodyNeverSuspends` | 빠른 경로가 이 타깃에서도 `asyncio` 를 안 타는가 | **통과** |
| `AsyncUpcallNativeEarlyCancellationTest.cancellingTheFutureReachesEnsureActiveWhileTheKotlinCallIsStillSuspendedOnAThreadTheRuntimeNeverAttachedItself` | `AsyncUpcallEarlyCancellationTest.cancellingTheFutureReachesEnsureActiveWhileTheKotlinCallIsStillSuspended` | §10 의 이른 통지: `_pm_cancel` → `ensureActive()` 가 **완료 전에** 던지는가 | **통과** |
| `AsyncUpcallNativeCancellationTest.aCompletionLandingOnACancelledFutureIsDroppedInsteadOfRaisingInsideTheLoop` | `AsyncUpcallCancellationTest.aCompletionLandingOnACancelledFutureIsDroppedInsteadOfRaisingInsideTheLoop` | §9.2 의 조용한 드롭: 비협조 본문이 취소된 `Future` 에 완료를 배달해도 루프가 다치지 않는가, 에러 지시자가 새지 않는가 | **통과** |

세 번 반복 실행해 타이밍에 의한 우연이 아님을 확인했다(§ "검증" 참고). `_pm_cancel` 은
`UpcallEntry.publish` 가 `_pm_resolve`/`_pm_bind`/`_pm_release` 와 함께 통째로 까는 바인딩이라
(§10.2 표가 이미 "nativeMain, iOS·androidNative 공용"이라고 적어 둔 대로) 새 배선은 필요 없었다 —
`bindUpcallOrNull` 의 native `actual`(`UpcallRawEntryPointTest.kt`)이 이미 부르고 있었다.

### 11.5 이번에도 확인하지 않은 것

- **androidNative 의 실행.** `compileTestKotlinAndroidNativeArm64` 로 새 테스트 세 파일이 그
  타깃에서도 컴파일된다는 것만 확인했다. 실행하려면 에뮬레이터가 필요하고, 이번 회차에는 다른
  작업이 쓰고 있어 비워 두지 않았다 — `_pm_cancel` 이 androidNative 에도 같은 방식으로 깔려 있으므로
  (§10.2) **성립할 것으로 기대하지만, 이것은 추론이지 측정이 아니다.**
- **Android(JVM/ART), iOS 실기기.** §8.5 가 이미 적어 둔 공백 그대로다 — 이 워크스페이스에 Android
  SDK 가 없고, 실기기를 쓰지 않았다.
- **루프가 죽은 채로 남은 핸들.** §10.6 이 남긴 그대로.

## 12. 생성된 프록시가 desktop 밖에서 설치조차 안 됐다 — 측정 (iOS 시뮬레이터)

§9.4 가 `PythonProxySource` 를 만들고 §11 이 async 경로를 iOS 에서 측정했지만, **`install()` 자체는
`desktopTest` 밖에서 한 번도 불린 적이 없었다.** 그래서 생성기와 부트스트랩이 서로 다른 것을 가정한
채로 계속 갔다. 이번에 `PythonProxyInstallTest` 를 `commonTest` 로 올려 iOS 시뮬레이터에서 돌리자
14개가 전부 빨갛게 나왔다.

### 12.1 관측한 것 — 두 가지가 독립적으로 어긋나 있었다

| # | iOS 에서 실제로 본 것 | 원인 |
|---|---|---|
| 1 | `PyException: the raw upcall entry points are not bound: define _pm_resolve(name_bytes) -> handle and _pm_invoke(handle, args_tuple) -> result` — 14/14 | `UpcallEntry.publish` 가 `_pm_invoke` 를 **일부러** 안 깔았다. 생성기의 가드는 그것 없이는 설치를 거부하고, 생성된 모든 호출이 `_pm_invoke(handle, args)` 다 |
| 2 | `PyException: bad argument type for built-in operation` — (1)을 고친 뒤 11/11 | 생성기의 지원 코드가 `_pm_resolve(name.encode('utf-8'))` 로 **bytes** 를 넘기는데, `pmResolveMethod` 가 `PyUnicode_AsUTF8` 로 읽어 `PyErr_BadArgument` 를 세우고 NULL 을 돌려줬다 |

(2)는 (1)의 뒤에 가려져 있어서, (1)을 고친 다음 `PyBytes_AsString` 폴백을 잠깐 되돌려 **따로
확인했다.** 커밋 메시지의 서술이 아니라 두 번 다 시뮬레이터에서 본 문자열이다.

세 번째로, 생성기의 지원 코드가 `_pm_bind` 를 **name → handle 로 재정의**하고 있었다. 세 부트스트랩이
같은 이름을 handle → callable 로 깔기 때문에, `exec` 가 지나가면서 네이티브 쪽을 파괴한다. desktop 은
`_pm_bind` 를 아예 안 깔아서 덮어쓸 것이 없었고, 그래서 조용했다.

### 12.2 어느 쪽을 맞췄나, 그리고 왜

**부트스트랩 쪽을 생성기에 맞췄다.** `UpcallEntry.publish` 가 이제 `_pm_invoke` 도 깐다
(`METH_VARARGS`, self 없이 `args = (handle, args_tuple)`).

원래 안 깔던 이유는 주석에 적혀 있었다 — "handle 을 별도 인자로 파이썬 손에 쥐여주지 않기 위해".
**그 이유는 옆 파일과 대조하면 성립하지 않는다:**

- 아무것도 그것을 강제하지 않았다. `_pm_bind` 가 **파이썬에서 온 생 정수**를 받아 그 handle 위의
  callable 을 돌려준다. `_pm_release`/`_pm_cancel` 도 생 handle 을 받는다. `_pm_bind(h)(*a)` 와
  `_pm_invoke(h, a)` 는 같은 트램폴린, 같은 인자, 같은 거절 경로다. 새로 열리는 권한이 없다.
- 유일한 소비자가 그것을 필요로 한다. 생성기가 만드는 모든 호출이 `_pm_invoke` 이고, 가드가 그
  이름 없이는 설치를 거부한다. 안 깔아서 지켜진 것은 handle 이 아니라, **desktop 을 뺀 모든 타깃에서
  프록시가 아예 안 도는 상태**였다.
- 반대 방향(생성기가 `_pm_bind` 로 `_pm_invoke` 를 합성)은 **호출 1건당 `PyCMethod_New` 할당**이고,
  런타임에 갈라지는 부트스트랩 규약을 둘로 유지하게 된다.

인자 타입은 **`str` 과 `bytes` 를 둘 다 받게** 했다(`PyUnicode_AsUTF8` 실패 → `PyErr_Clear` →
`PyBytes_AsString`). 생성기는 계속 bytes 를 보낸다 — desktop 의 `ctypes.CFUNCTYPE(c_long, c_char_p)`
가 `str` 을 **거절**하므로, 한 번 적어서 전부에서 도는 철자는 bytes 뿐이다. 비용은 이름 1건당 실패한
`PyUnicode_AsUTF8` 하나이고, 이름은 설치 때 한 번만 푼다.

생성기의 name → handle 헬퍼는 `_pm_lookup` 으로 이름을 바꿨다. `PythonProxySourceTest` 에
`support` 가 `_pm_bind`/`_pm_resolve`/`_pm_invoke`/`_pm_release`/`_pm_cancel` 중 어느 것도
`def` 하지 않는다는 회귀 가드를 넣었다.

### 12.3 테스트를 어디로 옮겼나

| 위치 | 내용 | 도는 곳 |
|---|---|---|
| `commonTest/PythonProxyInstallTest` | 프록시 계약 11개 + fixture(`ProxyFragment`, `ProxyCounter`, `ProxyLoopHarness`) | desktop · iOS · wasmJs · androidNative(컴파일) · Android(계측) |
| `desktopTest/PythonProxyDeliveryTest` | 스레드가 필요한 전달 3개 (`java.lang.Thread`) | desktop |
| `nativeTest/PythonProxyNativeDeliveryTest` | 같은 3개를 `pthread_create` 로 (**새로 추가**) | iOS · androidNative |

부트스트랩이 없는 타깃은 **건너뛰지 않는다.** `publishesProxyEntryPoints`(`expect val`, 타깃별
상수)가 false 인 곳에서는 생성 모듈 자신의 가드가 뜨는 것을 단언한다. 런타임에 `globals()` 를
들여다보지 않는 이유가 이것이다 — 그러면 발행을 그만둔 타깃이 조용히 반대 분기로 넘어가 초록으로
남고, 그게 바로 이번에 고친 실패 양식이다.

측정: iOS 시뮬레이터 318개 0 실패(이전 303), desktop 342개 0 실패(스킵 1), wasmJs 328개 0 실패,
`compileTestKotlinAndroidNativeArm64` 와 `:sample:compileKotlinIosSimulatorArm64` 통과.

### 12.4 못 맞춘 두 타깃과, 각각 무엇이 필요한가

> §13 에서 **Android(ART) 는 맞췄다.** 아래 Android 항목은 그때의 진단이고, **절반만 맞았다.**
> §14 에서 **wasmJs 도 맞췄다.** 아래 wasmJs 항목의 결론은 **틀렸다** — 새 `@WasmExport` 는
> 필요하지 않았고, 이미 있는 하나로 다섯 개를 전부 깔 수 있었다.

- **wasmJs — 안 된다.** 파이썬으로 건너가는 것이 *바인딩된 callable* 하나뿐이다(`UpcallEntry.bind`
  가 유일한 `@WasmExport` `pmp_invoke` 위에 만든다). 이름 해석은 Kotlin 안에서 끝난다.
  `_pm_resolve`/`_pm_invoke` 를 깔려면 `@WasmExport` 가 더 필요하고, `@WasmExport` 는 `.wasm` 을
  만드는 컴파일에서만 유효하므로 **라이브러리가 아니라 임베딩하는 애플리케이션이**
  (`wasmJsTest/UpcallExports.kt` 모양으로) 선언해야 한다. 게다가 이 빌드는 `import asyncio` 가
  trap 되므로 suspend 프록시는 그다음에도 첫 `await` 에서 죽는다. 억지로 맞추지 않았다.
  → **§14 가 가운데 문장을 뒤집었다.** `@WasmExport` 제약 자체는 맞고, asyncio trap 도 맞다.
  틀린 것은 "그러므로 새 export 가 필요하다" 는 결론 하나다.
- **Android(ART) — C 한 조각이 빠졌다.** §11.4 이후로도 "경계 shim 이 없다"고 적혀 있었지만 그것은
  틀렸다. `androidMain` 의 `UpcallEntry.publish` 가 `jni_onload.def` 의 C shim 을 통해
  `_pm_resolve`/`_pm_bind`/`_pm_release`/`_pm_cancel` 을 실제로 깐다(그래서
  `UpcallThreadAttachTest` 가 `threading.Thread` 에서 업콜을 돌린다). 빠진 것은 `_pm_invoke` 하나뿐:
  기존 `pmp_upcall_invoke_meth` 옆에 `args[0]` 에서 handle, `args[1]` 에서 인자 튜플을 읽는
  `pmp_upcall_invoke_free_meth` 를 두고 `pmp_upcall_publish` 에 항목 하나를 더하면 된다. Kotlin 은
  빠진 것이 없다 — `UpcallCallbacks.invoke(long, long)` 이 이미 그 모양이고 method ID 도 있다.
  **에뮬레이터 없이 돌릴 수 없어서 넣지 않았다**; 테스트 쪽 `publishesProxyEntryPoints` 가 false 로
  같은 메모를 들고 있으므로, 구현되는 날 "이 타깃은 거절한다"는 단언이 실패해서 알려준다.

## 13. ART 에서 프록시가 돈다 — 그리고 §12.4 의 진단은 절반만 맞았다

에뮬레이터에서 돌려보고 맞췄다. 필요한 것은 **C 함수 하나가 아니라 C 변경 두 개**였다.

### 13.1 기록된 절반: `_pm_invoke`

§12.4 가 적은 그대로다. `jni_onload.def` 에 `pmp_upcall_invoke_free_meth(self, args)` 를 두고
(`args[0]` 이 handle, `args[1]` 이 인자 튜플), `g_pmInvokeFreeDef` 를 `upcall_publish` 에 더했다.
Kotlin 은 정말로 빠진 것이 없었다 — `UpcallCallbacks.invoke(long, long)` 과 그 method ID 를 그대로
썼다. `PyMethodDef` 설치는 개수를 세는 배열이 아니라 `if (!pmp_install_method(...)) return 0;` 의
연쇄이므로 어긋날 리터럴이 애초에 없다(`RegisterNatives` 의 개수는 이전부터 `sizeof` 계산이다).

### 13.2 기록되지 않은 절반: `_pm_resolve` 가 `bytes` 를 안 받았다

`_pm_invoke` 만 넣고 돌린 결과가 이것이다 — **가드는 통과하고 그다음 줄에서 죽었다.**

| | pmp_api26 | pmp_api36 |
|---|---|---|
| `_pm_invoke` 만 추가 | 11개 전부 실패: `TypeError: bad argument type for built-in operation` | 같음 |
| `_pm_resolve` 까지 고침 | 11개 전부 통과 | 같음 |

`pmp_upcall_resolve_meth` 는 인자를 `PyUnicode_AsUTF8` 로만 읽었는데, `PythonProxySource` 의
`_pm_lookup` 은 `bytes` 를 보낸다(desktop 이 `ctypes.CFUNCTYPE(c_long, c_char_p)` 로 resolver 에
닿고 `c_char_p` 가 `str` 을 거부하므로, 모든 호스트에서 통하는 철자는 `bytes` 뿐이다). nativeMain 의
`pmResolveMethod` 는 **바로 그 이유로 이미 두 철자를 다 받게 고쳐져 있었고**, ART shim 만 안 고쳐져
있었다. 아무도 묻지 않았기 때문이다 — 이 shim 의 유일한 호출자였던
`androidInstrumentedTest/bindUpcallOrNull` 이 `str` 을 넘긴다.

`support` 의 주석은 "다른 부트스트랩은 전부 두 철자를 받는다"고 단언하고 있었다. 그것은 **검증된
적 없는 문장**이었고, ART 에서 거짓이었다.

**소스를 읽어서는 찾을 수 없는 종류의 결함이다.** 두 번째 절반은 런타임에만 존재하고, 첫 번째
절반을 고쳐야 비로소 도달한다. §12.3 이 `PythonProxyInstallTest` 를 `commonTest` 로 옮긴 이유가
이것 그대로다.

### 13.3 순서

`publishesProxyEntryPoints` 의 ART 값은 **11개가 실제로 통과하는 것을 본 뒤에** true 로 바꿨다.
그 전 단계에서는 두 번 다 빨간색이었고, 두 실패 메시지가 서로 다른 것을 가리켰다(먼저
`TypeError`, 고친 뒤 "Expected an exception to be thrown, but was completed successfully").

### 13.4 측정

| | 결과 |
|---|---|
| ART 계측 (`connectedDebugAndroidTest`) | **336개 0 실패** × pmp_api26 · pmp_api36 (그중 `PythonProxyInstallTest` 11개가 이제 진짜 경로) |
| androidNative (`androidNativeArm64Test`) | 316개 0 실패 × 2대 |
| desktop (`desktopTest`) | 342개 0 실패, 스킵 1 |

### 13.5 sample

`ProxyDemo.android.kt` 는 "unavailable on Android" 를 반환하고 있었고, `UpcallDemo.android.kt` 의
§3 은 "the boundary shim is desktop-only today" 라고 적혀 있었다. 둘 다 이제 거짓이므로 고쳤다.
두 에뮬레이터에서 앱을 띄워 확인한 화면:

| 섹션 | pmp_api26 · pmp_api36 |
|---|---|
| 3 — 업콜 | `handle 4294967327 -> 0 · with args -> presses x3 = 0` (Kotlin 이 아니라 **파이썬이** `_pm_resolve`/`_pm_invoke` 로 호출) |
| 5 — 클래스 프록시 | `installed over PyMethodDef via JNI: 466 lines, 2 proxy classes`, `Greeter('Kotlin').greet(2) -> hello Kotlin! hello Kotlin!`, `g.greetings = 99 -> AttributeError (private set held)` |
| 6 — companion | `Greeter.forget() -> 100, then built=0`, `Greeter('x').built -> AttributeError (companion is class-only)` |
| 7 — fast path | `await g.greetNow(1) -> hello fast path!`, `Futures created -> 0` |
| 7 — really suspends | `await g.greetLater(2) -> hello slow path! hello slow path!`, `Futures created -> 1`, `raised -> None`, `completer thread -> clean` |

마지막 줄이 iOS 가 아직 못 하는 것이다: `awaitSuspendingDemo` 는 파킹된 continuation 을 재개할
Kotlin 스레드가 필요한데, `androidMain` 은 Kotlin/JVM 이라 `java.lang.Thread` 로 desktop 과 같은
모양을 쓸 수 있다. 재개는 CPython 도 ART 도 모르는 스레드에서 오므로, 전달뿐 아니라 `pmp_attach`
까지 함께 돈다.

## 14. wasm 에서도 프록시가 돈다 — §12.4 의 "새 export 가 필요하다" 는 틀렸다

§12.4 는 wasmJs 를 유일한 미구현 타깃으로 남기며 이렇게 적었다: `_pm_resolve`/`_pm_invoke` 를 깔려면
**각자 새 `@WasmExport`** 가 필요하고, `@WasmExport` 는 `.wasm` 을 만드는 컴파일에서만 유효하므로
라이브러리가 아니라 임베딩 앱이 선언해야 한다 — 그러므로 라이브러리는 할 수 있는 것이 없다.

**전제는 전부 맞는데 결론이 틀렸다.** 물어보지 않은 질문이 하나 남아 있었다: *이미 하나가
export 되어 있다면, 그 하나로 다 할 수 없나?*

### 14.1 이미 있던 하나가 누구 것이었나

먼저 확인한 것은 그 하나가 **누구의 컴파일에서 나오는가**다. §12.4 의 진단은 "임베딩 앱이 선언해야
한다" 인데, 지금 그것을 선언하고 있는 것은:

    python-multiplatform/src/wasmJsTest/kotlin/python/native/ffi/UpcallExports.kt

    @kotlin.wasm.WasmExport("pmp_invoke")
    fun pmpInvoke(selfPtr: Int, argsPtr: Int): Int = UpcallEntry.invokeMethod(selfPtr, argsPtr)

**테스트 컴파일이다.** 그리고 그 파일의 주석이 이미 정확히 그렇게 적고 있다 — "이것은 테스트
스캐폴딩이 아니라, 이 라이브러리를 임베딩하는 애플리케이션이 스스로 써야 하는 파일이며, 스위트가
애플리케이션이 지나가는 경로를 그대로 지나가도록 여기에 재현해 둔 것" 이다. 즉 §12.4 의 진단은
**이미 충족되어 있었다.** 임베딩 앱이 선언해야 하는 것은 맞고, 이미 선언되어 있었다.

배선은 이렇다. `wasmJsMain/UpcallEntry` 가 `bindings.pmpRegisterUpcall("pmp_invoke")` 로 그 export 를
CPython 의 `__indirect_function_table` 에 `Table.set` 하고 (반환값이 곧 C 함수 포인터), 그것을
`ml_meth` 으로 하는 `PyMethodDef` 를 하나 `malloc` 해서, `PyCFunction_NewEx(def, self, NULL)` 로
callable 을 만든다. 파이썬이 부르는 함수 포인터는 그 하나뿐이다.

### 14.2 디스패처는 성립한다 — 그리고 op 인자조차 필요 없다

과제는 "`pmp_invoke(op, a, b)` 처럼 op 코드로 갈라라" 였는데, 실제로는 **op 인자를 더할 필요가
없었다.** `PyCFunction` 은 함수 포인터*와* `self` 를 함께 들고 다니고 `PyCFunction_NewEx` 는 호출마다
새 객체를 만든다. 그러므로 **export 하나가 이미 서로 다른 파이썬 callable 을 얼마든지 뒷받침한다** —
`UpcallEntry.bind` 가 `CallableHandle` 로 계속 해오던 것이 정확히 그것이다.

핸들이 들어갈 자리에 핸들이 될 수 없는 값을 넣으면 같은 포인터가 디스패처가 된다:

| `self` | 파이썬에 깔리는 이름 | 모양 |
|---|---|---|
| `-2` | `_pm_resolve` | `(name: str \| bytes) -> handle`, 없으면 `-1` |
| `-3` | `_pm_invoke` | `(handle, args_tuple) -> result` |
| `-4` | `_pm_bind` | `(handle) -> callable` |
| `-5` | `_pm_release` | `(handle) -> int` |
| `-6` | `_pm_cancel` | `(handle) -> int` |
| 그 외 | (바인딩된 callable) | `UpcallTrampoline.invoke(self, args)` |

**왜 음수가 안전한가:** `CallableHandle.isValid` 가 `raw >= 0` 이고, `ObjectReference` 는
`(generation shl 32) or slot` 이며 generation 은 1 이상이다. 살아 있는 핸들은 어느 쪽이든 음수가 될
수 없다. `-1` 은 일부러 op 로 쓰지 않았다 — `CallableHandle.NONE` 이고, 트램폴린 자신의 거절 경로에
계속 도달해야 한다.

그래서 **새 `@WasmExport` 는 0 개**이고, 임베딩 앱이 쓰는 파일은 이전과 글자 하나 다르지 않다.
`verifyWasmAbiSignatures` 도 그대로다 (`317 @WasmImport` + 글루 3 — 새 바인딩이 없다).

생성기 쪽 얇은 shim 은 필요 없었다. `PythonProxySource` 가 요구하는 두 이름이 그대로 깔리므로
`support` 절의 `_pm_lookup` 이 이미 있는 그대로 동작한다. 다만 `_pm_resolve` 는 **`str` 과 `bytes` 를
둘 다** 받게 썼다 — §13.2 에서 ART 가 정확히 그 자리에서 죽었기 때문이다.

### 14.3 순서 — 고치기 전에 빨간 것을 봤다

`publishesProxyEntryPoints` 의 wasmJs 값은 10개가 실제로 통과하는 것을 본 뒤에 true 로 바꿨다.
그리고 그 통과가 진짜인지 확인하려고 `bindUpcallOrNull` 에서 `UpcallEntry.publish` 호출만 빼고 다시
돌렸다:

    PythonProxyInstallTest  11/11 FAILED
    PyException: the raw upcall entry points are not bound: define _pm_resolve(name_bytes)
    -> handle and _pm_invoke(handle, args_tuple) -> result before installing proxies

생성 모듈 자신의 가드다. 통과가 부트스트랩에 실제로 달려 있다는 뜻이고, 빼면 §12.1 이 iOS 에서 본
것과 같은 문자열이 wasm 에서 나온다.

### 14.4 asyncio 는 여전히 안 된다 — 그래서 상수가 둘이다

> **§15 가 이 절의 전제를 뒤집었다.** `import asyncio` 는 이제 **통과한다.** 상수를 `false` 로
> 두는 결론 자체는 유지되지만 **이유가 바뀌었다** — trap 이 아니라, 이 상수가 게이트하는 테스트가
> 만드는 *기본* 이벤트 루프를 아직 만들 수 없기 때문이다(`socketpair` → `require('ws')`, §15.7).
> 아래 문단의 "trap 시킨다" 는 더 이상 사실이 아니다.

§9.5 는 그대로다: `import asyncio` 가 이 wasm 빌드에서 **인스턴스를 trap** 시킨다. 파이썬 예외가
아니라 프로세스가 죽는 것이므로 Kotlin 에서도 파이썬에서도 잡을 수 없고, 부트스트랩이 생겼다고
달라지지 않는다.

그러므로 `publishesProxyEntryPoints` 하나로 두 가지를 말하게 두지 않고 **`proxyBootstrapSupportsAsyncio`
를 따로 뒀다.** 하나로 뭉치면 "프록시가 아예 없다" 와 "await 할 수 있는 프록시가 없다" 가 같은
문장이 되고, wasm 에서 실제로 성립하는 10개의 계약을 조용히 단언하지 않게 된다.

이 상수가 거짓인 곳에서는 **거절을 단언하지 않는다.** 다른 미구현 타깃과 다른 점이 이것이다 —
거기서는 생성 모듈의 가드가 뜨는 것을 볼 수 있지만, 여기서 관측되는 것은 오류가 아니라 프로세스
사망이라 단언할 대상이 없다. 정직한 모양은 그 한 줄을 돌리지 않는 것이고, 상수가 그 사실을 적는
자리다.

### 14.5 측정

| | 결과 |
|---|---|
| wasmJs (`wasmJsNodeTest`) | **340개 0 실패 0 스킵** — 기준선과 동일. `PythonProxyInstallTest` 11개 중 10개가 이제 진짜 경로, 1개(asyncio)는 §14.4 |
| desktop (`desktopTest`) | 360개 0 실패, 스킵 1 — 회귀 없음 |
| `verifyWasmAbiSignatures` | 통과, 317 + 글루 3 (변화 없음) |
| `compileTestKotlinAndroidNativeArm64`, `compileTestKotlinIosSimulatorArm64` | 통과 |

`GeneratedProxyCostTest` 가 wasm 에서 처음으로 숫자를 낸다 (경계 자체가 ~300 ns 인 타깃이다):

```
Net of the harness floor (62.48 ns), and what the proxy layer adds
  module function   raw 301.91 ns   proxy  318.98 ns   1.05x  (+17.06 ns)
  constructor       raw 719.75 ns   proxy 1070.98 ns   1.48x  (+351.23 ns)
  instance method   raw 339.22 ns   proxy  357.47 ns   1.05x  (+18.24 ns)
  property read     raw 290.94 ns   proxy  308.41 ns   1.06x  (+17.47 ns)
  property write    raw 360.42 ns   proxy  438.77 ns   1.21x  (+78.34 ns)
  static read       raw 228.82 ns   proxy  300.94 ns   1.31x  (+72.12 ns)
  module read       raw 228.82 ns   proxy  324.39 ns   1.41x  (+95.57 ns)

Resolution: two identical raw rows differ by -73.09 ns
HandleTable roots before the timed loops: 1, after: 1

--- Generated proxy install cost ---
  generated source            338 lines, 14052 chars
  PythonProxySource.render()  186.63 us
  PythonProxySource.install() 2342.14 us   (...of which Python3.exec 2155.50 us)
```

**해상도 주의:** 이 타깃의 동일한 두 raw 행이 73 ns 차이가 난다. 위의 static/module 행들의 +72…+96 ns
는 그 해상도와 같은 크기이므로 **숫자로 읽으면 안 된다.** 읽을 수 있는 것은 생성자의 +351 ns 뿐이고,
그것은 desktop·시뮬레이터와 같은 이유다 (`type.__call__` + `__init__` 프레임 + 속성 저장 + `__del__`).

`HandleTable roots before/after: 1 → 1` 이 별도로 말하는 것이 하나 있다: 만 번의 `Counter(10)` 이
전부 `__del__` → `_pm_release` 로 돌아왔다. 디스패처가 release 를 invoke 경로로 잘못 보냈다면 조용히
전부 샜을 자리다.

### 14.6 남은 것

- **async 프록시.** ~~§9.5 의 trap 이 풀리지 않는 한 불가능하다. 이 빌드의 `selectors` 가
  `RuntimeError: unreachable` 로 죽는 것이 원인이고, 그것은 이 문서가 아니라 CPython wasm 빌드
  쪽 문제다.~~ **§15 가 이 항목을 뒤집었다.** trap 은 풀렸고, 원인은 CPython wasm 빌드가 아니라
  우리 부트 코드였다. 남은 것은 trap 이 아니라 **기본 이벤트 루프**다 (§15.7).
- **`sample` 의 wasm 경로.** §13.5 가 Android 에 대해 한 것을 wasm 에 대해서는 하지 않았다.

---

## 15. `selectors` trap 의 원인 — 측정, 그리고 §9.5·§14.4·§14.6 의 진단은 틀렸다

§9.5 는 `import selectors` 가 `RuntimeError: unreachable` 로 죽는 것을 관측하고 거기서 멈췄다.
§14.6 은 그것을 "CPython wasm 빌드 쪽 문제" 로 넘겼다. **둘 다 틀렸다.** 원인은 `selectors` 도,
`asyncio` 도, wasm 도, CPython 빌드도 아니다. **우리 쪽 부트 코드다.** 그리고 고쳤다.

### 15.1 관측한 것

`RuntimeError: unreachable` 은 원인이 아니라 3차 증상이었다. 실제 순서는 이렇다:

    select.poll().poll(0)
      -> SuspendError: trying to suspend without WebAssembly.promising     <- 1차, 진짜 원인
      -> SuspendError 가 CPython 의 C 프레임을 Py_END_ALLOW_THREADS 없이 되감는다
      -> 바깥 withGIL{} 의 PyGILState_Release 가
         Fatal Python error: PyGILState_Release: thread state ... must be current when releasing
      -> abort()  ->  wasm `unreachable` 오피코드                            <- 3차, 보고된 것

격리된 프로브를 gradle 로 돌려 1차와 2차를 직접 읽었다. `RuntimeError: unreachable` 만 보고
"파이썬 예외가 아니다" 라고 적었을 때 놓친 것이 그 위의 두 줄이다.

### 15.2 왜 `select` 는 되고 `selectors` 는 안 됐나 — §9.5 가 지목한 그 대비의 답

**모듈을 import 하는 것과 그 모듈의 함수를 호출하는 것의 차이다.** `import select` 는 아무것도
호출하지 않는다. `selectors.py` 는 import 시점에 호출한다:

    582:  selector_obj.poll(0)          # _can_use(method) 안
    600:  elif _can_use('poll'):        # DefaultSelector 선택

이 빌드의 `select` 에는 `kqueue`/`epoll`/`devpoll` 이 없으므로(`hasattr` 은 전부 False — 안전하게
넘어간다) 실제로 **호출까지 가는 첫 후보가 `poll`** 이다. 그래서 `selectors` 가 첫 희생자였고,
`asyncio` 는 `selectors` 를 거쳐서만 죽었다. §9.5 가 "막는 것은 asyncio 가 아니라 그 한 모듈" 이라고
좁힌 것은 맞았지만, 한 칸 더 들어가면 **모듈이 아니라 그 한 syscall** 이다.

### 15.3 원인 — Emscripten 은 JSPI 를 빌드 타임이 아니라 **런타임에** 결정한다

CPython 의 링크 라인에 `-sJSPI` 는 **없다.** 그런데도 JSPI 를 쓴다. 글루가 이렇게 되어 있다:

    function __block_for_int(p) { return p }
    if (WebAssembly.Suspending) { __block_for_int = new WebAssembly.Suspending(__block_for_int) }
    function __maybe_poll_async(...) { if (!WebAssembly.promising) { return null } ... }

그리고 짝이 되는 `WebAssembly.promising` 래퍼를 **`main` 하나에만** 씌운다.

    if (name === "main") { const main = WebAssembly.promising(orig.sym); ... }

**우리는 `main` 을 부르지 않는다.** 이 파일 맨 위에 적혀 있는 그대로, 부트업은 `Python3.initialize()`
가 `Py_Initialize` 를 다른 C API 와 똑같이 직접 wasm 호출로 발행하는 것이다. 그러므로 JSPI 가 있는
런타임에서는 블로킹 syscall 이 suspending import 가 되는데 **스택 어디에도 promising 프레임이 없다.**
첫 번째 블로킹 syscall 이 프로세스를 가져간다.

즉 이것은 "wasm 에서 asyncio 가 안 된다" 가 아니라 **`main` 을 쓰지 않는 임베딩이 Emscripten 의
JSPI 경로와 맞지 않는다** 는 문제였다. 우리가 `main` 을 쓰지 않는 것은 의도한 설계다.

### 15.4 노드 버전이 갈랐다 — 같은 wasm 바이너리, 반대 결과

런타임 감지이므로 **바이너리가 아니라 실행 환경이 결정한다.** 같은 `python.wasm` 으로 측정했다:

| | `select.poll().poll(0)` | `import selectors` |
|---|---|---|
| Node 22 (JSPI 없음) | `[]` 반환 | OK — `PollSelector` |
| Node 26 (JSPI 기본 켜짐) | `SuspendError` | 프로세스 사망 |

gradle 의 wasm 테스트 러너는 Node **26** 에서 돈다. §9.5 를 측정한 것도 그 러너다. Node 26 에는
JSPI 를 끄는 V8 플래그가 없다 — 실험 기능이 아니라 출시된 기능이라 `--experimental-wasm-jspi` 가
아예 없다.

### 15.5 고친 것 — `cpython.mjs` 에서 두 줄

    delete WebAssembly.promising;
    delete WebAssembly.Suspending;

Emscripten 팩토리를 부르기 **전에** 지운다. 그러면 Node 26 이 Node 22 의 동기 경로로 돌아간다.
이 라이브러리가 원하는 것이 정확히 그쪽이다: 모든 호출이 Kotlin 에서 **동기 wasm 호출**로 도착하므로,
블로킹 syscall 은 실제로 **블로킹해야** 한다. 비동기 경로는 쓸 데가 없다.

ES import 는 호이스팅되므로 이 시점에 `python.mjs` 는 이미 평가되어 있다. 상관없다 — JSPI 래핑은
모듈 평가가 아니라 **팩토리 호출 안에서** 일어난다. 측정으로 확인했다.

**stdlib 을 건드리지 않는다는 것이 이 선택의 핵심이다.** `sys.modules['selectors']` 에 shim 을
넣는 방안도 후보였지만 채택하지 않았다:

- shim 은 import 만 통과시킨다. selector 루프는 **매 이터레이션마다** `poll`/`select` 를 부르므로
  첫 이터레이션에서 똑같이 죽는다. 증상을 한 칸 뒤로 미룰 뿐이다.
- 사용자가 진짜 `selectors` 를 원할 때 조용히 가짜를 준다. 명시적 opt-in 을 붙여야 하는 것은 이 쪽이다.
- 고칠 지점이 아니다. 문제는 stdlib 이 아니라 **우리 부트 코드**였다.

지금 `selectors` 는 **진짜 모듈**이고 `DefaultSelector` 도 진짜 `PollSelector` 로 해결된다.
그러므로 opt-in 도 필요 없다 — 끄고 켤 동작 차이가 없고, 되던 것이 늘어날 뿐이다.

### 15.6 측정 — 어디까지 가나

`WasmSelectorsImportTest`(wasmJsTest, 4개) 가 고정한다. 고치기 전에 빨간 것을 봤다: 프로브를
gradle 로 돌려 §15.1 의 fatal error 를 직접 읽었고, 그 실행은 테스트 실패가 아니라
`Test running process exited unexpectedly` 였다.

| | 결과 |
|---|---|
| `select.poll().poll(0)`, `select.select(...)` | **통과** — suspend 하지 않고 반환한다 |
| `import selectors` → `DefaultSelector is PollSelector` | **통과** — 진짜 모듈, 진짜 디스패치 |
| `import asyncio`, `asyncio.events`, `asyncio.base_events` | **통과** |
| selector 를 안 쓰는 루프로 코루틴 1개 완주 (`await Future`, `asyncio.sleep(0)`) | **통과** — 84 |

즉 **async 업콜이 필요로 하는 표면은 이 타깃에서 성립한다**: 도는 루프, 진짜 `asyncio.Future`,
`create_future`, `call_soon`.

### 15.7 남은 것 — 기본 루프는 여전히 못 만든다 (다른 원인)

`asyncio.run()` 은 아직 안 된다. **JSPI 와 무관한 별개의 벽이다:**

    BaseSelectorEventLoop.__init__ -> _make_self_pipe -> socket.socketpair()
      -> Emscripten ___syscall_listen -> require('ws')     ->  MODULE_NOT_FOUND

Emscripten 은 소켓을 Node 의 `ws` 패키지로 흉내내는데 그것이 설치되어 있지 않다. 이것은 §15.5
이전에도 있었고 지금도 있다 — Node 22 에서 측정했을 때도 똑같이 났다. 그래서 §15.6 의 마지막 줄이
**커스텀 루프**다: Pyodide 가 같은 문제를 푸는 방식이고, selector 도 self-pipe 도 열지 않으므로
두 벽을 모두 비켜간다.

`proxyBootstrapSupportsAsyncio` 는 **`false` 로 둔다.** `import asyncio` 는 이제 되지만 그 상수가
게이트하는 테스트는 *기본* 이벤트 루프를 만들고 `create_future` 를 세므로, 그 경로는 아직 없다.
상수를 뒤집으려면 라이브러리가 커스텀 루프를 제공할지부터 정해야 하고 — 그것은 §7 의 프록시 설계가
아니라 새 결정이다. 여기서 하지 않는다. **§9.5 의 "wasm 에서 (C) 는 첫 걸음에서 없다" 는 이제
틀렸다. 첫 걸음은 있다. 없는 것은 기본 루프다.**

### 15.8 `ws` 를 실제로 넣어 봤다 — (A) 는 패키지 문제가 아니라 구조적으로 막혀 있다

§15.7 이 연 질문("`ws` 를 넣으면 그것만으로 되는가")에 실제로 답했다. `wasmJsTest` 에
`npm("ws", "8.18.0")` 을 임시로 추가하고, 격리된 프로브 파일(`WasmAsyncioRunProbeTest`, 커밋하지
않음)에서 `asyncio.run()` 을 호출해 관측했다.

**한 걸음 나아갔다.** `MODULE_NOT_FOUND` 로 프로세스가 죽던 것이, `ws` 를 넣자 진짜 파이썬
예외로 바뀌었다:

    OSError: [Errno 28] Invalid argument
      at socket.py:633, _fallback_socketpair -> lsock.accept()

**그리고 거기서 막힌다 — 근본적으로.** CPython 이 임베드하는 Emscripten 글루(`python.mjs`)를
직접 읽어 원인을 확인했다. `SOCKFS.websocket_sock_ops` 는 소켓을 실제 `ws` 연결로 흉내내는데,
`listen()` 은 `require("ws").Server` 로 진짜 `WebSocketServer` 를 열고, `connect()` 는 진짜
`WebSocket` 클라이언트를 연다 — 루프백이라도 실제 TCP 핸드셰이크와 WebSocket 업그레이드가 오간다.
`accept()` 의 구현은 이렇다:

    accept(listensock){
        if (!listensock.server || !listensock.pending.length) { throw new FS.ErrnoError(28) }
        ...
    }

즉 **핸드셰이크가 끝나 `pending` 에 연결이 들어와 있어야 `accept()` 가 성공한다.** 그런데
`_fallback_socketpair()` 는 `bind() -> listen() -> connect() -> accept()` 를 전부 한 C 함수
안에서, 하나의 동기 wasm 호출로 순서대로 부른다. §15.3 이 이미 적어 둔 그대로 이 임베딩은 `main` 을
부르지 않으므로 이 호출들 사이에 **Node 이벤트 루프가 틱을 돌 기회가 없다** — `connect()` 가 시작한
WebSocket 핸드셰이크는 다음 매크로태스크가 되어야 진행되는데, 그 다음 매크로태스크가 올 시점에는
이미 `accept()` 가 (그리고 그 바깥의 동기 wasm 호출 전체가) 끝나 있다. `pending` 은 언제나 비어
있고, `accept()` 는 언제나 EINVAL 이다.

**이것은 `ws` 가 없어서 나는 에러가 아니다.** `ws` 를 설치하면 1차 장벽(`require` 실패)은 없어지고
2차 장벽(핸드셰이크와 동기 호출의 경쟁)이 드러날 뿐이며, 2차 장벽은 **어떤 npm 패키지로도 못
넘는다** — `socketpair()` 를 웹소켓으로 흉내내는 구현 자체가 비동기이고, 이 라이브러리의 부트업
모델(모든 호출이 Kotlin 에서 동기 wasm 호출로 도착) 은 정확히 §15.3 이 JSPI 를 꺼야 했던 이유와
같은 이유로 그 비동기성을 담을 곳이 없다. JSPI 를 다시 켜도 소용없다 — 켜져 있었다면 `main` 을
부르지 않는 한 애초에 `SuspendError` 로 죽었을 자리다(§15.3). 그러므로 (A) 는 "아직 안 해서 안 되는
것"이 아니라 **이 임베딩 모델과 구조적으로 상충한다.**

**브라우저에서는 더 나쁘다.** 여기서 확인한 것은 Node 전용 경로다 — `require("ws")` 는
`ENVIRONMENT_IS_NODE` 분기에서만 있고, 브라우저 분기는 전역 `WebSocket` 을 그대로 쓴다. 즉
브라우저에서 `listen()` 자체가 없다(`if(!ENVIRONMENT_IS_NODE){throw new FS.ErrnoError(138)}`) --
`_fallback_socketpair` 가 루프백 서버를 열 수조차 없다. 설령 (A) 가 Node 에서 성립했더라도 그것은
**Node 테스트 환경 전용 해법**이었을 것이고, 브라우저 배포에는 애초에 적용되지 않았다. 이번 측정으로
그 구분조차 낼 필요가 없어졌다 — Node 에서도 막히기 때문이다.

측정 후 `ws` 의존성과 프로브 파일은 되돌렸다(`kotlinWasmUpgradeYarnLock` 으로 lockfile 도 원복).
얻는 것 없이 npm 의존성만 늘리는 선택이기 때문이다.

**결론 — (C).** 세 선택지 중 남는 것은 (B) 커스텀 루프를 라이브러리가 제공하는 것과, (C) 아무것도
하지 않고 임베더에게 맡기는 것 둘뿐이다. (B) 는 §15.6 의 `_PmpMiniLoop` 로 *메커니즘은* 이미
증명되어 있다 -- selector 도 self-pipe 도 열지 않는 루프는 코루틴을 완주시킨다. 그러나 `asyncio.run()`
이 기대하는 것은 그 최소 루프가 아니라 `Runner`/`BaseEventLoop` 계약 전체(`call_later`,
`shutdown_asyncgens`, `shutdown_default_executor`, 예외 처리 프로토콜, `KeyboardInterrupt` 취소 등)이고,
그것을 CPython 버전에 맞춰 계속 맞는 상태로 유지하는 것은 "라이브러리가 파이썬 런타임을 흉내내는"
바로 그 비용이다. 이번 조사가 확정한 것은 asyncio.run() 을 막는 벽의 **정체**(비동기 소켓 흉내가
동기 부트업과 상충)이지, 그 벽을 대신할 만큼 신뢰할 수 있는 대체 루프의 존재가 아니다. 그러므로
`proxyBootstrapSupportsAsyncio` 는 **여기서는 뒤집지 않는다.** 커스텀 루프를 제공할지는 여전히 별도
결정이고, §15.6 이 이미 증명한 최소 메커니즘 위에서 언제든 다시 집어들 수 있다.
