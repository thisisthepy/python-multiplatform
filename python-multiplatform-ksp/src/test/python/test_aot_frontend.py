"""AOT mode of the front end and the verifier (SPEC N-17, #451; agent proposal, not owner-confirmed).

`frontend.lower(path, aot=True)` and `verify.verify(module, aot=True)` read an AOT program as it is
written: `print` is a statement of its own, module constants are typed values, the `__main__` block is
the entry function, and an int local is `I64` (there is no `INT`, nothing deopts). With the flag off
nothing changes: the same text lowered by the default mode gives the IR it always gave. No C compiler is
needed here (the front end needs Pyrefly, like test_frontend.py).
"""
import dataclasses
import textwrap
from pathlib import Path

import pytest

from conftest import decorate_compiled
from typedpython import aot, frontend, ir, verify
from typedpython.aot import rep
from typedpython.ir import Type


def write(tmp_path, source, name="prog.py") -> Path:
    path = tmp_path / name
    path.write_text(textwrap.dedent(source).lstrip("\n"))
    return path


def walk(x):
    """Every dataclass node under `x` (an IR tuple, statement or expression)."""
    if isinstance(x, (tuple, list)):
        for y in x:
            yield from walk(y)
    elif dataclasses.is_dataclass(x) and not isinstance(x, type):
        yield x
        for f in dataclasses.fields(x):
            yield from walk(getattr(x, f.name))


def aot_ir(tmp_path, source):
    return frontend.lower(write(tmp_path, source), aot=True)


def function(module, name):
    [f] = [f for f in module.functions if f.name == name]
    return f


TOTAL = """
def total(n: int) -> None:
    s = 0
    for i in range(n):
        s += i
    print("total", s)
"""


# --- print ---------------------------------------------------------------------------------------------

def test_print_is_a_statement_and_ints_stay_i64(tmp_path):
    f = function(aot_ir(tmp_path, TOTAL), "total")
    assert f.pure and not [n for n in walk(f.body) if isinstance(n, (ir.CallObject, ir.Global))]
    assert f.locals["s"] is Type.I64 and f.locals["i"] is Type.I64
    [p] = [n for n in walk(f.body) if isinstance(n, ir.Print)]
    assert isinstance(p.args[0], ir.Const) and p.args[0].value == "total"
    assert p.args[1] == ir.Local(Type.I64, "s")
    assert Type.INT not in {n.type for n in walk(f.body) if isinstance(n, ir.Expr)}
    # each int operation carries its proof flag as in a pure function: `s += i` grows, so it is unproved
    adds = [n for n in walk(f.body) if isinstance(n, ir.BinOp) and n.op is ir.BinOpKind.ADD]
    assert adds and not any(a.proven for a in adds)


def test_default_mode_is_unchanged_for_the_same_text(tmp_path):
    """The contrast: with the flag off `print` is an object call, the function is impure and its
    unproved int is the N-13 `INT`."""
    [f] = frontend.lower(write(tmp_path, decorate_compiled(TOTAL))).functions
    assert not f.pure and f.locals["s"] is Type.INT
    assert not [n for n in walk(f.body) if isinstance(n, ir.Print)]
    assert [n for n in walk(f.body) if isinstance(n, ir.CallObject)]


# --- module constants ----------------------------------------------------------------------------------

CONSTANTS = """
PI: float = 3.14
N = 10
SOLAR_MASS: float = 4 * PI * PI

def power(x: float, n: int) -> float:
    if n <= 0:
        return 1.0
    return x * power(x, n - 1)

def area(r: float) -> float:
    return PI * power(r, 2) + SOLAR_MASS + N
"""


def test_module_constants_are_named_consts_never_object_reads(tmp_path):
    """`area` reads constants and calls a recursive function: no entry global, no `Global`."""
    area = function(aot_ir(tmp_path, CONSTANTS), "area")
    assert area.pure and not area.entry_globals
    assert not [n for n in walk(area.body) if isinstance(n, ir.Global)]
    named = {n.name: n for n in walk(area.body) if isinstance(n, ir.Const) and n.name is not None}
    assert {k: (v.type, v.value) for k, v in named.items()} == {
        "PI": (Type.F64, 3.14), "SOLAR_MASS": (Type.F64, 4 * 3.14 * 3.14), "N": (Type.I64, 10)}


def test_the_default_mode_still_reads_them_as_globals_here(tmp_path):
    """What the closed-function workaround of #449 was for: with the flag off a global read in a
    function that calls a recursive one is an object read, and the function stays interpreted."""
    m = frontend.lower(write(tmp_path, decorate_compiled(CONSTANTS)))
    assert [f.name for f in m.functions] == ["power"]
    assert "object" in m.skipped["area"]


def test_assigning_a_module_constant_makes_it_a_local(tmp_path):
    m = aot_ir(tmp_path, """
        N = 10

        def f(x: int) -> int:
            N = x % 3
            return N

        def g() -> int:
            return N % 4
        """)
    f, g = function(m, "f"), function(m, "g")
    assert f.locals["N"] is Type.I64
    assert not [n for n in walk(f.body) if isinstance(n, ir.Const) and n.name == "N"]
    assert [n for n in walk(g.body) if isinstance(n, ir.Const) and n.name == "N" and n.value == 10]


def test_range_step_may_be_an_int_constant(tmp_path):
    m = aot_ir(tmp_path, """
        STEP = 3

        def f(n: int) -> None:
            for i in range(0, n, STEP):
                print(i)
            for j in range(n, 0, -STEP):
                print(j)
        """)
    steps = [n.step.value for n in walk(function(m, "f").body) if isinstance(n, ir.ForRange)]
    assert steps == [3, -3]


@pytest.mark.parametrize("source, text", [
    ("X: float = 3\n", "annotated `float` but its value is an int"),
    ("X: int = True\n", "annotated `int` but its value is a bool"),
    ("X: str = 3\n", "not int, float or bool"),
    ("X: float\n", "needs a value"),
])
def test_a_constant_whose_annotation_disagrees_is_an_error(tmp_path, source, text):
    with pytest.raises(aot.AotError) as raised:
        aot.generate(write(tmp_path, source + '\nif __name__ == "__main__":\n    pass\n'))
    [f] = raised.value.findings
    assert (f.rule, f.line, f.name) == ("aot/constant", 1, "X") and text in f.message


def test_annotated_constants_fold_with_python_arithmetic(tmp_path):
    c = aot.generate(write(tmp_path, """
        PI: float = 3.14
        SOLAR_MASS: float = 4 * PI * PI
        LIMIT: int = 7

        def f(x: float) -> float:
            return x * SOLAR_MASS

        if __name__ == "__main__":
            print(f(2.0), LIMIT)
        """))
    assert repr(4 * 3.14 * 3.14) in c and "INT64_C(7)" in c


# --- the entry block -----------------------------------------------------------------------------------

def test_entry_block_is_the_entry_function_with_the_files_own_lines(tmp_path):
    source = """
        def f(x: int) -> int:
            return x

        if __name__ == "__main__":
            a = f(1)
            print(a)
        """
    m = aot_ir(tmp_path, source)
    assert [f.name for f in m.functions] == ["f", frontend.AOT_ENTRY]
    entry = function(m, frontend.AOT_ENTRY)
    assert entry.params == () and entry.returns is Type.NONE and entry.locals == {"a": Type.I64}
    assert entry.source_line == 4       # the `if` line of the file itself, nothing was rewritten


def test_a_decorator_is_accepted_and_optional_by_the_front_end(tmp_path):
    m = aot_ir(tmp_path, """
        @compiled
        def f(x: float) -> float:
            return x * 2.0

        @compiled(parallel=False, fastmath=False)
        def g(x: float) -> float:
            return x + 1.0

        def h(x: float) -> float:
            return x - 1.0
        """)
    assert [f.name for f in m.functions] == ["f", "g", "h"] and not m.skipped


def test_main_called_from_the_entry_block_when_main_prints(tmp_path):
    c = aot.generate(write(tmp_path, """
        def main() -> None:
            print("in main")

        if __name__ == "__main__":
            main()
        """))
    assert "tpf_main()" in c and 'tpa_print_str("in main", 7)' in c


# --- the verifier --------------------------------------------------------------------------------------

def test_print_is_known_to_the_verifier_only_in_aot_mode(tmp_path):
    m = aot_ir(tmp_path, TOTAL)
    proved, diagnostics = verify.verify(m, aot=True)
    assert [f.name for f in proved.functions] == ["total"] and not diagnostics
    proved, diagnostics = verify.verify(m)
    assert not proved.functions
    assert {d.rule for d in diagnostics} >= {"verify/unknown-node"}


def test_the_deopt_in_impure_function_rule_is_off_only_in_aot_mode(tmp_path):
    """`g(1.5)` passes a float for an int, so it is an object call (the function is impure), and `a + b`
    is an unproved int operation (it can deopt). The default verifier refuses that pair because a redo
    would repeat the call; with no interpreter there is no redo, and the back end names the call."""
    m = aot_ir(tmp_path, """
        def g(n: int) -> int:
            return n

        def f(a: int, b: int) -> int:
            g(1.5)
            return a + b
        """)
    f = function(m, "f")
    assert not f.pure and f.may_deopt
    _, in_aot = verify.verify(m, aot=True)
    assert "verify/deopt-impure" not in {d.rule for d in in_aot if d.function == "f"}
    _, in_default = verify.verify(m)
    assert "verify/deopt-impure" in {d.rule for d in in_default if d.function == "f"}


def test_other_rules_stay_on_in_aot_mode(tmp_path):
    """Definite assignment is a rule of the program, not of the fallback."""
    m = aot_ir(tmp_path, """
        def f(n: int) -> int:
            if n > 0:
                x = 1
            return x
        """)
    proved, diagnostics = verify.verify(m, aot=True)
    assert "verify/definite-assignment" in {d.rule for d in diagnostics}
    assert not proved.functions


# --- native lists as values ----------------------------------------------------------------------------

LISTS = """
def make(n: int) -> list[float]:
    return [0.5] * n

def total(a: list[float]) -> float:
    s = 0.0
    for i in range(len(a)):
        s += a[i]
    return s

def run(n: int) -> float:
    a = make(n)
    b = a
    c = a[:]
    d = [1.0, 2.0]
    e = list(range(n))
    return total(a) + total(b) + total(c) + total(d) + float(e[0])
"""


def assigned(f, name):
    [a] = [n for n in walk(f.body) if isinstance(n, ir.Assign) and n.target == name]
    return a.value


def test_a_list_is_a_native_array_value(tmp_path):
    m = aot_ir(tmp_path, LISTS)
    make, total, run = function(m, "make"), function(m, "total"), function(m, "run")
    assert make.returns is Type.F64_ARRAY and run.returns is Type.F64
    assert isinstance(total.params[0], ir.ArrayParam) and total.params[0].type is Type.F64_ARRAY
    assert {k: v for k, v in run.locals.items() if v in ir.ARRAYS} == {
        "a": Type.F64_ARRAY, "b": Type.F64_ARRAY, "c": Type.F64_ARRAY, "d": Type.F64_ARRAY, "e": Type.I64_ARRAY}
    # `a = make(n)` takes its type from the callee, a call that returns a list
    call = assigned(run, "a")
    assert isinstance(call, ir.Call) and call.function == "make" and call.type is Type.F64_ARRAY
    # `b = a`: both names mean the one list
    assert assigned(run, "b") == ir.Local(Type.F64_ARRAY, "a")
    assert assigned(run, "c") == ir.CopyArray(Type.F64_ARRAY, "a")
    d = assigned(run, "d")
    assert isinstance(d, ir.ListDisplay) and d.type is Type.F64_ARRAY and [x.value for x in d.elements] == [1.0, 2.0]
    e = assigned(run, "e")
    assert isinstance(e, ir.NewArray) and e.iota and e.type is Type.I64_ARRAY
    [ret] = [n for n in walk(make.body) if isinstance(n, ir.Return)]
    assert isinstance(ret.value, ir.NewArray) and not ret.value.iota and ret.value.fill == ir.Const(Type.F64, 0.5)
    # a list is passed to a program function by name: the callee gets the caller's own list
    calls = [n for n in walk(run.body) if isinstance(n, ir.Call) and n.function == "total"]
    assert [c.args for c in calls] == [(ir.Local(Type.F64_ARRAY, x),) for x in "abcd"]
    proved, diagnostics = verify.verify(m, aot=True)
    assert not diagnostics and [f.name for f in proved.functions] == ["make", "total", "run"]


def test_three_or_more_list_parameters_are_not_read_element_by_element(tmp_path):
    """The default mode copies lists in, so 3 or more are read one element at a time and may deopt. In AOT mode a
    list is the caller's own array: no copy-in, no deopt, no entry exit, however many parameters."""
    m = aot_ir(tmp_path, """
        def f(a: list[float], b: list[float], c: list[float], d: list[float]) -> None:
            a[0] = b[0] + c[0] + d[0]
        """)
    f = function(m, "f")
    assert not f.may_deopt and not f.resumes
    proved, diagnostics = verify.verify(m, aot=True)
    assert not diagnostics and [g.name for g in proved.functions] == ["f"]
    # the default verifier refuses the same IR: three lists mean element-by-element reads there, which may leave
    # for the interpreter (`verify/array-direct`), and AOT mode has no interpreter to leave for
    unproved, default_diagnostics = verify.verify(m)
    assert not unproved.functions and {d.rule for d in default_diagnostics} == {"verify/array-direct"}


def test_a_list_used_in_any_other_way_is_a_skip_naming_it(tmp_path):
    m = aot_ir(tmp_path, """
        def f(a: list[float]) -> None:
            print(a)

        def g(a: list[float], b: list[float]) -> bool:
            return a == b

        def h(a: list[float]) -> float:
            s = 0.0
            for v in a:
                s += v
            return s
        """)
    assert not m.functions
    assert "the list `a` is used other than by indexing" in m.skipped["f"]
    assert "the list `a` is used other than by indexing" in m.skipped["g"]
    assert "the list `a` is used other than by indexing" in m.skipped["h"]


def test_a_list_argument_must_be_a_name_of_the_parameters_type(tmp_path):
    m = aot_ir(tmp_path, """
        def g(a: list[float]) -> int:
            return 0

        def f() -> int:
            return g([1.0])

        def h(a: list[int]) -> int:
            return g(a)
        """)
    assert "must be the name of a list" in m.skipped["f"]
    assert "is list[int], but parameter `a` of `g` is list[float]" in m.skipped["h"]


# --- fixed tuples --------------------------------------------------------------------------------------

TUPLES = """
def pair(x: float, n: int) -> tuple[float, int]:
    return x * 2.0, n

def use(x: float) -> float:
    a, b = pair(x, 3)
    return a
"""


def test_a_tuple_is_returned_and_unpacked_into_names(tmp_path):
    m = aot_ir(tmp_path, TUPLES)
    pair, use = function(m, "pair"), function(m, "use")
    assert pair.returns is Type.OBJ                          # as in the default mode: a tuple is an object
    [r] = [n for n in walk(pair.body) if isinstance(n, ir.ReturnTuple)]
    assert [v.type for v in r.values] == [Type.F64, Type.I64] and ir.aot_tuple_kinds(pair) == (Type.F64, Type.I64)
    [u] = [n for n in walk(use.body) if isinstance(n, ir.Unpack)]
    assert u.targets == ("a", "b") and u.call.function == "pair" and u.call.type is Type.OBJ
    assert use.locals["a"] is Type.F64 and use.locals["b"] is Type.I64
    assert not [n for n in walk(use.body) if isinstance(n, (ir.Tuple, ir.Box))]
    proved, diagnostics = verify.verify(m, aot=True)
    assert not diagnostics and [f.name for f in proved.functions] == ["pair", "use"]


def test_a_tuple_is_unpacked_only(tmp_path):
    m = aot_ir(tmp_path, """
        def pair(x: float) -> tuple[float, float]:
            return x, x

        def whole(x: float) -> float:
            t = pair(x)
            return t[0]

        def few(x: float) -> float:
            a, b, c = pair(x)
            return a
        """)
    assert "returns a tuple, which an AOT program can only unpack" in m.skipped["whole"]
    assert "unpacks 3 names from the 2 values" in m.skipped["few"]


def test_a_value_unpacked_from_a_call_has_no_proved_range(tmp_path):
    """The targets of an unpacking are not locals with a recorded interval: `a + 1` stays unproved (a result
    the callee computed may be any int), in the front end and in the verifier alike."""
    path = write(tmp_path, """
        def g() -> tuple[int, int]:
            return 1, 2

        def f() -> int:
            a, b = g()
            return a + 1

        if __name__ == "__main__":
            print(f())
        """)
    with pytest.raises(aot.AotError) as raised:
        aot.generate(path, strict_int=True)
    assert [(f.rule, f.line) for f in raised.value.findings] == [("aot/int-unproven", 6)]
    aot.generate(path)                      # (accepted without strict_int: `a + 1` is a TAGGED operation, aot/rep.py)


# --- print arguments, f-strings ------------------------------------------------------------------------

def test_an_fstring_is_a_formatted_print_argument(tmp_path):
    m = aot_ir(tmp_path, """
        def f(x: float, n: int, t: bool) -> None:
            print(f"x={x:.2f}, n={n} t={t}!", 5)
        """)
    [p] = [n for n in walk(function(m, "f").body) if isinstance(n, ir.Print)]
    fs, five = p.args
    assert isinstance(fs, ir.FString) and fs.type is Type.OBJ and five == ir.Const(Type.I64, 5)
    assert [type(x).__name__ for x in fs.parts] == ["Const", "FormatField", "Const", "FormatField", "Const",
                                                    "FormatField", "Const"]
    fields = [x for x in fs.parts if isinstance(x, ir.FormatField)]
    assert [(x.value.type, x.precision) for x in fields] == [(Type.F64, 2), (Type.I64, None), (Type.BOOL, None)]
    assert [x.value for x in fs.parts if isinstance(x, ir.Const)] == ["x=", ", n=", " t=", "!"]
    proved, diagnostics = verify.verify(m, aot=True)
    assert not diagnostics and proved.functions


@pytest.mark.parametrize("fragment, text", [
    ("{x!r}", "conversion"),
    ("{x=}", "conversion"),
    ("{x:8.2f}", "format spec `8.2f`"),
    ("{x:e}", "format spec `e`"),
    ("{x:.21f}", "format spec `.21f`"),
    ("{x:.{3}f}", "nested field"),
    ("{n:.2f}", "only a float takes `.Nf`"),
])
def test_an_fstring_field_outside_the_two_forms_is_a_skip(tmp_path, fragment, text):
    m = aot_ir(tmp_path, f"def f(x: float, n: int) -> None:\n    print(f'{fragment}')\n")
    assert not m.functions and text in m.skipped["f"], m.skipped


def test_an_fstring_anywhere_but_print_is_a_skip(tmp_path):
    m = aot_ir(tmp_path, "def f(x: float) -> None:\n    s = f'{x}'\n")
    assert "only supported as an argument of print" in m.skipped["f"]


# --- the conditional expression, abs, min, max ---------------------------------------------------------

def test_conditional_expression_abs_min_and_max(tmp_path):
    m = aot_ir(tmp_path, """
        def f(x: float, y: float, n: int, p: bool) -> float:
            a = x if p else y
            b = abs(x)
            c = min(x, y)
            d = max(x, y)
            e = 1 if p else 2
            return a + b + c + d + float(e)
        """)
    f = function(m, "f")
    conds = {n.then.type for n in walk(f.body) if isinstance(n, ir.Cond)}
    assert conds == {Type.F64, Type.I64}
    assert [n for n in walk(f.body) if isinstance(n, ir.MathCall) and n.func is ir.MathFunc.FABS]
    # min(x, y) is `y if y < x else x` and max `y if y > x else x`: a tie and a NaN keep the first argument
    mn = assigned(f, "c")
    assert isinstance(mn, ir.Cond) and mn.cond == ir.Compare(
        Type.BOOL, ir.CompareKind.LT, ir.Local(Type.F64, "y"), ir.Local(Type.F64, "x"))
    assert mn.then == ir.Local(Type.F64, "y") and mn.orelse == ir.Local(Type.F64, "x")
    mx = assigned(f, "d")
    assert isinstance(mx, ir.Cond) and mx.cond.op is ir.CompareKind.GT
    proved, diagnostics = verify.verify(m, aot=True)
    assert not diagnostics and proved.functions


def test_a_conditional_expression_needs_two_values_of_one_type(tmp_path):
    m = aot_ir(tmp_path, """
        def f(p: bool) -> float:
            return 1 if p else 2.0

        def g(a: int, b: float) -> float:
            return min(a, b)
        """)
    assert "the branches of" in m.skipped["f"] and "two ints, two floats or two bools" in m.skipped["f"]
    assert "needs two ints or two floats" in m.skipped["g"]


def test_abs_of_an_int_follows_the_strict_rule(tmp_path):
    """`abs(v)` is `-v` where v is negative: proved when v's range excludes the most negative int, else an
    `aot/int-unproven` (the verifier re-proves the front end's flag under the guard `v < 0`)."""
    ok = aot.generate(write(tmp_path, """
        def f() -> int:
            v = 5
            w = -12
            return abs(v) + abs(w)

        if __name__ == "__main__":
            print(f())
        """, "ok.py"))
    assert "tpa_pneg_i64" in ok
    bad = write(tmp_path, """
        def f(a: int) -> int:
            return abs(a)

        if __name__ == "__main__":
            print(f(1))
        """, "bad.py")
    assert "tpa_int_neg(" in aot.generate(bad)          # accepted without strict_int: -(-2**63) is a TAGGED result
    with pytest.raises(aot.AotError) as raised:
        aot.generate(bad, strict_int=True)
    [finding] = raised.value.findings
    assert (finding.rule, finding.line) == ("aot/int-unproven", 2) and "`abs(a)`" in finding.message


def test_the_conditional_branch_runs_only_when_chosen(tmp_path):
    """The generated C evaluates a fallible branch inside the `if`, not before it."""
    c = aot.generate(write(tmp_path, """
        def f(a: float, b: float) -> float:
            return a / b if b != 0.0 else 0.0

        if __name__ == "__main__":
            print(f(1.0, 0.0))
        """))
    body = c[c.index("static int tpf_f("):]
    assert body.index("if (v_b != (0.0))") < body.index("tpa_truediv_f64(")


# --- sys.argv ------------------------------------------------------------------------------------------

def test_sys_argv_nodes(tmp_path):
    m = aot_ir(tmp_path, """
        import sys

        def f() -> int:
            n = int(sys.argv[1])
            return n + len(sys.argv) % 2
        """)
    f = function(m, "f")
    [a] = [n for n in walk(f.body) if isinstance(n, ir.ArgvInt)]
    assert a.type is Type.I64 and a.index == ir.Const(Type.I64, 1)
    assert [n for n in walk(f.body) if isinstance(n, ir.ArgvLen)]
    assert f.locals["n"] is Type.I64 and not [n for n in walk(f.body) if isinstance(n, (ir.Global, ir.GetAttr))]


@pytest.mark.parametrize("source, text", [
    ("import sys\ndef f() -> int:\n    return sys.maxsize\n", "only `int(sys.argv[i])` and `len(sys.argv)`"),
    ("def f() -> int:\n    return int(sys.argv[1])\n", "only supported as `int(sys.argv[i])`"),
])
def test_sys_is_used_only_as_argv_in_int_and_len(tmp_path, source, text):
    m = aot_ir(tmp_path, source)
    assert not m.functions and text in m.skipped["f"], m.skipped


# --- the flag off: nothing of this changes the default mode ---------------------------------------------

@pytest.mark.parametrize("source, text", [
    ("def f() -> list[float]:\n    return [1.0, 2.0]\n", "list display is not supported"),
    ("def g(x: float) -> tuple[float, float]:\n    return x, x\n\ndef f(x: float) -> float:\n    a, b = g(x)\n"
     "    return a\n", "tuple unpacking is not supported"),
    ("def f(x: float) -> float:\n    return 1.0 if x > 0.0 else 2.0\n", "conditional expression is not supported"),
    ("def g(n: int) -> list[float]:\n    return [0.0] * n\n\ndef f(n: int) -> float:\n    a = g(n)\n    return a[0]\n",
     "a list local is a native array only when created by"),
    ("def f(n: int) -> float:\n    a = [0.0] * n\n    b = a\n    return b[0]\n", "is used other than by indexing, len()"),
    ("def f(x: float) -> float:\n    return abs(x)\n", "`abs(x)` is object"),
    ("import sys\ndef f() -> int:\n    return int(sys.argv[1])\n", "`int(sys.argv[1])` is object"),
])
def test_the_default_mode_still_skips_these_as_before(tmp_path, source, text):
    m = frontend.lower(write(tmp_path, decorate_compiled(source)))
    assert any(text in why for why in m.skipped.values()), (m.skipped, [f.name for f in m.functions])


def test_the_default_verifier_does_not_define_the_aot_nodes(tmp_path):
    """The IR of an AOT program is refused by the default verifier: Print, ReturnTuple, Unpack, ListDisplay,
    Cond, ArgvInt and ArgvLen are nodes of the AOT mode only."""
    m = aot_ir(tmp_path, """
        import sys

        def pair(x: float) -> tuple[float, float]:
            return x, x

        def f(x: float) -> float:
            a, b = pair(x)
            c = [1.0, 2.0]
            d = a if x > 0.0 else b
            print(c[0])
            return d

        def g() -> int:
            return int(sys.argv[1]) + len(sys.argv) % 2
        """)
    assert {f.name for f in m.functions} == {"pair", "f", "g"}
    unproved, diagnostics = verify.verify(m)
    names = {d.message.split()[2] for d in diagnostics if d.rule == "verify/unknown-node"}
    assert {"ReturnTuple", "Unpack", "ListDisplay", "Cond", "ArgvInt", "Print"} <= names, names
    assert not unproved.functions


# --- the verifier refuses malformed AOT IR (each of these must fail, or the rule checks nothing) ----------

def hand(body, params=(), locals_=None, returns=Type.NONE, name="f"):
    f = ir.Function(name, tuple(params), returns, locals_ or {}, tuple(body), pure=True, may_deopt=False,
                    source_line=1)
    return ir.Module("m", (f,))


def rules_of(m):
    return {d.rule for d in verify.verify(m, aot=True)[1]}


A = ir.ArrayParam("a", Type.F64_ARRAY, False)
LA = ir.Local(Type.F64_ARRAY, "a")


def test_a_list_name_is_not_a_value_in_an_operand_or_a_print(tmp_path):
    assert "verify/array-use" in rules_of(hand([ir.Print((LA,))], [A]))
    assert "verify/array-use" in rules_of(hand(
        [ir.ExprStmt(ir.Compare(Type.BOOL, ir.CompareKind.EQ, LA, LA))], [A]))
    # (and it is fine where an assignment to a list local takes one)
    ok = hand([ir.Assign("b", LA)], [A], {"b": Type.F64_ARRAY})
    assert not rules_of(ok)


def test_a_list_is_assigned_only_to_a_list_local_of_its_type():
    assert "verify/type" in rules_of(hand([ir.Assign("x", LA)], [A], {"x": Type.F64}))
    assert "verify/type" in rules_of(hand([ir.Assign("b", LA)], [A], {"b": Type.I64_ARRAY}))
    assert "verify/array-use" in rules_of(hand([ir.Assign("a", ir.ListDisplay(Type.F64_ARRAY, (ir.Const(Type.F64, 1.0),)))], [A]))


def test_a_creation_stands_only_as_an_assigned_or_returned_value():
    disp = ir.ListDisplay(Type.F64_ARRAY, (ir.Const(Type.F64, 1.0), ir.Const(Type.F64, 2.0)))
    assert "verify/array-escape" in rules_of(hand([ir.ExprStmt(disp)]))
    assert not rules_of(hand([ir.Return(disp)], returns=Type.F64_ARRAY))
    assert "verify/type" in rules_of(hand([ir.Return(disp)], returns=Type.I64_ARRAY))
    assert "verify/type" in rules_of(hand([ir.Return(disp)], returns=Type.F64))
    mixed = ir.ListDisplay(Type.F64_ARRAY, (ir.Const(Type.F64, 1.0), ir.Const(Type.I64, 2)))
    assert "verify/type" in rules_of(hand([ir.Return(mixed)], returns=Type.F64_ARRAY))
    assert "verify/structure" in rules_of(hand([ir.Return(ir.ListDisplay(Type.F64_ARRAY, ()))], returns=Type.F64_ARRAY))


def test_a_list_argument_is_a_list_name_of_the_parameters_type():
    callee = ir.Function("g", (A,), Type.NONE, {}, (), pure=True, may_deopt=False, source_line=1)
    disp = ir.ListDisplay(Type.F64_ARRAY, (ir.Const(Type.F64, 1.0),))
    wrong = ir.Local(Type.I64_ARRAY, "b")
    def caller(arg, locals_):
        f = ir.Function("f", (), Type.NONE, locals_, (ir.Assign("a", disp), ir.ExprStmt(ir.Call(Type.NONE, "g", (arg,)))),
                        pure=True, may_deopt=False, source_line=2)
        return ir.Module("m", (callee, f))
    assert not rules_of(caller(ir.Local(Type.F64_ARRAY, "a"), {"a": Type.F64_ARRAY}))
    assert "verify/array-use" in rules_of(caller(disp, {"a": Type.F64_ARRAY}))
    assert "verify/array-use" in rules_of(caller(wrong, {"a": Type.F64_ARRAY, "b": Type.I64_ARRAY}))


def test_a_function_returns_a_list_only_where_it_is_bound_or_returned():
    maker = ir.Function("make", (), Type.F64_ARRAY, {}, (ir.Return(ir.ListDisplay(
        Type.F64_ARRAY, (ir.Const(Type.F64, 1.0),))),), pure=True, may_deopt=False, source_line=1)
    call = ir.Call(Type.F64_ARRAY, "make", ())

    def user(stmt, locals_):
        f = ir.Function("f", (), Type.NONE, locals_, (stmt,), pure=True, may_deopt=False, source_line=2)
        return ir.Module("m", (maker, f))
    assert not rules_of(user(ir.Assign("a", call), {"a": Type.F64_ARRAY}))
    assert not rules_of(user(ir.ExprStmt(call), {}))                    # the result is released
    assert "verify/array-escape" in rules_of(user(ir.Print((call,)), {}))
    assert "verify/type" in rules_of(user(ir.Assign("x", call), {"x": Type.F64}))


def test_unpack_and_return_tuple_are_checked_against_each_other():
    pair = ir.Function("pair", (), Type.OBJ, {}, (ir.ReturnTuple((ir.Const(Type.F64, 1.0), ir.Const(Type.I64, 2))),),
                       pure=True, may_deopt=False, source_line=1)
    call = ir.Call(Type.OBJ, "pair", ())

    def user(stmt, locals_):
        f = ir.Function("f", (), Type.NONE, locals_, (stmt,), pure=True, may_deopt=False, source_line=2)
        return ir.Module("m", (pair, f))
    assert not rules_of(user(ir.Unpack(("x", "n"), call), {"x": Type.F64, "n": Type.I64}))
    assert "verify/type" in rules_of(user(ir.Unpack(("x", "n"), call), {"x": Type.I64, "n": Type.I64}))      # wrong type
    assert "verify/type" in rules_of(user(ir.Unpack(("x",), call), {"x": Type.F64}))                           # wrong count
    assert "verify/type" in rules_of(user(ir.ExprStmt(call), {}))        # a tuple is only unpacked
    two = ir.Function("two", (), Type.OBJ, {}, (
        ir.If(ir.Const(Type.BOOL, True), (ir.ReturnTuple((ir.Const(Type.F64, 1.0),)),),
              (ir.ReturnTuple((ir.Const(Type.I64, 1),)),)),), pure=True, may_deopt=False, source_line=1)
    assert "verify/type" in rules_of(ir.Module("m", (two,)))              # two shapes in one function


def test_an_fstring_field_is_checked():
    def printing(field):
        return hand([ir.Print((ir.FString(Type.OBJ, (field,)),))], [ir.Param("x", Type.F64), ir.Param("n", Type.I64)])
    x, n = ir.Local(Type.F64, "x"), ir.Local(Type.I64, "n")
    assert not rules_of(printing(ir.FormatField(Type.OBJ, x, 3)))
    assert not rules_of(printing(ir.FormatField(Type.OBJ, n, None)))
    assert "verify/type" in rules_of(printing(ir.FormatField(Type.OBJ, n, 3)))         # `.3f` of an int
    assert "verify/type" in rules_of(printing(ir.FormatField(Type.OBJ, x, 21)))        # precision too large
    assert "verify/type" in rules_of(printing(ir.Const(Type.F64, 1.0)))                # not a part
    # an FString is a Print argument only
    assert "verify/unknown-node" in rules_of(hand([ir.ExprStmt(ir.FString(Type.OBJ, ()))]))


# --- which ints are RAW and which TAGGED (aot/rep.py, #462) ------------------------------------------------------
#
# The inference at the level of the IR: a name, a parameter, a return and a tuple element is RAW (int64) or TAGGED (a
# value that may be any integer). The rule is in the module docstring of aot/rep.py. Design decision D1 (a) and the
# representation: leader, 2026-11-16, not owner-confirmed; the rule itself is an agent proposal.

def reps_of(tmp_path, source, strict=False):
    m = aot_ir(tmp_path, source)
    proved, diagnostics = verify.verify(m, aot=True)
    assert [f.name for f in proved.functions] == [f.name for f in m.functions], (diagnostics, m.skipped)
    return proved, rep.infer(proved, strict=strict)


def ops(proved, name, kind):
    return [n for n in walk(function(proved, name).body) if isinstance(n, ir.BinOp) and n.op is kind]


def test_loop_counters_and_proved_results_are_raw(tmp_path):
    proved, r = reps_of(tmp_path, """
        def f() -> int:
            t = 0
            for i in range(10):
                t = i * 2 + 1
            return t
        """)
    assert [r.name("f", n) for n in ("i", "t")] == [rep.RAW, rep.RAW] and r.returns("f") == rep.RAW
    assert all(o.proven for o in ops(proved, "f", ir.BinOpKind.MUL) + ops(proved, "f", ir.BinOpKind.ADD))


def test_an_element_read_and_a_length_are_raw_and_so_is_what_is_proved_from_them(tmp_path):
    _, r = reps_of(tmp_path, """
        def f(a: list[int]) -> int:
            t = 0
            for i in range(len(a)):
                t = a[i] // 2 + i
            return t
        """)
    assert [r.name("f", n) for n in ("i", "t")] == [rep.RAW, rep.RAW] and r.returns("f") == rep.RAW


def test_an_accumulator_that_is_not_proved_is_tagged_and_so_is_its_return(tmp_path):
    _, r = reps_of(tmp_path, """
        def f(n: int) -> int:
            s = 0
            for i in range(n):
                s += i
            return s
        """)
    assert r.name("f", "n") == rep.RAW and r.name("f", "i") == rep.RAW          # nobody passes a TAGGED n
    assert r.name("f", "s") == rep.TAGGED and r.returns("f") == rep.TAGGED


def test_an_int_read_from_the_arguments_is_tagged_and_so_is_a_loop_over_it(tmp_path):
    _, r = reps_of(tmp_path, """
        import sys

        def f() -> int:
            n = int(sys.argv[1])
            t = 0
            for i in range(n):
                t = i
            return t
        """)
    assert [r.name("f", x) for x in ("n", "i", "t")] == [rep.TAGGED] * 3 and r.returns("f") == rep.TAGGED


def test_a_proved_operation_with_a_tagged_operand_is_tagged(tmp_path):
    """The verifier proves `x + 1` (x is narrowed to 0..99 by the guard), but x is a big-integer-capable value: a
    proved flag assumes its operands fit 64 bits, so the result is TAGGED."""
    proved, r = reps_of(tmp_path, """
        def f(n: int) -> int:
            x = n * n
            y = 0
            if 0 <= x < 100:
                y = x + 1
            return y
        """)
    [add] = ops(proved, "f", ir.BinOpKind.ADD)
    assert add.proven, "the premise: the flag is set"
    assert r.name("f", "x") == rep.TAGGED and r.name("f", "y") == rep.TAGGED and r.returns("f") == rep.TAGGED


def test_a_proof_read_from_a_guard_on_a_tagged_value_is_not_trusted(tmp_path):
    """`lo + 1` is proved from `lo < hi` and `hi <= INT64_MAX`; hi = lo * lo is TAGGED, so that is not true of its
    value: the sound re-proof (aot/rep.py, verify.sound_proofs) does not prove `lo + 1`, so it is not RAW."""
    proved, r = reps_of(tmp_path, """
        def step(w: list[int]) -> int:
            lo = w[0]
            hi = lo * lo
            if lo < hi:
                lo = lo + 1
            return lo
        """)
    [add] = ops(proved, "step", ir.BinOpKind.ADD)
    assert add.proven, "the premise: the verifier proves it"
    assert not r.proved(add) and r.of("step", add) == rep.TAGGED
    assert r.name("step", "lo") == rep.TAGGED and r.returns("step") == rep.TAGGED


def test_a_guard_on_a_raw_value_taints_nothing(tmp_path):
    proved, r = reps_of(tmp_path, """
        def f(w: list[int]) -> int:
            lo = w[0]
            hi = w[1]
            if lo < hi and lo > 0:
                lo = lo - 1
            return lo
        """)
    [sub] = ops(proved, "f", ir.BinOpKind.SUB)
    assert sub.proven and r.proved(sub) and r.of("f", sub) == rep.RAW
    assert r.name("f", "lo") == rep.RAW


# Every way a fact about one value enters a proof about another (the front end's interval analysis and the verifier's
# re-proof), each with a TAGGED value whose int64-only interval would make the proof false: guards of each comparison
# kind and operand order, `not`, `and`, `or`, `while`, a returning guard, a conditional expression, a copy and a second
# guard after the first, a join, a TAGGED parameter, call result and unpacked element, `int(sys.argv[i])`, a loop
# variable, a TAGGED and a guarded loop bound, a conditional expression and `min` as the other side, `abs`, a `%` by a
# TAGGED divisor. In each, the operation is marked proved (the premise: the default-mode rules prove it) and is not RAW.
# `t = w[1] * w[2]` is TAGGED (any int); `w[0]` is any int64. The differential cases with values that make each one wrap
# are `guards_on_big_values` in test_aot_build.py. (Agent proposal, not owner-confirmed.)

_HEAD = """
def f(w: list[int]) -> int:
    a = w[0]
    t = w[1] * w[2]
"""

GUARD_PATHS = {
    "lt": (_HEAD + "    if a < t:\n        a = a + 1\n    return a\n", "f", "a + 1"),
    "gt_swapped": (_HEAD + "    if t > a:\n        a = a + 1\n    return a\n", "f", "a + 1"),
    "le": (_HEAD + "    u = t - 1\n    if a <= u:\n        a = a + 1\n    return a\n", "f", "a + 1"),
    "gt": (_HEAD + "    if a > t:\n        a = a - 1\n    return a\n", "f", "a - 1"),
    "ge": (_HEAD + "    u = t + 1\n    if a >= u:\n        a = a - 1\n    return a\n", "f", "a - 1"),
    "eq": (_HEAD + "    if t > 0:\n        s = 9223372036854775807 - t\n        if a == s:\n            a = a - 1\n"
                   "    return a\n", "f", "a - 1"),
    "returning_guard": (_HEAD + "    if a >= t:\n        return 0\n    return a + 1\n", "f", "a + 1"),
    "not": (_HEAD + "    if not (a >= t):\n        a = a + 1\n    return a\n", "f", "a + 1"),
    "and": (_HEAD + "    if a < t and a > -5:\n        a = a + 1\n    return a\n", "f", "a + 1"),
    "or_false": (_HEAD + "    if a >= t or a < -5:\n        return 0\n    return a + 1\n", "f", "a + 1"),
    "while": (_HEAD + "    n = 0\n    while a < t and n < 1:\n        a = a + 1\n        n = n + 1\n    return a\n",
              "f", "a + 1"),
    "conditional_expression": (_HEAD + "    return a + 1 if a < t else 0\n", "f", "a + 1"),
    "copy_after_the_guard": (_HEAD + "    if a < t:\n        c = a\n        return c + 1\n    return 0\n", "f", "c + 1"),
    "second_guard": (_HEAD + "    c = w[3]\n    if a < t:\n        if c <= a:\n            return c + 1\n    return 0\n",
                     "f", "c + 1"),
    "join": (_HEAD + "    if a < t:\n        b = a\n    else:\n        b = 0\n    return b + 1\n", "f", "b + 1"),
    "tagged_parameter": ("""
def g(a: int, t: int) -> int:
    if a < t:
        return a + 1
    return 0

def f(w: list[int]) -> int:
    return g(w[0], w[1] * w[2])
""", "g", "a + 1"),
    "tagged_call_result": ("""
def big(w: list[int]) -> int:
    return w[1] * w[2]

def f(w: list[int]) -> int:
    a = w[0]
    t = big(w)
    if a < t:
        return a + 1
    return 0
""", "f", "a + 1"),
    "tagged_unpacked_element": ("""
def pair(w: list[int]) -> tuple[int, int]:
    return w[1] * w[2], 0

def f(w: list[int]) -> int:
    a = w[0]
    t, z = pair(w)
    if a < t:
        return a + 1
    return z
""", "f", "a + 1"),
    "argv": ("""
import sys

def f(w: list[int]) -> int:
    a = w[0]
    t = int(sys.argv[1])
    if a < t:
        return a + 1
    return 0
""", "f", "a + 1"),
    "loop_variable": ("""
def f(w: list[int]) -> int:
    t = w[2] * w[3]
    r = 0
    for i in range(w[0], w[1], -1):
        if i < t:
            r = i + 1
            break
    return r
""", "f", "i + 1"),
    "tagged_loop_bound": ("""
def f(w: list[int]) -> int:
    t = w[1] * w[2]
    r = 0
    for i in range(t):
        r = i + 1
        break
    return r
""", "f", "i + 1"),
    "guarded_loop_bound": (_HEAD + "    r = 0\n    if a < t:\n        for i in range(a, 0, -1):\n            r = i + 1\n"
                                   "            break\n    return r\n", "f", "i + 1"),
    "conditional_expression_as_the_other_side": (_HEAD + "    if a < (t if a > 0 else 5):\n        return a + 1\n"
                                                         "    return 0\n", "f", "a + 1"),
    "min_as_the_other_side": (_HEAD + "    m = min(t, w[3])\n    if a < m:\n        return a + 1\n    return 0\n",
                              "f", "a + 1"),
    "abs": (_HEAD + "    if a > t:\n        return abs(a)\n    return 0\n", "f", "-a"),
    "remainder_by_a_tagged_divisor": (_HEAD + "    m = a % t\n    if a < m:\n        return a + 1\n    return 0\n",
                                      "f", "a + 1"),
}


def ir_text(e) -> str:
    """A small printer of int expressions, to name an operation in the tables here."""
    if isinstance(e, ir.Const):
        return repr(e.value)
    if isinstance(e, ir.Local):
        return e.name
    if isinstance(e, ir.BinOp):
        return f"{ir_text(e.left)} {e.op.value} {ir_text(e.right)}"
    if isinstance(e, ir.UnaryOp):
        return f"{e.op.value}{ir_text(e.operand)}"
    return type(e).__name__


@pytest.mark.parametrize("name", list(GUARD_PATHS))
def test_a_proof_that_reads_a_tagged_value_does_not_make_an_operation_raw(tmp_path, name):
    source, fn, text = GUARD_PATHS[name]
    proved, r = reps_of(tmp_path, source)
    found = [n for n in walk(function(proved, fn).body)
             if isinstance(n, (ir.BinOp, ir.UnaryOp)) and n.type is Type.I64 and ir_text(n) == text]
    assert found and all(n.proven for n in found), "the premise: the default-mode rules prove the operation"
    assert all(not r.proved(n) and r.of(fn, n) == rep.TAGGED for n in found)


# A proved index skips its bounds check only if the sound re-proof proves it too. The verifier gives a list created
# with a length the length's interval; with a TAGGED length (or a RAW one narrowed by a TAGGED value, or the length of
# such a list) that interval reads the TAGGED value as int64, and a constant index looked in bounds on a list that is
# shorter (here: empty, since `9223372036854775807 - t` is negative for a big t). Before the sound re-proof every one of
# these skipped its check. The differential cases are `list_shorter_than_its_proof*` in test_aot_build.py.

_SHORT = _HEAD.replace("    a = w[0]\n", "") + "    n = 9223372036854775807 - t\n    arr = [0] * (n + 5)\n"

INDEX_PATHS = {
    "tagged_length": (_SHORT + "    return arr[4]\n", "arr"),
    "store_with_a_tagged_length": (_SHORT + "    arr[4] = 7\n    return len(arr)\n", "arr"),
    "copy_of_such_a_list": (_SHORT + "    b = arr[:]\n    return b[4]\n", "b"),
    "length_of_such_a_list": (_SHORT + "    arr2 = [0] * len(arr)\n    return arr2[4]\n", "arr2"),
    "raw_length_narrowed_by_a_tagged_value": (_HEAD.replace("    a = w[0]\n", "") + """\
    s = 9223372036854775807 - t
    n = w[0]
    if n > s:
        arr = [0] * n
        return arr[0]
    return 0
""", "arr"),
}


@pytest.mark.parametrize("name", list(INDEX_PATHS))
def test_a_bound_proof_that_reads_a_tagged_value_keeps_the_check(tmp_path, name):
    source, array = INDEX_PATHS[name]
    proved, r = reps_of(tmp_path, source)
    found = [n for n in walk(function(proved, "f").body)
             if isinstance(n, (ir.Index, ir.StoreIndex)) and n.array == array]
    assert found and all(n.proven for n in found), "the premise: the verifier proves the index in bounds"
    assert not any(r.trusted("f", n) for n in found)


def test_what_holds_of_any_int_is_still_raw_and_trusted(tmp_path):
    """The other side: a guard against a length, an argument every caller passes RAW, an unpacked RAW element, a list of
    constant length and a list whose TAGGED length is guarded from below keep their proofs (the re-proof is not a
    blanket TAGGED)."""
    proved, r = reps_of(tmp_path, """
        def g(a: int, t: int) -> int:
            if a < t:
                return a + 1
            return 0

        def pair(w: list[int]) -> tuple[int, int]:
            return w[1], 0

        def f(w: list[int]) -> int:
            a = w[0]
            if a < len(w):
                a = a + 1
            t, z = pair(w)
            if a < t:
                a = a + 1
            arr = [0] * 5
            s = 0
            for i in range(len(arr)):
                s = s + arr[i]
            big = w[1] * w[2]
            if big > 3:
                more = [0] * big
                s = s + more[3]
            return g(a, w[1]) + arr[4] + z
        """)
    adds = [n for name in ("f", "g") for n in walk(function(proved, name).body)
            if isinstance(n, ir.BinOp) and n.op is ir.BinOpKind.ADD and ir_text(n) == "a + 1"]
    assert len(adds) == 3 and all(n.proven and r.proved(n) for n in adds)
    assert r.name("f", "a") == rep.RAW and r.returns("g") == rep.RAW
    loads = [n for n in walk(function(proved, "f").body) if isinstance(n, ir.Index) and n.array in ("arr", "more")]
    assert len(loads) == 3 and all(n.proven and r.trusted("f", n) for n in loads)


def test_a_parameter_is_tagged_when_one_call_site_passes_a_tagged_value(tmp_path):
    _, r = reps_of(tmp_path, """
        def g(a: int) -> int:
            return a

        def h(b: int) -> int:
            return b

        def f(n: int) -> int:
            x = g(1)
            y = g(n * n)
            z = h(2)
            return z
        """)
    assert r.name("g", "a") == rep.TAGGED and r.returns("g") == rep.TAGGED
    assert r.name("h", "b") == rep.RAW and r.returns("h") == rep.RAW
    assert r.name("f", "x") == rep.TAGGED and r.name("f", "y") == rep.TAGGED and r.name("f", "z") == rep.RAW
    assert r.returns("f") == rep.RAW


def test_a_tuple_has_a_representation_per_element(tmp_path):
    _, r = reps_of(tmp_path, """
        def pair(a: int) -> tuple[int, int]:
            return a * a, 3

        def user() -> int:
            x, y = pair(2)
            return y
        """)
    assert r.reps["pair"].tuple_elements == [rep.TAGGED, rep.RAW]
    assert r.name("user", "x") == rep.TAGGED and r.name("user", "y") == rep.RAW and r.returns("user") == rep.RAW


def test_abs_of_an_int_is_tagged_where_the_negation_is_not_proved(tmp_path):
    _, r = reps_of(tmp_path, """
        def f(a: int) -> int:
            return abs(a)

        def g() -> int:
            v = 5
            return abs(v)
        """)
    assert r.returns("f") == rep.TAGGED and r.returns("g") == rep.RAW


def test_a_remainder_and_a_division_by_a_constant_of_raw_values_stay_raw(tmp_path):
    _, r = reps_of(tmp_path, """
        def f(a: int, b: int) -> int:
            return a % b

        def g(a: int) -> int:
            return a // 3

        def h(a: int, b: int) -> int:
            return a // b
        """)
    assert r.returns("f") == rep.RAW and r.returns("g") == rep.RAW and r.returns("h") == rep.TAGGED


def test_strict_mode_has_nothing_tagged(tmp_path):
    _, r = reps_of(tmp_path, """
        def f(n: int) -> int:
            s = 0
            for i in range(n):
                s += i
            return s
        """, strict=True)
    assert r.name("f", "s") == rep.RAW and r.returns("f") == rep.RAW


def test_the_text_of_a_program_names_the_big_integer_runtime_only_where_a_value_is_tagged(tmp_path):
    proved = write(tmp_path, """
        def f(a: list[float], n: int) -> float:
            s = 0.0
            for i in range(n):
                s += a[i]
            return s

        if __name__ == "__main__":
            v = [1.0, 2.0]
            print(f(v, 2))
        """, "proved.py")
    assert "tp_aot_int" not in aot.generate(proved)
    tagged = write(tmp_path, """
        def f(n: int) -> int:
            s = 0
            for i in range(n):
                s += i
            return s

        if __name__ == "__main__":
            print(f(5))
        """, "tagged.py")
    c = aot.generate(tagged)
    assert '#include "tp_aot_int.h"' in c and "tpa_int_add(" in c and "tpa_int_release(v_s)" in c
