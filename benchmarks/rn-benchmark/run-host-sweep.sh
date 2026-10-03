#!/bin/bash
# The host-side half of the benchmark: JS-only rows and the warmup convergence sweep, run under
# BOTH the standalone Hermes VM and Node/V8 from the same source files.
#
#   ./run-host-sweep.sh                  both engines
#   ./run-host-sweep.sh hermes           Hermes only
#   ./run-host-sweep.sh node             Node only
#
# No device, no emulator and no simulator is involved, so this is safe to run at any time. It
# measures no boundary -- see bench/host-sweep.js for what it does settle.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/env.sh"

BENCH="$HERE/RNBench/bench"
HERMES="$HERE/tools/node_modules/hermes-engine-cli/osx-bin/hermes"
WHICH="${1:-both}"

run_hermes() {
  if [ ! -x "$HERMES" ]; then
    echo "no standalone Hermes at $HERMES -- run: (cd $HERE/tools && npm install)" >&2
    return 1
  fi
  # Standalone Hermes has no module system, so the three files are concatenated in load order.
  # The UMD wrapper in each of them falls back to assigning a global when `module` is absent.
  local tmp
  tmp="$(mktemp -t rnbench-hermes)".js
  cat "$BENCH/harness.js" "$BENCH/jsRows.js" "$BENCH/host-sweep.js" > "$tmp"
  "$HERMES" "$tmp"
  local rc=$?
  rm -f "$tmp"
  return $rc
}

run_node() {
  node "$BENCH/host-sweep.js"
}

rc=0
case "$WHICH" in
  hermes) run_hermes || rc=$? ;;
  node)   run_node   || rc=$? ;;
  both)
    run_hermes || rc=$?
    run_node   || rc=$?
    ;;
  *)
    echo "usage: $0 [hermes|node|both]" >&2
    rc=2
    ;;
esac
exit $rc
