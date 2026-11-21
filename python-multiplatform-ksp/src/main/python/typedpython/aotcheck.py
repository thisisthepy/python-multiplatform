"""Check-only mode of the TypedPython AOT design (SPEC N-17, #446; docs/design/typedpython-aot.md
sections 4, 5 and 11). It builds nothing and changes nothing in the default mode.

Given an entry module it walks the program (the entry module and, transitively, the sibling `.py`
modules it imports) and reports

- **error**: a construct outside the AOT subset by design (`eval`, a generator, `id()`, an import
  outside the provided set, ...);
- **pending**, with a tier K / O / S / T: a construct inside the designed subset that the compiler
  cannot compile without CPython yet (`print`, a string method, a dict, ...);

and one status per function: `ready` when today's front end and verifier accept it and its IR needs
no CPython object operation.

Two independent sources:

A. An AST pass over every module. It does not use the front end, so every construct is reported, not
   only the first one per function. It is purely syntactic: it does not know types.
B. The front end and the verifier, run on copies of the sources with `@compiled` put above every
   top-level def and class (owner decision D6: an AOT build compiles every function; `@compiled` is
   only for options). The copies live in a temporary directory; the lines they report are mapped back
   to the original files.

Everything here is an agent proposal, not owner-confirmed (#446).
"""
from __future__ import annotations

import ast
import builtins
import dataclasses
import re
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

from typedpython import frontend, ir, resume, verify

PROVIDED_MODULES = frozenset({"math", "sys", "time"})

# The IR nodes that are generic C-API object operations today: each needs a CPython object, so a
# function containing one is not ready for a build without CPython. BinOp / UnaryOp / Compare whose
# operand or result type is OBJ are object operations too (`_object_op_kind`). Deliberately NOT in
# the list: Tuple (a fixed tuple in a Return), Box / Unbox, Global, Const of a str, New / FieldGet /
# FieldSet / CheckExact / IsExact of a compiled class, Is (a pointer comparison).
OBJECT_OPERATIONS: dict[type, str] = {
    ir.CallObject: "CallObject",       # PyObject_Vectorcall
    ir.GetAttr: "GetAttr",             # PyObject_GetAttr
    ir.GetItem: "GetItem",             # PyObject_GetItem
    ir.StoreItem: "StoreItem",         # PyObject_SetItem
    ir.Contains: "Contains",           # PySequence_Contains
    ir.NewDict: "NewDict",             # PyDict_New
    ir.NewList: "NewList",             # PyList_New
    ir.ForObj: "ForObj",               # PyObject_GetIter / tp_iternext
    ir.Format: "Format",               # PyObject_Format
    ir.BuildString: "BuildString",     # PyUnicode_Join
    ir.CompareObj: "CompareObj",       # PyObject_RichCompare
    ir.ObjToFloat: "ObjToFloat",       # PyNumber_Float
    ir.Truth: "Truth",                 # PyObject_IsTrue (not named by the inventory; same kind of call)
}

_DYNAMIC_CALLS = frozenset({"eval", "exec", "compile", "__import__", "globals", "locals", "vars"})
_INTROSPECTION_CALLS = frozenset({"getattr", "setattr", "delattr", "hasattr"})
_INTROSPECTION_ATTRS = frozenset({"__dict__", "__class__", "__slots__", "__bases__", "__mro__"})
_KERNEL_BUILTINS = frozenset()
_SET_BUILTINS_S = frozenset({"sorted", "sum", "any", "all", "enumerate", "zip", "reversed", "ord", "chr", "repr", "str", "format"})
_CONTAINER_CALLS = frozenset({"dict", "set", "tuple", "bytes"})
_SUPPORTED_CALLS = frozenset({"len", "range", "float", "bool", "isinstance", "type", "list", "print", "int", "abs", "min", "max"})
_SIGNAL_EXCEPTIONS = frozenset({"KeyboardInterrupt", "RecursionError"})
_COMPILED = "compiled"


# --- the report ----------------------------------------------------------------------------------

@dataclass(frozen=True)
class Finding:
    path: str                  # relative to the entry module's directory, posix
    line: int                  # in the original (undecorated) file
    kind: str                  # "error" | "pending"
    tier: str | None           # None for an error; "K", "O", "S" or "T" for pending
    rule: str
    name: str                  # the construct or symbol
    message: str
    function: str | None       # enclosing top-level function or class, None at module level


@dataclass(frozen=True)
class FunctionStatus:
    path: str
    line: int
    name: str                  # `f`, `C` or `C.m`
    ready: bool
    reasons: list[str]


@dataclass
class Report:
    findings: list[Finding] = field(default_factory=list)
    functions: list[FunctionStatus] = field(default_factory=list)

    @property
    def blocking(self) -> bool:
        return any(f.kind in ("error", "pending") for f in self.findings)

    def to_json(self) -> dict[str, object]:
        return {"findings": [dataclasses.asdict(f) for f in self.findings],
                "functions": [dataclasses.asdict(f) for f in self.functions]}

    def lines(self) -> list[str]:
        out = []
        for f in self.findings:
            kind = f.kind if f.tier is None else f"{f.kind}/{f.tier}"
            out.append(f"{f.path}:{f.line}: {kind} {f.rule}: {f.message}")
        for s in self.functions:
            out.append(f"{s.path}:{s.line}: function {s.name}: "
                       + ("ready" if s.ready else "not ready" + (f" ({'; '.join(s.reasons)})" if s.reasons else "")))
        return out


# --- the program ---------------------------------------------------------------------------------

@dataclass
class _Module:
    path: Path
    rel: str
    source: str
    tree: ast.Module | None
    error: str | None = None
    error_line: int = 1


def _imported_names(tree: ast.Module) -> list[str]:
    """Names of modules a plain `import m` / `from m import ...` mentions (any depth, absolute only)."""
    out = []
    for n in ast.walk(tree):
        if isinstance(n, ast.Import):
            out.extend(a.name for a in n.names)
        elif isinstance(n, ast.ImportFrom) and n.level == 0 and n.module:
            out.append(n.module)
    return out


def _load_program(entry: Path) -> list[_Module]:
    root = entry.parent
    modules: list[_Module] = []
    seen: set[Path] = set()
    queue = [entry]
    while queue:
        path = queue.pop(0)
        if path in seen:
            continue
        seen.add(path)
        source = path.read_text()
        rel = path.relative_to(root).as_posix()
        try:
            tree = ast.parse(source, filename=str(path))
        except SyntaxError as e:
            modules.append(_Module(path, rel, source, None, f"syntax error: {e.msg}", e.lineno or 1))
            continue
        modules.append(_Module(path, rel, source, tree))
        for name in _imported_names(tree):
            candidate = root / f"{name}.py"
            if "." not in name and name not in PROVIDED_MODULES and candidate.is_file():
                queue.append(candidate.resolve())
    return modules


def _project_module(name: str, root: Path) -> bool:
    return "." not in name and name not in PROVIDED_MODULES and (root / f"{name}.py").is_file()


# --- source A: the AST pass ----------------------------------------------------------------------

def _is_compiled_decorator(d: ast.expr) -> bool:
    if isinstance(d, ast.Call):
        d = d.func
    return isinstance(d, ast.Name) and d.id == _COMPILED


def _literal(node: ast.expr) -> bool:
    """An int, float or str literal (a negative number included)."""
    if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.USub, ast.UAdd)):
        node = node.operand
    return isinstance(node, ast.Constant) and type(node.value) in (int, float, str)


def _annotation_kind(annotation: ast.expr | None) -> str | None:
    if isinstance(annotation, ast.Subscript):
        annotation = annotation.value
    if isinstance(annotation, ast.Name) and annotation.id in ("str", "set"):
        return annotation.id
    return None


def _constant_expr(node: ast.expr, bound: set[str]) -> bool:
    if isinstance(node, ast.Constant):
        return True
    if isinstance(node, ast.UnaryOp):
        return _constant_expr(node.operand, bound)
    if isinstance(node, ast.BinOp):
        return _constant_expr(node.left, bound) and _constant_expr(node.right, bound)
    if isinstance(node, ast.Name):
        return node.id in bound
    return (isinstance(node, ast.Attribute) and isinstance(node.value, ast.Name)
            and node.value.id == "math")


def _is_main_guard(node: ast.stmt) -> bool:
    if not isinstance(node, ast.If):
        return False
    t = node.test
    return (isinstance(t, ast.Compare) and isinstance(t.left, ast.Name) and t.left.id == "__name__"
            and len(t.ops) == 1 and isinstance(t.ops[0], ast.Eq)
            and isinstance(t.comparators[0], ast.Constant) and t.comparators[0].value == "__main__")


def _targets_names(node: ast.expr) -> list[str]:
    if isinstance(node, ast.Name):
        return [node.id]
    if isinstance(node, (ast.Tuple, ast.List)):
        return [n for e in node.elts for n in _targets_names(e)]
    return []


def _module_bound(tree: ast.Module) -> set[str]:
    """Names bound at module level (not inside a def): defs, classes, imports, assignment targets."""
    bound: set[str] = set()

    def visit(stmts: list[ast.stmt]) -> None:
        for s in stmts:
            if isinstance(s, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                bound.add(s.name)
            elif isinstance(s, ast.Import):
                bound.update((a.asname or a.name.split(".")[0]) for a in s.names)
            elif isinstance(s, ast.ImportFrom):
                bound.update((a.asname or a.name) for a in s.names)
            elif isinstance(s, ast.Assign):
                for t in s.targets:
                    bound.update(_targets_names(t))
            elif isinstance(s, (ast.AnnAssign, ast.AugAssign)):
                bound.update(_targets_names(s.target))
            elif isinstance(s, ast.For):
                bound.update(_targets_names(s.target))
            for name in ("body", "orelse", "finalbody"):
                inner = getattr(s, name, None)
                if isinstance(inner, list) and inner and not isinstance(s, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                    visit(inner)

    visit(tree.body)
    return bound


def _scope_names(fn: ast.AST) -> set[str]:
    """Every name a def binds anywhere inside it (parameters included): they shadow builtins."""
    names: set[str] = set()
    for n in ast.walk(fn):
        if isinstance(n, ast.arg):
            names.add(n.arg)
        elif isinstance(n, ast.Name) and isinstance(n.ctx, (ast.Store, ast.Del)):
            names.add(n.id)
        elif isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            names.add(n.name)
        elif isinstance(n, ast.Import):
            names.update((a.asname or a.name.split(".")[0]) for a in n.names)
        elif isinstance(n, ast.ImportFrom):
            names.update((a.asname or a.name) for a in n.names)
        elif isinstance(n, ast.ExceptHandler) and n.name:
            names.add(n.name)
    return names


def _annotated(fn: ast.AST) -> dict[str, str]:
    """name -> "str" | "set" for parameters and variables annotated so inside `fn`."""
    out: dict[str, str] = {}
    for n in ast.walk(fn):
        if isinstance(n, ast.arg) and (k := _annotation_kind(n.annotation)):
            out[n.arg] = k
        elif isinstance(n, ast.AnnAssign) and isinstance(n.target, ast.Name) and (k := _annotation_kind(n.annotation)):
            out[n.target.id] = k
    return out


class _Pass(ast.NodeVisitor):
    def __init__(self, module: _Module, root: Path, program_classes: set[str]) -> None:
        self.m = module
        self.root = root
        self.program_classes = program_classes
        self.findings: list[Finding] = []
        assert module.tree is not None
        self.bound = _module_bound(module.tree)
        self.func: str | None = None
        self.depth = 0                      # enclosing defs
        self.locals: set[str] = set()
        self.print_fstrings: set[int] = set()
        self.kinds: dict[str, str] = {}     # annotated str / set names of the enclosing top-level def

    # -- helpers

    def add(self, kind: str, tier: str | None, rule: str, name: str, node: ast.AST, message: str) -> None:
        self.findings.append(Finding(self.m.rel, getattr(node, "lineno", 1), kind, tier, rule, name,
                                     message, self.func))

    def error(self, rule: str, name: str, node: ast.AST, message: str) -> None:
        self.add("error", None, rule, name, node, message)

    def pending(self, tier: str, rule: str, name: str, node: ast.AST, message: str) -> None:
        self.add("pending", tier, rule, name, node, message)

    def builtin(self, name: str) -> bool:
        return name not in self.bound and name not in self.locals and hasattr(builtins, name)

    # -- module level

    def run(self) -> list[Finding]:
        tree = self.m.tree
        assert tree is not None
        for s in tree.body:
            self.module_statement(s)
        return self.findings

    def module_statement(self, s: ast.stmt) -> None:
        if isinstance(s, (ast.Import, ast.ImportFrom, ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            self.visit(s)
        elif isinstance(s, ast.Expr) and isinstance(s.value, ast.Constant) and isinstance(s.value.value, str):
            return                                              # a docstring
        elif _is_main_guard(s):
            assert isinstance(s, ast.If)
            for b in s.body + s.orelse:
                self.visit(b)
        elif (isinstance(s, ast.Assign) and all(isinstance(t, ast.Name) for t in s.targets)
              and _constant_expr(s.value, self.bound)) or (
                isinstance(s, ast.AnnAssign) and isinstance(s.target, ast.Name)
                and (s.value is None or _constant_expr(s.value, self.bound))):
            return                                              # a constant assigned to a name
        else:
            self.pending("K", "aot/builtin", type(s).__name__, s,
                         f"a module-level `{type(s).__name__}` statement is not an import, def, class, docstring, "
                         "constant assignment or entry guard, and is not compiled without CPython yet")
            self.visit(s)

    # -- imports

    def visit_Import(self, node: ast.Import) -> None:
        for a in node.names:
            self.import_of(a.name, node, node.names)

    def visit_ImportFrom(self, node: ast.ImportFrom) -> None:
        if node.level:
            self.error("aot/import", "." * node.level + (node.module or ""), node,
                       "a relative import is outside the AOT program model (the program is the entry module "
                       "and the sibling modules it imports by name)")
            return
        name = node.module or ""
        if any(a.name == "*" for a in node.names):
            self.error("aot/import", name, node, f"`from {name} import *` binds names the checker cannot see")
        self.import_of(name, node, None)

    def import_of(self, name: str, node: ast.stmt, _names: object) -> None:
        top = name.split(".")[0]
        if self.depth:
            self.error("aot/import", name, node, f"an import of `{name}` inside a function runs at call time, "
                                                   "which the AOT program model does not have")
        if top == "numpy" or top == "array":
            self.error("aot/numpy", name, node, f"`{name}` buffers are CPython objects, so they are not part of an AOT program")
        elif top == "importlib":
            self.error("aot/dynamic-code", name, node, "`importlib` loads code at run time")
        elif top == "threading":
            self.pending("T", "aot/threading", name, node, "`threading` is tier T (threads), not compiled yet")
        elif top in PROVIDED_MODULES and "." not in name:
            return
        elif _project_module(name, self.root):
            return
        elif not self.depth:
            self.error("aot/import", name, node, f"`{name}` is neither a module of this program nor one of the "
                                                   "provided standard modules (math, sys, time)")

    # -- definitions

    def visit_FunctionDef(self, node: ast.FunctionDef) -> None:
        self.definition(node, is_async=False)

    def visit_AsyncFunctionDef(self, node: ast.AsyncFunctionDef) -> None:
        self.error("aot/async", "async def", node, "`async def` is outside the AOT subset (no event loop)")
        self.definition(node, is_async=True)

    def definition(self, node: ast.FunctionDef | ast.AsyncFunctionDef, is_async: bool) -> None:
        if self.depth >= 1:
            self.error("aot/closure", "def", node, f"a `def {node.name}` nested in a function is a closure, "
                                                    "which is outside the AOT subset")
        self.decorators(node)
        a = node.args
        if a.vararg:
            self.error("aot/star-args", f"*{a.vararg.arg}", a.vararg, "`*args` parameters are outside the AOT subset")
        if a.kwarg:
            self.error("aot/star-args", f"**{a.kwarg.arg}", a.kwarg, "`**kwargs` parameters are outside the AOT subset")
        saved = (self.func, self.locals, self.kinds)
        if self.func is None:
            self.func = node.name
            self.kinds = _annotated(node)
        else:
            self.kinds = {**self.kinds, **_annotated(node)}
        self.locals = self.locals | _scope_names(node)
        self.depth += 1
        for d in a.defaults + [d for d in a.kw_defaults if d is not None]:
            self.visit(d)
        for s in node.body:
            self.visit(s)
        self.depth -= 1
        self.func, self.locals, self.kinds = saved

    def decorators(self, node: ast.FunctionDef | ast.AsyncFunctionDef | ast.ClassDef) -> None:
        for d in node.decorator_list:
            if not _is_compiled_decorator(d):
                self.error("aot/decorator", ast.unparse(d), d,
                           f"the decorator `@{ast.unparse(d)}` is outside the AOT subset (only `@compiled`)")

    def visit_ClassDef(self, node: ast.ClassDef) -> None:
        self.decorators(node)
        for b in node.bases:
            if not (isinstance(b, ast.Name) and b.id == "object"):
                self.pending("S", "aot/inheritance", ast.unparse(b), b,
                             f"the base class `{ast.unparse(b)}` needs inheritance, a later tier (S), "
                             "not compiled yet")
        for k in node.keywords:
            self.error("aot/class-shape", k.arg or "**", k.value,
                       f"the class keyword `{k.arg or '**'}=` (a metaclass or class option) is outside the AOT subset")
        saved = (self.func, self.locals, self.kinds)
        if self.func is None:
            self.func = node.name
            self.kinds = {}
        for s in node.body:
            self.visit(s)
        self.func, self.locals, self.kinds = saved

    # -- statements

    def visit_Global(self, node: ast.Global) -> None:
        self.error("aot/global", ", ".join(node.names), node, "a `global` statement rebinds a module name from inside a function")

    def visit_Nonlocal(self, node: ast.Nonlocal) -> None:
        self.error("aot/closure", "nonlocal", node, "`nonlocal` needs a closure, which is outside the AOT subset")

    def visit_With(self, node: ast.With) -> None:
        self.error("aot/with", "with", node, "`with` runs a context manager's methods, which is outside the AOT subset")
        self.generic_visit(node)

    def visit_AsyncWith(self, node: ast.AsyncWith) -> None:
        self.error("aot/async", "async with", node, "`async with` is outside the AOT subset (no event loop)")
        self.error("aot/with", "with", node, "`with` runs a context manager's methods, which is outside the AOT subset")
        self.generic_visit(node)

    def visit_AsyncFor(self, node: ast.AsyncFor) -> None:
        self.error("aot/async", "async for", node, "`async for` is outside the AOT subset (no event loop)")
        self.set_order(node.iter)
        self.generic_visit(node)

    def visit_Match(self, node: ast.Match) -> None:
        self.error("aot/match", "match", node, "`match` is outside the AOT subset")
        self.generic_visit(node)

    def visit_Raise(self, node: ast.Raise) -> None:
        self.pending("S", "aot/exception", "raise", node, "`raise` is tier S (exceptions), not compiled yet")
        self.generic_visit(node)

    def visit_Try(self, node: ast.Try) -> None:
        self.pending("S", "aot/exception", "try", node, "`try` is tier S (exceptions), not compiled yet")
        self.generic_visit(node)

    visit_TryStar = visit_Try

    def visit_Assert(self, node: ast.Assert) -> None:
        self.pending("S", "aot/exception", "assert", node, "`assert` is tier S (exceptions), not compiled yet")
        self.generic_visit(node)

    def visit_ExceptHandler(self, node: ast.ExceptHandler) -> None:
        t = node.type
        for n in (t.elts if isinstance(t, ast.Tuple) else [t]):
            if isinstance(n, ast.Name) and n.id in _SIGNAL_EXCEPTIONS:
                self.error("aot/signal", n.id, n, f"`except {n.id}` observes a CPython runtime condition "
                                                   "(owner decision D4: Ctrl-C and the recursion limit differ)")
        self.generic_visit(node)

    def class_attribute_store(self, target: ast.expr) -> None:
        if (isinstance(target, ast.Attribute) and isinstance(target.value, ast.Name)
                and target.value.id in self.program_classes and target.value.id not in self.locals):
            self.error("aot/class-shape", f"{target.value.id}.{target.attr}", target,
                       f"assigning the attribute `{target.attr}` of the class `{target.value.id}` changes its layout "
                       "after the class body")

    def visit_Assign(self, node: ast.Assign) -> None:
        for t in node.targets:
            self.class_attribute_store(t)
        self.generic_visit(node)

    def visit_AugAssign(self, node: ast.AugAssign) -> None:
        self.class_attribute_store(node.target)
        self.generic_visit(node)

    def visit_AnnAssign(self, node: ast.AnnAssign) -> None:
        self.class_attribute_store(node.target)
        if node.value is not None:
            self.visit(node.value)

    def visit_For(self, node: ast.For) -> None:
        self.set_order(node.iter)
        self.generic_visit(node)

    def set_order(self, it: ast.expr) -> None:
        what = None
        if isinstance(it, ast.Set):
            what = "a set display"
        elif isinstance(it, ast.Call) and isinstance(it.func, ast.Name) and it.func.id == "set" and self.builtin("set"):
            what = "a `set(...)` call"
        elif isinstance(it, ast.Name) and self.kinds.get(it.id) == "set":
            what = f"the name `{it.id}` annotated `set`"
        if what:
            self.error("aot/set-order", "set", it,
                       f"iterating {what} gives an order that depends on CPython's hash table (syntactic check: "
                       "only a set display, a `set(...)` call or a name annotated `set` is recognised)")

    # -- expressions

    def visit_Lambda(self, node: ast.Lambda) -> None:
        self.error("aot/closure", "lambda", node, "`lambda` makes a closure, which is outside the AOT subset")
        self.generic_visit(node)

    def visit_Yield(self, node: ast.Yield) -> None:
        self.error("aot/generator", "yield", node, "`yield` makes a generator, which is outside the AOT subset")
        self.generic_visit(node)

    def visit_YieldFrom(self, node: ast.YieldFrom) -> None:
        self.error("aot/generator", "yield from", node, "`yield from` makes a generator, which is outside the AOT subset")
        self.generic_visit(node)

    def visit_Await(self, node: ast.Await) -> None:
        self.error("aot/async", "await", node, "`await` is outside the AOT subset (no event loop)")
        self.generic_visit(node)

    def comprehension(self, node: ast.expr, name: str) -> None:
        for g in node.generators:                       # type: ignore[attr-defined]
            self.set_order(g.iter)
        self.generic_visit(node)

    def visit_GeneratorExp(self, node: ast.GeneratorExp) -> None:
        self.error("aot/generator", "generator expression", node, "a generator expression is lazy, which is outside the AOT subset")
        self.comprehension(node, "generator expression")

    def visit_ListComp(self, node: ast.ListComp) -> None:
        self.pending("S", "aot/container", "list comprehension", node, "a list comprehension is tier S (general containers), not compiled yet")
        self.comprehension(node, "list comprehension")

    def visit_SetComp(self, node: ast.SetComp) -> None:
        self.pending("S", "aot/container", "set comprehension", node, "a set comprehension is tier S (general containers), not compiled yet")
        self.comprehension(node, "set comprehension")

    def visit_DictComp(self, node: ast.DictComp) -> None:
        self.pending("S", "aot/container", "dict comprehension", node, "a dict comprehension is tier S (general containers), not compiled yet")
        self.comprehension(node, "dict comprehension")

    def visit_Dict(self, node: ast.Dict) -> None:
        self.pending("S", "aot/container", "dict display", node, "a dict display is tier S (general containers), not compiled yet")
        self.generic_visit(node)

    def visit_JoinedStr(self, node: ast.JoinedStr) -> None:
        if id(node) not in self.print_fstrings:
            self.pending("S", "aot/str", "f-string", node, "an f-string outside `print()` is tier S (strings), not compiled yet")
        self.generic_visit(node)

    def visit_FormattedValue(self, node: ast.FormattedValue) -> None:
        self.visit(node.value)                  # the format spec is part of the same f-string
        if node.format_spec is not None:
            for v in node.format_spec.values:
                if isinstance(v, ast.FormattedValue):
                    self.visit_FormattedValue(v)

    def visit_Compare(self, node: ast.Compare) -> None:
        operands = [node.left, *node.comparators]
        for i, op in enumerate(node.ops):
            if isinstance(op, (ast.Is, ast.IsNot)) and (_literal(operands[i]) or _literal(operands[i + 1])):
                self.error("aot/identity", "is" if isinstance(op, ast.Is) else "is not", node,
                           "`is` against an int, float or str literal depends on CPython's object caching")
        self.generic_visit(node)

    def visit_Attribute(self, node: ast.Attribute) -> None:
        if node.attr in _INTROSPECTION_ATTRS:
            self.error("aot/introspection", node.attr, node, f"`.{node.attr}` reads the object model at run time")
        self.generic_visit(node)

    def visit_Call(self, node: ast.Call) -> None:
        f = node.func
        if any(isinstance(a, ast.Starred) for a in node.args):
            self.error("aot/star-args", "*", node, "a `*` argument is outside the AOT subset")
        if any(k.arg is None for k in node.keywords):
            self.error("aot/star-args", "**", node, "a `**` argument is outside the AOT subset")
        if isinstance(f, ast.Name) and self.builtin(f.id):
            if f.id == "print":
                for a in node.args:
                    if isinstance(a, ast.JoinedStr):
                        self.print_fstrings.add(id(a))
            self.builtin_call(f.id, node)
        elif isinstance(f, ast.Attribute):
            if (isinstance(f.value, ast.Constant) and isinstance(f.value.value, str)) or (
                    isinstance(f.value, ast.Name) and self.kinds.get(f.value.id) == "str"):
                self.pending("S", "aot/str", f.attr, node, f"the string method `{f.attr}` is tier S (strings), not compiled yet "
                                                          "(syntactic: a str literal or a name annotated `str`)")
        self.generic_visit(node)

    def builtin_call(self, n: str, node: ast.Call) -> None:
        if n in _DYNAMIC_CALLS:
            self.error("aot/dynamic-code", n, node, f"`{n}()` runs or inspects code at run time")
        elif n == "id":
            self.error("aot/identity", "id", node, "`id()` returns an address that depends on the CPython heap")
        elif n == "hash":
            self.error("aot/identity", "hash", node,
                       "`hash()` is reported for any argument because the checker cannot type it: the hash of an "
                       "object is its address and that of a str depends on the per-process seed")
        elif n in _INTROSPECTION_CALLS:
            self.error("aot/introspection", n, node, f"`{n}()` looks up an attribute by a run-time name")
        elif n == "type":
            if len(node.args) == 3:
                self.error("aot/introspection", "type", node, "`type(name, bases, dict)` builds a class at run time")
        elif n in _KERNEL_BUILTINS:
            self.pending("K", "aot/builtin", n, node, f"`{n}()` is not compiled without CPython yet (tier K)")
        elif n in _SET_BUILTINS_S:
            self.pending("S", "aot/builtin", n, node, f"`{n}()` is tier S, not compiled yet")
        elif n in _CONTAINER_CALLS:
            self.pending("S", "aot/container", n, node, f"`{n}()` is tier S (general containers), not compiled yet")
        elif n in _SUPPORTED_CALLS:
            return
        elif isinstance(getattr(builtins, n), type) and issubclass(getattr(builtins, n), BaseException):
            return
        else:
            self.error("aot/builtin-unavailable", n, node, f"the builtin `{n}` is not in the provided set")


# --- source B: the front end and the verifier ----------------------------------------------------

def _first_line(node: ast.stmt) -> int:
    return min([node.lineno] + [d.lineno for d in getattr(node, "decorator_list", [])])


def _decorate(source: str, tree: ast.Module) -> tuple[str, list[int]]:
    """`source` with `@compiled` above every top-level def / class that has none (as
    `conftest.decorate_compiled`), and the original first lines the decorators were put above."""
    firsts = [_first_line(n) for n in tree.body
              if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef))
              and not any(_is_compiled_decorator(d) for d in n.decorator_list)]
    lines = source.split("\n")
    for first in reversed(firsts):
        lines.insert(first - 1, "@compiled")
    return "\n".join(lines), firsts


def _original_line(decorated: int, firsts: list[int]) -> int:
    """The original line of a line of the decorated copy. The k-th inserted line (0-based) sits at
    `firsts[k] + k`; a line after it is shifted by the number of insertions at or before it."""
    shift = sum(1 for k, f in enumerate(firsts) if f + k <= decorated)
    return max(1, decorated - shift)


_LINE = re.compile(r"\b(lines?) (\d+(?:, \d+)*)")


def _remap_text(text: str, firsts: list[int]) -> str:
    def sub(m: re.Match) -> str:
        nums = ", ".join(str(_original_line(int(n), firsts)) for n in m.group(2).split(", "))
        return f"{m.group(1)} {nums}"
    return _LINE.sub(sub, text)


def _object_op_kind(x: object) -> str | None:
    kind = OBJECT_OPERATIONS.get(type(x))
    if kind:
        return kind
    if isinstance(x, ir.BinOp) and ir.Type.OBJ in (x.left.type, x.right.type, x.type):
        return "BinOp[OBJ]"
    if isinstance(x, ir.UnaryOp) and ir.Type.OBJ in (x.operand.type, x.type):
        return "UnaryOp[OBJ]"
    if isinstance(x, ir.Compare) and ir.Type.OBJ in (x.left.type, x.right.type):
        return "Compare[OBJ]"
    return None


def _walk_ir(fn: ir.Function, line_of, visit) -> None:
    """Call `visit(node, line)` on every statement and expression of `fn.body`; `line` is the
    decorated-copy line of the innermost source statement known for it."""
    def walk(x: object, line: int) -> None:
        if isinstance(x, (tuple, list)):
            for y in x:
                walk(y, line)
        elif dataclasses.is_dataclass(x) and not isinstance(x, type):
            if isinstance(x, ir.Stmt) and x.stmt is not None:
                line = line_of(x.stmt, line)
            visit(x, line)
            for f in dataclasses.fields(x):
                walk(getattr(x, f.name), line)

    walk(fn.body, fn.source_line)


def _unproven_i64(x: object) -> str | None:
    from typedpython import cgen          # imported here: only the strict mode needs it
    if isinstance(x, ir.BinOp) and x.left.type is ir.Type.I64 and x.op in cgen._I64_DEOPT and not x.proven:
        return x.op.value
    if (isinstance(x, ir.UnaryOp) and x.op is ir.UnaryOpKind.NEG and x.operand.type is ir.Type.I64
            and not x.proven):
        return "-"
    return None


def _definitions(tree: ast.Module) -> list[tuple[str, ast.FunctionDef | ast.AsyncFunctionDef | ast.ClassDef | ast.If, str | None]]:
    """(status name, node, enclosing top-level name) for each top-level def, class, method and __main__."""
    out: list = []
    for n in tree.body:
        if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)):
            out.append((n.name, n, None))
        elif isinstance(n, ast.ClassDef):
            out.append((n.name, n, None))
            for s in n.body:
                if isinstance(s, (ast.FunctionDef, ast.AsyncFunctionDef)):
                    out.append((f"{n.name}.{s.name}", s, n.name))
        elif _is_main_guard(n):
            out.append(("__main__", n, None))
    return out


def _front_end(modules: list[_Module], strict_int: bool, report: Report) -> None:
    with tempfile.TemporaryDirectory(prefix="typedpython-aot-") as work:
        for m in modules:
            if m.tree is None:
                continue
            firsts: list[int] = []
            text = m.source
            copy = Path(work) / m.rel
            copy.parent.mkdir(parents=True, exist_ok=True)
            copy.write_text(text)
            defs = _definitions(m.tree)
            try:
                lowered = frontend.lower(copy, aot=True)
                proved, diagnostics = verify.verify(lowered, aot=True)
            except Exception as e:      # noqa: BLE001 - one module failing must not hide the rest
                lines = str(e).strip().splitlines()
                text_ = f"{type(e).__name__}: {lines[0] if lines else ''}"
                report.findings.append(Finding(m.rel, 1, "error", None, "aot/front-end", type(e).__name__,
                                               f"the front end failed on this module ({text_})", None))
                for name, node, _ in defs:
                    report.functions.append(FunctionStatus(m.rel, node.lineno, name, False, ["the front end failed on the module"]))
                continue
            _module_status(m, firsts, text, defs, lowered, proved, diagnostics, strict_int, report)


def _module_status(m: _Module, firsts: list[int], text: str, defs: list, lowered: ir.Module,
                   proved: ir.Module, diagnostics: list, strict_int: bool, report: Report) -> None:
    from types import SimpleNamespace

    decorated = ast.parse(text)
    by_name = {name: node for name, node, _ in _definitions(decorated)}
    lowered_names = {f.name for f in lowered.functions}
    proved_fns = {f.name: f for f in proved.functions}
    class_ok = {c.name for c in proved.classes}
    for name, node, owner in defs:
        if isinstance(node, ast.ClassDef):
            if name in class_ok:
                report.functions.append(FunctionStatus(m.rel, node.lineno, name, True, []))
            else:
                why = proved.skipped.get(name) or lowered.skipped.get(name) or "the front end did not accept the class"
                report.functions.append(FunctionStatus(m.rel, node.lineno, name, False, [_remap_text(why, firsts)]))
            continue
        ir_name = frontend.AOT_ENTRY if name == "__main__" else name
        reasons: list[str] = []
        fn = proved_fns.get(ir_name)
        if fn is None:
            if ir_name in lowered_names:
                mine = [d for d in diagnostics if d.function == ir_name]
                reasons = [_remap_text(f"{d.rule}: {d.message} (line {d.source_line})", firsts) for d in mine]
                reasons = reasons or [_remap_text(proved.skipped.get(ir_name, "the verifier rejected it"), firsts)]
            else:
                reasons = [_remap_text(lowered.skipped.get(ir_name) or _missing_reason(ir_name, owner, lowered, proved), firsts)]
            report.functions.append(FunctionStatus(m.rel, node.lineno, name, False, reasons))
            continue
        decorated_fn = by_name.get(name)
        if isinstance(decorated_fn, ast.FunctionDef):
            stmts = resume.statements(decorated_fn)
        elif isinstance(decorated_fn, ast.If):
            stmts = resume.statements(SimpleNamespace(body=decorated_fn.body))
        else:
            stmts = []

        def line_of(k: int, fallback: int, _stmts=stmts) -> int:
            return _stmts[k].lineno if 0 <= k < len(_stmts) else fallback

        ops: dict[str, list[int]] = {}
        unproven: list[tuple[str, int]] = []

        def visit(x: object, line: int, _ops=ops, _un=unproven) -> None:
            kind = _object_op_kind(x)
            if kind:
                _ops.setdefault(kind, []).append(_original_line(line, firsts))
            if strict_int and (sym := _unproven_i64(x)):
                _un.append((sym, _original_line(line, firsts)))

        _walk_ir(fn, line_of, visit)
        for kind, lines in ops.items():
            reasons.append(f"object operation {kind} at line {', '.join(str(n) for n in sorted(set(lines)))}")
        report.functions.append(FunctionStatus(m.rel, node.lineno, name, not ops, reasons))
        for sym, line in sorted(set(unproven), key=lambda t: (t[1], t[0])):
            report.findings.append(Finding(
                m.rel, line, "error", None, "aot/int-unproven", sym,
                f"the int operation `{sym}` is not proved to stay inside i64, so a strict build refuses it",
                owner or name))


def _missing_reason(name: str, owner: str | None, lowered: ir.Module, proved: ir.Module) -> str:
    if owner is not None:
        why = proved.skipped.get(owner) or lowered.skipped.get(owner)
        if why:
            return f"its class `{owner}` is not compiled: {why}"
    return "the front end did not lower it"


# --- entry points --------------------------------------------------------------------------------

def check(entry: Path, strict_int: bool = False, *, front_end: bool = True) -> Report:
    """Check the program whose entry module is `entry`. `front_end=False` runs only the AST pass
    (no Pyrefly, no function statuses); the default runs both sources."""
    entry = Path(entry).resolve()
    root = entry.parent
    modules = _load_program(entry)
    program_classes = {n.name for m in modules if m.tree is not None
                       for n in m.tree.body if isinstance(n, ast.ClassDef)}
    report = Report()
    for m in modules:
        if m.tree is None:
            report.findings.append(Finding(m.rel, m.error_line, "error", None, "aot/front-end", "SyntaxError",
                                           f"the module does not parse ({m.error})", None))
            continue
        report.findings.extend(sorted(_Pass(m, root, program_classes).run(),
                                      key=lambda f: (f.line, f.rule, f.name)))
    if front_end:
        _front_end(modules, strict_int, report)
    return report
