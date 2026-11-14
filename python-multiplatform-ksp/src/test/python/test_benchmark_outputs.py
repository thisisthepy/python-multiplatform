"""TypedPython: the five benchmarks print exactly what the interpreter prints (#41, M2 criterion).

The M2 criterion of #41 asks for a record that the compiled benchmarks give output identical to
CPython. `benchmarks/typedpython/run.py verify --typedpython` checks that too, but it also needs
Node, Java and Rust, so it does not run in the TypedPython CI. This file runs the same comparison
inside that CI, on every push to develop, so the record carries the commit and date of each run.

For each benchmark: the source with `@compiled` above every top-level def and class is compiled
through `pipeline.compile_module`; its `main()` runs at the size `run.py verify` uses, and its stdout
is compared with the plain source run by the interpreter in the same process. The compiled-function
list is printed (`pytest -s` shows it) so a run also records what was compiled; the list itself is
pinned by `test_class_methods.py::test_the_benchmarks_compile_the_same_functions_as_before`.

What this cannot find: a difference that only shows at the benchmark size, or only under concurrent
callers. The differential tests listed in docs/design/typedpython.md cover the arithmetic edge cases.

Written after the implementation (a record of existing behaviour, not a red/green test): it passes on
develop today, and a mismatch is a regression.
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import sys
from pathlib import Path

import pytest

from conftest import decorate_compiled
from typedpython import pipeline

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
BENCH = REPO / "benchmarks" / "typedpython" / "py"

# name -> (source file, size), the verify sizes of benchmarks/typedpython/run.py BENCHMARKS
SIZES = {
    "nbody": ("nbody.py", "5000"),
    "spectral-norm": ("spectral_norm.py", "100"),
    "fannkuch-redux": ("fannkuch.py", "7"),
    "binary-trees": ("binary_trees.py", "10"),
    "wordfreq": ("wordfreq.py", "100000"),
}


def run_main(module, name: str, size: str) -> str:
    old = sys.argv
    sys.argv = [name, size]
    buf = io.StringIO()
    try:
        with contextlib.redirect_stdout(buf):
            module.main()
    finally:
        sys.argv = old
    return buf.getvalue()


def load_plain(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.mark.parametrize("name", sorted(SIZES))
def test_compiled_benchmark_output_equals_the_interpreter(tmp_path, name):
    py, size = SIZES[name]
    source = (BENCH / py).read_text()
    stem = py.removesuffix(".py")

    plain_path = tmp_path / f"{stem}_ref.py"
    plain_path.write_text(source)
    expected = run_main(load_plain(plain_path, f"{stem}_ref"), name, size)
    assert expected.strip(), f"{name}: the interpreted run printed nothing"

    path = tmp_path / py
    path.write_text(decorate_compiled(source))
    result = pipeline.compile_module(path, tmp_path / "out")
    compiled = sorted(f.name for f in result.module.functions)
    print(f"\n{name}: compiled {compiled}, skipped {sorted(result.skipped)}")
    if result.extension is None:
        # nothing compiled (nbody on free-threaded builds until #255): the comparison would be
        # interpreter against interpreter, so it is reported, not counted as a pass
        assert compiled == []
        pytest.skip(f"{name}: no function compiled on this build, nothing to compare")

    module = pipeline.load(result)
    before = getattr(module, "__typedpython_deopts__", None)
    assert run_main(module, name, size) == expected
    print(f"{name}: output identical, deopts {before} -> {getattr(module, '__typedpython_deopts__', None)}")
