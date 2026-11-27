"""AOT mode, tier S2c (design 4.3.2): comprehensions, built without CPython and compared with CPython.

A list, set or dict comprehension that is the value of an assignment to a name or of a `return` is lowered as the loop
it means (typedpython/comprehensions.py): a hidden local per comprehension variable (Python 3 scoping: the enclosing
name of the same spelling is not touched), the accumulator built in place, the first iterable evaluated before
anything else, CPython's exceptions where CPython raises them. Agent proposal, not owner-confirmed.
"""
from __future__ import annotations

from pathlib import Path

import pytest

from test_aot_tier_s1 import _compare, _write
from typedpython import aot


COMPS = """
import sys


def evens(xs: list[int]) -> list[int]:
    return [x * 2 for x in xs if x % 2 == 0]


def lengths(words: list[str]) -> dict[str, int]:
    out = {w: len(w) for w in words}
    return out


def main() -> None:
    words = sys.argv[1].split(",")
    x = "outer"
    lens = [len(x) for x in words]
    print(lens, x)
    upper = [w.upper() for w in words if w]
    pairs = [i * j for i in range(4) for j in range(i) if j != 1]
    halves: list[float] = [i / 2 for i in range(4)]
    counts = lengths(words)
    firsts = {w[0] for w in words if w}
    print(upper, pairs, halves, counts, len(firsts), "a" in firsts, "q" in firsts)
    doubled = [h * 2 for h in halves if h != 0.5]
    print(doubled)
    d = {"a": 1, "b": 2}
    inv = {v: k for k, v in d.items()}
    vals = [v + 1 for v in d.values()]
    four = [1, 2, 3, 4]
    e4 = evens(four)
    e0 = evens(lens)
    print(inv, vals, e4, e0)
    xs = [1, 2, 3]
    xs = [x + 10 for x in xs]
    print(xs)
    chars = [c for c in "h\\u00e9llo" if c != "l"]
    none = [c for c in ""]
    print(chars, none, len(none))
    nested = [a + b for a in words for b in words if a < b]
    print(nested)
    nums = [int(t) for t in sys.argv[2].split(",")]
    print(nums)
    q = [100 // n for n in nums]
    print(q)


if __name__ == "__main__":
    main()
"""


def test_list_set_and_dict_comprehensions_are_the_loops_they_mean(tmp_path: Path):
    """Filters, several `for` clauses, a tuple target over items(), a comprehension over its own target's old value,
    Python 3 scoping (the outer `x` stays "outer") and the exception of an element (ZeroDivisionError) as in CPython."""
    _compare(tmp_path, COMPS, [["a,bb,ccc", "1,2,5"], ["b", "5"], ["x,y", "3,0"]])


LOOPS = """
import sys


def weigh(words: list[str], nums: list[int]) -> int:
    t = 0
    for w, n in zip(words, nums):
        t += len(w) * n
    return t


def main() -> None:
    words = sys.argv[1].split(",")
    nums = [int(t) for t in sys.argv[2].split(",")]
    i = 99
    for i, w in enumerate(words):
        print(i, w)
    print(i)
    for i, w in enumerate(words, 1):
        if i == 2:
            continue
        print(i, w, end=";")
    print()
    for i, c in enumerate("h\u00e9!", -1):
        print(i, c)
    d = {"a": 1, "b": 2}
    for i, k in enumerate(d):
        print(i, k)
    for i, v in enumerate(d.values()):
        print(i, v)
    for i, (k, v) in enumerate(d.items()):
        for j, w in enumerate(words):
            print(i, k, v, j, w, end=";")
    print()
    for i, j in enumerate(range(3, 6)):
        print(i * j)
    for w, n in zip(words, nums):
        print(w * n)
    print(weigh(words, nums))
    for a, b, c in zip(words, nums, "xyz"):
        print(a, b, c)
    idx = [i * 10 for i, w in enumerate(words) if w]
    both = {w: n for w, n in zip(words, nums)}
    print(idx, both)
    grow = [1, 2]
    for x, y in zip(grow, nums):
        grow.append(x + y)
        print(x, y)
    print(grow)
    for i, n in enumerate(nums):
        if i > 5:
            break
        print(100 // n)


if __name__ == "__main__":
    main()
"""


def test_enumerate_and_zip_are_the_loops_they_mean(tmp_path: Path):
    """`for i, x in enumerate(it, start)` counts beside the loop over `it` (a list, a str, a dict, a view, a range), the
    counter left at its last value as in CPython; `for a, b in zip(xs, ys)` reads each list or str by index up to the
    shortest, the length read at every step (a list that grows in the loop is seen growing, as CPython's iterator sees
    it); both in a `for` statement and in a comprehension. Agent proposal, not owner-confirmed."""
    _compare(tmp_path, LOOPS, [["a,bb,ccc", "1,2,5"], ["b", "5,6"], ["x,y", "3,0"]])


REVERSED = """
import sys


def last_first(ws: list[str]) -> str:
    out = ""
    for w in reversed(ws):
        out += w
    return out


def main() -> None:
    words = sys.argv[1].split(",")
    nums = [int(t) for t in sys.argv[2].split(",")]
    for w in reversed(words):
        print(w)
    for c in reversed("h\u00e9!"):
        print(c, end="")
    print()
    print(last_first(words))
    back = [n * 2 for n in reversed(nums) if n != 2]
    print(back)
    for i, n in enumerate(reversed(nums)):
        print(i, n)
    for w, n in zip(words, nums):
        for c in reversed(w):
            print(c * n, end=";")
    print()
    shrink = [1, 2, 3, 4]
    for x in reversed(shrink):
        print(x)
        if x == 4:
            shrink.pop()
            shrink.pop()
    print(shrink)
    grow = [1, 2]
    for x in reversed(grow):
        grow.append(x)
    print(grow)
    for n in reversed(nums):
        print(10 // n)


if __name__ == "__main__":
    main()
"""


def test_reversed_is_the_loop_it_means(tmp_path: Path):
    """`for x in reversed(xs)` of a list or a str reads it by index from its end, the index checked against the length
    at every step as CPython's reversed list iterator checks it: a list that shrinks in the loop ends it early, one
    that grows is not seen growing. In a `for` statement, in a comprehension and inside enumerate(). Agent proposal,
    not owner-confirmed."""
    _compare(tmp_path, REVERSED, [["a,bb,ccc", "1,2,5"], ["b", "5,6"], ["x,y", "3,0"]])


RANGES = """
import sys


def main() -> None:
    n = int(sys.argv[1])
    r = range(n)
    s = range(2, n + 3, 3)
    t = range(n, -2, -2)
    print(r, s, t, len(r), len(s), len(t))
    print(f"{r}|{s!r}|{t} end", str(s), repr(t))
    for x in s:
        print(x, end=" ")
    print()
    total = 0
    for x in t:
        total += x
    print(total)
    k = 4
    print(k in s, 5 in s, 3 in t, 2 in t, -1 not in t, n in r, 0 in r)
    if r:
        print("r has", len(r))
    if not t:
        print("t is empty")
    print(bool(s), not r)
    sq = [x * x for x in s if x != 5]
    print(sq)
    for i, x in enumerate(t):
        print(i, x, end=";")
    print()
    r = range(1, n)
    print(r, len(r), 1 in r, bool(r))


if __name__ == "__main__":
    main()
"""


def test_a_range_object_is_its_bounds(tmp_path: Path):
    """`r = range(a, b, STEP)` (a constant step) is held as its start and stop: a `for` loop over it, len(), `in`, its
    truth and its repr (print, str, repr, an f-string) are CPython's, for a positive and a negative step and an empty
    range. Agent proposal, not owner-confirmed."""
    _compare(tmp_path, RANGES, [["5"], ["0"], ["1"], ["-3"]])


SUMS = """
import sys


def total(xs: list[int]) -> int:
    return sum(xs)


def has(ws: list[str]) -> bool:
    return any(ws)


def main() -> None:
    nums = [int(t) for t in sys.argv[1].split(",")]
    fl = [int(t) / 10 for t in sys.argv[1].split(",")]
    s = sum(nums)
    s2 = sum(nums, 100)
    s3 = sum(nums, start=-1)
    print(s, s2, s3, total(nums))
    f = sum(fl, 0.0)
    neg = sum(fl, -0.0)
    tiny = [0.1] * 10
    t = sum(tiny, 0.0)
    big = [1e100, 1.0, -1e100]
    b = sum(big, 0.0)
    huge = [1e308, 1e308, -1.0]
    h = sum(huge, 0.0)
    print(f, neg, t, t == 1.0, b, h)
    d = {"a": 1, "b": 2}
    sv = sum(d.values())
    sr = sum(range(5))
    print(sv, sr)
    w = sys.argv[2].split(",")
    a1 = any(nums)
    a2 = all(nums)
    a3 = any(w)
    a4 = all(w)
    e1 = any(range(0))
    e2 = all(range(0))
    print(a1, a2, a3, a4, e1, e2, has(w))
    q = sum(range(100 // nums[0]))
    print(q)


if __name__ == "__main__":
    main()
"""


def test_sum_any_and_all_of_an_iterable_are_the_loops_they_mean(tmp_path: Path):
    """`sum(it[, start])`, `any(it)` and `all(it)` bound to a name or returned are the loop each means over any
    iterable a `for` loop takes: an int sum as CPython adds ints, a float sum (a float start) with CPython's compensated
    summation (Neumaier, `cs_add` in Python/bltinmodule.c: `sum([1e100, 1.0, -1e100], 0.0)` is 1.0), any() and all()
    stopping at the first true or false item. Agent proposal, not owner-confirmed."""
    _compare(tmp_path, SUMS, [["1,2,5", "a,,b"], ["0", ""], ["-3,4", "x"]])


def _rules(tmp_path: Path, source: str) -> set[tuple[str, str]]:
    with pytest.raises(aot.AotError) as raised:
        aot.generate(_write(tmp_path, source))
    return {(f.rule, f.message) for f in raised.value.findings}


MAIN = '\n\ndef main() -> None:\n    f()\n\n\nif __name__ == "__main__":\n    main()\n'


@pytest.mark.parametrize("body, reason", [
    # a comprehension is the value of an assignment to a name or of a return
    ("    xs = [1, 2]\n    print([x for x in xs])\n", "bind it to a name"),
    ("    xs = [1, 2]\n    n = len([x for x in xs])\n    print(n)\n", "bind it to a name"),
    # a generator expression is not a comprehension (no generators in an AOT program)
    ("    xs = [1, 2]\n    print(sum(x for x in xs))\n", "generator expression"),
    # what the loop it means cannot hold is refused as that loop is
    ("    xs = [1, 2]\n    ys = [x > 1 for x in xs]\n    print(ys)\n", "list[bool]"),
    # a name bound inside it (`:=`) leaks to the enclosing scope in CPython; `:=` is outside the AOT mode anyway
    ("    xs = [1, 2]\n    ys = [(y := x) for x in xs]\n    print(ys)\n", "NamedExpr"),
    # enumerate and zip are the iterable of a loop, unpacked into names
    ("    xs = [1, 2]\n    for t in enumerate(xs):\n        print(t)\n", "unpacked into"),
    ("    xs = [1, 2]\n    e = enumerate(xs)\n    print(e)\n", "enumerate"),
    ("    xs = [1, 2]\n    n = len(xs)\n    for i, x in enumerate(xs, n):\n        print(i, x)\n", "start"),
    ("    xs = [1, 2]\n    for a, b in zip(xs, xs, strict=True):\n        print(a, b)\n", "keyword"),
    ("    xs = [1, 2]\n    for a, b in zip(xs, [3]):\n        print(a, b)\n", "names"),
    ("    xs = [1, 2]\n    for a in zip(xs, xs):\n        print(a)\n", "unpacked into"),
    ("    xs = [1, 2]\n    ys = [3]\n    for a, b in zip(xs, ys):\n        xs = ys\n        print(a, b)\n", "rebinds"),
    ("    d = {1: 2}\n    xs = [1, 2]\n    for a, b in zip(d, xs):\n        print(a, b)\n", "a list or a str"),
    ("    d = {1: 2}\n    xs = [1, 2]\n    ys = [a + b for a, b in zip(d, xs)]\n    print(ys)\n", "a dict or a set"),
    # reversed() is the iterable of a loop, of a list or a str name the loop does not rebind
    ("    d = {1: 2}\n    for k in reversed(d):\n        print(k)\n", "a list or a str"),
    ("    d = {1: 2}\n    ks = [k for k in reversed(d)]\n    print(ks)\n", "a dict or a set"),
    ("    xs = [1, 2]\n    r = reversed(xs)\n    print(r)\n", "reversed"),
    ("    xs = [1, 2]\n    for x in reversed(xs):\n        xs = [3]\n        print(x)\n", "rebinds"),
    ("    xs = [1, 2]\n    for x in reversed(xs + xs):\n        print(x)\n", "names"),
    # a range object is used by a loop, len(), `in`, its truth and its repr; its step is an int constant
    ("    r = range(3)\n    print(list(r))\n", "a range object"),
    ("    r = range(3)\n    print(r[0])\n", "a range object"),
    ("    r = range(3)\n    r = 4\n    print(r)\n", "a range object"),
    ("    n = 2\n    r = range(0, 9, n)\n    print(len(r))\n", "step"),
    ("    r = range(1, 5, 0)\n    print(len(r))\n", "must not be zero"),
    ("    r = range(3)\n    xs = [1, 2]\n    for a, b in zip(r, xs):\n        print(a, b)\n", "a list or a str"),
    # sum(), any(), all() of an iterable are bound to a name or returned; a float sum has a float start
    ("    xs = [1, 2]\n    print(sum(xs))\n", "bind it to a name"),
    ("    xs = [1.5, 2.5]\n    s = sum(xs)\n    print(s)\n", "sum(xs, 0.0)"),
    ("    xs = ['a']\n    s = sum(xs)\n    print(s)\n", "sum() of"),
    ("    xs = [1.5, 2.5]\n    s: float = sum(xs)\n    print(s)\n", "sum(xs, 0.0)"),
    ("    xs = [1, 2]\n    s = any(xs, xs)\n    print(s)\n", "one argument"),
    ("    xs = [1, 2]\n    s = sum(x * 2 for x in xs)\n    print(s)\n", "generator expression"),
    # (found here: a comprehension over a list[float] was refused, and float() of a str reached the verifier)
    ("    w = '1.5'\n    x = float(w)\n    print(x)\n", "float() of a str"),
])
def test_what_a_comprehension_cannot_do_yet_is_refused_with_the_reason(tmp_path: Path, body: str, reason: str):
    found = _rules(tmp_path, "def f() -> None:\n" + body + MAIN)
    assert any(reason in r or reason in m for r, m in found), found
