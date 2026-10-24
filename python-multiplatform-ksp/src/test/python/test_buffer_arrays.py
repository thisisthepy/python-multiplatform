"""Zero-copy buffer-protocol array parameters (issue #149, identity-safe part; SPEC N-8 / N-9).

A parameter annotated `array.array[float]` or `array.array[int]` is a *buffer array*: the entry
guard takes a `Py_buffer` export of the exact type, holds it until the function returns on every
exit, and every element access goes straight to the buffer memory as one relaxed atomic 8-byte load
or store. No copy-in, no write-back.

numpy is not supported (#220): an `NDArray[...]` parameter is an ordinary object parameter and the
function runs as the interpreter runs it (one test below pins that).

Everything here is single-threaded and differential against the interpreted function
(`__typedpython_interpreted__`): same result, same exception type and text, same final contents of
every argument. Tests that a path was *compiled* compare `__typedpython_deopts__` before and after,
so a function that silently stayed interpreted fails them.

Not covered, on purpose: what a concurrent resize by another thread observes (open point of #149;
API.md "Buffer arrays").
"""
from __future__ import annotations

import array
import dataclasses
import importlib.machinery
import importlib.util
import textwrap

import pytest

from typedpython import cbuild, cgen, frontend, ir, verify

SOURCE = textwrap.dedent('''
    # typedpython: compiled
    import array


    def sum_ad(a: array.array[float]) -> float:
        s = 0.0
        for i in range(len(a)):
            s += a[i]
        return s


    def sum_aq(a: array.array[int]) -> int:
        s = 0
        for i in range(len(a)):
            s += a[i]
        return s


    def get_ad(a: array.array[float], i: int) -> float:
        return a[i]


    def get_aq(a: array.array[int], i: int) -> int:
        return a[i]


    def set_ad(a: array.array[float], i: int, v: float) -> None:
        a[i] = v


    def set_aq(a: array.array[int], i: int, v: int) -> None:
        a[i] = v


    def length_ad(a: array.array[float]) -> int:
        return len(a)


    def scale_ad(a: array.array[float], k: float) -> None:
        for i in range(len(a)):
            a[i] = a[i] * k


    def iota_aq(a: array.array[int]) -> None:
        for i in range(len(a)):
            a[i] = i


    def copy_ad(dst: array.array[float], src: array.array[float]) -> None:
        for i in range(len(dst)):
            dst[i] = src[i]


    def cascade_ad(a: array.array[float], b: array.array[float]) -> None:
        for i in range(len(a)):
            a[i] = b[i] + 1.0


    def fill_n(a: array.array[float], n: int) -> None:
        for i in range(n):
            a[i] = 1.0


    def mixed(l: list[float], a: array.array[float]) -> None:
        for i in range(len(a)):
            a[i] = l[i]


    def four(a: array.array[float], b: array.array[float], c: array.array[float],
             d: array.array[float]) -> None:
        for i in range(len(a)):
            a[i] = b[i] + c[i] + d[i]


    def div_ad(a: array.array[float]) -> float:
        return a[0] / a[1]


    def open_ad(a: array.array[float], o: object) -> float:
        return a[0] + float(o.v)


    def rebind_view(a: array.array[float]) -> None:
        y = a[:]
        y[0] = 5.0


''')

COMPILED = {
    "sum_ad", "sum_aq", "get_ad", "get_aq", "set_ad", "set_aq", "length_ad", "scale_ad", "iota_aq",
    "copy_ad", "cascade_ad", "fill_n", "mixed", "four", "div_ad",
}
SKIPPED = {"open_ad", "rebind_view"}


def build_source(d, source, name="bufmod"):
    path = d / f"{name}.py"
    path.write_text(source)
    proved, diags = verify.verify(frontend.lower(path))
    return path, proved, diags, cgen.generate(proved, path, path.name)


def load_so(so):
    return cbuild.load(so.stem.split(".")[0], so)


@dataclasses.dataclass
class Built:
    proved: object
    diags: list
    c: str
    module: object


@pytest.fixture(scope="module")
def built(tmp_path_factory):
    d = tmp_path_factory.mktemp("bufmod")
    path, proved, diags, c = build_source(d, SOURCE)
    so = cbuild.build(c, path.stem, d / "out")
    return Built(proved, diags, c, load_so(so))


def assert_compiled(built, *names):
    have = {f.name for f in built.proved.functions}
    assert set(names) <= have, (sorted(set(names) - have), built.proved.skipped)


def deopts(m):
    return m.__typedpython_deopts__


def interp(m, name):
    return m.__typedpython_interpreted__[name]


def outcome(fn, args):
    try:
        return ("ok", fn(*args))
    except Exception as e:  # noqa: BLE001 - the type and text are the observable
        return ("raise", type(e), str(e))


def same_value(a, b):
    return a == b or (a != a and b != b)       # nan


def differential(m, name, make, expect_deopt=False):
    """Run the compiled and the interpreted function on two fresh copies of the arguments; assert
    equal result (or equal exception type and text) and equal final contents of every argument."""
    ca, ia = make(), make()
    before = deopts(m)
    c = outcome(getattr(m, name), ca)
    after = deopts(m)
    i = outcome(interp(m, name), ia)
    assert c[0] == i[0], (c, i)
    if c[0] == "ok":
        assert same_value(c[1], i[1]), (c, i)
    else:
        assert c[1:] == i[1:], (c, i)
    for x, y in zip(ca, ia):
        if isinstance(x, (array.array, list)):
            assert len(x) == len(y) and all(same_value(p, q) for p, q in zip(x, y)), (x, y)
    if expect_deopt:
        assert after == before + 1, "expected a deopt (an object-path call), got none"
    else:
        assert after == before, "unexpected deopt: the compiled path did not run"
    return c


F = [1.5, -2.25, 3.0, 4.5]
I = [3, -5, 7, 11]


def AD(v=F): return array.array("d", v)
def AQ(v=I): return array.array("q", v)


# --- what is compiled, and why the rest is not --------------------------------------------------

def test_compiled_set_and_skip_reasons(built):
    names = {f.name for f in built.proved.functions}
    assert names >= COMPILED, sorted(COMPILED - names)
    for n in SKIPPED:
        assert n in built.proved.skipped, n
    assert "verify/array-open" in built.proved.skipped["open_ad"]
    assert "never copied" in built.proved.skipped["rebind_view"]


def test_ir_shape_marks_buffer_params(built):
    by = {f.name: f for f in built.proved.functions}
    assert (by["sum_ad"].params[0].buffer, by["sum_ad"].params[0].stored) == ("array", False)
    assert by["scale_ad"].params[0].stored is True
    assert [q.buffer for q in by["mixed"].params] == [None, "array"]


def test_no_limit_on_buffer_parameters(built):
    by = {f.name: f for f in built.proved.functions}
    assert sum(p.buffer is not None for p in by["four"].params) == 4
    assert "tp_cs_begin" not in wrapper_text(built.c, "four")
    n = 4
    a, b, c, d = (array.array("d", [float(k)] * n) for k in range(4))
    built.module.four(a, b, c, d)
    assert a.tolist() == [6.0] * n


def wrapper_text(c, name):
    start = c.index(f"static PyObject *tp_wrap_{name}(")
    return c[start:c.index("\n}\n", start)]


# --- reads, writes, len ---------------------------------------------------------------------------

@pytest.mark.parametrize("name,make", [
    ("sum_ad", lambda: (AD(),)), ("sum_aq", lambda: (AQ(),)),
    ("sum_ad", lambda: (AD([]),)), ("sum_aq", lambda: (array.array("l", I),)),
    ("length_ad", lambda: (AD(),)), ("length_ad", lambda: (AD([]),)),
])
def test_reads_and_len(built, name, make):
    differential(built.module, name, make)


@pytest.mark.parametrize("name,make", [
    ("get_ad", lambda: (AD(), 2)), ("get_ad", lambda: (AD(), -1)), ("get_ad", lambda: (AD(), -4)),
    ("get_aq", lambda: (AQ(), 3)), ("get_aq", lambda: (AQ(), -4)),
])
def test_index_and_negative_index(built, name, make):
    differential(built.module, name, make)


@pytest.mark.parametrize("name,make", [
    ("get_ad", lambda: (AD(), 4)), ("get_ad", lambda: (AD(), -5)), ("get_ad", lambda: (AD([]), 0)),
    ("get_aq", lambda: (AQ(), 99)),
    ("set_ad", lambda: (AD(), 4, 1.0)), ("set_aq", lambda: (AQ(), -5, 1)),
])
def test_index_errors_match_cpython_type_and_text(built, name, make):
    c = differential(built.module, name, make)
    assert c[0] == "raise" and c[1] is IndexError


@pytest.mark.parametrize("name,make", [
    ("set_ad", lambda: (AD(), 1, 9.5)), ("set_ad", lambda: (AD(), -1, 9.5)),
    ("set_aq", lambda: (AQ(), 0, -77)), ("set_aq", lambda: (AQ(), 2, 2 ** 40)),
    ("scale_ad", lambda: (AD(), 2.0)), ("scale_ad", lambda: (AD([]), 2.0)),
    ("iota_aq", lambda: (AQ(),)),
    ("copy_ad", lambda: (AD(), AD([9.0, 8.0, 7.0, 6.0]))),
])
def test_writes_are_visible_to_the_caller(built, name, make):
    differential(built.module, name, make)


def test_a_write_really_lands_in_the_callers_memory(built):
    assert_compiled(built, "set_ad", "scale_ad")
    a = AD()
    built.module.set_ad(a, 1, 42.0)
    assert a[1] == 42.0
    built.module.scale_ad(a, 2.0)
    assert a.tolist() == [3.0, 84.0, 6.0, 9.0]


def test_exception_mid_loop_keeps_earlier_writes(built):
    differential(built.module, "fill_n", lambda: (AD([0.0] * 3), 4))
    differential(built.module, "fill_n", lambda: (AD([0.0] * 5), 4))
    a = AD([0.0] * 3)
    with pytest.raises(IndexError):
        built.module.fill_n(a, 4)
    assert a.tolist() == [1.0, 1.0, 1.0]


def test_array_array_elements_are_python_floats_division_raises(built):
    differential(built.module, "div_ad", lambda: (AD([1.0, 0.0]),))
    differential(built.module, "div_ad", lambda: (AD([1.0, 4.0]),))


# --- aliasing -------------------------------------------------------------------------------------

def test_the_same_array_passed_twice(built):
    assert_compiled(built, "copy_ad", "cascade_ad")
    a = AD()
    before = deopts(built.module)
    built.module.copy_ad(a, a)
    assert deopts(built.module) == before
    assert a.tolist() == F
    # the same array as both operands: each read sees the program-order state
    a = AD([0.0, 0.0, 0.0])
    differential(built.module, "cascade_ad", lambda: (lambda x: (x, x))(AD([1.0, 2.0, 3.0])))
    built.module.cascade_ad(a, a)
    assert a.tolist() == [1.0, 1.0, 1.0]
    a = AD([0.0, 0.0, 0.0])
    interp(built.module, "cascade_ad")(a, a)
    assert a.tolist() == [1.0, 1.0, 1.0]


def test_buffer_and_list_parameters_together(built):
    l = [10.0, 20.0, 30.0, 40.0]
    a = AD([0.0] * 4)
    before = deopts(built.module)
    built.module.mixed(l, a)
    assert deopts(built.module) == before
    assert a.tolist() == l and l == [10.0, 20.0, 30.0, 40.0]
    # an array in place of the list is a deopt (guard), not an alias
    differential(built.module, "mixed", lambda: (AD([1.0] * 4), AD([0.0] * 4)), expect_deopt=True)


# --- guards: anything else takes the object path --------------------------------------------------

class SubArray(array.array):
    pass


@pytest.mark.parametrize("name,make", [
    ("sum_aq", lambda: (AD(),)),
    ("sum_aq", lambda: (array.array("Q", [1, 2]),)),
    ("sum_aq", lambda: (array.array("i", [1, 2]),)),
    ("sum_ad", lambda: (array.array("f", [1.0, 2.0]),)),
    ("sum_ad", lambda: (SubArray("d", F),)),
    ("sum_ad", lambda: (list(F),)),
    ("sum_ad", lambda: (memoryview(AD()),)),
    ("sum_ad", lambda: (None,)),
])
def test_wrong_kind_takes_the_object_path_and_is_still_correct(built, name, make):
    differential(built.module, name, make, expect_deopt=True)


def test_wrong_arity_and_keywords_still_reach_cpython(built):
    assert_compiled(built, "sum_ad")
    with pytest.raises(TypeError):
        built.module.sum_ad()
    with pytest.raises(TypeError):
        built.module.sum_ad(AD(), AD())
    assert built.module.sum_ad(a=AD()) == sum(F)


# --- the export is released on every exit ----------------------------------------------------------

def can_resize(a):
    try:
        a.append(0.0)
    except BufferError:
        return False
    return True


def test_export_released_after_ok_error_and_deopt(built):
    m = built.module
    a = AD()
    m.sum_ad(a)
    assert can_resize(a)                                 # ok exit
    a = AD()
    with pytest.raises(IndexError):
        m.get_ad(a, 99)
    assert can_resize(a)                                 # error exit
    a = AD()
    with pytest.raises(IndexError):
        m.set_ad(a, 99, 1.0)
    assert can_resize(a)
    q = array.array("q", [2 ** 62, 2 ** 62, 2 ** 62])
    before = deopts(m)
    assert m.sum_aq(q) == 3 * 2 ** 62                    # overflow of i64: deopt after entry
    assert deopts(m) == before + 1
    q.append(1)                                          # released before the interpreted redo
    d = array.array("d", [1.0])
    before = deopts(m)
    m.sum_aq(d)                                          # export taken, then the format check fails
    assert deopts(m) == before + 1
    assert can_resize(d)
    s = SubArray("d", F)
    m.sum_ad(s)                                          # type guard fails before any export
    assert can_resize(s)
    dst = AD()
    with pytest.raises(IndexError):
        m.copy_ad(dst, AD([1.0]))                        # the second export is held at the error
    assert can_resize(dst)
    dst, src = AD(), AD()
    m.copy_ad(dst, src)
    assert can_resize(dst) and can_resize(src)


def exports_leak_after_error(out, c):
    mod = load_so(cbuild.build(c, "bufmod", out))
    a = AD()
    with pytest.raises(IndexError):
        mod.get_ad(a, 99)
    return not can_resize(a)


def test_mutation_dropping_release_on_the_error_path_is_caught(built, tmp_path):
    # a build whose error path does not call tp_buf_exit leaks the export; the same observation
    # as the release test shows it, and the unmutated build does not leak
    start = built.c.index("tp_error:", built.c.index("static PyObject *tp_wrap_get_ad("))
    end = built.c.index("tp_deopt:", start)
    mutated = built.c[:start] + built.c[start:end].replace("tp_buf_exit(", "tp_buf_leak(") \
        + built.c[end:]
    assert mutated != built.c
    mutated = mutated.replace(
        "static const char tp_source[]",
        "static inline void tp_buf_leak(tp_buf *b) { (void)b; }\nstatic const char tp_source[]", 1)
    assert exports_leak_after_error(tmp_path / "mutated", mutated)
    assert not exports_leak_after_error(tmp_path / "clean", built.c)


def test_plain_switch_builds_and_agrees(built, tmp_path):
    # -DTP_BUFFER_PLAIN: plain loads and stores instead of relaxed atomics (measurement only)
    assert "TP_BUFFER_PLAIN" in (cbuild.RUNTIME_DIR / "tp_runtime.h").read_text()
    so = cbuild.build(built.c, "bufmod", tmp_path / "plain",
                      flags=list(cbuild.DEFAULT_FLAGS) + ["-DTP_BUFFER_PLAIN"])
    m = load_so(so)
    a = AD()
    m.scale_ad(a, 2.0)
    assert a.tolist() == [v * 2 for v in F]
    assert m.sum_aq(AQ()) == sum(I)


def test_atomic_access_is_the_default(built):
    text = (cbuild.RUNTIME_DIR / "tp_runtime.h").read_text()
    assert "#ifdef TP_BUFFER_PLAIN" in text
    plain, _, atomic = text.partition("#ifdef TP_BUFFER_PLAIN")[2].partition("#else")
    assert "__atomic_load_n" in atomic.split("#endif")[0] and "__atomic" not in plain
    # the generated code reaches the elements only through the helpers
    start = built.c.index("static int tp_impl_scale_ad(", built.c.index("static const char tp_source"))
    while not built.c[built.c.index(")", start) + 1:].startswith("\n{"):
        start = built.c.index("static int tp_impl_scale_ad(", start + 1)
    body = built.c[start:built.c.index("\n}\n", start)]
    assert "tp_buf_ld_f64(" in body and "tp_buf_st_f64(" in body and "->data[" not in body


# --- closedness (#203) ----------------------------------------------------------------------------

def test_function_that_would_run_user_code_stays_interpreted(built):
    assert_compiled(built, "sum_ad")

    class O:
        v = 1.0
    assert built.module.open_ad(AD([2.0]), O()) == 3.0
    assert "open_ad" not in {f.name for f in built.proved.functions}


# --- verifier rules, on hand-built IR (the verifier never trusts the front end) -------------------

def test_verifier_buffer_kind_and_copy_rules():
    from test_verify import F64 as VF64, F64A as VF64A, assert_rejected, fn
    from typedpython.ir import ArrayParam, Assign, Const, CopyArray, Index, Return, Type

    for kind in ("ndarray", "memoryview"):        # "numpy" is a buffer kind since #220
        assert_rejected("verify/buffer", fn("f", params=[ArrayParam("a", VF64A, False, kind)],
                                             returns=VF64, pure=True,
                                             body=[Return(Index(VF64, "a", Const(Type.I64, 0)))]))
    copy = fn("f", params=[ArrayParam("a", VF64A, False, "array")], returns=VF64, pure=True,
              locals_={"b": VF64A},
              body=[Assign("b", CopyArray(VF64A, "a")), Return(Index(VF64, "b", Const(Type.I64, 0)))])
    assert_rejected("verify/buffer", copy)
    import dataclasses
    assert_rejected("verify/buffer", dataclasses.replace(
        copy, params=[ArrayParam("a", VF64A, False, "numpy")]))


def test_verifier_buffer_params_are_not_limited_but_lists_still_are():
    from test_verify import F64 as VF64, F64A as VF64A, assert_accepted, assert_rejected, fn
    from typedpython.ir import ArrayParam, Const, Index, Return, Type

    def many(n, buffer):
        return fn("f", params=[ArrayParam(f"a{k}", VF64A, False, buffer) for k in range(n)],
                  returns=VF64, pure=True,
                  body=[Return(Index(VF64, "a0", Const(Type.I64, 0)))])

    assert_accepted(many(5, "array"))
    assert_rejected("verify/structure", many(3, None))


# --- vectorised element-wise store loops (#226) ---------------------------------------------------

VEC_SOURCE = textwrap.dedent('''
    # typedpython: compiled
    import array


    def vscale(a: array.array[float], k: float) -> None:
        for i in range(len(a)):
            a[i] = a[i] * k


    def vmix(a: array.array[float], k: float) -> None:
        for i in range(len(a)):
            a[i] = -(a[i] - k) * 2.5 + k


    def vtwo(a: array.array[float], b: array.array[float]) -> None:
        for i in range(len(a)):
            a[i] = a[i] + b[i]


    def vfill(a: array.array[float], k: float) -> None:
        for i in range(len(a)):
            a[i] = k


    def vdiv(a: array.array[float], k: float) -> None:
        for i in range(len(a)):
            a[i] = a[i] / k
''')


@pytest.fixture(scope="module")
def vec_built(tmp_path_factory):
    d = tmp_path_factory.mktemp("vecmod")
    path, proved, diags, c = build_source(d, VEC_SOURCE, "vecmod")
    return Built(proved, diags, c, load_so(cbuild.build(c, path.stem, d / "out"))), d


def _body(c, name):
    start = c.index(f"static int tp_impl_{name}(", c.index("static const char tp_source"))
    while not c[c.index(")", start) + 1:].startswith("\n{"):
        start = c.index(f"static int tp_impl_{name}(", start + 1)
    return c[start:c.index("\n}\n", start)]


def test_vector_helper_only_where_the_architecture_guarantees_it():
    h = (cbuild.RUNTIME_DIR / "tp_runtime.h").read_text()
    guard = h[h.index("#define TP_BUF_VEC2") - 120:h.index("#define TP_BUF_VEC2")]
    assert "__aarch64__" in guard and "x86" not in guard and "__SSE" not in guard
    ld = h[h.index("tp_buf_ld2_f64("):h.index("tp_buf_st2_f64(")]
    st = h[h.index("tp_buf_st2_f64("):h.index("#endif", h.index("tp_buf_st2_f64("))]
    assert '"ldr %q0, %1"' in ld and '"str %q1, %0"' in st and '"Q"' in ld and '"=Q"' in st
    assert '"memory"' not in ld + st
    assert "vld1q" not in ld + st and "vst1q" not in ld + st      # no plain C access the compiler may split
    plain = h.index("#ifdef TP_BUFFER_PLAIN")
    assert h.index("#else", plain) < h.index("#define TP_BUF_VEC2")     # absent from the plain build


def test_vectorised_loops_are_emitted_for_the_pattern_only(vec_built):
    b, _ = vec_built
    for n in ("vscale", "vmix", "vfill"):
        body = _body(b.c, n)
        assert "#ifdef TP_BUF_VEC2" in body and "tp_buf_st2_f64(" in body, n
        assert "tp_buf_st_f64(" in body, n          # the scalar tail stays
    for n in ("vdiv", "vtwo"):      # division can raise; b[i] is not proven in range: scalar only
        assert "tp_buf_st2_f64" not in _body(b.c, n), n


@pytest.mark.parametrize("n", [0, 1, 2, 3, 7, 1000, 4097])
def test_vectorised_results_equal_the_interpreter(vec_built, n):
    m = vec_built[0].module
    mk = lambda: array.array("d", [i * 0.37 - 5 for i in range(n)])  # noqa: E731
    differential(m, "vscale", lambda: (mk(), 1.7))
    differential(m, "vmix", lambda: (mk(), 0.5))
    differential(m, "vfill", lambda: (mk(), 3.25))
    differential(m, "vdiv", lambda: (mk(), 4.0))
    differential(m, "vtwo", lambda: (mk(), array.array("d", [i * 0.11 for i in range(n)])))


def test_fallback_build_without_the_vector_helper_agrees(vec_built, tmp_path):
    b, _ = vec_built
    so = cbuild.build(b.c, "vecmod", tmp_path / "novec",
                      flags=list(cbuild.DEFAULT_FLAGS) + ["-DTP_NO_BUF_VEC"])
    m = load_so(so)
    a = array.array("d", [i * 1.5 for i in range(33)])
    m.vscale(a, 2.0)
    assert a.tolist() == [i * 3.0 for i in range(33)]


def test_concurrent_reader_sees_no_torn_element(vec_built):
    import struct
    import threading
    m = vec_built[0].module
    x = struct.unpack("<d", struct.pack("<Q", 0x1111111122222222))[0]
    y = struct.unpack("<d", struct.pack("<Q", 0x3333333344444444))[0]
    n = 4096
    a = array.array("d", [x] * n)
    ok = {struct.pack("<d", x), struct.pack("<d", y)}
    stop = threading.Event()

    def writer():
        k = 0
        while not stop.is_set():
            m.vfill(a, y if k & 1 else x)
            k += 1

    t = threading.Thread(target=writer)
    t.start()
    bad = 0
    try:
        for _ in range(300):
            raw = a.tobytes()
            for off in range(0, len(raw), 8):
                if raw[off:off + 8] not in ok:
                    bad += 1
    finally:
        stop.set()
        t.join()
    assert bad == 0


def test_no_vec_build_does_not_use_the_helper(vec_built, tmp_path):
    import subprocess
    c = tmp_path / "v.c"
    c.write_text(vec_built[0].c)
    import sysconfig
    base = ["cc", "-E", "-P", "-I", str(cbuild.RUNTIME_DIR), "-I", sysconfig.get_paths()["include"]]
    def body(*flags):
        out = subprocess.run(base + list(flags) + [str(c)], capture_output=True, text=True, check=True).stdout
        return _body(out, "vscale")
    import platform
    if platform.machine() != "arm64" and platform.machine() != "aarch64":
        pytest.skip("the vector helper exists on aarch64 only")
    assert "tp_buf_st2_f64(" in body()
    assert "tp_buf_st2_f64(" not in body("-DTP_NO_BUF_VEC")
