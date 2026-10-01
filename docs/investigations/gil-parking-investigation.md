# Why enabling `PyEval_SaveThread()` in `Python3.initialize()` crashes desktopTest

Research only. No source in this repo was changed to produce this document; all CPython claims
below are cited from `python/cpython` at tag `v3.14.0` on GitHub, fetched read-only.

## 0. Summary and confidence

**Ruled out, with source evidence:** the failure is not "`PyGILState_Ensure` creates a second,
competing `PyThreadState`" on the initialising thread. It reuses the *same* `PyThreadState*` that
`Py_Initialize()` created. This is CPython's documented, intended mechanism for exactly this
pattern (park, then re-enter from a C thread later) — see §2 and §3.

**Established, with source evidence, but not sufficient by itself:** `PyEval_SaveThread()` leaves
the calling OS thread's slot in the GILState TSS (`autoTSSkey`) pointing at the parked
`PyThreadState`. CPython's own comment in `pystate.c` says so explicitly: "We do not unbind the
gilstate tstate here. It will still be used in `PyGILState_Ensure()`." Reusing the same,
still-registered thread state is a supported idiom (it's what `Py_BEGIN_ALLOW_THREADS` /
`Py_END_ALLOW_THREADS` do on every existing embedding), so this fact **describes the mechanism
correctly but does not, by itself, explain a segfault.** Something else that the reuse *doesn't*
restore is the more likely proximate cause, and I could not pin that down from source reading
alone. See §5 for the two candidates and their weaknesses.

**Confidence: medium on the mechanism (thread-state identity across the park/re-enter boundary),
low on the exact reason it becomes memory-unsafe.** The minimal experiment in §6 is designed to
close that gap cheaply, without running the full suite.

## 1. Same thread, established rather than assumed

`PythonTestFixture.available` is a Kotlin `by lazy` property; `Python3.initialize()` runs on
whichever thread first touches it. `desktopTest`'s Gradle configuration
(`python-multiplatform/build.gradle.kts:724`) sets no `maxParallelForks`, no
`junit.jupiter.execution.parallel.*` property exists anywhere in the repo, and no
`junit-platform.properties` exists. Gradle's `Test` task therefore runs the whole suite
sequentially inside one worker JVM, on the JVM's single test-execution thread — which is named
`"Test worker"` by Gradle, matching the thread name in today's crash report verbatim. So
`Python3.initialize()` and `EmbedApiLowLevelTest.importReturnsANewReferenceAndMissingModulesReportFailure`
run on the **same OS thread**, in the same process, with nothing else attaching in between.

This directly determines which branch of `PyGILState_Ensure()` fires later (§3): the
"already-registered" branch, not the "first time on this OS thread" branch.

## 2. What `PyEval_SaveThread()` does to the calling thread's state

Source (`Python/ceval_gil.c`, v3.14.0):

```c
PyThreadState *
PyEval_SaveThread(void)
{
    PyThreadState *tstate = _PyThreadState_GET();
    _PyThreadState_Detach(tstate);
    return tstate;
}
```

`_PyThreadState_Detach` (`Python/pystate.c`) calls `detach_thread(tstate, _Py_THREAD_DETACHED)`:

```c
static void
detach_thread(PyThreadState *tstate, int detached_state)
{
    assert(_Py_atomic_load_int_relaxed(&tstate->state) == _Py_THREAD_ATTACHED);
    assert(tstate == current_fast_get());
    if (tstate->critical_section != 0) {
        _PyCriticalSection_SuspendAll(tstate);
    }
    ...
    tstate_deactivate(tstate);
    tstate_set_detached(tstate, detached_state);
    current_fast_clear(&_PyRuntime);
    _PyEval_ReleaseLock(tstate->interp, tstate, 0);
}
```

and `tstate_deactivate`:

```c
static inline void
tstate_deactivate(PyThreadState *tstate)
{
    ...
    tstate->_status.active = 0;

    // We do not unbind the gilstate tstate here.
    // It will still be used in PyGILState_Ensure().
}
```

So: the `PyThreadState` object itself is **not destroyed**. It is marked inactive/detached, the
GIL is released, and the per-OS-thread "current thread state" pointer
(`current_fast_get`/`current_fast_clear`, a `thread_local` variable) is cleared to `NULL`. What is
**not** touched anywhere in this path: `tstate->gilstate_counter`, and the GILState TSS slot
(`gilstate_tss` / historically `autoTSSkey`) that `PyGILState_Ensure` consults. That slot is a
*separate* piece of per-OS-thread storage from the "current thread state" pointer, and CPython's
own comment above states outright that detaching deliberately leaves it alone.

By the end of `Py_Initialize()` (before the parking call), the calling thread's GILState TSS slot
is already bound to the main thread's `PyThreadState`, with `gilstate_counter == 1` — confirmed by
`_PyGILState_SetTstate()`'s debug assertions (`Python/pystate.c`):

```c
assert(runtime->gilstate.autoInterpreterState == tstate->interp);
assert(gilstate_tss_get(runtime) == tstate);
assert(tstate->gilstate_counter == 1);
```

(`bind_gilstate_tstate` is what performs that binding, called from `tstate_activate` — i.e. from
inside `_PyThreadState_Attach`/`PyEval_RestoreThread` — the first time the main thread is
attached during startup.)

## 3. What `PyGILState_Ensure()` then does on that same thread

Source (`Python/pystate.c`, v3.14.0):

```c
PyGILState_STATE
PyGILState_Ensure(void)
{
    ...
    PyThreadState *tcur = gilstate_tss_get(runtime);
    int has_gil;
    if (tcur == NULL) {
        tcur = new_threadstate(runtime->gilstate.autoInterpreterState,
                               _PyThreadState_WHENCE_GILSTATE);
        ...
        bind_tstate(tcur);
        bind_gilstate_tstate(tcur);
        tcur->gilstate_counter = 0;
        has_gil = 0;
    }
    else {
        has_gil = holds_gil(tcur);
    }
    if (!has_gil) {
        PyEval_RestoreThread(tcur);
    }
    ++tcur->gilstate_counter;
    return has_gil ? PyGILState_LOCKED : PyGILState_UNLOCKED;
}
```

Because the GILState TSS slot on this OS thread is still bound (§2), `tcur` is **not** `NULL`, so
the "new thread" branch (which would create a genuinely second `PyThreadState`) is **not** taken.
`tcur` is the exact same `PyThreadState*` `Py_Initialize()` created. `holds_gil(tcur)` compares
`tcur` against `current_fast_get()`, which is `NULL` after parking, so `has_gil = 0`, and
`PyEval_RestoreThread(tcur)` re-attaches that same object (`current_fast_set`, GIL re-acquired,
`tstate_activate` — which is a no-op on the already-bound gilstate slot). `gilstate_counter` goes
1 → 2. `PyGILState_Release()` mirrors this exactly: decrements 2 → 1, and because the saved
`oldstate` was `PyGILState_UNLOCKED`, it re-parks with another internal `PyEval_SaveThread()` call.
This is self-consistent and, cycled repeatedly, returns to the same `gilstate_counter == 1`,
detached baseline every time. **Nothing in this path frees, reallocates, or otherwise clears the
frame stack, exception state, or recursion bookkeeping that the object already carried** — it is
literally the same object, reused.

This rules out "two thread states corrupting each other's interpreter frame stack" as the
mechanism, at least on the initialising thread — there is only ever one `PyThreadState` in play
here. It does not, by itself, identify what *does* go wrong.

## 4. Whether `PyGILState_Release` is handed an invalid state

No. Traced above: the `PyGILState_STATE` value threaded through `withGIL`'s `tState.state` /
`PyGILState_Release` call is always the value `PyGILState_Ensure` just returned for the current,
still-registered `tcur`. There is no thread-identity mismatch and no stale/deleted state reaching
`Release` in this flow. (Contrast with the *different*, already-fixed bug documented in
`GCLeakTest.desktop.kt:6-11`: an earlier version called `PyEval_SaveThread()` from `forceGC()` on
a thread that was not attached at all. `PyEval_SaveThread`'s body dereferences
`_PyThreadState_GET()` unconditionally — on a PGO/LTO release build, `assert()`s such as
`detach_thread`'s `assert(tstate == current_fast_get())` compile out, so a `NULL` tstate reaches
`detach_thread` and is dereferenced (`tstate->critical_section`) with no check. That is a plain
null-pointer crash from calling `PyEval_SaveThread` while unattached, and it is a different bug
shape from today's: today's parking call is made while the thread *is* attached, immediately
after `Py_Initialize()`, so this specific failure mode does not apply.)

## 5. What CPython 3.14 changed here, and the two remaining candidates

The whole attach/detach vocabulary read above (`_PyThreadState_Attach`/`Detach`,
`tstate_activate`/`deactivate`, the `_status.active` / `_status.bound_gilstate` bitfield,
`current_fast_get`/`set`/`clear`) is the free-threading-era (PEP 703) rewrite of thread-state
lifecycle management, unified so the same source serves both GIL and `Py_GIL_DISABLED` builds. I
did not verify the exact CPython version this landed in (older CPython used a simpler
`PyEval_AcquireThread`/`ReleaseThread` pair over a single `interp->tstate_current` swap), but it is
recent relative to most public documentation and tutorials on `PyGILState_*`, which is worth
knowing before trusting older blog posts or Stack Overflow answers about this API.

Two candidates for what actually breaks, neither confirmed:

**Candidate A — stale per-thread C-stack recursion limits.** Each `_PyThreadStateImpl` carries
`c_stack_top` / `c_stack_soft_limit` / `c_stack_hard_limit`, computed **once** by
`_Py_InitializeRecursionLimits()` from the current machine stack pointer, and only if
`c_stack_hard_limit == 0`:

```c
void
_PyThreadState_Attach(PyThreadState *tstate)
{
    ...
    if (_tstate->c_stack_hard_limit == 0) {
        _Py_InitializeRecursionLimits(tstate);
    }
    ...
}
```

Because our thread reuses the same `PyThreadState` object across every park/re-enter cycle, this
calibration happens exactly once — at whatever C call depth `Py_Initialize()`'s *first* internal
attach happened at — and is **never** recomputed on later re-entries from a structurally different
call depth (the JVM/Panama boundary is a different C stack shape than CPython's own
`Py_InitializeFromConfig` call chain). `_Py_CheckRecursiveCall` compares the live stack pointer
against this stale `c_stack_hard_limit` on every recursive C call in the eval loop
(`if (here_addr < c_stack_hard_limit) Py_FatalError(...)`). This is a real, citable staleness bug
in the reuse path — but by itself it explains a *miscalibrated guard*, and the failure it produces
when it fires is a `RecursionError` or a `Py_FatalError` with the text "Unrecoverable stack
overflow", neither of which matches today's raw `SIGSEGV` (no such message was reported), and a
single-frame `PyImport_ImportModule("sys")` call is not deep enough to plausibly exhaust several
hundred KB to MB of C stack on its own. **I could not make this candidate fit the observed crash
without more evidence, and flag it mainly because it's a real, separate latent hazard in the reuse
pattern that the fix should be aware of regardless of whether it explains today's crash.**

**Candidate B — something the eval loop touches on the initial (never-parked) startup path that
`import sys`'s cache-hit-with-Python-level-bookkeeping path exercises differently.**
`PyImport_ImportModule("sys")` for an already-loaded module does not simply `PyDict_GetItemString`
and return — `import_ensure_initialized()` (in `Modules/import.c`, not fetched in this
investigation) can invoke Python-level machinery (attribute lookups on `__spec__`, method calls),
which is consistent with the reported crash frames (`PyObject_CallMethodObjArgs` →
`_PyFunction_Vectorcall` → `DICT_MERGE`) being genuine bytecode execution rather than a pure C
lookup. I could not determine, from source reading alone, why *this* bytecode execution — on a
thread whose `PyThreadState` is otherwise identical to the one `Py_Initialize()` used successfully
— would be unsafe. This is the open question the experiment in §6 is aimed at.

I was not able to reduce this to one explanation. Ranked: reuse of the same `PyThreadState` across
the park/re-enter boundary is established fact and is the right place to keep looking; which piece
of state that reuse fails to restore is unresolved, with Candidate A eliminated by the mismatch in
failure shape and Candidate B unconfirmed.

## 6. Minimal experiment (does not require the full suite)

Add a single desktop test — not to run today, but to hand to the next attempt — that escalates in
four steps on **one thread**, printing/asserting between each so a failure localizes to a specific
step instead of reporting "0 tests run":

```kotlin
@Test
fun parkThenReenterSameThreadIsolatesTheFailure() {
    Python3.initialize(silent = true)                       // must NOT be parked yet at this point
    val savedBeforeGuess = PyGILState_GetThisThreadState()   // record identity #1, still attached
    println("before park: $savedBeforeGuess")

    val parked = PyEval_SaveThread()                         // manual parking, mirroring the
                                                               // commented-out call in initialize()
    println("PyEval_SaveThread returned: $parked")

    // Step 1: bare re-entry, no Python C API call at all inside the guard except one that
    // reads state without touching bytecode.
    withGIL {
        val after = PyGILState_GetThisThreadState()
        println("after Ensure: $after")
        // EXPECT: after == savedBeforeGuess (same PyThreadState*, confirms/refutes §3 empirically
        // rather than from source reading alone). A mismatch would mean the "new thread" branch
        // fired -- i.e. the TSS slot was NOT still bound, contradicting the CPython source read
        // here, and pointing at something specific to this embedding (e.g. the JVM's native
        // thread setup interacting with TSS) rather than to CPython's generic behaviour.
    }
    // Step 1 crashing here would mean the attach/detach cycle itself is broken -- contradicts
    // this document's source reading, and the desktop Panama bindings for PyEval_SaveThread /
    // PyGILState_Ensure/Release (bindings.kt:41-50) would be the next thing to audit, since this
    // project has a documented history of invoke/invokeExact and ADDRESS/JAVA_LONG mismatches.

    // Step 2: a pure-C call that cannot execute Python bytecode.
    withGIL {
        Py_IsInitialized()
    }
    // Step 2 crashing but not Step 1 would point at something broken in ordinary C API use after
    // reattachment, unrelated to bytecode execution -- undermining Candidate B.

    // Step 3: the exact call that crashed today.
    withGIL {
        PyImport_ImportModule("sys")
    }
    // Step 3 crashing but not Steps 1-2 confirms it's specifically bytecode execution (not mere
    // attachment, not mere C API use) that's unsafe after reattachment -- supports Candidate B
    // and argues against Candidate A (see the manual-lookup step below).

    // Step 4 (only if Step 3 crashes): bypass import_ensure_initialized's Python-level path
    // entirely, staying in pure C, to see whether a plain cache lookup alone is safe:
    withGIL {
        val modules = PySys_GetObject("modules")
        PyDict_GetItemString(modules!!, "sys")
    }
    // If Step 4 is safe where Step 3 is not, that isolates the crash to whatever Python-level
    // code import_ensure_initialized runs on a cache hit -- worth reading Modules/import.c's
    // import_ensure_initialized next, specifically for anything that inspects C-stack depth,
    // per-thread caches, or recursion state.
}
```

This is deliberately not `@Test`-annotated as ready to run blind — the next attempt should run it
alone (`--tests` filter), on a quiet tree, and stop at the first step that crashes rather than
proceeding to the next. That directly gives a call-depth-bounded bisection of the failure, which
the "audit every call site" and "run the whole suite" attempts so far have not produced.

`PyGILState_GetThisThreadState` and `PySys_GetObject`/`PyDict_GetItemString` are already declared
in `EmbedAPI.kt` (`commonMain`); their desktop `actual`s were not verified as part of this
investigation (see §7).

## 7. What the fix would look like, under each candidate

- **If Candidate A (stale C-stack calibration) turns out to matter**, no amount of call-site
  guarding fixes it — the fix has to either recalibrate `c_stack_hard_limit` on every reattachment
  (not something this project's API surface exposes; would need a non-limited-API call or a
  CPython-side change) or ensure the *first* attach happens at a call depth structurally
  representative of where Python will actually run later. Concretely: call `Py_Initialize()`
  itself from inside the same trampoline depth used by ordinary `withGIL` calls, rather than
  directly from `Python3.initialize()`'s own stack frame, so the one-time calibration isn't
  systematically shallower or deeper than steady-state use. This is a real design change, not a
  one-line fix.

- **If Candidate B (something Python-level import bookkeeping depends on) turns out to matter**,
  the fix likely isn't about *this* call site specifically — `PyImport_ImportModule("sys")` is
  representative of "any first bytecode execution after reattachment", not a special case — so
  narrowing further requires the bisection in §6 before a fix can be scoped at all.

- **If the experiment shows Step 1 or Step 2 already crashes** (i.e. even bare reattachment or a
  pure-C call is unsafe), that overturns the "reuse is fine, something else breaks" framing of this
  whole document, and the next place to look is the desktop Panama bindings for exactly these four
  functions (`bindings.kt:41-50`) — this project has a documented, repeated history of exactly this
  class of bug (`invoke` vs `invokeExact`, `ADDRESS` vs `JAVA_LONG`), and while a quick read during
  this investigation found `PyEval_SaveThread`/`RestoreThread`/`PyGILState_Ensure`/`Release` all
  using `invokeExact` and the project's `P = JAVA_LONG` pointer convention correctly, that read was
  not exhaustive (the `find()` symbol-lookup helper and the underlying `MethodHandle` descriptors
  it builds were not traced end to end).

- **A structurally different fix, independent of which candidate is right:** if reusing the
  initialising thread's `PyThreadState` across a park/re-enter boundary turns out to be
  fundamentally what's unsafe in this embedding (as opposed to something narrower), the design
  question becomes whether the initialising thread should ever call `PyGILState_Ensure` again at
  all. An alternative shape: run `Py_Initialize()` on a dedicated thread that parks once and is
  never reused for Python work afterward — every subsequent call, including from what is currently
  "the same thread" in tests, would then go through the "first time on this OS thread" branch of
  `PyGILState_Ensure`, which creates a genuinely fresh `PyThreadState` rather than resurrecting the
  init-time one. That is a bigger change than uncommenting the parking line (it changes where
  `Py_Initialize()`/`Py_Finalize()` are allowed to be called from, and `Python3.finalize()`'s
  `PyEval_RestoreThread(mainThreadState)` call would need to run on that same dedicated thread
  too), and it should not be attempted until the experiment in §6 has actually localized the fault
  — building it on a guess would repeat the pattern this document was asked to stop.

## 8. What could not be determined

- **The exact opcode-level or memory-level reason `DICT_MERGE` faults.** Both candidates in §5 are
  plausible mechanisms consistent with *some* of the evidence, but neither was confirmed against
  the specific crash. This needs the experiment in §6, or a debugger session (`lldb` on the core,
  or a build with `Py_DEBUG` to turn the compiled-out `assert()`s back on) — deliberately not
  attempted here per the "prefer reading over running" instruction for this task.
- **Whether `import_ensure_initialized`'s Python-level path (Candidate B) is real** — I did not
  fetch `Modules/import.c`, so the claim that a `sys.modules` cache hit still runs Python-level
  code is inferred from general CPython import-machinery knowledge, not confirmed against 3.14
  source in this session.
- **Which CPython version introduced the current attach/detach vocabulary and the C-stack-address
  recursion guard.** Both read as free-threading-era (PEP 703) work; I did not verify version
  numbers, so treat "this is new" as directionally right but not dated.
- **Whether the desktop Panama bindings for the four functions in play are fully correct end to
  end.** `bindings.kt:41-50` looked right on inspection (consistent `invokeExact`, consistent
  `JAVA_LONG` pointer convention), but the `find()` helper and `MethodHandle` descriptor
  construction underneath were not traced, and this project has a specific documented history of
  bugs in exactly this area.
- **iOS.** This document is about the desktop crash only. ROADMAP §1 already separately notes that
  the iOS simulator's `GCLeakTest` failure is not GIL-related and is a distinct, unexamined defect;
  nothing here bears on it.
