"""Native ints in impure functions (SPEC N-13, #232).

An impure function cannot deopt after an effect. Its `int` locals are the IR type `INT`: a native
i64 that is promoted in place to a PyLong when an operation leaves the i64 range, with the results of
CPython. Everything here is a differential test against the interpreted function of the same module
(`__typedpython_interpreted__`), so a result, a raised exception, the side effects that happened before
it and the absence of a deopt must all be equal.

Red before the implementation: the IR has no `INT` type (the structural tests fail with
AttributeError), the C has no per-name global cache and no module-state integer constants, and
`word_of` / `main` of the wordfreq benchmark are rejected by the cost rule.
"""
from __future__ import annotations

import builtins
import contextlib
import gc
import importlib.util
import io
import re
import shutil
import sys
import textwrap
import threading
import types
import weakref
from pathlib import Path

import pytest

from typedpython import cbuild, cgen, cost, frontend, ir, verify

REPO = Path(__file__).resolve().parents[4]
WORDFREQ = REPO / "benchmarks" / "typedpython" / "py" / "wordfreq.py"

SOURCE = textwrap.dedent('''
    # typedpython: compiled

    import math as GMOD

    LIMIT: int = 10
    MOD: int = 2147483647


    def _one() -> int:
        return 1


    def _two() -> int:
        return 2


    class GTYPE:
        pass


    GFN = _one


    def add2(log: list[object], a: int, b: int) -> object:
        log.append("start")
        r: int = a + b
        log.append(r)
        r = r + b
        log.append(r)
        return r


    def sub2(log: list[object], a: int, b: int) -> object:
        log.append("start")
        r: int = a - b
        log.append(r)
        r = r - b
        log.append(r)
        return r


    def mul2(log: list[object], a: int, b: int) -> object:
        log.append("start")
        r: int = a * b
        log.append(r)
        r = r * b
        log.append(r)
        return r


    def neg2(log: list[object], a: int) -> object:
        log.append("start")
        r: int = -a
        log.append(r)
        r = -r
        log.append(r)
        return r


    def floordiv2(log: list[object], a: int, b: int) -> object:
        log.append("start")
        r: int = a + 1
        log.append(r)
        r = r // b
        log.append(r)
        r = a // b
        log.append(r)
        return r


    def mod2(log: list[object], a: int, b: int) -> object:
        log.append("start")
        r: int = a - 1
        log.append(r)
        r = r % b
        log.append(r)
        r = a % b
        log.append(r)
        return r


    def lsh(log: list[object], a: int, b: int) -> object:
        log.append("start")
        r: int = a << b
        log.append(r)
        return r


    def rsh(log: list[object], a: int, b: int) -> object:
        log.append("start")
        r: int = a + 1
        log.append(r)
        r = r >> b
        log.append(r)
        return r


    def roundtrip(log: list[object], a: int) -> object:
        log.append(a)
        x: int = a
        x = x + 10
        log.append(x)
        x = x - 20
        log.append(x)
        x = x * 2
        log.append(x)
        x = x // 2
        log.append(x)
        return x


    def compare(log: list[object], a: int, b: int) -> None:
        log.append("start")
        x: int = a + 1
        y: int = b - 1
        if x < y:
            log.append("lt")
        if x <= y:
            log.append("le")
        if x > y:
            log.append("gt")
        if x >= y:
            log.append("ge")
        if x == y:
            log.append("eq")
        if x != y:
            log.append("ne")
        if x:
            log.append("truthy")
        if not y:
            log.append("y-zero")


    def fact(log: list[object], n: int) -> object:
        log.append(n)
        f: int = 1
        for i in range(n):
            f = f * (i + 1)
        log.append(f)
        return f


    def grow(log: list[object], a: int, k: int) -> object:
        x: int = a
        i: int = 0
        while i < k:
            x = x * 3 + 1
            i += 1
            log.append(x)
        return x


    def consts(log: list[object]) -> None:
        log.append(7000000000)
        log.append(5)
        log.append(-7000000000)


    def next_state(s: int) -> int:
        return (s * 48271) % MOD


    def drive(log: list[object], seed: int, n: int) -> object:
        s: int = seed + 1
        for _ in range(n):
            s = next_state(s)
            log.append(s)
        return s


    def read_limit(log: list[object]) -> object:
        log.append(1)
        return LIMIT


    def read_twice(log: list[object], cb: object) -> None:
        log.append(LIMIT)
        cb()
        log.append(LIMIT)


    def use_builtin(log: list[object], s: str) -> object:
        log.append(1)
        return len(s)


    def read_fn(log: list[object]) -> object:
        log.append(1)
        return GFN


    def read_type(log: list[object]) -> object:
        log.append(1)
        return GTYPE


    def read_mod(log: list[object]) -> object:
        log.append(1)
        return GMOD


    def call_g(x: object) -> object:
        return GFN()
''')

COMPILED = {"add2", "sub2", "mul2", "neg2", "floordiv2", "mod2", "lsh", "rsh", "roundtrip",
            "compare", "fact", "grow", "consts", "next_state", "drive", "read_limit", "read_twice",
            "use_builtin", "read_fn", "read_type", "read_mod", "call_g"}

B = [0, 1, -1, 2, -2, 3, 7, -7, 2**31, 2**32, -2**31, 2**62 - 1, 2**62, 2**62 + 1, -2**62,
     2**63 - 1, 2**63 - 2, -2**63, -2**63 + 1, 3037000499, 3037000500, -3037000500]
COUNTS = [0, 1, 2, 31, 62, 63, 64, 65, 100, -1, -64]


def build_source(d, source, name="nimod", runtime_dir=None):
    path = d / f"{name}.py"
    path.write_text(source)
    proved, diags = verify.verify(frontend.lower(path))
    c = cgen.generate(proved, path, path.name)
    so = cbuild.build(c, path.stem, d / "out", runtime_dir=runtime_dir)
    return proved, diags, c, cbuild.load(so.stem.split(".")[0], so)


@pytest.fixture(scope="module")
def built(tmp_path_factory):
    d = tmp_path_factory.mktemp("nimod")
    proved, diags, c, m = build_source(d, SOURCE)
    return proved, diags, c, m


def deopts(m):
    return m.__typedpython_deopts__


def interp(m, name):
    return m.__typedpython_interpreted__[name]


def outcome(fn, args):
    log: list = []
    try:
        res = ("ok", fn(log, *args))
    except BaseException as e:  # noqa: BLE001 - the type and the message are observable
        res = ("raise", type(e).__name__, str(e))
    return res, [(type(x).__name__, x) for x in log]


def differential(m, name, args):
    before = deopts(m)
    c = outcome(getattr(m, name), args)
    after = deopts(m)
    i = outcome(interp(m, name), args)
    assert c == i, (name, args, c, i)
    assert after == before, f"{name}{tuple(args)} deopted: the compiled path did not run"
    return c


def fn_of(built, name):
    return next(f for f in built[0].functions if f.name == name)


# --- red/green structure ---------------------------------------------------------------------

def test_the_module_compiles_every_function(built):
    names = {f.name for f in built[0].functions}
    assert COMPILED <= names, built[0].skipped


def test_int_locals_of_impure_functions_are_native(built):
    """i64 locals in impure functions: the local is the IR type INT, not an object."""
    for name, local in (("add2", "r"), ("fact", "f"), ("grow", "x"), ("grow", "i"),
                        ("roundtrip", "x"), ("compare", "x")):
        f = fn_of(built, name)
        assert f.pure is False
        assert f.locals[local] is ir.Type.INT, (name, local, f.locals)
    assert not built[1], built[1]            # the verifier proved all of it


def test_an_int_fed_from_an_object_stays_an_object(tmp_path):
    src = textwrap.dedent('''
        # typedpython: compiled
        def f(log: list[object], g: object) -> object:
            log.append(1)
            n: int = g()
            m: int = 3
            m = m + 1
            return n
    ''')
    proved, _, _, _ = build_source(tmp_path, src)
    f = next(f for f in proved.functions if f.name == "f")
    assert f.locals["n"] is ir.Type.OBJ
    assert f.locals["m"] is ir.Type.INT


# --- the i64 boundary --------------------------------------------------------------------------

@pytest.mark.parametrize("name", ["add2", "sub2", "mul2", "floordiv2", "mod2"])
def test_binary_ops_at_the_i64_boundary_equal_cpython(built, name):
    m = built[3]
    for a in B:
        for b in B:
            differential(m, name, [a, b])


def test_neg_at_the_i64_boundary(built):
    m = built[3]
    for a in B:
        differential(m, "neg2", [a])
    # the one native negation that overflows
    (res, log) = differential(m, "neg2", [-2**63])
    assert res == ("ok", -2**63) and log[1:] == [("int", 2**63), ("int", -2**63)]


def test_shifts_at_the_i64_boundary(built):
    m = built[3]
    for a in B:
        for n in COUNTS:
            differential(m, "lsh", [a, n])
            differential(m, "rsh", [a, n])
    assert differential(m, "lsh", [1, -1])[0] == ("raise", "ValueError", "negative shift count")
    assert differential(m, "lsh", [1, 63])[0] == ("ok", 2**63)


def test_division_by_zero_after_a_side_effect(built):
    m = built[3]
    for a in (0, 5, 2**63 - 1, -2**63):
        (res, log) = differential(m, "floordiv2", [a, 0])
        assert res[0] == "raise" and res[1] == "ZeroDivisionError" and log, (a, res, log)
        (res, log) = differential(m, "mod2", [a, 0])
        assert res[0] == "raise" and res[1] == "ZeroDivisionError" and log


def test_min_floordiv_minus_one_promotes(built):
    m = built[3]
    # -2**63 // -1 == 2**63 does not fit i64; the result is exactly the interpreter's
    (res, log) = differential(m, "floordiv2", [-2**63, -1])
    assert res == ("ok", 2**63)


def test_promotion_survives_further_arithmetic_and_comes_back(built):
    m = built[3]
    for a in B:
        differential(m, "roundtrip", [a])
    assert differential(m, "roundtrip", [2**63 - 1])[1][1] == ("int", 2**63 + 9)    # promoted


def test_a_promoted_value_is_carried_through_a_loop(built):
    m = built[3]
    for n in (0, 1, 2, 20, 21, 25, 60):
        differential(m, "fact", [n])
    assert differential(m, "fact", [25])[0] == ("ok", 15511210043330985984000000)
    for a in (0, 1, -1, 2**62, 2**63 - 1, -2**63):
        for k in (0, 1, 5, 45):
            differential(m, "grow", [a, k])


def test_comparisons_of_native_and_promoted_ints(built):
    m = built[3]
    for a in B:
        for b in B:
            differential(m, "compare", [a, b])


def test_a_param_outside_i64_still_deopts_at_entry_before_any_effect(built):
    m = built[3]
    before = deopts(m)
    log: list = []
    assert m.add2(log, 2**63, 1) == 2**63 + 2
    assert deopts(m) == before + 1
    assert log == ["start", 2**63 + 1, 2**63 + 2]


def test_redo_call_with_native_and_promoted_arguments(built):
    m = built[3]
    for seed in (0, 1, 12345, 2**40, 2**62, 2**63 - 2, 2**63 - 1, -2**63):
        for n in (0, 1, 3, 40):
            ca, ia = outcome(m.drive, [seed, n]), outcome(interp(m, "drive"), [seed, n])
            assert ca == ia, (seed, n, ca, ia)


# --- no leak, no stale reference -----------------------------------------------------------------

def test_promoted_values_do_not_leak(built):
    m = built[3]

    def churn():
        for _ in range(300):
            log: list = []
            m.fact(log, 30)
            m.grow(log, 2**62, 5)
            m.add2(log, 2**63 - 1, 1)
            with contextlib.suppress(ZeroDivisionError):
                m.floordiv2(log, 2**63 - 1, 0)

    churn()
    gc.collect()
    before = sys.getallocatedblocks()
    churn()
    gc.collect()
    assert sys.getallocatedblocks() - before < 200


# --- constants are built once --------------------------------------------------------------------

def test_constants_are_boxed_once(built):
    m = built[3]
    a, b = [], []
    m.consts(a)
    m.consts(b)
    assert a == b == [7000000000, 5, -7000000000]
    assert a[0] is b[0] and a[2] is b[2], "a constant is a fresh PyLong per use, not pre-boxed"


def test_generated_c_boxes_no_literal(built):
    c = built[2]
    body = re.sub(r"/\*.*?\*/", "", c, flags=re.S)
    assert not re.search(r"tp_box_i64\(\s*(?:INT64_C\()?-?\(?\d", body), \
        "a constant is boxed per use instead of read from module state"


# --- globals and builtins are cached behind the dict-watcher epoch ----------------------------

def test_global_reads_are_cached(built):
    c = built[2]
    assert "tp_gcache" in c


def test_a_rebound_global_is_seen_by_the_cached_read(built):
    m = built[3]
    log: list = []
    assert m.read_limit(log) == 10
    try:
        for v in (11, "x", None, 2**70, 10):
            m.LIMIT = v
            assert differential(m, "read_limit", [])[0] == ("ok", v)
        m.__dict__["LIMIT"] = 12
        assert differential(m, "read_limit", [])[0] == ("ok", 12)
        m.__dict__.update(LIMIT=13)
        assert differential(m, "read_limit", [])[0] == ("ok", 13)
        m.__dict__.setdefault("LIMIT", 99)
        assert differential(m, "read_limit", [])[0] == ("ok", 13)
        del m.LIMIT
        res, log = differential(m, "read_limit", [])
        assert res[:2] == ("raise", "NameError") and "LIMIT" in res[2]
        m.LIMIT = 10
        assert differential(m, "read_limit", [])[0] == ("ok", 10)
    finally:
        m.LIMIT = 10


def test_a_global_rebound_in_the_middle_of_a_call(built):
    m = built[3]

    def bump():
        m.LIMIT = m.LIMIT + 1

    try:
        c = outcome(m.read_twice, [bump])
        m.LIMIT = 10
        i = outcome(interp(m, "read_twice"), [bump])
        assert c == i and c[1] == [("int", 10), ("int", 11)], (c, i)
    finally:
        m.LIMIT = 10


def test_a_patched_builtin_is_seen_by_the_cached_read(built):
    m = built[3]
    assert differential(m, "use_builtin", ["abc"])[0] == ("ok", 3)
    real = builtins.len
    try:
        builtins.len = lambda s: 42
        assert differential(m, "use_builtin", ["abc"])[0] == ("ok", 42)
        m.len = lambda s: 7                      # a module global shadows the builtin
        assert differential(m, "use_builtin", ["abc"])[0] == ("ok", 7)
        del m.len
        assert differential(m, "use_builtin", ["abc"])[0] == ("ok", 42)
    finally:
        builtins.len = real
        m.__dict__.pop("len", None)
    assert differential(m, "use_builtin", ["abc"])[0] == ("ok", 3)


# --- the cache extends no lifetime ------------------------------------------------------------

def _plain_function():
    def f():
        return 1
    return f


def _plain_type():
    return type("T", (), {})


def _plain_module():
    return types.ModuleType("gm")


@pytest.mark.parametrize("name,reader,make", [("GFN", "read_fn", _plain_function),
                                              ("GTYPE", "read_type", _plain_type),
                                              ("GMOD", "read_mod", _plain_module)])
def test_a_rebound_cached_global_is_freed_as_in_the_interpreter(built, name, reader, make):
    m = built[3]
    saved = getattr(m, name)
    try:
        for impl in (getattr(m, reader), interp(m, reader)):
            old = make()
            setattr(m, name, old)
            for _ in range(5):                       # the compiled read caches it
                assert impl([]) is old
            ref = weakref.ref(old)
            del old
            setattr(m, name, None)
            gc.collect()
            assert ref() is None, f"{reader}: the old {name} is kept alive"
    finally:
        setattr(m, name, saved)


def test_a_finalizer_in_a_rebound_functions_dict_runs_at_the_same_point(built):
    m = built[3]
    saved = m.GFN
    try:
        traces = []
        for impl in (m.read_fn, interp(m, "read_fn")):
            events: list = []

            class D:
                def __del__(self):
                    events.append("del")

            f = _plain_function()
            f.payload = D()
            m.GFN = f
            for _ in range(5):
                impl([])
            del f
            events.append("before")
            m.GFN = None
            events.append("after")
            traces.append(events)
        assert traces[0] == traces[1] == ["before", "del", "after"], traces
    finally:
        m.GFN = saved


def test_a_global_rebound_by_one_thread_while_others_call_through_it(built):
    m = built[3]
    saved = m.GFN
    stop = threading.Event()
    bad: list = []

    def rebinder():
        fns = [m._one, m._two]
        i = 0
        while not stop.is_set():
            m.GFN = fns[i & 1]
            i += 1

    def caller():
        for _ in range(20000):
            r = m.call_g(None)
            if r not in (1, 2):
                bad.append(r)

    try:
        t = threading.Thread(target=rebinder)
        cs = [threading.Thread(target=caller) for _ in range(4)]
        t.start()
        [c.start() for c in cs]
        [c.join() for c in cs]
        stop.set()
        t.join()
    finally:
        m.GFN = saved
    assert not bad, bad[:5]


# --- mutants: each fast path is shown to matter -------------------------------------------------

def mutant_runtime(tmp_path, old, new):
    mutant = tmp_path / "runtime"
    shutil.copytree(cbuild.RUNTIME_DIR, mutant)
    text = (mutant / "tp_runtime.h").read_text()
    assert old in text, old
    (mutant / "tp_runtime.h").write_text(text.replace(old, new))
    return mutant


def test_a_wrapping_add_is_caught(tmp_path):
    """Mutation: the native add does not check for overflow. The boundary test must fail."""
    rt = mutant_runtime(
        tmp_path, "#define TP_INT_ADD_OVF(a, b, r) __builtin_add_overflow((a), (b), (r))",
        "#define TP_INT_ADD_OVF(a, b, r) ((*(r) = (int64_t)((uint64_t)(a) + (uint64_t)(b))), 0)")
    (tmp_path / "bad").mkdir()
    _, _, _, bad = build_source(tmp_path / "bad", SOURCE, runtime_dir=rt)
    with pytest.raises(AssertionError):
        differential(bad, "add2", [2**63 - 1, 1])


def test_a_cache_that_is_never_invalidated_is_caught(tmp_path):
    """Mutation: the global cache ignores the epoch. A rebound global is then not seen."""
    rt = mutant_runtime(
        tmp_path, "#define TP_GCACHE_FRESH(slot_epoch, epoch) ((slot_epoch) == (epoch))",
        "#define TP_GCACHE_FRESH(slot_epoch, epoch) ((slot_epoch) != 0)")
    (tmp_path / "bad").mkdir()
    _, _, _, bad = build_source(tmp_path / "bad", SOURCE, runtime_dir=rt)
    log: list = []
    assert bad.read_limit(log) == 10
    bad.LIMIT = 11
    with pytest.raises(AssertionError):
        differential(bad, "read_limit", [])


# --- the cost rule and wordfreq --------------------------------------------------------------

def test_an_int_loop_is_native_work_for_the_cost_rule(tmp_path):
    src = textwrap.dedent('''
        # typedpython: compiled
        def digits(log: list[object], n: int) -> None:
            x: int = n + 1
            while x > 0:
                log.append(x % 10)
                x //= 10
    ''')
    path = tmp_path / "digits.py"
    path.write_text(src)
    proved, diags = verify.verify(frontend.lower(path))
    kept, diags = cost.apply(proved, diags)
    assert {f.name for f in kept.functions} == {"digits"}, kept.skipped


def test_an_object_only_loop_is_still_rejected(tmp_path):
    src = textwrap.dedent('''
        # typedpython: compiled
        def words(xs: list[object], ys: list[object]) -> None:
            for x in xs:
                ys.append(x)
    ''')
    path = tmp_path / "words.py"
    path.write_text(src)
    proved, diags = verify.verify(frontend.lower(path))
    kept, diags = cost.apply(proved, diags)
    assert {f.name for f in kept.functions} == set(), kept.skipped
    assert "policy/cost" in kept.skipped["words"]


@pytest.fixture(scope="module")
def wordfreq(tmp_path_factory):
    d = tmp_path_factory.mktemp("wordfreq")
    src = "# typedpython: compiled\n" + WORDFREQ.read_text()
    path = d / "wordfreq.py"
    path.write_text(src)
    proved, diags = verify.verify(frontend.lower(path))
    kept, diags = cost.apply(proved, diags)
    c = cgen.generate(kept, path, path.name)
    so = cbuild.build(c, path.stem, d / "out")
    m = cbuild.load(so.stem.split(".")[0], so)
    spec = importlib.util.spec_from_file_location("wordfreq_plain", WORDFREQ)
    plain = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(plain)
    return kept, diags, m, plain


def run_main(main, n):
    old = sys.argv
    sys.argv = ["wordfreq", str(n)]
    buf = io.StringIO()
    try:
        with contextlib.redirect_stdout(buf):
            main()
    finally:
        sys.argv = old
    return buf.getvalue()


def test_wordfreq_word_of_and_main_pass_the_cost_rule(wordfreq):
    kept, diags, _, _ = wordfreq
    names = {f.name for f in kept.functions}
    assert {"next_state", "word_of", "main"} <= names, kept.skipped
    assert not [d for d in diags if d.rule == "policy/cost"]


def test_wordfreq_output_equals_cpython(wordfreq):
    _, _, m, plain = wordfreq
    for n in (0, 1, 2, 50, 3000):
        assert run_main(m.main, n) == run_main(plain.main, n)
        assert run_main(m.main, n) == run_main(interp(m, "main"), n)
    assert deopts(m) == 0
    for i in (0, 1, 25, 26, 17575, 17576, 99999, 10**9):
        assert m.word_of(i) == plain.word_of(i)
