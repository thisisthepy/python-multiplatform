#!/bin/bash
# One command, one table.
#
#   ./run-bench.sh host           JS-only rows + the warmup sweep, under Hermes and Node.
#                                 No device. Safe to run at any time.
#   ./run-bench.sh android        Build, install, launch, capture the report from logcat.
#                                 Needs one connected device or a running emulator.
#   ./run-bench.sh ios            Build, install, launch, capture the report from the simulator log.
#                                 Needs one BOOTED simulator. Will not boot one for you.
#   ./run-bench.sh build          Build both platforms and stop. Touches no device.
#   ./run-bench.sh all            host, then android, then ios. Never stops early: a platform that
#                                 cannot run writes down why and the next one still runs.
#
# Output goes to results/<platform>-<timestamp>.txt as well as to the terminal. Every file opens
# with the conditions the run was taken under -- load average, device, engine, versions, counts --
# because a per-call figure without them is not a measurement, and this workspace has already lost
# a bisect to numbers recorded without a load average beside them.
#
# Environment:
#   ANDROID_SERIAL=emulator-5554   pick a device when more than one is attached. Setting this is
#                                  your assertion that that device is free; the script cannot tell.
#   RN_BENCH_REQUIRE_QUIET=1       refuse to run at all if the 1-minute load average is above
#                                  RN_BENCH_MAX_LOAD (default 4.0) instead of merely warning.
#   RN_BENCH_MAX_LOAD=4.0          the threshold for the above.
#   RN_BENCH_CAPTURE_SECS=900      how long to wait for the on-device report.
#
# Nothing here averages across runs and nothing discards an outlier: each invocation produces one
# report whose rows are min-max over the repetitions inside that single process. Comparing two
# invocations of this script is comparing two processes, which the reference project's docs are
# explicit is not a measurement -- if you want a range, read the min-max inside one report.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/env.sh"

# Android and iOS do NOT share an id, and assuming they did cost one whole run: simctl launch
# failed with FBSOpenApplicationServiceErrorDomain code=4, which reads like a broken build and is
# really "no such app". `applicationId` in android/app/build.gradle is com.rnbench, while the iOS
# target still carries React Native's template default. Read from the built app rather than
# restated here, so this cannot drift again.
ANDROID_APP_ID=com.rnbench
IOS_APP_ID_DEFAULT=org.reactjs.native.example.RNBench
APP_ID="$ANDROID_APP_ID"
RESULTS="$HERE/results"
# This benchmark lives inside the reference repository (benchmarks/rn-benchmark), so the repo is
# two levels up. RN_BENCH_REFERENCE_REPO overrides it.
REFERENCE_REPO="${RN_BENCH_REFERENCE_REPO:-$(cd "$HERE/../.." && pwd)}"
mkdir -p "$RESULTS"
STAMP="$(date +%Y%m%d-%H%M%S)"
MAX_LOAD="${RN_BENCH_MAX_LOAD:-4.0}"
CAPTURE_SECS="${RN_BENCH_CAPTURE_SECS:-900}"

die() { echo "$*" >&2; exit 1; }

# --- conditions -----------------------------------------------------------------------------------
#
# Written into every results file, ahead of the report. The three that have actually caught problems
# in this workspace, and are therefore not optional:
#
#   - load average, because the reference project watched one commit read 672 and 1076 ns on a
#     loaded machine and lost a bisect to it;
#   - the reference repo's commit, because the table in COMPARISON.md quotes it and the quote goes
#     stale silently (it already did once: a whole column was re-measured upstream);
#   - a digest of the harness sources, because the harness can be edited without a commit and there
#     is otherwise no way to tell two runs of "the benchmark" apart.

load_1min() { uptime | sed -e 's/.*load averages*: *//' -e 's/,//g' | awk '{print $1}'; }

harness_digest() {
  # Everything that can change a number: the JS, the two native modules, the spec.
  find "$HERE/RNBench/bench" "$HERE/RNBench/modules/bench/src" \
       "$HERE/RNBench/modules/bench/android" "$HERE/RNBench/modules/bench/ios" \
       "$HERE/RNBench/App.tsx" \
       -type f \( -name '*.js' -o -name '*.ts' -o -name '*.tsx' -o -name '*.kt' -o -name '*.mm' -o -name '*.h' \) \
       2>/dev/null | sort | xargs shasum -a 256 2>/dev/null | shasum -a 256 | awk '{print substr($1,1,12)}'
}

reference_commit() {
  if [ -d "$REFERENCE_REPO/.git" ]; then
    local c d
    c="$(git -C "$REFERENCE_REPO" rev-parse --short HEAD 2>/dev/null)"
    d="$(git -C "$REFERENCE_REPO" status --porcelain 2>/dev/null | head -1)"
    printf '%s%s' "$c" "$([ -n "$d" ] && echo ' (working tree dirty)')"
  else
    printf 'not found at %s' "$REFERENCE_REPO"
  fi
}

rn_version() {
  awk -F'"' '/"react-native": *"/ {print $4; exit}' "$HERE/RNBench/package.json" 2>/dev/null
}

# $1 platform label, $2 extra lines (may be empty)
conditions_block() {
  local platform="$1"
  echo "=== conditions ==="
  echo "platform:         $platform"
  echo "timestamp:        $(date '+%Y-%m-%dT%H:%M:%S%z')"
  echo "host:             $(uname -srm), $(sysctl -n hw.ncpu) cores, $(( $(sysctl -n hw.memsize) / 1073741824 )) GB"
  echo "load average:     $(uptime | sed -e 's/.*load averages*: *//')   (before the run)"
  echo "react-native:     $(rn_version)"
  echo "hermes:           reported by the app itself -- see the 'engine:' line in the report below"
  echo "harness digest:   $(harness_digest)   (rn-benchmark is not a git repository)"
  echo "reference repo:   $REFERENCE_REPO @ $(reference_commit)"
  echo "counts:           warmup $(app_const WARMUP), $(app_const N) iterations per timed loop, $(app_const REPS) repetitions per row"
  echo "                  convergence sweep: $(app_const SWEEP_REPS) repetitions x $(app_const N), cold"
  if [ $# -gt 1 ] && [ -n "$2" ]; then
    printf '%s\n' "$2"
  fi
  echo
}

# Read a count out of the app rather than restating it here. These two used to be separate
# constants -- this block said "warmup 100000" as a literal while `bench/appRun.js` decided what
# the app actually did -- so a results file could state conditions the run was not taken under,
# which is the one thing a conditions block exists to prevent. It has already happened: the
# 2026-08-19T00:50 report's header says 100000 while its own verdict block reports 400000.
# A missing constant is a hard failure, not a silent blank: a conditions line that quietly says
# nothing is the same defect in a quieter font.
app_const() {
  local name="$1" value
  value=$(sed -n "s/^const ${name} = \([0-9][0-9]*\);.*/\1/p" "$HERE/RNBench/bench/appRun.js" | head -1)
  if [ -z "$value" ]; then
    echo "run-bench.sh: cannot read const ${name} from RNBench/bench/appRun.js" >&2
    exit 2
  fi
  printf '%s' "$value"
}

# Loud, and refusable. The point is that a number taken on a busy machine reaches the reader
# already labelled as unquotable, rather than being labelled later by someone who remembers.
#
# Called TWICE, and the two calls are not interchangeable:
#
#   `require_quiet_before_work` runs before anything this script does costs CPU. That is the one
#   that may refuse, because it is the only moment the load average describes the machine rather
#   than describing this script. It used to be called only after `build_android`, so the number it
#   judged was one the Gradle build had just created: a run started on a load of 1.79 measured 4.45
#   and reported itself unquotable for its own build's sake.
#
#   `load_guard` runs inside the report, to write the conditions down. Its result is discarded
#   there on purpose -- it sits in a `| tee` pipeline where a return value cannot stop anything,
#   which is exactly how `RN_BENCH_REQUIRE_QUIET=1` came to print "refusing to run" and then run
#   the whole benchmark anyway. Refusing is not its job any more.
require_quiet_before_work() {
  local l
  l="$(load_1min)"
  if awk -v a="$l" -v b="$MAX_LOAD" 'BEGIN {exit !(a > b)}'; then
    if [ "${RN_BENCH_REQUIRE_QUIET:-0}" = "1" ]; then
      echo "1-minute load average is $l, above $MAX_LOAD, and RN_BENCH_REQUIRE_QUIET=1." >&2
      echo "Refusing to start: nothing measured here would be quotable. Wait for the machine to" >&2
      echo "settle, or unset RN_BENCH_REQUIRE_QUIET to take an explicitly unquotable reading." >&2
      return 1
    fi
  fi
  return 0
}

load_guard() {
  local l
  l="$(load_1min)"
  if awk -v a="$l" -v b="$MAX_LOAD" 'BEGIN {exit !(a > b)}'; then
    echo "*********************************************************************************"
    echo "* WARNING: 1-minute load average is $l, above $MAX_LOAD."
    echo "* Figures from this run are NOT quotable. On this machine a single commit has read"
    echo "* 672 and 1076 ns for the same row under load. Re-run on a quiet machine before"
    echo "* putting anything from this file in a table."
    echo "*********************************************************************************"
    echo
  fi
  return 0
}

cmd_host() {
  local out="$RESULTS/host-$STAMP.txt"
  { conditions_block "host (no device: JS engine floor and the warmup sweep only)"
    load_guard || true
  } | tee "$out"
  "$HERE/run-host-sweep.sh" both 2>&1 | tee -a "$out"
  { echo; echo "load average after: $(uptime | sed -e 's/.*load averages*: *//')"; } | tee -a "$out"
  echo "written: $out"
}

# Release, not debug, and that is a measurement decision rather than a packaging one:
#
#   - a debug APK loads JS from a Metro server over `adb reverse`, so a run depends on a process
#     nobody captured and on whatever Metro happened to have cached. A release APK carries
#     `assets/index.android.bundle` -- Hermes bytecode, compiled at build time -- so the run is
#     reproducible from the artefact alone.
#   - debug JS is unminified and runs with dev-mode assertions on. Those are not the conditions any
#     shipped app runs in, and they inflate the JS-only floor rows that everything else is read
#     against.
#
# The release build is signed with the template's debug keystore, so it installs on a device
# without any further setup.
build_android() {
  ( cd "$HERE/RNBench/android" && ./gradlew :app:assembleRelease --console=plain ) \
    > "$HERE/android-build.log" 2>&1
  local rc=$?
  if [ $rc -ne 0 ]; then
    echo "android build FAILED (exit $rc); see $HERE/android-build.log" >&2
    grep -E "^(FAILURE|\* What went wrong)" -A6 "$HERE/android-build.log" | head -30 >&2
  fi
  return $rc
}

# Release, for the same reason Android is built Release: a Debug iOS build fetches its JS from a
# Metro server that nobody in this script starts or captures, while a Release build carries
# `main.jsbundle` inside the .app. Both platforms therefore run bytecode compiled at build time, so
# the two columns are taken under the same conditions rather than one of them running unminified
# dev-mode JS.
build_ios() {
  "$HERE/build-ios.sh" Release > "$HERE/ios-build.log" 2>&1
  local rc=$?
  if [ $rc -ne 0 ]; then
    echo "ios build FAILED (exit $rc); see $HERE/ios-build.log" >&2
    grep -E "error:" "$HERE/ios-build.log" | head -20 >&2
  fi
  return $rc
}

# --- did the run actually produce anything? -------------------------------------------------------
#
# This exists because it did not, twice, and the script exited 0 both times. `./run-bench.sh ios`
# wrote results/ios-20260814-171508.txt (22 lines) and results/ios-20260814-183543.txt (19 lines),
# each containing the conditions block and not one benchmark row, and reported success. A file that
# says nothing is worse than a missing file: it looks like a measurement until someone reads it.
#
# So: zero rows is a failure. Both platforms are checked by the same function, and both `cmd_ios`
# and `cmd_android` return its status, so `all`'s summary and the process exit code both tell the
# truth about an empty capture.
#
# A "row" is a line of the report table: `name | min ns/op | max ns/op | reps`, whose last three
# fields are numbers. That is matched rather than a mere line count because the log capture also
# yields progress (`#`) lines and prose, and a run that printed only progress lines before dying is
# exactly as empty as one that printed nothing.
report_row_count() {
  grep -cE '\| +[0-9]+\.[0-9]+ \| +[0-9]+\.[0-9]+ \| +[0-9]+$' "$1" 2>/dev/null
}

# $1 platform label, $2 results file. Appends the verdict to the file and returns non-zero if the
# run is not quotable.
verify_report() {
  local platform="$1" out="$2" rows ended warmup rc=0
  rows="$(report_row_count "$out")"; rows="${rows:-0}"
  ended=0; grep -qx 'END' "$out" 2>/dev/null && ended=1
  # The app decides this, not the harness: section 6 of bench/appRun.js reads its own convergence
  # sweeps and prints the verdict. All that happens here is that the verdict is given the same
  # weight as an empty capture, for the same reason -- see below.
  warmup=ok; grep -q 'AT LEAST ONE SWEEP SAYS THIS WARMUP IS INSUFFICIENT' "$out" 2>/dev/null && warmup=INSUFFICIENT

  { echo
    echo "=== capture check ==="
    echo "benchmark rows captured:  $rows"
    echo "END marker seen:          $([ "$ended" = 1 ] && echo yes || echo 'NO')"
    echo "warmup verdict:           $warmup   (the app's own, from its convergence sweeps)"
  } | tee -a "$out"

  if [ "$rows" -eq 0 ]; then
    { echo
      echo "*** FAILED: this run captured NO benchmark rows. ***"
      echo
      echo "Everything above the capture check is the conditions the run was attempted under; none"
      echo "of it is a measurement. Do not fill any cell from this file, and do not read the"
      echo "script's exit status as anything but failure."
      echo
      echo "Where to look first, in order of how often each has actually been the cause here:"
      echo "  1. the capture filter. The $platform capture reads the device log through a filter;"
      echo "     if the app's log level or tag changed, the filter matches nothing while the app"
      echo "     runs the whole benchmark perfectly. This was the cause both times it happened:"
      echo "     \`log stream\` defaults to --level default, which drops the Info-level messages"
      echo "     React Native's console.log produces on iOS."
      echo "  2. the app crashed before printing. Check the '#' progress lines above for the last"
      echo "     row it reached; if there are none, it died before the first row."
      echo "  3. the capture window (${CAPTURE_SECS}s) was too short. The 1000-element rows take"
      echo "     tens of seconds each."
    } | tee -a "$out"
    rc=1
  elif [ "$ended" = 0 ]; then
    { echo
      echo "*** INCOMPLETE: $rows rows were captured but the END marker never arrived. ***"
      echo "The report is cut short -- the capture window (${CAPTURE_SECS}s) may be too short, or"
      echo "the app died partway. Rows present above were measured; rows absent were not run."
    } | tee -a "$out"
    rc=1
  elif ! grep -q "all .* rows returned a positive duration" "$out"; then
    { echo
      echo "*** SUSPECT: the report finished but did not assert that every row was positive. ***"
      echo "Look for an 'UNUSABLE ROWS' line above: those rows measured <= 0 and are not figures."
    } | tee -a "$out"
    rc=1
  fi

  # A full report whose own warmup verdict says the boundary rows were taken before the host
  # settled is the same failure as an empty capture, and was being reported the same way an empty
  # capture used to be: exit 0. It is worse than empty, in fact -- an empty file makes a reader go
  # looking, while a complete-looking table with a warning eleven lines above it does not. The app
  # states this verdict precisely because the reference project published two boundary costs taken
  # under a too-small warmup and neither reproduced; having it printed and then exiting 0 would put
  # this harness back in that position.
  #
  # This does not discard the file. The JS-only rows and the sweeps are unaffected -- what the
  # verdict indicts is the boundary rows -- and the sweep printed above is what says how much more
  # warmup is needed. It is the exit status that changes, so nothing downstream reads the run as
  # quotable on its own.
  if [ "$warmup" = INSUFFICIENT ]; then
    { echo
      echo "*** NOT QUOTABLE AS A WHOLE: the run's own warmup verdict is INSUFFICIENT. ***"
      echo
      echo "$rows rows were captured and the report is complete, but at least one convergence sweep"
      echo "did not settle far enough before the warmup count. WHICH sweep decides which rows are"
      echo "affected, and the report says which -- read the 'warmup verdict' block above rather"
      echo "than discarding the file:"
      echo
      echo "  - boundary sweep thin  -> the rows that cross into native are not figures."
      echo "  - JS-only sweep thin   -> the JS baseline and the ratios are not figures; the"
      echo "                            absolute boundary numbers do not depend on them."
      echo
      echo "On this machine the usual cause is load, not a genuinely short warmup: check the load"
      echo "average at the top of this file, and re-run quiet before changing WARMUP."
    } | tee -a "$out"
    rc=1
  fi
  return $rc
}

# Writes the conditions block plus a stated reason into the results file and returns non-zero.
# A platform that could not run must leave a file saying so: a missing file is indistinguishable
# from a file nobody looked for, and "quietly skipped" is how an empty column becomes a filled one
# in someone's memory.
record_skip() {
  local platform="$1" out="$2" reason="$3"
  { conditions_block "$platform"
    echo "=== NOT RUN ==="
    printf '%s\n' "$reason"
    echo
    echo "No figures were produced. Do not treat any cell this run would have filled as measured."
  } | tee "$out"
  echo "written: $out" >&2
  return 1
}

# Exactly one device, or the one named by ANDROID_SERIAL. Prints the serial on stdout.
pick_android_device() {
  local devices count
  devices="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
  if [ -z "$devices" ]; then
    return 1
  fi
  if [ -n "${ANDROID_SERIAL:-}" ]; then
    if echo "$devices" | grep -qx "$ANDROID_SERIAL"; then
      printf '%s' "$ANDROID_SERIAL"
      return 0
    fi
    return 2
  fi
  count="$(echo "$devices" | wc -l | tr -d ' ')"
  if [ "$count" != "1" ]; then
    return 3
  fi
  printf '%s' "$devices"
  return 0
}

cmd_android() {
  local out="$RESULTS/android-$STAMP.txt"

  local serial rc
  serial="$(pick_android_device)"; rc=$?
  case $rc in
    1) record_skip "android" "$out" \
         "SKIPPED: no adb device is attached.
Start an emulator or connect a device and re-run:
  \$ANDROID_HOME/emulator/emulator -avd <name> &
  ./run-bench.sh android
Available AVDs: $(ls ~/.android/avd/*.ini 2>/dev/null | xargs -n1 basename 2>/dev/null | sed 's/\.ini$//' | tr '\n' ' ')"
       return 1 ;;
    2) record_skip "android" "$out" \
         "SKIPPED: ANDROID_SERIAL=$ANDROID_SERIAL is set but that device is not attached.
Attached: $(adb devices | awk 'NR>1 && $2=="device" {print $1}' | tr '\n' ' ')"
       return 1 ;;
    3) record_skip "android" "$out" \
         "SKIPPED: more than one device is attached and ANDROID_SERIAL is not set.
Attached: $(adb devices | awk 'NR>1 && $2=="device" {print $1}' | tr '\n' ' ')
The Android app id ($ANDROID_APP_ID) is the same on every device, so a multi-device run would
install over itself. Re-run with the one you may use, e.g.:
  ANDROID_SERIAL=emulator-5554 ./run-bench.sh android
On this machine the emulators are shared, so setting it is your assertion that that one is free."
       return 1 ;;
  esac
  export ANDROID_SERIAL="$serial"

  require_quiet_before_work || {
    record_skip "android" "$out" \
      "SKIPPED: the 1-minute load average was above $MAX_LOAD before this run built anything, and RN_BENCH_REQUIRE_QUIET=1."
    return 1
  }

  build_android || {
    record_skip "android" "$out" "SKIPPED: the release build failed. See $HERE/android-build.log"
    return 1
  }

  local apk="$HERE/RNBench/android/app/build/outputs/apk/release/app-release.apk"
  [ -f "$apk" ] || { record_skip "android" "$out" "SKIPPED: no release APK at $apk"; return 1; }

  # Identify the device in the report. `am start -W` gives the launch figure section 5b of
  # COMPARISON.md asks for, and costs nothing to take here.
  local dev_desc
  dev_desc="device:           $serial  $(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r') \
/ API $(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r') \
/ $(adb shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r') \
/ build $(adb shell getprop ro.build.id 2>/dev/null | tr -d '\r')"

  { conditions_block "android" "$dev_desc
apk:              $apk ($(du -h "$apk" | awk '{print $1}'))"
    load_guard || true
  } | tee "$out"

  adb install -r "$apk" > /dev/null || { echo "adb install failed" >&2; return 1; }

  adb logcat -c
  # -W blocks until the first frame, so this is time-to-first-render on a cold start of a
  # freshly installed APK. Recorded, not compared: the benchmark itself runs after it.
  { echo "--- am start -W (cold launch of a just-installed APK) ---"
    adb shell am start -W -n "$APP_ID/.MainActivity" 2>&1 | sed 's/^/  /'
    echo
  } | tee -a "$out"

  echo "capturing (up to ${CAPTURE_SECS} s; '#' lines are progress, not results)..."
  # A watchdog, because the alternative is a pipe that hangs forever when the app crashes before
  # printing END, and a hang looks exactly like a slow row.
  ( sleep "$CAPTURE_SECS"; adb shell am force-stop "$APP_ID" >/dev/null 2>&1 ) &
  local watchdog=$!
  adb logcat -v raw ReactNativeJS:V '*:S' | while IFS= read -r line; do
    case "$line" in
      *RNBENCH\|*)
        printf '%s\n' "${line#*RNBENCH| }"
        case "$line" in *"RNBENCH| END"*) break ;; esac
        ;;
    esac
  done | tee -a "$out"
  kill $watchdog 2>/dev/null

  { echo; echo "load average after: $(uptime | sed -e 's/.*load averages*: *//')"; } | tee -a "$out"
  # Same check as iOS, and it returns non-zero rather than only printing: the previous version here
  # printed INCOMPLETE and still exited 0, so `all`'s summary would say "produced figures".
  verify_report "android" "$out"; local vrc=$?
  echo "written: $out"
  return $vrc
}

cmd_ios() {
  local out="$RESULTS/ios-$STAMP.txt"

  # Checked before building, because the build takes minutes and these failures are instant.
  #
  # Note what is NOT checked here: `xcodebuild -showdestinations`. It reports zero iOS destinations
  # on this machine for every project, and an earlier pass read that as "iOS cannot be built here".
  # It is not: it is Xcode's scheme-destination layer refusing an SDK with no matching platform
  # component, and build-ios.sh sidesteps it by building the target against -sdk iphonesimulator.
  # The things that would actually stop a run are the simulator SDK and an installed runtime.
  if ! xcrun --sdk iphonesimulator --show-sdk-path >/dev/null 2>&1; then
    record_skip "ios" "$out" "SKIPPED: no iOS simulator SDK. \`xcrun --sdk iphonesimulator --show-sdk-path\` failed."
    return 1
  fi
  if [ "$(xcrun simctl list runtimes 2>/dev/null | grep -c '^iOS ')" = "0" ]; then
    record_skip "ios" "$out" "SKIPPED: no iOS simulator runtime is installed. \`xcrun simctl list runtimes\` lists none."
    return 1
  fi

  build_ios || { record_skip "ios" "$out" "SKIPPED: the iOS build failed. See $HERE/ios-build.log"; return 1; }

  local booted
  booted="$(xcrun simctl list devices booted | grep -c "Booted")"
  if [ "$booted" != "1" ]; then
    record_skip "ios" "$out" \
      "SKIPPED: expected exactly one BOOTED simulator, found $booted.
This script will not boot one: the simulators on this machine are shared with other work.
Boot the one you may use, then re-run:
  xcrun simctl boot <udid> && ./run-bench.sh ios"
    return 1
  fi

  local app
  app="$(find "$HERE/DerivedData/Build/Products" -name 'RNBench.app' -maxdepth 3 | head -1)"
  [ -n "$app" ] || { record_skip "ios" "$out" "SKIPPED: no RNBench.app under $HERE/DerivedData/Build/Products"; return 1; }

  # The runtime version, not just the device name. COMPARISON.md pairs this column against the
  # reference project's iosSimulatorArm64 column, and that pairing is only a pairing if both ran on
  # the same runtime -- so the runtime has to be in the file, not in someone's memory.
  local sim_desc
  sim_desc="$(xcrun simctl list devices booted \
              | awk '/^-- /{rt=$0; gsub(/^-- | --$/,"",rt)} /Booted/{sub(/^ +/,""); print $0"   [runtime: "rt"]"}' | head -1)"
  { conditions_block "ios" \
      "simulator:        $sim_desc
xcode:            $(xcodebuild -version | tr '\n' ' ')
app:              $app"
    load_guard || true
  } | tee "$out"

  xcrun simctl install booted "$app" || { echo "simctl install failed" >&2; return 1; }

  # Read the id out of the app that was just installed, rather than trusting a constant. The two
  # platforms disagree, and a stale constant fails as "device failed to launch", which reads like
  # the build is broken when the build is fine.
  local ios_app_id
  ios_app_id="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$app/Info.plist" 2>/dev/null)"
  [ -n "$ios_app_id" ] || ios_app_id="$IOS_APP_ID_DEFAULT"
  APP_ID="$ios_app_id"
  echo "bundle id:        $APP_ID  (read from the installed app)" | tee -a "$out"

  xcrun simctl terminate booted "$APP_ID" >/dev/null 2>&1

  # The log stream is started first and killed on the END marker, so this terminates on its own
  # rather than needing a Ctrl-C -- a run that needs a human to stop it is a run nobody repeats.
  #
  # `--level info` is load-bearing and was missing for two entire runs, each of which exited 0 with
  # an empty table. `log stream` defaults to `--level default`, which streams Default, Error and
  # Fault and *silently drops Info and Debug*. React Native's iOS logger emits JS `console.log` at
  # Info (`[com.facebook.react.log:javascript]`, type `I`), so the predicate below was being applied
  # to a stream the messages had already been excluded from -- the app was running the benchmark
  # correctly the whole time and nothing was listening at the right level.
  #
  # Confirmed by A/B on one launch, same predicate, same app: without `--level info`, 0 matching
  # lines; with it, 18. The Android path needs no equivalent because logcat has no such default
  # filter -- `ReactNativeJS:V` already asks for verbose.
  xcrun simctl spawn booted log stream --style compact --level info \
      --predicate 'eventMessage CONTAINS "RNBENCH|"' > "$out.raw" 2>/dev/null &
  local logpid=$!

  # Wait for the stream to actually be live rather than sleeping a guessed interval. `log stream`
  # prints "Filtering the log data using ..." once it has attached, so that banner -- the same one
  # the grep below is careful to exclude from the results -- is the readiness signal. A fixed sleep
  # here is a race whose loss looks exactly like the bug this script already has a section about:
  # an empty capture of a healthy run. The app's first line is a progress line emitted within
  # milliseconds of launch, so there is no slack to lose.
  local ready=0 waited_ready=0
  while [ $waited_ready -lt 30 ]; do
    grep -q 'Filtering the log data' "$out.raw" 2>/dev/null && { ready=1; break; }
    sleep 1
    waited_ready=$((waited_ready + 1))
  done
  if [ "$ready" = 0 ]; then
    kill $logpid 2>/dev/null
    record_skip "ios" "$out" "SKIPPED: \`log stream\` never reported it had attached (waited 30 s).
Nothing was captured because nothing was listening; this is not a result about the app."
    return 1
  fi

  xcrun simctl launch booted "$APP_ID" > /dev/null || { kill $logpid 2>/dev/null; echo "simctl launch failed" >&2; return 1; }

  local waited=0
  while [ $waited -lt "$CAPTURE_SECS" ]; do
    grep -q "RNBENCH| END" "$out.raw" 2>/dev/null && break
    sleep 2
    waited=$((waited + 2))
  done
  kill $logpid 2>/dev/null

  # Only the app's own lines. `log stream` prefaces every stream with its own banner ("Filtering the
  # log data using ...") which itself contains the string RNBENCH, and prints a termination notice
  # when killed; an unfiltered `sed` copied both into the results file, which is how a file with
  # zero rows still looked like it had captured something. The trailing space in `RNBENCH| ` is what
  # excludes the banner, whose copy of the tag is quoted (`"RNBENCH|"`).
  grep 'RNBENCH| ' "$out.raw" | sed -e 's/.*RNBENCH| //' >> "$out"
  rm -f "$out.raw"
  { echo; echo "load average after: $(uptime | sed -e 's/.*load averages*: *//')"; } | tee -a "$out"
  verify_report "ios" "$out"; local vrc=$?
  echo "written: $out"
  return $vrc
}

case "${1:-host}" in
  host)    cmd_host ;;
  android) cmd_android ;;
  ios)     cmd_ios ;;
  build)
    rc=0
    build_android || rc=1
    build_ios || rc=1
    if [ $rc -eq 0 ]; then echo "both platforms built"; fi
    exit $rc
    ;;
  all)
    # Each platform is attempted regardless of what the previous one did, and the summary says
    # which produced figures. An `exit` in the middle of this used to mean a missing device on
    # Android silently prevented iOS from being attempted at all.
    host_rc=0; android_rc=0; ios_rc=0
    cmd_host    || host_rc=$?
    cmd_android || android_rc=$?
    cmd_ios     || ios_rc=$?
    echo
    echo "=== summary ==="
    st() { [ "$1" = "0" ] && echo "produced figures" || echo "NOT RUN -- see the results file for why"; }
    echo "  host:    $(st $host_rc)"
    echo "  android: $(st $android_rc)"
    echo "  ios:     $(st $ios_rc)"
    echo "  files:   $RESULTS/*-$STAMP.txt"
    [ "$host_rc$android_rc$ios_rc" = "000" ] || echo
    [ "$host_rc$android_rc$ios_rc" = "000" ] || echo "  Not every platform ran. Leave the cells they would have filled as 미측정."
    ;;
  *) die "usage: $0 [host|android|ios|build|all]" ;;
esac
