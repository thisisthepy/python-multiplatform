"""OSR-style exit continuations (SPEC N-8, #152 option (i)).

A function that holds a snapshot (an array copy-in or entry globals) polls at the top of a loop
iteration. When the re-validation fails it must leave for the interpreter at that point. For every
loop of such a function this module generates a Python function, the continuation, built from the
function's own AST: it runs "the rest of the function from the top of that iteration".

Contract (the C side builds the call from `ir.Resume.params`):

* Site numbering: loops of `Function.body` in IR pre-order (see `ir.Resume`). The AST loops are
  numbered the same way; if the counts or kinds disagree, no resume is generated at all.
* Parameters, in this order:
  1. every source-level local and parameter that may be bound at the loop, sorted by name. "Source
     level" means a name the function's AST binds (parameter, assignment, augmented assignment,
     loop target, `with ... as`, `except ... as`, import, def/class name, walrus, `del` target).
     Compiler temporaries never appear in the AST, so they are excluded by construction. Left out:
     names declared `global`, the site's own loop targets and every enclosing ForRange variable (all
     of those are set by the loop header from the hidden state), and a local with no binding
     statement before the loop, inside it or inside an enclosing loop (it cannot be bound yet; the
     continuation still treats it as a local, so reading it raises UnboundLocalError).
  2. the hidden loop state, outermost loop first and the site last: for a ForRange `k`:
     `__tp_cur_<k>`, `__tp_stop_<k>`, `__tp_step_<k>` (the loop variable's value for the iteration
     about to run and the already evaluated bounds); for an enclosing ForObj `k`: `__tp_it_<k>`
     (the live iterator; its targets are ordinary locals); for the site if it is a ForObj:
     `__tp_it_<k>`, `__tp_cur_<k>` (the item just taken from the iterator, before unpacking, which
     the continuation processes first); a While has none. `<k>` is that loop's site number.
* A local that is unbound at the call is passed as the module-level singleton `__tp_unbound`
  (defined by `resume_sources`); the continuation first `del`s it, so a later read raises
  UnboundLocalError exactly as CPython would.
* Returns what the rest of the function returns (falling off the end gives None) or raises.

Statement continuations (#390 item 5, SPEC N-8 "statement exit"; agent proposal, not confirmed). A
function with an effect that holds list copies (`needs_stmt_exits`) must not run interpreted code
while the copies are live, so a C to C Call whose callee reports a deopt leaves for the interpreter
at the start of the statement holding it. For each such statement S (`exit_stmts`: the source
statements whose IR holds a Call, numbered as `statements` numbers them) this module generates a
continuation `__tp_resume_<function>_s<k>` (`ir.Resume.stmt` = k, `site` -1) that re-executes S from
its start, then the statements after S in its block, then for each enclosing loop (innermost first)
the rest of that loop's current iteration and its remaining iterations, then the statements after
the outermost loop: the loop construction above with S in place of the site loop. Parameters follow
the contract above, with S's enclosing loops only: S itself has no hidden state (a `for` statement
re-evaluates its `range` bounds and its variable is an ordinary local; a `while` re-evaluates its
test, which makes the continuation of a `while` statement the same as its loop continuation). The
statement must be reached through plain `if` / `for` / `while` statements (no loop `else`, not inside
try / with / match), as a loop site.

`osr_problem`, `mappable` and `exit_problem` are the one definition of "this function has OSR-style
exits" and "this statement has a statement exit" that `cgen` and `verify` both use.
"""
from __future__ import annotations

import ast
import copy
from typing import Iterable

from typedpython import ir

PRELUDE = '''\
from itertools import chain as __tp_chain
__tp_unbound = object()
def __tp_resume_def(src, lmap):
    import ast, sys
    frame = sys._getframe(1)
    tree = ast.parse(src)
    last = len(lmap) - 1
    for n in ast.walk(tree):
        if hasattr(n, "lineno"):
            n.lineno, n.end_lineno, n.col_offset, n.end_col_offset = lmap[min(n.lineno - 1, last)]
    exec(compile(tree, frame.f_code.co_filename, "exec"), frame.f_globals)
'''


def has_snapshot(f: ir.Function) -> bool:
    """Same rule as `cgen._FnGen.snapshot` (cgen.py, property `snapshot`, ~line 2186): any
    entry global, or any ArrayParam (buffer or not; the property does not look at `buffer`), except
    the lists of a function that reads them element by element (`ir.direct_lists`, #255)."""
    if ir.direct_lists(f):
        return bool(f.entry_globals) or any(isinstance(p, ir.ArrayParam) and p.buffer is not None
                                            for p in f.params)
    return bool(f.entry_globals) or any(isinstance(p, ir.ArrayParam) for p in f.params)


def resumes_for(fn: ast.FunctionDef, f: ir.Function) -> tuple[ir.Resume, ...]:
    """The resumes of `f`, lowered from `fn`; () when `f` holds no snapshot. Loop continuations
    first; then, when `f` needs statement exits and its statement positions agree with `fn`
    (`_attribution_ok`), one statement continuation per statement in `exit_stmts(f)`."""
    if not has_snapshot(f):
        return ()
    kinds = [_kind(s) for s in ir_loops(f.body)]
    stmts: tuple[int, ...] = ()
    if needs_stmt_exits(f) and _attribution_ok(fn, f):
        stmts = tuple(sorted(k for k in exit_stmts(f) if isinstance(k, int)))
    return build(fn, kinds, f.name, stmts)


# --- statement exits (#390 item 5): the predicates cgen and verify share ----------------------------

_HIDDEN = ("__tp_cur_", "__tp_stop_", "__tp_step_")


def holds_lists(f: ir.Function) -> bool:
    """The wrapper copies list parameters in and holds their critical section (cgen `_holds_lists`)."""
    return not ir.direct_lists(f) and any(isinstance(p, ir.ArrayParam) and p.buffer is None
                                          for p in f.params)


def needs_stmt_exits(f: ir.Function) -> bool:
    """Every Call of `f` leaves through a statement exit on the callee's deopt: `f` has an effect
    (a pure function propagates the deopt to its wrapper, which redoes the call after the write-back)
    and holds list copies."""
    return f.pure is not True and holds_lists(f)


def own_exprs(s: ir.Stmt):
    """The expressions an IR statement evaluates itself (not those of the statements in its body),
    every node of each, in field order."""
    stack = []
    for fl in s.__dataclass_fields__.values():
        v = getattr(s, fl.name)
        for x in (v if isinstance(v, tuple) else (v,)):
            if isinstance(x, ir.Expr):
                stack.append(x)
    out = []
    while stack:
        e = stack.pop(0)
        out.append(e)
        for fl in e.__dataclass_fields__.values():
            v = getattr(e, fl.name)
            for x in (v if isinstance(v, tuple) else (v,)):
                if isinstance(x, ir.Expr):
                    stack.append(x)
    return out


def ir_stmts(stmts: Iterable[ir.Stmt]):
    """Every IR statement in pre-order (a statement before the statements in its body)."""
    for s in stmts:
        yield s
        for fl in s.__dataclass_fields__.values():
            v = getattr(s, fl.name)
            if isinstance(v, tuple) and v and all(isinstance(x, ir.Stmt) for x in v):
                yield from ir_stmts(v)


def exit_stmts(f: ir.Function) -> set:
    """The `stmt` of every IR statement that evaluates a Call itself (None included when one has no
    source position)."""
    return {s.stmt for s in ir_stmts(f.body) if any(isinstance(e, ir.Call) for e in own_exprs(s))}


def mappable(f: ir.Function, name: str) -> bool:
    """A continuation parameter the C side can pass (cgen `_osr_value`): the hidden loop state, an
    array parameter (its list or buffer object), a local f64 / i64 array (a new list), or a scalar,
    object or native-int local or parameter that is not an entry global."""
    if name.startswith(_HIDDEN):
        return True
    if any(isinstance(p, ir.ArrayParam) and p.name == name for p in f.params):
        return True
    params = {p.name: p.type for p in f.params if not isinstance(p, ir.ArrayParam)}
    t = params.get(name, f.locals.get(name))
    if name not in params and t in (ir.Type.F64_ARRAY, ir.Type.I64_ARRAY):
        return True
    return t in (ir.Type.I64, ir.Type.F64, ir.Type.BOOL, ir.Type.OBJ, ir.Type.INT) \
        and name not in {g.name for g in f.entry_globals}


def osr_problem(f: ir.Function) -> str | None:
    """Why `f` has no OSR-style exits (cgen `_osr_plan`), or None. Every loop needs its continuation
    with every parameter mappable; virtual objects, regions, a parallel ForRange or a ForObj loop
    rule exits out."""
    if not has_snapshot(f):
        return "it holds no snapshot"
    if not f.resumes:
        return "it has no continuation"
    if f.virtual:
        return "it has virtual objects"
    if getattr(f, "regions", ()):
        return "it builds objects in a region"
    loops = list(ir_loops(f.body))
    by_site = {r.site: r for r in f.resumes if r.stmt is None}
    if len(loops) != len(by_site) or any(k not in by_site for k in range(len(loops))):
        return "a loop has no continuation"
    for k, st in enumerate(loops):
        if isinstance(st, ir.ForRange) and st.par is not None:
            return "it has a parallel loop (a parallel region has no exit)"
        if not isinstance(st, (ir.ForRange, ir.While)):
            return "it has a loop over an object"
        for name in by_site[k].params:
            if not mappable(f, name):
                return f"the continuation of loop {k} takes {name}, which has no C value"
    return None


def exit_problem(f: ir.Function, k: object) -> str | None:
    """Why the statement numbered `k` of `f` has no statement exit, or None."""
    why = osr_problem(f)
    if why is not None:
        return f"the function has no OSR-style exit ({why})"
    if not isinstance(k, int) or isinstance(k, bool):
        return "the statement has no source position"
    r = next((r for r in f.resumes if r.stmt == k), None)
    if r is None:
        return f"statement {k} has no continuation"
    for name in r.params:
        if not mappable(f, name):
            return f"the continuation of statement {k} takes {name}, which has no C value"
    return None


# IR statement kind -> the source statements it may be lowered from (`_attribution_ok`). An Assign may
# be the temporary of any statement; a `while` whose test needs temporaries also gives an If and a
# Break (frontend `stmt`, the While case).
_LOWERED_FROM = {
    ir.Assign: (ast.stmt,),
    ir.StoreIndex: (ast.Assign, ast.AnnAssign, ast.AugAssign),
    ir.FieldSet: (ast.Assign, ast.AnnAssign, ast.AugAssign),
    ir.StoreItem: (ast.Assign, ast.AnnAssign, ast.AugAssign),
    ir.ExprStmt: (ast.Expr,),
    ir.Return: (ast.Return,),
    ir.If: (ast.If, ast.While),
    ir.While: (ast.While,),
    ir.ForRange: (ast.For,),
    ir.ForObj: (ast.For,),
    ir.Break: (ast.Break, ast.While),
    ir.Continue: (ast.Continue,),
}


def _attribution_ok(fn: ast.FunctionDef, f: ir.Function) -> bool:
    """The IR's statement positions are consistent with `fn`: each names a statement of `fn` of a
    kind it can be lowered from, and they never decrease in IR pre-order. Otherwise no statement
    continuation is generated (and the function, needing them, stays interpreted)."""
    stmts = statements(fn)
    last = -1
    for s in ir_stmts(f.body):
        k = s.stmt
        if k is None:
            continue
        if not isinstance(k, int) or isinstance(k, bool) or not 0 <= k < len(stmts) or k < last:
            return False
        if not isinstance(stmts[k], _LOWERED_FROM.get(type(s), ())):
            return False
        last = k
    return True


def ir_loops(stmts: Iterable[ir.Stmt]):
    """Loops in IR pre-order (the numbering of `ir.Resume.site`)."""
    for s in stmts:
        if isinstance(s, (ir.ForRange, ir.While, ir.ForObj)):
            yield s
        for fl in s.__dataclass_fields__.values():
            v = getattr(s, fl.name)
            if isinstance(v, tuple) and v and all(isinstance(x, ir.Stmt) for x in v):
                yield from ir_loops(v)


def _kind(s: ir.Stmt) -> str:
    return "range" if isinstance(s, ir.ForRange) else "obj" if isinstance(s, ir.ForObj) else "while"


# --- the AST side ---------------------------------------------------------------------------------

def statements(fn: ast.FunctionDef) -> list[ast.stmt]:
    """The function's own statements in pre-order (not those of a nested def or class): index =
    statement number (`ir.Stmt.stmt`, `ir.Resume.stmt`)."""
    return list(_own_stmts(fn.body))


def stmt_numbers(fn: ast.FunctionDef) -> dict[int, int]:
    """id(statement) -> its number."""
    return {id(st): i for i, st in enumerate(statements(fn))}

class _Site:
    def __init__(self, node: ast.stmt, number: int, chain: list, ok: bool) -> None:
        self.node, self.number, self.chain, self.ok = node, number, chain, ok


def _sub_lists(st: ast.stmt):
    for name in ("body", "orelse", "finalbody"):
        v = getattr(st, name, None)
        if isinstance(v, list) and v and isinstance(v[0], ast.stmt):
            yield name, v
    for h in getattr(st, "handlers", []):
        yield "handler", h.body
    for c in getattr(st, "cases", []):
        yield "case", c.body


def _collect(stmts: list, chain: list, ok: bool, out: list) -> None:
    for i, st in enumerate(stmts):
        if isinstance(st, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            continue
        here = [*chain, (stmts, i)]
        if isinstance(st, (ast.For, ast.While)) and not st.orelse:
            out.append(_Site(st, len(out), here, ok))
            _collect(st.body, here, ok, out)
            continue
        if isinstance(st, (ast.For, ast.While)):
            out.append(_Site(st, len(out), here, False))
        sub_ok = ok and isinstance(st, (ast.If, ast.For, ast.While)) and not (
            isinstance(st, (ast.For, ast.While)) and st.orelse)
        for _, lst in _sub_lists(st):
            _collect(lst, here, sub_ok, out)


def _collect_stmts(stmts: list, chain: list, ok: bool, out: dict) -> None:
    """id(statement) -> (statement, chain, ok) for every statement of this scope (`ok` as `_collect`:
    reached through plain if / for / while only)."""
    for i, st in enumerate(stmts):
        here = [*chain, (stmts, i)]
        out[id(st)] = (st, here, ok)
        if isinstance(st, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            continue
        sub_ok = ok and isinstance(st, (ast.If, ast.For, ast.While)) and not (
            isinstance(st, (ast.For, ast.While)) and st.orelse)
        for _, lst in _sub_lists(st):
            _collect_stmts(lst, here, sub_ok, out)


def _names(e: ast.AST, out: set[str]) -> None:
    """Names an expression (not a statement) binds in the enclosing function scope."""
    stack = [e]
    while stack:
        n = stack.pop()
        if isinstance(n, ast.Lambda):
            continue
        if isinstance(n, (ast.ListComp, ast.SetComp, ast.DictComp, ast.GeneratorExp)):
            for sub in ast.walk(n):
                if isinstance(sub, ast.NamedExpr) and isinstance(sub.target, ast.Name):
                    out.add(sub.target.id)
            continue
        if isinstance(n, ast.Name) and isinstance(n.ctx, (ast.Store, ast.Del)):
            out.add(n.id)
        stack.extend(ast.iter_child_nodes(n))


def _stmt_binds(st: ast.stmt) -> set[str]:
    out: set[str] = set()
    if isinstance(st, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
        out.add(st.name)
        return out
    if isinstance(st, (ast.Import, ast.ImportFrom)):
        for a in st.names:
            out.add((a.asname or a.name).split(".")[0])
        return out
    for child in ast.iter_child_nodes(st):
        if isinstance(child, (ast.stmt, ast.ExceptHandler, ast.match_case)):
            continue
        _names(child, out)
    return out


def _own_stmts(stmts: list):
    """Every statement of this scope in pre-order, with exception handler names as pseudo bindings."""
    for st in stmts:
        yield st
        if isinstance(st, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            continue
        for _, lst in _sub_lists(st):
            yield from _own_stmts(lst)


def _all_binders(fn: ast.FunctionDef) -> tuple[dict[str, list[ast.AST]], set[str], set[str]]:
    """name -> binding statements; (global names); (names bound by handlers/patterns: unsupported)."""
    binders: dict[str, list[ast.AST]] = {}
    globals_: set[str] = set()
    odd: set[str] = set()
    for st in _own_stmts(fn.body):
        if isinstance(st, ast.Global):
            globals_ |= set(st.names)
        if isinstance(st, ast.Nonlocal):
            odd |= set(st.names)
        for n in _stmt_binds(st):
            binders.setdefault(n, []).append(st)
        for h in getattr(st, "handlers", []):
            if h.name:
                binders.setdefault(h.name, []).append(st)
        for c in getattr(st, "cases", []):
            for sub in ast.walk(c.pattern):
                for attr in ("name", "rest"):
                    nm = getattr(sub, attr, None)
                    if isinstance(nm, str):
                        binders.setdefault(nm, []).append(st)
    return binders, globals_, odd


def _arg_names(fn: ast.FunctionDef) -> list[str]:
    a = fn.args
    names = [x.arg for x in [*a.posonlyargs, *a.args, *a.kwonlyargs]]
    names += [x.arg for x in (a.vararg, a.kwarg) if x is not None]
    return names


def _target_names(t: ast.AST) -> list[str]:
    return [n.id for n in ast.walk(t) if isinstance(n, ast.Name) and isinstance(n.ctx, ast.Store)]


def _skeleton(src: str, ref: ast.AST) -> ast.Module:
    tree = ast.parse(src)
    for n in ast.walk(tree):
        if "lineno" in n._attributes:
            n.lineno = n.end_lineno = ref.lineno
            n.col_offset = ref.col_offset
            n.end_col_offset = ref.col_offset + 1
    return tree


def _copy_all(stmts) -> list[ast.stmt]:
    return [copy.deepcopy(s) for s in stmts]


def build(fn: ast.FunctionDef, kinds: list[str], qname: str,
          stmts: Iterable[int] = ()) -> tuple[ir.Resume, ...]:
    """Continuations for every loop of `fn`, then for every statement numbered in `stmts` that can
    have one (statement continuations, module docstring). `kinds[i]` is "range", "obj" or "while"
    for loop `i` in pre-order (from the IR). Returns () when the AST's loops do not match `kinds`."""
    sites: list[_Site] = []
    _collect(fn.body, [], True, sites)
    if len(sites) != len(kinds):
        return ()
    for s, k in zip(sites, kinds):
        if isinstance(s.node, ast.While) != (k == "while"):
            return ()
        if k == "range" and not (isinstance(s.node, ast.For) and isinstance(s.node.target, ast.Name)):
            return ()
    binders, globals_, odd = _all_binders(fn)
    if odd:
        return ()
    out = []
    for s in sites:
        if not s.ok:
            continue
        r = _one(fn, qname, s, sites, kinds, binders, globals_)
        if r is not None:
            out.append(r)
    wanted = sorted(set(stmts))
    if wanted:
        numbered = statements(fn)
        where: dict = {}
        _collect_stmts(fn.body, [], True, where)
        for k in wanted:
            if not 0 <= k < len(numbered):
                continue
            node, chain, ok = where[id(numbered[k])]
            if ok:
                out.append(_one(fn, qname, _Site(node, k, chain, ok), sites, kinds, binders, globals_,
                                stmt=True))
    return tuple(out)


def _one(fn, qname, site, sites, kinds, binders, globals_, stmt=False):
    """The continuation of a loop site, or (`stmt`) of the statement `site.node` numbered
    `site.number`: then the statement is re-executed from its start and only its enclosing loops
    have hidden state."""
    n = len(site.chain)
    by_node = {id(s.node): s for s in sites}
    # loops on the path, outermost first, the site last (a statement site: its enclosing loops only)
    path = [lst[i] for lst, i in site.chain]
    loops = [by_node[id(x)] for x in (path[:-1] if stmt else path) if id(x) in by_node]
    ref = site.node

    # --- parameters
    excluded: set[str] = set(globals_)
    hidden: list[str] = []
    for lp in loops:
        k, kind = lp.number, kinds[lp.number]
        if kind == "range":
            excluded.add(lp.node.target.id)
            hidden += [f"__tp_cur_{k}", f"__tp_stop_{k}", f"__tp_step_{k}"]
        elif kind == "obj":
            if lp is site:
                excluded |= set(_target_names(lp.node.target))
                hidden += [f"__tp_it_{k}", f"__tp_cur_{k}"]
            else:
                hidden += [f"__tp_it_{k}"]
    in_loop_ids: set[int] = set()
    for lp in loops:
        in_loop_ids |= {id(x) for x in _own_stmts([lp.node])}
    if stmt and isinstance(site.node, ast.While):
        # a while statement starts again at every test: its body may have run (a `for` statement
        # starts once, before its body, so its own body cannot have bound anything yet)
        in_loop_ids |= {id(x) for x in _own_stmts([site.node])}
    order = {id(st): i for i, st in enumerate(_own_stmts(fn.body))}
    site_order = order[id(site.node)]
    args = set(_arg_names(fn))
    locals_ = []
    for name in sorted(set(binders) | args):
        if name in excluded:
            continue
        if name in args or any(order[id(b)] < site_order or id(b) in in_loop_ids
                               for b in binders.get(name, [])):
            locals_.append(name)
    params = tuple(locals_) + tuple(hidden)

    # --- body
    def rest(k: int) -> list[ast.stmt]:
        lst, i = site.chain[k]
        return [*enter(k), *_copy_all(lst[i + 1:])]

    def enter(k: int) -> list[ast.stmt]:
        lst, i = site.chain[k]
        node = lst[i]
        if k == n - 1:
            return site_construct(node)
        if isinstance(node, ast.If):
            return rest(k + 1)
        return enclosing(node, rest(k + 1))

    def site_construct(node) -> list[ast.stmt]:
        if stmt:
            return [copy.deepcopy(node)]            # the statement itself, from its start
        kind, num = kinds[site.number], site.number
        if kind == "while":
            return [copy.deepcopy(node)]
        if kind == "range":
            skel = _skeleton(f"for _ in range(__tp_cur_{num}, __tp_stop_{num}, __tp_step_{num}): pass",
                             node)
        else:
            skel = _skeleton(f"for _ in __tp_chain((__tp_cur_{num},), __tp_it_{num}): pass", node)
        loop = skel.body[0]
        loop.target = copy.deepcopy(node.target)
        loop.body = _copy_all(node.body)
        return [loop]

    def enclosing(node, r: list[ast.stmt]) -> list[ast.stmt]:
        num = by_node[id(node)].number
        kind = kinds[num]
        first = f"__tp_first_{num}"
        init = _skeleton(f"{first} = True", node).body[0]
        flip = _skeleton(f"{first} = False", node).body[0]
        body = _copy_all(node.body)
        if kind == "range":
            skel = _skeleton(f"for _ in range(__tp_cur_{num}, __tp_stop_{num}, __tp_step_{num}):\n"
                             f"    if {first}: pass\n    else: pass", node)
            loop = skel.body[0]
            loop.target = copy.deepcopy(node.target)
            loop.body[0].body = [flip, *r]
            loop.body[0].orelse = body
            return [init, loop]
        if kind == "while":
            skel = _skeleton(f"while True:\n    if {first}: pass\n    else: pass", node)
            loop = skel.body[0]
            loop.test = ast.BoolOp(ast.Or(), [_skeleton(first, node).body[0].value,
                                              copy.deepcopy(node.test)])
            ast.copy_location(loop.test, node)
            loop.body[0].body = [flip, *r]
            loop.body[0].orelse = body
            return [init, loop]
        item = f"__tp_item_{num}"
        skel = _skeleton(
            f"while True:\n    if {first}: pass\n    else:\n"
            f"        try:\n            {item} = next(__tp_it_{num})\n"
            f"        except StopIteration:\n            break\n"
            f"        _ = {item}\n        del {item}", node)
        loop = skel.body[0]
        els = loop.body[0].orelse
        assign = els[1]
        assign.targets = [copy.deepcopy(node.target)]
        loop.body[0].body = [flip, *r]
        loop.body[0].orelse = [*els, *body]
        return [init, loop]

    top, i0 = site.chain[0]
    stmts = [*enter(0), *_copy_all(top[i0 + 1:])]
    pre: list[ast.stmt] = []
    if globals_:
        pre.append(_skeleton("global " + ", ".join(sorted(globals_)), fn).body[0])
    for p in locals_:
        pre.extend(_skeleton(f"if {p} is __tp_unbound: del {p}", fn).body)
    name = f"__tp_resume_{qname.replace('.', '_')}_{'s' if stmt else ''}{site.number}"
    func = _skeleton(f"def {name}({', '.join(params)}): pass", fn).body[0]
    func.body = [*pre, *stmts]
    ast.fix_missing_locations(func)
    text = ast.unparse(func)
    short = qname.split(".")[-1]
    source = (f"{text}\n{name}.__code__ = {name}.__code__.replace("
              f"co_name={short!r}, co_qualname={qname!r})\n")
    if stmt:
        return ir.Resume(-1, name, params, source, _linemap(func, text, fn), stmt=site.number)
    return ir.Resume(site.number, name, params, source, _linemap(func, text, fn))


def _entry(st: ast.stmt) -> tuple[int, int, int, int]:
    if isinstance(st, (ast.For, ast.While, ast.If, ast.With, ast.Try, ast.FunctionDef)):
        end_col = st.col_offset + 1
        for ch in ast.iter_child_nodes(st):
            if isinstance(ch, ast.expr) and ch.end_lineno == st.lineno:
                end_col = max(end_col, ch.end_col_offset)
        return (st.lineno, st.lineno, st.col_offset, end_col)
    return (st.lineno, st.end_lineno or st.lineno, st.col_offset, st.end_col_offset or st.col_offset + 1)


def _linemap(func: ast.FunctionDef, text: str, fn: ast.FunctionDef):
    """Generated line -> original position, by pairing the statements of the unparsed-and-reparsed
    function with those of the one we built (same shapes, so same order)."""
    reparsed = ast.parse(text).body[0]
    a = [s for s in ast.walk(func) if isinstance(s, ast.stmt)]
    b = [s for s in ast.walk(reparsed) if isinstance(s, ast.stmt)]
    nlines = len(text.splitlines())
    head = (fn.lineno, fn.lineno, fn.col_offset, fn.col_offset + 1)
    table: dict[int, tuple[int, int, int, int]] = {1: head}
    if [type(x) for x in a] == [type(x) for x in b]:
        for x, y in zip(a, b):
            table.setdefault(y.lineno, _entry(x))
        table[1] = head
    out = []
    cur = head
    for line in range(1, nlines + 1):
        cur = table.get(line, cur)
        out.append(cur)
    out.append(cur)   # the `__code__` replacement line
    return tuple(out)


def resume_sources(module: ir.Module) -> str:
    """Python source to append to the module's source (what the exec slot runs at import): the
    prelude, then every continuation of every function. "" when no function has a resume."""
    parts = []
    for f in module.functions:
        for r in f.resumes:
            parts.append(f"__tp_resume_def({r.source!r}, {r.linemap!r})\n")
    if not parts:
        return ""
    return "\n" + PRELUDE + "".join(parts)
