"""Which ints are RAW and which are TAGGED (SPEC N-17, #462).

Provenance. Design decision D1 (a) (leader, 2026-11-16, not owner-confirmed): an int that is not proved inside 64
bits is a small-int / big-int value of its own (`runtime/tp_aot_int.h`). The representation below (RAW int64 and
TAGGED `tpa_int`, decided per program by a fixpoint over all functions) was approved by the leader on 2026-11-16, not
owner-confirmed. The inference rule written out here, and the sound re-proof it rests on in particular, are agent
proposals, not owner-confirmed.

Two representations of an IR int (a value of type `Type.I64`; the IR calls every int I64 because the front end and
the verifier prove it fits, the AOT back end now reads that name as "an int the front end typed as an int"):

  RAW     an int known to fit int64. Stored as `int64_t` and computed as before this step (a proved operation is plain
          machine arithmetic).
  TAGGED  an int that may be any integer. Stored as `tpa_int` (an owned reference to a small int or a big integer).

The inference (`infer`) is one fixpoint over every function of the module, starting with everything RAW and raising a
name, a parameter, a return or a tuple element to TAGGED when something TAGGED flows into it (a representation only
goes up, so the loop ends). A name is a local, a parameter, a loop variable or a target of an unpacking; it has ONE
representation for the whole function. An expression is RAW only when it is one of:

  * an int constant (the source-level checker already refused one outside int64);
  * `len(...)` (a list length), `len(sys.argv)`;
  * the loop variable of a `range` loop whose start and stop are RAW (the step is a constant);
  * an element read from a `list[int]` of a RAW list group (below); an element read from a big-capable group is TAGGED;
  * a name whose every assignment is RAW; a parameter whose every call site passes a RAW value, whose body assigns
    only RAW values, and (for an export of a library) whatever arrives from outside, which is an int64;
  * a call whose callee returns RAW (every `return` of the callee is RAW);
  * a conditional expression whose two branches are both RAW;
  * `+ - *` and unary minus that the IR marks `proven` AND that the sound re-proof (below) proves AND whose operands
    are all RAW;
  * `//` by a constant other than -1 of a RAW operand, and `%` of two RAW operands (the result lies between zero and
    the divisor); a `//` by -1 or by a variable is TAGGED (INT64_MIN // -1 is 2**63).

Everything else is TAGGED: the result of an operation that is not proved, of any operation with a TAGGED operand,
`int(sys.argv[i])`, an int read from a dict or bound by a loop over one (tier S2b: a dict holds its ints as
`tpa_int`, so a key or a value may be any int; agent proposal, not owner-confirmed), and every name, parameter,
return and tuple element such a value flows into. A name assigned
both RAW and TAGGED values is TAGGED (a RAW value is converted with `tpa_int_from_i64` where it is stored).

The sound re-proof (why a proved flag is not enough). The front end and the verifier prove an operation from
intervals that read every int as one inside 64 bits: the interval of an operation that is not proved is its clamp to
int64 ("it would have deopted"), a parameter or a call result is "any int64", and a guard `a < b` cuts `a` by that
interval of `b`. Where a value is TAGGED none of that is true of it. Example: `lo = items[0]` (any int64),
`hi = lo * lo` (TAGGED), `if lo < hi: lo = lo + 1`: the verifier proves `lo + 1` from `lo < hi` and `hi <= INT64_MAX`,
false for the big `hi`, and with `lo == INT64_MAX` a RAW addition would wrap; `n = 9223372036854775807 - hi` then
`a = [0] * (n + 5)` gives `a` a length of at least 5 to the verifier, and `a[4]` a bounds check it may skip, while the
list is empty. So the proved flags are only a candidate: `verify.sound_proofs` walks the verified IR again with
intervals that hold of unbounded integers, given this inference as its oracle (a RAW name or return is an int64, a
TAGGED one is unbounded, `int(sys.argv[i])` is unbounded, nothing is clamped; verify.py "The sound re-proof of the
AOT mode"), and an operation is RAW, and a proved index skips its check (`Representation.trusted`), only if it is
proved there too. The two are one fixpoint: the inference starts with everything RAW, the re-proof runs under it, a
name rises to TAGGED where a proof fails, and the re-proof runs again until nothing rises. At the end every RAW name
is an int64 on every path by induction over the execution: each RAW value is a constant, a length, an element, or a
proved operation whose operands are RAW (int64 by the induction) and whose interval facts hold of unbounded ints.

What this cannot find: it treats every name as one representation for the whole function, so a counter that is RAW
for its first thousand iterations is TAGGED from the start (speed, never exactness); and the re-proof proves less
than the default mode's rules where a fact needs a TAGGED value's int64 range (`if lo < hi` above).

Lists of ints (#465; static classification: leader decision 2026-11-16, not owner-confirmed; the rules and their
wording are agent proposals, not owner-confirmed). Every `list[int]` name of the program (a local, a parameter, the
list a function returns, a call result, a creation) belongs to a GROUP. Two names are merged into one group whenever
they may denote the same list object:

  * `b = a`;
  * an argument and the parameter it is passed to, at every call site (a call anywhere in an expression, a statement
    that drops the result, an unpacking);
  * the list a function returns and the call result that receives it (every `return` of the function joins the
    function's return node, and an assignment from a call joins the target to it);
  * a name that is assigned more than once: all the lists it ever holds are one group (a group is made of NAMES, not of
    assignments), so both branches of a conditional or the two sides of a join are one group already;
  * a list that crosses a library boundary is one group with the outside: an export's list parameter and list result
    are nodes like any other, and the class of their group is what the generated signature says (`aot/library.py`).

A copy `b = a[:]` makes a new object, so it does not merge `b` with `a`; but its ELEMENTS come from `a`, so it is a
one-way constraint: when the group of `a` is big-capable, the group of `b` is too (a copy of a raw list that is itself
big-capable is a conversion, `tpa_int_list_from_i64`). A group is BIG-CAPABLE when any of these can happen to a
member, and RAW otherwise:

  * an element store of a TAGGED value (`a[i] = v`, `a[i] op= v`: the value is TAGGED by the int inference);
  * a creation by `[v] * n` with a TAGGED `v`, or by a display with a TAGGED element (`list(range(n))` holds only
    values below `n`, which fit int64, so it creates a raw list);
  * a copy from a big-capable group (above);
  * anything that is not understood: a list name that is assigned something that is not a list name, a call, a copy or a
    creation, an argument of a list parameter that is not a list name (the emitter refuses most of these, so this is
    the rule that cannot make a wrong program run).

The class and the int inference depend on each other (an element read from a RAW group is a RAW int; one from a
big-capable group is a TAGGED int, unbounded for the sound re-proof; whether a stored value is TAGGED depends on the int
inference), so they are one fixpoint: both only rise (RAW to TAGGED, raw group to big-capable), so it ends. At the end
every element read from a RAW group is an int64 on every path: nothing but a RAW value is ever stored into it (induction
over the stores, as for the names), and every list that flows into it by aliasing is in the same group. A group is a
set of names, so a name that holds two lists is as big-capable as the worse of the two.

What this cannot find: the classification is per name and per program, not per object or per path, so a list that
receives a big value on one path only (or once, in a loop that never runs) makes every list it may alias big-capable
(speed, never exactness: a big-capable list pays tagged element access); and it does not look at what a callee does to
a list it is given unless the callee's parameter is in the same group, which is every callee.

`Representation.list_groups()` and `explain(program)` report the groups, the merges and the causes (`--explain-lists`).

Strict mode (`infer(..., strict=True)`, `--strict-int`): nothing is ever TAGGED here. The back end then keeps what it
did before this step: an operation that is not proved is the finding `aot/int-unproven`, so a strict build refuses
every program that needs a TAGGED value (design decision D1 (a), not owner-confirmed). Every list is RAW there.
"""
from __future__ import annotations

import dataclasses
from dataclasses import dataclass, field

from typedpython import ir
from typedpython.ir import Type

RAW = "raw"
TAGGED = "tagged"
BIG = "big-capable"        # the class of a list group that is not RAW (RAW is also the name of the other class)
UNSET = -1                 # the statement of a local that no statement has assigned yet
RETURN = "<return>"        # the name of a function's returned list in a group (not an identifier, so no clash)


def join(a: str, b: str) -> str:
    return TAGGED if TAGGED in (a, b) else RAW


def children(node):
    """Every IR expression and statement directly inside `node` (fields that are nodes or tuples of nodes)."""
    for fl in dataclasses.fields(node):
        v = getattr(node, fl.name)
        for x in (v if isinstance(v, tuple) else (v,)):
            if isinstance(x, (ir.Expr, ir.Stmt)):
                yield x


def walk(node):
    """`node` and everything under it, pre-order."""
    yield node
    for c in children(node):
        yield from walk(c)


def walk_stmts(node, cur=None):
    """`(node, the number of the source statement it belongs to)` for `node` and everything under it, pre-order (`cur`
    is the statement the enclosing one gave, for a node that is outside every statement)."""
    if isinstance(node, ir.Stmt) and node.stmt is not None:
        cur = node.stmt
    yield node, cur
    for c in children(node):
        yield from walk_stmts(c, cur)


@dataclass
class FunctionReps:
    names: dict[str, str] = field(default_factory=dict)          # I64 name (parameter, local, loop variable) -> rep
    returns: str = RAW                                            # the rep of an int return
    tuple_elements: list[str] = field(default_factory=list)       # the rep of each element of a returned tuple
    name: str = ""                                                # the function (an element read asks its list's class)


Key = tuple[str, str]      # a list name of a function: (function, name); the returned list is (function, RETURN)


@dataclass
class Merge:
    """Two list names that may be one list: the statement that said so (the diagnostic reads it)."""
    a: Key
    b: Key
    fn: str
    stmt: int | None
    why: str


@dataclass
class Cause:
    """Why a group is big-capable: the statement of `fn` and what it does to the list `key`."""
    key: Key
    fn: str
    stmt: int | None
    what: str


@dataclass
class ListGroup:
    """One group of `list[int]` names (module docstring "Lists of ints")."""
    id: int
    members: list[Key]
    cls: str                                 # RAW or BIG
    merges: list[Merge]
    causes: list[Cause]


class Representation:
    """The result of `infer`: the representation of every int of every function."""

    def __init__(self, module: ir.Module, strict: bool = False) -> None:
        self.module = module
        self.strict = strict
        self.fns = {f.name: f for f in module.functions}
        self.reps = {f.name: FunctionReps(name=f.name) for f in module.functions}
        self.field_reps: dict[tuple[str, str], str] = {}
        # the sound re-proof (module docstring): id of a node of `module` -> proved under intervals of unbounded ints
        self.proofs: dict[int, bool] = {}
        # the groups of `list[int]` names (module docstring "Lists of ints")
        self._parent: dict[Key, Key] = {}
        self._first: dict[Key, tuple[str, int | None]] = {}    # a name -> (function, statement) it first appears at
        self._merges: list[Merge] = []
        self._copies: list[tuple[Key, Key, str, int | None]] = []     # (source, copy, function, statement)
        self._big: set[Key] = set()                                     # roots of the big-capable groups
        self._causes: list[Cause] = []
        self._cause_seen: set[tuple] = set()
        if not strict:
            self.build_lists()
            self.run()

    # -- queries

    def name(self, fn: str, name: str) -> str:
        return self.reps[fn].names.get(name, RAW)

    def returns(self, fn: str) -> str:
        return self.reps[fn].returns

    def field(self, cls: str, fname: str) -> str:
        if self.strict:
            return RAW
        return self.field_reps.get((cls, fname), RAW)

    def field_list_big(self, cls: str, fname: str) -> bool:
        if self.strict:
            return False
        key = (f"<class:{cls}>", fname)
        if key not in self._parent:
            return False
        return self.find(key) in self._big

    def element(self, fn: str, i: int) -> str:
        r = self.reps[fn].tuple_elements
        return r[i] if i < len(r) else RAW

    def of(self, fn: str, e: ir.Expr) -> str:
        """The representation of the expression `e` of function `fn` (RAW for anything that is not an int)."""
        if self.strict or getattr(e, "type", None) is not Type.I64:
            return RAW
        return self._of(self.reps[fn], e)

    def trusted(self, fn: str, node: ir.Index | ir.StoreIndex) -> bool:
        """May the proved index `node` (an `Index` or `StoreIndex` of `fn` the verifier marked `proven`) skip its
        bounds check? Only if the sound re-proof proves it in bounds too."""
        if self.strict:
            return True
        return self.proofs.get(id(node), False)

    # -- the lists

    def list_big(self, fn: str, name: str) -> bool:
        """Is the `list[int]` name `name` of function `fn` in a big-capable group? (strict mode has none; a name the
        analysis does not know is big-capable: on any doubt, big-capable.)"""
        if self.strict:
            return False
        key = (fn, name)
        if key not in self._parent:
            return True
        return self.find(key) in self._big

    def list_class(self, fn: str, name: str) -> str:
        return BIG if self.list_big(fn, name) else RAW

    def return_big(self, fn: str) -> bool:
        """Is the list function `fn` returns in a big-capable group?"""
        return self.list_big(fn, RETURN)

    def list_groups(self) -> list[ListGroup]:
        """Every group, in the order of its first member (function order, parameters, locals, the returned list)."""
        by_root: dict[Key, ListGroup] = {}
        for key in self._parent:
            root = self.find(key)
            g = by_root.get(root)
            if g is None:
                g = by_root[root] = ListGroup(len(by_root) + 1, [], BIG if root in self._big else RAW, [], [])
            g.members.append(key)
        for m in self._merges:
            by_root[self.find(m.a)].merges.append(m)
        for c in self._causes:
            by_root[self.find(c.key)].causes.append(c)
        return list(by_root.values())

    def find(self, key: Key) -> Key:
        root = key
        while self._parent[root] != root:
            root = self._parent[root]
        while self._parent[key] != root:
            self._parent[key], key = root, self._parent[key]
        return root

    def node(self, key: Key, stmt: int | None = None) -> None:
        if key not in self._parent:
            self._parent[key] = key
            self._first[key] = (key[0], stmt)

    def union(self, a: Key, b: Key, fn: str, stmt: int | None, why: str) -> None:
        self.node(a, stmt)
        self.node(b, stmt)
        ra, rb = self.find(a), self.find(b)
        if ra == rb:
            return
        self._parent[rb] = ra
        if rb in self._big:
            self._big.discard(rb)
            self._big.add(ra)
        self._merges.append(Merge(a, b, fn, stmt, why))

    def mark_big(self, key: Key, fn: str, stmt: int | None, what: str) -> bool:
        """The group of `key` is big-capable for this reason. True when it was not yet."""
        tag = (key, fn, stmt, what)
        if tag not in self._cause_seen:
            self._cause_seen.add(tag)
            self._causes.append(Cause(key, fn, stmt, what))
        root = self.find(key)
        if root in self._big:
            return False
        self._big.add(root)
        return True

    @staticmethod
    def shown(key: Key) -> str:
        return f"{key[0]}() return" if key[1] == RETURN else f"{key[0]}.{key[1]}"

    def build_lists(self) -> None:
        """The nodes and the merges (they do not depend on the representations; what makes a group big-capable does,
        and is found by `scan`)."""
        for f in self.module.functions:
            for p in f.params:
                if isinstance(p, ir.ArrayParam) and p.type is Type.I64_ARRAY:
                    self.node((f.name, p.name))
            for name, t in f.locals.items():
                if t is Type.I64_ARRAY:
                    self.node((f.name, name), UNSET)
            if f.returns is Type.I64_ARRAY:
                self.node((f.name, RETURN))
        for c in self.module.classes:
            for fd in c.field_decls:
                if fd.type is Type.I64_ARRAY:
                    self.node((f"<class:{c.name}>", fd.name))
        for f in self.module.functions:
            for s in f.body:
                for n, cur in walk_stmts(s):
                    if isinstance(n, ir.Assign) and (f.name, n.target) in self._parent:
                        self.bind((f.name, n.target), n.value, f, cur, "assignment")
                    elif isinstance(n, ir.FieldSet) and (f"<class:{n.cls}>", n.field) in self._parent:
                        self.bind((f"<class:{n.cls}>", n.field), n.value, f, cur, "field assignment")
                    elif isinstance(n, ir.Return) and f.returns is Type.I64_ARRAY and n.value is not None:
                        self.bind((f.name, RETURN), n.value, f, cur, "return")
                    elif isinstance(n, ir.Call):
                        self.bind_call(n, f, cur)
                    elif isinstance(n, ir.ListExtend) and (f.name, n.array) in self._parent \
                            and (f.name, n.other) in self._parent:
                        # tier S2a: the items of `other` move into `array` (a copy of each item, so one group)
                        self.union((f.name, n.array), (f.name, n.other), f.name, cur,
                                   f"`{n.array}.extend({n.other})`")
                    elif isinstance(n, ir.ListSetSlice) and (f.name, n.array) in self._parent \
                            and (f.name, n.other) in self._parent:
                        self.union((f.name, n.array), (f.name, n.other), f.name, cur,
                                   f"`{n.array}[...] = {n.other}`")
                    elif isinstance(n, ir.ListCompare) and (f.name, n.left) in self._parent \
                            and (f.name, n.right) in self._parent:
                        # tier S2a: two compared lists share one kind, so the comparison reads both alike
                        self.union((f.name, n.left), (f.name, n.right), f.name, cur,
                                   f"`{n.left}` compared with `{n.right}`")
                    elif isinstance(n, ir.New):
                        init_fn = self.fns.get(f"{n.cls}.__init__")
                        if init_fn is not None:
                            for p, a in zip(init_fn.params[1:], n.args):
                                if isinstance(p, ir.ArrayParam) and p.type is Type.I64_ARRAY:
                                    if isinstance(a, ir.Local) and a.type is Type.I64_ARRAY:
                                        self.union((f.name, a.name), (init_fn.name, p.name), f.name, cur,
                                                   f"the argument and the parameter `{p.name}` of `{init_fn.name}`")
                                    else:
                                        self.mark_big((init_fn.name, p.name), f.name, cur,
                                                      f"an argument of `{init_fn.name}` that is not a list name")

    def bind(self, target: Key, value: ir.Expr, f: ir.Function, cur: int | None, how: str) -> None:
        """`target` takes the list `value`: a name (one group), a call result (one group with the callee's returned
        list), a copy (one-way), a creation (nothing to merge), anything else (big-capable)."""
        if self._first[target][1] == UNSET:
            self._first[target] = (f.name, cur)
        if getattr(value, "type", None) is not Type.I64_ARRAY and not isinstance(value, ir.CopyArray):
            self.mark_big(target, f.name, cur, f"{how} of something that is not an `int` list")
        elif isinstance(value, ir.Local):
            self.union(target, (f.name, value.name), f.name, cur,
                       "assignment" if how == "assignment" else f"`return {value.name}`")
        elif isinstance(value, ir.Call):
            callee = self.fns.get(value.function)
            if callee is None or callee.returns is not Type.I64_ARRAY:
                self.mark_big(target, f.name, cur, f"{how} of the result of `{value.function}`, which is not a known list")
            else:
                self.union(target, (callee.name, RETURN), f.name, cur,
                           f"the call result of `{value.function}` and the list it returns")
        elif isinstance(value, ir.CopyArray):
            src = (f.name, value.src)
            if src in self._parent:
                self._copies.append((src, target, f.name, cur))
            elif self.types_of(f).get(value.src) is Type.I64_ARRAY:
                self.mark_big(target, f.name, cur, f"a copy of `{value.src}`, which is not a list of this function")
        elif isinstance(value, ir.FieldGet):
            fkey = (f"<class:{value.cls}>", value.field)
            if fkey in self._parent:
                self.union(target, fkey, f.name, cur, f"reads list field `{value.cls}.{value.field}`")
            else:
                self.mark_big(target, f.name, cur, f"reads unknown list field `{value.cls}.{value.field}`")
        elif isinstance(value, ir.LIST_MAKERS):
            # tier S2a: a + b, a * n, a[i:j]: the new list is one group with its sources (a merge, not a one-way
            # copy, so the items never change representation on the way)
            for src in ((value.left, value.right) if isinstance(value, ir.ListConcat) else (value.src,)):
                if (f.name, src) in self._parent:
                    self.union(target, (f.name, src), f.name, cur, f"{how} made from `{src}`")
                else:
                    self.mark_big(target, f.name, cur, f"{how} made from `{src}`, which is not a list of this function")
        elif not isinstance(value, (ir.NewArray, ir.ListDisplay)):
            self.mark_big(target, f.name, cur, f"{how} of an expression that is not understood")

    @staticmethod
    def types_of(f: ir.Function) -> dict[str, Type]:
        out = {p.name: p.type for p in f.params if isinstance(p, (ir.Param, ir.ArrayParam))}
        out.update(f.locals)
        return out

    def bind_call(self, call: ir.Call, f: ir.Function, cur: int | None) -> None:
        callee = self.fns.get(call.function)
        if callee is None:
            return
        for p, a in zip(callee.params, call.args):
            if isinstance(p, ir.ArrayParam) and p.type is Type.I64_ARRAY:
                if isinstance(a, ir.Local) and a.type is Type.I64_ARRAY:
                    self.union((f.name, a.name), (callee.name, p.name), f.name, cur,
                               f"the argument and the parameter `{p.name}` of the call of `{call.function}`")
                else:
                    self.mark_big((callee.name, p.name), f.name, cur,
                                  f"an argument of the call of `{call.function}` that is not a list name")

    def explain(self, program=None, exports=None) -> str:
        """The diagnostic of `--explain-lists`: one block per group, in the order of `list_groups`, with its members
        (function, name, line), its class, each merge and each cause with the statement and the line that made it.
        `program` (aot/program.py) gives the lines and the text of the statements; `exports` (a library) the names of
        the exported functions, whose list parameters and results cross the boundary. The text is a function of the
        program alone (no address, no hash order)."""
        from typedpython import resume
        from typedpython.aot.program import MAIN

        stmts: dict[str, list] = {}
        source = program.source.splitlines() if program is not None else []
        bodies = program.bodies() if program is not None else {}

        def where(fn: str, stmt: int | None) -> tuple[int, str]:
            if fn not in stmts:
                stmts[fn] = resume.statements(bodies[fn]) if fn in bodies else []       # type: ignore[arg-type]
            line = self.fns[fn].source_line
            if stmt is not None and 0 <= stmt < len(stmts[fn]):
                line = stmts[fn][stmt].lineno
            text = source[line - 1].strip() if 0 < line <= len(source) else ""
            return line, text

        def disp(fn: str) -> str:
            return "__main__" if fn == MAIN else fn

        def name(key: Key) -> str:
            return f"{disp(key[0])}() return" if key[1] == RETURN else f"{disp(key[0])}.{key[1]}"

        exported = set(exports or ())
        out: list[str] = []
        for g in self.list_groups():
            out.append(f"list group {g.id}: {g.cls}")
            members = []
            for key in g.members:
                fn, stmt = self._first[key]
                members.append(f"{name(key)} (line {where(fn, None if stmt == UNSET else stmt)[0]})")
            out.append("  members: " + ", ".join(members))
            edge = []
            for key in g.members:
                if key[0] in exported and (key[1] == RETURN or key[1] in
                                           {q.name for q in self.fns[key[0]].params if isinstance(q, ir.ArrayParam)}):
                    edge.append(f"the result of the export `{key[0]}`" if key[1] == RETURN
                                else f"the parameter `{key[1]}` of the export `{key[0]}`")
            if edge:
                kind = "int_list" if g.cls == BIG else "i64_list"
                out.append(f"  library boundary ({kind}): " + ", ".join(edge))
            if g.merges:
                out.append("  merged:")
                for m in g.merges:
                    line, text = where(m.fn, m.stmt)
                    out.append(f"    line {line}, in {disp(m.fn)}: `{text}`: {name(m.a)} and {name(m.b)} may be the "
                               f"same list ({m.why})")
            else:
                out.append("  merged: nothing")
            if g.causes:
                out.append("  big-capable because:")
                for c in sorted(g.causes, key=lambda c: (where(c.fn, c.stmt)[0], c.fn, c.what)):
                    line, text = where(c.fn, c.stmt)
                    out.append(f"    line {line}, in {disp(c.fn)}: `{text}`: {c.what}")
            elif g.cls == BIG:
                out.append("  big-capable because: (a list this analysis does not understand)")
            else:
                out.append("  raw: no TAGGED value is stored into it, no creation of it holds one, and no copy of a "
                           "big-capable list reaches it")
        return "\n".join(out) + ("\n" if out else "")

    # -- the oracle of the sound re-proof (verify.sound_proofs)

    def raw_name(self, fn: str, name: str) -> bool:
        r = self.reps.get(fn)
        return r is not None and r.names.get(name) == RAW

    def raw_return(self, fn: str) -> bool:
        r = self.reps.get(fn)
        return r is not None and r.returns == RAW

    def raw_element(self, fn: str, array: str) -> bool:
        """Is an element of the list `array` of `fn` an int64? Only if its group is RAW."""
        return not self.list_big(fn, array)

    # -- the rule

    def proved(self, e: ir.Expr) -> bool:
        """`e` is marked `proven` and the sound re-proof proves it too."""
        return bool(getattr(e, "proven", False)) and self.proofs.get(id(e), False)

    def _of(self, r: FunctionReps, e: ir.Expr) -> str:
        if getattr(e, "type", None) is not Type.I64:
            return RAW
        if isinstance(e, ir.Const):
            return RAW
        if isinstance(e, ir.Local):
            return r.names.get(e.name, RAW)
        if isinstance(e, (ir.Len, ir.ArgvLen, ir.BoolToInt, ir.StrLen, ir.StrOrd, ir.StrWrite, ir.StrListLen, ir.StrMethod,
                          ir.ObjListLen, ir.DictLen)):
            return RAW
        if isinstance(e, (ir.Index, ir.ListPop)):          # (ListPop: tier S2a, an element as Index reads it)
            return TAGGED if self.list_big(r.name, e.array) else RAW
        if isinstance(e, ir.ListSearch):
            return RAW                                     # a position or a count: inside the length
        if isinstance(e, ir.FieldGet):
            return self.field_reps.get((e.cls, e.field), RAW)
        if isinstance(e, (ir.ArgvInt, ir.FloatToInt, ir.StrToInt, ir.MathCall, ir.StrParseInt)):
            return TAGGED
        if isinstance(e, ir.Call):
            callee = self.reps.get(e.function)
            return callee.returns if callee is not None else TAGGED
        if isinstance(e, ir.Cond):
            return join(self._of(r, e.then), self._of(r, e.orelse))
        if isinstance(e, ir.TupleIndex):                   # tier S2a: any element of a split tuple
            out = self._of(r, e.elements[0])
            for x in e.elements[1:]:
                out = join(out, self._of(r, x))
            return out
        if isinstance(e, ir.UnaryOp):
            if e.op in (ir.UnaryOpKind.POS, ir.UnaryOpKind.INVERT):
                return self._of(r, e.operand)
            if e.op is ir.UnaryOpKind.NEG:
                return RAW if self.proved(e) and self._of(r, e.operand) == RAW else TAGGED
            return TAGGED
        if isinstance(e, ir.BinOp):
            a, b = self._of(r, e.left), self._of(r, e.right)
            if a == TAGGED or b == TAGGED:
                return TAGGED
            k = e.op
            if k in (ir.BinOpKind.ADD, ir.BinOpKind.SUB, ir.BinOpKind.MUL, ir.BinOpKind.LSHIFT):
                return RAW if self.proved(e) else TAGGED
            if k in (ir.BinOpKind.MOD, ir.BinOpKind.AND, ir.BinOpKind.OR, ir.BinOpKind.XOR, ir.BinOpKind.RSHIFT):
                return RAW
            if k is ir.BinOpKind.FLOORDIV:
                if isinstance(e.right, ir.Const) and e.right.value != -1:
                    return RAW
                return RAW if self.proved(e) else TAGGED
            return TAGGED
        return TAGGED

    # -- the fixpoint

    def run(self) -> None:
        from typedpython import verify      # (verify does not import this module)

        for f in self.module.functions:
            r = self.reps[f.name]
            for p in f.params:
                if isinstance(p, ir.Param) and p.type is Type.I64:
                    r.names.setdefault(p.name, RAW)
            for name, t in f.locals.items():
                if t is Type.I64:
                    r.names.setdefault(name, RAW)
        while True:
            # the re-proof under the representation as it stands, then raise what it no longer proves; again until
            # a re-proof raises nothing (a representation only goes up, so this ends)
            self.proofs = verify.sound_proofs(self.module, self)
            raised = False
            changed = True
            while changed:
                changed = False
                for f in self.module.functions:
                    changed |= self.scan(f)
                changed |= self.propagate()
                raised |= changed
            if not raised:
                return

    def creation(self, key: Key, value: ir.Expr, r: FunctionReps, cur: int | None) -> bool:
        """A list created by `value` and bound to `key`: big-capable when it is made of a TAGGED value."""
        if key not in self._parent:
            return False
        if isinstance(value, ir.NewArray) and not value.iota and value.fill is not None \
                and value.fill.type is Type.I64 and self._of(r, value.fill) == TAGGED:
            return self.mark_big(key, r.name, cur, "creates it as `[v] * n` with a TAGGED `v`")
        if isinstance(value, ir.ListDisplay) and any(v.type is Type.I64 and self._of(r, v) == TAGGED
                                                     for v in value.elements):
            return self.mark_big(key, r.name, cur, "creates it as a display with a TAGGED element")
        return False

    def propagate(self) -> bool:
        """A copy of a big-capable list is big-capable (one-way)."""
        changed = False
        for src, dst, fn, cur in self._copies:
            if self.find(src) in self._big:
                changed |= self.mark_big(dst, fn, cur, f"copies `{src[1]}`, which is big-capable")
        return changed

    @staticmethod
    def raise_(d: dict[str, str], key: str, rep: str) -> bool:
        if rep == TAGGED and d.get(key) != TAGGED:
            d[key] = TAGGED
            return True
        return False

    def scan(self, f: ir.Function) -> bool:
        r = self.reps[f.name]
        changed = False
        elements = r.tuple_elements
        for s in f.body:
            for n, cur in walk_stmts(s):
                if isinstance(n, ir.Assign):
                    if n.value.type is Type.I64:
                        changed |= self.raise_(r.names, n.target, self._of(r, n.value))
                    elif n.value.type is Type.I64_ARRAY:
                        changed |= self.creation((f.name, n.target), n.value, r, cur)
                elif isinstance(n, ir.FieldSet):
                    if n.value.type is Type.I64 and self._of(r, n.value) == TAGGED:
                        if self.field_reps.get((n.cls, n.field), RAW) != TAGGED:
                            self.field_reps[(n.cls, n.field)] = TAGGED
                            changed = True
                elif isinstance(n, (ir.StoreIndex, ir.ListAppend, ir.ListInsert)):     # (tier S2a: append, insert)
                    if (f.name, n.array) in self._parent and n.value.type is Type.I64 \
                            and self._of(r, n.value) == TAGGED:
                        changed |= self.mark_big((f.name, n.array), f.name, cur,
                                                 f"stores a TAGGED value into `{n.array}`")
                elif isinstance(n, ir.ForRange):
                    rep = join(self._of(r, n.start), self._of(r, n.stop))
                    changed |= self.raise_(r.names, n.var, rep)
                elif isinstance(n, ir.ForDict):
                    # tier S2b: a dict holds its ints tagged (any int), so what a loop over one binds is TAGGED
                    for var, t in ((n.key_var, n.kinds[0]), (n.value_var, n.kinds[1])):
                        if var is not None and t is Type.I64:
                            changed |= self.raise_(r.names, var, TAGGED)
                elif isinstance(n, ir.Return):
                    if n.value is not None and n.value.type is Type.I64 and r.returns != TAGGED \
                            and self._of(r, n.value) == TAGGED:
                        r.returns = TAGGED
                        changed = True
                    elif n.value is not None and n.value.type is Type.I64_ARRAY:
                        changed |= self.creation((f.name, RETURN), n.value, r, cur)
                elif isinstance(n, ir.ReturnTuple):
                    while len(elements) < len(n.values):
                        elements.append(RAW)
                        changed = True
                    for i, v in enumerate(n.values):
                        if v.type is Type.I64 and elements[i] != TAGGED and self._of(r, v) == TAGGED:
                            elements[i] = TAGGED
                            changed = True
                elif isinstance(n, ir.Unpack):
                    callee = self.reps.get(n.call.function)
                    if callee is not None:
                        for i, t in enumerate(n.targets):
                            if i < len(callee.tuple_elements):
                                changed |= self.raise_(r.names, t, callee.tuple_elements[i])
                if isinstance(n, ir.Call):
                    callee_fn = self.fns.get(n.function)
                    callee = self.reps.get(n.function)
                    if callee_fn is not None and callee is not None:
                        for p, a in zip(callee_fn.params, n.args):
                            if isinstance(p, ir.Param) and p.type is Type.I64:
                                changed |= self.raise_(callee.names, p.name, self._of(r, a))
                elif isinstance(n, ir.New):
                    init_name = f"{n.cls}.__init__"
                    callee_fn = self.fns.get(init_name)
                    callee = self.reps.get(init_name)
                    if callee_fn is not None and callee is not None:
                        for p, a in zip(callee_fn.params[1:], n.args):
                            if isinstance(p, ir.Param) and p.type is Type.I64:
                                changed |= self.raise_(callee.names, p.name, self._of(r, a))
        return changed


def infer(module: ir.Module, *, strict: bool = False) -> Representation:
    """The representations of every int of `module`. (The int parameters of a library export also arrive from
    outside as int64, which is RAW, so they raise nothing: only the call sites inside the module do.)"""
    return Representation(module, strict)
