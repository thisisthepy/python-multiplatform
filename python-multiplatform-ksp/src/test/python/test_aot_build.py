"""AOT build step (SPEC N-17, #448, #451): `python -m typedpython aot-build`.

A single-module program with an `if __name__ == "__main__":` entry (scalar functions, native lists,
fixed tuples of scalars, f-strings in `print`, `len(sys.argv)`) is compiled to an executable
that contains no CPython. The differential tests run the executable beside the same file under
`sys.executable` (no `@compiled`, plain CPython): standard output and the exit status must be equal, and
for an uncaught exception the last line of standard error too. They need a C compiler (skipped without
one) and CPython 3.14 or later (the messages of 3.14 and 3.15). The rejection table and the generated-C
text checks need no compiler (they need Pyrefly, like test_aot_check.py).

Executables built here (each one runs the type inferencer and the C compiler, which is what made the
windows job slow): FIVE in all, 3 without the measurement: the merged program (through the command
line), the exception selector program (one executable, the case chosen by `len(sys.argv)`, run once
per case with that many dummy arguments), a variant of nbody (size fixed in the source), and the two size programs of the measurement. #451's first half
built 12 (the exceptions were 9 programs); before it, 22.

Under the CI sanitizer job (TP_SANITIZE=1) every executable is built with ASan and UBSan
(`aot/cc.py`), and `exe_env` runs it with the leak checker on.

Everything here is an agent proposal, not owner-confirmed.
"""
import io
import os
import re
import shutil
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
SELECTOR_ORDER = sorted(RAISING)


def selector_source() -> str:
    """One program: every case of RAISING as a function, and the entry that runs the one `len(sys.argv)`
    selects (case i when the program has i + 1 arguments, itself included)."""
    parts = ["import math\nimport sys\n"]
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
    """The dummy arguments that select the case: `len(sys.argv)` is its index plus one."""
    return ["x"] * SELECTOR_ORDER.index(name)


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
    }
    for name, line in want.items():
        args = case_args(name)
        ours = run_exe(selector.exe, args)
        assert last_line(ours.stderr) == line, name
        assert ours.returncode == 1, name
        assert last_line(run_py(selector.src, args).stderr) == line, name


# --- nbody: a benchmark as a whole program ---------------------------------------------------------
#
# benchmarks/typedpython/py/nbody.py itself is refused: `n: int = int(sys.argv[1])` is `int()` of a string,
# whose value is not proved to stay inside 64 bits (big integers arrive in a later step), and that is its
# ONLY finding. A variant written into tmp_path with that line replaced by `n: int = 1000` (the benchmark
# file is not touched) is built and prints exactly what CPython prints for the same variant file.

ARGV_LINE = "    n: int = int(sys.argv[1])"


def nbody_variant(tmp_path) -> Path:
    source = (BENCH / "nbody.py").read_text()
    assert source.count(ARGV_LINE) == 1
    path = tmp_path / "nbody_variant.py"
    path.write_text(source.replace(ARGV_LINE, "    n: int = 1000"))
    return path


def test_nbody_is_refused_only_for_int_of_argv():
    source = (BENCH / "nbody.py").read_text()
    line = source.splitlines().index(ARGV_LINE) + 1
    with pytest.raises(aot.AotError) as raised:
        aot.generate(BENCH / "nbody.py")
    [f] = raised.value.findings
    assert (f.rule, f.line) == ("aot/int-unproven", line)
    assert "`int(sys.argv[1])` is not proved to stay inside 64 bits" in f.message and "later step" in f.message


@pytest.fixture(scope="module")
def nbody(tmp_path_factory):
    need_cc()
    tmp = tmp_path_factory.mktemp("nbody")
    src = nbody_variant(tmp)
    return SimpleNamespace(src=src, exe=aot.build(src, tmp / "nbody"))


def test_nbody_prints_what_cpython_prints(nbody):
    ours, theirs = run_exe(nbody.exe), run_py(nbody.src)
    assert theirs.returncode == 0, text(theirs.stderr)
    assert re.fullmatch(r"-?\d+\.\d{9} -?\d+\.\d{9}\n", text(theirs.stdout)), text(theirs.stdout)
    assert text(ours.stdout) == text(theirs.stdout)
    assert ours.returncode == 0 and text(ours.stderr) == ""


def test_nbody_generates_c_with_one_cleanup_point_per_function(tmp_path):
    c = aot.generate(nbody_variant(tmp_path))
    bodies = re.findall(r"^static int tpf_\w+\([^)]*\)\n\{.*?^\}", c, flags=re.S | re.M)
    assert len(bodies) == 12                  # the eleven functions of nbody.py and the entry
    for body in bodies:
        assert body.count("tp_cleanup:") <= 1
        assert "return -1" not in body and "return 0" not in body, "a function leaves through its cleanup point only"
    assert "tpa_f64_release(v_x)" in c and "tpf_make_x(&" in c


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
    "string variable": ("def f() -> None:\n    s = 'x'\n", ("aot/str", 2, "str")),
    "f-string as a value": ("def f(x: int) -> None:\n    s = f'{x}'\n", ("aot/f-string", 2, "f-string")),
    "print keyword": ("def f() -> None:\n    print(1, end='')\n", ("aot/print-keyword", 2, "end")),
    "print as a value": ("def f() -> None:\n    x = print(1)\n", ("aot/print-use", 2, "print")),
    "class": ("class C:\n    pass\n", ("aot/module-statement", 1, "class")),
    "unknown builtin": ("def f(x: float) -> int:\n    return round(x)\n", ("aot/builtin", 2, "round")),
    "int builtin": ("def f(x: float) -> int:\n    return int(x)\n", ("aot/builtin", 2, "int")),
    "math attribute": ("import math\ndef f() -> float:\n    return math.pi\n", ("aot/attribute", 3, "pi")),
    "math function": ("import math\ndef f(x: float) -> float:\n    return math.floor(x)\n",
                      ("aot/math", 3, "math.floor")),
    "bitwise operator": ("def f(a: int) -> int:\n    return a & 3\n", ("aot/operator", 2, "BitAnd")),
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
    "list method": ("def f(a: list[float]) -> None:\n    a.append(1.0)\n", ("aot/call", 2, "a.append")),
    "list iteration": (
        "def f(a: list[float]) -> float:\n    s = 0.0\n    for v in a:\n        s += v\n    return s\n",
        ("aot/statement", 3, "for")),
    "list slice": ("def f(a: list[float]) -> int:\n    b = a[1:]\n    return 0\n", ("aot/container", 2, "slice")),
    "list comparison": ("def f(a: list[float], b: list[float]) -> bool:\n    return a == b\n", ("aot/front-end", 2, "f")),
    "list printed": ("def f(a: list[float]) -> None:\n    print(a)\n", ("aot/front-end", 2, "f")),
    "list in an f-string": ("def f(a: list[float]) -> None:\n    print(f'{a}')\n", ("aot/front-end", 2, "f")),
    "list module constant": ("A = [1.0]\n", ("aot/constant", 1, "A")),
    "list addition": ("def f(a: list[float]) -> int:\n    b = a + a\n    return 0\n", ("aot/front-end", 2, "f")),
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
    "sys exit": ("import sys\ndef f() -> None:\n    sys.exit(1)\n", ("aot/call", 3, "sys.exit")),
    "sys attribute": ("import sys\ndef f() -> int:\n    return sys.maxsize\n", ("aot/attribute", 3, "maxsize")),
    "sys argv slice": ("import sys\ndef f() -> int:\n    x = sys.argv[1:]\n    return 0\n", ("aot/container", 3, "slice")),
    "int of an argument": ("import sys\ndef f() -> int:\n    return int(sys.argv[1])\n", ("aot/int-unproven", 3, "int")),
    "int of argument zero": ("import sys\ndef f() -> int:\n    return int(sys.argv[0])\n", ("aot/int-unproven", 3, "int")),
    "sys argv as a string": ("import sys\ndef f() -> None:\n    x = sys.argv[1]\n", ("aot/container", 3, "subscript")),
    "sys argv with a float index": (
        "import sys\ndef f(x: float) -> int:\n    return int(sys.argv[x])\n", ("aot/front-end", 3, "f")),
    "int of a string literal": ("def f() -> int:\n    return int('5')\n", ("aot/builtin", 2, "int")),
    "min of three": ("def f(a: float) -> float:\n    return min(a, a, a)\n", ("aot/call", 2, "min")),
    "min of an int and a float": ("def f(a: int, b: float) -> float:\n    return min(a, b)\n", ("aot/front-end", 2, "f")),
    "abs of the most negative int": ("def f(a: int) -> int:\n    return abs(a)\n", ("aot/int-unproven", 2, "-")),
    "f-string conversion": ("def f(x: float) -> None:\n    print(f'{x!r}')\n", ("aot/f-string", 2, "conversion")),
    "f-string equals": ("def f(x: float) -> None:\n    print(f'{x=}')\n", ("aot/f-string", 2, "conversion")),
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
    "bitwise operator",
    "class",
    "decorator",
    "empty list display",
    "f-string as a value",
    "f-string conversion",
    "f-string equals",
    "f-string nested field",
    "f-string other type",
    "f-string precision too large",
    "f-string width",
    "float annotation on an int value",
    "from import",
    "import twice",
    "int annotation on a bool value",
    "int builtin",
    "int literal outside 64 bits",
    "int of a string literal",
    "list in a tuple return",
    "list iteration",
    "list method",
    "list module constant",
    "list of bool annotation",
    "list of lists annotation",
    "list slice",
    "math attribute",
    "math function",
    "min of three",
    "missing annotation",
    "module constant outside 64 bits",
    "module constant that is a call",
    "module loop",
    "module statement",
    "print as a value",
    "print keyword",
    "second import",
    "string variable",
    "sys argv as a string",
    "sys argv slice",
    "sys attribute",
    "sys exit",
    "tuple annotation of a parameter",
    "tuple display",
    "tuple unpack of a display",
    "unknown builtin",
})
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
            "later": [n for n in rows if n not in SOURCE_LEVEL_ROWS]}


@pytest.fixture(scope="module")
def rejections(tmp_path_factory):
    """Each merged program generated once: row name -> (the finding to look for, the program's findings)."""
    results = {}
    for group, names in rejection_groups().items():
        source, expected = merged_rejection_source(names)
        path = write(tmp_path_factory.mktemp(f"rejected_{group}"), source)
        try:
            aot.generate(path)
            findings = None
        except aot.AotError as e:
            findings = e.findings
        for name in names:
            results[name] = (expected[name], findings)
    return results


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
        aot.generate(path)
    found = {(f.rule, f.line, f.name) for f in raised.value.findings}
    assert {("aot/int-unproven", 2, "+"), ("aot/int-unproven", 5, "*"), ("aot/front-end", 8, "c"),
            ("aot/int-unproven", 12, "-"), ("aot/front-end", 15, "e")} <= found, found


# spectral_norm.py and fannkuch.py need big integers (a later step): they are refused, and ONLY for an
# int operation not proved inside 64 bits. Everything else they use (lists made by `[v] * n`,
# `list(range(n))` and `a[:]`, list parameters, a returned tuple and its unpacking, f-strings with `.9f`,
# `math.sqrt`; `int(sys.argv[1])` is one more of the refused ints) is supported, so no other finding may appear. The exact set of
# (line, expression) is pinned: a change of these files or of the proofs shows here.
INT_UNPROVEN = {
    "spectral_norm.py": {
        (6, "(i + j) * (i + j + 1)"), (6, "i + j"), (6, "i + j + 1"), (6, "(i + j) * (i + j + 1) // 2 + i"),
        (6, "(i + j) * (i + j + 1) // 2 + i + 1"), (31, "int(sys.argv[1])")},
    "fannkuch.py": {
        (13, "r - 1"), (14, "r -= 1"), (27, "flips += 1"), (32, "checksum += flips"), (34, "checksum -= flips"),
        (42, "count[r] -= 1"), (45, "r += 1"), (46, "perm_count += 1"), (50, "int(sys.argv[1])")},
}


@pytest.mark.parametrize("name", sorted(INT_UNPROVEN))
def test_spectral_norm_and_fannkuch_are_refused_only_for_unproved_ints(name):
    with pytest.raises(aot.AotError) as raised:
        aot.generate(BENCH / name)
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


def test_generated_c_contains_no_cpython(tmp_path):
    c = aot.generate(write(tmp_path, merged_source(["math_functions"])))
    header = HEADER.read_text()
    for what, body in (("generated C", c), ("tp_aot.h", header)):
        assert "Python.h" not in body, what
        assert not re.search(r"\b_?Py[A-Z_a-z]", body), (what, re.findall(r"\b_?Py[A-Z_a-z]\w*", body))
    assert '#include "tp_aot.h"' in c
    for inc in re.findall(r"#include\s*[<\"]([^>\"]+)", c + header):
        # (stdatomic.h and intrin.h: the library build's atomic list counts, tp_aot.h under -DTPA_LIBRARY, #455)
        assert inc in {"tp_aot.h", "errno.h", "math.h", "stdint.h", "stdio.h", "stdlib.h", "string.h",
                       "stdatomic.h", "intrin.h"}, inc


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
