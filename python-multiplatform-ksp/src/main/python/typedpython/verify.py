"""TypedPython IR safety verifier (SPEC N-9, #41; maintainer decision 2026-10-03).

`verify(module)` proves, per function and on the IR alone, the properties listed in the "Safety"
section of `ir.py`. A function it cannot prove is removed from `Module.functions` and moved to
`Module.skipped` with the reason, so it stays interpreted; every reason is also returned as a
`Diagnostic`. A proved function comes back with each `Index`/`StoreIndex` whose bounds were proved
marked `proven=True`; every other access has `proven=False` (an input `proven` is never trusted).

The verifier reads nothing but `ir`, it is independent of the front end, and it trusts no flag
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
  verify/condition-only       a CompareObj outside a condition (If/While cond, or an And/Or
                              operand that is itself in a condition): its rich-comparison result
                              need not be a bool, so only its truth value may be used
  verify/structure            break/continue outside a loop, the ForRange var assigned in its body,
                              a zero or non-constant step, duplicate names, bad declarations, an
                              entry global that collides with a parameter/local/other entry global or
                              is assigned in the body, or more than two array parameters (the lists
                              are locked from copy-in to write-back: one- and two-object critical
                              sections only, #159)
  verify/proven-overflow      `proven` is set on an op the verifier cannot prove stays inside i64 (or
                              on a node `proven` does not apply to, or it is not a bool)
  verify/redo                 `Call.redo` where it is not legal (see below)
  verify/entry-global-open    a function with entry_globals is not closed
  verify/array-open           a function with an ArrayParam runs user code (#203)
  verify/buffer               a malformed `ArrayParam.buffer` (only None and "array"), or a
                              CopyArray of a buffer array (#149)
  verify/par-loop             a `ForRange.par` (SPEC N-15, #277) that the verifier does not recompute to
                              the same value: the function is not marked `parallel`, or the loop is
                              not eligible (see "Parallel loops" below)
  verify/class                a class named by FieldGet/FieldSet/New/IsExact/CheckExact/Param.cls is not
                              a (valid) ClassDecl of the module, a field is not one of its fields, a
                              New names a class without trivial_init or has the wrong arity, or a
                              ClassDecl itself is malformed (bad name/fields, duplicates)
  verify/field-unproved       a FieldGet/FieldSet whose object is not proved to be exactly the class

Fixed-layout classes (SPEC N-11). The verifier never trusts the front end for them either:

  * Every class named anywhere must be a valid `ClassDecl` of `Module.classes`; every field named
    must be one of that class's `fields`; `New` needs `trivial_init` and exactly len(fields) OBJ
    arguments; `Param.cls` only on an OBJ parameter (`optional` only together with `cls`).
  * Exact-type proof for FieldGet/FieldSet (`verify/field-unproved` otherwise). The object must be
    one of exactly these forms, for the same class C as the access:
      (a) a `Local` that is a parameter with cls=C and optional=False, until the local is assigned;
      (b) a `CheckExact(x, C)` node used directly as the object;
      (c) a `New(C, ...)` node used directly as the object;
      (d) a `Local` proved exact-C by control flow, with no assignment of it in between: inside the
          `then` of `If(IsExact(Local, C), ...)`, or its `orelse` when the condition is false
          (`Not(IsExact)`), and inside the right operand of an `And` whose left operand is
          such a test (an `Or`'s right operand: when the left is false); or
          `Is(Local x, Const(None, OBJ), negate=True)` (true, or `negate=False` false) when x is
          an `optional` parameter of class C that the function never assigns anywhere; or
          after `if <not exact>: return/break/continue` (the facts of the branches that fall
          through are intersected); or a local assigned from (b), (c) or another proved local
          (`x = New(C, ..)`, `x = CheckExact(y, C)`, `x = y`) from there until it is assigned again;
      (e) a `Local` that is not a parameter, entry global or loop var, and whose every Assign in the
          function is a `New(C, ...)` of that same class: whatever is read from it (definite
          assignment is proved separately) is a C created by New. Flow-insensitive, so one other kind
          of assignment anywhere in the function ends the form.
    Facts are killed by any assignment of the local (and, in a loop, by an assignment anywhere in
    the loop body for the whole loop, the loop var included). Nothing else is accepted: not
    isinstance, not a fact about another local, not a fact through a call, GetAttr or FieldGet.
  * A `Call` of a compiled callee whose parameter has cls=C (the callee's entry guard) with an
    argument not proved exact-C by the forms above can fail that guard in the middle of the caller:
    it counts toward `may_deopt` (so it is legal only in a pure function) unless it is a legal redo.
  * Purity/deopt: CheckExact can deopt (like Unbox to i64): only in a pure function, and it counts
    toward `may_deopt` even when redundant. FieldSet is an effect (and makes the function not
    *closed*: replacing a slot may drop the last reference to the old value, whose `__del__` is user
    code). FieldGet, Is, IsExact, New are not effects.
  * Typing: Is/IsExact give BOOL over OBJ operands; FieldGet/New/CheckExact give OBJ; `Const(None,
    OBJ)` is the only OBJ constant (anything else `verify/type`) and counts as a reference creator
    (a new reference to the None singleton) wherever an OBJ may flow: an operand of Is, a returned
    value, an argument.
  * Ownership: New, FieldGet and CheckExact are reference creators. CheckExact is read as the IR says,
    "`obj` itself ... after checking": the result is a new reference to the operand (the back end
    increfs it); the operand's own reference is untouched.

Methods (SPEC N-11, #155). A `Function` with an `owner` must name a valid ClassDecl, be called
`<owner>.<identifier>` (not a slot name) and take `self` as its first parameter, `Param(OBJ, cls=owner,
optional=False)` (`verify/class` otherwise). `CheckMethod(obj, C, m)` is CheckExact plus a callee edge to
`C.m`: it deopts (pure functions only, counts toward `may_deopt`), names a compiled method of C
(`verify/call-unresolved` otherwise; a rejected or removed method rejects the caller) and is an exact-C
proof like CheckExact.

Objects, str and dict code (#150). GetItem, Contains, Format, StoreItem and ForObj run user code
(`__getitem__`, `__hash__`, `__eq__`, `__format__`, `__iter__`, `__next__`, `__setitem__`): each is an
effect and makes the function not closed (so it can hold no array, no buffer and no entry global),
like CallObject. NewList, NewDict and BuildString run none. They are OBJ producers (new references)
with the OBJ operand slots below; an OBJ Const is None or a plain str (UTF-8, no NUL); a ForObj's
targets are distinct declared OBJ locals, assigned by the loop (so definitely assigned inside the
body, and not after it unless assigned before: a loop of zero iterations binds nothing), killing
any exact-type fact about them; `unpack` false means exactly one target; `BinOp.inplace` only on an
OBJ operation; `Format.conversion` is -1, 97, 114 or 115 and `spec` None or a plain str.

Proven i64 operations. The verifier never trusts `BinOp.proven` / `UnaryOp.proven`: for every I64
expression it computes an interval [lo, hi] (exact Python ints) and accepts `proven=True` only on an
I64 ADD/SUB/MUL (BinOp) or NEG (UnaryOp) whose exact result interval lies inside [-2**63, 2**63-1],
or on an I64 FLOORDIV/MOD whose divisor is a Const power of two >= 1 and whose dividend interval lies
inside [0, 2**63-1] (the back end then emits a shift / mask, #148);
anything else (a different op, a non-I64 node, a result that may leave i64, a dividend that may be
negative, another divisor, a non-bool flag) is rejected, and so is the whole function. A proven op is not a deopting node (an impure function may
contain it; a pure one does not count it toward `may_deopt`); every unproven I64 ADD/SUB/MUL/NEG
still deopts. The interval rules, exactly:

  Const v                      [v, v]
  Local, a ForRange var inside its own loop body:
      step > 0                 [lo(start), max(lo(start), hi(stop) - 1)]
      step < 0                 [min(hi(start), lo(stop) + 1), hi(start)]
                               (the bounds are the intervals of the start/stop expressions, both
                               evaluated before the loop; then intersected with the i64 range)
  Local, any other I64 local   see "Local variables and guards" below
  Len(array)                   [0, 2**62]
  ADD / SUB / MUL              interval arithmetic on the operand intervals (for MUL the min/max of
                               the four endpoint products); a proven op must fit i64 exactly, an
                               unproven one is clamped to i64 (a result outside it deopts, so only
                               in-range values flow on)
  NEG / POS                    [-hi, -lo] / [lo, hi]
  INVERT                       [~hi, ~lo] (~x == -x - 1, always in i64)
  AND                          an operand with lo >= 0: [0, min of the hi of the non-negative operands]
  MOD                          divisor interval > 0: [0, hi(b) - 1]; < 0: [lo(b) + 1, 0]; else
                               [-(d - 1), d - 1] with d = max |b|
  FLOORDIV                     divisor interval that keeps its sign: the min/max of the four
                               corner quotients; else [-m, m] with m = max |a| (all clamped to i64:
                               -2**63 // -1 deopts, so it yields no value)
  everything else              the full i64 range: I64 params and entry globals, a loop var after
                               its loop, Index elements, Unbox, Call results, TRUEDIV

Local variables and guards (#148). Two flow facts narrow what a read of an I64 local can be; both
are computed here, never taken from the front end.

  * Assigned values. A local that is not a parameter, entry global, array or ForRange var of the
    function, and is assigned by Assign statements, has the join of the intervals of every value
    assigned to it (an unproven op counts clamped to i64, as above). The intervals are a fixed point:
    `_prove` walks the body again and again, each walk reading the intervals the previous one found,
    until no interval grows; from the fourth walk a bound that still grows jumps to the i64 limit,
    and after 16 walks every such local is full range. The fixed point means each assigned value
    lies inside its local's interval, whichever path ran. An assignment that reads a local with no
    interval yet (the first walks) adds nothing that walk. So `x = 1; x + 1` is proven, and a counter
    that starts at 0 and only grows (`k = 0 ... k = k + 1`) is in [0, 2**63-1], not "any i64".
  * Guards. Inside the then-branch of `If(c)` (the else-branch for `c` false), the body of
    `While(c)`, and after an `If` one branch of which cannot complete (return, break, continue), a
    Local that is a side of an I64 comparison with the other side's interval [lo, hi] is narrowed:
    `x < e` gives x <= hi - 1, `x <= e` x <= hi, `x > e` x >= lo + 1, `x >= e` x >= lo, `x == e`
    [lo, hi]; the Local may be either side; And (true) and Or (false) apply both links, Not flips,
    the negation of a comparison is the opposite comparison, `!=` and anything else give nothing.
    A guard on `x` ends at the next Assign to `x`; entering a loop drops the guards on every local
    the loop body assigns (the back edge brings other values); a While's condition is re-read at
    each iteration, so it narrows the top of every iteration. A read is the assigned-values interval
    narrowed by the guard (and a ForRange var's interval is narrowed by it too).
  So `if n > 0: return n - 1` and `while lo < hi: lo += 1; hi -= 1` need no check, `n <= 0` early
  return then `n - 1` likewise.

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

Parallel loops (SPEC N-15, #277; step 1). `ForRange.par` is never trusted: `_par_info` recomputes it
for every loop; a declared value (the front end declares none) must equal it, else `verify/par-loop`. A loop
is eligible only if ALL of these hold:
  * the function is marked (`Function.parallel`);
  * the loop is `range(c >= 0, len(A), step > 0)` over an array `A` the body does not reassign (the
    same condition that lets the verifier prove bounds, so every access below is `proven`);
  * the body is a non-empty sequence of `StoreIndex` statements and nothing else (no Assign, no
    nested loop, no If, no Break/Continue/Return, no call);
  * every store is `a[var] = value` with the loop variable itself as index, `proven`, into an F64
    list or local array (not a buffer), so distinct iterations write distinct elements;
  * `value` is built from F64 Const, an F64 scalar Local (nothing in the body assigns it), a ToFloat
    of an I64 Const or Local, `Index(b, var)` proven on an F64 non-buffer array, F64 ADD/SUB/MUL
    (not `np`), and F64 NEG. No division (ZeroDivisionError), no I64 arithmetic (overflow), no
    comparison, no call, no object. So no node can fail, deopt or touch a Python object, and a read
    of `b[var]` where `b` is another array cannot alias a written element (array parameters are
    distinct lists by the entry guard, local arrays are distinct allocations), and a read of the
    stored array itself is the same element the iteration writes.
  * Step 2 (reductions): or the body is a non-empty sequence of `acc = acc op e` (`_par_reduce`): op
    ADD or MUL, each `acc` a distinct i64 local, definitely assigned before the loop, not an array,
    loop variable or entry global, and read nowhere else in the body (not in any `e`); `e` built from
    i64 Const, i64 Local (the loop variable, or a scalar nothing in the body assigns), `b[var]`
    proven on an i64 non-buffer list or local array, and i64 + - *. Stores and reductions are never
    mixed in one loop. Those nodes can overflow, which is not a failure of the region: the runtime
    discards it and reruns the loop sequentially. (& | ^ are not in the compiled subset: the front
    end lowers no bitwise operator, so there is no such reduction to mark; min/max are later.)
Anything else compiles sequentially, unchanged. This cannot find other eligible shapes (stencils,
calls, nested tiles, min/max); those are later steps.

Tuple. `Tuple(OBJ, elements)`: a tuple of OBJ expressions (a scalar needs Box), result OBJ, a
reference creator in the ownership rule. Building one is not an effect.

What this cannot prove: relations between variables (`i < n` bounds `i`, not `i + n`, and says
nothing about `j` when `j` is not compared), the range of a counter that is bounded only by how
often a loop runs (`k += 1` in `while c`), values through array elements, calls or Unbox, a loop var
after the loop, a guard on a local the guarded code assigns before the use, or `range` bounds that
are themselves full-range (`range(n)` bounds the var only by i64). A function needing such a proof
keeps its checked, deopting ops (and is then legal only if pure).

Call.redo. Legal only if the caller is impure, the callee is pure and may_deopt, and the callee
returns F64, BOOL, NONE or I64 (`verify/redo` otherwise); a redo Call of an I64-returning callee has
type INT (below). A redo call is not a deopting node in the caller: the callee alone is redone,
unobservably, since it is pure.

Native ints (SPEC N-13, #232). `Type.INT` is a Python int that is a native i64 or, once an operation
left the i64 range, a PyLong. It lives only in locals and temporaries (never a parameter, entry global
or return value). It is produced only by `ToInt` (of an I64), by BinOp/UnaryOp over INT operands, by
an INT local, and by a redo Call of an I64-returning callee (an INT argument may stand for an I64
parameter of a redo callee: a promoted value does not fit, which is the callee's deopt, redone).
BinOp on INT: both operands INT, ADD/SUB/MUL/FLOORDIV/MOD/LSHIFT/RSHIFT/AND/OR/XOR, result INT; TRUEDIV of ints is an
OBJ operation on boxed values; `proven` is never legal on it. UnaryOp NEG/POS of INT is INT. Compare of two
INTs is BOOL. An INT crosses into an object slot only through `Box`, and an I64 into an INT slot only
through `ToInt`; an INT never flows into an I64 slot. None of these nodes can deopt (they promote), run
user code or make a function impure, so an impure function may contain them freely, and they do not
affect `may_deopt`, `pure` or closedness. Bitwise operators (SPEC N-16, #289): I64 AND/OR/XOR and
UnaryOp INVERT of I64 / INT are legal and cannot deopt; I64 LSHIFT/RSHIFT are unproven deopting ops
(overflow, a negative count), so a function holding one is not impure-safe unless it is pure; they
are built in pure functions only (an impure function shifts INTs). None exists on F64.

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
    ToInt, Truth, Tuple, Type, Unbox, UnaryOp, UnaryOpKind, While,
)
from .ir import CheckExact, CheckMethod, ClassDecl, FieldGet, FieldSet, Is, IsExact, New
from .ir import BuildString, Contains, ForObj, Format, GetItem, NewDict, NewList, StoreItem
from .ir import ParInfo

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
STRUCTURE = "verify/structure"
CONDITION_ONLY = "verify/condition-only"
PROVEN = "verify/proven-overflow"
VIRTUAL = "verify/virtual-escape"
REDO = "verify/redo"
ENTRY_OPEN = "verify/entry-global-open"
ARRAY_OPEN = "verify/array-open"
BUFFER = "verify/buffer"
CLASS = "verify/class"
FIELD = "verify/field-unproved"
PAR_LOOP = "verify/par-loop"


@dataclass(frozen=True)
class Diagnostic:
    function: str
    rule: str
    message: str
    source_line: int


# Exact classes, not isinstance: a subclass of an ir node is not an ir node (its meaning is not
# the one the back end implements).
_EXPRS = frozenset({Const, Local, BinOp, UnaryOp, Compare, And, Or, ToFloat, ToInt, Box, Unbox, MathCall,
                    Call, Global, GetAttr, CallObject, Truth, CompareObj, ObjToFloat, Len, Index,
                    NewArray, CopyArray, Tuple, Is, IsExact, CheckExact, CheckMethod, FieldGet, New,
                    GetItem, Contains, NewList, NewDict, Format, BuildString})
_STMTS = frozenset({Assign, StoreIndex, ExprStmt, If, While, ForRange, Return, Break, Continue,
                    FieldSet, StoreItem, ForObj})
# Nodes whose OBJ result is a new reference (ir.py "ownership": Box, Global, GetAttr, CallObject,
# OBJ BinOp, Tuple), plus reads of an OBJ local (the local owns its reference; the back end increfs or
# borrows under that rule) and Calls of a compiled function returning OBJ (which returns a new
# reference by the same rule).
_OBJ_PRODUCERS = frozenset({Box, Global, GetAttr, CallObject, BinOp, Local, Call, Tuple, New,
                            FieldGet, CheckExact, CheckMethod, Const,      # an OBJ Const is None or a str (e_Const)
                            GetItem, NewList, NewDict, Format, BuildString})
_CONVERSIONS = (-1, 97, 114, 115)          # Format.conversion: none, !a, !r, !s
_VALUE_TYPES = (Type.I64, Type.F64, Type.BOOL, Type.OBJ)        # what a local may hold
_LOCAL_TYPES = _VALUE_TYPES + (Type.INT,)       # an INT lives only in a local or a temporary (SPEC N-13)
_RETURN_TYPES = _VALUE_TYPES + (Type.NONE,)
# The nodes that produce an INT (an owned tp_int: native, or a PyLong once promoted).
_INT_PRODUCERS = frozenset({ToInt, BinOp, UnaryOp, Local, Call})
_INT_OPS = frozenset({BinOpKind.ADD, BinOpKind.SUB, BinOpKind.MUL, BinOpKind.FLOORDIV,
                      BinOpKind.MOD, BinOpKind.LSHIFT, BinOpKind.RSHIFT, BinOpKind.AND,
                      BinOpKind.OR, BinOpKind.XOR})
_BITWISE_OPS = frozenset({BinOpKind.AND, BinOpKind.OR, BinOpKind.XOR})   # SPEC N-16
_ELEMENT = {Type.F64_ARRAY: Type.F64, Type.I64_ARRAY: Type.I64, Type.BOOL_ARRAY: Type.BOOL}
_MATH_ARITY = {MathFunc.ATAN2: 2, MathFunc.HYPOT: 2}            # every other MathFunc: 1
_I64_DEOPT_OPS = frozenset({BinOpKind.ADD, BinOpKind.SUB, BinOpKind.MUL, BinOpKind.FLOORDIV,
                            BinOpKind.MOD, BinOpKind.TRUEDIV, BinOpKind.LSHIFT, BinOpKind.RSHIFT})
_I64_MIN, _I64_MAX = -2 ** 63, 2 ** 63 - 1
_FULL = (_I64_MIN, _I64_MAX)
_LEN_MAX = 2 ** 62
_PROVABLE_OPS = frozenset({BinOpKind.ADD, BinOpKind.SUB, BinOpKind.MUL})
_NP_F64_OPS = frozenset({BinOpKind.ADD, BinOpKind.SUB, BinOpKind.MUL, BinOpKind.TRUEDIV,
                         BinOpKind.FLOORDIV, BinOpKind.MOD})
_POW2_OPS = frozenset({BinOpKind.FLOORDIV, BinOpKind.MOD})   # `proven` means shift / mask
_NEGATE = {CompareKind.EQ: CompareKind.NE, CompareKind.NE: CompareKind.EQ,
           CompareKind.LT: CompareKind.GE, CompareKind.GE: CompareKind.LT,
           CompareKind.LE: CompareKind.GT, CompareKind.GT: CompareKind.LE}
_SWAP = {CompareKind.EQ: CompareKind.EQ, CompareKind.NE: CompareKind.NE,
         CompareKind.LT: CompareKind.GT, CompareKind.GT: CompareKind.LT,
         CompareKind.LE: CompareKind.GE, CompareKind.GE: CompareKind.LE}
_ROUNDS = 16          # rounds of the local-interval fixpoint before every local is full range
_WIDEN_FROM = 3       # from this round on a bound that still grows jumps to the i64 limit

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
        self.bottom = False                 # fixpoint round: start/stop not known yet
        self.breaks: list[_State] = []


class _Checker:
    """Checks one function against the module's signatures. Collects diagnostics; rebuilds the
    body with proven bounds."""

    def __init__(self, function: Function, signatures: dict[str, Function],
                 classes: dict[str, ClassDecl] | None = None,
                 var_ivs: dict[str, tuple[int, int]] | None = None, fixing: bool = False):
        self.fn = function
        # Local intervals (module docstring "Local variables and guards"): `var_ivs` is the interval
        # of every eligible local; `fixing` says this walk is a round of the fixpoint that computes
        # them (a local without an interval yet is then "bottom": `bottom` is set when one is read).
        self.var_ivs = dict(var_ivs or {})
        self.fixing = fixing
        self.eligible: frozenset[str] = frozenset()
        self.contrib: dict[str, tuple[int, int]] = {}
        self.bottom = False
        self.dead = 0                        # >0: inside code that is bottom in this round
        self.refine: dict[str, tuple[int, int]] = {}   # guard facts at this point
        self.sigs = signatures
        self.classes = classes or {}
        self.facts: dict[str, str] = {}      # local -> class it is proved exactly (see docstring)
        self.optional: dict[str, str] = {}   # optional-class parameter -> class
        self.reassigned: frozenset[str] = frozenset()
        self.new_only: dict[str, str] = {}   # local whose every assignment is New(C, ..) -> C
        self.diags: list[Diagnostic] = []
        self.can_deopt = False
        self.effects: list[str] = []         # reasons the body is impure
        self.stored: set[str] = set()
        self.callees: set[str] = set()
        self.loops: list[_Loop] = []
        self.scalars: dict[str, Type] = {}   # name -> type of every scalar/OBJ variable
        self.arrays: dict[str, Type] = {}    # name -> array type of every ArrayParam
        self.buffers: set[str] = set()       # names of every buffer ArrayParam
        self.larrays: dict[str, Type] = {}   # name -> array type of every local array
        self.assigns: dict[str, int] = {}    # local array -> number of Assign statements to it
        self.lens: dict[str, tuple[int, int]] = {}   # single-assignment local array -> length
        self.entry: dict[str, Type] = {}     # name -> type of every entry global
        self.opens: list[str] = []           # reasons the function is not closed
        self.slow_user: list[str] = []       # slow paths that can run user code (FieldGet, New)

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
                elif p.buffer not in (None, "array", "numpy"):
                    self.error(BUFFER, f"array parameter '{p.name}' has buffer kind {p.buffer!r}")
                else:
                    self.arrays[p.name] = p.type
                    if p.buffer:
                        self.buffers.add(p.name)
            elif type(p) is Param:
                if p.type not in _VALUE_TYPES:
                    self.error(TYPE, f"parameter '{p.name}' has type {p.type}; arrays must be "
                                     f"ArrayParam")
                else:
                    self.scalars[p.name] = p.type
                    self.param_class(p)
            else:
                self.error(UNKNOWN, f"parameter {p!r} is not an ir Param/ArrayParam")
                continue
            if p.name in params:
                self.error(STRUCTURE, f"parameter '{p.name}' appears twice")
            params.add(p.name)
        if sum(type(p) is ArrayParam and p.buffer is None for p in fn.params) > 2:
            self.error(STRUCTURE, "more than two array parameters: compiled code holds each list's "
                                  "critical section from copy-in to write-back, and free-threaded "
                                  "CPython has no section for more than two objects (#159)")
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
            if p.cls is not None or p.optional is not False:
                self.error(TYPE, f"entry global '{p.name}' carries a class annotation")
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
            elif t not in _LOCAL_TYPES:
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

    def param_class(self, p: Param) -> None:
        """`Param.cls`/`optional`: only on an OBJ parameter, naming a ClassDecl of the module. A
        non-optional one is an exact-C fact at entry (the entry guard); an optional one is C or None."""
        if type(p.optional) is not bool:
            self.error(TYPE, f"parameter '{p.name}' has a non-bool `optional`")
            return
        if p.cls is None:
            if p.optional:
                self.error(TYPE, f"parameter '{p.name}' is optional but names no class")
            return
        if p.type is not Type.OBJ:
            self.error(TYPE, f"parameter '{p.name}' names class {p.cls!r} but is "
                             f"{p.type.value}, not obj")
        elif not self.known_class(p.cls, f"parameter '{p.name}'"):
            return
        elif p.optional:
            self.optional[p.name] = p.cls
        else:
            self.facts[p.name] = p.cls

    def known_class(self, cls: object, what: str) -> bool:
        if not isinstance(cls, str) or cls not in self.classes:
            self.error(CLASS, f"{what} names class {cls!r}, which is not a compiled class of this "
                              f"module")
            return False
        return True

    def known_field(self, cls: str, field: object, what: str) -> bool:
        if not self.known_class(cls, what):
            return False
        if not isinstance(field, str) or field not in self.classes[cls].fields:
            self.error(CLASS, f"{what}: '{field}' is not a field of class '{cls}' "
                              f"(fields: {', '.join(self.classes[cls].fields)})")
            return False
        return True

    # --- exact-type facts ---------------------------------------------------------------------

    def new_only_locals(self, params: frozenset[str]) -> dict[str, str]:
        """Form (e): locals (not parameters, entry globals, loop vars or arrays) every Assign of
        which is a `New` of one and the same declared class."""
        values: dict[str, list[object]] = {}
        _assigned_values(self.fn.body, values)
        loop_vars = _loop_vars(self.fn.body)
        out: dict[str, str] = {}
        for name, vs in values.items():
            if name in params or name in loop_vars or name in self.larrays:
                continue
            classes = {v.cls for v in vs if type(v) is New and isinstance(v.cls, str)}
            if (len(classes) == 1 and all(type(v) is New for v in vs)
                    and next(iter(classes)) in self.classes):
                out[name] = next(iter(classes))
        return out

    def exact_of(self, e: object) -> str | None:
        """The class `e` is proved to be exactly (forms (a)-(d) of the module docstring)."""
        cls = type(e)
        if cls is Local:
            return self.facts.get(e.name) or self.new_only.get(e.name)
        if cls is CheckExact or cls is CheckMethod or cls is New:
            return e.cls if isinstance(e.cls, str) and e.cls in self.classes else None
        return None

    def _none_test(self, c: Is) -> str | None:
        """`x is None` / `x is not None` on an optional-C parameter never assigned: the name."""
        for a, b in ((c.left, c.right), (c.right, c.left)):
            if (type(a) is Local and type(b) is Const and b.type is Type.OBJ and b.value is None
                    and a.name in self.optional and a.name not in self.reassigned):
                return a.name
        return None

    def facts_if(self, c: object, truth: bool) -> dict[str, str]:
        """The exact-type facts that hold when condition `c` evaluated to `truth`."""
        cls = type(c)
        if cls is IsExact:
            if truth and type(c.obj) is Local and c.cls in self.classes:
                return {c.obj.name: c.cls}
            return {}
        if cls is Is and type(c.negate) is bool:
            name = self._none_test(c)
            if name is not None and truth is c.negate:     # `is not None` true, or `is None` false
                return {name: self.optional[name]}
            return {}
        if cls is UnaryOp and c.op is UnaryOpKind.NOT:
            return self.facts_if(c.operand, not truth)
        if (cls is And and truth) or (cls is Or and not truth):
            return {**self.facts_if(c.left, truth), **self.facts_if(c.right, truth)}
        return {}

    def proved(self, obj: ir.Expr, cls: str, what: str) -> None:
        if self.exact_of(obj) != cls:
            self.error(FIELD, f"{what}: the object is not proved to be exactly '{cls}' (needs an "
                              f"exact-{cls} parameter, a CheckExact, a New, or a dominating "
                              f"IsExact / `is not None` of an optional parameter on the same local, "
                              f"with no assignment in between)")

    # --- expressions --------------------------------------------------------------------------

    def slot(self, e: ir.Expr, want: Type, what: str) -> None:
        """`e` flows into a slot of type `want` (already checked as an expression)."""
        got = getattr(e, "type", None)
        if got is want:
            return
        if got is Type.INT and want is Type.OBJ:
            self.error(OWNERSHIP, f"{what}: int flows into an OBJ slot without Box")
        elif want is Type.INT and got in (Type.I64, Type.OBJ):
            self.error(TYPE if got is Type.I64 else OWNERSHIP,
                       f"{what}: {got.value} flows into an int slot without ToInt")
        elif got is Type.INT and want in ir.SCALARS:
            self.error(TYPE, f"{what}: an int (native or promoted) cannot flow into a {want.value} "
                             f"slot")
        elif got is Type.OBJ and want in ir.SCALARS:
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
        if t is Type.INT and cls not in _INT_PRODUCERS:
            self.error(OWNERSHIP, f"{cls.__name__} cannot produce an int (only ToInt, int BinOp/UnaryOp, "
                                  f"int locals and redo Calls of i64 functions do)")
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
        if t is Type.OBJ:
            ok = v is None or _plain_str(v)   # None, or a str the C source can carry exactly
        if not ok:
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
                    if loop.bottom:
                        self.bottom = True
                    return _meet_interval(loop.interval, self.refine.get(e.name))
            base = _FULL
            if e.name in self.eligible:
                known = self.var_ivs.get(e.name)
                if known is not None:
                    base = known
                elif self.fixing:
                    self.bottom = True
            return _meet_interval(base, self.refine.get(e.name))
        if cls is Len:
            return self.lens.get(e.array, (0, _LEN_MAX))
        if cls is BinOp and e.op in _PROVABLE_OPS:
            lo, hi = _exact(e.op, self.interval(e.left), self.interval(e.right))
            return (max(lo, _I64_MIN), min(hi, _I64_MAX))
        if cls is BinOp and e.op in _POW2_OPS:
            lo, hi = _div_interval(e.op, self.interval(e.left), self.interval(e.right))
            return (max(lo, _I64_MIN), min(hi, _I64_MAX))
        if cls is BinOp and e.op is BinOpKind.AND:
            # SPEC N-16: a non-negative operand bounds an AND to [0, its hi] (the sign bit clears)
            his = [iv[1] for iv in (self.interval(e.left), self.interval(e.right)) if iv[0] >= 0]
            return (0, min(his)) if his else _FULL
        if cls is UnaryOp and e.op is UnaryOpKind.INVERT:
            lo, hi = self.interval(e.operand)          # ~x == -x - 1, monotone decreasing
            return (~hi, ~lo)
        if cls is UnaryOp and e.op in (UnaryOpKind.NEG, UnaryOpKind.POS):
            lo, hi = self.interval(e.operand)
            lo, hi = (-hi, -lo) if e.op is UnaryOpKind.NEG else (lo, hi)
            return (max(lo, _I64_MIN), min(hi, _I64_MAX))
        return _FULL

    def refinements(self, c: ir.Expr, truth: bool) -> dict[str, tuple[int, int]]:
        """The intervals that hold of locals when condition `c` evaluated to `truth` (module
        docstring "Local variables and guards"). A comparison of two I64 operands one of which is a
        `Local` bounds that local by the interval of the other; And (true) / Or (false) combine
        both links; Not flips; anything else gives nothing."""
        cls = type(c)
        if cls is Compare and type(c.op) is CompareKind:
            l, r = c.left, c.right
            if getattr(l, "type", None) is not Type.I64 or getattr(r, "type", None) is not Type.I64:
                return {}
            kind = c.op if truth else _NEGATE[c.op]
            out: dict[str, tuple[int, int]] = {}
            if type(l) is Local:
                _narrow(out, l.name, _bound(kind, self.interval(r)))
            if type(r) is Local:
                _narrow(out, r.name, _bound(_SWAP[kind], self.interval(l)))
            return out
        if cls is UnaryOp and c.op is UnaryOpKind.NOT:
            return self.refinements(c.operand, not truth)
        if (cls is And and truth) or (cls is Or and not truth):
            out = self.refinements(c.left, truth)
            for name, iv in self.refinements(c.right, truth).items():
                _narrow(out, name, iv)
            return out
        return {}

    def narrowed(self, c: ir.Expr, truth: bool) -> tuple[dict[str, tuple[int, int]], bool]:
        """(the current guard facts narrowed by `c` being `truth`, whether the narrowing read a local
        whose interval is not known yet in this fixpoint round)."""
        self.bottom = False
        found = self.refinements(c, truth)
        bottom, self.bottom = self.bottom, False
        out = dict(self.refine)
        for name, iv in found.items():
            _narrow(out, name, iv)
        return out, bottom

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

    def check_shift(self, op: BinOpKind, left: ir.Expr, right: ir.Expr) -> bool:
        """`proven` on an I64 FLOORDIV / MOD: the divisor is a Const power of two >= 1 and the
        dividend lies in [0, 2**63-1], so the back end may emit a shift / mask (Python's floor
        semantics and C's agree there). Returns whether the node counts as non-deopting."""
        d = right.value if type(right) is Const and type(right.value) is int else None
        if d is None or d < 1 or d & (d - 1) != 0:
            self.error(PROVEN, f"`proven` is set on i64 {op.value} but the divisor is not a "
                               f"Const power of two >= 1")
        elif self.interval(left)[0] < 0:
            self.error(PROVEN, f"`proven` is set on i64 {op.value} by {d} but the dividend may be "
                               f"negative (its interval starts at {self.interval(left)[0]})")
        else:
            return True
        return False

    def e_BinOp(self, e: BinOp, state: _State) -> ir.Expr:
        if type(e.op) is not BinOpKind:
            self.error(UNKNOWN, f"BinOp op {e.op!r} is not a BinOpKind")
            return e
        left, right = self.expr(e.left, state), self.expr(e.right, state)
        lt, rt = getattr(left, "type", None), getattr(right, "type", None)
        if type(e.inplace) is not bool:
            self.error(TYPE, f"BinOp `inplace` {e.inplace!r} is not a bool")
        elif e.inplace and lt is not Type.OBJ:
            self.error(TYPE, "BinOp `inplace` is set on a non-OBJ operation (it is PyNumber_InPlace*)")
        proven = False
        if type(e.proven) is not bool:
            self.error(TYPE, f"BinOp `proven` {e.proven!r} is not a bool")
        elif e.proven:
            both = e.type is Type.I64 and lt is Type.I64 and rt is Type.I64
            if both and e.op in _POW2_OPS:
                proven = self.check_shift(e.op, left, right)
            else:
                shape = both and e.op in _PROVABLE_OPS
                exact = (_exact(e.op, self.interval(left), self.interval(right))
                         if shape else _FULL)
                proven = self.check_proven(shape, exact, f"{e.type.value} {e.op.value}")
        if type(e.np) is not bool:
            self.error(TYPE, f"BinOp `np` {e.np!r} is not a bool")
        elif e.np:
            f64_op = e.type is Type.F64 and lt is Type.F64 and rt is Type.F64
            i64_op = (lt is Type.I64 and rt is Type.I64 and not e.proven and e.op in (
                BinOpKind.TRUEDIV, BinOpKind.FLOORDIV, BinOpKind.MOD)
                and e.type is (Type.F64 if e.op is BinOpKind.TRUEDIV else Type.I64))
            if not (f64_op or i64_op):
                self.error(TYPE, "BinOp `np` is set on something other than an f64 operation or an "
                                 "unproven i64 / // % (#220)")
            elif f64_op and e.op not in _NP_F64_OPS:
                self.error(TYPE, f"BinOp `np` on an f64 {e.op.value}")
            elif i64_op or self.fn.pure is True:
                self.can_deopt = True        # a warning / int64 division by zero: the interpreter redoes
        shift = e.op in (BinOpKind.LSHIFT, BinOpKind.RSHIFT)
        if lt is Type.INT:
            # SPEC N-13: both operands INT, result INT, never deopts (it promotes in place)
            if rt is not Type.INT:
                self.slot(right, Type.INT, f"right operand of {e.op.value}")
            elif e.op not in _INT_OPS:
                self.error(TYPE, f"int {e.op.value} has no INT form (box the operands: it is an OBJ "
                                 f"operation)")
            elif e.type is not Type.INT:
                self.error(TYPE, f"int {e.op.value} int is int, node says {e.type.value}")
        elif lt not in (Type.I64, Type.F64, Type.OBJ):
            self.error(TYPE if lt is not Type.OBJ else OWNERSHIP,
                       f"BinOp {e.op.value} on {getattr(lt, 'value', lt)} operands")
        elif lt is not rt:
            self.slot(right, lt, f"right operand of {e.op.value}")
        elif (shift or e.op in _BITWISE_OPS) and lt is Type.F64:
            self.error(TYPE, f"{e.op.value} on f64 operands: CPython raises TypeError (an OBJ "
                             f"operation)")
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
        if e.proven and lt is Type.INT:
            self.error(PROVEN, f"`proven` is set on an int {e.op.value}, which promotes instead")
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
        elif ot is Type.INT:
            if e.type is not Type.INT:
                self.error(TYPE, f"unary {e.op.value} of int is int, node says {e.type.value}")
            if e.proven:
                self.error(PROVEN, f"`proven` is set on unary {e.op.value} of an int, which "
                                   f"promotes instead")
        else:
            if ot is Type.OBJ:
                self.error(OWNERSHIP, f"unary {e.op.value} on OBJ has no IR form")
            elif ot not in (Type.I64, Type.F64):
                self.error(TYPE, f"unary {e.op.value} on {getattr(ot, 'value', ot)}")
            elif e.type is not ot:
                self.error(TYPE, f"unary {e.op.value} of {ot.value} is {ot.value}, node says "
                                 f"{e.type.value}")
            elif ot is Type.F64 and e.op is UnaryOpKind.INVERT:
                self.error(TYPE, "unary ~ on f64")
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
        if lt is Type.INT or rt is Type.INT:
            if lt is not rt:
                self.error(TYPE, f"Compare of {getattr(lt, 'value', lt)} and "
                                 f"{getattr(rt, 'value', rt)}: both sides of an int comparison are "
                                 f"INT (ToInt widens an i64)")
        elif Type.OBJ in (lt, rt):
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
        left = self.expr(e.left, state, cond)
        saved = self.facts
        # the right operand runs only when the left was true (And) / false (Or)
        self.facts = {**saved, **self.facts_if(left, type(e) is And)}
        right = self.expr(e.right, state, cond)
        self.facts = saved
        self.slot(left, Type.BOOL, f"left operand of {name}")
        self.slot(right, Type.BOOL, f"right operand of {name}")
        if e.type is not Type.BOOL:
            self.error(TYPE, f"{name} gives bool, node says {e.type.value}")
        return dataclasses.replace(e, left=left, right=right)

    e_And = _bool_pair
    e_Or = _bool_pair

    def e_ToFloat(self, e: ToFloat, state: _State) -> ir.Expr:
        operand = self.expr(e.operand, state)
        if type(e.explicit) is not bool:
            self.error(TYPE, f"ToFloat `explicit` {e.explicit!r} is not a bool")
        self.slot(operand, Type.F64 if e.explicit and getattr(operand, "type", None) is Type.F64
                  else Type.I64, "operand of ToFloat")
        if e.type is not Type.F64:
            self.error(TYPE, f"ToFloat gives f64, node says {e.type.value}")
        return dataclasses.replace(e, operand=operand)

    def e_ToInt(self, e: ToInt, state: _State) -> ir.Expr:
        operand = self.expr(e.operand, state)
        self.slot(operand, Type.I64, "operand of ToInt")
        if e.type is not Type.INT:
            self.error(TYPE, f"ToInt gives int, node says {e.type.value}")
        return dataclasses.replace(e, operand=operand)

    def e_Box(self, e: Box, state: _State) -> ir.Expr:
        operand = self.expr(e.operand, state)
        ot = getattr(operand, "type", None)
        if ot is Type.INT:
            pass
        elif ot is Type.OBJ:
            self.error(OWNERSHIP, "Box of an OBJ (it is already an object)")
        elif ot not in ir.SCALARS and ot not in ir.ARRAYS:
            self.error(TYPE, f"Box of {getattr(ot, 'value', ot)}")
        if e.type is not Type.OBJ:
            self.error(TYPE, f"Box gives obj, node says {e.type.value}")
        np_flag = e.np
        if np_flag is not None:
            np_flag = self.expr(e.np, state)
            if ot not in ir.SCALARS:
                self.error(TYPE, "Box `np` is set on a Box of something other than a scalar")
            self.slot(np_flag, Type.BOOL, "`np` flag of Box")
        return dataclasses.replace(e, operand=operand, np=np_flag)

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
        redo_ok = type(e.redo) is bool and e.redo and self.fn.pure is not True
        for k, (a, p) in enumerate(zip(args, callee.params)):
            if type(p) is ArrayParam:
                self.error(ARRAY_USE, f"'{e.function}' takes array parameter '{p.name}'; an array "
                                      f"cannot be passed by Call (the entry guard and write-back "
                                      f"belong to the Python-level call)")
            elif (redo_ok and p.type is Type.I64 and getattr(a, "type", None) is Type.INT):
                pass        # SPEC N-13: a promoted INT does not fit the parameter: the callee's deopt, redone
            else:
                self.slot(a, p.type, f"argument {k + 1} of '{e.function}'")
        # SPEC N-13: a redo Call of an i64-returning callee gives an INT (the redone value is any int)
        int_result = (redo_ok and callee.returns is Type.I64 and e.type is Type.INT)
        if e.type is not callee.returns and not int_result:
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
            elif callee.returns not in (Type.F64, Type.BOOL, Type.NONE, Type.I64):
                self.error(REDO, f"redo call of '{e.function}' returning "
                                 f"{getattr(callee.returns, 'value', callee.returns)}: only f64, "
                                 f"bool, none and i64 (as an int) results are exactly reproducible")
            elif callee.returns is Type.I64 and e.type is not Type.INT:
                self.error(REDO, f"redo call of '{e.function}' returning i64 must have type int "
                                 f"(the redone value may be a big int)")
            else:
                redo = True
        if not redo:
            for p in callee.params:
                # The callee's entry guard `type(x) is C` may fail, even for an argument that is a
                # just-allocated `New(C)` local: on free-threaded CPython another thread can find
                # the object through gc.get_objects() and assign `__class__` (SPEC N-12). So
                # exact_of must not exempt the guard; cgen's `_can_deopt` is equally conservative.
                if type(p) is Param and p.cls is not None:
                    self.can_deopt = True
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

    # --- fixed-layout classes -----------------------------------------------------------------

    def e_GetItem(self, e: GetItem, state: _State) -> ir.Expr:
        obj = self.obj_operand(e.obj, state, "container of GetItem")
        index = self.obj_operand(e.index, state, "index of GetItem")
        self.result(e, Type.OBJ)
        self.effects.append("GetItem (runs __getitem__, __hash__, __eq__, __index__)")
        self.opens.append("GetItem")
        return dataclasses.replace(e, obj=obj, index=index)

    def e_Contains(self, e: Contains, state: _State) -> ir.Expr:
        left = self.obj_operand(e.left, state, "left operand of in")
        right = self.obj_operand(e.right, state, "right operand of in")
        if type(e.negate) is not bool:
            self.error(TYPE, f"Contains `negate` {e.negate!r} is not a bool")
        self.result(e, Type.BOOL)
        self.effects.append("Contains (runs __contains__, __iter__, __eq__)")
        self.opens.append("Contains")
        return dataclasses.replace(e, left=left, right=right)

    def e_NewList(self, e: NewList, state: _State) -> ir.Expr:
        self.result(e, Type.OBJ)
        return e

    def e_NewDict(self, e: NewDict, state: _State) -> ir.Expr:
        self.result(e, Type.OBJ)
        return e

    def e_Format(self, e: Format, state: _State) -> ir.Expr:
        operand = self.obj_operand(e.operand, state, "operand of Format")
        if e.spec is not None and not _plain_str(e.spec):
            self.error(TYPE, f"Format spec {e.spec!r} is not None or a plain str")
        if e.conversion not in _CONVERSIONS or type(e.conversion) is not int:
            self.error(TYPE, f"Format conversion {e.conversion!r} is not one of {_CONVERSIONS}")
        self.result(e, Type.OBJ)
        self.effects.append("Format (runs __format__, __str__, __repr__)")
        self.opens.append("Format")
        return dataclasses.replace(e, operand=operand)

    def e_BuildString(self, e: BuildString, state: _State) -> ir.Expr:
        if not isinstance(e.elements, tuple) or len(e.elements) < 2:
            self.error(STRUCTURE, "BuildString elements are not a tuple of at least two")
            return e
        elements = tuple(self.obj_operand(x, state, f"element {k + 1} of BuildString")
                         for k, x in enumerate(e.elements))
        for k, x in enumerate(elements):
            if not (type(x) is Format or (type(x) is Const and type(x.value) is str)):
                self.error(TYPE, f"element {k + 1} of BuildString is not a Format or a str Const")
        self.result(e, Type.OBJ)
        return dataclasses.replace(e, elements=elements)

    def e_Is(self, e: Is, state: _State) -> ir.Expr:
        left = self.obj_operand(e.left, state, "left operand of is")
        right = self.obj_operand(e.right, state, "right operand of is")
        if type(e.negate) is not bool:
            self.error(TYPE, f"Is `negate` {e.negate!r} is not a bool")
        self.result(e, Type.BOOL)
        return dataclasses.replace(e, left=left, right=right)

    def e_IsExact(self, e: IsExact, state: _State) -> ir.Expr:
        obj = self.obj_operand(e.obj, state, "operand of IsExact")
        self.known_class(e.cls, "IsExact")
        self.result(e, Type.BOOL)
        return dataclasses.replace(e, obj=obj)

    def e_CheckExact(self, e: CheckExact, state: _State) -> ir.Expr:
        obj = self.obj_operand(e.obj, state, "operand of CheckExact")
        self.known_class(e.cls, "CheckExact")
        self.result(e, Type.OBJ)
        self.can_deopt = True       # a wrong type deopts, even if the check is redundant
        return dataclasses.replace(e, obj=obj)

    def e_CheckMethod(self, e: CheckMethod, state: _State) -> ir.Expr:
        """Methods (SPEC N-11, #155): the check is a deopt, like CheckExact, and names a compiled
        method of the class: the callee edge makes a rejected or removed method reject this function
        too (the back end's descriptors are built from the methods that are compiled)."""
        obj = self.obj_operand(e.obj, state, "operand of CheckMethod")
        self.result(e, Type.OBJ)
        self.can_deopt = True
        if self.known_class(e.cls, "CheckMethod"):
            q = f"{e.cls}.{e.method}" if isinstance(e.method, str) else None
            callee = self.sigs.get(q) if q is not None else None
            if callee is None or callee.owner != e.cls:
                self.error(UNRESOLVED, f"CheckMethod names method {e.method!r} of '{e.cls}', which "
                                       f"is not a compiled method of this module")
            else:
                self.callees.add(q)
        return dataclasses.replace(e, obj=obj)

    def e_FieldGet(self, e: FieldGet, state: _State) -> ir.Expr:
        obj = self.obj_operand(e.obj, state, f"object of .{e.field}")
        self.result(e, Type.OBJ)
        if self.known_field(e.cls, e.field, f"FieldGet .{e.field}"):
            self.proved(obj, e.cls, f"FieldGet {e.cls}.{e.field}")
        # a slot read runs no user code: not an effect. Its slow path after a class change (getattr)
        # can, which matters to array functions (#203).
        self.slow_user.append(f"FieldGet .{e.field} (generic getattr after a class change)")
        return dataclasses.replace(e, obj=obj)

    def e_New(self, e: New, state: _State) -> ir.Expr:
        self.slow_user.append(f"New {e.cls} (calls the class after a class change)")
        args = self.args(e.args, state, f"New {e.cls}")
        for k, a in enumerate(args):
            self.slot(a, Type.OBJ, f"argument {k + 1} of New {e.cls}")
        self.result(e, Type.OBJ)
        if self.known_class(e.cls, "New"):
            decl = self.classes[e.cls]
            if decl.trivial_init is not True:
                self.error(CLASS, f"New of '{e.cls}', whose __init__ is not trivial (it must be "
                                  f"called as an object)")
            if len(args) != len(decl.fields):
                self.error(CLASS, f"New of '{e.cls}' has {len(args)} argument(s) for "
                                  f"{len(decl.fields)} field(s)")
        return dataclasses.replace(e, args=args)

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
            elif e.src in self.buffers:
                self.error(BUFFER, f"CopyArray of buffer array '{e.src}': a buffer array is never "
                                   "copied)")
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
        if (target in self.eligible and not alloc and not self.dead
                and getattr(value, "type", None) is Type.I64):
            self.bottom = False
            iv = self.interval(value)
            if not self.bottom:
                self.contrib[target] = _join_interval(self.contrib.get(target), iv)
            self.bottom = False
        self.refine.pop(target, None)         # a guard on `target` ends at its assignment
        proved = None if alloc else self.exact_of(value)
        if proved is not None:
            self.facts[target] = proved
        else:
            self.facts.pop(target, None)
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

    def s_FieldSet(self, s: FieldSet, state: _State):
        obj = self.obj_operand(s.obj, state, f"object of .{s.field} =")
        value = self.expr(s.value, state)
        self.slot(value, Type.OBJ, f"value stored into .{s.field}")
        if self.known_field(s.cls, s.field, f"FieldSet .{s.field}"):
            self.proved(obj, s.cls, f"FieldSet {s.cls}.{s.field}")
        self.effects.append(f"FieldSet .{s.field} (a store)")
        self.opens.append(f"FieldSet .{s.field}")      # dropping the old value may run its __del__
        return dataclasses.replace(s, obj=obj, value=value), state

    def s_StoreItem(self, s: StoreItem, state: _State):
        obj = self.obj_operand(s.obj, state, "container of StoreItem")
        index = self.obj_operand(s.index, state, "index of StoreItem")
        value = self.obj_operand(s.value, state, "value of StoreItem")
        self.effects.append("StoreItem (runs __setitem__, __hash__, __eq__)")
        self.opens.append("StoreItem")
        return dataclasses.replace(s, obj=obj, index=index, value=value), state

    def s_ForObj(self, s: ForObj, state: _State):
        it = self.obj_operand(s.iter, state, "iterable of ForObj")
        targets = s.targets
        ok = (isinstance(targets, tuple) and targets and all(isinstance(t, str) for t in targets)
              and len(set(targets)) == len(targets))
        if not ok:
            self.error(STRUCTURE, f"ForObj targets {targets!r} are not distinct names")
            targets = ()
        if type(s.unpack) is not bool:
            self.error(TYPE, f"ForObj `unpack` {s.unpack!r} is not a bool")
        elif not s.unpack and len(targets) != 1 and ok:
            self.error(STRUCTURE, "ForObj without unpack has exactly one target")
        for t in targets:
            if t in self.arrays or t in self.larrays:
                self.error(ARRAY_USE, f"ForObj target '{t}' is an array")
            elif self.scalars.get(t) is not Type.OBJ:
                self.error(TYPE, f"ForObj target '{t}' must be a declared obj local")
            if t in self.entry:
                self.error(STRUCTURE, f"entry global '{t}' is assigned by the loop")
            for outer in self.loops:
                if outer.var == t:
                    self.error(STRUCTURE, f"loop variable '{t}' is assigned in its loop body "
                                          f"(by an inner loop)")
        self.effects.append("ForObj (runs __iter__ and __next__)")
        self.opens.append("ForObj")
        loop = _Loop(None, None)
        self.loops.append(loop)
        head = self.loop_facts(s.body, tuple(targets))
        self.facts = dict(head)
        killed = _assigned_names(s.body) | set(targets)
        head_refine = {k: v for k, v in self.refine.items() if k not in killed}
        self.refine = head_refine
        body, _ = self.block(s.body, None if state is None else state | set(targets))
        self.loops.pop()
        self.refine = dict(head_refine)
        self.facts = dict(head)
        # Zero iterations leave the targets as they were: the state after the loop is the entry
        # state, met with every break state (each a superset of it).
        after: _State = state
        for br in loop.breaks:
            after = _meet(after, br)
        return dataclasses.replace(s, iter=it, body=body), after

    def s_If(self, s: If, state: _State):
        cond = self.cond(s.cond, state, "if")
        base = self.facts
        entry = self.refine
        self.facts = {**base, **self.facts_if(cond, True)}
        self.refine, dead = self.narrowed(cond, True)
        self.dead += dead
        then, st = self.block(s.then, state)
        self.dead -= dead
        then_facts, then_refine = self.facts, self.refine
        self.facts = {**base, **self.facts_if(cond, False)}
        self.refine = entry
        self.refine, dead = self.narrowed(cond, False)
        self.dead += dead
        orelse, se = self.block(s.orelse, state)
        self.dead -= dead
        else_facts, else_refine = self.facts, self.refine
        if st is None and se is None:
            self.refine = entry
        elif st is None:
            self.refine = else_refine
        elif se is None:
            self.refine = then_refine
        else:
            self.refine = {k: (min(v[0], else_refine[k][0]), max(v[1], else_refine[k][1]))
                           for k, v in then_refine.items() if k in else_refine}
        if st is None and se is None:
            self.facts = base
        elif st is None:
            self.facts = else_facts
        elif se is None:
            self.facts = then_facts
        else:
            self.facts = {k: v for k, v in then_facts.items() if else_facts.get(k) == v}
        return dataclasses.replace(s, cond=cond, then=then, orelse=orelse), _meet(st, se)

    def loop_facts(self, body: object, extra: tuple[str, ...] = ()) -> dict[str, str]:
        """The facts that hold at the head of every iteration: those of the entry that nothing in
        the loop body (or its loop var) assigns."""
        killed = _assigned_names(body) | set(extra)
        return {k: v for k, v in self.facts.items() if k not in killed}

    def s_While(self, s: While, state: _State):
        head = self.loop_facts(s.body)
        self.facts = head
        killed = _assigned_names(s.body)
        head_refine = {k: v for k, v in self.refine.items() if k not in killed}
        self.refine = head_refine
        cond = self.cond(s.cond, state, "while")
        loop = _Loop(None, None)
        self.facts = {**head, **self.facts_if(cond, True)}
        self.refine, dead = self.narrowed(cond, True)
        self.dead += dead
        self.loops.append(loop)
        # Assignments only add names, so the loop-head state is the entry state (the meet of the
        # entry, end-of-body and continue states equals the entry state).
        body, _ = self.block(s.body, state)
        self.loops.pop()
        self.dead -= dead
        self.refine = dict(head_refine)
        self.facts = dict(head)       # leaving by the condition or a break: no more than the head's facts
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
            self.bottom = False
            (slo, shi), (elo, ehi) = self.interval(start), self.interval(stop)
            loop.bottom, self.bottom = self.bottom, False
            if step.value > 0:
                lo = slo
                hi = max(lo, ehi - 1)
            else:
                hi = shi
                lo = min(hi, elo + 1)
            loop.interval = (max(lo, _I64_MIN), min(hi, _I64_MAX))
        self.loops.append(loop)
        head = self.loop_facts(s.body, (var,))
        self.facts = dict(head)
        killed = _assigned_names(s.body) | {var}
        head_refine = {k: v for k, v in self.refine.items() if k not in killed}
        self.refine = head_refine
        body, _ = self.block(s.body, None if state is None else state | {var})
        self.loops.pop()
        self.refine = dict(head_refine)
        self.facts = dict(head)
        # Zero iterations leave `var` (and everything the body assigns) as it was: the state after
        # the loop is the entry state, met with every break state (each a superset of it).
        after: _State = state
        for br in loop.breaks:
            after = _meet(after, br)
        par = self._par_info(s, step_ok, body, loop, state)
        if s.par is not None and s.par != par:
            self.error(PAR_LOOP, f"ForRange over '{var}' declares par={s.par!r}, but the verifier "
                                 f"computes {par!r} (SPEC N-15)")
        return dataclasses.replace(s, start=start, stop=stop, body=body, par=par), after

    def _par_info(self, s: ForRange, step_ok: bool, body: tuple, loop: _Loop,
                  state: _State = None) -> ParInfo | None:
        """SPEC N-15 steps 1 and 2: the ParInfo of this loop, or None (see "Parallel loops" above)."""
        if self.fn.parallel is not True or not step_ok or loop.bounds_array is None:
            return None
        if self.diags or not isinstance(body, tuple) or not body:
            return None
        var = s.var
        stored: list[str] = []
        read: list[str] = []
        weight = [0]

        def array_ok(name: str, want: Type = Type.F64_ARRAY) -> bool:
            t = self.arrays.get(name) or self.larrays.get(name)
            return t is want and name not in self.buffers

        def at_var(ix: object, proven: object) -> bool:
            return proven is True and type(ix) is Local and ix.name == var and ix.type is Type.I64

        def ok(e: object) -> bool:
            weight[0] += 1
            t = type(e)
            if t is Const:
                return e.type is Type.F64 and type(e.value) is float
            if t is Local:
                return (e.type is Type.F64 and e.name != var and e.name not in self.arrays
                        and e.name not in self.larrays and (e.name in self.scalars or e.name in self.entry))
            if t is ToFloat:
                o = e.operand
                return (e.type is Type.F64 and getattr(o, "type", None) is Type.I64
                        and ((type(o) is Local and o.name not in self.arrays
                              and o.name not in self.larrays)
                             or (type(o) is Const and type(o.value) is int)))
            if t is Index:
                if e.type is Type.F64 and array_ok(e.array) and at_var(e.index, e.proven):
                    read.append(e.array)
                    return True
                return False
            if t is BinOp:
                return (e.type is Type.F64 and e.left.type is Type.F64 and e.right.type is Type.F64
                        and e.op in (BinOpKind.ADD, BinOpKind.SUB, BinOpKind.MUL) and not e.np
                        and e.inplace is False and ok(e.left) and ok(e.right))
            if t is UnaryOp:
                return e.type is Type.F64 and e.op is UnaryOpKind.NEG and ok(e.operand)
            return False

        if type(body[0]) is Assign:
            return self._par_reduce(s, body, state, weight, array_ok, at_var)
        for st in body:
            if type(st) is not StoreIndex or not array_ok(st.array) or not at_var(st.index, st.proven):
                return None
            weight[0] += 2
            if not ok(st.value) or st.value.type is not Type.F64:
                return None
            stored.append(st.array)
        return ParInfo(tuple(sorted(set(stored))), tuple(sorted(set(read))), weight[0])

    def _par_reduce(self, s: ForRange, body: tuple, state: _State, weight: list, array_ok,
                    at_var) -> ParInfo | None:
        """SPEC N-15 step 2: a body of only `acc = acc op e` (op ADD or MUL, either operand order), each
        `acc` a distinct i64 local of this function (not a loop variable, array or entry global) that
        is definitely assigned before the loop and appears nowhere else in the body, and `e` built
        from i64 Const, i64 Local (the loop variable, or any scalar the body does not assign), `b[var]`
        proven on an i64 non-buffer list or local array, and i64 + - * . Any of those nodes can overflow:
        the runtime then discards the region and reruns the loop sequentially. No store is allowed in
        the same loop (a rerun then needs no restore)."""
        var = s.var
        if state is None:
            return None
        accs: dict[str, str] = {}
        for st in body:
            if (type(st) is not Assign or type(st.value) is not BinOp or st.value.type is not Type.I64
                    or st.value.op not in (BinOpKind.ADD, BinOpKind.MUL) or st.value.np
                    or st.target in accs):
                return None
            accs[st.target] = st.value.op.name
        for acc in accs:
            if (self.scalars.get(acc) is not Type.I64 or acc == var or acc in self.arrays
                    or acc in self.larrays or acc in self.entry or acc not in state):
                return None
        read: list[str] = []

        def ok(e: object) -> bool:
            weight[0] += 1
            t = type(e)
            if t is Const:
                return e.type is Type.I64 and type(e.value) is int
            if t is Local:
                return (e.type is Type.I64 and e.name not in accs and e.name not in self.arrays
                        and e.name not in self.larrays
                        and (e.name == var or e.name in self.scalars or e.name in self.entry))
            if t is Index:
                if e.type is Type.I64 and array_ok(e.array, Type.I64_ARRAY) and at_var(e.index, e.proven):
                    read.append(e.array)
                    return True
                return False
            if t is BinOp:
                return (e.type is Type.I64 and e.left.type is Type.I64 and e.right.type is Type.I64
                        and e.op in (BinOpKind.ADD, BinOpKind.SUB, BinOpKind.MUL) and not e.np
                        and ok(e.left) and ok(e.right))
            return False

        for st in body:
            weight[0] += 2
            b = st.value
            if type(b.left) is Local and b.left.name == st.target and b.left.type is Type.I64:
                other = b.right
            elif type(b.right) is Local and b.right.name == st.target and b.right.type is Type.I64:
                other = b.left
            else:
                return None
            if not ok(other):
                return None
        return ParInfo((), tuple(sorted(set(read))), weight[0], tuple(sorted(accs.items())))

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
        self.reassigned = frozenset(_assigned_names(fn.body))
        assigned: dict[str, list[object]] = {}
        _assigned_values(fn.body, assigned)
        loop_vars = _loop_vars(fn.body)
        self.eligible = frozenset(
            n for n in assigned
            if self.scalars.get(n) is Type.I64 and n not in params and n not in loop_vars
            and n not in self.arrays and n not in self.larrays)
        self.new_only = self.new_only_locals(params)
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
        virtual = _virtual_locals(fn, body) if not self.diags else {}
        if fn.virtual and set(fn.virtual) != set(virtual):
            self.error(VIRTUAL, f"virtual locals {sorted(fn.virtual)} declared, but only "
                                f"{sorted(virtual)} are provably virtual (SPEC N-12a)")
        return dataclasses.replace(fn, body=body, virtual=tuple(sorted(virtual)))


# --- virtual objects (SPEC N-12a) ----------------------------------------------------------------------

# The expression nodes a function may contain for its objects to be virtual: none of them runs user
# code, reads a global or an attribute, or calls anything (a Call can redo in the interpreter), so
# nothing can observe a live virtual object. OBJ BinOp is excluded in `_virtual_locals`.
_VIRTUAL_OK = frozenset({Const, Local, BinOp, UnaryOp, Compare, And, Or, ToFloat, ToInt, Box, Unbox,
                         MathCall, Len, Index, NewArray, CopyArray, Is, IsExact, CheckExact,
                         FieldGet, New})
_VIRTUAL_STMTS = frozenset({Assign, StoreIndex, If, While, ForRange, Return, Break, Continue})


def _exprs_of(node: object):
    """Every ir expression directly under `node` (an expression or statement), in field order."""
    for f in dataclasses.fields(node):
        v = getattr(node, f.name)
        for x in (v if isinstance(v, tuple) else (v,)):
            if isinstance(x, ir.Expr):
                yield x


def _virtual_locals(fn: Function, body: tuple) -> dict[str, str]:
    """local -> class of the virtual objects of `fn` (SPEC N-12a), or {}. A local is virtual when
    `fn` is pure and contains only `_VIRTUAL_OK` nodes (no unknown code at all), and the local is
    never a parameter, is only assigned `New(C, args)` of one class with args that cannot run a
    finalizer when dropped (Box of a scalar, a str/None Const, an OBJ parameter), and is read only as
    the object of a `FieldGet` of C. Anything else stays an ordinary object (N-11)."""
    if fn.pure is not True or not isinstance(body, tuple):
        return {}
    params = {p.name for p in fn.params}
    safe_reads: dict[str, list[str]] = {}
    other_reads: set[str] = set()
    ok = True

    def expr(e: object) -> None:
        nonlocal ok
        if type(e) not in _VIRTUAL_OK or (type(e) is BinOp and e.type is Type.OBJ):
            ok = False
            return
        if type(e) is FieldGet and type(e.obj) is Local:
            safe_reads.setdefault(e.obj.name, []).append(e.cls)
            return
        if type(e) is Local:
            other_reads.add(e.name)
        for x in _exprs_of(e):
            expr(x)

    def block(stmts: object) -> None:
        nonlocal ok
        if not isinstance(stmts, tuple):
            ok = False
            return
        for st in stmts:
            if type(st) not in _VIRTUAL_STMTS:
                ok = False
                return
            for x in _exprs_of(st):
                expr(x)
            for nested in (getattr(st, "then", ()), getattr(st, "orelse", ()), getattr(st, "body", ())):
                block(nested)

    block(body)
    if not ok:
        return {}
    assigned: dict[str, list[object]] = {}
    _assigned_values(body, assigned)
    out: dict[str, str] = {}
    for name, values in assigned.items():
        if (fn.locals.get(name) is not Type.OBJ or name in params or name in other_reads
                or name not in safe_reads):
            continue
        if not all(type(v) is New and isinstance(v.cls, str) for v in values):
            continue
        classes = {v.cls for v in values}
        if len(classes) != 1 or set(safe_reads[name]) != classes:
            continue
        if not all(_virtual_arg(a, params) for v in values for a in v.args):
            continue
        out[name] = next(iter(classes))
    return out


def _virtual_arg(a: object, params: set[str]) -> bool:
    if type(a) is Box:
        return a.operand.type in ir.SCALARS
    if type(a) is Const:
        return a.type is Type.OBJ
    return type(a) is Local and a.type is Type.OBJ and a.name in params


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
            elif cls in (While, ForRange, ForObj):
                _assigned(st.body, counts)
    return counts


def _assigned_values(body: object, out: dict[str, list[object]]) -> None:
    """Every Assign value in `body`, by target (tolerates a malformed body)."""
    if isinstance(body, tuple):
        for st in body:
            cls = type(st)
            if cls is Assign:
                out.setdefault(st.target, []).append(st.value)
            elif cls is If:
                _assigned_values(st.then, out)
                _assigned_values(st.orelse, out)
            elif cls in (While, ForRange):
                _assigned_values(st.body, out)
            elif cls is ForObj:
                for t in st.targets if isinstance(st.targets, tuple) else ():
                    out.setdefault(t, []).append(st)   # not a New: ends form (e) for the target
                _assigned_values(st.body, out)


def _loop_vars(body: object) -> set[str]:
    out: set[str] = set()
    if isinstance(body, tuple):
        for st in body:
            cls = type(st)
            if cls is If:
                out |= _loop_vars(st.then) | _loop_vars(st.orelse)
            elif cls in (While, ForObj):
                out |= _loop_vars(st.body)
            elif cls is ForRange:
                out.add(st.var)
                out |= _loop_vars(st.body)
    return out


def _assigned_names(body: object) -> set[str]:
    """Every local assigned anywhere in `body` by an Assign or as a ForRange var."""
    out: set[str] = set()
    if isinstance(body, tuple):
        for st in body:
            cls = type(st)
            if cls is Assign:
                out.add(st.target)
            elif cls is If:
                out |= _assigned_names(st.then) | _assigned_names(st.orelse)
            elif cls is While:
                out |= _assigned_names(st.body)
            elif cls is ForObj:
                out |= set(st.targets) if isinstance(st.targets, tuple) else set()
                out |= _assigned_names(st.body)
            elif cls is ForRange:
                out.add(st.var)
                out |= _assigned_names(st.body)
    return out


def _plain_str(v: object) -> bool:
    """A str the generated C source carries exactly: it encodes to UTF-8 and has no NUL."""
    if type(v) is not str or "\0" in v:
        return False
    try:
        v.encode("utf-8")
    except UnicodeEncodeError:
        return False
    return True


def _meet_interval(a: tuple[int, int], b: tuple[int, int] | None) -> tuple[int, int]:
    """`a` narrowed by `b`; an empty meet (unreachable code) leaves `a`, which is still sound."""
    if b is None:
        return a
    lo, hi = max(a[0], b[0]), min(a[1], b[1])
    return (lo, hi) if lo <= hi else a


def _narrow(facts: dict[str, tuple[int, int]], name: str, iv: tuple[int, int] | None) -> None:
    if iv is None:
        return
    cur = facts.get(name)
    facts[name] = iv if cur is None else _meet_interval(cur, iv)


def _bound(kind: CompareKind, other: tuple[int, int]) -> tuple[int, int] | None:
    """The interval of `x` when `x <kind> e` is true and `e` lies in `other`."""
    lo, hi = other
    if kind is CompareKind.LT:
        out = (_I64_MIN, hi - 1)
    elif kind is CompareKind.LE:
        out = (_I64_MIN, hi)
    elif kind is CompareKind.GT:
        out = (lo + 1, _I64_MAX)
    elif kind is CompareKind.GE:
        out = (lo, _I64_MAX)
    elif kind is CompareKind.EQ:
        out = (lo, hi)
    else:
        return None
    return out if out[0] <= out[1] else None


def _div_interval(op: BinOpKind, a: tuple[int, int], b: tuple[int, int]) -> tuple[int, int]:
    """The result interval of I64 FLOORDIV / MOD (an operation that completes: a zero divisor
    raises and -2**63 // -1 deopts, so neither yields a value). MOD: [0, hi(b) - 1] for a positive
    divisor, [lo(b) + 1, 0] for a negative one, else |result| < max |b|. FLOORDIV: for a divisor
    that keeps its sign the extremes are at the corners; else |result| <= max |a|."""
    if op is BinOpKind.MOD:
        if b[0] > 0:
            return (0, b[1] - 1)
        if b[1] < 0:
            return (b[0] + 1, 0)
        d = max(abs(b[0]), abs(b[1]), 1)
        return (-(d - 1), d - 1)
    if b[0] > 0 or b[1] < 0:
        corners = [x // y for x in a for y in b]
        return (min(corners), max(corners))
    m = max(abs(a[0]), abs(a[1]))
    return (-m, m)


def _join_interval(a: tuple[int, int] | None, b: tuple[int, int]) -> tuple[int, int]:
    return b if a is None else (min(a[0], b[0]), max(a[1], b[1]))


def _exact(op: BinOpKind, a: tuple[int, int], b: tuple[int, int]) -> tuple[int, int]:
    """The exact (unbounded) result interval of ADD/SUB/MUL on operand intervals."""
    if op is BinOpKind.ADD:
        return a[0] + b[0], a[1] + b[1]
    if op is BinOpKind.SUB:
        return a[0] - b[1], a[1] - b[0]
    products = (a[0] * b[0], a[0] * b[1], a[1] * b[0], a[1] * b[1])
    return min(products), max(products)


def _prove(g: Function, sigs: dict[str, Function], classes: dict[str, ClassDecl]
           ) -> tuple[_Checker, Function]:
    """Check `g`, first computing the interval of every eligible local (Local variables and
    guards): walks of the body repeat, each reading the intervals found by the one before, until
    they stop growing (a bound still growing from round `_WIDEN_FROM` jumps to the i64 limit). The
    result is a fixed point, so every assigned value lies inside its local's interval. If it does
    not settle in `_ROUNDS` walks, every eligible local is full range."""
    known: dict[str, tuple[int, int]] = {}
    for rnd in range(_ROUNDS):
        c = _Checker(g, sigs, classes, known, True)
        rebuilt = c.run()
        if not c.eligible:
            return c, rebuilt
        grown = dict(known)
        for name in c.eligible:
            iv = c.contrib.get(name)
            if iv is None:
                continue
            old = known.get(name)
            if old is None:
                grown[name] = iv
                continue
            lo = old[0] if iv[0] >= old[0] else (_I64_MIN if rnd >= _WIDEN_FROM else iv[0])
            hi = old[1] if iv[1] <= old[1] else (_I64_MAX if rnd >= _WIDEN_FROM else iv[1])
            grown[name] = (lo, hi)
        if grown == known:
            absent = c.eligible - set(known)
            if not absent:
                return c, rebuilt
            for name in absent:         # never given an interval: nothing is known about it
                grown[name] = _FULL
        known = grown
    c = _Checker(g, sigs, classes, {}, False)
    return c, c.run()


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
    classes, skipped_classes = _check_classes(module, set(named), diags)
    for name, g in sigs.items():
        problem = _owner_problem(g, classes)
        if problem is not None:
            reject(name, g.source_line, CLASS, problem)

    checked: dict[str, Function] = {}
    callees: dict[str, set[str]] = {}
    open_why: dict[str, str] = {}          # a function that runs user code -> why
    slow_why: dict[str, str] = {}          # a function whose slow paths can run user code -> why
    for name, g in sigs.items():
        c, rebuilt = _prove(g, sigs, classes)
        for d in c.diags:
            reject(d.function, d.source_line, d.rule, d.message)
        checked[name] = rebuilt
        callees[name] = c.callees
        if c.opens:
            open_why[name] = c.opens[0]
        if c.slow_user:
            slow_why[name] = c.slow_user[0]

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

    # ArrayParam (#203): the list is copied in and written back, so nothing may run user code while
    # the copy is live: user code could read or change the list. Closed as above, and also no
    # FieldGet/New, whose slow paths run user code after a class change, anywhere in the call tree.
    array_why = dict(open_why)
    for name, why in slow_why.items():
        array_why.setdefault(name, why)
    changed = True
    while changed:
        changed = False
        for name, cs in callees.items():
            if name not in array_why:
                bad = sorted(c for c in cs if c in array_why)
                if bad:
                    array_why[name] = f"calls '{bad[0]}', which can run user code ({array_why[bad[0]]})"
                    changed = True
    for name, g in sigs.items():
        if name in array_why and any(isinstance(p, ArrayParam) for p in g.params):
            reject(name, g.source_line, ARRAY_OPEN,
                   f"holds a list copy-in but can run user code: {array_why[name]}")

    # A call cycle (direct or mutual recursion) is legal: every compiled impl function counts its
    # frame at entry (tp_enter_call), so recursion ends in RecursionError where CPython raises it
    # and the C stack cannot overflow (issue #57). Purity and may_deopt flow through the cycle as
    # through any Call: each flag is proved on the function's own body against its callees'
    # declared flags, so a cycle through a may_deopt callee is legal only in a pure function.

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
    for name, reason in {**skipped_classes, **reasons}.items():
        skipped.setdefault(name, reason)
    return Module(module.name, kept, skipped, tuple(classes.values())), diags


def _owner_problem(g: Function, classes: dict[str, ClassDecl]) -> str | None:
    """Why `g.owner` is not a legal method declaration (ir.Function.owner), or None."""
    owner = g.owner
    if owner is None:
        return None
    if not isinstance(owner, str) or owner not in classes:
        return f"owner {owner!r} is not a compiled class of this module"
    prefix = f"{owner}."
    if not (g.name.startswith(prefix) and g.name[len(prefix):].isidentifier()):
        return f"a method of '{owner}' must be named '{owner}.<identifier>', not '{g.name}'"
    first = g.params[0] if g.params else None
    if not (type(first) is Param and first.type is Type.OBJ and first.cls == owner
            and first.optional is False):
        return f"the first parameter of method '{g.name}' must be `self` guarded exactly '{owner}'"
    if classes[owner].fields and g.name[len(prefix):] in classes[owner].fields:
        return f"method '{g.name}' has the name of a slot of '{owner}'"
    return None


def _check_classes(module: Module, function_names: set[str], diags: list[Diagnostic]
                   ) -> tuple[dict[str, ClassDecl], dict[str, str]]:
    """The valid ClassDecls of the module by name, and the reason for each invalid one."""
    declared = module.classes if isinstance(module.classes, tuple) else ()
    names: dict[str, int] = {}
    for c in declared:
        if type(c) is ClassDecl and isinstance(c.name, str):
            names[c.name] = names.get(c.name, 0) + 1
    valid: dict[str, ClassDecl] = {}
    bad: dict[str, str] = {}
    for c in declared:
        problem = None
        if type(c) is not ClassDecl:
            problem = f"{type(c).__name__} is not an ir ClassDecl"
        elif not (isinstance(c.name, str) and c.name.isidentifier()):
            problem = f"class name {c.name!r} is not an identifier"
        elif names[c.name] > 1:
            problem = f"class '{c.name}' is declared {names[c.name]} times"
        elif c.name in function_names:
            problem = f"class '{c.name}' has the name of a function of the module"
        elif not (isinstance(c.fields, tuple) and all(isinstance(f, str) and f.isidentifier()
                                                      for f in c.fields)):
            problem = f"fields of class '{c.name}' are not a tuple of identifiers"
        elif len(set(c.fields)) != len(c.fields):
            problem = f"class '{c.name}' lists a field twice"
        elif type(c.trivial_init) is not bool:
            problem = f"`trivial_init` of class '{c.name}' is not a bool"
        name = getattr(c, "name", repr(c))
        if problem is None:
            valid[c.name] = c
        else:
            d = Diagnostic(str(name), CLASS, problem, 0)
            if d not in diags:
                diags.append(d)
            bad.setdefault(str(name), f"{CLASS}: {problem}")
    return valid, bad
