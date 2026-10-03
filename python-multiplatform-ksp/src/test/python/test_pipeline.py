"""End to end: source -> frontend -> verify -> cgen -> cbuild -> import (SPEC N-7, #41)."""
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
# typedpython: compiled


def total(n: int) -> float:
    acc: float = 0.0
    for k in range(n):
        acc += k * 0.5
    return acc


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

def greet(name: str) -> str:
    return "hi " + name
""")
    result = pipeline.compile_module(path, tmp_path / "out")
    assert "greet" in result.skipped and result.skipped["greet"]
    assert pipeline.load(result).greet("x") == "hi x"


def test_a_module_with_nothing_to_compile_produces_no_extension(write, tmp_path):
    path = write("plain.py", "def f(x: int) -> int:\n    return x\n")
    result = pipeline.compile_module(path, tmp_path / "out")
    assert result.extension is None


def test_gate_errors_block_compilation(write, tmp_path):
    path = write("bad.py", "# typedpython: compiled\n\ndef f(x):\n    return x\n")
    with pytest.raises(pipeline.CompileError, match="implicit-any-parameter"):
        pipeline.compile_module(path, tmp_path / "out")


DEMO = """\
# typedpython: compiled
import sys


def total(n: int) -> float:
    acc: float = 0.0
    for k in range(n):
        acc += k * 0.5
    return acc


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
