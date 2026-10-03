# 노출 정책, 무엇이 Python 에서 보이는가

Kotlin 의 무엇을 함수 테이블에 등록해 Python 에 노출할지에 대한 규칙. 테이블 자체의 구조는
[`upcall.md`](upcall.md).

이 문서의 표는 **KSP 프로세서가 소스에서 읽는 선언**(`python-multiplatform-ksp` 의 `BindingPolicy.kt`)에
대한 규칙이다. 미리 컴파일된 jar 를 읽는 아티팩트 워커(`python-multiplatform-gradle-plugin`)는
같은 블랙리스트 정신을 따르되, 소스 없이 바이트코드/Kotlin 메타데이터에서 읽으므로 일부 행이
다르다, 아래 해당 행과 §"결정 이력" 에 표시했다. Kotlin 이름은 어느 쪽이든 그대로 노출되며
(AGENTS.md §12.1) 이 정책에는 이름을 바꾸는 규칙이 없다.

## 원칙: 블랙리스트

**모든 `public` 선언이 자동으로 등록된다. 빼고 싶은 것에 애노테이션을 붙인다.**

화이트리스트(`@PythonAPI` 를 붙인 것만 노출)는 **기각됐다.** 노출하려는 모든 멤버에 애노테이션을
다는 것은 지나치게 번거롭고, 라이브러리 작성자가 붙이기를 잊으면 조용히 안 보이게 된다.

```kotlin
class MyClass {
    fun doSomething(arg: String): String = "..."   // 자동 노출

    @PythonInternal
    fun internalHelper() { }                       // 제외

    private fun privateMethod() { }                // 애초에 대상 아님
}

@PythonInternal
class InternalUtility { }                          // 클래스 통째로 제외
```

애노테이션 이름은 **`@PythonInternal`** 로 한다. Kotlin 의 `internal` 과 의미가 자연스럽게 이어진다.
(`@ExcludeFromPython` 도 후보였으나 길다.)

## 등록 대상

| 대상 | 등록 여부 | 근거 |
|---|---|---|
| `public` 함수 | ✅ | 기본 원칙 |
| `public` 클래스 | ✅ | |
| **생성자** | ✅ | Python 에서 인스턴스를 만들어야 한다 |
| **프로퍼티** | ✅ getter. setter 는 `var` 만 | |
| **companion object 멤버** | ✅ 클래스의 정적 메서드처럼 노출 | companion 객체 자체를 따로 노출할 필요는 없다 |
| **`object` 선언** | ✅ companion 과 같은 모양 | 인스턴스가 하나뿐이므로 수신자가 없다. 생성자는 없다 |
| **`interface`** | ✅ 멤버만 | 인스턴스는 없지만, 핸들로 건너온 Kotlin 객체를 인터페이스를 통해 호출할 수 있다. 생성자 없음 |
| **`enum class`** | ✅ 엔트리마다 정적 접근자 + `name`/`ordinal`/`valueOf` | 엔트리 이름은 `ReflectedClass.enumEntryNames` 로 함께 나간다, Python 쪽 `enum.Enum` 미러의 재료 |
| **중첩 선언** | ✅ `Outer.Inner` 이름으로 | 노출되지 않는 바깥 선언에서 탐색이 멈춘다 |
| **`annotation class`** | ❌ | 애노테이션을 읽으려면 런타임 리플렉션이 필요하고, Python 이 만든 인스턴스는 붙일 곳이 없다 |
| **enum 의 `values()` / `entries`** | ❌ | 컬렉션을 돌려주는데 경계에 컬렉션 마샬링이 없다 |
| **제네릭 선언** (`class Box<T>`, `fun <T> f`) | ❌ | 생성된 캐스트가 이름 붙일 타입이 없다: `args[0] as T` 는 unresolved reference |
| **abstract / sealed 클래스의 생성자** | ❌ | "Cannot create an instance of an abstract class", 생성된 파일이 컴파일되지 않는다 |
| `private` / `protected` | ❌ | |
| **`internal`** | ❌ | JVM 바이트코드에서는 name-mangling 된 public 이지만 의미론적으로는 비공개다. KSP 의 `Modifier.INTERNAL` 로 걸러낸다 |
| **확장 함수 (KSP, 소스)** | ❌ | 생성되는 엔트리는 `Array<Any?>` 위의 람다이고 수신자를 둘 자리가 없다 (`BindingPolicy.isExposedFunctionShape` 이 `extensionReceiver != null` 을 거른다). 아티팩트 워커는 다르다, 아래 |
| **확장 함수 (아티팩트 워커, 컴파일된 jar)** | ✅ 수신자 프록시의 메서드로 | `Modifier.padding(16).size(24)` 처럼 체인이 이어진다 (SPEC U-7, `docs/design/kotlin-extensions-in-python.md`) |
| **소스 안의 `@Composable`** | ❌ (KSP) | 생성된 호출이 `@Composable` 컨텍스트 밖이라 컴파일되지 않는다. 컴파일된 jar 의 composable 은 워커가 바인딩한다 (SPEC B-6, `docs/design/ecosystem.md` §4) |

## 라이브러리 자신은 통째로 제외한다

`python.native.ffi`, `python.multiplatform` 등 **이 라이브러리 자체 패키지는 KSP 설정의 exclude
목록으로 통째 제외**한다.

`PyObject`, `PyType`, `Python3`, 330개 FFI 바인딩이 전부 테이블에 올라가면 Python 에서 다시 부를
이유가 없는 것들로 테이블만 부푼다. 내부 클래스마다 `@PythonInternal` 을 붙이는 것은 비현실적이다.

## Python 으로 매핑할 수 없는 것들

| 대상 | 처리 | 근거 |
|---|---|---|
| **`suspend` 함수** | **노출한다**, 비동기 호출 규약으로 | 생성 본문이 `PendingCall.start { }` 로 코루틴을 시작하고, 경계는 값(중단 없이 끝남) 또는 `asyncio.Future`(중단됨)를 돌려준다. Python 에서 `await` 한다 (SPEC U-5, [`upcall.md`](upcall.md)). 처음에는 "자동 제외"였다, 아래 정정 참조. `suspend` **함수 타입** 값(`suspend (Long) -> Long`)은 불투명 `OBJECT` 핸들로 나간다 |
| **`inline` + `reified`** | 자동 제외 | 컴파일 후 reified 타입 정보가 사라진다. **실제 규칙은 이보다 넓다**, 타입 파라미터가 있는 선언은 `reified` 여부와 무관하게 전부 제외한다 |
| **값 클래스(value class)** | KSP: 별도 규칙 없음(`BindingPolicy.kt` 에 값 클래스 분기가 없다). 아티팩트 워커: 왕복을 지원하고, 허용 목록의 것(예: `Dp`)만 원시 값으로 쓸 수 있다 | JVM 에서 언박싱된 형태로 노출되어 케이스가 갈린다 (SPEC B-4, `ComposableValueClassSlotTest.kt`) |

`suspend` 와 `reified` 는 KSP 에서 선언 수준으로 판별되므로 걸러내는 비용이 낮다.

## 결정 이력

블랙리스트 원칙은 설계 논의에서 직접 확정됐다. 세부 규칙(가시성, 확장 함수, 매핑 불가 타입, 애노테이션
이름)은 그 논의에서 다루지 않았고, 이후 판단으로 정한 것이다, 구현하면서 뒤집힐 수 있다.

특히 다음 둘은 재고 여지가 있다:

- **확장 함수 제외 (KSP)**: 소스 경로에서는 제외 상태 그대로다. 다만 이것이 풀린 것은 아티팩트
  워커 쪽이다: 확장 함수는 수신자 프록시의 메서드로 노출된다(SPEC U-7). KSP 가 생성한 프록시에서의
  동작은 아직 테스트로 단언되지 않았다.
- **값 클래스**: 초안의 "등록하고 실패하게 둔다"는 구현되지 않았다. 워커가 값 클래스를 다루고
  (SPEC B-4), KSP 는 따로 걸러내지 않는다.

## 구현 시점의 정정 (2026-08)

위 표 중 두 줄은 구현하면서 넓어졌고, 그 근거는 **생성된 파일이 컴파일되지 않은 것**이다,
프로세서가 스스로 알아챌 수 있는 종류의 오류가 아니었다.

- **제네릭은 `reified` 뿐 아니라 전부 제외한다.** 엔트리는 `Array<Any?>` 위의 람다이므로 모든
  파라미터가 소스에 적을 수 있는 타입으로 캐스트되어야 한다. 타입 파라미터에는 그런 표기가 없다.
  `Box<*>` 로 지우는 방안은, 수신자가 `T` 를 받는 멤버를 만족시키지 못해 기각했다.
- **파라미터 타입의 타입 인자는 그대로 렌더링한다.** `args[0] as kotlin.collections.List` 는
  "One type argument expected" 이다. 생성 파일 머리에 `@file:Suppress("UNCHECKED_CAST")` 가
  붙는 이유이기도 하다.
- **컴파일러 생성 멤버는 `copy` 와 `componentN` 이 실제로 KSP 에 보인다.** `equals`/`hashCode`/
  `toString` 은 보이지 않았다. 관찰된 사실이며, 둘 다 제외한다.
- **`suspend` 는 제외 대상에서 빠졌다.** 초안은 "숨은 `Continuation` 인자 때문에 호출이 불가능하다"며
  자동 제외했다. 비동기 업콜(`PendingCall`)이 생긴 뒤 이 수식어는 제외가 아니라 **본문 형태를
  고르는 신호**가 되었다 (`BindingPolicy.isSuspending`, `GeneratedSuspendTest`).
- **추상·봉인 클래스는 클래스로는 노출하되 생성자만 뺀다** (`BindingPolicy.isConstructible`; `inner`
  클래스도 같다, 바깥 인스턴스를 둘 자리가 없다). 표의 "abstract / sealed 클래스의 생성자" 행의 구현.
- **`internal set` 은 노출하지 않는다.** `private set` 은 생성 코드가 컴파일되지 않아서, `internal set`
  은 컴파일은 되지만 노출 대상이 모듈 공개 API 이기 때문이다 (`BindingPolicy.isExposedSetter`).
