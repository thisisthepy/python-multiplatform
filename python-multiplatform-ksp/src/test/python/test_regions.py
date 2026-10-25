"""TypedPython region allocation for unbounded virtual objects (SPEC N-12b, #268).

A recursive tree built by compiled `make(d)` and consumed by compiled `check(t)` inside one compiled
host (`check(make(d))`) is never observable, so its nodes live in a bump-allocated region of that
activation: no PyObject exists. Design: docs/design/typedpython-regions.md.

Red record: written before any implementation (11 red, 5 green on the first run). The test names keep the
RED / GREEN suffix of that record; the suffix says what the test was before the implementation, all pass
now. A "RED:" message marked a missing feature, not a regression.

Names asserted here (`Function.regions`, `Module.region_variants`, rule `verify/region-escape`,
`__typedpython_region_stats__`, `__typedpython_region_fail_after__`, C symbol `tp_region_open`) are the
implemented ones; rename them in one place (the constants below).
"""
from __future__ import annotations

import dataclasses
import gc
import sys
import textwrap
import threading
import tracemalloc
import types

import pytest

from test_cgen import runtime_dir, need_runtime
from typedpython import cbuild, cgen, cost, frontend, verify

REGION_ESCAPE = "verify/region-escape"
FN_FIELD = "regions"                 # Function.regions: the region scopes the function opens
MOD_FIELD = "region_variants"        # Module.region_variants: functions that get a hidden-region clone
C_OPEN = "tp_region_open"
STATS = "__typedpython_region_stats__"          # () -> (opened, freed, objects)
FAIL_AFTER = "__typedpython_region_fail_after__"  # (n) -> the nth region object allocation fails; -1 off

SOURCE = textwrap.dedent('''\
    # typedpython: compiled
    """typedpython region test module."""

    KEPT = None


    class Node:
        __slots__ = ("left", "right")
        left: "Node | None"
        right: "Node | None"

        def __init__(self, left: "Node | None", right: "Node | None") -> None:
            self.left = left
            self.right = right


    def make(depth: int) -> Node:
        if depth > 0:
            return Node(make(depth - 1), make(depth - 1))
        return Node(None, None)


    def check(node: Node) -> int:
        left: Node | None = node.left
        right: Node | None = node.right
        if left is None or right is None:
            return 1
        return 1 + check(left) + check(right)


    def tree_sum(d: int) -> int:
        return check(make(d))


    def repeat(d: int, n: int) -> int:
        t: int = 0
        for _ in range(n):
            t += check(make(d))
        return t


    def escapes(d: int) -> Node:
        return make(d)


    def keeps(d: int) -> int:
        global KEPT
        KEPT = make(d)
        return check(KEPT)


    def prints(d: int) -> int:
        n = make(d)
        print("unknown code while the tree is alive")
        return check(n)


    def ident(d: int) -> int:
        return id(make(d)) * 0


    def same(d: int) -> bool:
        return make(d) is make(d)


    def mixed(d: int) -> int:
        t = make(d)
        return check(make(d)) + check(t)


    def noisy(d: int) -> object:
        print("unknown code")
        return check(make(d))
''')

HOSTS = ("tree_sum", "repeat")                       # must open a region
NOT_HOSTS = ("escapes", "keeps", "prints", "ident", "same", "mixed", "noisy", "make", "check")


def region_of(fn) -> tuple:
    return tuple(getattr(fn, FN_FIELD, ()) or ())


def red(cond: bool, what: str) -> None:
    assert cond, f"RED: not implemented yet: {what}"


@pytest.fixture(scope="module")
def built(tmp_path_factory):
    need_runtime()
    d = tmp_path_factory.mktemp("regmod")
    path = d / "regmod.py"
    path.write_text(SOURCE)
    proved, diags = verify.verify(frontend.lower(path))
    proved, diags = cost.apply(proved, diags)
    text = cgen.generate(proved, path, path.name)
    so = cbuild.build(text, "regmod", d / "out", runtime_dir=runtime_dir())
    ref = types.ModuleType("regref")
    exec(compile(SOURCE, "regref", "exec"), ref.__dict__)
    return types.SimpleNamespace(path=path, proved=proved, diags=diags, text=text, ref=ref,
                                 m=cbuild.load("regmod", so),
                                 fns={f.name: f for f in proved.functions})


def nodes(d: int) -> int:
    return 2 ** (d + 1) - 1


# --- the verifier proves the region (RED) --------------------------------------------------------------

def test_hosts_open_a_region_RED(built):
    for name in HOSTS:
        assert name in built.fns, f"{name} must compile (it is pure)"
        red(len(region_of(built.fns[name])) >= 1, f"{name} opens no region (Function.{FN_FIELD})")


def test_make_and_check_get_region_variants_RED(built):
    variants = set(getattr(built.proved, MOD_FIELD, ()) or ())
    red(variants == {"make", "check"}, f"Module.{MOD_FIELD} is {sorted(variants)}, want make and check")


def test_a_forged_region_on_an_escaping_function_is_rejected_RED(built):
    red(any(f.name == "regions" for f in dataclasses.fields(type(built.fns["escapes"]))),
        f"ir.Function has no field {FN_FIELD}")
    forged = dataclasses.replace(built.fns["escapes"], **{FN_FIELD: ("make",)})
    mod = dataclasses.replace(built.proved, functions=tuple(
        forged if f.name == "escapes" else f for f in built.proved.functions))
    _, diags = verify.verify(mod)
    assert any(d.rule == REGION_ESCAPE for d in diags)


def test_the_non_hosts_open_no_region_GREEN(built):
    """Negative cases (return, global store, unknown code alive, id, is): green now only because
    nothing is ever a region; the mutation test below is what makes this able to fail."""
    for name in NOT_HOSTS:
        if name in built.fns:
            assert region_of(built.fns[name]) == (), name


def test_the_region_node_set_holds_no_unknown_code(built):
    from typedpython import ir
    assert hasattr(verify, "_REGION_OK"), "verify._REGION_OK (the node set a region host may contain)"
    assert ir.Call in verify._REGION_OK and ir.New in verify._REGION_OK
    for node in (ir.CallObject, ir.GetAttr, ir.Global, ir.Truth, ir.CompareObj, ir.FieldSet):
        assert node not in verify._REGION_OK, node.__name__


def test_mutation_disabling_the_escape_check_turns_the_escaping_cases_into_hosts(built, monkeypatch):
    """The escape check (`verify._RegionScan.fail`: every way a region value or unknown code can get
    into a function) is what keeps `mixed` (a producer result held in a local) out of a region. With the
    check disabled it becomes a host. (`noisy`, an impure function that prints, stays a non-host even
    then: an impure caller's calls carry `redo`, which is not the shape of a region site either.)

    The earlier version of this test mutated `verify._REGION_OK` with CallObject and expected `prints` to
    become a host. That cannot be expressed: `prints` is impure, the front end does not compile it at
    all (`check(n)` is an object in an impure function), and the host rule also requires purity, which
    is not a node-set property. `mixed` is compiled, so the mutation is observable."""
    assert "mixed" in built.fns and region_of(built.fns["mixed"]) == ()
    monkeypatch.setattr(verify._RegionScan, "fail", lambda self: None)
    mutated, _ = verify.verify(frontend.lower(built.path))
    got = {f.name: region_of(f) for f in mutated.functions if f.name == "mixed"}
    assert got == {"mixed": ("make",)}, got


# --- the generated C ------------------------------------------------------------------------------------

def test_the_host_opens_a_region_in_c_RED(built):
    red(C_OPEN in built.text, f"no {C_OPEN} in the generated C")


# --- the runtime: counters, no PyObject, cost (RED) -----------------------------------------------------

def test_one_region_per_call_holding_every_node_RED(built):
    stats = getattr(built.m, STATS, None)
    red(stats is not None, STATS)
    o0, f0, n0 = stats()
    assert built.m.tree_sum(6) == nodes(6)
    o1, f1, n1 = stats()
    assert (o1 - o0, f1 - f0, n1 - n0) == (1, 1, nodes(6))


def test_a_loop_resets_the_region_per_statement_RED(built):
    stats = getattr(built.m, STATS, None)
    red(stats is not None, STATS)
    o0, f0, n0 = stats()
    assert built.m.repeat(4, 50) == 50 * nodes(4)
    o1, f1, n1 = stats()
    # one region per statement execution: memory is bounded by one tree, not by 50 trees
    assert (o1 - o0, f1 - f0, n1 - n0) == (50, 50, 50 * nodes(4))


def test_no_python_object_is_allocated_for_the_tree_RED(built):
    """tracemalloc sees every PyMem allocation: the interpreter's tree is large, the region's is not."""
    d = 12

    def peak(fn):
        tracemalloc.start()
        try:
            tracemalloc.reset_peak()
            fn(d)
            return tracemalloc.get_traced_memory()[1]
        finally:
            tracemalloc.stop()

    interp = peak(built.ref.tree_sum)
    compiled = peak(built.m.tree_sum)
    assert interp > 100_000
    red(compiled < interp // 10, f"compiled peak {compiled} B is not far below interpreted {interp} B")


def test_region_allocation_failure_deopts_and_frees_RED(built):
    fail = getattr(built.m, FAIL_AFTER, None)
    red(fail is not None, FAIL_AFTER)
    stats = getattr(built.m, STATS)
    d0 = built.m.__typedpython_deopts__
    o0, f0, _ = stats()
    fail(10)
    try:
        assert built.m.tree_sum(5) == nodes(5)             # the interpreter redoes the call
    finally:
        fail(-1)
    o1, f1, _ = stats()
    assert built.m.__typedpython_deopts__ == d0 + 1
    assert (o1 - o0, f1 - f0) == (1, 1)


def test_class_change_deopts_and_frees_the_region_RED(built):
    stats = getattr(built.m, STATS, None)
    red(stats is not None, STATS)
    saved = built.m.Node

    class Node2:
        __slots__ = ("left", "right")

        def __init__(self, left, right):
            self.left, self.right = left, right

    o0, f0, _ = stats()
    try:
        built.m.Node = Node2
        assert built.m.tree_sum(3) == built.ref.tree_sum(3)
    finally:
        built.m.Node = saved
    o1, f1, _ = stats()
    assert o1 - o0 == f1 - f0                                # whatever opened was freed


def test_threads_never_find_a_region_object_RED(built):
    stats = getattr(built.m, STATS, None)
    red(stats is not None, STATS)
    stop = threading.Event()
    seen = []

    def finder():
        while not stop.is_set():
            seen.append(sum(1 for o in gc.get_objects() if type(o) is built.m.Node))

    def work():
        for i in range(200):
            assert built.m.tree_sum(i % 8) == nodes(i % 8)

    o0, f0, _ = stats()
    t = threading.Thread(target=finder)
    ws = [threading.Thread(target=work) for _ in range(4)]
    t.start()
    for w in ws:
        w.start()
    for w in ws:
        w.join()
    stop.set()
    t.join()
    o1, f1, _ = stats()
    assert o1 - o0 == 800 and f1 - f0 == 800
    assert max(seen, default=0) == 0                          # no Node was ever visible to gc


# --- differential results and observations (GREEN now, must stay) ---------------------------------------

def test_results_equal_the_interpreter_GREEN(built):
    for d in (0, 1, 5, 10):
        assert built.m.tree_sum(d) == built.ref.tree_sum(d)
        assert built.m.repeat(d, 3) == built.ref.repeat(d, 3)
    assert built.m.prints(3) == built.ref.prints(3)
    assert built.m.ident(3) == 0 and built.m.same(2) is False


def test_an_escaping_make_still_returns_real_nodes_GREEN(built):
    t = built.m.escapes(3)
    assert type(t) is built.m.Node and type(t.left) is built.m.Node
    assert built.m.check(t) == nodes(3)               # the normal entry of check, real objects
    assert built.m.keeps(3) == nodes(3) and built.m.KEPT is not None


def test_gc_and_allocation_counts_are_unchanged_by_a_call_GREEN(built):
    built.m.tree_sum(8)                                # warm up lazily created state
    gc.collect()
    nodes = lambda: sum(1 for o in gc.get_objects() if type(o) is built.m.Node)
    n0, b0 = nodes(), sys.getallocatedblocks()
    for _ in range(5):
        built.m.tree_sum(8)
    gc.collect()
    assert nodes() == n0           # no Node is left behind (counting all objects saw pytest's own)
    assert abs(sys.getallocatedblocks() - b0) < 1000   # a leaked tree is ~2300 blocks per call


def test_tracing_runs_the_interpreted_function_GREEN(built):
    def run(fn):
        events = []

        def tracer(frame, event, arg):
            if frame.f_code.co_name in ("tree_sum", "make", "check"):
                events.append((frame.f_code.co_name, event))
            return tracer

        sys.settrace(tracer)
        try:
            r = fn(2)
        finally:
            sys.settrace(None)
        return r, events

    assert run(built.m.tree_sum) == run(built.ref.tree_sum)


# --- more cases (added with the implementation) ---------------------------------------------------------

def test_the_python_visible_entries_never_use_a_region(built):
    stats = built.m.__typedpython_region_stats__
    s0 = stats()
    t = built.m.make(6)
    assert type(t) is built.m.Node and built.m.check(t) == nodes(6)
    built.m.escapes(4)
    assert stats() == s0                                  # no region was opened by a Python-visible entry
    assert not [n for n in dir(built.m) if "rimpl" in n]  # the internal entries have no Python name


def test_a_forged_module_level_variant_set_is_reported(built):
    forged = dataclasses.replace(built.proved, region_variants=("tree_sum",))
    _, diags = verify.verify(forged)
    assert any(d.rule == REGION_ESCAPE for d in diags)


def test_a_finalizer_or_a_field_store_keeps_the_class_out_of_a_region(tmp_path):
    base = SOURCE.split("def escapes")[0]
    with_del = base.replace("        self.right = right\n",
                            "        self.right = right\n\n    def __del__(self) -> None:\n        pass\n", 1)
    with_store = base + textwrap.dedent('''\
        def poke(node: Node) -> int:
            node.left = None
            return 0
        ''')
    for name, text in (("plain", base), ("del", with_del), ("store", with_store)):
        path = tmp_path / f"{name}.py"
        path.write_text(text)
        mod, _ = verify.verify(frontend.lower(path))
        hosts = {f.name for f in mod.functions if f.regions}
        assert hosts == ({"tree_sum", "repeat"} if name == "plain" else set()), (name, hosts)


# --- scalar and mixed fields: hand-built IR, the front end cannot read a scalar field ----------------

SCALAR_SOURCE = textwrap.dedent('''\
    class Cell:
        __slots__ = ("val", "w", "ok", "next")

        def __init__(self, val, w, ok, next):
            self.val = val
            self.w = w
            self.ok = ok
            self.next = next


    def build(n, big):
        if n > 0:
            return Cell(n * big, n * 0.5, n % 2 == 0, build(n - 1, big))
        return Cell(0, 0.0, True, None)


    def total(c):
        acc = c.w + c.val
        if c.ok:
            acc += 1.0
        if c.next is None:
            return acc
        return acc + total(c.next)


    def chain_total(n, big):
        return total(build(n, big))
''')


def _scalar_module():
    from typedpython import ir
    from typedpython.ir import (Assign, BinOp, BinOpKind, Box, Call, ClassDecl, Compare, CompareKind, Const,
                                FieldGet, If, Is, Module, New, Param, Return, ToFloat, Type, Unbox)
    from test_cgen import I64, F64, BOOL, OBJ, c, L, fn
    cell = ClassDecl("Cell", ("val", "w", "ok", "next"), True)

    def get(field):
        return FieldGet(OBJ, L("c", OBJ), "Cell", field)

    n, big = L("n", I64), L("big", I64)
    node = New(OBJ, "Cell", (
        Box(OBJ, BinOp(I64, BinOpKind.MUL, n, big)),
        Box(OBJ, BinOp(F64, BinOpKind.MUL, ToFloat(F64, n), c(0.5))),
        Box(OBJ, Compare(BOOL, CompareKind.EQ, BinOp(I64, BinOpKind.MOD, n, c(2)), c(0))),
        Call(OBJ, "build", (BinOp(I64, BinOpKind.SUB, n, c(1)), big))))
    leaf = New(OBJ, "Cell", (Box(OBJ, c(0)), Box(OBJ, c(0.0)), Box(OBJ, c(True)), Const(OBJ, None)))
    build = fn("build", [Param("n", I64), Param("big", I64)], OBJ, {},
               [If(Compare(BOOL, CompareKind.GT, n, c(0)), (Return(node),)), Return(leaf)],
               pure=True, may_deopt=True)
    acc = L("acc", F64)
    total = fn("total", [Param("c", OBJ, cls="Cell")], F64, {"acc": F64},
               [Assign("acc", BinOp(F64, BinOpKind.ADD, Unbox(F64, get("w")),
                                    ToFloat(F64, Unbox(I64, get("val"))))),
                If(Unbox(BOOL, get("ok")), (Assign("acc", BinOp(F64, BinOpKind.ADD, acc, c(1.0))),)),
                If(Is(BOOL, get("next"), Const(OBJ, None)), (Return(acc),)),
                Return(BinOp(F64, BinOpKind.ADD, acc, Call(F64, "total", (get("next"),))))],
               pure=True, may_deopt=True)
    host = fn("chain_total", [Param("n", I64), Param("big", I64)], F64, {},
              [Return(Call(F64, "total", (Call(OBJ, "build", (n, big)),)))], pure=True, may_deopt=True)
    return Module(name="tp_region_scalar", functions=(build, total, host), classes=(cell,))


@pytest.fixture(scope="module")
def scalar(tmp_path_factory):
    need_runtime()
    d = tmp_path_factory.mktemp("regscalar")
    path = d / "tp_region_scalar.py"
    path.write_text(SCALAR_SOURCE)
    proved, diags = verify.verify(_scalar_module())
    assert not diags, diags
    text = cgen.generate(proved, path, path.name)
    so = cbuild.build(text, "tp_region_scalar", d / "out", runtime_dir=runtime_dir())
    return types.SimpleNamespace(proved=proved, text=text, m=cbuild.load("tp_region_scalar", so))


def test_scalar_and_pointer_fields_share_one_node(scalar):
    assert scalar.proved.region_variants == ("build", "total")
    assert {f.name: f.regions for f in scalar.proved.functions}["chain_total"] == ("build",)
    for member in (".i =", ".f =", ".p ="):
        assert member in scalar.text, member
    m = scalar.m
    ref = m.__typedpython_interpreted__
    stats = m.__typedpython_region_stats__
    for n in (0, 1, 2, 9, 40):
        o0, f0, k0 = stats()
        assert m.chain_total(n, 3) == ref["chain_total"](n, 3)
        o1, f1, k1 = stats()
        assert (o1 - o0, f1 - f0, k1 - k0) == (1, 1, n + 1)


def test_an_overflow_inside_the_region_deopts_and_frees(scalar):
    m = scalar.m
    stats = m.__typedpython_region_stats__
    d0 = m.__typedpython_deopts__
    o0, f0, _ = stats()
    big = 2 ** 62                                        # n * big leaves i64 for n >= 2
    got = m.chain_total(5, big)
    o1, f1, _ = stats()
    d1 = m.__typedpython_deopts__
    assert got == m.__typedpython_interpreted__["chain_total"](5, big)
    assert d1 >= d0 + 1                                  # the host deopted (the interpreted redo may deopt callees too)
    assert o1 - o0 == f1 - f0 >= 1


def test_the_recursion_limit_error_frees_the_region(scalar):
    m = scalar.m
    stats = m.__typedpython_region_stats__
    o0, f0, _ = stats()
    with pytest.raises(RecursionError):
        m.chain_total(sys.getrecursionlimit() + 500, 1)
    with pytest.raises(RecursionError):
        m.__typedpython_interpreted__["chain_total"](sys.getrecursionlimit() + 500, 1)
    o1, f1, _ = stats()
    assert o1 - o0 == f1 - f0 >= 1


def _chunks(m):
    return m.__typedpython_region_chunks__()


def test_every_chunk_is_freed_on_every_exit_edge(built):
    """The common exit block frees the chunks on the normal, deopt and error edges alike."""
    m = built.m
    a0, f0 = _chunks(m)
    assert m.tree_sum(10) == nodes(10)                                  # normal exit, several chunks grown
    assert m.repeat(6, 20) == 20 * nodes(6)                             # loop: chunks reused, freed once
    m.__typedpython_region_fail_after__(100)
    try:
        assert m.tree_sum(9) == nodes(9)                                # allocation failure: deopt edge
    finally:
        m.__typedpython_region_fail_after__(-1)
    saved = m.Node

    class Node2:
        __slots__ = ("left", "right")

        def __init__(self, left, right):
            self.left, self.right = left, right

    try:
        m.Node = Node2
        m.tree_sum(3)                                                   # class change: deopt edge
    finally:
        m.Node = saved
    a1, f1 = _chunks(m)
    assert a1 - a0 >= 3 and a1 - a0 == f1 - f0                         # chunks were used, and all came back


def test_an_exception_raised_while_the_tree_is_being_built_frees_the_region(built):
    """The error edge (-1) after nodes were allocated: a signal handler raises at the eval-breaker poll
    that every compiled entry makes, in the middle of building a tree of 8M nodes."""
    import signal

    def boom(*_):
        raise TimeoutError("tick")

    m = built.m
    a0, f0 = _chunks(m)
    o0, fr0, k0 = m.__typedpython_region_stats__()
    old = signal.signal(signal.SIGALRM, boom)
    try:
        signal.setitimer(signal.ITIMER_REAL, 0.02)
        with pytest.raises(TimeoutError):
            m.tree_sum(22)
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, old)
    a1, f1 = _chunks(m)
    o1, fr1, k1 = m.__typedpython_region_stats__()
    assert a1 - a0 >= 1 and a1 - a0 == f1 - f0
    assert (o1 - o0, fr1 - fr0) == (1, 1) and 0 < k1 - k0 < nodes(22)


def test_a_loop_grows_the_region_once_not_per_statement(built):
    m = built.m
    a0, _ = _chunks(m)
    assert m.repeat(5, 200) == 200 * nodes(5)           # 63 nodes * 16 B < one 4 KiB chunk, reused 200 times
    a1, _ = _chunks(m)
    assert a1 - a0 == 1


# --- a class change while a consumer runs (signal handler at a poll) ----------------------------------

def _with_property_installed_by_handler(fn, node_cls, delay):
    import signal

    def boom(*_):
        node_cls.left = property(lambda self: None, lambda self, v: None)   # what `n.left` means changes

    old = signal.signal(signal.SIGALRM, boom)
    try:
        signal.setitimer(signal.ITIMER_REAL, delay)
        return fn()
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, old)


def test_a_class_change_by_a_signal_handler_in_the_consumer_deopts_the_region_host(tmp_path):
    """The handler runs at a tp_poll inside the call and installs a property `left` on Node. The timer is
    set to 85% of a measured call, so it lands mostly in `check` (the producer's per-New guard cannot
    be what catches it). Every read of a region field repeats the class check of a real FieldGet; without
    it the compiled call returns the full node count, which no CPython execution can (a read of
    `left` after the handler ran is None, so `check` returns 1 at the first read after it)."""
    import time
    need_runtime()
    path = tmp_path / "regsig.py"
    path.write_text(SOURCE)
    proved, _ = verify.verify(frontend.lower(path))
    text = cgen.generate(proved, path, path.name)
    so = cbuild.build(text, "regsig", tmp_path / "out", runtime_dir=runtime_dir())
    m = cbuild.load("regsig", so)
    d = 21
    t0 = time.perf_counter()
    assert m.tree_sum(d) == nodes(d)
    delay = 0.85 * (time.perf_counter() - t0)
    d0 = m.__typedpython_deopts__
    o0, f0, _ = m.__typedpython_region_stats__()
    got = _with_property_installed_by_handler(lambda: m.tree_sum(d), m.Node, delay)
    assert m.__typedpython_deopts__ > d0                  # the host noticed and redid the call
    o1, f1, _ = m.__typedpython_region_stats__()
    assert o1 - o0 == f1 - f0
    # the redo ran interpreted with the property in place: `left` is None, so check returns 1
    assert got == 1
