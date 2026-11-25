"""A small library for `AotLibraryCallTest`: dicts across the boundary (ABI version 3, #443, tier S2b).

`dict[int, int]`, `dict[int, float]` and `dict[int, bool]` parameters and results (Kotlin: DictI64I64, DictI64F64,
DictI64Bool). The library only reads a dict parameter (a function that writes one is not exported), so the Kotlin
classes are read-only. Compiled with `python -m typedpython aot-lib` and also run by plain CPython, where the test's
expected values come from. Agent proposal, not owner-confirmed.
"""


def counts(xs: list[int]) -> dict[int, int]:
    d: dict[int, int] = {}
    for x in xs:
        d[x] = d.get(x, 0) + 1
    return d


def total(d: dict[int, int]) -> int:
    s = 0
    for k, v in d.items():
        s += k * v
    return s


def lookup(d: dict[int, int], k: int) -> int:
    return d[k]


def scaled(d: dict[int, float], f: float) -> dict[int, float]:
    out: dict[int, float] = {}
    for k in d:
        out[k] = d[k] * f
    return out


def evens(n: int) -> dict[int, bool]:
    out: dict[int, bool] = {}
    for i in range(n):
        out[i] = i % 2 == 0
    return out


def same(d: dict[int, bool]) -> dict[int, bool]:
    return d


def big(n: int) -> dict[int, int]:
    v = 1
    for _ in range(n):
        v *= 2
    d: dict[int, int] = {}
    d[7] = v
    return d
