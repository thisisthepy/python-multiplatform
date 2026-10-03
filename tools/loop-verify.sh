#!/bin/bash
# Verification gate for the paseo loop. Exits 0 only if every suite that the loop
# may have touched is green. Counts come from the XML, not from exit codes, and the
# results directories are cleared first so a crashed run cannot report stale numbers.
#
#   tools/loop-verify.sh [checkout]     defaults to the checkout this script lives in
#
# Each suite is its own Gradle invocation: one invocation can hide an ordering dependency
# between modules (it once hid 24 failures in :ksp-fixtures:artifact).
set -u
ROOT="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$ROOT" || exit 1
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
LOGDIR="$ROOT/.tmp/loop-verify"
mkdir -p "$LOGDIR"

rm -rf python-multiplatform/build/test-results \
       ksp-fixtures/app/build/test-results \
       ksp-fixtures/compose/build/test-results \
       ksp-fixtures/artifact/build/test-results \
       python-multiplatform-gradle-plugin/build/test-results

rc=0
for task in :python-multiplatform:desktopTest :ksp-fixtures:app:desktopTest \
            :ksp-fixtures:compose:desktopTest :ksp-fixtures:artifact:desktopTest \
            :python-multiplatform-gradle-plugin:test \
            :python-multiplatform:compileKotlinAndroidNativeArm64; do
  log="$LOGDIR/$(echo "$task" | tr ':' '_').log"
  ./gradlew "$task" --rerun --console=plain > "$log" 2>&1
  r=$?
  echo "$task exit=$r compile-errors=$(grep -c '^e: ' "$log")"
  [ "$r" -eq 0 ] || rc=1
done

python3 - <<'PY'
import glob, sys, xml.etree.ElementTree as ET
bad = 0
for label, pat in [
    ("desktop", "python-multiplatform/build/test-results/desktopTest/*.xml"),
    ("app", "ksp-fixtures/app/build/test-results/desktopTest/*.xml"),
    ("compose", "ksp-fixtures/compose/build/test-results/desktopTest/*.xml"),
    ("artifact", "ksp-fixtures/artifact/build/test-results/desktopTest/*.xml"),
    ("plugin", "python-multiplatform-gradle-plugin/build/test-results/test/*.xml"),
]:
    t = f = e = 0
    for p in glob.glob(pat):
        r = ET.parse(p).getroot()
        t += int(r.get('tests')); f += int(r.get('failures')); e += int(r.get('errors'))
    print(f"{label}: {t}/{f}/{e}")
    if t == 0 or f or e:
        bad = 1
sys.exit(bad)
PY
py=$?

[ "$rc" -eq 0 ] && [ "$py" -eq 0 ]
