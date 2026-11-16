"""Which ints are RAW and which are TAGGED (SPEC N-17, #462).

Provenance. Design decision D1 (a) (leader, 2026-11-16, not owner-confirmed): an int that is not proved inside 64
bits is a small-int / big-int value of its own (`runtime/tp_aot_int.h`). The representation below (RAW int64 and
TAGGED `tpa_int`, decided per program by a fixpoint over all functions) was approved by the leader on 2026-11-16, not
owner-confirmed. The inference rule written out here, and the taint rule in particular, are agent proposals, not
owner-confirmed.

Two representations of an IR int (a value of type `Type.I64`; the IR calls every int I64 because the front end and
the verifier prove it fits, the AOT back end now reads that name as "an int the front end typed as an int"):

  RAW     an int known to fit int64. Stored as `int64_t` and computed as before this step (a proved operation is plain
          machine arithmetic).
  TAGGED  an int that may be any integer. Stored as `tpa_int` (an owned reference to a small int or a big integer).

The inference (`infer`) is one fixpoint over every function of the module, starting with everything RAW and raising a
name, a parameter, a return or a tuple element to TAGGED when something TAGGED flows into it (a representation only
goes up, so the loop ends). A name is a local, a parameter, a loop variable or a target of an unpacking; it has ONE
representation for the whole function. An expression is RAW only when it is one of:

  * an int constant (the source-level checker already refused one outside int64);
  * `len(...)` (a list length), `len(sys.argv)`;
  * the loop variable of a `range` loop whose start and stop are RAW (the step is a constant);
  * an element read from a `list[int]` (the elements of a list stay int64 in this step);
  * a name whose every assignment is RAW; a parameter whose every call site passes a RAW value, whose body assigns
    only RAW values, and (for an export of a library) whatever arrives from outside, which is an int64;
  * a call whose callee returns RAW (every `return` of the callee is RAW);
  * a conditional expression whose two branches are both RAW;
  * `+ - *` and unary minus that the IR marks `proven` AND whose operands are all RAW AND whose proof is not tainted
    (below): the verifier's proof reads the intervals of its operands, and an interval is true of a value only when
    that value is RAW, so a proved flag alone is not enough;
  * `//` by a constant other than -1 (and `//` the IR marks proven) of a RAW operand, and `%` of two RAW operands
    (the result lies between zero and the divisor); a `//` by -1 or by a variable is TAGGED (INT64_MIN // -1 is 2**63).

Everything else is TAGGED: the result of an operation that is not proved, of any operation with a TAGGED operand,
`int(sys.argv[i])`, and every name, parameter, return and tuple element such a value flows into. A name assigned
both RAW and TAGGED values is TAGGED (a RAW value is converted with `tpa_int_from_i64` where it is stored).

The taint rule (why a proved flag is not enough). The front end and the verifier narrow the interval of a local
under a guard: in `if a < b:` the interval of `a` is cut by the interval of `b`. Where `b` is TAGGED its interval is
what the proof machinery assumed (the clamp to int64 of an operation that "would have deopted"), and that is not
true of the big integer `b` really holds. So `a < b` with a TAGGED `b` (or one derived from a tainted name) TAINTS `a`:
an operation that reads `a` is not trusted by its proved flag, and is TAGGED unless it can be RAW without a proof. A
tainted name taints every name assigned from it. Example the rule exists for: `lo = items[0]` (any int64),
`hi = lo * lo` (TAGGED), `if lo < hi: lo = lo + 1`: the verifier proves `lo + 1` from `lo < hi` and `hi <= INT64_MAX`,
which is false for the big `hi`, and with `lo == INT64_MAX` a RAW addition would wrap. The same rule decides whether a
proved index (`Index.proven`, `StoreIndex.proven`) may skip its bounds check (`Representation.trusted`).

What this cannot find: it treats every name as one representation for the whole function, so a counter that is RAW
for its first thousand iterations is TAGGED from the start (speed, never exactness); and a taint is function-wide, not
scoped to the branch that holds the guard.

Strict mode (`infer(..., strict=True)`, `--strict-int`): nothing is ever TAGGED here. The back end then keeps what it
did before this step: an operation that is not proved is the finding `aot/int-unproven`, so a strict build refuses
every program that needs a TAGGED value (design decision D1 (a), not owner-confirmed).
"""
from __future__ import annotations

import dataclasses
from dataclasses import dataclass, field

from typedpython import ir
from typedpython.ir import Type

RAW = "raw"
TAGGED = "tagged"


def join(a: str, b: str) -> str:
    return TAGGED if TAGGED in (a, b) else RAW


def children(node):
    """Every IR expression and statement directly inside `node` (fields that are nodes or tuples of nodes)."""
    for fl in dataclasses.fields(node):
        v = getattr(node, fl.name)
        for x in (v if isinstance(v, tuple) else (v,)):
            if isinstance(x, (ir.Expr, ir.Stmt)):
                yield x


def walk(node):
    """`node` and everything under it, pre-order."""
    yield node
    for c in children(node):
        yield from walk(c)


@dataclass
class FunctionReps:
    names: dict[str, str] = field(default_factory=dict)          # I64 name (parameter, local, loop variable) -> rep
    returns: str = RAW                                            # the rep of an int return
    tuple_elements: list[str] = field(default_factory=list)       # the rep of each element of a returned tuple
    tainted: set[str] = field(default_factory=set)


class Representation:
    """The result of `infer`: the representation of every int of every function."""

    def __init__(self, module: ir.Module, strict: bool = False) -> None:
        self.module = module
        self.strict = strict
        self.fns = {f.name: f for f in module.functions}
        self.reps = {f.name: FunctionReps() for f in module.functions}
        if not strict:
            self.run()

    # -- queries

    def name(self, fn: str, name: str) -> str:
        return self.reps[fn].names.get(name, RAW)

    def returns(self, fn: str) -> str:
        return self.reps[fn].returns

    def element(self, fn: str, i: int) -> str:
        r = self.reps[fn].tuple_elements
        return r[i] if i < len(r) else RAW

    def tainted(self, fn: str) -> set[str]:
        return self.reps[fn].tainted

    def of(self, fn: str, e: ir.Expr) -> str:
        """The representation of the expression `e` of function `fn` (RAW for anything that is not an int)."""
        if self.strict or getattr(e, "type", None) is not Type.I64:
            return RAW
        return self._of(self.reps[fn], e)

    def uses_tainted(self, fn: str, e: ir.Expr) -> bool:
        return self._uses_tainted(self.reps[fn], e)

    def trusted(self, fn: str, e: ir.Expr) -> bool:
        """`e` (a proved operation or index) may rely on its proved flag: no operand reads a tainted name."""
        if self.strict:
            return True
        return not self._uses_tainted(self.reps[fn], e)

    # -- the rule

    def _of(self, r: FunctionReps, e: ir.Expr) -> str:
        if getattr(e, "type", None) is not Type.I64:
            return RAW
        if isinstance(e, ir.Const):
            return RAW
        if isinstance(e, ir.Local):
            return r.names.get(e.name, RAW)
        if isinstance(e, (ir.Len, ir.ArgvLen, ir.Index)):
            return RAW
        if isinstance(e, ir.ArgvInt):
            return TAGGED
        if isinstance(e, ir.Call):
            callee = self.reps.get(e.function)
            return callee.returns if callee is not None else TAGGED
        if isinstance(e, ir.Cond):
            return join(self._of(r, e.then), self._of(r, e.orelse))
        if isinstance(e, ir.UnaryOp):
            if e.op is ir.UnaryOpKind.POS:
                return self._of(r, e.operand)
            if e.op is ir.UnaryOpKind.NEG:
                ok = e.proven and self._of(r, e.operand) == RAW and not self._uses_tainted(r, e.operand)
                return RAW if ok else TAGGED
            return TAGGED
        if isinstance(e, ir.BinOp):
            a, b = self._of(r, e.left), self._of(r, e.right)
            if a == TAGGED or b == TAGGED:
                return TAGGED
            k = e.op
            if k in (ir.BinOpKind.ADD, ir.BinOpKind.SUB, ir.BinOpKind.MUL):
                tainted = self._uses_tainted(r, e.left) or self._uses_tainted(r, e.right)
                return RAW if e.proven and not tainted else TAGGED
            if k is ir.BinOpKind.MOD:
                return RAW
            if k is ir.BinOpKind.FLOORDIV:
                if isinstance(e.right, ir.Const) and e.right.value != -1:
                    return RAW
                tainted = self._uses_tainted(r, e.left) or self._uses_tainted(r, e.right)
                return RAW if e.proven and not tainted else TAGGED
            return TAGGED
        return TAGGED

    def _uses_tainted(self, r: FunctionReps, e: ir.Expr) -> bool:
        """Does the interval the proofs give `e` read a tainted name? (Mirrors `frontend._Lowerer.interval`: a
        constant, a length, a call and an element read have an interval of their own, whatever their operands.)"""
        if isinstance(e, ir.Local):
            return e.name in r.tainted
        if isinstance(e, ir.BinOp):
            return self._uses_tainted(r, e.left) or self._uses_tainted(r, e.right)
        if isinstance(e, ir.UnaryOp):
            return self._uses_tainted(r, e.operand)
        if isinstance(e, ir.Cond):
            return self._uses_tainted(r, e.then) or self._uses_tainted(r, e.orelse)
        if isinstance(e, ir.Index):
            return False
        return False

    # -- the fixpoint

    def run(self) -> None:
        for f in self.module.functions:
            r = self.reps[f.name]
            for p in f.params:
                if isinstance(p, ir.Param) and p.type is Type.I64:
                    r.names.setdefault(p.name, RAW)
            for name, t in f.locals.items():
                if t is Type.I64:
                    r.names.setdefault(name, RAW)
        changed = True
        while changed:
            changed = False
            for f in self.module.functions:
                changed |= self.scan(f)

    @staticmethod
    def raise_(d: dict[str, str], key: str, rep: str) -> bool:
        if rep == TAGGED and d.get(key) != TAGGED:
            d[key] = TAGGED
            return True
        return False

    def scan(self, f: ir.Function) -> bool:
        r = self.reps[f.name]
        changed = False
        elements = r.tuple_elements
        for s in f.body:
            for n in walk(s):
                if isinstance(n, ir.Assign):
                    if n.value.type is Type.I64:
                        changed |= self.raise_(r.names, n.target, self._of(r, n.value))
                        if self._uses_tainted(r, n.value) and n.target not in r.tainted:
                            r.tainted.add(n.target)
                            changed = True
                elif isinstance(n, ir.ForRange):
                    rep = join(self._of(r, n.start), self._of(r, n.stop))
                    changed |= self.raise_(r.names, n.var, rep)
                    if (self._uses_tainted(r, n.start) or self._uses_tainted(r, n.stop)) and n.var not in r.tainted:
                        r.tainted.add(n.var)
                        changed = True
                elif isinstance(n, ir.Return):
                    if n.value is not None and n.value.type is Type.I64 and r.returns != TAGGED \
                            and self._of(r, n.value) == TAGGED:
                        r.returns = TAGGED
                        changed = True
                elif isinstance(n, ir.ReturnTuple):
                    while len(elements) < len(n.values):
                        elements.append(RAW)
                        changed = True
                    for i, v in enumerate(n.values):
                        if v.type is Type.I64 and elements[i] != TAGGED and self._of(r, v) == TAGGED:
                            elements[i] = TAGGED
                            changed = True
                elif isinstance(n, ir.Unpack):
                    callee = self.reps.get(n.call.function)
                    if callee is not None:
                        for i, t in enumerate(n.targets):
                            if i < len(callee.tuple_elements):
                                changed |= self.raise_(r.names, t, callee.tuple_elements[i])
                elif isinstance(n, ir.Compare):
                    if n.left.type is Type.I64 and n.right.type is Type.I64:
                        for local, other in ((n.left, n.right), (n.right, n.left)):
                            if isinstance(local, ir.Local) and not isinstance(other, ir.Const):
                                if (self._of(r, other) == TAGGED or self._uses_tainted(r, other)) \
                                        and local.name not in r.tainted:
                                    r.tainted.add(local.name)
                                    changed = True
                if isinstance(n, ir.Call):
                    callee_fn = self.fns.get(n.function)
                    callee = self.reps.get(n.function)
                    if callee_fn is not None and callee is not None:
                        for p, a in zip(callee_fn.params, n.args):
                            if isinstance(p, ir.Param) and p.type is Type.I64:
                                changed |= self.raise_(callee.names, p.name, self._of(r, a))
        return changed


def infer(module: ir.Module, *, strict: bool = False) -> Representation:
    """The representations of every int of `module`. (The int parameters of a library export also arrive from
    outside as int64, which is RAW, so they raise nothing: only the call sites inside the module do.)"""
    return Representation(module, strict)
