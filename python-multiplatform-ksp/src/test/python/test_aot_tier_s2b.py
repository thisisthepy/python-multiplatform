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


NESTED = """
import sys


def index(words: list[str]) -> dict[str, dict[int, int]]:
    out: dict[str, dict[int, int]] = {}
    for i in range(len(words)):
        w = words[i]
        if w not in out:
            out[w] = {}
        out[w][len(w)] = out[w].get(len(w), 0) + i
        out[w][-1] = i
    return out


def total(t: dict[str, dict[int, int]]) -> int:
    n = 0
    for w, inner in t.items():
        for k, v in inner.items():
            n += k * v + len(w)
    return n


def main() -> None:
    words = sys.argv[1].split(",")
    t = index(words)
    print(t, len(t), total(t))
    first = t[words[0]]
    first[100] = 7
    print(t[words[0]], first == t[words[0]])
    counts: dict[str, int] = {}
    for w in words:
        counts.setdefault(w, 0)
        counts[w] += 1
    print(counts)
    g: dict[str, dict[str, float]] = {"a": {"x": 1.5}, "b": {}}
    g["b"]["y"] = -0.0
    g.setdefault("c", {})["z"] = 2.0
    print(g, g.get("q", {"none": 0.0}), g.pop("a"), g)
    h: dict[int, dict[str, str | None]] = {1: {"k": None}}
    h[1]["j"] = "v"
    for k in h[1]:
        print(k, h[1][k])
    u = {1: {"k": None, "j": "v"}}
    print(h == u, h != u, h[1] == {"k": None})
    c = t.copy()
    del c[words[0]]
    print(len(c), len(t), words[0] in c)
    k2, inner2 = t.popitem()
    print(k2, inner2)
    print(t[sys.argv[2]][int(sys.argv[3])])


if __name__ == "__main__":
    main()
"""


def test_dicts_as_dict_values_and_augmented_stores(tmp_path: Path):
    """dict[K, dict[K2, V]]: an inner dict is a value like another (stored, read, aliased, compared, printed, popped);
    `d[k][j] = v`, `d.setdefault(k, {})[j] = v`, loops over an inner dict, and `d[k] += 1`."""
    _compare(tmp_path, NESTED, [["a,b,a,cc", "a", "1"], ["x", "x", "-1"], ["a,b", "a", "1"], ["a,b", "zz", "1"]])


DICT_LISTS = """
import sys


def rows(text: str) -> list[dict[str, int]]:
    out: list[dict[str, int]] = []
    for line in text.split(";"):
        row: dict[str, int] = {}
        for cell in line.split(","):
            if cell:
                row[cell] = len(cell)
        out.append(row)
    return out


def widest(table: list[dict[str, int]]) -> int:
    best = 0
    for row in table:
        for k, v in row.items():
            if v > best:
                best = v
    return best


class Sheet:
    pages: list[dict[int, str]]

    def __init__(self) -> None:
        self.pages = [{}]

    def put(self, k: int, s: str) -> None:
        self.pages[-1][k] = s
        if len(self.pages[-1]) == 2:
            self.pages.append({})


def main() -> None:
    t = rows(sys.argv[1])
    print(t, len(t), widest(t))
    first = t[0]
    first["new"] = 99
    print(t[0], t[-1])
    t[0]["x"] = 1
    t.insert(0, {"head": 0})
    t.append(first)
    last = t.pop()
    last["popped"] = 1
    print(t, last)
    t[1] = {}
    t.reverse()
    print(t, len(t[0]))
    u = [{"u": 1}]
    u.extend(u)
    u.extend(t)
    u[0]["v"] = 2
    print(u)
    s = Sheet()
    for w in sys.argv[2].split(","):
        s.put(len(w), w)
    print(s.pages)
    for p in s.pages:
        p.clear()
    print(s.pages, f"{s.pages}", str(t))
    t.clear()
    print(t, t[int(sys.argv[3])])


if __name__ == "__main__":
    main()
"""


def test_lists_of_dicts(tmp_path: Path):
    """list[dict[K, V]]: built, appended, inserted, popped, indexed (the dict read out is the same object), stored,
    reversed, cleared, looped over, printed, as a field, a parameter and a return; IndexError as CPython's."""
    _compare(tmp_path, DICT_LISTS, [["a,bb;ccc;", "x,yy,zzz", "0"], [";", "", "-1"]])


TUPLE_KEYS = """
import sys


def grid(n: int) -> dict[tuple[int, int], str]:
    g: dict[tuple[int, int], str] = {}
    for i in range(n):
        for j in range(n):
            if (i + j) % 2 == 0:
                g[i, j] = "x" if i == j else "o"
    return g


def main() -> None:
    n = int(sys.argv[1])
    g = grid(n)
    print(g, len(g))
    p = (1, 1)
    print(g.get(p, "-"), (0, 1) in g, p in g, g.get((n, n), "none"))
    for (i, j), c in g.items():
        if c == "x":
            print(i, j, end="; ")
    print()
    for k in g:
        print(k, k[0] + k[1], end=" ")
    print()
    names: dict[tuple[str, bool], int] = {("a", True): 1}
    names["b", False] = 2
    names[("a", True)] += 10
    print(names, names[("b", False)], names.pop(("a", True)), names)
    big = {(int(sys.argv[2]) * 10 ** 15, "k"): 1.5}
    bk, bv = big.popitem()
    print(bk, bv, bk[0] + 1, big)
    q, v = g.popitem()
    print(q, v)
    print(g[(int(sys.argv[3]), 0)])


if __name__ == "__main__":
    main()
"""


def test_tuple_keys(tmp_path: Path):
    """dict[tuple[...], V] of int, str and bool elements: d[a, b], d[t], t in d, get / pop / setdefault of a tuple,
    loops binding the key as a tuple or as its elements, popitem, the repr `(1, 'a')`, KeyError((1, 2))."""
    _compare(tmp_path, TUPLE_KEYS, [["3", "7", "0"], ["1", "-2", "5"]])


SETS = """
import sys


def seen(words: list[str]) -> set[str]:
    out: set[str] = set()
    for w in words:
        out.add(w)
    return out


class Bag:
    items: set[int]

    def __init__(self) -> None:
        self.items = {1, 2}

    def put(self, k: int) -> bool:
        if k in self.items:
            return False
        self.items.add(k)
        return True


def main() -> None:
    words = sys.argv[1].split(",")
    s = seen(words)
    print(len(s), "a" in s, "zz" not in s, bool(s), len(set()))
    t = {"a", "b", "c"}
    print(s == t, s != t, s <= t, s < t, s >= t, s > t, s.issubset(t), t.issuperset(s), s.isdisjoint({"q"}))
    u = s | t
    i = s & t
    d = s - t
    x = s ^ t
    print(len(u), len(i), len(d), len(x), "a" in i, u == t | s, i == t & s)
    u.discard("a")
    u.discard("nope")
    s.update(t)
    c = s.copy()
    c.clear()
    print(len(u), "a" in u, len(s), len(c), c == set())
    b = Bag()
    print(b.put(3), b.put(3), b.put(1), len(b.items), 3 in b.items, True in b.items)
    pairs: set[tuple[int, str]] = {(1, "a")}
    pairs.add((2, "b"))
    print((1, "a") in pairs, (2, "a") in pairs, len(pairs))
    nums = {10, 20}
    nums.remove(int(sys.argv[2]))
    print(len(nums), 10 in nums)


if __name__ == "__main__":
    main()
"""


def test_sets_without_their_order(tmp_path: Path):
    """set[T] of int, str, bool and tuples: displays, set(), add, discard, remove (KeyError(x)), clear, copy, update,
    in, len, truth, ==, subset tests, | & - ^, as a local, a parameter, a return and a field. Nothing here depends on
    the order of a set (a loop over one, its repr, pop() are refused: aot/set-order)."""
    _compare(tmp_path, SETS, [["a,b,a", "10"], ["x", "20"], ["a,b,c,d", "5"]])


VIEWS = """
import sys


def main() -> None:
    d = {"a": 1, "b": 2}
    for w in sys.argv[1].split(","):
        d[w] = len(w)
    print(len(d.keys()), len(d.values()), len(d.items()))
    print("a" in d.keys(), "zz" not in d.keys(), 2 in d.values(), 99 in d.values(), ("a", 1) in d.items(),
          ("a", 2) in d.items())
    print(d.keys(), d.values(), d.items())
    print(str(d.keys()), repr(d.items()), f"{d.values()}")
    names = {1: "x", 2: None}
    print(names.values(), None in names.values(), names.items())
    e: dict[int, int] = {}
    print(e.keys(), e.values(), e.items(), len(e.items()))
    words = sys.argv[1].split(",")
    s = set(words)
    nums = set([3, 1, 3])
    print(len(s), "a" in s, len(nums), 3 in nums, s == set(words))


if __name__ == "__main__":
    main()
"""


def test_views_outside_a_loop_and_a_set_of_a_list(tmp_path: Path):
    """d.keys() / d.values() / d.items() measured, searched and printed (`dict_keys(['a'])`); set(xs) of a list."""
    _compare(tmp_path, VIEWS, [["c,a,dd"], ["b"]])


VIEW_VALUES = """
import sys


def main() -> None:
    d = {"a": 1, "b": 2}
    ks = d.keys()
    vs = d.values()
    its = d.items()
    print(len(ks), len(vs), len(its), ks, vs, its)
    for w in sys.argv[1].split(","):
        d[w] = len(w)
    print(len(ks), "a" in ks, "zz" not in ks, 2 in vs, 99 not in vs, ("a", 1) in its, ("a", 2) not in its)
    print(ks, vs, its, str(ks), repr(vs), f"{its}!")
    line = ""
    for k in ks:
        line += k + ";"
    for v in vs:
        line += str(v) + ";"
    for k, v in its:
        line += k + "=" + str(v) + ";"
    print(line)
    if ks:
        print("not empty", bool(vs), not its)
    old = ks
    d = {"z": 26}
    ks = d.keys()
    print(old, ks, len(old), len(ks))
    del d["z"]
    print(ks, len(ks), bool(ks), not ks)
    t: dict[tuple[int, str], dict[int, int]] = {(1, "x"): {2: 3}}
    tk = t.keys()
    tv = t.values()
    print(tk, tv, (1, "x") in tk)
    two = old
    n = 0
    for k in two:
        n += 1
    print(n, two)


if __name__ == "__main__":
    main()
"""


def test_a_view_bound_to_a_name_is_live(tmp_path: Path):
    """`ks = d.keys()` (and values(), items()) bound to a name: it shows the dict as it is when used (len, `in`, loops,
    truth, the repr), and keeps the dict it was made from when the dict's name is bound again, as in CPython."""
    _compare(tmp_path, VIEW_VALUES, [["c,a,dd"], ["b"]])


VIEW_CHANGED = """
import sys


def main() -> None:
    d = {1: 1, 2: 2}
    ks = d.keys()
    n = int(sys.argv[1])
    for k in ks:
        if n > 0:
            d[k + 10] = k
            n -= 1
    print(d)


if __name__ == "__main__":
    main()
"""


def test_a_loop_over_a_view_name_raises_where_cpython_raises(tmp_path: Path):
    """The loop over a view name is CPython's dict iterator: a dict that changes size raises RuntimeError."""
    _compare(tmp_path, VIEW_CHANGED, [["0"], ["1"]])


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
    # a dict held by a dict: of its exact kinds; == of dicts with a float anywhere inside tests identity first
    ("    d: dict[str, dict[str, int]] = {}\n    e = {'x': 'y'}\n    d['a'] = e\n    print(d)\n", "invariant"),
    ("    d: dict[str, dict[str, float]] = {'a': {}}\n    print(d == {'a': {}})\n", "aot/identity"),
    ("    d: dict[str, dict[str, int]] = {'a': {}}\n    d['a'] += 1\n    print(d)\n", "held by a dict"),
    # a list of dicts: searched by value (identity first) is not in yet; lists are invariant
    ("    ds = [{'a': 1}]\n    print(ds.index({'a': 1}))\n", "searching a list of dicts"),
    ("    ds = [{'a': 1}]\n    print({'a': 1} in ds)\n", "searching a list of dicts"),
    ("    ds = [{'a': 1}]\n    es: list[dict[str, float]] = ds\n    print(es)\n", "invariant"),
    ("    ds = [{'a': 1}]\n    es = [{'b': 2}]\n    print(ds == es)\n", "comparing lists of dicts"),
    # a tuple key: exactly its elements' types (a tuple keeps a bool as it came), given as a tuple; no float element
    ("    d: dict[tuple[int, int], str] = {}\n    d[True, 1] = 'x'\n    print(d)\n", "exactly its elements' types"),
    ("    d: dict[tuple[int, int], str] = {(1, 2): 'x'}\n    print(d[3])\n", "gives a tuple key as a display"),
    ("    d: dict[tuple[float, int], str] = {}\n    print(d)\n", "outside the AOT mode"),
    # a set: what depends on the order of its table is refused (aot/set-order); a bool is not added to a set of int
    ("    s = {3, 1, 2}\n    for x in s:\n        print(x)\n", "aot/set-order"),
    ("    s = {3, 1, 2}\n    print(s)\n", "aot/set-order"),
    ("    s = {'b', 'a'}\n    print(f'{s}')\n", "aot/set-order"),
    ("    s = {3, 1, 2}\n    print(s.pop())\n", "aot/set-order"),
    ("    s = {3, 1, 2}\n    s.add(True)\n    print(len(s))\n", "a bool key"),
    ("    s = {3, 1, 2}\n    print(s[0])\n", "not subscriptable"),
    # a view bound to a name is used as a view: what a dict has and a view has not is refused; a search of float
    # values tests identity first in CPython
    ("    d = {1: 2}\n    k = d.keys()\n    print(k[1])\n", "is a view"),
    ("    d = {1: 2}\n    k = d.keys()\n    print(k.get(1, 0))\n", "is a view"),
    ("    d = {1: 2}\n    k = d.keys()\n    print(k == {1})\n", "is a view"),
    ("    d = {1: 2}\n    k = d.keys()\n    print(k & {1})\n", "is a view"),
    ("    d = {1: 2}\n    k = d.items()\n    e = dict(k)\n    print(e)\n", "is a view"),
    ("    d = {1: 2}\n    k = d.keys()\n    print(list(k))\n", "is a view"),
    ("    d = {1: 2}\n    k = d.keys()\n    xs = [k]\n    print(len(xs))\n", "dict_keys"),
    ("    d = {1: 2}\n    k = d.values()\n    e = {0: k}\n    print(len(e))\n", "dict_values"),
    ("    d = {1: 2.5}\n    k = d.values()\n    print(2.5 in k)\n", "aot/identity"),
    ("    d = {1: 2.5}\n    print(2.5 in d.values())\n", "aot/identity"),
    ("    xs = [1.5]\n    print(len(set(xs)))\n", "is used other than"),
])
def test_what_a_dict_cannot_hold_yet_is_refused_with_the_reason(tmp_path: Path, body: str, reason: str):
    found = _rules(tmp_path, "def f() -> None:\n" + body + MAIN)
    assert any(reason in m for _, m in found), found
