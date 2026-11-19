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

Polls inside an epoch-checked call (leader decision 2026-11-15: postponing signals and stop-the-world
requests until the call returns changes Python's behaviour and is not accepted; this design is an agent
proposal, not owner-confirmed). The call no longer raises the snapshot depth: every poll in its call tree
runs the eval breaker (`tp_epoch_poll_slow`), then compares the epoch with the one the entering frame read
(`tp_tls.tree_epoch`) and deopts the call when it moved, so a loop whose exit depends on a global a handler
rebinds ends; an exception the breaker raised (KeyboardInterrupt from a handler) is kept by the end check
even when the epoch moved (`tp_tls.poll_raised`). A function with an entry global inside an epoch tree runs
under the epoch itself, so it polls too. The behaviour tests for this build with `-DTP_TEST_EG_HOOK` and use
a C callable hook (`functools.partial(_thread.interrupt_main, SIGINT)`): it makes SIGINT pending without
running a bytecode, so the handler can run only at a poll inside the compiled call, or after it returns.
Red before this change, observed locally: the generated-C tests marked "(poll)" below. Red before this
change, reasoned from the generated C of 552987f6 and not run (CI): `signal` gives 11.0 (the handler ran
after the call), `stop` hangs (no poll in the call). The mutant (`cgen._poll_epoch_check` off) must lose the
KeyboardInterrupt when the handler rebinds G first (the end check clears it and redoes) and must not end
`spin`.
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
    Assign, BinOp, BinOpKind, Break, Call, CallObject, ClassDecl, Compare, CompareKind, Const, ExprStmt,
    FieldGet, ForRange, Function, If, Local, Module, Param, Return, Type, Unbox, While,
)

F64, OBJ, I64, BOOL = Type.F64, Type.OBJ, Type.I64, Type.BOOL

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

    STOP = False
    Q = 0

    def acc(o, n):
        s = 0.0
        for i in range(n):
            s = s + o.x
        return s + G

    def spin(n):
        i = 0
        while i < n:
            if STOP:
                break
            i = i + 1
        return i + Q

    def dacc(cb, o, n):
        cb()
        return acc(o, n)
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
        # polls inside an epoch-checked call: one entry global and a field read in a range loop
        Function(name="acc", params=(cell, Param("n", I64)), returns=F64, locals={"s": F64, "i": I64},
                 body=(Assign("s", Const(F64, 0.0)),
                       ForRange("i", Const(I64, 0), Local(I64, "n"), Const(I64, 1),
                                (Assign("s", _add(Local(F64, "s"), _field_x())),)),
                       Return(_add(Local(F64, "s"), Local(F64, "G")))),
                 pure=True, may_deopt=True, source_line=1, entry_globals=(Param("G", F64),)),
        # a loop whose exit depends on an entry global (two names: runs under the epoch); bounded, so the
        # counted impl runs its fast twin
        Function(name="spin", params=(Param("n", I64),), returns=I64, locals={"i": I64},
                 body=(Assign("i", Const(I64, 0)),
                       While(Compare(BOOL, CompareKind.LT, Local(I64, "i"), Local(I64, "n")),
                             (If(Local(BOOL, "STOP"), (Break(),)),
                              Assign("i", BinOp(I64, BinOpKind.ADD, Local(I64, "i"), Const(I64, 1))))),
                       Return(BinOp(I64, BinOpKind.ADD, Local(I64, "i"), Local(I64, "Q")))),
                 pure=True, may_deopt=True, source_line=1,
                 entry_globals=(Param("STOP", BOOL), Param("Q", I64))),
        # an impure caller: the Call of acc is the frame that enters the epoch tree
        Function(name="dacc", params=(Param("cb", OBJ), Param("o", OBJ), Param("n", I64)), returns=F64,
                 locals={}, body=(ExprStmt(CallObject(OBJ, Local(OBJ, "cb"), (), ())),
                                  Return(Call(F64, "acc", (Local(OBJ, "o"), Local(I64, "n")), redo=True))),
                 pure=False, may_deopt=False, source_line=1),
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
    # (poll) an exception is redone only if no poll raised it: a handler's KeyboardInterrupt is kept
    err = w.index("if (tp_s < 0 && !tp_px && !tp_eg_unchanged(tp_ge)) { PyErr_Clear(); goto tp_deopt; }", call)
    assert ok < err < w.index("if (tp_s < 0) goto tp_error;", call)


@pytest.mark.parametrize("name", ["case1", "case2", "case3", "err2", "nog", "acc", "spin", "callee_h"])
def test_poll_an_epoch_checked_call_holds_no_snapshot_depth_and_publishes_its_epoch(c_text, name):
    # (poll) leader decision 2026-11-15: polls inside the call run the eval breaker, so the wrapper does not
    # raise the snapshot depth; it publishes the epoch it read (and a cleared poll flag) for the polls of the
    # call tree, and restores the outer values before it looks at the result
    w = _wrapper(c_text, name)
    assert "snapshot_depth" not in w, w
    call = w.index(f"tp_impl_{name}(")
    assert w.index("tp_t->tree_epoch = tp_ge; tp_t->poll_raised = 0;") < call
    assert call < w.index("int tp_px = tp_t->poll_raised;") < w.index(
        "tp_t->tree_epoch = tp_te; tp_t->poll_raised = tp_tx;") < w.index("tp_eg_unchanged(tp_ge)")


@pytest.mark.parametrize("name", ["acc", "spin", "callee_h"])
def test_poll_a_function_with_entry_globals_that_runs_under_the_epoch_polls(c_text, name):
    # (poll) item 2: its entry globals no longer make it a snapshot holder; its impl polls at entry and the
    # result of a poll is propagated as it is (-1 an exception, 1 the epoch moved: a deopt)
    impl = _impl(c_text, name)
    assert "snapshot_depth" not in impl, impl
    assert "{ int tp_pq = tp_epoch_poll(tp_t, tp_st->breaker); if (tp_pq != 0) { tp_rc = tp_pq; goto tp_exit; } }" \
        in impl, impl


def test_poll_the_loops_of_an_epoch_tree_poll_with_the_epoch(c_text):
    # (poll) items 4 and 6: a chunked range loop and a while loop, counted impl and fast twin
    acc = _impl(c_text, "acc")
    assert "{ int tp_pq = tp_epoch_poll_slow(tp_t, tp_st->breaker); if (tp_pq != 0) { tp_rc = tp_pq; goto tp_exit; } }" \
        in acc, acc
    assert "tp_poll_slow(" not in acc and "tp_poll(" not in acc, acc
    head = "static inline int tp_fimpl_spin(PyObject *tp_module, int tp_fd"
    start = c_text.index(head)
    while c_text[start:c_text.index("\n", start)].endswith(";"):      # the definition, not the declaration
        start = c_text.index(head, start + 1)
    twin = c_text[start:c_text.index("\n}\n", start)]
    assert ("if (tp_pc_tick(&tp_pc)) { tp_depth_py_begin(tp_t, tp_fd); tp_s = tp_epoch_poll_slow(tp_t, "
            "tp_st->breaker); tp_depth_py_end(tp_t, tp_fd); if (tp_s != 0) { tp_rc = tp_s; goto tp_exit; } }") in twin, twin
    assert "snapshot_depth" not in twin, twin


def test_poll_a_callee_without_entry_globals_in_an_epoch_tree_polls_with_the_epoch():
    # (poll) item 5: a callee with no entry global of its own, inside the tree of `root`, loops on its
    # argument; its loop poll compares the tree's epoch (it is pure, so a deopt return is legal)
    import tempfile
    from pathlib import Path
    body = (Assign("i", Const(I64, 0)),
            While(Compare(BOOL, CompareKind.LT, Local(I64, "i"), Local(I64, "n")),
                  (Assign("i", BinOp(I64, BinOpKind.ADD, Local(I64, "i"), Const(I64, 1))),)),
            Return(Local(I64, "i")))
    loop = Function(name="loop", params=(Param("n", I64),), returns=I64, locals={"i": I64}, body=body,
                    pure=True, may_deopt=True, source_line=1)
    root = Function(name="root", params=(), returns=I64, locals={},
                    body=(Return(BinOp(I64, BinOpKind.ADD, Call(I64, "loop", (Local(I64, "A"),)), Local(I64, "B"))),),
                    pure=True, may_deopt=True, source_line=1, entry_globals=(Param("A", I64), Param("B", I64)))
    with tempfile.TemporaryDirectory() as d:
        p = Path(d) / "lpmod.py"
        p.write_text("A = 1\nB = 2\ndef loop(n):\n    i = 0\n    while i < n:\n        i = i + 1\n    return i\n"
                     "def root():\n    return loop(A) + B\n")
        c = cgen.generate(Module(name="lpmod", functions=(loop, root)), p, p.name)
    assert "tp_epoch_poll_slow(" in _impl(c, "loop") and "tp_epoch_poll(" in _impl(c, "loop")
    w = _wrapper(c, "loop")      # not a root: its wrapper hides any outer tree from it
    assert "tp_eg_unchanged(" not in w
    call = w.index("tp_impl_loop(")
    assert w.index("uint64_t tp_te = tp_t->tree_epoch; tp_t->tree_epoch = 0;") < call \
        < w.index("tp_t->tree_epoch = tp_te;"), w


def test_two_entry_globals_are_read_at_one_instant(c_text):
    w = _wrapper(c_text, "case3")
    assert w.count("tp_eg_snapshot(") == 1 and "tp_eg_snapshot(tp_dict, tp_gn, 2," in w, w


def test_an_impure_caller_reads_the_epoch_in_the_dict_section_around_the_call(c_text):
    # drive is impure: the epoch is read at its Call of case1, whose tree reads G and H
    impl = _impl(c_text, "drive")
    snap = impl.index("tp_eg_snapshot(tp_dict")
    call = impl.index("tp_impl_case1(", snap)
    # (poll) no snapshot depth around the Call: the callee's polls run the eval breaker; the Call publishes
    # the epoch it read for them and restores the outer tree's values right after
    assert "snapshot_depth" not in impl, impl
    pub = impl.index("tp_t->poll_raised = 0;", snap)
    assert pub < call and "tp_t->tree_epoch = tp_t" in impl[snap:call]
    after = impl[call:]
    assert after.index("= tp_t->poll_raised; tp_t->tree_epoch = ") < after.index("tp_eg_unchanged(")
    assert "tp_cg_redo(" in after                                     # a change redoes the callee


def test_poll_an_impure_caller_keeps_an_exception_a_poll_raised(c_text):
    # (poll) the Call site's end check clears an exception only when no poll raised it
    impl = _impl(c_text, "dacc")
    call = impl.index("tp_impl_acc(")
    line = [x for x in impl[call:].splitlines() if "PyErr_Clear(); tp_s = 1;" in x]
    assert len(line) == 1 and "tp_s < 0 && !tp_t" in line[0] and "&& !tp_eg_unchanged(" in line[0], line


def test_poll_a_wrapper_that_reaches_an_epoch_tree_member_hides_the_outer_tree(c_text):
    # (poll) item 5: a finalizer can run any wrapper inside an epoch tree; one whose call tree reaches a
    # member of an epoch tree (here drive, impure) sets the tree epoch to 0 around its impl and restores it,
    # so no poll under it compares a tree it is not part of (an impure caller cannot take a poll's deopt)
    w = _wrapper(c_text, "drive")
    call = w.index("tp_impl_drive(")
    assert w.index("uint64_t tp_te = tp_t->tree_epoch; tp_t->tree_epoch = 0;") < call \
        < w.index("tp_t->tree_epoch = tp_te;"), w
    # a function outside every epoch tree is not touched (the benchmarks' generated C stays the same)
    for name in ("usek",):
        assert "tree_epoch" not in _wrapper(c_text, name) and "tp_epoch_poll" not in _impl(c_text, name)


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
    for name in ("usek",):
        w = _wrapper(c_text, name)
        assert "tp_global(" in w and "tp_eg_" not in w and "snapshot_depth" not in w, w
    assert '"K"' not in c_text[c_text.index("tp_watch_names[]"):][:300]


def test_poll_one_entry_global_inside_an_epoch_tree_runs_under_the_epoch_itself(c_text):
    # (poll) callee_h (entry global H) is in the trees of case1 and nog: there it must poll (item 2), which a
    # snapshot holder does not do, so it runs under the epoch itself wherever it is entered from outside
    w = _wrapper(c_text, "callee_h")
    assert "tp_global(" not in w and "tp_eg_snapshot(tp_dict, tp_gn, 1," in w and "tp_eg_unchanged(tp_ge)" in w, w


def test_cgen_has_one_mutation_point_for_the_epoch_check(c_text, tmp_path, monkeypatch):
    monkeypatch.setattr(cgen, "_epoch_check", lambda: False)
    p = tmp_path / "egmut.py"
    p.write_text(SOURCE)
    mutant = cgen.generate(module("egmut"), p, p.name)
    assert "tp_eg_unchanged(" in c_text and "tp_eg_unchanged(" not in mutant


def test_poll_cgen_has_one_mutation_point_for_the_poll_epoch_check(c_text, tmp_path, monkeypatch):
    # (poll) off: no poll compares the epoch, nothing publishes it, and the end check clears any exception
    # after a change, as #414 did; the polls themselves still run (item 1 is not the mutation)
    monkeypatch.setattr(cgen, "_poll_epoch_check", lambda: False)
    p = tmp_path / "egpm.py"
    p.write_text(SOURCE)
    mutant = cgen.generate(module("egpm"), p, p.name)
    for text, want in ((c_text, True), (mutant, False)):
        assert ("tree_epoch" in text) is want and ("tp_epoch_poll" in text) is want
        assert ("poll_raised" in text) is want
    assert "if (tp_s < 0 && !tp_eg_unchanged(tp_ge)) { PyErr_Clear(); goto tp_deopt; }" in _wrapper(mutant, "acc")
    assert "snapshot_depth" not in _wrapper(mutant, "acc") and "snapshot_depth" not in _impl(mutant, "acc")
    assert "tp_poll_slow(tp_t, tp_st->breaker)" in _impl(mutant, "acc")


def test_poll_a_buffer_holder_in_an_epoch_tree_still_leaves_when_the_epoch_moved():
    # (poll) item 6: a function holding a buffer runs no user code (#149, unchanged: its polls do not run the
    # breaker), but its loops still compare the tree's epoch, so a loop on a stale snapshot ends
    import tempfile
    from pathlib import Path
    a = ir.ArrayParam("a", Type.F64_ARRAY, stored=False, buffer="array")
    f = Function(name="bsum", params=(a, Param("n", I64)), returns=F64, locals={"s": F64, "i": I64},
                 body=(Assign("s", Const(F64, 0.0)),
                       ForRange("i", Const(I64, 0), Local(I64, "n"), Const(I64, 1),
                                (Assign("s", _add(Local(F64, "s"), Local(F64, "G"))),)),
                       Return(_add(Local(F64, "s"), Local(F64, "H")))),
                 pure=True, may_deopt=True, source_line=1, entry_globals=(Param("G", F64), Param("H", F64)))
    with tempfile.TemporaryDirectory() as d:
        p = Path(d) / "bsmod.py"
        p.write_text("G = 1.0\nH = 1.0\ndef bsum(a, n):\n    s = 0.0\n    for i in range(n):\n        s = s + G\n"
                     "    return s + H\n")
        c = cgen.generate(Module(name="bsmod", functions=(f,)), p, p.name)
    impl = _impl(c, "bsum")
    assert "tp_t->snapshot_depth++;" in impl and "tp_epoch_poll" not in impl, impl
    assert "{ if (tp_epoch_moved(tp_t)) { tp_rc = 1; goto tp_exit; } }" in impl, impl


def test_poll_the_runtime_hides_the_tree_while_the_breaker_runs_and_flags_its_exception():
    # (poll) tp_runtime.h: the slow epoch poll sets the tree epoch to 0 around the breaker call (whatever a
    # handler runs is outside this tree), restores it, marks an exception it raised, and compares the epoch
    text = (runtime_dir() / "tp_runtime.h").read_text()
    body = text[text.index("static TP_COLD int tp_epoch_poll_slow(tp_tls *t, PyObject *breaker)"):]
    body = body[:body.index("\n}\n")]
    order = ["t->tree_epoch = 0;", "rc = tp_poll_slow(t, breaker);", "t->tree_epoch = e;",
             "t->poll_raised = 1;", "tp_eg_unchanged(e)"]
    assert [body.index(x) for x in order] == sorted(body.index(x) for x in order), body


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


# --- polls inside an epoch-checked call (compiled extension: CI) -------------------------------------
# Leader decision 2026-11-15 (agent proposal for the means, not owner-confirmed). The hook is a C callable:
# `functools.partial(_thread.interrupt_main, SIGINT)` makes SIGINT pending and returns without running a
# bytecode, so the Python handler can run only at a poll inside the compiled call (a poll's breaker call),
# or, when nothing in the call polls, right after it returns. The handler removes the hook first.

CHILD_POLL = r"""
import _thread, functools, importlib.machinery, importlib.util, json, signal, sys
path, name, keys = sys.argv[1], sys.argv[2], sys.argv[3].split(",")
loader = importlib.machinery.ExtensionFileLoader(name, path)
spec = importlib.util.spec_from_file_location(name, path, loader=loader)
m = importlib.util.module_from_spec(spec)
loader.exec_module(m)
log = []

def case(key):
    m.G, m.STOP, m.Q = 1.0, False, 0
    o = m.Cell(1.0)
    def set_x():                 # (a) state the rest of the call reads, no watched global
        o.x = 2.0
    def ki():                    # (b) the handler raises
        raise KeyboardInterrupt
    def rebind_ki():             # (b) a watched global first, then the exception
        m.G = 5.0
        raise KeyboardInterrupt
    def stop():                  # (c) the loop's exit
        m.STOP = True
    call, effect = {
        "signal": (lambda: m.acc(o, 10), set_x),
        "ki": (lambda: m.acc(o, 10), ki),
        "ki_rebind": (lambda: m.acc(o, 10), rebind_ki),
        "ki_impure": (lambda: m.dacc(lambda: None, o, 10), rebind_ki),
        "stop": (lambda: m.spin(1 << 62), stop),
    }[key]
    def handler(signum, frame):
        vars(m).pop("__tp_test_eg_hook__", None)
        log.append(signum)
        effect()
    del log[:]
    signal.signal(signal.SIGINT, handler)
    m.__tp_test_eg_hook__ = functools.partial(_thread.interrupt_main, signal.SIGINT)
    before = m.__typedpython_deopts__
    try:
        got = ["ok", call()]
    except KeyboardInterrupt:
        got = ["raise", "KeyboardInterrupt"]
    rep = {"got": got, "handled": len(log), "deopts": m.__typedpython_deopts__ - before}
    vars(m).pop("__tp_test_eg_hook__", None)
    signal.signal(signal.SIGINT, signal.default_int_handler)
    return rep

report = {"watched": m.__typedpython_class_info__()["Cell"]["watched"]}
for key in keys:
    report[key] = case(key)
print("REPORT " + json.dumps(report))
"""

# What the interpreter allows. The signal is pending before the call's first bytecode; the interpreter runs
# the handler at the first eval-breaker check (RESUME of acc / spin, or the first backward jump), so
# (a) acc(o, 10) is 10 * 2 + G = 21 (handler before the first addition) or 1 + 9 * 2 + G = 20 (after it),
# never 11 (the handler after the call); (b) the KeyboardInterrupt is raised by the call; (c) spin returns
# 0 or 1.
POLL_ALLOWED = {"signal": {("ok", 21.0), ("ok", 20.0)},
                "ki": {("raise", "KeyboardInterrupt")}, "ki_rebind": {("raise", "KeyboardInterrupt")},
                "ki_impure": {("raise", "KeyboardInterrupt")}, "stop": {("ok", 0), ("ok", 1)}}


def _run_poll(so, name: str, keys: str, timeout: float):
    try:
        proc = subprocess.run([sys.executable, "-c", CHILD_POLL, str(so), name, keys], capture_output=True,
                              text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return None                    # the call did not return
    assert proc.returncode == 0, f"child failed ({proc.returncode}):\n{proc.stdout}\n{proc.stderr}"
    line = [x for x in proc.stdout.splitlines() if x.startswith("REPORT ")][-1]
    rep = json.loads(line[len("REPORT "):])
    assert rep["watched"] is True, rep
    for k, v in rep.items():
        if isinstance(v, dict) and "got" in v:
            v["got"] = tuple(v["got"])
    return rep


@pytest.fixture(scope="module")
def egmod_so(tmp_path_factory):
    return _build(tmp_path_factory, "egpoll")


def test_a_signal_during_an_epoch_checked_call_runs_inside_it(egmod_so):
    # (a) the handler's store is seen by the rest of the call; red before (reasoned): 11.0, the handler
    # waited for the call to return
    rep = _run_poll(egmod_so, "egpoll", "signal", 120)
    r = rep["signal"]
    assert r["handled"] == 1 and r["got"] in POLL_ALLOWED["signal"] and r["deopts"] == 0, r
    assert r["got"] == ("ok", 21.0), r                 # the first chunk's poll, before the first addition


@pytest.mark.parametrize("key", ["ki", "ki_rebind", "ki_impure"])
def test_keyboard_interrupt_from_a_handler_inside_an_epoch_checked_call_propagates(egmod_so, key):
    # (b) also when the handler rebinds a watched global first (the epoch moved): the end check of the
    # wrapper (acc) or of the impure caller's Call (dacc) keeps the exception a poll raised
    rep = _run_poll(egmod_so, "egpoll", key, 120)
    r = rep[key]
    assert r["handled"] == 1 and r["got"] in POLL_ALLOWED[key] and r["deopts"] == 0, r


def test_a_loop_whose_exit_a_handler_rebinds_ends_with_the_interpreters_result(egmod_so):
    # (c) `while i < n: if STOP: break` with STOP read once at entry and n = 2**62: the handler rebinds STOP
    # at the first poll, the poll sees the epoch move and the call is redone interpreted, which stops at once;
    # red before (reasoned): no poll in the call, it does not return (timeout)
    rep = _run_poll(egmod_so, "egpoll", "stop", 120)
    assert rep is not None, "spin did not return"
    r = rep["stop"]
    assert r["handled"] == 1 and r["got"] in POLL_ALLOWED["stop"] and r["deopts"] == 1, r


def test_the_mutant_without_the_poll_epoch_check_loses_the_interrupt_and_does_not_end(tmp_path_factory,
                                                                                    monkeypatch):
    # the mutation point is what makes (b) and (c) pass: without the flag the end check clears the
    # KeyboardInterrupt after the rebinding and redoes the call (15.0 = 10 * 1 + 5, G read after the
    # handler); without the comparison at the poll spin keeps its stale STOP and runs on
    monkeypatch.setattr(cgen, "_poll_epoch_check", lambda: False)
    so = _build(tmp_path_factory, "egpmut")
    rep = _run_poll(so, "egpmut", "signal,ki,ki_rebind,ki_impure", 120)
    assert rep["signal"]["got"] == ("ok", 21.0), rep               # the polls still run the breaker
    assert rep["ki"]["got"] == ("raise", "KeyboardInterrupt"), rep  # epoch unchanged: kept anyway
    for key in ("ki_rebind", "ki_impure"):
        assert rep[key]["got"] == ("ok", 15.0) and rep[key]["deopts"] == 1, rep
    assert _run_poll(so, "egpmut", "stop", 20) is None
