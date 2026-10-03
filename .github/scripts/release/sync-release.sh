#!/usr/bin/env bash
# Regenerate the release branch from a source ref (default: develop).
# release is CI-generated and disposable: exactly one commit on top of the
# source commit, rebuilt every run; existing release branches are ignored.
#
# release tree = source tree minus Markdown files that must not reach main:
#   (a) every *.md at the repository root except README.md
#   (b) every *.md directly inside docs/  (docs/<sub>/... is untouched)
# Everything else is kept. Always one generated commit (parent = source);
# no-op only if target already has that tree and that parent.
# Uses plumbing + a private index file: the caller's working tree, index and
# HEAD are never touched.
#
# Usage: sync-release.sh [--source REF] [--target BRANCH] [--push] [--dry-run]
set -eu

SOURCE=develop
TARGET=release
PUSH=0
DRY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --source) SOURCE="$2"; shift 2 ;;
    --target) TARGET="$2"; shift 2 ;;
    --push)   PUSH=1; shift ;;
    --dry-run) DRY=1; shift ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

die() { echo "sync-release: $*" >&2; exit 1; }

# Source: local ref, else origin's.
if src_commit="$(git rev-parse -q --verify "$SOURCE^{commit}" 2>/dev/null)"; then :
elif src_commit="$(git rev-parse -q --verify "refs/remotes/origin/$SOURCE^{commit}" 2>/dev/null)"; then :
else die "cannot resolve source ref '$SOURCE'"; fi
short="$(git rev-parse --short=7 "$src_commit")"

# Paths to drop (NUL-safe list in a file, newline listing for humans).
GITDIR="$(git rev-parse --absolute-git-dir)"
WORK="$GITDIR/sync-release.$$"
mkdir -p "$WORK"
trap 'rm -rf "$WORK"' EXIT

git ls-tree -r -z --name-only "$src_commit" | while IFS= read -r -d '' p; do
  case "$p" in
    README.md) ;;
    */*)
      case "$p" in
        docs/*/*) ;;           # inside a docs subdirectory: keep
        docs/*.md) printf '%s\0' "$p" ;;
      esac ;;
    *.md) printf '%s\0' "$p" ;;
  esac
done > "$WORK/drop.z"

if [ "$DRY" -eq 1 ]; then
  tr '\0' '\n' < "$WORK/drop.z"
  exit 0
fi

# Filtered tree through a private index.
export GIT_INDEX_FILE="$WORK/index"
git read-tree "$src_commit"
git update-index --force-remove -z --stdin < "$WORK/drop.z"
new_tree="$(git write-tree)"
unset GIT_INDEX_FILE

# Refuse to move a branch that is checked out in this worktree.
if [ "$(git symbolic-ref -q HEAD || true)" = "refs/heads/$TARGET" ]; then
  die "'$TARGET' is checked out here; switch away first"
fi

# release is a generated, disposable branch: always exactly one commit on top of
# the source commit. Any existing local/remote release (possibly stale, from an
# older workflow) is NOT used as a base; it is only compared for the no-op case.
old_local=""
old_local="$(git rev-parse -q --verify "refs/heads/$TARGET^{commit}" 2>/dev/null)" || old_local=""

# main may hold commits develop lacks (earlier release merges, hotfixes). Then
# release also takes main as a second parent: the tree stays the filtered source
# tree, and main can always fast-forward to release -- the PR never conflicts.
main_commit=""
for ref in refs/remotes/origin/main refs/heads/main; do
  main_commit="$(git rev-parse -q --verify "$ref^{commit}" 2>/dev/null)" && break
  main_commit=""
done
parents="$src_commit"
if [ -n "$main_commit" ] && ! git merge-base --is-ancestor "$main_commit" "$src_commit"; then
  parents="$src_commit $main_commit"
fi

# No-op only if the existing target has exactly this tree AND these parents.
if [ -n "$old_local" ] \
   && [ "$(git rev-parse "$old_local^{tree}")" = "$new_tree" ] \
   && [ "$(git rev-list --parents -n 1 "$old_local" | cut -d' ' -f2-)" = "$parents" ]; then
  echo "no changes: $TARGET already regenerated from $short"
  new_commit="$old_local"
else
  pargs=""; for p in $parents; do pargs="$pargs -p $p"; done
  new_commit="$(git commit-tree "$new_tree" $pargs -m "Release: sync from $SOURCE $short")"
  git update-ref "refs/heads/$TARGET" "$new_commit"
  echo "$TARGET -> $(git rev-parse --short "$new_commit") (parent $short)"
fi

if [ "$PUSH" -eq 1 ]; then
  # release is rewritten from scratch on every run, so a plain push would be
  # rejected as non-fast-forward. --force-with-lease pinned to the remote sha we
  # observed overwrites only that exact state: if someone else moved release in
  # the meantime we fail instead of clobbering. Without a remote release there
  # is nothing to lose, so an empty expectation (must not exist) is used.
  old_remote="$(git ls-remote origin "refs/heads/$TARGET" | cut -f1)"
  git push --force-with-lease="refs/heads/$TARGET:$old_remote" origin "$new_commit:refs/heads/$TARGET"
fi
