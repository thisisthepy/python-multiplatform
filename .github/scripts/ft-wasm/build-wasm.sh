#!/usr/bin/env bash
# Build free-threaded CPython (--disable-gil) with pthreads for wasm32-emscripten,
# with CPython's own driver (Platforms/emscripten). Step one of #175: a build
# experiment. Usage: build-wasm.sh [VERSION]
#
# Environment:
#   FT_WORK                  scratch directory (default .tmp/ft-wasm)
#   FT_OUT                   receives the archive the workflow uploads (default .tmp/ft-wasm-out)
#   FT_WASM_EMSDK_VERSION    Emscripten to install; empty = the version CPython itself pins
#                            (Platforms/emscripten/config.toml, 4.0.19 in 3.15.0)
#   FT_WASM_TRAMPOLINE_PATCH apply (default) | skip; see patch_trampoline below
#
# Needs: a C toolchain for the native build Python, git, curl, node on PATH
# (CPython 3.15.0 pins node 24 in config.toml), python3 >= 3.11 to run the driver.
#
# This is a separate script, not a mode of python-multiplatform/scripts/wasm/build-cpython.sh:
# that script is pinned to CPython 3.14.2 / Emscripten 5.0.3 / pyemscripten_2026_0, clones a
# pinned git commit, writes into .caches/ and stages the runtime the wasmJs suite runs against.
# This job builds a different CPython (3.15.0 tarball) with a different Emscripten and must not
# touch that runtime. The flags below are the #175 investigation's proposal, not a decision.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"

export FT_WORK="${FT_WORK:-$PWD/.tmp/ft-wasm}"
export FT_OUT="${FT_OUT:-$PWD/.tmp/ft-wasm-out}"
# fetch_source, require_gil_disabled, record_sha256, py_minor
# shellcheck source-path=SCRIPTDIR source=../ft-mobile/common.sh
source "$here/../ft-mobile/common.sh"
log() { echo "[ft-wasm] $*" >&2; }
die() { echo "[ft-wasm] ERROR: $*" >&2; exit 1; }

PY_VERSION="${1:-3.15.0}"
TRAMPOLINE_PATCH="${FT_WASM_TRAMPOLINE_PATCH:-apply}"
minor="$(py_minor "$PY_VERSION")"
HOST=wasm32-emscripten

# Compile and link flags (agent proposal from the #175 investigation, not confirmed).
# The driver hardcodes CFLAGS=-DPY_CALL_TRAMPOLINE -sUSE_BZIP2 and appends user args after
# it; autoconf keeps the last assignment, so the full string is repeated here.
#   -fwasm-exceptions -sSUPPORT_LONGJMP=wasm  same unwinding ABI as build-cpython.sh
#   -pthread                                  atomics + shared memory (configure also adds it
#                                             to CFLAGS_NODIST for --enable-wasm-pthreads)
#   -sPTHREAD_POOL_SIZE=8                     pre-created Workers: Thread.start() blocks until
#                                             the thread runs, and a Worker cannot start while
#                                             the JS thread that asked for it is blocked
#   -sMAXIMUM_MEMORY=2GB                      shared memory needs a declared maximum
ABI_FLAGS="-fwasm-exceptions -sSUPPORT_LONGJMP=wasm"
HOST_CFLAGS="-DPY_CALL_TRAMPOLINE -sUSE_BZIP2 $ABI_FLAGS -pthread"
HOST_LDFLAGS="$ABI_FLAGS -pthread -sPTHREAD_POOL_SIZE=8 -sMAXIMUM_MEMORY=2GB"

src="$(fetch_source "$PY_VERSION")"
cd "$src"
[ -d Platforms/emscripten ] || die "no Platforms/emscripten in CPython $PY_VERSION (3.15+ expected)"

# ---- Emscripten -----------------------------------------------------------
# One emsdk under FT_WORK. The driver is then run without --emsdk-cache, so it uses the
# activated environment ("Build will use EMSDK from current environment").
pinned="$(sed -n 's/^emscripten-version = "\(.*\)"$/\1/p' Platforms/emscripten/config.toml)"
[ -n "$pinned" ] || die "no emscripten-version in Platforms/emscripten/config.toml"
EMSDK_VERSION="${FT_WASM_EMSDK_VERSION:-$pinned}"
log "Emscripten pinned by CPython $PY_VERSION: $pinned; using: $EMSDK_VERSION"
emsdk="$FT_WORK/emsdk"
if [ ! -x "$emsdk/emsdk" ]; then
    git clone --depth 1 https://github.com/emscripten-core/emsdk.git "$emsdk"
fi
"$emsdk/emsdk" install "$EMSDK_VERSION"
"$emsdk/emsdk" activate "$EMSDK_VERSION"
# shellcheck disable=SC1091
EMSDK_QUIET=1 source "$emsdk/emsdk_env.sh"
emcc --version > "$FT_WORK/emcc-version.txt"
emcc_line="$(head -n 1 "$FT_WORK/emcc-version.txt")"
log "$emcc_line"
case "$emcc_line" in *" $EMSDK_VERSION "*) ;; *) die "emcc is not $EMSDK_VERSION" ;; esac
node --version

# ---- patch: call trampoline under shared memory ---------------------------
# Python/emscripten_trampoline.c instantiates a wasm-gc trampoline module in pre-run. That
# module is compiled with -Wl,--import-memory and no -pthread (Makefile.pre.in), so it imports
# a NON-shared env.memory; giving it the shared wasmMemory of a pthread build is a link error,
# and the `new WebAssembly.Instance` call is outside the try block, so startup throws.
# Pyodide #237 also reports that the addFunction'ed pointer exists only in the main thread's
# table ("table index is out of bounds" on the first call from a spawned thread).
# Minimal fix: when memory is shared, return 0, which selects the existing JS trampoline
# (_PyEM_TrampolineCall_JS, wasmTable.get(func) on the calling thread). The edit is asserted,
# so an upstream change fails the build instead of silently skipping the patch.
# FT_WASM_TRAMPOLINE_PATCH=skip leaves the source alone, to observe the unpatched failure.
patch_trampoline() {
    python3 - Python/emscripten_trampoline.c <<'PATCH'
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
case "$TRAMPOLINE_PATCH" in
    apply) patch_trampoline ;;
    skip)  log "trampoline patch skipped (FT_WASM_TRAMPOLINE_PATCH=skip)" ;;
    *)     die "FT_WASM_TRAMPOLINE_PATCH must be apply or skip, got '$TRAMPOLINE_PATCH'" ;;
esac

# ---- native build Python (free-threaded too, as in ft-mobile) -------------
python3 --version
python3 Platforms/emscripten configure-build-python -- --disable-gil
python3 Platforms/emscripten make-build-python
python3 Platforms/emscripten pythoninfo-build
cross-build/build/python -c 'import sys, sysconfig; assert sysconfig.get_config_var("Py_GIL_DISABLED") == 1; print("build python free-threaded:", sys.version)'

# ---- libffi and mpdec with -pthread ---------------------------------------
# The driver builds them with CFLAGS="-O2 -fPIC -DWASM_BIGINT" (make_libffi.sh) and
# CFLAGS=-fPIC (mpdec). emcc appends EMCC_CFLAGS to every invocation, so -pthread reaches
# both without editing the driver. Checked below by the objects' target_features section.
EMCC_CFLAGS="-pthread" python3 Platforms/emscripten make-libffi
EMCC_CFLAGS="-pthread" python3 Platforms/emscripten make-mpdec

prefix="cross-build/$HOST/prefix"
for lib in "$prefix/lib/libffi.a" "$prefix/lib/libmpdec.a"; do
    [ -f "$lib" ] || die "no $lib"
    # wasm target_features entries are <prefix byte><LEB length><name>: "+", 7, "atomics".
    python3 - "$lib" <<'CHECK' || die "$lib was not compiled with atomics (-pthread)"
import sys
data = open(sys.argv[1], "rb").read()
sys.exit(0 if b"+\x07atomics" in data else 1)
CHECK
    log "ok: $lib has +atomics"
done

# ---- host configure -------------------------------------------------------
python3 Platforms/emscripten configure-host --clean --host-runner node -- \
    "CFLAGS=$HOST_CFLAGS" \
    "LDFLAGS=$HOST_LDFLAGS" \
    --disable-gil --enable-wasm-pthreads

hostdir="cross-build/$HOST/build/python"
require_gil_disabled "$hostdir/pyconfig.h"
# configure adds -sUSE_PTHREADS to LDFLAGS_NODIST (CONFIGURE_LDFLAGS_NODIST in the
# Makefile) only when --enable-wasm-pthreads took effect.
grep -Eq '^CONFIGURE_LDFLAGS_NODIST=.*-sUSE_PTHREADS' "$hostdir/Makefile" \
    || die "configure did not enable wasm pthreads (no -sUSE_PTHREADS in LDFLAGS_NODIST)"
grep -Eq '^ABIFLAGS=[[:space:]]*t$' "$hostdir/Makefile" || die "host Makefile ABIFLAGS is not t"
log "ok: host configured with --disable-gil --enable-wasm-pthreads, ABIFLAGS=t"

# ---- patch: JS reachability for the embedding probe -----------------------
# Same substitution as patch_makefile_reachability in python-multiplatform/scripts/wasm/build-cpython.sh:
# the library reaches CPython without main through Module.wasmExports / Module.wasmMemory, which
# the stock link line does not expose (3.15 default: FS,callMain,ENV,HEAPU32,TTY). Written as a
# regex over the whole comma list rather than a fixed string, because the 3.15 Makefile was not
# available when this was written. The result is asserted, so a changed link line fails the
# build here instead of producing an archive the probe cannot use.
# The C functions: CPython's LINKFORSHARED lists -sEXPORTED_FUNCTIONS explicitly
# (3.14: _main,_Py_Version,__PyRuntime,...) next to -sMAIN_MODULE. Whether 3.15 still does is
# unverified, so _malloc,_free,_Py_Version are appended to that list when it exists (harmless if
# MAIN_MODULE already exports everything); the probe then checks the exports it needs and
# fails loudly if one is missing.
patch_makefile_exports() {
    python3 - "$hostdir/Makefile" <<'PATCH'
import re
import sys
from pathlib import Path
p = Path(sys.argv[1]); t = p.read_text()

def add_runtime_methods(m):
    items = m.group(2).split(",")
    for extra in ("wasmExports", "wasmMemory", "wasmTable"):
        if extra not in items:
            items.append(extra)
    return m.group(1) + ",".join(items)

t, n = re.subn(r"(-sEXPORTED_RUNTIME_METHODS=)(\S+)", add_runtime_methods, t)
assert n >= 1, "no -sEXPORTED_RUNTIME_METHODS= in the host Makefile; the link line changed shape, re-check the patch"
for m in re.finditer(r"-sEXPORTED_RUNTIME_METHODS=(\S+)", t):
    methods = m.group(1).split(",")
    assert all(x in methods for x in ("wasmExports", "wasmMemory", "wasmTable")), "wasmExports/wasmMemory/wasmTable missing after the patch: " + m.group(0)
print(f"Makefile: wasmExports,wasmMemory,wasmTable added to {n} -sEXPORTED_RUNTIME_METHODS list(s)")

def add_functions(m):
    items = m.group(2).split(",")
    for extra in ("_malloc", "_free", "_Py_Version"):
        if extra not in items:
            items.append(extra)
    return m.group(1) + ",".join(items)

t, k = re.subn(r"(-sEXPORTED_FUNCTIONS=)(\S+)", add_functions, t)
if k:
    print(f"Makefile: _malloc,_free,_Py_Version ensured in {k} -sEXPORTED_FUNCTIONS list(s)")
else:
    print("Makefile: NOTE no -sEXPORTED_FUNCTIONS= found; relying on the link exporting C symbols another way (the probe checks)")
p.write_text(t)
for line in t.splitlines():
    if "-sEXPORTED_RUNTIME_METHODS" in line or "-sEXPORTED_FUNCTIONS" in line or "-sMAIN_MODULE" in line:
        print("Makefile link line:", line.strip()[:2000])
PATCH
}
patch_makefile_exports

# ---- host build -----------------------------------------------------------
# make-host also runs `python.sh --version`, so a startup failure (for example the trampoline
# instantiation above, with FT_WASM_TRAMPOLINE_PATCH=skip) fails this step.
python3 Platforms/emscripten make-host

# ---- verification ---------------------------------------------------------
# python.wasm must use shared memory with a declared maximum.
node - "$hostdir/python.wasm" <<'NODE' || die "python.wasm has no shared memory"
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

# The interpreter itself: GIL off at run time, four real threads.
run_smoke() {
    local label="$1"; shift
    local out="$FT_WORK/smoke-$label.log" rc
    set +e
    timeout 600 "$@" "$here/smoke.py" > "$out" 2>&1
    rc=$?
    set -e
    cat "$out"
    [ "$rc" -eq 0 ] || die "smoke test ($label) exited $rc (124 = timed out, a thread start may have deadlocked)"
    grep -q '^FT-WASM-SMOKE-OK ' "$out" || die "smoke test ($label) did not print FT-WASM-SMOKE-OK"
    log "ok: smoke test ($label)"
}
run_smoke build-tree "$PWD/$hostdir/python.sh"

# ---- archive --------------------------------------------------------------
# The driver has no install or package step for Emscripten, so the archive is assembled here.
# The stdlib directory is named by the built interpreter's own sysconfig, so a non-FT build
# (python3.15) fails the name check instead of being renamed.
stdlib_name="$(timeout 300 "$PWD/$hostdir/python.sh" -c 'import os, sysconfig; print(os.path.basename(sysconfig.get_path("stdlib")))' | tail -n 1)"
[ "$stdlib_name" = "python${minor}t" ] || die "interpreter's stdlib dir is '$stdlib_name', expected python${minor}t"
log "ok: interpreter reports lib/$stdlib_name"

name="python-${PY_VERSION}t-$HOST"
stage="$FT_WORK/stage/$name"
rm -rf "$FT_WORK/stage"; mkdir -p "$stage/lib/$stdlib_name/lib-dynload" "$stage/include/$stdlib_name"
cp "$hostdir/python.mjs" "$hostdir/python.wasm" "$stage/"
cp "$hostdir/node_entry.mjs" "$hostdir/streams.mjs" "$stage/"
# Everything but the test suite; the zip-and-omit selection is left to whoever stages it.
tar -C Lib --exclude='./test' --exclude='__pycache__' -cf - . | tar -C "$stage/lib/$stdlib_name" -xf -
cp "$hostdir/$(cat "$hostdir/pybuilddir.txt")"/_sysconfigdata_*.py "$stage/lib/$stdlib_name/"
touch "$stage/lib/$stdlib_name/lib-dynload/.empty"
cp -R Include/. "$stage/include/$stdlib_name/"
cp "$hostdir/pyconfig.h" "$stage/include/$stdlib_name/"
{
    echo "CPython $PY_VERSION, --disable-gil --enable-wasm-pthreads, $HOST"
    echo "emcc: $emcc_line"
    echo "CFLAGS=$HOST_CFLAGS"
    echo "LDFLAGS=$HOST_LDFLAGS"
    echo "trampoline patch: $TRAMPOLINE_PATCH"
    echo "link patch: wasmExports,wasmMemory,wasmTable in EXPORTED_RUNTIME_METHODS (embedding probe)"
    echo "built from https://www.python.org/ftp/python/ (Python-$PY_VERSION.tgz)"
} > "$stage/BUILD-INFO.txt"

archive="$FT_OUT/$name.tar.gz"
mkdir -p "$FT_OUT"
tar -C "$FT_WORK/stage" -czf "$archive" "$name"

# Re-check the archive itself, unpacked somewhere else, and run it with PYTHONHOME pointing
# at it (node_entry.mjs copies process.env into the Emscripten ENV, and NODEFS mounts /).
check="$FT_WORK/wasm-check"
rm -rf "$check"; mkdir -p "$check"
tar -xzf "$archive" -C "$check"
root="$check/$name"
require_gil_disabled "$root/include/python${minor}t/pyconfig.h"
[ -d "$root/lib/python${minor}t" ] || die "no lib/python${minor}t in the archive"
[ -f "$root/lib/python${minor}t/os.py" ] || die "no lib/python${minor}t/os.py in the archive"
ls "$root/lib/python${minor}t"/_sysconfigdata_*.py > /dev/null || die "no _sysconfigdata in the archive"
nodeflags=""
[ "$(node -p 'process.versions.node.split(".")[0]')" = "24" ] && nodeflags="--experimental-wasm-jspi"
# shellcheck disable=SC2086
run_smoke archive env PYTHONHOME="$root" node $nodeflags "$root/node_entry.mjs" \
    --this-program="$root/bin/python${minor}t"

echo "wasm32-emscripten archive (Emscripten $EMSDK_VERSION, trampoline patch: $TRAMPOLINE_PATCH):" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
record_sha256 "$archive"
