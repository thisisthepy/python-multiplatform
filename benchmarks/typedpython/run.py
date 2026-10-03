#!/usr/bin/env python3
"""TypedPython benchmark runner (issue #24). Stdlib only.

    python3 run.py verify
    python3 run.py measure [--repeat N] [--python PY] [--force] [--write]

`verify` runs every implementation at a small size and requires identical output.
`measure` reports the median wall time over N runs at benchmark size. The Java column runs
pre-compiled classes (javac -d java-out), so javac time is excluded; JVM startup is included.
The "TypedPython @compiled" column runs only with `--typedpython <dir>` (the directory that holds
the `typedpython` package, python-multiplatform-ksp/src/main/python) and `--typedpython-python` (an
interpreter with the gate's dependencies, e.g. that module's .venv): each benchmark is copied with
the `# typedpython: compiled` marker, compiled through `typedpython.pipeline`, and its `main()` is
run from the built extension. Without the option the column reports n/a.
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
TP_BUILD = ROOT / "tp-build"
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


COMPILE_SNIPPET = """
import json, sys
from pathlib import Path
from typedpython import pipeline
r = pipeline.compile_module(Path(sys.argv[1]), Path(sys.argv[2]))
print(json.dumps({"extension": str(r.extension) if r.extension else None,
                  "compiled": [f.name for f in r.module.functions], "skipped": r.skipped}))
"""

RUN_SNIPPET = """
import importlib.machinery, importlib.util, sys
name, path = sys.argv[1], sys.argv[2]
loader = importlib.machinery.ExtensionFileLoader(name, path)
spec = importlib.util.spec_from_file_location(name, path, loader=loader)
mod = importlib.util.module_from_spec(spec)
loader.exec_module(mod)
sys.argv = [name] + sys.argv[3:]
mod.main()
"""


def build_compiled(tp_dir: Path, tp_python: str) -> dict[str, tuple[str, list[str]] | str]:
    """Compile each benchmark with TypedPython; name -> (extension, compiled functions) or a reason."""
    built: dict[str, tuple[str, list[str]] | str] = {}
    env = dict(os.environ, PYTHONPATH=str(tp_dir))
    for name, (py, *_rest) in BENCHMARKS.items():
        work = TP_BUILD / name
        work.mkdir(parents=True, exist_ok=True)
        source = work / py
        source.write_text("# typedpython: compiled\n" + (ROOT / "py" / py).read_text())
        result = subprocess.run([tp_python, "-c", COMPILE_SNIPPET, str(source), str(work / "out")],
                                capture_output=True, text=True, env=env)
        if result.returncode != 0:
            built[name] = f"n/a (compile failed: {result.stderr.strip().splitlines()[-1:]})"
            continue
        info = json.loads(result.stdout.strip().splitlines()[-1])
        built[name] = (info["extension"], info["compiled"]) if info["extension"] else "n/a (nothing compiled)"
    return built


def commands(name: str, size: str, python: str, java_source: bool,
             compiled: dict[str, tuple[str, list[str]] | str] | None = None) -> dict[str, list[str]]:
    py, js, cls, rs, _, _ = BENCHMARKS[name]
    java = ["java", str(ROOT / "java" / f"{cls}.java"), size] if java_source \
        else ["java", "-cp", str(JAVA_OUT), cls, size]
    cmds = {
        "CPython": [python, str(ROOT / "py" / py), size],
        "Node": [NODE, str(ROOT / "js" / js), size],
        "Java": java,
        "Rust": [str(TARGET / "release" / rs), size],
    }
    entry = (compiled or {}).get(name)
    if isinstance(entry, tuple):
        cmds["TypedPython @compiled"] = [python, "-c", RUN_SNIPPET, Path(py).stem, entry[0], size]
    return cmds


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
    compiled = build_compiled(Path(args.typedpython), args.typedpython_python) if args.typedpython else None
    bad = 0
    for name, spec in BENCHMARKS.items():
        outputs = {col: run_once(cmd)[1]
                   for col, cmd in commands(name, spec[4], args.python, True, compiled).items()}
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
    compiled = build_compiled(Path(args.typedpython), args.typedpython_python) if args.typedpython else None
    results: dict[str, dict[str, object]] = {}
    for name, spec in BENCHMARKS.items():
        size = args.size.get(name, spec[5])
        row: dict[str, object] = {"size": size}
        entry = (compiled or {}).get(name, NOT_BUILT)
        if isinstance(entry, tuple):
            row["compiled_functions"] = entry[1]
        for col, cmd in commands(name, size, args.python, False, compiled).items():
            runs = [run_once(cmd) for _ in range(args.repeat)]
            outs = {out for _, out in runs}
            row[col] = {"median_s": statistics.median(t for t, _ in runs),
                        "runs_s": [t for t, _ in runs], "output": sorted(outs)}
            if len(outs) != 1:
                print(f"warning: {name}/{col} produced differing outputs across runs")
        if not isinstance(entry, tuple):
            row["TypedPython @compiled"] = entry
        measured = [c for c in COLUMNS if isinstance(row.get(c), dict)]
        outs_all = {tuple(row[c]["output"]) for c in measured}  # type: ignore[index]
        row["outputs_agree"] = len(outs_all) == 1
        results[name] = row
        print(f"measured {name}", file=sys.stderr)

    print()
    print("| benchmark | size | " + " | ".join(COLUMNS) + " |")
    print("|---|---|" + "---|" * len(COLUMNS))
    for name, row in results.items():
        cells = [f"{row[c]['median_s']:.3f} s" if isinstance(row.get(c), dict) else str(row.get(c, NOT_BUILT))
                 for c in COLUMNS]  # type: ignore[index]
        flag = "" if row["outputs_agree"] else " (OUTPUTS DIFFER)"
        print(f"| {name}{flag} | {row['size']} | " + " | ".join(cells) + " |")
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
    for p in (v, m):
        p.add_argument("--typedpython", metavar="DIR",
                       help="directory holding the typedpython package; enables the @compiled column")
        p.add_argument("--typedpython-python", default=sys.executable,
                       help="interpreter for compiling (needs pyrefly 1.3.2)")
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
