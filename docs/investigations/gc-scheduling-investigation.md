# Why a pure C API embedder never collects cycles, and never merges a refcount

Two things this document is careful about, because it corrects a previous document that was not:

- **CPython file:line citations are now verified against the tagged source.** The v3.14.7 tarball
  (the version this build pins — `gradle.properties: pythonVersion=3.14.7`) was fetched to
  `/Volumes/macMini/cpython-src` for §8d and every line number in this document was re-checked
  against it. Two in §1 were wrong by ~100 lines and are corrected below; the rest were right.
  Nothing is vendored into this repo. Where a claim matters, §3, §4 and §8 still back it with a
  measurement taken here rather than with a citation.
- **Every number in §3, §5 and §8 was measured on this machine**, on `desktopTest`, macOS arm64,
  CPython 3.14.7, both the default and the `-PpythonFreeThreaded=true` builds.
- **§8 corrects §7.** A measurement probe in `GCSchedulingMeasurementTest` was leaking the list
  returned by `gc.get_objects()`, which holds the whole tracked heap, and that leak is what
  produced the earlier free-threaded conclusion. The correction was itself measured; the
  hypothesis it replaced was measured and rejected rather than merely doubted.

## 0. Summary

Two symptoms that looked unrelated share one cause, and a third that looked like the same thing is
not.

- **The cyclic collector never runs.** An embedder that only ever calls `PyObject_Call` and friends
  never triggers a generational collection, however much it allocates. True on both builds. (§1)
- **Free-threaded, a completed `Py_DecRef` can leave the object alive.** The count is correct and
  the decrement happened, but `tp_dealloc` has not run and the memory is not back. (§2)

  These two are the same thing: CPython schedules both jobs by setting a bit on the *eval breaker*,
  and the only code that reads those bits is `_Py_HandlePending`, which the evaluation loop calls
  and nothing else does. No bytecode frame, no checkpoint; no checkpoint, no collection and no
  merge.
- **`CycleCollectionTest`'s free-threaded failure is *not* the same thing**, and an eval-loop
  checkpoint provably does not fix it. It is deferred reference counting on the heap type, and what
  materialises a deferred reference is a collection, not a checkpoint. (§3)
- **The checkpoint fixes §1 on the build with the global lock, and on the free-threaded build it
  often does not.** It fires exactly as designed there and still reclaims nothing. This is *not*
  caused by the FFI wrapper's per-call GIL scopes — that hypothesis was tested by holding the
  checkpoint count fixed and widening the scope, and rejected. (§8)

  The cause is that free-threaded, `_Py_RunGC` re-asks `gc_should_collect` *after* the checkpoint
  has read the scheduled bit, and that check gates generation 0 on the growth of the **whole
  process's** memory footprint — the JVM's. Python-side allocation cannot move it, so the gate
  shuts one-way as soon as the JVM's footprint settles. Confirmed by opening and closing the gate
  on demand, including by faulting in 400 MB of ballast and watching Python cycles get collected
  with no threshold changed. (§8d)

## 1. `_Py_ScheduleGC` only sets a bit

Allocation-driven collection has not been a direct call since 3.12. `_Py_ScheduleGC` sets
`_PY_GC_SCHEDULED_BIT` on the eval breaker and returns:

| build | function | file:line (v3.14.7, verified) |
|---|---|---|
| global lock | `_Py_ScheduleGC` | `Python/gc.c:1846` |
| free-threaded | `_Py_ScheduleGC` | `Python/gc_free_threading.c:2795` |

The bit's only reader is `_Py_HandlePending` (`Python/ceval_gil.c:1357`), which calls `_Py_RunGC`
when it is set (`Python/ceval_gil.c:1397-1399`). `_Py_HandlePending` in turn has one caller family: the
`_CHECK_PERIODIC` / `_CHECK_PERIODIC_IF_NOT_YIELD_FROM` uops in `Python/bytecodes.c`, which open
every Python-level frame (`RESUME`) and close every call instruction. There is no public entry
point to it.

So **allocation schedules a collection that only the evaluation loop will ever perform.** An
embedder that executes no Python-level frame accumulates the scheduled bit forever and its cycles
are never broken. This is not a free-threading property; it is the same on both builds, and it is
why `gc.collect()` (or the C `PyGC_Collect`) has been the only thing that has ever collected
anything in this library's tests.

### `Py_MakePendingCalls` is not a substitute

It is the obvious Stable ABI candidate and it does not work. It forwards to
`_PyEval_MakePendingCalls` (`Python/ceval_gil.c:1034`), which handles `handle_signals` and
`make_pending_calls` and returns. It never looks at `_PY_GC_SCHEDULED_BIT`, and on a free-threaded
build it never looks at the merge bit either.

This one is checked here rather than taken on trust:
`EvalCheckpointTest.testDrainPendingReleasesReclaimsWhatTheCleanerGaveBack` calls
`Py_MakePendingCalls`, asserts it returned 0, and asserts the reference count did not move.

## 2. Free-threading: biased reference counting, and the queue only the eval loop drains

`Py_DECREF` on a free-threaded build is not one operation. It branches on whether the decrementing
thread is the object's owner, recorded in `ob_tid`:

- **Owner.** `ob_ref_local` is decremented non-atomically. If it reaches zero and the shared count
  is zero too, `tp_dealloc` runs immediately, on this thread.
- **Not the owner.** The decrement goes to `ob_ref_shared` atomically. If that count was already at
  zero — i.e. this decrement is the one that takes the object to zero — `tp_dealloc` *cannot* run
  here, because the object belongs to another thread. It is pushed onto that thread's queue by
  `_Py_brc_queue_object` (`Objects/object.c:411`, reached from the inline decref in
  `Include/refcount.h:363`).

The queue is drained by `_Py_brc_merge_refcounts`, whose only caller is, again, `_Py_HandlePending`
(`Python/ceval_gil.c:1388`), behind `_PY_EVAL_EXPLICIT_MERGE_BIT`.

**And once a collection has run, a surviving object can become a non-owner case for everybody.**
On the way out, `gc_restore_tid` (`Python/gc_free_threading.c:325`) puts each survivor's owning
thread id back — *except* when its shared refcount has been merged, in which case `ob_tid` is left
at 0 (`:330`) and no thread matches any more, so every further decrement of that object takes the
shared path. (The inherited citation for this was `:205`, which is not the mechanism; merging
itself is at `:264`. The behaviour is as described, but it applies to merged survivors rather than
to all of them.)

That is the mechanism behind the ROADMAP §9 measurement: 1000 wrappers released by the JVM cleaner,
2003 `Py_DecRef` calls demonstrably made, and `sys.getrefcount` frozen at 1002 through 50 forced
JVM GCs, 500 further C API round trips and two seconds of wall clock — then one trivial bytecode
frame released all thousand at once.

## 3. `CycleCollectionTest`: two defects, and the one that is *not* a checkpoint problem

`CycleCollectionTest.testHandleReleasedWhenProxyDiesWithoutCycle` was the last free-threaded
failure. It has two independent causes, and the guard assertion — "the type's count must rise by
exactly `rounds` while the instances are alive, otherwise the probe is not reading a refcount" —
caught both, exactly as it was written to.

### 3a. The probe read the wrong bytes

`ob_refcnt` is at offset 0 only on a build with the global lock. Free-threaded CPython lays the
header out differently:

```c
/* Include/object.h, Py_GIL_DISABLED */
struct _object {
    uintptr_t  ob_tid;          /* +0,  8 bytes -- owning thread id, NOT a refcount */
    uint16_t   ob_flags;        /* +8              */
    PyMutex    ob_mutex;        /* +10             */
    uint8_t    ob_gc_bits;      /* +11             */
    uint32_t   ob_ref_local;    /* +12, 4 bytes    */
    Py_ssize_t ob_ref_shared;   /* +16, 8 bytes, shifted by _Py_REF_SHARED_SHIFT (2),
                                        low 2 bits are state flags */
    PyTypeObject *ob_type;      /* +24             */
};
```

`Py_REFCNT` there is `ob_ref_local + (ob_ref_shared >> 2)`. Reading eight bytes at offset 0 yields
`ob_tid` — which is why the failure reported a ten-digit number that was *identical* before and
after creating 100 instances: measured here, `6171668704` both times. The layout above was
confirmed against this build by dumping the header (see 3b); it is not taken from documentation.

The probe now reads the right fields on each build.

### 3b. Correcting the correction: heap types **are** deferred-reference-counted

This section exists because two successive diagnoses of this failure were wrong in opposite
directions, and only a direct measurement settles it.

ROADMAP §9 originally said:

> Fixing the offset would not save the test. Its premise is that "each live instance of a heap type
> holds one reference to that type", and free-threaded CPython gives heap types **deferred
> reference counting** [...] The invariant simply does not exist there, so `tp_dealloc`'s
> obligation to release the type reference cannot be checked this way at all.

A later source trace proposed the opposite: that this was wrong, that `_PyGC_BITS_DEFERRED` is set
only on modules, top-level functions, descriptors and immortal objects, that nothing marks a
`PyType_FromSpec` heap type or its instances as deferred, and that the real cause was queued BRC
decrements that an eval-loop checkpoint would flush.

**Measured, both of those are partly wrong, and the practical conclusion belongs to the first.**
Dumping the proxy type's header on the free-threaded build, before and after 100 instantiations:

| | `ob_tid` (+0) | `ob_gc_bits` (+11) | `ob_ref_local` (+12) | `ob_ref_shared` (+16) |
|---|---|---|---|---|
| fresh type | `0x16d9b70e0` | `0x41` | 2 | `0x3ffffffffffffffd` |
| 100 instances alive | `0x16d9b70e0` | `0x41` | 2 | `0x3ffffffffffffffd` |
| after `drainPendingReleases()` | `0x16d9b70e0` | `0x41` | 2 | `0x3ffffffffffffffd` |
| after `PyGC_Collect()` | `0x16d9b70e0` | `0x41` | 2 | `0x400000000000018d` |
| after 100 `Py_DecRef` → 100 `tp_dealloc` | `0` | `0x41` | 0 | `0x4000000000000007` |

Reading that off:

- `ob_gc_bits == 0x41` is `_PyGC_BITS_TRACKED | _PyGC_BITS_DEFERRED`. The heap type **is** marked
  deferred.
- `ob_ref_shared == 0x3ffffffffffffffd` is `(0x0fffffffffffffff << 2) | 1`: a shared count of
  `2^60 - 1` = `PY_SSIZE_T_MAX / 8`, which is the deferred sentinel, with the
  `_Py_REF_MAYBE_WEAKREF` flag. Two independent signals agreeing.
- **Creating 100 instances changed nothing.** `PyType_GenericAlloc` increments the type through
  `_Py_INCREF_TYPE`, which is a no-op for a deferred type on its owning thread. The header is
  bit-identical.
- **An eval-loop checkpoint changed nothing either.** This is the decisive one: the proposed fix
  does not work, and cannot, because there is no queued decrement to merge. The increments were
  never made in the first place.
- **A collection is what makes deferred references real.** `ob_ref_shared` rose by exactly
  `100 << 2`. This is `gc_free_threading.c` converting deferred references into counted ones as it
  walks.
- **And then `tp_dealloc` gives back exactly 100.** Accounting for the flag bits moving from
  `_Py_REF_MAYBE_WEAKREF` (1) to `_Py_REF_MERGED` (3) and `ob_ref_local` merging to zero, the total
  goes from `1152921504606847077` back to `1152921504606846977` — the pre-instantiation value,
  exactly.

So the original ROADMAP claim was right about the *mechanism* (heap types are deferred) and wrong
about the *consequence* (that the invariant does not exist and cannot be checked). The invariant
holds on both builds. Free-threaded it is merely not **observable** until a collection has
materialised the deferred references. The later trace was right that instances are ordinary
reference-counted objects — but the test reads the *type's* count, not an instance's, so that
correction did not touch the failing assertion.

`CycleCollectionTest` therefore takes a `PyGC_Collect()` on either side of its instantiation loop,
free-threaded only. The assertions are unchanged: the same "rises by exactly 100" and "returns to
exactly where it started" are asserted on both builds, and both now hold.

### What this means for `abi3t`

The conclusion drawn from the wrong premise — "so `abi3t` making `PyObject` an incomplete type
would sink this test" — does not follow either. The test does need to know the header layout, which
is a real cost of a direct probe and the one place in this repo that reads inside a `PyObject`. But
that is a property of the probe, not of the invariant, and it would be equally solvable by counting
through `sys.getrefcount` at the price of the argument's own temporary reference. The library
proper stays clean: `ProxyTypeFactory` writes only into memory it obtained from
`PyObject_GetTypeData`.

## 4. What this repo does about §1 and §2

`Python3.drainPendingReleases()` reaches a checkpoint the cheapest way there is: it calls a cached,
already-compiled, zero-argument Python function whose body is `pass`, through
`PyObject_CallNoArgs`. That is one `RESUME`, hence one `_CHECK_PERIODIC`, hence one
`_Py_HandlePending` — one BRC merge, one QSBR sweep, and one scheduled collection if one was
scheduled.

It is deliberately **not** `PyRun_SimpleString("pass")` or `exec("pass")`:

- `PyRun_SimpleString` runs `PyErr_Print()` on failure, which clears the error indicator. This repo
  has twice had unrelated tests die because a helper wiped an indicator its caller had not read
  yet; `Python3.exec` is built on `PyRun_String` for exactly that reason.
- `exec("pass")` recompiles a module on every call — 23× the cached call, §5.

`withGIL` takes one automatically at its outermost entry, behind two gates: at most one per
`Python3.autoDrainInterval` outermost scopes, and skipped entirely unless `ReleaseCounter.released`
has moved since the last one. It defaults to 32 on free-threaded builds and 0 (off) otherwise.
`reachEvalCheckpointHoldingGIL` additionally declines while `PyErr_Occurred()` is non-null, for the
same error-indicator reason.

It is not taken from the cleaner. The queue that needs merging belongs to the thread that *owns*
the object; a cleaner owns nothing, so a checkpoint there would run Python on a cleaner thread (the
ROADMAP §1 deadlock) in order to drain an empty queue.
`EvalCheckpointTest.testCleanerActivityAloneTakesNoCheckpoint` pins the suppression.

`PyGC_Collect` is the one Stable ABI function that reclaims on behalf of *another* thread, because
`gc_collect_internal` stops the world and walks every thread state, calling
`_PyObject_MergePerThreadRefcounts` and `merge_queued_objects` for each. It costs a heap walk
rather than a queue pop — §5 — and, as §3b shows, it is also the only thing that materialises a
deferred reference.

## 5. Measurements

`EvalCheckpointTest.testCheckpointCostAgainstTheAlternatives`, free-threaded desktop build, macOS
arm64. The test asserts the *ordering*, not the absolute numbers: the ordering is a property of the
design and would break the moment the checkpoint started compiling or allocating.

| | ns/op | |
|---|---:|---|
| `withGIL { }` — attach and detach, nothing else | 170.51 | the floor |
| `Python3.drainPendingReleases()` | 302.70 | the checkpoint, ~132 ns over the floor |
| `withGIL { Py_MakePendingCalls() }` | 197.34 | cheap, and does not merge |
| `Python3.exec("pass")` | 7 037.04 | 23× the checkpoint — it recompiles |
| `withGIL { PyGC_Collect() }` | 224 625.00 | 742× the checkpoint — heap walk, but drains every thread |

At the default `autoDrainInterval = 32` the automatic checkpoint amortises to about 4 ns on a
~170 ns outermost scope, and a workload that drops no wrappers skips it on a field compare.

`PyGC_Collect` being ~740× the checkpoint is also the cost `CycleCollectionTest` pays for the two
collections §3b requires: ~0.45 ms for the whole test, free-threaded only.

## 6. What is still true, and what to watch

- The scheduled-GC half of §1 affects **both** builds. Nothing in this repo currently depends on
  allocation-driven collection, and `autoDrainInterval` defaults to 0 with the global lock, so the
  default configuration still never runs a generational collection on its own. That is a choice,
  not an oversight, and reversing it is one assignment.
- `EvalCheckpointTest` asserts that `Py_MakePendingCalls` does *not* merge. If CPython ever changes
  its mind, that test fails and says so.
- The header layout in §3a is a private detail read in exactly one place, and that read is guarded
  by an assertion that fails loudly if the offsets stop being right. It already has, twice.
- The deferred sentinel gives roughly `2^60` of headroom, so nothing here is at risk of the count
  actually reaching zero. But note the asymmetry `ProxyTypeFactory` lives with: its hand-written
  `tp_dealloc` releases the type through the public `Py_DecRef`, which is deferred-unaware, while
  the matching increment went through the deferred-aware `_Py_INCREF_TYPE` and did not happen. That
  balances out once a collection has materialised the references — measured above — but it is
  balanced by the collector, not by the two calls.

## 7. GC Accumulation and Reentrancy Measurement

Measurements taken to verify the consequences of §1 and the safety of the checkpoint confirm:

- **Cyclic Garbage Accumulation (GIL Build)**: A workload creating 10,000 cyclic object groups entirely through the C API (never running a Python bytecode evaluation loop) with `autoDrainInterval = 0` accumulates **20,000+** cyclic garbage objects indefinitely. Because no evaluation loop runs, `_Py_ScheduleGC`'s scheduled bit is never checked by `_CHECK_PERIODIC`, and cyclic GC never occurs.
- **`autoDrainInterval` Effectiveness (GIL build)**: When running the exact same C API workload but with `autoDrainInterval = 32`, `python-multiplatform` evaluates a dummy function (`__pmp_eval_checkpoint__`) to force the evaluation loop to run periodically. This allows `_CHECK_PERIODIC` to see the scheduled bit, trigger `_Py_RunGC()`, and reclaim the cyclic garbage (leaving ~1,970 of ~20,000). **This result is specific to the build with the global lock.** Free-threaded, the same checkpoints frequently reclaim nothing at all — §8.
- **`__del__` Reentrancy Risk**: Executing `PyGC_Collect()` or processing a checkpoint can invoke `__del__` methods. A scenario where `__del__` directly calls back into Kotlin (via Panama upcalls) was executed (`GCSchedulingMeasurementTest.testReentrancyDuringCheckpoint`). The reentrancy is safe on the GIL build: it does not deadlock, it does not loop infinitely, and it successfully executes the Kotlin upcall while the garbage collection is in progress.

## 8. Free-threaded: the checkpoint fires and reclaims nothing, and it is not the scope shape

Everything in this section was measured here, on `desktopTest`, macOS arm64, CPython 3.14.7, both
builds. The workload is `GCSchedulingMeasurementTest.buildAndDropCycles`: 10,000 unreachable
`list` cycles, 20,000 objects, built entirely through the C API.

### 8a. The probe was holding the evidence

The measurement chain used to be written inline as

```kotlin
gc.getAttr("get_objects").invoke().getAttr("__len__").invoke().toString().toInt()
```

which closes none of its temporaries. `gc.get_objects()` returns a list holding **a strong
reference to every tracked object in the interpreter**, so a leaked one roots the very garbage the
next measurement is trying to see.

That leak is where §7's earlier free-threaded conclusion came from — the claim that "a trailing
explicit `gc.collect()` finds nothing further to reclaim, so the residue is not simply garbage
waiting for the next checkpoint," and the inference from it that free-threaded CPython was failing
to *reclaim* something. With the temporaries released, **an explicit `gc.collect()` reclaims the
residue essentially completely, on both builds, in every configuration tried**: 60+ repetitions,
post-collection residue between −13 and +2 objects out of ~20,000.

So the residue is ordinary collectable cyclic garbage. What differs free-threaded is what makes the
collector *run*, not what it can take. `GCSchedulingMeasurementTest` now asserts this on both
builds (`assertResidueIsOrdinaryCollectableGarbage`), which is the opposite of what the previous
text asserted about it.

### 8b. Rejected: "per-call `PyGILState_Ensure`/`Release` destroys the scheduled GC bit"

The standing hypothesis was that because free-threading keeps `eval_breaker` per thread state, an
FFI wrapper that attaches and detaches around every C API call destroys and recreates the thread
state, discarding the scheduled GC bit with it — and that widening the scope would therefore fix
collection.

**Measured and rejected.** Holding the checkpoint count fixed at 2,000 explicit
`Python3.drainPendingReleases()` calls (`autoDrainInterval = 0`, one drain every 5 rounds) and
varying *only* the enclosing scope, free-threaded, residue out of 20,000:

| enclosing scope | reps | residue |
|---|---:|---|
| none — one `withGIL` per C API call | 5 | 20,000 ×5 |
| one per round (~12 C API calls) | 5 | 20,000 ×5 |
| one per 100 rounds | 10 | 20,000 ×10 |
| one for the whole 10,000-round workload | 10 | 11,562 / 1,280 / 1,280, then 20,000 ×7 |

Widening the scope to 100 rounds — which gives the thread state a long life and hundreds of
allocations before it is destroyed — changes nothing whatsoever. The hypothesis predicts the
opposite, so it is wrong.

The single-scope row does not rescue it either: **it is bimodal within one JVM.** Those ten
repetitions are consecutive and identical; it reclaimed on the first three and then never again.
The configuration latches, which is a property of accumulated interpreter state, not of the scope
shape the call happens to be made under.

With the automatic checkpoint instead (`autoDrainInterval = 32`), free-threaded:

| enclosing scope | checkpoints taken | residue |
|---|---:|---|
| none — one `withGIL` per C API call | 1,875 | 20,000 ×5; also 7,427 and 11,523 in two single-repetition JVMs |
| one per round | 312–313 | 20,000 ×5 |
| one per 100 rounds | 3 | 20,000 ×5 |
| one for the whole workload | **0** | 20,000 ×3 |

The last row is a property of the gate, not of the collector: a single outermost scope decrements
`checkpointCountdown` once, so at interval 32 no automatic checkpoint is ever reached. An embedder
that wraps a long batch in one `withPython` therefore gets *fewer* automatic checkpoints, not more.

### 8c. The contrast that is actually there

The same sweep on the build with the global lock is stable and near-complete in **every** shape:

| configuration | checkpoints | residue |
|---|---:|---|
| per C API call, `autoDrainInterval = 32` | 1,875 | 1,964–1,972 |
| per C API call, 2,000 explicit drains | 2,000 | 1,920 ×5 |
| one per 100 rounds, 2,000 explicit drains | 2,000 | 1,920 ×5 |
| whole workload in one scope, 2,000 explicit drains | 2,000 | 1,920–1,962 |
| one per round, `autoDrainInterval = 32` | 312–313 | 1,556–1,616 |
| one per 100 rounds, `autoDrainInterval = 32` | **3** | 1,600–3,200 |

Three checkpoints reclaim ~90% on the GIL build. Two thousand reclaim nothing free-threaded. That
gap — not the scope shape, which is irrelevant on both builds — is the whole finding.

### 8d. Answered: the checkpoint is fine, the collector asks a second question

The question §8c leaves open is why a checkpoint that demonstrably runs
(`CheckpointCounter.reached` climbs by an identical, deterministic amount every run) triggers no
collection free-threaded, and why the same configuration latches from "reclaims" to "never
reclaims" inside one JVM.

CPython v3.14.7 was fetched to `/Volumes/macMini/cpython-src` (not vendored here) and read. **The
checkpoint is not where this goes wrong.** The checkpoint reads the scheduled bit exactly as §1
describes, on both builds. What differs is that free-threaded, `_Py_RunGC` then asks a *second*
question, and it is that one that answers no.

#### The asymmetry, in four lines of source

| | global lock (`Python/gc.c`) | free-threaded (`Python/gc_free_threading.c`) |
|---|---|---|
| schedules on | `generations[0].count > threshold`, and nothing else (`gc.c:1866`) | `gc_should_collect` (`:2153`) |
| re-checked when the checkpoint runs it | `gc_select_generation` — generation 0 on count alone (`gc.c:1258`) | `gc_should_collect` **again** (`:2328`) |
| process-memory gate | **none**; `gc.c` has no `last_mem` field and never calls `get_process_mem_usage` | `gc_should_collect_mem_usage` (`:2080`) |
| `long_lived_total / 4` | oldest generation only (`gc.c:1300`) | **every** generation-0 decision (`:2131`) |

`gc_should_collect` (`gc_free_threading.c:2117`) is:

```c
if (count <= threshold || threshold == 0 || !gc_enabled) return false;   // :2123
if (gcstate->old[0].threshold == 0) return true;                        // :2126
if (count < gcstate->long_lived_total / 4) return false;                 // :2131
return gc_should_collect_mem_usage(gcstate);                            // :2136
```

and `gc_should_collect_mem_usage` (`:2080`) is, in substance:

```c
if (deferred > threshold * 40) return true;                             // :2089
if ((footprint - last_mem) > Py_MAX(last_mem / 10, 128)) return true;   // :2100
young.count = 0; deferred_count += young.count;  return false;          // :2109-2113
```

`footprint` is `get_process_mem_usage()`, which on macOS is
`task_info(TASK_VM_INFO).phys_footprint` (`:2010`) — **the whole process**, i.e. the JVM.
`last_mem` is written in exactly one place, after a collection (`:2301`).

So, in a JVM-hosted embedder: 20,000 small Python lists cannot move a JVM's physical footprint by
the required `last_mem / 10`, the gate returns false, and on the way out it **zeroes `young.count`
into `deferred_count`** — which means the count has to climb from zero to 2,000 again before the
question is even asked again. The only escape is `deferred_count > threshold * 40` = 80,000
container allocations at the default threshold, and this workload makes ~20,000. Checkpoints are
irrelevant to all of it; the bit gets read and `gc_collect_main` declines at `:2328`.

**This is also the latch.** `last_mem` only moves when a collection runs, and it moves to the
whole process's current footprint. Early in a JVM's life the footprint is still climbing steeply,
so the gate opens and collections happen; once a collection records the JVM's settled footprint,
the bar becomes a tenth of a large number that Python-side allocation cannot move, and it is shut
one-way. Nothing about the scope shape is involved, which is consistent with §8b rejecting it.

#### Confirmed by experiment, not by reading

The repo has been wrong before about causes derived from source reading, so each claim above was
turned into a prediction that could fail. All of it is in
`python-multiplatform/src/desktopTest/.../ref/FreeThreadedGCGateTest.kt`.

**Which gate declines** — `gc_should_collect_mem_usage`'s failing branch zeroes `young.count`
(`:2109`); the `long_lived_total/4` branch (`:2131`) does not. So the shape of `gc.get_count()[0]`
across the workload tells them apart. Free-threaded it is a **sawtooth**
(`[1728, 1488, 976, 464, 2464, 1488, 976, 464, 2464, 1488]`) with 0 collections and residue 20,000:
the memory gate, repeatedly.

> This probe took two attempts, and the first one was wrong in the direction that would have
> produced a confident false answer. `gc_get_count_impl` (`Modules/gcmodule.c:215`) flushes the
> per-thread allocation buffer and sets `gc->alloc_count = 0`, and `record_allocation` only
> consults `gc_should_collect` when that buffer reaches `LOCAL_ALLOC_COUNT_THRESHOLD` = 512
> (`:69`, `:2147`). Sampling every 500 allocations therefore stopped the collector from ever being
> asked, and produced a perfectly linear trace (`226, 726, 1226, … 19726`) that reads exactly like
> proof of the *other* hypothesis. Sampling every 2,000 allocations instead gives the sawtooth.

**The one-line falsifier** — `:2126` says `old[0].threshold == 0` short-circuits the whole chain.
Nothing in `gc.c` reads that field while scheduling. So `gc.set_threshold(2000, 0, 0)` — a setting
about *generation 1* — must switch *generation 0* collection on free-threaded and do nothing at all
with the global lock. Run A/B/A in one JVM so the latch cannot explain the difference; residue out
of 20,000, and the number of collections that actually ran:

| arm | free-threaded | global lock |
|---|---|---|
| `(2000, 10, 10)` | 20,000 — 0 collections | 1,968 — 9 collections |
| `(2000, 0, 0)` | **1,318 — 8 collections** | 1,966 — 5 collections |
| `(2000, 10, 10)` again | 20,000 — 0 collections | 1,970 — 9 collections |

**The `deferred_count` arithmetic** — the escape bar is `40 × threshold0`, and `deferred_count`
grows *only* in the memory gate's failing branch. Against a ~20,000-allocation workload that
predicts no collection at threshold0 = 2000 (bar 80,000) or 500 (bar 20,000), and collection at
100 (bar 4,000). Free-threaded: 20,000 / 20,000 / **1,466 with 3 collections**. The same sweep with
the global lock just scales smoothly with the threshold (9 / 36 / 172 collections), because there
is no such bar. This also proves the `long_lived_total/4` gate at `:2131` is being *passed* —
nothing else feeds `deferred_count`.

**The latch, driven directly** — if the gate is whole-process footprint, then faulting in 400 MB of
ballast partway through the workload must open it, while changing no threshold, allocating no extra
Python container and touching nothing the collector tracks. Dropping the ballast must shut it
again. Residue out of 20,000:

| arm | free-threaded | global lock |
|---|---|---|
| baseline | 20,000 — 0 collections | 1,972 — 9 collections |
| 400 MB faulted in midway | **9,754 — 1 collection** | 1,988 — 9 collections |
| ballast dropped | 20,000 — 0 collections | 1,968 — 9 collections |

Reproduced identically to the object in three consecutive JVMs. **JVM memory growth, and nothing
else, collected Python cycles.** That is the finding: on a free-threaded build the Python cyclic
collector's schedule is a function of the host process's memory, which for an embedder is not
Python's memory at all.

One experiment did **not** confirm what it was built to test and is recorded as such: scaling the
workload (10,000 / 30,000 / 60,000 cycles) at default thresholds to cross the 80,000 bar gave
1 / 2 / 2 collections, but the 10,000-cycle arm should have given 0. It ran first in its JVM, while
the footprint was still climbing, so the memory gate — the very thing being controlled for —
supplied the collections. The scaling prediction is untested, not confirmed.

#### What this changes

- The free-threaded case now has a **bound**, where §8 could only say the residue was bimodal and
  refuse to assert one. `FreeThreadedGCGateTest` holds free-threading to the same
  "reclaims more than half" bound as the GIL build *once the gate the source names is open*, and
  asserts the matched no-op on the GIL build so that a CPython change to either gate fails the test
  and says which one.
- `GCSchedulingMeasurementTest.measureCyclicGarbageWithAutoDrain` still asserts no bound
  free-threaded in the default configuration, and that is now a documented consequence rather than
  an unknown: at default thresholds the outcome depends on the JVM's footprint history, so it is
  genuinely not boundable, and the two arms that reclaimed in §8b's single-scope row were the JVM
  still warming up.
- §8b's rejection stands and is now explained: scope shape cannot matter because the gate never
  looks at thread state.

#### The advice does not change

An embedder can open the gate deliberately — `gc.set_threshold(t0, 0, 0)` restores count-driven
collection free-threaded, and a small `threshold0` lowers the `40 × threshold0` bar — but both
trade throughput for it, and both are tuning of CPython internals that the Stable ABI does not
promise. The supported way to collect cycles in an embedder that runs no Python bytecode is still
the one that works identically on both builds, documented in §4: `PyGC_Collect()`, at a cost
proportional to the heap.
