"""AOT mode, tier S2c: list, set and dict comprehensions (design 4.3.2, S2c). Agent proposal, not owner-confirmed.

A comprehension that is the whole value of an assignment to a name (`xs = [...]`, `xs: list[int] = [...]`) or of a
`return` is rewritten, on the AST the lowering reads, into the loop it means:

    ys = [e for x in xs if c]        ys__c1: list[int] = []
                                     for x__c1 in xs:
                                         if c':
                                             ys__c1.append(e')
                                     ys = ys__c1

* Python 3 scoping: every comprehension variable is a hidden local (`x__c1`), so a name of the same spelling in the
  enclosing function is not touched; the accumulator is hidden too, so the target is bound only once the comprehension
  is complete (and `xs = [x + 1 for x in xs]` iterates the old list).
* The order of evaluation is CPython's: the first iterable first, then per item the conditions left to right and the
  element; of a dict comprehension the key before the value (PEP 572 / 3.8 order), through a temporary of the key type
  when the key is not a name or a constant.
* Types: the accumulator has the type of what it is bound to (the annotation, the type Pyrefly gives the target name,
  or the return annotation). A comprehension variable's type is read from Pyrefly at a probe: the copy of the source
  that Pyrefly types gets, just before the statement, `[(x, y,) for x in xs for y in ys]` (the clauses without their
  conditions, so a variable has its declared type, not a narrowed one), and each variable's type is the type of its
  load in that probe. The hidden local gets that type as an injected `kinds` entry.

Not rewritten, left for the lowering to refuse naming the reason: a comprehension anywhere else (an operand, an
argument), a generator expression (aot/generator), an async comprehension; and refused here: `:=` inside one (it binds
a name of the enclosing function in CPython), a nested comprehension inside one.
"""
from __future__ import annotations

import ast
from dataclasses import dataclass, field

from typedpython import rebinding

COMPS = (ast.ListComp, ast.SetComp, ast.DictComp)
Kinds = dict[str, dict[str, list[tuple[int, str]]]]


@dataclass(frozen=True)
class Site:
    """A statement whose whole value is a comprehension, in the function `qname`."""
    qname: str
    stmt: ast.stmt
    comp: ast.ListComp | ast.SetComp | ast.DictComp


@dataclass
class Rewrite:
    kinds: Kinds
    problems: dict[str, str] = field(default_factory=dict)      # IR function name -> skip reason ("line N: ...")


class _Problem(Exception):
    def __init__(self, line: int, message: str) -> None:
        super().__init__(message)
        self.line = line


def _own_statements(fn: ast.FunctionDef) -> list[ast.stmt]:
    return [s for s in rebinding._own_statements(fn) if s is not fn]


def _comp_of(s: ast.stmt) -> ast.ListComp | ast.SetComp | ast.DictComp | None:
    if isinstance(s, ast.Assign) and len(s.targets) == 1 and isinstance(s.targets[0], ast.Name):
        value = s.value
    elif isinstance(s, ast.AnnAssign) and isinstance(s.target, ast.Name) and s.value is not None:
        value = s.value
    elif isinstance(s, ast.Return) and s.value is not None:
        value = s.value
    else:
        return None
    if isinstance(value, COMPS) and not any(g.is_async for g in value.generators):
        return value
    return None


def sites(functions: list[tuple[str, ast.FunctionDef]]) -> list[Site]:
    """Every statement of the functions whose value is a comprehension this pass rewrites."""
    return [Site(q, s, c) for q, fn in functions for s in _own_statements(fn) if (c := _comp_of(s)) is not None]


def _variables(comp: ast.ListComp | ast.SetComp | ast.DictComp) -> list[str]:
    """The comprehension's variables, in the order their clauses bind them (each once)."""
    names: list[str] = []
    for g in comp.generators:
        for n in ast.walk(g.target):
            if isinstance(n, ast.Name) and n.id not in names:
                names.append(n.id)
    return names


def probe(probed: str, probes: list[rebinding.Probe], wanted: list[rebinding.Assignment],
          found: list[Site]) -> tuple[str, list[rebinding.Probe], dict[tuple[int, str], tuple[int, int]]]:
    """`probed` (rebinding.probe's copy) with a probe before each site's statement: (the new copy, the assignment
    probes moved to where they now are, (id of the comprehension, variable) -> (line, column) of its load)."""
    lines = probed.splitlines(keepends=True)
    placed: dict[tuple[int, str], tuple[int, int]] = {}
    inserts: list[tuple[int, int, Site]] = []
    for site in found:
        s = site.stmt
        # the statement's column in the copy: the assignment probes inserted before it on its line come first
        shift = sum(len(("; " + a.name).encode()) for a in wanted
                    if a.end_line == s.lineno and a.end_byte <= s.col_offset)
        inserts.append((s.lineno, s.col_offset + shift, site))
    for line_no, at, site in sorted(inserts, key=lambda t: (t[0], t[1]), reverse=True):
        names = _variables(site.comp)
        if not names:
            continue
        head = "[(" + "".join(f"{n}, " for n in names) + ") for "
        offsets: dict[str, int] = {}
        text = head
        # the loads: the tuple at the head; offsets in UTF-8 bytes from the start of the insertion
        pos = len("[(".encode())
        for n in names:
            offsets[n] = pos
            pos += len(f"{n}, ".encode())
        text += " for ".join(f"{ast.unparse(g.target)} in {ast.unparse(g.iter)}" for g in site.comp.generators)
        text += "]; "
        size = len(text.encode())
        encoded = lines[line_no - 1].encode()
        lines[line_no - 1] = encoded[:at].decode() + text + encoded[at:].decode()
        probes = [rebinding.Probe(p.name, p.line, p.column + size, p.assignment)
                  if p.line == line_no and p.column > at else p for p in probes]
        placed = {k: ((ln, col + size) if ln == line_no and col > at else (ln, col)) for k, (ln, col) in placed.items()}
        for n in names:
            placed[(id(site.comp), n)] = (line_no, at + offsets[n] + 1)
    return "".join(lines), probes, placed


def _names(fn: ast.FunctionDef) -> set[str]:
    return {n.id for n in ast.walk(fn) if isinstance(n, ast.Name)} | {a.arg for a in ast.walk(fn)
                                                                     if isinstance(a, ast.arg)}


class _Rename(ast.NodeTransformer):
    def __init__(self, mapping: dict[str, str]) -> None:
        self.mapping = mapping

    def visit_Name(self, node: ast.Name) -> ast.AST:
        if node.id in self.mapping:
            return ast.copy_location(ast.Name(id=self.mapping[node.id], ctx=node.ctx), node)
        return node


def _renamed(node: ast.AST, mapping: dict[str, str]) -> ast.AST:
    import copy
    return _Rename(mapping).visit(copy.deepcopy(node))


def _located(node: ast.AST, at: ast.stmt) -> ast.AST:
    """`node` with the location of `at` wherever it has none (the nodes this pass makes)."""
    for n in ast.walk(node):
        if "lineno" in n._attributes and not hasattr(n, "lineno"):
            n.lineno, n.col_offset = at.lineno, at.col_offset
            n.end_lineno, n.end_col_offset = at.end_lineno, at.end_col_offset
    return node


def _annotation_of(spelled: str, line: int) -> ast.expr:
    try:
        return ast.parse(spelled, mode="eval").body
    except SyntaxError:
        raise _Problem(line, f"the comprehension is bound to a `{spelled}`, a type the AOT mode does not spell")


def rewrite(found: list[Site], functions: list[tuple[str, ast.FunctionDef]], kinds: Kinds,
            types: dict[tuple[int, str], str]) -> Rewrite:
    """Rewrite every site in place (see the module docstring). `types`: (id of the comprehension, variable) -> the
    type Pyrefly gives the variable at its probe."""
    out = Rewrite({q: {n: list(v) for n, v in d.items()} for q, d in kinds.items()})
    by_function = dict(functions)
    taken = {q: _names(fn) for q, fn in functions}
    counter: dict[str, int] = {}
    for site in found:
        if site.qname in out.problems:
            continue
        try:
            new = _rewrite_site(site, by_function[site.qname], out.kinds.setdefault(site.qname, {}), types,
                                taken[site.qname], counter)
        except _Problem as e:
            out.problems[site.qname] = f"line {e.line}: {e}"
            continue
        _replace(by_function[site.qname], site.stmt, new)
    return out


def _fresh(base: str, taken: set[str], counter: dict[str, int], q: str) -> str:
    while True:
        counter[q] = counter.get(q, 0) + 1
        name = f"{base}__c{counter[q]}"
        if name not in taken:
            taken.add(name)
            return name


def _rewrite_site(site: Site, fn: ast.FunctionDef, kinds: dict[str, list[tuple[int, str]]],
                  types: dict[tuple[int, str], str], taken: set[str], counter: dict[str, int]) -> list[ast.stmt]:
    s, comp, line = site.stmt, site.comp, site.stmt.lineno
    for n in ast.walk(comp):
        if isinstance(n, ast.NamedExpr):
            raise _Problem(n.lineno, f"`{ast.unparse(n)}`: `:=` inside a comprehension binds a name of the "
                                     "enclosing function in CPython, which an AOT comprehension does not do yet")
        if n is not comp and isinstance(n, (*COMPS, ast.GeneratorExp, ast.Lambda)):
            raise _Problem(n.lineno, f"`{ast.unparse(n)}` inside the comprehension `{ast.unparse(comp)}`: a "
                                     "comprehension holds no comprehension, generator or lambda in an AOT program yet")
    # the accumulator's type
    if isinstance(s, ast.AnnAssign):
        annotation = s.annotation
        base = s.target.id                                                  # type: ignore[union-attr]
    elif isinstance(s, ast.Assign):
        base = s.targets[0].id                                              # type: ignore[attr-defined]
        spelled = [k for ln, k in kinds.get(base, []) if ln == line]
        if not spelled:
            raise _Problem(line, f"no type is known for `{base}`, bound to a comprehension")
        annotation = _annotation_of(spelled[0], line)
    else:
        if fn.returns is None:
            raise _Problem(line, "the function returning a comprehension has no return annotation")
        annotation, base = fn.returns, "result"
    import copy
    annotation = copy.deepcopy(annotation)
    acc = _fresh(base, taken, counter, site.qname)

    # the variables: hidden locals typed at their probe
    mapping: dict[str, str] = {}
    clauses: list[tuple[ast.expr, ast.expr, list[ast.expr]]] = []
    for g in comp.generators:
        it = _renamed(g.iter, mapping)          # an iterable sees the variables of the clauses before it
        for n in ast.walk(g.target):
            if not isinstance(n, (ast.Name, ast.Tuple, ast.Store)):
                raise _Problem(line, f"the comprehension target `{ast.unparse(g.target)}` is not a name or a tuple of "
                                     "names")
            if isinstance(n, ast.Name) and n.id not in mapping:
                spelled = types.get((id(comp), n.id))
                if spelled is None:
                    raise _Problem(line, f"no type is known for the comprehension variable `{n.id}`")
                hidden = _fresh(n.id, taken, counter, site.qname)
                kinds[hidden] = [(line, spelled)]
                mapping[n.id] = hidden
        target = _renamed(g.target, mapping)
        clauses.append((target, it, [_renamed(c, mapping) for c in g.ifs]))  # type: ignore[misc]

    # the innermost statement: add the element
    accn = lambda ctx: ast.Name(id=acc, ctx=ctx)    # noqa: E731
    if isinstance(comp, ast.DictComp):
        key, value = _renamed(comp.key, mapping), _renamed(comp.value, mapping)
        inner: list[ast.stmt] = []
        if not isinstance(key, (ast.Name, ast.Constant)):
            if not (isinstance(annotation, ast.Subscript) and isinstance(annotation.slice, ast.Tuple)
                    and len(annotation.slice.elts) == 2):
                raise _Problem(line, f"the dict comprehension is bound to `{ast.unparse(annotation)}`, not a "
                                     "dict[K, V]")
            ktmp = _fresh("key", taken, counter, site.qname)
            inner.append(ast.AnnAssign(target=ast.Name(id=ktmp, ctx=ast.Store()),
                                       annotation=copy.deepcopy(annotation.slice.elts[0]), value=key, simple=1))
            key = ast.Name(id=ktmp, ctx=ast.Load())
        inner.append(ast.Assign(targets=[ast.Subscript(value=accn(ast.Load()), slice=key, ctx=ast.Store())],
                                value=value))
        empty: ast.expr = ast.Dict(keys=[], values=[])
    else:
        method = "append" if isinstance(comp, ast.ListComp) else "add"
        inner = [ast.Expr(value=ast.Call(func=ast.Attribute(value=accn(ast.Load()), attr=method, ctx=ast.Load()),
                                         args=[_renamed(comp.elt, mapping)], keywords=[]))]
        empty = ast.List(elts=[], ctx=ast.Load()) if isinstance(comp, ast.ListComp) else \
            ast.Call(func=ast.Name(id="set", ctx=ast.Load()), args=[], keywords=[])

    # the loops, innermost first
    body = inner
    for target, it, ifs in reversed(clauses):
        for cond in reversed(ifs):
            body = [ast.If(test=cond, body=body, orelse=[])]
        body = [ast.For(target=target, iter=it, body=body, orelse=[])]

    init = ast.AnnAssign(target=accn(ast.Store()), annotation=annotation, value=empty, simple=1)
    if isinstance(s, ast.Return):
        final: ast.stmt = ast.Return(value=accn(ast.Load()))
    elif isinstance(s, ast.AnnAssign):
        final = ast.AnnAssign(target=s.target, annotation=s.annotation, value=accn(ast.Load()), simple=s.simple)
    else:
        final = ast.Assign(targets=s.targets, value=accn(ast.Load()))
    return [_located(x, s) for x in (init, *body, final)]               # type: ignore[misc]


def _replace(fn: ast.FunctionDef, old: ast.stmt, new: list[ast.stmt]) -> None:
    """Replace the statement `old` of `fn` (in any block of its own) by `new`."""
    stack: list[ast.AST] = [fn]
    while stack:
        node = stack.pop()
        for name in ("body", "orelse", "finalbody"):
            block = getattr(node, name, None)
            if isinstance(block, list) and old in block:
                i = block.index(old)
                block[i:i + 1] = new
                return
            if isinstance(block, list):
                stack += [b for b in block if not isinstance(b, (ast.FunctionDef, ast.ClassDef, ast.AsyncFunctionDef))]
        for h in getattr(node, "handlers", []):
            stack.append(h)
        for c in getattr(node, "cases", []):
            stack.append(c)
    raise AssertionError("the statement is not in its function")
