"""A small library for `AotLibraryCallTest`: one function per boundary shape and per failure.

Compiled with `python -m typedpython aot-lib` (no CPython in the result) and also run by plain CPython,
which is where the test's expected exception classes and messages come from.
"""


def rem(a: int, b: int) -> int:
    return a % b


def at(v: list[float], i: int) -> float:
    return v[i]


def triple(a: int) -> tuple[int, float, bool]:
    return a // 3, float(a) / 2.0, a > 0


def flip(flag: bool) -> bool:
    return not flag


def largest(w: list[int]) -> int:
    m = w[0]
    for i in range(len(w)):
        m = max(m, w[i])
    return m


def same_list(v: list[float]) -> list[float]:
    return v


def count_up(n: int) -> list[int]:
    return list(range(n))


def set_first(v: list[float], x: float) -> None:
    v[0] = x


def keywords(val: float, object: float) -> float:
    return val - object


def alias_effect(a: list[float], b: list[float]) -> None:
    a[0] = b[0] + 1.0
    b[0] = a[0] * 2.0
