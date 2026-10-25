"""SPEC N-8 / #152: a snapshot-holding function polls inside long loops and, when a list or an entry
global changed at the poll, leaves for the interpreter at that iteration (an OSR-style exit).

Deterministic: the module is built with TP_POLL_INTERVAL=32 and TP_TEST_SNAP_HOOK, so a module-level
`__tp_test_snap_hook__()` runs at every poll of a snapshot holder, in the window where its lists are
released, exactly where another thread or a signal handler could run. The reference for each case is
the interpreted function with the same hook called at the same iterations (every 32nd, from the second
chunk on), so compiled and interpreted must agree on every result, list and exception.
"""
from __future__ import annotations

import textwrap

import pytest

from test_cgen import need_runtime, runtime_dir
from typedpython import cbuild, cgen, frontend, verify

SOURCE = textwrap.dedent('''
    # typedpython: compiled

    SCALE = 2.0


    def scale_all(x: list[float]) -> float:
        s = 0.0
        for i in range(len(x)):
            x[i] = x[i] * SCALE
            s += x[i]
        return s


    def nested(x: list[float], n: int) -> int:
        hits = 0
        for r in range(n):
            for i in range(len(x)):
                if x[i] < 0.0:
                    continue
                hits += 1
                if hits > 100000:
                    break
            hits += 1000
        return hits


    def countdown(x: list[int]) -> int:
        k = 0
        total = 0
        while k < len(x):
            total += x[k]
            k += 1
        return total
''')

# The interpreted references: the same bodies, with the hook at the iterations the compiled poll runs
# it (the top of every 32nd iteration of a for loop, from the second chunk on; every 32nd iteration of
# a while loop, counting down from the function's entry).
REFERENCE = textwrap.dedent('''
    SCALE = 2.0
    HOOK = None


    def scale_all(x):
        s = 0.0
        for i in range(len(x)):
            if i and i % 32 == 0:
                HOOK()
            x[i] = x[i] * SCALE
            s += x[i]
        return s


    def nested(x, n):
        hits = 0
        for r in range(n):
            for i in range(len(x)):
                if i and i % 32 == 0:
                    HOOK()
                if x[i] < 0.0:
                    continue
                hits += 1
                if hits > 100000:
                    break
            hits += 1000
        return hits


    def countdown(x):
        k = 0
        total = 0
        tick = 32
        while True:
            tick -= 1
            if tick == 0:
                tick = 32
                HOOK()
            if not k < len(x):
                break
            total += x[k]
            k += 1
        return total
''')


@pytest.fixture(scope="module")
def mods(tmp_path_factory):
    need_runtime()
    d = tmp_path_factory.mktemp("osr")
    path = d / "osrmod.py"
    path.write_text(SOURCE)
    proved, _ = verify.verify(frontend.lower(path))
    c = cgen.generate(proved, path, path.name)
    so = cbuild.build(c, "osrmod", d / "out", runtime_dir=runtime_dir(),
                      flags=(*cbuild.DEFAULT_FLAGS, "-DTP_POLL_INTERVAL=32", "-DTP_TEST_SNAP_HOOK"))
    m = cbuild.load("osrmod", so)
    ref: dict = {}
    exec(compile(REFERENCE, "reference", "exec"), ref)
    return m, ref, c


def _hook_at(calls: int, action):
    """A hook that runs `action` on its `calls`-th call (1-based) and counts every call."""
    state = {"n": 0}

    def hook():
        state["n"] += 1
        if state["n"] == calls:
            action()
    return hook, state


def _both(mods, fname, make_args, calls, action_for):
    """Run compiled and interpreted with a hook firing `action_for(args, module_globals)` at the
    `calls`-th poll; return ((result or exception, args), ...) for both."""
    m, ref, _ = mods
    out = []
    for kind in ("compiled", "interpreted"):
        args = make_args()
        g = vars(m) if kind == "compiled" else ref
        hook, state = _hook_at(calls, lambda: action_for(args, g))
        if kind == "compiled":
            m.__tp_test_snap_hook__ = hook
        else:
            ref["HOOK"] = hook
        try:
            res = ("ok", getattr(m if kind == "compiled" else None, fname, None)(*args)
                   if kind == "compiled" else ref[fname](*args))
        except Exception as e:           # compared by type and text
            res = ("raise", type(e).__name__, str(e))
        finally:
            if kind == "compiled":
                del m.__tp_test_snap_hook__
            g["SCALE"] = 2.0
        out.append((res, args, state["n"]))
    return out


def test_snapshot_holders_get_continuations_and_poll(mods):
    _, _, c = mods
    assert "__tp_resume_scale_all_0" in c and "tp_osr *tp_my" in c
    assert c.count("#152: poll inside a snapshot holder") >= 4     # scale_all, nested (2), countdown


@pytest.mark.parametrize("calls", [1, 2, 5])
def test_an_unchanged_poll_keeps_running_compiled(mods, calls):
    m, ref, _ = mods
    seen = []
    orig = vars(m)["__tp_resume_scale_all_0"]
    m.__tp_resume_scale_all_0 = lambda *a: seen.append(1) or orig(*a)
    try:
        comp, interp = _both(mods, "scale_all", lambda: ([float(i) for i in range(200)],), calls,
                             lambda args, g: None)
    finally:
        m.__tp_resume_scale_all_0 = orig
    assert comp[0] == interp[0] and comp[1] == interp[1]
    assert comp[2] == interp[2] == 6                               # polls at 32, 64, ..., 192
    assert seen == []                                               # no exit when nothing changed


@pytest.mark.parametrize("calls", [1, 3, 6])
def test_a_length_change_at_a_poll_continues_interpreted(mods, calls):
    comp, interp = _both(mods, "scale_all", lambda: ([float(i) for i in range(200)],), calls,
                         lambda args, g: args[0].append(1.5))
    assert comp[0] == interp[0]
    assert comp[1] == interp[1]


@pytest.mark.parametrize("calls", [1, 4])
def test_an_element_type_change_at_a_poll_continues_interpreted(mods, calls):
    def put_int(args, g):
        args[0][100] = 7                  # an int where the copy-in requires floats
    comp, interp = _both(mods, "scale_all", lambda: ([float(i) for i in range(200)],), calls, put_int)
    assert comp[0] == interp[0]
    assert comp[1] == interp[1]


def test_a_rebound_entry_global_at_a_poll_continues_interpreted(mods):
    def rebind(args, g):
        g["SCALE"] = 3.0
    comp, interp = _both(mods, "scale_all", lambda: ([float(i) for i in range(200)],), 2, rebind)
    assert comp[0] == interp[0]
    assert comp[1] == interp[1]


@pytest.mark.parametrize("calls", [1, 3, 8, 13])
def test_an_exit_inside_a_nested_loop_keeps_break_and_continue(mods, calls):
    def flip(args, g):
        x = args[0]
        x[5] = -1.0
        x.append(2.0)
    comp, interp = _both(mods, "nested", lambda: ([float(i % 7) for i in range(100)], 5), calls, flip)
    assert comp[0] == interp[0]
    assert comp[1] == interp[1]


@pytest.mark.parametrize("calls", [1, 2])
def test_a_while_loop_exit(mods, calls):
    def grow(args, g):
        args[0].extend([10, 20, 30])
    comp, interp = _both(mods, "countdown", lambda: (list(range(100)),), calls, grow)
    assert comp[0] == interp[0]
    assert comp[1] == interp[1]


def test_an_exception_raised_in_the_continuation_reaches_the_caller(mods):
    def bad(args, g):
        args[0][150] = "x"                # the interpreted rest raises TypeError on this element
    comp, interp = _both(mods, "scale_all", lambda: ([float(i) for i in range(200)],), 1, bad)
    assert comp[0][0] == "raise" and comp[0] == interp[0]
    assert comp[1] == interp[1]


@pytest.mark.parametrize("calls", [1, 3])
def test_an_exception_at_a_poll_leaves_cpythons_partial_writes(mods, calls):
    """What a signal handler raising KeyboardInterrupt does: the list holds the writes of the
    iterations before the poll, written back before the poll ran, as CPython's own stores would be."""
    class Stop(Exception):
        pass

    def stop(args, g):
        raise Stop("at the poll")
    comp, interp = _both(mods, "scale_all", lambda: ([float(i) for i in range(200)],), calls, stop)
    assert comp[0] == interp[0] == ("raise", "Stop", "at the poll")
    assert comp[1] == interp[1]
