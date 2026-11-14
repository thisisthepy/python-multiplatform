"""TypedPython object cost without a change in what Python can observe (#125, design 4.3.4
"참조 옮기기와 빌린 피연산자", cgen module docstring rules 3 and 3a).

Two changes of the generated C, both removing a +1/-1 reference-count pair on an object that another
reference keeps alive, with nothing in between that can run code or reach a safepoint:
  - a New moves its argument temporaries' references into the fresh object's slots
    (`tp_new_fixed_move`) instead of copying them and releasing the temporaries;
  - an operand a helper only borrows (the two sides of Is, the object of FieldGet / IsExact, an OBJ
    argument of a C to C Call) names the OBJ local itself instead of a Py_NewRef copy.

The behaviour tests compare every observation the leader's constraint (2026-11-13) lists, gc
tracking, gc.get_objects, weakrefs and their callbacks, __del__ order, sys.getrefcount, id
uniqueness, subclasses and a class changed after import, with the interpreted functions the
extension keeps. They pass before and after the change: they guard the preserved behaviour, they do
not measure the change. The mutation tests show that the guarded properties fail when the move or the
borrow is done wrongly (a copy that leaks, a release that frees too early).

Red record (expected; agents do not build here, AGENTS.md 10, so CI runs this file):
  - before the change: test_gen_new_moves_*, test_gen_borrowed_operands_*, test_header_move_*
    fail (no tp_new_fixed_move, Py_NewRef copies of locals), and the three mutation tests fail with
    AttributeError (no cgen._moved_temps / cgen._operand_release) or a compile error (no
    TP_MOVED_REF); every behaviour test passes;
  - after the change: everything passes.
"""
from __future__ import annotations

import gc
import subprocess
import sys
import textwrap
import threading
import weakref

import pytest

from test_cgen import PACKAGE, OUT_ROOT, BOOL, OBJ, L, fn, runtime_dir, need_runtime
from test_cgen_classes import f_make, f_check
from typedpython.ir import ClassDecl, FieldGet, Is, Module, New, Param, Return, Const

SOURCE = textwrap.dedent('''\
    """typedpython allocation test module, the interpreted reference."""
    LOG = []

    class Node:
        __slots__ = ("left", "right")

        def __init__(self, left, right):
            self.left = left
            self.right = right

    class WNode:                           # weak-referenceable
        __slots__ = ("left", "right", "__weakref__")

        def __init__(self, left, right):
            self.left = left
            self.right = right

    class DNode:                           # a finalizer from the start
        __slots__ = ("left", "right")

        def __init__(self, left, right):
            self.left = left
            self.right = right

        def __del__(self):
            LOG.append(id(self))

    class LNode:                           # gains __del__ after import (a test sets it)
        __slots__ = ("left", "right")

        def __init__(self, left, right):
            self.left = left
            self.right = right

    def make(depth):
        if depth == 0:
            return None
        return Node(make(depth - 1), make(depth - 1))

    def check(node):
        if node is None:
            return 0
        return 1 + check(node.left) + check(node.right)

    def make_w(depth):
        if depth == 0:
            return None
        return WNode(make_w(depth - 1), make_w(depth - 1))

    def make_d(depth):
        if depth == 0:
            return None
        return DNode(make_d(depth - 1), make_d(depth - 1))

    def make_l(depth):
        if depth == 0:
            return None
        return LNode(make_l(depth - 1), make_l(depth - 1))

    def pair(x):
        return Node(x, x)

    def first(p):
        return p.left

    def is_none(x):
        return x is None
    ''')

CLASSES = (ClassDecl("Node", ("left", "right"), True), ClassDecl("WNode", ("left", "right"), True),
           ClassDecl("DNode", ("left", "right"), True, has_del=True),
           ClassDecl("LNode", ("left", "right"), True))


def f_pair():
    x = L("x", OBJ)
    return fn("pair", [Param("x", OBJ)], OBJ, {}, [Return(New(OBJ, "Node", (x, x)))],
              pure=True, may_deopt=True)


def f_first():
    return fn("first", [Param("p", OBJ, cls="Node")], OBJ, {},
              [Return(FieldGet(OBJ, L("p", OBJ), "Node", "left"))], pure=True, may_deopt=True)


def f_is_none():
    return fn("is_none", [Param("x", OBJ)], BOOL, {},
              [Return(Is(BOOL, L("x", OBJ), Const(OBJ, None)))], pure=True, may_deopt=False)


def functions():
    return (f_make(), f_check(), f_make("make_w", "WNode"), f_make("make_d", "DNode"),
            f_make("make_l", "LNode"), f_pair(), f_first(), f_is_none())


def generate(name):
    import shutil
    from typedpython import cgen
    d = OUT_ROOT / name
    if d.exists():
        shutil.rmtree(d)
    d.mkdir(parents=True)
    p = d / f"{name}.py"
    p.write_text(SOURCE)
    return cgen.generate(Module(name=name, functions=functions(), classes=CLASSES), p)


def build(name, flags=None):
    from typedpython import cbuild
    need_runtime()
    src = generate(name)
    kwargs = {} if flags is None else {"flags": flags}
    so = cbuild.build(src, name, OUT_ROOT / name, runtime_dir=runtime_dir(), **kwargs)
    return cbuild.load(name, so), so


@pytest.fixture(scope="module")
def m():
    return build("tp_alloc_pres")[0]


def interp(m, name):
    return m.__typedpython_interpreted__[name]


def nodes(t):
    """Every node of a tree, pre-order."""
    out, todo = [], [t]
    while todo:
        n = todo.pop()
        if n is None:
            continue
        out.append(n)
        todo.append(n.right)
        todo.append(n.left)
    return out


def impl_body(text, name):
    """The definition (not the forward declaration) of the counted impl of `name`."""
    sig = f"static int tp_impl_{name}(PyObject *tp_module, PyThreadState"
    start = text.index(sig)
    while text[start:text.index("\n", start)].endswith(";"):      # skip the declaration
        start = text.index(sig, start + 1)
    return text[start:text.index("\n}\n", start)]


# --- the generated C (red before the change) --------------------------------------------------------

def test_gen_new_moves_its_argument_temporaries():
    text = generate("tp_alloc_gen_new")
    assert "tp_new_fixed(" not in text, "a New still copies its arguments (tp_new_fixed)"
    lines = text.splitlines()
    sites = [i for i, ln in enumerate(lines) if "= tp_new_fixed_move(" in ln]
    assert sites, text
    for i in sites:
        t = lines[i].split("=")[0].strip()
        nxt = lines[i + 1].strip()
        assert nxt.startswith(f"if ({t} != NULL) {{") and "= NULL;" in nxt, (lines[i], nxt)


def test_gen_borrowed_operands_take_no_copy():
    text = generate("tp_alloc_gen_borrow")
    check = impl_body(text, "check")
    # adoption of the parameter stays (rule 1); no copy of the local
    assert "l_node = Py_NewRef(p_node);" in check and "Py_NewRef(l_node)" not in check, check
    assert "(l_node == Py_None)" in check, check
    assert "tp_field_get(l_node," in check, check
    assert "tp_impl_check(tp_module, tp_ts, tp_t, l_l," in check, check
    assert "tp_impl_check(tp_module, tp_ts, tp_t, l_r," in check, check
    assert "Py_NewRef(Py_None)" not in check, check
    assert "tp_release(&l_" not in check.split("tp_exit:")[0], "a borrowed local is released"
    first = impl_body(text, "first")
    assert "tp_field_get(l_p," in first and "Py_NewRef(l_p)" not in first, first


def test_header_move_helper_moves_and_gives_back_on_failure():
    from typedpython import cbuild
    h = (cbuild.RUNTIME_DIR / "tp_runtime.h").read_text()
    start = h.index("static inline PyObject *tp_new_fixed_move(")
    body = h[start:h.index("\n}\n", start)]
    assert "Py_NewRef(" not in body, body                 # the caller's reference moves in
    assert "TP_MOVED_REF(values[i])" in body, body         # the single mutation point
    assert "__atomic_store_n(" in body and "__ATOMIC_RELEASE" in body, body
    assert "PyMember_SetOne" not in body and "CRITICAL_SECTION" not in body, body
    # on a failure after some stores, those slots are emptied before the object is released
    fail = body[body.index("values[i] == NULL"):]
    assert fail.index("= NULL;") < fail.index("Py_DECREF(obj)"), body


# --- observable behaviour, compiled against interpreted (green before and after) -------------------

def test_results_match_the_interpreter(m):
    for d in (0, 1, 5):
        assert m.check(m.make(d)) == interp(m, "check")(interp(m, "make")(d)) == 2 ** d - 1
    x = object()
    p, q = m.pair(x), interp(m, "pair")(x)
    assert type(p) is type(q) is m.Node and p.left is p.right is x and q.left is x
    assert m.first(p) is x and m.is_none(None) is True and m.is_none(p) is False


def test_compiled_objects_are_gc_tracked_like_interpreted_ones(m):
    t, u = m.make(4), interp(m, "make")(4)
    assert [gc.is_tracked(n) for n in nodes(t)] == [gc.is_tracked(n) for n in nodes(u)]
    assert all(gc.is_tracked(n) for n in nodes(t))
    assert gc.is_tracked(m.pair(None)) and gc.is_tracked(interp(m, "pair")(None))


def test_compiled_objects_are_in_gc_get_objects(m):
    t = m.make(5)
    live = {id(o) for o in gc.get_objects() if type(o) is m.Node}
    assert all(id(n) in live for n in nodes(t))
    assert len(nodes(t)) == 31


def test_ids_are_unique_while_alive(m):
    t = m.make(7)
    keep = [m.pair(None) for _ in range(100)]
    ids = [id(n) for n in nodes(t)] + [id(p) for p in keep]
    assert len(ids) == len(set(ids)) == 127 + 100


def test_weakref_callbacks_run_in_the_interpreters_order(m):
    def order(make):
        t = make(4)
        index = {id(n): k for k, n in enumerate(nodes(t))}
        log = []
        refs = [weakref.ref(n, lambda r, k=index[id(n)]: log.append(k)) for n in nodes(t)]
        assert all(r() is not None for r in refs)
        del t
        assert all(r() is None for r in refs)
        return log
    got, want = order(m.make_w), order(interp(m, "make_w"))
    assert got == want and sorted(got) == list(range(15)), (got, want)


def test_del_runs_once_per_object_in_the_interpreters_order(m):
    def order(make):
        m.LOG.clear()
        t = make(4)
        index = {id(n): k for k, n in enumerate(nodes(t))}
        assert m.LOG == []
        del t
        return [index[i] for i in m.LOG]
    got, want = order(m.make_d), order(interp(m, "make_d"))
    assert got == want and sorted(got) == list(range(15)), (got, want)


def test_a_class_that_gains_del_later_finalizes_compiled_objects(m):
    t = m.make_l(3)
    m.LNode.__del__ = lambda self: m.LOG.append(id(self))
    try:
        m.LOG.clear()
        ids = {id(n) for n in nodes(t)}
        del t
        assert set(m.LOG) == ids and len(m.LOG) == 7
        m.LOG.clear()
        u = m.make_l(3)                     # built after the change (the class's tag moved)
        ids = {id(n) for n in nodes(u)}
        del u
        assert set(m.LOG) == ids and len(m.LOG) == 7
    finally:
        del m.LNode.__del__
        m.LOG.clear()


def test_a_subclass_with_del_inside_a_compiled_pair_dies_when_the_pair_dies(m):
    log = []

    class Sub(m.Node):
        __slots__ = ()

        def __del__(self):
            log.append("sub")

    s = Sub(None, None)
    p = m.pair(s)
    del s
    assert log == []                            # the pair's two slots keep it alive
    assert m.check(Sub(None, None)) == interp(m, "check")(Sub(None, None)) == 1
    log.clear()
    del p
    assert log == ["sub"]


def test_reference_counts_match_the_interpreter_at_every_call_boundary(m):
    x = object()
    base = sys.getrefcount(x)
    p = m.pair(x)
    compiled = sys.getrefcount(x)
    q = interp(m, "pair")(x)
    assert compiled == base + 2 and sys.getrefcount(x) == base + 4
    del p, q
    n, h = m.Node(None, None), m.Node(x, None)
    b = sys.getrefcount(n), sys.getrefcount(h), sys.getrefcount(x)
    for _ in range(50):
        m.check(n), m.first(h), m.is_none(n), m.check(m.Node(n, n))
    assert (sys.getrefcount(n), sys.getrefcount(h), sys.getrefcount(x)) == b


def test_reference_counts_are_unchanged_over_many_calls(m):
    shared = object()
    holder = m.Node(shared, None)
    keep = m.Node(None, None)

    def churn(k):
        for _ in range(k):
            m.check(m.make(4))
            m.pair(shared)
            m.first(holder)
            m.is_none(keep)
            m.check(m.Node(keep, None))

    churn(20)
    base = sys.getrefcount(shared), sys.getrefcount(holder), sys.getrefcount(keep)
    churn(3000)
    assert (sys.getrefcount(shared), sys.getrefcount(holder), sys.getrefcount(keep)) == base


def test_concurrent_threads_keep_counts_exact(m):
    """3.15t: other threads move a shared object's count through ob_ref_shared, this one through
    ob_ref_local; a lost or extra increment shows in sys.getrefcount once the threads are done."""
    shared = object()
    keep = m.Node(None, None)
    base = sys.getrefcount(shared), sys.getrefcount(keep)
    errors = []

    def work():
        try:
            for _ in range(3000):
                p = m.pair(shared)
                assert m.first(p) is shared
                assert m.check(m.Node(keep, keep)) == 3
                assert m.check(m.make(3)) == 7
                assert m.is_none(keep) is False
        except Exception as e:                  # noqa: BLE001 (reported below)
            errors.append(repr(e))

    threads = [threading.Thread(target=work) for _ in range(4)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert errors == []
    assert (sys.getrefcount(shared), sys.getrefcount(keep)) == base


# --- mutations: a wrong move or borrow must fail ----------------------------------------------------

MUTANT_SCRIPT = textwrap.dedent('''\
    import sys
    sys.path.insert(0, {pkg!r})
    from typedpython import cbuild
    m = cbuild.load({name!r}, {so!r})
    log = []
    class T:
        def __del__(self):
            log.append("T")
    bad = 0
    x = T()
    base = sys.getrefcount(x)
    p = m.pair(x)
    if sys.getrefcount(x) != base + 2:
        bad |= 1
    for _ in range(200):
        m.pair(x)
        m.check(m.make(4))
    if sys.getrefcount(x) != base + 2:
        bad |= 2
    del x
    if log:
        bad |= 4                                # freed while the pair still holds it
    del p
    if log != ["T"]:
        bad |= 8
    n = m.Node(None, None)
    b = sys.getrefcount(n)
    for _ in range(100):
        m.check(n)
        m.first(n)
        m.is_none(n)
    if sys.getrefcount(n) != b:
        bad |= 16
    print("RESULT", bad, log)
    sys.exit(bad)
    ''')


def run_child(name, so):
    script = MUTANT_SCRIPT.format(pkg=str(PACKAGE.parent), name=name, so=str(so))
    return subprocess.run([sys.executable, "-c", script], capture_output=True, text=True, timeout=120)


def test_mutation_unmutated_build_passes_the_script():
    _, so = build("tp_alloc_ok")
    run = run_child("tp_alloc_ok", so)
    assert run.returncode == 0, run.stdout + run.stderr


def _build_mutant(name, monkeypatch, attr, value):
    from typedpython import cgen, cbuild
    need_runtime()
    monkeypatch.setattr(cgen, attr, value)
    src = generate(name)
    monkeypatch.undo()
    return cbuild.build(src, name, OUT_ROOT / name, runtime_dir=runtime_dir())


def test_mutation_releasing_moved_temporaries_is_caught(monkeypatch):
    """The temporaries a New moved into its object are released anyway: one reference too few per
    slot, the argument dies while the pair still points at it."""
    so = _build_mutant("tp_alloc_mut_move", monkeypatch, "_moved_temps", lambda items: "")
    run = run_child("tp_alloc_mut_move", so)
    assert run.returncode != 0, "the over-release mutant passed:\n" + run.stdout + run.stderr


def test_mutation_copying_instead_of_moving_is_caught():
    """tp_new_fixed_move takes a new reference as tp_new_fixed did, while the caller still drops its
    temporaries without releasing them: one leaked reference per slot."""
    from typedpython import cbuild
    flags = list(cbuild.DEFAULT_FLAGS) + ["-DTP_MUTANT_COPY_MOVED"]   # object-like: cl.exe too
    need_runtime()
    src = generate("tp_alloc_mut_copy")
    so = cbuild.build(src, "tp_alloc_mut_copy", OUT_ROOT / "tp_alloc_mut_copy",
                      runtime_dir=runtime_dir(), flags=flags)
    run = run_child("tp_alloc_mut_copy", so)
    assert run.returncode != 0, "the leaking mutant passed:\n" + run.stdout + run.stderr


def test_mutation_releasing_a_borrowed_local_is_caught(monkeypatch):
    """A borrowed OBJ local is released after the helper as if it were a temporary: the local's own
    reference is dropped (and the local emptied) while the function still uses it."""
    so = _build_mutant("tp_alloc_mut_borrow", monkeypatch, "_operand_release",
                       lambda name, owned: name if (owned or name.startswith("l_")) else None)
    run = run_child("tp_alloc_mut_borrow", so)
    assert run.returncode != 0, "the borrowed-release mutant passed:\n" + run.stdout + run.stderr
