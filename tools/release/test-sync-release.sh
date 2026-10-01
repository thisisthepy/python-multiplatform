#!/usr/bin/env bash
# Tests for sync-release.sh. Builds a throwaway repo under <repo>/.tmp/.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SYNC="$HERE/sync-release.sh"
SCRATCH="$ROOT/.tmp/release-sync-test.$$"
FAILS=0; PASSES=0
cleanup() { rm -rf "$SCRATCH"; }
trap cleanup EXIT
mkdir -p "$SCRATCH"

ok()   { PASSES=$((PASSES+1)); echo "ok   - $1"; }
fail() { FAILS=$((FAILS+1)); echo "FAIL - $1"; }
check() { # desc, command...
  local d="$1"; shift
  if "$@" >/dev/null 2>&1; then ok "$d"; else fail "$d"; fi
}
in_rel() { git cat-file -e "release:$1" 2>/dev/null; }

R="$SCRATCH/repo"
mkdir -p "$R" && cd "$R" || exit 1
git init -q -b develop .
git config user.name t; git config user.email t@example.com
mkdir -p docs/guide docs/sub sub/deeper
for f in README.md CLAUDE.md AGENTS.md ROADMAP.md docs/a.md docs/b.md \
         docs/sub/c.md docs/guide/index.html docs/x.png sub/README.md \
         sub/deeper/notes.md build.gradle.kts LICENSE; do
  echo "$f" > "$f"
done
git add -A && git commit -q -m init
echo dirty > README.md   # unstaged change
echo staged > STAGED.txt; git add STAGED.txt
before_status="$(git status --porcelain)"
before_head="$(git rev-parse HEAD)"

if [ ! -x "$SYNC" ] && [ ! -f "$SYNC" ]; then
  fail "sync-release.sh exists"; echo "$PASSES passed, $FAILS failed"; exit 1
fi

out="$(bash "$SYNC" --dry-run 2>&1)"; rc=$?
check "dry-run exits 0" test $rc -eq 0
echo "$out" | grep -qx "ROADMAP.md" && ok "dry-run lists ROADMAP.md" || fail "dry-run lists ROADMAP.md"
check "dry-run creates no release" bash -c '! git rev-parse -q --verify refs/heads/release'

bash "$SYNC" >"$SCRATCH/run1.log" 2>&1; rc=$?
check "first run exits 0 (release created)" test $rc -eq 0
check "release branch exists" git rev-parse -q --verify refs/heads/release

for p in README.md docs/sub/c.md docs/guide/index.html docs/x.png sub/README.md \
         sub/deeper/notes.md build.gradle.kts LICENSE; do
  check "kept: $p" in_rel "$p"
done
for p in CLAUDE.md AGENTS.md ROADMAP.md docs/a.md docs/b.md; do
  check "dropped: $p" bash -c "! git cat-file -e release:$p"
done
check "release README is the committed one" test "$(git show release:README.md)" = "README.md"

check "caller HEAD unchanged" test "$(git rev-parse HEAD)" = "$before_head"
check "caller branch still develop" test "$(git symbolic-ref --short HEAD)" = develop
check "caller status unchanged" test "$(git status --porcelain)" = "$before_status"
check "caller working file kept" test "$(cat ROADMAP.md)" = "ROADMAP.md"

h1="$(git rev-parse release)"
bash "$SYNC" >"$SCRATCH/run2.log" 2>&1
check "second run is a no-op" test "$(git rev-parse release)" = "$h1"

# New develop commit -> exactly one new linear commit
echo more >> build.gradle.kts; echo "new" > NEW.md
git add build.gradle.kts NEW.md
git commit -q -m "more"; git reset -q   # leave index sane
bash "$SYNC" >"$SCRATCH/run3.log" 2>&1
check "third run adds one commit" test "$(git rev-list --count "$h1..release")" = 1
check "new commit parent is previous head" test "$(git rev-parse release^)" = "$h1"
check "message format" bash -c 'git log -1 --format=%s release | grep -Eq "^Release: sync from develop [0-9a-f]{7,}$"'
check "new root md dropped" bash -c '! git cat-file -e release:NEW.md'
check "new change propagated" bash -c 'git show release:build.gradle.kts | grep -q more'

# Docs-only md change -> no-op
h3="$(git rev-parse release)"
echo edit >> ROADMAP.md; git add ROADMAP.md; git commit -q -m "docs only"
bash "$SYNC" >"$SCRATCH/run4.log" 2>&1
check "dropped-file-only change is no-op" test "$(git rev-parse release)" = "$h3"

# Refuses when target is checked out
git checkout -q release 2>/dev/null
bash "$SYNC" >/dev/null 2>&1; rc=$?
check "refuses when release is checked out" test $rc -ne 0
git checkout -q develop

echo "$PASSES passed, $FAILS failed"
[ "$FAILS" -eq 0 ]
