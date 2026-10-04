# TypedPython 커널 컴파일러 IR 선택 (2026-10-04)

표기: **[V]** = verified (직접 실행한 명령 또는 fetch 한 URL), **[R]** = 로컬 저장소 파일을 직접 읽음, **[I]** = inference.
결론의 반영: `docs/design/typedpython.md` §5.4 (2026-10-04 결정). 조사 당시 작업 위치는 git 에 넣지 않은 `.tmp/research/` 였다(스크립트와 venv 는 커밋하지 않았다). venv 두 개(`venv` = py3.13 + xdsl, `venv314t` = py3.14 free-threaded + MLIR 바인딩 wheel). 스크립트 `t_spirv.py`, `t_bin.py`, `t_ptx.py`.

## 0. 전제 (읽은 문서)

- [R] typedpython.md §2.1: 유효한 파이썬만, 커널도 CPython 에서 그대로 실행(참조 구현). §5.1: iOS 때문에 AOT 가 하드 제약. §5.3 현재 경로: 자체 소형 IR -> SPIR-V -> (Vulkan | SPIRV-Cross->MSL | naga->WGSL), PTX 별도. 착지점 torchnative.
- [R] torchnative `vulkan.rs` (3801줄): `ash` 로 로더를 dlopen, GLSL 을 `glslc` 로 컴파일한 `.spv` 를 저장소에 체크인하고 `include_bytes!` 로 싣는다(71행). `create_shader_module`(692행) -> `create_compute_pipelines`(750행), 형상은 push constant(866행) 으로 전달, 디스패치는 `dispatch_kernel` / `dispatch_kernel_words`(788, 801행). 즉 **임의 SPIR-V 워드열을 받는 진입점이 이미 `dispatch_kernel_words` 모양으로 존재**하고, 첫 슬라이스의 도착 지점은 "`.spv` 를 컴파일러가 만든 것으로 바꾸는 것" 이다 (이전 조사 §12 의 "임의 SPIR-V 진입점이 필요" 는 이 파일의 현재 상태로 보면 부분적으로 이미 해결됐을 가능성, 단 파이프라인 캐시 상태는 읽지 않음) [R + I].
- 이전 조사(kernel-backend.md)의 "자체 프런트엔드 + 자체 IR" 권고는 MLIR 을 내부 인프라로 평가하지 않았다. 이 문서가 그 공백을 채운다.

프런트엔드(valid Python `@kernel` 서브셋 파싱, 등급 1 게이트와 공유)는 어느 선택지에서도 우리 몫이다. 선택지가 갈리는 곳은 **프런트엔드 뒤: IR, 변환(tiling/layout/pipelining), 코드 방출(emitter)** 이다.

## 1. 직접 확인한 사실 (이번 조사에서 실행한 것)

### 1.1 MLIR Python 바인딩 wheel

- [V] PyPI 의 `mlir-python-bindings` 는 **이름 선점용 빈 placeholder** (0.0.1, "not the real distribution"). `mlir` 도 2023 년 빈 패키지. 즉 `uv add mlir-python-bindings` 는 진짜를 주지 않는다 (`curl pypi.org/pypi/<name>/json`).
- [V] 실제 wheel 은 GitHub release `llvm/eudsl` 태그 `mlir-python-bindings` 에 있다 (GitHub API 로 목록 조회: 658 assets, 최신 `mlir_python_bindings-20261004+758aa4a92-*`, 거의 매일 갱신). 설치 가이드: `pip install mlir-python-bindings -f https://llvm.github.io/eudsl` (eudsl README 요약). 이전 저장소 `makslevental/mlir-wheels` 는 2025-11-29 archive, eudsl 로 이전됨 [V WebFetch].
- [V] 플랫폼/파이썬 매트릭스 (asset 이름 집계): cp310, 311, 312, 313, 314, **cp314t(free-threaded)** 각각 macOS arm64(`macosx_13_0_arm64`), Linux x86_64/aarch64(manylinux_2_34), Windows amd64. **cp315 / cp315t wheel 은 없음** (`cp315` 문자열 asset 0개). 크기 wheel 당 58 ~ 76 MB, 설치 후 `mlir/` 디렉터리 253 MB (macOS cp314t).
- [V] 실제로 설치해 실행: `uv venv venv314t --python 3.14t; uv pip install --python venv314t/bin/python <macOS cp314t wheel URL>` 성공. `sys.version` = "3.14.7 free-threading build", `import mlir` 후 `sys._is_gil_enabled()` = **False** (GIL 이 다시 켜지지 않음). 등록된 dialect: spirv, gpu, nvvm, vector, linalg, transform, amdgpu, x86, nvgpu, emitc, llvm, scf, memref, tensor, tosa 등 (`pkgutil.iter_modules(mlir.dialects.__path__)`).
- [V] 공식 문서(mlir.llvm.org/docs/Bindings/Python/): free-threading 은 3.13+ 에서 "독립된 Context 라면 멀티스레드 안전". 안전하지 않다고 명시한 것: IR printing 과 PassManager 동시 사용, location error emit, module dump, transform interpreter, **GPU dialect 관련 작업**. 우리는 컴파일을 빌드 타임에 단일 프로세스/스레드로 돌리면 되므로 영향이 작다 [I].
- [V] 동작 증명 1, **SPIR-V 바이너리**: `gpu.module @k [#spirv.target_env<...>] { spirv.module Logical GLSL450 requires #spirv.vce<v1.3,[Shader],[]> {...} }` 에 `gpu-module-to-binary` 를 돌리면 `gpu.binary` 에 `\03\02#\07` (SPIR-V magic 0x07230203) 로 시작하는 바이너리 blob 이 나온다 (`t_bin.py`). 즉 **파이썬 안에서 SPIR-V 직렬화까지 도달**. 단 `vce_triple` 없으면 "module must have 'vce_triple' attribute to be serializeable" 로 실패.
- [V] 동작 증명 2, **gpu -> spirv 하향**: `gpu.func` (global_id, memref.load, arith.addf, memref.store) 에 `convert-gpu-to-spirv` 를 돌리면 `spirv.func` 가 나온다 (`t_spirv.py`; StorageBuffer 구조체 래핑, GlobalInvocationId builtin 포함). 이어 `gpu-module-to-binary` 를 같은 모듈에 바로 돌리면 "the module has no target attributes" 로 실패: 하향 결과의 `spirv.module` 이 `gpu.module` 의 형제로 남고 target 속성이 없기 때문. **하향 산출물을 `gpu.module [target]` 안에 넣는 접합 코드는 우리가 써야 한다** (실행해 보지 않음, [I] 난이도 낮음). 이 end-to-end(f32 add -> .spv 바이트) 는 **검증하지 못했다**.
- [V] 동작 증명 3, **PTX**: `gpu.module @k [#nvvm.target<chip="sm_80">]` 에 `gpu.module(convert-gpu-to-nvvm,canonicalize),gpu-module-to-binary{format=isa}` 를 돌리면 `.version 7.0 .target sm_80 ... .visible .entry f(` 로 시작하는 PTX 텍스트가 나온다 (`t_ptx.py`). wheel 에 NVPTX 백엔드가 들어 있다는 뜻.
- [V] `spirv.KHR.CooperativeMatrixLoad/Store/MulAdd/Length` op 이 파이썬 dialect 모듈(`mlir.dialects._spirv_ops_gen`)에 있음. `gpu-lower-to-nvvm-pipeline`, `convert-vector-to-spirv`, `convert-arith-to-spirv`, `convert-nvgpu-to-nvvm` 패스가 파싱됨.
- [V] 확인 못 한 패스 이름: `spirv-lower-abi-attrs`, `spirv-update-vce`, `convert-gpu-to-nvvm` (Context 없이 파싱 시도했거나 이름이 다를 수 있음; `convert-gpu-to-nvvm` 은 위 PTX 실험에서 `gpu.module(...)` 안에서는 동작). 결론 가능한 것은 "일부 패스가 파이썬에서 호출 가능" 까지.

### 1.2 xdsl

- [V] `uv pip install xdsl` -> xdsl 0.72.0 (PyPI 2026-10-02 릴리스). 순수 파이썬, 의존성 `immutabledict`, `ordered-set`, `typing-extensions` 셋뿐. Requires-Python >=3.10, classifier 3.14 까지. **py3.14t venv 에서도 설치/임포트되고 `sys._is_gil_enabled()` = False** [V]. 3.15t 는 시험하지 못함 (순수 파이썬이라 [I] 가능성 높음).
- [V] 라이선스: PyPI METADATA 는 "Apache License v2.0 with LLVM Exceptions". GitHub API 의 SPDX 는 NOASSERTION (표기 불일치, 사소함). GitHub star 595, 최근 push 2026-10-03, PyPI 릴리스 126 개, 최근 8 개가 2026-05-29 ~ 2026-10-02 사이 (약 2~4 주 간격) [V].
- [V] dialect 목록 (`pkgutil.iter_modules(xdsl.dialects.__path__)`): acc, affine, arith, arm, arm_neon, bufferization, builtin, cf, comb, complex, csl, dlti, emitc, func, **gpu**, linalg, llvm, math, memref, memref_stream, ml_program, omp, pdl, ptr, riscv*, scf, snitch*, stencil, tensor, tosa, **transform**, ub, **vector**, wasm, x86*, 등. **`spirv` dialect 없음**: `grep -ril "spirv\|spir-v" site-packages/xdsl` 결과 0 건 [V].
- [V] 백엔드 (`xdsl.backend`): `llvm` (xdsl -> LLVM IR 텍스트 변환, 약 950줄), `wgsl/wgsl_printer.py` (252줄), `riscv`, `x86`, `csl`, **`mps` 는 스텁** (`print_to_mps` 가 `NotImplementedError("MPS backend not yet implemented")`, AIR 비트코드를 목표로 한다고 docstring). `xdsl-opt` 의 대상 목록: csl, llvm, mps, riscv-asm, wgsl, x86-asm. SPIR-V, MSL, PTX 방출기는 없음.
- [V] gpu dialect(796줄)는 launch/func/module/global_id/barrier/subgroup_id 등 기본 op 위주이며, `subgroup_mma` 계열 op 이름은 상위 60 개 심볼 출력에서 확인되지 않음 [V 한계: 전체 grep 하지 않음]. 협력 행렬 관련 dialect 는 `grep` 상 없음.

## 2. 선택지 비교

### 옵션 1. 완전 자체 IR + 자체 방출기

- 대상별 경로 [I]:
  - SPIR-V: 우리 IR -> SPIR-V 워드를 직접 방출(binary writer 수백 줄 + 구조화 제어 흐름(OpLoopMerge/OpSelectionMerge), 메모리 모델, Capability/Extension 선언, 협력 행렬 `OpCooperativeMatrix*KHR`). `spirv-val` 로 검증 가능.
  - MSL: 같은 IR 에서 텍스트 방출. 어느 옵션이든 MSL 은 우리가 쓴다(아래 참조), 자체 IR 에서는 **가장 자연스럽다**.
  - WGSL: 텍스트 방출. SPIR-V 로 가는 것보다 단순.
  - PTX: 텍스트 방출(작은 부분집합) 또는 LLVM 에 의존. 자체 PTX 방출은 레지스터 할당/주소 공간이 일.
  - CPU SIMD: C(벡터 확장)로 방출, 이는 §4.3 에서 이미 정한 `typed IR -> C` 와 합칠 수 있다 [R typedpython.md §4.3].
- 타일 변환: **전부 직접.** 레이아웃 추론, 소프트웨어 파이프라인, swizzle, autotune 대체물. 이것이 가장 큰 일이다 (이전 조사 §5.5).
- 도구 무게: 최소. 순수 파이썬 코드, 3.15t 문제 없음 [I].
- 위험: 수년 걸리는 변환 인프라를 혼자 쌓는다. 반면 MSL/SPIR-V 직접 제어 가능, 디버깅이 쉽다.
- 첫 슬라이스 (f16 elementwise 또는 softmax -> SPIR-V -> `vulkan.rs` 디스패치 -> CPU 참조와 비교): 파이썬 AST -> 타입 IR(어차피 필요), SPIR-V 방출 약 800 ~ 1500 줄, 호스트 하니스. 추정 2 ~ 4 주(1인) [I, 측정 아님]. f16 은 `Float16` + `StorageBuffer16BitAccess` capability 필요(기기 의존).
- 타일 matmul + cooperative matrix: IR 에 tile 타입과 `mma` op, `OpCooperativeMatrix*` 방출, 공유메모리 타일링, 정밀한 레이아웃. 추정 2 ~ 4 개월 [I]. Adreno/Mali 에서는 기본값 아님(선택 경로) [V 이전 조사, llama.cpp PR 요약].

### 옵션 2. MLIR (upstream, 파이썬 바인딩, C++ 코어)

- 획득 [V §1.1]: PyPI 에 없음. `llvm/eudsl` release 의 nightly wheel(매일, cp314t 포함, macOS arm64 / Linux x86_64+aarch64 / Windows x86_64). 커밋 해시가 버전에 붙는 **nightly 스냅샷**이라 안정 릴리스 번호가 없고 고정하려면 특정 날짜 wheel URL 을 lockfile 에 박아야 한다 (uv 의 `--find-links` 또는 URL 의존성). 직접 빌드는 LLVM 전체 빌드(`MLIR_ENABLE_BINDINGS_PYTHON=ON`, nanobind) 이며 시간/디스크가 크다 [I, 빌드해 보지 않음].
- 버전 churn: 매일 갱신되는 upstream main 추종. Python 바인딩 API, dialect op 이름, 패스 옵션이 자주 바뀐다 (예: 검색에서 확인된 SPIR-V KHR CooperativeMatrix Load/Store Stride 옵션화 PR #214194, 협력 행렬 operand 수 수정 PR #118014) [V 검색요약]. 완화: 특정 날짜 wheel 로 고정하고 분기별로 올리는 정책 [I].
- 3.15t: **wheel 없음** (cp314t 까지) [V]. 기반이 nanobind 라 소스 빌드로 3.15t 가능성은 있으나 미확인 [I]. 사용자 목표가 3.15t 이므로, 컴파일러(빌드 타임)를 3.14t 또는 GIL 파이썬 별도 인터프리터에서 돌리고 앱 런타임과 분리하는 것이 현실적 [I]. 공식 문서가 free-threading 에서 GPU dialect/PassManager 동시 사용을 안전하지 않다고 명시한 점도 단일 스레드 컴파일로 우회 [V 문서].
- 대상별 경로:
  - SPIR-V: gpu/vector/arith/memref/scf -> `convert-gpu-to-spirv` -> `spirv` dialect -> `gpu-module-to-binary` 로 직렬화 [V 부분: 각 단계 개별 실행. 연결은 접합 코드 필요]. 협력 행렬: `gpu.subgroup_mma_*` 가 `spirv.KHR.CooperativeMatrixMulAdd` 로 내려가는 변환이 있다는 포럼 글 [V 검색요약], `spirv.KHR.CooperativeMatrix*` op 은 설치본에 존재 [V]. 실제로 f16 협력 행렬 matmul 이 변환을 통과해 Adreno/Mali 에서 도는지는 **검증 못함**.
  - MSL: **upstream 에 MSL 타깃 없음**. 우리가 확인한 설치본에서 dialect 목록(spirv, gpu, nvvm, amdgpu, emitc 등)에 metal/msl 이 없다 [V 목록 확인]. 외부 사례만 있음: Triton 외부 백엔드(triton-ext PR #126, bledden/triton-msl)가 TTGIR 에서 MSL 을 방출하는 패스를 가진다 [V 검색요약]. 가능한 길 둘: (a) `emitc` dialect 로 C++ 형 텍스트를 뽑는 구조를 흉내 내 **MSL 프린터를 우리가 작성**, (b) SPIR-V -> SPIRV-Cross -> MSL (협력 행렬은 simdgroup_matrix 8x8 float 로만 제한되고 최근 initial 지원 단계 [V 검색요약, SPIRV-Cross PR #2645]; Metal 4 cooperative tensor / matmul2d 는 도달 불가 [V 이전 조사]). 따라서 Apple 은 어느 쪽이든 MSL 방출기를 직접 쓴다 [I].
  - WGSL: upstream 에 WGSL 타깃 없음 [V 목록]. SPIR-V -> naga 또는 직접 방출.
  - PTX: **되는 것을 확인** [V §1.1, `t_ptx.py`]. 가장 성숙한 경로.
  - CPU SIMD: `vector` + `llvm` dialect -> LLVM IR -> ExecutionEngine/오브젝트 [V: `mlir.execution_engine` 임포트됨. 실제 NEON 오브젝트 방출은 시험 안 함]. 또는 `emitc` 로 C. iOS 앱엔 LLVM 을 싣지 않고 빌드 타임에 오브젝트/라이브러리만 [I].
- 타일/레이아웃/파이프라인 변환: **여기가 MLIR 의 최대 이점.** `linalg`(structured ops) + `transform` dialect(`structured.tile_using_for`, vectorize 등), `vector.contract` 패턴, `gpu` dialect 의 mma/예약 [V 검색요약 + 설치본에 `_structured_transform_ops_gen`, `_gpu_transform_ops_gen`, `_nvgpu_transform_ops_gen` 존재 [V]]. 소프트웨어 파이프라이닝(cp.async 급) 은 nvgpu 쪽에 일부, Vulkan 쪽은 IREE 가 가진 별도 코드젠(IREE 의 SPIR-V 파이프라인은 upstream 이 아님 [V iree.dev 요약])이라 upstream 만으로 Vulkan 에서 Triton/IREE 급 matmul 성능이 나온다는 보장은 없다 [I].
- 프런트엔드 접합: 파이썬 AST -> MLIR 를 `ir.Module`/`OpBuilder` 로 구성. "valid Python 그대로 실행" 요건은 프런트엔드가 AST 를 읽는 방식이므로 충돌하지 않는다 [I].
- 도구 무게: wheel 66 ~ 76 MB / 설치 253 MB, 빌드 타임 전용이므로 앱 크기와 무관. 단 uv/pypackpack 설치 흐름에 PyPI 가 아닌 외부 index(find-links) 가 들어가고 날짜 고정이 필요하다 [V + I].
- 위험: (1) nightly 스냅샷 의존과 API churn, (2) 3.15t wheel 부재, (3) eudsl 이 자체를 "alpha" 로 표기 [V], (4) MSL/WGSL 은 어차피 직접 방출, (5) 디버깅이 C++ 패스 안에서 일어나 에러 메시지가 불친절 [I], (6) wheel 이 단일 메인테이너 프로젝트(makslevental -> llvm/eudsl) 의 유지에 달림 [I].
- 첫 슬라이스: 접합 코드(gpu.module 에 spirv.module + target_env/vce 부착) + 프런트엔드 -> MLIR 빌더 + 형상/버퍼 ABI 정리(`spirv.interface_var_abi`, push constant 는 MLIR 에서 `spirv.StorageClass PushConstant` 구성 필요 [I]). 추정 2 ~ 4 주 [I]; 환경/버전 고정과 f16/ABI 문제 때문에 옵션 1 과 비슷하거나 약간 더 걸릴 수 있다. 이득은 슬라이스 이후.
- 타일 matmul + cooperative matrix: 변환 파이프라인을 upstream 패스로 조립. Vulkan KHR 협력 행렬 경로는 존재하나 성능 튜닝 정도는 모름. 추정 1 ~ 3 개월, 하지만 위험이 옵션 1 보다 "변환이 안 맞을 때 고칠 수 없음" 쪽에 쌓인다 [I].

### 옵션 3. xdsl (순수 파이썬 MLIR 호환)

- [V §1.2] 설치 쉬움(PyPI, 의존 3개, 3.10 ~ 3.14t), 활발(2~4 주 릴리스), 라이선스 Apache-2.0 with LLVM Exception. 3.15t 는 미시험이나 순수 파이썬이라 [I] 문제 가능성 낮음.
- MLIR 텍스트 호환: `xdsl-opt` 로 MLIR 텍스트를 읽고 쓴다(프린터/파서). 따라서 **xdsl <-> MLIR(mlir-opt, 위 wheel) 상호운용을 텍스트로 연결**할 수 있다 [I, 두 도구를 실제로 연결해 보지 않음]. 즉 옵션 3 은 옵션 2 와 배타적이지 않다: 프런트엔드/고수준 변환은 xdsl, SPIR-V 직렬화나 NVPTX 는 MLIR wheel 에 텍스트로 넘기는 혼합 구성이 가능 [I].
- 대상별 경로:
  - SPIR-V: **방출기 없음, spirv dialect 도 없음** [V]. 텍스트를 MLIR 에 넘겨야 하거나 SPIR-V 방출을 직접 써야 한다.
  - MSL: 없음. `mps` 백엔드는 AIR 비트코드를 목표로 한 NotImplemented 스텁 [V].
  - WGSL: 있음(252줄) 단 범위는 작음(소스에서 `gpu.FuncOp`, `memref.StoreOp` 처리 등 기본 연산) [V 부분: 코드 헤드만 확인, 지원 범위 전체는 미확인].
  - PTX: 없음. LLVM IR 텍스트 변환(`xdsl.backend.llvm`)은 있어 `llvm` 의 NVPTX 로 이어 붙일 수 있으나 llvmlite 등 별도 필요 [I].
  - CPU SIMD: `vector`, `x86`, `arm_neon`, `riscv`, `llvm` 백엔드 등 있음. 실사용 성숙도는 미확인.
- 변환: `transform` dialect(1042줄) 와 `vector`(1470줄), `linalg` 가 있으나 upstream MLIR 의 `structured.*` 변환 패스 수준인지 확인 못 함. 협력 행렬/타일링 패스 존재는 확인 못 함 [V 한계]. 패스를 파이썬으로 쓰므로 새 패스 작성/수정은 쉬움(`pattern_rewriter`) [I].
- 성숙도: 연구/컴파일러 교육 중심 프로젝트(스텐실, CSL, RISC-V/Snitch 등) 이며 GPU 코드젠 프로덕션 사례는 확인 못함 [I, 근거: dialect 구성].
- 위험: 필요한 후단(SPIR-V/MSL/PTX 방출, 협력 행렬 dialect)이 거의 비어 있어, 옵션 1 에서 "IR 클래스 + 패스 인프라 + 프린터" 를 xdsl 로 빌려 오는 정도의 이득. IR 인프라(SSA, 파서, verifier, 패턴 리라이터, 정확성 검사)를 공짜로 얻는 점이 장점 [I].
- 첫 슬라이스: 옵션 1 과 같은 SPIR-V 방출기를 어차피 써야 하므로 추가 이득은 IR 인프라 재사용뿐. xdsl 학습 비용 + 방출기 직접 작성 -> 2 ~ 4 주 [I]. Cooperative matrix: spirv dialect 를 xdsl 에 추가(IRDL 로 정의 가능 [I]) + 직렬화기 작성 필요. 옵션 1 대비 이득 작음.

### 기타 (간단히)

- Triton 의 MLIR dialect(TTIR/TTGIR) 재사용: 서브모듈/LLVM 포크에 묶여 있고 JIT 전제, Apple MSL 은 외부 포크(triton-ext)가 TTGIR -> MSL 을 했다 [V 검색요약]. 우리 파이썬-서브셋 프런트엔드와 TTGIR 의 레이아웃 인코딩을 쓰려면 Triton 빌드 전체가 따라온다 [I]. 비추천.
- IREE: SPIR-V 코드젠(Mali Valhall, Adreno 640+ 대상 triple) 과 Vulkan 런타임을 가진 AOT 컴파일러 [V iree.dev 요약]. 입력은 모델 단위(linalg/StableHLO)이고 산출물은 `.vmfb`(flatbuffer) 이며 SPIR-V 만 라이브러리로 뽑아 쓰는 공식 경로는 확인하지 못했다 [V 부재는 검색 한계]. 타일링/매트릭스 파이프라인이 가장 성숙한 오픈 구현이므로 **알고리즘 참조 대상**, 의존 대상은 아님 [I].
- Mojo: 닫힘, MAX 가 Apple GPU 초기 [V 이전 조사].
- SPIRV-Cross(MSL): 협력 행렬 초기 지원, simdgroup_matrix 는 8x8 float 계열만 [V 검색요약]. 즉 SPIR-V 경유 Apple 은 f16 matmul 에서 불리할 수 있고 Metal 4 tensor ops 는 불가 [V 이전 조사 + I].

## 3. 요약 표

| 항목 | 1. 자체 IR | 2. MLIR wheel | 3. xdsl |
|---|---|---|---|
| SPIR-V 방출 | 직접 작성 | **있음** (gpu/spirv dialect + `gpu-module-to-binary`, 접합 필요) [V] | 없음 [V] |
| MSL | 직접 (자연스러움) | upstream 없음, 직접 [V] | 없음, mps 는 AIR 스텁 [V] |
| WGSL | 직접 | upstream 없음 | 있음(소형) [V] |
| PTX | 직접(작게) | **있음, 실행 확인** [V] | 없음 (llvm 텍스트 경유 [I]) |
| CPU SIMD | C 벡터 확장 | vector+llvm / emitc [I] | arm_neon/x86/llvm 있음 [V 존재] |
| 타일/레이아웃/파이프라인 | 전부 직접 | linalg+transform+vector+gpu 가 큼 [V 존재, 성능은 미확인] | transform/vector 있음, 수준 미확인 |
| 설치 | 없음 | wheel 66~76MB, PyPI 아님, nightly find-links [V] | `uv add xdsl` [V] |
| 3.14t | 해당 | **wheel 있음, GIL 유지 off** [V] | **됨** [V] |
| 3.15t | 문제 없음 [I] | **wheel 없음** [V] | 가능성 높음 [I] |
| 버전 churn | 없음 | 높음 (매일) [V] | 중 (2~4 주 릴리스, 0.x) [V] |
| 첫 슬라이스 | 2~4 주 [I] | 2~4 주 [I] | 2~4 주 [I] |
| 타일 matmul + coop mat | 2~4 개월 [I] | 1~3 개월 (고장 시 수정 불가 위험) [I] | 2~4 개월 [I] |

## 4. 권고 (불확실성 명시)

1. **IR 의 정본은 우리가 소유한다(옵션 1 의 IR), 단 MLIR 호환 형태로 설계한다.** 이유: (a) §2.1 요건과 프런트엔드는 우리 몫이며 어떤 외부 IR 도 대신하지 못한다 [I], (b) MSL, WGSL 은 MLIR 을 써도 직접 써야 하므로 MLIR 이 줄여 주는 방출기는 SPIR-V 와 PTX 둘뿐이다 [V], (c) 3.15t wheel 이 없고 nightly 고정이라 도구 체인의 기초로 삼기에는 변동이 크다 [V]. 이 판단은 "방출기가 일의 대부분" 이라는 가정에 의존한다 [I].
2. **MLIR wheel 은 "SPIR-V 와 PTX 직렬화기/검증기, 그리고 비교 오라클" 로 선택적 사용을 우선 시험한다.** 우리 IR 을 MLIR 텍스트로 내리는 얇은 방출기(수백 줄)만 쓰면, `convert-gpu-to-spirv` + `gpu-module-to-binary` 로 SPIR-V 를, `gpu-module-to-binary{format=isa}` 로 PTX 를 얻는다 [V 각 단계 개별]. 막히면 직접 SPIR-V 방출로 후퇴할 수 있어 위험이 낮다. 3.15t 와는 별도 프로세스(3.14t 또는 GIL 인터프리터) 로 분리 실행 [I].
3. **타일 변환(tiling, 협력 행렬, pipelining) 에서 MLIR 의 transform/linalg/vector 를 쓸지는 슬라이스 2 이후 별도 시험으로 결정.** 지금은 약속하지 않는다. 이 영역이 MLIR 의 진짜 이점이지만 Vulkan 에서의 성능은 IREE 쪽 코드젠에 있다 [I, V 검색요약].
4. **xdsl 은 채택하지 않되 IR/패스 인프라 설계의 참고, 그리고 MLIR 텍스트 상호운용 후보로 남긴다.** 후단이 비어 있어 SPIR-V/MSL/PTX 에 이득이 없다 [V].
5. **Apple 은 MSL 직접 방출.** 이전 조사와 동일 결론이고 이번에도 어느 옵션도 대신하지 않는다 [V + I].

미결/불확실: (a) `gpu.func` 하향 결과를 `gpu-module-to-binary` 로 연결하는 접합이 실제로 쉬운지 (실행 안 함), (b) f16/협력 행렬 SPIR-V 가 실기(Adreno, Mali)에서 도는지, (c) wheel 이 NEON/x86 CPU 오브젝트를 만들 수 있는지(LLVM 타깃 구성 미확인; NVPTX 는 확인), (d) 3.15t 에서의 MLIR 소스 빌드 가능성, (e) eudsl wheel 의 장기 유지, (f) 시간 추정은 모두 측정 아닌 추정.

## 5. 다음 시험 (구체적 첫 슬라이스)

1. `t_spirv.py` 를 확장: 하향된 `spirv.module` 을 `gpu.module [target_env]` 안에 옮기고 `requires` 를 붙여 직렬화, `spirv-val` 로 검증 (`spirv-val` 은 이 환경에 설치하지 않았음).
2. 그 `.spv` 를 `torch_c/shaders/` 의 체크인 모양대로 넣고 `dispatch_kernel_words` 경로로 실행, CPU 참조(torchnative flash.rs 식 허용 오차 비교)와 대조. 이 단계는 torchnative 쪽 빌드가 필요하므로 이번 조사에서는 하지 않았다.
3. 같은 커널을 직접 방출한 SPIR-V 와 크기/명령어/기기 호환성 비교(옵션 1 대비 MLIR 산출물의 위험 평가).

## 6. 이 조사 방법이 찾지 못하는 것

wheel 설치와 개별 패스 실행으로 "존재와 단일 단계의 동작" 만 확인했으므로, 연결된 전체 파이프라인의 정확성, 실기 GPU 드라이버 동작, SPIR-V 산출물의 성능, 장기 API 안정성, 그리고 xdsl/MLIR 변환 패스의 실제 품질은 찾지 못한다 (시간 추정도 측정이 아니다).
