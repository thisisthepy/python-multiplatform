"""Which ints are RAW and which are TAGGED (SPEC N-17, #462).

Provenance. Design decision D1 (a) (leader, 2026-11-16, not owner-confirmed): an int that is not proved inside 64
bits is a small-int / big-int value of its own (`runtime/tp_aot_int.h`). The representation below (RAW int64 and
TAGGED `tpa_int`, decided per program by a fixpoint over all functions) was approved by the leader on 2026-11-16, not
owner-confirmed. The inference rule written out here, and the sound re-proof it rests on in particular, are agent
proposals, not owner-confirmed.

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
  * `+ - *` and unary minus that the IR marks `proven` AND that the sound re-proof (below) proves AND whose operands
    are all RAW;
  * `//` by a constant other than -1 of a RAW operand, and `%` of two RAW operands (the result lies between zero and
    the divisor); a `//` by -1 or by a variable is TAGGED (INT64_MIN // -1 is 2**63).

Everything else is TAGGED: the result of an operation that is not proved, of any operation with a TAGGED operand,
`int(sys.argv[i])`, and every name, parameter, return and tuple element such a value flows into. A name assigned
both RAW and TAGGED values is TAGGED (a RAW value is converted with `tpa_int_from_i64` where it is stored).

The sound re-proof (why a proved flag is not enough). The front end and the verifier prove an operation from
intervals that read every int as one inside 64 bits: the interval of an operation that is not proved is its clamp to
int64 ("it would have deopted"), a parameter or a call result is "any int64", and a guard `a < b` cuts `a` by that
interval of `b`. Where a value is TAGGED none of that is true of it. Example: `lo = items[0]` (any int64),
`hi = lo * lo` (TAGGED), `if lo < hi: lo = lo + 1`: the verifier proves `lo + 1` from `lo < hi` and `hi <= INT64_MAX`,
false for the big `hi`, and with `lo == INT64_MAX` a RAW addition would wrap; `n = 9223372036854775807 - hi` then
`a = [0] * (n + 5)` gives `a` a length of at least 5 to the verifier, and `a[4]` a bounds check it may skip, while the
list is empty. So the proved flags are only a candidate: `verify.sound_proofs` walks the verified IR again with
intervals that hold of unbounded integers, given this inference as its oracle (a RAW name or return is an int64, a
TAGGED one is unbounded, `int(sys.argv[i])` is unbounded, nothing is clamped; verify.py "The sound re-proof of the
AOT mode"), and an operation is RAW, and a proved index skips its check (`Representation.trusted`), only if it is
proved there too. The two are one fixpoint: the inference starts with everything RAW, the re-proof runs under it, a
name rises to TAGGED where a proof fails, and the re-proof runs again until nothing rises. At the end every RAW name
is an int64 on every path by induction over the execution: each RAW value is a constant, a length, an element, or a
proved operation whose operands are RAW (int64 by the induction) and whose interval facts hold of unbounded ints.

What this cannot find: it treats every name as one representation for the whole function, so a counter that is RAW
for its first thousand iterations is TAGGED from the start (speed, never exactness); and the re-proof proves less
than the default mode's rules where a fact needs a TAGGED value's int64 range (`if lo < hi` above).

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


class Representation:
    """The result of `infer`: the representation of every int of every function."""

    def __init__(self, module: ir.Module, strict: bool = False) -> None:
        self.module = module
        self.strict = strict
        self.fns = {f.name: f for f in module.functions}
        self.reps = {f.name: FunctionReps() for f in module.functions}
        # the sound re-proof (module docstring): id of a node of `module` -> proved under intervals of unbounded ints
        self.proofs: dict[int, bool] = {}
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

    def of(self, fn: str, e: ir.Expr) -> str:
        """The representation of the expression `e` of function `fn` (RAW for anything that is not an int)."""
        if self.strict or getattr(e, "type", None) is not Type.I64:
            return RAW
        return self._of(self.reps[fn], e)

    def trusted(self, fn: str, node: ir.Index | ir.StoreIndex) -> bool:
        """May the proved index `node` (an `Index` or `StoreIndex` of `fn` the verifier marked `proven`) skip its
        bounds check? Only if the sound re-proof proves it in bounds too."""
        if self.strict:
            return True
        return self.proofs.get(id(node), False)

    # -- the oracle of the sound re-proof (verify.sound_proofs)

    def raw_name(self, fn: str, name: str) -> bool:
        r = self.reps.get(fn)
        return r is not None and r.names.get(name) == RAW

    def raw_return(self, fn: str) -> bool:
        r = self.reps.get(fn)
        return r is not None and r.returns == RAW

    # -- the rule

    def proved(self, e: ir.Expr) -> bool:
        """`e` is marked `proven` and the sound re-proof proves it too."""
        return bool(getattr(e, "proven", False)) and self.proofs.get(id(e), False)

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
                return RAW if self.proved(e) and self._of(r, e.operand) == RAW else TAGGED
            return TAGGED
        if isinstance(e, ir.BinOp):
            a, b = self._of(r, e.left), self._of(r, e.right)
            if a == TAGGED or b == TAGGED:
                return TAGGED
            k = e.op
            if k in (ir.BinOpKind.ADD, ir.BinOpKind.SUB, ir.BinOpKind.MUL):
                return RAW if self.proved(e) else TAGGED
            if k is ir.BinOpKind.MOD:
                return RAW
            if k is ir.BinOpKind.FLOORDIV:
                if isinstance(e.right, ir.Const) and e.right.value != -1:
                    return RAW
                return RAW if self.proved(e) else TAGGED
            return TAGGED
        return TAGGED

    # -- the fixpoint

    def run(self) -> None:
        from typedpython import verify      # (verify does not import this module)

        for f in self.module.functions:
            r = self.reps[f.name]
            for p in f.params:
                if isinstance(p, ir.Param) and p.type is Type.I64:
                    r.names.setdefault(p.name, RAW)
            for name, t in f.locals.items():
                if t is Type.I64:
                    r.names.setdefault(name, RAW)
        while True:
            # the re-proof under the representation as it stands, then raise what it no longer proves; again until
            # a re-proof raises nothing (a representation only goes up, so this ends)
            self.proofs = verify.sound_proofs(self.module, self)
            raised = False
            changed = True
            while changed:
                changed = False
                for f in self.module.functions:
                    changed |= self.scan(f)
                raised |= changed
            if not raised:
                return

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
                elif isinstance(n, ir.ForRange):
                    rep = join(self._of(r, n.start), self._of(r, n.stop))
                    changed |= self.raise_(r.names, n.var, rep)
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
