#!/usr/bin/env python3
"""The one TypedPython desktop measurement of #41 (leader answer, 2026-11-21: the outstanding measurements of
#41, #255, #125 and #274 run together, once, by the coordinating session, alone, load below 1). Stdlib only;
it reuses run.py's build and A/B pieces.

    python3 benchmarks/typedpython/quiet_window.py \
        --typedpython python-multiplatform-ksp/src/main/python \
        --typedpython-python python-multiplatform-ksp/.venv/bin/python \
        --python python-multiplatform-ksp/.venv/bin/python \
        --baseline-typedpython .worktrees/m41-base/python-multiplatform-ksp/src/main/python \
        --write

`--baseline-typedpython` is a checkout of the commit before #405 (`git worktree add .worktrees/m41-base
cee0b92f^1`); without it item 3's before/after pair is skipped and says so.

Items (the numbering of #41's comment of 2026-11-21):
  1. nbody, spectral-norm and fannkuch-redux: CPython vs `@compiled`, interleaved A/B pairs (run.py's
     `measure_pair`), median ratio. nbody's criterion is >= 10x or a recorded decision.
  2. nbody's ratio of item 1 is #255's speed after (d2) #415 (the old 31x is not assumed).
  3. binary-trees: CPython vs `@compiled` on this tree, and `@compiled` before #405 vs this tree
     (#405 + #412), interleaved; ratio = baseline time / current time.
  4. The #226 scale kernel (`a[i] = a[i] * k` over an array.array of 2,000,000 doubles) built three ways:
     plain loads/stores (`-DTP_BUFFER_PLAIN`), relaxed-atomic scalar (`-DTP_NO_BUF_VEC`), and the inline-asm
     vector helper (default). One process, interleaved rounds, the order rotated each round. #274's rule:
     revert the vector path when vector/plain > 1.10 or vector is not faster than the atomic scalar path;
     the script prints the verdict, the revert itself is a PR.

Refuses to start when the 1-minute load average is 1.0 or more (`--force` records the run anyway, marked).
Every pair records the load; the JSON records the commit, host, interpreter and both load ends.
"""
from __future__ import annotations

import argparse
import datetime
import json
import os
import platform
import socket
import statistics
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
import run  # noqa: E402

LOAD_MAX = 1.0
KERNEL_N = 2_000_000
KERNEL_CALLS = 20          # calls per flavour per round; the round's value is their median
FLAVOURS = {"plain": ["-DTP_BUFFER_PLAIN"], "atomic": ["-DTP_NO_BUF_VEC"], "vector": []}

KERNEL_SRC = """\
import array


@compiled
def scale_arr(a: array.array[float], k: float) -> None:
    for i in range(len(a)):
        a[i] = a[i] * k
"""

KERNEL_BUILD = """
import json, sys
from pathlib import Path
from typedpython import cbuild, cgen, frontend, verify
src, out, flags = Path(sys.argv[1]), Path(sys.argv[2]), json.loads(sys.argv[3])
proved, _ = verify.verify(frontend.lower(src))
names = [f.name for f in proved.functions]
c = cgen.generate(proved, src, src.name)
so = cbuild.build(c, src.stem, out, flags=list(cbuild.DEFAULT_FLAGS) + flags)
print(json.dumps({"so": str(so), "compiled": names, "vector_helper": "tp_buf_ld2_f64(" in c}))
"""

KERNEL_TIME = """
import array, importlib.machinery, importlib.util, json, os, statistics, sys, time
mods = {}
for spec in json.loads(sys.argv[1]):
    name, path = spec
    loader = importlib.machinery.ExtensionFileLoader(name, path)
    s = importlib.util.spec_from_file_location(name, path, loader=loader)
    m = importlib.util.module_from_spec(s)
    loader.exec_module(m)
    mods[name] = m
n, calls, rounds = int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
names = list(mods)
ref = array.array("d", [i * 0.37 - 5.0 for i in range(1000)])
outs = []
for name in names:                      # the three agree with the interpreter on a small input
    a = array.array("d", ref)
    mods[name].scale_arr(a, 1.5)
    outs.append(a.tolist())
want = [v * 1.5 for v in ref]
assert all(o == want for o in outs), "flavours differ from the interpreter"
data = array.array("d", [1.0]) * n
rows = []
for r in range(rounds):
    order = names[r % len(names):] + names[:r % len(names)]
    row = {"load": os.getloadavg()[0]}
    for name in order:
        f = mods[name].scale_arr
        f(data, 1.0)                    # warm
        ts = []
        for _ in range(calls):
            t0 = time.perf_counter()
            f(data, 1.0)
            ts.append(time.perf_counter() - t0)
        row[name] = statistics.median(ts)
    rows.append(row)
print(json.dumps(rows))
"""


def load1() -> float:
    return os.getloadavg()[0]


def ab(name: str, a: list[str], b: list[str], pairs: int, label: str) -> dict[str, object]:
    r = run.measure_pair(name, a, b, None, pairs, label)
    if r.get("outputs_differ"):
        raise SystemExit(f"{name}/{label}: outputs differ, nothing recorded")
    return r


def compiled_items(args: argparse.Namespace, tp_dir: Path, build_dir: Path) -> dict[str, object]:
    run.TP_BUILD = build_dir
    return run.build_compiled(tp_dir, args.typedpython_python)


def item_kernels(args: argparse.Namespace, work: Path) -> dict[str, object]:
    work.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, PYTHONPATH=str(Path(args.typedpython).resolve()))
    built = []
    info: dict[str, object] = {}
    for flavour, flags in FLAVOURS.items():
        src = work / f"kern_{flavour}.py"
        src.write_text(KERNEL_SRC)
        r = subprocess.run([args.typedpython_python, "-c", KERNEL_BUILD, str(src), str(work / flavour),
                            json.dumps(flags)], capture_output=True, text=True, env=env)
        if r.returncode != 0:
            return {"error": f"{flavour}: build failed: {r.stderr.strip().splitlines()[-1:]}"}
        b = json.loads(r.stdout.strip().splitlines()[-1])
        if b["compiled"] != ["scale_arr"]:
            return {"error": f"{flavour}: scale_arr did not compile ({b['compiled']})"}
        info[flavour] = {"flags": flags, "vector_helper_in_c": b["vector_helper"]}
        built.append([src.stem, b["so"]])
    r = subprocess.run([args.python, "-c", KERNEL_TIME, json.dumps(built), str(KERNEL_N), str(KERNEL_CALLS),
                        str(args.pairs)], capture_output=True, text=True)
    if r.returncode != 0:
        return {"error": f"timing failed: {r.stderr.strip().splitlines()[-1:]}"}
    rows = json.loads(r.stdout.strip().splitlines()[-1])
    med = {f: statistics.median(row[f"kern_{f}"] for row in rows) for f in FLAVOURS}
    vec_plain = statistics.median(row["kern_vector"] / row["kern_plain"] for row in rows)
    atomic_plain = statistics.median(row["kern_atomic"] / row["kern_plain"] for row in rows)
    vec_atomic = statistics.median(row["kern_vector"] / row["kern_atomic"] for row in rows)
    keep = vec_plain <= 1.10 and vec_atomic < 1.0
    return {"n": KERNEL_N, "calls_per_round": KERNEL_CALLS, "rounds": rows, "build": info,
            "median_s": med, "vector_over_plain": vec_plain, "atomic_over_plain": atomic_plain,
            "vector_over_atomic": vec_atomic,
            "verdict_274": "keep the vector path" if keep else "REVERT the vector path (#274 rule)",
            "vector_helper_compiled_in": platform.machine() in ("arm64", "aarch64")}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--typedpython", required=True, metavar="DIR")
    ap.add_argument("--typedpython-python", default=sys.executable)
    ap.add_argument("--python", default=sys.executable, help="the measured interpreter (3.15t)")
    ap.add_argument("--baseline-typedpython", metavar="DIR", help="typedpython package before #405 (item 3)")
    ap.add_argument("--pairs", type=int, default=11)
    ap.add_argument("--force", action="store_true", help="run at load >= 1.0 (the record says so)")
    ap.add_argument("--write", action="store_true", help="write results/<date>-<host>-m41-quiet-window.json")
    args = ap.parse_args()

    start_load = os.getloadavg()
    if start_load[0] >= LOAD_MAX and not args.force:
        print(f"load {start_load[0]:.2f} >= {LOAD_MAX}: not a quiet window (check uptime; --force to record anyway)")
        return 2
    report: dict[str, object] = {
        "date": datetime.datetime.now().isoformat(timespec="seconds"), "commit": run.source_commit(),
        "host": socket.gethostname(), "machine": platform.machine(), "python": args.python,
        "pairs": args.pairs, "load_start": start_load, "forced": start_load[0] >= LOAD_MAX,
        "method": "interleaved A/B, order alternated; ratio = A time / B time"}

    current = compiled_items(args, Path(args.typedpython), ROOT / "tp-build")
    items: dict[str, object] = {}
    for name in ("nbody", "spectral-norm", "fannkuch-redux", "binary-trees"):
        entry = current.get(name)
        size = run.BENCHMARKS[name][5]
        if not isinstance(entry, tuple):
            items[name] = {"skipped": entry}
            continue
        cmds = run.commands(name, size, args.python, False, current)
        r = ab(name, cmds["CPython"], cmds["TypedPython @compiled"], args.pairs, "compiled")
        r.update(size=size, compiled_functions=entry[1])
        items[name] = r
        print(f"measured {name}: {r['median_ratio']:.2f}x", file=sys.stderr)
    report["items_1_2_3_vs_cpython"] = items

    if args.baseline_typedpython:
        base = compiled_items(args, Path(args.baseline_typedpython), ROOT / "tp-build" / "baseline")
        name, size = "binary-trees", run.BENCHMARKS["binary-trees"][5]
        b, c = base.get(name), current.get(name)
        if isinstance(b, tuple) and isinstance(c, tuple):
            bc = run.commands(name, size, args.python, False, base)["TypedPython @compiled"]
            cc = run.commands(name, size, args.python, False, current)["TypedPython @compiled"]
            r = ab(name, bc, cc, args.pairs, "before-405-vs-now")
            r.update(size=size, baseline_compiled=b[1], current_compiled=c[1])
            report["item_3_before_after"] = r
        else:
            report["item_3_before_after"] = {"skipped": f"baseline {b!r}, current {c!r}"}
    else:
        report["item_3_before_after"] = {"skipped": "no --baseline-typedpython"}

    report["item_4_kernels"] = item_kernels(args, ROOT / "tp-build" / "m41-kernels")
    report["load_end"] = os.getloadavg()

    print()
    print(f"commit {report['commit']}, host {report['host']}, {args.pairs} pairs, load "
          f"{start_load[0]:.2f} -> {report['load_end'][0]:.2f}{' (FORCED, not quiet)' if report['forced'] else ''}")
    print("| item | benchmark | speedup (median of pairs) | range | load during pairs |")
    print("|---|---|---|---|---|")
    for name, r in items.items():
        if "skipped" in r:
            print(f"| 1-3 | {name} | {r['skipped']} | | |")
            continue
        loads = [p["load"][0] for p in r["pairs"]]
        print(f"| 1-3 | {name} vs CPython | {r['median_ratio']:.2f}x | {r['min_ratio']:.2f} to "
              f"{r['max_ratio']:.2f}x | {min(loads):.2f} to {max(loads):.2f} |")
    ba = report["item_3_before_after"]
    if "skipped" in ba:
        print(f"| 3 | binary-trees before #405 vs now | {ba['skipped']} | | |")
    else:
        loads = [p["load"][0] for p in ba["pairs"]]
        print(f"| 3 | binary-trees before #405 vs now | {ba['median_ratio']:.3f}x | {ba['min_ratio']:.3f} to "
              f"{ba['max_ratio']:.3f}x | {min(loads):.2f} to {max(loads):.2f} |")
    k = report["item_4_kernels"]
    if "error" in k:
        print(f"| 4 | scale kernel | {k['error']} | | |")
    else:
        print(f"| 4 | scale kernel vector/plain | {k['vector_over_plain']:.3f} | atomic/plain "
              f"{k['atomic_over_plain']:.3f}, vector/atomic {k['vector_over_atomic']:.3f} | {k['verdict_274']} |")
    if args.write:
        path = ROOT / "results" / f"{datetime.date.today().isoformat()}-{socket.gethostname()}-m41-quiet-window.json"
        path.write_text(json.dumps(report, indent=2) + "\n")
        print(f"wrote {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
