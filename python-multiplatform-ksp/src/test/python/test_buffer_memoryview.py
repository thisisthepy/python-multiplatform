"""`memoryview[float]` / `memoryview[int]` parameters on the zero-copy buffer path (issue #149).

A memoryview parameter takes the same path as `array.array[float]` / `array.array[int]`: the export
is taken on the view, held for the call, released on every exit, and elements are read and written
in place. Anything the path cannot take (released, read-only when stored to, non-contiguous,
multi-dimensional, other format, not a memoryview) goes to the object path and the interpreter
raises or runs it.

Tests are differential against the interpreted function (`__typedpython_interpreted__`): same
result, same exception type and text, same final contents. numpy cases skip without numpy.
"""
from __future__ import annotations

import array
import textwrap
import threading
import time

import pytest

from typedpython import cbuild
from test_buffer_arrays import (Built, build_source, deopts, interp, load_so, outcome,
                                same_value)

SOURCE = textwrap.dedent('''
    # typedpython: compiled
    import array


    def sum_mf(m: memoryview[float]) -> float:
        s = 0.0
        for i in range(len(m)):
            s += m[i]
        return s


    def sum_mi(m: memoryview[int]) -> int:
        s = 0
        for i in range(len(m)):
            s += m[i]
        return s


    def get_mf(m: memoryview[float], i: int) -> float:
        return m[i]


    def get_mi(m: memoryview[int], i: int) -> int:
        return m[i]


    def set_mf(m: memoryview[float], i: int, v: float) -> None:
        m[i] = v


    def set_mi(m: memoryview[int], i: int, v: int) -> None:
        m[i] = v


    def length_mf(m: memoryview[float]) -> int:
        return len(m)


    def scale_mf(m: memoryview[float], k: float) -> None:
        for i in range(len(m)):
            m[i] = m[i] * k


    def spin_mf(m: memoryview[float], n: int) -> float:
        s = 0.0
        L = len(m)
        for k in range(n):
            s += m[k % L]
        return s


    def spin_af(a: array.array[float], n: int) -> float:
        s = 0.0
        L = len(a)
        for k in range(n):
            s += a[k % L]
        return s
''')

COMPILED = {"sum_mf", "sum_mi", "get_mf", "get_mi", "set_mf", "set_mi", "length_mf", "scale_mf",
            "spin_mf", "spin_af"}

F = [1.5, -2.25, 3.0, 4.5]
I = [3, -5, 7, 11]


@pytest.fixture(scope="module")
def built(tmp_path_factory):
    d = tmp_path_factory.mktemp("mvmod")
    path, proved, diags, c = build_source(d, SOURCE, "mvmod")
    so = cbuild.build(c, path.stem, d / "out")
    return Built(proved, diags, c, load_so(so))


def MF(v=F): return memoryview(array.array("d", v))
def MI(v=I): return memoryview(array.array("q", v))


def contents(x):
    if isinstance(x, memoryview):
        try:
            return ("mv", x.tolist())
        except ValueError as e:
            return ("released", str(e))
    if isinstance(x, bytearray):
        return ("seq", list(x))
    if isinstance(x, (array.array, list)):
        return ("seq", list(x))
    return ("other", None)


def same_contents(a, b):
    if a[0] != b[0]:
        return False
    if a[0] in ("mv", "seq"):
        return len(a[1]) == len(b[1]) and all(same_value(p, q) for p, q in zip(a[1], b[1]))
    return a == b


def differential(m, name, make, expect_deopt=False):
    """Compiled vs interpreted on two fresh argument sets; also compares the final contents of
    every argument (viewed through the memoryview, and through its owner when `make` returns a
    trailing owners tuple)."""
    ca, ia = make(), make()
    cargs, cown = ca[:-1], ca[-1]
    iargs, iown = ia[:-1], ia[-1]
    before = deopts(m)
    c = outcome(getattr(m, name), cargs)
    after = deopts(m)
    i = outcome(interp(m, name), iargs)
    assert c[0] == i[0], (c, i)
    if c[0] == "ok":
        assert same_value(c[1], i[1]), (c, i)
    else:
        assert c[1:] == i[1:], (c, i)
    for x, y in zip(list(cargs) + list(cown), list(iargs) + list(iown)):
        assert same_contents(contents(x), contents(y)), (contents(x), contents(y))
    if expect_deopt:
        assert after == before + 1, "expected a deopt (an object-path call), got none"
    else:
        assert after == before, "unexpected deopt: the compiled path did not run"
    return c


def as_list(owner, fmt):
    if isinstance(owner, bytearray):
        return memoryview(owner).cast(fmt).tolist()
    return list(owner)


def mk(view, *rest):
    """Argument factory: view() builds (memoryview, owner); rest are plain extra arguments."""
    def make():
        v, owner = view()
        return (v,) + rest + ((owner,),)
    return make


def v_array_d(vals=F):
    a = array.array("d", vals)
    return memoryview(a), a


def v_array_q(vals=I):
    a = array.array("q", vals)
    return memoryview(a), a


def v_array_l(vals=I):
    a = array.array("l", vals)
    return memoryview(a), a


def v_bytearray_d(vals=F):
    b = bytearray(array.array("d", vals).tobytes())
    return memoryview(b).cast("d"), b


def v_bytearray_q(vals=I):
    b = bytearray(array.array("q", vals).tobytes())
    return memoryview(b).cast("q"), b


def v_numpy_d(vals=F):
    np = pytest.importorskip("numpy")
    a = np.array(vals, dtype=np.float64)
    return memoryview(a), a


def v_numpy_q(vals=I):
    np = pytest.importorskip("numpy")
    a = np.array(vals, dtype=np.int64)
    return memoryview(a), a


FLOAT_VIEWS = [v_array_d, v_bytearray_d, v_numpy_d]
INT_VIEWS = [v_array_q, v_array_l, v_bytearray_q, v_numpy_q]


# --- what is compiled ------------------------------------------------------------------------------

def test_memoryview_params_are_in_the_compiled_set(built):
    names = {f.name for f in built.proved.functions}
    assert names >= COMPILED, (sorted(COMPILED - names), built.proved.skipped)


def test_ir_shape_marks_memoryview_params(built):
    by = {f.name: f for f in built.proved.functions}
    assert (by["sum_mf"].params[0].buffer, by["sum_mf"].params[0].stored) == ("memoryview", False)
    assert (by["sum_mi"].params[0].buffer, by["sum_mi"].params[0].stored) == ("memoryview", False)
    assert by["scale_mf"].params[0].stored is True
    assert by["set_mi"].params[0].buffer == "memoryview"
    assert by["spin_af"].params[0].buffer == "array"


# --- reads, len, negative index, writes ------------------------------------------------------------

@pytest.mark.parametrize("view", FLOAT_VIEWS)
def test_float_reads_len_and_negative_index(built, view):
    m = built.module
    differential(m, "sum_mf", mk(view))
    differential(m, "length_mf", mk(view))
    differential(m, "sum_mf", mk(lambda: view([])))
    for i in (0, 2, -1, -4):
        differential(m, "get_mf", mk(view, i))


@pytest.mark.parametrize("view", INT_VIEWS)
def test_int_reads_and_negative_index(built, view):
    m = built.module
    differential(m, "sum_mi", mk(view))
    for i in (0, 3, -1, -4):
        differential(m, "get_mi", mk(view, i))


@pytest.mark.parametrize("view", FLOAT_VIEWS)
def test_float_writes_are_visible_to_the_caller(built, view):
    m = built.module
    differential(m, "set_mf", mk(view, 1, 9.5))
    differential(m, "set_mf", mk(view, -1, 9.5))
    differential(m, "scale_mf", mk(view, 2.0))
    v, owner = view()
    before = deopts(m)
    m.set_mf(v, 1, 42.0)
    m.scale_mf(v, 2.0)
    assert deopts(m) == before
    assert v.tolist() == [3.0, 84.0, 6.0, 9.0]
    assert as_list(owner, "d") == [3.0, 84.0, 6.0, 9.0]


@pytest.mark.parametrize("view", INT_VIEWS)
def test_int_writes_are_visible_to_the_caller(built, view):
    m = built.module
    differential(m, "set_mi", mk(view, 0, -77))
    differential(m, "set_mi", mk(view, 2, 2 ** 40))
    v, owner = view()
    m.set_mi(v, 3, 123)
    assert as_list(owner, "q") == [3, -5, 7, 123]


def test_view_of_a_slice_of_the_owner_is_the_same_memory(built):
    a = array.array("d", F)
    v = memoryview(a)[1:3]                     # 1-D, contiguous, offset by one element
    differential(built.module, "sum_mf", lambda: (memoryview(array.array("d", F))[1:3], ()))
    built.module.set_mf(v, 0, 100.0)
    assert a.tolist() == [1.5, 100.0, 3.0, 4.5]


# --- index errors ----------------------------------------------------------------------------------

@pytest.mark.parametrize("view", FLOAT_VIEWS)
def test_out_of_range_read_and_store_raise_memoryview_indexerror(built, view):
    m = built.module
    for name, args in (("get_mf", (4,)), ("get_mf", (-5,)), ("set_mf", (4, 1.0)),
                       ("set_mf", (-5, 1.0))):
        c = differential(m, name, mk(view, *args))
        assert c[0] == "raise" and c[1] is IndexError
        assert c[2] == "index out of bounds on dimension 1", c


def test_out_of_range_on_empty_and_int_views(built):
    m = built.module
    c = differential(m, "get_mf", mk(lambda: v_array_d([]), 0))
    assert c[1] is IndexError and c[2] == "index out of bounds on dimension 1"
    c = differential(m, "get_mi", mk(v_array_q, 99))
    assert c[1] is IndexError and c[2] == "index out of bounds on dimension 1"
    c = differential(m, "set_mi", mk(v_array_q, -5, 1))
    assert c[1] is IndexError and c[2] == "index out of bounds on dimension 1"


def test_wrong_value_type_matches_the_interpreter(built):
    # a memoryview 'd' store of a non-float: the interpreter's TypeError, not a silent conversion
    differential(built.module, "set_mf", mk(v_array_d, 0, 1), expect_deopt=True)


# --- object path: the interpreter raises or runs it -------------------------------------------------

def test_read_only_view_read_is_compiled_and_store_deopts(built):
    m = built.module
    ro = lambda: (memoryview(bytes(array.array("d", F).tobytes())).cast("d"), ())  # noqa: E731
    differential(m, "sum_mf", ro)                                   # reading needs no write access
    c = differential(m, "set_mf", lambda: ro()[:1] + (0, 1.0, ()), expect_deopt=True)
    assert c[0] == "raise" and c[1] is TypeError, c
    c = differential(m, "scale_mf", lambda: ro()[:1] + (2.0, ()), expect_deopt=True)
    assert c[0] == "raise"
    # all-zero bytes object, as in the issue
    c = differential(m, "set_mf",
                     lambda: (memoryview(bytes(16)).cast("d"), 0, 1.0, ()), expect_deopt=True)
    assert c[0] == "raise" and c[1] is TypeError


def test_non_contiguous_view_takes_the_object_path(built):
    m = built.module
    mkv = lambda: (memoryview(array.array("d", [1.0, 2.0, 3.0, 4.0, 5.0, 6.0]))[::2], ())  # noqa: E731
    differential(m, "sum_mf", mkv, expect_deopt=True)
    differential(m, "length_mf", mkv, expect_deopt=True)
    differential(m, "get_mf", lambda: mkv()[:1] + (1, ()), expect_deopt=True)
    differential(m, "scale_mf", lambda: mkv()[:1] + (3.0, ()), expect_deopt=True)


def test_two_dimensional_cast_takes_the_object_path(built):
    m = built.module
    mkv = lambda: (memoryview(array.array("d", [1.0, 2.0, 3.0, 4.0])).cast("B").cast("d", (2, 2)), ())  # noqa: E731
    c = differential(m, "sum_mf", mkv, expect_deopt=True)
    assert c[0] == "raise"
    differential(m, "length_mf", mkv, expect_deopt=True)
    differential(m, "get_mf", lambda: mkv()[:1] + (0, ()), expect_deopt=True)


def test_other_formats_take_the_object_path(built):
    m = built.module
    f = lambda: (memoryview(array.array("f", [1.0, 2.0])), ())            # noqa: E731
    differential(m, "sum_mf", f, expect_deopt=True)
    differential(m, "get_mf", lambda: f()[:1] + (0, ()), expect_deopt=True)
    i = lambda: (memoryview(array.array("i", [1, 2])), ())                # noqa: E731
    differential(m, "sum_mi", i, expect_deopt=True)
    differential(m, "sum_mi", lambda: (memoryview(array.array("d", F)), ()), expect_deopt=True)
    differential(m, "sum_mf", lambda: (memoryview(array.array("q", I)), ()), expect_deopt=True)
    differential(m, "sum_mi", lambda: (memoryview(b"abcd"), ()), expect_deopt=True)


def test_released_view_takes_the_object_path_and_raises_valueerror(built):
    m = built.module

    def released():
        v, owner = v_array_d()
        v.release()
        return v, (owner,)
    for name, extra in (("sum_mf", ()), ("length_mf", ()), ("get_mf", (0,)), ("set_mf", (0, 1.0))):
        def make(extra=extra):
            v, owner = released()
            return (v,) + extra + (owner,)
        c = differential(m, name, make, expect_deopt=True)
        assert c[0] == "raise" and c[1] is ValueError, c


@pytest.mark.parametrize("make_arg", [
    lambda: list(F),
    lambda: array.array("d", F),
    lambda: bytearray(32),
    lambda: bytes(32),
    lambda: None,
])
def test_not_a_memoryview_takes_the_object_path(built, make_arg):
    differential(built.module, "sum_mf", lambda: (make_arg(), ()), expect_deopt=True)
    differential(built.module, "length_mf", lambda: (make_arg(), ()), expect_deopt=True)


def test_memoryview_subclass_like_wrong_kind_in_array_param(built):
    # a memoryview where an array.array[float] is expected stays on the object path
    differential(built.module, "spin_af", lambda: (MF(), 4, ()), expect_deopt=True)


# --- the export is released after ok, error and deopt ----------------------------------------------

def can_resize(a):
    try:
        a.append(0)
    except BufferError:
        return False
    return True


def release_ok(v):
    try:
        v.release()
    except BufferError:
        return False
    return True


def test_export_released_after_ok_error_and_deopt(built):
    m = built.module
    # ok
    a = array.array("d", F)
    v = memoryview(a)
    before = deopts(m)
    m.sum_mf(v)
    assert deopts(m) == before
    assert release_ok(v) and can_resize(a)
    # error (index)
    a = array.array("d", F)
    v = memoryview(a)
    with pytest.raises(IndexError):
        m.get_mf(v, 99)
    with pytest.raises(IndexError):
        m.set_mf(v, 99, 1.0)
    assert release_ok(v) and can_resize(a)
    # deopt after entry: i64 overflow in the sum
    a = array.array("q", [2 ** 62, 2 ** 62, 2 ** 62])
    v = memoryview(a)
    before = deopts(m)
    assert m.sum_mi(v) == 3 * 2 ** 62
    assert deopts(m) == before + 1
    assert release_ok(v) and can_resize(a)
    # deopt at the guard: wrong format for the parameter
    a = array.array("d", F)
    v = memoryview(a)
    before = deopts(m)
    c = outcome(m.sum_mi, (v,))
    assert deopts(m) == before + 1
    assert c == outcome(interp(m, "sum_mi"), (v,))
    assert release_ok(v) and can_resize(a)
    # deopt: read-only store (export of a read-only view must not linger)
    ro = memoryview(bytes(16)).cast("d")
    with pytest.raises(TypeError):
        m.set_mf(ro, 0, 1.0)
    assert release_ok(ro)
    # the view itself stays usable after the call (it was not released by the call)
    a = array.array("d", F)
    v = memoryview(a)
    m.scale_mf(v, 2.0)
    assert v.tolist() == [x * 2 for x in F]
    v.release()
    a.append(1.0)


def test_export_is_held_only_during_the_call(built):
    a = array.array("d", F)
    v = memoryview(a)
    built.module.sum_mf(v)
    a2 = array.array("d", F)
    built.module.sum_mf(memoryview(a2))      # a temporary view: nothing keeps a2 pinned
    a2.append(1.0)
    del v
    a.append(1.0)
    assert len(a) == 5 and len(a2) == 5


# --- another thread while a compiled call runs ------------------------------------------------------

N = 1 << 16
SPIN = 600_000_000


def calibrated_spin(built):
    """Pick an iteration count so that the compiled call runs about 0.6 s on this machine."""
    a = array.array("d", [1.0] * N)
    t0 = time.perf_counter()
    built.module.spin_mf(memoryview(a), 20_000_000)
    dt = max(time.perf_counter() - t0, 1e-4)
    return int(20_000_000 * 0.6 / dt)


def probe_during_call(call, probes, attempts=3):
    """Run call() on the main thread while a worker applies each probe in a loop. Returns
    (result, {probe_name: (BufferError count, other outcomes)}). Retries `attempts` times when a
    probe never observed the call (machine too fast or too loaded)."""
    for _ in range(attempts):
        go, done = threading.Event(), threading.Event()
        seen = {name: [0, []] for name in probes}

        def worker():
            go.wait()
            time.sleep(0.05)
            while not done.is_set():
                for name, probe in probes.items():
                    try:
                        probe()
                    except BufferError:
                        seen[name][0] += 1
                    except Exception as e:  # noqa: BLE001
                        seen[name][1].append(repr(e))
                    else:
                        seen[name][1].append("succeeded")
                        return
                time.sleep(0.001)

        t = threading.Thread(target=worker, daemon=True)
        t.start()
        go.set()
        try:
            result = call()
        finally:
            done.set()
            t.join(timeout=30)
        assert not t.is_alive()
        if all(s[0] > 0 for s in seen.values()):
            break
    return result, {k: tuple(v) for k, v in seen.items()}


def test_other_thread_release_and_append_get_bufferror_during_a_memoryview_call(built):
    n = calibrated_spin(built)
    a = array.array("d", [1.0] * N)
    v = memoryview(a)
    expected = interp(built.module, "spin_mf")(memoryview(array.array("d", [1.0] * N)), 1000)
    assert expected == 1000.0
    result, seen = probe_during_call(
        lambda: built.module.spin_mf(v, n),
        {"release": v.release, "append": lambda: a.append(9.0)})
    assert result == float(n)
    assert seen["release"][0] > 0 and seen["release"][1] == [], seen
    assert seen["append"][0] > 0 and seen["append"][1] == [], seen
    assert len(a) == N                                   # nothing resized under the call
    assert release_ok(v) and can_resize(a)               # held only during the call


def test_other_thread_append_gets_bufferror_during_an_array_call(built):
    n = calibrated_spin(built)
    a = array.array("d", [1.0] * N)
    result, seen = probe_during_call(
        lambda: built.module.spin_af(a, n),
        {"append": lambda: a.append(9.0), "extend": lambda: a.extend([1.0]),
         "pop": lambda: a.pop()})
    assert result == float(n)
    for name in ("append", "extend", "pop"):
        assert seen[name][0] > 0 and seen[name][1] == [], (name, seen)
    assert len(a) == N
    assert can_resize(a)


def test_numpy_backed_view_blocks_release_during_a_call(built):
    np = pytest.importorskip("numpy")
    n = calibrated_spin(built)
    a = np.ones(N, dtype=np.float64)
    v = memoryview(a)
    result, seen = probe_during_call(lambda: built.module.spin_mf(v, n), {"release": v.release})
    assert result == float(n)
    assert seen["release"][0] > 0 and seen["release"][1] == [], seen
    assert release_ok(v)
