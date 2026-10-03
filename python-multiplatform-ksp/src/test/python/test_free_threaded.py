"""Compiled modules are safe on free-threaded CPython 3.15t (python-multiplatform #159, #141 phase 2).

Free-threaded 3.15t is the only build (maintainer decision 2026-10-04). A generated extension must
  * declare `Py_mod_gil = Py_MOD_GIL_NOT_USED`, so importing it does not switch the GIL back on;
  * keep its shared mutable state thread-safe (runtime/API.md "Free-threaded CPython");
  * read nothing through a borrowed reference that another thread can free;
  * hold the list's critical section from before the copy-in to after the write-back (array params).

Every concurrency scenario runs in a CHILD interpreter with a timeout (a crash or a deadlock fails
one test, not the run). Iteration counts scale with TP_FT_STRESS (default 1).

Red record (before the change, on the 3.15t venv): the GIL test and the three module-level checks
failed (`Py_mod_gil` absent, the GIL came back at import), the concurrent counters lost updates, and
a three-list function was compiled.
"""
from __future__ import annotations

import dataclasses
import json
import os
import re
import subprocess
import sys
import sysconfig
import textwrap
from pathlib import Path

import pytest

from typedpython import cbuild, cgen, frontend, pipeline, verify

STRESS = max(1, int(os.environ.get("TP_FT_STRESS", "1")))
FREE_THREADED = bool(sysconfig.get_config_var("Py_GIL_DISABLED"))

SOURCE = '''\
# typedpython: compiled


class Cell:
    __slots__ = ("a", "b")
    a: object
    b: object

    def __init__(self, a: object, b: object) -> None:
        self.a = a
        self.b = b


class Twin:
    __slots__ = ("a", "b")
    a: object
    b: object

    def __init__(self, a: object, b: object) -> None:
        self.a = a
        self.b = b


def geta(c: Cell) -> object:
    return c.a


def seta(c: Cell, v: object) -> None:
    c.a = v


def mk(a: object, b: object) -> Cell:
    return Cell(a, b)


def total(xs: list[float], rounds: int) -> float:
    s: float = 0.0
    for r in range(rounds):
        for i in range(len(xs)):
            s += xs[i]
    return s


def fill(xs: list[float], v: float) -> None:
    for i in range(len(xs)):
        xs[i] = v


def edge(xs: list[float]) -> float:
    return xs[0] - xs[len(xs) - 1]


def fill_int(xs: list[int], v: int) -> None:
    for i in range(len(xs)):
        xs[i] = v


def edge_int(xs: list[int]) -> int:
    return xs[0] - xs[len(xs) - 1]


def rotate(xs: list[float]) -> None:
    if len(xs) > 1:
        first: float = xs[0]
        for i in range(1, len(xs)):
            xs[i - 1] = xs[i]
        xs[len(xs) - 1] = first


def zero2(xs: list[int], ys: list[int]) -> int:
    for i in range(len(xs)):
        xs[i] = 0
    for i in range(len(ys)):
        ys[i] = 0
    return 0


def pair_edge(xs: list[int], ys: list[int]) -> int:
    return xs[0] - ys[len(ys) - 1]


def three(a: list[int], b: list[int], c: list[int]) -> int:
    return len(a) + len(b) + len(c)


K: int = 3


def usek(x: int) -> int:
    return x + K
'''

LOADER = """\
import faulthandler, importlib.machinery, importlib.util, json, sys, threading, time
faulthandler.enable()
path = sys.argv[1]
loader = importlib.machinery.ExtensionFileLoader("ftmod", path)
spec = importlib.util.spec_from_file_location("ftmod", path, loader=loader)
m = importlib.util.module_from_spec(spec)
loader.exec_module(m)
STRESS = int(sys.argv[2])


def run_threads(targets):
    errors = []

    def wrap(fn):
        def go():
            try:
                fn()
            except BaseException as e:      # a failed assertion in a worker must reach the report
                errors.append(repr(e))
        return go

    ts = [threading.Thread(target=wrap(t)) for t in targets]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    return errors
"""


@pytest.fixture(scope="module")
def built(tmp_path_factory):
    d = tmp_path_factory.mktemp("ft")
    path = d / "ftmod.py"
    path.write_text(SOURCE)
    result = pipeline.compile_module(path, d / "out")
    assert result.extension is not None, result.skipped
    compiled = {f.name for f in result.module.functions}
    wanted = {"geta", "seta", "mk", "total", "fill", "edge", "fill_int", "edge_int", "rotate", "zero2",
              "pair_edge", "usek"}
    assert wanted <= compiled, {n: result.skipped.get(n) for n in wanted - compiled}
    return result, path, d


@pytest.fixture(scope="module")
def extension(built):
    return built[0].extension


def run_child(extension: Path, body: str, timeout: int = 180, env: dict | None = None) -> dict:
    script = LOADER + textwrap.dedent(body)
    e = dict(os.environ)
    e.pop("PYTHON_GIL", None)
    if os.environ.get("TP_FT_FORCE_GIL_OFF") == "1":     # red record: what the code does with no GIL
        e["PYTHON_GIL"] = "0"
    e.update(env or {})
    proc = subprocess.run([sys.executable, "-c", script, str(extension), str(STRESS)],
                          capture_output=True, text=True, timeout=timeout, env=e)
    assert proc.returncode == 0, f"child failed ({proc.returncode}):\n{proc.stdout}\n{proc.stderr}"
    return json.loads(proc.stdout.strip().splitlines()[-1])


pytestmark = pytest.mark.skipif(not FREE_THREADED, reason="needs a free-threaded CPython (3.15t)")


# --- the module declares it does not need the GIL ----------------------------------------------------

def test_generated_module_declares_gil_not_used(built):
    result, path, _ = built
    c_text = cgen.generate(result.module, path)
    assert re.search(r"\{\s*Py_mod_gil\s*,\s*Py_MOD_GIL_NOT_USED\s*\}", c_text)
    assert "Py_GIL_DISABLED" in c_text          # guarded: a GIL build still compiles


def test_importing_a_compiled_module_keeps_the_gil_off(extension):
    r = run_child(extension, """
        print(json.dumps({"before": True, "gil": sys._is_gil_enabled(),
                          "f": m.total([1.0, 2.0], 1)}))
    """)
    assert r["gil"] is False and r["f"] == 3.0


def test_no_runtime_warning_about_the_gil(extension):
    script = LOADER.replace("import faulthandler", "import warnings\nwarnings.simplefilter('error')\n"
                                                   "import faulthandler")
    proc = subprocess.run([sys.executable, "-c", script + "print('ok')", str(extension), "1"],
                          capture_output=True, text=True, timeout=120)
    assert proc.returncode == 0, proc.stderr
    assert "global interpreter lock" not in proc.stderr


# --- more than two list parameters stay interpreted --------------------------------------------------

def test_three_list_parameters_stay_interpreted(built):
    result, _, _ = built
    assert "three" not in {f.name for f in result.module.functions}
    reason = result.skipped["three"]
    assert "list" in reason and ("three" in reason or "more than two" in reason), reason


def test_front_end_gives_a_clear_reason(tmp_path):
    p = tmp_path / "m.py"
    p.write_text("# typedpython: compiled\n\n\ndef f(a: list[int], b: list[float], c: list[int]) -> int:\n"
                 "    return len(a)\n")
    m = frontend.lower(p)
    assert not m.functions
    assert "more than two list parameters" in m.skipped["f"], m.skipped["f"]


def test_two_list_parameters_still_compile(built):
    result, _, _ = built
    assert {"zero2", "pair_edge"} <= {f.name for f in result.module.functions}


def test_verifier_rejects_three_array_params(built):
    from typedpython import ir
    result, _, _ = built
    f = next(f for f in result.module.functions if f.name == "zero2")
    third = ir.ArrayParam("zs", ir.Type.I64_ARRAY, False)
    bad = dataclasses.replace(f, params=f.params + (third,))
    mod = dataclasses.replace(result.module, functions=type(result.module.functions)([bad]), skipped={})
    proved, diags = verify.verify(mod)
    assert not proved.functions
    assert any("array" in d.message and "two" in d.message for d in diags), diags


def test_cgen_refuses_three_array_params(built):
    from typedpython import ir
    result, path, _ = built
    f = next(f for f in result.module.functions if f.name == "zero2")
    third = ir.ArrayParam("zs", ir.Type.I64_ARRAY, False)
    bad = dataclasses.replace(f, params=f.params + (third,))
    mod = dataclasses.replace(result.module, functions=type(result.module.functions)([bad]), skipped={})
    with pytest.raises(cgen.CGenError, match="two"):
        cgen.generate(mod, path)


# --- concurrent calls --------------------------------------------------------------------------------

def test_private_lists_in_many_threads(extension):
    r = run_child(extension, """
        N, ROUNDS = 8, 40 * STRESS
        bad = []
        def work(k):
            def go():
                xs = [float(k * 1000 + i) for i in range(500)]
                want = sum(xs)
                for _ in range(ROUNDS):
                    if m.total(xs, 3) != want * 3:
                        bad.append(("total", k))
                    m.fill(xs, float(k))
                    if xs != [float(k)] * 500:
                        bad.append(("fill", k))
                    xs[:] = [float(k * 1000 + i) for i in range(500)]
            return go
        errs = run_threads([work(k) for k in range(N)])
        print(json.dumps({"bad": bad, "errs": errs}))
    """)
    assert r == {"bad": [], "errs": []}


def test_shared_list_only_read(extension):
    r = run_child(extension, """
        shared = [float(i) * 0.5 for i in range(2000)]
        want = sum(shared) * 5
        bad = []
        def go():
            for _ in range(60 * STRESS):
                v = m.total(shared, 5)
                if v != want:
                    bad.append(v)
        errs = run_threads([go for _ in range(8)])
        print(json.dumps({"bad": bad[:3], "errs": errs, "gil": sys._is_gil_enabled()}))
    """)
    assert r == {"bad": [], "errs": [], "gil": False}


def test_shared_list_writers_and_readers_see_whole_calls(extension):
    """`fill` writes every element in one call, `edge` reads the first and last: with the list's
    critical section held from copy-in to write-back, a reader sees a state between two whole calls,
    so first == last, always."""
    r = run_child(extension, """
        shared = [0.0] * 20000
        torn = []
        stop = False
        def writer(v):
            def go():
                for i in range(40 * STRESS):
                    m.fill(shared, v + i)
            return go
        def reader():
            while not stop:
                d = m.edge(shared)
                if d != 0.0:
                    torn.append(d)
        def driver():
            global stop
            errs = run_threads([writer(1000.0 * k) for k in range(1, 4)])
            stop = True
            return errs
        rs = [threading.Thread(target=lambda: errs_r.extend(run_threads([reader] * 3)))]
        errs_r = []
        rs[0].start()
        errs = driver()
        rs[0].join()
        print(json.dumps({"torn": len(torn), "errs": errs + errs_r}))
    """)
    assert r == {"torn": 0, "errs": []}


def test_shared_list_int_writers_and_readers(extension):
    r = run_child(extension, """
        shared = [0] * 20000
        torn = []
        stop = False
        def writer(v):
            def go():
                for i in range(40 * STRESS):
                    m.fill_int(shared, v + i)
            return go
        def reader():
            while not stop:
                d = m.edge_int(shared)
                if d != 0:
                    torn.append(d)
        errs_r = []
        rt = threading.Thread(target=lambda: errs_r.extend(run_threads([reader] * 3)))
        rt.start()
        errs = run_threads([writer(1000 * k) for k in range(1, 4)])
        stop = True
        rt.join()
        print(json.dumps({"torn": len(torn), "errs": errs + errs_r}))
    """)
    assert r == {"torn": 0, "errs": []}


def test_shared_list_read_modify_write_equals_a_serialisation(extension):
    """`rotate` is a read-modify-write of the whole list: T calls from any threads must leave the
    list rotated by exactly T (a lost update or a torn copy would not)."""
    r = run_child(extension, """
        n = 64
        shared = [float(i) for i in range(n)]
        T_PER, THREADS = 150 * STRESS, 6
        errs = run_threads([(lambda: [m.rotate(shared) for _ in range(T_PER)])
                            for _ in range(THREADS)])
        k = (T_PER * THREADS) % n
        want = [float(i) for i in list(range(k, n)) + list(range(k))]
        print(json.dumps({"ok": shared == want, "errs": errs}))
    """)
    assert r == {"ok": True, "errs": []}


def test_two_shared_lists(extension):
    r = run_child(extension, """
        a = [7] * 3000
        b = [9] * 3000
        bad = []
        def zero():
            for _ in range(30 * STRESS):
                m.zero2(a, b)
                m.zero2(b, a)             # the opposite order: the two-list section is ordered
                a[:] = [7] * 3000
                b[:] = [9] * 3000
        def look():
            for _ in range(200 * STRESS):
                d = m.pair_edge(a, b)
                if d not in (7 - 9, 0, 7, -9):
                    bad.append(d)
        errs = run_threads([zero, zero, look, look])
        print(json.dumps({"bad": bad, "errs": errs}))
    """)
    assert r == {"bad": [], "errs": []}


def test_shared_objects_concurrent_fieldset_and_fieldget(extension):
    r = run_child(extension, """
        cell = m.Cell((0,), None)
        written = {(k, i) for k in range(1, 7) for i in range(200 * STRESS)} | {(0,)}
        bad = []
        def writer(k):
            def go():
                for i in range(200 * STRESS):
                    m.seta(cell, (k, i))
            return go
        def reader():
            for _ in range(2000 * STRESS):
                v = m.geta(cell)
                if v not in written:
                    bad.append(v)
        def maker():
            for i in range(500 * STRESS):
                c = m.mk((i,), [i])
                if c.a != (i,) or c.b != [i]:
                    bad.append(("mk", i))
        errs = run_threads([writer(k) for k in range(1, 4)] + [reader] * 3 + [maker] * 2)
        print(json.dumps({"bad": [repr(x) for x in bad[:3]], "errs": errs}))
    """)
    assert r == {"bad": [], "errs": []}


def test_class_modified_while_threads_read_fields(extension):
    r = run_child(extension, """
        cell = m.Cell(11, 22)
        stop = False
        bad = []
        def reader():
            while not stop:
                if m.geta(cell) != 11:
                    bad.append("get")
                c = m.mk(1, 2)
                if (c.a, c.b) != (1, 2):
                    bad.append("mk")
                m.seta(cell, 11)
        def modifier():
            global stop
            for i in range(300 * STRESS):
                m.Cell.counter = i               # every assignment moves the class's version tag
                if i % 7 == 0:
                    m.Cell.other = lambda self: i
            stop = True
        errs_r = []
        rt = threading.Thread(target=lambda: errs_r.extend(run_threads([reader] * 4)))
        rt.start()
        errs = run_threads([modifier])
        rt.join()
        info = m.__typedpython_class_info__()["Cell"]
        print(json.dumps({"bad": bad[:3], "errs": errs + errs_r, "compiled": info["compiled"],
                          "refreshed": info["refreshed"] > 0}))
    """)
    assert r == {"bad": [], "errs": [], "compiled": True, "refreshed": True}


def test_global_rebinding_with_the_dict_watcher(extension):
    """The thread that rebinds `Cell` calls `mk` right after: it must see its own rebinding, however
    many other threads are refreshing the global's cache at the same moment."""
    r = run_child(extension, """
        stop = False
        bad = []
        def hammer():
            while not stop:
                c = m.mk(1, 2)
                if type(c).__name__ not in ("Cell", "Twin") or (c.a, c.b) != (1, 2):
                    bad.append("hammer")
        errs_r = []
        ht = threading.Thread(target=lambda: errs_r.extend(run_threads([hammer] * 4)))
        ht.start()
        orig = m.Cell
        for i in range(10000 * STRESS):
            m.Cell = m.Twin if i % 2 == 0 else orig
            want = "Twin" if i % 2 == 0 else "Cell"
            got = type(m.mk(1, 2)).__name__
            if got != want:
                bad.append((i, got, want))
        stop = True
        ht.join()
        print(json.dumps({"bad": bad[:3], "errs": errs_r}))
    """)
    assert r == {"bad": [], "errs": []}


def test_deopt_counter_does_not_lose_updates(extension):
    r = run_child(extension, """
        N, M = 8, 300 * STRESS
        before = m.__typedpython_deopts__
        res = []
        def go():
            for _ in range(M):
                res.append(m.usek(1.5))        # a float for an int parameter: deopt, interpreted
        errs = run_threads([go for _ in range(N)])
        print(json.dumps({"deopts": m.__typedpython_deopts__ - before, "want": N * M,
                          "same": set(res) == {4.5}, "errs": errs}))
    """)
    assert r["errs"] == [] and r["same"]
    assert r["deopts"] == r["want"]


def test_entry_global_rebinding_in_another_thread(extension):
    r = run_child(extension, """
        stop = False
        bad = []
        def reader():
            while not stop:
                v = m.usek(10)
                if v not in (13, 110, 1010):
                    bad.append(v)
        errs_r = []
        rt = threading.Thread(target=lambda: errs_r.extend(run_threads([reader] * 4)))
        rt.start()
        for i in range(3000 * STRESS):
            m.K = (3, 100, 1000)[i % 3]
        stop = True
        rt.join()
        print(json.dumps({"bad": bad[:3], "errs": errs_r}))
    """)
    assert r == {"bad": [], "errs": []}


# --- the mutation: without the list's critical section the shared-list test must fail ----------------

def _mutant(built, tmp: Path, drop: str) -> Path:
    result, path, _ = built
    c_text = cgen.generate(result.module, path)
    kept = [ln for ln in c_text.split("\n") if drop not in ln]
    assert len(kept) < len(c_text.split("\n")), f"{drop} not found in the generated C"
    return cbuild.build("\n".join(kept), "ftmod", tmp / drop, ext_suffix=None)


def test_mutant_without_the_array_critical_section_is_caught(built, tmp_path):
    so = _mutant(built, tmp_path, "tp_cs_begin")
    detected = False
    for _ in range(3):
        try:
            r = run_child(so, """
                shared = [0.0] * 20000
                torn = []
                stop = False
                def writer(v):
                    def go():
                        for i in range(60):
                            m.fill(shared, v + i)
                    return go
                def reader():
                    while not stop:
                        d = m.edge(shared)
                        if d != 0.0:
                            torn.append(d)
                errs_r = []
                rt = threading.Thread(target=lambda: errs_r.extend(run_threads([reader] * 3)))
                rt.start()
                errs = run_threads([writer(1000.0 * k) for k in range(1, 4)])
                stop = True
                rt.join()
                print(json.dumps({"torn": len(torn)}))
            """)
            detected = r["torn"] > 0
        except AssertionError:
            detected = True                      # a crash is a detection too
        if detected:
            break
    assert detected, "the mutant (no critical section around copy-in) was not caught"


def _racy_runtime(tmp: Path, *, drop_dict_lock: bool, plain_epoch: bool) -> Path:
    """A copy of tp_runtime.h with one #159 protection removed (the mutation of that protection)."""
    h = (cbuild.RUNTIME_DIR / "tp_runtime.h").read_text()
    g = h
    if drop_dict_lock:
        g = g.replace("    Py_BEGIN_CRITICAL_SECTION(module_dict);\n", "")
        g = g.replace("    Py_END_CRITICAL_SECTION();\n    if (rc <= 0) {\n        return rc;",
                      "    if (rc <= 0) {\n        return rc;")
    if plain_epoch:
        g = g.replace("tp_atomic_add_u64(&tp_globals_epoch, 1);", "tp_globals_epoch++;")
        g = g.replace("tp_atomic_load_u64(&tp_globals_epoch)", "tp_globals_epoch")
        g = g.replace("tp_atomic_load_u64(&rt->gepoch)", "rt->gepoch")
        g = g.replace("tp_atomic_store_u64(&rt->gepoch, epoch)", "rt->gepoch = epoch")
    assert g != h
    d = tmp / "rt"
    d.mkdir(parents=True, exist_ok=True)
    (d / "tp_runtime.h").write_text(g)
    return d


def test_mutant_without_the_dict_critical_section_is_caught(built, tmp_path):
    """Without the critical section around the epoch read and the global lookup, a thread can
    remember a binding the rebinding thread has already replaced (the watcher fires before the store)."""
    result, path, _ = built
    rt = _racy_runtime(tmp_path, drop_dict_lock=True, plain_epoch=False)
    so = cbuild.build(cgen.generate(result.module, path), "ftmod", tmp_path / "mut", runtime_dir=rt)
    body = """
        stop = False
        bad = []
        def hammer():
            while not stop:
                m.mk(1, 2)
        ht = threading.Thread(target=lambda: run_threads([hammer] * 6))
        ht.start()
        orig = m.Cell
        for i in range(20000):
            m.Cell = m.Twin if i % 2 == 0 else orig
            if type(m.mk(1, 2)).__name__ != ("Twin" if i % 2 == 0 else "Cell"):
                bad.append(i)
        stop = True
        ht.join()
        print(json.dumps({"stale": len(bad)}))
    """
    stale = 0
    for _ in range(3):
        stale = run_child(so, body)["stale"]
        if stale:
            break
    assert stale > 0, "the mutant (no dict critical section) was not caught"


# --- ThreadSanitizer (best effort) --------------------------------------------------------------------
# The interpreter is not instrumented, so TSan sees races between two accesses made by generated C or
# the header (the epoch, the class state) but NOT a missing list lock (the list is written by CPython).

TSAN_SCRIPT = """
cell = m.Cell(1, 2)
stop = False
def hammer():
    while not stop:
        c = m.mk(1, 2); m.geta(cell); m.seta(cell, 5)
ht = threading.Thread(target=lambda: run_threads([hammer] * 3)); ht.start()
orig = m.Cell
for i in range(300):
    m.Cell = m.Twin if i % 2 == 0 else orig
    type(m.mk(1, 2))
    m.Cell.counter = i
stop = True; ht.join()
shared = [float(i) for i in range(500)]
run_threads([(lambda: [m.rotate(shared) for _ in range(50)]) for _ in range(4)])
run_threads([(lambda: [m.fill(shared, 1.0) or m.total(shared, 2) for _ in range(50)]) for _ in range(4)])
run_threads([(lambda: [m.usek(1.5) for _ in range(100)]) for _ in range(4)])
print("TSAN-RUN-DONE")
"""


def _tsan_launcher(tmp: Path) -> Path | None:
    if sys.platform != "darwin" and not sys.platform.startswith("linux"):
        return None
    cc = (sysconfig.get_config_var("CC") or "cc").split()
    lc, launcher = tmp / "tsan_launcher.c", tmp / "tsan_python"
    lc.write_text("#include <Python.h>\nint main(int argc, char **argv) { return Py_BytesMain(argc, argv); }\n")
    libdir = sysconfig.get_config_var("LIBDIR")
    link = subprocess.run(cc + ["-fsanitize=thread", "-g", f"-I{sysconfig.get_paths()['include']}", str(lc),
                                f"-L{libdir}", f"-lpython{sysconfig.get_config_var('LDVERSION')}",
                                f"-Wl,-rpath,{libdir}", "-o", str(launcher)],
                          capture_output=True, text=True)
    return launcher if link.returncode == 0 else None


def _tsan_reports(built, tmp: Path, launcher: Path, rt: Path | None) -> tuple[int, bool]:
    result, path, _ = built
    flags = list(cbuild.DEFAULT_FLAGS) + ["-O1", "-g", "-fno-omit-frame-pointer", "-fsanitize=thread"]
    so = cbuild.build(cgen.generate(result.module, path), "ftmod", tmp / ("so-" + (rt.parent.name if rt else "real")),
                      runtime_dir=rt, flags=flags)
    env = dict(os.environ, PYTHONHOME=sys.base_prefix,
               TSAN_OPTIONS="halt_on_error=0:report_signal_unsafe=0:history_size=4")
    env.pop("PYTHON_GIL", None)
    env.pop("DYLD_INSERT_LIBRARIES", None)
    run = subprocess.run([str(launcher), "-c", LOADER + textwrap.dedent(TSAN_SCRIPT), str(so), "1"],
                         capture_output=True, text=True, env=env, timeout=600)
    out = run.stdout + run.stderr
    return out.count("WARNING: ThreadSanitizer"), "TSAN-RUN-DONE" in out


@pytest.fixture(scope="module")
def tsan_launcher(tmp_path_factory):
    d = tmp_path_factory.mktemp("tsan")
    launcher = _tsan_launcher(d)
    if launcher is None:
        pytest.skip("cannot link a ThreadSanitizer launcher on this host")
    return d, launcher


def test_threadsanitizer_reports_nothing_on_the_extension(built, tsan_launcher):
    d, launcher = tsan_launcher
    reports, done = _tsan_reports(built, d, launcher, None)
    assert done and reports == 0, f"{reports} ThreadSanitizer report(s) (done={done})"


def test_threadsanitizer_sees_a_plain_epoch(built, tsan_launcher):
    """The check can fail: with the epoch back to plain loads and increments TSan reports the race
    between the watcher callback and the readers."""
    d, launcher = tsan_launcher
    rt = _racy_runtime(d / "racy", drop_dict_lock=True, plain_epoch=True)
    reports, done = _tsan_reports(built, d, launcher, rt)
    assert done and reports > 0
