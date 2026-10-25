# Downcall/upcall benchmark drift investigation (2026-08-14)

> **Superseded** by [`../design/downcall-design.md`](../design/downcall-design.md) ("Warmup: what the
> boundary benchmarks must do") on 2026-10-03; kept for history. The sections below are the record
> of how `UpcallBoundaryCostTest`'s drift was found to be a same-process warm-up artefact, how it
> was pinned (desktop to a window, wasmJs to commit `4472f83a`), and how the warmup count was
> chosen (100 000). Figures taken at the old 3 000-call warmup are not current numbers; current
> ones are in `docs/investigations/cost-table.md` and `docs/design/upcall.md` §7.

### Ratio consistency: mostly holds, desktop is the exception

> **Superseded as a source of numbers, kept as the record of how the defect was found.** Every figure
> in this section and the two that follow it was taken with `UpcallBoundaryCostTest`'s 3 000-call
> warmup, which "The benchmark was measuring the benchmark" below shows was too small by more than an
> order of magnitude. The reasoning is what still stands: these three sections are what established
> that the number moved for a repository-located reason and then that the reason was the benchmark's
> own methodology. Current figures are in `docs/design/upcall.md` §7 (which absorbed `upcall-design.md`).

`upcall-design.md`'s own upcall/downcall ratios, from the same `UpcallBoundaryCostTest`, are
reproduced by these three fresh runs (the test computes and prints its own ratio each time, so this
is a direct comparison, not a re-derivation):

| Platform | ratio recorded in `upcall-design.md` | ratio, this pass (3 runs) | consistent? |
|---|---|---|---|
| desktop | 2.49–2.89x | 2.13–2.34x | **no, see below** |
| iOS simulator | 1.33–1.43x | 1.38–1.42x | yes, nested inside the old range |
| wasmJs | 2.56–3.36x | 2.36–2.63x | mostly, top of the new range overlaps the bottom of the old one. **Superseded: since drifted to 1.58–1.68x, pinned to `4472f83a` and explained, see "wasmJs: closed too" below** |

**Desktop's ratio moved outside the previously recorded range, and it is the upcall side that
moved, not the downcall side.** This pass's downcall-same-shape figure (315.69–335.32 ns) sits at
the bottom of the old 315–527 ns range, consistent, just at its floor. This pass's upcall figure
(687.83–741.26 ns, read from the same `UpcallBoundaryCostTest` runs) sits *below* the old range's
floor of 861 ns entirely. So on this machine, at this commit (`65e1bf5d`), a desktop upcall through
`ctypes` measures cheaper than what `upcall-design.md`'s table recorded, while the downcall side did
not move. Two things separate the two tables and either could be the cause: `develop` has moved a
great deal since that table was captured (this session's own `git merge` pulled in 87 changed files,
including upcall-adjacent ones, see the merge log above), and the two tables were also captured on
different runs of the same machine, which is exactly the kind of thing this document elsewhere warns
is not comparable. Neither is ruled out here; this is reported as an open discrepancy, not resolved
into either explanation.

### Closed: repository drift, not run-to-run noise (2026-08-14)

The discrepancy above was tested directly rather than argued from a second reading. `537c1a0b`
(the commit the 861 ns floor was recorded on) and the current tip were each built in their own
freshly created `git worktree`, not reused, not incrementally recompiled, and
`:python-multiplatform:desktopTest` (the full suite, not a filtered subset, see the pitfall below)
was run three times against each, clearing `build/test-results` before every run:

| | run 1 | run 2 | run 3 |
|---|---|---|---|
| `537c1a0b`, fresh worktree | 870.48 ns | 808.72 ns | 869.12 ns |
| current tip, fresh worktree | 697.73 ns | 680.46 ns | 686.21 ns |

Same machine, same JDK (Temurin 21.0.12), same day. The two bands (809–870 ns vs. 680–698 ns) do
not overlap across six runs split into two isolated builds, which rules out ordinary run-to-run
variance as the explanation. Something in the repository between those two points changed the
measured number. `537c1a0b`'s figure also reproduces its own historical record (861–1313 ns) almost
exactly, which rules out a stale or miscalibrated re-measurement on this end.

**A methodology pitfall found along the way:** an initial attempt to bisect the ~75 commits in
between used `--tests UpcallBoundaryCostTest` to skip the other ~359 tests and go faster. That
filter alone moved the number into the 1300–1400 ns range at *every* commit tried, old and new
alike, it does not merely add noise, it changes which regime the measurement lands in. Re-running
the same checkouts with the unfiltered full suite reproduced the fast band again. The cause is
below; the practical lesson is that this benchmark's absolute number is not a property of the
timed code alone, so a comparison across two runs is only valid if both used the same suite scope
this document already required (Validation runs the full suite), a filtered re-run of one
benchmark test is not equivalent to the run it is being compared against, even on the same commit.

**Bisection was attempted and did not converge to one commit; it converged to a window, and to a
mechanism.** Git bisect (full-suite grading, threshold at 750 ns) was run between `537c1a0b` and
the fast tip. Partway through, machine load reached 10–12 on this 8-core host, two Android
emulators and other concurrent agent activity were running, unrelated to this task and outside its
control (`ps aux` at the time confirmed it; this document's own environment notes elsewhere warn
that this machine hosts several worktrees' worth of parallel agent work). Under that load, repeated
measurements of the *same* checkout spanned both regimes (one commit read 672, 771, and 1076 ns
across three consecutive runs), which made single-commit attribution unreliable. What did hold up
under repetition was the window: commits at or before `4722cfe9` measured consistently in the slow
band (854–855 ns), and commits at or after `ec8ff389` measured consistently in the fast band (672,
700 ns, each confirmed in its own fresh clean worktree). That window,
`4722cfe9..ec8ff389` (~15 commits), contains both commits this task flagged as candidates:
`6776329d` ("Perf: Price the layer users actually call, and stop a module read raising an exception
per access") and `d45071e7` (proxy handle lifetime). `975d3900` sits well outside this window and is
ruled out.

**Neither candidate touches the code this benchmark measures, which points at an indirect cause.**
`git diff <parent>..<commit>` for both `6776329d` and `d45071e7` shows changes confined to
`PythonProxySource` (the generated-proxy source emitter, its tests) and, for `d45071e7`, a doc-only
comment added to `HandleTable.kt`. Neither touches `UpcallTable.resolve`, `HandleTable.resolve`/
`release`, the non-suspend branch of `UpcallTrampoline.invoke`, or desktop's `UpcallStub`, the
actual call chain `UpcallBoundaryCostTest` times through `TrampolineFragment`. A commit that does
not touch the timed path cannot have made the timed path itself cheaper.

What it can do is change what runs *before* the timed path in the same process. `desktopTest` sets
no `forkEvery`, so Gradle's default applies: all ~360 tests in the suite run in one forked JVM, one
JIT compiler, one heap (confirmed by reading the `tasks.named<Test>("desktopTest")` block in
`build.gradle.kts`, no fork-per-test config exists). `6776329d`'s own commit title is "stop a
module read raising an exception per access", replacing an exception-driven control-flow path
(expensive on the JVM: every throw captures a stack trace) with a direct one.
`GeneratedProxyCostTest`, touched by the same commit, drives `N = 10_000` iterations times 3 kept
times multiple rows of generated-proxy calls that exercise exactly that module-attribute path, and
it runs earlier in the same shared JVM process as `UpcallBoundaryCostTest`. Removing tens of
thousands of exception throws from an earlier test changes the shared process's GC pressure and
JIT tiering history by the time a later, unrelated benchmark in the same run gets timed, without
the later benchmark's own code path changing at all. That is a plausible, mechanism-level
explanation for why only desktop moved: iOS runs through XCTest's own process model and wasmJs
through a Node process with a different (V8) JIT, neither sharing HotSpot's C2 warm-up state the
way every desktop test in one Gradle-forked JVM does.

**This is not proven to the level of a single blamed commit**, machine contention prevented that,
but it is proven to the level that matters for the table: the number moved for a real, reproducible,
repository-located reason, not for noise, and the most likely mechanism is a same-process
warm-up artifact of the benchmark's own methodology rather than a genuine drop in the upcall
boundary's per-call cost. The open discrepancy above is closed on that basis, not left open.

**wasmJs has since drifted further, past even its own "consistent" reading above.** A fresh check
on the current tip (same machine, same session, two runs: 321.32 ns and 315.89 ns, tight) puts
wasmJs's upcall figure well below the 687–741 ns-equivalent range implied by this table's own
"this pass" ratio (2.36–2.63x against a ~192–205 ns downcall figure that still matches today's
run), today's ratio is 1.26–1.67x. So the "yes, mostly consistent" verdict recorded for wasmJs
above was accurate for the commit it was measured on (`65e1bf5d`) and has since gone stale in the
same direction as desktop's did. iOS was not re-checked: this task's constraints rule out using the
iOS simulator, so its "yes, nested inside the old range" verdict above stands unverified rather
than reconfirmed.

### wasmJs: closed too, one commit, and the mechanism demonstrated rather than argued (2026-08-14)

wasmJs's drift is **repository drift, not noise**, it is pinned to the **single commit `4472f83a`**,
and, unlike desktop's, which stayed at the level of a plausible story, its mechanism was
reproduced by a controlled experiment. It is *not* a genuine change in what the wasm upcall boundary
costs.

The measurement protocol was the one the desktop section above used, with two changes forced by
this machine: runs at the two ends were **interleaved** rather than run in two blocks, and the
timed task was re-run with `--rerun` on `wasmJsNodeTest` alone rather than `--rerun-tasks`, so a
full Kotlin recompile does not finish moments before the benchmark starts. Every run is the **whole
wasm suite** (filtering changes the measured region, see the pitfall below), `build/test-results`
is cleared before each, and all of them are 344/0/0 (258/0/0 at `537c1a0b`, whose suite was smaller).

| commit | upcall | downcall, same shape | ratio | runs |
|---|---|---|---|---|
| `65e1bf5d` (parent) | 484.22–500.38 ns | 190.11–191.00 ns | 2.53–2.61x | 3 |
| **`4472f83a` (child)** | **306.66–315.82 ns** | **189.32–202.56 ns** | **1.52–1.66x** | 3 |
| `ec8ff389` | 482.03–507.38 ns | 197.87–204.10 ns | 2.38–2.53x | 3 |
| `4722cfe9` | 477.40–497.05 ns | 190.60–203.23 ns | 2.34–2.60x | 3 |
| `17f058ca` | 302.55–320.54 ns | 189.47–203.92 ns | 1.50–1.65x | 3 |
| `73f2af6b` | 312.50–322.11 ns | 188.95–213.49 ns | 1.46–1.66x | 3 |
| current tip | 355.82–373.21 ns | 222.07–226.88 ns | 1.58–1.64x | 4 (+4 earlier) |

`4472f83a` and its own parent are adjacent commits measured minutes apart in one worktree, and their
upcall bands do not touch. **The downcall column does not move anywhere in this table**, it is the
upcall side alone, exactly as on desktop.

**The window does *not* overlap desktop's.** Desktop's was `4722cfe9..ec8ff389`; both of those
endpoints measure in wasm's *slow* band and are indistinguishable from each other, so wasm did not
move across desktop's window at all. `4472f83a` sits after `ec8ff389`. The two platforms therefore
do **not** share a cause in `commonMain`'s marshalling or handle paths, which was the hypothesis
worth testing, and it is refuted rather than left open.

**The MEMFS change is not the cause either.** `3355851c` (stdlib zip installed into MEMFS instead
of resolving out of a CPython source checkout) was the obvious environmental confound, since it
lands after the desktop window. `73f2af6b` is its immediate parent and already measures 312–322 ns,
fully in the fast band. The transition happened before it.

**Why a commit that only adds a branch to the timed function made it measure cheaper.**
`4472f83a`'s only edit to the timed path is a `when` on `self` in `UpcallEntry.invokeMethod`, which
can only cost more, not less. What it also did was make `PythonProxySource.install()` succeed on
wasm for the first time. Before it, `GeneratedProxyCostTest` and `ProxyHandleLifetimeTest` printed
"no proxies are installable on this target, so there is nothing to measure" and drove **zero**
upcalls; after it, `GeneratedProxyCostTest` drives on the order of 270 000 calls
(`N = 10 000` × 3 kept × ~9 rows) straight through `UpcallEntry.invokeMethod`, earlier in the
**same Node process**, and therefore into the same V8 wasm tier-up state that
`UpcallBoundaryCostTest` is later timed in. The benchmark's own 3 000-iteration warmup does not
reach that state on its own.

That was tested, not just asserted. At the **current tip**, with nothing changed but
`GeneratedProxyCostTest`'s body short-circuited so it installs nothing and calls nothing, the
pre-`4472f83a` behaviour, same commit, same build, suite still 344/0/0, the upcall figure went
back up:

| current tip | upcall | downcall | ratio |
|---|---|---|---|
| as it is | 355.82–373.21 ns | 222.07–226.88 ns | 1.58–1.64x |
| with `GeneratedProxyCostTest` inert | 459.67–473.79 ns | 187.24–192.16 ns | 2.36–2.53x |

One lever, at one commit, moves the number across the whole gap and lands it back on `65e1bf5d`'s
band (2.53–2.61x) and on this document's own "this pass" reading (2.36–2.63x). So the desktop
section's suspicion, that this benchmark reports a same-process warm-up artifact rather than a
boundary cost, is **confirmed on wasmJs by direct experiment**, on a different commit and a
non-overlapping window from desktop's. What the two platforms share is the methodology, not a
code path.

**The consequence for the table is that `UpcallBoundaryCostTest`'s absolute upcall figure is not a
property of the commit alone.** It is a property of the commit *and* of how much upcall traffic the
rest of the suite pushed through the same process first. Any future row should record the suite
composition it was taken with, or the number will "drift" again the next time an unrelated test
starts or stops exercising the boundary.

**One thing did not reproduce and is left as observed.** `537c1a0b`, the commit
`upcall-design.md`'s 703–1075 ns wasm row was recorded on, does not reproduce that row here: a
freshly created worktree at it measured 546.45 ns once and then 1476.85–1602.25 ns on four
subsequent runs (its trampoline figure was similarly unstable, 268–612 ns, while its downcall stayed
flat at 239–247 ns). That commit's suite is 258 tests rather than 344 and predates the MEMFS change,
so it is a different environment in two ways at once, and no attempt is made here to reconcile it.
The conclusion above rests on the adjacent-commit pair `65e1bf5d`/`4472f83a` and on the tip-vs-tip
experiment, neither of which depends on `537c1a0b` at all.

**Load, since these numbers depend on it.** Machine load average was 1.4–1.6 at the start and
stayed in the 1.7–3.2 band for every run quoted above. An earlier batch was thrown away: creating
the comparison worktree set Spotlight indexing its 16 190 files, which took load to 10.4 and made
the same commit read 546 ns then 1448–1677 ns. Those runs are not in this document. The machine also
had an Android emulator resident throughout, which is why the floor is ~1.5 rather than ~0.

### The benchmark was measuring the benchmark, and the count that fixes it is measured (2026-08-14)

The two sections above diagnosed `UpcallBoundaryCostTest` and stopped there: they established that
its absolute figure was a same-process warm-up artefact, pinned wasmJs's shift to one commit, and
attached a staleness note to the table. **The measurement itself was not changed**, so the next pass
would have re-derived the same non-number. This section changes it and shows the change works.

#### What is warming, decided by experiment rather than by plausibility

Both earlier sections reached for "JIT tiering" as a story. It is testable, and the obvious rival,
CPython-side state, meaning the specializing interpreter, free lists, the allocator, string
interning, makes an opposite prediction about one row that the report already prints.

The timed loops were run **40 times in succession** inside one run, on both hosts, in two suite
configurations. Per 10 000 calls:

| | rep 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | … plateau |
|---|---|---|---|---|---|---|---|---|---|
| desktop upcall, suite as-is | 801 | 744 | 704 | 705 | 676 | 602 | 593 | 537 | ~535 ns |
| desktop upcall, proxy test inert | 1307 | 716 | 696 | 660 | 548 | 539 | 558 | 554 | ~535 ns |
| desktop downcall (Kotlin-driven) | 475 | 264 | 264 | 212 | 141 | 141 | 140 | 143 | ~141 ns |
| wasmJs upcall, suite as-is | 300 | 313 | 317 | 290 | 301 | 285 | 305 | 278 | ~280 ns |
| wasmJs upcall, proxy test inert | 785 | 339 | 317 | 303 | 294 | 299 | 319 | 279 | ~280 ns |

**The two configurations converge on the same value and they converge from opposite sides.** So the
plateau belongs to the boundary and everything before it belongs to the suite. That alone settles
what the number should be.

**Two rows in the same sweep do not move at all**, and they are what identifies the mechanism: the
empty Python loop (8–9 ns desktop, 14–16 ns wasm) and the pure-Python callee (24–27 ns, 47–54 ns) are
flat from the very first rep, in every configuration, on both hosts. CPython-side state would move
those, they are pure interpreter work, and it does not. Only rows that cross into host code move.
It is host JIT tier-up: C2 on the JVM, V8's tiering on wasm.

The confirmation is on desktop, where the two runtimes can be told apart: CPython is a **native
dylib** there, so its own speed cannot depend on how warm the JVM is, and indeed desktop's
pure-Python rows are identical (8.44–8.59 ns) whether the class runs alone or inside the full suite.

#### The count, and why 100 000

Convergence needed 40 000 calls cold and 70 000 warm on desktop, and ~70 000 on wasm. The warm side
settles later, so it sets the requirement; 100 000 is that with margin. The Kotlin-driven rows were
on the same curve and worse off, they warmed `N / 4` = 2 500, and desktop's downcall row reads 653,
266, 266, 161 before settling at ~141, so they use the same count now. Warming the Python-driven and
Kotlin-driven halves differently would make the headline ratio a ratio of two compilation states.

`GeneratedProxyCostTest` needed far less, and the reason is its shape: it warms all 19 rows before
timing any, so the shared `_pm_invoke` path already received 19 × 3 000 = 57 000 calls, just under
the knee. 5 000 puts it at 95 000. Measured, its raw rows moved from 3–7% solo-vs-suite disagreement
to 1–3.4%.

**`inline` on `Benchmark.measure` was tried and is worse.** The hypothesis was that one shared
`block()` call site goes megamorphic across every benchmark in the process. Inlining it made desktop's
downcall row need ~230 000 iterations to reach the plateau it reaches in ~50 000 through the shared
non-inlined loop, because each inlined copy is separate code that tiers up on its own. Reverted, and
recorded so it is not tried again.

#### The check the old table could not pass

| | full suite | proxy test short-circuited | this class alone (`--tests`) |
|---|---|---|---|
| desktop upcall, **3 000 warmup** | 673.91 ns | 536.75 ns | - |
| desktop upcall, **100 000 warmup** | 510–560 ns (11 runs) | 501–530 ns (3) | 506–550 ns (3) |
| wasmJs upcall, **3 000 warmup** | 322.03 ns | 291.67 ns | - |
| wasmJs upcall, **100 000 warmup** | 290–304 ns (7 runs) | 287–301 ns (3) | 321–353 ns (3) |

The middle column is the lever the wasmJs section above used to prove the defect existed. It used to
move desktop by 25% and wasmJs by 10%; it now moves neither outside its own run-to-run band. **That
is the fix.**

**The third column is honest about what is still not fixed, on one target.** Desktop passes it. wasmJs
does not, and the reason is not the boundary: filtered to this class alone, wasmJs's *pure-Python*
rows read 23.05 ns and 83.10 ns against the full suite's 15.04 ns and 47.22 ns, 55–74% slower, in
rows with no boundary in them. CPython is itself a wasm module under V8, so a Node process that lives
one second runs the interpreter's own bytecode slower than one that lives ten, and every row is
inflated together. Raising the warmup to 300 000 does not move it (solo read 318.83 and 326.21 ns
against 100 000's 320.54–337.42), which is what distinguishes a host-lifetime effect from a
call-count effect. **wasmJs's ratio columns do survive filtering**, 2.66–3.13x solo against
2.91–3.14x in the suite, so the ratio is the quantity to quote there, and the absolute column is a
full-suite figure that must be labelled as one. It is left in the table with that label rather than
deleted, because desktop's is now sound and dropping both would lose a real result.

#### What it costs

Measured on this machine, full suite, `build/test-results` cleared before each run:

| | 3 000 warmup | 100 000 warmup |
|---|---|---|
| `desktopTest`, total test time | 4.93–4.97 s | 5.10–5.20 s |
| …of which `UpcallBoundaryCostTest` | 0.036 s | 0.155–0.162 s |
| …of which `GeneratedProxyCostTest` | 0.553–0.571 s | 0.625–0.661 s |
| `wasmJsNodeTest`, wall clock | 4.31 s / 5.85 s | 4.43 s / 6.11 s |

**About +0.2 s on desktop (+4% of test time) and +0.1 s on wasm.** The warmup is bounded by the very
cost it is warming, so eight rows of 100 000 calls at ~500 ns is under a second by construction. The
wasm rows are wall clock because that runner reports 0.0 s per class in its XML.

Test counts are unchanged throughout: **desktop 360/0/1, wasmJs 344/0/0**, on every run quoted here.

**Load.** 1.14 at the start, 1.6–3.8 for every run quoted, `uptime` checked before each batch. No
other agent was running and no worktree was created during this pass, so the Spotlight problem the
previous section had did not arise. Two readings taken while load was transiently 6.25 (back-to-back
Gradle invocations) were timing measurements only, and are not among the per-call figures.

