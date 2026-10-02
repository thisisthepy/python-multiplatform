# 다운콜 설계 초안의 Android 진단 (2026-08 초)

> **Superseded** by [`../design/downcall-design.md`](../design/downcall-design.md) on 2026-10-03; kept for history.
> 아래 "확인된 버그" 는 **수정되었다** — Android 는 `RegisterNatives` 로 전부 바인딩한다
> (`python-multiplatform/src/androidMain/README.md`, ROADMAP §2). 호출 규약 표의 전환 비용(115/35/25 ns)은
> 외부 벤치마크에서 가져온 값이며, 이 프로젝트가 실측한 값(API 26-36)과 반대 경향이다 — 실측은 본 문서의
> "Measured" 절과 `androidMain/README.md` 에 있다.

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

