"""Differential tests of tp_runtime.h against CPython (design §4.3, #41).

`runtime_shim/tp_shim.c` is a tiny extension that exposes every helper of
`typedpython/runtime/tp_runtime.h`; each test calls the helper and CPython's own operation on the
same inputs and compares the outcome exactly:

  * results bit-for-bit (floats compared by their IEEE bits, so -0.0 and 0.0 differ; NaN is
    compared as "any NaN", payload and sign of a NaN are not part of the contract);
  * exceptions by exact type AND message, taken from the interpreter running the test (so the
    3.13 and 3.14 messages are each checked against the real thing);
  * the return convention: 0 ok / 1 deopt / -1 error. The shim turns a convention violation (-1
    without an exception, 0/1 with an exception set) into AssertionError.

Deopt (rc 1) is allowed only where API.md names it; each test says when it expects it.

Environment: set TP_SANITIZE=1 (with DYLD_INSERT_LIBRARIES pointing at the ASan runtime on macOS,
or LD_PRELOAD on Linux) to build the shim with AddressSanitizer + UBSan.
"""
from __future__ import annotations

import importlib.util
import itertools
import math
import operator
import os
import random
import shlex
import subprocess
import struct
import sys
import sysconfig
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
KSP = HERE.parents[2]
REPO_ROOT = KSP.parent
RUNTIME_DIR = KSP / "src" / "main" / "python" / "typedpython" / "runtime"
SHIM_C = HERE / "runtime_shim" / "tp_shim.c"
BUILD_DIR = REPO_ROOT / ".tmp" / "runtime_shim_build"
HEAVY = REPO_ROOT.parent.parent / ".tmp" / "heavy.sh"

I64_MIN = -(2**63)
I64_MAX = 2**63 - 1
INF = math.inf
NAN = math.nan


# --- building the shim ------------------------------------------------------------------------

def _lock_already_held() -> bool:
    """True if an ancestor process is heavy.sh (the whole pytest run then already holds the lock;
    taking it again would deadlock)."""
    pid = os.getpid()
    for _ in range(40):
        out = subprocess.run(["ps", "-o", "ppid=,command=", "-p", str(pid)],
                             capture_output=True, text=True).stdout.strip()
        if not out:
            return False
        ppid, _, cmd = out.partition(" ")
        if "heavy.sh" in cmd:
            return True
        pid = int(ppid)
        if pid <= 1:
            return False
    return False


def _run_compile(cmd: list[str]) -> None:
    if HEAVY.exists() and not _lock_already_held():
        cmd = [str(HEAVY)] + cmd
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError("shim build failed:\n" + " ".join(cmd) + "\n" + proc.stdout + proc.stderr)


def _build(variant: str):
    suffix = sysconfig.get_config_var("EXT_SUFFIX")
    sanitize = os.environ.get("TP_SANITIZE") == "1"
    name = f"tp_shim_{variant}" + ("_san" if sanitize else "")
    BUILD_DIR.mkdir(parents=True, exist_ok=True)
    out = BUILD_DIR / (name + suffix)
    cc = shlex.split(sysconfig.get_config_var("CC") or "cc")
    cmd = cc + ["-std=c99", "-O1", "-g", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter",
                "-shared", "-fPIC", f"-DTP_SHIM_NAME={name}",
                "-I", sysconfig.get_paths()["include"], "-I", str(RUNTIME_DIR)]
    if variant == "portable":
        cmd.append("-DTP_RUNTIME_FORCE_PORTABLE_OVERFLOW")
    if sanitize:
        cmd += ["-fsanitize=address,undefined", "-fno-sanitize-recover=undefined",
                "-fno-omit-frame-pointer"]
        res = subprocess.run(cc + ["-print-resource-dir"], capture_output=True, text=True).stdout.strip()
        if sys.platform == "darwin" and res:
            cmd += ["-Wl,-rpath," + res + "/lib/darwin"]
    if sys.platform == "darwin":
        cmd += ["-undefined", "dynamic_lookup"]
    cmd += [str(SHIM_C), "-o", str(out)]
    _run_compile(cmd)
    spec = importlib.util.spec_from_file_location(name, out)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


@pytest.fixture(scope="session", params=["default", "portable"])
def rt(request):
    """The compiled shim; built once per variant per session. `portable` forces the fallback for
    the overflow builtins so both code paths of the header are exercised."""
    mod = _build(request.param)
    info = mod.info()
    assert bool(info["portable"]) == (request.param == "portable")
    return mod


# --- comparison helpers -----------------------------------------------------------------------

def norm(v):
    if isinstance(v, bool):
        return ("b", v)
    if isinstance(v, float):
        return ("nan",) if v != v else ("f", struct.pack("<d", v).hex())
    if isinstance(v, int):
        return ("i", v)
    if isinstance(v, complex):
        return ("c", repr(v))
    return ("o", v)


def py(fn, *a):
    try:
        return norm(fn(*a))
    except Exception as e:  # noqa: BLE001 - the exception IS the result
        return ("exc", type(e), str(e))


def sh(fn, *a):
    """Outcome of a shim helper returning (rc, value)."""
    try:
        rc, v = fn(*a)
    except Exception as e:  # noqa: BLE001
        return ("exc", type(e), str(e))
    if rc == 1:
        return ("deopt",)
    assert rc == 0, rc
    return norm(v)


def check(cases, label):
    bad = [(args, exp, got) for args, exp, got in cases if exp != got]
    assert not bad, f"{label}: {len(bad)} of {len(cases)} mismatches, first 8:\n" + "\n".join(
        f"  {a!r}\n    CPython: {e!r}\n    runtime: {g!r}" for a, e, g in bad[:8])


def fits(v) -> bool:
    return I64_MIN <= v <= I64_MAX


INT_VALUES = sorted({0, 1, -1, 2, -2, 7, -7, 2**31, -(2**31), 2**31 - 1, 2**32, 2**53, -(2**53),
                     2**53 + 1, -(2**53) - 1, 2**53 - 1, 2**62, -(2**62), 2**62 + 1, I64_MIN,
                     I64_MAX, I64_MIN + 1, I64_MAX - 1, 3, -3, 10, -10, 1000003, -1000003})

FLOAT_VALUES = [0.0, -0.0, 1.0, -1.0, 1.5, -1.5, 0.5, -0.5, 2.5, -2.5, 3.0, -3.0, 7.0, 0.1, -0.1,
                INF, -INF, NAN, 5e-324, -5e-324, 2.2250738585072014e-308, 1e-300, -1e-300, 1e300,
                -1e300, 1.7976931348623157e308, -1.7976931348623157e308, 2.0**53, -(2.0**53),
                2.0**63, -(2.0**63), 1e22, 4.5e15]


# --- ints -------------------------------------------------------------------------------------

@pytest.mark.parametrize("name,pyop", [("add_i64", operator.add), ("sub_i64", operator.sub),
                                       ("mul_i64", operator.mul)])
def test_checked_arithmetic_matches_bigint_and_deopts_exactly_on_overflow(rt, name, pyop):
    fn = getattr(rt, name)
    cases = []
    for a, b in itertools.product(INT_VALUES, repeat=2):
        exact = pyop(a, b)
        cases.append(((a, b), ("i", exact) if fits(exact) else ("deopt",), sh(fn, a, b)))
    check(cases, name)


def test_neg_i64_deopts_only_for_int64_min(rt):
    cases = []
    for a in INT_VALUES:
        cases.append(((a,), ("i", -a) if fits(-a) else ("deopt",), sh(rt.neg_i64, a)))
    check(cases, "neg_i64")
    assert sh(rt.neg_i64, I64_MIN) == ("deopt",)


def test_fuzz_checked_arithmetic_against_bigint(rt):
    rnd = random.Random(20261003)
    cases = []
    for _ in range(4000):
        bits_a, bits_b = rnd.choice([8, 31, 32, 53, 62, 63]), rnd.choice([8, 31, 32, 53, 62, 63])
        a = rnd.randint(-(2**bits_a), 2**bits_a)
        b = rnd.randint(-(2**bits_b), 2**bits_b)
        a, b = max(I64_MIN, min(I64_MAX, a)), max(I64_MIN, min(I64_MAX, b))
        for name, pyop in (("add_i64", operator.add), ("sub_i64", operator.sub),
                           ("mul_i64", operator.mul)):
            exact = pyop(a, b)
            cases.append(((name, a, b), ("i", exact) if fits(exact) else ("deopt",),
                          sh(getattr(rt, name), a, b)))
    check(cases, "fuzz")


def test_floordiv_i64_is_python_floor_division(rt):
    cases = []
    for a, b in itertools.product(INT_VALUES, repeat=2):
        ref = py(operator.floordiv, a, b)
        if ref[0] == "i" and not fits(ref[1]):
            ref = ("deopt",)  # only MIN // -1
        cases.append(((a, b), ref, sh(rt.floordiv_i64, a, b)))
    check(cases, "floordiv_i64")
    assert sh(rt.floordiv_i64, I64_MIN, -1) == ("deopt",)


def test_mod_i64_has_the_sign_of_the_divisor(rt):
    cases = [((a, b), py(operator.mod, a, b), sh(rt.mod_i64, a, b))
             for a, b in itertools.product(INT_VALUES, repeat=2)]
    check(cases, "mod_i64")
    assert sh(rt.mod_i64, -7, 2) == ("i", 1)
    assert sh(rt.mod_i64, 7, -2) == ("i", -1)
    assert sh(rt.mod_i64, I64_MIN, -1) == ("i", 0)  # CPython: 0 (the C % would trap)


def test_int_division_by_zero_messages_are_cpythons(rt):
    for fn, op in ((rt.floordiv_i64, operator.floordiv), (rt.mod_i64, operator.mod),
                   (rt.truediv_i64, operator.truediv)):
        for a in (0, 1, -5, I64_MAX, I64_MIN):
            exp, got = py(op, a, 0), sh(fn, a, 0)
            assert exp[0] == "exc" and exp[1] is ZeroDivisionError
            assert got == exp, (op, a, exp, got)


def test_fuzz_floordiv_mod_i64(rt):
    rnd = random.Random(7)
    cases = []
    for _ in range(4000):
        a = rnd.randint(-(2**rnd.choice([4, 31, 63])), 2**rnd.choice([4, 31, 63]) - 1)
        b = rnd.randint(-(2**rnd.choice([2, 9, 31, 63])), 2**rnd.choice([2, 9, 31, 63]) - 1)
        a, b = max(I64_MIN, min(I64_MAX, a)), max(I64_MIN, min(I64_MAX, b))
        for name, op in (("floordiv_i64", operator.floordiv), ("mod_i64", operator.mod)):
            ref = py(op, a, b)
            if ref[0] == "i" and not fits(ref[1]):
                ref = ("deopt",)
            cases.append(((name, a, b), ref, sh(getattr(rt, name), a, b)))
    check(cases, "fuzz floordiv/mod")


TRUEDIV_INTS = INT_VALUES + [2**53 - 2, 2**53 + 2, -(2**53) + 1, 2**53 + 3, 9007199254740993, 5,
                             -5, 6, 49, 355, 113]


def test_truediv_i64_is_correctly_rounded_or_deopts_above_2_53(rt):
    cases = []
    for a, b in itertools.product(TRUEDIV_INTS, repeat=2):
        ref = py(operator.truediv, a, b)
        if ref[0] != "exc" and (abs(a) > 2**53 or abs(b) > 2**53):
            ref = ("deopt",)
        cases.append(((a, b), ref, sh(rt.truediv_i64, a, b)))
    check(cases, "truediv_i64")
    # the boundary itself is exact-rounded by the C division
    assert sh(rt.truediv_i64, 2**53, 3) == py(operator.truediv, 2**53, 3)
    assert sh(rt.truediv_i64, 2**53 + 1, 3) == ("deopt",)
    assert sh(rt.truediv_i64, 1, -(2**53) - 1) == ("deopt",)
    assert sh(rt.truediv_i64, 0, -5) == ("f", struct.pack("<d", -0.0).hex())  # -0.0, as CPython


def test_fuzz_truediv_i64(rt):
    rnd = random.Random(11)
    cases = []
    for _ in range(4000):
        a = rnd.randint(-(2**53), 2**53)
        b = rnd.randint(-(2**rnd.choice([3, 20, 53])), 2**rnd.choice([3, 20, 53]))
        cases.append(((a, b), py(operator.truediv, a, b), sh(rt.truediv_i64, a, b)))
    check(cases, "fuzz truediv")


def test_i64_to_f64_rounds_half_to_even_like_float_of_int(rt):
    rnd = random.Random(3)
    vals = list(INT_VALUES)
    for e in range(53, 63):
        base = 2**e
        vals += [base + k for k in range(-4, 9)] + [-(base + k) for k in range(-4, 9)]
        vals += [base + 2**(e - 52) // 2 + d for d in (-1, 0, 1)]  # halfway points
    vals += [rnd.randint(2**53, I64_MAX) for _ in range(20000)]
    vals += [rnd.randint(I64_MIN, -(2**53)) for _ in range(20000)]
    vals = [v for v in vals if fits(v)]
    cases = [((v,), norm(float(v)), norm(rt.i64_to_f64(v))) for v in vals]
    check(cases, "i64_to_f64")


# --- floats -----------------------------------------------------------------------------------

@pytest.mark.parametrize("name,op", [("truediv_f64", operator.truediv),
                                     ("floordiv_f64", operator.floordiv),
                                     ("mod_f64", operator.mod)])
def test_float_division_family_is_bit_exact_with_cpython(rt, name, op):
    fn = getattr(rt, name)
    cases = [((a, b), py(op, a, b), sh(fn, a, b))
             for a, b in itertools.product(FLOAT_VALUES, repeat=2)]
    check(cases, name)


@pytest.mark.parametrize("name,op", [("truediv_f64", operator.truediv),
                                     ("floordiv_f64", operator.floordiv),
                                     ("mod_f64", operator.mod)])
def test_float_division_by_signed_zero_raises_cpythons_message(rt, name, op):
    for zero in (0.0, -0.0):
        for a in (1.0, -1.0, 0.0, -0.0, INF, NAN, 1e300):
            exp, got = py(op, a, zero), sh(getattr(rt, name), a, zero)
            assert exp[0] == "exc" and exp[1] is ZeroDivisionError
            assert got == exp, (name, a, zero, exp, got)


@pytest.mark.parametrize("name,op", [("floordiv_f64", operator.floordiv), ("mod_f64", operator.mod)])
def test_fuzz_float_floordiv_mod(rt, name, op):
    rnd = random.Random(5)
    cases = []
    for _ in range(6000):
        a = rnd.uniform(-1, 1) * 10.0 ** rnd.randint(-5, 20)
        b = rnd.uniform(-1, 1) * 10.0 ** rnd.randint(-5, 20)
        if rnd.random() < 0.25:
            b = float(rnd.randint(-9, 9)) or 1.0
        if rnd.random() < 0.1:
            a = float(rnd.randint(-100, 100))
        cases.append(((a, b), py(op, a, b), sh(getattr(rt, name), a, b)))
    check(cases, name)


def test_floor_mod_sign_and_zero_cases_spelled_out(rt):
    nz = struct.pack("<d", -0.0).hex()
    pz = struct.pack("<d", 0.0).hex()
    assert sh(rt.mod_f64, -1.0, 1.0) == ("f", pz)         # fmod = -0.0 -> +0.0 (sign of divisor)
    assert sh(rt.mod_f64, 1.0, -1.0) == ("f", nz)
    assert sh(rt.mod_f64, -7.5, 2.0) == norm(-7.5 % 2.0)
    assert sh(rt.floordiv_f64, -0.0, 5.0) == norm(-0.0 // 5.0)
    assert sh(rt.floordiv_f64, 1.0, -INF) == norm(1.0 // -INF)


# --- int/float exact comparison ---------------------------------------------------------------

OPS = [(0, operator.lt), (1, operator.le), (2, operator.eq), (3, operator.ne), (4, operator.gt),
       (5, operator.ge)]
CMP_INTS = INT_VALUES + [2**53 + 2, 2**63 - 1024, -(2**63) + 1024, 2**63 - 513, 9223372036854774784,
                         4503599627370497, 9007199254740992]
CMP_FLOATS = FLOAT_VALUES + [float(2**53), float(2**53 + 2), 9.223372036854776e18,
                             -9.223372036854776e18, 9.223372036854775e18, 9223372036854774784.0,
                             0.1 + 0.2, 2.0**62, 4503599627370496.5, -4503599627370496.5,
                             9007199254740993.0, 1e19, -1e19, 2.0**63 - 1024]


def test_int_float_comparison_is_exact_for_every_operator(rt):
    assert op_name_sanity()
    cases = []
    for a, b in itertools.product(CMP_INTS, CMP_FLOATS):
        for code, pyop in OPS:
            cases.append(((a, b, code), int(pyop(a, b)), rt.cmp_i64_f64(a, b, code)))
            cases.append(((b, a, code), int(pyop(b, a)), rt.cmp_f64_i64(b, a, code)))
    check(cases, "cmp")


def op_name_sanity():
    import _operator  # noqa: F401
    return True


def test_int_float_comparison_named_hard_cases(rt):
    # 2**53 + 1 is not equal to float(2**53), although (double)(2**53+1) == 2**53
    assert rt.cmp_i64_f64(2**53 + 1, float(2**53), 2) == 0
    assert rt.cmp_i64_f64(2**53 + 1, float(2**53), 4) == 1
    assert rt.cmp_i64_f64(I64_MAX, 9.223372036854776e18, 0) == 1   # INT64_MAX < 2**63
    assert rt.cmp_i64_f64(I64_MAX, 9.223372036854776e18, 2) == 0
    assert rt.cmp_i64_f64(I64_MIN, -9.223372036854776e18, 2) == 1  # -2**63 == -2.0**63
    for code, _ in OPS:
        assert rt.cmp_i64_f64(1, NAN, code) == (1 if code == 3 else 0)
        assert rt.cmp_f64_i64(NAN, 1, code) == (1 if code == 3 else 0)
    assert rt.cmp_i64_f64(I64_MAX, INF, 0) == 1 and rt.cmp_i64_f64(I64_MIN, -INF, 4) == 1


# --- math -------------------------------------------------------------------------------------

MATH1 = ["sqrt", "exp", "log", "sin", "cos", "tan", "fabs"]
MATH_VALUES = FLOAT_VALUES + [1000.0, -1000.0, 709.0, 709.782712893384, 709.7827128933841, -745.0,
                              -746.0, -1e5, 1e5, 0.5 * math.pi, math.pi, 1e22, 1e15, 100.0,
                              -100.0, 710.0]


@pytest.mark.parametrize("name", MATH1)
def test_math_one_argument_matches_math_module_including_messages(rt, name):
    ref_fn = getattr(math, name)
    cases = [((x,), py(ref_fn, x), sh(rt.math1, name, x)) for x in MATH_VALUES]
    check(cases, "math." + name)


@pytest.mark.parametrize("name", ["sqrt", "exp", "log", "sin", "cos", "tan"])
def test_math_one_argument_fuzz_is_bit_exact(rt, name):
    rnd = random.Random(hash(name) & 0xFFFF)
    ref_fn = getattr(math, name)
    xs = [rnd.uniform(-1, 1) * 10.0 ** rnd.randint(-10, 10) for _ in range(3000)]
    cases = [((x,), py(ref_fn, x), sh(rt.math1, name, x)) for x in xs]
    check(cases, "fuzz math." + name)


def test_math_named_error_cases(rt):
    # the rows named in the task, spelled out: type+message are whatever this interpreter says
    for name, x in [("sqrt", -1.0), ("sqrt", -0.0), ("exp", 1000.0), ("log", 0.0), ("log", -1.0),
                    ("log", -INF), ("sin", INF), ("cos", -INF), ("tan", INF), ("sqrt", -INF)]:
        exp, got = py(getattr(math, name), x), sh(rt.math1, name, x)
        assert got == exp, (name, x, exp, got)
    assert sh(rt.math1, "sqrt", -0.0) == ("f", struct.pack("<d", -0.0).hex())
    assert py(math.exp, 1000.0)[:2] == ("exc", OverflowError)
    assert py(math.exp, 1000.0)[2] == "math range error"


@pytest.mark.parametrize("name", ["atan2", "hypot"])
def test_math_two_argument_matches_math_module(rt, name):
    ref_fn = getattr(math, name)
    cases = [((x, y), py(ref_fn, x, y), sh(rt.math2, name, x, y))
             for x, y in itertools.product(MATH_VALUES, repeat=2)]
    check(cases, "math." + name)
    assert sh(rt.math2, "hypot", INF, NAN) == py(math.hypot, INF, NAN) == norm(INF) or True
    assert sh(rt.math2, "hypot", INF, NAN) == ("f", struct.pack("<d", INF).hex())
    for sx, sy in itertools.product((1.0, -1.0, 0.0, -0.0), repeat=2):
        assert sh(rt.math2, "atan2", math.copysign(1.0, sy) * abs(sy), math.copysign(1.0, sx) * abs(sx)) \
            == py(math.atan2, math.copysign(1.0, sy) * abs(sy), math.copysign(1.0, sx) * abs(sx))


@pytest.mark.parametrize("name", ["atan2", "hypot"])
def test_math_two_argument_fuzz_is_bit_exact(rt, name):
    rnd = random.Random(99 + len(name))
    ref_fn = getattr(math, name)
    cases = []
    for _ in range(6000):
        x = rnd.uniform(-1, 1) * 10.0 ** rnd.randint(-320, 307)
        y = rnd.uniform(-1, 1) * 10.0 ** rnd.randint(-320, 307)
        if rnd.random() < 0.3:
            y = x * rnd.uniform(0.9, 1.1)
        cases.append(((x, y), py(ref_fn, x, y), sh(rt.math2, name, x, y)))
    check(cases, "fuzz math." + name)


# --- boxing -----------------------------------------------------------------------------------

def test_box_returns_exact_types_and_values(rt):
    for v in INT_VALUES:
        r = rt.box_i64(v)
        assert type(r) is int and r == v
    for v in FLOAT_VALUES:
        r = rt.box_f64(v)
        assert type(r) is float and norm(r) == norm(v)
    assert rt.box_bool(1) is True and rt.box_bool(0) is False
    assert rt.box_bool(-5) is True and rt.box_bool(2) is True


class _I(int):
    pass


class _F(float):
    pass


def test_unbox_is_exact_type_only(rt):
    for v in INT_VALUES:
        assert rt.unbox_i64(v) == (0, v)
    assert rt.unbox_i64(2**63) == (1, None)
    assert rt.unbox_i64(-(2**63) - 1) == (1, None)
    assert rt.unbox_i64(10**30) == (1, None)
    assert rt.unbox_i64(True) == (1, None)        # bool is not exactly int
    assert rt.unbox_i64(_I(5)) == (1, None)
    assert rt.unbox_i64(1.0) == (1, None)
    assert rt.unbox_i64("1") == (1, None) and rt.unbox_i64(None) == (1, None)
    for v in FLOAT_VALUES:
        rc, out = rt.unbox_f64(v)
        assert rc == 0 and norm(out) == norm(v)
    assert rt.unbox_f64(_F(1.5)) == (1, None)
    assert rt.unbox_f64(1) == (1, None) and rt.unbox_f64(True) == (1, None)
    assert rt.unbox_f64(None) == (1, None)
    assert rt.unbox_bool(True) == (0, 1) and rt.unbox_bool(False) == (0, 0)
    assert rt.unbox_bool(1) == (1, None) and rt.unbox_bool(0) == (1, None)
    assert rt.unbox_bool(None) == (1, None) and rt.unbox_bool("x") == (1, None)


# --- arrays -----------------------------------------------------------------------------------

class _L(list):
    pass


def _fl(*xs):
    return [float(repr(x)) for x in xs]  # fresh float objects (not shared constants)


def _il(*xs):
    return [int(str(x)) for x in xs]


def _ref_slot(lst, i, store):
    try:
        if store:
            lst[i] = lst[i] if False else None  # placeholder, replaced below
        else:
            lst[i]
    except IndexError as e:
        return ("exc", type(e), str(e))
    return ("ok",)


def _exc_of(r):
    return ("exc", type(r), str(r)) if isinstance(r, BaseException) else ("ok",)


@pytest.mark.parametrize("kind,make", [("f64", lambda *x: _fl(*x)), ("i64", lambda *x: _il(*x))])
def test_array_index_resolution_matches_list_semantics(rt, kind, make):
    run = getattr(rt, f"{kind}_array_run")
    lst = make(10, 20, 30)
    idxs = [0, 1, 2, 3, 4, -1, -2, -3, -4, -5, 100, -100, I64_MAX, I64_MIN, I64_MIN + 1, 2**40]
    script = [("get", i) for i in idxs] + [("set", i, 1) for i in idxs]
    scratch = list(lst)
    erc, results, xrc, xerr, again = run(lst, script)
    assert (erc, xrc, xerr, again) == (0, 0, None, 0)
    ref = []
    for i in idxs:
        try:
            ref.append(("val", scratch[i]))
        except IndexError as e:
            ref.append(("exc", type(e), str(e)))
    for i in idxs:
        try:
            scratch[i] = 1
            ref.append(("set",))
        except IndexError as e:
            ref.append(("exc", type(e), str(e)))
    got = []
    for r in results:
        if isinstance(r, BaseException):
            got.append(("exc", type(r), str(r)))
        elif r is None:
            got.append(("set",))
        else:
            got.append(("val", r))
    assert got == ref
    # the loads/stores that raised carry the two distinct messages
    msgs = {(g[1], g[2]) for g in got if g[0] == "exc"}
    assert msgs == {(IndexError, "list index out of range"),
                    (IndexError, "list assignment index out of range")}


@pytest.mark.parametrize("kind,make", [("f64", _fl), ("i64", _il)])
def test_array_empty_list(rt, kind, make):
    run = getattr(rt, f"{kind}_array_run")
    erc, results, xrc, xerr, again = run([], [("len",), ("get", 0), ("set", 0, 1), ("get", -1)])
    assert (erc, xrc, xerr, again) == (0, 0, None, 0)
    assert results[0] == 0
    assert [(type(r), str(r)) for r in results[1:]] == [
        (IndexError, "list index out of range"), (IndexError, "list assignment index out of range"),
        (IndexError, "list index out of range")]


def test_f64_array_enter_deopts_unless_exact_list_of_exact_floats(rt):
    cases = {
        "list subclass": _L(_fl(1.0, 2.0)),
        "tuple": (1.0, 2.0),
        "none": None,
        "int in float list": [1.0, 2, 3.0],
        "bool in float list": [1.0, True],
        "float subclass element": [1.0, _F(2.0)],
        "str element": [1.0, "x"],
        "none element": [None],
        "dict": {0: 1.0},
    }
    for name, obj in cases.items():
        before = list(obj) if isinstance(obj, list) else None
        erc, results, xrc, xerr, again = rt.f64_array_run(obj, [("len",)])
        assert erc == 1, name
        assert (results, xrc, xerr, again) == ([], 0, None, 0), name  # exit after deopt is safe
        if before is not None:
            assert list(obj) == before, name


def test_i64_array_enter_deopts_unless_exact_list_of_exact_in_range_ints(rt):
    cases = {
        "list subclass": _L(_il(1, 2)),
        "float in int list": [1, 2.0],
        "bool in int list": [1, True],
        "int subclass": [1, _I(2)],
        "2**63": [1, 2**63],
        "-2**63-1": [-(2**63) - 1],
        "huge": [10**40],
        "tuple": (1, 2),
        "none": None,
    }
    for name, obj in cases.items():
        erc, results, xrc, xerr, again = rt.i64_array_run(obj, [("len",)])
        assert erc == 1, name
        assert (results, xrc, xerr, again) == ([], 0, None, 0), name
    ok = _il(I64_MAX, I64_MIN, 0, -1)
    erc, results, xrc, xerr, again = rt.i64_array_run(ok, [("get", 0), ("get", 1), ("get", 3)])
    assert (erc, results, xrc) == (0, [I64_MAX, I64_MIN, -1], 0)


def test_f64_array_reads_values_bit_exactly_and_writes_back_only_dirty_slots(rt):
    lst = _fl(1.5, 2.5, -0.0, 4.5)
    keep = list(lst)  # same objects
    erc, results, xrc, xerr, again = rt.f64_array_run(
        lst, [("len",), ("get", 2), ("set", 1, 9.0), ("set", -1, NAN), ("dirty",)])
    assert (erc, xrc, xerr, again) == (0, 0, None, 0)
    assert results[0] == 4 and norm(results[1]) == norm(-0.0)
    assert results[3] is None and results[4] == bytes([0, 1, 0, 1])
    assert lst[0] is keep[0] and lst[2] is keep[2]            # untouched slots: same objects
    assert lst[1] == 9.0 and lst[1] is not keep[1]
    assert lst[3] != lst[3] and type(lst[3]) is float
    assert len(lst) == 4


def test_array_write_back_creates_new_objects_even_for_an_equal_value(rt):
    lst = _fl(1.5, 2.5)
    old = lst[1]
    rt.f64_array_run(lst, [("set", 1, 2.5)])
    assert lst[1] == 2.5 and lst[1] is not old                # dirty means replaced
    big = _il(2**40, 2**41)
    oldb = big[0]
    rt.i64_array_run(big, [("set", 0, 2**40)])
    assert big[0] == 2**40 and big[0] is not oldb and big[1] == 2**41


@pytest.mark.parametrize("kind", ["f64", "i64"])
def test_array_write_back_refcounts_are_balanced(rt, kind):
    run = getattr(rt, f"{kind}_array_run")
    lst = _fl(1.5, 2.5, 3.5) if kind == "f64" else _il(2**40, 2**41, 2**42)
    new = 7.5 if kind == "f64" else 2**43
    old = lst[1]
    base_old = sys.getrefcount(old)
    for _ in range(50):
        run(lst, [("set", 1, new)])
        assert sys.getrefcount(lst[1]) == sys.getrefcount(lst[2])  # same as an untouched slot: no leaked ref
        lst[1] = old if _ % 2 == 0 else lst[1]
    run(lst, [("set", 1, new)])
    assert sys.getrefcount(old) <= base_old                   # the replaced object was released


@pytest.mark.parametrize("kind", ["f64", "i64"])
def test_array_exit_failure_path_frees_and_reports_error(rt, kind):
    run = getattr(rt, f"{kind}_array_run")
    lst = _fl(1.0, 2.0) if kind == "f64" else _il(1, 2)
    # the list is emptied while the array is live: writing slot 0 back must fail, not crash
    erc, results, xrc, xerr, again = run(lst, [("set", 0, 5), ("set", 1, 6), ("clear",)])
    assert erc == 0 and xrc == -1 and isinstance(xerr, BaseException) and again == 0
    assert lst == []


@pytest.mark.parametrize("which", [0, 1])
def test_array_exit_on_a_zeroed_struct_is_a_noop(rt, which):
    assert rt.array_exit_zeroed(which) == 0


def test_any_same(rt):
    a, b, c = [], [], []
    assert rt.any_same(()) == 0 and rt.any_same((a,)) == 0
    assert rt.any_same((a, b, c)) == 0
    assert rt.any_same((a, b, a)) == 1 and rt.any_same((a, a)) == 1
    assert rt.any_same((a, b, c, c)) == 1
    x = 5.0
    assert rt.any_same((x, x)) == 1


def test_large_arrays_round_trip(rt):
    n = 100_000
    lst = [float(i) for i in range(n)]
    erc, results, xrc, xerr, again = rt.f64_array_run(
        lst, [("set", i, -float(i)) for i in range(0, n, 997)] + [("len",)])
    assert (erc, xrc) == (0, 0) and results[-1] == n
    assert all(lst[i] == (-float(i) if i % 997 == 0 else float(i)) for i in range(n))


# --- opaque objects ---------------------------------------------------------------------------

def exc_of(fn, *a):
    """Outcome of an operation that returns an arbitrary object: ('ok', value) or the exception's
    type and message (and the NameError.name attribute)."""
    try:
        return ("ok", fn(*a))
    except Exception as e:  # noqa: BLE001
        return ("exc", type(e), str(e), getattr(e, "name", None))


class Weird:
    """An object whose protocol methods do observable things."""

    def __init__(self):
        self.calls = []

    def __bool__(self):
        self.calls.append("bool")
        return True

    def __float__(self):
        return 2.5

    def __lt__(self, other):
        self.calls.append("lt")
        return NotImplemented

    @property
    def boom(self):
        raise RuntimeError("property boom")

    @property
    def tracked(self):
        self.calls.append("tracked")
        return 42


class _Raises:
    def __bool__(self):
        raise ValueError("no truth")

    def __float__(self):
        raise ZeroDivisionError("no float")

    def __eq__(self, other):
        raise KeyError("no eq")

    __hash__ = None


class _ReturnsObj:
    """`==` returns a non-bool whose truth is what the condition sees."""

    def __init__(self, truth):
        self.truth = truth

    def __eq__(self, other):
        return _Tru(self.truth)


class _Tru:
    def __init__(self, t):
        self.t = t

    def __bool__(self):
        return self.t


def test_global_reads_module_globals_then_builtins_with_cpythons_nameerror(rt):
    ns = {"x": 1, "len": "shadowed", "__builtins__": __builtins__}
    exec("pass", ns)  # a real module namespace
    assert rt.global_(ns, "x") == 1
    assert rt.global_(ns, "len") == "shadowed"           # globals first
    assert rt.global_(ns, "print") is print              # then builtins
    assert rt.global_(ns, "int") is int
    for name in ("nope", "y", "N" * 300, "übung", "_"):
        ref = exc_of(eval, name, dict(ns))
        got = exc_of(rt.global_, ns, name)
        assert got == ref and ref[1] is NameError, (name, ref, got)
    assert exc_of(rt.global_, ns, "nope")[2] == "name 'nope' is not defined"
    # looked up at the time of the read: rebinding is seen
    ns["x"] = 2
    assert rt.global_(ns, "x") == 2
    del ns["x"]
    assert exc_of(rt.global_, ns, "x")[1] is NameError
    # a namespace whose __builtins__ is a module, and one without __builtins__ at all
    ns2 = {"__builtins__": __import__("builtins")}
    assert rt.global_(ns2, "sum") is sum
    assert rt.global_({}, "sum") is sum
    assert exc_of(rt.global_, {}, "nope")[1] is NameError


def test_global_returns_a_new_reference(rt):
    marker = object()
    ns = {"m": marker, "__builtins__": {}}
    base = sys.getrefcount(marker)
    for _ in range(2000):
        r = rt.global_(ns, "m")
        del r
        try:
            rt.global_(ns, "missing")
        except NameError:
            pass
    assert sys.getrefcount(marker) == base
    r = rt.global_(ns, "m")
    assert sys.getrefcount(marker) == base + 1
    del r


def test_getattr_is_getattr_including_descriptors_and_errors(rt):
    w = Weird()
    assert rt.getattr_(w, "calls") is w.calls
    assert rt.getattr_(w, "tracked") == 42 and w.calls == ["tracked"]
    for obj, name in [(w, "boom"), (w, "missing"), (1, "real"), (None, "x"), ("s", "upper"),
                      (int, "__name__"), (3.5, "is_integer")]:
        ref, got = exc_of(getattr, obj, name), exc_of(rt.getattr_, obj, name)
        if ref[0] == "ok":   # bound methods compare by (self, func)
            assert got[0] == "ok" and got[1] == ref[1], (obj, name)
        else:
            assert got == ref, (obj, name, ref, got)


def test_call_matches_vectorcall_semantics_positional_and_keywords(rt):
    def f(a, b=10, *, c=100, d=1000):
        return (a, b, c, d)

    assert rt.call(f, (1,), None) == f(1)
    assert rt.call(f, (1, 2), None) == f(1, 2)
    assert rt.call(f, (1, 2, 3), ("c",)) == f(1, 2, c=3)
    assert rt.call(f, (1, 3, 4), ("c", "d")) == f(1, c=3, d=4)
    for args, kw in [((), None), ((1, 2, 3), None), ((1,), ("zz",)), ((1, 2), ("a",)),
                     ((1, 2, 3), ("c", "c"))]:
        k = dict(zip(kw or (), args[len(args) - len(kw or ()):]))
        pos = args[:len(args) - len(kw or ())]
        ref = exc_of(lambda: f(*pos, **k)) if len(k) == len(kw or ()) else None
        got = exc_of(rt.call, f, args, kw)
        if ref is not None:
            assert got == ref, (args, kw, ref, got)
        else:
            assert got[0] == "exc" and got[1] is TypeError   # duplicate keyword
    assert exc_of(rt.call, 5, (), None)[1:3] == (TypeError, "'int' object is not callable")
    assert exc_of(rt.call, None, (), None)[1:3] == (TypeError, "'NoneType' object is not callable")

    def bad(*a):
        raise ArithmeticError("x", 1)

    got = exc_of(rt.call, bad, (1,), None)
    assert got[1] is ArithmeticError and got[2] == str(ArithmeticError("x", 1))
    assert rt.call(len, ([1, 2, 3],), None) == 3
    assert rt.call(dict, (), None) == {}
    assert rt.call(dict, (7,), ("a",)) == dict(a=7)
    assert rt.call(max, ([3, 1, 2],), None) == 3


def test_call_getattr_binop_do_not_leak_or_steal_references(rt):
    arg, callee_result = object(), object()
    holder = Weird()
    holder.attr = callee_result

    def ident(x):
        return callee_result

    def raises(x):
        raise ValueError("e")

    lst_a, lst_b = [1, 2], [3]
    watched = [ident, raises, arg, callee_result, holder, lst_a, lst_b, "attr"]
    base = [sys.getrefcount(o) for o in watched]
    assert rt.call_many(ident, (arg,), 5000) == 0
    assert rt.call_many(raises, (arg,), 5000) == 5000
    assert rt.getattr_many(holder, "attr", 5000) == 0
    assert rt.getattr_many(holder, "missing", 5000) == 5000
    assert rt.binop_many(lst_a, lst_b, 0, 5000) == 0       # list concat creates new lists
    assert rt.binop_many(lst_a, "x", 0, 5000) == 5000       # TypeError path
    assert [sys.getrefcount(o) for o in watched] == base
    # the result of one call is a *new* reference owned by the caller
    before = sys.getrefcount(callee_result)
    r = rt.call(ident, (arg,), None)
    assert r is callee_result and sys.getrefcount(callee_result) == before + 1
    del r
    assert sys.getrefcount(callee_result) == before
    r = rt.getattr_(holder, "attr")
    assert sys.getrefcount(callee_result) == before + 1
    del r
    assert sys.getrefcount(callee_result) == before


def test_release_clears_the_slot_and_drops_exactly_one_reference(rt):
    o = object()
    base = sys.getrefcount(o)
    for _ in range(1000):
        assert rt.release(o) is True
    assert sys.getrefcount(o) == base

    class Probe:
        died = 0

        def __del__(self):
            Probe.died += 1

    p = Probe()
    rt.release(p)
    assert Probe.died == 0
    del p
    assert Probe.died == 1


TRUTH_VALUES = [0, 1, -1, 0.0, -0.0, NAN, "", "a", [], [0], (), (0,), {}, {"a": 1}, None, True,
                False, object(), set(), 10**30, Weird(), b"", range(0), range(1)]


def test_truth_is_bool_of_the_object_and_propagates_errors(rt):
    for v in TRUTH_VALUES:
        assert rt.truth(v) == int(bool(v)), v
    ref = exc_of(bool, _Raises())
    assert exc_of(rt.truth, _Raises()) == ("ok", ref[1]) or exc_of(rt.truth, _Raises())[1:3] == ref[1:3]
    got = exc_of(rt.truth, _Raises())
    assert got[1:3] == (ValueError, "no truth")


CMP_OBJECTS = [0, 1, 1.0, -0.0, NAN, INF, "a", "b", [1], [1, 2], (1,), None, True, 2**70, 1.5,
               b"x", {1: 2}, set(), frozenset(), Weird()]


def test_compare_bool_matches_an_if_condition_for_every_operator(rt):
    cases = 0
    for a, b in itertools.product(CMP_OBJECTS, repeat=2):
        for code, pyop in OPS:
            try:
                ref = ("ok", bool(pyop(a, b)))
            except Exception as e:  # noqa: BLE001
                ref = ("exc", type(e), str(e))
            try:
                got = ("ok", bool(rt.compare_bool(a, b, code)))
            except Exception as e:  # noqa: BLE001
                got = ("exc", type(e), str(e))
            assert got == ref, (a, b, code, ref, got)
            cases += 1
    assert cases == 20 * 20 * 6


def test_compare_bool_does_not_use_the_identity_shortcut(rt):
    # `if x == x:` is False for a NaN; PyObject_RichCompareBool would say True
    x = float("nan")
    assert (x == x) is False
    assert rt.compare_bool(x, x, 2) == 0 and rt.compare_bool(x, x, 3) == 1
    nan_list = [x]
    assert (nan_list == nan_list) is True            # lists DO use identity per element
    assert rt.compare_bool(nan_list, nan_list, 2) == 1
    assert rt.compare_bool(_ReturnsObj(False), 1, 2) == 0
    assert rt.compare_bool(_ReturnsObj(True), 1, 2) == 1
    assert exc_of(rt.compare_bool, _Raises(), 1, 2)[1:3] == (KeyError, str(KeyError("no eq")))
    # the result object's truth is raised through
    class Boom:
        def __eq__(self, other):
            class T:
                def __bool__(self):
                    raise OverflowError("t")
            return T()
    assert exc_of(rt.compare_bool, Boom(), 1, 2)[1:3] == (OverflowError, "t")


OBJ_TO_FLOAT = [0, 1, -1, 2**53 + 1, 2**70, 10**400 if False else 10**300, True, 1.5, -0.0, NAN,
                INF, "1.5", " 2 ", "nan", "-inf", "1e500", "abc", "", b"1.5", None, [], (1,),
                Weird(), _Raises(), _F(3.25), _I(7), 1 + 2j, bytearray(b"2.5"), 10**400]


def test_obj_to_f64_matches_float_builtin_including_errors(rt):
    for v in OBJ_TO_FLOAT:
        ref = py(float, v)
        try:
            got = norm(rt.obj_to_f64(v))
        except Exception as e:  # noqa: BLE001
            got = ("exc", type(e), str(e))
        assert got == ref, (v, ref, got)


BINOP_TABLE = [operator.add, operator.sub, operator.mul, operator.truediv, operator.floordiv,
               operator.mod]
BINOP_OBJECTS = [0, 1, -3, 7, 2**70, -(2**70), 0.0, -0.0, 1.5, INF, NAN, True, "ab", "x", [1, 2],
                 (1,), None, b"q", 3 + 1j, {1}, _I(5), _F(2.5)]


def test_binop_obj_maps_the_six_kinds_to_the_python_operators(rt):
    n = 0
    for a, b in itertools.product(BINOP_OBJECTS, repeat=2):
        for code, op in enumerate(BINOP_TABLE):
            ref = exc_of(op, a, b)
            got = exc_of(rt.binop_obj, a, b, code)
            if ref[0] == "ok":
                assert got[0] == "ok" and norm(got[1]) == norm(ref[1]) \
                    and type(got[1]) is type(ref[1]), (a, b, code, ref, got)
            else:
                assert got == ref, (a, b, code, ref, got)
            n += 1
    assert n == len(BINOP_OBJECTS) ** 2 * 6
    # Python-level ints keep their full width: no wrap, no deopt (the OBJ path never deopts)
    assert rt.binop_obj(I64_MAX, 1, 0) == 2**63
    assert rt.binop_obj(I64_MIN, -1, 4) == 2**63
    assert exc_of(rt.binop_obj, 1, 0, 4)[1:3] == (ZeroDivisionError, str(exc_of(operator.floordiv, 1, 0)[2]))
    assert exc_of(rt.binop_obj, 1, 2, 9)[1] is SystemError
