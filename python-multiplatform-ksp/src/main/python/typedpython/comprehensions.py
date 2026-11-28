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

`sum(it[, start])`, `any(it)`, `all(it)`, `min(it)` and `max(it)` that are the whole value of such a statement are the loops they mean too:
the iterable is any iterable a `for` loop takes (the site is the comprehension `(v for v in it)` with a hidden `v`,
typed at its probe as a comprehension variable is). An int sum adds as CPython's int fast path does (`acc = acc + v`);
a float sum takes a float constant start (an empty float sum with the default int start is the int 0, which a float
cannot hold) and adds with CPython's compensated summation (Neumaier: `cs_add` and `cs_to_double` in
Python/bltinmodule.c, each item through float() as `PyLong_AsDouble` takes an int); any() and all() stop at the first
true or false item, through every clause's loop; min() and max() keep the first item and replace it by one that
compares strictly smaller (larger), as `min_max` does, and of an empty iterable raise CPython's ValueError (an
`ir.Raise`, through a `raise` statement this pass marks) or, given `default=d`, are `d` (a constant or a name, of the
items' type: the rewrite reads it before the loop reads the iterable, which only a constant or a name allows); `sorted(it, reverse=r)` is the list built by the loop,
then sorted in place by CPython's listsort (`list.sort`, tp_aot.h), `r` a bool constant or a name.

`key=k` (sorted(), min(), max(), and `xs.sort(key=k)` as a statement): `k` is the name of a function or a lambda of one
parameter, applied by inlining (`f(v)`, or the lambda's body with its parameter as `v`), its value typed at the probe
through `(tp_key__p := <applied>), tp_key__p`. min() / max() compute each item's key as it is read and keep the item
whose key compares strictly smaller (larger). A keyed sort is what list_sort_impl does: `r` first, then the keys of
the list's items, left to right, into a hidden list, then the marker statement `KEYSORT(keys, r, xs)`, which the
lowering makes an `ir.ListSortKeyed` (the (key, position) pairs sorted by CPython's listsort, each list moved by the
positions; tuples.py gives it every element list of a list of tuples). `xs.sort(key=k)` only where the key cannot reach
`xs` (CPython shows the key an empty list): see `_list_keyed_sort`.

A generator expression that is the argument of such a call (leader, 2026-11-28: accepted, with laziness and the
short-circuit kept) is the site's comprehension itself, `sum(x * 2 for x in xs if c)`: its first iterable is evaluated
first, as the generator's creation evaluates it, and each item is computed when the loop reaches it, which is when the
call would take it from the generator: any() / all() stop computing at the first true (false) item, min() / max()
compute each item's key right after the item, sorted() computes every item before any key. sum(it, start) reads its
start before the loop, so a start that is not a constant or a name is refused unless the iterable is a name (CPython
evaluates the iterable first; this was a defect for any iterable before generators were accepted).

Not rewritten, left for the lowering to refuse naming the reason: a comprehension anywhere else (an operand, an
argument), a generator expression anywhere else (aot/generator), an async comprehension; and refused here: `:=` inside one (it binds
a name of the enclosing function in CPython), a nested comprehension inside one.
"""
from __future__ import annotations

import ast
from dataclasses import dataclass, field

from typedpython import rebinding

COMPS = (ast.ListComp, ast.SetComp, ast.DictComp)
REDUCERS = ("sum", "any", "all", "min", "max", "sorted")
Kinds = dict[str, dict[str, list[tuple[int, str]]]]
KEYSORT = "__tp_keysort"    # `KEYSORT(keys, reverse, L)`: sort L by the keys list (ir.ListSortKeyed); tuples.py splits L
KEY = "<key>"               # the probe's entry of a site's key, beside its variables
_KEY_TYPES = ("int", "float", "str")    # a key of a sort (a list of bool does not exist yet); min / max take bool too


@dataclass(frozen=True)
class Site:
    """A statement whose whole value is a comprehension, in the function `qname`."""
    qname: str
    stmt: ast.stmt
    comp: ast.ListComp | ast.SetComp | ast.DictComp | ast.GeneratorExp
    call: ast.Call | None = None          # sum() / any() / all(): the call, `comp` its iterable as `(v for v in it)`


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


def _value_of(s: ast.stmt) -> ast.expr | None:
    if isinstance(s, ast.Assign) and len(s.targets) == 1 and isinstance(s.targets[0], ast.Name):
        return s.value
    if isinstance(s, ast.AnnAssign) and isinstance(s.target, ast.Name) and s.value is not None:
        return s.value
    if isinstance(s, ast.Return) and s.value is not None:
        return s.value
    return None


def _reducer_of(s: ast.stmt) -> ast.Call | None:
    """The sum() / any() / all() call that is the whole value of `s` (a name assignment or a return), of an iterable
    that is not a generator expression."""
    value = _value_of(s)
    if isinstance(value, ast.Call) and isinstance(value.func, ast.Name) and value.func.id in REDUCERS \
            and value.args and not isinstance(value.args[0], ast.Starred) \
            and not (isinstance(value.args[0], ast.GeneratorExp) and any(g.is_async for g in value.args[0].generators)) \
            and not (value.func.id in ("min", "max", "sorted") and len(value.args) != 1):  # (min(a, b): an expression)
        return value
    return None


def _keyed_sort_of(s: ast.stmt) -> ast.Call | None:
    """`xs.sort(..., key=k, ...)` as a statement, of a name (tier S2c: the keyed sort this pass rewrites)."""
    if isinstance(s, ast.Expr) and isinstance(s.value, ast.Call) and isinstance(s.value.func, ast.Attribute) \
            and s.value.func.attr == "sort" and isinstance(s.value.func.value, ast.Name) \
            and any(k.arg == "key" for k in s.value.keywords):
        return s.value
    return None


def _key_of(call: ast.Call | None) -> ast.expr | None:
    return None if call is None else next((k.value for k in call.keywords if k.arg == "key"), None)


def _applied(key: ast.expr, arg: str) -> ast.expr:
    """The key applied to the local `arg`: `f(arg)` for a name, the lambda's body with its parameter as `arg`."""
    if isinstance(key, ast.Lambda):
        return _renamed(key.body, {key.args.args[0].arg: arg})              # type: ignore[return-value]
    return ast.Call(func=_renamed(key, {}), args=[ast.Name(id=arg, ctx=ast.Load())], keywords=[])


class _Substitute(ast.NodeTransformer):
    def __init__(self, name: str, value: ast.expr) -> None:
        self.name, self.value = name, value

    def visit_Name(self, node: ast.Name) -> ast.AST:
        import copy
        return copy.deepcopy(self.value) if node.id == self.name else node


def _applied_to(key: ast.expr, value: ast.expr) -> ast.expr:
    """The key applied to the expression `value` (only for the probe, which types it)."""
    import copy
    if isinstance(key, ast.Lambda):
        return _Substitute(key.args.args[0].arg, value).visit(copy.deepcopy(key.body))   # type: ignore[return-value]
    return ast.Call(func=copy.deepcopy(key), args=[copy.deepcopy(value)], keywords=[])


def _check_key(key: ast.expr, line: int) -> None:
    """A key is the name of a function or a lambda of one positional parameter whose body binds nothing and makes
    no function (inlined, it is evaluated where the key would be called, with the names it reads read then)."""
    if isinstance(key, ast.Name):
        return
    if not isinstance(key, ast.Lambda):
        raise _Problem(line, f"the key= `{ast.unparse(key)}` is the name of a function or a lambda of one parameter in "
                             "an AOT program")
    a = key.args
    if len(a.args) != 1 or a.posonlyargs or a.kwonlyargs or a.vararg or a.kwarg or a.defaults:
        raise _Problem(line, f"the key `{ast.unparse(key)}` is a lambda of one parameter in an AOT program")
    for n in ast.walk(key.body):
        if isinstance(n, (ast.NamedExpr, ast.Lambda, ast.GeneratorExp, ast.Yield, ast.YieldFrom, ast.Await, *COMPS)):
            raise _Problem(line, f"the key `{ast.unparse(key)}`: a lambda key holds no comprehension, lambda, generator "
                                 "or `:=` in an AOT program")


def _comp_of(s: ast.stmt) -> ast.ListComp | ast.SetComp | ast.DictComp | None:
    value = _value_of(s)
    if value is None:
        return None
    if isinstance(value, COMPS) and not any(g.is_async for g in value.generators):
        return value
    return None


def sites(functions: list[tuple[str, ast.FunctionDef]]) -> list[Site]:
    """Every statement of the functions whose value is a comprehension, or a sum() / any() / all(), this pass
    rewrites."""
    out: list[Site] = []
    for q, fn in functions:
        taken = _names(fn)
        k = 0
        for s in _own_statements(fn):
            if (c := _comp_of(s)) is not None:
                out.append(Site(q, s, c))
            elif (call := _reducer_of(s)) is not None and isinstance(call.args[0], ast.GeneratorExp):
                out.append(Site(q, s, call.args[0], call))     # (leader 2026-11-28: the generator is the loop it means)
            elif (call := _reducer_of(s) or _keyed_sort_of(s)) is not None:
                while True:
                    k += 1
                    v = f"item__s{k}"
                    if v not in taken:
                        taken.add(v)
                        break
                it = call.func.value if isinstance(call.func, ast.Attribute) else call.args[0]
                gen = ast.GeneratorExp(elt=ast.Name(id=v, ctx=ast.Load()), generators=[ast.comprehension(
                    target=ast.Name(id=v, ctx=ast.Store()), iter=it, ifs=[], is_async=0)])
                out.append(Site(q, s, ast.copy_location(gen, it), call))
    return out


def _variables(comp: ast.ListComp | ast.SetComp | ast.DictComp | ast.GeneratorExp) -> list[str]:
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
        key = _key_of(site.call)
        applied = ""
        if key is not None and isinstance(key, (ast.Name, ast.Lambda)) \
                and (len(names) == 1 or isinstance(site.comp, ast.GeneratorExp)):
            try:
                _check_key(key, s.lineno)
                applied = ast.unparse(_applied_to(key, site.comp.elt))             # type: ignore[union-attr]
            except _Problem:
                applied = ""                        # (refused with its reason by the rewrite)
        head = "[(" + "".join(f"{n}, " for n in names)
        offsets: dict[str, int] = {}
        # the loads: the tuple at the head; offsets in UTF-8 bytes from the start of the insertion
        pos = len("[(".encode())
        for n in names:
            offsets[n] = pos
            pos += len(f"{n}, ".encode())
        if applied:
            # the key's value, through a name whose load is typed: `(tp_key__p := <key applied>), tp_key__p, `
            bound = f"(tp_key__p := {applied}), "
            head += bound + "tp_key__p, "
            offsets[KEY] = pos + len(bound.encode())
        text = head + ") for "
        text += " for ".join(f"{ast.unparse(g.target)} in {ast.unparse(g.iter)}" for g in site.comp.generators)
        text += "]; "
        size = len(text.encode())
        encoded = lines[line_no - 1].encode()
        lines[line_no - 1] = encoded[:at].decode() + text + encoded[at:].decode()
        probes = [rebinding.Probe(p.name, p.line, p.column + size, p.assignment)
                  if p.line == line_no and p.column > at else p for p in probes]
        placed = {k: ((ln, col + size) if ln == line_no and col > at else (ln, col)) for k, (ln, col) in placed.items()}
        for n in offsets:
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


def _float_constant(e: ast.expr | None) -> bool:
    if isinstance(e, ast.UnaryOp) and isinstance(e.op, (ast.USub, ast.UAdd)):
        e = e.operand
    return isinstance(e, ast.Constant) and type(e.value) is float


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
    call = site.call
    key = _key_of(call)
    key_type = ""
    if key is not None:
        _check_key(key, line)
        key_type = _key_type(types.get((id(comp), KEY)), call, line)    # type: ignore[arg-type]
    if call is not None and isinstance(call.func, ast.Attribute):
        return _list_keyed_sort(site, fn, kinds, types, taken, counter, key, key_type)   # type: ignore[arg-type]
    if call is not None:
        if call.func.id == "sum":                                           # type: ignore[attr-defined]
            if len(call.args) + len(call.keywords) > 2 or any(k.arg != "start" for k in call.keywords) \
                    or (len(call.args) == 2 and call.keywords):
                raise _Problem(line, "sum() takes an iterable and a start in an AOT program")
            start = call.args[1] if len(call.args) == 2 else next((k.value for k in call.keywords), None)
            first = comp.generators[0].iter
            simple = lambda e: isinstance(e, (ast.Name, ast.Constant)) or (           # noqa: E731
                isinstance(e, ast.UnaryOp) and isinstance(e.operand, ast.Constant))
            if start is not None and not simple(start) and not simple(first):
                # the loop reads the start before the iterable, CPython the iterable first
                raise _Problem(line, f"`{ast.unparse(call)}`: CPython evaluates the iterable before the start; an AOT "
                                     "program takes a start that is a constant or a name unless the iterable is a name")
        elif call.func.id == "sorted":                                      # type: ignore[attr-defined]
            for k in call.keywords:
                if k.arg == "key":
                    continue
                if k.arg != "reverse":
                    raise _Problem(line, f"`{ast.unparse(call)}`: sorted() takes no keyword `{k.arg}`")
                if not (isinstance(k.value, ast.Name) or (isinstance(k.value, ast.Constant)
                                                          and isinstance(k.value.value, bool))):
                    raise _Problem(line, f"`{ast.unparse(call)}`: the reverse= of sorted() is a bool constant or a name "
                                         "in an AOT program (CPython evaluates it before it reads the iterable)")
        elif call.func.id in ("min", "max") and call.keywords:              # type: ignore[attr-defined]
            for k in call.keywords:
                if k.arg == "key":
                    continue
                if k.arg != "default":
                    raise _Problem(line, f"{call.func.id}() of an iterable takes no {k.arg}= in an AOT program yet")  # type: ignore[attr-defined]
                d = k.value.operand if isinstance(k.value, ast.UnaryOp) and isinstance(k.value.op, ast.USub) \
                    and isinstance(k.value.operand, ast.Constant) and type(k.value.operand.value) in (int, float) \
                    else k.value                                            # (a negative number is a constant too)
                if not (isinstance(d, ast.Name) or (isinstance(d, ast.Constant)
                                                    and type(d.value) in (int, float, str, bool))):
                    raise _Problem(line, f"`{ast.unparse(call)}`: the default= of {call.func.id}() is a constant or a "  # type: ignore[attr-defined]
                                         "name in an AOT program (the loop reads it before the iterable)")
        elif len(call.args) != 1 or call.keywords:
            raise _Problem(line, f"{call.func.id}() takes one argument")      # type: ignore[attr-defined]
    # the accumulator's type
    if isinstance(s, ast.AnnAssign):
        annotation = s.annotation
        base = s.target.id                                                  # type: ignore[union-attr]
    elif isinstance(s, ast.Assign):
        base = s.targets[0].id                                              # type: ignore[attr-defined]
        spelled = [k for ln, k in kinds.get(base, []) if ln == line]
        if not spelled:
            raise _Problem(line, f"no type is known for `{base}`, bound to a comprehension")
        if call is not None:
            annotation = _reduced_type(call, spelled[0], line)
            # (the target is bound to the result, of that type: Pyrefly spells an int sum `Literal[0] | int`)
            kinds[base] = [(ln, ast.unparse(annotation) if ln == line else k) for ln, k in kinds[base]]
        else:
            annotation = _annotation_of(spelled[0], line)
    else:
        if fn.returns is None:
            raise _Problem(line, "the function returning a comprehension has no return annotation")
        annotation, base = fn.returns, "result"
    if call is not None and not isinstance(s, ast.Assign):
        annotation = _reduced_type(call, ast.unparse(annotation), line)    # (an annotated target, a return)
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
    if call is not None:
        return _reduce(site, call, annotation, acc, clauses, mapping, kinds, taken, counter, key, key_type)
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


def _reduced_type(call: ast.Call, spelled: str, line: int) -> ast.expr:
    """The type of the value of a sum() / any() / all() call (Pyrefly's `spelled`), as an annotation."""
    name = call.func.id                                                     # type: ignore[attr-defined]
    if name in ("any", "all"):
        return ast.Name(id="bool", ctx=ast.Load())
    if name == "sorted":
        if spelled in ("list[int]", "list[float]", "list[str]") \
                or (_key_of(call) is not None and spelled.startswith("list[") and spelled.endswith("]")):
            return _annotation_of(spelled, line)        # (with a key, the items are any the lowering lists)
        raise _Problem(line, f"`{ast.unparse(call)}` is a {spelled}: an AOT program sorts int, float or str items")
    if name in ("min", "max"):
        import re
        parts = {"int" if re.fullmatch(r"(typing\.)?Literal\[-?\d+\]", p.strip()) else p.strip()
                 for p in spelled.split("|")}
        if len(parts) == 1 and next(iter(parts)) in _ZERO:
            return ast.Name(id=next(iter(parts)), ctx=ast.Load())
        if len(parts) == 1 and _key_of(call) is not None and _zero_tuple(next(iter(parts))) is not None:
            return _annotation_of(next(iter(parts)), line)      # (with a key: a tuple of int, float, str or bool)
        if any(k.arg == "default" for k in call.keywords):
            raise _Problem(line, f"`{ast.unparse(call)}` is a {spelled}: the default= of {name}() has the items' type "
                                 "in an AOT program (int, float, str or bool)")
        if _key_of(call) is not None:
            raise _Problem(line, f"`{ast.unparse(call)}` is a {spelled}: an AOT program compiles {name}() with a key of "
                                 "int, float, str or bool items, or of tuples of them")
        raise _Problem(line, f"`{ast.unparse(call)}` is a {spelled}: an AOT program compiles {name}() of int, float, "
                             "str or bool items")
    start = call.args[1] if len(call.args) == 2 else next((k.value for k in call.keywords if k.arg == "start"), None)
    import re
    # the union's parts, a literal int (the default start `Literal[0]`) as int
    parts = {"int" if re.fullmatch(r"(typing\.)?Literal\[-?\d+\]", p.strip()) else p.strip()
             for p in spelled.split("|")}
    if parts == {"int"}:
        return ast.Name(id="int", ctx=ast.Load())
    if "float" in parts and parts <= {"int", "float"}:
        if parts != {"float"} or not _float_constant(start):
            raise _Problem(line, f"`{ast.unparse(call)}`: a sum of floats with the int start 0 is the int 0 when it is "
                                 "empty, which a float cannot hold in an AOT program: give it a float constant start, "
                                 "`sum(xs, 0.0)`")
        return ast.Name(id="float", ctx=ast.Load())
    raise _Problem(line, f"`{ast.unparse(call)}` is a {spelled}: an AOT program compiles sum() of int, float or bool "
                         "items")


_ZERO = {"int": 0, "float": 0.0, "str": "", "bool": False}     # a placeholder of each type min() / max() hold


def _zero_tuple(spelled: str) -> ast.expr | None:
    """The placeholder of a `tuple[...]` of int, float, str and bool (a fixed shape), else None."""
    try:
        t = ast.parse(spelled, mode="eval").body
    except SyntaxError:
        return None
    if not (isinstance(t, ast.Subscript) and isinstance(t.value, ast.Name) and t.value.id == "tuple"):
        return None
    elts = t.slice.elts if isinstance(t.slice, ast.Tuple) else [t.slice]
    if not elts or not all(isinstance(e, ast.Name) and e.id in _ZERO for e in elts):
        return None
    return ast.Tuple(elts=[ast.Constant(_ZERO[e.id]) for e in elts], ctx=ast.Load())    # type: ignore[union-attr]


def _key_type(spelled: str | None, call: ast.Call, line: int) -> str:
    """The type of a key's value (int, float or str; of min() / max() bool too), from its probe."""
    import re
    name = call.func.attr if isinstance(call.func, ast.Attribute) else call.func.id      # type: ignore[attr-defined]
    if spelled is None:
        raise _Problem(line, f"`{ast.unparse(call)}`: no type is known for the value of its key")
    parts = {"int" if re.fullmatch(r"(typing\.)?Literal\[-?\d+\]", p.strip()) else rebinding._base(p.strip())
             for p in spelled.split("|")}
    allowed = (*_KEY_TYPES, "bool") if name in ("min", "max") else _KEY_TYPES
    if len(parts) != 1 or next(iter(parts)) not in allowed:
        raise _Problem(line, f"`{ast.unparse(call)}`: its key gives a {spelled}; an AOT program compares by an int, "
                             f"float or str key{' or a bool one' if name in ('min', 'max') else ''} (a tuple key is "
                             "not in the AOT mode yet)")
    return next(iter(parts))


_SCALARS = {"int", "float", "str", "bool", "bytes", "str | None", "None | str"}


def _keyed_tail(listname: str, key: ast.expr, key_type: str, reverse: ast.expr, item: str, line: int,
                kinds: dict[str, list[tuple[int, str]]], taken: set[str], counter: dict[str, int],
                q: str) -> list[ast.stmt]:
    """The keyed sort of the list `listname`, as list_sort_impl does it: `reverse` evaluated first, then the keys,
    one per item, left to right, into a hidden list, then the sort of the (key, position) pairs moving the items."""
    out: list[ast.stmt] = []
    rev = reverse
    if not isinstance(reverse, ast.Constant):
        r = _fresh("reverse", taken, counter, q)
        out.append(ast.AnnAssign(target=ast.Name(id=r, ctx=ast.Store()), annotation=ast.Name(id="bool", ctx=ast.Load()),
                                 value=reverse, simple=1))
        rev = ast.Name(id=r, ctx=ast.Load())
    keys = _fresh("keys", taken, counter, q)
    kv = _fresh("kv", taken, counter, q)
    kinds[kv] = [(line, item)]
    out.append(ast.AnnAssign(target=ast.Name(id=keys, ctx=ast.Store()), annotation=_annotation_of(f"list[{key_type}]", line),
                             value=ast.List(elts=[], ctx=ast.Load()), simple=1))
    append = ast.Expr(value=ast.Call(func=ast.Attribute(value=ast.Name(id=keys, ctx=ast.Load()), attr="append",
                                                        ctx=ast.Load()), args=[_applied(key, kv)], keywords=[]))
    out.append(ast.For(target=ast.Name(id=kv, ctx=ast.Store()), iter=ast.Name(id=listname, ctx=ast.Load()),
                       body=[append], orelse=[]))
    out.append(ast.Expr(value=ast.Call(func=ast.Name(id=KEYSORT, ctx=ast.Load()),
                                       args=[ast.Name(id=keys, ctx=ast.Load()), rev,
                                             ast.Name(id=listname, ctx=ast.Load())], keywords=[])))
    return out


def _list_keyed_sort(site: Site, fn: ast.FunctionDef, kinds: dict[str, list[tuple[int, str]]],
                     types: dict[tuple[int, str], str], taken: set[str], counter: dict[str, int], key: ast.expr,
                     key_type: str) -> list[ast.stmt]:
    """`xs.sort(key=k, reverse=r)`. While CPython computes the keys the list is empty (list_sort_impl sets its size
    to 0), so a key that reaches `xs` sees `[]`: the keys here are computed with `xs` as it is, which is the same only
    when the key cannot reach the list: the items are int, float, str, bool, bytes or tuples of them, and every name
    the key reads that the function binds holds one of those."""
    s, call, line = site.stmt, site.call, site.stmt.lineno
    assert call is not None and isinstance(call.func, ast.Attribute) and isinstance(call.func.value, ast.Name)
    name = call.func.value.id
    if call.args:
        raise _Problem(line, f"`{ast.unparse(call)}`: list.sort() takes no positional argument")
    for k in call.keywords:
        if k.arg not in ("key", "reverse"):
            raise _Problem(line, f"`{ast.unparse(call)}`: list.sort() takes no keyword `{k.arg}`")
    item = types.get((id(site.comp), site.comp.elt.id))                    # type: ignore[union-attr]
    if item is None:
        raise _Problem(line, f"`{ast.unparse(call)}`: no type is known for the items of `{name}`")
    empty = ("CPython shows the key an empty list while it computes the keys; sort a copy with sorted() instead")
    if item not in _SCALARS and _zero_tuple(item) is None:
        raise _Problem(line, f"`{ast.unparse(call)}`: the items of `{name}` are {item}, which may reach `{name}`, and "
                             + empty)
    bound = {a.arg for a in ast.walk(fn) if isinstance(a, ast.arg)} | \
        {n.id for n in ast.walk(fn) if isinstance(n, ast.Name) and isinstance(n.ctx, ast.Store)}
    if isinstance(key, ast.Lambda):
        param = key.args.args[0].arg
        called = {id(c.func) for c in ast.walk(key.body) if isinstance(c, ast.Call)}
        annotated = {a.arg: ast.unparse(a.annotation) for a in fn.args.args if a.annotation is not None}
        annotated.update({t.target.id: ast.unparse(t.annotation) for t in ast.walk(fn)
                          if isinstance(t, ast.AnnAssign) and isinstance(t.target, ast.Name)})
        for n in ast.walk(key.body):
            if not isinstance(n, ast.Name) or n.id == param or id(n) in called or n.id not in bound:
                continue
            spelled = {annotated[n.id]} if n.id in annotated else {k for _, k in kinds.get(n.id, [])}
            if not spelled or not all(t in _SCALARS for t in spelled):
                raise _Problem(line, f"`{ast.unparse(call)}`: its key reads `{n.id}`, which may be `{name}`, and "
                                     + empty)
    reverse = next((k.value for k in call.keywords if k.arg == "reverse"), ast.Constant(False))
    out = _keyed_tail(name, key, key_type, reverse, item, line, kinds, taken, counter, site.qname)
    return [_located(x, s) for x in out]                                    # type: ignore[misc]


def _reduce(site: Site, call: ast.Call, annotation: ast.expr, acc: str,
            clauses: list[tuple[ast.expr, ast.expr, list[ast.expr]]], mapping: dict[str, str],
            kinds: dict[str, list[tuple[int, str]]], taken: set[str], counter: dict[str, int],
            key: ast.expr | None = None, key_type: str = "") -> list[ast.stmt]:
    """The statements of a sum() / any() / all() site (see the module docstring)."""
    import copy
    s, line, name = site.stmt, site.stmt.lineno, call.func.id              # type: ignore[attr-defined]
    item = _renamed(site.comp.elt, mapping)                                 # type: ignore[union-attr]
    load = lambda n: ast.Name(id=n, ctx=ast.Load())         # noqa: E731
    store = lambda n: ast.Name(id=n, ctx=ast.Store())       # noqa: E731
    assign = lambda n, v: ast.Assign(targets=[store(n)], value=v)   # noqa: E731
    head: list[ast.stmt] = []
    tail: list[ast.stmt] = []
    stop: ast.expr | None = None
    if name == "sorted":
        reverse = next((k.value for k in call.keywords if k.arg == "reverse"), ast.Constant(False))
        head = [ast.AnnAssign(target=store(acc), annotation=annotation, value=ast.List(elts=[], ctx=ast.Load()),
                              simple=1)]
        inner = [ast.Expr(value=ast.Call(func=ast.Attribute(value=load(acc), attr="append", ctx=ast.Load()),
                                         args=[item], keywords=[]))]
        tail = [ast.Expr(value=ast.Call(func=ast.Attribute(value=load(acc), attr="sort", ctx=ast.Load()), args=[],
                                        keywords=[ast.keyword(arg="reverse", value=copy.deepcopy(reverse))]))]
        if key is not None:
            # the list built, then its keys, then the sort of the pairs (sorted() is list(it).sort(key=k))
            if isinstance(site.comp.elt, ast.Name) and site.comp.elt.id in mapping:
                spelled = [k for ln, k in kinds.get(mapping[site.comp.elt.id], []) if ln == line]
            else:                                   # a generator's element: the item type of the list it builds
                spelled = [ast.unparse(annotation.slice)] if isinstance(annotation, ast.Subscript) else []
            if not spelled:
                raise _Problem(line, f"`{ast.unparse(call)}`: no type is known for its items")
            tail = _keyed_tail(acc, key, key_type, copy.deepcopy(reverse), spelled[0], line, kinds, taken, counter,
                               site.qname)
    elif name in ("min", "max"):
        seen = _fresh("seen", taken, counter, site.qname)
        default = next((k.value for k in call.keywords if k.arg == "default"), None)
        placeholder = ast.Constant(_ZERO[annotation.id]) if isinstance(annotation, ast.Name) else \
            _zero_tuple(ast.unparse(annotation))
        assert placeholder is not None
        head = [ast.AnnAssign(target=store(seen), annotation=ast.Name(id="bool", ctx=ast.Load()),
                              value=ast.Constant(False), simple=1),
                ast.AnnAssign(target=store(acc), annotation=annotation,
                              value=copy.deepcopy(default) if default is not None else placeholder,
                              simple=1)]
        op = ast.Lt() if name == "min" else ast.Gt()
        if key is None:
            better = ast.Compare(left=item, ops=[op], comparators=[load(acc)])
            inner = [ast.If(test=ast.UnaryOp(op=ast.Not(), operand=load(seen)),
                            body=[assign(acc, copy.deepcopy(item)), assign(seen, ast.Constant(True))],
                            orelse=[ast.If(test=better, body=[assign(acc, copy.deepcopy(item))], orelse=[])])]
        else:
            # min_max: the key of each item as it is read; the item kept with its key, replaced by a strictly
            # smaller (larger) key
            best, kv = _fresh("best", taken, counter, site.qname), _fresh("kv", taken, counter, site.qname)
            kinds[kv] = [(line, key_type)]
            head.append(ast.AnnAssign(target=store(best), annotation=ast.Name(id=key_type, ctx=ast.Load()),
                                      value=ast.Constant(_ZERO[key_type]), simple=1))
            first_stmts: list[ast.stmt] = []
            if isinstance(item, ast.Name):
                held = item.id
            else:                                   # a generator's element, computed once per item
                held = _fresh("item", taken, counter, site.qname)
                kinds[held] = [(line, ast.unparse(annotation))]
                first_stmts = [assign(held, item)]
                item = load(held)
            better = ast.Compare(left=load(kv), ops=[op], comparators=[load(best)])
            keep = [assign(acc, copy.deepcopy(item)), assign(best, load(kv))]
            inner = [*first_stmts, assign(kv, _applied(key, held)),
                     ast.If(test=ast.UnaryOp(op=ast.Not(), operand=load(seen)),
                            body=[*copy.deepcopy(keep), assign(seen, ast.Constant(True))],
                            orelse=[ast.If(test=better, body=keep, orelse=[])])]
        empty = ast.Raise(exc=None, cause=None)
        empty.tp_builtin_raise = ("ValueError", f"{name}() iterable argument is empty")     # type: ignore[attr-defined]
        tail = [] if default is not None else [ast.If(test=ast.UnaryOp(op=ast.Not(), operand=load(seen)), body=[empty],
                                                      orelse=[])]
    elif name in ("any", "all"):
        found = name == "any"
        head = [ast.AnnAssign(target=store(acc), annotation=annotation, value=ast.Constant(not found), simple=1)]
        test = item if found else ast.UnaryOp(op=ast.Not(), operand=item)
        inner: list[ast.stmt] = [ast.If(test=test, body=[assign(acc, ast.Constant(found)), ast.Break()], orelse=[])]
        stop = load(acc) if found else ast.UnaryOp(op=ast.Not(), operand=load(acc))
    else:
        start = call.args[1] if len(call.args) == 2 else next((k.value for k in call.keywords if k.arg == "start"),
                                                               None)
        if isinstance(annotation, ast.Name) and annotation.id == "float":
            # CPython's compensated sum: hi the running sum, lo the lost low-order bits (cs_add, cs_to_double)
            lo, t, x = (_fresh(b, taken, counter, site.qname) for b in ("lo", "t", "x"))
            for n in (t, x):
                kinds[n] = [(line, "float")]
            head = [ast.AnnAssign(target=store(acc), annotation=annotation, value=copy.deepcopy(start), simple=1),
                    ast.AnnAssign(target=store(lo), annotation=ast.Name(id="float", ctx=ast.Load()),
                                  value=ast.Constant(0.0), simple=1)]
            absolute = lambda e: ast.Call(func=load("abs"), args=[e], keywords=[])     # noqa: E731
            plus = lambda a, b: ast.BinOp(left=a, op=ast.Add(), right=b)              # noqa: E731
            minus = lambda a, b: ast.BinOp(left=a, op=ast.Sub(), right=b)             # noqa: E731
            v = site.comp.elt.id if isinstance(site.comp.elt, ast.Name) else None   # type: ignore[union-attr]
            spelled = [k for ln, k in kinds.get(mapping[v], []) if ln == line] if v in mapping else []
            # (an int item is converted as PyLong_AsDouble converts it; a float item is taken as it is)
            converted = item if spelled == ["float"] else ast.Call(func=load("float"), args=[item], keywords=[])
            inner = [assign(x, converted),
                     assign(t, plus(load(acc), load(x))),
                     ast.If(test=ast.Compare(left=absolute(load(acc)), ops=[ast.GtE()],
                                             comparators=[absolute(load(x))]),
                            body=[assign(lo, plus(load(lo), plus(minus(load(acc), load(t)), load(x))))],
                            orelse=[assign(lo, plus(load(lo), plus(minus(load(x), load(t)), load(acc))))]),
                     assign(acc, load(t))]
            finite = ast.Compare(left=minus(load(lo), load(lo)), ops=[ast.Eq()], comparators=[ast.Constant(0.0)])
            nonzero = ast.Compare(left=load(lo), ops=[ast.NotEq()], comparators=[ast.Constant(0.0)])
            tail = [ast.If(test=ast.BoolOp(op=ast.And(), values=[nonzero, finite]),
                           body=[assign(acc, plus(load(acc), load(lo)))], orelse=[])]
        else:
            head = [ast.AnnAssign(target=store(acc), annotation=annotation,
                                  value=copy.deepcopy(start) if start is not None else ast.Constant(0), simple=1)]
            inner = [assign(acc, ast.BinOp(left=load(acc), op=ast.Add(), right=item))]
    body = inner
    for k, (target, it, ifs) in enumerate(reversed(clauses)):
        if k > 0 and stop is not None:
            body = body + [ast.If(test=copy.deepcopy(stop), body=[ast.Break()], orelse=[])]
        for cond in reversed(ifs):
            body = [ast.If(test=cond, body=body, orelse=[])]
        body = [ast.For(target=target, iter=it, body=body, orelse=[])]
    if isinstance(s, ast.Return):
        final: ast.stmt = ast.Return(value=load(acc))
    elif isinstance(s, ast.AnnAssign):
        final = ast.AnnAssign(target=s.target, annotation=s.annotation, value=load(acc), simple=s.simple)
    else:
        final = ast.Assign(targets=s.targets, value=load(acc))
    return [_located(x, s) for x in (*head, *body, *tail, final)]          # type: ignore[misc]


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
