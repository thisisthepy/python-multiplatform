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

from conftest import COMPILED_SHIM
from typedpython import pipeline

posix_signals = pytest.mark.skipif(sys.platform == "win32", reason="needs POSIX signals (SIGALRM/SIGUSR1/setitimer) or fork; Windows has none")

SOURCE = """\


@compiled
def spin(n: int) -> float:
    acc: float = 0.0
    for k in range(n):
        acc += k * 0.5
    return acc


@compiled
def fib(n: int) -> int:
    if n < 2:
        return n
    return fib(n - 1) + fib(n - 2)


@compiled
def total(xs: list[float], rounds: int) -> float:
    s: float = 0.0
    for r in range(rounds):
        for i in range(len(xs)):
            s += xs[i]
    return s
"""

LOADER = COMPILED_SHIM + """\
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


@posix_signals
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
        # The deferred handler runs at the first eval-breaker check after the call returns, which can
        # come a few microseconds BEFORE the child reads t1 (3.15t checks right after a call): the
        # tolerance only has to separate that from "ran inside a call of 0.3 s or more".
        assert r["handler"] >= r["t1"] - 0.005, f"handler ran inside the call but its write is not in the result: {r}"


# ---- chunked polling for range loops (#206, SPEC N-8) ---------------------------------------------

CHUNK_SOURCE = """\


@compiled
def brk(n: int, k: int) -> int:
    s: int = 0
    last: int = -1
    for i in range(n):
        if i == k:
            break
        s += i
        last = i
    return s * 3 + last


@compiled
def cont(n: int, m: int) -> int:
    s: int = 0
    for i in range(n):
        if i % m == 0:
            continue
        s += 1
    return s


@compiled
def ret(n: int, k: int) -> int:
    for i in range(n):
        if i == k:
            return i * 2
    return -1


@compiled
def down(n: int) -> int:
    s: int = 0
    for i in range(n, 0, -3):
        s += i
    return s


@compiled
def down1(n: int) -> int:
    s: int = 0
    for i in range(n, -1, -1):
        s += 1
    return s


@compiled
def up3(a: int, b: int) -> int:
    s: int = 0
    for i in range(a, b, 3):
        s += i % 7
    return s


@compiled
def nested(n: int, m: int) -> int:
    s: int = 0
    for i in range(n):
        for j in range(m):
            if j == i:
                break
            s += 1
    return s


@compiled
def brk_in_while(n: int) -> int:
    s: int = 0
    for i in range(n):
        while True:
            s += 1
            break
        if i % 2 == 0:
            continue
        s += 1
    return s


@compiled
def huge(a: int, b: int, cap: int) -> int:
    c: int = 0
    for i in range(a, b):
        c += 1
        if c >= cap:
            break
    return c


@compiled
def huge_last(a: int, b: int, cap: int) -> int:
    c: int = 0
    last: int = 0
    for i in range(a, b):
        c += 1
        last = i
        if c >= cap:
            break
    return last
"""

CHUNK_CHILD = """
import json
src = json.loads(sys.argv[2])
ns = {}
exec(src, ns)
ref = ns
I = 4096
M = 2**63
trips = [0, 1, 2, I - 1, I, I + 1, 2 * I - 1, 2 * I, 2 * I + 1, 3 * I, 3 * I + 5, 10 * I + 7]
cases = []
for n in trips:
    for k in (0, 1, I - 1, I, I + 1, 2 * I, n - 1, n, n + 1):
        cases += [("brk", (n, k)), ("ret", (n, k))]
    for mm in (1, 2, 3, I):
        cases.append(("cont", (n, mm)))
    cases += [("down", (n,)), ("down1", (n,)), ("brk_in_while", (n,))]
    cases.append(("up3", (-7, n)))
    cases.append(("up3", (n, -7)))
    cases.append(("nested", (n, 5)))
cases += [("nested", (40, 4097)), ("nested", (4100, 4100)), ("nested", (3, 20000))]
cases += [("up3", (5, 5)), ("up3", (6, 5)), ("down", (0,)), ("down", (-5,))]
cases += [("huge", (-M, M - 1, cap)) for cap in (1, I, I + 1, 3 * I)]
cases += [("huge", (M - 3, M - 1, 100)), ("huge", (M - 1, M - 1, 100)), ("huge", (-M, -M + 2 * I + 3, 10**9)),
          ("huge", (-M, -M + 3 * I, 10**9)), ("huge_last", (-M, M - 1, 2 * I + 1)),
          ("huge_last", (M - 2 * I - 3, M - 1, 10**9)), ("huge", (M - 1, -M, 10))]
bad = []
for name, args in cases:
    want = getattr(ref["__builtins__"], "None", None)
    want = ns[name](*args)
    got = getattr(m, name)(*args)
    if want != got or type(want) is not type(got):
        bad.append([name, [str(a) for a in args], str(want), str(got)])
print(json.dumps({"n": len(cases), "bad": bad[:10]}))
"""


def run_child_args(extension: Path, body: str, *args: str) -> dict:
    script = LOADER + textwrap.dedent(body)
    proc = subprocess.run([sys.executable, "-c", script, str(extension), *args],
                          capture_output=True, text=True, timeout=300)
    assert proc.returncode == 0, f"child failed ({proc.returncode}):\n{proc.stdout}\n{proc.stderr}"
    return json.loads(proc.stdout.strip().splitlines()[-1])


@pytest.fixture(scope="module")
def chunk_module(tmp_path_factory):
    d = tmp_path_factory.mktemp("ebchunk")
    path = d / "loops.py"
    path.write_text(CHUNK_SOURCE)
    result = pipeline.compile_module(path, d / "out")
    assert result.extension is not None, result.skipped
    names = {f.name for f in result.module.functions}
    assert names >= {"brk", "cont", "ret", "down", "down1", "up3", "nested", "brk_in_while", "huge",
                     "huge_last"}, result.skipped
    return path, result


def _generated_c(path: Path) -> str:
    from typedpython import cgen, frontend, verify
    module, _ = verify.verify(frontend.lower(path))
    return cgen.generate(module, path)


def _function_c(c: str, name: str) -> str:
    import re
    start = re.search(rf"tp_impl_{name}\([^)]*\)\s*\{{", c).end()
    return c[start:c.index("\ntp_exit:", start)]


def test_range_loop_polls_once_per_chunk(chunk_module):
    path, _ = chunk_module
    body = _function_c(_generated_c(path), "down1")
    # one poll site, outside the innermost (per-iteration) loop
    assert body.count("tp_poll_slow") == 1, body
    assert "TP_POLL_INTERVAL" in body, body
    assert "tp_pc_tick" not in body, body            # #280: the chunk is the countdown
    lines = body.splitlines()
    poll = next(i for i, l in enumerate(lines) if "tp_poll_slow" in l)
    fors = [i for i, l in enumerate(lines) if l.lstrip().startswith("for (") or l.lstrip().startswith("while (")]
    assert len([i for i in fors if i < poll]) >= 1, body            # the chunk loop encloses the poll
    assert len([i for i in fors if i > poll]) >= 1, body            # the per-iteration loop follows it
    # the per-iteration loop has no poll inside it
    inner = lines[max(i for i in fors if i > poll):]
    assert not any("tp_poll_slow" in l for l in inner), body


def test_while_loop_keeps_its_per_iteration_countdown(tmp_path):
    path = tmp_path / "w.py"
    path.write_text("\n\n@compiled\ndef w(n: int) -> int:\n    i: int = 0\n    while i < n:\n"
                    "        i += 1\n    return i\n")
    body = _function_c(_generated_c(path), "w")
    lines = body.splitlines()
    loop = next(i for i, l in enumerate(lines) if "for (;;)" in l)
    poll = next(i for i, l in enumerate(lines) if "tp_pc_tick" in l)
    assert poll == loop + 1, body


def test_range_loop_results_match_cpython_across_chunk_boundaries(chunk_module):
    path, result = chunk_module
    r = run_child_args(result.extension, CHUNK_CHILD, json.dumps(CHUNK_SOURCE))
    assert r["n"] > 200
    assert r["bad"] == [], r


def test_keyboard_interrupt_reaches_a_long_range_loop_in_chunks(extension):
    # spin() is a pure range loop: with chunked polling the interrupt must still arrive promptly.
    r = run_child(extension, """
        threading.Timer(0.05, lambda: __import__("_thread").interrupt_main()).start()
        t0 = time.perf_counter()
        try:
            m.spin(9_000_000_000_000)
            out = "completed"
        except KeyboardInterrupt:
            out = "interrupted"
        print(json.dumps({"out": out, "elapsed": time.perf_counter() - t0}))
    """)
    assert r["out"] == "interrupted"
    assert r["elapsed"] < 1.0, r


MID_SOURCE = """\


@compiled
def mid(n: int) -> float:
    x: float = 1.0
    for k in range(n):
        x = x * 0.9999999 + 1.0 / (k + 1.0)
    return x
"""


@posix_signals
def test_signal_reaches_a_range_loop_shorter_than_interval_squared(tmp_path):
    # #280: a chunk of TP_POLL_INTERVAL iterations must end in a slow poll, not in a countdown that
    # also counts TP_POLL_INTERVAL (one poll per 4096*4096 = 16.7M iterations). 12M iterations is
    # under that, so the old code never polled and the signal was seen only after the loop ended.
    path = tmp_path / "loops.py"
    path.write_text(MID_SOURCE)
    result = pipeline.compile_module(path, tmp_path / "out")
    assert result.extension is not None, result.skipped
    r = run_child(result.extension, """
        def handler(signum, frame):
            raise KeyboardInterrupt
        signal.signal(signal.SIGALRM, handler)
        n = 12_000_000
        t0 = time.perf_counter()
        m.mid(n)
        full = time.perf_counter() - t0
        signal.setitimer(signal.ITIMER_REAL, full / 10)
        t0 = time.perf_counter()
        try:
            m.mid(n)
            out = "completed"
        except KeyboardInterrupt:
            out = "interrupted"
        signal.setitimer(signal.ITIMER_REAL, 0)
        print(json.dumps({"out": out, "full": full, "elapsed": time.perf_counter() - t0}))
    """)
    assert r["out"] == "interrupted", r
    assert r["elapsed"] < r["full"] * 0.8, r     # before #280 the signal waited for the whole loop
