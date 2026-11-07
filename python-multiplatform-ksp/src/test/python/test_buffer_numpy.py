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
    import array
    import math
    import numpy as np
    import numpy.typing as npt


    @compiled
    def sum_ad(a: array.array[float]) -> float:
        s = 0.0
        for i in range(len(a)):
            s += a[i]
        return s


    @compiled
    def nd_first(x: npt.NDArray[np.float64]) -> object:
        return x[0]


    @compiled
    def nd_first_f(x: npt.NDArray[np.float64]) -> float:
        return x[0]


    @compiled
    def nd_first_i(x: npt.NDArray[np.int64]) -> int:
        return x[0]


    @compiled
    def nd_sum(x: npt.NDArray[np.float64]) -> float:
        s = 0.0
        for i in range(len(x)):
            s += x[i]
        return s


    @compiled
    def nd_sum_from_first(x: npt.NDArray[np.float64]) -> float:
        s = x[0]
        for i in range(1, len(x)):
            s += x[i]
        return s


    @compiled
    def nd_isum(x: npt.NDArray[np.int64]) -> int:
        s = 0
        for i in range(len(x)):
            s += x[i]
        return s


    @compiled
    def nd_gt(x: npt.NDArray[np.float64]) -> object:
        return x[0] > x[1]


    @compiled
    def nd_flag(x: npt.NDArray[np.float64]) -> object:
        b = x[0] > 0.0
        return b


    @compiled
    def nd_plain(x: npt.NDArray[np.float64]) -> object:
        return float(x[0])


    @compiled
    def nd_mixed(x: npt.NDArray[np.float64], k: float) -> object:
        return k + 1.0


    @compiled
    def nd_sqrt(x: npt.NDArray[np.float64]) -> object:
        return math.sqrt(x[0] * x[0])


    @compiled
    def nd_neg(x: npt.NDArray[np.float64]) -> object:
        return -x[1]


    @compiled
    def nd_len(x: npt.NDArray[np.float64]) -> int:
        return len(x)


    @compiled
    def nd_set(x: npt.NDArray[np.float64], i: int, v: float) -> None:
        x[i] = v


    @compiled
    def nd_copy(dst: npt.NDArray[np.float64], src: npt.NDArray[np.float64]) -> None:
        for i in range(len(dst)):
            dst[i] = src[i]


    @compiled
    def nd_iset(x: npt.NDArray[np.int64], i: int, v: int) -> None:
        x[i] = v


    @compiled
    def nd_get(x: npt.NDArray[np.float64], i: int) -> object:
        return x[i]


    @compiled
    def nd_overflow(x: npt.NDArray[np.float64]) -> object:
        return x[0] * x[1]


    @compiled
    def nd_invalid(x: npt.NDArray[np.float64]) -> object:
        return x[0] - x[1]


    @compiled
    def nd_iover(x: npt.NDArray[np.int64]) -> object:
        return x[0] + x[1]


    @compiled
    def nd_energy(x: npt.NDArray[np.float64], y: npt.NDArray[np.float64], z: npt.NDArray[np.float64],
                  vx: npt.NDArray[np.float64], vy: npt.NDArray[np.float64],
                  vz: npt.NDArray[np.float64], m: npt.NDArray[np.float64]) -> float:
        e = 0.0
        for i in range(len(m)):
            e += 0.5 * m[i] * (vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i])
        return e


    @compiled
    def nd_copy7(x: npt.NDArray[np.float64], y: npt.NDArray[np.float64], z: npt.NDArray[np.float64],
                 vx: npt.NDArray[np.float64], vy: npt.NDArray[np.float64],
                 vz: npt.NDArray[np.float64], m: npt.NDArray[np.float64]) -> None:
        for i in range(len(m)):
            x[i] = vx[i]
            y[i] = vy[i]
            z[i] = vz[i]
            m[i] = x[i]


    @compiled
    def nd_div(x: npt.NDArray[np.float64]) -> object:
        return x[0] / x[1]


    @compiled
    def nd_idiv(x: npt.NDArray[np.int64]) -> object:
        return x[0] // x[1]


    @compiled
    def nd_param(x: npt.NDArray[np.float64], k: float, use: bool) -> object:
        if use:
            k = x[0]
        return k


    @compiled
    def nd_branch(x: npt.NDArray[np.float64], use: bool) -> object:
        if use:
            v = x[0]
        else:
            v = 2.5
        return v


    @compiled
    def nd_tuple(x: npt.NDArray[np.int64]) -> tuple[int, int, bool]:
        return (x[0], 3, x[1] > 0)


    @compiled
    def nd_abs(x: npt.NDArray[np.float64]) -> object:
        return abs(x[1])


    @compiled
    def nd_to_list(x: npt.NDArray[np.float64], dst: list[float]) -> None:
        dst[0] = x[0]


    @compiled
    def nd_mixed_cmp(x: npt.NDArray[np.int64], f: float) -> object:
        if x[0] < f:
            return 1
        return 0


    @compiled
    def whole_sum(x: npt.NDArray[np.float64]) -> object:
        return np.sum(x)


    @compiled
    def whole_attr(x: npt.NDArray[np.float64]) -> object:
        return x.size


    @compiled
    def whole_pass(x: npt.NDArray[np.float64]) -> object:
        return np.add(x, 1.0).sum()


    @compiled
    def nd_int_impure(x: npt.NDArray[np.int64]) -> object:
        s = 0
        for i in range(len(x)):
            s += x[i]
        x[0] = 1
        return s


    @compiled
    def nd_scale(x: npt.NDArray[np.float64], k: float) -> None:
        for i in range(len(x)):
            x[i] = x[i] * k

    @compiled
    def p_div(x: npt.NDArray[np.float64]) -> object:
        return x[0] / x[1]


    @compiled
    def p_fdiv(x: npt.NDArray[np.float64]) -> object:
        return x[0] // x[1]


    @compiled
    def p_mod(x: npt.NDArray[np.float64]) -> object:
        return x[0] % x[1]


    @compiled
    def p_add(x: npt.NDArray[np.float64]) -> object:
        return x[0] + x[1]


    @compiled
    def p_sub(x: npt.NDArray[np.float64]) -> object:
        return x[0] - x[1]


    @compiled
    def p_mul(x: npt.NDArray[np.float64]) -> object:
        return x[0] * x[1]


    @compiled
    def p_pdiv(x: npt.NDArray[np.float64], k: float) -> object:
        return k / x[0]


    @compiled
    def p_idiv(x: npt.NDArray[np.int64]) -> object:
        return x[0] / x[1]


    @compiled
    def p_ifdiv(x: npt.NDArray[np.int64]) -> object:
        return x[0] // x[1]


    @compiled
    def p_imod(x: npt.NDArray[np.int64]) -> object:
        return x[0] % x[1]


    @compiled
    def p_imixed(x: npt.NDArray[np.int64]) -> object:
        return x[0] // 3 + x[1] % 4


    @compiled
    def i_div(x: npt.NDArray[np.float64]) -> None:
        x[2] = x[0] / x[1]


    @compiled
    def i_fdiv(x: npt.NDArray[np.float64]) -> None:
        x[2] = x[0] // x[1]


    @compiled
    def i_mod(x: npt.NDArray[np.float64]) -> None:
        x[2] = x[0] % x[1]


    @compiled
    def i_add(x: npt.NDArray[np.float64]) -> None:
        x[2] = x[0] + x[1]


    @compiled
    def i_sub(x: npt.NDArray[np.float64]) -> None:
        x[2] = x[0] - x[1]


    @compiled
    def i_mul(x: npt.NDArray[np.float64]) -> None:
        x[2] = x[0] * x[1]


    @compiled
    def i_plain(x: npt.NDArray[np.float64], k: float) -> None:
        x[2] = k / x[1]
        x[3] = k * 2.0


    @compiled
    def i_multiline(x: npt.NDArray[np.float64]) -> None:
        x[2] = (x[0] *
                x[1])
        x[3] = (x[0]
                / x[1]
                + x[0] * x[1])


    @compiled
    def i_aug(x: npt.NDArray[np.float64]) -> None:
        x[0] *= x[1]
        x[1] /= x[2]
        x[2] += x[3]


    @compiled
    def i_loop(x: npt.NDArray[np.float64], y: npt.NDArray[np.float64]) -> float:
        s = 0.0
        for i in range(len(x)):
            y[i] = x[i] / y[i]
            s += y[i] * x[i]
        return s


    @compiled
    def i_mixed(x: npt.NDArray[np.float64], n: int) -> None:
        x[0] = n / x[1]
        x[2] = x[3] * n


    @compiled
    def i_ret(x: npt.NDArray[np.float64]) -> object:
        x[2] = x[0] * x[1]
        return x[0] / x[1]


    @compiled
    def i_ifdiv(x: npt.NDArray[np.int64]) -> object:
        x[2] = x[0] // x[1]
        return x[2]


    @compiled
    def nd_advance(dt: float, steps: int, x: npt.NDArray[np.float64], y: npt.NDArray[np.float64],
                   z: npt.NDArray[np.float64], vx: npt.NDArray[np.float64],
                   vy: npt.NDArray[np.float64], vz: npt.NDArray[np.float64],
                   mass: npt.NDArray[np.float64]) -> None:
        for _ in range(steps):
            for i in range(5):
                for j in range(i + 1, 5):
                    dx: float = x[i] - x[j]
                    dy: float = y[i] - y[j]
                    dz: float = z[i] - z[j]
                    d2: float = dx * dx + dy * dy + dz * dz
                    mag: float = dt / (d2 * math.sqrt(d2))
                    vx[i] -= dx * mass[j] * mag
                    vy[i] -= dy * mass[j] * mag
                    vz[i] -= dz * mass[j] * mag
                    vx[j] += dx * mass[i] * mag
                    vy[j] += dy * mass[i] * mag
                    vz[j] += dz * mass[i] * mag
            for i in range(5):
                x[i] += dt * vx[i]
                y[i] += dt * vy[i]
                z[i] += dt * vz[i]


    @compiled
    def nd_energy_full(x: npt.NDArray[np.float64], y: npt.NDArray[np.float64],
                       z: npt.NDArray[np.float64], vx: npt.NDArray[np.float64],
                       vy: npt.NDArray[np.float64], vz: npt.NDArray[np.float64],
                       mass: npt.NDArray[np.float64]) -> float:
        e: float = 0.0
        for i in range(5):
            e += 0.5 * mass[i] * (vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i])
            for j in range(i + 1, 5):
                dx: float = x[i] - x[j]
                dy: float = y[i] - y[j]
                dz: float = z[i] - z[j]
                e -= mass[i] * mass[j] / math.sqrt(dx * dx + dy * dy + dz * dz)
        return e
    # #261: np.bool_ arrays (1-byte items, format "?")
    @compiled
    def nd_b_get(x: npt.NDArray[np.bool_], i: int) -> object:
        return x[i]


    @compiled
    def nd_b_any(x: npt.NDArray[np.bool_]) -> bool:
        for i in range(len(x)):
            if x[i]:
                return True
        return False


    @compiled
    def nd_b_count(x: npt.NDArray[np.bool_]) -> int:
        c = 0
        for i in range(len(x)):
            if x[i]:
                c += 1
        return c


    @compiled
    def nd_b_set(x: npt.NDArray[np.bool_], i: int, v: bool) -> None:
        x[i] = v


    @compiled
    def nd_b_flip(x: npt.NDArray[np.bool_]) -> None:
        for i in range(len(x)):
            x[i] = not x[i]


    @compiled
    def nd_b_copy(a: npt.NDArray[np.bool_], b: npt.NDArray[np.bool_]) -> None:
        for i in range(len(a)):
            b[i] = a[i]


    @compiled
    def nd_b_eq(x: npt.NDArray[np.bool_], y: npt.NDArray[np.bool_], i: int) -> object:
        return x[i] == y[i]


    @compiled
    def nd_b_gt(x: npt.NDArray[np.float64], m: npt.NDArray[np.bool_]) -> None:
        for i in range(len(x)):
            m[i] = x[i] > 2.0


    @compiled
    def nd_b_len(x: npt.NDArray[np.bool_]) -> int:
        return len(x)


    @compiled
    def nd_b_store_int(x: npt.NDArray[np.bool_], i: int, v: int) -> None:
        x[i] = v


    @compiled
    def nd_b_store_float(x: npt.NDArray[np.bool_], i: int, v: float) -> None:
        x[i] = v


    @compiled
    def nd_b_arith(x: npt.NDArray[np.bool_], i: int) -> object:
        return x[i] + 1


    @compiled
    def nd_b_whole(x: npt.NDArray[np.bool_]) -> object:
        return np.sum(x)
''')

COMPILED = {
    "nd_first", "nd_first_f", "nd_first_i", "nd_sum", "nd_sum_from_first", "nd_isum", "nd_gt",
    "nd_flag", "nd_plain", "nd_mixed", "nd_sqrt", "nd_neg", "nd_len", "nd_set", "nd_copy",
    "nd_iset", "nd_get", "nd_param", "nd_branch", "nd_tuple", "nd_overflow", "nd_invalid", "nd_iover", "nd_energy", "nd_copy7",
    # step 2
    "nd_div", "nd_idiv", "nd_scale", "p_div", "p_fdiv", "p_mod", "p_add", "p_sub", "p_mul", "p_pdiv",
    "p_idiv", "p_ifdiv", "p_imod", "p_imixed", "i_div", "i_fdiv", "i_mod", "i_add", "i_sub", "i_mul",
    "nd_b_get", "nd_b_any", "nd_b_count", "nd_b_set", "nd_b_flip", "nd_b_copy", "nd_b_eq", "nd_b_gt",
    "nd_b_len",
    "i_plain", "i_multiline", "i_aug", "i_loop", "i_mixed", "i_ret", "nd_advance", "nd_energy_full",
}
IMPURE = {"i_div", "i_fdiv", "i_mod", "i_add", "i_sub", "i_mul", "i_plain", "i_multiline", "i_aug",
          "i_loop", "i_mixed", "i_ret", "nd_advance", "nd_scale"}
NOT_COMPILED = {"nd_b_store_int", "nd_b_store_float", "nd_b_arith", "i_ifdiv", "nd_to_list", "nd_mixed_cmp", "nd_abs", "nd_int_impure"}   # nd_abs calls user code   # could warn / divide, or store a possibly warning op

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
    return r, [(x.category, str(x.message), x.filename, x.lineno) for x in w]


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


def test_division_matches_numpy(built):
    _, m, _ = built
    for make in (lambda: (f64([1.0, 0.0]),), lambda: (f64([-1.0, 0.0]),), lambda: (f64([0.0, 0.0]),),
                 lambda: (f64(),)):
        differential(m, "nd_div", make, expect_deopt=make is not None and make()[0][1] == 0.0)
    c, w = differential(m, "nd_idiv", lambda: (i64([5, 0]),), expect_deopt=True)
    assert [x[0] for x in w] == [RuntimeWarning]
    differential(m, "nd_idiv", lambda: (i64([5, 2]),))
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


# --- step 2: numpy division and impure arithmetic ---------------------------------------------------------

SPECIAL = [0.0, -0.0, 1.0, -1.0, 2.5, -2.5, 1e308, -1e308, 1e-310, 5e-324, float("inf"),
           float("-inf"), float("nan"), 0.3]
IMIN = -2 ** 63
SPECIAL_I = [0, 1, -1, 7, -7, IMAX, IMIN]


def grid(m, name, pairs, make, pure):
    """Every pair, compiled against interpreted: result, TYPE, warnings with file and line."""
    seen = set()
    for a, b in pairs:
        ca, ia = make(a, b), make(a, b)
        before = m.__typedpython_deopts__
        c, cw = run(getattr(m, name), ca)
        deopted = m.__typedpython_deopts__ - before
        i, iw = run(m.__typedpython_interpreted__[name], ia)
        assert c[0] == i[0], (name, a, b, c, i)
        if c[0] == "ok" and c[1] is not None:
            assert type(c[1]) is type(i[1]), (name, a, b)
            assert same(c[1], i[1]), (name, a, b, c, i)
        assert cw == iw, (name, a, b, cw, iw)
        for x, y in zip(ca, ia):
            if isinstance(x, np.ndarray):
                assert x.dtype == y.dtype
                assert all(same(p, q) and np.signbit(p) == np.signbit(q)
                           for p, q in zip(x.tolist(), y.tolist()) if p == p), (name, a, b, x, y)
                assert all((p != p) == (q != q) for p, q in zip(x.tolist(), y.tolist()))
        assert deopted == (1 if pure and cw else 0), (name, a, b, deopted, cw)
        seen.update(str(w[1]) for w in cw)
    return seen


def fpairs():
    return [(a, b) for a in SPECIAL for b in SPECIAL]


@pytest.mark.parametrize("name", ["p_div", "p_fdiv", "p_mod", "p_add", "p_sub", "p_mul"])
def test_pure_float_ops_match_numpy_over_special_values(built, name):
    _, m, _ = built
    seen = grid(m, name, fpairs(), lambda a, b: (f64([a, b]),), pure=True)
    if name == "p_div":
        assert {"divide by zero encountered in scalar divide", "invalid value encountered in scalar divide",
                "overflow encountered in scalar divide"} <= seen


@pytest.mark.parametrize("name", ["i_div", "i_fdiv", "i_mod", "i_add", "i_sub", "i_mul"])
def test_impure_float_ops_match_numpy_and_warn_from_the_compiled_code(built, name):
    _, m, _ = built
    seen = grid(m, name, fpairs(), lambda a, b: (f64([a, b, 0.0]),), pure=False)
    assert seen, "numpy warned for no pair: the grid tests nothing"


def test_impure_warning_has_the_operations_file_and_line(built):
    _, m, _ = built
    _, w = run(m.i_multiline, (f64([BIG, 10.0, 0.0, 0.0]),))
    lines = [x[3] for x in w]
    assert len(set(lines)) >= 2, w
    grid(m, "i_multiline", [(a, b) for a in (1.0, 0.0, BIG, float("inf")) for b in (0.0, 1.0, BIG)],
         lambda a, b: (f64([a, b, 0.0, 0.0]),), pure=False)
    grid(m, "i_aug", [(a, b) for a in (1.0, 0.0, BIG) for b in (0.0, 1.0, BIG, float("nan"))],
         lambda a, b: (f64([a, b, 0.0, BIG]),), pure=False)


def test_impure_error_filter_raises_and_leaves_the_same_state(built):
    _, m, _ = built
    for fn in (m.i_div, m.__typedpython_interpreted__["i_div"]):
        x = f64([1.0, 0.0, 7.0])
        with warnings.catch_warnings():
            warnings.simplefilter("error")
            with pytest.raises(RuntimeWarning, match="divide by zero encountered in scalar divide"):
                fn(x)
        assert x.tolist() == [1.0, 0.0, 7.0]


def test_impure_default_filter_warns_once_per_location(built):
    _, m, _ = built
    out = []
    for fn in (m.i_div, m.__typedpython_interpreted__["i_div"]):
        with warnings.catch_warnings(record=True) as w:
            warnings.simplefilter("default")
            m.__dict__.pop("__warningregistry__", None)
            for _ in range(3):
                fn(f64([1.0, 0.0, 0.0]))
        out.append([(x.filename, x.lineno, str(x.message)) for x in w])
    assert out[0] == out[1] and len(out[0]) == 1, out


def test_impure_mixed_plain_and_numpy_operands(built):
    _, m, _ = built
    grid(m, "i_plain", [(a, b) for a in (1.0, 0.0, BIG) for b in (0.0, 2.0, -0.0)],
         lambda a, b: (f64([0.0, b, 0.0, 0.0]), a), pure=False)
    grid(m, "i_mixed", [(a, b) for a in (1, 0, 2 ** 62) for b in (0.0, 2.0, 1e-310)],
         lambda a, b: (f64([0.0, b, 0.0, BIG]), a), pure=False)
    grid(m, "p_pdiv", [(a, b) for a in (1.0, 0.0, BIG) for b in (0.0, 2.0, 1e-310)],
         lambda a, b: (f64([b, 0.0]), a), pure=True)
    for name in ("i_loop", "i_ret"):
        for xs, ys in (([1.0, 2.0, 0.0], [0.0, 4.0, 2.0]), ([BIG, 1.0, 0.0], [1e-5, 0.0, 0.0])):
            args = lambda: ((f64(xs), f64(ys)) if name == "i_loop" else (f64(xs),))  # noqa: E731
            differential(m, name, args)


@pytest.mark.parametrize("name", ["p_idiv", "p_ifdiv", "p_imod"])
def test_pure_int_division_matches_numpy(built, name):
    _, m, _ = built
    pairs = [(a, b) for a in SPECIAL_I for b in SPECIAL_I]
    grid(m, name, pairs, lambda a, b: (i64([a, b]),), pure=True)


def test_int_division_by_a_constant_and_mixed_ops(built):
    _, m, _ = built
    grid(m, "p_imixed", [(a, b) for a in SPECIAL_I for b in SPECIAL_I],
         lambda a, b: (i64([a, b]),), pure=True)
    differential(m, "p_idiv", lambda: (i64([2 ** 60 + 1, 3]),))     # numpy divides as doubles


def test_the_int_divide_by_zero_in_an_impure_function_stays_on_the_object_path(built):
    proved, m, _ = built
    assert "i_ifdiv" in proved.skipped
    differential(m, "i_ifdiv", lambda: (i64([5, 0, 0]),))


def test_a_disabled_warning_path_would_differ(built):
    """The check that can fail: the compiled impure i_div must emit the warning itself."""
    _, m, _ = built
    _, w = run(m.i_div, (f64([1.0, 0.0, 0.0]),))
    assert [(x[0], x[1]) for x in w] == [(RuntimeWarning, "divide by zero encountered in scalar divide")]


BODIES = {
    "x": [0.0, 4.84143144246472090, -4.15980459285970104e-1, 1.2 , 8.34336671824457987],
    "y": [0.0, -1.16032004402742839, 7.69901118419740425e-1, -1.2, 4.12479856412430479],
    "z": [0.0, -1.03622044471123109e-1, -8.5e-1, 0.5, -4.0e-1],
    "vx": [0.0, 1.66007664274403694e-3 * 365.24, -2.76742510726862411e-3 * 365.24, 0.1, 0.2],
    "vy": [0.0, 7.69901118419740425e-3 * 365.24, 4.99852801234917238e-3 * 365.24, 0.3, 0.1],
    "vz": [0.0, -6.90460016972063023e-5 * 365.24, 2.30417297573763929e-5 * 365.24, -0.2, 0.05],
    "mass": [39.47841760435743, 0.03769367487038949, 0.011286326131968767, 0.0017237240570597112, 0.0020336868699246304],
}


def bodies():
    return [np.array(BODIES[k], dtype=np.float64) for k in ("x", "y", "z", "vx", "vy", "vz", "mass")]


def test_nbody_advance_and_energy_compile_and_match(built):
    proved, m, _ = built
    by = {f.name: f for f in proved.functions}
    assert "nd_advance" in by and "nd_energy_full" in by, proved.skipped
    assert by["nd_advance"].pure is False and by["nd_energy_full"].pure is True
    ref = m.__typedpython_interpreted__
    a, b = bodies(), bodies()
    for steps in (1, 25):
        before = m.__typedpython_deopts__
        with warnings.catch_warnings(record=True) as w:
            warnings.simplefilter("always")
            m.nd_advance(0.01, steps, *a)
            ref["nd_advance"](0.01, steps, *b)
        assert m.__typedpython_deopts__ == before
        assert w == []
        for p, q in zip(a, b):
            assert p.tolist() == q.tolist()
        e1, e2 = m.nd_energy_full(*a), ref["nd_energy_full"](*b)
        assert type(e1) is type(e2) is np.float64 and e1 == e2
    # coincident bodies: the interpreter's warnings and results, from the compiled advance
    a, b = bodies(), bodies()
    for arrs in (a, b):
        arrs[0][2] = arrs[0][1]; arrs[1][2] = arrs[1][1]; arrs[2][2] = arrs[2][1]
    ca, cw = run(m.nd_advance, (0.01, 1, *a))
    ia, iw = run(ref["nd_advance"], (0.01, 1, *b))
    assert cw == iw and cw, (cw, iw)
    assert all(same(p, q) for u, v in zip(a, b) for p, q in zip(u.tolist(), v.tolist()))


# --- #261: bool arrays (1-byte elements) ---------------------------------------------------------------

def bl(v=(True, False, True, True)):
    return np.array(v, dtype=np.bool_)


def test_bool_array_params_are_numpy_buffers_of_one_byte_items(built):
    proved, _, c = built
    by = {f.name: f for f in proved.functions}
    assert by["nd_b_get"].params[0].buffer == "numpy"
    assert by["nd_b_get"].params[0].type is ir.Type.BOOL_ARRAY
    assert by["nd_b_set"].params[0].stored
    assert '"?"' in c


def test_bool_element_is_np_bool(built):
    _, m, _ = built
    for i in (0, 1, -1):
        c, _ = differential(m, "nd_b_get", lambda: (bl(), i))
        assert type(c[1]) is np.bool_


def test_bool_loops_and_results_match(built):
    _, m, _ = built
    for v in ([], [False], [False, False], [True, False, True], [False] * 9 + [True]):
        c, _ = differential(m, "nd_b_any", lambda: (bl(v),))
        assert type(c[1]) is bool
        c, _ = differential(m, "nd_b_count", lambda: (bl(v),))
        assert type(c[1]) is int
        differential(m, "nd_b_len", lambda: (bl(v),))
        differential(m, "nd_b_flip", lambda: (bl(v),))
    differential(m, "nd_b_copy", lambda: (bl(), bl([False] * 4)))


def test_bool_stores_and_negative_indexes(built):
    _, m, _ = built
    for i in (0, 3, -1, -4):
        for v in (True, False):
            differential(m, "nd_b_set", lambda: (bl(), i, v))
    differential(m, "nd_b_eq", lambda: (bl(), bl([True, True, False, True]), 1))
    c, _ = differential(m, "nd_b_eq", lambda: (bl(), bl(), 0))
    assert type(c[1]) is np.bool_
    differential(m, "nd_b_gt", lambda: (f64(), bl([False] * 4)))


@pytest.mark.parametrize("i", [4, -5, 100])
def test_bool_out_of_range_has_numpys_message(built, i):
    _, m, _ = built
    c, _ = differential(m, "nd_b_get", lambda: (bl(), i))
    assert c[0] == "raise" and c[1] is IndexError and "out of bounds for axis 0 with size 4" in c[2]
    c, _ = differential(m, "nd_b_set", lambda: (bl(), i, True))
    assert c[0] == "raise" and "out of bounds for axis 0 with size 4" in c[2]


def test_a_store_numpy_converts_differently_stays_interpreted(built):
    proved, m, _ = built
    for n in ("nd_b_store_int", "nd_b_store_float", "nd_b_arith"):
        assert n in proved.skipped
    for v in (0, 1, 5, -3, 2 ** 70):
        differential(m, "nd_b_store_int", lambda: (bl(), 1, v))
    for v in (0.0, 0.5, float("nan")):
        differential(m, "nd_b_store_float", lambda: (bl(), 2, v))
    differential(m, "nd_b_arith", lambda: (bl(), 0))


def test_bool_guard_refuses_other_dtypes_and_views(built):
    _, m, _ = built
    differential(m, "nd_b_get", lambda: (bl(), 0))
    differential(m, "nd_b_get", lambda: (np.array([1, 0], dtype=np.uint8), 0), expect_deopt=True)
    differential(m, "nd_b_get", lambda: (f64(), 0), expect_deopt=True)
    differential(m, "nd_b_get", lambda: (np.array([True, False, True, False])[::2], 0), expect_deopt=True)
    differential(m, "nd_first", lambda: (bl(),), expect_deopt=True)      # a float64 function, bool array
    ro = bl()
    ro.setflags(write=False)
    differential(m, "nd_b_set", lambda: (ro, 0, True), expect_deopt=True)


def test_bool_whole_array_use_is_an_opaque_object(built):
    proved, m, _ = built
    f = next((f for f in proved.functions if f.name == "nd_b_whole"), None)
    assert f is not None and not any(isinstance(p, ir.ArrayParam) for p in f.params)
    differential(m, "nd_b_whole", lambda: (bl(),))
