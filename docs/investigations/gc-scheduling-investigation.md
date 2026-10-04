# Why a pure C API embedder never collects cycles, and never merges a refcount

Conditions for every number below unless stated: `desktopTest`, macOS arm64 (Apple M1), CPython
3.14.7 (`gradle.properties: pythonVersion=3.14.7`), both the default (GIL) build and the opt-in
free-threaded build (`-PpythonFreeThreaded=true`, 3.14t; desktop only, default is `false`). CPython
file:line citations were checked against the v3.14.7 tarball (fetched outside the repo, not
vendored). Printed by `GCSchedulingMeasurementTest`, `EvalCheckpointTest`, `FreeThreadedGCGateTest`,
`CycleCollectionTest` (all under `python-multiplatform/src/{desktopTest,commonTest}/.../ref/`).

## 0. Summary

Conclusions (current):

- **The cyclic collector never runs for an embedder that executes no Python frame.** Allocation
  only sets a bit on the eval breaker; the only reader is `_Py_HandlePending`, called from the eval
  loop. True on both builds. (§1)
- **Free-threaded, a completed `Py_DecRef` can leave the object alive** (biased reference counting:
  a non-owner thread's final decrement is queued to the owner, drained only by the eval loop). (§2)
- **`CycleCollectionTest`'s free-threaded failure was a different thing:** the probe read
  `ob_tid` instead of a refcount (header layout differs), and heap types are deferred-ref-counted,
  so the type's count is observable only after a collection. An eval-loop checkpoint provably does
  not fix it; two `PyGC_Collect()` calls in the test do. (§3)
- **The checkpoint fixes §1 on the GIL build; free-threaded it often reclaims nothing**: because
  `_Py_RunGC` re-asks `gc_should_collect`, which gates generation 0 on growth of the **whole
  process's** memory footprint (the JVM's). Python-side allocation cannot move it. Not caused by
  per-call `PyGILState_Ensure/Release` scopes (tested, rejected). (§8, §8d)
- **Supported way to collect cycles in an embedder that runs no bytecode, identical on both
  builds: `PyGC_Collect()`**, cost proportional to the heap (§5). `drainPendingReleases()` is the
  cheap checkpoint that merges refcounts (and collects on the GIL build).

**Status: acted on.**
- `Python3.drainPendingReleases()` and `Python3.autoDrainInterval` (default 32 on free-threaded
  builds, 0 otherwise): `python-multiplatform/src/commonMain/kotlin/python/multiplatform/ffi/Python3.kt`
  (~lines 737-802; checkpoint function `__pmp_eval_checkpoint__`); rules in
  `python-multiplatform/src/commonMain/README.md`.
- Tests: `commonTest/.../ref/EvalCheckpointTest.kt`, `desktopTest/.../ref/GCSchedulingMeasurementTest.kt`,
  `FreeThreadedGCGateTest.kt`, `CycleCollectionTest.kt`. SPEC M-3, T-2.
- Free-threaded default behaviour (no bound on reclamation at default thresholds) is a documented
  consequence, not fixed.

## 1. `_Py_ScheduleGC` only sets a bit

Allocation-driven collection has not been a direct call since 3.12.

| build | function | file:line (v3.14.7) |
|---|---|---|
| GIL | `_Py_ScheduleGC` | `Python/gc.c:1846` |
| free-threaded | `_Py_ScheduleGC` | `Python/gc_free_threading.c:2795` |

The bit's only reader is `_Py_HandlePending` (`Python/ceval_gil.c:1357`; runs `_Py_RunGC` at
`:1397-1399`), reached from `_CHECK_PERIODIC` uops that open every Python frame (`RESUME`) and
close every call instruction. No public entry point. So allocation schedules a collection that only
the evaluation loop will perform; `gc.collect()`/`PyGC_Collect` is the only thing that has ever
collected anything in this library's tests.

`Py_MakePendingCalls` is **not** a substitute: it forwards to `_PyEval_MakePendingCalls`
(`ceval_gil.c:1034`), which never looks at the GC bit nor the merge bit. Checked by
`EvalCheckpointTest.testDrainPendingReleasesReclaimsWhatTheCleanerGaveBack` (calls it, asserts 0
return and that the refcount did not move).

## 2. Free-threading: biased reference counting, and the queue only the eval loop drains

On a free-threaded build `Py_DECREF` branches on whether the thread owns the object (`ob_tid`):
owner decrements `ob_ref_local` and may `tp_dealloc` at once; a non-owner decrements
`ob_ref_shared` atomically, and if that takes it to zero the object is queued to its owner by
`_Py_brc_queue_object` (`Objects/object.c:411`). The queue is drained by
`_Py_brc_merge_refcounts`, whose only caller is `_Py_HandlePending` (`ceval_gil.c:1388`, behind
`_PY_EVAL_EXPLICIT_MERGE_BIT`). After a collection, merged survivors keep `ob_tid == 0`
(`gc_restore_tid`, `gc_free_threading.c:325/:330`), so every later decrement takes the shared path.

Measurement (ROADMAP §9): 1000 wrappers released by the JVM cleaner, 2003 `Py_DecRef` calls made,
`sys.getrefcount` frozen at 1002 through 50 forced JVM GCs, 500 further C API round trips and two
seconds of wall clock; one trivial bytecode frame then released all thousand at once.

## 3. `CycleCollectionTest`: two defects

Two successive diagnoses were wrong in opposite directions; a direct header dump settled it.

**3a. The probe read the wrong bytes.** `ob_refcnt` is at offset 0 only on the GIL build.
Free-threaded `struct _object` is `ob_tid` +0 (8 B), `ob_flags` +8, `ob_mutex` +10, `ob_gc_bits`
+11, `ob_ref_local` +12 (4 B), `ob_ref_shared` +16 (8 B, shifted 2, low 2 bits flags), `ob_type`
+24; `Py_REFCNT = ob_ref_local + (ob_ref_shared >> 2)`. The failure printed an identical ten-digit
number (`6171668704`) before and after 100 instances: that was `ob_tid`.

**3b. Heap types are deferred-reference-counted.** Header of the proxy type, free-threaded:

| | `ob_tid` | `ob_gc_bits` | `ob_ref_local` | `ob_ref_shared` |
|---|---|---|---|---|
| fresh type | `0x16d9b70e0` | `0x41` | 2 | `0x3ffffffffffffffd` |
| 100 instances alive | same | `0x41` | 2 | `0x3ffffffffffffffd` |
| after `drainPendingReleases()` | same | `0x41` | 2 | `0x3ffffffffffffffd` |
| after `PyGC_Collect()` | same | `0x41` | 2 | `0x400000000000018d` |
| after 100 `Py_DecRef` → 100 `tp_dealloc` | `0` | `0x41` | 0 | `0x4000000000000007` |

`0x41` = tracked | deferred. `ob_ref_shared` = `2^60 - 1` sentinel plus `_Py_REF_MAYBE_WEAKREF`.
Creating instances changes nothing (`_Py_INCREF_TYPE` is a no-op for a deferred type on its owning
thread); a checkpoint changes nothing (no queued decrement exists); a collection materialises the
references (`ob_ref_shared` rose by exactly `100 << 2`) and `tp_dealloc` gives back exactly 100.
So the invariant ("each live instance holds one reference to its type") holds on both builds;
free-threaded it is observable only after a collection. `CycleCollectionTest` takes a
`PyGC_Collect()` on either side of its instantiation loop, free-threaded only.

`abi3t` is not a blocker: the probe's header-layout knowledge is a property of the probe (the one
place in this repo reading inside a `PyObject`), not of the invariant; `ProxyTypeFactory` writes
only into memory from `PyObject_GetTypeData`.

## 4. What this repo does about §1 and §2

`Python3.drainPendingReleases()` reaches a checkpoint by calling a cached, compiled, zero-argument
Python function (body `pass`) through `PyObject_CallNoArgs`: one `RESUME` → one `_CHECK_PERIODIC` →
one `_Py_HandlePending` (BRC merge, QSBR sweep, scheduled collection if any). Not
`PyRun_SimpleString("pass")` (its `PyErr_Print()` clears the error indicator) and not `exec("pass")`
(recompiles each time, 23× the cached call).

`withGIL` takes one automatically at its outermost entry behind two gates: at most one per
`Python3.autoDrainInterval` outermost scopes, and only if `ReleaseCounter.released` has moved.
`reachEvalCheckpointHoldingGIL` declines while `PyErr_Occurred()` is non-null. It is not taken from
the cleaner thread (the queue belongs to the owning thread; running Python there is the ROADMAP §1
deadlock), pinned by `EvalCheckpointTest.testCleanerActivityAloneTakesNoCheckpoint`.
`PyGC_Collect` is the one Stable ABI call that reclaims on behalf of other threads (it stops the
world and merges each thread state), at heap-walk cost, and the only thing that materialises a
deferred reference.

## 5. Measurements

`EvalCheckpointTest.testCheckpointCostAgainstTheAlternatives`, free-threaded desktop, macOS arm64.
The test asserts the *ordering*, not the numbers.

| | ns/op | |
|---|---:|---|
| `withGIL { }`, attach and detach | 170.51 | floor |
| `Python3.drainPendingReleases()` | 302.70 | ~132 ns over floor |
| `withGIL { Py_MakePendingCalls() }` | 197.34 | cheap, does not merge |
| `Python3.exec("pass")` | 7 037.04 | 23× the checkpoint |
| `withGIL { PyGC_Collect() }` | 224 625.00 | 742× the checkpoint |

At `autoDrainInterval = 32` the automatic checkpoint amortises to ~4 ns on a ~170 ns outermost
scope. `CycleCollectionTest`'s two collections cost ~0.45 ms, free-threaded only.

## 6. What is still true, and what to watch

- The scheduled-GC half of §1 affects both builds. Nothing in this repo depends on
  allocation-driven collection, and `autoDrainInterval` defaults to 0 on the GIL build, so by
  default no generational collection runs on its own. A choice, not an oversight; reversing it is
  one assignment.
- `EvalCheckpointTest` asserts `Py_MakePendingCalls` does *not* merge; it fails if CPython changes.
- The header layout in §3a is read in exactly one place, guarded by an assertion that has already
  fired twice.
- `ProxyTypeFactory`'s hand-written `tp_dealloc` releases the type through the deferred-unaware
  `Py_DecRef`, while the matching increment went through deferred-aware `_Py_INCREF_TYPE` and did
  not happen; balanced by the collector, not by the two calls (measured in §3b).

## 7. GC accumulation and re-entrancy (GIL build)

- 10,000 cyclic groups built purely through the C API with `autoDrainInterval = 0`: 20,000+
  objects accumulate indefinitely (no bytecode runs, the bit is never read).
- Same workload with `autoDrainInterval = 32`: checkpoints let `_Py_RunGC` run; ~1,970 of ~20,000
  remain. **Specific to the GIL build** (free-threaded: §8).
- `__del__` calling back into Kotlin during `PyGC_Collect()` or a checkpoint
  (`GCSchedulingMeasurementTest.testReentrancyDuringCheckpoint`): safe on the GIL build, no
  deadlock, no loop, the upcall runs.

## 8. Free-threaded: the checkpoint fires and reclaims nothing

Workload: `GCSchedulingMeasurementTest.buildAndDropCycles`, 10,000 unreachable `list` cycles
(20,000 objects) built through the C API.

**8a. The probe held the evidence.** Measuring with `gc.get_objects()` unclosed leaked a list that
strongly references every tracked object, rooting the garbage being measured; that produced the
earlier "free-threaded does not reclaim" conclusion. With temporaries released, an explicit
`gc.collect()` reclaims the residue essentially completely on both builds (60+ repetitions,
residue −13..+2 of ~20,000). The residue is ordinary collectable garbage; what differs is what
makes the collector *run*. Do not restore the inline `gc.get_objects()` chain.

**8b. Rejected: per-call `PyGILState_Ensure/Release` destroys the scheduled bit.** 2,000 fixed
explicit drains, only the enclosing scope varied, free-threaded, residue of 20,000: no scope 20,000
×5; per round 20,000 ×5; per 100 rounds 20,000 ×10; one scope for the whole workload bimodal within
one JVM (11,562 / 1,280 / 1,280, then 20,000 ×7). Widening the scope changes nothing. With
`autoDrainInterval = 32`: 1,875 / 312–313 / 3 / **0** checkpoints for the four scope shapes (one
outermost scope decrements the countdown once, so a long batch in one `withPython` gets *fewer*
automatic checkpoints), residue 20,000 throughout.

**8c. The contrast.** On the GIL build every shape is stable and near-complete: residue 1,920–1,972
for per-call/explicit-drain shapes, 1,556–1,616 per round at interval 32; **3 checkpoints at
interval 32 per-100-rounds still reclaim ~90%** (residue 1,600–3,200). Three checkpoints reclaim
~90% on the GIL build; two thousand reclaim nothing free-threaded. That gap, not scope shape, is
the finding.

### 8d. Answered: the checkpoint is fine, the collector asks a second question

Source-confirmed asymmetry (v3.14.7):

| | GIL (`gc.c`) | free-threaded (`gc_free_threading.c`) |
|---|---|---|
| schedules on | `generations[0].count > threshold` only (`gc.c:1866`) | `gc_should_collect` (`:2153`) |
| re-checked when the checkpoint runs it | `gc_select_generation`, gen 0 on count alone (`:1258`) | `gc_should_collect` **again** (`:2328`) |
| process-memory gate | none | `gc_should_collect_mem_usage` (`:2080`) |
| `long_lived_total / 4` | oldest generation only (`:1300`) | every gen-0 decision (`:2131`) |

`gc_should_collect_mem_usage` returns true if `deferred > threshold * 40` (`:2089`) or
`(footprint − last_mem) > max(last_mem/10, 128)` (`:2100`); otherwise it zeroes `young.count` into
`deferred_count` (`:2109-2113`) and returns false. `footprint` is `get_process_mem_usage()`, on
macOS `task_info(TASK_VM_INFO).phys_footprint` (`:2010`): the **whole process**, i.e. the JVM.
`last_mem` is written only after a collection (`:2301`). Twenty thousand small Python lists cannot
move a JVM's footprint by `last_mem/10`, and the escape (`deferred_count > 40 × threshold0` = 80,000
at default) is not reached by ~20,000 allocations. **This is also the latch:** early in a JVM's life
the footprint is climbing and the gate opens; once a collection records the settled footprint, the
gate is shut one-way. Scope shape is irrelevant, consistent with §8b.

Experiments (all in `FreeThreadedGCGateTest`; residue of 20,000 and collections run):

| experiment | free-threaded | GIL |
|---|---|---|
| `gc.get_count()[0]` shape across workload | sawtooth `[1728, 1488, 976, 464, 2464, 1488, 976, 464, 2464, 1488]`, 0 collections, residue 20,000 (memory gate declines) | - |
| threshold `(2000,10,10)` | 20,000, 0 collections | 1,968, 9 collections |
| `(2000,0,0)` (`old[0].threshold == 0` short-circuits `:2126`) | **1,318, 8 collections** | 1,966, 5 collections |
| `(2000,10,10)` again (A/B/A, one JVM) | 20,000, 0 | 1,970, 9 |
| `threshold0` 2000 / 500 / 100 (bar 80,000 / 20,000 / 4,000) | 20,000 / 20,000 / **1,466 (3 collections)** | scales smoothly: 9 / 36 / 172 collections |
| baseline | 20,000, 0 | 1,972, 9 |
| 400 MB ballast faulted in midway | **9,754, 1 collection** | 1,988, 9 |
| ballast dropped | 20,000, 0 | 1,968, 9 |

The ballast result reproduced identically in three consecutive JVMs: JVM memory growth, and nothing
else, collected Python cycles. Probe pitfall recorded: sampling `gc.get_count()` every 500
allocations flushes the per-thread buffer (`gc_get_count_impl`, `Modules/gcmodule.c:215`;
`LOCAL_ALLOC_COUNT_THRESHOLD` = 512), so the collector is never asked and the trace looks linear;
sample every 2,000.

Not confirmed: the scaling prediction (10,000 / 30,000 / 60,000 cycles at default thresholds gave
1 / 2 / 2 collections; the 10,000 arm should have given 0 but ran first in its JVM while the
footprint was still climbing). Untested, not confirmed.

What changed in code/tests: `FreeThreadedGCGateTest` holds free-threading to the same "reclaims
more than half" bound as the GIL build once the gate is open, and asserts the matched no-op on the
GIL build so a CPython change to either gate fails it. `measureCyclicGarbageWithAutoDrain` asserts
no bound free-threaded in the default configuration (outcome depends on JVM footprint history).

Advice: an embedder can open the gate (`gc.set_threshold(t0, 0, 0)`; small `threshold0` lowers the
`40 × threshold0` bar), but both trade throughput and tune CPython internals the Stable ABI does not
promise. The supported way is `PyGC_Collect()` (§4).

## How to reproduce

```bash
# GIL build
./gradlew :python-multiplatform:desktopTest --tests 'python.multiplatform.ref.GCSchedulingMeasurementTest' \
    --tests 'python.multiplatform.ref.CycleCollectionTest' --console=plain > .tmp/gc-gil.log 2>&1; echo "EXIT=$?"
# free-threaded build (3.14t, desktop only)
./gradlew :python-multiplatform:desktopTest -PpythonFreeThreaded=true \
    --tests 'python.multiplatform.ref.FreeThreadedGCGateTest' --tests 'python.multiplatform.ref.EvalCheckpointTest' \
    --console=plain > .tmp/gc-ft.log 2>&1; echo "EXIT=$?"
```

(Package names assumed from the directory layout; adjust to the test files' `package` line.) Run on
a quiet machine, per-module and unfiltered when quoting numbers; the footprint-gated results depend
on JVM history, so run the whole suite for a cut.

The full original, with the stepwise corrections, is
[`docs/archive/investigations/gc-scheduling-investigation.md`](../archive/investigations/gc-scheduling-investigation.md).
