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


GROWTH = """
import sys


def build(n: int) -> list[int]:
    out = [0] * 0
    for i in range(n):
        out.append(i * 3)
    return out


def fsum(a: list[float]) -> float:
    t = 0.0
    for v in a:
        t += v
    return t


def drain(a: list[int]) -> int:
    t = 0
    while len(a) > 0:
        t += a.pop()
    return t


def main() -> None:
    n = int(sys.argv[1])
    mode = int(sys.argv[2])
    xs = build(n)
    alias = xs
    xs.append(100)
    xs.insert(0, -1)
    xs.insert(-100, -2)
    xs.insert(1000, 999)
    print(len(alias), xs[0], xs[1], xs[len(xs) - 1])
    print(xs.count(3), xs.index(100), len(xs), xs.pop())
    xs.extend(xs)
    print(len(xs), xs.count(-1))
    xs.remove(100)
    xs.reverse()
    print(xs[0], len(xs), xs.pop(), xs.pop(0), xs.pop(-2))
    f = [1.5]
    for v in f:
        if len(f) < 6:
            f.append(v * 2.0)
    f.insert(1, -0.0)
    print(fsum(f), len(f), f.pop(1), f[0], f[len(f) - 1])
    big = [2 ** 70]
    big.append(-(2 ** 70))
    big.extend(big)
    print(big.count(2 ** 70), big.pop(), len(big))
    print(drain(xs), len(alias))
    xs.clear()
    print(len(xs), len(alias))
    if mode == 1:
        print(xs.pop())
    elif mode == 2:
        xs.append(5)
        print(xs.pop(1))
    elif mode == 3:
        print(alias.index(7))
    elif mode == 4:
        alias.remove(7)
    elif mode == 5:
        print(big.index(2 ** 80))
    elif mode == 6:
        print(xs.pop(-1))


if __name__ == "__main__":
    main()
"""


def test_lists_that_change_length(tmp_path: Path):
    """append / insert (CPython's clamping) / extend (also of itself) / pop / clear / reverse / index / count /
    remove on list[int] (RAW and big-capable) and list[float], seen through an alias; an append inside a loop over the
    list is visited; every error message (design 4.3.2)."""
    _compare(tmp_path, GROWTH, [["0", "0"], ["5", "0"], ["40", "0"], ["3", "1"], ["3", "2"], ["3", "3"],
                                ["3", "4"], ["3", "5"], ["0", "6"]])


def test_a_program_that_never_resizes_keeps_the_fixed_layout(tmp_path: Path):
    """The growable layout is chosen per list kind, only where the program changes a length (design 4.3.2): a tier K
    program has no TPA_GROW define, so its C is what it was."""
    c = aot.generate(_write(tmp_path, ITERATION))
    assert "TPA_GROW_" not in c
    g = aot.generate(_write(tmp_path, GROWTH, "grow.py"))
    assert "#define TPA_GROW_INT 1" in g and "#define TPA_GROW_F64 1" in g and "TPA_GROW_I64" not in g


def test_a_float_list_is_not_searched(tmp_path: Path):
    """`x in a` / index / count / remove on a list of float: CPython's answer for NaN depends on object identity
    (`y is x or y == x`), so it is refused, never answered differently."""
    for call in ("a.index(1.0)", "a.count(1.0)"):
        src = f"def f(a: list[float]) -> int:\n    return {call}\n" + \
              '\n\ndef main() -> None:\n    a = [1.0]\n    print(f(a))\n\n\nif __name__ == "__main__":\n    main()\n'
        with pytest.raises(aot.AotError) as raised:
            aot.generate(_write(tmp_path, src))
        assert "aot/identity" in str(raised.value)


@pytest.mark.parametrize("op, kept", [("a.pop()", True), ("g(a)", True), ("a.append(1.0)", False)])
def test_a_list_type_the_program_shrinks_keeps_every_bounds_check(tmp_path: Path, op: str, kept: bool):
    """The verifier drops the check of `a[i]` in `for i in range(len(a))` only while no function of the program can
    shrink a list of that type (here directly, or through the alias in a callee); growing keeps the proof."""
    src = ("def g(b: list[float]) -> None:\n    b.pop()\n\n\n"
           "def f(a: list[float]) -> float:\n    t = 0.0\n    for i in range(len(a)):\n        t += a[i]\n"
           f"        if t > 3.0:\n            {op}\n    return t\n\n\n"
           "def main() -> None:\n    a = [1.0, 2.0, 3.0, 4.0]\n    print(f(a))\n    g(a)\n\n\n"
           'if __name__ == "__main__":\n    main()\n')
    if op == "a.append(1.0)":
        src = src.replace("    b.pop()\n", "    b.append(0.5)\n")
    c = aot.generate(_write(tmp_path, src))
    body = c[c.index("static int tpf_f(tpa_f64_list *v_a TPA_UNUSED, double *out)\n{"):]
    body = body[:body.index("\n}\n")]
    assert ("tpa_slot(" in body) == kept, body


def test_print_str_and_repr_of_lists(tmp_path: Path):
    """A list of float / int (RAW and big-capable) printed, in str() / repr() and in f-string fields: its repr, each
    item's repr (design 4.3.2)."""
    _compare(tmp_path, """
import sys


def main() -> None:
    k = int(sys.argv[1])
    f = [1.5, -0.0, 1e300 * 1e10, 0.1, 1e16, 2.5e-7]
    i = [1, -2, k]
    b = [2 ** 70, k]
    e = [0] * 0
    print(f, i, b, e)
    print(str(f), repr(i), f"{b} and {e!r} {i!s}")
    s = str(i) + "!"
    f.append(-1.0)
    print(s, len(repr(f)), f)


if __name__ == "__main__":
    main()
""", [["0"], ["-7"], [str(2 ** 63 - 1)]])
