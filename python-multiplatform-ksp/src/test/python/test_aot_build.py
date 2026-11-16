"""First AOT build step (SPEC N-17, #448): `python -m typedpython aot-build`.

A single-module program of scalar functions with an `if __name__ == "__main__":` entry is compiled to an
executable that contains no CPython. The differential tests build each program and run it beside the
same file under `sys.executable` (no `@compiled`, plain CPython): standard output and the exit status
must be equal, and for an uncaught exception the last line of standard error too. They need a C
compiler (skipped without one) and CPython 3.14 or later (the messages of 3.14 and 3.15). The rejection
table and the generated-C text checks need no compiler (they need Pyrefly, like test_aot_check.py).

Everything here is an agent proposal, not owner-confirmed.
"""
import re
import shutil
import subprocess
import sys
import textwrap
from pathlib import Path

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


# --- programs the build must run exactly as CPython does ------------------------------------------

PROGRAMS = {
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

        if __name__ == "__main__":
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
        def show(a: float, b: float) -> None:
            print(a, b, a / b, a // b, a % b)

        if __name__ == "__main__":
            show(7.5, 2.0)
            show(-7.5, 2.0)
            show(7.5, -2.0)
            show(-7.5, -2.0)
            show(0.0, -3.0)
            show(-0.0, 3.0)
            show(5.5, 0.1)
            show(-5.5, 0.1)
            show(1e308, 1e-3)
            show(1.0, 3.0)
            show(2.0, 7.0)
            show(1e-300, 1e300)
            x = 1e308 * 10
            show(1.0, x)
            show(-1.0, x)
            show(x, 3.0)
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

        if __name__ == "__main__":
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

        if __name__ == "__main__":
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

        if __name__ == "__main__":
            primes()
            table()
            countdown()
        """,
    "math_functions": """
        import math

        def show(x: float) -> None:
            print(math.sqrt(x), math.exp(x), math.log(x), math.sin(x), math.cos(x), math.tan(x), math.fabs(-x))

        def two(a: float, b: float) -> None:
            print(math.atan2(a, b), math.hypot(a, b), math.atan2(-a, b), math.hypot(a * 1e200, b * 1e200))

        if __name__ == "__main__":
            show(0.5)
            show(1.0)
            show(2.0)
            show(10.0)
            show(123.456)
            show(1e-10)
            two(3.0, 4.0)
            two(1.0, 1.0)
            two(0.0, 1.0)
            two(1.0, 0.0)
            two(-2.5, 0.25)
            two(0.0, 0.0)
            print(math.fabs(-0.0), math.sqrt(0.0), math.exp(0.0), math.log(1.0), math.exp(-1000.0))
        """,
    "module_constants": """
        import math

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

        if __name__ == "__main__":
            print(N, SCALE, HALF, NEG, FLAG, BIG, MIX)
            for i in range(4):
                print(scaled(i), below(scaled(i)), shadow(i * 3))
            print(math.sqrt(MIX), N // 3, N % 3, NEG // 3, NEG % 3)
        """,
    "printing": """
        INF = 1e308 * 10

        if __name__ == "__main__":
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
}


@pytest.mark.parametrize("name", sorted(PROGRAMS))
def test_program_matches_cpython(tmp_path, name):
    ours, theirs = run_both(tmp_path, PROGRAMS[name], name)
    assert text(theirs.stdout).strip(), "the program must print something"
    assert_same(ours, theirs)


def corpus_program() -> str:
    return """
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

        if __name__ == "__main__":
            literals()
            growing()
            shrinking()
            sums()
            halves()
            doubles()
            decades()
            odd()
        """


def test_float_repr_corpus_matches_cpython(tmp_path):
    ours, theirs = run_both(tmp_path, corpus_program(), "corpus")
    assert len(text(theirs.stdout).splitlines()) > 300
    assert_same(ours, theirs)


# --- uncaught exceptions ---------------------------------------------------------------------------

RAISING = {
    "float_div_zero": """
        def div(a: float, b: float) -> float:
            return a / b

        if __name__ == "__main__":
            print("before", div(1.0, 2.0))
            print(div(1.0, 0.0))
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
    "raise_in_called_function": """
        def inner(a: float) -> float:
            return 1.0 / a

        def outer(a: float) -> float:
            return inner(a) + inner(a - 1.0)

        if __name__ == "__main__":
            print(outer(2.0))
            print(outer(1.0))
            print("after")
        """,
}


@pytest.mark.parametrize("name", sorted(RAISING))
def test_uncaught_exception_matches_cpython(tmp_path, name):
    ours, theirs = run_both(tmp_path, RAISING[name], name)
    assert text(theirs.stdout).startswith("before") or name == "raise_in_called_function"
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
    c = aot.generate(write(tmp_path, PROGRAMS["math_functions"]))
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


def test_emit_c_and_exit_status_through_the_cli(tmp_path, capsys):
    need_cc()
    src = write(tmp_path, 'if __name__ == "__main__":\n    print("cli")\n')
    out = tmp_path / "cli_out"
    c_file = tmp_path / "cli.c"
    assert main(["aot-build", str(src), "-o", str(out), "--emit-c", str(c_file)]) == 0
    assert "tpa_print_str" in c_file.read_text()
    exe = out.with_name(out.name + ".exe") if sys.platform == "win32" else out
    run = subprocess.run([str(exe)], capture_output=True, timeout=60)
    assert (text(run.stdout), run.returncode) == ("cli\n", 0)


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
