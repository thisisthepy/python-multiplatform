"""Cost policy: compile a function only if the C is expected to beat the interpreter (#150).

This is a policy, not a proof. `verify` proves that the C for a function is safe and keeps CPython's
results; it says nothing about speed, and a function can pass every proof and still be slower
compiled. The case that made this necessary: in an *impure* function a Python `int` is a CPython
object (the front end cannot prove i64 arithmetic free of overflow where a deopt could not be
redone), so every `+`, `//`, `%` and comparison of such code is a generic `PyNumber_*` /
`PyObject_RichCompare` call on a boxed int. CPython 3.15's specialising interpreter runs exactly that
faster than the generic C calls (wordfreq `word_of`: 248.6 ms compiled against 95.7 ms interpreted).
Compiled code wins only when it does native work, so the rule asks for it.

The rule, applied after verification (so it only ever removes verified functions, never changes
the IR): a function is kept when it is **pure**, or when some loop (`While`, `ForRange`; a `ForObj`
is a loop over objects and does not count by itself, but loops nested inside it do) has a body, nested
statements included, containing a native-typed operation:

  * a `BinOp`, `Compare`, `UnaryOp` or `MathCall` on I64/F64/BOOL operands;
  * an `Index` or `StoreIndex` on an array or buffer;
  * a `Call` of a compiled function with at least one argument, all of them native.

A pure function qualifies by construction: it has no object operation at all (no `GetAttr`,
`CallObject`, OBJ `BinOp` ...), so everything it does is native, loop or not (`eval_a`, a pure
straight-line formula, must stay compiled). The rule therefore changes no pure function.

A rejected function stays interpreted with the diagnostic below. Because an impure function can be
the target of a direct C `Call`, anything that calls a rejected function is rejected too (the same
propagation `verify` uses for an unverified callee).

Straight-line impure functions (no loop at all) are kept: they have no loop to lose to the
interpreter, and that keeps every function that compiled before #150 compiled (fixed-layout setters,
Kotlin calls). The rule targets loops over objects only.

The real fix is native ints in impure functions (a separate issue); when it lands, this rule is the
thing to revisit.
"""
from __future__ import annotations

import dataclasses

from typedpython import ir
from typedpython.ir import Type
from typedpython.verify import Diagnostic

RULE = "policy/cost"
REASON = ("only object operations in its loops: compiled C would be slower than the interpreter "
          "(#150)")

_NATIVE = (Type.I64, Type.F64, Type.BOOL)


def _walk(x):
    if isinstance(x, (tuple, list)):
        for y in x:
            yield from _walk(y)
    elif dataclasses.is_dataclass(x) and not isinstance(x, type):
        yield x
        for f in dataclasses.fields(x):
            yield from _walk(getattr(x, f.name))


def _native_op(n) -> bool:
    if isinstance(n, ir.BinOp):
        return n.left.type in _NATIVE
    if isinstance(n, ir.UnaryOp):
        return n.operand.type in _NATIVE
    if isinstance(n, ir.Compare):
        return n.left.type in _NATIVE and n.right.type in _NATIVE
    if isinstance(n, ir.MathCall):
        return True
    if isinstance(n, (ir.Index, ir.StoreIndex)):
        return True
    if isinstance(n, ir.Call):
        return bool(n.args) and all(a.type in _NATIVE for a in n.args)
    return False


def _loops(body):
    for n in _walk(body):
        if isinstance(n, (ir.While, ir.ForRange)):
            yield n


def does_native_work(fn: ir.Function) -> bool:
    if fn.pure:
        return True
    if not any(isinstance(n, (ir.While, ir.ForRange, ir.ForObj)) for n in _walk(fn.body)):
        # straight-line code (a field setter, a Kotlin call): no loop to lose to the interpreter, and
        # what compiled before #150 keeps compiling
        return True
    return any(_native_op(n) for loop in _loops(fn.body) for n in _walk(loop.body))


def apply(module: ir.Module, diagnostics: list[Diagnostic]) -> tuple[ir.Module, list[Diagnostic]]:
    """`module` without the functions the rule rejects (they move to `skipped`), and the diagnostics
    extended by one per rejected function."""
    reasons: dict[str, str] = {}
    for fn in module.functions:
        if not does_native_work(fn):
            reasons[fn.name] = f"{RULE}: {REASON}"
    changed = True
    while changed:                      # a direct Call needs its callee compiled
        changed = False
        for fn in module.functions:
            if fn.name in reasons:
                continue
            bad = sorted({n.function for n in _walk(fn.body)
                          if isinstance(n, ir.Call) and n.function in reasons})
            if bad:
                reasons[fn.name] = (f"{RULE}: calls '{bad[0]}', which is not compiled "
                                    f"({reasons[bad[0]]})")
                changed = True
    if not reasons:
        return module, diagnostics
    diags = list(diagnostics)
    lines = {f.name: f.source_line for f in module.functions}
    for name, text in reasons.items():
        diags.append(Diagnostic(name, RULE, text.split(": ", 1)[1], lines[name]))
    kept = tuple(f for f in module.functions if f.name not in reasons)
    skipped = {**module.skipped, **{n: t for n, t in reasons.items()}}
    return dataclasses.replace(module, functions=kept, skipped=skipped), diags
