"""A small library for `AotLibraryCallTest`: lists of ints that can leave 64 bits (ABI version 2, #465).

`fill_factorials`, `total` and `make` work on lists the compiler finds big-capable, so they cross the boundary as
`int_list`; `raw_sum` works on a list that never holds a value outside 64 bits and stays an `i64_list`. Compiled with
`python -m typedpython aot-lib` and also run by plain CPython, where the test's expected values come from.
"""


def fill_factorials(out: list[int]) -> None:
    f = 1
    for i in range(len(out)):
        f = f * (i + 1)
        out[i] = f


def make(n: int) -> list[int]:
    r = list(range(n))
    fill_factorials(r)
    return r


def total(xs: list[int]) -> int:
    s = 0
    for i in range(len(xs)):
        s += xs[i]
    return s


def total_of_factorials(n: int) -> int:
    r = make(n)
    return total(r)


def raw_sum(v: list[int]) -> int:
    s = 0
    for i in range(len(v)):
        s += v[i]
    return s
