"""Regenerates the table of non-printable code points that `runtime/tp_aot_int.h` embeds (SPEC N-17, #462).

The table is what the AOT runtime needs to write `repr(s)` exactly as the reference interpreter does inside the message
`ValueError: invalid literal for int() with base 10: <repr>`: a character is shown as it is when `str.isprintable()` is
true for it and escaped (`\\xNN`, `\\uNNNN`, `\\UNNNNNNNN`) otherwise, and `isprintable` depends on the Unicode version of
the interpreter that runs this script. The table holds the maximal ranges of code points from U+0080 up that are NOT
printable (ASCII is decided in the header itself), as inclusive (first, last) pairs, sorted. A code point in no range is
printable.

The table in the header is the one of Unicode 17.0.0 (the Unicode version of CPython 3.15). It is an agent proposal, not
owner-confirmed, that the runtime pins one version; a test (test_aot_repr.py) compares the table, and the runtime's `repr`
over every code point, with the interpreter that runs the tests, and skips only when that interpreter's Unicode version
differs from the pinned one.

Regenerate (with the interpreter whose Unicode version you want to pin, from the repository root):

    python-multiplatform-ksp/.venv/bin/python python-multiplatform-ksp/tools/gen_aot_nonprintable.py --write

`--write` replaces the text between the BEGIN GENERATED and END GENERATED lines of
python-multiplatform-ksp/src/main/python/typedpython/runtime/tp_aot_int.h. Without an option the block is printed.
`--check` exits 1 when the block in the header differs from what this interpreter generates.
"""
from __future__ import annotations

import sys
import unicodedata
from pathlib import Path

HEADER = (Path(__file__).resolve().parents[1] / "src" / "main" / "python" / "typedpython" / "runtime" / "tp_aot_int.h")
BEGIN = "/* BEGIN GENERATED: tpa_int__nonprint (python-multiplatform-ksp/tools/gen_aot_nonprintable.py) */"
END = "/* END GENERATED: tpa_int__nonprint */"


def ranges() -> list[tuple[int, int]]:
    out: list[tuple[int, int]] = []
    start = None
    for cp in range(0x80, 0x110000):
        np = not chr(cp).isprintable()
        if np and start is None:
            start = cp
        elif not np and start is not None:
            out.append((start, cp - 1))
            start = None
    if start is not None:
        out.append((start, 0x10FFFF))
    return out


def block() -> str:
    rs = ranges()
    flat = [x for r in rs for x in r]
    lines = [BEGIN,
             f"#define TPA_INT_UNICODE_VERSION \"{unicodedata.unidata_version}\"      /* the Unicode version of the table */",
             f"#define TPA_INT__NONPRINT_N {len(rs)}                              /* the number of (first, last) pairs */",
             "static const uint32_t tpa_int__nonprint[2 * TPA_INT__NONPRINT_N] = {"]
    for i in range(0, len(flat), 8):
        chunk = ", ".join(f"0x{v:X}" for v in flat[i:i + 8])
        lines.append(f"    {chunk}{',' if i + 8 < len(flat) else ''}")
    lines += ["};", END]
    return "\n".join(lines)


def main(argv: list[str]) -> int:
    text = block()
    if "--write" in argv or "--check" in argv:
        body = HEADER.read_text(encoding="utf-8")
        a, b = body.find(BEGIN), body.find(END)
        if a < 0 or b < 0:
            print(f"{HEADER} has no generated block", file=sys.stderr)
            return 2
        new = body[:a] + text + body[b + len(END):]
        if "--check" in argv:
            return 0 if new == body else 1
        HEADER.write_text(new, encoding="utf-8")
        print(f"wrote {len(ranges())} ranges, Unicode {unicodedata.unidata_version}")
        return 0
    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
