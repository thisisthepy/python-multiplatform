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

Cython and numba columns (issue: compare TypedPython fairly against them). They need a harness venv
that holds Cython, numba and a C compiler (Xcode command line tools), created ONLY with uv:

    uv venv --python 3.14 benchmarks/typedpython/.venv
    uv pip install --python benchmarks/typedpython/.venv/bin/python cython setuptools numba

`--tools-python` (default benchmarks/typedpython/.venv/bin/python) names that interpreter; without it
the three columns report n/a. Columns, each in the form most favourable to it:
  "Cython (pure)"   py/*.py compiled unmodified by cythonize (annotations as written), same CPython;
  "Cython (typed)"  cython/*.pyx: cdef types, C arrays, boundscheck/wraparound off, cdef class Node;
  "numba"           numba/*.py with @njit(cache=True) (binary-trees: jitclass, no cache). The time
                    reported is the STEADY-STATE call at benchmark size, inside the process, after a
                    warm-up call that triggered the JIT. The JIT cost is the separate record
                    "numba compile" (first call: cold cache vs cached; numba import excluded).
Cython is built by cython/build.py before any timing (build/cython/, git-ignored). numba runs in a
fresh process with its own NUMBA_CACHE_DIR (build/numba-cache/<benchmark>, wiped per cold run).
"CPython (tools python)" is the same program under the tools interpreter, and is the baseline for the
Cython/numba ratios in `interleaved` (so those pairs use one interpreter); it reads "same as CPython"
when --python and --tools-python are the same version. Per-column tool version, flags and a semantics
note are written to the JSON ("tools") and printed after the table.

binary-trees declares `__slots__` (TypedPython compiles fixed-layout classes only, SPEC N-11). The
speedup is reported against that CPython version; `CPython (no __slots__)` runs the same program
with the `__slots__` line removed, so what adding the slots changes in CPython is recorded too.
"""
import argparse
import datetime
import json
import os
import shutil
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
NOSLOTS = "CPython (no __slots__)"
CPY_TOOLS = "CPython (tools python)"
CY_PURE = "Cython (pure)"
CY_TYPED = "Cython (typed)"
NUMBA = "numba"
TOOL_COLS = [CY_PURE, CY_TYPED, NUMBA]
COLUMNS = ["CPython", NOSLOTS, CPY_TOOLS, "Node", "Java", "Rust", "TypedPython @compiled", *TOOL_COLS]
TOOLS_PY_DEFAULT = ROOT / ".venv" / "bin" / "python"
CYTHON_OUT = ROOT / "build" / "cython"
NUMBA_CACHE = ROOT / "build" / "numba-cache"
# tiny sizes for numba's warm-up (first) call: the types are the same at any size, so the first
# call is almost all compile time
NUMBA_WARM = {"nbody": "10", "spectral-norm": "4", "fannkuch-redux": "4", "binary-trees": "1",
              "wordfreq": "10"}
CYTHON_FLAGS = "cython/build.py: cythonize(language_level=3), cc -O3 -ffp-contract=off (no fused multiply-add)"
SEMANTICS = {
    CY_PURE: "py/*.py unmodified; Cython honours the existing annotations (float is a C double, int stays "
             "a Python int, list stays a list), so semantics are CPython's apart from float arithmetic on typed locals",
    CY_TYPED: "C semantics where declared: C long/int arithmetic wraps instead of growing, cdivision (no "
              "ZeroDivisionError), boundscheck/wraparound off on C arrays, cdef class Node (no __dict__), "
              "C-stack recursion (no RecursionError); wordfreq keeps a Python dict",
    NUMBA: "fixed-width int64/float64 (ints wrap, no Python big ints, no OverflowError), error_model=numpy "
           "(no ZeroDivisionError), no bounds checks, numpy arrays for lists, typed.Dict/List for dict/list "
           "(wordfreq), jitclass for the tree (binary-trees); reported time is steady state, JIT is separate",
}

SLOTS_LINE = '    __slots__ = ("left", "right")\n'
NOSLOTS_BENCHMARKS = {"binary-trees"}
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


def noslots_source(name: str) -> Path:
    """The benchmark with its `__slots__` line removed, written under tp-build/noslots/."""
    py = BENCHMARKS[name][0]
    source = (ROOT / "py" / py).read_text()
    if SLOTS_LINE not in source:
        sys.exit(f"{name}: expected the line {SLOTS_LINE.strip()!r} in py/{py}")
    out = TP_BUILD / "noslots" / py
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(source.replace(SLOTS_LINE, "", 1))
    return out


TOOLS_PROBE = """
import json, sys
out = {"python": sys.version.split()[0]}
for mod in ("Cython", "numba", "llvmlite", "numpy", "setuptools"):
    try:
        out[mod] = __import__(mod).__version__
    except Exception as e:
        out[mod] = f"missing ({type(e).__name__}: {e})"
print(json.dumps(out))
"""


def _cython_up_to_date() -> bool:
    sources = [*(ROOT / "py").glob("*.py"), *(ROOT / "cython").glob("*"), ]
    newest = max(p.stat().st_mtime for p in sources)
    for name, (py, *_r) in BENCHMARKS.items():
        stem = Path(py).stem
        for kind, mod in (("pure", stem), ("typed", stem + "_typed")):
            found = list((CYTHON_OUT / kind).glob(f"{mod}.*.so"))
            if not found or found[0].stat().st_mtime < newest:
                return False
    return True


def build_tools(tools_python: str | None, python: str) -> dict[str, object]:
    """Probe the tools interpreter and cythonize + C-compile the Cython columns (never in a timed run).
    Returns {"python", "versions", "na": {column: reason}, "same_python"}."""
    tools: dict[str, object] = {"python": None, "versions": {}, "na": {}, "same_python": True}
    na: dict[str, str] = tools["na"]  # type: ignore[assignment]
    if not tools_python or not Path(tools_python).exists():
        why = ("n/a (no harness venv; create it with: uv venv --python 3.14 benchmarks/typedpython/.venv && "
               "uv pip install --python benchmarks/typedpython/.venv/bin/python cython setuptools numba)")
        return {**tools, "na": {c: why for c in TOOL_COLS}}
    probe = subprocess.run([tools_python, "-c", TOOLS_PROBE], capture_output=True, text=True)
    if probe.returncode != 0:
        return {**tools, "na": {c: f"n/a (tools python failed: {probe.stderr.strip()[-200:]})" for c in TOOL_COLS}}
    versions = json.loads(probe.stdout.strip().splitlines()[-1])
    tools.update(python=tools_python, versions=versions)
    base = subprocess.run([python, "-c", "import sys; print(sys.version.split()[0])"],
                          capture_output=True, text=True).stdout.strip()
    tools["same_python"] = base == versions["python"]
    tools["baseline_python"] = base
    if "missing" in versions["Cython"] or "missing" in versions["setuptools"]:
        na[CY_PURE] = na[CY_TYPED] = f"n/a (Cython {versions['Cython']}; setuptools {versions['setuptools']})"
    elif not _cython_up_to_date():
        build = subprocess.run([tools_python, str(ROOT / "cython" / "build.py"), str(CYTHON_OUT)],
                               capture_output=True, text=True)
        if build.returncode != 0:
            reason = f"n/a (cython build failed: {' | '.join(build.stderr.strip().splitlines()[-3:])})"
            na[CY_PURE] = na[CY_TYPED] = reason
    if "missing" in versions["numba"] or "missing" in versions["numpy"]:
        na[NUMBA] = f"n/a (numba {versions['numba']}; numpy {versions['numpy']})"
    return tools


def commands(name: str, size: str, python: str, java_source: bool,
             compiled: dict[str, tuple[str, list[str]] | str] | None = None,
             tools: dict[str, object] | None = None) -> dict[str, list[str]]:
    py, js, cls, rs, _, _ = BENCHMARKS[name]
    java = ["java", str(ROOT / "java" / f"{cls}.java"), size] if java_source \
        else ["java", "-cp", str(JAVA_OUT), cls, size]
    cmds = {
        "CPython": [python, str(ROOT / "py" / py), size],
        "Node": [NODE, str(ROOT / "js" / js), size],
        "Java": java,
        "Rust": [str(TARGET / "release" / rs), size],
    }
    if name in NOSLOTS_BENCHMARKS:
        cmds[NOSLOTS] = [python, str(noslots_source(name)), size]
    entry = (compiled or {}).get(name)
    if isinstance(entry, tuple):
        cmds["TypedPython @compiled"] = [python, "-c", RUN_SNIPPET, Path(py).stem, entry[0], size]
    if tools and tools.get("python"):
        tp = str(tools["python"])
        na: dict[str, str] = tools["na"]  # type: ignore[assignment]
        stem = Path(py).stem
        cmds[CPY_TOOLS] = Cmd([tp, str(ROOT / "py" / py), size])
        for col, kind, mod in ((CY_PURE, "pure", stem), (CY_TYPED, "typed", stem + "_typed")):
            so = sorted((CYTHON_OUT / kind).glob(f"{mod}.*.so"))
            if col not in na and so:
                cmd = Cmd([tp, "-c", RUN_SNIPPET, mod, str(so[0]), size])
                cmd.soft = True
                cmds[col] = cmd
        if NUMBA not in na:
            cmd = Cmd([tp, "-c", NUMBA_SNIPPET, str(ROOT / "numba" / py), stem + "_numba",
                       NUMBA_WARM[name], size])
            cmd.numba = True
            cmd.soft = True
            cmd.cache_dir = NUMBA_CACHE / name
            cmd.env = {"NUMBA_CACHE_DIR": str(cmd.cache_dir)}
            cmds[NUMBA] = cmd
    return cmds


class RunFailed(Exception):
    """A Cython/numba column failed to run; reported as n/a with the concrete error."""


class Cmd(list):
    """argv plus, for the numba column, an environment, a cache directory and the last timing."""
    env: dict[str, str] | None = None
    numba = False
    cache_dir: Path | None = None
    cold = True
    last: dict[str, float] | None = None
    soft = False


NUMBA_SNIPPET = """
import importlib.util, json, sys, time
import numba, numpy                     # imported first: import time is not compile time
path, name, warm, size = sys.argv[1:5]
t0 = time.perf_counter()
spec = importlib.util.spec_from_file_location(name, path)
m = importlib.util.module_from_spec(spec)
sys.modules[name] = m                   # jitclass resolves its module through sys.modules
spec.loader.exec_module(m)              # binary-trees (explicit signatures) compiles here
m.run(int(warm))                        # first call: JIT compile (or cache load) at a tiny size
t1 = time.perf_counter()
out = m.run(int(size))                  # steady state: already compiled
t2 = time.perf_counter()
print(out)
print("#timing " + json.dumps({"first_call_s": t1 - t0, "steady_s": t2 - t1}))
"""


def run_once(cmd: list[str], soft: bool = False) -> tuple[float, str]:
    """Wall time and stdout. A numba Cmd returns its steady-state seconds instead (see NUMBA_SNIPPET)."""
    cache_dir = getattr(cmd, "cache_dir", None)
    if cache_dir is not None and getattr(cmd, "cold", True):
        shutil.rmtree(cache_dir, ignore_errors=True)
    env = dict(os.environ, **cmd.env) if getattr(cmd, "env", None) else None  # type: ignore[attr-defined]
    start = time.perf_counter()
    result = subprocess.run(cmd, capture_output=True, text=True, env=env)
    elapsed = time.perf_counter() - start
    if result.returncode != 0:
        if soft or getattr(cmd, "soft", False):
            last = (result.stderr.strip().splitlines() or ["no stderr"])[-1]
            raise RunFailed(f"exit {result.returncode}: {last}")
        sys.stderr.write(f"failed ({result.returncode}): {' '.join(cmd)}\n{result.stderr}")
        sys.exit(1)
    out = result.stdout.strip()
    if getattr(cmd, "numba", False):
        lines = out.splitlines()
        timing = json.loads(lines[-1][len("#timing "):])
        cmd.last = dict(timing, wall_s=elapsed)  # type: ignore[attr-defined]
        out = "\n".join(lines[:-1]).strip()
        elapsed = timing["steady_s"]
    return elapsed, out


def tools_for(args: argparse.Namespace) -> dict[str, object]:
    return build_tools(args.tools_python, args.python)


def tools_report(tools: dict[str, object]) -> dict[str, object]:
    """Per-column tool version, flags and semantics note, for the printout and the JSON."""
    v: dict[str, str] = tools.get("versions") or {}  # type: ignore[assignment]
    na: dict[str, str] = tools["na"]  # type: ignore[assignment]
    py = f"CPython {v.get('python', '?')}"
    report: dict[str, object] = {
        "tools_python": {"interpreter": py, "baseline_python": tools.get("baseline_python"),
                         "same_interpreter_as_CPython_column": tools.get("same_python")},
        CY_PURE: {"tool": f"Cython {v.get('Cython')} on {py}", "flags": CYTHON_FLAGS,
                  "semantics": SEMANTICS[CY_PURE]},
        CY_TYPED: {"tool": f"Cython {v.get('Cython')} on {py}",
                   "flags": CYTHON_FLAGS + "; # cython: boundscheck=False, wraparound=False, cdivision=True, "
                   "initializedcheck=False in each .pyx", "semantics": SEMANTICS[CY_TYPED]},
        NUMBA: {"tool": f"numba {v.get('numba')}, llvmlite {v.get('llvmlite')}, numpy {v.get('numpy')} on {py}",
                "flags": "@njit(cache=True, error_model='numpy'); binary-trees: jitclass + explicit "
                         "signatures, cache=False; NUMBA_CACHE_DIR per benchmark",
                "semantics": SEMANTICS[NUMBA]},
    }
    for col in TOOL_COLS:
        if col in na:
            report[col]["unavailable"] = na[col]  # type: ignore[index]
    return report


def print_tools_report(report: dict[str, object]) -> None:
    print("\ntools:")
    for key, val in report.items():
        if key == "tools_python":
            print(f"  tools python: {val['interpreter']}; baseline CPython column: {val['baseline_python']}; "  # type: ignore[index]
                  f"same interpreter: {val['same_interpreter_as_CPython_column']}")  # type: ignore[index]
        else:
            print(f"  {key}: {val['tool']}\n    flags: {val['flags']}\n    semantics: {val['semantics']}")  # type: ignore[index]


def run_column(col: str, cmd: list[str]) -> tuple[float, str] | str:
    """run_once, but a Cython/numba failure becomes the n/a string instead of ending the run."""
    try:
        return run_once(cmd)
    except RunFailed as e:
        return f"n/a ({e})"


def verify(args: argparse.Namespace) -> int:
    build()
    compiled = build_compiled(Path(args.typedpython), args.typedpython_python) if args.typedpython else None
    tools = tools_for(args)
    bad = 0
    for name, spec in BENCHMARKS.items():
        outputs: dict[str, str] = {}
        notes: dict[str, str] = dict(tools["na"])  # type: ignore[arg-type]
        for col, cmd in commands(name, spec[4], args.python, True, compiled, tools).items():
            res = run_column(col, cmd)
            if isinstance(res, str):
                notes[col] = res
            else:
                outputs[col] = res[1]
        distinct = set(outputs.values())
        status = "OK  " if len(distinct) == 1 else "FAIL"
        print(f"{status} {name} (size {spec[4]}): {next(iter(outputs.values())) if len(distinct) == 1 else ''}")
        print(f"       columns: {', '.join(outputs)}")
        for col, why in notes.items():
            print(f"       {col}: {why}")
        if len(distinct) != 1:
            bad += 1
            for col, out in outputs.items():
                print(f"       {col}: {out}")
    print("all outputs agree" if bad == 0 else f"{bad} benchmark(s) disagree")
    print_tools_report(tools_report(tools))
    return 1 if bad else 0


def measure_numba(cmd: Cmd, repeat: int) -> tuple[dict[str, object], dict[str, object]]:
    """Cold-cache runs (each wipes the cache dir, so each compiles) give the steady-state time and the
    cold compile time; one more run with the cache left in place gives the cached first-call time."""
    steady, outs, cold_first, walls = [], set(), [], []
    for _ in range(repeat):
        t, out = run_once(cmd)
        steady.append(t)
        outs.add(out)
        cold_first.append(cmd.last["first_call_s"])  # type: ignore[index]
        walls.append(cmd.last["wall_s"])  # type: ignore[index]
    cache_files = len([p for p in cmd.cache_dir.rglob("*") if p.is_file()]) if cmd.cache_dir else 0
    cmd.cold = False
    try:
        t_c, out_c = run_once(cmd)
        outs.add(out_c)
        cached_first = cmd.last["first_call_s"]  # type: ignore[index]
        cached_steady = t_c
    finally:
        cmd.cold = True
    column = {"median_s": statistics.median(steady), "runs_s": steady, "output": sorted(outs),
              "process_wall_s_runs": walls, "note": "steady-state call at benchmark size, JIT excluded"}
    compile_rec = {"cold_first_call_s_median": statistics.median(cold_first), "cold_first_call_s_runs": cold_first,
                   "cached_first_call_s": cached_first, "cached_steady_s": cached_steady,
                   "cache_files_written": cache_files, "cache_used": cache_files > 0,
                   "note": "first call = module import + first run() at a tiny size; numba import excluded"}
    return column, compile_rec


def measure(args: argparse.Namespace) -> int:
    load = os.getloadavg()
    print(f"load average: {load[0]:.2f} {load[1]:.2f} {load[2]:.2f}")
    print(subprocess.run(["uptime"], capture_output=True, text=True).stdout.strip())
    if load[0] > LOAD_LIMIT and not args.force:
        print(f"refusing to measure: 1-minute load {load[0]:.2f} > {LOAD_LIMIT} (use --force to override)")
        return 2
    build()
    compiled = build_compiled(Path(args.typedpython), args.typedpython_python) if args.typedpython else None
    tools = tools_for(args)
    report = tools_report(tools)
    results: dict[str, dict[str, object]] = {}
    for name, spec in BENCHMARKS.items():
        size = args.size.get(name, spec[5])
        row: dict[str, object] = {"size": size}
        entry = (compiled or {}).get(name, NOT_BUILT)
        if isinstance(entry, tuple):
            row["compiled_functions"] = entry[1]
        for col, cmd in commands(name, size, args.python, False, compiled, tools).items():
            if col == CPY_TOOLS and tools.get("same_python"):
                continue
            try:
                if col == NUMBA:
                    row[col], row["numba compile"] = measure_numba(cmd, args.repeat)  # type: ignore[arg-type]
                    outs = set(row[col]["output"])  # type: ignore[index]
                else:
                    runs = [run_once(cmd) for _ in range(args.repeat)]
                    outs = {out for _, out in runs}
                    row[col] = {"median_s": statistics.median(t for t, _ in runs),
                                "runs_s": [t for t, _ in runs], "output": sorted(outs)}
            except RunFailed as e:
                row[col] = f"n/a ({e})"
                continue
            if len(outs) != 1:
                print(f"warning: {name}/{col} produced differing outputs across runs")
        if not isinstance(entry, tuple):
            row["TypedPython @compiled"] = entry
        for col, why in tools["na"].items():  # type: ignore[union-attr]
            row[col] = why
        if tools.get("same_python"):
            row[CPY_TOOLS] = "same as CPython"
        measured = [c for c in COLUMNS if isinstance(row.get(c), dict)]
        outs_all = {tuple(row[c]["output"]) for c in measured}  # type: ignore[index]
        row["outputs_agree"] = len(outs_all) == 1
        results[name] = row
        print(f"measured {name}", file=sys.stderr)

    print()
    print("| benchmark | size | " + " | ".join(COLUMNS) + " |")
    print("|---|---|" + "---|" * len(COLUMNS))
    for name, row in results.items():
        cells = [f"{row[c]['median_s']:.3f} s" if isinstance(row.get(c), dict)
                 else str(row.get(c, ", " if c == NOSLOTS else NOT_BUILT))
                 for c in COLUMNS]  # type: ignore[index]
        flag = "" if row["outputs_agree"] else " (OUTPUTS DIFFER)"
        print(f"| {name}{flag} | {row['size']} | " + " | ".join(cells) + " |")
    print(f"\nmedian of {args.repeat} runs, wall time; Java excludes javac, includes JVM startup.")
    print("numba column = steady-state call inside the process (JIT excluded); its compile cost:")
    for name, row in results.items():
        rec = row.get("numba compile")
        if isinstance(rec, dict):
            print(f"  {name}: cold first call {rec['cold_first_call_s_median']:.3f} s, cached first call "
                  f"{rec['cached_first_call_s']:.3f} s, cache files written {rec['cache_files_written']}")
    print_tools_report(report)

    if args.write:
        out_dir = ROOT / "results"
        out_dir.mkdir(exist_ok=True)
        path = out_dir / f"{datetime.date.today().isoformat()}-{socket.gethostname()}.json"
        payload = {"date": datetime.datetime.now().isoformat(timespec="seconds"),
                   "host": socket.gethostname(), "load_average": load, "repeat": args.repeat,
                   "python": subprocess.run([args.python, "--version"], capture_output=True, text=True).stdout.strip(),
                   "tools": report, "results": results}
        path.write_text(json.dumps(payload, indent=2) + "\n")
        print(f"wrote {path}")
    return 0


def interleaved(args: argparse.Namespace) -> int:
    """CPython vs TypedPython @compiled, alternated A/B in the same time window (leader decision,
    2026-10-03): when the machine is never quiet, load hits both sides of each pair alike, so the
    per-pair ratio is stable even though absolute times are not. Reports the median ratio, never
    absolute times as the result, with the load average recorded at every pair.

    The Cython and numba columns are paired the same way against the CPython run under the tools
    interpreter (`CPython (tools python)`), each pair alternating which side runs first. For numba the
    ratio uses its in-process steady-state time (JIT excluded, process start included for CPython, a
    small bias in numba's favour at these sizes); its first-call (compile) time is recorded per pair."""
    tools = tools_for(args)
    if not args.typedpython and not tools.get("python"):
        print("interleaved needs --typedpython or a tools python")
        return 2
    compiled = build_compiled(Path(args.typedpython), args.typedpython_python) if args.typedpython else {}
    results: dict[str, dict[str, object]] = {}
    for name, spec in BENCHMARKS.items():
        entry = compiled.get(name)
        size = args.size.get(name, spec[5])
        cmds = commands(name, size, args.python, False, compiled, tools)
        r: dict[str, object] = {"size": size}
        if args.typedpython:
            if isinstance(entry, tuple):
                r.update(measure_pair(name, cmds["CPython"], cmds["TypedPython @compiled"], cmds.get(NOSLOTS),
                                      args.pairs, "compiled"))
                r["compiled_functions"] = entry[1]
            else:
                r["skipped"] = entry
        extras: dict[str, object] = {}
        base = cmds.get(CPY_TOOLS)
        for col in TOOL_COLS:
            if col in tools["na"]:  # type: ignore[operator]
                extras[col] = tools["na"][col]  # type: ignore[index]
            elif col not in cmds or base is None:
                extras[col] = "n/a (not built)"
            else:
                try:
                    extras[col] = measure_pair(name, base, cmds[col], None, args.pairs, col)
                except RunFailed as e:
                    extras[col] = f"n/a ({e})"
        r["tools_pairs"] = extras
        results[name] = r
        print(f"measured {name}", file=sys.stderr)
        if any(isinstance(v, dict) and v.get("outputs_differ") for v in [r, *extras.values()]):
            print(f"{name}: OUTPUTS DIFFER, see the JSON/table")
            return 1

    print()
    print("| benchmark | size | speedup vs CPython (median of pairs) | range | load during pairs (1-min) |")
    print("|---|---|---|---|---|")
    for name, r in results.items():
        if "skipped" in r:
            print(f"| {name} | - | {r['skipped']} | | |")
        elif "median_ratio" in r:
            loads = [p["load"][0] for p in r["pairs"]]  # type: ignore[index]
            print(f"| {name} | {r['size']} | {r['median_ratio']:.1f}x | {r['min_ratio']:.1f}\u2013{r['max_ratio']:.1f}x "
                  f"| {min(loads):.1f}\u2013{max(loads):.1f} |")
    if args.typedpython:
        print(f"\n{args.pairs} A/B pairs per benchmark, order alternated; ratio = CPython time / compiled time.")
    print("\n| benchmark | column | speedup vs CPython (tools python), median of pairs | range | extra |")
    print("|---|---|---|---|---|")
    for name, r in results.items():
        for col, p in r["tools_pairs"].items():  # type: ignore[union-attr]
            if isinstance(p, dict):
                extra = ""
                if col == NUMBA:
                    extra = (f"first call (compile) median {statistics.median(q['first_call_s'] for q in p['pairs']):.3f} s; "
                             f"process wall median {statistics.median(q['wall_s'] for q in p['pairs']):.3f} s")
                print(f"| {name} | {col} | {p['median_ratio']:.1f}x | {p['min_ratio']:.1f}\u2013{p['max_ratio']:.1f}x | {extra} |")
            else:
                print(f"| {name} | {col} | {p} | | |")
    print(f"\n{args.pairs} pairs per column, order alternated; ratio = CPython (tools python) time / column time.")
    report = tools_report(tools)
    print_tools_report(report)
    if args.write:
        out_dir = ROOT / "results"
        out_dir.mkdir(exist_ok=True)
        path = out_dir / f"{datetime.date.today().isoformat()}-{socket.gethostname()}-interleaved.json"
        path.write_text(json.dumps({"date": datetime.datetime.now().isoformat(timespec="seconds"),
                                    "host": socket.gethostname(), "method": "interleaved A/B",
                                    "pairs": args.pairs, "tools": report, "results": results}, indent=2) + "\n")
        print(f"wrote {path}")
    return 0


def measure_pair(name: str, a_cmd: list[str], b_cmd: list[str], c_cmd: list[str] | None, n_pairs: int,
                 label: str) -> dict[str, object]:
    """n_pairs A/B pairs (A = CPython, B = the column); which runs first alternates every pair."""
    pairs = []
    for i in range(n_pairs):
        order = (a_cmd, b_cmd) if i % 2 == 0 else (b_cmd, a_cmd)   # alternate which runs first
        load = os.getloadavg()
        (t1, o1), (t2, o2) = run_once(order[0]), run_once(order[1])
        t_a, o_a, t_b, o_b = (t1, o1, t2, o2) if order[0] is a_cmd else (t2, o2, t1, o1)
        if o_a != o_b:
            print(f"{name}/{label}: OUTPUTS DIFFER in pair {i}: {o_a!r} vs {o_b!r}")
            return {"outputs_differ": True}
        pair: dict[str, object] = {"cpython_s": t_a, "compiled_s": t_b, "ratio": t_a / t_b, "load": load}
        last = getattr(b_cmd, "last", None)
        if getattr(b_cmd, "numba", False) and last:
            pair.update(first_call_s=last["first_call_s"], wall_s=last["wall_s"])
        if c_cmd is not None:
            t_c, o_c = run_once(c_cmd)
            if o_c != o_a:
                print(f"{name}: OUTPUTS DIFFER in pair {i} (no __slots__): {o_c!r} vs {o_a!r}")
                return {"outputs_differ": True}
            pair.update(cpython_noslots_s=t_c, ratio_vs_noslots=t_c / t_b)
        pairs.append(pair)
    ratios = [p["ratio"] for p in pairs]
    out: dict[str, object] = {"pairs": pairs, "median_ratio": statistics.median(ratios),
                              "min_ratio": min(ratios), "max_ratio": max(ratios)}
    if c_cmd is not None:
        out["median_ratio_vs_noslots"] = statistics.median(p["ratio_vs_noslots"] for p in pairs)
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)
    v = sub.add_parser("verify")
    v.add_argument("--python", default=sys.executable)
    m = sub.add_parser("measure")
    m.add_argument("--python", default=sys.executable)
    ab = sub.add_parser("interleaved", help="CPython vs @compiled/Cython/numba alternated A/B; reports median ratios")
    ab.add_argument("--python", default=sys.executable)
    ab.add_argument("--pairs", type=int, default=11)
    ab.add_argument("--write", action="store_true")
    ab.add_argument("--size", action="append", default=[], metavar="NAME=N")
    for p in (v, m, ab):
        p.add_argument("--typedpython", metavar="DIR",
                       help="directory holding the typedpython package; enables the @compiled column")
        p.add_argument("--typedpython-python", default=sys.executable,
                       help="interpreter for compiling (needs pyrefly 1.3.2)")
        p.add_argument("--tools-python", default=str(TOOLS_PY_DEFAULT),
                       help="interpreter of the uv venv holding Cython and numba (default: benchmarks/"
                            "typedpython/.venv/bin/python); enables the Cython and numba columns")
    m.add_argument("--repeat", type=int, default=5)
    m.add_argument("--force", action="store_true")
    m.add_argument("--write", action="store_true")
    m.add_argument("--size", action="append", default=[], metavar="NAME=N",
                   help="override a benchmark size (for runner smoke tests only)")
    args = parser.parse_args()
    if args.cmd in ("measure", "interleaved"):
        args.size = dict(s.split("=", 1) for s in args.size)
    if args.cmd == "interleaved":
        return interleaved(args)
    return verify(args) if args.cmd == "verify" else measure(args)


if __name__ == "__main__":
    sys.exit(main())
