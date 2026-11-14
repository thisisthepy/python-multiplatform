"""Three or more list parameters in a function with an effect (#255 (d2); SPEC N-8; design/typedpython.md
4.3.6). Agent proposal, not confirmed.

Such a function (`ir.direct_writer`) is compiled like the pure case (#255 (d1)) extended to stores: no
copy-in, no lock, no snapshot; every `a[i]` is one `tp_list_ld_*` (`PyList_GetItemRef`) and every
`a[i] = v` one `tp_list_st_*` (`PyList_SetItem` of a new object) at once, in program order. A read that
finds an element of another type, a global that is missing or of another type, or a callee's deopt
leaves through the statement exit of the statement being executed: its continuation re-executes that
statement, interpreted, from its start, then the rest of the function. Sound by the per-statement rule:
every node that can leave comes before the statement's first effect (verifier `verify/array-direct`,
cgen CGenError).

Sections:
  * front end (pure Python): nbody's `advance` and `offset_momentum` shapes lower; a scalar module
    global is read at its use (`Unbox(Global)`), not at entry.
  * verifier (pure Python): acceptance, each refusal, the per-statement rule on hand-built IR.
  * statement continuations of writer statements (pure Python, the resume.py differential harness of
    test_stmt_exit.py), and the mutant that skips the statement being left.
  * generated C structure (cgen.generate, no C compiler).
  * behaviour of the compiled extension (needs a C compiler: CI), written so that a build without the
    feature fails at the fixture (the functions are not compiled): planted elements of another type at
    every position, short lists, globals rebound, deleted and of another type, aliased and empty
    lists, negative indices, a callee overflow, nbody against the interpreter, one threaded
    program-order test, and two deterministic mutants (an exit that skips the statement, a read without
    the element type check).

Red record: see the report of the agent that wrote this file (pure-Python tests observed red before the
implementation; compiled-extension tests not run by it, AGENTS.md 10: CI).
"""
from __future__ import annotations

import dataclasses
import re
import textwrap
import traceback
from pathlib import Path

import pytest

from conftest import decorate_compiled
from typedpython import cgen, cost, frontend, ir, resume, verify

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
NBODY = REPO / "benchmarks" / "typedpython" / "py" / "nbody.py"

SOURCE = '''\
import math

G: float = 2.0


@compiled
def adv3(a: list[float], b: list[float], c: list[float], n: int) -> float:
    s: float = 0.0
    for i in range(n):
        a[i] = a[i] + 1.0
        b[i] -= a[i] * c[i]
        c[i] += 0.5
        s += b[i]
    return s


@compiled
def glob3(a: list[float], b: list[float], c: list[float]) -> None:
    for i in range(len(a)):
        a[i] = b[i] * G
        c[i] = a[i] / G


@compiled
def neg3(a: list[float], b: list[float], c: list[float]) -> float:
    a[-1] = b[-1] + c[-2]
    b[-2] += 1.0
    return a[-1] + float(len(c))


@compiled
def ifw(a: list[float], b: list[float], c: list[float]) -> float:
    t: float = 0.0
    for i in range(len(a)):
        if a[i] > 0.0:
            b[i] = a[i]
        else:
            c[i] = a[i]
        t += a[i]
    return t


@compiled
def wh(a: list[float], b: list[float], c: list[float]) -> float:
    x: float = 0.0
    while x < a[0]:
        b[0] = b[0] + 1.0
        c[0] = x
        x += 1.0
    return x


@compiled
def g(i: int, k: int) -> float:
    t = 1
    if i == k:
        t = 4611686018427387904
    return float(t * 4)


@compiled
def callw(a: list[float], b: list[float], c: list[float], k: int) -> None:
    for i in range(len(a)):
        a[i] = g(i, k) + b[i]
        c[i] = a[i] * 0.5


@compiled
def fill3i(a: list[int], b: list[int], c: list[int], v: int) -> None:
    c[0] = v
    a[0] = v
    b[0] = v


@compiled
def root3(a: list[float], b: list[float], c: list[float]) -> None:
    for i in range(len(a)):
        b[i] = math.sqrt(a[i])
        c[i] = 1.0 / a[i]
'''

WRITERS = ("adv3", "glob3", "neg3", "ifw", "wh", "callw", "fill3i", "root3")


def _lower(tmp_path, source, name="dw.py"):
    p = tmp_path / name
    p.write_text(textwrap.dedent(source).lstrip("\n"))
    return frontend.lower(p), p


def _fn(module, name):
    found = [f for f in module.functions if f.name == name]
    assert found, (name, module.skipped.get(name))
    return found[0]


def _nodes(root, cls):
    found, stack = [], [root]
    while stack:
        x = stack.pop()
        if isinstance(x, (tuple, list)):
            stack.extend(x)
        elif dataclasses.is_dataclass(x) and not isinstance(x, type):
            if isinstance(x, cls):
                found.append(x)
            stack.extend(getattr(x, f.name) for f in dataclasses.fields(x))
    return found


def _rules(diags, name):
    return sorted({d.rule for d in diags if d.function == name})


# --- front end --------------------------------------------------------------------------------------------

def test_writers_lower_without_copy_in_or_entry_globals(tmp_path):
    m, _ = _lower(tmp_path, SOURCE)
    for name in WRITERS:
        f = _fn(m, name)
        assert f.pure is False and f.may_deopt is False, name
        assert f.entry_globals == (), name
        assert ir.direct_writer(f) and ir.direct_lists(f), name
        # a statement continuation for every statement that reads (fill3i only stores: none)
        assert any(r.stmt is not None for r in f.resumes) is (name != "fill3i"), (name, f.resumes)


def test_a_scalar_global_is_read_at_its_use(tmp_path):
    m, _ = _lower(tmp_path, SOURCE)
    f = _fn(m, "glob3")
    unboxes = _nodes(f, ir.Unbox)
    assert len(unboxes) == 2 and all(u.type is ir.Type.F64 and u.operand == ir.Global(ir.Type.OBJ, "G")
                                     for u in unboxes), unboxes
    # elsewhere the same global is still an entry global (a function without three lists)
    m2, _ = _lower(tmp_path, '''
        G: float = 2.0


        @compiled
        def two(a: list[float], b: list[float]) -> None:
            a[0] = b[0] * G
    ''', "two.py")
    assert _fn(m2, "two").entry_globals == (ir.Param("G", ir.Type.F64),)


@pytest.mark.skipif(not NBODY.exists(), reason="benchmarks not present")
def test_nbody_advance_and_offset_momentum_lower(tmp_path):
    p = tmp_path / "nbody.py"
    p.write_text(decorate_compiled(NBODY.read_text()))
    m = frontend.lower(p)
    adv, off = _fn(m, "advance"), _fn(m, "offset_momentum")
    for f in (adv, off):
        assert not f.pure and not f.may_deopt and f.entry_globals == () and ir.direct_writer(f)
    assert [u.operand for u in _nodes(off, ir.Unbox)] == [ir.Global(ir.Type.OBJ, "SOLAR_MASS")] * 3
    assert _nodes(adv, ir.Unbox) == [] and _nodes(adv, ir.Global) == []


# --- the verifier ------------------------------------------------------------------------------------------

def test_the_writers_are_accepted(tmp_path):
    m, _ = _lower(tmp_path, SOURCE)
    out, diags = verify.verify(m)
    assert set(WRITERS) | {"g"} <= {f.name for f in out.functions}, diags


@pytest.mark.skipif(not NBODY.exists(), reason="benchmarks not present")
def test_nbody_compiles_all_three_kernels(tmp_path):
    p = tmp_path / "nbody.py"
    p.write_text(decorate_compiled(NBODY.read_text()))
    out, diags = cost.apply(*verify.verify(frontend.lower(p)))
    assert {f.name for f in out.functions} == {"advance", "energy", "offset_momentum"}, out.skipped


def _verified(tmp_path, source, name):
    m, _ = _lower(tmp_path, source)
    out, diags = verify.verify(m)
    assert name not in {f.name for f in out.functions}, f"{name} was accepted"
    return out.skipped[name], diags


def test_a_copy_of_a_list_parameter_is_refused(tmp_path):
    why, diags = _verified(tmp_path, '''
        @compiled
        def cp(a: list[float], b: list[float], c: list[float]) -> None:
            t = a[:]
            b[0] = t[0]
            c[0] = 1.0
    ''', "cp")
    assert verify.ARRAY_DIRECT in _rules(diags, "cp"), why


def test_a_parallel_loop_is_refused(tmp_path):
    # From source a list read element by element is never proved in bounds, so no loop of such a
    # function is eligible: `parallel=True` compiles it sequentially. A declared `par` is refused.
    m, _ = _lower(tmp_path, '''
        @compiled(parallel=True)
        def par(a: list[float], b: list[float], c: list[float]) -> None:
            for i in range(0, len(a)):
                a[i] = b[i] * 2.0
            c[0] = 1.0
    ''')
    out, diags = verify.verify(m)
    f = _fn(out, "par")
    assert all(s.par is None for s in _nodes(f, ir.ForRange)), diags
    loop = _nodes(_fn(m, "par"), ir.ForRange)[0]
    forged = dataclasses.replace(loop, par=ir.ParInfo(("a",), ("b",), 1))
    bad = dataclasses.replace(_fn(m, "par"), body=tuple(forged if s is loop else s for s in _fn(m, "par").body))
    out, diags = verify.verify(dataclasses.replace(m, functions=(bad,), skipped={}))
    assert not out.functions
    assert verify.ARRAY_DIRECT in _rules(diags, "par"), diags


def test_a_buffer_parameter_beside_the_lists_is_refused(tmp_path):
    m, _ = _lower(tmp_path, SOURCE)
    f = _fn(m, "adv3")
    bad = dataclasses.replace(f, params=f.params + (ir.ArrayParam("d", ir.Type.F64_ARRAY, False, "array"),))
    out, diags = verify.verify(dataclasses.replace(m, functions=(bad,), skipped={}))
    assert not out.functions
    assert verify.ARRAY_DIRECT in _rules(diags, "adv3"), diags


def test_a_callee_with_an_entry_global_is_refused(tmp_path):
    why, diags = _verified(tmp_path, '''
        K: float = 2.0


        @compiled
        def h(i: int) -> float:
            return float(i) * K


        @compiled
        def ce(a: list[float], b: list[float], c: list[float]) -> None:
            a[0] = h(1) + b[0]
            c[0] = 1.0
    ''', "ce")
    # (also `verify/deopt-impure`: the callee's entry guard can fail at the call, and it is no redo)
    assert verify.ARRAY_DIRECT in _rules(diags, "ce"), diags
    assert any("entry global 'K'" in d.message for d in diags if d.rule == verify.ARRAY_DIRECT), diags


# hand-built IR: f(a, b, c) with an effect, three lists, statement continuations as given

F64, I64 = ir.Type.F64, ir.Type.I64


def _read(name, k=0):
    return ir.Index(F64, name, ir.Const(I64, k))


def _st(name, value, stmt):
    return ir.StoreIndex(name, ir.Const(I64, 0), value, stmt=stmt)


def _resume(k, params=("a", "b", "c", "u")):
    return ir.Resume(-1, f"__tp_resume_f_s{k}", params, "", (), stmt=k)


def _hand(body, resumes=(), extra=()):
    imp = ir.Function("imp", (), ir.Type.NONE, {}, (ir.Return(),), False, False, 1)
    stored = {s.array for s in body if isinstance(s, ir.StoreIndex)}
    lists = tuple(ir.ArrayParam(n, ir.Type.F64_ARRAY, n in stored) for n in ("a", "b", "c"))
    f = ir.Function("f", lists, ir.Type.NONE, {"u": F64, "_tp0": F64}, tuple(body), False, False, 2,
                    resumes=tuple(resumes))
    return ir.Module("m", (imp, *extra, f))


def test_hand_built_writer_with_a_read_before_the_store_is_accepted():
    body = [_st("a", _read("b"), 0), ir.Return(stmt=1)]
    out, diags = verify.verify(_hand(body, [_resume(0)]))
    assert "f" in {f.name for f in out.functions}, diags


def test_a_store_before_a_read_of_the_same_statement_is_refused():
    # one source statement (0) lowered to a store, then an IR statement that reads: the read could
    # leave after the store, and the re-executed statement would store again
    body = [_st("a", ir.Const(F64, 1.0), 0), ir.Assign("_tp0", _read("b"), stmt=0),
            _st("c", ir.Local(F64, "_tp0"), 0), ir.Return(stmt=1)]
    out, diags = verify.verify(_hand(body, [_resume(0)]))
    assert "f" not in {f.name for f in out.functions}
    assert verify.ARRAY_DIRECT in _rules(diags, "f"), diags


def test_a_read_whose_statement_has_no_continuation_is_refused():
    body = [_st("a", _read("b"), 0), ir.Return(stmt=1)]
    out, diags = verify.verify(_hand(body, [_resume(7)]))
    assert verify.ARRAY_DIRECT in _rules(diags, "f"), diags
    out, diags = verify.verify(_hand([_st("a", _read("b"), None), ir.Return()], [_resume(0)]))
    assert verify.ARRAY_DIRECT in _rules(diags, "f"), diags


def test_a_call_of_a_callee_with_an_effect_is_refused():
    call = ir.ExprStmt(ir.Call(ir.Type.NONE, "imp", ()), stmt=0)
    out, diags = verify.verify(_hand([call, _st("a", _read("b"), 1), ir.Return(stmt=2)],
                                     [_resume(0), _resume(1)]))
    assert "f" not in {f.name for f in out.functions}
    assert verify.ARRAY_DIRECT in _rules(diags, "f"), diags


def test_cgen_refuses_a_store_before_a_read_of_the_same_statement(tmp_path):
    body = [_st("a", ir.Const(F64, 1.0), 0), ir.Assign("_tp0", _read("b"), stmt=0),
            _st("c", ir.Local(F64, "_tp0"), 0), ir.Return(stmt=1)]
    p = tmp_path / "m.py"
    p.write_text("def imp():\n    return None\n\ndef f(a, b, c):\n    a[0] = 1.0; c[0] = b[0]\n    return\n")
    with pytest.raises(cgen.CGenError, match="255"):
        cgen.generate(_hand(body, [_resume(0), _resume(1)]), p, "m.py")


# --- statement continuations of writer statements (pure Python) --------------------------------------------

ADV = """
def f(a, b, c, n):
    s = 0.0
    for i in range(n):
        a[i] = a[i] + 1.0
        b[i] -= a[i] * c[i]
        c[i] += 0.5
        s += b[i]
    return s
"""


def _adv_args():
    return ([0.5, 1, 2.5, 3.0], [1.0, 2.0, 3, 4.0], [2.0, 1.0, 0.5, 4], 4)


@pytest.mark.parametrize("k", [2, 3, 4, 5, 6])
def test_writer_statement_continuations_match_the_uninterrupted_run(k):
    from test_stmt_exit import stmt_check
    assert stmt_check(ADV, k, _adv_args) >= 1


def test_the_harness_catches_an_exit_that_skips_its_statement(monkeypatch):
    from test_stmt_exit import stmt_check
    monkeypatch.setattr(resume, "_reexecute_the_statement", lambda: False)
    with pytest.raises(AssertionError):
        stmt_check(ADV, 3, _adv_args)


# --- generated C ------------------------------------------------------------------------------------------

def _function_text(c: str, signature_start: str) -> str:
    i = c.index(signature_start)
    while c[i:c.index("\n", i)].rstrip().endswith(";"):
        i = c.index(signature_start, i + 1)
    return c[i:c.index("\n}\n", i)]


@pytest.fixture
def dw_c(tmp_path):
    m, p = _lower(tmp_path, SOURCE)
    out, diags = verify.verify(m)
    assert set(WRITERS) <= {f.name for f in out.functions}, diags
    return cgen.generate(out, p, "dw.py"), out


def test_writers_take_no_lock_and_copy_nothing(dw_c):
    c, _ = dw_c
    for name in WRITERS:
        w = _function_text(c, f"static PyObject *tp_wrap_{name}(")
        assert "tp_cs_" not in w and "_array_enter" not in w and "_array_exit" not in w, name
        assert "PyList_CheckExact" in w and "tp_eg_snapshot" not in w and "tp_global(" not in w, name
        impl = _function_text(c, f"static int tp_impl_{name}(")
        assert "tp_cs_" not in impl and "->data[" not in impl and "_array_st(" not in impl, name
        assert "snapshot_depth" not in impl and "tp_cg_redo(" not in impl and "tp_rc = 1" not in impl, name


def test_reads_and_stores_are_list_operations_in_source_order(dw_c):
    c, _ = dw_c
    impl = _function_text(c, "static int tp_impl_adv3(")
    ops = re.findall(r"tp_list_(ld|st)_f64\(l_(\w+)->list", impl)
    assert ops == [("ld", "a"), ("st", "a"), ("ld", "b"), ("ld", "a"), ("ld", "c"), ("st", "b"),
                   ("ld", "c"), ("st", "c"), ("ld", "b")], ops
    ops = re.findall(r"tp_list_(ld|st)_i64\(l_(\w+)->list", _function_text(c, "static int tp_impl_fill3i("))
    assert ops == [("st", "c"), ("st", "a"), ("st", "b")], ops


def _exit_tail(impl: str, label: str) -> str:
    start = impl.index(f"{label}: {{")
    tail = impl[start:impl.index(f"{label}_e: ;", start)]
    assert "tp_my->result = PyObject_Vectorcall(tp_ofn" in tail and "goto tp_exit;" in tail
    return tail


def test_a_type_mismatch_leaves_through_the_statement_exit(dw_c):
    c, _ = dw_c
    impl = _function_text(c, "static int tp_impl_adv3(")
    # statements of adv3: 0 s = 0.0, 1 for, 2 a[i] = .., 3 b[i] -= .., 4 c[i] += .., 5 s += b[i], 6 return
    exits = []
    reads = re.findall(r"tp_s = tp_list_ld_f64\([^\n]*\n\s*if \(tp_s == 1\) goto (tp_sx\d+);[^\n]*\n"
                       r"\s*if \(tp_s != 0\) \{ tp_rc = tp_s; goto tp_exit; \}", impl)
    for label in reads:
        tail = _exit_tail(impl, label)
        exits.append(re.search(r'"__tp_resume_adv3_s(\d+)"', tail).group(1))
        # nothing to write back, no section to end, no snapshot to release
        assert "_array_exit" not in tail and "tp_cs_end" not in tail and "snapshot_depth" not in tail
    assert exits == ["2", "3", "3", "3", "4", "5"], exits
    assert impl.count("tp_list_ld_f64(") == 6
    # one shared tail per IR statement: 4 statements read, so 4 tails
    assert len(set(reads)) == 4 and impl.count("PyObject_Vectorcall(tp_ofn") == 4


def test_a_global_is_read_at_its_use(dw_c):
    c, _ = dw_c
    impl = _function_text(c, "static int tp_impl_glob3(")
    loop = impl[impl.index("for ("):]
    assert loop.count("tp_gcache_global(") == 2, impl
    # its missing name or other type leaves through the statement exit, as an element read does
    for mt in re.finditer(r"tp_gcache_global\(.*\n.*PyErr_Clear\(\); tp_s = 1; \}\n.*\n\s*if \(tp_s == 1\) goto (tp_sx\d+);",
                          loop):
        assert '"__tp_resume_glob3_s' in _exit_tail(impl, mt.group(1))
    assert len(re.findall(r"PyErr_Clear\(\); tp_s = 1; \}", loop)) == 2


def test_the_writer_polls_like_a_function_without_lists(dw_c):
    c, _ = dw_c
    impl = _function_text(c, "static int tp_impl_adv3(")
    assert "tp_poll(tp_t, tp_st->breaker)" in impl and "tp_poll_slow(" in impl
    assert "#152: poll inside a snapshot holder" not in impl
    assert impl.index("tp_osr *tp_my = tp_t->osr; tp_t->osr = NULL;") < impl.index("tp_poll(tp_t")


def test_a_fast_twin_counts_its_frames_around_the_continuation(dw_c):
    c, out = dw_c
    if "static inline int tp_fimpl_adv3(" not in c:
        pytest.skip("adv3 has no fast twin")
    twin = _function_text(c, "static inline int tp_fimpl_adv3(")
    for mt in re.finditer(r"tp_my->result = PyObject_Vectorcall", twin):
        before = twin[:mt.start()]
        assert before.rstrip().endswith("tp_depth_py_begin(tp_t, tp_fd);"), before[-200:]


def test_one_and_two_list_functions_keep_the_copy_in_window(tmp_path):
    m, p = _lower(tmp_path, '''
        @compiled
        def two(a: list[float], b: list[float]) -> None:
            a[0] = b[0] + 1.0
    ''', "two.py")
    out, diags = verify.verify(m)
    c = cgen.generate(out, p, "two.py")
    w = _function_text(c, "static PyObject *tp_wrap_two(")
    assert "tp_cs_begin(&tp_lock" in w and "tp_f64_array_enter" in w
    assert "tp_list_st_" not in c and "tp_list_ld_" not in c


# --- the compiled extension (CI: needs a C compiler) --------------------------------------------------------

def _build(tmp_path_factory, name, skip_statement=False, runtime=None):
    from test_cgen import need_runtime, runtime_dir
    from typedpython import cbuild
    need_runtime()
    d = tmp_path_factory.mktemp(name)
    path = d / f"{name}.py"
    path.write_text(SOURCE)
    saved = resume._reexecute_the_statement
    if skip_statement:
        resume._reexecute_the_statement = lambda: False
    try:
        proved, diags = verify.verify(frontend.lower(path))
    finally:
        resume._reexecute_the_statement = saved
    assert set(WRITERS) <= {f.name for f in proved.functions}, diags
    c = cgen.generate(proved, path, path.name)
    rt = runtime_dir()
    if runtime is not None:
        rt = d / "rt"
        rt.mkdir()
        (rt / "tp_runtime.h").write_text(runtime((runtime_dir() / "tp_runtime.h").read_text()))
    so = cbuild.build(c, name, d / "out", runtime_dir=rt)
    ref: dict = {}
    exec(compile(SOURCE, path.name, "exec"), ref)
    return cbuild.load(name, so), ref, path


@pytest.fixture(scope="module")
def dwmod(tmp_path_factory):
    return _build(tmp_path_factory, "dwmod")


def _run(fn, args):
    try:
        return ("ok", fn(*args))
    except Exception as e:      # noqa: BLE001 - compared by type and text
        return ("raise", type(e).__name__, str(e))


def _state(args):
    return [[(type(x).__name__, x) for x in a] for a in args if isinstance(a, list)]


def _both(mod, ref, name, make):
    a, b = make(), make()
    got, want = _run(getattr(mod, name), a), _run(ref[name], b)
    return (got, _state(a)), (want, _state(b))


def _adv_make(lst, pos, value, n=4, lens=(4, 4, 4)):
    def make():
        ls = [[0.5 + i for i in range(lens[0])], [2.0 + i for i in range(lens[1])],
              [1.5 * (i + 1) for i in range(lens[2])]]
        if lst is not None:
            ls[lst][pos] = value
        return (*ls, n)
    return make


@pytest.mark.parametrize("value", [3, 2 ** 70, True])
@pytest.mark.parametrize("pos", range(4))
@pytest.mark.parametrize("lst", range(3))
def test_an_element_of_another_type_anywhere_gives_the_interpreters_run(dwmod, lst, pos, value):
    m, ref, _ = dwmod
    got, want = _both(m, ref, "adv3", _adv_make(lst, pos, value))
    assert got == want


@pytest.mark.parametrize("name", ["ifw", "wh", "root3"])
@pytest.mark.parametrize("pos", range(3))
def test_planted_elements_in_if_while_and_math_statements(dwmod, name, pos):
    m, ref, _ = dwmod

    def make():
        a, b, c = [3.0, -1.0, 2.0], [0.0, 0.0, 0.0], [5.0, 5.0, 5.0]
        a[pos] = 2
        return a, b, c
    got, want = _both(m, ref, name, make)
    assert got == want


def test_root3_raises_where_the_interpreter_does(dwmod):
    m, ref, _ = dwmod
    for a in ([4.0, -1.0, 2.0], [4.0, 0.0, 2.0]):          # math domain error; ZeroDivisionError
        got, want = _both(m, ref, "root3", lambda: (list(a), [0.0] * 3, [0.0] * 3))
        assert got == want and got[0][0] == "raise", got


@pytest.mark.parametrize("lens", [(4, 4, 2), (4, 1, 4), (2, 4, 4), (0, 0, 0)])
@pytest.mark.parametrize("planted", [False, True])
def test_a_short_list_raises_index_error_after_partial_effects(dwmod, lens, planted):
    m, ref, _ = dwmod
    got, want = _both(m, ref, "adv3", _adv_make(0 if planted else None, 0, 7, 4, lens))
    assert got == want and got[0][0] == "raise" and got[0][1] == "IndexError", got


@pytest.mark.parametrize("value", [3, 2.5, "x", None, 2 ** 80])
def test_the_global_rebound_between_calls(dwmod, value):
    m, ref, _ = dwmod
    make = lambda: ([0.0] * 3, [1.0, 2.0, 3.0], [0.0] * 3)      # noqa: E731
    old = m.G, ref["G"]
    try:
        m.G = ref["G"] = value
        got, want = _both(m, ref, "glob3", make)
        assert got == want, (got, want)
        m.G = ref["G"] = 2.0
        got, want = _both(m, ref, "glob3", make)
        assert got == want and got[0] == ("ok", None)
    finally:
        m.G, ref["G"] = old


def test_the_global_deleted_raises_name_error_from_the_interpreted_statement(dwmod):
    m, ref, _ = dwmod
    make = lambda: ([0.0] * 3, [1.0, 2.0, 3.0], [0.0] * 3)      # noqa: E731
    old = m.G, ref["G"]
    try:
        del m.G
        del ref["G"]
        got, want = _both(m, ref, "glob3", make)
        assert got == want and got[0][1] == "NameError", got
        a, b, c = make()
        try:
            m.glob3(a, b, c)
        except NameError as e:
            fs = traceback.extract_tb(e.__traceback__)[-1]
            assert fs.name == "glob3", fs
    finally:
        m.G, ref["G"] = old


def test_aliased_lists(dwmod):
    m, ref, _ = dwmod
    for shape in ("xxx", "xyx", "xxy", "yxx"):
        def make(shape=shape):
            x, y = [1.0, 2.0, 3.0, 4.0], [0.5, 0.25, 0.125, 2.0]
            pick = {"x": x, "y": y}
            return (*[pick[s] for s in shape], 4)
        a, b = make(), make()
        got, want = _run(m.adv3, a), _run(ref["adv3"], b)
        assert got == want and _state(a) == _state(b), shape


def test_empty_lists_and_negative_indices(dwmod):
    m, ref, _ = dwmod
    for args in (([], [], [], 0), ):
        got, want = _both(m, ref, "adv3", lambda args=args: tuple(list(x) if isinstance(x, list) else x
                                                                     for x in args))
        assert got == want
    for make in (lambda: ([1.0, 2.0], [3.0, 4.0], [5.0, 6.0]),
                 lambda: ([1.0], [3.0, 4.0], [5.0, 6.0, 7.0]),
                 lambda: ([], [3.0, 4.0], [5.0, 6.0]),          # a[-1] = ...: IndexError on the store
                 lambda: ([1.0], [3.0], [5.0, 6.0]),            # b[-2]: IndexError after a store
                 lambda: ([1.0, 2.0], [3.0, 4], [5.0, 6.0])):   # an int read by b[-2] += 1.0
        got, want = _both(m, ref, "neg3", make)
        assert got == want, (got, want)


@pytest.mark.parametrize("k", [-1, 0, 2, 5])
def test_a_pure_callee_overflow_leaves_through_the_statement_exit(dwmod, k):
    m, ref, _ = dwmod
    got, want = _both(m, ref, "callw", lambda: ([0.0] * 6, [float(i) for i in range(6)], [0.0] * 6, k))
    assert got == want


def test_non_list_arguments_deopt_at_entry(dwmod):
    m, ref, _ = dwmod
    for args in (((1.0,), [1.0], [1.0], 1), ([1.0], [1.0], None, 1)):
        assert _run(m.adv3, args) == _run(ref["adv3"], args)


@pytest.mark.skipif(not NBODY.exists(), reason="benchmarks not present")
def test_nbody_matches_the_interpreter(tmp_path):
    from test_cgen import need_runtime
    from typedpython import pipeline
    need_runtime()
    p = tmp_path / "nbody_dw.py"
    p.write_text(decorate_compiled(NBODY.read_text()))
    result = pipeline.compile_module(p, tmp_path / "out")
    assert {"advance", "energy", "offset_momentum"} <= {f.name for f in result.module.functions}, \
        result.skipped
    m = pipeline.load(result)
    ref: dict = {"__name__": "nbody_ref"}
    exec(compile(NBODY.read_text(), "nbody_ref.py", "exec"), ref)

    def run(ns):
        bodies = [ns[f"make_{k}"]() for k in ("x", "y", "z", "vx", "vy", "vz", "mass")]
        ns["offset_momentum"](bodies[3], bodies[4], bodies[5], bodies[6])
        e0 = ns["energy"](*bodies)
        ns["advance"](0.01, 200, *bodies)
        return e0, ns["energy"](*bodies), bodies
    before = m.__typedpython_deopts__
    assert run(vars(m)) == run(ref)
    assert m.__typedpython_deopts__ == before


def test_stores_reach_lock_free_readers_in_program_order(dwmod):
    """fill3i stores c[0], a[0], b[0] (small ints, immortal: a reader's lock-free read never falls back
    to the list's lock). An interpreted reader that sees a call's a[0] must then see that call's c[0]
    or a later one: c[0] >= a[0] in every interleaving."""
    import threading
    m, _, _ = dwmod
    a, b, c = [0], [0], [0]
    stop = [False]
    bad = []

    def writer():
        for r in range(20):
            for v in range(1, 200):
                m.fill3i(a, b, c, v + 0 * r)
            a[0] = b[0] = c[0] = 0
        stop[0] = True

    def reader():
        while not stop[0]:
            x = a[0]
            y = c[0]
            if y < x:
                bad.append((x, y))
    ts = [threading.Thread(target=writer)] + [threading.Thread(target=reader) for _ in range(3)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    assert bad == []


def test_the_mutant_exit_that_skips_its_statement_is_caught(tmp_path_factory):
    m, ref, _ = _build(tmp_path_factory, "dwskip", skip_statement=True)
    caught = 0
    for lst in range(3):
        for pos in range(4):
            got, want = _both(m, ref, "adv3", _adv_make(lst, pos, 3))
            caught += got != want
    assert caught > 0, "a continuation that starts after the statement it leaves was not caught"


def test_the_mutant_read_without_the_type_check_is_caught(tmp_path_factory):
    check = ("    if (!PyFloat_CheckExact(v)) {\n        Py_DECREF(v);\n        return 1;\n    }\n"
             "    *out = PyFloat_AS_DOUBLE(v);\n")

    def strip(h):
        assert h.count(check) == 1
        return h.replace(check, "    *out = PyFloat_AS_DOUBLE(v);\n")
    m, ref, _ = _build(tmp_path_factory, "dwnocheck", runtime=strip)
    got, want = _both(m, ref, "adv3", _adv_make(1, 2, 3))
    assert got != want, "a read that does not check the element type was not caught"
