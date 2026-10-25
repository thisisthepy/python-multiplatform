#!/usr/bin/env bash
# Build free-threaded CPython (--disable-gil) for aarch64-linux-android with
# CPython's own tooling (Platforms/Android). Usage: build-android.sh [VERSION]
# Needs ANDROID_HOME (SDK with cmdline-tools/latest) and a JDK 17, 21 or 25.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=common.sh
source "$here/common.sh"

PY_VERSION="${1:-3.15.0}"
HOST=aarch64-linux-android
: "${ANDROID_HOME:?ANDROID_HOME must point at the Android SDK}"

src="$(fetch_source "$PY_VERSION")"
cd "$src"

# `Platforms/Android package` lists the files to ship with `git ls-files`, so
# the unpacked tarball needs an index that tracks Platforms/Android.
if [ ! -d .git ]; then
    git init -q .
    git add Platforms/Android
fi

# Install the NDK version the tooling pins (android-env.sh would do it on first
# use; doing it here makes a missing cmdline-tools fail early and visibly).
ndk_version="$(sed -n 's/^ndk_version=//p' Platforms/Android/android-env.sh)"
[ -n "$ndk_version" ] || die "no ndk_version in Platforms/Android/android-env.sh"
log "NDK required by CPython $PY_VERSION: $ndk_version"
if [ ! -e "$ANDROID_HOME/ndk/$ndk_version/package.xml" ]; then
    yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "ndk;$ndk_version" >/dev/null || true
fi
[ -e "$ANDROID_HOME/ndk/$ndk_version/package.xml" ] || die "NDK $ndk_version not installed"

# --disable-gil goes to configure after `--`, for the build Python and the host.
python3 Platforms/Android configure-build -- --disable-gil
python3 Platforms/Android make-build
python3 Platforms/Android pythoninfo-build
python3 Platforms/Android configure-host "$HOST" -- --disable-gil
python3 Platforms/Android make-host "$HOST"
python3 Platforms/Android package "$HOST"

# ---- verification -------------------------------------------------------
minor="$(py_minor "$PY_VERSION")"
archive="$(ls cross-build/$HOST/dist/python-*-$HOST.tar.gz)"
log "package: $archive"

# The build Python must itself be free-threaded.
cross-build/build/python -c 'import sys, sysconfig; assert sysconfig.get_config_var("Py_GIL_DISABLED") == 1; print("build python free-threaded:", sys.version)'

check="$FT_WORK/android-check"
rm -rf "$check"; mkdir -p "$check"
tar -xzf "$archive" -C "$check"

require_gil_disabled "$check/prefix/include/python${minor}t/pyconfig.h"
lib="$(ls "$check"/prefix/lib/libpython${minor}t*.so* 2>/dev/null | head -n 1 || true)"
[ -n "$lib" ] || die "no libpython${minor}t*.so in the package"
log "ok: $lib"
file "$lib" | tee /dev/stderr | grep -q 'ARM aarch64' || die "$lib is not an aarch64 ELF"
[ -d "$check/prefix/lib/python${minor}t" ] || die "no lib/python${minor}t in the package"

cp "$archive" "$FT_OUT/"
echo "Android archive:" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
record_sha256 "$FT_OUT/$(basename "$archive")"
