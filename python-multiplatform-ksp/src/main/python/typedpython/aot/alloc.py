"""Virtual objects / scalar replacement analysis for TypedPython AOT (SPEC N-17, #493).

Implements PR-O2a (docs/design/typedpython-aot.md 6.3.2 items 1 and 4):
  - Analyzes every `ir.New(cls, args)` allocation site in `module.functions`.
  - Promotes a local `v = ir.New(cls, args)` to `"virtual"` (scalar-replaced into
    per-field C locals with zero heap allocation) iff:
      1. `v` is a non-parameter local assigned at most once in the function, and
         that single assignment is `v = ir.New(cls, args)`.
      2. `cls` has no `__del__` method and its `__init__` is a simple
         field-initializing constructor (`self.<field> = <param>` or `<const>`).
      3. Every use of `v` in the function is exclusively `ir.FieldGet(Local(v), ...)`
         or `ir.FieldSet(Local(v), ..., val)` (where `val` may only reference `v`
         through `ir.FieldGet(Local(v), ...)`).
  - Otherwise falls back to `"heap-rc"` and records the exact escape/fallback reason
    for `--explain-alloc`.

PR-O2b (N-12b/c step 2, design 6.3.2 item 2 "Step 2 as implemented"; agent proposal, not owner-confirmed):
scope regions, decided per `(function, class)` after the virtual sites are known.
  - `(F, C)` is region when every site of `C` in `F` that is not virtual, and every local of `C` in `F`
    that is not virtual, can be region-typed together (one C type per local): `C` has no `__del__`, a
    simple `__init__` (the same `init_maps`) and at least one field (a zero-size region object would give
    every object one address), a list argument of a site is a list name, and in `F` no
    region pointer of `C` is returned (R1), stored into a heap object, a virtual object, a container
    (R2) or passed to a call, a method call included (R3), no heap pointer of `C` (a parameter, a call
    result, a field of a heap or virtual object) flows into a local of `C`, a region object's field of
    class `C` or an argument of a region site, and `is` never compares a region pointer of `C` with a
    heap one. The decision is a fixpoint: removing a class can make another one's store a heap store.
  - The scope of `(F, C)` is the innermost loop whose body holds every statement that uses `C`, in which
    every local of `C` is definitely assigned before it is read (no iteration reads the previous
    iteration's object), widened (R2 outlives) to the scope of any region object a `C` pointer is stored
    into; otherwise the function body.
  - `--explain-alloc`: `region (function scope)`, `region (per-iteration scope of the <for|while> loop,
    depth <k>)`, or for a heap-rc site `[region: <reason>]` after the virtual reason.

PR-O2c (tier O step 3, design 6.3.2 item 3 "Step 3 as implemented"; agent proposal, not owner-confirmed): a heap
site of a cyclic-capable class (`ClassAnalysis.cyclic_classes`) is `heap-gc`, the object tracked by the cycle
collector, reported with `[gc: <why the class may form a cycle>]` after the region reason. The virtual and
region decisions are unchanged for such a class: a virtual object is no object, and a cycle inside a region is
freed with the region (a region pointer never reaches the heap, R2).
"""
from __future__ import annotations

from dataclasses import dataclass, field

from typedpython import ir
from typedpython.aot.classes import EXPR_CHILD_ATTRS, EXPR_CHILD_SEQS, ClassAnalysis, _walk_stmts
from typedpython.aot.program import MAIN


@dataclass(frozen=True)
class AllocSite:
    """One `ir.New` allocation site and its chosen strategy + reason."""

    fn_name: str
    line: int
    target_name: str | None
    cls_name: str
    strategy: str       # "virtual", "region", "heap-rc" or "heap-gc" (PR-O2c: a heap site of a cyclic-capable class)
    reason: str
    region_reason: str | None = None     # a heap site: why `(fn, cls)` is not region
    virtual_reason: str | None = None    # a region site: why it is not virtual (the better tier)
    gc_reason: str | None = None         # a heap-gc site: why the class may form a cycle (it is tracked)


@dataclass(frozen=True)
class RegionInfo:
    """The region of one `(function, class)`: owned by the function body (`loop` None) or by one iteration
    of `loop` (a `ForRange` or `While` of the function, compared by identity), `depth` its nesting depth."""

    cls: str
    loop: ir.Stmt | None
    depth: int
    kind: str

    def describe(self) -> str:
        if self.loop is None:
            return "function scope"
        return f"per-iteration scope of the {self.kind} loop, depth {self.depth}"


@dataclass
class AllocPlan:
    """Whole-module allocation plan produced by `analyze_alloc`."""

    virtual_locals: dict[tuple[str, str], str] = field(default_factory=dict)
    init_maps: dict[str, tuple[tuple[str, int | ir.Const], ...]] = field(default_factory=dict)
    sites: list[AllocSite] = field(default_factory=list)
    regions: dict[tuple[str, str], RegionInfo] = field(default_factory=dict)
    region_reasons: dict[tuple[str, str], str] = field(default_factory=dict)

    def is_virtual(self, fn_name: str, local_name: str) -> bool:
        return (fn_name, local_name) in self.virtual_locals

    def is_region(self, fn_name: str, cls_name: str) -> bool:
        return (fn_name, cls_name) in self.regions

    def fn_regions(self, fn_name: str) -> dict[str, RegionInfo]:
        """The region classes of one function: class name -> its region."""
        return {c: info for (f, c), info in self.regions.items() if f == fn_name}

    def explain(self) -> str:
        """Human-readable report for `--explain-alloc`."""
        if not self.sites:
            return "no class allocation sites\n"
        lines: list[str] = []
        for s in self.sites:
            shown_fn = "__main__" if s.fn_name == MAIN else s.fn_name
            target = s.target_name if s.target_name is not None else "<temp>"
            text = f"{shown_fn}:{s.line} {target} = {s.cls_name}(...) -> {s.strategy} ({s.reason})"
            if s.region_reason is not None:
                text += f" [region: {s.region_reason}]"
            if s.gc_reason is not None:
                text += f" [gc: {s.gc_reason}]"
            if s.virtual_reason is not None:
                text += f" [not virtual: {s.virtual_reason}]"
            lines.append(text)
        return "\n".join(lines) + "\n"


def _simple_init_map(
    cls_name: str,
    init_fn: ir.Function | None,
) -> tuple[tuple[str, int | ir.Const], ...] | None:
    """If `cls.__init__` only assigns params or constants to `self.<field>`, return
    a tuple of `(field_name, param_index | ir.Const)` in statement order; else None."""
    if init_fn is None:
        return ()
    if not init_fn.params:
        return None
    self_name = init_fn.params[0].name
    param_idx = {p.name: i for i, p in enumerate(init_fn.params[1:])}
    mapping: list[tuple[str, int | ir.Const]] = []
    for s in init_fn.body:
        if isinstance(s, ir.ExprStmt) and isinstance(s.value, ir.Const) and isinstance(s.value.value, str):
            # Ignore a docstring in __init__
            continue
        if isinstance(s, ir.Return):
            if s.value is None or (isinstance(s.value, ir.Const) and s.value.type is ir.Type.NONE):
                continue
            return None
        if isinstance(s, ir.FieldSet):
            if (
                s.cls == cls_name
                and isinstance(s.obj, ir.Local)
                and s.obj.name == self_name
            ):
                if isinstance(s.value, ir.Local) and s.value.name in param_idx:
                    mapping.append((s.field, param_idx[s.value.name]))
                    continue
                if isinstance(s.value, ir.Const):
                    mapping.append((s.field, s.value))
                    continue
        return None
    return tuple(mapping)


def _walk_exprs_in_expr(expr: ir.Expr):
    """Yield every sub-expression of `expr` (including `expr` itself)."""
    yield expr
    for attr in EXPR_CHILD_ATTRS:
        sub = getattr(expr, attr, None)
        if isinstance(sub, ir.Expr):
            yield from _walk_exprs_in_expr(sub)
    for attr in EXPR_CHILD_SEQS:
        subs = getattr(expr, attr, None)
        if isinstance(subs, tuple):
            for s in subs:
                if isinstance(s, ir.Expr):
                    yield from _walk_exprs_in_expr(s)


def _expr_uses(expr: ir.Expr, name: str) -> bool:
    return any(isinstance(e, ir.Local) and e.name == name for e in _walk_exprs_in_expr(expr))


def _find_escape_reason(fn: ir.Function, var_name: str, cls_name: str) -> str | None:
    """Return the first reason `var_name` cannot be scalar-replaced in `fn`, or None if safe."""

    def _check_expr(expr: ir.Expr) -> str | None:
        if isinstance(expr, ir.Local):
            if expr.name == var_name:
                return "escapes via expression"
            return None
        if isinstance(expr, ir.FieldGet):
            if isinstance(expr.obj, ir.Local) and expr.obj.name == var_name:
                return None
            return _check_expr(expr.obj)
        if isinstance(expr, ir.Is):
            if _expr_uses(expr.left, var_name) or _expr_uses(expr.right, var_name):
                return "used in identity comparison (is)"
            return None
        if isinstance(expr, ir.IsExact):
            if _expr_uses(expr.obj, var_name):
                return "used in identity comparison (is)"
            return None
        if isinstance(expr, ir.Call):
            if (
                expr.args
                and isinstance(expr.args[0], ir.Local)
                and expr.args[0].name == var_name
                and expr.function.startswith(f"{cls_name}.")
            ):
                return f"non-inlined method call: {expr.function}"
            for a in expr.args:
                if _expr_uses(a, var_name):
                    # Check if it's only used inside a FieldGet (e.g. f(p.x))
                    sub = _check_expr(a)
                    if sub is not None:
                        if isinstance(a, ir.Local) and a.name == var_name:
                            return f"escapes via call to {expr.function}"
                        return sub
            return None
        if isinstance(expr, ir.New):
            for a in expr.args:
                if _expr_uses(a, var_name):
                    sub = _check_expr(a)
                    if sub is not None:
                        if isinstance(a, ir.Local) and a.name == var_name:
                            return f"escapes via call to {expr.cls}"
                        return sub
            return None
        if isinstance(expr, ir.ListDisplay):
            for a in expr.elements:
                if _expr_uses(a, var_name):
                    sub = _check_expr(a)
                    if sub is not None:
                        if isinstance(a, ir.Local) and a.name == var_name:
                            return "escapes into container"
                        return sub
            return None
        for attr in EXPR_CHILD_ATTRS:
            sub_e = getattr(expr, attr, None)
            if isinstance(sub_e, ir.Expr):
                res = _check_expr(sub_e)
                if res is not None:
                    return res
        for attr in EXPR_CHILD_SEQS:
            subs = getattr(expr, attr, None)
            if isinstance(subs, tuple):
                for s in subs:
                    if isinstance(s, ir.Expr):
                        res = _check_expr(s)
                        if res is not None:
                            return res
        return None

    for stmt in _walk_stmts(fn.body):
        if isinstance(stmt, ir.Assign):
            if isinstance(stmt.value, ir.Local) and stmt.value.name == var_name:
                return "escapes via alias assignment"
            res = _check_expr(stmt.value)
            if res is not None:
                return res
        elif isinstance(stmt, ir.FieldSet):
            # Target object: allowed if it is Local(var_name)
            if not (isinstance(stmt.obj, ir.Local) and stmt.obj.name == var_name):
                res = _check_expr(stmt.obj)
                if res is not None:
                    return res
            # Value stored into field: var_name itself cannot be stored
            if isinstance(stmt.value, ir.Local) and stmt.value.name == var_name:
                return "escapes into object field"
            res = _check_expr(stmt.value)
            if res is not None:
                return res
        elif isinstance(stmt, ir.StoreIndex):
            if isinstance(stmt.value, ir.Local) and stmt.value.name == var_name:
                return "escapes into container"
            for e in (stmt.index, stmt.value):
                res = _check_expr(e)
                if res is not None:
                    return res
        elif isinstance(stmt, ir.StoreItem):
            if isinstance(stmt.value, ir.Local) and stmt.value.name == var_name:
                return "escapes into container"
            for e in (stmt.obj, stmt.index, stmt.value):
                res = _check_expr(e)
                if res is not None:
                    return res
        elif isinstance(stmt, ir.Return):
            if stmt.value is not None:
                if isinstance(stmt.value, ir.Local) and stmt.value.name == var_name:
                    return "escapes via return"
                res = _check_expr(stmt.value)
                if res is not None:
                    return res
        elif isinstance(stmt, ir.ReturnTuple):
            for v in stmt.values:
                if isinstance(v, ir.Local) and v.name == var_name:
                    return "escapes via return"
                res = _check_expr(v)
                if res is not None:
                    return res
        elif isinstance(stmt, ir.ExprStmt):
            res = _check_expr(stmt.value)
            if res is not None:
                return res
        elif isinstance(stmt, ir.Print):
            for v in stmt.args:
                res = _check_expr(v)
                if res is not None:
                    return res
        elif isinstance(stmt, ir.If):
            res = _check_expr(stmt.cond)
            if res is not None:
                return res
        elif isinstance(stmt, ir.While):
            res = _check_expr(stmt.cond)
            if res is not None:
                return res
        elif isinstance(stmt, ir.ForRange):
            for e in (stmt.start, stmt.stop, stmt.step):
                if e is not None:
                    res = _check_expr(e)
                    if res is not None:
                        return res
        elif isinstance(stmt, ir.ForObj):
            res = _check_expr(stmt.iter)
            if res is not None:
                return res
        elif isinstance(stmt, (ir.ForStr, ir.ForObjList)):
            res = _check_expr(stmt.value)
            if res is not None:
                return res
        elif isinstance(stmt, ir.TimeSleep):
            res = _check_expr(stmt.seconds)
            if res is not None:
                return res
        elif isinstance(stmt, ir.SysExit):
            if stmt.code is not None:
                res = _check_expr(stmt.code)
                if res is not None:
                    return res
        elif isinstance(stmt, ir.Unpack):
            res = _check_expr(stmt.call)
            if res is not None:
                return res
        elif not isinstance(stmt, (ir.Break, ir.Continue, ir.ForRange)):
            # Any other statement (tier S2a: ListAppend, ListInsert, ListSetSlice, ...): the object itself among its
            # expressions is an escape (into a container, or somewhere this analysis does not follow); a statement
            # it does not know never lets a virtual object through unseen.
            for e in _stmt_exprs(stmt):
                if isinstance(e, ir.Local) and e.name == var_name:
                    return "escapes into container"
                res = _check_expr(e)
                if res is not None:
                    return res
    return None


def analyze_alloc(module: ir.Module, class_analysis: ClassAnalysis) -> AllocPlan:
    """Analyze every `ir.New` site in `module` and return an `AllocPlan`."""
    plan = AllocPlan()
    if not class_analysis.classes:
        return plan

    funcs_by_name = {f.name: f for f in module.functions}

    # Precompute simple __init__ maps for every class
    for cname in class_analysis.classes:
        if f"{cname}.__del__" in funcs_by_name:
            continue
        init_fn = funcs_by_name.get(f"{cname}.__init__")
        imap = _simple_init_map(cname, init_fn)
        if imap is not None:
            plan.init_maps[cname] = imap

    for fn in module.functions:
        param_names = {p.name for p in fn.params}
        all_stmts = list(_walk_stmts(fn.body))

        # Count assignments to every local name in `fn`
        assign_counts: dict[str, int] = {}
        for s in all_stmts:
            if isinstance(s, ir.Assign):
                assign_counts[s.target] = assign_counts.get(s.target, 0) + 1
            elif isinstance(s, ir.Unpack):
                for t in s.targets:
                    assign_counts[t] = assign_counts.get(t, 0) + 1
            elif isinstance(s, ir.ForRange):
                assign_counts[s.var] = assign_counts.get(s.var, 0) + 1
            elif isinstance(s, ir.ForObj):
                for t in s.targets:
                    assign_counts[t] = assign_counts.get(t, 0) + 1
            elif isinstance(s, (ir.ForStr, ir.ForObjList)):
                assign_counts[s.var] = assign_counts.get(s.var, 0) + 1

        for s in all_stmts:
            line = fn.source_line
            if isinstance(s, ir.Assign) and isinstance(s.value, ir.New):
                v = s.target
                cls_name = s.value.cls
                # Check if `s.value.args` itself contains nested temporary `ir.New`
                for arg in s.value.args:
                    for sub in _walk_exprs_in_expr(arg):
                        if isinstance(sub, ir.New):
                            plan.sites.append(AllocSite(
                                fn.name, line, None, sub.cls, "heap-rc",
                                "temporary (not bound to a single local)",
                            ))
                # Evaluate promotion criteria for `v = ir.New(cls_name, args)`
                reason: str | None = None
                if v in param_names:
                    reason = "reassigned or branch-merged"
                elif v not in fn.local_classes:
                    reason = "not a typed class local"
                elif assign_counts.get(v, 0) != 1:
                    reason = "reassigned or branch-merged"
                elif cls_name not in class_analysis.classes:
                    reason = f"unknown class: {cls_name}"
                elif f"{cls_name}.__del__" in funcs_by_name:
                    reason = "class defines __del__"
                elif _has_list_field(class_analysis, cls_name):
                    reason = "class holds a list (tier S2a: its fields stay counted references of a heap object)"
                elif cls_name not in plan.init_maps:
                    reason = "complex __init__"
                else:
                    init_fn = funcs_by_name.get(f"{cls_name}.__init__")
                    expected_argc = (len(init_fn.params) - 1) if init_fn is not None else 0
                    if len(s.value.args) != expected_argc:
                        reason = "complex __init__"
                    elif init_fn is not None and any(
                        isinstance(p, ir.ArrayParam) and not isinstance(a, ir.Local)
                        for p, a in zip(init_fn.params[1:], s.value.args)
                    ):
                        # Keep heap-rc so ev_New enforces the exact same aot/type check
                        reason = "non-local list argument"
                    else:
                        reason = _find_escape_reason(fn, v, cls_name)

                if reason is None:
                    plan.virtual_locals[(fn.name, v)] = cls_name
                    plan.sites.append(AllocSite(
                        fn.name, line, v, cls_name, "virtual",
                        "scalar-replaced (no escape)",
                    ))
                else:
                    plan.sites.append(AllocSite(
                        fn.name, line, v, cls_name, "heap-rc", reason,
                    ))
            else:
                # Any other statement: collect any temporary `ir.New` expressions
                exprs: list[ir.Expr] = []
                for attr in ("value", "obj", "index", "cond", "start", "stop", "step", "iter", "seconds", "code", "call"):
                    e = getattr(s, attr, None)
                    if isinstance(e, ir.Expr):
                        exprs.append(e)
                for attr in ("values", "args"):
                    es = getattr(s, attr, None)
                    if isinstance(es, tuple):
                        exprs.extend(e for e in es if isinstance(e, ir.Expr))
                for e in exprs:
                    for sub in _walk_exprs_in_expr(e):
                        if isinstance(sub, ir.New):
                            plan.sites.append(AllocSite(
                                fn.name, line, None, sub.cls, "heap-rc",
                                "temporary (not bound to a single local)",
                            ))

    # PR-O2b: the region decision of every (function, class) with a site that is not virtual.
    for fn in module.functions:
        _RegionAnalysis(fn, plan, class_analysis, funcs_by_name).run()
    rewritten: list[AllocSite] = []
    for s in plan.sites:
        if s.strategy != "heap-rc":
            rewritten.append(s)
            continue
        key = (s.fn_name, s.cls_name)
        if key in plan.regions:
            rewritten.append(AllocSite(s.fn_name, s.line, s.target_name, s.cls_name, "region",
                                       plan.regions[key].describe(), virtual_reason=s.reason))
        elif class_analysis.is_cyclic(s.cls_name):
            # PR-O2c: a heap object of a cyclic-capable class is tracked by the cycle collector
            rewritten.append(AllocSite(s.fn_name, s.line, s.target_name, s.cls_name, "heap-gc", s.reason,
                                       plan.region_reasons.get(key, "no region analysis"),
                                       gc_reason=class_analysis.cycle_reasons.get(s.cls_name, "may form a cycle")))
        else:
            rewritten.append(AllocSite(s.fn_name, s.line, s.target_name, s.cls_name, "heap-rc", s.reason,
                                       plan.region_reasons.get(key, "no region analysis")))
    plan.sites = rewritten
    return plan


# --- PR-O2b: scope regions -------------------------------------------------------------------------

_EXPR_ATTRS = ("value", "obj", "index", "cond", "start", "stop", "step", "iter", "seconds", "code", "call")
_EXPRS_ATTRS = ("values", "args")


def _has_list_field(ca: ClassAnalysis, cls: str) -> bool:
    """Tier S2a: `cls` has a list[C] field, or (tier S2b) a dict field: such an object is always a heap object, not
    virtual, not region (the container changes without a field store)."""
    decl = ca.classes.get(cls)
    return decl is not None and any(fd.type in (ir.Type.OBJ_LIST, ir.Type.DICT) for fd in decl.field_decls)


def _stmt_exprs(s: ir.Stmt) -> list[ir.Expr]:
    """The expressions a statement itself evaluates (not those of the statements nested in it)."""
    out: list[ir.Expr] = []
    for attr in _EXPR_ATTRS:
        e = getattr(s, attr, None)
        if isinstance(e, ir.Expr):
            out.append(e)
    for attr in _EXPRS_ATTRS:
        es = getattr(s, attr, None)
        if isinstance(es, tuple):
            out.extend(e for e in es if isinstance(e, ir.Expr))
    return out


def _is_loop(s: ir.Stmt) -> bool:
    return isinstance(s, (ir.ForRange, ir.While, ir.ForObj, ir.ForStr, ir.ForObjList))


def _walk_scoped(stmts: tuple[ir.Stmt, ...], stack: tuple[ir.Stmt, ...] = ()):
    """Every statement with the loops it is nested in (outermost first); a loop's own expressions belong
    to the enclosing scope, its body to the loop."""
    for s in stmts:
        yield s, stack
        if isinstance(s, ir.If):
            yield from _walk_scoped(s.then, stack)
            yield from _walk_scoped(s.orelse, stack)
        elif _is_loop(s):
            yield from _walk_scoped(s.body, stack + (s,))


def _is_none(e: ir.Expr) -> bool:
    return isinstance(e, ir.Const) and e.value is None


class _RegionAnalysis:
    """The region decision of one function (module docstring, PR-O2b)."""

    def __init__(self, fn: ir.Function, plan: AllocPlan, class_analysis: ClassAnalysis,
                 funcs_by_name: dict[str, ir.Function]) -> None:
        self.fn = fn
        self.plan = plan
        self.ca = class_analysis
        self.funcs = funcs_by_name
        self.virtual = {name for (f, name) in plan.virtual_locals if f == fn.name}
        self.local_cls = {name: cls for name, (cls, _) in fn.local_classes.items()}
        self.param_cls = {p.name: p.cls for p in fn.params if isinstance(p, ir.Param) and p.cls is not None}
        self.region: set[str] = set()
        self.stmts = list(_walk_stmts(fn.body))

    # -- what an expression is

    def cls_of(self, e: ir.Expr) -> str | None:
        """The class of an object-valued expression (None for a scalar, a list or `None`)."""
        if isinstance(e, ir.Local):
            return self.local_cls.get(e.name) or self.param_cls.get(e.name)
        if isinstance(e, ir.FieldGet):
            fd = self.ca.field_map.get((e.cls, e.field))
            return fd.cls if fd is not None else None
        if isinstance(e, ir.New):
            return e.cls
        if isinstance(e, ir.Call):
            f = self.funcs.get(e.function)
            return f.return_cls if f is not None else None
        return None

    def region_valued(self, e: ir.Expr) -> str | None:
        """The class of `e` when `e` is a pointer into a region of this function (under the current
        `self.region`): a region local, a `C(...)` site, a region-class field read from a region object."""
        if isinstance(e, ir.Local):
            if e.name in self.virtual:
                return None
            c = self.local_cls.get(e.name)
            return c if c is not None and c in self.region else None
        if isinstance(e, ir.FieldGet):
            if self.region_valued(e.obj) is None:
                return None
            k = self.cls_of(e)
            return k if k is not None and k in self.region else None
        if isinstance(e, ir.New):
            return e.cls if e.cls in self.region else None
        return None

    def heap_valued(self, e: ir.Expr, cls: str) -> bool:
        """`e` is a heap pointer of class `cls` (a parameter, a call result, a field of a heap or virtual
        object, a local that is not region-typed)."""
        return self.cls_of(e) == cls and self.region_valued(e) is None and not _is_none(e)

    def heap_source(self, e: ir.Expr) -> str:
        if isinstance(e, ir.Local):
            if e.name in self.param_cls:
                return f"parameter {e.name}"
            return f"local {e.name}"
        if isinstance(e, ir.Call):
            return f"returned by {e.function}"
        if isinstance(e, ir.FieldGet):
            where = "a virtual object" if isinstance(e.obj, ir.Local) and e.obj.name in self.virtual else "a heap object"
            return f"field {e.cls}.{e.field} of {where}"
        return "expression"

    # -- the candidates

    def candidate_reason(self, cls: str) -> str | None:
        """Why `cls` cannot be a region class of this function at all, before the escape rules."""
        if cls not in self.ca.classes:
            return f"unknown class: {cls}"
        if f"{cls}.__del__" in self.funcs:
            return "class defines __del__"
        if _has_list_field(self.ca, cls):
            return "class holds a list (tier S2a)"
        if cls not in self.plan.init_maps:
            return "complex __init__"
        if not self.ca.classes[cls].field_decls:
            # a zero-size region object: every `tpa_region_alloc` would return one address and `is` could
            # not tell two objects apart (and MSVC rejects an empty struct); heap-rc is correct
            return "class has no fields"
        init_fn = self.funcs.get(f"{cls}.__init__")
        expected_argc = (len(init_fn.params) - 1) if init_fn is not None else 0
        for s in self.stmts:
            for e in _stmt_exprs(s):
                for sub in _walk_exprs_in_expr(e):
                    if not (isinstance(sub, ir.New) and sub.cls == cls):
                        continue
                    if len(sub.args) != expected_argc:
                        return "complex __init__"
                    if init_fn is not None and any(
                        isinstance(p, ir.ArrayParam) and not isinstance(a, ir.Local)
                        for p, a in zip(init_fn.params[1:], sub.args)
                    ):
                        return "non-local list argument"
        return None

    # -- the escape rules

    def visit(self, e: ir.Expr, cls: str, allowed: bool, ctx: str) -> str | None:
        """The first rule a region pointer of `cls` inside `e` breaks, or None. `allowed`: a region
        pointer of `cls` may stand at this position; `ctx` names the rule it breaks otherwise."""
        if self.region_valued(e) == cls and not allowed:
            return ctx or "escapes via expression"
        if isinstance(e, ir.New):
            x_region = e.cls in self.region
            if x_region:
                for a in e.args:
                    if self.heap_valued(a, cls):
                        return f"heap {cls} flows in (argument of {e.cls}(...))"
            for a in e.args:
                r = self.visit(a, cls, x_region, f"R2: escapes into a heap object ({e.cls} constructor)")
                if r is not None:
                    return r
            return None
        if isinstance(e, ir.Call):
            for i, a in enumerate(e.args):
                if i == 0 and e.function.startswith(f"{cls}.") and self.region_valued(a) == cls:
                    return f"R3: non-inlined method call: {e.function}"
                r = self.visit(a, cls, False, f"R3: escapes via call to {e.function}")
                if r is not None:
                    return r
            return None
        if isinstance(e, ir.CallObject):
            for a in (e.callee,) + e.args:
                r = self.visit(a, cls, False, "R3: escapes via an object call")
                if r is not None:
                    return r
            return None
        if isinstance(e, ir.FieldGet):
            return self.visit(e.obj, cls, True, "")
        if isinstance(e, ir.Is):
            for a, b in ((e.left, e.right), (e.right, e.left)):
                if self.region_valued(a) == cls and self.heap_valued(b, cls):
                    return "identity comparison with a heap object"
            r = self.visit(e.left, cls, True, "")
            if r is not None:
                return r
            return self.visit(e.right, cls, True, "")
        if isinstance(e, ir.ListDisplay):
            for a in e.elements:
                r = self.visit(a, cls, False, "R2: escapes into a container")
                if r is not None:
                    return r
            return None
        for attr in EXPR_CHILD_ATTRS:
            sub = getattr(e, attr, None)
            if isinstance(sub, ir.Expr):
                r = self.visit(sub, cls, False, "escapes via expression")
                if r is not None:
                    return r
        for attr in EXPR_CHILD_SEQS:
            subs = getattr(e, attr, None)
            if isinstance(subs, tuple):
                for sub in subs:
                    if isinstance(sub, ir.Expr):
                        r = self.visit(sub, cls, False, "escapes via expression")
                        if r is not None:
                            return r
        return None

    def check_stmt(self, s: ir.Stmt, cls: str) -> str | None:
        if isinstance(s, ir.Assign):
            t, v = s.target, s.value
            if t in self.local_cls and self.local_cls[t] == cls and t not in self.virtual:
                if _is_none(v):
                    return None
                if self.region_valued(v) == cls:
                    return self.visit(v, cls, True, "")
                if self.cls_of(v) == cls:
                    return f"heap {cls} flows in ({self.heap_source(v)})"
                return "escapes via expression"
            if t in self.virtual and isinstance(v, ir.New):
                for a in v.args:
                    r = self.visit(a, cls, False, "R2: escapes into a virtual object")
                    if r is not None:
                        return r
                return None
            if t in self.param_cls and self.param_cls[t] == cls and self.region_valued(v) == cls:
                return f"assigned to the parameter {t} (a heap object)"
            return self.visit(v, cls, False, "escapes via expression")
        if isinstance(s, ir.FieldSet):
            fd = self.ca.field_map.get((s.cls, s.field))
            k = fd.cls if fd is not None else None
            if self.region_valued(s.obj) is not None:
                if k == cls and self.heap_valued(s.value, cls):
                    return f"heap {cls} flows in (stored into {s.cls}.{s.field} of a region object)"
                return self.visit(s.value, cls, True, "")
            where = "a virtual object" if isinstance(s.obj, ir.Local) and s.obj.name in self.virtual else "a heap object"
            r = self.visit(s.obj, cls, True, "")
            if r is not None:
                return r
            return self.visit(s.value, cls, False, f"R2: escapes into {where} field {s.cls}.{s.field}")
        if isinstance(s, ir.Return):
            return self.visit(s.value, cls, False, "R1: escapes via return") if s.value is not None else None
        if isinstance(s, ir.ReturnTuple):
            for v in s.values:
                r = self.visit(v, cls, False, "R1: escapes via return")
                if r is not None:
                    return r
            return None
        if isinstance(s, (ir.StoreIndex, ir.StoreItem)):
            for e in _stmt_exprs(s):
                r = self.visit(e, cls, False, "R2: escapes into a container")
                if r is not None:
                    return r
            return None
        for e in _stmt_exprs(s):
            r = self.visit(e, cls, False, "escapes via expression")
            if r is not None:
                return r
        return None

    def violation(self, cls: str) -> str | None:
        for s in self.stmts:
            r = self.check_stmt(s, cls)
            if r is not None:
                return r
        return None

    # -- the scope

    def mentions(self, s: ir.Stmt, cls: str) -> bool:
        """`s` itself uses a region pointer of `cls` (a region local, a site, a region field read, or it
        assigns a region local of `cls`)."""
        if isinstance(s, ir.Assign) and s.target in self.local_cls and self.local_cls[s.target] == cls \
                and s.target not in self.virtual:
            return True
        for e in _stmt_exprs(s):
            for sub in _walk_exprs_in_expr(e):
                if self.region_valued(sub) == cls:
                    return True
        return False

    def reads(self, s: ir.Stmt, names: set[str]) -> set[str]:
        out: set[str] = set()
        for e in _stmt_exprs(s):
            for sub in _walk_exprs_in_expr(e):
                if isinstance(sub, ir.Local) and sub.name in names:
                    out.add(sub.name)
        return out

    def definitely_assigned(self, loop: ir.Stmt, cls: str) -> bool:
        """Inside one iteration of `loop`, every read of a region local of `cls` follows an assignment of
        that local on every path from the top of the body (so no iteration sees the previous one's
        object, which the reset at the top of the iteration freed)."""
        names = {n for n, c in self.local_cls.items() if c == cls and n not in self.virtual}

        def walk(stmts: tuple[ir.Stmt, ...], defd: set[str]) -> set[str] | None:
            for s in stmts:
                if self.reads(s, names) - defd:
                    return None
                if isinstance(s, ir.Assign):
                    if s.target in names:
                        defd = defd | {s.target}
                elif isinstance(s, ir.If):
                    a = walk(s.then, set(defd))
                    b = walk(s.orelse, set(defd))
                    if a is None or b is None:
                        return None
                    defd = a & b
                elif _is_loop(s):
                    if walk(s.body, set(defd)) is None:          # the body may run no time: nothing becomes assigned
                        return None
            return defd

        return walk(loop.body, set()) is not None

    def region_stores(self) -> list[tuple[str, str]]:
        """(D, X) for every store of a region pointer of D into a region object of X: a field store, or an
        argument of a site of X."""
        out: list[tuple[str, str]] = []
        for s in self.stmts:
            if isinstance(s, ir.FieldSet) and self.region_valued(s.obj) is not None:
                d = self.region_valued(s.value)
                if d is not None:
                    out.append((d, s.cls))
            for e in _stmt_exprs(s):
                for sub in _walk_exprs_in_expr(e):
                    if isinstance(sub, ir.New) and sub.cls in self.region:
                        for a in sub.args:
                            d = self.region_valued(a)
                            if d is not None:
                                out.append((d, sub.cls))
        return out

    def choose_scopes(self) -> None:
        chains: dict[str, list[ir.Stmt] | None] = {c: None for c in self.region}
        for s, stack in _walk_scoped(self.fn.body):
            for c in self.region:
                if not self.mentions(s, c):
                    continue
                chain = chains[c]
                if chain is None:
                    chains[c] = list(stack)
                else:
                    n = 0
                    while n < len(chain) and n < len(stack) and chain[n] is stack[n]:
                        n += 1
                    del chain[n:]
        choice: dict[str, int] = {}
        for c in self.region:
            chain = chains[c] or []
            k = len(chain)
            while k > 0 and not self.definitely_assigned(chain[k - 1], c):
                k -= 1
            choice[c] = k
        stores = self.region_stores()
        changed = True
        while changed:
            changed = False
            for d, x in stores:
                if choice[d] > choice[x]:
                    k = choice[x]
                    chain = chains[d] or []
                    while k > 0 and not self.definitely_assigned(chain[k - 1], d):
                        k -= 1
                    choice[d] = k
                    changed = True
        for c in self.region:
            chain = chains[c] or []
            k = choice[c]
            loop = chain[k - 1] if k > 0 else None
            kind = "while" if isinstance(loop, ir.While) else "for"
            self.plan.regions[(self.fn.name, c)] = RegionInfo(c, loop, k, kind)

    # -- the decision

    def run(self) -> None:
        sites: set[str] = set()
        for s in self.stmts:
            if isinstance(s, ir.Assign) and isinstance(s.value, ir.New) and s.target in self.virtual:
                for a in s.value.args:                       # the arguments of a virtual site may hold sites
                    sites.update(sub.cls for sub in _walk_exprs_in_expr(a) if isinstance(sub, ir.New))
                continue
            for e in _stmt_exprs(s):
                sites.update(sub.cls for sub in _walk_exprs_in_expr(e) if isinstance(sub, ir.New))
        for c in sorted(sites):
            why = self.candidate_reason(c)
            if why is not None:
                self.plan.region_reasons[(self.fn.name, c)] = why
            else:
                self.region.add(c)
        while True:
            removed = None
            for c in sorted(self.region):
                why = self.violation(c)
                if why is not None:
                    removed = (c, why)
                    break
            if removed is None:
                break
            self.region.discard(removed[0])
            self.plan.region_reasons[(self.fn.name, removed[0])] = removed[1]
        if self.region:
            self.choose_scopes()
