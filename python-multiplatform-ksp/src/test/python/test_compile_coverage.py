"""What the TypedPython compiler does with each Python construct (design §6.2 table, #23).

Each case is a tiny module under `# typedpython: compiled`. The test builds it, runs `scenario()`
interpreted and compiled, and requires the same `repr`. `TYPED` names the functions the compiler is
expected to give C types to; every other function of the case is expected to be left untyped. Cases
the pipeline cannot yet handle are `xfail(strict=True)` with the reason, so the list stays visible
and a fix flips them to a failure that must be acknowledged.

This table is the requirement list for the stage-2 IR (design §4.3).
"""
import pytest

from typedpython import compiler

pytest.importorskip("Cython", reason="compiling needs the [compile] extra")

MARK = "# typedpython: compiled\n"

CASES: dict[str, tuple[str, set[str], set[str]]] = {}


def case(name: str, source: str, typed: set[str], untyped: set[str]) -> None:
    CASES[name] = (MARK + source, typed, untyped)


case("generator", """
from typing import Iterator

def squares(n: int) -> Iterator[int]:
    for i in range(n):
        yield i * i

def scenario() -> str:
    return repr(list(squares(5)))
""", {"scenario"}, {"squares"})

case("async-def", """
import asyncio

async def twice(n: int) -> int:
    return n * 2

def scenario() -> str:
    return repr(asyncio.run(twice(21)))
""", {"scenario"}, set())

case("closure", """
from typing import Callable

def make(n: int) -> Callable[[int], int]:
    def inner(x: int) -> int:
        return x + n
    return inner

def scenario() -> str:
    return repr(make(2)(40))
""", {"scenario"}, {"make"})

case("lambda", """
def apply(n: int) -> int:
    g = lambda x: x + n
    return g(1)

def scenario() -> str:
    return repr(apply(41))
""", {"scenario"}, {"apply"})

case("comprehension", """
def squares(n: int) -> list[int]:
    return [i * i for i in range(n)]

def table(n: int) -> dict[int, int]:
    return {i: i * 2 for i in range(n)}

def uniq(n: int) -> set[int]:
    return {i % 3 for i in range(n)}

def total(n: int) -> int:
    return sum(i for i in range(n))

def scenario() -> str:
    return repr((squares(5), table(3), sorted(uniq(10)), total(10)))
""", {"scenario"}, {"squares", "table", "uniq", "total"})

case("try-except-finally", """
def safe_div(a: int, b: int) -> int:
    try:
        r = a // b
    except ZeroDivisionError:
        r = -1
    finally:
        pass
    return r

def scenario() -> str:
    return repr((safe_div(10, 2), safe_div(1, 0)))
""", {"scenario", "safe_div"}, set())

case("with-statement", """
import contextlib

def guarded(n: int) -> int:
    total = 0
    with contextlib.suppress(ValueError):
        total = int("x") + n
    return total

def scenario() -> str:
    return repr(guarded(3))
""", {"scenario", "guarded"}, set())

case("f-string", """
def show(n: int, x: float) -> str:
    return f"{n}:{x:.2f}:{n * 2!r}"

def scenario() -> str:
    return show(3, 1.5)
""", {"scenario", "show"}, set())

case("star-unpacking", """
def split(xs: list[int]) -> int:
    first, *rest = xs
    return first * len(rest)

def merge(a: list[int], b: list[int]) -> list[int]:
    return [*a, *b]

def scenario() -> str:
    return repr((split([1, 2, 3]), merge([1], [2, 3])))
""", {"scenario", "split", "merge"}, set())

case("walrus", """
def check(n: int) -> int:
    if (m := n * 2) > 5:
        return m
    return 0

def scenario() -> str:
    return repr((check(1), check(4)))
""", {"scenario", "check"}, set())

case("match-statement", """
def kind(n: int) -> str:
    match n:
        case 0:
            return "zero"
        case 1 | 2:
            return "small"
        case _:
            return "big"

def scenario() -> str:
    return repr([kind(0), kind(2), kind(9)])
""", {"scenario", "kind"}, set())

case("global", """
COUNT = 0

def bump(n: int) -> int:
    global COUNT
    COUNT += n
    return COUNT

def scenario() -> str:
    return repr((bump(1), bump(2), COUNT))
""", {"scenario"}, {"bump"})

case("nonlocal", """
from typing import Callable

def counter() -> Callable[[], int]:
    n = 0
    def step() -> int:
        nonlocal n
        n += 1
        return n
    return step

def scenario() -> str:
    c = counter()
    return repr((c(), c(), c()))
""", {"scenario"}, {"counter"})

case("dict-and-set", """
def histogram(words: list[str]) -> dict[str, int]:
    d: dict[str, int] = {}
    for w in words:
        d[w] = d.get(w, 0) + 1
    return d

def members(n: int) -> int:
    s: set[int] = set()
    for i in range(n):
        s.add(i % 4)
    return len(s)

def scenario() -> str:
    return repr((histogram(["a", "b", "a"]), members(10)))
""", {"scenario", "histogram", "members"}, set())

case("string-methods", """
def shout(s: str) -> str:
    return s.strip().upper().replace("A", "4")

def words(s: str) -> list[str]:
    return s.split(",")

def scenario() -> str:
    return repr((shout("  banana "), words("a,b,c")))
""", {"scenario", "shout", "words"}, set())

case("recursion", """
def fib(n: int) -> int:
    if n < 2:
        return n
    return fib(n - 1) + fib(n - 2)

def fact(n: int) -> int:
    if n <= 1:
        return 1
    return n * fact(n - 1)

def scenario() -> str:
    return repr((fib(15), fact(25)))
""", {"scenario", "fib", "fact"}, set())

case("varargs-kwargs", """
def total(*args: int, **kwargs: int) -> int:
    return sum(args) + sum(kwargs.values())

def scenario() -> str:
    return repr((total(1, 2, 3), total(1, a=4, b=5)))
""", {"scenario"}, {"total"})

case("keyword-only", """
def f(a: int, *, b: int = 2) -> int:
    return a * b

def scenario() -> str:
    return repr((f(3), f(3, b=5)))
""", {"scenario"}, {"f"})

case("slots-class", """
class Point:
    __slots__ = ("x", "y")

    def __init__(self, x: int, y: int) -> None:
        self.x = x
        self.y = y

    def norm1(self) -> int:
        return abs(self.x) + abs(self.y)

def scenario() -> str:
    p = Point(3, -4)
    try:
        p.z = 1  # type: ignore[attr-defined]
    except AttributeError:
        return repr((p.norm1(), "no dynamic attribute"))
    return "dynamic attribute allowed"
""", {"scenario", "Point.__init__", "Point.norm1"}, set())

case("dataclass", """
from dataclasses import dataclass

@dataclass
class Vec:
    x: float
    y: float

    def dot(self, other: "Vec") -> float:
        return self.x * other.x + self.y * other.y

def scenario() -> str:
    a, b = Vec(1.0, 2.0), Vec(3.0, 4.0)
    return repr((a, a.dot(b), a == Vec(1.0, 2.0)))
""", {"scenario", "Vec.dot"}, set())

case("property", """
class Box:
    def __init__(self, w: int) -> None:
        self._w = w

    @property
    def width(self) -> int:
        return self._w

    @width.setter
    def width(self, value: int) -> None:
        self._w = value

def scenario() -> str:
    b = Box(2)
    b.width = 7
    return repr(b.width)
""", {"scenario", "Box.__init__"}, {"Box.width"})

case("staticmethod-classmethod", """
class Tools:
    @staticmethod
    def twice(n: int) -> int:
        return n * 2

    @classmethod
    def make(cls) -> "Tools":
        return cls()

def scenario() -> str:
    return repr((Tools.twice(4), type(Tools.make()).__name__))
""", {"scenario"}, {"Tools.twice", "Tools.make"})

case("inheritance-super", """
class Base:
    def __init__(self, n: int) -> None:
        self.n = n

    def value(self) -> int:
        return self.n

class Child(Base):
    def __init__(self, n: int) -> None:
        super().__init__(n + 1)

    def value(self) -> int:
        return super().value() * 2

def scenario() -> str:
    c = Child(4)
    return repr((c.value(), isinstance(c, Base)))
""", {"scenario", "Base.__init__", "Base.value"}, {"Child.__init__", "Child.value"})


@pytest.mark.parametrize("name", list(CASES))
def test_construct_builds_and_matches_cpython(name, write, tmp_path):
    source, typed, untyped = CASES[name]
    module_name = "cov_" + name.replace("-", "_")
    built = compiler.compile_module(write(f"{module_name}.py", source), tmp_path / "out")
    print("TYPED", name, sorted(built.typed_functions), "UNTYPED", dict(built.untyped))
    compiled, interpreted = built.load(), built.load_interpreted()
    assert compiled.scenario() == interpreted.scenario()
    assert set(built.typed_functions) == typed
    assert set(built.untyped) == untyped
