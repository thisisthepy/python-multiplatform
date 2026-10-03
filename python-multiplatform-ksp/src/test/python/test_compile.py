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


# --- default arguments (design §6.2) -------------------------------------------------------------

DEFAULTS = """
    import typedpython

    CALLS = [0]

    def make_step() -> int:
        CALLS[0] += 1
        return 10 * CALLS[0]

    @typedpython.compiled
    def scaled(x: float, k: float = 2.0, n: int = 3) -> float:
        return x * k + n

    @typedpython.compiled
    def stepped(x: int, step: int = make_step()) -> int:
        return x + step
"""


def test_default_arguments_are_typed_and_keep_the_signature(write, tmp_path):
    built = build(write, tmp_path, "defaults", DEFAULTS)
    assert "scaled" in built.typed_functions and "scaled" not in built.untyped
    module, interpreted = built.load(), built.load_interpreted()
    assert module.scaled(1.5) == interpreted.scaled(1.5)                  # both defaults used
    assert module.scaled(1.5, 3.0) == interpreted.scaled(1.5, 3.0)        # one overridden
    assert module.scaled(1.5, 3.0, 7) == interpreted.scaled(1.5, 3.0, 7)  # both overridden
    assert module.scaled(1.5, n=9) == interpreted.scaled(1.5, n=9)        # by keyword
    assert module.__typedpython_deopts__ == 0


def test_a_default_of_the_wrong_runtime_type_takes_the_interpreted_path(write, tmp_path):
    module = build(write, tmp_path, "defaults_type", DEFAULTS).load()
    interpreted = module._tp_interpreted()
    assert module.scaled(1.0, 2) == interpreted.scaled(1.0, 2)    # int passed for a float parameter
    assert type(module.scaled(1.0, 2)) is type(interpreted.scaled(1.0, 2))
    assert module.__typedpython_deopts__ == 2


def test_defaults_are_evaluated_once_at_definition_time(write, tmp_path):
    module = build(write, tmp_path, "defaults_once", DEFAULTS).load()
    assert module.CALLS == [1]               # evaluated while the module loaded, not per call
    assert module.stepped(1) == 11
    assert module.stepped(2) == 12
    assert module.stepped(1, 5) == 6
    assert module.CALLS == [1]


# --- methods (design §6.2) -----------------------------------------------------------------------

METHODS = """
    # typedpython: compiled

    class Acc:
        def __init__(self, start: int) -> None:
            self.total = start

        def add(self, n: int) -> None:
            self.total += n

        def power(self, base: int, n: int) -> int:
            r = 1
            for _ in range(n):
                r *= base
            return r

        def scaled(self, x: float, k: float = 2.0) -> float:
            return x * k

        def is_peer(self, other: "Acc", n: int) -> bool:
            return isinstance(other, Acc)

        @staticmethod
        def twice(n: int) -> int:
            return n * 2
"""


def test_methods_are_typed_and_instances_stay_ordinary(write, tmp_path):
    built = build(write, tmp_path, "methods", METHODS)
    for name in ("Acc.__init__", "Acc.add", "Acc.power", "Acc.scaled", "Acc.is_peer"):
        assert name in built.typed_functions, name
    assert "Acc.twice" in built.untyped            # a decorated method is left alone
    module = built.load()
    acc = module.Acc(5)
    acc.dynamic = 1                                # not a cdef class: dynamic attributes still work
    assert acc.__dict__ == {"total": 5, "dynamic": 1}
    assert module.Acc.twice(4) == 8
    assert acc.scaled(1.5) == 3.0 and acc.scaled(1.5, 4.0) == 6.0


def test_a_pure_method_promotes_on_overflow_and_keeps_the_bound_instance(write, tmp_path):
    built = build(write, tmp_path, "methods_pure", METHODS)
    module, interpreted = built.load(), built.load_interpreted()
    acc = module.Acc(0)
    assert acc.power(2, 10) == 1024
    assert module.__typedpython_deopts__ == 0
    assert acc.power(10, 30) == interpreted.Acc(0).power(10, 30) == 10**30
    assert module.__typedpython_deopts__ == 1


def test_a_method_that_stores_into_self_takes_the_interpreted_path_before_any_effect(write, tmp_path):
    module = build(write, tmp_path, "methods_store", METHODS).load()
    acc = module.Acc(0)
    acc.add(2**70)
    acc.add(1)
    assert acc.total == 2**70 + 1        # each call took effect exactly once
    assert module.__typedpython_deopts__ == 1


def test_the_interpreted_fallback_sees_the_compiled_class(write, tmp_path):
    module = build(write, tmp_path, "methods_global", METHODS).load()
    assert module.Acc(0).is_peer(module.Acc(1), 2**70) is True
    assert module.__typedpython_deopts__ == 1


PROPERTY_READ = """
    # typedpython: compiled

    class P:
        def __init__(self) -> None:
            self.count = 0

        @property
        def tick(self) -> int:
            self.count += 1
            return self.count

        def calc(self, n: int) -> int:
            r = self.tick
            for _ in range(n):
                r *= 1 << 40
            return r
"""


def test_reading_an_attribute_of_self_is_not_pure(write, tmp_path):
    # `self.tick` is a property with an effect; redoing the call after an overflow would run it twice.
    built = build(write, tmp_path, "property_read", PROPERTY_READ)
    module, interpreted = built.load(), built.load_interpreted()
    a, b = module.P(), interpreted.P()
    assert a.calc(5) == b.calc(5)                 # 2**200: overflows a C long long
    assert a.count == b.count == 1


DECORATED = """
    import typedpython

    class K:
        @typedpython.compiled
        def half(self, n: int) -> float:
            return n / 2

        def plain(self, n: int) -> int:
            return n
"""


def test_a_decorated_method_is_typed_and_an_undecorated_one_is_not(write, tmp_path):
    built = build(write, tmp_path, "decorated_method", DECORATED)
    assert built.typed_functions == ["K.half"]
    assert built.load().K().half(3) == 1.5
