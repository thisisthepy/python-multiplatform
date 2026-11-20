"""PR-O2c (SPEC N-17, tier O step 3): the TypedPython AOT cycle collector at a safepoint.

Covers (design: docs/design/typedpython-aot.md 6.3.2 item 3, "Step 3 as implemented"):
  - A class PR-O1 refused with `aot/cycle-possible` compiles; its heap struct carries the collector's header
    and `tpa_cls_<C>_new` is the safepoint (`tpa_gc_safepoint()` before the allocation).
  - A cycle of two objects is collected: with the default trigger and with `-DTPA_GC_STRESS` (a collection at
    every safepoint), `-DTPA_GC_STATS` reports every tracked object freed and none remaining at exit.
  - A cycle reachable from a live local, from a borrowed parameter, or through a mutable field of a live root
    is kept across every safepoint (an ASan/UBSan job catches a use after free).
  - A cycle whose objects own lists, big ints and acyclic objects: the collector's clear releases them (the
    built program runs with LeakSanitizer on in the sanitizer job).
  - Interaction with regions and virtual objects: a region object's or a virtual object's heap field is the
    only root of a cycle and keeps it; a region of the cyclic class itself holds its cycles with no collector.
  - The acyclic fast path is unchanged: no `tp_aot_gc.h`, no header word, the same `main` for
    `benchmarks/typedpython/py/binary_trees.py` and for a program whose only mutation is type-graph acyclic;
    in a mixed program only the cyclic-capable struct carries the header.
  - `--explain-alloc` reports `heap-gc` with the cycle reason; `aot/cycle-del` beside the front end's refusal
    of `__del__`; the elision rules (3) and (4) are off for every field of a cyclic-capable class.
Every generated C text goes through the compiler's syntax-only mode with `-Werror=incompatible-pointer-types`.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
import textwrap
from pathlib import Path

import pytest

from typedpython import aot, cbuild
from typedpython.aot import cc as cc_mod

ROOT = Path(__file__).resolve().parents[4]
BENCH = ROOT / "benchmarks" / "typedpython" / "py"

STATS = re.compile(r"^tpa_gc: collections=(\d+) freed=(\d+) exit_freed=(\d+) remaining=(\d+)$", re.M)


def _write(tmp_path: Path, src: str, name: str = "prog.py") -> Path:
    p = tmp_path / name
    p.write_text(textwrap.dedent(src).lstrip(), encoding="utf-8")
    return p


def _run_aot(exe: Path, args: list[str] | None = None) -> subprocess.CompletedProcess[str]:
    """The built program, with LeakSanitizer on where the build is sanitized (the pytest process keeps it off:
    CPython itself leaks at exit; the program is expected to free everything, cycles included)."""
    env = dict(os.environ)
    if cc_mod.sanitizing() and sys.platform.startswith("linux"):
        env["ASAN_OPTIONS"] = env.get("ASAN_OPTIONS", "") + ":detect_leaks=1"
    return subprocess.run([str(exe), *(args or [])], capture_output=True, text=True, env=env)


def _run_both(tmp_path: Path, p: Path, args: list[str] | None = None):
    exe = aot.build(p, tmp_path / (p.stem + "_bin"))
    cp = subprocess.run([sys.executable, str(p), *(args or [])], capture_output=True, text=True)
    return cp, _run_aot(exe, args)


def _stats(tmp_path: Path, p: Path, args: list[str], *, stress: bool) -> tuple[str, tuple[int, int, int, int]]:
    """stdout and (collections, freed, exit_freed, remaining) of the program built with `-DTPA_GC_STATS`
    (and `-DTPA_GC_STRESS`)."""
    defines = ("TPA_GC_STATS",) + (("TPA_GC_STRESS",) if stress else ())
    exe = aot.build(p, tmp_path / (p.stem + ("_stress" if stress else "_stats")), defines=defines)
    r = _run_aot(exe, args)
    assert r.returncode == 0, r.stderr
    m = STATS.search(r.stderr)
    assert m is not None, r.stderr
    return r.stdout, tuple(int(x) for x in m.groups())      # type: ignore[return-value]


def _func_body_c(c_text: str, fn_name: str) -> str:
    marker = f"static int tpf_{fn_name}("
    pos = 0
    while True:
        start = c_text.find(marker, pos)
        assert start != -1, f"{marker} not found in generated C"
        line_end = c_text.index("\n", start)
        if not c_text[start:line_end].rstrip().endswith(";"):
            brace = c_text.index("{", line_end)
            break
        pos = line_end + 1
    depth = 0
    for i in range(brace, len(c_text)):
        if c_text[i] == "{":
            depth += 1
        elif c_text[i] == "}":
            depth -= 1
            if depth == 0:
                return c_text[brace : i + 1]
    raise AssertionError(f"unterminated function body for {fn_name}")


def _struct_c(c_text: str, name: str) -> str:
    start = c_text.index(f"struct {name} {{\n")
    return c_text[start:c_text.index("};", start)]


def _syntax_check(c_text: str, tmp_path: Path, name: str) -> None:
    if cbuild._is_msvc():
        return
    c_file = tmp_path / f"{name}.c"
    c_file.write_text(c_text, encoding="utf-8")
    cmd = list(cbuild.compiler())
    if cc_mod.sanitizing():
        cmd = cc_mod._sanitized(cmd)
    cmd += ["-std=c11", "-fsyntax-only", "-Wall", "-Wextra", "-Werror=incompatible-pointer-types",
            f"-I{cc_mod.RUNTIME_DIR}", str(c_file)]
    r = subprocess.run(cmd, capture_output=True, text=True)
    assert r.returncode == 0, r.stderr


MUT = """
import sys


class MutNode:
    __slots__ = ("v", "nxt")
    v: int
    nxt: "MutNode | None"

    def __init__(self, v: int, nxt: "MutNode | None") -> None:
        self.v = v
        self.nxt = nxt


def link(a: MutNode, b: MutNode) -> None:
    a.nxt = b
"""


def test_two_object_cycle_collected(tmp_path: Path):
    src = MUT + """

def churn(n: int) -> int:
    total: int = 0
    for i in range(n):
        a: MutNode = MutNode(i, None)
        b: MutNode = MutNode(i + 1, None)
        link(a, b)
        link(b, a)
        nb: MutNode | None = a.nxt
        if nb is not None:
            total += nb.v
    return total


if __name__ == "__main__":
    print(churn(int(sys.argv[1])))
"""
    p = _write(tmp_path, src)
    report = aot.explain_alloc(p)
    assert ("churn:" in report and "a = MutNode(...) -> heap-gc (escapes via call to link) "
            "[region: R3: escapes via call to link] [gc: field `MutNode.nxt` is mutated outside `__init__` in `link`"
            in report)
    c_text = aot.generate(p)
    _syntax_check(c_text, tmp_path, "two")
    assert '#include "tp_aot_gc.h"' in c_text
    assert _struct_c(c_text, "tpa_cls_MutNode").startswith("struct tpa_cls_MutNode {\n    tpa_gc_head gc;\n    tpa_rc_t rc;\n")
    ctor = c_text[c_text.index("static int tpa_cls_MutNode_new(") :]
    ctor = ctor[ctor.index("{") : ctor.index("\n}\n")]
    assert ctor.index("tpa_gc_safepoint();") < ctor.index("calloc(")          # the safepoint precedes the allocation
    assert "tpa_gc_track(&obj->gc, &tpa_cls_MutNode_gc_type);" in ctor
    assert "tpa_gc_exit();" in c_text[c_text.index("int main("):]

    n = 50000
    cp, aot_res = _run_both(tmp_path, p, [str(n)])
    assert cp.returncode == 0
    assert aot_res.returncode == 0, aot_res.stderr
    assert aot_res.stdout == cp.stdout
    assert aot_res.stderr == cp.stderr == ""

    out, (collections, freed, exit_freed, remaining) = _stats(tmp_path, p, [str(n)], stress=False)
    assert out == cp.stdout
    assert collections >= 10                 # 2n tracked allocations, the default trigger every 2000
    assert freed + exit_freed == 2 * n
    assert freed >= 2 * n - 4000             # at most two trigger windows of garbage are left to the exit collection
    assert remaining == 0

    out, (collections, freed, exit_freed, remaining) = _stats(tmp_path, p, ["3000"], stress=True)
    assert out == subprocess.run([sys.executable, str(p), "3000"], capture_output=True, text=True).stdout
    assert collections >= 2 * 3000 - 2       # one collection at every safepoint with a tracked object alive
    assert freed + exit_freed == 2 * 3000
    assert remaining == 0


def test_cycle_reachable_from_live_references_kept(tmp_path: Path):
    src = MUT + """

def churn(p: MutNode, n: int) -> int:
    total: int = 0
    for i in range(n):
        x: MutNode = MutNode(i, None)
        y: MutNode = MutNode(-i, None)
        link(x, y)
        link(y, x)
        q: MutNode | None = p.nxt
        if q is not None:
            total += q.v
    return total


def through_a_field(n: int) -> int:
    root: MutNode = MutNode(0, None)
    a: MutNode = MutNode(1, None)
    b: MutNode = MutNode(2, None)
    link(a, b)
    link(b, a)
    link(root, a)
    a = MutNode(7, None)
    b = MutNode(8, None)
    acc: int = churn(root, n)
    first: MutNode | None = root.nxt
    if first is not None:
        second: MutNode | None = first.nxt
        if second is not None:
            third: MutNode | None = second.nxt
            if third is not None:
                acc += first.v * 100 + second.v * 10 + third.v
    return acc + a.v + b.v


def main(n: int) -> None:
    a: MutNode = MutNode(1, None)
    b: MutNode = MutNode(2, None)
    link(a, b)
    link(b, a)
    held: MutNode | None = a.nxt
    print(churn(a, n))
    print(churn(b, n))
    c: MutNode | None = b.nxt
    if c is not None and held is not None:
        print(c.v, held.v)
    print(through_a_field(n))


if __name__ == "__main__":
    main(int(sys.argv[1]))
"""
    p = _write(tmp_path, src)
    c_text = aot.generate(p)
    _syntax_check(c_text, tmp_path, "kept")
    cp, aot_res = _run_both(tmp_path, p, ["2500"])
    assert cp.returncode == 0
    assert aot_res.returncode == 0, aot_res.stderr
    assert aot_res.stdout == cp.stdout
    out, (collections, freed, exit_freed, remaining) = _stats(tmp_path, p, ["300"], stress=True)
    assert out == subprocess.run([sys.executable, str(p), "300"], capture_output=True, text=True).stdout
    assert collections >= 600
    assert remaining == 0
    # churn's cycles three times, main's pair and through_a_field's first pair are the collector's; through_a_field's
    # root and its two replacement nodes are freed by their counts when the frame returns, so they are not counted
    assert freed + exit_freed == 3 * 2 * 300 + 2 + 2


def test_cycle_owning_lists_big_ints_and_acyclic_objects_is_freed(tmp_path: Path):
    src = """
import sys


class Payload:
    __slots__ = ("val",)
    val: int

    def __init__(self, val: int) -> None:
        self.val = val


class Holder:
    __slots__ = ("items", "fl", "big", "pay", "nxt")
    items: list[int]
    fl: list[float]
    big: int
    pay: Payload
    nxt: "Holder | None"

    def __init__(self, items: list[int], fl: list[float], big: int, pay: Payload, nxt: "Holder | None") -> None:
        self.items = items
        self.fl = fl
        self.big = big
        self.pay = pay
        self.nxt = nxt


def tie(a: Holder, b: Holder) -> None:
    a.nxt = b
    b.nxt = a


def run(n: int) -> int:
    acc: int = 0
    for i in range(n):
        xs: list[int] = [i, i + 1, i + 2]
        ys: list[float] = [0.5, 1.5]
        a: Holder = Holder(xs, ys, 1 << (64 + i % 5), Payload(i), None)
        b: Holder = Holder(xs, ys, (1 << 70) + i, Payload(-i), None)
        tie(a, b)
        other: Holder | None = a.nxt
        if other is not None:
            items: list[int] = other.items
            fl: list[float] = other.fl
            pay: Payload = other.pay
            acc += items[2] + pay.val + (other.big >> 68) + (a.big >> 64) + int(fl[1])
    return acc


if __name__ == "__main__":
    print(run(int(sys.argv[1])))
"""
    p = _write(tmp_path, src)
    report = aot.explain_alloc(p)
    assert "a = Holder(...) -> heap-gc (escapes via call to tie)" in report
    assert "<temp> = Payload(...) -> heap-rc (temporary (not bound to a single local))" in report
    c_text = aot.generate(p)
    _syntax_check(c_text, tmp_path, "owning")
    clear = c_text[c_text.index("static void tpa_cls_Holder_gc_clear(") :]
    clear = clear[: clear.index("\n}\n")]
    for call in ("tpa_int_list_release(", "tpa_f64_release(", "tpa_int_clear(", "tpa_cls_Payload_release(",
                 "tpa_cls_Holder_release("):          # `list[int]` is the big-int-capable `tpa_int_list`
        assert call in clear, call
    traverse = c_text[c_text.index("static void tpa_cls_Holder_gc_traverse(") :]
    traverse = traverse[: traverse.index("\n}\n")]
    assert "f_nxt" in traverse and "f_pay" not in traverse       # only fields of cyclic-capable classes are edges
    assert "struct tpa_cls_Payload {\n    tpa_rc_t rc;\n" in c_text

    cp, aot_res = _run_both(tmp_path, p, ["3000"])
    assert cp.returncode == 0
    assert aot_res.returncode == 0, aot_res.stderr
    assert aot_res.stdout == cp.stdout
    out, (collections, freed, exit_freed, remaining) = _stats(tmp_path, p, ["700"], stress=True)
    assert out == subprocess.run([sys.executable, str(p), "700"], capture_output=True, text=True).stdout
    assert freed + exit_freed == 1400
    assert remaining == 0


def test_region_and_virtual_objects_holding_a_cycle(tmp_path: Path):
    src = MUT + """

class Cell:
    __slots__ = ("node", "k")
    node: MutNode | None
    k: int

    def __init__(self, node: MutNode | None, k: int) -> None:
        self.node = node
        self.k = k


def via_region(n: int) -> int:
    total: int = 0
    for i in range(n):
        a: MutNode = MutNode(i, None)
        b: MutNode = MutNode(i + 100, None)
        link(a, b)
        link(b, a)
        cell: Cell = Cell(a, i)
        c2: Cell = cell
        a = MutNode(-1, None)
        b = MutNode(-2, None)
        link(a, b)
        link(b, a)
        held: MutNode | None = c2.node
        if held is not None:
            nx: MutNode | None = held.nxt
            if nx is not None:
                total += nx.v + c2.k
    return total


def via_virtual(n: int) -> int:
    total: int = 0
    for i in range(n):
        a: MutNode = MutNode(i, None)
        b: MutNode = MutNode(i + 200, None)
        link(a, b)
        link(b, a)
        box: Cell = Cell(a, i)
        a = MutNode(-3, None)
        b = MutNode(-4, None)
        link(a, b)
        link(b, a)
        held: MutNode | None = box.node
        if held is not None:
            nx: MutNode | None = held.nxt
            if nx is not None:
                total += nx.v + box.k
    return total


def region_cycle(n: int) -> int:
    total: int = 0
    for i in range(n):
        a: MutNode = MutNode(i, None)
        b: MutNode = MutNode(i + 1, None)
        a.nxt = b
        b.nxt = a
        cur: MutNode | None = a
        for j in range(5):
            if cur is not None:
                total += cur.v
                cur = cur.nxt
    return total


if __name__ == "__main__":
    n: int = int(sys.argv[1])
    print(via_region(n))
    print(via_virtual(n))
    print(region_cycle(n))
"""
    p = _write(tmp_path, src)
    report = aot.explain_alloc(p)
    assert "via_region:" in report
    assert "cell = Cell(...) -> region (per-iteration scope of the for loop, depth 1)" in report
    assert "box = Cell(...) -> virtual (scalar-replaced (no escape))" in report
    assert "region_cycle:" in report
    lines = [ln for ln in report.splitlines() if ln.startswith("region_cycle:")]
    assert len(lines) == 2
    assert all("MutNode(...) -> region (per-iteration scope of the for loop, depth 1)" in ln for ln in lines)
    assert all("heap-gc" in ln for ln in report.splitlines() if "MutNode(...)" in ln and "region_cycle" not in ln)

    c_text = aot.generate(p)
    _syntax_check(c_text, tmp_path, "regions")
    assert "tpa_gc_head gc;" in _struct_c(c_text, "tpa_cls_MutNode")
    assert "tpa_gc_head" not in _struct_c(c_text, "tpa_reg_region_cycle__MutNode")
    assert "tpa_gc" not in _func_body_c(c_text, "region_cycle")
    assert "tpa_cls_MutNode_" not in _func_body_c(c_text, "region_cycle")

    cp, aot_res = _run_both(tmp_path, p, ["1500"])
    assert cp.returncode == 0
    assert aot_res.returncode == 0, aot_res.stderr
    assert aot_res.stdout == cp.stdout
    out, (collections, freed, exit_freed, remaining) = _stats(tmp_path, p, ["400"], stress=True)
    assert out == subprocess.run([sys.executable, str(p), "400"], capture_output=True, text=True).stdout
    assert freed + exit_freed == 2 * 400 * 4                 # the region's cycles are not the collector's
    assert remaining == 0


def test_acyclic_programs_generate_no_collector(tmp_path: Path):
    bench = BENCH / "binary_trees.py"
    c_text = aot.generate(bench)
    assert "tpa_gc" not in c_text and "tp_aot_gc.h" not in c_text
    assert "struct tpa_cls_Node {\n    tpa_rc_t rc;\n" in c_text
    main = c_text[c_text.index("int main("):]
    assert "rc = tpf___tp_main();" in main and "tpa_gc" not in main       # the entry since #451

    acyclic_mutation = """
class Leaf:
    __slots__ = ("v",)
    v: int

    def __init__(self, v: int) -> None:
        self.v = v


class Tree:
    __slots__ = ("leaf", "n")
    leaf: Leaf | None
    n: int

    def __init__(self, leaf: Leaf | None, n: int) -> None:
        self.leaf = leaf
        self.n = n


def fill(t: Tree, n: int) -> int:
    acc: int = 0
    for i in range(n):
        t.leaf = Leaf(i)
        lf: Leaf | None = t.leaf
        if lf is not None:
            acc += lf.v + t.n
    return acc


if __name__ == "__main__":
    t: Tree = Tree(None, 3)
    print(fill(t, 10))
"""
    p = _write(tmp_path, acyclic_mutation, "acyclic.py")
    report = aot.explain_alloc(p)
    assert "heap-gc" not in report and "[gc:" not in report
    c_text = aot.generate(p)
    _syntax_check(c_text, tmp_path, "acyclic")
    assert "tpa_gc" not in c_text and "tp_aot_gc.h" not in c_text
    cp, aot_res = _run_both(tmp_path, p)
    assert aot_res.returncode == cp.returncode == 0
    assert aot_res.stdout == cp.stdout

    mixed = MUT + """

class Payload:
    __slots__ = ("val",)
    val: int

    def __init__(self, val: int) -> None:
        self.val = val


def main() -> None:
    a: MutNode = MutNode(1, None)
    b: MutNode = MutNode(2, None)
    link(a, b)
    link(b, a)
    p: Payload = Payload(5)
    q: Payload = p
    print(q.val, len(sys.argv))


if __name__ == "__main__":
    main()
"""
    p = _write(tmp_path, mixed, "mixed.py")
    c_text = aot.generate(p)
    _syntax_check(c_text, tmp_path, "mixed")
    assert "struct tpa_cls_Payload {\n    tpa_rc_t rc;\n" in c_text
    assert "struct tpa_cls_MutNode {\n    tpa_gc_head gc;\n    tpa_rc_t rc;\n" in c_text
    assert "tpa_cls_Payload_gc_" not in c_text
    assert "tpa_gc_safepoint();" not in c_text[c_text.index("static int tpa_cls_Payload_new("):].split("\n}\n")[0]
    cp, aot_res = _run_both(tmp_path, p)
    assert aot_res.returncode == cp.returncode == 0
    assert aot_res.stdout == cp.stdout


def test_cycle_del_is_refused_beside_the_front_end(tmp_path: Path):
    p = _write(tmp_path, MUT + """

class Ring:
    __slots__ = ("nxt",)
    nxt: "Ring | None"

    def __init__(self, nxt: "Ring | None") -> None:
        self.nxt = nxt

    def __del__(self) -> None:
        print("gone")


def close(a: Ring, b: Ring) -> None:
    a.nxt = b


if __name__ == "__main__":
    r: Ring = Ring(None)
    s: Ring = Ring(None)
    close(r, s)
    close(s, r)
    m: MutNode = MutNode(1, None)
    print(m.v)
""")
    with pytest.raises(aot.AotError) as exc:
        aot.generate(p)
    rules = {(f.rule, f.name) for f in exc.value.findings}
    assert ("aot/cycle-del", "Ring") in rules
    assert any(r == "aot/front-end" and n == "Ring.__del__" for r, n in rules)
    assert not any(r == "aot/cycle-possible" for r, _ in rules)


def test_elision_off_for_every_field_of_a_cyclic_class(tmp_path: Path):
    src = """
import sys


class Tag:
    __slots__ = ("k",)
    k: int

    def __init__(self, k: int) -> None:
        self.k = k


class Pair:
    __slots__ = ("tag", "other")
    tag: Tag
    other: "Pair | None"

    def __init__(self, tag: Tag, other: "Pair | None") -> None:
        self.tag = tag
        self.other = other


class Box:
    __slots__ = ("tag",)
    tag: Tag

    def __init__(self, tag: Tag) -> None:
        self.tag = tag


def couple(a: Pair, b: Pair) -> None:
    a.other = b


def read_pair(p: Pair) -> int:
    t: Tag = p.tag
    return t.k


def read_box(b: Box) -> int:
    t: Tag = b.tag
    return t.k


def main() -> None:
    a: Pair = Pair(Tag(1), None)
    b: Pair = Pair(Tag(2), None)
    couple(a, b)
    couple(b, a)
    x: Box = Box(Tag(3))
    print(read_pair(a) + read_pair(b) + read_box(x), len(sys.argv))


if __name__ == "__main__":
    main()
"""
    p = _write(tmp_path, src)
    c_text = aot.generate(p)
    _syntax_check(c_text, tmp_path, "elision")
    assert "tpa_cls_Tag_retain(" in _func_body_c(c_text, "read_pair")       # `Pair.tag` is immutable, but Pair is cyclic-capable
    assert "tpa_cls_Tag_retain(" not in _func_body_c(c_text, "read_box")    # the acyclic class keeps PR-O1's elision
    cp, aot_res = _run_both(tmp_path, p)
    assert aot_res.returncode == cp.returncode == 0
    assert aot_res.stdout == cp.stdout
