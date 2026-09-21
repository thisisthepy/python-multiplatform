# 서브 에이전트 공통 규칙 — 작업 시작 전에 반드시 읽는다

저장소 루트의 `CLAUDE.md` 를 **먼저 읽어라.** 거기 규정이 개별 프롬프트보다 우선한다.

## 1. 직접 하라
**Task/Agent 도구로 위임하지 마라.** 네가 직접 읽고 쓰고 돌려라.

## 2. 오래 걸리는 명령은 포그라운드로
gradle 이든 무엇이든 **배경으로 돌리고 기다리지 마라.** 배경으로 돌린 뒤 "알림을 기다린다"며
턴을 끝내면 **작업이 통째로 사라진다.** 실제로 그렇게 잃은 에이전트가 셋 있다.

## 3. 커밋하지 마라
`git commit` / `add` / `checkout` / `restore` / `stash` **전부 금지.** 커밋은 조율 세션이 한다.
`git merge --no-edit develop` 만 예외이고, 그것도 **거부되면 보고하고 멈춰라** —
커밋되지 않은 변경이 있으면 merge 는 조용히 거부한다.

## 4. 배정된 범위 밖을 만지지 마라
여러 에이전트가 동시에 돈다. 프롬프트가 지정한 디렉터리 밖의 파일을 수정하지 마라.
특히 `build.gradle.kts` 의 소스셋 구조와 `commonMain` 은 지시 없이 건드리지 마라.

## 5. 금지 문자열
커밋 메시지, 문서, 코드 주석 어디에도 `Co-Authored-By` 나 `Generated with Claude Code` 를
넣지 마라.

## 6. 빌드 검증
종료 코드를 파이프로 읽지 마라. 파일로 리다이렉트하고 `EXIT=` 를 읽어라.

    ./gradlew <task> --console=plain > /tmp/x.log 2>&1; echo "EXIT=$?"

컴파일 에러는 `e: ` 로 시작하는 줄이다. `w: ` 경고는 대부분 기존부터 있던 것이다.

**Android 관련 태스크에는 `ANDROID_HOME=/Users/ibrew/Library/Android/sdk` 가 필요하다.**

## 7. 테스트를 셀 때
- **세기 전에 `build/test-results/` 를 지워라.** 크래시한 실행은 이전 XML 을 남긴다.
- **결과 XML 만 지우면 Gradle 이 UP-TO-DATE 로 건너뛴다.** `--rerun` 또는 `--rerun-tasks` 를 붙여라.
  이것 때문에 안 돈 스위트를 통과로 읽은 적이 있다.

## 8. 검증에 androidNative 컴파일을 포함하라
`nativeMain` 은 iOS 와 androidNative 가 공유한다. **iOS 만 확인하면 androidNative 가 깨진 채로 지나간다.**

    ./gradlew :python-multiplatform:compileKotlinAndroidNativeArm64

## 9. TDD
테스트를 **먼저** 쓰고 구현하라. 구현 전에 그 테스트가 **실패하는 것을 확인하고 기록하라.**
구현 전 실패와 회귀로 인한 실패를 구분할 수 있게 써라.

측정 테스트도 함께 두어라 — FFI 호출 1건당 비용, 포인터 박싱, 참조 카운팅 왕복, 문자열 마샬링,
컬렉션 변환이 관찰 대상이다. `@HighOverheadNativeCall` 애노테이션이 그 의도를 표시한다.

## 10. 관측하지 않은 것을 보고하지 마라
숫자는 반드시 실제로 세어라. 모르면 "모른다", 못 했으면 "못 했다"고 써라.
**빈칸을 그럴듯한 문장으로 메우지 마라** — 이 저장소에서 그것이 반복된 실패 모드였다.
막혔으면 어디서 왜 막혔는지 그대로 적어라.

## 11. 측정할 때
다른 에이전트가 함께 도는 중이면 **절대 수치를 확정하지 마라.** 부하 걸린 기계에서 같은 커밋이
672~1076 ns 를 오갔다. 측정 전 `uptime` 으로 load 를 확인하고 보고에 적어라.
스위트를 `--tests` 로 좁히지 마라 — 측정 영역 자체가 바뀐다.

## 12. 이미 내려진 결정 — 뒤집지 마라
- **동적 바인딩은 제거된 옵션이다.** GraalVM 닫힌 세계와 Kotlin/Native 리플렉션 부재 때문이다.
- **이름으로 JVM 메서드를 조회하지 않는다.** 워커는 **Kotlin 소스를 생성하고 kotlinc 가 컴파일한다.**
  맹글링은 컴파일러가 한다. (맹글링 접미사는 값 클래스 시그니처만 해싱해서 `padding`·`size`·
  `width`·`height` 가 전부 `-3ABfNKs` 를 공유한다. 이름 조회는 원리적으로 불가능하다.)
- **`androidx.*` 는 원본 Kotlin 을 가리킨다.** `pythonx.*` 가 우리가 파이썬으로 쓴 계층이고
  androidx 를 랩핑한다.
- **`.pyi` 생성은 Gradle 플러그인의 몫이다** (PyREPL 방식).
- `JClass`/`KClass`/`ObjcClass` 는 **그 플랫폼에 실제로 있는 것만** 동작한다. 스텁을 만들어
  억지로 통일하지 않는다.

## 13. 소스셋별 규정
`python-multiplatform/src/<소스셋>/README.md` 에 그 플랫폼의 필수 규정이 측정 근거와 함께 있다.
**해당 소스셋을 건드리기 전에 읽어라.**

| 소스셋 | 핵심 |
|---|---|
| `commonMain` | 객체 모델은 `bindings` 참조 금지, 모든 C API 호출에 GIL, 참조 규약 명시 |
| `desktopMain` | **`invoke` 금지, 무조건 `invokeExact`**. 포인터는 `JAVA_LONG` |
| `androidMain` | `RegisterNatives` 로 바인딩, 경계는 원시 타입만 |
| `artMain` | JNI 는 여기에만 |
| `nativeMain` | iOS 와 공유 — Android 전용 코드 금지 |
| `iosMain` | 프레임워크에 stdlib 없음 → `PYTHONHOME` 필요 |

## 14. 참조 규약을 틀리지 마라
빌린 포인터를 `borrowed = false` 로 감싸면 두 래퍼가 한 포인터를 decref 한다.
**이 저장소에서 그것이 힙을 손상시키고 무관한 테스트에서 터진 적이 있다.**
