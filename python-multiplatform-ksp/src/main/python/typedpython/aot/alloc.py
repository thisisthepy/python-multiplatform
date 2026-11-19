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
         or `ir.FieldSet(Local(v), ..., val)` (with `v` not referenced in `val`).
  - Otherwise falls back to `"heap-rc"` and records the exact escape/fallback reason
    for `--explain-alloc`.
"""
from __future__ import annotations

from dataclasses import dataclass, field

from typedpython import ir
from typedpython.aot.classes import ClassAnalysis, _walk_stmts
from typedpython.aot.program import MAIN


@dataclass(frozen=True)
class AllocSite:
    """One `ir.New` allocation site and its chosen strategy + reason."""

    fn_name: str
    line: int
    target_name: str | None
    cls_name: str
    strategy: str       # "virtual" or "heap-rc"
    reason: str


@dataclass
class AllocPlan:
    """Whole-module allocation plan produced by `analyze_alloc`."""

    virtual_locals: dict[tuple[str, str], str] = field(default_factory=dict)
    init_maps: dict[str, tuple[tuple[str, int | ir.Const], ...]] = field(default_factory=dict)
    sites: list[AllocSite] = field(default_factory=list)

    def is_virtual(self, fn_name: str, local_name: str) -> bool:
        return (fn_name, local_name) in self.virtual_locals

    def explain(self) -> str:
        """Human-readable report for `--explain-alloc`."""
        if not self.sites:
            return "no class allocation sites\n"
        lines: list[str] = []
        for s in self.sites:
            shown_fn = "__main__" if s.fn_name == MAIN else s.fn_name
            target = s.target_name if s.target_name is not None else "<temp>"
            lines.append(
                f"{shown_fn}:{s.line} {target} = {s.cls_name}(...) -> {s.strategy} ({s.reason})"
            )
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
    for attr in ("left", "right", "operand", "value", "obj", "index", "cond", "then", "orelse"):
        sub = getattr(expr, attr, None)
        if isinstance(sub, ir.Expr):
            yield from _walk_exprs_in_expr(sub)
    for attr in ("args", "elements", "parts"):
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
        if isinstance(expr, (ir.Is, ir.IsExact)):
            if _expr_uses(expr.left, var_name) or _expr_uses(expr.right, var_name):
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
        for attr in ("left", "right", "operand", "value", "obj", "index", "cond", "then", "orelse"):
            sub_e = getattr(expr, attr, None)
            if isinstance(sub_e, ir.Expr):
                res = _check_expr(sub_e)
                if res is not None:
                    return res
        for attr in ("args", "elements", "parts"):
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
            for v in stmt.values:
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
            res = _check_expr(stmt.iterable)
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
            res = _check_expr(stmt.value)
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
                t = getattr(s, "target", None) or getattr(s, "var", None)
                if isinstance(t, str):
                    assign_counts[t] = assign_counts.get(t, 0) + 1

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
                if v in param_names or v not in fn.local_classes:
                    reason = "reassigned or branch-merged"
                elif assign_counts.get(v, 0) != 1:
                    reason = "reassigned or branch-merged"
                elif cls_name not in class_analysis.classes:
                    reason = f"unknown class: {cls_name}"
                elif f"{cls_name}.__del__" in funcs_by_name:
                    reason = "class defines __del__"
                elif cls_name not in plan.init_maps:
                    reason = "complex __init__"
                else:
                    init_fn = funcs_by_name.get(f"{cls_name}.__init__")
                    expected_argc = (len(init_fn.params) - 1) if init_fn is not None else 0
                    if len(s.value.args) != expected_argc:
                        reason = "complex __init__"
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
                for attr in ("value", "obj", "index", "cond", "start", "stop", "step", "iterable", "seconds", "code"):
                    e = getattr(s, attr, None)
                    if isinstance(e, ir.Expr):
                        exprs.append(e)
                for attr in ("values",):
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

    return plan
