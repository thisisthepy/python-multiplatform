# TypedPython: parallel execution of order-independent loops

Issue #151. SPEC item N-15. Status: design only; the implementation is planned separately. Maintainer decisions of 2026-10-25
are recorded in §12 and the body follows them.
INTENT §1.4 is the boundary: compiled code computes what CPython computes, and every execution of
compiled code is indistinguishable from some valid CPython execution (SPEC N-12). INTENT §1.4 and
N-1 ask for parallelism from free-threaded CPython; nothing here goes beyond that.

The issue's rule, restated: parallelise only loops whose result does not depend on iteration order,
so results stay bit-identical to CPython. This note makes "does not depend on iteration order"
checkable on the existing IR (`typedpython/ir.py`), and says where that rule gives no parallelism at all.

## 1. Summary of the decisions

1. The unit is one `ir.ForRange` statement. Workers are plain C threads that never touch the CPython
   API. The loop body is outlined into a function that calls no runtime helper needing a thread state.
2. **One failure rule for everything that is not a plain success:** if any worker hits an exception,
   a deopt, an overflow or an `INT` promotion, the whole region is abandoned, the state it wrote is
   restored, and the loop is rerun **sequentially from its start**. The sequential run is today's
   generated code, so the exception type, message, first failing iteration, partial writes and deopt
   behaviour are CPython's by construction. The parallel path never has to reproduce an error.
3. Restoring is possible only where the loop's writes are private until the region succeeds. That
   holds for `list` copy-in arrays (the Python list is untouched until write-back) and local arrays
   (snapshot). It does **not** hold for buffer arrays (`array.array`), which are written in place.
   **Decision 1: buffer loops stay sequential.** Only list and local-array loops are parallel.
4. Reductions: integer `+ * & | ^` may be chunked, and `min`/`max` only if chunked evaluation is exactly
   equal to Python's (§6, decision 6). **Float `+` and `*` reductions are excluded in v1** (§6,
   decision 4; a fastmath opt-in, #153, awaits the owner).
5. No persistent thread pool in v1: threads are created per region and joined before the call returns
   (§7, decision 3). Thread creation cost is measured together with the threshold.
6. A cost rule decides, at run time, whether a region is worth its fixed cost; below the threshold
   the sequential code runs (§8). The threshold is measured, not guessed here.
7. v1 parallelises only functions the user marks `@compiled(parallel=True)` (decision 5, #358). Widening to unmarked functions later is easy; narrowing is hard.

## 2. What exists today (the facts this design stands on)

All from `ir.py`, `runtime/API.md` and `runtime/tp_runtime.h`.

- `ForRange(var, start, stop, step, body)`: `step` a non-zero i64 constant; `var` is i64, not assigned
  in the body; bounds evaluated once. A `for ... in range` already runs in chunks of
  `TP_POLL_INTERVAL` (4096) iterations and polls the eval breaker once per chunk (#206).
- **Array parameters** (`ArrayParam`): a `list[float]`/`list[int]` is copied into a C array
  (`tp_f64_array`/`tp_i64_array`, with a per-element `dirty` byte), the function runs no user code
  (`verify/array-open`, #203), dirty elements are written back as new objects on every exit. On
  free-threaded builds the wrapper holds the list's `PyCriticalSection` (two lists at most) from
  before enter to after exit (#159). An exact `array.array` (`buffer="array"`) is not copied: a
  `Py_buffer` export is held and each element access is one relaxed atomic 8-byte load or store; two
  buffers may share memory (#149).
- A function with array stores is **impure and can never deopt after entry** (`Function.may_deopt` is
  False). Only a `pure` function may contain deopting nodes after entry; a deopt redoes the whole call
  in the interpreted function. `pure` functions store only into local arrays (`NewArray`).
- Functions holding an array or entry globals do not poll the eval breaker, and the slow poll does
  nothing while a snapshot is active (`snapshot_depth`).
- Failure sources inside a loop body: `Index`/`StoreIndex` without `proven` (`IndexError`), integer
  `//` and `%` (`ZeroDivisionError`), checked i64 `+ - * neg` (deopt in a pure function), `math.*`
  (`ValueError`/`OverflowError`), `INT` promotion (N-13, allocates a `PyLong`), anything that boxes or
  allocates (`MemoryError`), `Call` (recursion depth, TLS counters), `CallObject`/`GetAttr`/`Global`
  (user code, not closed).
- N-14: at entry a compiled function checks whether tracing/profiling/monitoring is active and, if so,
  runs the interpreted original.

## 3. Which loops qualify

A loop qualifies only if the verifier proves every condition below. Following `proven`,
`Function.virtual` and `entry_globals`, the verifier **recomputes** the result and the front end never
sets it: a new field `ForRange.par` (a `ParInfo`, absent by default) is set by `verify`, and a loop
whose declared `par` differs from the recomputed one is rejected (`verify/par-loop`). A loop that does
not qualify is not an error: it is the sequential loop it is today, and a diagnostic names the first
condition that failed (like `verify/array-open`), so users can see why.

### 3.1 Function-level conditions

- The function is **closed**: no `Global`, `GetAttr`, `CallObject`, `Truth`, `CompareObj`,
  `ObjToFloat`, OBJ `BinOp`, `GetItem`, `Contains`, `Format`, `StoreItem`, `ForObj`, `FieldGet`,
  `FieldSet`, `New`. Equivalently: no user code can run while the region is active. Workers cannot
  run Python, and another thread's code must not be needed to make progress.
- The function holds a copy-in list array, a local array, or only scalars
  (a pure scalar loop, e.g. a reduction over `range`). A function whose loop touches a buffer array
  (`array.array`) is not parallelised (decision 1).
- The user marked the function `@compiled(parallel=True)` (decision 5, #358).
- Not under `entry_globals` mutation: entry globals are read once at entry and are locals in the body,
  so they are read-only and fine.

### 3.2 Loop-body conditions

Let `I` be the loop variable.

1. **Body is a DAG of straight-line statements, `If`, and nested `ForRange`/`While`** over scalar
   locals, `Const`, `BinOp`/`UnaryOp`/`Compare`/`And`/`Or`, `ToFloat`/`ToInt`, `MathCall`, `Index`,
   `StoreIndex`, `Len`. No `Call` in the first version (a callee brings recursion accounting
   `tp_enter_call`, thread-local depth, and its own failure set; allowing calls to *leaf closed
   functions* with no array params is a later extension, §11). No `Return` inside the loop (an early
   exit makes later iterations conditional on earlier ones). `Continue` is allowed; `Break` is not
   (it is an order dependence).
2. **Scalar locals are iteration-private.** Every local assigned in the body is definitely assigned
   in the body before any read in the same iteration (verified with the existing definite-assignment
   machinery run on the body alone), and is **dead after the loop** (not read before being reassigned
   after it). Such locals become per-worker copies. A local assigned in the body and read after the
   loop is a loop-carried/last-value dependence: reject. The exception is `I` itself, whose final
   value after a non-empty loop is `start + step*(trip-1)`, computable without running the body, and
   the sequential code sets it.
3. **Writes hit distinct indices (injective).** Each `StoreIndex(a, idx, v)` has `idx` of the affine
   form `c*I + k` with `c` a non-zero i64 constant (or a loop-invariant local when proven non-zero
   at entry, §4.2) and `k` loop-invariant, with the multiplication and addition proven not to overflow
   i64 from the loop bounds (the same proof `proven` arithmetic uses). An injective map from distinct
   `I` to distinct slots means two iterations never write one element. Several stores into one array
   in one iteration are fine if their affine forms can never be equal across different iterations
   (in v1: require identical `c` and, for two stores to one array, either the same `k` (the same
   element, written twice by one iteration, in program order) or `0 < |k1 - k2| < |c|`, which makes
   the images of different iterations disjoint).
   Nested-loop stores such as `a[I*m + J]` are covered when the inner range is proven within
   `[0, m)` (a 2-D tile; `c = m`, inner offset `J`); this is the matrix case and needs the interval
   proof, so it is listed as the first extension after the 1-D case (§11).
   Anything else (data-dependent index `a[b[I]]`, `a[I*I]`, `a[I // 2]`, non-affine) is rejected,
   because injectivity is not provable.
4. **Reads never see another iteration's write.** For every array that is stored into, every `Index`
   of it in the body uses the *same* affine index expression as one of its stores (structurally equal
   after normalisation) and that read is dominated by no write at a different iteration: a read of
   `a[c*I+k]` sees only the original value or the same iteration's write. Reading a stored array at
   any other index is a loop-carried dependence: reject. Reads of arrays that are not stored in the
   loop are unrestricted (any index).
5. **No aliasing between a stored array and any other array the body reads or writes.** Lists: the
   entry guard already refuses two array parameters that are the same list object (`tp_any_same`).
   Local arrays are distinct allocations. Buffers never reach this point (decision 1), so the
   address-range guard of the earlier draft is not needed.
6. **No writes to anything outside arrays and iteration-private scalars**: no `FieldSet`, `StoreItem`.
   (Already implied by closedness, listed because it is the part the dependence proof relies on.)
7. **Reductions** satisfy §6, otherwise the loop carries a dependence and is rejected.

### 3.3 What this admits and what it does not

Admitted (v1): `out[i] = f(a[i], b[i])` over lists or local arrays with F64/I64 arithmetic; `a[i] = a[i] * s`
(read-modify-write of one's own element); a stencil whose *output* is a different array from its input;
per-row work such as `for i: for j: C[i*m+j] = ...` with the 2-D proof (extension); integer sums,
products, bitwise folds; min/max if exactly Python's (§6).

Not admitted: prefix sums and any `s = s + ...` on floats; in-place stencils (`a[i] = a[i-1] + a[i+1]`);
`a[b[i]] = ...` scatter; anything with `Break`; a loop that calls user code; a loop over an `OBJ`
iterable (`ForObj`); `While` as the parallel loop (trip count unknown).

## 4. Exceptions, deopt, partial writes

CPython runs iteration 0, 1, 2 ... in order and stops at the first failing one; iterations before it
have written their results (and the failing iteration's own writes before the failing operation are
visible), iterations after it have run not at all. A parallel run that lets iteration 900 write before
iteration 500 fails would leave an execution CPython cannot produce. The design therefore never reports
a failure from the parallel path.

### 4.1 Abort and rerun sequentially (lists, local arrays, scalars)

Each worker runs its chunk of the iteration space in the outlined body, which compiles with a
**failure mode** instead of an exception mode: every site that would raise (unproven bounds, `//` `%`
by zero, `math` domain/range error, checked i64 overflow, `INT` promotion, float-to-int range) sets a
shared atomic `fail` flag and the worker returns. Other workers check `fail` once per micro-chunk
(a power of two, a few hundred iterations) and stop. After the join, if `fail` is set:

1. Restore every array the region may have written to its pre-region contents:
   - `list` copy-in arrays: the Python list was not touched; re-run `tp_*_array_enter` on it (it is
     still locked by the critical section the wrapper holds), which also resets `dirty`. Cost O(n),
     paid only on failure.
   - local arrays (`NewArray`): if the loop only writes them, nothing to restore beyond what the
     sequential rerun overwrites; if it also reads them (read-modify-write), take a snapshot at
     region entry (a memcpy, counted in the cost rule, §8).
2. Per-worker scalar copies are discarded; the parent's locals are unchanged (§3.2.2 guarantees they
   were dead or private).
3. Run the original sequential loop. It raises, deopts or promotes exactly as today, at the first
   failing iteration in order, with partial writes identical to CPython's.

Correctness argument: after step 1 and 2 the machine state equals the state at the start of the
region, the sequential code is a function of that state, and the workers' effects were confined to
state that step 1 and 2 restored. Workers touched no Python object, no refcount, no global, no
`errno`-visible state outside their own thread, and allocated nothing visible (if a worker hits
`MemoryError` on a scratch allocation it also just sets `fail`; the rerun reports whatever CPython
would, including a real `MemoryError`).

This single rule covers the deopt question: in a pure function a deopt inside the region is a
failure, the rerun deopts at the same point as today (or not at all, if it was a spurious parallel
artefact such as a partial-sum overflow, §6), and the whole call is then redone by the interpreter
exactly as now. An impure function cannot deopt after entry anyway.

### 4.2 Buffers: sequential

A buffer is written in place and other threads can read it, so a region could not be undone after a
failure, and the earlier draft made buffer loops eligible only when no failure site was reachable
(bounds of the whole range, non-zero divisors, no raising `math` call, no unproved i64 arithmetic,
no float division by data). **Decision 1 removes that path:** a loop over an `array.array` buffer is
the sequential loop that runs today. No guard, no rollback and no alias check for buffers exists in
v1. This also makes the buffer loop narrower than the list loop by design, not by guard.

### 4.3 What `IndexError` looks like

An out-of-range store on a list array in the middle of the iteration space: a worker sets `fail`,
the rerun reaches the same iteration sequentially, raises `IndexError("list assignment index out of
range")`, and `dirty` elements written before it are written back by the existing exit path, so the
list contains exactly the writes of iterations `0 .. k-1` plus the failing iteration's earlier
statements. That is today's behaviour and the differential test pins it (§10).

## 5. Observability

Rule (SPEC N-12): every execution of compiled code must be indistinguishable from some valid CPython
execution.

### 5.1 Tracing, profiling, monitoring (N-14)

N-14 decides at function entry: if the calling thread has `settrace`/`setprofile`, or any
`sys.monitoring` tool has a global event, the interpreted original runs. The parallel region is inside
the compiled function, so a traced call never reaches it. A `ForRange` has no events a tool could see
in compiled code anyway (compiled code has no frames), so the N-14 contract is unchanged. Workers are
not Python threads: they have no thread state, so no per-thread trace function applies to them and no
event is emitted from them. N-14's one known difference (events for a running function begin at its
next call if monitoring is enabled mid-call) is unchanged and not made longer by parallelism, except
that a region is a longer uninterrupted run. Direct consequence: no new test oracle is needed beyond
`test_tracing.py` run with a parallel loop in the function.

### 5.2 Signals, KeyboardInterrupt, the eval breaker

Functions holding an array do not poll today, and the slow poll is suppressed while a snapshot is
active, so a signal arriving during such a loop is handled after the function returns, which is "a
CPython execution in which it arrived late". The region adds no new behaviour for those functions.

For a function that does poll (pure scalar loops, local-array-only functions): the sequential code
polls once per `TP_POLL_INTERVAL` iterations. A region would otherwise suspend polling for its whole
duration. Rule: the **main thread executes the first chunk itself** and, between micro-chunks, calls
the same `tp_poll`. It is the only thread allowed to. If the poll raises (KeyboardInterrupt from a
handler), the main thread sets a `cancel` flag, joins the workers and propagates; this is valid
because a polling function is pure or local-only, so its partial state is unobservable, and CPython
too may stop at any bytecode boundary. A signal handler is user code that runs only on the main
thread inside `tp_poll`, which is the point where it would run today, and it can see no partial loop
state because the function is closed. Poll granularity during a region is the micro-chunk, no coarser
than today.

### 5.3 Other threads observing a buffer or list mid-loop

Lists. The wrapper holds the list's critical section from before copy-in to after write-back. Writes
reach the list only in the write-back, which happens after the join, on the calling thread, in index
order, exactly as today. Workers touch only the C array. So another thread observes the list in
states a valid CPython execution produces (all old, then a prefix of the new values), the same
argument as the sequential version, unchanged by parallelism. (Lock-free optimistic list reads in
free-threaded CPython are the pre-existing question about the existing write-back, not made worse.)

Local arrays and scalars: nobody else can see them. Unobservable.

**Buffers** are never written by a region (decision 1), so nothing here changes for them. The earlier
concern, that chunked stores let another thread see an element order no ascending CPython loop
produces, does not arise. If the maintainer later reopens the decision, that ordering question has to
be settled first (the existing relaxed-atomic design is itself the open point of #149).

### 5.4 Things that are observable but not semantic

OS thread count, CPU time versus wall time, `os.fork()`'s multi-thread `DeprecationWarning` while a
region is active, a profiler sampling a non-Python thread. None of these is Python-level program
behaviour. **Decision 2: they are outside the identity**, the same kind of carve-out as N-12's "when
the cyclic garbage collector runs", and SPEC N-15 records them. The GC itself: a region delays
stop-the-world requests because the calling thread stays attached (it must: detaching releases the
list critical section, see §7.3), which is the liveness delay SPEC already documents for long array
loops (#152). It does not change results.

## 6. Reductions

A reduction is a scalar `s` with `s = s OP e` in the body, `e` independent of `s`, `s` read after
the loop. It is order-dependent in general; it is parallelisable only if chunked evaluation gives the
**bit-for-bit result of the sequential loop**. "Fixed order" in the issue is not enough by itself:
a chunk order that is the same on every run and every thread count is deterministic, but it is not
CPython's order, and CPython's order is the oracle.

**Float `+` and `*`: not parallelised in v1 (decision 4).** Not a permanent ban: a fastmath opt-in
(#153) awaits the owner's decision, and N-15 follows that decision. Without such an opt-in: Floating-point addition is not
associative: `(a+b)+c` and `a+(b+c)` differ in the last bits, in overflow to `inf`, and in
cancellation. Summing chunks and then summing the partials computes a different expression tree than
`((((0+a0)+a1)+a2)+...)`. No fixed chunk size fixes this; the sequential tree is a linear chain and
any parallel tree has a different shape. Pairwise summation, Kahan summation, `math.fsum` and a
parallel prefix scan all produce different (often more accurate) bits, which is exactly what INTENT
§1.4 forbids. The only way to reproduce the chain in parallel is to compute the prefix state each
chunk would start from, which is the sequential computation. So a loop such as `s += a[i] * b[i]` (a
dot product), `s += x` (a mean), `s += x*x` (a norm) stays sequential. The verifier marks it "float
reduction, order-dependent" in the diagnostic. This will disappoint people; the diagnostic should say
that a different algorithm written in Python (an explicit blocked sum with its own order) is parallel
by construction because each block is an order-independent loop writing its own output element, and
the caller combines blocks in the order they choose.

Narrow float exceptions are possible but left out of v1: a sum is exact and therefore associative when
every partial sum is an integer below 2**53 in magnitude; this can be established by a dynamic
pre-scan of the data (all elements integral, `sum |x| < 2**53`), which costs a pass and is wrong to
guess statically. Not worth the complexity until a measurement shows it matters.

Allowed reductions (all must hold: operation exactly associative and commutative on the values
involved, so any chunk tree equals the chain):

| Operation | Condition | Why bit-identical |
|---|---|---|
| i64 `+` | pure function, or accumulator typed i64 | Exact arithmetic is associative. Sequential checked-`+` deopts (pure function) to the interpreter which returns the exact big-int sum; chunked evaluation in `__int128` (or per-chunk checked sums combined with a final checked combine) returns the same value when the total fits, and a partial overflow is a failure that triggers the rerun/deopt, which gives the same final answer. |
| i64 `*` | same, with checked multiply | Multiplication of integers is associative and commutative in exact arithmetic; overflow handled as above. |
| `&`, `\|`, `^` | any | Bitwise operations are associative and commutative; no overflow. |
| `min`, `max` | only if exactly equal to Python's, see below | Decision 6. |

**`min`/`max` must equal Python's exactly, or they wait for a later step.** Python's semantics:
`min(a, b)` evaluates `b < a` and returns `b` only if true, otherwise `a`; `max(a, b)` evaluates
`b > a` likewise. So on ties the **first element in iteration order is kept**, which for floats
includes `+0.0` versus `-0.0` (equal, different bits: the first one seen wins). Comparisons with NaN
are false, so NaN behaviour depends on position: a NaN accumulator is never replaced (`x < nan` is
false), and a NaN element never replaces the accumulator (`nan < m` is false), while a NaN that is
the very first element becomes the accumulator and stays. The chain is therefore not associative
over data containing NaN (`a=1, b=nan, c=0` gives 0 grouped left, 1 grouped right when written as a
two-way fold on the values alone).

Chunked combination must reproduce this chain bit for bit:

- each chunk folds left to right with the same comparison, starting from its own first element (not
  from the global initial value);
- chunk results combine in chunk order, applying the same `<` (or `>`) with the earlier chunk's
  result as the left operand, so ties keep the earlier chunk's value;
- a NaN anywhere changes which element is kept in a position-dependent way. The combination is
  accepted only if it provably matches the sequential chain for NaN placement as well (a chunk
  whose accumulator is NaN must keep it through the combine; a chunk that begins with NaN as its
  first element must be treated as the global first only when it is the first chunk). If the
  implementation cannot show equality for every NaN and `-0.0` placement in the differential tests
  (§10), `min`/`max` over F64 move to a later step and v1 keeps the integer and bitwise reductions.
- i64 `min`/`max` have a total order with no NaN or signed zero, so the leftmost extreme is the same
  under any grouping that combines in chunk order; they follow the same rule for uniformity.

Each worker keeps its own accumulator; chunks are combined **in chunk order** on the calling thread
(chunk order is fixed by the iteration order, not by which worker finished first). For the table above
the combine order does not affect bits, but combining in index order costs nothing and keeps tie-break
rules (leftmost min) trivially right.

Reductions with a conditional update (`if x > t: s += 1`) are the same operation behind an `If` and
are admitted by the same table. A reduction whose `e` reads `s` (`s = s*2 + x`) is a recurrence:
rejected. Boolean `any`/`all` loops with `Break` are rejected (early exit is an order dependence;
a future version could do "find first" with an ordered minimum of the first hit, §11).

## 7. Thread ownership and lifetime

### 7.1 Who creates workers

The generated module's runtime (`tp_runtime.h`), not Python's `threading`, not the host. Workers are
`pthread`s (Win32 threads on Windows) created with the C API only. They never call `PyGILState_Ensure`
or `PyThreadState_New`: they have no thread state, can run no Python and hold no reference. The body
function is outlined by `cgen` into a `static` function over a struct of captured scalars and array
pointers, so a worker's whole call tree is generated arithmetic.

Consequences: they are invisible to `threading.enumerate()`, they cannot be traced (§5.1), they do not
count against the thread-state limits, they are not stopped by the GC's stop-the-world (they never
attach), and no Python object may be reachable from them. The body outliner must not use
`TP_THREAD_LOCAL` helpers that assume a Python thread (`tp_poll`, `tp_enter_call`, `tp_depth`):
verify rejects a `Call` or nested polling construct inside a parallel body, and the no-poll flavour of
nested loops is emitted for the outlined body.

### 7.2 Per-call threads first, a pool later

v1 (decision 3): create `min(T, ceil(trip / min_chunk)) - 1` threads per region, run one chunk on the calling
thread, join all before returning. No thread outlives the call.

Why not a pool now:

- **Idle threads are observable.** `os.fork()` in 3.12+ emits a `DeprecationWarning` when the process
  has multiple threads (the check is OS-level, not `threading`-level), and an idle pool is such a
  thread. A program that forks after running a compiled function would warn where interpreted code
  does not. Per-call threads exist only inside a call and cannot be present at a fork from another
  thread unless that other thread is itself in a region, which is the same as any thread doing work.
- **Whose code do idle threads run?** `tp_runtime.h` is included into every generated module, so a
  pool would be per module and its idle loop lives in that module's text. A module that is unloaded
  or torn down (`tp_clear`) while its threads wait would run freed code. A host-owned pool (the
  python-multiplatform native library, shared through a capsule) avoids per-module duplication and
  unloading but ties plain-CPython use (no host) to a fallback and adds an API.
- It is an optimisation of the fixed cost (§8). Thread creation and join cost is measured together with
  `TP_PAR_MIN_WORK`; a pool is reconsidered only if that measurement shows the cost dominates.

### 7.3 Why the calling thread must stay attached

The calling thread must **not** release its thread state (`Py_BEGIN_ALLOW_THREADS`) while waiting:
detaching suspends the held `PyCriticalSection`s, which is the same reason the list wrapper refuses a
third list, and would let other threads mutate the list while the region runs. It waits attached, with
the main chunk done first, by `pthread_join` or a futex/condvar wait. This is the same liveness delay
as the sequential long loop (a stop-the-world request waits for the loop), not worse.

### 7.4 Interpreter shutdown, fork, errors

- A region never survives its call, so `Py_Finalize` cannot find a worker running. No `atexit` hook.
  (A later pool would need `Py_AtExit` join and an "interpreter finalising" guard; out of scope.)
- Thread creation fails (resource limit): the region falls back to fewer workers, down to the calling
  thread alone, which is the sequential loop. No exception; not a semantic event.
- `fork()` from another Python thread while a region is running in this one: the child has only the
  forking thread and the workers do not exist. The child would never run this call (its copy of this
  thread does not exist either). `pthread_atfork` handlers are not needed for per-call threads, since
  no runtime lock is held across calls. A later pool needs `prepare`/`parent`/`child` handlers; noted.
- Process-wide thread cap: `TYPEDPYTHON_THREADS` (positive integer, default `min(cores, 8)`, the default measured and recorded on #151; cores from
  `sched_getaffinity`/`sysconf`), read once at module init. `1` disables the feature, the way a
  test disables it. Platforms without threads (wasm, a build with no `pthread`) compile the region
  out entirely (`#if` guard), and the sequential loop is the only one.
- Android: workers are plain C threads that make no JNI call and need no `AttachCurrentThread`.

## 8. Cost rule

Starting threads is not free and a memory-bound loop gains little. At the loop head, with
`n = trip count` (known at run time from `start/stop/step`):

```
parallel iff  n >= N_min  &&  n * w >= W_min  &&  available threads > 1
```

`w` is a per-iteration weight computed by `cgen` from the IR (a small table: float add/mul 1, divide
and `math.*` 10-20, array load/store 2, nested `ForRange` multiplies by its own estimated trip when
constant, otherwise a large default), and `W_min` is the work worth a thread start plus join, both
**decided by a benchmark** (decision 7: a quiet window, recorded on #151), not by this note. Inputs the rule must consider:

- thread creation + join cost per worker on each supported platform (Linux, macOS, Windows, Android);
- the cost of the `list` copy-in/write-back that surrounds the region (O(n) and not parallel unless the
  copy-in is itself chunked: it runs under the list's critical section and allocates `PyFloat`s on
  write-back, so it is on the calling thread; if copy-in dominates, the cost rule must say no);
- the snapshot memcpy of §4.1;
- memory bandwidth: `a[i] = b[i] + c[i]` streams 24 bytes per iteration and saturates at a few
  cores, so a weight below some arithmetic intensity yields `parallel = false` or caps threads;
- the failure rerun doubles the time of a failing call; accepted because failures end in an exception
  or deopt anyway.

The threshold lives as `#define TP_PAR_MIN_WORK` in `tp_runtime.h`, overridable like
`TP_POLL_INTERVAL`, with the measured value and the date recorded next to it. Chunking: contiguous
static ranges, each a multiple of 64 bytes of the stored arrays' element size (a cache line), to avoid
false sharing on `dirty` and the data; static partition because iterations are uniform by assumption
(non-uniform bodies, e.g. data-dependent inner trip counts, get dynamic micro-chunk stealing in a later
version). Partitioning cannot affect the result, only speed, because of §3.2.

## 9. Verifier and back end plan (for the implementation issue)

- `ir.py`: `ForRange.par: ParInfo | None = None`, set only by `verify`, with `kind` (`map` or
  `reduce`), the list of stored arrays with their affine forms, the reductions (accumulator, op, init),
  the private scalars, and the needed entry guards (§4.2). Documented next to `proven`.
- `verify.py`: a pass `verify/par-loop` that computes `ParInfo` or a reason for refusal, and rejects a
  declared `par` that differs from the recomputed one (a "tamper" test).
- `cgen.py`: outline the body, emit a failure-mode variant of every raising site, the region driver
  (`tp_par_run`), restore, and the sequential fallback (which is the existing emitter, unchanged).
  The sequential emitter must stay the single source of exceptions; the parallel emitter never builds
  an exception.
- `runtime/API.md`: a "Parallel regions" section and rows in the free-threaded state table: the `fail`
  flag and the reduction slots are region-local (stack), the thread cap is immutable after init.
- Introspection: `__typedpython_parallel__` counters (regions run, regions that fell back, fallbacks
  because of failure), like `__typedpython_deopts__`, so tests can prove the parallel path ran
  (AGENTS.md §8: a check that cannot fail is not a check).

## 10. Tests the implementation must have

Written from SPEC N-15 before the code, red first (AGENTS.md §5).

1. **Differential vs the interpreter** (`__typedpython_interpreted__`), bit-for-bit (`struct.pack`
   comparison, not `==`, so `-0.0` and NaN payloads count): map loops over lists and local arrays, random
   data (and a check that `array.array` loops stay sequential); trip counts `0, 1, 2, T-1, T, T+1, chunk boundaries -1/0/+1, prime numbers`, negative and
   non-unit steps; data with NaN, inf, `-0.0`, subnormals; read-modify-write loops; 2-D tile loops.
2. **Thread-count independence:** the same inputs with `TYPEDPYTHON_THREADS` 1, 2, 3, 7, 64 give
   identical bytes.
3. **Failure semantics:** an `IndexError`, `ZeroDivisionError`, `ValueError` (math domain), i64
   overflow in a pure function, and an `INT` promotion at the first, a middle, the last iteration and
   in several workers' chunks at once; the exception type/message, the resulting list contents (the
   exact prefix of writes), and the deopt count equal the interpreter's. Run a buffer loop too: it takes the
   sequential path, same results.
4. **Qualification, negative:** each condition of §3 has a loop violating only that condition that
   must *not* be parallelised (prefix sum, in-place stencil, scatter, `Break`, a float sum, a loop
   reading a stored array at another index, a buffer loop, an unmarked function, a call inside), checked by
   the verifier diagnostic and by the counter staying zero while the result stays correct.
5. **Disable test:** build with the region disabled and confirm the "the parallel path ran" tests fail
   (AGENTS.md §8).
6. **Reductions:** i64 sums with partial-prefix overflow whose total fits; sums that overflow; XOR,
   AND, OR; min and max against Python's `min`/`max` for ties, `-0.0` vs `0.0` and NaN at every position
   (first, middle, last, chunk boundary, several NaNs); the float-sum diagnostic.
7. **Race and observation:** a concurrent reader thread polling a list during a long
   region (free-threaded build): reader sees only valid list states. A thread appending to a list at call time. GC stop-the-world during a
   region (allocation-heavy second thread): no deadlock, no crash, results equal.
8. **TSan** (and ASan/UBSan) over the runtime and a generated module with regions on a free-threaded
   interpreter, in CI, run as its own invocation. No reports from region code.
9. **N-14:** `test_tracing.py` cases with a parallel function: traced call goes to the interpreter,
   no region, events equal.
10. **Signals:** `KeyboardInterrupt` delivered during a polling function's region stops it and
    leaves no thread behind (thread count back to baseline); for an array function the signal is
    handled after return.
11. **Fork and shutdown:** `os.fork()` after a region produces no warning (no thread left behind);
    interpreter exit right after a region; thread creation failure simulated by a low `RLIMIT_NPROC`
    falls back sequentially.
12. **Measurement tests** (AGENTS.md §14): thread start/join cost, the crossover trip count per
    platform, scaling curve for a compute-bound and a bandwidth-bound loop. These produce the
    `TP_PAR_MIN_WORK` value and the thread cap; run alone, unfiltered, after `uptime`.

## 11. Extensions after the first version

2-D tile stores (`a[i*m + j]`), calls to leaf closed functions with no array parameters, dynamic
micro-chunk scheduling for uneven bodies, "find first" with an ordered minimum of the hit index, a
host-owned thread pool, `entry_globals` read in the body, the exact-float-sum pre-scan of §6, float
`+`/`*` reductions under a fastmath opt-in if #153 is decided that way, implicit parallelisation of
unmarked functions, `min`/`max` reductions if v1 cannot match Python's exactly, and buffer loops only
if the maintainer reopens decision 1.

## 12. Decisions

Recorded 2026-10-25 from the maintainer. The body and SPEC N-15 follow them.

1. **Buffers stay sequential.** `array.array` buffer loops are not parallelised. Only `list` (copy-in)
   and local-array loops are. This removes the free-threaded ordering question of the earlier §5.3
   (buffers are written in place, no rollback, and a concurrent reader could see an order no CPython
   execution produces) and the buffer alias and no-failure guards.
2. **Non-semantic effects are outside the identity**, like N-12's cyclic GC timing, and are recorded
   in SPEC N-15: OS thread count, CPU time versus wall time, the multi-thread `os.fork()` warning
   while a region is active, and profilers seeing non-Python threads.
3. **No persistent pool in v1.** Threads are created and joined per region. Thread creation cost is
   measured together with the threshold (`TP_PAR_MIN_WORK`).
4. **Float `+` and `*` reductions are excluded in v1.** Not permanently: a fastmath opt-in (#153)
   awaits the owner's decision, and N-15 follows that decision. Until then they stay sequential.
5. **Only functions the user marks are parallelised in v1.** Marked with `@compiled(parallel=True)`
   (maintainer decision 2026-10-30, #358: `@compiled` is the only compilation mark, and options are
   keyword arguments on `@compiled`, matching `#332` `@compiled(fastmath=True)`; the earlier
   `# typedpython: parallel` comment marker was removed). Widening later (implicit parallelisation) is
   easy; narrowing later is hard, so v1 starts narrow.
6. **`min`/`max` reductions are in v1 only if exactly equal to Python's** (§6). If an implementation
   cannot match it, they move to a later step and v1 keeps the integer and bitwise reductions only.
7. **Thresholds are measured, not chosen here.** `TP_PAR_MIN_WORK` and the thread cap (default 8) are
   measured in a quiet window (after `uptime`, run alone, unfiltered) and recorded on #151.

### Open questions that remain

- (Settled: free-threaded 3.15t is the only build, #158, so there is no GIL-build variant.)
- Which devices the threshold is measured on (the Mac mini only, or also an Android device). The
  measurement itself is decided (item 7); the device set is not.
- (Settled 2026-10-30, #358: the marking is `@compiled(parallel=True)`.)
