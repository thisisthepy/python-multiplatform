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
      - a mutable self-referential class, an `__init__` `self` escape, an alias store in `__init__` and a
        mutation of another instance in `__init__` were rejected with aot/cycle-possible until PR-O2c; since
        the cycle collector (test_aot_tier_o2c.py) they compile as `heap-gc` and run as CPython does;
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
                items: list[int] = self.items
                total += float(items[0])
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


def _tracked(tmp_path: Path, src: str, cls: str, expect_stdout: str) -> None:
    """PR-O2c: a class PR-O1 refused with `aot/cycle-possible` compiles, tracked by the cycle collector
    (its struct carries `tpa_gc_head gc;`), and runs as CPython does."""
    p = _write(tmp_path, src)
    c_text = aot.generate(p)
    assert '#include "tp_aot_gc.h"' in c_text
    assert f"struct tpa_cls_{cls} {{\n    tpa_gc_head gc;\n    tpa_rc_t rc;\n" in c_text
    cp, aot_res = _run_both(tmp_path, src)
    assert cp.returncode == 0
    assert aot_res.returncode == 0, aot_res.stderr
    assert aot_res.stdout == cp.stdout == expect_stdout


def test_mutable_self_ref_class_is_tracked_by_the_collector(tmp_path: Path):
    """Was `test_cycle_possible_mutable_self_ref_rejected` (aot/cycle-possible) until PR-O2c."""
    _tracked(tmp_path, """
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
        link(b, a)
        c: MutNode | None = a.nxt
        print(c is b, c is not None)


    if __name__ == "__main__":
        main()
    """, "MutNode", "True True\n")


def test_init_self_escape_class_is_tracked_by_the_collector(tmp_path: Path):
    """Was `test_cycle_possible_init_self_escape_rejected` until PR-O2c: `self.nxt = self` in `__init__` makes
    the class not immutable-after-init; the one-object cycle is the collector's."""
    _tracked(tmp_path, """
    class EscNode:
        __slots__ = ("nxt",)
        nxt: "EscNode | None"

        def __init__(self) -> None:
            self.nxt = self


    def main() -> None:
        a: EscNode = EscNode()
        b: EscNode | None = a.nxt
        print(a is a, b is a)


    if __name__ == "__main__":
        main()
    """, "EscNode", "True True\n")


def test_init_self_alias_store_class_is_tracked_by_the_collector(tmp_path: Path):
    """Was `test_cycle_possible_init_self_alias_store_conservatively_rejected` until PR-O2c. Assigning through
    `alias = self; alias.nxt = ...` inside `__init__` is still conservatively a mutating store (only direct
    `self.<field> = ...` is exempted), so the class is cyclic-capable and tracked."""
    _tracked(tmp_path, """
    class AliasNode:
        __slots__ = ("nxt",)
        nxt: "AliasNode | None"

        def __init__(self, nxt: "AliasNode | None") -> None:
            alias: AliasNode = self
            alias.nxt = nxt


    def main() -> None:
        a: AliasNode = AliasNode(None)
        print(a is a)


    if __name__ == "__main__":
        main()
    """, "AliasNode", "True\n")


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


def test_adversarial_init_mutating_other_instance_retains_local(tmp_path: Path):
    """Reproducer 1 (UAF): Holder.__init__ mutates another Holder instance's field while a local holds it."""
    src = """
    class Payload:
        __slots__ = ("val",)
        val: int

        def __init__(self, val: int) -> None:
            self.val = val


    class Holder:
        __slots__ = ("item",)
        item: "Payload | None"

        def __init__(self, other: "Holder | None", item: "Payload | None") -> None:
            self.item = item
            if other is not None:
                other.item = None


    def main() -> None:
        a: Holder = Holder(None, Payload(77))
        t: Payload | None = a.item
        b: Holder = Holder(a, Payload(88))
        bi: Payload | None = b.item
        if t is not None and bi is not None:
            print(t.val, bi.val)


    if __name__ == "__main__":
        main()
    """
    cp, aot_res = _run_both(tmp_path, src)
    assert cp.returncode == 0
    assert aot_res.returncode == 0
    assert aot_res.stdout == cp.stdout


def test_init_mutating_other_instance_class_is_tracked_by_the_collector(tmp_path: Path):
    """Reproducer 2 (acyclicity bypass): Node.__init__ mutating prev.nxt on another Node makes the class not
    immutable-after-init. Rejected with aot/cycle-possible until PR-O2c; now tracked by the collector."""
    _tracked(tmp_path, """
    class Node:
        __slots__ = ("v", "nxt")
        v: int
        nxt: "Node | None"

        def __init__(self, v: int, prev: "Node | None", nxt: "Node | None") -> None:
            self.v = v
            self.nxt = nxt
            if prev is not None:
                prev.nxt = nxt


    def main() -> None:
        a: Node = Node(1, None, None)
        b: Node = Node(2, a, b_of(a))
        c: Node | None = a.nxt
        print(b.v, c is b)


    def b_of(a: Node) -> Node:
        return a


    if __name__ == "__main__":
        main()
    """, "Node", "2 False\n")


def test_tagged_int_field_normal_and_partial_init(tmp_path: Path):
    """TAGGED int fields are initialized to TPA_INT_ZERO (never NULL) for both normal and partial __init__."""
    src_ok = """
    import sys


    class BigCell:
        __slots__ = ("big", "extra")
        big: int
        extra: int

        def __init__(self, x: int) -> None:
            self.big = x
            self.extra = x + (1 << 80)


    def main() -> None:
        n: int = int(sys.argv[1])
        c: BigCell = BigCell(n)
        d: BigCell = BigCell(1 << 80)
        print(c.big, c.extra, d.big, d.extra)


    if __name__ == "__main__":
        main()
    """
    cp, aot_res = _run_both(tmp_path, src_ok, args=["123456789012345678901234567890"], name="big_ok.py")
    assert cp.returncode == 0
    assert aot_res.returncode == 0
    assert aot_res.stdout == cp.stdout

    src_fail = """
    class BigFail:
        __slots__ = ("a", "b")
        a: int
        b: int

        def __init__(self, denom: int) -> None:
            self.a = 1 << 100
            self.b = (1 << 100) // denom


    def main() -> None:
        x: BigFail = BigFail(0)
        print(x.a)


    if __name__ == "__main__":
        main()
    """
    cp2, aot_res2 = _run_both(tmp_path, src_fail, name="big_fail.py")
    assert cp2.returncode != 0
    assert aot_res2.returncode != 0
    assert "ZeroDivisionError" in aot_res2.stderr


def test_library_transitively_mutable_class_rejected(tmp_path: Path):
    """Library mode checks transitive immutability across reachable class fields."""
    p = _write(tmp_path, """
    class Inner:
        __slots__ = ("n",)
        n: int

        def __init__(self, n: int) -> None:
            self.n = n


    class Outer:
        __slots__ = ("inner",)
        inner: Inner

        def __init__(self, inner: Inner) -> None:
            self.inner = inner


    def mutate(o: Outer) -> int:
        inn: Inner = o.inner
        inn.n = inn.n + 1
        return inn.n
    """)
    with pytest.raises(aot.AotError) as exc:
        aot.generate_library(p, exports=["mutate"], prefix="mylib")
    assert any(f.rule == "aot/library-mutable-class" and f.name == "Outer" for f in exc.value.findings)


def test_non_class_attribute_and_method_rejected_when_class_exists(tmp_path: Path):
    """When a module defines a compiled class, non-class attributes/methods and undeclared
    class attributes are still rejected by the allowlist check."""
    cases = [
        (
            "int_attr.py",
            """
            class Box:
                __slots__ = ("v",)
                v: int

                def __init__(self, v: int) -> None:
                    self.v = v

            def f(x: int) -> int:
                return x.real

            if __name__ == "__main__":
                print(f(1))
            """,
            "aot/attribute",
        ),
        (
            "list_method.py",
            """
            class Box:
                __slots__ = ("v",)
                v: int

                def __init__(self, v: int) -> None:
                    self.v = v

            def f(xs: list[float]) -> None:
                xs.sort()                       # (append is tier S2a; sort comes with S2c)

            if __name__ == "__main__":
                xs: list[float] = [1.0]
                f(xs)
            """,
            "aot/call",
        ),
        (
            "math_unknown.py",
            """
            import math

            class Box:
                __slots__ = ("v",)
                v: int

                def __init__(self, v: int) -> None:
                    self.v = v

            def f() -> float:
                return math.foo

            if __name__ == "__main__":
                print(f())
            """,
            "aot/attribute",
        ),
        (
            "sys_unknown.py",
            """
            import sys

            class Box:
                __slots__ = ("v",)
                v: int

                def __init__(self, v: int) -> None:
                    self.v = v

            def f() -> int:
                return sys.version

            if __name__ == "__main__":
                print(f())
            """,
            "aot/attribute",
        ),
        (
            "box_missing.py",
            """
            class Box:
                __slots__ = ("v",)
                v: int

                def __init__(self, v: int) -> None:
                    self.v = v

            def f(b: Box) -> int:
                return b.missing

            if __name__ == "__main__":
                b: Box = Box(1)
                print(f(b))
            """,
            "aot/attribute",
        ),
    ]
    for name, src, expected_rule in cases:
        p = _write(tmp_path, src, name=name)
        with pytest.raises(aot.AotError) as exc:
            aot.generate(p)
        assert any(f.rule == expected_rule for f in exc.value.findings), (
            f"{name}: expected {expected_rule}, got {exc.value.findings}"
        )
