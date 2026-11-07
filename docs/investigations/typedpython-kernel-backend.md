# TypedPython 커널 백엔드 조사 (2026-10-04)

표기: **[V]** = verified (출처 URL 병기, 이번 조사에서 직접 fetch/search 결과로 확인). **[I]** = unverified / my inference.
출처 중 WebSearch 요약은 검색 엔진 요약문이므로, 숫자나 버전은 채택 전에 원문 재확인이 필요하다.

결론의 반영: `docs/design/typedpython.md` §5 (2026-10-04 결정). IR 선택은 후속 조사 [`typedpython-kernel-ir.md`](typedpython-kernel-ir.md) 가 다듬었다.

## 0. 조사 당시의 계획 (읽은 문서, 이후 §5 가 바뀌었다)

- typedpython.md §2.1: 유효한 파이썬만. 커널도 수정 없이 CPython 에서 실행돼 참조 구현이 된다.
- §5: iOS 는 런타임 코드 생성 금지이므로 AOT 가 하드 제약. 경로는 `@kernel` 서브셋 -> 자체 IR -> SPIR-V -> (Vulkan | SPIRV-Cross->MSL | naga->WGSL), PTX 별도. 착지점은 torchnative 의 `torch.library` 커스텀 op. SIMT 먼저, 타일은 나중.
- torchnative DESIGN.md: Triton 기각(JIT), 후보는 손으로 쓴 Metal/NEON, CubeCL("성숙도 확인 필요"), AOT Triton. 안드로이드 GPU 는 "Vulkan/wgpu 백엔드 도입 또는 작성" 으로 비어 있다.

## 1. TileLang (tile-ai/tilelang)

- 백엔드 [V github.com/tile-ai/tilelang]: CUDA(주), ROCm/HIP, Ascend, Apple Metal 은 supported. LLVM CPU, CuTe DSL, WebGPU 는 README 상 experimental. v0.1.13 (2026-08-03 출시라는 검색 요약), 문서 사이트는 0.1.14.
- 불일치 [V]: 공식 docs(tilelang.com/get_started/targets.html)는 Metal, LLVM, WebGPU, C 를 모두 "Fully supported" 로 적지만 README 는 WebGPU 를 experimental 로 적는다. 문서 쪽이 낙관적이라고 보는 게 안전하다 [I].
- Vulkan/SPIR-V: 타깃 문서에 항목 없음. 로드맵 이슈 #56 (2025-01-26 개설) 에 "planned" 로만 있고 진행 표시 없음 [V github.com/tile-ai/tilelang/issues/56]. 즉 Android Vulkan 경로는 **없다** (Mali/Adreno 불가) [V 부재 확인, 완료 여부는 이슈 본문 한계로 100% 는 아님].
- 라이선스: MIT [V raw.githubusercontent.com/tile-ai/tilelang/main/LICENSE]. 2024-12~2025-03 기간 Microsoft 협업 부가 조항 언급이 있다. TVM 은 Apache-2.0 [I, 기억 기반].
- AOT: README 가 "AOT export" 를 기능으로 언급 [V]. 단 무엇이 export 되는지(소스 vs 바이너리, iOS 에 싣는 형태)는 확인 못함. 타깃 문서에 `C` 타깃(소스만 출력)이 있어 소스 생성은 가능 [V].
- 순수 파이썬 CPU 실행: LLVM CPU 타깃이 있으나 "빌드 시 USE_LLVM=ON, LLVM 15+" 필요, 검색 요약상 experimental [V 검색요약]. 파이썬 인터프리터가 커널 본문을 그대로 돌리는 참조 실행(우리 §2.1 요건)은 TileLang 의 `T.prim_func` 가 TVM script 빌더라서 **그대로는 불가** [I: 커널이 파이썬 AST 를 직접 실행하지 않고 DSL 빌더로 구성된다는 구조적 추론, 실행해 보지 않음].
- TVM 의 단점(빌드 복잡도, 바이너리 크기, 컴파일 시간, 포크 변경, free-threading 지원): **이번 조사에서 확인하지 못함**. README 가 TVM 을 핵심 의존성으로 명시한다는 것만 [V]. 나머지는 [I] 이며 측정이 필요하다 (§7).
- 우리 요건과 맞는 점: 타일 추상화, 텐서 코어/simdgroup 접근이 1급. 어긋나는 점: 새 DSL 문법(`T.` API), "유효한 파이썬만" 은 만족하나 CPython 에서 의미 있게 실행되지는 않음, iOS/Android Vulkan 부재.

## 2. wgpu + naga

### 2.1 현재 상태
- wgpu 문서 v30.0.1 [V docs.rs/wgpu/latest/wgpu/struct.Features.html]:
  - SUBGROUP: Vulkan, DX12, Metal 에서 지원, "WebGPU 표준으로 곧 이동 예상".
  - SHADER_F16: Vulkan, Metal, DX12, WebGPU 모두.
  - EXPERIMENTAL_COOPERATIVE_MATRIX: 문서상 8x8 f32 로 제한, **native 전용** (Metal, Vulkan).
  - PASSTHROUGH_SHADERS: Vulkan, DX12, Metal, WebGPU 에서 셰이더를 파싱 없이 직접 제출 가능. 즉 우리가 SPIR-V/MSL 을 직접 만들어 wgpu 런타임에만 얹는 길이 열려 있다.
  - 해당 문서에 PUSH_CONSTANTS, int8 dot 항목은 보이지 않음 (이 문서 요약 기준. 부재의 증거가 아님) [V 한계 있음].
- 협력 행렬 PR #8251: 2025-12-22 merge, Vulkan(KHR_cooperative_matrix)과 Metal(simdgroup) 대상, 후속 PR 로 정수(i32/u32/i8/u8)와 비정방 타일 [V github.com/gfx-rs/wgpu/pull/8251, /pull/9490, /pull/10414]. 문서의 "8x8 f32 만" 과 후속 PR 이 어긋나므로 어느 릴리스에 어디까지 들어갔는지는 미확정.
- WebGPU 표준: subgroups 는 Chrome 134 에서 출시 [V developer.chrome.com/blog/new-in-webgpu-134], Chrome 144 에서 subgroup_id/num_subgroups 추가 [V 검색요약]. subgroup matrix 제안은 2026 에도 활발히 수정 중(2026-09 D3D12 반영 PR) 이므로 **표준 미확정, 브라우저 출시 전** [V github.com/gpuweb/gpuweb/issues/4195, PR #10964; 출시 여부는 확인 못함].
- naga: subgroupElect 외 subgroup 연산 지원 [V github.com/gfx-rs/wgpu/issues/7396, 이슈 시점 기준].

### 2.2 성능 증거
- Burn 블로그 요약: Vulkan(SPIR-V) 컴파일러가 WGSL 보다 matmul 에서 크게 낫다. 이유는 SPIR-V 쪽에서 텐서 코어와 f16 을 쓸 수 있기 때문 [V 검색요약, burn.dev/blog/sota-multiplatform-matmul]. 같은 맥락에서 "CUDA/Vulkan 이 WebGPU 보다 prefill 최대 10x, decode 2.5x 빠르고 Metal 이 WebGPU 보다 prefill 2x 이상" 이라는 문장이 나왔으나, 어느 모델/논문의 수치인지 출처 귀속이 불분명하다 (Llamas on the Web arXiv 2605.20706 로 추정되나 PDF 본문에서 수치를 직접 확인하지 못함) [V 검색요약, 귀속 미확인].
- Meganeura (arXiv 2608.01563): wgpu 를 거치지 않고 Vulkan/Metal 로 직접 컴파일하며, wgpu 층의 오버헤드를 이유로 든다고 요약됨 [V 요약, 수치 미확인].
- 결론 [I]: ML 커널에서 "wgpu 의 WGSL 경로" 는 협력 행렬/int8/f16 접근에서 native 에 뒤진다. wgpu 를 **런타임** 으로만 쓰고 셰이더는 PASSTHROUGH 로 넣는 것이 중간 길이다.

### 2.3 모바일, torchnative 공존
- Android Vulkan, iOS Metal 은 wgpu 가 지원하는 백엔드 [I: 일반 지식, 이번에 문서로 재확인하지 않음].
- torchnative 의 `mps`/`vulkan` 은 torch 디바이스이고, wgpu 는 별도 디바이스/버퍼 소유권을 가진다. 같은 MTLBuffer/VkBuffer 를 공유하려면 interop 이 필요하다 [I]. 커스텀 op 가 자체 Vulkan/Metal 호출을 쓰는 편이 버퍼 공유가 쉽다 [I].

## 3. CubeCL (tracel-ai/cubecl)

- 백엔드: CUDA, ROCm(HIP), wgpu 를 통한 Vulkan(SPIR-V)/WebGPU(WGSL)/Metal, CPU(SIMD). 라이선스 MIT/Apache-2.0. 상태 "Alpha, Burn 이 프로덕션 사용" [V github.com/tracel-ai/cubecl].
- 네이티브 Metal: cubecl-metal 이 0.11.0-pre.4 에 추가되고 MSL 경로가 WGSL 폴백과 병존. 관련 이슈들(컴파일 실패, 폴백 혼선)이 최근 열려 있어 **이 경로는 아직 불안정** [V 검색요약, github.com/tracel-ai/cubecl/pull/1734, /issues/1722, /issues/1730].
- 타일/매트릭스: cubek/cubecl 의 matmul 이 텐서 코어, double buffering, 벡터화로 cuBLAS/CUTLASS 와 경쟁(RTX 4080 에서 Simple 변형이 대체로 앞섬). Apple M2 Pro 에서는 Specialization 변형이 Metal 의 plane 제어 부족으로 뒤짐 [V burn.dev/blog/sota-multiplatform-matmul].
- AOT: 공식 입장이 "JIT, 실제 실행되는 변형만 생성" [V github 요약]. 즉 **iOS 에서 그대로는 불가**. 다만 IR 에서 소스(WGSL/MSL/SPIR-V)를 빌드 타임에 뽑아 저장하는 길은 코드 구조상 가능해 보인다 [I, 확인 안 함].
- 파이썬 프런트엔드 -> CubeCL IR: CubeCL 의 입구는 Rust `#[cube]` 매크로가 IR 빌더 호출로 바꾸는 것 [V]. IR 은 Rust 크레이트 내부 API 라 파이썬에서 직접 만들려면 Rust 쪽에 IR 을 직렬화하는 얇은 계층을 우리가 써야 한다 [I]. 또 comptime 특수화는 Rust 제네릭에 묶여 있어 파이썬 쪽 표현이 어렵다 [I].
- CPU 참조 실행: CubeCL 자체 CPU 런타임이 있으나 이는 Rust 쪽이고 파이썬 인터프리터 실행이 아니다 [I].

## 4. MLIR 계열 (Triton 스택, IREE, Mojo)

- Triton: JIT 이 전제. Apple 용 외부 백엔드(triton-metal, triton-msl, triton-ext PR #126/#127)가 존재하며 MSL 을 생성하고 upstream test_core 를 통과한다고 주장하나, 개인 프로젝트이며 fp8/mxfp 행렬 유닛 없음, 1024 스레드/32KB threadgroup 상한 등 하드웨어 한계가 있다 [V 검색요약, github.com/bledden/triton-msl]. Vulkan/SPIR-V 백엔드는 이번 검색에서 찾지 못함 [V 부재는 검색 한계]. 이전 조사(FlexLA, ICLR 2026)는 Triton 위 AOT 디스패처 선례 [V torchnative DESIGN.md 인용].
- IREE: AOT 중심, Vulkan/Metal/CUDA/CPU, "Android, iOS, 베어메탈 지원", 런타임은 임베디드에서 약 30KB 까지 작아질 수 있음 [V 검색요약, iree.dev]. 그러나 이는 최소 구성이고 GPU 백엔드 포함 모바일 앱의 실제 크기와 컴파일러 빌드 부담은 확인 못함 [I]. 입력 단위가 모델(그래프) 이라, 단일 커널 DSL 을 임베드하려면 linalg/TOSA/StableHLO 로 내려야 한다 [I].
- Mojo: Apple GPU 는 nightly 에서 초기 지원, 경로가 Mojo -> LLVM IR -> AIR 비트코드 -> metallib. MAX 그래프/모델은 아직 Apple GPU 에서 미지원(조사 시점 검색 요약) [V forum.modular.com/t/apple-silicon-gpu-support-in-mojo/2295]. Modular 25.6 이 NVIDIA/AMD/Apple 통합을 표방 [V modular.com 블로그 제목]. 모조의 SIMD 구현 세부(SIMD[dtype,width] 1급 타입 등)는 이번에 재확인하지 않음 [I, 일반 지식].
- 이 계열의 공통 한계: 컴파일러 빌드/배포가 무겁고, 우리 "파이썬 AST -> IR" 프런트엔드를 MLIR 로 올리려면 MLIR 바인딩을 끌어와야 한다 [I].

## 5. 자체 소형 IR -> SPIR-V (현재 계획)

만들어야 하는 것 [I, 다른 시스템 경험에 근거한 추정. 규모는 측정 아님]:
1. 프런트엔드: `@kernel` 파이썬 AST 서브셋 -> 타입된 IR. (등급 1 의 Pyrefly 게이트와 공유 가능)
2. SIMT 코드 생성: SPIR-V 를 직접 emit (rspirv 같은 크레이트나 직접 binary writer). 구조화된 제어 흐름 규칙, 메모리 모델, 용량 한정자 처리가 필요.
3. 공유 메모리, 배리어, 원자 연산, 서브그룹 연산.
4. 행렬 유닛: SPIR-V `OpCooperativeMatrix*KHR` 직접 emit (Vulkan 경로). Metal 은 SPIRV-Cross 가 simdgroup_matrix 로 내려주는지 불확실. SPIRV-Cross 의 협력 행렬 지원은 이번에 확인 못함 [I]. 한편 naga 는 협력 행렬 입력 WGSL, 출력 SPIR-V/MSL/WGSL 이 구현 중 [V wgpu PR #8251].
5. 타일 추상화를 올리려면 레이아웃 추론, 소프트웨어 파이프라이닝(cp.async 대응), 레지스터/공유 메모리 swizzle, autotuning 대체물(AOT 이므로 빌드 타임 탐색)이 필요. 이것이 Triton/TileLang 이 수년 투자한 부분.
6. Metal 4 `matmul2d`/cooperative_tensor 는 SPIR-V 경로로 접근 불가 (MSL 4 전용, MPP 라이브러리) [V WWDC26 330, developer.apple.com]. Apple Neural Accelerator(M5) 를 쓰려면 SPIR-V -> MSL 변환이 아니라 MSL 직접 emit 이 필요하다 [I].
7. Vulkan 쪽 협력 행렬 하드웨어: Adreno 는 VK_KHR_cooperative_matrix + VK_QCOM_cooperative_matrix_conversion 이 있는 신형에서만 llama.cpp 가 활성화 중, Mali 는 일부(PanVK) 외에는 없음으로 보고됨 [V llama.cpp PR #29328 검색요약]. 따라서 **Android 에서는 협력 행렬이 기본값이 아니라 선택 경로**이고 SIMT/서브그룹 폴백이 반드시 필요하다.

## 6. CPU SIMD 쪽

- 우리 `@compiled` 는 자체 typed IR -> C (typedpython.md §4.3). 선택지 [I, 이번에 재조사 안 함]:
  a. C 벡터 확장 (`__attribute__((vector_size))`, clang/GCC 공통, MSVC 불가). 소스 생성이 단순하고 NEON/AVX 로 내려감. 폭이 고정이라 SVE 류는 못 씀.
  b. Highway: 런타임 디스패치와 가변 폭 지원. C++ 템플릿이라 C 생성기와 결합하려면 C++ 로 emit. 라이선스 Apache-2.0/BSD-3 [I, 기억 기반].
  c. LLVM 직접 emit: 가장 강력하나 의존성 최대. iOS 앱에 LLVM 을 싣는 것은 비현실적이고 AOT 빌드 머신에서만 쓸 수 있음. 빌드 타임 도구로서는 가능.
  d. 모조 방식: `SIMD[dtype,N]` 을 1급 타입으로 두고 LLVM 에 내림 [I 일반 지식].
- 권고(추정): a 를 기본, 핵심 BLAS 류는 손으로 쓴 NEON (torchnative 가 이미 후보로 둠), 성능이 필요한 곳에만 b.

## 7. 비교표

범례: O 가능(검증), o 가능 추정, X 불가, ? 미확인.

| 항목 | TileLang | wgpu+naga | CubeCL | MLIR(IREE/Triton/Mojo) | 자체 IR->SPIR-V |
|---|---|---|---|---|---|
| iOS AOT | ? (export 있으나 iOS 형태 미확인) | o (PASSTHROUGH 로 MSL/SPIR-V 제출 가능; 런타임은 Rust 크레이트) | X 기본(JIT). 소스 추출 개조 필요 [I] | IREE o (AOT 전제). Triton X (JIT). Mojo ? | O 설계 목표 |
| Android Vulkan | X (SPIR-V 미구현) | O (wgpu Vulkan 백엔드) | O (SPIR-V 컴파일러, JIT) | IREE O, 그 외 ? | o (직접 emit) |
| Metal | O 소스 생성(macOS, iOS ?) | O (naga MSL) | O 네이티브 cubecl-metal 신규/불안정 | IREE o, Triton 외부 포크, Mojo nightly | o (SPIRV-Cross 또는 MSL 직접) |
| WebGPU | 실험 | O (WGSL) | O (WGSL, SPIR-V 대비 느림) | IREE ? | o (naga) |
| CUDA | O (주력) | X (wgpu 에 CUDA 없음) | O | Triton/Mojo O | PTX 별도 작업 |
| 행렬 유닛 | O (CUDA/HIP/Metal) | 실험(협력 행렬, native 전용, WebGPU 표준 미확정) | O (SPIR-V/CUDA 경로, WGSL 경로 약함) | O (Triton/Mojo) | 직접 구현 필요 |
| 타일 추상화 | O | X | 부분(cubek 라이브러리 수준) | Triton O | X (뒤로 미룸) |
| 순수 파이썬 CPU 참조 실행 | X [I] | 해당 없음 | 해당 없음 | Triton 은 interpreter 모드 있음 [I 기억] | O (§2.1 설계) |
| 의존성 무게 | 큼(TVM) [I] | 중(Rust) | 중(Rust) | 큼 | 작음(우리 코드) |
| 라이선스 | MIT (TVM Apache-2.0 [I]) | MIT/Apache-2.0 [I] | MIT/Apache-2.0 [V] | IREE Apache-2.0 [I], Mojo 폐쇄적 부분 있음 [I] | 해당 없음 |
| 성숙도 | 활발, 릴리스 0.1.x | 활발, 협력 행렬 신기능 | Alpha, 변화 빠름 | IREE 성숙, Mojo Apple 초기 | 0 |

## 8. 각 선택지가 **못 하는** 것

- TileLang: Android Vulkan 불가, 파이썬 인터프리터 참조 실행 불가(추정), TVM 빌드 체인을 우리 Gradle/uv 배포에 넣는 부담, iOS AOT 형태 미확인.
- wgpu/naga: WebGPU 표준에 협력 행렬 아직 없음, WGSL 경로는 int8 dot / 협력 행렬 / 큰 공유 메모리에서 native 에 뒤짐(Burn 사례), Metal 4 tensor ops 접근 불가, CUDA 불가. 런타임이 Rust 라 torchnative 의 버퍼와 별도 소유.
- CubeCL: JIT 전제로 iOS 불가(개조 전), Rust 가 소스 언어라 파이썬 프런트엔드 입구가 없음, Alpha, Metal 네이티브 경로 불안정.
- MLIR: 무거운 빌드, 커널 단위 DSL 아님(IREE), Triton 은 JIT, Mojo 는 우리가 소유할 수 없는 폐쇄 도구.
- 자체 IR: 타일 수준 최적화(파이프라인, 레이아웃)를 전부 직접 만들어야 하고, 벤더 드라이버 버그와 기기별 튜닝은 어떤 선택지도 대신해 주지 않는다. Apple Neural Accelerator 는 SPIR-V 를 거치면 못 쓴다.

## 9. 권고 (불확실성 명시)

1. **프런트엔드와 IR 은 자체 소유를 유지한다.** 이유: §2.1(참조 CPU 실행 = 파이썬 그대로)을 만족하는 후보는 이것뿐이고 [I], 등급 1 의 게이트와 공유된다. 외부 시스템은 모두 자체 DSL 이 입구라서 §2.1 과 충돌한다 [V 각 DSL 의 입구 확인, 충돌 판단은 I].
2. **백엔드는 한 개로 고정하지 말고 emitter 를 교체 가능한 계층으로 둔다.** 1단계는 SIMT 로 SPIR-V(Vulkan, Android)와 MSL(Apple) 두 개를 직접 emit. SPIRV-Cross 경유보다 MSL 직접 emit 을 권한다(Metal 4 tensor ops, simdgroup_matrix 에 닿으려면 필요) [I].
3. **런타임은 먼저 wgpu 를 쓰지 않는다.** torchnative 의 mps/vulkan 디바이스와 버퍼를 공유해야 하는 커스텀 op 라는 위치 때문에, 자체 얇은 Vulkan/Metal 디스패처가 맞을 가능성이 높다 [I]. 웹 타깃에서만 wgpu/WebGPU 를 쓰고, 이때 WGSL 의 성능 한계를 감수한다. 협력 행렬이 필요한 웹 경로는 표준 확정 이후.
4. **CubeCL 은 "라이브러리 수준 참조" 로 쓰고 의존하지 않는다.** cubek matmul 의 타일 알고리즘(double buffering, line size)과 벤치 결과는 우리 커널 설계의 비교 기준으로 가치가 크다 [V]. 의존하려면 AOT 개조와 파이썬 IR 직렬화가 먼저다 [I].
5. **TileLang 은 데스크톱 CUDA/Metal(macOS) 에서 비교 기준(baseline)으로 쓴다.** 모바일 경로가 없으므로 주 백엔드로는 부적합 [V Vulkan 부재 / I 판단].
6. 타일 추상화는 SIMT 가 돌아간 뒤. 처음부터 Triton 급을 목표로 하면 §5 의 항목 5 가 프로젝트를 잡아먹는다 [I].
7. CPU SIMD 는 C 벡터 확장으로 시작 [I].

불확실성: (a) TVM/TileLang 의 실제 비용을 측정하지 않았음, (b) wgpu 협력 행렬의 릴리스별 현황과 성능, (c) Adreno/Mali 협력 행렬 보급률, (d) 직접 emit 한 SPIR-V 가 기기별 드라이버에서 얼마나 안 깨지는지, (e) SPIR-V -> MSL 에서 협력 행렬이 내려가는지(SPIRV-Cross 미확인).

## 10. 결정을 위해 필요한 측정

1. TileLang 을 `pip`/`uv` 로 설치한 크기, 소스 빌드 시간, Python 3.13/3.14t 지원 여부, 소스 export 로 얻는 MSL/CUDA 가 iOS 앱에 링크 가능한지(실제로 export 후 Xcode 에서 컴파일).
2. 동일 커널(GEMM 1024^3 f16, softmax, 융합 어텐션) 을 (i) 손 작성 MSL (ii) 우리 SPIR-V->SPIRV-Cross MSL (iii) naga MSL (iv) CubeCL Metal 로 M-시리즈/A-시리즈에서 GFLOPS 비교. Apple 은 simdgroup_matrix 와 Metal 4 matmul2d 둘 다.
3. Android: Adreno 7xx/8xx, Mali G7xx 에서 VK_KHR_cooperative_matrix 노출 여부와 SIMT 폴백 대비 속도(실기 3종 이상).
4. wgpu PASSTHROUGH + 우리 SPIR-V 와 직접 Vulkan 디스패치의 호출당 오버헤드(소형 커널 반복 10만 회), 그리고 torchnative 텐서 버퍼 공유 가능 여부.
5. 직접 emit SPIR-V 의 spirv-val, 드라이버 크래시율(가능한 많은 기기/Android 버전, CI 팜).
6. CPU: C 벡터 확장 vs 손 NEON vs Highway 로 dot/GEMV/q4 matmul 비교(torchnative 의 기존 커널 기준). 측정은 규칙 8 에 따라 단독, 비필터로.
7. 자체 IR 구현 비용 추정을 위한 프로토타입: SIMT vector-add + reduction + 공유 메모리 GEMM 을 SPIR-V 와 MSL 로 emit 하는 데 걸리는 기간.

## 11. 이 조사 방법이 찾지 못하는 것

웹 검색 요약과 문서 페이지 요약(작은 모델이 변환한 결과)에 의존했으므로, 실제 성능 수치, 실기 드라이버 버그, 빌드해 보아야 보이는 의존성 문제, 요약기가 누락하거나 잘못 적은 세부(특히 PDF 본문 수치)는 찾지 못한다.

## 12. torchnative 를 커널 컴파일러의 일부로 보기

표기는 위와 같다. 이 절의 **[R]** = verified (torchnative 저장소의 파일, 이번에 직접 읽음. 경로 병기, 실행하지는 않았으므로 문서가 주장하는 측정값을 재현한 것은 아님). **[V]** 는 웹 출처, **[I]** 는 추론.

### 12.0 읽고 나서 바뀌는 사실 (§2, §5 의 전제 수정)

- torchnative 에는 **이미 자체 Vulkan 경로가 있다.** `torchnative/rust/torch_c/src/vulkan.rs` (3801줄), `ash`(loaded, dlopen) 기반, GLSL `.comp` 를 빌드 머신에서 `glslc` 로 컴파일해 `.spv` 를 **저장소에 체크인**하고 `include_bytes!` 로 싣는다. `torch_c/shaders/` 에 add, matmul, bmm, softmax, layer_norm, gelu, embedding, gather 등 f32 셰이더가 있다 [R: torch_c/shaders/compile.sh 와 목록].
- 셰이더는 shape 마다 재생성하지 않는다. 모양은 push constant 로 넘기고 셰이더 하나가 모든 크기를 처리한다 [R: docs/devices/VULKAN.md §3].
- `ash` vs `wgpu` 를 **기기(에뮬레이터)에서 둘 다 돌려 비교**했다. 둘 다 통과, ash 는 크레이트 4개 719 KB / 클린 빌드 8.5 s, wgpu 는 60 크레이트 6.77 MB / 24 s. 권고는 ash. 단 "Apple GPU 를 어떻게 채우나" 가 걸려 있어, candle 의 metal 을 켜면 ash, 커널을 직접 소유하면 wgpu 가 맞다고 명시하고 결정은 조율 세션에 남겼다 [R: docs/devices/VULKAN.md §4.1, §5.1-5.3]. 이 조사의 질문(커널을 우리가 소유)은 바로 그 두 번째 칸이다.
- 단 wgpu 측정은 `Limits::downlevel_defaults()` 로 돌렸고 이는 기기 한계가 아니라 wgpu 하한, 버퍼는 최대 4 MB 였다 [R: VULKAN.md 약 381행]. 큰 텐서·협력 행렬·f16 에서의 비교는 아직 없다.
- Apple 쪽은 `mps` 디바이스가 candle Metal 위에 있고, candle Metal 에는 조용한 CPU 폴백이 없으며 이 크레이트의 커널 중 54개 op 이 호스트 되읽기라 `NotImplementedError` 로 거절한다 [R: docs/devices/MPS.md]. `Cargo.toml` 은 Apple 에서 `accelerate` 만 켜고 `metal` 은 기본값으로 끈다는 주석이 있다 [R: torch_c/Cargo.toml, DESIGN.md 1150-1160].
- 즉 §5 의 "SPIRV-Cross->MSL, naga->WGSL" 같은 **번역 계층 전략**은 torchnative 입장에서는 이미 한 번 검토되어 "가장 가벼운 것 = 직접 ash + 체크인된 SPIR-V" 로 기운 상태다. 그 방향과 정합하도록 §9 권고 3("wgpu 를 먼저 쓰지 않는다")은 근거가 강화된다.

### 12.1 (a) 빌드 타임 그래프 캡처 -> 융합 구간 -> 커널 컴파일러

**상태 [R]**: `torch_c/src/capture.rs` + `docs/graph/CAPTURE.md`. `_aten_dispatch` 단일 문(door) 끝 한 줄에서 op 을 기록한다. 기록 = (op 이름 `aten.<op>.<overload>`, 피연산자 shape/dtype/device, 결과), 가드 = 입력의 **정확한** shape/dtype/device, 재생 = eager 와 대조. FX/ExportedProgram 과 1:1 대응이라 ExecuTorch 로 갈 수 있는 모양이라는 판단이 문서에 있다 (단 "부족한 네 가지" 가 명시됨, 이번에는 열람하지 않음).
**거절하는 것 [R: CAPTURE.md §4]**: 텐서 값 기반 제어 흐름(`_local_scalar_dense`), 인플레이스/별칭, **동적 shape**, 난수, 비텐서 결과, 컨테이너. 상수(가중치)는 참조로 잡아 가드하지 않음. 배치 차원은 자유롭지 않음.
**torch.compile 은 불가 [R]**: `docs/graph/COMPILE.md` 는 abi3 에서 PEP 523 프레임 평가 훅이 막힌다고 진단하고, 대안으로 `torch.export` 경로를 본다. `docs/graph/EXPORT6.md` 는 실제 transformers 40개 아키텍처 중 export 성공이 **0/40** (벽 8개 중 상당수 해결, 그래도 헤드라인은 0/40) 이라고 적는다.

이 역할이 필요로 하는 것과 막는 것:

1. **융합 패턴 선정기.** 기록에서 직선 구간을 찾아 "gated MLP", "RMSNorm+residual", "SDPA", "dequant+matmul" 같은 패턴을 추출. `NPUFUSE.md` 가 Intel NPU 에서 gated MLP 를 OpenVINO 그래프 하나로 낮춘 선례다 (단 NPU 에서는 아직 실행 안 됨, 문서가 스스로 명시) [R]. 이것은 새 컴파일러가 아니라 **패턴 매처**로 시작하는 게 현실적이다 [I].
2. **동적 shape (LLM 디코드).** 막힘의 핵심. 디코드는 KV 길이가 매 스텝 변하고, 현재 캡처 가드는 정확한 shape 를 요구한다 [R]. 외부 사례: ExecuTorch 는 LLM 에서 sequence 차원을 dynamic 으로 표시하며, 정적 shape 만 되는 백엔드(예: QNN)에는 prefill(최대 길이로 패딩)과 단일 토큰 decode 모델을 따로 export 한다 [V docs.pytorch.org/executorch/1.0/llm/export-custom-llm.html, 검색요약]. 즉 업계도 "shape 별 그래프 2~몇 개 + 패딩" 으로 해결한다. torchnative 도 같은 길(prefill 그래프, decode 그래프를 각각 캡처, KV 는 최대 길이 사전 할당 + 길이는 스칼라 입력)을 택하는 것이 가능해 보인다 [I]. AOTInductor 가 동적 shape 를 어떻게 다루는지는 이번에 원문으로 확인하지 못했다 (ExecuTorch 의 Metal/CUDA 가 AOTInductor 를 활용 중이라는 검색요약만 [V]).
3. **커널 쪽 계약.** 융합 구간을 TypedPython `@kernel` 로 쓴 커널에 매핑하려면, 커널이 **shape 를 데이터(push constant/uniform)로 받는** 형태여야 한다. torchnative Vulkan 셰이더가 이미 그렇게 한다 [R]. TypedPython 커널 시그니처에 "shape/stride 를 런타임 인자로, tile 크기는 컴파일 타임 상수" 를 요구사항으로 넣는 것이 자연스럽다 [I].
4. **자동 코드 생성은 이 단계에서 불필요.** 융합 영역을 즉석에서 Inductor 식으로 생성하려면 pointwise/reduction 스케줄러가 필요한데 규모가 크다 [I]. 1단계는 "사람이 `@kernel` 로 쓴 융합 커널 + 캡처 기록에서 패턴 매칭으로 치환" 이면 된다. 이것은 DESIGN.md §8 의 `use_kernel_forward_from_hub` 레이어 치환과 같은 자리다 [R].

못 하는 것: torch.compile 의 자동 융합 대체, 데이터 의존 제어 흐름(MoE 라우팅, 조기 종료), 인플레이스 KV 갱신(변이는 거절됨, 단 KV 캐시 갱신이 정확히 그 경로 [R: DESIGN.md §4 "주의"]). **KV 캐시 인플레이스가 캡처 거절과 정면 충돌**한다 점이 가장 큰 구체적 장애다 [I: 두 문서의 결합에서 도출].

### 12.2 (b) torchnative 디바이스 층을 커널 런타임으로 공유

**상태 [R]**: Vulkan(ash) 디바이스 존재(f32 위주 셰이더, 1훈련 스텝까지 도는 단계 VULKAN10), Metal 은 candle 경유 mps, CPU 는 candle + 자체 커널. 각 디바이스에서 Metal/Vulkan 의 저장소(storage)는 torchnative 가 소유한다.

장점 [I]: TypedPython 커널이 별도 런타임을 갖지 않고 같은 버퍼·큐·동기화를 쓰면 (1) 호스트 복사 없음, (2) 커널 소유 문제(수명, 풀링)가 한 곳, (3) 앞서 §0 이 제기한 "Taichi 식 고립" 을 피한다.
필요한 것:
- torchnative `vulkan.rs` 에 **외부 SPIR-V 를 받는 "임의 커널 디스패치" 진입점**: 파이프라인 캐시, 디스크립터 레이아웃 선언(버퍼 개수, push constant 크기), 워크그룹 크기. 현재 프로브는 디스패치마다 파이프라인·풀을 만들고 부수며 이는 "프로브라서 그럼" 이라고 문서가 명시, 커널이 몇 개를 넘으면 얇은 내부 계층이 필요하다고 스스로 적었다 [R: VULKAN.md §5.2].
- Metal 쪽: 현재 candle-metal 이 MTLDevice/큐를 소유한다. 커스텀 MSL 을 끼우려면 candle 의 command buffer 에 인코딩하거나 같은 MTLBuffer 를 받아 별도 인코딩해야 한다. candle-metal-kernels 가 `rust/vendor/` 에 벤더링되어 있어 [R] 수정은 가능하나 버퍼 공유 방식은 코드를 읽지 않아 모른다 [I].
- 행렬 유닛 접근: 현재 셰이더에 cooperative matrix 사용 흔적은 확인하지 못함(.comp 이름만 열람, 내용 미열람) [R 한계].
막힘: Vulkan 셰이더가 f32 뿐이면 AI 커널 목적(f16/bf16/int4 matmul)에 부족. 이는 TypedPython 커널의 첫 번째 실제 수요처다 [I].

**wgpu 선택지와의 상호작용 [R + I]**: torchnative 는 wgpu 를 이미 기기에서 통과시켜 둔 프로브(`vulkan_probe` 의 `--features wgpu-route`)를 보존하고 있다 [R]. 그래서 두 런타임이 공존하면 버퍼가 둘로 갈라진다. 권고: 네이티브(Android Vulkan, Apple Metal)는 torchnative 디바이스 층(ash/candle-metal), **웹 타깃만 wgpu(WebGPU)** 로 분리하고, 두 경로는 같은 IR 에서 SPIR-V/MSL/WGSL 을 각각 emit 하는 emitter 로만 만난다. 웹에서 torchnative 를 쓰는지는 `wasm_probe` 가 있다는 것만 확인 [R].

### 12.3 (c) torchnative CPU 경로를 수치 오라클로

**상태 [R]**: `torch_c/src/flash.rs` 는 ATen CPU flash-attention 을 bf16/fp16 에서 **bit-identical** (226136개 원소 중 차이 0) 로 재현한 "참조 구현" 이고, f32/f64 는 upstream 이 BLAS 를 쓰므로 비트 일치 불가(차이 4.17e-07 수준)라고 문서가 구분한다. `vulkan_probe/src/check.rs` 는 GPU 와 CPU 참조를 같은 하니스로 비교하는 패턴이다.
역할 적합성:
- 수치 검증의 좋은 오라클이 된다. 단 **무엇과 일치해야 하는가를 먼저 정해야 한다**: TypedPython 의 순수 파이썬 참조 실행(§2.1) vs torchnative CPU(candle) vs 상류 PyTorch. 세 개의 오라클이 서로 다른 reduction 순서를 쓰므로 f32 에서는 허용오차 기반(ULP 한도 선언) 비교가 필요하다 [I, flash.rs 의 문서가 이 현상을 직접 보여줌 R].
- 기기에서 CPU 오라클을 쓰는 것(디바이스 내 자기 대조)은 이미 프로브가 한다. 같은 방식으로 `@kernel` 하나당 (입력 시드, CPU 참조, GPU 결과) 를 출력하는 테스트 형식을 재사용할 수 있다 [I].
- 한계: 오라클이 candle 이라면 candle 의 의미론 차이(복사 지향, view/in-place 임피던스 [R]) 가 섞인다. 커널 단위 테스트는 raw 버퍼 입력이라 영향이 작다 [I].

### 12.4 (d) kernels 번들 리졸버를 착지 슬롯으로

**상태 [R: DESIGN.md §8]**: HF `kernels` 계약(백엔드 변형 선언, transformers 무수정 교체)을 채택하고, 해석 시점을 빌드 타임으로 옮겨 Gradle 플러그인이 변형을 골라 AOT 컴파일·번들, 런타임 리졸버가 번들을 조회. 타깃 표에서 Android GPU 는 `kernels` 에 `vulkan` 백엔드가 없어 **구멍**이고, 권고는 "1) Android 는 CPU 만으로 시작 + 2) 상류에 vulkan 백엔드 제안 병행".
TypedPython 입장 [I]:
- `@kernel` 하나가 `metal` / `vulkan`(비표준) / `cpu` / `cuda` 변형 번들을 산출하는 **`kernel-builder` 대체 빌더**가 되는 구도. 이것이 §5.4 의 "`torch.library` 커스텀 op 등록" 보다 구체적인 착지 형태다. 두 개를 동시에 만족시키려면: op 등록은 torchnative 의 ATen 디스패처(`aten.<op>.<overload>`), 변형 선택은 리졸버로 하는 이중 구조가 필요할 수 있다. 어느 쪽이 정본인지는 결정 필요.
- 업스트림 `vulkan` 백엔드 슬롯 제안과 TypedPython 의 Vulkan emitter 는 한 묶음으로 움직이는 게 맞다. 이것이 DESIGN.md §8 의 선택지 2 를 실체화한다.
- 막힘: 빌드 타임 AOT 컴파일을 Gradle 플러그인이 호출해야 하므로 TypedPython 컴파일러(파이썬 프로세스) 호출 비용과 증분 컴파일(SPEC N-10)의 연계가 필요 [I].

### 12.5 종합 (이 절의 권고, 모두 [I])

1. 가장 얇은 수직 슬라이스: **`@kernel` 1개 (f16 GEMV 또는 RMSNorm) -> SPIR-V 직접 emit -> torchnative `vulkan.rs` 에 임의 SPIR-V 디스패치 진입점 추가 -> 기기에서 CPU 오라클과 대조.** 이미 있는 프로브·체크인 SPIR-V 흐름과 같은 모양이라 새 위험이 가장 적다.
2. wgpu 는 **웹 전용**으로 한정. torchnative 의 기존 측정(719 KB vs 6.77 MB)이 모바일 네이티브에서의 기각 근거다 [R].
3. 그래프 캡처(a)는 커널 컴파일러의 선행 조건이 아니다. 먼저 레이어 치환(`use_kernel_forward_from_hub` 식)으로 가고, 캡처 기반 융합은 KV 인플레이스와 동적 shape 문제가 풀린 뒤로 미룬다. torch.export 0/40 이 그 이유다 [R].
4. 결정이 필요한 것: (i) Apple 은 candle-metal 에 끼울지, TypedPython 이 MSL 을 emit 하고 자체 Metal 디스패처를 둘지, (ii) 정본 등록 경로(ATen 디스패처 vs kernels 리졸버), (iii) Vulkan f16/cooperative matrix 를 torchnative 디바이스 층의 범위로 볼지.

### 12.6 추가 측정 항목 (§10 에 더해)

8. torchnative Vulkan: 디스패치 1회당 오버헤드 (파이프라인 캐시 전/후), 동일 matmul 의 f32 GFLOPS (Adreno/Mali 실기), f16 셰이더 가능 여부.
9. candle-metal 에 외부 MSL 커널을 같은 command buffer 로 끼울 수 있는지(코드 열람 + 프로토타입).
10. 캡처: Llama 급 1개 모델의 decode 스텝을 "KV 최대 길이 고정 + 길이 스칼라" 로 쓰면 캡처 가드를 통과하는지 (인플레이스 KV 갱신 때문에 현재는 거절될 것으로 예상, 미측정).
11. 세 오라클 (파이썬 참조, candle CPU, 상류 PyTorch) 의 f32 softmax/matmul 편차 크기.

이 절의 조사 방법이 찾지 못하는 것: 문서의 측정값을 재현하지 않았고(문서가 주장하는 숫자를 그대로 인용), `vulkan.rs` 3801줄과 candle-metal 연동 코드는 본문을 읽지 않았으며, 문서가 "해결됨" 이라 적은 항목이 현재 코드에서도 참인지는 확인하지 못한다.
