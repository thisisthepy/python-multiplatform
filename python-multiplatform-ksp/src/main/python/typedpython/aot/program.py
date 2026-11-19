"""The program of an AOT build, read from source (SPEC N-17, #448, #451; agent proposal, not owner-confirmed).

`analyze(entry)` reads the entry module and does what needs no types:

1. checks the shape of the program (module level, imports of `math` and `sys`, `print`, the statements and
   expressions a function may hold) and collects findings in the file's own lines;
2. folds the module constants (`NAME = <constant expression>`, `NAME: int | float | bool = ...`) with
   Python's own arithmetic (`frontend.fold_constant`) so a malformed one is reported here, in its own
   words.

It rewrites nothing: the front end reads the file as it is (`frontend.lower(path, aot=True)`), with
`print` as a statement of its own, the constants as typed values and the `__main__` block as the entry
function (frontend module docstring "AOT mode").
"""
from __future__ import annotations

import ast
import builtins
import re
from dataclasses import dataclass, field
from pathlib import Path
from types import SimpleNamespace

from typedpython import frontend
from typedpython.aotcheck import Finding

I64_MIN, I64_MAX = -2**63, 2**63 - 1
MATH_FUNCTIONS = {"sqrt": 1, "exp": 1, "log": 1, "sin": 1, "cos": 1, "tan": 1, "fabs": 1, "atan2": 2, "hypot": 2}
SCALAR_NAMES = ("int", "float", "bool")
LIST_ELEMENTS = ("int", "float")             # `list[int]` and `list[float]` are the native lists
# builtin names a program must not rebind: the AOT mode compiles a call of each of these as the builtin
BUILTINS_USED = frozenset({"print", "float", "range", "len", "abs", "min", "max", "int", "list"})
RESERVED_PREFIX = "__tp_"
MAIN = frontend.AOT_ENTRY

_BIN_OPS = (ast.Add, ast.Sub, ast.Mult, ast.Div, ast.FloorDiv, ast.Mod)
_CMP_OPS = (ast.Eq, ast.NotEq, ast.Lt, ast.LtE, ast.Gt, ast.GtE)
_STATEMENT_NAMES = {
    "ClassDef": "class", "Try": "try", "TryStar": "try", "With": "with", "AsyncWith": "async with",
    "Raise": "raise", "Assert": "assert", "Delete": "del", "AsyncFor": "async for",
    "AsyncFunctionDef": "async def", "Match": "match", "Import": "import", "ImportFrom": "import",
    "TypeAlias": "type alias", "Nonlocal": "nonlocal", "Global": "global",
}
_CONTAINER_NAMES = {
    "List": "list display", "Tuple": "tuple display", "Dict": "dict display", "Set": "set display",
    "ListComp": "list comprehension", "SetComp": "set comprehension", "DictComp": "dict comprehension",
    "GeneratorExp": "generator expression", "Subscript": "subscript", "Slice": "slice",
}


@dataclass
class Program:
    path: Path
    rel: str
    source: str
    tree: ast.Module
    functions: list[ast.FunctionDef] = field(default_factory=list)
    main: ast.If | None = None
    consts: dict[str, int | float | bool] = field(default_factory=dict)
    findings: list[Finding] = field(default_factory=list)

    def bodies(self) -> dict[str, object]:
        """IR function name -> an object with `.body` (the function's own statements, original nodes)
        and `.lineno`, for the statement numbers of `resume.statements`."""
        out: dict[str, object] = {f.name: f for f in self.functions}
        if self.main is not None:
            out[MAIN] = SimpleNamespace(body=self.main.body, lineno=self.main.lineno)
        return out


# --- findings ------------------------------------------------------------------------------------

class _Report:
    def __init__(self, rel: str) -> None:
        self.rel = rel
        self.findings: list[Finding] = []
        self.function: str | None = None

    def error(self, node_or_line, rule: str, name: str, message: str) -> None:
        line = node_or_line if isinstance(node_or_line, int) else getattr(node_or_line, "lineno", 1)
        self.findings.append(Finding(self.rel, line, "error", None, rule, name, message, self.function))


# --- the checks ----------------------------------------------------------------------------------

def _bound_names(fn: ast.AST) -> set[str]:
    """Every name a function binds anywhere inside it (parameters and assignment targets)."""
    names: set[str] = set()
    for n in ast.walk(fn):
        if isinstance(n, ast.arg):
            names.add(n.arg)
        elif isinstance(n, ast.Name) and isinstance(n.ctx, (ast.Store, ast.Del)):
            names.add(n.id)
    return names


class _Checker:
    def __init__(self, program: Program, rep: _Report, arity: dict[str, int], has_math: bool,
                 has_sys: bool = False) -> None:
        self.p = program
        self.rep = rep
        self.arity = arity
        self.has_math = has_math
        self.has_sys = has_sys
        self.consts = program.consts
        self.shadow: set[str] = set()

    # -- one function (a def, or the entry block)

    def function(self, name: str, node: ast.FunctionDef | None, body: list[ast.stmt]) -> None:
        self.rep.function = name
        if node is not None:
            self.signature(node)
            self.shadow = _bound_names(node)
        else:
            self.shadow = set()
            for s in body:
                self.shadow |= _bound_names(s)
        for s in body:
            self.stmt(s)
        self.rep.function = None

    def annotation(self, ann: ast.expr | None, what: str, node: ast.AST, allow_none: bool = False,
                   allow_tuple: bool = False) -> None:
        if ann is None:
            self.rep.error(node, "aot/annotation", what, f"{what} has no type annotation (a program is checked "
                           "by its annotations)")
        elif isinstance(ann, ast.Name) and ann.id in SCALAR_NAMES:
            return
        elif allow_none and isinstance(ann, ast.Constant) and ann.value is None:
            return
        elif _is_list_annotation(ann):
            return
        elif allow_tuple and _is_tuple_annotation(ann):
            return
        else:
            more = ", and a tuple of those as a return type" if allow_tuple else ""
            self.rep.error(ann, "aot/type", ast.unparse(ann), f"the type `{ast.unparse(ann)}` is outside the "
                           f"AOT mode (int, float, bool, list[int], list[float]{more}, and None as a return type)")

    def signature(self, fn: ast.FunctionDef) -> None:
        a = fn.args
        for d in fn.decorator_list:
            self.rep.error(d, "aot/decorator", ast.unparse(d), f"the decorator `@{ast.unparse(d)}` is outside "
                           "the first AOT step (every function is compiled, no decorator is needed)")
        if a.posonlyargs or a.kwonlyargs or a.vararg or a.kwarg:
            self.rep.error(fn, "aot/parameters", fn.name, "only plain positional parameters are supported")
        if a.defaults or any(d is not None for d in a.kw_defaults):
            self.rep.error(fn, "aot/parameters", fn.name, "default parameter values are outside the first AOT step")
        for arg in a.args:
            self.annotation(arg.annotation, f"parameter `{arg.arg}`", arg)
        self.annotation(fn.returns, f"the return of `{fn.name}`", fn, allow_none=True, allow_tuple=True)

    # -- statements

    def stmt(self, s: ast.stmt) -> None:
        if isinstance(s, ast.Return):
            if s.value is not None:
                if isinstance(s.value, ast.Constant) and s.value.value is None:
                    return
                if isinstance(s.value, ast.Tuple):       # `return a, b`: a fixed tuple of scalars
                    for x in s.value.elts:
                        self.expr(x)
                    return
                self.expr(s.value)
        elif isinstance(s, ast.Assign):
            if len(s.targets) != 1 or not self.target_ok(s.targets[0]):
                self.rep.error(s, "aot/assign", "assignment", "only `name = value`, `a, b = f(...)` and "
                               "`list[i] = value` are supported (no chained assignment, no attribute or slice target)")
            elif isinstance(s.targets[0], ast.Subscript):
                self.expr(s.targets[0].slice)
            self.expr(s.value)
        elif isinstance(s, ast.AnnAssign):
            if not isinstance(s.target, ast.Name) or s.value is None:
                self.rep.error(s, "aot/assign", "assignment", "only `name: type = value` is supported")
            else:
                self.annotation(s.annotation, f"`{s.target.id}`", s)
            if s.value is not None:
                self.expr(s.value)
        elif isinstance(s, ast.AugAssign):
            if not (isinstance(s.target, ast.Name) or self.subscript_target(s.target)) \
                    or not isinstance(s.op, _BIN_OPS):
                self.rep.error(s, "aot/assign", "augmented assignment",
                               f"the augmented assignment `{ast.unparse(s)}` is outside the first AOT step")
            elif isinstance(s.target, ast.Subscript):
                self.expr(s.target.slice)
            self.expr(s.value)
        elif isinstance(s, ast.If):
            self.expr(s.test)
            for b in s.body + s.orelse:
                self.stmt(b)
        elif isinstance(s, ast.While):
            self.expr(s.test)
            if s.orelse:
                self.rep.error(s.orelse[0], "aot/statement", "while-else", "`else` on a loop is outside the first AOT step")
            for b in s.body + s.orelse:
                self.stmt(b)
        elif isinstance(s, ast.For):
            self.for_stmt(s)
        elif isinstance(s, (ast.Break, ast.Continue, ast.Pass)):
            return
        elif isinstance(s, ast.Expr):
            self.expr_stmt(s)
        elif isinstance(s, ast.FunctionDef):
            self.rep.error(s, "aot/statement", "nested def", "a function defined inside a function is a closure, "
                           "outside the first AOT step")
        else:
            kind = _STATEMENT_NAMES.get(type(s).__name__, type(s).__name__)
            self.rep.error(s, "aot/statement", kind, f"a `{kind}` statement is outside the first AOT step")

    def subscript_target(self, t: ast.expr) -> bool:
        """`name[index]` (not a slice): a store into a list."""
        return isinstance(t, ast.Subscript) and isinstance(t.value, ast.Name) and not isinstance(t.slice, ast.Slice) \
            and t.value.id not in {"sys"}

    def target_ok(self, t: ast.expr) -> bool:
        if isinstance(t, ast.Name) or self.subscript_target(t):
            return True
        return isinstance(t, (ast.Tuple, ast.List)) and bool(t.elts) and all(isinstance(e, ast.Name) for e in t.elts)

    def for_stmt(self, s: ast.For) -> None:
        it = s.iter
        if not (isinstance(it, ast.Call) and isinstance(it.func, ast.Name) and it.func.id == "range"
                and 1 <= len(it.args) <= 3 and not it.keywords):
            self.rep.error(s, "aot/statement", "for", "only `for name in range(...)` is supported")
        else:
            for a in it.args:
                self.expr(a)
        if not isinstance(s.target, ast.Name):
            self.rep.error(s, "aot/statement", "for", "the loop target must be one name")
        if s.orelse:
            self.rep.error(s.orelse[0], "aot/statement", "for-else", "`else` on a loop is outside the first AOT step")
        for b in s.body + s.orelse:
            self.stmt(b)

    def expr_stmt(self, s: ast.Expr) -> None:
        v = s.value
        if isinstance(v, ast.Constant) and isinstance(v.value, str):
            return                                                  # a docstring
        if isinstance(v, ast.Call) and isinstance(v.func, ast.Name) and v.func.id == "print":
            self.print_stmt(s, v)
        elif isinstance(v, ast.Call):
            self.expr(v)
        else:
            self.rep.error(s, "aot/statement", "expression", "an expression statement must be a call")

    def print_stmt(self, s: ast.Expr, call: ast.Call) -> None:
        for k in call.keywords:
            self.rep.error(k.value, "aot/print-keyword", k.arg or "**",
                           f"print(..., {k.arg or '**'}=...) is outside the first AOT step "
                           "(positional arguments only; no sep, end, file or flush)")
        for a in call.args:
            if isinstance(a, ast.Starred):
                self.rep.error(a, "aot/print-arg", "*", "a starred argument of print is outside the first AOT step")
            elif isinstance(a, ast.Constant) and isinstance(a.value, str):
                if "\x00" in a.value or any(0xD800 <= ord(c) <= 0xDFFF for c in a.value):
                    self.rep.error(a, "aot/print-arg", "str", "a string literal with a NUL or a lone surrogate is "
                                   "outside the first AOT step")
            elif isinstance(a, ast.Constant) and a.value is None:
                self.rep.error(a, "aot/print-arg", "None", "print(None) is outside the first AOT step "
                               "(int, float, bool and string literals only)")
            elif isinstance(a, ast.JoinedStr):
                self.fstring(a)
            else:
                self.expr(a)

    def fstring(self, a: ast.JoinedStr) -> None:
        """An f-string argument of print: text, `{e}` and `{e:.Nf}` (N from 0 to 20); nothing else."""
        for piece in a.values:
            if isinstance(piece, ast.Constant):
                if isinstance(piece.value, str) and ("\x00" in piece.value
                                                     or any(0xD800 <= ord(c) <= 0xDFFF for c in piece.value)):
                    self.rep.error(piece, "aot/print-arg", "str", "a string literal with a NUL or a lone surrogate is "
                                   "outside the first AOT step")
                continue
            assert isinstance(piece, ast.FormattedValue)
            if piece.conversion != -1:
                self.rep.error(piece, "aot/f-string", "conversion", "an f-string conversion (`!r`, `!s`, `!a`) or `=` is "
                               "outside the AOT mode (`{e}` and `{e:.Nf}` only)")
            if piece.format_spec is not None:
                texts = piece.format_spec.values                       # type: ignore[attr-defined]
                if not all(isinstance(t, ast.Constant) and isinstance(t.value, str) for t in texts):
                    self.rep.error(piece, "aot/f-string", "nested field", "a nested field in a format spec is outside "
                                   "the AOT mode")
                else:
                    spec = "".join(t.value for t in texts)             # type: ignore[attr-defined]
                    m = re.fullmatch(r"\.([0-9]{1,2})f", spec)
                    if m is None or int(m.group(1)) > frontend.AOT_MAX_PRECISION:
                        self.rep.error(piece, "aot/f-string", spec, f"the format spec `{spec}` is outside the AOT mode "
                                       f"(only `.Nf` with N from 0 to {frontend.AOT_MAX_PRECISION})")
            self.expr(piece.value)

    # -- expressions

    def expr(self, e: ast.expr) -> None:
        if isinstance(e, ast.Constant):
            v = e.value
            if isinstance(v, bool) or isinstance(v, float):
                return
            if isinstance(v, int):
                if not I64_MIN <= v <= I64_MAX:
                    self.rep.error(e, "aot/int-literal", str(v), f"the int literal {v} is outside 64 bits "
                                   "(big integers arrive in a later step)")
                return
            kind = "None" if v is None else type(v).__name__
            if isinstance(v, str):
                self.rep.error(e, "aot/str", "str", "a string is only supported as a literal argument of print "
                               "(strings arrive in a later step)")
            else:
                self.rep.error(e, "aot/literal", kind, f"a `{kind}` literal is outside the first AOT step")
        elif isinstance(e, ast.Name):
            if e.id in self.consts and e.id not in self.shadow:
                v = self.consts[e.id]
                if isinstance(v, float) and v != v:
                    self.rep.error(e, "aot/constant", e.id, f"the module constant `{e.id}` is nan, which the "
                                   "first AOT step does not support")
        elif isinstance(e, ast.BinOp):
            if not isinstance(e.op, _BIN_OPS):
                sym = type(e.op).__name__
                self.rep.error(e, "aot/operator", sym, f"the operator `{_op_symbol(e.op)}` is outside the first AOT step "
                               "(+ - * / // % on int and float)")
            self.expr(e.left)
            self.expr(e.right)
        elif isinstance(e, ast.UnaryOp):
            if not isinstance(e.op, (ast.USub, ast.UAdd, ast.Not)):
                self.rep.error(e, "aot/operator", "~", "the operator `~` is outside the first AOT step")
            self.expr(e.operand)
        elif isinstance(e, ast.BoolOp):
            for v in e.values:
                self.expr(v)
        elif isinstance(e, ast.Compare):
            for op in e.ops:
                if not isinstance(op, _CMP_OPS):
                    self.rep.error(e, "aot/operator", type(op).__name__, "only == != < <= > >= comparisons are supported")
            for v in [e.left, *e.comparators]:
                self.expr(v)
        elif isinstance(e, ast.Call):
            self.call(e)
        elif isinstance(e, ast.JoinedStr):
            self.rep.error(e, "aot/f-string", "f-string", "an f-string is only supported as an argument of print "
                           "(strings arrive in a later step)")
        elif isinstance(e, ast.Attribute):
            self.rep.error(e, "aot/attribute", e.attr, f"the attribute access `{ast.unparse(e)}` is outside the first AOT step "
                           "(`sys.argv` only inside `int(sys.argv[i])` and `len(sys.argv)`)")
        elif isinstance(e, ast.IfExp):
            self.expr(e.test)
            self.expr(e.body)
            self.expr(e.orelse)
        elif isinstance(e, ast.List):
            self.list_display(e)
        elif isinstance(e, ast.Subscript):
            self.subscript(e)
        elif isinstance(e, ast.Lambda):
            self.rep.error(e, "aot/expression", "lambda", "a lambda is outside the first AOT step")
        elif type(e).__name__ in _CONTAINER_NAMES:
            kind = _CONTAINER_NAMES[type(e).__name__]
            self.rep.error(e, "aot/container", kind, f"a {kind} is outside the first AOT step (containers arrive in a later step)")
        else:
            kind = type(e).__name__
            self.rep.error(e, "aot/expression", kind, f"a `{kind}` expression is outside the first AOT step")

    def list_display(self, e: ast.List) -> None:
        if not e.elts:
            self.rep.error(e, "aot/container", "list display", "an empty list display `[]` has no element type in the AOT "
                           "mode (a list holds only floats or only ints)")
        for x in e.elts:
            if isinstance(x, ast.Starred):
                self.rep.error(x, "aot/container", "list display", "a starred element in a list display is outside the "
                               "AOT mode")
            else:
                self.expr(x)

    def subscript(self, e: ast.Subscript) -> None:
        """`a[i]` of a list, and `a[:]` (the whole list, a copy). Any other slice, a subscript of anything but a
        name, is outside the AOT mode."""
        if isinstance(e.slice, ast.Slice):
            sl = e.slice
            if not (sl.lower is None and sl.upper is None and sl.step is None):
                self.rep.error(e, "aot/container", "slice", f"the slice `{ast.unparse(e)}` is outside the AOT mode "
                               "(only the whole-list copy `a[:]`)")
        elif not isinstance(e.value, ast.Name):
            self.rep.error(e, "aot/container", "subscript", f"the subscript `{ast.unparse(e)}` is outside the AOT mode "
                           "(a list name indexed by an int)")
        else:
            if e.value.id == "sys":
                self.rep.error(e, "aot/attribute", "sys", "`sys` may only be used as `int(sys.argv[i])` and `len(sys.argv)`")
            self.expr(e.slice)
        if isinstance(e.value, ast.Name) and e.value.id in self.p.consts and e.value.id not in self.shadow:
            self.rep.error(e, "aot/container", "subscript", f"`{e.value.id}` is a constant, not a list")

    def sys_argv(self, e: ast.expr) -> bool:
        return (isinstance(e, ast.Attribute) and e.attr == "argv" and isinstance(e.value, ast.Name)
                and e.value.id == "sys" and self.has_sys and "sys" not in self.shadow)

    def call(self, c: ast.Call) -> None:
        f = c.func
        for k in c.keywords:
            self.rep.error(k.value, "aot/keyword", k.arg or "**", "keyword arguments are outside the first AOT step")
        if any(isinstance(a, ast.Starred) for a in c.args):
            self.rep.error(c, "aot/keyword", "*", "a starred argument is outside the first AOT step")
        if isinstance(f, ast.Name):
            n = f.id
            if n in self.shadow:
                self.rep.error(f, "aot/call", n, f"`{n}` is a variable; only direct calls of the program's functions are supported")
            elif n in self.arity:
                if len(c.args) != self.arity[n]:
                    self.rep.error(c, "aot/call", n, f"`{n}` takes {self.arity[n]} argument(s) but {len(c.args)} are given")
            elif n == "float":
                if len(c.args) != 1:
                    self.rep.error(c, "aot/call", n, "float() with other than one argument is outside the first AOT step")
            elif n == "len":
                if len(c.args) == 1 and self.sys_argv(c.args[0]):
                    return
                if len(c.args) != 1 or not isinstance(c.args[0], ast.Name):
                    self.rep.error(c, "aot/call", n, "len() is only supported of a list name and of `sys.argv`")
            elif n == "abs":
                if len(c.args) != 1:
                    self.rep.error(c, "aot/call", n, "abs() takes one argument")
            elif n in ("min", "max"):
                if len(c.args) != 2:
                    self.rep.error(c, "aot/call", n, f"{n}() of other than two arguments is outside the AOT mode")
            elif n == "int":
                if (len(c.args) == 1 and isinstance(c.args[0], ast.Subscript) and self.sys_argv(c.args[0].value)
                        and not isinstance(c.args[0].slice, ast.Slice)):
                    self.expr(c.args[0].slice)
                    return
                self.rep.error(c, "aot/builtin", n, "`int()` is only supported as `int(sys.argv[i])` in the AOT mode")
                return
            elif n == "list":
                a = c.args[0] if len(c.args) == 1 else None
                if not (isinstance(a, ast.Call) and isinstance(a.func, ast.Name) and a.func.id == "range"
                        and len(a.args) == 1 and not a.keywords and "range" not in self.shadow):
                    self.rep.error(c, "aot/builtin", n, "`list()` is only supported as `list(range(n))` in the AOT mode")
                    return
                self.expr(a.args[0])
                return
            elif n == "print":
                self.rep.error(c, "aot/print-use", "print", "print is only supported as a statement of its own")
            elif n == "range":
                self.rep.error(c, "aot/call", "range", "range is only supported as the iterable of a `for` loop")
            elif hasattr(builtins, n) or n not in self.consts:
                self.rep.error(f, "aot/builtin", n, f"the call of `{n}` is not provided in the first AOT step "
                               "(the program's functions, float, math functions and print)")
            else:
                self.rep.error(f, "aot/call", n, f"`{n}` is a constant, not a function")
        elif (isinstance(f, ast.Attribute) and isinstance(f.value, ast.Name) and f.value.id == "math"
              and self.has_math and "math" not in self.shadow):
            if f.attr not in MATH_FUNCTIONS:
                self.rep.error(f, "aot/math", f"math.{f.attr}", f"`math.{f.attr}` is not provided in the first AOT step "
                               f"({', '.join(MATH_FUNCTIONS)})")
            elif len(c.args) != MATH_FUNCTIONS[f.attr]:
                self.rep.error(c, "aot/math", f"math.{f.attr}", f"`math.{f.attr}` takes {MATH_FUNCTIONS[f.attr]} argument(s)")
        else:
            self.rep.error(f, "aot/call", ast.unparse(f), f"the call of `{ast.unparse(f)}` is outside the first AOT step")
        for a in c.args:
            self.expr(a)


def _is_list_annotation(ann: ast.expr) -> bool:
    """`list[float]` or `list[int]`."""
    return (isinstance(ann, ast.Subscript) and isinstance(ann.value, ast.Name) and ann.value.id == "list"
            and isinstance(ann.slice, ast.Name) and ann.slice.id in LIST_ELEMENTS)


def _is_tuple_annotation(ann: ast.expr) -> bool:
    """`tuple[int, float, ...]` of at least one int, float or bool (no `...`)."""
    if not (isinstance(ann, ast.Subscript) and isinstance(ann.value, ast.Name) and ann.value.id == "tuple"):
        return False
    items = ann.slice.elts if isinstance(ann.slice, ast.Tuple) else [ann.slice]
    return bool(items) and all(isinstance(i, ast.Name) and i.id in SCALAR_NAMES for i in items)


def _op_symbol(op: ast.operator) -> str:
    return {ast.Pow: "**", ast.LShift: "<<", ast.RShift: ">>", ast.BitAnd: "&", ast.BitOr: "|",
            ast.BitXor: "^", ast.MatMult: "@"}.get(type(op), type(op).__name__)


# --- the module ----------------------------------------------------------------------------------

def _without_main_guards(source: str, tree: ast.Module) -> str:
    """The source with every `if __name__ == "__main__":` block of the module level blanked, line by line, so
    every other statement keeps its line number. A library (`analyze(library=True)`) has no entry."""
    lines = source.split("\n")
    for s in tree.body:
        if frontend.is_main_guard(s) and s.end_lineno is not None:
            for i in range(s.lineno - 1, s.end_lineno):
                lines[i] = ""
    return "\n".join(lines)


def analyze(entry: Path, *, library: bool = False) -> Program:
    """Read the program. `library=True` (`typedpython aot-lib`, #455; agent proposal, not owner-confirmed): the
    module has no entry, so a missing `if __name__ == "__main__":` block is not a finding, and a block that is
    there is not part of the program (it is blanked before anything reads the source, `program.source` is the
    blanked text, the line numbers are the file's). `analyze(entry)` is what `aot-build` has always called."""
    entry = Path(entry).resolve()
    source = entry.read_text(encoding="utf-8")
    rep = _Report(entry.name)
    try:
        tree = ast.parse(source, filename=str(entry))
        if library:
            source = _without_main_guards(source, tree)
            tree = ast.parse(source, filename=str(entry))
    except SyntaxError as e:
        rep.error(e.lineno or 1, "aot/syntax", "SyntaxError", f"the module does not parse ({e.msg})")
        return Program(entry, entry.name, source, ast.Module(body=[], type_ignores=[]), findings=rep.findings)
    program = Program(entry, entry.name, source, tree)

    # -- module level
    has_math = False
    has_sys = False
    main_guards: list[ast.If] = []
    seen_names: dict[str, int] = {}
    for i, s in enumerate(tree.body):
        if isinstance(s, ast.Expr) and isinstance(s.value, ast.Constant) and isinstance(s.value.value, str) and i == 0:
            continue
        if isinstance(s, ast.Import):
            only = s.names[0] if len(s.names) == 1 and s.names[0].asname is None else None
            if only is not None and only.name == "math" and not has_math:
                has_math = True
            elif only is not None and only.name == "sys" and not has_sys:
                has_sys = True
            else:
                rep.error(s, "aot/import", ", ".join(a.name for a in s.names),
                          "only a single `import math` and a single `import sys` are supported in the AOT mode")
        elif isinstance(s, ast.ImportFrom):
            rep.error(s, "aot/import", s.module or ".", "only a single `import math` and a single `import sys` are "
                      "supported in the AOT mode")
        elif isinstance(s, ast.FunctionDef):
            program.functions.append(s)
            if s.name in seen_names:
                rep.error(s, "aot/redefinition", s.name, f"`{s.name}` is defined twice (first at line {seen_names[s.name]})")
            seen_names.setdefault(s.name, s.lineno)
        elif ((isinstance(s, ast.Assign) and len(s.targets) == 1 and isinstance(s.targets[0], ast.Name))
              or (isinstance(s, ast.AnnAssign) and isinstance(s.target, ast.Name))):
            name = s.targets[0].id if isinstance(s, ast.Assign) else s.target.id      # type: ignore[union-attr]
            try:
                _, v = frontend.aot_constant(s, program.consts)
            except ValueError as e:
                rep.error(s, "aot/constant", name, f"the module constant `{name}` {e}")
            else:
                if name in seen_names:
                    rep.error(s, "aot/redefinition", name, f"`{name}` is bound twice at module level "
                              f"(first at line {seen_names[name]}); a constant is assigned once")
                else:
                    program.consts[name] = v
                seen_names.setdefault(name, s.lineno)
        elif frontend.is_main_guard(s):
            assert isinstance(s, ast.If)
            main_guards.append(s)
            if s.orelse:
                rep.error(s.orelse[0], "aot/entry", "else", "the `if __name__ == \"__main__\"` block has no `else` branch in the first AOT step")
        else:
            kind = _STATEMENT_NAMES.get(type(s).__name__, type(s).__name__)
            rep.error(s, "aot/module-statement", kind,
                      f"a module-level `{kind}` statement is not allowed: a program has a docstring, `import math` and "
                      f"`import sys`, "
                      "defs, `NAME = <constant expression>` (also `NAME: int | float | bool = ...`) and one "
                      "`if __name__ == \"__main__\"` block")
    if library:
        assert not main_guards          # blanked above
    elif len(main_guards) != 1:
        rep.error(main_guards[1] if len(main_guards) > 1 else 1, "aot/entry", "__main__",
                  "a program has exactly one `if __name__ == \"__main__\":` block" +
                  (f" (found {len(main_guards)})" if main_guards else ""))
    else:
        program.main = main_guards[0]
        after = tree.body[tree.body.index(program.main) + 1:]
        if after:
            rep.error(after[0], "aot/entry", "__main__", "the `if __name__ == \"__main__\":` block must be the last "
                      "statement of the module: CPython runs it before anything after it is defined")

    names = {f.name for f in program.functions}
    for n, line in list(seen_names.items()):
        if n.startswith(RESERVED_PREFIX):
            rep.error(line, "aot/reserved", n, f"names starting with `{RESERVED_PREFIX}` are reserved")
    for n in (*names, *program.consts):
        if n in BUILTINS_USED | {"math", "sys"}:
            rep.error(seen_names[n], "aot/rebinding", n, f"`{n}` is rebound by the program, which the first AOT step does not support")
    for n in ast.walk(tree):
        bound = None
        if isinstance(n, ast.arg):
            bound = n.arg
        elif isinstance(n, ast.Name) and isinstance(n.ctx, (ast.Store, ast.Del)):
            bound = n.id
        if bound in BUILTINS_USED:
            rep.error(n, "aot/rebinding", bound, f"`{bound}` is rebound by the program, which the first AOT step does not support")

    # -- the entry block must not bind a module name (it would rebind a constant or a function that
    # other functions read; as a function body its names would be locals)
    module_names = names | set(program.consts) | ({"math"} if has_math else set()) | ({"sys"} if has_sys else set())
    if program.main is not None:
        for s in program.main.body:
            for n in ast.walk(s):
                if isinstance(n, ast.Name) and isinstance(n.ctx, (ast.Store, ast.Del)) and n.id in module_names:
                    rep.error(n, "aot/main-rebinds", n.id, f"the `__main__` block assigns `{n.id}`, a name the module "
                              "defines; the first AOT step keeps the block's names local")

    # -- functions
    arity = {f.name: len(f.args.args) for f in program.functions}
    checker = _Checker(program, rep, arity, has_math, has_sys)
    for f in program.functions:
        checker.function(f.name, f, f.body)
    if program.main is not None:
        checker.function("__main__", None, program.main.body)
        if program.main.test.end_lineno != program.main.test.lineno:
            rep.error(program.main, "aot/entry", "__main__", "the `__main__` test must be on one line")

    unique: dict[tuple[int, str, str], Finding] = {}
    for f in rep.findings:
        unique.setdefault((f.line, f.rule, f.name), f)
    program.findings = list(unique.values())
    return program
