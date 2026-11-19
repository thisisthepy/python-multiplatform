#!/usr/bin/env bash
# Build free-threaded CPython (--disable-gil) for aarch64-linux-android with
# CPython's own tooling (Platforms/Android). Usage: build-android.sh [VERSION] [HOST]
# HOST is aarch64-linux-android (default) or x86_64-linux-android.
# Needs ANDROID_HOME (SDK with cmdline-tools/latest) and a JDK 17, 21 or 25.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=common.sh
source "$here/common.sh"

PY_VERSION="${1:-3.15.0}"
HOST="${2:-aarch64-linux-android}"
case "$HOST" in
    aarch64-linux-android) elf_arch="ARM aarch64" ;;
    x86_64-linux-android) elf_arch="x86-64" ;;
    *) echo "unsupported HOST: $HOST" >&2; exit 2 ;;
esac
: "${ANDROID_HOME:?ANDROID_HOME must point at the Android SDK}"

src="$(fetch_source "$PY_VERSION")"
cd "$src"

# Android's app seccomp filter kills legacy syscalls on x86_64: CPython's vendored mimalloc calls
# syscall(SYS_access) (and SYS_open) directly whenever those numbers exist, which raised SIGSYS
# at Py_Initialize on the x86_64 emulator (issue #436, toolchain#145). Make Android take
# mimalloc's libc branch (bionic uses openat/faccessat). arm64 has no __NR_access and already does.
prim=Objects/mimalloc/prim/unix/prim.c
guard='#if defined(MI_HAS_SYSCALL_H) && defined(SYS_open) && defined(SYS_close) && defined(SYS_read) && defined(SYS_access)'
if ! grep -qF '&& !defined(__ANDROID__)' "$prim"; then
    grep -qxF "$guard" "$prim" || die "$prim: mimalloc raw-syscall guard not found; recheck issue #436"
    GUARD="$guard" python3 - "$prim" <<'PY'
import os, sys
p = sys.argv[1]
g = os.environ["GUARD"]
s = open(p).read()
assert s.count(g + "\n") == 1, "guard line is not unique"
open(p, "w").write(s.replace(g + "\n", g + " && !defined(__ANDROID__)\n"))
PY
fi
grep -qF "$guard && !defined(__ANDROID__)" "$prim" || die "$prim: Android seccomp patch did not apply"

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
file "$lib" | tee /dev/stderr | grep -q "$elf_arch" || die "$lib is not an $elf_arch ELF"
[ -d "$check/prefix/lib/python${minor}t" ] || die "no lib/python${minor}t in the package"

cp "$archive" "$FT_OUT/"
echo "Android archive ($HOST):" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
record_sha256 "$FT_OUT/$(basename "$archive")"
