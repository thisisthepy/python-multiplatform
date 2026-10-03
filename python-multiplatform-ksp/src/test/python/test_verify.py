"""The IR safety verifier (SPEC N-8, #41).

`verify.verify(module)` proves each function's safety properties on the IR; a function it cannot
prove is removed from `functions`, moved to `skipped` with the reason, and reported. Every test
builds IR by hand: the verifier must not depend on the front end.

Each property has an accepted example and rejected examples with the expected rule id. The last
section mutates correct functions at random (seeded) and checks every property-breaking mutation
is caught, and that property-preserving rewrites are not rejected.
"""
from __future__ import annotations

import dataclasses
import random
from dataclasses import dataclass

import pytest

from typedpython import ir
from typedpython.ir import (
    And, ArrayParam, Assign, BinOp, BinOpKind, Box, Break, Call, CallObject, Compare, CompareKind,
    CompareObj, Const, Continue, CopyArray, ExprStmt, ForRange, Function, GetAttr, Global, If, Index,
    Len, Local, MathCall, MathFunc, Module, NewArray, ObjToFloat, Or, Param, Return, StoreIndex,
    ToFloat, Truth, Tuple, Type, Unbox, UnaryOp, UnaryOpKind, While,
)

I64, F64, BOOL, NONE, OBJ = Type.I64, Type.F64, Type.BOOL, Type.NONE, Type.OBJ
F64A, I64A = Type.F64_ARRAY, Type.I64_ARRAY

DA = "verify/definite-assignment"
MISSING_RETURN = "verify/missing-return"
TYPE = "verify/type"
OWNERSHIP = "verify/ownership"
DEOPT = "verify/deopt-impure"
FLAG_DEOPT = "verify/flag-may-deopt"
FLAG_PURE = "verify/flag-pure"
ARRAY_USE = "verify/array-use"
ARRAY_STORED = "verify/array-stored"
UNKNOWN = "verify/unknown-node"
UNRESOLVED = "verify/call-unresolved"
UNVERIFIED = "verify/call-unverified"
RECURSION = "verify/recursion"
STRUCTURE = "verify/structure"
CONDITION_ONLY = "verify/condition-only"
PROVEN = "verify/proven-overflow"
REDO = "verify/redo"
ENTRY_OPEN = "verify/entry-global-open"
ESCAPE = "verify/array-escape"


def _verify():
    # Imported lazily so that, before verify.py exists, every test fails on this line (red
    # stage) instead of the whole file failing to collect.
    from typedpython import verify
    return verify


# --- builders ------------------------------------------------------------------------------------

def i(v): return Const(I64, v)
def f(v): return Const(F64, v)
def b(v): return Const(BOOL, v)
def li(n): return Local(I64, n)
def lf(n): return Local(F64, n)
def lb(n): return Local(BOOL, n)
def lo(n): return Local(OBJ, n)
def add(l, r, t=I64): return BinOp(t, BinOpKind.ADD, l, r)
def lt(l, r): return Compare(BOOL, CompareKind.LT, l, r)
def g_(n): return Global(OBJ, n)
def call_obj(callee, *args, kw=()): return CallObject(OBJ, callee, tuple(args), tuple(kw))
def pycall(name, *args): return call_obj(g_(name), *args)


def padd(l, r): return BinOp(I64, BinOpKind.ADD, l, r, proven=True)
def psub(l, r): return BinOp(I64, BinOpKind.SUB, l, r, proven=True)
def pmul(l, r): return BinOp(I64, BinOpKind.MUL, l, r, proven=True)
def pneg(x): return UnaryOp(I64, UnaryOpKind.NEG, x, proven=True)
IMPURE = ExprStmt(CallObject(OBJ, Global(OBJ, "print"), (), ()))    # makes a function impure


def fn(name="f", body=(), params=(), locals_=None, returns=NONE, pure=False, may_deopt=False,
       line=10, entry_globals=()):
    return Function(name=name, params=tuple(params), returns=returns, locals=dict(locals_ or {}),
                    body=tuple(body), pure=pure, may_deopt=may_deopt, source_line=line,
                    entry_globals=tuple(entry_globals))


def run(*functions, skipped=None):
    return _verify().verify(Module("m", tuple(functions), dict(skipped or {})))


def kept(out):
    return [g.name for g in out.functions]


def rules_for(diags, name):
    return {d.rule for d in diags if d.function == name}


def assert_accepted(*functions, skipped=None):
    out, diags = run(*functions, skipped=skipped)
    assert kept(out) == [g.name for g in functions], [dataclasses.astuple(d) for d in diags]
    assert diags == []
    return out


def assert_rejected(rule, *functions, name="f"):
    out, diags = run(*functions)
    assert name not in kept(out)
    assert name in out.skipped
    assert rule in rules_for(diags, name), [dataclasses.astuple(d) for d in diags]
    return out, diags


# --- output shape --------------------------------------------------------------------------------

def test_output_keeps_proved_functions_and_moves_others_to_skipped():
    good = fn("good", [Return()])
    bad = fn("bad", [Return(li("nope"))], returns=I64, line=42)
    out, diags = run(good, bad, skipped={"front": "uses **kwargs"})
    assert kept(out) == ["good"]
    assert out.name == "m"
    assert out.skipped["front"] == "uses **kwargs"
    assert "bad" in out.skipped and out.skipped["bad"]
    d = [d for d in diags if d.function == "bad"][0]
    assert d.source_line == 42 and d.rule and d.message
    assert all(d.function == "bad" for d in diags)


# --- 1. definite assignment ----------------------------------------------------------------------

def test_assigned_on_both_branches_is_accepted():
    assert_accepted(fn(params=[Param("c", BOOL)], locals_={"x": I64}, returns=I64, body=[
        If(lb("c"), (Assign("x", i(1)),), (Assign("x", i(2)),)),
        Return(li("x")),
    ]))


def test_read_before_assign_on_one_branch_is_rejected_and_names_the_local():
    _, diags = assert_rejected(DA, fn(params=[Param("c", BOOL)], locals_={"x": I64}, returns=I64,
                                      body=[If(lb("c"), (Assign("x", i(1)),)), Return(li("x"))]))
    assert any("'x'" in d.message for d in diags if d.rule == DA)


def test_branch_that_returns_does_not_need_the_assignment():
    assert_accepted(fn(params=[Param("c", BOOL)], locals_={"x": I64}, returns=I64, body=[
        If(lb("c"), (Return(i(0)),), (Assign("x", i(2)),)),
        Return(li("x")),
    ]))


def test_loop_var_read_after_possibly_zero_iteration_loop_is_rejected():
    assert_rejected(DA, fn(params=[Param("n", I64)], locals_={"k": I64}, returns=I64, body=[
        ForRange("k", i(0), li("n"), i(1), (ExprStmt(li("k")),)),
        Return(li("k")),
    ]))


def test_loop_var_assigned_before_the_loop_may_be_read_after_it():
    assert_accepted(fn(params=[Param("n", I64)], locals_={"k": I64}, returns=I64, body=[
        Assign("k", i(-1)),
        ForRange("k", i(0), li("n"), i(1), (ExprStmt(li("k")),)),
        Return(li("k")),
    ]))


def test_assignment_inside_a_loop_body_does_not_count_after_the_loop():
    assert_rejected(DA, fn(params=[Param("c", BOOL)], locals_={"x": I64}, returns=I64, body=[
        While(lb("c"), (Assign("x", i(1)), Break())),
        Return(li("x")),
    ]))


def test_read_in_loop_body_before_its_assignment_in_the_body_is_rejected():
    assert_rejected(DA, fn(params=[Param("c", BOOL)], locals_={"x": I64, "y": I64}, body=[
        While(lb("c"), (Assign("y", li("x")), Assign("x", i(1)))),
        Return(),
    ]))


def test_continue_skips_the_rest_of_the_loop_body():
    # `continue` before the assignment: the read after the If in the body is reached only on the
    # path that assigned x — accepted. The read after the loop is not (zero iterations).
    body_ok = (If(lb("c"), (Continue(),), (Assign("x", i(1)),)), ExprStmt(li("x")))
    assert_accepted(fn(params=[Param("c", BOOL)], locals_={"x": I64},
                       body=[While(lb("c"), body_ok), Return()]))
    assert_rejected(DA, fn(params=[Param("c", BOOL)], locals_={"x": I64}, returns=I64,
                           body=[While(lb("c"), body_ok), Return(li("x"))]))


def test_while_true_exits_only_through_break_or_return():
    assert_accepted(fn(locals_={"x": I64}, returns=I64, body=[
        While(b(True), (Assign("x", i(3)), Break())),
        Return(li("x")),
    ]))
    assert_accepted(fn(returns=I64, body=[While(b(True), (Return(i(1)),))]))


def test_falling_off_the_end_of_a_valued_function_is_rejected():
    assert_rejected(MISSING_RETURN, fn(params=[Param("c", BOOL)], returns=I64,
                                       body=[If(lb("c"), (Return(i(1)),))]))


def test_parameters_are_assigned_at_entry():
    assert_accepted(fn(params=[Param("x", F64)], returns=F64, body=[Return(lf("x"))]))


def test_break_outside_a_loop_is_rejected():
    assert_rejected(STRUCTURE, fn(body=[Break()]))
    assert_rejected(STRUCTURE, fn(body=[Continue()]))


def test_assigning_the_loop_var_in_the_body_is_rejected():
    assert_rejected(STRUCTURE, fn(params=[Param("n", I64)], locals_={"k": I64}, body=[
        ForRange("k", i(0), li("n"), i(1), (Assign("k", i(5)),)),
    ]))


def test_zero_step_is_rejected():
    assert_rejected(STRUCTURE, fn(params=[Param("n", I64)], locals_={"k": I64}, body=[
        ForRange("k", i(0), li("n"), i(0), ()),
    ]))


# --- 2. typing -----------------------------------------------------------------------------------

def test_mixed_arithmetic_through_to_float_is_accepted():
    assert_accepted(fn(params=[Param("x", F64), Param("n", I64)], returns=F64,
                       body=[Return(add(lf("x"), ToFloat(F64, li("n")), F64))]))


def test_f64_plus_i64_without_to_float_is_rejected():
    assert_rejected(TYPE, fn(params=[Param("x", F64), Param("n", I64)], returns=F64,
                             body=[Return(add(lf("x"), li("n"), F64))]))


def test_i64_truediv_result_must_be_f64():
    ok = BinOp(F64, BinOpKind.TRUEDIV, li("a"), li("b"))
    bad = BinOp(I64, BinOpKind.TRUEDIV, li("a"), li("b"))
    ps = [Param("a", I64), Param("b", I64)]
    assert_accepted(fn(params=ps, returns=F64, pure=True, may_deopt=True, body=[Return(ok)]))
    assert_rejected(TYPE, fn(params=ps, returns=I64, pure=True, may_deopt=True,
                             body=[Return(bad)]))


def test_bool_arithmetic_is_rejected():
    assert_rejected(TYPE, fn(params=[Param("c", BOOL)], returns=BOOL,
                             body=[Return(add(lb("c"), lb("c"), BOOL))]))


def test_compare_result_must_be_bool_and_mixed_numeric_compare_is_allowed():
    assert_accepted(fn(params=[Param("x", F64), Param("n", I64)], returns=BOOL,
                       body=[Return(lt(li("n"), lf("x")))]))
    assert_rejected(TYPE, fn(params=[Param("x", F64), Param("n", I64)], returns=I64,
                             body=[Return(Compare(I64, CompareKind.LT, li("n"), lf("x")))]))


def test_and_or_need_bool_operands():
    assert_accepted(fn(params=[Param("c", BOOL)], returns=BOOL,
                       body=[Return(Or(BOOL, And(BOOL, lb("c"), b(True)), lb("c")))]))
    assert_rejected(TYPE, fn(params=[Param("n", I64), Param("c", BOOL)], returns=BOOL,
                             body=[Return(And(BOOL, li("n"), lb("c")))]))


def test_if_and_while_conditions_must_be_bool():
    assert_rejected(TYPE, fn(params=[Param("n", I64)], body=[If(li("n"), (Return(),))]))
    assert_rejected(TYPE, fn(params=[Param("n", I64)], body=[While(li("n"), (Break(),))]))


def test_assign_value_must_match_declared_local_type():
    assert_rejected(TYPE, fn(locals_={"x": I64}, body=[Assign("x", f(1.0))]))


def test_assign_to_undeclared_local_is_rejected():
    assert_rejected(TYPE, fn(body=[Assign("x", f(1.0))]))


def test_local_read_must_match_its_declaration():
    assert_rejected(TYPE, fn(params=[Param("x", F64)], returns=I64, body=[Return(li("x"))]))


def test_return_type_must_match():
    assert_rejected(TYPE, fn(returns=I64, body=[Return(f(1.0))]))
    assert_rejected(TYPE, fn(returns=I64, body=[Return()]))
    assert_rejected(TYPE, fn(returns=NONE, body=[Return(i(1))]))


def test_const_value_must_match_its_type():
    assert_rejected(TYPE, fn(returns=I64, body=[Return(i(1.5))]))
    assert_rejected(TYPE, fn(returns=I64, body=[Return(i(True))]))
    assert_rejected(TYPE, fn(returns=I64, body=[Return(i(2 ** 63))]))
    assert_rejected(TYPE, fn(returns=F64, body=[Return(f(1))]))


def test_call_arity_and_argument_types_are_checked_against_the_callee():
    g = fn("g", params=[Param("x", F64)], returns=F64, body=[Return(lf("x"))])
    ok = fn("f", returns=F64, body=[Return(Call(F64, "g", (f(1.0),)))])
    assert_accepted(ok, g)
    assert_rejected(TYPE, fn("f", returns=F64, body=[Return(Call(F64, "g", ()))]), g)
    assert_rejected(TYPE, fn("f", returns=F64, body=[Return(Call(F64, "g", (i(1),)))]), g)
    assert_rejected(TYPE, fn("f", returns=I64, body=[Return(Call(I64, "g", (f(1.0),)))]), g)


def test_index_store_len_need_array_params():
    ps = [Param("a", F64)]
    assert_rejected(ARRAY_USE, fn(params=ps, returns=F64, body=[Return(Index(F64, "a", i(0)))]))
    assert_rejected(ARRAY_USE, fn(params=ps, returns=I64, body=[Return(Len(I64, "a"))]))
    assert_rejected(ARRAY_USE, fn(params=ps, body=[StoreIndex("a", i(0), f(1.0)), Return()]))


def test_index_type_and_element_type():
    a = [ArrayParam("a", F64A, stored=True)]
    assert_rejected(TYPE, fn(params=a, body=[StoreIndex("a", i(0), i(1)), Return()]))
    assert_rejected(TYPE, fn(params=a, returns=I64, body=[Return(Index(I64, "a", i(0)))]))
    assert_rejected(TYPE, fn(params=[ArrayParam("a", F64A, stored=False)], returns=F64,
                             body=[Return(Index(F64, "a", f(0.0)))]))


def test_math_call_needs_f64_args_and_the_right_arity():
    ps = [Param("x", F64), Param("n", I64)]
    assert_accepted(fn(params=ps, returns=F64, body=[
        Return(MathCall(F64, MathFunc.ATAN2, (MathCall(F64, MathFunc.SQRT, (lf("x"),)), lf("x"))))
    ]))
    assert_rejected(TYPE, fn(params=ps, returns=F64,
                             body=[Return(MathCall(F64, MathFunc.SQRT, (li("n"),)))]))
    assert_rejected(TYPE, fn(params=ps, returns=F64,
                             body=[Return(MathCall(F64, MathFunc.ATAN2, (lf("x"),)))]))
    assert_rejected(TYPE, fn(params=ps, returns=F64,
                             body=[Return(MathCall(F64, MathFunc.SQRT, (lf("x"), lf("x"))))]))


def test_not_needs_bool_and_neg_keeps_type():
    assert_accepted(fn(params=[Param("c", BOOL), Param("x", F64)], returns=BOOL, body=[
        ExprStmt(UnaryOp(F64, UnaryOpKind.NEG, lf("x"))),
        Return(UnaryOp(BOOL, UnaryOpKind.NOT, lb("c"))),
    ]))
    assert_rejected(TYPE, fn(params=[Param("n", I64)], returns=BOOL,
                             body=[Return(UnaryOp(BOOL, UnaryOpKind.NOT, li("n")))]))


# --- 2b. object nodes (Global, GetAttr, CallObject, Truth, CompareObj, ObjToFloat) ---------------

def test_global_read_alone_keeps_a_function_pure():
    assert_accepted(fn(returns=OBJ, pure=True, body=[Return(g_("Color"))]))
    assert_rejected(TYPE, fn(returns=F64, body=[Return(Global(F64, "x"))]))


def test_get_attr_is_obj_to_obj_and_impure():
    assert_accepted(fn(params=[Param("o", OBJ)], returns=OBJ,
                       body=[Return(GetAttr(OBJ, lo("o"), "width"))]))
    assert_rejected(FLAG_PURE, fn(params=[Param("o", OBJ)], returns=OBJ, pure=True,
                                  body=[Return(GetAttr(OBJ, lo("o"), "width"))]))
    assert_rejected(OWNERSHIP, fn(params=[Param("x", F64)], returns=OBJ,
                                  body=[Return(GetAttr(OBJ, lf("x"), "real"))]))
    assert_rejected(TYPE, fn(params=[Param("o", OBJ)], returns=F64,
                             body=[Return(GetAttr(F64, lo("o"), "width"))]))


def test_call_object_takes_obj_callee_and_args_and_is_impure():
    assert_accepted(fn(params=[Param("x", F64), Param("o", OBJ)], returns=OBJ, body=[
        Return(call_obj(GetAttr(OBJ, g_("Modifier"), "padding"), lo("o"), Box(OBJ, lf("x")),
                        kw=("dp",))),
    ]))
    assert_rejected(FLAG_PURE, fn(returns=OBJ, pure=True, body=[Return(pycall("make"))]))
    assert_rejected(OWNERSHIP, fn(params=[Param("x", F64)], returns=OBJ,
                                  body=[Return(pycall("make", lf("x")))]))
    assert_rejected(OWNERSHIP, fn(params=[Param("x", F64)], returns=OBJ,
                                  body=[Return(call_obj(lf("x")))]))
    assert_rejected(TYPE, fn(params=[Param("o", OBJ)], returns=OBJ,
                             body=[Return(call_obj(g_("make"), lo("o"), kw=("a", "b")))]))
    assert_rejected(TYPE, fn(params=[Param("o", OBJ)], returns=F64,
                             body=[Return(CallObject(F64, g_("make"), (lo("o"),), ()))]))


def test_truth_is_obj_to_bool_and_impure():
    assert_accepted(fn(params=[Param("o", OBJ)], returns=BOOL,
                       body=[Return(Truth(BOOL, lo("o")))]))
    assert_rejected(FLAG_PURE, fn(params=[Param("o", OBJ)], returns=BOOL, pure=True,
                                  body=[Return(Truth(BOOL, lo("o")))]))
    assert_rejected(OWNERSHIP, fn(params=[Param("c", BOOL)], returns=BOOL,
                                  body=[Return(Truth(BOOL, lb("c")))]))
    assert_rejected(TYPE, fn(params=[Param("o", OBJ)], returns=I64,
                             body=[Return(Truth(I64, lo("o")))]))


def test_compare_obj_in_a_condition_is_accepted():
    cmp = CompareObj(BOOL, CompareKind.LT, lo("o"), lf("x"))
    assert_accepted(fn(params=[Param("o", OBJ), Param("x", F64), Param("c", BOOL)], body=[
        If(cmp, (Return(),)),
        While(And(BOOL, lb("c"), Or(BOOL, cmp, b(False))), (Break(),)),
        Return(),
    ]))


def test_compare_obj_outside_a_condition_is_rejected():
    cmp = CompareObj(BOOL, CompareKind.LT, lo("o"), lo("p"))
    ps = [Param("o", OBJ), Param("p", OBJ), Param("c", BOOL)]
    assert_rejected(CONDITION_ONLY, fn(params=ps, returns=BOOL, body=[Return(cmp)]))
    assert_rejected(CONDITION_ONLY, fn(params=ps, locals_={"r": BOOL},
                                       body=[Assign("r", cmp), Return()]))
    # `x = c and (a < b)` yields the rich comparison's own object in CPython, not a bool
    assert_rejected(CONDITION_ONLY, fn(params=ps, locals_={"r": BOOL},
                                       body=[Assign("r", And(BOOL, lb("c"), cmp)), Return()]))
    assert_rejected(CONDITION_ONLY, fn(params=ps,
                                       body=[If(UnaryOp(BOOL, UnaryOpKind.NOT, cmp), ()),
                                             Return()]))


def test_compare_obj_needs_an_obj_operand_and_is_impure():
    ps = [Param("o", OBJ), Param("x", F64)]
    assert_rejected(TYPE, fn(params=ps, body=[
        If(CompareObj(BOOL, CompareKind.LT, lf("x"), lf("x")), ()), Return()]))
    assert_rejected(FLAG_PURE, fn(params=ps, pure=True, body=[
        If(CompareObj(BOOL, CompareKind.LT, lo("o"), lf("x")), ()), Return()]))
    assert_rejected(TYPE, fn(params=ps, body=[
        If(CompareObj(OBJ, CompareKind.LT, lo("o"), lo("o")), ()), Return()]))


def test_obj_to_float_is_obj_to_f64_impure_and_never_deopts():
    assert_accepted(fn(params=[Param("o", OBJ)], returns=F64,
                       body=[Return(ObjToFloat(F64, lo("o")))]))
    assert_rejected(FLAG_PURE, fn(params=[Param("o", OBJ)], returns=F64, pure=True,
                                  body=[Return(ObjToFloat(F64, lo("o")))]))
    assert_rejected(OWNERSHIP, fn(params=[Param("n", I64)], returns=F64,
                                  body=[Return(ObjToFloat(F64, li("n")))]))
    assert_rejected(TYPE, fn(params=[Param("o", OBJ)], returns=I64,
                             body=[Return(ObjToFloat(I64, lo("o")))]))


def test_obj_binop_is_impure():
    assert_rejected(FLAG_PURE, fn(params=[Param("o", OBJ)], returns=OBJ, pure=True,
                                  body=[Return(BinOp(OBJ, BinOpKind.ADD, lo("o"), lo("o")))]))


# --- 3. deopt placement and flags ----------------------------------------------------------------

def test_i64_add_in_a_pure_may_deopt_function_is_accepted():
    assert_accepted(fn(params=[Param("n", I64)], returns=I64, pure=True, may_deopt=True,
                       body=[Return(add(li("n"), i(1)))]))


def test_i64_add_in_an_impure_function_is_rejected():
    assert_rejected(DEOPT, fn(params=[Param("n", I64)], returns=I64, pure=False, may_deopt=False,
                              body=[Return(add(li("n"), i(1)))]))
    assert_rejected(DEOPT, fn(params=[Param("n", I64)], returns=I64, pure=False, may_deopt=True,
                              body=[Return(add(li("n"), i(1)))]))


@pytest.mark.parametrize("node", [
    BinOp(I64, BinOpKind.SUB, Local(I64, "n"), Const(I64, 1)),
    BinOp(I64, BinOpKind.MUL, Local(I64, "n"), Const(I64, 2)),
    BinOp(I64, BinOpKind.FLOORDIV, Local(I64, "n"), Const(I64, 2)),
    BinOp(I64, BinOpKind.MOD, Local(I64, "n"), Const(I64, 2)),
    BinOp(F64, BinOpKind.TRUEDIV, Local(I64, "n"), Const(I64, 2)),
    UnaryOp(I64, UnaryOpKind.NEG, Local(I64, "n")),
    Unbox(I64, Box(OBJ, Local(I64, "n"))),
])
def test_every_deopting_node_is_rejected_in_an_impure_function(node):
    assert_rejected(DEOPT, fn(params=[Param("n", I64)], body=[ExprStmt(node), Return()]))
    assert_accepted(fn(params=[Param("n", I64)], pure=True, may_deopt=True,
                       body=[ExprStmt(node), Return()]))


def test_non_deopting_nodes_are_fine_in_an_impure_function():
    assert_accepted(fn(params=[Param("x", F64), Param("n", I64), Param("o", OBJ)], returns=F64,
                       body=[
        ExprStmt(pycall("print", Box(OBJ, lf("x")))),
        ExprStmt(UnaryOp(I64, UnaryOpKind.POS, li("n"))),
        ExprStmt(Unbox(F64, Box(OBJ, lf("x")))),
        ExprStmt(ObjToFloat(F64, GetAttr(OBJ, lo("o"), "x"))),
        ExprStmt(BinOp(OBJ, BinOpKind.MUL, lo("o"), lo("o"))),
        Return(BinOp(F64, BinOpKind.FLOORDIV, lf("x"), f(2.0))),
    ]))


def test_may_deopt_flag_must_match_the_body():
    assert_rejected(FLAG_DEOPT, fn(params=[Param("n", I64)], returns=I64, pure=True,
                                   may_deopt=False, body=[Return(add(li("n"), i(1)))]))
    assert_rejected(FLAG_DEOPT, fn(params=[Param("x", F64)], returns=F64, pure=True,
                                   may_deopt=True, body=[Return(lf("x"))]))


def test_pure_flag_lying_store_index_is_rejected():
    assert_rejected(FLAG_PURE, fn(params=[ArrayParam("a", F64A, stored=True)], pure=True,
                                  body=[StoreIndex("a", i(0), f(1.0)), Return()]))


def test_pure_flag_lying_call_object_is_rejected():
    assert_rejected(FLAG_PURE, fn(pure=True, body=[ExprStmt(pycall("print")), Return()]))


def test_pure_function_may_only_call_pure_functions():
    g = fn("g", body=[ExprStmt(pycall("print")), Return()])
    assert_rejected(FLAG_PURE, fn("f", pure=True, body=[ExprStmt(Call(NONE, "g", ())), Return()]),
                    g)


def test_impure_caller_of_a_may_deopt_callee_is_rejected():
    g = fn("g", params=[Param("n", I64)], returns=I64, pure=True, may_deopt=True,
           body=[Return(add(li("n"), i(1)))])
    caller = fn("f", returns=I64, body=[ExprStmt(pycall("print")),
                                        Return(Call(I64, "g", (i(1),)))])
    out, _ = assert_rejected(DEOPT, caller, g)
    assert "g" in kept(out)


def test_pure_caller_of_a_may_deopt_callee_propagates_may_deopt():
    g = fn("g", params=[Param("n", I64)], returns=I64, pure=True, may_deopt=True,
           body=[Return(add(li("n"), i(1)))])
    assert_accepted(fn("f", returns=I64, pure=True, may_deopt=True,
                       body=[Return(Call(I64, "g", (i(1),)))]), g)
    assert_rejected(FLAG_DEOPT, fn("f", returns=I64, pure=True, may_deopt=False,
                                   body=[Return(Call(I64, "g", (i(1),)))]), g)


# --- 4. arrays -----------------------------------------------------------------------------------

def _sum(stop, index, step=1, start=0, store=False):
    """for k in range(start, stop, step): s = s + a[index]   (or a[index] = 0.0)"""
    body = (StoreIndex("a", index, f(0.0)),) if store else (
        Assign("s", add(lf("s"), Index(F64, "a", index), F64)),)
    return fn(params=[ArrayParam("a", F64A, stored=store), ArrayParam("b", F64A, stored=False)],
              locals_={"s": F64, "k": I64}, returns=F64, body=[
                  Assign("s", f(0.0)),
                  ForRange("k", i(start), stop, i(step), body),
                  Return(lf("s")),
              ])


def _accesses(function):
    found = []

    def walk(node):
        if isinstance(node, (Index, StoreIndex)):
            found.append(node)
        if dataclasses.is_dataclass(node):
            for fl in dataclasses.fields(node):
                walk(getattr(node, fl.name))
        elif isinstance(node, tuple):
            for x in node:
                walk(x)

    walk(function.body)
    return found


def _proven(function):
    out = assert_accepted(function)
    acc = _accesses(out.functions[0])
    assert len(acc) == 1
    return acc[0].proven


def test_range_len_index_is_proven():
    assert _proven(_sum(Len(I64, "a"), li("k"))) is True
    assert _proven(_sum(Len(I64, "a"), li("k"), store=True)) is True
    assert _proven(_sum(Len(I64, "a"), li("k"), start=2, step=3)) is True


@pytest.mark.parametrize("stop,index,step,start", [
    (add(Len(I64, "a"), i(1)), li("k"), 1, 0),            # range(0, len(a)+1)
    (Len(I64, "a"), add(li("k"), i(1)), 1, 0),            # a[k+1]
    (Len(I64, "b"), li("k"), 1, 0),                       # len of another array
    (Len(I64, "a"), li("k"), -1, 0),                      # negative step
    (Len(I64, "a"), li("k"), 1, -1),                      # negative start
    (Len(I64, "a"), i(0), 1, 0),                          # constant index, empty array possible
])
def test_other_indices_keep_the_runtime_check(stop, index, step, start):
    function = _sum(stop, index, step=step, start=start)
    if any(isinstance(n, BinOp) and n.type is I64 for n in (stop, index)):
        function = dataclasses.replace(function, pure=True, may_deopt=True)
    assert _proven(function) is False


def test_index_after_the_loop_is_not_proven():
    function = fn(params=[ArrayParam("a", F64A, stored=False)], locals_={"k": I64}, returns=F64,
                  body=[Assign("k", i(0)),
                        ForRange("k", i(0), Len(I64, "a"), i(1), ()),
                        Return(Index(F64, "a", li("k")))])
    assert _proven(function) is False


def test_a_proven_flag_from_the_input_is_not_trusted():
    function = _sum(Len(I64, "b"), li("k"))
    lying = dataclasses.replace(function, body=(
        function.body[0],
        dataclasses.replace(function.body[1], body=(
            Assign("s", add(lf("s"), Index(F64, "a", li("k"), proven=True), F64)),)),
        function.body[2]))
    assert _proven(lying) is False


def test_array_passed_to_call_is_rejected():
    g = fn("g", params=[ArrayParam("x", F64A, stored=False)], returns=F64,
           body=[Return(Index(F64, "x", i(0)))])
    caller = fn("f", params=[ArrayParam("a", F64A, stored=False)], returns=F64,
                body=[Return(Call(F64, "g", (Local(F64A, "a"),)))])
    assert_rejected(ARRAY_USE, caller, g)


def test_array_read_as_a_value_is_rejected():
    assert_rejected(ARRAY_USE, fn(params=[ArrayParam("a", F64A, stored=False)], returns=OBJ,
                                  body=[Return(Box(OBJ, Local(F64A, "a")))]))
    assert_rejected(ARRAY_USE, fn(params=[ArrayParam("a", F64A, stored=False)], returns=OBJ,
                                  body=[Return(pycall("print", Local(F64A, "a")))]))


def test_assigning_an_array_param_is_rejected():
    assert_rejected(ARRAY_USE, fn(params=[ArrayParam("a", F64A, stored=False)],
                                  locals_={"a": F64}, body=[Assign("a", f(0.0)), Return()]))


def test_stored_flag_must_match_the_body():
    assert_rejected(ARRAY_STORED, fn(params=[ArrayParam("a", F64A, stored=False)],
                                     body=[StoreIndex("a", i(0), f(1.0)), Return()]))
    assert_rejected(ARRAY_STORED, fn(params=[ArrayParam("a", F64A, stored=True)], returns=F64,
                                     body=[Return(Index(F64, "a", i(0)))]))


def test_array_param_must_have_an_array_type():
    assert_rejected(TYPE, fn(params=[ArrayParam("a", F64, stored=False)], body=[Return()]))
    assert_rejected(TYPE, fn(params=[Param("a", F64A)], body=[Return()]))


# --- 5. ownership and node set -------------------------------------------------------------------

def test_box_callobject_unbox_pipeline_is_accepted():
    assert_accepted(fn(params=[Param("x", F64), Param("o", OBJ)], locals_={"r": OBJ}, returns=F64,
                       body=[Assign("r", pycall("f2", Box(OBJ, lf("x")), lo("o"))),
                             Assign("r", BinOp(OBJ, BinOpKind.ADD, lo("r"), lo("o"))),
                             Return(Unbox(F64, lo("r")))]))


def test_obj_into_a_scalar_slot_without_unbox_is_rejected():
    assert_rejected(OWNERSHIP, fn(params=[Param("o", OBJ)], locals_={"x": F64},
                                  body=[Assign("x", lo("o")), Return()]))
    assert_rejected(OWNERSHIP, fn(params=[Param("o", OBJ)], returns=F64, body=[Return(lo("o"))]))
    assert_rejected(OWNERSHIP, fn(params=[Param("o", OBJ)], returns=OBJ,
                                  body=[Return(Box(OBJ, lo("o")))]))
    assert_rejected(OWNERSHIP, fn(params=[Param("o", OBJ)], body=[If(lo("o"), ()), Return()]))


def test_scalar_into_an_obj_slot_without_box_is_rejected():
    assert_rejected(OWNERSHIP, fn(params=[Param("x", F64)], returns=OBJ, body=[Return(lf("x"))]))
    assert_rejected(OWNERSHIP, fn(params=[Param("x", F64)],
                                  body=[ExprStmt(pycall("print", lf("x"))), Return()]))


def test_obj_from_a_node_that_does_not_create_a_reference_is_rejected():
    assert_rejected(OWNERSHIP, fn(returns=OBJ, body=[Return(Const(OBJ, None))]))
    assert_rejected(OWNERSHIP, fn(params=[Param("o", OBJ)], returns=OBJ,
                                  body=[Return(UnaryOp(OBJ, UnaryOpKind.NEG, lo("o")))]))
    assert_rejected(OWNERSHIP, fn(params=[ArrayParam("a", F64A, stored=False)], returns=OBJ,
                                  body=[Return(Index(OBJ, "a", i(0)))]))
    assert_rejected(OWNERSHIP, fn(params=[Param("o", OBJ)], returns=OBJ,
                                  body=[Return(Truth(OBJ, lo("o")))]))
    assert_rejected(OWNERSHIP, fn(params=[Param("o", OBJ)], returns=OBJ,
                                  body=[Return(ObjToFloat(OBJ, lo("o")))]))


@dataclass(frozen=True)
class Weird(ir.Expr):
    pass


@dataclass(frozen=True)
class SneakyBinOp(BinOp):
    pass


@dataclass(frozen=True)
class Free(ir.Stmt):
    name: str


def test_unknown_node_subclasses_are_rejected():
    assert_rejected(UNKNOWN, fn(returns=I64, body=[Return(Weird(I64))]))
    assert_rejected(UNKNOWN, fn(params=[Param("x", F64)], returns=F64,
                                body=[Return(SneakyBinOp(F64, BinOpKind.ADD, lf("x"), lf("x")))]))
    assert_rejected(UNKNOWN, fn(params=[Param("o", OBJ)], body=[Free("o"), Return()]))
    assert_rejected(UNKNOWN, fn(body=[ExprStmt(Return()), Return()]))
    assert_rejected(UNKNOWN, fn(params=[Param("x", F64)], returns=F64,
                                body=[Return(BinOp(F64, "+", lf("x"), lf("x")))]))


# --- 6. calls ------------------------------------------------------------------------------------

def test_unknown_callee_is_rejected():
    assert_rejected(UNRESOLVED, fn(returns=I64, body=[Return(Call(I64, "nowhere", ()))]))


def test_callee_left_interpreted_by_the_front_end_is_unresolved():
    out, diags = run(fn(returns=I64, body=[Return(Call(I64, "g", ()))]), skipped={"g": "kwargs"})
    assert "f" not in kept(out) and UNRESOLVED in rules_for(diags, "f")


def test_caller_of_a_rejected_callee_is_rejected_transitively():
    g = fn("g", returns=I64, body=[Return(f(1.0))])                     # type error
    h = fn("h", returns=I64, body=[Return(Call(I64, "g", ()))])
    k = fn("k", returns=I64, body=[Return(Call(I64, "h", ()))])
    out, diags = run(k, h, g)
    assert kept(out) == []
    assert UNVERIFIED in rules_for(diags, "h") and UNVERIFIED in rules_for(diags, "k")


def test_recursion_is_rejected():
    assert_rejected(RECURSION, fn("f", returns=I64, body=[Return(Call(I64, "f", ()))]))
    g = fn("g", returns=I64, body=[Return(Call(I64, "f", ()))])
    out, diags = assert_rejected(RECURSION, fn("f", returns=I64, body=[Return(Call(I64, "g", ()))]),
                                 g)
    assert "g" not in kept(out)


def test_duplicate_function_names_are_rejected():
    out, diags = run(fn("f", body=[Return()]), fn("f", body=[Return()]))
    assert kept(out) == [] and STRUCTURE in rules_for(diags, "f")


# --- 7. proven i64 operations (BinOp.proven / UnaryOp.proven) ------------------------------------
#
# The verifier never trusts `proven`: it computes an interval for every I64 expression and accepts
# a proven op only when the exact result interval fits in i64. Intervals (verify.py documents them):
# Const [v, v]; a ForRange var inside its own loop [lo(start), hi(stop)-1] (step > 0) or
# [lo(stop)+1, hi(start)] (step < 0); Len [0, 2**60-1]; ADD/SUB/MUL/NEG/POS by interval arithmetic;
# MOD by a divisor whose interval is positive [0, hi(divisor)-1]; FLOORDIV by a positive divisor
# interval by its endpoints; everything else (params, entry globals, other locals, Index, Unbox,
# Call) the full i64 range.

def _ranged(op, start=0, stop=5, step=1, impure=True, pure=False, may_deopt=False, params=()):
    """for k in range(start, stop, step): t = <op(k)>   in an (impure) function"""
    body = ([IMPURE] if impure else []) + [
        ForRange("k", i(start) if isinstance(start, int) else start,
                 i(stop) if isinstance(stop, int) else stop, i(step), (Assign("t", op(li("k"))),)),
        Return(),
    ]
    return fn(params=params, locals_={"k": I64, "t": I64}, pure=pure, may_deopt=may_deopt,
              body=body)


def test_proven_add_of_a_small_range_var_is_accepted_in_an_impure_function():
    assert_accepted(_ranged(lambda k: padd(k, i(1))))


def test_proven_add_of_two_i64_params_is_rejected():
    ps = [Param("a", I64), Param("b", I64)]
    assert_rejected(PROVEN, fn(params=ps, returns=I64, body=[IMPURE, Return(padd(li("a"), li("b")))]))
    assert_rejected(PROVEN, fn(params=ps, returns=I64, pure=True,
                               body=[Return(padd(li("a"), li("b")))]))


def test_unproven_i64_ops_still_deopt_in_an_impure_function():
    assert_rejected(DEOPT, _ranged(lambda k: add(k, i(1))))
    assert_rejected(DEOPT, _ranged(lambda k: UnaryOp(I64, UnaryOpKind.NEG, k)))


def test_a_proven_op_is_not_a_deopting_node():
    # pure function whose only i64 op is proven: it cannot deopt, so may_deopt must be False
    ok = _ranged(lambda k: padd(k, i(1)), impure=False, pure=True, may_deopt=False)
    assert_accepted(ok)
    assert_rejected(FLAG_DEOPT, dataclasses.replace(ok, may_deopt=True))


@pytest.mark.parametrize("op", [
    lambda k: psub(k, i(1)),
    lambda k: pmul(k, k),
    lambda k: pneg(k),
    lambda k: UnaryOp(I64, UnaryOpKind.NEG, k, proven=True),
    lambda k: padd(padd(k, i(1)), pmul(k, i(3))),          # proven ops nest
])
def test_other_provable_ops_are_accepted(op):
    assert_accepted(_ranged(op))


def test_proven_neg_of_a_param_is_rejected():
    # -(-2**63) overflows
    assert_rejected(PROVEN, fn(params=[Param("n", I64)], returns=I64, body=[IMPURE, Return(pneg(li("n")))]))


def test_proven_on_a_node_that_has_no_overflow_is_rejected():
    ps = [Param("x", F64), Param("n", I64)]
    assert_rejected(PROVEN, fn(params=ps, returns=F64,
                               body=[Return(BinOp(F64, BinOpKind.ADD, lf("x"), lf("x"), proven=True))]))
    assert_rejected(PROVEN, fn(params=ps, returns=I64, pure=True, may_deopt=True, body=[
        Return(BinOp(I64, BinOpKind.FLOORDIV, li("n"), i(2), proven=True))]))
    assert_rejected(PROVEN, fn(params=ps, returns=I64, body=[
        Return(UnaryOp(I64, UnaryOpKind.POS, li("n"), proven=True))]))


def test_a_non_bool_proven_flag_is_rejected():
    out, diags = run(_ranged(lambda k: BinOp(I64, BinOpKind.ADD, k, i(1), proven=1)))
    assert "f" not in kept(out)


def test_range_var_interval_uses_the_stop_bound():
    assert_accepted(_ranged(lambda k: padd(k, i(2 ** 63 - 5)), stop=5))         # k <= 4
    assert_rejected(PROVEN, _ranged(lambda k: padd(k, i(2 ** 63 - 4)), stop=5))  # 4 + (2**63-4)


def test_range_var_interval_uses_the_start_bound():
    # range(-5, 5): k >= -5
    assert_accepted(_ranged(lambda k: psub(k, i(2 ** 63 - 5)), start=-5))         # -5 - (2**63-5)
    assert_rejected(PROVEN, _ranged(lambda k: psub(k, i(2 ** 63 - 4)), start=-5))


def test_negative_step_bounds():
    # range(10, -1, -1): k in [0, 10]
    assert_accepted(_ranged(lambda k: psub(k, i(2 ** 63 - 1)), start=10, stop=-1, step=-1))
    assert_rejected(PROVEN, _ranged(lambda k: padd(k, i(2 ** 63 - 10)), start=10, stop=-1, step=-1))
    # range(n, 0, -1) with n a param: k in [1, 2**63-1]: k - 1 is provable, k + 1 is not
    ps = [Param("n", I64)]
    assert_accepted(_ranged(lambda k: psub(k, i(1)), start=li("n"), stop=0, step=-1, params=ps))
    assert_rejected(PROVEN, _ranged(lambda k: padd(k, i(1)), start=li("n"), stop=0, step=-1,
                                    params=ps))


def test_range_up_to_a_param_bounds_the_var_by_the_param_range():
    # range(0, n): k <= 2**63-2, so k + 1 fits but k + 2 may not
    ps = [Param("n", I64)]
    assert_accepted(_ranged(lambda k: padd(k, i(1)), stop=li("n"), params=ps))
    assert_rejected(PROVEN, _ranged(lambda k: padd(k, i(2)), stop=li("n"), params=ps))


def test_len_bound_is_zero_to_two_to_the_62():
    a = [ArrayParam("a", F64A, stored=False)]
    L = Len(I64, "a")
    assert_accepted(fn(params=a, returns=I64, body=[IMPURE, Return(padd(L, i(2 ** 62 - 1)))]))
    assert_rejected(PROVEN, fn(params=a, returns=I64,
                               body=[IMPURE, Return(padd(L, i(2 ** 62)))]))
    assert_rejected(PROVEN, fn(params=a, returns=I64, body=[IMPURE, Return(pmul(L, i(2)))]))
    assert_accepted(fn(params=a, returns=I64, body=[IMPURE, Return(pmul(L, i(-2)))]))  # -2**63
    assert_rejected(PROVEN, fn(params=a, returns=I64, body=[IMPURE, Return(pmul(L, i(-3)))]))


def test_mod_and_floordiv_are_full_range_so_nothing_is_provable_from_them():
    ps = [Param("n", I64)]
    m = BinOp(I64, BinOpKind.MOD, li("n"), i(7))
    assert_rejected(PROVEN, fn(params=ps, returns=I64, pure=True, may_deopt=True,
                               body=[Return(padd(m, i(1)))]))


def test_what_the_intervals_cannot_prove():
    # a local assigned a constant is still full range (locals other than loop vars are not tracked)
    assert_rejected(PROVEN, fn(locals_={"x": I64}, returns=I64,
                               body=[IMPURE, Assign("x", i(1)), Return(padd(li("x"), i(1)))]))
    # the loop var after its loop is full range
    assert_rejected(PROVEN, fn(locals_={"k": I64}, returns=I64, body=[
        IMPURE, Assign("k", i(0)), ForRange("k", i(0), i(5), i(1), ()), Return(padd(li("k"), i(1)))]))
    # an i64 array element is full range
    assert_rejected(PROVEN, fn(params=[ArrayParam("a", I64A, stored=False)], returns=I64,
                               body=[IMPURE, Return(padd(Index(I64, "a", i(0)), i(1)))]))


def test_proven_flag_survives_verification():
    out = assert_accepted(_ranged(lambda k: padd(k, i(1))))
    loop = out.functions[0].body[1]
    assert loop.body[0].value.proven is True


# --- 8. Call.redo ---------------------------------------------------------------------------------

def _callee(name="g", returns=F64, pure=True, may_deopt=True):
    """g(n: int) — deopts on n * 2 overflow when may_deopt; returns `returns`."""
    prod = BinOp(I64, BinOpKind.MUL, li("n"), i(2)) if may_deopt else li("n")
    value = {F64: ToFloat(F64, prod), I64: prod, BOOL: lt(prod, i(0)), NONE: None}[returns]
    body = ([ExprStmt(prod), Return()] if returns is NONE else [Return(value)])
    if not pure:
        body = [IMPURE] + body
    return fn(name, params=[Param("n", I64)], returns=returns, pure=pure, may_deopt=may_deopt,
              body=body)


def _redo_caller(returns=F64, pure=False, may_deopt=False, redo=True):
    call = Call(returns, "g", (i(3),), redo=redo)
    body = [ExprStmt(call), Return()]
    if not pure:
        body = [IMPURE] + body
    return fn("f", pure=pure, may_deopt=may_deopt, body=body)


@pytest.mark.parametrize("returns", [F64, BOOL, NONE])
def test_redo_from_an_impure_caller_to_a_pure_may_deopt_callee_is_accepted(returns):
    assert_accepted(_redo_caller(returns), _callee(returns=returns))


def test_redo_to_an_i64_returning_callee_is_rejected():
    out, _ = assert_rejected(REDO, _redo_caller(I64), _callee(returns=I64))
    assert "g" in kept(out)


def test_redo_in_a_pure_caller_is_rejected():
    assert_rejected(REDO, _redo_caller(pure=True, may_deopt=True), _callee())
    assert_rejected(REDO, _redo_caller(pure=True, may_deopt=False), _callee())


def test_redo_to_a_callee_that_cannot_deopt_is_rejected():
    assert_rejected(REDO, _redo_caller(), _callee(may_deopt=False))


def test_redo_to_an_impure_callee_is_rejected():
    assert_rejected(REDO, _redo_caller(), _callee(pure=False, may_deopt=False))


def test_redo_does_not_make_the_caller_may_deopt():
    assert_rejected(FLAG_DEOPT, _redo_caller(may_deopt=True), _callee())


def test_the_same_call_without_redo_is_a_deopt_in_an_impure_caller():
    assert_rejected(DEOPT, _redo_caller(redo=False), _callee())


def test_a_non_bool_redo_flag_is_rejected():
    out, _ = run(dataclasses.replace(_redo_caller(), body=(
        IMPURE, ExprStmt(Call(F64, "g", (i(3),), redo=1)), Return())), _callee())
    assert "f" not in kept(out)


# --- 9. Function.entry_globals --------------------------------------------------------------------

def _closed(entry=(Param("N", I64),), extra=(), returns=F64, name="f"):
    """A closed, impure function: for k in range(0, N): a[k] = a[k] * SCALE (SCALE an entry global
    when listed)."""
    a = ArrayParam("a", F64A, stored=True)
    return fn(name, params=[a], entry_globals=entry, locals_={"k": I64}, returns=returns, body=[
        *extra,
        ForRange("k", i(0), Len(I64, "a"), i(1), (
            StoreIndex("a", li("k"), BinOp(F64, BinOpKind.MUL, Index(F64, "a", li("k")),
                                            ToFloat(F64, li("N")))),)),
        Return(f(0.0)) if returns is F64 else Return(),
    ])


def test_entry_global_in_a_closed_function_is_accepted():
    assert_accepted(_closed())


def test_entry_globals_are_assigned_at_entry():
    assert_accepted(fn(entry_globals=[Param("G", F64), Param("B", BOOL)], returns=F64,
                       body=[If(lb("B"), (Return(lf("G")),)), Return(f(1.0))]))


def test_entry_global_in_a_pure_function_is_accepted():
    assert_accepted(fn(entry_globals=[Param("G", F64)], returns=F64, pure=True,
                       body=[Return(lf("G"))]))


@pytest.mark.parametrize("node", [
    ExprStmt(CallObject(OBJ, Global(OBJ, "print"), (), ())),
    ExprStmt(GetAttr(OBJ, Global(OBJ, "m"), "x")),
    If(Truth(BOOL, Global(OBJ, "m")), ()),
    If(CompareObj(BOOL, CompareKind.EQ, Global(OBJ, "m"), Box(OBJ, Const(I64, 1))), ()),
    ExprStmt(ObjToFloat(F64, Global(OBJ, "m"))),
    ExprStmt(BinOp(OBJ, BinOpKind.ADD, Global(OBJ, "m"), Global(OBJ, "m"))),
])
def test_entry_global_in_a_function_running_user_code_is_rejected(node):
    assert_rejected(ENTRY_OPEN, _closed(extra=[node]))


def test_the_same_open_node_without_entry_globals_is_accepted():
    # The open node alone is fine: only entry globals need the function closed.
    a = ArrayParam("a", F64A, stored=False)
    assert_accepted(fn(params=[a], returns=F64, body=[
        ExprStmt(CallObject(OBJ, Global(OBJ, "print"), (), ())), Return(Index(F64, "a", i(0)))]))


def test_global_read_and_box_keep_a_function_closed():
    assert_accepted(_closed(extra=[ExprStmt(Global(OBJ, "m")), ExprStmt(Box(OBJ, li("N")))]))


def test_entry_global_needs_every_callee_closed_transitively():
    h_open = fn("h", body=[IMPURE, Return()])
    h_closed = fn("h", body=[Return()])
    g = fn("g", body=[ExprStmt(Call(NONE, "h", ())), Return()])
    caller = _closed(extra=[ExprStmt(Call(NONE, "g", ()))])
    assert_accepted(caller, g, h_closed)
    out, _ = assert_rejected(ENTRY_OPEN, caller, g, h_open)
    assert "g" in kept(out) and "h" in kept(out)


def test_entry_global_must_be_a_scalar_param():
    assert_rejected(TYPE, _closed(entry=(Param("N", OBJ),)))
    assert_rejected(TYPE, fn(entry_globals=[Param("A", F64A)], body=[Return()]))
    assert_rejected(TYPE, fn(entry_globals=[Param("A", NONE)], body=[Return()]))
    out, _ = run(fn(entry_globals=[ArrayParam("A", F64A, stored=False)], body=[Return()]))
    assert "f" not in kept(out)
    out, _ = run(fn(entry_globals=["N"], body=[Return()]))
    assert "f" not in kept(out)


def test_entry_global_names_must_not_collide():
    assert_rejected(STRUCTURE, _closed(entry=(Param("a", I64),)))                 # an array param
    assert_rejected(STRUCTURE, fn(params=[Param("N", I64)], entry_globals=[Param("N", I64)],
                                  body=[Return()]))
    assert_rejected(STRUCTURE, fn(locals_={"N": I64}, entry_globals=[Param("N", I64)],
                                  body=[Return()]))
    assert_rejected(STRUCTURE, fn(entry_globals=[Param("N", I64), Param("N", I64)],
                                  body=[Return()]))


def test_entry_global_is_read_as_a_local_of_its_type():
    assert_rejected(TYPE, fn(entry_globals=[Param("G", F64)], returns=I64,
                             body=[Return(li("G"))]))


def test_assigning_an_entry_global_is_rejected():
    assert_rejected(STRUCTURE, fn(entry_globals=[Param("G", F64)],
                                  body=[Assign("G", f(1.0)), Return()]))
    assert_rejected(STRUCTURE, fn(entry_globals=[Param("N", I64)], body=[
        ForRange("N", i(0), i(3), i(1), ()), Return()]))


def test_entry_global_i64_is_full_range():
    assert_rejected(PROVEN, _closed(extra=[ExprStmt(padd(li("N"), i(1)))]))


def test_call_of_a_function_with_entry_globals_from_an_impure_caller():
    # The callee's entry guard can fail in the middle of the caller: that is a deopt there,
    # unless the caller already guarded the same global (and, being closed, cannot rebind it).
    g = fn("g", entry_globals=[Param("G", F64)], returns=F64, pure=True, body=[Return(lf("G"))])
    impure = fn("f", returns=F64, body=[IMPURE, Return(Call(F64, "g", ()))])
    assert_rejected(DEOPT, impure, g)
    covered = fn("f", params=[ArrayParam("a", F64A, stored=True)],
                 entry_globals=[Param("G", F64)], returns=F64,
                 body=[StoreIndex("a", i(0), Call(F64, "g", ())), Return(lf("G"))])
    assert_accepted(covered, g)
    wrong_type = dataclasses.replace(covered, entry_globals=(Param("G", I64),),
                                     body=(StoreIndex("a", i(0), Call(F64, "g", ())),
                                           Return(f(0.0))))
    assert_rejected(DEOPT, wrong_type, g)
    assert_accepted(fn("f", returns=F64, pure=True, may_deopt=True,
                       body=[Return(Call(F64, "g", ()))]), g)


# --- "nothing slips through": seeded IR mutation -------------------------------------------------

def _base_functions():
    """Two correct functions exercising every node kind; the mutator breaks them."""
    pure = fn("dot", params=[ArrayParam("a", F64A, stored=False),
                             ArrayParam("w", F64A, stored=False), Param("n", I64)],
              locals_={"s": F64, "k": I64, "c": I64, "t": OBJ}, returns=F64, pure=True,
              may_deopt=True, line=3, body=[
                  Assign("s", f(0.0)),
                  Assign("c", i(0)),
                  Assign("t", g_("TOLERANCE")),
                  ForRange("k", i(0), Len(I64, "a"), i(1), (
                      If(And(BOOL, lt(li("k"), li("n")), lt(Index(F64, "a", li("k")), f(1e9))),
                         (Assign("s", add(lf("s"), BinOp(F64, BinOpKind.MUL,
                                                         Index(F64, "a", li("k")),
                                                         Index(F64, "w", i(0))), F64)),),
                         (Continue(),)),
                      Assign("c", add(li("c"), i(1))),
                  )),
                  Return(add(lf("s"), MathCall(F64, MathFunc.SQRT, (ToFloat(F64, li("c")),)),
                             F64)),
              ])
    impure = fn("scale", params=[ArrayParam("a", F64A, stored=True), Param("x", F64),
                                 Param("o", OBJ), Param("c", BOOL)],
                locals_={"k": I64, "r": OBJ, "y": F64}, returns=F64, line=20, body=[
                    Assign("r", call_obj(GetAttr(OBJ, g_("Modifier"), "padding"),
                                         Box(OBJ, lf("x")), lo("o"), kw=("dp",))),
                    Assign("y", Unbox(F64, lo("r"))),
                    If(Truth(BOOL, lo("r")), (Assign("y", ObjToFloat(F64, lo("o"))),)),
                    ForRange("k", i(0), Len(I64, "a"), i(1), (
                        StoreIndex("a", li("k"), BinOp(F64, BinOpKind.MUL,
                                                       Index(F64, "a", li("k")), lf("y"))),
                    )),
                    While(And(BOOL, Compare(BOOL, CompareKind.GT, lf("y"), f(1.0)),
                              CompareObj(BOOL, CompareKind.NE, lo("o"), lo("r"))), (
                        Assign("y", BinOp(F64, BinOpKind.TRUEDIV, lf("y"), f(2.0))),
                        Assign("r", BinOp(OBJ, BinOpKind.ADD, lo("r"), lo("o"))),
                        If(UnaryOp(BOOL, UnaryOpKind.NOT, lt(lf("y"), f(100.0))), (Break(),)),
                    )),
                    Return(lf("y")),
                ])
    return pure, impure


def _paths(node, pred, path=()):
    """Every (path, node) below `node` whose node satisfies pred. A path is a tuple of
    field names / tuple indices."""
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


def _replace(node, path, new):
    if not path:
        return new
    head, rest = path[0], path[1:]
    if isinstance(node, tuple):
        return node[:head] + (_replace(node[head], rest, new),) + node[head + 1:]
    return dataclasses.replace(node, **{head: _replace(getattr(node, head), rest, new)})


def _is_expr(n): return isinstance(n, ir.Expr)


def _replace_body(g, path, new):
    return dataclasses.replace(g, body=_replace(g.body, path, new))


def _breaking_mutations(rng, g):
    """Return (description, mutant, rules): the mutant breaks a property by construction; rules
    is the set of rule ids any one of which is an acceptable report."""
    exprs = _paths(g.body, _is_expr)
    path, e = rng.choice(exprs)
    kind = rng.randrange(14)
    if kind == 0:   # read of an undeclared/unassigned local of the right type
        return "unassigned read", _replace_body(g, path, Local(e.type, "zz_unset")), {
            DA, TYPE, STRUCTURE}
    if kind == 1:   # change the declared type of a node
        other = rng.choice([t for t in (I64, F64, BOOL, OBJ) if t is not e.type])
        return "retype", _replace_body(g, path, dataclasses.replace(e, type=other)), {
            TYPE, OWNERSHIP, STRUCTURE}
    if kind == 2:   # substitute an unknown node
        return "unknown", _replace_body(g, path, Weird(e.type)), {UNKNOWN, STRUCTURE}
    if kind == 3:   # flip pure
        return "flip pure", dataclasses.replace(g, pure=not g.pure), {FLAG_PURE, DEOPT, FLAG_DEOPT}
    if kind == 4:   # flip may_deopt
        return "flip may_deopt", dataclasses.replace(g, may_deopt=not g.may_deopt), {
            FLAG_DEOPT, DEOPT}
    if kind == 5:   # flip stored on the first array
        k = next(j for j, p in enumerate(g.params) if isinstance(p, ArrayParam))
        p = g.params[k]
        ps = g.params[:k] + (dataclasses.replace(p, stored=not p.stored),) + g.params[k + 1:]
        return "flip stored", dataclasses.replace(g, params=ps), {ARRAY_STORED, FLAG_PURE}
    if kind == 6:   # change the return type
        other = rng.choice([t for t in (I64, F64, BOOL, OBJ, NONE) if t is not g.returns])
        return "returns", dataclasses.replace(g, returns=other), {TYPE, OWNERSHIP, MISSING_RETURN}
    if kind == 7:   # drop a declared local
        name = rng.choice(sorted(g.locals))
        loc = {k: v for k, v in g.locals.items() if k != name}
        return "drop local", dataclasses.replace(g, locals=loc), {TYPE, STRUCTURE}
    if kind == 8:   # call an unknown function
        return "unknown call", _replace_body(g, path, Call(e.type, "nowhere", ())), {
            UNRESOLVED, STRUCTURE}
    if kind == 9:   # a store into the pure function, or an I64 add into the impure one
        if g.pure:
            st = StoreIndex(next(p.name for p in g.params if isinstance(p, ArrayParam)), i(0),
                            f(0.0))
            return "store in pure", dataclasses.replace(g, body=(st,) + g.body), {
                FLAG_PURE, ARRAY_STORED}
        st = ExprStmt(add(i(1), i(2)))
        return "deopt in impure", dataclasses.replace(g, body=(st,) + g.body), {DEOPT}
    if kind == 10:  # a Break/Continue at function level
        return "stray break", dataclasses.replace(
            g, body=(rng.choice([Break(), Continue()]),) + g.body), {STRUCTURE}
    if kind == 11:  # an effecting object node into the pure function / a value-position CompareObj
        if g.pure:
            node = rng.choice([GetAttr(OBJ, lo("t"), "v"), call_obj(lo("t")), Truth(BOOL, lo("t")),
                               ObjToFloat(F64, lo("t")), BinOp(OBJ, BinOpKind.ADD, lo("t"),
                                                                lo("t"))])
            return "effect in pure", dataclasses.replace(
                g, body=g.body[:3] + (ExprStmt(node),) + g.body[3:]), {FLAG_PURE}
        cmp = CompareObj(BOOL, CompareKind.EQ, lo("o"), lo("o"))
        return "compare_obj value", dataclasses.replace(
            g, locals={**g.locals, "q": BOOL}, body=(Assign("q", cmp),) + g.body), {CONDITION_ONLY}
    if kind == 12:  # a scalar into an OBJ slot (unbox removed / box removed)
        boxes = _paths(g.body, lambda n: type(n) in (Box, Unbox, ObjToFloat))
        if boxes:
            bpath, bx = rng.choice(boxes)
            return "drop box", _replace_body(g, bpath, bx.operand), {OWNERSHIP, TYPE}
        return "drop box", _replace_body(g, path, Box(OBJ, e)) if e.type in (I64, F64, BOOL) \
            else _replace_body(g, path, Unbox(F64, e)), {OWNERSHIP, TYPE, STRUCTURE}
    # kind 13: drop the first Assign (its local becomes possibly unassigned) — breaking because the
    # local is read later, which holds for the first Assign of both base functions.
    k = next(j for j, s in enumerate(g.body) if isinstance(s, Assign))
    return "drop assign", dataclasses.replace(g, body=g.body[:k] + g.body[k + 1:]), {DA}


def _preserving_mutations(rng, g):
    """Rewrites that keep every property; the verifier must still accept them."""
    kind = rng.randrange(4)
    if kind == 0:   # wrap the body in `if True:` on both branches
        return "if-true wrap", dataclasses.replace(g, body=(If(b(True), g.body, g.body),))
    if kind == 1:   # an extra declared, assigned, unused local
        return "dead local", dataclasses.replace(
            g, locals={**g.locals, "zz": F64}, body=(Assign("zz", f(0.5)),) + g.body)
    if kind == 2:   # a dead statement after the return
        return "dead tail", dataclasses.replace(g, body=g.body + (ExprStmt(f(1.0)),))
    # swap the operands of a Compare / CompareObj (types stay valid)
    cmps = _paths(g.body, lambda n: type(n) in (Compare, CompareObj))
    path, c = rng.choice(cmps)
    flipped = {CompareKind.LT: CompareKind.GT, CompareKind.GT: CompareKind.LT}.get(c.op, c.op)
    return "flip compare", _replace_body(g, path, type(c)(BOOL, flipped, c.right, c.left))


def test_base_functions_are_accepted():
    out = assert_accepted(*_base_functions())
    dot, scale = out.functions
    assert [a.proven for a in _accesses(dot)] == [True, True, False]
    assert [a.proven for a in _accesses(scale)] == [True, True]


@pytest.mark.parametrize("seed", [20261003])
def test_no_breaking_mutation_slips_through(seed):
    rng = random.Random(seed)
    base = _base_functions()
    missed, seen = [], set()
    for n in range(200):
        g = base[rng.randrange(2)]
        desc, mutant, allowed = _breaking_mutations(rng, g)
        seen.add(desc)
        others = [h for h in base if h.name != g.name]
        out, diags = run(*others, mutant)
        got = rules_for(diags, g.name)
        if g.name in kept(out) or not (got & allowed):
            missed.append((n, desc, g.name, sorted(got)))
    assert missed == []
    assert len(seen) >= 12      # the seed exercised most mutation kinds


@pytest.mark.parametrize("seed", [7])
def test_preserving_rewrites_are_accepted(seed):
    rng = random.Random(seed)
    base = _base_functions()
    rejected = []
    for n in range(100):
        g = base[rng.randrange(2)]
        desc, mutant = _preserving_mutations(rng, g)
        out, diags = run(*[h for h in base if h.name != g.name], mutant)
        if g.name not in kept(out):
            rejected.append((n, desc, [dataclasses.astuple(d) for d in diags]))
    assert rejected == []


# --- mutation of proven / redo / entry_globals ---------------------------------------------------

def _proof_functions():
    """Correct functions exercising proven ops, redo and entry globals; the mutator breaks them."""
    h = fn("h", params=[Param("n", I64)], returns=F64, pure=True, may_deopt=True, line=1,
           body=[Return(ToFloat(F64, BinOp(I64, BinOpKind.MUL, li("n"), i(2))))])
    q = fn("q", params=[Param("n", I64)], returns=I64, pure=True, may_deopt=True, line=2,
           body=[Return(add(li("n"), i(1)))])
    pc = fn("pc", returns=F64, pure=True, may_deopt=True, line=3,
            body=[Return(Call(F64, "h", (i(3),)))])
    fill = fn("fill", params=[ArrayParam("a", F64A, stored=True)],
              entry_globals=[Param("N", I64), Param("S", F64)],
              locals_={"k": I64, "t": I64, "v": I64, "u": F64}, returns=F64, line=4, body=[
                  ForRange("k", i(0), Len(I64, "a"), i(1), (
                      Assign("t", psub(pmul(li("k"), i(2)), i(1))),
                      Assign("v", pneg(padd(li("k"), i(1)))),
                      StoreIndex("a", li("k"), BinOp(F64, BinOpKind.MUL, Index(F64, "a", li("k")),
                                                     ToFloat(F64, li("t")))),
                  )),
                  Assign("u", Call(F64, "h", (i(3),), redo=True)),
                  Return(BinOp(F64, BinOpKind.ADD, BinOp(F64, BinOpKind.ADD, lf("u"), lf("S")),
                               ToFloat(F64, li("N")))),
              ])
    talk = fn("talk", returns=NONE, line=5, body=[IMPURE, Return()])
    return {x.name: x for x in (h, q, pc, fill, talk)}


def _proof_mutations(rng, base):
    """(description, mutated functions by name, the function that must be rejected, the rules any
    one of which is an acceptable report)."""
    b = dict(base)
    kind = rng.randrange(14)
    if kind == 0:       # widen a proven op's operand to a full-range entry global
        paths = _paths(b["fill"].body, lambda n: type(n) is BinOp and n.proven)
        path, node = rng.choice(paths)
        wide = BinOp(I64, rng.choice([BinOpKind.ADD, BinOpKind.MUL]), node.left, li("N"),
                     proven=True)
        b["fill"] = _replace_body(b["fill"], path, wide)
        return "widen proven", b, "fill", {PROVEN}
    if kind == 1:       # proven set on an op that can overflow
        b["q"] = _replace_body(b["q"], (0, "value"),
                               BinOp(I64, BinOpKind.ADD, li("n"), i(1), proven=True))
        return "proven on param add", b, "q", {PROVEN}
    if kind == 2:       # proven set on a node that has no i64 overflow
        node = rng.choice([BinOp(F64, BinOpKind.ADD, f(1.0), f(2.0), proven=True),
                           BinOp(I64, BinOpKind.FLOORDIV, i(4), i(2), proven=True),
                           UnaryOp(I64, UnaryOpKind.POS, i(1), proven=True),
                           BinOp(I64, BinOpKind.ADD, i(1), i(2), proven=1)])
        t = Type.F64 if node.type is F64 else Type.I64
        b["q"] = dataclasses.replace(b["q"], returns=t, may_deopt=node.type is I64 and
                                     getattr(node, "op", None) is BinOpKind.FLOORDIV,
                                     body=(Return(node),))
        return "proven on wrong node", b, "q", {PROVEN, TYPE}
    if kind == 3:       # un-prove a proven op in the impure function: it deopts
        paths = _paths(b["fill"].body, lambda n: type(n) in (BinOp, UnaryOp) and n.proven)
        path, node = rng.choice(paths)
        b["fill"] = _replace_body(b["fill"], path, dataclasses.replace(node, proven=False))
        return "unprove", b, "fill", {DEOPT}
    if kind == 4:       # un-redo
        paths = _paths(b["fill"].body, lambda n: type(n) is Call)
        path, node = paths[0]
        b["fill"] = _replace_body(b["fill"], path, dataclasses.replace(node, redo=False))
        return "drop redo", b, "fill", {DEOPT}
    if kind == 5:       # redo from a pure caller
        b["pc"] = dataclasses.replace(b["pc"], body=(Return(Call(F64, "h", (i(3),), redo=True)),))
        return "redo in pure", b, "pc", {REDO}
    if kind == 6:       # the callee stops being may_deopt / pure / F64-or-BOOL-or-NONE
        how = rng.randrange(3)
        h = b["h"]
        if how == 0:
            b["h"] = dataclasses.replace(h, may_deopt=False)
            return "redo callee not may_deopt", b, "fill", {REDO}
        if how == 1:
            b["h"] = dataclasses.replace(h, pure=False, may_deopt=False, body=(IMPURE,) + (
                Return(ToFloat(F64, li("n"))),))
            return "redo callee impure", b, "fill", {REDO}
        b["h"] = dataclasses.replace(h, returns=I64, body=(Return(BinOp(
            I64, BinOpKind.MUL, li("n"), i(2))),))
        b["fill"] = _replace_body(b["fill"], (1, "value"), Call(I64, "h", (i(3),),
                                                                        redo=True))
        return "redo to i64", b, "fill", {REDO, TYPE}
    if kind == 7:       # an open node in the function with entry globals
        node = rng.choice([GetAttr(OBJ, g_("m"), "x"), pycall("f"), Truth(BOOL, g_("m")),
                           ObjToFloat(F64, g_("m")), BinOp(OBJ, BinOpKind.ADD, g_("m"), g_("m")),
                           CompareObj(BOOL, CompareKind.EQ, g_("m"), g_("m"))])
        node = ExprStmt(node) if type(node) is not CompareObj else If(node, ())
        b["fill"] = dataclasses.replace(b["fill"], body=(node,) + b["fill"].body)
        return "open node", b, "fill", {ENTRY_OPEN}
    if kind == 8:       # entry globals on a function that is open
        b["talk"] = dataclasses.replace(b["talk"], entry_globals=(Param("N", I64),))
        return "entry global on open", b, "talk", {ENTRY_OPEN}
    if kind == 9:       # a callee that runs user code
        b["h"] = dataclasses.replace(b["h"], pure=False, may_deopt=False, body=(IMPURE,) + (
            Return(ToFloat(F64, li("n"))),))
        return "open callee", b, "fill", {ENTRY_OPEN}
    if kind == 10:      # entry global removed while the body reads it
        keep = rng.choice([("N",), ("S",), ()])
        eg = tuple(p for p in b["fill"].entry_globals if p.name in keep)
        b["fill"] = dataclasses.replace(b["fill"], entry_globals=eg)
        return "drop entry global", b, "fill", {TYPE}
    if kind == 11:      # a bad entry global: wrong type / collision / duplicate / array
        bad = rng.choice([(Param("N", OBJ), TYPE), (Param("a", I64), STRUCTURE),
                          (Param("k", I64), STRUCTURE), (Param("N", I64), STRUCTURE),
                          (Param("W", F64A), TYPE), (Param("W", NONE), TYPE)])
        b["fill"] = dataclasses.replace(b["fill"], entry_globals=b["fill"].entry_globals + (bad[0],))
        return "bad entry global", b, "fill", {bad[1]}
    if kind == 12:      # assign an entry global
        tgt = rng.choice(["N", "S"])
        v = i(1) if tgt == "N" else f(1.0)
        b["fill"] = dataclasses.replace(b["fill"], body=(Assign(tgt, v),) + b["fill"].body)
        return "assign entry global", b, "fill", {STRUCTURE}
    # kind 13: a function with entry globals called, unguarded, from an impure caller
    caller = fn("fill2", params=[ArrayParam("a", F64A, stored=True)], returns=F64, line=6,
                body=[StoreIndex("a", i(0), f(1.0)), Return(Call(F64, "pc2", ()))])
    callee = fn("pc2", entry_globals=[Param("S", F64)], returns=F64, pure=True, line=7,
                body=[Return(lf("S"))])
    b["fill2"], b["pc2"] = caller, callee
    return "unguarded entry-global call", b, "fill2", {DEOPT}


def test_proof_base_functions_are_accepted():
    assert_accepted(*_proof_functions().values())


@pytest.mark.parametrize("seed", [20261004])
def test_no_proof_breaking_mutation_slips_through(seed):
    rng = random.Random(seed)
    base = _proof_functions()
    missed, seen = [], set()
    for n in range(300):
        desc, funcs, target, allowed = _proof_mutations(rng, base)
        seen.add(desc)
        out, diags = run(*funcs.values())
        got = rules_for(diags, target)
        if target in kept(out) or not (got & allowed):
            missed.append((n, desc, target, sorted(got)))
    assert missed == []


def test_each_proof_mutation_kind_was_exercised():
    rng = random.Random(20261004)
    base = _proof_functions()
    kinds = {_proof_mutations(rng, base)[0] for _ in range(300)}
    assert len(kinds) == 16, sorted(kinds)     # 14 kinds; kind 6 has three variants


# --- 10. local arrays and tuples (NewArray / CopyArray / Tuple; issue #41, N-8) -------------------

def na(t, n, fill=None, iota=False): return NewArray(t, n, fill, iota)
def la(name, t=F64A): return Local(t, name)
def tup(*elements): return Tuple(OBJ, tuple(elements))


def _local_sum(body=None, pure=True, may_deopt=False, params=(), locals_=None, returns=F64):
    """t = [0.0] * 8; for k in range(len(t)): t[k] = 1.0; s += t[k]; return s"""
    body = body if body is not None else [
        Assign("t", na(F64A, i(8), f(0.0))),
        Assign("s", f(0.0)),
        ForRange("k", i(0), Len(I64, "t"), i(1), (
            StoreIndex("t", li("k"), f(1.0)),
            Assign("s", add(lf("s"), Index(F64, "t", li("k")), F64)),
        )),
        Return(lf("s")),
    ]
    return fn(params=params, locals_={"t": F64A, "s": F64, "k": I64, **(locals_ or {})},
              returns=returns, pure=pure, may_deopt=may_deopt, body=body)


def _flags(function):
    out = assert_accepted(function)
    return [a.proven for a in _accesses(out.functions[0])]


# 10.1 what a local array may be and where an allocation may stand

def test_a_pure_function_with_a_local_array_is_accepted():
    assert_accepted(_local_sum())


def test_storing_into_a_local_array_is_not_an_effect():
    assert_accepted(_local_sum(pure=True))
    assert_accepted(_local_sum(pure=False))             # a conservative flag is also fine


def test_storing_into_an_array_param_still_makes_a_function_impure():
    g = _local_sum(params=[ArrayParam("a", F64A, stored=True)], body=[
        Assign("t", na(F64A, i(8), f(0.0))),
        StoreIndex("t", i(0), f(1.0)),
        StoreIndex("a", i(0), f(1.0)),
        Return(f(0.0)),
    ])
    assert_rejected(FLAG_PURE, g)
    assert_accepted(dataclasses.replace(g, pure=False))


def test_reading_a_local_array_does_not_make_the_stored_flag_wrong():
    assert_accepted(_local_sum(params=[ArrayParam("a", F64A, stored=False)]))


def test_may_deopt_flag_is_recomputed_with_local_arrays():
    deopting = _local_sum(body=[
        Assign("t", na(I64A, i(4), i(0))),
        Assign("x", add(Index(I64, "t", i(0)), i(1))),
        Return(ToFloat(F64, li("x"))),
    ], locals_={"t": I64A, "x": I64}, may_deopt=True)
    assert_accepted(deopting)
    assert_rejected(FLAG_DEOPT, dataclasses.replace(deopting, may_deopt=False))
    assert_rejected(FLAG_DEOPT, dataclasses.replace(_local_sum(), may_deopt=True))
    assert_rejected(DEOPT, dataclasses.replace(deopting, pure=False))


def test_each_allocation_form_is_accepted():
    for t, alloc, deopts in [(F64A, na(F64A, i(3), f(1.5)), False),
                             (I64A, na(I64A, i(3), i(7)), False),
                             (I64A, na(I64A, li("n"), None, True), False),
                             (F64A, na(F64A, add(li("n"), i(1)), f(0.0)), True)]:
        assert_accepted(fn(params=[Param("n", I64)], locals_={"t": t}, returns=NONE, pure=True,
                           may_deopt=deopts, body=[Assign("t", alloc), Return()]))


@pytest.mark.parametrize("place", ["expr-stmt", "return", "call-arg", "box", "tuple", "fill",
                                   "length", "condition-free-assign"])
def test_an_allocation_anywhere_but_an_assign_to_a_local_array_is_rejected(place):
    alloc = na(F64A, i(2), f(0.0))
    stmt = {
        "expr-stmt": ExprStmt(alloc),
        "return": Return(alloc),
        "call-arg": ExprStmt(Call(NONE, "g", (alloc,))),
        "box": Assign("o", Box(OBJ, alloc)),
        "tuple": Assign("o", tup(alloc)),
        "fill": Assign("t", na(F64A, i(2), alloc)),
        "length": Assign("t", na(F64A, alloc, f(0.0))),
        "condition-free-assign": Assign("o", alloc),
    }[place]
    g = fn(locals_={"t": F64A, "o": OBJ}, returns=NONE, body=[stmt, Return()] if place != "return"
           else [stmt])
    out, diags = run(g, fn("g", params=[Param("x", OBJ)], returns=NONE, body=[Return()]))
    assert "f" not in kept(out)
    assert ESCAPE in rules_for(diags, "f"), [dataclasses.astuple(d) for d in diags]


def test_a_copy_anywhere_but_an_assign_to_a_local_array_is_rejected():
    for stmt in [ExprStmt(CopyArray(F64A, "a")), Return(CopyArray(F64A, "a")),
                 Assign("o", CopyArray(F64A, "a")), Assign("x", CopyArray(F64A, "a"))]:
        g = fn(params=[ArrayParam("a", F64A, stored=False)], locals_={"o": OBJ, "x": F64},
               returns=NONE, body=[stmt, Return()])
        out, diags = run(g)
        assert "f" not in kept(out)
        assert ESCAPE in rules_for(diags, "f"), (stmt, [dataclasses.astuple(d) for d in diags])


def test_an_allocation_into_an_array_param_is_rejected():
    assert_rejected(ARRAY_USE, fn(params=[ArrayParam("a", F64A, stored=True)], returns=NONE, body=[
        Assign("a", na(F64A, i(2), f(0.0))), Return()]))


def test_the_allocation_type_must_match_the_declared_local():
    assert_rejected(TYPE, fn(locals_={"t": I64A}, returns=NONE, body=[
        Assign("t", na(F64A, i(2), f(0.0))), Return()]))
    assert_rejected(TYPE, fn(locals_={"t": F64A}, returns=NONE, body=[
        Assign("t", CopyArray(I64A, "a")), Return()],
        params=[ArrayParam("a", I64A, stored=False)]))


def test_the_node_type_must_be_an_array_type():
    assert_rejected(TYPE, fn(locals_={"t": F64A}, returns=NONE, body=[
        Assign("t", NewArray(I64, i(2), i(0))), Return()]))


def test_a_local_array_may_only_be_assigned_an_allocation():
    assert_rejected(TYPE, fn(locals_={"t": F64A}, returns=NONE, body=[
        Assign("t", f(1.0)), Return()]))
    assert_rejected(TYPE, fn(locals_={"t": F64A}, returns=NONE, body=[
        Assign("t", Const(NONE, None)), Return()]))


def test_new_array_length_must_be_i64():
    assert_rejected(TYPE, fn(locals_={"t": F64A}, returns=NONE, body=[
        Assign("t", na(F64A, f(2.0), f(0.0))), Return()]))
    assert_rejected(OWNERSHIP, fn(locals_={"t": F64A}, returns=NONE, body=[
        Assign("t", na(F64A, g_("N"), f(0.0))), Return()]))


def test_new_array_fill_must_match_the_element_type():
    assert_rejected(TYPE, fn(locals_={"t": F64A}, returns=NONE, body=[
        Assign("t", na(F64A, i(2), i(0))), Return()]))
    assert_rejected(TYPE, fn(locals_={"t": I64A}, returns=NONE, body=[
        Assign("t", na(I64A, i(2), f(0.0))), Return()]))
    assert_rejected(OWNERSHIP, fn(locals_={"t": I64A}, returns=NONE, body=[
        Assign("t", na(I64A, i(2), g_("zero"))), Return()]))


def test_new_array_fill_is_absent_exactly_when_iota():
    assert_rejected(TYPE, fn(locals_={"t": I64A}, returns=NONE, body=[
        Assign("t", na(I64A, i(2))), Return()]))
    assert_rejected(TYPE, fn(locals_={"t": I64A}, returns=NONE, body=[
        Assign("t", na(I64A, i(2), i(0), True)), Return()]))


def test_iota_is_only_for_i64_arrays_and_must_be_a_bool():
    assert_rejected(TYPE, fn(locals_={"t": F64A}, returns=NONE, body=[
        Assign("t", na(F64A, i(2), None, True)), Return()]))
    assert_rejected(TYPE, fn(locals_={"t": I64A}, returns=NONE, body=[
        Assign("t", NewArray(I64A, i(2), None, 1)), Return()]))


def test_copy_array_source_must_be_an_array_of_the_same_type():
    ok = fn(params=[ArrayParam("a", F64A, stored=False)], locals_={"t": F64A}, returns=NONE,
            pure=True, body=[Assign("t", CopyArray(F64A, "a")), Return()])
    assert_accepted(ok)
    assert_rejected(ARRAY_USE, dataclasses.replace(ok, params=(Param("a", F64),)))
    assert_rejected(ARRAY_USE, fn(locals_={"t": F64A, "x": F64}, returns=NONE, body=[
        Assign("x", f(1.0)), Assign("t", CopyArray(F64A, "x")), Return()]))
    assert_rejected(ARRAY_USE, fn(locals_={"t": F64A}, returns=NONE, body=[
        Assign("t", CopyArray(F64A, "nowhere")), Return()]))
    assert_rejected(TYPE, dataclasses.replace(
        ok, params=(ArrayParam("a", I64A, stored=False),)))


def test_copy_of_a_local_array_is_accepted():
    assert_accepted(fn(locals_={"t": I64A, "u": I64A}, returns=NONE, pure=True, body=[
        Assign("t", na(I64A, i(4), None, True)), Assign("u", CopyArray(I64A, "t")), Return()]))


def test_local_array_declarations_are_checked():
    assert_rejected(TYPE, fn(params=[Param("t", I64)], locals_={"t": I64A}, returns=NONE,
                             body=[Return()]))
    assert_rejected(ARRAY_USE, fn(params=[ArrayParam("t", I64A, stored=False)],
                                  locals_={"t": I64A}, returns=NONE, body=[Return()]))
    assert_rejected(STRUCTURE, fn(entry_globals=[Param("t", I64)], locals_={"t": I64A},
                                  returns=NONE, body=[Return()]))
    assert_rejected(TYPE, fn(locals_={"t": Type.NONE}, returns=NONE, body=[Return()]))


# 10.2 no escape

def _escapes(use, ret=NONE):
    """f(): t = [0] * 2; <use>; with t an i64 local array; g takes an OBJ."""
    t = la("t", I64A)
    stmt = use(t)
    g = fn(locals_={"t": I64A, "o": OBJ, "x": I64, "a": F64A, "u": I64A}, returns=ret, body=[
        Assign("t", na(I64A, i(2), i(0))), stmt] + ([Return()] if ret is NONE else []))
    out, diags = run(g, fn("g", params=[Param("x", OBJ)], returns=NONE, body=[Return()]))
    assert "f" not in kept(out)
    assert ESCAPE in rules_for(diags, "f"), [dataclasses.astuple(d) for d in diags]


@pytest.mark.parametrize("use", [
    lambda t: ExprStmt(t),
    lambda t: ExprStmt(Call(NONE, "g", (t,))),
    lambda t: ExprStmt(call_obj(g_("h"), t)),
    lambda t: ExprStmt(call_obj(t)),
    lambda t: Assign("o", Box(OBJ, t)),
    lambda t: Assign("o", tup(t)),
    lambda t: Assign("o", tup(Box(OBJ, i(1)), t)),
    lambda t: Assign("u", t),
    lambda t: Assign("o", t),
    lambda t: Assign("x", Unbox(I64, t)),
    lambda t: Assign("o", GetAttr(OBJ, t, "x")),
    lambda t: If(Truth(BOOL, t), ()),
    lambda t: If(CompareObj(BOOL, CompareKind.EQ, t, lo("o")), ()),
])
def test_a_local_array_used_as_a_value_is_an_escape(use):
    _escapes(use)


def test_returning_a_local_array_is_an_escape():
    for returns in (OBJ, NONE):
        g = fn(locals_={"t": I64A}, returns=returns, body=[
            Assign("t", na(I64A, i(2), i(0))), Return(la("t", I64A))])
        out, diags = run(g)
        assert "f" not in kept(out)
        assert ESCAPE in rules_for(diags, "f")


def test_a_local_array_is_not_a_loop_variable_or_an_array_param_argument():
    assert_rejected(TYPE, fn(locals_={"t": I64A}, returns=NONE, body=[
        Assign("t", na(I64A, i(2), i(0))),
        ForRange("t", i(0), i(2), i(1), ()), Return()]))
    callee = fn("g", params=[ArrayParam("a", I64A, stored=False)], returns=NONE, body=[Return()])
    caller = fn(locals_={"t": I64A}, returns=NONE, body=[
        Assign("t", na(I64A, i(2), i(0))), ExprStmt(Call(NONE, "g", (la("t", I64A),))),
        Return()])
    out, diags = run(callee, caller)
    assert "f" not in kept(out) and ESCAPE in rules_for(diags, "f")


def test_index_store_len_and_copy_source_are_the_only_uses_and_are_accepted():
    assert_accepted(fn(locals_={"t": I64A, "u": I64A, "x": I64}, returns=I64, pure=True,
                       may_deopt=True, body=[
        Assign("t", na(I64A, i(4), i(0))),
        StoreIndex("t", i(1), i(5)),
        Assign("u", CopyArray(I64A, "t")),
        Assign("x", add(Index(I64, "u", i(1)), Len(I64, "t"))),
        Return(li("x")),
    ]))


def test_index_and_store_name_a_declared_array():
    assert_rejected(ARRAY_USE, fn(locals_={"x": I64}, returns=I64, body=[
        Assign("x", Index(I64, "nowhere", i(0))), Return(li("x"))]))
    assert_rejected(ARRAY_USE, fn(locals_={"x": I64}, returns=NONE, body=[
        Assign("x", i(1)), StoreIndex("x", i(0), i(1)), Return()]))


def test_store_value_must_match_the_local_element_type():
    assert_rejected(TYPE, fn(locals_={"t": F64A}, returns=NONE, body=[
        Assign("t", na(F64A, i(2), f(0.0))), StoreIndex("t", i(0), i(1)), Return()]))
    assert_rejected(TYPE, fn(locals_={"t": F64A}, returns=NONE, body=[
        Assign("t", na(F64A, i(2), f(0.0))), StoreIndex("t", f(0.0), f(1.0)), Return()]))


# 10.3 bounds

def test_loop_over_len_of_a_local_array_is_proven():
    assert _flags(_local_sum()) == [True, True]


def test_loop_over_len_of_a_local_array_of_unknown_length_is_proven():
    g = _local_sum(params=[Param("n", I64)], body=[
        Assign("t", na(F64A, li("n"), f(0.0))),
        ForRange("k", i(0), Len(I64, "t"), i(1), (StoreIndex("t", li("k"), f(1.0)),)),
        Return(f(0.0)),
    ])
    assert _flags(g) == [True]


def test_other_indices_into_an_array_of_unknown_length_are_unproven():
    g = _local_sum(params=[Param("n", I64)], body=[
        Assign("t", na(F64A, li("n"), f(0.0))),
        StoreIndex("t", i(0), f(1.0)),
        ForRange("k", i(0), li("n"), i(1), (StoreIndex("t", li("k"), f(1.0)),)),
        Return(f(0.0)),
    ])
    assert _flags(g) == [False, False]


def test_reassigning_the_array_inside_the_loop_defeats_the_loop_proof():
    g = _local_sum(body=[
        Assign("t", na(F64A, i(8), f(0.0))),
        Assign("s", f(0.0)),
        ForRange("k", i(0), Len(I64, "t"), i(1), (
            Assign("t", na(F64A, i(1), f(0.0))),
            Assign("s", add(lf("s"), Index(F64, "t", li("k")), F64)),
        )),
        Return(lf("s")),
    ])
    assert _flags(g) == [False]


def test_reassigning_the_array_in_a_nested_statement_also_defeats_it():
    g = _local_sum(params=[Param("c", BOOL)], body=[
        Assign("t", na(F64A, i(8), f(0.0))),
        Assign("s", f(0.0)),
        ForRange("k", i(0), Len(I64, "t"), i(1), (
            If(lb("c"), (Assign("t", na(F64A, i(1), f(0.0))),)),
            Assign("s", add(lf("s"), Index(F64, "t", li("k")), F64)),
        )),
        Return(lf("s")),
    ])
    assert _flags(g) == [False]


def _const_index(length, *indices, extra=()):
    """t = [0.0] * length; x = t[i0] + t[i1] + ...  -> the proven flags in order"""
    reads = [Index(F64, "t", i(ix)) for ix in indices]
    total = reads[0]
    for r in reads[1:]:
        total = add(total, r, F64)
    return fn(locals_={"t": F64A}, returns=F64, pure=True, body=[
        *extra, Assign("t", na(F64A, i(length), f(0.0))), Return(total)])


def test_constant_indices_below_a_known_length_are_proven():
    assert _flags(_const_index(8, 0, 7)) == [True, True]


def test_constant_indices_outside_a_known_length_are_not_proven():
    assert _flags(_const_index(8, 8, 100, -1, -8)) == [False, False, False, False]
    assert _flags(_const_index(0, 0)) == [False]
    assert _flags(_const_index(-3, 0)) == [False]        # a negative length is an empty array


def test_an_index_expression_with_a_bounded_interval_is_proven():
    g = fn(locals_={"t": F64A, "s": F64, "k": I64}, returns=F64, pure=True, may_deopt=False, body=[
        Assign("t", na(F64A, i(10), f(0.0))),
        Assign("s", f(0.0)),
        ForRange("k", i(0), i(9), i(1), (
            Assign("s", add(lf("s"), Index(F64, "t", padd(li("k"), i(1))), F64)),
            Assign("s", add(lf("s"), Index(F64, "t", psub(li("k"), i(1))), F64)),
        )),
        Return(lf("s")),
    ])
    assert _flags(g) == [True, False]      # k + 1 in [1, 9]; k - 1 in [-1, 7] may be negative


def test_the_length_interval_comes_from_the_length_expression():
    g = fn(locals_={"t": F64A, "j": I64, "s": F64}, returns=F64, pure=True, body=[
        Assign("s", f(0.0)),
        ForRange("j", i(2), i(6), i(1), (
            Assign("t", na(F64A, li("j"), f(0.0))),
            Assign("s", add(lf("s"), Index(F64, "t", i(0)), F64)),
            Assign("s", add(lf("s"), Index(F64, "t", i(1)), F64)),
            Assign("s", add(lf("s"), Index(F64, "t", i(2)), F64)),
        )),
        Return(lf("s")),
    ])
    assert _flags(g) == [True, True, False]      # j in [2, 5] -> the length is at least 2


def test_a_twice_assigned_array_has_no_known_length():
    g = fn(params=[Param("c", BOOL)], locals_={"t": F64A}, returns=F64, pure=True, body=[
        Assign("t", na(F64A, i(8), f(0.0))),
        If(lb("c"), (Assign("t", na(F64A, i(1), f(0.0))),)),
        Return(Index(F64, "t", i(5))),
    ])
    assert _flags(g) == [False]


def test_a_copy_inherits_the_length_of_a_known_source_only():
    g = fn(params=[ArrayParam("a", F64A, stored=False)], locals_={"t": F64A, "u": F64A, "v": F64A},
           returns=F64, pure=True, body=[
        Assign("t", na(F64A, i(4), f(0.0))),
        Assign("u", CopyArray(F64A, "t")),
        Assign("v", CopyArray(F64A, "a")),
        Return(add(add(Index(F64, "u", i(3)), Index(F64, "u", i(4)), F64),
                   Index(F64, "v", i(0)), F64)),
    ])
    assert _flags(g) == [True, False, False]


def test_a_proven_flag_from_the_input_is_not_trusted_for_local_arrays():
    g = fn(params=[Param("n", I64)], locals_={"t": F64A}, returns=F64, pure=True, body=[
        Assign("t", na(F64A, li("n"), f(0.0))),
        Return(Index(F64, "t", i(0), proven=True)),
    ])
    assert _flags(g) == [False]


def test_len_of_a_local_array_has_the_length_interval():
    def doubled(length):
        return fn(params=[Param("n", I64)], locals_={"t": F64A, "x": I64}, returns=I64, pure=True,
                  body=[Assign("t", na(F64A, length, f(0.0))),
                        Assign("x", pmul(Len(I64, "t"), i(2 ** 60))), Return(li("x"))])
    assert_accepted(doubled(i(5)))              # len == 5: 5 * 2**60 fits i64 (only if known)
    assert_rejected(PROVEN, doubled(li("n")))   # unknown length: [0, 2**62] * 2**60 does not
    assert_rejected(PROVEN, doubled(i(20)))     # len == 20: 20 * 2**60 does not fit


# 10.4 definite assignment

@pytest.mark.parametrize("use", [
    lambda: Assign("x", Index(F64, "t", i(0))),
    lambda: StoreIndex("t", i(0), f(1.0)),
    lambda: Assign("n", Len(I64, "t")),
    lambda: Assign("u", CopyArray(F64A, "t")),
    lambda: ForRange("k", i(0), Len(I64, "t"), i(1), ()),
])
def test_a_local_array_must_be_assigned_before_any_use(use):
    g = fn(params=[Param("c", BOOL)], locals_={"t": F64A, "u": F64A, "x": F64, "n": I64, "k": I64},
           returns=NONE, body=[
        If(lb("c"), (Assign("t", na(F64A, i(2), f(0.0))),)),
        use(), Return()])
    assert_rejected(DA, g)
    fixed = dataclasses.replace(g, body=(Assign("t", na(F64A, i(2), f(0.0))),) + g.body[1:])
    assert_accepted(dataclasses.replace(fixed, pure=True))


def test_assigned_on_both_branches_a_local_array_is_defined():
    assert_accepted(fn(params=[Param("c", BOOL)], locals_={"t": F64A}, returns=F64, pure=True,
                       body=[If(lb("c"), (Assign("t", na(F64A, i(2), f(0.0))),),
                                (Assign("t", na(F64A, i(3), f(0.0))),)),
                             Return(Index(F64, "t", i(0)))]))


def test_a_local_array_assigned_in_a_loop_is_not_defined_after_it():
    assert_rejected(DA, fn(locals_={"t": F64A, "k": I64}, returns=F64, body=[
        ForRange("k", i(0), i(3), i(1), (Assign("t", na(F64A, i(2), f(0.0))),)),
        Return(Index(F64, "t", i(0)))]))


def test_the_allocation_operands_are_read_before_the_array_is_assigned():
    assert_rejected(DA, fn(locals_={"t": F64A}, returns=NONE, body=[
        Assign("t", na(F64A, Len(I64, "t"), f(0.0))), Return()]))


# 10.5 tuples

def test_a_tuple_of_boxed_scalars_and_objects_is_accepted():
    assert_accepted(fn(params=[Param("o", OBJ), Param("x", F64)], locals_={"r": OBJ}, returns=OBJ,
                       pure=True, body=[
        Assign("r", tup(Box(OBJ, lf("x")), lo("o"), Box(OBJ, i(3)))), Return(lo("r"))]))


def test_a_tuple_can_be_returned_directly_and_may_be_empty():
    assert_accepted(fn(returns=OBJ, pure=True, body=[Return(tup())]))
    assert_accepted(fn(params=[Param("x", I64)], returns=OBJ, pure=True,
                       body=[Return(tup(Box(OBJ, li("x"))))]))


def test_a_tuple_of_local_array_elements_is_accepted_through_index():
    assert_accepted(fn(locals_={"t": I64A}, returns=OBJ, pure=True, body=[
        Assign("t", na(I64A, i(3), i(1))),
        Return(tup(Box(OBJ, Index(I64, "t", i(0))), Box(OBJ, Len(I64, "t"))))]))


def test_a_scalar_tuple_element_needs_box():
    assert_rejected(OWNERSHIP, fn(params=[Param("x", F64)], returns=OBJ, body=[
        Return(tup(lf("x")))]))
    assert_rejected(OWNERSHIP, fn(returns=OBJ, body=[Return(tup(i(1)))]))


def test_a_tuple_is_an_obj_and_only_an_obj():
    assert_rejected(TYPE, fn(returns=I64, body=[Return(Tuple(I64, ()))]))
    assert_rejected(OWNERSHIP, fn(returns=I64, body=[Return(tup())]))
    assert_rejected(OWNERSHIP, fn(locals_={"x": F64}, returns=NONE, body=[
        Assign("x", tup()), Return()]))
    assert_rejected(TYPE, fn(returns=BOOL, body=[Return(Tuple(BOOL, ()))]))


def test_a_tuple_creates_a_reference():
    # an OBJ local assigned from a tuple is an owned reference, like Box/CallObject
    assert_accepted(fn(locals_={"r": OBJ, "q": OBJ}, returns=OBJ, pure=True, body=[
        Assign("r", tup()), Assign("q", tup(lo("r"))), Return(lo("q"))]))


def test_tuple_elements_must_be_a_tuple_of_checked_expressions():
    assert_rejected(STRUCTURE, fn(returns=OBJ, body=[Return(Tuple(OBJ, [Box(OBJ, i(1))]))]))
    assert_rejected(DA, fn(locals_={"o": OBJ}, returns=OBJ, body=[Return(tup(lo("o")))]))
    assert_rejected(TYPE, fn(returns=OBJ, body=[Return(tup(Const(F64, 1)))]))


def test_a_tuple_is_not_an_effect_but_unboxing_its_elements_is_not_available():
    # a tuple creation is pure; the function may keep pure=True with a deopt-free body
    assert_accepted(fn(returns=OBJ, pure=True, may_deopt=False, body=[Return(tup())]))


# 10.6 mutation: nothing slips through

def _array_functions():
    """Correct functions with local arrays and tuples; the mutator breaks them."""
    fk = fn("fk", params=[Param("n", I64), ArrayParam("a", I64A, stored=False)],
            locals_={"p": I64A, "q": I64A, "k": I64, "s": I64, "t": OBJ, "o": OBJ},
            returns=OBJ, pure=True, may_deopt=True, line=3, body=[
                Assign("p", na(I64A, i(8), None, True)),
                Assign("q", CopyArray(I64A, "p")),
                Assign("s", i(0)),
                Assign("o", g_("OBJECT")),
                ForRange("k", i(0), Len(I64, "p"), i(1), (
                    StoreIndex("q", li("k"), add(Index(I64, "p", li("k")), i(1))),
                    Assign("s", add(li("s"), Index(I64, "q", li("k")))),
                )),
                Assign("t", tup(Box(OBJ, li("s")), Box(OBJ, Index(I64, "q", i(0))), lo("o"))),
                Return(lo("t")),
            ])
    ip = fn("ip", params=[ArrayParam("a", F64A, stored=True)],
            locals_={"z": F64A, "k": I64, "r": OBJ}, returns=NONE, line=9, body=[
                Assign("z", na(F64A, Len(I64, "a"), f(0.0))),
                ForRange("k", i(0), Len(I64, "a"), i(1), (
                    StoreIndex("z", li("k"), Index(F64, "a", li("k"))),
                    StoreIndex("a", li("k"), BinOp(F64, BinOpKind.ADD, Index(F64, "z", li("k")),
                                                   f(1.0))),
                )),
                Assign("r", call_obj(g_("print"), Box(OBJ, Index(F64, "z", i(0))))),
                Return(),
            ])
    sink = fn("sink", params=[Param("x", OBJ)], returns=NONE, line=12, body=[Return()])
    return {x.name: x for x in (fk, ip, sink)}


def _array_mutations(rng, base):
    """(description, functions by name, the function that must be rejected, the rules any one of
    which is an acceptable report)."""
    b = dict(base)
    fk, ip = b["fk"], b["ip"]
    kind = rng.randrange(16)
    arr = rng.choice([("p", I64A), ("q", I64A)])
    leak = la(*arr)
    insert = lambda g, *stmts: dataclasses.replace(g, body=g.body[:-1] + tuple(stmts) + g.body[-1:])
    if kind == 0:       # return the array
        b["fk"] = dataclasses.replace(fk, body=fk.body[:-1] + (Return(leak),))
        return "return array", b, "fk", {ESCAPE}
    if kind == 1:       # pass it to a compiled Call
        b["fk"] = insert(fk, ExprStmt(Call(NONE, "sink", (leak,))))
        return "call array", b, "fk", {ESCAPE}
    if kind == 2:       # pass it to CallObject
        b["fk"] = insert(fk, Assign("o", call_obj(g_("keep"), leak)))
        return "callobject array", b, "fk", {ESCAPE}
    if kind == 3:       # box it
        b["fk"] = insert(fk, Assign("o", Box(OBJ, leak)))
        return "box array", b, "fk", {ESCAPE}
    if kind == 4:       # put it in a tuple
        b["fk"] = insert(fk, Assign("o", tup(Box(OBJ, i(1)), leak)))
        return "tuple array", b, "fk", {ESCAPE}
    if kind == 5:       # alias it
        b["fk"] = insert(fk, Assign("q" if arr[0] == "p" else "p", leak))
        return "alias array", b, "fk", {ESCAPE}
    if kind == 6:       # an allocation where it may not stand
        alloc = rng.choice([na(I64A, i(2), i(0)), CopyArray(I64A, "p")])
        stmt = rng.choice([ExprStmt(alloc), Assign("o", alloc), Assign("s", alloc),
                           Assign("o", Box(OBJ, alloc))])
        b["fk"] = insert(fk, stmt)
        return "stray allocation", b, "fk", {ESCAPE}
    if kind == 7:       # pure flag set on an impure function / an extra effect in a pure one
        if rng.random() < 0.5:
            b["ip"] = dataclasses.replace(ip, pure=True)
            return "pure on impure", b, "ip", {FLAG_PURE}
        b["fk"] = insert(fk, StoreIndex("a", i(0), i(1)))
        return "param store in pure", b, "fk", {FLAG_PURE}
    if kind == 8:       # pure flag cleared on a function that deopts / may_deopt flipped
        if rng.random() < 0.5:
            b["fk"] = dataclasses.replace(fk, pure=False)
            return "drop pure", b, "fk", {DEOPT}
        b["fk"] = dataclasses.replace(fk, may_deopt=False)
        return "drop may_deopt", b, "fk", {FLAG_DEOPT}
    if kind == 9:       # an impurity added to the pure function
        stmt = rng.choice([ExprStmt(pycall("print")),
                           Assign("o", GetAttr(OBJ, g_("m"), "x"))])
        b["fk"] = insert(fk, stmt)
        return "impurity in pure", b, "fk", {FLAG_PURE}
    if kind == 10:      # the allocation is removed: every use reads an unassigned array
        keep = [s for s in fk.body if not (type(s) is Assign and s.target == arr[0])]
        b["fk"] = dataclasses.replace(fk, body=tuple(keep))
        return "drop allocation", b, "fk", {DA}
    if kind == 11:      # a malformed allocation
        bad = rng.choice([na(I64A, i(8)), na(I64A, i(8), i(0), True), na(I64A, f(8.0), i(0)),
                          na(I64A, i(8), f(0.0)), NewArray(I64A, i(8), None, 1),
                          na(F64A, i(8), f(0.0)), NewArray(I64, i(8), i(0))])
        b["fk"] = dataclasses.replace(fk, body=(Assign("p", bad),) + fk.body[1:])
        return "bad allocation", b, "fk", {TYPE}
    if kind == 12:      # a bad copy source
        src = rng.choice([("n", {ARRAY_USE}), ("nowhere", {ARRAY_USE})])
        b["fk"] = dataclasses.replace(fk, body=(fk.body[0], Assign("q", CopyArray(I64A, src[0]))
                                                ) + fk.body[2:])
        return "bad copy source", b, "fk", src[1] | {DA}
    if kind == 13:      # a scalar tuple element without Box
        b["fk"] = insert(fk, Assign("o", tup(li("s"))))
        return "unboxed tuple element", b, "fk", {OWNERSHIP}
    if kind == 14:      # the local's declared type disagrees with the allocation
        b["fk"] = dataclasses.replace(fk, locals={**fk.locals, "p": F64A})
        return "declared type", b, "fk", {TYPE}
    # kind 15: a tuple typed as something else
    b["fk"] = insert(fk, Assign("s", Tuple(I64, ())))
    return "tuple not obj", b, "fk", {TYPE, OWNERSHIP}


def test_array_base_functions_are_accepted():
    assert_accepted(*_array_functions().values())


@pytest.mark.parametrize("seed", [20261005])
def test_no_array_breaking_mutation_slips_through(seed):
    rng = random.Random(seed)
    base = _array_functions()
    missed = []
    for n in range(400):
        desc, funcs, target, allowed = _array_mutations(rng, base)
        out, diags = run(*funcs.values())
        got = rules_for(diags, target)
        if target in kept(out) or not (got & allowed):
            missed.append((n, desc, target, sorted(got)))
    assert missed == []


def test_each_array_mutation_kind_was_exercised():
    rng = random.Random(20261005)
    base = _array_functions()
    kinds = {_array_mutations(rng, base)[0] for _ in range(400)}
    assert len(kinds) == 18, sorted(kinds)     # 16 kinds; kinds 7 and 8 have two variants
