#!/bin/bash
# Free-threaded CPython 3.15.0 (--disable-gil) with pthreads for wasm32-emscripten, on Emscripten
# 5.0.3 with the pyemscripten_2026_0 unwinding ABI, staged as the runtime python-multiplatform's
# wasmJs target runs against (#175).
#
#   python-multiplatform/scripts/wasm/build-cpython.sh            build + zip + verify + smoke + stage + probe   (needs network the first time)
#   python-multiplatform/scripts/wasm/build-cpython.sh zip        rebuild only the stdlib zip of an existing build (no emsdk)
#   python-multiplatform/scripts/wasm/build-cpython.sh verify     check an existing build and its zip carry the ABI and thread claims
#   python-multiplatform/scripts/wasm/build-cpython.sh smoke      run smoke.py (threads, upcalls, proxy slots) under the build tree's python.sh
#   python-multiplatform/scripts/wasm/build-cpython.sh stage      copy python.wasm, python.mjs and the zip to the runtime dir
#   python-multiplatform/scripts/wasm/build-cpython.sh probe      run embed-probe.mjs against the staged runtime (Python without main, as the library runs it)
#   python-multiplatform/scripts/wasm/build-cpython.sh stock      build a STOCK interpreter (CPython's own driver defaults) as a negative control
#
# Which CPython, and why. Owner, 2026-10-04, on #175: "The wasm interpreter is also 3.15t, built by us
# (tools/wasm/build-cpython.sh) with pthreads; not Pyodide's GIL build ... Cost accepted: Pyodide's
# emscripten wheels do not load; compiled dependencies are built by pypackpack for wasm." The leader
# approved Decision 10 on 2026-11-26 (team report pmp-binder.md): (a) a1, this script builds 3.15t with
# pthreads; (b) b1, "one Emscripten 5.0.3 for PMP wasm and pypackpack #82"; (c) c1, the compiled-wheel
# test moves to a pypackpack-built cp315t wheel when #82 produces one. The thread build's flags and
# patches come from .github/scripts/ft-wasm/build-wasm.sh, whose CI runs proved them (build-ft-wasm
# 45704188042 on 5.0.3: smoke test in both layouts, embedding probe a to d); that script is deleted
# once wasm.yml is green on this one.
#
# Everything lands inside this repository, under the git-ignored `.caches/`:
#
#   .caches/emsdk                   emsdk, pinned to 5.0.3            (override: EMSDK)
#   .caches/wasm-build/cpython315t  CPython checkout + cross build    (override: CPYTHON_CHECKOUT)
#   .caches/wasm-runtime            the three files the library needs (override: WASM_RUNTIME_DIR)
#   .caches/wasm-build/cpython315-stock, .caches/wasm-runtime-stock   the `stock` control
#
# `.caches/wasm-runtime` is the default `wasmPythonDir` in python-multiplatform/build.gradle.kts, so
# after one run of this script `./gradlew :python-multiplatform:wasmJsNodeTest` runs the suite with
# no flags. It is also exactly the layout of an unpacked `python-multiplatform-wasm-runtime` zip.
#
# Why this is a build and not a download: no distributor ships a free-threaded pthreads python.wasm,
# and none with `wasmExports,wasmMemory` in -sEXPORTED_RUNTIME_METHODS, which is what the library's
# @WasmImport declarations bind to.
#
# What separates this from CPython's own driver defaults (`Platforms/emscripten build`):
#
#   1. unwinding ABI  -fwasm-exceptions -sSUPPORT_LONGJMP=wasm, at compile AND link: python.wasm must
#                     export the __cpp_exception and __c_longjmp tags pyemscripten_2026_0 side modules
#                     import. Without it: LinkError: Import "env" "__cpp_exception": tag import requires
#                     a WebAssembly.Tag
#   2. tag claim      PYEMSCRIPTEN_PLATFORM_VERSION, which CPython 3.15.0 does not define either. The
#                     wheel tag becomes cp315-cp315t-pyemscripten_2026_0_wasm32; the cp315t ABI tag is
#                     what keeps those wheels apart from the GIL build's (agent proposal for pypackpack
#                     #82, not confirmed).
#   3. threads        --disable-gil --enable-wasm-pthreads, -pthread at compile and link,
#                     -sPTHREAD_POOL_SIZE=8 (Thread.start() blocks until the thread runs, and a Worker
#                     cannot start while the JS thread that asked for it is blocked),
#                     -sMAXIMUM_MEMORY=2GB (a shared memory needs a maximum), libffi and mpdec rebuilt
#                     with -pthread (wasm-ld refuses non-atomic objects in a shared-memory link), and a
#                     free-threaded build Python.
#   4. trampoline     Python/emscripten_trampoline.c's wasm-gc trampoline imports a non-shared memory,
#                     so under a shared memory it falls back to the JS trampoline (patch_trampoline).
#   5. _pm_upcall     a built-in module (beside this script) that routes upcalls and proxy slots made on
#                     a pthread to the main runtime thread, where Kotlin runs; see its header.
#   6. JS reachability  wasmExports/wasmMemory/wasmTable/addFunction/removeFunction in
#                     -sEXPORTED_RUNTIME_METHODS. Not ABI-sensitive; needed so Kotlin can bind.
#   7. no ppoll       ac_cv_func_ppoll=no: 3.15's select.poll() prefers ppoll(), which Emscripten links
#                     as an ENOSYS stub, so poll() always raised and selectors fell back to select().
#   8. JSPI I/O       Python/emscripten_syscalls.c takes its JSPI fd_read/poll path only under the
#                     promising main(); in a pthread or an embedder it threw SuspendError on node 24.
#   9. NOT DONE       lzma, zstd and OpenSSL are still missing (as in the 3.14.2 build this replaces).
#
# TRAP: the driver hardcodes CFLAGS=-DPY_CALL_TRAMPOLINE -sUSE_BZIP2 in its configure argv and
# appends user args AFTER; autoconf takes the last assignment, so the full string must be repeated or
# -DPY_CALL_TRAMPOLINE is silently dropped.
#
# TRAP: every Makefile edit goes into Makefile AND Makefile.pre. Adding _pm_upcall to
# Modules/Setup.local makes make re-run makesetup, which regenerates Makefile from Makefile.pre and
# silently drops an edit made to Makefile alone (pmp17's embedding probe: Module.wasmMemory undefined).
#
# TRAP: the stdlib zip is not rebuilt by `make-host`, so `zip` always runs after the patches, and
# `verify` checks the zip, not only the build tree.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
HERE="$ROOT/python-multiplatform/scripts/wasm"
CACHES="$ROOT/.caches"

CPYTHON_TAG=v3.15.0
CPYTHON_COMMIT=d220409a13b77de7a6c30abe16ea90db01528edc
EMSCRIPTEN_VERSION=5.0.3
PLATFORM_VERSION=2026_0

EMSDK="${EMSDK:-$CACHES/emsdk}"
CHECKOUT="${CPYTHON_CHECKOUT:-$CACHES/wasm-build/cpython315t}"
RUNTIME_DIR="${WASM_RUNTIME_DIR:-$CACHES/wasm-runtime}"
BUILD="$CHECKOUT/cross-build/wasm32-emscripten/build/python"
PREFIX="$CHECKOUT/cross-build/wasm32-emscripten/prefix"
# The native build interpreter is `python` on Linux and `python.exe` on macOS (CPython's configure
# names it so on a case-insensitive volume, where `python` would collide with the `Python/` directory).
native_python() {
  local d="$CHECKOUT/cross-build/build"
  if [ -x "$d/python.exe" ]; then echo "$d/python.exe"; else echo "$d/python"; fi
}
ZIP_NAME=python3.15t.zip
ZIP="${STDLIB_ZIP:-$BUILD/$ZIP_NAME}"

ABI_FLAGS="-fwasm-exceptions -sSUPPORT_LONGJMP=wasm"
HOST_CFLAGS="-DPY_CALL_TRAMPOLINE -sUSE_BZIP2 $ABI_FLAGS -pthread"
HOST_LDFLAGS="$ABI_FLAGS -pthread -sPTHREAD_POOL_SIZE=8 -sMAXIMUM_MEMORY=2GB"

die() { echo "build-cpython: $*" >&2; exit 1; }

# --- toolchain --------------------------------------------------------------------------------------
ensure_emsdk() {
  if [ ! -x "$EMSDK/emsdk" ]; then
    mkdir -p "$(dirname "$EMSDK")"
    git clone https://github.com/emscripten-core/emsdk.git "$EMSDK"
  fi
  if [ ! -f "$EMSDK/upstream/emscripten/emscripten-version.txt" ] ||
     ! grep -q "\"$EMSCRIPTEN_VERSION\"" "$EMSDK/upstream/emscripten/emscripten-version.txt"; then
    # This emsdk is private to the repository, so installing a version here does not replace
    # anyone else's -- unlike ~/emsdk, where `install` swaps `upstream/` in place for every user.
    "$EMSDK/emsdk" install "$EMSCRIPTEN_VERSION"
    "$EMSDK/emsdk" activate "$EMSCRIPTEN_VERSION"
  fi
  # shellcheck disable=SC1091
  source "$EMSDK/emsdk_env.sh" > /dev/null 2>&1
  emcc --version | head -1
  emcc --version | head -1 | grep -q " $EMSCRIPTEN_VERSION " ||
    die "emcc is not $EMSCRIPTEN_VERSION; Decision 10 (b) pins exactly that version"
}

ensure_checkout() {
  if [ ! -d "$CHECKOUT/.git" ]; then
    mkdir -p "$(dirname "$CHECKOUT")"
    git clone --depth 1 --branch "$CPYTHON_TAG" https://github.com/python/cpython.git "$CHECKOUT"
  fi
  local head
  head="$(git -C "$CHECKOUT" rev-parse HEAD)"
  [ "$head" = "$CPYTHON_COMMIT" ] ||
    die "$CHECKOUT is at $head, expected $CPYTHON_TAG ($CPYTHON_COMMIT)"
  [ -d "$CHECKOUT/Platforms/emscripten" ] || die "no Platforms/emscripten in $CHECKOUT"
}

# --- 2a. PYEMSCRIPTEN_PLATFORM_VERSION has to survive sysconfig's Makefile parser --------------------
# sysconfig coerces any Makefile value that parses as an integer, and Python accepts underscores in
# integer literals -- so int('2026_0') == 20260 and packaging emits pyemscripten_20260_wasm32.
# _ALWAYS_STR is the existing opt-out (it already holds the two Apple deployment targets).
patch_sysconfig() {
  python3 - "$CHECKOUT/Lib/sysconfig/__init__.py" <<'PATCH'
import sys
from pathlib import Path
p = Path(sys.argv[1]); t = p.read_text()
if "PYEMSCRIPTEN_PLATFORM_VERSION" not in t:
    old = "_ALWAYS_STR = {\n    'IPHONEOS_DEPLOYMENT_TARGET',\n    'MACOSX_DEPLOYMENT_TARGET',\n}"
    new = ("_ALWAYS_STR = {\n    'IPHONEOS_DEPLOYMENT_TARGET',\n    'MACOSX_DEPLOYMENT_TARGET',\n"
           "    # PEP 783: int('2026_0') == 20260 without this.\n"
           "    'PYEMSCRIPTEN_PLATFORM_VERSION',\n}")
    assert old in t, "sysconfig._ALWAYS_STR changed shape; re-check the patch"
    p.write_text(t.replace(old, new))
    print("patched Lib/sysconfig/__init__.py")
PATCH
}

# --- 4. call trampoline under shared memory -------------------------------------------------------------
# Python/emscripten_trampoline.c instantiates a wasm-gc trampoline module in pre-run. It is compiled
# with -Wl,--import-memory and no -pthread, so it imports a NON-shared env.memory; giving it the shared
# wasmMemory of a pthread build is a link error, and the `new WebAssembly.Instance` call is outside the
# try block, so start-up throws. Its addFunction pointer would also exist only in the main thread's
# table. When the memory is shared, return 0, which selects the existing JS trampoline. Asserted, so an
# upstream change fails the build instead of silently skipping the patch.
patch_trampoline() {
  python3 - "$CHECKOUT/Python/emscripten_trampoline.c" <<'PATCH'
import sys
from pathlib import Path
p = Path(sys.argv[1]); t = p.read_text()
marker = "pmp-175: shared memory"
if marker not in t:
    old = "function getPyEMTrampolinePtr() {\n"
    new = (old +
           "    // " + marker + ": the wasm-gc trampoline imports non-shared memory and its\n"
           "    // addFunction pointer is valid only on this thread; use the JS trampoline.\n"
           "    if (typeof SharedArrayBuffer !== \"undefined\" && wasmMemory.buffer instanceof SharedArrayBuffer) {\n"
           "        return 0;\n"
           "    }\n")
    assert t.count(old) == 1, "getPyEMTrampolinePtr changed shape; re-check the patch"
    p.write_text(t.replace(old, new))
    print("patched Python/emscripten_trampoline.c (shared memory -> JS trampoline)")
PATCH
}

# CPython's Python/emscripten_syscalls.c routes fd_read and poll through JSPI (a promise and
# WebAssembly.Suspending) whenever the engine has WebAssembly.promising, which node 24 has by default.
# Suspending works only below the promising wrapper CPython puts around main(). A pthread is not below
# it, and neither is an embedder that never calls main(), so there the first poll() threw
# "SuspendError: trying to suspend without WebAssembly.promising" (#542 CI run 45742320989, node
# 24.22.3: smoke.py's select.poll() in its pthread main; node 22 has no JSPI and passed). The patch
# marks the promising main in this JS context and takes the async path only under it; everywhere else
# fd_read and poll go to Emscripten's synchronous implementations.
patch_syscalls() {
  python3 - "$CHECKOUT/Python/emscripten_syscalls.c" <<'PATCH'
import sys
from pathlib import Path
p = Path(sys.argv[1]); t = p.read_text()
marker = "pmp-542: JSPI only under the promising main"
if marker not in t:
    guard = "    if (!WebAssembly.promising) {\n        return null;\n    }\n"
    assert t.count(guard) == 2, "the fd_read/poll JSPI guards changed shape; re-check the patch"
    t = t.replace(guard,
                  "    // " + marker + " (pthreads and embedders are not below it).\n"
                  "    if (!WebAssembly.promising || !Module.__pmPromisingMain) {\n"
                  "        return null;\n    }\n")
    old = "            orig.sym = (...args) => {\n"
    assert t.count(old) == 1, "the promising main wrapper changed shape; re-check the patch"
    t = t.replace(old, old + "                Module.__pmPromisingMain = true; // " + marker + "\n")
    p.write_text(t)
    print("patched Python/emscripten_syscalls.c (JSPI fd_read/poll only under the promising main)")
PATCH
}

# --- the native build Python, libffi and mpdec ---------------------------------------------------------
# The build Python is free-threaded too. libffi and mpdec get -pthread through EMCC_CFLAGS (emcc appends
# it to every invocation), checked by the archives' target_features section ("+", 7, "atomics").
ensure_prerequisites() {
  if [ ! -x "$(native_python)" ]; then
    ( cd "$CHECKOUT" &&
      python3 Platforms/emscripten configure-build-python -- --disable-gil &&
      python3 Platforms/emscripten make-build-python )
  fi
  "$(native_python)" -c 'import sysconfig; assert sysconfig.get_config_var("Py_GIL_DISABLED") == 1, "the build Python is not free-threaded"'
  if [ ! -f "$PREFIX/lib/libffi.a" ]; then
    ( cd "$CHECKOUT" && EMCC_CFLAGS="-pthread" python3 Platforms/emscripten make-libffi )
  fi
  if [ ! -f "$PREFIX/lib/libmpdec.a" ]; then
    ( cd "$CHECKOUT" && EMCC_CFLAGS="-pthread" python3 Platforms/emscripten make-mpdec )
  fi
  local lib
  for lib in "$PREFIX/lib/libffi.a" "$PREFIX/lib/libmpdec.a"; do
    python3 -c 'import sys; sys.exit(0 if b"+\x07atomics" in open(sys.argv[1], "rb").read() else 1)' "$lib" ||
      die "$lib was not compiled with atomics (-pthread); delete it and rebuild"
  done
}

# --- 2b + 6. Makefile edits, before the first link ----------------------------------------------------
# Both go into Makefile and Makefile.pre (see the TRAP above). Split in two because `stock` takes only
# the second: the platform claim is ABI, the runtime methods are not.
patch_makefile() {
  patch_makefile_platform
  patch_makefile_reachability
}

patch_makefile_platform() {
  python3 - "$PLATFORM_VERSION" "$BUILD/Makefile" "$BUILD/Makefile.pre" <<'PATCH'
import sys
from pathlib import Path
version = sys.argv[1]
for arg in sys.argv[2:]:
    p = Path(arg); t = p.read_text()
    if "\nPYEMSCRIPTEN_PLATFORM_VERSION" not in t:
        i = t.index("CONFIGURE_CFLAGS=\t")
        t = t[:i] + f"PYEMSCRIPTEN_PLATFORM_VERSION=\t{version}\n" + t[i:]
        print(f"{p.name}: PYEMSCRIPTEN_PLATFORM_VERSION={version}")
    p.write_text(t)
PATCH
}

patch_makefile_reachability() {
  python3 - "$BUILD/Makefile" "$BUILD/Makefile.pre" <<'PATCH'
import re
import sys
from pathlib import Path
RUNTIME = ("wasmExports", "wasmMemory", "wasmTable", "addFunction", "removeFunction")
def add(m):
    items = m.group(2).split(",")
    return m.group(1) + ",".join(items + [x for x in RUNTIME if x not in items])
for arg in sys.argv[1:]:
    p = Path(arg); t = p.read_text()
    t, n = re.subn(r"(-sEXPORTED_RUNTIME_METHODS=)(\S+)", add, t)
    assert n >= 1, f"no -sEXPORTED_RUNTIME_METHODS= in {p.name}; the link line changed shape, re-check the patch"
    p.write_text(t)
    print(f"{p.name}: runtime methods exposed for @WasmImport in {n} list(s)")
PATCH
}

# --- 5. the _pm_upcall built-in module ------------------------------------------------------------------
# Built in statically through the build tree's Modules/Setup.local (configure leaves an empty one;
# makesetup finds the source under $(srcdir)/Modules).
add_pm_upcall() {
  cp "$HERE/_pm_upcall.c" "$CHECKOUT/Modules/_pm_upcall.c"
  grep -q '^_pm_upcall ' "$BUILD/Modules/Setup.local" 2>/dev/null ||
    printf '*static*\n_pm_upcall _pm_upcall.c\n' >> "$BUILD/Modules/Setup.local"
  # make re-runs makesetup only when Setup.local is NEWER than Makefile, and the Makefile patches above
  # are written within the same second on a fast machine, so the module was silently left out (seen
  # locally: verify found no pm_* exports). Date the Makefile back so the regeneration always happens;
  # it rebuilds Makefile from the patched Makefile.pre.
  python3 -c 'import os, sys, time; t = time.time() - 10; os.utime(sys.argv[1], (t, t))' "$BUILD/Makefile"
}

do_build() {
  ensure_emsdk
  ensure_checkout
  patch_trampoline
  patch_syscalls
  ensure_prerequisites
  patch_sysconfig
  ( cd "$CHECKOUT" &&
    python3 Platforms/emscripten configure-host --clean --host-runner node -- \
      "CFLAGS=$HOST_CFLAGS" \
      "LDFLAGS=$HOST_LDFLAGS" \
      ac_cv_func_ppoll=no \
      --disable-gil --enable-wasm-pthreads )
  # ac_cv_func_ppoll=no: 3.15's select.poll() calls ppoll() whenever configure finds it, and
  # Emscripten links ppoll but stubs it (system/lib/libc/emscripten_syscall_stubs.c, UNIMPLEMENTED),
  # so every poll().poll() raised OSError ENOSYS and selectors fell back to SelectSelector (seen
  # locally: WasmSelectorsImportTest). Without ppoll it calls poll(), which Emscripten implements.
  # CPython's config.site-wasm32-emscripten does not list it.
  ! grep -Eq '^#define HAVE_PPOLL 1' "$BUILD/pyconfig.h" ||
    die "configure still found ppoll(); Emscripten's is a stub that returns ENOSYS"
  grep -Eq '^CONFIGURE_LDFLAGS_NODIST=.*-sUSE_PTHREADS' "$BUILD/Makefile" ||
    die "configure did not enable wasm pthreads (no -sUSE_PTHREADS in LDFLAGS_NODIST)"
  grep -Eq '^ABIFLAGS=[[:space:]]*t$' "$BUILD/Makefile" || die "host Makefile ABIFLAGS is not t"
  patch_makefile
  add_pm_upcall
  ( cd "$CHECKOUT" && python3 Platforms/emscripten make-host )
}

# --- the stdlib zip ---------------------------------------------------------------------------------
# Runs CPython's own wasm_assets.py with the *native* build interpreter, the way the generated
# Makefile's $(ZIP_STDLIB) rule does -- but with paths derived here rather than read from the
# Makefile, which bakes in the absolute location the build was configured at and breaks if the tree
# is moved. Bytecode goes to a private pycache prefix so nothing is written into the checkout's Lib.
mk_var() { sed -n -E "s/^$1=[[:space:]]*(.*)$/\1/p" "$BUILD/Makefile" | head -1; }

do_zip() {
  [ -x "$(native_python)" ] || die "no native build interpreter at $(native_python); run a full build first"
  [ -f "$BUILD/pybuilddir.txt" ] || die "no $BUILD/pybuilddir.txt; run a full build first"
  local host_platform machdep multiarch abiflags builddir work
  host_platform="$(mk_var _PYTHON_HOST_PLATFORM)"
  machdep="$(mk_var MACHDEP)"; multiarch="$(mk_var MULTIARCH)"; abiflags="$(mk_var ABIFLAGS)"
  builddir="$(cat "$BUILD/pybuilddir.txt")"
  work="$(dirname "$ZIP")/.wasm-assets-work"
  rm -rf "$work"; mkdir -p "$work/pycache"
  # wasm_assets.py reads the build's Makefile from --buildroot and creates an empty lib-dynload
  # marker under it; give it a scratch root holding a copy so nothing is written into the build.
  cp "$BUILD/Makefile" "$work/Makefile"
  # The native interpreter is PGO-instrumented and drops default.profraw into its cwd on exit.
  ( cd "$BUILD" &&
    LLVM_PROFILE_FILE="$work/default.profraw" \
    _PYTHON_HOSTRUNNER=node \
    _PYTHON_PROJECT_BASE="$BUILD" \
    _PYTHON_HOST_PLATFORM="$host_platform" \
    _PYTHON_SYSCONFIGDATA_NAME="_sysconfigdata_${abiflags}_${machdep}_${multiarch}" \
    _PYTHON_SYSCONFIGDATA_PATH="$BUILD/$builddir" \
    PYTHONPATH="$CHECKOUT/Lib" \
    PYTHONPYCACHEPREFIX="$work/pycache" \
    "$(native_python)" "$CHECKOUT/Platforms/emscripten/wasm_assets.py" \
      --buildroot "$work" --prefix / -o "$ZIP" )
  rm -rf "$work"
}

# --- verify, rather than assume ---------------------------------------------------------------------
# The names of the wasm tags (export kind 4) a module exports, comma-separated.
exported_tags() {
  node -e '
const b = require("fs").readFileSync(process.argv[1]);
const leb = (b, i) => { let r = 0, s = 0; for (;;) { const x = b[i++]; r |= (x & 0x7f) << s; s += 7; if (!(x & 0x80)) return [r, i]; } };
let i = 8; const tags = [];
while (i < b.length) {
  const sid = b[i++]; let size; [size, i] = leb(b, i); const end = i + size;
  if (sid === 7) { let j = i, n; [n, j] = leb(b, j);
    for (let k = 0; k < n; k++) { let l; [l, j] = leb(b, j); const nm = b.slice(j, j + l).toString(); j += l;
      const kind = b[j++]; [, j] = leb(b, j); if (kind === 4) tags.push(nm); } }
  i = end;
}
console.log(tags.join(","));
' "$1"
}

# Prints the memory python.wasm uses and fails unless it is shared with a declared maximum.
check_shared_memory() {
  node - "$1" <<'NODE'
const b = require("fs").readFileSync(process.argv[2]);
const leb = (i) => { let r = 0, s = 0; for (;;) { const x = b[i++]; r += (x & 0x7f) * 2 ** s; s += 7; if (!(x & 0x80)) return [r, i]; } };
const str = (i) => { let n; [n, i] = leb(i); return [b.slice(i, i + n).toString(), i + n]; };
const limits = (i) => { const flags = b[i++]; let min, max = null; [min, i] = leb(i); if (flags & 1) [max, i] = leb(i); return [{ flags, min, max }, i]; };
let i = 8, mem = null;
while (i < b.length && !mem) {
  const id = b[i++]; let size; [size, i] = leb(i); const end = i + size;
  if (id === 2) { let n, j; [n, j] = leb(i);
    for (let k = 0; k < n && !mem; k++) { let m, f; [m, j] = str(j); [f, j] = str(j); const kind = b[j++];
      if (kind === 0) { [, j] = leb(j); }
      else if (kind === 1) { j++; [, j] = limits(j); }
      else if (kind === 2) { let l; [l, j] = limits(j); mem = { where: `import ${m}.${f}`, ...l }; }
      else if (kind === 3) { j += 2; }
      else if (kind === 4) { j++; [, j] = leb(j); }
      else throw new Error(`unknown import kind ${kind}`); } }
  if (id === 5) { let j; [, j] = leb(i); const [l] = limits(j); mem = { where: "defined", ...l }; }
  i = end;
}
if (!mem) { console.error("no memory found"); process.exit(1); }
const shared = (mem.flags & 2) !== 0;
console.log(`python.wasm memory: ${mem.where} shared=${shared} min=${mem.min} max=${mem.max} pages`);
process.exit(shared && mem.max !== null ? 0 : 1);
NODE
}

do_verify() {
  [ -f "$BUILD/python.wasm" ] || die "no $BUILD/python.wasm"
  [ -f "$ZIP" ] || die "no $ZIP"
  local tags name
  tags="$(exported_tags "$BUILD/python.wasm")"
  echo "exported wasm tags: ${tags:-NONE}"
  for t in __cpp_exception __c_longjmp; do
    case ",$tags," in *",$t,"*) ;; *) die "missing tag $t: unwinding ABI NOT matched" ;; esac
  done
  check_shared_memory "$BUILD/python.wasm" || die "python.wasm has no shared memory with a maximum"
  grep -q '^#define Py_GIL_DISABLED 1' "$BUILD/pyconfig.h" || die "pyconfig.h does not define Py_GIL_DISABLED"
  # The link must really export the runtime names (Emscripten writes Module["wasmMemory"]=wasmMemory
  # only for an exported name); an edit lost to a Makefile regeneration fails here.
  for name in wasmExports wasmMemory wasmTable addFunction removeFunction; do
    grep -q "Module\[\"$name\"\]=" "$BUILD/python.mjs" ||
      die "python.mjs does not export Module.$name (the EXPORTED_RUNTIME_METHODS patch was lost)"
  done
  node - "$BUILD/python.wasm" <<'NODE' || die "python.wasm does not export the _pm_upcall functions"
const m = new WebAssembly.Module(require("fs").readFileSync(process.argv[2]));
const names = new Set(WebAssembly.Module.exports(m).map((e) => e.name));
const want = ["pm_upcall_dispatch", "pm_upcall_set_target", "pm_upcall_dispatch_index",
              "pm_tp_traverse", "pm_tp_clear", "pm_tp_dealloc", "pm_proxy_slot_index", "pm_proxy_set_slot_target"];
const missing = want.filter((n) => !names.has(n));
console.log(`python.wasm _pm_upcall exports: ${want.filter((n) => names.has(n)).join(",")}${missing.length ? " MISSING " + missing.join(",") : ""}`);
process.exit(missing.length ? 1 : 0);
NODE
  python3 - "$ZIP" "$PLATFORM_VERSION" <<'CHECK'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1]); version = sys.argv[2]
names = z.namelist()
data = [n for n in names if n.startswith("_sysconfigdata_")]
assert data, "the stdlib zip has no _sysconfigdata module"
blob = z.read(data[0])
assert b"PYEMSCRIPTEN_PLATFORM_VERSION" in blob and version.encode() in blob, \
    f"{data[0]} in the zip does not carry PYEMSCRIPTEN_PLATFORM_VERSION={version}: the zip predates the Makefile patch"
assert b"Py_GIL_DISABLED" in blob, f"{data[0]} in the zip has no Py_GIL_DISABLED"
assert b"PYEMSCRIPTEN_PLATFORM_VERSION" in z.read("sysconfig/__init__.pyc"), \
    "sysconfig in the zip lacks the _ALWAYS_STR patch: the tag would read pyemscripten_20260"
print(f"stdlib zip: {data[0]} claims {version}; sysconfig keeps it a str")
CHECK
}

# GIL off at run time, four real threads, upcalls and proxy slots from Python threads, under the build
# tree's python.sh (Python's main thread is a pthread there, -sPROXY_TO_PTHREAD).
do_smoke() {
  [ -x "$BUILD/python.sh" ] || die "no $BUILD/python.sh; run a full build first"
  local out
  out="$(dirname "$ZIP")/smoke.log"
  "$BUILD/python.sh" "$HERE/smoke.py" > "$out" 2>&1 || { cat "$out"; die "smoke.py failed"; }
  cat "$out"
  grep -q '^FT-WASM-SMOKE-OK ' "$out" || die "smoke.py did not print FT-WASM-SMOKE-OK"
}

# Python without main, the way the library runs it, against the staged runtime: a shared memory a
# Kotlin module can import, Py_Initialize from the JS main thread, threads, and smoke.py in that layout.
# The probe runs Python with PYTHONHOME at its root and its threads read lib/python3.15t/os.py as a
# file, so the root gets the source standard library (without test/) and the build's sysconfigdata.
do_probe() {
  [ -f "$RUNTIME_DIR/python.mjs" ] || die "no staged runtime in $RUNTIME_DIR; run stage first"
  local root lib
  root="$(mktemp -d "$(dirname "$ZIP")/probe.XXXXXX")"
  lib="$root/lib/python3.15t"
  cp "$RUNTIME_DIR/python.mjs" "$RUNTIME_DIR/python.wasm" "$root/"
  mkdir -p "$lib/lib-dynload"
  tar -C "$CHECKOUT/Lib" --exclude='./test' --exclude='__pycache__' -cf - . | tar -C "$lib" -xf -
  cp "$BUILD/$(cat "$BUILD/pybuilddir.txt")"/_sysconfigdata_*.py "$lib/"
  node "$HERE/embed-probe.mjs" "$root" > "$root.log" 2>&1 || true
  cat "$root.log"
  grep -q '^FT-WASM-EMBED-PROBE-OK$' "$root.log" || die "embedding probe failed (log: $root.log)"
  rm -rf "$root" "$root.log"
}

do_stage() {
  mkdir -p "$RUNTIME_DIR"
  # A runtime directory left by the 3.14.2 build holds python3.14.zip; two zips would leave the
  # staging to pick one by directory order.
  rm -f "$RUNTIME_DIR"/python*.zip
  cp "$BUILD/python.wasm" "$BUILD/python.mjs" "$RUNTIME_DIR/"
  cp "$ZIP" "$RUNTIME_DIR/$ZIP_NAME"
  echo "staged into $RUNTIME_DIR"
}

# --- stock: the negative control ---------------------------------------------------------------------
# CPython 3.15.0 built by its own driver's defaults (GIL, no pthreads), with exactly one addition: the
# runtime methods the library binds through (patch_makefile_reachability). No unwinding ABI, no
# PYEMSCRIPTEN_PLATFORM_VERSION, no sysconfig patch. Point the suite at it and the test that exists for
# the ABI must fail:
#
#   ./gradlew :python-multiplatform:wasmJsNodeTest -PwasmPythonDir=.caches/wasm-runtime-stock \
#       --tests '*WasmInterpreterAbiTest*'
#
#   WasmInterpreterAbiTest   no __cpp_exception tag; PYEMSCRIPTEN_PLATFORM_VERSION is None
#
# A separate checkout, not a second build directory: configure-host --clean wipes the one build
# directory a checkout has, and the sysconfig patch lives in the checkout's Lib/.
do_stock() {
  CHECKOUT="${STOCK_CPYTHON_CHECKOUT:-$CACHES/wasm-build/cpython315-stock}"
  RUNTIME_DIR="${STOCK_WASM_RUNTIME_DIR:-$CACHES/wasm-runtime-stock}"
  BUILD="$CHECKOUT/cross-build/wasm32-emscripten/build/python"
  ZIP_NAME=python3.15.zip
  ZIP="$BUILD/$ZIP_NAME"
  ensure_emsdk
  ensure_checkout
  if grep -q PYEMSCRIPTEN_PLATFORM_VERSION "$CHECKOUT/Lib/sysconfig/__init__.py"; then
    die "$CHECKOUT carries the ABI build's sysconfig patch; the stock control needs a clean checkout"
  fi
  if [ ! -x "$(native_python)" ]; then
    ( cd "$CHECKOUT" && python3 Platforms/emscripten configure-build-python &&
      python3 Platforms/emscripten make-build-python )
  fi
  ( cd "$CHECKOUT" && python3 Platforms/emscripten make-libffi && python3 Platforms/emscripten make-mpdec )
  ( cd "$CHECKOUT" && python3 Platforms/emscripten configure-host --clean --host-runner node )
  patch_makefile_reachability
  ( cd "$CHECKOUT" && python3 Platforms/emscripten make-host )
  do_zip
  # The control is only a control if it really lacks what the ABI build verifies.
  local tags
  tags="$(exported_tags "$BUILD/python.wasm")"
  case ",$tags," in
    *",__cpp_exception,"*) die "the stock python.wasm exports __cpp_exception -- it is not a negative control" ;;
  esac
  echo "stock python.wasm exports no exception tags (${tags:-NONE}), as a control must"
  do_stage
}

case "${1:-all}" in
  all)    do_build; do_zip; do_verify; do_smoke; do_stage; do_probe ;;
  build)  do_build ;;
  zip)    do_zip ;;
  verify) do_verify ;;
  smoke)  do_smoke ;;
  stage)  do_stage ;;
  probe)  do_probe ;;
  stock)  do_stock ;;
  *) die "unknown command '$1' (all | build | zip | verify | smoke | stage | probe | stock)" ;;
esac
