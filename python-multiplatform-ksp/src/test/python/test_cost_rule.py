"""The cost policy (cost.py, #150): a function is compiled only if it does native work.

A verified function that only does object operations in its loops (in an impure function an int is a
CPython object, so `+`, `//`, `%` and `>` are generic PyNumber / RichCompare calls) is slower compiled
than interpreted, so it stays interpreted with a diagnostic. Pure functions are never touched.
`pipeline.compile_module(..., cost_rule=False)` is the test-only opt-out.
"""
from __future__ import annotations

import textwrap

import pytest

from typedpython import cost, pipeline

HEADER = "# typedpython: compiled\n"

OBJECT_ONLY = HEADER + textwrap.dedent('''
    def word_of(word_id: int) -> str:
        x: int = word_id + 17576
        w: str = ""
        while x > 0:
            w = chr(97 + x % 26) + w
            x //= 26
        return w
''')


def build(tmp_path, source, **kw):
    path = tmp_path / "m.py"
    path.write_text(source)
    return pipeline.compile_module(path, tmp_path / "out", **kw)


def names(result):
    return {f.name for f in result.module.functions}


def test_an_object_only_loop_stays_interpreted_with_the_reason(tmp_path):
    r = build(tmp_path, OBJECT_ONLY)
    assert names(r) == set() and r.extension is None
    assert "slower than the interpreter" in r.skipped["word_of"]
    assert r.skipped["word_of"].startswith("policy/cost: only object operations in its loops")
    assert [d.rule for d in r.diagnostics] == ["policy/cost"]


def test_the_opt_out_compiles_the_same_function(tmp_path):
    r = build(tmp_path, OBJECT_ONLY, cost_rule=False)
    assert names(r) == {"word_of"} and r.extension is not None


MIXED = HEADER + textwrap.dedent('''
    def mixed(n: int, xs: list[str]) -> float:
        total: float = 0.0
        for i in range(n):
            total += 1.5 * 2.0
            xs.append("a")
        return total


    def sq(k: int) -> int:
        return k * k


    def pure_no_loop(a: float, b: float) -> float:
        return a * b + 1.0


    def object_loop_with_native_op_outside(n: int, xs: list[str]) -> float:
        total: float = 1.0 + 2.0
        i: int = 0
        while i < n:
            xs.append("a")
            i += 1
        return total


    def native_loop_inside_object_loop(rows: list[list[str]]) -> float:
        total: float = 0.0
        for row in rows:
            for k in range(3):
                total += 0.5 * 2.0
        return total
''')


def test_one_native_loop_with_object_operations_compiles(tmp_path):
    r = build(tmp_path, MIXED)
    got = names(r)
    assert {"mixed", "pure_no_loop", "native_loop_inside_object_loop"} <= got, r.skipped
    assert "object_loop_with_native_op_outside" not in got            # the native op is outside the loop
    assert "object_loop_with_native_op_outside" in r.skipped


def test_a_pure_function_is_never_rejected_even_without_a_loop(tmp_path):
    r = build(tmp_path, MIXED)
    assert "pure_no_loop" in names(r) and "sq" in names(r)


def test_a_caller_of_a_rejected_function_is_rejected_too(tmp_path):
    # unit level: the propagation over direct Calls
    from typedpython import frontend, ir, verify
    src = tmp_path / "p.py"
    src.write_text(OBJECT_ONLY)
    proved, diags = verify.verify(frontend.lower(src))
    assert [f.name for f in proved.functions] == ["word_of"]
    word = proved.functions[0]
    caller = ir.Function(name="c", params=(), returns=ir.Type.NONE, locals={}, pure=False,
                         may_deopt=False, source_line=1,
                         body=(ir.ForRange("i", ir.Const(ir.Type.I64, 0), ir.Const(ir.Type.I64, 3),
                                           ir.Const(ir.Type.I64, 1),
                                           (ir.ExprStmt(ir.Call(ir.Type.NONE, "word_of", ())),)),))
    module = ir.Module("p", (word, caller))
    out, diags = cost.apply(module, [])
    assert out.functions == ()                       # `c` has no native op (an empty-arg Call is not one)
    assert "word_of" in out.skipped and "c" in out.skipped


def test_the_rule_is_what_keeps_it_interpreted(tmp_path, monkeypatch):
    """Mutation: with the rule replaced by 'always native' the first test above must fail."""
    monkeypatch.setattr(cost, "does_native_work", lambda fn: True)
    with pytest.raises(AssertionError):
        test_an_object_only_loop_stays_interpreted_with_the_reason(tmp_path)


STRAIGHT_LINE = HEADER + textwrap.dedent('''
    def label(n: int, name: str) -> str:
        return f"{name}:{n + 1}"
''')


def test_a_straight_line_impure_function_still_compiles(tmp_path):
    # no loop to lose to the interpreter: what compiled before #150 keeps compiling
    r = build(tmp_path, STRAIGHT_LINE)
    assert names(r) == {"label"}, r.skipped
