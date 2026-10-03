"""Compiled code checks the eval breaker (#141, SPEC N-8): during a long compiled loop or recursion,
signals reach Python (KeyboardInterrupt), and other threads get the GIL.

A Python signal handler, a pending call and another thread are user code that can change lists and
globals. A function that holds an array copy-in (ArrayParam) or an entry-globals snapshot runs no
user code by contract, so while such a function is active the poll is deferred: the result is then
the CPython execution in which the signal arrived after the call.

Each scenario runs in a child interpreter, so a broken build cannot take pytest down and signal
handlers stay out of the test process. Timing thresholds are generous; they separate "polled"
(tens of ms) from "never polled" (the whole loop, seconds).
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


def spin(n: int) -> float:
    acc: float = 0.0
    for k in range(n):
        acc += k * 0.5
    return acc


def fib(n: int) -> int:
    if n < 2:
        return n
    return fib(n - 1) + fib(n - 2)


def total(xs: list[float], rounds: int) -> float:
    s: float = 0.0
    for r in range(rounds):
        for i in range(len(xs)):
            s += xs[i]
    return s
"""

LOADER = """\
import importlib.machinery, importlib.util, json, signal, sys, threading, time
path = sys.argv[1]
loader = importlib.machinery.ExtensionFileLoader("loops", path)
spec = importlib.util.spec_from_file_location("loops", path, loader=loader)
m = importlib.util.module_from_spec(spec)
loader.exec_module(m)
"""


@pytest.fixture(scope="module")
def extension(tmp_path_factory):
    d = tmp_path_factory.mktemp("eb")
    path = d / "loops.py"
    path.write_text(SOURCE)
    result = pipeline.compile_module(path, d / "out")
    assert result.extension is not None, result.skipped
    names = {f.name for f in result.module.functions}
    assert names >= {"spin", "fib", "total"}, result.skipped
    return result.extension


def run_child(extension: Path, body: str) -> dict:
    script = LOADER + textwrap.dedent(body)
    proc = subprocess.run([sys.executable, "-c", script, str(extension)],
                          capture_output=True, text=True, timeout=120)
    assert proc.returncode == 0, f"child failed ({proc.returncode}):\n{proc.stdout}\n{proc.stderr}"
    return json.loads(proc.stdout.strip().splitlines()[-1])


def test_keyboard_interrupt_reaches_a_compiled_loop(extension):
    r = run_child(extension, """
        threading.Timer(0.05, lambda: __import__("_thread").interrupt_main()).start()
        t0 = time.perf_counter()
        try:
            m.spin(4_000_000_000)          # seconds of work if never interrupted
            out = "completed"
        except KeyboardInterrupt:
            out = "interrupted"
        print(json.dumps({"out": out, "elapsed": time.perf_counter() - t0}))
    """)
    assert r["out"] == "interrupted"
    assert r["elapsed"] < 1.0, r


def test_keyboard_interrupt_reaches_compiled_recursion(extension):
    r = run_child(extension, """
        threading.Timer(0.05, lambda: __import__("_thread").interrupt_main()).start()
        t0 = time.perf_counter()
        try:
            m.fib(45)                       # many seconds compiled; no loop, only calls
            out = "completed"
        except KeyboardInterrupt:
            out = "interrupted"
        print(json.dumps({"out": out, "elapsed": time.perf_counter() - t0}))
    """)
    assert r["out"] == "interrupted"
    assert r["elapsed"] < 1.0, r


def test_another_thread_runs_while_a_compiled_loop_runs(extension):
    r = run_child(extension, """
        stamps = []
        stop = False
        def worker():
            n = 0
            while not stop:
                n += 1
                if n % 1000 == 0:
                    stamps.append(time.perf_counter())
        t = threading.Thread(target=worker)
        t.start()
        time.sleep(0.05)
        t0 = time.perf_counter()
        m.spin(1_500_000_000)
        t1 = time.perf_counter()
        stop = True
        t.join()
        inside = [s for s in stamps if t0 + 0.1 < s < t1 - 0.1]
        print(json.dumps({"inside": len(inside), "call_s": t1 - t0}))
    """)
    assert r["call_s"] > 0.3, f"loop too short to judge: {r}"
    assert r["inside"] > 0, f"the worker thread never ran during the compiled call: {r}"


def test_a_handler_is_deferred_while_an_array_copy_is_live(extension):
    # total() reads its list through a copy-in. A handler that writes the list mid-call would be
    # invisible to the copy, a result no CPython execution can give. So the handler must run after
    # the call (the execution where the signal arrived late), or the result must include its write.
    r = run_child(extension, """
        xs = [1.0] * 100_000
        seen = {}
        def handler(signum, frame):
            seen["t"] = time.perf_counter()
            xs[0] = 1.0e6
        signal.signal(signal.SIGUSR1, handler)
        import os
        threading.Timer(0.05, lambda: os.kill(os.getpid(), signal.SIGUSR1)).start()
        t0 = time.perf_counter()
        s = m.total(xs, 20_000)
        t1 = time.perf_counter()
        time.sleep(0.01)                  # let a deferred handler run
        print(json.dumps({"s": s, "plain": 100_000 * 20_000 * 1.0, "t0": t0, "t1": t1,
                          "handler": seen.get("t")}))
    """)
    assert r["handler"] is not None, "the handler never ran"
    assert r["t1"] - r["t0"] > 0.3, f"call too short to judge: {r}"
    if r["s"] == r["plain"]:
        assert r["handler"] >= r["t1"], f"handler ran inside the call but its write is not in the result: {r}"
