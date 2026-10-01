#!/usr/bin/env bash
# Sync the release branch from a source ref (default: develop).
#
# release tree = source tree minus Markdown files that must not reach main:
#   (a) every *.md at the repository root except README.md
#   (b) every *.md directly inside docs/  (docs/<sub>/... is untouched)
# Everything else is kept. One linear commit per sync; no-op if tree unchanged.
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
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
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

# Current release head: local, else origin's.
base=""
if base="$(git rev-parse -q --verify "refs/heads/$TARGET^{commit}" 2>/dev/null)"; then :
elif base="$(git rev-parse -q --verify "refs/remotes/origin/$TARGET^{commit}" 2>/dev/null)"; then :
else base=""; fi

if [ -z "$base" ]; then
  if [ "$new_tree" = "$(git rev-parse "$src_commit^{tree}")" ]; then
    new_commit="$src_commit"
  else
    new_commit="$(git commit-tree "$new_tree" -p "$src_commit" -m "Release: sync from $SOURCE $short")"
  fi
  git update-ref "refs/heads/$TARGET" "$new_commit"
  echo "created $TARGET at $(git rev-parse --short "$new_commit")"
else
  if [ "$new_tree" = "$(git rev-parse "$base^{tree}")" ]; then
    echo "no changes: $TARGET already up to date"
    new_commit="$base"
    # make sure the local branch exists when only origin had it
    git rev-parse -q --verify "refs/heads/$TARGET" >/dev/null || git update-ref "refs/heads/$TARGET" "$base"
  else
    new_commit="$(git commit-tree "$new_tree" -p "$base" -m "Release: sync from $SOURCE $short")"
    git update-ref "refs/heads/$TARGET" "$new_commit"
    echo "$TARGET -> $(git rev-parse --short "$new_commit")"
  fi
fi

if [ "$PUSH" -eq 1 ]; then
  git push origin "refs/heads/$TARGET:refs/heads/$TARGET"
fi
