"""AOT mode, tier S2a: tuple values of a fixed shape (design 4.3.2, "Tuples").

A `tuple[int, str, ...]` is immutable and its shape is in its type, so a tuple held by a local or passed to a parameter
has no observable identity in the subset this step accepts (no `is`, no `id`, never stored into a container or a
field): this pass splits every such tuple into one local per element before the function is lowered, on the AST the
lowering reads, after Pyrefly has typed the original source. Each element local is typed by the tuple's element kind
(the injected `kinds` entries, or an annotation the rewrite writes), so the lowering, the verifier and the back end see
plain ints, floats, bools and strs and add nothing for tuples.

What is rewritten, with CPython's meaning kept:

* `t = (a, b)` / `t: tuple[int, str] = (a, b)`: each element evaluated in order, then bound (through temporaries when
  an element reads the tuple's own elements, CPython builds the whole tuple before binding); `t = u` copies the element
  locals; `t = f(...)` of a program function returning a fixed tuple is the unboxed multi-value return of tier K
  (`t__0, t__1 = f(...)`, an `ir.Unpack`).
* `t[k]` for a constant k inside the shape (negative counted from the end); `len(t)`; `a, b = t`; `a, b = x, y`
  (the swap idiom: every value first, then the names, left to right).
* `t == u`, `!=`, `<`, `<=`, `>`, `>=`: CPython's tuple rich comparison (Objects/tupleobject.c tuplerichcompare): the
  first index whose items differ decides with the operator, else the lengths do. Items are compared by `==` here, which
  for int, bool and str is what CPython's identity-or-equality test gives; a float item makes it depend on identity
  (NaN), so a comparison of tuples with a float element is refused (aot/identity), as for lists.
* `x in t`: `x == t[0] or x == t[1] ...` (identity-or-equality; refused for float, as above).
* `print(t)`, `str(t)`, `repr(t)`, `f"{t}"`: the repr, `(1, 'a')`, and `(1,)` for one element.
* `for v in t` over a tuple whose elements share one kind: a hidden index over `range(len(t))` and `v` the element.
* `return t` in a function declared `-> tuple[...]`: the elements (`return t__0, t__1`); `return f(...)` of such a
  call: the elements through temporaries.
* a parameter `p: tuple[int, str]` of a module-level function is one parameter per element, and every call of that
  function passes a tuple name or a tuple display as the elements.

Anything else that reads or binds a tuple name leaves the function uncompiled with a finding naming it (`problems`),
never a guess. Methods keep their parameters (a tuple parameter of a method is not split in this step)."""

from __future__ import annotations

import ast
import copy
from dataclasses import dataclass, field

ELEMENT_KINDS = ("int", "float", "bool", "str")
_SEQ = "__"                     # element locals are `<name>__<i>`

Kinds = dict[str, dict[str, list[tuple[int, str]]]]


def shape_of(kind: str | None) -> tuple[str, ...] | None:
    """`tuple[int, str]` -> ("int", "str"); None for anything else (a variable-length tuple, another element kind)."""
    if not kind:
        return None
    try:
        e = ast.parse(kind, mode="eval").body
    except SyntaxError:
        return None
    if not (isinstance(e, ast.Subscript) and isinstance(e.value, ast.Name) and e.value.id == "tuple"):
        return None
    items = e.slice.elts if isinstance(e.slice, ast.Tuple) else [e.slice]
    out = []
    for i in items:
        if not (isinstance(i, ast.Name) and i.id in ELEMENT_KINDS):
            return None
        out.append(i.id)
    return tuple(out) or None


def annotation_shape(ann: ast.expr | None) -> tuple[str, ...] | None:
    return shape_of(ast.unparse(ann)) if ann is not None else None


@dataclass
class Split:
    kinds: Kinds
    problems: dict[str, str] = field(default_factory=dict)      # IR function name -> skip reason ("line N: ...")


class _Problem(Exception):
    def __init__(self, line: int, message: str) -> None:
        super().__init__(message)
        self.line = line
        self.message = message


def split(tree: ast.Module, kinds: Kinds) -> Split:
    """Rewrite every function of `tree` in place (module functions and methods) and return the element locals' kinds
    beside the others, and the functions this step cannot compile with the reason."""
    out = Split({q: {n: list(v) for n, v in d.items()} for q, d in kinds.items()})
    functions: list[tuple[str, ast.FunctionDef, bool]] = []
    for s in tree.body:
        if isinstance(s, ast.FunctionDef):
            functions.append((s.name, s, True))
        elif isinstance(s, ast.ClassDef):
            functions += [(f"{s.name}.{m.name}", m, False) for m in s.body if isinstance(m, ast.FunctionDef)]
    params = {q: [annotation_shape(a.annotation) for a in fn.args.args] for q, fn, top in functions if top}
    returns = {q: annotation_shape(fn.returns) for q, fn, _ in functions}
    for q, fn, top in functions:
        if not top and not any(annotation_shape(a.annotation) for a in fn.args.args) and not _mentions_tuples(fn, out.kinds.get(q, {})):
            continue
        work = _Function(q, fn, top, out.kinds.setdefault(q, {}), params, returns)
        original = copy.deepcopy(fn)
        try:
            work.run()
        except _Problem as p:
            # (the function is left as it was; the lowering is told not to compile it)
            fn.args, fn.body = original.args, original.body
            out.problems[q] = f"line {p.line}: {p.message}"
    return out


def _mentions_tuples(fn: ast.FunctionDef, fkinds: dict[str, list[tuple[int, str]]]) -> bool:
    if any(shape_of(k) for v in fkinds.values() for _, k in v):
        return True
    return any(isinstance(n, ast.AnnAssign) and annotation_shape(n.annotation) for n in ast.walk(fn)) \
        or any(isinstance(n, ast.Tuple) and isinstance(n.ctx, ast.Load) for n in ast.walk(fn))


def _name(id_: str, ctx: ast.expr_context | None = None) -> ast.Name:
    return ast.Name(id=id_, ctx=ctx or ast.Load())


def _pure(e: ast.expr) -> bool:
    """Evaluating `e` again is unobservable (a name or a constant)."""
    return isinstance(e, (ast.Name, ast.Constant)) or (
        isinstance(e, ast.UnaryOp) and isinstance(e.op, ast.USub) and isinstance(e.operand, ast.Constant))


def _same(x: ast.expr, y: ast.expr) -> bool:
    """`x` and `y` are one value (the same name, or equal constants of one type)."""
    if isinstance(x, ast.Name) and isinstance(y, ast.Name):
        return x.id == y.id
    if isinstance(x, ast.Constant) and isinstance(y, ast.Constant):
        return type(x.value) is type(y.value) and x.value == y.value
    return False


def _repr_pieces(elements: list[ast.expr]) -> list[ast.expr]:
    """The f-string pieces of a tuple's repr: `(`, each element's repr joined by `, `, `,` for one element, `)`."""
    parts: list[ast.expr] = [ast.Constant("(")]
    for i, e in enumerate(elements):
        if i:
            parts.append(ast.Constant(", "))
        parts.append(ast.FormattedValue(value=e, conversion=ord("r"), format_spec=None))
    parts.append(ast.Constant(",)" if len(elements) == 1 else ")"))
    return parts


class _Function:
    def __init__(self, qname: str, fn: ast.FunctionDef, top: bool, kinds: dict[str, list[tuple[int, str]]],
                 params: dict[str, list[tuple[str, ...] | None]], returns: dict[str, tuple[str, ...] | None]) -> None:
        self.q, self.fn, self.top, self.kinds = qname, fn, top, kinds
        self.params, self.returns = params, returns
        self.names = {n.id for n in ast.walk(fn) if isinstance(n, ast.Name)} | {a.arg for a in fn.args.args}
        # (the names the function binds: a builtin or a program function of that name is shadowed)
        self.locals = {n.id for n in ast.walk(fn) if isinstance(n, ast.Name) and not isinstance(n.ctx, ast.Load)} \
            | {a.arg for a in fn.args.args}
        self.tuples: dict[str, tuple[str, ...]] = {}
        self.temp_n = 0

    # -- which names are tuples

    def find(self) -> None:
        if self.top:
            for a in self.fn.args.args:
                s = annotation_shape(a.annotation)
                if s is not None:
                    self.tuples[a.arg] = s
        for n in ast.walk(self.fn):
            if isinstance(n, ast.AnnAssign) and isinstance(n.target, ast.Name):
                s = annotation_shape(n.annotation)
                if s is not None:
                    self.claim(n.target.id, s, n.lineno)
        for name, entries in self.kinds.items():
            shapes = {shape_of(k) for _, k in entries}
            if shapes == {None}:
                continue
            if None in shapes or len(shapes) > 1:
                line = entries[0][0]
                raise _Problem(line, f"local `{name}` is given a tuple and values of other types or shapes ("
                               + ", ".join(sorted({f'`{k}`' for _, k in entries})) + ")")
            [s] = shapes
            assert s is not None
            self.claim(name, s, entries[0][0])
        if not self.top:
            for a in self.fn.args.args:
                if annotation_shape(a.annotation) is not None:
                    raise _Problem(self.fn.lineno, f"parameter `{a.arg}` of the method `{self.q}` is a tuple; a "
                                                   "tuple parameter is split only for a module-level function in "
                                                   "this step")

    def claim(self, name: str, shape: tuple[str, ...], line: int) -> None:
        if name in self.tuples and self.tuples[name] != shape:
            raise _Problem(line, f"local `{name}` is given tuples of two shapes")
        for i in range(len(shape)):
            if self.elem(name, i) in self.names:
                raise _Problem(line, f"the element local `{self.elem(name, i)}` of the tuple `{name}` is a name of "
                                     "the function already")
        self.tuples[name] = shape

    @staticmethod
    def elem(name: str, i: int) -> str:
        return f"{name}{_SEQ}{i}"

    def elems(self, name: str) -> list[ast.expr]:
        return [_name(self.elem(name, i)) for i in range(len(self.tuples[name]))]

    def temp(self, kind: str, line: int) -> str:
        while True:
            t = f"_tpt{self.temp_n}"
            self.temp_n += 1
            if t not in self.names:
                break
        self.names.add(t)
        self.kinds.setdefault(t, []).append((line, kind))
        return t

    def kind_of(self, name: str, line: int) -> str | None:
        found = [k for ln, k in self.kinds.get(name, []) if ln == line]
        return found[0] if found else None

    # -- the rewrite

    def run(self) -> None:
        self.find()
        if not self.tuples and not any(isinstance(n, ast.Tuple) for n in ast.walk(self.fn)):
            return
        for name, shape in self.tuples.items():           # every element local typed wherever the tuple is bound
            for line, k in self.kinds.get(name, []):
                if shape_of(k) is not None:
                    for i, ek in enumerate(shape):
                        self.kinds.setdefault(self.elem(name, i), []).append((line, ek))
        if self.top:
            args: list[ast.arg] = []
            for a in self.fn.args.args:
                s = self.tuples.get(a.arg) if annotation_shape(a.annotation) is not None else None
                if s is None:
                    args.append(a)
                    continue
                args += [ast.copy_location(ast.arg(arg=self.elem(a.arg, i), annotation=_name(k)), a)
                         for i, k in enumerate(s)]
            self.fn.args.args = args
        self.fn.body = self.block(self.fn.body)

    def block(self, body: list[ast.stmt]) -> list[ast.stmt]:
        out: list[ast.stmt] = []
        for s in body:
            new = self.stmt(s)
            for n in new:
                ast.copy_location(n, s)
                ast.fix_missing_locations(n)
                self.check_left(n)
            out += new
        return out or [ast.copy_location(ast.Pass(), body[0])] if body else out

    def check_left(self, s: ast.stmt) -> None:
        """A tuple name the rewrite did not take apart: the function is not compiled (never a tuple object)."""
        for n in ast.walk(s):
            if isinstance(n, ast.Name) and n.id in self.tuples:
                raise _Problem(getattr(n, "lineno", s.lineno),
                               f"the tuple `{n.id}` is used where an AOT program has no tuple object yet (a tuple is "
                               "indexed by a constant, unpacked, compared, searched with `in`, printed, iterated, "
                               "passed to a parameter of a module function, returned, or copied to another name)")

    def stmt(self, s: ast.stmt) -> list[ast.stmt]:
        line = s.lineno
        if isinstance(s, (ast.Assign, ast.AnnAssign)):
            target = s.targets[0] if isinstance(s, ast.Assign) and len(s.targets) == 1 else (
                s.target if isinstance(s, ast.AnnAssign) else None)
            value = s.value
            if isinstance(target, ast.Name) and target.id in self.tuples and value is not None:
                return self.bind(target.id, value, line, annotated=isinstance(s, ast.AnnAssign))
            if isinstance(s, ast.Assign) and isinstance(target, (ast.Tuple, ast.List)) and value is not None \
                    and all(isinstance(e, ast.Name) for e in target.elts):
                names = [e.id for e in target.elts]                         # type: ignore[union-attr]
                if any(n in self.tuples for n in names):
                    raise _Problem(line, f"`{ast.unparse(s)}` unpacks into a tuple name (a tuple of tuples is not in "
                                         "this step)")
                if isinstance(value, ast.Name) and value.id in self.tuples:
                    shape = self.tuples[value.id]
                    if len(shape) != len(names):
                        raise _Problem(line, f"`{ast.unparse(s)}` unpacks {len(names)} names from a tuple of "
                                             f"{len(shape)}")
                    return [ast.Assign(targets=[_name(n, ast.Store())], value=e)
                            for n, e in zip(names, self.elems(value.id))]
                if isinstance(value, ast.Tuple) and not any(isinstance(e, ast.Starred) for e in value.elts):
                    if len(value.elts) != len(names):
                        raise _Problem(line, f"`{ast.unparse(s)}` unpacks {len(names)} names from {len(value.elts)} "
                                             "values")
                    vals = [self.expr(e) for e in value.elts]
                    return self.assign_all(names, vals, line)
        if isinstance(s, ast.Return) and s.value is not None:
            shape = self.returns.get(self.q)
            if shape is not None and isinstance(s.value, ast.Name) and s.value.id in self.tuples:
                if self.tuples[s.value.id] != shape:
                    raise _Problem(line, f"`return {s.value.id}` returns a `tuple[{', '.join(self.tuples[s.value.id])}]` "
                                         f"from a function declared `tuple[{', '.join(shape)}]`")
                return [ast.Return(value=ast.Tuple(elts=self.elems(s.value.id), ctx=ast.Load()))]
            if shape is not None and self.tuple_call(s.value) is not None:
                call = self.expr(s.value)
                temps = [self.temp(k, line) for k in shape]
                return [ast.Assign(targets=[ast.Tuple(elts=[_name(t, ast.Store()) for t in temps], ctx=ast.Store())],
                                   value=call),
                        ast.Return(value=ast.Tuple(elts=[_name(t) for t in temps], ctx=ast.Load()))]
        if isinstance(s, ast.For) and isinstance(s.iter, ast.Name) and s.iter.id in self.tuples:
            return self.for_tuple(s)
        if isinstance(s, ast.If):
            s.test = self.expr(s.test)
            s.body = self.block(s.body)
            s.orelse = self.block(s.orelse)
            return [s]
        if isinstance(s, ast.While):
            s.test = self.expr(s.test)
            s.body = self.block(s.body)
            s.orelse = self.block(s.orelse)
            return [s]
        if isinstance(s, ast.For):
            s.iter = self.expr(s.iter)
            s.body = self.block(s.body)
            s.orelse = self.block(s.orelse)
            return [s]
        return [self.expr_stmt(s)]

    def expr_stmt(self, s: ast.stmt) -> ast.stmt:
        return _Exprs(self).visit(s)

    def expr(self, e: ast.expr) -> ast.expr:
        return _Exprs(self).visit(e)

    def tuple_call(self, e: ast.expr) -> tuple[str, ...] | None:
        if isinstance(e, ast.Call) and isinstance(e.func, ast.Name) and e.func.id in self.returns \
                and e.func.id not in self.locals:
            return self.returns[e.func.id]
        return None

    def bind(self, name: str, value: ast.expr, line: int, annotated: bool) -> list[ast.stmt]:
        shape = self.tuples[name]
        targets = [self.elem(name, i) for i in range(len(shape))]
        if isinstance(value, ast.Name) and value.id in self.tuples:
            if self.tuples[value.id] != shape:
                raise _Problem(line, f"`{value.id}` is a tuple of another shape than `{name}`")
            if value.id == name:
                return [ast.Pass()]
            return self.assign_all(targets, self.elems(value.id), line, kinds=shape, annotated=annotated)
        if isinstance(value, ast.Tuple) and not any(isinstance(e, ast.Starred) for e in value.elts):
            if len(value.elts) != len(shape):
                raise _Problem(line, f"`{name}` is a tuple of {len(shape)}, given a display of {len(value.elts)}")
            vals = [self.expr(e) for e in value.elts]
            return self.assign_all(targets, vals, line, kinds=shape, annotated=annotated)
        called = self.tuple_call(value)
        if called is not None:
            if called != shape:
                raise _Problem(line, f"`{ast.unparse(value)}` returns a `tuple[{', '.join(called)}]`, but `{name}` is "
                                     f"a `tuple[{', '.join(shape)}]`")
            return [ast.Assign(targets=[ast.Tuple(elts=[_name(t, ast.Store()) for t in targets], ctx=ast.Store())],
                               value=self.expr(value))]
        raise _Problem(line, f"the tuple `{name}` is bound to `{ast.unparse(value)}`; a tuple value comes from a tuple "
                             "display, another tuple name or a call of a program function returning a fixed tuple")

    def assign_all(self, targets: list[str], values: list[ast.expr], line: int,
                   kinds: tuple[str, ...] | None = None, annotated: bool = False) -> list[ast.stmt]:
        """`targets = values` with CPython's order: every value, then every name (temporaries when a later value reads
        an earlier target)."""
        read = [{n.id for n in ast.walk(v) if isinstance(n, ast.Name)} for v in values]
        clash = any(targets[i] in r for i in range(len(targets)) for r in read[i + 1:])

        def put(t: str, v: ast.expr, k: str | None) -> ast.stmt:
            if annotated and k is not None:
                return ast.AnnAssign(target=_name(t, ast.Store()), annotation=_name(k), value=v, simple=1)
            return ast.Assign(targets=[_name(t, ast.Store())], value=v)

        if not clash:
            return [put(t, v, kinds[i] if kinds else None) for i, (t, v) in enumerate(zip(targets, values))]
        out: list[ast.stmt] = []
        temps = []
        for i, (t, v) in enumerate(zip(targets, values)):
            k = kinds[i] if kinds else self.kind_of(t, line)
            if k is None:
                raise _Problem(line, f"no type is known for `{t}` in this assignment")
            tmp = self.temp(k, line)
            temps.append(tmp)
            out.append(ast.Assign(targets=[_name(tmp, ast.Store())], value=v))
        out += [put(t, _name(tmp), kinds[i] if kinds else None) for i, (t, tmp) in enumerate(zip(targets, temps))]
        return out

    def for_tuple(self, s: ast.For) -> list[ast.stmt]:
        line = s.lineno
        name = s.iter.id                                                    # type: ignore[union-attr]
        shape = self.tuples[name]
        if not isinstance(s.target, ast.Name) or s.orelse:
            raise _Problem(line, f"`for {ast.unparse(s.target)} in {name}` with another target than a name, or an else")
        if len(set(shape)) != 1:
            raise _Problem(line, f"`for {s.target.id} in {name}` over a tuple of elements of different types")
        stored = {n.id for n in ast.walk(ast.Module(body=s.body, type_ignores=[]))
                  if isinstance(n, ast.Name) and isinstance(n.ctx, ast.Store)}
        if any(self.elem(name, i) in stored or name in stored for i in range(len(shape))):
            raise _Problem(line, f"`for {s.target.id} in {name}` whose body rebinds `{name}` (CPython keeps iterating "
                                 "the first tuple)")
        idx = self.temp("int", line)
        self.kinds.pop(idx, None)                       # (a range loop variable is an int by its binding)
        pick: ast.expr = _name(self.elem(name, len(shape) - 1))
        for i in range(len(shape) - 2, -1, -1):
            pick = ast.IfExp(test=ast.Compare(left=_name(idx), ops=[ast.Eq()], comparators=[ast.Constant(i)]),
                             body=_name(self.elem(name, i)), orelse=pick)
        self.kinds.setdefault(s.target.id, []).append((line, shape[0]))
        head = ast.Assign(targets=[_name(s.target.id, ast.Store())], value=pick)
        ast.copy_location(head, s)
        body = self.block(s.body)
        loop = ast.For(target=_name(idx, ast.Store()),
                       iter=ast.Call(func=_name("range"), args=[ast.Constant(len(shape))], keywords=[]),
                       body=[head, *body], orelse=[], type_comment=None)
        return [loop]


class _Exprs(ast.NodeTransformer):
    """The expression rewrites of one statement."""

    def __init__(self, f: _Function) -> None:
        self.f = f

    def tuple_elements(self, e: ast.expr) -> list[ast.expr] | None:
        """The elements of a tuple name or a tuple display (rewritten), else None."""
        if isinstance(e, ast.Name) and e.id in self.f.tuples:
            return self.f.elems(e.id)
        if isinstance(e, ast.Tuple) and isinstance(e.ctx, ast.Load) and e.elts \
                and not any(isinstance(x, ast.Starred) for x in e.elts):
            return [self.visit(x) for x in e.elts]
        return None

    def kinds(self, e: ast.expr) -> tuple[str, ...] | None:
        if isinstance(e, ast.Name) and e.id in self.f.tuples:
            return self.f.tuples[e.id]
        return None

    def visit_Subscript(self, node: ast.Subscript) -> ast.expr:
        if isinstance(node.value, ast.Name) and node.value.id in self.f.tuples:
            shape = self.f.tuples[node.value.id]
            k = node.slice
            v: object = None
            if isinstance(k, ast.Constant) and type(k.value) is int:
                v = k.value
            elif isinstance(k, ast.UnaryOp) and isinstance(k.op, ast.USub) and isinstance(k.operand, ast.Constant) \
                    and type(k.operand.value) is int:
                v = -k.operand.value
            if isinstance(v, int) and -len(shape) <= v < len(shape):
                return _name(self.f.elem(node.value.id, v % len(shape)))
            raise _Problem(node.lineno, f"`{ast.unparse(node)}`: a tuple is indexed by a constant inside its "
                                        f"{len(shape)} elements in this step (a variable index or a slice of a tuple "
                                        "is not in the AOT mode yet)")
        return self.generic_visit(node)

    def visit_Call(self, node: ast.Call) -> ast.expr:
        f = node.func
        if isinstance(f, ast.Name) and f.id not in self.f.locals and len(node.args) == 1 and not node.keywords:
            els = self.tuple_elements(node.args[0])
            if els is not None and f.id == "len" and isinstance(node.args[0], ast.Name):
                return ast.Constant(len(els))
            if els is not None and f.id in ("str", "repr"):
                return ast.JoinedStr(values=_repr_pieces(els))
        if isinstance(f, ast.Name) and f.id == "print" and f.id not in self.f.locals:
            args = []
            for a in node.args:
                els = self.tuple_elements(a)
                args.append(ast.JoinedStr(values=_repr_pieces(els)) if els is not None else self.visit(a))
            node.args = args
            node.keywords = [self.visit(k) for k in node.keywords]
            return node
        if isinstance(f, ast.Name) and f.id in self.f.params and f.id not in self.f.locals \
                and any(s is not None for s in self.f.params[f.id]) and len(node.args) == len(self.f.params[f.id]):
            args = []
            for a, s in zip(node.args, self.f.params[f.id]):
                if s is None:
                    args.append(self.visit(a))
                    continue
                els = self.tuple_elements(a)
                given = self.kinds(a) or (tuple("?" for _ in els) if els is not None else None)
                if els is None or len(els) != len(s) or (self.kinds(a) is not None and given != s):
                    raise _Problem(node.lineno, f"`{ast.unparse(a)}` is passed to a `tuple[{', '.join(s)}]` parameter "
                                                f"of `{f.id}`; an AOT program passes a tuple name or a tuple display of "
                                                "that shape")
                args += els
            node.args = args
            return node
        return self.generic_visit(node)

    def visit_JoinedStr(self, node: ast.JoinedStr) -> ast.expr:
        values: list[ast.expr] = []
        for v in node.values:
            if isinstance(v, ast.FormattedValue) and v.format_spec is None and v.conversion in (-1, ord("s"), ord("r")):
                els = self.tuple_elements(v.value)
                if els is not None:
                    values += _repr_pieces(els)
                    continue
            values.append(self.visit(v))
        node.values = values
        return node

    def visit_Compare(self, node: ast.Compare) -> ast.expr:
        if len(node.ops) != 1:
            return self.generic_visit(node)
        op, left, right = node.ops[0], node.left, node.comparators[0]
        line = node.lineno
        if isinstance(op, (ast.In, ast.NotIn)):
            els = self.tuple_elements(right)
            if els is None:
                return self.generic_visit(node)
            kinds = self.kinds(right)
            x = self.visit(left)
            if not _pure(x):
                raise _Problem(line, f"`{ast.unparse(node)}`: an AOT program searches a tuple for a name or a constant "
                                     "(bind the value to a name first)")
            if (kinds is not None and "float" in kinds) or (isinstance(x, ast.Constant) and isinstance(x.value, float)) \
                    or (isinstance(x, ast.Name) and any(k == "float" for _, k in self.f.kinds.get(x.id, []))):
                raise _Problem(line, f"`{ast.unparse(node)}`: searching a tuple with a float depends on object identity "
                                     "for NaN in CPython (`x in t` is `y is x or y == x` for each item), which an AOT "
                                     "program does not have (aot/identity)")
            tests: list[ast.expr] = [ast.Compare(left=copy.deepcopy(x), ops=[ast.Eq()], comparators=[e]) for e in els]
            found: ast.expr = tests[0] if len(tests) == 1 else ast.BoolOp(op=ast.Or(), values=tests)
            return ast.UnaryOp(op=ast.Not(), operand=found) if isinstance(op, ast.NotIn) else found
        a, b = self.tuple_elements(left), self.tuple_elements(right)
        if a is None or b is None:
            if a is not None or b is not None:
                raise _Problem(line, f"`{ast.unparse(node)}` compares a tuple with something that is not a tuple")
            return self.generic_visit(node)
        if not all(_pure(e) for e in a + b):
            raise _Problem(line, f"`{ast.unparse(node)}`: an AOT program compares tuple names or displays of names and "
                                 "constants (bind the values to names first)")
        ka, kb = self.kinds(left), self.kinds(right)
        floats = any(k == "float" for k in (ka or ()) + (kb or ())) or any(
            isinstance(e, ast.Constant) and isinstance(e.value, float) for e in a + b) or any(
            isinstance(e, ast.Name) and any(k == "float" for _, k in self.f.kinds.get(e.id, [])) for e in a + b)
        if floats:
            raise _Problem(line, f"`{ast.unparse(node)}`: comparing tuples with a float element tests each pair by "
                                 "identity first in CPython (a NaN equals itself there), which an AOT program does not "
                                 "have (aot/identity)")
        # (an item equals itself here: no float item, and an int, bool or str equals itself; such a pair decides
        # nothing and is left out, which also keeps a C compiler from seeing `x != x`)
        differ = [i for i in range(min(len(a), len(b))) if not _same(a[i], b[i])]
        if isinstance(op, (ast.Eq, ast.NotEq)):
            if len(a) != len(b) or not differ:
                equal = len(a) == len(b)            # (different lengths: never equal; else every pair is one value)
                return ast.Constant(equal if isinstance(op, ast.Eq) else not equal)
            cmp = ast.Eq() if isinstance(op, ast.Eq) else ast.NotEq()
            parts: list[ast.expr] = [ast.Compare(left=a[i], ops=[cmp], comparators=[b[i]]) for i in differ]
            if len(parts) == 1:
                return parts[0]
            return ast.BoolOp(op=ast.And() if isinstance(op, ast.Eq) else ast.Or(), values=parts)
        if not isinstance(op, (ast.Lt, ast.LtE, ast.Gt, ast.GtE)):
            raise _Problem(line, f"`{ast.unparse(node)}`: `is` of tuples asks for object identity, and an AOT program "
                                 "has no tuple object yet")
        lengths = {ast.Lt: len(a) < len(b), ast.LtE: len(a) <= len(b), ast.Gt: len(a) > len(b),
                   ast.GtE: len(a) >= len(b)}[type(op)]
        result: ast.expr = ast.Constant(lengths)
        for i in reversed(differ):
            result = ast.IfExp(test=ast.Compare(left=a[i], ops=[ast.NotEq()], comparators=[b[i]]),
                               body=ast.Compare(left=copy.deepcopy(a[i]), ops=[type(op)()],
                                                comparators=[copy.deepcopy(b[i])]),
                               orelse=result)
        return result
