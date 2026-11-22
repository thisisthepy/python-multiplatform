"""TypedPython AOT, tier S step 1a (PR-S1a, #443): the `str` value (docs/design/typedpython-aot.md 4.3.1, runtime
tp_aot_str.h). Tier S is in 1.0 (owner, 2026-11-21 21:55).

Every program is differential: built with the AOT mode and run beside the reference CPython on the same arguments;
stdout is compared as bytes (a lone surrogate written through surrogateescape is a raw byte), as are the exit status
and the last line of stderr. Red before S1a: every program below is refused (`aot/str`: "a string is only supported
as a literal argument of print"; `aot/f-string`; `aot/print-keyword`), so `aot.build` raises AotError.

The sanitizer job runs every program with AddressSanitizer, UBSan and (Linux) LeakSanitizer on: the counts of every
str (names, temporaries, the in-place `+=`, the loop's held string, returned strings) are checked there.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import textwrap
from pathlib import Path

import pytest

from typedpython import aot, cbuild
from typedpython.aot import cc as cc_mod


def _need_cc() -> None:
    if sys.version_info < (3, 15):
        pytest.skip("the str runtime follows CPython 3.15 (UTF-8 mode, the 3.15 messages)")
    try:
        if cbuild._is_msvc():
            cbuild.msvc_env()
        elif shutil.which(cbuild.compiler()[0]) is None:
            pytest.skip("no C compiler on PATH")
    except Exception as e:      # noqa: BLE001
        pytest.skip(f"no C compiler: {e}")


def _env() -> dict[str, str]:
    env = dict(os.environ)
    if cc_mod.sanitizing() and sys.platform.startswith("linux"):
        env["ASAN_OPTIONS"] = env.get("ASAN_OPTIONS", "") + ":detect_leaks=1"
    return env


def _write(tmp_path: Path, source: str, name: str = "prog.py") -> Path:
    p = tmp_path / name
    p.write_text(textwrap.dedent(source).lstrip(), encoding="utf-8")
    return p


def _last(stderr: bytes) -> bytes:
    lines = stderr.strip().splitlines()
    return lines[-1] if lines else b""


def _compare(tmp_path: Path, source: str, argv_cases: list[list[str]], name: str = "prog.py") -> Path:
    _need_cc()
    src = _write(tmp_path, source, name)
    exe = aot.build(src, tmp_path / (src.stem + "_bin"))
    for args in argv_cases:
        py = subprocess.run([sys.executable, str(src), *args], capture_output=True)
        nat = subprocess.run([str(exe), *args], capture_output=True, env=_env())
        assert nat.returncode == py.returncode, (args, nat.returncode, py.returncode, nat.stderr[-2000:])
        assert nat.stdout == py.stdout, (args, nat.stdout[-3000:], py.stdout[-3000:])
        if py.returncode != 0:
            assert _last(nat.stderr) == _last(py.stderr), (args, nat.stderr[-2000:], py.stderr[-2000:])
        else:
            assert nat.stderr == py.stderr, (args, nat.stderr[-2000:], py.stderr[-2000:])
    return src


# The corpus: ASCII, Latin-1, BMP, astral, a lone surrogate and the empty string. A surrogate is built with chr()
# (the source text stays valid UTF-8) and every result is printed through repr(), which escapes it.
CORPUS = """
def corpus(k: int) -> str:
    if k == 0:
        return "hello, world"
    if k == 1:
        return "d\\u00e9j\\u00e0 vu \\u00ff"
    if k == 2:
        return "\\u4e16\\u754c \\u0394x"
    if k == 3:
        return "a\\U0001F600b\\U00010348"
    if k == 4:
        return "x" + chr(0xDC80) + "y" + chr(0xD800)
    return ""
"""


def test_operators_on_every_kind(tmp_path: Path):
    _compare(tmp_path, CORPUS + """

def show(s: str, t: str) -> None:
    print(repr(s + t), repr(s * 2), repr(2 * t), repr(s * 0), repr(s * -1), len(s), len(s + t))
    print(s == t, s != t, s < t, s <= t, s > t, s >= t, s == s, s < s + "\\u0000")
    print(t in s, t not in s, "" in s, s in s + t, repr(s[:1]) if s else "-")
    print(bool(s), "yes" if s else "no", repr(min(s, t)), repr(max(s, t)))


def indexing(s: str) -> None:
    n = len(s)
    for i in range(-n, n):
        print(i, repr(s[i]), end=" ")
    print()
    for a in range(-n - 2, n + 3):
        for b in range(-n - 2, n + 3):
            print(repr(s[a:b]), repr(s[a:b:2]), repr(s[b:a:-1]), repr(s[a::-3]), repr(s[:b:3]), end=";")
    print()
    print(repr(s[::-1]), repr(s[::]), repr(s[1:]), repr(s[:-1]))


def iterate(s: str) -> str:
    out = ""
    for ch in s:
        out += "[" + ch + "]"
        if ch == " ":
            continue
        if ch == "z":
            break
    return out


if __name__ == "__main__":
    for i in range(6):
        for j in range(6):
            show(corpus(i), corpus(j))
        indexing(corpus(i))
        print(repr(iterate(corpus(i))))
""", [[]])


def test_producers_and_consumers(tmp_path: Path):
    _compare(tmp_path, CORPUS + """

GREETING = "h\\u00e9llo"
SHOUT: str = GREETING + "!" * 3


def numbers(x: int, f: float, b: bool) -> str:
    return str(x) + "," + repr(x) + "," + str(f) + "," + repr(f) + "," + str(b) + "," + repr(b)


if __name__ == "__main__":
    print(numbers(0, 0.0, False), numbers(-7, -0.5, True), numbers(9223372036854775807, 1e300, True))
    print(numbers(-9223372036854775807 - 1, 1.0 / 3.0, False), numbers(1, 1e308 * 10.0, True))
    big = int("123456789012345678901234567890")
    print(str(big), repr(-big), str(big * big))
    for k in range(6):
        s = corpus(k)
        print(repr(s), repr(repr(s)), repr(str(s)), str(s) == s)
    print(repr("it's"), repr('say "x"'), repr("both ' and \\""), repr("\\t\\n\\r\\\\\\x00\\x7f\\u00a0\\u00ad\\U0001F600"))
    print(ord("a"), ord("\\u00e9"), ord("\\u4e16"), ord("\\U0001F600"), ord(chr(0xDC80)))
    print(repr(chr(0)), repr(chr(255)), repr(chr(256)), repr(chr(0xFFFF)), repr(chr(0x10FFFF)), repr(chr(True)))
    digits = "\\u0661\\u0662"
    print(int("42"), int(" -17 "), int("1_000"), int(digits), int(" " + digits + "\\u3000"), int("99999999999999999999999"))
    print(GREETING, SHOUT, len(SHOUT), str(), repr(str()))
    print(f"{GREETING}|{GREETING!r}|{3!r}|{2.5!r}|{True!s}|{1.25:.1f}|{-3}|{SHOUT=}")
    msg = f"{len(GREETING)} chars: {GREETING[1]!r}"
    print(msg, len(msg))
""", [[]])


def test_print_sep_end_and_stream_writes(tmp_path: Path):
    _compare(tmp_path, """
import sys


if __name__ == "__main__":
    print("a", "b", "c", sep="")
    print("a", 1, 2.5, True, sep=", ", end=".\\n")
    print("x", sep=None, end=None)
    print(sep="-", end="|\\n")
    print("\\u4e16", "\\U0001F600", sep="\\u00e9")
    n = sys.stdout.write("w\\u00e9\\n")
    print(n)
    m = sys.stderr.write("e" + chr(0xD800) + "\\U0001F600" + chr(0xDC80) + "\\n")
    print(m)
    sys.stdout.write("no newline")
    print()
""", [[]])


def test_lone_surrogates_on_standard_output(tmp_path: Path):
    """UTF-8 mode: U+DC80..U+DCFF is written back as its raw byte (surrogateescape); any other surrogate raises
    CPython's UnicodeEncodeError, the arguments before it written with the separator, nothing of it written
    (observed on 3.15.0rc1t: `print("a", "x\\ud800")` writes `a ` and raises)."""
    _compare(tmp_path, """
import sys


if __name__ == "__main__":
    mode = int(sys.argv[1])
    if mode == 0:
        print("a" + chr(0xDC80) + chr(0xDCFF) + "b")
    elif mode == 1:
        print("a", "x" + chr(0xD800) + "y")
    elif mode == 2:
        print("ab" + chr(0xD800) + chr(0xD801))
    elif mode == 3:
        print(chr(0xDC80) + chr(0xD800) + chr(0xDC81))
    elif mode == 4:
        print("ok", sep=chr(0xDFFF))
        print("a", "b", sep=chr(0xDFFF))
    elif mode == 5:
        sys.stdout.write("w" + chr(0xDBFF))
    elif mode == 6:
        print(f"pre {chr(0xD800)} post")
""", [[str(m)] for m in range(7)])


def test_exact_error_messages(tmp_path: Path):
    _compare(tmp_path, """
import sys


if __name__ == "__main__":
    mode = int(sys.argv[1])
    big = int(sys.argv[2])
    s = "abc"
    if mode == 0:
        print(s[3])
    elif mode == 1:
        print(s[-4])
    elif mode == 2:
        print(s[big])
    elif mode == 3:
        print(s[::big])
    elif mode == 4:
        print(ord("ab"))
    elif mode == 5:
        print(ord(""))
    elif mode == 6:
        print(chr(big))
    elif mode == 7:
        print(int("12x"))
    elif mode == 8:
        print(int("12" + chr(0xD800)))
    elif mode == 9:
        print(len(s * big))
    elif mode == 10:
        print(len("ab" * 4611686018427387904))
    elif mode == 11:
        print(repr(s[big:]), repr(s[:big]), repr(s[-big::big]))
    elif mode == 12:
        print(repr(sys.argv[big]))
""", [["0", "0"], ["1", "0"], ["2", str(2**70)], ["2", str(-2**70)], ["3", "0"], ["4", "0"], ["5", "0"],
      ["6", "-1"], ["6", str(0x110000)], ["6", str(2**70)], ["6", str(-2**70)], ["7", "0"], ["8", "0"],
      ["9", str(2**70)], ["9", str(-2**70)], ["10", "0"], ["11", str(2**70)], ["11", str(-2**70)],
      ["12", "9"], ["12", str(2**70)]])


def test_arguments_as_the_reference_decodes_them(tmp_path: Path):
    src = _compare(tmp_path, """
import sys


if __name__ == "__main__":
    for i in range(len(sys.argv)):
        if i > 0:
            a = sys.argv[i]
            print(i, repr(a), len(a), -1 if a == "" else ord(a[0]))
""", [["plain", "déjà", "世\U0001F600", ""]])
    if sys.platform == "win32":
        return          # the Windows arguments arrive as UTF-16: there are no undecodable bytes to pass
    exe = src.parent / (src.stem + "_bin")
    raw = [b"\xff\xfe", b"a\x80b", b"\xc3\xa9\xe9"]
    py = subprocess.run([sys.executable, str(src), *raw], capture_output=True)
    nat = subprocess.run([str(exe), *raw], capture_output=True, env=_env())
    assert (nat.returncode, nat.stdout) == (py.returncode, py.stdout), (nat.stdout, py.stdout, nat.stderr)


def test_in_place_concatenation_never_rewrites_another_holder(tmp_path: Path):
    """`s += t` grows the string in place only when the name owns it and nothing else holds it (design 4.3.1, review
    of #510 F1): a second name, a caller's argument and a loop's held string must keep their value."""
    src = _compare(tmp_path, """
def grow(s: str) -> str:
    s += "!"
    s += s
    return s


def build(n: int) -> str:
    out = ""
    for i in range(n):
        out += chr(97 + i % 26)
    return out


if __name__ == "__main__":
    a = "ab" * 2
    b = grow(a)
    print(a, b)
    t = a
    a += "x"
    print(t, a)
    s = build(5)
    for ch in s:
        s += ch
    print(s)
    w = "\\u00e9" * 3
    w += "\\u4e16"
    w += "z"
    print(repr(w), len(w))
    print(len(build(100000)))
""", [[]])
    c_text = aot.generate(src)
    assert "tpa_str_iadd(&v_out, " in c_text and "tpa_str_iadd(&v_s, " in c_text
    grow_fn = c_text[c_text.index("static int tpf_grow(tpa_str *pv_s"):]
    assert "tpa_str_set(&v_s, pv_s);" in grow_fn[:grow_fn.index("\n}\n")]     # an assigned parameter is an owned copy


def test_exhaustive_repr_of_every_code_point(tmp_path: Path):
    """repr() of every code point 0..0x10FFFF, in chunks, compared with the reference (the printable table and the
    escapes)."""
    _compare(tmp_path, """
if __name__ == "__main__":
    c = 0
    while c < 1114112:
        line = ""
        stop = c + 4096
        while c < stop:
            line += repr(chr(c))
            c += 1
        print(line)
""", [[]])


def test_a_string_free_program_does_not_link_the_str_runtime(tmp_path: Path):
    src = _write(tmp_path, """
def f(n: int) -> int:
    return n * 2


if __name__ == "__main__":
    print("n =", f(21))
    print(f"{f(1)}")
""")
    c_text = aot.generate(src)
    assert "tp_aot_str.h" not in c_text and "tpa_str" not in c_text
    assert 'tpa_print_str("n =", 3);' in c_text           # a plain literal argument is written as it was


def test_str_in_a_library_export_is_refused(tmp_path: Path):
    src = _write(tmp_path, """
def name(n: int) -> str:
    return str(n)
""", "lib.py")
    with pytest.raises(aot.AotError) as e:
        aot.generate_library(src, exports=["name"], prefix="lib")
    assert any(f.rule == "aot/library-str" for f in e.value.findings), e.value
