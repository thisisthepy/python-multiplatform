"""TypedPython AOT, tier S step 2b (#443): `dict[K, V]` (docs/design/typedpython-aot.md 4.3.2, "dict and set"; agent
proposal, not owner-confirmed).

Differential against the reference interpreter, as test_aot_tier_s1.py (stdout compared as bytes, the exit status, the
last stderr line). The runtime itself (runtime/tp_aot_dict.h: layouts, what a loop that changes its dict visits) is
compared with CPython's dict by test_aot_dict_runtime.py; these tests build programs, so they check the front end, the
verifier and the emitter: every operation's result and message, references (the sanitizer job), and the refusals.
Red before S2b: a `dict[...]` annotation is `aot/type` ("the type ... is outside the AOT mode").

What this cannot find: a layout difference no program here reaches (test_aot_dict_runtime.py covers those).
"""
from __future__ import annotations

from pathlib import Path

import pytest

from test_aot_tier_s1 import _compare, _write
from typedpython import aot

DICT_LOCALS = """
import sys


def counts(words: list[str]) -> dict[str, int]:
    d: dict[str, int] = {}
    for w in words:
        d[w] = d.get(w, 0) + 1
    return d


def total(d: dict[str, int]) -> int:
    t = 0
    for k in d:
        t += d[k] * len(k)
    return t


def show(d: dict[str, int]) -> None:
    for k, v in d.items():
        print(k, v)
    for v in d.values():
        print(v, end=" ")
    print()
    for k in d.keys():
        print(repr(k), end=" ")
    print()


def main() -> None:
    words = sys.argv[1].split(",")
    d = counts(words)
    print(d, len(d), total(d))
    show(d)
    print("a" in d, "zz" in d, "zz" not in d, d.get("zz", -1), d.get(words[0], -1))
    print(d.setdefault("new", 7), d.setdefault("new", 8), d.pop("new"), d.pop("new", 9), len(d))
    squares = {1: 1.5, 2: 4.0, 3: 9.25}
    squares[4] = 16.0
    squares[1] = -1.0
    print(squares[True], True in squares, squares.get(False, 0.5))
    del squares[2]
    print(squares, repr(squares), str(squares), f"{squares}")
    flags: dict[int, bool] = {0: False, 1: True}
    flags[2] = not flags[1]
    print(flags, flags[2], 2 in flags)
    names = {"x": "\\u00e9t\\u00e9", "y": ""}
    names["z"] = names["x"] + "!"
    print(names, names.get("q", "-"), names.pop("y"))
    other = {"x": "\\u00e9t\\u00e9", "z": "\\u00e9t\\u00e9!"}
    print(names == other, names != other, names == {"x": "a"})
    c = d.copy()
    c["copy"] = 1
    print(len(c), len(d), "copy" in d)
    d.update(c)
    print(d)
    c.clear()
    print(c, len(c), c == {})
    if len(sys.argv) > 2:
        print(d[sys.argv[2]])


if __name__ == "__main__":
    main()
"""


def test_dict_locals_parameters_and_returns(tmp_path: Path):
    _compare(tmp_path, DICT_LOCALS, [["a,b,a,c"], ["x"], ["\u00e9,\u00e9,b", "b"], ["a,b", "missing"],
                                     ["a", "\u00e9t\u00e9"]])


KINDS = """
import sys


def add(d: dict[str, int], k: str) -> None:
    d[k] = d.get(k, 0) + 10


def rebind(d: dict[str, int]) -> int:
    n = len(d)
    d = {"only": n}
    d["more"] = 1
    return len(d)


def nick(names: dict[str, str | None], k: str) -> str:
    v = names.get(k)
    if v is None:
        return "-"
    return v.upper()


def main() -> None:
    flags = {True: "yes", False: "no"}
    flags[True] = "YES"
    for b, word in flags.items():
        print(b, word, not b)
    print(flags, flags[1 == 1], len(flags))
    names: dict[str, str | None] = {"ann": "annie", "bob": None}
    names["cy"] = None
    print(names, nick(names, "ann"), nick(names, "bob"), nick(names, "zed"), names.setdefault("dee"))
    print(names.get("bob"), names.get("x"), names.pop("cy"), names)
    w = {"a": 0.5, "b": -0.0, "c": 1e300 * 10}
    w["d"] = 0.1 + 0.2
    total = 0.0
    for x in w.values():
        total += x if x == x else 0.0
    print(w, total, w.get("z", 2.5))
    big: dict[int, str] = {}
    k = int(sys.argv[1])
    big[k] = "k"
    big[k * k] = "k2"
    big[-k] = "neg"
    big[7] = "seven"
    print(big, k in big, k * k in big, k + 1 in big, big[-k])
    for key, val in big.items():
        print(key, key + 1, val)
    seq = {0: 0, 1: 1, 2: 2, 3: 3, 4: 4, 5: 5, 6: 6, 7: 7, 8: 8, 9: 9, 10: 10, 11: 11, 12: 12, 13: 13, 14: 14,
           15: 15, 16: 16, 17: 17, 18: 18, 19: 19}
    for i in seq:
        if i == int(sys.argv[2]):
            del seq[i]
            seq[100 + i] = i
        print(i, end=" ")
    print()
    print(len(seq), seq)
    c = {"x": 1}
    add(c, "x")
    add(c, "y")
    print(c, rebind(c), c)
    e = dict(c)
    e["z"] = 3
    print(c, e, c == e, bool(e), "nonempty" if c else "empty")
    c.clear()
    if not c:
        print("cleared", c)


if __name__ == "__main__":
    main()
"""


def test_dict_kinds_aliasing_and_a_chunked_display(tmp_path: Path):
    _compare(tmp_path, KINDS, [["5", "3"], ["123456789012345678901", "19"], ["-4", "0"], ["0", "50"]])


ITERATION = """
import sys


def grow(d: dict[int, int], n: int) -> None:
    for k in d:
        if k == n:
            d[100 + k] = k
        print(k)


def shrink(d: dict[int, int], n: int) -> None:
    for k, v in d.items():
        if v == n:
            del d[k]
        print(k, v)


def replace(d: dict[int, int], n: int) -> None:
    for k in d:
        if k == n:
            del d[k]
            d[k + 50] = 0
        print(k)


def main() -> None:
    n = int(sys.argv[2])
    d = {0: 0, 1: 1, 2: 4, 3: 9, 4: 16}
    for i in range(5, int(sys.argv[1])):
        d[i] = i * i
    if sys.argv[3] == "grow":
        grow(d, n)
    elif sys.argv[3] == "shrink":
        shrink(d, n)
    else:
        replace(d, n)
    print(d)


if __name__ == "__main__":
    main()
"""


def test_a_loop_that_changes_its_dict_raises_where_cpython_raises(tmp_path: Path):
    _compare(tmp_path, ITERATION, [["5", "9", "grow"], ["5", "2", "grow"], ["5", "16", "shrink"], ["5", "1", "shrink"],
                                   ["8", "49", "shrink"], ["5", "1", "replace"], ["5", "99", "replace"], ["7", "6", "grow"]])


def test_key_errors_carry_the_repr_of_the_key(tmp_path: Path):
    _compare(tmp_path, """
import sys


def main() -> None:
    d = {"a": 1}
    e = {1: "one"}
    k = sys.argv[1]
    if k == "pop":
        print(d.pop("b\\u00e9'"))
    elif k == "del":
        del e[-7]
    elif k == "true":
        f: dict[int, int] = {}
        print(f[True])
    else:
        print(d[k])


if __name__ == "__main__":
    main()
""", [["a"], ["b"], ["it's"], ["pop"], ["del"], ["true"], ["\u4e2d\"\n"]])


def test_print_makes_the_repr_of_a_container_when_it_writes_it(tmp_path: Path):
    """CPython's print converts each argument when it writes it, after every argument is evaluated: a list or a dict
    printed beside a call that changes it shows the change. (Defect found with S2b: the repr of a bare list argument was
    made when the argument was evaluated; an f-string field or str() is made there, as in CPython.)"""
    _compare(tmp_path, """
import sys


def main() -> None:
    xs = sys.argv[1].split(",")
    print(xs, xs.pop(), f"{xs}", str(xs))
    a = [1, 2, len(xs)]
    print(a, a.pop(), a)
    d = {"k": 1, "j": 2}
    print(d, d.pop("j"), str(d), d)


if __name__ == "__main__":
    main()
""", [["a,b,c"], ["x"]])


FIELDS_AND_METHODS = """
import sys


class Inventory:
    stock: dict[str, int]
    prices: dict[str, float]
    name: str

    def __init__(self, name: str, prices: dict[str, float]) -> None:
        self.name = name
        self.stock = {}
        self.prices = prices

    def add(self, item: str, n: int) -> None:
        self.stock[item] = self.stock.get(item, 0) + n

    def take(self, item: str) -> int:
        return self.stock.pop(item)

    def value(self) -> float:
        total = 0.0
        for item, n in self.stock.items():
            total += n * self.prices.get(item, 0.0)
        return total

    def snapshot(self) -> dict[str, int]:
        return self.stock.copy()

    def merge(self, more: dict[str, int]) -> None:
        for k, v in more.items():
            self.add(k, v)


def main() -> None:
    inv = Inventory("shop", {"apple": 0.5, "pear": 0.75})
    for w in sys.argv[1].split(","):
        inv.add(w, len(w))
    print(inv.name, inv.stock, inv.value(), len(inv.stock), "apple" in inv.stock)
    snap = inv.snapshot()
    inv.merge({"apple": 1, "fig": 2})
    print(snap, inv.stock, snap == inv.stock)
    inv.prices = {"fig": 2.0}
    inv.prices["apple"] = 1.0
    print(inv.value(), inv.prices)
    d = inv.stock
    d["kiwi"] = 9
    print(inv.stock["kiwi"], inv.take(sys.argv[2]), inv.stock)
    for k in inv.stock:
        print(k, end=";")
    print()


if __name__ == "__main__":
    main()
"""


def test_dict_fields_and_method_parameters_and_returns(tmp_path: Path):
    _compare(tmp_path, FIELDS_AND_METHODS, [["apple,pear,apple", "pear"], ["fig", "nope"], ["\u00e9,apple", "apple"]])


POPITEM = """
import sys


def last(d: dict[str, int]) -> tuple[str, int]:
    return d.popitem()


class Stack:
    seen: dict[int, str]

    def __init__(self) -> None:
        self.seen = {}

    def push(self, k: int, v: str) -> None:
        self.seen[k] = v

    def drop(self) -> int:
        k, v = self.seen.popitem()
        print("drop", k, v)
        return k


def main() -> None:
    d = {"a": 1, "b": 2, "c": 3}
    k, v = d.popitem()
    print(k, v, d)
    t = d.popitem()
    print(t, t[0], d)
    d["x"] = 9
    d.popitem()
    d["y"] = 8
    u = last(d)
    print(d, u, d)
    big = {int(sys.argv[1]) * 1000000000000: True}
    n, b = big.popitem()
    print(n, n + 1, b, big)
    s = Stack()
    for w in sys.argv[2].split(","):
        s.push(len(s.seen) - 5, w)
    while len(s.seen) > 1:
        print(s.drop(), s.seen)
    names: dict[str, str | None] = {"p": None}
    a, z = names.popitem()
    print(a, z, names)
    e: dict[int, float] = {1: 0.5}
    for i in range(int(sys.argv[3])):
        q, r = e.popitem()
        print(q, r)


if __name__ == "__main__":
    main()
"""


def test_popitem_takes_the_last_entry_and_raises_on_an_empty_dict(tmp_path: Path):
    """d.popitem(): unpacked, bound to a tuple, returned as a tuple, as a statement, of a field; the entry count is
    truncated (a new key goes where the popped one was), and an empty dict raises KeyError('popitem(): dictionary is
    empty')."""
    _compare(tmp_path, POPITEM, [["3", "a,b,c", "1"], ["123456789", "x", "2"], ["-1", "\u00e9,\u00e8", "0"]])


WORDFREQ = Path(__file__).resolve().parents[4] / "benchmarks" / "typedpython" / "py" / "wordfreq.py"


def test_the_wordfreq_benchmark_compiles_and_prints_what_cpython_prints(tmp_path: Path):
    """The tier S benchmark (a dict[str, int] counted with get(), two loops over its items) builds without CPython."""
    _compare(tmp_path, WORDFREQ.read_text(encoding="utf-8"), [["1000"], ["30000"]], name="wordfreq.py")


def _rules(tmp_path: Path, source: str) -> set[tuple[str, str]]:
    with pytest.raises(aot.AotError) as raised:
        aot.generate(_write(tmp_path, source))
    return {(f.rule, f.message) for f in raised.value.findings}


MAIN = '\n\ndef main() -> None:\n    f()\n\n\nif __name__ == "__main__":\n    main()\n'


@pytest.mark.parametrize("body, reason", [
    # CPython keeps the stored object: a bool stays a bool in a dict of int, an int stays an int in a dict of float
    ("    d: dict[str, int] = {}\n    d['a'] = True\n    print(d)\n", "keeps the type of the value"),
    ("    d: dict[str, float] = {'a': 1}\n    print(d)\n", "keeps the type of the value"),
    ("    d: dict[float, int] = {}\n    print(d)\n", "outside the AOT mode"),
    # a bool inserted as a new key of a dict of int would come back from a loop as a bool
    ("    d: dict[int, int] = {}\n    d[True] = 1\n    print(d)\n", "a bool key"),
    ("    d = {1: 2}\n    print(d.setdefault(False, 3))\n", "a bool key"),
    # popitem() gives a tuple: unpacked into two names of the key and value types, or bound to a tuple
    ("    d = {1: 2}\n    print(d.popitem())\n", "returns a tuple"),
    ("    d = {1: 2}\n    a, b, c = d.popitem()\n    print(a, b, c)\n", "into two names"),
    ("    d = {1: 'x'}\n    a = 0\n    b = 0\n    a, b = d.popitem()\n    print(a, b)\n", "given values of types"),
])
def test_what_a_dict_cannot_hold_yet_is_refused_with_the_reason(tmp_path: Path, body: str, reason: str):
    found = _rules(tmp_path, "def f() -> None:\n" + body + MAIN)
    assert any(reason in m for _, m in found), found
