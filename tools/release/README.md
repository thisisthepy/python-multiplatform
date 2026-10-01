# Release flow

    develop --push--> release (auto) --PR--> main

1. Every push to `develop` triggers `.github/workflows/release-sync.yml`, which runs
   `tools/release/sync-release.sh --push`. It adds one commit
   (`Release: sync from develop <sha>`) to `release` whose tree is the develop tree
   minus the files below. If nothing changed, no commit is made.
2. The workflow opens (or updates) a pull request `release` -> `main`.
3. Merging that PR is the only way to change `main`. `main-source-guard.yml` fails
   any PR into `main` whose head is not `release`.
4. On push to `main`, `pages.yml` deploys `docs/guide/` to GitHub Pages.

## What is dropped on release

- every `*.md` at the repository root except `README.md` (including `CLAUDE.md`, `AGENTS.md`)
- every `*.md` directly inside `docs/`

Kept: everything else, including Markdown in `docs/<subfolder>/` and nested `README.md` files.
Only Markdown is ever dropped.

## Local use

    tools/release/sync-release.sh --dry-run     # list paths that would be dropped
    tools/release/sync-release.sh               # update local release branch
    tools/release/sync-release.sh --push        # also push to origin
    tools/release/test-sync-release.sh          # tests (throwaway repo in .tmp/)

The script uses plumbing and a private index, so your working tree, index and HEAD are untouched.
It refuses to run while `release` is checked out.

## Protecting main

    tools/release/protect-main.sh               # print what would be applied
    tools/release/protect-main.sh --apply       # apply via gh api (needs admin)

This requires a pull request, blocks force-push and deletion. Classic protection cannot
restrict a PR's source branch, so add the check `only-release-into-main` as required
(the script does; the workflow must have run once first).
