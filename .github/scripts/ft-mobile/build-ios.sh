#!/usr/bin/env bash
# Build free-threaded CPython (--disable-gil) as an iOS XCframework with
# CPython's own tooling (Platforms/Apple). Usage: build-ios.sh [VERSION]
# Needs macOS with a full Xcode and python3 >= 3.10.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=common.sh
source "$here/common.sh"

PY_VERSION="${1:-3.15.0}"
# The XCframework needs every slice Platforms/Apple declares: one device host
# and two simulator hosts (arm64, x86_64).
HOSTS=(arm64-apple-ios arm64-apple-ios-simulator x86_64-apple-ios-simulator)

src="$(fetch_source "$PY_VERSION")"
cd "$src"
xcodebuild -version

# Platforms/Apple names the standard library directory "python<major.minor>"
# when it packages and in the Xcode build helper, but a free-threaded build
# installs "python<major.minor>t" (LIBDEST has $(ABI_THREAD)). Without these two
# edits `package` fails copying the xcprivacy files and the helper script would
# not find the standard library. Each edit is asserted so a changed upstream
# script fails loudly instead of silently skipping the patch.
python3 -I - <<'PY'
import pathlib
def patch(path, old, new):
    p = pathlib.Path(path)
    s = p.read_text()
    assert s.count(old) == 1, f"{path}: expected exactly one {old!r}"
    p.write_text(s.replace(old, new))
patch("Platforms/Apple/__main__.py",
      'version_tag = ".".join(version.split(".")[:2])',
      'version_tag = ".".join(version.split(".")[:2]) + "t"')
patch("Platforms/Apple/testbed/Python.xcframework/build/utils.sh",
      'grep -E "^python3\\.\\d+$"',
      'grep -E "^python3\\.[0-9]+t?$"')
PY

# --disable-gil goes to configure after `--`, for the build Python and each host.
python3 Platforms/Apple configure-build -- --disable-gil
python3 Platforms/Apple make-build
python3 Platforms/Apple pythoninfo-build
for host in "${HOSTS[@]}"; do
    python3 Platforms/Apple configure-host iOS "$host" -- --disable-gil
    python3 Platforms/Apple make-host iOS "$host"
done
python3 Platforms/Apple package iOS

# ---- verification -------------------------------------------------------
minor="$(py_minor "$PY_VERSION")"
archive="$(ls cross-build/dist/python-*-iOS-XCframework.tar.gz)"
log "package: $archive"

# On macOS the build interpreter is python.exe (cross-build/build/python is a directory).
build_py=cross-build/build/python.exe; [ -x "$build_py" ] || build_py=cross-build/build/python
"$build_py" -c 'import sys, sysconfig; assert sysconfig.get_config_var("Py_GIL_DISABLED") == 1; print("build python free-threaded:", sys.version)'

check="$FT_WORK/ios-check"
rm -rf "$check"; mkdir -p "$check"
tar -xzf "$archive" -C "$check"
xcf="$check/Python.xcframework"
[ -d "$xcf" ] || die "no Python.xcframework in the package"

[ -d "$xcf/lib/python${minor}t" ] || die "no lib/python${minor}t in the XCframework"
slices=0
for slice in "$xcf"/ios-*; do
    slices=$((slices + 1))
    # Headers/pyconfig.h is a wrapper that includes pyconfig-<arch>.h.
    found=0
    for h in "$slice"/Python.framework/Headers/pyconfig-*.h; do
        require_gil_disabled "$h"; found=$((found + 1))
    done
    [ "$found" -gt 0 ] || die "no pyconfig-<arch>.h in $slice"
    [ -e "$slice/lib/libpython${minor}t.dylib" ] || die "no libpython${minor}t.dylib in $slice"
    lipo -info "$slice/Python.framework/Python"
done
[ "$slices" -eq 2 ] || die "expected 2 slices (device, simulator), found $slices"

cp "$archive" "$FT_OUT/"
echo "iOS archive:" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
record_sha256 "$FT_OUT/$(basename "$archive")"
