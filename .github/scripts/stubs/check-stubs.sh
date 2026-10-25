#!/usr/bin/env bash
# Type-checks .github/scripts/stubs/consumer.py against a directory of generated Kotlin-name stubs (issue #31).
#
#   .github/scripts/stubs/check-stubs.sh <stub-dir> [venv-dir]
#
# <stub-dir> is what `generatePythonStubs` wrote, e.g.
#   python-multiplatform-compose/fixtures/compose/build/generated/pythonStubs/desktopMain
# The venv defaults to <repo>/.tmp/stubs-venv (git-ignored) and is created on first use.
set -euo pipefail

root="$(cd "$(dirname "$0")/../../.." && pwd)"
stubs="$(cd "${1:?usage: check-stubs.sh <stub-dir> [venv-dir]}" && pwd)"
venv="${2:-$root/.tmp/stubs-venv}"

if [ ! -x "$venv/bin/mypy" ]; then
  python3 -m venv "$venv"
  "$venv/bin/pip" install --quiet mypy
fi

# .github/scripts/stubs/mypy.ini: `strict`, and `warn_unused_ignores` for the consumer so that the lines it
# expects to be rejected fail the check when they stop being. Errors inside the stubs themselves are
# reported too: MYPYPATH modules are checked, not silenced.
MYPYPATH="$stubs" "$venv/bin/mypy" \
  --config-file "$root/.github/scripts/stubs/mypy.ini" \
  --cache-dir "$root/.tmp/mypy-cache" \
  "$root/.github/scripts/stubs/consumer.py"
