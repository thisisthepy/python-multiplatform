"""Tracing, profiling and monitoring see compiled functions (SPEC N-14, #142).

A compiled function has no Python frame, so at entry it checks whether `sys.settrace`,
`sys.setprofile` or `sys.monitoring` is active (any global event, or a local event on the function's
original code object) and, if so, runs the interpreted original. The events are then CPython's own.
Each test compares the events of the compiled module with those of the interpreted originals
(`__typedpython_interpreted__`), in a child interpreter.

Red before the implementation: the compiled run emits no events at all, so every comparison fails.
"""
import json
import subprocess
import sys
import textwrap
from pathlib import Path

import pytest

from typedpython import pipeline

SOURCE = """\
# typedpython: compiled
from typing import Callable


def sq(x: float) -> float:
    return x * x


def loop(n: int) -> float:
    acc: float = 0.0
    for k in range(n):
        acc += sq(k * 0.5)
    return acc


def leaf(cb: Callable[[], None], n: float) -> float:
    cb()
    return n + 1.0


def outer(cb: Callable[[], None], n: float) -> float:
    cb()
    return leaf(cb, n) + leaf(cb, n)
"""

LOADER = """\
import importlib.machinery, importlib.util, json, sys, threading
path = sys.argv[1]
loader = importlib.machinery.ExtensionFileLoader("tmod", path)
spec = importlib.util.spec_from_file_location("tmod", path, loader=loader)
m = importlib.util.module_from_spec(spec)
loader.exec_module(m)
orig = m.__typedpython_interpreted__
names = {"sq", "loop", "leaf", "outer"}
codes = {orig[n].__code__ for n in names}
ev = []
def rec(kind, code, line=None):
    if code.co_name in names:
        ev.append((kind, code.co_name, line))
def tracer(fr, e, a):
    rec("t:" + e, fr.f_code, fr.f_lineno if e == "line" else None)
    return tracer
def profiler(fr, e, a):
    if e in ("call", "return"):
        rec("p:" + e, fr.f_code)
def noop():
    pass
def sweep(fn_c, fn_i, install, uninstall):
    out = []
    for fn in (fn_c, fn_i):
        del ev[:]
        install(); r = fn(); uninstall()
        out.append((r, list(ev)))
    return out
"""


@pytest.fixture(scope="module")
def extension(tmp_path_factory):
    d = tmp_path_factory.mktemp("tr")
    path = d / "tmod.py"
    path.write_text(SOURCE)
    result = pipeline.compile_module(path, d / "out")
    assert result.extension is not None, result.skipped
    names = {f.name for f in result.module.functions}
    assert names >= {"sq", "loop", "leaf", "outer"}, result.skipped
    return result.extension


def run_child(extension: Path, body: str) -> dict:
    script = LOADER + textwrap.dedent(body)
    proc = subprocess.run([sys.executable, "-c", script, str(extension)],
                          capture_output=True, text=True, timeout=120)
    assert proc.returncode == 0, f"child failed ({proc.returncode}):\n{proc.stdout}\n{proc.stderr}"
    return json.loads(proc.stdout.strip().splitlines()[-1])


def same(extension, install, uninstall, call="m.loop(3)", interp="orig['loop'](3)"):
    return run_child(extension, f"""
        c, i = sweep(lambda: {call}, lambda: {interp}, {install}, {uninstall})
        print(json.dumps({{"c": c, "i": i}}))
    """)


def assert_equal_and_nonempty(r):
    assert r["i"][1], "the interpreted run emitted no events: the test is broken"
    assert r["c"] == r["i"], r


def test_settrace_events_equal_interpreted(extension):
    assert_equal_and_nonempty(same(extension, "lambda: sys.settrace(tracer)", "lambda: sys.settrace(None)"))


def test_setprofile_events_equal_interpreted(extension):
    assert_equal_and_nonempty(same(extension, "lambda: sys.setprofile(profiler)", "lambda: sys.setprofile(None)"))


MON = """
M = sys.monitoring
E = M.events
def mon_on():
    M.use_tool_id(3, "t")
    M.register_callback(3, E.PY_START, lambda c, o: rec("m:start", c))
    M.register_callback(3, E.PY_RETURN, lambda c, o, r: rec("m:return", c))
    M.register_callback(3, E.LINE, lambda c, l: rec("m:line", c, l))
    M.register_callback(3, E.CALL, lambda c, o, cal, a: rec("m:call", c))
    M.set_events(3, E.PY_START | E.PY_RETURN | E.LINE | E.CALL)
def mon_off():
    M.set_events(3, 0)
    for e in (E.PY_START, E.PY_RETURN, E.LINE, E.CALL):
        M.register_callback(3, e, None)
    M.free_tool_id(3)
"""


def monitoring(extension, body, extra=""):
    return run_child(extension, MON + extra + textwrap.dedent(body))


def test_monitoring_global_events_equal_interpreted(extension):
    r = monitoring(extension, """
        c, i = sweep(lambda: m.loop(3), lambda: orig['loop'](3), mon_on, mon_off)
        print(json.dumps({"c": c, "i": i}))
    """)
    assert_equal_and_nonempty(r)


LOCAL = """
def on():
    M.use_tool_id(3, "t")
    M.register_callback(3, E.PY_START, lambda c, o: rec("m:start", c))
    M.register_callback(3, E.LINE, lambda c, l: rec("m:line", c, l))
    M.set_local_events(3, orig['sq'].__code__, E.PY_START | E.LINE)
def off():
    M.set_local_events(3, orig['sq'].__code__, 0)
    M.register_callback(3, E.PY_START, None)
    M.register_callback(3, E.LINE, None)
    M.free_tool_id(3)
"""


def test_monitoring_local_events_only_equal_interpreted(extension):
    """A tool that uses only set_local_events on the original code (pdb, debugpy breakpoints)."""
    r = monitoring(extension, """
        c, i = sweep(lambda: m.sq(2.0), lambda: orig['sq'](2.0), on, off)
        print(json.dumps({"c": c, "i": i}))
    """, LOCAL)
    assert_equal_and_nonempty(r)


def test_after_local_events_are_cleared_calls_return_to_the_compiled_path(extension):
    r = monitoring(extension, """
        d0 = m.__typedpython_deopts__
        on(); m.sq(2.0); m.sq(2.0)
        d1 = m.__typedpython_deopts__
        off()
        m.sq(2.0); m.sq(2.0); m.loop(3)
        d2 = m.__typedpython_deopts__
        print(json.dumps({"while_on": d1 - d0, "after_off": d2 - d1}))
    """, LOCAL)
    assert r == {"while_on": 2, "after_off": 0}, r


def test_no_events_without_tracing_and_compiled_path_still_taken(extension):
    r = run_child(extension, """
        before = m.__typedpython_deopts__
        m.loop(3); m.sq(2.0)
        sys.settrace(tracer); sys.settrace(None)       # on, then off again
        m.loop(3)
        print(json.dumps({"deopts": m.__typedpython_deopts__ - before, "ev": len(ev)}))
    """)
    assert r == {"deopts": 0, "ev": 0}, r


def test_tracing_set_after_entry_c_to_c_call_is_interpreted(extension):
    """An impure caller starts tracing through `cb`; its direct call of a compiled impure callee
    must then run interpreted (the C-to-C call re-checks)."""
    r = run_child(extension, """
        def start():
            sys.settrace(tracer)
        def go():
            del ev[:]
            r = m.outer(start, 1.0)
            sys.settrace(None)
            return r, [e for e in ev if e[1] == "leaf"]
        rc, leaves_c = go()
        assert rc == 4
        print(json.dumps({"leaf_events": len(leaves_c)}))
    """)
    assert r["leaf_events"] > 0, r


def test_known_difference_monitoring_enabled_in_a_running_compiled_function(extension):
    """SPEC N-14, known difference: sys.monitoring turned on while a compiled function is running
    produces events for that function from its next call on (CPython would instrument the running
    frame). Here `outer` starts the tool through `cb`; the interpreted `outer` would report LINE
    events of its own frame after that point, the compiled one reports none for itself."""
    r = monitoring(extension, """
        on = []
        def start():
            if not on:
                on.append(1)
                mon_on()
        def go(fn):
            del ev[:]; del on[:]
            fn(start, 1.0)
            mon_off()
            return [e for e in ev if e[1] == "outer"]
        c = go(m.outer)
        i = go(orig['outer'])
        print(json.dumps({"c": len(c), "i": len(i)}))
    """)
    assert r["i"] > 0 and r["c"] == 0, r


def test_tracing_set_by_another_thread_is_per_thread_for_settrace(extension):
    r = run_child(extension, """
        t = threading.Thread(target=lambda: sys.settrace(tracer))
        t.start(); t.join()
        m.loop(3)
        print(json.dumps({"ev": len(ev), "gettrace": sys.gettrace() is None}))
    """)
    assert r == {"ev": 0, "gettrace": True}, r


def test_replacing_the_interpreted_table_entry_does_not_dangle(extension):
    r = monitoring(extension, """
        import gc
        on()
        orig['sq'] = lambda x: x * x            # drops the last table reference to the original
        gc.collect()
        v = m.sq(3.0)
        off()
        gc.collect()
        print(json.dumps({"v": v}))
    """, LOCAL)
    assert r == {"v": 9.0}, r


def test_reimported_module_checks_its_own_original_code(extension):
    r = monitoring(extension, """
        import importlib.util, importlib.machinery
        loader2 = importlib.machinery.ExtensionFileLoader("tmod", path)
        spec2 = importlib.util.spec_from_file_location("tmod", path, loader=loader2)
        m2 = importlib.util.module_from_spec(spec2)
        loader2.exec_module(m2)
        orig2 = m2.__typedpython_interpreted__
        M.use_tool_id(3, "t")
        M.set_local_events(3, orig2['sq'].__code__, E.PY_START)
        d0 = m2.__typedpython_deopts__; e0 = m.__typedpython_deopts__
        m2.sq(2.0); m.sq(2.0)
        out = {"new": m2.__typedpython_deopts__ - d0, "old": m.__typedpython_deopts__ - e0}
        M.set_local_events(3, orig2['sq'].__code__, 0); M.free_tool_id(3)
        print(json.dumps(out))
    """)
    assert r == {"new": 1, "old": 0}, r
