# Free-threaded CPython for wasm32-emscripten

Status: build recipe written from the CPython 3.15.0 source; **not yet run in CI.** Nothing
here has been compiled, linked or run. Tracking issue: #175 (this is its first step, the
build experiment). The owner approved the CI job on 2026-11-13 ("#175 may add the new wasm
3.15t CI build job").

python-multiplatform gets its parallelism from free-threaded CPython (decision 8 in
`AGENTS.md`). The wasmJs target today runs a GIL, no-threads CPython 3.14.2
([`wasm-design.md`](wasm-design.md)). No prebuilt free-threaded CPython with pthreads exists
for Emscripten, so the workflow `.github/workflows/build-ft-wasm.yml` builds one with
CPython's own driver and checks that it runs threads without the GIL under node. It does not
change `wasm.yml`, `python-multiplatform/scripts/wasm/build-cpython.sh` or the runtime the
wasmJs suite uses.

## What is built

| Host | Tooling | Output |
|---|---|---|
| `wasm32-emscripten`, pthreads, `--disable-gil` | `Platforms/emscripten` (CPython 3.15) | `python-3.15.0t-wasm32-emscripten.tar.gz` |

The archive holds `python.mjs`, `python.wasm`, CPython's `node_entry.mjs` and `streams.mjs`,
`lib/python3.15t/` (the standard library without `test/`, plus `_sysconfigdata_*.py` and an
empty `lib-dynload/`), `include/python3.15t/` (with the host `pyconfig.h`) and
`BUILD-INFO.txt` (Emscripten version, flags, whether the trampoline patch was applied). The
driver has no install or package step for Emscripten, so the script assembles it.

## Choices made for this experiment

These are agent proposals from the #175 investigation, not decisions:

- **Emscripten**: the version CPython itself pins (`emscripten-version` in
  `Platforms/emscripten/config.toml`, 4.0.19 for 3.15.0), not the 5.0.3 that
  `build-cpython.sh` pins for `pyemscripten_2026_0`. Which version the library should use is
  open. The dispatch input `emscripten_version` overrides it, so 5.0.3 can be tried without
  editing the script.
- **Node**: 24, the version `config.toml` pins, passed as `--host-runner node`.
- **Flags**:

  ```sh
  python3 Platforms/emscripten configure-build-python -- --disable-gil
  python3 Platforms/emscripten make-build-python
  EMCC_CFLAGS=-pthread python3 Platforms/emscripten make-libffi
  EMCC_CFLAGS=-pthread python3 Platforms/emscripten make-mpdec
  python3 Platforms/emscripten configure-host --clean --host-runner node -- \
    "CFLAGS=-DPY_CALL_TRAMPOLINE -sUSE_BZIP2 -fwasm-exceptions -sSUPPORT_LONGJMP=wasm -pthread" \
    "LDFLAGS=-fwasm-exceptions -sSUPPORT_LONGJMP=wasm -pthread -sPTHREAD_POOL_SIZE=8 -sMAXIMUM_MEMORY=2GB" \
    --disable-gil --enable-wasm-pthreads
  python3 Platforms/emscripten make-host
  ```

  The driver puts `CFLAGS=-DPY_CALL_TRAMPOLINE -sUSE_BZIP2` before user arguments and
  autoconf keeps the last assignment, so the full string is repeated. `--enable-wasm-pthreads`
  makes configure add `-pthread`, `-sUSE_PTHREADS` and `-sPROXY_TO_PTHREAD` itself.
  `-sPTHREAD_POOL_SIZE=8` pre-creates Workers, because `Thread.start()` waits for the new
  thread and a Worker cannot start while the JS thread that requested it is blocked.
  `-sMAXIMUM_MEMORY=2GB` gives the shared memory a maximum, which shared memory requires.
- **libffi and mpdec** are rebuilt with `-pthread` through `EMCC_CFLAGS` (emcc appends it to
  every invocation), because the driver builds them without it and wasm-ld is expected to
  refuse non-atomic objects in a shared-memory link.
- The build Python is free-threaded too, as in [`free-threaded-mobile.md`](free-threaded-mobile.md).

## The one source patch

`Python/emscripten_trampoline.c` instantiates a wasm-gc call trampoline in pre-run. That
module is compiled with `-Wl,--import-memory` and no `-pthread` (`Makefile.pre.in`), so it
imports a non-shared memory, and instantiating it against the shared memory of a pthread
build is expected to throw outside the `try` block, at startup. Pyodide #237 reports this on
3.14, plus a second problem: the trampoline's `addFunction` pointer exists only in the main
thread's table. `build-wasm.sh` adds an early `return 0` to `getPyEMTrampolinePtr()` when
`wasmMemory.buffer` is a `SharedArrayBuffer`, which selects CPython's existing JS trampoline
(`wasmTable.get(func)` on the calling thread). The edit is asserted, so an upstream change
fails the build instead of skipping the patch. The dispatch input `trampoline_patch: skip`
builds without it, to observe the unpatched failure.

No patch is applied for `--disable-gil` itself; the investigation found none needed.

## Scripts

`.github/scripts/ft-wasm/`:

- `build-wasm.sh [VERSION]`: unpacks `Python-<VERSION>.tgz` (through
  `.github/scripts/ft-mobile/common.sh`), installs Emscripten into `$FT_WORK/emsdk`, applies
  the patch, runs the steps above, verifies, assembles and re-verifies the archive, and
  writes its sha256 to the job summary. Honours `FT_WORK`, `FT_OUT` (default under `.tmp/`),
  `FT_WASM_EMSDK_VERSION` and `FT_WASM_TRAMPOLINE_PATCH`.
- `smoke.py`: run under node by the script, see below.

The build is meant for CI (`AGENTS.md` rule 10). The workflow runs on `workflow_dispatch`
and on pushes to `develop` that change its own files.

## Verification built into the workflow

The script stops at the first failed check, in this order:

1. `emcc --version` reports the requested Emscripten version.
2. The build Python reports `Py_GIL_DISABLED == 1`.
3. `libffi.a` and `libmpdec.a` carry `+atomics` in their wasm `target_features`.
4. The host `pyconfig.h` has `#define Py_GIL_DISABLED 1`, the host Makefile has
   `-sUSE_PTHREADS` in `CONFIGURE_LDFLAGS_NODIST` and `ABIFLAGS` is `t`.
5. `make-host` links `python.wasm` and runs `python.sh --version` (a startup failure, for
   example the trampoline, fails here).
6. `python.wasm` has a shared memory with a declared maximum (read from its import or memory
   section; the page counts are logged).
7. `smoke.py` under `python.sh` in the build tree: `sys.platform == "emscripten"`,
   `Py_GIL_DISABLED == 1`, `sys._is_gil_enabled() is False`, then four `threading.Thread`s
   each compute a sum; all must join within 120 s with the right results and four distinct
   thread idents, and the GIL must still be off. It prints `FT-WASM-SMOKE-OK ...`, which the
   script greps for. The run has a 600 s timeout (exit 124 means a thread start may have
   deadlocked).
8. The interpreter's own `sysconfig.get_path("stdlib")` ends in `python3.15t`; the archive's
   stdlib directory is named from it.
9. The unpacked archive has `include/python3.15t/pyconfig.h` with `Py_GIL_DISABLED 1`,
   `lib/python3.15t/os.py` and `_sysconfigdata_*.py`, and `smoke.py` passes again with
   `PYTHONHOME` pointing at it.

The archive is uploaded as the workflow artifact `python-<version>t-wasm32-emscripten`. No
release is published or changed.

## Embedding probe (step 2 of #175)

Agent proposal toward #175 step 2, not a decision. It answers, on CI, whether the library's way
of using CPython (load `python.mjs` as a factory with `noInitialRun`, never call `main`, call
exports from the JS main thread) works against this pthreads build.

`build-wasm.sh` now appends `wasmExports,wasmMemory,wasmTable` to every
`-sEXPORTED_RUNTIME_METHODS` list in the host Makefile before `make-host` (the same edit as
`patch_makefile_reachability` in `python-multiplatform/scripts/wasm/build-cpython.sh`, as a
regex because the 3.15 Makefile text was not checked) and asserts the result. If the Makefile
has a `-sEXPORTED_FUNCTIONS=` list it also ensures `_malloc,_free,_Py_Version` are in it. A
missing list is only logged: whether 3.15 still exports C symbols through `-sMAIN_MODULE`
(as 3.14 does) is unverified, and the probe fails loudly if an export it needs is absent.

The workflow step `Embedding probe (no main)` runs after the archive upload, so a probe
failure or hang does not lose the archive:
`timeout 600 node .github/scripts/ft-wasm/embed-probe.mjs <unpacked archive>`. It prints one
`[x] PASS|FAIL` line per check and `FT-WASM-EMBED-PROBE-OK` only if all pass; on a timeout
(exit 124) the step reports the last check line printed.

- `a`: the factory loads with `noInitialRun`, `Module.wasmMemory.buffer` is a
  `SharedArrayBuffer` (page count printed).
- `b`: two hand-assembled modules import `intrinsics.memory` (the name Kotlin/Wasm uses).
  A1 imports a non-shared memory and must fail with `LinkError`; A2 imports a shared one with
  maximum 65536 pages and must instantiate, and `A2.load(&Py_Version)` must equal the value
  read through a view of `wasmMemory`. A2 passing keeps the post-link import patch (investigation
  options 2a/2b) open; A1 not failing would mean the blocker is not real.
- `c`: `Py_Initialize`, `PyEval_SaveThread`, then `PyGILState_Ensure` and `PyRun_SimpleString`
  (strings placed with the exported `malloc`) of a script that starts four threads which print
  and read `os.py`, joins them, asserts `sys._is_gil_enabled()` is False, and runs
  `gc.collect()` inside a worker thread. This exercises the main thread blocking in `join`
  while Workers need it to service proxied syscalls.

Node only. Node's main thread uses a real `Atomics.wait`; a browser's main thread busy-waits
and services proxied calls, so a pass here does not show the browser works, and a Node deadlock
may not occur in a browser. It does not involve Kotlin at all (no upcalls, no proxies, no
Kotlin-side memory import).

## Not verified

- The workflow has not run yet. Whether 3.15.0 configures, compiles and links with
  `--disable-gil --enable-wasm-pthreads` on Emscripten 4.0.19 is unknown. The only real
  evidence is upstream's free-threaded build **without** pthreads (CPython gh-158997, on
  `main` with Emscripten 6.0.10) and Pyodide's **GIL** build with pthreads (3.14, Emscripten
  5.0.3). No project is known to have combined the two.
- Whether `EMCC_CFLAGS=-pthread` is enough for libffi and mpdec, and whether the
  `-sUSE_BZIP2` port links into a shared-memory build, is a prediction.
- Whether the JS trampoline fallback is enough, and what it costs per call compared with the
  wasm-gc trampoline (`WasmCallTrampolineTest` covers only the current GIL build).
- Only node is exercised; no browser (which also needs cross-origin isolation for
  `SharedArrayBuffer`).
- The smoke test checks that four threads run and join with the GIL off. It is not a
  thread-safety test, does not run CPython's test suite, and does not measure parallel
  speed-up.
- mimalloc's WASI path on Emscripten has no thread-exit hook; the investigation predicts a
  slow per-thread leak. Not measured.

## Open: the Kotlin side (later steps of #175, not implemented)

- Kotlin/Wasm imports `intrinsics.memory` as non-shared (`min=0, max=none`); a shared
  memory cannot satisfy that import. The owner chose a post-link rewrite of that import
  (2026-11-21, SPEC T-7, [`wasm-design.md`](wasm-design.md)); it is implemented and applies
  once this build is the staged runtime (#175 step 2).
- Kotlin calls CPython on the JS thread that loaded the module (`noInitialRun`, no `main`),
  so `-sPROXY_TO_PTHREAD` does not apply there; a first `Thread.start()` from Kotlin relies on
  the pre-created worker pool.
- Upcalls from a non-main Python thread: the Kotlin trampolines are placed with `table.set`
  only in the main thread's table; each Worker has its own table, so every Worker needs the
  same entries (SPEC U-5, the pythonx-concurrent wasm dispatchers).
- Staging this archive as a wasmJs runtime (it lacks `wasmExports,wasmMemory` in
  `-sEXPORTED_RUNTIME_METHODS` and the `pyemscripten` platform tag that `build-cpython.sh`
  adds).

## Design: upcalls from Python threads (#175 step 2, agent proposal, not confirmed)

The owner put threads calling Kotlin on wasm in 1.0 (owner rule of 2026-11-21 22:05: no scope
reduction; pmp-binder report, "Correction" section). This is the proposed route; nothing below
has run yet.

**The problem.** A `PyCFunction`'s `ml_meth` is a table index. Today it is the index `Table.set`
gave Kotlin's `@WasmExport` entry in the **main thread's** table. Every pthread Worker
instantiates `python.wasm` with its own table, where that index holds nothing, so a Python thread
calling a Kotlin callable traps. Kotlin/Wasm itself runs only on the main thread.

**The route.**
1. A built-in module compiled into our CPython build (`Modules/Setup.local`: `_pm_upcall
   _pm_upcall.c`) defines `pm_upcall_dispatch(self, args)`. A static function has the **same**
   table index in every Worker, because the static element segment is instantiated identically
   in each one. `ml_meth` becomes that index instead of the Kotlin export's.
2. On the main thread, `pm_upcall_dispatch` calls the Kotlin entry through the table index Kotlin
   registered once at bootstrap (`pm_upcall_set_target(index)`), as today.
3. On any other thread, it detaches its thread state (`PyEval_SaveThread`), runs the same call on
   the main thread with `emscripten_sync_run_in_main_runtime_thread`, and re-attaches
   (`PyEval_RestoreThread`). Detaching matters on a free-threaded build: a stop-the-world (a GC)
   started while the main thread runs Kotlin would otherwise wait forever for the blocked thread.
   The Kotlin entry already takes its own `PyGILState_Ensure`/`Release`, so the main thread
   attaches its own thread state for the call.
4. **The main thread must be able to service the call.** Emscripten's main thread runs queued
   proxied calls while it blocks in a futex wait, which `pthread_join`, `thread.join()` and
   CPython's locks use. A main thread that is busy in JS (not blocked, not returned to the event
   loop) does not service it; such an upcall waits until it does.

**What it changes.** The CPython build (one C file plus `Setup.local`, in
`.github/scripts/ft-wasm/build-wasm.sh`), the wasm `UpcallEntry` (it binds the dispatcher's index
and registers the Kotlin one), and `cpython.mjs` (nothing, if the dispatcher's index is read through
an exported C function). No new dependency, workflow or kind of artefact.

**Tests (CI, wasm job).**
- A Python thread calls a Kotlin callable and gets its result.
- Four threads do so at once.
- The main thread blocks in `join` while the threads call Kotlin.
- A `gc.collect()` runs in one thread while another is inside an upcall.
- An exception raised in Kotlin reaches the Python thread.

**Implemented (2026-11-25, `feat/175-wasm-thread-upcalls`, not yet run).**
- `.github/scripts/ft-wasm/_pm_upcall.c`, built into the 3.15t runtime by `build-wasm.sh` through
  the build tree's `Modules/Setup.local`. It declares `Py_MOD_GIL_NOT_USED`, without which its
  import would re-enable the GIL. Exports `pm_upcall_dispatch`, `pm_upcall_set_target`,
  `pm_upcall_dispatch_index`; `build-wasm.sh` checks all three and that the module is built in.
- An exception raised on the main thread is set on the main thread's thread state, so the
  dispatcher takes it there (`PyErr_GetRaisedException`) and raises it again on the calling
  thread (`PyErr_SetRaisedException`).
- `cpython.mjs` `pmpUpcallDispatchIndex` / `pmpUpcallSetTarget` (optional: `-1` and a no-op on a
  runtime without the module) and `UpcallEntry.entryIndex`: `ml_meth` is the dispatcher when it
  exists, with the Kotlin entry as its target; otherwise the Kotlin entry, as before.
- The C route is tested without Kotlin by `smoke.py` in the ft-wasm build job: a C self-test target
  (`_pm_upcall.selftest`) reports the thread it ran on. Four threads make 50 calls each while the
  main thread waits in `join()` and a fifth thread runs `gc.collect()`; every call must run on the
  main thread, and `selftest("raise")` must raise the main thread's `ValueError` in the calling
  thread.

**Open.**
- Whether `emscripten_sync_run_in_main_runtime_thread` is serviced inside the futex wait in Node
  as well as in the browser. Its source says yes for both; the smoke test shows it for Node.
- The Kotlin-level tests (a Python thread calling a Kotlin callable through `UpcallEntry`) need the
  wasmJs suite to run on the 3.15t runtime, which waits on Decision 6.
- `ProxyTypeFactory`'s type slots (`tp_dealloc`, `tp_traverse`, the proxy methods) are also
  `pmpRegisterUpcall` indices, so a proxy object used from a Python thread still traps. They have
  other C signatures, so each needs its own dispatcher entry; next step. `tp_traverse` cannot take
  this route: a free-threaded collection calls it with the world stopped, on whichever thread
  collects, and the main thread cannot attach to run it until the world restarts, so routing it
  would deadlock. It has to run in the collecting thread without Kotlin (for example, a C
  traverse over the proxy's handle slot only).
- It depends on step 1 (#515) and on the 3.15t runtime (Decision 6), to have a runtime to test
  against.
