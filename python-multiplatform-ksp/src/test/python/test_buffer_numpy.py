"""numpy array parameters on the zero-copy buffer path (issue #220, step 1; SPEC N-8 / N-9).

An `NDArray[np.float64]` / `NDArray[np.int64]` parameter is read and written in place like an
`array.array`. What differs is what the interpreter computes: an element is a numpy scalar, so every
value derived from one is an `np.float64` / `np.int64` / `np.bool` when it escapes, numpy emits
RuntimeWarnings on overflow / invalid value, and numpy divides without raising. Step 1 reproduces
the result and its TYPE exactly (a run-time taint flag per local), and never differs silently on a
warning or a division: a pure function deopts (the interpreter then emits the warning), anything
else that could warn or divide is not compiled. Every test compares result type, value, emitted
warnings (`warnings.catch_warnings(record=True)`) and final array contents with the interpreted
function. Skipped without numpy.
"""
import textwrap
import warnings

import pytest

np = pytest.importorskip("numpy")

from typedpython import cbuild, ir  # noqa: E402
from test_buffer_arrays import build_source, load_so  # noqa: E402

SOURCE = textwrap.dedent('''
    # typedpython: compiled
    import array
    import math
    import numpy as np
    import numpy.typing as npt


    def sum_ad(a: array.array[float]) -> float:
        s = 0.0
        for i in range(len(a)):
            s += a[i]
        return s


    def nd_first(x: npt.NDArray[np.float64]) -> object:
        return x[0]


    def nd_first_f(x: npt.NDArray[np.float64]) -> float:
        return x[0]


    def nd_first_i(x: npt.NDArray[np.int64]) -> int:
        return x[0]


    def nd_sum(x: npt.NDArray[np.float64]) -> float:
        s = 0.0
        for i in range(len(x)):
            s += x[i]
        return s


    def nd_sum_from_first(x: npt.NDArray[np.float64]) -> float:
        s = x[0]
        for i in range(1, len(x)):
            s += x[i]
        return s


    def nd_isum(x: npt.NDArray[np.int64]) -> int:
        s = 0
        for i in range(len(x)):
            s += x[i]
        return s


    def nd_gt(x: npt.NDArray[np.float64]) -> object:
        return x[0] > x[1]


    def nd_flag(x: npt.NDArray[np.float64]) -> object:
        b = x[0] > 0.0
        return b


    def nd_plain(x: npt.NDArray[np.float64]) -> object:
        return float(x[0])


    def nd_mixed(x: npt.NDArray[np.float64], k: float) -> object:
        return k + 1.0


    def nd_sqrt(x: npt.NDArray[np.float64]) -> object:
        return math.sqrt(x[0] * x[0])


    def nd_neg(x: npt.NDArray[np.float64]) -> object:
        return -x[1]


    def nd_len(x: npt.NDArray[np.float64]) -> int:
        return len(x)


    def nd_set(x: npt.NDArray[np.float64], i: int, v: float) -> None:
        x[i] = v


    def nd_copy(dst: npt.NDArray[np.float64], src: npt.NDArray[np.float64]) -> None:
        for i in range(len(dst)):
            dst[i] = src[i]


    def nd_iset(x: npt.NDArray[np.int64], i: int, v: int) -> None:
        x[i] = v


    def nd_get(x: npt.NDArray[np.float64], i: int) -> object:
        return x[i]


    def nd_overflow(x: npt.NDArray[np.float64]) -> object:
        return x[0] * x[1]


    def nd_invalid(x: npt.NDArray[np.float64]) -> object:
        return x[0] - x[1]


    def nd_iover(x: npt.NDArray[np.int64]) -> object:
        return x[0] + x[1]


    def nd_energy(x: npt.NDArray[np.float64], y: npt.NDArray[np.float64], z: npt.NDArray[np.float64],
                  vx: npt.NDArray[np.float64], vy: npt.NDArray[np.float64],
                  vz: npt.NDArray[np.float64], m: npt.NDArray[np.float64]) -> float:
        e = 0.0
        for i in range(len(m)):
            e += 0.5 * m[i] * (vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i])
        return e


    def nd_copy7(x: npt.NDArray[np.float64], y: npt.NDArray[np.float64], z: npt.NDArray[np.float64],
                 vx: npt.NDArray[np.float64], vy: npt.NDArray[np.float64],
                 vz: npt.NDArray[np.float64], m: npt.NDArray[np.float64]) -> None:
        for i in range(len(m)):
            x[i] = vx[i]
            y[i] = vy[i]
            z[i] = vz[i]
            m[i] = x[i]


    def nd_div(x: npt.NDArray[np.float64]) -> object:
        return x[0] / x[1]


    def nd_idiv(x: npt.NDArray[np.int64]) -> object:
        return x[0] // x[1]


    def nd_param(x: npt.NDArray[np.float64], k: float, use: bool) -> object:
        if use:
            k = x[0]
        return k


    def nd_branch(x: npt.NDArray[np.float64], use: bool) -> object:
        if use:
            v = x[0]
        else:
            v = 2.5
        return v


    def nd_tuple(x: npt.NDArray[np.int64]) -> tuple[int, int, bool]:
        return (x[0], 3, x[1] > 0)


    def nd_abs(x: npt.NDArray[np.float64]) -> object:
        return abs(x[1])


    def nd_to_list(x: npt.NDArray[np.float64], dst: list[float]) -> None:
        dst[0] = x[0]


    def nd_mixed_cmp(x: npt.NDArray[np.int64], f: float) -> object:
        if x[0] < f:
            return 1
        return 0


    def whole_sum(x: npt.NDArray[np.float64]) -> object:
        return np.sum(x)


    def whole_attr(x: npt.NDArray[np.float64]) -> object:
        return x.size


    def whole_pass(x: npt.NDArray[np.float64]) -> object:
        return np.add(x, 1.0).sum()


    def nd_int_impure(x: npt.NDArray[np.int64]) -> object:
        s = 0
        for i in range(len(x)):
            s += x[i]
        x[0] = 1
        return s


    def nd_scale(x: npt.NDArray[np.float64], k: float) -> None:
        for i in range(len(x)):
            x[i] = x[i] * k
''')

COMPILED = {
    "nd_first", "nd_first_f", "nd_first_i", "nd_sum", "nd_sum_from_first", "nd_isum", "nd_gt",
    "nd_flag", "nd_plain", "nd_mixed", "nd_sqrt", "nd_neg", "nd_len", "nd_set", "nd_copy",
    "nd_iset", "nd_get", "nd_param", "nd_branch", "nd_tuple", "nd_overflow", "nd_invalid", "nd_iover", "nd_energy", "nd_copy7",
}
NOT_COMPILED = {"nd_div", "nd_idiv", "nd_scale", "nd_to_list", "nd_mixed_cmp", "nd_abs", "nd_int_impure"}   # nd_abs calls user code   # could warn / divide, or store a possibly warning op

F = [1.5, -2.25, 3.0, 4.5]
I = [3, -5, 7, 11]
BIG = 1.7e308
IMAX = 2 ** 63 - 1


@pytest.fixture(scope="module")
def built(tmp_path_factory):
    d = tmp_path_factory.mktemp("bufnp")
    path, proved, diags, c = build_source(d, SOURCE)
    so = cbuild.build(c, path.stem, d / "out")
    return proved, load_so(so), c


def run(fn, args):
    with warnings.catch_warnings(record=True) as w:
        warnings.simplefilter("always")
        try:
            r = ("ok", fn(*args))
        except Exception as e:  # noqa: BLE001 - type and text are the observable
            r = ("raise", type(e), str(e))
    return r, [(x.category, str(x.message), x.lineno) for x in w]


def same(a, b):
    return a == b or (a != a and b != b)


def differential(m, name, make, expect_deopt=False):
    ca, ia = make(), make()
    before = m.__typedpython_deopts__
    c, cw = run(getattr(m, name), ca)
    deopted = m.__typedpython_deopts__ - before
    ref = m.__typedpython_interpreted__.get(name) or getattr(m, name)   # not compiled: itself
    i, iw = run(ref, ia)
    assert c[0] == i[0], (c, i)
    if c[0] == "ok":
        assert type(c[1]) is type(i[1]), (type(c[1]), type(i[1]))
        assert same(c[1], i[1]), (c, i)
    else:
        assert c[1:] == i[1:], (c, i)
    assert cw == iw, (cw, iw)
    for x, y in zip(ca, ia):
        if isinstance(x, np.ndarray):
            assert x.dtype == y.dtype and x.shape == y.shape
            assert all(same(p, q) for p, q in zip(x.tolist(), y.tolist())), (x, y)
    assert deopted == (1 if expect_deopt else 0), f"deopts: {deopted}, expected {expect_deopt}"
    return c, cw


def f64(v=F):
    return np.array(v, dtype=np.float64)


def i64(v=I):
    return np.array(v, dtype=np.int64)


# --- what is compiled ------------------------------------------------------------------------------

def test_compiled_set(built):
    proved, _, _ = built
    names = {f.name for f in proved.functions}
    assert COMPILED <= names, (sorted(COMPILED - names), proved.skipped)
    for n in NOT_COMPILED:
        assert n in proved.skipped, n
        assert n not in names


def test_ir_marks_numpy_buffers(built):
    proved, _, _ = built
    by = {f.name: f for f in proved.functions}
    assert [(p.buffer, p.stored) for p in by["nd_first"].params] == [("numpy", False)]
    assert by["nd_set"].params[0].buffer == "numpy" and by["nd_set"].params[0].stored
    assert by["nd_isum"].params[0].type is ir.Type.I64_ARRAY
    assert by["nd_energy"].pure is True
    assert sum(p.buffer == "numpy" for p in by["nd_energy"].params) == 7


def test_seven_numpy_arrays_compile_and_run(built):
    _, m, _ = built
    arrs = lambda: tuple(f64([k + 0.5, k + 1.5, k + 2.5]) for k in range(7))
    c, _ = differential(m, "nd_energy", arrs)
    assert type(c[1]) is np.float64
    differential(m, "nd_copy7", arrs)


def test_array_array_path_is_unchanged(built):
    import array
    _, m, _ = built
    r = m.sum_ad(array.array("d", F))
    assert type(r) is float and r == sum(F)


# --- results and result types ------------------------------------------------------------------------

@pytest.mark.parametrize("name", ["nd_first", "nd_first_f"])
def test_element_is_a_numpy_scalar(built, name):
    _, m, _ = built
    c, _ = differential(m, name, lambda: (f64(),))
    assert type(c[1]) is np.float64


def test_int_element_is_np_int64_whatever_the_annotation_says(built):
    _, m, _ = built
    c, _ = differential(m, "nd_first_i", lambda: (i64(),))
    assert type(c[1]) is np.int64


@pytest.mark.parametrize("name,make,t", [
    ("nd_sum", lambda: (f64(),), np.float64),
    ("nd_sum", lambda: (f64([]),), float),            # zero iterations: the plain 0.0
    ("nd_sum_from_first", lambda: (f64(),), np.float64),
    ("nd_isum", lambda: (i64(),), np.int64),
    ("nd_isum", lambda: (i64([]),), int),
    ("nd_gt", lambda: (f64(),), np.bool_),
    ("nd_flag", lambda: (f64(),), np.bool_),
    ("nd_plain", lambda: (f64(),), float),            # float(np.float64) is a plain float
    ("nd_sqrt", lambda: (f64(),), float),             # math.sqrt returns a float
    ("nd_neg", lambda: (f64(),), np.float64),
    ("nd_len", lambda: (f64(),), int),
])
def test_result_type_follows_the_interpreter(built, name, make, t):
    _, m, _ = built
    c, _ = differential(m, name, make)
    assert type(c[1]) is t


def test_plain_float_result_stays_float(built):
    _, m, _ = built
    c, _ = differential(m, "nd_mixed", lambda: (f64(), 2.0))
    assert type(c[1]) is float


def test_reads_stores_and_negative_indexes(built):
    _, m, _ = built
    for i in (0, 3, -1, -4):
        differential(m, "nd_get", lambda: (f64(), i))
        differential(m, "nd_set", lambda: (f64(), i, 9.25))
        differential(m, "nd_iset", lambda: (i64(), i, 77))
    differential(m, "nd_copy", lambda: (f64([0.0] * 4), f64()))


@pytest.mark.parametrize("i", [4, -5, 100])
def test_out_of_range_has_numpys_message(built, i):
    _, m, _ = built
    c, _ = differential(m, "nd_get", lambda: (f64(), i))
    assert c[0] == "raise" and c[1] is IndexError and "out of bounds for axis 0 with size 4" in c[2]
    c, _ = differential(m, "nd_set", lambda: (f64(), i, 1.0))
    assert c[0] == "raise" and "out of bounds for axis 0 with size 4" in c[2]


def test_object_path_for_arrays_the_guard_refuses(built):
    _, m, _ = built
    x = f64(list(range(8)))
    differential(m, "nd_first", lambda: (x[::2].copy(),))             # fine, contiguous
    differential(m, "nd_first", lambda: (x[::2],), expect_deopt=True)  # strided view
    differential(m, "nd_first", lambda: (i64(),), expect_deopt=True)   # wrong dtype
    differential(m, "nd_first", lambda: (f64().astype(">f8"),), expect_deopt=True)
    ro = f64()
    ro.setflags(write=False)
    differential(m, "nd_set", lambda: (ro, 0, 1.0), expect_deopt=True)  # numpy's own error
    differential(m, "nd_first", lambda: ([1.0, 2.0],), expect_deopt=True)


# --- warnings: a pure function deopts, the interpreter emits them -----------------------------------------

def test_float_overflow_warning_comes_from_the_interpreter(built):
    _, m, _ = built
    c, w = differential(m, "nd_overflow", lambda: (f64([BIG, 10.0]),), expect_deopt=True)
    assert [x[0] for x in w] == [RuntimeWarning] and "overflow" in w[0][1]
    differential(m, "nd_overflow", lambda: (f64([2.0, 10.0]),))          # no warning, no deopt


def test_invalid_warning_comes_from_the_interpreter(built):
    _, m, _ = built
    inf = float("inf")
    c, w = differential(m, "nd_invalid", lambda: (f64([inf, inf]),), expect_deopt=True)
    assert [x[0] for x in w] == [RuntimeWarning] and "invalid" in w[0][1]


def test_int_overflow_warning_comes_from_the_interpreter(built):
    _, m, _ = built
    c, w = differential(m, "nd_iover", lambda: (i64([IMAX, 1]),), expect_deopt=True)
    assert [x[0] for x in w] == [RuntimeWarning] and "overflow" in w[0][1]
    differential(m, "nd_iover", lambda: (i64(),))


def test_division_and_impure_arithmetic_stay_on_the_object_path(built):
    _, m, _ = built
    for make in (lambda: (f64([1.0, 0.0]),), lambda: (f64([-1.0, 0.0]),), lambda: (f64([0.0, 0.0]),),
                 lambda: (f64(),)):
        differential(m, "nd_div", make)
    c, w = differential(m, "nd_idiv", lambda: (i64([5, 0]),))
    assert [x[0] for x in w] == [RuntimeWarning]
    differential(m, "nd_scale", lambda: (f64([BIG, 1.0]), 10.0))
    differential(m, "nd_scale", lambda: (f64(), 2.0))


def test_a_compiled_run_emits_no_warning_when_the_interpreter_emits_none(built):
    _, m, _ = built
    with warnings.catch_warnings(record=True) as w:
        warnings.simplefilter("always")
        m.nd_sum(f64())
        m.nd_isum(i64())
        m.nd_energy(*(f64() for _ in range(7)))
    assert w == []


def test_taint_is_per_value_at_run_time(built):
    _, m, _ = built
    for use in (True, False):
        c, _ = differential(m, "nd_param", lambda: (f64(), 7.0, use))
        assert type(c[1]) is (np.float64 if use else float)
        c, _ = differential(m, "nd_branch", lambda: (f64(), use))
        assert type(c[1]) is (np.float64 if use else float)


def test_tuple_and_call_arguments_carry_numpy_scalars(built):
    _, m, _ = built
    c, _ = differential(m, "nd_tuple", lambda: (i64(),))
    assert [type(v) for v in c[1]] == [np.int64, int, np.bool_]
    c, _ = differential(m, "nd_abs", lambda: (f64(),))   # interpreted: calls user code
    assert type(c[1]) is np.float64


def test_a_numpy_value_stored_into_a_list_or_compared_mixed_stays_interpreted(built):
    _, m, _ = built
    differential(m, "nd_to_list", lambda: (f64(), [0.0]))
    differential(m, "nd_mixed_cmp", lambda: (i64([2 ** 53 + 1]), float(2 ** 53)))


@pytest.mark.parametrize("name", ["whole_sum", "whole_attr", "whole_pass"])
def test_whole_array_functions_still_compile_with_an_opaque_parameter(built, name):
    proved, m, _ = built
    f = next((f for f in proved.functions if f.name == name), None)
    assert f is not None, proved.skipped.get(name)
    assert not any(isinstance(p, ir.ArrayParam) for p in f.params)
    c, _ = differential(m, name, lambda: (f64(),))
    if name == "whole_sum":
        assert type(c[1]) is np.float64


def test_a_numpy_value_never_becomes_a_native_int(built):
    proved, m, _ = built
    assert "nd_int_impure" in proved.skipped
    assert not any(type(n).__name__ == "ToInt" for f in proved.functions
                   if f.name.startswith("nd_") for n in _nodes(f.body))
    c, _ = differential(m, "nd_int_impure", lambda: (i64(),))
    assert type(c[1]) is np.int64


def _nodes(x):
    import dataclasses
    if isinstance(x, (tuple, list)):
        for y in x:
            yield from _nodes(y)
    elif dataclasses.is_dataclass(x) and not isinstance(x, type):
        yield x
        for f in dataclasses.fields(x):
            yield from _nodes(getattr(x, f.name))
