"""The program of an AOT build, read from source (SPEC N-17, #448; agent proposal, not owner-confirmed).

`analyze(entry)` reads the entry module and does what needs no types:

1. checks the shape of the program (module level, imports, `print`, the statements and expressions a
   function may hold) and collects findings in the original file's lines;
2. folds the module constants (`NAME = <constant expression>`) with Python's own arithmetic;
3. writes the text the front end is given. The front end is the default mode's, which compiles a
   function it can prove pure and keeps it as an object function otherwise, so the text is shaped to be
   pure and every edit keeps the line count and the line of everything it touches:
   - the `if __name__ == "__main__":` header becomes `def __tp_main() -> None` and the block is its
     body (the front end lowers the entry like any function);
   - each module constant read in a function is replaced by its literal (a recursive function that
     reads a global would otherwise read it as an object, and the literal also gives the interval proof
     a value);
   - each `print(...)` statement calls a stub `__tp_print_<k>(a0: object, ...)` with `pass` as body,
     appended at the end of the file: a call of a pure function keeps the caller pure, so the integer
     arithmetic in its arguments is proved exactly as in any function (an impure function's ints are
     `INT`, which the front end never proves). A string literal argument is replaced by `0` and
     remembered. `emit` turns each call of a stub back into the print.
"""
from __future__ import annotations

import ast
import builtins
import math
from dataclasses import dataclass, field
from pathlib import Path
from types import SimpleNamespace

from typedpython.aotcheck import Finding

I64_MIN, I64_MAX = -2**63, 2**63 - 1
MATH_FUNCTIONS = {"sqrt": 1, "exp": 1, "log": 1, "sin": 1, "cos": 1, "tan": 1, "fabs": 1, "atan2": 2, "hypot": 2}
SCALAR_NAMES = ("int", "float", "bool")
RESERVED_PREFIX = "__tp_"
MAIN = "__tp_main"
PRINT_PREFIX = "__tp_print_"

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
class PrintSite:
    """One `print(...)` statement: per argument, None for an expression the stub receives, else the
    string literal."""

    index: int
    line: int
    template: list[str | None]

    @property
    def stub(self) -> str:
        return f"{PRINT_PREFIX}{self.index}"


@dataclass
class Program:
    path: Path
    rel: str
    source: str
    tree: ast.Module
    functions: list[ast.FunctionDef] = field(default_factory=list)
    main: ast.If | None = None
    consts: dict[str, int | float | bool] = field(default_factory=dict)
    prints: dict[str, PrintSite] = field(default_factory=dict)      # stub name -> site
    findings: list[Finding] = field(default_factory=list)
    edited: str = ""

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


# --- constants -----------------------------------------------------------------------------------

def _fold(node: ast.expr, known: dict[str, int | float | bool]):
    """The value of a constant expression: an int, float or bool literal, unary minus (and plus), and
    `+ - * /` over literals and earlier constants. Raises ValueError with the reason otherwise."""
    if isinstance(node, ast.Constant):
        if type(node.value) in (int, float, bool):
            return node.value
        raise ValueError(f"a {type(node.value).__name__} literal is not a numeric constant")
    if isinstance(node, ast.Name):
        if node.id in known:
            return known[node.id]
        raise ValueError(f"`{node.id}` is not an earlier constant")
    if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.USub, ast.UAdd)):
        v = _fold(node.operand, known)
        return -v if isinstance(node.op, ast.USub) else +v
    if isinstance(node, ast.BinOp) and isinstance(node.op, (ast.Add, ast.Sub, ast.Mult, ast.Div)):
        a, b = _fold(node.left, known), _fold(node.right, known)
        try:
            if isinstance(node.op, ast.Add):
                return a + b
            if isinstance(node.op, ast.Sub):
                return a - b
            if isinstance(node.op, ast.Mult):
                return a * b
            return a / b
        except ArithmeticError as e:
            raise ValueError(f"the expression raises {type(e).__name__}: {e}") from None
    raise ValueError(f"a `{type(node).__name__}` is not allowed in a constant expression "
                     "(only literals, unary minus and + - * / over earlier constants)")


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
    def __init__(self, program: Program, rep: _Report, arity: dict[str, int], has_math: bool) -> None:
        self.p = program
        self.rep = rep
        self.arity = arity
        self.has_math = has_math
        self.edits: list[tuple[ast.AST, ast.AST | None, str]] = []   # (start node, end node, text)
        self.print_count = 0
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

    def annotation(self, ann: ast.expr | None, what: str, node: ast.AST, allow_none: bool = False) -> None:
        if ann is None:
            self.rep.error(node, "aot/annotation", what, f"{what} has no type annotation (a program is checked "
                           "by its annotations)")
        elif isinstance(ann, ast.Name) and ann.id in SCALAR_NAMES:
            return
        elif allow_none and isinstance(ann, ast.Constant) and ann.value is None:
            return
        else:
            self.rep.error(ann, "aot/type", ast.unparse(ann), f"the type `{ast.unparse(ann)}` is outside the "
                           "first AOT step (int, float and bool, and None as a return type)")

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
        self.annotation(fn.returns, f"the return of `{fn.name}`", fn, allow_none=True)

    # -- statements

    def stmt(self, s: ast.stmt) -> None:
        if isinstance(s, ast.Return):
            if s.value is not None:
                if isinstance(s.value, ast.Constant) and s.value.value is None:
                    return
                self.expr(s.value)
        elif isinstance(s, ast.Assign):
            if len(s.targets) != 1 or not isinstance(s.targets[0], ast.Name):
                self.rep.error(s, "aot/assign", "assignment", "only `name = value` is supported "
                               "(no chained assignment, no tuple or subscript target)")
            self.expr(s.value)
        elif isinstance(s, ast.AnnAssign):
            if not isinstance(s.target, ast.Name) or s.value is None:
                self.rep.error(s, "aot/assign", "assignment", "only `name: type = value` is supported")
            else:
                self.annotation(s.annotation, f"`{s.target.id}`", s)
            if s.value is not None:
                self.expr(s.value)
        elif isinstance(s, ast.AugAssign):
            if not isinstance(s.target, ast.Name) or not isinstance(s.op, _BIN_OPS):
                self.rep.error(s, "aot/assign", "augmented assignment",
                               f"the augmented assignment `{ast.unparse(s)}` is outside the first AOT step")
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
        ok = True
        for k in call.keywords:
            ok = False
            self.rep.error(k.value, "aot/print-keyword", k.arg or "**",
                           f"print(..., {k.arg or '**'}=...) is outside the first AOT step "
                           "(positional arguments only; no sep, end, file or flush)")
        template: list[str | None] = []
        for a in call.args:
            if isinstance(a, ast.Starred):
                ok = False
                self.rep.error(a, "aot/print-arg", "*", "a starred argument of print is outside the first AOT step")
            elif isinstance(a, ast.Constant) and isinstance(a.value, str):
                if "\x00" in a.value or any(0xD800 <= ord(c) <= 0xDFFF for c in a.value):
                    ok = False
                    self.rep.error(a, "aot/print-arg", "str", "a string literal with a NUL or a lone surrogate is "
                                   "outside the first AOT step")
                template.append(a.value)
            elif isinstance(a, ast.Constant) and a.value is None:
                ok = False
                self.rep.error(a, "aot/print-arg", "None", "print(None) is outside the first AOT step "
                               "(int, float, bool and string literals only)")
            else:
                self.expr(a)
                template.append(None)
        if not ok:
            return
        site = PrintSite(self.print_count, s.lineno, template)
        self.print_count += 1
        self.p.prints[site.stub] = site
        self.edits.append((call.func, None, site.stub))
        for a, t in zip(call.args, template):
            if t is not None:
                self.edits.append((a, a, "0"))

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
                    self.rep.error(e, "aot/constant", e.id, f"the module constant `{e.id}` is nan, which has no "
                                   "literal form in the first AOT step")
                else:
                    self.edits.append((e, e, _literal(v)))
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
            self.rep.error(e, "aot/f-string", "f-string", "an f-string is outside the first AOT step (strings arrive in a later step)")
        elif isinstance(e, ast.Attribute):
            self.rep.error(e, "aot/attribute", e.attr, f"the attribute access `{ast.unparse(e)}` is outside the first AOT step")
        elif isinstance(e, ast.IfExp):
            self.rep.error(e, "aot/expression", "conditional expression", "a conditional expression is outside the first AOT step")
        elif isinstance(e, ast.Lambda):
            self.rep.error(e, "aot/expression", "lambda", "a lambda is outside the first AOT step")
        elif type(e).__name__ in _CONTAINER_NAMES:
            kind = _CONTAINER_NAMES[type(e).__name__]
            self.rep.error(e, "aot/container", kind, f"a {kind} is outside the first AOT step (containers arrive in a later step)")
        else:
            kind = type(e).__name__
            self.rep.error(e, "aot/expression", kind, f"a `{kind}` expression is outside the first AOT step")

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


def _op_symbol(op: ast.operator) -> str:
    return {ast.Pow: "**", ast.LShift: "<<", ast.RShift: ">>", ast.BitAnd: "&", ast.BitOr: "|",
            ast.BitXor: "^", ast.MatMult: "@"}.get(type(op), type(op).__name__)


def _literal(v: int | float | bool) -> str:
    """The source text of a constant's value (parenthesised, so a negative one binds as a unit)."""
    if isinstance(v, bool):
        return f"({v})"
    if isinstance(v, int):
        return f"({v})"
    if v != v:
        raise ValueError("nan")
    if math.isinf(v):
        return "(1e999)" if v > 0 else "(-1e999)"
    return f"({v!r})"


# --- the module ----------------------------------------------------------------------------------

def _is_main_guard(node: ast.stmt) -> bool:
    if not isinstance(node, ast.If):
        return False
    t = node.test
    return (isinstance(t, ast.Compare) and isinstance(t.left, ast.Name) and t.left.id == "__name__"
            and len(t.ops) == 1 and isinstance(t.ops[0], ast.Eq) and len(t.comparators) == 1
            and isinstance(t.comparators[0], ast.Constant) and t.comparators[0].value == "__main__")


def analyze(entry: Path) -> Program:
    entry = Path(entry).resolve()
    source = entry.read_text(encoding="utf-8")
    rep = _Report(entry.name)
    try:
        tree = ast.parse(source, filename=str(entry))
    except SyntaxError as e:
        rep.error(e.lineno or 1, "aot/syntax", "SyntaxError", f"the module does not parse ({e.msg})")
        return Program(entry, entry.name, source, ast.Module(body=[], type_ignores=[]), findings=rep.findings)
    program = Program(entry, entry.name, source, tree)

    # -- module level
    has_math = False
    main_guards: list[ast.If] = []
    seen_names: dict[str, int] = {}
    for i, s in enumerate(tree.body):
        if isinstance(s, ast.Expr) and isinstance(s.value, ast.Constant) and isinstance(s.value.value, str) and i == 0:
            continue
        if isinstance(s, ast.Import):
            if len(s.names) == 1 and s.names[0].name == "math" and s.names[0].asname is None and not has_math:
                has_math = True
            else:
                rep.error(s, "aot/import", ", ".join(a.name for a in s.names),
                          "only a single `import math` is supported in the first AOT step")
        elif isinstance(s, ast.ImportFrom):
            rep.error(s, "aot/import", s.module or ".", "only a single `import math` is supported in the first AOT step")
        elif isinstance(s, ast.FunctionDef):
            program.functions.append(s)
            if s.name in seen_names:
                rep.error(s, "aot/redefinition", s.name, f"`{s.name}` is defined twice (first at line {seen_names[s.name]})")
            seen_names.setdefault(s.name, s.lineno)
        elif isinstance(s, ast.Assign) and len(s.targets) == 1 and isinstance(s.targets[0], ast.Name):
            name = s.targets[0].id
            try:
                v = _fold(s.value, program.consts)
                if isinstance(v, int) and not isinstance(v, bool) and not I64_MIN <= v <= I64_MAX:
                    raise ValueError(f"the int value {v} is outside 64 bits (big integers arrive in a later step)")
            except ValueError as e:
                rep.error(s, "aot/constant", name, f"the module constant `{name}` is not a constant expression: {e}")
            else:
                if name in seen_names:
                    rep.error(s, "aot/redefinition", name, f"`{name}` is bound twice at module level "
                              f"(first at line {seen_names[name]}); a constant is assigned once")
                else:
                    program.consts[name] = v
                seen_names.setdefault(name, s.lineno)
        elif _is_main_guard(s):
            assert isinstance(s, ast.If)
            main_guards.append(s)
            if s.orelse:
                rep.error(s.orelse[0], "aot/entry", "else", "the `if __name__ == \"__main__\"` block has no `else` branch in the first AOT step")
        else:
            kind = _STATEMENT_NAMES.get(type(s).__name__, type(s).__name__)
            rep.error(s, "aot/module-statement", kind,
                      f"a module-level `{kind}` statement is not allowed: a program has a docstring, one `import math`, "
                      "defs, `NAME = <constant expression>` and one `if __name__ == \"__main__\"` block")
    if len(main_guards) != 1:
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
        if n in {"print", "float", "range", "math"}:
            rep.error(seen_names[n], "aot/rebinding", n, f"`{n}` is rebound by the program, which the first AOT step does not support")
    for n in ast.walk(tree):
        bound = None
        if isinstance(n, ast.arg):
            bound = n.arg
        elif isinstance(n, ast.Name) and isinstance(n.ctx, (ast.Store, ast.Del)):
            bound = n.id
        if bound in {"print", "float", "range"}:
            rep.error(n, "aot/rebinding", bound, f"`{bound}` is rebound by the program, which the first AOT step does not support")

    # -- the entry block must not bind a module name (it would rebind a constant or a function that
    # other functions read; as a function body its names would be locals)
    module_names = names | set(program.consts) | ({"math"} if has_math else set())
    if program.main is not None:
        for s in program.main.body:
            for n in ast.walk(s):
                if isinstance(n, ast.Name) and isinstance(n.ctx, (ast.Store, ast.Del)) and n.id in module_names:
                    rep.error(n, "aot/main-rebinds", n.id, f"the `__main__` block assigns `{n.id}`, a name the module "
                              "defines; the first AOT step keeps the block's names local")

    # -- functions
    arity = {f.name: len(f.args.args) for f in program.functions}
    checker = _Checker(program, rep, arity, has_math)
    for f in program.functions:
        checker.function(f.name, f, f.body)
    if program.main is not None:
        checker.function("__main__", None, program.main.body)
        if _is_main_guard(program.main) and program.main.test.end_lineno != program.main.test.lineno:
            rep.error(program.main, "aot/entry", "__main__", "the `__main__` test must be on one line")

    unique: dict[tuple[int, str, str], Finding] = {}
    for f in rep.findings:
        unique.setdefault((f.line, f.rule, f.name), f)
    program.findings = list(unique.values())
    if not program.findings:
        program.edited = _apply_edits(source, program, checker.edits)
    return program


# --- text edits ----------------------------------------------------------------------------------

def _apply_edits(source: str, program: Program, node_edits: list[tuple[ast.AST, ast.AST | None, str]]) -> str:
    """The text for the front end. Every edit keeps the number of lines: a replaced multi-line literal is
    followed by as many newlines as it spanned."""
    data = source.encode("utf-8")
    starts = [0]
    for i, b in enumerate(data):
        if b == 0x0A:
            starts.append(i + 1)

    def offset(line: int, col: int) -> int:                 # ast columns are UTF-8 byte offsets
        return starts[line - 1] + col

    edits: list[tuple[int, int, bytes]] = []
    for start, end, text in node_edits:
        if end is None:                                      # a Name (the callee `print`): its own span
            end = start
        a = offset(start.lineno, start.col_offset)
        b = offset(end.end_lineno, end.end_col_offset)
        newlines = end.end_lineno - start.lineno
        edits.append((a, b, text.encode() + b"\n" * newlines))
    if program.main is not None:                             # the header: `if __name__ == "__main__"` -> def
        t = program.main.test
        edits.append((offset(program.main.lineno, program.main.col_offset),
                      offset(t.end_lineno, t.end_col_offset), f"def {MAIN}() -> None".encode()))
    for a, b, text in sorted(edits, key=lambda e: e[0], reverse=True):
        data = data[:a] + text + data[b:]
    out = data.decode("utf-8")
    if not out.endswith("\n"):
        out += "\n"
    for site in program.prints.values():
        params = ", ".join(f"a{i}: object" for i in range(len(site.template)))
        out += f"def {site.stub}({params}) -> None:\n    pass\n"
    return out
