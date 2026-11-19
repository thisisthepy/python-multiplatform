"""Tier K PR5 (#476, SPEC N-17): bitwise/shift, power, int()/bool(), sys.exit/maxsize, math, time.

Differential tests against CPython 3.14+ (stdout, exit code, and last line of stderr on uncaught
exceptions), plus `aot-lib` rejection of `sys.exit`.
"""
import shutil
import subprocess
import sys
import textwrap
from pathlib import Path

import pytest

from typedpython import aot, cbuild
from typedpython.aot import cc, program


def _need_cc() -> None:
    if sys.version_info < (3, 14):
        pytest.skip("the AOT runtime carries the exception messages of CPython 3.14 and later")
    if not (cc.RUNTIME_DIR / "tp_aot.h").is_file():
        pytest.skip("tp_aot.h does not exist")
    try:
        if cbuild._is_msvc():
            cbuild.msvc_env()
        elif shutil.which(cbuild.compiler()[0]) is None:
            pytest.skip("no C compiler on PATH")
    except Exception as e:
        pytest.skip(f"no C compiler: {e}")


def _build_and_compare(tmp_path: Path, source: str, argv_cases: list[list[str]]) -> None:
    _need_cc()
    src = tmp_path / "prog.py"
    src.write_text(textwrap.dedent(source).lstrip(), encoding="utf-8")
    exe = tmp_path / ("prog.exe" if sys.platform == "win32" else "prog")
    res = aot.build(src, exe)
    assert res.ok, f"aot.build failed: {res.findings}"
    for args in argv_cases:
        py = subprocess.run([sys.executable, str(src), *args], capture_output=True, text=True)
        nat = subprocess.run([str(exe), *args], capture_output=True, text=True)
        assert nat.returncode == py.returncode, f"args={args}: rc {nat.returncode} != {py.returncode}\nstderr={nat.stderr}"
        assert nat.stdout == py.stdout, f"args={args}: stdout mismatch\nAOT: {nat.stdout!r}\nPY : {py.stdout!r}"
        if py.returncode != 0:
            py_last = py.stderr.strip().splitlines()[-1] if py.stderr.strip() else ""
            nat_last = nat.stderr.strip().splitlines()[-1] if nat.stderr.strip() else ""
            assert nat_last == py_last, f"args={args}: stderr mismatch\nAOT: {nat_last!r}\nPY : {py_last!r}"


def test_tier_k5_bitwise_shift_pow_builtins_math(tmp_path: Path) -> None:
    src = """
    import math
    import sys

    def bitwise_ops(a: int, b: int) -> int:
        x: int = (a & b) | (a ^ b)
        x &= ~b
        x |= a
        x ^= b
        return x

    def shift_ops(a: int, s: int) -> int:
        y: int = a << s
        y >>= 2
        return y

    def pow_int(a: int, b: int) -> int:
        r: int = a ** b
        r **= 2
        return r

    def pow_float(a: float, b: float) -> float:
        return a ** b

    if __name__ == "__main__":
        mode: int = int(sys.argv[1])
        if mode == 1:
            print(bitwise_ops(42, 15))
            print(bitwise_ops(-42, 15))
            print(bitwise_ops(-42, -15))
            print(~0, ~1, ~(-1), ~True, ~False)
            print(True & False, True | False, True ^ True, True << 3, True >> 1)
        elif mode == 2:
            print(shift_ops(1, 70))
            print(shift_ops(-1, 70))
            print((-100) >> 3, (-1) >> 100, 100 >> 100)
            big: int = 1 << 100
            print(big & ((1 << 50) - 1))
            print((big | 7) ^ 3)
            print(~big)
        elif mode == 3:
            print(pow_int(2, 10))
            print(pow_int(2, 40))
            print(pow_int(-3, 5))
            print(0 ** 0, 1 ** 1000, (-1) ** 99, (-1) ** 100)
            print(pow_float(2.0, 3.0), pow_float(2.0, -2.0), 2 ** -3)
        elif mode == 4:
            print(int(3.9), int(-3.9), int(0.0), int(-0.0))
            print(int(1e30), int(-1e30))
            print(int(True), int(False), int(42))
            print(int("123456789012345678901234567890"))
            print(int("-99999999999999999999999999"))
            xs: list[int] = [1, 2, 3]
            print(bool(0), bool(1), bool(-5), bool(0.0), bool(0.5), bool(math.nan), bool(xs))
            print(sys.maxsize)
        elif mode == 5:
            print(math.floor(3.7), math.floor(-3.7), math.floor(1e25))
            print(math.ceil(3.2), math.ceil(-3.2), math.ceil(-1e25))
            print(math.trunc(3.9), math.trunc(-3.9))
            print(math.pow(2.0, 10.0), math.log2(8.0), math.log10(1000.0))
            print(f"{math.asin(0.5):.6f}", f"{math.acos(0.5):.6f}", f"{math.atan(1.0):.6f}")
            print(f"{math.sinh(1.0):.6f}", f"{math.cosh(1.0):.6f}", f"{math.tanh(1.0):.6f}")
            print(math.isnan(math.nan), math.isnan(1.0), math.isinf(math.inf), math.isinf(-math.inf))
            print(math.isfinite(1.0), math.isfinite(math.inf), math.isfinite(math.nan))
            print(math.copysign(3.0, -1.0), math.fmod(5.5, 2.0))
            print(f"{math.pi:.6f}", f"{math.e:.6f}", f"{math.tau:.6f}")
        elif mode == 6:
            sys.exit(0)
        elif mode == 7:
            sys.exit(42)
        elif mode == 8:
            sys.exit(None)
        elif mode == 9:
            sys.exit()
    """
    _build_and_compare(tmp_path, src, [[str(i)] for i in range(1, 10)])


def test_tier_k5_exceptions(tmp_path: Path) -> None:
    src = """
    import math
    import sys
    import time

    if __name__ == "__main__":
        mode: int = int(sys.argv[1])
        if mode == 1:
            print(1 << -1)
        elif mode == 2:
            print(1 >> -1)
        elif mode == 3:
            print(0.0 ** -1.0)
        elif mode == 4:
            print(int(math.nan))
        elif mode == 5:
            print(int(math.inf))
        elif mode == 6:
            print(math.floor(math.nan))
        elif mode == 7:
            print(math.ceil(math.inf))
        elif mode == 8:
            print(math.log2(-1.0))
        elif mode == 9:
            print(math.asin(2.0))
        elif mode == 10:
            print(math.fmod(1.0, 0.0))
        elif mode == 11:
            print(math.pow(0.0, -1.0))
        elif mode == 12:
            print(int("not_a_number"))
        elif mode == 13:
            time.sleep(-1.0)
    """
    _build_and_compare(tmp_path, src, [[str(i)] for i in range(1, 14)])


def test_tier_k5_time_module(tmp_path: Path) -> None:
    _need_cc()
    src = tmp_path / "t.py"
    src.write_text(textwrap.dedent("""
        import time

        if __name__ == "__main__":
            t0: float = time.perf_counter()
            m0: float = time.monotonic()
            w0: float = time.time()
            time.sleep(0.005)
            t1: float = time.perf_counter()
            m1: float = time.monotonic()
            print(t1 >= t0, m1 >= m0, w0 > 1000000000.0)
    """).lstrip(), encoding="utf-8")
    exe = tmp_path / ("t.exe" if sys.platform == "win32" else "t")
    res = aot.build(src, exe)
    assert res.ok, f"aot.build failed: {res.findings}"
    out = subprocess.run([str(exe)], capture_output=True, text=True, check=True)
    assert out.stdout.strip() == "True True True"


def test_tier_k5_library_refuses_sys_exit(tmp_path: Path) -> None:
    src = tmp_path / "lib.py"
    src.write_text(textwrap.dedent("""
        import sys

        def f(x: int) -> int:
            sys.exit(x)
            return x
    """).lstrip(), encoding="utf-8")
    prog = program.analyze(src, library=True)
    rules = [f.rule for f in prog.findings]
    assert "aot/library-exit" in rules
