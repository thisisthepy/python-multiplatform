"""TypedPython IR safety verifier (SPEC N-9, #41; maintainer decision 2026-10-03).

`verify(module)` proves, per function and on the IR alone, the properties listed in the "Safety"
section of `ir.py`. A function it cannot prove is removed from `Module.functions` and moved to
`Module.skipped` with the reason, so it stays interpreted; every reason is also returned as a
`Diagnostic`. A proved function comes back with each `Index`/`StoreIndex` whose bounds were proved
marked `proven=True`; every other access has `proven=False` (an input `proven` is never trusted).

The verifier reads nothing but `ir` — it is independent of the front end — and it trusts no flag
the front end set (`pure`, `may_deopt`, `ArrayParam.stored`, node `type`s): each is recomputed from
the body and compared.

Rules (the `rule` of a Diagnostic):

  verify/definite-assignment  a local may be read before it is assigned on some path
  verify/missing-return       a function returning a value can reach the end of its body
  verify/type                 an operand, result, declaration, constant, or call signature is wrong
  verify/ownership            an OBJ is produced by a node that does not create a new reference, or
                              crosses into a scalar slot without Unbox (or a scalar into an OBJ
                              slot without Box)
  verify/deopt-impure         a node that can deopt appears in an impure function
  verify/flag-may-deopt       `may_deopt` differs from what the body computes
  verify/flag-pure            `pure` is set but the body has an effect
  verify/array-use            an array parameter is used other than by Index/StoreIndex/Len (or as a
                              CopyArray source), or those nodes name something that is not an array
  verify/array-stored         `ArrayParam.stored` differs from the body
  verify/array-escape         a local array is used other than by Index/StoreIndex/Len and as a
                              CopyArray source (returned, passed, boxed, stored in a tuple, aliased,
                              compared...), or NewArray/CopyArray stand anywhere but as the value of
                              an Assign to a local array
  verify/unknown-node         a node (or enum field) that `ir` does not define
  verify/call-unresolved      a Call names a function that is not compiled in this module
  verify/call-unverified      a Call names a function that the verifier rejected
  verify/recursion            the function is on a cycle of Calls (C stack depth is unbounded;
                              CPython would raise RecursionError instead)
  verify/condition-only       a CompareObj outside a condition (If/While cond, or an And/Or
                              operand that is itself in a condition): its rich-comparison result
                              need not be a bool, so only its truth value may be used
  verify/structure            break/continue outside a loop, the ForRange var assigned in its body,
                              a zero or non-constant step, duplicate names, bad declarations, an
                              entry global that collides with a parameter/local/other entry global or
                              is assigned in the body
  verify/proven-overflow      `proven` is set on an op the verifier cannot prove stays inside i64 (or
                              on a node `proven` does not apply to, or it is not a bool)
  verify/redo                 `Call.redo` where it is not legal (see below)
  verify/entry-global-open    a function with entry_globals is not closed

Proven i64 operations. The verifier never trusts `BinOp.proven` / `UnaryOp.proven`: for every I64
expression it computes an interval [lo, hi] (exact Python ints) and accepts `proven=True` only on an
I64 ADD/SUB/MUL (BinOp) or NEG (UnaryOp) whose exact result interval lies inside [-2**63, 2**63-1];
anything else (a different op, a non-I64 node, a result that may leave i64, a non-bool flag) is
rejected, and so is the whole function. A proven op is not a deopting node (an impure function may
contain it; a pure one does not count it toward `may_deopt`); every unproven I64 ADD/SUB/MUL/NEG
still deopts. The interval rules, exactly:

  Const v                      [v, v]
  Local, a ForRange var inside its own loop body:
      step > 0                 [lo(start), max(lo(start), hi(stop) - 1)]
      step < 0                 [min(hi(start), lo(stop) + 1), hi(start)]
                               (the bounds are the intervals of the start/stop expressions, both
                               evaluated before the loop; then intersected with the i64 range)
  Len(array)                   [0, 2**62]
  ADD / SUB / MUL              interval arithmetic on the operand intervals (for MUL the min/max of
                               the four endpoint products); a proven op must fit i64 exactly, an
                               unproven one is clamped to i64 (a result outside it deopts, so only
                               in-range values flow on)
  NEG / POS                    [-hi, -lo] / [lo, hi]
  everything else              the full i64 range: I64 params and entry globals, every local that is
                               not the ForRange var of an enclosing loop (the verifier does no flow
                               analysis, so a local assigned a constant is still "any i64"), a loop
                               var after its loop, Index elements, Unbox, Call results, FLOORDIV,
                               MOD, TRUEDIV

Local arrays (NewArray / CopyArray, ir.Function "Local arrays"). A local of an array type holds a
native array the function owns. The verifier proves, never trusting the front end:

  * NewArray/CopyArray are the value of an Assign to a local declared with the same array type, and
    nowhere else (`verify/array-escape` elsewhere, `verify/type` on a type mismatch). NewArray: length
    I64; fill absent iff `iota` (a bool), `iota` only for I64_ARRAY; a fill has the element type.
    CopyArray: the source is a local or parameter array of the same type.
  * No escape: a local array is read only by Index/StoreIndex/Len and as a CopyArray source. Any
    `Local` naming it (a return value, a Call/CallObject/Box/Tuple operand, another assignment...) is
    `verify/array-escape`. It cannot be a loop variable, a parameter, or an entry global.
  * Purity: a StoreIndex into a local array is not an effect (and does not set `stored`, which is
    for ArrayParams); a function whose only stores are into local arrays can be `pure`. A store into
    an array parameter still is. `pure` and `may_deopt` are recomputed as before and compared.
  * Definite assignment: the allocation's operands are read before the array is assigned, and every
    Index/StoreIndex/Len/CopyArray source of a local array needs it assigned on every path.
  * Bounds. (1) The ForRange rule (var over range(c >= 0, Len(a), step > 0)) now also covers a local
    array, but only if the loop body never assigns that array (a reassignment may change its
    length; the stop is evaluated once). (2) A local array assigned by exactly one Assign statement
    in the whole body has a known length interval [lo, hi]: for NewArray the interval of the length
    expression clamped at 0 below (a negative length is an empty array), for CopyArray the interval
    of the source if the source is itself such a local (otherwise [0, 2**62]). Then Len(a) has that
    interval, and an Index/StoreIndex whose index interval lies inside [0, lo - 1] is proven. That
    proves constant indices and bounded index expressions; a negative index is never proven (the
    `proven` flag means 0 <= index < len). An array assigned twice or more has no known length.

Tuple. `Tuple(OBJ, elements)`: a tuple of OBJ expressions (a scalar needs Box), result OBJ, a
reference creator in the ownership rule. Building one is not an effect.

What this cannot prove: anything that needs knowing a value through assignments (accumulators,
`x = 1; x + 1`), relations between variables (`i < n` does not bound `i + n`), bounds that come from
guards or conditions (`if n < 100`), values through array elements, calls or Unbox, the result of
`//` and `%` (even by a constant), a loop var after the loop, or `range` bounds that are themselves
full-range (`range(n)` bounds the var only by i64). A function needing such a proof keeps its
checked, deopting ops (and is then legal only if pure).

Call.redo. Legal only if the caller is impure, the callee is pure and may_deopt, and the callee
returns F64, BOOL or NONE (`verify/redo` otherwise). A redo call is not a deopting node in the
caller: the callee alone is redone, unobservably, since it is pure.

Function.entry_globals. Scalar `Param`s (I64/F64/BOOL), read once at entry as part of the guards;
assigned at entry for definite assignment; the body reads them as `Local(name)` of that type and may
not assign them; their names collide with no parameter, local or other entry global. The function
must be *closed*: no GetAttr, CallObject, Truth, CompareObj, ObjToFloat or OBJ BinOp, and Calls only
to closed functions (a fixpoint over the call graph), else `verify/entry-global-open`. Beyond that
rule (conservative, not in ir.py): a Call of a function that has entry globals can fail the callee's
entry guard in the middle of the caller, which is a deopt there unless the caller is itself guarded
the same way (it lists the same name and type as an entry global, and being closed cannot see it
rebound) or the call is a legal `redo`.
"""
from __future__ import annotations

import dataclasses
from dataclasses import dataclass

from . import ir
from .ir import (
    And, ArrayParam, Assign, BinOp, BinOpKind, Box, Break, Call, CallObject, Compare, CompareKind,
    CompareObj, Const, Continue, CopyArray, ExprStmt, ForRange, Function, GetAttr, Global, If, Index, Len,
    Local, MathCall, MathFunc, Module, NewArray, ObjToFloat, Or, Param, Return, StoreIndex, ToFloat,
    Truth, Tuple, Type, Unbox, UnaryOp, UnaryOpKind, While,
)

DA = "verify/definite-assignment"
MISSING_RETURN = "verify/missing-return"
TYPE = "verify/type"
OWNERSHIP = "verify/ownership"
DEOPT = "verify/deopt-impure"
FLAG_DEOPT = "verify/flag-may-deopt"
FLAG_PURE = "verify/flag-pure"
ARRAY_USE = "verify/array-use"
ARRAY_STORED = "verify/array-stored"
ESCAPE = "verify/array-escape"
UNKNOWN = "verify/unknown-node"
UNRESOLVED = "verify/call-unresolved"
UNVERIFIED = "verify/call-unverified"
RECURSION = "verify/recursion"
STRUCTURE = "verify/structure"
CONDITION_ONLY = "verify/condition-only"
PROVEN = "verify/proven-overflow"
REDO = "verify/redo"
ENTRY_OPEN = "verify/entry-global-open"


@dataclass(frozen=True)
class Diagnostic:
    function: str
    rule: str
    message: str
    source_line: int


# Exact classes, not isinstance: a subclass of an ir node is not an ir node (its meaning is not
# the one the back end implements).
_EXPRS = frozenset({Const, Local, BinOp, UnaryOp, Compare, And, Or, ToFloat, Box, Unbox, MathCall,
                    Call, Global, GetAttr, CallObject, Truth, CompareObj, ObjToFloat, Len, Index,
                    NewArray, CopyArray, Tuple})
_STMTS = frozenset({Assign, StoreIndex, ExprStmt, If, While, ForRange, Return, Break, Continue})
# Nodes whose OBJ result is a new reference (ir.py "ownership": Box, Global, GetAttr, CallObject,
# OBJ BinOp, Tuple), plus reads of an OBJ local (the local owns its reference; the back end increfs or
# borrows under that rule) and Calls of a compiled function returning OBJ (which returns a new
# reference by the same rule).
_OBJ_PRODUCERS = frozenset({Box, Global, GetAttr, CallObject, BinOp, Local, Call, Tuple})
_VALUE_TYPES = (Type.I64, Type.F64, Type.BOOL, Type.OBJ)        # what a local may hold
_RETURN_TYPES = _VALUE_TYPES + (Type.NONE,)
_ELEMENT = {Type.F64_ARRAY: Type.F64, Type.I64_ARRAY: Type.I64}
_MATH_ARITY = {MathFunc.ATAN2: 2, MathFunc.HYPOT: 2}            # every other MathFunc: 1
_I64_DEOPT_OPS = frozenset({BinOpKind.ADD, BinOpKind.SUB, BinOpKind.MUL, BinOpKind.FLOORDIV,
                            BinOpKind.MOD, BinOpKind.TRUEDIV})
_I64_MIN, _I64_MAX = -2 ** 63, 2 ** 63 - 1
_FULL = (_I64_MIN, _I64_MAX)
_LEN_MAX = 2 ** 62
_PROVABLE_OPS = frozenset({BinOpKind.ADD, BinOpKind.SUB, BinOpKind.MUL})

# Definite assignment uses `None` for "unreachable" (bottom: every name counts as assigned).
_State = frozenset[str] | None


def _meet(a: _State, b: _State) -> _State:
    if a is None:
        return b
    if b is None:
        return a
    return a & b


class _Loop:
    def __init__(self, var: str | None, bounds_array: str | None):
        self.var = var                      # ForRange var (None for While)
        self.bounds_array = bounds_array    # array whose indices by `var` are in bounds
        self.interval = _FULL               # the values `var` takes inside the body
        self.breaks: list[_State] = []


class _Checker:
    """Checks one function against the module's signatures. Collects diagnostics; rebuilds the
    body with proven bounds."""

    def __init__(self, function: Function, signatures: dict[str, Function]):
        self.fn = function
        self.sigs = signatures
        self.diags: list[Diagnostic] = []
        self.can_deopt = False
        self.effects: list[str] = []         # reasons the body is impure
        self.stored: set[str] = set()
        self.callees: set[str] = set()
        self.loops: list[_Loop] = []
        self.scalars: dict[str, Type] = {}   # name -> type of every scalar/OBJ variable
        self.arrays: dict[str, Type] = {}    # name -> array type of every ArrayParam
        self.larrays: dict[str, Type] = {}   # name -> array type of every local array
        self.assigns: dict[str, int] = {}    # local array -> number of Assign statements to it
        self.lens: dict[str, tuple[int, int]] = {}   # single-assignment local array -> length
        self.entry: dict[str, Type] = {}     # name -> type of every entry global
        self.opens: list[str] = []           # reasons the function is not closed

    def error(self, rule: str, message: str) -> None:
        d = Diagnostic(self.fn.name, rule, message, self.fn.source_line)
        if d not in self.diags:
            self.diags.append(d)

    # --- declarations -------------------------------------------------------------------------

    def declarations(self) -> frozenset[str]:
        fn = self.fn
        params: set[str] = set()
        for p in fn.params:
            if type(p) is ArrayParam:
                if p.type not in ir.ARRAYS:
                    self.error(TYPE, f"array parameter '{p.name}' has non-array type {p.type}")
                elif not isinstance(p.stored, bool):
                    self.error(TYPE, f"array parameter '{p.name}' has a non-bool `stored`")
                else:
                    self.arrays[p.name] = p.type
            elif type(p) is Param:
                if p.type not in _VALUE_TYPES:
                    self.error(TYPE, f"parameter '{p.name}' has type {p.type}; arrays must be "
                                     f"ArrayParam")
                else:
                    self.scalars[p.name] = p.type
            else:
                self.error(UNKNOWN, f"parameter {p!r} is not an ir Param/ArrayParam")
                continue
            if p.name in params:
                self.error(STRUCTURE, f"parameter '{p.name}' appears twice")
            params.add(p.name)
        eg = fn.entry_globals
        if not isinstance(eg, tuple):
            self.error(STRUCTURE, "entry_globals is not a tuple")
            eg = ()
        for p in eg:
            if type(p) is ArrayParam:
                self.error(TYPE, f"entry global '{p.name}' is an array")
                continue
            if type(p) is not Param:
                self.error(UNKNOWN, f"entry global {p!r} is not an ir Param")
                continue
            if p.type not in ir.SCALARS:
                self.error(TYPE, f"entry global '{p.name}' has type {p.type}; only i64, f64 and "
                                 f"bool globals can be read at entry")
                continue
            if p.name in params or p.name in self.entry:
                self.error(STRUCTURE, f"entry global '{p.name}' collides with a parameter or "
                                      f"another entry global")
                continue
            self.entry[p.name] = p.type
            self.scalars[p.name] = p.type
        params |= set(self.entry)
        if not isinstance(fn.locals, dict):
            self.error(STRUCTURE, "locals is not a dict")
            return frozenset(params)
        for name, t in fn.locals.items():
            if name in self.entry:
                self.error(STRUCTURE, f"local '{name}' collides with an entry global")
            elif name in self.arrays:
                self.error(ARRAY_USE, f"local '{name}' shadows array parameter '{name}'")
            elif t in ir.ARRAYS:
                if name in self.scalars:
                    self.error(TYPE, f"local array '{name}' collides with a scalar parameter")
                else:
                    self.larrays[name] = t
            elif t not in _VALUE_TYPES:
                self.error(TYPE, f"local '{name}' has type {t}")
            elif name in self.scalars and self.scalars[name] is not t:
                self.error(TYPE, f"local '{name}' declared {t} but parameter is "
                                 f"{self.scalars[name]}")
            else:
                self.scalars[name] = t
        if fn.returns not in _RETURN_TYPES:
            self.error(TYPE, f"return type {fn.returns} is not a value type")
        for flag in ("pure", "may_deopt"):
            if not isinstance(getattr(fn, flag), bool):
                self.error(TYPE, f"`{flag}` is not a bool")
        return frozenset(params)

    # --- expressions --------------------------------------------------------------------------

    def slot(self, e: ir.Expr, want: Type, what: str) -> None:
        """`e` flows into a slot of type `want` (already checked as an expression)."""
        got = getattr(e, "type", None)
        if got is want:
            return
        if got is Type.OBJ and want in ir.SCALARS:
            self.error(OWNERSHIP, f"{what}: OBJ flows into a {want.value} slot without Unbox")
        elif want is Type.OBJ and got in ir.SCALARS:
            self.error(OWNERSHIP, f"{what}: {got.value} flows into an OBJ slot without Box")
        elif got in ir.ARRAYS:
            pass    # reported as array-use where the array was read
        else:
            self.error(TYPE, f"{what}: expected {want.value}, got "
                             f"{got.value if isinstance(got, Type) else got!r}")

    def expr(self, e: object, state: _State, cond: bool = False) -> ir.Expr:
        """Check `e`, returning it rebuilt (with proven bounds). `state` is the definitely
        assigned set at this point; `cond` says `e` is used only for its truth value (an If/While
        condition, or an And/Or operand in one)."""
        cls = type(e)
        if cls not in _EXPRS:
            self.error(UNKNOWN, f"expression node {cls.__name__} is not defined by ir")
            return e  # type: ignore[return-value]
        t = e.type
        if not isinstance(t, Type):
            self.error(UNKNOWN, f"{cls.__name__} has non-Type type {t!r}")
            return e
        if t is Type.OBJ and cls not in _OBJ_PRODUCERS:
            self.error(OWNERSHIP, f"{cls.__name__} cannot produce an OBJ (only Box, Global, GetAttr, CallObject, OBJ "
                                  f"BinOp, Tuple, OBJ locals and OBJ-returning Calls create "
                                  f"references)")
        if cls is CompareObj and not cond:
            self.error(CONDITION_ONLY, "CompareObj outside a condition: the rich comparison's "
                                       "result object would be kept, and it need not be a bool")
        method = getattr(self, "e_" + cls.__name__)
        if cls in (And, Or):
            return method(e, state, cond)
        return method(e, state)

    def e_Const(self, e: Const, state: _State) -> ir.Expr:
        v, t = e.value, e.type
        ok = ((t is Type.I64 and type(v) is int and _I64_MIN <= v <= _I64_MAX)
              or (t is Type.F64 and type(v) is float)
              or (t is Type.BOOL and type(v) is bool)
              or (t is Type.NONE and v is None))
        if not ok and t is not Type.OBJ:
            self.error(TYPE, f"constant {v!r} does not match type {t.value}")
        return e

    def e_Local(self, e: Local, state: _State) -> ir.Expr:
        name = e.name
        if name in self.arrays:
            self.error(ARRAY_USE, f"array parameter '{name}' is used as a value (only Index, "
                                  f"StoreIndex and Len may use it)")
            return e
        if name in self.larrays:
            self.error(ESCAPE, f"local array '{name}' is used as a value (only Index, StoreIndex, "
                               f"Len and CopyArray may use it)")
            return e
        declared = self.scalars.get(name)
        if declared is None:
            self.error(TYPE, f"local '{name}' is not declared")
            return e
        if declared is not e.type:
            self.error(TYPE, f"local '{name}' is {declared.value} but read as {e.type.value}")
        if state is not None and name not in state:
            self.error(DA, f"local '{name}' may be read before it is assigned")
        return e

    def interval(self, e: ir.Expr) -> tuple[int, int]:
        """The interval rules in the module docstring: [lo, hi] bounding every value `e` (an I64
        expression, already checked) can take when it completes without deopting."""
        cls = type(e)
        if getattr(e, "type", None) is not Type.I64:
            return _FULL
        if cls is Const:
            return (e.value, e.value) if type(e.value) is int else _FULL
        if cls is Local:
            for loop in reversed(self.loops):
                if loop.var == e.name:
                    return loop.interval
            return _FULL
        if cls is Len:
            return self.lens.get(e.array, (0, _LEN_MAX))
        if cls is BinOp and e.op in _PROVABLE_OPS:
            lo, hi = _exact(e.op, self.interval(e.left), self.interval(e.right))
            return (max(lo, _I64_MIN), min(hi, _I64_MAX))
        if cls is UnaryOp and e.op in (UnaryOpKind.NEG, UnaryOpKind.POS):
            lo, hi = self.interval(e.operand)
            lo, hi = (-hi, -lo) if e.op is UnaryOpKind.NEG else (lo, hi)
            return (max(lo, _I64_MIN), min(hi, _I64_MAX))
        return _FULL

    def check_proven(self, shape_ok: bool, interval: tuple[int, int], what: str) -> bool:
        """`proven=True` (already known to be a bool) is accepted only if re-proved. Returns
        whether the node counts as non-deopting."""
        if not shape_ok:
            self.error(PROVEN, f"`proven` is set on {what}, which has no i64 overflow to prove")
        elif not (_I64_MIN <= interval[0] and interval[1] <= _I64_MAX):
            self.error(PROVEN, f"`proven` is set on {what} but its result interval "
                               f"[{interval[0]}, {interval[1]}] is not inside i64")
        else:
            return True
        return False

    def e_BinOp(self, e: BinOp, state: _State) -> ir.Expr:
        if type(e.op) is not BinOpKind:
            self.error(UNKNOWN, f"BinOp op {e.op!r} is not a BinOpKind")
            return e
        left, right = self.expr(e.left, state), self.expr(e.right, state)
        lt, rt = getattr(left, "type", None), getattr(right, "type", None)
        proven = False
        if type(e.proven) is not bool:
            self.error(TYPE, f"BinOp `proven` {e.proven!r} is not a bool")
        elif e.proven:
            shape = (e.type is Type.I64 and lt is Type.I64 and rt is Type.I64
                     and e.op in _PROVABLE_OPS)
            exact = _exact(e.op, self.interval(left), self.interval(right)) if shape else _FULL
            proven = self.check_proven(shape, exact, f"{e.type.value} {e.op.value}")
        if lt not in (Type.I64, Type.F64, Type.OBJ):
            self.error(TYPE if lt is not Type.OBJ else OWNERSHIP,
                       f"BinOp {e.op.value} on {getattr(lt, 'value', lt)} operands")
        elif lt is not rt:
            self.slot(right, lt, f"right operand of {e.op.value}")
        else:
            want = Type.F64 if (lt is Type.I64 and e.op is BinOpKind.TRUEDIV) else lt
            if e.type is not want:
                self.error(TYPE, f"{lt.value} {e.op.value} {rt.value} is {want.value}, node says "
                                 f"{e.type.value}")
            if lt is Type.I64 and e.op in _I64_DEOPT_OPS and not proven:
                self.can_deopt = True
            if lt is Type.OBJ:
                self.effects.append(f"OBJ {e.op.value} (runs user methods)")
                self.opens.append(f"OBJ {e.op.value} (runs user methods)")
        return dataclasses.replace(e, left=left, right=right)

    def e_UnaryOp(self, e: UnaryOp, state: _State) -> ir.Expr:
        if type(e.op) is not UnaryOpKind:
            self.error(UNKNOWN, f"UnaryOp op {e.op!r} is not a UnaryOpKind")
            return e
        operand = self.expr(e.operand, state)
        ot = getattr(operand, "type", None)
        proven = False
        if type(e.proven) is not bool:
            self.error(TYPE, f"UnaryOp `proven` {e.proven!r} is not a bool")
        elif e.proven:
            shape = e.type is Type.I64 and ot is Type.I64 and e.op is UnaryOpKind.NEG
            lo, hi = self.interval(operand) if shape else _FULL
            proven = self.check_proven(shape, (-hi, -lo) if shape else _FULL,
                                       f"unary {e.op.value} of {getattr(ot, 'value', ot)}")
        if e.op is UnaryOpKind.NOT:
            self.slot(operand, Type.BOOL, "operand of not")
            if e.type is not Type.BOOL:
                self.error(TYPE, f"not gives bool, node says {e.type.value}")
        else:
            if ot is Type.OBJ:
                self.error(OWNERSHIP, f"unary {e.op.value} on OBJ has no IR form")
            elif ot not in (Type.I64, Type.F64):
                self.error(TYPE, f"unary {e.op.value} on {getattr(ot, 'value', ot)}")
            elif e.type is not ot:
                self.error(TYPE, f"unary {e.op.value} of {ot.value} is {ot.value}, node says "
                                 f"{e.type.value}")
            elif ot is Type.I64 and e.op is UnaryOpKind.NEG and not proven:
                self.can_deopt = True
        return dataclasses.replace(e, operand=operand)

    def e_Compare(self, e: Compare, state: _State) -> ir.Expr:
        if type(e.op) is not CompareKind:
            self.error(UNKNOWN, f"Compare op {e.op!r} is not a CompareKind")
            return e
        left, right = self.expr(e.left, state), self.expr(e.right, state)
        lt, rt = getattr(left, "type", None), getattr(right, "type", None)
        numeric = (Type.I64, Type.F64)
        if Type.OBJ in (lt, rt):
            self.error(OWNERSHIP, "Compare on an OBJ operand (rich comparison returns an object, "
                                  "not a bool)")
        elif not ((lt in numeric and rt in numeric) or (lt is Type.BOOL and rt is Type.BOOL)):
            self.error(TYPE, f"Compare of {getattr(lt, 'value', lt)} and "
                             f"{getattr(rt, 'value', rt)}")
        if e.type is not Type.BOOL:
            self.error(TYPE, f"Compare gives bool, node says {e.type.value}")
        return dataclasses.replace(e, left=left, right=right)

    def _bool_pair(self, e: And | Or, state: _State, cond: bool = False) -> ir.Expr:
        name = type(e).__name__
        left, right = self.expr(e.left, state, cond), self.expr(e.right, state, cond)
        self.slot(left, Type.BOOL, f"left operand of {name}")
        self.slot(right, Type.BOOL, f"right operand of {name}")
        if e.type is not Type.BOOL:
            self.error(TYPE, f"{name} gives bool, node says {e.type.value}")
        return dataclasses.replace(e, left=left, right=right)

    e_And = _bool_pair
    e_Or = _bool_pair

    def e_ToFloat(self, e: ToFloat, state: _State) -> ir.Expr:
        operand = self.expr(e.operand, state)
        self.slot(operand, Type.I64, "operand of ToFloat")
        if e.type is not Type.F64:
            self.error(TYPE, f"ToFloat gives f64, node says {e.type.value}")
        return dataclasses.replace(e, operand=operand)

    def e_Box(self, e: Box, state: _State) -> ir.Expr:
        operand = self.expr(e.operand, state)
        ot = getattr(operand, "type", None)
        if ot is Type.OBJ:
            self.error(OWNERSHIP, "Box of an OBJ (it is already an object)")
        elif ot not in ir.SCALARS and ot not in ir.ARRAYS:
            self.error(TYPE, f"Box of {getattr(ot, 'value', ot)}")
        if e.type is not Type.OBJ:
            self.error(TYPE, f"Box gives obj, node says {e.type.value}")
        return dataclasses.replace(e, operand=operand)

    def e_Unbox(self, e: Unbox, state: _State) -> ir.Expr:
        operand = self.expr(e.operand, state)
        ot = getattr(operand, "type", None)
        if ot in ir.SCALARS:
            self.error(OWNERSHIP, f"Unbox of a {ot.value} (only an OBJ can be unboxed)")
        elif ot is not Type.OBJ and ot not in ir.ARRAYS:
            self.error(TYPE, f"Unbox of {getattr(ot, 'value', ot)}")
        if e.type not in ir.SCALARS:
            self.error(TYPE, f"Unbox gives a scalar, node says {e.type.value}")
        elif e.type is Type.I64:
            self.can_deopt = True
        return dataclasses.replace(e, operand=operand)

    def e_MathCall(self, e: MathCall, state: _State) -> ir.Expr:
        if type(e.func) is not MathFunc:
            self.error(UNKNOWN, f"MathCall func {e.func!r} is not a MathFunc")
            return e
        args = self.args(e.args, state, f"math.{e.func.value}")
        arity = _MATH_ARITY.get(e.func, 1)
        if len(args) != arity:
            self.error(TYPE, f"math.{e.func.value} takes {arity} argument(s), got {len(args)}")
        for k, a in enumerate(args):
            self.slot(a, Type.F64, f"argument {k + 1} of math.{e.func.value}")
        if e.type is not Type.F64:
            self.error(TYPE, f"math.{e.func.value} gives f64, node says {e.type.value}")
        return dataclasses.replace(e, args=args)

    def args(self, args: object, state: _State, what: str) -> tuple[ir.Expr, ...]:
        if not isinstance(args, tuple):
            self.error(STRUCTURE, f"arguments of {what} are not a tuple")
            return ()
        return tuple(self.expr(a, state) for a in args)

    def e_Call(self, e: Call, state: _State) -> ir.Expr:
        args = self.args(e.args, state, f"call of '{e.function}'")
        callee = self.sigs.get(e.function)
        if callee is None:
            self.error(UNRESOLVED, f"call of '{e.function}', which is not a compiled function of "
                                   f"this module")
            return dataclasses.replace(e, args=args)
        self.callees.add(e.function)
        if len(args) != len(callee.params):
            self.error(TYPE, f"'{e.function}' takes {len(callee.params)} argument(s), got "
                             f"{len(args)}")
        for k, (a, p) in enumerate(zip(args, callee.params)):
            if type(p) is ArrayParam:
                self.error(ARRAY_USE, f"'{e.function}' takes array parameter '{p.name}'; an array "
                                      f"cannot be passed by Call (the entry guard and write-back "
                                      f"belong to the Python-level call)")
            else:
                self.slot(a, p.type, f"argument {k + 1} of '{e.function}'")
        if e.type is not callee.returns:
            self.error(TYPE, f"'{e.function}' returns {callee.returns.value}, node says "
                             f"{e.type.value}")
        redo = False
        if type(e.redo) is not bool:
            self.error(TYPE, f"Call `redo` {e.redo!r} is not a bool")
        elif e.redo:
            if self.fn.pure is True:
                self.error(REDO, f"redo call of '{e.function}' in a pure function (a pure caller "
                                 f"propagates the deopt instead)")
            elif callee.pure is not True or callee.may_deopt is not True:
                self.error(REDO, f"redo call of '{e.function}', which is not a pure may_deopt "
                                 f"function")
            elif callee.returns not in (Type.F64, Type.BOOL, Type.NONE):
                self.error(REDO, f"redo call of '{e.function}' returning "
                                 f"{getattr(callee.returns, 'value', callee.returns)}: only f64, "
                                 f"bool and none results are exactly reproducible")
            else:
                redo = True
        if callee.may_deopt is True and not redo:
            self.can_deopt = True
        if not redo and not self.covers(callee):
            self.can_deopt = True
        if callee.pure is not True:
            self.effects.append(f"calls impure '{e.function}'")
        return dataclasses.replace(e, args=args)

    def covers(self, callee: Function) -> bool:
        """Every entry global of `callee` is also an entry global of this function with the same
        type: the callee's entry guard cannot fail mid-caller (this function, being closed,
        cannot see the global rebound)."""
        eg = callee.entry_globals
        if not isinstance(eg, tuple):
            return False
        return all(type(p) is Param and self.entry.get(p.name) is p.type for p in eg)

    def obj_operand(self, e: object, state: _State, what: str) -> ir.Expr:
        x = self.expr(e, state)
        self.slot(x, Type.OBJ, what)
        return x

    def result(self, e: ir.Expr, want: Type) -> None:
        if e.type is not want:
            self.error(TYPE, f"{type(e).__name__} gives {want.value}, node says {e.type.value}")

    def e_Global(self, e: Global, state: _State) -> ir.Expr:
        if not isinstance(e.name, str):
            self.error(TYPE, f"Global name {e.name!r} is not a str")
        self.result(e, Type.OBJ)
        return e        # a read alone has no effect (ir.Global)

    def e_GetAttr(self, e: GetAttr, state: _State) -> ir.Expr:
        obj = self.obj_operand(e.obj, state, f"object of .{e.name}")
        if not isinstance(e.name, str):
            self.error(TYPE, f"GetAttr name {e.name!r} is not a str")
        self.result(e, Type.OBJ)
        self.effects.append(f"GetAttr .{e.name} (a property may have effects)")
        self.opens.append(f"GetAttr .{e.name}")
        return dataclasses.replace(e, obj=obj)

    def e_CallObject(self, e: CallObject, state: _State) -> ir.Expr:
        callee = self.obj_operand(e.callee, state, "callee of CallObject")
        args = self.args(e.args, state, "CallObject")
        for k, a in enumerate(args):
            self.slot(a, Type.OBJ, f"argument {k + 1} of CallObject")
        kw = e.kwnames
        if not (isinstance(kw, tuple) and all(isinstance(n, str) for n in kw)):
            self.error(TYPE, f"CallObject kwnames {kw!r} is not a tuple of str")
        elif len(kw) > len(args) or len(set(kw)) != len(kw):
            self.error(TYPE, f"CallObject has {len(kw)} keyword name(s) {kw!r} for {len(args)} "
                             f"argument(s) (they must name distinct trailing arguments)")
        self.result(e, Type.OBJ)
        self.effects.append("CallObject")
        self.opens.append("CallObject")
        return dataclasses.replace(e, callee=callee, args=args)

    def e_Truth(self, e: Truth, state: _State) -> ir.Expr:
        operand = self.obj_operand(e.operand, state, "operand of Truth")
        self.result(e, Type.BOOL)
        self.effects.append("Truth (runs __bool__/__len__)")
        self.opens.append("Truth")
        return dataclasses.replace(e, operand=operand)

    def e_CompareObj(self, e: CompareObj, state: _State) -> ir.Expr:
        if type(e.op) is not CompareKind:
            self.error(UNKNOWN, f"CompareObj op {e.op!r} is not a CompareKind")
            return e
        left, right = self.expr(e.left, state), self.expr(e.right, state)
        types = (getattr(left, "type", None), getattr(right, "type", None))
        if Type.OBJ not in types:
            self.error(TYPE, "CompareObj without an OBJ operand (use Compare)")
        for t in types:
            if t is not Type.OBJ and t not in ir.SCALARS and t not in ir.ARRAYS:
                self.error(TYPE, f"CompareObj operand of type {getattr(t, 'value', t)}")
        self.result(e, Type.BOOL)
        self.effects.append("CompareObj (runs rich comparison)")
        self.opens.append("CompareObj")
        return dataclasses.replace(e, left=left, right=right)

    def e_ObjToFloat(self, e: ObjToFloat, state: _State) -> ir.Expr:
        operand = self.obj_operand(e.operand, state, "operand of ObjToFloat")
        self.result(e, Type.F64)
        self.effects.append("ObjToFloat (runs __float__/__index__)")
        self.opens.append("ObjToFloat")
        return dataclasses.replace(e, operand=operand)

    def array(self, name: str, node: str, state: _State = None) -> Type | None:
        """The array type of `name` (an array parameter or a local array); a local array must be
        definitely assigned."""
        t = self.arrays.get(name)
        if t is not None:
            return t
        t = self.larrays.get(name)
        if t is None:
            self.error(ARRAY_USE, f"{node} of '{name}', which is not an array")
        elif state is not None and name not in state:
            self.error(DA, f"local array '{name}' may be used ({node}) before it is assigned")
        return t

    def e_Len(self, e: Len, state: _State) -> ir.Expr:
        self.array(e.array, "len", state)
        if e.type is not Type.I64:
            self.error(TYPE, f"len gives i64, node says {e.type.value}")
        return e

    def index(self, array: str, index: object, state: _State, node: str
              ) -> tuple[ir.Expr, Type | None, bool]:
        at = self.array(array, node, state)
        idx = self.expr(index, state)
        self.slot(idx, Type.I64, f"index of {node} '{array}'")
        return idx, (_ELEMENT[at] if at else None), self.in_bounds(array, idx)

    def e_Index(self, e: Index, state: _State) -> ir.Expr:
        idx, element, proven = self.index(e.array, e.index, state, "index")
        if element is not None and e.type is not element and e.type is not Type.OBJ:
            self.error(TYPE, f"element of '{e.array}' is {element.value}, node says "
                             f"{e.type.value}")
        return dataclasses.replace(e, index=idx, proven=proven)

    def in_bounds(self, array: str, idx: ir.Expr) -> bool:
        """0 <= idx < len(array): idx is the var of an enclosing ForRange over
        range(c >= 0, len(array), step > 0) (for a local array, one whose body never assigns it), or
        -- for a local array of known length -- idx has an interval inside [0, minimum length - 1].
        The var is never assigned in the body (checked), the stop is evaluated once, and no IR node
        changes an array's length."""
        if array in self.lens and getattr(idx, "type", None) is Type.I64:
            lo, hi = self.interval(idx)
            if 0 <= lo and hi < self.lens[array][0]:
                return True
        if type(idx) is not Local or not (array in self.arrays or array in self.larrays):
            return False
        for loop in reversed(self.loops):
            if loop.var == idx.name:
                return loop.bounds_array == array
        return False

    # --- tuples and local arrays --------------------------------------------------------------

    def e_Tuple(self, e: Tuple, state: _State) -> ir.Expr:
        if not isinstance(e.elements, tuple):
            self.error(STRUCTURE, "Tuple elements are not a tuple")
            return e
        elements = tuple(self.obj_operand(x, state, f"element {k + 1} of Tuple")
                         for k, x in enumerate(e.elements))
        self.result(e, Type.OBJ)
        return dataclasses.replace(e, elements=elements)

    def e_NewArray(self, e: NewArray, state: _State) -> ir.Expr:
        return self.stray(e, state)

    def e_CopyArray(self, e: CopyArray, state: _State) -> ir.Expr:
        return self.stray(e, state)

    def stray(self, e: NewArray | CopyArray, state: _State) -> ir.Expr:
        self.error(ESCAPE, f"{type(e).__name__} is only legal as the value of an Assign to a local "
                           f"array")
        return e

    def allocation(self, e: NewArray | CopyArray, target: str, state: _State) -> ir.Expr:
        """`e` is the value of `Assign(target, e)`. Checks the operands, and that the node's type
        is an array type equal to the target's declared one."""
        name = type(e).__name__
        want = self.larrays.get(target)
        if not isinstance(e.type, Type) or e.type not in ir.ARRAYS:
            self.error(TYPE, f"{name} gives an array, node says "
                             f"{getattr(e.type, 'value', e.type)}")
        elif want is not None and e.type is not want:
            self.error(TYPE, f"{name} gives {e.type.value} but local array '{target}' is "
                             f"{want.value}")
        element = _ELEMENT.get(e.type)
        if type(e) is CopyArray:
            src = self.arrays.get(e.src) or self.larrays.get(e.src)
            if not isinstance(e.src, str) or src is None:
                self.error(ARRAY_USE, f"CopyArray of '{e.src}', which is not an array")
            else:
                self.array(e.src, "copy", state)
                if src is not e.type:
                    self.error(TYPE, f"CopyArray of {src.value} '{e.src}' gives {e.type.value}")
            return e
        length = self.expr(e.length, state)
        self.slot(length, Type.I64, "length of NewArray")
        fill = e.fill
        if type(e.iota) is not bool:
            self.error(TYPE, f"NewArray `iota` {e.iota!r} is not a bool")
        elif e.iota:
            if e.type is not Type.I64_ARRAY:
                self.error(TYPE, "NewArray iota is only for i64 arrays")
            if fill is not None:
                self.error(TYPE, "NewArray iota takes no fill")
        elif fill is None:
            self.error(TYPE, "NewArray needs a fill unless it is iota")
        if fill is not None:
            fill = self.expr(fill, state)
            if element is not None:
                self.slot(fill, element, "fill of NewArray")
        return dataclasses.replace(e, length=length, fill=fill)

    def length_of(self, e: NewArray | CopyArray) -> tuple[int, int]:
        """The interval of the length of the array `e` creates (the operands are checked)."""
        if type(e) is CopyArray:
            return self.lens.get(e.src, (0, _LEN_MAX))
        if getattr(e.length, "type", None) is not Type.I64:
            return (0, _LEN_MAX)
        lo, hi = self.interval(e.length)
        return (min(max(lo, 0), _LEN_MAX), min(max(hi, 0), _LEN_MAX))

    # --- statements ---------------------------------------------------------------------------

    def block(self, body: object, state: _State) -> tuple[tuple[ir.Stmt, ...], _State]:
        if not isinstance(body, tuple):
            self.error(STRUCTURE, "a statement block is not a tuple")
            return (), state
        out = []
        for s in body:
            s2, state = self.stmt(s, state)
            out.append(s2)
        return tuple(out), state

    def stmt(self, s: object, state: _State) -> tuple[ir.Stmt, _State]:
        cls = type(s)
        if cls not in _STMTS:
            self.error(UNKNOWN, f"statement node {cls.__name__} is not defined by ir")
            return s, state  # type: ignore[return-value]
        return getattr(self, "s_" + cls.__name__)(s, state)

    def s_Assign(self, s: Assign, state: _State):
        target = s.target
        alloc = type(s.value) in (NewArray, CopyArray)
        if alloc:
            if target in self.larrays:
                value = self.allocation(s.value, target, state)
                if self.assigns.get(target) == 1:
                    self.lens[target] = self.length_of(value)
            else:
                value = self.allocation(s.value, target, state)
                self.error(ESCAPE, f"{type(s.value).__name__} is assigned to '{target}', which is "
                                   f"not a local array")
        else:
            value = self.expr(s.value, state)
            if target in self.larrays:
                self.error(TYPE, f"local array '{target}' may only be assigned NewArray or "
                                 f"CopyArray")
        if target in self.larrays:
            pass
        elif target in self.arrays:
            self.error(ARRAY_USE, f"assignment to array parameter '{target}'")
        elif target not in self.scalars:
            self.error(TYPE, f"assignment to undeclared local '{target}'")
        elif not alloc:
            self.slot(value, self.scalars[target], f"assignment to '{target}'")
            if getattr(value, "type", None) is Type.NONE:
                self.error(TYPE, f"None assigned to '{target}'")
        if target in self.entry:
            self.error(STRUCTURE, f"entry global '{target}' is assigned in the body")
        for loop in self.loops:
            if loop.var == target:
                self.error(STRUCTURE, f"loop variable '{target}' is assigned in its loop body")
        return dataclasses.replace(s, value=value), (None if state is None else state | {target})

    def s_StoreIndex(self, s: StoreIndex, state: _State):
        idx, element, proven = self.index(s.array, s.index, state, "store")
        value = self.expr(s.value, state)
        if element is not None:
            self.slot(value, element, f"value stored into '{s.array}'")
        if s.array not in self.larrays:     # a local array is the function's own: not an effect
            self.stored.add(s.array)
            self.effects.append(f"stores into '{s.array}'")
        return dataclasses.replace(s, index=idx, value=value, proven=proven), state

    def s_ExprStmt(self, s: ExprStmt, state: _State):
        return dataclasses.replace(s, value=self.expr(s.value, state)), state

    def cond(self, c: object, state: _State, what: str) -> ir.Expr:
        c2 = self.expr(c, state, cond=True)
        self.slot(c2, Type.BOOL, f"condition of {what}")
        return c2

    def s_If(self, s: If, state: _State):
        cond = self.cond(s.cond, state, "if")
        then, st = self.block(s.then, state)
        orelse, se = self.block(s.orelse, state)
        return dataclasses.replace(s, cond=cond, then=then, orelse=orelse), _meet(st, se)

    def s_While(self, s: While, state: _State):
        cond = self.cond(s.cond, state, "while")
        loop = _Loop(None, None)
        self.loops.append(loop)
        # Assignments only add names, so the loop-head state is the entry state (the meet of the
        # entry, end-of-body and continue states equals the entry state).
        body, _ = self.block(s.body, state)
        self.loops.pop()
        forever = type(s.cond) is Const and s.cond.type is Type.BOOL and s.cond.value is True
        after: _State = None if forever else state
        for br in loop.breaks:
            after = _meet(after, br)
        return dataclasses.replace(s, cond=cond, body=body), after

    def s_ForRange(self, s: ForRange, state: _State):
        start = self.expr(s.start, state)
        stop = self.expr(s.stop, state)
        self.slot(start, Type.I64, "range start")
        self.slot(stop, Type.I64, "range stop")
        step = s.step
        step_ok = (type(step) is Const and step.type is Type.I64 and type(step.value) is int
                   and step.value != 0 and _I64_MIN <= step.value <= _I64_MAX)
        if not step_ok:
            self.error(STRUCTURE, f"range step must be a non-zero i64 Const, got {step!r}")
        var = s.var
        if var in self.arrays:
            self.error(ARRAY_USE, f"loop variable '{var}' is an array parameter")
        elif self.scalars.get(var) is not Type.I64:
            self.error(TYPE, f"loop variable '{var}' must be a declared i64 local")
        if var in self.entry:
            self.error(STRUCTURE, f"entry global '{var}' is assigned by the loop")
        for outer in self.loops:
            if outer.var == var:
                self.error(STRUCTURE, f"loop variable '{var}' is assigned in its loop body "
                                      f"(by an inner loop)")
        bounds = None
        if (step_ok and step.value > 0 and type(start) is Const and start.type is Type.I64
                and type(start.value) is int and start.value >= 0 and type(stop) is Len
                and (stop.array in self.arrays
                     or (stop.array in self.larrays
                         and stop.array not in _assigned(s.body)))):
            bounds = stop.array
        loop = _Loop(var, bounds)
        if (step_ok and getattr(start, "type", None) is Type.I64
                and getattr(stop, "type", None) is Type.I64):
            (slo, shi), (elo, ehi) = self.interval(start), self.interval(stop)
            if step.value > 0:
                lo = slo
                hi = max(lo, ehi - 1)
            else:
                hi = shi
                lo = min(hi, elo + 1)
            loop.interval = (max(lo, _I64_MIN), min(hi, _I64_MAX))
        self.loops.append(loop)
        body, _ = self.block(s.body, None if state is None else state | {var})
        self.loops.pop()
        # Zero iterations leave `var` (and everything the body assigns) as it was: the state after
        # the loop is the entry state, met with every break state (each a superset of it).
        after: _State = state
        for br in loop.breaks:
            after = _meet(after, br)
        return dataclasses.replace(s, start=start, stop=stop, body=body), after

    def s_Return(self, s: Return, state: _State):
        returns = self.fn.returns
        if s.value is None:
            if returns is not Type.NONE:
                self.error(TYPE, f"return without a value in a function returning "
                                 f"{getattr(returns, 'value', returns)}")
            return s, None
        value = self.expr(s.value, state)
        if isinstance(returns, Type):
            self.slot(value, returns, "return value")
        return dataclasses.replace(s, value=value), None

    def _jump(self, s: Break | Continue, state: _State):
        if not self.loops:
            self.error(STRUCTURE, f"{type(s).__name__.lower()} outside a loop")
        elif type(s) is Break:
            self.loops[-1].breaks.append(state)
        return s, None

    s_Break = _jump
    s_Continue = _jump

    # --- the function -------------------------------------------------------------------------

    def run(self) -> Function:
        fn = self.fn
        params = self.declarations()
        if self.larrays:
            counts: dict[str, int] = {}
            _assigned(fn.body, counts)
            self.assigns = {n: counts.get(n, 0) for n in self.larrays}
        body, end = self.block(fn.body, params)
        if end is not None and fn.returns is not Type.NONE:
            self.error(MISSING_RETURN, f"the end of '{fn.name}' is reachable but it returns "
                                       f"{getattr(fn.returns, 'value', fn.returns)}")
        # deopt placement and flags
        if self.can_deopt and fn.pure is not True:
            self.error(DEOPT, "a node that can deopt (checked i64 arithmetic, i64 negation, Unbox to "
                              "i64, or a call of a may_deopt function) is in an impure function; a redo "
                              "would repeat its effects")
        if fn.may_deopt is not self.can_deopt:
            if fn.pure is True or fn.may_deopt is True:
                self.error(FLAG_DEOPT, f"may_deopt is {fn.may_deopt} but the body "
                                       f"{'can' if self.can_deopt else 'cannot'} deopt")
        if fn.pure is True and self.effects:
            self.error(FLAG_PURE, "pure is set but the body " + "; ".join(sorted(set(self.effects))))
        for p in fn.params:
            if type(p) is ArrayParam and p.name in self.arrays and p.stored is not (p.name in
                                                                                 self.stored):
                self.error(ARRAY_STORED, f"array '{p.name}' has stored={p.stored} but the body "
                                         f"{'stores' if p.name in self.stored else 'never stores'}"
                                         f" into it")
        return dataclasses.replace(fn, body=body)


def _assigned(body: object, counts: dict[str, int] | None = None) -> dict[str, int]:
    """Every name assigned by an Assign statement anywhere in `body` (nested blocks included), with
    the number of Assign statements. Tolerates a malformed body (the checker reports it)."""
    counts = {} if counts is None else counts
    if isinstance(body, tuple):
        for st in body:
            cls = type(st)
            if cls is Assign:
                counts[st.target] = counts.get(st.target, 0) + 1
            elif cls is If:
                _assigned(st.then, counts)
                _assigned(st.orelse, counts)
            elif cls in (While, ForRange):
                _assigned(st.body, counts)
    return counts


def _exact(op: BinOpKind, a: tuple[int, int], b: tuple[int, int]) -> tuple[int, int]:
    """The exact (unbounded) result interval of ADD/SUB/MUL on operand intervals."""
    if op is BinOpKind.ADD:
        return a[0] + b[0], a[1] + b[1]
    if op is BinOpKind.SUB:
        return a[0] - b[1], a[1] - b[0]
    products = (a[0] * b[0], a[0] * b[1], a[1] * b[0], a[1] * b[1])
    return min(products), max(products)


def _cycles(graph: dict[str, set[str]]) -> set[str]:
    """Names on a cycle of `graph` (Tarjan's strongly connected components)."""
    index: dict[str, int] = {}
    low: dict[str, int] = {}
    stack: list[str] = []
    on: set[str] = set()
    result: set[str] = set()
    counter = [0]

    def visit(v: str) -> None:
        index[v] = low[v] = counter[0]
        counter[0] += 1
        stack.append(v)
        on.add(v)
        for w in graph.get(v, ()):
            if w not in graph:
                continue
            if w not in index:
                visit(w)
                low[v] = min(low[v], low[w])
            elif w in on:
                low[v] = min(low[v], index[w])
        if low[v] == index[v]:
            comp = []
            while True:
                w = stack.pop()
                on.discard(w)
                comp.append(w)
                if w == v:
                    break
            if len(comp) > 1 or v in graph.get(v, ()):
                result.update(comp)

    for v in graph:
        if v not in index:
            visit(v)
    return result


def verify(module: Module) -> tuple[Module, list[Diagnostic]]:
    """Prove each function of `module`; return the module of proved functions (with proven bounds
    marked) and every diagnostic. Rejected functions move to `skipped` with the reason."""
    diags: list[Diagnostic] = []
    reasons: dict[str, str] = {}
    functions = module.functions if isinstance(module.functions, tuple) else ()

    def reject(fn_name: str, line: int, rule: str, message: str) -> None:
        d = Diagnostic(fn_name, rule, message, line)
        if d not in diags:
            diags.append(d)
        reasons.setdefault(fn_name, f"{rule}: {message}")

    named: dict[str, list[Function]] = {}
    for g in functions:
        if type(g) is not Function:
            reject(getattr(g, "name", repr(g)), getattr(g, "source_line", 0), UNKNOWN,
                   f"{type(g).__name__} is not an ir Function")
            continue
        named.setdefault(g.name, []).append(g)
    for name, gs in named.items():
        if len(gs) > 1:
            for g in gs:
                reject(name, g.source_line, STRUCTURE, f"function '{name}' is defined "
                                                       f"{len(gs)} times")
    sigs = {name: gs[0] for name, gs in named.items() if len(gs) == 1}

    checked: dict[str, Function] = {}
    callees: dict[str, set[str]] = {}
    open_why: dict[str, str] = {}          # a function that runs user code -> why
    for name, g in sigs.items():
        c = _Checker(g, sigs)
        rebuilt = c.run()
        for d in c.diags:
            reject(d.function, d.source_line, d.rule, d.message)
        checked[name] = rebuilt
        callees[name] = c.callees
        if c.opens:
            open_why[name] = c.opens[0]

    # Closedness (entry_globals): a fixpoint over the call graph.
    changed = True
    while changed:
        changed = False
        for name, cs in callees.items():
            if name not in open_why:
                bad = sorted(c for c in cs if c in open_why)
                if bad:
                    open_why[name] = f"calls '{bad[0]}', which runs user code ({open_why[bad[0]]})"
                    changed = True
    for name, g in sigs.items():
        if name in open_why and isinstance(g.entry_globals, tuple) and g.entry_globals:
            reject(name, g.source_line, ENTRY_OPEN,
                   f"has entry globals but is not closed: {open_why[name]}")

    for name in sorted(_cycles(callees)):
        reject(name, sigs[name].source_line, RECURSION,
               f"'{name}' is on a cycle of calls; compiled recursion has no depth limit")

    # A call into a function that is not compiled cannot be a C call: propagate to a fixpoint.
    changed = True
    while changed:
        changed = False
        for name, cs in callees.items():
            if name in reasons:
                continue
            bad = sorted(c for c in cs if c in reasons)
            if bad:
                reject(name, sigs[name].source_line, UNVERIFIED,
                       f"calls '{bad[0]}', which is not compiled ({reasons[bad[0]]})")
                changed = True

    kept = tuple(checked[g.name] for g in functions
                 if type(g) is Function and g.name in checked and g.name not in reasons)
    skipped = dict(module.skipped)
    for name, reason in reasons.items():
        skipped.setdefault(name, reason)
    return Module(module.name, kept, skipped), diags
