"""TypedPython: the Python frame depth is measured once per segment, not once per compiled frame
(#125, docs/design/typedpython.md 4.3.3b, agent proposal, not confirmed).

A *segment* is one call of a compiled impl from its wrapper (`tp_wrap_f`), up to the first point
where a call of a wrapper of the same module returns inside it. Inside a segment the Python frames
below the compiled frames cannot change: Python code that runs in between (a callback, a `__del__`,
a signal handler) pushes frames and pops all of them before it returns to C. So the first depth check
of a segment measures the Python depth (`tp_python_depth`, one `PyThreadState_GetFrame`) and every
later check of the segment reuses it. The wrapper resets the measurement before and after its impl
call, so a nested wrapper never sees the outer segment's depth and the outer segment measures again
after a nested wrapper returned (exactly where it measured before this change, so the cached frame
object and every frame's lifetime stay the same).

What must NOT move, and stays per frame: `Py_GetRecursionLimit()` (a `__del__` or a callback can call
`sys.setrecursionlimit` between two compiled frames) and `Py_EnterRecursiveCall` (the C-stack guard:
it compares this frame's stack address).

  * red before the implementation: `__tp_trace_frame_reads__` (-DTP_TRACE_DEPTH) counts one
    `PyThreadState_GetFrame` per compiled frame (`down(100)`: 101 instead of 1), and the generated
    wrappers have no `tp_depth_seg_begin` / `tp_depth_seg_end`;
  * green before and after (they pin what must not change): every differential test against the
    interpreter (depth at every Python depth and recursion depth around the limit, a nested compiled
    call from a callback, the limit changed by a `__del__` and by a callback mid-recursion, threads),
    the frame-lifetime test and the `sys._getframe` test;
  * mutants: a wrapper that does not reset before its impl, one that does not reset after it, and a
    limit read once per call instead of per frame must each be caught.

Builds go through the front end, verifier, cgen and cbuild in pytest's tmp_path.
"""
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

from conftest import COMPILED_SHIM
from typedpython import cbuild, cgen, frontend, verify

SOURCE = """\
from typing import Callable


@compiled
def down(n: int) -> float:
    if n == 0:
        return 0.0
    return 1.0 + down(n - 1)


@compiled
def visit(a: Callable[[int], object], b: Callable[[int], object], n: int) -> None:
    a(n)
    b(n)
    if n > 0:
        visit(a, b, n - 1)


@compiled
def around(cb: Callable[[], None], m: int) -> float:
    cb()
    return down(m)


@compiled
def seq(g: Callable[[], None], probe: Callable[[], None], m: int) -> float:
    g()
    probe()
    r = down(m)
    probe()
    return r


@compiled
def leaf(i: int) -> float:
    return 1.0 / (i + 1)


@compiled
def mid(i: int) -> float:
    return leaf(i) + leaf(i + 1)


@compiled
def deep_mid(n: int) -> float:
    if n == 0:
        return mid(0)
    return 1.0 + deep_mid(n - 1)
"""

NAMES = {"down", "visit", "around", "seq", "leaf", "mid", "deep_mid"}


def generate(d: Path):
    path = d / "depth_mod.py"
    path.write_text(SOURCE)
    proved, _ = verify.verify(frontend.lower(path))
    return path, proved, cgen.generate(proved, path, path.name)


def build(d: Path, extra: tuple[str, ...] = (), runtime_dir: Path | None = None):
    path, proved, c = generate(d)
    so = cbuild.build(c, path.stem, d / "out", runtime_dir=runtime_dir,
                      flags=cbuild.DEFAULT_FLAGS + extra)
    return proved, c, so


def mutant_runtime(tmp_path: Path, *edits: tuple[str, str]) -> Path:
    mutant = tmp_path / "runtime"
    shutil.copytree(cbuild.RUNTIME_DIR, mutant)
    text = (mutant / "tp_runtime.h").read_text()
    for old, new in edits:
        assert text.count(old) == 1, old
        text = text.replace(old, new)
    (mutant / "tp_runtime.h").write_text(text)
    return mutant


@pytest.fixture(scope="module")
def generated(tmp_path_factory):
    return generate(tmp_path_factory.mktemp("depth_c"))


@pytest.fixture(scope="module")
def plain_build(tmp_path_factory):
    return build(tmp_path_factory.mktemp("depth_plain"))


@pytest.fixture(scope="module")
def plain_src(tmp_path_factory):
    p = tmp_path_factory.mktemp("depth_src") / "depth_mod_plain.py"
    p.write_text(SOURCE)
    return p


def fn_text(c: str, signature_start: str) -> str:
    """The text of the C function whose definition starts with `signature_start`."""
    i = c.index(signature_start + "(")
    while not c[c.index(")", i) + 1:].startswith("\n{"):
        i = c.index(signature_start + "(", i + 1)
    return c[i:c.index("\n}\n", i)]


# --- generated C and the runtime header ----------------------------------------------------------

def test_every_function_compiles(generated):
    proved = generated[1]
    assert {f.name for f in proved.functions} == NAMES, proved.skipped


def test_every_wrapper_resets_the_segment_right_around_its_impl_call(generated):
    c = generated[2]
    for name in NAMES:
        w = fn_text(c, f"static PyObject *tp_wrap_{name}").splitlines()
        calls = [i for i, line in enumerate(w) if re.match(rf"\s*tp_s = tp_impl_{name}\(", line)]
        assert len(calls) == 1, (name, calls)
        i = calls[0]
        assert w[i - 1].strip() == "tp_depth_seg_begin(tp_t);", (name, w[i - 1])
        assert w[i + 1].strip() == "tp_depth_seg_end(tp_t);", (name, w[i + 1])
        assert sum("tp_depth_seg_" in line for line in w) == 2, name


def test_impls_never_reset_or_measure_the_segment_themselves(generated):
    c = generated[2]
    for name in NAMES:
        for sig in (f"static int tp_impl_{name}", f"static inline int tp_fimpl_{name}"):
            if sig + "(" not in c:
                continue
            body = fn_text(c, sig)
            assert "tp_depth_seg_" not in body and "tp_python_depth" not in body, sig
            assert "PyThreadState_GetFrame" not in body, sig


def header_fn(name: str) -> str:
    h = (cbuild.RUNTIME_DIR / "tp_runtime.h").read_text()
    i = re.search(rf"static inline [^\n(]*\b{name}\(", h).start()
    return h[i:h.index("\n}\n", i)]


def test_the_frame_checks_use_the_segment_depth_and_keep_the_per_frame_calls():
    enter = header_fn("tp_enter_call")
    room = header_fn("tp_depth_room")
    for body in (enter, room):
        assert "tp_seg_depth(ts, t)" in body and "tp_python_depth" not in body, body
        assert "Py_GetRecursionLimit()" in body, body        # the limit stays per frame
    assert "Py_EnterRecursiveCall(" in enter                  # the C-stack guard stays per frame
    seg = header_fn("tp_seg_depth")
    assert "tp_python_depth(ts, t)" in seg and "seg < 0" in seg


# --- children: differential runs against the interpreter -----------------------------------------

LOAD = COMPILED_SHIM + """\
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
comp = load("depth_mod", sys.argv[1], True)
plain = load("depth_mod_plain", sys.argv[2], False)
LIMIT = int(sys.argv[3])
sys.setrecursionlimit(LIMIT)

def descend(d, fn, a):
    if d > 0:
        return descend(d - 1, fn, a)
    try:
        return ["ok", fn(a)]
    except RecursionError:
        return ["rec"]

def run(fn, a, d):
    try:
        return descend(d, fn, a)
    except RecursionError:
        return ["deep"]

report = {"mismatch": [], "kinds": {}}
def note(tag, c, p):
    if c != p:
        report["mismatch"].append([tag, c, p])
    k = report["kinds"].setdefault(tag, {})
    key = c[0] if isinstance(c[0], str) else c[0][0]
    k[key] = k.get(key, 0) + 1

inner = []
def mk(mod, k, j):
    # a Python callback that descends k Python frames, then makes a nested compiled call down(j)
    def cb():
        def dive(i):
            if i == 0:
                try:
                    inner.append(["ok", mod.down(j)])
                except RecursionError:
                    inner.append(["rec"])
                return
            dive(i - 1)
        dive(k)
    return cb

def nested(mod, d, k, j, m):
    del inner[:]
    r = run(lambda a: mod.around(mk(mod, k, j), a), m, d)
    return [r, list(inner)]
"""

CHILD_SWEEP = LOAD + """\
for d in range(1, LIMIT + 6):                    # every Python depth around the limit
    note("down@d", run(comp.down, 20, d), run(plain.down, 20, d))
for n in range(LIMIT - 40, LIMIT + 6):           # every recursion depth around the limit
    note("down@n", run(comp.down, n, 5), run(plain.down, n, 5))
    # deep counted recursion, then a bounded callee whose precheck (tp_depth_room) reuses the depth
    note("deep_mid", run(comp.deep_mid, n, 5), run(plain.deep_mid, n, 5))
print(json.dumps(report))
"""

CHILD_NESTED = LOAD + """\
# inner: a nested wrapper inside a segment must measure its own (deeper) Python depth
for j in range(LIMIT - 70, LIMIT + 6):
    note("inner", nested(comp, 10, 30, j, 0), nested(plain, 10, 30, j, 0))
# outer: after the nested wrapper returned, the outer segment must not keep the inner depth
for m in range(LIMIT - 70, LIMIT + 6):
    note("outer", nested(comp, 10, 30, 0, m), nested(plain, 10, 30, 0, m))
print(json.dumps(report))
"""

CHILD_LIMIT = LOAD + """\
class Setter:
    def __init__(self, to):
        self.to = to
    def __del__(self):
        sys.setrecursionlimit(self.to)

def by_del(mod, d, n, at, to):
    # visit(a, b, n) calls a(n) then b(n) in every frame; b = objs.pop drops the last element, and
    # at n == at that is a Setter whose only reference is the compiled call's result: its __del__
    # runs when the compiled frame releases it (the interpreter: POP_TOP), between two frames
    sys.setrecursionlimit(LIMIT)
    log = []
    objs = [None] * (n + 1)
    objs[at] = Setter(to)
    r = run(lambda a: mod.visit(log.append, objs.pop, a), n, d)
    sys.setrecursionlimit(LIMIT)
    return [r, len(log)]

def by_callback(mod, d, m, to):
    sys.setrecursionlimit(LIMIT)
    r = run(lambda a: mod.around(lambda: sys.setrecursionlimit(to), a), m, d)
    sys.setrecursionlimit(LIMIT)
    return [r]

N = 2 * LIMIT
for to in range(60, LIMIT - 20, 3):              # lowered 20 frames in: the next frames see it
    note("del-lower", by_del(comp, 5, N, N - 20, to), by_del(plain, 5, N, N - 20, to))
for to in (LIMIT + 40, 3 * LIMIT):               # raised 20 frames in: the recursion may go deeper
    note("del-raise", by_del(comp, 5, N, N - 20, to), by_del(plain, 5, N, N - 20, to))
for m in range(40, 140, 1):                      # a callback lowers it before a compiled recursion
    note("cb-lower", by_callback(comp, 5, m, 100), by_callback(plain, 5, m, 100))
print(json.dumps(report))
"""

CHILD_FRAMES = LOAD + """\
events = []

class Obj:
    def __del__(self):
        events.append("del")

def mkg(mod):
    def g():
        x = Obj()           # dies with g's frame (or with the frame object that keeps g's locals)
        mod.down(1)
    return g

def probe():
    events.append("probe")

out = {}
del events[:]
comp.seq(mkg(comp), probe, 3)
out["compiled"] = list(events)
del events[:]
plain.seq(mkg(plain), probe, 3)
out["interpreted"] = list(events)

def chain():
    f, names = sys._getframe(2), []          # from the callback's caller down
    while f is not None:
        names.append(f.f_code.co_name)
        f = f.f_back
    return names

seen = []
def cb():
    seen.append(chain())
def cb_nested():
    comp.down(3)          # a nested wrapper first: its segment must not change what cb sees
    seen.append(chain())
def cb_nested_plain():
    plain.down(3)
    seen.append(chain())

def at(d, fn):
    if d > 0:
        return at(d - 1, fn)
    return fn()

for d in (0, 7, 40):
    at(d, lambda: comp.around(cb, 5))
    at(d, lambda: comp.around(cb_nested, 5))
    at(d, lambda: comp.around(cb, 5))
    at(d, lambda: plain.around(cb, 5))
    at(d, lambda: plain.around(cb_nested_plain, 5))
out["chains"] = seen
print(json.dumps(out))
"""

CHILD_THREADS = LOAD + """\
import threading
results = {}
def worker(t):
    mine = []
    for j in range(LIMIT - 60, LIMIT + 6, 2):
        mine.append([nested_t(comp, 3 + t, 10 + t, j, 0), nested_t(plain, 3 + t, 10 + t, j, 0)])
        mine.append([nested_t(comp, 3 + t, 10 + t, 0, j), nested_t(plain, 3 + t, 10 + t, 0, j)])
    results[t] = mine

local = threading.local()
def mk_t(mod, k, j):
    def cb():
        def dive(i):
            if i == 0:
                try:
                    local.inner.append(["ok", mod.down(j)])
                except RecursionError:
                    local.inner.append(["rec"])
                return
            dive(i - 1)
        dive(k)
    return cb

def nested_t(mod, d, k, j, m):
    local.inner = []
    r = run(lambda a: mod.around(mk_t(mod, k, j), a), m, d)
    return [r, list(local.inner)]

ts = [threading.Thread(target=worker, args=(t,)) for t in range(4)]
[t.start() for t in ts]
[t.join() for t in ts]
for t, rows in sorted(results.items()):
    for c, p in rows:
        note(f"thread{t}", c, p)
print(json.dumps(report))
"""


def run_child(script: str, so: Path, plain_src: Path, limit: str = "200") -> dict:
    proc = subprocess.run([sys.executable, "-c", script, str(so), str(plain_src), limit],
                          capture_output=True, text=True, timeout=600)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    return json.loads(proc.stdout.strip().splitlines()[-1])


def assert_sweep(r):
    assert r["mismatch"] == [], r["mismatch"][:5]
    for tag in ("down@d", "down@n", "deep_mid"):                 # the sweep crossed the boundary
        assert r["kinds"][tag].get("ok", 0) > 5 and r["kinds"][tag].get("rec", 0) >= 1, (tag, r["kinds"])


def assert_nested(r):
    assert r["mismatch"] == [], r["mismatch"][:5]
    for tag in ("inner", "outer"):
        assert r["kinds"][tag].get("ok", 0) > 5, (tag, r["kinds"])
    # crossing the boundary: some outer runs end in RecursionError, some inner calls do too
    assert r["kinds"]["outer"].get("rec", 0) >= 1, r["kinds"]


def assert_limit(r):
    assert r["mismatch"] == [], r["mismatch"][:5]
    assert r["kinds"]["del-lower"].get("rec", 0) >= 10, r["kinds"]   # the lowered limit stopped them
    assert r["kinds"]["del-raise"].get("ok", 0) >= 1, r["kinds"]     # the raised one let one through
    k = r["kinds"]["cb-lower"]
    assert k.get("ok", 0) > 5 and k.get("rec", 0) > 5, k


def test_depth_at_every_python_depth_and_recursion_depth_matches_the_interpreter(plain_build, plain_src):
    assert_sweep(run_child(CHILD_SWEEP, plain_build[2], plain_src))


def test_a_nested_compiled_call_from_a_callback_matches_the_interpreter(plain_build, plain_src):
    assert_nested(run_child(CHILD_NESTED, plain_build[2], plain_src))


def test_a_limit_changed_by_a_del_or_a_callback_mid_recursion_is_honoured(plain_build, plain_src):
    assert_limit(run_child(CHILD_LIMIT, plain_build[2], plain_src, "300"))


def test_threads_each_see_their_own_depth(plain_build, plain_src):
    r = run_child(CHILD_THREADS, plain_build[2], plain_src)
    assert r["mismatch"] == [], r["mismatch"][:5]
    assert sorted(r["kinds"]) == [f"thread{t}" for t in range(4)], r["kinds"]


def test_frame_lifetimes_and_getframe_are_unchanged(plain_build, plain_src):
    """Pins develop's behaviour (green before and after the change).

    Lifetime: inside `seq`, the nested `down(1)` called from the Python callback g measures the depth
    with g's frame on top, so the depth cache holds g's frame object; when g returns, that frame
    object takes g's locals (`take_ownership`, Python/frame.c), and `x` dies when the outer segment
    next measures, at its `down(m)`, not at g's return as in the interpreter. That difference from the
    interpreter exists on develop (see the design note); this test only pins that the change keeps
    the same moment: a wrapper that kept the inner segment's depth after it returned would never
    re-measure and `x` would live until `seq` returns (["probe", "probe", "del"]).

    sys._getframe: the frames a callee sees are the Python frames only (compiled frames have none),
    the same at every depth and after a nested compiled call."""
    r = run_child(CHILD_FRAMES, plain_build[2], plain_src)
    assert r["interpreted"] == ["del", "probe", "probe"], r
    assert r["compiled"] == ["probe", "del", "probe"], r
    chains = r["chains"]
    assert len(chains) == 15
    for i in range(3):
        c1, c2, c3, p1, p2 = chains[5 * i:5 * i + 5]
        assert c1 == c2 == c3, (c1, c2, c3)
        assert c1 == [n for n in p1 if n not in ("around", "down")], (c1, p1)
        assert p1 == p2
    assert len(chains[10]) > len(chains[0])                         # deeper at d=40 than at d=0


# --- the measurement really happens once per segment (red before the implementation) -------------

def test_one_frame_read_per_segment(tmp_path):
    proved, c, so = build(tmp_path, ("-DTP_TRACE_DEPTH",))
    m = cbuild.load("depth_mod", so)

    def reads(fn):
        before = m.__tp_trace_frame_reads__()
        fn()
        return m.__tp_trace_frame_reads__() - before

    # develop measured once per compiled frame: 101, 52 (51 frames + mid's precheck), 1, 101, 18
    assert reads(lambda: m.down(100)) == 1
    assert reads(lambda: m.deep_mid(50)) == 1
    assert reads(lambda: m.mid(3)) == 1
    log: list = []
    objs = [None] * 101
    assert reads(lambda: m.visit(log.append, objs.pop, 100)) == 1 and len(log) == 101
    # around: its own segment, the nested down(5) from the callback, then around measures again
    # after that wrapper returned (its down(10) is the first check after the nested segment)
    assert reads(lambda: m.around(lambda: m.down(5), 10)) == 3


def test_the_trace_counter_is_absent_from_a_normal_build(plain_build):
    c, so = plain_build[1], plain_build[2]
    assert "TP_TRACE_DEPTH" in c                              # guarded, so compiled out
    m = cbuild.load("depth_mod", so)
    assert not hasattr(m, "__tp_trace_frame_reads__")


# --- mutants: each reset and the per-frame limit read are shown to matter -------------------------

SEG_BEGIN = "static inline void tp_depth_seg_begin(tp_tls *t) { t->depth.seg = -1; }"
SEG_END = "static inline void tp_depth_seg_end(tp_tls *t) { t->depth.seg = -1; }"
ENTER = "static inline int tp_enter_call(PyThreadState *ts, tp_tls *t)\n{"
ENTER_CHECK = "if (tp_seg_depth(ts, t) + t->depth.count + 1 > Py_GetRecursionLimit()) {"


def test_a_wrapper_that_does_not_reset_before_its_impl_is_caught(tmp_path, plain_src):
    """Mutation: a nested wrapper reuses the outer segment's (shallower) depth."""
    rt = mutant_runtime(tmp_path, (SEG_BEGIN, "static inline void tp_depth_seg_begin(tp_tls *t) { (void)t; }"))
    (tmp_path / "bad").mkdir()
    so = build(tmp_path / "bad", runtime_dir=rt)[2]
    with pytest.raises(AssertionError):
        assert_nested(run_child(CHILD_NESTED, so, plain_src))


def test_a_wrapper_that_does_not_reset_after_its_impl_is_caught(tmp_path, plain_src):
    """Mutation: the outer segment keeps the inner segment's (deeper) depth and never re-measures."""
    rt = mutant_runtime(tmp_path, (SEG_END, "static inline void tp_depth_seg_end(tp_tls *t) { (void)t; }"))
    (tmp_path / "bad").mkdir()
    so = build(tmp_path / "bad", runtime_dir=rt)[2]
    with pytest.raises(AssertionError):
        assert_nested(run_child(CHILD_NESTED, so, plain_src))
    r = run_child(CHILD_FRAMES, so, plain_src)
    assert r["compiled"] == ["probe", "probe", "del"], r               # and x lives longer


def test_a_limit_read_once_per_call_is_caught(tmp_path, plain_src):
    """Mutation: the recursion limit is read at the first compiled frame only (count == 0), as a
    once-per-wrapper limit would be. A limit changed mid-recursion is then missed."""
    rt = mutant_runtime(
        tmp_path,
        (ENTER, "static int tp_mut_limit = 0;\n" + ENTER),
        (ENTER_CHECK, "if (t->depth.count == 0) tp_mut_limit = Py_GetRecursionLimit();\n"
                      "    if (tp_seg_depth(ts, t) + t->depth.count + 1 > tp_mut_limit) {"))
    (tmp_path / "bad").mkdir()
    so = build(tmp_path / "bad", runtime_dir=rt)[2]
    with pytest.raises(AssertionError):
        assert_limit(run_child(CHILD_LIMIT, so, plain_src, "300"))
