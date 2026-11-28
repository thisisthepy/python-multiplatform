"""PR-O2b (SPEC N-17, N-12b/c step 2): TypedPython AOT scope regions + --explain-alloc region decisions.

Covers (design: docs/design/typedpython-aot.md 6.3.2 item 2, "Step 2 as implemented"):
  - A per-iteration region: a linked chain of unbounded length built and walked inside one loop iteration
    is bump-allocated in a region reset at the top of every iteration (`tpa_reg_<F>__<C>_reset`), no
    `tpa_cls_<C>_new`, no retain/release of the class; CPython-identical output.
  - A function-scope region with an early `return` and with an exception path (`1 // 0`): the region is
    freed once at `tp_cleanup:` (ASan/UBSan job: no leak, no use after free).
  - A region object that owns heap values (a `list[int]`, a big int, a heap-rc object): the generated
    per-class walk releases them at reset and at free.
  - Nested scopes: a per-iteration object stored into a function-scope object widens the inner class to
    the function scope (R2's outlives rule); a per-iteration object holding a function-scope pointer stays
    per-iteration.
  - Escapes are not region (R1 return, R2 store into a heap object, R3 call) and a heap pointer flowing
    into a local keeps the class heap-rc; the report carries `[region: <reason>]` after the virtual reason.
  - Identity (`is`) and rebinding across branches, which the virtual tier refuses, are fine in a region.
  - `benchmarks/typedpython/py/binary_trees.py`: `Node` stays heap-rc with `[region: R1: escapes via return]`
    (callee specialisations are a later step) and still builds.
  Every generated C text is checked with the C compiler's syntax-only mode and
  `-Werror=incompatible-pointer-types`: a region pointer (`tpa_reg_<F>__<C> *`) and a heap pointer
  (`tpa_cls_<C> *`) are distinct C types, so mixing them is a hard error there.
"""
from __future__ import annotations

import subprocess
import sys
import textwrap
from pathlib import Path

from typedpython import aot, cbuild
from typedpython.aot import cc as cc_mod

ROOT = Path(__file__).resolve().parents[4]
BENCH = ROOT / "benchmarks" / "typedpython" / "py"


def _write(tmp_path: Path, src: str, name: str = "prog.py") -> Path:
    p = tmp_path / name
    p.write_text(textwrap.dedent(src).lstrip(), encoding="utf-8")
    return p


def _run_both(tmp_path: Path, src: str, args: list[str] | None = None, name: str = "prog.py"):
    p = _write(tmp_path, src, name)
    exe = aot.build(p, tmp_path / (Path(name).stem + "_bin"))
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


def _syntax_check(c_text: str, tmp_path: Path, name: str) -> None:
    """The generated C through the compiler's syntax-only mode with `-Wall -Wextra` and the one warning that
    matters here promoted to an error: a region pointer used as a heap pointer or the reverse. GCC and
    Clang only; with MSVC the build in `_run_both` is the check."""
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


NODE = """
class Node:
    __slots__ = ("val", "next")
    val: int
    next: "Node | None"

    def __init__(self, val: int, next: "Node | None") -> None:
        self.val = val
        self.next = next
"""


def test_region_per_iteration_linked_chain(tmp_path: Path):
    src = NODE + """

def run(n: int) -> int:
    total: int = 0
    for i in range(n):
        head: Node | None = None
        for j in range(i + 1):
            head = Node(j, head)
        cur: Node | None = head
        while cur is not None:
            total += cur.val
            cur = cur.next
    return total


if __name__ == "__main__":
    print(run(40))
    print(run(0))
"""
    p = _write(tmp_path, src)
    report = aot.explain_alloc(p)
    assert "run:" in report
    assert "head = Node(...) -> region (per-iteration scope of the for loop, depth 1)" in report

    c_text = aot.generate(p)
    _syntax_check(c_text, tmp_path, "chain")
    body = _func_body_c(c_text, "run")
    assert "tpa_region_alloc(" in body
    assert "tpa_reg_run__Node_reset(" in body
    assert "tpa_reg_run__Node_free(" in body
    assert "tpa_cls_Node_" not in body

    cp, aot_res = _run_both(tmp_path, src)
    assert cp.returncode == 0
    assert aot_res.returncode == 0
    assert aot_res.stdout == cp.stdout


def test_region_function_scope_early_return_and_exception(tmp_path: Path):
    src = NODE + """

def find(n: int, target: int) -> int:
    head: Node | None = None
    for j in range(n):
        head = Node(j * 3, head)
    cur: Node | None = head
    while cur is not None:
        if cur.val == target:
            return cur.val * 2
        cur = cur.next
    return -1


def boom(n: int, d: int) -> int:
    head: Node | None = None
    for j in range(n):
        head = Node(j, head)
    q: int = 1 // d
    if head is None:
        return q
    return q + head.val


if __name__ == "__main__":
    print(find(10, 9))
    print(find(10, 4))
    print(find(0, 0))
    print(boom(5, 2))
    print(boom(5, 0))
"""
    p = _write(tmp_path, src)
    report = aot.explain_alloc(p)
    assert "find:" in report
    assert "boom:" in report
    assert report.count("head = Node(...) -> region (function scope)") == 2

    c_text = aot.generate(p)
    _syntax_check(c_text, tmp_path, "early")
    for fn in ("find", "boom"):
        body = _func_body_c(c_text, fn)
        assert "tpa_region_alloc(" in body
        assert "_reset(" not in body
        assert body.count("tpa_reg_" + fn + "__Node_free(") == 1
        assert "tpa_cls_Node_" not in body

    cp, aot_res = _run_both(tmp_path, src)
    assert cp.returncode != 0            # boom(5, 0) raises ZeroDivisionError after four lines of output
    assert aot_res.returncode != 0
    assert aot_res.stdout == cp.stdout
    assert "ZeroDivisionError" in aot_res.stderr


def test_region_object_owning_heap_values(tmp_path: Path):
    src = """
class Payload:
    __slots__ = ("val",)
    val: int

    def __init__(self, val: int) -> None:
        self.val = val


class Holder:
    __slots__ = ("items", "big", "pay", "next")
    items: list[int]
    big: int
    pay: Payload
    next: "Holder | None"

    def __init__(self, items: list[int], big: int, pay: Payload, next: "Holder | None") -> None:
        self.items = items
        self.big = big
        self.pay = pay
        self.next = next


def bump(p: Payload) -> int:
    return p.val + 1


def run(n: int) -> int:
    acc: int = 0
    for i in range(n):
        xs: list[int] = [i, i + 1]
        p: Payload = Payload(i)
        acc += bump(p)
        h: Holder | None = None
        for j in range(3):
            h = Holder(xs, 1 << (64 + j), p, h)
        cur: Holder | None = h
        while cur is not None:
            items: list[int] = cur.items
            pay: Payload = cur.pay
            acc += items[1] + pay.val + (cur.big >> 62)
            cur = cur.next
    return acc


if __name__ == "__main__":
    print(run(12))
"""
    p = _write(tmp_path, src)
    report = aot.explain_alloc(p)
    assert "h = Holder(...) -> region (per-iteration scope of the for loop, depth 1)" in report
    assert "p = Payload(...) -> heap-rc (escapes via call to bump) [region: R3: escapes via call to bump]" in report

    c_text = aot.generate(p)
    _syntax_check(c_text, tmp_path, "owning")
    body = _func_body_c(c_text, "run")
    assert "tpa_reg_run__Holder_reset(" in body
    assert "tpa_cls_Holder_" not in body
    assert "tpa_cls_Payload_new(" in body
    # the per-class walk releases every owned heap field of every object in the region
    drop_start = c_text.index("static void tpa_reg_run__Holder_drop(tpa_region *r)\n")
    drop = c_text[drop_start:c_text.index("\n}\n", drop_start)]
    assert "tpa_i64_release(" in drop
    assert "tpa_int_release(" in drop
    assert "tpa_cls_Payload_release(" in drop
    assert "f_next" not in drop            # a region pointer is never released

    cp, aot_res = _run_both(tmp_path, src)
    assert cp.returncode == 0
    assert aot_res.returncode == 0
    assert aot_res.stdout == cp.stdout


def test_region_nested_scopes(tmp_path: Path):
    src = """
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


class Wrap:
    __slots__ = ("leaf", "k")
    leaf: Leaf
    k: int

    def __init__(self, leaf: Leaf, k: int) -> None:
        self.leaf = leaf
        self.k = k


def inner_into_outer(n: int) -> int:
    t: Tree = Tree(None, 5)
    u: Tree = t
    acc: int = 0
    for i in range(n):
        lf: Leaf = Leaf(i)
        u.leaf = lf
        acc += u.n + lf.v
    last: Leaf | None = u.leaf
    if last is not None:
        acc += last.v * 100
    return acc


def outer_into_inner(n: int) -> int:
    base: Leaf = Leaf(7)
    b2: Leaf = base
    acc: int = 0
    for i in range(n):
        w: Wrap = Wrap(b2, i)
        w2: Wrap = w
        lf: Leaf = w2.leaf
        acc += lf.v + w2.k
    return acc


if __name__ == "__main__":
    print(inner_into_outer(6))
    print(inner_into_outer(0))
    print(outer_into_inner(9))
"""
    p = _write(tmp_path, src)
    report = aot.explain_alloc(p)
    lines = report.splitlines()
    io = [ln for ln in lines if ln.startswith("inner_into_outer:")]
    oi = [ln for ln in lines if ln.startswith("outer_into_inner:")]
    assert any("t = Tree(...) -> region (function scope)" in ln for ln in io)
    assert any("lf = Leaf(...) -> region (function scope)" in ln for ln in io)       # widened: stored into `u`
    assert any("base = Leaf(...) -> region (function scope)" in ln for ln in oi)
    assert any("w = Wrap(...) -> region (per-iteration scope of the for loop, depth 1)" in ln for ln in oi)

    c_text = aot.generate(p)
    _syntax_check(c_text, tmp_path, "nested")
    b1 = _func_body_c(c_text, "inner_into_outer")
    assert "_reset(" not in b1
    assert "tpa_reg_inner_into_outer__Leaf_free(" in b1
    assert "tpa_reg_inner_into_outer__Tree_free(" in b1
    b2 = _func_body_c(c_text, "outer_into_inner")
    assert "tpa_reg_outer_into_inner__Wrap_reset(" in b2
    assert "tpa_reg_outer_into_inner__Leaf_reset(" not in b2
    assert "tpa_cls_Leaf_" not in b1 + b2
    assert "tpa_cls_Wrap_" not in b2
    assert "tpa_cls_Tree_" not in b1

    cp, aot_res = _run_both(tmp_path, src)
    assert cp.returncode == 0
    assert aot_res.returncode == 0
    assert aot_res.stdout == cp.stdout


def test_region_fallbacks_report_their_rule(tmp_path: Path):
    src = """
class Point:
    __slots__ = ("x", "y")
    x: int
    y: int

    def __init__(self, x: int, y: int) -> None:
        self.x = x
        self.y = y


class Holder:
    __slots__ = ("p",)
    p: Point | None

    def __init__(self, p: Point | None) -> None:
        self.p = p


def make_point(x: int, y: int) -> Point:
    p: Point = Point(x, y)
    return p


def consume(p: Point) -> int:
    return p.x * 10 + p.y


def via_call(a: int, b: int) -> int:
    p: Point = Point(a, b)
    return consume(p)


def store(h: Holder, a: int) -> None:
    p: Point = Point(a, a + 1)
    h.p = p


def pick(a: Point, flag: int) -> int:
    p: Point = Point(1, 2)
    if flag > 0:
        p = a
    return p.x + p.y


if __name__ == "__main__":
    q: Point = make_point(3, 4)
    h: Holder = Holder(None)
    store(h, 7)
    hp: Point | None = h.p
    s: int = 0
    if hp is not None:
        s = hp.x + hp.y
    print(q.x + q.y, via_call(5, 6), s, pick(q, 1), pick(q, 0))
"""
    p = _write(tmp_path, src)
    report = aot.explain_alloc(p)
    assert "p = Point(...) -> heap-rc (escapes via return) [region: R1: escapes via return]" in report
    assert "p = Point(...) -> heap-rc (escapes via call to consume) [region: R3: escapes via call to consume]" in report
    assert ("p = Point(...) -> heap-rc (escapes into object field) "
            "[region: R2: escapes into a heap object field Holder.p]") in report
    assert ("p = Point(...) -> heap-rc (reassigned or branch-merged) "
            "[region: heap Point flows in (parameter a)]") in report
    assert "region (" not in report.replace("[region:", "")

    c_text = aot.generate(p)
    _syntax_check(c_text, tmp_path, "fallbacks")
    assert "tpa_region_alloc(" not in c_text

    cp, aot_res = _run_both(tmp_path, src)
    assert cp.returncode == 0
    assert aot_res.returncode == 0
    assert aot_res.stdout == cp.stdout


def test_region_identity_and_rebinding(tmp_path: Path):
    src = """
class Point:
    __slots__ = ("x", "y")
    x: int
    y: int

    def __init__(self, x: int, y: int) -> None:
        self.x = x
        self.y = y


def check(n: int) -> int:
    p: Point = Point(n, n + 1)
    q: Point = Point(n, n + 1)
    r: Point = p
    if n % 2 == 0:
        r = q
    acc: int = 0
    if p is not q:
        acc += 1
    if r is q:
        acc += 10
    if r is not None:
        r.x = r.x + 5
    return acc + p.x + q.x + r.y


if __name__ == "__main__":
    print(check(3))
    print(check(4))
"""
    p = _write(tmp_path, src)
    report = aot.explain_alloc(p)
    assert "p = Point(...) -> region (function scope)" in report
    assert "q = Point(...) -> region (function scope)" in report

    c_text = aot.generate(p)
    _syntax_check(c_text, tmp_path, "identity")
    body = _func_body_c(c_text, "check")
    assert body.count("tpa_region_alloc(") == 2
    assert "tpa_cls_Point_" not in body

    cp, aot_res = _run_both(tmp_path, src)
    assert cp.returncode == 0
    assert aot_res.returncode == 0
    assert aot_res.stdout == cp.stdout


def test_binary_trees_stays_heap_rc_with_region_reason(tmp_path: Path):
    bench = BENCH / "binary_trees.py"
    report = aot.explain_alloc(bench)
    make_lines = [ln for ln in report.splitlines() if ln.startswith("make:")]
    assert len(make_lines) == 2
    for ln in make_lines:
        assert "<temp> = Node(...) -> heap-rc (temporary (not bound to a single local)) " \
               "[region: R1: escapes via return]" in ln
    assert "-> region (" not in report

    c_text = aot.generate(bench)
    _syntax_check(c_text, tmp_path, "binary_trees")
    assert "tpa_region_alloc(" not in c_text
    exe = aot.build(bench, tmp_path / "binary_trees_bin")
    cp = subprocess.run([sys.executable, str(bench), "6"], capture_output=True, text=True, check=True)
    aot_res = subprocess.run([str(exe), "6"], capture_output=True, text=True, check=True)
    assert aot_res.stdout == cp.stdout


def test_region_refuses_a_class_with_no_fields(tmp_path: Path):
    """Review of #501, finding 1: a class with no fields must stay heap-rc. A region struct for it is empty,
    `tpa_region_alloc` of size 0 returns the same address every time, and `a is b` would be true for two
    distinct objects (CPython prints 0 here). Red before the fix: the report said
    `-> region (per-iteration scope of the for loop, depth 1)`, the C held `struct tpa_reg_run__Empty { };`
    and two `tpa_region_alloc(` calls, and the binary printed 3."""
    src = """
class Empty:
    __slots__ = ()

    def __init__(self) -> None:
        pass


def run(n: int) -> int:
    acc: int = 0
    for i in range(n):
        a: Empty = Empty()
        b: Empty = Empty()
        if a is b:
            acc += 1
    return acc


if __name__ == "__main__":
    print(run(3))
"""
    p = _write(tmp_path, src)
    report = aot.explain_alloc(p)
    assert ("a = Empty(...) -> heap-rc (used in identity comparison (is)) "
            "[region: class has no fields]") in report
    assert ("b = Empty(...) -> heap-rc (used in identity comparison (is)) "
            "[region: class has no fields]") in report
    assert "-> region (" not in report

    c_text = aot.generate(p)
    _syntax_check(c_text, tmp_path, "empty_class")
    assert "tpa_region_alloc(" not in c_text
    assert "tpa_reg_run__Empty" not in c_text

    cp, aot_res = _run_both(tmp_path, src)
    assert cp.returncode == 0
    assert aot_res.returncode == 0
    assert aot_res.stdout == cp.stdout == "0\n"


NODE_N = """
class Node:
    __slots__ = ("n",)
    n: int

    def __init__(self, n: int) -> None:
        self.n = n


def size(p: Node) -> int:
    return p.n + 1
"""


def test_region_rules_see_the_length_of_a_list_repeat(tmp_path: Path):
    """Review of #501, finding 2: `[0] * size(p)` keeps `size(p)` inline as `NewArray.length`, and the
    expression walkers skipped `length` and `fill`, so a pointer passed to a call there broke no rule.
    Red before the fix: with the alias `q = p` the analysis made `(run, Node)` region and the emitter's
    guard refused the program (`aot/region: a region pointer reaches the call of size`); without the alias
    `p` was scalar-replaced and the C called `tpf_size(v_p, ...)` with `v_p` undeclared (#495's twin)."""
    with_alias = NODE_N + """

def run(k: int) -> int:
    p: Node = Node(k)
    q: Node = p
    xs: list[int] = [0] * size(p)
    return len(xs) + p.n + q.n


if __name__ == "__main__":
    print(run(5))
"""
    p = _write(tmp_path, with_alias, "alias.py")
    report = aot.explain_alloc(p)
    assert ("p = Node(...) -> heap-rc (escapes via alias assignment) "
            "[region: R3: escapes via call to size]") in report
    assert "-> region (" not in report
    c_text = aot.generate(p)
    _syntax_check(c_text, tmp_path, "alias")
    assert "tpa_region_alloc(" not in c_text
    cp, aot_res = _run_both(tmp_path, with_alias, name="alias.py")
    assert cp.returncode == 0
    assert aot_res.returncode == 0
    assert aot_res.stdout == cp.stdout == "16\n"

    no_alias = NODE_N + """

def run(k: int) -> int:
    p: Node = Node(k)
    xs: list[int] = [0] * size(p)
    return len(xs) + p.n


if __name__ == "__main__":
    print(run(5))
"""
    p = _write(tmp_path, no_alias, "noalias.py")
    report = aot.explain_alloc(p)
    assert ("p = Node(...) -> heap-rc (escapes via call to size) "
            "[region: R3: escapes via call to size]") in report
    c_text = aot.generate(p)
    _syntax_check(c_text, tmp_path, "noalias")
    assert "v_p" in _func_body_c(c_text, "run")
    cp, aot_res = _run_both(tmp_path, no_alias, name="noalias.py")
    assert cp.returncode == 0
    assert aot_res.returncode == 0
    assert aot_res.stdout == cp.stdout == "11\n"

    # The walker itself, for the shapes no program reaches today (a read inside `NewArray.length` in the
    # per-iteration safety check, a region pointer as the callee or an argument of an object call).
    from typedpython import ir
    from typedpython.aot.alloc import _walk_exprs_in_expr

    arr = ir.NewArray(ir.Type.I64_ARRAY, ir.Local(ir.Type.I64, "n"), ir.Local(ir.Type.I64, "v"))
    assert {e.name for e in _walk_exprs_in_expr(arr) if isinstance(e, ir.Local)} == {"n", "v"}
    call = ir.CallObject(ir.Type.OBJ, ir.Local(ir.Type.OBJ, "cb"), (ir.Local(ir.Type.OBJ, "p"),))
    assert {e.name for e in _walk_exprs_in_expr(call) if isinstance(e, ir.Local)} == {"cb", "p"}
    box = ir.Box(ir.Type.OBJ, ir.Local(ir.Type.I64, "x"), ir.Local(ir.Type.BOOL, "x_np"))
    assert {e.name for e in _walk_exprs_in_expr(box) if isinstance(e, ir.Local)} == {"x", "x_np"}
