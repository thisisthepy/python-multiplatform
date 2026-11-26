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
])
def test_what_a_comprehension_cannot_do_yet_is_refused_with_the_reason(tmp_path: Path, body: str, reason: str):
    found = _rules(tmp_path, "def f() -> None:\n" + body + MAIN)
    assert any(reason in r or reason in m for r, m in found), found
