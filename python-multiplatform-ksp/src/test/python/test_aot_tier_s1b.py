"""TypedPython AOT, tier S step 1b (#443): the str methods, the Unicode tables generated from the building
interpreter, `list[str]` and `str` fields of compiled classes (docs/design/typedpython-aot.md 4.3.1).

Differential against the reference interpreter, as test_aot_tier_s1.py (stdout compared as bytes, the exit status, the
last stderr line). Red before S1b: every method call is `aot/call` ("the call of `s.split` is outside the first AOT
step"), a `list[str]` annotation is `aot/type`, and a `str` field leaves its class uncompiled.
"""
from __future__ import annotations

import random
import sys
from pathlib import Path

import pytest

from test_aot_tier_s1 import CORPUS, _compare, _write
from typedpython import aot
from typedpython.aot import unicode as ucd


def test_methods_over_every_kind(tmp_path: Path):
    _compare(tmp_path, CORPUS + """

SEPS = ", "


def methods(s: str) -> None:
    print(repr(s.lower()), repr(s.upper()), s.isalpha(), s.isdigit())
    print(s.split(), s.split(None, 1), s.split(" "), s.split(" ", 1), s.split("l"), s.split(maxsplit=0))
    print(repr(s.strip()), repr(s.lstrip()), repr(s.rstrip()), repr(s.strip("hd ")), repr(s.strip(None)))
    print(s.startswith("h"), s.endswith(("d", "y")), s.startswith("", 2), s.endswith("", 99), s.startswith(("x",), 1, 3))
    print(s.find("l"), s.find(""), s.find("o", 5), s.find("o", -3, -1), s.find("zz"), s.find("", 99))
    print(repr(s.replace("l", "L")), repr(s.replace("", "|")), repr(s.replace("", "|", 2)), repr(s.replace("o", "", 1)),
          repr(s.replace("l", "L", 0)), repr(s.replace("l", "L", -1)))
    parts = s.split()
    print(SEPS.join(parts), "".join(parts), "-".join([]), len(parts), parts)


if __name__ == "__main__":
    for k in range(6):
        methods(corpus(k))
    print("\\u03a3\\u03a3\\u03a3 \\u0391\\u03a3 \\u03a3x \\u03a3. a\\u03a3'".lower())
    print("stra\\u00dfe \\u0131\\u0130 \\u01c5 \\ufb03".upper(), "\\u0130".lower(), len("\\u0130".lower()))
    print("\\u0661\\u0662".isdigit(), "\\u00b2".isdigit(), "\\u00e9t\\u00e9".isalpha(), "a1".isalpha())
    print(" \\t\\n\\x0b\\x0c\\r\\x1c\\x1d\\x1e\\x1f\\x85\\xa0\\u1680\\u2000\\u2028\\u3000x ".split())
""", [[]])


def test_split_errors_and_counts(tmp_path: Path):
    _compare(tmp_path, """
import sys


if __name__ == "__main__":
    mode = int(sys.argv[1])
    big = int(sys.argv[2])
    if mode == 0:
        print("a,b".split(""))
    elif mode == 1:
        print("a b c".split(None, big))
    elif mode == 2:
        print("aaa".replace("a", "b", big))
    elif mode == 3:
        print("abc".find("c", big, -big), "abc".startswith("a", -big, big))
""", [["0", "0"], ["1", str(2**70)], ["1", str(-2**70)], ["2", str(2**70)], ["3", str(2**70)], ["3", str(-2**70)]])


def test_lists_of_str(tmp_path: Path):
    _compare(tmp_path, """
def words(s: str) -> list[str]:
    return s.split()


def count(xs: list[str], w: str) -> int:
    n = 0
    for x in xs:
        if x == w:
            n += 1
    return n


def first(xs: list[str]) -> str:
    return xs[0] if xs else ""


if __name__ == "__main__":
    ws = words("the cat and the hat and the bat")
    print(ws, len(ws), ws[1], ws[-1], count(ws, "the"), first(ws), first([]))
    names: list[str] = ["\\u00e9", "it's", 'q"', "\\n"]
    print(names, repr(names), str(names), "|".join(names))
    empty: list[str] = []
    print(empty, len(empty), bool(empty), bool(names))
    other = names
    names = ws
    print(other[0], names[0])
    for w in "x y".split():
        print(w)
    print(ws[100])
""", [[]])


def test_str_fields_of_compiled_classes(tmp_path: Path):
    src = _compare(tmp_path, """
class Person:
    __slots__ = ("name", "age")
    name: str
    age: int

    def __init__(self, name: str, age: int) -> None:
        self.name = name
        self.age = age


def make(n: str) -> Person:
    return Person(n + "!", 3)


def rename(p: Person, n: str) -> str:
    old = p.name
    p.name = n
    return old + "->" + p.name


def local_only(k: int) -> int:
    q = Person("x" * k, k)
    return len(q.name)


def in_a_loop(n: int) -> int:
    t = 0
    for i in range(n):
        m = Person(str(i) * 3, i)
        t += len(m.name) + len(rename(m, "z"))
    return t


if __name__ == "__main__":
    p = make("ann")
    print(p.name, len(p.name), p.age, p.name.upper())
    print(rename(p, "bob"), p.name)
    print(local_only(4), in_a_loop(2000))
""", [[]])
    report = aot.explain_alloc(src)
    assert "-> virtual" in report and "-> heap-rc" in report, report


def test_the_case_and_property_tables_match_the_interpreter():
    """The generated tables answer what this interpreter answers: the derived Cased / Case_Ignorable properties
    reproduce lower()'s final sigma over random strings (the rule only reads them), and each mapping and property
    is the interpreter's own."""
    cased, ign = ucd.property_set("cased"), ucd.property_set("ignorable")
    lower = dict(ucd.mapping("lower"))

    def model(s: str) -> str:
        out = []
        for i, ch in enumerate(s):
            if ch != "Σ":
                out.append("".join(map(chr, lower.get(ord(ch), (ord(ch),)))))
                continue
            j = i - 1
            while j >= 0 and ord(s[j]) in ign:
                j -= 1
            fs = j >= 0 and ord(s[j]) in cased
            if fs and i + 1 < len(s):
                j = i + 1
                while j < len(s) and ord(s[j]) in ign:
                    j += 1
                fs = j == len(s) or ord(s[j]) not in cased
            out.append("ς" if fs else "σ")
        return "".join(out)

    pool = [chr(c) for c in sorted(ign)[:300] + sorted(cased)[:600]] + list("ab1 .Σ'ͅ")
    rng = random.Random(7)
    for _ in range(20000):
        s = "".join(rng.choice(pool) for _ in range(rng.randint(1, 8)))
        assert model(s) == s.lower(), repr(s)
    assert ucd.property_set("space") == frozenset(c for c in range(0x110000) if chr(c).isspace())


def test_a_program_gets_only_the_tables_it_uses(tmp_path: Path):
    src = _write(tmp_path, """
def f(s: str) -> str:
    return s.upper() + s.strip()


if __name__ == "__main__":
    print(f(" x "))
""")
    c_text = aot.generate(src)
    assert "TPA_UCD_UPPER" in c_text and "TPA_UCD_SPACE" in c_text and "TPA_UNIDATA_VERSION" in c_text
    assert "TPA_UCD_LOWER" not in c_text and "TPA_UCD_ALPHA" not in c_text
    assert c_text.index("TPA_UCD_UPPER") < c_text.index('#include "tp_aot_str.h"')


@pytest.mark.skipif(sys.platform == "win32", reason="mutating a generated table: one platform is enough")
def test_a_wrong_table_entry_is_caught(tmp_path: Path):
    """Mutant: one wrong entry of the upper table (a -> B) must change the output the differential test compares."""
    import subprocess
    from test_aot_tier_s1 import _env, _need_cc
    from typedpython.aot import cc
    _need_cc()
    src = _write(tmp_path, """
if __name__ == "__main__":
    print("abc".upper())
""")
    c_text = aot.generate(src).replace("{0x61, 1u << 24 | 0x41, 0x0, 0x0}", "{0x61, 1u << 24 | 0x42, 0x0, 0x0}")
    exe = cc.compile_executable(c_text, tmp_path / "mut")
    out = subprocess.run([str(exe)], capture_output=True, env=_env()).stdout
    assert out.strip() == b"BBC", out
