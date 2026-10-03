"""TypedPython fast entry (issue #147): a depth precheck plus an uncounted entry for *bounded*
compiled functions (no call cycle, bounded callees, no node that can run Python code).

  * the precheck `tp_depth_room(k)` is the exact condition under which the per-call counting of
    `tp_enter_call` could not refuse anything, so the point where RecursionError is raised is the
    interpreter's, at every depth (differential test, in a child interpreter with a small limit);
  * the fast path is really taken when there is room (a hit counter compiled in only with
    `-DTP_TRACE_FAST`) and is not taken when there is none;
  * functions that are not bounded get no fast impl;
  * the generated C shape (the fast impl is `static inline`, calls only fast impls, the normal impl
    keeps counting).

Builds go through the front end, verifier, cgen and cbuild in `.tmp/` of the worktree via tmp_path.
"""
import json
import subprocess
import sys
import textwrap
from pathlib import Path

import pytest

from typedpython import cbuild, cgen, frontend, verify

SOURCE = """\
# typedpython: compiled


def helper(x: int) -> float:
    d: dict[str, int] = {}
    d['a'] = x
    return d['a'] * 2.5


def leaf(i: int) -> float:
    return 1.0 / (i + 1)


def mid(i: int) -> float:
    return leaf(i) + leaf(i + 1)


def top(i: int) -> float:
    return mid(i) * 2.0


def objy(i: int) -> float:
    return float(helper(i)) + leaf(i)


def viaobj(i: int) -> float:
    return objy(i) + 1.0


def rec(n: int) -> int:
    if n == 0:
        return 0
    return 1 + rec(n - 1)


def callrec(n: int) -> int:
    return rec(n) + 1


def loopy(n: int) -> float:
    s: float = 0.0
    for i in range(n):
        s += leaf(i)
    return s
"""

BOUNDED = {"leaf": 1, "mid": 2, "top": 3, "loopy": 2}
NOT_BOUNDED = ("objy", "viaobj", "rec", "callrec")


def build(d: Path, extra: tuple[str, ...] = ()):
    path = d / "fast_mod.py"
    path.write_text(SOURCE)
    proved, _ = verify.verify(frontend.lower(path))
    c = cgen.generate(proved, path, path.name)
    so = cbuild.build(c, path.stem, d / "out", flags=cbuild.DEFAULT_FLAGS + extra)
    return proved, c, so


@pytest.fixture(scope="module")
def traced(tmp_path_factory):
    return build(tmp_path_factory.mktemp("fast_traced"), ("-DTP_TRACE_FAST",))


@pytest.fixture(scope="module")
def plain_build(tmp_path_factory):
    return build(tmp_path_factory.mktemp("fast_plain"))


def fn_text(c: str, signature_start: str) -> str:
    """The text of the C function whose definition starts with `signature_start`."""
    i = c.index(signature_start + "(", c.index("static const char tp_source"))
    # skip forward declarations: find the definition (the one followed by a newline and `{`)
    while not c[c.index(")", i) + 1:].startswith("\n{"):
        i = c.index(signature_start + "(", i + 1)
    return c[i:c.index("\n}\n", i)]


def test_every_function_compiles(traced):
    proved = traced[0]
    names = {f.name for f in proved.functions}
    assert names == set(BOUNDED) | set(NOT_BOUNDED), proved.skipped


def test_bounded_functions_get_a_static_inline_fast_impl(traced):
    c = traced[1]
    for name in BOUNDED:
        assert f"static inline int tp_fimpl_{name}(" in c, name


def test_unbounded_functions_get_no_fast_impl(traced):
    c = traced[1]
    for name in NOT_BOUNDED:
        assert f"tp_fimpl_{name}" not in c, name


def test_the_fast_impl_calls_only_fast_impls_and_never_counts(traced):
    c = traced[1]
    fast = fn_text(c, "static inline int tp_fimpl_mid")
    assert "tp_fimpl_leaf(" in fast and "tp_impl_leaf(" not in fast
    assert "tp_enter_call" not in fast and "tp_leave_call" not in fast
    assert "tp_poll(tp_st->breaker)" not in fast          # no entry poll
    loop = fn_text(c, "static inline int tp_fimpl_loopy")
    assert "tp_poll(" in loop or "tp_pc" in loop          # loop polls stay


def test_the_normal_impl_prechecks_and_still_counts(traced):
    c = traced[1]
    normal = fn_text(c, "static int tp_impl_mid")
    assert "tp_depth_room(2)" in normal
    assert normal.index("tp_depth_room(2)") < normal.index("tp_enter_call()")
    assert "tp_impl_leaf(" in normal and "tp_fimpl_leaf(" not in normal
    top = fn_text(c, "static int tp_impl_top")
    assert "tp_depth_room(3)" in top
    assert "tp_depth_room" not in fn_text(c, "static int tp_impl_rec")


def test_the_fast_impl_is_never_addressed(traced):
    c = traced[1]
    assert "&tp_fimpl_" not in c and "(tp_fimpl_" not in c


CHILD = """\
import importlib.machinery, importlib.util, json, sys
def load(name, path, ext):
    if ext:
        loader = importlib.machinery.ExtensionFileLoader(name, path)
        spec = importlib.util.spec_from_file_location(name, path, loader=loader)
    else:
        spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    (loader if ext else spec.loader).exec_module(m)
    return m
comp = load("fast_mod", sys.argv[1], True)
plain = load("fast_mod_plain", sys.argv[2], False)
LIMIT = int(sys.argv[3])
sys.setrecursionlimit(LIMIT)

def descend(d, fn, a):
    if d > 0:
        return descend(d - 1, fn, a)
    try:
        return ("ok", fn(a))
    except RecursionError:
        return ("rec",)

def run(fn, a, d):
    try:
        return descend(d, fn, a)
    except RecursionError:
        return ("deep",)

report = {"mismatch": [], "ok": {}, "rec": {}, "hits": []}
for name in ("leaf", "mid", "top", "loopy"):
    arg = 3
    for d in range(1, LIMIT + 6):
        before = comp.__tp_trace_fast_hits__()
        c = run(getattr(comp, name), arg, d)
        hit = comp.__tp_trace_fast_hits__() - before
        p = run(getattr(plain, name), arg, d)
        if c != p:
            report["mismatch"].append([name, d, c, p])
        kind = c[0]
        report.setdefault(kind, {})
        report[kind][name] = report[kind].get(name, 0) + 1
        # one hit per entry from Python that found room, none when it ran counted (or never ran)
        if hit != (1 if kind == "ok" else 0):
            report["hits"].append([name, d, kind, hit])
print(json.dumps(report))
"""


def test_differential_depth_at_every_depth_around_the_limit(traced, tmp_path):
    proved, c, so = traced
    plain_src = tmp_path / "fast_mod_plain.py"
    plain_src.write_text(SOURCE)
    proc = subprocess.run([sys.executable, "-c", CHILD, str(so), str(plain_src), "200"],
                          capture_output=True, text=True, timeout=300)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    r = json.loads(proc.stdout.strip().splitlines()[-1])
    assert r["mismatch"] == [], r["mismatch"][:5]
    assert r["hits"] == [], r["hits"][:5]
    for name in ("leaf", "mid", "top", "loopy"):                # the sweep crossed the boundary
        assert r["ok"][name] > 100 and r["rec"][name] >= 1, (name, r)
    # top needs more room than leaf: it starts raising at a smaller depth
    assert r["rec"]["top"] > r["rec"]["leaf"]


def test_the_fast_path_is_taken_when_there_is_room(tmp_path):
    proved, c, so = build(tmp_path, ("-DTP_TRACE_FAST",))
    m = cbuild.load("fast_mod", so)
    assert m.__tp_trace_fast_hits__() == 0
    assert m.top(4) == 2.0 * (1 / 5 + 1 / 6)
    assert m.__tp_trace_fast_hits__() == 1                  # one precheck, no per-callee counting
    assert m.loopy(10) == sum(1.0 / (i + 1) for i in range(10))
    assert m.__tp_trace_fast_hits__() == 2
    assert m.rec(10) == 10 and m.callrec(10) == 11          # not bounded: no precheck
    assert m.__tp_trace_fast_hits__() == 2
    assert m.viaobj(3) == 3 * 2.5 + 1 / 4 + 1.0
    assert m.__tp_trace_fast_hits__() == 3                  # objy is not bounded; its callee leaf is
    assert m.mid(2) == 1 / 3 + 1 / 4                        # a bounded callee entered from Python
    assert m.__tp_trace_fast_hits__() == 4


def test_the_trace_counter_is_absent_from_a_normal_build(plain_build):
    c = plain_build[1]
    assert "TP_TRACE_FAST" in c                              # guarded, so compiled out
    so = plain_build[2]
    m = cbuild.load("fast_mod", so)
    assert not hasattr(m, "__tp_trace_fast_hits__")
    assert m.top(1) == 2.0 * (1 / 2 + 1 / 3)
