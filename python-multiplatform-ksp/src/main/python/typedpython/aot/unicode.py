"""The Unicode tables of an AOT program, generated from the interpreter that builds it (tier S1b, design
typedpython-aot.md 4.3.1 "Unicode data"; agent proposal, not owner-confirmed).

`str.lower`, `str.upper`, `str.isalpha`, `str.isdigit` and the whitespace of `split()` / `strip()` depend on CPython's
Unicode database. The AOT compiler runs on the reference CPython, so each table is read from that interpreter's
own `str` methods, code point by code point: the C program then answers exactly what the interpreter that built it
answers. Only the tables a program uses are emitted (`tables_c`), with `unicodedata.unidata_version` recorded in the
C text (`TPA_UNIDATA_VERSION`); an AOT program is one translation unit generated in one process, so its tables
cannot come from two interpreters.

Layout. A property is a two-level bit table: an index of 0x1100 one-byte block numbers (one per 256 code points)
and the distinct 256-bit blocks. A case mapping is the sorted list of the code points whose mapping is not
themselves, each with its one to three result code points, found by binary search. `lower` also needs CPython's
final-sigma rule (`handle_capital_sigma`), which reads the Cased and Case_Ignorable properties; the interpreter does
not expose them, so they are read through `lower()` itself: for a code point c, `("1" + c + "\\u03a3").lower()` ends
in the final sigma exactly when c is cased and not case-ignorable, and `("A" + c + "\\u03a3").lower()` when it is
either. The rule only ever asks whether a code point that is not case-ignorable is cased, so these two facts are
all it needs (checked against the interpreter over random strings in test_aot_tier_s1b.py).
"""
from __future__ import annotations

import functools
import unicodedata

PROPERTIES = ("alpha", "digit", "space", "cased", "ignorable")
MAPPINGS = ("lower", "upper")
_SIGMA = "Σ"
_FINAL = "ς"


@functools.lru_cache(maxsize=None)
def property_set(name: str) -> frozenset[int]:
    """The code points that have the property `name`, as this interpreter answers."""
    if name == "alpha":
        return frozenset(c for c in range(0x110000) if chr(c).isalpha())
    if name == "digit":
        return frozenset(c for c in range(0x110000) if chr(c).isdigit())
    if name == "space":
        return frozenset(c for c in range(0x110000) if chr(c).isspace())
    if name == "cased":       # (meaningful for a code point that is not case-ignorable, module docstring)
        return frozenset(c for c in range(0x110000) if ("1" + chr(c) + _SIGMA).lower()[-1] == _FINAL)
    if name == "ignorable":
        cased = property_set("cased")
        return frozenset(c for c in range(0x110000)
                         if c not in cased and ("A" + chr(c) + _SIGMA).lower()[-1] == _FINAL)
    raise ValueError(name)


@functools.lru_cache(maxsize=None)
def mapping(name: str) -> tuple[tuple[int, tuple[int, ...]], ...]:
    """(code point, its full mapping) for every code point whose `name` (lower, upper) is not itself, sorted.
    The lower mapping of U+03A3 is the non-final sigma; the runtime applies the final-sigma rule."""
    out = []
    for c in range(0x110000):
        m = chr(c).lower() if name == "lower" else chr(c).upper()
        if m != chr(c):
            out.append((c, tuple(ord(x) for x in m)))
    return tuple(out)


def _bits_c(name: str) -> list[str]:
    members = property_set(name)
    blocks: dict[bytes, int] = {}
    index = []
    for b in range(0x1100):
        bits = bytearray(32)
        for c in range(b * 256, b * 256 + 256):
            if c in members:
                bits[(c & 255) >> 3] |= 1 << (c & 7)
        index.append(blocks.setdefault(bytes(bits), len(blocks)))
    assert len(blocks) <= 256
    lines = [f"static const uint8_t tpa_ucd_{name}_index[0x1100] = {{"]
    for i in range(0, len(index), 32):
        lines.append("    " + ", ".join(str(x) for x in index[i:i + 32]) + ",")
    lines.append("};")
    lines.append(f"static const uint8_t tpa_ucd_{name}_blocks[{len(blocks)}][32] = {{")
    for bits in blocks:
        lines.append("    {" + ", ".join(f"0x{x:02x}" for x in bits) + "},")
    lines.append("};")
    lines.append(f"static TPA_UNUSED int tpa_ucd_is{name}(uint32_t c)")
    lines.append("{")
    lines.append(f"    return c < 0x110000u && ((tpa_ucd_{name}_blocks[tpa_ucd_{name}_index[c >> 8]][(c & 255u) >> 3] "
                 ">> (c & 7u)) & 1u);")
    lines.append("}")
    return lines


def _map_c(name: str) -> list[str]:
    entries = mapping(name)
    lines = [f"#define TPA_UCD_{name.upper()}_N {len(entries)}",
             f"static const uint32_t tpa_ucd_{name}_map[TPA_UCD_{name.upper()}_N][4] = {{    /* code point, n, result */"]
    for c, m in entries:
        r = list(m) + [0] * (3 - len(m))
        lines.append(f"    {{0x{c:x}, {len(m)}u << 24 | 0x{r[0]:x}, 0x{r[1]:x}, 0x{r[2]:x}}},")
    lines.append("};")
    lines.append(f"/* The full {name} mapping of c into out (room for 3); returns its length. */")
    lines.append(f"static TPA_UNUSED int tpa_ucd_{name}(uint32_t c, uint32_t *out)")
    lines.append("{")
    lines.append(f"    size_t lo = 0, hi = TPA_UCD_{name.upper()}_N;")
    lines.append("    while (lo < hi) {")
    lines.append("        size_t mid = lo + (hi - lo) / 2;")
    lines.append(f"        if (c < tpa_ucd_{name}_map[mid][0]) {{")
    lines.append("            hi = mid;")
    lines.append("        }")
    lines.append(f"        else if (c > tpa_ucd_{name}_map[mid][0]) {{")
    lines.append("            lo = mid + 1;")
    lines.append("        }")
    lines.append("        else {")
    lines.append(f"            const uint32_t *e = tpa_ucd_{name}_map[mid];")
    lines.append("            out[0] = e[1] & 0xFFFFFFu;")
    lines.append("            out[1] = e[2];")
    lines.append("            out[2] = e[3];")
    lines.append("            return (int)(e[1] >> 24);")
    lines.append("        }")
    lines.append("    }")
    lines.append("    out[0] = c;")
    lines.append("    return 1;")
    lines.append("}")
    return lines


def needed(names: set[str]) -> set[str]:
    """The tables `names` (properties and mappings the program's str operations use) need, closed: `lower` needs
    the final-sigma properties."""
    out = set(names)
    if "lower" in out:
        out |= {"cased", "ignorable"}
    return out


def tables_c(names: set[str]) -> list[str]:
    """The C text of the tables `names` (empty when the program uses none), for the place between tp_aot_int.h and
    tp_aot_str.h: each defines `TPA_UCD_<NAME>`, which enables the runtime functions that read it."""
    names = needed(names)
    if not names:
        return []
    out = [f"/* Unicode tables generated from the building interpreter (Unicode {unicodedata.unidata_version}). */",
           f'#define TPA_UNIDATA_VERSION "{unicodedata.unidata_version}"']
    for n in PROPERTIES:
        if n in names:
            out.append(f"#define TPA_UCD_{n.upper()} 1")
            out += _bits_c(n)
    for n in MAPPINGS:
        if n in names:
            out.append(f"#define TPA_UCD_{n.upper()} 1")
            out += _map_c(n)
    return out
