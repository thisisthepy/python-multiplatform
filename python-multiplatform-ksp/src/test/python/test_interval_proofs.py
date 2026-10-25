"""Interval proofs that drop provably safe checked i64 operations (issue #148, SPEC N-8 / N-9).

New facts, all re-proved by the verifier (the front end only proposes `proven`):

  * guards: inside `if x < e:` / `while lo < hi:` (and after a guard that returns) a local's
    interval is narrowed by the comparison, until the local is assigned;
  * locals: a local assigned only i64 values has the join of their intervals (a flow-insensitive
    fixpoint with widening), so `x = 1; x + 1` and non-negative accumulators are known;
  * `//` and `%` by a constant have bounded results;
  * `//` and `%` by a positive power-of-two Const of a value in [0, 2**63-1] carry `proven` and
    are emitted as a shift / mask (no tp_floordiv_i64 / tp_mod_i64);
  * the front end's `len()` bound is 2**62, as the verifier's.

Section 1 builds IR by hand (no front end): each fact is accepted when sound and rejected with the
PROVEN rule when the IR claims it without the fact. Section 2 runs source through the front end,
verifier, C back end and the C compiler, and compares the compiled function with CPython at the
boundary values. Builds go to pytest's basetemp inside the worktree's `.tmp/`.
"""
from __future__ import annotations

import dataclasses
import importlib.machinery
import importlib.util
import re
from pathlib import Path

import pytest

from typedpython import cbuild, cgen, frontend, verify
from typedpython.ir import (
    And, ArrayParam, Assign, BinOp, BinOpKind, Compare, CompareKind, Const, ForRange, If, Index,
    Local, Or, Param, Return, Type, UnaryOp, UnaryOpKind, While,
)
from test_verify import (
    IMPURE, PROVEN, FLAG_DEOPT, assert_accepted, assert_rejected, fn, i, kept, lb, li, padd, pmul,
    psub, run,
)

I64, BOOL = Type.I64, Type.BOOL
MAX, MIN = 2 ** 63 - 1, -2 ** 63
A = [ArrayParam("a", Type.I64_ARRAY, stored=False)]
N = [Param("n", I64)]


def cmp(kind, l, r): return Compare(BOOL, kind, l, r)
def lt(l, r): return cmp(CompareKind.LT, l, r)
def le(l, r): return cmp(CompareKind.LE, l, r)
def gt(l, r): return cmp(CompareKind.GT, l, r)
def ge(l, r): return cmp(CompareKind.GE, l, r)
def eq(l, r): return cmp(CompareKind.EQ, l, r)
def pdiv(l, r): return BinOp(I64, BinOpKind.FLOORDIV, l, r, proven=True)
def pmod(l, r): return BinOp(I64, BinOpKind.MOD, l, r, proven=True)
def div(l, r): return BinOp(I64, BinOpKind.FLOORDIV, l, r)
def mod(l, r): return BinOp(I64, BinOpKind.MOD, l, r)
def elem(k=0): return Index(I64, "a", i(k))


def pure_fn(body, params=N, locals_=None, may_deopt=False, **kw):
    return fn(params=params, locals_=locals_, returns=I64, pure=True, may_deopt=may_deopt,
              body=body, **kw)


# --- 1a. guards ----------------------------------------------------------------------------------

def test_a_less_than_guard_bounds_the_local_from_above():
    ok = pure_fn([If(lt(li("n"), i(MAX)), (Return(padd(li("n"), i(1))),)), Return(i(0))])
    assert_accepted(ok)
    # `<=` leaves n == MAX possible
    assert_rejected(PROVEN, pure_fn([If(le(li("n"), i(MAX)), (Return(padd(li("n"), i(1))),)),
                                     Return(i(0))]))
    # no guard at all
    assert_rejected(PROVEN, pure_fn([Return(padd(li("n"), i(1)))]))


def test_a_greater_than_guard_bounds_the_local_from_below():
    assert_accepted(pure_fn([If(gt(li("n"), i(MIN)), (Return(psub(li("n"), i(1))),)), Return(i(0))]))
    assert_rejected(PROVEN, pure_fn([If(ge(li("n"), i(MIN)),
                                        (Return(psub(li("n"), i(1))),)), Return(i(0))]))


def test_the_local_may_be_on_the_right_of_the_comparison():
    assert_accepted(pure_fn([If(gt(i(MAX), li("n")), (Return(padd(li("n"), i(1))),)),
                             Return(i(0))]))


def test_the_other_side_may_be_any_expression_with_a_known_interval():
    # n < m, and m <= 2**63 - 1: n <= 2**63 - 2
    ps = [Param("n", I64), Param("m", I64)]
    assert_accepted(pure_fn([If(lt(li("n"), li("m")), (Return(padd(li("n"), i(1))),)),
                             Return(i(0))], params=ps))
    # but n <= m only gives n <= 2**63 - 1
    assert_rejected(PROVEN, pure_fn([If(le(li("n"), li("m")), (Return(padd(li("n"), i(1))),)),
                                     Return(i(0))], params=ps))


def test_a_conjunction_applies_every_link_and_a_disjunction_none():
    both = And(BOOL, lt(li("n"), i(MAX)), gt(li("n"), i(MIN)))
    assert_accepted(pure_fn([If(both, (Return(padd(psub(li("n"), i(1)), i(1))),)), Return(i(0))]))
    either = Or(BOOL, lt(li("n"), i(MAX)), lt(li("n"), i(5)))
    assert_rejected(PROVEN, pure_fn([If(either, (Return(padd(li("n"), i(1))),)), Return(i(0))]))
    # the else branch of a disjunction has both links false
    neither = Or(BOOL, ge(li("n"), i(MAX)), lt(li("n"), i(MIN + 1)))
    assert_accepted(pure_fn([If(neither, (Return(i(0)),), (Return(padd(li("n"), i(1))),))]))


def test_not_and_the_else_branch_negate_the_condition():
    assert_accepted(pure_fn([If(UnaryOp(BOOL, UnaryOpKind.NOT, ge(li("n"), i(MAX))),
                                (Return(padd(li("n"), i(1))),)), Return(i(0))]))
    assert_accepted(pure_fn([If(ge(li("n"), i(MAX)), (Return(i(0)),),
                                (Return(padd(li("n"), i(1))),))]))


def test_a_guard_that_returns_narrows_the_code_after_it():
    assert_accepted(pure_fn([If(ge(li("n"), i(MAX)), (Return(i(0)),)),
                             Return(padd(li("n"), i(1)))]))
    # the branch falls through: the code after the If is reachable with n == MAX
    assert_rejected(PROVEN, pure_fn([If(ge(li("n"), i(MAX)), (Assign("x", i(1)),)),
                                     Return(padd(li("n"), i(1)))], locals_={"x": I64}))


def test_an_equality_guard_pins_the_local():
    assert_accepted(pure_fn([If(eq(li("n"), i(5)), (Return(padd(li("n"), i(MAX - 5))),)),
                             Return(i(0))]))
    assert_rejected(PROVEN, pure_fn([If(eq(li("n"), i(5)), (Return(padd(li("n"), i(MAX - 4))),)),
                                     Return(i(0))]))


def test_assigning_the_local_ends_the_guard():
    ps = N + A
    ok = pure_fn([Assign("x", li("n")),
                  If(lt(li("x"), i(MAX)), (Return(padd(li("x"), i(1))),)), Return(i(0))],
                 params=ps, locals_={"x": I64})
    assert_accepted(ok)
    killed = pure_fn([Assign("x", li("n")),
                      If(lt(li("x"), i(MAX)), (Assign("x", elem()), Return(padd(li("x"), i(1))))),
                      Return(i(0))], params=ps, locals_={"x": I64})
    assert_rejected(PROVEN, killed)


def test_a_while_condition_holds_at_the_top_of_each_iteration_until_the_local_is_assigned():
    # while lo < hi: lo += 1; hi -= 1   (the fannkuch reversal)
    loop = While(lt(li("lo"), li("hi")), (Assign("lo", padd(li("lo"), i(1))),
                                          Assign("hi", psub(li("hi"), i(1)))))
    body = [Assign("lo", elem(0)), Assign("hi", elem(1)), loop, Return(i(0))]
    assert_accepted(pure_fn(body, params=A, locals_={"lo": I64, "hi": I64}))
    # lo <= hi lets lo be MAX (and hi be MIN): neither step is safe
    weak = While(le(li("lo"), li("hi")), (Assign("lo", padd(li("lo"), i(1))),))
    assert_rejected(PROVEN, pure_fn([Assign("lo", elem(0)), Assign("hi", elem(1)), weak, Return(i(0))],
                                    params=A, locals_={"lo": I64, "hi": I64}))
    weak2 = While(le(li("lo"), li("hi")), (Assign("hi", psub(li("hi"), i(1))),))
    assert_rejected(PROVEN, pure_fn([Assign("lo", elem(0)), Assign("hi", elem(1)), weak2, Return(i(0))],
                                    params=A, locals_={"lo": I64, "hi": I64}))


def test_a_guard_does_not_survive_the_back_edge_of_a_loop_that_assigns_the_local():
    # if x < MAX: while c: y = x + 1; x = a[0]     second iteration: x is any i64
    inner = While(lb("c"), (Assign("y", padd(li("x"), i(1))), Assign("x", elem())))
    body = [Assign("x", li("n")), If(lt(li("x"), i(MAX)), (inner,)), Return(i(0))]
    ps = N + A + [Param("c", BOOL)]
    assert_rejected(PROVEN, pure_fn(body, params=ps, locals_={"x": I64, "y": I64}))
    # the same loop with the guard as its own condition re-checks it every iteration
    again = While(lt(li("x"), i(MAX)), (Assign("y", padd(li("x"), i(1))), Assign("x", elem())))
    assert_accepted(pure_fn([Assign("x", li("n")), again, Return(i(0))], params=N + A,
                            locals_={"x": I64, "y": I64}))


def test_a_guard_does_not_outlive_a_loop_that_assigns_the_local():
    inner = While(lb("c"), (Assign("x", elem()),))
    body = [Assign("x", li("n")),
            If(lt(li("x"), i(MAX)), (inner, Return(padd(li("x"), i(1))))), Return(i(0))]
    ps = N + A + [Param("c", BOOL)]
    assert_rejected(PROVEN, pure_fn(body, params=ps, locals_={"x": I64}))


def test_a_guard_on_a_float_comparison_proves_nothing():
    ps = [Param("n", I64), Param("x", Type.F64)]
    body = [If(lt(Const(Type.F64, 0.0), Local(Type.F64, "x")), (Return(padd(li("n"), i(1))),)), Return(i(0))]
    assert_rejected(PROVEN, pure_fn(body, params=ps))


# --- 1b. locals ----------------------------------------------------------------------------------

def test_a_local_assigned_a_constant_has_that_interval():
    body = [Assign("x", i(1)), Return(padd(li("x"), i(1)))]
    assert_accepted(pure_fn(body, params=(), locals_={"x": I64}))


def test_a_local_has_the_join_of_all_its_assignments():
    ps = [Param("c", BOOL)]
    def f(extra):
        return pure_fn([If(lb("c"), (Assign("x", i(5)),), (Assign("x", i(7)),)),
                        Return(padd(li("x"), i(extra)))], params=ps, locals_={"x": I64})
    assert_accepted(f(MAX - 7))
    assert_rejected(PROVEN, f(MAX - 6))


def test_a_local_assigned_from_an_unknown_value_is_full_range():
    body = [Assign("x", li("n")), Return(padd(li("x"), i(1)))]
    assert_rejected(PROVEN, pure_fn(body, locals_={"x": I64}))
    body = [Assign("x", i(0)), If(gt(li("n"), i(0)), (Assign("x", elem()),)),
            Return(padd(li("x"), i(1)))]
    assert_rejected(PROVEN, pure_fn(body, params=N + A, locals_={"x": I64}))


def test_a_counter_that_only_grows_is_non_negative():
    # x = 0; while c: x = x + 1 (checked)       x in [0, MAX]
    loop = While(lb("c"), (Assign("x", BinOp(I64, BinOpKind.ADD, li("x"), i(1))),))
    ps = [Param("c", BOOL)]
    base = [Assign("x", i(0)), loop]
    assert_accepted(pure_fn(base + [Return(pdiv(li("x"), i(4)))], params=ps, locals_={"x": I64},
                            may_deopt=True))
    # a proven +1 on it is not provable: x can be MAX
    assert_rejected(PROVEN, pure_fn(base + [Return(padd(li("x"), i(1)))], params=ps,
                                    locals_={"x": I64}, may_deopt=True))


def test_a_counter_that_only_shrinks_is_not_non_negative():
    loop = While(lb("c"), (Assign("x", BinOp(I64, BinOpKind.SUB, li("x"), i(1))),))
    ps = [Param("c", BOOL)]
    assert_rejected(PROVEN, pure_fn([Assign("x", i(0)), loop, Return(pdiv(li("x"), i(4)))],
                                    params=ps, locals_={"x": I64}, may_deopt=True))


def test_the_fixpoint_terminates_and_stays_sound_for_mutually_dependent_locals():
    # x = y + 1 and y = x + 1 (checked): both start at 0 and grow without bound
    loop = While(lb("c"), (Assign("x", BinOp(I64, BinOpKind.ADD, li("y"), i(1))),
                           Assign("y", BinOp(I64, BinOpKind.ADD, li("x"), i(1)))))
    ps = [Param("c", BOOL)]
    base = [Assign("x", i(0)), Assign("y", i(0)), loop]
    loc = {"x": I64, "y": I64}
    assert_accepted(pure_fn(base + [Return(pdiv(li("y"), i(2)))], params=ps, locals_=loc,
                            may_deopt=True))
    assert_rejected(PROVEN, pure_fn(base + [Return(padd(li("y"), i(1)))], params=ps, locals_=loc,
                                    may_deopt=True))


def test_a_for_range_variable_is_still_only_known_inside_its_loop():
    body = [Assign("k", i(0)), ForRange("k", i(0), i(5), i(1), ()), Return(padd(li("k"), i(1)))]
    assert_rejected(PROVEN, pure_fn(body, params=(), locals_={"k": I64}))


def test_a_parameter_that_is_reassigned_is_still_unknown():
    body = [Assign("n", i(0)), Return(padd(li("n"), i(1)))]
    assert_rejected(PROVEN, pure_fn(body))


# --- 1c. // and % ---------------------------------------------------------------------------------

def test_mod_by_a_positive_constant_is_between_zero_and_the_divisor_minus_one():
    ok = lambda extra: pure_fn([Return(padd(mod(li("n"), i(7)), i(extra)))], may_deopt=True)
    assert_accepted(ok(MAX - 6))
    assert_rejected(PROVEN, ok(MAX - 5))


def test_mod_by_a_negative_constant_is_between_the_divisor_plus_one_and_zero():
    assert_accepted(pure_fn([Return(padd(mod(li("n"), i(-7)), i(MAX)))], may_deopt=True))
    assert_rejected(PROVEN, pure_fn([Return(padd(mod(li("n"), i(7)), i(MAX)))], may_deopt=True))


def test_mod_by_an_unknown_divisor_is_full_range():
    ps = [Param("n", I64), Param("m", I64)]
    assert_rejected(PROVEN, pure_fn([Return(padd(mod(li("n"), li("m")), i(1)))], params=ps,
                                    may_deopt=True))


def test_floordiv_by_a_positive_constant_halves_the_range():
    two = lambda e: pure_fn([Return(e(div(li("n"), i(2))))], may_deopt=True)
    assert_accepted(two(lambda d: padd(d, d)))                 # [-2**63, 2**63 - 2]
    assert_accepted(two(lambda d: pmul(d, i(2))))
    assert_rejected(PROVEN, two(lambda d: pmul(d, i(3))))


def test_floordiv_by_minus_one_is_full_range():
    assert_rejected(PROVEN, pure_fn([Return(padd(div(li("n"), i(-1)), i(1)))], may_deopt=True))


# --- 1d. floor division / modulo by a power of two -------------------------------------------------

def _loop(op, start=0, stop=5, params=()):
    return fn(params=params, locals_={"k": I64, "t": I64}, pure=True, may_deopt=False,
              body=(ForRange("k", i(start), i(stop), i(1), (Assign("t", op(li("k"))),)), Return()))


@pytest.mark.parametrize("d", [1, 2, 8, 1024, 2 ** 62])
def test_proven_floordiv_and_mod_by_a_power_of_two_of_a_non_negative_value_are_accepted(d):
    assert_accepted(_loop(lambda k: pdiv(k, i(d))))
    assert_accepted(_loop(lambda k: pmod(k, i(d))))


def test_a_proven_div_is_not_a_deopting_node():
    ok = _loop(lambda k: pdiv(k, i(4)))
    assert_accepted(ok)
    assert_rejected(FLAG_DEOPT, dataclasses.replace(ok, may_deopt=True))


@pytest.mark.parametrize("op", [pdiv, pmod])
def test_proven_div_or_mod_needs_a_non_negative_dividend(op):
    assert_rejected(PROVEN, _loop(lambda k: op(k, i(4)), start=-1))
    assert_rejected(PROVEN, pure_fn([Return(op(li("n"), i(4)))]))


@pytest.mark.parametrize("op", [pdiv, pmod])
@pytest.mark.parametrize("d", [0, -8, 3, 6, 12])
def test_proven_div_or_mod_needs_a_positive_power_of_two_constant(op, d):
    assert_rejected(PROVEN, _loop(lambda k: op(k, i(d))))


@pytest.mark.parametrize("op", [pdiv, pmod])
def test_proven_div_or_mod_needs_a_constant_divisor(op):
    assert_rejected(PROVEN, _loop(lambda k: op(k, li("k"))))
    assert_rejected(PROVEN, _loop(lambda k: op(k, padd(i(2), i(2)))))


def test_proven_truediv_is_still_rejected():
    assert_rejected(PROVEN, _loop(lambda k: BinOp(Type.F64, BinOpKind.TRUEDIV, k, i(2), proven=True)))


def test_a_guarded_dividend_is_non_negative():
    assert_accepted(pure_fn([If(ge(li("n"), i(0)), (Return(pdiv(li("n"), i(16))),)), Return(i(0))]))
    assert_rejected(PROVEN, pure_fn([If(ge(li("n"), i(-1)), (Return(pdiv(li("n"), i(16))),)),
                                     Return(i(0))]))


def test_a_mod_result_can_feed_a_proof():
    # (k % 8) + (MAX - 7) fits
    assert_accepted(_loop(lambda k: padd(pmod(k, i(8)), i(MAX - 7))))
    assert_rejected(PROVEN, _loop(lambda k: padd(pmod(k, i(8)), i(MAX - 6))))


# --- 2. end to end ---------------------------------------------------------------------------------

SOURCE = '''\
# typedpython: compiled


def dec_if_positive(n: int) -> int:
    if n > 0:
        return n - 1
    return -1


def dec_after_guard(n: int) -> int:
    if n <= 0:
        return 0
    return n - 1


def inc_below_max(n: int) -> int:
    if n < 9223372036854775807:
        return n + 1
    return 0


def inc_below_100(n: int) -> int:
    if n < 100:
        return n + 1
    return 0


def inc_if_positive(n: int) -> int:
    if n > 0:
        return n + 1
    return 0


def reverse_gap(a: int, b: int) -> int:
    lo: int = a
    hi: int = b
    steps: int = 0
    while lo < hi:
        lo += 1
        hi -= 1
        steps += 1
    return hi - lo + steps


def killed_guard(n: int, a: list[int]) -> int:
    if n < 100:
        n = a[0]
        return n + 1
    return 0


def killed_in_loop(c: int, a: list[int]) -> int:
    n: int = a[0]
    t: int = 0
    if n < 100:
        for i in range(c):
            t = n + 1
            n = a[1]
    return t


def parity(n: int) -> int:
    k: int = 0
    s: int = 0
    for i in range(n):
        if k % 2 == 0:
            s += 1
        k += 1
    return s


def const_local() -> int:
    x: int = 41
    return x + 1


def range_shifts(a: int, b: int) -> int:
    if a < 0:
        return 0
    t: int = 0
    for i in range(a, b):
        t += i // 4 + i % 8 + i // 1
    return t


def guarded_shift(n: int) -> int:
    if n >= 0:
        return n // 16 + n % 16
    return 0


def negative_shift(n: int) -> int:
    return n // 4 + n % 8


def odd_divisor(n: int) -> int:
    if n >= 0:
        return n // 3 + n % 6
    return 0


def mod_then_add(n: int) -> int:
    return n % 10 + 1


def pair_sums(a: list[int]) -> int:
    best: int = 0
    for i in range(len(a)):
        for j in range(len(a)):
            if i + j > best:
                best = i + j
    return best
'''

CASES = {
    "dec_if_positive": [(n,) for n in (MIN, -1, 0, 1, 2, MAX - 1, MAX)],
    "dec_after_guard": [(n,) for n in (MIN, -1, 0, 1, 2, MAX - 1, MAX)],
    "inc_below_max": [(n,) for n in (MIN, -1, 0, MAX - 2, MAX - 1, MAX)],
    "inc_below_100": [(n,) for n in (MIN, 98, 99, 100, MAX)],
    "inc_if_positive": [(n,) for n in (MIN, 0, 1, MAX - 1, MAX)],
    "reverse_gap": [(0, 0), (0, 1), (0, 7), (5, 2), (-3, 4), (MAX - 3, MAX), (MIN, MIN + 3),
                    (MAX - 1, MAX), (MIN, MIN + 1)],
    "killed_guard": [(5, [3]), (5, [MAX]), (500, [MAX]), (5, [MIN])],
    "killed_in_loop": [(0, [5, MAX]), (1, [5, MAX]), (3, [5, MAX]), (3, [5, 6]), (3, [500, MAX])],
    "parity": [(0,), (1,), (2,), (7,), (100,)],
    "const_local": [()],
    "range_shifts": [(0, 0), (0, 40), (-20, 20), (-3, 0), (3, 3), (5, 2), (MAX - 5, MAX), (MIN, MIN + 6),
                     (MIN + 3, MIN + 4)],
    "guarded_shift": [(n,) for n in (MIN, -1, 0, 1, 15, 16, 17, 1023, MAX)],
    "negative_shift": [(n,) for n in (MIN, -9, -8, -1, 0, 1, 7, 8, MAX)],
    "odd_divisor": [(n,) for n in (MIN, -1, 0, 1, 5, 6, 7, MAX)],
    "mod_then_add": [(n,) for n in (MIN, -11, -1, 0, 9, 10, MAX)],
    "pair_sums": [([],), ([5],), ([1, 2, 3],), (list(range(9)),)],
}


@pytest.fixture(scope="module")
def built(tmp_path_factory):
    d = tmp_path_factory.mktemp("interval_proofs")
    path = d / "ivmod.py"
    path.write_text(SOURCE)
    lowered = frontend.lower(path)
    proved, diags = verify.verify(lowered)
    c = cgen.generate(proved, path, path.name)
    so = cbuild.build(c, path.stem, d / "out")
    loader = importlib.machinery.ExtensionFileLoader(path.stem, str(so))
    spec = importlib.util.spec_from_file_location(path.stem, str(so), loader=loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return dataclasses.make_dataclass("Built", ["lowered", "proved", "diags", "c", "module"])(
        lowered, proved, diags, c, module)


def impl_text(c: str, name: str) -> str:
    """The C text of the normal implementation of `name`."""
    start = c.index(f"static int tp_impl_{name}(", c.index("static const char tp_source"))
    while not c[c.index(")", start) + 1:].startswith("\n{"):
        start = c.index(f"static int tp_impl_{name}(", start + 1)
    return c[start:c.index("\n}\n", start)]


def helpers(c: str, name: str) -> dict[str, int]:
    text = impl_text(c, name)
    out: dict[str, int] = {}
    for h in re.findall(r"tp_(add|sub|mul|floordiv|mod|neg)_i64\(", text):
        out[h] = out.get(h, 0) + 1
    return out


def test_every_function_is_compiled(built):
    assert {f.name for f in built.proved.functions} == set(CASES), (built.proved.skipped, built.diags)


@pytest.mark.parametrize("name", sorted(CASES))
def test_compiled_equals_cpython_at_the_boundaries(built, name):
    comp = getattr(built.module, name)
    plain = built.module.__typedpython_interpreted__[name]
    for args in CASES[name]:
        want = plain(*[list(a) if isinstance(a, list) else a for a in args])
        got = comp(*[list(a) if isinstance(a, list) else a for a in args])
        assert got == want and type(got) is type(want), (name, args, got, want)


# which helpers must be gone from each function's C, and which must stay
GONE = {
    "dec_if_positive": ["sub"],
    "dec_after_guard": ["sub"],
    "inc_below_max": ["add"],
    "inc_below_100": ["add"],
    "const_local": ["add"],
    "guarded_shift": ["floordiv", "mod", "add"],
    "mod_then_add": ["add"],
    "pair_sums": ["add"],
}
STAY = {
    "inc_if_positive": ["add"],
    "killed_guard": ["add"],
    "killed_in_loop": ["add"],
    "negative_shift": ["floordiv", "mod"],
    "odd_divisor": ["floordiv", "mod"],
    "mod_then_add": ["mod"],
}


@pytest.mark.parametrize("name", sorted(GONE))
def test_a_proved_operation_has_no_checked_helper_in_the_c(built, name):
    found = helpers(built.c, name)
    for h in GONE[name]:
        assert h not in found, (name, found)


@pytest.mark.parametrize("name", sorted(STAY))
def test_an_unprovable_operation_keeps_its_checked_helper(built, name):
    found = helpers(built.c, name)
    for h in STAY[name]:
        assert h in found, (name, found)


def test_reverse_gap_drops_exactly_the_two_guarded_steps(built):
    # lo += 1 and hi -= 1 are proved by `lo < hi`; `steps += 1` and `hi - lo + steps` are not
    assert helpers(built.c, "reverse_gap") == {"add": 2, "sub": 1}


def test_parity_masks_the_accumulator_and_keeps_its_increments_checked(built):
    found = helpers(built.c, "parity")
    assert "mod" not in found and found.get("add", 0) >= 2
    assert "& " in impl_text(built.c, "parity")


def test_range_shifts_use_shifts_and_masks(built):
    text = impl_text(built.c, "range_shifts")
    found = helpers(built.c, "range_shifts")
    assert "floordiv" not in found and "mod" not in found
    assert ">> 2" in text and "& INT64_C(7)" in text


def test_the_front_end_marks_the_new_facts_and_the_verifier_agrees(built):
    f = {g.name: g for g in built.lowered.functions}
    def ops(fn_name):
        found, stack = [], [f[fn_name]]
        while stack:
            x = stack.pop()
            if isinstance(x, (tuple, list)):
                stack.extend(x)
            elif dataclasses.is_dataclass(x) and not isinstance(x, type):
                if isinstance(x, BinOp):
                    found.append(x)
                stack.extend(getattr(x, fl.name) for fl in dataclasses.fields(x))
        return found

    assert all(b.proven for b in ops("dec_if_positive"))
    assert all(b.proven for b in ops("guarded_shift"))
    assert not any(b.proven for b in ops("inc_if_positive"))
    assert built.diags == []
