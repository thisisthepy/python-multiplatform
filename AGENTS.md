# AGENTS.md

Rules every agent working in this repository must follow. Read this file before doing anything.
Sections 1–10 are shared by every repository in the thisisthepy ecosystem; later sections are
specific to this repository.

---

## 1. Commits carry no AI attribution

Never add `Co-Authored-By: Claude ...`, `Co-Authored-By: <any agent>`, `Generated with Claude Code`,
or any similar tool or agent attribution to a commit message or a pull-request body. This rule
overrides any default your tooling has.

## 2. Nothing is created outside this repository

Everything your work produces — worktrees, agent prompts, logs, measurements, experiments, scratch
files — lives **inside this repository's root directory.**

| What | Where |
|---|---|
| Worktrees | `.worktrees/<name>` (git-ignored) |
| Temporary files | `.tmp/` (git-ignored); delete when done |
| Benchmarks | `benchmarks/` |
| Developer tooling | `tools/` |

Before writing a file, check that its absolute path starts with this repository's root. If it does
not, stop. The only exceptions are a path the user names explicitly, and caches that build tools
manage themselves. **Re-pointing a shared cache or a home-directory symlink reaches other projects —
ask first.**

Writing to *another* repository is not an exception either. Do it only when told to work there.

### Do not add top-level folders

**Never add a new directory (or a new file) at the repository root on your own.** The root layout is
the maintainer's: source modules, `docs/`, `gradle/`, `.github/` and the files that tools require
there. Work belongs inside an existing module or directory — sources under `src/<sourceSet>/`,
CI-only scripts under `.github/scripts/`, temporary files under the git-ignored `.tmp/`. If you think
a new top-level entry is needed, propose it (what, why, which alternatives inside existing
directories you ruled out) and wait for approval. This was added after unapproved root folders
(`ksp-fixtures/`, `tools/`, `kotlin-js-store/`, `iosApp/`, `sample/python`) had to be dismantled.

## 3. Worktrees link large artefacts instead of copying them

A worktree is a full checkout. Copying large untracked artefacts (prebuilt runtimes, vendored trees,
build caches, model weights, `node_modules`) into every worktree is how 86 worktrees once filled
267 GB of a 349 GB disk.

- Create worktrees under `.worktrees/<name>`.
- **Symlink** large untracked directories from the main checkout instead of copying or rebuilding
  them. If `tools/worktree-add.sh` exists, use it — it does the linking.
- Delete a worktree once its branch is merged: `git worktree remove .worktrees/<name>`.
- Periodically delete `build/` directories inside worktrees; they only grow.

## 4. Branches

| Branch | Who writes to it |
|---|---|
| `feat/<topic>` | You. All work happens here. Never name a branch `work/...`. |
| `develop` | Merged into from `feat/` branches after verification. Never commit to it directly. |
| `release` | **CI only.** Not a standing branch: CI regenerates it from every push to `develop`, in the main-only file layout, and opens the PR into `main`. It may not exist. Never write to it. |
| `main` | **Pull request from `release` only.** Never push or merge to it directly. |

Only `main`, `develop` and `release` are standing branches. A `feat/` branch lives until its pull
request merges: merge with `gh pr merge --delete-branch`, then delete the local branch and its
worktree. Periodically delete every branch already merged into `develop`, remote and local
(`git branch -r --merged origin/develop`); an unmerged branch older than a few days is either
landed or reported, not left. Branches named `release-*` are preserved snapshots: keep them.

`main` carries a reduced layout: of the Markdown files, only `README.md` stays at the repository
root, and `docs/` keeps only its subdirectories (no Markdown files directly under `docs/`).
CI runs `tools/release/sync-release.sh` (`.github/workflows/release-sync.yml`) to produce that layout; do not hand-edit `release` or `main`.

### Issues and pull requests

Every new feature goes through an issue and a pull request:

1. Before starting, search the repository's issues (`gh issue list --state all --search "<keywords>"`).
2. If no issue covers the work, open one (`gh issue create`) stating what and why, and the
   completion criterion — which tests must pass.
3. Work on a `feat/<topic>` branch, push every commit, and open a pull request into `develop`
   whose body contains `Closes #<number>`.
4. Merge into `develop` through that pull request (`gh pr merge`), not by a local merge, so the
   issue is linked.
5. Then close the issue yourself: `gh issue close <number> --comment "Landed in develop via #<PR>"`.
   GitHub's `Closes #N` only fires when a pull request merges into the default branch (`main`),
   and these pull requests merge into `develop`.

## 5. Intent → Spec → Test → Code

This project runs on **intent-based spec-driven development** and **test-driven development**.

1. `docs/INTENT.md` states what the project is for. It is the boundary. **The spec may not go
   beyond the intent.**
2. `docs/SPEC.md` states what the project does. A behaviour change starts as a spec change.
3. Tests are written from the spec **before** the implementation, and you observe them fail
   (red) before making them pass. Report the red output.
4. Code is written to make the tests pass.

If a request conflicts with `docs/INTENT.md`, say so instead of implementing it.

## 6. User-authored files are specification

Files the user wrote by hand — notebooks, example build files, sample apps — are the specification.
Read them **first**. Never delete, rewrite, or `git add` them without being told to. Generated
documentation (roadmaps, design notes) is a record of work, not a requirement; when the two
disagree, the user's file wins.

## 7. Show a conclusion before acting on it

Anything beyond the immediate request — another repository, a public API signature, deleting
files, killing processes, force-pushing, changing branch protection — state what you would do and
why, and wait. Investigating, measuring, and reporting are always fine.

**Push every commit right away.** After you commit — on a work branch or on `develop` — push it to
the remote immediately; no confirmation is needed. Never push to `main` or `release` by hand, and
never force-push without the user's explicit approval.

When a rule and backward compatibility conflict, **the rule wins.** List the callers that break and
fix them; do not keep the forbidden thing "so nothing breaks".

## 8. Verification that can fail

- Never read a build's exit code through a pipe (`| tail`, `| grep`). Redirect to a file, then read
  `$?`. A background command ending in `echo` always reports 0.
- Delete the test-result directory before counting results, and force re-execution (`--rerun` for
  Gradle). Stale XML otherwise reports an old, larger number.
- Run independent test modules as **separate** invocations. One invocation can hide an ordering
  dependency.
- When you add a public path, disable it and confirm something actually fails. If nothing fails,
  nothing uses it.
- **Do not trust an agent's report.** Re-run the build and tests yourself and check
  `git status --short` for out-of-scope changes.
- **Never `git add -A`.** Stage explicit paths. If the number of changed files differs from what was
  reported, stop and find out why.
- Measurements run alone, unfiltered, after checking `uptime`.

## 9. Reporting

Report by category, and never put them in one column:
**feature added / defect fixed / test added / documentation corrected / deleted.**
A rising test count is not progress when the tests assert an absence. Before writing "nothing left
to implement", say what you counted against.

### Writing docs and code text

- **No em-dash.** Do not write the em-dash character (U+2014) anywhere: docs, guides, README,
  comments, docstrings, strings, commit messages, PR and issue text. Use a comma, colon,
  parentheses, or two sentences.
- **Install and run examples use `uv`, `ppp` (pypackpack) or `tcl` (toolchain-lite).** Never write
  a `pip install` example in a guide, README or doc.
- **Tone:** the pythonx-compose guide is the reference for how docs read; align with it.

## 10. Agents

- A headless agent (`claude -p`, `agy -p`) has **no next turn**. Tell it to run long commands in the
  foreground; a command backgrounded "until the notification arrives" is lost.
- Pass the model explicitly. Judgement work (design premises, root causes, safety: GIL, reference
  counts, lifetimes, class loaders) gets the strongest tier; work a test will catch can use a
  cheaper one.
- Give every agent prompt the absolute paths it may write to, and repeat rule 2 in it.
- **Subagents do not run heavy local builds.** Subagents write code, design, investigate, review
  and document. Gradle builds, cargo builds, the test gate and model runs are done by the session
  itself — one at a time on this machine — or by CI (GitHub Actions) on a pushed branch. Several
  sessions share one machine; parallel local builds slow every one of them.

---

# Rules specific to python-multiplatform

## 11. What this repository is

python-multiplatform embeds **CPython** in **Kotlin Multiplatform** and provides Kotlin ↔ Python
interoperability in both directions:

- **Downcall** (Kotlin → Python): Kotlin calls the CPython Stable ABI through one `expect`
  declaration per C function and a typed object model built on top of it.
- **Upcall** (Python → Kotlin): Python reaches Kotlin through a **build-time generated function
  table** and a single entry point per platform.

| Path | What it is |
|---|---|
| `python-multiplatform/` | The library: FFI layer, object model, upcall runtime |
| `python-multiplatform-ksp/` | KSP processor that generates the upcall table from Kotlin sources |
| `python-multiplatform-gradle-plugin/` | Gradle plugin: `PYTHONHOME` staging, artifact walker (binds prebuilt jars/klibs), `.pyi` stub generation |
| `ksp-fixtures/` | Consumer modules that exercise what the generators produce |
| `sample/` | Compose Multiplatform demo app (desktop, Android, iOS, wasmJs, GraalVM native image) |
| `binary/` | Source archives of per-platform CPython distributions |
| `python_for_kotlin_binding.mermaid` | User-authored sketch of the object model (see rule 14) |
| `docs/INTENT.md`, `docs/SPEC.md` | Intent and behavioural contract (rule 5) |
| `docs/design/`, `docs/platforms/`, `docs/investigations/`, `docs/roadmap/` | Current design records (one per topic), platform notes, investigation conclusions, the work left to do |
| `docs/archive/` | Superseded designs and investigation narratives, kept for history (see its `README.md`) |

### Source-set hierarchy

`commonMain` is the root. It splits into `jvmMain` (→ `androidMain`, `desktopMain`) and `nativeMain`
(→ `iosMain`, `artMain` → androidNative). `wasmJsMain` hangs off `commonMain` (experimental). The
hierarchy is built **by hand** in the `sourceSets` block of `python-multiplatform/build.gradle.kts`;
the default hierarchy template is not applied.

### Layers

- **FFI** — `python/native/ffi/EmbedAPI.kt` declares the CPython Stable ABI as `expect` functions
  (about 330). Each platform supplies `EmbedAPI.<platform>.kt` + `bindings.kt` as `actual`s.
  `NativePointer` (a value class) unifies the platform pointer representation.
- **Object model** — `python/multiplatform/ffi/` holds the Kotlin wrapper types rooted at `PyObject`.
- **Upcall runtime** — `python/multiplatform/reflection/` and `python/multiplatform/ffi/upcall/`.

## 12. Decisions already made — do not reverse them

These come from the maintainer or were settled with evidence. Raise a concern instead of working
around one.

1. **The binder never exports a Kotlin namespace under a different name.** A Kotlin fully-qualified
   name in Python means the original Kotlin (`androidx.compose...` is androidx). The binder must
   contain **no** feature that renames `androidx` to `pythonx`, not even as an opt-in or a default
   kept "for compatibility" (rule 7).
2. **`pythonx` is a real Python package** (it lives in the separate `pythonx-compose` repository).
   Its code imports the `androidx` modules and makes them Pythonic. Do not build anything here that
   synthesises `pythonx.*` modules or prevents a real on-disk `pythonx` package from loading.
3. **No runtime reflection, no dynamic binding.** Kotlin/Native has effectively no reflection and
   GraalVM native image is closed-world. Upcalls go through a table generated at build time.
4. **Never look up JVM methods by name.** The artifact walker generates Kotlin source and `kotlinc`
   compiles it; the compiler does the name mangling. (Value-class mangling hashes only the
   signature, so `padding`, `size`, `width` and `height` all share one suffix — name lookup is
   impossible in principle.)
5. **`.pyi` generation belongs to the Gradle plugin** (the PyREPL approach).
6. **`JClass` / `KClass` / `ObjcClass` work only where the platform really has them.** Do not stub
   them to force uniformity.
7. **Exposure is a blacklist.** Every `public` declaration is registered; `@PythonInternal` opts out.
   A whitelist (`@PythonAPI`) was rejected.
8. **Parallelism comes from free-threaded CPython (3.14t works on desktop with `-PpythonFreeThreaded=true`; default is GIL; see ROADMAP §9),** not from sub-interpreters.
9. **Do not add PanamaPort as a dependency** (licence and dependence on ART internals).

## 13. Upcalls must work in a GraalVM native image

An upcall (Python → Kotlin) is not done when it passes on the JVM. It must also work in a **GraalVM
native image** — that is the reason the design uses a build-time table. Only building the image
proves the choice holds.

- Toolchain: **Liberica Native Image Kit**.
- Reference wiring: the sibling checkout `compose-graal-hello` (Compose + GraalVM native image).
- Target: the `sample/` app's native-image build path; procedure and last record in
  `docs/platforms/graal-native-image-verification.md`.

**An upcall that passes only on the JVM is not complete.**

## 14. Test-driven development in this repository

Rule 5 applies. In addition:

- `python_for_kotlin_binding.mermaid` is the user's sketch of the object model. It shows the
  relationships between types and the scope of features. It is **not a frozen spec**: concrete
  method names and signatures may change during implementation.
- Next to behaviour tests, write **measurement tests that expose expensive points**: cost per FFI
  call, pointer boxing, reference-count round trips, string marshalling, collection conversion.
  The `@HighOverheadNativeCall` annotation marks this intent in the code.
- Write tests so that a pre-implementation failure (red, expected) can be told apart from a
  regression.

## 15. Verifying a change

### Build commands

```bash
./gradlew <task> --console=plain > .tmp/build.log 2>&1; echo "EXIT=$?"
```

- Compile errors are lines starting with `e: `. Most `w: ` warnings (inlining in particular) are
  pre-existing; ignore them.
- In a **background** run, do not end the script with `echo` — the tool then reports `echo`'s exit
  code (always 0). Read the `EXIT=` line from the log, or make Gradle the last command.
- Android tasks need `ANDROID_HOME` set to the Android SDK.
- Before counting results, delete `build/test-results/<target>/` **and** pass `--rerun` (or
  `--rerun-tasks`). Deleting the XML alone makes Gradle skip the task as UP-TO-DATE, and a suite that
  never ran gets read as passing.

### Compilation targets

| Task | Covers | Note |
|---|---|---|
| `:python-multiplatform:compileKotlinAndroidNativeArm64` | commonMain + nativeMain + artMain | No Xcode needed. Fastest default loop |
| `:python-multiplatform:compileKotlinIosSimulatorArm64` | commonMain + nativeMain + iosMain | Needs an accepted Xcode licence |
| `:python-multiplatform:compileKotlinDesktop` | commonMain + jvmMain + desktopMain | |

**Always include the androidNative compile.** `nativeMain` is shared by iOS and androidNative;
checking only iOS lets androidNative break unnoticed (an `actual` placed only in `iosMain` once did,
for days).

### The generator consumers: `ksp-fixtures`

`:python-multiplatform-gradle-plugin:test` alone does not verify the code that **uses** what the
generators emit. Run each fixture module as a **separate** Gradle invocation (rule 8):

| Task | Catches |
|---|---|
| `:ksp-fixtures:app:desktopTest` | Upcall and proxy runtime |
| `:ksp-fixtures:compose:desktopTest` | Compose bindings and render proofs |
| `:ksp-fixtures:artifact:desktopTest` | Artifact walker and `.pyi` stubs |

Forgetting `artifact` has put red tests on `develop` twice; plugin unit tests cannot see them.
Running the three in one invocation once hid 24 failures caused by an ordering dependency.

### Merging

- Merge only from the coordinating session, in the main checkout with `develop` checked out.
  `git merge <own branch>` inside a worktree is a no-op.
- `git merge` **silently refuses** when there are uncommitted changes. Check the result directly
  (`git merge --no-edit develop && echo OK`), not by grepping for `CONFLICT`; otherwise you verify
  against the old baseline.
- **Rebuild after every merge.** Branches that pass separately can break together — two agents once
  migrated the same function independently and cinterop rejected the duplicate C wrapper. Before
  adding JNI wrappers, check for duplicates:

      grep -oE "^static [a-z]+ (f_[A-Za-z0-9_]+)" jni_onload.def | awk '{print $3}' | sort | uniq -d
      grep -oE '\{"[A-Za-z0-9_]+"' jni_onload.def | sort | uniq -d

### Inspecting an agent's work

- To check that a test is red before a fix, use `git stash push -- <file>` and `git stash pop`.
  **Never `git checkout -- <directory>`** on uncommitted agent work — it cannot be recovered (nine
  files were lost that way once).
- Agents have broken every kind of instruction here: reverting others' work with `git checkout`,
  editing `commonMain` and the source-set structure of `build.gradle.kts` outside their scope,
  "fixing" tests they were told not to touch, and deleting working implementation files. Always
  check `git status --short` and re-run the build yourself (rule 8).

## 16. Platform rules

Each source set has a `README.md` with the rules for that platform and the measurements behind
them: `python-multiplatform/src/<sourceSet>/README.md`. **Read it before touching that source set.**

| Source set | Key rules |
|---|---|
| `commonMain` | Layering: the object model must not reference `bindings`. Every C API call holds the GIL. Every wrapper states its reference convention |
| `desktopMain` | **Never `MethodHandle.invoke`; always `invokeExact`.** Pointers are `JAVA_LONG`, not `ADDRESS` |
| `androidMain` | Bind through `RegisterNatives`; choose the calling convention per API level and per function; only primitive types cross the boundary |
| `artMain` | JNI lives here only (`nativeMain` is shared with iOS); this is where composed functions go |
| `nativeMain` | Shared with iOS — no Android-only code. There is no boundary, so no composition is needed |
| `iosMain` | The framework has no stdlib → `PYTHONHOME` is required; pass env vars to simulator tests with the `SIMCTL_CHILD_` prefix |
| `wasmJsMain` | Experimental; see its README and `docs/platforms/wasm-design.md` |

### Reference conventions

Wrapping a **borrowed** pointer with `borrowed = false` makes two wrappers decref one pointer. In
this repository that corrupted the heap and crashed unrelated tests. State the convention at every
wrap site.

### Known toolchain constraints

- `EmbedAPI.kt` contains many `expect inline fun`. Combining them with `expect`/`actual` in an
  intermediate source set (`jvmMain`) has crashed Kotlin 2.0.20 with
  **`Internal error in file lowering`**. Check this before designing any JVM-family unification.
- Desktop FFI must not depend on a specific JDK version; the default JDK changes.
- Android `java.lang.ref.Cleaner` exists only on API 33+. While older API levels are supported, a
  `PhantomReference`-based path is required.
- The shell has no `timeout` command (zsh without coreutils); use each tool's own timeout option.

## 17. Drift checks

Individual steps can all be reasonable while the sum drifts away from what was asked. Each check
below exists because that happened here.

1. **The spec is what the user gave, not what we wrote.** Before starting, find and read the
   user-authored files (notebooks such as `UI.ipynb` in pythonx-compose, example projects, example
   build files, `python_for_kotlin_binding.mermaid`, `sample/`). If you cannot find a spec, ask where
   it is. `docs/roadmap/ROADMAP.md` and design notes are outputs of work, not requirements; when they
   disagree with the user's spec, the spec wins. *(A user-facing spec notebook was once classified
   as "untracked user file, do not touch" and went unread for a week.)*
2. **A structure the user states is a constraint, not a suggestion.** Before choosing a design,
   check explicitly that it is compatible with the structure the user described. If you think your
   design is better, **say so instead of building it.** *(An adapter that synthesised `pythonx.*`
   set `__path__ = []` on every module, which made the user-required on-disk `pythonx` package
   impossible to load — and then its code was deleted as "unnecessary".)*
3. **Test count is not progress.** Report by category (rule 9). Before writing "nothing left to
   implement", state what you counted against — closing every roadmap item left the gaps in
   `docs/design/ecosystem.md` §5 untouched. *(71 tests in one repository all asserted absences and
   added no capability.)*
4. **Your criterion can decide the answer.** When writing an audit or investigation prompt, write one
   line on **what this criterion cannot find**. Ask by capability — "what now provides what this
   file used to provide?" — not by identifier. When an agent's conclusion matches your hypothesis,
   be more suspicious, not less. *(An audit required identical identifiers to count code as
   migrated; Python code reimplemented as a Kotlin scanner could never match, so "nothing migrated"
   was reported as fact.)*
5. **A check that cannot fail is not a check.** Run modules separately; never `git add -A` and stop
   when the changed-file count differs from the report; when you add a public path, disable it and
   confirm something breaks. *(21 fixtures used raw addresses instead of the new public API, so
   emptying the API still passed. A 22nd changed file — a production file replaced by a tamper stub
   — was committed by `git add -A`.)*
6. **Backward compatibility ranks below rules.** If "to avoid breaking callers we must keep X" comes
   to mind, check whether X is the forbidden thing. List the callers that break and fix them.
7. **Show conclusions before executing them.** Deleting files, killing processes, changing APIs
   across repositories, and pushing are proposed and then wait (rule 7).

## 18. Running agents

### Means

| Means | Use |
|---|---|
| The coordinating session | Distribution, verification, commits, merges. **Agents do not commit.** |
| The session's `Agent` tool | Implementation and investigation. Completion notifications come back, so give it long work that needs polling (device builds, emulators). It is visible to the user. |
| `agy -p` (headless) | Implementation and investigation in a background process. Survives interrupts of the coordinating session, but has no next turn and the user cannot watch it. |

Work running **inside** the coordinating turn dies when that turn is interrupted (a user message
mid-answer once killed three multi-hour agents). Choose the means deliberately; do not trade the
user's visibility for interrupt resistance without asking.

### Model tiers

Pass the model explicitly — the `Agent` tool otherwise inherits the coordinator's model. The test is
not "how hard is it" but **"would the coordinator notice if it were wrong?"** Work a test catches can
use a cheaper tier; interpreting measurements, deciding what stays open, and safety arguments cannot.

| Work | `Agent` tool | `agy` |
|---|---|---|
| Design premises, root causes, "what does this result mean" | `opus` | `claude-opus-4-6-thinking`, `gemini-3.1-pro-high` |
| Safety: GIL, reference counts, lifetimes, class loaders | `opus` | `claude-opus-4-6-thinking` |
| Implementing an existing spec (TDD), regression hunts, reading code against docs | `sonnet` | `claude-sonnet-4-6`, `gemini-3.1-pro-high` |
| Doc sync, stale numbers, bulk mechanical edits, running measurements | `haiku` | `gemini-3.6-flash-high` / `-medium` |

Observed here: agent failures came from **judgement**, not from mechanical work, and also happened
at the top tier. A prompt that points to the spec document and pins the baseline numbers matters
more than the tier.

### What every agent prompt contains

- Rule 2 restated with the absolute paths the agent may write to; temporary files go in the
  worktree's `.tmp/`.
- "Do the work yourself; do not delegate to another agent."
- "Run long commands in the **foreground**. You have no next turn." (Three headless agents were lost
  to a backgrounded command, one of them an `xcodebuild`.)
- "Do not run `git commit`, `add`, `checkout`, `restore` or `stash`." The only exception is
  `git merge --no-edit develop`, and if it is refused, report and stop.
- "Do not touch files outside your assigned directories" — in particular the source-set structure in
  `build.gradle.kts` and `commonMain`, unless told to.
- "Do not report what you did not observe. Count numbers for real. If you do not know, say so; if you
  could not do it, say so and say where it stopped."
- "Never write `Co-Authored-By` or `Generated with Claude Code` anywhere" (rule 1).

### `agy` usage

```bash
agy -p "<prompt>" --model <model> --print-timeout 40m \
    --dangerously-skip-permissions \
    --add-dir <worktree> [--add-dir <other read dirs>]
```

- `--print-timeout` defaults to 5 minutes; long work needs more.
- `--dangerously-skip-permissions` is required headless: without it the first tool needing the
  `command` permission is auto-denied and agy exits having done nothing, with no failing exit code.
- `--effort` is accepted only by model names without a built-in level (e.g. `gemini-3.6-flash`).
  Claude models and names like `gemini-3.6-flash-high` reject it and exit immediately — which looks
  like success in a background run.
- After launching, read the head of the log **and** the worktree's `git status --short`; an exit
  code does not prove the agent did anything.
- Before claiming a quota problem, query it: `agy -p "/usage"` (a slash command, not a
  subcommand). There are two windows, weekly and five-hour, tallied per model family and per
  account; the five-hour window is usually the one that blocks. Do not guess from
  `Individual quota reached`. If one account or family is blocked, continue with another.
- An authentication failure (`OAuth session expired`) is not an expired subscription; log in again.
  Different symptoms need a fresh diagnosis.

### Parallelism

- Isolate TDD and large work in a worktree (rule 3). An agent in its red phase breaks the build for
  every other agent sharing the tree; splitting file ownership does not prevent that.
- The limit is the machine (8 cores, 16 GB RAM), not git: **3–4** agents that build and test, plus
  3–4 that only read or write docs. **Android/iOS device tests: one at a time** — there are two
  emulators and the same package name collides on install.
- Split work by directory; agents sharing a file overwrite each other. Agents sharing one Gradle
  daemon serialise their builds.
- **Measurements run alone, unfiltered** (rule 8). Under load 10–12 the same commit measured
  672–1076 ns per upcall, and narrowing the suite with `--tests` moved both ends of a bisect to
  1300–1400 ns. Those numbers mean something only inside the full suite.

## 19. Disk hygiene

- The workspace lives on the external volume `/Volumes/macMini`; the internal disk has almost no free
  space. Toolchains, SDKs, worktrees and build output go on the external volume. Check `df -h /`
  before creating anything large on the internal disk.
- Worktree `build/` directories only grow (they were 53–74 % of each worktree's size). Delete them
  periodically:

      find .worktrees -maxdepth 3 -type d -name build -not -path '*/build/*' -print0 | xargs -0 -n 20 rm -rf

  First make sure no build is running — a Gradle/Kotlin daemon holding files leaves a half-deleted
  tree. A live process is not necessarily a running build (an `aapt2` once sat for four days); check
  `etime`. Check each worktree for uncommitted changes before deleting anything.
- Deleting the worktree itself is the next step and is safe once its branch is merged.
- Gradle and Konan caches (`~/.gradle`, `~/.konan`) are managed by the user. Do not move them or
  re-point their symlinks (rule 2).
