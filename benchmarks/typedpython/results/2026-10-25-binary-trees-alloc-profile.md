# binary-trees compiled: where the time goes (issue #125)

Python 3.15.0rc1 free-threading build (python-multiplatform-ksp/.venv), macOS arm64, n=16.
Load (uptime) at the runs: 11.1 to 13.4 (1 min). The machine was busy, so wall times are noisy; the
shares below come from one `sample` run and are the useful part. One run each, not a statistic.

## Commands

    # compile as benchmarks/typedpython/run.py does (marker line prepended, pipeline.compile_module)
    PYTHONPATH=python-multiplatform-ksp/src/main/python $P -c "...pipeline.compile_module(src, out)"
    # compiled functions: make, check. Skipped: Node.__init__ (dunder), main (policy/cost #150)
    $P .tmp/bt/run.py binary_trees <ext.so> {base|nogc|freeze}   # RUN_SNIPPET plus gc.disable() / gc.collect();gc.freeze()
    sample <pid> 2 -file .tmp/bt/sample.txt                       # 1 ms, 1668 main-thread samples

## Wall time, n=16 (diagnostic only)

| Run | seconds |
|---|---|
| CPython (interpreted) | 2.69 |
| compiled, gc on | 2.95 (51 gen-0 collections, 8.76M candidates, gc.get_stats duration 0.366 s = 12%) |
| compiled, gc.disable() | 2.31 (-22%) |
| compiled, gc.collect(); gc.freeze() | 2.73 (-7%) |

In this loaded run compiled was not faster than CPython at all; the issue's 1.7x came from interleaved runs.

## Self time by category (sample, 1637 top-of-stack samples)

| Category | Samples | Share | Functions |
|---|---|---|---|
| thread state / TLS / recursion guard | 511 | 31.2% | _tlv_get_addr 363, PyThreadState_GetFrame 83, Py_EnterRecursiveCall, PyThreadState_Get, PyModule_GetState, stubs |
| compiled code | 428 | 26.1% | tp_impl_check 165, tp_impl_make 120, tp_release 83, tp_enter_call 47 |
| dealloc | 281 | 17.2% | subtype_dealloc 179, MiFree, GC_Del, MergeZeroLocalRefcount, subtype_traverse |
| alloc + slot init | 238 | 14.5% | PyMember_SetOne 133, PyType_GenericAlloc 50, MiMalloc 31, memset, tp_new_fixed |
| slot read | 84 | 5.1% | PyMember_GetOne |
| gc collect (self) | 84 | 5.1% | gc_propagate_alive, update_refs, scans |

Inclusive GC (frames under _Py_HandlePending -> gc_collect_main): 135 samples, 8.2%.
Alloc + dealloc + gc together: about 37% self time. The hypothesis (alloc/dealloc/GC dominate) is only
partly right: the largest single block is thread-state access (_tlv_get_addr is thread-local lookup on
the free-threaded build). _tlv_get_addr callers (inclusive attribution): PyThreadState_Get 57,
tp_impl_check 54, tp_impl_make 44, Py_EnterRecursiveCall 44, PyMember_SetOne 38, Py_GetRecursionLimit 26,
PyType_GenericAlloc 25, MiMalloc 21. Compiled code itself calls the recursion guard / tstate lookups
(tp_enter_call, tp_impl_check), so some of that block is the compiled code's own overhead, not CPython's.

Caveats: single 2 s sample; free-threaded build only (GIL build not measured); TLS share is specific to
this build; categories are my grouping of symbols.

## Options that keep observable behaviour (Task 2)

Expected gains are upper bounds from the shares above, not measured.

| Option | Expected gain | Observable? |
|---|---|---|
| Cache tstate once per compiled call (pass it down / read once in tp_enter_call) instead of repeated PyThreadState_Get, Py_EnterRecursiveCall, Py_GetRecursionLimit | up to ~10 to 25% (TLS + guard share 31%, part is CPython's own) | No |
| Write slots of a fixed-layout New directly (tp_new_fixed already does) instead of PyMember_SetOne; read via offset instead of PyMember_GetOne | ~5 to 10% (SetOne 133 + GetOne 84 of 1637) | No for N-11 fixed layout; must keep the descriptor path for subclasses |
| Skip redundant zeroing (memset then every slot written) | ~1% (memset 17) | No |
| Cheaper dealloc for N-11 classes: type-specific tp_dealloc clearing known slots instead of generic subtype_dealloc | up to ~10% (subtype_dealloc 179 + traverse 41) | Mostly no; must keep weakref/finalizer/trashcan semantics and subclass fallbacks |
| Per-type freelist (reuse freed Node memory) | ~5 to 10% (MiMalloc/MiFree/GenericAlloc ~110) | Yes: id() reuse is more predictable, and freed objects must still leave gc tracking. Needs a decision |
| Batching GC accounting / raising threshold for these types | up to 8 to 22% (inclusive GC 8%, gc.disable 22%) | Yes: changes when collections run and gc.get_objects/cycle timing. Needs a decision. Not implemented |
| Untracking Node (no GC header) | the rest of GC share | Yes: gc.get_objects, cycles (leak). Out of scope, needs a decision; overlaps #143 (virtual objects, N-12), not duplicated here |

gc.freeze() as a diagnostic gained only 7%, so the old-generation scan is a minor part; the cost is the
young-generation work triggered by allocation count.
