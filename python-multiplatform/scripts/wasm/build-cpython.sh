#!/bin/bash
# CPython 3.14.2 for wasm32-emscripten, ABI-matched to pyemscripten_2026_0 (PEP 783), and staged as
# the runtime python-multiplatform's wasmJs target runs against.
#
#   python-multiplatform/scripts/wasm/build-cpython.sh            build + zip + verify + stage   (needs network the first time)
#   python-multiplatform/scripts/wasm/build-cpython.sh zip        rebuild only the stdlib zip of an existing build (no emsdk)
#   python-multiplatform/scripts/wasm/build-cpython.sh verify     check an existing build and its zip carry the ABI claims
#   python-multiplatform/scripts/wasm/build-cpython.sh stage      copy python.wasm, python.mjs and the zip to the runtime dir
#   python-multiplatform/scripts/wasm/build-cpython.sh wheels     download the pinned compiled wheels the wasm suite loads
#   python-multiplatform/scripts/wasm/build-cpython.sh stock      build a STOCK PEP 776 interpreter as a negative control
#
# Everything lands inside this repository, under the git-ignored `.caches/`:
#
#   .caches/emsdk                   emsdk, pinned to 5.0.3            (override: EMSDK)
#   .caches/wasm-build/cpython314   CPython checkout + cross build    (override: CPYTHON_CHECKOUT)
#   .caches/wasm-runtime            the three files the library needs (override: WASM_RUNTIME_DIR)
#   .caches/wasm-wheels             pinned pyemscripten_2026_0 wheels     (override: WASM_WHEELS_DIR)
#   .caches/wasm-build/cpython314-stock, .caches/wasm-runtime-stock   the `stock` control
#
# `.caches/wasm-runtime` is the default `wasmPythonDir` in python-multiplatform/build.gradle.kts, so
# after one run of this script `./gradlew :python-multiplatform:wasmJsNodeTest` runs the suite with
# no flags. It is also exactly the layout of an unpacked `python-multiplatform-wasm-runtime` zip.
#
# Why this is a build and not a download (unlike every other platform's CPython): no distributor
# ships a python.wasm with `wasmExports,wasmMemory` in -sEXPORTED_RUNTIME_METHODS, which is the one
# addition this library needs so its @WasmImport declarations have something to bind to.
#
# What separates this from a stock PEP 776 build (`Tools/wasm/emscripten build`), in order of how
# much work each is:
#
#   1. unwinding ABI  -fwasm-exceptions -sSUPPORT_LONGJMP=wasm, at compile AND link. This is the one
#                     that gates wheel loading: python.wasm must export the __cpp_exception and
#                     __c_longjmp tags every pyemscripten_2026_0 side module imports. Without it:
#                       LinkError: Import "env" "__cpp_exception": tag import requires a WebAssembly.Tag
#   2. tag claim      PYEMSCRIPTEN_PLATFORM_VERSION, which does not exist in CPython 3.14.2.
#                     `packaging` reads it to emit the platform tag. See the sysconfig patch below.
#   3. JS reachability  wasmExports/wasmMemory/wasmTable in -sEXPORTED_RUNTIME_METHODS. Not
#                     ABI-sensitive; needed only so Kotlin has something to bind @WasmImport to.
#   4. NOT DONE       lzma, zstd and OpenSSL are still missing. ABI-sensitive per the Pyodide flag
#                     list, but they did not gate the wheel tested (pydantic_core 2.48.0).
#
# TRAP: the driver hardcodes CFLAGS=-DPY_CALL_TRAMPOLINE -sUSE_BZIP2 in its configure argv and
# appends user args AFTER; autoconf takes the last assignment, so the full string must be repeated or
# -DPY_CALL_TRAMPOLINE is silently dropped.
#
# TRAP: the stdlib zip is not rebuilt by `make-host`. The build this script replaces was patched
# after its zip had been made, so the zip it shipped carried neither patch: under the library the
# interpreter reported PYEMSCRIPTEN_PLATFORM_VERSION = None. `zip` therefore always runs after the
# patches, and `verify` checks the zip, not only the build tree.
#
# Emscripten is pinned to 5.0.3 because that is what pyemscripten_2026_0 specifies (Pyodide
# Makefile.envs at tag 314.0.4: PYODIDE_EMSCRIPTEN_VERSION ?= 5.0.3, PYODIDE_ABI_VERSION ?= 2026_0,
# PYVERSION ?= 3.14.2).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
CACHES="$ROOT/.caches"

CPYTHON_TAG=v3.14.2
CPYTHON_COMMIT=df793163d5821791d4e7caf88885a2c11a107986
EMSCRIPTEN_VERSION=5.0.3
PLATFORM_VERSION=2026_0

EMSDK="${EMSDK:-$CACHES/emsdk}"
CHECKOUT="${CPYTHON_CHECKOUT:-$CACHES/wasm-build/cpython314}"
RUNTIME_DIR="${WASM_RUNTIME_DIR:-$CACHES/wasm-runtime}"
BUILD="$CHECKOUT/cross-build/wasm32-emscripten/build/python"
NATIVE_PYTHON="$CHECKOUT/cross-build/build/python.exe"
ZIP_NAME=python3.14.zip
ZIP="${STDLIB_ZIP:-$BUILD/$ZIP_NAME}"

ABI_FLAGS="-fwasm-exceptions -sSUPPORT_LONGJMP=wasm"

# --- the wheels the suite loads -----------------------------------------------------------------------
# `wasmJsTest/.../WasmCompiledWheelTest` imports a real compiled pyemscripten_2026_0 wheel and calls
# into it -- the claim the ABI flags above exist for. The wheels are not checked in; they are
# downloaded here by URL and accepted only with the sha256 below. Both pins were read from PyPI's
# JSON API (https://pypi.org/pypi/<project>/<version>/json, `urls[].digests.sha256`):
#
#   pydantic-core 2.48.0        Rust/PyO3, cp314-cp314-pyemscripten_2026_0_wasm32 -- the compiled one
#   typing-extensions 4.16.0    pure Python; pydantic-core 2.48.0 requires typing-extensions>=4.14.1
#
# "<file name> <url> <sha256>", one per line.
WHEELS_DIR="${WASM_WHEELS_DIR:-$CACHES/wasm-wheels}"
WHEELS=(
  "pydantic_core-2.48.0-cp314-cp314-pyemscripten_2026_0_wasm32.whl https://files.pythonhosted.org/packages/19/70/b7b9042d5e745d3893f8b5597a00727325d0f41dd5e5fd8875335d876770/pydantic_core-2.48.0-cp314-cp314-pyemscripten_2026_0_wasm32.whl 4fc45a49334c54541cbc97bf416d9300b4b1d3b2840dfb079b1123dc3f9ef5a6"
  "typing_extensions-4.16.0-py3-none-any.whl https://files.pythonhosted.org/packages/49/d3/b8441a820a491ddfc024b0b0cf0393375b75ea13866d9c66727e54c2fc80/typing_extensions-4.16.0-py3-none-any.whl 481caa481374e813c1b176ada14e97f1f67a4539ce9cfeb3f350d78d6370c2e8"
)

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
  emcc --version | head -1 | grep -q "$EMSCRIPTEN_VERSION" ||
    die "emcc is not $EMSCRIPTEN_VERSION; pyemscripten_2026_0 needs exactly that version"
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

# --- the native build Python, libffi and mpdec ---------------------------------------------------------
# `configure-host` asserts on the native build interpreter's lib.* directory and links against the
# wasm libffi/mpdec in the prefix, none of which exists in a fresh checkout. The tree this script
# was first run against had been built by the driver's own `build` beforehand, which is why only
# configure-host/make-host appeared here and a fresh checkout failed at configure-host.
ensure_prerequisites() {
  if [ ! -x "$NATIVE_PYTHON" ]; then
    ( cd "$CHECKOUT" &&
      python3 Tools/wasm/emscripten configure-build-python &&
      python3 Tools/wasm/emscripten make-build-python )
  fi
  if [ ! -f "$CHECKOUT/cross-build/wasm32-emscripten/prefix/lib/libffi.a" ]; then
    ( cd "$CHECKOUT" && python3 Tools/wasm/emscripten make-libffi )
  fi
  if [ ! -f "$CHECKOUT/cross-build/wasm32-emscripten/prefix/lib/libmpdec.a" ]; then
    ( cd "$CHECKOUT" && python3 Tools/wasm/emscripten make-mpdec )
  fi
}

# --- 2b + 3. Makefile edits, before the first link ----------------------------------------------------
# Split in two because `stock` takes only the second: the platform claim is ABI, the runtime methods
# are not (neither name is in PEP 783's ABI-sensitive list) and the library cannot bind without them.
patch_makefile() {
  patch_makefile_platform
  patch_makefile_reachability
}

patch_makefile_platform() {
  python3 - "$BUILD/Makefile" "$PLATFORM_VERSION" <<'PATCH'
import sys
from pathlib import Path
p = Path(sys.argv[1]); version = sys.argv[2]; t = p.read_text()
if "\nPYEMSCRIPTEN_PLATFORM_VERSION" not in t:
    i = t.index("CONFIGURE_CFLAGS=\t")
    t = t[:i] + f"PYEMSCRIPTEN_PLATFORM_VERSION=\t{version}\n" + t[i:]
    print(f"Makefile: PYEMSCRIPTEN_PLATFORM_VERSION={version}")
p.write_text(t)
PATCH
}

patch_makefile_reachability() {
  python3 - "$BUILD/Makefile" <<'PATCH'
import sys
from pathlib import Path
p = Path(sys.argv[1]); t = p.read_text()
old = "-sEXPORTED_RUNTIME_METHODS=FS,callMain,ENV,HEAPU32,TTY"
new = old + ",wasmExports,wasmMemory,wasmTable,addFunction,removeFunction"
if old in t and "wasmExports" not in t:
    t = t.replace(old, new)
    print("Makefile: runtime methods exposed for @WasmImport")
assert "wasmExports" in t, "EXPORTED_RUNTIME_METHODS changed shape; re-check the patch"
p.write_text(t)
PATCH
}

do_build() {
  ensure_emsdk
  ensure_checkout
  ensure_prerequisites
  patch_sysconfig
  ( cd "$CHECKOUT" &&
    python3 Tools/wasm/emscripten configure-host --clean \
      "CFLAGS=-DPY_CALL_TRAMPOLINE -sUSE_BZIP2 $ABI_FLAGS" \
      "LDFLAGS=$ABI_FLAGS" )
  patch_makefile
  ( cd "$CHECKOUT" && python3 Tools/wasm/emscripten make-host )
}

# --- the stdlib zip ---------------------------------------------------------------------------------
# Runs CPython's own wasm_assets.py with the *native* build interpreter, the way the generated
# Makefile's $(ZIP_STDLIB) rule does -- but with paths derived here rather than read from the
# Makefile, which bakes in the absolute location the build was configured at and breaks if the tree
# is moved. Bytecode goes to a private pycache prefix so nothing is written into the checkout's Lib.
mk_var() { sed -n -E "s/^$1=[[:space:]]*(.*)$/\1/p" "$BUILD/Makefile" | head -1; }

do_zip() {
  [ -x "$NATIVE_PYTHON" ] || die "no native build interpreter at $NATIVE_PYTHON; run a full build first"
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
    "$NATIVE_PYTHON" "$CHECKOUT/Tools/wasm/emscripten/wasm_assets.py" \
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

do_verify() {
  [ -f "$BUILD/python.wasm" ] || die "no $BUILD/python.wasm"
  [ -f "$ZIP" ] || die "no $ZIP"
  local tags
  tags="$(exported_tags "$BUILD/python.wasm")"
  echo "exported wasm tags: ${tags:-NONE}"
  for t in __cpp_exception __c_longjmp; do
    case ",$tags," in *",$t,"*) ;; *) die "missing tag $t: unwinding ABI NOT matched" ;; esac
  done
  python3 - "$ZIP" "$PLATFORM_VERSION" <<'CHECK'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1]); version = sys.argv[2]
names = z.namelist()
data = [n for n in names if n.startswith("_sysconfigdata_")]
assert data, "the stdlib zip has no _sysconfigdata module"
blob = z.read(data[0])
assert b"PYEMSCRIPTEN_PLATFORM_VERSION" in blob and version.encode() in blob, \
    f"{data[0]} in the zip does not carry PYEMSCRIPTEN_PLATFORM_VERSION={version}: the zip predates the Makefile patch"
assert b"PYEMSCRIPTEN_PLATFORM_VERSION" in z.read("sysconfig/__init__.pyc"), \
    "sysconfig in the zip lacks the _ALWAYS_STR patch: the tag would read pyemscripten_20260"
print(f"stdlib zip: {data[0]} claims {version}; sysconfig keeps it a str")
CHECK
}

do_stage() {
  mkdir -p "$RUNTIME_DIR"
  cp "$BUILD/python.wasm" "$BUILD/python.mjs" "$RUNTIME_DIR/"
  cp "$ZIP" "$RUNTIME_DIR/$ZIP_NAME"
  echo "staged into $RUNTIME_DIR"
}

# --- wheels ------------------------------------------------------------------------------------------
sha256_of() {
  if command -v sha256sum > /dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

do_wheels() {
  mkdir -p "$WHEELS_DIR"
  local entry name url sha dest got
  for entry in "${WHEELS[@]}"; do
    read -r name url sha <<< "$entry"
    dest="$WHEELS_DIR/$name"
    if [ -f "$dest" ] && [ "$(sha256_of "$dest")" = "$sha" ]; then
      echo "wheel present: $name"
      continue
    fi
    rm -f "$dest.part"
    curl -fsSL --retry 3 "$url" -o "$dest.part"
    got="$(sha256_of "$dest.part")"
    if [ "$got" != "$sha" ]; then
      rm -f "$dest.part"
      die "$name: downloaded sha256 $got, pinned $sha -- refusing it"
    fi
    mv "$dest.part" "$dest"
    echo "fetched $name"
  done
}

# --- stock: the negative control ---------------------------------------------------------------------
# CPython's own PEP 776 build, with exactly one addition: the runtime methods the library binds
# through (patch_makefile_reachability). No unwinding ABI, no PYEMSCRIPTEN_PLATFORM_VERSION, no
# sysconfig patch. Point the suite at it and the two tests that exist for the ABI must fail:
#
#   ./gradlew :python-multiplatform:wasmJsNodeTest -PwasmPythonDir=.caches/wasm-runtime-stock \
#       --tests '*WasmInterpreterAbiTest*' --tests '*WasmCompiledWheelTest*'
#
#   WasmInterpreterAbiTest   no __cpp_exception tag; PYEMSCRIPTEN_PLATFORM_VERSION is None
#   WasmCompiledWheelTest    LinkError: ... "__cpp_exception": tag import requires a WebAssembly.Tag
#
# A separate checkout, not a second build directory: configure-host --clean wipes the one build
# directory a checkout has, and the sysconfig patch lives in the checkout's Lib/.
do_stock() {
  CHECKOUT="${STOCK_CPYTHON_CHECKOUT:-$CACHES/wasm-build/cpython314-stock}"
  RUNTIME_DIR="${STOCK_WASM_RUNTIME_DIR:-$CACHES/wasm-runtime-stock}"
  BUILD="$CHECKOUT/cross-build/wasm32-emscripten/build/python"
  NATIVE_PYTHON="$CHECKOUT/cross-build/build/python.exe"
  ZIP="$BUILD/$ZIP_NAME"
  ensure_emsdk
  ensure_checkout
  if grep -q PYEMSCRIPTEN_PLATFORM_VERSION "$CHECKOUT/Lib/sysconfig/__init__.py"; then
    die "$CHECKOUT carries the ABI build's sysconfig patch; the stock control needs a clean checkout"
  fi
  ensure_prerequisites
  ( cd "$CHECKOUT" && python3 Tools/wasm/emscripten configure-host --clean )
  patch_makefile_reachability
  ( cd "$CHECKOUT" && python3 Tools/wasm/emscripten make-host )
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
  all)    do_build; do_zip; do_verify; do_stage; do_wheels ;;
  build)  do_build ;;
  zip)    do_zip ;;
  verify) do_verify ;;
  stage)  do_stage ;;
  wheels) do_wheels ;;
  stock)  do_stock ;;
  *) die "unknown command '$1' (all | build | zip | verify | stage | wheels | stock)" ;;
esac
