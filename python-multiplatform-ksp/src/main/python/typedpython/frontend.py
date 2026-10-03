"""Front end: Python AST + Pyrefly-inferred types -> the typed IR (`ir.py`, #41, SPEC N-7).

`lower(path)` reads one module and returns an `ir.Module`:

- Which functions: module-level `def`s decorated with the builtin `@compiled` (a bare name, no
  import — #42), or every module-level `def` when the first non-empty line is
  `# typedpython: compiled`.
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
constants, range targets (between their bounds), `len()` and the i64 range of a guarded parameter.
"""
from __future__ import annotations

import ast
import tempfile
from collections.abc import Iterable
from dataclasses import dataclass, field
from pathlib import Path

from typedpython import gate, ir, pyrefly, rebinding
from typedpython.ir import Type

MARKER = "# typedpython: compiled"

I64_MIN, I64_MAX = -2**63, 2**63 - 1
TOP = (I64_MIN, I64_MAX)
EXACT_INT_FLOAT = 2**53  # |n| <= this: int/int true division is exact-rounded in C (ir.BinOp)

Interval = tuple[int, int] | None  # None: no value at all (not yet known to be reached)

TYPE_NAME = {
    Type.I64: "int", Type.F64: "float", Type.BOOL: "bool", Type.NONE: "None", Type.OBJ: "object",
    Type.F64_ARRAY: "list[float]", Type.I64_ARRAY: "list[int]",
}
SCALAR_KIND = {"int": Type.I64, "float": Type.F64, "bool": Type.BOOL}
ARRAY_KIND = {"list[float]": Type.F64_ARRAY, "list[int]": Type.I64_ARRAY}

BINOP = {
    ast.Add: ir.BinOpKind.ADD, ast.Sub: ir.BinOpKind.SUB, ast.Mult: ir.BinOpKind.MUL,
    ast.Div: ir.BinOpKind.TRUEDIV, ast.FloorDiv: ir.BinOpKind.FLOORDIV, ast.Mod: ir.BinOpKind.MOD,
}
COMPARE = {
    ast.Eq: ir.CompareKind.EQ, ast.NotEq: ir.CompareKind.NE, ast.Lt: ir.CompareKind.LT,
    ast.LtE: ir.CompareKind.LE, ast.Gt: ir.CompareKind.GT, ast.GtE: ir.CompareKind.GE,
}
MATH_ARITY = {
    ir.MathFunc.SQRT: 1, ir.MathFunc.EXP: 1, ir.MathFunc.LOG: 1, ir.MathFunc.SIN: 1,
    ir.MathFunc.COS: 1, ir.MathFunc.TAN: 1, ir.MathFunc.FABS: 1, ir.MathFunc.ATAN2: 2,
    ir.MathFunc.HYPOT: 2,
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


# --- the module ----------------------------------------------------------------------------------

@dataclass
class _ModuleInfo:
    bindings: dict[str, list[tuple[int, str]]]   # module-level name -> [(line, how)]
    stdlib_math: bool

    def bound(self, name: str) -> bool:
        return name in self.bindings


@dataclass
class _Signature:
    node: ast.FunctionDef
    params: tuple[ir.Param | ir.ArrayParam, ...]
    returns: Type


def lower(path: Path) -> ir.Module:
    path = Path(path).resolve()
    source = path.read_text()
    tree = ast.parse(source, filename=str(path))
    info = _module_info(tree)

    skipped: dict[str, str] = {}
    signatures: dict[str, _Signature] = {}
    for node, reason in _targets(tree, source, info):
        if reason is not None:
            skipped[node.name] = reason
            continue
        assert isinstance(node, ast.FunctionDef)
        try:
            signatures[node.name] = _signature(node)
        except Skip as e:
            skipped[node.name] = e.reason

    for name, cycle in _call_cycles(signatures).items():
        skipped[name] = (f"line {signatures[name].node.lineno}: recursion "
                         f"({' -> '.join(cycle)}) is not compiled: C has no recursion limit "
                         "where CPython raises RecursionError")
        del signatures[name]

    kinds = _inferred_kinds(path, source, tree, [s.node for s in signatures.values()])

    # Optimistic start: every candidate lowered, pure, not deopting; then iterate to a fixpoint
    # (purity is the greatest fixpoint through calls, may_deopt the least).
    env: dict[str, ir.Function] = {
        name: ir.Function(name, s.params, s.returns, {}, (), True, False, s.node.lineno)
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
    return ir.Module(path.stem, tuple(functions), skipped)


def _call_cycles(signatures: dict[str, _Signature]) -> dict[str, list[str]]:
    """Candidates on a call cycle through calls by name to other candidates (syntactic, so a
    call that would end up as CallObject is counted too: conservative)."""
    calls = {
        name: {n.func.id for n in ast.walk(s.node) if isinstance(n, ast.Call)
               and isinstance(n.func, ast.Name) and n.func.id in signatures}
        for name, s in signatures.items()
    }
    cycles: dict[str, list[str]] = {}
    for start in signatures:
        stack = [(start, [start])]
        seen: set[str] = set()
        while stack and start not in cycles:
            name, path = stack.pop()
            for callee in sorted(calls[name]):
                if callee == start:
                    cycles[start] = path + [start]
                    break
                if callee not in seen:
                    seen.add(callee)
                    stack.append((callee, path + [callee]))
    return cycles


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
    return _ModuleInfo(bindings, len(math) == 1 and math[0][1] == "import math")


def _binding_targets(stmt: ast.stmt) -> list[ast.expr]:
    if isinstance(stmt, ast.Assign):
        return list(stmt.targets)
    if isinstance(stmt, (ast.AugAssign, ast.AnnAssign, ast.For, ast.AsyncFor)):
        return [stmt.target]
    if isinstance(stmt, (ast.With, ast.AsyncWith)):
        return [i.optional_vars for i in stmt.items if i.optional_vars is not None]
    return []


def _targets(
    tree: ast.Module, source: str, info: _ModuleInfo,
) -> list[tuple[ast.FunctionDef | ast.AsyncFunctionDef, str | None]]:
    first = next((line.strip() for line in source.splitlines() if line.strip()), "")
    whole_module = first == MARKER
    found: list[tuple[ast.FunctionDef | ast.AsyncFunctionDef, str | None]] = []
    for node in tree.body:
        if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        marked = [d for d in node.decorator_list if isinstance(d, ast.Name) and d.id == "compiled"]
        if not (whole_module or marked):
            continue
        reason = None
        if marked and info.bound("compiled"):
            line = info.bindings["compiled"][0][0]
            reason = (f"line {node.lineno}: `compiled` is bound in this module (line {line}), "
                      "so `@compiled` is not the builtin")
        elif other := [d for d in node.decorator_list if d not in marked]:
            reason = (f"line {other[0].lineno}: decorator `@{_src(other[0])}` is not supported "
                      "(only `@compiled`)")
        elif isinstance(node, ast.AsyncFunctionDef):
            reason = f"line {node.lineno}: async function is not supported"
        elif len(info.bindings.get(node.name, [])) > 1:
            lines = ", ".join(str(line) for line, _ in info.bindings[node.name])
            reason = f"line {node.lineno}: `{node.name}` is bound more than once (lines {lines})"
        found.append((node, reason))
    return found


def _signature(fn: ast.FunctionDef) -> _Signature:
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
    for a in args.args:
        if a.annotation is None:
            raise Skip(a.lineno, f"parameter `{a.arg}` has no annotation")
        kind = _annotation_kind(a.annotation)
        if kind in ARRAY_KIND:
            params.append(ir.ArrayParam(a.arg, ARRAY_KIND[kind], False))  # `stored` set later
        else:
            params.append(ir.Param(a.arg, SCALAR_KIND.get(kind, Type.OBJ)))
    if fn.returns is None:
        raise Skip(line, "no return annotation")
    if isinstance(fn.returns, ast.Constant) and fn.returns.value is None:
        returns = Type.NONE
    else:
        returns = SCALAR_KIND.get(_annotation_kind(fn.returns), Type.OBJ)
    return _Signature(fn, tuple(params), returns)


def _annotation_kind(annotation: ast.expr) -> str:
    if isinstance(annotation, ast.Name) and annotation.id in SCALAR_KIND:
        return annotation.id
    return _src(annotation).replace(" ", "")


# --- local types from Pyrefly --------------------------------------------------------------------

def _inferred_kinds(
    path: Path, source: str, tree: ast.Module, functions: list[ast.FunctionDef],
) -> dict[str, dict[str, list[tuple[int, str]]]]:
    """function -> local -> [(line, base type)] for every unannotated assignment (Pyrefly)."""
    scopes = rebinding._scopes(tree)
    wanted_scopes = {scopes.index(fn) for fn in functions}
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
        found.setdefault(function.name, {}).setdefault(p.name, []).append(
            (a.line, rebinding._base(spelled)))
    return found


# --- one function --------------------------------------------------------------------------------

def _lower_function(
    sig: _Signature, env: dict[str, ir.Function], info: _ModuleInfo,
    kinds: dict[str, dict[str, list[tuple[int, str]]]],
) -> ir.Function | str:
    inferred = kinds.get(sig.node.name, {})
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
        return (pure_error or e).reason


def _lower_in_mode(sig, env, info, inferred, impure: bool) -> ir.Function:
    # Range-target intervals: least fixpoint by iteration, widened to TOP if it does not settle.
    bounds: dict[str, Interval] = {}
    for _ in range(8):
        lowerer = _Lowerer(sig, env, info, inferred, impure, bounds)
        fn = lowerer.run()
        joined = {**bounds}
        for var, iv in lowerer.found_bounds.items():
            joined[var] = _join(joined.get(var), iv)
        if joined == bounds:
            return fn
        bounds = joined
    widened = {var: TOP for var in bounds}
    return _Lowerer(sig, env, info, inferred, impure, widened).run()


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
                 bounds: dict[str, Interval]) -> None:
        self.sig = sig
        self.fn = sig.node
        self.env = env
        self.info = info
        self.inferred = inferred
        self.impure_mode = impure
        self.bounds = bounds
        self.found_bounds: dict[str, Interval] = {}

        self.params = {p.name: p for p in sig.params}
        self.arrays = {p.name: p.type for p in sig.params if isinstance(p, ir.ArrayParam)}
        self.bindings: dict[str, list[_Binding]] = {}
        self.annotations: dict[str, list[tuple[int, str]]] = {}
        self._collect_bindings()
        self.range_vars = {
            n for n, bs in self.bindings.items()
            if n not in self.params and any(b.how == "range" for b in bs)
            and all(b.how in ("range", "annotation") for b in bs)
        }
        self.all_names = {n.id for n in ast.walk(self.fn) if isinstance(n, ast.Name)} \
            | set(self.params)

        self.types: dict[str, Type] = {}
        self.temps: dict[str, Type] = {}
        self.pre: list[ir.Stmt] = []
        self.hoist_ok = True
        self.impure = False      # an effect outside the locals
        self.deopts = False      # a node that can deopt
        self.stored: set[str] = set()

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
        params = tuple(
            ir.ArrayParam(p.name, p.type, p.name in self.stored) if isinstance(p, ir.ArrayParam)
            else p for p in self.sig.params
        )
        pure = not self.impure
        assert pure or not self.deopts or not self.impure_mode, \
            "impure mode emitted a deopting node"
        return ir.Function(self.fn.name, params, self.sig.returns, locals_, body, pure,
                           self.deopts, self.fn.lineno)

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

    def is_local(self, name: str) -> bool:
        return name in self.params or name in self.bindings or name in self.temps

    def local_type(self, name: str, line: int) -> Type:
        if name in self.temps:
            return self.temps[name]
        if name in self.params:
            return self.params[name].type
        if name in self.types:
            return self.types[name]
        kinds: list[tuple[int, str]] = list(self.annotations.get(name, []))
        kinds += [(b.line, "int") for b in self.bindings.get(name, []) if b.how == "range"]
        if name not in self.annotations:
            kinds += self.inferred.get(name, [])
        distinct = sorted({k for _, k in kinds})
        if not distinct:
            raise Skip(self.bindings[name][0].line, f"no type is known for local `{name}`")
        if len(distinct) > 1:
            where = next(line for line, k in kinds if k != kinds[0][1])
            raise Skip(where, f"local `{name}` is given values of types "
                              + " and ".join(f"`{k}`" for k in distinct))
        kind = distinct[0]
        if kind in ARRAY_KIND or kind.startswith(("list[float]", "list[int]")):
            raise Skip(kinds[0][0], f"local `{name}: {kind}`: only parameters can be native "
                                    "arrays")
        if kind == "int":
            t = Type.OBJ if self.impure_mode and name not in self.range_vars else Type.I64
        else:
            t = SCALAR_KIND.get(kind, Type.OBJ)
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
        out: list[ir.Stmt] = []
        for s in stmts:
            out += self.stmt(s)
        return tuple(out)

    def start(self) -> None:
        self.pre = []
        self.hoist_ok = True

    def take(self) -> list[ir.Stmt]:
        pre, self.pre = self.pre, []
        return pre

    def stmt(self, node: ast.stmt) -> list[ir.Stmt]:
        self.start()
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
            return pre + [ir.If(cond, self.block(node.body), self.block(node.orelse))]
        if isinstance(node, ast.While):
            if node.orelse:
                raise Skip(line, "`while ... else` is not supported")
            cond = self.condition(node.test)
            pre = self.take()
            body = self.block(node.body)
            if not pre:
                return [ir.While(cond, body)]
            # The condition needs statements (a chain's temporary): run them on every iteration.
            exit_ = ir.If(cond, (), (ir.Break(),))  # cond stays directly a condition
            return [ir.While(ir.Const(Type.BOOL, True), (*pre, exit_, *body))]
        if isinstance(node, ast.For):
            return self.for_range(node)
        if isinstance(node, ast.Return):
            return self.return_(node)
        raise _unsupported(node)

    def assign(self, target: ast.expr, value_node: ast.expr, node: ast.stmt) -> list[ir.Stmt]:
        line = node.lineno
        if isinstance(target, ast.Name):
            name = target.id
            if name in self.arrays:
                raise Skip(line, f"array parameter `{name}` is rebound")
            value = self.value(value_node)
            t = self.local_type(name, line)
            return self.take() + [ir.Assign(name, self.coerce(value, t, value_node,
                                                               f"local `{name}`"))]
        if isinstance(target, ast.Subscript):
            array, elem = self.array_of(target)
            # CPython evaluates the value first, then the index.
            value = self.value(value_node)
            value = self.coerce(value, elem, value_node, f"`{array}: {TYPE_NAME[self.arrays[array]]}`")
            if not _simple(target.slice) and not _trivial(value):
                value = self.temp(value)
            index = self.index(target.slice)
            self.stored.add(array)
            self.impure = True
            return self.take() + [ir.StoreIndex(array, index, value)]
        if isinstance(target, ast.Attribute):
            raise Skip(line, f"assignment to attribute `{_src(target)}` is not supported")
        if isinstance(target, (ast.Tuple, ast.List)):
            raise Skip(line, "tuple unpacking is not supported")
        raise _unsupported(target)

    def aug_assign(self, node: ast.AugAssign) -> list[ir.Stmt]:
        line = node.lineno
        op = self.binop_kind(node.op, node)
        target = node.target
        if isinstance(target, ast.Name):
            name = target.id
            if name in self.arrays:
                raise Skip(line, f"array parameter `{name}` is rebound")
            if not self.is_local(name):
                raise _unsupported(node)
            t = self.local_type(name, line)
            current = ir.Local(t, name)
            result = self.arith(op, current, self.sub(node.value), node)
            return self.take() + [ir.Assign(name, self.coerce(result, t, node, f"local `{name}`"))]
        if isinstance(target, ast.Subscript):
            array, elem = self.array_of(target)
            index = self.index(target.slice)
            if not isinstance(index, (ir.Local, ir.Const)):
                index = self.temp(index)  # `a[i] += e` evaluates `i` once
            load = ir.Index(elem, array, index)
            self.hoist_ok = False
            result = self.arith(op, load, self.sub(node.value), node)
            result = self.coerce(result, elem, node, f"`{array}: {TYPE_NAME[self.arrays[array]]}`")
            self.stored.add(array)
            self.impure = True
            return self.take() + [ir.StoreIndex(array, index, result)]
        if isinstance(target, ast.Attribute):
            raise Skip(line, f"assignment to attribute `{_src(target)}` is not supported")
        raise _unsupported(target)

    def for_range(self, node: ast.For) -> list[ir.Stmt]:
        line = node.lineno
        if node.orelse:
            raise Skip(line, "`for ... else` is not supported")
        if not isinstance(node.target, ast.Name):
            raise Skip(line, f"loop target `{_src(node.target)}` is not supported (only a name)")
        if not (_is_range_call(node.iter) and self.builtin("range")):
            raise Skip(line, f"`for` over `{_src(node.iter)}` is not supported (only range())")
        call = node.iter
        assert isinstance(call, ast.Call)
        var = node.target.id
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
            if step is None or step == 0:
                raise Skip(line, f"range step `{_src(args[2])}` must be a non-zero int literal")
        else:
            step = 1
        if len(args) == 1:
            start: ir.Expr = ir.Const(Type.I64, 0)
            stop = self.range_bound(args[0])
        else:
            start = self.range_bound(args[0])
            stop = self.range_bound(args[1])
        pre = self.take()

        s, e = self.interval(start), self.interval(stop)
        if s is not None and e is not None:
            if step > 0:
                iv = (s[0], max(s[0], e[1] - 1))
            else:
                iv = (min(s[1], e[0] + 1), s[1])
            self.found_bounds[var] = _join(self.found_bounds.get(var), iv)

        body = self.block(node.body)
        return pre + [ir.ForRange(var, start, stop, ir.Const(Type.I64, step), body)]

    def range_bound(self, node: ast.expr) -> ir.Expr:
        e = self.value(node)
        if e.type != Type.I64:
            why = (" (it is a CPython int here: its arithmetic is not proved free of i64 overflow "
                   "in an impure function)" if e.type == Type.OBJ and self.impure_mode else "")
            raise Skip(node.lineno, f"range bound `{_src(node)}` is not a native int{why}")
        return e

    def return_(self, node: ast.Return) -> list[ir.Stmt]:
        returns = self.sig.returns
        if node.value is None or (isinstance(node.value, ast.Constant) and node.value.value is None):
            if returns != Type.NONE:
                raise Skip(node.lineno, f"returns None from a function declared to return "
                                        f"{TYPE_NAME[returns]}")
            return [ir.Return(None)]
        if returns == Type.NONE:
            raise Skip(node.lineno, "returns a value from a function declared to return None")
        value = self.value(node.value)
        value = self.coerce(value, returns, node.value, "the return value")
        return self.take() + [ir.Return(value)]

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
            obj = self.boxed(self.sub(node.value), node.value)
            self.impure = True
            return ir.GetAttr(Type.OBJ, obj, node.attr)
        if isinstance(node, ast.Subscript):
            if isinstance(node.value, ast.Name) and node.value.id in self.arrays:
                array, elem = self.array_of(node)
                return ir.Index(elem, array, self.index(node.slice))
            raise Skip(line, f"subscript `{_src(node)}` of an object is not supported")
        raise _unsupported(node)

    def name(self, node: ast.Name) -> ir.Expr:
        name = node.id
        if name in self.arrays:
            raise self.array_misuse(name, node)
        if self.is_local(name):
            if name not in self.params and name not in self.temps and all(
                    b.how == "annotation" for b in self.bindings[name]):
                raise Skip(node.lineno, f"`{name}` is read but never assigned")
            return ir.Local(self.local_type(name, node.lineno), name)
        return ir.Global(Type.OBJ, name)

    def array_misuse(self, name: str, node: ast.AST) -> Skip:
        return Skip(node.lineno, f"array parameter `{name}` is used other than by indexing or "
                                 f"len() (in `{_src(node)}`)")

    def array_of(self, node: ast.Subscript) -> tuple[str, Type]:
        if not (isinstance(node.value, ast.Name) and node.value.id in self.arrays):
            raise Skip(node.lineno, f"subscript `{_src(node)}` of an object is not supported")
        name = node.value.id
        return name, Type.F64 if self.arrays[name] == Type.F64_ARRAY else Type.I64

    def index(self, node: ast.expr) -> ir.Expr:
        if isinstance(node, ast.Slice):
            raise _unsupported(node)
        e = self.value(node)
        if e.type != Type.I64:
            why = (" (it is a CPython int here: its arithmetic is not proved free of i64 overflow "
                   "in an impure function)" if e.type == Type.OBJ and self.impure_mode else "")
            raise Skip(node.lineno, f"index `{_src(node)}` is {TYPE_NAME[e.type]}, not a native "
                                    f"int{why}")
        return e

    def binop_kind(self, op: ast.operator, node: ast.AST) -> ir.BinOpKind:
        kind = BINOP.get(type(op))
        if kind is None:
            raise Skip(node.lineno, f"operator `{_src(node)}` is not supported")
        return kind

    def arith(self, op: ir.BinOpKind, left: ir.Expr, right: ir.Expr, node: ast.AST) -> ir.Expr:
        lt, rt = left.type, right.type
        if Type.BOOL in (lt, rt) or Type.NONE in (lt, rt):
            raise Skip(node.lineno, f"arithmetic on {TYPE_NAME[Type.BOOL if Type.BOOL in (lt, rt) else Type.NONE]} "
                                    f"in `{_src(node)}` is not supported")
        if Type.OBJ in (lt, rt):
            return self.obj_binop(op, left, right)
        if lt == Type.I64 and rt == Type.I64:
            result = Type.F64 if op == ir.BinOpKind.TRUEDIV else Type.I64
            e = ir.BinOp(result, op, left, right)
            if self.proved(e):
                return e
            if self.impure_mode:
                return self.obj_binop(op, left, right)
            self.deopts = True
            return e
        if lt == Type.I64:
            left = ir.ToFloat(Type.F64, left)
        if rt == Type.I64:
            right = ir.ToFloat(Type.F64, right)
        return ir.BinOp(Type.F64, op, left, right)

    def obj_binop(self, op: ir.BinOpKind, left: ir.Expr, right: ir.Expr) -> ir.Expr:
        self.impure = True
        return ir.BinOp(Type.OBJ, op, _box(left), _box(right))

    def unary(self, node: ast.UnaryOp) -> ir.Expr:
        if isinstance(node.op, ast.Not):
            operand = self.truth(self.sub(node.operand), node.operand)
            return ir.UnaryOp(Type.BOOL, ir.UnaryOpKind.NOT, operand)
        if not isinstance(node.op, (ast.USub, ast.UAdd)):
            raise Skip(node.lineno, f"operator `{_src(node)}` is not supported")
        operand = self.sub(node.operand)
        kind = ir.UnaryOpKind.NEG if isinstance(node.op, ast.USub) else ir.UnaryOpKind.POS
        if operand.type == Type.F64:
            return ir.UnaryOp(Type.F64, kind, operand)
        if operand.type == Type.I64:
            e = ir.UnaryOp(Type.I64, kind, operand)
            if kind == ir.UnaryOpKind.POS or self.proved(e):
                return e
            if self.impure_mode:  # -x of a CPython int is 0 - x
                return self.obj_binop(ir.BinOpKind.SUB, ir.Const(Type.I64, 0), operand)
            self.deopts = True
            return e
        raise Skip(node.lineno, f"unary `{_src(node)}` on {TYPE_NAME[operand.type]} is not "
                                "supported")

    def compare(self, node: ast.Compare, cond: int) -> ir.Expr:
        # A chain is an And of its links: as a condition (2) that And is the condition; as an
        # operand of the condition's And/Or (1) it would be nested, so only a single link there.
        obj_ok = cond == 2 or (cond == 1 and len(node.ops) == 1)
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
        kind = COMPARE.get(type(op))
        if kind is None:
            raise Skip(node.lineno, f"comparison `{_src(node)}` is not supported "
                                    "(only == != < <= > >=)")
        lt, rt = left.type, right.type
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
        for i, v in enumerate(node.values):
            if i > 0:
                self.hoist_ok = False  # evaluated only if the earlier operands did not decide
            e = self.sub(v, 1 if cond == 2 else 0)
            if cond == 2:
                e = self.truth(e, v)
            elif e.type != Type.BOOL:
                raise Skip(node.lineno, f"`{word}` on a non-bool operand `{_src(v)}` outside a "
                                        "condition (it returns the operand)")
            operands.append(e)
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
            if plain and len(node.args) == 1 and name == "float" and self.builtin("float"):
                arg = self.value(node.args[0])
                if arg.type == Type.I64:
                    return ir.ToFloat(Type.F64, arg)
                if arg.type == Type.F64:
                    return arg  # float(x) of an exact float is x itself
                self.impure = True
                return ir.ObjToFloat(Type.F64, _box(arg))
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

        if isinstance(func, ast.Name) and func.id in self.arrays:
            raise self.array_misuse(func.id, node)
        callee = self.boxed(self.sub(func), func)
        self.hoist_ok = False
        args = [self.boxed(self.value(a), a) for a in node.args]
        args += [self.boxed(self.value(k.value), k.value) for k in node.keywords]
        self.impure = True
        return ir.CallObject(Type.OBJ, callee, tuple(args),
                             tuple(k.arg for k in node.keywords if k.arg is not None))

    def direct_call(self, name: str, node: ast.Call) -> ir.Expr | None:
        """`Call` of another compiled function, else `CallObject` of its module global; None when
        the generic path should lower the call (decided before any argument is lowered)."""
        callee = self.env[name]
        if node.keywords or len(node.args) != len(callee.params):
            return None  # CPython's own TypeError, through the interpreted path
        if any(isinstance(p, ir.ArrayParam) for p in callee.params):
            return None
        self.hoist_ok = False
        args = [self.value(a) for a in node.args]
        direct = not (callee.may_deopt and self.impure_mode)  # else a deopt could not be redone
        for p, e in zip(callee.params, args):
            if p.type != Type.OBJ and e.type != p.type:
                direct = False  # the callee's guard would deopt; the global keeps CPython's result
        if not direct:
            self.impure = True
            return ir.CallObject(Type.OBJ, ir.Global(Type.OBJ, name),
                                 tuple(self.boxed(e, a) for e, a in zip(args, node.args)))
        if not callee.pure:
            self.impure = True
        if callee.may_deopt:
            self.deopts = True
        typed = tuple(self.boxed(e, a) if p.type == Type.OBJ else e
                      for p, e, a in zip(callee.params, args, node.args))
        return ir.Call(callee.returns, name, typed)

    def math_call(self, attr: str, node: ast.Call) -> ir.Expr | None:
        try:
            func = ir.MathFunc(attr)
        except ValueError:
            return None
        if len(node.args) != MATH_ARITY[func]:
            return None
        self.hoist_ok = False
        args = [self.value(a) for a in node.args]
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
        if target == Type.OBJ and value.type in ir.SCALARS:
            return ir.Box(Type.OBJ, value)
        extra = ""
        if value.type == Type.OBJ:
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
        if isinstance(e, ir.Local) and e.type == Type.I64 and e.name in self.range_vars:
            return self.bounds.get(e.name)
        if isinstance(e, ir.Len):
            return (0, I64_MAX)
        if isinstance(e, (ir.BinOp, ir.UnaryOp)) and e.type == Type.I64 and self.proved(e):
            return self._result_interval(e)
        return TOP

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
        if e.op == ir.BinOpKind.FLOORDIV:
            m = max(abs(a[0]), abs(a[1]))
            return (max(-m, I64_MIN), min(m, I64_MAX))
        if e.op == ir.BinOpKind.MOD:
            d = max(abs(b[0]), abs(b[1]), 1)
            return (-(d - 1), d - 1)
        return TOP


# --- helpers -------------------------------------------------------------------------------------

def _box(e: ir.Expr) -> ir.Expr:
    return ir.Box(Type.OBJ, e) if e.type in ir.SCALARS else e


def _trivial(e: ir.Expr) -> bool:
    """No effect, cannot raise, cannot deopt: may be evaluated in any order, or twice."""
    if isinstance(e, (ir.Const, ir.Local, ir.Len)):
        return True
    if isinstance(e, ir.ToFloat):
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


def _simple(node: ast.expr) -> bool:
    return isinstance(node, (ast.Name, ast.Constant))


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
    if isinstance(last, ir.Return):
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


__all__ = ["lower", "MARKER"]
