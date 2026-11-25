"""AOT library output, tier S2b: a dict across the library boundary (ABI version 3, #443).

`dict[int, int]`, `dict[int, float]` and `dict[int, bool]` parameters and results of exported functions, as the opaque
counted handles `<p>_dict_i64_i64`, `<p>_dict_i64_f64` and `<p>_dict_i64_bool` with `_new`, `_set`, `_get`, `_contains`,
`_next`, `_len`, `_retain` and `_release`. The consumer is ctypes, as in test_aot_lib.py: every exported function is called
through the C ABI and compared with the SAME source imported as a plain module (the items in their order, floats by their
bits, the class and message of a failure). The reference count of a handle is its first 8 bytes (the runtime's dict starts
with its count), and the tests read it after the calls.

The rule that lets one handle be shared by threads without a lock: an exported function never writes a dict parameter
(aot/library-dict-write, checked through the callees it passes the dict to); the caller builds a dict with `_new` and `_set`
before sharing it. A per-dict lock is tier T. ONE library is built here (`dlib`); the refusals need no compiler.

Agent proposal, not owner-confirmed (the ABI shape is the leader's to decide; the names and messages are agent proposals).
"""
import ctypes
import importlib.util
import json
import re
import shutil
import struct
import sys
import textwrap
import threading
from pathlib import Path
from types import SimpleNamespace

import pytest

from typedpython import aot, cbuild
from typedpython.aot import cc, library
from aot_text_check import cpython_api_uses

HEADER = cc.RUNTIME_DIR / "tp_aot.h"
PREFIX = "dk"


def need_cc():
    if sys.version_info < (3, 14):
        pytest.skip("the AOT runtime carries the exception messages of CPython 3.14 and later")
    if not HEADER.is_file():
        pytest.skip(f"{HEADER} does not exist")
    try:
        if cbuild._is_msvc():
            cbuild.msvc_env()
        elif shutil.which(cbuild.compiler()[0]) is None:
            pytest.skip("no C compiler")
    except cbuild.CBuildError as e:
        pytest.skip(str(e))


def write(tmp_path, source, name="mod"):
    path = tmp_path / f"{name}.py"
    path.write_text(textwrap.dedent(source).lstrip("\n"))
    return path


DLIB = '''
def counts(xs: list[int]) -> dict[int, int]:
    d: dict[int, int] = {}
    for x in xs:
        d[x] = d.get(x, 0) + 1
    return d


def total(d: dict[int, int]) -> int:
    s = 0
    for k, v in d.items():
        s += k * v
    return s


def size_of(d: dict[int, int]) -> int:
    return len(d)


def via_helper(d: dict[int, int]) -> int:
    return total(d) + size_of(d)


def lookup(d: dict[int, int], k: int) -> int:
    return d[k]


def has(d: dict[int, float], k: int) -> bool:
    return k in d


def scaled(d: dict[int, float], f: float) -> dict[int, float]:
    out: dict[int, float] = {}
    for k in d:
        out[k] = d[k] * f
    return out


def evens(n: int) -> dict[int, bool]:
    out: dict[int, bool] = {}
    for i in range(n):
        out[i] = i % 2 == 0
    return out


def same(d: dict[int, bool]) -> dict[int, bool]:
    return d


def true_count(d: dict[int, bool]) -> int:
    n = 0
    for v in d.values():
        if v:
            n += 1
    return n


def big(n: int) -> dict[int, int]:
    v = 1
    for _ in range(n):
        v *= 2
    d: dict[int, int] = {}
    d[7] = v
    d[v] = 7
    return d


if __name__ == "__main__":
    print(total(counts([1, 2])))
'''
EXPORTS = ["counts", "total", "via_helper", "lookup", "has", "scaled", "evens", "same", "true_count", "big"]
KINDS = {"dict_i64_i64": ("i64", "i64"), "dict_i64_f64": ("i64", "f64"), "dict_i64_bool": ("i64", "bool")}
CT = {"i64": ctypes.c_int64, "f64": ctypes.c_double, "bool": ctypes.c_uint8}
ARG_CT = {**CT, "i64_list": ctypes.c_void_p, **{k: ctypes.c_void_p for k in KINDS}}


def bits(x: float) -> bytes:
    return struct.pack("<d", x)


class DLib:
    """The library through ctypes, every signature from its description."""

    def __init__(self, path, desc):
        self.desc = desc
        self.cdll = ctypes.CDLL(str(path))
        self.fn = {}
        for f in desc["functions"]:
            c = getattr(self.cdll, f["symbol"])
            c.restype = ctypes.c_int
            c.argtypes = [ARG_CT[p["type"]] for p in f["params"]] + [ctypes.POINTER(ARG_CT[t]) for t in f["returns"]]
            self.fn[f["name"]] = (c, f)
        self.dicts = {}
        for kind, syms in desc["dicts"].items():
            v = CT[syms["value"]]
            ns = SimpleNamespace(vt=v, value=syms["value"], **{k: getattr(self.cdll, syms[k]) for k in
                                                              ("new", "set", "get", "contains", "next", "len", "retain",
                                                               "release")})
            ns.new.restype, ns.new.argtypes = ctypes.c_void_p, []
            ns.set.restype, ns.set.argtypes = ctypes.c_int, [ctypes.c_void_p, ctypes.c_int64, v]
            ns.get.restype, ns.get.argtypes = ctypes.c_int, [ctypes.c_void_p, ctypes.c_int64, ctypes.POINTER(v)]
            ns.contains.restype, ns.contains.argtypes = ctypes.c_int, [ctypes.c_void_p, ctypes.c_int64]
            ns.next.restype = ctypes.c_int
            ns.next.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_int64), ctypes.POINTER(ctypes.c_int64),
                                ctypes.POINTER(v)]
            ns.len.restype, ns.len.argtypes = ctypes.c_int64, [ctypes.c_void_p]
            ns.retain.restype, ns.retain.argtypes = None, [ctypes.c_void_p]
            ns.release.restype, ns.release.argtypes = None, [ctypes.c_void_p]
            self.dicts[kind] = ns
        ls = desc["lists"]["i64_list"]
        self.lnew, self.ldata, self.lrel = (getattr(self.cdll, ls[k]) for k in ("new", "data", "release"))
        self.lnew.restype, self.lnew.argtypes = ctypes.c_void_p, [ctypes.c_int64]
        self.ldata.restype, self.ldata.argtypes = ctypes.c_void_p, [ctypes.c_void_p]
        self.lrel.restype, self.lrel.argtypes = None, [ctypes.c_void_p]
        self.last = getattr(self.cdll, desc["runtime"]["last_error"])
        self.last.restype = ctypes.c_int
        self.last.argtypes = [ctypes.POINTER(ctypes.c_char_p), ctypes.POINTER(ctypes.c_char_p)]

    def new(self, kind, items):
        ns = self.dicts[kind]
        h = ns.new()
        assert h
        for k, v in items:
            assert ns.set(h, k, v) == 0
        return h

    def items(self, kind, h):
        ns = self.dicts[kind]
        pos, k, v = ctypes.c_int64(0), ctypes.c_int64(), ns.vt()
        out = []
        while True:
            r = ns.next(h, ctypes.byref(pos), ctypes.byref(k), ctypes.byref(v))
            if r == 0:
                return out
            assert r == 1, self.error()
            out.append((k.value, bool(v.value) if ns.value == "bool" else v.value))

    def int_list(self, values):
        h = self.lnew(len(values))
        arr = (ctypes.c_int64 * max(len(values), 1)).from_address(self.ldata(h)) if values else []
        for i, x in enumerate(values):
            arr[i] = x
        return h

    @staticmethod
    def rc(h):
        return ctypes.c_int64.from_address(h).value

    def call(self, name, *args):
        c, f = self.fn[name]
        outs = [ARG_CT[t]() for t in f["returns"]]
        status = c(*args, *[ctypes.byref(o) for o in outs])
        return status, [o.value for o in outs]

    def error(self):
        cn, msg = ctypes.c_char_p(), ctypes.c_char_p()
        if self.last(ctypes.byref(cn), ctypes.byref(msg)) != 0:
            return None
        return cn.value.decode("utf-8"), msg.value.decode("utf-8")


def reference(path: Path):
    spec = importlib.util.spec_from_file_location("dlib_ref", str(path))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="module")
def dlib(tmp_path_factory):
    need_cc()
    tmp = tmp_path_factory.mktemp("dlib")
    src = write(tmp, DLIB, "dlib")
    c_text, header, description = aot.generate_library(src, exports=EXPORTS, prefix=PREFIX)
    out = tmp / "out"
    path = cc.compile_library(c_text, out, library.library_file_name(PREFIX))
    return SimpleNamespace(lib=DLib(path, json.loads(json.dumps(description))), ref=reference(src), c=c_text,
                           header=header, description=description)


def test_the_description_and_the_header_name_the_dict_kinds_the_signatures_use(dlib):
    d, h, c = dlib.description, dlib.header, dlib.c
    assert d["abi_version"] == 3
    for kind in KINDS:
        assert kind in library.VOCABULARY
    types = {f["name"]: ([p["type"] for p in f["params"]], f["returns"]) for f in d["functions"]}
    assert types["counts"] == (["i64_list"], ["dict_i64_i64"])
    assert types["total"] == (["dict_i64_i64"], ["i64"]) and types["lookup"] == (["dict_i64_i64", "i64"], ["i64"])
    assert types["has"] == (["dict_i64_f64", "i64"], ["bool"])
    assert types["scaled"] == (["dict_i64_f64", "f64"], ["dict_i64_f64"])
    assert types["same"] == (["dict_i64_bool"], ["dict_i64_bool"]) and types["evens"] == (["i64"], ["dict_i64_bool"])
    assert set(d["dicts"]) == set(KINDS)
    assert d["dicts"]["dict_i64_f64"] == {
        "key": "i64", "value": "f64", "new": "dk_dict_i64_f64_new", "set": "dk_dict_i64_f64_set",
        "get": "dk_dict_i64_f64_get", "contains": "dk_dict_i64_f64_contains", "next": "dk_dict_i64_f64_next",
        "len": "dk_dict_i64_f64_len", "retain": "dk_dict_i64_f64_retain", "release": "dk_dict_i64_f64_release"}
    assert "typedef struct dk_dict_i64_i64 dk_dict_i64_i64;" in h
    assert "DK_API dk_dict_i64_f64 *dk_dict_i64_f64_new(void);" in h
    assert "DK_API int dk_dict_i64_f64_set(dk_dict_i64_f64 *d, int64_t key, double value);" in h
    assert "DK_API int dk_dict_i64_bool_get(const dk_dict_i64_bool *d, int64_t key, uint8_t *out);" in h
    assert "DK_API int dk_dict_i64_i64_contains(const dk_dict_i64_i64 *d, int64_t key);" in h
    assert ("DK_API int dk_dict_i64_i64_next(const dk_dict_i64_i64 *d, int64_t *pos, int64_t *key, int64_t *value);"
            in h)
    assert "DK_API int dk_counts(dk_i64_list *p_xs, dk_dict_i64_i64 **out);" in h
    assert "DK_API int dk_scaled(dk_dict_i64_f64 *p_d, double p_f, dk_dict_i64_f64 **out);" in h
    assert "Dicts (" in h and "never writes a dict" in h and "the value of the dict does not fit in 64 bits" in h
    assert chr(0x2014) not in h
    assert not cpython_api_uses(c) and not cpython_api_uses(h)
    syms = [f["symbol"] for f in d["functions"]] + [v for e in d["dicts"].values()
                                                   for k, v in e.items() if k not in ("key", "value")]
    for sym in syms:
        assert re.search(rf"^DK_API [^\n;]*\b{sym}\(", h, re.M), sym
        assert re.search(rf"^TPA_EXPORT [^\n]*\b{sym}\(", c, re.M), sym


def test_a_library_without_a_dict_at_its_boundary_has_no_dict_entry(tmp_path):
    src = write(tmp_path, '''
        def inside(n: int) -> int:
            d: dict[int, int] = {}
            d[n] = n
            return len(d)
        ''')
    _c, h, d = aot.generate_library(src, exports=["inside"], prefix="nd")
    assert d["abi_version"] == 3 and "dicts" not in d and "nd_dict_" not in h


@pytest.mark.parametrize("xs", [[], [3, 1, 3, 2, 1, 3], list(range(40)) * 2, [-5, 2 ** 62, -5]])
def test_a_returned_dict_has_the_items_and_the_order_cpython_gives(dlib, xs):
    lib, ref = dlib.lib, dlib.ref
    lh = lib.int_list(xs)
    status, (h,) = lib.call("counts", lh)
    assert status == 0, lib.error()
    assert lib.rc(h) == 1
    assert lib.items("dict_i64_i64", h) == list(ref.counts(xs).items())
    assert lib.dicts["dict_i64_i64"].len(h) == len(ref.counts(xs))
    lib.dicts["dict_i64_i64"].release(h)
    lib.lrel(lh)


def test_a_dict_built_by_the_caller_is_read_by_the_functions_as_cpython_reads_it(dlib):
    lib, ref = dlib.lib, dlib.ref
    items = [(5, 2), (-3, 7), (5, 4), (2 ** 63 - 1, 0), (0, -9)]
    h = lib.new("dict_i64_i64", items)
    expect = dict(items)
    assert lib.items("dict_i64_i64", h) == list(expect.items())        # a set of an existing key keeps its place
    for name in ("total", "via_helper"):
        status, (r,) = lib.call(name, h)
        assert status == 0 and r == getattr(ref, name)(expect)
    status, (r,) = lib.call("lookup", h, -3)
    assert status == 0 and r == 7
    assert lib.rc(h) == 1
    lib.dicts["dict_i64_i64"].release(h)


def test_a_missing_key_is_the_key_error_cpython_raises(dlib):
    lib, ref = dlib.lib, dlib.ref
    h = lib.new("dict_i64_i64", [(1, 1)])
    with pytest.raises(KeyError) as e:
        ref.lookup({1: 1}, -42)
    status, _ = lib.call("lookup", h, -42)
    assert status == -1 and lib.error() == ("KeyError", str(e.value))
    out = ctypes.c_int64(-12345)
    assert lib.dicts["dict_i64_i64"].get(h, 99, ctypes.byref(out)) == -1
    assert lib.error() == ("KeyError", "99") and out.value == -12345
    assert lib.dicts["dict_i64_i64"].get(h, 1, ctypes.byref(out)) == 0 and out.value == 1
    assert lib.dicts["dict_i64_i64"].contains(h, 1) == 1 and lib.dicts["dict_i64_i64"].contains(h, 2) == 0
    lib.dicts["dict_i64_i64"].release(h)


def test_float_values_keep_their_bits(dlib):
    lib, ref = dlib.lib, dlib.ref
    items = [(1, 0.1), (2, -0.0), (3, 1e308), (4, float("inf")), (5, 5e-324)]
    h = lib.new("dict_i64_f64", items)
    status, (r,) = lib.call("scaled", h, 3.0)
    assert status == 0, lib.error()
    got = lib.items("dict_i64_f64", r)
    want = list(ref.scaled(dict(items), 3.0).items())
    assert [(k, bits(v)) for k, v in got] == [(k, bits(v)) for k, v in want]
    for k in (3, 6):
        status, (b,) = lib.call("has", h, k)
        assert status == 0 and bool(b) == ref.has(dict(items), k)
    lib.dicts["dict_i64_f64"].release(r)
    lib.dicts["dict_i64_f64"].release(h)


def test_bool_values_and_a_returned_argument_keeps_its_identity(dlib):
    lib, ref = dlib.lib, dlib.ref
    status, (h,) = lib.call("evens", 7)
    assert status == 0
    assert lib.items("dict_i64_bool", h) == list(ref.evens(7).items())
    status, (s,) = lib.call("same", h)
    assert status == 0 and s == h and lib.rc(h) == 2
    lib.dicts["dict_i64_bool"].release(s)
    assert lib.dicts["dict_i64_bool"].set(h, 1, 7) == 0                  # a value other than 0 or 1 is true
    out = ctypes.c_uint8(9)
    assert lib.dicts["dict_i64_bool"].get(h, 1, ctypes.byref(out)) == 0 and out.value == 1
    status, (n,) = lib.call("true_count", h)
    assert status == 0 and n == 5
    assert lib.rc(h) == 1
    lib.dicts["dict_i64_bool"].release(h)


def test_a_key_or_value_outside_64_bits_is_the_documented_overflow_and_the_rest_reads(dlib):
    lib = dlib.lib
    status, (h,) = lib.call("big", 70)
    assert status == 0, lib.error()
    ns = lib.dicts["dict_i64_i64"]
    pos, k, v = ctypes.c_int64(0), ctypes.c_int64(-1), ctypes.c_int64(-1)
    assert ns.next(h, ctypes.byref(pos), ctypes.byref(k), ctypes.byref(v)) == -1
    assert lib.error() == ("OverflowError", "the value of the dict does not fit in 64 bits")
    assert ns.next(h, ctypes.byref(pos), ctypes.byref(k), ctypes.byref(v)) == -1
    assert lib.error() == ("OverflowError", "the key of the dict does not fit in 64 bits")
    assert ns.next(h, ctypes.byref(pos), ctypes.byref(k), ctypes.byref(v)) == 0
    out = ctypes.c_int64(-1)
    assert ns.get(h, 7, ctypes.byref(out)) == -1 and out.value == -1
    assert lib.error() == ("OverflowError", "the value of the dict does not fit in 64 bits")
    status, (h2,) = lib.call("big", 10)
    assert status == 0 and lib.items("dict_i64_i64", h2) == [(7, 1024), (1024, 7)]
    ns.release(h2)
    ns.release(h)


def test_a_null_handle_is_a_type_error_not_a_crash(dlib):
    lib = dlib.lib
    status, _ = lib.call("total", None)
    assert status == -1 and lib.error() == ("TypeError", "argument 'd' is a null dict handle")
    for kind, ns in lib.dicts.items():
        out = ns.vt()
        pos = ctypes.c_int64(0)
        k = ctypes.c_int64()
        null = ("TypeError", "argument 'd' is a null dict handle")
        assert ns.set(None, 1, 1) == -1 and lib.error() == null
        assert ns.get(None, 1, ctypes.byref(out)) == -1 and lib.error() == null
        assert ns.contains(None, 1) == -1 and lib.error() == null
        assert ns.next(None, ctypes.byref(pos), ctypes.byref(k), ctypes.byref(out)) == -1 and lib.error() == null
        assert ns.len(None) == 0
        ns.retain(None)
        ns.release(None)


def test_new_clears_the_error_record_and_set_does_not_need_it(dlib):
    lib = dlib.lib
    lib.call("lookup", lib.new("dict_i64_i64", []), 1)
    assert lib.error() is not None
    h = lib.dicts["dict_i64_i64"].new()
    assert lib.error() is None and lib.rc(h) == 1 and lib.dicts["dict_i64_i64"].len(h) == 0
    lib.dicts["dict_i64_i64"].release(h)


def test_several_threads_read_one_shared_dict_at_once(dlib):
    lib, ref = dlib.lib, dlib.ref
    items = [(i * 7919 % 1000, i) for i in range(500)]
    h = lib.new("dict_i64_i64", items)
    want = (ref.total(dict(items)), ref.via_helper(dict(items)))
    bad = []

    def work():
        for _ in range(200):
            for name, w in zip(("total", "via_helper"), want):
                status, (r,) = lib.call(name, h)
                if status != 0 or r != w:
                    bad.append((name, status, r))

    ts = [threading.Thread(target=work) for _ in range(6)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    assert bad == [] and lib.rc(h) == 1
    lib.dicts["dict_i64_i64"].release(h)


# --- refusals: no compiler needed ------------------------------------------------------------------------------

WRITES = {
    "store": "d[k] = 1",
    "augmented": "d[k] += 1",
    "delete": "del d[k]",
    "pop": "d.pop(k)",
    "popitem": "d.popitem()",
    "setdefault": "d.setdefault(k, 0)",
    "clear": "d.clear()",
    "update": "d.update({k: 1})",
    "alias": "e = d\n    e[k] = 1",
    "in_a_list": "ds = [d]\n    ds[0][k] = 1",
    "through_a_callee": "helper(d, k)",
    "returned_by_a_callee": "e = same(d)\n    e[k] = 1",
}


@pytest.mark.parametrize("name", sorted(WRITES))
def test_an_export_that_writes_its_dict_parameter_is_refused(tmp_path, name):
    src = write(tmp_path, f'''
def helper(e: dict[int, int], k: int) -> None:
    e[k] = 2


def same(e: dict[int, int]) -> dict[int, int]:
    return e


def f(d: dict[int, int], k: int) -> None:
    {WRITES[name]}
''')
    with pytest.raises(aot.AotError) as e:
        aot.generate_library(src, exports=["f"], prefix="w")
    rules = [f.rule for f in e.value.findings]
    assert rules == ["aot/library-dict-write"], [aot.format_finding(f) for f in e.value.findings]
    assert "`f`" in e.value.findings[0].message and "`d`" in e.value.findings[0].message


def test_a_callee_that_only_reads_the_dict_is_not_a_write(tmp_path):
    src = write(tmp_path, '''
def helper(e: dict[int, int], k: int) -> int:
    return e.get(k, 0) + len(e)


def f(d: dict[int, int], k: int) -> int:
    return helper(d, k)
''')
    _c, _h, d = aot.generate_library(src, exports=["f"], prefix="w")
    assert [p["type"] for p in d["functions"][0]["params"]] == ["dict_i64_i64", "i64"]


def test_a_view_of_the_dict_bound_to_a_name_is_not_a_write(tmp_path):
    """`ks = d.keys()` holds the parameter's dict, but a view name is only read (len, `in`, loops, truth, the repr)."""
    src = write(tmp_path, '''
def f(d: dict[int, int], k: int) -> int:
    ks = d.keys()
    vs = d.values()
    its = d.items()
    n = len(ks)
    for v in vs:
        n += v
    for a, b in its:
        n += a * b
    if k in ks and ks:
        n += 1
    return n
''')
    _c, _h, d = aot.generate_library(src, exports=["f"], prefix="w")
    assert [p["type"] for p in d["functions"][0]["params"]] == ["dict_i64_i64", "i64"]


OUTSIDE = {
    "str_key": "d: dict[str, int]) -> int:\n    return len(d)",
    "str_value": "d: dict[int, str]) -> int:\n    return len(d)",
    "bool_key": "d: dict[bool, int]) -> int:\n    return len(d)",
    "dict_value": "d: dict[int, dict[int, int]]) -> int:\n    return len(d)",
    "set": "d: set[int]) -> int:\n    return len(d)",
    "tuple_key": "d: dict[tuple[int, int], int]) -> int:\n    return len(d)",
}


@pytest.mark.parametrize("name", sorted(OUTSIDE))
def test_a_dict_kind_outside_the_boundary_is_refused_naming_it(tmp_path, name):
    src = write(tmp_path, f"def f({OUTSIDE[name]}\n")
    with pytest.raises(aot.AotError) as e:
        aot.generate_library(src, exports=["f"], prefix="o")
    rules = {f.rule for f in e.value.findings}
    assert rules <= {"aot/export", "aot/library-str"} and rules, [aot.format_finding(f) for f in e.value.findings]
