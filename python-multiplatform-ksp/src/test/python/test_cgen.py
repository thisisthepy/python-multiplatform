"""TypedPython C back end: typed IR -> C source -> built CPython extension (#41).

The IR is built by hand here (no front end). Every end-to-end test compares the compiled function
with the interpreted one that the extension keeps in `__typedpython_interpreted__`, because the
contract (ir.py, INTENT §1.4) is "compiled code computes what CPython computes".

End-to-end tests need the C runtime header `tp_runtime.h`. It is looked up in
`$TYPEDPYTHON_RUNTIME_DIR` when that is set, otherwise in the package's own `runtime/` directory;
when it is missing those tests skip with that reason (a pre-implementation skip, not a regression).
Generation-only tests (`test_gen_*`) never need it.

Every build happens inside the caller's `heavy.sh` lock (the whole pytest process runs under it);
build output goes to the worktree's `.tmp/cgen-tests/`, never to /tmp.
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import sysconfig
import textwrap
from pathlib import Path

import pytest

from typedpython import ir
from typedpython.ir import (
    And, ArrayParam, Assign, BinOp, BinOpKind, Box, Call, Compare, CompareKind, Const, ExprStmt,
    ForRange, Function, If, Index, Len, Local, Module, Param, Return, StoreIndex, Type,
    Global, GetAttr, CallObject, Truth, CompareObj, ObjToFloat,
    UnaryOp, UnaryOpKind, Unbox, While, Break, Continue, ToFloat, MathCall, MathFunc,
    NewArray, CopyArray, Tuple,
)

PACKAGE = Path(__file__).resolve().parents[2] / "main" / "python" / "typedpython"
WORKTREE = Path(__file__).resolve().parents[4]
OUT_ROOT = WORKTREE / ".tmp" / "cgen-tests"


def runtime_dir() -> Path:
    env = os.environ.get("TYPEDPYTHON_RUNTIME_DIR")
    return Path(env) if env else PACKAGE / "runtime"


def need_runtime():
    header = runtime_dir() / "tp_runtime.h"
    if not header.is_file():
        pytest.skip(f"C runtime header not available yet: {header} does not exist "
                    "(set TYPEDPYTHON_RUNTIME_DIR to the tp-runtime worktree's runtime/ dir)")
    text = header.read_text()
    missing = [h for h in OBJ_HELPERS if not re.search(rf"\b{h}\s*\(", text)]
    if missing:
        pytest.skip(f"{header} does not define the opaque-object helpers yet: {missing}")


# --- IR shorthands -------------------------------------------------------------------------------

I64, F64, BOOL, OBJ, NONE = Type.I64, Type.F64, Type.BOOL, Type.OBJ, Type.NONE


def c(v, t=None):
    if t is None:
        t = BOOL if isinstance(v, bool) else I64 if isinstance(v, int) else F64
    return Const(t, v)


def L(name, t):
    return Local(t, name)


def binop(op, a, b, t=None):
    if t is None:
        t = F64 if (op is BinOpKind.TRUEDIV) else a.type
    return BinOp(t, op, a, b)


def call(name, *args, kwnames=()):
    """`name(*args)` for a module global (a binder function is an ordinary global)."""
    return CallObject(OBJ, Global(OBJ, name), tuple(args), tuple(kwnames))


def fn(name, params, returns, locals_, body, *, pure, may_deopt, line=1, entry_globals=()):
    return Function(name=name, params=tuple(params), returns=returns, locals=dict(locals_),
                    body=tuple(body), pure=pure, may_deopt=may_deopt, source_line=line,
                    entry_globals=tuple(entry_globals))


# The module source that every e2e module embeds; each test picks the functions it compiles.
SOURCE = textwrap.dedent('''\
    """typedpython test module, the interpreted reference."""
    calls = []

    def record(x):
        calls.append(x)
        return x

    def poly(x: float, n: int) -> float:
        acc = 0.0
        for i in range(n):
            acc = acc * x + 0.1
        return acc

    def mul_all(a: int, n: int) -> int:
        acc = 1
        for i in range(n):
            acc = acc * a
        return acc

    def twice_mul(a: int, n: int) -> int:
        return mul_all(a, n) * 2

    def eff(x: int) -> int:
        record(x)
        return x

    def scale(xs: list[float], k: float, n: int) -> None:
        for i in range(n):
            xs[i] = xs[i] * k

    def addfirst(dst: list[float], src: list[float]) -> None:
        for i in range(len(dst)):
            dst[i] = dst[i] + src[0]

    def floordiv(a: int, b: int) -> int:
        return a // b

    def objmix(o, p):
        q = o + p
        q = record(q)
        return q + o

    def count_until(limit: int, skip: int) -> int:
        total = 0
        i = 0
        while True:
            if i >= limit:
                break
            i = i + 1
            if i == skip:
                continue
            total = total + i
        return total

    def isum(xs: list[int]) -> int:
        s = 0
        for i in range(len(xs)):
            s = s + xs[i]
        return s

    def hyp(x: float, y: float) -> float:
        return math.sqrt(x * x + y * y)

    def mixed_lt(a: int, b: float) -> bool:
        return a < b and not (b < 0.0)

    def tofl(a: int) -> float:
        return float(a) / 3.0

    def neg_last(xs: list[float]) -> float:
        return -xs[-1]

    # --- plain-Python stand-ins for binder proxies (a Kotlin function, a Kotlin object) ---
    made = []

    class Counter:
        def __init__(self, scale):
            self.scale = scale
            self.reads = 0

        @property
        def factor(self):
            self.reads += 1
            return self.scale

        def apply(self, v, *, offset=0.0):
            return v * self.factor + offset

    def make(scale):
        made.append(Counter(scale))
        return made[-1]

    def boom(x):
        raise ValueError(f"bad value {x!r}")

    def target():
        return 1

    def interop(scale: float, n: int) -> float:
        obj = make(scale)
        acc = 0.0
        for i in range(n):
            v = obj.apply(acc, offset=0.5)
            acc = float(v) * 0.5 + math.sqrt(acc + 1.0)
            if obj.factor > 1.0:
                acc = acc + 1.0
        return acc + float(obj.reads)

    def which():
        return target()

    def length(o):
        return len(o)

    def calls_boom(o):
        return boom(o)

    def truthy(o) -> bool:
        if o:
            return True
        return False

    # --- 957315ea: proven ops, callee-only redo, entry snapshots of globals, CompareObj ---
    def proven_eff(x: int) -> int:
        record(x)
        return x + 1

    def growth(n: int) -> float:
        acc = 1
        for i in range(n):
            acc = acc * 3
        return float(acc)

    def growth_lie(n: int) -> float:
        acc = 1
        for i in range(n):
            acc = acc * 3
        return acc          # the IR below claims float(acc): a contract violation the redo catches

    def fill(xs: list[float], n: int) -> float:
        for i in range(len(xs)):
            xs[i] = xs[i] * 2.0
        g = growth(n)
        xs[0] = xs[0] + g
        return g

    def fill_lie(xs: list[float], n: int) -> float:
        xs[0] = xs[0] * 2.0
        return growth_lie(n)

    def big(n: int) -> bool:
        acc = 1
        for i in range(n):
            acc = acc * 3
        return acc > 1000

    def flag(xs: list[float], n: int) -> float:
        xs[0] = xs[0] + 1.0
        if big(n):
            return 1.0
        return 0.0

    def selfeq(x) -> bool:
        if x == x:
            return True
        return False

    SCALE = 2.5

    def scaled(x: float) -> float:
        return x * SCALE

    def twice_scaled(x: float) -> float:
        return scaled(x) * 2.0

    # --- local arrays and tuple returns (fannkuch, M2) ---
    def la_build(n: int) -> tuple:
        a = list(range(n))
        b = [0] * n
        s = 0
        for i in range(n // 2):
            t = a[i]
            a[i] = a[n - 1 - i]
            a[n - 1 - i] = t
        c = a[:]
        for i in range(len(c)):
            b[i] = c[i] * 3 + a[i]
            s = s + b[i]
        first = -1
        last = -1
        if len(a) > 0:
            first = a[0]
            last = a[-1]
        return (len(a), len(b), first, last, s)

    def la_f64(n: int) -> tuple:
        x = [0.5] * n
        y = x[:]
        total = 0.0
        for i in range(len(x)):
            x[i] = x[i] * float(i) + 1.0
            y[i] = x[i] / 2.0
            total = total + y[i]
        return (len(y), total)

    def la_overflow(n: int, k: int) -> tuple:
        a = list(range(n))
        b = a[:]
        acc = 1
        for i in range(len(b)):
            acc = acc + k
            b[i] = i
        return (acc, len(a))

    def la_index(n: int, i: int) -> int:
        a = list(range(n))
        return a[i]

    def la_store(n: int, i: int, v: int) -> int:
        a = [0] * n
        a[i] = v
        return len(a)

    def la_reassign(n: int) -> tuple:
        a = list(range(n))
        a = [7] * (n + 1)
        a = a[:]
        return (len(a), a[0], a[-1])

    def la_copy_param(xs: list[int]) -> tuple:
        c = xs[:]
        c[0] = 99
        return (xs[0], c[0], len(c))

    def la_unit() -> tuple:
        return ()
    ''')
SOURCE = "import math\n" + SOURCE


def f_poly():
    acc, x, n, i = L("acc", F64), L("x", F64), L("n", I64), L("i", I64)
    return fn("poly", [Param("x", F64), Param("n", I64)], F64,
              {"acc": F64, "i": I64},
              [Assign("acc", c(0.0)),
               ForRange("i", c(0), n, c(1),
                        (Assign("acc", binop(BinOpKind.ADD, binop(BinOpKind.MUL, acc, x), c(0.1))),)),
               Return(acc)],
              pure=True, may_deopt=False, line=9)


def f_mul_all():
    acc, a, n = L("acc", I64), L("a", I64), L("n", I64)
    return fn("mul_all", [Param("a", I64), Param("n", I64)], I64, {"acc": I64, "i": I64},
              [Assign("acc", c(1)),
               ForRange("i", c(0), n, c(1), (Assign("acc", binop(BinOpKind.MUL, acc, a)),)),
               Return(acc)],
              pure=True, may_deopt=True)


def f_twice_mul():
    call = Call(I64, "mul_all", (L("a", I64), L("n", I64)))
    return fn("twice_mul", [Param("a", I64), Param("n", I64)], I64, {},
              [Return(binop(BinOpKind.MUL, call, c(2)))], pure=True, may_deopt=True)


def f_eff():
    x = L("x", I64)
    return fn("eff", [Param("x", I64)], I64, {},
              [ExprStmt(call("record", Box(OBJ, x))), Return(x)],
              pure=False, may_deopt=False)


def f_scale():
    xs = "xs"
    i = L("i", I64)
    return fn("scale", [ArrayParam(xs, Type.F64_ARRAY, stored=True), Param("k", F64), Param("n", I64)],
              NONE, {"i": I64},
              [ForRange("i", c(0), L("n", I64), c(1),
                        (StoreIndex(xs, i, binop(BinOpKind.MUL, Index(F64, xs, i), L("k", F64))),))],
              pure=False, may_deopt=False)


def f_addfirst():
    i = L("i", I64)
    return fn("addfirst", [ArrayParam("dst", Type.F64_ARRAY, stored=True),
                           ArrayParam("src", Type.F64_ARRAY, stored=False)],
              NONE, {"i": I64},
              [ForRange("i", c(0), Len(I64, "dst"), c(1),
                        (StoreIndex("dst", i, binop(BinOpKind.ADD, Index(F64, "dst", i, proven=True),
                                                    Index(F64, "src", c(0)))),))],
              pure=False, may_deopt=False)


def f_floordiv():
    return fn("floordiv", [Param("a", I64), Param("b", I64)], I64, {},
              [Return(binop(BinOpKind.FLOORDIV, L("a", I64), L("b", I64)))], pure=True, may_deopt=True)


def f_objmix():
    o, p, q = L("o", OBJ), L("p", OBJ), L("q", OBJ)
    return fn("objmix", [Param("o", OBJ), Param("p", OBJ)], OBJ, {"q": OBJ},
              [Assign("q", binop(BinOpKind.ADD, o, p)),
               Assign("q", call("record", q)),
               Return(binop(BinOpKind.ADD, q, o))],
              pure=False, may_deopt=False)


def f_count_until():
    i, total = L("i", I64), L("total", I64)
    return fn("count_until", [Param("limit", I64), Param("skip", I64)], I64, {"total": I64, "i": I64},
              [Assign("total", c(0)), Assign("i", c(0)),
               While(c(True), (
                   If(Compare(BOOL, CompareKind.GE, i, L("limit", I64)), (Break(),)),
                   Assign("i", binop(BinOpKind.ADD, i, c(1))),
                   If(Compare(BOOL, CompareKind.EQ, i, L("skip", I64)), (Continue(),)),
                   Assign("total", binop(BinOpKind.ADD, total, i)),
               )),
               Return(total)],
              pure=True, may_deopt=True)


def f_isum():
    s, i = L("s", I64), L("i", I64)
    return fn("isum", [ArrayParam("xs", Type.I64_ARRAY, stored=False)], I64, {"s": I64, "i": I64},
              [Assign("s", c(0)),
               ForRange("i", c(0), Len(I64, "xs"), c(1),
                        (Assign("s", binop(BinOpKind.ADD, s, Index(I64, "xs", i, proven=True))),)),
               Return(s)],
              pure=True, may_deopt=True)


def f_hyp():
    x, y = L("x", F64), L("y", F64)
    s = binop(BinOpKind.ADD, binop(BinOpKind.MUL, x, x), binop(BinOpKind.MUL, y, y))
    return fn("hyp", [Param("x", F64), Param("y", F64)], F64, {},
              [Return(MathCall(F64, MathFunc.SQRT, (s,)))], pure=True, may_deopt=False)


def f_mixed_lt():
    a, b = L("a", I64), L("b", F64)
    return fn("mixed_lt", [Param("a", I64), Param("b", F64)], BOOL, {},
              [Return(And(BOOL, Compare(BOOL, CompareKind.LT, a, b),
                          UnaryOp(BOOL, UnaryOpKind.NOT, Compare(BOOL, CompareKind.LT, b, c(0.0)))))],
              pure=True, may_deopt=False)


def f_tofl():
    return fn("tofl", [Param("a", I64)], F64, {},
              [Return(binop(BinOpKind.TRUEDIV, ToFloat(F64, L("a", I64)), c(3.0)))],
              pure=True, may_deopt=False)


def f_neg_last():
    return fn("neg_last", [ArrayParam("xs", Type.F64_ARRAY, stored=False)], F64, {},
              [Return(UnaryOp(F64, UnaryOpKind.NEG, Index(F64, "xs", c(-1))))],
              pure=True, may_deopt=False)


def f_interop():
    obj, acc, v, i = L("obj", OBJ), L("acc", F64), L("v", OBJ), L("i", I64)
    apply = CallObject(OBJ, GetAttr(OBJ, obj, "apply"), (Box(OBJ, acc), Box(OBJ, c(0.5))), ("offset",))
    step = binop(BinOpKind.ADD, binop(BinOpKind.MUL, ObjToFloat(F64, v), c(0.5)),
                 MathCall(F64, MathFunc.SQRT, (binop(BinOpKind.ADD, acc, c(1.0)),)))
    return fn("interop", [Param("scale", F64), Param("n", I64)], F64,
              {"obj": OBJ, "acc": F64, "v": OBJ, "i": I64},
              [Assign("obj", call("make", Box(OBJ, L("scale", F64)))),
               Assign("acc", c(0.0)),
               ForRange("i", c(0), L("n", I64), c(1), (
                   Assign("v", apply),
                   Assign("acc", step),
                   If(CompareObj(BOOL, CompareKind.GT, GetAttr(OBJ, obj, "factor"), Box(OBJ, c(1.0))),
                      (Assign("acc", binop(BinOpKind.ADD, acc, c(1.0))),)),
               )),
               Return(binop(BinOpKind.ADD, acc, ObjToFloat(F64, GetAttr(OBJ, obj, "reads"))))],
              pure=False, may_deopt=False)


def f_which():
    return fn("which", [], OBJ, {}, [Return(call("target"))], pure=False, may_deopt=False)


def f_length():
    return fn("length", [Param("o", OBJ)], OBJ, {}, [Return(call("len", L("o", OBJ)))],
              pure=False, may_deopt=False)


def f_calls_boom():
    return fn("calls_boom", [Param("o", OBJ)], OBJ, {}, [Return(call("boom", L("o", OBJ)))],
              pure=False, may_deopt=False)


def f_truthy():
    return fn("truthy", [Param("o", OBJ)], BOOL, {},
              [If(Truth(BOOL, L("o", OBJ)), (Return(c(True)),)), Return(c(False))],
              pure=False, may_deopt=False)


def f_proven_eff():
    x = L("x", I64)
    return fn("proven_eff", [Param("x", I64)], I64, {},
              [ExprStmt(call("record", Box(OBJ, x))),
               Return(BinOp(I64, BinOpKind.ADD, x, c(1), proven=True))],
              pure=False, may_deopt=False)


def _growth_ir(name, result):
    acc = L("acc", I64)
    return fn(name, [Param("n", I64)], result, {"acc": I64, "i": I64},
              [Assign("acc", c(1)),
               ForRange("i", c(0), L("n", I64), c(1), (Assign("acc", binop(BinOpKind.MUL, acc, c(3))),)),
               Return(ToFloat(F64, acc) if result is F64 else Compare(BOOL, CompareKind.GT, acc, c(1000)))],
              pure=True, may_deopt=True)


def f_growth():
    return _growth_ir("growth", F64)


def f_growth_lie():
    return _growth_ir("growth_lie", F64)


def f_big():
    return _growth_ir("big", BOOL)


def f_fill():
    i, g = L("i", I64), L("g", F64)
    return fn("fill", [ArrayParam("xs", Type.F64_ARRAY, stored=True), Param("n", I64)], F64,
              {"i": I64, "g": F64},
              [ForRange("i", c(0), Len(I64, "xs"), c(1),
                        (StoreIndex("xs", i, binop(BinOpKind.MUL, Index(F64, "xs", i, proven=True), c(2.0)),
                                    proven=True),)),
               Assign("g", Call(F64, "growth", (L("n", I64),), redo=True)),
               StoreIndex("xs", c(0), binop(BinOpKind.ADD, Index(F64, "xs", c(0)), g)),
               Return(g)],
              pure=False, may_deopt=False)


def f_fill_lie():
    return fn("fill_lie", [ArrayParam("xs", Type.F64_ARRAY, stored=True), Param("n", I64)], F64, {},
              [StoreIndex("xs", c(0), binop(BinOpKind.MUL, Index(F64, "xs", c(0)), c(2.0))),
               Return(Call(F64, "growth_lie", (L("n", I64),), redo=True))],
              pure=False, may_deopt=False)


def f_flag():
    return fn("flag", [ArrayParam("xs", Type.F64_ARRAY, stored=True), Param("n", I64)], F64, {},
              [StoreIndex("xs", c(0), binop(BinOpKind.ADD, Index(F64, "xs", c(0)), c(1.0))),
               If(Call(BOOL, "big", (L("n", I64),), redo=True), (Return(c(1.0)),)),
               Return(c(0.0))],
              pure=False, may_deopt=False)


def f_selfeq():
    x = L("x", OBJ)
    return fn("selfeq", [Param("x", OBJ)], BOOL, {},
              [If(CompareObj(BOOL, CompareKind.EQ, x, x), (Return(c(True)),)), Return(c(False))],
              pure=False, may_deopt=False)


def f_scaled():
    return fn("scaled", [Param("x", F64)], F64, {},
              [Return(binop(BinOpKind.MUL, L("x", F64), L("SCALE", F64)))],
              pure=True, may_deopt=False, entry_globals=[Param("SCALE", F64)])


def f_twice_scaled():
    # The callee's entry-global guard can fail at the call: that is a deopt of the callee, which a
    # pure caller propagates (so this caller may deopt).
    return fn("twice_scaled", [Param("x", F64)], F64, {},
              [Return(binop(BinOpKind.MUL, Call(F64, "scaled", (L("x", F64),)), c(2.0)))],
              pure=True, may_deopt=True)


INTEROP = (f_interop, f_which, f_length, f_calls_boom, f_truthy)
CONTRACT_957315EA = (f_proven_eff, f_growth, f_growth_lie, f_big, f_fill, f_fill_lie, f_flag,
                     f_selfeq, f_scaled, f_twice_scaled)
OBJ_HELPERS = ("tp_global", "tp_getattr", "tp_call", "tp_truth", "tp_compare_bool", "tp_obj_to_f64",
               "tp_binop_obj", "tp_release")

ALL = (f_poly, f_mul_all, f_twice_mul, f_eff, f_scale, f_addfirst, f_floordiv, f_objmix,
       f_count_until, f_isum, f_hyp, f_mixed_lt, f_tofl, f_neg_last) + INTEROP + CONTRACT_957315EA


# --- building -------------------------------------------------------------------------------------

def source_file(name: str) -> Path:
    d = OUT_ROOT / name
    if d.exists():
        shutil.rmtree(d)
    d.mkdir(parents=True)
    p = d / f"{name}.py"
    p.write_text(SOURCE)
    return p


def build_module(name: str, functions=ALL, flags=None):
    """Generate, build and import an extension `name` compiling `functions`."""
    from typedpython import cbuild, cgen

    need_runtime()
    src = source_file(name)
    module = Module(name=name, functions=tuple(f() for f in functions))
    c_source = cgen.generate(module, src)
    kwargs = {} if flags is None else {"flags": flags}
    so = cbuild.build(c_source, name, src.parent, runtime_dir=runtime_dir(), **kwargs)
    return cbuild.load(name, so)


@pytest.fixture(scope="module")
def mod():
    return build_module("tp_cgen_e2e")


def interp(m, name):
    return m.__typedpython_interpreted__[name]


def deopts(m):
    return m.__typedpython_deopts__


# --- generation only (no runtime header needed) ---------------------------------------------------

def gen(functions=ALL, name="tp_gen_only"):
    from typedpython import cgen

    src = source_file(name)
    return cgen.generate(Module(name=name, functions=tuple(f() for f in functions)), src)


def test_gen_has_multiphase_init_and_embeds_the_source():
    text = gen()
    assert "PyInit_tp_gen_only" in text and "PyModuleDef_Init" in text and "Py_mod_exec" in text
    assert "__typedpython_interpreted__" in text and "__typedpython_deopts__" in text
    assert "tp_impl_poly" in text and "METH_FASTCALL" in text


def test_gen_rejects_a_deopting_node_in_an_impure_function():
    from typedpython import cgen

    bad = fn("bad", [Param("a", I64)], I64, {},
             [ExprStmt(call("record", Box(OBJ, L("a", I64)))),
              Return(binop(BinOpKind.ADD, L("a", I64), c(1)))],
             pure=False, may_deopt=False)
    with pytest.raises(cgen.CGenError, match="deopt"):
        cgen.generate(Module("tp_bad", (bad,)), source_file("tp_bad"))


def test_gen_rejects_a_deopting_call_from_an_impure_caller():
    from typedpython import cgen

    caller = fn("caller", [Param("a", I64)], I64, {},
                [ExprStmt(call("record", Box(OBJ, L("a", I64)))),
                 Return(Call(I64, "mul_all", (L("a", I64), c(2))))],
                pure=False, may_deopt=False)
    with pytest.raises(cgen.CGenError, match="deopt"):
        cgen.generate(Module("tp_bad2", (f_mul_all(), caller)), source_file("tp_bad2"))


# Every function the generated C calls must be CPython C API, a tp_runtime.h helper named in
# runtime/API.md, or a function the generated file defines itself.
C_WORDS = {"void", "if", "for", "while", "switch", "return", "sizeof", "defined", "INT64_C", "UINT64_C"}


def api_helpers() -> set[str]:
    text = (PACKAGE / "runtime" / "API.md").read_text()
    return set(re.findall(r"\b(tp_[a-z0-9_]+)\s*\(", text))


def test_gen_calls_only_cpython_api_and_runtime_helpers():
    text = gen()
    # strip comments and string literals so the embedded source does not count
    body = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    body = re.sub(r'"(?:\\.|[^"\\])*"', '""', body)
    called = set(re.findall(r"\b([A-Za-z_][A-Za-z0-9_]*)\s*\(", body))
    defined = set(re.findall(r"^(?:static\s+)?[A-Za-z_][A-Za-z0-9_ \*]*?\b([A-Za-z_][A-Za-z0-9_]*)\s*\(",
                             body, flags=re.M))
    helpers = api_helpers()
    assert "tp_add_i64" in helpers and "tp_any_same" in helpers   # the scan reads API.md for real
    bad = sorted(n for n in called - C_WORDS - helpers - defined
                 if not re.match(r"^(Py[A-Z_]|PyModuleDef_Init$)", n))
    assert bad == [], f"generated C calls functions outside the CPython API and tp_runtime.h: {bad}"
    # tp_* names that are called but neither in API.md nor defined here are typos in waiting
    stray = sorted(n for n in called if n.startswith("tp_") and n not in helpers and n not in defined)
    assert stray == []
    # no private CPython API
    assert not re.search(r"\b_Py[A-Za-z]", body)


# --- end to end -----------------------------------------------------------------------------------

def test_compiled_functions_replace_the_globals(mod):
    assert type(mod.poly).__name__ == "builtin_function_or_method"
    assert type(interp(mod, "poly")).__name__ == "function"
    assert mod.record.__module__ == "tp_cgen_e2e"       # interpreted names live in the same dict
    assert interp(mod, "poly").__globals__ is mod.__dict__


def test_f64_loop_returns_exactly_the_interpreted_result(mod):
    before = deopts(mod)
    for x, n in ((1.1, 1000), (0.37, 17), (-2.5, 9), (1e308, 3), (float("nan"), 2)):
        got, want = mod.poly(x, n), interp(mod, "poly")(x, n)
        assert type(got) is float
        assert repr(got) == repr(want), (x, n, got, want)     # bit-exact, incl. inf/nan
    assert deopts(mod) == before


def test_i64_overflow_in_a_pure_function_deopts_to_the_big_int_result(mod):
    before = deopts(mod)
    assert mod.mul_all(3, 10) == 3 ** 10
    assert deopts(mod) == before
    assert mod.mul_all(3, 50) == 3 ** 50
    assert deopts(mod) == before + 1


def test_deopt_propagates_through_a_c_to_c_call(mod):
    before = deopts(mod)
    assert mod.twice_mul(7, 5) == 2 * 7 ** 5
    assert deopts(mod) == before
    assert mod.twice_mul(7, 40) == 2 * 7 ** 40
    # The outermost compiled call is redone once (+1). The interpreted twice_mul then calls the
    # global mul_all, the compiled one, since there is one namespace, which deopts again (+1).
    assert deopts(mod) == before + 2


def test_big_int_argument_takes_the_interpreted_path_before_any_effect(mod):
    mod.calls.clear()
    before = deopts(mod)
    assert mod.eff(5) == 5
    assert mod.calls == [5] and deopts(mod) == before
    big = 2 ** 70
    assert mod.eff(big) == big
    assert mod.calls == [5, big]               # the effect ran once, by the interpreter
    assert deopts(mod) == before + 1
    assert mod.mul_all(2 ** 64, 1) == 2 ** 64 and deopts(mod) == before + 2


def test_wrong_arity_raises_cpythons_own_type_error(mod):
    with pytest.raises(TypeError) as want:
        interp(mod, "poly")(1.0)
    with pytest.raises(TypeError) as got:
        mod.poly(1.0)
    assert str(got.value) == str(want.value)
    assert "poly() missing 1 required positional argument: 'n'" == str(got.value)
    with pytest.raises(TypeError) as got3:
        mod.poly(1.0, 2, 3)
    assert "poly() takes 2 positional arguments but 3 were given" == str(got3.value)


def test_keyword_arguments_take_the_interpreted_path(mod):
    before = deopts(mod)
    assert repr(mod.poly(x=1.1, n=30)) == repr(interp(mod, "poly")(1.1, 30))
    assert repr(mod.poly(1.1, n=30)) == repr(interp(mod, "poly")(1.1, 30))
    assert deopts(mod) == before + 2
    with pytest.raises(TypeError) as got:
        mod.poly(1.1, m=3)
    with pytest.raises(TypeError) as want:
        interp(mod, "poly")(1.1, m=3)
    assert str(got.value) == str(want.value)


def test_exact_float_guard_rejects_int_and_subclass(mod):
    class F(float):
        def __rmul__(self, other):
            return 42.0

    before = deopts(mod)
    assert mod.poly(2, 3) == interp(mod, "poly")(2, 3)            # int x -> interpreted
    assert mod.poly(F(2.0), 3) == interp(mod, "poly")(F(2.0), 3) == 42.1  # subclass -> interpreted
    assert deopts(mod) == before + 2


def test_f64_array_param_is_written_back(mod):
    xs = [1.0, 2.0, 3.5]
    ident = id(xs)
    before = deopts(mod)
    assert mod.scale(xs, 2.0, 3) is None
    assert xs == [2.0, 4.0, 7.0] and id(xs) == ident
    assert all(type(v) is float for v in xs)
    assert deopts(mod) == before


def test_exception_mid_loop_keeps_the_partial_updates(mod):
    xs, ys = [1.0, 2.0, 3.0], [1.0, 2.0, 3.0]
    with pytest.raises(IndexError) as got:
        mod.scale(xs, 10.0, 5)
    with pytest.raises(IndexError) as want:
        interp(mod, "scale")(ys, 10.0, 5)
    assert str(got.value) == str(want.value) == "list index out of range"
    assert xs == ys == [10.0, 20.0, 30.0]


def test_negative_index_counts_from_the_end(mod):
    assert mod.neg_last([1.0, 2.5]) == -2.5
    with pytest.raises(IndexError, match="list index out of range"):
        mod.neg_last([])


def test_aliasing_two_array_params_takes_the_interpreted_path(mod):
    a, b = [1.0, 2.0, 3.0], [1.0, 2.0, 3.0]
    before = deopts(mod)
    mod.addfirst(a, a)
    interp(mod, "addfirst")(b, b)
    assert a == b == [2.0, 4.0, 5.0]
    assert deopts(mod) == before + 1
    d, s = [1.0, 2.0], [10.0]
    mod.addfirst(d, s)
    assert d == [11.0, 12.0] and s == [10.0] and deopts(mod) == before + 1


def test_a_non_float_element_takes_the_interpreted_path(mod):
    xs, ys = [1.0, 2, 3.0], [1.0, 2, 3.0]
    before = deopts(mod)
    mod.scale(xs, 2.0, 3)
    interp(mod, "scale")(ys, 2.0, 3)
    assert xs == ys and [type(v) for v in xs] == [type(v) for v in ys]
    assert deopts(mod) == before + 1
    assert mod.scale((1.0,), 2.0, 0) is None and deopts(mod) == before + 2   # a tuple is not a list


def test_i64_array_and_its_range_guard(mod):
    before = deopts(mod)
    assert mod.isum([1, 2, 3, -4]) == 2 and deopts(mod) == before
    assert mod.isum([2 ** 63 - 1, 1]) == 2 ** 63 and deopts(mod) == before + 1   # overflow in body
    assert mod.isum([2 ** 64, 1]) == 2 ** 64 + 1 and deopts(mod) == before + 2   # element guard
    assert mod.isum([True, 1]) == 2 and deopts(mod) == before + 3                # bool is not int


def test_integer_division_by_zero_has_cpythons_message(mod):
    assert mod.floordiv(-7, 2) == -4
    with pytest.raises(ZeroDivisionError) as got:
        mod.floordiv(1, 0)
    with pytest.raises(ZeroDivisionError) as want:
        interp(mod, "floordiv")(1, 0)
    assert str(got.value) == str(want.value)
    before = deopts(mod)
    assert mod.floordiv(-2 ** 63, -1) == 2 ** 63 and deopts(mod) == before + 1


def test_while_break_continue(mod):
    for limit, skip in ((10, 3), (0, 0), (5, 9), (100, 100)):
        assert mod.count_until(limit, skip) == interp(mod, "count_until")(limit, skip)


def test_math_and_mixed_compare_and_tofloat(mod):
    for x, y in ((3.0, 4.0), (1e200, 1e200), (0.1, 0.2)):
        assert repr(mod.hyp(x, y)) == repr(interp(mod, "hyp")(x, y))
    for a, b in ((2 ** 53 + 1, float(2 ** 53)), (1, 1.5), (3, 2.0), (-1, -0.5), (1, float("nan"))):
        assert mod.mixed_lt(a, b) is interp(mod, "mixed_lt")(a, b), (a, b)
    for a in (1, 2 ** 53 + 1, -(2 ** 62) - 3, 7):
        assert repr(mod.tofl(a)) == repr(interp(mod, "tofl")(a))


def test_obj_code_keeps_reference_counts_balanced(mod):
    o, p = 10 ** 30, 10 ** 31            # not cached small ints
    mod.calls.clear()
    assert mod.objmix(o, p) == interp(mod, "objmix")(o, p)
    mod.calls.clear()
    ro, rp = sys.getrefcount(o), sys.getrefcount(p)
    for _ in range(1000):
        mod.objmix(o, p)
    mod.calls.clear()
    assert (sys.getrefcount(o), sys.getrefcount(p)) == (ro, rp)
    with pytest.raises(TypeError) as got:
        mod.objmix(1, "a")
    with pytest.raises(TypeError) as want:
        interp(mod, "objmix")(1, "a")
    assert str(got.value) == str(want.value)


def test_interop_matches_the_interpreted_run_including_property_reads(mod):
    for scale, n in ((1.5, 20), (0.5, 7), (2.0, 0)):
        mod.made.clear()
        got = mod.interop(scale, n)
        got_reads = mod.made[-1].reads
        want = interp(mod, "interop")(scale, n)
        want_reads = mod.made[-1].reads
        assert repr(got) == repr(want), (scale, n)
        assert got_reads == want_reads == 2 * n     # `factor` is read in apply() and in the if
        assert len(mod.made) == 2                   # make() ran once per call, no redo


def test_a_rebound_global_is_seen_and_builtins_are_found(mod):
    assert mod.which() == 1
    old = mod.target
    try:
        mod.target = lambda: 2
        assert mod.which() == 2
        del mod.target
        with pytest.raises(NameError) as got:
            mod.which()
        with pytest.raises(NameError) as want:
            interp(mod, "which")()
        assert str(got.value) == str(want.value) == "name 'target' is not defined"
    finally:
        mod.target = old
    assert mod.length([1, 2, 3]) == 3


def test_an_exception_in_the_callee_propagates_and_leaks_nothing(mod):
    o = object()
    with pytest.raises(ValueError) as want:
        interp(mod, "calls_boom")(o)
    del want
    before = sys.getrefcount(o)
    for _ in range(1000):
        with pytest.raises(ValueError) as got:
            mod.calls_boom(o)
        assert str(got.value) == f"bad value {o!r}"
        del got
    assert sys.getrefcount(o) == before


def test_truth_runs_dunder_bool_and_its_errors(mod):
    class B:
        def __bool__(self):
            raise RuntimeError("no truth")

    assert mod.truthy([1]) is True and mod.truthy([]) is False and mod.truthy(0.0) is False
    with pytest.raises(RuntimeError, match="no truth"):
        mod.truthy(B())


def test_gen_rejects_an_effect_in_a_pure_function():
    from typedpython import cgen

    bad = fn("badpure", [Param("o", OBJ)], OBJ, {}, [Return(GetAttr(OBJ, L("o", OBJ), "x"))],
             pure=True, may_deopt=False)
    with pytest.raises(cgen.CGenError, match="pure"):
        cgen.generate(Module("tp_bad3", (bad,)), source_file("tp_bad3"))


# --- 957315ea: proven ops, Call.redo, entry_globals, CompareObj without identity shortcut ----------

def test_gen_accepts_proven_ops_in_an_impure_function():
    from typedpython import cgen

    a = L("a", I64)
    ok = fn("ok", [Param("a", I64)], I64, {},
            [ExprStmt(call("record", Box(OBJ, a))),
             Return(UnaryOp(I64, UnaryOpKind.NEG, BinOp(I64, BinOpKind.SUB, a, c(1), proven=True),
                            proven=True))],
            pure=False, may_deopt=False)
    text = cgen.generate(Module("tp_proven_gen", (ok,)), source_file("tp_proven_gen"))
    assert "typedpython: proven operation overflowed" in text


def test_gen_rejects_proven_on_an_op_that_is_not_checked_i64_arithmetic():
    from typedpython import cgen

    bad = fn("badproven", [Param("a", I64)], I64, {},
             [Return(BinOp(I64, BinOpKind.FLOORDIV, L("a", I64), c(2), proven=True))],
             pure=True, may_deopt=True)
    with pytest.raises(cgen.CGenError, match="proven"):
        cgen.generate(Module("tp_bad4", (bad,)), source_file("tp_bad4"))


def test_gen_rejects_redo_of_an_i64_callee():
    from typedpython import cgen

    caller = fn("caller", [Param("a", I64)], I64, {},
                [ExprStmt(call("record", Box(OBJ, L("a", I64)))),
                 Return(Call(I64, "mul_all", (L("a", I64), c(2)), redo=True))],
                pure=False, may_deopt=False)
    with pytest.raises(cgen.CGenError, match="redo"):
        cgen.generate(Module("tp_bad5", (f_mul_all(), caller)), source_file("tp_bad5"))


def test_gen_rejects_entry_globals_in_a_function_that_runs_user_code():
    from typedpython import cgen

    bad = fn("notclosed", [Param("o", OBJ)], F64, {},
             [ExprStmt(call("record", L("o", OBJ))), Return(L("SCALE", F64))],
             pure=False, may_deopt=False, entry_globals=[Param("SCALE", F64)])
    with pytest.raises(cgen.CGenError, match="closed"):
        cgen.generate(Module("tp_bad6", (bad,)), source_file("tp_bad6"))


def test_proven_op_in_an_impure_function_computes_the_interpreted_result(mod):
    mod.calls.clear()
    before = deopts(mod)
    assert mod.proven_eff(5) == interp(mod, "proven_eff")(5) == 6
    assert mod.calls == [5, 5] and deopts(mod) == before
    mod.calls.clear()
    # A proof the IR producer got wrong: the overflow is an internal error, never a deopt (the
    # effect has happened already, so a redo would run it twice).
    with pytest.raises(SystemError, match="typedpython: proven operation overflowed"):
        mod.proven_eff(2 ** 63 - 1)
    assert mod.calls == [2 ** 63 - 1] and deopts(mod) == before


def test_redo_reruns_only_the_pure_callee_and_the_callers_effects_happen_once(mod):
    xs, ys = [1.0, 2.0], [1.0, 2.0]
    before = deopts(mod)
    got = mod.fill(xs, 10)
    assert deopts(mod) == before                      # 3**10 fits: no redo
    want = interp(mod, "fill")(ys, 10)
    assert repr(got) == repr(want) and xs == ys == [2.0 + 3.0 ** 10, 4.0]

    xs, ys = [1.0, 2.0], [1.0, 2.0]
    before = deopts(mod)
    got = mod.fill(xs, 45)                            # 3**40 leaves i64 inside growth
    assert deopts(mod) == before + 1                  # growth redone once, fill not redone
    want = interp(mod, "fill")(ys, 45)
    assert type(got) is float and repr(got) == repr(want) == repr(float(3 ** 45))
    # effects exactly once: xs[1] doubled once, xs[0] doubled once then grown once
    assert xs == ys == [2.0 + float(3 ** 45), 4.0]
    assert [type(v) for v in xs] == [float, float]


def test_redo_of_a_bool_callee(mod):
    for n, want_r in ((3, 0.0), (7, 1.0), (45, 1.0)):
        xs, ys = [1.0], [1.0]
        before = deopts(mod)
        got = mod.flag(xs, n)
        assert deopts(mod) == before + (1 if n >= 40 else 0)
        assert got == interp(mod, "flag")(ys, n) == want_r
        assert xs == ys == [2.0]


def test_redo_result_of_the_wrong_type_is_a_system_error_naming_the_callee(mod):
    xs = [1.0]
    assert mod.fill_lie(xs, 10) == float(3 ** 10) and xs == [2.0]
    xs = [1.0]
    with pytest.raises(SystemError, match="growth_lie"):
        mod.fill_lie(xs, 45)
    assert xs == [2.0]                                # the caller's store is written back once


def test_redo_releases_its_temporaries(mod):
    # The redo creates a boxed argument, the interpreted function object reference and a result
    # float per call; a leak of any one is >= 24 bytes per call, so 2000 calls would grow by
    # >= 48 KB. (Floats are not GC-tracked and are fresh objects, so refcounts cannot show this.)
    import tracemalloc

    def run(k):
        for _ in range(k):
            xs = [1.0]
            mod.fill(xs, 45)
            mod.flag(xs, 45)
            try:
                mod.fill_lie(xs, 45)
            except SystemError:
                pass

    run(200)                                          # warm free lists and caches
    tracemalloc.start()
    try:
        base = tracemalloc.get_traced_memory()[0]
        run(2000)
        grown = tracemalloc.get_traced_memory()[0] - base
    finally:
        tracemalloc.stop()
    assert grown < 16 * 1024, grown
    n = 10 ** 30
    before = sys.getrefcount(n)
    for _ in range(1000):
        mod.selfeq(n)
    assert sys.getrefcount(n) == before


def test_entry_global_is_read_at_each_call(mod):
    old = mod.SCALE
    try:
        before = deopts(mod)
        assert repr(mod.scaled(3.0)) == repr(interp(mod, "scaled")(3.0)) == repr(7.5)
        mod.SCALE = 4.0                                 # rebinding between calls is seen
        assert mod.scaled(3.0) == interp(mod, "scaled")(3.0) == 12.0
        assert mod.twice_scaled(3.0) == interp(mod, "twice_scaled")(3.0) == 24.0
        assert deopts(mod) == before
        mod.SCALE = 3                                   # an int: the whole call is interpreted
        got = mod.scaled(3.0)
        assert deopts(mod) == before + 1
        assert type(got) is float and got == interp(mod, "scaled")(3.0) == 9.0
        d = deopts(mod)
        assert mod.twice_scaled(3.0) == 18.0
        # the pure caller is redone (+1); the interpreted caller calls the compiled scaled, whose
        # guard fails again (+1)
        assert deopts(mod) == d + 2
        del mod.SCALE                                   # unbound: interpreted path raises NameError
        with pytest.raises(NameError) as got_e:
            mod.scaled(3.0)
        with pytest.raises(NameError) as want_e:
            interp(mod, "scaled")(3.0)
        assert str(got_e.value) == str(want_e.value) == "name 'SCALE' is not defined"
    finally:
        mod.SCALE = old


def test_compare_obj_has_no_identity_shortcut(mod):
    nan = float("nan")
    assert mod.selfeq(nan) is interp(mod, "selfeq")(nan) is False

    class Never:
        def __eq__(self, other):
            return False

    o = Never()
    assert mod.selfeq(o) is interp(mod, "selfeq")(o) is False
    assert mod.selfeq(1.0) is True


# --- local arrays and tuples (issue #41, contract 513f10f2) -----------------------------------------

I64A, F64A = Type.I64_ARRAY, Type.F64_ARRAY
LOCAL_ARRAY_HELPERS = ("tp_i64_array_new", "tp_i64_array_iota", "tp_i64_array_copy",
                       "tp_i64_array_free", "tp_f64_array_new", "tp_f64_array_copy",
                       "tp_f64_array_free", "tp_tuple")


def need_local_array_runtime():
    need_runtime()
    header = runtime_dir() / "tp_runtime.h"
    text = header.read_text()
    missing = [h for h in LOCAL_ARRAY_HELPERS if not re.search(rf"\b{h}\s*\(", text)]
    if missing:
        pytest.skip(f"{header} does not define the local-array helpers yet (the tp-runtime agent "
                    f"is still writing them): {missing}")


def f_la_build():
    n, i, t = L("n", I64), L("i", I64), L("t", I64)
    s, first, last = L("s", I64), L("first", I64), L("last", I64)
    mirror = binop(BinOpKind.SUB, binop(BinOpKind.SUB, n, c(1)), i)
    return fn("la_build", [Param("n", I64)], OBJ,
              {"a": I64A, "b": I64A, "c": I64A, "i": I64, "t": I64, "s": I64, "first": I64, "last": I64},
              [Assign("a", NewArray(I64A, n, None, True)),
               Assign("b", NewArray(I64A, n, c(0))),
               Assign("s", c(0)),
               ForRange("i", c(0), binop(BinOpKind.FLOORDIV, n, c(2)), c(1), (
                   Assign("t", Index(I64, "a", i)),
                   StoreIndex("a", i, Index(I64, "a", mirror)),
                   StoreIndex("a", mirror, t),
               )),
               Assign("c", CopyArray(I64A, "a")),
               ForRange("i", c(0), Len(I64, "c"), c(1), (
                   StoreIndex("b", i, binop(BinOpKind.ADD, binop(BinOpKind.MUL, Index(I64, "c", i), c(3)),
                                            Index(I64, "a", i))),
                   Assign("s", binop(BinOpKind.ADD, s, Index(I64, "b", i))),
               )),
               Assign("first", c(-1)), Assign("last", c(-1)),
               If(Compare(BOOL, CompareKind.GT, Len(I64, "a"), c(0)), (
                   Assign("first", Index(I64, "a", c(0))),
                   Assign("last", Index(I64, "a", c(-1))),
               )),
               Return(Tuple(OBJ, (Box(OBJ, Len(I64, "a")), Box(OBJ, Len(I64, "b")), Box(OBJ, first),
                                  Box(OBJ, last), Box(OBJ, s))))],
              pure=True, may_deopt=True)


def f_la_f64():
    n, i, total = L("n", I64), L("i", I64), L("total", F64)
    return fn("la_f64", [Param("n", I64)], OBJ,
              {"x": F64A, "y": F64A, "total": F64, "i": I64},
              [Assign("x", NewArray(F64A, n, c(0.5))),
               Assign("y", CopyArray(F64A, "x")),
               Assign("total", c(0.0)),
               ForRange("i", c(0), Len(I64, "x"), c(1), (
                   StoreIndex("x", i, binop(BinOpKind.ADD, binop(BinOpKind.MUL, Index(F64, "x", i),
                                                                 ToFloat(F64, i)), c(1.0))),
                   StoreIndex("y", i, binop(BinOpKind.TRUEDIV, Index(F64, "x", i), c(2.0))),
                   Assign("total", binop(BinOpKind.ADD, total, Index(F64, "y", i))),
               )),
               Return(Tuple(OBJ, (Box(OBJ, Len(I64, "y")), Box(OBJ, total))))],
              pure=True, may_deopt=False)


def f_la_overflow():
    i, acc = L("i", I64), L("acc", I64)
    return fn("la_overflow", [Param("n", I64), Param("k", I64)], OBJ,
              {"a": I64A, "b": I64A, "acc": I64, "i": I64},
              [Assign("a", NewArray(I64A, L("n", I64), None, True)),
               Assign("b", CopyArray(I64A, "a")),
               Assign("acc", c(1)),
               ForRange("i", c(0), Len(I64, "b"), c(1), (
                   Assign("acc", binop(BinOpKind.ADD, acc, L("k", I64))),
                   StoreIndex("b", i, i),
               )),
               Return(Tuple(OBJ, (Box(OBJ, acc), Box(OBJ, Len(I64, "a")))))],
              pure=True, may_deopt=True)


def f_la_index():
    return fn("la_index", [Param("n", I64), Param("i", I64)], I64, {"a": I64A},
              [Assign("a", NewArray(I64A, L("n", I64), None, True)),
               Return(Index(I64, "a", L("i", I64)))],
              pure=True, may_deopt=False)


def f_la_store():
    return fn("la_store", [Param("n", I64), Param("i", I64), Param("v", I64)], I64, {"a": I64A},
              [Assign("a", NewArray(I64A, L("n", I64), c(0))),
               StoreIndex("a", L("i", I64), L("v", I64)),
               Return(Len(I64, "a"))],
              pure=True, may_deopt=False)


def f_la_reassign():
    n = L("n", I64)
    return fn("la_reassign", [Param("n", I64)], OBJ, {"a": I64A},
              [Assign("a", NewArray(I64A, n, None, True)),
               Assign("a", NewArray(I64A, binop(BinOpKind.ADD, n, c(1)), c(7))),
               Assign("a", CopyArray(I64A, "a")),
               Return(Tuple(OBJ, (Box(OBJ, Len(I64, "a")), Box(OBJ, Index(I64, "a", c(0))),
                                  Box(OBJ, Index(I64, "a", c(-1))))))],
              pure=True, may_deopt=True)


def f_la_copy_param():
    return fn("la_copy_param", [ArrayParam("xs", I64A, stored=False)], OBJ, {"c": I64A},
              [Assign("c", CopyArray(I64A, "xs")),
               StoreIndex("c", c(0), c(99)),
               Return(Tuple(OBJ, (Box(OBJ, Index(I64, "xs", c(0))), Box(OBJ, Index(I64, "c", c(0))),
                                  Box(OBJ, Len(I64, "c")))))],
              pure=True, may_deopt=False)


def f_la_unit():
    return fn("la_unit", [], OBJ, {}, [Return(Tuple(OBJ, ()))], pure=True, may_deopt=False)


LOCALARR = (f_la_build, f_la_f64, f_la_overflow, f_la_index, f_la_store, f_la_reassign,
            f_la_copy_param, f_la_unit)


@pytest.fixture(scope="module")
def la():
    need_local_array_runtime()
    return build_module("tp_cgen_la", functions=LOCALARR)


def test_gen_local_array_scan_calls_only_runtime_helpers():
    text = gen(LOCALARR, name="tp_gen_la")
    body = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    body = re.sub(r'"(?:\\.|[^"\\])*"', '""', body)
    called = set(re.findall(r"\b([A-Za-z_][A-Za-z0-9_]*)\s*\(", body))
    helpers = api_helpers()
    for h in LOCAL_ARRAY_HELPERS:
        assert h in helpers, f"{h} is not in runtime/API.md"
        assert h in called, f"the local-array functions never call {h}"
    stray = sorted(n for n in called if n.startswith("tp_") and n not in helpers
                   and not re.search(rf"^static [^\n]*\b{n}\s*\(", body, flags=re.M))
    assert stray == []


def test_gen_local_arrays_never_go_through_the_write_back_exit():
    text = gen((f_la_build,), name="tp_gen_la2")
    impl = text[text.index("static int tp_impl_la_build(PyObject *tp_module, int64_t l_n"):]
    impl = impl[:impl.index("static PyObject *tp_wrap_la_build")]
    assert "_array_exit" not in impl and "_array_enter" not in impl
    assert impl.count("tp_i64_array_free(") >= 3          # a, b, c are freed at the exit block
    assert ".dirty" not in impl and "->dirty" not in impl  # stores into a local set no dirty bit


def test_gen_rejects_a_new_array_that_is_not_an_assign_value():
    from typedpython import cgen

    bad = fn("bad", [Param("n", I64)], I64, {"a": I64A},
             [ExprStmt(NewArray(I64A, L("n", I64), c(0))), Return(c(0))], pure=True, may_deopt=False)
    with pytest.raises(cgen.CGenError, match="NewArray"):
        cgen.generate(Module("tp_bad3", (bad,)), source_file("tp_bad3"))


def test_gen_rejects_assigning_an_array_of_the_wrong_element_type():
    from typedpython import cgen

    bad = fn("bad", [Param("n", I64)], I64, {"a": F64A},
             [Assign("a", NewArray(I64A, L("n", I64), c(0))), Return(c(0))], pure=True, may_deopt=False)
    with pytest.raises(cgen.CGenError):
        cgen.generate(Module("tp_bad4", (bad,)), source_file("tp_bad4"))


def test_gen_a_store_into_a_param_array_still_makes_a_pure_function_an_error():
    from typedpython import cgen

    bad = fn("bad", [ArrayParam("xs", I64A, stored=True)], NONE, {},
             [StoreIndex("xs", c(0), c(1))], pure=True, may_deopt=False)
    with pytest.raises(cgen.CGenError, match="pure"):
        cgen.generate(Module("tp_bad5", (bad,)), source_file("tp_bad5"))


def test_gen_a_store_into_a_local_array_is_not_an_effect():
    text = gen((f_la_store,), name="tp_gen_la3")
    assert "tp_impl_la_store" in text


def test_local_arrays_equal_cpython_for_several_n(la):
    for n in (0, 1, 2, 3, 7, 10, 64, -1, -5):
        got, want = la.la_build(n), interp(la, "la_build")(n)
        assert type(got) is tuple and got == want, (n, got, want)
    assert deopts(la) == 0


def test_f64_local_arrays_equal_cpython_bit_for_bit(la):
    for n in (0, 1, 5, 100, -3):
        got, want = la.la_f64(n), interp(la, "la_f64")(n)
        assert repr(got) == repr(want), (n, got, want)


def test_a_tuple_with_no_elements_is_the_empty_tuple(la):
    assert la.la_unit() == () and type(la.la_unit()) is tuple


def test_local_array_index_errors_equal_cpython(la):
    for fname, args in (("la_index", (5, 5)), ("la_index", (5, -6)), ("la_index", (0, 0)),
                        ("la_index", (5, 4)), ("la_index", (5, -5)),
                        ("la_store", (3, 3, 1)), ("la_store", (3, -4, 1)), ("la_store", (0, 0, 1)),
                        ("la_store", (3, -1, 9)), ("la_store", (3, 2, 9))):
        try:
            want = ("ok", interp(la, fname)(*args))
        except Exception as e:
            want = (type(e), str(e))
        try:
            got = ("ok", getattr(la, fname)(*args))
        except Exception as e:
            got = (type(e), str(e))
        assert got == want, (fname, args, got, want)
    # and the two messages are really the list ones, not something of our own
    with pytest.raises(IndexError, match=r"^list index out of range$"):
        la.la_index(2, 2)
    with pytest.raises(IndexError, match=r"^list assignment index out of range$"):
        la.la_store(2, 2, 0)


def test_reassigning_a_local_array_replaces_it_and_copy_of_itself_is_safe(la):
    for n in (0, 1, 5, 40):
        assert la.la_reassign(n) == interp(la, "la_reassign")(n)
    for bad in (-1,):                       # a == [7]*0: a[0] raises in both
        with pytest.raises(IndexError) as g:
            la.la_reassign(bad)
        with pytest.raises(IndexError) as w:
            interp(la, "la_reassign")(bad)
        assert str(g.value) == str(w.value)


def test_copy_of_an_array_param_leaves_the_list_untouched(la):
    xs = [5, 6, 7]
    assert la.la_copy_param(xs) == interp(la, "la_copy_param")([5, 6, 7]) == (5, 99, 3)
    assert xs == [5, 6, 7]


def test_overflow_deopt_with_local_arrays_returns_the_big_int_result(la):
    before = deopts(la)
    got, want = la.la_overflow(50, 2 ** 62), interp(la, "la_overflow")(50, 2 ** 62)
    assert got == want and got[0] == 1 + 50 * 2 ** 62 and got[0] > 2 ** 63
    assert deopts(la) == before + 1
    before = deopts(la)
    assert la.la_overflow(10, 2) == interp(la, "la_overflow")(10, 2) and deopts(la) == before


def heap_in_use() -> int:
    """Bytes the C allocator has handed out right now (macOS: aggregated over all malloc zones)."""
    import ctypes

    if sys.platform != "darwin":
        pytest.skip("heap-in-use probe is written for macOS (malloc_zone_statistics) only")

    class Stats(ctypes.Structure):
        _fields_ = [("blocks_in_use", ctypes.c_uint), ("size_in_use", ctypes.c_size_t),
                    ("max_size_in_use", ctypes.c_size_t), ("size_allocated", ctypes.c_size_t)]

    libc = ctypes.CDLL(None)
    stats = Stats()
    libc.malloc_zone_statistics(None, ctypes.byref(stats))
    return stats.size_in_use


def leaked_bytes(call, rounds=40) -> int:
    import gc

    call()                                  # warm caches and the allocator
    gc.collect()
    before = heap_in_use()
    for _ in range(rounds):
        call()
    gc.collect()
    return heap_in_use() - before


# Each call below allocates two 800 KB arrays (n = 100000); a leak of one array per call is 32 MB
# over 40 rounds, far above the allocator noise this bound allows. (LeakSanitizer does not exist on
# macOS, so the leak check is this heap-in-use delta; ASan below checks the memory errors.)
LEAK_LIMIT = 4 * 1024 * 1024


def test_deopt_in_a_pure_function_frees_the_local_arrays(la):
    before = deopts(la)
    assert la.la_overflow(100_000, 2 ** 62)[1] == 100_000   # deopted: the sum is a big int
    assert deopts(la) == before + 1
    assert leaked_bytes(lambda: la.la_overflow(100_000, 2 ** 62)) < LEAK_LIMIT


def test_ok_path_frees_the_local_arrays(la):
    assert leaked_bytes(lambda: la.la_overflow(100_000, 1)) < LEAK_LIMIT
    assert deopts(la) >= 0


def test_error_path_frees_the_local_arrays(la):
    def boom():
        try:
            la.la_index(100_000, 10 ** 9)
        except IndexError:
            pass
    assert leaked_bytes(boom) < LEAK_LIMIT


def test_reassigning_in_a_loop_frees_the_old_arrays(la):
    assert leaked_bytes(lambda: la.la_reassign(100_000)) < LEAK_LIMIT


# --- sanitizers -----------------------------------------------------------------------------------

ASAN_SCRIPT = textwrap.dedent('''\
    import sys
    sys.path.insert(0, {pkg!r})
    from typedpython import cbuild
    m = cbuild.load({name!r}, {so!r})
    I = m.__typedpython_interpreted__
    assert repr(m.poly(1.1, 500)) == repr(I["poly"](1.1, 500))
    assert m.mul_all(3, 50) == 3 ** 50
    assert m.twice_mul(7, 40) == 2 * 7 ** 40
    xs = [1.0, 2.0, 3.0]
    try:
        m.scale(xs, 10.0, 5)
    except IndexError:
        pass
    assert xs == [10.0, 20.0, 30.0], xs
    a = [1.0, 2.0, 3.0]; m.addfirst(a, a); assert a == [2.0, 4.0, 5.0]
    d = [1.0] * 1000; m.addfirst(d, [2.0]); assert d == [3.0] * 1000
    assert m.isum(list(range(10000))) == sum(range(10000))
    assert m.isum([2 ** 63 - 1, 1]) == 2 ** 63
    try:
        m.floordiv(1, 0)
    except ZeroDivisionError:
        pass
    for _ in range(200):
        m.objmix(10 ** 30, 10 ** 31); m.calls.clear()
    assert m.neg_last([1.0, 2.0]) == -2.0
    assert m.count_until(50, 7) == I["count_until"](50, 7)
    for _ in range(50):
        xs = [1.0, 2.0]; m.fill(xs, 45); m.flag(xs, 45)
        try:
            m.fill_lie([1.0], 45)
        except SystemError:
            pass
    assert m.scaled(2.0) == 5.0 and m.selfeq(float("nan")) is False
    for _ in range(50):
        m.made.clear(); m.interop(1.5, 20)
        try:
            m.calls_boom(1)
        except ValueError:
            pass
    print("ASAN-RUN-OK", m.__typedpython_deopts__)
    ''')


def asan_run(name: str, functions, script_template: str) -> tuple[str, Path]:
    """Build `functions` with ASan+UBSan, run `script_template` in a sanitized launcher; return the
    combined output and the extension path (skips when the platform cannot do it)."""
    from typedpython import cbuild

    need_runtime()
    cc = sysconfig.get_config_var("CC") or "cc"
    if sys.platform != "darwin":
        pytest.skip("sanitizer preload recipe written for macOS only")
    probe = subprocess.run(cc.split() + ["-print-file-name=libclang_rt.asan_osx_dynamic.dylib"],
                           capture_output=True, text=True)
    asan_rt = Path(probe.stdout.strip())
    if probe.returncode != 0 or not asan_rt.is_file():
        pytest.skip(f"host compiler {cc!r} has no ASan runtime ({probe.stdout.strip()!r})")
    src = source_file(name)
    from typedpython import cgen
    c_source = cgen.generate(Module(name=name, functions=tuple(f() for f in functions)), src)
    flags = list(cbuild.DEFAULT_FLAGS) + ["-O1", "-g", "-fno-omit-frame-pointer",
                                          "-fsanitize=address,undefined",
                                          "-fno-sanitize-recover=undefined"]
    so = cbuild.build(c_source, name, src.parent, runtime_dir=runtime_dir(), flags=flags)
    # macOS refuses DYLD_INSERT_LIBRARIES of the sanitizer runtime into the framework python
    # ("Sanitizer load violates platform policy", observed 2026-10-03 on Darwin 25.5), so the
    # extension is loaded by a small sanitized launcher linked against libpython: ASan is then in
    # the process from the start, as it must be.
    launcher_c = src.parent / "asan_python.c"
    launcher_c.write_text("#include <Python.h>\nint main(int argc, char **argv) "
                          "{ return Py_BytesMain(argc, argv); }\n")
    launcher = src.parent / "asan_python"
    libdir = sysconfig.get_config_var("LIBDIR")
    ldver = sysconfig.get_config_var("LDVERSION")
    link = subprocess.run(cc.split() + ["-fsanitize=address,undefined", "-g",
                                        f"-I{sysconfig.get_paths()['include']}", str(launcher_c),
                                        f"-L{libdir}", f"-lpython{ldver}", "-o", str(launcher)],
                          capture_output=True, text=True)
    if link.returncode != 0:
        pytest.skip(f"cannot link a sanitized launcher against libpython{ldver}: {link.stderr[-2000:]}")
    env = dict(os.environ, PYTHONHOME=sys.base_prefix,
               ASAN_OPTIONS="detect_leaks=0:abort_on_error=0:halt_on_error=1",
               UBSAN_OPTIONS="halt_on_error=1:print_stacktrace=1")
    env.pop("DYLD_INSERT_LIBRARIES", None)
    script = script_template.format(pkg=str(PACKAGE.parent), name=name, so=str(so))
    run = subprocess.run([str(launcher), "-c", script], capture_output=True, text=True, env=env)
    out = run.stdout + run.stderr
    if "violates platform policy" in out or "Interceptors are not working" in out:
        pytest.skip(f"ASan runtime could not be loaded: {out[-2000:]}")
    assert run.returncode == 0 and "ASAN-RUN-OK" in run.stdout, out[-6000:]
    return out, so


def assert_instrumented(so: Path, out: str) -> None:
    linked = subprocess.run(["otool", "-L", str(so)], capture_output=True, text=True).stdout
    assert "libclang_rt.asan" in linked, linked          # the extension really is instrumented
    assert "runtime error" not in out and "AddressSanitizer" not in out, out[-6000:]


def test_build_with_address_and_undefined_sanitizers():
    out, so = asan_run("tp_cgen_asan", ALL, ASAN_SCRIPT)
    assert_instrumented(so, out)


ASAN_LA_SCRIPT = textwrap.dedent('''\
    import sys
    sys.path.insert(0, {pkg!r})
    from typedpython import cbuild
    m = cbuild.load({name!r}, {so!r})
    I = m.__typedpython_interpreted__
    for n in (0, 1, 2, 3, 7, 64, -4):
        assert m.la_build(n) == I["la_build"](n), n
        assert repr(m.la_f64(n)) == repr(I["la_f64"](n)), n
    for n in (0, 1, 9):
        assert m.la_reassign(n) == I["la_reassign"](n), n
    for args in ((5, 5), (5, -6), (0, 0), (5, 4)):
        for name in ("la_index",):
            try:
                want = I[name](*args)
            except IndexError as e:
                want = str(e)
            try:
                got = getattr(m, name)(*args)
            except IndexError as e:
                got = str(e)
            assert got == want, (name, args, got, want)
    try:
        m.la_store(3, 3, 1)
    except IndexError:
        pass
    assert m.la_unit() == ()
    xs = [5, 6, 7]
    assert m.la_copy_param(xs) == (5, 99, 3) and xs == [5, 6, 7]
    for _ in range(100):
        assert m.la_overflow(1000, 2 ** 62) == I["la_overflow"](1000, 2 ** 62)
        assert m.la_overflow(1000, 1) == I["la_overflow"](1000, 1)
    print("ASAN-RUN-OK", m.__typedpython_deopts__)
    ''')


def test_local_arrays_under_address_and_undefined_sanitizers():
    need_local_array_runtime()
    out, so = asan_run("tp_cgen_asan_la", LOCALARR, ASAN_LA_SCRIPT)
    assert_instrumented(so, out)


# --- building against the embedded CPython ------------------------------------------------------

EMBEDDED_314 = (WORKTREE.parents[1] / "python-multiplatform" / "build" / "python-standalone"
                / "extracted" / "macos-aarch64" / "python" / "include" / "python3.14")


def test_builds_against_the_embedded_cpython_314_headers():
    """Compile only: the host 3.13 interpreter cannot import a 3.14 extension."""
    from typedpython import cbuild, cgen

    need_runtime()
    if not (EMBEDDED_314 / "Python.h").is_file():
        pytest.skip(f"embedded CPython 3.14 headers not extracted: {EMBEDDED_314}")
    name = "tp_cgen_314"
    src = source_file(name)
    c_source = cgen.generate(Module(name=name, functions=tuple(f() for f in ALL)), src)
    flags = list(cbuild.DEFAULT_FLAGS) + ["-Werror=deprecated-declarations",
                                          "-Werror=incompatible-pointer-types",
                                          "-Werror=int-conversion"]
    so = cbuild.build(c_source, name, src.parent, runtime_dir=runtime_dir(), flags=flags,
                      python_include=EMBEDDED_314, ext_suffix=".cpython-314-darwin.so")
    assert so.name == "tp_cgen_314.cpython-314-darwin.so" and so.stat().st_size > 0
    # the 3.14 headers were really used: the same source against them sees PY_VERSION_HEX 3.14
    probe = cbuild.build('#include <Python.h>\n#if PY_VERSION_HEX < 0x030E0000\n#error not 3.14\n#endif\n'
                         'PyMODINIT_FUNC PyInit_tp_probe(void) { return NULL; }\n',
                         "tp_probe", src.parent, runtime_dir=runtime_dir(),
                         python_include=EMBEDDED_314, ext_suffix=".cpython-314-darwin.so")
    assert probe.is_file()
