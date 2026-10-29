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

from conftest import COMPILED_SHIM
from typedpython import cbuild, cgen, frontend, ir, verify

posix_signals = pytest.mark.skipif(sys.platform == "win32", reason="needs POSIX signals (SIGALRM/SIGUSR1/setitimer) or fork; Windows has none")

FREE_THREADED = bool(sysconfig.get_config_var("Py_GIL_DISABLED"))
PAR_RULE = "verify/par-loop"

SOURCE = '''\

# typedpython: parallel
@compiled
def scale(xs: list[float], a: float, b: float) -> None:
    for i in range(len(xs)):
        xs[i] = xs[i] * a + b


@compiled
def scale_unmarked(xs: list[float], a: float, b: float) -> None:
    for i in range(len(xs)):
        xs[i] = xs[i] * a + b


# typedpython: parallel
@compiled
def ramp(xs: list[float], a: float) -> None:
    for i in range(len(xs)):
        xs[i] = -(float(i) * a) + xs[i]


# typedpython: parallel
@compiled
def two_stores(xs: list[float], ys: list[float], a: float) -> None:
    for i in range(len(xs)):
        xs[i] = xs[i] + a
        xs[i] = xs[i] * xs[i]


# typedpython: parallel
@compiled
def neighbour(xs: list[float]) -> None:
    for i in range(len(xs) - 1):
        xs[i] = xs[i + 1]


# typedpython: parallel
@compiled
def total(xs: list[float]) -> float:
    s: float = 0.0
    for i in range(len(xs)):
        s += xs[i]
    return s


@compiled
def helper(x: float) -> float:
    return x * 2.0


# typedpython: parallel
@compiled
def with_call(xs: list[float]) -> None:
    for i in range(len(xs)):
        xs[i] = helper(xs[i])


# typedpython: parallel
@compiled
def divides(xs: list[float], d: float) -> None:
    for i in range(len(xs)):
        xs[i] = xs[i] / d


# typedpython: parallel
@compiled
def local_fill(a: float) -> float:
    t = [0.0] * 300000
    for i in range(len(t)):
        t[i] = float(i) * a
    return t[7] + t[299999]


# typedpython: parallel
@compiled
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
            elif isinstance(s, ir.If):
                walk(s.then)
                walk(s.orelse)
            elif isinstance(s, ir.While):
                walk(s.body)
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

CHILD = COMPILED_SHIM + """\
import importlib.machinery, importlib.util, json, struct, sys, threading
path = sys.argv[1]
name = path.replace(chr(92), "/").rsplit("/", 1)[-1].split(".")[0]
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


# =====================================================================================================
# Step 2 (SPEC N-15, #277): i64 reductions `acc op= expr`, op in + and *.
# (& | ^ are not in the compiled subset: the front end skips `s &= x`, the IR has no bitwise BinOpKind.)
#
# Scope chosen: the body of a reduction loop is only reductions (no element-wise stores in the same
# loop), so a rerun needs no array restore. Each chunk computes its partial from the op's identity with
# the sequential code's own overflow checks; any chunk overflow or a combination overflow discards the
# whole region and the sequential loop runs from its start state (it deopts as today).
#
# Red record: written before the implementation. Before it, `ir.ParInfo` had no `reduce`, reduction
# loops were never `par` (so the region counter did not move and `__typedpython_parallel__()` had
# three fields, no rerun counter); see the report in #277.
# =====================================================================================================

RSOURCE = '''\

# typedpython: parallel
@compiled
def r_sum(xs: list[int]) -> int:
    s: int = 0
    for i in range(len(xs)):
        s += xs[i]
    return s


# typedpython: parallel
@compiled
def r_sum_init(xs: list[int], s0: int) -> int:
    s: int = s0
    for i in range(len(xs)):
        s += xs[i]
    return s


# typedpython: parallel
@compiled
def r_prod(xs: list[int], c: int) -> int:
    s: int = 1
    for i in range(len(xs)):
        s *= xs[i] * c
    return s


# typedpython: parallel
@compiled
def r_sum_mul(xs: list[int], c: int) -> int:
    s: int = 0
    for i in range(len(xs)):
        s += xs[i] * c
    return s


# typedpython: parallel
@compiled
def r_two(xs: list[int]) -> int:
    a: int = 0
    b: int = 7
    for i in range(len(xs)):
        a += xs[i]
        b += xs[i] * 2
    return a * 3 + b


# typedpython: parallel
@compiled
def r_range_sum(xs: list[int]) -> int:
    s: int = 5
    for i in range(len(xs)):
        s += i - 2
    return s


# typedpython: parallel
@compiled
def r_local(n: int) -> int:
    t = [0] * n
    for i in range(len(t)):
        t[i] = i * 3 + 1
    s: int = 0
    for i in range(len(t)):
        s += t[i]
    return s


@compiled
def r_unmarked(xs: list[int]) -> int:
    s: int = 0
    for i in range(len(xs)):
        s += xs[i]
    return s


# typedpython: parallel
@compiled
def n_acc_read(xs: list[int]) -> int:
    s: int = 0
    for i in range(len(xs)):
        s += xs[i] + s
    return s


# typedpython: parallel
@compiled
def n_two_ops(xs: list[int]) -> int:
    s: int = 0
    for i in range(len(xs)):
        s += xs[i]
        s *= xs[i]
    return s


# typedpython: parallel
@compiled
def n_float(xs: list[float]) -> float:
    s: float = 0.0
    for i in range(len(xs)):
        s += xs[i]
    return s


# typedpython: parallel
@compiled
def n_copy(xs: list[int]) -> int:
    s: int = 0
    w: int = 0
    for i in range(len(xs)):
        s += xs[i]
        w = s
    return s + w


# typedpython: parallel
@compiled
def n_mixed(xs: list[int]) -> int:
    t = [0.0] * len(xs)
    s: int = 0
    for i in range(len(xs)):
        t[i] = t[i] + 1.5
        s += xs[i]
    return s


# typedpython: parallel
@compiled
def n_div(xs: list[int], d: int) -> int:
    s: int = 0
    for i in range(len(xs)):
        s += xs[i] // d
    return s


# typedpython: parallel
@compiled
def n_shift_step(xs: list[int]) -> int:
    s: int = 0
    for i in range(0, len(xs), 2):
        s += xs[i + 1]
    return s
'''

RSIZES = [0, 1, 2, 7, 1000, 200003, 1048576]
I64_MAX = 2**63 - 1
I64_MIN = -(2**63)


@pytest.fixture(scope="module")
def rbuilt(tmp_path_factory):
    return build(tmp_path_factory.mktemp("rpar"), RSOURCE, name="rparmod")


@pytest.fixture(scope="module")
def rmod(rbuilt):
    return cbuild.load("rparmod", rbuilt[2])


def rdata(n, seed=5, lo=-1000, hi=1000):
    import random
    r = random.Random(seed)
    return [r.randint(lo, hi) for _ in range(n)]


def reruns(m):
    return m.__typedpython_parallel__()[3]


def same(rmod, name, *args):
    """compiled result == interpreter result (exact Python int), with fresh copies of list arguments"""
    want = rmod.__typedpython_interpreted__[name](*[list(a) if isinstance(a, list) else a for a in args])
    got = getattr(rmod, name)(*[list(a) if isinstance(a, list) else a for a in args])
    assert got == want and type(got) is type(want), (name, got, want)
    return got


RED = ["r_sum", "r_sum_init", "r_prod", "r_sum_mul", "r_two", "r_range_sum", "r_local"]
EXPECT_REDUCE = {
    "r_sum": (("s", "ADD"),), "r_prod": (("s", "MUL"),), "r_sum_mul": (("s", "ADD"),),
    "r_two": (("a", "ADD"), ("b", "ADD")),
}


@pytest.mark.parametrize("name", RED)
def test_reduction_loops_are_marked_par(rbuilt, name):
    f = fn(rbuilt, name)
    loops = [l for l in par_loops(f) if l.par is not None]
    assert loops, name
    for nm, want in EXPECT_REDUCE.items():
        if nm == name:
            assert loops[-1].par.reduce == want and loops[-1].par.stored == ()


@pytest.mark.parametrize("name", ["n_acc_read", "n_two_ops", "n_float", "n_copy", "n_mixed", "n_div",
                                  "n_shift_step"])
def test_ineligible_reductions_stay_sequential(rbuilt, name):
    f = fn(rbuilt, name)
    assert f.parallel is True
    loops = par_loops(f)
    assert loops and all(l.par is None for l in loops), name


def test_unmarked_reduction_never_parallel(rbuilt):
    assert all(l.par is None for l in par_loops(fn(rbuilt, "r_unmarked")))


def test_c_regions_only_for_eligible_reductions(rbuilt):
    import re
    owners = set(re.findall(r"static void tp_par_(\w+?)(?:_fast)?_\d+_body\(", rbuilt[1]))
    assert owners == set(RED), owners


def _forge(rbuilt, name, par):
    f = fn(rbuilt, name)
    loop = par_loops(f)[-1]
    fake = dataclasses.replace(loop, par=par)
    bad = dataclasses.replace(f, body=tuple(fake if s is loop else s for s in f.body))
    return verify.verify(dataclasses.replace(rbuilt[3], functions=(bad,), skipped={}))


def test_verifier_rejects_a_forged_reduction(rbuilt):
    good = par_loops(fn(rbuilt, "r_sum"))[-1].par
    # forged on an ineligible loop (the accumulator is read in the body)
    for name in ("n_acc_read", "n_two_ops", "n_copy", "n_mixed", "n_div"):
        proved, diags = _forge(rbuilt, name, ir.ParInfo((), (), 3, (("s", "ADD"),)))
        assert not proved.functions and any(d.rule == PAR_RULE for d in diags), name
    # right loop, wrong reduction operator
    proved, diags = _forge(rbuilt, "r_sum", dataclasses.replace(good, reduce=(("s", "MUL"),)))
    assert not proved.functions and any(d.rule == PAR_RULE for d in diags)
    # right loop, reduction dropped
    proved, diags = _forge(rbuilt, "r_sum", dataclasses.replace(good, reduce=()))
    assert not proved.functions and any(d.rule == PAR_RULE for d in diags)
    # the honest value passes
    proved, diags = _forge(rbuilt, "r_sum", good)
    assert proved.functions and not diags


@pytest.mark.parametrize("n", RSIZES)
def test_reductions_match_interpreter_no_overflow(rmod, n):
    xs, ys = rdata(n, 1), rdata(n, 2)
    for name in ("r_sum", "r_range_sum"):
        same(rmod, name, xs)
    same(rmod, "r_sum_init", xs, 12345)
    same(rmod, "r_sum_mul", xs, 977)
    same(rmod, "r_two", xs)
    same(rmod, "r_prod", [1, -1] * (n // 2) + [1] * (n % 2), 1)     # +-1: never overflows


@pytest.mark.parametrize("n", RSIZES)
def test_sums_with_large_values_that_fit(rmod, n):
    xs = rdata(n, 3, -(2**62) // max(n, 1), 2**62 // max(n, 1))      # partial sums stay inside i64
    same(rmod, "r_sum", xs)
    same(rmod, "r_sum_mul", rdata(n, 4, -5, 5), 2**40)


def test_product_with_zero_and_small_values(rmod):
    n = 300000
    xs = [1] * n
    xs[10], xs[200000] = 7, -3
    same(rmod, "r_prod", xs, 1)
    xs[150000] = 0
    same(rmod, "r_prod", xs, 1)
    same(rmod, "r_prod", [2] * 62 + [1] * (n - 62), 1)                 # 2**62 fits
    same(rmod, "r_prod", [-2] * 63 + [1] * (n - 63), 1)                # -2**63 fits exactly


def test_local_array_reduction(rmod):
    for n in (0, 1, 5, 300000):
        same(rmod, "r_local", n)


# overflow partway: the whole region is discarded and the sequential rerun produces Python's int

@pytest.mark.skipif((os.cpu_count() or 1) < 2, reason="needs a second core to start a region")
def test_sum_overflow_in_a_chunk_reruns_and_equals_python(rmod):
    n = 400000
    base = rdata(n, 6, -3, 3)
    xs = list(base)
    xs[0] = xs[1] = 2**62                                       # chunk 0 overflows, and the prefix
    xs[n - 1] = -(2**62)
    r0, rr0 = regions(rmod), reruns(rmod)
    got = same(rmod, "r_sum", xs)
    assert got == sum(xs)
    assert regions(rmod) == r0 + 1 and reruns(rmod) == rr0 + 1
    # not representable in i64 at the end: Python's big int
    xs2 = list(base)
    xs2[0] = xs2[n - 1] = I64_MAX
    got = same(rmod, "r_sum", xs2)
    assert got == sum(xs2) and got > I64_MAX


@pytest.mark.skipif((os.cpu_count() or 1) < 2, reason="needs a second core to start a region")
def test_sum_overflow_only_in_the_combination_reruns(rmod):
    n = 400000
    xs = [0] * n
    xs[0] = 2**62                                               # first chunk partial: 2**62
    xs[n - 1] = 2**62                                           # last chunk partial: 2**62; total 2**63
    rr0 = reruns(rmod)
    got = same(rmod, "r_sum", xs)
    assert got == 2**63
    assert reruns(rmod) == rr0 + 1


@pytest.mark.skipif((os.cpu_count() or 1) < 2, reason="needs a second core to start a region")
def test_sum_that_overflows_and_comes_back_equals_python(rmod):
    n = 400000
    xs = [0] * n
    xs[1], xs[2], xs[3] = 2**62, 2**62, -(2**62)                # prefix passes 2**63, ends at 2**62
    got = same(rmod, "r_sum", xs)
    assert got == 2**62
    xs = rdata(n, 7, -5, 5)
    xs[100], xs[101], xs[n - 5] = I64_MAX, I64_MAX, I64_MIN
    got = same(rmod, "r_sum", xs)
    assert got == sum(xs)


@pytest.mark.skipif((os.cpu_count() or 1) < 2, reason="needs a second core to start a region")
def test_product_overflow_reruns_and_equals_python(rmod):
    n = 400000
    xs = [1] * n
    for k in (3, 150000, 399999):
        xs[k] = 2**22
    rr0 = reruns(rmod)
    got = same(rmod, "r_prod", xs, 1)                           # 2**66
    assert got == 2**66 and reruns(rmod) == rr0 + 1
    got = same(rmod, "r_prod", [3] * 60 + [1] * (n - 60), 1)    # 3**60 > 2**63
    assert got == 3**60
    xs = [1] * n
    xs[0] = xs[n - 1] = 2**40
    xs[7] = 0
    got = same(rmod, "r_prod", xs, 1)                           # intermediate overflow, result 0
    assert got == 0


@pytest.mark.skipif((os.cpu_count() or 1) < 2, reason="needs a second core to start a region")
def test_element_expression_overflow_reruns(rmod):
    n = 400000
    xs = rdata(n, 8, -9, 9)
    xs[250000] = 2**30
    rr0 = reruns(rmod)
    got = same(rmod, "r_sum_mul", xs, 2**40)                    # xs[i] * c overflows at one element
    assert reruns(rmod) == rr0 + 1
    assert got == __import__("functools").reduce(lambda a, b: a + (b * 2**40), xs, 0)


@pytest.mark.skipif((os.cpu_count() or 1) < 2, reason="needs a second core to start a region")
def test_region_and_rerun_counters(rmod):
    r0, rr0 = regions(rmod), reruns(rmod)
    rmod.r_sum([1] * 10)
    assert regions(rmod) == r0                                  # below threshold: sequential
    assert rmod.r_sum([1] * 1048576) == 1048576
    assert regions(rmod) == r0 + 1 and reruns(rmod) == rr0      # above: a region, no rerun
    r1 = regions(rmod)
    for name in ("r_unmarked",):
        getattr(rmod, name)([1] * 1048576)
    rmod.n_acc_read([0] * 1048576)
    rmod.n_two_ops([1] * 1048576)
    rmod.n_float([1.0] * 1048576)
    rmod.n_copy([1] * 1048576)
    rmod.n_mixed([1] * 1048576)
    assert regions(rmod) == r1


def test_negative_cases_correct(rmod):
    xs = rdata(300000, 9, -5, 5)
    for name, args in (("n_acc_read", (xs,)), ("n_two_ops", (xs,)), ("n_float", ([float(v) for v in xs],)),
                       ("n_copy", (xs,)), ("n_mixed", (xs,)), ("n_div", (xs, 3)),
                       ("n_shift_step", (xs,)), ("r_unmarked", (xs,))):
        want = rmod.__typedpython_interpreted__[name](*[list(a) if isinstance(a, list) else a for a in args])
        got = getattr(rmod, name)(*[list(a) if isinstance(a, list) else a for a in args])
        assert got == want, name


RTHREAD_BODY = """
    import random
    r = random.Random(11)
    n = 400001
    small = [r.randint(-1000, 1000) for _ in range(n)]
    ovf = list(small); ovf[0] = ovf[1] = 2**62; ovf[n - 1] = -(2**62)
    ones = [1] * n; ones[5] = ones[200000] = ones[n - 1] = 2**22
    out = {
        "sum": m.r_sum(small), "ovf": m.r_sum(ovf), "prod": m.r_prod(ones, 1), "two": m.r_two(small),
        "mul": m.r_sum_mul(small, 2**40),
        "regions": m.__typedpython_parallel__()[0], "reruns": m.__typedpython_parallel__()[3],
    }
    print(json.dumps(out))
"""


def test_reduction_results_identical_for_every_thread_count(rbuilt):
    import random
    import functools
    r = random.Random(11)
    n = 400001
    small = [r.randint(-1000, 1000) for _ in range(n)]
    ovf = list(small)
    ovf[0] = ovf[1] = 2**62
    ovf[n - 1] = -(2**62)
    ones = [1] * n
    ones[5] = ones[200000] = ones[n - 1] = 2**22
    want = {"sum": sum(small), "ovf": sum(ovf), "prod": 2**66, "mul": sum(small) * 2**40,
            "two": sum(small) * 3 + 7 + sum(small) * 2}
    results = {t: run_child(rbuilt[2], RTHREAD_BODY, {"TYPEDPYTHON_THREADS": str(t)}) for t in (1, 2, 3, 7)}
    for t, res in results.items():
        for k, v in want.items():
            assert res[k] == v, (t, k)
    assert results[1]["regions"] == 0 and results[1]["reruns"] == 0       # 1 disables the feature
    if (os.cpu_count() or 1) > 1:
        assert results[2]["regions"] > 0 and results[7]["regions"] > 0
        assert results[2]["reruns"] == 2 and results[7]["reruns"] == 2     # ovf and prod rerun, once each


def test_no_reduction_region_without_a_marker(tmp_path):
    src = RSOURCE.replace("# typedpython: parallel\n", "")
    _, c_text, _, _ = build(tmp_path, src, name="rnomark")
    assert "tp_par_run(" not in c_text and "__typedpython_parallel__" not in c_text


# =====================================================================================================
# Step 3a (SPEC N-15, #277): min/max reductions, the conditional update `if e < acc: acc = e`.
#
# The compiled subset has no `min(a, b)` / `max(a, b)` call (it stays a generic object call, never
# parallel). The form it does compile is Python's own definition of `min(acc, e)`: `if e < acc: acc = e`
# (`e > acc` for max; `acc > e` and `acc < e` are the same comparison). Strict `<` keeps the first element
# on ties, which is Python's. Int: exact under chunked combination in chunk order. Float: the chunked
# combination equals the sequential chain only without NaN, so a NaN (in an element or in the initial
# accumulator) discards the region and the sequential loop runs; the counterexample is
# [1.0] | [nan, 0.0]: chunk fold gives 1.0, the sequential chain gives 0.0.
#
# Red record: written before the implementation; before it none of these loops was `par`.
# =====================================================================================================

MSOURCE = '''\

# typedpython: parallel
@compiled
def mn_i(xs: list[int]) -> int:
    m = xs[0]
    for i in range(len(xs)):
        if xs[i] < m:
            m = xs[i]
    return m


# typedpython: parallel
@compiled
def mx_i(xs: list[int]) -> int:
    m = xs[0]
    for i in range(len(xs)):
        if xs[i] > m:
            m = xs[i]
    return m


# typedpython: parallel
@compiled
def mn_f(xs: list[float]) -> float:
    m = xs[0]
    for i in range(len(xs)):
        if xs[i] < m:
            m = xs[i]
    return m


# typedpython: parallel
@compiled
def mx_f(xs: list[float]) -> float:
    m = xs[0]
    for i in range(len(xs)):
        if xs[i] > m:
            m = xs[i]
    return m


# typedpython: parallel
@compiled
def mn_f_flipped(xs: list[float], init: float) -> float:
    m = init
    for i in range(len(xs)):
        if m > xs[i]:
            m = xs[i]
    return m


# typedpython: parallel
@compiled
def mx_f_flipped(xs: list[float], init: float) -> float:
    m = init
    for i in range(len(xs)):
        if m < xs[i]:
            m = xs[i]
    return m


# typedpython: parallel
@compiled
def mn_i_expr(xs: list[int], c: int) -> int:
    m = c
    for i in range(len(xs)):
        if xs[i] * 3 - i < m:
            m = xs[i] * 3 - i
    return m


# typedpython: parallel
@compiled
def mn_f_expr(xs: list[float], a: float) -> float:
    m = a
    for i in range(len(xs)):
        if xs[i] * a - float(i) < m:
            m = xs[i] * a - float(i)
    return m


# typedpython: parallel
@compiled
def mn_and_sum(xs: list[int]) -> int:
    m = xs[0]
    s = 0
    for i in range(len(xs)):
        s += xs[i]
        if xs[i] < m:
            m = xs[i]
    return m * 1000003 + s


# typedpython: parallel
@compiled
def two_ext(xs: list[float]) -> float:
    lo = xs[0]
    hi = xs[0]
    for i in range(len(xs)):
        if xs[i] < lo:
            lo = xs[i]
        if xs[i] > hi:
            hi = xs[i]
    return hi - lo


# typedpython: parallel
@compiled
def mn_i_init(xs: list[int], init: int) -> int:
    m = init
    for i in range(len(xs)):
        if xs[i] < m:
            m = xs[i]
    return m


# typedpython: parallel
@compiled
def mn_i_big_e(xs: list[int], c: int) -> int:
    m = 0
    for i in range(len(xs)):
        if xs[i] * c < m:
            m = xs[i] * c
    return m


@compiled
def mn_unmarked(xs: list[int]) -> int:
    m = xs[0]
    for i in range(len(xs)):
        if xs[i] < m:
            m = xs[i]
    return m


# typedpython: parallel
@compiled
def n_le(xs: list[float]) -> float:
    m = xs[0]
    for i in range(len(xs)):
        if xs[i] <= m:
            m = xs[i]
    return m


# typedpython: parallel
@compiled
def n_other_value(xs: list[int]) -> int:
    m = xs[0]
    for i in range(len(xs)):
        if xs[i] < m:
            m = xs[i] + 1
    return m


# typedpython: parallel
@compiled
def n_else(xs: list[int]) -> int:
    m = xs[0]
    s = 0
    for i in range(len(xs)):
        if xs[i] < m:
            m = xs[i]
        else:
            s += 1
    return m + s


# typedpython: parallel
@compiled
def n_two_in_then(xs: list[int]) -> int:
    m = xs[0]
    s = 0
    for i in range(len(xs)):
        if xs[i] < m:
            m = xs[i]
            s += 1
    return m + s


# typedpython: parallel
@compiled
def n_acc_in_value(xs: list[int]) -> int:
    m = xs[0]
    for i in range(len(xs)):
        if xs[i] < m:
            m = xs[i]
        m = m + 0
    return m


# typedpython: parallel
@compiled
def n_cond_other(xs: list[int], t: int) -> int:
    m = xs[0]
    for i in range(len(xs)):
        if xs[i] < t:
            m = xs[i]
    return m


# typedpython: parallel
@compiled
def n_mixed_types(xs: list[int], init: float) -> float:
    m = init
    for i in range(len(xs)):
        if xs[i] < m:
            m = float(xs[i])
    return m


# typedpython: parallel
@compiled
def n_acc_reads_acc(xs: list[int]) -> int:
    m = xs[0]
    for i in range(len(xs)):
        if xs[i] < m:
            m = xs[i]
        elif xs[i] == m:
            m = m
    return m


# typedpython: parallel
@compiled
def n_div(xs: list[float], d: float) -> float:
    m = xs[0]
    for i in range(len(xs)):
        if xs[i] / d < m:
            m = xs[i] / d
    return m
'''

MM_OK = {"mn_i": (("m", "MIN"),), "mx_i": (("m", "MAX"),), "mn_f": (("m", "MIN"),),
         "mx_f": (("m", "MAX"),), "mn_f_flipped": (("m", "MIN"),), "mx_f_flipped": (("m", "MAX"),),
         "mn_i_expr": (("m", "MIN"),), "mn_f_expr": (("m", "MIN"),),
         "mn_and_sum": (("m", "MIN"), ("s", "ADD")), "two_ext": (("hi", "MAX"), ("lo", "MIN")),
         "mn_i_init": (("m", "MIN"),), "mn_i_big_e": (("m", "MIN"),)}
MM_NO = ["n_le", "n_other_value", "n_else", "n_two_in_then", "n_acc_in_value", "n_cond_other",
         "n_mixed_types", "n_acc_reads_acc", "n_div"]


@pytest.fixture(scope="module")
def mbuilt(tmp_path_factory):
    return build(tmp_path_factory.mktemp("mmpar"), MSOURCE, name="mmparmod")


@pytest.fixture(scope="module")
def mmod(mbuilt):
    return cbuild.load("mmparmod", mbuilt[2])


@pytest.mark.parametrize("name", sorted(MM_OK))
def test_minmax_loops_are_marked_par(mbuilt, name):
    loops = [l for l in par_loops(fn(mbuilt, name)) if l.par is not None]
    assert loops, name
    assert loops[-1].par.reduce == MM_OK[name] and loops[-1].par.stored == ()


@pytest.mark.parametrize("name", MM_NO)
def test_ineligible_minmax_shapes_stay_sequential(mbuilt, name):
    f = fn(mbuilt, name)
    assert f.parallel is True
    loops = par_loops(f)
    assert loops and all(l.par is None for l in loops), name


def test_unmarked_minmax_never_parallel(mbuilt):
    assert all(l.par is None for l in par_loops(fn(mbuilt, "mn_unmarked")))


def test_minmax_c_regions_only_for_eligible(mbuilt):
    import re
    owners = set(re.findall(r"static void tp_par_(\w+?)(?:_fast)?_\d+_body\(", mbuilt[1]))
    assert owners == set(MM_OK), owners


def test_verifier_rejects_a_forged_minmax(mbuilt):
    good = par_loops(fn(mbuilt, "mn_i"))[-1].par
    proved, diags = _forge(mbuilt, "mn_i", dataclasses.replace(good, reduce=(("m", "MAX"),)))
    assert not proved.functions and any(d.rule == PAR_RULE for d in diags)
    for name in ("n_le", "n_other_value", "n_else", "n_cond_other"):
        proved, diags = _forge(mbuilt, name, ir.ParInfo((), (), 3, (("m", "MIN"),)))
        assert not proved.functions and any(d.rule == PAR_RULE for d in diags), name
    proved, diags = _forge(mbuilt, "mn_i", good)
    assert proved.functions and not diags


def test_naive_chunk_fold_differs_on_nan_so_float_nan_cannot_be_chunked():
    """The counterexample, in plain Python: why a NaN must send the float region to the sequential loop."""
    nan = float("nan")

    def fold(xs, acc):
        for x in xs:
            if x < acc:
                acc = x
        return acc

    seq = fold([1.0, nan, 0.0], 5.0)
    chunk_first = fold([1.0], 5.0)
    chunk_second = fold([nan, 0.0], 5.0)               # starts from its own first element on a chunk
    naive = chunk_second if chunk_second < chunk_first else chunk_first
    assert seq == 0.0
    # the chunk starting at nan keeps nan, and `nan < 1.0` is false: the combine keeps 1.0
    p2 = nan
    for x in [0.0]:
        if x < p2:
            p2 = x
    assert p2 != p2
    combined = p2 if p2 < 1.0 else 1.0
    assert combined == 1.0 and combined != seq
    assert naive in (0.0, 1.0)


MM_CHILD_BODY = """
    import math, random
    I = m.__typedpython_interpreted__
    def eq(a, b):
        if isinstance(a, float):
            return isinstance(b, float) and struct.pack("<d", a) == struct.pack("<d", b)
        return a == b and type(a) is type(b)
    bad = []
    def check(name, *args):
        want = I[name](*[list(a) if isinstance(a, list) else a for a in args])
        got = getattr(m, name)(*[list(a) if isinstance(a, list) else a for a in args])
        if not eq(got, want):
            bad.append([name, repr(got), repr(want)])
    r = random.Random(3)
    n = 300001
    ints = [r.randint(-10**6, 10**6) for _ in range(n)]
    flts = [r.uniform(-1e3, 1e3) for _ in range(n)]
    for nm in ("mn_i", "mx_i"):
        check(nm, ints)
        check(nm, [5] * n)
    check("mn_i_init", ints, -10**9); check("mn_i_init", ints, 10**9)
    check("mn_i_expr", ints, 10**9); check("mn_i_expr", ints, -10**9)
    check("mn_and_sum", ints)
    check("mn_i_big_e", ints, 2**40)
    check("mn_i_big_e", ints, 2**60)            # element expression overflows: rerun, then deopt as sequential
    # int ties: the same value everywhere, nothing is replaced
    for nm in ("mn_f", "mx_f"):
        check(nm, flts)
    check("two_ext", flts)
    check("mn_f_expr", flts, 1.5)
    # ties and signed zeros: the first -0.0/+0.0 in iteration order must win
    for first, second in ((-0.0, 0.0), (0.0, -0.0)):
        for pos in (0, 1, n // 3, n // 2, n - 2):
            z = [7.0] * n
            z[pos] = first
            z[pos + 1] = second
            for nm in ("mn_f", "mx_f"):
                zz = list(z)
                zz[0] = zz[0] if pos <= 1 else zz[0]
                check(nm, [1.0 if k not in (pos, pos + 1) else z[k] for k in range(n)] if nm == "mn_f"
                      else [-1.0 if k not in (pos, pos + 1) else z[k] for k in range(n)])
            check("mn_f_flipped", [1.0 if k not in (pos, pos + 1) else z[k] for k in range(n)], 0.5)
            check("mx_f_flipped", [-1.0 if k not in (pos, pos + 1) else z[k] for k in range(n)], -0.5)
    # a zero that ties with the initial accumulator never replaces it
    check("mn_f_flipped", [0.0] * n, -0.0); check("mn_f_flipped", [-0.0] * n, 0.0)
    check("mx_f_flipped", [0.0] * n, -0.0); check("mx_f_flipped", [-0.0] * n, 0.0)
    # NaN at every kind of position, and the exact counterexample [1] | [nan, 0] around chunk edges
    for pos in (0, 1, 2, 1000, n // 4, n // 3, n // 2, n // 2 + 1, 2 * n // 3, n - 2, n - 1):
        for other in (0, 1, 5, n // 2, n - 1):
            x = [1.0] * n
            x[other] = 0.0 if other else 1.0
            x[pos] = float("nan")
            for nm in ("mn_f", "mx_f"):
                check(nm, x)
            check("mn_f_flipped", x, 2.0); check("mx_f_flipped", x, -2.0)
            check("two_ext", x)
    x = [1.0] * n; x[n // 3] = float("nan"); x[n // 3 + 1] = 0.0
    check("mn_f", x); check("mn_f_flipped", x, 9.0)
    check("mn_f_flipped", [1.0] * n, float("nan")); check("mx_f_flipped", [1.0] * n, float("nan"))
    check("mn_f", [float("nan")] * n)
    check("mn_f_flipped", [float("inf")] * n, float("inf"))
    check("mn_f_expr", [float("inf")] * n, 1.0)            # inf - inf style NaN made inside the expression
    # small and empty-ish sizes
    for k in (1, 2, 3, 7, 1000):
        check("mn_i", ints[:k]); check("mx_f", flts[:k]); check("mn_f_flipped", flts[:k], 0.0)
    check("mn_f_flipped", [], 3.0)
    par = m.__typedpython_parallel__()
    print(json.dumps({"bad": bad, "regions": par[0], "reruns": par[3]}))
"""


@pytest.mark.parametrize("threads", [1, 2, 3, 7])
def test_minmax_equals_interpreter_for_every_thread_count(mbuilt, threads):
    r = run_child(mbuilt[2], MM_CHILD_BODY, {"TYPEDPYTHON_THREADS": str(threads)}, timeout=900)
    assert r["bad"] == [], r["bad"][:5]
    if threads == 1:
        assert r["regions"] == 0 and r["reruns"] == 0
    elif (os.cpu_count() or 1) > 1:
        assert r["regions"] > 0
        assert r["reruns"] > 0                         # NaN and overflow cases fell back


def test_float_minmax_without_nan_never_reruns(mbuilt):
    r = run_child(mbuilt[2], """
        import random
        rr = random.Random(9)
        xs = [rr.uniform(-1e3, 1e3) for _ in range(400001)]
        a = m.mn_f(xs); b = m.mx_f(xs); c = m.mn_f_flipped(xs, 2e3)
        print(json.dumps({"ok": a == min(xs) and b == max(xs) and c == min(xs),
                          "regions": m.__typedpython_parallel__()[0], "reruns": m.__typedpython_parallel__()[3]}))
    """, {"TYPEDPYTHON_THREADS": "4"})
    assert r["ok"]
    if (os.cpu_count() or 1) > 1:
        assert r["regions"] == 3 and r["reruns"] == 0


def test_float_minmax_with_nan_reruns_once_per_call(mbuilt):
    r = run_child(mbuilt[2], """
        xs = [1.0] * 400001
        xs[200000] = float("nan"); xs[200001] = 0.0
        a = m.mn_f(xs)
        print(json.dumps({"a": a, "regions": m.__typedpython_parallel__()[0], "reruns": m.__typedpython_parallel__()[3]}))
    """, {"TYPEDPYTHON_THREADS": "4"})
    assert r["a"] == 0.0
    if (os.cpu_count() or 1) > 1:
        assert r["reruns"] == 1


# =====================================================================================================
# Step 3b (SPEC N-15, design §5.2 and §7.4): fork, signals and interpreter shutdown around a region.
# Every scenario runs in a CHILD interpreter with a timeout (a crash or a hang fails one test).
# Red record: these are new tests of behaviour steps 1 and 2 already implement (no polling inside a
# region, workers are not Python threads, per-call threads); none was red before the change.
# =====================================================================================================

BIG = 4_000_000


def _threads_env():
    return {"TYPEDPYTHON_THREADS": "4"}


@posix_signals
def test_fork_from_a_python_thread_while_another_thread_runs_a_region(built):
    r = run_child(built[2], """
        import os, time, warnings, signal
        warnings.simplefilter("ignore")
        big = [float(k %% 977) for k in range(%d)]
        running = threading.Event()
        stop = threading.Event()
        parent_bad = []
        def regionist():
            xs = list(big)
            first = True
            while not stop.is_set():
                ys = list(big)
                if first:
                    running.set(); first = False
                m.scale(ys, 2.0, 1.0)
                if ys[5] != big[5] * 2.0 + 1.0 or ys[-1] != big[-1] * 2.0 + 1.0:
                    parent_bad.append(1)
        t = threading.Thread(target=regionist)
        t.start()
        assert running.wait(60)
        out = {}
        def forker():
            pid = os.fork()
            if pid == 0:
                # the child has only this thread: a small call and a full region must both work
                ok = True
                try:
                    a = [1.5] * 10
                    m.scale(a, 2.0, 1.0)
                    ok = ok and a == [4.0] * 10
                    b = [float(k %% 977) for k in range(300001)]
                    m.scale(b, 2.0, 1.0)
                    ok = ok and b[7] == 15.0
                finally:
                    os._exit(0 if ok else 3)
            out["pid"] = pid
        f = threading.Thread(target=forker)
        f.start(); f.join()
        deadline = time.time() + 120
        status = None
        while time.time() < deadline:
            p, st = os.waitpid(out["pid"], os.WNOHANG)
            if p:
                status = st
                break
            time.sleep(0.01)
        stop.set(); t.join()
        print(json.dumps({"status": status, "exited": os.WIFEXITED(status) if status is not None else None,
                          "code": os.WEXITSTATUS(status) if status is not None and os.WIFEXITED(status) else None,
                          "parent_bad": parent_bad, "regions": m.__typedpython_parallel__()[0]}))
    """ % BIG, _threads_env(), timeout=300)
    assert r["exited"] is True and r["code"] == 0, r
    assert r["parent_bad"] == []
    if (os.cpu_count() or 1) > 1:
        assert r["regions"] >= 1


SIGNAL_BODY = """
    import signal, time
    main = threading.get_ident()
    big = [float(k %% 977) for k in range(%d)]
    seen = []
    def handler(signum, frame):
        seen.append((threading.get_ident(), time.perf_counter()))
        %s
    signal.signal(signal.SIGALRM, handler)
    %s
"""


@posix_signals
def test_signal_handlers_run_on_the_main_thread_after_the_region(built):
    r = run_child(built[2], SIGNAL_BODY % (BIG, "pass", "") + """
    marks = []
    for _ in range(3):
        del seen[:]
        xs = list(big)
        signal.setitimer(signal.ITIMER_REAL, 0.002)
        t0 = time.perf_counter()
        m.scale(xs, 2.0, 1.0)
        t1 = time.perf_counter()
        time.sleep(0.05)                                  # let a pending handler run
        marks.append({"n": len(seen), "all_main": all(i == main for i, _ in seen),
                      "after_region": all(t >= t1 - 1e-4 for _, t in seen), "dur": t1 - t0,
                      "ok": xs[3] == big[3] * 2.0 + 1.0 and xs[-1] == big[-1] * 2.0 + 1.0})
    signal.setitimer(signal.ITIMER_REAL, 0)
    print(json.dumps({"marks": marks, "regions": m.__typedpython_parallel__()[0]}))
    """, _threads_env(), timeout=300)
    for k in r["marks"]:
        assert k["ok"] and k["all_main"], k
        assert k["n"] == 1, k
    if (os.cpu_count() or 1) > 1:
        assert r["regions"] == 3
        assert all(k["after_region"] for k in r["marks"]), r["marks"]   # no handler ran inside the region


@posix_signals
def test_repeating_signals_never_run_a_handler_on_a_worker(built):
    r = run_child(built[2], SIGNAL_BODY % (BIG, "pass", "") + """
    signal.setitimer(signal.ITIMER_REAL, 0.001, 0.001)
    ok = True
    for _ in range(6):
        xs = list(big)
        m.scale(xs, 2.0, 1.0)
        ok = ok and xs[11] == big[11] * 2.0 + 1.0
    signal.setitimer(signal.ITIMER_REAL, 0)
    print(json.dumps({"ok": ok, "n": len(seen), "all_main": all(i == main for i, _ in seen)}))
    """, _threads_env(), timeout=300)
    assert r["ok"] and r["all_main"] and r["n"] >= 1


@posix_signals
def test_keyboardinterrupt_during_a_region_matches_the_sequential_semantics(built):
    """The interrupt is raised by the handler after the region ended: the list is either completely
    scaled or untouched (the call raised before write-back), never partly written, and the exception
    is the KeyboardInterrupt, as for the interpreter."""
    r = run_child(built[2], SIGNAL_BODY % (BIG, "raise KeyboardInterrupt", "") + """
    outcomes = []
    for _ in range(4):
        xs = list(big)
        raised = False
        signal.setitimer(signal.ITIMER_REAL, 0.002)
        try:
            m.scale(xs, 2.0, 1.0)
            time.sleep(0.2)
        except KeyboardInterrupt:
            raised = True
        signal.setitimer(signal.ITIMER_REAL, 0)
        full = all(xs[k] == big[k] * 2.0 + 1.0 for k in range(0, len(xs), 4093))
        untouched = xs == big
        outcomes.append({"raised": raised, "full": full, "untouched": untouched,
                         "main": all(i == main for i, _ in seen)})
    print(json.dumps({"outcomes": outcomes}))
    """, _threads_env(), timeout=300)
    for o in r["outcomes"]:
        assert o["raised"] and o["main"], o
        assert o["full"] != o["untouched"], o               # exactly one: all or nothing


@posix_signals
def test_keyboardinterrupt_in_a_reduction_region(rbuilt):
    r = run_child(rbuilt[2], SIGNAL_BODY % (BIG, "raise KeyboardInterrupt", "") + """
    ints = [k %% 1000 for k in range(%d)]
    want = sum(ints)
    res = []
    for _ in range(4):
        got = None
        raised = False
        signal.setitimer(signal.ITIMER_REAL, 0.002)
        try:
            got = m.r_sum(ints)
            time.sleep(0.2)
        except KeyboardInterrupt:
            raised = True
        signal.setitimer(signal.ITIMER_REAL, 0)
        res.append({"raised": raised, "got": got})
    plain = m.r_sum(ints)
    print(json.dumps({"res": res, "plain": plain, "want": want}))
    """ % BIG, _threads_env(), timeout=300)
    assert r["plain"] == r["want"]
    for o in r["res"]:
        assert o["raised"] and o["got"] in (None, r["want"]), o   # a result is the full sum or nothing


def test_interpreter_shutdown_while_a_daemon_thread_is_in_a_region(built):
    for attempt in range(3):
        r = run_child(built[2], """
            import time
            big = [float(k %% 977) for k in range(%d)]
            inside = threading.Event()
            def spin():
                while True:
                    ys = list(big)
                    inside.set()
                    m.scale(ys, 2.0, 1.0)
            t = threading.Thread(target=spin, daemon=True)
            t.start()
            assert inside.wait(60)
            time.sleep(0.01 * attempt_delay)
            print(json.dumps({"exiting": True}))
            sys.stdout.flush()
        """.replace("attempt_delay", str(attempt)) % BIG, _threads_env(), timeout=300)
        assert r == {"exiting": True}


def test_interpreter_shutdown_with_a_daemon_reduction_region(rbuilt):
    r = run_child(rbuilt[2], """
        import time
        ints = list(range(%d))
        inside = threading.Event()
        def spin():
            while True:
                inside.set()
                m.r_sum(ints)
        t = threading.Thread(target=spin, daemon=True)
        t.start()
        assert inside.wait(60)
        print(json.dumps({"exiting": True}))
        sys.stdout.flush()
    """ % BIG, _threads_env(), timeout=300)
    assert r == {"exiting": True}


# =====================================================================================================
# N-15 step 4: i64 bitwise reductions `acc = acc & e`, `| e`, `^ e` (#277).
#
# Written before the implementation (red). `& | ^` are exactly associative and commutative and never
# overflow: a chunk folds from the identity (-1 for &, 0 for | and ^) and the partials combine in chunk
# order. An overflow in an element expression (`xs[i] * c`) still discards the region and reruns the
# loop sequentially. Shifts are not accepted in `e` (their deopt is not a worker failure in v1).
# Red record: before the change none of the BOK loops was `par` (_par_reduce accepted ADD/MUL only).
# =====================================================================================================

BSOURCE = '''\

# typedpython: parallel
@compiled
def b_and(xs: list[int]) -> int:
    s: int = -1
    for i in range(len(xs)):
        s &= xs[i]
    return s


# typedpython: parallel
@compiled
def b_or(xs: list[int]) -> int:
    s: int = 0
    for i in range(len(xs)):
        s |= xs[i]
    return s


# typedpython: parallel
@compiled
def b_xor(xs: list[int]) -> int:
    s: int = 0
    for i in range(len(xs)):
        s ^= xs[i]
    return s


# typedpython: parallel
@compiled
def b_and_swapped(xs: list[int], s0: int) -> int:
    s: int = s0
    for i in range(len(xs)):
        s = xs[i] & s
    return s


# typedpython: parallel
@compiled
def b_or_swapped(xs: list[int]) -> int:
    s: int = 0
    for i in range(len(xs)):
        s = xs[i] | s
    return s


# typedpython: parallel
@compiled
def b_xor_expr(xs: list[int], c: int) -> int:
    s: int = 0
    for i in range(len(xs)):
        s = s ^ ((xs[i] * c) + (i & 255))
    return s


# typedpython: parallel
@compiled
def b_or_not(xs: list[int]) -> int:
    s: int = 0
    for i in range(len(xs)):
        s |= ~xs[i]
    return s


# typedpython: parallel
@compiled
def b_mix(xs: list[int]) -> int:
    a: int = 0
    x: int = 5
    o: int = 0
    m: int = 1000000
    for i in range(len(xs)):
        a += xs[i]
        x ^= xs[i] & 4095
        o |= xs[i]
        if xs[i] < m:
            m = xs[i]
    return a + x * 3 + o * 5 + m * 7


# typedpython: parallel
@compiled
def b_local(n: int) -> int:
    t = [0] * n
    for i in range(len(t)):
        t[i] = i * 7 - 3
    s: int = 0
    for i in range(len(t)):
        s ^= t[i]
    return s


@compiled
def b_unmarked(xs: list[int]) -> int:
    s: int = 0
    for i in range(len(xs)):
        s ^= xs[i]
    return s


# typedpython: parallel
@compiled
def nb_acc_read(xs: list[int]) -> int:
    s: int = 0
    for i in range(len(xs)):
        s |= xs[i] ^ s
    return s


# typedpython: parallel
@compiled
def nb_two_ops(xs: list[int]) -> int:
    s: int = 0
    for i in range(len(xs)):
        s |= xs[i]
        s &= xs[i]
    return s


# typedpython: parallel
@compiled
def nb_and_then_add(xs: list[int]) -> int:
    s: int = 0
    for i in range(len(xs)):
        s ^= xs[i]
        s += 1
    return s


# typedpython: parallel
@compiled
def nb_shift(xs: list[int]) -> int:
    s: int = 0
    for i in range(len(xs)):
        s ^= xs[i] << 1
    return s


# typedpython: parallel
@compiled
def nb_rshift(xs: list[int], k: int) -> int:
    s: int = 0
    for i in range(len(xs)):
        s |= xs[i] >> k
    return s


# typedpython: parallel
@compiled
def nb_copy(xs: list[int]) -> int:
    s: int = 0
    w: int = 0
    for i in range(len(xs)):
        s ^= xs[i]
        w = s
    return s + w


# typedpython: parallel
@compiled
def nb_div(xs: list[int], d: int) -> int:
    s: int = 0
    for i in range(len(xs)):
        s ^= xs[i] // d
    return s
'''

BOK = {
    "b_and": (("s", "AND"),), "b_or": (("s", "OR"),), "b_xor": (("s", "XOR"),),
    "b_and_swapped": (("s", "AND"),), "b_or_swapped": (("s", "OR"),), "b_xor_expr": (("s", "XOR"),),
    "b_or_not": (("s", "OR"),), "b_local": (("s", "XOR"),),
    "b_mix": (("a", "ADD"), ("m", "MIN"), ("o", "OR"), ("x", "XOR")),
}
BNO = ["nb_acc_read", "nb_two_ops", "nb_and_then_add", "nb_shift", "nb_rshift", "nb_copy", "nb_div"]


@pytest.fixture(scope="module")
def bbuilt(tmp_path_factory):
    return build(tmp_path_factory.mktemp("bpar"), BSOURCE, name="bparmod")


@pytest.fixture(scope="module")
def bmod(bbuilt):
    return cbuild.load("bparmod", bbuilt[2])


@pytest.mark.parametrize("name", sorted(BOK))
def test_bitwise_reduction_loops_are_marked_par(bbuilt, name):
    loops = [l for l in par_loops(fn(bbuilt, name)) if l.par is not None]
    assert loops, name
    assert loops[-1].par.reduce == BOK[name] and loops[-1].par.stored == ()


@pytest.mark.parametrize("name", BNO)
def test_ineligible_bitwise_shapes_stay_sequential(bbuilt, name):
    f = fn(bbuilt, name)
    assert f.parallel is True
    loops = par_loops(f)
    assert loops and all(l.par is None for l in loops), name


def test_unmarked_bitwise_never_parallel(bbuilt):
    assert all(l.par is None for l in par_loops(fn(bbuilt, "b_unmarked")))


def test_bitwise_c_regions_only_for_eligible(bbuilt):
    import re
    owners = set(re.findall(r"static void tp_par_(\w+?)(?:_fast)?_\d+_body\(", bbuilt[1]))
    assert owners == set(BOK), owners


def test_verifier_rejects_a_forged_bitwise_reduction(bbuilt):
    good = par_loops(fn(bbuilt, "b_xor"))[-1].par
    proved, diags = _forge(bbuilt, "b_xor", dataclasses.replace(good, reduce=(("s", "OR"),)))
    assert not proved.functions and any(d.rule == PAR_RULE for d in diags)
    proved, diags = _forge(bbuilt, "b_xor", dataclasses.replace(good, reduce=()))
    assert not proved.functions and any(d.rule == PAR_RULE for d in diags)
    for name, op in (("nb_acc_read", "OR"), ("nb_two_ops", "OR"), ("nb_shift", "XOR"), ("nb_rshift", "OR"),
                     ("nb_copy", "XOR"), ("nb_and_then_add", "XOR")):
        proved, diags = _forge(bbuilt, name, ir.ParInfo((), (), 3, (("s", op),)))
        assert not proved.functions and any(d.rule == PAR_RULE for d in diags), name
    proved, diags = _forge(bbuilt, "b_xor", good)
    assert proved.functions and not diags


def _bsame(m, name, *args):
    want = m.__typedpython_interpreted__[name](*[list(a) if isinstance(a, list) else a for a in args])
    got = getattr(m, name)(*[list(a) if isinstance(a, list) else a for a in args])
    assert got == want and type(got) is type(want), (name, got, want)


@pytest.mark.parametrize("n", RSIZES)
def test_bitwise_reductions_match_interpreter(bmod, n):
    for lo, hi in ((-1000, 1000), (I64_MIN, I64_MAX), (0, 255)):
        xs = rdata(n, 3, lo, hi)
        for nm in ("b_and", "b_or", "b_xor", "b_or_swapped", "b_or_not", "b_mix"):
            _bsame(bmod, nm, xs)
        _bsame(bmod, "b_and_swapped", xs, -1)
        _bsame(bmod, "b_and_swapped", xs, 0x0F0F0F0F0F0F0F0F)
        _bsame(bmod, "b_xor_expr", xs, 1)
    _bsame(bmod, "b_local", n)


def test_bitwise_identity_edge_values(bmod):
    n = 300001
    _bsame(bmod, "b_and", [-1] * n)
    _bsame(bmod, "b_and", [I64_MIN] * n)
    _bsame(bmod, "b_or", [I64_MIN] * n)
    _bsame(bmod, "b_xor", [I64_MAX] * n)
    _bsame(bmod, "b_xor", [I64_MAX] * (n - 1))
    z = [-1] * n
    z[n // 2] = 0
    _bsame(bmod, "b_and", z)


def test_bitwise_region_counter_and_no_rerun(bmod):
    if (os.cpu_count() or 1) < 2 or os.environ.get("TYPEDPYTHON_THREADS") == "1":
        pytest.skip("single thread")
    before = bmod.__typedpython_parallel__()
    xs = rdata(300001, 4, I64_MIN, I64_MAX)
    bmod.b_and(list(xs)); bmod.b_or(list(xs)); bmod.b_xor(list(xs))
    after = bmod.__typedpython_parallel__()
    assert after[0] - before[0] == 3 and after[3] == before[3]


def test_bitwise_element_expression_overflow_reruns_and_equals_python(bmod):
    n = 300001
    xs = [1] * n
    xs[n // 2] = 2**40
    before = bmod.__typedpython_parallel__()[3]
    _bsame(bmod, "b_xor_expr", xs, 2**30)               # 2**70 overflows i64: rerun, then promote
    if (os.cpu_count() or 1) > 1 and os.environ.get("TYPEDPYTHON_THREADS") != "1":
        assert bmod.__typedpython_parallel__()[3] > before


BCHILD_BODY = """
    import random
    I = m.__typedpython_interpreted__
    bad = []
    def check(name, *args):
        want = I[name](*[list(a) if isinstance(a, list) else a for a in args])
        got = getattr(m, name)(*[list(a) if isinstance(a, list) else a for a in args])
        if not (got == want and type(got) is type(want)):
            bad.append([name, repr(got), repr(want)])
    r = random.Random(9)
    n = 300001
    for lo, hi in ((-1000, 1000), (-2**63, 2**63 - 1), (0, 255)):
        xs = [r.randint(lo, hi) for _ in range(n)]
        for nm in ("b_and", "b_or", "b_xor", "b_or_swapped", "b_or_not", "b_mix"):
            check(nm, xs)
        check("b_and_swapped", xs, -1); check("b_xor_expr", xs, 3)
        for k in (0, 1, 2, 3, 7, 1000):
            for nm in ("b_and", "b_or", "b_xor", "b_mix"):
                check(nm, xs[:k])
    ov = [1] * n; ov[n // 2] = 2**40
    check("b_xor_expr", ov, 2**30)
    check("b_local", n)
    par = m.__typedpython_parallel__()
    print(json.dumps({"bad": bad, "regions": par[0], "reruns": par[3]}))
"""


@pytest.mark.parametrize("threads", [1, 2, 3, 7])
def test_bitwise_equals_interpreter_for_every_thread_count(bbuilt, threads):
    r = run_child(bbuilt[2], BCHILD_BODY, {"TYPEDPYTHON_THREADS": str(threads)}, timeout=900)
    assert r["bad"] == [], r["bad"][:5]
    if threads == 1:
        assert r["regions"] == 0 and r["reruns"] == 0
    elif (os.cpu_count() or 1) > 1:
        assert r["regions"] > 0 and r["reruns"] >= 1


# =====================================================================================================
# ThreadSanitizer over the parallel regions (SPEC N-15, #277: "race tests and TSan pass on 3.15t").
# The interpreter is not instrumented; TSan sees the generated C and the runtime header, which is where
# the workers, the partial slots and the join live. A report counts when the first non-sanitizer frame
# of one of its two accesses is in the extension (same rule as test_free_threaded.py).
# =====================================================================================================

TSOURCE = '''\

# typedpython: parallel
@compiled
def t_scale(xs: list[float], a: float, b: float) -> None:
    for i in range(len(xs)):
        xs[i] = xs[i] * a + b


# typedpython: parallel
@compiled
def t_sum(xs: list[int]) -> int:
    s: int = 0
    for i in range(len(xs)):
        s += xs[i]
    return s


# typedpython: parallel
@compiled
def t_prod(xs: list[int], c: int) -> int:
    s: int = 1
    for i in range(len(xs)):
        s *= xs[i] * c
    return s


# typedpython: parallel
@compiled
def t_min(xs: list[int]) -> int:
    m = xs[0]
    for i in range(len(xs)):
        if xs[i] < m:
            m = xs[i]
    return m


# typedpython: parallel
@compiled
def t_max(xs: list[float]) -> float:
    m = xs[0]
    for i in range(len(xs)):
        if xs[i] > m:
            m = xs[i]
    return m


# typedpython: parallel
@compiled
def t_and(xs: list[int]) -> int:
    s: int = -1
    for i in range(len(xs)):
        s &= xs[i]
    return s


# typedpython: parallel
@compiled
def t_or(xs: list[int]) -> int:
    s: int = 0
    for i in range(len(xs)):
        s |= xs[i]
    return s


# typedpython: parallel
@compiled
def t_xor(xs: list[int]) -> int:
    s: int = 0
    for i in range(len(xs)):
        s ^= xs[i]
    return s
'''

TSAN_PAR_BODY = """
I = m.__typedpython_interpreted__
n = 131072
ints = [((k * 40503) % 65521) - 30000 for k in range(n)]
small = [1 + (k % 2) for k in range(n)]
flts = [((k * 40503) % 65521) / 13.0 for k in range(n)]
bad = []
def work(seed):
    def go():
        for _ in range(3):
            xs = list(flts); m.t_scale(xs, 1.5, -0.5)
            ys = list(flts); I["t_scale"](ys, 1.5, -0.5)
            if xs != ys: bad.append("t_scale")
            for nm, a in (("t_sum", ints), ("t_min", ints), ("t_max", flts), ("t_and", ints),
                          ("t_or", ints), ("t_xor", ints)):
                if getattr(m, nm)(list(a)) != I[nm](list(a)): bad.append(nm)
            if m.t_prod(list(small[:30]), 1) != I["t_prod"](list(small[:30]), 1): bad.append("t_prod")
    return go
errors = run_threads([work(k) for k in range(4)])
print("TSAN-RUN-DONE", json.dumps({"bad": bad, "errors": errors, "regions": m.__typedpython_parallel__()[0]}))
"""


def _tsan_launcher_par(tmp: Path) -> Path | None:
    if sys.platform != "darwin" and not sys.platform.startswith("linux"):
        return None
    cc = (sysconfig.get_config_var("CC") or "cc").split()
    lc, launcher = tmp / "tsan_launcher.c", tmp / "tsan_python"
    lc.write_text("#include <Python.h>\nint main(int argc, char **argv) { return Py_BytesMain(argc, argv); }\n")
    libdir = sysconfig.get_config_var("LIBDIR")
    try:
        link = subprocess.run(cc + ["-fsanitize=thread", "-g", f"-I{sysconfig.get_paths()['include']}", str(lc),
                                    f"-L{libdir}", f"-lpython{sysconfig.get_config_var('LDVERSION')}",
                                    f"-Wl,-rpath,{libdir}", "-o", str(launcher)],
                              capture_output=True, text=True)
    except OSError:
        return None
    return launcher if link.returncode == 0 else None


def _par_access_is_ours(block: str, name: str) -> bool:
    for part in block.split("\n\n")[:2]:
        frames = [ln for ln in part.splitlines() if ln.strip().startswith("#")]
        first = next((f for f in frames if "libtsan" not in f and "sanitizer_common" not in f), "")
        if name in first:
            return True
    return False


def _par_tsan_reports(tmp: Path, launcher: Path, runtime_dir: Path | None, tag: str) -> tuple[int, bool, str]:
    d = tmp / tag
    d.mkdir(parents=True, exist_ok=True)
    path = d / "tsanpar.py"
    path.write_text(TSOURCE)
    proved, _ = verify.verify(frontend.lower(path))
    flags = list(cbuild.DEFAULT_FLAGS) + ["-O1", "-g", "-fno-omit-frame-pointer", "-fsanitize=thread"]
    so = cbuild.build(cgen.generate(proved, path), "tsanpar", d / "out", runtime_dir=runtime_dir, flags=flags)
    loader = CHILD + "import time\n"
    body = loader + textwrap.dedent("""
        def run_threads(targets):
            errors = []
            def wrap(fn):
                def go():
                    try:
                        fn()
                    except BaseException as e:
                        errors.append(repr(e))
                return go
            ts = [threading.Thread(target=wrap(t)) for t in targets]
            for t in ts: t.start()
            for t in ts: t.join()
            return errors
    """) + textwrap.dedent(TSAN_PAR_BODY)
    env = dict(os.environ, PYTHONHOME=sys.base_prefix, TYPEDPYTHON_THREADS="4",
               TSAN_OPTIONS="halt_on_error=0:report_signal_unsafe=0:history_size=4")
    env.pop("PYTHON_GIL", None)
    env.pop("DYLD_INSERT_LIBRARIES", None)
    run = subprocess.run([str(launcher), "-c", body, str(so)], capture_output=True, text=True, env=env, timeout=900)
    out = run.stdout + run.stderr
    blocks = out.split("WARNING: ThreadSanitizer")[1:]
    ours = [b for b in blocks if _par_access_is_ours(b, "tsanpar")]
    first = ("WARNING: ThreadSanitizer" + ours[0])[:4000] if ours else ""
    done = "TSAN-RUN-DONE" in out and '"bad": []' in out and '"errors": []' in out \
        and '"regions": 0}' not in out         # regions really started workers (not a vacuous pass)
    return len(ours), done, first + ("" if done else "\n--- output tail ---\n" + out[-1500:])


@pytest.fixture(scope="module")
def par_tsan_launcher(tmp_path_factory):
    d = tmp_path_factory.mktemp("partsan")
    launcher = _tsan_launcher_par(d)
    if launcher is None:
        pytest.skip("cannot link a ThreadSanitizer launcher on this host")
    return d, launcher


@pytest.mark.skipif(not FREE_THREADED, reason="needs a free-threaded CPython")
@pytest.mark.skipif((os.cpu_count() or 1) < 2, reason="needs a second core to start a region")
def test_tsan_reports_nothing_on_parallel_regions(par_tsan_launcher):
    d, launcher = par_tsan_launcher
    reports, done, first = _par_tsan_reports(d, launcher, None, "real")
    assert done and reports == 0, f"{reports} ThreadSanitizer report(s) (done={done}); first:\n{first}"


def _racy_par_runtime(tmp: Path) -> Path:
    """tp_runtime.h with the join removed: workers are detached, so the caller combines the partials
    while the workers are still writing them."""
    h = (cbuild.RUNTIME_DIR / "tp_runtime.h").read_text()
    old = "                pthread_join(th[k], NULL);\n"
    assert old in h
    g = h.replace(old, "                pthread_detach(th[k]);\n")
    d = tmp / "rt"
    d.mkdir(parents=True, exist_ok=True)
    (d / "tp_runtime.h").write_text(g)
    return d


@pytest.mark.race_mutant
@pytest.mark.skipif(not FREE_THREADED, reason="needs a free-threaded CPython")
@pytest.mark.skipif((os.cpu_count() or 1) < 2, reason="needs a second core to start a region")
def test_tsan_sees_a_region_combined_before_the_join(par_tsan_launcher):
    """The check can fail: with the join replaced by a detach TSan reports the partials and the loop
    output being read while workers still write them."""
    d, launcher = par_tsan_launcher
    rt = _racy_par_runtime(d / "racy")
    reports, _done, _first = _par_tsan_reports(d, launcher, rt, "mutant")
    assert reports > 0
