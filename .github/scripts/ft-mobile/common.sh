#!/usr/bin/env bash
# Helpers shared by build-android.sh and build-ios.sh. Source this file, do not run it.

# FT_WORK: scratch directory (source tree and cross-build output).
# FT_OUT : directory that receives the archives the workflow uploads.
FT_WORK="${FT_WORK:-$PWD/.tmp/ft-mobile}"
FT_OUT="${FT_OUT:-$PWD/.tmp/ft-mobile-out}"

log() { echo "[ft-mobile] $*" >&2; }
die() { echo "[ft-mobile] ERROR: $*" >&2; exit 1; }

# "3.15.0" -> "3.15" (also "3.15.0rc1" -> "3.15").
py_minor() { echo "$1" | sed -E 's/^([0-9]+\.[0-9]+).*/\1/'; }

# Download and unpack the CPython source tarball; print the source directory.
# CPython's mobile tooling needs the directory to be a git work tree only for
# `Platforms/Android package` (it runs `git ls-files` there), so the caller
# decides whether to `git init`.
fetch_source() {
    local version="$1"
    local dirver="${version%%[a-z]*}"   # the ftp directory has no rc/alpha suffix
    local tgz="$FT_WORK/Python-$version.tgz"
    mkdir -p "$FT_WORK" "$FT_OUT"
    if [ ! -f "$tgz" ]; then
        log "downloading CPython $version"
        curl -fL --retry 5 --retry-all-errors -o "$tgz" \
            "https://www.python.org/ftp/python/$dirver/Python-$version.tgz"
    fi
    if [ ! -d "$FT_WORK/Python-$version" ]; then
        tar -xzf "$tgz" -C "$FT_WORK"
    fi
    echo "$FT_WORK/Python-$version"
}

# Fail unless FILE contains a line matching `#define Py_GIL_DISABLED 1`.
require_gil_disabled() {
    local file="$1"
    grep -Eq '^#define Py_GIL_DISABLED 1$' "$file" \
        || die "$file does not define Py_GIL_DISABLED 1"
    log "ok: Py_GIL_DISABLED 1 in $file"
}

# Print "sha256  name" lines for the given files, to stdout and the job summary.
record_sha256() {
    local f line
    for f in "$@"; do
        line="$(cd "$(dirname "$f")" && shasum -a 256 "$(basename "$f")")"
        echo "$line"
        if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
            echo "$line" >> "$GITHUB_STEP_SUMMARY"
        fi
    done
}
