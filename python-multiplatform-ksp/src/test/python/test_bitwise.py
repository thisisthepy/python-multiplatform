"""i64 bitwise operators `& | ^ ~ << >>` and their augmented forms (SPEC N-16, #289).

Every check is a differential test against the interpreted function of the same module
(`__typedpython_interpreted__`): a result, a raised exception (type and message) and, for an impure
function, the side effects before it must be equal; the deopt counter says whether the compiled path
ran.

Red before the implementation: the IR has no AND / OR / XOR kind and no INVERT, pure shifts are
skipped, so the module's functions are all in `skipped` and the structural tests fail.
"""
from __future__ import annotations

import random
import textwrap

import pytest

from typedpython import frontend, ir

from test_native_int_impure import build_source

SOURCE = textwrap.dedent('''


    @compiled
    def p_and(a: int, b: int) -> int:
        return a & b


    @compiled
    def p_or(a: int, b: int) -> int:
        return a | b


    @compiled
    def p_xor(a: int, b: int) -> int:
        return a ^ b


    @compiled
    def p_inv(a: int) -> int:
        return ~a


    @compiled
    def p_shl(a: int, b: int) -> int:
        return a << b


    @compiled
    def p_shr(a: int, b: int) -> int:
        return a >> b


    @compiled
    def p_aug(a: int, b: int) -> int:
        x: int = a
        x ^= b
        x |= 1
        x &= ~b
        return x


    @compiled
    def p_augshl(a: int, b: int) -> int:
        x: int = a
        x <<= b
        return x


    @compiled
    def p_augshr(a: int, b: int) -> int:
        x: int = a
        x >>= b
        return x


    @compiled
    def p_mix(a: int, b: int) -> int:
        return (((a & 255) << 3) | (b >> 2)) ^ ~a


    @compiled
    def p_mask_loop(n: int) -> int:
        h: int = 0
        for i in range(n):
            h = ((h << 5) ^ (h >> 2) ^ i) & 0xFFFFFFFF
        return h


    @compiled
    def p_array(xs: list[int]) -> None:
        for i in range(len(xs)):
            xs[i] &= 0xFF
            xs[i] |= 256
            xs[i] ^= i


    @compiled
    def p_iv(a: int, b: int) -> int:
        return (a & 0xFFFF) * (b & 0xFFFF) + ~(a & 7)


    @compiled
    def i_and(log: list[object], a: int, b: int) -> object:
        log.append("start")
        r: int = a & b
        log.append(r)
        return r


    @compiled
    def i_or(log: list[object], a: int, b: int) -> object:
        log.append("start")
        r: int = a | b
        log.append(r)
        return r


    @compiled
    def i_xor(log: list[object], a: int, b: int) -> object:
        log.append("start")
        r: int = a ^ b
        log.append(r)
        return r


    @compiled
    def i_inv(log: list[object], a: int) -> object:
        log.append("start")
        r: int = ~a
        log.append(r)
        return r


    @compiled
    def i_shl(log: list[object], a: int, b: int) -> object:
        log.append("start")
        r: int = a << b
        log.append(r)
        return r


    @compiled
    def i_shr(log: list[object], a: int, b: int) -> object:
        log.append("start")
        r: int = a >> b
        log.append(r)
        return r


    @compiled
    def i_aug(log: list[object], a: int, b: int) -> object:
        log.append("start")
        x: int = a
        x <<= b
        x ^= a
        x |= 3
        x &= x - 1
        x >>= 1
        log.append(x)
        return x


    @compiled
    def i_big(log: list[object], a: int, b: int, c: int) -> object:
        log.append("start")
        r: int = (a * b) & c
        s: int = (a * b) | c
        t: int = ~(a * b)
        u: int = (a * b) ^ (c << 70)
        log.append(r)
        log.append(s)
        log.append(t)
        log.append(u)
        return r


    @compiled
    def o_ops(log: list[object], x: object, y: object) -> object:
        log.append("start")
        r: object = x | y
        r = r & y
        r ^= x
        return r


    @compiled
    def o_inplace(log: list[object], x: object, y: object) -> object:
        log.append("start")
        x |= y
        return x


    @compiled
    def f_and(log: list[object], a: float, b: int) -> object:
        log.append("start")
        return a & b


    @compiled
    def b_and(a: bool, b: bool) -> bool:
        return a & b


    @compiled
    def b_inv(a: bool) -> int:
        return ~a
''')

PURE = {"p_and", "p_or", "p_xor", "p_inv", "p_shl", "p_shr", "p_aug", "p_augshl", "p_augshr",
        "p_mix", "p_mask_loop", "p_iv"}
IMPURE_ARRAY = {"p_array"}   # stores into a parameter list: an effect, so not pure
IMPURE = {"i_and", "i_or", "i_xor", "i_inv", "i_shl", "i_shr", "i_aug", "i_big", "o_ops",
          "o_inplace"}
I64_MIN, I64_MAX = -2**63, 2**63 - 1

_rng = random.Random(289)
VALUES = [0, 1, -1, 2, -2, 5, 255, 2**31, -2**31, 2**32 + 7, 2**62 - 1, 2**62, -2**62, I64_MAX,
          I64_MAX - 1, I64_MIN, I64_MIN + 1] + [_rng.randint(I64_MIN, I64_MAX) for _ in range(12)]
COUNTS = [0, 1, 2, 31, 62, 63, 64, 65, 1000, -1, -2, -63, -64, -1000]


@pytest.fixture(scope="module")
def built(tmp_path_factory):
    d = tmp_path_factory.mktemp("bitwise")
    return build_source(d, SOURCE, "bitmod")


def fn_of(built, name):
    return next(f for f in built[0].functions if f.name == name)


def deopts(m):
    return m.__typedpython_deopts__


def run(fn, args, log=False):
    lg: list = []
    try:
        res = ("ok", fn(lg, *args) if log else fn(*args))
    except BaseException as e:  # noqa: BLE001 - the type and the message are observable
        res = ("raise", type(e).__name__, str(e))
    return (res, [(type(x).__name__, x) for x in lg]) if log else res


def differential(m, name, args, log=False, deopt=False):
    """compiled == interpreted; the deopt counter moved exactly when `deopt` says so."""
    before = deopts(m)
    c = run(getattr(m, name), args, log)
    moved = deopts(m) - before
    i = run(m.__typedpython_interpreted__[name], args, log)
    assert c == i, (name, args, c, i)
    assert moved == (1 if deopt else 0), f"{name}{tuple(args)}: deopt counter moved {moved}, " \
                                          f"expected {int(deopt)}"
    return c


def shl_deopts(a, n):
    return n < 0 or not (I64_MIN <= a << n <= I64_MAX) if n < 4000 else a != 0


# --- structure ---------------------------------------------------------------------------------

def test_every_function_compiles_with_the_right_purity(built):
    names = {f.name for f in built[0].functions}
    assert PURE | IMPURE | IMPURE_ARRAY <= names, built[0].skipped
    for n in PURE:
        assert fn_of(built, n).pure is True, n
    for n in IMPURE:
        assert fn_of(built, n).pure is False, n
    assert not built[1], built[1]            # the verifier proved all of it


def test_bool_operands_are_not_supported(built):
    """`True & False` is a bool and `~True` is -2 in Python: the subset skips them."""
    names = {f.name for f in built[0].functions}
    assert "b_and" not in names and "b_inv" not in names
    assert "bool" in built[0].skipped["b_and"] and "bool" in built[0].skipped["b_inv"]


def test_bitwise_nodes_exist_in_the_ir(built):
    kinds = {k.name for k in ir.BinOpKind}
    assert {"AND", "OR", "XOR"} <= kinds and "INVERT" in {k.name for k in ir.UnaryOpKind}


# --- pure functions ----------------------------------------------------------------------------

@pytest.mark.parametrize("name", ["p_and", "p_or", "p_xor", "p_aug", "p_mix"])
def test_pure_bitwise_never_deopts_and_equals_python(built, name):
    m = built[3]
    for a in VALUES:
        for b in VALUES:
            differential(m, name, [a, b])


def test_pure_invert(built):
    m = built[3]
    for a in VALUES:
        differential(m, "p_inv", [a])
    assert m.p_inv(I64_MIN) == I64_MAX and m.p_inv(I64_MAX) == I64_MIN


def test_pure_right_shift_is_arithmetic_floor(built):
    m = built[3]
    for a in VALUES:
        for n in COUNTS:
            for name in ("p_shr", "p_augshr"):
                differential(m, name, [a, n], deopt=n < 0)
    assert m.p_shr(-5, 1) == -3 and m.p_shr(-1, 64) == -1 and m.p_shr(5, 64) == 0
    assert m.p_shr(I64_MIN, 63) == -1 and m.p_shr(I64_MAX, 1000) == 0


def test_pure_left_shift_overflow_deopts_to_the_big_int(built):
    m = built[3]
    for a in VALUES:
        for n in COUNTS:
            for name in ("p_shl", "p_augshl"):
                differential(m, name, [a, n], deopt=shl_deopts(a, n))
    # in range stays compiled, including the boundary and a negative operand
    assert differential(m, "p_shl", [1, 62]) == ("ok", 2**62)
    assert differential(m, "p_shl", [-1, 63]) == ("ok", I64_MIN)
    assert differential(m, "p_shl", [0, 1000]) == ("ok", 0)
    # past the boundary the interpreter's big int comes back
    assert differential(m, "p_shl", [1, 63], deopt=True) == ("ok", 2**63)
    assert differential(m, "p_shl", [1, 64], deopt=True) == ("ok", 2**64)
    assert differential(m, "p_shl", [I64_MIN, 1], deopt=True) == ("ok", -2**64)
    assert differential(m, "p_shl", [3, 1000], deopt=True) == ("ok", 3 << 1000)


def test_pure_negative_shift_count_raises_value_error(built):
    m = built[3]
    for name in ("p_shl", "p_shr", "p_augshl", "p_augshr"):
        for a in (0, 1, -1, I64_MAX):
            res = differential(m, name, [a, -1], deopt=True)
            assert res == ("raise", "ValueError", "negative shift count")


def test_pure_loop_with_mask_stays_compiled(built):
    m = built[3]
    for n in (0, 1, 7, 100, 5000):
        differential(m, "p_mask_loop", [n])


def test_pure_array_elements(built):
    m = built[3]
    for xs in ([], [1, 2, 3], [-1, 255, 256, I64_MAX, I64_MIN], [_rng.randint(I64_MIN, I64_MAX) for _ in range(40)]):
        a, b = list(xs), list(xs)
        before = deopts(m)
        m.p_array(a)
        m.__typedpython_interpreted__["p_array"](b)
        assert a == b and deopts(m) == before


# --- impure functions --------------------------------------------------------------------------

@pytest.mark.parametrize("name", ["i_and", "i_or", "i_xor"])
def test_impure_bitwise_equals_python(built, name):
    m = built[3]
    for a in VALUES:
        for b in VALUES:
            differential(m, name, [a, b], log=True)


def test_impure_invert(built):
    m = built[3]
    for a in VALUES:
        differential(m, "i_inv", [a], log=True)


@pytest.mark.parametrize("name", ["i_shl", "i_shr", "i_aug"])
def test_impure_shifts_promote_instead_of_deopting(built, name):
    m = built[3]
    for a in VALUES:
        for n in COUNTS:
            differential(m, name, [a, n], log=True)       # deopt counter must not move
    res, log = differential(m, "i_shl", [1, 63], log=True)
    assert res == ("ok", 2**63) and log == [("str", "start"), ("int", 2**63)]
    res, log = differential(m, "i_shl", [3, 1000], log=True)
    assert res == ("ok", 3 << 1000)
    assert differential(m, "i_shl", [0, 1000], log=True)[0] == ("ok", 0)


def test_impure_negative_count_raises_after_the_side_effect(built):
    m = built[3]
    for name in ("i_shl", "i_shr"):
        res, log = differential(m, name, [5, -3], log=True)
        assert res == ("raise", "ValueError", "negative shift count")
        assert log == [("str", "start")]


def test_int_operands_that_left_i64(built):
    m = built[3]
    for a in (0, 1, -1, 3, 2**40, -2**40, I64_MAX, I64_MIN):
        for b in (0, 1, -1, 7, 2**40, I64_MAX, I64_MIN):
            for c in (0, 1, -1, 255, I64_MAX, I64_MIN):
                differential(m, "i_big", [a, b, c], log=True)


def test_object_operands_use_the_object_operators(built):
    m = built[3]
    for x, y in (({1, 2}, {2, 3}), (5, 3), (True, False), (b"a", b"b"), ({"a": 1}, {"a": 2})):
        a1, a2 = (x.copy() if hasattr(x, "copy") else x), (x.copy() if hasattr(x, "copy") else x)
        c = run(m.o_ops, [a1, y], log=True)
        i = run(m.__typedpython_interpreted__["o_ops"], [a2, y], log=True)
        assert c == i
    s1, s2 = {1}, {1}
    assert m.o_inplace([], s1, {2}) is s1 and s1 == {1, 2}      # `|=` on a set is in place
    assert m.__typedpython_interpreted__["o_inplace"]([], s2, {2}) is s2


def test_float_operand_raises_type_error_as_python(built):
    m = built[3]
    name = "f_and"
    if name in {f.name for f in built[0].functions}:
        c = run(getattr(m, name), [1.5, 2], log=True)
        assert c == run(m.__typedpython_interpreted__[name], [1.5, 2], log=True)
        assert c[0][0] == "raise" and c[0][1] == "TypeError"


def test_masked_operands_are_proved_free_of_overflow(built):
    """`x & nonneg` is in [0, nonneg] and ~ is exact, so the products below cannot deopt."""
    f = fn_of(built, "p_iv")
    assert f.may_deopt is False
    m = built[3]
    for a in VALUES:
        for b in VALUES:
            differential(m, "p_iv", [a, b])
