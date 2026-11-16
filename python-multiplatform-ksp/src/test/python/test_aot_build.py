"""First AOT build step (SPEC N-17, #448, #451): `python -m typedpython aot-build`.

A single-module program of scalar functions with an `if __name__ == "__main__":` entry is compiled to an
executable that contains no CPython. The differential tests run the executable beside the same file
under `sys.executable` (no `@compiled`, plain CPython): standard output and the exit status must be
equal, and for an uncaught exception the last line of standard error too. They need a C compiler
(skipped without one) and CPython 3.14 or later (the messages of 3.14 and 3.15). The rejection table
and the generated-C text checks need no compiler (they need Pyrefly, like test_aot_check.py).

Executables built here (each one runs the type inferencer and the C compiler, which is what made the
windows job slow): the merged program 1 (through the command line), the uncaught-exception programs 9
(one process each, see RAISING), the size measurement 2: 12 in all, 10 without the measurement. Before
#451 the file built 22 (8 programs, the float corpus, 10 exception programs, the command line test, 2
sizes).

Everything here is an agent proposal, not owner-confirmed.
"""
import re
import shutil
import subprocess
import sys
import textwrap
from pathlib import Path
from types import SimpleNamespace

import pytest

from typedpython import aot, cbuild
from typedpython.__main__ import main
from typedpython.aot import cc

HEADER = cc.RUNTIME_DIR / "tp_aot.h"


def need_cc():
    if sys.version_info < (3, 14):
        pytest.skip("the AOT runtime carries the exception messages of CPython 3.14 and later")
    if not HEADER.is_file():
        pytest.skip(f"{HEADER} does not exist")
    try:
        if cbuild._is_msvc():
            cbuild.msvc_env()
        elif shutil.which(cbuild.compiler()[0]) is None:
            pytest.skip("no C compiler")
    except cbuild.CBuildError as e:
        pytest.skip(str(e))


def write(tmp_path, source, name="prog"):
    path = tmp_path / f"{name}.py"
    path.write_text(textwrap.dedent(source).lstrip("\n"))
    return path


def text(b: bytes) -> str:
    return b.decode("utf-8", errors="replace").replace("\r\n", "\n")


def last_line(stderr: bytes) -> str:
    lines = [ln.strip() for ln in text(stderr).splitlines() if ln.strip()]
    return lines[-1] if lines else ""


def run_both(tmp_path, source, name="prog"):
    """Build `source` as an AOT executable, run it and run the file under CPython."""
    need_cc()
    src = write(tmp_path, source, name)
    exe = aot.build(src, tmp_path / name)
    ours = subprocess.run([str(exe)], capture_output=True, timeout=60)
    theirs = subprocess.run([sys.executable, str(src)], capture_output=True, timeout=60)
    return ours, theirs


def assert_same(ours, theirs, failed=False):
    assert text(ours.stdout) == text(theirs.stdout)
    assert ours.returncode == theirs.returncode
    if failed:
        assert theirs.returncode == 1
        assert last_line(ours.stderr) == last_line(theirs.stderr)
    else:
        assert theirs.returncode == 0


# --- the cases that end normally: one program, one build, one run ---------------------------------
#
# Every case is a chunk of the module (its functions and constants, then `case_<name>()` holding what
# was that program's entry block). The merged program calls them in turn, printing a marker line
# before each; the executable and the file under CPython run once, and the two outputs are compared
# whole and then case by case (split at the markers), so a failure names the case.

MARK = "@@case "

CASES = {
    "int_loops": """
        def gcd(a: int, b: int) -> int:
            while b != 0:
                t = a % b
                a = b
                b = t
            return a

        def clamp(x: int, lo: int, hi: int) -> int:
            if x < lo:
                return lo
            if x > hi:
                return hi
            return x

        def case_int_loops() -> None:
            print(gcd(48, 18), gcd(17, 5), gcd(0, 9), gcd(-12, 18), gcd(12, -18), gcd(-12, -18))
            for i in range(1, 8):
                print(i, i * i, i * i * i, (i - 4) // 3, (i - 4) % 3, (i - 4) // -3, (i - 4) % -3, i * 7 - 20)
            for i in range(10, 0, -3):
                print(i, clamp(i, 2, 8))
            j = 0
            while j < 6:
                j += 1
                print(j, 100 // 7, 100 % 7, -100 // 7, -100 % 7, 100 // -7, 100 % -7)
        """,
    "float_division": """
        def show_div(a: float, b: float) -> None:
            print(a, b, a / b, a // b, a % b)

        def case_float_division() -> None:
            show_div(7.5, 2.0)
            show_div(-7.5, 2.0)
            show_div(7.5, -2.0)
            show_div(-7.5, -2.0)
            show_div(0.0, -3.0)
            show_div(-0.0, 3.0)
            show_div(5.5, 0.1)
            show_div(-5.5, 0.1)
            show_div(1e308, 1e-3)
            show_div(1.0, 3.0)
            show_div(2.0, 7.0)
            show_div(1e-300, 1e300)
            x = 1e308 * 10
            show_div(1.0, x)
            show_div(-1.0, x)
            show_div(x, 3.0)
        """,
    "comparisons": """
        def cmp(x: float, n: int) -> None:
            print(x < n, x <= n, x == n, x != n, x > n, x >= n)
            print(n < x, n <= x, n == x, n != x, n > x, n >= x)

        def logic(a: bool, b: bool) -> None:
            print(a and b, a or b, not a, a == b, a != b, not (a or b), a and not b)

        def band(x: float) -> bool:
            return 0.0 < x < 10.0

        def zone(x: float, n: int) -> int:
            if x < 0.0 and n < 0:
                return 1
            if x < 0.0 or n < 0:
                return 2
            if not (x >= 100.0) and n != 7:
                return 3
            return 4

        def case_comparisons() -> None:
            cmp(2.5, 2)
            cmp(2.0, 2)
            cmp(-0.0, 0)
            cmp(9007199254740992.0, 9007199254740993)
            cmp(9007199254740994.0, 9007199254740993)
            cmp(1e300, 9223372036854775807)
            cmp(-1e300, -9223372036854775807)
            nan = 1e308 * 10 - 1e308 * 10
            cmp(nan, 1)
            logic(True, True)
            logic(True, False)
            logic(False, True)
            logic(False, False)
            print(band(5.0), band(-1.0), band(10.0), band(0.0), band(9.999))
            print(zone(-1.0, -1), zone(-1.0, 1), zone(1.0, -1), zone(1.0, 1), zone(1.0, 7), zone(200.0, 1))
        """,
    "recursion": """
        def power(x: float, n: int) -> float:
            if n <= 0:
                return 1.0
            return x * power(x, n - 1)

        def harmonic(n: int) -> float:
            if n <= 0:
                return 0.0
            return 1.0 / float(n) + harmonic(n - 1)

        def is_even(n: int) -> bool:
            if n <= 0:
                return n == 0
            return not is_even(n - 1)

        def depth(n: int) -> int:
            if n <= 0:
                return 0
            return depth(n - 1)

        def case_recursion() -> None:
            print(power(2.0, 10), power(1.1, 50), power(-3.0, 7), power(0.5, 0))
            print(harmonic(1), harmonic(10), harmonic(100))
            print(is_even(0), is_even(1), is_even(10), is_even(101), is_even(-3))
            print(depth(500), depth(0), depth(-5))
        """,
    "nested_loops": """
        def primes() -> None:
            for n in range(2, 60):
                is_prime = True
                for d in range(2, n):
                    if d * d > n:
                        break
                    if n % d == 0:
                        is_prime = False
                        break
                if not is_prime:
                    continue
                print(n)

        def table() -> None:
            for i in range(1, 6):
                for j in range(1, 6):
                    if j > i:
                        break
                    if (i + j) % 2 == 1:
                        continue
                    print(i, j, i * j)

        def countdown() -> None:
            i = 20
            while i > 0:
                i -= 1
                if i % 5 != 0:
                    continue
                print("tick", i)
                if i < 6:
                    break

        def case_nested_loops() -> None:
            primes()
            table()
            countdown()
        """,
    "math_functions": """
        def show_math(x: float) -> None:
            print(math.sqrt(x), math.exp(x), math.log(x), math.sin(x), math.cos(x), math.tan(x), math.fabs(-x))

        def two(a: float, b: float) -> None:
            print(math.atan2(a, b), math.hypot(a, b), math.atan2(-a, b), math.hypot(a * 1e200, b * 1e200))

        def case_math_functions() -> None:
            show_math(0.5)
            show_math(1.0)
            show_math(2.0)
            show_math(10.0)
            show_math(123.456)
            show_math(1e-10)
            two(3.0, 4.0)
            two(1.0, 1.0)
            two(0.0, 1.0)
            two(1.0, 0.0)
            two(-2.5, 0.25)
            two(0.0, 0.0)
            print(math.fabs(-0.0), math.sqrt(0.0), math.exp(0.0), math.log(1.0), math.exp(-1000.0))
        """,
    "module_constants": """
        N = 10
        SCALE = 2.5
        HALF = SCALE / 2
        NEG = -N
        FLAG = True
        BIG = 1e308 * 10
        MIX = N * SCALE - 1

        def scaled(i: int) -> float:
            return i * SCALE + N

        def below(x: float) -> bool:
            return x < BIG and FLAG

        def shadow(N: int) -> int:
            return N % 4

        def assigned(x: int) -> int:
            SCALE = x % 5
            return SCALE

        def case_module_constants() -> None:
            print(N, SCALE, HALF, NEG, FLAG, BIG, MIX)
            for i in range(4):
                print(scaled(i), below(scaled(i)), shadow(i * 3), assigned(i * 7))
            print(math.sqrt(MIX), N // 3, N % 3, NEG // 3, NEG % 3)
        """,
    "annotated_constants": """
        PI: float = 3.14
        LIMIT: int = 7
        STEP: int = 3
        ON: bool = True
        TAU: float = 2 * PI
        SOLAR_MASS: float = 4 * PI * PI
        DOUBLE_LIMIT: int = LIMIT * 2

        def decay(x: float, n: int) -> float:
            if n <= 0:
                return x * SOLAR_MASS
            return decay(x * 0.5, n - 1) + PI

        def case_annotated_constants() -> None:
            print(PI, LIMIT, STEP, ON, TAU, SOLAR_MASS, DOUBLE_LIMIT)
            print(decay(1.0, 5), decay(2.5, 0))
            for i in range(0, LIMIT, STEP):
                print(i, i * LIMIT, ON and i < STEP)
            for i in range(LIMIT, 0, -STEP):
                print(i)
        """,
    "printing": """
        INF = 1e308 * 10

        def case_printing() -> None:
            print(True, False)
            print(-0.0, 0.0, INF, -INF)
            x = 1e308 * 10
            print(x, -x, x - x)
            print(1, -5, 9223372036854775807, 0)
            print("hello", "world", 42, 3.5, "tab\\there", 'quote"s', "back\\\\slash", "what?", "")
            print("ends", True)
            print()
            print(1.0, 10.0, 100.0, 1e15, 1e16, 1e17, 0.1, 0.01, 0.001, 0.0001, 0.00001)
            print(1.5e-7, 12345.6789, -2.5, 1e22, 1e23, 123456789.0, 1.0 / 3.0, 2.0 / 3.0)
        """,
    "float_repr_corpus": """
        def literals() -> None:
            print(1e16, 1e-4, 1e-5, 1e22, 5e-324, 1.7976931348623157e308, 0.1 + 0.2, 1 / 3, 2 / 3)
            print(100.0, 123456789012345680.0, 1e15, 9999999999999998.0, 1e23, 1.5e-7, 0.0001)
            print(0.00001234, 123456789.123456789, 4.35, 2.675, 5e-324 * 3, 2.2250738585072014e-308)
            print(2.225073858507201e-308, 1e300 * 1e10, -1e300 * 1e10, 0.3, 0.1 * 3.0, 1e21, 1e-7)
            print(9007199254740992.0, 9007199254740993.0, 4503599627370496.5, 0.5, 0.25, 1.1, 2.2, 3.3)
            print(8.41e21, 1.2345e-5, 99999999999999.99, 999999999999999.9, 9999999999999999.0, 5e-5)
            print(1.0000000000000002, 0.9999999999999999, 123e18, 123e-18, 17.0, 17.5, 1e2, 1e3, 1e5)

        def growing() -> None:
            x = 1.0
            for i in range(60):
                print(x)
                x = x * 7.123456789

        def shrinking() -> None:
            x = 1.0
            for i in range(340):
                x = x * 0.1
                if i % 4 == 0:
                    print(x)

        def sums() -> None:
            x = 0.0
            for i in range(40):
                x = x + 0.1
                print(x)

        def halves() -> None:
            x = 1.0
            for i in range(1074):
                x = x * 0.5
                if i % 25 == 0:
                    print(x)
            print(x)
            print(x * 0.5)
            print(-x)

        def doubles() -> None:
            x = 1.0
            for i in range(1023):
                x = x * 2.0
                if i % 20 == 0:
                    print(x)
            print(x)
            print(x * 2.0)

        def decades() -> None:
            x = 1e-7
            for i in range(34):
                print(x)
                x = x * 10.0

        def odd() -> None:
            x = 0.7
            for i in range(30):
                print(x, -x)
                x = x * x + 0.123

        def case_float_repr_corpus() -> None:
            literals()
            growing()
            shrinking()
            sums()
            halves()
            doubles()
            decades()
            odd()
        """,
    # `main` is called straight from the entry block (see merged_source), and prints itself
    "main_function": """
        def main() -> None:
            print("in main", 1, 2.5)
        """,
}
DIRECT = {"main_function": "main()"}        # what the entry block runs for a case with no case_<name>()


def merged_source(names=None) -> str:
    """The cases `names` (default all) as one program: `import math`, the chunks, then the entry block that
    prints `@@case <name>` and runs each case."""
    names = list(CASES) if names is None else list(names)
    parts = ["import math\n"]
    parts += [textwrap.dedent(CASES[n]).strip("\n") + "\n" for n in names]
    entry = ['if __name__ == "__main__":']
    for n in names:
        entry.append(f'    print("{MARK}{n}")')
        entry.append(f"    {DIRECT.get(n, f'case_{n}()')}")
    return "\n".join(parts) + "\n" + "\n".join(entry) + "\n"


def split_cases(out: str) -> dict[str, str]:
    """Standard output cut at the marker lines: case name -> what it printed."""
    found: dict[str, list[str]] = {}
    current = None
    for line in out.splitlines():
        if line.startswith(MARK):
            current = line[len(MARK):]
            found[current] = []
        elif current is not None:
            found[current].append(line)
        else:
            found.setdefault("<before the first marker>", []).append(line)
    return {k: "\n".join(v) for k, v in found.items()}


@pytest.fixture(scope="module")
def merged(tmp_path_factory):
    """Build the merged program once through the command line (the exit status and `--emit-c` are
    checked by the tests below), run it once, run the same file under CPython once."""
    need_cc()
    tmp = tmp_path_factory.mktemp("merged")
    src = write(tmp, merged_source(), "merged")
    out = tmp / "merged_exe"
    c_file = tmp / "merged.c"
    status = main(["aot-build", str(src), "-o", str(out), "--emit-c", str(c_file)])
    exe = out.with_name(out.name + ".exe") if sys.platform == "win32" else out
    ours = subprocess.run([str(exe)], capture_output=True, timeout=120) if status == 0 else None
    theirs = subprocess.run([sys.executable, str(src)], capture_output=True, timeout=120)
    return SimpleNamespace(status=status, c_file=c_file, ours=ours, theirs=theirs,
                           ours_cases=split_cases(text(ours.stdout)) if ours else {},
                           theirs_cases=split_cases(text(theirs.stdout)))


def test_merged_program_builds_and_runs(merged):
    assert merged.status == 0, "the command line build of the merged program failed"
    assert merged.theirs.returncode == 0, text(merged.theirs.stderr)
    assert merged.ours.returncode == 0, text(merged.ours.stderr)
    assert "tpa_print_str" in merged.c_file.read_text()          # --emit-c wrote the C beside the executable


def test_merged_program_whole_output_matches_cpython(merged):
    assert merged.ours is not None
    assert text(merged.ours.stdout) == text(merged.theirs.stdout)
    assert merged.ours.returncode == merged.theirs.returncode == 0


def test_every_case_printed_its_marker_in_order(merged):
    want = list(CASES)
    assert [k for k in merged.theirs_cases if k in CASES] == want
    assert [k for k in merged.ours_cases if k in CASES] == want
    assert "<before the first marker>" not in merged.ours_cases


@pytest.mark.parametrize("name", list(CASES))
def test_case_matches_cpython(merged, name):
    assert merged.ours is not None, "the merged program did not build"
    theirs = merged.theirs_cases[name]
    assert theirs.strip(), "the case must print something"
    assert merged.ours_cases.get(name) == theirs


def test_float_repr_corpus_is_large_enough(merged):
    assert len(merged.theirs_cases["float_repr_corpus"].splitlines()) > 300


# --- uncaught exceptions ---------------------------------------------------------------------------
#
# An uncaught exception ends the process, so each of these is its own executable: there is no
# `sys.argv` yet to pick a case in one build. NINE BUILDS, one per distinct exception path (each is a
# different runtime helper; the float division reaches its helper through two frames, which is also the
# propagation check). The next PR, which adds `sys.argv`, can fold them into one executable that takes
# the case name as its argument.

RAISING = {
    "float_div_zero_through_two_frames": """
        def inner(a: float) -> float:
            return 1.0 / a

        def outer(a: float) -> float:
            return inner(a) + inner(a - 1.0)

        if __name__ == "__main__":
            print("before", outer(2.0))
            print(outer(1.0))
            print("after")
        """,
    "float_floordiv_zero": """
        def fd(a: float, b: float) -> float:
            return a // b

        if __name__ == "__main__":
            print("before", fd(7.0, 2.0))
            print(fd(1.0, -0.0))
            print("after")
        """,
    "float_mod_zero": """
        def md(a: float, b: float) -> float:
            return a % b

        if __name__ == "__main__":
            print("before", md(7.0, 2.0))
            print(md(1.0, 0.0))
            print("after")
        """,
    "int_floordiv_zero": """
        def fd(a: int) -> int:
            return a // 0

        if __name__ == "__main__":
            print("before")
            print(fd(5))
            print("after")
        """,
    "int_mod_zero": """
        def md(a: int, b: int) -> int:
            return a % b

        if __name__ == "__main__":
            print("before", md(7, 3), md(-7, 3))
            print(md(5, 0))
            print("after")
        """,
    "int_truediv_zero": """
        def td() -> float:
            return 1 / 0

        if __name__ == "__main__":
            print("before")
            print(td())
        """,
    "sqrt_negative": """
        import math

        if __name__ == "__main__":
            print("before", math.sqrt(4.0))
            x = -1.0
            print(math.sqrt(x))
            print("after")
        """,
    "log_zero": """
        import math

        def f(x: float) -> float:
            return math.log(x)

        if __name__ == "__main__":
            print("before", f(1.0))
            print(f(0.0))
            print("after")
        """,
    "exp_overflow": """
        import math

        def f(x: float) -> float:
            return math.exp(x)

        if __name__ == "__main__":
            print("before", f(1.0))
            print(f(1000.0))
            print("after")
        """,
}


@pytest.mark.parametrize("name", sorted(RAISING))
def test_uncaught_exception_matches_cpython(tmp_path, name):
    ours, theirs = run_both(tmp_path, RAISING[name], name)
    assert text(theirs.stdout).startswith("before")
    assert "after" not in text(ours.stdout)
    assert_same(ours, theirs, failed=True)


# --- what the mode refuses -------------------------------------------------------------------------

MAIN = '\nif __name__ == "__main__":\n    pass\n'

# source (line 1 is its first line), the expected (rule, line, name)
REJECTED = {
    "unproved int op": (
        "def f(n: int) -> int:\n    s = 0\n    i = 0\n    while i < n:\n        s = s + i\n        i = i + 1\n    return s\n",
        ("aot/int-unproven", 5, "+")),
    "unproved augmented op": (
        "def f(n: int) -> int:\n    s = 0\n    for i in range(n):\n        s += i\n    return s\n",
        ("aot/int-unproven", 4, "+")),
    "int negation": ("def f(a: int) -> int:\n    return -a\n", ("aot/int-unproven", 2, "-")),
    "int floor division by a variable": (
        "def f(a: int, b: int) -> int:\n    return a // b\n", ("aot/int-unproven", 2, "//")),
    "int true division": ("def f(a: int, b: int) -> float:\n    return a / b\n", ("aot/int-true-division", 2, "/")),
    "int literal outside 64 bits": (
        "def f() -> int:\n    return 18446744073709551616\n", ("aot/int-literal", 2, "18446744073709551616")),
    "module constant outside 64 bits": (
        "N = 9223372036854775807 + 1\n", ("aot/constant", 1, "N")),
    "module statement": ('print("hi")\n', ("aot/module-statement", 1, "Expr")),
    "module loop": ("for i in range(3):\n    pass\n", ("aot/module-statement", 1, "For")),
    "module constant that is a call": ("N = abs(3)\n", ("aot/constant", 1, "N")),
    "float annotation on an int value": ("X: float = 3\n", ("aot/constant", 1, "X")),
    "int annotation on a bool value": ("X: int = True\n", ("aot/constant", 1, "X")),
    "annotation outside int float bool": ("X: str = 3\n", ("aot/constant", 1, "X")),
    "annotated constant without a value": ("X: float\n", ("aot/constant", 1, "X")),
    "annotated constant outside 64 bits": ("X: int = 9223372036854775807 + 1\n", ("aot/constant", 1, "X")),
    "second import": ("import math\nimport sys\n", ("aot/import", 2, "sys")),
    "from import": ("from math import sqrt\n", ("aot/import", 1, "math")),
    "list": ("def f() -> int:\n    x = [1, 2]\n    return 0\n", ("aot/container", 2, "list display")),
    "string variable": ("def f() -> None:\n    s = 'x'\n", ("aot/str", 2, "str")),
    "f-string": ("def f(x: int) -> None:\n    print(f'{x}')\n", ("aot/f-string", 2, "f-string")),
    "print keyword": ("def f() -> None:\n    print(1, end='')\n", ("aot/print-keyword", 2, "end")),
    "print as a value": ("def f() -> None:\n    x = print(1)\n", ("aot/print-use", 2, "print")),
    "class": ("class C:\n    pass\n", ("aot/module-statement", 1, "class")),
    "unknown builtin": ("def f(x: float) -> int:\n    return abs(x)\n", ("aot/builtin", 2, "abs")),
    "int builtin": ("def f(x: float) -> int:\n    return int(x)\n", ("aot/builtin", 2, "int")),
    "math attribute": ("import math\ndef f() -> float:\n    return math.pi\n", ("aot/attribute", 3, "pi")),
    "math function": ("import math\ndef f(x: float) -> float:\n    return math.floor(x)\n",
                      ("aot/math", 3, "math.floor")),
    "bitwise operator": ("def f(a: int) -> int:\n    return a & 3\n", ("aot/operator", 2, "BitAnd")),
    "decorator": ("@compiled\ndef f() -> None:\n    pass\n", ("aot/decorator", 1, "compiled")),
    "conditional expression": ("def f(a: bool) -> int:\n    return 1 if a else 2\n",
                               ("aot/expression", 2, "conditional expression")),
    "missing annotation": ("def f(a) -> int:\n    return 1\n", ("aot/annotation", 1, "parameter `a`")),
    "list annotation": ("def f(a: list[int]) -> int:\n    return 1\n", ("aot/type", 1, "list[int]")),
    "assignment to a module name in the entry block": (
        "N = 3\nif __name__ == \"__main__\":\n    N = 4\n", ("aot/main-rebinds", 3, "N")),
    "no entry": ("def f() -> None:\n    pass\n", ("aot/entry", 1, "__main__")),
    "statements after the entry block": (
        'if __name__ == "__main__":\n    pass\ndef g() -> int:\n    return 1\n', ("aot/entry", 3, "__main__")),
    "a function the front end skips": (
        "def f(n: int, k: int) -> int:\n    t = 0\n    for i in range(0, n, k):\n        t = 1\n    return t\n",
        ("aot/front-end", 3, "f")),
    "a function the verifier rejects": (
        "def f(n: int) -> int:\n    if n > 0:\n        x = 1\n    return x\n", ("aot/verify", 1, "f")),
}


@pytest.mark.parametrize("name", sorted(REJECTED))
def test_rejection_table(tmp_path, name):
    source, (rule, line, what) = REJECTED[name]
    if rule not in ("aot/entry",) and "__main__" not in source:
        source += MAIN
    path = write(tmp_path, source)
    with pytest.raises(aot.AotError) as raised:
        aot.generate(path)
    found = [(f.rule, f.line, f.name) for f in raised.value.findings]
    assert (rule, line, what) in found, found
    assert all(f.path == "prog.py" for f in raised.value.findings)


def test_unproved_int_message_names_the_expression(tmp_path):
    source, _ = REJECTED["unproved int op"]
    path = write(tmp_path, source + MAIN)
    with pytest.raises(aot.AotError) as raised:
        aot.generate(path)
    [f] = raised.value.findings
    assert (f.rule, f.line) == ("aot/int-unproven", 5)
    assert "`s + i`" in f.message
    assert "not proved" in f.message and "64 bits" in f.message and "later step" in f.message
    assert "prog.py:5: error aot/int-unproven:" in str(raised.value)


def test_int_true_division_message_names_the_expression(tmp_path):
    path = write(tmp_path, "def f(a: int, b: int) -> float:\n    return a / b\n" + MAIN)
    with pytest.raises(aot.AotError) as raised:
        aot.generate(path)
    [f] = raised.value.findings
    assert f.rule == "aot/int-true-division" and "`a / b`" in f.message and "2**53" in f.message


def test_proved_int_operations_are_accepted(tmp_path):
    """Loop counters, a counter bounded by its loop test, a remainder and a floor division by a
    constant need no proof from the caller."""
    path = write(tmp_path, """
        def f(n: int) -> int:
            i = 0
            while i < n:
                i += 1
            r = 0
            for k in range(100):
                m = k * k + 1
                r = (m - 5) // 3 + i % 5
            return r % 5

        if __name__ == "__main__":
            print(f(10))
        """)
    c = aot.generate(path)
    assert "tpa_padd_i64" in c and "tpa_mod_i64" in c and "tpa_floordiv_i64" in c


# --- the generated C -------------------------------------------------------------------------------

def test_generated_c_contains_no_cpython(tmp_path):
    c = aot.generate(write(tmp_path, merged_source(["math_functions"])))
    header = HEADER.read_text()
    for what, body in (("generated C", c), ("tp_aot.h", header)):
        assert "Python.h" not in body, what
        assert not re.search(r"\b_?Py[A-Z_a-z]", body), (what, re.findall(r"\b_?Py[A-Z_a-z]\w*", body))
    assert '#include "tp_aot.h"' in c
    for inc in re.findall(r"#include\s*[<\"]([^>\"]+)", c + header):
        assert inc in {"tp_aot.h", "errno.h", "math.h", "stdint.h", "stdio.h", "stdlib.h", "string.h"}, inc


def test_generated_c_uses_the_default_modes_float_flags():
    assert "-ffp-contract=off" in cc.FLAGS and "-fno-fast-math" in cc.FLAGS
    assert cbuild.DEFAULT_FLAGS == cc.FLAGS[:len(cbuild.DEFAULT_FLAGS)]
    assert "/fp:precise" in cbuild._msvc_flags(list(cc.FLAGS))


def test_cli_reports_each_finding_on_one_line(tmp_path, capsys):
    src = write(tmp_path, REJECTED["unproved int op"][0] + MAIN)
    assert main(["aot-build", str(src), "-o", str(tmp_path / "never")]) == 1
    err = capsys.readouterr().err.strip().splitlines()
    assert len(err) == 1 and err[0].startswith("prog.py:5: error aot/int-unproven: ")
    assert not (tmp_path / "never").exists()


# --- measurement -----------------------------------------------------------------------------------

@pytest.mark.measurement
def test_executable_sizes(tmp_path):
    need_cc()
    sizes = {}
    for name, source in (("empty", 'if __name__ == "__main__":\n    pass\n'),
                         ("hello", 'if __name__ == "__main__":\n    print("hello")\n')):
        exe = aot.build(write(tmp_path, source, name), tmp_path / name)
        sizes[name] = exe.stat().st_size
        strip = shutil.which("strip")
        if strip and sys.platform != "win32":
            copy = tmp_path / f"{name}_stripped"
            shutil.copy2(exe, copy)
            if subprocess.run([strip, str(copy)], capture_output=True).returncode == 0:
                sizes[f"{name} stripped"] = copy.stat().st_size
    print()
    for k, v in sizes.items():
        print(f"[aot size] {k}: {v} bytes")
    assert all(v < 5_000_000 for v in sizes.values())
