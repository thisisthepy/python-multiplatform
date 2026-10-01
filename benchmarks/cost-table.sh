#!/usr/bin/env bash
#
# The one command that cuts docs/investigations/cost-table.md.
#
#     ./benchmarks/cost-table.sh                    # all six targets, once each, then render
#     ./benchmarks/cost-table.sh --runs 3           # three runs each, rendered as min-max
#     ./benchmarks/cost-table.sh --targets desktop  # one target
#     ./benchmarks/cost-table.sh render             # re-render the newest JSON, run nothing
#
# Prerequisites are checked below rather than assumed, because every one of them has silently
# produced a wrong or empty table at least once in this repo.
#
# This is a script and not a Gradle task deliberately: the task would have to invoke `./gradlew`
# for each target from inside a running build, and a nested Gradle invocation on the same project
# directory deadlocks on the outer build's caches rather than failing cleanly.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(dirname "$here")"

: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
export ANDROID_HOME

if [ ! -x "$ANDROID_HOME/platform-tools/adb" ]; then
  echo "note: no adb at $ANDROID_HOME/platform-tools/adb -- the two ART targets and" >&2
  echo "      androidNativeArm64 will be reported as skipped, not silently omitted." >&2
fi

python3 --version >/dev/null 2>&1 || { echo "python3 is required" >&2; exit 1; }

exec python3 "$here/cost_table.py" "$@"
