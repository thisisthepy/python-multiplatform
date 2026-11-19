"""PR-O1 (issue #486, SPEC N-17): TypedPython AOT Tier O step 1 tests.

Covers:
  - Differential tests against CPython for benchmarks/typedpython/py/binary_trees.py (n = 4, 6, 10).
  - Headerless structs with scalar, tpa_int, list, nested class, C | None fields, methods, is / is None.
  - Adversarial lifetime tests (run under ASan/UBSan in CI sanitizers job):
      (a) local read from a mutable field whose owner is mutated through an alias/callee before reuse;
      (b) f(x.field) where f mutates x.field while using its parameter;
      (c) partial __init__ failure cleanup (no leak of already-initialized reference fields);
      (d) deep chain deallocation via tail-loop release (no C stack overflow).
  - Acyclicity proof and immutability analysis:
      - type-graph acyclic mutable class accepted;
      - immutable-after-init self-referential Node accepted;
      - mutable self-referential class rejected with aot/cycle-possible;
      - __init__ self escape rejected with aot/cycle-possible;
      - aot-lib export of mutable class rejected with aot/library-mutable-class.
"""
from __future__ import annotations

import subprocess
import sys
import textwrap
from pathlib import Path

import pytest

from typedpython import aot

ROOT = Path(__file__).resolve().parents[4]
BENCH = ROOT / "benchmarks" / "typedpython" / "py"


def _write(tmp_path: Path, src: str, name: str = "prog.py") -> Path:
    p = tmp_path / name
    p.write_text(textwrap.dedent(src).lstrip(), encoding="utf-8")
    return p


def _run_both(tmp_path: Path, src: str, args: list[str] | None = None, name: str = "prog.py"):
    p = _write(tmp_path, src, name)
    exe = aot.build(p, tmp_path / "prog_bin")
    a = args or []
    cp = subprocess.run([sys.executable, str(p), *a], capture_output=True, text=True)
    aot_res = subprocess.run([str(exe), *a], capture_output=True, text=True)
    return cp, aot_res


@pytest.mark.parametrize("n", ["4", "6", "10"])
def test_binary_trees_differential(tmp_path: Path, n: str):
    bench = BENCH / "binary_trees.py"
    exe = aot.build(bench, tmp_path / f"binary_trees_{n}")
    cp = subprocess.run([sys.executable, str(bench), n], capture_output=True, text=True, check=True)
    aot_res = subprocess.run([str(exe), n], capture_output=True, text=True, check=True)
    assert aot_res.stdout == cp.stdout
    assert aot_res.stderr == cp.stderr


def test_fields_methods_and_identity(tmp_path: Path):
    src = """
    class Box:
        __slots__ = ("x", "y", "flag", "items", "child")
        x: int
        y: float
        flag: bool
        items: list[int]
        child: "Box | None"

        def __init__(self, x: int, y: float, flag: bool, items: list[int], child: "Box | None") -> None:
            self.x = x
            self.y = y
            self.flag = flag
            self.items = items
            self.child = child

        def score(self) -> float:
            total: float = self.y + float(self.x)
            if self.flag:
                total += float(self.items[0])
            c: Box | None = self.child
            if c is not None:
                total += c.score()
            return total


    def main() -> None:
        a: list[int] = [10, 20]
        b: list[int] = [30, 40]
        leaf: Box = Box(2, 1.5, True, a, None)
        root: Box = Box(5, 2.5, False, b, leaf)
        same: bool = root.child is leaf
        diff: bool = root.child is not root
        print(root.score(), same, diff)


    if __name__ == "__main__":
        main()
    """
    cp, aot_res = _run_both(tmp_path, src)
    assert cp.returncode == 0
    assert aot_res.returncode == 0
    assert aot_res.stdout == cp.stdout


def test_adversarial_lifetime_mutable_field_local_and_arg(tmp_path: Path):
    """Adversarial lifetime cases (a) and (b):
    (a) local read from a mutable field whose owner is mutated via callee before local is read again;
    (b) f(x.field) where f mutates x.field while using its parameter.
    """
    src = """
    class Payload:
        __slots__ = ("val",)
        val: int

        def __init__(self, val: int) -> None:
            self.val = val


    class Holder:
        __slots__ = ("item",)
        item: Payload

        def __init__(self, item: Payload) -> None:
            self.item = item


    def replace_item(h: Holder, new_val: int) -> None:
        h.item = Payload(new_val)


    def use_while_mutating(p: Payload, h: Holder) -> int:
        replace_item(h, 999)
        cur: Payload = h.item
        return p.val + cur.val


    def main() -> None:
        h: Holder = Holder(Payload(42))
        # (a) local read from mutable field, then owner mutated via callee
        saved: Payload = h.item
        replace_item(h, 100)
        cur: Payload = h.item
        r1: int = saved.val + cur.val
        # (b) f(x.field) where f mutates x.field while using its parameter
        r2: int = use_while_mutating(h.item, h)
        print(r1, r2)


    if __name__ == "__main__":
        main()
    """
    cp, aot_res = _run_both(tmp_path, src)
    assert cp.returncode == 0
    assert aot_res.returncode == 0
    assert aot_res.stdout == cp.stdout


def test_adversarial_partial_init_failure_cleanup(tmp_path: Path):
    """Adversarial lifetime case (c): __init__ sets a reference field then raises ZeroDivisionError."""
    src = """
    class Leaf:
        __slots__ = ("v",)
        v: int

        def __init__(self, v: int) -> None:
            self.v = v


    class Pair:
        __slots__ = ("a", "b")
        a: Leaf
        b: int

        def __init__(self, v: int, denom: int) -> None:
            self.a = Leaf(v)
            self.b = 10 // denom


    def main() -> None:
        p: Pair = Pair(7, 0)
        print(p.b)


    if __name__ == "__main__":
        main()
    """
    cp, aot_res = _run_both(tmp_path, src)
    assert cp.returncode != 0
    assert aot_res.returncode != 0
    assert "ZeroDivisionError" in aot_res.stderr


def test_adversarial_deep_chain_deallocation(tmp_path: Path):
    """Adversarial lifetime case (d): deep self-referential chain freed via tail loop without stack overflow."""
    src = """
    class Link:
        __slots__ = ("val", "nxt")
        val: int
        nxt: "Link | None"

        def __init__(self, val: int, nxt: "Link | None") -> None:
            self.val = val
            self.nxt = nxt


    def build(n: int) -> Link | None:
        head: Link | None = None
        for i in range(n):
            head = Link(i, head)
        return head


    def sum_chain(head: Link | None) -> int:
        total: int = 0
        cur: Link | None = head
        while cur is not None:
            total += cur.val
            cur = cur.nxt
        return total


    def main() -> None:
        chain: Link | None = build(50000)
        print(sum_chain(chain))


    if __name__ == "__main__":
        main()
    """
    cp, aot_res = _run_both(tmp_path, src)
    assert cp.returncode == 0
    assert aot_res.returncode == 0
    assert aot_res.stdout == cp.stdout


def test_cycle_possible_mutable_self_ref_rejected(tmp_path: Path):
    p = _write(tmp_path, """
    class MutNode:
        __slots__ = ("nxt",)
        nxt: "MutNode | None"

        def __init__(self, nxt: "MutNode | None") -> None:
            self.nxt = nxt


    def link(a: MutNode, b: MutNode) -> None:
        a.nxt = b


    def main() -> None:
        a: MutNode = MutNode(None)
        b: MutNode = MutNode(None)
        link(a, b)


    if __name__ == "__main__":
        main()
    """)
    with pytest.raises(aot.AotError) as exc:
        aot.generate(p)
    assert any(f.rule == "aot/cycle-possible" and f.name == "MutNode" for f in exc.value.findings)


def test_cycle_possible_init_self_escape_rejected(tmp_path: Path):
    p = _write(tmp_path, """
    class EscNode:
        __slots__ = ("nxt",)
        nxt: "EscNode | None"

        def __init__(self) -> None:
            self.nxt = self


    def main() -> None:
        a: EscNode = EscNode()
        print(a is a)


    if __name__ == "__main__":
        main()
    """)
    with pytest.raises(aot.AotError) as exc:
        aot.generate(p)
    assert any(f.rule == "aot/cycle-possible" and f.name == "EscNode" for f in exc.value.findings)


def test_library_mutable_class_rejected(tmp_path: Path):
    p = _write(tmp_path, """
    class Counter:
        __slots__ = ("n",)
        n: int

        def __init__(self, n: int) -> None:
            self.n = n


    def bump(c: Counter) -> int:
        c.n = c.n + 1
        return c.n
    """)
    with pytest.raises(aot.AotError) as exc:
        aot.generate_library(p, exports=["bump"], prefix="mylib")
    assert any(f.rule == "aot/library-mutable-class" and f.name == "Counter" for f in exc.value.findings)
