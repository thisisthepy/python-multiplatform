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
