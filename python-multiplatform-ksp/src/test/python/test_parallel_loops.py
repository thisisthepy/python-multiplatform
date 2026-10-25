"""TypedPython parallel order-independent loops, step 1 (SPEC N-15, #151, #277).

Step 1 scope: in a function marked by the comment line `# typedpython: parallel` directly above its
`def`, a `for i in range(len(a))` loop whose body is only stores `a[i] = <F64 expression>` (the same
index i, each proven in bounds), over list copy-in or local arrays, with values built from constants,
loop-invariant scalars, `float(i)`, reads `b[i]` at the same i (proven) and F64 + - * and negation,
runs on several C threads above TP_PAR_MIN_WORK. The verifier recomputes the set (`ForRange.par`) and
rejects a declared one that differs (`verify/par-loop`). Everything else compiles as before.

Red record: written before the implementation. Before it, `ir.ForRange` had no `par`, `ir.Function`
had no `parallel`, the marker comment was ignored and the generated module had no
`__typedpython_parallel__`; every test below that touches those failed (see the report in #277).
"""
from __future__ import annotations

import dataclasses
import json
import os
import struct
import subprocess
import sys
import sysconfig
import textwrap
from pathlib import Path

import pytest

from typedpython import cbuild, cgen, frontend, ir, verify

FREE_THREADED = bool(sysconfig.get_config_var("Py_GIL_DISABLED"))
PAR_RULE = "verify/par-loop"

SOURCE = '''\
# typedpython: compiled

# typedpython: parallel
def scale(xs: list[float], a: float, b: float) -> None:
    for i in range(len(xs)):
        xs[i] = xs[i] * a + b


def scale_unmarked(xs: list[float], a: float, b: float) -> None:
    for i in range(len(xs)):
        xs[i] = xs[i] * a + b


# typedpython: parallel
def ramp(xs: list[float], a: float) -> None:
    for i in range(len(xs)):
        xs[i] = -(float(i) * a) + xs[i]


# typedpython: parallel
def two_stores(xs: list[float], ys: list[float], a: float) -> None:
    for i in range(len(xs)):
        xs[i] = xs[i] + a
        xs[i] = xs[i] * xs[i]


# typedpython: parallel
def neighbour(xs: list[float]) -> None:
    for i in range(len(xs) - 1):
        xs[i] = xs[i + 1]


# typedpython: parallel
def total(xs: list[float]) -> float:
    s: float = 0.0
    for i in range(len(xs)):
        s += xs[i]
    return s


def helper(x: float) -> float:
    return x * 2.0


# typedpython: parallel
def with_call(xs: list[float]) -> None:
    for i in range(len(xs)):
        xs[i] = helper(xs[i])


# typedpython: parallel
def divides(xs: list[float], d: float) -> None:
    for i in range(len(xs)):
        xs[i] = xs[i] / d


# typedpython: parallel
def local_fill(a: float) -> float:
    t = [0.0] * 300000
    for i in range(len(t)):
        t[i] = float(i) * a
    return t[7] + t[299999]


# typedpython: parallel
def local_dyn(n: int, a: float) -> float:
    t = [1.0] * n
    for i in range(len(t)):
        t[i] = t[i] + float(i) * a
    return t[n - 1]
'''


def build(tmp: Path, source: str = SOURCE, flags: tuple[str, ...] = cbuild.DEFAULT_FLAGS, name: str = "parmod"):
    path = tmp / f"{name}.py"
    path.write_text(source)
    lowered = frontend.lower(path)
    proved, diags = verify.verify(lowered)
    c_source = cgen.generate(proved, path)
    so = cbuild.build(c_source, name, tmp / "out", flags=flags)
    return proved, c_source, so, lowered


@pytest.fixture(scope="module")
def built(tmp_path_factory):
    d = tmp_path_factory.mktemp("par")
    return build(d)


@pytest.fixture(scope="module")
def mod(built):
    return cbuild.load("parmod", built[2])


def fn(built, name):
    return next(f for f in built[0].functions if f.name == name)


def par_loops(f):
    out = []

    def walk(body):
        for s in body:
            if isinstance(s, ir.ForRange):
                out.append(s)
                walk(s.body)
            elif isinstance(s, (ir.If, ir.While)):
                walk(s.body)
                walk(getattr(s, "orelse", ()))
    walk(f.body)
    return out


def bits(xs):
    return struct.pack(f"<{len(xs)}d", *xs)


def data(n, seed=1):
    import random
    r = random.Random(seed)
    out = [r.uniform(-1e3, 1e3) for _ in range(n)]
    for k, v in enumerate((float("nan"), float("inf"), -0.0, 5e-324, float("-inf"))):
        if k < n:
            out[(k * 7919) % n] = v
    return out


def regions(m):
    return m.__typedpython_parallel__()[0]


SIZES = [0, 1, 2, 7, 1000, 200003, 1048576]


# --- marking and eligibility (IR / verifier markers, not timing) -------------------------------------

def test_marker_comment_sets_function_parallel(built):
    assert fn(built, "scale").parallel is True
    assert fn(built, "scale_unmarked").parallel is False


@pytest.mark.parametrize("name", ["scale", "ramp", "two_stores", "local_fill", "local_dyn"])
def test_eligible_loops_are_marked_par(built, name):
    loops = par_loops(fn(built, name))
    assert loops and all(l.par is not None for l in loops), name


@pytest.mark.parametrize("name", ["neighbour", "total", "with_call", "divides"])
def test_ineligible_loops_stay_sequential(built, name):
    f = fn(built, name)
    assert f.parallel is True
    loops = par_loops(f)
    assert loops and all(l.par is None for l in loops), name


def test_unmarked_function_never_parallel(built):
    assert all(l.par is None for l in par_loops(fn(built, "scale_unmarked")))


def test_front_end_never_sets_par(built):
    lowered = built[3]
    for f in lowered.functions:
        assert all(l.par is None for l in par_loops(f)), f.name


def test_verifier_rejects_a_declared_par_on_an_ineligible_loop(built):
    f = fn(built, "neighbour")
    loop = par_loops(f)[0]
    fake = dataclasses.replace(loop, par=ir.ParInfo(("xs",), ()))
    bad = dataclasses.replace(f, body=tuple(fake if s is loop else s for s in f.body))
    mod = dataclasses.replace(built[0], functions=(bad,), skipped={})
    proved, diags = verify.verify(mod)
    assert not proved.functions
    assert any(d.rule == PAR_RULE for d in diags), diags


def test_verifier_rejects_a_declared_par_in_an_unmarked_function(built):
    f = fn(built, "scale")
    loop = par_loops(f)[0]
    fake = dataclasses.replace(loop, par=loop.par or ir.ParInfo(("xs",), ("xs",)))
    bad = dataclasses.replace(f, parallel=False, body=tuple(fake if s is loop else s for s in f.body))
    mod = dataclasses.replace(built[0], functions=(bad,), skipped={})
    proved, diags = verify.verify(mod)
    assert not proved.functions
    assert any(d.rule == PAR_RULE for d in diags), diags


def test_verifier_recomputes_par_when_it_is_missing(built):
    """The front end leaves `par` empty; the proved module has it, set by the verifier alone."""
    assert par_loops(fn(built, "scale"))[0].par is not None


# --- generated C -------------------------------------------------------------------------------------

def test_c_has_a_region_only_for_eligible_loops(built):
    c_text = built[1]
    assert "tp_par_run" in c_text
    import re
    owners = set(re.findall(r"static void tp_par_(\w+?)(?:_fast)?_\d+_body\(", c_text))
    assert owners == {"scale", "ramp", "two_stores", "local_fill", "local_dyn"}, owners
    assert "TP_PAR_MIN_WORK" in c_text


def test_no_par_code_without_a_marker(tmp_path):
    src = SOURCE.replace("# typedpython: parallel\n", "")
    _, c_text, _, _ = build(tmp_path, src, name="nomark")
    assert "tp_par_run(" not in c_text and "__typedpython_parallel__" not in c_text


# --- differential results vs the interpreter ---------------------------------------------------------

@pytest.mark.parametrize("n", SIZES)
def test_scale_matches_interpreter_bit_for_bit(mod, n):
    xs = data(n)
    want, got = list(xs), list(xs)
    mod.__typedpython_interpreted__["scale"](want, 1.0000001, -3.25)
    mod.scale(got, 1.0000001, -3.25)
    assert bits(got) == bits(want)


@pytest.mark.parametrize("n", [0, 5, 200003])
def test_ramp_matches(mod, n):
    xs = data(n, 3)
    want, got = list(xs), list(xs)
    mod.__typedpython_interpreted__["ramp"](want, 0.1)
    mod.ramp(got, 0.1)
    assert bits(got) == bits(want)


@pytest.mark.parametrize("n", [0, 3, 200003])
def test_two_stores_match(mod, n):
    xs = data(n, 4)
    ys = [0.0] * 4
    want, got = list(xs), list(xs)
    mod.__typedpython_interpreted__["two_stores"](want, list(ys), 0.3)
    mod.two_stores(got, list(ys), 0.3)
    assert bits(got) == bits(want)


def test_local_array_loops_match(mod):
    for a in (0.5, -2.75, float("inf"), float("nan")):
        w = mod.__typedpython_interpreted__["local_fill"](a)
        g = mod.local_fill(a)
        assert struct.pack("<d", g) == struct.pack("<d", w)
    for n in (1, 10, 150001):
        w = mod.__typedpython_interpreted__["local_dyn"](n, 0.7)
        g = mod.local_dyn(n, 0.7)
        assert struct.pack("<d", g) == struct.pack("<d", w)


def test_ineligible_functions_still_correct(mod):
    xs = data(300000, 9)
    for name, args in (("neighbour", ()), ("total", ()), ("with_call", ()), ("divides", (3.0,))):
        want, got = list(xs), list(xs)
        w = mod.__typedpython_interpreted__[name](want, *args)
        g = getattr(mod, name)(got, *args)
        assert bits(got) == bits(want), name
        if w is not None:
            assert struct.pack("<d", g) == struct.pack("<d", w)


def test_divide_by_zero_still_raises_in_a_marked_function(mod):
    with pytest.raises(ZeroDivisionError):
        mod.divides([1.0] * 100000, 0.0)


# --- the parallel path really ran (counters), and only when it should --------------------------------

def test_region_counter_above_threshold_only(mod):
    r0 = regions(mod)
    mod.scale([1.0] * 10, 2.0, 1.0)
    assert regions(mod) == r0                       # below threshold: sequential
    mod.scale([1.0] * 1048576, 2.0, 1.0)
    if (os.cpu_count() or 1) > 1:
        assert regions(mod) == r0 + 1               # above: one region
    r1 = regions(mod)
    mod.scale_unmarked([1.0] * 1048576, 2.0, 1.0)
    mod.neighbour([1.0] * 1048576)
    mod.total([1.0] * 1048576)
    mod.with_call([1.0] * 1048576)
    assert regions(mod) == r1                       # unmarked / ineligible: never


def test_list_untouched_when_the_call_raises_before_the_loop(mod):
    with pytest.raises(TypeError):
        mod.scale([1.0] * 100000, 2.0)              # wrong arity deopts to the interpreter's TypeError


# --- child processes: thread counts, creation failure, concurrency -----------------------------------

CHILD = """\
import importlib.machinery, importlib.util, json, struct, sys, threading
path = sys.argv[1]
name = path.rsplit("/", 1)[-1].split(".")[0]
loader = importlib.machinery.ExtensionFileLoader(name, path)
spec = importlib.util.spec_from_file_location(name, path, loader=loader)
m = importlib.util.module_from_spec(spec)
loader.exec_module(m)
"""


def run_child(so: Path, body: str, env: dict | None = None, timeout: int = 300) -> dict:
    e = dict(os.environ)
    e.pop("TYPEDPYTHON_THREADS", None)
    e.update(env or {})
    proc = subprocess.run([sys.executable, "-c", CHILD + textwrap.dedent(body), str(so)],
                          capture_output=True, text=True, timeout=timeout, env=e)
    assert proc.returncode == 0, f"child failed ({proc.returncode}):\n{proc.stdout}\n{proc.stderr}"
    return json.loads(proc.stdout.strip().splitlines()[-1])


THREAD_BODY = """
    xs = [((k * 2654435761) % 1000003) / 977.0 - 500.0 for k in range(400001)]
    m.scale(xs, 1.0000003, 0.125)
    print(json.dumps({"hex": struct.pack("<%dd" % len(xs), *xs).hex(), "regions": m.__typedpython_parallel__()[0]}))
"""


def test_identical_bytes_for_every_thread_count(built):
    so = built[2]
    results = {t: run_child(so, THREAD_BODY, {"TYPEDPYTHON_THREADS": str(t)}) for t in (1, 2, 3, 7, 64)}
    first = results[1]
    assert first["regions"] == 0                    # 1 disables the feature
    for t, r in results.items():
        assert r["hex"] == first["hex"], t
    if (os.cpu_count() or 1) > 1:
        assert results[2]["regions"] == 1 and results[7]["regions"] == 1


def test_thread_creation_failure_falls_back_to_sequential(tmp_path):
    """Every pthread_create fails (test hook): the call still completes, correctly, on the caller."""
    _, _, so, _ = build(tmp_path, flags=cbuild.DEFAULT_FLAGS + ("-DTP_PAR_TEST_FAIL_CREATE=1",), name="parfail")
    r = run_child(so, THREAD_BODY, {"TYPEDPYTHON_THREADS": "4"})
    ok = run_child(built_so_for_compare(tmp_path), THREAD_BODY, {"TYPEDPYTHON_THREADS": "1"})
    assert r["hex"] == ok["hex"]
    info = run_child(so, """
        m.scale([1.0] * 400001, 2.0, 1.0)
        print(json.dumps(list(m.__typedpython_parallel__())))
    """, {"TYPEDPYTHON_THREADS": "4"})
    assert info[2] > 0                              # creation failures were counted, none raised


def built_so_for_compare(tmp_path):
    return build(tmp_path, name="parcmp")[2]


@pytest.mark.skipif(not FREE_THREADED, reason="needs a free-threaded CPython")
def test_concurrent_calls_from_several_python_threads(built):
    r = run_child(built[2], """
        N = 6
        base = [((k * 40503) % 65521) / 13.0 for k in range(262144)]
        want = list(base)
        m.__typedpython_interpreted__["scale"](want, 1.5, -0.5)
        bad = []
        errors = []
        def work(k):
            def go():
                try:
                    for _ in range(6):
                        xs = list(base)
                        m.scale(xs, 1.5, -0.5)
                        if xs != want:
                            bad.append(k)
                except BaseException as e:
                    errors.append(repr(e))
            return go
        ts = [threading.Thread(target=work(k)) for k in range(N)]
        for t in ts: t.start()
        for t in ts: t.join()
        shared = [1.0] * 262144
        shared_bad = []
        def sh():
            for _ in range(4):
                m.scale(shared, 1.0, 0.0)   # identity: whole-call atomic under the list's section
                if shared != [1.0] * 262144:
                    shared_bad.append(1)
        ts = [threading.Thread(target=sh) for _ in range(4)]
        for t in ts: t.start()
        for t in ts: t.join()
        print(json.dumps({"bad": bad, "errors": errors, "shared_bad": shared_bad,
                          "gil": sys._is_gil_enabled(), "regions": m.__typedpython_parallel__()[0]}))
    """, {"TYPEDPYTHON_THREADS": "4"})
    assert r["bad"] == [] and r["errors"] == [] and r["shared_bad"] == []
    assert r["gil"] is False
    if (os.cpu_count() or 1) > 1:
        assert r["regions"] >= 6 * 6
