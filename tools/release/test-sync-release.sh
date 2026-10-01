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

# A stale release (unrelated history, old content), both local and as a
# remote-tracking ref, must be ignored and overwritten.
stale_tree="$(git hash-object -w -t tree /dev/null)"
stale="$(git commit-tree "$stale_tree" -m "stale 2025 release")"
git update-ref refs/heads/release "$stale"
git update-ref refs/remotes/origin/release "$stale"

out="$(bash "$SYNC" --dry-run 2>&1)"; rc=$?
check "dry-run exits 0" test $rc -eq 0
echo "$out" | grep -qx "ROADMAP.md" && ok "dry-run lists ROADMAP.md" || fail "dry-run lists ROADMAP.md"
check "dry-run leaves stale release alone" test "$(git rev-parse refs/heads/release)" = "$stale"

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

check "stale release overwritten: parent is source" test "$(git rev-parse release^)" = "$before_head"
check "stale release not an ancestor" bash -c '! git merge-base --is-ancestor '"$stale"' release'
check "exactly one commit over source" test "$(git rev-list --count "$before_head..release")" = 1
check "message format" bash -c 'git log -1 --format=%s release | grep -Eq "^Release: sync from develop [0-9a-f]{7,}$"'

h1="$(git rev-parse release)"
bash "$SYNC" >"$SCRATCH/run2.log" 2>&1
check "second run is a no-op" test "$(git rev-parse release)" = "$h1"

# New develop commit -> release regenerated: still one commit on top of the NEW source
echo more >> build.gradle.kts; echo "new" > NEW.md
git add build.gradle.kts NEW.md
git commit -q -m "more"; git reset -q   # leave index sane
d2="$(git rev-parse develop)"
bash "$SYNC" >"$SCRATCH/run3.log" 2>&1
check "regenerated: parent is new source" test "$(git rev-parse release^)" = "$d2"
check "regenerated: one commit over source" test "$(git rev-list --count "$d2..release")" = 1
check "regenerated: old release not an ancestor" bash -c '! git merge-base --is-ancestor '"$h1"' release'
check "new root md dropped" bash -c '! git cat-file -e release:NEW.md'
check "new change propagated" bash -c 'git show release:build.gradle.kts | grep -q more'
h3="$(git rev-parse release)"
bash "$SYNC" >"$SCRATCH/run3b.log" 2>&1
check "no-op again after regeneration" test "$(git rev-parse release)" = "$h3"

# Docs-only md change -> new source commit, so release is regenerated (parent moves)
echo edit >> ROADMAP.md; git add ROADMAP.md; git commit -q -m "docs only"
d3="$(git rev-parse develop)"
bash "$SYNC" >"$SCRATCH/run4.log" 2>&1
check "dropped-file-only change still regenerates onto new source" test "$(git rev-parse release^)" = "$d3"
check "tree unchanged by dropped-only change" test "$(git rev-parse release^{tree})" = "$(git rev-parse "$h3^{tree}")"

# Nothing to drop: still one distinct generated commit on top of source
R2="$SCRATCH/repo2"; git init -q -b develop "$R2"
( cd "$R2" && git config user.name t && git config user.email t@example.com \
  && echo r > README.md && echo k > f.txt && git add -A && git commit -q -m i \
  && s2="$(git rev-parse HEAD)" && bash "$SYNC" >/dev/null 2>&1 \
  && test "$(git rev-parse release^)" = "$s2" && test "$(git rev-parse release)" != "$s2" \
  && test "$(git rev-parse release^{tree})" = "$(git rev-parse "$s2^{tree}")" ) \
  && ok "nothing dropped: still one generated commit on top of source" \
  || fail "nothing dropped: still one generated commit on top of source"

# Created when missing
git branch -D release -q
bash "$SYNC" >"$SCRATCH/run5.log" 2>&1
check "created when missing, parent is source" test "$(git rev-parse release^)" = "$d3"

check "caller HEAD unchanged at end" test "$(git rev-parse HEAD)" = "$d3"

# --push: creates remote release, then overwrites it after a new source commit
git init -q --bare "$SCRATCH/remote.git"
git remote add origin "$SCRATCH/remote.git"
git branch -D release -q 2>/dev/null
bash "$SYNC" --push >"$SCRATCH/push1.log" 2>&1
check "push creates remote release" test "$(git --git-dir="$SCRATCH/remote.git" rev-parse refs/heads/release)" = "$(git rev-parse release)"
echo again >> LICENSE; git add LICENSE; git commit -q -m again; git reset -q
bash "$SYNC" --push >"$SCRATCH/push2.log" 2>&1
check "push force-overwrites regenerated release" test "$(git --git-dir="$SCRATCH/remote.git" rev-parse refs/heads/release)" = "$(git rev-parse release)"
git push -q -f origin "$stale:refs/heads/release" 2>/dev/null
git update-ref refs/remotes/origin/release "$stale"
bash "$SYNC" --push >"$SCRATCH/push3.log" 2>&1
check "push overwrites stale remote release" test "$(git --git-dir="$SCRATCH/remote.git" rev-parse refs/heads/release)" = "$(git rev-parse release)"

# Refuses when target is checked out
git checkout -q release 2>/dev/null
bash "$SYNC" >/dev/null 2>&1; rc=$?
check "refuses when release is checked out" test $rc -ne 0
git checkout -q develop

echo "$PASSES passed, $FAILS failed"
[ "$FAILS" -eq 0 ]
