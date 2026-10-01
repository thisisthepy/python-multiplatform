# rn-benchmark

A React Native app whose only purpose is to price the **JS ↔ native boundary** with the same
measurement method the [PythonMultiplatform](../thisisthepy/PythonMultiplatform) repository uses to
price its **Python ↔ Kotlin** boundary, so that the two can be read next to each other.

The comparison itself, including what corresponds to what and what is still unmeasured, is in
[`COMPARISON.md`](COMPARISON.md). This file is how to run it.

---

## One command

```bash
./run-bench.sh host        # JS-only rows + the warmup convergence sweep. No device. Always safe.
./run-bench.sh build       # Build Android and iOS. Touches no device.
./run-bench.sh android     # Build, install, launch, capture the report from logcat.
./run-bench.sh ios         # Build, install, launch, capture from the simulator log.
./run-bench.sh all         # host, then android, then ios.
```

Every run writes to `results/<platform>-<timestamp>.txt` as well as to the terminal, **opening with
the conditions it was taken under** — load average before and after, device, engine version,
reference-repo commit, a digest of the harness sources, and the warmup/iteration/repetition counts.
A per-call figure without those is not a measurement. See `COMPARISON.md` §9.

`android` and `ios` each require **exactly one** device: the app id is the same on all of them, so
a two-device run would install over itself. Set `ANDROID_SERIAL` to choose when more than one is
attached — doing so is your assertion that that device is free, which the script cannot check.
`ios` will not boot a simulator for you — it expects one already booted, deliberately, because the
simulators on this machine are shared.

A platform that cannot run **writes a results file saying why** and returns non-zero; `all` carries
on to the next platform and prints a summary. Nothing is skipped silently. Above a 1-minute load
average of 4.0 every run prints a warning into its own output that its figures are not quotable;
`RN_BENCH_REQUIRE_QUIET=1` makes that a refusal instead.

| variable | default | |
|---|---|---|
| `ANDROID_SERIAL` | — | pick one of several attached devices |
| `RN_BENCH_REQUIRE_QUIET` | `0` | `1` = refuse to run on a busy machine |
| `RN_BENCH_MAX_LOAD` | `4.0` | the threshold for the above |
| `RN_BENCH_CAPTURE_SECS` | `900` | how long to wait for the on-device report |

Supporting scripts, usable on their own:

| script | what it does | needs a device? |
|---|---|---|
| `./run-host-sweep.sh [hermes\|node\|both]` | JS-only rows and the 40-repetition convergence sweep | no |
| `./build-ios.sh [Debug\|Release]` | `xcodebuild` for the simulator SDK | no |
| `./check-ios-module.sh` | compiles `Bench.mm` against the generated TurboModule protocol | no |

---

## What is measured

Nine synchronous JS → native rows, one per shape, each with a JS-only row of the same shape beside
it in the same run:

| row | what it charges |
|---|---|
| `zeroArgs()` | the boundary and nothing else |
| `addInts(3, 4)` | two numbers across, one back |
| `stringLength(8 / 128 chars)` | one string conversion |
| `echoString(8 / 128 chars)` | two string conversions |
| `sumArray(8 / 1000 elements)` | array conversion; run at two lengths so the per-element slope separates from the intercept |
| `sumObject(3 keys)` | object/map conversion |
| `nativeLoopNs(...)` | the callee with the crossing removed, timed by the native clock |

Plus, kept in their own block and never subtracted from anything, the native → JS direction as
callback and Promise **round trips** — see "the asymmetry" in `COMPARISON.md`.

### The method, and why it is copied rather than invented

`bench/harness.js` is a port of the reference project's
`python-multiplatform/src/commonTest/kotlin/python/multiplatform/overhead/Benchmark.kt`. Two
boundary costs measured with two different loop shapes are not comparable, which is the whole point
of this repository. What that buys, specifically:

- **Warmup of the same shape and the same block as the timed loop**, never a different block.
- **A warmup count taken from a measured convergence point.** The reference project settled on
  100 000 after a 40-repetition sweep; `run-host-sweep.sh` is that same sweep, so the question can
  be asked of Hermes rather than assumed. (It has an answer, and it is not the same as the JVM's —
  see `COMPARISON.md` §3.)
- **The same sweep on device, over the boundary itself.** The host sweep prices Hermes, which has
  no JIT. It cannot price the Kotlin callee, which runs on **ART, which does** — and which the
  reference project measured as needing the largest warmup of any target it tested. So the
  on-device run sweeps `addInts` through the TurboModule, cold, before anything else, and every
  report ends with a verdict on whether its own warmup was large enough. `COMPARISON.md` §3a is
  why this is the sweep that matters.
- **min–max over repetitions, no outlier dropped.** A single reading is not a measurement, and a
  dropped outlier hides the case where the host's spread is larger than the effect.
- **Every baseline measured in the same process.** No row is compared against a number from another
  run, another build or another machine.
- **No wall-clock assertion.** The only thing checked is that a row came back positive rather than
  as a zero from a clock with no resolution — the failure that would otherwise read as "the
  boundary is free".

---

## Layout

```
rn-benchmark/
  env.sh                    node, npm cache, Android SDK, Ruby/CocoaPods. Source before anything.
  run-bench.sh              the one command
  run-host-sweep.sh         Hermes + Node, JS-only rows and the convergence sweep
  build-ios.sh              xcodebuild for the simulator SDK
  check-ios-module.sh       clang compile-check of the iOS TurboModule against its generated protocol
  COMPARISON.md             the comparison with PythonMultiplatform
  tools/                    standalone Hermes VM (hermes-engine-cli)
  RNBench/                  the app (React Native 0.87.0, New Architecture, Hermes)
    bench/harness.js        the port of Benchmark.kt
    bench/jsRows.js         JS-only rows: the language floor
    bench/host-sweep.js     the host runner (Hermes and Node)
    bench/appRun.js         the on-device runner
    App.tsx                 runs on mount, prints every line separately for logcat
    modules/bench/          the TurboModule, as an autolinked local library
      src/NativeBench.ts    the codegen spec
      android/…/BenchModule.kt
      ios/Bench.mm
```

`modules/bench` is a **local library**, not app-level code, so autolinking wires it on both
platforms: CocoaPods picks up `react-native-bench.podspec`, Gradle picks up `android/`, and codegen
runs from the library's own `codegenConfig`. That avoids editing `project.pbxproj` by hand, which
is the usual reason an app-level native module is painful to reproduce.

---

## This machine

`env.sh` encodes the environment without naming any path outside this repository. Machine-specific
toolchains come in through variables; caches it creates go under `.caches/` here (git-ignored):

- **Node.** RN 0.87 wants `^22.13 || ^24.3 || >=26`, so the Gradle-cached `node-v22.0.0` does
  **not** qualify. Set `RN_BENCH_NODE_HOME` to a qualifying install (the original runs used emsdk's
  bundled 24.19.0) when the `node` on PATH is not one. The npm cache defaults to `.caches/npm`.
- **Ruby.** On the machine of the original runs the rbenv Ruby 3.2.2 was broken: it had been
  installed under a previous account name, so every Mach-O in it and every shebang pointed at the
  old home directory and `pod` died with `bad interpreter`. The workaround was a relocated **copy**
  whose install names, shebangs and OpenSSL references were repaired, with `RUBYLIB` set because the
  interpreter's compiled-in load path still pointed at the old prefix. Point `RN_BENCH_RUBY_HOME` at
  such a copy if you need one. `GEM_HOME` defaults to `.caches/gems`; CocoaPods 1.15.2 comes from the
  project's `Gemfile`.
- **Android NDK.** AGP auto-installs NDK 27.1.12297006 (2.4 GB) into the SDK. On a machine whose
  internal disk is short, move it elsewhere and leave a symlink so the SDK still finds it.

The Android SDK needed `platforms;android-37.0` (RN 0.87 compiles against SDK 37). The bundled
`cmdline-tools` was too old to know that package; `cmdline-tools/latest-2` was installed alongside
it and used for that one command.

---

## Status

| | state |
|---|---|
| Android build | **green** — debug APK builds, JSI shim links into `libappmodules.so`, `BenchPackage` autolinked |
| Android run | **done but not quotable** — full report, 21 rows, on API 26 emulator. `results/android-20260814-203238.txt`, load 3.17; warmup verdict is INSUFFICIENT (both sweeps signal inadequate settling), so figures are marked NOT QUOTABLE. `COMPARISON.md` §7c |
| iOS `pod install` + codegen | **green** — 87 pods, `RNBenchSpec` generated |
| iOS module compile | **green** — `check-ios-module.sh`, with a negative control |
| iOS app build | **green** — `./build-ios.sh Release`, `** BUILD SUCCEEDED **`. The earlier "blocked" verdict was a misread `-showdestinations`; nothing was downloaded to clear it. `COMPARISON.md` §7a |
| iOS run | **done** — full report, 21 rows, on iPhone 17 Pro / iOS 26.2. `results/ios-20260814-200903.txt`, ~75 s |
| host JS rows + warmup sweep | **done** — see `COMPARISON.md` §3 |
| on-device warmup sweep | **done on iOS** (boundary settles at 10 000 calls, 10x margin); **attempted on Android** but did not settle within ±5% over 40 repetitions (first 806 ns against plateau 308 ns = 2.62x, result not conclusive). §3a |

Two things to know before reading or re-running any of this, both written up in `COMPARISON.md` §7:

- **The iOS capture needs `log stream --level info`.** Without it the capture is empty while the app
  runs the whole benchmark perfectly — `console.log` reaches the unified log at Info level, which
  `log stream` drops by default. This cost two runs that exited 0 with empty results files.
- **An empty or incomplete capture is now a non-zero exit**, and every results file ends with a
  `capture check` block. A run that produced no rows can no longer look like a measurement.
