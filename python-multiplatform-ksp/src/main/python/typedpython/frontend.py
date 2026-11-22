"""Front end: Python AST + Pyrefly-inferred types -> the typed IR (`ir.py`, #41, SPEC N-8).

`lower(path)` reads one module and returns an `ir.Module`:

- Which functions: module-level `def`s decorated with the builtin `@compiled` (a bare name, no
  import, #42). That decorator is the only mark; there is no module-level pragma (#353).
- A function is lowered only when **all** of it is expressible in the IR; otherwise it is left out
  and `Module.skipped[name]` says why, with the line. There is no partial lowering.
- Types: parameters and returns from their annotations (`int`/`float`/`bool`, `list[float]` and
  `list[int]` parameters as native arrays, anything else an opaque object); locals from their
  annotation, a `for ... in range()` target, or else the Pyrefly-inferred type of their
  assignments (read back through the same probe the gate uses, `rebinding.probe`).

Purity and deopt (ir.Function). Each function is lowered first as if pure; if the result is impure
(it stores into an array, calls an object, reads an attribute, or calls an impure function), it is
lowered again in impure mode, where nothing may deopt: an int whose i64 arithmetic is not proved
free of overflow is a CPython int object (OBJ, `PyNumber_*`), a call to a compiled function that
may deopt goes through the module global (`CallObject(Global)`), and only `for ... in range()`
targets whose every binding is such a loop stay I64. The proof is a small interval analysis:
constants, range targets (between their bounds), `len()` (at most 2**62), the i64 range of a guarded
parameter, the join of what a plain local is assigned (a fixpoint over lowering rounds, widened
when it keeps growing), and the comparisons around a use (`if n > 0:` narrows `n` inside, `while
lo < hi:` narrows both at the top of each iteration, a returning guard narrows the code after it;
a narrowing ends at the next assignment; verify.py "Local variables and guards" is the contract and
the verifier recomputes all of it). It is recorded in the IR: an I64 ADD/SUB/MUL/NEG it proves cannot
overflow is `proven=True` (the verifier re-proves it; a proven op does not deopt), and so is an I64
`//` or `%` by a Const power of two of a value it proves >= 0 (the back end emits a shift / mask).
Any other division or modulo of ints cannot be recorded, so it counts as deopting. An impure caller of a pure callee that may deopt and returns F64, BOOL
or NONE emits `Call(redo=True)`: only the callee is redone on a deopt.

Fixed-layout classes (SPEC N-11, `ir.ClassDecl`). A module-level class (in a marked module, or
decorated `@compiled`) becomes a `ClassDecl` only when its layout is fixed in CPython too; see
`_class_decl` for the rules. A class that fails them has no `ClassDecl`, an entry in
`Module.skipped` under the class name with the reason, and the functions using it lower as before
(`GetAttr`, `CallObject`). Over a `ClassDecl`: `None` is `Const(None, OBJ)`, `is`/`is not` is
`ir.Is`, a parameter annotated `C` / `C | None` / `Optional[C]` is an OBJ `Param(cls=C, optional)`,
`obj.field` is a `FieldGet` (exact type proved: a non-optional never-rebound parameter, or a local
only ever bound to `C(...)` built as `New`; otherwise `CheckExact` in a pure function, and the
function is skipped in an impure one), `obj.field = v` is a `FieldSet` (an effect; only on a proved
object), and `C(args)` is `New` when `ClassDecl.trivial_init` (arguments in `__slots__` order) else
`CallObject(Global(C))`. `trivial_init` requires the i-th `__init__` parameter to be stored into
the i-th slot (ir.ClassDecl: "`__init__(self, *fields-in-order)`"); other orders stay an object call.

Methods (SPEC N-11, #155). The plain instance methods of a `ClassDecl` class (`_method_targets`: no
decorator, not a dunder, once in the class body, no `super()`/`__class__`, `self` unannotated or
annotated with the class) are lowered like functions under the IR name `C.m` (`ir.Function.owner`), with
`self` as `Param(OBJ, cls=C)`. Every other method of such a class is in `Module.skipped` under `C.m` with
the reason. `obj.m(args)` on a local of class C (`method_call`) is `Call("C.m", (CheckMethod(obj, C, m),
*args))` in a pure function when the callee is a pure compiled method with no entry globals and matching
argument types; in every other case it stays the generic `CallObject(GetAttr(obj, m))`, which reaches the
installed descriptor through CPython's own lookup.

Objects, str and dict code (#150). A function may also use, as object operations (each is the C API
call the interpreter's bytecode makes, ir.py says which): `str` constants, `{}` and `[]`, `o[i]`
(`GetItem`) and `o[i] = v` (`StoreItem`) on anything that is not a native array, `x in c` /
`x not in c` (`Contains`), f-strings (`Format` / `BuildString`, a constant format spec only),
`for t in obj` and `for a, b in obj` (`ForObj`), and augmented assignment on objects in its in-place
form. `for v in range(n)` whose bound is a CPython int object (an impure function's ints are) is a
`ForObj` over `range(n)` called as an object, and then `v` is an object too (`_ObjRange`). All of
them make the function impure and not closed, so it can hold no array and no entry global. What stays
interpreted: non-empty list/dict displays, slices, `del`, tuple-assignment, comprehensions,
`x[i] += v` on an object, a format spec with a nested field.

Entry globals (ir.Function.entry_globals). A module-level name assigned once at top level, of type
float/int/bool (annotated, or literals and arithmetic of such names), never rebound or deleted in
the module, and read in a *closed* function (one that runs no user code, decided after lowering)
is read once at entry: a `Param` of the function and `Local(name)` at every read. Elsewhere it is a
`Global` (OBJ). In a function with an effect and three or more list parameters (#255 (d2),
`ir.direct_writer`, agent proposal) such a name is `Unbox(Global(name))` instead, read at each use.

AOT mode (`lower(path, aot=True)`, SPEC N-17, #451; agent proposal, not owner-confirmed). The standalone
build of `typedpython.aot` has no interpreter to fall back to, so it reads the same source with these
differences, and only these (with the flag off nothing below applies):

- every top-level `def` is a candidate, `@compiled` or not (owner decision D6);
- a function is lowered once, in the pure mode, never as an impure function: there is no deopt, so
  nothing needs the `INT` type, an int local is `I64`, and each int operation carries its `proven`
  flag from the interval proof exactly as in a pure function (`print` does not change that);
- `print(...)` is an `ir.Print` statement (positional scalar or string-literal arguments, `print` not
  rebound in the module) and is not an effect;
- a module-level `NAME = <constant expression>` / `NAME: int|float|bool = <constant expression>` bound
  once is read as `ir.Const(..., name=NAME)` (`fold_constant`), never as a `Global` or an entry global;
  a function that assigns the name makes it a local;
- the `if __name__ == "__main__":` block that ends the module is lowered as the function `AOT_ENTRY`
  (`-> None`, no parameters) with the block's own statements and line numbers;
- lists (#451): a `list[float]` / `list[int]` is a native array for its whole life and a value. A local is
  one when annotated or inferred so (`_find_local_arrays_aot`), whatever binds it: a creation (`[a, b]`
  `ListDisplay`, `[v] * n` / `n * [v]` and `list(range(n))` `NewArray`, `a[:]` `CopyArray`), another list's
  name (`Local` of an array type: both names mean the one list) or a call of a program function that
  returns a list (`Call` of an array type, `-> list[float]`). A list is passed to a program function by
  name and is the caller's own, so two parameters may be one list and there is no copy-in, no entry
  guard and no limit on the parameter count. Indexing, `len` and element stores are `Index`, `Len` and
  `StoreIndex` as in the default mode; anything else done to a list is a `Skip` naming it;
- a function that returns `tuple[T0, T1, ...]` of scalars returns `ReturnTuple` (its IR return type is
  OBJ, as in the default mode) and is called only to be unpacked, `a, b = f(...)` (`Unpack`);
- `print(f"...")`: an f-string is a `print` argument only (`FString` of `Const` text and `FormatField`s,
  `{e}` or `{e:.Nf}`), never a string value; conversions, `=`, widths, other presentation types and nested
  fields are skipped;
- `a if c else b` of two scalars of one type is `Cond` (each branch lowered under the guard facts of the
  test); `abs(x)` is `fabs` (a float) or `x if x >= 0 else -x` (an int, whose `-x` carries the proof
  flag like any negation); `min(a, b)` / `max(a, b)` are `b if b < a else a` / `b if b > a else a`, which is
  what CPython returns (a tie and a NaN keep the first argument);
- `int(sys.argv[i])` is `ArgvInt` (the back end holds its value as a big-integer-capable int, `aot/rep.py`; under
  `--strict-int` it refuses it: `aot/int-unproven`) and `len(sys.argv)` is `ArgvLen`
  (`import sys` bound once; every other use of `sys` is skipped);
- no continuation (`resume`) is written: nothing is redone in an interpreter.
"""
from __future__ import annotations

import ast
import dataclasses
import re
import tempfile
from collections.abc import Iterable
from dataclasses import dataclass, field
from pathlib import Path

from typedpython import gate, ir, pyrefly, rebinding, resume, taint
from typedpython.ir import Type

I64_MIN, I64_MAX = -2**63, 2**63 - 1
TOP = (I64_MIN, I64_MAX)
EXACT_INT_FLOAT = 2**53  # |n| <= this: int/int true division is exact-rounded in C (ir.BinOp)

AOT_MAX_PRECISION = 20  # AOT mode: the largest N of an f-string field `{x:.Nf}`
AOT_MAX_STR_CONSTANT = 1 << 24  # AOT mode: the longest str module constant (it is embedded in the C source)

LEN_MAX = 2**62  # a native array is never longer (the verifier's Len interval is [0, 2**62])

Interval = tuple[int, int] | None  # None: no value at all (not yet known to be reached)

TYPE_NAME = {
    Type.I64: "int", Type.F64: "float", Type.BOOL: "bool", Type.NONE: "None", Type.OBJ: "object",
    Type.INT: "int", Type.STR: "str", Type.STR_LIST: "list[str]", Type.STR_OPT: "str | None",
    Type.F64_ARRAY: "list[float]", Type.I64_ARRAY: "list[int]", Type.BOOL_ARRAY: "NDArray[np.bool_]",
}
SCALAR_KIND = {"int": Type.I64, "float": Type.F64, "bool": Type.BOOL}
# AOT mode (tier S1, PR-S1a): what an annotation names beside the scalars. The default mode never reads it.
AOT_KIND = {**SCALAR_KIND, "str": Type.STR, "list[str]": Type.STR_LIST,
            # tier S2a: the spellings of `str | None`
            "str | None": Type.STR_OPT, "None | str": Type.STR_OPT, "Optional[str]": Type.STR_OPT,
            "typing.Optional[str]": Type.STR_OPT, "str|None": Type.STR_OPT, "None|str": Type.STR_OPT}


def _aot_kind(kind: str) -> str:
    """AOT mode: an annotation or an inferred type in the spelling AOT_KIND uses (quotes off; Pyrefly's
    `LiteralString`, also inside a generic, is a str)."""
    kind = kind.strip("'\"").replace("typing.LiteralString", "str").replace("LiteralString", "str")
    while "str | str" in kind:          # (`list[LiteralString | str]`, Pyrefly's type after `s += ...`: a list of str)
        kind = kind.replace("str | str", "str")
    return kind
# AOT mode, tier S2a: the list methods called as statements (they return None)
LIST_STMT_METHODS = frozenset({"append", "insert", "extend", "clear", "reverse", "remove"})
LIST_METHOD_NAMES = LIST_STMT_METHODS | {"pop", "index", "count"}
# AOT mode, tier S1b: the str methods (design 4.3.1): name -> (parameter names in order, the result type)
STR_METHODS = {
    "split": (("sep", "maxsplit"), Type.STR_LIST), "join": (("iterable",), Type.STR),
    "strip": (("chars",), Type.STR), "lstrip": (("chars",), Type.STR), "rstrip": (("chars",), Type.STR),
    "lower": ((), Type.STR), "upper": ((), Type.STR),
    "startswith": (("prefix", "start", "end"), Type.BOOL), "endswith": (("prefix", "start", "end"), Type.BOOL),
    "find": (("sub", "start", "end"), Type.I64), "replace": (("old", "new", "count"), Type.STR),
    "isalpha": ((), Type.BOOL), "isdigit": ((), Type.BOOL),
}
STR_METHOD_KEYWORDS = {"split": ("sep", "maxsplit"), "replace": ("count",)}
ARRAY_KIND = {"list[float]": Type.F64_ARRAY, "list[int]": Type.I64_ARRAY}

BINOP = {
    ast.Add: ir.BinOpKind.ADD, ast.Sub: ir.BinOpKind.SUB, ast.Mult: ir.BinOpKind.MUL,
    ast.Div: ir.BinOpKind.TRUEDIV, ast.FloorDiv: ir.BinOpKind.FLOORDIV, ast.Mod: ir.BinOpKind.MOD,
    ast.LShift: ir.BinOpKind.LSHIFT, ast.RShift: ir.BinOpKind.RSHIFT,
    ast.BitAnd: ir.BinOpKind.AND, ast.BitOr: ir.BinOpKind.OR, ast.BitXor: ir.BinOpKind.XOR,
}
BITWISE = (ir.BinOpKind.AND, ir.BinOpKind.OR, ir.BinOpKind.XOR)
COMPARE = {
    ast.Eq: ir.CompareKind.EQ, ast.NotEq: ir.CompareKind.NE, ast.Lt: ir.CompareKind.LT,
    ast.LtE: ir.CompareKind.LE, ast.Gt: ir.CompareKind.GT, ast.GtE: ir.CompareKind.GE,
}
MATH_ARITY = {
    ir.MathFunc.SQRT: 1, ir.MathFunc.EXP: 1, ir.MathFunc.LOG: 1, ir.MathFunc.LOG2: 1,
    ir.MathFunc.LOG10: 1, ir.MathFunc.SIN: 1, ir.MathFunc.COS: 1, ir.MathFunc.TAN: 1,
    ir.MathFunc.ASIN: 1, ir.MathFunc.ACOS: 1, ir.MathFunc.ATAN: 1, ir.MathFunc.ATAN2: 2,
    ir.MathFunc.SINH: 1, ir.MathFunc.COSH: 1, ir.MathFunc.TANH: 1, ir.MathFunc.FABS: 1,
    ir.MathFunc.HYPOT: 2, ir.MathFunc.POW: 2, ir.MathFunc.COPYSIGN: 2, ir.MathFunc.FMOD: 2,
    ir.MathFunc.ISNAN: 1, ir.MathFunc.ISINF: 1, ir.MathFunc.ISFINITE: 1,
    ir.MathFunc.FLOOR: 1, ir.MathFunc.CEIL: 1, ir.MathFunc.TRUNC: 1,
}
_DEFAULT_MATH_FUNCS = frozenset({
    ir.MathFunc.SQRT, ir.MathFunc.EXP, ir.MathFunc.LOG, ir.MathFunc.SIN,
    ir.MathFunc.COS, ir.MathFunc.TAN, ir.MathFunc.FABS, ir.MathFunc.ATAN2,
    ir.MathFunc.HYPOT,
})
_MATH_BOOL_FUNCS = frozenset({ir.MathFunc.ISNAN, ir.MathFunc.ISINF, ir.MathFunc.ISFINITE})
_MATH_INT_FUNCS = frozenset({ir.MathFunc.FLOOR, ir.MathFunc.CEIL, ir.MathFunc.TRUNC})
_MATH_CONSTS = {
    "pi": 3.141592653589793,
    "e": 2.718281828459045,
    "tau": 6.283185307179586,
    "inf": float("inf"),
    "nan": float("nan"),
}

UNSUPPORTED = {
    "Try": "try statement", "TryStar": "try statement", "With": "with statement",
    "AsyncWith": "async with statement", "Match": "match statement", "Raise": "raise statement",
    "Assert": "assert statement", "Global": "global declaration",
    "Nonlocal": "nonlocal declaration", "FunctionDef": "nested def",
    "AsyncFunctionDef": "nested async def", "ClassDef": "nested class",
    "Import": "import inside a function", "ImportFrom": "import inside a function",
    "Delete": "del statement", "AsyncFor": "async for", "TypeAlias": "type alias",
    "Lambda": "lambda", "ListComp": "list comprehension", "SetComp": "set comprehension",
    "DictComp": "dict comprehension", "GeneratorExp": "generator expression",
    "List": "list display", "Tuple": "tuple display", "Dict": "dict display",
    "Set": "set display", "Yield": "yield (generator)", "YieldFrom": "yield from (generator)",
    "Await": "await", "IfExp": "conditional expression", "JoinedStr": "f-string",
    "NamedExpr": "assignment expression", "Starred": "starred expression", "Slice": "slicing",
}


class Skip(Exception):
    """This function cannot be expressed in the IR; it stays interpreted."""

    def __init__(self, line: int, message: str) -> None:
        super().__init__(f"line {line}: {message}")
        self.reason = f"line {line}: {message}"


def _unsupported(node: ast.AST) -> Skip:
    what = UNSUPPORTED.get(type(node).__name__, f"`{type(node).__name__}`")
    return Skip(getattr(node, "lineno", 0), f"{what} is not supported")


def _src(node: ast.AST) -> str:
    return ast.unparse(node)


OPTIONAL_MARK = "(aot/optional)"


def _optional(line: int, node: ast.AST, cls: str | None = None) -> Skip:
    """AOT mode, tier S2a: a `str | None` value used where a str is needed, or a `C | None` value whose field or
    method is used, with no test before it that proves it is not None (design 4.3.2). The marker makes the finding
    `aot/optional` (aot/__init__.py)."""
    what = (f"a `{cls} | None` value whose field or method is used" if cls is not None
            else "a `str | None` value used where a str is needed")
    return Skip(line, f"`{_src(node)}` is {what}, and nothing before it proves it is not None {OPTIONAL_MARK}: test "
                      "`is not None` first (`if x is not None:`, or an `if x is None:` branch that leaves)")


# --- the module ----------------------------------------------------------------------------------

@dataclass
class _ModuleInfo:
    bindings: dict[str, list[tuple[int, str]]]   # module-level name -> [(line, how)]
    stdlib_math: bool
    scalar_globals: dict[str, Type] = field(default_factory=dict)  # entry-global candidates
    classes: dict[str, ir.ClassDecl] = field(default_factory=dict)  # compiled fixed-layout classes
    # Top-level `import x [as y]` / `from m import n [as k]` names bound exactly once in the module:
    # local name -> dotted qualified name (`a` -> `array`, `array` -> `array.array`).
    imports: dict[str, str] = field(default_factory=dict)
    # AOT mode (`lower(path, aot=True)`): the module constants (`aot_constants`), name -> the Const a
    # read of it lowers to. `aot` is False in the default mode, and `aot_consts` is then empty.
    aot: bool = False
    aot_consts: dict[str, ir.Const] = field(default_factory=dict)
    # AOT mode: `import sys` is the one plain import of `sys` (only `sys.argv` is used, see `_Lowerer.call`),
    # and the functions that return a fixed tuple of scalars (name -> element types).
    aot_sys: bool = False
    aot_time: bool = False
    aot_tuples: dict[str, tuple[Type, ...]] = field(default_factory=dict)

    def bound(self, name: str) -> bool:
        return name in self.bindings


@dataclass
class _Signature:
    node: ast.FunctionDef
    params: tuple[ir.Param | ir.ArrayParam, ...]
    returns: Type
    # `-> tuple[a, b]`: the element kinds (a scalar type, else OBJ); None when not a tuple annotation.
    tuple_returns: tuple[Type, ...] | None = None
    tuple_ellipsis: bool = False   # `tuple[int, ...]`: a variable length
    return_annotation: str = ""
    owner: str | None = None       # the compiled class this is a method of (ir.Function.owner)
    return_cls: str | None = None
    return_optional: bool = False

    @property
    def qname(self) -> str:
        """The IR name: `f`, or `C.m` for a method (a module function `m` cannot collide)."""
        return f"{self.owner}.{self.node.name}" if self.owner else self.node.name


AOT_ENTRY = "__tp_main"      # the IR name of the `if __name__ == "__main__":` block in AOT mode


def lower(path: Path, *, aot: bool = False) -> ir.Module:
    """The IR of the module at `path`. `aot=True` is the AOT mode (module docstring, `verify.verify(m,
    aot=True)` is its verifier); it is off by default and nothing differs from the default mode then."""
    path = Path(path).resolve()
    source = path.read_text()
    tree = ast.parse(source, filename=str(path))
    if aot:
        aot_entry(tree)
    info = _module_info(tree)
    if aot:
        info.aot = True
        info.aot_consts = aot_constants(tree, info.bindings)
        sys_bound = info.bindings.get("sys", [])
        info.aot_sys = len(sys_bound) == 1 and info.imports.get("sys") == "sys"
        time_bound = info.bindings.get("time", [])
        info.aot_time = len(time_bound) == 1 and info.imports.get("time") == "time"

    skipped: dict[str, str] = {}
    class_decls, class_skips = _classes(tree, source, info, aot)
    info.classes = class_decls
    skipped.update(class_skips)
    signatures: dict[str, _Signature] = {}
    marked: set[str] = set()
    fastmath_marked: set[str] = set()
    for node, reason, parallel, fastmath in _targets(tree, source, info, aot):
        if reason is not None:
            skipped[node.name] = reason
            continue
        assert isinstance(node, ast.FunctionDef)
        try:
            signatures[node.name] = _signature(node, info.classes, info)
        except Skip as e:
            skipped[node.name] = e.reason
        else:
            if parallel:
                marked.add(node.name)
            if fastmath:
                fastmath_marked.add(node.name)
    for owner, node, reason in _method_targets(tree, info, aot):
        q = f"{owner}.{node.name}"
        if reason is not None:
            skipped[q] = reason
            continue
        try:
            signatures[q] = _signature(node, info.classes, info, owner=owner)
        except Skip as e:
            skipped[q] = e.reason

    if aot:
        info.aot_tuples = {n: s.tuple_returns for n, s in signatures.items()
                           if s.tuple_returns is not None and all(t in ir.SCALARS for t in s.tuple_returns)
                           and s.returns == Type.OBJ}

    kinds = _inferred_kinds(path, source, tree, [(q, s.node) for q, s in signatures.items()])

    # Optimistic start: every candidate lowered, pure, not deopting; then iterate to a fixpoint
    # (purity is the greatest fixpoint through calls, may_deopt the least).
    env: dict[str, ir.Function] = {
        name: ir.Function(name=name, params=s.params, returns=s.returns, locals={}, body=(), pure=True,
                    may_deopt=False, source_line=s.node.lineno, owner=s.owner,
                    return_cls=s.return_cls, return_optional=s.return_optional)
        for name, s in signatures.items()
    }
    results: dict[str, ir.Function | str] = {}
    for _ in range(25):
        new = {name: _lower_function(s, env, info, kinds) for name, s in signatures.items()}
        if new == results:
            break
        results = new
        env = {n: r for n, r in results.items() if isinstance(r, ir.Function)}
    else:
        results = {n: f"line {s.node.lineno}: purity/deopt analysis did not converge"
                   for n, s in signatures.items()}

    functions = []
    for name, result in results.items():
        if isinstance(result, ir.Function):
            functions.append(result)
        else:
            skipped[name] = result
    functions.sort(key=lambda f: f.source_line)
    functions = [
        dataclasses.replace(f, parallel=f.name in marked, fastmath=f.name in fastmath_marked)
        if (f.name in marked or f.name in fastmath_marked) else f
        for f in functions
    ]
    return ir.Module(path.stem, tuple(functions), skipped, tuple(class_decls.values()))


# --- AOT mode: the entry block and the module constants ---------------------------------------------

def is_main_guard(node: ast.stmt) -> bool:
    """`if __name__ == "__main__":` (the test only)."""
    if not isinstance(node, ast.If):
        return False
    t = node.test
    return (isinstance(t, ast.Compare) and isinstance(t.left, ast.Name) and t.left.id == "__name__"
            and len(t.ops) == 1 and isinstance(t.ops[0], ast.Eq) and len(t.comparators) == 1
            and isinstance(t.comparators[0], ast.Constant) and t.comparators[0].value == "__main__")


def aot_entry(tree: ast.Module) -> ast.FunctionDef | None:
    """AOT mode: replace the `if __name__ == "__main__":` block that ends the module (no `else`) by the
    function `AOT_ENTRY() -> None` whose body is the block's own statements, so the entry is lowered
    like any function and every statement keeps the line it has in the file. Nothing in the file is
    rewritten: this is a change of the parsed tree. Returns the function, or None when the module does
    not end in such a block."""
    if not tree.body or not is_main_guard(tree.body[-1]) or tree.body[-1].orelse:     # type: ignore[attr-defined]
        return None
    block = tree.body[-1]
    assert isinstance(block, ast.If)
    returns = ast.Constant(value=None)
    fn = ast.FunctionDef(
        name=AOT_ENTRY,
        args=ast.arguments(posonlyargs=[], args=[], vararg=None, kwonlyargs=[], kw_defaults=[],
                           kwarg=None, defaults=[]),
        body=block.body, decorator_list=[], returns=returns, type_comment=None)
    if "type_params" in ast.FunctionDef._fields:
        fn.type_params = []
    ast.copy_location(returns, block)
    ast.copy_location(fn, block)
    fn.end_lineno, fn.end_col_offset = block.end_lineno, block.end_col_offset
    tree.body[-1] = fn
    return fn


def fold_constant(node: ast.expr, known: dict[str, int | float | bool]) -> int | float | bool:
    """AOT mode: the value of a constant expression, an int, float or bool literal, unary minus (and
    plus), and `+ - * /` over literals and earlier constants (`known`), computed with Python's own
    arithmetic on the folded values (so it is CPython's value, a float stays a float and an int an
    int). Raises ValueError with the reason otherwise."""
    if isinstance(node, ast.Constant):
        if type(node.value) in (int, float, bool, str):     # str: tier S1 (PR-S1a)
            return node.value
        raise ValueError(f"a {type(node.value).__name__} literal is not a numeric constant")
    if isinstance(node, ast.Name):
        if node.id in known:
            return known[node.id]
        raise ValueError(f"`{node.id}` is not an earlier constant")
    if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.USub, ast.UAdd)):
        v = fold_constant(node.operand, known)
        return -v if isinstance(node.op, ast.USub) else +v
    if isinstance(node, ast.BinOp) and isinstance(node.op, (ast.Add, ast.Sub, ast.Mult, ast.Div)):
        a, b = fold_constant(node.left, known), fold_constant(node.right, known)
        try:
            if isinstance(node.op, ast.Add):
                return a + b
            if isinstance(node.op, ast.Sub):
                return a - b
            if isinstance(node.op, ast.Mult):
                return a * b
            return a / b
        except (ArithmeticError, TypeError, MemoryError) as e:
            raise ValueError(f"the expression raises {type(e).__name__}: {e}") from None
    raise ValueError(f"a `{type(node).__name__}` is not allowed in a constant expression "
                     "(only literals, unary minus and + - * / over earlier constants)")


def aot_constant(stmt: ast.stmt, known: dict[str, int | float | bool]) -> tuple[str, int | float | bool]:
    """AOT mode: (name, value) of the module-level `NAME = <constant expression>` or `NAME: T =
    <constant expression>` (T one of int, float, bool). The annotation must agree with the folded
    value's own type: CPython keeps `PI: float = 3` an int, so that is an error, not a conversion.
    Raises ValueError; the message completes "the module constant `NAME` ..."."""
    if isinstance(stmt, ast.AnnAssign):
        if not isinstance(stmt.target, ast.Name) or stmt.value is None:
            raise ValueError("needs a value (`NAME: float = <constant expression>`)")
        name, annotation, value_node = stmt.target.id, stmt.annotation, stmt.value
    else:
        assert isinstance(stmt, ast.Assign) and isinstance(stmt.targets[0], ast.Name)
        name, annotation, value_node = stmt.targets[0].id, None, stmt.value
    try:
        v = fold_constant(value_node, known)
    except ValueError as e:
        raise ValueError(f"is not a constant expression: {e}") from None
    if type(v) is int and not I64_MIN <= v <= I64_MAX:
        raise ValueError(f"is not a constant expression: the int value {v} is outside 64 bits "
                         "(big integers arrive in a later step)")
    if type(v) is str and len(v) > AOT_MAX_STR_CONSTANT:
        raise ValueError(f"is a string of {len(v)} characters, more than the {AOT_MAX_STR_CONSTANT} a constant may "
                         "have in the AOT mode (it is embedded in the program)")
    if annotation is not None:
        kind = (annotation.id if isinstance(annotation, ast.Name)
                and (annotation.id in SCALAR_KIND or annotation.id == "str") else None)
        if kind is None:
            raise ValueError(f"is annotated `{_src(annotation)}`, which is not int, float, bool or str")
        if type(v).__name__ != kind:
            hint = ("; write a float literal such as `1.0`" if kind == "float" and type(v) is int else "")
            raise ValueError(f"is annotated `{kind}` but its value is {_article(type(v).__name__)}, which "
                             f"CPython keeps as it is (an annotation converts nothing{hint})")
    return name, v


def _article(word: str) -> str:
    return f"{'an' if word[:1] in 'aeiou' else 'a'} {word}"


def aot_constants(tree: ast.Module, bindings: dict[str, list[tuple[int, str]]]) -> dict[str, ir.Const]:
    """AOT mode: every module-level typed constant, name -> the `Const` a read of it lowers to. A name
    bound more than once, or whose statement is not a constant (the AOT build reports those before the
    front end runs), is not one."""
    known: dict[str, int | float | bool] = {}
    out: dict[str, ir.Const] = {}
    for stmt in tree.body:
        if isinstance(stmt, ast.Assign) and len(stmt.targets) == 1 and isinstance(stmt.targets[0], ast.Name):
            name = stmt.targets[0].id
        elif isinstance(stmt, ast.AnnAssign) and isinstance(stmt.target, ast.Name):
            name = stmt.target.id
        else:
            continue
        if len(bindings.get(name, [])) != 1:
            continue
        try:
            _, v = aot_constant(stmt, known)
        except ValueError:
            continue
        known[name] = v
        t = (Type.BOOL if type(v) is bool else Type.I64 if type(v) is int else Type.STR if type(v) is str
             else Type.F64)
        out[name] = ir.Const(t, v, name=name)
    return out


def _module_info(tree: ast.Module) -> _ModuleInfo:
    bindings: dict[str, list[tuple[int, str]]] = {}

    def add(name: str, line: int, how: str) -> None:
        bindings.setdefault(name, []).append((line, how))

    for stmt in rebinding._own_statements(tree):
        if isinstance(stmt, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            add(stmt.name, stmt.lineno, "def")
        elif isinstance(stmt, ast.Import):
            for alias in stmt.names:
                stdlib = alias.name == "math" and alias.asname is None
                add(alias.asname or alias.name.split(".")[0], stmt.lineno,
                    "import math" if stdlib else "import")
        elif isinstance(stmt, ast.ImportFrom):
            for alias in stmt.names:
                add(alias.asname or alias.name, stmt.lineno, "import")
        else:
            for target in _binding_targets(stmt):
                for n in ast.walk(target):
                    if isinstance(n, ast.Name) and isinstance(n.ctx, ast.Store):
                        add(n.id, stmt.lineno, "assign")
    for node in ast.walk(tree):
        if isinstance(node, ast.Global):
            for name in node.names:
                add(name, node.lineno, "global")

    math = bindings.get("math", [])
    return _ModuleInfo(bindings, len(math) == 1 and math[0][1] == "import math",
                       _scalar_globals(tree, bindings), imports=_import_names(tree, bindings))


def _import_names(tree: ast.Module, bindings: dict[str, list[tuple[int, str]]]) -> dict[str, str]:
    """Names bound by a plain top-level import and by nothing else in the module (so the name
    certainly is that module or attribute when an annotation is evaluated)."""
    out: dict[str, str] = {}
    for stmt in tree.body:
        if isinstance(stmt, ast.Import):
            for alias in stmt.names:
                if alias.asname:
                    out[alias.asname] = alias.name
                else:
                    root = alias.name.split(".")[0]
                    out[root] = root
        elif isinstance(stmt, ast.ImportFrom) and stmt.level == 0 and stmt.module:
            for alias in stmt.names:
                if alias.name != "*":
                    out[alias.asname or alias.name] = f"{stmt.module}.{alias.name}"
    return {n: q for n, q in out.items() if len(bindings.get(n, [])) == 1}


def _qualified(node: ast.expr, info: _ModuleInfo) -> str | None:
    """`a.b` -> "array.b" through the module's own imports, else None."""
    parts: list[str] = []
    while isinstance(node, ast.Attribute):
        parts.append(node.attr)
        node = node.value
    if not isinstance(node, ast.Name) or node.id not in info.imports:
        return None
    return ".".join([info.imports[node.id], *reversed(parts)])


def _buffer_kind(annotation: ast.expr, info: _ModuleInfo) -> tuple[Type, str] | None:
    """The buffer-array reading of an annotation (#149), else None (an ordinary object parameter):
    `array.array[float]` / `array.array[int]` (any spelling that resolves, through this module's
    own imports, to `array.array`), and the builtin `memoryview[float]` / `memoryview[int]`. A bare `array.array`, another element type, a numpy
    `NDArray[...]` (not supported: its element arithmetic is numpy's), or a name bound more than
    once is not one."""
    if not isinstance(annotation, ast.Subscript):
        return None
    base = _qualified(annotation.value, info)
    arg = annotation.slice
    if base == "array.array" and isinstance(arg, ast.Name) and arg.id in ("float", "int") \
            and not info.bound(arg.id):
        return ARRAY_KIND[f"list[{arg.id}]"], "array"
    if isinstance(annotation.value, ast.Name) and annotation.value.id == "memoryview" \
            and not info.bound("memoryview") and isinstance(arg, ast.Name) \
            and arg.id in ("float", "int") and not info.bound(arg.id):
        return ARRAY_KIND[f"list[{arg.id}]"], "memoryview"
    if base == "numpy.typing.NDArray":
        dtype = _qualified(arg, info)
        if dtype in _NUMPY_DTYPE:
            return _NUMPY_DTYPE[dtype], "numpy"
    return None


def _element_only(fn: ast.FunctionDef, name: str) -> bool:
    """Whether every use of `name` in `fn` is `name[i]` (read or store, not a slice) or `len(name)`,
    and it is never rebound: the only uses a buffer array supports."""
    ok: set[int] = set()
    for n in ast.walk(fn):
        if isinstance(n, ast.Subscript) and isinstance(n.value, ast.Name) and n.value.id == name \
                and not isinstance(n.slice, ast.Slice) \
                and not (isinstance(n.slice, ast.Tuple) and any(isinstance(e, ast.Slice) for e in n.slice.elts)):
            ok.add(id(n.value))
        elif isinstance(n, ast.Call) and isinstance(n.func, ast.Name) and n.func.id == "len" \
                and len(n.args) == 1 and isinstance(n.args[0], ast.Name) and n.args[0].id == name:
            ok.add(id(n.args[0]))
    return all(id(n) in ok for n in ast.walk(fn)
               if isinstance(n, ast.Name) and n.id == name and not isinstance(n.ctx, ast.Param)
               ) and not any(isinstance(n, ast.Name) and n.id == name and isinstance(n.ctx, ast.Store)
                             for n in ast.walk(fn))


# numpy dtypes that are 8-byte buffer arrays (#220): the scalar type annotation they spell
_NUMPY_DTYPE = {"numpy.float64": Type.F64_ARRAY, "numpy.int64": Type.I64_ARRAY,
                "numpy.bool_": Type.BOOL_ARRAY, "numpy.bool": Type.BOOL_ARRAY}


def _scalar_globals(tree: ast.Module, bindings: dict[str, list[tuple[int, str]]]) -> dict[str, Type]:
    """Module-level names that are assigned exactly once, at top level, with a float/int/bool
    value, and are never rebound (`global`, `del`, `:=`, a loop target ...) anywhere in the module."""
    spoiled = {n.id for n in ast.walk(tree) if isinstance(n, ast.Name)
               and isinstance(n.ctx, ast.Del)}
    spoiled |= {n.target.id for n in ast.walk(tree)
                if isinstance(n, ast.NamedExpr) and isinstance(n.target, ast.Name)}
    found: dict[str, Type] = {}
    for stmt in tree.body:
        if isinstance(stmt, ast.AnnAssign) and isinstance(stmt.target, ast.Name) \
                and stmt.value is not None:
            name, annotated = stmt.target.id, SCALAR_KIND.get(_annotation_kind(stmt.annotation))
            t = annotated
        elif isinstance(stmt, ast.Assign) and len(stmt.targets) == 1 \
                and isinstance(stmt.targets[0], ast.Name):
            name, t = stmt.targets[0].id, _literal_type(stmt.value, found)
        else:
            continue
        if t is not None and len(bindings.get(name, [])) == 1 and name not in spoiled:
            found[name] = t
    return found


def _literal_type(node: ast.expr, known: dict[str, Type]) -> Type | None:
    """The type of an expression made only of literals, arithmetic and `known` globals."""
    if isinstance(node, ast.Constant):
        v = node.value
        if isinstance(v, bool):
            return Type.BOOL
        if isinstance(v, int):
            return Type.I64 if I64_MIN <= v <= I64_MAX else None
        return Type.F64 if isinstance(v, float) else None
    if isinstance(node, ast.Name):
        return known.get(node.id)
    if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.USub, ast.UAdd)):
        t = _literal_type(node.operand, known)
        return t if t in (Type.I64, Type.F64) else None
    if isinstance(node, ast.BinOp) and type(node.op) in BINOP:
        a, b = _literal_type(node.left, known), _literal_type(node.right, known)
        if a not in (Type.I64, Type.F64) or b not in (Type.I64, Type.F64):
            return None
        if a == b == Type.I64 and not isinstance(node.op, ast.Div):
            return Type.I64
        return Type.F64
    return None


def _binding_targets(stmt: ast.stmt) -> list[ast.expr]:
    if isinstance(stmt, ast.Assign):
        return list(stmt.targets)
    if isinstance(stmt, (ast.AugAssign, ast.AnnAssign, ast.For, ast.AsyncFor)):
        return [stmt.target]
    if isinstance(stmt, (ast.With, ast.AsyncWith)):
        return [i.optional_vars for i in stmt.items if i.optional_vars is not None]
    return []


# --- fixed-layout classes (SPEC N-11) --------------------------------------------------------------

# A user override of any of these runs user code on a slot read, write or construction, which the
# direct slot access (FieldGet/FieldSet/New) would skip.
SLOT_ACCESS_DUNDERS = ("__getattribute__", "__getattr__", "__setattr__", "__delattr__", "__new__")


def _is_compiled_decorator(d: ast.expr) -> bool:
    return (isinstance(d, ast.Name) and d.id == "compiled") or (
        isinstance(d, ast.Call) and isinstance(d.func, ast.Name) and d.func.id == "compiled"
    )


def _compiled_decorator_options(d: ast.expr) -> tuple[bool, bool, str | None]:
    """Parse `@compiled` or `@compiled(parallel=..., fastmath=...)` -> (parallel, fastmath, error_reason_or_None)."""
    if isinstance(d, ast.Name):
        return False, False, None
    assert isinstance(d, ast.Call)
    if d.args:
        return False, False, (f"line {d.lineno}: `@compiled` takes keyword arguments only "
                              f"(got positional `{_src(d.args[0])}`)")
    parallel = False
    fastmath = False
    seen: set[str] = set()
    for kw in d.keywords:
        if kw.arg is None:
            return False, False, f"line {d.lineno}: `**{_src(kw.value)}` in `@compiled(...)` is not supported"
        if kw.arg not in ("parallel", "fastmath"):
            return False, False, (f"line {d.lineno}: `@compiled` keyword argument `{kw.arg}` is not "
                                  "supported (only `parallel` and `fastmath` with literal `True` or `False`)")
        if kw.arg in seen:
            return False, False, f"line {d.lineno}: `@compiled` keyword argument `{kw.arg}` is repeated"
        seen.add(kw.arg)
        if not (isinstance(kw.value, ast.Constant) and isinstance(kw.value.value, bool)):
            return False, False, (f"line {d.lineno}: `@compiled({kw.arg}=...)` requires a literal `True` "
                                  f"or `False`, got `{_src(kw.value)}`")
        if kw.arg == "parallel":
            parallel = kw.value.value
        else:
            fastmath = kw.value.value
    return parallel, fastmath, None


def is_opted_in(path: Path) -> bool:
    """Whether the front end considers the module for compilation at all: a module-level `def` / `class`
    decorated `@compiled` (the same test `_targets` and `_classes` apply)."""
    source = Path(path).read_text()
    return any(
        isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef))
        and any(_is_compiled_decorator(d) for d in node.decorator_list)
        for node in ast.parse(source, filename=str(path)).body)


def _classes(
    tree: ast.Module, source: str, info: _ModuleInfo, aot: bool = False,
) -> tuple[dict[str, ir.ClassDecl], dict[str, str]]:
    decls: dict[str, ir.ClassDecl] = {}
    skipped: dict[str, str] = {}
    known_names = {
        node.name for node in tree.body
        if isinstance(node, ast.ClassDef)
        and (aot or any(isinstance(d, ast.Name) and d.id == "compiled" for d in node.decorator_list))
    }
    for node in tree.body:
        if not isinstance(node, ast.ClassDef):
            continue
        marked = any(isinstance(d, ast.Name) and d.id == "compiled" for d in node.decorator_list)
        if not marked and not aot:
            continue
        result = _class_decl(node, tree, info, aot=aot, known_names=known_names)
        if isinstance(result, str):
            skipped[node.name] = result
        else:
            decls[node.name] = result
    return decls, skipped


def _aot_field_decl(fname: str, ann: ast.expr, known_names: set[str]) -> ir.FieldDecl | None:
    """Resolve an AOT class field annotation to an `ir.FieldDecl`."""
    kind = _annotation_kind(ann)
    if kind in SCALAR_KIND:
        return ir.FieldDecl(fname, SCALAR_KIND[kind])
    if kind.strip("'\"") == "str":
        return ir.FieldDecl(fname, Type.STR)            # tier S1b: an owned str (acyclic: a str holds nothing)
    if AOT_KIND.get(_aot_kind(kind)) is Type.STR_OPT:
        return ir.FieldDecl(fname, Type.STR_OPT)        # tier S2a
    if kind in ARRAY_KIND:
        return ir.FieldDecl(fname, ARRAY_KIND[kind])
    # class or class | None (including string forward references)
    stripped = kind.strip("'\"")
    optional = False
    for prefix in ("Optional[", "typing.Optional["):
        if stripped.startswith(prefix) and stripped.endswith("]"):
            stripped, optional = stripped[len(prefix):-1].strip(), True
    parts = [p.strip().strip("'\"") for p in stripped.split("|")]
    named = [p for p in parts if p != "None"]
    if len(named) == 1 and named[0] in known_names:
        optional = optional or ("None" in parts)
        return ir.FieldDecl(fname, Type.OBJ, cls=named[0], optional=optional)
    return None


def _class_decl(
    node: ast.ClassDef, tree: ast.Module, info: _ModuleInfo,
    aot: bool = False, known_names: set[str] | None = None,
) -> ir.ClassDecl | str:
    """The `ClassDecl` of a class whose layout is fixed in CPython too, else why it is not one."""
    name, at = node.name, f"line {node.lineno}: class `{node.name}`"
    other = [d for d in node.decorator_list if not (isinstance(d, ast.Name) and d.id == "compiled")]
    if other:
        return f"{at}: decorator `@{_src(other[0])}` is not supported (only `@compiled`)"
    if node.decorator_list and info.bound("compiled"):
        return f"{at}: `compiled` is bound in this module, so `@compiled` is not the builtin"
    if node.keywords:
        return f"{at}: has a metaclass or class keyword `{node.keywords[0].arg}`"
    bases = [b for b in node.bases if not (isinstance(b, ast.Name) and b.id == "object")]
    if bases or (node.bases and info.bound("object")):
        shown = _src(bases[0]) if bases else "object"
        return f"{at}: has a base class `{shown}` (only `object` is allowed)"
    if len(info.bindings.get(name, [])) > 1:
        lines = ", ".join(str(line) for line, _ in info.bindings[name])
        return f"{at}: `{name}` is bound more than once (lines {lines})"
    for n in ast.walk(tree):
        if isinstance(n, ast.Name) and isinstance(n.ctx, ast.Del) and n.id == name:
            return f"{at}: `{name}` is deleted (line {n.lineno}); bound more than once"
        if isinstance(n, ast.NamedExpr) and isinstance(n.target, ast.Name) and n.target.id == name:
            return f"{at}: `{name}` is rebound by `:=` (line {n.lineno}); bound more than once"
        if isinstance(n, ast.ClassDef) and any(isinstance(b, ast.Name) and b.id == name
                                               for b in n.bases):
            return f"{at}: is subclassed by `{n.name}` (line {n.lineno})"
        for target in _assigned_attributes(n):
            if isinstance(target.value, ast.Name) and target.value.id == name:
                return (f"{at}: its class attribute `{target.attr}` is assigned at line "
                        f"{target.lineno}")
        if isinstance(n, ast.Call) and isinstance(n.func, ast.Name) \
                and n.func.id in ("setattr", "delattr") and n.args \
                and isinstance(n.args[0], ast.Name) and n.args[0].id == name:
            return f"{at}: its class attribute is assigned by `{_src(n)}` at line {n.lineno}"

    slots: ast.expr | None = None
    annotated: list[str] = []
    annotated_anns: dict[str, ast.expr] = {}
    init: ast.FunctionDef | None = None
    has_del = False
    for st in node.body:
        if isinstance(st, ast.Expr) and isinstance(st.value, ast.Constant) \
                and isinstance(st.value.value, str):
            continue
        if isinstance(st, ast.Pass):
            continue
        if isinstance(st, ast.Assign) and len(st.targets) == 1 \
                and isinstance(st.targets[0], ast.Name) and st.targets[0].id == "__slots__":
            slots = st.value
        elif isinstance(st, ast.AnnAssign) and isinstance(st.target, ast.Name) and st.value is None:
            annotated.append(st.target.id)
            annotated_anns[st.target.id] = st.annotation
        elif isinstance(st, ast.FunctionDef):
            if st.name in SLOT_ACCESS_DUNDERS:
                return f"{at}: defines `{st.name}`, which runs user code on slot access"
            if st.name == "__init__":
                init = st
            if st.name == "__del__":
                has_del = True
        else:
            return (f"{at}: class body statement `{_src(st).splitlines()[0]}` (line {st.lineno}) "
                    "is not supported (only `__slots__`, bare field annotations and methods)")
    if slots is None:
        if not aot:
            return f"{at}: has no `__slots__`"
        fields = tuple(dict.fromkeys(annotated))
    else:
        if not (isinstance(slots, (ast.Tuple, ast.List)) and all(
                isinstance(e, ast.Constant) and isinstance(e.value, str) for e in slots.elts)):
            return f"{at}: `__slots__` is not a literal tuple or list of strings"
        fields = tuple(e.value for e in slots.elts)  # type: ignore[attr-defined]
        twice = next((f for f in fields if fields.count(f) > 1), None)
        if twice is not None:
            return f"{at}: `__slots__` lists `{twice}` twice"
        if set(fields) != set(annotated):
            return (f"{at}: `__slots__` {fields} does not equal its annotated fields "
                    f"{tuple(dict.fromkeys(annotated))}")
    field_decls: tuple[ir.FieldDecl, ...] = ()
    if aot:
        decls_list: list[ir.FieldDecl] = []
        names_set = known_names if known_names is not None else {name}
        for f in fields:
            fd = _aot_field_decl(f, annotated_anns[f], names_set)
            if fd is None:
                return f"{at}: field `{f}` has unsupported AOT type `{_src(annotated_anns[f])}`"
            decls_list.append(fd)
        field_decls = tuple(decls_list)
    return ir.ClassDecl(name, fields, init is not None and _trivial_init(init, fields), has_del, field_decls)


def _assigned_attributes(n: ast.AST) -> list[ast.Attribute]:
    targets: list[ast.expr] = []
    if isinstance(n, ast.Assign):
        targets = list(n.targets)
    elif isinstance(n, (ast.AugAssign, ast.AnnAssign)):
        targets = [n.target]
    elif isinstance(n, ast.Delete):
        targets = list(n.targets)
    elif isinstance(n, (ast.For, ast.AsyncFor)):
        targets = [n.target]
    found: list[ast.Attribute] = []
    for t in targets:
        found += [x for x in ast.walk(t) if isinstance(x, ast.Attribute)]
    return found


def _trivial_init(init: ast.FunctionDef, fields: tuple[str, ...]) -> bool:
    """`__init__(self, p0, p1, ...)` whose body is exactly `self.<f> = <param>` once per slot, with
    parameter i stored into slot i (`ir.ClassDecl.trivial_init`). Statement order is free."""
    a = init.args
    if init.decorator_list or a.posonlyargs or a.kwonlyargs or a.vararg or a.kwarg or a.defaults \
            or init.returns is not None and _src(init.returns) != "None" or not a.args:
        return False
    self_name, params = a.args[0].arg, [x.arg for x in a.args[1:]]
    body = list(init.body)
    if body and isinstance(body[0], ast.Expr) and isinstance(body[0].value, ast.Constant) \
            and isinstance(body[0].value.value, str):
        body = body[1:]
    if len(params) != len(fields) or len(set(params)) != len(params) or len(body) != len(fields):
        return False
    stored: dict[str, str] = {}
    for st in body:
        if not (isinstance(st, ast.Assign) and len(st.targets) == 1
                and isinstance(st.targets[0], ast.Attribute)
                and isinstance(st.targets[0].value, ast.Name)
                and st.targets[0].value.id == self_name and isinstance(st.value, ast.Name)
                and st.value.id in params and self_name not in params):
            return False
        field_name = st.targets[0].attr
        if field_name not in fields or field_name in stored:
            return False
        stored[field_name] = st.value.id
    return all(stored.get(f) == p for f, p in zip(fields, params))


def _aot_unqualified(kind: str, classes: dict[str, ir.ClassDecl]) -> str:
    """AOT mode: Pyrefly spells an inferred class with its module (`prog.Node`, `prog.Node | None`). An AOT program
    imports no module that defines a class (only math, sys and time), so a qualified name whose last part is a class of
    the program is that class (a local bound from a call result otherwise lost its class)."""
    parts, changed = [], False
    for p in kind.split("|"):
        q = p.strip()
        if "." in q and q.rsplit(".", 1)[1] in classes:
            q, changed = q.rsplit(".", 1)[1], True
        parts.append(q)
    # unchanged spelling unless a part was qualified; `_class_of` splits on a bare `|` without stripping blanks
    return "|".join(parts) if changed else kind


def _class_of(kind: str, classes: dict[str, ir.ClassDecl]) -> tuple[str, bool] | None:
    """`C`, `C | None`, `None | C`, `Optional[C]`, `"C | None"` (an annotation spelled by
    `_annotation_kind`) -> (C, optional) for a compiled class C, else None."""
    kind = kind.strip("'\"")
    optional = False
    for prefix in ("Optional[", "typing.Optional["):
        if kind.startswith(prefix) and kind.endswith("]"):
            kind, optional = kind[len(prefix):-1], True
    parts = [p.strip("'\"") for p in kind.split("|")]
    named = [p for p in parts if p != "None"]
    if len(named) != 1 or named[0] not in classes:
        return None
    return named[0], optional or len(named) != len(parts)


def _targets(
    tree: ast.Module, source: str, info: _ModuleInfo, aot: bool = False,
) -> list[tuple[ast.FunctionDef | ast.AsyncFunctionDef, str | None, bool, bool]]:
    found: list[tuple[ast.FunctionDef | ast.AsyncFunctionDef, str | None, bool, bool]] = []
    for node in tree.body:
        if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        marked = [d for d in node.decorator_list if _is_compiled_decorator(d)]
        if not marked and not aot:      # AOT mode: every top-level def is a target (owner decision D6)
            continue
        reason = None
        parallel = False
        fastmath = False
        if marked and info.bound("compiled"):
            line = info.bindings["compiled"][0][0]
            reason = (f"line {node.lineno}: `compiled` is bound in this module (line {line}), "
                      "so `@compiled` is not the builtin")
        elif other := [d for d in node.decorator_list if d not in marked]:
            reason = (f"line {other[0].lineno}: decorator `@{_src(other[0])}` is not supported "
                      "(only `@compiled`)")
        else:
            for d in marked:
                par, fm, r = _compiled_decorator_options(d)
                if r is not None:
                    reason = r
                    break
                parallel = parallel or par
                fastmath = fastmath or fm
        if reason is None:
            if isinstance(node, ast.AsyncFunctionDef):
                reason = f"line {node.lineno}: async function is not supported"
            elif len(info.bindings.get(node.name, [])) > 1:
                lines = ", ".join(str(line) for line, _ in info.bindings[node.name])
                reason = f"line {node.lineno}: `{node.name}` is bound more than once (lines {lines})"
        found.append((node, reason, parallel, fastmath))
    return found


def _method_targets(
    tree: ast.Module, info: _ModuleInfo, aot: bool = False,
) -> list[tuple[str, ast.FunctionDef, str | None]]:
    """(class, def, reason it stays interpreted or None) for every `def` in the body of a class that
    is a `ClassDecl` (SPEC N-11, #155). Only a plain instance method is a candidate: no decorator,
    not a dunder (the interpreter runs those: `__init__` is lowered by `trivial_init`, the rest
    would change what `repr`, `==`, `del` ... do when compiled), not a name-mangled `__private`,
    defined once in the class body, not using `super()` or `__class__` (the compiler creates the
    cell for the original function only), and `self` is unannotated or annotated with the class."""
    found: list[tuple[str, ast.FunctionDef, str | None]] = []
    for node in tree.body:
        if not isinstance(node, ast.ClassDef) or node.name not in info.classes:
            continue
        counts: dict[str, int] = {}
        for st in node.body:
            if isinstance(st, (ast.FunctionDef, ast.AsyncFunctionDef)):
                counts[st.name] = counts.get(st.name, 0) + 1
        for st in node.body:
            if isinstance(st, ast.AsyncFunctionDef):
                found.append((node.name, st, f"line {st.lineno}: async method is not supported"))  # type: ignore[arg-type]
                continue
            if not isinstance(st, ast.FunctionDef):
                continue
            at = f"line {st.lineno}"
            reason = None
            if st.decorator_list:
                reason = (f"{at}: decorator `@{_src(st.decorator_list[0])}` on a method is not "
                          "supported (a plain instance method only)")
            elif st.name.startswith("__") and not (aot and st.name == "__init__"):
                reason = (f"{at}: `{st.name}` is a dunder or name-mangled method; it stays "
                          "interpreted (the interpreter runs it)")
            elif counts[st.name] > 1:
                reason = f"{at}: `{st.name}` is defined more than once in the class body"
            elif not st.args.args and not st.args.posonlyargs:
                reason = f"{at}: a method without a positional `self` parameter"
            else:
                used = next((n for n in ast.walk(st) if isinstance(n, ast.Name)
                             and n.id in ("super", "__class__")), None)
                if used is not None:
                    reason = (f"{at}: `{used.id}` needs the class cell of the original function; "
                              "the method stays interpreted")
            found.append((node.name, st, reason))
    return found


def _signature(fn: ast.FunctionDef, classes: dict[str, ir.ClassDecl] | None = None,
               info: _ModuleInfo | None = None, owner: str | None = None) -> _Signature:
    args = fn.args
    line = fn.lineno
    if args.posonlyargs:
        raise Skip(line, "positional-only parameters are not supported")
    if args.kwonlyargs:
        raise Skip(line, "keyword-only parameters are not supported")
    if args.vararg is not None:
        raise Skip(line, f"`*{args.vararg.arg}` is not supported")
    if args.kwarg is not None:
        raise Skip(line, f"`**{args.kwarg.arg}` is not supported")
    if args.defaults:
        raise Skip(line, "default parameter values are not supported")
    params: list[ir.Param | ir.ArrayParam] = []
    kinds_map = AOT_KIND if info is not None and info.aot else SCALAR_KIND      # tier S1: `str` in AOT mode only
    for position, a in enumerate(args.args):
        if owner is not None and position == 0:
            # `self` (ir.Function.owner): unannotated, or annotated with the class itself
            if a.annotation is not None and _annotation_kind(a.annotation).strip("'\"") != owner:
                raise Skip(a.lineno, f"`{a.arg}` is annotated `{_src(a.annotation)}`, not the "
                                     f"class `{owner}`")
            params.append(ir.Param(a.arg, Type.OBJ, owner, False))
            continue
        if a.annotation is None:
            raise Skip(a.lineno, f"parameter `{a.arg}` has no annotation")
        kind = _annotation_kind(a.annotation)
        buffer = _buffer_kind(a.annotation, info) if info is not None else None
        if buffer is not None and buffer[1] == "numpy" and not _element_only(fn, a.arg):
            buffer = None       # #220: used as a whole object (np.sum(x), x.T, passed on): opaque
        if buffer is not None:
            params.append(ir.ArrayParam(a.arg, buffer[0], False, buffer[1]))  # `stored` set later
        elif kind in ARRAY_KIND:
            params.append(ir.ArrayParam(a.arg, ARRAY_KIND[kind], False))  # `stored` set later
        elif (c := _class_of(kind, classes or {})) is not None:
            params.append(ir.Param(a.arg, Type.OBJ, c[0], c[1]))
        else:
            params.append(ir.Param(a.arg, kinds_map.get(_aot_kind(kind) if kinds_map is AOT_KIND else kind, Type.OBJ)))
    if fn.returns is None:
        raise Skip(line, "no return annotation")
    if isinstance(fn.returns, ast.Constant) and fn.returns.value is None:
        returns = Type.NONE
    else:
        rk = _annotation_kind(fn.returns)
        returns = kinds_map.get(_aot_kind(rk) if kinds_map is AOT_KIND else rk, Type.OBJ)
        if returns == Type.OBJ and info is not None and info.aot:
            # AOT mode: a returned list is a native array (a counted list the caller then owns)
            returns = ARRAY_KIND.get(_annotation_kind(fn.returns), returns)
        if returns in ir.SCALARS and any(
                isinstance(p, ir.ArrayParam) and p.buffer == "numpy" for p in params):
            returns = Type.OBJ      # #220: the interpreter returns np.float64 / np.int64 / np.bool
    elements, ellipsis = _tuple_annotation(fn.returns)
    ret_cls, ret_opt = None, False
    if returns == Type.OBJ and classes:
        rc = _class_of(_annotation_kind(fn.returns), classes)
        if rc is not None:
            ret_cls, ret_opt = rc
    return _Signature(fn, tuple(params), returns, elements, ellipsis, _src(fn.returns), owner,
                      return_cls=ret_cls, return_optional=ret_opt)


def _tuple_annotation(annotation: ast.expr) -> tuple[tuple[Type, ...] | None, bool]:
    """`tuple[int, float]` -> ((I64, F64), False); `tuple[int, ...]` -> (None, True)."""
    if not (isinstance(annotation, ast.Subscript) and isinstance(annotation.value, ast.Name)
            and annotation.value.id == "tuple"):
        return None, False
    inner = annotation.slice
    items = list(inner.elts) if isinstance(inner, ast.Tuple) else [inner]
    if any(isinstance(i, ast.Constant) and i.value is Ellipsis for i in items):
        return None, True
    return tuple(SCALAR_KIND.get(_annotation_kind(i), Type.OBJ) for i in items), False


def _annotation_kind(annotation: ast.expr) -> str:
    if isinstance(annotation, ast.Name) and annotation.id in SCALAR_KIND:
        return annotation.id
    return _src(annotation).replace(" ", "")


# --- local types from Pyrefly --------------------------------------------------------------------

def _inferred_kinds(
    path: Path, source: str, tree: ast.Module, functions: list[tuple[str, ast.FunctionDef]],
) -> dict[str, dict[str, list[tuple[int, str]]]]:
    """IR function name -> local -> [(line, base type)] for every unannotated assignment (Pyrefly)."""
    scopes = rebinding._scopes(tree)
    keys = {id(fn): q for q, fn in functions}
    wanted_scopes = {scopes.index(fn) for _, fn in functions}
    wanted = [a for a in rebinding.assignments(tree) if a.scope in wanted_scopes]
    if not wanted:
        return {}
    probed, probes = rebinding.probe(source, wanted)
    with tempfile.TemporaryDirectory(prefix="typedpython-frontend-") as work:
        copies = gate._mirror(Path(work), {str(path): probed})
        [copy] = copies
        roots = pyrefly.import_roots([str(path)])
        report = pyrefly.run([Path(copy)], [Path(work) / "0", Path(copy).parent, *roots])
        types = report.expression_types.get(copy, {})

    found: dict[str, dict[str, list[tuple[int, str]]]] = {}
    for p in probes:
        a = p.assignment
        if a is None:
            continue
        spelled = types.get((p.line, p.column), "Unknown")
        function = scopes[a.scope]
        assert isinstance(function, ast.FunctionDef)
        found.setdefault(keys[id(function)], {}).setdefault(p.name, []).append(
            (a.line, rebinding._base(spelled)))
    return found


# --- one function --------------------------------------------------------------------------------

def _lower_function(
    sig: _Signature, env: dict[str, ir.Function], info: _ModuleInfo,
    kinds: dict[str, dict[str, list[tuple[int, str]]]],
) -> ir.Function | str:
    inferred = kinds.get(sig.qname, {})
    if any(isinstance(p, ir.ArrayParam) and p.buffer == "numpy" for p in sig.params):
        inferred = _numpy_kinds(sig, inferred)
    if info.aot:
        # One lowering, in the pure mode, whatever the result's purity: there is no interpreter to
        # retry in, so no `INT` and no object-int fallback. A function that holds an object operation
        # (a call that is not direct, say) comes back impure and the back end names the operation.
        try:
            return _lower_in_mode(sig, env, info, inferred, impure=False)
        except Skip as e:
            return e.reason
    pure_error: Skip | None = None
    try:
        fn = _lower_in_mode(sig, env, info, inferred, impure=False)
        if fn.pure:
            return fn
    except Skip as e:
        pure_error = e
    try:
        return _lower_in_mode(sig, env, info, inferred, impure=True)
    except Skip as e:
        # The pure attempt's reason is the natural one, unless it stopped after an impure construct:
        # then the function was never going to be pure and the pure mode's types (a native int where
        # the impure mode has an object) make its complaint beside the point.
        if pure_error is not None and not getattr(pure_error, "impure_seen", False):
            return pure_error.reason
        return e.reason


_NUMPY_SPELLED = {"float64": "float", "numpy.float64": "float", "int64": "int", "numpy.int64": "int",
                  "bool_": "bool", "numpy.bool_": "bool", "numpy.bool": "bool"}


def _numpy_kinds(sig: _Signature, inferred: dict[str, list[tuple[int, str]]],
                 ) -> dict[str, list[tuple[int, str]]]:
    """Pyrefly spells a value computed from a numpy element `Unknown` when it cannot import numpy
    (or `float64` / `int64` when it can): in a function with numpy arrays (#220) those entries are
    re-read from the source, as the scalar kind (float / int / bool) the interpreter's numpy scalar
    stands for. An entry stays `Unknown` when the source does not settle it."""
    arrays = {p.name: p.type for p in sig.params if isinstance(p, ir.ArrayParam) and p.buffer == "numpy"}
    scalars = {p.name: _annotation_kind(p_node.annotation)
               for p, p_node in zip(sig.params, sig.node.args.args)
               if isinstance(p, ir.Param) and p.type in ir.SCALARS}
    nodes = {n.lineno: n for n in ast.walk(sig.node)
             if isinstance(n, (ast.Assign, ast.AugAssign)) and len(getattr(n, "targets", [0])) == 1}

    # newer numpy stubs type an element `typing.Any`: the same "source settles it" case as `Unknown`
    out = {name: [(line, _NUMPY_SPELLED.get(k, "Unknown" if k == "typing.Any" else k)) for line, k in entries]
           for name, entries in inferred.items()}

    def known(name: str) -> str | None:
        ks = {k for _, k in out.get(name, []) if k != "Unknown"}
        if name in scalars:
            ks.add(scalars[name])
        return next(iter(ks)) if len(ks) == 1 else None

    def kind(e: ast.expr) -> str | None:
        if isinstance(e, ast.Constant):
            return {bool: "bool", int: "int", float: "float"}.get(type(e.value))
        if isinstance(e, ast.Name):
            return known(e.id)
        if isinstance(e, ast.Subscript) and isinstance(e.value, ast.Name) and e.value.id in arrays:
            return {Type.F64_ARRAY: "float", Type.BOOL_ARRAY: "bool"}.get(arrays[e.value.id], "int")
        if isinstance(e, ast.UnaryOp):
            return "bool" if isinstance(e.op, ast.Not) else kind(e.operand)
        if isinstance(e, ast.BinOp):
            a, b = kind(e.left), kind(e.right)
            if a is None or b is None or "bool" in (a, b):
                return None
            return "float" if "float" in (a, b) or isinstance(e.op, ast.Div) else "int"
        if isinstance(e, ast.Compare):
            return "bool"
        return None

    for _ in range(3):
        for name, entries in out.items():
            fixed = []
            for line, k in entries:
                node = nodes.get(line)
                if k == "Unknown" and node is not None:
                    value = node.value
                    if isinstance(node, ast.AugAssign):
                        value = ast.BinOp(left=ast.Name(id=name), op=node.op, right=node.value)
                    k = kind(value) or k
                fixed.append((line, k))
            out[name] = fixed
    return out


def _lower_in_mode(sig, env, info, inferred, impure: bool) -> ir.Function:
    if info.aot:
        # no entry globals: a module constant is an `ir.Const` (`_Lowerer.name`), never an object read
        return _lower_with_bounds(sig, env, info, inferred, impure, {})
    fn = _lower_with_bounds(sig, env, info, inferred, impure, info.scalar_globals)
    if fn.entry_globals and not _closed(fn, env):
        # A global read once at entry is only the same as a read at each use if nothing between
        # can run user code: here something can, so the reads stay `Global`.
        fn = _lower_with_bounds(sig, env, info, inferred, impure, {})
    return fn


class _ObjRange(Exception):
    """A `for v in range(...)` whose bound is a CPython int object: `v` cannot be an I64 range
    index. Raised once per variable; the function is lowered again with `v` an object (a `ForObj`)."""

    def __init__(self, var: str) -> None:
        super().__init__(var)
        self.var = var


class _ObjInt(Exception):
    """A local of an impure function that would be an `INT` (SPEC N-13) is assigned an object (a call
    of an unknown callable, a global, `int(x)`): it stays an object. Raised once per variable; the
    function is lowered again with the variable an object."""

    def __init__(self, var: str) -> None:
        super().__init__(var)
        self.var = var


def _lower_with_bounds(sig, env, info, inferred, impure: bool,
                       candidates: dict[str, Type]) -> ir.Function:
    obj_ranges: frozenset[str] = frozenset()
    obj_ints: frozenset[str] = frozenset()
    while True:
        try:
            return _lower_rounds(sig, env, info, inferred, impure, candidates, obj_ranges, obj_ints)
        except _ObjRange as r:
            assert r.var not in obj_ranges, "an object range variable was asked for twice"
            obj_ranges |= {r.var}
        except _ObjInt as r:
            assert r.var not in obj_ints, "an object int variable was asked for twice"
            obj_ints |= {r.var}


def _lower_rounds(sig, env, info, inferred, impure: bool, candidates: dict[str, Type],
                  obj_ranges: frozenset[str], obj_ints: frozenset[str]) -> ir.Function:
    # Range-target intervals: least fixpoint by iteration, widened to TOP if it does not settle.
    # A local that keeps growing (a counter) is widened to the i64 limit from round 3 on, as the
    # verifier's own fixpoint does (verify._prove).
    bounds: dict[str, Interval] = {}
    for rnd in range(16):
        lowerer = _Lowerer(sig, env, info, inferred, impure, bounds, candidates, obj_ranges, obj_ints)
        try:
            fn = lowerer.run()
        except Skip as e:
            e.impure_seen = lowerer.impure      # type: ignore[attr-defined]
            raise
        joined = {**bounds}
        for var, iv in lowerer.found_bounds.items():
            new = _join(joined.get(var), iv)
            old = joined.get(var)
            if rnd >= 3 and var in lowerer.acc_vars and old is not None and new != old:
                new = (old[0] if new[0] >= old[0] else I64_MIN,
                       old[1] if new[1] <= old[1] else I64_MAX)
            joined[var] = new
        if joined == bounds:
            return fn
        bounds = joined
    widened = {var: TOP for var in bounds}
    return _Lowerer(sig, env, info, inferred, impure, widened, candidates, obj_ranges, obj_ints).run()


def _join(a: Interval, b: Interval) -> Interval:
    if a is None:
        return b
    if b is None:
        return a
    return (min(a[0], b[0]), max(a[1], b[1]))


@dataclass
class _Binding:
    line: int
    how: str  # "range" (a for-over-range target), "annotation" (no value), "assign", "for"


class _Lowerer:
    def __init__(self, sig: _Signature, env: dict[str, ir.Function], info: _ModuleInfo,
                 inferred: dict[str, list[tuple[int, str]]], impure: bool,
                 bounds: dict[str, Interval], candidates: dict[str, Type],
                 obj_ranges: frozenset[str] = frozenset(),
                 obj_ints: frozenset[str] = frozenset()) -> None:
        self.sig = sig
        self.obj_ranges = obj_ranges
        self.obj_ints = obj_ints
        self.candidates = candidates
        self.entry_used: dict[str, Type] = {}
        self.fn = sig.node
        self.env = env
        self.info = info
        self.inferred = inferred
        self.impure_mode = impure
        self.bounds = bounds
        self.found_bounds: dict[str, Interval] = {}

        self.params = {p.name: p for p in sig.params}
        self.arrays = {p.name: p.type for p in sig.params if isinstance(p, ir.ArrayParam)}
        self.numpy = {p.name for p in sig.params
                      if isinstance(p, ir.ArrayParam) and p.buffer == "numpy"}
        # #255 (d2), agent proposal, not confirmed: in impure mode a function with three or more list
        # parameters reads and stores them element by element (ir.direct_writer) and has no entry
        # global: a scalar module global is read at each use (`name`)
        self.direct_write = impure and sum(isinstance(p, ir.ArrayParam) and p.buffer is None
                                           for p in sig.params) > 2
        self.bindings: dict[str, list[_Binding]] = {}
        self.annotations: dict[str, list[tuple[int, str]]] = {}
        self._collect_bindings()
        # Local arrays (ir.NewArray / CopyArray): name -> array type; also in `self.arrays`.
        self.local_arrays = self._find_local_arrays_aot() if info.aot else self._find_local_arrays()
        self.arrays.update(self.local_arrays)
        self.range_vars = {
            n for n, bs in self.bindings.items()
            if n not in self.params and any(b.how == "range" for b in bs)
            and all(b.how in ("range", "annotation") for b in bs) and n not in self.obj_ranges
        }
        # Locals bound by a loop over an object (`for t in obj`, an object range): always objects.
        self.iter_vars = {n for n, bs in self.bindings.items() if any(b.how == "for" for b in bs)}
        self.iter_vars |= self.obj_ranges
        # Locals only ever assigned by plain `=` / `+=` statements (never a loop target): their
        # interval is the join of what is assigned to them (found_bounds, widened across rounds).
        self.acc_vars = {
            n for n, bs in self.bindings.items()
            if n not in self.params and n not in self.local_arrays
            and any(b.how == "assign" for b in bs)
            and all(b.how in ("assign", "annotation") for b in bs)
        }
        # Guard facts at this point (verify.py "Local variables and guards"): local -> interval.
        self.refined: dict[str, tuple[int, int]] = {}
        self.not_none: set[str] = set()     # AOT mode, tier S2a: the STR_OPT locals proved not None here
        self.dead = 0   # >0: in code whose guard read a local with no interval yet in this round
        self.all_names = {n.id for n in ast.walk(self.fn) if isinstance(n, ast.Name)} \
            | set(self.params)

        self.types: dict[str, Type] = {}
        self.temps: dict[str, Type] = {}
        self.pre: list[ir.Stmt] = []
        self.hoist_ok = True
        self.impure = False      # an effect outside the locals
        self.deopts = False      # a node that can deopt
        self.stored: set[str] = set()
        self.current: ast.stmt | None = None   # the statement being lowered (for messages)
        self._numbers: dict[int, int] | None = None   # id(source statement) -> its number (`block`)
        self._new_locals: dict[str, str] | None = None

    # --- entry ---

    def run(self) -> ir.Function:
        body = self.block(self.fn.body)
        if self.sig.returns != Type.NONE and _falls_through(body):
            last = self.fn.body[-1]
            raise Skip(getattr(last, "end_lineno", None) or last.lineno,
                       f"can reach the end without returning a {TYPE_NAME[self.sig.returns]} "
                       "(CPython would return None)")
        locals_ = {}
        for name in self.bindings:
            if name not in self.params:
                locals_[name] = self.local_type(name, self.bindings[name][0].line)
        locals_.update(self.temps)
        if self.numpy:
            try:
                body, flags, deopts = taint.apply(
                    body, self.numpy, not self.impure,
                    frozenset(p.name for p in self.sig.params if isinstance(p, ir.Param)),
                    frozenset(locals_) | frozenset(p.name for p in self.sig.params))
            except taint.Refuse as why:
                raise Skip(self.fn.lineno, str(why)) from None
            locals_.update(flags)
            self.deopts = self.deopts or deopts
        params = tuple(
            ir.ArrayParam(p.name, p.type, p.name in self.stored, p.buffer)
            if isinstance(p, ir.ArrayParam)
            else p for p in self.sig.params
        )
        pure = not self.impure
        assert pure or not self.deopts or not self.impure_mode, \
            "impure mode emitted a deopting node"
        n_lists = sum(isinstance(p, ir.ArrayParam) and p.buffer is None for p in params)
        if n_lists > 2 and not self.info.aot:       # (AOT mode: a list is the caller's own array, never copied)
            # Free-threaded CPython (#159): copying lists in needs them locked from copy-in to
            # write-back, and CPython offers one- and two-object critical sections only (a nested
            # third could suspend the others). So three or more are read element by element (#255,
            # ir.direct_lists). A pure function deopts on an element of another type (d1). One with
            # an effect (ir.direct_writer, d2) stores element by element too and leaves through
            # statement exits; lowered in impure mode it reads globals at their use (`name`), so it
            # has no entry global. (A pure-mode lowering with an effect is discarded by
            # `_lower_function`, which lowers the function again in impure mode.)
            if pure and self.entry_used:
                raise Skip(self.fn.lineno, f"more than two list parameters ({n_lists}) and a module global "
                                           "read: the lists are read element by element, so the global "
                                           "cannot be read once at entry (#255)")
            if pure:
                self.deopts = True
        local_classes: dict[str, tuple[str, bool]] = {}
        if self.info.aot:
            for name, ltype in locals_.items():
                if ltype == Type.OBJ:
                    cls, _ = self.cls_of_name(name)
                    if cls is not None:
                        kinds = list(self.annotations.get(name, []))
                        if name not in self.annotations:
                            kinds += self.inferred.get(name, [])
                        opt = False
                        for _, k in kinds:
                            c = _class_of(k, self.info.classes)
                            if c is not None and c[1]:
                                opt = True
                        local_classes[name] = (cls, opt)
        fn = ir.Function(
            name=self.sig.qname, params=params, returns=self.sig.returns, locals=locals_, body=body,
            pure=pure, may_deopt=self.deopts, source_line=self.fn.lineno,
            entry_globals=tuple(ir.Param(n, t) for n, t in self.entry_used.items()),
            owner=self.sig.owner, return_cls=self.sig.return_cls,
            return_optional=self.sig.return_optional, local_classes=local_classes)
        if self.info.aot:
            return fn       # nothing is redone in an interpreter, so there is no continuation to write
        # SPEC N-8 (#152): a snapshot holder gets a Python continuation per loop (OSR-style exit).
        return dataclasses.replace(fn, resumes=resume.resumes_for(self.fn, fn))

    # --- names and types ---

    def _collect_bindings(self) -> None:
        def add(name: str, line: int, how: str) -> None:
            self.bindings.setdefault(name, []).append(_Binding(line, how))

        for stmt in rebinding._own_statements(self.fn):
            if stmt is self.fn:
                continue
            if isinstance(stmt, ast.AnnAssign) and isinstance(stmt.target, ast.Name):
                add(stmt.target.id, stmt.lineno, "assign" if stmt.value is not None else "annotation")
                self.annotations.setdefault(stmt.target.id, []).append(
                    (stmt.lineno, _annotation_kind(stmt.annotation)))
            elif isinstance(stmt, (ast.For, ast.AsyncFor)):
                how = "range" if _is_range_call(stmt.iter) else "for"
                for n in ast.walk(stmt.target):
                    if isinstance(n, ast.Name) and isinstance(n.ctx, ast.Store):
                        add(n.id, stmt.lineno, how)
            else:
                for target in _binding_targets(stmt):
                    for n in ast.walk(target):
                        if isinstance(n, ast.Name) and isinstance(n.ctx, ast.Store):
                            add(n.id, stmt.lineno, "assign")

    def _find_local_arrays(self) -> dict[str, Type]:
        """Locals created by `list(range(n))`, `[v] * n` or `src[:]` (src an array). Every binding
        of such a name must be one of those creations: a local array is never rebound to anything
        else, so it has one element type for its whole life."""
        creations: dict[str, list[tuple[ast.stmt, str, ast.expr]]] = {}
        for stmt in rebinding._own_statements(self.fn):
            if isinstance(stmt, ast.Assign) and len(stmt.targets) == 1 \
                    and isinstance(stmt.targets[0], ast.Name):
                name, value = stmt.targets[0].id, stmt.value
            elif isinstance(stmt, ast.AnnAssign) and isinstance(stmt.target, ast.Name) \
                    and stmt.value is not None:
                name, value = stmt.target.id, stmt.value
            else:
                continue
            kind = _creation_kind(value)
            if kind is not None and name not in self.params:
                creations.setdefault(name, []).append((stmt, kind, value))
        buffers = {p.name for p in self.sig.params if isinstance(p, ir.ArrayParam) and p.buffer}
        for cs in creations.values():
            for stmt, kind, value in cs:
                if kind == "copy" and value.value.id in buffers:  # type: ignore[attr-defined]
                    raise Skip(stmt.lineno, f"`{_src(value)}` slices buffer parameter "
                                            f"`{value.value.id}`: a buffer array is never copied "  # type: ignore[attr-defined]
                                            "(`[:]` would be a native copy of a list)")
        known = set(self.arrays) | {n for n, cs in creations.items()
                                    if any(k != "copy" for _, k, _ in cs)}
        while True:
            more = {n for n, cs in creations.items() if n not in known and any(
                k == "copy" and v.value.id in known for _, k, v in cs)}  # type: ignore[attr-defined]
            if not more:
                break
            known |= more
        found: dict[str, Type] = {}
        for name, cs in creations.items():
            if name not in known:
                continue
            cs = [c for c in cs if c[1] != "copy" or c[2].value.id in known]  # type: ignore[attr-defined]
            lines = {c[0].lineno for c in cs}
            other = [b for b in self.bindings[name] if b.how != "annotation" and b.line not in lines]
            if other:
                raise Skip(other[0].line, f"local `{name}` is a native array (created at line "
                                          f"{cs[0][0].lineno}) but is also bound by something "
                                          "that does not create an array")
            found[name] = self._array_type(name, cs, found)
        return found

    def _find_local_arrays_aot(self) -> dict[str, Type]:
        """AOT mode: a local is a native list (an F64_ARRAY / I64_ARRAY) when it is annotated, or inferred
        by Pyrefly from every one of its bindings, as `list[float]` / `list[int]`. What each binding makes
        of it is decided where the assignment is lowered (`aot_list_assign`): a list display, `[v] * n`,
        `list(range(n))`, `a[:]`, another list name or a call of a program function that returns a list."""
        found: dict[str, Type] = {}
        for name in self.bindings:
            if name in self.params:
                continue
            kinds: list[tuple[int, str]] = list(self.annotations.get(name, []))
            if name not in self.annotations:
                kinds += self.inferred.get(name, [])
            if not any(k.startswith("list") for _, k in kinds):
                continue
            if all((_aot_kind(k) if self.info.aot else k) == "list[str]" for _, k in kinds):
                continue        # tier S1b: a list of str is a STR_LIST value, not a native array
            distinct = sorted({k for _, k in kinds})
            if len(distinct) > 1:
                where = next(line for line, k in kinds if k != kinds[0][1])
                raise Skip(where, f"local `{name}` is given values of types "
                                  + " and ".join(f"`{k}`" for k in distinct))
            if distinct[0] not in ARRAY_KIND:
                raise Skip(kinds[0][0], f"local `{name}: {distinct[0]}`: a native list holds only floats or only "
                                        "ints (`list[float]` or `list[int]`)")
            found[name] = ARRAY_KIND[distinct[0]]
        return found

    def _array_type(self, name: str, cs, found: dict[str, Type]) -> Type:
        line = cs[0][0].lineno
        kinds: list[tuple[int, str]] = list(self.annotations.get(name, []))
        if name not in self.annotations:
            kinds += self.inferred.get(name, [])
        distinct = sorted({k for _, k in kinds})
        if len(distinct) > 1:
            raise Skip(line, f"local `{name}` is given values of types "
                             + " and ".join(f"`{k}`" for k in distinct))
        if distinct:
            if distinct[0] not in ARRAY_KIND:
                raise Skip(line, f"local `{name}: {distinct[0]}`: a native array's element type "
                                 "must be exactly int or float")
            return ARRAY_KIND[distinct[0]]
        for _, kind, value in cs:  # unknown to Pyrefly: a copy takes its source's type
            if kind == "copy":
                src = value.value.id
                if src in self.arrays:
                    return self.arrays[src]
                if src in found:
                    return found[src]
        raise Skip(line, f"no type is known for local `{name}`")

    def is_local(self, name: str) -> bool:
        return name in self.params or name in self.bindings or name in self.temps

    def local_type(self, name: str, line: int) -> Type:
        if name in self.temps:
            return self.temps[name]
        if name in self.params:
            return self.params[name].type
        if name in self.local_arrays:
            return self.local_arrays[name]
        if name in self.types:
            return self.types[name]
        kinds: list[tuple[int, str]] = list(self.annotations.get(name, []))
        kinds += [(b.line, "int") for b in self.bindings.get(name, []) if b.how == "range"]
        if name not in self.annotations:
            kinds += self.inferred.get(name, [])
        if self.info.aot:
            # Pyrefly spells a result it only partly resolves `int | Unknown` (`sys.stdout.write`, typeshed's
            # TextIO). The known part is the type: the lowered value is typed exactly, and `coerce` refuses a
            # value of another type, so nothing unchecked comes in.
            kinds = [(line, k[:-len(" | Unknown")] if k.endswith(" | Unknown") else k) for line, k in kinds]
            # (a str built from literals is spelled `LiteralString`: a str)
            kinds = [(line, _aot_kind(k)) for line, k in kinds]
        distinct = sorted({k for _, k in kinds})
        if not distinct and name in self.iter_vars:
            self.types[name] = Type.OBJ   # what a loop over an object binds: an object
            return Type.OBJ
        if not distinct:
            raise Skip(self.bindings[name][0].line, f"no type is known for local `{name}`")
        if len(distinct) > 1:
            where = next(line for line, k in kinds if k != kinds[0][1])
            raise Skip(where, f"local `{name}` is given values of types "
                              + " and ".join(f"`{k}`" for k in distinct))
        kind = distinct[0]
        if kind in ARRAY_KIND or kind.startswith(("list[float]", "list[int]")):
            raise Skip(kinds[0][0], f"local `{name}: {kind}`: a list local is a native array only "
                                    "when created by `list(range(n))`, `[v] * n` or `src[:]`")
        if kind == "int":
            if not self.impure_mode or name in self.range_vars:
                t = Type.I64
            elif name in self.iter_vars or name in self.obj_ints:
                t = Type.OBJ        # a loop over an object binds objects; an int fed from an object stays one
            else:
                t = Type.INT        # SPEC N-13: native, promoted in place on overflow
        elif self.info.aot:
            t = AOT_KIND.get(kind, Type.OBJ)        # tier S1: `str` is STR
        else:
            t = SCALAR_KIND.get(kind, Type.OBJ)
        if name in self.iter_vars and t != Type.OBJ and not (self.info.aot and t is Type.STR):
            # (AOT mode: `for ch in s` over a str binds a STR, `ForStr`)
            raise Skip(self.bindings[name][0].line, f"loop variable `{name}` is bound by a loop over "
                                                    f"an object but is a native {TYPE_NAME[t]}")
        self.types[name] = t
        return t

    def temp(self, value: ir.Expr) -> ir.Local:
        n = len(self.temps)
        while f"_tp{n}" in self.all_names or f"_tp{n}" in self.temps:
            n += 1
        name = f"_tp{n}"
        self.temps[name] = value.type
        self.pre.append(ir.Assign(name, value))
        return ir.Local(value.type, name)

    # --- statements ---

    def block(self, stmts: Iterable[ast.stmt]) -> tuple[ir.Stmt, ...]:
        """The IR of `stmts`. Every IR statement carries the number of the source statement it was
        lowered from (`ir.Stmt.stmt`, `resume.statements` order), its temporaries included (#390
        item 5: the statement exit of a function holding lists re-executes that statement)."""
        if self._numbers is None:
            self._numbers = resume.stmt_numbers(self.fn)
        out: list[ir.Stmt] = []
        for s in stmts:
            k = self._numbers.get(id(s))
            out += [_at(x, k) for x in self.stmt(s)]
        return tuple(out)

    def start(self) -> None:
        self.pre = []
        self.hoist_ok = True

    def take(self) -> list[ir.Stmt]:
        pre, self.pre = self.pre, []
        return pre

    def stmt(self, node: ast.stmt) -> list[ir.Stmt]:
        self.start()
        self.current = node
        line = node.lineno
        if isinstance(node, ast.Pass):
            return []
        if isinstance(node, ast.Break):
            return [ir.Break()]
        if isinstance(node, ast.Continue):
            return [ir.Continue()]
        if isinstance(node, ast.Expr):
            if isinstance(node.value, ast.Constant):
                return []  # a docstring or a bare literal: no effect
            if self.info.aot and _is_print_call(node.value) and self.builtin("print"):
                return self.print_stmt(node.value)
            if self.info.aot and isinstance(node.value, ast.Call) and isinstance(node.value.func, ast.Attribute) \
                    and isinstance(node.value.func.value, ast.Name) and not node.value.keywords \
                    and not any(isinstance(a, ast.Starred) for a in node.value.args):
                mod = node.value.func.value.id
                attr = node.value.func.attr
                if mod == "sys" and attr == "exit" and self.info.aot_sys and not self.is_local("sys"):
                    return self.aot_sys_exit(node.value)
                if mod == "time" and attr == "sleep" and self.info.aot_time and not self.is_local("time"):
                    return self.aot_time_sleep(node.value)
                if self.is_list_name(mod) and attr in LIST_STMT_METHODS:
                    return self.list_method_stmt(node.value)             # tier S2a
            value = self.sub(node.value)
            return self.take() + [ir.ExprStmt(value)]
        if isinstance(node, ast.Assign):
            if len(node.targets) != 1:
                raise Skip(line, "chained assignment `a = b = ...` is not supported")
            return self.assign(node.targets[0], node.value, node)
        if isinstance(node, ast.AnnAssign):
            if not isinstance(node.target, ast.Name):
                raise Skip(line, f"annotated assignment to `{_src(node.target)}` is not supported")
            if node.value is None:
                return []
            return self.assign(node.target, node.value, node)
        if isinstance(node, ast.AugAssign):
            return self.aug_assign(node)
        if isinstance(node, ast.If):
            cond = self.condition(node.test)
            pre = self.take()
            entry = self.refined
            nn_entry = set(self.not_none)
            self.refined, dead = self.narrowed(cond, True, entry)
            self.dead += dead
            self.not_none = nn_entry | _none_facts(cond, True)
            then = self.block(node.body)
            self.dead -= dead
            then_refined, then_nn = self.refined, self.not_none
            self.refined, dead = self.narrowed(cond, False, entry)
            self.dead += dead
            self.not_none = nn_entry | _none_facts(cond, False)
            orelse = self.block(node.orelse)
            self.dead -= dead
            else_refined, else_nn = self.refined, self.not_none
            then_ends, else_ends = _diverges(then), _diverges(orelse)
            self.not_none = (nn_entry if then_ends and else_ends else else_nn if then_ends
                             else then_nn if else_ends else then_nn & else_nn)
            if then_ends and else_ends:
                self.refined = entry
            elif then_ends:
                self.refined = else_refined
            elif else_ends:
                self.refined = then_refined
            else:
                self.refined = {k: (min(v[0], else_refined[k][0]), max(v[1], else_refined[k][1]))
                                for k, v in then_refined.items() if k in else_refined}
            return pre + [ir.If(cond, then, orelse)]
        if isinstance(node, ast.While):
            if node.orelse:
                raise Skip(line, "`while ... else` is not supported")
            killed = _stored_names(node.body)
            head = {k: v for k, v in self.refined.items() if k not in killed}
            self.refined = head
            nn_head = self.not_none - killed
            self.not_none = set(nn_head)
            cond = self.condition(node.test)
            pre = self.take()
            self.refined, dead = self.narrowed(cond, True, head)
            self.dead += dead
            self.not_none = nn_head | _none_facts(cond, True)
            body = self.block(node.body)
            self.dead -= dead
            self.refined = dict(head)
            self.not_none = set(nn_head)
            if not pre:
                return [ir.While(cond, body)]
            # The condition needs statements (a chain's temporary): run them on every iteration.
            exit_ = ir.If(cond, (), (ir.Break(),))  # cond stays directly a condition
            return [ir.While(ir.Const(Type.BOOL, True), (*pre, exit_, *body))]
        if isinstance(node, ast.For):
            # tier S2a: a name the loop assigns is not known to be not None at the head of any iteration, nor after
            killed = _stored_names(node.body) | {n.id for n in ast.walk(node.target) if isinstance(n, ast.Name)}
            self.not_none -= killed
            out = self.for_range(node)
            self.not_none -= killed
            return out
        if isinstance(node, ast.Return):
            return self.return_(node)
        raise _unsupported(node)

    def print_stmt(self, call: ast.Call) -> list[ir.Stmt]:
        """AOT mode: `print(a, b, ..., sep=..., end=...)` as `ir.Print`. The arguments are lowered in order, then
        `sep` and `end` in source order; each argument is an int, float, bool or str value (tier S1), `sep` and `end`
        a str or None. Not an effect: nothing here changes how the function's ints are typed."""
        args: list[ir.Expr] = []
        for a in call.args:
            if isinstance(a, ast.Starred):
                raise Skip(a.lineno, "a starred argument of print is not supported in an AOT program")
            if isinstance(a, ast.JoinedStr):
                args.append(self.aot_fstring(a))
                continue
            listed = self.list_str(a)                  # tier S2a: a list is printed as its repr
            if listed is not None:
                args.append(listed)
                continue
            e = self.value(a)
            if e.type not in ir.SCALARS and e.type not in (Type.OBJ, Type.STR, Type.STR_LIST, Type.STR_OPT):
                raise Skip(a.lineno, f"print of `{_src(a)}` ({TYPE_NAME[e.type]}) is not supported in an "
                                     "AOT program (int, float, bool and str values)")
            # (an OBJ argument, say a call that is not direct, stays: it has no AOT form and the back
            # end names the operation that made it)
            args.append(e)
        extra: dict[str, ir.Expr | None] = {}
        for k in call.keywords:
            if k.arg not in ("sep", "end"):
                raise Skip(k.value.lineno, f"print(..., {k.arg or '**'}=...) is not supported in an AOT "
                                           "program (positional arguments, `sep` and `end`)")
            if k.arg in extra:
                raise Skip(k.value.lineno, f"print() is given `{k.arg}` twice")
            if isinstance(k.value, ast.Constant) and k.value.value is None:
                extra[k.arg] = None                 # CPython: None means the default
                continue
            v = self.value(k.value)
            if v.type is not Type.STR:
                raise Skip(k.value.lineno, f"print's `{k.arg}` is {TYPE_NAME.get(v.type, v.type.value)}; it must "
                                           "be a str or None")
            extra[k.arg] = v
        return self.take() + [ir.Print(tuple(args), sep=extra.get("sep"), end=extra.get("end"))]

    def assign(self, target: ast.expr, value_node: ast.expr, node: ast.stmt) -> list[ir.Stmt]:
        line = node.lineno
        if isinstance(target, ast.Name):
            name = target.id
            if name in self.local_arrays:
                if self.info.aot:
                    return self.aot_list_assign(name, value_node, node)
                return self.new_array(name, value_node, node)
            if name in self.arrays:
                raise Skip(line, f"array parameter `{name}` is rebound")
            value = self.value(value_node)
            t = self.local_type(name, line)
            if t == Type.INT and value.type == Type.OBJ:
                raise _ObjInt(name)         # fed from an object: the local stays one (SPEC N-13)
            value = self.coerce(value, t, value_node, f"local `{name}`")
            self.note_assign(name, value)
            return self.take() + [ir.Assign(name, value)]
        if isinstance(target, ast.Subscript) and not (
                isinstance(target.value, ast.Name) and target.value.id in self.arrays):
            if isinstance(target.slice, ast.Slice):
                raise Skip(line, f"slice assignment `{_src(target)} = ...` is not supported")
            # STORE_SUBSCR: the value, then the container, then the index.
            value = self.boxed(self.value(value_node), value_node)
            obj = self.boxed(self.sub(target.value), target.value)
            key = self.boxed(self.sub(target.slice), target.slice)
            self.impure = True
            return self.take() + [ir.StoreItem(obj, key, value)]
        if isinstance(target, ast.Subscript):
            array, elem = self.array_of(target)
            # CPython evaluates the value first, then the index.
            value = self.value(value_node)
            value = self.coerce(value, elem, value_node, f"`{array}: {TYPE_NAME[self.arrays[array]]}`")
            if not _simple(target.slice) and not _trivial(value):
                value = self.temp(value)
            index = self.index(target.slice)
            self.store_effect(array)
            return self.take() + [ir.StoreIndex(array, index, value)]
        if isinstance(target, ast.Attribute):
            found = self.field_set(target, value_node)
            if found is not None:
                return found
            raise Skip(line, f"assignment to attribute `{_src(target)}` is not supported")
        if isinstance(target, (ast.Tuple, ast.List)):
            if self.info.aot:
                return self.aot_unpack(target, value_node, node)
            raise Skip(line, "tuple unpacking is not supported")
        raise _unsupported(target)

    # --- AOT mode: native lists as values, tuples, formatted print arguments ---

    def aot_list_assign(self, name: str, value_node: ast.expr, node: ast.stmt) -> list[ir.Stmt]:
        """`name = <list>` in AOT mode: the list is a creation (`[a, b]`, `[v] * n`, `n * [v]`,
        `list(range(n))`, `src[:]`), another list's name (both names then mean the one list) or a call of
        a program function that returns a list."""
        t = self.local_arrays[name]
        e = self.array_expr(value_node, t, f"local `{name}: {TYPE_NAME[t]}`")
        return self.take() + [ir.Assign(name, e)]

    def array_expr(self, node: ast.expr, want: Type, what: str) -> ir.Expr:
        """The IR of the list `node` of type `want` (AOT mode): `Local` of a list name, `NewArray`,
        `CopyArray`, `ListDisplay` or a `Call` of a program function; else Skip."""
        line = node.lineno
        elem = Type.F64 if want == Type.F64_ARRAY else Type.I64
        if isinstance(node, ast.Name):
            if node.id not in self.arrays:
                raise Skip(line, f"`{_src(node)}` is not a list, but {what} is {TYPE_NAME[want]}")
            if self.arrays[node.id] != want:
                raise Skip(line, f"`{_src(node)}` is {TYPE_NAME[self.arrays[node.id]]}, but {what} is "
                                 f"{TYPE_NAME[want]}")
            return ir.Local(want, node.id)
        kind = _creation_kind(node)
        if kind == "iota":
            if not (self.builtin("list") and self.builtin("range")):
                raise Skip(line, f"`{_src(node)}` with `list` or `range` rebound")
            if elem != Type.I64:
                raise Skip(line, f"`{_src(node)}` is a list of int, but {what} is {TYPE_NAME[want]}")
            length = self.range_bound(node.args[0].args[0])        # type: ignore[attr-defined]
            return ir.NewArray(want, length, None, True)
        if kind == "copy":
            src = node.value.id                                   # type: ignore[attr-defined]
            if src not in self.arrays:
                raise Skip(line, f"`{_src(node)}` slices `{src}`, which is not a list")
            if self.arrays[src] != want:
                raise Skip(line, f"`{_src(node)}` is {TYPE_NAME[self.arrays[src]]}, but {what} is "
                                 f"{TYPE_NAME[want]}")
            return ir.CopyArray(want, src)
        if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Mult) and (
                (isinstance(node.left, ast.List) and len(node.left.elts) == 1)
                or (isinstance(node.right, ast.List) and len(node.right.elts) == 1)):
            vn_first = isinstance(node.left, ast.List)
            elt = (node.left if vn_first else node.right).elts[0]  # type: ignore[attr-defined]
            count = node.right if vn_first else node.left
            if isinstance(elt, ast.Starred) or isinstance(count, ast.List):
                raise Skip(line, f"`{_src(node)}` is not `[v] * n`")
            if vn_first:
                fill = self.coerce(self.value(elt), elem, elt, what)
                if not _trivial(fill):
                    fill = self.temp(fill)               # `[v] * n` evaluates v before n
                length = self.value(count)
            else:
                length = self.value(count)
                if not _trivial(length):
                    length = self.temp(length)           # `n * [v]` evaluates n before v
                fill = self.coerce(self.value(elt), elem, elt, what)
            if length.type != Type.I64:
                raise Skip(line, f"array length `{_src(count)}` is {TYPE_NAME[length.type]}, not a native int")
            return ir.NewArray(want, length, fill)
        if isinstance(node, ast.List):
            if not node.elts:
                raise Skip(line, "an empty list display `[]` has no element type here (a native list holds only "
                                 "floats or only ints)")
            if any(isinstance(x, ast.Starred) for x in node.elts):
                raise Skip(line, "a starred element in a list display is not supported")
            elements = []
            for x in node.elts:
                elements.append(self.coerce(self.value(x), elem, x, what))
            return ir.ListDisplay(want, tuple(elements))
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and not self.is_local(node.func.id) \
                and node.func.id in self.env:
            e = self.call(node)
            if isinstance(e, ir.Call) and e.type == want:
                return e
            if isinstance(e, ir.Call) and e.type in ir.ARRAYS:
                raise Skip(line, f"`{_src(node)}` returns {TYPE_NAME[e.type]}, but {what} is {TYPE_NAME[want]}")
        if isinstance(node, ast.Attribute):
            fg = self.field_get(node)
            if fg is not None:
                if fg.type == want:
                    return fg
                if fg.type in ir.ARRAYS:
                    raise Skip(line, f"`{_src(node)}` has type {TYPE_NAME[fg.type]}, but {what} is {TYPE_NAME[want]}")
        raise Skip(line, f"{what} is bound to `{_src(node)}`, which is not a list the AOT mode can make "
                         "(`[a, b]`, `[v] * n`, `n * [v]`, `list(range(n))`, `src[:]`, another list's name, or "
                         "a call of a program function that returns a list)")

    def aot_unpack(self, target: ast.Tuple | ast.List, value_node: ast.expr, node: ast.stmt) -> list[ir.Stmt]:
        """`a, b = f(args)` where `f` returns a fixed tuple of scalars (AOT mode): `ir.Unpack`."""
        line = node.lineno
        names: list[str] = []
        for e in target.elts:
            if not isinstance(e, ast.Name):
                raise Skip(line, f"the target `{_src(e)}` of a tuple unpacking must be a name")
            names.append(e.id)
        if len(set(names)) != len(names):
            raise Skip(line, f"`{_src(target)}` binds a name twice")
        if not (isinstance(value_node, ast.Call) and isinstance(value_node.func, ast.Name)
                and value_node.func.id in self.info.aot_tuples and not self.is_local(value_node.func.id)):
            raise Skip(line, f"`{_src(node)}`: a tuple is only unpacked from a call of a program function that "
                             "returns a fixed `tuple[...]` of ints, floats or bools")
        fname = value_node.func.id
        if fname not in self.env:
            raise Skip(line, f"`{fname}` is not compiled (see its own finding)")
        kinds = self.info.aot_tuples[fname]
        if len(names) != len(kinds):
            raise Skip(line, f"`{_src(node)}` unpacks {len(names)} names from the {len(kinds)} values `{fname}` returns")
        call = self.direct_call(fname, value_node, tuple_ok=True)
        if not isinstance(call, ir.Call):
            raise Skip(line, f"the call `{_src(value_node)}` is not a direct call of a program function")
        for n, kind in zip(names, kinds):
            if n in self.arrays:
                raise Skip(line, f"`{n}` is a list, but `{fname}` returns a {TYPE_NAME[kind]} there")
            t = self.local_type(n, line)
            if t != kind:
                raise Skip(line, f"`{n}` is {TYPE_NAME[t]}, but `{fname}` returns {TYPE_NAME[kind]} there")
            if kind == Type.I64 and n in self.acc_vars and not self.dead:
                self.found_bounds[n] = _join(self.found_bounds.get(n), TOP)
            self.refined.pop(n, None)
        return self.take() + [ir.Unpack(tuple(names), call)]

    def aot_fstring(self, node: ast.JoinedStr, value: bool = False) -> ir.Expr:
        """An f-string (AOT mode): constant text, `{e}` and `{e!s}` of an int, float, bool or str, `{e!r}` of one of
        those, and `{e:.Nf}` of a float. Given to `print` it is an OBJ `FString` written piece by piece; anywhere
        else (`value`, tier S1) a STR `FString`, the string the parts make. Anything else (`!a`, `=`, a width,
        another presentation type, a nested field) is an error, not an approximation."""
        parts: list[ir.Expr] = []
        for piece in node.values:
            if isinstance(piece, ast.Constant) and isinstance(piece.value, str):
                parts.append(ir.Const(Type.STR, piece.value))
                continue
            assert isinstance(piece, ast.FormattedValue)
            if piece.conversion not in (-1, 115, 114):
                raise Skip(piece.lineno, f"the conversion `!a` of `{_src(piece)}` in an f-string is not supported "
                                         "in an AOT program (`{e}`, `{e!s}`, `{e!r}` and `{e:.Nf}`)")
            precision = None
            if piece.format_spec is not None:
                texts = piece.format_spec.values                   # type: ignore[attr-defined]
                if not all(isinstance(t, ast.Constant) and isinstance(t.value, str) for t in texts):
                    raise Skip(piece.lineno, f"the format spec of `{_src(piece)}` has a nested field, which is not "
                                             "supported")
                spec = "".join(t.value for t in texts)             # type: ignore[attr-defined]
                m = re.fullmatch(r"\.([0-9]{1,2})f", spec)
                if m is None or int(m.group(1)) > AOT_MAX_PRECISION or piece.conversion != -1:
                    raise Skip(piece.lineno, f"the format spec `{spec}` in an f-string is not supported in an AOT "
                                             f"program (only `.Nf` with N from 0 to {AOT_MAX_PRECISION}, on a float "
                                             "with no conversion)")
                precision = int(m.group(1))
            listed = self.list_str(piece.value)
            v = listed if listed is not None else self.value(piece.value)
            if listed is not None and precision is None:
                parts.append(ir.FormatField(Type.OBJ, v, None, repr=False))   # a list's str is its repr (tier S2a)
                continue
            if precision is None and v.type not in ir.SCALARS and v.type not in (Type.STR, Type.STR_OPT):
                raise Skip(piece.lineno, f"`{_src(piece.value)}` in an f-string is {TYPE_NAME[v.type]}, not an "
                                         "int, float, bool or str")
            if precision is not None and v.type != Type.F64:
                raise Skip(piece.lineno, f"`{_src(piece.value)}:.{precision}f` formats a {TYPE_NAME[v.type]}; "
                                         "only a float takes `.Nf` in an AOT program")
            parts.append(ir.FormatField(Type.OBJ, v, precision, repr=piece.conversion == 114))
        return ir.FString(Type.STR if value else Type.OBJ, tuple(parts))

    # --- tier S1 (PR-S1a): str operations, AOT mode only (docs/design/typedpython-aot.md 4.3.1) ---

    def str_int(self, e: ir.Expr, node: ast.AST, what: str) -> ir.Expr:
        """An int operand of a str operation (an index, a bound, a count): an int as it is, a bool as 0 or 1."""
        if e.type == Type.I64:
            return e
        if e.type == Type.BOOL:
            return ir.BoolToInt(Type.I64, e)
        raise Skip(node.lineno, f"{what} `{_src(node)}` is {TYPE_NAME.get(e.type, e.type.value)}, not an int")

    def aot_subscript(self, node: ast.Subscript) -> ir.Expr:
        """`sys.argv[i]`, `s[i]` and `s[a:b:c]` of a str (AOT mode); a subscript of anything else has no AOT form."""
        line = node.lineno
        if self.is_sys_argv(node.value):
            if isinstance(node.slice, ast.Slice):
                raise Skip(line, f"the slice `{_src(node)}` of `sys.argv` is not supported in an AOT program (a list of "
                                 "str arrives with tier S1b)")
            return ir.ArgvStr(Type.STR, self.str_int(self.value(node.slice), node.slice, "the index"))
        value = self.sub(node.value)
        if value.type is Type.STR_LIST:
            if isinstance(node.slice, ast.Slice):
                raise Skip(line, f"the slice `{_src(node)}` of a list of str is tier S2 (lists that change and new "
                                 "lists from old ones)")
            return ir.StrListIndex(Type.STR, value, self.str_int(self.value(node.slice), node.slice, "the index"))
        if value.type is not Type.STR:
            raise Skip(line, f"the subscript `{_src(node)}` of a {TYPE_NAME.get(value.type, value.type.value)} has no "
                             "form in an AOT program")
        if isinstance(node.slice, ast.Slice):
            sl = node.slice
            bounds = []
            for b, what in ((sl.lower, "the start"), (sl.upper, "the stop"), (sl.step, "the step")):
                if b is None or (isinstance(b, ast.Constant) and b.value is None):
                    bounds.append(None)
                else:
                    bounds.append(self.str_int(self.value(b), b, what))
            return ir.StrSlice(Type.STR, value, bounds[0], bounds[1], bounds[2])
        return ir.StrIndex(Type.STR, value, self.str_int(self.value(node.slice), node.slice, "the index"))

    def str_arith(self, op: ir.BinOpKind, left: ir.Expr, right: ir.Expr, node: ast.AST) -> ir.Expr:
        """`+` of two strs and `*` of a str and an int (AOT mode); `%` is printf-style formatting, which waits for
        tier S2 (`aot/str-format`)."""
        lt, rt = left.type, right.type
        if Type.STR_OPT in (lt, rt):                                   # tier S2a: name the operand
            side = getattr(node, "left" if lt is Type.STR_OPT else "right", node)
            raise _optional(node.lineno, side)
        if op is ir.BinOpKind.ADD and lt is Type.STR and rt is Type.STR:
            return ir.StrConcat(Type.STR, left, right)
        if op is ir.BinOpKind.MUL and lt is Type.STR and rt in (Type.I64, Type.BOOL):
            return ir.StrRepeat(Type.STR, left, self.str_int(right, node, "the count"))
        if op is ir.BinOpKind.MUL and rt is Type.STR and lt in (Type.I64, Type.BOOL):
            return ir.StrRepeat(Type.STR, right, self.str_int(left, node, "the count"), count_first=True)
        if op is ir.BinOpKind.MOD and lt is Type.STR:
            raise Skip(node.lineno, f"`{_src(node)}`: printf-style `%` formatting of a str is tier S2 in an AOT program "
                                    "(aot/str-format); an f-string formats the same values")
        raise Skip(node.lineno, f"`{_src(node)}`: the operator `{op.value}` of a {TYPE_NAME.get(lt, lt.value)} and a "
                                f"{TYPE_NAME.get(rt, rt.value)} raises TypeError in CPython; an AOT program does not "
                                "compile it")

    def opt_link(self, op: ast.cmpop, left: ir.Expr, right: ir.Expr, node: ast.Compare) -> ir.Expr:
        """A comparison with a `str | None` operand (tier S2a): `is None` / `is not None`, and `==` / `!=` with a
        str, a `str | None` or None (None equals only None)."""
        none = (lambda e: isinstance(e, ir.Const) and e.value is None)
        if isinstance(op, (ast.Is, ast.IsNot)):
            if none(left) or none(right):
                x = right if none(left) else left
                return ir.Is(Type.BOOL, x, ir.Const(Type.OBJ, None), isinstance(op, ast.IsNot))
            raise Skip(node.lineno, f"`{_src(node)}`: `is` between two strs depends on interning in CPython "
                                    "(aot/str-identity); use `==`")
        if isinstance(op, (ast.Eq, ast.NotEq)):
            if none(left) or none(right):
                x = right if none(left) else left
                return ir.Is(Type.BOOL, x, ir.Const(Type.OBJ, None), isinstance(op, ast.NotEq))
            if {left.type, right.type} <= {Type.STR, Type.STR_OPT}:
                return ir.StrCompare(Type.BOOL, COMPARE[type(op)], left, right)
        raise Skip(node.lineno, f"`{_src(node)}`: a `str | None` value is used where a str is needed without a test "
                                "that it is not None (aot/optional; `if x is not None:` proves it)")

    def str_link(self, op: ast.cmpop, left: ir.Expr, right: ir.Expr, node: ast.Compare) -> ir.Expr:
        """One comparison with a str operand (AOT mode)."""
        lt, rt = left.type, right.type
        if isinstance(op, (ast.Is, ast.IsNot)):
            raise Skip(node.lineno, f"`{_src(node)}`: `is` on a str depends on interning and constant folding in "
                                    "CPython, so it has no defined result to reproduce (aot/str-identity); use `==`")
        if lt is not Type.STR or rt is not Type.STR:
            raise Skip(node.lineno, f"comparison `{_src(node)}` of a {TYPE_NAME.get(lt, lt.value)} with a "
                                    f"{TYPE_NAME.get(rt, rt.value)} is not supported in an AOT program (two strs)")
        if isinstance(op, (ast.In, ast.NotIn)):
            return ir.StrContains(Type.BOOL, left, right, isinstance(op, ast.NotIn))
        kind = COMPARE.get(type(op))
        if kind is None:
            raise Skip(node.lineno, f"comparison `{_src(node)}` is not supported")
        return ir.StrCompare(Type.BOOL, kind, left, right)

    def aot_str_builtin(self, name: str, node: ast.Call) -> ir.Expr | None:
        """`len`, `str`, `repr`, `ord` and `chr` where a str is involved (AOT mode); None: not one of these."""
        args = node.args
        line = node.lineno
        if name in ("str", "repr") and self.builtin(name):
            if name == "str" and not args:
                return ir.Const(Type.STR, "")
            if len(args) != 1:
                raise Skip(line, f"{name}() takes one argument in an AOT program")
            listed = self.list_str(args[0])
            if listed is not None:
                return listed                           # tier S2a: str(a) and repr(a) of a list are its repr
            x = self.value(args[0])
            if x.type not in ir.SCALARS and x.type not in (Type.STR, Type.STR_LIST, Type.STR_OPT):
                raise Skip(line, f"{name}() of a {TYPE_NAME.get(x.type, x.type.value)} is not supported in an AOT "
                                 "program (int, float, bool, str and list[str])")
            return ir.StrOf(Type.STR, x, repr=name == "repr")
        if name == "ord" and self.builtin("ord"):
            if len(args) != 1:
                raise Skip(line, "ord() takes one argument")
            x = self.value(args[0])
            if x.type is not Type.STR:
                raise Skip(line, f"ord() of a {TYPE_NAME.get(x.type, x.type.value)} is not supported in an AOT program")
            return ir.StrOrd(Type.I64, x)
        if name == "chr" and self.builtin("chr"):
            if len(args) != 1:
                raise Skip(line, "chr() takes one argument")
            return ir.StrChr(Type.STR, self.str_int(self.value(args[0]), args[0], "the argument of chr()"))
        if name == "len" and self.builtin("len") and len(args) == 1 \
                and not (isinstance(args[0], ast.Name) and args[0].id in self.arrays):
            x = self.value(args[0])
            if x.type is Type.STR:
                return ir.StrLen(Type.I64, x)
            if x.type is Type.STR_LIST:
                return ir.StrListLen(Type.I64, x)
            if x.type is Type.STR_OPT:                                 # tier S2a
                raise _optional(line, args[0])
            raise Skip(line, f"len() of a {TYPE_NAME.get(x.type, x.type.value)} is not supported in an AOT program")
        return None

    # --- AOT mode, tier S2a: the methods of a list[float] / list[int] (design 4.3.2) ---

    def list_str(self, node: ast.expr) -> ir.Expr | None:
        """`a` (a list[float] / list[int] name) where its str is wanted: print, str(), repr(), an f-string field; the
        str and the repr of a list are the same text (tier S2a). None for anything else."""
        if self.info.aot and isinstance(node, ast.Name) and node.id in self.arrays:
            return ir.ListStr(Type.STR, node.id)
        return None

    def is_list_name(self, name: str) -> bool:
        """AOT mode, tier S2a: `name` holds a list whose methods the AOT mode has: a list[float] / list[int] (an
        array) or a list[str] local or parameter (a STR_LIST value)."""
        if name in self.arrays:
            return True
        return self.info.aot and self.is_local(name) and self.local_type(name, 0) is Type.STR_LIST

    def list_type(self, array: str) -> Type:
        return self.arrays[array] if array in self.arrays else Type.STR_LIST

    def list_elem(self, array: str) -> Type:
        t = self.list_type(array)
        return Type.F64 if t == Type.F64_ARRAY else Type.STR if t == Type.STR_LIST else Type.I64

    def list_item(self, array: str, node: ast.expr, what: str) -> ir.Expr:
        """An item stored into `array`: its element type exactly (an int stored into a list of float stays an int in
        CPython, so it has no AOT form, as for `a[i] = v`)."""
        return self.coerce(self.value(node), self.list_elem(array), node, what)

    def list_index_arg(self, node: ast.expr, what: str) -> ir.Expr:
        e = self.value(node)
        if e.type is Type.BOOL:
            e = ir.BoolToInt(Type.I64, e)
        if e.type is not Type.I64:
            raise Skip(node.lineno, f"{what} `{_src(node)}` is {TYPE_NAME.get(e.type, e.type.value)}, not an int")
        return e

    def list_method_stmt(self, node: ast.Call) -> list[ir.Stmt]:
        """`a.append(v)`, `a.insert(i, v)`, `a.extend(b)`, `a.clear()`, `a.reverse()`, `a.remove(v)` as statements."""
        func = node.func
        assert isinstance(func, ast.Attribute) and isinstance(func.value, ast.Name)
        array, m, args, line = func.value.id, func.attr, node.args, node.lineno
        want = {"append": 1, "insert": 2, "extend": 1, "clear": 0, "reverse": 0, "remove": 1}[m]
        if node.keywords or len(args) != want:
            raise Skip(line, f"`{_src(node)}`: list.{m}() takes {want} positional argument(s) in an AOT program")
        self.hoist_ok = False
        self.list_effect(array)
        what = f"the item given to `{array}.{m}`"
        if m == "append":
            out: ir.Stmt = ir.ListAppend(array, self.list_item(array, args[0], what))
        elif m == "insert":
            at = self.list_index_arg(args[0], f"the index of `{array}.insert`")
            out = ir.ListInsert(array, at, self.list_item(array, args[1], what))
        elif m == "extend":
            other = args[0]
            if not (isinstance(other, ast.Name) and self.is_list_name(other.id)
                    and self.list_type(other.id) == self.list_type(array)):
                raise Skip(line, f"`{_src(node)}`: an AOT program extends a list by another list name of its type")
            out = ir.ListExtend(array, other.id)
        elif m == "clear":
            out = ir.ListClear(array)
        elif m == "reverse":
            out = ir.ListReverse(array)
        else:
            out = ir.ListRemove(array, self.list_search_value(array, args[0], node))
        return self.take() + [out]

    def list_effect(self, array: str) -> None:
        if array in self.arrays:
            self.store_effect(array)
        else:
            self.impure = True              # a list[str] changed through a name: an effect, as a store is

    def list_search_value(self, array: str, node: ast.expr, call: ast.AST) -> ir.Expr:
        if self.list_type(array) == Type.STR_LIST:
            return self.coerce(self.value(node), Type.STR, node, f"the str searched in `{array}`")
        if self.arrays[array] == Type.F64_ARRAY:
            raise Skip(call.lineno, f"`{_src(call)}`: searching a list of float depends on object identity for NaN in "
                                    "CPython (`x in a` is `y is x or y == x` for each item), which an AOT program does "
                                    "not have (aot/identity)")
        e = self.value(node)
        if e.type is Type.BOOL:
            e = ir.BoolToInt(Type.I64, e)           # True == 1 in CPython
        if e.type is not Type.I64:
            raise Skip(call.lineno, f"`{_src(call)}`: the value searched for is "
                                    f"{TYPE_NAME.get(e.type, e.type.value)}, not an int")
        return e

    def list_method_expr(self, func: ast.Attribute, node: ast.Call) -> ir.Expr:
        """`a.pop()`, `a.pop(i)`, `a.index(v)`, `a.count(v)` as values; a method that returns None is refused here."""
        assert isinstance(func.value, ast.Name)
        array, m, args, line = func.value.id, func.attr, node.args, node.lineno
        if m in LIST_STMT_METHODS:
            raise Skip(line, f"`{_src(node)}` returns None; an AOT program calls list.{m}() as a statement")
        if node.keywords:
            raise Skip(line, f"`{_src(node)}`: keyword arguments of list.{m}() are not supported")
        self.hoist_ok = False
        if m == "pop" and len(args) <= 1:
            self.list_effect(array)
            at = self.list_index_arg(args[0], f"the index of `{array}.pop`") if args else None
            return ir.ListPop(self.list_elem(array), array, at)
        if m in ("index", "count") and len(args) == 1:
            return ir.ListSearch(Type.I64, m, array, self.list_search_value(array, args[0], node))
        raise Skip(line, f"`{_src(node)}`: list.{m}() is not supported in an AOT program yet")

    def str_receiver(self, node: ast.expr) -> bool:
        """Is `node` (the receiver of a method call) a str value rather than a module or an instance of a compiled
        class? (A module name or a class local keeps the paths it had.)"""
        if isinstance(node, ast.Name):
            if not self.is_local(node.id):
                return node.id in self.info.aot_consts and self.info.aot_consts[node.id].type is Type.STR
            t = self.local_type(node.id, node.lineno) if node.id not in self.arrays else None
            if t is Type.STR_OPT and node.id not in self.not_none:
                raise _optional(node.lineno, node)                      # tier S2a: a method of a possible None
            return t is Type.STR or t is Type.STR_OPT
        if isinstance(node, ast.Attribute) and isinstance(node.value, ast.Name) \
                and node.value.id in ("sys", "math", "time") and not self.is_local(node.value.id):
            return False                                    # a module's attribute (sys.stdout.write, ...)
        return True

    def str_method(self, func: ast.Attribute, node: ast.Call) -> ir.Expr:
        """`s.<method>(...)` of tier S1b (design 4.3.1), keyword arguments mapped to their places."""
        line = node.lineno
        name = func.attr
        recv = self.sub(func.value)
        if recv.type is not Type.STR:
            raise Skip(line, f"`{_src(func)}`: `.{name}` of a {TYPE_NAME.get(recv.type, recv.type.value)} has no form "
                             "in an AOT program")
        params, result = STR_METHODS[name]
        if len(node.args) > len(params):
            raise Skip(line, f"str.{name}() takes at most {len(params)} argument(s)")
        given: list[ast.expr | None] = list(node.args) + [None] * (len(params) - len(node.args))
        for k in node.keywords:
            # CPython: only split's sep / maxsplit and replace's count may be given by keyword (TypeError otherwise)
            if k.arg not in STR_METHOD_KEYWORDS.get(name, ()) or given[params.index(k.arg)] is not None:
                raise Skip(line, f"str.{name}() takes no keyword argument `{k.arg}` (CPython raises TypeError)")
            given[params.index(k.arg)] = k.value
        while given and given[-1] is None:
            given.pop()
        args: list[ir.Expr | None] = []
        prefixes: list[ir.Expr] = []
        any_of = False
        for pname, a in zip(params, given):
            if a is None or (isinstance(a, ast.Constant) and a.value is None):
                if pname in ("sub", "old", "new", "iterable") or (pname == "prefix"):
                    raise Skip(line, f"str.{name}() needs `{pname}`")
                args.append(None)
                continue
            if pname == "prefix":
                elts = [self.value(x) for x in a.elts] if isinstance(a, ast.Tuple) else [self.value(a)]
                if any(x.type is not Type.STR for x in elts):
                    raise Skip(line, f"str.{name}() takes a str or a tuple of str")
                prefixes = elts
                any_of = isinstance(a, ast.Tuple)
                continue
            v = self.value(a)
            if pname == "iterable" and isinstance(v, ir.NewList):
                v = ir.StrListDisplay(Type.STR_LIST, ())              # `sep.join([])`
            want = Type.STR_LIST if pname == "iterable" else (
                Type.I64 if pname in ("maxsplit", "count", "start", "end") else Type.STR)
            if want is Type.I64:
                v = self.str_int(v, a, f"`{pname}` of str.{name}()")
            elif v.type is not want:
                raise Skip(line, f"`{pname}` of str.{name}() is {TYPE_NAME.get(v.type, v.type.value)}, not "
                                 f"{TYPE_NAME[want]}")
            args.append(v)
        if name in ("startswith", "endswith"):
            # layout: start, end (None when absent), then every prefix (a tuple's elements, evaluated first, in order)
            args = (args + [None, None])[:2] + list(prefixes)
        return ir.StrMethod(result, recv, name, tuple(args), any_of)

    def std_write(self, func: ast.Attribute, node: ast.Call) -> ir.Expr | None:
        """`sys.stdout.write(s)` / `sys.stderr.write(s)` (AOT mode, tier S1); None: not one of these."""
        v = func.value
        if not (func.attr == "write" and isinstance(v, ast.Attribute) and v.attr in ("stdout", "stderr")
                and isinstance(v.value, ast.Name) and v.value.id == "sys" and self.info.aot_sys
                and not self.is_local("sys")):
            return None
        if node.keywords or len(node.args) != 1:
            raise Skip(node.lineno, f"`sys.{v.attr}.write` takes one argument")
        x = self.value(node.args[0])
        if x.type is not Type.STR:
            raise Skip(node.lineno, f"`sys.{v.attr}.write` of a {TYPE_NAME.get(x.type, x.type.value)}: the text stream "
                                    "takes a str")
        return ir.StrWrite(Type.I64, x, err=v.attr == "stderr")

    # --- fixed-layout classes ---

    def new_class(self, node: ast.expr) -> str | None:
        """The class C when `node` is `C(args)` that lowers to `ir.New`."""
        if not (isinstance(node, ast.Call) and isinstance(node.func, ast.Name)):
            return None
        decl = self.info.classes.get(node.func.id)
        if decl is None or self.is_local(decl.name) \
                or node.keywords or any(isinstance(a, ast.Starred) for a in node.args):
            return None
        if self.info.aot:
            init_fn = self.env.get(f"{decl.name}.__init__")
            expected = len(init_fn.params) - 1 if init_fn is not None else len(decl.fields)
            if len(node.args) != expected:
                return None
            return decl.name
        if not decl.trivial_init or len(node.args) != len(decl.fields):
            return None
        return decl.name

    def new_locals(self) -> dict[str, str]:
        """Locals every one of whose bindings is a `C(...)` lowered to `New`: always exactly a C."""
        if self._new_locals is None:
            good: dict[str, set[str]] = {}
            bad: set[str] = set()
            for stmt in rebinding._own_statements(self.fn):
                if stmt is self.fn:
                    continue
                value = None
                if isinstance(stmt, ast.Assign) and len(stmt.targets) == 1 \
                        and isinstance(stmt.targets[0], ast.Name):
                    name, value = stmt.targets[0].id, stmt.value
                elif isinstance(stmt, ast.AnnAssign) and isinstance(stmt.target, ast.Name):
                    name, value = stmt.target.id, stmt.value
                    if value is None:
                        continue
                else:
                    for t in _binding_targets(stmt):
                        bad |= {n.id for n in ast.walk(t) if isinstance(n, ast.Name)}
                    continue
                c = self.new_class(value)
                if c is None:
                    bad.add(name)
                else:
                    good.setdefault(name, set()).add(c)
            self._new_locals = {n: next(iter(cs)) for n, cs in good.items()
                                if n not in bad and len(cs) == 1 and n not in self.params}
        return self._new_locals

    def cls_of_name(self, name: str) -> tuple[str | None, bool]:
        """(compiled class the name is declared to hold, whether its exact type is proved)."""
        classes = self.info.classes
        if name in self.params:
            p = self.params[name]
            if isinstance(p, ir.Param) and p.cls is not None:
                if self.info.aot:
                    return p.cls, True
                rebound = any(b.how != "annotation" for b in self.bindings.get(name, []))
                return p.cls, not p.optional and not rebound
            return None, False
        if name in self.new_locals():
            return self.new_locals()[name], True
        if name in self.bindings:
            kinds = list(self.annotations.get(name, []))
            if name not in self.annotations:
                kinds += self.inferred.get(name, [])
            found = {_class_of(_aot_unqualified(k, classes) if self.info.aot else k, classes) for _, k in kinds}
            if len(found) == 1 and None not in found:
                return next(iter(found))[0], self.info.aot  # type: ignore[index]
        return None, False

    def cls_optional(self, name: str) -> bool:
        """AOT mode, tier S2a: the class local or parameter `name` is declared `C | None` (so a field read or a
        method call through it needs a proof that it is not None)."""
        classes = self.info.classes
        if name in self.params:
            p = self.params[name]
            return isinstance(p, ir.Param) and p.cls is not None and p.optional
        if name in self.new_locals() or name not in self.bindings:
            return False
        kinds = list(self.annotations.get(name, []))
        if name not in self.annotations:
            kinds += self.inferred.get(name, [])
        return any((c := _class_of(_aot_unqualified(k, classes), classes)) is not None and c[1] for _, k in kinds)

    def check_not_none(self, name: str, cls: str, node: ast.AST) -> None:
        """AOT mode, tier S2a: refuse a field or method use through a `C | None` name not proved not None here
        (before S2a this read had no check, so a None crashed instead of raising AttributeError)."""
        if self.info.aot and name not in self.not_none and self.cls_optional(name):
            raise _optional(getattr(node, "lineno", 0), node, cls)

    def field_target(self, node: ast.Attribute) -> tuple[ir.Local, str, bool] | None:
        """(object, class, exact type proved) for `name.field` of a compiled class's field."""
        if not (isinstance(node.value, ast.Name) and self.is_local(node.value.id)):
            return None
        cls, exact = self.cls_of_name(node.value.id)
        if cls is None or node.attr not in self.info.classes[cls].fields:
            return None
        self.check_not_none(node.value.id, cls, node.value)
        return ir.Local(Type.OBJ, node.value.id), cls, exact

    def field_get(self, node: ast.Attribute) -> ir.Expr | None:
        found = self.field_target(node)
        if found is None:
            return None
        obj, cls, exact = found
        if self.info.aot:
            decl = self.info.classes[cls]
            fd = next((f for f in decl.field_decls if f.name == node.attr), None)
            ftype = fd.type if fd is not None else Type.OBJ
            return ir.FieldGet(ftype, obj, cls, node.attr)
        if not exact:
            if self.impure_mode:
                raise Skip(node.lineno, f"`{_src(node)}`: the exact type of `{obj.name}` is not "
                                        f"proved to be `{cls}`, and an impure function cannot "
                                        "deopt on a failed check")
            obj = ir.CheckExact(Type.OBJ, obj, cls)
            self.deopts = True
        return ir.FieldGet(Type.OBJ, obj, cls, node.attr)

    def field_set(self, target: ast.Attribute, value_node: ast.expr) -> list[ir.Stmt] | None:
        found = self.field_target(target)
        if found is None:
            return None
        obj, cls, exact = found
        if self.info.aot:
            decl = self.info.classes[cls]
            fd = next((f for f in decl.field_decls if f.name == target.attr), None)
            if fd is not None and fd.type in ir.ARRAYS:
                value = self.array_expr(value_node, fd.type, f"field `{cls}.{target.attr}`")
            else:
                value = self.value(value_node)
                if fd is not None and fd.type == Type.F64 and value.type == Type.I64:
                    value = ir.ToFloat(Type.F64, value)
            self.impure = True
            return self.take() + [ir.FieldSet(obj, cls, target.attr, value)]
        if not exact:
            raise Skip(target.lineno, f"`{_src(target)} = ...`: the exact type of `{obj.name}` is "
                                      f"not proved to be `{cls}`, and a store cannot be guarded by "
                                      "a deopting check")
        value = self.boxed(self.value(value_node), value_node)
        self.impure = True
        return self.take() + [ir.FieldSet(obj, cls, target.attr, value)]

    def arg_proved(self, e: ir.Expr, p: ir.Param) -> bool:
        """The argument `e` is certainly accepted by the callee's entry guard for `p.cls`."""
        if isinstance(e, ir.Const) and e.value is None:
            return p.optional
        if isinstance(e, (ir.New, ir.CheckExact, ir.CheckMethod)):
            return e.cls == p.cls
        if isinstance(e, ir.Local) and e.type == Type.OBJ:
            cls, exact = self.cls_of_name(e.name)
            return exact and cls == p.cls
        return False

    def array_word(self, name: str) -> str:
        return "local array" if name in self.local_arrays else "array parameter"

    def store_effect(self, array: str) -> None:
        """A store into a parameter list is an effect (write-back); into a local array it is not."""
        if array not in self.local_arrays:
            self.stored.add(array)
            self.impure = True

    def new_array(self, name: str, value_node: ast.expr, node: ast.stmt) -> list[ir.Stmt]:
        line = node.lineno
        t = self.local_arrays[name]
        elem = Type.F64 if t == Type.F64_ARRAY else Type.I64
        what = f"local array `{name}: {TYPE_NAME[t]}`"
        kind = _creation_kind(value_node)
        if kind == "iota":
            call = value_node.args[0]  # type: ignore[attr-defined]
            if not (self.builtin("list") and self.builtin("range")):
                raise Skip(line, f"`{_src(value_node)}` with `list` or `range` rebound")
            if elem != Type.I64:
                raise Skip(line, f"`{_src(value_node)}` is a list of int, but {what} is "
                                 f"{TYPE_NAME[t]}")
            length = self.range_bound(call.args[0])
            return self.take() + [ir.Assign(name, ir.NewArray(t, length, None, True))]
        if kind == "fill":
            assert isinstance(value_node, ast.BinOp) and isinstance(value_node.left, ast.List)
            fill = self.coerce(self.value(value_node.left.elts[0]), elem,
                               value_node.left.elts[0], what)
            if not _trivial(fill):
                fill = self.temp(fill)  # `[v] * n` evaluates v before n
            length = self.value(value_node.right)
            if length.type != Type.I64:
                why = (" (it is a CPython int here: its arithmetic is not proved free of i64 "
                       "overflow in an impure function)" if length.type in (Type.OBJ, Type.INT)
                       and self.impure_mode else "")
                raise Skip(line, f"array length `{_src(value_node.right)}` is "
                                 f"{TYPE_NAME[length.type]}, not a native int{why}")
            return self.take() + [ir.Assign(name, ir.NewArray(t, length, fill))]
        if kind == "copy":
            assert isinstance(value_node, ast.Subscript) and isinstance(value_node.value, ast.Name)
            src = value_node.value.id
            if self.arrays[src] != t:
                raise Skip(line, f"`{_src(value_node)}` is {TYPE_NAME[self.arrays[src]]}, but "
                                 f"{what} is {TYPE_NAME[t]}")
            return [ir.Assign(name, ir.CopyArray(t, src))]
        raise Skip(line, f"{what} is rebound to `{_src(value_node)}`, which does not create an array")

    def aug_assign(self, node: ast.AugAssign) -> list[ir.Stmt]:
        line = node.lineno
        target = node.target
        if self.info.aot and isinstance(target, ast.Name) and self.is_list_name(target.id) \
                and isinstance(node.op, ast.Add):
            # tier S2a: `a += b` extends `a` in place (list.__iadd__: the same object, every alias sees it)
            other = node.value
            if not (isinstance(other, ast.Name) and self.is_list_name(other.id)
                    and self.list_type(other.id) == self.list_type(target.id)):
                raise Skip(line, f"`{_src(node)}`: an AOT program extends a list by another list name of its type")
            self.hoist_ok = False
            self.list_effect(target.id)
            return self.take() + [ir.ListExtend(target.id, other.id)]
        op = self.binop_kind(node.op, node)
        if isinstance(target, ast.Name):
            name = target.id
            if name in self.arrays:
                raise Skip(line, f"{self.array_word(name)} `{name}` is rebound")
            if not self.is_local(name):
                raise _unsupported(node)
            t = self.local_type(name, line)
            current = ir.Local(t, name)
            result = self.arith(op, current, self.sub(node.value), node, inplace=True)
            if t == Type.INT and result.type == Type.OBJ:
                raise _ObjInt(name)
            result = self.coerce(result, t, node, f"local `{name}`")
            self.note_assign(name, result)
            return self.take() + [ir.Assign(name, result)]
        if isinstance(target, ast.Subscript):
            array, elem = self.array_of(target)
            index = self.index(target.slice)
            if not isinstance(index, (ir.Local, ir.Const)):
                index = self.temp(index)  # `a[i] += e` evaluates `i` once
            load = ir.Index(elem, array, index)
            self.hoist_ok = False
            result = self.arith(op, load, self.sub(node.value), node)
            result = self.coerce(result, elem, node, f"`{array}: {TYPE_NAME[self.arrays[array]]}`")
            self.store_effect(array)
            return self.take() + [ir.StoreIndex(array, index, result)]
        if isinstance(target, ast.Attribute):
            raise Skip(line, f"assignment to attribute `{_src(target)}` is not supported")
        raise _unsupported(target)

    def for_object(self, node: ast.For, call: ast.Call | None = None) -> list[ir.Stmt]:
        """`for t in obj` / `for a, b in obj` (ir.ForObj); `call` is an object-bounded `range(...)`."""
        line = node.lineno
        target = node.target
        if isinstance(target, ast.Name):
            names, unpack = [target.id], False
        elif isinstance(target, (ast.Tuple, ast.List)) and target.elts \
                and all(isinstance(e, ast.Name) for e in target.elts):
            names, unpack = [e.id for e in target.elts], True  # type: ignore[attr-defined]
        else:
            raise Skip(line, f"loop target `{_src(target)}` is not supported (names only)")
        if len(set(names)) != len(names):
            raise Skip(line, f"loop target `{_src(target)}` binds a name twice")
        for n in names:
            if n in self.arrays:
                raise Skip(line, f"loop variable `{n}` is an array")
            if self.local_type(n, line) != Type.OBJ:
                raise Skip(line, f"loop variable `{n}` is a native {TYPE_NAME[self.local_type(n, line)]}"
                                 ", but a loop over an object binds objects")
        if call is not None:      # range(...) with an object bound: called as the object it is
            callee = ir.Global(Type.OBJ, "range")
            self.hoist_ok = False
            it: ir.Expr = ir.CallObject(Type.OBJ, callee, tuple(
                self.boxed(self.value(a), a) for a in call.args))
        else:
            it = self.boxed(self.value(node.iter), node.iter)
        self.impure = True
        pre = self.take()
        killed = _stored_names(node.body) | set(names)
        head = {k: v for k, v in self.refined.items() if k not in killed}
        self.refined = head
        body = self.block(node.body)
        self.refined = dict(head)
        return pre + [ir.ForObj(tuple(names), it, body, unpack)]

    def for_str(self, node: ast.For) -> list[ir.Stmt]:
        """AOT mode, tier S1: `for ch in s` over a str (`ir.ForStr`); the only iterable beside `range(...)`."""
        line = node.lineno
        if not isinstance(node.target, ast.Name):
            raise Skip(line, f"loop target `{_src(node.target)}` is not supported (only a name)")
        var = node.target.id
        if var in self.arrays or var in self.params and self.params[var].type is not Type.STR:
            raise Skip(line, f"loop variable `{var}` is not a str name")
        it = self.value(node.iter)
        if it.type not in (Type.STR, Type.STR_LIST):
            raise Skip(line, f"`for {var} in {_src(node.iter)}`: an AOT program iterates `range(...)`, a str and a list "
                             "of str (the other containers arrive with tier S2)")
        t = self.types.get(var) or (self.params[var].type if var in self.params else None)
        if t is None:
            t = self.local_type(var, line)
            if t is Type.OBJ:       # nothing but the loop binds it: a loop over a str binds a str
                self.types[var] = t = Type.STR
        if t is not Type.STR:
            raise Skip(line, f"loop variable `{var}` is {TYPE_NAME.get(t, t.value)}, but a loop over a str binds a str")
        pre = self.take()
        killed = _stored_names(node.body) | {var}
        head = {k: v for k, v in self.refined.items() if k not in killed}
        self.refined = head
        body = self.block(node.body)
        self.refined = dict(head)
        return pre + [ir.ForStr(var, it, body)]

    def for_list(self, node: ast.For) -> list[ir.Stmt]:
        """AOT mode, tier S2a: `for v in a` over a `list[float]` / `list[int]`, as CPython's list iterator runs it: a
        hidden index, and the length read again before every step, so an item appended in the body is visited and
        a list shortened in the body ends the loop (Objects/listobject.c, listiter_next). Lowered to
        `i = 0; while i < len(a): v = a[i]; i += 1; body` (the index moves before the body, so `continue` keeps it)."""
        line = node.lineno
        assert isinstance(node.iter, ast.Name)
        array = node.iter.id
        if not isinstance(node.target, ast.Name):
            raise Skip(line, f"loop target `{_src(node.target)}` is not supported (only a name)")
        var = node.target.id
        if var in self.arrays or var in self.params:
            raise Skip(line, f"loop variable `{var}` is a list or a parameter")
        if array in _stored_names(node.body):
            # CPython's iterator holds the list it started on; this lowering reads the name at every step
            raise Skip(line, f"`for {var} in {array}` whose body rebinds `{array}`: CPython keeps iterating the "
                             "first list, which an AOT program does not hold yet; iterate a copy or another name")
        elem = Type.F64 if self.arrays[array] == Type.F64_ARRAY else Type.I64
        t = self.types.get(var) or self.local_type(var, line)
        if t is Type.OBJ:       # nothing but the loop binds it (Pyrefly gives a loop target no type): the element's
            self.types[var] = t = elem
        if t != elem:
            raise Skip(line, f"loop variable `{var}` is {TYPE_NAME.get(t, t.value)}, but a loop over the "
                             f"{TYPE_NAME[self.arrays[array]]} `{array}` binds {TYPE_NAME[elem]}")
        idx = self.temp(ir.Const(Type.I64, 0))
        pre = self.take()
        killed = _stored_names(node.body) | {var}
        head = {k: v for k, v in self.refined.items() if k not in killed}
        self.refined = head
        self.not_none -= killed
        step = [ir.Assign(var, ir.Index(elem, array, idx)),
                # proven: the loop test gives index < len(a) <= LEN_MAX (the verifier proves it again)
                ir.Assign(idx.name, ir.BinOp(Type.I64, ir.BinOpKind.ADD, idx, ir.Const(Type.I64, 1), proven=True))]
        body = self.block(node.body)
        self.refined = dict(head)
        self.not_none -= killed
        cond = ir.Compare(Type.BOOL, ir.CompareKind.LT, idx, ir.Len(Type.I64, array))
        return pre + [ir.While(cond, (*step, *body))]

    def for_range(self, node: ast.For) -> list[ir.Stmt]:
        line = node.lineno
        if node.orelse:
            raise Skip(line, "`for ... else` is not supported")
        if isinstance(node.iter, ast.Name) and node.iter.id in self.arrays:
            if self.info.aot:
                return self.for_list(node)
            raise self.array_misuse(node.iter.id, node.iter)
        if not (_is_range_call(node.iter) and self.builtin("range")):
            if self.info.aot:
                return self.for_str(node)
            return self.for_object(node)
        call = node.iter
        assert isinstance(call, ast.Call)
        if not isinstance(node.target, ast.Name):
            raise Skip(line, f"loop target `{_src(node.target)}` is not supported (only a name)")
        var = node.target.id
        if var in self.obj_ranges:
            return self.for_object(node, call)
        if var in self.params:
            raise Skip(line, f"loop variable `{var}` is a parameter")
        for inner in ast.walk(ast.Module(body=node.body, type_ignores=[])):
            for target in _binding_targets(inner) if isinstance(inner, ast.stmt) else []:
                if any(isinstance(n, ast.Name) and isinstance(n.ctx, ast.Store) and n.id == var
                       for n in ast.walk(target)):
                    raise Skip(inner.lineno, f"loop variable `{var}` is assigned in the loop body")
        if self.local_type(var, line) != Type.I64:
            other = next(b.line for b in self.bindings[var] if b.how != "range")
            raise Skip(line, f"loop variable `{var}` is also assigned at line {other}; in an "
                             "impure function that makes it a CPython int, not a range index")
        args = call.args
        if len(args) == 3:
            step = _int_literal(args[2])
            if step is None and self.info.aot:
                step = self.aot_int(args[2])
            if step is None or step == 0:
                raise Skip(line, f"range step `{_src(args[2])}` must be a non-zero int literal")
        else:
            step = 1
        if len(args) == 1:
            start: ir.Expr = ir.Const(Type.I64, 0)
            stop = self.range_bound(args[0], var)
        else:
            start = self.range_bound(args[0], var)
            stop = self.range_bound(args[1], var)
        pre = self.take()

        s, e = self.interval(start), self.interval(stop)
        if s is not None and e is not None and not self.dead:
            if step > 0:
                iv = (s[0], max(s[0], e[1] - 1))
            else:
                iv = (min(s[1], e[0] + 1), s[1])
            self.found_bounds[var] = _join(self.found_bounds.get(var), iv)

        killed = _stored_names(node.body) | {var}
        head = {k: v for k, v in self.refined.items() if k not in killed}
        self.refined = head
        body = self.block(node.body)
        self.refined = dict(head)
        return pre + [ir.ForRange(var, start, stop, ir.Const(Type.I64, step), body)]

    def aot_int(self, node: ast.expr) -> int | None:
        """AOT mode: the value of an int module constant read (`STEP`, `-STEP`) where a range step must
        be a literal, else None."""
        sign = 1
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.USub, ast.UAdd)):
            sign = -1 if isinstance(node.op, ast.USub) else 1
            node = node.operand
        if isinstance(node, ast.Name) and not self.is_local(node.id):
            c = self.info.aot_consts.get(node.id)
            if c is not None and c.type == Type.I64:
                return sign * c.value       # type: ignore[operator]
        return None

    def range_bound(self, node: ast.expr, var: str | None = None) -> ir.Expr:
        e = self.value(node)
        if e.type in (Type.OBJ, Type.INT) and self.impure_mode and var is not None:
            raise _ObjRange(var)    # a CPython int object: the loop is a ForObj over range(...)
        if e.type != Type.I64:
            why = (" (it is a CPython int here: its arithmetic is not proved free of i64 overflow "
                   "in an impure function)" if e.type in (Type.OBJ, Type.INT) and self.impure_mode else "")
            raise Skip(node.lineno, f"range bound `{_src(node)}` is not a native int{why}")
        return e

    def return_(self, node: ast.Return) -> list[ir.Stmt]:
        returns = self.sig.returns
        if node.value is None or (isinstance(node.value, ast.Constant) and node.value.value is None):
            if returns == Type.OBJ:
                return [ir.Return(ir.Const(Type.OBJ, None))]
            if returns == Type.STR_OPT:
                return [ir.Return(ir.Const(Type.STR_OPT, None))]      # tier S2a
            if returns != Type.NONE:
                raise Skip(node.lineno, f"returns None from a function declared to return "
                                        f"{TYPE_NAME[returns]}")
            return [ir.Return(None)]
        if returns == Type.NONE:
            raise Skip(node.lineno, "returns a value from a function declared to return None")
        if self.info.aot and returns in ir.ARRAYS:
            e = self.array_expr(node.value, returns, "the return value")
            return self.take() + [ir.Return(e)]
        if isinstance(node.value, ast.Tuple):
            return self.return_tuple(node.value)
        value = self.value(node.value)
        value = self.coerce(value, returns, node.value, "the return value")
        return self.take() + [ir.Return(value)]

    def return_tuple(self, node: ast.Tuple) -> list[ir.Stmt]:
        sig = self.sig
        if any(isinstance(e, ast.Starred) for e in node.elts):
            raise Skip(node.lineno, f"`return {_src(node)}` with a starred element is not supported")
        if sig.tuple_returns is None:
            why = ("a variable-length `tuple[..., ...]`" if sig.tuple_ellipsis
                   else f"`{sig.return_annotation}`")
            raise Skip(node.lineno, f"returns `{_src(node)}`, but the declared return type is "
                                    f"{why}, not a fixed `tuple[...]`")
        kinds = sig.tuple_returns
        if len(kinds) != len(node.elts):
            raise Skip(node.lineno, f"returns {len(node.elts)} values, but the return type "
                                    f"`{sig.return_annotation}` declares {len(kinds)}")
        elements = []
        for kind, e_node in zip(kinds, node.elts):
            e = self.value(e_node)
            if e.type in ir.SCALARS and kind in ir.SCALARS and e.type != kind:
                raise Skip(e_node.lineno, f"`{_src(e_node)}` is {TYPE_NAME[e.type]}, but the "
                                          f"return type `{sig.return_annotation}` declares "
                                          f"{TYPE_NAME[kind]} there")
            if self.info.aot:
                if e.type != kind or kind not in ir.SCALARS:
                    raise Skip(e_node.lineno, f"`{_src(e_node)}` is {TYPE_NAME[e.type]}, but the return type "
                                              f"`{sig.return_annotation}` declares {TYPE_NAME.get(kind, 'something else')} "
                                              "there (a tuple of ints, floats and bools only)")
                elements.append(e)
                continue
            elements.append(self.boxed(e, e_node))
        if self.info.aot:
            return self.take() + [ir.ReturnTuple(tuple(elements))]
        return self.take() + [ir.Return(ir.Tuple(Type.OBJ, tuple(elements)))]

    # --- expressions ---

    def sub(self, node: ast.expr, cond: int = 0) -> ir.Expr:
        """Lower a subexpression in evaluation order; after a non-trivial one, nothing later in
        the statement may be hoisted in front of it."""
        e = self.expr(node, cond)
        if not _trivial(e):
            self.hoist_ok = False
        return e

    def value(self, node: ast.expr) -> ir.Expr:
        e = self.sub(node)
        if e.type == Type.NONE:
            raise Skip(node.lineno, f"uses the result of `{_src(node)}`, which returns None")
        return e

    def condition(self, node: ast.expr) -> ir.Expr:
        return self.truth(self.sub(node, cond=2), node)

    def truth(self, e: ir.Expr, node: ast.expr) -> ir.Expr:
        if e.type == Type.BOOL:
            return e
        if e.type == Type.I64:
            return ir.Compare(Type.BOOL, ir.CompareKind.NE, e, ir.Const(Type.I64, 0))
        if e.type == Type.F64:
            return ir.Compare(Type.BOOL, ir.CompareKind.NE, e, ir.Const(Type.F64, 0.0))
        if e.type == Type.STR:      # AOT mode, tier S1: a str is true when it is not empty
            return ir.Compare(Type.BOOL, ir.CompareKind.NE, ir.StrLen(Type.I64, e), ir.Const(Type.I64, 0))
        if e.type == Type.STR_LIST:
            return ir.Compare(Type.BOOL, ir.CompareKind.NE, ir.StrListLen(Type.I64, e), ir.Const(Type.I64, 0))
        if e.type == Type.STR_OPT:      # tier S2a: `if x:` is `x is not None and len(x) != 0`
            return ir.And(Type.BOOL, ir.Is(Type.BOOL, e, ir.Const(Type.OBJ, None), True),
                          ir.Compare(Type.BOOL, ir.CompareKind.NE, ir.StrLen(Type.I64, ir.StrNarrow(Type.STR, e)),
                                     ir.Const(Type.I64, 0)))
        if e.type == Type.INT:
            return ir.Compare(Type.BOOL, ir.CompareKind.NE, e,
                              ir.ToInt(Type.INT, ir.Const(Type.I64, 0)))
        if e.type == Type.OBJ:
            self.impure = True
            return ir.Truth(Type.BOOL, e)
        raise Skip(node.lineno, f"truth value of `{_src(node)}` ({TYPE_NAME[e.type]})")

    def expr(self, node: ast.expr, cond: int = 0) -> ir.Expr:
        """`cond`: 2 when `node` is itself an If/While condition, 1 when it is an operand of an
        And/Or that is the condition, 0 elsewhere (ir.CompareObj is allowed only at 2 and 1)."""
        line = node.lineno
        if isinstance(node, ast.Constant):
            v = node.value
            if isinstance(v, bool):
                return ir.Const(Type.BOOL, v)
            if isinstance(v, int):
                if not I64_MIN <= v <= I64_MAX:
                    raise Skip(line, f"int literal {v} is outside i64")
                return ir.Const(Type.I64, v)
            if isinstance(v, float):
                return ir.Const(Type.F64, v)
            if v is None:
                return ir.Const(Type.OBJ, None)
            if isinstance(v, str) and self.info.aot:
                return ir.Const(Type.STR, v)        # tier S1: any text (the back end embeds its code points)
            if isinstance(v, str):
                return self.str_const(v, node)
            raise Skip(line, f"{type(v).__name__} constant `{_src(node)}` is not supported")
        if isinstance(node, ast.Name):
            return self.name(node)
        if isinstance(node, ast.BinOp):
            op = self.binop_kind(node.op, node)
            left = self.sub(node.left)
            right = self.sub(node.right)
            return self.arith(op, left, right, node)
        if isinstance(node, ast.UnaryOp):
            return self.unary(node)
        if isinstance(node, ast.Compare):
            return self.compare(node, cond)
        if isinstance(node, ast.BoolOp):
            return self.boolop(node, cond)
        if isinstance(node, ast.Call):
            return self.call(node)
        if isinstance(node, ast.Attribute):
            if isinstance(node.value, ast.Name) and node.value.id in self.arrays:
                raise self.array_misuse(node.value.id, node)
            if self.info.aot and isinstance(node.value, ast.Name) and node.value.id == "sys" \
                    and self.info.aot_sys and not self.is_local("sys"):
                if node.attr == "maxsize":
                    return ir.Const(Type.I64, I64_MAX)
                raise Skip(line, f"`{_src(node)}` is not supported in an AOT program (only `sys.argv`, `sys.maxsize` and "
                                 "`sys.exit`)")
            if self.info.aot and isinstance(node.value, ast.Name) and node.value.id == "math" \
                    and self.info.stdlib_math and not self.is_local("math") and node.attr in _MATH_CONSTS:
                return ir.Const(Type.F64, _MATH_CONSTS[node.attr])
            field_get = self.field_get(node)
            if field_get is not None:
                return field_get
            obj = self.boxed(self.sub(node.value), node.value)
            self.impure = True
            return ir.GetAttr(Type.OBJ, obj, node.attr)
        if isinstance(node, ast.Subscript):
            if isinstance(node.value, ast.Name) and node.value.id in self.arrays:
                if isinstance(node.slice, ast.Slice):
                    raise Skip(line, f"`{_src(node)}` slices {self.array_word(node.value.id)} "
                                     f"`{node.value.id}`: only `y = {node.value.id}[:]` as the whole "
                                     "right-hand side of an assignment is a native copy")
                array, elem = self.array_of(node)
                return ir.Index(elem, array, self.index(node.slice))
            if self.info.aot:
                return self.aot_subscript(node)
            if isinstance(node.slice, ast.Slice):
                raise Skip(line, f"slice `{_src(node)}` is not supported")
            obj = self.boxed(self.sub(node.value), node.value)      # the container, then the index
            key = self.boxed(self.sub(node.slice), node.slice)
            self.impure = True
            return ir.GetItem(Type.OBJ, obj, key)
        if isinstance(node, ast.Dict) and not node.keys:
            return ir.NewDict(Type.OBJ)
        if self.info.aot and isinstance(node, ast.List) and node.elts and isinstance(node.ctx, ast.Load) \
                and not any(isinstance(e, ast.Starred) for e in node.elts):
            elts = tuple(self.value(e) for e in node.elts)
            if all(e.type is Type.STR for e in elts):
                return ir.StrListDisplay(Type.STR_LIST, elts)      # tier S1b
            raise Skip(line, f"the list display `{_src(node)}` is not a list of str; a native list of floats or ints "
                             "is created by assigning it to a list name")
        if isinstance(node, ast.List) and not node.elts and isinstance(node.ctx, ast.Load):
            return ir.NewList(Type.OBJ)
        if isinstance(node, ast.JoinedStr):
            if self.info.aot:
                return self.aot_fstring(node, value=True)
            return self.fstring(node)
        if isinstance(node, ast.IfExp) and self.info.aot:
            return self.aot_ifexp(node)
        raise _unsupported(node)

    def str_const(self, v: str, node: ast.AST) -> ir.Expr:
        """A `str` constant: the text must be exactly the UTF-8 C string the back end embeds."""
        try:
            v.encode("utf-8")
        except UnicodeEncodeError:
            raise Skip(node.lineno, "a str constant with a lone surrogate is not supported") from None
        if "\0" in v:
            raise Skip(node.lineno, "a str constant containing NUL is not supported")
        return ir.Const(Type.OBJ, v)

    def fstring(self, node: ast.JoinedStr) -> ir.Expr:
        """BUILD_STRING of the constant pieces and the `Format` of each field, in source order."""
        parts: list[ir.Expr] = []
        for piece in node.values:
            if isinstance(piece, ast.Constant) and isinstance(piece.value, str):
                parts.append(self.str_const(piece.value, piece))
                continue
            assert isinstance(piece, ast.FormattedValue)
            spec = None
            if piece.format_spec is not None:
                texts = piece.format_spec.values  # type: ignore[attr-defined]
                if not all(isinstance(t, ast.Constant) and isinstance(t.value, str) for t in texts):
                    raise Skip(piece.lineno, f"format spec of `{_src(piece)}` has a nested field "
                                             "(only a constant spec is supported)")
                spec = "".join(t.value for t in texts)  # type: ignore[attr-defined]
                self.str_const(spec, piece)
            operand = self.boxed(self.value(piece.value), piece.value)
            self.impure = True
            parts.append(ir.Format(Type.OBJ, operand, spec, piece.conversion))
        if not parts:
            return ir.Const(Type.OBJ, "")
        if len(parts) == 1:
            return parts[0]            # the compiler emits no BUILD_STRING for one piece
        return ir.BuildString(Type.OBJ, tuple(parts))

    def name(self, node: ast.Name) -> ir.Expr:
        name = node.id
        if name in self.arrays:
            raise self.array_misuse(name, node)
        if self.is_local(name):
            if name not in self.params and name not in self.temps and all(
                    b.how == "annotation" for b in self.bindings[name]):
                raise Skip(node.lineno, f"`{name}` is read but never assigned")
            t = self.local_type(name, node.lineno)
            if t is Type.STR_OPT and name in self.not_none:
                return ir.StrNarrow(Type.STR, ir.Local(t, name))       # tier S2a: proved not None here
            return ir.Local(t, name)
        if name in self.info.aot_consts:
            return self.info.aot_consts[name]       # AOT mode: a typed constant, never an object read
        if name in self.candidates and self.direct_write:
            # #255 (d2): read at this use, as LOAD_GLOBAL does; a missing name or a value of another
            # type leaves through the statement exit (ir.direct_writer), so this is not a deopt
            return ir.Unbox(self.candidates[name], ir.Global(Type.OBJ, name))
        if name in self.candidates:
            self.entry_used.setdefault(name, self.candidates[name])
            return ir.Local(self.candidates[name], name)
        return ir.Global(Type.OBJ, name)

    def array_misuse(self, name: str, node: ast.AST) -> Skip:
        where = self.current if isinstance(self.current, (
            ast.Assign, ast.AnnAssign, ast.AugAssign, ast.Return, ast.Expr)) else node
        if self.info.aot:
            return Skip(node.lineno, f"the list `{name}` is used other than by indexing, len(), as a `[:]` source, as "
                                     f"an argument of a program function, as a return value or by binding it to a "
                                     f"name (in `{_src(where)}`): the AOT mode has no comparison, printing, "
                                     "slicing or method of a list yet")
        if name in self.local_arrays:
            return Skip(node.lineno, f"local array `{name}` is used other than by indexing, len() "
                                     f"or as a `[:]` source (in `{_src(where)}`)")
        return Skip(node.lineno, f"array parameter `{name}` is used other than by indexing or "
                                 f"len() (in `{_src(where)}`)")

    def array_of(self, node: ast.Subscript) -> tuple[str, Type]:
        if not (isinstance(node.value, ast.Name) and node.value.id in self.arrays):
            raise Skip(node.lineno, f"subscript `{_src(node)}` of an object is not supported")
        name = node.value.id
        return name, {Type.F64_ARRAY: Type.F64, Type.BOOL_ARRAY: Type.BOOL}.get(self.arrays[name], Type.I64)

    def index(self, node: ast.expr) -> ir.Expr:
        if isinstance(node, ast.Slice):
            raise _unsupported(node)
        e = self.value(node)
        if e.type != Type.I64:
            why = (" (it is a CPython int here: its arithmetic is not proved free of i64 overflow "
                   "in an impure function)" if e.type in (Type.OBJ, Type.INT) and self.impure_mode else "")
            raise Skip(node.lineno, f"index `{_src(node)}` is {TYPE_NAME[e.type]}, not a native "
                                    f"int{why}")
        return e

    def binop_kind(self, op: ast.operator, node: ast.AST) -> ir.BinOpKind:
        if isinstance(op, ast.Pow) and self.info.aot:
            return ir.BinOpKind.POW
        kind = BINOP.get(type(op))
        if kind is None:
            raise Skip(node.lineno, f"operator `{_src(node)}` is not supported")
        return kind

    def to_int(self, e: ir.Expr) -> ir.Expr:
        """An I64 widened to an INT (SPEC N-13); an INT as it is."""
        return ir.ToInt(Type.INT, e) if e.type == Type.I64 else e

    def arith(self, op: ir.BinOpKind, left: ir.Expr, right: ir.Expr, node: ast.AST,
              inplace: bool = False) -> ir.Expr:
        if self.info.aot and Type.STR in (left.type, right.type):
            return self.str_arith(op, left, right, node)
        e = self._arith(op, left, right, node, inplace)
        if isinstance(e, ir.BinOp) and e.type in ir.SCALARS and self.numpy:
            e = dataclasses.replace(e, line=node.lineno)      # the warning site (#220)
        return e

    def _arith(self, op: ir.BinOpKind, left: ir.Expr, right: ir.Expr, node: ast.AST,
               inplace: bool = False) -> ir.Expr:
        lt, rt = left.type, right.type
        if self.info.aot and Type.BOOL in (lt, rt) and Type.NONE not in (lt, rt):
            if op in BITWISE and lt == Type.BOOL and rt == Type.BOOL:
                res = ir.BinOp(Type.I64, op, ir.BoolToInt(Type.I64, left), ir.BoolToInt(Type.I64, right))
                return ir.Compare(Type.BOOL, ir.CompareKind.NE, res, ir.Const(Type.I64, 0))
            if op in BITWISE or op in (ir.BinOpKind.LSHIFT, ir.BinOpKind.RSHIFT):
                if lt == Type.BOOL:
                    left = ir.BoolToInt(Type.I64, left)
                    lt = Type.I64
                if rt == Type.BOOL:
                    right = ir.BoolToInt(Type.I64, right)
                    rt = Type.I64
        if Type.BOOL in (lt, rt) or Type.NONE in (lt, rt):
            raise Skip(node.lineno, f"arithmetic on {TYPE_NAME[Type.BOOL if Type.BOOL in (lt, rt) else Type.NONE]} "
                                    f"in `{_src(node)}` is not supported")
        if Type.OBJ in (lt, rt):
            return self.obj_binop(op, left, right, inplace)
        shift = op in (ir.BinOpKind.LSHIFT, ir.BinOpKind.RSHIFT)
        if (shift or op in BITWISE) and Type.F64 in (lt, rt):
            return self.obj_binop(op, left, right, inplace)         # CPython's TypeError
        if Type.INT in (lt, rt):
            if Type.F64 in (lt, rt) or op == ir.BinOpKind.TRUEDIV:
                return self.obj_binop(op, left, right, inplace)     # a float result: the object operation
            return ir.BinOp(Type.INT, op, self.to_int(left), self.to_int(right))
        if op in BITWISE:       # SPEC N-16: native in range, never an overflow, never a deopt
            return ir.BinOp(Type.I64, op, left, right)
        if shift:
            if self.impure_mode:        # promotes instead of deopting (SPEC N-13)
                return ir.BinOp(Type.INT, op, self.to_int(left), self.to_int(right))
            self.deopts = True          # SPEC N-16: an overflow or a negative count deopts
            return ir.BinOp(Type.I64, op, left, right)
        if op == ir.BinOpKind.POW:
            if lt == Type.I64 and rt == Type.I64:
                if isinstance(right, ir.Const) and isinstance(right.value, int) and right.value < 0:
                    return ir.BinOp(Type.F64, op, ir.ToFloat(Type.F64, left), ir.ToFloat(Type.F64, right))
                self.deopts = True
                return ir.BinOp(Type.I64, op, left, right)
            if lt == Type.I64:
                left = ir.ToFloat(Type.F64, left)
            if rt == Type.I64:
                right = ir.ToFloat(Type.F64, right)
            return ir.BinOp(Type.F64, op, left, right)
        if lt == Type.I64 and rt == Type.I64:
            result = Type.F64 if op == ir.BinOpKind.TRUEDIV else Type.I64
            e = ir.BinOp(result, op, left, right)
            if self.recordable(e) and self.proved(e):
                return dataclasses.replace(e, proven=True)
            if self.impure_mode:
                if op == ir.BinOpKind.TRUEDIV:
                    return self.obj_binop(op, left, right, inplace)
                # not proved free of overflow, and an impure function cannot deopt: an INT, native
                # until an operation leaves i64, then a PyLong, with CPython's results (SPEC N-13)
                return ir.BinOp(Type.INT, op, self.to_int(left), self.to_int(right))
            self.deopts = True
            return e
        if lt == Type.I64:
            left = ir.ToFloat(Type.F64, left)
        if rt == Type.I64:
            right = ir.ToFloat(Type.F64, right)
        return ir.BinOp(Type.F64, op, left, right)

    def obj_binop(self, op: ir.BinOpKind, left: ir.Expr, right: ir.Expr,
                  inplace: bool = False) -> ir.Expr:
        self.impure = True
        return ir.BinOp(Type.OBJ, op, _box(left), _box(right), inplace=inplace)

    def unary(self, node: ast.UnaryOp) -> ir.Expr:
        if isinstance(node.op, ast.Not):
            operand = self.truth(self.sub(node.operand), node.operand)
            return ir.UnaryOp(Type.BOOL, ir.UnaryOpKind.NOT, operand)
        if not isinstance(node.op, (ast.USub, ast.UAdd, ast.Invert)):
            raise Skip(node.lineno, f"operator `{_src(node)}` is not supported")
        operand = self.sub(node.operand)
        if isinstance(node.op, ast.Invert):       # SPEC N-16
            if operand.type == Type.INT:
                return ir.UnaryOp(Type.INT, ir.UnaryOpKind.INVERT, operand)
            if operand.type == Type.I64:
                if isinstance(operand, ir.Const):
                    return ir.Const(Type.I64, ~operand.value)
                return ir.UnaryOp(Type.I64, ir.UnaryOpKind.INVERT, operand)
            if self.info.aot and operand.type == Type.BOOL:
                return ir.UnaryOp(Type.I64, ir.UnaryOpKind.INVERT, ir.BoolToInt(Type.I64, operand))
            raise Skip(node.lineno, f"unary `{_src(node)}` on {TYPE_NAME[operand.type]} is not "
                                    "supported")
        kind = ir.UnaryOpKind.NEG if isinstance(node.op, ast.USub) else ir.UnaryOpKind.POS
        if operand.type == Type.F64:
            return ir.UnaryOp(Type.F64, kind, operand)
        if operand.type == Type.INT:
            return ir.UnaryOp(Type.INT, kind, operand)
        if operand.type == Type.I64:
            if isinstance(operand, ir.Const) and I64_MIN <= -operand.value <= I64_MAX:
                # a negative literal is one constant, as CPython's compiler folds it (so a Box of it
                # is the module-state constant, SPEC N-13)
                return ir.Const(Type.I64, -operand.value if kind == ir.UnaryOpKind.NEG else operand.value)
            e = ir.UnaryOp(Type.I64, kind, operand)
            if kind == ir.UnaryOpKind.POS:
                return e
            if self.proved(e):
                return dataclasses.replace(e, proven=True)
            if self.impure_mode:  # -x of an i64 may leave i64: an INT (SPEC N-13)
                return ir.UnaryOp(Type.INT, kind, self.to_int(operand))
            self.deopts = True
            return e
        raise Skip(node.lineno, f"unary `{_src(node)}` on {TYPE_NAME[operand.type]} is not "
                                "supported")

    def compare(self, node: ast.Compare, cond: int) -> ir.Expr:
        # A chain is an And of its links: as a condition (2) that And is the condition; as an
        # operand of the condition's And/Or (1) it would be nested, so only a single link there.
        obj_ok = cond == 2 or (cond == 1 and len(node.ops) == 1)
        if self.info.aot and len(node.ops) == 1 and isinstance(node.ops[0], (ast.In, ast.NotIn)) \
                and isinstance(node.comparators[0], ast.Name) and self.is_list_name(node.comparators[0].id):
            # tier S2a: `v in a` / `v not in a` of a list[int] / list[str] (a list of float: aot/identity)
            name = node.comparators[0].id
            found: ir.Expr = ir.ListSearch(Type.BOOL, "in", name, self.list_search_value(name, node.left, node))
            if isinstance(node.ops[0], ast.NotIn):
                found = ir.UnaryOp(Type.BOOL, ir.UnaryOpKind.NOT, found)
            return found
        left = self.sub(node.left)
        links: list[ir.Expr] = []
        last = len(node.ops) - 1
        for i, (op, comparator) in enumerate(zip(node.ops, node.comparators)):
            if i > 0:
                self.hoist_ok = False  # later links run only if the earlier ones held
            hoist_ok = self.hoist_ok
            right = self.expr(comparator)
            if right.type == Type.NONE:
                raise Skip(comparator.lineno, f"uses the result of `{_src(comparator)}`, which "
                                              "returns None")
            if i < last and not isinstance(right, (ir.Local, ir.Const)):
                # The middle operand of a chain is evaluated once: bind it to a temporary.
                if hoist_ok:
                    right = self.temp(right)
                elif not _trivial(right):
                    raise Skip(comparator.lineno, f"chained comparison: `{_src(comparator)}` "
                                                  "cannot be evaluated once here")
            if not _trivial(right):
                self.hoist_ok = False
            links.append(self.link(op, left, right, obj_ok, node))
            left = right
        result = links[0]
        for link in links[1:]:
            result = ir.And(Type.BOOL, result, link)
        return result

    def link(self, op: ast.cmpop, left: ir.Expr, right: ir.Expr, obj_ok: bool,
             node: ast.Compare) -> ir.Expr:
        if self.info.aot:
            left, right = _unnarrow_against_none(left, right), _unnarrow_against_none(right, left)
        if self.info.aot and Type.STR_OPT in (left.type, right.type):
            return self.opt_link(op, left, right, node)
        if self.info.aot and Type.STR in (left.type, right.type):
            return self.str_link(op, left, right, node)
        if isinstance(op, (ast.Is, ast.IsNot)):
            if left.type != Type.OBJ or right.type != Type.OBJ:
                raise Skip(node.lineno, f"identity comparison `{_src(node)}` of a native "
                                        f"{TYPE_NAME[left.type if left.type != Type.OBJ else right.type]}"
                                        " is not supported (only of objects)")
            return ir.Is(Type.BOOL, left, right, isinstance(op, ast.IsNot))
        if isinstance(op, (ast.In, ast.NotIn)):
            self.impure = True
            return ir.Contains(Type.BOOL, _box(left), _box(right), isinstance(op, ast.NotIn))
        kind = COMPARE.get(type(op))
        if kind is None:
            raise Skip(node.lineno, f"comparison `{_src(node)}` is not supported "
                                    "(only == != < <= > >=, in, not in, is, is not)")
        lt, rt = left.type, right.type
        if Type.INT in (lt, rt) and Type.OBJ not in (lt, rt) and Type.F64 not in (lt, rt) \
                and Type.BOOL not in (lt, rt):
            return ir.Compare(Type.BOOL, kind, self.to_int(left), self.to_int(right))
        if Type.INT in (lt, rt) and Type.OBJ not in (lt, rt) and Type.BOOL not in (lt, rt):
            lt = rt = Type.OBJ              # an int against a float: compared as objects, like the interpreter
            left, right = _box(left), _box(right)
        if Type.OBJ in (lt, rt):
            if not obj_ok:
                raise Skip(node.lineno, f"comparison `{_src(node)}` with an object is not "
                                        "directly a condition (its result need not be a bool)")
            self.impure = True
            return ir.CompareObj(Type.BOOL, kind, _box(left), _box(right))
        if (lt == Type.BOOL) != (rt == Type.BOOL):
            raise Skip(node.lineno, f"comparison `{_src(node)}` of a bool with a number is not "
                                    "supported")
        return ir.Compare(Type.BOOL, kind, left, right)

    def boolop(self, node: ast.BoolOp, cond: int) -> ir.Expr:
        word = "and" if isinstance(node.op, ast.And) else "or"
        operands = []
        nn_entry = set(self.not_none)
        for i, v in enumerate(node.values):
            if i > 0:
                self.hoist_ok = False  # evaluated only if the earlier operands did not decide
            e = self.sub(v, 1 if cond else 0)
            if cond:
                e = self.truth(e, v)
            elif e.type != Type.BOOL:
                raise Skip(node.lineno, f"`{word}` on a non-bool operand `{_src(v)}` outside a "
                                        "condition (it returns the operand)")
            operands.append(e)
            # tier S2a: `x is not None and len(x) > 0` (the later operands run only when the earlier decided so)
            self.not_none |= _none_facts(e, word == "and")
        self.not_none = nn_entry
        result = operands[0]
        for e in operands[1:]:
            result = (ir.And if word == "and" else ir.Or)(Type.BOOL, result, e)
        return result

    def builtin(self, name: str) -> bool:
        return not self.info.bound(name) and not self.is_local(name)

    def call(self, node: ast.Call) -> ir.Expr:
        line = node.lineno
        if any(isinstance(a, ast.Starred) for a in node.args) or any(
                k.arg is None for k in node.keywords):
            raise Skip(line, f"`*`/`**` arguments in `{_src(node)}` are not supported")
        func = node.func
        plain = not node.keywords

        if isinstance(func, ast.Name):
            name = func.id
            if name in self.arrays:
                raise self.array_misuse(name, node)
            if plain and len(node.args) == 1 and name == "len" and self.builtin("len") \
                    and isinstance(node.args[0], ast.Name) and node.args[0].id in self.arrays:
                return ir.Len(Type.I64, node.args[0].id)
            if self.info.aot and plain:
                found = self.aot_builtin_call(name, node)
                if found is not None:
                    return found
            if plain and len(node.args) == 1 and name == "float" and self.builtin("float"):
                arg = self.value(node.args[0])
                if self.numpy and arg.type in (Type.I64, Type.F64):
                    # #220: float(np.float64) / float(np.int64) is a plain float
                    return ir.ToFloat(Type.F64, arg, explicit=True)
                if arg.type == Type.I64:
                    return ir.ToFloat(Type.F64, arg)
                if arg.type == Type.F64:
                    return arg  # float(x) of an exact float is x itself
                self.impure = True
                return ir.ObjToFloat(Type.F64, _box(arg))
            new_cls = self.new_class(node)
            if new_cls is not None:
                self.hoist_ok = False
                if self.info.aot:
                    new_args = []
                    for a in node.args:
                        if isinstance(a, ast.Name) and a.id in self.arrays:
                            new_args.append(ir.Local(self.arrays[a.id], a.id))
                        else:
                            new_args.append(self.value(a))
                    return ir.New(Type.OBJ, new_cls, tuple(new_args))
                return ir.New(Type.OBJ, new_cls,
                              tuple(self.boxed(self.value(a), a) for a in node.args))
            if not self.is_local(name) and name in self.env:
                direct = self.direct_call(name, node)
                if direct is not None:
                    return direct

        if isinstance(func, ast.Attribute) and isinstance(func.value, ast.Name) \
                and func.value.id == "math" and self.info.stdlib_math \
                and not self.is_local("math") and plain:
            math = self.math_call(func.attr, node)
            if math is not None:
                return math

        if self.info.aot and isinstance(func, ast.Attribute) and isinstance(func.value, ast.Name) \
                and self.is_list_name(func.value.id) and func.attr in LIST_METHOD_NAMES:
            return self.list_method_expr(func, node)                     # tier S2a

        if self.info.aot and isinstance(func, ast.Attribute):
            write = self.std_write(func, node)
            if write is not None:
                return write
            if func.attr in STR_METHODS and self.str_receiver(func.value):
                return self.str_method(func, node)

        if self.info.aot and isinstance(func, ast.Attribute) and isinstance(func.value, ast.Name) \
                and func.value.id == "time" and self.info.aot_time \
                and not self.is_local("time") and plain:
            if func.attr in ("perf_counter", "monotonic", "time") and not node.args:
                return ir.TimeCall(Type.F64, ir.TimeFunc(func.attr))

        if isinstance(func, ast.Attribute) and plain:
            direct = self.method_call(func, node)
            if direct is not None:
                return direct

        if isinstance(func, ast.Name) and func.id in self.arrays:
            raise self.array_misuse(func.id, node)
        callee = self.boxed(self.sub(func), func)
        self.hoist_ok = False
        args = [self.boxed(self.value(a), a) for a in node.args]
        args += [self.boxed(self.value(k.value), k.value) for k in node.keywords]
        self.impure = True
        return ir.CallObject(Type.OBJ, callee, tuple(args),
                             tuple(k.arg for k in node.keywords if k.arg is not None))

    def direct_call(self, name: str, node: ast.Call, tuple_ok: bool = False) -> ir.Expr | None:
        """`Call` of another compiled function, else `CallObject` of its module global; None when
        the generic path should lower the call (decided before any argument is lowered)."""
        callee = self.env[name]
        if node.keywords or len(node.args) != len(callee.params):
            return None  # CPython's own TypeError, through the interpreted path
        if self.info.aot:
            return self.aot_call(name, node, callee, tuple_ok)
        if any(isinstance(p, ir.ArrayParam) for p in callee.params):
            return None
        self.hoist_ok = False
        args = [self.value(a) for a in node.args]
        redo = callee.may_deopt and self.impure_mode and callee.pure \
            and callee.returns in (Type.F64, Type.BOOL, Type.NONE, Type.I64)
        # (an I64 result of a redo may be a big int: the call is then an INT, SPEC N-13)
        direct = redo or not (callee.may_deopt and self.impure_mode)
        for p, e in zip(callee.params, args):
            if p.type != Type.OBJ and e.type != p.type:
                if redo and p.type == Type.I64 and e.type == Type.INT:
                    continue    # a promoted INT does not fit: the callee's deopt, redone
                direct = False  # the callee's guard would deopt; the global keeps CPython's result
        # A class parameter's guard (`type(x) is C`) fails for an argument not proved to be a C:
        # that is a deopt of the callee at its entry, redoable only by a pure caller.
        unproved = any(isinstance(p, ir.Param) and p.cls is not None and not self.arg_proved(e, p)
                       for p, e in zip(callee.params, args))
        if unproved and self.impure_mode:
            direct = False
        if not direct:
            self.impure = True
            return ir.CallObject(Type.OBJ, ir.Global(Type.OBJ, name),
                                 tuple(self.boxed(e, a) for e, a in zip(args, node.args)))
        if not callee.pure:
            self.impure = True
        if (callee.may_deopt and not redo) or unproved:
            self.deopts = True
        typed = tuple(self.boxed(e, a) if p.type == Type.OBJ else e
                      for p, e, a in zip(callee.params, args, node.args))
        result = Type.INT if redo and callee.returns == Type.I64 else callee.returns
        return ir.Call(result, name, typed, redo=redo)

    def is_sys_argv(self, node: ast.expr) -> bool:
        return (isinstance(node, ast.Attribute) and node.attr == "argv" and isinstance(node.value, ast.Name)
                and node.value.id == "sys" and self.info.aot_sys and not self.is_local("sys"))

    def aot_builtin_call(self, name: str, node: ast.Call) -> ir.Expr | None:
        """The builtins of AOT mode that are not in the default mode: `len(sys.argv)`, `int(sys.argv[i])`,
        `abs(x)`, `min(a, b)` and `max(a, b)` (two numbers of one type). None: not one of these."""
        args = node.args
        line = node.lineno
        if name == "len" and self.builtin("len") and len(args) == 1 and self.is_sys_argv(args[0]):
            return ir.ArgvLen(Type.I64)
        found = self.aot_str_builtin(name, node)
        if found is not None:
            return found
        if name == "int" and self.builtin("int"):
            if len(args) == 1 and isinstance(args[0], ast.Subscript) and self.is_sys_argv(args[0].value) \
                    and not isinstance(args[0].slice, ast.Slice):
                lit = args[0].slice
                index = self.value(lit)
                if index.type != Type.I64:
                    raise Skip(line, f"the index `{_src(lit)}` of `sys.argv` is {TYPE_NAME[index.type]}, not a native "
                                     "int")
                return ir.ArgvInt(Type.I64, index)
            if len(args) == 1 and isinstance(args[0], ast.Constant) and isinstance(args[0].value, str):
                return ir.StrToInt(Type.I64, args[0].value)
            if len(args) == 1:
                x = self.value(args[0])
                if x.type == Type.STR:
                    return ir.StrParseInt(Type.I64, x)
                if x.type == Type.I64:
                    return x
                if x.type == Type.F64:
                    return ir.FloatToInt(Type.I64, x)
                if x.type == Type.BOOL:
                    return ir.BoolToInt(Type.I64, x)
                raise Skip(line, f"int() of a {TYPE_NAME[x.type]} is not supported in an AOT program")
            raise Skip(line, f"`{_src(node)}`: `int()` takes one argument in an AOT program")
        if name == "bool" and self.builtin("bool"):
            if len(args) != 1:
                raise Skip(line, "bool() takes one argument in an AOT program")
            if isinstance(args[0], ast.Name) and args[0].id in self.arrays:
                return ir.Compare(Type.BOOL, ir.CompareKind.NE, ir.Len(Type.I64, args[0].id), ir.Const(Type.I64, 0))
            x = self.value(args[0])
            return self.truth(x, args[0])
        if name == "abs" and self.builtin("abs"):
            if len(args) != 1:
                raise Skip(line, "abs() takes one argument")
            ok = self.hoist_ok
            x = self.value(args[0])
            if x.type == Type.F64:
                return ir.MathCall(Type.F64, ir.MathFunc.FABS, (x,))
            if x.type == Type.I64:
                x = self.once(x, args[0], ok)
                neg = ir.UnaryOp(Type.I64, ir.UnaryOpKind.NEG, x)
                c = ir.Compare(Type.BOOL, ir.CompareKind.LT, x, ir.Const(Type.I64, 0))
                # `-x` runs only where `x < 0`: prove it under that guard, as the verifier does
                entry = self.refined
                self.refined, dead = self.narrowed(c, True, entry)
                self.dead += dead
                if self.proved(neg):
                    neg = dataclasses.replace(neg, proven=True)
                else:
                    self.deopts = True          # as an unproved `-x` anywhere (`unary`): the back end reports it
                self.dead -= dead
                self.refined = entry
                return self.aot_cond(c, neg, x, node)
            raise Skip(line, f"abs() of a {TYPE_NAME[x.type]} is not supported in an AOT program (int and float only)")
        if name in ("min", "max") and self.builtin(name):
            if len(args) != 2:
                raise Skip(line, f"{name}() of other than two arguments is not supported in an AOT program")
            ok = self.hoist_ok
            a = self.once(self.value(args[0]), args[0], ok)
            b = self.once(self.value(args[1]), args[1], ok)
            if a.type == b.type == Type.STR:
                kind = ir.CompareKind.LT if name == "min" else ir.CompareKind.GT
                return self.aot_cond(ir.StrCompare(Type.BOOL, kind, b, a), b, a, node)
            if a.type != b.type or a.type not in (Type.I64, Type.F64):
                raise Skip(line, f"{name}() needs two ints or two floats in an AOT program, not "
                                 f"{TYPE_NAME[a.type]} and {TYPE_NAME[b.type]} (the result's type is that of the "
                                 "argument CPython picks)")
            # CPython: the first argument, replaced by the second when the second compares strictly smaller
            # (min) or larger (max); so a tie, and a NaN, keep the first
            kind = ir.CompareKind.LT if name == "min" else ir.CompareKind.GT
            return self.aot_cond(ir.Compare(Type.BOOL, kind, b, a), b, a, node)
        return None

    def once(self, e: ir.Expr, node: ast.AST, hoist_ok: bool) -> ir.Expr:
        """`e` where it is used twice: evaluated once, into a temporary when it is not trivial. `hoist_ok`
        is `self.hoist_ok` from before `e` was lowered (the statement's earlier operands were all trivial)."""
        if _trivial(e):
            return e
        if not hoist_ok:
            raise Skip(getattr(node, "lineno", 0), f"`{_src(node)}` cannot be held in a temporary here (part of the "
                                                   "statement before it, or a condition, must run first); bind it "
                                                   "to a name in a statement of its own")
        return self.temp(e)

    def aot_cond(self, cond: ir.Expr, then: ir.Expr, orelse: ir.Expr, node: ast.AST) -> ir.Expr:
        return ir.Cond(then.type, cond, then, orelse)

    def aot_ifexp(self, node: ast.IfExp) -> ir.Expr:
        """`a if c else b` of two scalars of one type (AOT mode). Each branch is lowered under the guard facts
        its test gives, as an `if` statement's branches are (the verifier does the same)."""
        cond = self.truth(self.sub(node.test), node.test)
        entry = self.refined
        nn_entry = set(self.not_none)
        self.hoist_ok = False                       # a branch runs only when its test says so
        self.refined, dead = self.narrowed(cond, True, entry)
        self.dead += dead
        self.not_none = nn_entry | _none_facts(cond, True)
        then = self.sub(node.body)
        self.dead -= dead
        self.refined, dead = self.narrowed(cond, False, entry)
        self.dead += dead
        self.not_none = nn_entry | _none_facts(cond, False)
        orelse = self.sub(node.orelse)
        self.dead -= dead
        self.refined = entry
        self.not_none = nn_entry
        if self.info.aot:                                   # tier S2a: a str or None branch makes a `str | None`
            none = (lambda e: isinstance(e, ir.Const) and e.value is None)
            if none(then) and orelse.type in (Type.STR, Type.STR_OPT):
                then = ir.Const(Type.STR_OPT, None)
            if none(orelse) and then.type in (Type.STR, Type.STR_OPT):
                orelse = ir.Const(Type.STR_OPT, None)
        if {then.type, orelse.type} <= {Type.STR, Type.STR_OPT} and then.type != orelse.type:
            return ir.Cond(Type.STR_OPT, cond, then, orelse)             # (the node's type is the wider one)
        if then.type != orelse.type or (then.type not in ir.SCALARS
                                        and then.type not in (Type.STR, Type.STR_OPT)):
            raise Skip(node.lineno, f"the branches of `{_src(node)}` are {TYPE_NAME[then.type]} and "
                                    f"{TYPE_NAME[orelse.type]}; an AOT program needs two ints, two floats, two "
                                    "bools or two strs")
        return ir.Cond(then.type, cond, then, orelse)

    def aot_call(self, name: str, node: ast.Call, callee: ir.Function, tuple_ok: bool) -> ir.Expr:
        """A call of a program function in AOT mode. A list argument is passed by name: the callee gets the
        caller's own list, not a copy. A scalar of another type than the parameter's is an object call (CPython
        passes it unconverted), which the back end reports. A function that returns a tuple is only called
        to be unpacked (`aot_unpack`)."""
        if name in self.info.aot_tuples and not tuple_ok:
            raise Skip(node.lineno, f"`{_src(node)}` returns a tuple, which an AOT program can only unpack into "
                                    f"names (`a, b = {name}(...)`)")
        self.hoist_ok = False
        args: list[ir.Expr] = []
        for p, a in zip(callee.params, node.args):
            if isinstance(p, ir.ArrayParam):
                if not (isinstance(a, ast.Name) and a.id in self.arrays):
                    raise Skip(a.lineno, f"the argument `{_src(a)}` of `{name}` must be the name of a list (parameter "
                                         f"`{p.name}` is {TYPE_NAME[p.type]}); bind it to a name first")
                if self.arrays[a.id] != p.type:
                    raise Skip(a.lineno, f"`{a.id}` is {TYPE_NAME[self.arrays[a.id]]}, but parameter `{p.name}` of "
                                         f"`{name}` is {TYPE_NAME[p.type]}")
                args.append(ir.Local(p.type, a.id))
            else:
                v = self.value(a)
                if isinstance(p, ir.Param) and p.type is Type.STR_LIST and isinstance(v, ir.NewList):
                    v = ir.StrListDisplay(Type.STR_LIST, ())        # tier S1b: `f([])` for a list[str] parameter
                if isinstance(p, ir.Param) and p.type is Type.STR_OPT:
                    v = self.coerce(v, Type.STR_OPT, a, f"parameter `{p.name}` of `{name}`")   # tier S2a
                args.append(v)
        direct = all(isinstance(p, ir.ArrayParam)
                     or (p.type != Type.OBJ and e.type == p.type)
                     or (p.type is Type.STR_OPT and e.type is Type.STR)         # tier S2a: a str is a `str | None`
                     or (isinstance(p, ir.Param) and p.cls is not None and e.type == Type.OBJ)
                     for p, e in zip(callee.params, args))
        if not direct and any(isinstance(p, ir.ArrayParam) for p in callee.params):
            raise Skip(node.lineno, f"the arguments of `{name}` do not match its parameter types, so the call is not a "
                                    "direct call (CPython passes them unconverted), and a list cannot be passed to an "
                                    "object call")
        if not direct:
            self.impure = True
            return ir.CallObject(Type.OBJ, ir.Global(Type.OBJ, name),
                                 tuple(self.boxed(e, a) for e, a in zip(args, node.args)))
        if not callee.pure:
            self.impure = True
        if callee.may_deopt:
            self.deopts = True
        return ir.Call(callee.returns, name, tuple(args))

    def method_call(self, func: ast.Attribute, node: ast.Call) -> ir.Expr | None:
        """`obj.m(args)` where `obj` is a local of compiled class C and `C.m` a compiled method: the
        direct C call `Call("C.m", (CheckMethod(obj, C, m), *args))` (ir.CheckMethod), only in a pure
        function and only to a pure callee, because the check is a deopt. None: the generic path
        (`CallObject(GetAttr(obj, m), args)`, CPython's own lookup, which finds the descriptor the
        module installed) lowers the call, decided before any argument is lowered. A callee with
        entry globals is left to the generic path too (its guard reads would need the caller's)."""
        if (self.impure_mode and not self.info.aot) \
                or not (isinstance(func.value, ast.Name) and self.is_local(func.value.id)):
            return None
        cls, _ = self.cls_of_name(func.value.id)
        callee = self.env.get(f"{cls}.{func.attr}") if cls is not None else None
        if callee is None or callee.owner != cls \
                or (not self.info.aot and (not callee.pure or callee.entry_globals)) \
                or len(node.args) != len(callee.params) - 1 \
                or (not self.info.aot and any(isinstance(p, ir.ArrayParam) for p in callee.params)) \
                or self.local_type(func.value.id, node.lineno) != Type.OBJ:
            return None
        assert cls is not None
        if self.info.aot:
            self.check_not_none(func.value.id, cls, func.value)
            recv: ir.Expr = ir.Local(Type.OBJ, func.value.id)
            self.hoist_ok = False
            args = [recv] + [self.value(a) for a in node.args]
            if not callee.pure:
                self.impure = True
            return ir.Call(callee.returns, f"{cls}.{func.attr}", tuple(args))
        recv = ir.CheckMethod(Type.OBJ, ir.Local(Type.OBJ, func.value.id), cls, func.attr)
        self.hoist_ok = False
        args = [recv] + [self.value(a) for a in node.args]
        for p, e in zip(callee.params, args):
            if p.type != Type.OBJ and e.type != p.type:
                # the callee's entry guard would deopt; the impure retry (which takes the generic
                # path) keeps CPython's result
                raise Skip(node.lineno, f"argument of type {TYPE_NAME[e.type]} for parameter "
                                        f"`{p.name}` of `{cls}.{func.attr}`")
        self.deopts = True      # the CheckMethod (and a class argument's guard) can deopt
        typed = tuple(self.boxed(e, a) if p.type == Type.OBJ else e
                      for p, e, a in zip(callee.params, args, [func] + list(node.args)))
        return ir.Call(callee.returns, f"{cls}.{func.attr}", typed)

    def aot_sys_exit(self, node: ast.Call) -> list[ir.Stmt]:
        line = node.lineno
        if not node.args:
            return [ir.SysExit(None)]
        if len(node.args) != 1:
            raise Skip(line, "sys.exit() takes at most one argument")
        arg = node.args[0]
        if isinstance(arg, ast.Constant) and arg.value is None:
            return [ir.SysExit(None)]
        val = self.value(arg)
        if val.type == Type.BOOL:
            val = ir.BoolToInt(Type.I64, val)
        if val.type != Type.I64:
            raise Skip(line, f"sys.exit() expects an int or None, not {TYPE_NAME[val.type]}")
        return self.take() + [ir.SysExit(val)]

    def aot_time_sleep(self, node: ast.Call) -> list[ir.Stmt]:
        line = node.lineno
        if len(node.args) != 1:
            raise Skip(line, "time.sleep() takes one argument")
        val = self.value(node.args[0])
        if val.type == Type.I64:
            val = ir.ToFloat(Type.F64, val)
        elif val.type == Type.BOOL:
            val = ir.ToFloat(Type.F64, ir.BoolToInt(Type.I64, val))
        if val.type != Type.F64:
            raise Skip(line, f"time.sleep() expects a float or int, not {TYPE_NAME[val.type]}")
        return self.take() + [ir.TimeSleep(val)]

    def math_call(self, attr: str, node: ast.Call) -> ir.Expr | None:
        try:
            func = ir.MathFunc(attr)
        except ValueError:
            return None
        if not self.info.aot and func not in _DEFAULT_MATH_FUNCS:
            return None
        if len(node.args) != MATH_ARITY[func]:
            return None
        self.hoist_ok = False
        args = [self.value(a) for a in node.args]
        if self.info.aot and func in _MATH_INT_FUNCS:
            if args[0].type == Type.I64:
                return args[0]
            if args[0].type == Type.F64:
                return ir.MathCall(Type.I64, func, (args[0],))
        if self.info.aot and func in _MATH_BOOL_FUNCS:
            if args[0].type in (Type.I64, Type.F64):
                farg = ir.ToFloat(Type.F64, args[0]) if args[0].type == Type.I64 else args[0]
                return ir.MathCall(Type.BOOL, func, (farg,))
        if all(e.type in (Type.I64, Type.F64) for e in args):
            return ir.MathCall(Type.F64, func, tuple(
                ir.ToFloat(Type.F64, e) if e.type == Type.I64 else e for e in args))
        # math's own conversion of an object (or a bool): call `math.<func>` as an object.
        self.impure = True
        callee = ir.GetAttr(Type.OBJ, ir.Global(Type.OBJ, "math"), attr)
        return ir.CallObject(Type.OBJ, callee, tuple(self.boxed(e, a)
                                                     for e, a in zip(args, node.args)))

    def boxed(self, e: ir.Expr, node: ast.AST) -> ir.Expr:
        if e.type == Type.NONE:
            raise Skip(node.lineno, f"uses the result of `{_src(node)}`, which returns None")
        return _box(e)

    def coerce(self, value: ir.Expr, target: Type, node: ast.AST, what: str) -> ir.Expr:
        if value.type == target:
            return value
        if target is Type.STR_OPT and self.info.aot and value.type is Type.STR:
            return value                                        # tier S2a: a str is a `str | None`
        if target is Type.STR_OPT and self.info.aot and isinstance(value, ir.Const) and value.value is None:
            return ir.Const(Type.STR_OPT, None)
        if target is Type.STR and self.info.aot and value.type is Type.STR_OPT:
            raise _optional(getattr(node, "lineno", 0), node)            # tier S2a
        if target is Type.STR_LIST and isinstance(value, ir.NewList) and self.info.aot:
            return ir.StrListDisplay(Type.STR_LIST, ())          # tier S1b: `x: list[str] = []`
        if target == Type.OBJ and (value.type in ir.SCALARS or value.type == Type.INT):
            return ir.Box(Type.OBJ, value)
        if target == Type.INT and value.type == Type.I64:
            return ir.ToInt(Type.INT, value)
        extra = ""
        if value.type == Type.INT:
            extra = " (it may be an int beyond i64 here: it cannot be a native i64)"
        elif value.type == Type.OBJ:
            extra = " (CPython would keep the object as it is; there is no conversion to make)"
        elif value.type == Type.I64 and target == Type.F64:
            extra = " (CPython keeps an int there, it does not convert it to float)"
        raise Skip(getattr(node, "lineno", 0),
                   f"`{_src(node)}` is {TYPE_NAME[value.type]}, but {what} is "
                   f"{TYPE_NAME[target]}{extra}")

    # --- intervals ---

    def interval(self, e: ir.Expr) -> Interval:
        if isinstance(e, ir.Const) and e.type == Type.I64:
            return (e.value, e.value)  # type: ignore[return-value]
        if isinstance(e, ir.Local) and e.type == Type.I64:
            base = (self.bounds.get(e.name) if e.name in self.range_vars or e.name in self.acc_vars
                    else TOP)
            if base is None:
                return None
            return _meet(base, self.refined.get(e.name))
        if isinstance(e, ir.Len):
            return (0, LEN_MAX)
        if isinstance(e, ir.Cond) and e.type == Type.I64:      # AOT mode: either branch
            return _join(self.interval(e.then), self.interval(e.orelse))
        if isinstance(e, (ir.BinOp, ir.UnaryOp)) and e.type == Type.I64:
            if isinstance(e, ir.BinOp) and e.op in (ir.BinOpKind.FLOORDIV, ir.BinOpKind.MOD):
                a, b = self.interval(e.left), self.interval(e.right)
                if a is None or b is None:
                    return None
                return _clamp(_div_interval(e.op, a, b))
            if isinstance(e, ir.BinOp) and e.op in (ir.BinOpKind.TRUEDIV, ir.BinOpKind.LSHIFT,
                                                    ir.BinOpKind.RSHIFT, ir.BinOpKind.OR,
                                                    ir.BinOpKind.XOR):
                return TOP
            # ADD/SUB/MUL/NEG/POS: an op that overflows deopts, so only in-range results flow on
            r = self._result_interval(e)
            return None if r is None else _clamp(r)
        return TOP

    def note_assign(self, name: str, value: ir.Expr) -> None:
        """`name = value` was lowered: record the value's interval for the local (acc_vars) and end
        any guard on it."""
        if name in self.acc_vars and not self.dead and value.type == Type.I64:
            iv = self.interval(value)
            if iv is not None:
                self.found_bounds[name] = _join(self.found_bounds.get(name), iv)
        self.refined.pop(name, None)
        self.not_none.discard(name)
        if value.type is Type.STR or isinstance(value, ir.New) \
                or (isinstance(value, ir.Local) and value.name in self.not_none):
            self.not_none.add(name)                 # tier S2a: a str, a new object or a proved name is not None

    def narrowed(self, cond: ir.Expr, truth: bool, base: dict[str, tuple[int, int]]
                 ) -> tuple[dict[str, tuple[int, int]], bool]:
        """(`base` narrowed by `cond` being `truth`, whether a bound read a local with no interval
        yet). Same rules as verify._Checker.refinements."""
        self.refined = base
        found, bottom = {}, False
        stack = [(cond, truth)]
        while stack:
            c, t = stack.pop()
            if isinstance(c, ir.UnaryOp) and c.op == ir.UnaryOpKind.NOT:
                stack.append((c.operand, not t))
            elif isinstance(c, (ir.And, ir.Or)) and (isinstance(c, ir.And) == t):
                stack += [(c.left, t), (c.right, t)]
            elif isinstance(c, ir.Compare) and c.left.type == Type.I64 and c.right.type == Type.I64:
                kind = c.op if t else _NEGATE[c.op]
                for local, other, k in ((c.left, c.right, kind), (c.right, c.left, _SWAP[kind])):
                    if isinstance(local, ir.Local):
                        iv = self.interval(other)
                        if iv is None:
                            bottom = True
                        else:
                            _narrow(found, local.name, _bound(k, iv))
        out = dict(base)
        for name, iv in found.items():
            _narrow(out, name, iv)
        return out, bottom

    def recordable(self, e: ir.BinOp) -> bool:
        """`proven` exists for ADD/SUB/MUL, and for FLOORDIV/MOD by a Const power of two >= 1 of a
        value known to be >= 0 (the back end then emits a shift / mask); the verifier re-proves
        both and cannot re-prove anything else."""
        if e.op in (ir.BinOpKind.ADD, ir.BinOpKind.SUB, ir.BinOpKind.MUL):
            return True
        if e.op in (ir.BinOpKind.FLOORDIV, ir.BinOpKind.MOD):
            d = e.right.value if isinstance(e.right, ir.Const) else None
            a = self.interval(e.left)
            return (type(d) is int and d >= 1 and d & (d - 1) == 0
                    and a is not None and a[0] >= 0)
        return False

    def proved(self, e: ir.BinOp | ir.UnaryOp) -> bool:
        """The i64 operation provably does not deopt (no overflow, exact division)."""
        if isinstance(e, ir.UnaryOp):
            if e.op == ir.UnaryOpKind.POS:
                return True
            a = self.interval(e.operand)
            return a is None or a[0] > I64_MIN
        a, b = self.interval(e.left), self.interval(e.right)
        if a is None or b is None:
            return True  # no value reaches here (yet)
        if e.op == ir.BinOpKind.TRUEDIV:
            return all(-EXACT_INT_FLOAT <= v <= EXACT_INT_FLOAT for v in (*a, *b))
        if e.op == ir.BinOpKind.MOD:
            return True  # the runtime computes -2**63 % -1 == 0 (ir.BinOp); only a raise remains
        if e.op == ir.BinOpKind.FLOORDIV:
            return not (a[0] == I64_MIN and b[0] <= -1 <= b[1])
        r = self._result_interval(e)
        return r is None or (r[0] >= I64_MIN and r[1] <= I64_MAX)

    def _result_interval(self, e: ir.BinOp | ir.UnaryOp) -> Interval:
        if isinstance(e, ir.UnaryOp):
            a = self.interval(e.operand)
            if a is None:
                return None
            if e.op == ir.UnaryOpKind.INVERT:
                return (~a[1], ~a[0])
            return a if e.op == ir.UnaryOpKind.POS else (-a[1], -a[0])
        a, b = self.interval(e.left), self.interval(e.right)
        if a is None or b is None:
            return None
        if e.op == ir.BinOpKind.ADD:
            return (a[0] + b[0], a[1] + b[1])
        if e.op == ir.BinOpKind.SUB:
            return (a[0] - b[1], a[1] - b[0])
        if e.op == ir.BinOpKind.MUL:
            products = [x * y for x in a for y in b]
            return (min(products), max(products))
        if e.op == ir.BinOpKind.AND:    # a non-negative operand bounds the result to [0, its max]
            his = [x[1] for x in (a, b) if x[0] >= 0]
            return (0, min(his)) if his else TOP
        return TOP


# --- helpers -------------------------------------------------------------------------------------

_NEGATE = {ir.CompareKind.EQ: ir.CompareKind.NE, ir.CompareKind.NE: ir.CompareKind.EQ,
           ir.CompareKind.LT: ir.CompareKind.GE, ir.CompareKind.GE: ir.CompareKind.LT,
           ir.CompareKind.LE: ir.CompareKind.GT, ir.CompareKind.GT: ir.CompareKind.LE}
_SWAP = {ir.CompareKind.EQ: ir.CompareKind.EQ, ir.CompareKind.NE: ir.CompareKind.NE,
         ir.CompareKind.LT: ir.CompareKind.GT, ir.CompareKind.GT: ir.CompareKind.LT,
         ir.CompareKind.LE: ir.CompareKind.GE, ir.CompareKind.GE: ir.CompareKind.LE}


def _clamp(iv: tuple[int, int]) -> tuple[int, int]:
    return (max(iv[0], I64_MIN), min(iv[1], I64_MAX))


def _meet(a: tuple[int, int], b: tuple[int, int] | None) -> tuple[int, int]:
    """`a` narrowed by `b`; an empty meet (unreachable code) leaves `a`, which is still sound."""
    if b is None:
        return a
    lo, hi = max(a[0], b[0]), min(a[1], b[1])
    return (lo, hi) if lo <= hi else a


def _narrow(facts: dict[str, tuple[int, int]], name: str, iv: tuple[int, int] | None) -> None:
    if iv is not None:
        cur = facts.get(name)
        facts[name] = iv if cur is None else _meet(cur, iv)


def _bound(kind: ir.CompareKind, other: tuple[int, int]) -> tuple[int, int] | None:
    """The interval of `x` when `x <kind> e` is true and `e` lies in `other` (verify._bound)."""
    lo, hi = other
    K = ir.CompareKind
    out = {K.LT: (I64_MIN, hi - 1), K.LE: (I64_MIN, hi), K.GT: (lo + 1, I64_MAX),
           K.GE: (lo, I64_MAX), K.EQ: (lo, hi)}.get(kind)
    return out if out is not None and out[0] <= out[1] else None


def _div_interval(op: ir.BinOpKind, a: tuple[int, int], b: tuple[int, int]) -> tuple[int, int]:
    """Result interval of I64 FLOORDIV / MOD (verify._div_interval)."""
    if op == ir.BinOpKind.MOD:
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


def _stored_names(stmts: Iterable[ast.stmt]) -> set[str]:
    """Every name stored (assigned, augmented, loop target) anywhere in `stmts`."""
    return {n.id for st in stmts for n in ast.walk(st)
            if isinstance(n, ast.Name) and isinstance(n.ctx, ast.Store)}


def _at(s: ir.Stmt, k: int | None) -> ir.Stmt:
    """`s` with source statement number `k` on it and on each statement of its body that has none
    yet (a statement lowered from a nested source statement already has its own)."""
    changes: dict = {}
    for fl in dataclasses.fields(s):
        v = getattr(s, fl.name)
        if isinstance(v, tuple) and v and all(isinstance(x, ir.Stmt) for x in v):
            nv = tuple(x if x.stmt is not None else _at(x, k) for x in v)
            if any(a is not b for a, b in zip(nv, v)):
                changes[fl.name] = nv
    if s.stmt is None and k is not None:
        changes["stmt"] = k
    return dataclasses.replace(s, **changes) if changes else s


def _none_facts(cond: ir.Expr, truth: bool) -> set[str]:
    """AOT mode, tier S2a: the `str | None` locals `cond` proves not None when it evaluates to `truth`."""
    if isinstance(cond, ir.Is) and isinstance(cond.left, ir.Const) and cond.left.value is None:
        cond = dataclasses.replace(cond, left=cond.right, right=cond.left)          # `None is x`
    if isinstance(cond, ir.Is) and isinstance(cond.right, ir.Const) and cond.right.value is None \
            and isinstance(cond.left, ir.Local) and cond.left.type in (Type.STR_OPT, Type.OBJ):
        return {cond.left.name} if cond.negate == truth else set()        # (OBJ: a `C | None` class local)
    if isinstance(cond, ir.And) and truth:
        return _none_facts(cond.left, True) | _none_facts(cond.right, True)
    if isinstance(cond, ir.Or) and not truth:
        return _none_facts(cond.left, False) | _none_facts(cond.right, False)
    if isinstance(cond, ir.UnaryOp) and cond.op is ir.UnaryOpKind.NOT:
        return _none_facts(cond.operand, not truth)
    return set()


def _unnarrow_against_none(e: ir.Expr, other: ir.Expr) -> ir.Expr:
    """`x is None` where x reads as proved (a StrNarrow): the comparison is of the `str | None` value itself."""
    if isinstance(e, ir.StrNarrow) and isinstance(other, ir.Const) and other.value is None:
        return e.value
    return e


def _diverges(body: tuple[ir.Stmt, ...]) -> bool:
    """Control never reaches the end of `body`: it ends in a return, break or continue, or in an
    `if` whose branches both do (verify.py reads these as unreachable states too)."""
    if not body:
        return False
    last = body[-1]
    if isinstance(last, (ir.Return, ir.ReturnTuple, ir.Break, ir.Continue, ir.SysExit)):  # (AOT: ReturnTuple, SysExit)
        return True
    if isinstance(last, ir.If):
        return _diverges(last.then) and _diverges(last.orelse)
    return False


def _walk(x):
    if isinstance(x, (tuple, list)):
        for y in x:
            yield from _walk(y)
    elif dataclasses.is_dataclass(x) and not isinstance(x, type):
        yield x
        for f in dataclasses.fields(x):
            yield from _walk(getattr(x, f.name))


def _closed(fn: ir.Function, env: dict[str, ir.Function], seen: frozenset[str] = frozenset()) -> bool:
    """`fn` runs no user code: no GetAttr, CallObject, Truth, CompareObj, ObjToFloat, GetItem,
    Contains, Format, StoreItem, ForObj or OBJ BinOp, and calls only closed functions."""
    for n in _walk(fn.body):
        if isinstance(n, (ir.GetAttr, ir.CallObject, ir.Truth, ir.CompareObj, ir.ObjToFloat,
                          ir.GetItem, ir.Contains, ir.Format, ir.StoreItem, ir.ForObj)):
            return False
        if isinstance(n, ir.BinOp) and n.type == Type.OBJ:
            return False
        if isinstance(n, ir.Call):
            callee = env.get(n.function)
            if callee is None or n.function in seen or not _closed(callee, env, seen | {fn.name}):
                return False
    return True


def _box(e: ir.Expr) -> ir.Expr:
    return ir.Box(Type.OBJ, e) if e.type in ir.SCALARS or e.type == Type.INT else e


def _trivial(e: ir.Expr) -> bool:
    """No effect, cannot raise, cannot deopt: may be evaluated in any order, or twice."""
    if isinstance(e, (ir.Const, ir.Local, ir.Len)):
        return True
    if isinstance(e, (ir.ToFloat, ir.ToInt)):
        return _trivial(e.operand)
    if isinstance(e, ir.UnaryOp):
        return (e.op == ir.UnaryOpKind.NOT or e.type == Type.F64) and _trivial(e.operand)
    if isinstance(e, ir.BinOp):
        return e.type == Type.F64 and e.op in (ir.BinOpKind.ADD, ir.BinOpKind.SUB,
                                               ir.BinOpKind.MUL) \
            and _trivial(e.left) and _trivial(e.right)
    if isinstance(e, (ir.Compare, ir.And, ir.Or)):
        return _trivial(e.left) and _trivial(e.right)
    return False


def _creation_kind(node: ast.expr) -> str | None:
    """How a statement's value creates a native array: "iota" `list(range(n))`, "fill" `[v] * n`,
    "copy" `name[:]` (whether `name` is an array is decided by the caller), else None."""
    if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "list" \
            and not node.keywords and len(node.args) == 1 and _is_range_call(node.args[0]) \
            and len(node.args[0].args) == 1:
        return "iota"
    if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Mult) \
            and isinstance(node.left, ast.List) and len(node.left.elts) == 1 \
            and not isinstance(node.left.elts[0], ast.Starred):
        return "fill"
    if isinstance(node, ast.Subscript) and isinstance(node.value, ast.Name) \
            and isinstance(node.slice, ast.Slice) and node.slice.lower is None \
            and node.slice.upper is None and node.slice.step is None:
        return "copy"
    return None


def _simple(node: ast.expr) -> bool:
    return isinstance(node, (ast.Name, ast.Constant))


def _is_print_call(node: ast.expr) -> bool:
    return isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "print"


def _is_range_call(node: ast.expr) -> bool:
    return isinstance(node, ast.Call) and isinstance(node.func, ast.Name) \
        and node.func.id == "range" and not node.keywords and 1 <= len(node.args) <= 3 \
        and not any(isinstance(a, ast.Starred) for a in node.args)


def _int_literal(node: ast.expr) -> int | None:
    if isinstance(node, ast.Constant) and type(node.value) is int:
        return node.value
    if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.USub) \
            and isinstance(node.operand, ast.Constant) and type(node.operand.value) is int:
        return -node.operand.value
    return None


def _falls_through(body: tuple[ir.Stmt, ...]) -> bool:
    if not body:
        return True
    last = body[-1]
    if isinstance(last, (ir.Return, ir.ReturnTuple)):
        return False
    if isinstance(last, ir.If):
        return _falls_through(last.then) or _falls_through(last.orelse)
    if isinstance(last, ir.While):
        forever = isinstance(last.cond, ir.Const) and last.cond.value is True
        return not forever or _breaks(last.body)
    return True


def _breaks(body: tuple[ir.Stmt, ...]) -> bool:
    """A `break` of this loop (not of a nested one)."""
    for s in body:
        if isinstance(s, ir.Break):
            return True
        if isinstance(s, ir.If) and (_breaks(s.then) or _breaks(s.orelse)):
            return True
    return False


__all__ = ["lower"]
