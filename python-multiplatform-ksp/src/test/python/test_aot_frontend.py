"""AOT mode of the front end and the verifier (SPEC N-17, #451; agent proposal, not owner-confirmed).

`frontend.lower(path, aot=True)` and `verify.verify(module, aot=True)` read an AOT program as it is
written: `print` is a statement of its own, module constants are typed values, the `__main__` block is
the entry function, and an int local is `I64` (there is no `INT`, nothing deopts). With the flag off
nothing changes: the same text lowered by the default mode gives the IR it always gave. No C compiler is
needed here (the front end needs Pyrefly, like test_frontend.py).
"""
import dataclasses
import io
import textwrap
import tokenize
import types
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
    ("{x!a}", "conversion `!a`"),
    ("{x!r:.2f}", "with no conversion"),
    ("{x:8.2f}", "format spec `8.2f`"),
    ("{x:e}", "format spec `e`"),
    ("{x:.21f}", "format spec `.21f`"),
    ("{x:.{3}f}", "nested field"),
    ("{n:.2f}", "only a float takes `.Nf`"),
])
def test_an_fstring_field_outside_the_two_forms_is_a_skip(tmp_path, fragment, text):
    m = aot_ir(tmp_path, f"def f(x: float, n: int) -> None:\n    print(f'{fragment}')\n")
    assert not m.functions and text in m.skipped["f"], m.skipped


def test_an_fstring_outside_print_is_a_str_value(tmp_path):
    """Tier S1 (PR-S1a): an f-string anywhere but a print argument is a STR `FString`; `{x!r}` and `{x=}` (text, then
    the repr) are fields with `repr`. Red before S1a: the function was skipped ("only supported as an argument of
    print")."""
    m = aot_ir(tmp_path, "def f(x: float, s: str) -> str:\n    return f'{x=} {s!r}'\n")
    assert not m.skipped, m.skipped
    [r] = [n for n in walk(function(m, "f").body) if isinstance(n, ir.Return)]
    fs = r.value
    assert isinstance(fs, ir.FString) and fs.type is Type.STR
    fields = [x for x in fs.parts if isinstance(x, ir.FormatField)]
    assert [(x.value.type, x.repr) for x in fields] == [(Type.F64, True), (Type.STR, True)]
    proved, diagnostics = verify.verify(m, aot=True)
    assert not diagnostics and proved.functions


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
    ("import sys\ndef f() -> int:\n    return sys.version\n", "only `sys.argv`, `sys.maxsize` and `sys.exit`"),
    ("def f() -> int:\n    return int(sys.argv[1])\n", "int() of a object is not supported in an AOT program"),
])
def test_sys_is_used_only_as_argv_in_int_and_len(tmp_path, source, text):
    m = aot_ir(tmp_path, source)
    assert not m.functions and text in m.skipped["f"], m.skipped


# --- the flag off: nothing of this changes the default mode ---------------------------------------------

DEFAULT_MODE_ROWS = [
    ("def f() -> list[float]:\n    return [1.0, 2.0]\n", "list display is not supported"),
    ("def g(x: float) -> tuple[float, float]:\n    return x, x\n\ndef f(x: float) -> float:\n    a, b = g(x)\n"
     "    return a\n", "tuple unpacking is not supported"),
    ("def f(x: float) -> float:\n    return 1.0 if x > 0.0 else 2.0\n", "conditional expression is not supported"),
    ("def g(n: int) -> list[float]:\n    return [0.0] * n\n\ndef f(n: int) -> float:\n    a = g(n)\n    return a[0]\n",
     "a list local is a native array only when created by"),
    ("def f(n: int) -> float:\n    a = [0.0] * n\n    b = a\n    return b[0]\n", "is used other than by indexing, len()"),
    ("def f(x: float) -> float:\n    return abs(x)\n", "`abs(x)` is object"),
    ("import sys\ndef f() -> int:\n    return int(sys.argv[1])\n", "`int(sys.argv[1])` is object"),
]


@pytest.fixture(scope="module")
def default_mode_modules(tmp_path_factory):
    """The rows below, lowered together by the default mode (one front-end run; `lowered_together`)."""
    return lowered_together(tmp_path_factory, {text: source for source, text in DEFAULT_MODE_ROWS}, aot_mode=False)


@pytest.mark.parametrize("source, text", DEFAULT_MODE_ROWS)
def test_the_default_mode_still_skips_these_as_before(default_mode_modules, source, text):
    m = default_mode_modules[text]
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


def suffixed(source: str, suffix: str) -> str:
    """`source` with every function it defines renamed `name` -> `name + suffix`: NAME tokens only, so a string or a
    comment is not touched, and an attribute (`x.f`) is left alone."""
    source = textwrap.dedent(source).lstrip("\n")
    toks = list(tokenize.generate_tokens(io.StringIO(source).readline))
    defined = {toks[i + 1].string for i, t in enumerate(toks[:-1]) if t.type == tokenize.NAME and t.string == "def"}
    starts, pos = [], 0
    for line in source.splitlines(keepends=True):
        starts.append(pos)
        pos += len(line)
    out = source
    for i in range(len(toks) - 1, -1, -1):
        t = toks[i]
        if t.type == tokenize.NAME and t.string in defined and not (i and toks[i - 1].string == "."):
            a = starts[t.start[0] - 1] + t.start[1]
            out = out[:a] + t.string + suffix + out[a + len(t.string):]
    return out


def merged(cases: dict) -> tuple[str, dict]:
    """One module from independent cases (name -> source): each case's functions get a suffix of their own, so no call
    and no name is shared between cases (the representation inference is a whole-program fixpoint, and a call between
    two cases would change a parameter's or a return's representation). The only thing shared is `import sys`.
    Returns the source and `{case: suffix}`."""
    parts, suffixes = [], {}
    for k, (name, source) in enumerate(cases.items()):
        suffixes[name] = f"_c{k}"
        body = [ln for ln in suffixed(source, suffixes[name]).splitlines() if ln != "import sys"]
        parts.append("\n".join(body).strip("\n") + "\n")
    head = "import sys\n\n" if any("import sys" in textwrap.dedent(s).splitlines() for s in cases.values()) else ""
    return head + "\n\n".join(parts), suffixes


def ops(proved, name, kind):
    return [n for n in walk(function(proved, name).body) if isinstance(n, ir.BinOp) and n.op is kind]


def case_view(module, sfx):
    """The part of the merged `module` that belongs to the case with suffix `sfx`, under the names its source uses."""
    cut = len(sfx)
    own = [dataclasses.replace(f, name=f.name[:-cut]) for f in module.functions if f.name.endswith(sfx)]
    skipped = {k[:-cut]: v for k, v in module.skipped.items() if k.endswith(sfx)}
    return types.SimpleNamespace(functions=own, skipped=skipped)


def lowered_together(tmp_path_factory, cases, *, aot_mode=True):
    """One front-end run for several independent sources (`merged`): `{case: its view of the lowered module}`. With
    `aot_mode=False` the default mode lowers it, `@compiled` above every function as `decorate_compiled` puts it."""
    source, suffixes = merged(cases)
    path = write(tmp_path_factory.mktemp("together"), source if aot_mode else decorate_compiled(source))
    module = frontend.lower(path, aot=True) if aot_mode else frontend.lower(path)
    return {k: case_view(module, sfx) for k, sfx in suffixes.items()}


# The cases below are independent small programs. Lowering one costs a front-end run (the type inferencer is a
# subprocess, seconds on a slow runner), so they are lowered together, once per test module, as ONE module in which every
# case's functions carry a suffix of their own (`merged`): no call and no name is shared between cases, so the
# whole-program fixpoint of `rep.infer` gives each case the facts it has alone. `shared_reps` returns a case's view of
# the result under the names the case's source uses. (Checked case by case, alone against merged, when this was
# written; agent proposal, not owner-confirmed.)

REP_CASES = {
    "loop_counters": """
        def f() -> int:
            t = 0
            for i in range(10):
                t = i * 2 + 1
            return t
        """,
    "element_read": """
        def f(a: list[int]) -> int:
            t = 0
            for i in range(len(a)):
                t = a[i] // 2 + i
            return t
        """,
    "accumulator": """
        def f(n: int) -> int:
            s = 0
            for i in range(n):
                s += i
            return s
        """,
    "int_from_argv": """
        import sys

        def f() -> int:
            n = int(sys.argv[1])
            t = 0
            for i in range(n):
                t = i
            return t
        """,
    "proved_with_tagged_operand": """
        def f(n: int) -> int:
            x = n * n
            y = 0
            if 0 <= x < 100:
                y = x + 1
            return y
        """,
    "guard_on_tagged": """
        def step(w: list[int]) -> int:
            lo = w[0]
            hi = lo * lo
            if lo < hi:
                lo = lo + 1
            return lo
        """,
    "guard_on_raw": """
        def f(w: list[int]) -> int:
            lo = w[0]
            hi = w[1]
            if lo < hi and lo > 0:
                lo = lo - 1
            return lo
        """,
    "what_holds_of_any_int": """
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
        """,
    "tagged_call_site": """
        def g(a: int) -> int:
            return a

        def h(b: int) -> int:
            return b

        def f(n: int) -> int:
            x = g(1)
            y = g(n * n)
            z = h(2)
            return z
        """,
    "tuple_elements": """
        def pair(a: int) -> tuple[int, int]:
            return a * a, 3

        def user() -> int:
            x, y = pair(2)
            return y
        """,
    "abs": """
        def f(a: int) -> int:
            return abs(a)

        def g() -> int:
            v = 5
            return abs(v)
        """,
    "remainder_and_division": """
        def f(a: int, b: int) -> int:
            return a % b

        def g(a: int) -> int:
            return a // 3

        def h(a: int, b: int) -> int:
            return a // b
        """,
}


# --- which lists are big-capable (aot/rep.py "Lists of ints", #465) -----------------------------------------------
#
# The classification of the `list[int]` names at the level of the IR: one group per set of names that may be one list
# (assignment, argument and parameter, returned list and call result, a name assigned more than once), a copy as a
# one-way constraint, and the causes that make a group big-capable (a store of a TAGGED value, `[v] * n` or a display
# with a TAGGED element, a copy of a big-capable list). They are lowered together with the cases above (one module, one
# front-end run), under suffixes of their own. `n * n` is TAGGED here: the parameter is any int64 (nobody passes more),
# and the product is not proved. Leader decision 2026-11-16, not owner-confirmed; the rules are agent proposals.

LIST_CASES = {
    "assign_alias": """
        def f(n: int) -> int:
            a = [0] * 3
            b = a
            b[0] = n * n
            return a[0]
        """,
    "argument_and_parameter": """
        def fill(p: list[int], v: int) -> None:
            p[0] = v * v

        def f(n: int) -> int:
            a = [0] * 2
            c = [0] * 2
            fill(a, n)
            fill(c, n)
            b = [0] * 2
            return a[0] + c[0] + b[0]
        """,
    "returned_list_and_call_result": """
        def make(n: int) -> list[int]:
            r = [0] * 2
            r[0] = n * n
            return r

        def f(n: int) -> int:
            a = make(n)
            return a[0]
        """,
    "returned_parameter": """
        def same(p: list[int]) -> list[int]:
            return p

        def f(n: int) -> int:
            a = [0] * 2
            b = same(a)
            b[1] = n * n
            return a[1]
        """,
    "two_names_and_a_branch": """
        def f(n: int, flag: bool) -> int:
            a = [1, 2]
            b = [3, 4]
            c = a
            if flag:
                c = b
            c[0] = n * n
            d = [5, 6]
            return a[0] + b[0] + d[0]
        """,
    "a_name_assigned_twice": """
        def f(n: int) -> int:
            a = [0] * 2
            a = [n * n, 1]
            return a[0]
        """,
    "copies": """
        def f(n: int) -> int:
            a = [0] * 2
            b = a[:]
            c = [0] * 2
            d = c[:]
            d[0] = n * n
            a[0] = n * n
            return a[0] + b[0] + c[0] + d[0]
        """,
    "two_functions_calling_each_other": """
        def ping(p: list[int], k: int) -> int:
            if k <= 0:
                return p[0]
            return pong(p, k - 1)

        def pong(p: list[int], k: int) -> int:
            p[0] = p[0] * 3
            return ping(p, k - 1)

        def f(k: int) -> int:
            a = [1, 2]
            return ping(a, k)
        """,
    "creation_with_a_tagged_value": """
        import sys

        def f() -> int:
            n = int(sys.argv[1])
            a = [n] * 2
            b = [n, 1]
            c = [1, 2]
            d = list(range(n))
            return a[0] + b[0] + c[0] + d[0]
        """,
    "raw_lists": """
        def put(p: list[int], i: int) -> None:
            p[i] = i % 7

        def f() -> int:
            a = [0] * 8
            for i in range(8):
                put(a, i)
            b = [0] * 4
            for i in range(4):
                b[i] = i * 3 + 1
            s = 0
            for i in range(4):
                s += b[i]
            return a[3] + s
        """,
    "element_reads": """
        def f(n: int) -> int:
            a = [0] * 2
            a[0] = n * n
            v = a[1]
            w = 0
            if v < 100:
                w = v + 1
            b = [0] * 2
            u = b[1]
            z = 0
            if u < 100:
                z = u + 1
            return w + z
        """,
}


class _Reps:
    """`rep.Representation` of the merged module, seen through one case's suffix."""

    def __init__(self, r, sfx):
        self._r, self._sfx = r, sfx

    def name(self, fn, name):
        return self._r.name(fn + self._sfx, name)

    def returns(self, fn):
        return self._r.returns(fn + self._sfx)

    def element(self, fn, i):
        return self._r.element(fn + self._sfx, i)

    def of(self, fn, e):
        return self._r.of(fn + self._sfx, e)

    def trusted(self, fn, node):
        return self._r.trusted(fn + self._sfx, node)

    def proved(self, e):
        return self._r.proved(e)

    def list_class(self, fn, name):
        return self._r.list_class(fn + self._sfx, name)

    def return_class(self, fn):
        return rep.BIG if self._r.return_big(fn + self._sfx) else rep.RAW

    def groups(self):
        """`[(class, {(function, name)})]` of this case's groups (the returned list of a function is `(function,
        rep.RETURN)`), in the order of their first member."""
        cut = len(self._sfx)
        out = []
        for g in self._r.list_groups():
            if g.members[0][0].endswith(self._sfx):
                out.append((g.cls, {(fn[:-cut], name) for fn, name in g.members}))
        return out

    def group_of(self, fn, name):
        """The set of names in the group of `(fn, name)`, and its class."""
        for cls, members in self.groups():
            if (fn, name) in members:
                return cls, members
        raise KeyError((fn, name))

    @property
    def reps(self):
        return {k[:-len(self._sfx)]: v for k, v in self._r.reps.items() if k.endswith(self._sfx)}


@pytest.fixture(scope="module")
def merged_cases(tmp_path_factory):
    cases = dict(REP_CASES)
    cases.update({"guard:" + k: v[0] for k, v in GUARD_PATHS.items()})
    cases.update({"index:" + k: v[0] for k, v in INDEX_PATHS.items()})
    cases.update({"list:" + k: v for k, v in LIST_CASES.items()})
    source, suffixes = merged(cases)
    proved, r = reps_of(tmp_path_factory.mktemp("merged"), source)
    return proved, r, rep.infer(proved, strict=True), suffixes


def shared_reps(merged_cases, key, strict=False):
    """`(proved, r)` of the case `key` as `reps_of` gives them for the case alone: its functions under their own names
    (the nodes are the merged module's own, so what `r` records about them holds)."""
    proved, r, r_strict, suffixes = merged_cases
    sfx = suffixes[key]
    return case_view(proved, sfx), _Reps(r_strict if strict else r, sfx)


def test_loop_counters_and_proved_results_are_raw(merged_cases):
    proved, r = shared_reps(merged_cases, "loop_counters")
    assert [r.name("f", n) for n in ("i", "t")] == [rep.RAW, rep.RAW] and r.returns("f") == rep.RAW
    assert all(o.proven for o in ops(proved, "f", ir.BinOpKind.MUL) + ops(proved, "f", ir.BinOpKind.ADD))


def test_an_element_read_and_a_length_are_raw_and_so_is_what_is_proved_from_them(merged_cases):
    _, r = shared_reps(merged_cases, "element_read")
    assert [r.name("f", n) for n in ("i", "t")] == [rep.RAW, rep.RAW] and r.returns("f") == rep.RAW


def test_an_accumulator_that_is_not_proved_is_tagged_and_so_is_its_return(merged_cases):
    _, r = shared_reps(merged_cases, "accumulator")
    assert r.name("f", "n") == rep.RAW and r.name("f", "i") == rep.RAW          # nobody passes a TAGGED n
    assert r.name("f", "s") == rep.TAGGED and r.returns("f") == rep.TAGGED


def test_an_int_read_from_the_arguments_is_tagged_and_so_is_a_loop_over_it(merged_cases):
    _, r = shared_reps(merged_cases, "int_from_argv")
    assert [r.name("f", x) for x in ("n", "i", "t")] == [rep.TAGGED] * 3 and r.returns("f") == rep.TAGGED


def test_a_proved_operation_with_a_tagged_operand_is_tagged(merged_cases):
    """The verifier proves `x + 1` (x is narrowed to 0..99 by the guard), but x is a big-integer-capable value: a
    proved flag assumes its operands fit 64 bits, so the result is TAGGED."""
    proved, r = shared_reps(merged_cases, "proved_with_tagged_operand")
    [add] = ops(proved, "f", ir.BinOpKind.ADD)
    assert add.proven, "the premise: the flag is set"
    assert r.name("f", "x") == rep.TAGGED and r.name("f", "y") == rep.TAGGED and r.returns("f") == rep.TAGGED


def test_a_proof_read_from_a_guard_on_a_tagged_value_is_not_trusted(merged_cases):
    """`lo + 1` is proved from `lo < hi` and `hi <= INT64_MAX`; hi = lo * lo is TAGGED, so that is not true of its
    value: the sound re-proof (aot/rep.py, verify.sound_proofs) does not prove `lo + 1`, so it is not RAW."""
    proved, r = shared_reps(merged_cases, "guard_on_tagged")
    [add] = ops(proved, "step", ir.BinOpKind.ADD)
    assert add.proven, "the premise: the verifier proves it"
    assert not r.proved(add) and r.of("step", add) == rep.TAGGED
    assert r.name("step", "lo") == rep.TAGGED and r.returns("step") == rep.TAGGED


def test_a_guard_on_a_raw_value_taints_nothing(merged_cases):
    proved, r = shared_reps(merged_cases, "guard_on_raw")
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
def test_a_proof_that_reads_a_tagged_value_does_not_make_an_operation_raw(merged_cases, name):
    source, fn, text = GUARD_PATHS[name]
    proved, r = shared_reps(merged_cases, "guard:" + name)
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
def test_a_bound_proof_that_reads_a_tagged_value_keeps_the_check(merged_cases, name):
    source, array = INDEX_PATHS[name]
    proved, r = shared_reps(merged_cases, "index:" + name)
    found = [n for n in walk(function(proved, "f").body)
             if isinstance(n, (ir.Index, ir.StoreIndex)) and n.array == array]
    assert found and all(n.proven for n in found), "the premise: the verifier proves the index in bounds"
    assert not any(r.trusted("f", n) for n in found)


def test_what_holds_of_any_int_is_still_raw_and_trusted(merged_cases):
    """The other side: a guard against a length, an argument every caller passes RAW, an unpacked RAW element, a list of
    constant length and a list whose TAGGED length is guarded from below keep their proofs (the re-proof is not a
    blanket TAGGED)."""
    proved, r = shared_reps(merged_cases, "what_holds_of_any_int")
    adds = [n for name in ("f", "g") for n in walk(function(proved, name).body)
            if isinstance(n, ir.BinOp) and n.op is ir.BinOpKind.ADD and ir_text(n) == "a + 1"]
    assert len(adds) == 3 and all(n.proven and r.proved(n) for n in adds)
    assert r.name("f", "a") == rep.RAW and r.returns("g") == rep.RAW
    loads = [n for n in walk(function(proved, "f").body) if isinstance(n, ir.Index) and n.array in ("arr", "more")]
    assert len(loads) == 3 and all(n.proven and r.trusted("f", n) for n in loads)


def test_a_parameter_is_tagged_when_one_call_site_passes_a_tagged_value(merged_cases):
    _, r = shared_reps(merged_cases, "tagged_call_site")
    assert r.name("g", "a") == rep.TAGGED and r.returns("g") == rep.TAGGED
    assert r.name("h", "b") == rep.RAW and r.returns("h") == rep.RAW
    assert r.name("f", "x") == rep.TAGGED and r.name("f", "y") == rep.TAGGED and r.name("f", "z") == rep.RAW
    assert r.returns("f") == rep.RAW


def test_a_tuple_has_a_representation_per_element(merged_cases):
    _, r = shared_reps(merged_cases, "tuple_elements")
    assert r.reps["pair"].tuple_elements == [rep.TAGGED, rep.RAW]
    assert r.name("user", "x") == rep.TAGGED and r.name("user", "y") == rep.RAW and r.returns("user") == rep.RAW


def test_abs_of_an_int_is_tagged_where_the_negation_is_not_proved(merged_cases):
    _, r = shared_reps(merged_cases, "abs")
    assert r.returns("f") == rep.TAGGED and r.returns("g") == rep.RAW


def test_a_remainder_and_a_division_by_a_constant_of_raw_values_stay_raw(merged_cases):
    _, r = shared_reps(merged_cases, "remainder_and_division")
    assert r.returns("f") == rep.RAW and r.returns("g") == rep.RAW and r.returns("h") == rep.TAGGED


def test_strict_mode_has_nothing_tagged(merged_cases):
    _, r = shared_reps(merged_cases, "accumulator", strict=True)
    assert r.name("f", "s") == rep.RAW and r.returns("f") == rep.RAW


def groups_of(merged_cases, key):
    _, r = shared_reps(merged_cases, "list:" + key)
    return r, r.groups()


def test_a_name_that_is_assigned_from_another_list_is_one_group_with_it(merged_cases):
    r, groups = groups_of(merged_cases, "assign_alias")
    assert groups == [(rep.BIG, {("f", "a"), ("f", "b")})]


def test_an_argument_and_its_parameter_are_one_group_at_every_call_site(merged_cases):
    """`a` and `c` are both passed to `fill`, so they are one group with its parameter (and big-capable: `fill` stores
    a TAGGED value); `b` is never passed and stays raw."""
    r, groups = groups_of(merged_cases, "argument_and_parameter")
    assert groups == [(rep.BIG, {("f", "a"), ("f", "c"), ("fill", "p")}), (rep.RAW, {("f", "b")})]


def test_a_returned_list_and_its_call_result_are_one_group(merged_cases):
    r, groups = groups_of(merged_cases, "returned_list_and_call_result")
    assert groups == [(rep.BIG, {("f", "a"), ("make", "r"), ("make", rep.RETURN)})]
    assert r.return_class("make") == rep.BIG


def test_a_parameter_that_is_returned_joins_the_argument_and_the_call_result(merged_cases):
    """`b = same(a)`: the list `same` returns is its parameter, so `a` and `b` are one list and the store through `b`
    makes the group of `a` big-capable."""
    r, groups = groups_of(merged_cases, "returned_parameter")
    assert groups == [(rep.BIG, {("f", "a"), ("f", "b"), ("same", "p"), ("same", rep.RETURN)})]


def test_a_name_that_holds_two_lists_makes_them_one_group_and_the_others_stay_apart(merged_cases):
    """`c = a` and, on the other branch, `c = b`: all the lists `c` ever holds are one group with it."""
    r, groups = groups_of(merged_cases, "two_names_and_a_branch")
    assert groups == [(rep.BIG, {("f", "a"), ("f", "b"), ("f", "c")}), (rep.RAW, {("f", "d")})]


def test_a_name_assigned_a_raw_creation_and_later_a_big_one_is_big_capable(merged_cases):
    r, groups = groups_of(merged_cases, "a_name_assigned_twice")
    assert groups == [(rep.BIG, {("f", "a")})]


def test_a_copy_is_a_new_group_and_big_capability_goes_one_way_from_the_source(merged_cases):
    """`b = a[:]` does not merge `b` with `a`, but a copy of a big-capable list is big-capable. `d = c[:]` followed by a
    store of a TAGGED value into `d` makes `d` big-capable and leaves its source `c` raw."""
    r, groups = groups_of(merged_cases, "copies")
    assert groups == [(rep.BIG, {("f", "a")}), (rep.BIG, {("f", "b")}), (rep.RAW, {("f", "c")}), (rep.BIG, {("f", "d")})]
    assert [r.list_class("f", n) for n in "abcd"] == [rep.BIG, rep.BIG, rep.RAW, rep.BIG]


def test_two_functions_that_call_each_other_with_one_list_share_it(merged_cases):
    r, groups = groups_of(merged_cases, "two_functions_calling_each_other")
    assert groups == [(rep.BIG, {("f", "a"), ("ping", "p"), ("pong", "p")})]


def test_a_creation_from_a_tagged_value_is_big_capable_and_list_of_range_is_not(merged_cases):
    """`[n] * 2` and `[n, 1]` hold a TAGGED value; `[1, 2]` does not; `list(range(n))` holds values below `n`, which
    fit int64 (a length that does not fit is an OverflowError before the list exists), so it is raw."""
    r, groups = groups_of(merged_cases, "creation_with_a_tagged_value")
    assert [r.list_class("f", n) for n in "abcd"] == [rep.BIG, rep.BIG, rep.RAW, rep.RAW]
    assert len(groups) == 4, "four creations, four groups"


def test_a_list_given_only_proved_values_and_read_into_tagged_arithmetic_stays_raw(merged_cases):
    """`put` stores `i % 7` (RAW), `b[i] = i * 3 + 1` is proved: the lists are raw, and so are their elements; `s` that
    sums the elements is TAGGED (the sum is not proved), which does not make `b` big-capable (a read is not a store)."""
    proved, r = shared_reps(merged_cases, "list:raw_lists")
    assert [c for c, _ in r.groups()] == [rep.RAW, rep.RAW]
    assert r.list_class("put", "p") == r.list_class("f", "a") == r.list_class("f", "b") == rep.RAW
    reads = [n for n in walk(function(proved, "f").body) if isinstance(n, ir.Index)]
    assert len(reads) == 2 and all(r.of("f", n) == rep.RAW for n in reads)
    assert r.name("f", "s") == rep.TAGGED


def test_an_element_read_is_raw_from_a_raw_group_and_tagged_and_not_re_proved_from_a_big_one(merged_cases):
    """The same code twice, `v = a[1]; if v < 100: v + 1`, once on the big-capable list `a` and once on the raw list
    `b`. The verifier proves both additions (it reads every element as an int64); the sound re-proof proves only the
    second: an element of a big-capable list is any int, so `v + 1` is not RAW, and its list read is TAGGED."""
    proved, r = shared_reps(merged_cases, "list:element_reads")
    assert r.list_class("f", "a") == rep.BIG and r.list_class("f", "b") == rep.RAW
    reads = {n.array: n for n in walk(function(proved, "f").body) if isinstance(n, ir.Index)}
    assert r.of("f", reads["a"]) == rep.TAGGED and r.of("f", reads["b"]) == rep.RAW
    adds = {n.left.name: n for n in ops(proved, "f", ir.BinOpKind.ADD) if isinstance(n.left, ir.Local)}
    assert adds["v"].proven and adds["u"].proven, "the premise: the default-mode rules prove both"
    assert not r.proved(adds["v"]) and r.of("f", adds["v"]) == rep.TAGGED
    assert r.proved(adds["u"]) and r.of("f", adds["u"]) == rep.RAW
    assert r.name("f", "v") == rep.TAGGED and r.name("f", "u") == rep.RAW


def test_strict_mode_has_no_big_capable_list(merged_cases):
    """`--strict-int` has no TAGGED value, so no list is big-capable and every element read is RAW."""
    proved, r = shared_reps(merged_cases, "list:copies", strict=True)
    assert [r.list_class("f", n) for n in "abcd"] == [rep.RAW] * 4


def test_a_list_name_the_analysis_does_not_know_is_big_capable(merged_cases):
    """On any doubt, big-capable: a name that is not a list of the function."""
    r = merged_cases[1]
    assert r.list_big("nowhere", "x") and r.list_class("nowhere", "x") == rep.BIG


EXPLAIN_SOURCE = """
import sys

def fill(p: list[int], v: int) -> None:
    p[0] = v

def main() -> None:
    a = [1, 2]
    b = a
    fill(b, int(sys.argv[1]))
    c = [5]
    print(a[0], c[0])

if __name__ == "__main__":
    main()
"""

EXPLAIN_TEXT = """list group 1: big-capable
  members: fill.p (line 3), main.a (line 7), main.b (line 8)
  merged:
    line 8, in main: `b = a`: main.b and main.a may be the same list (assignment)
    line 9, in main: `fill(b, int(sys.argv[1]))`: main.b and fill.p may be the same list (the argument and the parameter `p` of the call of `fill`)
  big-capable because:
    line 4, in fill: `p[0] = v`: stores a TAGGED value into `p`
list group 2: raw
  members: main.c (line 10)
  merged: nothing
  raw: no TAGGED value is stored into it, no creation of it holds one, and no copy of a big-capable list reaches it
"""


def test_explain_lists_prints_the_groups_the_merges_and_the_causes_and_builds_nothing(tmp_path, capsys):
    path = write(tmp_path, EXPLAIN_SOURCE, "explained.py")
    from typedpython.__main__ import main
    status = main(["aot-build", str(path), "--explain-lists"])
    shown = capsys.readouterr()
    assert status == 0 and shown.err == ""
    assert shown.out == EXPLAIN_TEXT
    assert sorted(p.name for p in tmp_path.iterdir()) == ["explained.py"], "nothing was built or written"


def test_the_executable_and_the_library_commands_still_need_an_output_without_the_explain_flag(tmp_path):
    from typedpython.__main__ import main
    path = write(tmp_path, EXPLAIN_SOURCE, "needs_output.py")
    for argv in (["aot-build", str(path)], ["aot-lib", str(path), "--export", "main", "--prefix", "p"]):
        with pytest.raises(SystemExit) as raised:
            main(argv)
        assert raised.value.code == 2


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
