"""TypedPython virtual objects, first step (SPEC N-12a, #143): function-local scalar replacement.

An OBJ local that is only ever assigned `New(C, ...)` and only ever read as the object of a
`FieldGet`, in a pure function with no unknown code, is virtual: no object is allocated. The
verifier recomputes the set (`Function.virtual`); the back end trusts only the verified set.

Red record: this file was written before the implementation. Before it, `Function` had no `virtual`
field and the generated C allocated every object through `tp_new_fixed`.
"""
from __future__ import annotations

import dataclasses
import shutil
import sys
import textwrap
import threading

import pytest

from test_cgen import OUT_ROOT, I64, F64, BOOL, OBJ, c, L, fn, runtime_dir, need_runtime
from typedpython import cgen, ir, verify
from typedpython.ir import (
    Assign, BinOp, BinOpKind, Box, Call, CallObject, ClassDecl, Compare, CompareKind, Const,
    ExprStmt, FieldGet, Global, If, Is, Module, New, Param, Return, Unbox, While,
)

VIRTUAL_ESCAPE = "verify/virtual-escape"

SOURCE = textwrap.dedent('''\
    """typedpython virtual object test module, the interpreted reference."""

    class Pt:
        __slots__ = ("x", "y")

        def __init__(self, x, y):
            self.x = x
            self.y = y

    class Qt:
        __slots__ = ("x", "y")

        def __init__(self, x, y):
            self.x = x
            self.y = y

    def helper(a):
        return a + 1.0

    def norm2(a, b):
        p = Pt(a, b)
        return p.x * p.x + p.y * p.y

    def ident(o):
        p = Pt(o, None)
        return p.x

    def loop_sum(n):
        t = 0
        i = 0
        while i < n:
            p = Pt(i, i + 1)
            t = t + p.x + p.y
            i = i + 1
        return t

    def escapes(a, b):
        p = Pt(a, b)
        return p

    def with_call(a, b):
        p = Pt(helper(a), b)
        return p.x + p.y
    ''')

PT = ClassDecl("Pt", ("x", "y"), True)
QT = ClassDecl("Qt", ("x", "y"), True)


def fget(local, field, cls="Pt"):
    return FieldGet(OBJ, L(local, OBJ), cls, field)


def f_norm2():
    a, b = L("a", I64), L("b", I64)
    px = Unbox(I64, fget("p", "x"))
    py = Unbox(I64, fget("p", "y"))
    return fn("norm2", [Param("a", I64), Param("b", I64)], I64, {"p": OBJ},
              [Assign("p", New(OBJ, "Pt", (Box(OBJ, a), Box(OBJ, b)))),
               Return(BinOp(I64, BinOpKind.ADD, BinOp(I64, BinOpKind.MUL, px, px),
                            BinOp(I64, BinOpKind.MUL, py, py)))],
              pure=True, may_deopt=True)


def f_ident():
    return fn("ident", [Param("o", OBJ)], OBJ, {"p": OBJ},
              [Assign("p", New(OBJ, "Pt", (L("o", OBJ), Const(OBJ, None)))),
               Return(fget("p", "x"))],
              pure=True, may_deopt=False)


def f_loop_sum():
    n, i, t = L("n", I64), L("i", I64), L("t", I64)
    px = Unbox(I64, fget("p", "x"))
    py = Unbox(I64, fget("p", "y"))
    return fn("loop_sum", [Param("n", I64)], I64, {"i": I64, "t": I64, "p": OBJ},
              [Assign("t", c(0)), Assign("i", c(0)),
               While(Compare(BOOL, CompareKind.LT, i, n),
                     (Assign("p", New(OBJ, "Pt", (Box(OBJ, i),
                                                  Box(OBJ, BinOp(I64, BinOpKind.ADD, i, c(1)))))),
                      Assign("t", BinOp(I64, BinOpKind.ADD, BinOp(I64, BinOpKind.ADD, t, px), py)),
                      Assign("i", BinOp(I64, BinOpKind.ADD, i, c(1))))),
               Return(t)],
              pure=True, may_deopt=True)


def f_escapes():
    a, b = L("a", I64), L("b", I64)
    return fn("escapes", [Param("a", I64), Param("b", I64)], OBJ, {"p": OBJ},
              [Assign("p", New(OBJ, "Pt", (Box(OBJ, a), Box(OBJ, b)))), Return(L("p", OBJ))],
              pure=True, may_deopt=False)


def f_helper():
    return fn("helper", [Param("a", I64)], I64, {},
              [Return(BinOp(I64, BinOpKind.ADD, L("a", I64), c(1)))], pure=True, may_deopt=True)


def f_with_call():
    a, b = L("a", I64), L("b", I64)
    px = Unbox(I64, fget("p", "x"))
    py = Unbox(I64, fget("p", "y"))
    return fn("with_call", [Param("a", I64), Param("b", I64)], I64, {"p": OBJ},
              [Assign("p", New(OBJ, "Pt", (Box(OBJ, Call(I64, "helper", (a,))), Box(OBJ, b)))),
               Return(BinOp(I64, BinOpKind.ADD, px, py))],
              pure=True, may_deopt=True)


def module(*funcs):
    return Module(name="tp_virtual", functions=tuple(funcs), classes=(PT, QT))


def proved(*funcs):
    m, diags = verify.verify(module(*funcs))
    return {g.name: g for g in m.functions}, diags


# --- the verifier recomputes the set -------------------------------------------------------------------

def test_a_local_only_read_by_field_get_is_virtual():
    fs, diags = proved(f_norm2())
    assert not diags
    assert fs["norm2"].virtual == ("p",)


def test_a_local_assigned_in_a_loop_is_virtual():
    fs, diags = proved(f_loop_sum())
    assert not diags
    assert fs["loop_sum"].virtual == ("p",)


def test_an_object_parameter_may_be_a_field_value():
    fs, diags = proved(f_ident())
    assert not diags and fs["ident"].virtual == ("p",)


def test_returning_the_object_keeps_it_real():
    fs, diags = proved(f_escapes())
    assert not diags and fs["escapes"].virtual == ()


def test_a_call_in_the_function_keeps_every_object_real():
    fs, diags = proved(f_helper(), f_with_call())
    assert not diags and fs["with_call"].virtual == ()


def test_is_on_the_object_keeps_it_real():
    base = f_norm2()
    g = dataclasses.replace(base, body=(
        base.body[0], If(Is(BOOL, L("p", OBJ), Const(OBJ, None)), (Return(c(0)),)), base.body[1]))
    fs, diags = proved(g)
    assert not diags and fs["norm2"].virtual == ()


def test_a_field_of_another_object_as_argument_keeps_it_real():
    q = FieldGet(OBJ, L("q", OBJ), "Qt", "x")
    g = fn("fromq", [Param("q", OBJ, cls="Qt")], OBJ, {"p": OBJ},
           [Assign("p", New(OBJ, "Pt", (q, Const(OBJ, None)))), Return(fget("p", "x"))],
           pure=True, may_deopt=False)
    fs, diags = proved(g)
    assert not diags and fs["fromq"].virtual == ()


def test_two_classes_assigned_to_one_local_keep_it_real():
    a = L("a", I64)
    g = fn("two", [Param("a", I64)], OBJ, {"p": OBJ},
           [If(Compare(BOOL, CompareKind.GT, a, c(0)),
               (Assign("p", New(OBJ, "Pt", (Box(OBJ, a), Const(OBJ, None)))),),
               (Assign("p", New(OBJ, "Qt", (Box(OBJ, a), Const(OBJ, None)))),)),
            Return(Const(OBJ, None))],
           pure=True, may_deopt=False)
    fs, diags = proved(g)
    assert not diags and fs["two"].virtual == ()


def test_an_impure_function_has_no_virtual_object():
    a, b = L("a", I64), L("b", I64)
    g = fn("impure", [Param("a", I64), Param("b", I64)], I64, {"p": OBJ},
           [Assign("p", New(OBJ, "Pt", (Box(OBJ, a), Box(OBJ, b)))),
            ExprStmt(CallObject(OBJ, Global(OBJ, "helper"), (Box(OBJ, a),), ())),
            Return(a)],
           pure=False, may_deopt=False)
    fs, diags = proved(g)
    assert not diags and fs["impure"].virtual == ()


def test_a_forged_virtual_entry_is_rejected():
    forged = dataclasses.replace(f_escapes(), virtual=("p",))
    m, diags = verify.verify(module(forged))
    assert any(d.rule == VIRTUAL_ESCAPE for d in diags)
    assert m.functions == ()


def test_mutation_without_the_unknown_code_rule_the_call_case_flips(monkeypatch):
    base, _ = proved(f_helper(), f_with_call())
    assert base["with_call"].virtual == ()
    monkeypatch.setattr(verify, "_VIRTUAL_OK", verify._VIRTUAL_OK | {ir.Call})
    mutated, _ = proved(f_helper(), f_with_call())
    assert mutated["with_call"].virtual == ("p",)         # the rule is what keeps it real


# --- the back end: same results as the interpreter -----------------------------------------------------

def build(name, funcs):
    from typedpython import cbuild
    need_runtime()
    d = OUT_ROOT / name
    if d.exists():
        shutil.rmtree(d)
    d.mkdir(parents=True)
    src = d / f"{name}.py"
    src.write_text(SOURCE)
    mod, diags = verify.verify(Module(name=name, functions=tuple(funcs), classes=(PT, QT)))
    assert not diags, diags
    text = cgen.generate(mod, src)
    so = cbuild.build(text, name, d, runtime_dir=runtime_dir())
    return cbuild.load(name, so), text


@pytest.fixture(scope="module")
def virt():
    return build("tp_virtual_v", (f_norm2(), f_ident(), f_loop_sum()))


@pytest.fixture(scope="module")
def real():
    return build("tp_virtual_r", (f_escapes(), f_helper(), f_with_call()))


def interp(m, name):
    return m.__typedpython_interpreted__[name]


def test_virtual_functions_allocate_nothing_through_tp_new_fixed(virt, real):
    assert "tp_new_fixed" not in virt[1]
    assert "tp_new_fixed" in real[1]                  # the same call is there when the object is real


def test_virtual_function_matches_the_interpreter(virt):
    m, _ = virt
    for a, b in [(1, 2), (0, -3), (3000000000, 1), (2 ** 40, 1), (1.5, 2.5)]:   # last two: deopt
        assert m.norm2(a, b) == interp(m, "norm2")(a, b)
    for n in (0, 1, 7, 1000):
        assert m.loop_sum(n) == interp(m, "loop_sum")(n)


def test_a_field_read_returns_the_stored_object_itself(virt):
    m, _ = virt
    o = object()
    assert m.ident(o) is o
    before = sys.getrefcount(o)
    for _ in range(1000):
        m.ident(o)
    assert sys.getrefcount(o) == before


def test_an_escaping_function_still_returns_a_real_pt(real):
    m, _ = real
    r = m.escapes(1, 2)
    assert type(r) is m.Pt and (r.x, r.y) == (1, 2)
    assert m.with_call(1, 2) == interp(m, "with_call")(1, 2)


def test_class_change_deopts_to_the_same_result(virt):
    m, _ = virt
    saved = m.Pt

    class Pt2:
        __slots__ = ("x", "y")

        def __init__(self, x, y):
            self.x, self.y = x + 1, y

    try:
        m.Pt = Pt2
        assert m.norm2(1, 2) == interp(m, "norm2")(1, 2) == 8
    finally:
        m.Pt = saved
    assert m.norm2(1, 2) == 5


def test_virtual_functions_are_safe_on_many_threads(virt):
    m, _ = virt
    errors = []

    def work():
        try:
            for i in range(2000):
                assert m.loop_sum(i % 20) == interp(m, "loop_sum")(i % 20)
                assert m.norm2(i, 1) == interp(m, "norm2")(i, 1)
        except BaseException as e:               # noqa: BLE001
            errors.append(e)

    ts = [threading.Thread(target=work) for _ in range(4)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    assert not errors
