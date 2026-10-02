"""`@typedpython.compiled` → Cython → a desktop CPython extension (design §4.3 stage 1, #23).

Semantics must not change (INTENT §1.4): every compiled function returns what the interpreted
one returns, including Python's arbitrary-precision `int`.
"""
import importlib
import sys

import pytest

import typedpython
from typedpython import compiler

cython = pytest.importorskip("Cython", reason="compiling needs the [compile] extra")


def build(write, tmp_path, name, source):
    path = write(f"{name}.py", source)
    return compiler.compile_module(path, tmp_path / "out")


def test_decorator_is_the_identity_in_cpython():
    def f(x: int) -> int:
        return x

    class C:
        pass

    assert typedpython.compiled(f) is f
    assert typedpython.compiled(C) is C


SCALAR = """
    import typedpython

    @typedpython.compiled
    def total(n: int) -> float:
        acc: float = 0.0
        for k in range(n):
            acc += k * 0.5
        return acc
"""


def test_compiled_module_is_an_extension_with_the_same_result(write, tmp_path):
    built = build(write, tmp_path, "scalar", SCALAR)
    assert built.extension.suffix in (".so", ".pyd")
    module = built.load()
    assert module.__file__ == str(built.extension)
    interpreted = built.load_interpreted()
    assert module.total(1000) == interpreted.total(1000)


FACT = """
    import typedpython

    @typedpython.compiled
    def fact(n: int) -> int:
        r = 1
        for k in range(2, n + 1):
            r *= k
        return r
"""


def test_int_overflow_promotes_to_a_big_integer(write, tmp_path):
    module = build(write, tmp_path, "fact", FACT).load()
    assert module.fact(10) == 3628800
    assert module.__typedpython_deopts__ == 0
    assert module.fact(30) == 265252859812191058636308480000000   # past 2**63
    assert module.__typedpython_deopts__ == 1


STORE = """
    import typedpython

    @typedpython.compiled
    def store(xs: list[int], n: int) -> None:
        xs.append(n)
"""


def test_a_big_int_argument_takes_the_interpreted_path_before_any_effect(write, tmp_path):
    module = build(write, tmp_path, "store", STORE).load()
    xs: list[int] = []
    module.store(xs, 2**70)
    assert xs == [2**70]          # appended exactly once


def test_gate_errors_block_compilation(write, tmp_path):
    path = write("bad.py", "import typedpython\n\n@typedpython.compiled\ndef f(x):\n    return x\n")
    with pytest.raises(compiler.CompileError) as raised:
        compiler.compile_module(path, tmp_path / "out")
    assert "implicit-any-parameter" in str(raised.value)


def test_module_marker_compiles_every_function(write, tmp_path):
    built = build(write, tmp_path, "marked", """
        # typedpython: compiled

        def half(n: int) -> float:
            return n / 2
    """)
    assert "half" in built.typed_functions


def test_undecorated_functions_still_work(write, tmp_path):
    built = build(write, tmp_path, "mixed", SCALAR + """

    def plain(n: int) -> int:
        return n + 1
    """)
    assert built.typed_functions == ["total"]
    assert built.load().plain(1) == 2


FLOATS = """
    import math
    import typedpython

    @typedpython.compiled
    def scale(xs: list[float], k: float) -> None:
        for i in range(len(xs)):
            xs[i] *= k
            xs[i] = math.sqrt(xs[i])
"""


def test_float_lists_keep_python_semantics(write, tmp_path):
    built = build(write, tmp_path, "floats", FLOATS)
    module, interpreted = built.load(), built.load_interpreted()
    a, b = [1.0, 4.0, 9.0], [1.0, 4.0, 9.0]
    module.scale(a, 4.0)
    interpreted.scale(b, 4.0)
    assert a == b and all(type(v) is float for v in a)


def test_an_int_element_takes_the_interpreted_path(write, tmp_path):
    module = build(write, tmp_path, "floats_int", FLOATS).load()
    xs = [1.0, 4]                         # list[float] admits an int; CPython keeps computing with it
    module.scale(xs, 1.0)
    assert xs == [1.0, 2.0]
    assert module.__typedpython_deopts__ == 1


def test_sqrt_of_a_negative_raises_like_cpython(write, tmp_path):
    import pytest
    module = build(write, tmp_path, "floats_neg", FLOATS).load()
    with pytest.raises(ValueError, match="math domain error"):
        module.scale([-1.0], 1.0)
