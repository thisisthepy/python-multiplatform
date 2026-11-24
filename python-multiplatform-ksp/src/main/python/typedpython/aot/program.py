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
MATH_FUNCTIONS = {
    "sqrt": 1, "exp": 1, "log": 1, "log2": 1, "log10": 1,
    "sin": 1, "cos": 1, "tan": 1, "asin": 1, "acos": 1, "atan": 1, "atan2": 2,
    "sinh": 1, "cosh": 1, "tanh": 1, "fabs": 1, "hypot": 2,
    "pow": 2, "copysign": 2, "fmod": 2,
    "isnan": 1, "isinf": 1, "isfinite": 1,
    "floor": 1, "ceil": 1, "trunc": 1,
}
MATH_CONSTANTS = frozenset({"pi", "e", "inf", "nan", "tau"})
TIME_FUNCTIONS = {"perf_counter": 0, "monotonic": 0, "time": 0, "sleep": 1}
SCALAR_NAMES = ("int", "float", "bool")
LIST_ELEMENTS = ("int", "float")             # `list[int]` and `list[float]` are the native lists
# builtin names a program must not rebind: the AOT mode compiles a call of each of these as the builtin
BUILTINS_USED = frozenset({"print", "float", "range", "len", "abs", "min", "max", "int", "bool", "list",
                           "str", "repr", "ord", "chr"})        # (str ... chr: tier S1)
RESERVED_PREFIX = "__tp_"
MAIN = frontend.AOT_ENTRY

_BIN_OPS = (ast.Add, ast.Sub, ast.Mult, ast.Div, ast.FloorDiv, ast.Mod,
            ast.BitAnd, ast.BitOr, ast.BitXor, ast.LShift, ast.RShift, ast.Pow)
_CMP_OPS = (ast.Eq, ast.NotEq, ast.Lt, ast.LtE, ast.Gt, ast.GtE, ast.Is, ast.IsNot, ast.In, ast.NotIn)
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
    classes: list[ast.ClassDef] = field(default_factory=list)
    main: ast.If | None = None
    consts: dict[str, int | float | bool | str] = field(default_factory=dict)
    findings: list[Finding] = field(default_factory=list)

    def bodies(self) -> dict[str, object]:
        """IR function name -> an object with `.body` (the function's own statements, original nodes)
        and `.lineno`, for the statement numbers of `resume.statements`."""
        out: dict[str, object] = {f.name: f for f in self.functions}
        for c in self.classes:
            for st in c.body:
                if isinstance(st, ast.FunctionDef):
                    out[f"{c.name}.{st.name}"] = st
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
                 has_sys: bool = False, has_time: bool = False, library: bool = False,
                 class_names: set[str] | None = None, class_arity: dict[str, int] | None = None,
                 class_fields: dict[str, set[str]] | None = None,
                 class_methods: dict[str, set[str]] | None = None,
                 func_return_class: dict[str, str] | None = None,
                 method_return_class: dict[tuple[str, str], str] | None = None,
                 field_class: dict[tuple[str, str], str] | None = None) -> None:
        self.p = program
        self.rep = rep
        self.arity = arity
        self.has_math = has_math
        self.has_sys = has_sys
        self.has_time = has_time
        self.library = library
        self.class_names: set[str] = class_names or set()
        self.class_arity: dict[str, int] = class_arity or {}
        self.class_fields: dict[str, set[str]] = class_fields or {}
        self.class_methods: dict[str, set[str]] = class_methods or {}
        self.func_return_class: dict[str, str] = func_return_class or {}
        self.func_return_list_class: dict[str, str] = {}   # tier S2a: set by check() after construction
        self.field_list_class: dict[tuple[str, str], str] = {}     # tier S2a: (class, list field) -> element class
        self.list_classes: dict[str, str] = {}             # tier S2a: list[C] names of the function -> C
        self.method_return_class: dict[tuple[str, str], str] = method_return_class or {}
        self.field_class: dict[tuple[str, str], str] = field_class or {}
        self.consts = program.consts
        self.shadow: set[str] = set()
        self.var_classes: dict[str, str] = {}

    # -- one function (a def, or the entry block)

    def function(self, name: str, node: ast.FunctionDef | None, body: list[ast.stmt],
                 is_method: bool = False, owner_class: str | None = None) -> None:
        self.rep.function = name
        self.var_classes = {}
        self.list_classes = {}
        if node is not None:
            self.signature(node, is_method=is_method)
            self.shadow = _bound_names(node)
            for idx, arg in enumerate(node.args.args):
                if is_method and idx == 0 and owner_class is not None:
                    self.var_classes[arg.arg] = owner_class
                else:
                    cls = _extract_class_name(arg.annotation, self.class_names)
                    if cls is not None:
                        self.var_classes[arg.arg] = cls
                    lcls = _list_class_name(arg.annotation, self.class_names)
                    if lcls is not None:
                        self.list_classes[arg.arg] = lcls       # tier S2a
        else:
            self.shadow = set()
            for s in body:
                self.shadow |= _bound_names(s)
        for s in body:
            self.stmt(s)
        self.var_classes = {}
        self.rep.function = None

    def _infer_list_class(self, e: ast.expr) -> str | None:
        """Tier S2a: the element class C of a list[C] expression (a name, a call of a function returning one, a
        display of instances of one class)."""
        if isinstance(e, ast.Name):
            return self.list_classes.get(e.id)
        if isinstance(e, ast.Attribute) and isinstance(e.value, ast.Name):
            recv = self.var_classes.get(e.value.id)
            if recv is not None:
                return self.field_list_class.get((recv, e.attr))        # a list field
        if isinstance(e, ast.Call) and isinstance(e.func, ast.Name):
            return self.func_return_list_class.get(e.func.id)
        if isinstance(e, ast.List) and e.elts:
            found = {self._infer_expr_class(x) for x in e.elts}
            if len(found) == 1 and None not in found:
                return next(iter(found))
        return None

    def _infer_expr_class(self, e: ast.expr) -> str | None:
        if isinstance(e, ast.Subscript) and not isinstance(e.slice, ast.Slice):
            return self._infer_list_class(e.value)            # tier S2a: an element of a list[C]
        if isinstance(e, ast.Call) and isinstance(e.func, ast.Attribute) and e.func.attr == "pop":
            lc = self._infer_list_class(e.func.value)
            if lc is not None:
                return lc
        if isinstance(e, ast.Name):
            return self.var_classes.get(e.id)
        if isinstance(e, ast.Call):
            if isinstance(e.func, ast.Name):
                if e.func.id in self.class_names:
                    return e.func.id
                return self.func_return_class.get(e.func.id)
            if isinstance(e.func, ast.Attribute) and isinstance(e.func.value, ast.Name):
                recv_cls = self.var_classes.get(e.func.value.id)
                if recv_cls is not None:
                    return self.method_return_class.get((recv_cls, e.func.attr))
        if isinstance(e, ast.Attribute) and isinstance(e.value, ast.Name):
            recv_cls = self.var_classes.get(e.value.id)
            if recv_cls is not None:
                return self.field_class.get((recv_cls, e.attr))
        if isinstance(e, ast.IfExp):
            return self._infer_expr_class(e.body) or self._infer_expr_class(e.orelse)
        return None

    def annotation(self, ann: ast.expr | None, what: str, node: ast.AST, allow_none: bool = False,
                   allow_tuple: bool = False) -> None:
        if ann is None:
            self.rep.error(node, "aot/annotation", what, f"{what} has no type annotation (a program is checked "
                           "by its annotations)")
        elif isinstance(ann, ast.Name) and (ann.id in SCALAR_NAMES or ann.id == "str"):     # str: tier S1
            return
        elif allow_none and isinstance(ann, ast.Constant) and ann.value is None:
            return
        elif _is_list_annotation(ann) or _is_str_list_annotation(ann) or _is_optional_str_annotation(ann):
            return
        elif _is_class_list_annotation(ann, self.class_names):
            return                          # tier S2a: list[C] of a compiled class
        elif _is_class_annotation(ann, self.class_names):
            return
        elif allow_tuple and _is_tuple_annotation(ann):
            return
        elif _is_dict_annotation(ann):
            return                          # tier S2b: dict[K, V]
        elif allow_tuple and _is_tuple_list_annotation(ann):
            return                          # tier S2a: one list per element (typedpython/tuples.py)
        else:
            more = (", a fixed tuple of int, float, bool and str, a list of such tuples"
                    if allow_tuple else "")
            self.rep.error(ann, "aot/type", ast.unparse(ann), f"the type `{ast.unparse(ann)}` is outside the "
                           f"AOT mode (int, float, bool, str, list[int], list[float], compiled classes{more}, dict[K, V] of int, str or "
                           "bool keys and int, float, bool, str or `str | None` values, and None as a return type)")

    def signature(self, fn: ast.FunctionDef, is_method: bool = False) -> None:
        a = fn.args
        for d in fn.decorator_list:
            self.rep.error(d, "aot/decorator", ast.unparse(d), f"the decorator `@{ast.unparse(d)}` is outside "
                           "the first AOT step (every function is compiled, no decorator is needed)")
        if a.posonlyargs or a.kwonlyargs or a.vararg or a.kwarg:
            self.rep.error(fn, "aot/parameters", fn.name, "only plain positional parameters are supported")
        if a.defaults or any(d is not None for d in a.kw_defaults):
            self.rep.error(fn, "aot/parameters", fn.name, "default parameter values are outside the first AOT step")
        for idx, arg in enumerate(a.args):
            if is_method and idx == 0:
                if arg.annotation is not None:
                    self.annotation(arg.annotation, f"parameter `{arg.arg}`", arg)
                continue
            self.annotation(arg.annotation, f"parameter `{arg.arg}`", arg, allow_tuple=True)
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
                self.subscript_parts(s.targets[0])
            t0 = s.targets[0]
            if (isinstance(s.value, ast.List) and not s.value.elts and isinstance(t0, ast.Attribute)
                    and isinstance(t0.value, ast.Name)
                    and (self.var_classes.get(t0.value.id), t0.attr) in self.field_list_class):
                pass                        # tier S2a: `self.items = []`, an empty list of the field's class
            else:
                self.expr(s.value)
            if len(s.targets) == 1 and isinstance(s.targets[0], ast.Name):
                lcls = self._infer_list_class(s.value)
                if lcls is not None:
                    self.list_classes[s.targets[0].id] = lcls       # tier S2a
                cls = self._infer_expr_class(s.value)
                if cls is not None:
                    self.var_classes[s.targets[0].id] = cls
                elif not (isinstance(s.value, ast.Constant) and s.value.value is None):
                    self.var_classes.pop(s.targets[0].id, None)
        elif isinstance(s, ast.AnnAssign):
            if not isinstance(s.target, ast.Name) or s.value is None:
                self.rep.error(s, "aot/assign", "assignment", "only `name: type = value` is supported")
            else:
                self.annotation(s.annotation, f"`{s.target.id}`", s, allow_tuple=True)
                cls = _extract_class_name(s.annotation, self.class_names)
                if cls is not None:
                    self.var_classes[s.target.id] = cls
                else:
                    self.var_classes.pop(s.target.id, None)
                lcls = _list_class_name(s.annotation, self.class_names)
                if lcls is not None:
                    self.list_classes[s.target.id] = lcls        # tier S2a
            if s.value is not None and not (isinstance(s.value, ast.List) and not s.value.elts
                                            and (_is_str_list_annotation(s.annotation)
                                                 or _is_tuple_list_annotation(s.annotation)
                                                 or _is_list_annotation(s.annotation)
                                                 or _list_class_name(s.annotation, self.class_names))):
                self.expr(s.value)          # (`x: list[str] = []`: an empty list of str, tier S1b; of int, float and
                #                             tuples, tier S2a)
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
        elif isinstance(s, ast.Delete):
            # tier S2a: `del a[i]`, `del a[i:j:k]` of a list (the front end checks `a` is one)
            for t in s.targets:
                if self.subscript_target(t) or self.slice_target(t):
                    self.subscript_parts(t)
                else:
                    self.rep.error(s, "aot/statement", "del", f"`del {ast.unparse(t)}` is outside the AOT mode (only "
                                   "`del a[i]` and `del a[i:j]` of a list)")
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
        if isinstance(t, ast.Subscript) and self.attribute_target(t.value) and not isinstance(t.slice, ast.Slice):
            return True                     # tier S2b: `obj.d[k] = v` of a dict field (the front end checks the type)
        return isinstance(t, ast.Subscript) and isinstance(t.value, ast.Name) and not isinstance(t.slice, ast.Slice) \
            and t.value.id not in {"sys"}

    def slice_target(self, t: ast.expr) -> bool:
        """`name[i:j:k]`: a slice of a list (tier S2a: slice assignment and `del`)."""
        return isinstance(t, ast.Subscript) and isinstance(t.value, ast.Name) and isinstance(t.slice, ast.Slice) \
            and t.value.id not in {"sys"}

    def subscript_parts(self, t: ast.Subscript) -> None:
        if isinstance(t.slice, ast.Slice):
            for b in (t.slice.lower, t.slice.upper, t.slice.step):
                if b is not None:
                    self.expr(b)
        else:
            self.expr(t.slice)

    def attribute_target(self, t: ast.expr) -> bool:
        """`name.field`: a store into a compiled class field."""
        if not (isinstance(t, ast.Attribute) and isinstance(t.value, ast.Name)):
            return False
        cls = self.var_classes.get(t.value.id)
        return cls is not None and t.attr in self.class_fields.get(cls, ())

    def target_ok(self, t: ast.expr) -> bool:
        if isinstance(t, ast.Name) or self.subscript_target(t) or self.slice_target(t) or self.attribute_target(t):
            return True
        return isinstance(t, (ast.Tuple, ast.List)) and bool(t.elts) and all(isinstance(e, ast.Name) for e in t.elts)

    def for_stmt(self, s: ast.For) -> None:
        it = s.iter
        if isinstance(it, ast.Call) and isinstance(it.func, ast.Name) and it.func.id == "range":
            if not (1 <= len(it.args) <= 3 and not it.keywords):
                self.rep.error(s, "aot/statement", "for", "range() takes one to three positional arguments")
            for a in it.args:
                self.expr(a)
        else:
            self.expr(it)               # tier S1: a str (the front end checks the type)
            lcls = self._infer_list_class(it)
            if lcls is not None and isinstance(s.target, ast.Name):
                self.var_classes[s.target.id] = lcls        # tier S2a: the variable of a loop over a list[C]
        if not isinstance(s.target, ast.Name) and not (
                isinstance(s.target, ast.Tuple) and s.target.elts and all(isinstance(x, ast.Name) for x in s.target.elts)):
            # (tier S2a: names for the elements of a list of tuples, which typedpython/tuples.py checks)
            self.rep.error(s, "aot/statement", "for", "the loop target must be one name, or names for the elements of "
                           "a list of tuples")
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
            if k.arg in ("sep", "end"):
                if not (isinstance(k.value, ast.Constant) and k.value.value is None):
                    self.expr(k.value)
            else:
                self.rep.error(k.value, "aot/print-keyword", k.arg or "**",
                               f"print(..., {k.arg or '**'}=...) is outside the AOT mode "
                               "(positional arguments, sep and end; no file or flush)")
        for a in call.args:
            if isinstance(a, ast.Starred):
                self.rep.error(a, "aot/print-arg", "*", "a starred argument of print is outside the first AOT step")
            elif isinstance(a, ast.Constant) and isinstance(a.value, str):
                pass                                                # tier S1: any text
            elif isinstance(a, ast.Constant) and a.value is None:
                self.rep.error(a, "aot/print-arg", "None", "print(None) is outside the first AOT step "
                               "(int, float, bool and string literals only)")
            elif isinstance(a, ast.JoinedStr):
                self.fstring(a)
            else:
                self.expr(a)

    def fstring(self, a: ast.JoinedStr) -> None:
        """An f-string (tier S1: anywhere): text, `{e}`, `{e!s}`, `{e!r}` and `{e:.Nf}` (N from 0 to 20); nothing else."""
        for piece in a.values:
            if isinstance(piece, ast.Constant):
                continue
            assert isinstance(piece, ast.FormattedValue)
            if piece.conversion not in (-1, 115, 114):
                self.rep.error(piece, "aot/f-string", "conversion", "the f-string conversion `!a` is outside the AOT "
                               "mode (`{e}`, `{e!s}`, `{e!r}` and `{e:.Nf}`)")
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
            if v is None:
                return
            kind = type(v).__name__
            if isinstance(v, str):
                return                                              # tier S1
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
            if not isinstance(e.op, (ast.USub, ast.UAdd, ast.Not, ast.Invert)):
                self.rep.error(e, "aot/operator", type(e.op).__name__,
                               f"the unary operator `{type(e.op).__name__}` is outside the AOT mode")
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
            self.fstring(e)                                         # tier S1: an f-string is a str value
        elif isinstance(e, ast.Attribute):
            if (isinstance(e.value, ast.Name) and e.value.id == "sys" and e.attr == "maxsize"
                    and self.has_sys and "sys" not in self.shadow):
                return
            if (isinstance(e.value, ast.Name) and e.value.id == "math" and e.attr in MATH_CONSTANTS
                    and self.has_math and "math" not in self.shadow):
                return
            if isinstance(e.value, ast.Name):
                cls = self.var_classes.get(e.value.id)
                if cls is not None and e.attr in self.class_fields.get(cls, ()):
                    return
            self.rep.error(e, "aot/attribute", e.attr, f"the attribute access `{ast.unparse(e)}` is outside the AOT mode "
                           "(`sys.argv`, `sys.maxsize`, `math` constants, and compiled class fields only)")
        elif isinstance(e, ast.IfExp):
            self.expr(e.test)
            self.expr(e.body)
            self.expr(e.orelse)
        elif isinstance(e, ast.List):
            self.list_display(e)
        elif isinstance(e, ast.Dict):
            for k, v in zip(e.keys, e.values):      # tier S2b: a dict display (the front end checks the types)
                if k is None:
                    self.rep.error(v, "aot/container", "dict display", "`**` in a dict display is outside the AOT mode")
                else:
                    self.expr(k)
                self.expr(v)
        elif isinstance(e, ast.Tuple) and e.elts and not any(isinstance(x, ast.Starred) for x in e.elts):
            for x in e.elts:                # tier S2a: a tuple display (the front end checks where it is used)
                self.expr(x)
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
        if isinstance(e.value, ast.Name) and e.value.id == "sys":
            self.rep.error(e, "aot/attribute", "sys", "`sys` may only be used as `sys.argv[i]`, `len(sys.argv)` and "
                           "`sys.exit`")
        elif not (isinstance(e.value, ast.Name) or self.sys_argv(e.value)):
            self.expr(e.value)              # tier S1: a str value (the front end checks the type)
        if isinstance(e.slice, ast.Slice):
            for b in (e.slice.lower, e.slice.upper, e.slice.step):
                if b is not None:
                    self.expr(b)            # a list takes only `a[:]`, which the front end checks; a str any slice
        else:
            self.expr(e.slice)
        if isinstance(e.value, ast.Name) and e.value.id in self.p.consts and e.value.id not in self.shadow \
                and not isinstance(self.p.consts[e.value.id], str):
            self.rep.error(e, "aot/container", "subscript", f"`{e.value.id}` is a constant, not a list")

    def sys_argv(self, e: ast.expr) -> bool:
        return (isinstance(e, ast.Attribute) and e.attr == "argv" and isinstance(e.value, ast.Name)
                and e.value.id == "sys" and self.has_sys and "sys" not in self.shadow)

    def call(self, c: ast.Call) -> None:
        f = c.func
        str_kw = frontend.STR_METHOD_KEYWORDS.get(f.attr, ()) if isinstance(f, ast.Attribute) else ()
        for k in c.keywords:
            if k.arg in str_kw:             # tier S1b: split(sep=, maxsplit=), replace(count=)
                self.expr(k.value)
                continue
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
            elif n in self.class_arity:
                if len(c.args) != self.class_arity[n]:
                    self.rep.error(c, "aot/call", n, f"`{n}` takes {self.class_arity[n]} argument(s) but {len(c.args)} are given")
            elif n == "float":
                if len(c.args) != 1:
                    self.rep.error(c, "aot/call", n, "float() with other than one argument is outside the first AOT step")
            elif n == "len":
                if len(c.args) == 1 and self.sys_argv(c.args[0]):
                    return
                if len(c.args) != 1:
                    self.rep.error(c, "aot/call", n, "len() takes one argument")
            elif n in ("str", "repr", "ord", "chr"):                 # tier S1
                if len(c.args) != 1 and not (n == "str" and not c.args):
                    self.rep.error(c, "aot/builtin", n, f"`{n}()` takes one argument in the AOT mode")
            elif n == "abs":
                if len(c.args) != 1:
                    self.rep.error(c, "aot/call", n, "abs() takes one argument")
            elif n in ("min", "max"):
                if len(c.args) != 2:
                    self.rep.error(c, "aot/call", n, f"{n}() of other than two arguments is outside the AOT mode")
            elif n == "int":
                if len(c.args) != 1:
                    self.rep.error(c, "aot/builtin", n, "`int()` takes one argument in the AOT mode")
                    return
                if (isinstance(c.args[0], ast.Subscript) and self.sys_argv(c.args[0].value)
                        and not isinstance(c.args[0].slice, ast.Slice)):
                    self.expr(c.args[0].slice)
                    return
                if isinstance(c.args[0], ast.Constant) and isinstance(c.args[0].value, str):
                    return
                self.expr(c.args[0])
                return
            elif n == "bool":
                if len(c.args) != 1:
                    self.rep.error(c, "aot/builtin", n, "`bool()` takes one argument in the AOT mode")
                    return
                self.expr(c.args[0])
                return
            elif n == "list":
                a = c.args[0] if len(c.args) == 1 else None
                if isinstance(a, ast.Name) and not c.keywords:
                    return                  # tier S2a: `list(xs)`, a copy (the front end checks xs is a list)
                if not (isinstance(a, ast.Call) and isinstance(a.func, ast.Name) and a.func.id == "range"
                        and len(a.args) == 1 and not a.keywords and "range" not in self.shadow):
                    self.rep.error(c, "aot/builtin", n, "`list()` is only supported as `list(range(n))` and `list(xs)` "
                                   "of a list in the AOT mode")
                    return
                self.expr(a.args[0])
                return
            elif n == "dict":
                if not (len(c.args) == 1 and isinstance(c.args[0], ast.Name) and not c.keywords):
                    self.rep.error(c, "aot/builtin", n, "`dict()` is only supported as `dict(d)` of a dict in the AOT "
                                   "mode")
                return                      # tier S2b: `dict(d)`, a copy (the front end checks d is a dict)
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
                self.rep.error(f, "aot/math", f"math.{f.attr}", f"`math.{f.attr}` is not provided in the AOT mode "
                               f"({', '.join(MATH_FUNCTIONS)})")
            elif len(c.args) != MATH_FUNCTIONS[f.attr]:
                self.rep.error(c, "aot/math", f"math.{f.attr}", f"`math.{f.attr}` takes {MATH_FUNCTIONS[f.attr]} argument(s)")
        elif (isinstance(f, ast.Attribute) and isinstance(f.value, ast.Name) and f.value.id == "sys"
              and f.attr == "exit" and self.has_sys and "sys" not in self.shadow):
            if self.library:
                self.rep.error(c, "aot/library-exit", "sys.exit",
                               "`sys.exit` is not allowed in a library build (`aot-lib`)")
            elif len(c.args) > 1:
                self.rep.error(c, "aot/call", "sys.exit", "`sys.exit` takes at most one argument")
            elif len(c.args) == 1 and isinstance(c.args[0], ast.Constant) and c.args[0].value is None:
                return
        elif (isinstance(f, ast.Attribute) and f.attr == "write" and isinstance(f.value, ast.Attribute)
              and f.value.attr in ("stdout", "stderr") and isinstance(f.value.value, ast.Name)
              and f.value.value.id == "sys" and self.has_sys and "sys" not in self.shadow):
            if len(c.args) != 1:                                    # tier S1
                self.rep.error(c, "aot/call", f"sys.{f.value.attr}.write", "the write of a text stream takes one argument")
        elif (isinstance(f, ast.Attribute) and isinstance(f.value, ast.Name) and f.value.id == "time"
              and self.has_time and "time" not in self.shadow):
            if f.attr not in TIME_FUNCTIONS:
                self.rep.error(f, "aot/time", f"time.{f.attr}", f"`time.{f.attr}` is not provided in the AOT mode "
                               f"({', '.join(TIME_FUNCTIONS)})")
            elif len(c.args) != TIME_FUNCTIONS[f.attr]:
                self.rep.error(c, "aot/time", f"time.{f.attr}", f"`time.{f.attr}` takes {TIME_FUNCTIONS[f.attr]} argument(s)")
        elif (isinstance(f, ast.Attribute) and isinstance(f.value, ast.Name)
              and f.value.id in self.var_classes
              and f.attr in self.class_methods.get(self.var_classes[f.value.id], ())):
            pass
        elif isinstance(f, ast.Attribute) and f.attr in frontend.STR_METHODS and not (
                isinstance(f.value, ast.Name) and f.value.id in ("sys", "math", "time") and f.value.id not in self.shadow):
            if not isinstance(f.value, ast.Name):
                self.expr(f.value)          # tier S1b: a str method (the front end checks the receiver is a str)
            for a in c.args:
                if f.attr == "join" and isinstance(a, ast.List) and not a.elts:
                    continue                # `sep.join([])`: an empty list of str
                if isinstance(a, ast.Tuple):  # startswith / endswith of a tuple of str
                    for x in a.elts:
                        self.expr(x)
                elif not (isinstance(a, ast.Constant) and a.value is None):
                    self.expr(a)
            return
        elif isinstance(f, ast.Attribute) and isinstance(f.value, ast.Name) and f.attr in LIST_METHODS | DICT_METHODS \
                and not (f.value.id in ("sys", "math", "time") and f.value.id not in self.shadow):
            pass                            # tier S2a / S2b: a list or dict method (the front end checks the receiver)
        elif isinstance(f, ast.Attribute) and isinstance(f.value, ast.Attribute) and f.attr in LIST_METHODS | DICT_METHODS:
            self.expr(f.value)              # tier S2a / S2b: a method of a list or dict field (`self.items.append(x)`)
        else:
            self.rep.error(f, "aot/call", ast.unparse(f), f"the call of `{ast.unparse(f)}` is outside the first AOT step")
        for a in c.args:
            if isinstance(a, ast.List) and not a.elts:
                continue                    # `f([])`: an empty list for a list[str] parameter (the front end checks)
            self.expr(a)


def _extract_class_name(ann: ast.expr | None, class_names: set[str]) -> str | None:
    """Extract `C` from `C`, `C | None`, `None | C`, `Optional[C]`, or string forward ref `\"C | None\"`."""
    if ann is None or not class_names:
        return None
    if isinstance(ann, ast.Constant) and isinstance(ann.value, str):
        try:
            ann = ast.parse(ann.value, mode="eval").body
        except SyntaxError:
            return None
    if isinstance(ann, ast.Name):
        return ann.id if ann.id in class_names else None
    if isinstance(ann, ast.BinOp) and isinstance(ann.op, ast.BitOr):
        left, right = ann.left, ann.right
        if isinstance(left, ast.Constant) and left.value is None:
            return _extract_class_name(right, class_names)
        if isinstance(right, ast.Constant) and right.value is None:
            return _extract_class_name(left, class_names)
    if (isinstance(ann, ast.Subscript)
            and ((isinstance(ann.value, ast.Name) and ann.value.id == "Optional")
                 or (isinstance(ann.value, ast.Attribute) and ann.value.attr == "Optional"))):
        return _extract_class_name(ann.slice, class_names)
    return None


def _is_class_annotation(ann: ast.expr, class_names: set[str]) -> bool:
    """`C`, `C | None`, `None | C`, `Optional[C]`, or string forward ref `\"C | None\"`."""
    return _extract_class_name(ann, class_names) is not None


def _is_list_annotation(ann: ast.expr) -> bool:
    """`list[float]` or `list[int]`."""
    return (isinstance(ann, ast.Subscript) and isinstance(ann.value, ast.Name) and ann.value.id == "list"
            and isinstance(ann.slice, ast.Name) and ann.slice.id in LIST_ELEMENTS)


# tier S2a: the methods of a list (design 4.3.2)
LIST_METHODS = frozenset({"append", "insert", "extend", "clear", "reverse", "remove", "pop", "index", "count"})
# tier S2b: the dict methods (the front end checks the receiver is a dict)
DICT_METHODS = frozenset({"get", "setdefault", "pop", "keys", "values", "items", "copy", "clear", "update"})


def _list_class_name(ann: ast.expr | None, class_names) -> str | None:
    """Tier S2a: C of a `list[C]` annotation of a compiled class, else None."""
    if ann is None or not _is_class_list_annotation(ann, class_names):
        return None
    if isinstance(ann, ast.Constant) and isinstance(ann.value, str):
        ann = ast.parse(ann.value, mode="eval").body
    inner = ann.slice                                         # type: ignore[attr-defined]
    return inner.value if isinstance(inner, ast.Constant) else inner.id


def _is_class_list_annotation(ann: ast.expr, class_names) -> bool:
    """`list[C]` of a compiled class C (tier S2a), also with a quoted C or as a string."""
    if isinstance(ann, ast.Constant) and isinstance(ann.value, str):
        try:
            ann = ast.parse(ann.value, mode="eval").body
        except SyntaxError:
            return False
    if not (isinstance(ann, ast.Subscript) and isinstance(ann.value, ast.Name) and ann.value.id in ("list", "List")):
        return False
    inner = ann.slice
    if isinstance(inner, ast.Constant) and isinstance(inner.value, str):
        return inner.value in class_names
    return isinstance(inner, ast.Name) and inner.id in class_names


def _is_optional_str_annotation(ann: ast.expr) -> bool:
    """`str | None`, `None | str`, `Optional[str]` (tier S2a), also as a string."""
    if isinstance(ann, ast.Constant) and isinstance(ann.value, str):
        try:
            ann = ast.parse(ann.value, mode="eval").body
        except SyntaxError:
            return False
    text = ast.unparse(ann).replace(" ", "")
    return text in ("str|None", "None|str", "Optional[str]", "typing.Optional[str]")


def _is_dict_annotation(ann: ast.expr) -> bool:
    """`dict[K, V]` with K int, str or bool and V int, float, bool, str or `str | None` (tier S2b)."""
    if isinstance(ann, ast.Constant) and isinstance(ann.value, str):
        try:
            ann = ast.parse(ann.value, mode="eval").body
        except SyntaxError:
            return False
    if not (isinstance(ann, ast.Subscript) and isinstance(ann.value, ast.Name) and ann.value.id in ("dict", "Dict")
            and isinstance(ann.slice, ast.Tuple) and len(ann.slice.elts) == 2):
        return False
    k, v = ann.slice.elts
    return (isinstance(k, ast.Name) and k.id in ("int", "str", "bool")
            and ((isinstance(v, ast.Name) and v.id in ("int", "float", "bool", "str")) or _is_optional_str_annotation(v)))


def _is_str_list_annotation(ann: ast.expr) -> bool:
    """`list[str]` (tier S1b) and `list[str | None]` (tier S2a)."""
    return (isinstance(ann, ast.Subscript) and isinstance(ann.value, ast.Name) and ann.value.id == "list"
            and ((isinstance(ann.slice, ast.Name) and ann.slice.id == "str") or _is_optional_str_annotation(ann.slice)))


def _is_tuple_annotation(ann: ast.expr) -> bool:
    """`tuple[int, str, ...]` of at least one int, float, bool or str (no `...`; tier S2a adds str and the tuple as a
    parameter or local type, design 4.3.2)."""
    if not (isinstance(ann, ast.Subscript) and isinstance(ann.value, ast.Name) and ann.value.id == "tuple"):
        return False
    items = ann.slice.elts if isinstance(ann.slice, ast.Tuple) else [ann.slice]
    return bool(items) and all(isinstance(i, ast.Name) and (i.id in SCALAR_NAMES or i.id == "str") for i in items)


def _is_tuple_list_annotation(ann: ast.expr) -> bool:
    """`list[tuple[int, str, ...]]` (tier S2a; typedpython/tuples.py refuses a bool element, there is no list of bool
    yet)."""
    return (isinstance(ann, ast.Subscript) and isinstance(ann.value, ast.Name) and ann.value.id == "list"
            and _is_tuple_annotation(ann.slice))


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
    has_time = False
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
            elif only is not None and only.name == "time" and not has_time:
                has_time = True
            else:
                rep.error(s, "aot/import", ", ".join(a.name for a in s.names),
                          "only a single `import math`, `import sys` and `import time` are supported in the AOT mode")
        elif isinstance(s, ast.ImportFrom):
            rep.error(s, "aot/import", s.module or ".", "only a single `import math`, `import sys` and `import time` are "
                      "supported in the AOT mode")
        elif isinstance(s, ast.FunctionDef):
            program.functions.append(s)
            if s.name in seen_names:
                rep.error(s, "aot/redefinition", s.name, f"`{s.name}` is defined twice (first at line {seen_names[s.name]})")
            seen_names.setdefault(s.name, s.lineno)
        elif isinstance(s, ast.ClassDef):
            program.classes.append(s)
            if s.name in seen_names:
                rep.error(s, "aot/redefinition", s.name, f"`{s.name}` is defined twice (first at line {seen_names[s.name]})")
            seen_names.setdefault(s.name, s.lineno)
            for d in s.decorator_list:
                rep.error(d, "aot/decorator", ast.unparse(d), f"the decorator `@{ast.unparse(d)}` on class `{s.name}` "
                          "is outside the AOT mode")
            if s.keywords:
                rep.error(s, "aot/class", s.name, f"class `{s.name}` has a metaclass or class keyword")
            bases = [b for b in s.bases if not (isinstance(b, ast.Name) and b.id == "object")]
            if bases:
                rep.error(s, "aot/class", s.name, f"class `{s.name}` has a base class `{ast.unparse(bases[0])}` "
                          "(inheritance is outside PR-O1)")
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
        if n in BUILTINS_USED | {"math", "sys", "time"}:
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
    module_names = (names | set(program.consts) | ({"math"} if has_math else set())
                    | ({"sys"} if has_sys else set()) | ({"time"} if has_time else set()))
    if program.main is not None:
        for s in program.main.body:
            for n in ast.walk(s):
                if isinstance(n, ast.Name) and isinstance(n.ctx, (ast.Store, ast.Del)) and n.id in module_names:
                    rep.error(n, "aot/main-rebinds", n.id, f"the `__main__` block assigns `{n.id}`, a name the module "
                              "defines; the first AOT step keeps the block's names local")

    # -- classes and functions
    class_names = {c.name for c in program.classes}
    class_arity: dict[str, int] = {}
    class_fields: dict[str, set[str]] = {}
    class_methods: dict[str, set[str]] = {}
    method_return_class: dict[tuple[str, str], str] = {}
    field_class: dict[tuple[str, str], str] = {}
    for c in program.classes:
        fields: set[str] = set()
        methods: set[str] = set()
        for st in c.body:
            if isinstance(st, ast.AnnAssign) and isinstance(st.target, ast.Name):
                fields.add(st.target.id)
                fcls = _extract_class_name(st.annotation, class_names)
                if fcls is not None:
                    field_class[(c.name, st.target.id)] = fcls
            elif isinstance(st, ast.FunctionDef):
                if st.name != "__init__":
                    methods.add(st.name)
                    rcls = _extract_class_name(st.returns, class_names)
                    if rcls is not None:
                        method_return_class[(c.name, st.name)] = rcls
        init_fn = next((st for st in c.body if isinstance(st, ast.FunctionDef) and st.name == "__init__"), None)
        if init_fn is not None:
            class_arity[c.name] = max(0, len(init_fn.args.args) - 1)
            self_name = init_fn.args.args[0].arg if init_fn.args.args else "self"
            for node in ast.walk(init_fn):
                if isinstance(node, ast.Assign):
                    for t in node.targets:
                        if isinstance(t, ast.Attribute) and isinstance(t.value, ast.Name) and t.value.id == self_name:
                            fields.add(t.attr)
                elif isinstance(node, ast.AnnAssign):
                    t = node.target
                    if isinstance(t, ast.Attribute) and isinstance(t.value, ast.Name) and t.value.id == self_name:
                        fields.add(t.attr)
                        fcls = _extract_class_name(node.annotation, class_names)
                        if fcls is not None:
                            field_class.setdefault((c.name, t.attr), fcls)
        else:
            class_arity[c.name] = sum(1 for st in c.body if isinstance(st, ast.AnnAssign) and isinstance(st.target, ast.Name))
        class_fields[c.name] = fields
        class_methods[c.name] = methods
    func_return_class: dict[str, str] = {}
    func_return_list_class: dict[str, str] = {}
    for f in program.functions:
        rcls = _extract_class_name(f.returns, class_names)
        if rcls is not None:
            func_return_class[f.name] = rcls
        lcls = _list_class_name(f.returns, class_names)
        if lcls is not None:
            func_return_list_class[f.name] = lcls          # tier S2a: a function returning list[C]
    arity = {f.name: len(f.args.args) for f in program.functions}
    checker = _Checker(program, rep, arity, has_math, has_sys, has_time, library,
                       class_names=class_names, class_arity=class_arity,
                       class_fields=class_fields, class_methods=class_methods,
                       func_return_class=func_return_class,
                       method_return_class=method_return_class,
                       field_class=field_class)
    checker.func_return_list_class = func_return_list_class      # tier S2a
    for c in program.classes:
        for st in c.body:
            if isinstance(st, ast.AnnAssign) and isinstance(st.target, ast.Name):
                lc = _list_class_name(st.annotation, class_names)
                if lc is not None:
                    checker.field_list_class[(c.name, st.target.id)] = lc
    for c in program.classes:
        for st in c.body:
            if isinstance(st, ast.AnnAssign) and isinstance(st.target, ast.Name) and st.value is None:
                if _is_tuple_annotation(st.annotation):
                    continue                # tier S2a: one field per element (typedpython/tuples.py)
                checker.annotation(st.annotation, f"field `{c.name}.{st.target.id}`", st)
            elif isinstance(st, ast.FunctionDef):
                checker.function(f"{c.name}.{st.name}", st, st.body, is_method=True, owner_class=c.name)
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
