# TypedPython: ownership inference 와 메모리 (Rust 대비) 조사

결론의 반영: `docs/design/typedpython.md` §4.3.5, 결함 #193 (2026-10-05). 메인테이너 결정: D1 예, D2 예(조건부), D3 예(조건부), D4 아니오, D5 결함 수정, D6 예(앱 배포 빌드만), D7 예, D8 지금은 아니오. 측정에 쓴 스크립트와 원자료(`.tmp/research/`)는 커밋하지 않았다.

날짜 2026-10-05. 태그: [V] 가져온 URL 또는 실행한 명령(근거 표기), [R] 로컬 파일을 읽음, [I] 추론.
측정 환경: Apple Silicon macOS (Darwin 25.5.0), CPython 3.14.7 (GIL), 3.14.7t, 3.15.0rc1 (GIL), 3.15.0rc1t.
설치는 `uv python install` 로 .tmp/research/pyinst 에 했다. 모든 스크립트와 원자료는 .tmp/research/ 아래에 있다
(measure.py, measure.out, rssdelta.py, rssdelta.out, rss.out, cext/, rs/).

## 0. 요약

1. 소유권 추론이 줄이는 것은 **시간**(refcount 연산, 할당)이고, 장기 상주 데이터의 **크기**는 거의 줄이지 못한다.
   장기 메모리는 객체 표현(박싱된 float 32 B, free-threaded 에서는 헤더 32 B)이 정한다 (B.1 표).
2. N-11 클래스를 non-GC 로 만들면 GIL 빌드에서 객체당 16 B(48 → 32 B)를 얻는다 [V cext/t.out]. 그러나
   **free-threaded 3.15t 에서는 0 B** 다 (GC 비트가 ob_gc_bits 로 헤더에 들어가 있어서, 48 == 48) [V cext/t.out].
   우리 대상은 3.15t 뿐이므로 non-GC 의 메모리 이득은 사실상 없고, 얻는 것은 GC 추적·순회 시간뿐이다.
3. 큰 이득은 **float/int 슬롯을 객체 안에 unbox** 하는 것이다 (두 float 필드 객체 157 B → 약 48 B, 3.3x, 3.15t 기준).
   그러나 이는 `a.x is a.x` 를 거짓으로 만들고 [V], `PyType_FromSpec` 으로 컴파일러가 타입을 직접 만들어야 한다.
   둘 다 사용자 결정이 필요하다.
4. **free-threaded 에서는 N-12 의 "borrowed 읽기" 근거가 무너진다** (A.2 (4)). 현재 문서는 "GIL 을 놓지 않으므로
   미지 코드 호출이 없으면 슬롯이 안 바뀐다" 에 기대는데, 3.15t 에서는 다른 스레드가 동시에 슬롯을 바꾼다. [I]
5. 설치 부작용: `uv python install` 이 `~/.local/bin/python3.14t`, `python3.15t` 심볼릭 링크 두 개를 저장소 밖에
   만들었다 (규칙 2 위반, 의도하지 않음). 사용자 승인으로 2026-10-05 에 지웠다. 다음 조사는 `uv python install` 대신 저장소 안 venv 만 쓴다.

## Part A. 소유권 추론 ("GC 를 스마트 포인터로")

### A.1 선행 사례 조사

| 시스템 | 메커니즘 | 언어에 요구하는 것 | 보고된 수치 | 근거 |
|---|---|---|---|---|
| Koka, Perceus | 정확한(precise) RC: 마지막 사용 직후 drop, 소유 참조를 이동하고 나머지만 dup. drop specialization, reuse analysis(해제될 셀을 같은 크기의 새 할당에 재사용), FBIP | 순수 함수형. 불변 데이터, 즉시 평가라서 순환이 없고 순환이 생길 수 있는 곳(ref)이 정적으로 알려져 있음 (논문 2.7.4). 타입과 효과가 추적됨 | 할당 집약 벤치에서 OCaml, Haskell, Swift, Java 와 비교해 "outstanding"; 트리 삽입(rbtree)에서 순수 함수형 Koka 가 in-place C++ std::map 의 10% 이내. no-opt 대비 reuse 로 메모리 8% 적음(rbtree 계열) | [V] https://www.microsoft.com/en-us/research/wp-content/uploads/2020/11/perceus-tr-v1.pdf (pdftotext 로 읽음: perceus.txt 줄 63 부터, 841, 1141) |
| Lean 4 | 소유(owned)/빌림(borrowed) 파라미터, 빌림 추론 휴리스틱, reset/reuse(공유되지 않았으면 제자리 재사용), 값에 single-threaded/multi-threaded/persistent 태그 | 순수 함수형, 즉시 평가 | 기준 대비 상대 시간 (ablation, lean.txt 표 6): `-borrow` 를 끄면 binarytrees 약 1.14x, deriv 1.16x 느림; `-reuse` 를 끄면 const_fold 1.64x, rbmap 3.23x; `-ST`(원자 RC 강제) 는 binarytrees 1.22x ~ rbmap 1.71x. 단 표 숫자가 PDF 추출에서 일부 깨져 소수 둘째 자리는 신뢰하지 않는다 | [V] https://arxiv.org/abs/1908.05647 (PDF https://arxiv.org/pdf/1908.05647 를 읽음) |
| Lobster | 할당마다 단일 소유자(보통 처음 대입된 변수, 필드, 벡터 원소)를 고르고 이후 사용을 가능한 한 borrow 로 만든다. 다른 곳에서 소유하려 할 때만 증가를 삽입. 런타임 RC 는 폴백으로 남음 | 정적 타입. 프로그래머 개입은 대체로 불필요 | "런타임 refcount 연산의 약 95% 제거" | [V] https://aardappel.github.io/lobster/memory_management.html |
| Nim ARC/ORC | 소멸자와 move 기반. 정적 제어 흐름 분석으로 "복사원이 이후 쓰이지 않으면 move", cursor 추론으로 복사 대신 별칭. ORC 는 ARC 위에 순환 수집기(trial deletion). 순환이 불가능한 타입은 수집기가 간선으로 보지 않음, `{.acyclic.}` 로 프로그래머가 보증 가능, 거짓 보증은 누수만 일으킴 | 정적 타입, 값 의미론 | 수치 없음 (문서와 블로그에 벤치마크가 없다) | [V] https://nim-lang.org/docs/destructors.html (move, cursor), 순환 부분은 검색 결과 요약: https://nim-lang.org/docs/mm.html (검색 결과 목록에서 확인, 해당 페이지를 직접 내려받지는 않음. 2차 확인 필요) |
| Swift ARC, OSSA | SIL 에서 값에 ownership 종류(owned / guaranteed / none / unowned)를 부여해 retain/release 쌍을 최적화기가 안전하게 제거. 변수 수명 전략 3종(strict, lexical, optimized) | 값 타입 위주, 클래스는 RC. 순환은 weak/unowned 를 프로그래머가 써야 함 | 문서에 수치 없음. Lean 논문 표에서 Swift 5.1.1 은 binarytrees 에서 Lean 의 약 5.4x 시간, 59% 가 GC(RC) 시간으로 표기됨 | [V] https://github.com/swiftlang/swift/blob/main/docs/SIL/Ownership.md (ownership 종류), 수치는 [V] lean.txt 의 비교 표 |
| Mojo | 인자 규약: 기본은 불변 참조(read), `mut` 가변 참조, `var` 소유권 이전(`^` 로 명시 이동), `out`/`deinit` 특수. ASAP 소멸: 마지막 사용 직후 소멸(스코프 끝이 아님), origin 이 참조의 수명을 추적 | 소유권을 시그니처에 **직접 적는다** (추론이 아니라 선언) | 수치 없음 | [V] https://mojolang.org/docs/manual/values/ownership , https://mojolang.org/docs/manual/values/lifetimes |

읽은 결과 공통점 [I]: 이득이 큰 시스템(Koka, Lean, Lobster)은 **불변 데이터 또는 단일 소유자 가정** 위에 있다.
파이썬 객체는 변경 가능하고 별칭이 자유로워서 (1) borrowed 로 증명할 수 있는 범위가 좁고, (2) 순환을 정적으로 배제할 수 없다.
Mojo 처럼 소유권을 선언하게 하는 길은 "새 문법 없음" 에 막힌다.

### A.2 우리 설계에 사상

#### (1) 컴파일된 함수 사이의 owned/borrowed 파라미터와 반환 추론

- **진입 규약.** 인터프리터에서 들어오는 인자는 vectorcall 규약상 호출자가 소유한다. 즉 컴파일된 함수 입구에서는
  모든 객체 파라미터가 borrowed 다. 그러므로 "owned 로 받는다" 는 컴파일된 함수끼리의 내부 ABI 에서만 의미가 있다. [I]
- **추론 규칙(Lean 식).** 파라미터는 borrowed 로 시작하고, 함수 안에서 (a) 슬롯·전역·컨테이너에 저장되거나 (b) 반환되거나
  (c) owned 파라미터 자리로 넘겨질 때만 owned 로 승격한다. 승격 지점에서만 INCREF 를 한 번 낸다. 반환은 항상 owned (새 참조)로
  둔다. borrowed 반환은 수명 추적 없이는 증명할 수 없어 하지 않는다 (Lean 도 반환은 owned). [I]
- **현재 설계와의 관계.** 4.3.4 나 절의 "빌린 읽기" 는 바로 이 규칙의 특수 경우다 (`check(node.left)`: 부모가 소유되고 callee 가
  슬롯을 쓰지 않으면 무연산). IR 의 N-9 소유 규칙(OBJ 참조는 하나의 소유 규칙)과 순수성 분석이 이미 필요한 정보를 갖고 있다. [R]
- **기대 효과.** Lobster 95%, Lean ablation 1.14 ~ 1.16x 는 **함수형 언어에서 RC 가 지배 비용인 경우**다. 우리는 refcount 쌍이
  3 ~ 4 ns (7절 R-1 측정)인 반면 객체 할당과 해제는 이보다 크다 (바이너리 트리의 1.7x 격차가 할당 때문이라는 #125 가설이
  있고 아직 프로파일 전이다 [R 설계 문서 4.3.4]). 따라서 refcount 생략은 가상 객체·영역 할당보다 **2차적**이라고 본다. [I]
  이 순서 판단은 측정으로 확인하지 못했다.
- **abi3t 의 영향.** 3.15 헤더에서 abi3t 는 `Py_INCREF`/`Py_DECREF`/`Py_REFCNT` 를 함수 호출로 만든다 [V src/refcount.h.main 줄
  101, 159, 260, 330 의 주석]. 그래서 쌍 하나를 빼면 호출 둘을 아낀다. 단 limited API(0x030c) 로 GIL 3.14 에서 측정한 쌍 비용은
  4.12 ns 로 인라인 3.96 ns 와 큰 차이가 없었다 [V cext/rc.out]. abi3t + 3.15t 조합은 컴파일하지 않았다 (측정 안 함).
- **관측성.** refcount 는 `sys.getrefcount` 와 미지 코드 호출에서만 보인다. 미지 호출 앞에서 쌍을 복원하면 관측 불가. [R 4.3.4 나]
  따라서 이 추론 자체는 **사용자 결정 없이** 동일성 규칙 안에서 구현 가능하다 (단 A.2 (4)의 free-threaded 조건 아래에서).

#### (2) N-11 클래스가 비순환임을 증명하고 `Py_TPFLAGS_HAVE_GC` 없이 만들기

사실 확인(전부 실행):

| 항목 | GIL 3.14.7 | 3.14.7t | 3.15.0rc1t | 근거 |
|---|---|---|---|---|
| 파이썬 `class S: __slots__=("x","y")` 인스턴스 `gc.is_tracked` | True | True | True | [V cext/t.out] |
| `__slots__ = ()` 빈 클래스 인스턴스 `gc.is_tracked` | True | True | True | [V cext/t.out, S0] |
| `PyType_FromSpec` 으로 만든 non-GC 타입 인스턴스 `gc.is_tracked` | False | False | False | [V cext/t.out, NonGcObj] |
| 그 인스턴스 `gc.get_objects()` 에 나타남 | 아니오 | 아니오 | 아니오 | [V] |
| `sys.getsizeof` : 파이썬 클래스(슬롯 2) / non-GC 타입 | 48 / 32 | 48 / 48 | 48 / 48 | [V] |
| 슬롯 2개 non-GC 타입에서 자기참조 1000개를 `gc.collect()` 후 dealloc 횟수 | 0 (누수) | 0 | 0 | [V]; GC 타입은 1000 |

- **파이썬 `class` 문으로는 non-GC 가 불가능하다.** `type_new_alloc` 이 모든 힙 타입에 `Py_TPFLAGS_HAVE_GC` 를 건다
  [R src/typeobject.c.314 줄 4183 ~ 4199, 주석 "All heap types need GC"]. 실제로 빈 슬롯 클래스도 추적된다 (위 표). [V]
- **따라서 컴파일러가 타입을 `PyType_FromSpec`(또는 `PyType_FromMetaclass`)로 직접 만들어야 한다.** 스펙이 주는 플래그가 그대로 쓰여
  `HAVE_GC` 를 뺄 수 있음을 실험으로 확인했다 [V cext/ngc.c, t.out]. 문서도 GC 플래그가 켜지는 조건을
  "순환에 참여할 수 있는 참조를 소유하거나 traverse/clear 를 구현할 때" 로 설명한다 [V https://docs.python.org/3.14/c-api/typeobj.html].
- **뒤따르는 관측 차이.** 컴파일러가 만든 타입은 클래스 사전에 `__new__` 가 더 있고 `__slots__` 속성이 없다 (`hasattr(T,"__slots__")`
  False) [V]. `__flags__`, `__basicsize__`, `sys.getsizeof`(GIL 에서 16 B 작음), `gc.is_tracked`, `gc.get_objects` 가 파이썬 클래스와
  다르다. `__slots__`·`__init__`·`__module__`·`__qualname__`·`__doc__` 를 복사해 대부분은 맞출 수 있지만 `gc` 관련 네 항목은 **정의상**
  달라진다. 컴파일된 모듈이 소스를 먼저 실행해 만든 원래 클래스와 이 타입을 어떻게 바꿔치기할지 (전역 이름 교체, deopt 용 인터프리터
  본문이 쓰는 클래스) 도 설계 문제다. [I]
- **비순환 증명의 한계(핵심).**
  - 주석의 타입은 런타임에 강제되지 않는다. 슬롯이 `PyObject*` 이면 인터프리터 쪽 코드가 `n.next = n` 처럼 무엇이든 넣을 수 있다.
    non-GC 타입 안의 순환은 영원히 수집되지 않고 (위 표 마지막 행), **다른 객체의 `__del__`/weakref 콜백도 영원히 안 돈다**. 이는 GC 시점
    (결정 (b))이 아니라 **수집 자체가 없는** 것이라 동일성 규칙 밖으로 나가는지 별도 판단이 필요하다. [I]
  - `Node(left: Node, right: Node)` 처럼 타입이 자기 자신을 닫는 재귀 클래스는 타입 그래프에서 이미 순환 가능하므로 증명 대상이 아니다
    (binary-trees 의 Node 는 안 된다). 비순환으로 증명되는 것은 필드 타입이 float/int/bool/str/None 이거나, 다른 비순환 N-11 클래스의
    DAG 인 클래스뿐이다. [I]
  - 그 경우도 `PyObject*` 슬롯에는 런타임 타입 위반을 막는 setter 가 없다. 안전하려면 슬롯이 **참조를 못 담는** 표현(unbox 된 double/i64)이거나
    저장 시 정확한 타입 검사를 해야 한다. 검사하면 CPython 이라면 성공했을 잘못된 타입 대입이 예외가 된다 (결정 필요, B.5 D3).
  - 서브클래스: `BASETYPE` 를 켜고 파이썬에서 상속하면 서브타입은 GC 가 되지만 기반 타입의 `tp_traverse` 가 없어 기반 슬롯의 참조를 순회하지 못한다.
    그래서 non-GC 타입은 final 이어야 하고 (`class Sub(Node)` 가 CPython 과 달리 `TypeError`), 이것도 관측 차이다. [I, 실험 안 함]
- **절약 추정.** GIL: 객체당 16 B (슬롯 2개 객체 48 → 32 B, -33%) [V]. 3.15t: 0 B [V]. 시간: GC 할당 카운터·추적·untrack 이 사라진다 (측정 안 함).

#### (3) refcount 1 일 때 제자리 재사용(Perceus)과 관측성

- **가능한 곳.** 컴파일된 함수가 **직접 소유한** (자기가 만들었거나 owned 로 돌려받은) N-11 객체가 죽는 지점 바로 뒤에 같은 클래스 객체를
  만들 때 (루프에서 `n = Node(...)` 를 반복 대입하는 형태). 인자로 들어온 객체는 borrowed 이므로 `Py_REFCNT == 1` 이어도 재사용하면 안 된다
  (호출자의 지역 변수가 그 하나를 쥐고 있다). [I]
- **CPython 3.14 의 우연한 해석 차이.** 인터프리터의 stackref borrow(LOAD_FAST_BORROW) 때문에 인터프리터 쪽 refcount 는 의미상 참조 수보다 작을 수 있다.
  우리는 공개 API 의 `Py_REFCNT` 만 쓰므로 "내가 소유한 참조가 유일" 만 쓰면 된다. [I, 이 부분 문서 확인 안 함: whatsnew 3.14 에는 해당 서술이 없었다 [V]]
- **free-threaded 의 유일성 검사.** 내부 판정은 `owned by current thread && ob_ref_local == 1 && ob_ref_shared == 0` [R src/pycore_object.h.314 줄 184 ~ 196].
  공개 형태 `PyUnstable_Object_IsUniquelyReferenced` 는 비 stable ABI 헤더(cpython/object.h)에 있다 [R src/object.h.314 줄 493]. abi3t 에서는 쓸 수 없고,
  쓰려면 "내가 소유한 유일 참조" 를 컴파일러 증명으로 보장하고 `Py_REFCNT(o) == 1` 을 보조 확인으로 쓴다. [I]
- **관측 지점(전부 [I]).**
  1. `id()`: 같은 주소가 다시 나오지만 CPython 도 해제 직후 할당에서 같은 주소를 자주 재사용하므로 유효한 실행이다.
  2. `gc.get_objects()` 순서와 세대 소속: 재사용 객체는 옛 위치와 세대에 남는다 (새 할당은 젊은 세대 끝). GC 시점 결정 (b) 와 같은 범주로 볼 수 있으나 `get_objects(generation=0)` 은 직접 관측 가능.
  3. `tracemalloc.get_object_traceback`: 재사용 객체는 이전 할당 위치를 보고한다.
  4. `__del__`/weakref 클래스: 이미 N-12 대상에서 제외됨.
  5. 자식 refcount: 슬롯 덮어쓰기에서 자식 decref 는 원래 해제와 같은 시점에 한다.
- **절약.** 할당·해제 호출과 0 채우기 생략. 메모리 크기에는 영향 없음. Lean ablation 에서 reuse 가 const_fold 1.64x, rbmap 3.23x [V] 이지만 이는 불변 함수형 트리 재구성 패턴이고, 파이썬의 변경 가능 객체에는 같은 비율이 나온다는 근거가 없다. [I]

#### (4) free-threaded 3.14t/3.15t

- **헤더 배치** [V src/object.h.top314 줄 152 ~ 163]:

      uintptr_t ob_tid;        // 소유 스레드 id (GC 와 trashcan 이 연결 리스트·gc_refs 로도 재사용)
      uint16_t  ob_flags;
      PyMutex   ob_mutex;      // 객체별 락
      uint8_t   ob_gc_bits;    // GC 상태 (PyGC_Head 를 대체)
      uint32_t  ob_ref_local;  // 소유 스레드의 비원자 카운트
      Py_ssize_t ob_ref_shared;// 다른 스레드의 원자 카운트 (+ 상태 비트)
      PyTypeObject *ob_type;

  GIL 빌드의 16 B 헤더(+ GC 객체는 앞에 16 B GC 헤더)에 비해 free-threaded 는 32 B 헤더이고 GC 헤더가 없다. 측정에서 float 24 → 40 B, `object()` 16 → 32 B
  [V measure.out]. 그래서 GC 객체는 크기가 같고 (48 == 48), 비 GC 객체(int, float, non-GC 타입)는 free-threaded 에서 커진다.
- **biased refcount.** 소유 스레드는 비원자 `ob_ref_local`, 다른 스레드는 원자 `ob_ref_shared` [V PEP 703 https://peps.python.org/pep-0703/].
  측정(INCREF+DECREF 한 쌍, 3.15t): 생성한 스레드 소유 객체 3.19 ns, **다른 스레드가 만들고 끝난 객체 14.24 ns**, 불멸(None) 0.63 ns.
  GIL 3.14: 3.96 ns, 불멸 0.35 ns [V cext/rc.out, 로드 평균 약 2, N=5천만, 3회 중 최소]. 즉 스레드 사이를 오가는 객체에서는 쌍 하나가 4.5배 비싸다.
- **deferred refcount.** 최상위 함수, 코드 객체, 모듈, 메서드처럼 여러 스레드가 자주 접근하는 객체는 스택 push/pop 에서 RC 를 건너뛰고 GC 때 참 카운트를 계산한다
  [V PEP 703]. N-11 인스턴스에 적용 가능한지는 확인하지 못했다.
- **immortalization.** `ob_ref_local == UINT32_MAX` 이면 INCREF/DECREF 는 무연산 [V PEP 703, src/refcount.h.314 줄 76, 110]. 할당기는 mimalloc [V PEP 703].
- **단일 스레드 오버헤드.** 3.14 whatsnew: free-threaded 의 단일 스레드 페널티 약 5 ~ 10% [V https://docs.python.org/3.14/whatsnew/3.14.html]. PEP 703 은 6%(Skylake) ~ 5%(Zen 3).
- **소유권 추론이 free-threaded 에서 아끼는 것.**
  - 공유 객체(스레드를 건너는 객체)를 borrowed 파라미터로 받으면 원자 연산 쌍(14 ns) 전체를 없앤다. 가장 큰 이득이 나는 자리다. [I, 측정 14.24 vs 3.19 에 근거]
  - 스레드 로컬 객체는 쌍이 3.2 ns 라 이득이 GIL 빌드와 비슷하다.
  - 불멸 객체(None, 작은 정수, 인턴 문자열)는 이미 0.6 ns.
- **중요한 위험.** 현재 설계의 borrowed 읽기 근거는 "컴파일된 코드는 GIL 을 놓지 않으므로 미지 코드 호출이 없으면 슬롯이 안 바뀐다" [R 4.3.4 나]. free-threaded
  에서는 GIL 이 없으므로 다른 스레드가 `node.left = None` 을 **동시에** 실행해 borrowed 로 쥔 자식을 해제할 수 있다 (use-after-free). 따라서 3.15t 에서
  (a) 호출자가 쥐고 있는 파라미터 자체는 여전히 borrowed 가능, (b) 공유될 수 있는 객체의 **필드 읽기**는 실제 참조 증가(소유 스레드면 3.2 ns)나 임계구역이 필요하고,
  (c) borrowed 필드 읽기는 "탈출하지 않은 스레드 로컬 객체"(가상 객체, 영역 객체, 이 호출에서 만들고 아직 저장하지 않은 객체)에만 허용된다. [I]
  같은 이유로 N-12 가상 객체와 영역은 free-threaded 에서도 안전하지만(스레드 로컬이라) 현재 문서의 "다른 스레드는 GIL 때문에 그 사이를 보지 못한다" 문장은 3.15t 에서 거짓이다.
  문서 4.3.4 나 의 마지막 문단("free-threaded 빌드는 범위 밖이다")과 이번 제약("3.15t 전용")이 충돌하므로 갱신이 필요하다.

## Part B. 메모리: CPython 대 Rust

### B.1 측정표

모든 수치는 요소(또는 객체) 하나당 바이트. "요청" 은 tracemalloc 이 본 파이썬 할당기 요청 바이트에서 리스트 슬롯 8 B 를 뺀 값, "RSS" 는 100만 개(dict 는 10만 개)를 만든 뒤
maxrss 증가를 개수로 나눈 값으로 **리스트 슬롯 8 B 포함** 이다 (측정 방식이 달라 같은 줄이어도 다른 열은 직접 비교하지 않는다). 소스: measure.out, rssdelta.out.

| 항목 | getsizeof 3.14 GIL / 3.14t | 요청 3.14 / 3.14t / 3.15t | RSS 3.14 / 3.14t / 3.15t | Rust |
|---|---|---|---|---|
| int (고유, 30비트 초과) | 28 / 44 | 32 / 48 / 48 | 42.4 / 64.2 / 64.8 (리스트 `[int]` 원소당) | i64 = 8 |
| float | 24 / 40 | 24 / 40 / 40 | 42.4 / 64.2 / 64.7 (리스트 `[float]` 원소당) | f64 = 8 |
| `object()` | 16 / 32 | 16 / 32 / 32 | | `()` 0, `Box<u8>` 힙 최소 16 (할당기 단위, 측정 안 함) |
| `__slots__` 2 float 필드 클래스 (필드 값 float 제외) | 48 / 48 | 48 / 48 / 48 | float 2개 포함 122.6 / 157.5 / 157.3 | struct{f64,f64} = 16 (Vec 안에 인라인), `Vec<Box<P>>` 원소당 24 [V rs/m] |
| 일반 클래스 2 필드 (필드 값 제외, 인라인 values) | 48 (+dict 288 가상) / 48 | 88 / 88 / 88 | float 2개 포함 170.9 / 204.2 / 203.9 | 16 |
| `list` 100만 int, 원소당 | getsizeof 8.4 (포인터만) | 40.4 / 56.4 / 56.4 (슬롯 포함) | | `Vec<i64>` 8 [V rs/m] |
| dict 10만 `str -> int`, 항목당 | getsizeof 38.4 (테이블만) | 120.5 / 152.5 / 152.5 | 171.2 / 170.4 / 171.2 | `HashMap<String,i64>` 55.3 요청 바이트 [V rs/m] |
| 유휴 인터프리터 `python -c pass` maxrss | | | 16.3 MiB (16656 KiB) / 17.3 MiB (17744) / 3.15: 16.6 MiB (16976) / 3.15t: 18.0 MiB (18400) | 빈 `main` 1.4 MiB (1440 KiB) |
| 같은 명령의 peak memory footprint | | | 6.3 MiB / 7.8 MiB / 6.4 MiB / 8.2 MiB | 측정 안 함 |

- **GC 헤더 포함 여부.** getsizeof 는 GIL 빌드에서 GC 헤더 16 B 를 더해 보고한다: 슬롯 클래스 48 = 헤더 16 + GC 헤더 16 + 슬롯 16, 같은 레이아웃의 non-GC 타입은 32 [V cext/t.out].
  free-threaded 는 둘 다 48.
- **Rust 쪽 수치 출처.** 위 Rust 값은 규칙 계산이 아니라 `rs/m.rs` 를 `rustc -O` 로 컴파일해 전역 할당기로 요청 바이트를 센 값이다 [V `rs/m` 실행].
  `HashMap<String,i64>` 의 capacity 는 114688 로 출력되었다. hashbrown 은 버킷당 제어 바이트 1 B 를 쓰고 적재율이 7/8 이므로 131072 버킷 x (String 24 + i64 8 + 1) 약 4.3 MB 이고,
  키 문자열 9 B 가 따로 힙에 있어 항목당 약 55 B 가 나온다 [V README 줄 35 "only 1 byte of overhead per entry instead of 8" https://github.com/rust-lang/hashbrown; 계산은 [I]].
  실제 RSS 는 힙 할당기 단위(16 B)로 올림되어 이보다 크다 (측정 안 함).
- **배율(위 표에서 계산).** float 리스트 원소: GIL 약 5x (40/8), 3.15t 약 7 ~ 8x (56 ~ 64/8). 두 float 필드 객체: RSS 기준 GIL 7.7x (122.6/16), 3.15t 9.8x (157.3/16);
  Rust 가 박싱해도(24) 5 ~ 6.6x. dict 항목: GIL 2.2x (120.5/55.3), 3.15t 2.8x (152.5/55.3). 유휴: 약 12x (16.3/1.4 MiB).
- **측정의 한계.** RSS 증가는 `ru_maxrss` 기반이라 단조 증가 값이다. 측정은 한 번에 하나씩 했고 로드 평균은 약 2 였다. 분산(반복)은 유휴 RSS 3회만 기록했다(거의 동일).
  `-I -S -OO` 실행에서 3.14 가 기준보다 큰 18016 KiB 였던 것은 최적화 레벨 2 의 pyc 가 없어 소스를 컴파일했기 때문일 것으로 [I] 보며 확인하지 않았다.

### B.2 현재 설계가 줄이는 것과 줄이지 못하는 것

| 현재 설계 요소 | 줄이는 것 | 줄이지 못하는 것 |
|---|---|---|
| 가상 객체 + 영역 (N-12) | 컴파일 구간 안에서만 사는 임시 객체의 할당과 해제 전체 (객체 하나당 48 B 와 할당 비용) | 컨테이너·전역·필드에 저장되는 장기 객체, 인터프리터로 넘어가는 객체 (실체화되면 위 표 그대로) |
| unbox 된 i64/f64 지역 변수 | 지역 스칼라의 박싱 (float 한 개 32 B, 3.15t 40 ~ 48 B) | 슬롯, 리스트, dict 에 저장되는 값은 저장 시점에 박싱 |
| `list[float]`/`list[int]` 네이티브 배열 | 컴파일 구간 안의 복사본 | 인터프리터가 쥔 `list` 자체 (원소당 40 ~ 56 B) |
| N-11 고정 레이아웃 | dict 288 B 가상 크기와 인라인 values 40 B 를 슬롯 16 B 로 | 슬롯 값은 여전히 `PyObject*` 라서 float 필드마다 박싱 32 ~ 48 B 가 따로 든다 |
| 소유권/refcount 생략 | 시간 | 크기 |
| 인터프리터 기본 비용 | 없음 | 16 ~ 18 MiB 유휴 RSS (Rust 1.4 MiB) |

### B.3 대안 표 (장기 메모리를 줄이면서 동일성 유지)

절약 추정은 모두 위 표의 숫자에서 계산한 값이며 구현해 보지 않았다.

| 번호 | 대안 | 절약 추정 (객체당, 3.15t / GIL) | 관측 위험 (동일성 규칙 대비) | 필요한 사용자 결정 |
|---|---|---|---|---|
| O1 | N-11 슬롯의 float/i64 unbox (엄격형). `Py_T_DOUBLE` 멤버 | 두 float 필드 객체 157 B 에서 48 B (3.3x) / 122.6 에서 40 B (3.1x) | **관측됨**: `a.x is a.x` False, `id(a.x)` 매 읽기마다 달라짐 [V cext/t.out DblObj]; `a.x = 1` 은 int 가 float 으로 바뀜(`type(a.x)`), 문자열 대입은 `TypeError`; i64 슬롯은 큰 정수·bool 을 못 담음; 타입이 컴파일러 생성 (아래 O5) | D1, D2, D3 |
| O2 | 같은 슬롯을 NaN-boxing 으로 (double 또는 `PyObject*` 태그). 비 float 값은 포인터로 저장 | O1 과 같은 크기 (슬롯은 8 B). GC 타입 유지 시 GIL 은 40 에서 56 B | `is`/`id` 만 관측됨 (float 읽기마다 새 객체). 타입 보존·임의 대입 가능. 단 `tp_traverse` 가 태그를 구분해야 하고 PyPy 의 방식과 같다 [I]. 3.15t 에서 매 읽기마다 새 float 할당(40 B 단명, 민감 경로에서 느려질 수 있음, 측정 안 함) | D1, D2 |
| O3 | N-11 클래스를 non-GC 로 (O1 과 결합할 때만 건전) | GIL 16 B (33%) / 3.15t 0 B [V] | `gc.is_tracked`, `gc.get_objects`, `sys.getsizeof`(GIL) 가 정의상 달라짐 [V]; final 이어야 함 [I]; `PyObject*` 슬롯과 결합하면 순환 누수로 건전성 상실 | D2, D3, D4 |
| O4 | 장기 데이터를 소스에서 `array.array('d')`/numpy 같은 압축 컨테이너로 쓰도록 안내 (엔진 변경 없음) | float 리스트 원소 56 ~ 64 B 에서 8 B (7 ~ 8x) | 엔진은 건드리지 않으므로 없음. 사용자 코드가 타입을 바꾸는 선택 (원소 `is` 동일성도 같이 바뀜) | 정책 결정 (검사기에서 `list[float]` 경고 여부) |
| O5 | `PyType_FromSpec` 로 컴파일러가 N-11 타입을 직접 생성 (O1 ~ O3 의 전제) | 자체로는 0 | `__dict__` 항목, `__flags__`, `__basicsize__`, `__slots__` 속성, 서브클래싱, pickle/copy/dataclass 연동, 인터프리터 폴백 때 쓰는 클래스와의 일치 | D2 |
| O6 | 인터프리터 기반 비용: `-S` (site 생략), frozen stdlib 유지(기본 on), 지연 import | `-S`: 16656 에서 15632 KiB (-1.0 MiB), 모듈 33 개에서 21 개 [V]; `-X frozen_modules=off` 는 21120 KiB (+4.4 MiB) 라서 frozen 을 반드시 켜 둘 것 [V] | 없음 (site 가 하는 일을 앱이 필요로 하지 않을 때). `sys.path` 에 site-packages 가 없어짐 | 임베딩 시 `-S` 허용 여부 |
| O7 | `PYTHONMALLOC=malloc` | 0 (유휴 +0.5 MiB, float/객체 RSS 동일) [V] | 없음 | 불필요 (효과 없음) |
| O8 | free-threaded 의 mimalloc 설정, 3.15t 대신 GIL 빌드 | 3.15t 의 float 40 B / 객체 헤더 32 B 의 추가 비용은 빌드 선택으로만 줄어듦 (GIL 은 float 24, int 28) | 빌드 선택은 제약 "3.15t 전용" 과 충돌 | 제약 변경 여부 (권고하지 않음) |
| O9 | 인터프리터 풋프린트: 유휴 16 ~ 18 MiB 는 libpython 매핑과 초기 모듈이 대부분 | 줄일 수 있는 것은 O6 정도 (-1 MiB), 나머지는 못 줄임 | | 없음 |

### B.4 권고

1. **소유권 추론(A.2 (1))은 구현해도 된다.** 관측 가능한 변화가 없고 사용자 결정이 필요 없다. 단, 3.15t 용 조건(A.2 (4)의 borrowed 필드 읽기 제한)을 설계에 먼저 반영한다. 크기에는 도움이 안 되고 시간에만 도움이 된다고 기대치를 낮춘다.
2. **non-GC 클래스(O3)는 단독으로는 하지 않는다.** 3.15t 에서 메모리 이득이 0 이고 (표 3.15t 열), 건전성이 슬롯 표현에 달려 있다. O1 과 한 묶음일 때만 의미가 있다.
3. **장기 메모리 이득의 본체는 O1/O2 (슬롯 unbox)와 O4 (압축 컨테이너)** 다. O4 는 결정이 거의 필요 없으므로 먼저 문서화·검사기 경고로 시작하고, O1/O2 는 `is`/`id` 결정 후 진행한다.
4. **O1 보다 O2 를 먼저 검토한다.** O2 는 관측 차이를 `is`/`id` 하나로 좁히고 O1 의 타입 강제 문제를 없앤다. 대가는 읽기마다 float 할당과 `tp_traverse` 복잡도다.
5. 가상 객체와 영역(N-12)이 장기 데이터에 안 닿는다는 점을 SPEC N-12 에 한 줄로 못박아 기대 관리를 한다.

### B.5 필요한 사용자 결정

- **D1** float/int 슬롯 값의 객체 동일성(`a.x is a.x`, `id(a.x)`)을 동일성 보증 범위에서 뺄 것인가. 근거 문장: 데이터 모델 3.1 은 불변 타입에서 "새 값을 계산하는 연산"이 기존 객체를 돌려줄 수 있다고만 허용한다 [V https://docs.python.org/3/reference/datamodel.html]. 속성 읽기는 "새 값 계산" 이 아니므로 읽을 때마다 새 float 를 주는 것은 엄밀히는 허용 밖이다. [I] GC 시점 (b) 결정과 같은 방식의 명시적 예외가 필요하다.
- **D2** 컴파일러가 N-11 타입을 `PyType_FromSpec` 로 직접 만들고 클래스 문 결과를 대체해도 되는가 (`__flags__`/`__basicsize__`/`gc` 관측 차이 허용 범위).
- **D3** 슬롯 타입 강제(`o.x = "s"` 가 `TypeError`) 허용 여부. O1/O3 에는 필요하고 O2 에는 불필요하다.
- **D4** non-GC 타입을 final 로 만들어 서브클래싱을 막아도 되는가 (O3 한정).
- **D5** 다른 스레드 동시 접근 가능성이 있는 3.15t 에서 N-12 4.3.4 나 의 borrowed 읽기 근거를 어떻게 바꿀 것인가 (스레드 로컬 증명만 허용 / 실제 incref 유지). 설계 문서 수정 승인.

## 7. 부록: 직접 측정한 보조 수치

- R-1 INCREF+DECREF 쌍(ns) [V cext/rc.out, cext/rc.c, N=5천만, 최소값]:

  | 빌드 | 소유 스레드 객체 | 다른 스레드가 만든 객체 | 불멸(None) |
  |---|---|---|---|
  | 3.14.7 GIL (인라인) | 3.96 | 3.95 | 0.35 |
  | 3.14.7 GIL (limited API 0x030c, 함수 호출) | 4.12 | 4.14 | 2.22 |
  | 3.14.7t | 3.21 | 14.20 | 0.63 |
  | 3.15.0rc1t | 3.19 | 14.24 | 0.63 |
- R-2 인터프리터 3.14 `-X frozen_modules=off`: 21120 KiB, `-S`: 15632 KiB, `-S -E`: 15632 KiB; 3.14t `-S -E`: 16880 KiB; 3.15t `-S -E`: 17424 KiB [V rss.out].
- 3.14.0 ~ 3.14.4 의 증분 GC 는 3.14.5 에서 되돌려졌다고 whatsnew 가 말한다 [V https://docs.python.org/3.14/whatsnew/3.14.html]. 이 연구는 3.14.7 과 3.15.0rc1 에서 했다.

## 8. 이 방법이 찾지 못하는 것 (Part C 포함)

단일 머신 마이크로벤치와 합성 클래스(float 2개)로 구조 크기만 쟀으므로, 실제 앱의 객체 구성·할당기 단편화·멀티스레드 경합 아래의 실효 절약과 (O2 의 읽기당 새 float 할당 같은) 시간 비용, 3.15t + abi3t 로 컴파일한 확장의 실제 refcount 호출 비용은 찾지 못한다.

## Part C. 인터프리터는 갱신 가능한 코드에만, 그 밖에는 최소 풋프린트

원자료: rssC.out, impcost.py, release.py, rel2.py, lazy.py/eager.py. 측정은 모두 같은 머신, 한 번에 하나씩, 3.14.7 / 3.14.7t / 3.15.0rc1 / 3.15.0rc1t.
주의: 새 설치 직후 첫 실행은 stdlib pyc 를 쓰느라 RSS 가 +8 ~ 9 MiB 크게 나온다 (3.14t 에서 -S -I +imports 가 38144 KiB 로 나왔다가 재측정에서 29280 KiB). 아래 표는 pyc 가 만들어진 뒤 정상 상태의 값이다.

### C.1 유휴와 소규모 앱의 RSS (maxrss KiB / peak memory footprint KiB)  [V rssC.out]

| 빌드 | `-c pass` | `-S -I -c pass` | `-S -I` + json,re,dataclasses,typing,asyncio | site 포함 + 같은 import | `-S -I -OO` + 같은 import |
|---|---|---|---|---|---|
| 3.14 GIL | 16640 / 6433 | 15632 / 5697 | 26160 / 14785 | 26624 / 15249 | 25808 / 14449 |
| 3.14t | 17744 / 7953 | 16880 / 7329 | 29392 / 18577 | 29472 / 18641 | 28576 / 17745 |
| 3.15 GIL | 16864 / 6449 | 15904 / 5793 | 27248 / 15649 | 27280 / 15649 | 26736 / 15137 |
| 3.15t | 18416 / 8353 | 17424 / 7617 | 30384 / 19201 | 30720 / 19505 | 29712 / 18529 |

- import 5개의 증가분: GIL 3.14 +10.5 MiB, 3.14t +12.5 MiB, 모듈 21개에서 147개(126개 추가). json 하나만: +2.3 MiB (GIL), re+typing+dataclasses: +5.1 MiB. [V]
- Rust 빈 `main` 은 1.4 MiB (Part B). `-S -I` 유휴는 그것의 약 11 ~ 12배다. [V]

### C.2 바닥(floor)을 이루는 것

| 구성 | 크기 | 근거 |
|---|---|---|
| 파이썬 힙(pymalloc)에 살아 있는 객체 | `-S -I` 유휴에 1,022,416 B, 블록 12,370 개, 1 MiB 아레나 2개(사용 가능 블록 261,808 B). GC 추적 객체 4,484 개, 대부분 `wrapper_descriptor` 1094, `method_descriptor` 797, `builtin_function_or_method` 694, `function` 672, `dict` 617 (내장 타입과 모듈의 메서드 표) | [V `sys._debugmallocstats`, `gc.get_objects`] |
| 물리 풋프린트(private/dirty) | GIL 5.7 MiB, 3.14t 7.3 MiB (`-S -I`). RSS 와의 차이 약 10 MiB 는 libpython(18.8 MB 파일)과 실행 파일·공유 캐시의 clean 파일 매핑 페이지다. 앱마다 중복되지 않는 공유 가능 페이지라서 "앱이 더하는 비용" 은 풋프린트 쪽이 맞다 | [V rssC.out, vmmap Physical footprint 8.6M(subprocess 를 import 한 측정이라 위 표보다 큼)]. 차이의 원인 서술은 [I] |
| 힙 밖 C 할당 | vmmap 상 MALLOC_SMALL+TINY 이 GIL 에서 약 2.7 MiB dirty (인터프리터 상태, 인턴 테이블 등으로 추정), 3.14t 에서는 0.6 MiB 이고 대신 풋프린트 합이 커짐 (mimalloc 이 VM 영역을 직접 씀) | [V vmmap -summary], 용도 추정은 [I] |
| free-threaded 추가분 | 유휴 +1.1 MiB RSS, +1.6 MiB 풋프린트. 객체 헤더 32 B 와 float 40 B 등 (Part B), 스레드별 mimalloc 힙 | [V], 스레드별 힙의 몫은 분리 측정하지 못함 [I] |
| import 한 모듈의 사전·코드 객체 | 5개 import 후 tracemalloc 으로 6.0 MiB (GIL) / 7.1 MiB (FT) 가 파이썬 할당기에 남음. 이 중 코드 객체 4,013 개의 `getsizeof` 합 1.67 MiB, 그 중 line table + exception table 0.69 MiB (코드 객체 크기의 41%). 나머지는 모듈 사전, 함수·클래스 객체, 상수, 확장 모듈(`_json`, `_asyncio` 등)의 C 상태 | [V impcost.py]. 나머지 분해는 [I] |

결론 [I]: 유휴 바닥(약 5.7 MiB 풋프린트)은 CPython 런타임이 정하고 앱이 줄일 수 있는 부분이 거의 없다. 앱이 영향을 줄 수 있는 쪽은 **무엇을 import 하느냐**(최대 +10 ~ 12 MiB)다.

### C.3 항목별 평가

| 항목 | 절약 추정 | 동일성 위험 | 노력 | 검증 상태 |
|---|---|---|---|---|
| 임베딩 초기화 최소화: `PyConfig_InitIsolatedConfig` + `site_import=0` + 명시적 `module_search_paths` | `-S`: RSS -1.0 MiB (16640 → 15632 KiB), 풋프린트 -0.7 MiB, 모듈 33 → 21 [V]. 격리 설정 자체는 환경 변수, argv, 사용자 site 를 무시 [V https://docs.python.org/3.14/c-api/init_config.html] | `sys.path` 에 site-packages 가 없다. `quit`/`exit`/`help` 내장이 없다. `sys.flags` 가 달라진다. 의존 패키지 경로를 우리가 구성해야 한다 (이미 Gradle 플러그인이 PYTHONHOME 을 스테이징하므로 자연스러움) | 낮음 | 절약 [V 측정], 위험 [I]. 같은 페이지에서 가져온 "최소 구성 예제" 코드는 `PyConfig` 와 `PyInitConfig_SetInt` 를 섞어 쓰는 것으로 읽혀 요약기의 합성일 수 있어 사용하지 않았다 |
| frozen stdlib / 최소 stdlib | 기본값 `frozen_modules` 켬이 이미 이득: 끄면 +4.4 MiB (16656 → 21120 KiB, 앞 측정). 추가 이득은 "안 쓰는 모듈 제외" 인데 RSS 는 import 한 만큼만 늘므로 디스크는 줄어도 RSS 는 import 안 한 모듈에서는 안 줄어든다 | import 하지 않을 모듈을 삭제하면 `ImportError` 가 달라진다. 닫힌 세계 증명이 있어야 함 | 중간 (이미 아티팩트 워커가 의존 그래프를 안다) | 켬/끔 차이 [V], 삭제의 RSS 이득이 거의 없다는 것은 [I] |
| PEP 810 명시적 lazy import | 3.15t 에서 다섯 모듈을 쓰지 않을 때 30272 → 17536 KiB (-12.7 MiB, -42%), 3.15 GIL 27328 → 16000 KiB. json 만 실제로 쓰면 20528 KiB [V lazy.py 등]. 3.15 에 실제로 들어갔다 [V PEP 810 상태 Final, Python-Version 3.15, 결정일 2025-11-03 https://peps.python.org/pep-0810/ ; whatsnew 3.15 가 `lazy import json` 와 `-X lazy_imports`, `PYTHON_LAZY_IMPORTS`, `sys.set_lazy_imports()` 를 서술 https://docs.python.org/3.15/whatsnew/3.15.html] | 사용자가 `lazy` 를 쓴 곳은 사용자 선택이라 위험 없음. 컴파일러가 임의로 lazy 로 바꾸면 import 부작용의 순서와 `ImportError` 발생 시점이 달라져 관측됨. 전역 모드(`-X lazy_imports`)는 같은 이유로 권하지 않음 | 낮음 (사용자 코드), 높음 (자동 변환) | 절약·상태 [V], 위험 [I]. 3.14 에는 없다 (우리는 3.15t 전용이라 무관) |
| docstring 제거(-OO), 줄 표 제거 | `-OO`: RSS -0.35 MiB (GIL), -0.8 MiB (3.14t) [V]. `-X no_debug_ranges` 를 더해도 RSS 변화가 관측되지 않았다 (26144 vs 25808 KiB, 3.14t 28848 vs 28576, 잡음 범위) [V]. line+exception tables 합 0.69 MiB 가 상한 | `-OO` 는 docstring 뿐 아니라 `assert` 문도 제거한다 (의미 변경, 관측됨). `__doc__` 이 None 이 되어 `dataclasses`, `argparse`, 일부 라이브러리가 달라진다. 줄 표를 지우면 traceback 의 줄 번호가 사라진다 (관측됨) | 낮음 | 절약 [V 소규모], 위험 [I]. 권고하지 않음 (이득이 작고 위험이 큼) |
| `gc.freeze` | 단일 프로세스 RSS 이득 없음. 목적은 `fork()` 후 copy-on-write 공유와 GC 순회 감소다 [V https://docs.python.org/3.14/library/gc.html]. free-threaded GC 는 메모리가 10% 늘지 않으면 수집을 건너뛴다 [V 같은 페이지] | `gc.get_freeze_count()`, `gc.get_objects()` 에 영향. 앱이 안 쓰면 없음 | 낮음 | [V 문서], 이득 없음 판단은 [I] |
| 해제한 메모리를 OS 에 반환 | **GIL (pymalloc)**: 100만 객체 해제 후 풋프린트 125.8M → 20.7M 로 돌아옴 (빈 아레나 해제) [V rel2.py]. 단 1% 만 흩어 남기면 아레나가 묶여 RSS 144 MiB 가 그대로 [V release.py]. **free-threaded (mimalloc)**: 같은 시험에서 158.7M → 158.9M, 즉 **반환되지 않음**. `MIMALLOC_PURGE_DELAY=0` 을 줘도 같음 (환경 변수를 이 빌드가 읽는지는 확인 못함) [V rel2.py, release.py]. libpython3.14t 는 `mi_collect` 류 심볼을 내보내지 않는다 [V nm -gU]. CPython 은 mimalloc 힙 수집을 QSBR 판정용 내부 경로에서만 부른다 [R src/obmalloc.c 줄 224] | 없음 (할당기 동작은 파이썬 의미가 아님) | FT: 높음. 직접 빌드한 CPython 에서 `mi_collect(true)` 를 노출하거나 `purge_delay` 를 설정해야 한다 (PBS 배포 바이너리로는 불가) | 측정 [V], 원인과 해법은 [I] |
| 컴파일 모듈이 deopt 용으로 들고 있는 소스·바이트코드의 지연 로드 | 코드 객체 평균 약 425 B (1.67 MiB / 4013), 그 41% 가 위치·예외 표 [V]. 컴파일 함수 1000 개면 약 0.4 MiB 로 바닥 대비 작다 [I]. 모듈 본문은 import 때 한 번 실행되고 그 코드 객체는 보관되지 않으므로 줄일 수 있는 것은 함수 코드 객체뿐이다 [I] | deopt 첫 호출이 느려진다. `__typedpython_interpreted__` 의 함수를 지연 생성 객체로 바꾸면 그것을 검사하는 코드가 다르게 본다 (드묾). FT 에서 지연 로드는 동기화가 필요하다 | 중간 | 절약 [I], 크기 통계 [V] |
| FastTrack: 갱신된 모듈은 인터프리터로, 건드리지 않은 모듈은 컴파일 상태로 | 모듈 단위는 N-10 증분 단위와 같아 구조적으로 맞는다 [R 설계 문서 4.3.2]. 그러나 컴파일 모듈이 소스를 먼저 실행해 네임스페이스를 만든다는 현재 설계 [R 4.3 "모듈 모양"] 때문에 컴파일된 모듈도 인터프리터와 소스(또는 pyc)를 import 때 쓴다. 인터프리터 자체는 컴파일 확장이 C API 와 파이썬 힙 위에 산다는 제약상 제거할 수 없다. 즉 바닥 5.7 MiB 풋프린트는 이 아이디어로 줄지 않는다 [I]. 줄일 수 있는 것은 "건드리지 않은 모듈의 인터프리터용 소스·코드 객체를 아예 안 싣는 것" 인데, 그러려면 컴파일러가 클래스·함수 정의를 C 초기화 코드로 내보내야 한다 | 클래스 생성 의미(메타클래스, `__init_subclass__`, 데코레이터 순서)를 C 로 재현해야 하므로 위험이 크다. 갱신 모듈과 컴파일 모듈 사이의 가정은 N-11 의 `tp_version_tag` 와 전역 바인딩 확인이 이미 일반 경로로 떨어뜨린다 [R 4.3.3] | 높음 | [I] 전부. 현재 코드가 실제로 그렇게 동작하는지는 확인하지 않았다 |

### C.4 권고 (Part C)

1. **먼저 할 것(위험 없음, 낮은 노력)**: 격리 구성과 `site_import=0` (약 1 MiB), `frozen_modules` 유지, 사용자 코드에서 `lazy import` 안내와 검사기 경고 (큰 라이브러리 import 가 12 MiB 까지 좌우).
2. **하지 않을 것**: `-OO` (assert 제거), 줄 표 제거, 컴파일러 자동 lazy 변환, `gc.freeze` (이득이 작거나 의미가 바뀐다).
3. **3.15t 에서 가장 큰 구조적 문제는 메모리 반환**이다. 피크 후 해제한 메모리가 돌아오지 않는다 (158.9M 유지). 장시간 실행 앱에는 피크 = 상주다. CPython 을 직접 빌드해 mimalloc 수집을 노출할 수 있는지가 열린 질문이다.
4. "인터프리터는 갱신 코드에만" 은 현재 제약(컴파일 확장이 CPython 힙 위)에서는 **풋프린트 바닥을 줄이지 못한다**. 얻는 것은 모듈 단위의 코드 객체와 소스 지연 로드 정도(수 MiB 이하)다.

### C.5 이 절의 사용자 결정

- **D6** 임베딩에서 `site` 를 끄고 `module_search_paths` 를 우리가 구성해도 되는가 (`quit`/`exit`/`help` 내장 부재 허용).
- **D7** 3.15t 용 CPython 을 직접 빌드(또는 패치)해 mimalloc 반환을 제어해도 되는가 (PBS 바이너리 의존 탈피).
- **D8** FastTrack 에서 컴파일 모듈의 인터프리터용 정의를 제거하는 설계(클래스·함수를 C 초기화로 내보냄)를 진행할 가치가 있는지.
