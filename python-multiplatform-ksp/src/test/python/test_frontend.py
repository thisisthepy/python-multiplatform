"""TypedPython front end: Python AST + Pyrefly types -> typed IR (#41, SPEC N-8).

Each test writes a small module and asserts on the IR `frontend.lower` produces, or on the reason
a function was left interpreted. The IR's own docstrings (typedpython/ir.py) are the contract:
mixed int/float gets `ToFloat`, chains evaluate the middle operand once, an impure function has
no node that can deopt.
"""
import dataclasses
import shutil
import textwrap
from pathlib import Path

import pytest

from typedpython import frontend
from typedpython.ir import (
    And, ArrayParam, Assign, BinOp, BinOpKind, Box, Break, Call, CallObject, Compare, CompareKind,
    CompareObj, Const, CopyArray, NewArray, Tuple, ExprStmt, While, ForRange, GetAttr, Global, If, Index, Len, Local, MathCall,
    MathFunc, ObjToFloat, Param, Return, StoreIndex, ToFloat, Truth, Type, UnaryOp, UnaryOpKind,
)

I64, F64, BOOL, OBJ = Type.I64, Type.F64, Type.BOOL, Type.OBJ
BENCH = Path("/Volumes/macMini/thisisthepy/PythonMultiplatform/.worktrees/typedpython-bench"
             "/benchmarks/typedpython/py")


def lower(tmp_path, source, name="mod.py"):
    path = tmp_path / name
    path.write_text(textwrap.dedent(source).lstrip("\n"))
    return frontend.lower(path)


def function(module, name):
    found = [f for f in module.functions if f.name == name]
    assert found, f"{name} was not lowered: {module.skipped.get(name)!r}"
    return found[0]


def nodes(root, cls):
    """Every IR node of class `cls` under `root` (a Function, statement or expression)."""
    found, stack = [], [root]
    while stack:
        x = stack.pop()
        if isinstance(x, (tuple, list)):
            stack.extend(x)
        elif dataclasses.is_dataclass(x) and not isinstance(x, type):
            if isinstance(x, cls):
                found.append(x)
            stack.extend(getattr(x, f.name) for f in dataclasses.fields(x))
    return found


# --- the basic shape -----------------------------------------------------------------------------

def test_scalar_loop_lowers_to_for_range(tmp_path):
    m = lower(tmp_path, """
        # typedpython: compiled
        def total(n: int, x: float) -> float:
            acc: float = 0.0
            for k in range(n):
                acc += x
            return acc
    """)
    assert m.skipped == {}
    f = function(m, "total")
    assert f.params == (Param("n", I64), Param("x", F64))
    assert f.returns == F64
    assert f.locals == {"acc": F64, "k": I64}
    assert f.body == (
        Assign("acc", Const(F64, 0.0)),
        ForRange("k", Const(I64, 0), Local(I64, "n"), Const(I64, 1), (
            Assign("acc", BinOp(F64, BinOpKind.ADD, Local(F64, "acc"), Local(F64, "x"))),
        )),
        Return(Local(F64, "acc")),
    )
    assert f.pure and not f.may_deopt
    assert f.source_line == 2


def test_int_locals_in_a_pure_function_are_i64_and_may_deopt(tmp_path):
    # `c` is unannotated: its type comes from Pyrefly.
    m = lower(tmp_path, """
        @compiled
        def f(a: int, b: int) -> int:
            c = a + b
            return c * 2
    """)
    f = function(m, "f")
    assert f.locals == {"c": I64}
    assert f.body[0] == Assign("c", BinOp(I64, BinOpKind.ADD, Local(I64, "a"), Local(I64, "b")))
    assert f.pure and f.may_deopt


def test_unannotated_local_with_two_types_is_skipped(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def f(a: int) -> float:
            c = a
            c = 1.5
            return c
    """)
    assert m.functions == ()
    assert "`c`" in m.skipped["f"] and "line " in m.skipped["f"]


def test_impure_function_keeps_an_unbounded_int_local_as_a_python_int(tmp_path):
    # Impure: a deopt could not be redone, so `m` is a CPython int object, not a checked i64.
    m = lower(tmp_path, """
        @compiled
        def g(a: list[float], n: int) -> None:
            m: int = n
            m += 1
            a[0] = 1.0
    """)
    f = function(m, "g")
    assert f.locals == {"m": OBJ}
    assert f.body[:2] == (
        Assign("m", Box(OBJ, Local(I64, "n"))),
        Assign("m", BinOp(OBJ, BinOpKind.ADD, Local(OBJ, "m"), Box(OBJ, Const(I64, 1)))),
    )
    assert not f.pure and not f.may_deopt


def test_impure_function_with_bounded_range_indices_lowers_without_deopt(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def h(a: list[float], n: int) -> None:
            for i in range(n):
                for j in range(i + 1, n):
                    a[j] += a[i]
    """)
    f = function(m, "h")
    assert not f.pure and not f.may_deopt
    assert f.params == (ArrayParam("a", Type.F64_ARRAY, True), Param("n", I64))


def test_impure_function_with_unproved_int_arithmetic_is_skipped(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def h(a: list[float], n: int) -> None:
            a[n * 2] = 1.0
    """)
    # `n * 2` can overflow i64 and the function is impure, so it is an int object: no native index.
    assert m.functions == ()
    assert "line 3" in m.skipped["h"] and "n * 2" in m.skipped["h"]


def test_range_index_arithmetic_is_proven(tmp_path):
    # ir.BinOp.proven / UnaryOp.proven: the interval proof is recorded in the node.
    m = lower(tmp_path, """
        @compiled
        def shift(a: list[float]) -> None:
            for i in range(4):
                a[i + 1] = a[i] - float(-i) + float(i * 2 - 1)
    """)
    f = function(m, "shift")
    assert not f.pure and not f.may_deopt
    ints = [b for b in nodes(f, BinOp) if b.type == I64]
    assert ints and all(b.proven for b in ints)
    assert BinOp(I64, BinOpKind.ADD, Local(I64, "i"), Const(I64, 1), proven=True) in ints
    assert nodes(f, UnaryOp) == [UnaryOp(I64, UnaryOpKind.NEG, Local(I64, "i"), proven=True)]


def test_unbounded_int_arithmetic_is_not_proven(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def f(a: int, b: int) -> int:
            return -(a + b)
    """)
    f = function(m, "f")
    assert f.pure and f.may_deopt
    assert f.body == (Return(UnaryOp(I64, UnaryOpKind.NEG, BinOp(
        I64, BinOpKind.ADD, Local(I64, "a"), Local(I64, "b")))),)
    assert not any(b.proven for b in nodes(f, BinOp)) and not nodes(f, UnaryOp)[0].proven


def test_float_arithmetic_is_never_marked_proven(tmp_path):
    # `proven` is for I64 ADD/SUB/MUL/NEG only (F64 never deopts).
    m = lower(tmp_path, """
        @compiled
        def f(x: float) -> float:
            return -(x + 1.0)
    """)
    f = function(m, "f")
    assert not any(n.proven for n in nodes(f, BinOp) + nodes(f, UnaryOp))


@pytest.mark.parametrize("expr", ["i // 2", "i / 2"])
def test_int_division_cannot_be_recorded_as_proven_so_it_deopts(tmp_path, expr):
    # ir.BinOp.proven exists for ADD/SUB/MUL only; the verifier cannot re-prove // and / on i64,
    # so they count as deopting: in a pure function may_deopt, in an impure one a CPython int.
    m = lower(tmp_path, f"""
@compiled
def f(n: int) -> float:
    t: float = 0.0
    for i in range(10):
        t += {expr}
    return t
""")
    assert function(m, "f").may_deopt


# --- conversions ---------------------------------------------------------------------------------

def test_mixed_int_float_arithmetic_inserts_to_float(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def mix(n: int, x: float) -> float:
            return n * x + 1
    """)
    f = function(m, "mix")
    assert f.body == (Return(BinOp(
        F64, BinOpKind.ADD,
        BinOp(F64, BinOpKind.MUL, ToFloat(F64, Local(I64, "n")), Local(F64, "x")),
        ToFloat(F64, Const(I64, 1)),
    )),)


def test_int_value_into_a_float_local_is_skipped(tmp_path):
    # CPython keeps the int: `y` would be an int at run time, not a float.
    m = lower(tmp_path, """
        @compiled
        def f(n: int) -> float:
            y: float = n
            return y
    """)
    assert m.functions == ()
    assert "line 3" in m.skipped["f"]


def test_int_true_division_gives_float(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def f(a: int, b: int) -> float:
            return a / b
    """)
    assert function(m, "f").body == (
        Return(BinOp(F64, BinOpKind.TRUEDIV, Local(I64, "a"), Local(I64, "b"))),)


def test_float_of_int_is_to_float(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def f(a: int) -> float:
            return float(a)
    """)
    assert function(m, "f").body == (Return(ToFloat(F64, Local(I64, "a"))),)


# --- chained comparison --------------------------------------------------------------------------

def test_chained_compare_of_locals(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def between(lo: int, x: int, hi: int) -> bool:
            return lo < x <= hi
    """)
    assert function(m, "between").body == (Return(And(
        BOOL,
        Compare(BOOL, CompareKind.LT, Local(I64, "lo"), Local(I64, "x")),
        Compare(BOOL, CompareKind.LE, Local(I64, "x"), Local(I64, "hi")),
    )),)


def test_chained_compare_evaluates_the_middle_once(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def sq(v: float) -> float:
            return v * v

        @compiled
        def within(lo: float, x: float, hi: float) -> bool:
            return lo < sq(x) < hi
    """)
    f = function(m, "within")
    assert len(f.body) == 2
    first, ret = f.body
    assert isinstance(first, Assign) and first.value == Call(F64, "sq", (Local(F64, "x"),))
    t = first.target
    assert f.locals[t] == F64 and t not in ("lo", "x", "hi")
    assert ret == Return(And(
        BOOL,
        Compare(BOOL, CompareKind.LT, Local(F64, "lo"), Local(F64, t)),
        Compare(BOOL, CompareKind.LT, Local(F64, t), Local(F64, "hi")),
    ))


# --- arrays --------------------------------------------------------------------------------------

def test_augmented_store_into_an_array_element(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def bump(a: list[float], i: int, e: float) -> None:
            a[i] += e
    """)
    assert function(m, "bump").body == (StoreIndex(
        "a", Local(I64, "i"),
        BinOp(F64, BinOpKind.ADD, Index(F64, "a", Local(I64, "i")), Local(F64, "e")),
    ),)


def test_augmented_store_evaluates_a_computed_index_once(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def bump_last(a: list[float], e: float) -> None:
            a[len(a) - 1] += e
    """)
    f = function(m, "bump_last")
    first, store = f.body
    assert isinstance(first, Assign)
    assert first.value == BinOp(I64, BinOpKind.SUB, Len(I64, "a"), Const(I64, 1), proven=True)
    t = first.target
    assert store == StoreIndex(
        "a", Local(I64, t),
        BinOp(F64, BinOpKind.ADD, Index(F64, "a", Local(I64, t)), Local(F64, "e")),
    )
    assert not f.pure and not f.may_deopt


def test_list_params_become_array_params_with_stored(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def scale(a: list[float], b: list[float], k: float, c: list[int]) -> None:
            for i in range(len(a)):
                a[i] = b[i] * k
    """)
    f = function(m, "scale")
    assert f.params == (
        ArrayParam("a", Type.F64_ARRAY, True), ArrayParam("b", Type.F64_ARRAY, False),
        Param("k", F64), ArrayParam("c", Type.I64_ARRAY, False),
    )
    assert f.body[0].stop == Len(I64, "a")


def test_array_param_used_whole_is_skipped(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def first(a: list[float]) -> float:
            return a[0]

        @compiled
        def caller(a: list[float]) -> float:
            return first(a)
    """)
    assert [f.name for f in m.functions] == ["first"]
    assert "`a`" in m.skipped["caller"] and "line 7" in m.skipped["caller"]


def test_int_stored_into_a_float_array_is_skipped(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def zero(a: list[float]) -> None:
            a[0] = 0
    """)
    assert m.functions == () and "line 3" in m.skipped["zero"]


# --- math ----------------------------------------------------------------------------------------

def test_math_sqrt_of_stdlib_math(tmp_path):
    m = lower(tmp_path, """
        import math

        @compiled
        def norm(x: float, y: float) -> float:
            return math.sqrt(x * x + y * y)
    """)
    assert function(m, "norm").body == (Return(MathCall(F64, MathFunc.SQRT, (BinOp(
        F64, BinOpKind.ADD,
        BinOp(F64, BinOpKind.MUL, Local(F64, "x"), Local(F64, "x")),
        BinOp(F64, BinOpKind.MUL, Local(F64, "y"), Local(F64, "y")),
    ),)),),)


@pytest.mark.parametrize("binding", ["import cmath as math", "import math\nmath = 3"])
def test_shadowed_math_is_skipped(tmp_path, binding):
    m = lower(tmp_path, binding + """

@compiled
def norm(x: float) -> float:
    return math.sqrt(x)
""")
    assert m.functions == ()
    assert "math" in m.skipped["norm"]


# --- calls and purity ----------------------------------------------------------------------------

def test_impure_caller_redoes_a_deopting_pure_callee(tmp_path):
    # ir.Call.redo: on a deopt only the (pure) callee is redone; the caller does not deopt.
    m = lower(tmp_path, """
        @compiled
        def tri(i: int, j: int) -> float:
            return 1.0 / ((i + j) * (i + j + 1) // 2 + i + 1)

        @compiled
        def fill(n: int, out: list[float]) -> None:
            for i in range(n):
                out[i] = tri(i, i)
    """)
    tri = function(m, "tri")
    assert tri.pure and tri.may_deopt
    fill = function(m, "fill")
    assert not fill.pure and not fill.may_deopt
    assert nodes(fill, Call) == [Call(F64, "tri", (Local(I64, "i"), Local(I64, "i")), redo=True)]
    assert nodes(fill, CallObject) == []


@pytest.mark.parametrize("returns, body, use", [
    ("bool", "return i + j > 0", "if tri(i, i):\n            out[i] = 1.0"),
    ("None", "k = i + j", "tri(i, i)\n        out[i] = 1.0"),
])
def test_redo_call_for_bool_and_none_callees(tmp_path, returns, body, use):
    m = lower(tmp_path, f"""
@compiled
def tri(i: int, j: int) -> {returns}:
    {body}

@compiled
def fill(n: int, out: list[float]) -> None:
    for i in range(n):
        {use}
""")
    assert function(m, "tri").may_deopt
    fill = function(m, "fill")
    assert not fill.pure and not fill.may_deopt
    [call] = nodes(fill, Call)
    assert call.function == "tri" and call.redo


def test_impure_caller_of_an_int_returning_deopting_callee_calls_the_global(tmp_path):
    # An I64 result of a redo could be a big int: such a callee is still called as an object.
    m = lower(tmp_path, """
        @compiled
        def add(i: int, j: int) -> int:
            return i + j

        @compiled
        def fill(n: int, out: list[float]) -> None:
            for i in range(n):
                out[i] = float(add(i, i))
    """)
    fill = function(m, "fill")
    assert not fill.pure and not fill.may_deopt
    assert nodes(fill, Call) == []
    assert nodes(fill, CallObject) == [
        CallObject(OBJ, Global(OBJ, "add"), (Box(OBJ, Local(I64, "i")), Box(OBJ, Local(I64, "i"))))]


def test_pure_caller_of_a_deopting_callee_does_not_redo(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def tri(i: int, j: int) -> float:
            return 1.0 / (i + j)

        @compiled
        def twice(i: int) -> float:
            return tri(i, i) * 2.0
    """)
    twice = function(m, "twice")
    assert twice.pure and twice.may_deopt
    assert [c.redo for c in nodes(twice, Call)] == [False]


def test_pure_caller_of_a_deopting_function_lowers(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def add(i: int, j: int) -> int:
            return i + j

        @compiled
        def twice(i: int) -> int:
            return add(i, i)
    """)
    twice = function(m, "twice")
    assert twice.body == (Return(Call(I64, "add", (Local(I64, "i"), Local(I64, "i")))),)
    assert twice.pure and twice.may_deopt


def test_caller_of_an_interpreted_function_calls_it_as_an_object(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def bad(x: float) -> float:
            try:
                return x
            except ValueError:
                return 0.0

        @compiled
        def good(x: float) -> object:
            return bad(x)

        @compiled
        def typed(x: float) -> float:
            return bad(x)
    """)
    assert [f.name for f in m.functions] == ["good"]
    assert "line 3" in m.skipped["bad"] and "try" in m.skipped["bad"]
    assert function(m, "good").body == (
        Return(CallObject(OBJ, Global(OBJ, "bad"), (Box(OBJ, Local(F64, "x")),))),)
    # The result is an object; a float return would need a conversion CPython does not make.
    assert "bad(x)" in m.skipped["typed"] and "line 14" in m.skipped["typed"]


# --- what is (not) lowered -----------------------------------------------------------------------

@pytest.mark.parametrize("body, what", [
    ("    try:\n        pass\n    except ValueError:\n        pass\n", "try"),
    ("    with x:\n        pass\n", "with"),
    ("    yield x\n", "yield"),
    ("    global q\n", "global"),
    ("    y = [x]\n", "list"),
    ("    y = (lambda: x)\n", "lambda"),
])
def test_unsupported_construct_is_skipped_with_its_line(tmp_path, body, what):
    m = lower(tmp_path, "@compiled\ndef f(x: float) -> None:\n" + body)
    assert m.functions == ()
    assert "line 3" in m.skipped["f"] and what in m.skipped["f"]


@pytest.mark.parametrize("signature", [
    "def f(x: float = 1.0) -> float:", "def f(*x: float) -> float:", "def f(x) -> float:",
    "def f(x: float):", "def f(*, x: float) -> float:", "def f(x: float, /) -> float:",
    "@staticmethod\ndef f(x: float) -> float:",
])
def test_unsupported_signature_is_skipped(tmp_path, signature):
    m = lower(tmp_path, f"@compiled\n{signature}\n    return 1.0\n")
    assert m.functions == () and "f" in m.skipped


def test_global_read_in_a_function_that_runs_user_code_is_a_global_node(tmp_path):
    # `print(x)` runs user code, which could rebind K: K is read where it is used (LOAD_GLOBAL).
    m = lower(tmp_path, """
        K: float = 2.0

        @compiled
        def f(x: float) -> object:
            print(x)
            return x * K

        @compiled
        def g(x: float) -> float:
            print(x)
            return x * K
    """)
    f = function(m, "f")
    assert f.body[1] == Return(
        BinOp(OBJ, BinOpKind.MUL, Box(OBJ, Local(F64, "x")), Global(OBJ, "K")))
    assert f.entry_globals == ()
    assert not f.pure and not f.may_deopt
    assert "x * K" in m.skipped["g"]


def test_scalar_globals_in_a_closed_function_are_entry_globals(tmp_path):
    m = lower(tmp_path, """
        PI: float = 3.141592653589793
        SOLAR_MASS: float = 4 * PI * PI
        DAYS = 365.24
        N = 3
        ON: bool = True

        @compiled
        def scale(a: list[float]) -> None:
            for i in range(N):
                if ON:
                    a[i] = a[i] / SOLAR_MASS * DAYS

        @compiled
        def k(x: float) -> float:
            return x * SOLAR_MASS
    """)
    scale = function(m, "scale")
    assert scale.entry_globals == (Param("N", I64), Param("ON", BOOL), Param("SOLAR_MASS", F64),
                                   Param("DAYS", F64))
    assert nodes(scale, Global) == []
    assert Local(F64, "SOLAR_MASS") in nodes(scale, Local)
    assert not scale.pure and not scale.may_deopt
    k = function(m, "k")
    assert k.entry_globals == (Param("SOLAR_MASS", F64),)
    assert k.body == (Return(BinOp(F64, BinOpKind.MUL, Local(F64, "x"), Local(F64, "SOLAR_MASS"))),)
    assert k.pure and not k.may_deopt
    assert "SOLAR_MASS" not in k.locals


def test_closedness_follows_calls(tmp_path):
    m = lower(tmp_path, """
        K: float = 2.0

        @compiled
        def noisy(x: float) -> float:
            print(x)
            return x

        @compiled
        def quiet(x: float) -> float:
            return x + 1.0

        @compiled
        def via_noisy(x: float) -> object:
            return noisy(x) * K

        @compiled
        def via_quiet(x: float) -> float:
            return quiet(x) * K
    """)
    assert function(m, "via_noisy").entry_globals == ()
    assert Global(OBJ, "K") in nodes(function(m, "via_noisy"), Global)
    assert function(m, "via_quiet").entry_globals == (Param("K", F64),)


@pytest.mark.parametrize("module", [
    "K: float = 2.0\nK = 3.0\n",                                         # assigned twice
    "K: float = 2.0\n\ndef reset() -> None:\n    global K\n    K = 1.0\n",  # `global K` elsewhere
    "import os\nif os.sep:\n    K = 2.0\n",                             # not at top level
    "for K in (1.0,):\n    pass\n",                                      # a loop target
    "import os\nK = os.cpu_count()\n",                                   # neither annotated nor literal
    "K: str = 'a'\n",                                                    # not a scalar
    "K: float = 2.0\ndel K\n",                                           # deleted
])
def test_a_global_that_may_be_rebound_is_never_an_entry_global(tmp_path, module):
    m = lower(tmp_path, module + """

@compiled
def f(x: float) -> object:
    return x * K
""")
    f = function(m, "f")
    assert f.entry_globals == ()
    assert Global(OBJ, "K") in nodes(f, Global)


def test_module_marker_compiles_every_module_level_def(tmp_path):
    m = lower(tmp_path, """

        # typedpython: compiled
        def a(x: float) -> float:
            return x

        def b(x: float) -> float:
            return -x

        @staticmethod
        def c(x: float) -> float:
            return x
    """)
    assert [f.name for f in m.functions] == ["a", "b"]
    assert set(m.skipped) == {"c"} and "decorator" in m.skipped["c"]


def test_without_marker_only_compiled_functions_are_lowered(tmp_path):
    m = lower(tmp_path, """
        x: int = 1
        # typedpython: compiled
        def a(x: float) -> float:
            return x

        @compiled
        def b(x: float) -> float:
            return -x
    """)
    assert [f.name for f in m.functions] == ["b"]
    assert m.skipped == {}


# --- objects: Kotlin interop and any Python value -------------------------------------------------

def test_compiled_code_calls_imported_callables_around_native_float_math(tmp_path):
    (tmp_path / "kotlinlib.py").write_text(textwrap.dedent("""
        class Canvas:
            pass

        def make_canvas(n: int) -> Canvas:
            return Canvas()

        def draw(c: Canvas, x: float, alpha: float) -> None:
            pass
    """))
    m = lower(tmp_path, """
        import math
        from kotlinlib import draw, make_canvas

        @compiled
        def render(n: int, scale: float) -> float:
            canvas = make_canvas(n)
            total: float = 0.0
            hits: int = 0
            for i in range(n):
                x: float = i * scale
                total += math.sqrt(x)
                draw(canvas, x, alpha=0.5)
                hits += 1
                if hits > n:
                    break
            return total
    """)
    f = function(m, "render")
    assert not f.pure and not f.may_deopt
    assert f.locals == {"canvas": OBJ, "total": F64, "hits": OBJ, "i": I64, "x": F64}
    assert f.body[0] == Assign("canvas", CallObject(
        OBJ, Global(OBJ, "make_canvas"), (Box(OBJ, Local(I64, "n")),)))
    loop = f.body[3]
    assert isinstance(loop, ForRange) and loop.var == "i"
    assert loop.body == (
        Assign("x", BinOp(F64, BinOpKind.MUL, ToFloat(F64, Local(I64, "i")), Local(F64, "scale"))),
        Assign("total", BinOp(F64, BinOpKind.ADD, Local(F64, "total"),
                              MathCall(F64, MathFunc.SQRT, (Local(F64, "x"),)))),
        ExprStmt(CallObject(OBJ, Global(OBJ, "draw"), (
            Local(OBJ, "canvas"), Box(OBJ, Local(F64, "x")), Box(OBJ, Const(F64, 0.5)),
        ), ("alpha",))),
        Assign("hits", BinOp(OBJ, BinOpKind.ADD, Local(OBJ, "hits"), Box(OBJ, Const(I64, 1)))),
        If(CompareObj(BOOL, CompareKind.GT, Local(OBJ, "hits"), Box(OBJ, Local(I64, "n"))),
           (Break(),)),
    )


def test_object_truth_float_and_method_calls(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def f(o: object, x: float) -> float:
            if o:
                return float(o.scale(x))
            return x
    """)
    assert function(m, "f").body == (
        If(Truth(BOOL, Local(OBJ, "o")), (Return(ObjToFloat(F64, CallObject(
            OBJ, GetAttr(OBJ, Local(OBJ, "o"), "scale"), (Box(OBJ, Local(F64, "x")),)))),)),
        Return(Local(F64, "x")),
    )


def test_object_comparison_outside_a_condition_is_skipped(tmp_path):
    # `a < b` on objects returns whatever __lt__ returns, which need not be a bool.
    m = lower(tmp_path, """
        @compiled
        def f(o: object, x: float) -> bool:
            return o < x
    """)
    assert m.functions == () and "line 3" in m.skipped["f"]


@pytest.mark.parametrize("body", ["return not (o < x)", "r: bool = c and o < x\n    return r"])
def test_object_comparison_not_directly_a_condition_is_skipped(tmp_path, body):
    m = lower(tmp_path, f"@compiled\ndef f(o: object, x: float, c: bool) -> bool:\n    {body}\n")
    assert m.functions == () and "line 3" in m.skipped["f"]


def test_object_comparison_as_an_operand_of_a_condition(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def f(o: object, x: float, c: bool) -> int:
            if c and o < x:
                return 1
            return 0
    """)
    assert function(m, "f").body[0].cond == And(
        BOOL, Local(BOOL, "c"),
        CompareObj(BOOL, CompareKind.LT, Local(OBJ, "o"), Box(OBJ, Local(F64, "x"))))


def test_while_condition_with_a_temporary_reevaluates_it_each_iteration(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def sq(v: float) -> float:
            return v * v

        @compiled
        def shrink(lo: float, x: float, hi: float) -> float:
            while lo < sq(x) < hi:
                x = x * 0.5
            return x
    """)
    loop = function(m, "shrink").body[0]
    assert isinstance(loop, While) and loop.cond == Const(BOOL, True)
    temp, test = loop.body[0], loop.body[1]
    assert isinstance(temp, Assign) and temp.value == Call(F64, "sq", (Local(F64, "x"),))
    # The condition stays an If condition (never under `not`), and exits through its else.
    assert isinstance(test, If) and isinstance(test.cond, And)
    assert test.then == () and test.orelse == (Break(),)


@pytest.mark.parametrize("source", [
    "@compiled\ndef f(n: int) -> int:\n    return f(n)\n",
    "@compiled\ndef f(n: int) -> int:\n    return g(n)\n\n"
    "@compiled\ndef g(n: int) -> int:\n    return f(n)\n",
])
def test_recursion_is_lowered_not_skipped(tmp_path, source):
    # #57: a call cycle is compiled; the run-time depth guard replaces the old refusal.
    m = lower(tmp_path, source)
    assert {f.name for f in m.functions} >= {"f"} and not any("recurs" in r for r in m.skipped.values())


def test_int_modulo_cannot_be_recorded_as_proven_so_it_deopts(tmp_path):
    # ir.BinOp.proven covers ADD/SUB/MUL only and the verifier counts an unproven i64 `%` as a
    # deopting node: in a pure function that is may_deopt, in an impure one a CPython int.
    m = lower(tmp_path, """
        @compiled
        def pure_mod(i: int, n: int) -> int:
            return i % n

        @compiled
        def put(a: list[float], i: int, n: int) -> None:
            a[i % n] = 1.0
    """)
    f = function(m, "pure_mod")
    assert f.pure and f.may_deopt
    assert not any(b.proven for b in nodes(f, BinOp))
    assert "i % n" in m.skipped["put"]


def test_locals_exclude_parameters(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def f(n: int) -> int:
            n = n + 1
            return n
    """)
    assert function(m, "f").locals == {}


# --- benchmarks ----------------------------------------------------------------------------------

@pytest.mark.skipif(not BENCH.exists(), reason="benchmark worktree not present")
def test_nbody_kernels_lower(tmp_path):
    path = tmp_path / "nbody.py"
    shutil.copy(BENCH / "nbody.py", path)
    path.write_text("# typedpython: compiled\n" + path.read_text())
    m = frontend.lower(path)
    for name in ("advance", "energy", "offset_momentum"):
        function(m, name)
    advance = function(m, "advance")
    assert not advance.pure and not advance.may_deopt
    assert BinOp(I64, BinOpKind.ADD, Local(I64, "i"), Const(I64, 1), proven=True) \
        in nodes(advance, BinOp)
    energy = function(m, "energy")
    assert energy.pure and not energy.may_deopt
    offset = function(m, "offset_momentum")
    assert not offset.pure and not offset.may_deopt
    assert offset.entry_globals == (Param("SOLAR_MASS", F64),)
    assert nodes(offset, Global) == []


@pytest.mark.skipif(not BENCH.exists(), reason="benchmark worktree not present")
def test_spectral_norm_kernels_lower(tmp_path):
    path = tmp_path / "spectral_norm.py"
    path.write_text("# typedpython: compiled\n" + (BENCH / "spectral_norm.py").read_text())
    m = frontend.lower(path)
    eval_a = function(m, "eval_a")
    assert eval_a.pure and eval_a.may_deopt
    for name in ("mul_av", "mul_atv"):
        f = function(m, name)
        assert not f.pure and not f.may_deopt
        [call] = nodes(f, Call)
        assert call.function == "eval_a" and call.redo


# --- local fixed-size arrays and tuple returns (ir.NewArray / CopyArray / Tuple, #41 M2) ---------

def test_list_range_local_is_an_iota_array(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def f(n: int) -> int:
            x: list[int] = list(range(n))
            return x[0]
    """)
    f = function(m, "f")
    assert f.locals == {"x": Type.I64_ARRAY}
    assert f.body == (
        Assign("x", NewArray(Type.I64_ARRAY, Local(I64, "n"), None, True)),
        Return(Index(I64, "x", Const(I64, 0))),
    )
    assert f.pure and not f.may_deopt


def test_fill_locals_are_new_arrays_of_their_element_type(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def f(n: int) -> float:
            a: list[int] = [0] * n
            b = [1.5] * n
            return b[a[0]]
    """)
    f = function(m, "f")
    assert f.locals == {"a": Type.I64_ARRAY, "b": Type.F64_ARRAY}
    assert f.body[:2] == (
        Assign("a", NewArray(Type.I64_ARRAY, Local(I64, "n"), Const(I64, 0))),
        Assign("b", NewArray(Type.F64_ARRAY, Local(I64, "n"), Const(F64, 1.5))),
    )
    assert f.pure


def test_slice_copy_of_a_local_and_of_a_parameter(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def f(p: list[int], n: int) -> int:
            x: list[int] = list(range(n))
            y = x[:]
            z: list[int] = p[:]
            return y[0] + z[0]
    """)
    f = function(m, "f")
    assert f.locals == {"x": Type.I64_ARRAY, "y": Type.I64_ARRAY, "z": Type.I64_ARRAY}
    assert f.body[1] == Assign("y", CopyArray(Type.I64_ARRAY, "x"))
    assert f.body[2] == Assign("z", CopyArray(Type.I64_ARRAY, "p"))
    assert f.params[0] == ArrayParam("p", Type.I64_ARRAY, False)


@pytest.mark.parametrize("what, source, snippet", [
    ("returned", "return x", "return x"),
    ("passed to a call", "print(x)\n    return 0", "print(x)"),
    ("compared", "return x == y", "x == y"),
    ("iterated over", "for v in x:\n        pass\n    return 0", "x"),
    ("sliced other than [:]", "w = x[1:]\n    return 0", "x[1:]"),
    ("appended to", "x.append(1)\n    return 0", "x.append(1)"),
    ("aliased", "w = x\n    return 0", "w = x"),
    ("copied outside an assignment", "return len(x[:])", "x[:]"),
    ("rebound by a non-array value", "x = 5\n    return 0", "x"),
])
def test_local_array_escapes_skip_the_function(tmp_path, what, source, snippet):
    source = source.replace("\n    ", "\n            ")  # the f-string below is dedented
    m = lower(tmp_path, f"""
        @compiled
        def f(n: int) -> int:
            x: list[int] = list(range(n))
            y: list[int] = [0] * n
            {source}
    """)
    assert "f" not in [fn.name for fn in m.functions], what
    reason = m.skipped["f"]
    assert snippet in reason, (what, reason)
    assert "array" in reason, (what, reason)


@pytest.mark.parametrize("annotation, value", [
    ("list[str]", '["a"] * n'),
    ("list[bool]", "[True] * n"),
    ("list[list[int]]", "[[0]] * n"),
])
def test_local_array_element_type_must_be_exactly_int_or_float(tmp_path, annotation, value):
    m = lower(tmp_path, f"""
        @compiled
        def f(n: int) -> int:
            x: {annotation} = {value}
            return 0
    """)
    assert "f" in m.skipped
    assert "exactly int or float" in m.skipped["f"], m.skipped["f"]


def test_int_fill_of_a_float_array_is_skipped(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def f(n: int) -> int:
            x: list[float] = [0] * n
            return 0
    """)
    assert "f" in m.skipped and "float" in m.skipped["f"]


def test_function_with_only_local_array_stores_is_pure(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def f(n: int) -> int:
            x: list[int] = [0] * n
            for i in range(n):
                x[i] = i * i
                x[i] += 1
            return x[n - 1] * n
    """)
    f = function(m, "f")
    assert nodes(f, StoreIndex) and f.pure
    # pure, so the unbounded int arithmetic is native with deopt, not a CPython int
    assert f.may_deopt
    assert f.locals["x"] == Type.I64_ARRAY and f.returns == I64


def test_function_storing_into_an_array_param_is_still_impure(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def f(p: list[int], n: int) -> int:
            x: list[int] = [0] * n
            p[0] = 1
            x[0] = 2
            return x[0]
    """)
    f = function(m, "f")
    assert not f.pure and not f.may_deopt
    assert f.params[0] == ArrayParam("p", Type.I64_ARRAY, True)


def test_storing_a_local_array_into_nothing_but_locals_keeps_calls_impure(tmp_path):
    # An object call is an effect whatever the arrays do.
    m = lower(tmp_path, """
        @compiled
        def f(n: int, g: object) -> int:
            x: list[int] = [0] * n
            x[0] = 1
            g(1)
            return x[0]
    """)
    assert not function(m, "f").pure


def test_tuple_return_boxes_each_element(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def f(a: int, b: float) -> tuple[int, float]:
            return a, b
    """)
    f = function(m, "f")
    assert f.returns == OBJ
    assert f.body == (Return(Tuple(OBJ, (Box(OBJ, Local(I64, "a")), Box(OBJ, Local(F64, "b"))))),)
    assert f.pure


def test_tuple_return_with_object_element_and_deopting_arithmetic(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def f(a: int, b: int, o: object) -> tuple[int, object]:
            return a // b, o
    """)
    f = function(m, "f")
    assert f.pure and f.may_deopt
    [ret] = nodes(f, Return)
    assert isinstance(ret.value, Tuple) and ret.value.elements[1] == Local(OBJ, "o")


@pytest.mark.parametrize("annotation, value, snippet", [
    ("tuple[int, float]", "a, b", "float"),
    ("object", "a, b", "tuple["),
    ("tuple[int, int, int]", "a, b", "declares 3"),
    ("tuple[int, ...]", "a, b", "..."),
])
def test_tuple_return_must_match_its_annotation(tmp_path, annotation, value, snippet):
    m = lower(tmp_path, f"""
        @compiled
        def f(a: int, b: int) -> {annotation}:
            return {value}
    """)
    assert "f" in m.skipped
    assert snippet in m.skipped["f"], m.skipped["f"]


@pytest.mark.skipif(not BENCH.exists(), reason="benchmark worktree not present")
def test_fannkuch_lowers(tmp_path):
    path = tmp_path / "fannkuch.py"
    path.write_text("# typedpython: compiled\n" + (BENCH / "fannkuch.py").read_text())
    m = frontend.lower(path)
    assert "fannkuch" in [f.name for f in m.functions], m.skipped.get("fannkuch")
    f = function(m, "fannkuch")
    assert f.pure and f.may_deopt
    assert f.returns == OBJ and f.params == (Param("n", I64),)
    assert f.locals["perm1"] == f.locals["count"] == f.locals["perm"] == Type.I64_ARRAY
    assert len(nodes(f, NewArray)) == 2 and len(nodes(f, CopyArray)) == 1
    assert nodes(f, Tuple)
