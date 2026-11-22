"""TypedPython AOT, tier S step 2a (#443): `str | None` and the not-None proofs it needs, also for `C | None` class
values (docs/design/typedpython-aot.md 4.3.2).

Differential against the reference interpreter, as test_aot_tier_s1.py (stdout compared as bytes, the exit status, the
last stderr line). Red before S2a: a `str | None` annotation is `aot/type` ("the type `str | None` is outside the AOT
mode"), and a field read through a `C | None` parameter compiles with no None check (a None crashes the program
instead of being refused at build time). The sanitizer job builds with -DTP_CHECK_PROVEN, where every read the front
end proved not None aborts on a NULL, so a wrong proof fails there.
"""
from __future__ import annotations

from pathlib import Path

import pytest

from test_aot_tier_s1 import _compare, _write
from typedpython import aot

OPTIONAL_STR = """
import sys


class Person:
    name: str
    nick: str | None

    def __init__(self, name: str, nick: str | None) -> None:
        self.name = name
        self.nick = nick


def find(words: list[str], prefix: str) -> str | None:
    for w in words:
        if w.startswith(prefix):
            return w
    return None


def label(p: Person) -> str:
    n = p.nick
    if n is None:
        return p.name
    return n + " (" + p.name + ")"


def describe(x: str | None) -> str:
    if x:
        return "text " + x.upper()
    return "nothing" if x is None else "empty"


def first_or(x: str | None, y: str) -> str:
    if x is not None and len(x) > 0:
        return x
    return y


def main() -> None:
    words = ["alpha", "beta", "gamma", ""]
    hit = find(words, sys.argv[1])
    miss = find(words, "zz")
    print(hit, miss, hit == "beta", miss == None, hit != miss, None == miss, hit is None, miss is not None)
    print(f"{hit} {miss!r} {hit!r}", repr(miss), str(hit), str(miss))
    print(describe(hit), describe(miss), describe(""), first_or(hit, "-"), first_or(miss, "-"))
    ann = Person("Ann", None)
    bob = Person("Bob", hit)
    print(label(ann), label(bob), ann.nick, bob.nick)
    bob.nick = None
    ann.nick = "annie"
    print(label(ann), label(bob))
    y: str | None = None
    if len(sys.argv) > 2:
        y = sys.argv[2]
    print(y, y is None, describe(y))
    y = "set"
    print(len(y), y.upper())
    z = hit if len(sys.argv) > 2 else None
    print(z)


if __name__ == "__main__":
    main()
"""


def test_str_or_none_values(tmp_path: Path):
    _compare(tmp_path, OPTIONAL_STR, [["be"], ["zz"], ["", "x"], ["al", ""], ["g", "\u00e9\u4e2d"]])


def test_optional_class_values_read_after_a_proof(tmp_path: Path):
    _compare(tmp_path, """
import sys


class Node:
    val: int
    nxt: "Node | None"

    def __init__(self, val: int, nxt: "Node | None") -> None:
        self.val = val
        self.nxt = nxt

    def twice(self) -> int:
        return 2 * self.val


def head_or(n: Node | None, d: int) -> int:
    if n is None:
        return d
    return n.val + n.twice()


def second(n: Node | None) -> int:
    if n is not None:
        m = n.nxt
        if m is not None and m.val > 0:
            return m.val
    return -1


def total(n: Node | None) -> int:
    s = 0
    cur = n
    while cur is not None:
        s += cur.val
        cur = cur.nxt
    return s


def main() -> None:
    k = int(sys.argv[1])
    chain: Node | None = None
    for i in range(k):
        chain = Node(i + 1, chain)
    print(head_or(chain, 99), second(chain), total(chain), head_or(None, 7), second(None))


if __name__ == "__main__":
    main()
""", [["0"], ["1"], ["2"], ["10"]])


def _rules(tmp_path: Path, source: str) -> set[tuple[str, int]]:
    with pytest.raises(aot.AotError) as raised:
        aot.generate(_write(tmp_path, source))
    return {(f.rule, f.line) for f in raised.value.findings}


MAIN = '\n\ndef main() -> None:\n    print(f("a"))\n\n\nif __name__ == "__main__":\n    main()\n'
NODE = ('class Node:\n    val: int\n    nxt: "Node | None"\n\n'
        '    def __init__(self, val: int, nxt: "Node | None") -> None:\n        self.val = val\n        self.nxt = nxt\n\n\n')
NODE_MAIN = '\n\ndef main() -> None:\n    print(f(Node(1, None)))\n\n\nif __name__ == "__main__":\n    main()\n'


@pytest.mark.parametrize("body, line", [
    ("    return x.upper()\n", 2),
    ("    return x + 'a'\n", 2),
    ("    return 'a' + x\n", 2),
    ("    return str(len(x))\n", 2),
    ("    return x\n", 2),
    ("    if x is None:\n        return ''\n    x = None\n    return x + '!'\n", 5),
    ("    if x is not None:\n        pass\n    return x.lower()\n", 4),
])
def test_an_unproved_use_of_str_or_none_is_aot_optional(tmp_path: Path, body: str, line: int):
    found = _rules(tmp_path, "def f(x: str | None) -> str:\n" + body + MAIN)
    assert ("aot/optional", line) in found, found


def test_a_reassignment_inside_a_loop_ends_the_proof(tmp_path: Path):
    """Inside the loop body `x` is assigned, so the test at the top of an iteration is the only proof; this is
    accepted (the read follows the test in the same iteration)."""
    p = _write(tmp_path, "def f(x: str | None) -> str:\n    s = ''\n    for _ in range(2):\n"
                         "        if x is not None:\n            s = s + x\n        x = None\n    return s\n" + MAIN)
    assert "tpf_f" in aot.generate(p)


@pytest.mark.parametrize("body, line", [
    ("    return n.val\n", 11),
    ("    if None is n:\n        return 0\n    n = n.nxt\n    return n.val\n", 14),
])
def test_an_unproved_field_read_of_an_optional_class_value_is_aot_optional(tmp_path: Path, body: str, line: int):
    found = _rules(tmp_path, NODE + "def f(n: Node | None) -> int:\n" + body + NODE_MAIN)
    assert ("aot/optional", line) in found, found


def test_the_optional_finding_names_the_value_and_the_fix(tmp_path: Path):
    with pytest.raises(aot.AotError) as raised:
        aot.generate(_write(tmp_path, NODE + "def f(n: Node | None) -> int:\n    return n.val\n" + NODE_MAIN))
    text = str(raised.value)
    assert "`n` is a `Node | None` value whose field or method is used" in text
    assert "`is not None`" in text


ITERATION = """
import sys


def total(a: list[float]) -> float:
    t = 0.0
    for v in a:
        if v < 0.0:
            continue
        if v > 100.0:
            break
        t += v
    return t


def isum(a: list[int]) -> int:
    t = 0
    for v in a:
        t += v
    return t


def pairs(a: list[int], b: list[int]) -> int:
    n = 0
    for x in a:
        for y in b:
            if x == y:
                n += 1
    return n


def main() -> None:
    k = int(sys.argv[1])
    xs = [1.5, -2.0, 3.25, 250.0, 4.0]
    big = [2 ** 62, 2 ** 62, k]
    small = list(range(k))
    print(total(xs), isum(big), isum(small), pairs(small, small))


if __name__ == "__main__":
    main()
"""


def test_for_over_lists(tmp_path: Path):
    """`for v in a` over list[float] and list[int] (RAW and big-capable): CPython's list iterator, the length
    read again before every step (design 4.3.2)."""
    _compare(tmp_path, ITERATION, [["0"], ["1"], ["7"]])
