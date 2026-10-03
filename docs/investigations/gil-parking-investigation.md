# Enabling `PyEval_SaveThread()` in `Python3.initialize()`

Question: why did parking the initialising thread's state (so other threads can attach) appear to
crash `desktopTest`?

## Conclusion

**The investigation's own diagnosis was incomplete, and parking is on.** What this document
established from CPython 3.14.0 source (read-only, no code run) holds, but it did not find the
cause, and the cause turned out not to be in the runtime:

- `PyGILState_Ensure` on the initialising thread after `PyEval_SaveThread()` does **not** create a
  second `PyThreadState`. `PyEval_SaveThread` detaches the state and releases the GIL but leaves the
  GILState TSS slot bound (CPython's own comment in `pystate.c`: "We do not unbind the gilstate
  tstate here. It will still be used in `PyGILState_Ensure()`"). `Ensure` finds the same state,
  `has_gil` is false, so it calls `PyEval_RestoreThread` on it; `gilstate_counter` goes 1 → 2 and
  `Release` brings it back and re-parks. Park/re-enter on one thread is the supported idiom
  (`Py_BEGIN/END_ALLOW_THREADS` does it).
- `PyGILState_Release` is never handed an invalid state in this flow.
- The thread is the same: Gradle runs the whole suite on one `"Test worker"` thread, which is also
  where `Python3.initialize()` runs.

**The actual cause (ROADMAP §1, "Closed"):** several tests wrapped a pointer they had only been
*lent* with `borrowed = false`, so two wrappers owned one pointer and both decremented it. That
double-free corrupted CPython's free lists and surfaced later in an unrelated test. With parking
off, the cleaner thread blocked forever on the GIL, so the extra decrements queued and never ran,
which is why enabling parking appeared to *cause* the crash. The `SIGSEGV` in
`_TAIL_CALL_DICT_MERGE` under `PyImport_ImportModule("sys")` was a victim of the corruption, not
the fault site. Two earlier attempts read the evidence as "parking is broken" and reverted.

The two candidates this document left open (stale per-thread C-stack recursion limits calibrated
once at first attach; Python-level import bookkeeping on a cache hit) were never confirmed and,
given the cause above, are not needed to explain anything. The stack-limit staleness is a real
property of reusing one `PyThreadState` (`_Py_InitializeRecursionLimits` runs only while
`c_stack_hard_limit == 0`) but no failure has been attributed to it.

**Status: acted on, parking enabled unconditionally.**
`python-multiplatform/src/commonMain/kotlin/python/multiplatform/ffi/Python3.kt`:
`initialize()` calls `PyEval_SaveThread()` after `Py_Initialize()` (guarded only by
`mainThreadState == null`); `finalize()` reclaims it with `PyEval_RestoreThread`. Verified by
reading the file at `5db4168c`.

Discrepancy to resolve elsewhere: `docs/SPEC.md` C-4 says "Releasing the GIL after initialisation
is not enabled." That contradicts the code above and ROADMAP §1. This document does not edit SPEC.

Related rule that survives: every C API call must be inside `withGIL{}`/`withPython{}`. Free-threaded
builds do not remove it (a thread must still be attached before touching any object, `Py_IncRef`
included). `Python3.kt` documents this at the `PyEval_SaveThread()` call site.

## Numbers

None. This was a source-reading investigation. The only quantitative record is the ROADMAP §1
test counts when parking was enabled: desktop 164, iOS 157, zero failures (a historical count; the
suites have grown since).

## How to reproduce / guard

The bisection experiment proposed here (park, re-enter on the same thread with bare re-entry → pure
C call → `PyImport_ImportModule("sys")` → `PySys_GetObject` + `PyDict_GetItemString`) exists as
`python-multiplatform/src/commonTest/kotlin/python/multiplatform/ffi/GilParkingTest.kt`
(`parkThenReenterSameThreadIsolatesTheFailure`). It asserts only that nothing crashes (SPEC C-4).
Run alone on a quiet tree:

```bash
./gradlew :python-multiplatform:desktopTest --tests 'python.multiplatform.ffi.GilParkingTest' \
    --console=plain > .tmp/gilparking.log 2>&1; echo "EXIT=$?"
```

Tests nearest the real cause (double release / ownership; named, not re-run here): `commonTest/.../ref/DoubleReleaseTest.kt`,
`OwnershipLeakTest.kt`, `GCLeakTest.kt`.

## Open

- Which CPython version introduced the attach/detach vocabulary and the C-stack-address recursion
  guard was not verified (it reads as the PEP 703 rewrite).
- iOS: `GCLeakTest`'s earlier simulator failure was not GIL-related (cleaners ran and reached
  `Py_DecRef`: 101001 of 101002). ROADMAP §1 reports the iOS suite green (157) once parking was
  enabled; this document does not explain the earlier iOS symptom.

The full original, with the CPython source excerpts, the same-thread argument and the experiment
sketch, is
[`docs/archive/investigations/gil-parking-investigation.md`](../archive/investigations/gil-parking-investigation.md).
