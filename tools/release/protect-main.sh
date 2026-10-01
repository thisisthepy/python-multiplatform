#!/usr/bin/env bash
# Print (default) or apply (--apply) branch protection for main.
# Usage: protect-main.sh [--apply] [--repo OWNER/NAME] [--branch main]
set -eu
APPLY=0; REPO=""; BRANCH=main
while [ $# -gt 0 ]; do
  case "$1" in
    --apply) APPLY=1; shift ;;
    --repo) REPO="$2"; shift 2 ;;
    --branch) BRANCH="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$REPO" ] || REPO="{owner}/{repo}"   # gh expands these placeholders from the current repo

BODY='{
  "required_status_checks": {"strict": true, "contexts": ["only-release-into-main"]},
  "enforce_admins": true,
  "required_pull_request_reviews": {"required_approving_review_count": 0},
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false
}'
CMD="gh api --method PUT repos/$REPO/branches/$BRANCH/protection --input -"

if [ "$APPLY" -eq 1 ]; then
  printf '%s' "$BODY" | gh api --method PUT "repos/$REPO/branches/$BRANCH/protection" --input -
  echo "applied protection to $BRANCH"
else
  echo "[dry run] would run: $CMD"
  echo "$BODY"
  echo "Re-run with --apply to apply. The check 'only-release-into-main' comes from .github/workflows/main-source-guard.yml;"
  echo "it must have run once before GitHub accepts it as a required check."
fi
