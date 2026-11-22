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
* `t[i]` for any other int index, of a tuple whose elements share one kind: a call of the marker `TUPLE_INDEX`
  with the index first and the element locals after it, which the lowering makes an `ir.TupleIndex` (CPython's
  index rules: negative counted from the end, `IndexError: tuple index out of range`). A tuple whose element kinds
  differ gives a union for a variable index, which the AOT mode does not type: refused.
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

A `list[tuple[int, str]]` (elements int, float or str; there is no list of bool yet) is split the same way, into one
list per element position, `L__0: list[int]` and `L__1: list[str]`: a tuple read out of a list is an immutable value
with no identity in this subset, so the parallel lists hold exactly what CPython's list holds, and every alias of the
list (a name, a parameter) holds the same parallel list objects, so a change through one is seen through all. Each list
operation is applied to every element list in turn, the index and the values evaluated once, first, in CPython's order;
an operation that fails (IndexError, ValueError) fails on the first element list, before any of them changed. Covered:
a display of tuple names or displays, `[]`, another such list, `+`, `*`, a slice, `list(M)`; `append`, `insert`,
`extend`, `pop` (as a statement or bound to a tuple or unpacked), `clear`, `reverse`, `L[i] = t`, `del L[i]`, `del
L[a:b]`, `L += M`; `len(L)`, `L[i]` bound, unpacked, indexed by a constant (`L[i][0]` is `L__0[i]`), printed or compared
when `i` is a name or a constant; `for t in L` / `for a, b in L` (CPython's list iterator: the length is read at each
step); `print(L)` / `str` / `repr` / f-strings (the repr is built in a loop before the statement, so the statement may
call nothing else); passing to a `list[tuple[...]]` parameter of a module function.

Anything else that reads or binds a tuple name leaves the function uncompiled with a finding naming it (`problems`),
never a guess. Methods keep their parameters (a tuple parameter of a method is not split in this step)."""

from __future__ import annotations

import ast
import copy
from dataclasses import dataclass, field

ELEMENT_KINDS = ("int", "float", "bool", "str")
LIST_ELEMENT_KINDS = ("int", "float", "str")    # (a list of tuples: one list per element; no list of bool yet)
TUPLE_INDEX = "__tp_tuple_index"   # `t[i]`: TUPLE_INDEX(i, t__0, t__1, ...), lowered to ir.TupleIndex
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


def list_shape_of(kind: str | None) -> tuple[str, ...] | None:
    """`list[tuple[int, str]]` -> ("int", "str"); None for anything else."""
    if not kind or not kind.startswith("list[") or not kind.endswith("]"):
        return None
    return shape_of(kind[5:-1])


def annotation_list_shape(ann: ast.expr | None) -> tuple[str, ...] | None:
    return list_shape_of(ast.unparse(ann)) if ann is not None else None


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
    lparams = {q: [annotation_list_shape(a.annotation) for a in fn.args.args] for q, fn, top in functions if top}
    returns = {q: annotation_shape(fn.returns) for q, fn, _ in functions}
    for q, fn, top in functions:
        if not top and not any(annotation_shape(a.annotation) or annotation_list_shape(a.annotation)
                               for a in fn.args.args) and not _mentions_tuples(fn, out.kinds.get(q, {})):
            continue
        work = _Function(q, fn, top, out.kinds.setdefault(q, {}), params, returns, lparams)
        original = copy.deepcopy(fn)
        try:
            work.run()
        except _Problem as p:
            # (the function is left as it was; the lowering is told not to compile it)
            fn.args, fn.body = original.args, original.body
            out.problems[q] = f"line {p.line}: {p.message}"
    return out


def _mentions_tuples(fn: ast.FunctionDef, fkinds: dict[str, list[tuple[int, str]]]) -> bool:
    if any(shape_of(k) or list_shape_of(k) for v in fkinds.values() for _, k in v):
        return True
    return any(isinstance(n, ast.AnnAssign) and (annotation_shape(n.annotation) or annotation_list_shape(n.annotation))
               for n in ast.walk(fn)) \
        or any(isinstance(n, ast.Tuple) and isinstance(n.ctx, ast.Load) for n in ast.walk(fn))


def _name(id_: str, ctx: ast.expr_context | None = None) -> ast.Name:
    return ast.Name(id=id_, ctx=ctx or ast.Load())


def _pure(e: ast.expr) -> bool:
    """Evaluating `e` again is unobservable (a name or a constant)."""
    return isinstance(e, (ast.Name, ast.Constant)) or (
        isinstance(e, ast.UnaryOp) and isinstance(e.op, ast.USub) and isinstance(e.operand, ast.Constant))


def _item(e: ast.expr) -> bool:
    """`L__k[i]` with a name or constant index: an element of an item of a list of tuples. Reading it again reads the
    same value (the comparison calls nothing), and every element list has one length, so the first read raises any
    IndexError."""
    return isinstance(e, ast.Subscript) and isinstance(e.value, ast.Name) and not isinstance(e.slice, ast.Slice) \
        and _pure(e.slice)


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
                 params: dict[str, list[tuple[str, ...] | None]], returns: dict[str, tuple[str, ...] | None],
                 lparams: dict[str, list[tuple[str, ...] | None]] | None = None) -> None:
        self.q, self.fn, self.top, self.kinds = qname, fn, top, kinds
        self.params, self.returns, self.lparams = params, returns, lparams or {}
        self.names = {n.id for n in ast.walk(fn) if isinstance(n, ast.Name)} | {a.arg for a in fn.args.args}
        # (the names the function binds: a builtin or a program function of that name is shadowed)
        self.locals = {n.id for n in ast.walk(fn) if isinstance(n, ast.Name) and not isinstance(n.ctx, ast.Load)} \
            | {a.arg for a in fn.args.args}
        self.tuples: dict[str, tuple[str, ...]] = {}
        self.lists: dict[str, tuple[str, ...]] = {}         # list[tuple[...]] names -> the element shape
        self.pre: list[ast.stmt] = []                       # statements the current statement needs first
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
        if self.top:
            for a in self.fn.args.args:
                s = annotation_list_shape(a.annotation)
                if s is not None:
                    self.claim_list(a.arg, s, a.lineno)
        for n in ast.walk(self.fn):
            if isinstance(n, ast.AnnAssign) and isinstance(n.target, ast.Name):
                s = annotation_list_shape(n.annotation)
                if s is not None:
                    self.claim_list(n.target.id, s, n.lineno)
        for name, entries in list(self.kinds.items()):
            lshapes = {list_shape_of(k) for _, k in entries}
            if lshapes == {None}:
                continue
            if None in lshapes or len(lshapes) > 1:
                raise _Problem(entries[0][0], f"local `{name}` is given a list of tuples and values of other types or "
                                              "shapes (" + ", ".join(sorted({f'`{k}`' for _, k in entries})) + ")")
            [s] = lshapes
            assert s is not None
            self.claim_list(name, s, entries[0][0])
        for name, entries in self.kinds.items():
            if name in self.lists:
                continue
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
                if annotation_shape(a.annotation) is not None or annotation_list_shape(a.annotation) is not None:
                    raise _Problem(self.fn.lineno, f"parameter `{a.arg}` of the method `{self.q}` is a tuple or a "
                                                   "list of tuples; such a parameter is split only for a "
                                                   "module-level function in this step")

    def claim(self, name: str, shape: tuple[str, ...], line: int) -> None:
        if name in self.tuples and self.tuples[name] != shape:
            raise _Problem(line, f"local `{name}` is given tuples of two shapes")
        for i in range(len(shape)):
            if self.elem(name, i) in self.names:
                raise _Problem(line, f"the element local `{self.elem(name, i)}` of the tuple `{name}` is a name of "
                                     "the function already")
        self.tuples[name] = shape

    def claim_list(self, name: str, shape: tuple[str, ...], line: int) -> None:
        if name in self.lists and self.lists[name] != shape:
            raise _Problem(line, f"local `{name}` is given lists of tuples of two shapes")
        if any(k not in LIST_ELEMENT_KINDS for k in shape):
            raise _Problem(line, f"`{name}` is a `list[tuple[{', '.join(shape)}]]`; a list of tuples holds int, float "
                                 "and str elements in this step (there is no list of bool yet)")
        for i in range(len(shape)):
            if self.elem(name, i) in self.names:
                raise _Problem(line, f"the element list `{self.elem(name, i)}` of `{name}` is a name of the function "
                                     "already")
        self.lists[name] = shape

    def comps(self, name: str) -> list[ast.expr]:
        return [_name(self.elem(name, i)) for i in range(len(self.lists[name]))]

    def index_temp(self, e: ast.expr, line: int, out: list[ast.stmt]) -> ast.expr:
        """`e` evaluated once, now: a constant as it is, anything else through an int temporary."""
        e = self.expr(e)
        if isinstance(e, ast.Constant) or (isinstance(e, ast.UnaryOp) and isinstance(e.operand, ast.Constant)):
            return e
        t = self.temp("int", line)
        out.append(ast.Assign(targets=[_name(t, ast.Store())], value=e))
        return _name(t)

    def values_once(self, e: ast.expr, shape: tuple[str, ...], line: int, out: list[ast.stmt],
                    what: str) -> list[ast.expr]:
        """The elements of the tuple value `e` (a tuple name, a display, a call of a program function returning one,
        an element of a list of tuples), each evaluated once, now, in order; pure ones are returned as they are."""
        called = self.tuple_call(e)
        if called is not None:
            if called != shape:
                raise _Problem(line, f"`{ast.unparse(e)}` returns a `tuple[{', '.join(called)}]` for {what} of "
                                     f"`tuple[{', '.join(shape)}]`")
            temps = [self.temp(k, line) for k in shape]
            out.append(ast.Assign(targets=[ast.Tuple(elts=[_name(t, ast.Store()) for t in temps], ctx=ast.Store())],
                                  value=self.expr(e)))
            return [_name(t) for t in temps]
        if isinstance(e, ast.Subscript) and isinstance(e.value, ast.Name) and e.value.id in self.lists \
                and not isinstance(e.slice, ast.Slice):
            if self.lists[e.value.id] != shape:
                raise _Problem(line, f"`{ast.unparse(e)}` is not a `tuple[{', '.join(shape)}]` ({what})")
            k = self.index_temp(e.slice, line, out)
            return [ast.Subscript(value=c, slice=copy.deepcopy(k), ctx=ast.Load()) for c in self.comps(e.value.id)]
        els = _Exprs(self).tuple_elements(e)
        if els is None or len(els) != len(shape) or (isinstance(e, ast.Name) and self.tuples[e.id] != shape):
            raise _Problem(line, f"`{ast.unparse(e)}` is {what}, which takes a tuple name, a tuple display or a "
                                 f"tuple-returning call of the shape `tuple[{', '.join(shape)}]`")
        if all(_pure(x) for x in els):
            return els
        got = []
        for x, k in zip(els, shape):
            if _pure(x):
                got.append(x)
                continue
            t = self.temp(k, line)
            out.append(ast.Assign(targets=[_name(t, ast.Store())], value=x))
            got.append(_name(t))
        return got

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
        if not self.tuples and not self.lists and not any(isinstance(n, ast.Tuple) for n in ast.walk(self.fn)):
            return
        for name, shape in self.lists.items():            # every element list typed wherever the list is bound
            for line, k in self.kinds.get(name, []):
                if list_shape_of(k) is not None:
                    for i, ek in enumerate(shape):
                        self.kinds.setdefault(self.elem(name, i), []).append((line, f"list[{ek}]"))
        for name, shape in self.tuples.items():           # every element local typed wherever the tuple is bound
            for line, k in self.kinds.get(name, []):
                if shape_of(k) is not None:
                    for i, ek in enumerate(shape):
                        self.kinds.setdefault(self.elem(name, i), []).append((line, ek))
        if self.top:
            args: list[ast.arg] = []
            for a in self.fn.args.args:
                s = self.tuples.get(a.arg) if annotation_shape(a.annotation) is not None else None
                ls = self.lists.get(a.arg) if annotation_list_shape(a.annotation) is not None else None
                if ls is not None:
                    args += [ast.copy_location(ast.arg(arg=self.elem(a.arg, i), annotation=ast.Subscript(
                        value=_name("list"), slice=_name(k), ctx=ast.Load())), a) for i, k in enumerate(ls)]
                    continue
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
            saved, self.pre = self.pre, []
            calls = [n for n in ast.walk(s) if isinstance(n, ast.Call) and not (
                isinstance(n.func, ast.Name) and n.func.id in ("print", "str", "repr", "len"))]
            new = self.stmt(s)
            if self.pre:
                if calls:
                    raise _Problem(s.lineno, f"`{ast.unparse(calls[0])}` is called in a statement that prints a list "
                                             "of tuples; its repr is built before the statement, so bind the call's "
                                             "value to a name first")
                new = self.pre + new
            self.pre = saved
            for n in new:
                ast.copy_location(n, s)
                ast.fix_missing_locations(n)
                self.check_left(n)
            out += new
        return out or [ast.copy_location(ast.Pass(), body[0])] if body else out

    def check_left(self, s: ast.stmt) -> None:
        """A tuple name the rewrite did not take apart: the function is not compiled (never a tuple object)."""
        for n in ast.walk(s):
            if isinstance(n, ast.Name) and n.id in self.lists:
                raise _Problem(getattr(n, "lineno", s.lineno),
                               f"the list of tuples `{n.id}` is used where this step has no form for it (a list of "
                               "tuples is built, appended to, inserted into, extended, popped, cleared, reversed, "
                               "indexed, stored into, deleted from, iterated, printed, measured with len, or passed to "
                               "a module function; `in`, `index`, `count`, `remove`, `==`, sorting and returning one "
                               "come with tuple objects)")
            if isinstance(n, ast.Name) and n.id in self.tuples:
                raise _Problem(getattr(n, "lineno", s.lineno),
                               f"the tuple `{n.id}` is used where an AOT program has no tuple object yet (a tuple is "
                               "indexed, unpacked, compared, searched with `in`, printed, iterated, "
                               "passed to a parameter of a module function, returned, or copied to another name)")

    def stmt(self, s: ast.stmt) -> list[ast.stmt]:
        line = s.lineno
        if self.lists:
            done = self.list_stmt(s)
            if done is not None:
                return done
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
                got = self.list_read(value, None, line)
                if got is not None:
                    out, vals = got
                    if len(vals) != len(names):
                        raise _Problem(line, f"`{ast.unparse(s)}` unpacks {len(names)} names from a tuple of "
                                             f"{len(vals)}")
                    return out + self.assign_all(names, vals, line)
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
        got = self.list_read(value, shape, line)
        if got is not None:
            out, vals = got
            return out + self.assign_all(targets, vals, line, kinds=shape, annotated=annotated)
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

    # -- lists of tuples

    def list_read(self, value: ast.expr, shape: tuple[str, ...] | None,
                  line: int) -> tuple[list[ast.stmt], list[ast.expr]] | None:
        """`L[i]` or `L.pop()` / `L.pop(i)` of a list of tuples bound to a tuple or unpacked: the statements that
        evaluate the index once, and one value per element list (None when `value` is neither)."""
        name, op, arg = None, None, None
        if isinstance(value, ast.Subscript) and isinstance(value.value, ast.Name) and value.value.id in self.lists \
                and not isinstance(value.slice, ast.Slice):
            name, op, arg = value.value.id, "index", value.slice
        elif isinstance(value, ast.Call) and isinstance(value.func, ast.Attribute) and value.func.attr == "pop" \
                and isinstance(value.func.value, ast.Name) and value.func.value.id in self.lists \
                and not value.keywords and len(value.args) <= 1:
            name, op, arg = value.func.value.id, "pop", (value.args[0] if value.args else None)
        if name is None:
            return None
        if shape is not None and self.lists[name] != shape:
            raise _Problem(line, f"`{ast.unparse(value)}` is a `tuple[{', '.join(self.lists[name])}]`, bound to a "
                                 f"`tuple[{', '.join(shape)}]`")
        out: list[ast.stmt] = []
        k = self.index_temp(arg, line, out) if arg is not None else None
        vals: list[ast.expr] = []
        for c in self.comps(name):
            if op == "index":
                vals.append(ast.Subscript(value=c, slice=copy.deepcopy(k), ctx=ast.Load()))
            else:
                vals.append(ast.Call(func=ast.Attribute(value=c, attr="pop", ctx=ast.Load()),
                                     args=[copy.deepcopy(k)] if k is not None else [], keywords=[]))
        if op == "pop" and len(vals) > 1:
            # (every pop is a value of its own: bind them in order, each element list loses its item together)
            temps = [self.temp(kd, line) for kd in self.lists[name]]
            out += [ast.Assign(targets=[_name(t, ast.Store())], value=v) for t, v in zip(temps, vals)]
            vals = [_name(t) for t in temps]
        return out, vals

    def list_value(self, name: str, value: ast.expr, line: int) -> list[ast.stmt]:
        """`L = value` for a list of tuples `L`: one assignment per element list."""
        shape = self.lists[name]
        targets = [self.elem(name, i) for i in range(len(shape))]
        out: list[ast.stmt] = []

        def other(e: ast.expr) -> str | None:
            if isinstance(e, ast.Name) and e.id in self.lists:
                if self.lists[e.id] != shape:
                    raise _Problem(line, f"`{e.id}` is a list of tuples of another shape than `{name}`")
                return e.id
            return None

        cols: list[ast.expr]
        if isinstance(value, ast.List):
            rows = [self.values_once(e, shape, line, out, f"an item of the list `{name}`") for e in value.elts]
            cols = [ast.List(elts=[r[i] for r in rows], ctx=ast.Load()) for i in range(len(shape))]
            if not rows:
                return [ast.AnnAssign(target=_name(t, ast.Store()), annotation=ast.Subscript(
                    value=_name("list"), slice=_name(k), ctx=ast.Load()), value=c, simple=1)
                        for t, k, c in zip(targets, shape, cols)]
        elif other(value) is not None:
            cols = self.comps(value.id)                                         # type: ignore[union-attr]
        elif isinstance(value, ast.BinOp) and isinstance(value.op, ast.Add) \
                and all(isinstance(x, ast.List) or other(x) is not None for x in (value.left, value.right)) \
                and not all(isinstance(x, ast.List) for x in (value.left, value.right)):
            sides = [self.columns(x, shape, line, out, name) for x in (value.left, value.right)]
            cols = [ast.BinOp(left=a, op=ast.Add(), right=b) for a, b in zip(*sides)]
        elif isinstance(value, ast.BinOp) and isinstance(value.op, ast.Mult) and (
                other(value.left) is not None or other(value.right) is not None):
            lst, n = (value.left, value.right) if other(value.left) is not None else (value.right, value.left)
            k = self.index_temp(n, line, out)
            cols = [ast.BinOp(left=c, op=ast.Mult(), right=copy.deepcopy(k)) for c in self.comps(lst.id)]  # type: ignore[union-attr]
        elif isinstance(value, ast.Subscript) and isinstance(value.slice, ast.Slice) and other(value.value) is not None:
            sl = value.slice
            parts = [self.index_temp(p, line, out) if p is not None else None for p in (sl.lower, sl.upper, sl.step)]
            cols = [ast.Subscript(value=c, slice=ast.Slice(*[copy.deepcopy(p) for p in parts]), ctx=ast.Load())
                    for c in self.comps(value.value.id)]                        # type: ignore[union-attr]
        elif isinstance(value, ast.Call) and isinstance(value.func, ast.Name) and value.func.id == "list" \
                and "list" not in self.locals and len(value.args) == 1 and not value.keywords \
                and other(value.args[0]) is not None:
            cols = [ast.Call(func=_name("list"), args=[c], keywords=[]) for c in self.comps(value.args[0].id)]  # type: ignore[union-attr]
        else:
            raise _Problem(line, f"the list of tuples `{name}` is bound to `{ast.unparse(value)}`; it comes from a list "
                                 "display of tuples, another list of tuples, `+`, `*`, a slice or `list(...)` in this "
                                 "step")
        return out + [ast.Assign(targets=[_name(t, ast.Store())], value=c) for t, c in zip(targets, cols)]

    def columns(self, e: ast.expr, shape: tuple[str, ...], line: int, out: list[ast.stmt],
                name: str) -> list[ast.expr]:
        """One operand of `+` / `+=` on a list of tuples: a list of tuples name (its element lists) or a list display of
        tuples (one list per element position bound to a temporary, the items evaluated once, now)."""
        if isinstance(e, ast.Name) and e.id in self.lists:
            if self.lists[e.id] != shape:
                raise _Problem(line, f"`{e.id}` is a list of tuples of another shape than `{name}`")
            return self.comps(e.id)
        assert isinstance(e, ast.List)
        rows = [self.values_once(x, shape, line, out, f"an item of a list joined to `{name}`") for x in e.elts]
        if not rows:
            raise _Problem(line, f"an empty display joined to the list of tuples `{name}` has no element type here")
        temps = [self.temp(f"list[{k}]", line) for k in shape]     # (`+` and `+=` take list names)
        out += [ast.Assign(targets=[_name(t, ast.Store())], value=ast.List(elts=[r[i] for r in rows], ctx=ast.Load()))
                for i, t in enumerate(temps)]
        return [_name(t) for t in temps]

    def list_stmt(self, s: ast.stmt) -> list[ast.stmt] | None:
        """A statement on a list of tuples, or None when `s` is not one."""
        line = s.lineno
        out: list[ast.stmt] = []
        if isinstance(s, (ast.Assign, ast.AnnAssign)):
            target = s.targets[0] if isinstance(s, ast.Assign) and len(s.targets) == 1 else (
                s.target if isinstance(s, ast.AnnAssign) else None)
            if isinstance(target, ast.Name) and target.id in self.lists:
                if s.value is None:
                    return [ast.Pass()]
                return self.list_value(target.id, s.value, line)
            if isinstance(target, ast.Subscript) and isinstance(target.value, ast.Name) \
                    and target.value.id in self.lists and s.value is not None:
                name = target.value.id
                if isinstance(target.slice, ast.Slice):
                    raise _Problem(line, f"`{ast.unparse(s)}`: slice assignment to a list of tuples is not in this step")
                vals = self.values_once(s.value, self.lists[name], line, out, f"stored into `{name}`")
                k = self.index_temp(target.slice, line, out)
                return out + [ast.Assign(targets=[ast.Subscript(value=c, slice=copy.deepcopy(k), ctx=ast.Store())],
                                         value=v) for c, v in zip(self.comps(name), vals)]
            return None
        if isinstance(s, ast.AugAssign) and isinstance(s.target, ast.Name) and s.target.id in self.lists:
            name = s.target.id
            if not (isinstance(s.op, ast.Add) and (isinstance(s.value, ast.List) or (
                    isinstance(s.value, ast.Name) and s.value.id in self.lists))):
                raise _Problem(line, f"`{ast.unparse(s)}`: a list of tuples is extended in place by another list of "
                                     "tuples of its shape or a display of tuples (`+=`)")
            cols = self.columns(s.value, self.lists[name], line, out, name)
            return out + [ast.AugAssign(target=_name(a.id, ast.Store()), op=ast.Add(), value=b)  # type: ignore[attr-defined]
                          for a, b in zip(self.comps(name), cols)]
        if isinstance(s, ast.Delete) and len(s.targets) == 1 and isinstance(s.targets[0], ast.Subscript) \
                and isinstance(s.targets[0].value, ast.Name) and s.targets[0].value.id in self.lists:
            t = s.targets[0]
            name = t.value.id                                                   # type: ignore[attr-defined]
            if isinstance(t.slice, ast.Slice):
                parts = [self.index_temp(p, line, out) if p is not None else None
                         for p in (t.slice.lower, t.slice.upper, t.slice.step)]
                key = lambda: ast.Slice(*[copy.deepcopy(p) for p in parts])      # noqa: E731
            else:
                k = self.index_temp(t.slice, line, out)
                key = lambda: copy.deepcopy(k)                                   # noqa: E731
            return out + [ast.Delete(targets=[ast.Subscript(value=c, slice=key(), ctx=ast.Del())])
                          for c in self.comps(name)]
        if isinstance(s, ast.Expr) and isinstance(s.value, ast.Call) and isinstance(s.value.func, ast.Attribute) \
                and isinstance(s.value.func.value, ast.Name) and s.value.func.value.id in self.lists:
            call = s.value
            name, m = call.func.value.id, call.func.attr                        # type: ignore[attr-defined]
            shape = self.lists[name]
            if call.keywords:
                return None

            def each(method: str, per: list[list[ast.expr]]) -> list[ast.stmt]:
                return [ast.Expr(value=ast.Call(func=ast.Attribute(value=c, attr=method, ctx=ast.Load()), args=a,
                                                keywords=[])) for c, a in zip(self.comps(name), per)]

            if m == "append" and len(call.args) == 1:
                vals = self.values_once(call.args[0], shape, line, out, f"appended to `{name}`")
                return out + each("append", [[v] for v in vals])
            if m == "insert" and len(call.args) == 2:
                k = self.index_temp(call.args[0], line, out)
                vals = self.values_once(call.args[1], shape, line, out, f"inserted into `{name}`")
                return out + each("insert", [[copy.deepcopy(k), v] for v in vals])
            if m == "extend" and len(call.args) == 1 and isinstance(call.args[0], ast.Name) \
                    and call.args[0].id in self.lists and self.lists[call.args[0].id] == shape:
                return each("extend", [[c] for c in self.comps(call.args[0].id)])
            if m in ("clear", "reverse") and not call.args:
                return each(m, [[] for _ in shape])
            if m == "pop" and len(call.args) <= 1:
                k = self.index_temp(call.args[0], line, out) if call.args else None
                return out + each("pop", [[copy.deepcopy(k)] if k is not None else [] for _ in shape])
            return None
        if isinstance(s, ast.For) and isinstance(s.iter, ast.Name) and s.iter.id in self.lists:
            return self.for_list(s)
        return None

    def for_list(self, s: ast.For) -> list[ast.stmt]:
        """`for t in L` / `for a, b in L`: CPython's list iterator (the length read at each step, so items appended in
        the body are visited): an index, the item's elements read out of the element lists, then the body."""
        line = s.lineno
        name = s.iter.id                                                    # type: ignore[union-attr]
        shape = self.lists[name]
        stored = {n.id for n in ast.walk(ast.Module(body=s.body, type_ignores=[]))
                  if isinstance(n, ast.Name) and isinstance(n.ctx, ast.Store)}
        if name in stored:
            raise _Problem(line, f"`for ... in {name}` whose body rebinds `{name}` (CPython keeps iterating the first "
                                 "list)")
        idx = self.temp("int", line)
        cell = [ast.Subscript(value=c, slice=_name(idx), ctx=ast.Load()) for c in self.comps(name)]
        t = s.target
        # (a loop target is not one of the probed assignments: its element kinds are written here, at the loop's line)
        if isinstance(t, ast.Name) and t.id not in self.lists:
            if t.id not in self.tuples:
                self.claim(t.id, shape, line)
            elif self.tuples[t.id] != shape:
                raise _Problem(line, f"`for {t.id} in {name}`: `{t.id}` is a tuple of another shape")
            names = [self.elem(t.id, i) for i in range(len(shape))]
        elif isinstance(t, (ast.Tuple, ast.List)) and all(isinstance(e, ast.Name) for e in t.elts) \
                and len(t.elts) == len(shape) \
                and not any(e.id in self.tuples or e.id in self.lists for e in t.elts):  # type: ignore[attr-defined]
            names = [e.id for e in t.elts]                                  # type: ignore[attr-defined]
        else:
            raise _Problem(line, f"`for {ast.unparse(t)} in {name}`: the target is a tuple name or names for each "
                                 f"of the {len(shape)} elements")
        for n, k in zip(names, shape):
            self.kinds.setdefault(n, []).append((line, k))
        heads = self.assign_all(names, cell, line, kinds=shape)
        step = ast.AugAssign(target=_name(idx, ast.Store()), op=ast.Add(), value=ast.Constant(1))
        for n in [*heads, step]:
            ast.copy_location(n, s)
            ast.fix_missing_locations(n)
        body = self.block(s.body)
        orelse = self.block(s.orelse)
        test = ast.Compare(left=_name(idx), ops=[ast.Lt()], comparators=[
            ast.Call(func=_name("len"), args=[self.comps(name)[0]], keywords=[])])
        start = ast.Assign(targets=[_name(idx, ast.Store())], value=ast.Constant(0))
        return [start, ast.While(test=test, body=[*heads, step, *body], orelse=orelse)]

    def list_operand(self, e: ast.expr) -> str | None:
        """The list of tuples name a list expression (`+`, `*`, a slice, `list(...)`) is made from, else None."""
        if isinstance(e, ast.Name):
            return e.id if e.id in self.lists else None
        if isinstance(e, ast.BinOp) and isinstance(e.op, (ast.Add, ast.Mult)):
            return self.list_operand(e.left) or self.list_operand(e.right)
        if isinstance(e, ast.Subscript) and isinstance(e.slice, ast.Slice):
            return self.list_operand(e.value)
        if isinstance(e, ast.Call) and isinstance(e.func, ast.Name) and e.func.id == "list" and len(e.args) == 1:
            return self.list_operand(e.args[0])
        return None

    def as_list(self, e: ast.expr, line: int) -> str | None:
        """A list of tuples name for `e`: the name itself, or a list expression bound to a new list of tuples before
        the statement (`pre`); None when `e` is not a list of tuples."""
        src = self.list_operand(e)
        if src is None:
            return None
        if isinstance(e, ast.Name):
            return src
        shape = self.lists[src]
        while True:
            name = f"_tpt{self.temp_n}"
            self.temp_n += 1
            if name not in self.names and all(self.elem(name, i) not in self.names for i in range(len(shape))):
                break
        self.names.add(name)
        self.claim_list(name, shape, line)
        for i, k in enumerate(shape):
            self.names.add(self.elem(name, i))
            self.kinds.setdefault(self.elem(name, i), []).append((line, f"list[{k}]"))
        self.pre += self.list_value(name, e, line)
        return name

    def list_repr(self, name: str, line: int) -> ast.expr:
        """The repr of the list of tuples `name`, built by statements run before the current statement (`pre`)."""
        shape = self.lists[name]
        acc, idx = self.temp("str", line), self.temp("int", line)
        cell = [ast.Subscript(value=c, slice=_name(idx), ctx=ast.Load()) for c in self.comps(name)]

        def add(e: ast.expr) -> ast.stmt:
            return ast.Assign(targets=[_name(acc, ast.Store())], value=ast.BinOp(left=_name(acc), op=ast.Add(), right=e))

        sep = ast.If(test=ast.Compare(left=_name(idx), ops=[ast.Gt()], comparators=[ast.Constant(0)]),
                     body=[add(ast.Constant(", "))], orelse=[])
        loop = ast.While(
            test=ast.Compare(left=_name(idx), ops=[ast.Lt()], comparators=[
                ast.Call(func=_name("len"), args=[self.comps(name)[0]], keywords=[])]),
            body=[sep, add(ast.JoinedStr(values=_repr_pieces(cell))),
                  ast.AugAssign(target=_name(idx, ast.Store()), op=ast.Add(), value=ast.Constant(1))],
            orelse=[])
        self.pre += [ast.Assign(targets=[_name(acc, ast.Store())], value=ast.Constant("[")),
                     ast.Assign(targets=[_name(idx, ast.Store())], value=ast.Constant(0)),
                     loop, add(ast.Constant("]"))]
        return _name(acc)

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
        if isinstance(e, ast.Subscript) and isinstance(e.value, ast.Name) and e.value.id in self.f.lists \
                and not isinstance(e.slice, ast.Slice):
            k = self.visit(e.slice)
            if not _pure(k):
                raise _Problem(e.lineno, f"`{ast.unparse(e)}`: an item of a list of tuples is read here by a name or a "
                                         "constant index (bind the index to a name first)")
            return [ast.Subscript(value=c, slice=copy.deepcopy(k), ctx=ast.Load()) for c in self.f.comps(e.value.id)]
        return None

    def list_name(self, e: ast.expr) -> str | None:
        return e.id if isinstance(e, ast.Name) and e.id in self.f.lists else None

    def kinds(self, e: ast.expr) -> tuple[str, ...] | None:
        if isinstance(e, ast.Name) and e.id in self.f.tuples:
            return self.f.tuples[e.id]
        if isinstance(e, ast.Subscript) and isinstance(e.value, ast.Name) and e.value.id in self.f.lists \
                and not isinstance(e.slice, ast.Slice):
            return self.f.lists[e.value.id]
        return None

    def visit_Subscript(self, node: ast.Subscript) -> ast.expr:
        inner = node.value
        if isinstance(inner, ast.Subscript) and isinstance(inner.value, ast.Name) and inner.value.id in self.f.lists \
                and not isinstance(inner.slice, ast.Slice) and isinstance(node.ctx, ast.Load):
            shape = self.f.lists[inner.value.id]          # `L[i][k]`: the element list k at i
            k = node.slice
            v = k.value if isinstance(k, ast.Constant) and type(k.value) is int else (
                -k.operand.value if isinstance(k, ast.UnaryOp) and isinstance(k.op, ast.USub)
                and isinstance(k.operand, ast.Constant) and type(k.operand.value) is int else None)
            if v is None or not -len(shape) <= v < len(shape):
                raise _Problem(node.lineno, f"`{ast.unparse(node)}`: an item of a list of tuples is indexed by a "
                                            f"constant inside its {len(shape)} elements in this step")
            return ast.copy_location(ast.Subscript(value=_name(self.f.elem(inner.value.id, v % len(shape))),
                                                   slice=self.visit(inner.slice), ctx=ast.Load()), node)
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
            if isinstance(k, ast.Slice):
                raise _Problem(node.lineno, f"`{ast.unparse(node)}`: a slice of a tuple is a new tuple, which the AOT "
                                            "mode does not build in this step")
            if len(set(shape)) != 1:
                raise _Problem(node.lineno, f"`{ast.unparse(node)}`: a variable index into a tuple whose elements "
                                            f"differ in type (`tuple[{', '.join(shape)}]`) is a union, which the AOT "
                                            "mode does not type (index it by a constant)")
            index = self.visit(k)
            call = ast.Call(func=_name(TUPLE_INDEX), args=[index, *self.f.elems(node.value.id)], keywords=[])
            return ast.copy_location(call, node)
        return self.generic_visit(node)

    def visit_Call(self, node: ast.Call) -> ast.expr:
        f = node.func
        if isinstance(f, ast.Name) and f.id not in self.f.locals and len(node.args) == 1 and not node.keywords:
            lst = self.list_name(node.args[0])
            if lst is not None and f.id == "len":
                return ast.Call(func=_name("len"), args=[self.f.comps(lst)[0]], keywords=[])
            if f.id in ("str", "repr"):
                made = self.f.as_list(node.args[0], node.lineno)
                if made is not None:
                    return self.f.list_repr(made, node.lineno)
            els = self.tuple_elements(node.args[0])
            if els is not None and f.id == "len" and isinstance(node.args[0], ast.Name):
                return ast.Constant(len(els))
            if els is not None and f.id in ("str", "repr"):
                return ast.JoinedStr(values=_repr_pieces(els))
        if isinstance(f, ast.Name) and f.id == "print" and f.id not in self.f.locals:
            args = []
            for a in node.args:
                made = self.f.as_list(a, node.lineno)
                if made is not None:
                    args.append(self.f.list_repr(made, node.lineno))
                    continue
                els = self.tuple_elements(a)
                args.append(ast.JoinedStr(values=_repr_pieces(els)) if els is not None else self.visit(a))
            node.args = args
            node.keywords = [self.visit(k) for k in node.keywords]
            return node
        if isinstance(f, ast.Name) and f.id in self.f.params and f.id not in self.f.locals \
                and (any(s is not None for s in self.f.params[f.id])
                     or any(s is not None for s in self.f.lparams.get(f.id, ())))\
                and len(node.args) == len(self.f.params[f.id]):
            args = []
            for a, s, ls in zip(node.args, self.f.params[f.id], self.f.lparams[f.id]):
                if ls is not None:
                    if self.list_name(a) is None or self.f.lists[a.id] != ls:          # type: ignore[attr-defined]
                        raise _Problem(node.lineno, f"`{ast.unparse(a)}` is passed to a `list[tuple[{', '.join(ls)}]]` "
                                                    f"parameter of `{f.id}`; an AOT program passes a list of tuples "
                                                    "name of that shape")
                    args += self.f.comps(a.id)                                   # type: ignore[attr-defined]
                    continue
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
                made = self.f.as_list(v.value, node.lineno)
                if made is not None:
                    values.append(ast.FormattedValue(value=self.f.list_repr(made, node.lineno), conversion=-1,
                                                     format_spec=None))
                    continue
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
        if not all(_pure(e) or _item(e) for e in a + b):
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
