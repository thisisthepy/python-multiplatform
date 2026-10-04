# TypedPython 과 JAX 비교 (조사일 2026-10-04)

결론의 반영: `docs/design/typedpython.md` §5.10 (2026-10-04). 메인테이너 결정: §5 의 권고 2 는 "torch 가 JAX 만큼 성능을 내는 부분만 torch 에 맡기고 나머지는 목록으로 따로 판단" 으로 바뀌었고, 권고 4 는 "numpy 의존 없음, 순수 파이썬 참조" 로 정해졌다.

태그: [V] 가져온 URL 또는 실행한 명령, [R] 읽은 로컬 파일, [I] 추론. 웹 도구가 페이지를 요약해서 돌려주므로
[V] 항목은 원문 인용이 아니라 요약을 본 것이다. 기기에서 직접 재지 않았다.

## 1. 비교표

| 항목 | JAX (0.11.2, 2026-09-17 [V]) | TypedPython (설계안 [R]) |
|---|---|---|
| 프로그래밍 모델 | `jit` 가 tracer 로 함수를 jaxpr 로 변환. 순수 함수, 불변 배열 [V] | 일반 파이썬 의미를 보존. 컴파일은 IR 에서 증명, 실패하면 인터프리터에 남김 [R §4.3.1] |
| 파이썬 제어 흐름 | trace 시점에 평가. 값 의존 분기는 에러, `static_argnums` 는 재컴파일 [V] | 컴파일 구간에서도 CPython 과 같은 결과가 계약 [R §4.3.4] |
| 부작용 | `print`, list append 는 trace 때만 실행되고 사라짐 [V] | 보존 (같은 결과 원칙) [R] |
| 커널 | Pallas. GPU 는 Mosaic GPU (Hopper 이상), Triton 은 0.11.0 에서 deprecated, TPU 는 Mosaic [V] | `@kernel`, 타일 우선 + SIMT 폴백, Vulkan/Metal/WebGPU/CUDA 출력기 [R §5] |
| 커널의 CPU 실행 | `interpret=True`. 커널을 JAX 함수로 낮춰 grid 위 `scan` 을 `jit` 한 것 [V] | 커널이 수정 없이 CPython 에서 실행 [R §5.2] (설계, 미구현) |
| 모바일 GPU 커널 | Pallas 변경 기록에 Vulkan/Metal 없음 [V] | 1차 목표 (Vulkan, Metal) [R] |
| AOT | `jax.export` 로 StableHLO 직렬화. 내장 플랫폼 TPU, CPU, CUDA, ROCm [V] | 빌드 타임 AOT 가 전제 (iOS) [R §5.1] |
| 실행 런타임 | XLA/PJRT 또는 StableHLO 소비자 (IREE, Google AI Edge) [V] | torchnative 장치 계층 (ash/Vulkan, candle-metal) [R §5.6] |
| 전체 프로그램 융합 | XLA 가 jit 단위로 융합 [I, 일반 지식. 이번에 1차 출처를 확인하지 못함] | 없음. 그래프 캡처로 미룸 (§5.9) [R] |
| autodiff / vmap / sharding | 핵심 기능 [I, 일반 지식. 확인 못 함] | 없음 (설계에 언급 없음 [R]) |
| 정적 타입 | 배열 shape/dtype 은 `jaxtyping` 런타임 검사. 정적 체커는 `Array` 로만 취급 [V] | 빌드 기본 게이트, `Any` 유출 0 (Pyrefly) [R §4.2] |
| 자유 스레드 CPython | 3.14t, 3.15t 지원 표기, mac 포함 wheel [V 검색 요약] | 3.14t 데스크톱 opt-in, 컴파일 모듈은 GIL 재활성 [R INTENT, §4.3.4] |
| iOS/Android 휠 | 변경 기록에 언급 없음 [V]. jaxlib 휠은 Linux, macOS ARM64, Windows [V 검색 요약] | 임베드 대상 [R] |
| HF transformers | v5 에서 Flax/TF 클래스 제거, PyTorch 단일 백엔드 [V] | torch op 로 착지해 transformers 무수정 사용 [R §5.6] |

## 2. 우리 설계가 이기는 지점 (근거가 버티는 것만)

1. **모바일 GPU 가 1급 대상이다.** JAX 의 GPU 커널 경로는 Mosaic GPU 가 Hopper 이상 NVIDIA 로 한정되고, Triton
   은 deprecated 다 [V]. Pallas 변경 기록에 Vulkan/Metal 이 없다 [V]. 공식 Apple GPU 플러그인은 "experimental" 이고
   macOS 15.6 에서 `arange` 도 실패하는 이슈가 열려 있다 (jax-metal 0.1.1) [V]. 서드파티 metaljax 가 있으나
   비공식 beta 이고 mlx-lm/llama.cpp 에 1.1~1.9배 뒤진다고 스스로 적는다 [V]. Android 에 대해서는 이번 조사에서
   근거를 찾지 못했다 [I: JAX 공식 경로로는 없다고 보이나 증명은 아니다].
2. **iOS/Android 에서 실행되는 런타임이 있다는 점.** JAX 모델의 모바일 배포는 jax2tf 를 거쳐 TF SavedModel,
   LiteRT 로 가는 2단계 경로가 문서화돼 있고 jax2tf 는 experimental 이다 [V]. 임의의 Pallas 커널이 그 경로로
   가는지는 확인하지 못했다. 그리고 `jax.export` 문서는 안정성이 보장되는 custom call 이 일부뿐이고 나머지는
   안전 검사를 꺼야 직렬화된다고 한다 [V]. Pallas 커널은 custom call 로 내려가므로 [I] 이식 가능한 아티팩트가
   되기 어렵다고 추정한다.
3. **정적 타입 게이트는 JAX 에 대응물이 없다.** jaxtyping 은 의도적으로 정적 shape 검사를 하지 않고, 정적
   체커에는 `Array` 로 보인다. 런타임(trace 시점) 검사다 [V]. 우리의 "사용자 코드에 `Any` 없음" 보장은 다른
   종류의 보장이다 [I]. 다만 이것이 JAX 대비 우위인 것은 "빌드 실패" 라는 시점뿐이며, shape 오류를 정적으로
   잡는다는 뜻이 아니다 (우리도 못 한다).
4. **파이썬 의미를 바꾸지 않는다.** JAX 는 `jit` 안에서 값 의존 분기 에러, 부작용 소실, 인덱스 clamp (NumPy 는
   에러), list 입력 거부, 기본 32비트 정밀도 등 다른 의미로 동작한다 [V]. TypedPython 의 계약은 "어떤 유효한
   CPython 실행과 구별 불가" [R §4.3.4] 이므로 기존 파이썬 코드를 의미 변경 없이 점진 컴파일한다는 점이 다르다.
   다만 이것은 설계 약속이고, 커널 쪽(타일 연산의 CPU 참조 구현)은 아직 구현이 없다 [R].
5. **CPU 에서 "진짜 파이썬으로" 디버깅.** Pallas `interpret=True` 는 커널을 JAX 함수로 낮춘 `scan` 을 `jit`
   한 것이고, CPU 에서 Pallas 를 돌리는 유일한 방법이다 [V]. 커널 본문은 `Ref` 와 `pl.*` 연산을 쓰므로 JAX 없이는
   실행되지 않는다 [I]. `jax.debug.print` 같은 JAX 도구가 쓰인다 [V]. 우리 설계는 JAX 도 필요 없이 CPython 만으로
   돈다는 점에서 한 걸음 더 가깝다. 단, 그 "일반 파이썬 실행" 이 느리다는 비용은 둘 다 같다 [I].
6. **생태계 방향.** transformers v5 가 Flax/TF 를 제거하고 PyTorch 단일 백엔드가 됐다 [V]. torch op 로 착지하는
   우리 경로가 HF 생태계와 맞는다. JAX 쪽은 MaxText 등 별도 도구로 호환을 유지한다고 한다 [V].

## 3. JAX 가 이기는 지점과 따라잡으려면

| JAX 의 강점 | 우리 상태 | 격차를 메우려면 |
|---|---|---|
| XLA 전체 프로그램 융합 | 없음 (§5.9) [R] | torchnative 그래프 캡처 (torchnative #68). 단기에는 수작업 융합 커널 번들 |
| `grad`, `vmap`, `jit` 합성 | 설계 범위 밖 [R] | torch autograd 에 맡긴다고 명시. 커널은 forward/backward 를 사용자가 `torch.library` 로 등록 [I] |
| 샤딩, 다중 기기 (pjit, shard_map) | 없음 | 모바일/온디바이스 목표상 범위 밖으로 선언하는 것이 정직하다 [I] |
| 성숙도, TPU/Hopper 성능 | 코드 없음 (커널 단계 4부터) [R] | 데스크톱 CUDA 비교는 Triton/TileLang 기준 [R §5.8] |
| 타일 커널 DSL 의 검증된 파이프라이닝, warp specialization | 설계만 | Mosaic GPU 문서 (파이프라이닝, warp specialization, Blackwell matmul) [V] 를 참조 사례로 삼는다 |
| `jax.export` 의 호환 창 (후방 6개월, 전방 3주) [V] | 아티팩트 형식 미정 | 커널 번들에 IR 버전과 출력기 버전을 박는다 [I] |
| 자유 스레드 휠 | 3.14t 만 | JAX 는 3.14t, 3.15t 표기 [V]. 우리 MLIR 보조 휠은 3.15t 없음 [R §5.4] |

## 4. 우위가 불분명하거나 입증되지 않은 것

- **"런타임 코드 생성 없음" 은 JAX 대비 우위가 아닐 수 있다.** 이유는 `jax.export` 의 StableHLO 를 IREE 가 AOT
  컴파일해 모바일에 싣는 길이 이론상 있기 때문이다 [V: IREE 와 Google AI Edge 가 StableHLO 소비자]. 실제로 JAX
  모델이 IREE 로 iOS/Android GPU 에서 도는 사례와 성능은 확인하지 못했다. 우리 조사도 IREE 를 "SPIR-V 코드
  생성만 떼어 쓸 공식 경로 없음" 으로 기각했으나 [R §5.4], 그것은 커널 컴파일러로서의 평가이고 "모델을 통째로
  배포하는 경로" 로서는 JAX+IREE 가 경쟁자다 [I].
- **성능.** 둘 다 모바일에서 측정이 없다. llama.cpp/MLX 근접 목표는 안 [R §5.8]. metaljax 가 1.1~1.9배 뒤진다고
  밝힌 수치가 있어 [V], 우리가 "JAX 보다 빠르다" 고 주장할 근거는 지금 없다.
- **"Pythonic 하다".** JAX 도 NumPy API 이고 학습 곡선이 낮다. 의미 보존의 가치는 기존 코드 이식 시나리오
  에서만 나타난다 [I].
- **타입 게이트의 가치.** JAX 사용자가 이 게이트를 원하는지(특히 `jnp` 중심 코드는 `Array` 로 충분히 추론되는지)
  검증하지 않았다 [I]. Pyrefly 가 JAX 를 쓰는 대규모 코드베이스에서 쓰인다는 서술 [R §4.1] 은 JAX 코드가
  게이트를 통과한다는 뜻이 아니다.
- **Mosaic GPU 와의 직접 대결.** Hopper/Blackwell 한정이라 겹치는 기기가 없다. 우리 데스크톱 CUDA(PTX) 경로가
  JAX 보다 낫다는 주장은 근거가 없다 [I].
- **Pallas 의 Vulkan/Metal 부재** 는 "변경 기록에 없다" 까지만 확인했다. 실험 브랜치나 제3자 포크는 못 봤다 [V 한계].

## 5. 설계 변경 권고

1. **포지셔닝 문장을 바꾼다.** "JAX 보다 낫다" 가 아니라 "JAX 가 가지 않는 곳(모바일 Vulkan/Metal, 앱에 임베드된
   CPython, 의미 보존)" 으로 쓴다. 근거가 이 범위에서만 서기 때문이다 (§2.1~2.4).
2. **범위 밖 선언을 문서에 넣는다.** autodiff, vmap, 샤딩은 torch 에 위임하고 만들지 않는다고 §5 에 한 단락 적는다.
   사용자가 JAX 와 같은 것을 기대하지 않게 한다.
3. **`interpret=True` 대응 성질을 시험으로 박는다.** "커널이 JAX/torch 없이 CPython 만으로 도는가" 를 첫 조각 시험에
   넣는다. 이것이 Pallas 대비 구체적 차별점이므로 구현 전에 반증 가능해야 한다 (AGENTS.md §14).
4. **타일 연산의 CPU 참조 구현 의존성을 정한다.** numpy 를 쓰면 "의존 없는 CPython" 우위가 약해진다. 순수 파이썬
   참조를 쓸지, 배열 라이브러리를 허용할지 결정이 필요하다 [I].
5. **IREE 경로를 "모델 단위 대안" 으로 §5.4 에 한 줄 비교로 추가**한다. 기각 사유를 커널 컴파일러 관점과 모델 배포
   관점으로 나눠 적는다.
6. **JAX 사용자 이주 경로 검토(선택).** 필요하면 StableHLO 입력을 받는 것은 INTENT §1 범위를 넘으므로 제안하지
   않는다. 메인테이너가 정할 일이다.
7. **융합 격차를 "알려진 약점" 으로 표에 유지**하고 torchnative #68 완료 전에는 모델 전체 속도를 비교 대상으로
   삼지 않는다 (§5.9 와 같다).

## 6. 이 방법이 찾지 못하는 것

웹 요약 도구가 돌려준 문장에 의존했고(원문 대조 없음), 기기 실측, 비공개 Google 로드맵, 공식 문서에 없는 제3자
Vulkan/Metal Pallas 포크, 그리고 XLA 융합/autodiff/vmap 의 1차 출처 확인(이번엔 일반 지식으로만 적음)은 찾지
못한다.

## 출처 (가져온 것)

- https://docs.jax.dev/en/latest/changelog.html (0.11.2, Python 3.12 이상, 3.14t/3.15t, 모바일 언급 없음)
- https://docs.jax.dev/en/latest/pallas/CHANGELOG.html (Triton deprecated, Vulkan/Metal 없음)
- https://docs.jax.dev/en/latest/pallas/quickstart.html (Mosaic GPU, Hopper 이상, Triton 비권장, Ref)
- https://docs.jax.dev/en/latest/pallas/design/design.html (scan 기반 emulation)
- https://docs.jax.dev/en/latest/export/export.html , https://docs.jax.dev/en/latest/jax.export.html
- https://docs.jax.dev/en/latest/jit-compilation.html , .../notebooks/Common_Gotchas_in_JAX.html
- https://docs.jax.dev/en/latest/installation.html (Apple GPU experimental)
- https://github.com/jax-ml/jax/issues/34109 (jax-metal 실패)
- https://pypi.org/project/metaljax/ (비공식, 1.1~1.9배)
- https://docs.kidger.site/jaxtyping/faq/
- https://github.com/openxla/stablehlo/blob/main/README.md (IREE, Google AI Edge)
- https://developers.google.com/edge/litert/conversion/jax/overview (jax2tf 경로, 검색 결과 요약)
- 웹 검색 요약: transformers v5 Flax 제거, jaxlib 3.14t 휠, pallas interpret 파라미터
