"""Split the TypedPython test files into shards for a CI job (windows: C compilation makes the whole
suite take 40 minutes in one job, CI run 43798264662).

    shard.py --shard all      prints nothing: pytest collects its configured test paths
    shard.py --shard 1/2      prints the test files of shard 1 of 2, one per line, relative to the
                              project directory (python-multiplatform-ksp)
    shard.py --check 2        only checks the split into 2 shards

Every call for a real shard first checks the whole split: the shards are pairwise disjoint and their
union is exactly the set of test files pytest would find by name (test_*.py and *_test.py under
src/test/python). A file that belonged to no shard, or to two, ends the job with an error instead of
silently not running. Files are dealt out in sorted order, so adding a test file moves later files to
the other shard but can never drop one."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[3] / "python-multiplatform-ksp"
TESTS = PROJECT / "src" / "test" / "python"


def test_files() -> list[str]:
    found = set(TESTS.rglob("test_*.py")) | set(TESTS.rglob("*_test.py"))
    return sorted(p.relative_to(PROJECT).as_posix() for p in found)


def shard(files: list[str], k: int, n: int) -> list[str]:
    """Shard k (1-based) of n."""
    return [f for i, f in enumerate(files) if i % n == k - 1]


def check(n: int) -> None:
    files = test_files()
    if not files:
        raise SystemExit(f"shard.py: no test files under {TESTS}")
    parts = [shard(files, k, n) for k in range(1, n + 1)]
    seen: dict[str, int] = {}
    for k, part in enumerate(parts, 1):
        for f in part:
            if f in seen:
                raise SystemExit(f"shard.py: {f} is in shard {seen[f]} and in shard {k}")
            seen[f] = k
    missing = sorted(set(files) - set(seen))
    extra = sorted(set(seen) - set(files))
    if missing or extra:
        raise SystemExit(f"shard.py: the shards are not the test files: missing {missing}, extra {extra}")
    if sum(len(p) for p in parts) != len(files):
        raise SystemExit("shard.py: the shard sizes do not add up to the number of test files")
    if any(not p for p in parts):
        raise SystemExit(f"shard.py: an empty shard among {n} for {len(files)} files")
    print(f"shard.py: {len(files)} test files in {n} disjoint shards of sizes {[len(p) for p in parts]}",
          file=sys.stderr)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(prog="shard.py")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--shard", help="'all', or K/N for shard K of N")
    group.add_argument("--check", type=int, metavar="N", help="check the split into N shards")
    args = parser.parse_args(argv)
    if args.check is not None:
        check(args.check)
        return 0
    if args.shard == "all":
        return 0
    try:
        k, n = (int(x) for x in args.shard.split("/"))
    except ValueError:
        raise SystemExit(f"shard.py: --shard must be 'all' or K/N, got {args.shard!r}") from None
    if not 1 <= k <= n:
        raise SystemExit(f"shard.py: shard {k} of {n} does not exist")
    check(n)
    print("\n".join(shard(test_files(), k, n)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
