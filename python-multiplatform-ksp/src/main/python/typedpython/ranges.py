"""AOT mode, tier S2c: a range object held in a name (design 4.3.2, S2c). Agent proposal, not owner-confirmed.

A local bound only to `range(...)` with an int constant step (`r = range(n)`, `r = range(a, b)`, `r = range(a, b, -2)`)
is held as two hidden int locals, its start and its stop; the step stays the constant. Each use is rewritten, on the
AST the lowering reads, into what CPython computes for a range object:

    r = range(a, b, 3)          r__lo1 = a; r__hi1 = b              (left to right, as CPython evaluates the arguments)
                                r__n3 = max(0, (r__hi1 - r__lo1 + 2) // 3)      (the length, once per binding)
    for x in r:                 for x in range(r__lo1, r__hi1, 3):
    len(r)                      r__n3
    x in r                      r__lo1 <= x < r__hi1 and (x - r__lo1) % 3 == 0
    if r: / not r / bool(r)     r__lo1 < r__hi1 (a negative step: r__hi1 < r__lo1)
    print(r), str(r), repr(r)   f"range({r__lo1}, {r__hi1}, 3)" (`range(a, b)` for a step of 1); in an f-string the same
                                parts, spliced in

The loop a comprehension or an enumerate() makes over `r` is rewritten too (this pass runs after theirs). Anything
else is refused naming the reason: a range object passed, returned, indexed, sliced, in a list, compared, or given to
list(), zip(), reversed(); a name bound to a range and to anything else; a step that is not an int constant (the
lowering of a `for` over a range needs a constant step) or is 0 (CPython raises ValueError at run time; refused at
compile time here); a binding whose arguments read the name itself; `x in r` of an `x` that is not a name or a
constant (it is read twice).
"""
from __future__ import annotations

import ast

from typedpython import comprehensions

Kinds = comprehensions.Kinds

_USES = ("an AOT program uses a range object by a `for` loop, len(), `in`, its truth and print / str / repr / an "
         "f-string")


class _Problem(Exception):
    def __init__(self, line: int, message: str) -> None:
        super().__init__(message)
        self.line = line


def _is_range(e: ast.expr | None) -> bool:
    return isinstance(e, ast.Call) and isinstance(e.func, ast.Name) and e.func.id == "range"


def _int_constant(e: ast.expr) -> int | None:
    sign = 1
    if isinstance(e, ast.UnaryOp) and isinstance(e.op, (ast.USub, ast.UAdd)):
        sign = -1 if isinstance(e.op, ast.USub) else 1
        e = e.operand
    if isinstance(e, ast.Constant) and type(e.value) is int:
        return sign * e.value
    return None


def _load(n: str) -> ast.Name:
    return ast.Name(id=n, ctx=ast.Load())


class _Range:
    def __init__(self, name: str, lo: str, hi: str, n: str, step: int) -> None:
        self.name, self.lo, self.hi, self.n, self.step = name, lo, hi, n, step

    def is_me(self, e: ast.AST) -> bool:
        return isinstance(e, ast.Name) and e.id == self.name and isinstance(e.ctx, ast.Load)

    def call(self) -> ast.expr:
        args: list[ast.expr] = [_load(self.lo), _load(self.hi)]
        if self.step != 1:
            args.append(ast.Constant(self.step))
        return ast.Call(func=_load("range"), args=args, keywords=[])

    def length(self) -> ast.expr:
        return _load(self.n)

    def count(self) -> ast.expr:
        """The length computed from the bounds (CPython's range length), in the binding's own statement."""
        m = abs(self.step)
        first, second = (self.hi, self.lo) if self.step > 0 else (self.lo, self.hi)
        span: ast.expr = ast.BinOp(left=_load(first), op=ast.Sub(), right=_load(second))
        if m != 1:
            span = ast.BinOp(left=ast.BinOp(left=span, op=ast.Add(), right=ast.Constant(m - 1)), op=ast.FloorDiv(),
                             right=ast.Constant(m))
        return ast.Call(func=_load("max"), args=[ast.Constant(0), span], keywords=[])

    def truth(self) -> ast.expr:
        a, b = (self.lo, self.hi) if self.step > 0 else (self.hi, self.lo)
        return ast.Compare(left=_load(a), ops=[ast.Lt()], comparators=[_load(b)])

    def contains(self, x: ast.expr) -> ast.expr:
        import copy
        if self.step > 0:
            bounds = ast.Compare(left=_load(self.lo), ops=[ast.LtE(), ast.Lt()],
                                 comparators=[copy.deepcopy(x), _load(self.hi)])
            offset = ast.BinOp(left=copy.deepcopy(x), op=ast.Sub(), right=_load(self.lo))
        else:
            bounds = ast.Compare(left=_load(self.hi), ops=[ast.Lt(), ast.LtE()],
                                 comparators=[copy.deepcopy(x), _load(self.lo)])
            offset = ast.BinOp(left=_load(self.lo), op=ast.Sub(), right=copy.deepcopy(x))
        if abs(self.step) == 1:
            return bounds
        aligned = ast.Compare(left=ast.BinOp(left=offset, op=ast.Mod(), right=ast.Constant(abs(self.step))),
                              ops=[ast.Eq()], comparators=[ast.Constant(0)])
        return ast.BoolOp(op=ast.And(), values=[bounds, aligned])

    def parts(self) -> list[ast.expr]:
        tail = ")" if self.step == 1 else f", {self.step})"
        return [ast.Constant("range("), ast.FormattedValue(value=_load(self.lo), conversion=-1, format_spec=None),
                ast.Constant(", "), ast.FormattedValue(value=_load(self.hi), conversion=-1, format_spec=None),
                ast.Constant(tail)]

    def text(self) -> ast.expr:
        return ast.JoinedStr(values=self.parts())


class _Uses(ast.NodeTransformer):
    """Rewrite the uses of one range object (see the module docstring)."""

    def __init__(self, r: _Range) -> None:
        self.r = r

    def visit_For(self, node: ast.For) -> ast.AST:
        if self.r.is_me(node.iter):
            node.iter = ast.copy_location(self.r.call(), node.iter)
        return self.generic_visit(node)

    def _test(self, node: ast.If | ast.While | ast.IfExp) -> ast.AST:
        if self.r.is_me(node.test):
            node.test = ast.copy_location(self.r.truth(), node.test)
        return self.generic_visit(node)

    visit_If = visit_While = visit_IfExp = _test

    def visit_UnaryOp(self, node: ast.UnaryOp) -> ast.AST:
        if isinstance(node.op, ast.Not) and self.r.is_me(node.operand):
            node.operand = ast.copy_location(self.r.truth(), node.operand)
        return self.generic_visit(node)

    def visit_Compare(self, node: ast.Compare) -> ast.AST:
        if len(node.ops) == 1 and isinstance(node.ops[0], (ast.In, ast.NotIn)) and self.r.is_me(node.comparators[0]) \
                and (isinstance(node.left, (ast.Name, ast.Constant)) or _int_constant(node.left) is not None) \
                and not self.r.is_me(node.left):
            inside = ast.copy_location(self.r.contains(node.left), node)
            if isinstance(node.ops[0], ast.NotIn):
                return ast.copy_location(ast.UnaryOp(op=ast.Not(), operand=inside), node)
            return inside
        return self.generic_visit(node)

    def visit_Call(self, node: ast.Call) -> ast.AST:
        f = node.func
        if isinstance(f, ast.Name) and len(node.args) == 1 and not node.keywords and self.r.is_me(node.args[0]):
            made = {"len": self.r.length, "bool": self.r.truth, "str": self.r.text, "repr": self.r.text}.get(f.id)
            if made is not None:
                return ast.copy_location(made(), node)
        if isinstance(f, ast.Name) and f.id == "print":
            node.args = [ast.copy_location(self.r.text(), a) if self.r.is_me(a) else a for a in node.args]
        return self.generic_visit(node)

    def visit_JoinedStr(self, node: ast.JoinedStr) -> ast.AST:
        values: list[ast.expr] = []
        for v in node.values:
            if isinstance(v, ast.FormattedValue) and self.r.is_me(v.value) and v.format_spec is None \
                    and v.conversion in (-1, ord("s"), ord("r")):
                values += [ast.copy_location(p, v) for p in self.r.parts()]
            else:
                values.append(v)
        node.values = values
        return self.generic_visit(node)


def _bindings(fn: ast.FunctionDef, name: str) -> list[ast.AST]:
    """Every node of `fn` that binds `name` (a statement, or a parameter)."""
    out: list[ast.AST] = [a for a in ast.walk(fn.args) if isinstance(a, ast.arg) and a.arg == name]
    for n in ast.walk(fn):
        if isinstance(n, ast.Name) and n.id == name and isinstance(n.ctx, (ast.Store, ast.Del)):
            out.append(n)
    return out


def _one(fn: ast.FunctionDef, q: str, name: str, kinds: dict[str, list[tuple[int, str]]], taken: set[str],
         counter: dict[str, int]) -> None:
    sites = [s for s in ast.walk(fn) if isinstance(s, ast.Assign) and len(s.targets) == 1
             and isinstance(s.targets[0], ast.Name) and s.targets[0].id == name and _is_range(s.value)]
    sites.sort(key=lambda s: (s.lineno, s.col_offset))
    targets = {id(s.targets[0]) for s in sites}
    for b in _bindings(fn, name):
        if id(b) not in targets:
            line = getattr(b, "lineno", sites[0].lineno)
            raise _Problem(line, f"`{name}` is bound to a range object at line {sites[0].lineno} and to something else "
                                 f"at line {line}: an AOT range object is a name bound only to range(...)")
    step: int | None = None
    for s in sites:
        call = s.value
        assert isinstance(call, ast.Call)
        if call.keywords or not 1 <= len(call.args) <= 3:
            raise _Problem(s.lineno, "range() takes one to three positional arguments")
        if any(isinstance(n, ast.Name) and n.id == name for a in call.args for n in ast.walk(a)):
            raise _Problem(s.lineno, f"`{_src(s)}` reads `{name}` while binding it to a new range object, which an AOT "
                                     "program does not do yet")
        this = 1
        if len(call.args) == 3:
            got = _int_constant(call.args[2])
            if got is None:
                raise _Problem(s.lineno, f"the step `{_src(call.args[2])}` of the range object `{name}` is an int "
                                         "constant in an AOT program")
            if got == 0:
                raise _Problem(s.lineno, f"`{_src(call)}`: range() arg 3 must not be zero (CPython raises ValueError; "
                                         "an AOT program refuses it when it is compiled)")
            this = got
        if step is not None and this != step:
            raise _Problem(s.lineno, f"the range object `{name}` is bound with the steps {step} and {this}: an AOT "
                                     "range object has one constant step")
        step = this
    assert step is not None
    lo, hi = _fresh(f"{name}__lo", taken, counter, q), _fresh(f"{name}__hi", taken, counter, q)
    r = _Range(name, lo, hi, _fresh(f"{name}__n", taken, counter, q), step)
    _Uses(r).visit(fn)
    ast.fix_missing_locations(fn)       # (a made node takes the location of the node it is in)
    for n in ast.walk(fn):
        if r.is_me(n):
            raise _Problem(getattr(n, "lineno", sites[0].lineno), f"`{name}` is a range object: {_USES}")
    for s in sites:
        call = s.value
        assert isinstance(call, ast.Call)
        a = call.args
        start: ast.expr = ast.Constant(0) if len(a) == 1 else a[0]
        stop = a[0] if len(a) == 1 else a[1]
        new = [ast.Assign(targets=[ast.Name(id=lo, ctx=ast.Store())], value=start),
               ast.Assign(targets=[ast.Name(id=hi, ctx=ast.Store())], value=stop)]
        if len(a) == 1:
            new.reverse()                       # (`stop` is evaluated first either way; the constant start after it)
        new.append(ast.Assign(targets=[ast.Name(id=r.n, ctx=ast.Store())], value=r.count()))
        for hidden in (lo, hi, r.n):
            kinds.setdefault(hidden, []).append((s.lineno, "int"))
        comprehensions._replace(fn, s, [comprehensions._located(x, s) for x in new])   # type: ignore[misc]


def _src(node: ast.AST) -> str:
    return ast.unparse(node)


def _fresh(base: str, taken: set[str], counter: dict[str, int], q: str) -> str:
    while True:
        counter[q] = counter.get(q, 0) + 1
        name = f"{base}{counter[q]}"
        if name not in taken:
            taken.add(name)
            return name


def rewrite(functions: list[tuple[str, ast.FunctionDef]], kinds: Kinds) -> tuple[Kinds, dict[str, str]]:
    """Rewrite every range object of the functions in place (see the module docstring): the kinds with the hidden
    bounds added, and the problems (IR function name -> "line N: ...")."""
    out = {q: {n: list(v) for n, v in d.items()} for q, d in kinds.items()}
    problems: dict[str, str] = {}
    for q, fn in functions:
        names = sorted({s.targets[0].id for s in ast.walk(fn) if isinstance(s, ast.Assign) and len(s.targets) == 1
                        and isinstance(s.targets[0], ast.Name) and _is_range(s.value)})
        if not names:
            continue
        taken = comprehensions._names(fn)
        counter: dict[str, int] = {}
        for name in names:
            try:
                _one(fn, q, name, out.setdefault(q, {}), taken, counter)
            except _Problem as e:
                problems.setdefault(q, f"line {e.line}: {e}")
                break
    return out, problems
