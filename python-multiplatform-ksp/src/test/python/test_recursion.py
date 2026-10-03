"""TypedPython recursion (issue #57, SPEC N-8): direct and mutual call cycles are compiled, and
compiled recursion raises CPython's RecursionError, never crashes the process.

Differential: every result is compared with the same source run by the interpreter. Builds go
through the whole pipeline (source -> gate -> frontend -> verify -> cgen -> cbuild), in
`.tmp/` of the worktree via `tmp_path`.
"""
import importlib.util
import os
import subprocess
import sys
import sysconfig
import textwrap
from pathlib import Path

import pytest

from typedpython import pipeline

PACKAGE = Path(__file__).resolve().parents[2] / "main" / "python"

SOURCE = """\
# typedpython: compiled


def fib(n: int) -> int:
    if n < 2:
        return n
    return fib(n - 1) + fib(n - 2)


def ack(m: int, n: int) -> int:
    if m == 0:
        return n + 1
    if n == 0:
        return ack(m - 1, 1)
    return ack(m - 1, ack(m, n - 1))


def is_even(n: int) -> bool:
    if n == 0:
        return True
    return is_odd(n - 1)


def is_odd(n: int) -> bool:
    if n == 0:
        return False
    return is_even(n - 1)


def down(n: int) -> int:
    if n == 0:
        return 0
    return 1 + down(n - 1)


def pow2(n: int) -> int:
    if n == 0:
        return 1
    return 2 * pow2(n - 1)


def fact(n: int) -> int:
    if n < 2:
        return 1
    return n * fact(n - 1)
"""

NAMES = ("fib", "ack", "is_even", "is_odd", "down", "pow2", "fact")


def load_plain(path: Path):
    spec = importlib.util.spec_from_file_location(path.stem + "_plain", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="module")
def built(tmp_path_factory):
    d = tmp_path_factory.mktemp("rec")
    path = d / "rec_mod.py"
    path.write_text(SOURCE)
    result = pipeline.compile_module(path, d / "out")
    compiled = pipeline.load(result) if result.extension else None
    return result, compiled, load_plain(path)


@pytest.fixture
def mod(built):
    result, compiled, plain = built
    assert result.extension is not None, result.skipped
    return compiled, plain


def test_every_recursive_function_is_compiled(built):
    result = built[0]
    assert {f.name for f in result.module.functions} == set(NAMES), result.skipped
    assert not any("recurs" in why for why in result.skipped.values()), result.skipped


def test_fib_matches_the_interpreter(mod):
    compiled, plain = mod
    for n in (0, 1, 2, 10, 20):
        assert compiled.fib(n) == plain.fib(n)


def test_ackermann_matches_the_interpreter(mod):
    compiled, plain = mod
    for m, n in ((0, 0), (1, 3), (2, 3), (3, 3)):
        assert compiled.ack(m, n) == plain.ack(m, n)


def test_mutual_recursion_matches_the_interpreter(mod):
    compiled, plain = mod
    for n in (0, 1, 2, 7, 100, 400):
        assert compiled.is_even(n) is plain.is_even(n)
        assert compiled.is_odd(n) is plain.is_odd(n)


def test_a_pure_recursive_int_function_that_overflows_returns_the_big_int(mod):
    compiled, plain = mod
    before = compiled.__typedpython_deopts__
    assert compiled.pow2(100) == plain.pow2(100) == 2 ** 100
    assert compiled.fact(25) == plain.fact(25)
    assert compiled.__typedpython_deopts__ > before          # it really deopted
    assert compiled.pow2(10) == 1024                         # and the fast path still works


def raised(fn, *args):
    with pytest.raises(RecursionError) as e:
        fn(*args)
    return str(e.value)


def test_recursion_past_the_limit_raises_the_same_error_as_the_interpreter(mod):
    compiled, plain = mod
    assert raised(compiled.down, 10 ** 6) == raised(plain.down, 10 ** 6) == \
        "maximum recursion depth exceeded"
    assert raised(compiled.is_even, 10 ** 6) == "maximum recursion depth exceeded"


def test_recursion_past_a_raised_limit_raises_too(mod):
    compiled, plain = mod
    old = sys.getrecursionlimit()
    sys.setrecursionlimit(old + 3000)
    try:
        assert compiled.down(old + 1000) == old + 1000        # allowed by the raised limit
        assert raised(compiled.down, 10 ** 6) == "maximum recursion depth exceeded"
        assert raised(plain.down, 10 ** 6) == "maximum recursion depth exceeded"
    finally:
        sys.setrecursionlimit(old)


def test_a_lowered_limit_applies_to_compiled_frames(mod):
    compiled, plain = mod
    old = sys.getrecursionlimit()
    base = len(_stack())
    sys.setrecursionlimit(base + 50)
    try:
        assert compiled.down(10) == 10
        assert raised(compiled.down, 200) == "maximum recursion depth exceeded"
        assert raised(plain.down, 200) == "maximum recursion depth exceeded"
    finally:
        sys.setrecursionlimit(old)


def _stack():
    import inspect
    return inspect.stack(0)


def test_the_depth_counter_is_balanced_after_every_error_exit(mod):
    # Each RecursionError leaves ~1000 compiled frames; a frame whose exit skipped tp_leave_call
    # would stay counted, and after a few errors even a shallow call would fail.
    compiled, _ = mod
    for _ in range(8):
        raised(compiled.down, 10 ** 6)
        raised(compiled.is_even, 10 ** 6)
    assert compiled.down(500) == 500
    assert compiled.fib(15) == 610


def test_the_depth_counter_is_balanced_after_a_deopt_unwinds(mod):
    compiled, _ = mod
    for _ in range(2000):                  # more frames than the limit if any deopt leaked a frame
        assert compiled.pow2(70) == 2 ** 70
    assert compiled.down(500) == 500


def max_ok(fn, hi=20_000):
    """The largest n for which fn(n) does not raise RecursionError (binary search)."""
    lo = 0
    while lo < hi:
        mid = (lo + hi + 1) // 2
        try:
            fn(mid)
            lo = mid
        except RecursionError:
            hi = mid - 1
    return lo


def test_depth_at_which_recursion_error_fires_is_recorded(mod, capsys):
    """Measures where RecursionError fires for interpreted, compiled, and mixed frames. Asserts
    only that both raise and nothing crashes; the offsets are recorded in the test output
    and in docs/design/typedpython.md."""
    compiled, plain = mod
    interp_only = max_ok(plain.down)
    compiled_only = max_ok(compiled.down)

    def mixed(k):
        def run(n):
            def inner(j):
                if j == 0:
                    return compiled.down(n)
                return inner(j - 1)
            return inner(k)
        return run

    results = {"interpreted": interp_only, "compiled": compiled_only}
    for k in (1, 10, 100):
        results[f"mixed k={k} interpreted frames"] = max_ok(mixed(k))
    with capsys.disabled():
        print("\nRECURSION-DEPTH-MEASUREMENT", results)
    assert interp_only > 0 and compiled_only > 0
    assert all(v > 0 for v in results.values())
    # both kinds do raise past their depth
    raised(plain.down, interp_only + 5)
    raised(compiled.down, compiled_only + 5)


# --- sanitizers: the RecursionError path ---------------------------------------------------------

ASAN_SCRIPT = textwrap.dedent('''\
    import sys
    sys.path.insert(0, {pkg!r})
    from typedpython import cbuild
    m = cbuild.load({name!r}, {so!r})
    for _ in range(50):
        for f in (m.down, m.is_even, m.fib):
            try:
                f(10 ** 6)
            except RecursionError as e:
                assert str(e) == "maximum recursion depth exceeded", e
    assert m.down(300) == 300 and m.fib(15) == 610 and m.pow2(80) == 2 ** 80
    sys.setrecursionlimit(200000)          # the C-stack guard must stop it before the stack ends
    try:
        m.down(10 ** 7)
    except RecursionError:
        pass
    print("ASAN-RUN-OK")
    ''')


def test_recursion_error_path_under_asan_and_ubsan(tmp_path):
    from typedpython import cbuild, cgen, frontend, verify

    cc = sysconfig.get_config_var("CC") or "cc"
    if sys.platform != "darwin":
        pytest.skip("sanitizer preload recipe written for macOS only (as test_cgen.py)")
    probe = subprocess.run(cc.split() + ["-print-file-name=libclang_rt.asan_osx_dynamic.dylib"],
                           capture_output=True, text=True)
    if probe.returncode != 0 or not Path(probe.stdout.strip()).is_file():
        pytest.skip("host compiler has no ASan runtime")
    path = tmp_path / "rec_asan.py"
    path.write_text(SOURCE)
    proved, _ = verify.verify(frontend.lower(path))
    assert {f.name for f in proved.functions} == set(NAMES), proved.skipped
    flags = list(cbuild.DEFAULT_FLAGS) + ["-O1", "-g", "-fno-omit-frame-pointer",
                                          "-fsanitize=address,undefined",
                                          "-fno-sanitize-recover=undefined"]
    so = cbuild.build(cgen.generate(proved, path), "rec_asan", tmp_path, flags=flags)
    launcher_c = tmp_path / "asan_python.c"
    launcher_c.write_text("#include <Python.h>\nint main(int argc, char **argv) "
                          "{ return Py_BytesMain(argc, argv); }\n")
    launcher = tmp_path / "asan_python"
    link = subprocess.run(
        cc.split() + ["-fsanitize=address,undefined", "-g", f"-I{sysconfig.get_paths()['include']}",
                      str(launcher_c), f"-L{sysconfig.get_config_var('LIBDIR')}",
                      f"-lpython{sysconfig.get_config_var('LDVERSION')}", "-o", str(launcher)],
        capture_output=True, text=True)
    if link.returncode != 0:
        pytest.skip(f"cannot link a sanitized launcher: {link.stderr[-1500:]}")
    env = dict(os.environ, PYTHONHOME=sys.base_prefix,
               ASAN_OPTIONS="detect_leaks=0:abort_on_error=0:halt_on_error=1",
               UBSAN_OPTIONS="halt_on_error=1:print_stacktrace=1")
    env.pop("DYLD_INSERT_LIBRARIES", None)
    script = ASAN_SCRIPT.format(pkg=str(PACKAGE), name="rec_asan", so=str(so))
    run = subprocess.run([str(launcher), "-c", script], capture_output=True, text=True, env=env)
    out = run.stdout + run.stderr
    if "violates platform policy" in out or "Interceptors are not working" in out:
        pytest.skip(f"ASan runtime could not be loaded: {out[-1500:]}")
    assert run.returncode == 0 and "ASAN-RUN-OK" in run.stdout, out[-5000:]
    linked = subprocess.run(["otool", "-L", str(so)], capture_output=True, text=True).stdout
    assert "libclang_rt.asan" in linked, linked
    assert "AddressSanitizer" not in out and "runtime error" not in out, out[-3000:]


# --- generated code uses the helpers -------------------------------------------------------------

def test_every_compiled_function_enters_and_leaves(built):
    from typedpython import cgen
    result = built[0]
    c = cgen.generate(result.module, result.source)
    assert c.count("tp_enter_call()") >= len(NAMES)
    assert c.count("tp_leave_call()") >= len(NAMES)
