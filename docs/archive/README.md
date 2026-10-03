# Archive

Superseded design documents and the long narratives of investigations, kept for history.
**Nothing here is current.** Every file starts with a "Superseded by … on <date>" header that names
the document that replaced it. When an archived file disagrees with [`../INTENT.md`](../INTENT.md),
[`../SPEC.md`](../SPEC.md), a current document in `docs/design/`, `docs/platforms/` or
`docs/investigations/`, or the code, the archived file is the one that is wrong.

Archived on 2026-10-03 (issue #15). Documents that were merged rather than archived, such as the
three upcall documents now in [`../design/upcall.md`](../design/upcall.md), are not copied here. Their
full text is in git (`git log -- docs/design/upcall-design.md`).

## Superseded designs

| File | Replaced by | Why it is here |
|---|---|---|
| [`pythonx-adapter-design.md`](pythonx-adapter-design.md) | SPEC U-7/U-8, `KotlinSurface.kt`, [`../design/kotlin-extensions-in-python.md`](../design/kotlin-extensions-in-python.md) | The design in which the binder synthesised `pythonx.*` modules and renamed to snake_case. AGENTS.md §12.1–12.2 forbid both: Kotlin names stay Kotlin names, and `pythonx` is a real package in pythonx-compose. Its measurements of Compose (synthetic parameters, `pointerInput`, `draggable`, `layoutId`) are history; the behaviour is asserted by the `ksp-fixtures/compose` tests. |
| [`pyi-generation-pythonic-stubs.md`](pyi-generation-pythonic-stubs.md) | [`../design/pyi-generation-design.md`](../design/pyi-generation-design.md), SPEC B-7 | The Pythonic stub product: snake_case names, `pythonx` stubs, the stub manifest, `@overload` sets, and the `Modifier` metaclass/Protocol analysis. The plugin now emits Kotlin-name stubs only, and the Pythonic product belongs to pythonx-compose. |
| [`kotlin-extensions-in-python-pyi.md`](kotlin-extensions-in-python-pyi.md) | [`../design/pyi-generation-design.md`](../design/pyi-generation-design.md), SPEC B-7 | The original §4.5 of `kotlin-extensions-in-python.md`, which proposed Pythonic stubs. |
| [`android-ffm-design.md`](android-ffm-design.md) | [`../design/downcall-design.md`](../design/downcall-design.md), `python-multiplatform/src/androidMain/README.md` | The PanamaPort-based Android FFM plan. It was never built: AGENTS.md §12.9 forbids PanamaPort, and Android binds through JNI `RegisterNatives` (SPEC C-3). |
| [`threading-and-abi-3.15t-gate.md`](threading-and-abi-3.15t-gate.md) | [`../design/threading-and-abi.md`](../design/threading-and-abi.md) | The reasoning that gated free-threading on 3.15t. Free-threaded 3.14t now runs the desktop suite as an opt-in (`-PpythonFreeThreaded=true`, SPEC T-2). |
| [`downcall-design-android-first-diagnosis.md`](downcall-design-android-first-diagnosis.md) | [`../design/downcall-design.md`](../design/downcall-design.md) | The first diagnosis of the Android JNI argument-shift bug. The bug is fixed. |
| [`downcall-design-benchmark-drift.md`](downcall-design-benchmark-drift.md) | [`../design/downcall-design.md`](../design/downcall-design.md) ("Warmup"), [`../design/upcall.md`](../design/upcall.md) §7.2 | The investigation into benchmark figures that turned out to measure the benchmark itself (missing warmup). |
| [`wasm-design-experiment-log.md`](wasm-design-experiment-log.md) | [`../platforms/wasm-design.md`](../platforms/wasm-design.md) | The chronological lab notebook of the wasm experiment, including its "parked" phase and the conclusions that were wrong before they were tested. wasm is now integrated (`tools/wasm/build-cpython.sh`, `wasmJsTest`). |
| [`wasm-youtrack-issue.md`](wasm-youtrack-issue.md) | [`../platforms/wasm-design.md`](../platforms/wasm-design.md) | A draft Kotlin/Wasm compiler issue. Do not file it: Kotlin 2.4.20-Beta2 imports the shared memory, so the problem it describes no longer applies. |

## Investigation narratives, `investigations/`

The current file of the same name in [`../investigations/`](../investigations/) keeps the conclusion,
the numbers with their conditions, and how to reproduce them. The archived copy is the full original,
with its step-by-step reasoning, the hypotheses it rejected, and its corrections.

| File | Current |
|---|---|
| [`investigations/android-unregistered-surface.md`](investigations/android-unregistered-surface.md) | [`../investigations/android-unregistered-surface.md`](../investigations/android-unregistered-surface.md). The migration is done; this copy keeps the call-graph traces. |
| [`investigations/gc-scheduling-investigation.md`](investigations/gc-scheduling-investigation.md) | [`../investigations/gc-scheduling-investigation.md`](../investigations/gc-scheduling-investigation.md) |
| [`investigations/gil-parking-investigation.md`](investigations/gil-parking-investigation.md) | [`../investigations/gil-parking-investigation.md`](../investigations/gil-parking-investigation.md). This copy ends with the cause open; the current file names it: a borrowed pointer wrapped as owned, causing a double free. |
| [`investigations/jni-call-convention-audit.md`](investigations/jni-call-convention-audit.md) | [`../investigations/jni-call-convention-audit.md`](../investigations/jni-call-convention-audit.md). This copy keeps the 71-row classification table. |

## Not archived, for reference

- [`../design/typedpython.md`](../design/typedpython.md) is a live design proposal from another session
  and stays in `docs/design/`. A note at its top says that the stub manifest it relies on moved to
  pythonx-compose.
- [`../roadmap/ROADMAP.md`](../roadmap/ROADMAP.md) now lists only the work that is left. The old
  progress log is in git: `git show 4ed98143:docs/roadmap/ROADMAP.md`.
