"""TypedPython typed IR (design §4.3 stage 2, #41).

The IR sits between the front end (AST + Pyrefly types → IR, `frontend.py`) and the back end
(IR → C, `cgen.py`). Both sides are written against this file; it is the contract.

The governing rule is INTENT §1.4: **compiled code computes what CPython computes.** The IR
encodes that rule, so a back end that implements each node as specified below cannot change a
result:

- Every node says what it does in CPython terms. Where a C operation differs from CPython (integer
  overflow, floor division and modulo of negatives, true division of large ints, int/float
  comparison, list indexing), the node is defined by the CPython behaviour and the back end must
  implement that behaviour, not the C one.
- When the C path cannot produce CPython's result, the IR says **deopt**: the compiled call gives
  up and the whole call is redone by the interpreted function. A deopt is allowed only where
  redoing is unobservable: before any effect (`Function.guards`, evaluated at entry), or anywhere
  inside a `pure` function. The front end guarantees that an impure function contains no node
  that can deopt (`Function.may_deopt` is False for impure functions); the back end may assert it.
- Exceptions are CPython's exceptions, with CPython's messages (`ZeroDivisionError("division by
  zero")`, `IndexError("list index out of range")`, `ValueError("math domain error")`, ...).
  The traceback differs (there is no Python frame for compiled code); that is the one accepted
  difference, as for every C extension.

Safety (`verify.py`, maintainer decision 2026-10-03: "IR 단계에서 안전성을 증명하도록 해"). Before any
C is generated, the verifier proves on the IR that the C will be memory-safe and keep the rules
above; **a function it cannot prove is not compiled** — it stays interpreted and the reason is
reported as a diagnostic. What it proves:

- definite assignment: no local is read before it is assigned on every path;
- types: every node's operands have the types the node requires; no implicit conversion;
- deopt placement: no node that can deopt in an impure function (`may_deopt` consistent);
- arrays: every `Index`/`StoreIndex` is either proved in bounds (and marked `proven`) or keeps its
  runtime check; array params are used only by `Index`/`StoreIndex`/`Len`;
- ownership: OBJ values are created only by nodes that return a new reference (Box, PyCall, OBJ
  BinOp) and the back end releases them by one rule (every OBJ local owns its reference; assigning
  releases the old one; every exit releases all); the IR has no node that frees, aliases a
  borrowed reference, or reads a possibly-NULL object, so use-after-free and NULL dereference have
  no IR form.

The generated C touches memory and `PyObject`s only through the runtime helpers (`tp_runtime.h`),
which are tested separately; AddressSanitizer/UBSan runs, differential tests against the
interpreter, and fuzzing are the safety net outside the proof.

Module shape (back end). The extension module executes the module's original source in its own
namespace first, so every name, class and global is the interpreted one and lives in one
namespace. Then each compiled function `f` replaces the global `f` with a C function, and the
original stays reachable as `__typedpython_interpreted__[f]` for deopt. Because there is one
namespace, interpreted code that calls `f` gets the compiled `f`, and a deopted call sees the same
globals and classes as compiled code.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum


# --- types ---------------------------------------------------------------------------------------

class Type(Enum):
    """The C representation of a value. Python semantics are attached per type below."""

    I64 = "i64"            # a Python int known to fit in [-2**63, 2**63-1]; arithmetic is checked
    F64 = "f64"            # a Python float (IEEE double, exactly as CPython)
    BOOL = "bool"          # a Python bool
    NONE = "none"          # the value None (only as a return type)
    OBJ = "obj"            # any Python object, held as a strong reference (PyObject*)
    F64_ARRAY = "f64[]"    # a list[float] parameter lowered to a native array (see ArrayParam)
    I64_ARRAY = "i64[]"    # a list[int] parameter lowered to a native array (see ArrayParam)


SCALARS = (Type.I64, Type.F64, Type.BOOL)
ARRAYS = (Type.F64_ARRAY, Type.I64_ARRAY)


# --- expressions ---------------------------------------------------------------------------------

@dataclass(frozen=True)
class Expr:
    type: Type


@dataclass(frozen=True)
class Const(Expr):
    """A literal. `value` is a Python int/float/bool/None matching `type`."""

    value: int | float | bool | None


@dataclass(frozen=True)
class Local(Expr):
    """Reading a local variable or parameter (always definitely assigned: the front end checks)."""

    name: str


class BinOpKind(Enum):
    ADD = "+"
    SUB = "-"
    MUL = "*"
    TRUEDIV = "/"
    FLOORDIV = "//"
    MOD = "%"


@dataclass(frozen=True)
class BinOp(Expr):
    """Arithmetic with CPython's result. Both operands have the same scalar type (the front end
    inserts `ToFloat` for mixed int/float, as CPython converts the int).

    I64 (result I64, except TRUEDIV → F64):
      ADD/SUB/MUL — checked; overflow → deopt.
      FLOORDIV/MOD — Python floor semantics (result sign follows the divisor for MOD); divisor 0
        → ZeroDivisionError("integer division or modulo by zero") for FLOORDIV and
        ZeroDivisionError("integer modulo by zero") for MOD (CPython 3.13/3.14 messages; the
        runtime's differential tests pin them); -2**63 // -1 overflows → deopt.
      TRUEDIV — CPython rounds int/int correctly; when both |operands| <= 2**53 the C double
        division is exact-rounded too, otherwise → deopt. Divisor 0 → ZeroDivisionError("division
        by zero").
    F64:
      ADD/SUB/MUL — IEEE, as CPython.
      TRUEDIV — divisor 0.0 (either sign) → ZeroDivisionError("float division by zero").
      FLOORDIV/MOD — CPython's float floor division and modulo (sign of divisor, `-0.0` cases),
        divisor 0.0 → ZeroDivisionError("float floor division by zero") / ("float modulo by
        zero").
    OBJ: CPython's generic operation on objects (PyNumber_*), never deopts.
    """

    op: BinOpKind
    left: Expr
    right: Expr


class UnaryOpKind(Enum):
    NEG = "-"
    POS = "+"
    NOT = "not"


@dataclass(frozen=True)
class UnaryOp(Expr):
    """NEG of I64 is checked (-(-2**63) → deopt). NOT takes a BOOL and gives a BOOL."""

    op: UnaryOpKind
    operand: Expr


class CompareKind(Enum):
    EQ = "=="
    NE = "!="
    LT = "<"
    LE = "<="
    GT = ">"
    GE = ">="


@dataclass(frozen=True)
class Compare(Expr):
    """Result BOOL. Chains (`a < b < c`) are lowered by the front end into `And` with each middle
    operand evaluated once (via a temporary). Mixed I64/F64 compares **exactly**, as CPython does —
    not by converting the int to double (2**53+1 != float(2**53)). NaN compares as in IEEE."""

    op: CompareKind
    left: Expr
    right: Expr


@dataclass(frozen=True)
class And(Expr):
    """`a and b` on BOOL operands only (result BOOL), short-circuit. The front end does not lower
    `and`/`or` on non-bool operands (they return an operand, not a bool)."""

    left: Expr
    right: Expr


@dataclass(frozen=True)
class Or(Expr):
    left: Expr
    right: Expr


@dataclass(frozen=True)
class ToFloat(Expr):
    """`float(x)` of an I64 (round-to-nearest-even, as CPython's int→float), or the implicit
    int→float conversion of mixed arithmetic. Type F64."""

    operand: Expr


@dataclass(frozen=True)
class Box(Expr):
    """A scalar or array element converted to a Python object (type OBJ): I64 → int, F64 → float,
    BOOL → True/False. Used when a typed value flows into OBJ code."""

    operand: Expr


@dataclass(frozen=True)
class Unbox(Expr):
    """An OBJ known by the front end to be exactly int/float/bool converted to a scalar. For I64,
    a value outside the i64 range → deopt (so only legal in a pure function or a guard)."""

    operand: Expr


class MathFunc(Enum):
    SQRT = "sqrt"
    EXP = "exp"
    LOG = "log"
    SIN = "sin"
    COS = "cos"
    TAN = "tan"
    ATAN2 = "atan2"
    FABS = "fabs"
    HYPOT = "hypot"


@dataclass(frozen=True)
class MathCall(Expr):
    """`math.<func>(x...)` on F64, type F64, with the stdlib `math` module's errors:
    a domain error → ValueError("math domain error"), a range overflow →
    OverflowError("math range error") (CPython's math_1/math_2 rules on errno/inf/nan)."""

    func: MathFunc
    args: tuple[Expr, ...]


@dataclass(frozen=True)
class Call(Expr):
    """A direct call of another compiled function in the same module (C to C). If the callee may
    deopt, the caller must be pure (the deopt propagates and the outermost compiled call is
    redone); the front end otherwise emits `PyCall`."""

    function: str
    args: tuple[Expr, ...]


@dataclass(frozen=True)
class PyCall(Expr):
    """Calling a Python-level callable found by global name in the module namespace (type OBJ),
    with OBJ arguments. Makes the function impure."""

    function: str
    args: tuple[Expr, ...]


@dataclass(frozen=True)
class Len(Expr):
    """`len(array)` of an array parameter. Type I64."""

    array: str


@dataclass(frozen=True)
class Index(Expr):
    """`array[i]` of an array parameter, with list semantics: a negative index counts from the
    end; out of range → IndexError("list index out of range"). Element type F64 or I64."""

    array: str
    index: Expr
    # Set only by `verify` when it has proved 0 <= index < len for the non-negative case (e.g. a
    # `ForRange` index over range(len(array))); then the back end may omit the bounds check.
    # Never set by the front end. Unproved accesses keep the full check.
    proven: bool = False


# --- statements ----------------------------------------------------------------------------------

@dataclass(frozen=True)
class Stmt:
    pass


@dataclass(frozen=True)
class Assign(Stmt):
    target: str
    value: Expr


@dataclass(frozen=True)
class StoreIndex(Stmt):
    """`array[i] = value` with list semantics (negative index; out of range →
    IndexError("list assignment index out of range")). Marks the element dirty for write-back."""

    array: str
    index: Expr
    value: Expr
    proven: bool = False      # as in Index


@dataclass(frozen=True)
class ExprStmt(Stmt):
    value: Expr


@dataclass(frozen=True)
class If(Stmt):
    cond: Expr                 # BOOL
    then: tuple[Stmt, ...]
    orelse: tuple[Stmt, ...] = ()


@dataclass(frozen=True)
class While(Stmt):
    cond: Expr                 # BOOL
    body: tuple[Stmt, ...]


@dataclass(frozen=True)
class ForRange(Stmt):
    """`for var in range(start, stop, step)`: `step` is a non-zero I64 Const; `start`/`stop` are
    evaluated once before the loop, as range() does. `var` is I64 and is not assigned in the body
    (the front end checks), so it never overflows: it stays between start and stop. After the loop
    `var` keeps its last value, and if the loop ran zero times it keeps its previous binding — the
    front end rejects reading it after the loop unless it was definitely assigned before."""

    var: str
    start: Expr
    stop: Expr
    step: Const
    body: tuple[Stmt, ...]


@dataclass(frozen=True)
class Return(Stmt):
    value: Expr | None = None


@dataclass(frozen=True)
class Break(Stmt):
    pass


@dataclass(frozen=True)
class Continue(Stmt):
    pass


# --- functions and modules -----------------------------------------------------------------------

@dataclass(frozen=True)
class Param:
    name: str
    type: Type


@dataclass(frozen=True)
class ArrayParam:
    """How a `list[float]`/`list[int]` parameter becomes a native array, keeping list semantics:

    At entry (guards, before any effect): the argument is exactly a `list`, every element is exactly
    `float` (F64_ARRAY) or exactly `int` within i64 (I64_ARRAY), and no two array parameters are
    the same list object (aliasing would make separate copies diverge). Then the elements are copied
    into a C array. The function body only indexes, stores into and takes `len` of it (no calls that
    could observe the list, no appends: the front end checks), so the list and the array cannot
    diverge observably. On **every** exit — return, exception, or deopt — dirty elements are
    written back as new float/int objects before control leaves the function, so CPython's
    partial-update-then-raise behaviour is preserved, and a deopt redo starts from the same list
    state the interpreted function would see... which is only correct because a function with array
    stores is impure and therefore can never deopt after entry (`Function.may_deopt` is False).
    """

    name: str
    type: Type        # F64_ARRAY or I64_ARRAY
    stored: bool      # whether the body stores into it (write-back needed)


@dataclass(frozen=True)
class Function:
    """One compiled function. `params` lists every positional parameter in order (scalar Param,
    ArrayParam, or Param of type OBJ for anything else). Keyword-only, *args, **kwargs, defaults
    and decorators other than `compiled` are not compiled in this stage (the function stays
    interpreted; `frontend` records why).

    `pure`: no effect outside its own locals — no stores into anything but locals, no PyCall, no
    attribute reads (a property can have effects), only `Call`s to pure functions. Only a pure
    function may contain deopting nodes after entry.

    Entry guards are generated by the back end from the params: exact runtime type per scalar/array
    param (`type(x) is float`, not isinstance — CPython would compute with the int or the subclass
    it was given), i64 range for I64, the ArrayParam checks. A failing guard → deopt (always safe:
    nothing has happened yet). Wrong arity or keyword use → deopt too, so CPython raises its own
    TypeError.
    """

    name: str
    params: tuple[Param | ArrayParam, ...]
    returns: Type
    locals: dict[str, Type]
    body: tuple[Stmt, ...]
    pure: bool
    may_deopt: bool
    source_line: int


@dataclass(frozen=True)
class Module:
    name: str
    functions: tuple[Function, ...]
    # Functions the front end left interpreted, with the reason (for `demo` and the coverage table).
    skipped: dict[str, str] = field(default_factory=dict)
