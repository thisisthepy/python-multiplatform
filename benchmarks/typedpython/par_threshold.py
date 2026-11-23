#!/usr/bin/env python3
"""The N-15 parallel-loop thresholds, measured (#278, design typedpython-parallel-loops.md item 12 and decision 7).
`TP_PAR_MIN_WORK` (65536) and `TP_PAR_MIN_CHUNK` (1024) in tp_runtime.h are placeholders, and the thread cap (8)
was never measured. This script measures them on the Mac mini and on an arm64 Android device, one run each, by the
coordinating session, alone, at a load below 1 (AGENTS.md rule 8). Stdlib only.

Method. Two `@compiled(parallel=True)` map loops over `list[float]`, one compute-bound (sqrt, sin, cos per element)
and one bandwidth-bound (`a[i] = a[i] + b[i]`), are built ONCE with `-DTP_PAR_MIN_WORK=0 -DTP_PAR_MIN_CHUNK=1`, so a
loop runs on the threads whenever there is more than one. The same binary runs in one process per thread count
(`TYPEDPYTHON_THREADS` is read once at start): 1 is the sequential baseline, then 2, 4, ... up to the cores. Every
trip count of a geometric series (2**4 .. 2**22) is timed (calls repeated until a sample takes 20 ms, median of 7
samples). From the table:
  * crossover n*(kernel, T): the smallest trip count from which every larger one is faster on T threads than on 1;
  * TP_PAR_MIN_WORK candidate = n* x the loop's weight (ParInfo.weight, the unit the gate in cgen uses), at the
    default thread count; the larger of the two kernels is the conservative choice;
  * TP_PAR_MIN_CHUNK candidate = n* / T of the compute kernel (the fewest iterations worth a thread);
  * thread cap: the smallest T after which one more step of threads gains less than 5 % at the largest n;
  * start/join cost: (time on T threads - time on 1 thread) at the smallest trip count.
The script prints these; recording them in tp_runtime.h and the design is a PR, and the choice is the leader's.

Desktop (the Mac mini):

    uptime
    python3 benchmarks/typedpython/par_threshold.py host \\
        --typedpython python-multiplatform-ksp/src/main/python \\
        --python python-multiplatform-ksp/.venv/bin/python \\
        --out benchmarks/typedpython/results/par-threshold-macmini.json

Android (arm64 device, one at a time; the project's 3.15t build, release `cpython-3.15.0t-mobile`):

    python3 benchmarks/typedpython/par_threshold.py android-kit \\
        --typedpython python-multiplatform-ksp/src/main/python \\
        --python python-multiplatform-ksp/.venv/bin/python \\
        --prefix <unpacked python-3.15.0t-aarch64-linux-android>/prefix \\
        --ndk $ANDROID_HOME/ndk/27.3.13750724 --kit .cache/par-kit
    sh .cache/par-kit/run-on-device.sh          # builds with the NDK, pushes, runs every thread count, pulls
    python3 benchmarks/typedpython/par_threshold.py analyze .cache/par-kit/results/*.jsonl \\
        --out benchmarks/typedpython/results/par-threshold-android-arm64.json

`android-kit` writes the generated C of the loops, the driver, a 10-line launcher (`Py_BytesMain`: the Android
package ships libpython3.15t.so but no interpreter executable) and the shell script; it runs nothing on a device.
"""
from __future__ import annotations

import argparse
import datetime
import json
import os
import platform
import shlex
import statistics
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
FLAGS = ["-DTP_PAR_MIN_WORK=0", "-DTP_PAR_MIN_CHUNK=1u"]
SIZES = [2 ** k for k in range(4, 23)]

# The shapes N-15 step 1 runs on threads (test_parallel_loops.py): `for i in range(len(xs))` storing `xs[i]` from
# + - * of xs[i] and loop-invariant floats. compute: a degree-8 polynomial (Horner, 16 flops per element);
# stream: one add per element (a load and a store, memory-bound).
KERNELS = """
@compiled(parallel=True)
def compute(xs: list[float], a: float) -> None:
    for i in range(len(xs)):
        xs[i] = ((((((((xs[i] * a + 0.5) * xs[i] - 0.25) * xs[i] + 0.125) * xs[i] - 0.0625) * xs[i] + 0.03125)
                    * xs[i] - 0.015625) * xs[i] + 0.0078125) * xs[i] - 0.00390625)


@compiled(parallel=True)
def stream(xs: list[float], a: float) -> None:
    for i in range(len(xs)):
        xs[i] = xs[i] + a
"""

# Runs in the build interpreter (the typedpython checkout on its path): the C of the kernels, the weight of each
# parallel loop, and (host only) the built extension.
BUILD = """
import json, sys
from pathlib import Path
from typedpython import cbuild, cgen, frontend, ir, verify
src, out, flags, build = Path(sys.argv[1]), Path(sys.argv[2]), json.loads(sys.argv[3]), sys.argv[4] == "1"
proved, _ = verify.verify(frontend.lower(src))
weights = {}
for f in proved.functions:
    stack = list(f.body)
    while stack:
        s = stack.pop()
        if isinstance(s, ir.ForRange) and s.par is not None:
            weights[f.name] = s.par.weight
        for v in vars(s).values():
            if isinstance(v, tuple):
                stack.extend(x for x in v if isinstance(x, ir.Stmt))
missing = sorted({"compute", "stream"} - set(weights))
if missing:
    raise SystemExit(f"not compiled as parallel loops: {missing} (skipped: {proved.skipped})")
c = cgen.generate(proved, src, src.name)
(out / "par_kernels.c").write_text(c)
so = str(cbuild.build(c, src.stem, out, flags=list(cbuild.DEFAULT_FLAGS) + flags)) if build else None
print(json.dumps({"so": so, "weights": weights}))
"""

# Runs in the measured interpreter (host or device): one thread count per process (TYPEDPYTHON_THREADS).
DRIVER = r'''
import importlib.machinery, importlib.util, json, math, os, statistics, sys, time
path, sizes, out = sys.argv[1], [int(x) for x in sys.argv[2].split(",")], sys.argv[3]
loader = importlib.machinery.ExtensionFileLoader("par_kernels", path)
spec = importlib.util.spec_from_file_location("par_kernels", path, loader=loader)
m = importlib.util.module_from_spec(spec)
loader.exec_module(m)
threads = os.environ.get("TYPEDPYTHON_THREADS", "?")
x = [(i % 97) * 0.01 for i in range(64)]
want = [((((((((v * 0.5 + 0.5) * v - 0.25) * v + 0.125) * v - 0.0625) * v + 0.03125) * v - 0.015625) * v
         + 0.0078125) * v - 0.00390625) for v in x]
m.compute(x, 0.5)
assert x == want, "the compiled loop differs from the interpreter"


def sample(fn, args):
    reps = 1
    while True:
        t0 = time.perf_counter()
        for _ in range(reps):
            fn(*args)
        dt = time.perf_counter() - t0
        if dt >= 0.02:
            return dt / reps
        reps *= 2


with open(out, "w") as f:
    for n in sizes:
        x = [(i % 97) * 0.01 for i in range(n)]
        a = [0.5] * n
        for kernel, fn, args in (("compute", m.compute, (x, 0.5)), ("stream", m.stream, (a, 1e-9))):
            fn(*args)
            secs = statistics.median(sample(fn, args) for _ in range(7))
            load = os.getloadavg()[0] if hasattr(os, "getloadavg") else None
            f.write(json.dumps({"kernel": kernel, "n": n, "threads": threads, "seconds": secs, "load": load}) + "\n")
            f.flush()
'''

LAUNCHER = """\
/* par_threshold.py android-kit (#278): the Android package of 3.15t ships libpython3.15t.so and no interpreter
 * executable, so the device runs the driver through this launcher. */
#include <Python.h>

int main(int argc, char **argv)
{
    return Py_BytesMain(argc, argv);
}
"""


def _load() -> float:
    return os.getloadavg()[0]


def _build(args, out: Path, build: bool) -> dict:
    out.mkdir(parents=True, exist_ok=True)
    src = out / "par_kernels.py"
    src.write_text(KERNELS.lstrip())
    env = dict(os.environ, PYTHONPATH=str(Path(args.typedpython).resolve()))
    r = subprocess.run([args.python, "-c", BUILD, str(src), str(out), json.dumps(FLAGS), "1" if build else "0"],
                       capture_output=True, text=True, env=env)
    if r.returncode != 0:
        raise SystemExit(f"build failed:\n{r.stdout}{r.stderr}")
    return json.loads(r.stdout.strip().splitlines()[-1])


def _thread_counts(cores: int) -> list[int]:
    out = [1]
    t = 2
    while t < cores:
        out.append(t)
        t *= 2
    if cores > 1:
        out.append(cores)
    return sorted(set(out))


def analyze(rows: list[dict], weights: dict[str, int]) -> dict:
    """The candidates from the table (module docstring)."""
    table: dict[tuple[str, int], dict[int, float]] = {}
    for r in rows:
        table.setdefault((r["kernel"], int(r["threads"])), {})[int(r["n"])] = float(r["seconds"])
    kernels = sorted({k for k, _ in table})
    counts = sorted({t for _, t in table})
    result: dict = {"kernels": {}, "thread_counts": counts}
    for k in kernels:
        seq = table.get((k, 1), {})
        per_t = {}
        for t in counts:
            if t == 1:
                continue
            par = table.get((k, t), {})
            ns = sorted(n for n in par if n in seq)
            cross = None
            for i, n in enumerate(ns):
                if all(par[m] < seq[m] for m in ns[i:]):
                    cross = n
                    break
            biggest = ns[-1] if ns else None
            per_t[t] = {
                "crossover_n": cross,
                "min_work": None if cross is None else cross * weights.get(k, 1),
                "speedup_at_largest_n": None if biggest is None else seq[biggest] / par[biggest],
                "start_join_seconds": None if not ns else par[ns[0]] - seq[ns[0]],
            }
        cap = None
        ts = [t for t in counts if t > 1 and per_t[t]["speedup_at_largest_n"]]
        for a, b in zip(ts, ts[1:]):
            if per_t[b]["speedup_at_largest_n"] < 1.05 * per_t[a]["speedup_at_largest_n"]:
                cap = a
                break
        result["kernels"][k] = {"weight": weights.get(k, 1), "by_threads": per_t,
                                "thread_cap": cap if cap is not None else (ts[-1] if ts else None)}
    default_t = max((t for t in counts if t <= 8), default=1)
    works = [result["kernels"][k]["by_threads"].get(default_t, {}).get("min_work") for k in kernels]
    works = [w for w in works if w is not None]
    comp = result["kernels"].get("compute", {}).get("by_threads", {}).get(default_t, {})
    result["candidates"] = {
        "threads": default_t,
        "TP_PAR_MIN_WORK": max(works) if works else None,
        "TP_PAR_MIN_CHUNK": (comp["crossover_n"] // default_t) if comp.get("crossover_n") else None,
        "thread_cap": [result["kernels"][k]["thread_cap"] for k in kernels],
    }
    return result


def cmd_host(args) -> None:
    load0 = _load()
    if load0 >= 1.0 and not args.force:
        raise SystemExit(f"load average {load0:.2f} >= 1.0: run it alone (or --force, recorded as such)")
    work = Path(args.work)
    built = _build(args, work, build=True)
    cores = os.cpu_count() or 1
    rows = []
    for t in _thread_counts(cores):
        out = work / f"threads-{t}.jsonl"
        env = dict(os.environ, TYPEDPYTHON_THREADS=str(t))
        subprocess.run([args.python, "-c", DRIVER, built["so"], ",".join(map(str, SIZES)), str(out)],
                       check=True, env=env)
        rows += [json.loads(line) for line in out.read_text().splitlines()]
    report = {
        "date": datetime.datetime.now().isoformat(timespec="seconds"),
        "host": platform.node(), "machine": platform.machine(), "cores": cores,
        "commit": subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True, cwd=ROOT).stdout.strip(),
        "load": [load0, _load()], "forced": bool(args.force and load0 >= 1.0), "flags": FLAGS,
        "weights": built["weights"], "rows": rows, "analysis": analyze(rows, built["weights"]),
    }
    _write(report, args.out)


def cmd_android_kit(args) -> None:
    kit = Path(args.kit)
    built = _build(args, kit, build=False)
    (kit / "driver.py").write_text(DRIVER.lstrip())
    (kit / "launcher.c").write_text(LAUNCHER)
    (kit / "weights.json").write_text(json.dumps(built["weights"]))
    prefix, ndk = Path(args.prefix).resolve(), Path(args.ndk).resolve()
    host_tag = "darwin-x86_64" if sys.platform == "darwin" else "linux-x86_64"
    clang = ndk / "toolchains" / "llvm" / "prebuilt" / host_tag / "bin" / f"aarch64-linux-android{args.api}-clang"
    runtime = Path(args.typedpython).resolve() / "typedpython" / "runtime"
    inc = prefix / "include" / "python3.15t"
    dev = "/data/local/tmp/tp278"
    q = shlex.quote
    sizes = ",".join(map(str, SIZES))
    lines = [
        "#!/bin/sh",
        "# par_threshold.py android-kit (#278): build with the NDK, push, run each thread count alone, pull.",
        "set -eu",
        f"cd {q(str(kit.resolve()))}",
        f"{q(str(clang))} -shared -fPIC {' '.join(['-O2', '-ffp-contract=off', '-fno-fast-math', *FLAGS])} "
        f"-I{q(str(inc))} -I{q(str(runtime))} par_kernels.c -L{q(str(prefix / 'lib'))} -lpython3.15t -lm "
        "-o par_kernels.so",
        f"{q(str(clang))} -O2 -I{q(str(inc))} launcher.c -L{q(str(prefix / 'lib'))} -lpython3.15t -o pylaunch",
        "adb shell nproc > cores.txt",
        f"adb shell rm -rf {dev} && adb shell mkdir -p {dev}",
        f"adb push {q(str(prefix))} {dev}/prefix > /dev/null",
        f"adb push par_kernels.so driver.py pylaunch {dev}/",
        "mkdir -p results",
        "CORES=$(tr -d '\\r' < cores.txt)",
        "T=1",
        "while :; do",
        "  adb shell uptime",
        f"  adb shell \"cd {dev} && LD_LIBRARY_PATH={dev}/prefix/lib PYTHONHOME={dev}/prefix TYPEDPYTHON_THREADS=$T "
        f"./pylaunch driver.py {dev}/par_kernels.so {sizes} {dev}/threads-$T.jsonl\"",
        f"  adb pull {dev}/threads-$T.jsonl results/ > /dev/null",
        "  [ \"$T\" -ge \"$CORES\" ] && break",
        "  T=$((T * 2)); [ \"$T\" -gt \"$CORES\" ] && T=$CORES",
        "done",
    ]
    script = kit / "run-on-device.sh"
    script.write_text("\n".join(lines) + "\n")
    print(f"kit written to {kit}; next: sh {script}")


def cmd_analyze(args) -> None:
    rows = []
    for p in args.results:
        rows += [json.loads(line) for line in Path(p).read_text().splitlines() if line.strip()]
    wfile = Path(args.results[0]).parent.parent / "weights.json"
    weights = json.loads(wfile.read_text()) if wfile.is_file() else {}
    report = {"date": datetime.datetime.now().isoformat(timespec="seconds"), "weights": weights, "rows": rows,
              "loads": sorted({r.get("load") for r in rows if r.get("load") is not None})[-1:],
              "analysis": analyze(rows, weights)}
    _write(report, args.out)


def _write(report: dict, out: str | None) -> None:
    text = json.dumps(report, indent=2)
    print(json.dumps(report["analysis"]["candidates"], indent=2))
    if out:
        Path(out).parent.mkdir(parents=True, exist_ok=True)
        Path(out).write_text(text + "\n")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    h = sub.add_parser("host")
    h.add_argument("--typedpython", required=True)
    h.add_argument("--python", required=True, help="the 3.15t interpreter that builds and runs the loops")
    h.add_argument("--work", default=str(ROOT / ".cache" / "par-threshold"))
    h.add_argument("--out")
    h.add_argument("--force", action="store_true")
    k = sub.add_parser("android-kit")
    k.add_argument("--typedpython", required=True)
    k.add_argument("--python", required=True, help="the host 3.15t interpreter that generates the C")
    k.add_argument("--prefix", required=True, help="prefix/ of the unpacked python-3.15.0t-aarch64-linux-android")
    k.add_argument("--ndk", required=True)
    k.add_argument("--api", default="24")
    k.add_argument("--kit", default=str(ROOT / ".cache" / "par-kit"))
    a = sub.add_parser("analyze")
    a.add_argument("results", nargs="+")
    a.add_argument("--out")
    args = ap.parse_args()
    {"host": cmd_host, "android-kit": cmd_android_kit, "analyze": cmd_analyze}[args.cmd](args)


if __name__ == "__main__":
    main()
