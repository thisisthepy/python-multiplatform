"""The IR safety verifier for fixed-layout classes (SPEC N-9 safety + N-11; IR contract 158a9b4b).

Every test builds IR by hand: the verifier must prove, never trust the front end, that

  * every class named by FieldGet/FieldSet/New/IsExact/CheckExact/Param.cls is a ClassDecl of the
    module, every field named belongs to it, and New is used only for trivial_init classes with one
    OBJ argument per field;
  * a FieldGet/FieldSet's object is proved exactly the class (`verify/field-unproved` otherwise);
  * CheckExact (can deopt) is only in pure functions, FieldSet is an effect, FieldGet/Is/IsExact/New
    are not; `pure` and `may_deopt` are recomputed and compared;
  * the node types are right and the OBJ-creating nodes follow the ownership rule.

The last section mutates correct class-using functions at random (seeded) and checks that every
property-breaking mutation is caught.
"""
from __future__ import annotations

import dataclasses
import random

import pytest

from typedpython import ir
from typedpython.ir import (
    And, ArrayParam, Assign, Break, CheckExact, ClassDecl, Const, ExprStmt, FieldGet, FieldSet,
    ForRange, Function, GetAttr, Global, If, Is, IsExact, Len, Local, Module, New, Or, Param,
    Return, StoreIndex, Type, UnaryOp, UnaryOpKind, While, CallObject, Box, Call, BinOp, BinOpKind,
)

I64, F64, BOOL, NONE, OBJ = Type.I64, Type.F64, Type.BOOL, Type.NONE, Type.OBJ
F64A = Type.F64_ARRAY

TYPE = "verify/type"
OWNERSHIP = "verify/ownership"
DEOPT = "verify/deopt-impure"
FLAG_DEOPT = "verify/flag-may-deopt"
FLAG_PURE = "verify/flag-pure"
STRUCTURE = "verify/structure"
CLASS = "verify/class"
FIELD = "verify/field-unproved"
ENTRY_OPEN = "verify/entry-global-open"
UNVERIFIED = "verify/call-unverified"

POINT = ClassDecl("Point", ("x", "y"), True)
OTHER = ClassDecl("Other", ("x", "z"), True)
SLOWINIT = ClassDecl("SlowInit", ("a",), False)
CLASSES = (POINT, OTHER, SLOWINIT)


def _verify():
    # Imported lazily so that, before the class rules exist, each test fails on its own assertion.
    from typedpython import verify
    return verify


# --- builders ------------------------------------------------------------------------------------

def lo(n): return Local(OBJ, n)
def li(n): return Local(I64, n)
def g_(n): return Global(OBJ, n)
def fget(obj, field, cls="Point"): return FieldGet(OBJ, obj, cls, field)
def fset(obj, field, value, cls="Point"): return FieldSet(obj, cls, field, value)
def exact(obj, cls="Point"): return IsExact(BOOL, obj, cls)
def check(obj, cls="Point"): return CheckExact(OBJ, obj, cls)
def new(*args, cls="Point"): return New(OBJ, cls, tuple(args))
def none(): return Const(OBJ, None)
def is_none(x, negate=False): return Is(BOOL, x, none(), negate)
def not_(x): return UnaryOp(BOOL, UnaryOpKind.NOT, x)
def pt(name="p", optional=False, cls="Point"): return Param(name, OBJ, cls, optional)
def obj(name="q"): return Param(name, OBJ)
def band(a, b): return And(BOOL, a, b)
def bor(a, b): return Or(BOOL, a, b)
IMPURE = ExprStmt(CallObject(OBJ, Global(OBJ, "print"), (), ()))


def fn(name="f", body=(), params=(), locals_=None, returns=OBJ, pure=True, may_deopt=False,
       line=10, entry_globals=()):
    return Function(name=name, params=tuple(params), returns=returns, locals=dict(locals_ or {}),
                    body=tuple(body), pure=pure, may_deopt=may_deopt, source_line=line,
                    entry_globals=tuple(entry_globals))


def run(*functions, classes=CLASSES):
    return _verify().verify(Module("m", tuple(functions), {}, tuple(classes)))


def kept(out):
    return [g.name for g in out.functions]


def rules_for(diags, name):
    return {d.rule for d in diags if d.function == name}


def accepted(*functions, classes=CLASSES):
    out, diags = run(*functions, classes=classes)
    assert kept(out) == [g.name for g in functions], [dataclasses.astuple(d) for d in diags]
    assert diags == []
    return out


def rejected(rule, *functions, name="f", classes=CLASSES):
    out, diags = run(*functions, classes=classes)
    assert name not in kept(out)
    assert name in out.skipped
    assert rule in rules_for(diags, name), [dataclasses.astuple(d) for d in diags]
    return out, diags


def getter(body, params=(pt(),), locals_=None, **kw):
    """A pure function returning an OBJ read from fields (the common shape of these tests)."""
    return fn(body=body, params=params, locals_=locals_, **kw)


# --- the module keeps its classes ----------------------------------------------------------------

def test_the_verified_module_keeps_its_valid_classes():
    out = accepted(getter([Return(fget(lo("p"), "x"))]))
    assert out.classes == CLASSES


# --- 1. class and field names --------------------------------------------------------------------

def test_a_function_without_classes_is_unaffected():
    accepted(fn(params=[obj()], body=[Return(lo("q"))]), classes=())


@pytest.mark.parametrize("stmt", [
    lambda: Return(fget(lo("p"), "x", cls="Nowhere")),
    lambda: Return(new(g_("a"), g_("b"), cls="Nowhere")),
    lambda: If(exact(lo("p"), "Nowhere"), (Return(g_("a")),)),
    lambda: Return(check(lo("p"), "Nowhere")),
    lambda: fset(lo("p"), "x", g_("v"), cls="Nowhere"),
])
def test_every_node_must_name_a_class_of_the_module(stmt):
    rejected(CLASS, fn(params=[pt()], pure=False, body=[stmt(), Return(g_("r"))]))


def test_a_param_may_only_name_a_class_of_the_module():
    rejected(CLASS, fn(params=[pt(cls="Nowhere")], body=[Return(lo("p"))]))
    rejected(CLASS, fn(params=[pt(cls="Nowhere", optional=True)], body=[Return(lo("p"))]))


def test_a_field_must_belong_to_the_named_class():
    rejected(CLASS, getter([Return(fget(lo("p"), "z"))]))                 # z is Other's field
    rejected(CLASS, fn(params=[pt()], pure=False, body=[fset(lo("p"), "nope", g_("v")), Return(lo("p"))]))
    accepted(getter([Return(fget(lo("p"), "y"))]))


def test_new_needs_a_trivial_init_class_and_one_object_per_field():
    accepted(getter([Return(new(g_("a"), g_("b")))], params=()))
    rejected(CLASS, getter([Return(new(g_("a")))], params=()))
    rejected(CLASS, getter([Return(new(g_("a"), g_("b"), g_("c")))], params=()))
    rejected(CLASS, getter([Return(new(cls="Point"))], params=()))
    rejected(CLASS, getter([Return(new(g_("a"), cls="SlowInit"))], params=()))      # not trivial_init
    accepted(getter([Return(new(g_("a"), g_("b"), cls="Other"))], params=()))


def test_new_arguments_must_be_objects():
    rejected(OWNERSHIP, getter([Return(new(Const(I64, 1), g_("b")))], params=()))
    accepted(getter([Return(new(Box(OBJ, Const(I64, 1)), g_("b")))], params=()))


def test_param_cls_only_on_obj_params_and_optional_only_with_cls():
    rejected(TYPE, fn(params=[Param("n", I64, "Point")], body=[Return(g_("r"))]))
    rejected(TYPE, fn(params=[Param("n", OBJ, None, True)], body=[Return(lo("n"))]))
    rejected(TYPE, fn(params=[Param("n", OBJ, "Point", 1)], body=[Return(lo("n"))]))
    rejected(TYPE, fn(params=[], entry_globals=[Param("N", I64, "Point")], returns=NONE,
                      body=[Return()]))


@pytest.mark.parametrize("bad, why", [
    (ClassDecl("Dup", ("a",), True), "dup"),
    (ClassDecl("Twice", ("a", "a"), True), "field twice"),
    (ClassDecl("not an id", ("a",), True), "name"),
    (ClassDecl("Fld", ("a b",), True), "field name"),
    (ClassDecl("Ti", ("a",), 1), "trivial_init"),
])
def test_a_malformed_class_decl_is_reported_and_unusable(bad, why):
    classes = (bad, bad) if why == "dup" else (bad,)
    f = getter([Return(fget(lo("p"), "a", cls=bad.name))], params=(pt(cls=bad.name),))
    out, diags = run(f, classes=classes)
    assert any(d.rule == CLASS and d.function == bad.name for d in diags), diags
    assert bad.name not in [c.name for c in out.classes]
    assert "f" not in kept(out)                    # the function that used it is not compiled
    assert CLASS in rules_for(diags, "f")


def test_a_class_with_the_name_of_a_function_is_rejected():
    out, diags = run(getter([Return(lo("p"))], params=(pt(),), name="Point"))
    assert any(d.rule == CLASS and d.function == "Point" for d in diags)
    assert out.classes == (OTHER, SLOWINIT)


# --- 2. the exact-type proof ---------------------------------------------------------------------

def test_exact_param_is_proved():
    accepted(getter([Return(fget(lo("p"), "x"))]))


def test_a_param_exact_proof_is_for_its_own_class():
    rejected(FIELD, getter([Return(fget(lo("p"), "x", cls="Other"))]))


def test_a_plain_object_param_is_not_proved():
    rejected(FIELD, getter([Return(fget(lo("q"), "x"))], params=(obj(),)))


def test_an_optional_param_alone_is_not_proved():
    rejected(FIELD, getter([Return(fget(lo("p"), "x"))], params=(pt(optional=True),)))


def test_check_exact_is_the_proof_for_its_own_class_only():
    accepted(getter([Return(fget(check(lo("q")), "x"))], params=(obj(),), may_deopt=True))
    rejected(FIELD, getter([Return(fget(check(lo("q"), "Other"), "x"))], params=(obj(),),
                           may_deopt=True))


def test_new_is_the_proof_for_its_own_class_only():
    accepted(getter([Return(fget(new(g_("a"), g_("b")), "x"))], params=()))
    rejected(FIELD, getter([Return(fget(new(g_("a"), g_("b"), cls="Other"), "x"))], params=()))


def test_a_local_assigned_from_a_new_or_check_exact_is_proved_until_reassigned():
    accepted(getter([Assign("t", new(g_("a"), g_("b"))), Return(fget(lo("t"), "x"))],
                    params=(), locals_={"t": OBJ}))
    accepted(getter([Assign("t", check(lo("q"))), Return(fget(lo("t"), "x"))], params=(obj(),),
                    locals_={"t": OBJ}, may_deopt=True))
    rejected(FIELD, getter([Assign("t", new(g_("a"), g_("b"))), Assign("t", g_("other")),
                            Return(fget(lo("t"), "x"))], params=(), locals_={"t": OBJ}))
    # a copy of a proved local is proved; a copy of an unproved one is not
    accepted(getter([Assign("t", lo("p")), Return(fget(lo("t"), "x"))], locals_={"t": OBJ}))
    rejected(FIELD, getter([Assign("t", lo("q")), Return(fget(lo("t"), "x"))], params=(obj(),),
                           locals_={"t": OBJ}))


def test_a_local_assigned_only_new_of_one_class_is_proved_everywhere():
    two = [If(exact(lo("q")), (Assign("t", new(g_("a"), g_("b"))),),
              (Assign("t", new(g_("c"), g_("d"))),)), Return(fget(lo("t"), "x"))]
    accepted(getter(two, params=(obj(),), locals_={"t": OBJ}))
    # in a loop, flow-insensitively: the read comes before the second assignment in the text
    loop = [Assign("t", new(g_("a"), g_("b"))), Assign("u", g_("o")),
            While(exact(g_("zz")), (Assign("u", fget(lo("t"), "x")), Assign("t", new(g_("c"), g_("d"))),
                                    Break())), Return(lo("u"))]
    accepted(getter(loop, params=(), locals_={"t": OBJ, "u": OBJ}, pure=False))


def test_one_other_assignment_to_the_local_ends_the_new_only_form():
    other = [Assign("t", new(g_("a"), g_("b"))), If(exact(g_("zz")), (Assign("t", g_("o")),)),
             Return(fget(lo("t"), "x"))]
    rejected(FIELD, getter(other, params=(), locals_={"t": OBJ}, pure=False))
    mixed = [Assign("t", new(g_("a"), g_("b"))), If(exact(g_("zz")),
                                                    (Assign("t", new(g_("a"), g_("b"), cls="Other")),)),
             Return(fget(lo("t"), "x"))]
    rejected(FIELD, getter(mixed, params=(), locals_={"t": OBJ}, pure=False))
    # a parameter is never new-only, even if every body assignment is a New
    rejected(FIELD, getter([If(exact(g_("zz")), (Assign("q", new(g_("a"), g_("b"))),)),
                            Return(fget(lo("q"), "x"))], params=(obj(),), pure=False))
    # the flow-sensitive forms still apply to a local with other assignments: the last assignment
    # before the read is a New, so form (d) proves it (the front end need not rely on this)
    accepted(getter([Assign("t", g_("o")), Assign("t", new(g_("a"), g_("b"))),
                     Return(fget(lo("t"), "x"))], params=(), locals_={"t": OBJ}))


def test_a_reassigned_param_loses_its_proof():
    rejected(FIELD, getter([Assign("p", g_("other")), Return(fget(lo("p"), "x"))]))
    accepted(getter([Assign("p", new(g_("a"), g_("b"))), Return(fget(lo("p"), "x"))]))


def test_a_dominating_is_exact_is_the_proof():
    body = [If(exact(lo("q")), (Return(fget(lo("q"), "x")),)), Return(g_("r"))]
    accepted(getter(body, params=(obj(),)))


def test_is_exact_proves_only_its_branch_local_and_class():
    p = (obj(),)
    rejected(FIELD, getter([If(exact(lo("q")), (), (Return(fget(lo("q"), "x")),)),
                            Return(g_("r"))], params=p))                    # else branch
    rejected(FIELD, getter([If(exact(lo("q")), ()), Return(fget(lo("q"), "x"))], params=p))
    rejected(FIELD, getter([If(exact(lo("q"), "Other"), (Return(fget(lo("q"), "x")),)),
                            Return(g_("r"))], params=p))                    # wrong class
    rejected(FIELD, getter([If(exact(lo("q")), (Return(fget(lo("w"), "x")),)), Return(g_("r"))],
                           params=(obj(), obj("w"))))                       # another local
    rejected(FIELD, getter([If(exact(g_("k")), (Return(fget(g_("k"), "x")),)), Return(g_("r"))],
                           params=p))                                       # not a local at all


def test_an_assignment_between_the_guard_and_the_access_kills_the_proof():
    body = [If(exact(lo("q")), (Assign("q", g_("o")), Return(fget(lo("q"), "x")))), Return(g_("r"))]
    rejected(FIELD, getter(body, params=(obj(),)))
    body = [If(exact(lo("q")), (Assign("q", new(g_("a"), g_("b"))), Return(fget(lo("q"), "x")))),
            Return(g_("r"))]
    accepted(getter(body, params=(obj(),)))              # ... unless it is itself proved


def test_the_condition_forms_that_prove():
    p = (obj(),)
    # And: the left test holds in the right operand and in the then branch
    accepted(getter([If(band(exact(lo("q")), Is(BOOL, fget(lo("q"), "x"), g_("k"))),
                        (Return(fget(lo("q"), "y")),)), Return(g_("r"))], params=p))
    # `not exact` false in the else
    accepted(getter([If(not_(exact(lo("q"))), (), (Return(fget(lo("q"), "x")),)),
                     Return(g_("r"))], params=p))
    # Or: the right operand runs when the left was false
    accepted(getter([If(bor(not_(exact(lo("q"))), Is(BOOL, fget(lo("q"), "x"), g_("k"))),
                        (Return(g_("r")),)), Return(g_("r"))], params=p))
    # ... but not the other way round
    rejected(FIELD, getter([If(bor(exact(lo("q")), Is(BOOL, fget(lo("q"), "x"), g_("k"))),
                               (Return(g_("r")),)), Return(g_("r"))], params=p))
    rejected(FIELD, getter([If(band(Is(BOOL, fget(lo("q"), "x"), g_("k")), exact(lo("q"))),
                               (Return(g_("r")),)), Return(g_("r"))], params=p))


def test_the_proof_survives_a_branch_that_leaves():
    p = (obj(),)
    accepted(getter([If(not_(exact(lo("q"))), (Return(g_("r")),)), Return(fget(lo("q"), "x"))],
                    params=p))
    # a fall-through branch does not
    rejected(FIELD, getter([If(not_(exact(lo("q"))), (Assign("t", g_("r")),)),
                            Return(fget(lo("q"), "x"))], params=p, locals_={"t": OBJ}))


def test_a_fact_both_branches_establish_survives_the_join_and_one_branch_does_not():
    def join(else_value):
        return getter([If(exact(lo("q")), (Assign("t", lo("q")),), (Assign("t", else_value),)),
                       Return(fget(lo("t"), "x"))], params=(obj(),), locals_={"t": OBJ})
    accepted(join(new(g_("a"), g_("b"))))
    rejected(FIELD, join(g_("o")))


def test_optional_param_is_not_none_proves_the_class():
    p = (pt(optional=True),)
    accepted(getter([If(is_none(lo("p"), negate=True), (Return(fget(lo("p"), "x")),)),
                     Return(g_("r"))], params=p))
    accepted(getter([If(is_none(lo("p")), (Return(g_("r")),)), Return(fget(lo("p"), "x"))],
                    params=p))
    accepted(getter([If(is_none(lo("p")), (Return(g_("r")),), (Return(fget(lo("p"), "x")),)),
                     Return(g_("r"))], params=p))
    # the `None` may stand on either side
    accepted(getter([If(Is(BOOL, none(), lo("p"), True), (Return(fget(lo("p"), "x")),)),
                     Return(g_("r"))], params=p))


def test_the_none_test_proves_nothing_where_the_param_is_not_a_never_assigned_optional_class():
    rejected(FIELD, getter([If(is_none(lo("q"), negate=True), (Return(fget(lo("q"), "x")),)),
                            Return(g_("r"))], params=(obj(),)))
    rejected(FIELD, getter([If(is_none(lo("p")), (Return(fget(lo("p"), "x")),)), Return(g_("r"))],
                           params=(pt(optional=True),)))          # wrong sense
    rejected(FIELD, getter([If(is_none(lo("p"), negate=True), (Return(fget(lo("p"), "x")),)),
                            Assign("p", g_("o")), Return(g_("r"))], params=(pt(optional=True),)))
    # reassigned anywhere in the function -> no fact, even where the assignment is later
    rejected(FIELD, getter([If(is_none(lo("p"), negate=True), (Return(fget(lo("p"), "x")),)),
                            Assign("p", g_("o")), Return(g_("r"))], params=(pt(optional=True),)))
    # the identity of another object is not a fact about this one
    rejected(FIELD, getter([If(Is(BOOL, lo("p"), g_("k"), True), (Return(fget(lo("p"), "x")),)),
                            Return(g_("r"))], params=(pt(optional=True),)))


def test_loops_kill_what_the_body_assigns():
    p = (obj(),)
    guard = lambda *body: getter([If(exact(lo("q")), (While(Const(BOOL, True), tuple(body)),)),
                                  Return(g_("r"))], params=p, pure=False)
    accepted(guard(fset(lo("q"), "x", g_("v")), Break()))
    # q is assigned later in the body: at the head of the next iteration the guard no longer holds
    rejected(FIELD, guard(fset(lo("q"), "x", g_("v")), Assign("q", g_("o"))))
    rejected(FIELD, getter([If(exact(lo("q")), (ForRange("k", Const(I64, 0), Const(I64, 3),
                                                         Const(I64, 1), (
        fset(lo("q"), "x", g_("v")), Assign("q", g_("o")))),)), Return(g_("r"))],
        params=p, locals_={"k": I64}, pure=False))


def test_a_proof_before_a_loop_that_assigns_does_not_outlive_it():
    p = (obj(),)
    loop = ForRange("k", Const(I64, 0), Const(I64, 3), Const(I64, 1), (Assign("q", g_("o")),))
    rejected(FIELD, getter([If(exact(lo("q")), (loop, Return(fget(lo("q"), "x")))),
                            Return(g_("r"))], params=p, locals_={"k": I64}, pure=False))
    quiet = ForRange("k", Const(I64, 0), Const(I64, 3), Const(I64, 1), (IMPURE,))
    accepted(getter([If(exact(lo("q")), (quiet, Return(fget(lo("q"), "x")))), Return(g_("r"))],
                    params=p, locals_={"k": I64}, pure=False))


def test_a_proof_does_not_cross_a_call_or_another_nodes_result():
    # a FieldGet result is just an object
    rejected(FIELD, getter([Return(fget(fget(lo("p"), "x"), "x"))]))
    rejected(FIELD, getter([Return(fget(g_("o"), "x"))]))
    rejected(FIELD, getter([Return(fget(CallObject(OBJ, g_("mk"), ()), "x"))], pure=False))


def test_field_set_needs_the_same_proof():
    accepted(fn(params=[pt()], pure=False, body=[fset(lo("p"), "x", g_("v")), Return(lo("p"))]))
    rejected(FIELD, fn(params=[obj("p")], pure=False, body=[fset(lo("p"), "x", g_("v")),
                                                              Return(lo("p"))]))
    rejected(FIELD, fn(params=[pt(optional=True)], pure=False,
                       body=[fset(lo("p"), "x", g_("v")), Return(lo("p"))]))
    accepted(fn(params=[obj("p")], pure=False,
                body=[If(exact(lo("p")), (fset(lo("p"), "x", g_("v")),)), Return(lo("p"))]))


def test_field_set_value_must_be_an_object():
    rejected(OWNERSHIP, fn(params=[pt()], pure=False,
                           body=[fset(lo("p"), "x", Const(I64, 1)), Return(lo("p"))]))
    accepted(fn(params=[pt()], pure=False, body=[fset(lo("p"), "x", Box(OBJ, Const(I64, 1))),
                                                 Return(lo("p"))]))


# --- 3. purity and deopt -------------------------------------------------------------------------

def test_check_exact_in_a_pure_function_is_a_deopt_point():
    accepted(getter([Return(check(lo("q")))], params=(obj(),), may_deopt=True))
    rejected(FLAG_DEOPT, getter([Return(check(lo("q")))], params=(obj(),), may_deopt=False))


def test_check_exact_in_an_impure_function_is_rejected():
    rejected(DEOPT, fn(params=[obj()], pure=False, may_deopt=False,
                       body=[IMPURE, Return(check(lo("q")))]))
    rejected(DEOPT, fn(params=[obj()], pure=False, may_deopt=True,
                       body=[IMPURE, Return(check(lo("q")))]))


def test_a_redundant_check_exact_still_counts_as_a_deopt_point():
    rejected(DEOPT, fn(params=[pt()], pure=False, body=[IMPURE, Return(check(lo("p")))]))


def test_a_caller_of_a_check_exact_function_inherits_its_deopt():
    callee = fn("g", params=[obj()], may_deopt=True, body=[Return(check(lo("q")))])
    impure = fn("f", params=[obj()], pure=False, body=[IMPURE, Return(Call(OBJ, "g", (lo("q"),)))])
    rejected(DEOPT, impure, callee)
    accepted(fn("f", params=[obj()], may_deopt=True, body=[Return(Call(OBJ, "g", (lo("q"),)))]),
             callee)


def test_a_call_whose_callee_guards_a_class_param_may_deopt_unless_the_argument_is_proved():
    callee = fn("g", params=[pt()], body=[Return(fget(lo("p"), "x"))])
    unproved = fn("f", params=[obj()], may_deopt=True, body=[Return(Call(OBJ, "g", (lo("q"),)))])
    accepted(unproved, callee)
    rejected(FLAG_DEOPT, dataclasses.replace(unproved, may_deopt=False), callee)
    rejected(DEOPT, fn("f", params=[obj()], pure=False, body=[IMPURE,
                                                              Return(Call(OBJ, "g", (lo("q"),)))]),
             callee)
    # proved arguments cannot fail the callee's guard: no deopt
    for arg, params in ((lo("p"), [pt()]), (new(g_("a"), g_("b")), [])):
        accepted(fn("f", params=params, may_deopt=False, body=[Return(Call(OBJ, "g", (arg,)))]),
                 callee)
    accepted(fn("f", params=[obj()], may_deopt=True,
                body=[Return(Call(OBJ, "g", (check(lo("q")),)))]), callee)     # the CheckExact deopts
    accepted(fn("f", params=[pt()], pure=False, body=[IMPURE, Return(Call(OBJ, "g", (lo("p"),)))]),
             callee)
    # the wrong class proves nothing for this callee
    rejected(FLAG_DEOPT, fn("f", params=[pt("p", cls="Other")], may_deopt=False,
                            body=[Return(Call(OBJ, "g", (lo("p"),)))]), callee)
    # an optional class param is satisfied by a proved exact argument too, not by an unproved one
    opt = fn("g", params=[pt(optional=True)], body=[Return(g_("a"))])
    accepted(fn("f", params=[pt()], may_deopt=False, body=[Return(Call(OBJ, "g", (lo("p"),)))]), opt)
    rejected(FLAG_DEOPT, fn("f", params=[obj()], may_deopt=False,
                            body=[Return(Call(OBJ, "g", (lo("q"),)))]), opt)


def test_field_set_is_an_effect():
    rejected(FLAG_PURE, fn(params=[pt()], pure=True, body=[fset(lo("p"), "x", g_("v")),
                                                           Return(lo("p"))]))
    callee = fn("g", params=[pt()], pure=False, body=[fset(lo("p"), "x", g_("v")), Return(lo("p"))])
    rejected(FLAG_PURE, fn("f", params=[pt()], pure=True, body=[Return(Call(OBJ, "g", (lo("p"),)))]),
             callee)


def test_field_set_makes_a_function_not_closed():
    rejected(ENTRY_OPEN, fn(params=[pt()], pure=False, returns=F64, entry_globals=[Param("G", F64)],
                            body=[fset(lo("p"), "x", g_("v")), Return(Local(F64, "G"))]))


def test_reads_identity_and_allocation_are_not_effects():
    accepted(getter([Return(fget(lo("p"), "x"))]))
    accepted(getter([If(is_none(lo("q")), (Return(g_("a")),)), Return(g_("b"))],
                    params=(obj(),)))
    accepted(getter([If(exact(lo("q")), (Return(g_("a")),)), Return(g_("b"))], params=(obj(),)))
    accepted(getter([Return(new(g_("a"), g_("b")))], params=()))
    accepted(fn(params=[pt()], returns=F64, entry_globals=[Param("G", F64)],
                body=[Assign("t", fget(lo("p"), "x")), Return(Local(F64, "G"))],
                locals_={"t": OBJ}))                    # a field read keeps a function closed


# --- 4. typing -----------------------------------------------------------------------------------

def test_is_and_is_exact_give_bool_over_objects():
    accepted(getter([If(Is(BOOL, lo("q"), g_("k")), (Return(g_("a")),)), Return(g_("b"))],
                    params=(obj(),)))
    rejected(TYPE, getter([If(Is(OBJ, lo("q"), g_("k")), (Return(g_("a")),)), Return(g_("b"))],
                          params=(obj(),)))
    rejected(OWNERSHIP, getter([If(Is(BOOL, Const(I64, 1), g_("k")), (Return(g_("a")),)),
                                Return(g_("b"))], params=(obj(),)))
    rejected(TYPE, getter([If(Is(BOOL, lo("q"), g_("k"), 1), (Return(g_("a")),)),
                           Return(g_("b"))], params=(obj(),)))
    rejected(TYPE, getter([If(IsExact(OBJ, lo("q"), "Point"), (Return(g_("a")),)),
                           Return(g_("b"))], params=(obj(),)))
    rejected(OWNERSHIP, getter([If(IsExact(BOOL, Const(F64, 1.0), "Point"), (Return(g_("a")),)),
                                Return(g_("b"))], params=(obj(),)))


def test_field_get_new_and_check_exact_give_obj():
    for node in (fget(lo("p"), "x"), check(lo("p")), new(g_("a"), g_("b"))):
        for wrong in (I64, F64, BOOL):
            rejected(TYPE, getter([Return(dataclasses.replace(node, type=wrong))],
                                  may_deopt=isinstance(node, CheckExact), returns=wrong))


def test_the_operand_of_a_field_node_must_be_an_object():
    rejected(OWNERSHIP, getter([Return(fget(Const(I64, 1), "x"))], params=()))
    rejected(OWNERSHIP, getter([Return(check(Const(I64, 1)))], params=(), may_deopt=True))
    rejected(OWNERSHIP, getter([If(exact(Const(I64, 1)), (Return(g_("a")),)), Return(g_("b"))],
                               params=()))


def test_none_is_an_obj_constant_and_nothing_else_is():
    rejected(TYPE, getter([If(Is(BOOL, lo("q"), Const(OBJ, 0)), (Return(g_("a")),)),
                           Return(g_("b"))], params=(obj(),)))
    rejected(TYPE, getter([If(Is(BOOL, lo("q"), Const(OBJ, "x")), (Return(g_("a")),)),
                           Return(g_("b"))], params=(obj(),)))
    # a None constant is a new reference to the singleton wherever an object may flow
    accepted(getter([Return(none())], params=()))
    accepted(getter([Assign("t", none()), Return(lo("t"))], params=(), locals_={"t": OBJ}))
    accepted(getter([Return(new(none(), g_("b")))], params=()))
    rejected(TYPE, getter([Return(Const(I64, None))], params=(), returns=I64))
    rejected(TYPE, getter([Return(Const(OBJ, 0))], params=()))


def test_a_none_constant_still_fits_no_scalar_slot():
    rejected(OWNERSHIP, getter([Return(none())], params=(), returns=F64))


# --- 5. ownership --------------------------------------------------------------------------------

def test_new_field_get_and_check_exact_are_reference_creators():
    accepted(getter([Return(new(g_("a"), g_("b")))], params=()))
    accepted(getter([Assign("t", fget(lo("p"), "x")), Return(lo("t"))], locals_={"t": OBJ}))
    accepted(getter([Return(check(lo("q")))], params=(obj(),), may_deopt=True))


def test_an_object_cannot_flow_into_a_scalar_slot_from_these_nodes():
    rejected(OWNERSHIP, getter([Return(fget(lo("p"), "x"))], returns=F64))
    rejected(OWNERSHIP, getter([Assign("n", fget(lo("p"), "x")), Return(li("n"))],
                               locals_={"n": I64}, returns=I64))


# --- "nothing slips through": seeded mutation of class-using functions ---------------------------

def _base_functions():
    pure = fn("mix", params=[pt("p"), obj("q")], locals_={"t": OBJ, "r": OBJ}, returns=OBJ, pure=True,
              may_deopt=True, line=3, body=[
                  Assign("t", check(lo("q"))),
                  Assign("r", new(fget(lo("t"), "x"), fget(lo("p"), "y"))),
                  If(exact(lo("q")), (Return(fget(lo("q"), "x")),)),
                  Return(fget(lo("r"), "y")),
              ])
    impure = fn("move", params=[pt("p"), pt("o", optional=True), obj("n")], locals_={"t": OBJ},
                returns=NONE, pure=False, may_deopt=False, line=20, body=[
                    IMPURE,
                    If(is_none(lo("o"), negate=True),
                       (fset(lo("o"), "x", fget(lo("p"), "y")),)),
                    If(not_(exact(lo("n"))), (Return(),)),
                    fset(lo("p"), "x", fget(lo("n"), "x")),
                    Assign("t", new(fget(lo("n"), "y"), g_("k"))),
                    fset(lo("t"), "y", lo("n")),
                    Return(),
                ])
    return pure, impure


def test_the_unmutated_class_functions_are_accepted():
    for g in _base_functions():
        accepted(g)


def _paths(node, pred, path=()):
    out = []
    if pred(node):
        out.append((path, node))
    if dataclasses.is_dataclass(node) and not isinstance(node, type):
        for fl in dataclasses.fields(node):
            out += _paths(getattr(node, fl.name), pred, path + (fl.name,))
    elif isinstance(node, tuple):
        for k, x in enumerate(node):
            out += _paths(x, pred, path + (k,))
    return out


def _replace(node, path, new_):
    if not path:
        return new_
    head, rest = path[0], path[1:]
    if isinstance(node, tuple):
        return node[:head] + (_replace(node[head], rest, new_),) + node[head + 1:]
    return dataclasses.replace(node, **{head: _replace(getattr(node, head), rest, new_)})


def _classy(n): return isinstance(n, (FieldGet, FieldSet, New, IsExact, CheckExact))


def _class_mutations(rng, g):
    """(description, mutant, acceptable rules): each mutant breaks a class property by construction."""
    kind = rng.randrange(11)
    sites = _paths(g.body, _classy)
    path, node = rng.choice(sites)
    if kind == 0:       # a class that is not declared
        return "unknown class", dataclasses.replace(g, body=_replace(
            g.body, path, dataclasses.replace(node, cls="Nowhere"))), {CLASS}
    if kind == 1:       # a field that is not in the class
        fields = _paths(g.body, lambda n: isinstance(n, (FieldGet, FieldSet)))
        fpath, fnode = rng.choice(fields)
        return "unknown field", dataclasses.replace(g, body=_replace(
            g.body, fpath, dataclasses.replace(fnode, field="nope"))), {CLASS}
    if kind == 2:       # New with the wrong arity
        news = _paths(g.body, lambda n: isinstance(n, New))
        npath, nnode = rng.choice(news)
        args = nnode.args[:-1] if rng.random() < 0.5 else nnode.args + (g_("extra"),)
        return "new arity", dataclasses.replace(g, body=_replace(
            g.body, npath, dataclasses.replace(nnode, args=args))), {CLASS}
    if kind == 3:       # New of a class whose __init__ is not trivial
        news = _paths(g.body, lambda n: isinstance(n, New))
        npath, nnode = rng.choice(news)
        return "new nontrivial", dataclasses.replace(g, body=_replace(
            g.body, npath, dataclasses.replace(nnode, cls="SlowInit", args=(g_("a"),)))), {
            CLASS, FIELD}
    if kind == 4:       # CheckExact in an impure function / FieldSet in a pure one
        if g.pure:
            st = fset(lo("p"), "x", g_("v"))
            return "fieldset in pure", dataclasses.replace(g, body=(st,) + g.body), {FLAG_PURE}
        st = ExprStmt(check(lo("n")))
        return "checkexact in impure", dataclasses.replace(g, body=(IMPURE, st) + g.body[1:]), {
            DEOPT}
    if kind == 5:       # drop the entry guard of the exact param
        ps = tuple(dataclasses.replace(p, cls=None) if p.name == "p" else p for p in g.params)
        return "drop param guard", dataclasses.replace(g, params=ps), {FIELD}
    if kind == 6:       # drop the IsExact / is-not-None guard: run the then branch unconditionally
        ifs = _paths(g.body, lambda n: isinstance(n, If) and _guards(n.cond))
        ipath, inode = rng.choice(ifs)
        # the then body replaces the If inside its block
        block_path, idx = ipath[:-1], ipath[-1]
        block = _replace_get(g.body, block_path)
        new_block = block[:idx] + inode.then + block[idx + 1:]
        return "drop guard", dataclasses.replace(g, body=_replace(g.body, block_path, new_block)), {
            FIELD}
    if kind == 7:       # assign over a proved local right before its use
        body = g.body
        victim = "t" if g.pure else "p"
        k = next(j for j, s in enumerate(body)
                 if _uses(s, victim) and not (isinstance(s, Assign) and s.target == victim))
        st = Assign(victim, g_("unknown"))
        return "kill proof", dataclasses.replace(g, body=body[:k] + (st,) + body[k:]), {FIELD}
    if kind == 8:       # break the pure/may_deopt flags
        if g.pure:
            return "flip may_deopt", dataclasses.replace(g, may_deopt=False), {FLAG_DEOPT}
        return "flip pure", dataclasses.replace(g, pure=True), {FLAG_PURE}
    if kind == 9:       # retype a class node
        path, node = rng.choice(_paths(g.body, lambda n: isinstance(n, ir.Expr) and _classy(n)))
        other = rng.choice([t for t in (I64, F64, BOOL, NONE) if t is not node.type
                            and (t is not OBJ)])
        if isinstance(node, IsExact):
            other = rng.choice([OBJ, I64])
        return "retype", dataclasses.replace(g, body=_replace(
            g.body, path, dataclasses.replace(node, type=other))), {TYPE, OWNERSHIP}
    # kind 10: an operand that is a scalar
    objs = _paths(g.body, lambda n: isinstance(n, (FieldGet, FieldSet, IsExact, CheckExact)))
    opath, onode = rng.choice(objs)
    return "scalar operand", dataclasses.replace(g, body=_replace(
        g.body, opath, dataclasses.replace(onode, obj=Const(I64, 1)))), {OWNERSHIP, TYPE}


def _guards(cond):
    return (isinstance(cond, IsExact) or isinstance(cond, Is)
            or (isinstance(cond, UnaryOp) and isinstance(cond.operand, IsExact)))


def _replace_get(node, path):
    for step in path:
        node = node[step] if isinstance(node, tuple) else getattr(node, step)
    return node


def _uses(stmt, name):
    return bool(_paths(stmt, lambda n: isinstance(n, Local) and n.name == name))


@pytest.mark.parametrize("seed", range(40))
def test_every_property_breaking_class_mutation_is_rejected(seed):
    rng = random.Random(seed)
    base = _base_functions()
    seen = set()
    for _ in range(60):
        g = rng.choice(base)
        try:
            desc, mutant, rules = _class_mutations(rng, g)
        except (IndexError, StopIteration):
            continue        # the mutation has no site in this function
        out, diags = run(mutant)
        seen.add(desc)
        assert mutant.name not in kept(out), f"{desc} was accepted"
        assert rules & rules_for(diags, mutant.name), (desc, rules, [d.rule for d in diags])


def test_all_the_mutation_kinds_are_exercised_across_the_seeds():
    kinds = set()
    for seed in range(40):
        rng = random.Random(seed)
        for _ in range(60):
            g = rng.choice(_base_functions())
            try:
                kinds.add(_class_mutations(rng, g)[0])
            except (IndexError, StopIteration):
                pass
    assert kinds >= {"unknown class", "unknown field", "new arity", "fieldset in pure",
                     "checkexact in impure", "drop param guard", "drop guard", "kill proof",
                     "flip may_deopt", "flip pure", "retype", "scalar operand", "new nontrivial"}


def test_dropping_each_guard_of_the_base_functions_is_caught_one_by_one():
    pure, impure = _base_functions()
    # the three guards: p's entry guard (both), q's IsExact (pure), o's is-not-None and n's early exit
    for g, guard_param in ((pure, "p"), (impure, "p"), (impure, "o")):
        ps = tuple(dataclasses.replace(p, cls=None, optional=False) if p.name == guard_param else p
                   for p in g.params)
        rejected(FIELD, dataclasses.replace(g, params=ps), name=g.name)
    drop_if = dataclasses.replace(pure, body=pure.body[:2] + pure.body[2].then + pure.body[3:])
    rejected(FIELD, drop_if, name="mix")
    no_none_guard = dataclasses.replace(
        impure, body=impure.body[:1] + impure.body[1].then + impure.body[2:])
    rejected(FIELD, no_none_guard, name="move")
    no_exit = dataclasses.replace(impure, body=impure.body[:2] + impure.body[3:])
    rejected(FIELD, no_exit, name="move")
