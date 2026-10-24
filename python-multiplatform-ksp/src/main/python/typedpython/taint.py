"""numpy taint (issue #220, step 1): which scalar values are numpy scalars in the interpreter.

In the interpreter an element of a numpy array is an `np.float64` / `np.int64`, and so is every value
computed from one with arithmetic (`s += x[i]`, `x[i] > 0` is an `np.bool`). Compiled code computes
with C scalars, so the front end records, per scalar local, a shadow BOOL local `<name>__np` that says
whether the value held is such a numpy scalar, and every `Box` of a scalar carries the flag
(`Box.np`) so the object that escapes (return value, argument of a call, tuple element...) has the
interpreter's type. The flag is a run-time value because it can differ per path (`s = 0.0` is a
float until the loop adds an element).

Conservative rules (decision 2026-10-25): whatever the pass cannot reproduce exactly is refused
(`Refuse`, the function stays interpreted):
  * `/`, `//`, `%` with a tainted operand (numpy divides without raising and warns),
  * `+ - *` on F64 with a tainted operand in an impure function (numpy warns on overflow / invalid
    value; a pure function deopts on a non-finite result instead, `BinOp.np`),
  * a tainted value stored into a list or local array, passed to a compiled callee, or used as the
    fill of a local array (the object stored or passed would have to be the numpy scalar),
  * a comparison of an I64 with an F64 where one side is tainted (numpy converts the int to double,
    CPython compares exactly), `And` / `Or` of values that are not all plain.
What this cannot find: a numpy semantic not listed above that the front end lowers to an IR node
(there is none today for + - * unary, compares, math.*, len, index); the tests pin each.
"""
from __future__ import annotations

import dataclasses

from . import ir
from .ir import Type


class Refuse(Exception):
    """The function uses numpy-derived values in a way step 1 does not reproduce."""


TRUE = ir.Const(Type.BOOL, True)


def flag_name(name: str) -> str:
    return f"{name}__np"


def _or(a: ir.Expr | None, b: ir.Expr | None) -> ir.Expr | None:
    if a is None:
        return b
    if b is None:
        return a
    if a == TRUE or b == TRUE:
        return TRUE
    return ir.Or(Type.BOOL, a, b)


class _Pass:
    def __init__(self, numpy: set[str], pure: bool) -> None:
        self.numpy = numpy
        self.pure = pure
        self.flagged: set[str] = set()
        self.deopts = False      # some BinOp got `np` (it can deopt)

    # --- taint of an expression: None (a plain value) or a BOOL expression ---------------------

    def taint(self, e: ir.Expr) -> ir.Expr | None:
        if e.type not in ir.SCALARS:
            return None
        if isinstance(e, ir.Index):
            return TRUE if e.array in self.numpy else None
        if isinstance(e, ir.Local):
            return ir.Local(Type.BOOL, flag_name(e.name)) if e.name in self.flagged else None
        if isinstance(e, ir.ToFloat):
            return None if e.explicit else self.taint(e.operand)
        if isinstance(e, ir.BinOp):
            t = _or(self.taint(e.left), self.taint(e.right))
            if t is not None and e.op in (ir.BinOpKind.TRUEDIV, ir.BinOpKind.FLOORDIV,
                                          ir.BinOpKind.MOD):
                raise Refuse(f"`{e.op.value}` of a value derived from a numpy element (numpy divides "
                             "without raising and warns; not reproduced yet)")
            if t is not None and e.type is Type.F64 and not self.pure:
                raise Refuse(f"`{e.op.value}` of a value derived from a numpy element in a function "
                             "with effects (numpy would warn on overflow; not reproduced yet)")
            return t
        if isinstance(e, ir.UnaryOp):
            return None if e.op is ir.UnaryOpKind.NOT else self.taint(e.operand)
        if isinstance(e, ir.Compare):
            t = _or(self.taint(e.left), self.taint(e.right))
            if t is not None and e.left.type is not e.right.type and Type.BOOL not in (
                    e.left.type, e.right.type):
                raise Refuse("comparison of an int and a float where one is derived from a numpy "
                             "element (numpy converts the int to double, CPython compares exactly)")
            return t
        if isinstance(e, (ir.And, ir.Or)):
            if self.taint(e.left) is not None or self.taint(e.right) is not None:
                raise Refuse("`and` / `or` of a value derived from a numpy element")
            return None
        if isinstance(e, ir.Call):
            if any(self.taint(a) is not None for a in e.args):
                raise Refuse(f"a value derived from a numpy element is passed to `{e.function}`")
            return None
        # Const, Len, MathCall (a float), Unbox, ObjToFloat, ...: plain values
        return None

    def plain(self, e: ir.Expr, what: str) -> None:
        if self.taint(e) is not None:
            raise Refuse(f"a value derived from a numpy element is {what}")

    # --- discovery of the locals that need a flag ---------------------------------------------------

    def discover(self, body) -> None:
        while True:
            before = len(self.flagged)
            self._scan(body)
            if len(self.flagged) == before:
                return

    def _scan(self, x) -> None:
        if isinstance(x, (tuple, list)):
            for y in x:
                self._scan(y)
        elif isinstance(x, ir.Assign):
            if x.value.type in ir.SCALARS and self.taint(x.value) is not None:
                self.flagged.add(x.target)
        elif dataclasses.is_dataclass(x) and not isinstance(x, type):
            for f in dataclasses.fields(x):
                self._scan(getattr(x, f.name))

    # --- rewrite -----------------------------------------------------------------------------------

    def rewrite(self, x):
        if isinstance(x, tuple):
            out = []
            for y in x:
                r = self.rewrite(y)
                out.extend(r if isinstance(r, list) else [r])
            return tuple(out)
        if not dataclasses.is_dataclass(x) or isinstance(x, type):
            return x
        if isinstance(x, ir.Assign):
            value = self.rewrite(x.value)
            stmt = dataclasses.replace(x, value=value)
            if x.target in self.flagged:
                t = self.taint(x.value)
                return [stmt, ir.Assign(flag_name(x.target), t if t is not None
                                        else ir.Const(Type.BOOL, False))]
            return stmt
        if isinstance(x, ir.Box) and x.operand.type in ir.SCALARS:
            return dataclasses.replace(x, operand=self.rewrite(x.operand), np=self.taint(x.operand))
        if isinstance(x, ir.BinOp) and x.type in ir.SCALARS:
            self.taint(x)            # refuses division / impure arithmetic wherever it appears
        if isinstance(x, ir.BinOp) and x.type is Type.F64 and x.op in (
                ir.BinOpKind.ADD, ir.BinOpKind.SUB, ir.BinOpKind.MUL):
            tainted = _or(self.taint(x.left), self.taint(x.right)) is not None
            self.deopts = self.deopts or tainted
            return dataclasses.replace(x, left=self.rewrite(x.left), right=self.rewrite(x.right),
                                       np=tainted)
        if isinstance(x, ir.StoreIndex) and x.array not in self.numpy:
            self.plain(x.value, "stored into a list or local array")
        if isinstance(x, ir.NewArray) and x.fill is not None:
            self.plain(x.fill, "the fill of a local array")
        if isinstance(x, ir.ToInt):
            self.plain(x.operand, "widened to a native INT (SPEC N-13), which has no numpy form")
        if isinstance(x, ir.Call):
            self.taint(x)            # refuses a tainted argument
        if isinstance(x, ir.Compare):
            self.taint(x)            # refuses what cannot be reproduced even inside a condition
        changes = {}
        for f in dataclasses.fields(x):
            v = getattr(x, f.name)
            r = self.rewrite(v)
            if r is not v:
                changes[f.name] = r
        return dataclasses.replace(x, **changes) if changes else x


def apply(body: tuple, numpy: set[str], pure: bool,
          scalar_params: frozenset[str] = frozenset()) -> tuple[tuple, dict[str, Type], bool]:
    """Rewrite a lowered body: shadow flags and `Box.np` / `BinOp.np`. Returns the new body and the
    new locals (the flags, BOOL) and whether the body now can deopt. Raises Refuse."""
    p = _Pass(numpy, pure)
    p.discover(body)
    new = p.rewrite(body)
    # a parameter that is reassigned from a numpy element: its flag starts False
    first = tuple(ir.Assign(flag_name(n), ir.Const(Type.BOOL, False))
                  for n in sorted(p.flagged & scalar_params))
    new = first + new
    return new, {flag_name(n): Type.BOOL for n in p.flagged}, p.deopts
