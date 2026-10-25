"""str and dict code in compiled functions (issue #150, widened; SPEC N-8 / N-9).

Every operation added here must call the C API the interpreter's bytecode calls, so each test is
differential: the compiled function and `__typedpython_interpreted__[name]` run on two fresh copies
of the arguments and must agree on the result, on the exception (type and repr, so the message and
args), on the final state of every argument and on the log of every user `__hash__` / `__eq__` /
`__index__` / `__format__` / `__repr__` / `__iter__` call (same calls, same order). A test that
expects the compiled path compares `__typedpython_deopts__` before and after, so a function that
silently stayed interpreted fails it.

Also here: the wordfreq benchmark's `word_of` and `main` compile and print what CPython prints, the
object operations leak no reference, and the exact-type guards of the fast paths are shown to matter
(a mutant header whose guard is "always true" makes a test fail).
"""
from __future__ import annotations

import contextlib
import dataclasses
import gc
import importlib.util
import io
import shutil
import sys
import textwrap
from pathlib import Path

import pytest

from typedpython import cbuild, cgen, frontend, verify

REPO = Path(__file__).resolve().parents[4]
WORDFREQ = REPO / "benchmarks" / "typedpython" / "py" / "wordfreq.py"

SOURCE = textwrap.dedent('''
    # typedpython: compiled
    from typing import SupportsIndex


    def get(d: dict[object, object], k: object) -> object:
        return d[k]


    def get_list(xs: list[object], i: int) -> object:
        return xs[i]


    def get_index(xs: list[object], i: SupportsIndex) -> object:
        return xs[i]


    def get_str(s: str, i: int) -> str:
        return s[i]


    def get_nested(d: dict[object, dict[object, object]], a: object, b: object) -> object:
        return d[a][b]


    def put(d: dict[object, object], k: object, v: object) -> None:
        d[k] = v


    def put_list(xs: list[object], i: int, v: object) -> None:
        xs[i] = v


    def has(c: list[object], x: object) -> bool:
        return x in c


    def has_not(c: list[object], x: object) -> bool:
        return x not in c


    def has_dict(c: dict[object, object], x: object) -> bool:
        return x in c


    def has_str(c: str, x: str) -> bool:
        return x in c


    def has_branch(c: list[object], x: object) -> str:
        if x in c:
            return "yes"
        return "no"


    def has_chain(c: list[object], x: object, y: object) -> bool:
        return x in c in y


    def pairs(xs: list[tuple[object, ...]]) -> list[object]:
        out: list[object] = []
        for a, b in xs:
            out.append(a)
            out.append(b)
        return out


    def triples(xs: list[tuple[object, ...]]) -> list[object]:
        out: list[object] = []
        for a, b, c in xs:
            out.append(a)
            out.append(c)
        return out


    def each(xs: list[object]) -> list[object]:
        out: list[object] = []
        for x in xs:
            if x is None:
                continue
            out.append(x)
        return out


    def first_after(xs: list[object], stop: object) -> object:
        found: object = None
        for x in xs:
            if x == stop:
                found = x
                break
        return found


    def return_inside(xs: list[object]) -> object:
        for x in xs:
            return x
        return None


    def nested(xs: list[list[object]]) -> list[object]:
        out: list[object] = []
        for row in xs:
            for x in row:
                if x is None:
                    break
                out.append(x)
        return out


    def items_of(d: dict[str, object]) -> list[object]:
        out: list[object] = []
        for k, v in d.items():
            out.append(k)
            out.append(v)
        return out


    def mutate_while_iterating(d: dict[str, object]) -> object:
        n: int = 0
        for k in d:
            d["new" + k] = n
            n += 1
        return n


    def fmt(x: object, y: float, n: int) -> str:
        return f"{x}|{x!r}|{x!s}|{x!a}|{y:.3f}|{n:05d}|{x:>5}|{n}"


    def fmt_one(x: object) -> str:
        return f"{x}"


    def fmt_text(x: object) -> str:
        return f"a{x}b{x!r}c"


    def fmt_empty() -> str:
        return f""


    def fmt_debug(x: object) -> str:
        return f"{x=}"


    def cat(a: str, b: str) -> str:
        return a + b


    def cat_in_place(a: str, b: str) -> str:
        s: str = ""
        s += a
        s += b
        return s


    def grow(xs: list[object], ys: list[object]) -> list[object]:
        out: list[object] = []
        out += xs
        out += ys
        return out


    def empties() -> object:
        d: dict[object, object] = {}
        xs: list[object] = []
        xs.append(d)
        return xs


    class Acc:
        def __init__(self, log: list[object]) -> None:
            self.log = log

        def __iadd__(self, other: int) -> Acc:
            self.log.append(("iadd", other))
            return self

        def __isub__(self, other: int) -> Acc:
            self.log.append(("isub", other))
            return self

        def __imul__(self, other: int) -> Acc:
            self.log.append(("imul", other))
            return self

        def __itruediv__(self, other: int) -> Acc:
            self.log.append(("itruediv", other))
            return self

        def __ifloordiv__(self, other: int) -> Acc:
            self.log.append(("ifloordiv", other))
            return self

        def __imod__(self, other: int) -> Acc:
            self.log.append(("imod", other))
            return self


    def aug_ops(a: Acc, b: int) -> Acc:
        a += b
        a -= b
        a *= b
        a /= b
        a //= b
        a %= b
        return a


    def aug_list(xs: list[str], n: int) -> list[str]:
        xs *= n
        xs += xs
        return xs


    def lookup_default(d: dict[str, int], k: str) -> object:
        counts: dict[str, int] = {}
        counts[k] = counts.get(k, 0) + 1
        counts[k] = counts.get(k, 0) + 1
        return counts[k] + d.get(k, 0)
''')

COMPILED = {
    "get", "get_list", "get_index", "get_str", "get_nested", "put", "put_list", "has",
    "has_not", "has_dict", "has_str", "has_branch", "has_chain", "pairs", "triples", "each",
    "first_after", "return_inside", "nested", "items_of", "mutate_while_iterating", "fmt", "fmt_one",
    "fmt_text", "fmt_empty", "fmt_debug", "cat", "cat_in_place", "grow", "empties", "lookup_default", "aug_ops", "aug_list",
}


def build_source(d, source, name="strmod", runtime_dir=None):
    path = d / f"{name}.py"
    path.write_text(source)
    proved, diags = verify.verify(frontend.lower(path))
    c = cgen.generate(proved, path, path.name)
    so = cbuild.build(c, path.stem, d / "out", runtime_dir=runtime_dir)
    return proved, diags, cbuild.load(so.stem.split(".")[0], so)


@dataclasses.dataclass
class Built:
    proved: object
    diags: list
    module: object


@pytest.fixture(scope="module")
def built(tmp_path_factory):
    d = tmp_path_factory.mktemp("strmod")
    proved, diags, m = build_source(d, SOURCE)
    return Built(proved, diags, m)


def deopts(m):
    return m.__typedpython_deopts__


def interp(m, name):
    return m.__typedpython_interpreted__[name]


def outcome(fn, args):
    try:
        return ("ok", fn(*args))
    except BaseException as e:  # noqa: BLE001 - the type, the message and the args are observable
        return ("raise", repr(e))


def state(args):
    return repr(args)


def differential(m, name, make, expect_deopt=False, logged=False):
    """Compiled and interpreted run on two fresh argument lists. With `logged`, `make()` returns
    `(args, log)` and the two logs of user-code calls must be equal and not empty."""
    ca, ia = make(), make()
    if logged:
        (ca, clog), (ia, ilog) = ca, ia
    before = deopts(m)
    c = outcome(getattr(m, name), ca)
    after = deopts(m)
    i = outcome(interp(m, name), ia)
    assert c == i, (c, i)
    assert state(ca) == state(ia), (state(ca), state(ia))
    if logged:
        assert clog == ilog and clog, (clog, ilog)
    if expect_deopt:
        assert after == before + 1, "expected a deopt (the interpreted path), got none"
    else:
        assert after == before, "unexpected deopt: the compiled path did not run"
    return c


class K:
    """A key whose hash and equality are user code, logging every call. Hash collides on purpose."""

    def __init__(self, v, log):
        self.v, self.log = v, log

    def __hash__(self):
        self.log.append(("hash", self.v))
        return hash(self.v) % 3

    def __eq__(self, other):
        self.log.append(("eq", self.v, getattr(other, "v", other)))
        return isinstance(other, K) and other.v == self.v

    def __repr__(self):
        return f"K({self.v})"


class SKey(str):
    """A str subclass whose hash and equality are user code."""
    log: list = []

    def __hash__(self):
        SKey.log.append(("hash", str(self)))
        return hash(str(self))

    def __eq__(self, other):
        SKey.log.append(("eq", str(self), str(other)))
        return str.__eq__(self, other)


class EqList:
    def __init__(self, v, log):
        self.v, self.log = v, log

    def __eq__(self, other):
        self.log.append(("eq", self.v, getattr(other, "v", other)))
        return getattr(other, "v", other) == self.v

    __hash__ = None

    def __repr__(self):
        return f"E({self.v})"


class Idx:
    def __init__(self, i, log):
        self.i, self.log = i, log

    def __index__(self):
        self.log.append(("index", self.i))
        return self.i

    def __repr__(self):
        return f"Idx({self.i})"


class Fmt:
    def __init__(self, log):
        self.log = log

    def __format__(self, spec):
        self.log.append(("format", spec))
        return f"<{spec}>"

    def __repr__(self):
        self.log.append(("repr",))
        return "R"

    def __str__(self):
        self.log.append(("str",))
        return "S"

    def __eq__(self, other):
        return self is other

    __hash__ = object.__hash__


class SubDict(dict):
    def __getitem__(self, k):
        return ("sub-get", k)

    def __setitem__(self, k, v):
        dict.__setitem__(self, k, ("sub-set", v))

    def __contains__(self, k):
        return k == "magic"


class MissingDict(dict):
    def __missing__(self, k):
        return ("missing", k)


class SubList(list):
    def __getitem__(self, i):
        return ("sub-list", i)


class SubStr(str):
    def __add__(self, other):
        return "sub-add"

    def __radd__(self, other):
        return "sub-radd"


class Three:
    def __iter__(self):
        return iter([1, 2, 3])

    def __repr__(self):
        return "Three()"


class Money:
    def __init__(self, v):
        self.v = v

    def __add__(self, other):
        return ("add", self.v, other)

    def __radd__(self, other):
        return ("radd", self.v, other)

    def __repr__(self):
        return f"Money({self.v})"


def test_compiled_set(built):
    names = {f.name for f in built.proved.functions}
    assert names >= COMPILED, (sorted(COMPILED - names),
                               {n: built.proved.skipped.get(n) for n in sorted(COMPILED - names)})


# --- subscript ----------------------------------------------------------------------------------

def test_dict_getitem(built):
    m = built.module
    for key in ("a", "zz", 3, None, (1, 2), 1.0):
        differential(m, "get", lambda key=key: [{"a": 1, 3: 4, None: 5, (1, 2): 6, 1: 7}, key])
    differential(m, "get", lambda: [{}, []])                      # unhashable key: TypeError
    differential(m, "get", lambda: [{}, {"a": 1}])
    differential(m, "get", lambda: [{"a": 1}, SKey("a")])


def test_dict_getitem_user_hash_and_eq_run_the_same_times(built):
    m = built.module
    for present in ([1], [1, 2, 3, 4], [0, 3, 6, 9]):            # colliding hashes: __eq__ runs
        for key_value in (1, 3, 5, 9):
            def make(present=present, key_value=key_value):
                log = []
                d = {K(v, log): v for v in present}
                log.clear()
                return [d, K(key_value, log)], log
            differential(m, "get", make, logged=True)
    for same in (True, False):                                    # identity shortcut vs __eq__
        def make(same=same):
            log = []
            key = K(1, log)
            d = {key: "x", K(4, log): "y"}
            log.clear()
            return [d, key if same else K(1, log)], log
        differential(m, "get", make, logged=True)


def test_dict_getitem_subclass_keys_and_dict_subclasses_use_their_own_methods(built):
    m = built.module
    SKey.log.clear()
    differential(m, "get", lambda: [{SKey("a"): 1}, "a"])
    differential(m, "get", lambda: [{"a": 1}, SKey("a")])
    assert SKey.log                                              # the user hash ran
    differential(m, "get", lambda: [SubDict({"a": 1}), "a"])      # __getitem__ override
    assert m.get(SubDict({"a": 1}), "a") == ("sub-get", "a")
    differential(m, "get", lambda: [MissingDict(), "q"])           # __missing__
    assert m.get(MissingDict(), "q") == ("missing", "q")
    differential(m, "get_nested", lambda: [{1: {2: 3}}, 1, 2])
    differential(m, "get_nested", lambda: [{1: {2: 3}}, 1, 5])
    differential(m, "get_nested", lambda: [{1: {2: 3}}, 7, 5])


def test_list_and_str_getitem(built):
    m = built.module
    for i in (0, 1, -1, 2, -3, 3, -4, 100):
        differential(m, "get_list", lambda i=i: [[10, 20, 30], i])
        differential(m, "get_str", lambda i=i: ["abc", i])
    differential(m, "get_list", lambda: [SubList([1, 2]), 0])
    differential(m, "get_list", lambda: [(1, 2, 3), 1])            # a tuple is subscriptable too
    differential(m, "get_list", lambda: [5, 0])                    # TypeError: not subscriptable
    differential(m, "get_list", lambda: [None, 0])
    differential(m, "get_list", lambda: [3.5, 0])
    differential(m, "get_list", lambda: [{"a": 1}, 0])             # KeyError(0)


def test_getitem_with_user_index(built):
    m = built.module

    def make(i, xs=(10, 20, 30)):
        def _make():
            log = []
            return [list(xs), Idx(i, log), log]
        return _make
    for i in (0, 2, -1, 5):
        differential(m, "get_index", lambda i=i: make(i)()[:2])
    # the user __index__ call is the same: logged through the argument itself
    log_a, log_b = [], []
    a = outcome(m.get_index, [[1, 2, 3], Idx(1, log_a)])
    b = outcome(interp(m, "get_index"), [[1, 2, 3], Idx(1, log_b)])
    assert a == b and log_a == log_b == [("index", 1)]


def test_dict_setitem(built):
    m = built.module
    for key in ("a", "new", 3, None):
        differential(m, "put", lambda key=key: [{"a": 1}, key, "v"])
    differential(m, "put", lambda: [{}, [], 1])                    # unhashable
    differential(m, "put", lambda: [SubDict(), "a", 1])             # __setitem__ override
    assert SubDict.__setitem__ is not dict.__setitem__
    d = SubDict()
    m.put(d, "a", 1)
    assert dict.__getitem__(d, "a") == ("sub-set", 1)
    differential(m, "put", lambda: [(1, 2), 0, 1])                  # tuple: TypeError
    differential(m, "put", lambda: [None, 0, 1])
    differential(m, "put", lambda: [5, 0, 1])
    differential(m, "put_list", lambda: [[1, 2, 3], 1, "x"])
    differential(m, "put_list", lambda: [[1, 2, 3], -3, "x"])
    differential(m, "put_list", lambda: [[1, 2, 3], 3, "x"])
    differential(m, "put_list", lambda: [[1, 2, 3], -4, "x"])
    differential(m, "put_list", lambda: [(1, 2, 3), 0, "x"])


def test_dict_setitem_user_hash_and_eq(built):
    m = built.module
    for same in (True, False):
        def make(same=same):
            log = []
            key = K(1, log)
            d = {key: "x", K(4, log): "y"}
            log.clear()
            return [d, key if same else K(1, log), "new"], log
        differential(m, "put", make, logged=True)
    for v in (7, 4, 10):                                          # new key, colliding key, collision
        def make(v=v):
            log = []
            d = {K(1, log): 0, K(4, log): 0}
            log.clear()
            return [d, K(v, log), 1], log
        differential(m, "put", make, logged=True)


# --- in ----------------------------------------------------------------------------------------

def test_contains(built):
    m = built.module
    for x in (1, 2, 9, None, "a"):
        differential(m, "has", lambda x=x: [[1, 2, 3, None, "a"], x])
        differential(m, "has_not", lambda x=x: [[1, 2, 3, None, "a"], x])
        differential(m, "has_dict", lambda x=x: [{1: 1, None: 2, "a": 3}, x])
        differential(m, "has_branch", lambda x=x: [[1, 2, 3], x])
    differential(m, "has_dict", lambda: [{}, []])                  # unhashable: TypeError
    differential(m, "has", lambda: [5, 1])                         # not iterable
    differential(m, "has", lambda: [None, 1])
    differential(m, "has_str", lambda: ["hello", "ell"])
    differential(m, "has_str", lambda: ["hello", "xyz"])
    differential(m, "has_str", lambda: ["hello", ""])
    differential(m, "has_dict", lambda: [SubDict(), "magic"])       # __contains__ override
    differential(m, "has_dict", lambda: [SubDict(), "other"])
    assert m.has_dict(SubDict(), "magic") is True
    differential(m, "has_chain", lambda: [[1, 2], 1, [[1, 2], 5]])
    differential(m, "has_chain", lambda: [[1, 2], 1, [[3], 5]])
    differential(m, "has_chain", lambda: [[1, 2], 9, [[1, 2], 5]])


def test_contains_with_custom_eq_runs_the_same_calls(built):
    m = built.module
    for needle in (1, 2, 7):
        la, lb = [], []
        ca = [EqList(1, la), EqList(2, la), EqList(3, la)]
        cb = [EqList(1, lb), EqList(2, lb), EqList(3, lb)]
        a = outcome(m.has, [ca, EqList(needle, la)])
        b = outcome(interp(m, "has"), [cb, EqList(needle, lb)])
        assert a == b and la == lb and la, (la, lb)
    # identity shortcut: the same object does not call __eq__ (PyObject_RichCompareBool)
    la, lb = [], []
    xa, xb = EqList(1, la), EqList(1, lb)
    assert outcome(m.has, [[xa], xa]) == outcome(interp(m, "has"), [[xb], xb])
    assert la == lb == []
    nan = float("nan")
    assert m.has([nan], nan) is True and interp(m, "has")([nan], nan) is True


# --- for ---------------------------------------------------------------------------------------

def test_for_unpack_and_errors(built):
    m = built.module
    good = [(1, 2), [3, 4], "ab", {5: 6}.popitem()]
    differential(m, "pairs", lambda: [list(good)])
    differential(m, "pairs", lambda: [[]])
    differential(m, "pairs", lambda: [[(1, 2, 3)]])                      # too many, exact tuple
    differential(m, "pairs", lambda: [[[1, 2, 3]]])                      # too many, exact list
    differential(m, "pairs", lambda: [["abc"]])                          # too many, other type
    differential(m, "pairs", lambda: [[{1: 1, 2: 2, 3: 3}]])            # too many, exact dict
    differential(m, "pairs", lambda: [[Three()]])                        # too many, any iterable
    differential(m, "pairs", lambda: [[(1,)]])                           # not enough
    differential(m, "pairs", lambda: [[()]])
    differential(m, "pairs", lambda: [[""]])
    differential(m, "pairs", lambda: [[5]])                              # non-iterable int
    differential(m, "pairs", lambda: [[None]])
    differential(m, "pairs", lambda: [[1.5]])
    differential(m, "pairs", lambda: [[(1, 2), (1, 2, 3)]])              # error after one item
    differential(m, "triples", lambda: [[(1, 2, 3), (4, 5, 6)]])
    differential(m, "triples", lambda: [[(1, 2)]])
    differential(m, "triples", lambda: [[(1, 2, 3, 4)]])
    differential(m, "triples", lambda: [[{1: 1, 2: 2, 3: 3}]])
    differential(m, "pairs", lambda: [5])                                # not iterable: TypeError
    differential(m, "pairs", lambda: [None])


def test_for_over_objects_break_continue_return(built):
    m = built.module
    differential(m, "each", lambda: [[1, None, 2, None, 3]])
    differential(m, "each", lambda: [[]])
    differential(m, "each", lambda: [(1, 2)])
    differential(m, "each", lambda: ["abc"])
    differential(m, "each", lambda: [{1: 1, 2: 2}])
    differential(m, "each", lambda: [range(4)])
    differential(m, "each", lambda: [5])                                 # TypeError
    differential(m, "first_after", lambda: [[1, 2, 3, 4], 3])
    differential(m, "first_after", lambda: [[1, 2, 3, 4], 9])
    differential(m, "return_inside", lambda: [[7, 8]])
    differential(m, "return_inside", lambda: [[]])
    differential(m, "nested", lambda: [[[1, 2, None, 3], [4, None], [5]]])
    differential(m, "nested", lambda: [[[1], 5]])                        # inner non-iterable
    differential(m, "items_of", lambda: [{"a": 1, "b": 2}])
    differential(m, "items_of", lambda: [{}])
    differential(m, "mutate_while_iterating", lambda: [{"a": 1}])        # RuntimeError: size changed
    differential(m, "mutate_while_iterating", lambda: [{}])


def test_for_calls_user_iter_and_next_the_same_times(built):
    m = built.module

    class It:
        def __init__(self, log, n):
            self.log, self.n, self.i = log, n, 0

        def __iter__(self):
            self.log.append("iter")
            return self

        def __next__(self):
            self.log.append(("next", self.i))
            if self.i >= self.n:
                raise StopIteration
            self.i += 1
            return self.i

    for n in (0, 3):
        la, lb = [], []
        a = outcome(m.each, [It(la, n)])
        b = outcome(interp(m, "each"), [It(lb, n)])
        assert a == b and la == lb and la

    class Boom(It):
        def __next__(self):
            r = super().__next__()
            if r == 2:
                raise ValueError("boom")
            return r

    la, lb = [], []
    a = outcome(m.each, [Boom(la, 5)])
    b = outcome(interp(m, "each"), [Boom(lb, 5)])
    assert a == b and a[0] == "raise" and la == lb


def test_early_exit_releases_the_iterator(built):
    m = built.module
    died = []

    class Gen:
        def __init__(self, tag):
            self.tag = tag

        def __iter__(self):
            return iter([1, 2, 3])

        def __del__(self):
            died.append(self.tag)

    class Iter:
        def __init__(self, tag):
            self.tag = tag

        def __iter__(self):
            return self

        def __next__(self):
            return 1

        def __del__(self):
            died.append(self.tag)

    for fn, name in ((m.first_after, "c"), (interp(m, "first_after"), "i")):
        died.clear()
        assert fn(Iter(name), 1) == 1
        gc.collect()
        assert died == [name], (name, died)
    for fn, name in ((m.return_inside, "c"), (interp(m, "return_inside"), "i")):
        died.clear()
        fn(Iter(name))
        gc.collect()
        assert died == [name]


# --- f-strings ---------------------------------------------------------------------------------

def test_fstring(built):
    m = built.module
    for x in (1, "s", 2.5, None, True, (1, 2), [1, "a"], b"by", "é", 10**30):
        for y, n in ((0.0, 0), (3.14159, 42), (-2.5, -7)):
            differential(m, "fmt", lambda x=x, y=y, n=n: [x, y, n])
    differential(m, "fmt_one", lambda: ["str"])
    differential(m, "fmt_one", lambda: [12])
    differential(m, "fmt_one", lambda: [None])
    differential(m, "fmt_text", lambda: [3])
    differential(m, "fmt_empty", lambda: [])
    differential(m, "fmt_debug", lambda: [12])
    differential(m, "fmt_debug", lambda: ["q"])
    la, lb = [], []
    a = outcome(m.fmt, [Fmt(la), 1.5, 2])
    b = outcome(interp(m, "fmt"), [Fmt(lb), 1.5, 2])
    assert a == b and la == lb and la, (la, lb)
    la, lb = [], []
    outcome(m.fmt_text, [Fmt(la)])
    outcome(interp(m, "fmt_text"), [Fmt(lb)])
    assert la == lb == [("format", ""), ("repr",)]


def test_fstring_format_errors(built):
    m = built.module
    differential(m, "fmt", lambda: [{1: 2}, 1.0, 1])                 # bad spec: TypeError
    differential(m, "fmt", lambda: [None, 1.0, 1])


# --- str concatenation ---------------------------------------------------------------------------

def test_str_add(built):
    m = built.module
    differential(m, "cat", lambda: ["a", "b"])
    differential(m, "cat", lambda: ["", ""])
    differential(m, "cat", lambda: ["é", "ü"])
    differential(m, "cat", lambda: [SubStr("a"), "b"])                 # a str subclass: its __add__
    differential(m, "cat", lambda: ["a", SubStr("b")])                 # its __radd__ wins (subclass)
    assert m.cat(SubStr("a"), "b") == "sub-add"
    assert m.cat("a", SubStr("b")) == "sub-radd"
    differential(m, "cat", lambda: [1, "b"])                           # TypeError text
    differential(m, "cat", lambda: ["a", 1])
    differential(m, "cat", lambda: [None, None])
    differential(m, "cat", lambda: [Money(1), "x"])
    differential(m, "cat", lambda: ["x", Money(2)])
    differential(m, "cat", lambda: [b"a", "b"])
    differential(m, "cat", lambda: [1, 2])                             # int + int through the object path
    differential(m, "cat", lambda: [[1], [2]])
    differential(m, "cat", lambda: [(1,), (2,)])
    differential(m, "cat_in_place", lambda: ["ab", "cd"])
    differential(m, "cat_in_place", lambda: [SubStr("a"), "b"])


def test_augmented_assignment_on_objects_is_in_place(built):
    m = built.module
    xs, ys = [1], [2]
    out = m.grow(xs, ys)
    assert out == [1, 2] and xs == [1] and ys == [2]
    differential(m, "grow", lambda: [[1], [2]])
    differential(m, "grow", lambda: [(1,), [2]])           # list += tuple extends in place
    differential(m, "grow", lambda: [{"a": 1}, "xy"])      # list += dict / str extends too
    differential(m, "grow", lambda: [5, [1]])               # TypeError text of the in-place op
    differential(m, "grow", lambda: [[1], 5])


def test_every_augmented_operator_on_an_object_is_the_in_place_one(built):
    m = built.module
    la, lb = [], []
    a, b = m.Acc(la), m.Acc(lb)
    before = deopts(m)
    assert m.aug_ops(a, 3) is a
    assert deopts(m) == before
    assert interp(m, "aug_ops")(b, 3) is b
    assert la == lb == [("iadd", 3), ("isub", 3), ("imul", 3), ("itruediv", 3),
                                    ("ifloordiv", 3), ("imod", 3)]
    xs = ["a", "b"]
    assert m.aug_list(xs, 2) is xs and xs == ["a", "b"] * 4        # *= and += extend the same list
    differential(m, "aug_list", lambda: [["a", "b"], 3])
    differential(m, "aug_list", lambda: [[], 3])
    differential(m, "aug_list", lambda: [("a", "b"), 2])           # a tuple: *= makes a new one
    differential(m, "aug_list", lambda: ["ab", 2])


def test_empty_displays_and_method_calls(built):
    m = built.module
    differential(m, "empties", lambda: [])
    assert m.empties() == [{}]
    differential(m, "lookup_default", lambda: [{"a": 5}, "a"])
    differential(m, "lookup_default", lambda: [{"a": 5}, "b"])
    differential(m, "lookup_default", lambda: [{}, "b"])


def test_generated_c_calls_only_documented_helpers(tmp_path):
    import re
    path = tmp_path / "strmod.py"
    path.write_text(SOURCE)
    proved, _ = verify.verify(frontend.lower(path))
    c = cgen.generate(proved, path, path.name)
    body = re.sub(r"/\*.*?\*/", "", c, flags=re.S)
    body = re.sub(r'"(?:\\.|[^"\\])*"', '""', body)
    called = {n for n in re.findall(r"\b(tp_[a-z0-9_]+)\s*\(", body)}
    api = (cbuild.RUNTIME_DIR / "API.md").read_text()
    documented = set(re.findall(r"\b(tp_[a-z0-9_]+)\s*\(", api))
    defined = set(re.findall(r"^static[^\n(]*\b(tp_[a-z0-9_]+)\s*\(", body, flags=re.M))
    for h in ("tp_getitem", "tp_setitem", "tp_contains", "tp_format", "tp_build_string", "tp_unpack",
              "tp_get_iter", "tp_iter_next", "tp_new_list", "tp_new_dict"):
        assert h in called, f"{h} is not used by the generated C of the test module"
    assert sorted(called - documented - defined) == []


# --- no reference leaks --------------------------------------------------------------------------

def test_no_reference_is_leaked_or_dropped(built):
    m = built.module
    key, val, other = object(), object(), object()
    obj = type("O", (), {"__eq__": lambda s, o: False, "__hash__": lambda s: 1})()
    bag = [obj]

    def churn():
        d = {key: val}
        for _ in range(200):
            m.get(d, key)
            m.put(d, key, val)
            m.has([obj], obj)
            m.has_not(bag, other)
            m.has_dict(d, key)
            m.each(bag)
            m.pairs([(obj, obj)])
            m.fmt_one(obj)
            m.fmt_text(obj)
            m.items_of({"a": obj})
            m.grow(bag, bag)
            for bad in (lambda: m.get({}, key), lambda: m.pairs([(obj,)]), lambda: m.pairs([(obj,) * 3]),
                        lambda: m.get_list([obj], 5), lambda: m.put({}, [], obj),
                        lambda: m.has_dict({}, [obj]), lambda: m.each(5), lambda: m.cat(obj, obj)):
                with contextlib.suppress(Exception):
                    bad()

    churn()
    gc.collect()
    before = [sys.getrefcount(x) for x in (key, val, other, obj)]
    churn()
    gc.collect()
    after = [sys.getrefcount(x) for x in (key, val, other, obj)]
    assert before == after, (before, after)


# --- the wordfreq benchmark --------------------------------------------------------------------

def run_main(main, n):
    old = sys.argv
    sys.argv = ["wordfreq", str(n)]
    buf = io.StringIO()
    try:
        with contextlib.redirect_stdout(buf):
            main()
    finally:
        sys.argv = old
    return buf.getvalue()


@pytest.fixture(scope="module")
def wordfreq(tmp_path_factory):
    d = tmp_path_factory.mktemp("wordfreq")
    src = "# typedpython: compiled\n" + WORDFREQ.read_text()
    proved, diags, m = build_source(d, src, name="wordfreq")
    spec = importlib.util.spec_from_file_location("wordfreq_plain", WORDFREQ)
    plain = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(plain)
    return Built(proved, diags, m), plain


def test_wordfreq_functions_compile(wordfreq):
    built, _ = wordfreq
    names = {f.name for f in built.proved.functions}
    assert {"next_state", "word_of", "main"} <= names, built.proved.skipped


def test_wordfreq_output_equals_cpython(wordfreq):
    built, plain = wordfreq
    m = built.module
    for n in (0, 1, 2, 50, 3000):
        compiled = run_main(m.main, n)
        assert compiled == run_main(plain.main, n)
        assert compiled == run_main(interp(m, "main"), n)
    assert deopts(m) == 0
    for i in (0, 1, 25, 26, 17575, 17576, 99999, 10**9):
        assert m.word_of(i) == plain.word_of(i)
    for bad in (-17576, -20000, True, 1.5, None, "x", 2**70):
        assert outcome(m.word_of, [bad]) == outcome(plain.word_of, [bad])


def test_wordfreq_main_n_100000(wordfreq):
    built, plain = wordfreq
    m = built.module
    assert run_main(m.main, 100000) == run_main(interp(m, "main"), 100000)


def test_wordfreq_main_errors_equal_cpython(wordfreq):
    built, plain = wordfreq
    m = built.module
    old = sys.argv
    try:
        for argv in (["w"], ["w", "x"], ["w", "1.5"], ["w", "-3"]):
            sys.argv = list(argv)
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                a = outcome(m.main, [])
                b = outcome(plain.main, [])
            assert a == b, (argv, a, b)
    finally:
        sys.argv = old


# --- the exact-type guards matter ---------------------------------------------------------------

def mutant_runtime(tmp_path, macro):
    """A copy of the runtime header whose fast-path guard `macro` accepts subclasses too."""
    mutant = tmp_path / "runtime"
    shutil.copytree(cbuild.RUNTIME_DIR, mutant)
    text = (mutant / "tp_runtime.h").read_text()
    exact = {"TP_FASTPATH_GUARD_DICT": "PyDict_CheckExact", "TP_FASTPATH_GUARD_STR": "PyUnicode_CheckExact"}[macro]
    loose = exact.replace("CheckExact", "Check")
    line = f"#define {macro}(o) {exact}(o)"
    assert line in text, line
    (mutant / "tp_runtime.h").write_text(text.replace(line, f"#define {macro}(o) {loose}(o)"))
    return mutant


def test_the_fast_path_guards_are_the_single_mutation_points():
    header = (cbuild.RUNTIME_DIR / "tp_runtime.h").read_text()
    assert header.count("TP_FASTPATH_GUARD_DICT(") >= 3        # the define and its uses
    assert header.count("TP_FASTPATH_GUARD_STR(") >= 2


def test_a_dict_guard_that_accepts_subclasses_is_caught(tmp_path):
    """Mutation: the dict fast paths are guarded by the exact type. Built against a header whose
    guard is `PyDict_Check`, a subclass overriding __getitem__ / __setitem__ is bypassed, and the
    differential tests above fail; here the same module is built both ways to show it."""
    (tmp_path / "good").mkdir()
    (tmp_path / "bad").mkdir()
    _, _, good = build_source(tmp_path / "good", SOURCE)
    _, _, bad = build_source(tmp_path / "bad", SOURCE,
                             runtime_dir=mutant_runtime(tmp_path, "TP_FASTPATH_GUARD_DICT"))
    assert good.get(SubDict({"a": 1}), "a") == ("sub-get", "a")
    assert bad.get(SubDict({"a": 1}), "a") == 1            # the mutant skips the override: caught
    d, e = SubDict(), SubDict()
    good.put(d, "a", 1)
    bad.put(e, "a", 1)
    assert dict.__getitem__(d, "a") == ("sub-set", 1)
    assert dict.__getitem__(e, "a") == 1
    with pytest.raises(AssertionError):
        differential(bad, "get", lambda: [SubDict({"a": 1}), "a"])


def test_a_str_guard_that_accepts_subclasses_is_caught(tmp_path):
    (tmp_path / "bad").mkdir()
    _, _, bad = build_source(tmp_path / "bad", SOURCE,
                             runtime_dir=mutant_runtime(tmp_path, "TP_FASTPATH_GUARD_STR"))
    assert bad.cat(SubStr("a"), "b") == "ab"               # the override ("sub-add") is skipped
    with pytest.raises(AssertionError):
        differential(bad, "cat", lambda: [SubStr("a"), "b"])
