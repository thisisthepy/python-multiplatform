"""Whole-program class immutability analysis and cycle-freedom proof (SPEC N-17, #486).

Single source of truth for PR-O1 (docs/design/typedpython-aot.md 6.3.1):
  1. Classifies every `(C, field)` as `immutable_after_init` iff no statement outside `C.__init__`
     anywhere in the program assigns to `field` on an instance of `C`, and `C.__init__` does not
     store `self` into any field or let `self` escape into any call.
  2. Proves cycle-freedom for every compiled class `C` by either type-graph acyclicity (Tarjan SCC)
     or `immutable_after_init` for every class in `C`'s SCC. A class proved by neither is *cyclic-capable*
     (`cyclic_classes`, with the reason in `cycle_reasons`): until PR-O2c that was the finding
     `aot/cycle-possible`; since PR-O2c (6.3.2 item 3, "Step 3 as implemented") such a class is compiled and
     tracked by the cycle collector of `runtime/tp_aot_gc.h`. Every class of the strongly connected component
     is cyclic-capable, not only its mutable members: trial deletion subtracts only the references between
     tracked objects, so an untracked class on a cycle would be a root that keeps the cycle forever.
     A `__del__` on a cyclic-capable class is `aot/cycle-del`.
  3. Provides the same `immutable_after_init` result to reference-counting elision (`aot/emit.py`, through
     `field_elidable`, which is off for every field of a cyclic-capable class: the safepoint invariant of
     the collector needs every reference to a tracked object counted) and library-mode safety
     (`aot/library.py`: `aot/library-mutable-class`).
"""
from __future__ import annotations

from dataclasses import dataclass, field

from typedpython import ir
from typedpython.aotcheck import Finding
from typedpython.ir import Type


# The child expressions of every `ir.Expr` node, by attribute name: a single child (`EXPR_CHILD_ATTRS`,
# an `Expr` or None) and a tuple of children (`EXPR_CHILD_SEQS`). Every expression walker of the AOT
# analyses (`_expr_uses_any`, `_check_init_escape` here, `aot/alloc.py`) reads these two tuples, so a node
# is never visible to one walker and invisible to another (review of #501: `NewArray.length` / `.fill` and
# `CallObject.callee` were missing from four hand-written copies). When a node with a new child attribute
# is added to `typedpython.ir`, add the attribute here.
EXPR_CHILD_ATTRS = ("left", "right", "operand", "value", "obj", "index", "cond", "then", "orelse",
                    "length", "fill", "callee", "np")
EXPR_CHILD_SEQS = ("args", "elements", "parts")


def _expr_uses_any(expr: ir.Expr, names: set[str]) -> bool:
    """True if `expr` references any local in `names`."""
    if isinstance(expr, ir.Local):
        return expr.name in names
    for attr in EXPR_CHILD_ATTRS:
        sub = getattr(expr, attr, None)
        if isinstance(sub, ir.Expr) and _expr_uses_any(sub, names):
            return True
    for attr in EXPR_CHILD_SEQS:
        subs = getattr(expr, attr, None)
        if isinstance(subs, tuple):
            for s in subs:
                if isinstance(s, ir.Expr) and _expr_uses_any(s, names):
                    return True
    return False


def _walk_stmts(stmts: tuple[ir.Stmt, ...]):
    """Yield every statement in `stmts` recursively."""
    for s in stmts:
        yield s
        if isinstance(s, ir.If):
            yield from _walk_stmts(s.then)
            yield from _walk_stmts(s.orelse)
        elif isinstance(s, (ir.ForRange, ir.While, ir.ForObj, ir.ForStr)):
            yield from _walk_stmts(s.body)


def _check_init_escape(fn: ir.Function) -> str | None:
    """Return a reason string if `self` escapes in `C.__init__`, else None."""
    if not fn.params:
        return None
    self_name = fn.params[0].name
    aliases: set[str] = {self_name}
    all_stmts = list(_walk_stmts(fn.body))
    changed = True
    while changed:
        changed = False
        for s in all_stmts:
            if isinstance(s, ir.Assign) and isinstance(s.value, ir.Local) and s.value.name in aliases:
                if s.target not in aliases:
                    aliases.add(s.target)
                    changed = True

    def _expr_escapes(e: ir.Expr) -> str | None:
        if isinstance(e, (ir.Call, ir.New, ir.CallObject)):
            # an object call: `self` as the callee (a bound method) escapes like an argument
            operands = ((e.callee,) if isinstance(e, ir.CallObject) else ()) + e.args
            for a in operands:
                if _expr_uses_any(a, aliases):
                    return f"`self` escapes into a call in `{fn.name}`"
                sub = _expr_escapes(a)
                if sub is not None:
                    return sub
            return None
        if isinstance(e, ir.ListDisplay):
            for a in e.elements:
                if _expr_uses_any(a, aliases):
                    return f"`self` escapes into a list in `{fn.name}`"
                sub = _expr_escapes(a)
                if sub is not None:
                    return sub
            return None
        for attr in EXPR_CHILD_ATTRS:
            sub_e = getattr(e, attr, None)
            if isinstance(sub_e, ir.Expr):
                res = _expr_escapes(sub_e)
                if res is not None:
                    return res
        for attr in EXPR_CHILD_SEQS:
            subs = getattr(e, attr, None)
            if isinstance(subs, tuple):
                for sub_e in subs:
                    if isinstance(sub_e, ir.Expr):
                        res = _expr_escapes(sub_e)
                        if res is not None:
                            return res
        return None

    for s in all_stmts:
        if isinstance(s, ir.FieldSet):
            if _expr_uses_any(s.value, aliases):
                return f"`self` is stored into `{s.cls}.{s.field}` in `{fn.name}`"
            res = _expr_escapes(s.value)
            if res is not None:
                return res
        elif isinstance(s, ir.StoreIndex):
            if _expr_uses_any(s.value, aliases):
                return f"`self` is stored into a list in `{fn.name}`"
            res = _expr_escapes(s.value)
            if res is not None:
                return res
        elif isinstance(s, (ir.Assign, ir.ExprStmt, ir.Return)):
            val = getattr(s, "value", None)
            if isinstance(val, ir.Expr):
                res = _expr_escapes(val)
                if res is not None:
                    return res
    return None


def _tarjan_scc(nodes: list[str], edges: dict[str, set[str]]) -> list[list[str]]:
    """Tarjan's strongly connected components algorithm."""
    index = 0
    indices: dict[str, int] = {}
    lowlink: dict[str, int] = {}
    stack: list[str] = []
    on_stack: set[str] = set()
    sccs: list[list[str]] = []

    def strongconnect(v: str) -> None:
        nonlocal index
        indices[v] = index
        lowlink[v] = index
        index += 1
        stack.append(v)
        on_stack.add(v)
        for w in sorted(edges.get(v, ())):
            if w not in indices:
                strongconnect(w)
                lowlink[v] = min(lowlink[v], lowlink[w])
            elif w in on_stack:
                lowlink[v] = min(lowlink[v], indices[w])
        if lowlink[v] == indices[v]:
            comp: list[str] = []
            while True:
                w = stack.pop()
                on_stack.remove(w)
                comp.append(w)
                if w == v:
                    break
            sccs.append(comp)

    for v in nodes:
        if v not in indices:
            strongconnect(v)
    return sccs


@dataclass
class ClassAnalysis:
    """Whole-program class immutability, acyclicity, and layout metadata."""

    classes: dict[str, ir.ClassDecl] = field(default_factory=dict)
    field_map: dict[tuple[str, str], ir.FieldDecl] = field(default_factory=dict)
    immutable_fields: frozenset[tuple[str, str]] = field(default_factory=frozenset)
    immutable_ref_classes: frozenset[str] = field(default_factory=frozenset)
    immutable_all_classes: frozenset[str] = field(default_factory=frozenset)
    self_escapes: dict[str, str] = field(default_factory=dict)
    mutating_stores: dict[tuple[str, str], tuple[str, int]] = field(default_factory=dict)
    tail_field: dict[str, str | None] = field(default_factory=dict)
    cyclic_classes: frozenset[str] = field(default_factory=frozenset)      # PR-O2c: tracked by the collector
    cycle_reasons: dict[str, str] = field(default_factory=dict)           # why each of them may form a cycle
    findings: list[Finding] = field(default_factory=list)

    def field_immutable(self, cls: str, fname: str) -> bool:
        return (cls, fname) in self.immutable_fields

    def field_elidable(self, cls: str, fname: str) -> bool:
        """PR-O1's elision rules (3) and (4) may skip the retain of a read of `cls.fname`: the field is
        immutable-after-init and `cls` is not cyclic-capable (PR-O2c: at a collector safepoint no tracked
        object may be kept alive solely by an uncounted reference, so a tracked class's fields are always
        retained when read, immutable or not)."""
        return cls not in self.cyclic_classes and (cls, fname) in self.immutable_fields

    def is_cyclic(self, cls: str) -> bool:
        return cls in self.cyclic_classes

    def class_immutable_for_library(self, cls: str) -> bool:
        seen: set[str] = set()
        stack: list[str] = [cls]
        while stack:
            cur = stack.pop()
            if cur in seen:
                continue
            seen.add(cur)
            if cur not in self.immutable_all_classes:
                return False
            decl = self.classes.get(cur)
            if decl is not None:
                for fd in decl.field_decls:
                    if fd.type == Type.OBJ and fd.cls is not None and fd.cls in self.classes:
                        stack.append(fd.cls)
        return True


def analyze_classes(module: ir.Module, rel: str, class_lines: dict[str, int] | None = None,
                    tagged_fields: set[tuple[str, str]] | None = None) -> ClassAnalysis:
    """Run the single whole-program immutability and cycle-freedom analysis on `module`."""
    classes = {c.name: c for c in module.classes}
    if not classes:
        return ClassAnalysis()
    lines = class_lines or {}
    tagged = tagged_fields or set()
    field_map: dict[tuple[str, str], ir.FieldDecl] = {}
    for c in module.classes:
        for fd in c.field_decls:
            field_map[(c.name, fd.name)] = fd

    # 1. Find all field stores outside C.__init__ (or on a non-self receiver inside C.__init__)
    mutating_stores: dict[tuple[str, str], tuple[str, int]] = {}
    funcs_by_name = {f.name: f for f in module.functions}
    for fn in module.functions:
        self_name = fn.params[0].name if fn.params else None
        for s in _walk_stmts(fn.body):
            if isinstance(s, ir.FieldSet):
                is_self_init = (
                    fn.name == f"{s.cls}.__init__"
                    and self_name is not None
                    and isinstance(s.obj, ir.Local)
                    and s.obj.name == self_name
                )
                if not is_self_init:
                    mutating_stores.setdefault((s.cls, s.field), (fn.name, fn.source_line))

    # 2. Check self escape in each C.__init__
    self_escapes: dict[str, str] = {}
    for cname in classes:
        init_fn = funcs_by_name.get(f"{cname}.__init__")
        if init_fn is not None:
            why = _check_init_escape(init_fn)
            if why is not None:
                self_escapes[cname] = why

    # 3. Classify immutable_fields, immutable_ref_classes, immutable_all_classes
    immutable_fields: set[tuple[str, str]] = set()
    for cname, decl in classes.items():
        if cname in self_escapes:
            continue
        for fd in decl.field_decls:
            if (cname, fd.name) not in mutating_stores:
                immutable_fields.add((cname, fd.name))

    def _is_ref_field(cname: str, fd: ir.FieldDecl) -> bool:
        if fd.type == Type.OBJ:
            return True
        if fd.type in ir.ARRAYS or fd.type is Type.STR:     # (STR: tier S1b, an owned reference)
            return True
        if fd.type == Type.I64 and (cname, fd.name) in tagged:
            return True
        return False

    immutable_ref_classes: set[str] = set()
    immutable_all_classes: set[str] = set()
    for cname, decl in classes.items():
        if cname in self_escapes:
            continue
        if all((cname, fd.name) in immutable_fields for fd in decl.field_decls if _is_ref_field(cname, fd)):
            immutable_ref_classes.add(cname)
        if all((cname, fd.name) in immutable_fields for fd in decl.field_decls):
            immutable_all_classes.add(cname)

    # 4. Cycle-freedom proof via Tarjan SCC
    edges: dict[str, set[str]] = {c: set() for c in classes}
    for cname, decl in classes.items():
        for fd in decl.field_decls:
            if fd.type == Type.OBJ and fd.cls is not None and fd.cls in classes:
                edges[cname].add(fd.cls)

    sccs = _tarjan_scc(list(classes.keys()), edges)
    findings: list[Finding] = []
    cyclic_classes: set[str] = set()
    cycle_reasons: dict[str, str] = {}
    for comp in sccs:
        has_cycle = len(comp) > 1 or (len(comp) == 1 and comp[0] in edges[comp[0]])
        if not has_cycle:
            continue
        mutable_in_scc = [c for c in comp if c not in immutable_ref_classes]
        if not mutable_in_scc:
            continue
        for cname in comp:
            culprit = cname if cname in mutable_in_scc else mutable_in_scc[0]
            if culprit in self_escapes:
                reason = self_escapes[culprit]
            else:
                mut_fields = [
                    (f"{culprit}.{fd.name}", mutating_stores[(culprit, fd.name)])
                    for fd in classes[culprit].field_decls
                    if _is_ref_field(culprit, fd) and (culprit, fd.name) in mutating_stores
                ]
                if mut_fields:
                    fname, (where_fn, where_line) = mut_fields[0]
                    if where_fn == f"{culprit}.__init__":
                        reason = f"field `{fname}` is mutated on another instance in `{where_fn}` (line {where_line})"
                    else:
                        reason = f"field `{fname}` is mutated outside `__init__` in `{where_fn}` (line {where_line})"
                else:
                    reason = f"class `{culprit}` is not immutable after `__init__`"
            # PR-O2c: the class is compiled, tracked by the cycle collector (it was `aot/cycle-possible`)
            cyclic_classes.add(cname)
            cycle_reasons[cname] = reason
            if classes[cname].has_del:
                findings.append(Finding(
                    rel, lines.get(cname, 1), "error", None, "aot/cycle-del", cname,
                    f"class `{cname}` defines `__del__` but may form a reference cycle ({reason}); a finalizer "
                    "is permitted only on a class proved acyclic (an acyclic class graph or immutable-after-init "
                    "reference fields), because the collector that frees a cycle runs no user code",
                    None,
                ))

    # 5. Last self-referential field for tail-loop release
    tail_field: dict[str, str | None] = {}
    for cname, decl in classes.items():
        last_self: str | None = None
        for fd in decl.field_decls:
            if fd.type == Type.OBJ and fd.cls == cname:
                last_self = fd.name
        tail_field[cname] = last_self

    return ClassAnalysis(
        classes=classes,
        field_map=field_map,
        immutable_fields=frozenset(immutable_fields),
        immutable_ref_classes=frozenset(immutable_ref_classes),
        immutable_all_classes=frozenset(immutable_all_classes),
        self_escapes=self_escapes,
        mutating_stores=mutating_stores,
        tail_field=tail_field,
        cyclic_classes=frozenset(cyclic_classes),
        cycle_reasons=cycle_reasons,
        findings=findings,
    )
