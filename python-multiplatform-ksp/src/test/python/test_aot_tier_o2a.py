"""PR-O2a (issue #493, SPEC N-17): TypedPython AOT Virtual Objects (scalar replacement) + --explain-alloc.

Covers:
  - Pure scalar virtual object (`Point(x, y)` created, fields read and mutated in a loop):
    CPython-identical output, no `tpa_cls_Point_new` call in the generated function body,
    and `explain_alloc` reports `virtual (scalar-replaced (no escape))`.
  - Virtual object holding owned fields (`list[int]`, big `tpa_int`, heap `Payload` class):
    CPython-identical output and leak-free / UAF-free cleanup under ASan/UBSan.
  - Fallback cases where `explain_alloc` reports `heap-rc` with the exact reason and
    differential execution matches CPython:
      - escapes via return
      - escapes via function call
      - non-inlined method call
      - identity comparison (`is`)
      - reassigned / branch-merged (`if`/`else`)
  - `benchmarks/typedpython/py/binary_trees.py` passes `explain_alloc` and builds cleanly.
  - CLI `python -m typedpython aot-build <entry> --explain-alloc`.
"""
from __future__ import annotations

import subprocess
import sys
import textwrap
from pathlib import Path

from typedpython import aot
from typedpython.__main__ import main as cli_main

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


def _func_body_c(c_text: str, fn_name: str) -> str:
    """Extract the C function definition for `fn_name` from generated C."""
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


def test_virtual_scalar_point_in_loop(tmp_path: Path):
    src = """
    class Point:
        __slots__ = ("x", "y")
        x: int
        y: int

        def __init__(self, x: int, y: int) -> None:
            self.x = x
            self.y = y


    def accumulate(n: int) -> int:
        total: int = 0
        for i in range(n):
            p: Point = Point(i, i + 1)
            p.x = p.x + 2
            total += p.x + p.y
        return total


    if __name__ == "__main__":
        print(accumulate(100))
    """
    p = _write(tmp_path, src)
    report = aot.explain_alloc(p)
    assert "accumulate:" in report
    assert "p = Point(...) -> virtual (scalar-replaced (no escape))" in report

    c_text = aot.generate(p)
    body = _func_body_c(c_text, "accumulate")
    assert "tpa_cls_Point_new" not in body

    cp, aot_res = _run_both(tmp_path, src)
    assert cp.returncode == 0
    assert aot_res.returncode == 0
    assert aot_res.stdout == cp.stdout


def test_virtual_object_with_owned_fields(tmp_path: Path):
    src = """
    class Payload:
        __slots__ = ("val",)
        val: int

        def __init__(self, val: int) -> None:
            self.val = val


    class Wrapper:
        __slots__ = ("items", "big", "inner")
        items: list[int]
        big: int
        inner: Payload

        def __init__(self, items: list[int], big: int, inner: Payload) -> None:
            self.items = items
            self.big = big
            self.inner = inner


    def work(n: int) -> int:
        acc: int = 0
        for i in range(n):
            xs: list[int] = [i, i + 1, i + 2]
            pay: Payload = Payload(i * 3)
            w: Wrapper = Wrapper(xs, 1 << 70, pay)
            items: list[int] = w.items
            inner: Payload = w.inner
            acc += items[1] + inner.val + (w.big >> 68)
            w.big = w.big + 1
        return acc


    if __name__ == "__main__":
        print(work(25))
    """
    p = _write(tmp_path, src)
    report = aot.explain_alloc(p)
    assert "w = Wrapper(...) -> virtual (scalar-replaced (no escape))" in report
    assert "pay = Payload(...) -> heap-rc (escapes via call to Wrapper)" in report

    c_text = aot.generate(p)
    body = _func_body_c(c_text, "work")
    assert "tpa_cls_Wrapper_new" not in body

    cp, aot_res = _run_both(tmp_path, src)
    assert cp.returncode == 0
    assert aot_res.returncode == 0
    assert aot_res.stdout == cp.stdout


def test_fallback_escapes_via_return(tmp_path: Path):
    src = """
    class Point:
        __slots__ = ("x", "y")
        x: int
        y: int

        def __init__(self, x: int, y: int) -> None:
            self.x = x
            self.y = y


    def make_point(x: int, y: int) -> Point:
        p: Point = Point(x, y)
        return p


    if __name__ == "__main__":
        q: Point = make_point(3, 4)
        print(q.x + q.y)
    """
    p = _write(tmp_path, src)
    report = aot.explain_alloc(p)
    assert "p = Point(...) -> heap-rc (escapes via return)" in report

    cp, aot_res = _run_both(tmp_path, src)
    assert cp.returncode == 0
    assert aot_res.returncode == 0
    assert aot_res.stdout == cp.stdout


def test_fallback_escapes_via_call(tmp_path: Path):
    src = """
    class Point:
        __slots__ = ("x", "y")
        x: int
        y: int

        def __init__(self, x: int, y: int) -> None:
            self.x = x
            self.y = y


    def consume(p: Point) -> int:
        return p.x * 10 + p.y


    def run(a: int, b: int) -> int:
        p: Point = Point(a, b)
        return consume(p)


    if __name__ == "__main__":
        print(run(5, 6))
    """
    p = _write(tmp_path, src)
    report = aot.explain_alloc(p)
    assert "p = Point(...) -> heap-rc (escapes via call to consume)" in report

    cp, aot_res = _run_both(tmp_path, src)
    assert cp.returncode == 0
    assert aot_res.returncode == 0
    assert aot_res.stdout == cp.stdout


def test_fallback_non_inlined_method_call(tmp_path: Path):
    src = """
    class Point:
        __slots__ = ("x", "y")
        x: int
        y: int

        def __init__(self, x: int, y: int) -> None:
            self.x = x
            self.y = y

        def norm(self) -> float:
            return float(self.x) * float(self.x) + float(self.y) * float(self.y)


    def run(a: int, b: int) -> float:
        p: Point = Point(a, b)
        return p.norm()


    if __name__ == "__main__":
        print(run(3, 4))
    """
    p = _write(tmp_path, src)
    report = aot.explain_alloc(p)
    assert "p = Point(...) -> heap-rc (non-inlined method call: Point.norm)" in report

    cp, aot_res = _run_both(tmp_path, src)
    assert cp.returncode == 0
    assert aot_res.returncode == 0
    assert aot_res.stdout == cp.stdout


def test_fallback_identity_comparison(tmp_path: Path):
    src = """
    class Point:
        __slots__ = ("x", "y")
        x: int
        y: int

        def __init__(self, x: int, y: int) -> None:
            self.x = x
            self.y = y


    def check(a: int) -> int:
        p: Point = Point(a, a + 1)
        q: Point = Point(a, a + 1)
        if p is not q:
            return p.x + q.y
        return 0


    if __name__ == "__main__":
        print(check(7))
    """
    p = _write(tmp_path, src)
    report = aot.explain_alloc(p)
    assert "p = Point(...) -> heap-rc (used in identity comparison (is))" in report

    cp, aot_res = _run_both(tmp_path, src)
    assert cp.returncode == 0
    assert aot_res.returncode == 0
    assert aot_res.stdout == cp.stdout


def test_fallback_reassigned_branch_merged(tmp_path: Path):
    src = """
    class Point:
        __slots__ = ("x", "y")
        x: int
        y: int

        def __init__(self, x: int, y: int) -> None:
            self.x = x
            self.y = y


    def choose(flag: int) -> int:
        p: Point = Point(1, 2)
        if flag <= 0:
            p = Point(3, 4)
        return p.x + p.y


    if __name__ == "__main__":
        print(choose(1))
        print(choose(0))
    """
    p = _write(tmp_path, src)
    report = aot.explain_alloc(p)
    assert "p = Point(...) -> heap-rc (reassigned or branch-merged)" in report

    cp, aot_res = _run_both(tmp_path, src)
    assert cp.returncode == 0
    assert aot_res.returncode == 0
    assert aot_res.stdout == cp.stdout


def test_binary_trees_explain_alloc_and_cli(tmp_path: Path, capsys):
    bench = BENCH / "binary_trees.py"
    report = aot.explain_alloc(bench)
    assert "Node(...)" in report
    assert "heap-rc" in report

    rc = cli_main(["aot-build", str(bench), "--explain-alloc"])
    assert rc == 0
    captured = capsys.readouterr()
    assert captured.out == report


def test_virtual_owned_fields_early_return_exception_and_loop_rebind(tmp_path: Path):
    src = """
    class Payload:
        __slots__ = ("val",)
        val: int

        def __init__(self, val: int) -> None:
            self.val = val


    class Wrapper:
        __slots__ = ("items", "big", "inner")
        items: list[int]
        big: int
        inner: Payload

        def __init__(self, items: list[int], big: int, inner: Payload) -> None:
            self.items = items
            self.big = big
            self.inner = inner


    def early_return(flag: int) -> int:
        if flag < 0:
            return -1
        xs: list[int] = [10, 20, 30]
        p: Payload = Payload(42)
        w: Wrapper = Wrapper(xs, 1 << 70, p)
        inner: Payload = w.inner
        if flag == 1:
            return inner.val + (w.big >> 68)
        items: list[int] = w.items
        return items[2] + inner.val


    def loop_rebind(n: int) -> int:
        xs: list[int] = [0, 1]
        p: Payload = Payload(0)
        w: Wrapper = Wrapper(xs, 1 << 65, p)
        acc: int = 0
        for i in range(n):
            new_xs: list[int] = [i, i + 1, i + 2]
            w.inner = Payload(i * 2)
            w.big = 1 << (60 + (i % 10))
            w.items = new_xs
            inner: Payload = w.inner
            items: list[int] = w.items
            acc += inner.val + items[2] + (w.big >> 58)
        return acc


    def loop_create(n: int) -> int:
        acc: int = 0
        for i in range(n):
            xs: list[int] = [i, i + 1]
            v: Wrapper = Wrapper(xs, 1 << (64 + (i % 8)), Payload(i + 5))
            inner: Payload = v.inner
            items: list[int] = v.items
            acc += inner.val + items[1] + (v.big >> 62)
        return acc


    if __name__ == "__main__":
        print(early_return(-1))
        print(early_return(1))
        print(early_return(2))
        print(loop_rebind(15))
        print(loop_create(15))
    """
    p = _write(tmp_path, src, "ok.py")
    report = aot.explain_alloc(p)
    assert "early_return:" in report
    assert "w = Wrapper(...) -> virtual (scalar-replaced (no escape))" in report
    assert "loop_rebind:" in report
    assert "loop_create:" in report
    assert "v = Wrapper(...) -> virtual (scalar-replaced (no escape))" in report

    c_text = aot.generate(p)
    for fn in ("early_return", "loop_rebind", "loop_create"):
        assert "tpa_cls_Wrapper_new" not in _func_body_c(c_text, fn)

    cp, aot_res = _run_both(tmp_path, src, name="ok.py")
    assert cp.returncode == 0
    assert aot_res.returncode == 0
    assert aot_res.stdout == cp.stdout

    # Exception path (1 // 0) after creating a virtual object with owned fields:
    # tp_cleanup must release every owned virtual field local once (verified under ASan).
    exc_src = """
    class Payload:
        __slots__ = ("val",)
        val: int

        def __init__(self, val: int) -> None:
            self.val = val


    class Wrapper:
        __slots__ = ("items", "big", "inner")
        items: list[int]
        big: int
        inner: Payload

        def __init__(self, items: list[int], big: int, inner: Payload) -> None:
            self.items = items
            self.big = big
            self.inner = inner


    def boom(d: int) -> int:
        xs: list[int] = [1, 2, 3]
        p: Payload = Payload(99)
        w: Wrapper = Wrapper(xs, 1 << 72, p)
        q: int = 1 // d
        inner: Payload = w.inner
        items: list[int] = w.items
        return q + inner.val + (w.big >> 70) + items[0]


    if __name__ == "__main__":
        print(boom(0))
    """
    pe = _write(tmp_path, exc_src, "exc.py")
    exc_report = aot.explain_alloc(pe)
    assert "w = Wrapper(...) -> virtual (scalar-replaced (no escape))" in exc_report
    assert "tpa_cls_Wrapper_new" not in _func_body_c(aot.generate(pe), "boom")

    cp_e, aot_e = _run_both(tmp_path, exc_src, name="exc.py")
    assert cp_e.returncode != 0
    assert aot_e.returncode != 0


def test_virtual_object_and_field_stored_in_heap_object(tmp_path: Path):
    src = """
    class Payload:
        __slots__ = ("val",)
        val: int

        def __init__(self, val: int) -> None:
            self.val = val


    class Wrapper:
        __slots__ = ("items", "big", "item")
        items: list[int]
        big: int
        item: Payload

        def __init__(self, items: list[int], big: int, item: Payload) -> None:
            self.items = items
            self.big = big
            self.item = item


    class Holder:
        __slots__ = ("w", "items", "big", "item")
        w: Wrapper
        items: list[int]
        big: int
        item: Payload

        def __init__(self, w: Wrapper, items: list[int], big: int, item: Payload) -> None:
            self.w = w
            self.items = items
            self.big = big
            self.item = item


    def store_object_itself(h: Holder) -> None:
        xs: list[int] = [1, 2]
        v_esc: Wrapper = Wrapper(xs, 1 << 66, Payload(11))
        h.w = v_esc


    def store_fields_only(h: Holder) -> None:
        xs: list[int] = [7, 8, 9]
        v_virt: Wrapper = Wrapper(xs, 1 << 70, Payload(77))
        h.item = v_virt.item
        h.big = v_virt.big
        h.items = v_virt.items


    if __name__ == "__main__":
        xs0: list[int] = [0]
        xs1: list[int] = [0]
        init_w: Wrapper = Wrapper(xs0, 1 << 64, Payload(1))
        h: Holder = Holder(init_w, xs1, 1 << 64, Payload(2))
        store_object_itself(h)
        store_fields_only(h)
        hw: Wrapper = h.w
        hw_item: Payload = hw.item
        h_item: Payload = h.item
        h_items: list[int] = h.items
        print(hw_item.val, h_item.val, h_items[2], h.big >> 68)
    """
    p = _write(tmp_path, src)
    report = aot.explain_alloc(p)
    assert "v_esc = Wrapper(...) -> heap-rc (escapes into object field)" in report
    assert "v_virt = Wrapper(...) -> virtual (scalar-replaced (no escape))" in report

    c_text = aot.generate(p)
    assert "tpa_cls_Wrapper_new" in _func_body_c(c_text, "store_object_itself")
    assert "tpa_cls_Wrapper_new" not in _func_body_c(c_text, "store_fields_only")

    cp, aot_res = _run_both(tmp_path, src)
    assert cp.returncode == 0
    assert aot_res.returncode == 0
    assert aot_res.stdout == cp.stdout
