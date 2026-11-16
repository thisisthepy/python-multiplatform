"""The big-integer runtime of the TypedPython AOT mode (SPEC N-17, #460): `runtime/tp_aot_int.h`.

The header is CPython-free C. It is tested here the way test_aot_lib.py tests the library output: a test-only C
shim (runtime_shim/tp_aot_int_shim.c) is built ONCE as a shared library (`aot/cc.py compile_library`, so with the
library flags: atomic counts, and ASan and UBSan under TP_SANITIZE=1) and called through `ctypes`, and every
result is compared with CPython's own `int`: values, the representation class (small or big, the canonical form),
exception classes and messages. The shim takes and returns decimal strings, doubles, int64s and opaque handles, so
no test depends on the internal layout.

Inputs: a deterministic generator (fixed seed) of interesting integers (zero, plus and minus one, the edges of
the small range at 2**62, of int64 at 2**63, of one and two and many 32-bit limbs, powers of 2 and of 10 and
their neighbours, limb patterns of all ones and of the top bit, random values of random bit lengths, products),
a few thousand pairs of them, and adversarial inputs for the two hard functions (true division: halfway quotients,
subnormal and overflowing ones; division: dividends built to need the add-back step of Knuth's algorithm D).

Leaks: the shim is built with -DTPA_INT_COUNT_LIVE, and `shim_live()` is the number of big objects allocated and
not yet freed. The `shim` fixture checks it is 0 when each test ends. (The sanitizer job runs the interpreter
with leak detection off, so this counter is how a leak is observed there.)

Independence of the two directions of the decimal conversion: `to_str` is checked against the reference on values
that `from_str` never touched (built from int64s with `mul` and `add`), and `from_str` is checked against the
reference's `str` of the same values and then against the arithmetic-built value with `sub`/`eq`; the arithmetic
identities (a * b // b == a, a // b * b + a % b == a, (a + b) - b == a) are evaluated inside the library.

Needs a C compiler (skipped without one) and CPython 3.14 or later (the messages of 3.14 and 3.15; the
non-ASCII digit table of the header is Unicode 17.0, the data of 3.15). Everything is an agent proposal,
not owner-confirmed.
"""
from __future__ import annotations

import ctypes
import math
import random
import re
import shutil
import struct
import sys
import threading
import unicodedata
from pathlib import Path
from types import SimpleNamespace

import pytest

from typedpython import cbuild
from typedpython.aot import cc, library

HEADER = cc.RUNTIME_DIR / "tp_aot_int.h"
SHIM = Path(__file__).resolve().parent / "runtime_shim" / "tp_aot_int_shim.c"
AOT_DIR = Path(cc.__file__).resolve().parent
PINNED_UNICODE = "17.0.0"

SMALL_MIN = -(1 << 62)
SMALL_MAX = (1 << 62) - 1
NOT_HANDLED = 100                    # shim_from_str's status for TPA_INT_NOT_HANDLED
OPS = {0: "add", 1: "sub", 2: "mul", 3: "floordiv", 4: "mod"}


def need_cc():
    if sys.version_info < (3, 14):
        pytest.skip("the AOT runtime carries the exception messages of CPython 3.14 and later")
    if hasattr(sys, "get_int_max_str_digits") and sys.get_int_max_str_digits() != 4300:
        pytest.skip("the runtime reproduces the default limit of 4300 digits")
    for f in (HEADER, SHIM):
        if not f.is_file():
            pytest.skip(f"{f} does not exist")
    try:
        if cbuild._is_msvc():
            cbuild.msvc_env()
        elif shutil.which(cbuild.compiler()[0]) is None:
            pytest.skip("no C compiler")
    except cbuild.CBuildError as e:
        pytest.skip(str(e))


# --- the library through ctypes ---------------------------------------------------------------------------

class Shim:
    def __init__(self, path):
        self.dll = d = ctypes.CDLL(str(path))
        vp, cp, ci = ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int
        pv = ctypes.POINTER(vp)
        u = ctypes.c_size_t
        sig = {
            "shim_last_error": (ci, [ctypes.POINTER(cp), ctypes.POINTER(cp)]),
            "shim_free": (None, [vp]),
            "shim_live": (ctypes.c_int64, []),
            "shim_threads": (ci, []),
            "shim_h_parse": (ci, [cp, ctypes.POINTER(u)]),
            "shim_h_from_i64": (ci, [ctypes.c_int64, ctypes.POINTER(u)]),
            "shim_h_is_small": (ci, [u]),
            "shim_h_retain": (None, [u]),
            "shim_h_release": (None, [u]),
            "shim_h_str": (ci, [u, pv]),
            "shim_h_cmp": (ci, [u, u]),
            "shim_h_eq": (ci, [u, u]),
            "shim_h_binop": (ci, [ci, u, u, ctypes.POINTER(u)]),
            "shim_binop": (ci, [ci, cp, cp, pv]),
            "shim_divmod": (ci, [cp, cp, pv, pv]),
            "shim_unop": (ci, [ci, cp, pv]),
            "shim_to_double": (ci, [cp, ctypes.POINTER(ctypes.c_double)]),
            "shim_truediv": (ci, [cp, cp, ctypes.POINTER(ctypes.c_double)]),
            "shim_cmp_double": (ci, [cp, ctypes.c_double, ctypes.POINTER(ci)]),
            "shim_from_str": (ci, [cp, u, pv]),
            "shim_i64_roundtrip": (ci, [ctypes.c_int64, pv, ctypes.POINTER(ci), ctypes.POINTER(ctypes.c_int64)]),
            "shim_to_i64": (ci, [cp, ctypes.POINTER(ctypes.c_int64), ctypes.POINTER(ci)]),
        }
        for name, (res, args) in sig.items():
            f = getattr(d, name)
            f.restype = res
            f.argtypes = args

    # -- helpers
    def live(self):
        return self.dll.shim_live()

    def error(self):
        cls, msg = ctypes.c_char_p(), ctypes.c_char_p()
        if self.dll.shim_last_error(ctypes.byref(cls), ctypes.byref(msg)) == 1:
            return None
        return cls.value.decode("ascii"), msg.value.decode("ascii")

    def take(self, p):
        s = ctypes.string_at(p.value).decode("ascii")
        self.dll.shim_free(p)
        return s

    def result(self, rc, p):
        """("ok", string, small-flag) or ("err", class, message)."""
        if rc >= 0:
            return ("ok", self.take(p), rc)
        assert rc == -1, f"status {rc} (an operand was not parsed): {self.error()}"
        e = self.error()
        assert e is not None, "status -1 and no exception recorded"
        return ("err",) + e

    # -- operations on decimal strings
    def binop(self, op, a, b):
        p = ctypes.c_void_p()
        rc = self.dll.shim_binop(op, a.encode(), b.encode(), ctypes.byref(p))
        return self.result(rc, p)

    def divmod(self, a, b):
        q, r = ctypes.c_void_p(), ctypes.c_void_p()
        rc = self.dll.shim_divmod(a.encode(), b.encode(), ctypes.byref(q), ctypes.byref(r))
        if rc >= 0:
            return ("ok", self.take(q), self.take(r), rc)
        assert rc == -1, rc
        return ("err",) + self.error()

    def unop(self, op, a):
        p = ctypes.c_void_p()
        rc = self.dll.shim_unop(op, a.encode(), ctypes.byref(p))
        return self.result(rc, p)

    def to_double(self, a):
        out = ctypes.c_double()
        rc = self.dll.shim_to_double(a.encode(), ctypes.byref(out))
        if rc == 0:
            return ("ok", bits(out.value))
        assert rc == -1, rc
        return ("err",) + self.error()

    def truediv(self, a, b):
        out = ctypes.c_double()
        rc = self.dll.shim_truediv(a.encode(), b.encode(), ctypes.byref(out))
        if rc == 0:
            return ("ok", bits(out.value))
        assert rc == -1, rc
        return ("err",) + self.error()

    def cmp_double(self, a, x):
        out = ctypes.c_int()
        assert self.dll.shim_cmp_double(a.encode(), x, ctypes.byref(out)) == 0
        return out.value

    def from_str(self, data: bytes):
        """("ok", decimal string, small), ("err", class, message) or ("not handled",)."""
        p = ctypes.c_void_p()
        rc = self.dll.shim_from_str(data, len(data), ctypes.byref(p))
        if rc == NOT_HANDLED:
            assert self.error() is None, "not handled must record no exception"
            return ("not handled",)
        return self.result(rc, p)

    # -- handles
    def parse(self, s):
        h = ctypes.c_size_t()
        assert self.dll.shim_h_parse(s.encode(), ctypes.byref(h)) >= 0, self.error()
        return h.value

    def from_i64(self, v):
        h = ctypes.c_size_t()
        assert self.dll.shim_h_from_i64(v, ctypes.byref(h)) >= 0, self.error()
        return h.value

    def hop(self, op, a, b):
        h = ctypes.c_size_t()
        rc = self.dll.shim_h_binop(op, a, b, ctypes.byref(h))
        assert rc >= 0, (OPS[op], self.error())
        return h.value

    def hstr(self, h):
        p = ctypes.c_void_p()
        rc = self.dll.shim_h_str(h, ctypes.byref(p))
        return self.result(rc, p)


def bits(x: float) -> int:
    return struct.unpack("<Q", struct.pack("<d", x))[0]


def small(v: int) -> int:
    return 1 if SMALL_MIN <= v <= SMALL_MAX else 0


@pytest.fixture(scope="module")
def shim_lib(tmp_path_factory):
    """ONE library for the module: the shim and the header, built with the library flags."""
    need_cc()
    out = tmp_path_factory.mktemp("int_shim")
    path = cc.compile_library("", out, library.library_file_name("tp_aot_int_shim"), keep=SHIM)
    return Shim(path)


@pytest.fixture
def shim(shim_lib):
    """The library, and a check at the end of the test that no big object is left alive."""
    before = shim_lib.live()
    yield shim_lib
    assert shim_lib.live() == before, f"{shim_lib.live() - before} big objects leaked"


# --- the reference ---------------------------------------------------------------------------------------

def ref_str(x: int):
    try:
        return ("ok", str(x))
    except ValueError as e:
        return ("err", "ValueError", str(e))


def ref_call(f):
    try:
        return ("ok", f())
    except (ZeroDivisionError, OverflowError, ValueError) as e:
        return ("err", type(e).__name__, str(e))


def ref_binop(op, a, b):
    r = ref_call({0: lambda: a + b, 1: lambda: a - b, 2: lambda: a * b, 3: lambda: a // b, 4: lambda: a % b}[op])
    if r[0] == "err":
        return r
    s = ref_str(r[1])
    return ("ok", s[1], small(r[1])) if s[0] == "ok" else s


def ref_float(f):
    r = ref_call(f)
    return ("ok", bits(r[1])) if r[0] == "ok" else r


def ref_int(s: str):
    r = ref_call(lambda: int(s))
    if r[0] == "err":
        return r
    t = ref_str(r[1])
    return ("ok", t[1], small(r[1])) if t[0] == "ok" else t


# --- generated inputs ------------------------------------------------------------------------------------

def gen_numbers():
    rng = random.Random(460)
    vals = {0, 1, -1, 2, -2, 10, -10}
    for e in (30, 31, 32, 33, 52, 53, 54, 61, 62, 63, 64, 65, 95, 96, 97, 127, 128, 129, 191, 192, 193, 255, 256,
              257, 511, 512, 513, 1023, 1024, 1025, 2047, 2048, 4095, 4096):
        for d in (-1, 0, 1):
            vals.add(2 ** e + d)
            vals.add(-(2 ** e + d))
    for e in (1, 2, 9, 18, 19, 27, 36, 100, 308, 309, 400, 616, 1000, 1200):
        for d in (-1, 0, 1):
            vals.add(10 ** e + d)
            vals.add(-(10 ** e + d))
    for k in range(1, 9):                                          # limb patterns
        for pat in (0xFFFFFFFF, 0x80000000, 0x00000001, 0x7FFFFFFF):
            v = 0
            for _ in range(k):
                v = (v << 32) | pat
            vals.add(v)
            vals.add(-v)
        vals.add(0xFFFFFFFF << (32 * k))
        vals.add(((1 << 32 * k) - 1) * (1 << 5))
    for e in range(2, 4000, 97):
        vals.add(2 ** e + rng.choice((-1, 0, 1)))
        vals.add(-(2 ** e) + rng.choice((-1, 0, 1)))
    for nb in (1, 5, 20, 31, 32, 33, 40, 62, 63, 64, 65, 90, 128, 200, 500, 1000, 2000, 3500):
        for _ in range(5):
            vals.add(rng.getrandbits(nb) * rng.choice((1, -1)))
    big = [v for v in list(vals) if 60 <= abs(v).bit_length() <= 1000]
    for _ in range(40):
        vals.add(rng.choice(big) * rng.choice(big))
    return sorted((v for v in vals if abs(v).bit_length() <= 5000), key=lambda v: (abs(v), v))


NUMBERS = gen_numbers()


def gen_pairs():
    rng = random.Random(461)
    core = NUMBERS[::max(1, len(NUMBERS) // 48)][:48]
    pairs = [(a, b) for a in core for b in core]
    for _ in range(1500):
        pairs.append((rng.choice(NUMBERS), rng.choice(NUMBERS)))
    # dividends built from the divisor, to reach the correction step of Knuth's algorithm D
    for _ in range(1200):
        v = 0
        for _ in range(rng.randint(2, 7)):
            v = (v << 32) | rng.choice((0, 1, 0x7FFFFFFF, 0x80000000, 0xFFFFFFFE, 0xFFFFFFFF, rng.getrandbits(32)))
        if v < (1 << 32):
            v |= 1 << 40
        qq = 0
        for _ in range(rng.randint(1, 5)):
            qq = (qq << 32) | rng.choice((0, 1, 0xFFFFFFFF, 0xFFFFFFFE, rng.getrandbits(32)))
        rr = rng.choice((0, v - 1, rng.randrange(v), v // 2))
        sa, sb = rng.choice((1, -1)), rng.choice((1, -1))
        pairs.append((sa * (v * qq + rr), sb * v))
    return pairs


PAIRS = gen_pairs()


def gen_truediv_pairs():
    rng = random.Random(462)

    def num():
        k = rng.random()
        if k < 0.3:
            return rng.choice(NUMBERS)
        if k < 0.5:
            return rng.choice((-1, 1)) * ((1 << rng.randint(52, 1100)) + rng.choice((-1, 0, 1, 2 ** rng.randint(0, 40))))
        return rng.choice((-1, 1)) * rng.getrandbits(rng.randint(1, 1100))

    out = [(10 ** 400, 10 ** 399), (2 ** 1024, 3), (1, 10 ** 400), (2 ** 53 + 1, 1), (0, -5), (0, 5), (10 ** 400, 0),
           (1, 0), (2 ** 1024 - 2 ** 970, 1), (2 ** 1024 - 2 ** 970 - 1, 1), (2 ** 1024, 1), (2 ** 1074, 2 ** 2200),
           (1, 2 ** 1074), (1, 2 ** 1075), (3, 2 ** 1075), (5 * 2 ** 1023, 5), (-(2 ** 1024), 1), (2 ** 62, 3),
           (SMALL_MAX, SMALL_MIN), (2 ** 53 + 1, 3), (-(2 ** 53) - 1, 7), (1, -(2 ** 70)), (0, 2 ** 100)]
    for _ in range(3000):
        k = rng.random()
        if k < 0.3:
            out.append((num(), num()))
        elif k < 0.5:                                              # a quotient near a huge or tiny scale
            e = rng.randint(-1200, 1200)
            b = num() or 1
            a = (abs(b) >> -e) if e < 0 else abs(b) << e
            out.append(((a + rng.choice((-1, 0, 1))) * rng.choice((-1, 1)), b))
        elif k < 0.7:                                              # exactly halfway between two doubles, and one off
            m = (rng.getrandbits(53) | (1 << 52)) * 2 + 1
            b = rng.choice((1, 3, 7, 2 ** rng.randint(1, 80), rng.getrandbits(rng.randint(1, 70)) | 1))
            sh = rng.randint(-1100, 1000)
            a = m * b
            if sh >= 0:
                a <<= sh
            else:
                b <<= -sh
            a += rng.choice((0, 0, 0, 1, -1))
            out.append((a * rng.choice((-1, 1)), b * rng.choice((-1, 1))))
        elif k < 0.85:                                             # subnormal results
            out.append((rng.getrandbits(rng.randint(1, 120)), 1 << rng.randint(1000, 1200)))
        else:
            out.append((rng.getrandbits(rng.randint(1, 64)) * rng.choice((-1, 1)),
                        rng.getrandbits(rng.randint(1, 64)) * rng.choice((-1, 1))))
    return out


def assert_none_differ(mismatches, total, what):
    assert not mismatches, f"{what}: {len(mismatches)} of {total} differ; first: {mismatches[:3]}"


# --- arithmetic ------------------------------------------------------------------------------------------

def run_binops(shim, op):
    bad = []
    for a, b in PAIRS:
        got = shim.binop(op, str(a), str(b))
        want = ref_binop(op, a, b)
        if got != want:
            bad.append((OPS[op], a, b, got, want))
    assert_none_differ(bad, len(PAIRS), OPS[op])


def test_the_generated_inputs_cover_the_boundaries():
    bl = {abs(v).bit_length() for v in NUMBERS}
    assert {0, 1, 62, 63, 64, 65, 96, 97, 128, 1024, 1025}.issubset(bl)
    assert SMALL_MAX in NUMBERS and SMALL_MIN in NUMBERS and SMALL_MAX + 1 in NUMBERS and SMALL_MIN - 1 in NUMBERS
    assert 2 ** 63 in NUMBERS and -(2 ** 63) in NUMBERS and 2 ** 64 in NUMBERS
    assert len(NUMBERS) > 400 and len(PAIRS) > 4500


def test_build_is_atomic(shim):
    assert shim.dll.shim_threads() == 1, "the library build is the one with atomic counts"


@pytest.mark.parametrize("op", [0, 1, 2], ids=["add", "sub", "mul"])
def test_add_sub_mul_equal_the_reference(shim, op):
    run_binops(shim, op)


def test_floor_division_equals_the_reference(shim):
    run_binops(shim, 3)


def test_modulo_equals_the_reference(shim):
    run_binops(shim, 4)


def test_divmod_equals_the_reference(shim):
    bad = []
    for a, b in PAIRS:
        got = shim.divmod(str(a), str(b))
        if b == 0:
            want = ("err", "ZeroDivisionError", "division by zero")
        else:
            q, r = divmod(a, b)
            want = ("ok", str(q), str(r), small(q) | (small(r) << 1))
        if got != want:
            bad.append((a, b, got, want))
    assert_none_differ(bad, len(PAIRS), "divmod")


def test_division_and_modulo_by_zero_give_the_references_messages(shim):
    for a in (0, 1, -1, SMALL_MAX, 2 ** 64, -(2 ** 200)):
        for op, name in ((3, "floordiv"), (4, "mod")):
            assert shim.binop(op, str(a), "0") == ("err", "ZeroDivisionError", "division by zero"), (name, a)
        assert shim.divmod(str(a), "0") == ("err", "ZeroDivisionError", "division by zero")
        assert shim.truediv(str(a), "0") == ("err", "ZeroDivisionError", "division by zero")
    for call in (lambda: 1 // 0, lambda: 1 % 0, lambda: divmod(1, 0), lambda: 1 / 0):
        with pytest.raises(ZeroDivisionError, match="^division by zero$"):
            call()


def test_floor_division_rounds_toward_negative_infinity_for_all_sign_combinations(shim):
    bad = []
    for a in (7, -7, 2 ** 70 + 5, -(2 ** 70) - 5, SMALL_MIN, SMALL_MAX, 2 ** 62, -(2 ** 62) - 1):
        for b in (2, -2, 3, -3, 2 ** 65 + 1, -(2 ** 65) - 1, SMALL_MIN, -1, 1, 2 ** 62):
            for op in (3, 4):
                got, want = shim.binop(op, str(a), str(b)), ref_binop(op, a, b)
                if got != want:
                    bad.append((OPS[op], a, b, got, want))
    assert_none_differ(bad, 8 * 10 * 2, "floor division and modulo signs")


def test_negation_absolute_value_and_the_decimal_round_trip(shim):
    bad = []
    for a in NUMBERS:
        for op, f in ((0, lambda v: -v), (1, abs), (2, lambda v: v)):
            got = shim.unop(op, str(a))
            r = f(a)
            want = ("ok", str(r), small(r))
            if got != want:
                bad.append((op, a, got, want))
    assert_none_differ(bad, 3 * len(NUMBERS), "neg, abs, str(int(s))")
    # the two values whose negation crosses the small range
    assert shim.unop(0, str(SMALL_MIN)) == ("ok", str(2 ** 62), 0)
    assert shim.unop(0, str(2 ** 62)) == ("ok", str(SMALL_MIN), 1)
    assert shim.unop(1, str(SMALL_MIN)) == ("ok", str(2 ** 62), 0)


def test_comparison_equals_the_reference(shim):
    bad = []
    for a, b in PAIRS:
        got = shim.binop(5, str(a), str(b))
        want = ("ok", str((a > b) - (a < b)), 1)
        got2 = shim.binop(6, str(a), str(b))
        want2 = ("ok", str(int(a == b)), 1)
        if got != want or got2 != want2:
            bad.append((a, b, got, want, got2, want2))
    assert_none_differ(bad, len(PAIRS), "cmp, eq")


def test_results_are_canonical_small_exactly_when_they_fit(shim):
    """Equal values have one representation: a result inside [-2**62, 2**62 - 1] is small, outside it is big,
    for every operation (the flag of each comparison above); these cases sit on the edge."""
    edge = [SMALL_MIN - 1, SMALL_MIN, SMALL_MIN + 1, -1, 0, 1, SMALL_MAX - 1, SMALL_MAX, SMALL_MAX + 1, 2 ** 63, 2 ** 64]
    bad = []
    for a in edge:
        for b in edge:
            for op in (0, 1, 2, 3, 4):
                if op >= 3 and b == 0:
                    continue
                got, want = shim.binop(op, str(a), str(b)), ref_binop(op, a, b)
                if got != want:
                    bad.append((OPS[op], a, b, got, want))
    assert_none_differ(bad, len(edge) ** 2 * 5, "canonical form on the edge")
    # a big minus a big that lands back in the small range is small
    assert shim.binop(1, str(SMALL_MAX + 1), "1") == ("ok", str(SMALL_MAX), 1)
    assert shim.binop(0, str(SMALL_MIN - 1), "1") == ("ok", str(SMALL_MIN), 1)
    assert shim.binop(3, str(2 ** 100), str(2 ** 40)) == ("ok", str(2 ** 60), 1)


def test_identities_evaluated_inside_the_library(shim):
    """No string conversion in the middle: handles built from int64s only, then the identities."""
    rng = random.Random(463)
    rel = shim.dll.shim_h_release
    for _ in range(300):
        parts = [rng.choice((rng.getrandbits(63), -rng.getrandbits(63), SMALL_MAX, SMALL_MIN, 1, -1)) for _ in range(6)]
        hs = [shim.from_i64(p) for p in parts]
        t1 = shim.hop(2, hs[0], hs[1])
        t2 = shim.hop(2, t1, hs[2])
        a = shim.hop(0, t2, hs[5])
        b = shim.hop(2, hs[3], hs[4])
        pa, pb = parts[0] * parts[1] * parts[2] + parts[5], parts[3] * parts[4]
        assert shim.hstr(a)[:2] == ("ok", str(pa)) and shim.hstr(b)[:2] == ("ok", str(pb))
        assert shim.dll.shim_h_is_small(a) == small(pa) and shim.dll.shim_h_is_small(b) == small(pb)
        keep = []
        if pb != 0:
            q = shim.hop(3, a, b)
            r = shim.hop(4, a, b)
            qb = shim.hop(2, q, b)
            back = shim.hop(0, qb, r)
            assert shim.dll.shim_h_eq(back, a) == 1, (pa, pb)
            assert shim.hstr(q)[1] == str(pa // pb) and shim.hstr(r)[1] == str(pa % pb)
            prod = shim.hop(2, a, b)
            pq = shim.hop(3, prod, b)
            assert shim.dll.shim_h_eq(pq, a) == 1
            keep += [q, r, qb, back, prod, pq]
        ab = shim.hop(0, a, b)
        s = shim.hop(1, ab, b)
        assert shim.dll.shim_h_eq(s, a) == 1
        assert shim.dll.shim_h_cmp(a, b) == (pa > pb) - (pa < pb)
        parsed = shim.parse(str(pa))                     # from_str against the value built by arithmetic
        assert shim.dll.shim_h_eq(parsed, a) == 1
        for h in hs + [t1, t2, a, b, ab, s, parsed] + keep:
            rel(h)


# --- int64 -----------------------------------------------------------------------------------------------

def test_int64_round_trip_and_fit(shim):
    vals = {0, 1, -1, 2 ** 63 - 1, -(2 ** 63)}
    for e in (30, 31, 32, 33, 61, 62, 63):
        for d in (-2, -1, 0, 1, 2):
            vals.add(2 ** e + d)
            vals.add(-(2 ** e) + d)
    rng = random.Random(464)
    for _ in range(300):
        vals.add(rng.getrandbits(rng.randint(1, 63)) * rng.choice((1, -1)))
    bad = []
    n = 0
    for v in sorted(vals):
        if not -(2 ** 63) <= v < 2 ** 63:
            continue
        n += 1
        p, fits, back = ctypes.c_void_p(), ctypes.c_int(), ctypes.c_int64()
        rc = shim.dll.shim_i64_roundtrip(v, ctypes.byref(p), ctypes.byref(fits), ctypes.byref(back))
        got = (shim.take(p), rc, fits.value, back.value)
        want = (str(v), small(v), 1, v)
        if got != want:
            bad.append((v, got, want))
    assert_none_differ(bad, n, "from_i64 round trip")


def test_to_int64_reports_a_value_that_does_not_fit_without_an_exception(shim):
    bad = []
    cases = [v for v in NUMBERS if abs(v).bit_length() < 80] + [2 ** 63 - 1, 2 ** 63, -(2 ** 63), -(2 ** 63) - 1, 2 ** 64]
    for v in cases:
        out, fits = ctypes.c_int64(), ctypes.c_int()
        rc = shim.dll.shim_to_i64(str(v).encode(), ctypes.byref(out), ctypes.byref(fits))
        want_fit = -(2 ** 63) <= v < 2 ** 63
        got = (rc, fits.value, out.value if rc == 0 else None)
        want = (0, 1, v) if want_fit else (1, 0, None)
        if got != want or (rc != 0 and shim.error() is not None):
            bad.append((v, got, want))
    assert_none_differ(bad, len(cases), "to_i64")


# --- double ----------------------------------------------------------------------------------------------

def test_to_double_is_correctly_rounded_with_the_references_overflow(shim):
    rng = random.Random(465)
    cases = list(NUMBERS)
    cases += [2 ** 1024 - 2 ** 970, 2 ** 1024 - 2 ** 970 - 1, 2 ** 1024 - 2 ** 970 + 1, -(2 ** 1024 - 2 ** 970), 2 ** 1024 - 1,
              2 ** 1023, 2 ** 53 + 1, 2 ** 53 + 3, 2 ** 54 + 2, 2 ** 54 + 6, 2 ** 53, -(2 ** 53) - 1, SMALL_MAX, SMALL_MIN]
    for _ in range(4000):
        k = rng.random()
        if k < 0.4:
            v = (1 << rng.randint(52, 1100)) + rng.choice((-1, 0, 1, 2 ** rng.randint(0, 40)))
        else:
            v = rng.getrandbits(rng.randint(1, 1100))
        cases.append(v * rng.choice((1, -1)))
    bad = []
    for v in cases:
        got, want = shim.to_double(str(v)), ref_float(lambda: float(v))
        if got != want:
            bad.append((v, got, want))
    assert_none_differ(bad, len(cases), "float(int)")
    assert shim.to_double(str(10 ** 400)) == ("err", "OverflowError", "int too large to convert to float")


def test_true_division_is_correctly_rounded_for_all_inputs(shim):
    pairs = gen_truediv_pairs()
    bad = []
    for a, b in pairs:
        got, want = shim.truediv(str(a), str(b)), ref_float(lambda: a / b)
        if got != want:
            bad.append((a, b, got, want))
    assert_none_differ(bad, len(pairs), "a / b")
    # the cases the reference documents: operands too large for a double with a representable quotient, an
    # overflowing quotient, a quotient that underflows, an exact tie
    assert shim.truediv(str(10 ** 400), str(10 ** 399)) == ("ok", bits(10.0))
    assert shim.truediv(str(2 ** 1024), "1") == ("err", "OverflowError", "integer division result too large for a float")
    assert shim.truediv("1", str(10 ** 400)) == ("ok", bits(0.0))
    assert shim.truediv("0", "-5") == ("ok", bits(-0.0))


def test_the_two_overflow_messages_are_the_references():
    with pytest.raises(OverflowError, match="^int too large to convert to float$"):
        float(10 ** 400)
    with pytest.raises(OverflowError, match="^integer division result too large for a float$"):
        (2 ** 1024) / 1


def test_comparison_with_a_double_is_exact(shim):
    rng = random.Random(466)
    specials = [0.0, -0.0, math.inf, -math.inf, math.nan, 2.0 ** 62, -(2.0 ** 62), 2.0 ** 63, -(2.0 ** 63), 2.0 ** 64,
                1.7976931348623157e308, -1.7976931348623157e308, 5e-324, -5e-324, 0.5, -0.5, 1.5, -1.5, 2.0 ** 1023,
                2.0 ** 53, 2.0 ** 53 + 2, 4611686018427387904.0, 4611686018427387648.0, 1e22, 1e300]
    bad = []
    total = 0
    sample = NUMBERS[::2] + [SMALL_MAX, SMALL_MAX + 1, SMALL_MIN, SMALL_MIN - 1, 2 ** 53 + 1, 2 ** 1024, -(2 ** 1024),
                             2 ** 1024 - 1, 2 ** 63 - 1, 2 ** 63 + 1, 2 ** 64 + 1]
    for a in sample:
        xs = [rng.choice(specials) for _ in range(3)]
        try:
            f = float(a)
        except OverflowError:
            f = math.copysign(1.7976931348623157e308, a)
        xs += [f, math.nextafter(f, math.inf), math.nextafter(f, -math.inf)]
        xs.append(struct.unpack("<d", struct.pack("<Q", rng.getrandbits(64)))[0])
        xs.append(rng.uniform(-1e5, 1e5) * rng.choice((1, 1e10, 1e-10)))
        for x in xs:
            total += 1
            want = 2 if x != x else (a > x) - (a < x)
            got = shim.cmp_double(str(a), x)
            if got != want:
                bad.append((a, x, got, want))
    for x in specials:                                              # every special against the edge integers
        for a in (0, 1, -1, SMALL_MAX, SMALL_MIN, 2 ** 62, 2 ** 63, 2 ** 1024):
            total += 1
            want = 2 if x != x else (a > x) - (a < x)
            if shim.cmp_double(str(a), x) != want:
                bad.append((a, x))
    assert_none_differ(bad, total, "int against double")


# --- decimal strings -------------------------------------------------------------------------------------

def test_the_decimal_conversion_in_both_directions_agrees_with_the_reference(shim):
    bad = []
    for v in NUMBERS:
        got = shim.from_str(str(v).encode())
        want = ("ok", str(v), small(v))
        if got != want:
            bad.append((v, got))
    assert_none_differ(bad, len(NUMBERS), "int(str(v)) in the library")


def test_values_built_by_arithmetic_print_as_the_reference_prints_them(shim):
    """to_str on values from_str never produced: products and sums of int64s."""
    rng = random.Random(467)
    for _ in range(200):
        parts = [rng.choice((rng.getrandbits(63), -rng.getrandbits(63))) for _ in range(rng.randint(2, 60))]
        h, py = shim.from_i64(1), 1
        for p in parts:
            hp = shim.from_i64(p)
            nh = shim.hop(2, h, hp)
            shim.dll.shim_h_release(h)
            shim.dll.shim_h_release(hp)
            h, py = nh, py * p
        want, got = ref_str(py), shim.hstr(h)
        assert got[:2] == ("ok", want[1]) if want[0] == "ok" else got == want, (parts, got, want)
        shim.dll.shim_h_release(h)


def test_to_str_limit_at_the_exact_boundary(shim):
    """str() refuses more than 4300 digits: 10**4299 has 4300 digits, 10**4300 has 4301. The values are built by
    arithmetic (from_str refuses to read more than 4300 digits), the sign is not counted."""
    for a_e, b_e in ((2150, 2149), (2150, 2150), (2150, 2151), (2150, 2000)):
        a, b = 10 ** a_e, 10 ** b_e
        assert shim.binop(2, str(a), str(b)) == ref_binop(2, a, b), (a_e, b_e)
    ha = shim.parse(str(10 ** 2150))
    for delta in (-1, 0, 1):
        for sign in (1, -1):
            v = sign * (10 ** 4300 + delta)
            prod = shim.hop(2, ha, ha)
            hd = shim.from_i64(delta)
            tot = shim.hop(0, prod, hd)
            hs = shim.from_i64(sign)
            res = shim.hop(2, tot, hs)
            got, want = shim.hstr(res), ref_str(v)
            if want[0] == "ok":
                assert got[:2] == ("ok", want[1]), (delta, sign)
            else:
                assert got == want, (delta, sign, got, want)
            for x in (prod, hd, tot, hs, res):
                shim.dll.shim_h_release(x)
    shim.dll.shim_h_release(ha)
    assert ref_str(10 ** 4300 - 1)[0] == "ok" and ref_str(10 ** 4300)[0] == "err"


def test_to_str_limit_near_the_bit_length_threshold(shim):
    """Values of 14270 to 14310 bits (10**4300 has 14285 bits; the library refuses at once above 14300)."""
    bad = []
    for k in range(14270, 14311):
        a, b = 2 ** 7000, 2 ** (k - 7000)
        got, want = shim.binop(2, str(a), str(b)), ref_binop(2, a, b)
        if got != want:
            bad.append((k, got, want))
    assert_none_differ(bad, 41, "str near the limit")
    assert shim.binop(2, str(2 ** 7000), str(2 ** 9000)) == ("err", "ValueError", str_limit_message())
    big = shim.parse(str(2 ** 7000))
    inner = shim.hop(2, big, big)
    huge = shim.hop(2, big, inner)                               # 2**21000
    assert shim.hstr(huge) == ("err", "ValueError", str_limit_message())
    for x in (big, inner, huge):
        shim.dll.shim_h_release(x)


def str_limit_message():
    try:
        str(10 ** 4300)
    except ValueError as e:
        return str(e)
    raise AssertionError("no limit")


def from_str_limit_message(n):
    try:
        int("1" * n)
    except ValueError as e:
        return str(e)
    raise AssertionError("no limit")


def test_the_digit_limit_in_both_directions_is_the_references(shim):
    assert str_limit_message() == ("Exceeds the limit (4300 digits) for integer string conversion; "
                                   "use sys.set_int_max_str_digits() to increase the limit")
    assert from_str_limit_message(4301) == ("Exceeds the limit (4300 digits) for integer string conversion: "
                                            "value has 4301 digits; use sys.set_int_max_str_digits() to increase the limit")
    for s in ("1" * 4300, "1" * 4301, "-" + "1" * 4300, "-" + "1" * 4301, "0" * 4300, "0" * 4301, "9" * 4300,
              "1_" * 2150 + "1", "1_" * 4300 + "1", " " + "1" * 4301 + " ", "+" + "1" * 4301, "1" * 5000, "0" * 9000,
              "1" * 4301 + "x", "1" * 4301 + "_", "1" * 4301 + "__1", "x" + "1" * 4301, "1" * 4310 + " 1",
              "1_" * 4300 + "1_", "\u0661" * 4301, "\u0661" * 4300, "1" * 4300 + "\u00a0", "\u3000" + "1" * 4301):
        got, want = shim.from_str(s.encode("utf-8")), ref_int(s)
        assert got == want, (s[:12], len(s), got, want)


# --- int(str) --------------------------------------------------------------------------------------------

ASCII_WHITESPACE = [c for c in range(128) if (lambda s: ref_int(s)[0] == "ok")(chr(c) + "5" + chr(c))]
UNICODE_WHITESPACE = [0x85, 0xA0, 0x1680, *range(0x2000, 0x200B), 0x2028, 0x2029, 0x202F, 0x205F, 0x3000]


def ref_ok(s):
    try:
        int(s)
        return True
    except ValueError:
        return False


def test_the_whitespace_int_strips_is_the_set_the_header_documents():
    ws_all = sorted(c for c in range(0x110000) if not 0xD800 <= c < 0xE000
                    and ref_ok(chr(c) + "7") and unicodedata.category(chr(c)) != "Nd" and chr(c) not in "+-")
    assert ws_all == [9, 10, 11, 12, 13, 32] + UNICODE_WHITESPACE
    assert ASCII_WHITESPACE == [9, 10, 11, 12, 13, 32], "0x1c to 0x1f are not whitespace for int()"


VALID = ["0", "-0", "+0", "00", "007", "-007", "1_000", "-1_0_0", "+1_2", " 12 ", "\t\n\v\f\r 5 \t\n\v\f\r", "+5", "-5",
         "0_0", "1" * 18, "1" * 19, "9" * 19, str(2 ** 62), str(2 ** 62 - 1), "-" + str(2 ** 62), "-" + str(2 ** 62 + 1),
         str(2 ** 63), "-" + str(2 ** 63), str(2 ** 64), "\u0663\u0662", "1_\u0663", "\uff15\uff16", "\u00a042\u3000",
         "\u2028-1\u2029", "\u0085+1_2\u0085", "\u1680 7 \u202f", "\U0001D7D9\U0001D7DA", "\u0966_\u0967"]
INVALID = ["", " ", "  \t", "+", "-", "+-1", "--1", "-+1", "1 2", "1_", "_1", "1__2", "1_ ", " _1", "+_1", "-_1", "0x10", "0b1",
           "0o7", "1e3", "1.0", ".5", "5.", "- 1", "+ 1", "1\x00", "\x001", "12\x0034", "\x1c1", "1\x1f", "\x1d", "a", "abc",
           "1,000", "1 000", "0_", "_", "__", "'", '"', "'\"", "\\", "\n", "\r\n1\r\n 2", "\t", "\x7f", "\x01\x02\x1b",
           "x" * 198, "x" * 199, "x" * 200, "x" * 201, "x" * 300, "'" * 150, '"' * 150, "\\" * 120, "\n" * 120, "\x01" * 60,
           "a" * 197 + "\n", "a" * 198 + "\n", "a" * 199 + "\n", "a" * 198 + "'", "'" + "a" * 300, "\"'" + "a" * 300,
           "1" * 4302 + " x"]
# inputs the runtime declines, with no exception: an invalid literal that holds a non-ASCII character
NOT_HANDLED_PINNED = ["\u0661\u0662\u0663x", "\u00e9", "\u2212 5", "5\u2212", "1\u00e9", "\u00e91", "\u4e2d", "\u0660x", "1_\u00e9", "\U0001F600",
                      "12\u200b", "\ufeff1", "\u00b2", "\u00bd", "\u0e51\u0e52\u0e53\u0e54x", "\u1c4a\u1c4b y"]


def test_int_from_str_valid_and_invalid_inputs_equal_the_reference(shim):
    declined, bad = [], []
    for s in VALID + INVALID + NOT_HANDLED_PINNED:
        got, want = shim.from_str(s.encode("utf-8")), ref_int(s)
        if got == ("not handled",):
            declined.append(s)
            assert want[0] == "err" and want[2].startswith("invalid literal for int() with base 10: "), (s, want)
            assert not s.isascii(), s
        elif got != want:
            bad.append((s[:20], got, want))
    assert_none_differ(bad, len(VALID + INVALID + NOT_HANDLED_PINNED), "int(str)")
    assert declined == NOT_HANDLED_PINNED, "the inputs the runtime declines are exactly the pinned ones"
    for s in VALID:
        assert ref_int(s)[0] == "ok", s
    for s in INVALID:
        assert ref_int(s)[0] == "err", s


def test_int_from_str_every_ascii_character_in_every_position(shim):
    bad = []
    n = 0
    for c in range(128):
        ch = chr(c)
        for s in (ch, ch + "1", "1" + ch, "1" + ch + "1", " " + ch, ch + " ", "-" + ch + "1", "1_" + ch, ch + "_1", "+" + ch):
            n += 1
            got, want = shim.from_str(s.encode("ascii")), ref_int(s)
            if got != want:
                bad.append((s, got, want))
    assert_none_differ(bad, n, "int(str) over ASCII")


def test_int_from_str_random_strings_equal_the_reference(shim):
    rng = random.Random(468)
    alpha = list("0123456789_+- \t\n\x1c\x00xe'\"\\") + ["\u00a0", "\u0663", "\u3000", "\u2212", "\u00e9", "\uff15", "\u2029"]
    bad, declined = [], 0
    n = 4000
    for _ in range(n):
        s = "".join(rng.choice(alpha) for _ in range(rng.choice((0, 1, 2, 3, 5, 8, 12))))
        got, want = shim.from_str(s.encode("utf-8")), ref_int(s)
        if got == ("not handled",):
            declined += 1
            if not (want[0] == "err" and "invalid literal" in want[2] and not s.isascii()):
                bad.append((s, got, want))
        elif got != want:
            bad.append((s, got, want))
    assert_none_differ(bad, n, "random int(str)")
    assert declined > 100, "the alphabet does exercise the declined inputs"


def test_int_from_str_unicode_whitespace_and_digits_by_code_point(shim):
    """Every code point c: the strings c + '7' and '7' + c (the first is valid exactly when c is whitespace or a
    decimal digit; the second when c is whitespace). The runtime either equals the reference or declines an
    invalid literal. The digit table of the header is Unicode 17.0 (the data of 3.15): another version of the
    interpreter's unicodedata makes this test skip rather than compare with different data."""
    if unicodedata.unidata_version != PINNED_UNICODE:
        pytest.skip(f"the digit table is Unicode {PINNED_UNICODE}, this interpreter has {unicodedata.unidata_version}")
    bad = []
    accepted = declined = total = 0
    for cp in range(0x110000):
        if 0xD800 <= cp < 0xE000:
            continue
        c = chr(cp)
        probes = [c + "7"]
        if cp % 211 == 0 or ref_ok(c + "7"):                          # the trailing position: whitespace, digits, a sample
            probes.append("7" + c)
        for s in probes:
            total += 1
            got = shim.from_str(s.encode("utf-8"))
            if got == ("not handled",):
                declined += 1
                if ref_ok(s) or cp < 128:
                    bad.append((hex(cp), s, got))
                continue
            want = ref_int(s)
            if got != want:
                bad.append((hex(cp), got, want))
            accepted += want[0] == "ok"
    assert_none_differ(bad, total, "int(str) over every code point")
    assert accepted > 1000 and declined > 1_000_000, (accepted, declined)


def test_int_from_str_invalid_utf8_is_declined_with_no_exception(shim):
    for data in (b"\xff", b"1\xc0\x80", b"\xed\xa0\x80", b"\xf4\x90\x80\x80", b"\xe2\x82", b"\x80", b"5\xc3", b"\xf0\x80\x80\x80"):
        assert shim.from_str(data) == ("not handled",), data
        assert shim.error() is None


def test_int_from_str_embedded_nul_is_an_invalid_literal_not_the_end_of_the_string(shim):
    for s in ("12\x0034", "\x00", "1\x00", " 1 \x00"):
        assert shim.from_str(s.encode()) == ref_int(s), s


# --- threads ---------------------------------------------------------------------------------------------

def test_threads_share_read_only_operands_and_compute_on_their_own_values(shim):
    """Six threads: each retains and releases the two shared bigs (the count is atomic in this build), multiplies
    and divides them, and also works on values of its own; the main thread releases the originals at the end.
    The exception record is per thread: each thread's division by zero reads back its own error."""
    a_s, b_s = str(7 ** 1800 - 99), str(3 ** 800 + 12345)
    a_i, b_i = int(a_s), int(b_s)
    ha, hb = shim.parse(a_s), shim.parse(b_s)
    want_prod = str(a_i * b_i)
    want_q, want_r = str(a_i // b_i), str(a_i % b_i)
    failures = []

    def worker(k):
        try:
            rng = random.Random(1000 + k)
            for i in range(120):
                shim.dll.shim_h_retain(ha)
                shim.dll.shim_h_retain(hb)
                prod = shim.hop(2, ha, hb)
                q, r = shim.hop(3, ha, hb), shim.hop(4, ha, hb)
                if (shim.hstr(prod)[1], shim.hstr(q)[1], shim.hstr(r)[1]) != (want_prod, want_q, want_r):
                    failures.append(("shared", k, i))
                for h in (prod, q, r):
                    shim.dll.shim_h_release(h)
                shim.dll.shim_h_release(ha)
                shim.dll.shim_h_release(hb)
                x, y = rng.getrandbits(rng.randint(60, 900)) - (1 << 400), rng.getrandbits(rng.randint(2, 500)) + 1
                got = [shim.binop(op, str(x), str(y)) for op in (0, 1, 2, 3, 4)]
                want = [ref_binop(op, x, y) for op in (0, 1, 2, 3, 4)]
                if got != want:
                    failures.append(("own", k, i, x, y))
                if shim.binop(3, str(x), "0") != ("err", "ZeroDivisionError", "division by zero"):
                    failures.append(("error record", k, i))
        except BaseException as e:      # a thread must report, not vanish
            failures.append(("exception", k, repr(e)))

    threads = [threading.Thread(target=worker, args=(k,)) for k in range(6)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert not failures, failures[:3]
    for h in (ha, hb):
        shim.dll.shim_h_release(h)
    assert shim.live() == 0


# --- text tests (no compiler) ----------------------------------------------------------------------------

def test_the_header_and_the_shim_contain_no_cpython():
    for what, path in (("tp_aot_int.h", HEADER), ("tp_aot_int_shim.c", SHIM)):
        body = path.read_text(encoding="utf-8")
        assert "Python.h" not in body, what
        assert not re.search(r"\b_?Py[A-Z_a-z]", body), (what, re.findall(r"\b_?Py[A-Z_a-z]\w*", body))
        assert "\u2014" not in body, what
        assert "__int128" not in body and "__builtin" not in body, what
    includes = re.findall(r"#include\s*[<\"]([^>\"]+)", HEADER.read_text(encoding="utf-8"))
    assert sorted(includes) == ["math.h", "stddef.h", "stdint.h", "stdlib.h", "string.h"], includes
    assert re.findall(r"#include\s*[<\"]([^>\"]+)", SHIM.read_text(encoding="utf-8")) == ["tp_aot.h", "tp_aot_int.h"]


def test_the_live_counter_exists_only_under_its_macro():
    """Outside `#if defined(TPA_INT_COUNT_LIVE) ... #else` no code names the counter, and the guarded region
    is the only place that defines it."""
    body = re.sub(r"/\*.*?\*/", "", HEADER.read_text(encoding="utf-8"), flags=re.S)       # comments
    m = re.search(r"#if defined\(TPA_INT_COUNT_LIVE\)(.*?)\n#else\n(.*?)\n#endif", body, re.S)
    assert m, "the counter has a guarded region with an #else"
    outside = body[:m.start()] + body[m.end():]
    assert "tpa_int_live_counter" in m.group(1) and "tpa_int_live(void)" in m.group(1)
    assert not re.search(r"\btpa_int_live(_counter|_t)?\b", outside), "the counter is named outside its guard"
    assert "tpa_int_live" not in m.group(2)
    # the other uses go through macros that are empty without the guard
    assert "#define TPA_INT__LIVE_INC() ((void)0)" in m.group(2) and "#define TPA_INT__LIVE_DEC() ((void)0)" in m.group(2)


def test_the_aot_back_end_does_not_use_the_header_yet():
    for path in AOT_DIR.rglob("*"):
        if path.is_file() and path.suffix in {".py", ".h", ".c"}:
            assert "tp_aot_int" not in path.read_text(encoding="utf-8", errors="replace"), path
