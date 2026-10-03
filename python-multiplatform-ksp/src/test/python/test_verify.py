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
    CompareObj, Const, Continue, ExprStmt, ForRange, Function, GetAttr, Global, If, Index, Len,
    Local, MathCall, MathFunc, Module, ObjToFloat, Or, Param, Return, StoreIndex, ToFloat, Truth,
    Type, Unbox, UnaryOp, UnaryOpKind, While,
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


def fn(name="f", body=(), params=(), locals_=None, returns=NONE, pure=False, may_deopt=False,
       line=10):
    return Function(name=name, params=tuple(params), returns=returns, locals=dict(locals_ or {}),
                    body=tuple(body), pure=pure, may_deopt=may_deopt, source_line=line)


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
