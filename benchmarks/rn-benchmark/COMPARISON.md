# React Native's boundary against PythonMultiplatform's, measured the same way

Reference project: `/Volumes/macMini/thisisthepy/PythonMultiplatform`, whose figures below are
quoted from `docs/design/upcall.md`, `docs/downcall-design.md` and `ROADMAP.md` at the commit
**`73c40da4`** (`develop`, 2026-08-14). **Nothing in the reference project was modified or re-run**;
its numbers are read, not reproduced.

> The previous revision of this document quoted `bd0029d1`. Upstream moved, and the move mattered:
> §4a's whole device column had been re-measured at a larger warmup, and one ART row changed by 3x.
> **A quoted table goes stale silently.** `run-bench.sh` now stamps the reference commit into every
> results file so a divergence is visible at the next run rather than at the next reading.

**One fourth source, added for the iOS comparison and cited separately everywhere it appears:**
`docs/cost-table.md`, whose `iosSimulatorArm64` row was captured at **`958c0082b294`** at load
[1.97, 2.29, 5.92], marked uncontaminated, from three runs. §4b and §1c use it rather than
`docs/design/upcall.md` because it is the only reference-side source that publishes an iOS upcall, its
same-run pure-Python callee control and its `withPython` scope figure together, the three cells the
comparison needs from one execution. Its upcall figure (2253.20–2312.97 ns) and §4a's quoted
2266–2301 ns are close but **are not the same measurement**; neither is derived from the other.

**The iOS column is now measured**, `results/ios-20260814-200903.txt`, iPhone 17 Pro simulator on
iOS 26.2, load average 3.79 at the start and 3.33 at the end, under the 4.0 ceiling. §7, which used
to say iOS could not be built on this machine, was **wrong about why**, and the correction is
recorded there rather than deleted. **The Android column is still 미측정** (unmeasured / 인용 불가):
2026-08-18/19에 두 회차의 단독 실행(`/tmp/rn-android-quiet.log`: WARMUP=100000, load 3.10; `/tmp/rn-android-w400k.log`: WARMUP=400000, load 1.5)을 진행했으나, 두 회차 모두 boundary sweep에서 동일하게 `NEVER SETTLED` 판정을 받았습니다:
`boundary (Hermes+JSI+JNI+ART): NEVER SETTLED. Warmup <N> is not justified by this run.`
`NEVER SETTLED within +/-5% over 40 repetitions. This sweep does not license any warmup count; lengthen it.`
따라서 RN Android 수치는 §7c/§7d에 따라 인용할 수 없습니다(NOT QUOTABLE).

Read the iOS column with one caveat the run states about itself, and only one: its **Hermes JS-only
baseline did not settle** far enough before the warmup (1.11x margin against a 1.5x bar), so the JS
rows and every ratio that divides by one carry that. **The boundary rows do not**, the boundary
sweep settled after 10 000 calls with a 10x margin, which is what an AOT callee with no tier-up
should do, and the absolute figures do not depend on the JS baseline at all. §4b is the table that
matters and it is made of absolute nanoseconds for exactly this reason.

---

## 1. The correspondence, and where it holds

The proposed mapping was:

```
RN: JS → native (TurboModule/JSI)  ≈  ours: Python → Kotlin (upcall)
RN: native → JS                    ≈  ours: Kotlin → Python (downcall)
```

**The first line is right, and for a stronger reason than "both are a script calling its host".
The second line is wrong as stated, and the reason it is wrong is the most useful single fact in
this document.**

### 1a. JS → native ≈ upcall: yes, and the internal structure matches too

A synchronous TurboModule call and a Python → Kotlin upcall are the same *kind* of event: a script
engine, executing on its own thread, transfers control into host code, with arguments marshalled at
the seam, and gets a value back before the next bytecode runs. Neither queues, neither serialises,
neither involves a scheduler.

The correspondence goes deeper than the description. Both projects pay a **different number of
boundaries per platform, and it is the same difference on the same platforms**, established here
by reading generated code rather than documentation:

| | React Native | PythonMultiplatform |
|---|---|---|
| **iOS** | JSI (C++) → Objective-C message send. One crossing. The generated `RNBenchSpec.h` builds `NativeBenchSpecJSI` over `ObjCTurboModule::InitParams` and calls the ObjC method directly. | No runtime boundary at all: a `PyMethodDef` whose `ml_meth` is a `staticCFunction` in the same binary (`docs/design/upcall.md` §7.4). |
| **Android** | JSI (C++) → **JNI** → Kotlin. Two crossings. Confirmed with `nm` on the built `libappmodules.so`: `NativeBenchSpecJSI::NativeBenchSpecJSI(JavaTurboModule::InitParams const&)`. | C entry point in `artMain/cinterop/jni_onload.def` → **JNI** → Kotlin. Two crossings, "one shim per shape, plus a JNI upcall per call". |

So on both projects Android is structurally the expensive side of the same asymmetry, for the same
reason: a second managed runtime that has to be entered through JNI.

### 1b. native → JS ≉ downcall: the direction is not symmetric in React Native

The reference project's downcall is a plain synchronous C call: Kotlin calls
`PyObject_CallObject` and has the result before the next statement. React Native has **no
app-level synchronous equivalent in that direction.** Reading the generated bindings:

- A method that **returns a value** is generated with
  `@ReactMethod(isBlockingSynchronousMethod = true)` and runs on the JS thread.
- A method returning **`void`** is `VoidKind`, and `JavaTurboModule` / `RCTTurboModule` dispatch it
  onto the module's method queue. It is *not* synchronous.
- A **`Callback`** parameter is invoked through the `RuntimeScheduler`, may be invoked only once,
  and lands on the JS thread on a later tick.

That is not a limitation of this benchmark; it is the architecture. So the native → JS rows here
are labelled **round trips**, are kept in a separate block, and are never subtracted from anything.
Comparing them against the reference project's downcall would be comparing a crossing against a
crossing-plus-scheduler-plus-microtask-drain.

**The honest mapping is therefore:**

| direction | RN | ours | comparable? |
|---|---|---|---|
| script → host, synchronous | sync TurboModule method (returns a value) | upcall (`trampoline.add`) | **yes, this is the comparison** |
| host → script, synchronous | *does not exist for app code* | downcall (`PyObject_CallObject`) | **no** |
| host → script, asynchronous | `Callback` / `Promise` round trip | (no equivalent; the reference project has no async downcall path) | **no, in the other direction** |
| fire-and-forget script → host | `void` TurboModule method | (no equivalent) | no |

A JSI-level synchronous native → JS call *is* possible, `jsi::Function::call` from a C++
TurboModule while on the JS thread, and that would be the true mirror. It needs a pure C++ module
rather than the Kotlin/ObjC one built here. Recorded as the next thing to add, not done.

### 1c. Row for row: what each row here must be read against, and on which ratio

The direction mapping above is not enough to fill a table with. A row is only comparable to a
specific *other row*, measured by a specific test, and three of the rows here have no counterpart at
all. Getting this wrong is the failure mode that matters most: two tables side by side look like a
comparison whether or not the cells correspond.

Reference-side sources: `UpcallBoundaryCostTest` (`commonTest`, quoted through
`docs/design/upcall.md` §"One upcall, across all five platforms") and `overhead/BenchmarkTest`
(quoted through `docs/downcall-design.md`).

| row here | what it charges | reference-project counterpart | same direction? | comparable? |
|---|---|---|---|---|
| `addInts(3, 4)` | JS → native sync crossing, two doubles in, one out | **`trampoline.add`, "upcall to Kotlin"**, two ints in, one out, driven from Python | yes, script → host | **yes. This is the row the cross-project comparison is made on.** |
| `zeroArgs()` | the same crossing with no arguments | *none.* The nearest is `UpcallTrampoline.invoke` with the GIL held, but that is the marshalling **with the boundary removed**, not the boundary with the marshalling removed, the opposite subtraction | yes | only as `addInts − zeroArgs`, i.e. what two numbers cost. The absolute value has nothing to sit beside |
| `stringLength(8 / 128)` | one string conversion inward | `PyUnicode_AsUTF8 (8 chars)` in `overhead/BenchmarkTest` (78–84 ns desktop at the settled warmup) | **no**, that is a downcall | shape only. Do not put them in one table cell |
| `echoString(8 / 128)` | two string conversions | same as above, doubled | **no** | shape only |
| `sumArray(8 / 1000)` | JSI Array → `ReadableArray`, eager, per element | `list → LongArray`, 1000 elements: 50065.89 → 4532.55 ns after composition | **no**, downcall | **slope only.** The per-element slope is the transferable quantity; the intercepts are not |
| `sumObject(3 keys)` | JSI Object → `ReadableMap` | `getAttr` composition, 3929.84 → 713.34 ns | **no**, downcall | slope/composition ratio only |
| `nativeLoopNs(n)` | the callee with the crossing removed, native clock | `_pm_time_loop` (empty Python loop) and `_pm_py_add` (pure-Python callee), both timed inside Python | n/a, both are floors | **yes in role**, not in value: each says "what this side costs before anything crosses" |
| JS-only rows (`jsRows.js`) | the language floor: loop, call, array, object | `_pm_time_loop` 8–9 ns and `_pm_py_add` 24–27 ns on desktop | n/a | **yes, and both projects require them from the same run as the boundary row** |
| `noop()` (`void`) | an enqueue onto the module's method queue | *none* | fire-and-forget | **no** |
| callback / Promise round trips | crossing + `RuntimeScheduler` hop + microtask drain | *none*, the reference project has no async downcall path | host → script, async | **no** |
| - | | `Python3.withPython { }`, empty: **50–57 ns** desktop, **276–303 ns** ART `pmp_api36` | charged to every reference crossing in both directions | **no counterpart here at all.** React Native has no GIL |

#### The ratio that actually transfers, and it is not the one either table leads with

The reference project's headline ratio is **upcall / downcall of the same shape**. This project's is
**`addInts` / a JS call of the same shape**. Those are different quantities, and reading one against
the other would be the exact mistake this section exists to prevent: React Native has no synchronous
host → script call to be the denominator of the first (§1b), so that ratio cannot be formed here.

What *can* be formed on both sides is **boundary cost ÷ the script language's own call of the same
shape**, `addInts / jsCall` here, `upcall / _pm_py_add` there. Both are a pure number, both come
from one run, and both answer "what multiple of a normal call in this language does crossing cost".

Computed here from two rows the reference project publishes from the same runs (it publishes the
rows, not this ratio):

| reference platform | upcall | pure-Python callee, same shape | **upcall / callee** |
|---|---|---|---|
| desktop (JVM 21.0.12) | 510–560 ns | 24–27 ns | **19–23x** |
| Android ART `pmp_api36` | 944–1063 ns | 49.7–50.4 ns | **19–21x** |
| iOS simulator (`docs/cost-table.md` @ `958c0082`) | 2253.20–2312.97 ns | 36.80–38.32 ns | **58.8–62.9x** |

Two very different platforms landing in the same band is what made this worth using as the
cross-project quantity, **and the iOS row, added once it could be measured, does not land in that
band.** 59–63x against 19–23x is not a measurement problem: the reference project's iOS upcall is
genuinely ~4x its desktop one while CPython's own call barely moves, so the ratio moves with it.
Whatever this quantity normalises away, it does not normalise away the platform.

**The RN side is now measured on iOS: 82.25 – 83.72x** (`addInts` 1101.28–1119.40 ns ÷ a Hermes call
of the same shape, 13.37–13.39 ns, both from `results/ios-20260814-200903.txt`). `appRun.js` prints
it directly as `addInts / a JS call of the same shape`. The Android cell is still 미측정.

So on the one platform where both sides exist, the ratio says RN's boundary is the dearer of the two
relative to its own language (82–84x against 59–63x) while the absolute nanoseconds say the opposite
(1.10 µs against 2.25–2.31 µs). **Both are true and §4b is where they are reconciled.** The reason
is the warning immediately below, arriving exactly as predicted: the denominators are *not* within
30% of each other here, CPython's call is 2.8x Hermes', so this ratio has stopped transferring on
this platform, and the absolute table is the one to read.

> **Read the denominator before the ratio.** Hermes' function call is ~33 ns and CPython's is
> ~25 ns on desktop, so the two denominators are within about 30% of each other, which is the only
> reason this ratio is meaningful across the projects at all. If the RN column were measured under
> JSC or V8 (~4–10 ns) the same boundary would show a 3–8x larger ratio without anything about the
> boundary changing. §6.5 is the long form of this.

### 1d. And the thing neither table shows: the two projects are not doing the same job

React Native embeds a JS engine to run **the application's own code**. PythonMultiplatform embeds
CPython to run **another language's ecosystem** inside a Kotlin app. That difference shows up in
the numbers as soon as you look at what is on each side of the boundary:

- RN's script side is Hermes, an engine built for fast startup and small size, with **no JIT**.
- Our script side is CPython, an engine with a specialising interpreter, a GIL, and reference
  counting that the boundary code has to participate in on every call.

So "which is faster" is close to meaningless. **"What creates the cost" is the comparison that
transfers**, and section 6 is that.

---

## 2. Method

Identical on both sides, because that is the point. `RNBench/bench/harness.js` is a port of the
reference project's `Benchmark.kt`; see [`README.md`](README.md) for the list of properties it
preserves (same-shape warmup, a warmup count taken from a measured convergence point, min–max with
no outlier dropped, every baseline in the same process, no wall-clock assertion).

Reference-side figures below are min–max over five runs of the whole suite, which is that project's
stated convention.

---

## 3. What warmup React Native needs, measured, and the answer is not the JVM's

The reference project's 100 000-call warmup is not a round number: its docs record a 40-repetition
sweep in which the JVM read 1307, 716, 696, 660, 548 and then flattened at ~535 ns per 10 000
calls, converging on the same plateau from the warm side too, and it attributes that to host JIT
tier-up (C2 on the JVM, V8's tiering on wasm). Its pure-Python control rows, by contrast, were flat
from the first repetition, which is what identified the warming as host-side rather than
CPython-side.

**The same sweep was run here on Hermes and on V8 from the same source file.** Both series are 40
repetitions of the timed loop with no warmup before the first, followed by the same sweep again
after warmup, exactly as the reference project did.

| | first repetition | second half, min–max | first / second-half min |
|---|---|---|---|
| **Hermes 0.12.0** (host binary, `Date.now`, n=8 192 000/rep) | 32.59 ns | **32.59 – 32.71 ns** | **1.00x** |
| Node 24.19.0 / V8 13.6 (host, `performance.now`, n=4 000/rep) | 18.08 ns | 9.79 – 29.88 ns | 1.85x |

The full series (`results/host-20260814-125318.txt`):

```
Hermes, cold:  32.59  32.59  32.59  32.71  32.59  32.59  32.59  32.59
               32.71  32.59  32.71  32.59  32.59  32.59  32.59  32.59   … 40 readings, all 32.59–32.71
Hermes, warm:  32.71  32.71  32.47  32.71  32.59  32.59  32.59  32.59   … all 32.47–32.96

V8, cold:      18.08  14.93  2342.30  28.74  20.68  20.64  20.69  20.63
               20.59  20.65  20.66  20.67  20.63  21.30  20.68  20.58
               20.71  23.54  9.99  9.84  9.82  11.69  10.46  9.97       … a step down at repetition ~19
```

**Findings, stated separately from what they do and do not license:**

1. **Hermes does not tier up.** Eighty consecutive readings, cold and warm, span 32.47–32.96 ns,
   a 1.5% band, with the first reading inside it. There is no plateau to reach because there is no
   compilation tier to reach it from: Hermes ships without a JIT. **The reference project's reason
   for warming 100 000 calls does not exist on this engine.**
2. **V8 does, and it does so unstably.** The same sweep on V8 steps down by roughly half around
   repetition 19 (≈80 000 calls, consistent with the reference project's ~70 000 convergence
   point), and an earlier run of the identical script stepped down *further*, to 0.58 ns, at a
   different repetition. One reading in the series above is 2342.30 ns. V8's spread across
   repetitions is larger than most of the effects this benchmark is trying to measure.
3. **The warmup stays at 100 000**: and the reason is stronger than the two originally given here
   (that the engine is a build-time choice, and that the warmup is free on Hermes anyway). Both are
   true, but they are "in case" arguments. The real reason is in 3a below: **this sweep only prices
   half of the boundary, and the half it cannot reach is the half that tiers up.**
4. **The JS-side floor in RN is high.** Hermes charges 32.84 ns for a two-argument function call
   where V8 charges 3.87–10.68 ns. This matters more than it looks: a boundary that costs 200 ns is
   a 6x tax on a Hermes call and a 19–52x tax on a V8 call, so *the same boundary looks cheaper in
   an RN app than it would in a browser*, purely because the language floor it sits on is higher.

**Caveat, and it is a real one.** The only standalone Hermes VM obtainable for this machine is
`hermes-engine-cli@0.12.0` (2022-era, RN 0.68 vintage), while the app ships RN 0.87's much newer
Hermes. Absolute ns figures from the host binary must **not** be quoted as the app's. What survives
the version gap is the *structural* finding, no JIT, therefore no tier-up, therefore no warmup
curve, and that has been Hermes' design throughout.

### 3a. The sweep above prices half the boundary, and the other half is on ART

**"Hermes has no JIT, so no warmup is needed" does not follow, and on Android it is wrong.** The
reference project's warmup exists because of *host JIT tier-up*, its own words: "C2 on the JVM,
V8's tiering on wasm". The question is therefore not "does the script engine tier up" but "does
**anything on the call path** tier up". On Android, the call path is:

```
JS (Hermes, no JIT) --JSI--> C++ --JNI--> Kotlin, running on ART
                    \____________________/         \__________/
                     host-sweep.js sees this        and NOT this
```

The callee of every boundary row is `BenchModule.kt` executing on ART. **ART is precisely the
runtime whose tier-up the reference project measured, and it produced the largest warmup requirement
in its entire table** (`docs/design/upcall.md` §7.2):

| reference target | first repetition ÷ plateau | flat from | margin at a 100 000 warmup |
|---|---|---|---|
| iOS simulator | 1.01x | ~10 000 | 10x |
| androidNative `pmp_api36` | 1.27x | ~40 000 | 2.5x |
| androidNative `pmp_api26` | 1.30x | ~30 000 | 3.3x |
| ART `pmp_api26` | 1.93x | ~20 000, noisily | 5x |
| **ART `pmp_api36`** | **8.56x** | **~90 000–100 000** | **~1x, none** |

Its `pmp_api36` ART series reads 9194.8, 3218.9, 2288.7, 1648.3, 1218.3, 1173.3, 1129.5, 1532.7,
1057.8, 1044.6 against a plateau of 1074.2, **still 9% high at 70 000 calls**, and note the 1532.7
at repetition 8, after four readings that were already closer. A series that dips toward the plateau
and back out has not settled.

So the RN Android boundary rows sit on the same curve, for the same reason, on the same runtime, at
the same API level. **100 000 is not margin here; it is roughly exactly enough**, and that is the
finding, not "Hermes needs no warmup".

**Two things follow, and both were wrong before this pass:**

1. **This document previously claimed `run-bench.sh android` prints the same sweep. It did not.**
   `appRun.js` never called `H.sweep`; the sweep existed only in `host-sweep.js`, which has no
   native module and no ART and therefore cannot ask this question at all. It now runs on device,
   **cold, before anything else**, in two versions: the JS-only row (prices Hermes) and
   `addInts` through the TurboModule (prices Hermes + JSI + JNI + ART). Same 40 repetitions ×
   10 000 calls as the reference project's, so the two series can be read against each other.
2. **The run now states its own verdict.** Every report ends with a `warmup verdict` block that
   computes, from that run's own sweeps, where the series settled and what margin 100 000 has over
   it. If the margin is under 1.5x, or the series never settles within ±5%, the report says in
   as many words that its own boundary rows must not be quoted. The threshold is printed with the
   answer because the answer depends on it, at ±5% the reference ART series settles at ~90 000
   and at ±10% at ~50 000, and a settle point quoted without its band is not reproducible.

**The iOS half is now answered, and it lands on top of the reference project's iOS row:**

| | first repetition ÷ plateau | flat from | margin at a 100 000 warmup |
|---|---|---|---|
| reference project, iOS simulator (quoted, top of this section) | 1.01x | ~10 000 | 10x |
| **RN `addInts` through the TurboModule, iOS simulator (measured today)** | **1.05x** | **10 000** | **10.00x** |

Two different projects, two different script engines, two different host languages, and the same
settling behaviour on the same platform, because on iOS *neither* project has a tiering runtime on
the far side of the boundary. That is the control that makes §3a's Android argument falsifiable
rather than merely plausible: the claim is that ART's tier-up is what forces the large warmup, and
the platform without ART shows no warm-up curve at all in either project.

**What is still not known:** whether RN 0.87's Hermes, on the shipping build, behaves like the
2022 standalone binary, and where the ART-backed boundary row actually settles on this hardware.
Both need one emulator and one `./run-bench.sh android`. **Neither has been measured**, and the iOS
result above says nothing about them, it is the arm of the experiment where the effect is expected
to be absent. The 100 000 is still carried over from the reference project's ART measurement, which
is a defensible prior and not a result.

One thing the iOS run *does* say about the warmup, in the other direction: the **Hermes** JS-only
sweep did not settle within a 1.5x margin on either iOS run (1.11x and 1.43x, at loads of 3.8 and
2.4). Hermes has no JIT, so this is not tier-up, and the series does not have the shape of one:

```
14.12 14.13 14.25 14.13 14.10 14.07 14.15 14.10 [15.37] 14.07 14.08 14.08 ... 13.57 13.58 ... 14.07 14.10
                                                  ^ one repetition, and it sets "flat from 90 000"
```

The first repetition is **1.02x the plateau**, flat from the start. What sets the settle point is a
**single 15.37 ns excursion at repetition 9**, and `analyzeSweep` reports the *last* reading outside
±5%, so one outlier anywhere early moves "flat from" to its position. The step down to ~13.57 in the
middle third and back to ~14.1 by the end is frequency scaling, not warming: a series that warms
does not go back up.

**So read "flat from" as an upper bound that a single noisy repetition can dominate, not as a
measured settle point.** The same fragility bit the boundary sweep on the load-5.8 run, where a
spike at repetition 30 produced "flat from 320 000" for a series whose first repetition was 0.99x
its plateau. The metric is left as it is, it is the reference project's, and changing it here would
break the comparison, but the sweep series is printed in full in every report precisely so this is
checkable rather than taken on trust.

The practical consequence: **a quiet machine matters more for the JS baseline than for the boundary
row on iOS**, which is the opposite of what §3a predicts for Android.

---

## 4. The boundary tables

### 4a. Ours, quoted (not re-run)

`UpcallBoundaryCostTest`, `docs/design/upcall.md`. **Re-quoted 2026-08-14 at `73c40da4`.** The
previous version of this table was taken at `bd0029d1` and is superseded: it carried four rows as
*italic* 3 000-warmup readings and two ART rows with three columns reading *not recorded*. Upstream
has since re-measured every device row at the 100 000 warmup and filled all five columns from one
test. **Nothing below is italic any more, and no row is a 3 000-warmup reading.**

| Platform | upcall | downcall, same shape | trampoline alone (GIL held) | upcall / downcall |
|---|---|---|---|---|
| **desktop** (JVM 21.0.12, macOS arm64) | 510–560 ns | 132–144 ns | 76–91 ns ‡ | 3.71–4.14x |
| **wasmJs** (Node) | 290–304 ns ‡ | 95–109 ns | 122–129 ns | 2.67–3.21x |
| **iOS simulator** (iOS 26.2, arm64) | 2266–2301 ns | 1599–1610 ns | 1962–1990 ns | 1.41–1.43x |
| **androidNative** (`pmp_api36`, arm64) | 3228–3275 ns | 2155–2229 ns | 2699–2760 ns | 1.45–1.51x |
| **androidNative** (`pmp_api26`, arm64) | 3234–3310 ns | 2470–2554 ns | 2731–3228 ns ※ | 1.28–1.32x |
| **Android ART** (`pmp_api36`, arm64) | 944–1063 ns | 692–818 ns | 793–907 ns | 1.20–1.45x |
| **Android ART** (`pmp_api26`, arm64) | 1146–1233 ns | 1185–1293 ns | 1012–1087 ns | 0.91–1.04x |

**The ART rows moved most, and that is the point of §3a.** ART `pmp_api36`'s upcall went
2301–3086 → **944–1063 ns** on the warmup change alone; the three Kotlin/Native targets barely moved.
The rows that fell are exactly the rows with a JIT under them. **Android ART is the row this
project's Android column is compared against**, and it is the one that was most wrong when it was
under-warmed.

‡ and ※ mark rows where upstream kept a single outlying run rather than dropping it; see the source
document. `wasmJs`'s absolute column is only meaningful as a full-suite figure, its ratio column is
the quotable one there.

Per-crossing FFI cost, once the desktop path was fixed (`docs/downcall-design.md`):

| | `PyList_Size` on `sys.path` |
|---|---|
| Desktop (Panama, `invokeExact`) | **2.65 ns** |
| Android API 36 emulator (`@FastNative`) | **4.28 ns** |
| Android API 36 hardware (`@FastNative`) | 7.24 ns |

The language floor those sit on, **corrected**: on desktop an empty Python loop is 8–9 ns and a
pure-Python callee of the same shape as the upcall is **24–27 ns**; on wasmJs they are 14–16 ns and
47–54 ns (`docs/downcall-design.md`). On ART `pmp_api36` they are 17.3 ns and 49.7–50.4 ns.
**Both are flat from the first 10 000 calls on every host and device**, the control that identified
the warming as host-side rather than CPython-side.

> An earlier version of this line gave the desktop pure-Python callee as 8.4–8.6 ns. That is the
> **empty loop**, not the callee. The distinction is not cosmetic: the callee is the denominator of
> the only ratio that transfers between the two projects (§1c), and using 8.5 instead of 25 would
> have inflated the reference project's side of that ratio by about 3x.

### 4b. The comparison itself, on iOS: absolute nanoseconds, side by side

**This is the representative table.** Both columns are one script language calling a host function
that adds two integers, on the same simulator runtime, on the same machine, on the same day. The
pairing is `addInts` ↔ **upcall**, per §1a; it is not `addInts` ↔ downcall, and §1b is why the
native → JS direction has no cell here at all.

| | React Native (this repo) | PythonMultiplatform (quoted) |
|---|---|---|
| the call | `Bench.addInts(3, 4)`, JS → ObjC++ TurboModule | Python → Kotlin upcall |
| **cost of one crossing** | **1101.28 – 1119.40 ns** | **2253.20 – 2312.97 ns** |
| the script language's own call, same shape, same run | 13.37 – 13.39 ns (Hermes) | 36.80 – 38.32 ns (CPython) |
| crossing ÷ that call | 82.25 – 83.72x | 58.80 – 62.85x |
| host | iPhone 17 Pro simulator, iOS 26.2, arm64 | `iosSimulatorArm64`, iOS 26.2 (SDK 260200), arm64 |
| source | `results/ios-20260814-200903.txt`, load 3.79 | `docs/cost-table.md` @ `958c0082`, load [1.97, 2.29, 5.92] |

**On iOS, one Python → Kotlin upcall costs about twice one React Native TurboModule call:
2.01 – 2.10x**, computed from the min/max ends of the two rows above.

Two things must be said about that number, in this order.

**It is not a like-for-like verdict on the two designs.** §1a establishes that on iOS *neither*
project pays a second managed-runtime crossing, RN does one ObjC message send, ours calls a
`staticCFunction` in the same binary, so the 2x is not "one extra boundary". What it prices is the
two runtimes' cost of *arriving at* their host function: CPython's calling convention, argument
tuple and GIL discipline against Hermes' JSI dispatch. The reference project's own controls locate
most of ours: an empty `withPython` scope alone is **701.18 – 723.85 ns** on this target, **63 – 66%
of RN's entire `addInts` call**, spent by us before a single argument is converted. That, not the
crossing, is where the 2x lives.

**The normalised ratio points the other way, and that is not a contradiction.** Ours is 58.8 – 62.9
CPython calls' worth of crossing; RN's is 82.3 – 83.7 Hermes calls' worth. Ours is cheaper *relative
to its own language* and dearer *in nanoseconds*, because CPython's own call (36.8 – 38.3 ns) is
about 2.8x Hermes' (13.4 ns). §1c is explicit that this ratio only transfers between projects while
the two denominators are close, and here they differ by nearly 3x, so **the absolute row is the
representative one and the ratio row is the supporting one**, not the reverse. A reader who quotes
only the ratio will conclude ours is the cheaper boundary, which on this platform it is not.

> The ratio row inherits the run's own caveat: the Hermes baseline sweep did not settle (1.11x
> margin). The absolute RN row does not, its sweep settled at 10 000 calls with 10x margin.

### 4c. React Native, measured on iOS, 미측정 on Android

The iOS column is filled from `results/ios-20260814-200903.txt`. Ranges are min–max over 5
repetitions inside that single process; nothing is averaged across runs and nothing is discarded.
The Android column is marked 미측정 (인용 불가): 2026-08-18/19에 수행한 두 단독 실행(`/tmp/rn-android-quiet.log`, WARMUP=100000, load 3.10 / `/tmp/rn-android-w400k.log`, WARMUP=400000, load 1.5) 모두에서 boundary sweep이 수렴하지 않고 `NEVER SETTLED` 판정을 받아, §7c에 의해 전 행이 인용 불가(NOT QUOTABLE)로 처리되었습니다.

| row | Android (Hermes, arm64) | iOS (Hermes, arm64) |
|---|---|---|
| **JS-only floor, measured in the same run** | | |
| empty JS loop | 미측정 | 13.68 – 14.08 ns |
| JS call, same shape as `addInts` | 미측정 | 13.37 – 13.39 ns † |
| **JS → native, synchronous** | | |
| `zeroArgs()`, boundary only | 미측정 | 975.82 – 985.27 ns |
| `addInts(3, 4)`, two numbers | 미측정 | **1101.28 – 1119.40 ns** |
| `stringLength(8 chars)`, one conversion | 미측정 | 1376.30 – 1377.83 ns |
| `stringLength(128 chars)` | 미측정 | 1783.75 – 1844.78 ns |
| `echoString(8 chars)`, two conversions | 미측정 | 1428.03 – 1479.65 ns |
| `echoString(128 chars)` | 미측정 | 1882.72 – 1916.37 ns |
| `sumArray(8 elements)` | 미측정 | 2024.79 – 2065.26 ns |
| `sumArray(1000 elements)` | 미측정 | 64584.08 – 65445.09 ns |
| `sumObject(3 keys)` | 미측정 | 2250.80 – 2270.83 ns |
| **controls** | | |
| `addInts` body, native loop, no crossing | 미측정 | 3.50 – 3.54 ns |
| `noop()`, `void`, async dispatch, *not* a crossing | 미측정 | 1421.74 – 1514.50 ns |
| **native → JS ROUND TRIPS (not comparable with the above)** | | |
| callback round trip | 미측정 | 13642.42 – 14032.71 ns |
| Promise round trip | 미측정 | 13868.88 – 14646.50 ns |
| bare `await Promise.resolve(1)` floor | 미측정 | 805.33 – 1351.58 ns |
| **convergence sweeps (cold, 40 x 10 000), see §3a** | | |
| JS-only row: settles after how many calls | 미측정 | 90 000 † |
| **`addInts` through the TurboModule: settles after how many calls** | 미측정 | **10 000** |
| margin the 100 000 warmup has over that | 미측정 | JS-only **1.11x, too thin †**; boundary **10.00x, adequate** |
| **ratios, all terms from the same run** | | |
| **`addInts` / a JS call of the same shape** ← the cross-project ratio (§1c) | 미측정 | 82.25 – 83.72x † |
| `addInts` / `zeroArgs` (what two numbers cost) | 미측정 | 1.13x (116.01 – 143.58 ns) † |
| `sumArray(1000)` / `sumArray(8)` (per-element slope) | 미측정 | 31.90x |
| `echoString(128)` / `echoString(8)` (per-character slope) | 미측정 | 1.32x |

† **carries the run's own caveat.** The Hermes JS-only sweep settled only at 90 000 calls against a
100 000 warmup, a 1.11x margin, under the 1.5x bar the report applies, so the JS baseline row and
every ratio computed against it are marked. This is a statement about the *denominator*, not about
the boundary: the boundary sweep in the same run settled at 10 000 calls with a 10x margin, and its
first repetition was 1.05x the plateau. On iOS the callee is AOT-compiled Objective-C++ with no
tier-up, so there is no warm-up curve for it to climb; the JS-side drift (14.15 → 13.57 ns across
the sweep) has the shape of CPU frequency scaling on a machine that was not idle, not of a warmup
that is too short. Re-running quieter is the fix, **not** raising `WARMUP`, see §3a for why that
number has no room in it on the Android side.

**What the iOS column says on its own,** before any cross-project reading:

- **The crossing dominates everything it carries.** `zeroArgs`, the boundary with no arguments at
  all, is 975.82 ns, which is 88% of `addInts`. Two integers cost 116 – 144 ns on top of it.
  Anything that reduces the *number* of calls pays; anything that reduces the *arguments* barely
  does.
- **Array elements are priced like arguments, not like bulk data.** `sumArray` goes 2024.79 →
  64584.08 ns from 8 to 1000 elements: **63.1 ns per element**, which is the same order as the
  58 – 72 ns each of `addInts`' two integers costs. Strings are ~17x cheaper per unit,
  `echoString` at 8 → 128 characters is 1.32x, or **3.6 – 3.8 ns per character**, so a 1000-element
  array is the row to avoid, and a long string is not.
- **The async round trips are ~12x a synchronous crossing** (13.6 – 14.6 µs against 1.10 µs) and
  most of that is not the boundary: a bare `await Promise.resolve(1)` with no native code in it is
  805 – 1352 ns, and the rest is the `RuntimeScheduler` hop. §1b is why these are reported
  separately and must not be subtracted from anything.
- **`noop()` is not a crossing** and is in the table as the control that proves it: at 1421.74 ns it
  is *dearer* than `addInts`, because a `void` TurboModule method is dispatched asynchronously.

The rows are chosen so that the two-length pairs give a slope and an intercept rather than one
blended number: `sumArray` at 8 and 1000 separates the per-call boundary from the per-element
conversion, and `echoString` at 8 and 128 does the same for characters. The reference project's
`getAttr` and `list → LongArray` composition rows are the equivalent measurement on its side.

Host-side JS floor, **measured** (host binaries, not the app, see the caveat in section 3):

| row | Hermes 0.12.0 | Node 24.19.0 / V8 13.6 |
|---|---|---|
| empty JS loop | 13.67 – 13.85 ns | 10.15 – 16.24 ns |
| JS call, same shape as `addInts` | 32.84 – 32.84 ns | 3.87 – 10.68 ns |
| JS call returning a string argument | 30.03 – 30.15 ns | 3.91 – 4.44 ns |
| JS sum of an 8-element array | 162.11 – 162.60 ns | 6.72 – 6.78 ns |
| JS sum of a 1000-element array | 14312.50 – 14374.99 ns | 611.66 – 667.60 ns |
| JS sum of a 3-key object | 39.79 – 39.92 ns | 4.33 – 4.49 ns |
| 128-char string built per call | 66.41 – 66.65 ns | 13.29 – 13.45 ns |

Read the two columns as *engines*, not as platforms: neither is the number an RN app would print,
and the Hermes column is a four-year-old build. The ratio between them is the more durable part,
and it is **not uniform, which is itself the finding**: on min-to-min,

| | Hermes / V8 |
|---|---|
| empty loop (no call, no allocation) | **1.35x** |
| function call | 8.5x |
| 3-key object read | 9.2x |
| 128-char string built per call | 5.0x |
| 8-element array sum | **24.1x** |
| 1000-element array sum | 23.4x |

Hermes' bytecode dispatch is within striking distance of V8's on a bare loop and falls behind by an
order of magnitude the moment property access and array indexing are involved, which is exactly
what a JIT with shape feedback buys and an interpreter cannot. That shape matters for the boundary
rows still to be measured: the `sumArray` rows have the most JS-side work in them, so their *JS*
half is where Hermes is weakest, and a boundary cost read as a ratio against them will look
correspondingly small.

---

## 5. Size and startup

### 5a. Size, measured on both sides, but they are not the same kind of artefact

**Read this comparison as two different questions.** The reference project's 23.42 MB is a
*library AAR* that a consumer app adds; the RN figure is a *whole application*. They are placed
together because both answer "what does embedding this runtime cost a shipped Android app", but the
denominators differ and neither number should be lifted out of its row.

| | measured | note |
|---|---|---|
| **PythonMultiplatform AAR** (release, 2 ABIs) | **23.42 MB** on disk, 22.04 MB of entries, 1740 files | after removing CPython's own test suite and C headers; was 40.10 MB. The library's own classes are **0.49 MB** of it, the rest is CPython plus its stdlib. `ROADMAP.md` §15d. |
| **RN release APK** (arm64-v8a only, this app) | **17.71 MB** on disk, 24.80 MB raw | measured here: `./gradlew :app:assembleRelease -PreactNativeArchitectures=arm64-v8a` |
| RN release APK (all four ABIs) | 51.56 MB on disk | measured here; this is what `run-bench.sh android` builds, since it does not know the device's ABI in advance. An App Bundle strips this per device; the AAR row above does **not** get that treatment for its `assets/`, which is the reference project's §15d finding. |
| RN debug APK (arm64-v8a only) | 38.84 MB on disk, 45.32 MB raw | measured here |

What is inside the RN release APK (raw entry bytes, arm64-v8a):

| | raw |
|---|---|
| `libreactnative.so` | 6.90 MB |
| `libhermesvm.so` | 2.47 MB |
| `libc++_shared.so` | 1.29 MB |
| `libjsi.so` | 0.41 MB |
| other `.so` (Fresco image codecs, fbjni, …) | 0.94 MB |
| dex (React Native's Java/Kotlin, AndroidX, this app) | 11.08 MB |
| `assets/index.android.bundle` (Hermes bytecode) | **1.00 MB** |
| res + other | 0.76 MB |

And what is inside ours, per `ROADMAP.md`'s breakdown of the pre-trim AAR (compressed, per ABI):
`jni/` 5.09–5.31 MB, pure-Python stdlib 3.16 MB, `lib-dynload` 2.37–2.46 MB.

**The shapes are different in a way worth saying out loud.** RN's runtime cost is dominated by
*engine and framework code*, 11.1 MB of dex plus 11.1 MB of native library, for 1.0 MB of actual
application. Ours is dominated by *a language's standard library shipped as data*, 0.49 MB of
library for ~23 MB of CPython. An RN app that grows adds JS bytecode at roughly 1 MB per
application; a PythonMultiplatform app that grows adds Python source, and the fixed CPython cost is
already paid.

### 5b. Startup

| | ours (measured, `ROADMAP.md`) | RN |
|---|---|---|
| first launch | **237 ms** (API 26) / **480 ms** (API 36), unpacking 804 files, 20 147 280 bytes of stdlib from assets | 미측정 |
| every later launch | **4 ms** (API 26) / **28 ms** (API 36), a stamp check | 미측정 |

The RN counterpart to measure is time-to-first-render, and the structural expectation to test is
that it has **no equivalent of the first-launch unpack at all**: Hermes bytecode is `mmap`ed
straight out of the APK's `assets/`, so there is nothing to stage into `filesDir` and no
first-launch/later-launch asymmetry to measure. If that holds, it is the single largest
non-boundary difference between the two designs, and it is a difference in *architecture* rather
than in optimisation: our stdlib is thousands of separate files that CPython must `stat` and open
by path, and RN's is one bytecode blob.

---

## 6. What creates the cost, the part that transfers

This is the section worth keeping. It does not need a device, because it is about mechanism.

**1. The number of managed-runtime boundaries per call is the same story on both sides, and
Android loses it twice.** Both projects go JSI/C → JNI → managed on Android and JSI/C → native
directly on iOS. In the reference project this is why Android's ART upcall is "one shim per shape,
plus a JNI upcall per call". In RN it is why `libappmodules.so` contains a `JavaTurboModule`-based
shim on Android and an `ObjCTurboModule`-based one on iOS. Anything that reduces crossings pays off
on Android roughly twice as much as on iOS, in both projects.

**2. Ours pays for something RN has no analogue of: the GIL, on every single call.** The reference
project measures an *empty* `Python3.withPython { }` scope, a GIL acquire/release with no C API
call in it, at 50–57 ns on desktop, 24–28 ns on wasm, and 725–982 ns on the iOS simulator. That is
charged to every crossing in both directions. React Native has no such lock: Hermes is
single-threaded per runtime and a synchronous TurboModule call is already on the JS thread, so
there is nothing to acquire. **A large part of what looks like "our boundary is expensive" is not
the boundary; it is a design constraint of the runtime being embedded.**

**3. RN pays for something we do not: everything crosses as a JSI value.** Our upcall passes a
`PyObject*` and the callee decides what to do with it; ownership is explicit and nothing is copied
unless asked. An RN argument is converted at the seam, `jsi::String` to `NSString`/`jstring`,
`jsi::Array` to `NSArray`/`ReadableArray`, and the conversion is eager and per element. This is
exactly why `sumArray` is measured at 8 and at 1000 elements: the slope between them *is* that
cost, and it is the row most likely to differ by an order of magnitude from ours.

**4. Both projects' worst case is the same shape: many small crossings.** The reference project
measured composition against per-call, `getAttr` 3929.84 → 713.34 ns, `list → LongArray` at 1000
elements 50065.89 → 4532.55 ns, and found that the win was mostly *redundant work removed*, not
crossings removed. RN's architectural answer to the same problem is the same one: batch at the
boundary. That agreement is worth more than either project's absolute numbers.

**5. The engine underneath changes what "overhead" means.** Hermes has no JIT, so its JS floor is
~33 ns per call where V8's is ~4–10 ns. A 200 ns boundary is a 6x tax on Hermes and a 20–50x tax on
V8. CPython's floor is 8–9 ns per bytecode loop iteration and **24–27 ns per pure-Python call** on
desktop, i.e. *within about 30% of Hermes*, not far below it. **That near-coincidence is the only
reason §1c's cross-project ratio means anything**; under JSC or V8 the same boundary would score
3–8x worse against its floor without anything about the boundary having changed. **Any cross-project
ratio must be read against the floor measured in the same run, which is why both harnesses measure
one.**

**6. And the JIT that matters on Android is not in the script engine.** Hermes has none, but the
Kotlin callee behind every RN Android boundary row runs on ART, which has one, and which the
reference project measured as needing the largest warmup of any target it tested. §3a. This is the
single correspondence that is easiest to get wrong in the *method* rather than in the table, because
the host-side sweep answers a question that looks like the right one and is not.

---

## 7. What was blocked, what actually blocked it, and what is still blocked

### 7a. iOS is not blocked. The previous diagnosis in this section was wrong.

This section used to open "**iOS cannot be built on this machine right now**", on the evidence of
`xcodebuild -showdestinations` reporting zero eligible iOS destinations and an
`iOS 26.5 is not installed` error, and it concluded that a several-GB `xcodebuild -downloadPlatform
iOS` was required. **iOS builds, installs, launches and produces a full report on this machine, and
nothing was downloaded.** `results/ios-20260814-200903.txt` is the run.

The `-showdestinations` output was real; the inference from it was not. That command exercises
Xcode's **scheme-destination resolution**, which insists on a platform component matching the
*current* SDK. Building the target against `-sdk iphonesimulator` with no destination does not go
through that layer, and it works. The runtimes that were dismissed as "from older Xcode installs and
Xcode 26.6 will not use them", iOS 26.2 among them, are exactly the runtimes the app now runs on.

**The lesson is about the test, not about Xcode.** `-showdestinations` was treated as a capability
probe for the machine when it is a resolution probe for a scheme, and a negative result from it was
generalised to "any project on this machine including CocoaPods' own targets". A capability claim
that broad should have been falsified by attempting the build once, which costs minutes and is what
`build-ios.sh` now does. `run-bench.sh` no longer consults `-showdestinations` at all; the comment
in `cmd_ios` records why, so this cannot be reintroduced as a fast pre-check.

### 7b. What actually cost two runs: the app was fine and nothing was listening

Two `./run-bench.sh ios` runs **exited 0 with results files containing the conditions block and not
one benchmark row** (`results/ios-20260814-171508.txt`, 22 lines; `results/ios-20260814-183543.txt`,
19 lines). The app built, installed and launched correctly in both, and, as far as can be told, ran
the entire benchmark correctly in both. The reports were never captured.

**Cause: `log stream` defaults to `--level default`, which streams Default, Error and Fault and
silently drops Info and Debug.** React Native's iOS logger emits JS `console.log` at **Info**, under
`[com.facebook.react.log:javascript]`. The predicate was therefore being applied to a stream the
messages had already been excluded from. logcat has no equivalent default filter, so the Android
path never had this problem and the harness had never needed to think about levels.

**Confirmed by A/B on a single launch**, same app, same predicate, two `log stream` processes
attached at once:

| `log stream` invocation | lines matching `RNBENCH\|` |
|---|---|
| `--style compact --predicate 'eventMessage CONTAINS "RNBENCH\|"'` | **0** |
| `--style compact --level info --predicate 'eventMessage CONTAINS "RNBENCH\|"'` | **91** |

A third stream at `--level debug` filtered on the process rather than the tag saw 1165 lines, which
is what confirms the app was talking the whole time. The 91-line capture contained the complete
report and the `END` marker.

**The output path was not changed.** `console.log` → unified log → `simctl spawn ... log stream` is
kept, because the app writing to a file and `simctl get_app_container` reading it back would have
diverged the two platforms' capture paths for a problem that was one missing flag on one of them.
Adding `--level info` costs nothing, keeps both platforms on "the app logs, the harness listens",
and leaves the Android path untouched.

### 7c. Why an empty capture was reported as success, and what now prevents it

The harness had no idea whether it had captured anything. It wrote the conditions, appended whatever
the log stream produced, and exited on the log stream's status. **A file that says nothing is worse
than a missing file: it looks like a measurement until someone reads it**, and it was misread twice.

`verify_report` now runs on both platforms and both `cmd_ios` and `cmd_android` return its status:

| condition | verdict | exit |
|---|---|---|
| zero benchmark rows | `FAILED`, with the three causes in the order they have actually occurred here | non-zero |
| rows but no `END` marker | `INCOMPLETE`, capture window too short, or the app died partway | non-zero |
| no "all N rows returned a positive duration" | `SUSPECT`, look for `UNUSABLE ROWS` | non-zero |
| the run's own warmup verdict is `INSUFFICIENT` | `NOT QUOTABLE AS A WHOLE`, naming which sweep and therefore which rows | non-zero |
| otherwise | rows and `END` recorded in a `capture check` block | 0 |

A "row" is matched as `name | min | max | reps` with three numeric fields, not counted as lines: the
capture also yields `#` progress lines and prose, and a run that printed only progress before dying
is exactly as empty as one that printed nothing. **Verified against all four states**, the two
empty files above return non-zero, a file with rows and `END` returns 0, and the same file with
`END` stripped returns non-zero.

The last row of that table is the one that fired on the runs reported here, and it is the reason the
iOS column in §4c carries a † rather than being quoted clean. **A complete-looking table with a
warning eleven lines above it is the same failure as an empty file**, and it was previously exiting
0 as well.

One further race was closed: the harness slept a fixed 2 seconds after starting `log stream` before
launching the app. It now waits for the stream's own `Filtering the log data using …` banner, up to
30 s, and records a skip if it never arrives. A lost race there produces an empty capture of a
healthy run, indistinguishable from the bug above.

### 7d. Still blocked

**Android: 미측정 (인용 불가 판정).** 2026-08-18/19에 두 회차의 단독 실행 로그(`/tmp/rn-android-quiet.log`, `/tmp/rn-android-w400k.log`)를 검증한 결과, RN Android 측정이 **인용 불가(NOT QUOTABLE)**로 확정되었습니다.

- **실행 조건 및 판정 원문**:
  - `/tmp/rn-android-quiet.log` (WARMUP=100000, `load average: 3.10 3.45 3.74 (before the run)`):
    `boundary (Hermes+JSI+JNI+ART): NEVER SETTLED. Warmup 100000 is not justified by this run.`
    `NEVER SETTLED within +/-5% over 40 repetitions. This sweep does not license any warmup count; lengthen it.`
  - `/tmp/rn-android-w400k.log` (WARMUP=400000, `load average: 2.97 1.85 1.63 (before the run)`):
    `boundary (Hermes+JSI+JNI+ART): NEVER SETTLED. Warmup 400000 is not justified by this run.`
    `NEVER SETTLED within +/-5% over 40 repetitions. This sweep does not license any warmup count; lengthen it.`

  - `results/android-20260819-095448.txt` (WARMUP=100000, `load average: 3.52 2.31 2.48 (before the run)`,
    `RN_BENCH_REQUIRE_QUIET=1`, 다른 에이전트 없이 단독 실행):
    `boundary (Hermes+JSI+JNI+ART): NEVER SETTLED. Warmup 100000 is not justified by this run.`
    세 번째 회차이자, 기계에 다른 작업이 하나도 없음을 확인하고 돌린 첫 회차입니다. 결과는 같습니다.

- **배제된 가설 (Ruled Out)**:
  1. **시스템 부하 문제 아님**: 호스트 load average가 3.10, 1.5, 3.52(기준치 4.0 미만)인 조용한 상태에서
     세 회차 모두 동일하게 `NEVER SETTLED` 판정이 남. 세 번째 회차는 `RN_BENCH_REQUIRE_QUIET=1` 로
     걸어 기계가 조용하지 않으면 아예 거부하도록 한 상태에서 통과한 것이므로, 부하는 이 판정의
     원인이 아닙니다.
  2. **워밍업 부족 문제 아님**: WARMUP을 100,000회에서 400,000회(4배)로 올려도 boundary sweep이 수렴하지 않고 동일하게 `NEVER SETTLED` 판정이 남.

- **다만 워밍업은 JS 쪽에서는 실제로 부족했습니다 (새 사실, 2026-08-19T09:55)**: 같은 회차에서
  JS-only sweep 이 `settles at 150000 calls, so warmup 100000 has only 0.67x margin -- TOO THIN` 로
  판정됐습니다. 이것은 boundary 미수렴과 **별개의 결함**이며, JS 기준선과 그것으로 나눈 **모든 비율**을
  무효화합니다(절대 boundary 수치는 무효화하지 않습니다, 그쪽은 기준선에 의존하지 않습니다).
  `appRun.js` 의 `WARMUP` 은 "sweep 이 달리 말하지 않는 한 그대로 둔다"는 자기 규칙을 달고 있었고,
  sweep 이 달리 말했으므로 **400,000 으로 올렸습니다.** 400,000 은 00:50 회차가 이미 그 sweep 에
  충분한 여유를 준다고 보인 값입니다. boundary 쪽은 이것으로 고쳐지지 않습니다.

- **네 번째 회차 (2026-08-23, WARMUP=400,000, load 2.71, 단독 실행), 미수렴은 수렴 문제가 아니었습니다.**
  이번에는 부하 가드를 고친 뒤(아래 참조) 실제로 조용한 상태에서 돌렸고, 판정은 여전히 양쪽 다
  `NEVER SETTLED` 였습니다. **그런데 sweep 의 원자료를 보면 수렴하지 않은 곡선이 아니라
  이미 평탄한 바닥에 스파이크가 얹힌 계열입니다:**

  | | 바닥(min) | 중앙값 | 최대 | 바닥 ±5% 안에 든 회차 | 바닥의 2배를 넘는 회차 |
  |---|---|---|---|---|---|
  | JS-only (Hermes) | 10.47 ns | 10.86 | **523.04** | 22/40 | 18/40 |
  | boundary (Hermes+JSI+JNI+ART) | 233.07 ns | 305.06 | **1080.88** | 13/40 | 13/40 |

  JS-only 계열에는 `10.63 10.64 10.63 10.86` 처럼 소수점 둘째 자리까지 반복되는 구간이 있습니다.
  Hermes 는 JIT 이 없어 **1회차부터 평탄한 것이 정상**이고, 실제로 평탄합니다,
  `first repetition 10.74 ns` 가 이미 바닥입니다. 깨는 것은 수렴 부족이 아니라 **단발 스파이크**이고,
  "40회 연속 ±5%" 기준은 스파이크 **한 번**이면 무조건 깨집니다.

  **이것이 워밍업을 4배로 올려도 나아지지 않은 이유입니다.** 워밍업을 늘려도 스케줄러 스파이크는
  사라지지 않습니다. 8/19 회차에서 100k 로 JS-only 가 "150,000 에서 수렴"으로 나온 것도
  그 회차의 후반부에 스파이크가 우연히 적었던 것으로 설명됩니다, 즉 그 "수렴"도 실체가 아니었습니다.

- **부하 가드 자체에 결함이 둘 있었고 고쳤습니다 (`run-bench.sh`)**:
  1. `RN_BENCH_REQUIRE_QUIET=1` 이 `load_guard || true` 로 호출되어 **"refusing to run" 을 출력하고도
     그대로 측정을 진행**했습니다. 8/20 회차(load 4.45)가 그렇게 기록됐습니다.
  2. 부하 판정이 `build_android` **뒤에** 있어서, 스크립트 자신의 Gradle 빌드가 만든 부하를 판정하고
     있었습니다. load 1.79 에서 시작한 실행이 4.45 를 측정해 스스로를 인용 불가로 표시했습니다.
  이제 `require_quiet_before_work` 가 **아무것도 빌드하기 전에** 판정하고 실제로 종료합니다
  (임계값을 0.01 로 낮춰 거부가 정말로 빌드·실행 없이 멈추는 것을 확인했습니다).

- **남은 가설 (Remaining Hypotheses - 미확정)**:
  1. **에뮬레이터 스케줄링 지터**: 하이퍼바이저/OS 스케줄링 불확실성으로 인해 중간중간 튀는 래그(예: w400k sweep 35회차 851.88 ns, 37회차 1625.86 ns) 발생.
  2. **수렴 기준(40회 연속 ±5%)의 에뮬레이터 환경 부적합성**: 에뮬레이터 환경 특성상 40회 연속 ±5% 이내 평탄(flat) 상태 유지 기준이 지나치게 엄격함.

- **향후 필요한 조치 (Next Steps)**:
  0. **WARMUP=400,000 으로 재측정**: 위의 JS 기준선 결함이 고쳐졌는지 확인. boundary 판정은 그대로일
     것으로 예상되며, 그 예상이 맞는지가 곧 위 "워밍업 부족 아님" 결론의 네 번째 확인이 됩니다.
     **단독 실행이어야 합니다.**
  1. **실기기(Physical Device) 측정**: 에뮬레이터 가상화 레이어/하이퍼바이저 지터를 제거한 실기기 환경에서 측정.
  2. **수렴 기준(Settling Criteria) 재검토**: 에뮬레이터 전용 수렴 마진(예: ±10% 또는 아웃라이어 정화 기준) 적용 검토.

**The Hermes JS-only baseline did not settle** on either iOS run, at loads of 2.4–5.8. See the †
note in §4c: this indicts the ratios, not the boundary figures, and the fix is a quieter machine
rather than a larger `WARMUP`.

---

## 8. To fill in the blanks

1. Android 실기기(Physical Device) 또는 수렴 기준 재검토 후 re-run → 에뮬레이터 단독 실행(WARMUP=100k, 400k)은 `NEVER SETTLED`로 인용 불가 판정됨(§7d 참조). 실기기 측정 또는 에뮬레이터 수렴 기준 조정을 통해 Android 열을 완성해야 함.
2. ~~`xcodebuild -downloadPlatform iOS` (several GB, internal disk, **ask first**)~~ **Done, and it
   needed none of that.** §7a: the platform download was never required, the block was a misread
   `-showdestinations`. iOS is filled in §4b and §4c. What is left on this side is a **quiet-machine
   re-run** to clear the † on the ratio rows, boot the simulator, wait for a 1-minute load under
   ~2.0, `./run-bench.sh ios`, and check that the warmup verdict block says `Adequate` for both
   sweeps rather than only for the boundary one.
3. Startup: RN's time-to-first-render against the reference project's 237/480 ms and 4/28 ms. The
   hypothesis to test is stated in 5b. **Partly automated already**: the Android path now runs
   `am start -W` on a freshly installed APK and records it, so the cold-launch half arrives with
   every run. The relaunch half still needs a second launch.
4. A **pure C++ TurboModule** using `jsi::Function::call` on the JS thread, to get the one
   synchronous native → JS number that would make the second row of the section-1 table
   comparable after all. This is the only measurement in this document that is missing for a
   design reason rather than a scheduling one.

---

## 9. Producing the numbers: one command, and the conditions it records

```bash
./run-bench.sh host        # no device, always safe
./run-bench.sh android     # build, install, am start -W, capture; needs ONE free emulator
./run-bench.sh ios         # build, install, launch, capture; needs ONE booted simulator
./run-bench.sh all         # all three; a platform that cannot run says so and the next still runs
```

Every invocation writes `results/<platform>-<stamp>.txt` **opening with the conditions**, because a
per-call figure without them is not a measurement:

```
=== conditions ===
platform / timestamp / host (kernel, cores, RAM)
load average (before, and again after the run)
react-native version; engine (Hermes version, reported by the app about itself)
harness digest      -- sha256 over bench/*.js, the Kotlin and ObjC modules, the spec
reference repo      -- path @ commit, flagged if its working tree is dirty
counts              -- warmup 100000, N 10000, 5 reps, sweep 40 x 10000
device              -- serial, model, API level, ABI, build id   (android only)
```

Three of those exist because of specific failures: the **load average** because this workspace lost
a bisect to a commit that read 672 and 1076 ns under load; the **reference commit** because §4a's
quote went stale without anyone noticing; the **harness digest** because `rn-benchmark` is not a git
repository and there is otherwise nothing to tell two runs of "the benchmark" apart.

**Behaviour that is deliberate:**

- **Above a 1-minute load average of 4.0 the run prints a boxed warning into its own results file**
  saying the figures are not quotable. `RN_BENCH_REQUIRE_QUIET=1` turns that into a refusal.
- **A platform that cannot run writes a results file saying why**: rather than exiting. A missing
  file is indistinguishable from a file nobody looked for, and that is how an empty column becomes a
  filled one in someone's memory. `all` therefore never stops early, and prints a summary of which
  platforms produced figures.
- **Two emulators attached is an error, not a guess.** The app id is the same on both, so a
  two-device run installs over itself. `ANDROID_SERIAL=emulator-5554 ./run-bench.sh android` selects
  one, and setting it is your assertion that that emulator is free, the script cannot tell.
- **Progress lines are prefixed `#`** and printed as each row starts, because the heavy rows take
  tens of seconds and a benchmark that prints only at the end is indistinguishable from a hung one.
- **The capture window is 900 s** (`RN_BENCH_CAPTURE_SECS`), with a watchdog, and the run says
  `INCOMPLETE` if the report did not finish rather than leaving a truncated file looking whole.
- **A run that captured nothing fails.** Every results file ends with a `capture check` block giving
  the row count, whether `END` arrived and the run's own warmup verdict, and any of those going
  wrong is a non-zero exit rather than a quiet 0. §7c is the table of states and why each exists.
- **The iOS capture reads the log at `--level info`.** This is load-bearing, not a detail:
  `log stream` defaults to `--level default` and silently drops the Info-level messages React
  Native's `console.log` produces on iOS. Without it the capture is empty while the app runs
  perfectly. §7b has the A/B that established it, 0 lines against 91 on a single launch.

**How long a full quiet-machine pass should take.** The `ios` row is now measured; the rest are
still estimates, and the machine has been at load 2.4–6.9 during the iOS work:

| step | estimate | basis |
|---|---|---|
| `host` | 2–4 min | Hermes calibrates the JS rows to millions of iterations on a 1 ms `Date.now` clock, then runs 2 x 40 sweep repetitions and 7 rows x 5 reps; the previous host run's output is the only evidence and it is not timestamped per section |
| `android` build (release, all ABIs) | 3–6 min warm, 15+ min cold | `android-build.log` from the last full build |
| `android` install + `am start -W` | < 1 min | |
| `android` benchmark itself | **5–15 min** | 5.5 M boundary calls plus 3.9 M JS-row calls at 100 000 warmup x 5 reps x 18 rows, dominated by the two 1000-element rows; the host Hermes 1000-element JS row alone is 14.3 µs/call, and 550 000 of those is ~8 min if the device matches the host binary |
| `ios` build (Release, simulator, incremental) | 1–3 min | measured today; a cold build with `pod install` already done is longer |
| `ios` benchmark itself | **~75 s** | measured today: launch to `END` in the A/B capture, all 21 rows including both 1000-element rows and the two cold 40 x 10 000 sweeps |

**The `android` estimate is wrong by a factor of about 60, and the error is a unit slip.** The same
18 rows at the same counts took **~75 s on the iOS simulator**. The estimate's call counts were
right, `repeat` does pay the warmup on every repetition, so 5 x (100 000 + 10 000) = 550 000 calls
per row, and 5.5 M boundary calls is correct, but "550 000 of those is ~8 min" is not: 550 000 x
14.3 µs is **7.9 seconds**, not 8 minutes. Microseconds were read as milliseconds.

Checked against the measurement rather than merely re-divided: on iOS the boundary `sumArray(1000)`
row is 550 000 x 64.6 µs = **35.5 s**, the two async round trips are **15.2 s** together, and the
rest of the rows and both sweeps account for the remainder of the 75 s. Android still pays a JNI hop
per boundary call that iOS does not, so it will be slower than 75 s, by how much is **미측정**.

**So budget under 10 minutes for `./run-bench.sh all` on a quiet machine**, most of it builds
rather than benchmarking. If it ever does prove too slow, the honest fix is to drop `sumArray(1000)`
to a smaller length and say so, **not** to lower the warmup, which is the one number §3a shows
there is no room in.
