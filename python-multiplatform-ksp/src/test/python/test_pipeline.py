"""End to end: source -> frontend -> verify -> cgen -> cbuild -> import (SPEC N-8, #41)."""
import importlib.util
import os
import subprocess
import sys
from pathlib import Path

import pytest

from typedpython import pipeline

SRC = Path(__file__).parents[2] / "main" / "python"


def interpreted(path: Path):
    spec = importlib.util.spec_from_file_location(path.stem + "_interp", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


SCALAR = """\


@compiled
def total(n: int) -> float:
    acc: float = 0.0
    for k in range(n):
        acc += k * 0.5
    return acc


@compiled
def fact(n: int) -> int:
    r: int = 1
    for k in range(2, n + 1):
        r = r * k
    return r
"""


def test_scalar_module_compiles_and_matches_cpython(write, tmp_path):
    path = write("scalar.py", SCALAR)
    result = pipeline.compile_module(path, tmp_path / "out")
    assert result.extension is not None, result.skipped
    assert {f.name for f in result.module.functions} >= {"total", "fact"}, result.skipped
    compiled = pipeline.load(result)
    ref = interpreted(path)
    assert compiled.total(1000) == ref.total(1000)
    assert compiled.fact(30) == ref.fact(30)              # promotes past i64
    assert compiled.__typedpython_deopts__ >= 1


def test_unsupported_functions_stay_interpreted_with_a_reason(write, tmp_path):
    path = write("mixed.py", SCALAR + """

@compiled
def greet(name: str) -> str:
    return name[1:]
""")
    result = pipeline.compile_module(path, tmp_path / "out")
    assert "greet" in result.skipped and result.skipped["greet"]
    assert pipeline.load(result).greet("xy") == "y"


def test_a_module_with_nothing_to_compile_produces_no_extension(write, tmp_path):
    path = write("plain.py", "def f(x: int) -> int:\n    return x\n")
    result = pipeline.compile_module(path, tmp_path / "out")
    assert result.extension is None


def test_gate_errors_block_compilation(write, tmp_path):
    path = write("bad.py", "@compiled\ndef f(x):\n    return x\n")
    with pytest.raises(pipeline.CompileError, match="implicit-any-parameter"):
        pipeline.compile_module(path, tmp_path / "out")


DEMO = """\
import sys


@compiled
def total(n: int) -> float:
    acc: float = 0.0
    for k in range(n):
        acc += k * 0.5
    return acc


@compiled
def main() -> None:
    print(f"{total(int(sys.argv[1])):.3f}")
"""


def test_demo_reports_identical_output(write, tmp_path):
    path = write("demo_mod.py", DEMO)
    env = dict(os.environ, PYTHONPATH=str(SRC))
    result = subprocess.run(
        [sys.executable, "-m", "typedpython", "demo", "--out", str(tmp_path / "b"), str(path), "1000"],
        capture_output=True, text=True, env=env,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    assert "identical output" in result.stdout and "249750.000" in result.stdout
    assert "compiled functions: total" in result.stdout


ARRAY_CALLBACK = """\
from typing import Callable


@compiled
def first_after(xs: list[float], cb: Callable[[], None]) -> float:
    cb()
    return xs[0]


@compiled
def fill_then(xs: list[float], cb: Callable[[], None]) -> None:
    xs[1] = 5.0
    cb()
"""


def test_array_function_with_a_callback_matches_cpython(write, tmp_path):
    # #203: the callback changes the list; a copy-in would read or write back stale values
    path = write("arrcb.py", ARRAY_CALLBACK)
    result = pipeline.compile_module(path, tmp_path / "out")
    assert "first_after" in result.skipped and "fill_then" in result.skipped, result.skipped
    compiled, ref = pipeline.load(result), interpreted(path)
    for mod in (compiled, ref):
        xs = [1.0, 2.0, 3.0]
        def cb():
            xs[0] = 100.0
            xs[1] = 7.0
        assert mod.first_after(xs, cb) == 100.0
        ys = [1.0, 2.0, 3.0]
        def cb2():
            ys[1] = 9.0
        mod.fill_then(ys, cb2)
        assert ys == [1.0, 9.0, 3.0]


GC_REACHABLE = """\


@compiled
class Cell:
    a: object
    b: object
    __slots__ = ("a", "b")

    def __init__(self, a: object, b: object) -> None:
        self.a = a
        self.b = b


@compiled
def seta(c: Cell, v: object) -> None:
    c.a = v


@compiled
def fresh(a: object, n: int) -> object:
    c = Cell(None, None)
    seta(c, a)
    s: object = None
    for i in range(n):
        s = c.a
    return s
"""


def test_a_fresh_local_does_not_exempt_the_callee_class_guard(tmp_path):
    # N-12: on free-threaded CPython another thread can reach even a just-allocated object
    # through gc.get_objects() and assign __class__, so the callee's `type(x) is C` entry guard
    # can fail. The impure caller must be skipped (verify/deopt-impure), never a CGenError.
    path = tmp_path / "gcreach.py"
    path.write_text(GC_REACHABLE)
    result = pipeline.compile_module(path, tmp_path / "out", cost_rule=False)
    assert "fresh" in result.skipped, result.skipped
    assert "deopt" in result.skipped["fresh"], result.skipped["fresh"]
    assert "seta" in [f.name for f in result.module.functions]
    mod = pipeline.load(result)
    c = mod.Cell(None, None)
    mod.seta(c, 7)
    assert c.a == 7
