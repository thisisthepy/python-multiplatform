#!/bin/bash
# Verification gate for the paseo loop. Exits 0 only if every suite that the loop
# may have touched is green. Counts come from the XML, not from exit codes, and the
# results directories are cleared first so a crashed run cannot report stale numbers.
set -u
cd /Volumes/macMini/worktrees/loop || exit 1
export ANDROID_HOME=/Users/ibrew/Library/Android/sdk

rm -rf python-multiplatform/build/test-results \
       ksp-fixtures/app/build/test-results \
       ksp-fixtures/compose/build/test-results \
       python-multiplatform-gradle-plugin/build/test-results

./gradlew :python-multiplatform:desktopTest :ksp-fixtures:app:desktopTest \
          :ksp-fixtures:compose:desktopTest :python-multiplatform-gradle-plugin:test \
          :python-multiplatform:compileKotlinAndroidNativeArm64 \
          --rerun --console=plain > /tmp/loop-verify.log 2>&1
rc=$?

python3 - <<'PY'
import glob, sys, xml.etree.ElementTree as ET
bad = 0
for label, pat in [
    ("desktop", "python-multiplatform/build/test-results/desktopTest/*.xml"),
    ("app", "ksp-fixtures/app/build/test-results/desktopTest/*.xml"),
    ("compose", "ksp-fixtures/compose/build/test-results/desktopTest/*.xml"),
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

grep -c "^e: " /tmp/loop-verify.log
[ "$rc" -eq 0 ] && [ "$py" -eq 0 ]
