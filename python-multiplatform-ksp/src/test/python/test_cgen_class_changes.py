"""TypedPython C back end: a compiled fixed-layout class (SPEC N-11) changed AFTER module init.

After init, compiled code must still compute what the interpreter computes when user code
  * replaces a field's class attribute (`Node.left = property(...)`), with or without a setter;
  * rebinds the module global (`Node = Twin`, `Node = Wide`, `del Node`);
  * assigns `obj.__class__` to a layout-compatible class;
  * changes `Node.__init__`, or adds an unrelated class attribute (`Node.counter = 1`);
  * exhausts the class's version tags (tp_version_tag stays 0);
and it must do so mid-body too, when an impure compiled function runs a Python callback
(CallObject) between entry and the field access / New — without a deopt after that effect.

Every scenario runs in a CHILD process (a crash fails one test, not the run) on a fresh compiled
module instance, and the same scenario runs on the plain interpreted source (loaded as an ordinary
module from the same file): the two normalised outcome lists must be equal. Probes read
`__typedpython_deopts__` and `__typedpython_class_info__()` of the compiled instance only.

Builds go to `.tmp/cgen-tests/` of the worktree (test_cgen.OUT_ROOT).
"""
from __future__ import annotations

import gc
import importlib.util
import json
import os
import subprocess
import sys
import sysconfig
import textwrap
from pathlib import Path

import pytest

from test_cgen import (PACKAGE, OUT_ROOT, I64, BOOL, OBJ, NONE, F64, c, L, fn, runtime_dir,
                       need_runtime)
from typedpython import ir
from typedpython.ir import (
    ArrayParam, Assign, BinOp, BinOpKind, Call, CallObject, ClassDecl, Compare, CompareKind, Const,
    ExprStmt, FieldGet, FieldSet, If, Is, IsExact, Module, New, Param, Return, StoreIndex, Type,
)

TEST_DIR = Path(__file__).resolve().parent

SOURCE = textwrap.dedent('''\
    """typedpython class-change test module — the interpreted reference."""

    class Node:
        __slots__ = ("left", "right")

        def __init__(self, left, right):
            self.left = left
            self.right = right

    class Twin:                            # the same layout as Node (so __class__ can be assigned)
        __slots__ = ("left", "right")

        def __init__(self, left, right):
            self.left = left
            self.right = right

    class Shadow:                          # the same layout; `left` is a property
        __slots__ = ("left", "right")

    Shadow.left = property(lambda self: "shadow")

    class Wide:                            # a different layout (an instance __dict__)
        def __init__(self, left, right):
            self.left = left
            self.right = right
            self.extra = "wide"

    SCALE = 2.0

    def make(depth):
        if depth == 0:
            return None
        return Node(make(depth - 1), make(depth - 1))

    def check(node):
        if node is None:
            return 0
        return 1 + check(node.left) + check(node.right)

    def pair(a, b):
        return Node(a, b)

    def get_left(n):
        return n.left

    def setl(n, v):
        n.left = v

    def mid_get(cb, n):
        cb()
        return n.left

    def mid_set(cb, n, v):
        cb()
        n.left = v

    def mid_new(cb, a, b):
        cb()
        return Node(a, b)

    def arg_new(cb, b):
        return Node(cb(), b)

    def via_pair(cb, a, b):
        cb()
        return pair(a, b)

    def is_node(cb, x):
        cb()
        return type(x) is Node

    def fill(a, n):
        x = n.left
        a[0] = SCALE
    ''')

NODE = ClassDecl("Node", ("left", "right"), True)
CLASSES = (NODE,)


def _o(name):
    return L(name, OBJ)


def f_make():
    depth = L("depth", I64)
    rec = lambda: Call(OBJ, "make", (BinOp(I64, BinOpKind.SUB, depth, c(1)),))
    return fn("make", [Param("depth", I64)], OBJ, {"a": OBJ, "b": OBJ},
              [If(Compare(BOOL, CompareKind.EQ, depth, c(0)), (Return(Const(OBJ, None)),)),
               Assign("a", rec()), Assign("b", rec()),
               Return(New(OBJ, "Node", (_o("a"), _o("b"))))],
              pure=True, may_deopt=True)


def f_check():
    node = _o("node")
    return fn("check", [Param("node", OBJ, cls="Node", optional=True)], I64, {"l": OBJ, "r": OBJ},
              [If(Is(BOOL, node, Const(OBJ, None)), (Return(c(0)),)),
               Assign("l", FieldGet(OBJ, node, "Node", "left")),
               Assign("r", FieldGet(OBJ, node, "Node", "right")),
               Return(BinOp(I64, BinOpKind.ADD,
                            BinOp(I64, BinOpKind.ADD, c(1), Call(I64, "check", (_o("l"),))),
                            Call(I64, "check", (_o("r"),))))],
              pure=True, may_deopt=True)


def f_pair():
    return fn("pair", [Param("a", OBJ), Param("b", OBJ)], OBJ, {},
              [Return(New(OBJ, "Node", (_o("a"), _o("b"))))], pure=True, may_deopt=False)


def f_get_left():
    return fn("get_left", [Param("n", OBJ, cls="Node")], OBJ, {},
              [Return(FieldGet(OBJ, _o("n"), "Node", "left"))], pure=True, may_deopt=False)


def f_setl():
    return fn("setl", [Param("n", OBJ, cls="Node"), Param("v", OBJ)], NONE, {},
              [FieldSet(_o("n"), "Node", "left", _o("v")), Return()], pure=False, may_deopt=False)


def _cb():
    return ExprStmt(CallObject(OBJ, _o("cb"), ()))


def f_mid_get():
    return fn("mid_get", [Param("cb", OBJ), Param("n", OBJ, cls="Node")], OBJ, {},
              [_cb(), Return(FieldGet(OBJ, _o("n"), "Node", "left"))], pure=False, may_deopt=False)


def f_mid_set():
    return fn("mid_set", [Param("cb", OBJ), Param("n", OBJ, cls="Node"), Param("v", OBJ)], NONE, {},
              [_cb(), FieldSet(_o("n"), "Node", "left", _o("v")), Return()],
              pure=False, may_deopt=False)


def f_mid_new():
    return fn("mid_new", [Param("cb", OBJ), Param("a", OBJ), Param("b", OBJ)], OBJ, {},
              [_cb(), Return(New(OBJ, "Node", (_o("a"), _o("b"))))], pure=False, may_deopt=False)


def f_arg_new():
    return fn("arg_new", [Param("cb", OBJ), Param("b", OBJ)], OBJ, {},
              [Return(New(OBJ, "Node", (CallObject(OBJ, _o("cb"), ()), _o("b"))))],
              pure=False, may_deopt=False)


def f_via_pair():
    return fn("via_pair", [Param("cb", OBJ), Param("a", OBJ), Param("b", OBJ)], OBJ, {},
              [_cb(), Return(Call(OBJ, "pair", (_o("a"), _o("b"))))], pure=False, may_deopt=False)


def f_is_node():
    return fn("is_node", [Param("cb", OBJ), Param("x", OBJ)], BOOL, {},
              [_cb(), Return(IsExact(BOOL, _o("x"), "Node"))], pure=False, may_deopt=False)


def f_fill():
    # closed (no user code), impure (an array store), with an entry global
    return fn("fill", [ArrayParam("a", Type.F64_ARRAY, True), Param("n", OBJ, cls="Node")], NONE,
              {"x": OBJ},
              [Assign("x", FieldGet(OBJ, _o("n"), "Node", "left")),
               StoreIndex("a", c(0), L("SCALE", F64)), Return()],
              pure=False, may_deopt=False, entry_globals=(Param("SCALE", F64),))


def functions():
    return (f_make(), f_check(), f_pair(), f_get_left(), f_setl(), f_mid_get(), f_mid_set(),
            f_mid_new(), f_arg_new(), f_via_pair(), f_is_node(), f_fill())


def source_file(name: str) -> Path:
    import shutil
    d = OUT_ROOT / name
    if d.exists():
        shutil.rmtree(d)
    d.mkdir(parents=True)
    p = d / f"{name}.py"
    p.write_text(SOURCE)
    return p


def generate(name, funcs=None):
    from typedpython import cgen
    funcs = functions() if funcs is None else funcs
    return cgen.generate(Module(name=name, functions=tuple(funcs), classes=CLASSES),
                         source_file(name))


def build(name, flags=None):
    from typedpython import cbuild
    need_runtime()
    src = generate(name)
    kwargs = {} if flags is None else {"flags": flags}
    return cbuild.build(src, name, OUT_ROOT / name, runtime_dir=runtime_dir(), **kwargs)


# --- the child process -------------------------------------------------------------------------------
# Everything below `child_main` runs in a separate interpreter (subprocess) so a crash or a corrupted
# class cannot leak into other tests.

def NOOP():
    return None


def norm(x):
    if x is None or isinstance(x, (bool, int, float, str)):
        return x
    if isinstance(x, (list, tuple)):
        return [norm(i) for i in x]
    if isinstance(x, BaseException):
        return ["exc", type(x).__name__, str(x)]
    d = {"type": type(x).__name__}
    for f in ("left", "right", "extra"):
        try:
            d[f] = norm(getattr(x, f))
        except Exception as e:                   # noqa: BLE001 — the outcome is the result
            d[f] = ["exc", type(e).__name__, str(e)]
    return d


def sc_unchanged(M, call, P):
    P("before")
    t = M.make(3)
    n = M.Node(1, 2)
    out = [norm(t), call("check", t), call("get_left", n), call("setl", n, "x"), norm(n),
           call("pair", 1, 2), call("mid_get", NOOP, n), call("mid_set", NOOP, n, "y"),
           call("mid_new", NOOP, 3, 4), call("arg_new", lambda: "a", 5), call("via_pair", NOOP, 6, 7),
           call("is_node", NOOP, n), call("is_node", NOOP, M.Twin(1, 2))]
    a = [0.0]
    out += [call("fill", a, n), a]
    P("after")
    return out


def sc_prop_get(M, call, P):
    n, n2 = M.Node(1, 2), M.Node(3, 4)
    P("before")
    M.Node.left = property(lambda s: 42)
    out = [call("get_left", n), call("mid_get", NOOP, n2), call("check", n), call("make", 1),
           call("pair", 1, 2), call("mid_new", NOOP, 1, 2), call("via_pair", NOOP, 1, 2),
           call("setl", n, 3), call("arg_new", NOOP, 2), call("get_left", n2)]
    P("after")
    return out


def sc_prop_set(M, call, P):
    log = []
    n = M.Node(1, 2)
    P("before")
    M.Node.left = property(lambda s: ("got", tuple(log)), lambda s, v: log.append(v))
    out = [call("setl", n, "x"), call("mid_set", NOOP, n, "y"), call("get_left", n), list(log),
           call("pair", "p", "q"), list(log), call("mid_new", NOOP, "r", "s"), list(log)]
    P("after")
    return out


def _rebind(M, cls_name, call, P):
    old = M.Node(1, 2)
    warm = [call("make", 1), call("pair", 0, 0), call("mid_new", NOOP, 0, 0),
            call("via_pair", NOOP, 0, 0), call("arg_new", lambda: 0, 0)]  # each New has looked once
    P("before")
    M.Node = getattr(M, cls_name)
    out = warm + [call("make", 2), call("pair", 1, 2), call("mid_new", NOOP, 1, 2),
                  call("via_pair", NOOP, 1, 2), call("arg_new", lambda: "a", 2),
                  call("is_node", NOOP, M.Twin(1, 2)), call("is_node", NOOP, old),
                  call("check", old), call("get_left", old)]
    P("after")
    return out


def sc_rebind_twin(M, call, P):
    return _rebind(M, "Twin", call, P)


def sc_rebind_wide(M, call, P):
    return _rebind(M, "Wide", call, P)


def sc_rebind_back(M, call, P):
    orig = M.Node
    out = [call("make", 1)]
    M.Node = M.Twin
    out += [call("make", 1), call("pair", 1, 2)]
    M.Node = orig
    P("restored")
    out += [call("make", 3), call("pair", 1, 2), call("mid_new", NOOP, 1, 2)]
    P("after")
    return out


def sc_del_global(M, call, P):
    seen = []
    out = [call("make", 1), call("pair", 0, 0), call("arg_new", lambda: 0, 0)]
    P("before")
    del M.Node
    out += [call("make", 1), call("pair", 1, 2), call("mid_new", NOOP, 1, 2),
            call("arg_new", lambda: seen.append("cb") or "a", 2), list(seen),
            call("via_pair", NOOP, 1, 2), call("is_node", NOOP, 5)]
    P("after")
    return out


def sc_mid_prop(M, call, P):
    n = M.Node(1, 2)
    out = [call("mid_get", NOOP, n)]                     # warm: the fast path
    P("before")

    def cb():
        M.Node.left = property(lambda s: 42)
    out += [call("mid_get", cb, n)]
    P("after")
    return out


def sc_mid_prop_set(M, call, P):
    log = []
    n = M.Node(1, 2)
    out = [call("mid_set", NOOP, n, 0)]
    P("before")

    def cb():
        M.Node.left = property(lambda s: "g", lambda s, v: log.append(v))
    out += [call("mid_set", cb, n, "v"), list(log)]
    P("after")
    return out


def sc_mid_rebind(M, call, P):
    out = [call("mid_new", NOOP, 0, 0)]
    P("before")
    out += [call("mid_new", lambda: setattr(M, "Node", M.Twin), 1, 2)]
    P("after")
    return out


def sc_arg_rebind(M, call, P):
    out = [call("arg_new", lambda: 0, 0)]
    P("before")

    def cb():
        M.Node = M.Twin
        return "a"
    out += [call("arg_new", cb, 2), call("arg_new", lambda: "b", 3)]  # the 2nd builds a Twin
    P("after")
    return out


def sc_mid_class(M, call, P):
    n, n2 = M.Node(1, 2), M.Node(3, 4)
    out = [call("mid_get", NOOP, n)]
    P("before")

    def to_shadow():
        n.__class__ = M.Shadow

    def to_twin():
        n2.__class__ = M.Twin
    out += [call("mid_get", to_shadow, n), call("mid_set", to_twin, n2, 9), norm(n2)]
    P("after")
    out += [call("mid_set", NOOP, n, 7)]                  # guard: n is a Shadow now → interpreted
    return out


def sc_mid_is_node(M, call, P):
    out = [call("is_node", NOOP, M.Node(1, 2))]
    P("before")
    out += [call("is_node", lambda: setattr(M, "Node", M.Twin), M.Twin(1, 2))]
    P("after")
    return out


def sc_mid_init(M, call, P):
    def swapped(self, l, r):
        M.Node.left.__set__(self, r)
        M.Node.right.__set__(self, l)
    init = swapped

    def cb():
        M.Node.__init__ = init
    out = [call("mid_new", NOOP, 0, 0), call("via_pair", NOOP, 0, 0)]
    P("before")
    out += [call("mid_new", cb, 1, 2)]
    P("mid")
    out += [call("via_pair", NOOP, 3, 4), call("pair", 5, 6), call("make", 1)]
    P("after")
    return out


def sc_counter(M, call, P):
    t0 = M.make(2)
    P("before")
    M.Node.counter = 1
    n = M.Node(1, 2)
    out = [call("make", 3), call("check", t0), call("get_left", n), call("setl", n, "x"), norm(n),
           call("pair", 1, 2), call("mid_get", NOOP, n), call("mid_new", NOOP, 1, 2)]
    P("after")
    return out


def sc_exhaust(M, call, P):
    n = M.Node(1, 2)
    for i in range(1100):                         # MAX_VERSIONS_PER_CLASS is 1000 in CPython 3.14
        M.Node.x = i
        M.Node.x                                  # a lookup assigns a fresh tag (until exhausted)
    P("before")
    out = [call("make", 2), call("check", M.Node(None, None)), call("get_left", n),
           call("setl", n, "s"), call("mid_get", NOOP, n), call("mid_set", NOOP, n, "t"),
           call("pair", 1, 2), call("via_pair", NOOP, 1, 2), call("mid_new", NOOP, 1, 2)]
    P("after")
    return out


def sc_entry_global(M, call, P):
    n = M.Node(1, 2)
    a = [0.0]
    out = [call("fill", a, n), list(a)]
    P("before")

    def getter(s):
        M.SCALE = 5.0
        return "p"
    M.Node.left = property(getter)
    b = [0.0]
    out += [call("fill", b, n), list(b), M.SCALE]
    P("after")
    return out


SCENARIOS = {k[3:]: v for k, v in dict(globals()).items() if k.startswith("sc_")}


def _exhaust_dict_watchers():
    """Take every free dict-watcher id of this interpreter (ctypes); return them."""
    import ctypes
    CB = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p)
    add = ctypes.pythonapi.PyDict_AddWatcher
    add.argtypes, add.restype = [CB], ctypes.c_int
    keep, ids = [], []
    while True:
        cb = CB(lambda *a: 0)
        keep.append(cb)
        try:
            ids.append(add(cb))
        except RuntimeError:
            break
        if len(ids) > 16:
            raise AssertionError(f"PyDict_AddWatcher never ran out: {ids}")
    _exhaust_dict_watchers.keep = keep
    return ids


def _clear_dict_watchers(ids):
    import ctypes
    clear = ctypes.pythonapi.PyDict_ClearWatcher
    clear.argtypes, clear.restype = [ctypes.c_int], ctypes.c_int
    for i in ids:
        clear(i)


_counter = [0]


def _load_reference(src):
    _counter[0] += 1
    spec = importlib.util.spec_from_file_location(f"tp_ref_{_counter[0]}", src)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _run(M, scenario, compiled):
    probes = []

    def P(label):
        if not compiled:
            return
        info = getattr(M, "__typedpython_class_info__", None)
        probes.append({"label": label, "deopts": M.__typedpython_deopts__,
                       "info": info() if info is not None else None})

    def call(name, *args):
        try:
            return norm(getattr(M, name)(*args))
        except Exception as e:                   # noqa: BLE001
            return ["exc", type(e).__name__, str(e)]
    try:
        out = SCENARIOS[scenario](M, call, P)
    except Exception as e:                       # noqa: BLE001 — a scenario must not raise itself
        out = ["scenario-raised", type(e).__name__, str(e)]
    return out, probes


def child_main(so, src, name, scenarios, exhaust=False):
    from typedpython import cbuild
    report = {"scenarios": {}}
    if exhaust:
        report["taken_watcher_ids"] = _exhaust_dict_watchers()
    for sc in scenarios:
        gc.collect()                             # a dropped instance releases its watcher id
        A = cbuild.load(name, so)
        R = _load_reference(src)
        got, probes = _run(A, sc, True)
        want, _ = _run(R, sc, False)
        report["scenarios"][sc] = {"compiled": got, "reference": want, "probes": probes}
        del A, R
    print("REPORT " + json.dumps(report))


def child_instances(so, name, n, keep_alive):
    """Load `n` instances; report what each says about its dict watcher."""
    from typedpython import cbuild
    kept, out = [], []
    for _ in range(n):
        if not keep_alive:
            gc.collect()
        A = cbuild.load(name, so)
        info = getattr(A, "__typedpython_class_info__", None)
        watched = info()["Node"]["watched"] if info else None
        # correctness on every instance, watched or not: a rebinding seen by New
        A.make(1)
        A.Node = A.Twin
        out.append([watched, type(A.make(1)).__name__, type(A.pair(1, 2)).__name__])
        if keep_alive:
            kept.append(A)
        del A
    print("REPORT " + json.dumps(out))


def child_refcounts(so, name, rounds):
    from typedpython import cbuild
    shared = object()
    A1 = cbuild.load(name, so)                    # unchanged: fast paths
    A2 = cbuild.load(name, so)                    # a property with a setter: slow / deopt paths
    A3 = cbuild.load(name, so)                    # the global rebound: New slow / deopt paths
    A4 = cbuild.load(name, so)                    # tags exhausted: every check fails
    store = []
    A2.Node.left = property(lambda s: shared, lambda s, v: (store.append(v), store.pop()))
    n1, n2, n4 = A1.Node(shared, shared), A2.Node.__new__(A2.Node), A4.Node(shared, shared)
    A2.Node.right.__set__(n2, shared)
    A3.make(1)
    A3.Node = A3.Twin
    for i in range(1100):
        A4.Node.x = i
        A4.Node.x
    cb = lambda: shared                           # noqa: E731

    def round_():
        for M, n in ((A1, n1), (A4, n4)):
            t = M.make(3)
            M.check(t)
            M.get_left(n)
            M.setl(n, shared)
            M.pair(shared, shared)
            M.mid_get(cb, n)
            M.mid_set(cb, n, shared)
            M.mid_new(cb, shared, shared)
            M.via_pair(cb, shared, shared)
            M.arg_new(cb, shared)
            M.is_node(cb, n)
            M.fill([0.0], n)
        A2.get_left(n2)
        A2.mid_get(cb, n2)
        A2.setl(n2, shared)
        A2.mid_set(cb, n2, shared)
        A2.fill([0.0], n2)
        try:
            A2.check(n2)
        except AttributeError:
            pass
        try:
            A2.pair(shared, shared)
        except AttributeError:
            pass
        A3.make(2)
        A3.pair(shared, shared)
        A3.mid_new(cb, shared, shared)
        A3.via_pair(cb, shared, shared)
        A3.arg_new(cb, shared)
        A3.is_node(cb, n1)

    for _ in range(30):
        round_()
    objs = [shared, n1, n2, n4, A1.Node, A2.Node, A3.Node, A3.Twin, A4.Node, cb]
    base = [sys.getrefcount(o) for o in objs]
    d0 = [M.__typedpython_deopts__ for M in (A1, A2, A3, A4)]
    for _ in range(rounds):
        round_()
    after = [sys.getrefcount(o) for o in objs]
    d1 = [M.__typedpython_deopts__ for M in (A1, A2, A3, A4)]
    print("REPORT " + json.dumps({"base": base, "after": after, "deopts": [d0, d1]}))


def _child_cmd(call: str):
    return [sys.executable, "-c",
            f"import sys; sys.path[:0] = [{str(TEST_DIR)!r}, {str(PACKAGE.parent)!r}]; "
            f"import test_cgen_class_changes as T; T.{call}"]


def _report(run):
    for line in run.stdout.splitlines():
        if line.startswith("REPORT "):
            return json.loads(line[len("REPORT "):])
    raise AssertionError(f"child failed (rc={run.returncode}):\n{run.stdout[-4000:]}\n"
                         f"{run.stderr[-6000:]}")


def run_scenarios(so, name, scenarios, exhaust=False, launcher=None, env=None):
    src = OUT_ROOT / name / f"{name}.py"
    cmd = _child_cmd(f"child_main({str(so)!r}, {str(src)!r}, {name!r}, {list(scenarios)!r}, "
                     f"exhaust={exhaust!r})")
    if launcher is not None:
        cmd[0] = str(launcher)
    run = subprocess.run(cmd, capture_output=True, text=True, env=env)
    rep = _report(run)
    assert run.returncode == 0, run.stderr[-4000:]
    return rep, run


# --- fixtures -----------------------------------------------------------------------------------------

NAME = "tp_cls_chg"


@pytest.fixture(scope="module")
def so():
    return build(NAME)


def same(rep, sc):
    r = rep["scenarios"][sc]
    assert r["compiled"] == r["reference"], json.dumps(r, indent=1)
    return r


def probe(r, label):
    for p in r["probes"]:
        if p["label"] == label:
            return p
    raise AssertionError(f"no probe {label}: {r['probes']}")


# --- the IR used here is what the verifier accepts --------------------------------------------------

def test_the_hand_built_ir_passes_the_verifier():
    from typedpython import verify
    proved, diags = verify.verify(Module(name="m", functions=functions(), classes=CLASSES))
    errors = [d for d in diags if getattr(d, "severity", "error") == "error"]
    assert not proved.skipped, (proved.skipped, diags)
    assert {f.name for f in proved.functions} == {f.name for f in functions()}, errors


# --- behaviour: watched module dict (the normal path) ------------------------------------------------

ALL_SCENARIOS = ("unchanged", "prop_get", "prop_set", "rebind_twin", "rebind_wide", "rebind_back",
                 "del_global", "mid_prop", "mid_prop_set", "mid_rebind", "arg_rebind", "mid_class",
                 "mid_is_node", "mid_init", "counter", "exhaust", "entry_global")


@pytest.fixture(scope="module")
def report(so):
    rep, _ = run_scenarios(so, NAME, ALL_SCENARIOS)
    return rep


@pytest.mark.parametrize("sc", ALL_SCENARIOS)
def test_compiled_equals_interpreted_after_the_class_changes(report, sc):
    r = same(report, sc)
    for p in r["probes"]:
        assert p["info"] is not None and p["info"]["Node"]["watched"] is True, p   # watcher path


def test_unchanged_class_stays_on_the_fast_path(report):
    r = same(report, "unchanged")
    b, a = probe(r, "before"), probe(r, "after")
    assert a["deopts"] == b["deopts"]
    assert a["info"]["Node"]["slow"] == b["info"]["Node"]["slow"] == 0
    assert a["info"]["Node"]["current"] is True


@pytest.mark.parametrize("sc", ("mid_prop", "mid_prop_set", "mid_rebind", "arg_rebind", "mid_class",
                                "mid_is_node"))
def test_impure_function_takes_the_slow_path_mid_body_without_a_deopt(report, sc):
    r = same(report, sc)
    b, a = probe(r, "before"), probe(r, "after")
    assert a["deopts"] == b["deopts"], (b, a)                # no deopt after the effect
    if sc != "arg_rebind":                                  # arg_rebind: New was loaded before cb
        assert a["info"]["Node"]["slow"] > b["info"]["Node"]["slow"], (b, a)


def test_property_reads_give_the_interpreted_value(report):
    r = same(report, "prop_get")
    assert r["compiled"][0] == 42 and r["compiled"][1] == 42


def test_property_setter_runs_on_field_set(report):
    r = same(report, "prop_set")
    assert r["compiled"][3] == ["x", "y"]


def test_rebound_global_builds_the_new_class(report):
    for sc, cls in (("rebind_twin", "Twin"), ("rebind_wide", "Wide")):
        r = same(report, sc)
        made = r["compiled"][5:10]
        assert all(isinstance(x, dict) and x["type"] == cls for x in made[1:4]), made


def test_rebinding_back_returns_to_the_fast_path(report):
    r = same(report, "rebind_back")
    assert probe(r, "after")["deopts"] == probe(r, "restored")["deopts"]


def test_mid_body_init_change_and_class_redo_of_a_pure_callee(report):
    r = same(report, "mid_init")
    assert r["compiled"][2] == {"type": "Node", "left": 2, "right": 1, "extra": r["compiled"][2]["extra"]}
    # mid_new (impure) took the slow path, no deopt; via_pair's pure callee `pair` was redone
    assert probe(r, "mid")["deopts"] == probe(r, "before")["deopts"]


def test_unrelated_class_attribute_refreshes_the_tag_back_to_the_fast_path(report):
    r = same(report, "counter")
    b, a = probe(r, "before"), probe(r, "after")
    assert a["deopts"] == b["deopts"], (b, a)
    assert a["info"]["Node"]["slow"] == 0, a
    assert a["info"]["Node"]["refreshed"] >= 1 and a["info"]["Node"]["current"] is True, a
    assert a["info"]["Node"]["captured_tag"] != b["info"]["Node"]["captured_tag"], (b, a)


def test_exhausted_version_tags_take_the_slow_path(report):
    r = same(report, "exhaust")
    b, a = probe(r, "before"), probe(r, "after")
    assert b["info"]["Node"]["tag"] == 0 and a["info"]["Node"]["tag"] == 0, (b, a)
    assert a["info"]["Node"]["current"] is False
    assert a["info"]["Node"]["slow"] > b["info"]["Node"]["slow"]
    assert a["deopts"] > b["deopts"]                         # the pure functions deopted


def test_closed_function_with_an_entry_global_sees_the_rebinding(report):
    r = same(report, "entry_global")
    assert r["compiled"][1] == [2.0] and r["compiled"][3] == [5.0]


# --- behaviour: no dict watcher available ------------------------------------------------------------

FALLBACK = ("rebind_twin", "rebind_wide", "rebind_back", "del_global", "mid_rebind", "arg_rebind",
            "mid_is_node", "unchanged")


def test_without_a_dict_watcher_rebinding_is_still_seen(so):
    rep, run = run_scenarios(so, NAME, FALLBACK, exhaust=True)
    assert 1 <= len(rep["taken_watcher_ids"]) <= 6, rep["taken_watcher_ids"]
    for sc in FALLBACK:
        r = same(rep, sc)
        assert r["probes"], sc
        for p in r["probes"]:
            assert p["info"]["Node"]["watched"] is False, p      # the conservative path ran
    r = rep["scenarios"]["mid_rebind"]
    assert probe(r, "after")["deopts"] == probe(r, "before")["deopts"]


def test_each_instance_gets_and_releases_its_own_watcher(so):
    run = subprocess.run(_child_cmd(f"child_instances({str(so)!r}, {NAME!r}, 12, False)"),
                         capture_output=True, text=True)
    rows = _report(run)
    assert all(w is True for w, _, _ in rows), rows               # released on module free
    assert all(m == "Twin" and p == "Twin" for _, m, p in rows), rows
    run = subprocess.run(_child_cmd(f"child_instances({str(so)!r}, {NAME!r}, 9, True)"),
                         capture_output=True, text=True)
    rows = _report(run)
    assert rows[0][0] is True and rows[-1][0] is False, rows       # ids run out: fallback
    assert all(m == "Twin" and p == "Twin" for _, m, p in rows), rows


# --- reference counts ------------------------------------------------------------------------------

def test_reference_counts_are_flat_over_fast_and_slow_paths(so):
    run = subprocess.run(_child_cmd(f"child_refcounts({str(so)!r}, {NAME!r}, 1500)"),
                         capture_output=True, text=True)
    rep = _report(run)
    assert rep["base"] == rep["after"], rep
    d0, d1 = rep["deopts"]
    assert d1[0] == d0[0] and d1[1] > d0[1] and d1[2] > d0[2] and d1[3] > d0[3], rep


# --- generation contract -------------------------------------------------------------------------------

def test_gen_uses_only_runtime_helpers():
    text = generate("tp_cls_chg_gen")
    for h in ("tp_class_capture", "tp_class_current", "tp_class_global_ok", "tp_getattr",
              "tp_setattr", "tp_globals_watch_event", "tp_globals_unwatch"):
        assert h in text, h
    assert "PyMember_" not in text and "tp_version_tag" not in text
    assert "PyDict_Watch(" not in text and "PyDict_AddWatcher" not in text


# --- mutations ---------------------------------------------------------------------------------------

def _mutant(monkeypatch, name, attr, value):
    from typedpython import cgen, cbuild
    monkeypatch.setattr(cgen, attr, value)
    src = generate(name)
    monkeypatch.undo()
    return cbuild.build(src, name, OUT_ROOT / name, runtime_dir=runtime_dir())


def test_mutation_check_always_current_fails_the_property_test(monkeypatch):
    need_runtime()
    so = _mutant(monkeypatch, "tp_cls_chg_mut1", "_class_current_expr", lambda *a, **k: "1")
    rep, _ = run_scenarios(so, "tp_cls_chg_mut1", ("prop_get",))
    r = rep["scenarios"]["prop_get"]
    assert r["compiled"] != r["reference"], "the mutant (no per-access check) passed prop_get"


def test_mutation_watcher_never_fires_fails_the_rebinding_test(monkeypatch):
    need_runtime()
    so = _mutant(monkeypatch, "tp_cls_chg_mut2", "_watch_event_stmt",
                 lambda: "(void)event; (void)key; return 0;")
    rep, _ = run_scenarios(so, "tp_cls_chg_mut2", ("rebind_twin",))
    r = rep["scenarios"]["rebind_twin"]
    assert r["compiled"] != r["reference"], "the mutant (silent watcher) passed rebind_twin"


# --- sanitizers -----------------------------------------------------------------------------------------

def test_class_change_scenarios_under_address_and_undefined_sanitizers():
    need_runtime()
    cc = sysconfig.get_config_var("CC") or "cc"
    if sys.platform != "darwin":
        pytest.skip("sanitizer preload recipe written for macOS only")
    probe_rt = subprocess.run(cc.split() + ["-print-file-name=libclang_rt.asan_osx_dynamic.dylib"],
                              capture_output=True, text=True)
    if probe_rt.returncode != 0 or not Path(probe_rt.stdout.strip()).is_file():
        pytest.skip(f"host compiler {cc!r} has no ASan runtime ({probe_rt.stdout.strip()!r})")
    from typedpython import cbuild
    name = "tp_cls_chg_asan"
    flags = list(cbuild.DEFAULT_FLAGS) + ["-O1", "-g", "-fno-omit-frame-pointer",
                                          "-fsanitize=address,undefined",
                                          "-fno-sanitize-recover=undefined"]
    so = build(name, flags=flags)
    d = so.parent
    launcher_c, launcher = d / "asan_python.c", d / "asan_python"
    launcher_c.write_text("#include <Python.h>\nint main(int argc, char **argv) "
                          "{ return Py_BytesMain(argc, argv); }\n")
    link = subprocess.run(cc.split() + ["-fsanitize=address,undefined", "-g",
                                        f"-I{sysconfig.get_paths()['include']}", str(launcher_c),
                                        f"-L{sysconfig.get_config_var('LIBDIR')}",
                                        f"-lpython{sysconfig.get_config_var('LDVERSION')}",
                                        "-o", str(launcher)], capture_output=True, text=True)
    if link.returncode != 0:
        pytest.skip(f"cannot link a sanitized launcher: {link.stderr[-2000:]}")
    env = dict(os.environ, PYTHONHOME=sys.base_prefix,
               PYTHONPATH=os.pathsep.join(p for p in sys.path if p and "site-packages" in p),
               ASAN_OPTIONS="detect_leaks=0:abort_on_error=0:halt_on_error=1",
               UBSAN_OPTIONS="halt_on_error=1:print_stacktrace=1")
    env.pop("DYLD_INSERT_LIBRARIES", None)
    run = subprocess.run([str(launcher)] + _child_cmd(
        f"child_main({str(so)!r}, {str(so.parent / (name + '.py'))!r}, {name!r}, "
        f"{list(ALL_SCENARIOS)!r})")[1:], capture_output=True, text=True, env=env)
    out = run.stdout + run.stderr
    if "violates platform policy" in out or "Interceptors are not working" in out:
        pytest.skip(f"ASan runtime could not be loaded: {out[-2000:]}")
    assert run.returncode == 0, out[-6000:]
    rep = _report(run)
    for sc in ALL_SCENARIOS:
        same(rep, sc)
    linked = subprocess.run(["otool", "-L", str(so)], capture_output=True, text=True).stdout
    assert "libclang_rt.asan" in linked, linked
    assert "runtime error" not in out and "AddressSanitizer" not in out, out[-6000:]
