#!/usr/bin/env python3
"""TypedPython benchmark runner (issue #24). Stdlib only.

    python3 run.py verify
    python3 run.py measure [--repeat N] [--python PY] [--force] [--write]

`verify` runs every implementation at a small size and requires identical output.
`measure` reports the median wall time over N runs at benchmark size. The Java column runs
pre-compiled classes (javac -d java-out), so javac time is excluded; JVM startup is included.
The "TypedPython @compiled" column is a placeholder: that backend does not exist yet.
"""
import argparse
import datetime
import json
import os
import socket
import statistics
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
TARGET = ROOT / "target"
JAVA_OUT = ROOT / "java-out"
CARGO = os.environ.get("CARGO", "/Users/ibrew/.cargo/bin/cargo")
NODE = os.environ.get("NODE", "/Users/ibrew/.local/bin/node")
LOAD_LIMIT = 4.0

# name -> (py file, js file, java class, rust bin, verify size, benchmark size)
BENCHMARKS = {
    "nbody": ("nbody.py", "nbody.mjs", "Nbody", "nbody", "5000", "1000000"),
    "spectral-norm": ("spectral_norm.py", "spectral_norm.mjs", "SpectralNorm", "spectral_norm", "100", "800"),
    "fannkuch-redux": ("fannkuch.py", "fannkuch.mjs", "Fannkuch", "fannkuch", "7", "10"),
    "binary-trees": ("binary_trees.py", "binary_trees.mjs", "BinaryTrees", "binary_trees", "10", "16"),
    "wordfreq": ("wordfreq.py", "wordfreq.mjs", "Wordfreq", "wordfreq", "100000", "3000000"),
}
COLUMNS = ["CPython", "Node", "Java", "Rust", "TypedPython @compiled"]
NOT_BUILT = "n/a (not built)"


def run_checked(cmd: list[str], cwd: Path | None = None, env: dict[str, str] | None = None) -> None:
    result = subprocess.run(cmd, cwd=cwd, env=env, capture_output=True, text=True)
    if result.returncode != 0:
        sys.stderr.write(f"command failed ({result.returncode}): {' '.join(cmd)}\n{result.stdout}{result.stderr}")
        sys.exit(1)


def build() -> None:
    env = dict(os.environ, CARGO_TARGET_DIR=str(TARGET))
    run_checked([CARGO, "build", "--release", "--quiet"], cwd=ROOT / "rust", env=env)
    JAVA_OUT.mkdir(exist_ok=True)
    sources = [str(p) for p in sorted((ROOT / "java").glob("*.java"))]
    run_checked(["javac", "-d", str(JAVA_OUT), *sources])


def commands(name: str, size: str, python: str, java_source: bool) -> dict[str, list[str]]:
    py, js, cls, rs, _, _ = BENCHMARKS[name]
    java = ["java", str(ROOT / "java" / f"{cls}.java"), size] if java_source \
        else ["java", "-cp", str(JAVA_OUT), cls, size]
    return {
        "CPython": [python, str(ROOT / "py" / py), size],
        "Node": [NODE, str(ROOT / "js" / js), size],
        "Java": java,
        "Rust": [str(TARGET / "release" / rs), size],
    }


def run_once(cmd: list[str]) -> tuple[float, str]:
    start = time.perf_counter()
    result = subprocess.run(cmd, capture_output=True, text=True)
    elapsed = time.perf_counter() - start
    if result.returncode != 0:
        sys.stderr.write(f"failed ({result.returncode}): {' '.join(cmd)}\n{result.stderr}")
        sys.exit(1)
    return elapsed, result.stdout.strip()


def verify(args: argparse.Namespace) -> int:
    build()
    bad = 0
    for name, spec in BENCHMARKS.items():
        outputs = {col: run_once(cmd)[1] for col, cmd in commands(name, spec[4], args.python, True).items()}
        distinct = set(outputs.values())
        status = "OK  " if len(distinct) == 1 else "FAIL"
        print(f"{status} {name} (size {spec[4]}): {next(iter(outputs.values())) if len(distinct) == 1 else ''}")
        if len(distinct) != 1:
            bad += 1
            for col, out in outputs.items():
                print(f"       {col}: {out}")
    print("all outputs agree" if bad == 0 else f"{bad} benchmark(s) disagree")
    return 1 if bad else 0


def measure(args: argparse.Namespace) -> int:
    load = os.getloadavg()
    print(f"load average: {load[0]:.2f} {load[1]:.2f} {load[2]:.2f}")
    print(subprocess.run(["uptime"], capture_output=True, text=True).stdout.strip())
    if load[0] > LOAD_LIMIT and not args.force:
        print(f"refusing to measure: 1-minute load {load[0]:.2f} > {LOAD_LIMIT} (use --force to override)")
        return 2
    build()
    results: dict[str, dict[str, object]] = {}
    for name, spec in BENCHMARKS.items():
        size = args.size.get(name, spec[5])
        row: dict[str, object] = {"size": size}
        for col, cmd in commands(name, size, args.python, False).items():
            runs = [run_once(cmd) for _ in range(args.repeat)]
            outs = {out for _, out in runs}
            row[col] = {"median_s": statistics.median(t for t, _ in runs),
                        "runs_s": [t for t, _ in runs], "output": sorted(outs)}
            if len(outs) != 1:
                print(f"warning: {name}/{col} produced differing outputs across runs")
        row["TypedPython @compiled"] = NOT_BUILT
        outs_all = {tuple(row[c]["output"]) for c in COLUMNS[:4]}  # type: ignore[index]
        row["outputs_agree"] = len(outs_all) == 1
        results[name] = row
        print(f"measured {name}", file=sys.stderr)

    print()
    print("| benchmark | size | " + " | ".join(COLUMNS) + " |")
    print("|---|---|" + "---|" * len(COLUMNS))
    for name, row in results.items():
        cells = [f"{row[c]['median_s']:.3f} s" for c in COLUMNS[:4]]  # type: ignore[index]
        flag = "" if row["outputs_agree"] else " (OUTPUTS DIFFER)"
        print(f"| {name}{flag} | {row['size']} | " + " | ".join(cells + [NOT_BUILT]) + " |")
    print(f"\nmedian of {args.repeat} runs, wall time; Java excludes javac, includes JVM startup.")

    if args.write:
        out_dir = ROOT / "results"
        out_dir.mkdir(exist_ok=True)
        path = out_dir / f"{datetime.date.today().isoformat()}-{socket.gethostname()}.json"
        payload = {"date": datetime.datetime.now().isoformat(timespec="seconds"),
                   "host": socket.gethostname(), "load_average": load, "repeat": args.repeat,
                   "python": subprocess.run([args.python, "--version"], capture_output=True, text=True).stdout.strip(),
                   "results": results}
        path.write_text(json.dumps(payload, indent=2) + "\n")
        print(f"wrote {path}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)
    v = sub.add_parser("verify")
    v.add_argument("--python", default=sys.executable)
    m = sub.add_parser("measure")
    m.add_argument("--python", default=sys.executable)
    m.add_argument("--repeat", type=int, default=5)
    m.add_argument("--force", action="store_true")
    m.add_argument("--write", action="store_true")
    m.add_argument("--size", action="append", default=[], metavar="NAME=N",
                   help="override a benchmark size (for runner smoke tests only)")
    args = parser.parse_args()
    if args.cmd == "measure":
        args.size = dict(s.split("=", 1) for s in args.size)
    return verify(args) if args.cmd == "verify" else measure(args)


if __name__ == "__main__":
    sys.exit(main())
