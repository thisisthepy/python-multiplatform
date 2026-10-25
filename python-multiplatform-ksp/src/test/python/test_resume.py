"""OSR-style exit continuations (SPEC N-8, #152): `typedpython.resume`.

Pure Python, no C. For each function and EVERY iteration of EVERY loop ("resume point") the test
runs the original up to that point, captures the locals and the loop state, calls the generated
continuation, and requires the same result, the same final list contents and the same exception
(type and text) as the interpreted original run end to end.

The capture is test-only: the function's AST is copied and a hook is inserted at the top of each
loop body (for `while`, before the condition), see `_Instrument`. Lists are deep-copied at capture
(one memo, so aliasing is kept) and the continuation works on its own copy.
"""
import ast
import copy
import textwrap
import traceback
from types import SimpleNamespace

import pytest

from typedpython import frontend, ir, resume

UNBOUND_NAME = "__tp_unbound"


# --- harness --------------------------------------------------------------------------------------

def loop_kinds(fn: ast.FunctionDef, force_obj: frozenset = frozenset()) -> list[str]:
    """Pre-order kinds, as the front end would give them (`range` for a range call with a name target)."""
    kinds: list[str] = []

    def walk(stmts):
        for st in stmts:
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


def instrument(fn: ast.FunctionDef, kinds: list[str]) -> ast.FunctionDef:
    """A copy of `fn` with hooks. Written as a plain recursive rewrite (clearer than a visitor)."""
    fn = copy.deepcopy(fn)
    fn.decorator_list = []
    counter = [0]

    def walk(stmts):
        for st in stmts:
            if isinstance(st, ast.While):
                s = counter[0]
                counter[0] += 1
                hook = ast.parse(f"(__hook({s}, locals()) or True)", mode="eval").body
                st.test = ast.BoolOp(ast.And(), [hook, st.test])
                walk(st.body)
            elif isinstance(st, ast.For):
                s = counter[0]
                counter[0] += 1
                call = ast.parse(f"{'__rng' if kinds[s] == 'range' else '__it'}({s}, 0)",
                                 mode="eval").body
                call.args[1] = st.iter
                st.iter = call
                st.body.insert(0, ast.parse(f"__hook({s}, locals())").body[0])
                walk(st.body)
            else:
                for name in ("body", "orelse"):
                    walk(getattr(st, name, []))

    walk(fn.body)
    ast.fix_missing_locations(fn)
    return fn


class _RecIter:
    def __init__(self, iterable):
        self.inner, self.cur = iter(iterable), None

    def __iter__(self):
        return self

    def __next__(self):
        self.cur = next(self.inner)
        return self.cur


def build_module(source: str, force_obj=frozenset()):
    """(namespace with f and its continuations, fn ast, kinds, resumes)."""
    source = textwrap.dedent(source).lstrip("\n")
    tree = ast.parse(source)
    fn = next(n for n in tree.body if isinstance(n, ast.FunctionDef))
    kinds = loop_kinds(fn, force_obj)
    resumes = resume.build(fn, kinds, fn.name)
    assert len(resumes) == len(kinds), "every loop must have a continuation"
    mod = ir.Module("m", (ir.Function(fn.name, (), ir.Type.NONE, {}, (), True, False, fn.lineno,
                                      resumes=resumes),))
    full = source + resume.resume_sources(mod)
    ns: dict = {}
    exec(compile(full, "mod.py", "exec"), ns)
    # a second namespace, instrumented
    inst = ast.Module(body=[instrument(fn, kinds)], type_ignores=[])
    ins_ns: dict = {}
    return ns, fn, kinds, resumes, inst, ins_ns, source


def outcome(call):
    try:
        return ("ok", call())
    except BaseException as e:   # noqa: BLE001 - the test compares every exception
        return ("raise", type(e).__name__, str(e))


def capture_points(inst, kinds, make_args):
    """Run the instrumented function; return the list of (site, loc copy, aux copy)."""
    points = []
    rng: dict[int, tuple[int, int]] = {}
    iters: dict[int, _RecIter] = {}

    def rng_(s, r):
        rng[s] = (r.stop, r.step)
        return r

    def it_(s, x):
        iters[s] = _RecIter(x)
        return iters[s]

    def hook(s, loc):
        aux = {"rng": dict(rng),
               "it": {k: v.inner for k, v in iters.items()},
               "cur": {k: v.cur for k, v in iters.items()}}
        loc_c, aux_c = copy.deepcopy((dict(loc), aux))
        points.append((s, loc_c, aux_c))

    ns = {"__hook": hook, "__rng": rng_, "__it": it_}
    exec(compile(ast.fix_missing_locations(inst), "mod.py", "exec"), ns)
    args = make_args()
    outcome(lambda: ns[inst.body[0].name](*args))
    return points


def loop_vars(fn, kinds):
    """site -> loop target AST (pre-order, same numbering)."""
    out = []

    def walk(stmts):
        for st in stmts:
            if isinstance(st, (ast.For, ast.While)):
                out.append(st)
            for name in ("body", "orelse"):
                walk(getattr(st, name, []))

    walk(fn.body)
    return out


def check(source, make_args, force_obj=frozenset(), list_params=None, min_points=1):
    """Compare every resume point. Returns the number of points checked."""
    ns, fn, kinds, resumes, inst, _, _ = build_module(source, force_obj)
    f = ns[fn.name]
    sentinel = ns[UNBOUND_NAME]
    loops = loop_vars(fn, kinds)
    by_site = {r.site: r for r in resumes}
    names = [a.arg for a in fn.args.args]
    # the reference run
    args0 = make_args()
    expect = outcome(lambda: f(*args0))
    expect_lists = copy.deepcopy([a for a in args0 if isinstance(a, list)])

    points = capture_points(inst, kinds, make_args)
    assert len(points) >= min_points
    for s, loc, aux in points:
        r = by_site[s]
        vals = []
        for p in r.params:
            if p.startswith("__tp_cur_"):
                k = int(p.rsplit("_", 1)[1])
                if kinds[k] == "range":
                    vals.append(loc[loops[k].target.id])
                else:
                    vals.append(aux["cur"][k])
            elif p.startswith("__tp_stop_"):
                vals.append(aux["rng"][int(p.rsplit("_", 1)[1])][0])
            elif p.startswith("__tp_step_"):
                vals.append(aux["rng"][int(p.rsplit("_", 1)[1])][1])
            elif p.startswith("__tp_it_"):
                vals.append(aux["it"][int(p.rsplit("_", 1)[1])])
            else:
                vals.append(loc.get(p, sentinel))
        got = outcome(lambda: ns[r.name](*vals))
        got_lists = [loc[n] for n in names if isinstance(loc.get(n), list)]
        assert got == expect, (s, r.params, vals, got, expect)
        assert got_lists == expect_lists, (s, got_lists, expect_lists)
    return len(points)


# --- the functions ----------------------------------------------------------------------------------

SINGLE = """
def f(xs, n):
    t = 0
    for i in range(n):
        t += xs[i] * 2
        xs[i] = t
    return t
"""

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

OBJ = """
def f(xs, n):
    t = 0
    for i in range(n):
        xs.append(i)
        t += i
        if t > 20:
            break
    return t
"""

OBJ_NESTED = """
def f(xs, n):
    t = 0
    for i in range(n):
        for j in range(i):
            xs.append(j)
            if j == 3:
                continue
            t += j
        xs.append(-i)
        if i == 5:
            continue
        t += 1000
    return t
"""

UNPACK = """
def f(xs, ys):
    t = 0
    for a, b in zip(xs, ys):
        t += a * b
        xs.append(t)
        if t > 30:
            break
    return t
"""

LATE = """
def f(xs, n):
    for i in range(n):
        if i == 2:
            late = i * 10
        xs[i] += 1
    return late
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

IF_ELSE = """
def f(xs, n):
    acc = 0
    for i in range(n):
        if i % 2:
            for j in range(3):
                acc += j
                xs.append(acc)
        else:
            acc -= 1
            for j in range(2):
                if j == 1:
                    continue
                acc += 10
        acc += 1
    return acc
"""


def test_single_for_range():
    assert check(SINGLE, lambda: ([1, 2, 3, 4], 4)) == 4


def test_single_for_range_raising_inside():
    assert check(SINGLE, lambda: ([1, 2, 3, 4], 6)) == 5   # the hook of i=4 runs before the IndexError


def test_single_for_range_zero_iterations():
    assert check(SINGLE, lambda: ([1], 0), min_points=0) == 0


@pytest.mark.parametrize("n", [3, 6, 9])
def test_nested_for_range_with_break_and_continue(n):
    assert check(NESTED, lambda: ([3, 1, -1, 4, 1, 5, 9, 2, 6], n)) > n


def test_nested_for_range_with_break_in_the_outer_loop():
    assert check(NESTED, lambda: ([300, 200, 100, 4, 1, 5, 9, 2, 6], 9)) > 5


def test_while_with_a_counter():
    assert check(WHILE, lambda: ([5, 5, 5, 5, 5],)) == 6


def test_while_with_an_inner_for():
    assert check(WHILE_FOR, lambda: ([0, 1, 2], 4)) > 8


def test_for_obj_over_a_range_object():
    assert check(OBJ, lambda: ([], 9), force_obj=frozenset({0})) == 7


def test_for_obj_range_object_exhausts_without_break():
    assert check(OBJ, lambda: ([], 4), force_obj=frozenset({0})) == 4


def test_for_obj_nested_outer_object_inner_range():
    assert check(OBJ_NESTED, lambda: ([], 8), force_obj=frozenset({0})) > 8


def test_for_obj_nested_both_objects():
    assert check(OBJ_NESTED, lambda: ([], 8), force_obj=frozenset({0, 1})) > 8


def test_for_obj_nested_inner_object_outer_range():
    assert check(OBJ_NESTED, lambda: ([], 8), force_obj=frozenset({1})) > 8


def test_for_obj_with_unpacking_targets():
    assert check(UNPACK, lambda: ([1, 2, 3, 4, 5], [5, 4, 3, 2, 1])) >= 3


@pytest.mark.parametrize("n", [2, 4])
def test_local_first_assigned_inside_the_loop(n):
    assert check(LATE, lambda: ([0, 0, 0, 0, 0], n)) == n


def test_return_inside_the_loop():
    assert check(RETURN, lambda: ([0, 3, 9], 3)) > 3


def test_return_inside_the_loop_not_taken():
    assert check(RETURN, lambda: ([0, 0, 0], 3)) > 3


@pytest.mark.parametrize("force", [frozenset(), frozenset({0}), frozenset({1}), frozenset({0, 1})])
def test_negative_step(force):
    assert check(NEG, lambda: ([], 9), force_obj=force) > 5


def test_loops_in_both_branches_of_an_if():
    assert check(IF_ELSE, lambda: ([], 5)) > 10


def test_hidden_state_and_param_order():
    ns, fn, kinds, resumes, *_ = build_module(NESTED)
    assert [r.site for r in resumes] == [0, 1]
    assert resumes[0].name == "__tp_resume_f_0"
    # sorted locals, then the loop state, outermost first
    # `j` is bound inside loop 0 (a previous iteration), so it may be bound at site 0
    assert resumes[0].params == ("j", "n", "total", "xs", "__tp_cur_0", "__tp_stop_0", "__tp_step_0")
    assert resumes[1].params == ("n", "total", "xs", "__tp_cur_0", "__tp_stop_0", "__tp_step_0",
                                 "__tp_cur_1", "__tp_stop_1", "__tp_step_1")
    # the loop variables are set by the loop headers, not passed
    assert "i" not in resumes[1].params and "j" not in resumes[1].params


def test_unbound_local_is_passed_as_the_sentinel_and_deleted():
    ns, fn, kinds, resumes, *_ = build_module(LATE)
    assert "late" in resumes[0].params and "if late is __tp_unbound:\n        del late" in resumes[0].source
    with pytest.raises(UnboundLocalError):
        ns["__tp_resume_f_0"](ns[UNBOUND_NAME], 2, [0, 0], 1, 2, 1)


def test_local_bound_only_after_the_loop_is_not_a_parameter():
    src = """
    def f(xs, n):
        for i in range(n):
            xs[i] += 1
        after = 1
        return after
    """
    resumes = build_module(src)[3]
    assert resumes[0].params == ("n", "xs", "__tp_cur_0", "__tp_stop_0", "__tp_step_0")


def test_traceback_reads_as_the_interpreted_function():
    src = """
    def f(xs, n):
        t = 0
        for i in range(n):
            t += 1
            xs[i] = 10 // (3 - i)
        return t
    """
    ns, fn, kinds, resumes, *_ = build_module(src)

    def frames(call):
        try:
            call()
        except ZeroDivisionError as e:
            return [(fs.filename, fs.name, fs.lineno) for fs in traceback.extract_tb(e.__traceback__)][-1]

    direct = frames(lambda: ns["f"]([0] * 6, 6))
    resumed = frames(lambda: ns["__tp_resume_f_0"](6, 1, [0] * 6, 1, 6, 1))
    assert resumed == direct == ("mod.py", "f", 5)
    assert ns["__tp_resume_f_0"].__code__.co_qualname == "f"


def test_a_global_declaration_is_kept():
    src = """
    G = 0
    def f(xs, n):
        global G
        for i in range(n):
            G += xs[i]
        return G
    """
    ns, fn, kinds, resumes, *_ = build_module(src)
    assert "G" not in resumes[0].params
    ns["G"] = 5
    assert ns["__tp_resume_f_0"](3, [1, 2, 3], 1, 3, 1) == 5 + 2 + 3


def test_loop_inside_a_with_has_no_continuation():
    src = """
    def f(xs, n):
        with open("/dev/null") as fh:
            for i in range(n):
                xs[i] += 1
        for k in range(n):
            xs[k] += 1
    """
    tree = ast.parse(textwrap.dedent(src))
    fn = tree.body[0]
    rs = resume.build(fn, ["range", "range"], "f")
    assert [r.site for r in rs] == [1]


def test_mismatching_loop_count_gives_nothing():
    tree = ast.parse(textwrap.dedent(SINGLE))
    assert resume.build(tree.body[0], [], "f") == ()


# --- the front end ------------------------------------------------------------------------------------

def lower(tmp_path, source):
    path = tmp_path / "mod.py"
    path.write_text(textwrap.dedent(source).lstrip("\n"))
    return frontend.lower(path)


def test_frontend_gives_a_snapshot_holder_one_resume_per_loop(tmp_path):
    module = lower(tmp_path, """
        # typedpython: compiled
        def f(xs: list[float], n: int) -> float:
            t = 0.0
            for i in range(n):
                for j in range(i, n):
                    t += xs[j]
                t += 1
            k = 1.0
            while t < 40.0:
                a = k * 2.0
                t += a
                k += 1.0
                xs[0] = t
            return t
    """)
    assert module.skipped == {}
    fn = module.functions[0]
    assert any(isinstance(p, ir.ArrayParam) for p in fn.params)
    assert [r.site for r in fn.resumes] == [0, 1, 2]
    assert [r.name for r in fn.resumes] == ["__tp_resume_f_0", "__tp_resume_f_1", "__tp_resume_f_2"]
    # `k` is bound by an assignment after loop 0 and 1 only: not a parameter there
    assert "k" not in fn.resumes[0].params and "k" in fn.resumes[2].params
    assert "a" in fn.resumes[2].params
    # compiler temporaries never appear
    assert not any(p.startswith("tp_") or p.startswith("__t") for r in fn.resumes for p in r.params
                   if not p.startswith("__tp_"))
    src = resume.resume_sources(module)
    ns = {}
    exec(compile(textwrap.dedent(open(tmp_path / "mod.py").read()) + src, "mod.py", "exec"), ns)
    # resume loop 1 (inner) at i=1, j=2 of n=4: t so far 0
    xs = [1.0, 2.0, 3.0, 4.0]
    got = ns["__tp_resume_f_1"](*[{"xs": xs, "n": 4, "t": 0.0, "k": ns[UNBOUND_NAME], "a": ns[UNBOUND_NAME],
                                   "__tp_cur_0": 1, "__tp_stop_0": 4, "__tp_step_0": 1,
                                   "__tp_cur_1": 2, "__tp_stop_1": 4, "__tp_step_1": 1}[p]
                                  for p in fn.resumes[1].params if p not in ("i", "j")])
    ref = [1.0, 2.0, 3.0, 4.0]
    t = 0.0
    for i in range(1, 4):
        for j in range(i if i != 1 else 2, 4):
            t += ref[j]
        t += 1
    k, a = 1.0, 0.0
    while t < 40.0:
        a = k * 2.0
        t += a
        k += 1.0
        ref[0] = t
    assert got == t
    assert xs == ref   # the while loop ran too


def test_frontend_function_without_a_snapshot_has_no_resumes(tmp_path):
    module = lower(tmp_path, """
        # typedpython: compiled
        def g(n: int) -> int:
            t = 0
            for i in range(n):
                t += i
            return t
    """)
    assert module.functions[0].resumes == ()
    assert resume.resume_sources(module) == ""
