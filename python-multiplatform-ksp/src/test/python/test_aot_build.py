"""AOT build step (SPEC N-17, #448, #451): `python -m typedpython aot-build`.

A single-module program with an `if __name__ == "__main__":` entry (scalar functions, native lists,
fixed tuples of scalars, f-strings in `print`, `len(sys.argv)`) is compiled to an executable
that contains no CPython. The differential tests run the executable beside the same file under
`sys.executable` (no `@compiled`, plain CPython): standard output and the exit status must be equal, and
for an uncaught exception the last line of standard error too. They need a C compiler (skipped without
one) and CPython 3.14 or later (the messages of 3.14 and 3.15). The rejection table and the generated-C
text checks need no compiler (they need Pyrefly, like test_aot_check.py).

Executables built here (each one runs the type inferencer and the C compiler, which is what made the
windows job slow): SIX in all, plus two for the measurement. The merged program (through the command
line), the merged big-integer program (#462; since #465 it also holds the cases of lists of big ints), the exception
selector program (one executable, the case chosen by `len(sys.argv)`, run once per case with that many dummy
arguments), spectral_norm.py as it is, nbody.py as it is and fannkuch.py as it is (each run with several arguments,
invalid ones included; the variant of nbody with its size fixed in the source, which was built before #462, is now only
generated and read as text), and the two size programs of the measurement (`test_executable_sizes`). #465 added one
(fannkuch). #462 added three (the big-integer program, spectral and the original nbody) and removed one (the nbody
variant's build). #451's first half built 12 (the exceptions were 9 programs); before it, 22.
One more program is built by test_aot_repr.py (the harness of the repr table).

Under the CI sanitizer job (TP_SANITIZE=1) every executable is built with ASan and UBSan
(`aot/cc.py`), and `exe_env` runs it with the leak checker on.

Everything here is an agent proposal, not owner-confirmed.
"""
import io
import math
import os
import random
import re
import shutil
import struct
import subprocess
import sys
import textwrap
import tokenize
from pathlib import Path
from types import SimpleNamespace

import pytest

from typedpython import aot, cbuild
from typedpython.__main__ import main
from typedpython.aot import cc
from aot_text_check import code_only, cpython_api_uses

HEADER = cc.RUNTIME_DIR / "tp_aot.h"
BENCH = Path(__file__).resolve().parents[4] / "benchmarks" / "typedpython" / "py"


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


def exe_env():
    """The environment an AOT executable runs in. Under the sanitizer job (TP_SANITIZE=1) it was built with
    ASan and UBSan, so it runs with the leak checker on (the job's own ASAN_OPTIONS turn it off for the
    interpreter, which leaks at exit) and without the ASan preload that job sets for the interpreter."""
    env = dict(os.environ)
    if env.get("TP_SANITIZE") == "1":
        env.pop("LD_PRELOAD", None)
        leaks = "1" if sys.platform.startswith("linux") else "0"      # LeakSanitizer: Linux only
        env["ASAN_OPTIONS"] = f"detect_leaks={leaks}:abort_on_error=0:halt_on_error=1"
        env["UBSAN_OPTIONS"] = "halt_on_error=1:print_stacktrace=1"
    return env


def run_exe(exe, args=(), timeout=60):
    return subprocess.run([str(exe), *args], capture_output=True, timeout=timeout, env=exe_env())


def run_py(src, args=(), timeout=60):
    return subprocess.run([sys.executable, str(src), *args], capture_output=True, timeout=timeout)


def exe_path(out: Path) -> Path:
    return out.with_name(out.name + ".exe") if sys.platform == "win32" else out


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
    "lists_creation": """
        def sum_f(a: list[float]) -> float:
            s = 0.0
            for i in range(len(a)):
                s += a[i]
            return s

        def show_f(a: list[float]) -> None:
            for i in range(len(a)):
                print(i, a[i])

        def show_i(a: list[int]) -> None:
            for i in range(len(a)):
                print(i, a[i])

        def case_lists_creation() -> None:
            a = [0.0, 4.8, 1.5 * 2.0]
            n = 4
            b = [1.25] * n
            c = n * [0.5]
            d = list(range(n))
            e = [7] * 3
            f = [3, 1, 4, 1, 5]
            g = a[:]
            h = f[:]
            z = [0.5] * 0
            w = [1] * -2
            g[0] = 99.0
            h[1] = 77
            print(a[0], g[0], len(a), len(g), len(z), len(w))
            print(h[1], f[1])
            show_f(a)
            show_f(b)
            show_f(c)
            show_i(d)
            show_i(e)
            show_f(g)
            show_i(h)
            print(sum_f(a), sum_f(b), sum_f(c), sum_f(z))
            x = 2.5
            m = [x, x * 2.0, -x, 1.0 / 3.0]
            show_f(m)
            k = 3
            p = [k, k, 5]
            show_i(p)
        """,
    "lists_aliasing": """
        def poke(x: list[float], y: list[float]) -> None:
            x[0] = 11.0
            y[1] = x[0] * 2.0
            x[1] += 1.0

        def head(a: list[float]) -> float:
            return a[0]

        def case_lists_aliasing() -> None:
            a = [1.0, 2.0, 3.0]
            b = a
            b[0] = 5.0
            print(a[0], b[0])
            c = a[:]
            c[0] = 6.0
            print(a[0], c[0], head(a), head(b), head(c))
            poke(a, a)
            print(a[0], a[1], b[0], b[1])
            d = [0.0, 0.0]
            poke(a, d)
            print(a[0], a[1], d[0], d[1])
            e = a
            a = [9.0, 8.0, 7.0]
            print(e[0], a[0], b[0])
            a = a[:]
            a[0] = 1.5
            print(a[0], e[0])
            b = c
            b[2] = -1.0
            print(c[2], e[2], a[2])
        """,
    "lists_negative_index": """
        def case_lists_negative_index() -> None:
            a = [10.0, 20.0, 30.0, 40.0]
            n = len(a)
            print(a[-1], a[-2], a[-4], a[-n], a[n - 1])
            a[-1] = 4.5
            a[-4] += 1.0
            a[n - 2] -= 2.0
            print(a[0], a[1], a[2], a[3])
            i = -3
            print(a[i])
            a[i] = 0.25
            print(a[1])
            b = [3, 2, 1]
            print(b[-1], b[-3])
        """,
    "lists_returned": """
        def make_f(n: int) -> list[float]:
            return [0.5] * n

        def make_i() -> list[int]:
            return [4, 5, 6]

        def first(a: list[float], b: list[float], pick_a: bool) -> list[float]:
            if pick_a:
                return a
            return b

        def forward(n: int) -> list[float]:
            return make_f(n)

        def twice(a: list[float]) -> list[float]:
            b = a[:]
            b[0] = b[0] * 2.0
            return b

        def local_return() -> list[float]:
            a = [1.0, 2.0]
            b = a
            b[1] = 8.0
            return a

        def early(n: int) -> list[float]:
            a = [0.0] * 3
            if n > 0:
                b = [1.0] * 2
                return b
            a[0] = 3.0
            return a

        def discard(n: int) -> None:
            make_f(n)

        def case_lists_returned() -> None:
            x = make_f(3)
            y = make_i()
            z = first(x, x, True)
            z[0] = 2.0
            w = forward(2)
            v = twice(x)
            u = local_return()
            t = early(1)
            s = early(0)
            discard(4)
            q = first(w, x, False)
            print(x[0], len(y), y[2], z[0], len(w), v[0], x[0], u[1], t[0], len(t), s[0], len(s), q[2])
        """,
    "lists_bubble": """
        def bubble(a: list[int]) -> None:
            n = len(a)
            for i in range(n):
                for j in range(n - 1 - i):
                    if a[j] > a[j + 1]:
                        t = a[j]
                        a[j] = a[j + 1]
                        a[j + 1] = t

        def reverse(a: list[float]) -> None:
            lo = 0
            hi = len(a) - 1
            while lo < hi:
                t = a[lo]
                a[lo] = a[hi]
                a[hi] = t
                lo += 1
                hi -= 1

        def case_lists_bubble() -> None:
            a = [5, 2, 9, -1, 7, 7, 0]
            bubble(a)
            for i in range(len(a)):
                print(a[i])
            f = [1.5, 2.5, 3.5, 4.5, 5.5]
            reverse(f)
            for i in range(len(f)):
                print(f[i])
        """,
    "lists_churn": """
        def churn(n: int) -> float:
            total = 0.0
            for i in range(n):
                a = [1.5] * 10
                b = a[:]
                b[i % 10] = 2.5
                a = b
                total += a[0] + a[i % 10]
            return total

        def case_lists_churn() -> None:
            print(churn(25), churn(0))
        """,
    "tuples": """
        def divmod_f(a: float, b: float) -> tuple[float, float]:
            return a // b, a % b

        def span(x: float) -> tuple[int, int, bool]:
            if x > 0.0:
                return 1, 2, True
            return -1, 0, False

        def case_tuples() -> None:
            q, r = divmod_f(7.5, 2.0)
            print(q, r)
            lo, hi, ok = span(2.0)
            print(lo, hi, ok)
            lo, hi, ok = span(-2.0)
            print(lo, hi, ok)
            q, r = divmod_f(-7.5, 2.0)
            print(q, r)
        """,
    "fstrings": """
        def case_fstrings() -> None:
            a = 0.5
            b = 1.5
            c = 2.5
            d = 0.125
            n = 7
            t = True
            print(f"{a:.0f} {b:.0f} {c:.0f} {d:.0f}")
            print(f"{a:.2f} {b:.2f} {c:.2f} {d:.2f}")
            print(f"{d:.3f} {d:.1f} {d:.17f}")
            print(f"{a} {n} {t} {d}")
            print(f"plain text")
            print(f"")
            print("x", f"{a}", 5, f"{n}:{n * 2}")
            print(f"{a:.9f}{b:.0f}")
            inf = 1e308 * 10
            nan = inf - inf
            print(f"{inf:.2f} {-inf:.0f} {nan:.3f} {-nan:.1f} {inf} {nan}")
            zero = -0.0
            print(f"{zero:.9f} {zero:.0f} {zero} {-1e-7:.3f} {1e-7:.9f}")
            print(f"{1e22:.2f} {123456.789:.1f} {2.675:.2f} {0.3:.20f} {1.0 / 3.0:.17f}")
            print(f"{1e300:.2f}")
            print(f"{1.7976931348623157e308:.2f} {5e-324:.20f} {0.03125:.4f} {0.09375:.4f}")
            print(f"{a * 2.0:.3f} {n < 3} {n * 2}")
            l = [1.5, 2.25]
            print(f"{l[0]:.1f} {l[1]:.1f} {len(l)}")
        """,
    "conditional": """
        def sign(x: float) -> float:
            return 1.0 if x > 0.0 else (-1.0 if x < 0.0 else 0.0)

        def safe_div(a: float, b: float) -> float:
            return a / b if b != 0.0 else 0.0

        def pick(a: list[float], i: int) -> float:
            return a[i] if i < len(a) else -1.0

        def case_conditional() -> None:
            print(sign(2.5), sign(-2.5), sign(0.0), sign(-0.0))
            print(safe_div(1.0, 4.0), safe_div(1.0, 0.0))
            a = [1.0, 2.0]
            print(pick(a, 0), pick(a, 1), pick(a, 2))
            n = 5
            m = 3 if n > 4 else 4
            flag = True if n == 5 else False
            print(m, flag, 1 if n < 0 else 2)
        """,
    "minmax_abs": """
        def case_minmax_abs() -> None:
            inf = 1e308 * 10
            nan = inf - inf
            print(abs(-2.5), abs(2.5), abs(-0.0), abs(0.0), abs(-inf), abs(nan))
            print(min(1.0, 2.0), min(2.0, 1.0), max(1.0, 2.0), max(2.0, 1.0))
            print(min(0.0, -0.0), min(-0.0, 0.0), max(0.0, -0.0), max(-0.0, 0.0))
            print(min(nan, 1.0), min(1.0, nan), max(nan, 1.0), max(1.0, nan))
            print(min(inf, -inf), max(inf, -inf))
            print(min(3, 9), max(3, 9), min(-4, -9), max(-4, -9), min(5, 5), max(5, 5))
            v = 17
            print(abs(v), min(v, 3), max(v, 30))
            w = -12
            print(abs(w))
            x = 2.5
            print(min(x, x * 2.0), max(x * 0.5, x))
        """,
    "argv_len": """
        def case_argv_len() -> None:
            print(len(sys.argv))
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
    parts = ["import math\nimport sys\n"]
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
    exe = exe_path(out)
    ours = run_exe(exe, timeout=120) if status == 0 else None
    theirs = run_py(src, timeout=120)
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


# --- exact float formatting harness (#450) ----------------------------------------------------------------

FLOAT_HARNESS = r'''
#include "tp_aot.h"

int main(void)
{
    char line[256];
    char buf[512];
    while (fgets(line, sizeof line, stdin) != NULL) {
        if (line[0] == 'R') {
            uint64_t bits = (uint64_t)strtoull(line + 2, NULL, 16);
            double x;
            int n;
            memcpy(&x, &bits, sizeof x);
            n = tpa_repr_f64(x, buf);
            if (n < 0 || buf[n] != '\0' || (size_t)n != strlen(buf)) {
                return 2;
            }
            fwrite(buf, 1, (size_t)n, stdout);
            putchar('\n');
        }
        else if (line[0] == 'F' || line[0] == 'P') {
            char *end;
            int prec = (int)strtol(line + 2, &end, 10);
            uint64_t bits = (uint64_t)strtoull(end, NULL, 16);
            double x;
            memcpy(&x, &bits, sizeof x);
            if (line[0] == 'F') {
                int n = tpa_format_f64_fixed(x, prec, buf);
                if (n < 0 || buf[n] != '\0' || (size_t)n != strlen(buf)) {
                    return 3;
                }
                fwrite(buf, 1, (size_t)n, stdout);
                putchar('\n');
            }
            else {
                tpa_print_f64_fixed(x, prec);
                putchar('\n');
            }
        }
    }
    fflush(stdout);
    return 0;
}
'''


@pytest.fixture(scope="module")
def float_harness(tmp_path_factory):
    need_cc()
    tmp = tmp_path_factory.mktemp("float_harness")
    return cc.compile_executable(FLOAT_HARNESS, tmp / "float_harness")


def _f64_hex(x: float) -> str:
    return f"{struct.unpack('>Q', struct.pack('>d', x))[0]:016x}"


def _run_float_harness(exe, lines: list[str]) -> list[str]:
    run = subprocess.run([str(exe)], input=("\n".join(lines) + "\n").encode("ascii"),
                         capture_output=True, env=exe_env(), timeout=120)
    assert run.returncode == 0, text(run.stderr)[-2000:]
    out = text(run.stdout).split("\n")
    assert out.pop() == ""
    assert len(out) == len(lines), (len(out), len(lines))
    return out


def test_float_repr_differential(float_harness):
    """#450: tpa_repr_f64 matches CPython repr(float) across special values, powers of 2 and 10
    (with neighbours), known tie cases, and 20,000 random bit patterns."""
    vals = [
        0.0, -0.0, float("inf"), float("-inf"), float("nan"), -float("nan"),
        struct.unpack(">d", struct.pack(">Q", 0x7FF8000000000001))[0],
        struct.unpack(">d", struct.pack(">Q", 0xFFF8000000000001))[0],
        struct.unpack(">d", struct.pack(">Q", 0x7FF0000000000001))[0],
        0.1 + 0.2, 1.0 / 3.0, 2.0 / 3.0, 4.35, 2.675, 1e23, 1e22, 1e21, 5e-324,
        2.2250738585072014e-308, 2.225073858507201e-308, 1.7976931348623157e308,
        9007199254740992.0, 9007199254740993.0, 4503599627370496.5,
    ]
    for bexp in range(0, 2047):
        for mant in (0, 1, 2, (1 << 52) - 2, (1 << 52) - 1):
            x = struct.unpack(">d", struct.pack(">Q", (bexp << 52) | mant))[0]
            vals.append(x)
            vals.append(-x)
    for e in range(-324, 309):
        for pfx in ("1", "9.999999999999999", "9.999999999999998", "5", "2.5", "1.25", "1.5"):
            try:
                x = float(f"{pfx}e{e}")
                vals.append(x)
                vals.append(math.nextafter(x, 0.0))
                vals.append(math.nextafter(x, math.inf))
            except OverflowError:
                pass
    rng = random.Random(20261119)
    for _ in range(20000):
        vals.append(struct.unpack(">d", struct.pack(">Q", rng.getrandbits(64)))[0])

    got = _run_float_harness(float_harness, [f"R {_f64_hex(x)}" for x in vals])
    bad = [(x, g, repr(x)) for x, g in zip(vals, got) if g != repr(x)]
    assert not bad, f"{len(bad)} of {len(vals)} differ, first: {bad[0]!r}"


def test_float_fixed_differential(float_harness):
    """#450: tpa_format_f64_fixed and tpa_print_f64_fixed match CPython format(x, '.Nf') across
    special values, dyadic halfways for prec 0..20, extreme exponents, and random floats."""
    vals = [
        0.0, -0.0, float("inf"), float("-inf"), float("nan"), -float("nan"),
        0.5, 1.5, 2.5, 3.5, 4.5, 0.25, 0.75, 0.125, 0.375, 0.625, 0.875,
        2.675, 4.35, 0.3, 1.0 / 3.0, 0.03125, 0.09375, 1e22, 1e300,
        1.7976931348623157e308, 5e-324, 2.2250738585072014e-308, 2.225073858507201e-308,
        9007199254740992.0, 9007199254740993.0, 4503599627370496.5,
    ]
    for p in range(21):
        for m_num in (1, 2, 3, 4, 5, 7, 9, 15, (1 << 52) + 1, (1 << 52) + 3, (1 << 53) - 1):
            vals.append(math.ldexp(float(m_num), -p - 1))
            vals.append(math.ldexp(float(m_num), -p - 2))
            vals.append(math.ldexp(float(m_num), -p))
            vals.append(-math.ldexp(float(m_num), -p - 1))
    rng = random.Random(20261120)
    for _ in range(500):
        vals.append(struct.unpack(">d", struct.pack(">Q", rng.getrandbits(64)))[0])

    pairs = [(x, p) for x in vals for p in (0, 1, 2, 3, 6, 15, 17, 20)]
    for x in vals[:100]:
        for p in range(21):
            pairs.append((x, p))

    got = _run_float_harness(float_harness, [f"F {p} {_f64_hex(x)}" for x, p in pairs])
    bad = [(x, p, g, f"{x:.{p}f}") for (x, p), g in zip(pairs, got) if g != f"{x:.{p}f}"]
    assert not bad, f"{len(bad)} of {len(pairs)} differ, first: {bad[0]!r}"

    # Also exercise tpa_print_f64_fixed directly
    print_pairs = [(x, p) for x in vals[:32] for p in (0, 1, 2, 3, 17, 20)]
    got_p = _run_float_harness(float_harness, [f"P {p} {_f64_hex(x)}" for x, p in print_pairs])
    bad_p = [(x, p, g, f"{x:.{p}f}") for (x, p), g in zip(print_pairs, got_p) if g != f"{x:.{p}f}"]
    assert not bad_p, f"{len(bad_p)} of {len(print_pairs)} differ in print, first: {bad_p[0]!r}"


# --- big integers through compiled code: one program, one build (#462) ----------------------------------------
#
# The same arrangement as CASES, for ints that are not proved inside 64 bits (design decision D1 (a), leader
# 2026-11-16, not owner-confirmed): growth past 64 and 128 bits and back into the small range, floor division and
# modulo with every sign, comparisons with each other and with floats (exact), true division, `float()`, mixed
# arithmetic, `abs` / `min` / `max`, truthiness, range loops (a bound read from the arguments, a loop that leaves
# early from a range of 10**30 values), printing at the digit-limit boundary, a proof that is not enough (aot/rep.py "The
# sound re-proof"; `guards_on_big_values` has one per way a guard on a big value reaches a proof), and the programs of
# the old rejection table that are accepted now. The executable runs with
# the argument 7 and the same file runs under CPython; the outputs must be equal case by case.

BIGINT_PRELUDE = """
def pw(b: int, e: int) -> int:
    r = 1
    for _ in range(e):
        r = r * b
    return r
"""

BIGINT_CASES = {
    "growth": """
        def fact(n: int) -> int:
            r = 1
            for i in range(1, n + 1):
                r *= i
            return r

        def case_growth() -> None:
            print(fact(20), fact(21), fact(25), fact(34), fact(35), fact(60))
            print(pw(2, 62), pw(2, 63), pw(2, 64), pw(2, 65), pw(2, 127), pw(2, 128))
            print(pw(-2, 63), pw(-2, 64), pw(-3, 41), pw(7, 77))
            p = pw(2, 62)
            print(p - 1, p, p + 1, -p, -p - 1, -p + 1, p + p, p - p - p)
            print(fact(30) - fact(30) + 5, fact(25) - fact(25) // 7 * 7, pw(10, 30) - pw(10, 30) + 1)
            big = pw(3, 80)
            small = big - big + 12
            print(small, small * small, big // big)
        """,
    "division": """
        def show(a: int, b: int) -> None:
            print(a // b, a % b)

        def case_division() -> None:
            big = pw(10, 30)
            huge = pw(7, 60)
            for sa in range(2):
                for sb in range(2):
                    a = big if sa == 0 else -big
                    b = huge if sb == 0 else -huge
                    show(a, b)
                    show(b, a)
                    show(a, 7 if sb == 0 else -7)
                    show(7 if sa == 0 else -7, b)
                    show(a * huge, b)
                    show(a, 3 if sb == 0 else -3)
            show(pw(2, 62), pw(2, 62))
            show(-pw(2, 62), -1)
            show(-pw(2, 62), 1)
            show(pw(2, 63), -pw(2, 62))
            show(-pw(2, 63) - 1, 2)
            show(0, big)
            show(big * big + 1, big)
            show(big * big - 1, -big)
        """,
    "float_mixing": """
        def cmps(a: int, x: float) -> None:
            print(a < x, a <= x, a == x, a != x, a > x, a >= x, x < a, x == a)

        def case_float_mixing() -> None:
            big = pw(2, 80)
            inf = 1e308 * 10.0
            nan = inf - inf
            cmps(big, 1e24)
            cmps(big, 1208925819614629174706176.0)
            cmps(big, 1208925819614629174706177.0)
            cmps(-big, -1208925819614629174706176.0)
            cmps(big, inf)
            cmps(big, -inf)
            cmps(big, nan)
            cmps(pw(2, 53) + 1, 9007199254740992.0)
            cmps(pw(2, 62) + 5, 4611686018427387904.0)
            cmps(pw(10, 400), 1e308)
            cmps(-pw(10, 400), -1e308)
            cmps(big - big, -0.0)
            a = pw(3, 50)
            b = pw(3, 51)
            print(a < b, a <= b, a == b, a != b, a > b, a >= b, a == a, b != b)
            print(float(big), float(-big), float(pw(10, 22)), float(pw(2, 53) + 1), float(pw(2, 54) + 3))
            print(float(pw(10, 300)), float(pw(2, 1023)))
            print(big / 3, 3 / big, big / big, big / -pw(2, 79), pw(10, 400) / pw(10, 399), -pw(10, 400) / pw(10, 100))
            print(pw(2, 62) / 7, 1 / pw(2, 62), (pw(2, 63) + 1) / 3)
            x = 2.5
            print(big + x, x + big, big - x, x - big, big * x, x * big, x / big, big / x)
            print(big + 0.5 == big, 1e300 * big, -big * 0.5)
            print(float(pw(2, 63)) == pw(2, 63), pw(2, 63) == float(pw(2, 63)))
        """,
    "abs_min_max_truth": """
        def digits(n: int) -> int:
            n = abs(n)
            d = 0
            while n:
                n = n // 10
                d += 1
            return d

        def flags(n: int) -> None:
            if n:
                print("true")
            else:
                print("false")
            print(not n, n != 0)

        def case_abs_min_max_truth() -> None:
            big = pw(10, 25)
            nb = -big
            z = big - big
            q = pw(2, 62)
            nq = -q
            r = pw(2, 63)
            nr = -r
            print(abs(big), abs(nb), abs(z), abs(nq), abs(nr), abs(r))
            big1 = big + 1
            five = 5
            nfive = -5
            print(min(big, nb), max(big, nb), min(big, big1), max(big, big1), min(big, five), max(nb, nfive))
            a70 = pw(2, 70)
            a71 = pw(2, 71)
            print(min(a70, a71), max(a70, a71), min(five, 7), max(five, 7))
            p3 = -pw(3, 70)
            print(digits(big), digits(a71), digits(p3), digits(0), digits(7))
            flags(big)
            flags(z)
            flags(nb)
            flags(0)
            flags(q - q + 1)
        """,
    "ranges": """
        import sys

        def first(n: int, how_many: int) -> int:
            t = 0
            c = 0
            for i in range(n):
                t = t + i
                c += 1
                if c == how_many:
                    break
            return t

        def case_ranges() -> None:
            n = int(sys.argv[1])
            total = 0
            for i in range(n):
                total += i * i
            print(n, total)
            for i in range(n - 3, n + 3):
                print(i)
            print(first(pw(10, 30), 5), first(pw(10, 30), 1))
            for i in range(pw(10, 30), pw(10, 30) + 3):
                print(i)
            for i in range(-pw(10, 25), -pw(10, 25) - 3, -1):
                print(i)
            for i in range(pw(2, 62) - 2, pw(2, 62) + 2):
                print(i)
            for i in range(pw(2, 63) - 2, 9223372036854775807):
                print(i)
            for i in range(0, -pw(10, 30), -5):
                print(i)
                if i < -12:
                    break
            for i in range(pw(10, 30), 0):
                print("never")
            s = 0
            for i in range(pw(2, 64), pw(2, 64) + 2000, 7):
                s += i
                continue
            print(s)
        """,
    "print_digits": """
        def case_print_digits() -> None:
            a = pw(10, 4299)
            print(a)
            print(-a)
            big = pw(2, 100)
            print(f"{big} and {big * big} then {-big}", big)
            print(f"{a - a + 7}|{big - big}|", a - a)
            print(f"{pw(10, 18)} {pw(10, 19)} {pw(2, 62) - 1} {pw(2, 62)} {-pw(2, 62)} {-pw(2, 62) - 1}")
        """,
    "tuples_and_lists": """
        def both(a: int) -> tuple[int, int]:
            return a * a, a

        def total(w: list[int]) -> int:
            t = 0
            for i in range(len(w)):
                t += w[i]
            return t

        def pick(v: list[float], k: int) -> float:
            return v[k]

        def put(v: list[float], k: int, x: float) -> None:
            v[k] = x

        def case_tuples_and_lists() -> None:
            big = pw(10, 20)
            x, y = both(big)
            print(x, y, x - x * 0 + y)
            w = [9223372036854775807, 9223372036854775807, -9223372036854775807]
            print(total(w), total(w) * 2, total(w) - w[0] - w[1] - w[2])
            v = [1.5, 2.5, 3.5]
            k = big - big + 2
            print(pick(v, k), pick(v, -k), pick(v, k - 2), pick(v, big - big - 3))
            put(v, k, 9.5)
            put(v, -big + big - 3, 0.5)
            print(v[0], v[1], v[2])
            w2 = [0] * (big - big + 3)
            w3 = list(range(big - big + 4))
            w4 = [1.5] * (-big + big - 2)
            print(len(w2), len(w3), len(w4), w3[big - big + 3])
        """,
    "proof_with_a_big_operand": """
        def step(w: list[int]) -> int:
            lo = w[0]
            hi = lo * lo
            if lo < hi:
                lo = lo + 1
            return lo

        def up_to(w: list[int]) -> int:
            lo = w[0]
            hi = w[1] * w[1]
            n = 0
            while lo < hi and n < 3:
                lo += 1
                n += 1
            return lo

        def case_proof_with_a_big_operand() -> None:
            w = [9223372036854775807]
            print(step(w))
            w2 = [9223372036854775806, 9223372036854775807]
            print(up_to(w2))
        """,
    # Each function below holds an operation that the default-mode rules prove from a guard on (or a value derived
    # from) a big int `t`, and the arguments make that operation leave 64 bits. Were it RAW (a proved flag trusted
    # without the sound re-proof, aot/rep.py), it would wrap: each 9223372036854775808 would print as
    # -9223372036854775808 and each -9223372036854775809 as 9223372036854775807. (Agent proposal, not owner-confirmed.)
    "guards_on_big_values": """
        def gd_lt(w: list[int]) -> int:
            a = w[0]
            t = w[1] * w[2]
            if a < t:
                a = a + 1
            return a

        def gd_gt_swapped(w: list[int]) -> int:
            a = w[0]
            t = w[1] * w[2]
            if t > a:
                a = a + 1
            return a

        def gd_le(w: list[int]) -> int:
            a = w[0]
            u = w[1] * w[2] - 1
            if a <= u:
                a = a + 1
            return a

        def gd_gt(w: list[int]) -> int:
            a = w[0]
            t = w[1] * w[2]
            if a > t:
                a = a - 1
            return a

        def gd_ge(w: list[int]) -> int:
            a = w[0]
            u = w[1] * w[2] + 1
            if a >= u:
                a = a - 1
            return a

        def gd_eq(w: list[int]) -> int:
            a = w[0]
            t = w[1] * w[2]
            if t > 0:
                s = 9223372036854775807 - t
                if a == s:
                    a = a - 1
            return a

        def gd_returning(w: list[int]) -> int:
            a = w[0]
            t = w[1] * w[2]
            if a >= t:
                return 0
            return a + 1

        def gd_not(w: list[int]) -> int:
            a = w[0]
            t = w[1] * w[2]
            if not (a >= t):
                a = a + 1
            return a

        def gd_and(w: list[int]) -> int:
            a = w[0]
            t = w[1] * w[2]
            if a < t and a > -5:
                a = a + 1
            return a

        def gd_or(w: list[int]) -> int:
            a = w[0]
            t = w[1] * w[2]
            if a >= t or a < -5:
                return 0
            return a + 1

        def gd_while(w: list[int]) -> int:
            a = w[0]
            t = w[1] * w[2]
            n = 0
            while a < t and n < 1:
                a = a + 1
                n = n + 1
            return a

        def gd_cond(w: list[int]) -> int:
            a = w[0]
            t = w[1] * w[2]
            return a + 1 if a < t else 0

        def gd_copy(w: list[int]) -> int:
            a = w[0]
            t = w[1] * w[2]
            if a < t:
                c = a
                return c + 1
            return 0

        def gd_second(w: list[int]) -> int:
            a = w[0]
            c = w[3]
            t = w[1] * w[2]
            if a < t:
                if c <= a:
                    return c + 1
            return 0

        def gd_join(w: list[int]) -> int:
            a = w[0]
            t = w[1] * w[2]
            if a < t:
                b = a
            else:
                b = 0
            return b + 1

        def gd_inner(a: int, t: int) -> int:
            if a < t:
                return a + 1
            return 0

        def gd_param(w: list[int]) -> int:
            return gd_inner(w[0], w[1] * w[2])

        def gd_big(w: list[int]) -> int:
            return w[1] * w[2]

        def gd_call(w: list[int]) -> int:
            a = w[0]
            t = gd_big(w)
            if a < t:
                return a + 1
            return 0

        def gd_pair(w: list[int]) -> tuple[int, int]:
            return w[1] * w[2], 0

        def gd_unpack(w: list[int]) -> int:
            a = w[0]
            t, z = gd_pair(w)
            if a < t:
                return a + 1
            return z

        def gd_loop_var(w: list[int]) -> int:
            t = w[2] * w[3]
            r = 0
            for i in range(w[0], w[1], -1):
                if i < t:
                    r = i + 1
                    break
            return r

        def gd_guarded_bound(w: list[int]) -> int:
            a = w[0]
            t = w[1] * w[2]
            r = 0
            if a < t:
                for i in range(a, 0, -1):
                    r = i + 1
                    break
            return r

        def gd_cond_other(w: list[int]) -> int:
            a = w[0]
            t = w[1] * w[2]
            if a < (t if a > 0 else 5):
                return a + 1
            return 0

        def gd_abs(w: list[int]) -> int:
            a = w[0]
            t = w[1] * w[2]
            if a > t:
                return abs(a)
            return 0

        def case_guards_on_big_values() -> None:
            hi = [9223372036854775807, 1099511627776, 1099511627776, 9223372036854775807]
            lo = [-9223372036854775807 - 1, -1099511627776, 1099511627776]
            eq = [-9223372036854775807 - 1, 4294967295, 4294967297]
            down = [9223372036854775807, 9223372036854775805, 1099511627776, 1099511627776]
            print(gd_lt(hi), gd_gt_swapped(hi), gd_le(hi), gd_gt(lo), gd_ge(lo), gd_eq(eq))
            print(gd_returning(hi), gd_not(hi), gd_and(hi), gd_or(hi), gd_while(hi), gd_cond(hi))
            print(gd_copy(hi), gd_second(hi), gd_join(hi), gd_param(hi), gd_call(hi), gd_unpack(hi))
            print(gd_loop_var(down), gd_guarded_bound(hi), gd_cond_other(hi), gd_abs(lo))
        """,
    "unproved_rows": """
        def counter(n: int) -> int:
            s = 0
            i = 0
            while i < n:
                s = s + i
                i = i + 1
            return s

        def summed(n: int) -> int:
            s = 0
            for i in range(n):
                s += i
            return s

        def neg(a: int) -> int:
            return -a

        def fdiv(a: int, b: int) -> int:
            return a // b

        def tdiv(a: int, b: int) -> float:
            return a / b

        def elem(a: list[int]) -> int:
            return a[0] + 1

        def absolute(a: int) -> int:
            return abs(a)

        def case_unproved_rows() -> None:
            print(counter(10), summed(100), neg(5), neg(-9223372036854775807 - 1), fdiv(7, 2), fdiv(-9223372036854775807 - 1, -1))
            print(tdiv(1, 3), tdiv(9007199254740993, 3), tdiv(-9223372036854775807 - 1, 7), tdiv(5, -9223372036854775807))
            w = [9223372036854775807, 1]
            print(elem(w), absolute(-9223372036854775807 - 1), absolute(-5), absolute(9223372036854775807))
        """,
    # lists of ints that hold big values (#465; static classification, leader decision 2026-11-16, not owner-confirmed): the
    # groups are in aot/rep.py "Lists of ints". Every case below names its functions with a prefix of its own, so no list
    # parameter is shared with another case (a parameter is one group with every argument it is passed).
    "list_store_and_creation": """
        def ls_repeat(n: int) -> list[int]:
            return [pw(2, 70)] * n

        def ls_display() -> list[int]:
            return [1, pw(3, 50), -5, pw(7, 30)]

        def ls_sum(a: list[int]) -> int:
            t = 0
            for i in range(len(a)):
                t += a[i]
            return t

        def case_list_store_and_creation() -> None:
            a = [0] * 5
            for i in range(5):
                a[i] = pw(10, 19 + i) + i
            print(a[0], a[1], a[2], a[3], a[4], len(a))
            b = ls_repeat(3)
            print(b[0], b[2], b[-1], len(b), ls_sum(b))
            b[1] = b[1] + 1
            print(b[0] == b[1], b[1] - b[0], b[2] == b[0])
            c = ls_display()
            print(c[0], c[1], c[2], c[3], ls_sum(c))
            print(ls_sum(a) - a[0] - a[1] - a[2] - a[3] - a[4])
            x = [pw(2, 64)] * 0
            print(len(x))
        """,
    "list_through_parameters_and_returns": """
        def lp_fill(a: list[int], v: int) -> None:
            a[0] = v
            a[1] = v * v

        def lp_read(a: list[int]) -> int:
            return a[1] + a[0]

        def lp_make(n: int) -> list[int]:
            r = [0] * n
            r[0] = pw(2, 66)
            return r

        def lp_pass(a: list[int]) -> list[int]:
            return a

        def lp_second(n: int) -> list[int]:
            a = lp_make(n)
            a[1] = a[0] + 1
            return a

        def case_list_through_parameters_and_returns() -> None:
            a = [1, 2, 3]
            lp_fill(a, pw(2, 40))
            print(a[0], a[1], a[2], lp_read(a))
            r = lp_make(3)
            print(r[0], r[1], r[2])
            s = lp_pass(r)
            s[2] = pw(5, 40)
            print(r[2], s[2], r[0] == s[0])
            t = lp_second(2)
            print(t[0], t[1])
            u = lp_pass(t)
            print(u[1] == t[1])
        """,
    "list_aliases_and_copies": """
        def case_list_aliases_and_copies() -> None:
            a = [1, 2, 3]
            b = a
            b[0] = pw(3, 60)
            print(a[0], b[0], a[1])
            a[1] = pw(2, 100)
            print(b[1], a[1])
            c = a[:]
            c[2] = pw(2, 200)
            print(a[2], c[2])
            a[0] = 5
            print(a[0], b[0], c[0])
            c[0] = c[0] + 1
            print(a[0], b[0], c[0], c[1] == a[1])
            r = [1, 2, 3]
            q = r[:]
            q[0] = pw(2, 70)
            print(r[0], q[0], r[1] + q[1])
            d = q[:]
            d[1] = d[0] * 2
            print(q[1], d[1], len(d))
        """,
    "list_reassigned": """
        def case_list_reassigned() -> None:
            a = [0] * 3
            print(a[0], a[1])
            a = [pw(2, 80), 1, 2]
            print(a[0], a[2])
            a[1] = a[0] * 2
            print(a[1])
            a = list(range(4))
            print(a[3], len(a))
            a[0] = pw(2, 99)
            print(a[0], a[1])
        """,
    "list_shared_by_two_functions": """
        def lt_even(a: list[int], n: int) -> int:
            if n <= 0:
                return a[0]
            a[0] = a[0] * 3
            return lt_odd(a, n - 1)

        def lt_odd(a: list[int], n: int) -> int:
            if n <= 0:
                return a[1]
            a[1] = a[1] + a[0]
            return lt_even(a, n - 1)

        def case_list_shared_by_two_functions() -> None:
            a = [5, 7]
            print(lt_even(a, 100), a[0], a[1])
            print(lt_odd(a, 3) == a[1])
        """,
    "list_values_go_big_and_small_again": """
        def case_list_values_go_big_and_small_again() -> None:
            a = [1] * 3
            a[0] = pw(2, 90)
            print(a[0])
            a[0] = a[0] - pw(2, 90) + 4
            print(a[0], a[0] + a[1])
            a[1] = pw(2, 62)
            a[2] = a[1] + a[1] - a[1]
            print(a[1], a[2], a[2] - a[1])
            a[1] = -pw(2, 62) - 1
            print(a[1], a[1] + 1 + pw(2, 62))
        """,
    "list_negative_indices_and_totals": """
        def case_list_negative_indices_and_totals() -> None:
            a = [pw(3, 45), pw(3, 46), pw(3, 47)]
            print(a[-1], a[-2], a[-3], len(a))
            a[-1] = a[-1] + 1
            t = 0
            for i in range(len(a)):
                t += a[i]
            print(t, a[2])
            n = 0
            for x in range(len(a)):
                n += a[-1 - x]
            print(n == t)
            a[-3] = a[-3] * a[-3]
            print(a[0])
        """,
    "list_elements_as_bounds_and_indices": """
        def case_list_elements_as_bounds_and_indices() -> None:
            a = [pw(2, 70), 3, 1]
            z = [0, 0, 0, 0, 0]
            b = [pw(2, 70) - pw(2, 70) + 2, 0, 1]
            print(z[b[0]], len(z))
            z[b[0]] = 9
            print(z[2], z[b[1]])
            k = 0
            for j in range(a[0]):
                k += j
                if j == a[1]:
                    break
            print(k)
            for j in range(a[1], a[2] + 2, 1):
                print(j)
            print(z[a[2]], z[-a[2]])
        """,
    "lists_that_stay_raw": """
        def case_lists_that_stay_raw() -> None:
            r = [0] * 4
            for i in range(4):
                r[i] = i * 3 + 1
            s = r[:]
            s[0] = r[3] % 7
            big = pw(2, 80)
            print(r[0] + big, s[0] * big, len(r), r[1] % 5, s[3])
        """,
}
BIGINT_ARGS = ["7"]


def bigint_source() -> str:
    parts = ["import math\nimport sys\n", textwrap.dedent(BIGINT_PRELUDE).strip("\n") + "\n"]
    parts += [textwrap.dedent(BIGINT_CASES[n]).replace("import sys\n\n", "", 1).strip("\n") + "\n" for n in BIGINT_CASES]
    entry = ['if __name__ == "__main__":']
    for n in BIGINT_CASES:
        entry.append(f'    print("{MARK}{n}")')
        entry.append(f"    case_{n}()")
    return "\n".join(parts) + "\n" + "\n".join(entry) + "\n"


@pytest.fixture(scope="module")
def bigint(tmp_path_factory):
    """The big-integer program: built once, run once with BIGINT_ARGS, the same file run under CPython."""
    need_cc()
    tmp = tmp_path_factory.mktemp("bigint")
    src = write(tmp, bigint_source(), "bigint")
    c_file = tmp / "bigint.c"
    exe = aot.build(src, tmp / "bigint", emit_c=c_file)
    ours, theirs = run_exe(exe, BIGINT_ARGS, timeout=120), run_py(src, BIGINT_ARGS, timeout=120)
    return SimpleNamespace(src=src, c_file=c_file, ours=ours, theirs=theirs,
                           ours_cases=split_cases(text(ours.stdout)), theirs_cases=split_cases(text(theirs.stdout)))


def test_the_big_integer_program_runs_and_matches_cpython(bigint):
    assert bigint.theirs.returncode == 0, text(bigint.theirs.stderr)
    assert bigint.ours.returncode == 0, text(bigint.ours.stderr)
    assert text(bigint.ours.stdout) == text(bigint.theirs.stdout)
    assert text(bigint.ours.stderr) == ""


@pytest.mark.parametrize("name", list(BIGINT_CASES))
def test_big_integer_case_matches_cpython(bigint, name):
    theirs = bigint.theirs_cases[name]
    assert theirs.strip(), "the case must print something"
    assert bigint.ours_cases.get(name) == theirs


def test_the_big_integer_cases_ran_in_order_and_cover_the_boundaries(bigint):
    assert [k for k in bigint.theirs_cases if k in BIGINT_CASES] == list(BIGINT_CASES)
    assert [k for k in bigint.ours_cases if k in BIGINT_CASES] == list(BIGINT_CASES)
    first = bigint.ours_cases["print_digits"].splitlines()[0]
    assert len(first) == 4300 and set(first[1:]) == {"0"}          # 10**4299: the last power of ten that prints
    assert "9223372036854775808" in bigint.ours_cases["growth"] and "18446744073709551616" in bigint.ours_cases["growth"]


def test_a_program_with_a_big_integer_includes_the_big_integer_header(bigint):
    c = bigint.c_file.read_text()
    assert '#include "tp_aot.h"\n#include "tp_aot_int.h"\n' in c
    assert "tpa_int_mul(" in c and "tpa_argv_int(" in c and "tpa_int_release(" in c


BIG_LIST_CALLS = ("tpa_int_list_fill(", "tpa_int_list_alloc(", "tpa_int_list_copy(", "tpa_int_list_from_i64(",
                  "tpa_int_list_iota(", "tpa_int_list_release(", "tpa_int_list_retain(")


def test_a_program_with_a_big_capable_list_uses_the_big_list_runtime_and_one_without_does_not(
        bigint, merged, nbody_variant_generated, generate_benchmark):
    """#465: the list calls of the new runtime appear where a list is big-capable (the big-integer program has one
    of each kind of creation, a retain for an alias and a release at every cleanup point) and nowhere else: not in the
    merged program, not in nbody, and not in spectral_norm, whose ints are big-integer-capable but whose lists are raw."""
    c = bigint.c_file.read_text()
    for call in BIG_LIST_CALLS[:-1]:
        assert call in c, call
    assert "tpa_int_list_retain(" in c and "tpa_int_set(&v_" in c and "tpa_int_list_load(" in c
    assert "tpa_int_list_store(" in c and "tpa_int_list_init(" in c, "element access of a big-capable list uses the helpers"
    for other in (merged.c_file.read_text(), nbody_variant_generated[1], generate_benchmark("spectral_norm.py"),
                  generate_benchmark("nbody.py")):
        assert "tpa_int_list" not in other
        assert not any(call in other for call in BIG_LIST_CALLS)


def test_a_list_that_only_holds_proved_values_stays_a_raw_list_next_to_big_capable_ones(bigint):
    """`case_lists_that_stay_raw` reads its lists into big-integer arithmetic and copies one, but never stores a big
    value: both are `tpa_i64_list`, and the function names nothing of the big list runtime. In `case_list_aliases_and_copies`
    the copy `q` of the raw list `r` is big-capable and `r` stays raw (the one-way rule)."""
    c = bigint.c_file.read_text()
    [body] = re.findall(r"^static int tpf_case_lists_that_stay_raw\([^)]*\)\n\{.*?^\}", c, flags=re.S | re.M)
    assert "tpa_i64_list *v_r" in body and "tpa_i64_list *v_s" in body and "tpa_i64_copy(" in body
    assert "tpa_int_list" not in body
    [body] = re.findall(r"^static int tpf_case_list_aliases_and_copies\([^)]*\)\n\{.*?^\}", c, flags=re.S | re.M)
    for name, kind in (("a", "int"), ("b", "int"), ("c", "int"), ("r", "i64"), ("q", "int"), ("d", "int")):
        assert re.search(rf"tpa_{kind}_list \*v_{name} TPA_UNUSED = NULL;", body), name
    assert "tpa_int_list_from_i64(v_r" in body and "tpa_int_list_copy(v_a" in body


def test_a_big_capable_list_is_created_stored_read_and_released_at_the_cleanup_point(tmp_path):
    c = aot.generate(write(tmp_path, """
        def f(n: int) -> int:
            a = [0] * 3
            a[1] = n * n
            return a[1] + a[-1]

        if __name__ == "__main__":
            print(f(4))
        """))
    [body] = re.findall(r"^static int tpf_f\([^)]*\)\n\{.*?^\}", c, flags=re.S | re.M)
    assert "tpa_int_list *v_a" in body
    assert "tpa_int_list_fill(" in body and body.count("tpa_int_list_store(v_a, ") == 1 and "v_a->d[" not in body
    assert "tpa_slot(" in body                                    # the bounds checks are the raw list's
    assert body.count("tp_cleanup:") == 1 and "return -1" not in body
    cleanup = body[body.index("tp_cleanup:"):]
    assert "tpa_int_list_release(v_a);" in cleanup
    assert "tpa_i64_list" not in body


def test_a_program_whose_ints_are_all_proved_has_no_big_integer_header(merged, nbody_variant_generated):
    """The merged program of CASES has no unproved int: its C does not include tp_aot_int.h, names nothing of
    it, and keeps the `main` it always had. So does the variant of nbody with its size fixed in the source; and
    strict mode generates exactly the same text for both (nothing in them is TAGGED)."""
    assert "tp_aot_int" not in merged.c_file.read_text() and "tpa_int" not in merged.c_file.read_text()
    variant, c = nbody_variant_generated
    assert "tp_aot_int" not in c and "tpa_int" not in c and "tpa_argv_int" not in c and "wmain" not in c
    assert "int main(int argc, char **argv)\n{\n    int rc;\n    tpa_argc = argc;\n    tpa_argv = argv;\n" in c
    assert aot.generate(variant, strict_int=True) == c


# --- uncaught exceptions ---------------------------------------------------------------------------
#
# An uncaught exception ends the process, so each case is its own RUN, but they are all one EXECUTABLE:
# the entry calls the case that `len(sys.argv)` selects (the harness gives it that many dummy arguments; the
# nine programs of #448 were nine builds). The same file runs under CPython with the same arguments, and
# standard output, the exit status and the last line of standard error must be equal. Every case is a
# different runtime path (the float division reaches its helper through two frames, which is also the
# propagation check; `list_oob_through_a_frame` leaves with lists alive in two frames, which the leak
# checker of the sanitizer job reads as the exception path releasing everything).

RAISING = {
    "float_div_zero_through_two_frames": ("""
        def inner(a: float) -> float:
            return 1.0 / a

        def outer(a: float) -> float:
            return inner(a) + inner(a - 1.0)

        def case_float_div_zero_through_two_frames() -> None:
            print("before", outer(2.0))
            print(outer(1.0))
            print("after")
        """, ()),
    "float_floordiv_zero": ("""
        def fd(a: float, b: float) -> float:
            return a // b

        def case_float_floordiv_zero() -> None:
            print("before", fd(7.0, 2.0))
            print(fd(1.0, -0.0))
            print("after")
        """, ()),
    "float_mod_zero": ("""
        def md(a: float, b: float) -> float:
            return a % b

        def case_float_mod_zero() -> None:
            print("before", md(7.0, 2.0))
            print(md(1.0, 0.0))
            print("after")
        """, ()),
    "int_floordiv_zero": ("""
        def fd_int(a: int) -> int:
            return a // 0

        def case_int_floordiv_zero() -> None:
            print("before")
            print(fd_int(5))
            print("after")
        """, ()),
    "int_mod_zero": ("""
        def md_int(a: int, b: int) -> int:
            return a % b

        def case_int_mod_zero() -> None:
            print("before", md_int(7, 3), md_int(-7, 3))
            print(md_int(5, 0))
            print("after")
        """, ()),
    "int_truediv_zero": ("""
        def td() -> float:
            return 1 / 0

        def case_int_truediv_zero() -> None:
            print("before")
            print(td())
        """, ()),
    "sqrt_negative": ("""
        def case_sqrt_negative() -> None:
            print("before", math.sqrt(4.0))
            x = -1.0
            print(math.sqrt(x))
            print("after")
        """, ()),
    "log_zero": ("""
        def log_of(x: float) -> float:
            return math.log(x)

        def case_log_zero() -> None:
            print("before", log_of(1.0))
            print(log_of(0.0))
            print("after")
        """, ()),
    "exp_overflow": ("""
        def exp_of(x: float) -> float:
            return math.exp(x)

        def case_exp_overflow() -> None:
            print("before", exp_of(1.0))
            print(exp_of(1000.0))
            print("after")
        """, ()),
    "list_read_out_of_range": ("""
        def case_list_read_out_of_range() -> None:
            a = [1.0, 2.0]
            print("before", a[1])
            print(a[2])
            print("after")
        """, ()),
    "list_read_negative_out_of_range": ("""
        def case_list_read_negative_out_of_range() -> None:
            a = [1, 2]
            print("before", a[-2])
            print(a[-3])
            print("after")
        """, ()),
    "list_store_out_of_range": ("""
        def case_list_store_out_of_range() -> None:
            a = [1.0, 2.0]
            a[1] = 3.0
            print("before", a[1])
            a[2] = 4.0
            print("after")
        """, ()),
    "list_store_negative_out_of_range": ("""
        def case_list_store_negative_out_of_range() -> None:
            a = [1.0, 2.0]
            a[-2] = 3.0
            print("before", a[0])
            a[-3] = 4.0
            print("after")
        """, ()),
    "list_augmented_store_out_of_range": ("""
        def case_list_augmented_store_out_of_range() -> None:
            a = [1.0, 2.0]
            a[0] += 3.0
            print("before", a[0])
            a[5] += 4.0
            print("after")
        """, ()),
    "list_oob_through_a_frame": ("""
        def inner_oob(a: list[float]) -> float:
            return a[10]

        def outer_oob() -> None:
            a = [1.0] * 3
            b = a[:]
            c = list(range(4))
            d = [2.0, 3.0]
            print("before", c[3], d[1])
            print(inner_oob(b))
            print("after")

        def case_list_oob_through_a_frame() -> None:
            keep = [0.5] * 2
            outer_oob()
            print(keep[0])
        """, ()),
}
# big integers (#462): every one is a different runtime path. A case that needs a particular first argument names it
# in the second element of its tuple (its index in SELECTOR_ORDER is its argument count, so it is never the first).
SELECTOR_PRELUDE = BIGINT_PRELUDE
RAISING.update({
    "argv_index_out_of_range": ("""
        def case_argv_index_out_of_range() -> None:
            print("before", len(sys.argv))
            print(int(sys.argv[5]))
            print("after")
        """, ()),
    "argv_negative_index_out_of_range": ("""
        def case_argv_negative_index_out_of_range() -> None:
            print("before", len(sys.argv))
            print(int(sys.argv[-100]))
            print("after")
        """, ()),
    "argv_index_too_large": ("""
        def case_argv_index_too_large() -> None:
            print("before")
            print(int(sys.argv[pw(10, 30)]))
            print("after")
        """, ()),
    "int_big_floordiv_zero": ("""
        def case_int_big_floordiv_zero() -> None:
            big = pw(10, 30)
            print("before", big // 7)
            z = big - big
            print(big // z)
            print("after")
        """, ()),
    "int_big_mod_zero": ("""
        def case_int_big_mod_zero() -> None:
            big = pw(10, 30)
            print("before", big % 7)
            z = big - big
            print(big % z)
            print("after")
        """, ()),
    "int_big_truediv_zero": ("""
        def case_int_big_truediv_zero() -> None:
            big = pw(10, 30)
            print("before", big / 7)
            z = big - big
            print(big / z)
            print("after")
        """, ()),
    "int_digit_limit_to_str": ("""
        def case_int_digit_limit_to_str() -> None:
            print("before", pw(10, 4299) // pw(10, 4290))
            print(pw(10, 4300))
            print("after")
        """, ()),
    "int_digit_limit_in_an_fstring": ("""
        def case_int_digit_limit_in_an_fstring() -> None:
            print("before")
            print(f"{1} {pw(10, 4300)}")
            print("after")
        """, ()),
    "int_digit_limit_second_argument": ("""
        def case_int_digit_limit_second_argument() -> None:
            print("before")
            print(1, pw(10, 4300))
            print("after")
        """, ()),
    "int_digit_limit_from_str": ("""
        def case_int_digit_limit_from_str() -> None:
            print("before")
            print(int(sys.argv[1]) + 1)
            print("after")
        """, ("1" * 4301,)),
    "int_float_overflow_conversion": ("""
        def case_int_float_overflow_conversion() -> None:
            print("before", float(pw(10, 300)))
            print(float(pw(10, 400)))
            print("after")
        """, ()),
    "int_float_overflow_mixed": ("""
        def case_int_float_overflow_mixed() -> None:
            print("before", 1.0 + pw(10, 300))
            print(1.0 + pw(10, 400))
            print("after")
        """, ()),
    "int_float_overflow_after_the_right_operand": ("""
        def noisy() -> float:
            print("the right operand ran")
            return 1.0

        def case_int_float_overflow_after_the_right_operand() -> None:
            print("before")
            big = pw(10, 400)
            print(big + noisy())
            print("after")
        """, ()),
    "int_float_overflow_truediv": ("""
        def case_int_float_overflow_truediv() -> None:
            big = pw(10, 400)
            print("before", big / pw(10, 399), big / pw(10, 100))
            print(big / 3)
            print("after")
        """, ()),
    "int_float_overflow_dividing_by_a_big_int": ("""
        def case_int_float_overflow_dividing_by_a_big_int() -> None:
            big = pw(10, 400)
            print("before", 0.0 * 5)
            print(1.0 / big)
            print("after")
        """, ()),
    "int_invalid_literal_non_ascii": ("""
        def case_int_invalid_literal_non_ascii() -> None:
            print("before")
            print(int(sys.argv[1]))
            print("after")
        """, ("5 \u00e9 \U0001f600\u200b",)),
    "int_invalid_literal_underscore": ("""
        def case_int_invalid_literal_underscore() -> None:
            print("before")
            print(int(sys.argv[1]))
            print("after")
        """, ("1__0",)),
    "list_index_too_large_read": ("""
        def case_list_index_too_large_read() -> None:
            a = [1.0, 2.0]
            print("before", a[1])
            print(a[pw(10, 30)])
            print("after")
        """, ()),
    "list_index_too_large_store": ("""
        def case_list_index_too_large_store() -> None:
            a = [1.0, 2.0]
            a[1] = 3.0
            print("before", a[1])
            a[pw(10, 30)] = 4.0
            print("after")
        """, ()),
    "list_negative_index_too_large": ("""
        def case_list_negative_index_too_large() -> None:
            a = [1.0, 2.0]
            print("before", a[-1])
            k = pw(10, 30)
            print(a[-k])
            print("after")
        """, ()),
    # A list created with a length the verifier reads as at least 5 (or 1), while it is empty: `9223372036854775807 - t`
    # is negative for the big t (3037000500 ** 2 is 145474193 more than 2**63 - 1, so the count -145474188 is in the
    # range where `[0] * n` is the empty list, not an OverflowError). The proved index must keep its check (aot/rep.py "The sound re-proof"); skipping it
    # reads or writes past the end of the list, printing a garbage value (or crashing under the sanitizer) and "after"
    # where CPython raises IndexError. (Agent proposal, not owner-confirmed.)
    "list_shorter_than_its_proof": ("""
        def short_load(w: list[int]) -> int:
            t = w[1] * w[2]
            n = 9223372036854775807 - t
            arr = [0] * (n + 5)
            return arr[4]

        def case_list_shorter_than_its_proof() -> None:
            w = [0, 3037000500, 3037000500]
            print("before")
            print(short_load(w))
            print("after")
        """, ()),
    "list_shorter_than_its_proof_store": ("""
        def short_store(w: list[int]) -> int:
            t = w[1] * w[2]
            n = 9223372036854775807 - t
            arr = [0] * (n + 5)
            arr[4] = 7
            return len(arr)

        def case_list_shorter_than_its_proof_store() -> None:
            w = [0, 3037000500, 3037000500]
            print("before")
            print(short_store(w))
            print("after")
        """, ()),
    "list_shorter_than_its_proof_copy": ("""
        def short_copy(w: list[int]) -> int:
            t = w[1] * w[2]
            n = 9223372036854775807 - t
            arr = [0] * (n + 5)
            b = arr[:]
            return b[4]

        def case_list_shorter_than_its_proof_copy() -> None:
            w = [0, 3037000500, 3037000500]
            print("before")
            print(short_copy(w))
            print("after")
        """, ()),
    "list_shorter_than_its_proof_length": ("""
        def short_length(w: list[int]) -> int:
            t = w[1] * w[2]
            n = 9223372036854775807 - t
            arr = [0] * (n + 5)
            arr2 = [0] * len(arr)
            return arr2[4]

        def case_list_shorter_than_its_proof_length() -> None:
            w = [0, 3037000500, 3037000500]
            print("before")
            print(short_length(w))
            print("after")
        """, ()),
    "list_shorter_than_its_proof_narrowed": ("""
        def short_narrowed(w: list[int]) -> int:
            t = w[1] * w[2]
            s = 9223372036854775807 - t
            n = w[0]
            if n > s:
                arr = [0] * n
                return arr[0]
            return 0

        def case_list_shorter_than_its_proof_narrowed() -> None:
            w = [0, 1099511627776, 1099511627776]
            print("before")
            print(short_narrowed(w))
            print("after")
        """, ()),
    "list_repeat_count_too_large": ("""
        def case_list_repeat_count_too_large() -> None:
            print("before")
            a = [1.0] * pw(10, 30)
            print("after", len(a))
        """, ()),
    "list_range_length_too_large": ("""
        def case_list_range_length_too_large() -> None:
            big = pw(10, 30)
            w = list(range(big - big + 3))
            nb = -big
            w2 = list(range(nb))
            print("before", len(w), len(w2))
            a = list(range(big))
            print("after", len(a))
        """, ()),
})
# lists of big ints (#465): a big-capable list reads and stores through the same bounds checks and messages as a raw one
# (the stored value is not proved inside 64 bits, which is what makes the list big-capable), and an exception leaves
# every frame holding such lists through its cleanup point (the leak checker of the sanitizer job reads the balance).
RAISING.update({
    "biglist_read_out_of_range": ("""
        def case_biglist_read_out_of_range() -> None:
            a = [0] * 3
            a[0] = pw(2, 70)
            print("before", a[0], len(a))
            print(a[3])
            print("after")
        """, ()),
    "biglist_read_negative_out_of_range": ("""
        def case_biglist_read_negative_out_of_range() -> None:
            a = [0] * 3
            a[1] = pw(2, 70)
            print("before", a[-3] == 0, a[1] > 0)
            print(a[-4])
            print("after")
        """, ()),
    "biglist_store_out_of_range": ("""
        def case_biglist_store_out_of_range() -> None:
            a = [0] * 3
            a[0] = pw(2, 70)
            print("before", a[0], len(a))
            a[3] = pw(2, 71)
            print("after")
        """, ()),
    "biglist_store_negative_out_of_range": ("""
        def case_biglist_store_negative_out_of_range() -> None:
            a = [pw(2, 70), 2]
            print("before", a[0], len(a))
            a[-3] = 1
            print("after")
        """, ()),
    "biglist_index_too_large": ("""
        def case_biglist_index_too_large() -> None:
            a = [pw(2, 70), 2]
            print("before", a[1])
            print(a[a[0]])
            print("after")
        """, ()),
    "biglist_oob_through_a_frame": ("""
        def bl_inner(a: list[int]) -> int:
            a[0] = a[0] * 3
            return a[9]

        def bl_outer(a: list[int]) -> int:
            mine = [pw(3, 50)] * 4
            mine[1] = mine[0] * a[0]
            return bl_inner(a) + mine[1]

        def case_biglist_oob_through_a_frame() -> None:
            keep = [pw(2, 70)] * 2
            more = [pw(5, 40), 1]
            print("before", keep[0] > 0, more[0] > 0)
            print(bl_outer(keep))
            print("after")
        """, ()),
})
SELECTOR_ORDER = sorted(RAISING)


def selector_source() -> str:
    """One program: every case of RAISING as a function, and the entry that runs the one `len(sys.argv)`
    selects (case i when the program has i + 1 arguments, itself included)."""
    parts = ["import math\nimport sys\n", textwrap.dedent(SELECTOR_PRELUDE).strip("\n") + "\n"]
    parts += [textwrap.dedent(RAISING[n][0]).strip("\n") + "\n" for n in SELECTOR_ORDER]
    entry = ['if __name__ == "__main__":']
    for i, n in enumerate(SELECTOR_ORDER):
        entry.append(f"    {'if' if i == 0 else 'elif'} len(sys.argv) == {i + 1}:")
        entry.append(f"        case_{n}()")
    return "\n".join(parts) + "\n" + "\n".join(entry) + "\n"


@pytest.fixture(scope="module")
def selector(tmp_path_factory):
    """The exception selector program, built once."""
    need_cc()
    tmp = tmp_path_factory.mktemp("selector")
    src = write(tmp, selector_source(), "selector")
    exe = aot.build(src, tmp / "selector")          # build returns the real path (`.exe` on windows)
    return SimpleNamespace(src=src, exe=exe)


def case_args(name):
    """The dummy arguments that select the case: `len(sys.argv)` is its index plus one. A case that reads an
    argument names the first ones (RAISING[name][1])."""
    args = ["x"] * SELECTOR_ORDER.index(name)
    for k, a in enumerate(RAISING[name][1]):
        assert k < len(args), f"{name} needs {k + 1} arguments but sorts too early to get them"
        args[k] = a
    return args


@pytest.mark.parametrize("name", SELECTOR_ORDER)
def test_uncaught_exception_matches_cpython(selector, name):
    args = case_args(name)
    ours, theirs = run_exe(selector.exe, args), run_py(selector.src, args)
    assert text(theirs.stdout).startswith("before")
    assert "after" not in text(ours.stdout)
    assert_same(ours, theirs, failed=True)
    assert last_line(theirs.stderr).split(":")[0] in ("ZeroDivisionError", "ValueError", "OverflowError", "IndexError")


def test_the_cases_name_the_exceptions_cpython_raises(selector):
    """The last line of standard error of each case, as CPython writes it: the messages this mode copies."""
    want = {
        "list_read_out_of_range": "IndexError: list index out of range",
        "list_read_negative_out_of_range": "IndexError: list index out of range",
        "list_store_out_of_range": "IndexError: list assignment index out of range",
        "list_store_negative_out_of_range": "IndexError: list assignment index out of range",
        "list_augmented_store_out_of_range": "IndexError: list index out of range",
        "list_oob_through_a_frame": "IndexError: list index out of range",
        # big integers (#462): the messages are the reference's own
        "list_index_too_large_read": "IndexError: cannot fit 'int' into an index-sized integer",
        "list_shorter_than_its_proof": "IndexError: list index out of range",
        "list_shorter_than_its_proof_store": "IndexError: list assignment index out of range",
        "list_shorter_than_its_proof_copy": "IndexError: list index out of range",
        "list_shorter_than_its_proof_length": "IndexError: list index out of range",
        "list_shorter_than_its_proof_narrowed": "IndexError: list index out of range",
        "list_index_too_large_store": "IndexError: cannot fit 'int' into an index-sized integer",
        "list_negative_index_too_large": "IndexError: cannot fit 'int' into an index-sized integer",
        "argv_index_too_large": "IndexError: cannot fit 'int' into an index-sized integer",
        "argv_index_out_of_range": "IndexError: list index out of range",
        "argv_negative_index_out_of_range": "IndexError: list index out of range",
        "list_repeat_count_too_large": "OverflowError: cannot fit 'int' into an index-sized integer",
        "list_range_length_too_large": "OverflowError: Python int too large to convert to C ssize_t",
        # lists of big ints (#465)
        "biglist_read_out_of_range": "IndexError: list index out of range",
        "biglist_read_negative_out_of_range": "IndexError: list index out of range",
        "biglist_store_out_of_range": "IndexError: list assignment index out of range",
        "biglist_store_negative_out_of_range": "IndexError: list assignment index out of range",
        "biglist_index_too_large": "IndexError: cannot fit 'int' into an index-sized integer",
        "biglist_oob_through_a_frame": "IndexError: list index out of range",
        "int_big_floordiv_zero": "ZeroDivisionError: division by zero",
        "int_big_mod_zero": "ZeroDivisionError: division by zero",
        "int_big_truediv_zero": "ZeroDivisionError: division by zero",
        "int_digit_limit_to_str": "ValueError: Exceeds the limit (4300 digits) for integer string conversion; "
                                  "use sys.set_int_max_str_digits() to increase the limit",
        "int_digit_limit_in_an_fstring": "ValueError: Exceeds the limit (4300 digits) for integer string conversion; "
                                         "use sys.set_int_max_str_digits() to increase the limit",
        "int_digit_limit_second_argument": "ValueError: Exceeds the limit (4300 digits) for integer string "
                                           "conversion; use sys.set_int_max_str_digits() to increase the limit",
        "int_digit_limit_from_str": "ValueError: Exceeds the limit (4300 digits) for integer string conversion: value "
                                    "has 4301 digits; use sys.set_int_max_str_digits() to increase the limit",
        "int_float_overflow_conversion": "OverflowError: int too large to convert to float",
        "int_float_overflow_mixed": "OverflowError: int too large to convert to float",
        "int_float_overflow_after_the_right_operand": "OverflowError: int too large to convert to float",
        "int_float_overflow_truediv": "OverflowError: integer division result too large for a float",
        "int_float_overflow_dividing_by_a_big_int": "OverflowError: int too large to convert to float",
        "int_invalid_literal_non_ascii": "ValueError: invalid literal for int() with base 10: "
                                         "'5 \u00e9 \U0001f600\\u200b'",
        "int_invalid_literal_underscore": "ValueError: invalid literal for int() with base 10: '1__0'",
    }
    for name, line in want.items():
        args = case_args(name)
        ours = run_exe(selector.exe, args)
        assert last_line(ours.stderr) == line, name
        assert ours.returncode == 1, name
        assert last_line(run_py(selector.src, args).stderr) == line, name


# --- the benchmarks as whole programs (#462) ----------------------------------------------------------------
#
# benchmarks/typedpython/py/spectral_norm.py and nbody.py are built AS THEY ARE (their size comes from
# `int(sys.argv[1])`: a big-integer-capable int, aot/rep.py) and run with several arguments, invalid ones included.
# `sys.argv[1]` is the user's input at run time, so every argument must give what CPython gives: the same output, exit
# status and last line of standard error, with the SAME raw argument bytes handed to both. fannkuch.py is refused, and
# only for the stores of values not proved inside 64 bits into a `list[int]` (the elements of a list are int64 in
# this step: the next PR). A variant of nbody with `n` fixed in the source has no unproved int at all; it is generated
# (not built) and read as text.

ARGV_LINE = "    n: int = int(sys.argv[1])"


def nbody_variant(tmp_path) -> Path:
    source = (BENCH / "nbody.py").read_text()
    assert source.count(ARGV_LINE) == 1
    path = tmp_path / "nbody_variant.py"
    path.write_text(source.replace(ARGV_LINE, "    n: int = 1000"))
    return path


@pytest.fixture(scope="module")
def nbody_variant_generated(tmp_path_factory):
    """The variant of nbody with its size fixed in the source, generated once (default mode): (its path, its C)."""
    variant = nbody_variant(tmp_path_factory.mktemp("nbody_variant"))
    return variant, aot.generate(variant)


@pytest.fixture(scope="module")
def generate_benchmark():
    """`aot.generate(BENCH / name)` (default mode) once per benchmark and module; a finding is raised again to
    each test that asks."""
    cache = {}

    def generate(name):
        if name not in cache:
            try:
                cache[name] = aot.generate(BENCH / name)
            except aot.AotError as e:
                cache[name] = e
        if isinstance(cache[name], aot.AotError):
            raise cache[name]
        return cache[name]
    return generate


@pytest.fixture(scope="module")
def nbody(tmp_path_factory):
    need_cc()
    tmp = tmp_path_factory.mktemp("nbody")
    src = tmp / "nbody.py"
    shutil.copy2(BENCH / "nbody.py", src)
    return SimpleNamespace(src=src, exe=aot.build(src, tmp / "nbody"))


@pytest.fixture(scope="module")
def spectral(tmp_path_factory):
    need_cc()
    tmp = tmp_path_factory.mktemp("spectral")
    src = tmp / "spectral_norm.py"
    shutil.copy2(BENCH / "spectral_norm.py", src)
    return SimpleNamespace(src=src, exe=aot.build(src, tmp / "spectral"))


@pytest.fixture(scope="module")
def fannkuch(tmp_path_factory):
    """fannkuch.py as it is (#465): `count` holds values that are not proved inside 64 bits, so it is a big-capable list;
    `perm1` and `perm` stay raw."""
    need_cc()
    tmp = tmp_path_factory.mktemp("fannkuch")
    src = tmp / "fannkuch.py"
    shutil.copy2(BENCH / "fannkuch.py", src)
    c_file = tmp / "fannkuch.c"
    return SimpleNamespace(src=src, exe=aot.build(src, tmp / "fannkuch", emit_c=c_file), c_file=c_file)


def same_run(program, args):
    """Run the executable and the file under CPython with the same arguments (str or raw bytes) and compare."""
    ours, theirs = run_exe(program.exe, args), run_py(program.src, args)
    assert text(ours.stdout) == text(theirs.stdout), args
    assert ours.returncode == theirs.returncode, (args, text(ours.stderr), text(theirs.stderr))
    if theirs.returncode != 0:
        assert theirs.returncode == 1
        assert last_line(ours.stderr) == last_line(theirs.stderr), args
    else:
        assert text(ours.stderr) == "", args
    return ours, theirs


def test_nbody_prints_what_cpython_prints(nbody):
    ours, theirs = same_run(nbody, ["1000"])
    assert theirs.returncode == 0, text(theirs.stderr)
    assert re.fullmatch(r"-?\d+\.\d{9} -?\d+\.\d{9}\n", text(theirs.stdout)), text(theirs.stdout)


@pytest.mark.parametrize("n", ["100", "7"])
def test_spectral_norm_prints_what_cpython_prints(spectral, n):
    ours, theirs = same_run(spectral, [n])
    assert theirs.returncode == 0, text(theirs.stderr)
    assert re.fullmatch(r"\d\.\d{9}\n", text(theirs.stdout)), text(theirs.stdout)


# What the program is given as its argument: (id, the argument; bytes are raw bytes, handed over as they are on POSIX).
# Most are invalid ints (the message shows the reference's repr of the string); some are valid in ways that are easy to
# get wrong (underscores, a sign, Unicode digits and spaces).
ARGUMENTS = [
    ("empty", ""),
    ("spaces", "   "),
    ("underscore_between_digits", "1_0"),
    ("leading_underscore", "_1"),
    ("trailing_underscore", "1_"),
    ("double_underscore", "1__0"),
    ("a_sign_only", "+"),
    ("hex", "0x10"),
    ("a_float", "1.5"),
    ("single_quote", "a'b"),
    ("both_quotes", "a'\"b"),
    ("a_tab", "\t7x"),
    ("a_backslash", "a\\b"),
    ("non_ascii_letters", "\u00e9\u4e2d"),
    ("non_ascii_digits_are_valid", "\u0663\u0662"),
    ("no_break_space_around_digits_is_valid", "\u00a05\u00a0"),
    ("a_non_printable_character", "5\u200b"),
    ("an_emoji", "\U0001f600"),
    ("a_sign_and_spaces_around_digits", " +6 "),
    ("leading_zeros", "007"),
    ("a_negative_number", "-3"),
    ("digits_4301", "1" * 4301),
    ("a_number_beyond_64_bits", "9" * 19),
    ("a_long_invalid_string", "x" * 300),
    ("an_invalid_utf8_byte", b"\xff"),
    ("truncated_utf8", b"7\xe2\x82"),
    ("invalid_utf8_between_digits", b"1\xc3\x28"),
    ("a_surrogate_as_utf8", b"5\xed\xa0\x80"),
]
NO_ARGUMENT = ("no_argument", None)


def argument_list(argument):
    return [] if argument is None else [argument]


def usable(argument):
    if isinstance(argument, bytes) and sys.platform == "win32":
        pytest.skip("program arguments are UTF-16 on windows: raw bytes are not an argument there")


@pytest.mark.parametrize("argument", [a for _, a in [*ARGUMENTS, NO_ARGUMENT]],
                         ids=[i for i, _ in [*ARGUMENTS, NO_ARGUMENT]])
def test_spectral_norm_with_every_kind_of_argument(spectral, argument):
    usable(argument)
    same_run(spectral, argument_list(argument))


# (nbody runs a thousand steps per valid argument under CPython, so its table is the invalid ones and a few valid)
NBODY_ARGUMENTS = [a for i, a in ARGUMENTS if i not in {"non_ascii_digits_are_valid", "leading_zeros",
                                                         "no_break_space_around_digits_is_valid",
                                                         "a_sign_and_spaces_around_digits", "underscore_between_digits",
                                                         "a_number_beyond_64_bits"}]
NBODY_IDS = [i for i, a in ARGUMENTS if a in NBODY_ARGUMENTS]


@pytest.mark.parametrize("argument", [*NBODY_ARGUMENTS, None, "2", "0", "-5", "1_0", "\u0663"],
                         ids=[*NBODY_IDS, "no_argument", "two", "zero", "negative", "underscore", "arabic_digit"])
def test_nbody_with_every_kind_of_argument(nbody, argument):
    usable(argument)
    same_run(nbody, argument_list(argument))


def test_an_argument_that_is_not_an_int_ends_with_the_reference_message(spectral):
    """Pin what the table compares: the line itself, for three of them, literally."""
    for argument, line in (("1_", "ValueError: invalid literal for int() with base 10: '1_'"),
                           ("\u00e9", "ValueError: invalid literal for int() with base 10: '\u00e9'"),
                           ("a'b", 'ValueError: invalid literal for int() with base 10: "a\'b"')):
        ours = run_exe(spectral.exe, [argument])
        assert ours.returncode == 1 and ours.stdout == b""
        assert last_line(ours.stderr) == line
    ours = run_exe(spectral.exe, [])
    assert ours.returncode == 1 and last_line(ours.stderr) == "IndexError: list index out of range"


def test_nbody_generates_with_a_big_integer_argument(generate_benchmark):
    c = generate_benchmark("nbody.py")
    assert '#include "tp_aot_int.h"' in c and "tpa_argv_int(INT64_C(1)" in c and "wmain" in c


def test_nbody_generates_c_with_one_cleanup_point_per_function(nbody_variant_generated):
    _, c = nbody_variant_generated
    bodies = re.findall(r"^static int tpf_\w+\([^)]*\)\n\{.*?^\}", c, flags=re.S | re.M)
    assert len(bodies) == 12                  # the eleven functions of nbody.py and the entry
    for body in bodies:
        assert body.count("tp_cleanup:") <= 1
        assert "return -1" not in body and "return 0" not in body, "a function leaves through its cleanup point only"
    assert "tpa_f64_release(v_x)" in c and "tpf_make_x(&" in c


@pytest.mark.parametrize("n", ["7", "9"])
def test_fannkuch_prints_what_cpython_prints(fannkuch, n):
    ours, theirs = same_run(fannkuch, [n])
    assert theirs.returncode == 0, text(theirs.stderr)
    assert re.fullmatch(r"-?\d+ \d+\n", text(theirs.stdout)), text(theirs.stdout)


@pytest.mark.parametrize("argument", ["x", "1.5", None, "1", "-3", "0"], ids=["not_an_int", "a_float", "no_argument",
                                                                            "one", "negative", "zero"])
def test_fannkuch_with_an_argument_that_is_not_a_usable_size(fannkuch, argument):
    same_run(fannkuch, argument_list(argument))


def test_the_benchmarks_that_build_generate_without_a_finding(generate_benchmark):
    for name in ("spectral_norm.py", "nbody.py", "fannkuch.py"):
        generate_benchmark(name)


def test_fannkuch_generates_with_no_finding_and_only_count_is_a_big_capable_list(generate_benchmark):
    """`count[r - 1] = r` and `count[r] -= 1` store a value that is not proved inside 64 bits (`r` starts as the
    argument), so `count` is a big-capable list (it was the finding `aot/int-list-store` before #465); `perm1` and
    `perm` only ever hold what is read from `perm1`, so they stay raw (their elements are plain int64)."""
    c = generate_benchmark("fannkuch.py")
    [body] = re.findall(r"^static int tpf_fannkuch\([^)]*\)\n\{.*?^\}", c, flags=re.S | re.M)
    assert "tpa_int_list *v_count" in body and "tpa_int_list_fill(" in body and "tpa_int_list_release(v_count)" in body
    assert "tpa_i64_list *v_perm1" in body and "tpa_i64_list *v_perm " in body
    assert "tpa_i64_iota(" in body and "tpa_i64_copy(" in body
    assert "tpa_int_list_store(v_count, " in body                  # an element store takes the new reference
    assert "tpa_int_list_load(v_count, " in body and "v_count->d[" not in body   # and every access is a locking helper
    assert "v_perm1->d[" in body and not re.search(r"tpa_int_list_(load|store|init)\(v_perm1?\b", body)
    assert body.count("tp_cleanup:") == 1


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
    "second import": ("import math\nimport os\n", ("aot/import", 2, "os")),
    "import twice": ("import sys\nimport sys\n", ("aot/import", 2, "sys")),
    "from import": ("from math import sqrt\n", ("aot/import", 1, "math")),
    "empty list display": ("def f() -> int:\n    x = []\n    return 0\n", ("aot/container", 2, "list display")),
    "str method outside the S1 list": ("def f(s: str) -> str:\n    return s.title()\n", ("aot/call", 2, "s.title")),
    "printf-style str formatting": ("def f(x: int) -> str:\n    return '%d' % x\n", ("aot/front-end", 2, "f")),
    "print keyword": ("def f() -> None:\n    print(1, file=None)\n", ("aot/print-keyword", 2, "file")),
    "print as a value": ("def f() -> None:\n    x = print(1)\n", ("aot/print-use", 2, "print")),
    "class with base": ("class C(int):\n    pass\n", ("aot/class", 1, "C")),
    "unknown builtin": ("def f(x: float) -> int:\n    return round(x)\n", ("aot/builtin", 2, "round")),
    "int with base": ("def f() -> int:\n    return int('5', 16)\n", ("aot/builtin", 2, "int")),
    "math attribute": ("import math\ndef f() -> float:\n    return math.foo\n", ("aot/attribute", 3, "foo")),
    "math function": ("import math\ndef f(x: float) -> float:\n    return math.gamma(x)\n",
                      ("aot/math", 3, "math.gamma")),
    "matmul operator": ("def f(a: int) -> int:\n    return a @ 3\n", ("aot/operator", 2, "MatMult")),
    "decorator": ("@compiled\ndef f() -> None:\n    pass\n", ("aot/decorator", 1, "compiled")),
    "conditional expression of two types": ("def f(a: bool) -> float:\n    return 1 if a else 2.0\n",
                                            ("aot/front-end", 2, "f")),
    "missing annotation": ("def f(a) -> int:\n    return 1\n", ("aot/annotation", 1, "parameter `a`")),
    "list of bool annotation": ("def f(a: list[bool]) -> int:\n    return 1\n", ("aot/type", 1, "list[bool]")),
    "list of lists annotation": ("def f(a: list[list[int]]) -> int:\n    return 1\n", ("aot/type", 1, "list[list[int]]")),
    "tuple annotation of a parameter": ("def f(a: tuple[int, int]) -> int:\n    return 1\n", ("aot/type", 1, "tuple[int, int]")),
    "list in a tuple return": ("def f(a: list[float]) -> tuple[list[float], int]:\n    return a, 1\n",
                               ("aot/type", 1, "tuple[list[float], int]")),
    # the constructs the AOT mode does not have yet, each named
    "list in a list": ("def f() -> int:\n    x = [[1.0]]\n    return 0\n", ("aot/front-end", 2, "f")),
    "list method": ("def f(a: list[float]) -> None:\n    a.sort()\n", ("aot/call", 2, "a.sort")),   # (append: S2a; sort: S2c)
    "list comparison": ("def f(a: list[float], b: list[float]) -> bool:\n    return a == b\n", ("aot/front-end", 2, "f")),
    "list module constant": ("A = [1.0]\n", ("aot/constant", 1, "A")),
    "list passed as an expression": (
        "def g(a: list[float]) -> int:\n    return 0\n\ndef f() -> int:\n    return g([1.0])\n",
        ("aot/front-end", 5, "f")),
    "mixed list display": ("def f() -> int:\n    x = [1, 2.0]\n    return 0\n", ("aot/front-end", 2, "f")),
    "list of bools": ("def f() -> int:\n    x = [True, False]\n    return 0\n", ("aot/front-end", 2, "f")),
    "list store of an int into floats": ("def f(a: list[float]) -> None:\n    a[0] = 1\n", ("aot/front-end", 2, "f")),
    "list index that is a float": (
        "def f(a: list[float], x: float) -> float:\n    return a[x]\n", ("aot/front-end", 2, "f")),
    "list returned as a scalar": ("def f(a: list[float]) -> float:\n    return a\n", ("aot/front-end", 2, "f")),
    "list rebound to a scalar": ("def f() -> None:\n    a = [1.0]\n    a = 2.0\n", ("aot/front-end", 2, "f")),
    "list element arithmetic is strict": (
        "def f(a: list[int]) -> int:\n    return a[0] + 1\n", ("aot/int-unproven", 2, "+")),
    "tuple display": ("def f() -> int:\n    t = (1, 2)\n    return 0\n", ("aot/container", 2, "tuple display")),
    "tuple unpack of a display": ("def f() -> int:\n    a, b = 1, 2\n    return 0\n", ("aot/container", 2, "tuple display")),
    "tuple used as a value": (
        "def g(x: float) -> tuple[float, float]:\n    return x, x\n\ndef f() -> float:\n    t = g(1.0)\n    return t[0]\n",
        ("aot/front-end", 5, "f")),
    "tuple unpacked into the wrong number": (
        "def g(x: float) -> tuple[float, float, float]:\n    return x, x, x\n\ndef f() -> float:\n"
        "    a, b = g(1.0)\n    return a\n", ("aot/front-end", 5, "f")),
    "tuple unpacked into the wrong type": (
        "def g(x: float) -> tuple[float, int]:\n    return x, 1\n\ndef f() -> float:\n"
        "    a, b = g(1.0)\n    return b\n", ("aot/front-end", 6, "f")),
    "tuple unpacked from a list": (
        "def f(a: list[float]) -> float:\n    x, y = a\n    return x\n", ("aot/front-end", 2, "f")),
    "tuple return of the wrong shape": (
        "def f(x: float) -> tuple[float, int]:\n    return x, x\n", ("aot/front-end", 2, "f")),
    "sys attribute": ("import sys\ndef f() -> int:\n    return sys.version\n", ("aot/attribute", 3, "version")),
    "sys argv slice": ("import sys\ndef f() -> int:\n    x = sys.argv[1:]\n    return 0\n", ("aot/front-end", 3, "f")),
    "int of an argument": ("import sys\ndef f() -> int:\n    return int(sys.argv[1])\n", ("aot/int-unproven", 3, "int")),
    "int of argument zero": ("import sys\ndef f() -> int:\n    return int(sys.argv[0])\n", ("aot/int-unproven", 3, "int")),
    "str identity": ("def f(s: str, t: str) -> bool:\n    return s is t\n", ("aot/front-end", 2, "f")),
    "sys argv with a float index": (
        "import sys\ndef f(x: float) -> int:\n    return int(sys.argv[x])\n", ("aot/front-end", 3, "f")),
    "min of three": ("def f(a: float) -> float:\n    return min(a, a, a)\n", ("aot/call", 2, "min")),
    "min of an int and a float": ("def f(a: int, b: float) -> float:\n    return min(a, b)\n", ("aot/front-end", 2, "f")),
    "abs of the most negative int": ("def f(a: int) -> int:\n    return abs(a)\n", ("aot/int-unproven", 2, "-")),
    "f-string ascii conversion": ("def f(x: float) -> None:\n    print(f'{x!a}')\n", ("aot/f-string", 2, "conversion")),
    "f-string ascii conversion with equals": ("def f(x: float) -> None:\n    print(f'{x=!a}')\n",
                                              ("aot/f-string", 2, "conversion")),
    "f-string width": ("def f(x: float) -> None:\n    print(f'{x:8.2f}')\n", ("aot/f-string", 2, "8.2f")),
    "f-string other type": ("def f(x: float) -> None:\n    print(f'{x:e}')\n", ("aot/f-string", 2, "e")),
    "f-string nested field": ("def f(x: float, n: int) -> None:\n    print(f'{x:.{n}f}')\n", ("aot/f-string", 2, "nested field")),
    "f-string precision too large": ("def f(x: float) -> None:\n    print(f'{x:.21f}')\n", ("aot/f-string", 2, ".21f")),
    "f-string precision on an int": ("def f(n: int) -> None:\n    print(f'{n:.2f}')\n", ("aot/front-end", 2, "f")),
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


# Most rows are checked from a few merged programs instead of one program each: `aot.generate` starts the
# type inferencer (a subprocess, slow on windows) for every program that gets past the source-level checker.
# A merged program holds each row's chunk once, its functions and module constants renamed with a suffix
# of their own (`f` becomes `f_r12`), so that no two rows share a name; every row's test then looks its
# (rule, line, name) up in the findings of the shared program, with the line moved to the merged file.
#
#  - SINGLE_ROWS stay one program each: the entry block is what they are about (a missing one, text
#    after it, an assignment in it).
#  - SOURCE_LEVEL_ROWS are the findings of the source-level checker (aot/program.py). When that checker
#    finds anything, only its findings are reported and the front end and the back end do not run, so
#    these rows must not share a program with the others.
#  - every other row is a finding of the front end, the verifier or the back end.
# A row filed in the wrong group is not found in its program, so its own test fails.
SINGLE_ROWS = frozenset({
    "no entry", "statements after the entry block", "assignment to a module name in the entry block"})
SOURCE_LEVEL_ROWS = frozenset({
    "annotated constant outside 64 bits",
    "annotated constant without a value",
    "annotation outside int float bool",
    "class with base",
    "decorator",
    "empty list display",
    "f-string ascii conversion",
    "f-string ascii conversion with equals",
    "f-string nested field",
    "f-string other type",
    "f-string precision too large",
    "f-string width",
    "float annotation on an int value",
    "from import",
    "import twice",
    "int annotation on a bool value",
    "int literal outside 64 bits",
    "int with base",
    "list in a tuple return",
    "list method",
    "list module constant",
    "list of bool annotation",
    "list of lists annotation",
    "math attribute",
    "math function",
    "matmul operator",
    "min of three",
    "missing annotation",
    "module constant outside 64 bits",
    "module constant that is a call",
    "module loop",
    "module statement",
    "print as a value",
    "print keyword",
    "second import",
    "str method outside the S1 list",
    "sys attribute",
    "tuple annotation of a parameter",
    "tuple display",
    "tuple unpack of a display",
    "unknown builtin",
})
# The rows that were refused for an int not proved inside 64 bits before #462. Without strict_int they are accepted
# (their programs are among the BIGINT_CASES, compared with CPython); with `strict_int=True` (`--strict-int`, design
# decision D1 (a), leader 2026-11-16, not owner-confirmed) each is refused exactly as it always was.
STRICT_ROWS = frozenset({
    "unproved int op", "unproved augmented op", "int negation", "int floor division by a variable",
    "int true division", "list element arithmetic is strict", "int of an argument", "int of argument zero",
    "abs of the most negative int"})
IMPORT_ROWS = frozenset({"second import", "import twice", "from import"})      # their imports are the point
RENAMED_NAMES = frozenset({"f", "g", "N", "X", "A"})
HOISTED_IMPORTS = ("import math", "import sys")


def rename_names(source: str, suffix: str) -> str:
    """`source` with every name token in RENAMED_NAMES given `suffix` (tokens, so strings and the
    format specs of f-strings are not touched)."""
    lines = source.splitlines(keepends=True)
    edits = []
    for tok in tokenize.generate_tokens(io.StringIO(source).readline):
        if tok.type == tokenize.NAME and tok.string in RENAMED_NAMES:
            edits.append(tok.start)
    for row, col in sorted(edits, reverse=True):
        line = lines[row - 1]
        name_end = col + 1
        lines[row - 1] = line[:name_end] + suffix + line[name_end:]
    return "".join(lines)


def merged_rejection_source(names: list[str]):
    """One program holding the chunks of the rows `names`; returns its text and, per row, the finding
    to look for: (rule, line in the merged file, name)."""
    parts = ["import math\nimport sys\n"]
    line_of_next_chunk = 3
    expected = {}
    for i, name in enumerate(names):
        source, (rule, line, what) = REJECTED[name]
        lines = source.splitlines(keepends=True)
        dropped = 0
        if name not in IMPORT_ROWS:
            while lines and lines[0].rstrip("\n") in HOISTED_IMPORTS:       # the merged file imports once, on top
                lines.pop(0)
                dropped += 1
        suffix = f"_r{i}"
        chunk = rename_names("".join(lines), suffix)
        if what in RENAMED_NAMES:
            what += suffix
        expected[name] = (rule, line_of_next_chunk + line - 1 - dropped, what)
        parts.append(chunk + "\n")
        line_of_next_chunk += chunk.count("\n") + 1
    return "".join(parts) + MAIN, expected


def rejection_groups() -> dict[str, list[str]]:
    rows = sorted(set(REJECTED) - SINGLE_ROWS)
    return {"source": [n for n in rows if n in SOURCE_LEVEL_ROWS],
            "later": [n for n in rows if n not in SOURCE_LEVEL_ROWS and n not in STRICT_ROWS],
            "strict": [n for n in rows if n in STRICT_ROWS]}


def test_every_grouped_row_name_is_a_row():
    """A renamed row left in a group set silently moves the new row into the wrong merged program (S1b: two
    source-level rows ran in the later program and hid every later finding)."""
    for group in (SINGLE_ROWS, SOURCE_LEVEL_ROWS, STRICT_ROWS, IMPORT_ROWS):
        assert group <= set(REJECTED), sorted(group - set(REJECTED))


@pytest.fixture(scope="module")
def rejections(tmp_path_factory):
    """Each merged program generated once: row name -> (the finding to look for, the program's findings)."""
    results = {}
    for group, names in rejection_groups().items():
        source, expected = merged_rejection_source(names)
        path = write(tmp_path_factory.mktemp(f"rejected_{group}"), source)
        try:
            aot.generate(path, strict_int=(group == "strict"))
            findings = None
        except aot.AotError as e:
            findings = e.findings
        for name in names:
            results[name] = (expected[name], findings)
        if group == "strict":                     # the same program without strict_int
            try:
                aot.generate(path)
                results["<strict group, default mode>"] = None
            except aot.AotError as e:
                results["<strict group, default mode>"] = e.findings
    return results


def test_the_rows_refused_only_by_strict_int_are_accepted_by_default(rejections):
    """The program of the strict rows, generated without strict_int: no finding (every int is exact)."""
    assert rejections["<strict group, default mode>"] is None


@pytest.mark.parametrize("name", sorted(set(REJECTED) - SINGLE_ROWS))
def test_rejection_table(rejections, name):
    want, findings = rejections[name]
    assert findings is not None, "the merged program was accepted"
    found = [(f.rule, f.line, f.name) for f in findings]
    assert want in found, found
    assert all(f.path == "prog.py" for f in findings)


@pytest.mark.parametrize("name", sorted(SINGLE_ROWS))
def test_rejection_table_entry_rows(tmp_path, name):
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
    aot.generate(path)                                  # accepted without strict_int
    with pytest.raises(aot.AotError) as raised:
        aot.generate(path, strict_int=True)
    [f] = raised.value.findings
    assert (f.rule, f.line) == ("aot/int-unproven", 5)
    assert "`s + i`" in f.message
    assert "not proved" in f.message and "64 bits" in f.message and "later step" in f.message
    assert "prog.py:5: error aot/int-unproven:" in str(raised.value)


def test_int_true_division_message_names_the_expression(tmp_path):
    path = write(tmp_path, "def f(a: int, b: int) -> float:\n    return a / b\n" + MAIN)
    assert "tpa_truediv_i64(" in aot.generate(path)      # accepted without strict_int
    with pytest.raises(aot.AotError) as raised:
        aot.generate(path, strict_int=True)
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


# --- every finding of the program, in every function ---------------------------------------------

def test_every_finding_of_a_program_is_reported(tmp_path):
    """Not the first function's findings only: an unproved operation in each of three functions, and a
    function the front end cannot lower, all in one error."""
    path = write(tmp_path, textwrap.dedent("""
        def a(x: int, y: int) -> int:
            return x + y

        def b(x: int) -> int:
            return x * x

        def c(s: float) -> float:
            t = [1.0, 2]
            return s

        def d(x: int) -> int:
            return -x

        def e(p: list[float]) -> int:
            return p == p
        """) + MAIN)
    with pytest.raises(aot.AotError) as raised:
        aot.generate(path, strict_int=True)
    found = {(f.rule, f.line, f.name) for f in raised.value.findings}
    assert {("aot/int-unproven", 2, "+"), ("aot/int-unproven", 5, "*"), ("aot/front-end", 8, "c"),
            ("aot/int-unproven", 12, "-"), ("aot/front-end", 15, "e")} <= found, found


# With `strict_int=True` (`--strict-int`) the three benchmarks are refused exactly as they were before #462: ONLY for an
# int operation not proved inside 64 bits (everything else they use is supported). The exact set of (line, expression)
# is pinned: a change of these files or of the proofs shows here.
INT_UNPROVEN = {
    "spectral_norm.py": {
        (6, "(i + j) * (i + j + 1)"), (6, "i + j"), (6, "i + j + 1"), (6, "(i + j) * (i + j + 1) // 2 + i"),
        (6, "(i + j) * (i + j + 1) // 2 + i + 1"), (31, "int(sys.argv[1])")},
    "fannkuch.py": {
        (13, "r - 1"), (14, "r -= 1"), (27, "flips += 1"), (32, "checksum += flips"), (34, "checksum -= flips"),
        (42, "count[r] -= 1"), (45, "r += 1"), (46, "perm_count += 1"), (50, "int(sys.argv[1])")},
    "nbody.py": {
        (next(i for i, ln in enumerate((BENCH / "nbody.py").read_text().splitlines(), 1) if ln == ARGV_LINE),
         "int(sys.argv[1])")},
}


@pytest.mark.parametrize("name", sorted(INT_UNPROVEN))
def test_strict_int_refuses_the_benchmarks_only_for_unproved_ints(name):
    with pytest.raises(aot.AotError) as raised:
        aot.generate(BENCH / name, strict_int=True)
    findings = raised.value.findings
    assert {f.rule for f in findings} == {"aot/int-unproven"}, [str(f) for f in findings]
    got = set()
    for f in findings:
        m = re.search(r"the int operation `(.+)` is not proved to stay inside 64 bits", f.message)
        assert m, f.message
        got.add((f.line, m.group(1)))
    assert got == INT_UNPROVEN[name]


# --- the generated C -------------------------------------------------------------------------------

def test_every_function_leaves_through_one_cleanup_point(tmp_path):
    """Two lists, an early return and an operation that can raise (an index): every exit, the early
    `return`, the failing read and the end of the body, goes through the one release of both lists."""
    c = aot.generate(write(tmp_path, """
        def pick(n: int, flag: bool) -> float:
            a = [1.0, 2.0]
            b = a[:]
            if flag:
                return 3.0
            return a[n] + b[0]

        if __name__ == "__main__":
            print(pick(1, False))
        """))
    [body] = re.findall(r"^static int tpf_pick\([^)]*\)\n\{.*?^\}", c, flags=re.S | re.M)
    assert body.count("tp_cleanup:") == 1
    # the two allocations that can fail, the early return, the failing read, the final return
    assert body.count("goto tp_cleanup") == 5
    assert "return -1" not in body and "return 0" not in body
    cleanup = body[body.index("tp_cleanup:"):]
    assert "tpa_f64_release(v_a);" in cleanup and "tpa_f64_release(v_b);" in cleanup and "return rc;" in cleanup
    assert body.index("tpa_slot(") < body.index("tp_cleanup:")


def test_a_list_name_bound_twice_is_retained_and_a_rebinding_releases(tmp_path):
    c = aot.generate(write(tmp_path, """
        def f() -> float:
            a = [1.0]
            b = a
            b = [2.0]
            return a[0] + b[0]

        if __name__ == "__main__":
            print(f())
        """))
    [body] = re.findall(r"^static int tpf_f\([^)]*\)\n\{.*?^\}", c, flags=re.S | re.M)
    assert body.index("tpa_f64_retain(v_a);") < body.index("tpa_f64_release(v_b);") < body.index("v_b = v_a;")
    assert body.count("tpa_f64_release(v_b);") == 3        # the two bindings and the cleanup


def test_the_sanitizer_switch_reaches_the_executable_build(monkeypatch, tmp_path):
    """TP_SANITIZE=1 adds the flags conftest.py adds to the default mode's extensions, once, to the AOT
    executable's compile and link line (one command), so the CI sanitizer job runs it under ASan/UBSan."""
    if cbuild._is_msvc():
        pytest.skip("the sanitizer flags are GCC and Clang flags")
    monkeypatch.delenv("TP_SANITIZE", raising=False)
    plain, _ = cc.command(tmp_path / "a.c", tmp_path / "a")
    base = cbuild.compiler()                 # (already sanitized when the whole run is, see conftest.py)
    for flag in cc.SANITIZE_FLAGS:
        assert (flag in plain) == (flag in base), flag
    monkeypatch.setenv("TP_SANITIZE", "1")
    assert cc.sanitizing()
    sanitized, _ = cc.command(tmp_path / "a.c", tmp_path / "a")
    for flag in cc.SANITIZE_FLAGS:
        assert sanitized.count(flag) == 1, (flag, sanitized)
    assert sanitized.index("-fsanitize=address,undefined") < sanitized.index(str(tmp_path / "a.c"))
    monkeypatch.setenv("TP_SANITIZE", "0")
    assert not cc.sanitizing()


@pytest.mark.parametrize("text", [
    'const char *s = "Python and PyObject";',
    '/* Python, PyObject, Py_INCREF(x) */ int a;',
    "char c = 'P'; int python_free;",
    "// PyObject\nint a;",
    'const char *s = "/* "; int a;',
    '/* a " quote */ int a;',
    'const char *s = "a\\"b PyObject"; int a;',
    'const char *s = "a\\\\"; int a;',
    '#include "tp_aot.h"\n#include <math.h>\n#include <stdint.h>\n',
])
def test_the_cpython_check_accepts(text):
    assert cpython_api_uses(text) == [], text


@pytest.mark.parametrize("text, found", [
    ("PyObject *o;", ["PyObject"]),
    ("x = _PyLong_New(1);", ["_PyLong_New"]),
    ("Py_INCREF(x);", ["Py_INCREF"]),
    ('const char *s = "/* "; PyObject *o; /* */', ["PyObject"]),
    ('/* a " quote */ PyObject *o; const char *s = "x";', ["PyObject"]),
    ('const char *s = "a\\"b PyObject"; PyObject *o;', ["PyObject"]),
    ('const char *s = "a\\\\"; PyObject *o;', ["PyObject"]),
    ("char c = 'P'; Py_None;", ["Py_None"]),
    ('#include "Python.h"\n', ["#include Python.h"]),
    ("#include <Python.h>\n", ["#include Python.h"]),
    ("#include <pyconfig.h>\n", ["#include pyconfig.h"]),
    ("#include <structmember.h>\n", ["#include structmember.h"]),
    ("#include <frameobject.h>\n", ["#include frameobject.h"]),
    ("#include <cpython/object.h>\n", ["#include cpython/object.h"]),
    ("#include <internal/pycore_long.h>\n", ["#include internal/pycore_long.h"]),
])
def test_the_cpython_check_rejects(text, found):
    assert cpython_api_uses(text) == found, text


def test_generated_c_contains_no_cpython(tmp_path):
    c = aot.generate(write(tmp_path, merged_source(["math_functions"])))
    header = HEADER.read_text()
    for what, body in (("generated C", c), ("tp_aot.h", header)):
        assert not cpython_api_uses(body), (what, cpython_api_uses(body))
    assert '#include "tp_aot.h"' in c
    big = HEADER.with_name("tp_aot_int.h").read_text()
    assert not cpython_api_uses(big), cpython_api_uses(big)
    for inc in re.findall(r"#include\s*[<\"]([^>\"]+)", c + header + big):
        # (stdatomic.h and intrin.h: the library build's atomic list counts, tp_aot.h under -DTPA_LIBRARY, #455;
        #  time.h and windows.h: tpa_time_* clocks and sleep in tp_aot.h, #476)
        assert inc in {"tp_aot.h", "errno.h", "math.h", "stdint.h", "stdio.h", "stdlib.h", "string.h",
                       "stdatomic.h", "intrin.h", "sched.h", "stddef.h", "tp_aot_int.h",
                       "time.h", "windows.h"}, inc


def test_tp_aot_has_no_clib_float_formatting():
    """#450: tp_aot.h does its own exact binary64 decimal formatting without strtod, atof, or
    printf float conversion specifiers."""
    header = HEADER.read_text(encoding="utf-8")
    assert "\u2014" not in header
    code = code_only(header)
    for fn in ("strtod", "strtof", "strtold", "atof", "__int128"):
        assert not re.search(rf"\b{fn}\b", code), fn
    no_comments = re.sub(r"/\*.*?\*/|//[^\n]*", "", header, flags=re.S)
    for lit in re.findall(r'"([^"\\]*(?:\\.[^"\\]*)*)"', no_comments):
        assert not re.search(r"%[0-9.*+\-# ']*[efgEFGaA]", lit), lit


def test_generated_c_uses_the_default_modes_float_flags():
    assert "-ffp-contract=off" in cc.FLAGS and "-fno-fast-math" in cc.FLAGS
    assert cbuild.DEFAULT_FLAGS == cc.FLAGS[:len(cbuild.DEFAULT_FLAGS)]
    assert "/fp:precise" in cbuild._msvc_flags(list(cc.FLAGS))


def test_generated_c_and_header_switch_fp_contraction_off_in_source(tmp_path):
    """#464: floating-point contraction is turned off in tp_aot.h and in the generated C itself (not only
    by compiler flags), and -ffast-math / /fp:fast is rejected at compile time."""
    c = aot.generate(write(tmp_path, """
        def fma_like(a: float, b: float, c: float) -> float:
            return a * b + c

        if __name__ == "__main__":
            x = 1.0 + 1.0 / 134217728.0
            print(fma_like(x, x, -1.0))
        """))
    header = HEADER.read_text(encoding="utf-8")
    for body in (header, c):
        for pragma in ("#pragma STDC FP_CONTRACT OFF", '#pragma GCC optimize("fp-contract=off")',
                       "#pragma float_control(precise, on)", "#pragma fp_contract(off)"):
            assert pragma in body, pragma
    assert "#if defined(__FAST_MATH__) || defined(_M_FP_FAST)" in header
    assert "#error " in header
    assert header.index("#pragma STDC FP_CONTRACT OFF") < header.index("tpa_truediv_f64")
    assert c.index("#pragma STDC FP_CONTRACT OFF") < c.index("tpf_fma_like")


def test_generated_c_without_fp_flags_matches_cpython_and_rejects_fast_math(tmp_path):
    """#464: compiling the generated C without `-ffp-contract=off` / `/fp:precise` (even with `-ffp-contract=on`
    on GCC/Clang) still computes CPython's double-rounded float result, and `-ffast-math` / `/fp:fast` fails
    the build at compile time."""
    need_cc()
    src = write(tmp_path, """
        def fma_exact(a: float, b: float, c: float) -> float:
            return a * b + c

        if __name__ == "__main__":
            x = 1.0 + 1.0 / 134217728.0
            print(fma_exact(x, x, -1.0))
        """)
    c_file = tmp_path / "fp.c"
    c_file.write_text(aot.generate(src), encoding="utf-8")
    exe = exe_path(tmp_path / "fp")
    cmd, env = cc.command(c_file, exe, obj_dir=tmp_path)
    idx = cmd.index(str(c_file))
    no_flag_cmd = [t for t in cmd[:idx] if t not in ("-ffp-contract=off", "-fno-fast-math", "/fp:precise")]
    if not cbuild._is_msvc():
        no_flag_cmd += ["-ffp-contract=on", "-Werror=pragmas"]
    no_flag_cmd += cmd[idx:]
    res = subprocess.run(no_flag_cmd, capture_output=True, env=env, timeout=60)
    assert res.returncode == 0, text(res.stderr) + text(res.stdout)
    assert_same(run_exe(exe), run_py(src))

    if cbuild._is_msvc():
        fast_cmd = [cbuild._msvc_cl(env), "/nologo", "/Zs", "/O2", "/fp:fast", f"/I{cc.RUNTIME_DIR}", str(c_file)]
    else:
        fast_cmd = ([t for t in cmd[:idx] if t not in ("-ffp-contract=off", "-fno-fast-math")]
                    + ["-fsyntax-only", "-ffast-math", str(c_file)])
    chk = subprocess.run(fast_cmd, capture_output=True, env=env, timeout=60)
    assert chk.returncode != 0
    assert "requires precise IEEE 754 floating-point semantics" in text(chk.stderr) + text(chk.stdout)


def test_cli_reports_each_finding_on_one_line(tmp_path, capsys):
    src = write(tmp_path, REJECTED["unproved int op"][0] + MAIN)
    assert main(["aot-build", str(src), "-o", str(tmp_path / "never"), "--strict-int"]) == 1
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
