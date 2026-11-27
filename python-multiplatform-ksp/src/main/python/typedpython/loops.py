"""AOT mode, tier S2c: `enumerate()` and `zip()` as the iterable of a loop (design 4.3.2, S2c). Agent proposal, not
owner-confirmed.

A `for` statement (one the comprehension rewrite made included) over `enumerate(...)` or `zip(...)` is rewritten, on
the AST the lowering reads, into the loop it means:

    for i, x in enumerate(it, 1):        n__e1: int = 1
        body                             for x in it:
                                             i = n__e1
                                             n__e1 = n__e1 + 1
                                             body

    for a, b in zip(xs, ys):             k__z1: int = 0
        body                             while k__z1 < len(xs) and k__z1 < len(ys):
                                             a = xs[k__z1]
                                             b = ys[k__z1]
                                             k__z1 = k__z1 + 1
                                             body

* enumerate: the iterable is any iterable a `for` loop takes; the counter is advanced before the body, so a
  `continue` counts; `i` is bound each step, so after the loop it holds the last count (and keeps its old value when
  the loop does not run), as in CPython. The start is an int constant (aot/program.py checks it), so evaluating it
  before the iterable is not observable.
* zip: each operand is the name of a list or a str (aot/program.py checks it is a name the loop does not rebind; the
  lowering refuses a dict or a set at the subscripts this pass marks, `tp_zip`). The length is read at every step, the
  operands left to right: a list iterator reads its list's length at every `next`, and zip stops at the first operand
  that is exhausted, so a list that grows or shrinks in the loop is seen doing so as CPython sees it. The elements are
  read in the order zip reads them.

Types: the hidden counter is an annotated int, and so is enumerate's count. zip's names are assignments now, typed
as the elements of the operands: the type Pyrefly gives each operand's load in the `for` header (`sites` names them;
the probes insert nothing before a `for` header on its line), `list[T]` giving T and `str` giving str. In a loop the
comprehension rewrite made, the names are the comprehension's hidden variables, already typed at its probe.
"""
from __future__ import annotations

import ast
import copy

from typedpython import comprehensions

Kinds = comprehensions.Kinds


def sites(functions: list[tuple[str, ast.FunctionDef]]) -> list[ast.Name]:
    """The name operands of the zip() loops of the functions, whose types `rewrite` needs (at their load)."""
    return [a for _, fn in functions for f in ast.walk(fn) if isinstance(f, ast.For) and _counted(f) == "zip"
            for a in f.iter.args if isinstance(a, ast.Name)]                 # type: ignore[attr-defined]


def _element(spelled: str | None, operand: ast.expr) -> str | None:
    """The type of an element of the operand `operand` of zip() typed `spelled`."""
    if isinstance(operand, ast.Constant) and isinstance(operand.value, str):
        return "str"
    if spelled is None:
        return None
    if spelled == "str":
        return "str"
    if spelled.startswith("list[") and spelled.endswith("]"):
        return spelled[5:-1]
    return None


def _counted(f: ast.For) -> str | None:
    it = f.iter
    if isinstance(it, ast.Call) and isinstance(it.func, ast.Name) and it.func.id in ("enumerate", "zip") \
            and not it.keywords and not f.orelse:
        return it.func.id
    return None


def _start(e: ast.expr) -> int:
    sign = 1
    if isinstance(e, ast.UnaryOp):
        sign = -1 if isinstance(e.op, ast.USub) else 1
        e = e.operand
    assert isinstance(e, ast.Constant) and type(e.value) is int
    return sign * e.value


def _operand(a: ast.expr) -> bool:
    return isinstance(a, ast.Name) or (isinstance(a, ast.Constant) and isinstance(a.value, str))


def _name(n: str, ctx: ast.expr_context) -> ast.Name:
    return ast.Name(id=n, ctx=ctx)


def _add_one(n: str) -> ast.stmt:
    return ast.Assign(targets=[_name(n, ast.Store())],
                      value=ast.BinOp(left=_name(n, ast.Load()), op=ast.Add(), right=ast.Constant(1)))


def _rewrite(f: ast.For, kind: str, fresh: str) -> list[ast.stmt] | None:
    it = f.iter
    assert isinstance(it, ast.Call)
    if kind == "enumerate":
        if not (1 <= len(it.args) <= 2 and isinstance(f.target, ast.Tuple) and len(f.target.elts) == 2
                and isinstance(f.target.elts[0], ast.Name)):
            return None                                  # (aot/program.py reports it)
        start = _start(it.args[1]) if len(it.args) == 2 else 0
        init = ast.AnnAssign(target=_name(fresh, ast.Store()), annotation=_name("int", ast.Load()),
                             value=ast.Constant(start), simple=1)
        step = [ast.Assign(targets=[f.target.elts[0]], value=_name(fresh, ast.Load())), _add_one(fresh)]
        loop = ast.For(target=f.target.elts[1], iter=it.args[0], body=step + f.body, orelse=[], type_comment=None)
        return [comprehensions._located(x, f) for x in (init, loop)]       # type: ignore[misc]
    operands = it.args
    if not (operands and all(_operand(a) for a in operands) and isinstance(f.target, ast.Tuple)
            and len(f.target.elts) == len(operands) and all(isinstance(e, ast.Name) for e in f.target.elts)):
        return None
    init = ast.AnnAssign(target=_name(fresh, ast.Store()), annotation=_name("int", ast.Load()),
                         value=ast.Constant(0), simple=1)
    tests = [ast.Compare(left=_name(fresh, ast.Load()), ops=[ast.Lt()],
                         comparators=[ast.Call(func=_name("len", ast.Load()), args=[copy.deepcopy(a)], keywords=[])])
             for a in operands]
    test: ast.expr = tests[0] if len(tests) == 1 else ast.BoolOp(op=ast.And(), values=tests)
    reads: list[ast.stmt] = []
    for t, a in zip(f.target.elts, operands):
        sub = ast.Subscript(value=copy.deepcopy(a), slice=_name(fresh, ast.Load()), ctx=ast.Load())
        sub.tp_zip = True                                # type: ignore[attr-defined]  (see the module docstring)
        reads.append(ast.Assign(targets=[t], value=sub))
    loop = ast.While(test=test, body=reads + [_add_one(fresh)] + f.body, orelse=[])
    return [comprehensions._located(x, f) for x in (init, loop)]           # type: ignore[misc]


def rewrite(functions: list[tuple[str, ast.FunctionDef]], kinds: Kinds,
            types: dict[int, str]) -> tuple[Kinds, dict[str, str]]:
    """Rewrite every such loop of the functions in place (see the module docstring): the kinds with the hidden counters
    and the loops' names added, and the problems (IR function name -> "line N: ..."). `types`: id of an operand of
    `sites` -> the type Pyrefly gives its load."""
    out = {q: {n: list(v) for n, v in d.items()} for q, d in kinds.items()}
    problems: dict[str, str] = {}
    for q, fn in functions:
        loops = [n for n in ast.walk(fn) if isinstance(n, ast.For) and _counted(n) is not None]
        if not loops:
            continue
        taken = comprehensions._names(fn)
        counter: dict[str, int] = {}
        for f in loops:
            kind = _counted(f)
            assert kind is not None
            base = "n" if kind == "enumerate" else "k"
            while True:
                counter[q] = counter.get(q, 0) + 1
                fresh = f"{base}__{kind[0]}{counter[q]}"
                if fresh not in taken:
                    taken.add(fresh)
                    break
            target, operands = f.target, f.iter.args                   # type: ignore[attr-defined]
            new = _rewrite(f, kind, fresh)
            if new is None:
                continue
            assert isinstance(target, ast.Tuple)
            mine = out.setdefault(q, {})
            mine[fresh] = [(f.lineno, "int")]
            names = list(zip(target.elts, operands)) if kind == "zip" else [(target.elts[0], None)]
            for t, a in names:
                assert isinstance(t, ast.Name)
                if any(ln == f.lineno for ln, _ in mine.get(t.id, [])):
                    continue                    # (a comprehension's hidden variable, typed at its probe)
                elem = "int" if a is None else _element(types.get(id(a)), a)
                if elem is None:
                    problems.setdefault(q, f"line {f.lineno}: `{ast.unparse(a)}` is a {types.get(id(a), 'value of an '
                                        'unknown type')}: zip() reads each operand by index in an AOT program, so an "
                                        "operand is a list or a str")
                    break
                mine.setdefault(t.id, []).append((f.lineno, elem))
            comprehensions._replace(fn, f, new)
    return out, problems
