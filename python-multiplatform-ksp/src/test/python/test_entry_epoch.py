"""#414 (SPEC N-8 "Entry globals and later shared reads", design/typedpython.md 4.3.6 아): an entry global
is read once at entry (a callee's at the Call), the interpreter reads it at every use.

Agent proposal, not confirmed. A pure function whose call tree reads more than one entry-global name, or
one and any other shared state, and holds no list copy, runs under the dict-watcher epoch: the wrapper
reads the epoch with its own entry globals in the module dict's critical section (`tp_eg_snapshot`), and
before the call returns a result or raises, the epoch must be unchanged; changed, the call is redone
interpreted (no effect has happened, and nothing in the call ran user code). An impure caller of such a
function does the same around the Call and redoes the callee. One entry-global name and no other shared
read keeps the plain entry read (wordfreq `next_state`).

The behaviour tests are deterministic, not timed: the module is built with `-DTP_TEST_EG_HOOK`, which
makes the wrapper of an epoch-checked function (and an impure caller, right before such a Call) run the
module-level `__tp_test_eg_hook__()` after the entry read and before the body. The hook plays another
thread B that rebinds G and then writes the state the function reads later. Each test states the results
the interleavings of B's two writes with the interpreted function's reads allow (derived from the program
order, not from running anything); the compiled result must be one of them.

Red before the change (reasoned from the generated C of 9c847a10; the hook did not exist there): every
wrapper read its entry globals with `tp_global` and returned the impl's result unchecked, so with the hook
at the same point `case1` and `drive` gave 21.0 (old G, new H), `case2` 21.0 (old G, new o.x) and `err2`
raised ZeroDivisionError (old G, new o.x), none of them an interleaving. The mutant test builds the new C
without the epoch check (`cgen._epoch_check`) and requires exactly those invalid results. The structure
tests run without a C compiler and were observed red before the implementation (see the report of #414).
Tests needing a compiled extension were not run by the agent that wrote them (AGENTS.md 10): CI.
"""
from __future__ import annotations

import json
import subprocess
import sys
import textwrap

import pytest

from test_cgen import need_runtime, runtime_dir
from typedpython import cgen, ir
from typedpython.ir import (
    BinOp, BinOpKind, Call, CallObject, ClassDecl, Const, ExprStmt, FieldGet, Function, Local, Module, Param,
    Return, Type, Unbox,
)

F64, OBJ, I64 = Type.F64, Type.OBJ, Type.I64

SOURCE = textwrap.dedent('''\
    """#414 test module: the interpreted reference of every compiled function."""

    class Cell:
        __slots__ = ("x",)

        def __init__(self, x):
            self.x = x

    G = 1.0
    H = 1.0
    K = 3.0
    J = 4.0

    def callee_h():
        return H

    def case1():
        return callee_h() * 10.0 + G

    def case2(o):
        return o.x * 10.0 + G

    def case3():
        return H * 10.0 + G

    def err2(o):
        return 100.0 / (o.x * 10.0 + G - 1.0)

    def drive(cb):
        cb()
        return case1()

    def usek(x):
        return x + K

    def nog():
        return callee_h() + J
    ''')

CELL = ClassDecl("Cell", ("x",), True)


def _fn(name, params, body, *, pure=True, may_deopt=False, entry=()):
    return Function(name=name, params=tuple(params), returns=F64, locals={}, body=tuple(body), pure=pure,
                    may_deopt=may_deopt, source_line=1, entry_globals=tuple(Param(n, F64) for n in entry))


def _add(a, b):
    return BinOp(F64, BinOpKind.ADD, a, b)


def _mul10(a):
    return BinOp(F64, BinOpKind.MUL, a, Const(F64, 10.0))


def _field_x():
    return Unbox(F64, FieldGet(OBJ, Local(OBJ, "o"), "Cell", "x"))


def functions():
    cell = Param("o", OBJ, cls="Cell")
    return (
        _fn("callee_h", [], [Return(Local(F64, "H"))], entry=("H",)),
        # the callee's entry guard runs at the Call, so the Call can deopt (may_deopt)
        _fn("case1", [], [Return(_add(_mul10(Call(F64, "callee_h", ())), Local(F64, "G")))],
            may_deopt=True, entry=("G",)),
        _fn("case2", [cell], [Return(_add(_mul10(_field_x()), Local(F64, "G")))], may_deopt=True, entry=("G",)),
        _fn("case3", [], [Return(_add(_mul10(Local(F64, "H")), Local(F64, "G")))], entry=("G", "H")),
        _fn("err2", [cell], [Return(BinOp(F64, BinOpKind.TRUEDIV, Const(F64, 100.0),
                                          BinOp(F64, BinOpKind.SUB, _add(_mul10(_field_x()), Local(F64, "G")),
                                                Const(F64, 1.0))))],
            may_deopt=True, entry=("G",)),
        _fn("drive", [Param("cb", OBJ)], [ExprStmt(CallObject(OBJ, Local(OBJ, "cb"), (), ())),
                                         Return(Call(F64, "case1", (), redo=True))], pure=False),
        _fn("usek", [Param("x", F64)], [Return(_add(Local(F64, "x"), Local(F64, "K")))], entry=("K",)),
        # no entry global of its own: H at the Call, J read at its use (a Global)
        _fn("nog", [], [Return(_add(Call(F64, "callee_h", ()), Unbox(F64, ir.Global(OBJ, "J"))))],
            may_deopt=True),
    )


def module(name: str) -> Module:
    return Module(name=name, functions=functions(), classes=(CELL,))


def _wrapper(c_text: str, name: str) -> str:
    start = c_text.index(f"static PyObject *tp_wrap_{name}(")
    return c_text[start:c_text.index("\n}\n", start)]


def _impl(c_text: str, name: str) -> str:
    """The counted impl's body (not its forward declaration, not the fast twin)."""
    head = f"static int tp_impl_{name}(PyObject *tp_module, PyThreadState"
    start = c_text.index(head)
    while c_text[start:c_text.index("\n", start)].endswith(";"):
        start = c_text.index(head, start + 1)
    return c_text[start:c_text.index("\n}\n", start)]


@pytest.fixture(scope="module")
def c_text(tmp_path_factory):
    d = tmp_path_factory.mktemp("egc")
    p = d / "egmod.py"
    p.write_text(SOURCE)
    return cgen.generate(module("egmod"), p, p.name)


# --- generated C (no C compiler) -------------------------------------------------------------------

@pytest.mark.parametrize("name", ["case1", "case2", "case3", "err2"])
def test_an_epoch_function_reads_its_entry_globals_with_the_epoch_and_checks_it_at_the_end(c_text, name):
    w = _wrapper(c_text, name)
    assert "tp_global(" not in w, w                                  # never the unlocked builtins-fallback read
    assert "if (tp_st->watch_id1 <= 0) goto tp_deopt;" in w          # unwatched: always interpreted
    snap = w.index("tp_eg_snapshot(")
    call = w.index(f"tp_impl_{name}(")
    assert snap < call
    ok = w.index("if (tp_s == 0 && !tp_eg_unchanged(tp_ge))", call)    # a result: epoch unchanged or redo
    err = w.index("if (tp_s < 0 && !tp_eg_unchanged(tp_ge)) { PyErr_Clear(); goto tp_deopt; }", call)
    assert ok < err < w.index("if (tp_s < 0) goto tp_error;", call)
    # no eval-breaker poll can run user code inside the call (its exception could not be redone)
    assert w.index("tp_t->snapshot_depth++;") < call < w.index("tp_t->snapshot_depth--;")


def test_two_entry_globals_are_read_at_one_instant(c_text):
    w = _wrapper(c_text, "case3")
    assert w.count("tp_eg_snapshot(") == 1 and "tp_eg_snapshot(tp_dict, tp_gn, 2," in w, w


def test_an_impure_caller_reads_the_epoch_in_the_dict_section_around_the_call(c_text):
    # drive is impure: the epoch is read at its Call of case1, whose tree reads G and H
    impl = _impl(c_text, "drive")
    snap = impl.index("tp_eg_snapshot(tp_dict")
    call = impl.index("tp_impl_case1(", snap)
    assert impl.index("tp_t->snapshot_depth++;", snap) < call
    assert "tp_eg_unchanged(" in impl[call:]
    assert "tp_cg_redo(" in impl[call:]                               # a change redoes the callee


def test_a_root_without_entry_globals_of_its_own_still_reads_the_epoch_in_the_dict_section(c_text):
    w = _wrapper(c_text, "nog")
    assert "tp_eg_snapshot(tp_dict, NULL, 0, NULL, &tp_ge);" in w, w
    assert "tp_eg_unchanged(tp_ge)" in w


def test_a_callee_entry_global_in_an_epoch_tree_is_read_from_the_module_dict_only(c_text):
    impl = _impl(c_text, "case1")
    assert "tp_global(" not in impl, impl
    assert "tp_eg_read(tp_dict, tp_st->names[" in impl


def test_every_entry_global_name_of_an_epoch_tree_is_watched(c_text):
    names = c_text[c_text.index("tp_watch_names[]"):][:300]
    assert '"G"' in names and '"H"' in names, names


def test_one_entry_global_and_no_other_shared_read_keeps_the_plain_entry_read(c_text):
    # case 4 of design 4.3.6 아 (wordfreq next_state): one instant, nothing to check
    for name in ("usek", "callee_h"):
        w = _wrapper(c_text, name)
        assert "tp_global(" in w and "tp_eg_" not in w and "snapshot_depth" not in w, w
    assert '"K"' not in c_text[c_text.index("tp_watch_names[]"):][:300]


def test_cgen_has_one_mutation_point_for_the_epoch_check(c_text, tmp_path, monkeypatch):
    monkeypatch.setattr(cgen, "_epoch_check", lambda: False)
    p = tmp_path / "egmut.py"
    p.write_text(SOURCE)
    mutant = cgen.generate(module("egmut"), p, p.name)
    assert "tp_eg_unchanged(" in c_text and "tp_eg_unchanged(" not in mutant


def _buf_writer(entry, extra=()):
    a = ir.ArrayParam("a", Type.F64_ARRAY, stored=True, buffer="array")
    value = Local(F64, entry[0])
    for n in entry[1:]:
        value = _add(value, Local(F64, n))
    return Function(name="bw", params=(a,), returns=Type.NONE, locals={},
                    body=(*extra, ir.StoreIndex("a", Const(I64, 0), value), Return()), pure=False,
                    may_deopt=False, source_line=1, entry_globals=tuple(Param(n, F64) for n in entry))


def test_an_impure_function_reads_two_entry_globals_at_one_instant(tmp_path):
    p = tmp_path / "bwmod.py"
    p.write_text("G = 1.0\nH = 1.0\ndef bw(a):\n    a[0] = G + H\n")
    c = cgen.generate(Module(name="bwmod", functions=(_buf_writer(("G", "H")),)), p, p.name)
    w = _wrapper(c, "bw")
    assert "tp_eg_snapshot(tp_dict, tp_gn, 2," in w and "tp_global(" not in w, w
    assert "tp_eg_unchanged(" not in w            # an impure function cannot redo: no check at the end


def test_cgen_refuses_an_impure_function_with_an_entry_global_and_a_later_shared_read(tmp_path):
    p = tmp_path / "bwmod.py"
    p.write_text("G = 1.0\nm = None\ndef bw(a):\n    m\n    a[0] = G\n")
    bad = _buf_writer(("G",), extra=(ExprStmt(ir.Global(OBJ, "m")),))
    with pytest.raises(cgen.CGenError, match="#414"):
        cgen.generate(Module(name="bwmod", functions=(bad,)), p, p.name)


# --- behaviour (compiled extension: CI) --------------------------------------------------------------
# Each run is a child interpreter: a process has eight dict-watcher ids (two reserved, shared by every
# extension), and an unwatched module runs every epoch function interpreted, which these tests must not
# mistake for the fix.

CHILD = r"""
import importlib.machinery, importlib.util, json, sys
path, name = sys.argv[1], sys.argv[2]
loader = importlib.machinery.ExtensionFileLoader(name, path)
spec = importlib.util.spec_from_file_location(name, path, loader=loader)
m = importlib.util.module_from_spec(spec)
loader.exec_module(m)

def reset():
    m.G, m.H = 1.0, 1.0
    vars(m).pop("__tp_test_eg_hook__", None)

def once(action):
    # B's writes, once: the interpreted redo runs no hook, and a second compiled call must not repeat them
    done = []
    def hook():
        if not done:
            done.append(1)
            action()
    m.__tp_test_eg_hook__ = hook
    return done

def outcome(call):
    try:
        return ["ok", call()]
    except ZeroDivisionError as e:
        return ["raise", type(e).__name__, str(e)]

o2, oe, oz = m.Cell(1.0), m.Cell(1.0), m.Cell(0.0)
def b_globals():                 # B: G = 2; H = 2
    m.G = 2.0
    m.H = 2.0
def b_field():                   # B: G = 2; o.x = 2
    m.G = 2.0
    o2.x = 2.0
def b_err():                     # B: G = 2; o.x = 0
    m.G = 2.0
    oe.x = 0.0
runs = {
    "case1": (lambda: m.case1(), b_globals),
    "drive": (lambda: m.drive(lambda: None), b_globals),
    "case2": (lambda: m.case2(o2), b_field),
    "err2": (lambda: m.err2(oe), b_err),
    "case3": (lambda: m.case3(), b_globals),
}
report = {"watched": m.__typedpython_class_info__()["Cell"]["watched"]}
reset()
before = m.__typedpython_deopts__
report["plain"] = [m.case1(), m.case2(m.Cell(1.0)), m.case3(), m.err2(m.Cell(1.0)), m.drive(lambda: None)]
report["plain_deopts"] = m.__typedpython_deopts__ - before
before = m.__typedpython_deopts__
report["plain_raise"] = outcome(lambda: m.err2(oz))
report["plain_raise_deopts"] = m.__typedpython_deopts__ - before
for key, (call, action) in runs.items():
    reset()
    done = once(action)
    before = m.__typedpython_deopts__
    got = outcome(call)
    report[key] = {"got": got, "ran": bool(done), "deopts": m.__typedpython_deopts__ - before}
reset()
print("REPORT " + json.dumps(report))
"""

# What the interleavings of B's two writes with the interpreted function's reads allow (program order):
# case1 reads H (in callee_h) and then G: H old -> G old or new (11, 12); H new -> G new (22). drive
# returns case1's value. case2 reads o.x and then G: the same shape. err2 reads o.x and then G:
# 100/10, 100/11, 100/1. case3 reads H and then G: 11, 12, 22.
THREE = {("ok", 11.0), ("ok", 12.0), ("ok", 22.0)}


def _zde() -> str:
    try:
        1.0 / 0.0
    except ZeroDivisionError as e:          # the text differs by CPython version (3.14: "division by zero")
        return str(e)
    raise AssertionError("no ZeroDivisionError")


ZDE = _zde()
ALLOWED = {"case1": THREE, "drive": THREE, "case2": THREE, "case3": THREE,
           "err2": {("ok", 10.0), ("ok", 100.0 / 11.0), ("ok", 100.0)}}


def _build(tmp_path_factory, name: str):
    from typedpython import cbuild
    need_runtime()
    d = tmp_path_factory.mktemp(name)
    p = d / f"{name}.py"
    p.write_text(SOURCE)
    c = cgen.generate(module(name), p, p.name)
    return cbuild.build(c, name, d / "out", runtime_dir=runtime_dir(),
                        flags=(*cbuild.DEFAULT_FLAGS, "-DTP_TEST_EG_HOOK"))


def _run(so, name: str) -> dict:
    proc = subprocess.run([sys.executable, "-c", CHILD, str(so), name], capture_output=True, text=True,
                          timeout=120)
    assert proc.returncode == 0, f"child failed ({proc.returncode}):\n{proc.stdout}\n{proc.stderr}"
    line = [x for x in proc.stdout.splitlines() if x.startswith("REPORT ")][-1]
    rep = json.loads(line[len("REPORT "):])
    for k, v in rep.items():
        if isinstance(v, dict) and "got" in v:
            v["got"] = tuple(v["got"])
    return rep


@pytest.fixture(scope="module")
def report(tmp_path_factory):
    rep = _run(_build(tmp_path_factory, "egmod"), "egmod")
    assert rep["watched"] is True, rep        # precondition: the module dict has a watcher
    return rep


def test_without_a_change_the_epoch_functions_run_compiled(report):
    assert report["plain"] == [11.0, 11.0, 11.0, 10.0, 11.0] and report["plain_deopts"] == 0, report


def test_an_exception_with_an_unchanged_epoch_is_raised_compiled(report):
    assert report["plain_raise"] == ["raise", "ZeroDivisionError", ZDE], report
    assert report["plain_raise_deopts"] == 0, report


@pytest.mark.parametrize("key", ["case1", "drive", "case2", "err2"])
def test_the_result_is_an_interleaving_and_the_call_was_redone(report, key):
    # case1: a callee's entry global read after the rebinding; drive: the same at an impure caller's
    # Call; case2: an object field; err2: an exception from the mixed state is redone, not raised
    r = report[key]
    assert r["ran"] and r["got"] in ALLOWED[key], r
    assert r["got"] == ("ok", 100.0 if key == "err2" else 22.0) and r["deopts"] == 1, r


def test_case3_two_entry_globals_from_one_snapshot(report):
    # both are read in one critical section, before the hook; B's writes then move the epoch
    r = report["case3"]
    assert r["ran"] and r["got"] in ALLOWED["case3"], r


def test_the_mutant_without_the_epoch_check_is_caught(tmp_path_factory, monkeypatch):
    monkeypatch.setattr(cgen, "_epoch_check", lambda: False)
    rep = _run(_build(tmp_path_factory, "egmut"), "egmut")
    assert rep["watched"] is True, rep
    keys = ("case1", "drive", "case2", "err2")
    assert all(rep[k]["ran"] for k in keys), rep
    bad = {k: rep[k]["got"] for k in keys if rep[k]["got"] not in ALLOWED[k]}
    # exactly the outcomes no interleaving gives (the old G with B's later write)
    assert bad == {"case1": ("ok", 21.0), "drive": ("ok", 21.0), "case2": ("ok", 21.0),
                   "err2": ("raise", "ZeroDivisionError", ZDE)}, rep
