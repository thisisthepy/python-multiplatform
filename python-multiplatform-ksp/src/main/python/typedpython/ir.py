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
above; **a function it cannot prove is not compiled**, it stays interpreted and the reason is
reported as a diagnostic. What it proves:

- definite assignment: no local is read before it is assigned on every path;
- types: every node's operands have the types the node requires; no implicit conversion;
- deopt placement: no node that can deopt in an impure function (`may_deopt` consistent);
- arrays: every `Index`/`StoreIndex` is either proved in bounds (and marked `proven`) or keeps its
  runtime check; array params are used only by `Index`/`StoreIndex`/`Len`;
- ownership: OBJ values are created only by nodes that return a new reference (Box, Global,
  GetAttr, CallObject, OBJ BinOp, GetItem, Format, BuildString, NewList, NewDict, a str Const) and
  the back end releases them by one rule (every OBJ local owns its reference; assigning
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

from dataclasses import dataclass, field, fields
from enum import Enum


# --- types ---------------------------------------------------------------------------------------

class Type(Enum):
    """The C representation of a value. Python semantics are attached per type below."""

    I64 = "i64"            # a Python int known to fit in [-2**63, 2**63-1]; arithmetic is checked
    F64 = "f64"            # a Python float (IEEE double, exactly as CPython)
    BOOL = "bool"          # a Python bool
    NONE = "none"          # the value None (only as a return type)
    OBJ = "obj"            # any Python object, held as a strong reference (PyObject*)
    INT = "int"            # a Python int that is a native i64 or, once promoted, a PyLong (SPEC N-13)
    F64_ARRAY = "f64[]"    # a list[float] parameter (ArrayParam) or a local array (NewArray/CopyArray)
    I64_ARRAY = "i64[]"    # a list[int] parameter (ArrayParam) or a local array (NewArray/CopyArray)
    BOOL_ARRAY = "bool[]"  # a numpy np.bool_ buffer parameter only (1-byte items, #261)
    STR = "str"            # AOT mode only (tier S1, PR-S1a): a Python str, a counted `tpa_str` (runtime/tp_aot_str.h)
    STR_LIST = "list[str]"  # AOT mode only (tier S1b): a list of str, a counted `tpa_str_list` (fixed length until S2)
    STR_OPT = "str | None"  # AOT mode only (tier S2a): a `tpa_str *` that is NULL for None
    BYTES = "bytes"         # AOT mode only (tier S2c): a Python bytes, a counted `tpa_str` of kind 1 whose code points
    #                         are the bytes (runtime/tp_aot_str.h); a local, a parameter or a return value
    STR_OPT_LIST = "list[str | None]"  # AOT mode only (tier S2a): a `tpa_str_list` whose items may be NULL (None)
    OBJ_LIST = "list[obj]"  # AOT mode only (tier S2a): a list of instances of one compiled class, a counted
    #                         `tpa_obj_list`; the element class is the `cls` of the Param / FieldDecl / return, or
    #                         `Function.list_classes[name]` for a local
    DICT = "dict"           # AOT mode only (tier S2b): a counted `tpa_dict` (runtime/tp_aot_dict.h); its key and value
    #                         types (`DictKinds`) are the `kinds` of the Param, `Function.return_kinds`, the display or
    #                         copy that makes it, or `Function.dict_kinds[name]` for a local
    KEY_TUPLE = "tuple key"  # AOT mode only (tier S2b): a tuple made to be a dict key (`ir.KeyTuple`), a counted
    #                         `tpa_dtup`; it exists only as the key operand of a dict node
    DICT_LIST = "list[dict]"  # AOT mode only (tier S2b): a list of dicts, a counted `tpa_obj_list` of `tpa_dict`; the
    #                         dicts' kinds are kept where a DICT's are (the Param's / FieldDecl's `kinds`,
    #                         `Function.return_kinds`, `Function.dict_kinds[name]`, the display's `kinds`)


SCALARS = (Type.I64, Type.F64, Type.BOOL)
# AOT mode, tier S2b: what a dict holds. A key is an int (I64: always a tagged `tpa_int` in the table, so a big int is a
# key like any other), a str, or a bool (an I64 key the table marks as a bool: `1 == True`, and the key keeps the type
# it was first inserted with); a value is an int (tagged), a float, a bool, a str or `str | None` (NULL for None), or a
# dict, whose own kinds stand in the value's place: `dict[str, dict[int, int]]` is (STR, (I64, I64)). (A dict held by a
# dict cannot hold that dict back, its type is a part of the outer type: no cycle, so plain reference counts free it.)
DictKinds = tuple[Type, "Type | DictKinds"]
DICT_KEYS = (Type.I64, Type.STR, Type.BOOL)
DICT_VALUES = (Type.I64, Type.F64, Type.BOOL, Type.STR, Type.STR_OPT)


def dict_vtype(v: "Type | DictKinds") -> Type:
    """The type of a value of a dict whose values are `v` (DICT for a dict held as a value)."""
    return Type.DICT if isinstance(v, tuple) else v


def good_dict_kinds(k: object, top: bool = True) -> bool:
    """`k` is (key type, value type), the value type an allowed scalar or str type or good kinds of a dict (not a
    set); the key type an allowed key type or a tuple of them (a tuple key, `ir.KeyTuple`). At the top, the value type
    NONE is a set (`is_set`)."""
    return (isinstance(k, tuple) and len(k) == 2
            and (k[0] in DICT_KEYS or (isinstance(k[0], tuple) and len(k[0]) >= 1
                                       and all(t in DICT_KEYS for t in k[0])))
            and (k[1] in DICT_VALUES or (top and k[1] is Type.NONE)
                 or (isinstance(k[1], tuple) and good_dict_kinds(k[1], top=False))))


def is_set(k: object) -> bool:
    """AOT mode, tier S2b: the kinds `k` of a DICT are those of a set (`set[T]`: the elements are the keys, the value
    type is NONE, no value is ever read)."""
    return isinstance(k, tuple) and len(k) == 2 and k[1] is Type.NONE


def view_match(view: str | None, a: "DictKinds", b: "DictKinds") -> bool:
    """The views `view` ("keys", "values", "items"; None: the dicts themselves) of dicts of kinds `a` and `b` read the
    same: the keys of a keys view, the values of a values view, both otherwise. A `KeysView[K]` does not say the
    values of its dict, nor a `ValuesView[V]` its keys (tier S2b)."""
    return a[0] == b[0] if view == "keys" else a[1] == b[1] if view == "values" else a == b


def dict_has_float(k: "DictKinds") -> bool:
    """A float is among the values of `k` or of the dicts it holds."""
    return k[1] is Type.F64 or (isinstance(k[1], tuple) and dict_has_float(k[1]))
# INT is not a scalar: it owns a reference when promoted. It is created only by `ToInt`, by arithmetic
# on INTs, and by a redo `Call` of an i64-returning callee, and it becomes an object only through `Box`.
ARRAYS = (Type.F64_ARRAY, Type.I64_ARRAY, Type.BOOL_ARRAY)


# --- expressions ---------------------------------------------------------------------------------

@dataclass(frozen=True)
class Expr:
    type: Type


@dataclass(frozen=True)
class Const(Expr):
    """A literal. `value` is a Python int/float/bool/None matching `type`. An OBJ constant is None
    or a `str` (a new reference to the module-lifetime str the back end holds for it; the text has
    no NUL and no lone surrogate, so it is exactly the UTF-8 the C source carries)."""

    value: int | float | bool | str | None
    # AOT mode only (SPEC N-17, #451; agent proposal, not owner-confirmed): the module constant this
    # constant was read from (`PI` for `PI * 2.0`), so a finding can name it. A module constant is
    # immutable and fully known, so this Const is exactly the one its value spells; the name is
    # metadata. Like `Stmt.stmt` it takes no part in equality, hashing or repr, so the default mode,
    # which never sets it, sees the node it always saw.
    name: str | None = field(default=None, kw_only=True, compare=False, repr=False)

    def __eq__(self, other: object) -> bool:
        if type(self) is not type(other):
            return NotImplemented
        if self.type != other.type:
            return False
        if (isinstance(self.value, float) and isinstance(other.value, float)
                and self.value != self.value and other.value != other.value):
            return True
        return self.value == other.value

    def __hash__(self) -> int:
        v = "nan" if isinstance(self.value, float) and self.value != self.value else self.value
        return hash((self.type, v))


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
    LSHIFT = "<<"          # INT and OBJ operands only (SPEC N-13)
    RSHIFT = ">>"          # I64 (pure functions), INT and OBJ operands
    AND = "&"              # SPEC N-16: I64, INT and OBJ operands
    OR = "|"
    XOR = "^"
    POW = "**"             # AOT mode (Tier K PR5): I64 and F64 operands


@dataclass(frozen=True)
class BinOp(Expr):
    """Arithmetic with CPython's result. Both operands have the same scalar type (the front end
    inserts `ToFloat` for mixed int/float, as CPython converts the int).

    I64 (result I64, except TRUEDIV → F64):
      ADD/SUB/MUL, checked; overflow → deopt.
      FLOORDIV/MOD, Python floor semantics (result sign follows the divisor for MOD; the runtime
        handles -2**63 % -1 == 0 without C's undefined behaviour); divisor 0
        → ZeroDivisionError with the running interpreter's message (3.13: "integer division or
        modulo by zero" / "integer modulo by zero"; 3.14: "division by zero" for every case, the
        runtime selects by version and its differential tests pin both); -2**63 // -1 overflows →
        deopt. Every ZeroDivisionError/math-domain message below follows the same rule.
      TRUEDIV, CPython rounds int/int correctly; when both |operands| <= 2**53 the C double
        division is exact-rounded too, otherwise → deopt. Divisor 0 → ZeroDivisionError("division
        by zero").
    F64:
      ADD/SUB/MUL, IEEE, as CPython.
      TRUEDIV, divisor 0.0 (either sign) → ZeroDivisionError("float division by zero").
      FLOORDIV/MOD, CPython's float floor division and modulo (sign of divisor, `-0.0` cases),
        divisor 0.0 → ZeroDivisionError("float floor division by zero") / ("float modulo by
        zero").
    I64 bitwise (SPEC N-16, #289): AND/OR/XOR are the native operations and never deopt (two's complement
      i64 equals Python's infinite two's complement in range). RSHIFT is the arithmetic floor shift
      (a count >= 64 gives 0 or -1). LSHIFT is exact: a result outside i64 → deopt. A negative count →
      deopt in both shifts (the interpreter raises ValueError("negative shift count"), and numpy scalars keep
      numpy's behaviour). I64 shifts are built in pure functions only; an impure function shifts INTs.
    INT (SPEC N-13; ADD/SUB/MUL/FLOORDIV/MOD/LSHIFT/RSHIFT/AND/OR/XOR, both operands INT, result INT; TRUEDIV of ints
      is an OBJ operation on boxed values): the native i64 operation when both operands are native
      and the result fits i64, else CPython's PyNumber_* on the two ints (exact ints, so no user code
      runs) and the result narrowed back to native when it fits. Never deopts, so it is legal in an
      impure function; it can raise (ZeroDivisionError, a negative shift count, OverflowError,
      MemoryError) exactly where CPython does. `proven` is never set on it.
    OBJ: CPython's generic operation on objects (PyNumber_*), never deopts. `inplace` (OBJ only) is
      the augmented form `a op= b` (PyNumber_InPlace*, which a list's `+=` and a user `__iadd__`
      need); an exact `str + str` is PyUnicode_Concat, the function PyNumber_Add ends in for it.
    """

    op: BinOpKind
    left: Expr
    right: Expr
    # I64 only, set by the front end when an interval proof shows the op cannot deopt; the
    # verifier re-proves it (never trusts it) and a proven op cannot deopt, so it is allowed in an
    # impure function.
    #   ADD/SUB/MUL: the result cannot leave the i64 range. The back end does plain arithmetic, with
    #     no check (#148); built with -DTP_CHECK_PROVEN it uses the checked helper and treats an
    #     overflow as an internal error (SystemError), never as a deopt.
    #   FLOORDIV/MOD: the divisor is a Const power of two >= 1 and the dividend lies in
    #     [0, 2**63-1] (Python's floor semantics and C's agree there); the back end emits `>>` / `&`.
    # Any other op, or an op the verifier cannot prove, is rejected (verify/proven-overflow).
    proven: bool = False
    inplace: bool = False
    # F64 ADD/SUB/MUL only (#220): an operand may be a numpy scalar in the interpreter, which warns
    # (RuntimeWarning: overflow / invalid value) where a double does not. A non-finite result →
    # deopt, so the interpreter redoes the call and emits the warning; legal only in a pure function.
    # Step 2 (#220): `np` also covers F64 TRUEDIV / FLOORDIV / MOD (numpy's results: inf / nan, no
    # ZeroDivisionError) and unproven I64 TRUEDIV / FLOORDIV / MOD (numpy's int64: division by zero
    # gives 0 / inf / nan; both are a deopt, and legal only in a pure function). Any floating-point
    # exception (overflow, invalid, divide by zero) is a deopt in a pure function; in an impure F64
    # operation the code emits numpy's RuntimeWarning itself, at `line` of the cgen display path.
    np: bool = False
    line: int = 0      # source line of the operation (the warning site of an impure `np` operation)


class UnaryOpKind(Enum):
    NEG = "-"
    POS = "+"
    NOT = "not"
    INVERT = "~"          # I64 and INT only (SPEC N-16); never overflows, never deopts


@dataclass(frozen=True)
class UnaryOp(Expr):
    """NEG of I64 is checked (-(-2**63) → deopt). NOT takes a BOOL and gives a BOOL. NEG and POS of an
    INT give an INT and never deopt (-(-2**63) is the PyLong 2**63)."""

    op: UnaryOpKind
    operand: Expr
    proven: bool = False          # NEG of I64: as BinOp.proven


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
    operand evaluated once (via a temporary). Mixed I64/F64 compares **exactly**, as CPython does,
    not by converting the int to double (2**53+1 != float(2**53)). NaN compares as in IEEE.
    Two INT operands compare as the exact ints (native when both are native)."""

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
    # `float(x)` written in the source (#220): the result is a plain float even when `x` is a numpy
    # scalar in the interpreter. The operand may then also be F64 (the value itself).
    explicit: bool = False


@dataclass(frozen=True)
class ToInt(Expr):
    """An I64 widened to an INT (type INT): native, no object, never fails (SPEC N-13)."""

    operand: Expr


@dataclass(frozen=True)
class Box(Expr):
    """A scalar or array element converted to a Python object (type OBJ): I64 → int, F64 → float,
    BOOL → True/False, INT → the int (a promoted INT is the PyLong it holds). Used when a typed value
    flows into OBJ code. A Box of an I64 or INT constant is read from module state, built once at
    module exec (SPEC N-13).
    `np` (#220, a BOOL expression, None otherwise): when it is true at run time the value is a numpy
    scalar in the interpreter (it derives from an element of a numpy array), and the box is the
    matching `np.int64` / `np.float64` / `np.bool` instead. The front end computes it per value (a
    shadow BOOL local per scalar local that can hold one)."""

    operand: Expr
    np: Expr | None = None


@dataclass(frozen=True)
class Unbox(Expr):
    """An OBJ converted to a scalar. The runtime checks the exact type (`tp_unbox_*`) and the i64
    range; anything else → deopt. Because it can deopt, it is legal only in a pure function (the
    verifier enforces this); impure code converts objects with `ObjToFloat` instead. Exception
    (#255 (d2), `direct_writer`): there a mismatch leaves through the statement exit instead."""

    operand: Expr


class MathFunc(Enum):
    SQRT = "sqrt"
    EXP = "exp"
    LOG = "log"
    LOG2 = "log2"
    LOG10 = "log10"
    SIN = "sin"
    COS = "cos"
    TAN = "tan"
    ASIN = "asin"
    ACOS = "acos"
    ATAN = "atan"
    ATAN2 = "atan2"
    SINH = "sinh"
    COSH = "cosh"
    TANH = "tanh"
    FABS = "fabs"
    HYPOT = "hypot"
    POW = "pow"
    COPYSIGN = "copysign"
    FMOD = "fmod"
    ISNAN = "isnan"
    ISINF = "isinf"
    ISFINITE = "isfinite"
    FLOOR = "floor"
    CEIL = "ceil"
    TRUNC = "trunc"


@dataclass(frozen=True)
class MathCall(Expr):
    """`math.<func>(x...)` on F64, type F64 (ATAN2 and HYPOT take exactly two arguments; other arities
    stay interpreted), with the stdlib `math` module's errors:
    a domain error → ValueError("math domain error"), a range overflow →
    OverflowError("math range error") (CPython's math_1/math_2 rules on errno/inf/nan)."""

    func: MathFunc
    args: tuple[Expr, ...]


@dataclass(frozen=True)
class Call(Expr):
    """A direct call of another compiled function in the same module (C to C). If the callee may
    deopt, the caller must be pure (the deopt propagates and the outermost compiled call is
    redone); otherwise the front end calls it as an object (`CallObject(Global(name), ...)`).
    A call cycle (direct or mutual recursion) is legal: every compiled function counts its frame at
    entry (runtime `tp_enter_call`), so a recursion that CPython stops with RecursionError is stopped
    with the same error, and the C stack cannot overflow.
    A callee with a `Param.cls` parameter can deopt at entry whenever an argument is not proved to be
    exactly that class, so such a call counts as a node that can deopt: the caller is pure (and
    `may_deopt`), or the call is `redo`. A pure callee that uses a compiled class can also return the
    deopt signal with `may_deopt` False, when the class changed after init (N-11); an impure caller
    then redoes that callee interpreted, for OBJ and I64 results as well, such a callee is pure and
    its I64 result is interval-proved, so the redone value is the same and fits.
    SPEC N-13: a redo Call of a callee returning I64 has type INT (the redone value may be any int;
    it is checked to be exactly an int, anything else is a SystemError), and an argument of type INT
    may stand for an I64 parameter: a promoted INT does not fit it, which is the callee's deopt
    (absorbed by the redo, the interpreted callee gets the boxed arguments)."""

    function: str
    args: tuple[Expr, ...]
    # Set when an impure caller calls a may_deopt (therefore pure) callee: on a deopt inside the
    # callee, only the callee is redone by its interpreted function, unobservable, because the
    # callee is pure, and the caller continues. Allowed only for callees returning F64, BOOL or
    # NONE: an F64-typed IR expression is a Python float in CPython too (ints reach floats only
    # through explicit ToFloat or float arithmetic), so the redone result is exactly a float; an
    # I64 result could be a big int and is not allowed here.
    redo: bool = False


@dataclass(frozen=True)
class Global(Expr):
    """Reading a module-level name (then builtins) as a new reference, looked up **at the time of the read** in the
    module namespace, exactly as CPython's LOAD_GLOBAL: rebinding the global later is seen.
    Unbound → NameError("name 'x' is not defined"). Type OBJ. This is how compiled code reaches
    functions and classes imported from Kotlin packages through the binder (they are ordinary
    module globals). A read alone has no effect, so it does not make a function impure."""

    name: str


@dataclass(frozen=True)
class GetAttr(Expr):
    """`obj.name` on an OBJ (PyObject_GetAttr, descriptors, properties and binder proxies' attribute
    hooks run exactly as in CPython). Type OBJ. Makes the function impure: a property may have
    effects, so the call can never be redone."""

    obj: Expr
    name: str


@dataclass(frozen=True)
class CallObject(Expr):
    """Calling an OBJ callable, a binder Kotlin function or proxy method, any Python callable,
    with positional `args` and keyword arguments `kwnames` (the last len(kwnames) args are the
    keyword values, as in vectorcall). Arguments are OBJ (scalars arrive through Box). Type OBJ.
    Makes the function impure. This is the general path that keeps Kotlin interop available to
    compiled code: whatever the binder accepts from interpreted code, it accepts from here, and
    whatever it returns (a proxy, a str, a list) flows on as an opaque OBJ."""

    callee: Expr
    args: tuple[Expr, ...]
    kwnames: tuple[str, ...] = ()


@dataclass(frozen=True)
class Truth(Expr):
    """`bool(obj)` as a condition (PyObject_IsTrue; may raise as CPython does). Type BOOL."""

    operand: Expr


@dataclass(frozen=True)
class CompareObj(Expr):
    """A comparison of two OBJ operands (a scalar side arrives through `Box`) **directly as a
    condition**: an If/While cond, or an And/Or operand when that And/Or is itself the condition.
    Semantics of `if a == b:` in CPython: the rich comparison runs, then the truth value of its
    result, **without** PyObject_RichCompareBool's identity shortcut (a NaN float compared with
    itself is False in CPython's `if x == x`). Type BOOL.
    Anywhere the comparison's own result object would be kept (`x = a < b`, `r = c and a < b`,
    `not (a < b)`) it is not allowed, because that object need not be a bool."""

    op: CompareKind
    left: Expr
    right: Expr


@dataclass(frozen=True)
class ObjToFloat(Expr):
    """`float(obj)` (PyNumber_Float: __float__/__index__ run as in CPython), result as F64. Raises as
    CPython raises; never deopts, so it is allowed in impure functions. Type F64."""

    operand: Expr


@dataclass(frozen=True)
class NewArray(Expr):
    """A fresh **local** array (type F64_ARRAY / I64_ARRAY), owned by the function:
    `[fill] * length` (`iota=False`), or `list(range(length))` (`iota=True`, I64 only, elements
    0..length-1). A negative length gives an empty array, as in CPython. Allocation failure →
    MemoryError. Only legal as the value of an `Assign` to a local of the same array type."""

    length: Expr                   # I64
    fill: Expr | None = None       # element-typed; None when iota
    iota: bool = False


@dataclass(frozen=True)
class CopyArray(Expr):
    """`src[:]` of a local or parameter array: a fresh local array with the same elements.
    Only legal as the value of an `Assign` to a local of the same array type."""

    src: str


@dataclass(frozen=True)
class Tuple(Expr):
    """`(a, b, ...)` of scalars/OBJs, a new tuple (type OBJ). Scalar elements arrive through Box."""

    elements: tuple[Expr, ...]


@dataclass(frozen=True)
class GetItem(Expr):
    """`obj[index]` on OBJ operands (obj first, then index): PyObject_GetItem, the call the
    interpreter's BINARY_OP [] makes, so `__getitem__`, `__missing__`, `__hash__`/`__eq__` of the
    key, `__index__` and every error (KeyError(key), IndexError, TypeError) are CPython's. For an
    object that is exactly a `dict` it is PyDict_GetItemRef and, when absent, KeyError(key) built as
    dict's own subscript does (runtime `tp_getitem`; the guard is the exact type). Type OBJ. Makes the
    function impure and not closed."""

    obj: Expr
    index: Expr


@dataclass(frozen=True)
class Contains(Expr):
    """`left in right` / `left not in right` (negate) on OBJ operands (left first, then right):
    PySequence_Contains(right, left), the call CONTAINS_OP makes, so `__contains__`, iteration and
    `__eq__` (with RichCompareBool's identity shortcut) run as in CPython. Type BOOL, always a bool
    (so unlike CompareObj it is allowed anywhere). Makes the function impure and not closed."""

    left: Expr
    right: Expr
    negate: bool = False


@dataclass(frozen=True)
class NewList(Expr):
    """`[]`: PyList_New(0). Type OBJ. A fresh object is not an effect."""


@dataclass(frozen=True)
class NewDict(Expr):
    """`{}`: PyDict_New(). Type OBJ. A fresh object is not an effect."""


@dataclass(frozen=True)
class Format(Expr):
    """One `{value!c:spec}` field of an f-string, exactly as FORMAT_VALUE / CONVERT_VALUE +
    FORMAT_SIMPLE / FORMAT_WITH_SPEC do: apply the conversion (`conversion`: -1 none, 115 `!s`
    PyObject_Str, 114 `!r` PyObject_Repr, 97 `!a` PyObject_ASCII), then with `spec` (a constant str)
    PyObject_Format(value, spec), without it the value itself when it is exactly a str, else
    PyObject_Format(value, NULL). Type OBJ (a str unless a user `__format__` returned something
    else, which PyObject_Format rejects with CPython's TypeError). Impure and not closed."""

    operand: Expr
    spec: str | None
    conversion: int


@dataclass(frozen=True)
class BuildString(Expr):
    """The f-string itself (BUILD_STRING): the str that joins `elements` (OBJ strs: Const strs and
    Format results), evaluated in order, each Format running before the next element is evaluated.
    Type OBJ. Runs no user code."""

    elements: tuple[Expr, ...]


@dataclass(frozen=True)
class Is(Expr):
    """`a is b` / `a is not b` (negate) on OBJ operands: identity, no user code. Type BOOL. With
    `Const(None, OBJ)` this is how `x is None` is written."""

    left: Expr
    right: Expr
    negate: bool = False


@dataclass(frozen=True)
class IsExact(Expr):
    """`type(obj) is C` for a compiled fixed-layout class C (SPEC N-11): no user code. Type BOOL.
    A true IsExact dominating a FieldGet/FieldSet on the same local is what the verifier accepts as
    the exact-type proof for that access. `C` is the module global as CPython reads it: when the
    global no longer is the class captured at init, the back end compares with the current binding
    (impure) or deopts (pure)."""

    obj: Expr
    cls: str


@dataclass(frozen=True)
class CheckExact(Expr):
    """`obj` itself (type OBJ), after checking `type(obj) is C`; anything else → deopt. Because it
    can deopt it is legal only in a pure function, the same rule as Unbox. It is the proof the
    verifier accepts for a field access the front end cannot guard with a dominating IsExact."""

    obj: Expr
    cls: str


@dataclass(frozen=True)
class CheckMethod(Expr):
    """`obj` itself (type OBJ), after checking that `obj.method` is the compiled method `cls.method`
    CPython would find (SPEC N-11, issue #155): `type(obj) is C` AND every method the module installed
    in C is still the very object in C's own dict (nobody rebound `C.method` or any other compiled
    method since init). Anything else → deopt, so it is legal only in a pure function, the same rule
    as CheckExact; it is how a pure compiled method or function calls `obj.method(args)` as a direct
    C call: the front end emits `Call("C.method", (CheckMethod(obj, C, method), *args))`. No user
    code runs. The check comes first among the call's operands, before the arguments are evaluated,
    where CPython's LOAD_ATTR is. The verifier accepts the result as an exact-C proof (it is that, at
    the check). `Module.functions` must hold a Function named `C.method` whose `owner` is C."""

    obj: Expr
    cls: str
    method: str


@dataclass(frozen=True)
class FieldGet(Expr):
    """`obj.field` of a compiled fixed-layout class C whose exact type is proved (a dominating true
    IsExact on the same local, a CheckExact, a New, or a parameter guarded `type(x) is C`). Reads the
    slot through the member descriptor captured at module init: a new reference, or CPython's own
    AttributeError for an unset slot. Runs no user code, so it is not an effect. Type OBJ.
    The class may change after init (a property replacing the field, `obj.__class__ = Other`): the
    back end re-checks `type(obj) is C` and C's version tag at the access, and otherwise does what
    CPython does, `getattr(obj, field)` in an impure function, a deopt in a pure one (where running
    user code could be repeated by a later deopt's redo). FieldSet likewise (setattr)."""

    obj: Expr
    cls: str
    field: str


@dataclass(frozen=True)
class New(Expr):
    """`C(args)` for a compiled class whose `__init__` only assigns each slot once from its parameters
    (ClassDecl.trivial_init): allocate with C's tp_alloc and fill the slots in __init__'s order,
    the observable result of running that __init__. A fresh object is not an effect. Type OBJ; args
    are OBJ (scalars through Box). `C` is read as a module global before the args, as CPython's
    LOAD_GLOBAL is: if it is no longer the captured class, or the class changed (e.g. __init__), the
    back end calls what the global held (impure) or deopts (pure)."""

    cls: str
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
    """`stmt` (SPEC N-8, #390 item 5): the number of the source statement this IR statement was
    lowered from, in `resume.statements` order (the function's own AST statements in pre-order), or
    None (IR built by hand, or a statement no source statement produced). The front end sets it on
    every statement it emits, the temporaries a statement needs included (`frontend.block`); a
    `while` whose test needs temporaries gives its synthetic exit test and `break` the while's
    number. It is only a position: it takes no part in equality, hashing or repr, so passes that
    compare IR are unaffected, and `dataclasses.replace` keeps it. A function holding list copies
    uses it to leave through a statement exit (`cgen`, `verify/array-wait`), and so does a function
    with an effect and three or more lists (`direct_writer`, `verify/array-direct`)."""

    stmt: int | None = field(default=None, kw_only=True, compare=False, repr=False)


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


# --- AOT mode, tier S2a: lists that change length (design 4.3.2). `array` names a `list[float]` / `list[int]` local or
# parameter (as Index); every alias of the list sees the change. A program with any of these on a list type gives that
# type the growable layout (the emitter's `TPA_GROW_*` define).

@dataclass(frozen=True)
class ListAppend(Stmt):
    """`array.append(value)`."""

    array: str
    value: Expr


@dataclass(frozen=True)
class ListInsert(Stmt):
    """`array.insert(index, value)`: CPython's clamping (a negative index counts from the end, then 0 and len are the
    limits; never an error)."""

    array: str
    index: Expr
    value: Expr


@dataclass(frozen=True)
class ListExtend(Stmt):
    """`array.extend(other)` and `array += other` (in place, the same object); `other` is a list name of the same type,
    possibly `array` itself (CPython copies the items first, so the list doubles)."""

    array: str
    other: str


@dataclass(frozen=True)
class ListClear(Stmt):
    """`array.clear()`."""

    array: str


@dataclass(frozen=True)
class ListSort(Stmt):
    """`array.sort(reverse=...)` (tier S2c): CPython's listsort without a key (tp_aot.h `TPA__DEF_SORT`), of a list of
    int, float or str. `reverse` is BOOL, evaluated before the sort."""

    array: str
    reverse: Expr


@dataclass(frozen=True)
class ListReverse(Stmt):
    """`array.reverse()`."""

    array: str


@dataclass(frozen=True)
class ListPop(Expr):
    """`array.pop()` / `array.pop(index)`: the element, removed. IndexError("pop from empty list") on an empty list,
    else IndexError("pop index out of range") for an index outside it (a negative index counts from the end)."""

    array: str
    index: Expr | None


@dataclass(frozen=True)
class ListSearch(Expr):
    """`array.index(value)` (I64), `array.count(value)` (I64), `value in array` (BOOL), on a `list[int]` only (a
    `list[float]` search depends on object identity for NaN in CPython, so it has no AOT form: `aot/identity`).
    `index`: ValueError("list.index(x): x not in list") when absent (CPython 3.14 and later)."""

    op: str                    # "index" | "count" | "in"
    array: str
    value: Expr


@dataclass(frozen=True)
class ListRemove(Stmt):
    """`array.remove(value)` on a `list[int]`: the first equal element; ValueError("list.remove(x): x not in list")."""

    array: str
    value: Expr


@dataclass(frozen=True)
class ListConcat(Expr):
    """`left + right` of two list names of one type: a new list (type: the list type)."""

    left: str
    right: str


@dataclass(frozen=True)
class ListRepeat(Expr):
    """`src * count` (`count_first`: `count * src`, CPython evaluates the count first then): a new list; a count of
    0 or less gives an empty list, a size past the largest list MemoryError."""

    src: str
    count: Expr
    count_first: bool = False


@dataclass(frozen=True)
class ListSlice(Expr):
    """`src[start:stop:step]` (a missing bound is None; all None is `list(src)` / `src[:]` of a list[str]): a new list,
    PySlice_AdjustIndices; ValueError("slice step cannot be zero")."""

    src: str
    start: Expr | None
    stop: Expr | None
    step: Expr | None


LIST_MAKERS = (ListConcat, ListRepeat, ListSlice)


@dataclass(frozen=True)
class ListDelete(Stmt):
    """`del array[index]` (IndexError("list assignment index out of range")) or, with `is_slice`, `del
    array[start:stop:step]` (a missing bound is None)."""

    array: str
    index: Expr | None
    start: Expr | None = None
    stop: Expr | None = None
    step: Expr | None = None
    is_slice: bool = False


@dataclass(frozen=True)
class ListSetSlice(Stmt):
    """`array[start:stop:step] = other` (`other` a list name of the same type, possibly `array`: its items are copied
    first). A step other than 1 needs equal sizes: ValueError("attempt to assign sequence of size N to extended slice
    of size M")."""

    array: str
    start: Expr | None
    stop: Expr | None
    step: Expr | None
    other: str


@dataclass(frozen=True)
class ListCompare(Expr):
    """`left op right` of two lists of one type, list[int] or list[str] (a list of float compares items by identity
    first in CPython: aot/identity). Objects/listobject.c list_richcompare: the first index where the items differ
    decides (`==` false, `!=` true, else that pair compared by `op`); with none, the lengths are compared. Type BOOL."""

    op: CompareKind
    left: str
    right: str


@dataclass(frozen=True)
class ListStr(Expr):
    """`str(array)` / `repr(array)` (the same text for a list), also for print and an f-string field. Type STR."""

    array: str


LIST_GROW_NODES = (ListAppend, ListInsert, ListExtend, ListSetSlice)
LIST_SHRINK_NODES = (ListPop, ListClear, ListRemove, ListDelete, ListSetSlice)


@dataclass(frozen=True)
class FieldSet(Stmt):
    """`obj.field = value` on a proved-exact compiled class (as FieldGet). An effect, unless `obj` is
    a New made in this same function and not yet escaped (not assumed in M2b: always an effect)."""

    obj: Expr
    cls: str
    field: str
    value: Expr


@dataclass(frozen=True)
class StoreItem(Stmt):
    """`obj[index] = value` on OBJ operands. CPython evaluates the value first, then the container,
    then the index: the back end evaluates them in that order (the three operands are expressions
    that may be effects). PyObject_SetItem, the call STORE_SUBSCR makes (for an object that is exactly
    a `dict`, PyDict_SetItem, which is what dict's `mp_ass_subscript` calls). An effect, and not
    closed: `__setitem__`, `__hash__`/`__eq__` of the key and dropping the old value run user code."""

    obj: Expr
    index: Expr
    value: Expr


@dataclass(frozen=True)
class ForObj(Stmt):
    """`for t in iter:` or `for t0, t1, ... in iter:` over any object: `iter` (OBJ) is evaluated once,
    GET_ITER is PyObject_GetIter, each step is PyIter_Next (the call FOR_ITER ends in: StopIteration
    is the end, any other exception propagates); the item is stored into the OBJ locals `targets`
    left to right. With `unpack` the item is unpacked into the targets as UNPACK_SEQUENCE does (an
    exact tuple of the right length directly, anything else through its iterator, with CPython's
    `cannot unpack non-iterable T object`, `not enough values to unpack (expected n, got k)` and
    `too many values to unpack (expected n[, got k])` errors); without it there is exactly one target
    and it receives the item. The targets keep their last value after the loop; a loop that ran zero
    times leaves them as they were. `break`/`continue` and every exit release the iterator once.
    The effect of GetIter/Next is user code, so the function is impure and not closed."""

    targets: tuple[str, ...]
    iter: Expr
    body: tuple[Stmt, ...]
    unpack: bool = False


@dataclass(frozen=True)
class ExprStmt(Stmt):
    value: Expr


@dataclass(frozen=True)
class Print(Stmt):
    """AOT mode only (SPEC N-17, #451; agent proposal, not owner-confirmed): `print(a, b, ...)` with
    positional arguments, standard output, a space between arguments and a newline at the end. Each
    argument is an I64, F64 or BOOL expression (printed as CPython's `str` of it) or an OBJ `Const`
    holding a `str` (printed as it is). The default mode never produces it (there `print` is an object
    call) and the verifier rejects it unless asked for AOT mode (`verify.verify(m, aot=True)`)."""

    args: tuple[Expr, ...]
    # Tier S1 (PR-S1a): `sep=` and `end=` (STR expressions, evaluated after the positional arguments, in source
    # order, as CPython evaluates a call's arguments); None: a space and a newline. An argument may be STR.
    sep: Expr | None = None
    end: Expr | None = None


# --- AOT-only nodes (SPEC N-17, #451; agent proposal, not owner-confirmed) -----------------------------
#
# The nodes below are produced only by `frontend.lower(path, aot=True)` and accepted only by
# `verify.verify(m, aot=True)` (the default mode never builds one; its verifier reports them as unknown
# nodes). They are the AOT mode's forms of what the default mode leaves to CPython objects: a list that
# lives as a native counted array and is passed and returned by name, a fixed tuple of scalars returned
# and unpacked, one `int(sys.argv[i])`, a formatted `print` argument and the conditional expression.

@dataclass(frozen=True)
class ListDisplay(Expr):
    """`[e0, e1, ...]` of scalar expressions of one element type: a fresh native array (type F64_ARRAY
    or I64_ARRAY, one element per expression, at least one). Like NewArray and CopyArray it is a
    creation: the value of an `Assign` to a local array, or of a `Return`."""

    elements: tuple[Expr, ...]


@dataclass(frozen=True)
class Cond(Expr):
    """`then if cond else orelse` on two scalars of one type (the type of the node). Only the chosen
    branch is evaluated."""

    cond: Expr                 # BOOL
    then: Expr
    orelse: Expr


@dataclass(frozen=True)
class TupleIndex(Expr):
    """`t[i]` of a split tuple (AOT mode, tier S2a, `typedpython/tuples.py`): the index, then the element chosen from
    `elements` (reads of the element locals, all of the node's type: a scalar or a str), with CPython's tuple rules: a
    negative index counts from the end; out of range -> IndexError("tuple index out of range"). Only the chosen
    element is read."""

    index: Expr                # I64
    elements: tuple[Expr, ...]


@dataclass(frozen=True)
class ArgvInt(Expr):
    """`int(sys.argv[index])` (type I64), `index` I64. CPython's `int()` of a string can be any int, so the AOT
    back end holds its value as a big-integer-capable (TAGGED) int (`aot/rep.py`, #462); under `--strict-int` it
    reports `aot/int-unproven` naming the expression instead. The node exists so the program is lowered,
    verified and every other finding is reported."""

    index: Expr


@dataclass(frozen=True)
class ArgvLen(Expr):
    """`len(sys.argv)` (type I64)."""


@dataclass(frozen=True)
class FloatToInt(Expr):
    """`int(float_expr)` (type I64, AOT mode): truncates toward zero, big-int capable (TAGGED)."""

    operand: Expr


@dataclass(frozen=True)
class BoolToInt(Expr):
    """`int(bool_expr)` (type I64, AOT mode): 0 or 1."""

    operand: Expr


@dataclass(frozen=True)
class StrToInt(Expr):
    """`int("literal")` (type I64, AOT mode): parsed at runtime with Python's base-10 int parser (TAGGED)."""

    text: str


class TimeFunc(Enum):
    PERF_COUNTER = "perf_counter"
    MONOTONIC = "monotonic"
    TIME = "time"


@dataclass(frozen=True)
class TimeCall(Expr):
    """`time.<func>()` (type F64, AOT mode)."""

    func: TimeFunc


@dataclass(frozen=True)
class TimeSleep(Stmt):
    """`time.sleep(seconds)` (AOT mode). `seconds` is F64."""

    seconds: Expr


@dataclass(frozen=True)
class Raise(Stmt):
    """AOT mode, tier S2c: raise the builtin exception `kind` (a `TPA_` kind of the runtime, "ValueError") with the
    fixed message CPython gives it where a rewrite of the AST stands for a builtin that raises (`min()` of an empty
    iterable: "min() iterable argument is empty"). Not a `raise` statement of the program, which stays refused."""

    kind: str
    message: str


@dataclass(frozen=True)
class SysExit(Stmt):
    """`sys.exit([code])` (AOT executable mode only; refused in `aot-lib`). `code` is I64 or None."""

    code: Expr | None = None


@dataclass(frozen=True)
class FormatField(Expr):
    """One replacement field of an f-string given to `print`, in an `FString` (type OBJ, no use
    elsewhere). `value` is I64, F64 or BOOL. `precision` None: `str(value)` (what `print(value)`
    writes). A number: `format(value, '.<precision>f')`, F64 only."""

    value: Expr
    precision: int | None = None
    # Tier S1 (PR-S1a): `{x!r}` (True: `repr(value)`); `value` may then also be STR, and a STR value without it
    # is the string itself (`{s}`).
    repr: bool = False


@dataclass(frozen=True)
class FString(Expr):
    """A formatted string: as an argument of `Print`, type OBJ (written part by part, or made in full first when a
    part can fail to encode); anywhere else (tier S1), type STR, the string the parts make. `parts` are str `Const`s
    and `FormatField`s, one after another with nothing between. Every field is evaluated, in order, before any part
    is written or the string is made."""

    parts: tuple[Expr, ...]


# --- Tier S1 (PR-S1a) string nodes (AOT mode only; docs/design/typedpython-aot.md 4.3.1) -------------------
#
# A STR value is a counted `tpa_str`. Each node below evaluates its operands left to right, as CPython does, and
# raises CPython's exception with its message where CPython raises one. An `FString` of type STR (anywhere but a
# `Print` argument, where it stays OBJ and is written piece by piece) is the string its parts make.

@dataclass(frozen=True)
class StrConcat(Expr):
    """`left + right` of two STR (type STR), or of two BYTES (tier S2c, type BYTES). `s += t` on a STR name is `Assign(s, StrConcat(Local s, t))`; the back
    end grows the name's string in place when nothing else holds it (design 4.3.1, the ownership rule)."""

    left: Expr
    right: Expr


@dataclass(frozen=True)
class StrRepeat(Expr):
    """`value * count` or `count * value` (type STR, or BYTES of a BYTES value: tier S2c); `value` STR, `count` I64. `count_first`: the int was the
    left operand (it is evaluated first)."""

    value: Expr
    count: Expr
    count_first: bool = False


@dataclass(frozen=True)
class StrCompare(Expr):
    """`left <op> right` of two STR, or of two BYTES (tier S2c), (type BOOL): equality by content, order by code point
    (for bytes, by unsigned byte)."""

    op: CompareKind
    left: Expr
    right: Expr


@dataclass(frozen=True)
class StrContains(Expr):
    """`needle in hay` (`negate`: `not in`) of two STR or two BYTES (tier S2c) (type BOOL); `needle` is evaluated
    first."""

    needle: Expr
    hay: Expr
    negate: bool = False


@dataclass(frozen=True)
class StrLen(Expr):
    """`len(value)` of a STR or a BYTES (type I64, in [0, 2**62])."""

    value: Expr


@dataclass(frozen=True)
class StrIndex(Expr):
    """`value[index]` (type STR); `index` I64 (an int outside 64 bits is CPython's IndexError)."""

    value: Expr
    index: Expr


@dataclass(frozen=True)
class StrSlice(Expr):
    """`value[start:stop:step]` of a STR (type STR) or a BYTES (type BYTES); each bound I64 or None (absent), evaluated in that order."""

    value: Expr
    start: Expr | None
    stop: Expr | None
    step: Expr | None


@dataclass(frozen=True)
class StrOf(Expr):
    """`str(value)` or (`repr`) `repr(value)` of an I64, F64, BOOL or STR (type STR); of a BYTES both are `b'...'`."""

    value: Expr
    repr: bool = False


@dataclass(frozen=True)
class BytesIndex(Expr):
    """`value[index]` of a BYTES (tier S2c, type I64 in [0, 255]); `index` I64 (an int outside 64 bits is CPython's
    IndexError), CPython's IndexError("index out of range")."""

    value: Expr
    index: Expr


@dataclass(frozen=True)
class BytesContainsInt(Expr):
    """`needle in hay` (`negate`: `not in`) of an I64 needle and a BYTES hay (tier S2c, type BOOL); a needle outside
    range(0, 256) is CPython's ValueError("byte must be in range(0, 256)"). `needle` is evaluated first."""

    needle: Expr
    hay: Expr
    negate: bool = False


@dataclass(frozen=True)
class StrEncode(Expr):
    """`value.encode()` / `value.encode("utf-8")` of a STR (tier S2c, type BYTES): strict UTF-8, a surrogate is
    CPython's UnicodeEncodeError."""

    value: Expr


@dataclass(frozen=True)
class BytesDecode(Expr):
    """`value.decode()` / `value.decode("utf-8")` of a BYTES (tier S2c, type STR): strict UTF-8, CPython's
    UnicodeDecodeError with its reason and range."""

    value: Expr


@dataclass(frozen=True)
class BytesNew(Expr):
    """`bytes(count)` (tier S2c, type BYTES): `count` zero bytes, `count` I64; a negative count is CPython's
    ValueError("negative count")."""

    count: Expr


@dataclass(frozen=True)
class BytesFromList(Expr):
    """`bytes(array)` of a list[int] local or parameter (tier S2c, type BYTES); an item outside range(0, 256) is
    CPython's ValueError("bytes must be in range(0, 256)")."""

    array: str


@dataclass(frozen=True)
class BytesDisplay(Expr):
    """`bytes([e0, e1, ...])` of I64 items (tier S2c, type BYTES): every item is evaluated, left to right, then the
    first outside range(0, 256) is CPython's ValueError("bytes must be in range(0, 256)")."""

    items: tuple[Expr, ...]


@dataclass(frozen=True)
class StrOrd(Expr):
    """`ord(value)` of a STR (type I64, in [0, 0x10FFFF])."""

    value: Expr


@dataclass(frozen=True)
class StrChr(Expr):
    """`chr(value)` of an I64 (type STR)."""

    value: Expr


@dataclass(frozen=True)
class ArgvStr(Expr):
    """`sys.argv[index]` (type STR), `index` I64: the argument as CPython decodes it."""

    index: Expr


@dataclass(frozen=True)
class StrParseInt(Expr):
    """`int(value)` of a STR (type I64, TAGGED: any int CPython parses)."""

    value: Expr


@dataclass(frozen=True)
class StrWrite(Expr):
    """`sys.stdout.write(value)` or (`err`) `sys.stderr.write(value)` of a STR (type I64: `len(value)`, as the text
    stream returns)."""

    value: Expr
    err: bool = False


@dataclass(frozen=True)
class StrMethod(Expr):
    """`recv.<name>(args)` of a STR receiver (tier S1b): split, join, strip, lstrip, rstrip, lower, upper,
    startswith, endswith, find, replace, isalpha, isdigit (design 4.3.1). `args` are the arguments in order, None for
    a `None` argument (`split(None, 2)`, `strip(None)`); `startswith` / `endswith` given a tuple have each element
    as an argument and `any_of` True. The receiver is evaluated first, then the arguments in source order: for
    startswith / endswith the layout is (start, end, prefix...), and the prefixes (the source's first argument) are
    evaluated before start and end."""

    recv: Expr
    name: str
    args: tuple[Expr | None, ...]
    any_of: bool = False


@dataclass(frozen=True)
class StrNarrow(Expr):
    """A read of a STR_OPT value the front end has proved is not None (tier S2a, design 4.3.2): type STR, no runtime
    work (`-DTP_CHECK_PROVEN` aborts on None, so the sanitizer job checks every proof)."""

    value: Expr


@dataclass(frozen=True)
class StrListDisplay(Expr):
    """`[s0, s1, ...]` of STR expressions (type STR_LIST, tier S1b), or of STR and STR_OPT ones (type STR_OPT_LIST, tier
    S2a: a None item is NULL); empty only where a list of str is expected
    (`x: list[str] = []`, `sep.join([])`)."""

    elts: tuple[Expr, ...]


@dataclass(frozen=True)
class StrListLen(Expr):
    """`len(value)` of a STR_LIST (type I64)."""

    value: Expr


@dataclass(frozen=True)
class StrListIndex(Expr):
    """`value[index]` of a STR_LIST (type STR); list semantics and IndexError("list index out of range")."""

    value: Expr
    index: Expr


@dataclass(frozen=True)
class ObjListDisplay(Expr):
    """`[a, b, ...]` of instances of the compiled class `cls` (type OBJ_LIST, tier S2a); empty only where a list of
    `cls` is expected (`x: list[C] = []`, an argument for a list[C] parameter)."""

    cls: str
    elements: tuple[Expr, ...]


@dataclass(frozen=True)
class ObjListLen(Expr):
    """`len(value)` of an OBJ_LIST (type I64)."""

    value: Expr


@dataclass(frozen=True)
class ObjListIndex(Expr):
    """`value[index]` of an OBJ_LIST of `cls` (type OBJ, an instance of `cls`, a new reference); list semantics."""

    value: Expr
    index: Expr
    cls: str


@dataclass(frozen=True)
class ObjListStore(Stmt):
    """`array[index] = value` of an OBJ_LIST local or parameter: the value, then the index, as CPython evaluates
    them; IndexError("list assignment index out of range")."""

    array: str
    index: Expr
    value: Expr


@dataclass(frozen=True)
class StrListStore(Stmt):
    """`array[index] = value` of a STR_LIST local or parameter (tier S2a): the value, then the index, as CPython
    evaluates them; IndexError("list assignment index out of range"). The list takes a new reference to the value and
    releases the item it replaces."""

    array: str
    index: Expr
    value: Expr


@dataclass(frozen=True)
class ForObjList(Stmt):
    """`for var in value:` over an OBJ_LIST: the list is held for the loop (CPython's iterator), its length read
    before every step; `var` (an OBJ local of the element class) takes each item in turn."""

    var: str
    value: Expr
    body: tuple[Stmt, ...]


# --- AOT mode, tier S2b: dict[K, V] (runtime/tp_aot_dict.h; design 4.3.2 "dict and set") ---------------------------
#
# A dict operand (`dict`, `value`, `left`, `right`, `other`) is an expression of type DICT. A key is an expression of
# the dict's key type, or a BOOL looking up a dict of int (`d[True]` finds key 1, and a KeyError prints `True`; a bool
# is never inserted into a dict of int, see ForDict); a value is of the dict's value type exactly (CPython keeps the
# object stored, so a bool in a dict of int or an int in a dict of float is refused by the front end, not converted).
# Every node raises what CPython raises, with its message: KeyError(repr(key)), RuntimeError for a loop whose dict
# changed size.

@dataclass(frozen=True)
class DictDisplay(Expr):
    """`{k0: v0, k1: v1, ...}` (type DICT) of `kinds`; `keys[i]` pairs with `values[i]`, evaluated k0, v0, k1, v1, ...
    as CPython does. The table is presized as CPython's BUILD_MAP of the display's bytecode presizes it (the back end
    mirrors the compiler's chunking), so a later loop that changes the dict sees CPython's layout."""

    kinds: DictKinds
    keys: tuple[Expr, ...]
    values: tuple[Expr, ...]


@dataclass(frozen=True)
class DictGet(Expr):
    """`dict[key]` (`default` None, `get` False: KeyError(repr(key)) when absent), `dict.get(key, default)` (`get`
    True), or `dict.get(key)` of a dict of str or `str | None` (`get` True, `default` None: type STR_OPT, None when
    absent). Type: the dict's value type (STR_OPT for a `get` without a default)."""

    dict: Expr
    key: Expr
    default: Expr | None = None
    get: bool = False


@dataclass(frozen=True)
class DictContains(Expr):
    """`key in dict` (`negate`: `not in`); type BOOL. The key is evaluated first, as CPython evaluates `a in b`."""

    key: Expr
    dict: Expr
    negate: bool = False


@dataclass(frozen=True)
class DictLen(Expr):
    """`len(dict)`; type I64."""

    dict: Expr


@dataclass(frozen=True)
class DictPop(Expr):
    """`dict.pop(key)` (KeyError(repr(key)) when absent) or `dict.pop(key, default)`; type: the dict's value type."""

    dict: Expr
    key: Expr
    default: Expr | None = None


@dataclass(frozen=True)
class DictSetDefault(Expr):
    """`dict.setdefault(key, default)`: the value under `key`, `default` inserted when absent; type: the value type."""

    dict: Expr
    key: Expr
    default: Expr


@dataclass(frozen=True)
class DictCopy(Expr):
    """`dict.copy()` or `dict(d)` (type DICT, the same `kinds`): CPython's copy, holes kept or compacted as it does."""

    kinds: DictKinds
    value: Expr


@dataclass(frozen=True)
class DictCompare(Expr):
    """`left == right` (`negate`: `!=`) of two dicts of the same kinds; type BOOL."""

    left: Expr
    right: Expr
    negate: bool = False


@dataclass(frozen=True)
class DictStr(Expr):
    """`str(dict)` / `repr(dict)` / `{dict}` in an f-string / `print(dict)`: CPython's repr; type STR. `value` is a DICT
    or a DICT_LIST (a list of dicts, its list_repr)."""

    value: Expr


@dataclass(frozen=True)
class DictStore(Stmt):
    """`dict[key] = value`: `value` first, then `dict`, then `key`, as CPython evaluates a subscript store."""

    dict: Expr
    key: Expr
    value: Expr


@dataclass(frozen=True)
class DictDelete(Stmt):
    """`del dict[key]` (KeyError(repr(key)) when absent)."""

    dict: Expr
    key: Expr


@dataclass(frozen=True)
class DictUpdate(Stmt):
    """`dict.update(other)` of a dict of the same kinds (CPython's dict_dict_merge)."""

    dict: Expr
    other: Expr


@dataclass(frozen=True)
class DictClear(Stmt):
    """`dict.clear()`."""

    dict: Expr


@dataclass(frozen=True)
class ForDict(Stmt):
    """`for key_var in dict` / `in dict.keys()` (`value_var` None), `for value_var in dict.values()` (`key_var` None),
    `for key_var, value_var in dict.items()`: the dict is held for the loop (CPython's iterator), and each step is
    CPython's `next`: RuntimeError("dictionary changed size during iteration") when the length differs from the one
    at the start, "dictionary keys changed during iteration" when more entries come than it held. `kinds` are the
    dict's; the variables are locals of the key type and of the value type. (A dict of int never holds a bool key:
    the front end refuses a bool key where one could be inserted, so the loop yields ints.)"""

    kinds: DictKinds
    value: Expr
    key_var: "str | tuple[str, ...] | None"     # (a tuple of names: a tuple key, one name per element)
    value_var: str | None
    body: tuple[Stmt, ...]


@dataclass(frozen=True)
class DictPopItem(Stmt):
    """`key_var, value_var = dict.popitem()` (both None: `dict.popitem()` as a statement): the last entry is removed
    and its key and value bound, left to right; an empty dict raises KeyError('popitem(): dictionary is empty'). The
    entry count is truncated to the removed entry, nothing becomes usable (CPython's dict_popitem_impl), so a key
    inserted next takes its place. `kinds` are the dict's; the variables are locals of the key and of the value type."""

    kinds: DictKinds
    dict: Expr
    key_var: "str | tuple[str, ...] | None"     # (a tuple of names: a tuple key, one name per element)
    value_var: str | None


@dataclass(frozen=True)
class KeyTuple(Expr):
    """`(a, b, ...)` as the key of a dict whose keys are tuples (type KEY_TUPLE): the elements evaluated in order, then
    a new tuple of them. Only a dict node's key operand. (A tuple read out of such a dict is bound to one name per
    element: `ForDict.key_var` / `DictPopItem.key_var` is then a tuple of names.)"""

    elements: tuple[Expr, ...]


@dataclass(frozen=True)
class DictListDisplay(Expr):
    """`[d0, d1, ...]` of dicts of `kinds` (type DICT_LIST), each element evaluated in order; the list holds a new
    reference to each (`[{}]`: a new dict; `[d]`: the dict `d` itself, shared). Empty only where a list of dicts of
    `kinds` is wanted."""

    kinds: DictKinds
    elements: tuple[Expr, ...]


@dataclass(frozen=True)
class DictListLen(Expr):
    """`len(value)` of a DICT_LIST (type I64)."""

    value: Expr


@dataclass(frozen=True)
class DictListIndex(Expr):
    """`value[index]` of a DICT_LIST (type DICT, the dict itself, a new reference); list semantics, IndexError("list
    index out of range")."""

    value: Expr
    index: Expr


@dataclass(frozen=True)
class DictListStore(Stmt):
    """`array[index] = value` of a DICT_LIST local or parameter: the value, then the index (CPython's order);
    IndexError("list assignment index out of range")."""

    array: str
    index: Expr
    value: Expr


@dataclass(frozen=True)
class ForDictList(Stmt):
    """`for var in value:` over a DICT_LIST: the list is held for the loop, its length read before every step; `var` (a
    DICT local of the list's kinds) takes each dict in turn."""

    var: str
    value: Expr
    body: tuple[Stmt, ...]


def dict_bound(s: "ForDict | DictPopItem") -> list[tuple[object, "Type | DictKinds"]]:
    """The names a ForDict or a DictPopItem binds, each with the type it takes (a tuple key: one name per element, of
    the element's type), keys first. Tolerates a malformed node (the verifier reports it)."""
    out: list[tuple[object, Type | DictKinds]] = []
    kinds = s.kinds if isinstance(s.kinds, tuple) and len(s.kinds) == 2 else (None, None)
    kt, vt = kinds
    if isinstance(s.key_var, tuple):
        out += list(zip(s.key_var, kt if isinstance(kt, tuple) else ()))
    elif s.key_var is not None:
        out.append((s.key_var, kt))
    if s.value_var is not None:
        out.append((s.value_var, vt))
    return out


@dataclass(frozen=True)
class DictHasValue(Expr):
    """`value in dict.values()` / `not in` (negate), the value first (type BOOL): some value of the dict equals it
    (identity first in CPython: never a float value here)."""

    value: Expr
    dict: Expr
    negate: bool = False


@dataclass(frozen=True)
class DictHasItem(Expr):
    """`(key, value) in dict.items()` / `not in` (negate): the key, the value, then the dict (type BOOL): the key is
    found and its value equals `value` (never a float value here)."""

    key: Expr
    value: Expr
    dict: Expr
    negate: bool = False


@dataclass(frozen=True)
class DictView(Expr):
    """`d.keys()` / `d.values()` / `d.items()` (`view`) bound to a view name (type DICT): the view holds the dict
    itself (a new reference), so it shows the dict as it is when it is used, and keeps that dict when the dict's name
    is bound again (CPython's view objects). Only the value of an Assign to a view local (tier S2b)."""

    view: str                      # "keys", "values", "items"
    dict: Expr


@dataclass(frozen=True)
class DictViewStr(Expr):
    """The repr of `dict.keys()` / `.values()` / `.items()` (`view`), `dict_keys(['a'])` ... (type STR)."""

    view: str                      # "keys", "values", "items"
    dict: Expr


@dataclass(frozen=True)
class SetFromList(Expr):
    """`set(array)` of a list[str] or a list[int] local or parameter (type DICT, set kinds): each item added in the
    list's order."""

    kinds: DictKinds
    array: str


@dataclass(frozen=True)
class SetDisplay(Expr):
    """`{e0, e1, ...}` / `set()` (type DICT, set kinds `(T, NONE)`): the elements evaluated in order, each added (an
    element equal to one already there is not: the first is kept)."""

    kinds: DictKinds
    elements: tuple[Expr, ...]


@dataclass(frozen=True)
class SetOp(Expr):
    """`left | right`, `&`, `-`, `^` (and union / intersection / difference / symmetric_difference of one set) of two
    sets of the same kinds: a new set (type DICT)."""

    op: str                        # "|", "&", "-", "^"
    left: Expr
    right: Expr


@dataclass(frozen=True)
class SetCompare(Expr):
    """`left <= right`, `<`, `>=`, `>` (issubset / issuperset) and `left.isdisjoint(right)` ("disjoint") of two sets of
    the same kinds (type BOOL)."""

    op: str                        # "<=", "<", ">=", ">", "disjoint"
    left: Expr
    right: Expr


@dataclass(frozen=True)
class SetAdd(Stmt):
    """`set.add(key)`: the set, then the key (a bool is never added to a set of int, as for a dict key)."""

    set: Expr
    key: Expr


@dataclass(frozen=True)
class SetRemove(Stmt):
    """`set.remove(key)` (KeyError(repr(key)) when absent) / `set.discard(key)` (`discard`: nothing when absent)."""

    set: Expr
    key: Expr
    discard: bool


DICT_EXPRS = (DictDisplay, DictGet, DictContains, DictLen, DictPop, DictSetDefault, DictCopy, DictCompare, DictStr,
              DictListDisplay, DictListLen, DictListIndex, KeyTuple, SetDisplay, SetOp, SetCompare, DictHasValue,
              DictHasItem, DictViewStr, DictView, SetFromList)
DICT_STMTS = (DictStore, DictDelete, DictUpdate, DictClear, ForDict, DictPopItem, DictListStore, ForDictList, SetAdd,
              SetRemove)


@dataclass(frozen=True)
class ForStr(Stmt):
    """`for var in value:` over a STR: `value` is evaluated once and held for the loop (rebinding its name in the body
    does not change what is iterated); `var` (a STR local) takes each one-character string in turn. After the loop
    `var` keeps its last value."""

    var: str
    value: Expr
    body: tuple[Stmt, ...]


@dataclass(frozen=True)
class ReturnTuple(Stmt):
    """`return a, b, ...` of scalars in a function declared `-> tuple[T0, T1, ...]` (AOT mode). The
    function's IR return type is OBJ, as in the default mode; the element types are those of its
    `ReturnTuple` statements (`aot_tuple_kinds`), all of which must agree."""

    values: tuple[Expr, ...]       # I64, F64 or BOOL


@dataclass(frozen=True)
class Unpack(Stmt):
    """`t0, t1, ... = f(args)` where `f` returns a fixed tuple of scalars (AOT mode): the call, then each
    element into its local. `call` is a `Call` of type OBJ; the targets are scalar locals of the
    element types. A tuple has no other use in AOT mode."""

    targets: tuple[str, ...]
    call: "Call"


def aot_tuple_kinds(f: "Function") -> tuple[Type, ...] | None:
    """The element types of the tuple `f` returns (its first `ReturnTuple`), or None."""
    stack = list(f.body)
    while stack:
        s = stack.pop(0)
        if isinstance(s, ReturnTuple):
            return tuple(v.type for v in s.values)
        for fl in fields(s):
            v = getattr(s, fl.name)
            if isinstance(v, tuple) and v and all(isinstance(x, Stmt) for x in v):
                stack[0:0] = list(v)
    return None


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
class ParInfo:
    """Why a `ForRange` may run its iterations on several threads (SPEC N-15, step 1): the body is
    only `a[var] = <F64 expression>` stores (or, step 2, only i64 `acc op= e` reductions: see `reduce`), `stored` are the arrays stored into (each at the loop
    variable, proven in bounds, so distinct iterations write distinct elements) and `read` the arrays
    read, all at the loop variable and proven in bounds. The expression nodes are Const, scalar
    Local (not assigned in the body), ToFloat of an I64, F64 + - * and negation, so no node can fail,
    deopt or touch a Python object, and `weight` is their count (the cost rule's per-iteration work).
    Arrays are distinct objects (entry guard), so a store at `var` cannot alias a read of another
    array."""

    stored: tuple[str, ...]
    read: tuple[str, ...]
    weight: int = 1
    # Step 2 (#277): the i64 reductions `acc = acc op e` of a loop whose body is only such statements,
    # as sorted (acc, BinOpKind name) pairs, op ADD, MUL, AND, OR or XOR (min/max as MIN, MAX). Then `stored` is empty. Each chunk computes
    # its partial from the op's identity with the sequential code's overflow checks; an overflow in any
    # chunk or in the combination discards the region and the sequential loop runs from its start.
    reduce: tuple[tuple[str, str], ...] = ()


@dataclass(frozen=True)
class ForRange(Stmt):
    """`for var in range(start, stop, step)`: `step` is a non-zero I64 Const; `start`/`stop` are
    evaluated once before the loop, as range() does. `var` is I64 and is not assigned in the body
    (the front end checks), so it never overflows: it stays between start and stop. After the loop
    `var` keeps its last value, and if the loop ran zero times it keeps its previous binding, the
    front end rejects reading it after the loop unless it was definitely assigned before."""

    var: str
    start: Expr
    stop: Expr
    step: Const
    body: tuple[Stmt, ...]
    # SPEC N-15 (#151, #277): set only by `verify` (like `proven`), which recomputes it from the body
    # and rejects a loop whose declared value differs (`verify/par-loop`). The front end leaves it
    # None. Not None: the iterations may run on several C threads (see `ParInfo`). None: sequential.
    par: ParInfo | None = None


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
    # For an OBJ parameter annotated with a compiled class C: the entry guard checks
    # `type(x) is C` (or `x is None` too when `optional`), deopting before any effect otherwise.
    cls: str | None = None
    optional: bool = False
    kinds: DictKinds | None = None      # AOT mode, tier S2b: the key and value types of a DICT parameter
    view: str | None = None             # ... and of a view parameter ("keys", "values", "items"): it holds the dict


@dataclass(frozen=True)
class ArrayParam:
    """How a `list[float]`/`list[int]` parameter becomes a native array, keeping list semantics:

    At entry (guards, before any effect): the argument is exactly a `list`, every element is exactly
    `float` (F64_ARRAY) or exactly `int` within i64 (I64_ARRAY), and no two array parameters are
    the same list object (aliasing would make separate copies diverge). Then the elements are copied
    into a C array. The function body only indexes, stores into and takes `len` of it, no appends, and
    runs no user code at all: the verifier rejects a function holding an array that is not closed
    (`verify/array-open`, #203), so the list and the array cannot diverge observably. On **every** exit, return, exception, or deopt, dirty elements are
    written back as new float/int objects before control leaves the function, so CPython's
    partial-update-then-raise behaviour is preserved, and a deopt redo starts from the same list
    state the interpreted function would see... which is only correct because a function with array
    stores is impure and therefore can never deopt after entry (`Function.may_deopt` is False).
    """

    name: str
    type: Type        # F64_ARRAY or I64_ARRAY
    stored: bool      # whether the body stores into it (write-back needed)
    # Buffer array (#149): None for a `list`. "array" (an exact `array.array`) means
    # the argument is NOT copied: the entry guard takes a Py_buffer export
    # (exact type, 1-D, C-contiguous, aligned 8-byte items, format `d` for F64_ARRAY and `q` or `l`
    # for I64_ARRAY, writable when `stored`), holds it until the function returns on every exit, and
    # every Index/StoreIndex is one relaxed atomic 8-byte load/store on the buffer memory, no
    # copy-in, no write-back, no critical section, no aliasing guard (two buffers may share memory
    # and reads then see writes in program order, as in the interpreter). `len` is the export's
    # shape[0], fixed for the call because the export forbids resizing. Closedness (#203) applies
    # unchanged. "numpy" (#220): an exact `numpy.ndarray` with dtype float64 (F64_ARRAY) or int64
    # (I64_ARRAY), same guard and element access; an out-of-range index raises numpy's IndexError
    # ("index i is out of bounds for axis 0 with size n"). The elements are numpy scalars in the
    # interpreter, see Box.np and BinOp.np.
    # Every other rule above for lists (stored, array-open, Index/StoreIndex/Len only) holds too.
    buffer: str | None = None


@dataclass(frozen=True)
class Function:
    """One compiled function. `params` lists every positional parameter in order (scalar Param,
    ArrayParam, or Param of type OBJ for anything else). Keyword-only, *args, **kwargs, defaults
    and decorators other than `compiled` are not compiled in this stage (the function stays
    interpreted; `frontend` records why).

    Local arrays (fannkuch, M2): a local of an array type holds a native array the function owns,
    created only by NewArray/CopyArray. It never escapes, used only by Index/StoreIndex/Len and
    as a CopyArray source; never returned, passed, boxed or captured, so stores into it are not
    effects (a function whose only stores are into local arrays can be pure), and no list object
    ever exists for it. Reassigning the local frees the old array; every exit frees all of them.
    Its length never changes after creation, so a bounds proof against Len(local) holds.

    `pure`: no effect outside its own locals, no stores into anything but locals, no CallObject,
    no GetAttr (a property can have effects), no Truth/CompareObj/ObjToFloat/OBJ BinOp/GetItem/
    Contains/Format/StoreItem/ForObj (they run user methods), only `Call`s to pure functions.

    Kotlin interop (maintainer: compiled code must still talk to Kotlin freely): a function that
    calls into the binder is impure, so its int arithmetic cannot use the deopting I64 path,
    unbounded ints there are OBJ (CPython's own int objects, PyNumber_*), while floats (F64 never
    deopts), bounded loop indices, and the loops around the calls are still native. Only a pure
    function may contain deopting nodes after entry.

    Entry guards are generated by the back end from the params: exact runtime type per scalar/array
    param (`type(x) is float`, not isinstance, CPython would compute with the int or the subclass
    it was given), i64 range for I64, the ArrayParam checks. A failing guard → deopt (always safe:
    nothing has happened yet). Wrong arity or keyword use → deopt too, so CPython raises its own
    TypeError. A function returning a value must return on every path (no falling off the end).
    """

    name: str
    params: tuple[Param | ArrayParam, ...]
    returns: Type
    locals: dict[str, Type]          # non-parameter locals only (temporaries included)
    body: tuple[Stmt, ...]
    pure: bool
    may_deopt: bool
    source_line: int
    # Module globals of a scalar type read ONCE at entry, as part of the guards: each must be
    # exactly that type (else deopt, before any effect), and the body reads it as `Local(name)`.
    # Legal only for a *closed* function, one that runs no user code (no GetAttr, CallObject,
    # Truth, CompareObj, ObjToFloat, OBJ BinOp, GetItem, Contains, Format, StoreItem, ForObj; Calls
    # only to closed functions), so nothing can
    # rebind the global between entry and use, and reading it once equals reading it at each use.
    # That holds for this thread only: another thread can rebind it and then write state read later
    # (#414, SPEC N-8). So a pure function without lists whose call tree reads two or more entry-global
    # names, or one and any other shared state, runs under the dict-watcher epoch (cgen `_needs_epoch`),
    # and a function with an effect or list copies has no shared read after entry
    # (`verify/entry-global-order`). A signal handler on this thread is user code too: an epoch call's
    # polls run it and then compare the epoch, so a rebinding there redoes the call (cgen
    # `tp_epoch_poll_slow`, leader decision 2026-11-15; the means are an agent proposal).
    entry_globals: tuple[Param, ...] = ()
    # A method of the compiled fixed-layout class `owner` (SPEC N-11, #155): `name` is
    # "<owner>.<method>" and params[0] is `self` as Param(OBJ, cls=owner, optional=False). The
    # back end does not replace a module global with it; it installs a descriptor in the class dict
    # (cgen.py "Methods"). None: a module-level function.
    owner: str | None = None
    # SPEC N-12a: OBJ locals that are virtual objects (scalar-replaced `New(C, ...)`: no object is
    # allocated, the fields live in C locals). Set only by `verify`, which recomputes the set and
    # rejects a function whose declared entries differ (`verify/virtual-escape`). The front end
    # leaves it empty.
    virtual: tuple[str, ...] = ()
    # SPEC N-15: the user marked this function `@compiled(parallel=True)` (#358).
    # Only a marked function can hold a `ForRange.par`.
    parallel: bool = False
    # SPEC N-8 (#153, #332): the user marked this function `@compiled(fastmath=True)` (reassociation
    # and FMA contraction enabled for this function only; NOT CPython-identical).
    fastmath: bool = False
    # SPEC N-12b (#268): the producers whose result this pure function keeps inside one statement
    # (`check(make(d))`), so each such call builds its objects in a region. Set only by `verify`,
    # which recomputes it and rejects a function whose declared entries differ
    # (`verify/region-escape`). The front end leaves it empty.
    regions: tuple[str, ...] = ()
    # SPEC N-8 (#152, option (i)): one entry per loop of a snapshot-holding function (see `resume.py`),
    # in IR pre-order. Empty for a function that holds no snapshot. A loop whose continuation cannot
    # be generated (an enclosing `with`/`try`, say) has no entry: its `site` is missing from the tuple.
    # After the loop entries, a function with an effect that holds list copies has one statement
    # entry (`Resume.stmt` set) per source statement holding a Call (#390 item 5, statement exits).
    resumes: tuple["Resume", ...] = ()
    # AOT mode (SPEC N-17, #486): return class (C or C | None) and local variable classes.
    return_cls: str | None = None
    return_optional: bool = False
    local_classes: dict[str, tuple[str, bool]] = field(default_factory=dict)
    # AOT mode, tier S2a: the element class of every OBJ_LIST local (a list of instances of that class)
    list_classes: dict[str, str] = field(default_factory=dict)
    # AOT mode, tier S2b: the key and value types of every DICT local and parameter, and of a DICT return
    dict_kinds: dict[str, DictKinds] = field(default_factory=dict)
    return_kinds: DictKinds | None = None
    return_view: str | None = None      # a returned view ("keys", ...): the DICT returned is the view's dict


@dataclass(frozen=True)
class Resume:
    """A Python continuation of one loop of a snapshot-holding function (SPEC N-8, OSR-style exit).

    `site`: the loop's number, counting every ForRange, While and ForObj of `Function.body` in IR
    pre-order: statements in order, `If.then` before `If.orelse`, a loop numbered before the loops
    in its body. `name`: `__tp_resume_<function>_<site>` (a `.` in a method's name becomes `_`).
    `params`: the exact parameter order of the continuation, see `resume.py` for the contract.
    `source`: the Python source of the `def`, followed by a line replacing `co_name`/`co_qualname`
    with the interpreted function's. `linemap`: for each line of `source` (index = line - 1) the
    original (lineno, end_lineno, col_offset, end_col_offset); `resume.resume_sources` uses it so
    the continuation's code object carries the original line numbers.

    A statement continuation (#390 item 5, SPEC N-8 "statement exit") has `stmt` set to the number
    of a source statement (`Stmt.stmt`), `site` -1 and `name` `__tp_resume_<function>_s<stmt>`: it
    re-executes that statement from its start, then runs the rest of the function, with the same
    parameter contract except that the statement itself has no hidden state (only its enclosing
    loops do). `stmt` None: a loop continuation as above."""

    site: int
    name: str
    params: tuple[str, ...]
    source: str
    linemap: tuple[tuple[int, int, int, int], ...] = ()
    stmt: int | None = None


@dataclass(frozen=True)
class FieldDecl:
    """One typed field of a compiled class (AOT mode, SPEC N-17, #486)."""

    name: str
    type: Type
    cls: str | None = None
    optional: bool = False
    kinds: DictKinds | None = None      # tier S2b: the key and value types of a DICT field


@dataclass(frozen=True)
class ClassDecl:
    """A compiled fixed-layout class (SPEC N-11): `__slots__` written by the user equal to its
    annotated fields, base object, no metaclass, no decorator but @compiled. The module init checks
    every field's class attribute is a member descriptor and captures it; if not, the class is
    not compiled (functions using it deopt at entry or are refused by the verifier)."""

    name: str
    fields: tuple[str, ...]
    trivial_init: bool      # __init__(self, *fields-in-order) doing exactly `self.f = f` once each
    # The class defines `__del__` (a finalizer): its objects are never placed in a region (SPEC N-12b),
    # freeing a region would skip the finalizer.
    has_del: bool = False
    field_decls: tuple[FieldDecl, ...] = ()


@dataclass(frozen=True)
class Module:
    name: str
    functions: tuple[Function, ...]
    # Functions the front end left interpreted, with the reason (for `demo` and the coverage table).
    skipped: dict[str, str] = field(default_factory=dict)
    classes: tuple[ClassDecl, ...] = ()
    # SPEC N-12b: the producers and consumers that get a second, internal entry taking a region.
    # Set only by `verify`, which recomputes it (a declared set that differs is `verify/region-escape`).
    region_variants: tuple[str, ...] = ()


# --- region field kinds (SPEC N-12b), shared by `verify` (proof) and `cgen` (layout) ---------------------

REGION_SCALAR_KINDS = {Type.I64: "i64", Type.F64: "f64", Type.BOOL: "bool"}


def direct_lists(f: object) -> bool:
    """#255 (design/typedpython.md 4.3.6): a function with three or more list parameters reads them
    element by element, without copy-in or critical section. CPython's critical sections lock one or
    two objects, and copying three or more lists in at one instant needs them all locked (the leader
    decision of 2026-11-13 excludes any lock outside that API). Each `Index` is one
    `PyList_GetItemRef` in program order, the interpreter's own atomic read; `Len` is the current
    length. In a pure function (d1) an element of the wrong type deopts (legal: nothing has happened
    yet). A function with an effect is `direct_writer` (d2). No entry global (a snapshot read at
    entry could disagree with later list reads), no buffer parameter, no CopyArray of a list
    parameter and no parallel loop: the verifier (`verify/array-direct`) and cgen refuse them."""
    params = getattr(f, "params", ())
    return sum(isinstance(p, ArrayParam) and p.buffer is None for p in params) > 2


def direct_writer(f: object) -> bool:
    """#255 (d2), agent proposal, not confirmed: a function with an effect and three or more list
    parameters (`direct_lists`, not pure). Every `StoreIndex` into a list parameter is one
    `PyList_SetItem` of a new object at once, in program order. Nothing deopts the call after entry:
    an element read that finds another type, an `Unbox` that fails (a module global of a scalar type
    is `Unbox(Global)`, read at its use) and a Call whose callee reports a deopt leave through the
    statement exit of the source statement being executed (`Stmt.stmt`, `Resume.stmt`), which
    re-executes that statement, interpreted, from its start. Sound only by the per-statement rule
    (`resume.exit_nodes`, verifier `verify/array-direct`, cgen CGenError): in each source statement
    every node that can leave comes before the statement's first effect."""
    return direct_lists(f) and getattr(f, "pure", False) is not True


def region_arg_kind(a: object, ref_class) -> object:
    """The region kind one `New` argument gives its field: "i64" / "f64" / "bool" for a `Box` of that
    scalar, "null" for the `None` constant, ("ref", class) for a value of a region class (`ref_class(a)`
    names the class of a `New` or a producer `Call`, or is None). None: the argument cannot be stored in a
    region at all (any other object reference)."""
    if type(a) is Box and a.np is None:
        return REGION_SCALAR_KINDS.get(a.operand.type)
    if type(a) is Const and a.type is Type.OBJ and a.value is None:
        return "null"
    c = ref_class(a)
    return ("ref", c) if c is not None else None


def region_field_kinds(functions, ref_class) -> dict[str, list[set]]:
    """class -> per field the set of kinds observed over every `New` of the class in `functions`
    (a kind of None means an argument that cannot live in a region)."""
    out: dict[str, list[set]] = {}

    def visit(e: object) -> None:
        if type(e) is New:
            slots = out.setdefault(e.cls, [set() for _ in e.args])
            if len(slots) == len(e.args):
                for s, a in zip(slots, e.args):
                    s.add(region_arg_kind(a, ref_class))
        for x in _children_of(e):
            visit(x)

    for f in functions:
        for st in f.body:
            visit(st)
    return out


def _children_of(node: object):
    for fl in fields(node):
        v = getattr(node, fl.name)
        for x in (v if isinstance(v, tuple) else (v,)):
            if isinstance(x, (Expr, Stmt)):
                yield x


def region_field_layout(kinds: list[set]) -> tuple | None:
    """The one kind of each field, or None when some field mixes kinds (a scalar and a pointer, two
    scalar types, pointers to two classes) or holds an argument a region cannot store. A field that is
    only ever `None` is ("ref", None)."""
    out = []
    for s in kinds:
        real = s - {"null"}
        if None in s or len(real) > 1:
            return None
        if real:
            out.append(next(iter(real)))
        elif "null" in s:
            out.append(("ref", None))
        else:
            return None
    return tuple(out)
