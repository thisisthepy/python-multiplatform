# The cost table

Kotlin ↔ Python 경계의 비용을, **여섯 타깃 전부에 대해 한 번에** 뽑아 놓은 표.

`docs/design/upcall-design.md` 와 `docs/design/downcall-design.md` 는 각자의 조사 과정에서 나온 표를 갖고 있고,
그 표들은 **조사가 끝난 시점의 기록**이다. 이 문서는 다르다 — 여기 있는 표는 사람이 옮겨 적은 것이
아니라 `benchmarks/cost_table.py` 가 실행 결과에서 직접 렌더링한 것이고, 아래 "다시 뽑는 법" 한
줄이면 통째로 갱신된다.

이 분리가 필요한 이유는 이 저장소가 이미 두 번 당했기 때문이다.

1. **조건 없는 숫자.** 워밍업 3,000 에서 잰 값들이 워밍업 표기 없이 표에 들어갔고, 나중에
   그 값들이 전부 "호스트 JIT 이 얼마나 데워져 있었는가"의 함수였다는 것이 드러났다
   (`docs/design/downcall-design.md`, "The benchmark was measuring the benchmark").
2. **부하 걸린 기계.** 같은 커밋이 672 ns 와 1076 ns 를 오갔고 bisect 한 회차를 통째로 버렸다.

그래서 이 하네스는 두 가지를 강제한다. **숫자와 조건을 같은 산출물에 함께 기록하고, 바쁜 기계에서는
아예 측정을 거부한다.**

---

## 다시 뽑는 법

```bash
ANDROID_HOME=/Users/ibrew/Library/Android/sdk ./benchmarks/cost-table.sh --runs 3
```

끝이다. 이 한 줄이 여섯 타깃을 **차례로** 돌리고, 결과를 `benchmarks/results/cost-table-<시각>.json`
과 같은 이름의 `.tsv` 로 남기고, 아래 마커 사이를 다시 렌더링한다.

### 전제 조건

| 조건 | 없으면 |
|---|---|
| **기계가 조용할 것** — 1분 load average 가 4.0 미만 | 수집이 **거부된다.** `--allow-busy` 로 넘길 수 있으나, 그 경우 산출물과 표 머리에 오염 표시가 붙고 그 숫자는 인용 대상이 아니다 |
| `ANDROID_HOME` 이 platform-tools 가 있는 SDK 를 가리킬 것 | ART 두 타깃과 `androidNativeArm64` 가 **skipped 로 기록된다** (조용히 빠지지 않는다) |
| 에뮬레이터 `pmp_api26`, `pmp_api36` 이 떠 있을 것 | 해당 타깃이 skipped 로 기록되고, 띄우는 명령이 출력과 JSON 에 함께 남는다 |
| Xcode 라이선스 동의 | `iosSimulatorArm64` 가 실패로 기록된다 |
| 다른 에이전트·빌드가 돌지 않을 것 | load ceiling 에 걸린다 |

에뮬레이터 두 대는 **동시에 쓸 수 없다.** 하네스는 `ANDROID_SERIAL` 로 한 대씩 고정해 직렬로 돌고,
`connectedDebugAndroidTest` 의 출력 디렉터리를 매 실행 전에 지워 다른 기기의 이전 결과가 이번 실행의
것으로 읽히지 않게 한다.

에뮬레이터를 띄우는 명령:

```bash
$ANDROID_HOME/emulator/emulator -avd pmp_api36 -no-snapshot-save -no-boot-anim &
$ANDROID_HOME/emulator/emulator -avd pmp_api26 -no-snapshot-save -no-boot-anim &
```

### 부분 실행

```bash
./benchmarks/cost-table.sh --targets desktop,wasmJs       # 기기 없이 되는 것만
./benchmarks/cost-table.sh --targets artApi26            # 에뮬레이터가 하나만 붙었을 때
./benchmarks/cost-table.sh render                         # 가장 최근 JSON 을 다시 렌더링만
python3 benchmarks/cost_table.py render --check           # 문서가 최신 JSON 과 어긋나면 exit≠0
```

`--targets` 로 좁히는 것은 **타깃**을 좁히는 것이지 스위트를 좁히는 것이 아니다. 하네스는 어떤
경우에도 `--tests` 를 쓰지 않는다 — 스위트를 좁히면 측정 영역 자체가 바뀌고, 이 저장소는 그것으로
한 회차를 버렸다 (양 끝이 1300~1400 ns 로 올라갔다).

### 나눠 뜬 것을 하나의 표로 합치기

에뮬레이터는 한 번에 한 대만 쓸 수 있으므로, 여섯 타깃이 한 번의 실행으로 다 채워지지 않는 경우가
정상이다. 그럴 때는 각각 뜨고 렌더링할 때 합친다.

```bash
./benchmarks/cost-table.sh collect --targets desktop,iosSimulatorArm64,wasmJs --out /tmp/host.json
./benchmarks/cost-table.sh collect --targets artApi36,androidNativeArm64     --out /tmp/a36.json
./benchmarks/cost-table.sh collect --targets artApi26                        --out /tmp/a26.json
python3 benchmarks/cost_table.py render --in /tmp/host.json,/tmp/a36.json,/tmp/a26.json
```

**커밋이 다른 cut 을 합치는 것은 거부된다.** 서로 다른 트리에서 나온 행을 한 표에 놓는 것이
`downcall-design.md` 가 한 절(`Closed: repository drift, not run-to-run noise`)을 통째로 써서
추적한 바로 그 실패다. `--allow-mixed` 로 넘길 수 있으나, 그러면 표 머리에 커밋 불일치가 명시되고
어느 타깃이 어느 cut 에서 왔는지가 함께 렌더링된다.

---

## 여섯 타깃과 그 태스크

| 타깃 | Gradle 태스크 | 결과 위치 | 기기 |
|---|---|---|---|
| `desktop` | `:python-multiplatform:desktopTest` | `build/test-results/desktopTest/*.xml` 의 `<system-out>` | 불필요 |
| `iosSimulatorArm64` | `:python-multiplatform:iosSimulatorArm64Test` | `build/test-results/iosSimulatorArm64Test/*.xml` | simctl 시뮬레이터 |
| `wasmJs` | `:python-multiplatform:wasmJsNodeTest` | `build/test-results/wasmJsNodeTest/*.xml` | 불필요 (Node) |
| `androidNativeArm64` | `:python-multiplatform:androidNativeArm64Test` | `build/test-results/androidNativeArm64Test/*.xml` | **에뮬레이터** (`pmp_api36`) |
| `artApi26` | `:python-multiplatform:connectedDebugAndroidTest` | `build/outputs/androidTest-results/connected/debug/<기기>/logcat-*.txt` | **에뮬레이터** (`pmp_api26`) |
| `artApi36` | `:python-multiplatform:connectedDebugAndroidTest` | 〃 | **에뮬레이터** (`pmp_api36`) |

기본 여섯 외에 `androidNativeArm64Api26` 이 하나 더 선언되어 있다 — `upcall-design.md` 의 표가
androidNative 를 API 레벨별로 나눠 기록하기 때문이고, `--targets` 로 이름을 대면 돈다.

**ART 만 결과를 읽는 경로가 다르다.** AGP 가 쓰는 JUnit XML 에는 `<system-out>` 이 없다 —
251개 testcase 에 0개였다. 대신 AGP 는 테스트별 `logcat-<클래스>-<메서드>.txt` 를 옆에 남기고,
벤치마크의 `println` 은 거기 `I System.out:` 줄로 들어간다. 하네스는 그 줄에서 접두사를 떼고 읽는다.

---

## 무엇이 측정되고, 어디서 오는가

| 파일 | 소스셋 | 재는 것 | 파서가 읽는 형태 |
|---|---|---|---|
| `overhead/Benchmark.kt` | `commonTest` | 하네스 자체 (`run`/`measure`) — 벤치마크가 아니다 | — |
| `overhead/BenchmarkTest.kt` | `commonTest` | 포인터 박싱, refcount 왕복, 문자열 마샬링(8/256/8192자), 정수 마샬링, `PyObject` 래퍼, `PyObject_GetAttrString` | `--- Benchmark Report ---` 표 |
| `native/ffi/UpcallBoundaryCostTest.kt` | `commonTest` | **업콜 1건**과 그것을 재는 데 필요한 다섯 기준선(같은 실행 안에서) | `--- Upcall boundary cost: <플랫폼> ---` 블록 |
| `ffi/upcall/GeneratedProxyCostTest.kt` | `commonTest` | 생성된 프록시 계층이 붙이는 비용 | `--- Generated proxy cost... ---` 블록 (JSON·TSV 에는 들어가나 아래 표에는 쓰지 않는다) |
| `reflection/UpcallOverheadTest.kt` | `commonTest` | 이름 조회 vs 캐시된 핸들, 인자 배열 비용 | `--- Benchmark Report ---` 표 |
| `desktopTest/DesktopOverheadBenchmark.kt` | `desktopTest` | Panama 전환 비용 | desktop 에서만 |
| `wasmJsTest/WasmMarshallingOverheadTest.kt` | `wasmJsTest` | `internedUtf8` vs `scratchUtf8` vs malloc/copy/free | wasmJs 에서만 |
| `androidInstrumentedTest/JniOverheadBenchmark.kt` | `androidInstrumentedTest` | JNI 호출 규약별 전환 비용 | ART 에서만 |
| `androidInstrumentedTest/CompositionBenchmark.kt` | `androidInstrumentedTest` | 호출당 크로싱 vs 조합된 `artMain` 호출 1건 | ART 에서만 |
| `androidInstrumentedTest/UpcallOverheadTest.kt` | `androidInstrumentedTest` | Android 업콜의 attach 비용 | ART 에서만 |

**다운콜은** `BenchmarkTest` 와 `UpcallBoundaryCostTest.measureDowncalls()` 에서,
**업콜은** `UpcallBoundaryCostTest` 와 `reflection/UpcallOverheadTest` 에서 나온다.
아래 표 두 개는 앞의 둘만 쓴다 — 여섯 타깃 전부에서 같은 모양으로 도는 것이 그 둘이기 때문이다.

파서는 라벨을 하드코딩하지 않는다. 위 블록 형식(`--- 제목: 플랫폼 ---`, 조건 줄, 들여쓴 `라벨  값 ns`)
을 따르는 것은 전부 JSON·TSV 로 들어간다. 표에 어떤 라벨을 쓸지는 렌더러가 고른다.

### 워밍업은 타깃의 성질이 아니라 **테스트 클래스**의 성질이다

`Benchmark.printReport` 로 출력하는 클래스가 여럿이고, 각자 워밍업이 다르다 —
`BenchmarkTest` 는 100,000, `reflection/UpcallOverheadTest` 는 `Benchmark.run` 의 기본값 1,000,
`GeneratedProxyCostTest` 는 5,000. 그래서 하네스는 모든 행에 **출력한 테스트 클래스**를 함께
기록하고, 렌더러는 `BenchmarkTest` 행만 그 표에 넣는다. 나머지는 "출력한 테스트" 열을 달아 별도
표로 나간다. 워밍업이 다른 행을 한 워밍업 캡션 아래 한 열에 놓는 것이 이 표를 한 번 무효화한
바로 그 오류이므로, 그 섞임은 구조적으로 막아 두었다.

### 파서가 우회하고 있는 출력 결함 하나

`UpcallBoundaryCostTest.report()` 는 라벨을 54칸으로 패딩하는데 다섯 라벨이 그보다 길다. 그래서
값이 라벨에 붙어 출력된다:

```
  PyObject_CallObject on a Python def (downcall, same shape)137.06 ns
    ...Python3.withPython { } with nothing in it, for scale53.37 ns
```

공백 구분자를 요구하는 파서는 **다운콜 표를 이루는 다섯 행을 통째로 놓친다.** 하네스는 값을 뒤쪽
단위(`ns`)에 앵커해 읽으므로 영향받지 않는다. 다만 사람이 읽는 출력으로서는 결함이고,
`commonTest` 파일을 고치는 일이라 여기서는 손대지 않고 기록만 해 둔다 — 고칠 때는 `padEnd(54)` 를
가장 긴 라벨(58자)보다 크게 올리면 된다.

---

## 얼마나 걸리는가

조용한 기계 기준의 근거 있는 추정. **호스트 두 타깃만 실제로 측정했고**, 나머지는 근거를 밝힌
추정이다.

| 타깃 | 1회 벽시계 | 근거 |
|---|---|---|
| `desktop` | ~50초 | 측정 (load 7.7, Gradle 캐시 warm). 조용하면 더 짧다 |
| `wasmJs` | ~25초 | 측정 (load 8.6, 같은 조건) |
| `iosSimulatorArm64` | 수 분 | 미측정. 시뮬레이터 부팅 + Kotlin/Native 테스트 링크가 지배적이고, 스위트 자체는 `downcall-design.md` 가 19.38 초로 기록 |
| `androidNativeArm64` | 수 분 | 미측정. 링크 + 60 MB 표준 라이브러리 push (`--sync` 라 2회차부터는 비교만) + 기기 실행 |
| `artApi26` / `artApi36` | 각 수 분 | 미측정. APK 조립·설치 + 251개 계측 테스트 (기록상 스위트 자체는 38~40초) |

캐시가 warm 하면 **여섯 타깃 1회가 대략 15~25분**, `--runs 3` 이면 링크·설치가 재사용되므로
선형보다는 덜 늘어 **40분~1시간** 정도로 본다. 콜드 캐시(새 worktree, Kotlin/Native 링크부터)면
여기에 링크 시간이 더 붙는다. **이 추정은 측정 두 개와 문서에 기록된 스위트 실행 시간에서 나온
것이고, 기기 세 타깃은 이 작업에서 한 번도 돌리지 않았다.**

---

## 산출물

| 파일 | 내용 |
|---|---|
| `benchmarks/results/cost-table-<시각>.json` | 실행별 전체 기록 — 조건, 스위트 집계, 파싱된 모든 지표, 건너뛴 타깃과 그 이유 |
| `benchmarks/results/cost-table-<시각>.tsv` | 같은 것의 long-format. `target run status source section label value unit iterations platform commit contaminated` |
| `benchmarks/results/logs/<타깃>-run<N>-<시각>.log` | 그 실행의 Gradle 출력 전체 |
| 이 문서의 마커 사이 | 위 JSON 에서 렌더링된 표 |

표에 남는 측정 조건: **커밋 해시**와 그 제목, 브랜치, **워킹 트리가 더러운지**, 수집 시각, 호스트
CPU·코어 수·OS, **시작/종료 load average 와 ceiling**, 타깃당 실행 횟수, `UpcallBoundaryCostTest`
가 스스로 출력한 **반복 횟수와 워밍업 횟수**, 소스에서 읽은 `BenchmarkTest` 의 `WARMUP` /
`WARMUP_BULK`, **기기·시뮬레이터 식별자**(AVD 이름, 시리얼, API 레벨, ABI / 부팅된 시뮬레이터),
플랫폼이 스스로 보고한 이름, 스위트 통과·실패·스킵 수, 벽시계 시간, 그리고 태스크 종료 코드.

`BenchmarkTest` 의 워밍업만 소스에서 읽는다 — 그 테스트는 자기 워밍업을 출력하지 않기 때문이다.
(`UpcallBoundaryCostTest` 는 출력한다.) 워밍업 없는 숫자가 이 표를 한 번 무효화했으므로 추정하지
않고, 읽지 못하면 그 사실을 기록한다.

---

<!-- COST-TABLE:GENERATED BEGIN -- everything between these markers is written by benchmarks/cost_table.py; edit the script, not this -->

_Generated by `benchmarks/cost_table.py` from `cut-hostless.json, cut-api36.json, cut-api26.json`._

### Conditions

Every number below is a function of these. A cost table without them is a record of whatever else was running.

- **commit** `958c0082b294` — Merge branch 'work/objecttype' into develop
- **branch** `develop`, worktree `/Volumes/macMini/thisisthepy/PythonMultiplatform`
- **captured** 2026-08-14T16:31:30+09:00 — the first of several cuts; each one's own time is in the source table below
- **host** Apple M1, 8 cores, Darwin 25.5.0 arm64
- **load average** [1.97, 2.29, 5.92] at start, [2.47, 3.28, 5.19] at end (ceiling 4.0, guard `ok`) — of the first cut only; per-cut figures below
- **runs per target** 3 — ranges below are min–max over them
- **`UpcallBoundaryCostTest`** warmup and iteration counts are self-reported per target in the table below (the test prints them)
- **`BenchmarkTest`** warmup, read from the source at this commit: `WARMUP` = 100000, `WARMUP_BULK` = 10000 (the ≥4096-char string rows use the second — see that file's own caveat)
- **suite scope** full task, never `--tests`-filtered: narrowing the suite changes what is being measured

This table is merged from several cuts — which target came from which:

| cut | commit | captured | load at start | contaminated | targets |
|---|---|---|---|---|---|
| `benchmarks/results/cut-hostless.json` | `958c0082b294` | 2026-08-14T16:31:30+09:00 | [1.97, 2.29, 5.92] | no | `desktop`, `iosSimulatorArm64`, `wasmJs`, `androidNativeArm64` |
| `benchmarks/results/cut-api36.json` | `958c0082b294` | 2026-08-14T16:43:04+09:00 | [1.42, 2.39, 4.24] | no | `artApi36` |
| `benchmarks/results/cut-api26.json` | `958c0082b294` | 2026-08-14T16:53:51+09:00 | [3.69, 2.75, 3.62] | no | `artApi26` |

| target | task | status | device / simulator | platform, self-reported | tests/fail/skip | wall s | loop counts |
|---|---|---|---|---|---|---|---|
| `desktop` | `:python-multiplatform:desktopTest` | ok | — | MacOS 26.5.1 (aarch64) / JVM 21.0.12 | 396/0/1 | 30.0/12.7/12.5 | iterations per loop: 10000, warmup: 100000 |
| `iosSimulatorArm64` | `:python-multiplatform:iosSimulatorArm64Test` | ok | — | iOS 26.2 (SDK 260200, arm64) / Native | 350/0/0 | 66.1/36.7/36.5 | iterations per loop: 10000, warmup: 100000 |
| `wasmJs` | `:python-multiplatform:wasmJsNodeTest` | ok | — | Web emscripten (wasm32) / Wasm Kotlin/Wasm | 364/0/0 | 21.3/5.9/5.7 | iterations per loop: 10000, warmup: 100000 |
| `androidNativeArm64` | `:python-multiplatform:androidNativeArm64Test` | ok | `pmp_api36` (emulator-5554, API 36, arm64-v8a) | Android 16 (SDK 36, aarch64) / Native | 353/0/0 | 76.4/46.7/46.3 | iterations per loop: 10000, warmup: 100000 |
| `artApi26` | `:python-multiplatform:connectedDebugAndroidTest` | failed | `pmp_api26` (emulator-5556, API 26, arm64-v8a) | Android 8.0.0 (SDK 26, aarch64) / ART VM 0.9 | 381/3/0 | 153.8/149.1/148.3 | iterations per loop: 10000, warmup: 100000 |
| `artApi36` | `:python-multiplatform:connectedDebugAndroidTest` | failed | `pmp_api36` (emulator-5554, API 36, arm64-v8a) | Android 16 (SDK 36, aarch64) / ART VM 0.9 | 381/2/0 | 262.9/148.7/146.7 | iterations per loop: 10000, warmup: 100000 |

### One upcall, per target

`UpcallBoundaryCostTest` (`commonTest`), all baselines measured in the same run as the upcall they price.

| target | upcall | downcall, same shape | trampoline alone | upcall / downcall | upcall / trampoline |
|---|---|---|---|---|---|
| `desktop` | 542.84–576.45 ns | 260.27–265.81 ns | 170.44–247.42 ns | 2.04–2.20x | 5.54–6.46x |
| `iosSimulatorArm64` | 2253.20–2312.97 ns | 1585.46–1655.40 ns | 2794.42–2843.16 ns | 1.39–1.42x | 1.14–1.15x |
| `wasmJs` | 302.41–310.72 ns | 96.53–100.95 ns | 149.50–164.88 ns | 3.07–3.14x | 2.39–2.46x |
| `androidNativeArm64` | 3348.75–3443.23 ns | 2194.26–2365.61 ns | 3957.01–4069.40 ns | 1.45–1.54x | 1.19–1.20x |
| `artApi26` | 1196.52–1272.28 ns | 1253.41–1295.79 ns | 1800.86–1863.97 ns | 0.92–1.01x | 1.05–1.18x |
| `artApi36` | 995.61–1154.10 ns | 804.27–838.60 ns | 1100.87–1167.17 ns | 1.18–1.43x | 1.10–1.29x |

Controls, from the same block — these are what say whether a row moved because the boundary moved or because the whole host did:

| target | empty Python loop | pure-Python callee | GIL-held trampoline |
|---|---|---|---|
| `desktop` | 8.67–8.68 ns | 25.77–26.09 ns | 85.52–97.95 ns |
| `iosSimulatorArm64` | 9.84–10.17 ns | 36.80–38.32 ns | 1948.58–2020.43 ns |
| `wasmJs` | 14.74–15.34 ns | 47.89–49.22 ns | 123.15–129.83 ns |
| `androidNativeArm64` | 17.22–17.31 ns | 48.85–58.45 ns | 2790.19–2857.59 ns |
| `artApi26` | 20.01–20.08 ns | 56.10–65.66 ns | 1073.40–1142.08 ns |
| `artApi36` | 17.22–18.14 ns | 58.40–65.30 ns | 873.48–911.95 ns |

### One downcall, per target

The first three columns are `UpcallBoundaryCostTest`'s own comparison basis read on its own; the fourth is `BenchmarkTest`'s 8-character string row from the same suite execution.

| target | empty `withPython` scope | `Py_IncRef` + `Py_DecRef` | `PyObject_CallObject` | `PyUnicode_FromString`, 8 chars |
|---|---|---|---|---|
| `desktop` | 51.78–52.60 ns | 250.48–257.99 ns | 260.27–265.81 ns | 185.13–191.00 ns |
| `iosSimulatorArm64` | 701.18–723.85 ns | 1488.64–1543.92 ns | 1585.46–1655.40 ns | 3338.51–3490.55 ns |
| `wasmJs` | 24.66–25.15 ns | 45.23–46.98 ns | 96.53–100.95 ns | 101.55–103.22 ns |
| `androidNativeArm64` | 998.73–1051.08 ns | 2058.84–2167.44 ns | 2194.26–2365.61 ns | 4419.55–4706.72 ns |
| `artApi26` | 506.19–540.11 ns | 1137.97–1189.74 ns | 1253.41–1295.79 ns | 1404.00–1485.24 ns |
| `artApi36` | 283.90–286.34 ns | 597.74–608.46 ns | 804.27–838.60 ns | 1013.04–1197.93 ns |

### `BenchmarkTest`, every row

The whole of `overhead/BenchmarkTest`, one column per target. Iteration counts are the test's own; the warmup is the `WARMUP` above for every row except the ≥4096-char string ones, which use `WARMUP_BULK`.

| row | iterations | `desktop` | `iosSimulatorArm64` | `wasmJs` | `androidNativeArm64` | `artApi26` | `artApi36` |
|---|---|---|---|---|---|---|---|
| `PyObject_GetAttrString` | 10,000 | 190.21–197.55 ns | 3359.97–3367.64 ns | 187.70–193.10 ns | 4414.72–4649.53 ns | 1351.16–1484.84 ns | 920.67–1114.50 ns |
| `PyLong_FromLongLong` | 100,000 | 124.86–127.77 ns | 1528.38–1549.84 ns | 57.50–59.17 ns | 2118.88–2286.43 ns | 1154.50–1230.39 ns | 720.19–738.93 ns |
| `PyLong_AsLongLong` | 100,000 | 58.11–59.11 ns | 763.39–771.76 ns | 26.96–27.08 ns | 1044.72–1209.32 ns | 572.33–653.66 ns | 300.14–308.04 ns |
| `Py_IncRef/Py_DecRef` | 1,000,000 | 109.81–112.59 ns | 1525.59–1562.09 ns | 45.81–48.68 ns | 2080.61–2221.67 ns | 1152.69–1270.82 ns | 599.46–614.50 ns |
| `PyUnicode_FromString (8 chars)` | 10,000 | 185.13–191.00 ns | 3338.51–3490.55 ns | 101.55–103.22 ns | 4419.55–4706.72 ns | 1404.00–1485.24 ns | 1013.04–1197.93 ns |
| `PyUnicode_AsUTF8 (8 chars)` | 10,000 | 80.29–115.54 ns | 976.86–1007.93 ns | 57.00–58.53 ns | 1226.12–1306.80 ns | 3967.62–4040.60 ns | 3380.46–3603.98 ns |
| `PyUnicode_FromString (256 chars)` | 10,000 | 252.13–263.88 ns | 30954.59–32067.15 ns | 753.78–759.26 ns | 40303.97–40737.47 ns | 2484.13–2514.75 ns | 4555.52–4683.17 ns |
| `PyUnicode_AsUTF8 (256 chars)` | 10,000 | 238.20–273.97 ns | 4215.35–4312.22 ns | 433.79–438.88 ns | 4931.79–5004.19 ns | 49560.37–50387.81 ns | 43690.80–45067.76 ns |
| `PyUnicode_FromString (8192 chars)` | 10,000 | 1867.14–2152.41 ns | 889250.68–919985.55 ns | 30560.16–30780.56 ns | 1169590.51–1182340.92 ns | 34593.37–36591.68 ns | 116198.67–119098.99 ns |
| `PyUnicode_AsUTF8 (8192 chars)` | 10,000 | 5308.67–5450.99 ns | 107813.46–109747.17 ns | 11283.89–11486.64 ns | 122217.03–123729.16 ns | 1475619.82–1485238.00 ns | 1304492.40–1327069.03 ns |
| `NativePointer.toRawValue()` | 1,000,000 | 2.27–2.31 ns | 50.58–70.80 ns | 14.38–14.98 ns | 68.96–70.72 ns | 3.70–3.80 ns | 5.98–6.27 ns |
| `NativePointer.toAddressValue()` | 1,000,000 | 2.28–2.32 ns | 56.84–57.14 ns | 14.45–14.92 ns | 83.42–84.13 ns | 16.57–18.37 ns | 21.37–21.93 ns |
| `AddressValue.toNativePointer()` | 1,000,000 | 2.27–2.35 ns | 65.10–66.20 ns | 6.34–6.75 ns | 95.53–95.75 ns | 19.98–20.20 ns | 26.57–27.37 ns |
| `Manual IncRef/DecRef` | 100,000 | 109.62–112.07 ns | 1516.04–1538.18 ns | 44.24–44.91 ns | 2063.26–2166.75 ns | 1147.20–1321.38 ns | 593.30–603.22 ns |
| `PyObject wrapper creation` | 100,000 | 199.74–201.47 ns | 1927.48–1954.60 ns | 259.71–278.76 ns | 2626.97–2703.14 ns | 1691.66–1745.09 ns | 1188.08–1225.27 ns |

### Other benchmark rows, by the test that printed them

These also come through `Benchmark.printReport`, and they are **not comparable with the table above or with each other**: each of these classes chooses its own warmup (`Benchmark.run`'s default is 1 000; `GeneratedProxyCostTest` uses 5 000), and some run on one target only. They are listed with their origin so a figure cannot be lifted out of here as if it shared the conditions above.

| printed by | row | iterations | `desktop` | `iosSimulatorArm64` | `wasmJs` | `androidNativeArm64` | `artApi26` | `artApi36` |
|---|---|---|---|---|---|---|---|---|
| `UpcallOverheadTest` | `HandleTable.resolve(handle)` | 1,000,000 | 8.06–9.29 ns | 111.53–114.80 ns | 7.73–8.16 ns | 155.69–157.77 ns | 15.52–18.37 ns | 35.07–35.99 ns |
| `UpcallOverheadTest` | `HandleTable.register + release` | 500,000 | 29.53–32.95 ns | 617.84–636.01 ns | 57.70–60.23 ns | 842.59–849.92 ns | 72.57–74.31 ns | 132.04–133.37 ns |
| `UpcallOverheadTest` | `UpcallTable.resolve(name) [200 entries]` | 1,000,000 | 9.63–9.96 ns | 140.43–140.68 ns | 34.78–36.62 ns | 235.79–242.40 ns | 46.42–46.84 ns | 292.52–295.97 ns |
| `UpcallOverheadTest` | `UpcallTable.invoke(handle, args)` | 1,000,000 | 12.56–15.97 ns | 235.43–236.55 ns | 41.63–44.03 ns | 303.76–310.15 ns | 45.97–46.82 ns | 78.46–80.12 ns |
| `UpcallOverheadTest` | `UpcallTable.invokeByName(name, args)` | 1,000,000 | 14.10–14.64 ns | 298.49–299.27 ns | 49.09–51.37 ns | 430.23–440.54 ns | 79.61–81.72 ns | 374.13–376.68 ns |
| `UpcallOverheadTest` | `arrayOf(1L) allocation + boxing` | 1,000,000 | 6.44–6.64 ns | 67.25–68.87 ns | 8.66–8.98 ns | 76.93–77.80 ns | 9.31–10.23 ns | 18.86–20.10 ns |
| `UpcallOverheadTest` | `invoke with a pre-built args array` | 1,000,000 | 8.64–8.91 ns | 173.09–179.16 ns | 23.07–24.06 ns | 240.92–251.28 ns | 34.76–37.70 ns | 60.44–61.46 ns |
| `UpcallOverheadTest` | `direct Kotlin call (floor)` | 1,000,000 | 2.26–2.95 ns | 15.02–15.39 ns | 4.45–4.63 ns | 15.15–15.49 ns | 4.14–4.21 ns | 13.79–14.69 ns |
| `WasmFinalizationTest` | `cleaner: registerCleaner + close (the whole hook)` | 50,000 | — | — | 77.70–90.21 ns | — | — | — |
| `WasmFinalizationTest` | `cleaner: toJsReference() alone (the externref crossing)` | 50,000 | — | — | 32.70–35.38 ns | — | — | — |
| `WasmMarshallingOverheadTest` | `marshal: malloc+encodeToByteArray+copy+free (old)` | 200,000 | — | — | 76.95–80.44 ns | — | — | — |
| `WasmMarshallingOverheadTest` | `marshal: malloc+free only` | 200,000 | — | — | 13.41–14.15 ns | — | — | — |
| `WasmMarshallingOverheadTest` | `marshal: encodeToByteArray only` | 200,000 | — | — | 50.10–52.39 ns | — | — | — |
| `WasmMarshallingOverheadTest` | `marshal: Wasm.scratchUtf8` | 200,000 | — | — | 28.44–29.48 ns | — | — | — |
| `WasmMarshallingOverheadTest` | `marshal: Wasm.internedUtf8 (hit)` | 200,000 | — | — | 20.71–21.71 ns | — | — | — |
| `WasmMarshallingOverheadTest` | `marshal 54-char name: Wasm.scratchUtf8` | 200,000 | — | — | 145.61–152.15 ns | — | — | — |
| `WasmMarshallingOverheadTest` | `marshal 54-char name: Wasm.internedUtf8 (hit)` | 200,000 | — | — | 24.73–25.88 ns | — | — | — |
| `WasmMarshallingOverheadTest` | `getattr: C string allocated per call (old)` | 50,000 | — | — | 241.35–259.20 ns | — | — | — |
| `WasmMarshallingOverheadTest` | `getattr: interned C string (new)` | 50,000 | — | — | 186.85–200.06 ns | — | — | — |
| `WasmMarshallingOverheadTest` | `getattr via actual: C string per call (old)` | 50,000 | — | — | 222.20–239.95 ns | — | — | — |
| `WasmMarshallingOverheadTest` | `getattr via actual: interned (new)` | 50,000 | — | — | 191.19–205.26 ns | — | — | — |
| `WasmMarshallingOverheadTest` | `getattr: scratch C string` | 50,000 | — | — | 192.62–204.75 ns | — | — | — |
| `WasmMarshallingOverheadTest` | `marshal 4000 B: malloc+encode+copy+free (old)` | 20,000 | — | — | 14285.62–14866.91 ns | — | — | — |
| `WasmMarshallingOverheadTest` | `marshal 4000 B: Wasm.scratchUtf8` | 20,000 | — | — | 9420.60–9830.11 ns | — | — | — |

<!-- COST-TABLE:GENERATED END -->

## The React Native comparison's Android column is unquotable, and not for the reason assumed

The cross-runtime comparison this table is read against
(`/Volumes/macMini/rn-benchmark/COMPARISON.md`) has an Android column that cannot
be quoted. Two solo runs on a quiet machine, one at the documented warmup and one
at four times it, both ended with the benchmark refusing its own numbers:

    boundary (Hermes+JSI+JNI+ART): NEVER SETTLED. Warmup 100000 is not justified by this run.
    boundary (Hermes+JSI+JNI+ART): NEVER SETTLED. Warmup 400000 is not justified by this run.

Two explanations are ruled out by that pair rather than argued away. Machine load
is not it: the runs were alone, at load averages of 3.10 and 2.97, where the
earlier failing run had ten to twelve. Warmup length is not it either: quadrupling
it changed nothing, and the constant it started from has its own provenance -- the
reference project's ART sweep put the plateau at ninety to a hundred thousand
calls.

What is left is visible in the sweep itself: adjacent repetitions differ by nearly
a factor of two (851.88 ns at the thirty-fifth, 1625.86 ns at the thirty-seventh).
That is the shape of scheduling jitter under an emulator, not of a runtime still
warming up -- and if it is, the convergence criterion the benchmark applies (forty
repetitions inside five per cent) is one an emulator may never satisfy no matter
how long the warmup.

So the next step is a physical device, or a deliberate decision about what
convergence means on an emulator. Not a longer warmup. The numbers stay out of
this table until one of those happens, and the benchmark enforces that itself by
exiting non-zero and marking the whole report unquotable.
