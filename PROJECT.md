# python-multiplatform — 프로젝트 요점

Kotlin Multiplatform 에 CPython 을 임베딩해 **Kotlin 과 Python 이 서로의 라이브러리를 양방향으로**
쓰게 한다. 모든 지원 플랫폼에서 같은 방식으로 동작하고, 경계를 넘는 비용을 측정하며 줄인다.

- 의도(경계): [`docs/INTENT.md`](docs/INTENT.md) · 스펙(동작 계약과 상태): [`docs/SPEC.md`](docs/SPEC.md)
- 에이전트 규정: [`AGENTS.md`](AGENTS.md) · 진척 기록: [`docs/roadmap/ROADMAP.md`](docs/roadmap/ROADMAP.md)

---

## 1. 두 방향

| 방향 | 이름 | 메커니즘 | 상세 |
|---|---|---|---|
| Kotlin → Python | **다운콜** | CPython Stable ABI 함수 호출 (`expect` 약 330개) | [`docs/design/downcall-design.md`](docs/design/downcall-design.md) |
| Python → Kotlin | **업콜** | 빌드 타임 생성 함수 테이블 + 플랫폼별 진입점 1개 | [`docs/design/upcall-design.md`](docs/design/upcall-design.md) |

```
            ┌──────────────────────────────────────────┐
   Kotlin   │  PyObject / PyType / PyList / ...         │
            │            ↓ 다운콜                        │
            │  expect fun PyList_Size(...)  (약 330개)   │
            └──────────────────────────────────────────┘
                          ↕
            ┌──────────────────────────────────────────┐
   Python   │  프록시 객체 → 업콜 진입점 1개 → 함수 테이블  │
            │            ↑ 업콜                          │
            └──────────────────────────────────────────┘
```

진입점 뒤는 모든 플랫폼이 **같은 함수 테이블**을 쓴다. 플랫폼별 Python → Kotlin 바인딩 코드는 없다.

## 2. 플랫폼별 구현 수단

| 플랫폼 | Kotlin 실행 환경 | 다운콜 | 업콜 진입 | 상태 |
|---|---|---|---|---|
| Desktop (macOS) | JVM | Panama FFM (`invokeExact`) | FFM upcall stub | 전체 스위트 · GraalVM 네이티브 이미지 업콜 검증 |
| Desktop (Linux/Windows) | JVM | 동상 | 동상 | 배선만 — 실행 기록 없음 |
| Android | ART | JNI `RegisterNatives` | JNI | 기기 테스트 |
| iOS | Kotlin/Native | cinterop | `@CName` | 시뮬레이터에서 공통 스위트 |
| androidNative | Kotlin/Native | cinterop | `@CName` | 기기에서 공통 스위트 |
| wasmJs | Kotlin/Wasm | Emscripten CPython (JS 경유) | `@WasmExport` | 실험적 |

## 3. 저장소 구조

| 경로 | 내용 |
|---|---|
| `python-multiplatform/` | 라이브러리 — FFI 계층(`python/native/ffi/`), 객체 모델(`python/multiplatform/ffi/`), 업콜 런타임 |
| `python-multiplatform-ksp/` | 업콜 테이블을 생성하는 KSP 프로세서 |
| `python-multiplatform-gradle-plugin/` | `stagePythonHome`, 아티팩트 워커(빌드된 jar/klib 바인딩), `.pyi` 스텁 생성 |
| `ksp-fixtures/` | 생성기 산출물을 실제로 쓰는 소비자 모듈 (`app`, `compose`, `artifact`, …) |
| `sample/` | Compose Multiplatform 데모 앱 (데스크톱·Android·iOS·wasmJs·GraalVM 네이티브 이미지) |
| `python_for_kotlin_binding.mermaid` | 사용자가 그린 객체 모델 스케치 (확정 스펙 아님) |
| `docs/` | `INTENT.md`, `SPEC.md`, `design/`, `platforms/`, `investigations/`, `roadmap/`, `guide/`, `locale/` |

소스셋은 `commonMain` 아래 `jvmMain`(→ `androidMain`, `desktopMain`)과 `nativeMain`(→ `iosMain`,
`artMain` → androidNative) 두 갈래이며 `wasmJsMain` 이 따로 붙는다. 계층은
`python-multiplatform/build.gradle.kts` 에서 수동으로 구성한다. 소스셋마다 `README.md` 에 그 플랫폼의
필수 규정이 있다.

## 4. 빌드와 테스트

```bash
./gradlew :python-multiplatform:compileKotlinAndroidNativeArm64 --console=plain > .tmp/build.log 2>&1; echo "EXIT=$?"
./gradlew :python-multiplatform:desktopTest --rerun --console=plain > .tmp/test.log 2>&1; echo "EXIT=$?"
./gradlew :sample:run        # 데스크톱 데모
```

- 컴파일 확인은 세 타깃: `compileKotlinAndroidNativeArm64`(기본 루프), `compileKotlinIosSimulatorArm64`,
  `compileKotlinDesktop`. **androidNative 를 빼먹지 않는다** — `nativeMain` 을 iOS 와 공유한다.
- 생성기 소비자 검증은 **모듈마다 따로**: `:ksp-fixtures:app:desktopTest`,
  `:ksp-fixtures:compose:desktopTest`, `:ksp-fixtures:artifact:desktopTest`.
- 결과는 `build/test-results/<target>/*.xml` 을 지우고 `--rerun` 으로 다시 돌려 센다.
- 종료 코드는 파이프로 읽지 않는다. 상세 규정은 `AGENTS.md` §15.
- CPython 은 저장소에 벤더링하지 않는다. Gradle 이 플랫폼별로 내려받아 검증·추출한다
  (`gradle.properties` 의 `pythonVersion`, 현재 3.14.7). free-threaded 빌드는
  `-PpythonFreeThreaded=true`.

## 5. 현재 상태

`docs/SPEC.md` 가 항목별 상태와 근거 테스트를 가진다. 요약:

- **구현**: 인터프리터 수명주기, 다운콜 표면, 객체 모델, GC 연동 참조 해제와 경계 순환 수거(데스크톱),
  KSP 업콜 테이블과 프록시(클래스·프로퍼티·companion·`suspend`·취소), jar 아티팩트 워커, Python 에서
  Compose 렌더(데스크톱), Android 부트스트랩, GraalVM 네이티브 이미지 업콜(데스크톱, 수동 검증).
- **부분**: iOS·androidNative·wasm 의 업콜과 수명 검증, klib 워커(스캐너만), `.pyi` 스텁, GIL 해제,
  Linux/Windows, free-threaded 는 desktop 한정·옵트인(3.14t, `-PpythonFreeThreaded=true`, 236 테스트 0 실패 — ROADMAP §9).
- **계획**: Android/iOS 의 free-threaded(프리빌트 없음), Android/iOS/wasm 의 Compose, 네이티브 이미지 검증 자동화.

## 6. 큰 결정들

| 결정 | 근거 |
|---|---|
| Python→Kotlin 은 **런타임 리플렉션 아님** — 빌드 타임 테이블 | Kotlin/Native 에 리플렉션이 사실상 없고, GraalVM 네이티브 이미지는 closed-world 다 |
| **JVM 메서드를 이름으로 찾지 않는다** — Kotlin 소스를 생성해 `kotlinc` 가 컴파일 | 값 클래스 맹글링 접미사가 겹쳐서 이름 조회는 원리적으로 불가능하다 |
| 바인더는 **Kotlin 네임스페이스를 다른 이름으로 내보내지 않는다** | 사용자 규정. `androidx.*` 는 원본 Kotlin, `pythonx.*` 는 pythonx-compose 의 실제 패키지 |
| 노출은 **블랙리스트** (`@PythonInternal`) | 화이트리스트는 붙이기를 잊으면 조용히 사라진다 |
| 병렬성은 **free-threading**, 멀티 인터프리터 아님 | 인터프리터별 GIL 은 C 확장이 `Py_mod_multiple_interpreters` 를 선언해야 import 된다 |
| free-threading 은 **desktop 한정 옵트인** (3.14t, 기본 `pythonFreeThreaded=false`) | 3.14t 가 desktop 에서 `-PpythonFreeThreaded=true` 로 동작한다(236 테스트 0 실패, ROADMAP §9). free-threaded 프리빌트는 desktop 에만 있고 Android/iOS 에는 없다. `Py_LIMITED_API` 를 정의하지 않으므로 `abi3t` 는 막는 요인이 아니다 |
| Desktop 은 **`invokeExact` 만** | `invoke` 는 호출마다 박싱한다 |
| PanamaPort **미사용** | 라이선스(GPLv2+CE)와 ART 내부 구조 의존 |
| LLVM JIT **불필요** | 시그니처에 구조체 값 전달·가변인자가 0개다 |

## 7. 열린 질문

`docs/SPEC.md` 끝의 "Outside intent — needs a decision" 이 원본이다. 요지:

1. `.pyi` 스텁 생성기가 매니페스트 없이도 `androidx.*` 를 `pythonx.*` 로 바꿔 쓴다 — 네임스페이스
   개명 금지 규정과 충돌하는가.
2. 라이브러리가 `sys.modules` 에 합성 `pythonx` 모듈(`__path__ = []`)을 넣는다 — 실제 `pythonx`
   패키지를 가리는가.
3. 멤버 이름의 snake_case 변환은 의도된 것인가.
4. 범용 `pythonx` 어댑터가 이 저장소에 있어야 하는가, pythonx-compose 에 있어야 하는가.

## 8. 문서 지도

| 폴더 | 내용 |
|---|---|
| [`docs/design/`](docs/design/) | 구조, 다운콜·업콜·업콜 테이블·비동기 업콜, 노출 정책, 마샬링, 객체 수명, 스레딩, `.pyi` 생성, pythonx 어댑터, Kotlin 확장 함수, 생태계, TypedPython 제안 |
| [`docs/platforms/`](docs/platforms/) | Android FFM, wasm, CPython 취득, GraalVM 네이티브 이미지 검증 |
| [`docs/investigations/`](docs/investigations/) | 비용 표, GC 스케줄링, GIL 파킹, JNI 호출 규약 감사, Android 미등록 표면 |
| [`docs/roadmap/`](docs/roadmap/) | 진척 기록 (사양이 아님) |
| [`docs/guide/`](docs/guide/) | GitHub Pages 가이드 (영어·한국어) |
