"""Statement exits (SPEC N-8, #390 item 5; design/typedpython.md 4.3.6 바).

A function holding one or two list copies must not run interpreted code while the copies are live
(an interpreted frame can detach, which suspends the lists' critical section, or hand the GIL over).
When a C to C Call in such a function reports the callee's deopt, the function leaves through a
statement exit: it writes the lists back, ends the section and calls a continuation that re-executes
the statement holding the Call from its start, then the rest of the function.

Sections:
  * resume.py statement sites (pure Python, no C): a differential harness. For a statement S of a
    function, an instrumented copy of the function raises a private exception carrying `locals()` and
    the loop state the n-th time S is reached; the continuation called with that state must give the
    same result, exception and list contents as the uninterrupted run, for every n. A mutant
    continuation (the rest of the current iteration dropped) must fail the harness.
  * front end attribution (pure Python): IR statements carry the number of the source statement.
  * verifier refusals and acceptances (pure Python).
  * generated C structure (cgen.generate, no C compiler).
  * behaviour of the compiled extension (needs a C compiler: CI). Expected red before the change:
    the window observations (`test_the_list_holds_the_interpreters_values_when_the_callee_runs*`),
    the traceback test and the mutant test; the result tests are green before and after (the old
    in-window redo gave the right values when nothing else touched the lists).

Red record (the agent that wrote this file, 2026-11-14, 3.15t venv, before the implementation): of the
106 pure-Python tests then present, 103 failed: 86 on the missing `resume.statements`, 7 on the missing
`stmt=` / `Resume.stmt`, and the behavioural reds: the parallel-loop `scale` and the hand-built `f`
were accepted, `tp_cg_redo` was reachable in `tp_impl_mul_av`, cgen raised no CGenError. 3 passed
before as they must (mul_av accepted, `out[i + 1] += g(i)` accepted, a pure list holder and a function
without lists not refused). `test_a_local_first_bound_in_a_while_body_reaches_its_later_tests` was
added after the first implementation and observed red on it (UnboundLocalError), then green. All pure
tests green after. The compiled-extension tests were not run by that agent (AGENTS.md 10): CI.
"""
from __future__ import annotations

import ast
import copy
import dataclasses
import re
import textwrap
import traceback

import pytest

from typedpython import cgen, frontend, ir, resume, verify

UNBOUND_NAME = "__tp_unbound"


# --- harness: statement sites of resume.py ------------------------------------------------------------

class _Capture(Exception):
    """Raised by the instrumented copy the n-th time the target statement is reached."""

    def __init__(self, loc, aux):
        super().__init__("capture")
        self.loc, self.aux = loc, aux


def _loop_kinds(fn: ast.FunctionDef, force_obj: frozenset = frozenset()) -> list[str]:
    kinds: list[str] = []

    def walk(stmts):
        for st in stmts:
            if isinstance(st, (ast.FunctionDef, ast.ClassDef)):
                continue
            if isinstance(st, ast.While):
                kinds.append("while")
            elif isinstance(st, ast.For):
                simple = (isinstance(st.iter, ast.Call) and isinstance(st.iter.func, ast.Name)
                          and st.iter.func.id == "range" and isinstance(st.target, ast.Name))
                kinds.append("range" if simple and len(kinds) not in force_obj else "obj")
            for name in ("body", "orelse"):
                walk(getattr(st, name, []))

    walk(fn.body)
    return kinds


def _loops(fn: ast.FunctionDef) -> list[ast.stmt]:
    out: list[ast.stmt] = []

    def walk(stmts):
        for st in stmts:
            if isinstance(st, (ast.FunctionDef, ast.ClassDef)):
                continue
            if isinstance(st, (ast.For, ast.While)):
                out.append(st)
            for name in ("body", "orelse"):
                walk(getattr(st, name, []))

    walk(fn.body)
    return out


class _RecIter:
    def __init__(self, iterable):
        self.inner = iter(iterable)

    def __iter__(self):
        return self

    def __next__(self):
        return next(self.inner)


def _instrument(fn: ast.FunctionDef, kinds: list[str], target: int, nth: int) -> ast.FunctionDef:
    """A copy of `fn` whose statement number `target` raises _Capture on its `nth` start."""
    fn = copy.deepcopy(fn)
    fn.decorator_list = []
    stmts = resume.statements(fn)
    node = stmts[target]
    loops = _loops(fn)
    for s, lp in enumerate(loops):
        if isinstance(lp, ast.For):
            call = ast.parse(f"{'__rng' if kinds[s] == 'range' else '__it'}({s}, 0)", mode="eval").body
            call.args[1] = lp.iter
            lp.iter = call
    hook = ast.parse(f"__hook({nth}, locals())", mode="eval").body
    if isinstance(node, ast.While):
        node.test = ast.BoolOp(ast.And(), [ast.BoolOp(ast.Or(), [hook, ast.Constant(True)]), node.test])
    else:
        for parent in ast.walk(fn):
            for name in ("body", "orelse"):
                lst = getattr(parent, name, None)
                if isinstance(lst, list) and any(x is node for x in lst):
                    i = next(j for j, x in enumerate(lst) if x is node)
                    lst.insert(i, ast.Expr(hook))
    ast.fix_missing_locations(fn)
    return fn


def _outcome(call):
    try:
        return ("ok", call())
    except _Capture:
        raise
    except BaseException as e:   # noqa: BLE001 - every exception is compared
        return ("raise", type(e).__name__, str(e))


def _first_iteration_body_dropped(source: str) -> str:
    """Mutant: in every `if __tp_first_k:` branch keep only the flag flip (the rest of the current
    iteration is dropped)."""
    tree = ast.parse(source)
    for n in ast.walk(tree):
        if isinstance(n, ast.If) and isinstance(n.test, ast.Name) and n.test.id.startswith("__tp_first_"):
            n.body = n.body[:1]
    return ast.unparse(tree)


def stmt_check(source, target, make_args, force_obj=frozenset(), mutate=None, max_points=200):
    """The differential harness. `target` is the statement number (resume.statements order) or a
    predicate on the AST statement (the first match). Returns the number of points checked."""
    source = textwrap.dedent(source).lstrip("\n")
    tree = ast.parse(source)
    fn = next(n for n in tree.body if isinstance(n, ast.FunctionDef))
    stmts = resume.statements(fn)
    if not isinstance(target, int):
        target = next(i for i, s in enumerate(stmts) if target(s))
    kinds = _loop_kinds(fn, force_obj)
    resumes = resume.build(fn, kinds, fn.name, stmts=(target,))
    [r] = [x for x in resumes if x.stmt == target]
    assert r.name == f"__tp_resume_{fn.name}_s{target}" and r.site == -1
    if mutate is not None:
        head, tail = r.source.split(f"\n{r.name}.__code__", 1)
        r = dataclasses.replace(r, source=mutate(head) + f"\n{r.name}.__code__" + tail)
    mod = ir.Module("m", (ir.Function(fn.name, (), ir.Type.NONE, {}, (), True, False, fn.lineno,
                                      resumes=(r,)),))
    ns: dict = {}
    exec(compile(source + resume.resume_sources(mod), "mod.py", "exec"), ns)
    sentinel = ns[UNBOUND_NAME]
    loops = _loops(fn)

    args0 = make_args()
    expect = _outcome(lambda: ns[fn.name](*args0))
    expect_lists = copy.deepcopy([a for a in args0 if isinstance(a, list)])
    names = [a.arg for a in fn.args.args]

    checked = 0
    for nth in range(1, max_points + 1):
        inst = ast.Module(body=[_instrument(fn, kinds, target, nth)], type_ignores=[])
        rng: dict[int, tuple[int, int]] = {}
        iters: dict[int, _RecIter] = {}
        count = [0]

        def rng_(s, x):
            rng[s] = (x.stop, x.step)
            return x

        def it_(s, x):
            iters[s] = _RecIter(x)
            return iters[s]

        def hook(n, loc):
            count[0] += 1
            if count[0] == n:
                loc_c, aux_c = copy.deepcopy((dict(loc), {"rng": dict(rng),
                                                          "it": {k: v.inner for k, v in iters.items()}}))
                raise _Capture(loc_c, aux_c)

        ins = {"__hook": hook, "__rng": rng_, "__it": it_}
        exec(compile(inst, "mod.py", "exec"), ins)
        args = make_args()
        try:
            ins[fn.name](*args)
            break                                   # S was reached fewer than nth times
        except _Capture as cap:
            loc, aux = cap.loc, cap.aux
        except BaseException:                       # noqa: BLE001 - finished before the nth start
            break
        vals = []
        for p in r.params:
            if p.startswith("__tp_cur_"):
                vals.append(loc[loops[int(p.rsplit("_", 1)[1])].target.id])
            elif p.startswith("__tp_stop_"):
                vals.append(aux["rng"][int(p.rsplit("_", 1)[1])][0])
            elif p.startswith("__tp_step_"):
                vals.append(aux["rng"][int(p.rsplit("_", 1)[1])][1])
            elif p.startswith("__tp_it_"):
                vals.append(aux["it"][int(p.rsplit("_", 1)[1])])
            else:
                vals.append(loc.get(p, sentinel))
        got = _outcome(lambda: ns[r.name](*vals))
        got_lists = [loc[n] for n in names if isinstance(loc.get(n), list)]
        assert got == expect, (nth, r.params, got, expect)
        assert got_lists == expect_lists, (nth, got_lists, expect_lists)
        checked += 1
    return checked


def _is(kind, text=None):
    def pred(st):
        return isinstance(st, kind) and (text is None or ast.unparse(st).splitlines()[0].strip() == text)
    return pred


NESTED = """
def f(xs, n):
    total = 0
    for i in range(n):
        if i == 2:
            continue
        for j in range(i, n):
            if j == 3:
                continue
            if xs[j] < 0:
                break
            total += xs[j]
            xs[j] += 1
        total += 100
        if total > 600:
            break
        xs[i] = total
    total += 7
    return total
"""

WHILE = """
def f(xs):
    k = 0
    while k < len(xs):
        xs[k] += k
        k += 1
        if k == 3:
            continue
        xs[0] += 1
    return k
"""

WHILE_FOR = """
def f(xs, n):
    k = 0
    while k < len(xs):
        for i in range(3):
            xs[i] += k
            if xs[i] > 12:
                break
        k += 1
        if k == 2:
            continue
        xs[0] -= 1
    return k
"""

ELIF = """
def f(xs, n):
    acc = 0
    for i in range(n):
        if i % 3 == 0:
            acc += 1
        elif i % 3 == 1:
            acc += 10
        else:
            acc -= 2
        xs.append(acc)
    return acc
"""

TWO_LOOPS_IF = """
def f(xs, n):
    t = 0
    for i in range(n):
        for j in range(n):
            if (i + j) % 2:
                t += i * j
                xs[j] += 1
            t += 1
    return t
"""

RETURN = """
def f(xs, n):
    for i in range(n):
        for j in range(n):
            if xs[i] + j == 7:
                return (i, j)
            xs[i] += 1
    return None
"""

LATE = """
def f(xs, n):
    for i in range(n):
        if i == 2:
            late = i * 10
        xs[i] += 1
    return late
"""

NEG = """
def f(xs, n):
    t = 0
    for i in range(n, 0, -2):
        for j in range(10, i, -3):
            t += j * i
            xs.append(t)
            if t % 7 == 0:
                break
        t -= 1
    return t
"""

RAISES = """
def f(xs, n):
    s = 0
    for i in range(n):
        s += 10 // (3 - i)
        xs[i] = s
    return s
"""

STRAIGHT = """
def f(xs, n):
    a = xs[0] * 2
    b = a + n
    xs[1] = b
    return a + b
"""


@pytest.mark.parametrize("target", [
    _is(ast.AugAssign, "total += xs[j]"),
    _is(ast.Assign, "xs[i] = total"),
    _is(ast.If, "if total > 600:"),
    _is(ast.If, "if xs[j] < 0:"),
    _is(ast.Continue),
    _is(ast.Break),
    _is(ast.For, "for j in range(i, n):"),
    _is(ast.AugAssign, "total += 7"),
    _is(ast.Return),
])
@pytest.mark.parametrize("n", [3, 6, 9])
def test_statement_sites_in_nested_loops_with_break_and_continue(target, n):
    assert stmt_check(NESTED, target, lambda: [[3, 1, -1, 4, 1, 5, 9, 2, 6], n]) >= 1


def test_statement_site_after_an_outer_break():
    assert stmt_check(NESTED, _is(ast.AugAssign, "total += 7"),
                      lambda: [[300, 200, 100, 4, 1, 5, 9, 2, 6], 9]) == 1


@pytest.mark.parametrize("target", [
    _is(ast.While), _is(ast.AugAssign, "xs[k] += k"), _is(ast.If, "if k == 3:"),
    _is(ast.AugAssign, "xs[0] += 1"), _is(ast.Return)])
def test_statement_sites_of_a_while_loop(target):
    assert stmt_check(WHILE, target, lambda: [[5, 5, 5, 5, 5]]) >= 1


WHILE_LATE = """
def f(xs):
    i = 0
    while i < len(xs):
        last = xs[i]
        i += 1
    return last
"""


def test_a_local_first_bound_in_a_while_body_reaches_its_later_tests():
    # at the n-th test, `last` holds the value of iteration n - 1: the continuation must receive it
    assert stmt_check(WHILE_LATE, _is(ast.While), lambda: [[4, 5, 6]]) == 4


def test_the_while_statement_is_re_entered_at_every_test():
    # one start per test evaluation: five iterations and the final false test
    assert stmt_check(WHILE, _is(ast.While), lambda: [[5, 5, 5, 5, 5]]) == 6


@pytest.mark.parametrize("target", [
    _is(ast.AugAssign, "xs[i] += k"), _is(ast.For), _is(ast.If, "if xs[i] > 12:"),
    _is(ast.AugAssign, "xs[0] -= 1")])
def test_statement_sites_in_a_for_inside_a_while(target):
    assert stmt_check(WHILE_FOR, target, lambda: [[0, 1, 2], 4]) >= 1


@pytest.mark.parametrize("target", [
    _is(ast.If, "if i % 3 == 1:"), _is(ast.AugAssign, "acc += 10"), _is(ast.AugAssign, "acc -= 2"),
    _is(ast.If, "if i % 3 == 0:"), _is(ast.Expr)])
def test_statement_sites_in_an_elif_chain(target):
    assert stmt_check(ELIF, target, lambda: [[], 8]) >= 2


def test_statement_inside_an_if_inside_two_loops():
    assert stmt_check(TWO_LOOPS_IF, _is(ast.AugAssign, "t += i * j"), lambda: [[0] * 5, 5]) == 12


@pytest.mark.parametrize("target", [_is(ast.Return, "return (i, j)"), _is(ast.If),
                                    _is(ast.AugAssign, "xs[i] += 1"), _is(ast.Return, "return None")])
@pytest.mark.parametrize("xs", [[0, 3, 9], [0, 0, 0]])
def test_statement_sites_around_a_return_inside_loops(target, xs):
    stmt_check(RETURN, target, lambda: [list(xs), 3])


@pytest.mark.parametrize("n", [2, 4])
@pytest.mark.parametrize("target", [_is(ast.Assign, "late = i * 10"), _is(ast.AugAssign),
                                    _is(ast.Return)])
def test_a_local_that_may_be_unbound(target, n):
    stmt_check(LATE, target, lambda: [[0, 0, 0, 0, 0], n])


@pytest.mark.parametrize("force", [frozenset(), frozenset({0}), frozenset({1}), frozenset({0, 1})])
@pytest.mark.parametrize("target", [_is(ast.AugAssign, "t += j * i"), _is(ast.AugAssign, "t -= 1"),
                                    _is(ast.For, "for j in range(10, i, -3):"), _is(ast.If)])
def test_negative_range_steps_and_object_loops(force, target):
    assert stmt_check(NEG, target, lambda: [[], 9], force_obj=force) >= 1


@pytest.mark.parametrize("target", [_is(ast.AugAssign), _is(ast.Assign), _is(ast.For)])
def test_an_exception_raised_by_the_re_executed_statement(target):
    # i == 3 divides by zero: the continuation raises CPython's own ZeroDivisionError
    assert stmt_check(RAISES, target, lambda: [[0] * 6, 6]) >= 1


@pytest.mark.parametrize("k", range(4))
def test_straight_line_statements(k):
    assert stmt_check(STRAIGHT, k, lambda: [[3, 0], 4]) == 1


def test_the_harness_catches_a_continuation_without_the_rest_of_the_iteration():
    with pytest.raises(AssertionError):
        stmt_check(NESTED, _is(ast.AugAssign, "total += xs[j]"),
                   lambda: [[3, 1, -1, 4, 1, 5, 9, 2, 6], 9], mutate=_first_iteration_body_dropped)


def test_statement_site_parameters_and_names():
    fn = ast.parse(textwrap.dedent(NESTED)).body[0]
    stmts = resume.statements(fn)
    k = next(i for i, s in enumerate(stmts) if _is(ast.AugAssign, "total += xs[j]")(s))
    kinds = _loop_kinds(fn)
    rs = resume.build(fn, kinds, "f", stmts=(k,))
    loop_rs = resume.build(fn, kinds, "f")
    assert rs[:len(loop_rs)] == loop_rs                       # loop sites unchanged, same order
    [r] = [x for x in rs if x.stmt is not None]
    assert (r.site, r.stmt, r.name) == (-1, k, f"__tp_resume_f_s{k}")
    # sorted locals that may be bound there, then the enclosing loops' state, outermost first; the
    # enclosing range variables are set by the loop headers, not passed
    assert r.params == ("n", "total", "xs", "__tp_cur_0", "__tp_stop_0", "__tp_step_0",
                        "__tp_cur_1", "__tp_stop_1", "__tp_step_1")


def test_a_for_statement_site_is_a_plain_copy_of_the_loop():
    fn = ast.parse(textwrap.dedent(NESTED)).body[0]
    stmts = resume.statements(fn)
    k = next(i for i, s in enumerate(stmts) if _is(ast.For, "for j in range(i, n):")(s))
    [r] = [x for x in resume.build(fn, _loop_kinds(fn), "f", stmts=(k,)) if x.stmt == k]
    # its own variable is an ordinary local (it may hold the previous run's value) and not hidden state
    assert "j" in r.params and "__tp_cur_1" not in r.params
    assert "for j in range(i, n):" in r.source


def test_statement_site_traceback_reads_as_the_interpreted_function():
    source = textwrap.dedent(RAISES).lstrip("\n")
    fn = ast.parse(source).body[0]
    k = next(i for i, s in enumerate(resume.statements(fn)) if _is(ast.AugAssign)(s))
    [r] = [x for x in resume.build(fn, _loop_kinds(fn), "f", stmts=(k,)) if x.stmt == k]
    mod = ir.Module("m", (ir.Function("f", (), ir.Type.NONE, {}, (), True, False, 1, resumes=(r,)),))
    ns: dict = {}
    exec(compile(source + resume.resume_sources(mod), "mod.py", "exec"), ns)

    def last(call):
        try:
            call()
        except ZeroDivisionError as e:
            return [(fs.filename, fs.name, fs.lineno) for fs in traceback.extract_tb(e.__traceback__)][-1]

    direct = last(lambda: ns["f"]([0] * 6, 6))
    resumed = last(lambda: ns[r.name](6, 0, [0] * 6, 3, 6, 1))
    assert resumed == direct == ("mod.py", "f", 4)
    assert ns[r.name].__code__.co_name == "f" and ns[r.name].__code__.co_qualname == "f"


def test_a_statement_inside_a_with_has_no_site():
    src = textwrap.dedent("""
    def f(xs, n):
        with open("/dev/null") as fh:
            xs[0] = n
        xs[1] = n
    """)
    fn = ast.parse(src).body[0]
    stmts = resume.statements(fn)
    inside = next(i for i, s in enumerate(stmts) if _is(ast.Assign, "xs[0] = n")(s))
    after = next(i for i, s in enumerate(stmts) if _is(ast.Assign, "xs[1] = n")(s))
    rs = resume.build(fn, [], "f", stmts=(inside, after))
    assert [r.stmt for r in rs] == [after]


# --- the front end: source positions of IR statements ---------------------------------------------------

SPECTRAL = '''
@compiled
def eval_a(i: int, j: int) -> float:
    return 1.0 / ((i + j) * (i + j + 1) // 2 + i + 1)


@compiled
def mul_av(n: int, v: list[float], out: list[float]) -> None:
    for i in range(n):
        s: float = 0.0
        for j in range(n):
            s += eval_a(i, j) * v[j]
        out[i] = s
'''


def _lower(tmp_path, source, name="mod.py"):
    p = tmp_path / name
    p.write_text(textwrap.dedent(source).lstrip("\n"))
    return frontend.lower(p), p


def _ir_stmts(body):
    for s in body:
        yield s
        for name in ("then", "orelse", "body"):
            v = getattr(s, name, ())
            if isinstance(v, tuple):
                yield from _ir_stmts(v)


def test_every_ir_statement_carries_its_source_statement(tmp_path):
    m, _ = _lower(tmp_path, SPECTRAL)
    f = next(x for x in m.functions if x.name == "mul_av")
    kinds = [(type(s).__name__, s.stmt) for s in _ir_stmts(f.body)]
    # 0 for, 1 s: float = 0.0, 2 for, 3 s += ..., 4 out[i] = s (resume.statements order)
    assert kinds == [("ForRange", 0), ("Assign", 1), ("ForRange", 2), ("Assign", 3), ("StoreIndex", 4)]


def test_a_while_with_a_temporary_test_attributes_its_synthetic_exit_to_the_while(tmp_path):
    m, _ = _lower(tmp_path, '''
        @compiled
        def g(x: float) -> float:
            return x * 2.0


        @compiled
        def w(v: list[float]) -> None:
            x = 0.0
            while 0.0 <= g(x) < 10.0:
                v[0] = v[0] + 1.0
                x += 1.0
    ''')
    f = next(x for x in m.functions if x.name == "w")
    stmts = list(_ir_stmts(f.body))
    loop = next(s for s in stmts if isinstance(s, ir.While))
    synthetic = [s for s in _ir_stmts(loop.body)
                 if isinstance(s, (ir.If, ir.Break)) or (isinstance(s, ir.Assign) and s.target.startswith("_tp"))]
    assert len(synthetic) == 3 and all(s.stmt == loop.stmt for s in synthetic), synthetic


def test_an_impure_list_holder_with_a_call_gets_statement_continuations(tmp_path):
    m, _ = _lower(tmp_path, SPECTRAL)
    f = next(x for x in m.functions if x.name == "mul_av")
    loops = [r for r in f.resumes if r.stmt is None]
    stmts = [r for r in f.resumes if r.stmt is not None]
    assert [r.name for r in loops] == ["__tp_resume_mul_av_0", "__tp_resume_mul_av_1"]
    assert [(r.name, r.site) for r in stmts] == [("__tp_resume_mul_av_s3", -1)]
    assert stmts[0].params == ("n", "out", "s", "v", "__tp_cur_0", "__tp_stop_0", "__tp_step_0",
                               "__tp_cur_1", "__tp_stop_1", "__tp_step_1")


def test_a_pure_function_or_one_without_lists_gets_no_statement_continuation(tmp_path):
    m, _ = _lower(tmp_path, '''
        @compiled
        def g(i: int) -> float:
            return float(i * 2)

        G: float = 1.0


        @compiled
        def pure2(v: list[float], n: int) -> float:
            s = 0.0
            for i in range(n):
                s += g(i) * v[i]
            return s


        @compiled
        def eg(n: int) -> float:
            s = 0.0
            for i in range(n):
                s += g(i) * G
            return s
    ''')
    for name in ("pure2", "eg"):
        f = next(x for x in m.functions if x.name == name)
        assert f.resumes and all(r.stmt is None for r in f.resumes), name


# --- the verifier --------------------------------------------------------------------------------------

def _verified(tmp_path, source):
    m, p = _lower(tmp_path, source)
    out, diags = verify.verify(m)
    return m, out, diags, p


def _rules(diags, name):
    return sorted({d.rule for d in diags if d.function == name})


def test_spectral_mul_av_is_accepted(tmp_path):
    _, out, diags, _ = _verified(tmp_path, SPECTRAL)
    assert {f.name for f in out.functions} == {"eval_a", "mul_av"}, diags


def test_a_list_holder_with_a_call_and_a_parallel_loop_stays_interpreted(tmp_path):
    # a parallel ForRange has no OSR-style exit, so the function has no statement exit at all
    _, out, diags, _ = _verified(tmp_path, '''
        @compiled
        def eval_a(i: int, j: int) -> float:
            return 1.0 / ((i + j) * (i + j + 1) // 2 + i + 1)


        @compiled(parallel=True)
        def scale(v: list[float], out: list[float]) -> float:
            for i in range(0, len(v)):
                v[i] = v[i] * 2.0
            g = eval_a(1, 2)
            out[0] = g
            return g
    ''')
    assert "scale" not in {f.name for f in out.functions}
    assert verify.ARRAY_WAIT in _rules(diags, "scale"), diags
    assert "statement exit" in out.skipped["scale"], out.skipped["scale"]


def _hand(body, resumes=(), locals_=None):
    """An impure list holder `f(a)` and a pure may_deopt `h(n)`, built by hand."""
    h = ir.Function("h", (ir.Param("n", ir.Type.I64),), ir.Type.F64, {}, (
        ir.Return(ir.ToFloat(ir.Type.F64, ir.BinOp(ir.Type.I64, ir.BinOpKind.MUL,
                                                   ir.Local(ir.Type.I64, "n"), ir.Const(ir.Type.I64, 2)))),),
        True, True, 1)
    f = ir.Function("f", (ir.ArrayParam("a", ir.Type.F64_ARRAY, True),), ir.Type.NONE,
                    dict(locals_ or {"u": ir.Type.F64}), tuple(body), False, False, 2, resumes=tuple(resumes))
    return ir.Module("m", (h, f))


def _call():
    return ir.Call(ir.Type.F64, "h", (ir.Const(ir.Type.I64, 3),), redo=True)


def _stmt_resume(k, params=("a", "u")):
    return ir.Resume(-1, f"__tp_resume_f_s{k}", params, "", (), stmt=k)


def _store(k, value=None):
    value = ir.Local(ir.Type.F64, "u") if value is None else value
    return ir.StoreIndex("a", ir.Const(ir.Type.I64, 0), value, stmt=k)


def test_a_call_without_a_source_statement_stays_interpreted():
    out, diags = verify.verify(_hand([ir.Assign("u", _call()), ir.StoreIndex(
        "a", ir.Const(ir.Type.I64, 0), ir.Local(ir.Type.F64, "u")), ir.Return()]))
    assert "f" not in {f.name for f in out.functions}
    assert verify.ARRAY_WAIT in _rules(diags, "f"), diags


def test_a_call_whose_statement_has_no_continuation_stays_interpreted():
    out, diags = verify.verify(_hand([ir.Assign("u", _call(), stmt=0), _store(1), ir.Return(stmt=2)],
                                     resumes=[_stmt_resume(5)]))
    assert verify.ARRAY_WAIT in _rules(diags, "f"), diags


def test_a_call_with_a_statement_continuation_is_accepted():
    out, diags = verify.verify(_hand([ir.Assign("u", _call(), stmt=0), _store(1), ir.Return(stmt=2)],
                                     resumes=[_stmt_resume(0)]))
    assert "f" in {f.name for f in out.functions}, diags


def test_an_unmappable_continuation_parameter_refuses():
    out, diags = verify.verify(_hand([ir.Assign("u", _call(), stmt=0), _store(1), ir.Return(stmt=2)],
                                     resumes=[_stmt_resume(0, params=("a", "u", "nowhere"))]))
    assert verify.ARRAY_WAIT in _rules(diags, "f"), diags


def test_a_store_of_the_same_statement_before_the_call_refuses():
    out, diags = verify.verify(_hand([_store(0, ir.Const(ir.Type.F64, 1.0)), ir.Assign("u", _call(), stmt=0),
                                      _store(1), ir.Return(stmt=2)],
                                     resumes=[_stmt_resume(0)]))
    assert verify.ARRAY_WAIT in _rules(diags, "f"), diags


def test_an_assignment_of_a_received_local_before_the_call_refuses():
    body = [ir.Assign("u", ir.Const(ir.Type.F64, 1.0), stmt=0), ir.Assign("t", _call(), stmt=0),
            _store(1, ir.Local(ir.Type.F64, "t")), ir.Return(stmt=2)]
    out, diags = verify.verify(_hand(body, resumes=[_stmt_resume(0)], locals_={"u": ir.Type.F64, "t": ir.Type.F64}))
    assert verify.ARRAY_WAIT in _rules(diags, "f"), diags
    # a compiler temporary (not a continuation parameter) before the call is fine
    body[0] = ir.Assign("_tp0", ir.Const(ir.Type.F64, 1.0), stmt=0)
    out, diags = verify.verify(_hand(body, resumes=[_stmt_resume(0)],
                                     locals_={"u": ir.Type.F64, "t": ir.Type.F64, "_tp0": ir.Type.F64}))
    assert "f" in {f.name for f in out.functions}, diags


def test_a_same_statement_run_must_be_contiguous():
    body = [ir.Assign("_tp0", ir.Const(ir.Type.F64, 1.0), stmt=0), _store(1, ir.Const(ir.Type.F64, 1.0)),
            ir.Assign("u", _call(), stmt=0), _store(2), ir.Return(stmt=3)]
    out, diags = verify.verify(_hand(body, resumes=[_stmt_resume(0)],
                                     locals_={"u": ir.Type.F64, "_tp0": ir.Type.F64}))
    assert verify.ARRAY_WAIT in _rules(diags, "f"), diags


def test_augmented_subscript_with_a_temporary_index_is_accepted(tmp_path):
    # `out[i + 1] += g(i)`: CPython evaluates the index, loads out[i + 1], then calls; the store is last
    m, out, diags, _ = _verified(tmp_path, '''
        @compiled
        def g(i: int) -> float:
            return float(i * 2)


        @compiled
        def bump(out: list[float], n: int) -> None:
            for i in range(n):
                out[i + 1] += g(i)
    ''')
    assert "bump" in {f.name for f in out.functions}, diags
    f = next(x for x in m.functions if x.name == "bump")
    body = list(_ir_stmts(f.body))
    assert any(isinstance(s, ir.Assign) and s.target.startswith("_tp") for s in body)


def test_a_pure_list_holder_and_a_function_without_lists_are_not_refused(tmp_path):
    _, out, diags, _ = _verified(tmp_path, '''
        @compiled
        def g(i: int) -> float:
            return float(i * 2)

        G: float = 2.0


        @compiled
        def pure2(v: list[float], n: int) -> float:
            s = 0.0
            for i in range(n):
                s += g(i) * v[i]
            return s


        @compiled
        def side(o: object, n: int) -> float:
            print(o)
            return g(n)
    ''')
    assert {"g", "pure2", "side"} <= {f.name for f in out.functions}, diags


# --- generated C ------------------------------------------------------------------------------------

def _function_text(c: str, signature_start: str) -> str:
    """The definition (not the forward declaration) of the function whose signature starts so."""
    i = c.index(signature_start)
    while c[i:c.index("\n", i)].rstrip().endswith(";"):
        i = c.index(signature_start, i + 1)
    j = c.index("\n}\n", i)
    return c[i:j]


@pytest.fixture
def spectral_c(tmp_path):
    m, p = _lower(tmp_path, SPECTRAL, "spectral.py")
    out, diags = verify.verify(m)
    assert {f.name for f in out.functions} == {"eval_a", "mul_av"}, diags
    return cgen.generate(out, p, "spectral.py")


def test_no_interpreted_redo_is_reachable_inside_the_window(spectral_c):
    for sig in ("static int tp_impl_mul_av(", "static inline int tp_fimpl_mul_av("):
        body = _function_text(spectral_c, sig)
        assert "tp_cg_redo(" not in body, sig
    wrap = _function_text(spectral_c, "static PyObject *tp_wrap_mul_av(")
    # the wrapper's own deopt redo runs after the write-back and tp_cs_end
    assert wrap.index("tp_deopt:") < wrap.rindex("tp_cs_end(&tp_lock);") < wrap.rindex("tp_cg_redo(")


@pytest.mark.parametrize("sig", ["static int tp_impl_mul_av(", "static inline int tp_fimpl_mul_av("])
def test_a_callee_deopt_leaves_through_the_statement_exit(spectral_c, sig):
    body = _function_text(spectral_c, sig)
    i = body.index("tp_s = tp_" + ("fimpl" if "fimpl" in sig else "impl") + "_eval_a(")
    after = body[i:]
    exit_ = after[after.index("if (tp_s == 1) {"):]
    exit_ = exit_[:exit_.index("goto tp_exit;\n", exit_.index("PyObject_Vectorcall"))]
    assert '"__tp_resume_mul_av_s3"' in exit_
    # write-back of both lists, then the section ends, then the continuation is looked up and called
    wb_v, wb_out = exit_.index("tp_f64_array_exit(l_v) < 0"), exit_.index("tp_f64_array_exit(l_out) < 0")
    end = exit_.index("tp_cs_end(tp_my->lock);")
    look = exit_.index("PyDict_GetItemStringRef(tp_dict, \"__tp_resume_mul_av_s3\"")
    call = exit_.index("PyObject_Vectorcall(tp_ofn")
    assert wb_v < end and wb_out < end < look < call
    assert "tp_rc = tp_my->result != NULL ? 2 : -1;" in exit_
    assert "(void)tp_f64_array_exit(" not in exit_     # a write-back, not a plain free (GIL builds)


def test_cgen_refuses_to_emit_an_in_window_redo(tmp_path):
    # bypass the verifier: a list holder whose Call has no statement exit
    module = _hand([ir.Assign("u", _call()), ir.StoreIndex("a", ir.Const(ir.Type.I64, 0),
                                                           ir.Local(ir.Type.F64, "u")), ir.Return()])
    p = tmp_path / "m.py"
    p.write_text("def h(n):\n    return float(n * 2)\n\ndef f(a):\n    u = h(3)\n    a[0] = u\n")
    with pytest.raises(cgen.CGenError, match="390"):
        cgen.generate(module, p, "m.py")


def test_the_poll_exit_still_only_frees_its_fresh_copies(spectral_c):
    body = _function_text(spectral_c, "static int tp_impl_mul_av(")
    poll = body[body.index("#152: poll inside a snapshot holder"):]
    poll = poll[:poll.index("goto tp_exit;\n", poll.index("PyObject_Vectorcall"))]
    assert "(void)tp_f64_array_exit(l_v);" in poll and "(void)tp_f64_array_exit(l_out);" in poll


# --- the compiled extension (CI: needs a C compiler) ---------------------------------------------------

BEHAVIOUR = '''

@compiled
def g(i: int, k: int) -> float:
    t = 1
    if i == k:
        t = 4611686018427387904
    return float(t * 4)


@compiled
def fill(v: list[float], out: list[float], k: int) -> None:
    for i in range(len(out)):
        out[i] = g(i, k) + v[i]


@compiled
def acc2(v: list[float], out: list[float], k: int) -> float:
    s = 0.0
    for i in range(len(out)):
        for j in range(len(v)):
            s += g(i * len(v) + j, k) * v[j]
        out[i] = s
    return s


@compiled
def iftest(v: list[float], out: list[float], k: int) -> float:
    hits = 0.0
    for i in range(len(out)):
        if g(i, k) > 10.0:
            out[i] = v[i]
            hits += 1.0
        else:
            out[i] = -v[i]
    return hits


@compiled
def whiletest(v: list[float], k: int) -> float:
    total = 0.0
    for i in range(len(v)):
        x = 0.0
        while g(i, k) < 10.0 and x < 3.0:
            v[i] = v[i] + 1.0
            x += 1.0
        total += x
    return total


@compiled
def ret(v: list[float], k: int) -> float:
    for i in range(len(v)):
        v[i] = v[i] * 2.0
        if i == k:
            return g(i, k)
    return 0.0
'''

N = 8


def _build(tmp_path_factory, name, mutant=False):
    from test_cgen import need_runtime, runtime_dir
    from typedpython import cbuild
    need_runtime()
    d = tmp_path_factory.mktemp(name)
    path = d / f"{name}.py"
    path.write_text(BEHAVIOUR)
    proved, diags = verify.verify(frontend.lower(path))
    assert {"g", "fill", "acc2", "iftest", "whiletest", "ret"} <= {f.name for f in proved.functions}, diags
    saved = cgen._redo_inside_window
    if mutant:
        cgen._redo_inside_window = lambda: True
    try:
        c = cgen.generate(proved, path, path.name)
    finally:
        cgen._redo_inside_window = saved
    so = cbuild.build(c, name, d / "out", runtime_dir=runtime_dir())
    ref: dict = {}
    exec(compile(BEHAVIOUR, path.name, "exec"), ref)          # one dict: `g` is looked up in it
    return cbuild.load(name, so), ref, path


@pytest.fixture(scope="module")
def stmod(tmp_path_factory):
    return _build(tmp_path_factory, "stmtexit")


@pytest.fixture(scope="module")
def stmutant(tmp_path_factory):
    return _build(tmp_path_factory, "stmtexitmut", mutant=True)


def _run(call):
    try:
        return ("ok", call())
    except Exception as e:      # noqa: BLE001 - compared by type and text
        return ("raise", type(e).__name__, str(e))


CASES = {
    "fill": lambda k: ([float(i) for i in range(N)], [0.0] * N, k),
    "acc2": lambda k: ([0.5 * i for i in range(3)], [0.0] * 4, k),
    "iftest": lambda k: ([float(i) for i in range(N)], [0.0] * N, k),
    "whiletest": lambda k: ([0.0] * N, k),
    "ret": lambda k: ([1.0] * N, k),
}


@pytest.mark.parametrize("name", sorted(CASES))
@pytest.mark.parametrize("k", [-1, 0, 3, 7, 11])
def test_a_callee_overflow_gives_the_interpreters_result_and_lists(stmod, name, k):
    m, ref, _ = stmod
    a, b = CASES[name](k), CASES[name](k)
    got, want = _run(lambda: getattr(m, name)(*a)), _run(lambda: ref[name](*b))
    assert got == want and a == b

    def types(args):
        return [[type(e) for e in x] for x in args if isinstance(x, list)]
    assert types(a) == types(b)


def test_the_deopt_is_counted_once_by_the_callees_wrapper(stmod):
    m, _, _ = stmod
    before = m.__typedpython_deopts__
    m.fill([1.0] * N, [0.0] * N, 3)
    assert m.__typedpython_deopts__ == before + 1


def _spy_case(mod_or_ns, interpreted: bool, threaded: bool):
    """Run `fill(v, out, 3)` where the callee's interpreted run (only at i == 3: the deopt) writes
    v[5] = 100.0, from this thread or from another one it joins. The interpreter reads v[5] after
    that write; a function that keeps its copy of v inside the window reads 5.0."""
    import threading
    v, out = [float(i) for i in range(N)], [0.0] * N

    def write():
        v[5] = 100.0

    def spying(orig):
        def spy(i, k):
            if i == k:
                if threaded:
                    t = threading.Thread(target=write)
                    t.start()
                    t.join()
                else:
                    write()
            return orig(i, k)
        return spy

    if interpreted:
        ns = mod_or_ns
        orig = ns["g"]
        ns["g"] = spying(orig)
        try:
            ns["fill"](v, out, 3)
        finally:
            ns["g"] = orig
    else:
        table = mod_or_ns.__typedpython_interpreted__
        orig = table["g"]
        table["g"] = spying(orig)
        try:
            mod_or_ns.fill(v, out, 3)
        finally:
            table["g"] = orig
    return v, out


@pytest.mark.parametrize("threaded", [False, True])
def test_the_list_holds_the_interpreters_values_when_the_callee_runs(stmod, threaded):
    m, ref, _ = stmod
    want = _spy_case(ref, True, threaded)
    assert want[1][5] == 100.0 + 4.0
    assert _spy_case(m, False, threaded) == want


@pytest.mark.parametrize("threaded", [False, True])
def test_the_window_observation_catches_an_in_window_redo(stmutant, threaded):
    m, ref, _ = stmutant
    assert _spy_case(m, False, threaded) != _spy_case(ref, True, threaded)


def test_a_traceback_in_the_continuation_reads_as_the_interpreted_function(stmod):
    m, ref, path = stmod

    def last(call):
        try:
            call()
        except IndexError as e:
            fs = traceback.extract_tb(e.__traceback__)[-1]
            return (fs.name, fs.lineno, str(e))

    # v is shorter than out: v[5] raises after the deopt at i == 3
    got = last(lambda: m.fill([1.0] * 5, [0.0] * N, 3))
    want = last(lambda: ref["fill"]([1.0] * 5, [0.0] * N, 3))
    assert got == want and got[0] == "fill"


def test_generated_c_of_the_behaviour_module_has_statement_exits(stmod):
    m, _, path = stmod
    proved, _ = verify.verify(frontend.lower(path))
    c = cgen.generate(proved, path, path.name)
    for name in ("fill", "acc2", "iftest", "whiletest", "ret"):
        assert re.search(rf'"__tp_resume_{name}_s\d+"', c), name


# --- numpy scalars at an exit (#426) ----------------------------------------------------------------
#
# In a function with a numpy buffer parameter (#220) a local derived from an element is an np.float64 /
# np.int64 / np.bool in the interpreter, decided per value at run time by the shadow flag `<name>__np`
# (taint.py). A continuation is interpreted code: it must receive that numpy scalar, not a float / int /
# bool, or `type(x)`, the returned object and numpy's arithmetic rules (inf and a RuntimeWarning where a
# float raises ZeroDivisionError) differ after the exit.
#
# Red record (the agent that wrote these, 2026-11-15, before the fix, by inspection of the generated C
# only: no test was run, AGENTS.md 10): the statement exit and the poll exit of `np_stmt` / `np_poll`
# boxed `s` with `PyFloat_FromDouble(l_s)`, `e` with `PyLong_FromLongLong((long long)l_e)` and `b` with
# `PyBool_FromLong(l_b)` although `l_s__np` / `l_e__np` / `l_b__np` existed; `rng_var` and `named` below
# were lowered. Everything here is unobserved until CI runs it.

NP_EXIT = '''
import numpy as np
import numpy.typing as npt


@compiled
def g(i: int, k: int) -> float:
    t = 1
    if i == k:
        t = 4611686018427387904
    return float(t * 4)


@compiled
def np_stmt(x: npt.NDArray[np.float64], out: list[float], k: int, w: int) -> object:
    s = 0.0
    b = False
    p = 1.5
    for i in range(len(out)):
        if i == 2:
            s = x[0]
            b = x[0] > x[1]
        out[i] = g(i, k)
    if w == 0:
        return s
    if w == 1:
        return b
    return p


@compiled
def np_div(x: npt.NDArray[np.float64], out: list[float], k: int, d: float) -> object:
    s = x[0]
    for i in range(len(out)):
        out[i] = g(i, k)
    return s / d


@compiled
def np_poll(x: npt.NDArray[np.float64], y: npt.NDArray[np.int64], v: list[float], w: int) -> object:
    s = 0.0
    e = 0
    b = False
    p = 1.5
    for i in range(len(v)):
        if i == 40:
            s = x[0]
            e = y[0]
            b = x[0] > x[1]
        p = p + v[i]
    if w == 0:
        return s
    if w == 1:
        return e
    if w == 2:
        return b
    return p
'''

NP_EXIT_NAMES = {"g", "np_stmt", "np_div", "np_poll"}


def _np_lower(d, name="npexit"):
    path = d / f"{name}.py"
    path.write_text(NP_EXIT)
    proved, diags = verify.verify(frontend.lower(path))
    assert NP_EXIT_NAMES <= {f.name for f in proved.functions}, (diags, proved.skipped)
    return proved, path


def _np_generate(proved, path, mutant=False):
    saved = cgen._exit_numpy_scalars
    if mutant:
        cgen._exit_numpy_scalars = lambda: False
    try:
        return cgen.generate(proved, path, path.name)
    finally:
        cgen._exit_numpy_scalars = saved


def _exit_tails(c: str, signature_start: str) -> dict[str, str]:
    """continuation name -> the text of each exit tail of that function that calls it (joined)."""
    body = _function_text(c, signature_start)
    out: dict[str, str] = {}
    for m in re.finditer(r'PyDict_GetItemStringRef\(tp_dict, "(__tp_resume_\w+)", &tp_ofn\)', body):
        end = body.index("PyObject_Vectorcall(tp_ofn", m.start())
        out[m.group(1)] = out.get(m.group(1), "") + body[m.start():end]
    return out


@pytest.mark.parametrize("sig", ["static int tp_impl_np_stmt(", "static inline int tp_fimpl_np_stmt("])
def test_a_statement_exit_and_a_poll_exit_box_a_flagged_local_as_its_numpy_scalar(tmp_path, sig):
    proved, path = _np_lower(tmp_path)
    c = _np_generate(proved, path)
    if sig not in c:                       # no fast twin for this function: nothing to check there
        assert "fimpl" in sig
        return
    tails = _exit_tails(c, sig)
    assert "__tp_resume_np_stmt_0" in tails                       # the loop's poll exit
    stmt = [k for k in tails if re.fullmatch(r"__tp_resume_np_stmt_s\d+", k)]
    assert stmt, sorted(tails)
    for name in ["__tp_resume_np_stmt_0", *stmt]:
        tail = tails[name]
        # the same run-time types Box.np uses: buf_types[2] np.float64, [4] np.bool_
        assert ("(l_s__np ? tp_box_np(tp_st->buf_types[2], PyFloat_FromDouble(l_s)) "
                ": PyFloat_FromDouble(l_s))") in tail, name
        assert ("(l_b__np ? tp_box_np(tp_st->buf_types[4], PyBool_FromLong(l_b)) "
                ": PyBool_FromLong(l_b))") in tail, name
        # a local that is never derived from an element has no flag and is boxed as before
        assert "= PyFloat_FromDouble(l_p);" in tail and "l_p__np" not in c, name
        # parameters and the hidden loop state are ints
        assert "= PyLong_FromLongLong((long long)l_k);" in tail, name


def test_a_poll_exit_boxes_an_int64_derived_local_as_np_int64(tmp_path):
    proved, path = _np_lower(tmp_path)
    c = _np_generate(proved, path)
    tail = _exit_tails(c, "static int tp_impl_np_poll(")["__tp_resume_np_poll_0"]
    assert ("(l_e__np ? tp_box_np(tp_st->buf_types[3], PyLong_FromLongLong((long long)l_e)) "
            ": PyLong_FromLongLong((long long)l_e))") in tail
    assert "(l_s__np ? tp_box_np(tp_st->buf_types[2], PyFloat_FromDouble(l_s))" in tail
    assert "(l_b__np ? tp_box_np(tp_st->buf_types[4], PyBool_FromLong(l_b))" in tail
    # nothing else is: p, the parameters and the loop state the continuation's `range` takes
    assert tail.count("tp_box_np(") == 3


def test_the_mutation_point_emits_the_plain_boxes(tmp_path):
    proved, path = _np_lower(tmp_path)
    c = _np_generate(proved, path, mutant=True)
    for sig in ("static int tp_impl_np_stmt(", "static int tp_impl_np_poll("):
        for name, tail in _exit_tails(c, sig).items():
            assert "tp_box_np(" not in tail and "= PyFloat_FromDouble(l_s);" in tail, (sig, name)


def test_functions_without_a_numpy_buffer_are_boxed_as_before(spectral_c):
    for sig in ("static int tp_impl_mul_av(", "static inline int tp_fimpl_mul_av("):
        for name, tail in _exit_tails(spectral_c, sig).items():
            assert "tp_box_np(" not in tail, (sig, name)


NP_REFUSED = '''
import numpy as np
import numpy.typing as npt


@compiled
def rng_var(y: npt.NDArray[np.int64], v: list[float]) -> object:
    i = y[0]
    for i in range(3):
        pass
    t = 0.0
    for j in range(len(v)):
        t += v[j]
    return i


@compiled
def named(x: npt.NDArray[np.float64], s__np: bool) -> object:
    s = 1.0
    if s__np:
        s = x[0]
    return s


@compiled
def kept(x: npt.NDArray[np.float64]) -> object:
    s = 0.0
    for i in range(len(x)):
        s = s + x[i]
    return s
'''


def test_a_flag_that_would_not_follow_the_value_keeps_the_function_interpreted(tmp_path):
    """The shadow flag is exact only if every binding of the local sets it. A `for ... in range`
    variable is bound by the loop, not by an assignment, so one that is also assigned a numpy-derived
    value would keep a stale flag (an np.int64 where the interpreter has an int): refused. A name
    ending in `__np` would be taken for a flag: refused in a function with a numpy buffer."""
    m, _ = _lower(tmp_path, NP_REFUSED, "nprefused.py")
    assert {f.name for f in m.functions} == {"kept"}, m.skipped
    assert "loop variable `i`" in m.skipped["rng_var"] and "numpy" in m.skipped["rng_var"]
    assert "`s__np`" in m.skipped["named"]


def _np_build(tmp_path_factory, name, mutant=False):
    from test_cgen import need_runtime, runtime_dir
    from typedpython import cbuild
    pytest.importorskip("numpy")
    need_runtime()
    d = tmp_path_factory.mktemp(name)
    proved, path = _np_lower(d, name)
    c = _np_generate(proved, path, mutant)
    # as test_osr_exit: a poll every 32 iterations, with `__tp_test_snap_hook__()` run in its window
    so = cbuild.build(c, name, d / "out", runtime_dir=runtime_dir(),
                      flags=(*cbuild.DEFAULT_FLAGS, "-DTP_POLL_INTERVAL=32", "-DTP_TEST_SNAP_HOOK"))
    return cbuild.load(name, so)


@pytest.fixture(scope="module")
def npmod(tmp_path_factory):
    return _np_build(tmp_path_factory, "npexit")


@pytest.fixture(scope="module")
def npmutant(tmp_path_factory):
    return _np_build(tmp_path_factory, "npexitmut", mutant=True)


def _np_args(n=N):
    import numpy as np
    return np.array([1.5, -2.25, 3.0], dtype=np.float64), np.array([3, -5, 7], dtype=np.int64), n


def _np_run(call):
    import warnings
    with warnings.catch_warnings(record=True) as w:
        warnings.simplefilter("always")
        try:
            r = ("ok", call())
        except Exception as e:      # noqa: BLE001 - compared by type and text
            r = ("raise", type(e).__name__, str(e))
    return r, [(x.category, str(x.message)) for x in w]


def _spied(m, pattern, call):
    """Run `call` with every continuation of `m` whose name matches `pattern` wrapped: returns the
    outcome and, per continuation call, {parameter name: type of the argument}."""
    import inspect
    seen = []
    saved = {k: v for k, v in vars(m).items() if re.fullmatch(pattern, k)}
    assert saved, pattern

    def spy(orig):
        names = list(inspect.signature(orig).parameters)

        def run(*a):
            seen.append({n: type(v) for n, v in zip(names, a)})
            return orig(*a)
        return run

    for k, v in saved.items():
        setattr(m, k, spy(v))
    try:
        return _np_run(call), seen
    finally:
        for k, v in saved.items():
            setattr(m, k, v)


def _stmt_case(m, k, w):
    """np_stmt compiled (continuations spied) and interpreted; the callee overflows at i == k."""
    x, _, n = _np_args()
    a, b = [0.0] * n, [0.0] * n
    got, seen = _spied(m, r"__tp_resume_np_stmt_s\d+", lambda: m.np_stmt(x, a, k, w))
    want = _np_run(lambda: m.__typedpython_interpreted__["np_stmt"](x.copy(), b, k, w))
    return got, want, seen, (a, b)


@pytest.mark.parametrize("w", [0, 1, 2])
@pytest.mark.parametrize("k", [-1, 1, 5])
def test_a_statement_exit_hands_over_the_interpreters_numpy_scalars(npmod, k, w):
    """k == 1: the exit comes before `s = x[0]` (s and b are still a float and a bool); k == 5: after
    (an np.float64 and an np.bool); k == -1: no exit. The flag is a run-time value, so one build
    covers both."""
    import numpy as np
    got, want, seen, (a, b) = _stmt_case(npmod, k, w)
    assert got == want and a == b
    assert got[0][0] == "ok" and type(got[0][1]) is type(want[0][1]), (got, want)
    assert type(got[0][1]) is {0: np.float64, 1: np.bool_, 2: float}[w]
    if k == -1:
        assert seen == []
        return
    [types] = seen                                             # one exit, at i == k
    assert types["p"] is float and types["k"] is int and types["x"] is np.ndarray
    if k == 1:
        assert types["s"] is float and types["b"] is bool
    else:
        assert types["s"] is np.float64 and types["b"] is np.bool_


def test_numpys_division_rules_hold_after_a_statement_exit(npmod):
    """`s / 0.0` of an np.float64 is inf with numpy's RuntimeWarning; of a float it raises
    ZeroDivisionError. With and without the exit the compiled call must do what the interpreter does."""
    m = npmod
    x, _, n = _np_args()
    for k in (-1, 3):
        got = _np_run(lambda: m.np_div(x, [0.0] * n, k, 0.0))
        want = _np_run(lambda: m.__typedpython_interpreted__["np_div"](x.copy(), [0.0] * n, k, 0.0))
        assert got == want, k
        assert got[0] == ("ok", float("inf")) and type(got[0][1]) is type(want[0][1])
        assert [c for c, _ in got[1]] == [RuntimeWarning], got


def _poll_case(m, calls, w):
    """np_poll compiled, a hook appending to `v` at its `calls`-th poll (polls at i = 32, 64, ...: a
    length change, so that poll leaves), against the interpreted function. The loop's bounds are
    evaluated before the loop and the appended element is never read, so the reference needs no hook."""
    x, y, _ = _np_args()
    v = [0.5] * 100
    state = {"n": 0}

    def hook():
        state["n"] += 1
        if state["n"] == calls:
            v.append(0.0)

    m.__tp_test_snap_hook__ = hook
    try:
        got, seen = _spied(m, r"__tp_resume_np_poll_0", lambda: m.np_poll(x, y, v, w))
    finally:
        del m.__tp_test_snap_hook__
    want = _np_run(lambda: m.__typedpython_interpreted__["np_poll"](x.copy(), y.copy(), [0.5] * 100, w))
    return got, want, seen


@pytest.mark.parametrize("w", [0, 1, 2, 3])
@pytest.mark.parametrize("calls", [1, 2])
def test_a_poll_exit_hands_over_the_interpreters_numpy_scalars(npmod, calls, w):
    """calls == 1: the poll at i == 32, before the locals take element values at i == 40; calls == 2:
    the poll at i == 64, after."""
    import numpy as np
    got, want, seen = _poll_case(npmod, calls, w)
    assert got == want
    assert got[0][0] == "ok" and type(got[0][1]) is type(want[0][1]), (got, want)
    [types] = seen                                             # the poll that saw the change left
    assert types["p"] is float and types["w"] is int
    assert all(types[h] is int for h in ("__tp_cur_0", "__tp_stop_0", "__tp_step_0"))
    if calls == 1:
        assert (types["s"], types["e"], types["b"]) == (float, int, bool)
    else:
        assert (types["s"], types["e"], types["b"]) == (np.float64, np.int64, np.bool_)


def test_plain_boxes_at_an_exit_are_caught(npmutant):
    """The mutant (`cgen._exit_numpy_scalars` False: every local boxed as a float / int / bool) must
    fail the comparisons above: the returned object and the division differ from the interpreter's."""
    m = npmutant
    got, want, seen, _ = _stmt_case(m, 5, 0)
    assert seen and seen[0]["s"] is float
    assert type(got[0][1]) is float and type(want[0][1]) is not float
    got, want, seen = _poll_case(m, 2, 1)
    assert seen and type(got[0][1]) is int and type(want[0][1]) is not int
    x, _, n = _np_args()
    assert _np_run(lambda: m.np_div(x, [0.0] * n, 3, 0.0))[0][:2] == ("raise", "ZeroDivisionError")
