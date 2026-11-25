"""AOT library output (SPEC N-17, #455): `python -m typedpython aot-lib`.

A single-module program with no entry is compiled to a C-ABI shared library with a header and a JSON
description. The consumer here is `ctypes` (standard library): every exported function is called through the
C ABI and compared with the SAME source imported as a plain Python module under `sys.executable` (no
`@compiled`, plain CPython): results (floats by their bits), mutations of list arguments, returned lists and
tuples, aliasing, list identity and reference counts, and for a failure the exception class and message that
`<prefix>_last_error` gives against what CPython raises. Two tests call the library from several threads at once.

The differential tests need a C compiler (skipped without one) and CPython 3.14 or later (the messages of 3.14
and 3.15). The rejection tests and the text tests of the generated C, header and description need no compiler
(they need Pyrefly, like test_aot_build.py).

Libraries built here (each build runs the type inferencer and the C compiler, which is what made the windows job
slow): THREE. One, module-scoped, with every exported function of the test source (`built`: its ints are all proved
inside 64 bits, so it is the library as it was before #462); one with lists of big ints (`biglib`, #465: its signatures
use the `int_list` kind of ABI version 2; it is compiled with the test-only live counter of big objects, which the
reference-balance tests read; every other library here is compiled as a user would); one small one for the command line (`tiny`, built by
`python -m typedpython aot-lib`), which also holds the functions whose ints are NOT proved inside 64 bits (#462): an
int result outside 64 bits fails with an ABI-level OverflowError, int parameters arrive as int64 at its extremes, a
tuple has a result that is outside 64 bits. The paths the build function and the command line return are the paths
used, no file name is derived here.

Under the CI sanitizer job (TP_SANITIZE=1) the library is built with ASan and UBSan (`aot/cc.py`), and the
interpreter that loads it has the ASan runtime preloaded (tools/sanitizers.py), so a use after free, a double
release or an out-of-bounds access in the library is reported. What that job does not see: a leak (the
interpreter's own leak checking is off), so a list the library forgot to release is not reported by it. The
balance is checked another way: the reference count of a handle is the first 8 bytes of the handle (the
16-byte header the design note names), and the tests read it after every call.

Everything here is an agent proposal, not owner-confirmed.
"""
import contextlib
import ctypes
import importlib.util
import io
import json
import os
import random
import re
import shutil
import struct
import sys
import textwrap
import threading
from pathlib import Path
from types import SimpleNamespace

import pytest

from typedpython import aot, cbuild, ir
from typedpython.__main__ import main
from typedpython.aot import cc, library
from aot_text_check import cpython_api_uses
from typedpython.ir import Type

HEADER = cc.RUNTIME_DIR / "tp_aot.h"
BENCH = Path(__file__).resolve().parents[4] / "benchmarks" / "typedpython" / "py"
PREFIX = "kit"


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


# --- the module under test -------------------------------------------------------------------------
#
# nbody.py as it is (its kernels offset_momentum, advance and energy are exported; its `main` reads
# `sys.argv` and is not reachable from any export, so it is not compiled), then the functions below. The module
# ends in an `if __name__ == "__main__":` block that holds a list comprehension: a library does not compile it,
# and CPython does not run it on import.

NBODY = (BENCH / "nbody.py").read_text(encoding="utf-8")

SOURCE_TAIL = '''
LIMIT: int = 100
SCALE: float = 0.5


def rem(a: int, b: int) -> int:
    return a % b


def third(a: int) -> int:
    return a // 3


def mix(a: int, x: float, flag: bool) -> float:
    if flag:
        return x * float(a)
    return x / float(a)


def fdiv(x: float, y: float) -> float:
    return x / y


def ffloor(x: float, y: float) -> float:
    return x // y


def fmod(x: float, y: float) -> float:
    return x % y


def root(x: float) -> float:
    return math.sqrt(x)


def positive(x: float) -> bool:
    return x > 0.0


def invert(flag: bool) -> bool:
    return not flag


def clamp_int(a: int) -> int:
    if a > LIMIT:
        return LIMIT
    return a


def get_f(v: list[float], i: int) -> float:
    return v[i]


def set_f(v: list[float], i: int, x: float) -> None:
    v[i] = x


def get_i(w: list[int], i: int) -> int:
    return w[i]


def set_i(w: list[int], i: int, x: int) -> None:
    w[i] = x


def scale_in_place(v: list[float], k: float) -> None:
    for i in range(len(v)):
        v[i] = v[i] * k * SCALE


def alias_effect(a: list[float], b: list[float]) -> None:
    a[0] = b[0] + 1.0
    b[0] = a[0] * 2.0


def squares(n: int) -> list[float]:
    r = [0.0] * n
    for i in range(n):
        r[i] = float(i) * float(i)
    return r


def count_up(n: int) -> list[int]:
    return list(range(n))


def same_list(v: list[float]) -> list[float]:
    return v


def copy_list(v: list[float]) -> list[float]:
    return v[:]


def reverse_in_place(w: list[int]) -> None:
    n = len(w)
    for i in range(n // 2):
        t = w[i]
        w[i] = w[n - 1 - i]
        w[n - 1 - i] = t


def largest(w: list[int]) -> int:
    m = w[0]
    for i in range(len(w)):
        m = max(m, w[i])
    return m


def stats(v: list[float]) -> tuple[float, float, int]:
    s = 0.0
    for i in range(len(v)):
        s += v[i]
    return s, s / float(len(v)), len(v)


def split7(a: int) -> tuple[int, int]:
    return a // 7, a % 7


def length_of(w: list[int]) -> int:
    return len(w)


def norm(v: list[float]) -> float:
    s = 0.0
    for i in range(len(v)):
        s += v[i] * v[i]
    return math.sqrt(s)


def normalise(v: list[float]) -> float:
    n = norm(v)
    scale_in_place(v, 1.0 / n)
    return n


def cube(a: int) -> int:
    return a * a * a


def uses_cube() -> int:
    return cube(3)


if __name__ == "__main__":
    nothing = [i for i in range(3)]
    print(nothing)
'''

SOURCE = NBODY + "\n\n" + SOURCE_TAIL

EXPORTS = ["rem", "third", "mix", "fdiv", "ffloor", "fmod", "root", "positive", "invert", "clamp_int",
           "get_f", "set_f", "get_i", "set_i", "scale_in_place", "alias_effect", "squares", "count_up",
           "same_list", "copy_list", "reverse_in_place", "largest", "stats", "split7", "length_of", "normalise",
           "offset_momentum", "advance", "energy"]

INF = float("inf")
NAN = float("nan")
I64_MIN, I64_MAX = -2**63, 2**63 - 1


# --- the consumer ----------------------------------------------------------------------------------

ARG_CT = {"i64": ctypes.c_int64, "f64": ctypes.c_double, "bool": ctypes.c_uint8,
          "f64_list": ctypes.c_void_p, "i64_list": ctypes.c_void_p, "int_list": ctypes.c_void_p}
LIST_KIND = {"f64_list": "f64", "i64_list": "i64"}
ELEM_CT = {"f64": ctypes.c_double, "i64": ctypes.c_int64}


def bits(x: float) -> bytes:
    return struct.pack("<d", x)


class Lib:
    """The library through ctypes, with every signature taken from its description file."""

    def __init__(self, files):
        self.files = files
        self.desc = json.loads(files.description.read_text(encoding="utf-8"))
        self.cdll = ctypes.CDLL(str(files.library))
        self.fn = {}
        for f in self.desc["functions"]:
            c = getattr(self.cdll, f["symbol"])
            c.restype = ctypes.c_int
            c.argtypes = ([ARG_CT[p["type"]] for p in f["params"]]
                          + [ctypes.POINTER(ARG_CT[t]) for t in f["returns"]])
            self.fn[f["name"]] = (c, f)
        self.lists = {}
        self.il = None
        for t, syms in self.desc["lists"].items():
            if t == "int_list":                  # element access through functions (there is no `data`)
                il = SimpleNamespace(new=getattr(self.cdll, syms["new"]), len=getattr(self.cdll, syms["len"]),
                                     get=getattr(self.cdll, syms["get"]), set=getattr(self.cdll, syms["set"]),
                                     retain=getattr(self.cdll, syms["retain"]), release=getattr(self.cdll, syms["release"]))
                il.new.restype, il.new.argtypes = ctypes.c_void_p, [ctypes.c_int64]
                il.len.restype, il.len.argtypes = ctypes.c_int64, [ctypes.c_void_p]
                il.get.restype, il.get.argtypes = ctypes.c_int, [ctypes.c_void_p, ctypes.c_int64,
                                                                  ctypes.POINTER(ctypes.c_int64)]
                il.set.restype, il.set.argtypes = ctypes.c_int, [ctypes.c_void_p, ctypes.c_int64, ctypes.c_int64]
                il.retain.restype, il.retain.argtypes = None, [ctypes.c_void_p]
                il.release.restype, il.release.argtypes = None, [ctypes.c_void_p]
                self.il = il
                continue
            ns = SimpleNamespace(elem=ELEM_CT[syms["element"]])
            ns.new = getattr(self.cdll, syms["new"])
            ns.new.restype, ns.new.argtypes = ctypes.c_void_p, [ctypes.c_int64]
            ns.len = getattr(self.cdll, syms["len"])
            ns.len.restype, ns.len.argtypes = ctypes.c_int64, [ctypes.c_void_p]
            ns.data = getattr(self.cdll, syms["data"])
            ns.data.restype, ns.data.argtypes = ctypes.c_void_p, [ctypes.c_void_p]
            ns.retain = getattr(self.cdll, syms["retain"])
            ns.retain.restype, ns.retain.argtypes = None, [ctypes.c_void_p]
            ns.release = getattr(self.cdll, syms["release"])
            ns.release.restype, ns.release.argtypes = None, [ctypes.c_void_p]
            self.lists[syms["element"]] = ns
        rt = self.desc["runtime"]
        self.init = getattr(self.cdll, rt["init"])
        self.init.restype, self.init.argtypes = ctypes.c_int, []
        self.shutdown = getattr(self.cdll, rt["shutdown"])
        self.shutdown.restype, self.shutdown.argtypes = None, []
        self.last = getattr(self.cdll, rt["last_error"])
        self.last.restype = ctypes.c_int
        self.last.argtypes = [ctypes.POINTER(ctypes.c_char_p), ctypes.POINTER(ctypes.c_char_p)]

    # -- lists
    def new_list(self, kind, values):
        ns = self.lists[kind]
        h = ns.new(len(values))
        assert h is not None
        if values:
            arr = (ns.elem * len(values)).from_address(ns.data(h))
            for i, v in enumerate(values):
                arr[i] = v
        return h

    def read_list(self, kind, h):
        ns = self.lists[kind]
        n = ns.len(h)
        if n == 0:
            return []
        return list((ns.elem * n).from_address(ns.data(h)))

    @staticmethod
    def rc(h):
        """The reference count: the first 8 bytes of a handle (the 16-byte header of design note 6.2). The
        library has no counter of its own to ask (nothing but the contract is exported), so the tests read it."""
        return ctypes.c_int64.from_address(h).value

    def release(self, kind, h):
        self.lists[kind].release(h)

    # -- big-capable lists (ABI version 2): element access through functions
    def get(self, h, i):
        """`(status, value)` of `int_list_get`; the value is a sentinel that must stay when the call fails."""
        out = ctypes.c_int64(-12345)
        return self.il.get(h, i, ctypes.byref(out)), out.value

    def new_int_list(self, values):
        h = self.il.new(len(values))
        assert h is not None
        for i, v in enumerate(values):
            assert self.il.set(h, i, v) == 0
        return h

    # -- calls and errors
    def raw(self, name, *args):
        """(status, the values of the out pointers); a list out is a handle (an int) or None."""
        c, f = self.fn[name]
        outs = [ARG_CT[t]() for t in f["returns"]]
        status = c(*args, *[ctypes.byref(o) for o in outs])
        return status, [o.value for o in outs]

    def error(self):
        """(class name, message) of the calling thread's last failure, or None when `last_error` says 1."""
        cn, msg = ctypes.c_char_p(), ctypes.c_char_p()
        r = self.last(ctypes.byref(cn), ctypes.byref(msg))
        if r != 0:
            assert r == 1
            return None
        return cn.value.decode("utf-8"), msg.value.decode("utf-8")


def reference(path: Path, name="kit_ref"):
    spec = importlib.util.spec_from_file_location(name, str(path))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="module")
def built(tmp_path_factory):
    """ONE library with every export of SOURCE, and the same file imported as a plain Python module."""
    need_cc()
    tmp = tmp_path_factory.mktemp("lib")
    src = tmp / "kit_mod.py"
    src.write_text(SOURCE, encoding="utf-8")
    files = aot.build_library(src, tmp / "out", exports=EXPORTS, prefix=PREFIX, emit_c=tmp / "kit.c")
    return SimpleNamespace(files=files, lib=Lib(files), ref=reference(src), src=src, c_file=tmp / "kit.c")


@pytest.fixture
def lib(built):
    return built.lib


@pytest.fixture
def ref(built):
    return built.ref


def norm_value(t, v):
    """A result in a form `==` compares exactly: a float by its bits, an int, a bool."""
    if t == "f64":
        assert type(v) is float, v
        return ("f64", bits(v))
    if t == "i64":
        assert type(v) is int, v
        return ("i64", v)
    assert t == "bool" and type(v) is bool, (t, v)
    return ("bool", v)


def norm_list(kind, values):
    return [bits(x) for x in values] if kind == "f64" else list(values)


def run_row(lib, ref, name, args):
    """Call `name` through the library and through the module on equal arguments and compare everything; returns
    the reference's outcome ("ok", value) or ("err", class, message)."""
    _, f = lib.fn[name]
    ptypes = [p["type"] for p in f["params"]]
    rtypes = f["returns"]
    clones: dict[int, list] = {}
    ref_args = []
    for a, t in zip(args, ptypes):
        if t in LIST_KIND:
            clones.setdefault(id(a), list(a))
            ref_args.append(clones[id(a)])
        else:
            ref_args.append(a)
    try:
        outcome = ("ok", getattr(ref, name)(*ref_args))
    except Exception as e:      # noqa: BLE001 - the class and the message are what is compared
        outcome = ("err", type(e).__name__, str(e))

    handles: dict[int, int] = {}
    kinds: dict[int, str] = {}
    lib_args = []
    for a, t in zip(args, ptypes):
        if t in LIST_KIND:
            if id(a) not in handles:
                handles[id(a)] = lib.new_list(LIST_KIND[t], a)
                kinds[id(a)] = LIST_KIND[t]
            lib_args.append(handles[id(a)])
        else:
            lib_args.append(int(a) if t == "bool" else a)
    status, outs = lib.raw(name, *lib_args)

    if outcome[0] == "err":
        assert status == -1, f"{name}{args!r}: CPython raised {outcome[1:]} and the library returned {status}"
        assert lib.error() == outcome[1:], f"{name}{args!r}"
        assert all(o is None for o, t in zip(outs, rtypes) if t in LIST_KIND)      # a list out is NULL on failure
    else:
        assert status == 0, f"{name}{args!r}: the library failed ({lib.error()})"
        assert lib.error() is None
        value = outcome[1]
        want = [value] if len(rtypes) == 1 else (list(value) if rtypes else [])
        if not rtypes:
            assert value is None
        assert len(outs) == len(want)
        for t, out, w in zip(rtypes, outs, want):
            if t in LIST_KIND:
                kind = LIST_KIND[t]
                assert out is not None and isinstance(w, list)
                assert norm_list(kind, lib.read_list(kind, out)) == norm_list(kind, w), f"{name}{args!r}"
                same = [k for k, c in clones.items() if c is w]
                if same:                                   # the list this function returned IS its argument
                    assert out == handles[same[0]], "a returned argument is the same handle"
                    assert lib.rc(out) == 2, "the returned argument is retained once"
                else:
                    assert out not in handles.values()
                    assert lib.rc(out) == 1, "a new list is owned by the caller alone"
                lib.release(kind, out)
            elif t == "bool":
                assert out in (0, 1)
                assert norm_value(t, bool(out)) == norm_value(t, w), f"{name}{args!r}"
            else:
                assert norm_value(t, out) == norm_value(t, w), f"{name}{args!r}"
    for key, h in handles.items():
        kind = kinds[key]
        assert norm_list(kind, lib.read_list(kind, h)) == norm_list(kind, clones[key]), f"{name}{args!r}: the list"
        assert lib.rc(h) == 1, "arguments are borrowed: the count is what it was"
        lib.release(kind, h)
    return outcome


# --- scalars, lists, tuples -----------------------------------------------------------------------

SAME = [1.0, 2.0, 3.0]

ROWS = [
    ("rem", (7, 3)), ("rem", (-7, 3)), ("rem", (7, -3)), ("rem", (I64_MIN, -1)), ("rem", (I64_MAX, 2)),
    ("third", (10,)), ("third", (-10,)), ("third", (I64_MIN,)), ("third", (I64_MAX,)),
    ("mix", (3, 1.5, True)), ("mix", (3, 1.5, False)), ("mix", (-7, -0.0, False)), ("mix", (2**62, 0.1, True)),
    ("mix", (5, INF, True)), ("mix", (5, -0.0, True)),
    ("fdiv", (1.0, 3.0)), ("fdiv", (-0.0, 5.0)), ("fdiv", (INF, 2.0)), ("fdiv", (NAN, 2.0)), ("fdiv", (5e-324, 3.0)),
    ("ffloor", (7.5, 2.0)), ("ffloor", (-7.5, 2.0)), ("ffloor", (7.5, -2.0)), ("ffloor", (-0.0, 3.0)),
    ("fmod", (7.5, 2.0)), ("fmod", (-7.5, 2.0)), ("fmod", (7.5, -2.0)), ("fmod", (0.0, -2.0)),
    ("root", (2.0,)), ("root", (0.0,)), ("root", (-0.0,)), ("root", (INF,)), ("root", (NAN,)),
    ("positive", (1.0,)), ("positive", (-0.0,)), ("positive", (NAN,)), ("positive", (-INF,)),
    ("invert", (True,)), ("invert", (False,)),
    ("clamp_int", (5,)), ("clamp_int", (500,)), ("clamp_int", (I64_MIN,)), ("clamp_int", (I64_MAX,)),
    # lists: reads and stores, a negative index, a store with no result
    ("get_f", ([1.5, -0.0, 3.25], 1)), ("get_f", ([1.5, -0.0, 3.25], -1)), ("get_f", ([1.5, INF], 1)),
    ("set_f", ([1.0, 2.0, 3.0], 1, -0.0)), ("set_f", ([1.0, 2.0, 3.0], -3, 9.5)),
    ("get_i", ([5, -6, I64_MAX], 2)), ("get_i", ([5, -6, I64_MIN], -1)),
    ("set_i", ([5, -6, 7], 0, I64_MIN)), ("set_i", ([5, -6, 7], -1, I64_MAX)),
    ("scale_in_place", ([1.0, 2.0, -0.0, 4.5], 3.0)), ("scale_in_place", ([], 3.0)),
    # aliasing: the same list for two parameters, and two lists
    ("alias_effect", (SAME, SAME)), ("alias_effect", ([1.0, 2.0], [10.0, 20.0, 30.0])),
    # a returned new list, a returned argument, a returned copy
    ("squares", (0,)), ("squares", (5,)), ("squares", (-3,)), ("squares", (1,)),
    ("count_up", (0,)), ("count_up", (6,)), ("count_up", (-2,)),
    ("same_list", ([1.0, 2.0],)), ("same_list", ([],)),
    ("copy_list", ([1.0, -0.0, 2.5],)), ("copy_list", ([],)),
    ("reverse_in_place", ([1, 2, 3, 4, 5],)), ("reverse_in_place", ([1, 2, 3, 4],)), ("reverse_in_place", ([],)),
    ("largest", ([3, 9, -2],)), ("largest", ([I64_MIN, I64_MIN],)),
    ("length_of", ([],)), ("length_of", ([1, 2, 3],)),
    # tuples
    ("stats", ([1.0, 2.0, 4.5],)), ("stats", ([-0.0],)),
    ("split7", (-15,)), ("split7", (15,)), ("split7", (I64_MIN,)), ("split7", (I64_MAX,)),
    # a function that calls others of the module (norm, scale_in_place), unreachable from nothing else
    ("normalise", ([3.0, 4.0],)), ("normalise", ([1.0, 2.0, 3.0],)),
]

FAIL_ROWS = [
    ("rem", (5, 0)), ("mix", (0, 1.0, False)), ("fdiv", (1.0, 0.0)), ("fdiv", (1.0, -0.0)), ("ffloor", (1.0, 0.0)),
    ("fmod", (1.0, 0.0)), ("root", (-1.0,)), ("root", (-INF,)),
    ("get_f", ([1.0], 1)), ("get_f", ([1.0], -2)), ("get_f", ([], 0)),
    ("set_f", ([1.0], 5, 1.0)), ("set_f", ([1.0, 2.0], -3, 1.0)),
    ("get_i", ([1], 1)), ("set_i", ([1, 2], 2, 0)), ("largest", ([],)), ("stats", ([],)), ("normalise", ([0.0],)),
]


def row_id(row):
    name, args = row
    return f"{name}-" + "_".join("L" if isinstance(a, list) else repr(a) for a in args)


@pytest.mark.parametrize("name,args", ROWS, ids=[f"{i}-{row_id(r)}" for i, r in enumerate(ROWS)])
def test_function_matches_cpython(lib, ref, name, args):
    outcome = run_row(lib, ref, name, args)
    assert outcome[0] == "ok", f"{name}{args!r} is a failure row: {outcome}"


@pytest.mark.parametrize("name,args", FAIL_ROWS, ids=[f"{i}-{row_id(r)}" for i, r in enumerate(FAIL_ROWS)])
def test_failure_matches_cpython_class_and_message(lib, ref, name, args):
    """-1, and `last_error` gives exactly the class name and `str(exception)` CPython raises for the same call
    (and the lists are in the state CPython leaves them in); the next successful call clears the record."""
    outcome = run_row(lib, ref, name, args)
    assert outcome[0] == "err", f"{name}{args!r} did not raise in CPython"
    status, outs = lib.raw("rem", 7, 3)
    assert status == 0 and outs == [1]
    assert lib.error() is None


def test_the_messages_are_the_ones_cpython_raises(lib, ref):
    """Pin three of them literally, so that a change of the reference interpreter shows here."""
    want = [("rem", (5, 0), ("ZeroDivisionError", "division by zero")),         # pairs, not a dict: the
            ("root", (-1.0,), ("ValueError", "expected a nonnegative input, got -1.0")),   # arguments hold lists
            ("get_f", ([1.0], 1), ("IndexError", "list index out of range")),
            ("set_f", ([1.0], 5, 1.0), ("IndexError", "list assignment index out of range"))]
    for name, args, expected in want:
        assert run_row(lib, ref, name, args)[1:] == expected, (name, args)


def test_a_bool_argument_other_than_0_or_1_is_true(lib, ref):
    for flag in (2, 255, -1 & 0xFF):
        status, outs = lib.raw("mix", 3, 1.5, flag)
        assert status == 0
        assert bits(outs[0]) == bits(ref.mix(3, 1.5, True))
    status, outs = lib.raw("invert", 7)
    assert (status, outs) == (0, [0])


def test_a_null_list_handle_is_a_type_error_not_a_crash(lib):
    h = lib.new_list("f64", [1.0, 2.0])
    status, outs = lib.raw("get_f", None, 0)
    assert status == -1 and lib.error() == ("TypeError", "argument 'v' is a null list handle")
    status, outs = lib.raw("alias_effect", h, None)                      # the second argument is the NULL one
    assert status == -1 and lib.error() == ("TypeError", "argument 'b' is a null list handle")
    status, outs = lib.raw("same_list", None)
    assert status == -1 and outs == [None] and lib.error() == ("TypeError", "argument 'v' is a null list handle")
    assert lib.read_list("f64", h) == [1.0, 2.0] and lib.rc(h) == 1       # nothing was touched
    status, _ = lib.raw("rem", 1, 1)
    assert status == 0 and lib.error() is None
    lib.release("f64", h)


def test_init_and_shutdown_exist_and_init_may_be_called_again(lib):
    assert lib.init() == 0 and lib.init() == 0
    results = []
    ts = [threading.Thread(target=lambda: results.append(lib.init())) for _ in range(4)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    assert results == [0, 0, 0, 0]
    lib.shutdown()                                   # nothing to free in this tier: the library still works
    assert lib.init() == 0 and lib.raw("rem", 7, 3) == (0, [1])


def test_last_error_with_no_failure_returns_1(lib):
    done = []

    def fresh_thread():
        done.append(lib.error())          # a thread that made no call into the library

    t = threading.Thread(target=fresh_thread)
    t.start()
    t.join()
    assert done == [None]


# --- the list functions -----------------------------------------------------------------------------

@pytest.mark.parametrize("kind", ["f64", "i64"])
def test_list_new_len_data(lib, kind):
    zero = 0.0 if kind == "f64" else 0
    h0 = lib.lists[kind].new(0)
    assert h0 is not None and lib.lists[kind].len(h0) == 0 and lib.read_list(kind, h0) == []
    h5 = lib.lists[kind].new(5)
    assert lib.lists[kind].len(h5) == 5 and lib.read_list(kind, h5) == [zero] * 5
    arr = (ELEM_CT[kind] * 5).from_address(lib.lists[kind].data(h5))
    arr[4] = 2.5 if kind == "f64" else 7
    assert lib.read_list(kind, h5)[4] == (2.5 if kind == "f64" else 7)
    hn = lib.lists[kind].new(-4)                        # `[0.0] * -4 == []`
    assert hn is not None and lib.lists[kind].len(hn) == 0
    for h in (h0, h5, hn):
        assert lib.rc(h) == 1
        lib.release(kind, h)


@pytest.mark.parametrize("kind", ["f64", "i64"])
def test_retain_then_two_releases(lib, kind):
    h = lib.lists[kind].new(3)
    assert lib.rc(h) == 1
    lib.lists[kind].retain(h)
    assert lib.rc(h) == 2
    lib.release(kind, h)
    assert lib.rc(h) == 1 and lib.lists[kind].len(h) == 3          # still alive after the first release
    lib.release(kind, h)                                           # the last owner frees (ASan sees a double free)
    lib.lists[kind].release(None)                                  # NULL is a no-op
    assert lib.lists[kind].len(None) == 0 and lib.lists[kind].data(None) is None


def test_list_new_failure_records_a_memory_error_like_cpython(lib):
    with pytest.raises(MemoryError) as raised:
        [0.0] * 2**62
    h = lib.lists["f64"].new(2**62)
    assert h is None
    assert lib.error() == ("MemoryError", str(raised.value))
    h = lib.lists["f64"].new(1)                                     # a successful call clears the record
    assert lib.error() is None
    lib.release("f64", h)


def test_a_returned_argument_keeps_its_identity_and_the_balance(lib):
    h = lib.new_list("f64", [1.0, 2.0])
    status, [out] = lib.raw("same_list", h)
    assert status == 0 and out == h and lib.rc(h) == 2
    lib.release("f64", out)
    assert lib.rc(h) == 1 and lib.read_list("f64", h) == [1.0, 2.0]
    lib.release("f64", h)


# --- the three nbody kernels -----------------------------------------------------------------------

def test_nbody_kernels_match_cpython_bit_for_bit(lib, ref):
    names = ("x", "y", "z", "vx", "vy", "vz", "mass")
    data = {n: getattr(ref, f"make_{n}")() for n in names}                       # nbody's own initial data, in CPython
    py = {n: list(v) for n, v in data.items()}
    hs = {n: lib.new_list("f64", v) for n, v in data.items()}

    def args(table, *lead):
        return [*lead, *(table[n] for n in names)]

    def same_state(label):
        for n in names:
            assert [bits(a) for a in lib.read_list("f64", hs[n])] == [bits(a) for a in py[n]], f"{label}: {n}"

    ref.offset_momentum(py["vx"], py["vy"], py["vz"], py["mass"])
    status, _ = lib.raw("offset_momentum", hs["vx"], hs["vy"], hs["vz"], hs["mass"])
    assert status == 0
    same_state("offset_momentum")

    e_py = ref.energy(*args(py))
    status, [e_lib] = lib.raw("energy", *args(hs))
    assert status == 0 and bits(e_lib) == bits(e_py)
    for step in range(3):                                                         # a few advance steps, compared after each
        ref.advance(0.01, 25, *args(py))
        status, _ = lib.raw("advance", 0.01, 25, *args(hs))
        assert status == 0
        same_state(f"advance {step}")
        e_py = ref.energy(*args(py))
        status, [e_lib] = lib.raw("energy", *args(hs))
        assert status == 0 and bits(e_lib) == bits(e_py), f"energy after step {step}"
    for n in names:
        assert lib.rc(hs[n]) == 1
        lib.release("f64", hs[n])


# --- threads --------------------------------------------------------------------------------------

def test_several_threads_call_the_library_at_once(lib, ref):
    """Six threads, started together: two compute on lists of their own, two share ONE handle (reads, and
    retain and release in pairs: the count is atomic), two exercise the thread-local error record (one fails
    all the time, one never does). Every result is compared with the single-threaded reference; the shared list
    keeps its contents and its count."""
    rounds = 300
    problems: list[str] = []
    barrier = threading.Barrier(6)
    shared_values = [1.5, 2.5, 3.5, 4.5]
    shared = lib.new_list("f64", shared_values)
    failing_list = lib.new_list("f64", [1.0])

    def guarded(body):
        def run():
            try:
                body()
            except BaseException as e:      # noqa: BLE001 - reported on the main thread
                problems.append(f"{type(e).__name__}: {e}")
        return run

    def compute(tid):
        def body():
            barrier.wait(timeout=60)
            for k in range(rounds):
                values = [float(tid + k + 1), 2.0, float(tid) + 0.5]
                expect = list(values)
                want_norm = ref.normalise(expect)
                h = lib.new_list("f64", values)
                status, [got] = lib.raw("normalise", h)
                if status != 0 or bits(got) != bits(want_norm):
                    problems.append(f"compute {tid} round {k}: status {status}, {got!r} != {want_norm!r}")
                if norm_list("f64", lib.read_list("f64", h)) != norm_list("f64", expect):
                    problems.append(f"compute {tid} round {k}: the list differs")
                lib.release("f64", h)
        return guarded(body)

    def share(tid):
        def body():
            barrier.wait(timeout=60)
            ns = lib.lists["f64"]
            for k in range(rounds):
                ns.retain(shared)
                status, [got] = lib.raw("get_f", shared, k % len(shared_values))
                if status != 0 or bits(got) != bits(shared_values[k % len(shared_values)]):
                    problems.append(f"share {tid} round {k}: read {got!r}")
                ns.retain(shared)
                ns.release(shared)
                ns.release(shared)
        return guarded(body)

    def failing():
        barrier.wait(timeout=60)
        for k in range(rounds):
            status, _ = lib.raw("get_f", failing_list, 99)
            err = lib.error()
            if status != -1 or err != ("IndexError", "list index out of range"):
                problems.append(f"failing round {k}: {status} {err}")

    def succeeding():
        barrier.wait(timeout=60)
        for k in range(rounds):
            status, outs = lib.raw("rem", 7 + k, 3)
            err = lib.error()
            if status != 0 or outs != [(7 + k) % 3] or err is not None:
                problems.append(f"succeeding round {k}: {status} {outs} {err}")      # another thread's failure leaked

    targets = [compute(0), compute(1), share(0), share(1), guarded(failing), guarded(succeeding)]
    threads = [threading.Thread(target=t) for t in targets]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=180)
    assert not any(t.is_alive() for t in threads), "a thread did not finish"
    assert problems == []
    assert lib.rc(shared) == 1, "every retain was matched by a release, from two threads"
    assert lib.read_list("f64", shared) == shared_values
    lib.release("f64", shared)
    lib.release("f64", failing_list)


# --- the command line -----------------------------------------------------------------------------

TINY = '''
def add_one(x: float) -> float:
    return x + 1.0


def inc(a: int) -> int:
    return a + 1


def accumulate(a: int, b: int) -> int:
    a = a + b
    return a


def big_round_trip(a: int) -> int:
    return (a * a * a) // (a * a)


def pair_big(a: int, b: int) -> tuple[int, int, float]:
    return a * b, a % 7, float(a * b)


def grow(n: int) -> int:
    r = 1
    for i in range(n):
        r = r * 3
    return r


def digit_count(a: int) -> int:
    d = 0
    a = abs(a)
    while a:
        a = a // 10
        d += 1
    return d
'''
TINY_EXPORTS = ["add_one", "inc", "accumulate", "big_round_trip", "pair_big", "grow", "digit_count"]


@pytest.fixture(scope="module")
def tiny(tmp_path_factory):
    """The small library, built through the command line once; with the same file imported as a plain module."""
    need_cc()
    tmp = tmp_path_factory.mktemp("tiny")
    src = write(tmp, TINY, "tiny")
    out = tmp / "out"
    c_file = tmp / "elsewhere" / "tiny.c"
    stdout = io.StringIO()
    with contextlib.redirect_stdout(stdout):
        status = main(["aot-lib", str(src), "--export", ",".join(TINY_EXPORTS), "--prefix", "tiny", "-o", str(out),
                       "--emit-c", str(c_file)])
    paths = [Path(line) for line in stdout.getvalue().splitlines() if line.strip()]
    lib = Lib(aot.LibraryFiles(*paths)) if status == 0 and len(paths) == 3 else None
    return SimpleNamespace(status=status, paths=paths, out=out, c_file=c_file, src=src, lib=lib,
                           ref=reference(src, "tiny_ref"))


def test_command_line_writes_exactly_the_library_the_header_and_the_description(tiny):
    assert tiny.status == 0
    paths = tiny.paths
    assert len(paths) == 3 and all(p.is_file() for p in paths), paths
    lib_path, header, description = paths
    assert lib_path.parent == tiny.out and header == tiny.out / "tiny.h" and description == tiny.out / "tiny.json"
    names = [n for n in os.listdir(tiny.out) if not n.endswith(".dSYM")]       # (a sanitized build on macOS: debug info)
    assert sorted(names) == sorted([lib_path.name, "tiny.h", "tiny.json"]), "no by-products in the output"
    assert tiny.c_file.is_file() and "TPA_EXPORT int tiny_add_one(" in tiny.c_file.read_text(encoding="utf-8")
    lib = tiny.lib
    assert lib.desc["library"] == lib_path.name
    assert lib.raw("add_one", 1.5) == (0, [2.5])
    assert lib.init() == 0


# --- ints that are not proved inside 64 bits (#462) ----------------------------------------------------------------
#
# Inside the library the source language's integer rules hold exactly (a big integer is a value like any other); at the
# boundary an `int` is int64. An int parameter arrives as int64 (and may be held as a big-integer-capable value inside:
# `accumulate` assigns its parameter). An int RESULT, also an element of a tuple, goes back through a fit check, and one
# that does not fit int64 fails the call with -1 and an OverflowError whose message says that the int result of
# that function does not fit in 64 bits: an ABI-level rule with no counterpart in the source language (the reference
# interpreter has no such limit, which the tests show next to the library's answer). Design decision D1 (a), leader
# 2026-11-16, not owner-confirmed; the boundary rule is an agent proposal, not owner-confirmed.

TINY_ROWS = [
    ("inc", (5,)), ("inc", (-1,)), ("inc", (I64_MIN,)), ("inc", (I64_MAX - 1,)),
    ("accumulate", (3, 4)), ("accumulate", (I64_MIN, 5)), ("accumulate", (I64_MAX, I64_MIN)),
    ("accumulate", (I64_MIN, I64_MAX)), ("accumulate", (I64_MAX, -I64_MAX)), ("accumulate", (I64_MIN, 0)),
    ("accumulate", (2**62, 2**62 - 1)), ("accumulate", (-(2**62), -(2**62))),
    ("big_round_trip", (3037000500,)), ("big_round_trip", (-7,)), ("big_round_trip", (I64_MAX // 4,)),
    ("big_round_trip", (I64_MIN + 1,)),
    ("pair_big", (3, 4)), ("pair_big", (-3037000499, 3037000499)), ("pair_big", (I64_MIN, 1)), ("pair_big", (0, I64_MAX)),
    ("grow", (0,)), ("grow", (1,)), ("grow", (39,)),
    ("digit_count", (0,)), ("digit_count", (I64_MIN,)), ("digit_count", (I64_MAX,)), ("digit_count", (-12345,)),
]
TINY_FAIL_ROWS = [("big_round_trip", (0,))]
TINY_OVERFLOW_ROWS = [
    ("inc", (I64_MAX,)), ("accumulate", (I64_MAX, 1)), ("accumulate", (I64_MIN, -1)), ("accumulate", (I64_MIN, I64_MIN)),
    ("grow", (40,)), ("grow", (200,)), ("pair_big", (3037000500, 3037000500)), ("pair_big", (I64_MIN, -1)),
    ("pair_big", (I64_MAX, I64_MAX)),
]


@pytest.mark.parametrize("name,args", TINY_ROWS, ids=[f"{i}-{row_id(r)}" for i, r in enumerate(TINY_ROWS)])
def test_a_function_with_big_integers_inside_matches_cpython(tiny, name, args):
    outcome = run_row(tiny.lib, tiny.ref, name, args)
    assert outcome[0] == "ok", outcome


@pytest.mark.parametrize("name,args", TINY_FAIL_ROWS)
def test_a_failure_with_big_operands_matches_cpython(tiny, name, args):
    outcome = run_row(tiny.lib, tiny.ref, name, args)
    assert outcome[0] == "err" and outcome[1:] == ("ZeroDivisionError", "division by zero")


@pytest.mark.parametrize("name,args", TINY_OVERFLOW_ROWS,
                         ids=[f"{i}-{row_id(r)}" for i, r in enumerate(TINY_OVERFLOW_ROWS)])
def test_an_int_result_outside_64_bits_fails_with_the_documented_error(tiny, name, args):
    """CPython answers (the value is an int like any other); the library cannot hand it over in an int64 and says so."""
    value = getattr(tiny.ref, name)(*args)
    values = value if isinstance(value, tuple) else (value,)
    assert any(isinstance(v, int) and not I64_MIN <= v <= I64_MAX for v in values), "the premise: it does not fit"
    lib = tiny.lib
    _, f = lib.fn[name]
    c, _ = lib.fn[name]
    outs = [ARG_CT[t]() for t in f["returns"]]
    status = c(*args, *[ctypes.byref(o) for o in outs])
    assert status == -1
    assert lib.error() == ("OverflowError", f"the int result of `{name}` does not fit in 64 bits")
    assert all(o.value in (0, 0.0) for o in outs), "no out value is written on failure"
    # the record is cleared by the next successful call, and the same function with small inputs succeeds
    assert lib.raw("inc", 1) == (0, [2])
    assert lib.error() is None
    small = {"inc": (1,), "accumulate": (1, 2), "grow": (3,), "pair_big": (2, 3)}[name]
    assert lib.raw(name, *small)[0] == 0 and lib.error() is None


def test_int_parameters_at_the_extremes_are_exact_inside_and_the_header_says_so(tiny):
    lib = tiny.lib
    assert lib.raw("accumulate", I64_MIN, I64_MAX) == (0, [-1])
    assert lib.raw("accumulate", I64_MAX, I64_MIN) == (0, [-1])
    assert lib.raw("big_round_trip", 3037000500) == (0, [3037000500])        # (3037000500 ** 3 does not fit 64 bits)
    assert lib.raw("digit_count", I64_MIN) == (0, [19])
    header = tiny.paths[1].read_text(encoding="utf-8")
    assert "held as an integer of any size" in header and "A second check of this kind concerns an int RESULT" in header
    assert "does not fit in 64 bits" in header and "no counterpart in the source language" in header
    assert chr(0x2014) not in header


def test_the_library_with_a_big_integer_includes_the_runtime_and_one_without_does_not(tiny, tmp_path):
    c = tiny.c_file.read_text(encoding="utf-8")
    assert re.findall(r"#include\s*[<\"]([^>\"]+)", c) == ["tp_aot.h", "tp_aot_int.h"]
    assert "tpa_int_to_i64(r0, &v0)" in c and "does not fit in 64 bits" in c
    plain = write(tmp_path, "def add_one(x: float) -> float:\n    return x + 1.0\n", "plain")
    c2, header2, _ = aot.generate_library(plain, exports=["add_one"], prefix="p")
    assert "tp_aot_int" not in c2 and "tpa_int" not in c2
    assert "held as an integer of any size" not in header2 and "second check" not in header2
    assert "an operation the compiler could not prove stays inside" in header2


def test_strict_int_refuses_the_big_integer_functions_as_before(tmp_path):
    path = write(tmp_path, TINY, "tiny_strict")
    with pytest.raises(aot.AotError) as raised:
        aot.generate_library(path, exports=["inc", "grow"], prefix="p", strict_int=True)
    found = {(f.rule, f.name) for f in raised.value.findings}
    assert found == {("aot/int-unproven", "+"), ("aot/int-unproven", "*")}, found
    aot.generate_library(path, exports=["inc", "grow"], prefix="p")                    # accepted by default


def test_command_line_strict_int_is_a_flag_of_aot_lib(tmp_path, capsys):
    path = write(tmp_path, TINY, "tiny_cli")
    assert main(["aot-lib", str(path), "--export", "inc", "--prefix", "p", "-o", str(tmp_path / "never"),
                 "--strict-int"]) == 1
    err = capsys.readouterr().err.strip().splitlines()
    assert err and all("error aot/int-unproven:" in line for line in err)
    assert not (tmp_path / "never").exists()


def test_command_line_reports_each_finding_on_one_line_and_writes_nothing(tmp_path, capsys):
    src = write(tmp_path, TINY, "tiny")
    out = tmp_path / "never"
    assert main(["aot-lib", str(src), "--export", "nope", "--prefix", "p", "-o", str(out)]) == 1
    err = capsys.readouterr().err.strip().splitlines()
    assert err == ["tiny.py:1: error aot/export: `nope` is not a function defined at the top level of tiny.py"]
    assert not out.exists()


def test_command_line_a_missing_module_is_not_a_finding(tmp_path, capsys):
    assert main(["aot-lib", str(tmp_path / "none.py"), "--export", "f", "--prefix", "p", "-o", str(tmp_path / "o")]) == 2
    assert "is not a file" in capsys.readouterr().err


# --- rejections (no compiler needed) -----------------------------------------------------------------

REJECT_BASE = '''
import sys

K: int = 3


def f(x: float) -> float:
    return x


def takes_text(s: str) -> int:
    return 1


def gives_text(x: int) -> str:
    return "a"


def takes_nested(v: list[list[float]]) -> int:
    return 1


def gives_tuple_with_list(x: int) -> tuple[list[float], int]:
    return [1.0], x


def cube(a: int) -> int:
    return a * a * a


def calls_cube(a: int) -> int:
    return cube(a)


def argc() -> int:
    return len(sys.argv)


def calls_argc() -> int:
    return argc()


def init() -> int:
    return 1
'''


def line_of(source, text):
    """The 1-based line of the first line of `source` (as `write` dedents it) that contains `text`."""
    for i, line in enumerate(source.lstrip("\n").splitlines(), 1):
        if text in line:
            return i
    raise AssertionError(text)


def findings_of(tmp_path, source, **kw):
    path = write(tmp_path, source, "rej")
    with pytest.raises(aot.AotError) as raised:
        aot.generate_library(path, **kw)
    return {(f.rule, f.line, f.name) for f in raised.value.findings}, raised.value


def test_an_unknown_export_is_refused_by_name(tmp_path):
    found, err = findings_of(tmp_path, REJECT_BASE, exports=["nope"], prefix="p")
    assert found == {("aot/export", 1, "nope")}
    assert "`nope`" in str(err)


def test_an_export_that_is_a_constant_is_refused(tmp_path):
    found, _ = findings_of(tmp_path, REJECT_BASE, exports=["K"], prefix="p")
    assert found == {("aot/export", 1, "K")}


@pytest.mark.parametrize("prefix", ["", "1abc", "my-lib", "a b", "a.b", "naïve", "tpa", "tp_x", "__x"])
def test_a_prefix_that_is_not_a_usable_c_identifier_is_refused(tmp_path, prefix):
    found, _ = findings_of(tmp_path, REJECT_BASE, exports=["f"], prefix=prefix)
    assert {r for r, _, _ in found} == {"aot/export"}


def test_no_exports_and_a_twice_exported_function_are_refused(tmp_path):
    assert {r for r, _, _ in findings_of(tmp_path, REJECT_BASE, exports=[], prefix="p")[0]} == {"aot/export"}
    assert findings_of(tmp_path, REJECT_BASE, exports=["f", "f"], prefix="p")[0] == {("aot/export", 1, "f")}


@pytest.mark.parametrize("name", ["takes_nested", "gives_tuple_with_list"])
def test_an_unsupported_parameter_or_return_type_is_refused_naming_the_export(tmp_path, name):
    found, err = findings_of(tmp_path, REJECT_BASE, exports=[name], prefix="p")
    assert found == {("aot/export", line_of(REJECT_BASE, f"def {name}("), name)}
    assert "outside the library boundary" in str(err)


@pytest.mark.parametrize("name", ["takes_text", "gives_text"])
def test_a_str_parameter_or_result_is_refused_with_its_own_rule(tmp_path, name):
    """Since tier S1a a str is a program type, but the C-ABI string kind comes later (SPEC N-17, design 4.3.1):
    the export is refused with `aot/library-str`, naming the export."""
    found, err = findings_of(tmp_path, REJECT_BASE, exports=[name], prefix="p")
    assert found == {("aot/library-str", line_of(REJECT_BASE, f"def {name}("), name)}
    assert "no C-ABI form yet" in str(err)


def test_a_reserved_symbol_name_is_refused(tmp_path):
    found, _ = findings_of(tmp_path, REJECT_BASE, exports=["init"], prefix="p")
    assert {r for r, _, _ in found} == {"aot/export"}


def test_an_exported_function_with_a_finding_gives_the_finding_aot_build_gives(tmp_path):
    """`cube` exported: the unproved int operation, with the position and text `aot-build` reports for the same
    function in a program with an entry."""
    lib_found, _ = findings_of(tmp_path, REJECT_BASE, exports=["cube"], prefix="p", strict_int=True)
    assert {r for r, _, _ in lib_found} == {"aot/int-unproven"}
    exe = write(tmp_path, 'def cube(a: int) -> int:\n    return a * a * a\n\n\nif __name__ == "__main__":\n    print(cube(2))\n',
                "exe")
    with pytest.raises(aot.AotError) as raised:
        aot.generate(exe, strict_int=True)
    lib_src = write(tmp_path, 'def cube(a: int) -> int:\n    return a * a * a\n', "libver")
    with pytest.raises(aot.AotError) as lib_raised:
        aot.generate_library(lib_src, exports=["cube"], prefix="p", strict_int=True)
    def unproven(e):
        return {(f.line, f.rule, f.name, f.message) for f in e.findings if f.rule == "aot/int-unproven"}
    assert unproven(lib_raised.value) == unproven(raised.value) and len(unproven(raised.value)) == 2


def test_a_function_reachable_from_an_export_fails_the_library_and_one_that_is_not_does_not(tmp_path):
    found, _ = findings_of(tmp_path, REJECT_BASE, exports=["calls_cube"], prefix="p", strict_int=True)
    assert {r for r, _, _ in found} == {"aot/int-unproven"}
    c, header, description = aot.generate_library(write(tmp_path, REJECT_BASE, "ok"), exports=["f"], prefix="p")
    assert "tpf_cube" not in c and "tpf_argc" not in c and "cube" not in header     # not emitted: nothing reaches them
    assert [f["name"] for f in description["functions"]] == ["f"]


def test_library_state_a_reachable_read_of_the_argument_vector_is_refused(tmp_path):
    """Tier K has no module-level mutable state, so the only run-time reads of state the front end can lower are
    of the process's arguments (`len(sys.argv)`): a library has none, and says so."""
    for export in ("argc", "calls_argc"):
        found, err = findings_of(tmp_path, REJECT_BASE, exports=[export], prefix="p")
        assert ("aot/library-state", line_of(REJECT_BASE, "return len(sys.argv)"), "sys.argv") in found, found
        assert "sys.argv" in str(err)


def _fn(body, params=(ir.Param("x", Type.I64),), locals_=None):
    return ir.Function(name="g", params=params, returns=Type.I64, locals=locals_ or {}, body=tuple(body),
                       pure=False, may_deopt=False, source_line=7)


def test_library_state_check_on_hand_made_ir():
    """The IR the front end cannot make from tier K source (a read of a module-level name when it runs, a
    store to one), built by hand as the verifier tests do: each is an `aot/library-state` finding naming it."""
    clean = _fn([ir.Assign("y", ir.Local(Type.I64, "x")), ir.Return(ir.Local(Type.I64, "y"))], locals_={"y": Type.I64})
    assert library.state_findings(clean, "m.py") == []
    cases = {
        "reads a Global": (_fn([ir.Return(ir.Global(Type.OBJ, "COUNTER"))]), "COUNTER"),
        "reads an unbound name": (_fn([ir.Return(ir.Local(Type.I64, "COUNTER"))]), "COUNTER"),
        "assigns a module name": (_fn([ir.Assign("COUNTER", ir.Const(Type.I64, 1)), ir.Return(ir.Const(Type.I64, 0))]),
                                  "COUNTER"),
        "stores into a module list": (_fn([ir.StoreIndex("TABLE", ir.Const(Type.I64, 0), ir.Const(Type.F64, 1.0)),
                                           ir.Return(ir.Const(Type.I64, 0))]), "TABLE"),
        "argument vector": (_fn([ir.Return(ir.ArgvLen(Type.I64))]), "sys.argv"),
    }
    for label, (fn, name) in cases.items():
        found = library.state_findings(fn, "m.py")
        assert found and {f.rule for f in found} == {"aot/library-state"} and name in {f.name for f in found}, label
        assert all(f.path == "m.py" and f.function == "g" for f in found), label
    entry = ir.Function(name="g", params=(), returns=Type.I64, locals={}, body=(ir.Return(ir.Local(Type.I64, "G")),),
                        pure=True, may_deopt=False, source_line=2, entry_globals=(ir.Param("G", Type.I64),))
    assert {f.name for f in library.state_findings(entry, "m.py")} == {"G"}


def test_static_data_in_the_generated_c_is_a_finding():
    assert library.static_data_findings("static int counter = 0;\nstatic double table[4];\n", "m.py")
    ok = "static int tpf_f(int64_t v) TPA_UNUSED;\ntypedef tpa_f64_list kit_f64_list;\n#define X 1\n/* a; b = c */\n"
    assert library.static_data_findings(ok, "m.py") == []


def test_a_library_needs_no_entry_but_aot_build_still_does(tmp_path):
    no_main = write(tmp_path, TINY, "nomain")
    c, _, _ = aot.generate_library(no_main, exports=["add_one"], prefix="p")
    assert "tpf_add_one" in c
    with pytest.raises(aot.AotError) as raised:
        aot.generate(no_main)
    assert {f.rule for f in raised.value.findings} == {"aot/entry"}


def test_a_module_level_statement_is_still_refused_in_a_library(tmp_path):
    found, _ = findings_of(tmp_path, TINY + "\nfor i in range(3):\n    pass\n", exports=["add_one"], prefix="p")
    assert {r for r, _, _ in found} == {"aot/module-statement"}


# --- lists that may hold big ints (#465, ABI version 2) ---------------------------------------------------------------
#
# A `list[int]` that the compiler finds big-capable (aot/rep.py "Lists of ints": a value not proved inside 64 bits is
# stored into its group) is passed as the opaque handle `<p>_int_list`; its elements are not int64 in memory, so there is
# `_get` and `_set` and no `_data`. The consumer here uses ctypes again. Each function of BIGLIB is compared with the
# same source imported as a plain module; an element that does not fit int64 cannot be read by `_get`, which fails with the
# ABI-level OverflowError of the header, so the exact value is shown through the functions that return an int that fits
# (`diff`, `total`). The library is compiled with the live counter of big objects (`TPA_INT_COUNT_LIVE`, test-only, as the
# shim of test_aot_int.py does): the tests read it after each use to see that every reference was given back. Leader decision
# 2026-11-16 for the static classification and the new kind, not owner-confirmed; the messages are agent proposals.

BIGLIB = '''
def store_product(w: list[int], i: int, a: int, b: int) -> None:
    w[i] = a * b


def make_big(n: int, v: int) -> list[int]:
    r = [0] * n
    r[0] = v * v
    return r


def repeat_big(n: int, v: int) -> list[int]:
    return [v * v] * n


def twice(n: int, v: int) -> list[int]:
    a = repeat_big(n, v)
    b = a[:]
    b[0] = b[0] + 1
    return b


def copy_with(w: list[int], v: int) -> list[int]:
    c = w[:]
    c[0] = v * v
    return c


def bump(w: list[int], i: int) -> None:
    w[i] = w[i] + 1


def total(w: list[int]) -> int:
    t = 0
    for i in range(len(w)):
        t += w[i]
    return t


def diff(w: list[int], i: int, j: int) -> int:
    return w[i] - w[j]


def grow_and_total(n: int, v: int) -> int:
    r = make_big(n, v)
    return total(r) + diff(r, 0, 1)


def raw_set(w: list[int], i: int, x: int) -> None:
    w[i] = x


def raw_get(w: list[int], i: int) -> int:
    return w[i]


if __name__ == "__main__":
    print(total([1, 2]))
'''
BIG_PREFIX = "big"
BIG_EXPORTS = ["store_product", "make_big", "repeat_big", "twice", "copy_with", "bump", "total", "diff", "grow_and_total",
               "raw_set", "raw_get"]
ELEMENT_OVERFLOW = ("OverflowError", "the element of the list does not fit in 64 bits")


@pytest.fixture(scope="module")
def biglib(tmp_path_factory):
    """BIGLIB as a library, compiled with the test-only live counter and one more export that reads it, next to the same
    file imported as a plain module. The generated C, header and description are the ones `generate_library` wrote (the
    counter and its export are added to a copy of the C text only)."""
    need_cc()
    tmp = tmp_path_factory.mktemp("biglib")
    src = write(tmp, BIGLIB, "biglib")
    c_text, header, description = aot.generate_library(src, exports=BIG_EXPORTS, prefix=BIG_PREFIX)
    counting = ("#define TPA_INT_COUNT_LIVE 1\n" + c_text
                + f"\nTPA_EXPORT int64_t {BIG_PREFIX}_test_live(void)\n{{\n    return tpa_int_live();\n}}\n")
    out = tmp / "out"
    lib_path = cc.compile_library(counting, out, library.library_file_name(BIG_PREFIX))
    header_path, json_path = out / f"{BIG_PREFIX}.h", out / f"{BIG_PREFIX}.json"
    header_path.write_text(header, encoding="utf-8")
    json_path.write_text(json.dumps(description, indent=2) + "\n", encoding="utf-8")
    lib = Lib(aot.LibraryFiles(lib_path, header_path, json_path))
    live = getattr(lib.cdll, f"{BIG_PREFIX}_test_live")
    live.restype, live.argtypes = ctypes.c_int64, []
    return SimpleNamespace(lib=lib, ref=reference(src, "biglib_ref"), c=c_text, header=header, description=description,
                           live=live, src=src)


def balanced(biglib):
    """Every big integer the library allocated has been given back."""
    assert biglib.live() == 0, "a big integer is still held (a leaked reference)"


def error_of(exc: Exception) -> tuple[str, str]:
    return type(exc).__name__, str(exc)


def test_the_description_and_the_header_name_the_kind_a_signature_uses(biglib):
    d, h = biglib.description, biglib.header
    assert d["abi_version"] == 3 and "int_list" in library.VOCABULARY
    types = {f["name"]: ([p["type"] for p in f["params"]], f["returns"]) for f in d["functions"]}
    assert types["store_product"] == (["int_list", "i64", "i64", "i64"], [])
    assert types["make_big"] == (["i64", "i64"], ["int_list"]) and types["twice"] == (["i64", "i64"], ["int_list"])
    assert types["bump"] == (["int_list", "i64"], []) and types["total"] == (["int_list"], ["i64"])
    assert types["diff"] == (["int_list", "i64", "i64"], ["i64"])
    # the raw kind is still there: a parameter nothing big reaches, and a raw list copied into a big-capable result
    assert types["raw_set"] == (["i64_list", "i64", "i64"], []) and types["raw_get"] == (["i64_list", "i64"], ["i64"])
    assert types["copy_with"] == (["i64_list", "i64"], ["int_list"])
    assert set(d["lists"]) == {"f64_list", "i64_list", "int_list"}
    assert d["lists"]["int_list"] == {
        "element": "i64", "new": "big_int_list_new", "len": "big_int_list_len", "get": "big_int_list_get",
        "set": "big_int_list_set", "retain": "big_int_list_retain", "release": "big_int_list_release"}
    assert "data" not in d["lists"]["int_list"] and d["lists"]["i64_list"]["data"] == "big_i64_list_data"
    assert "typedef struct big_int_list big_int_list;" in h
    assert "BIG_API big_int_list *big_int_list_new(int64_t n);" in h
    assert "BIG_API int big_int_list_get(const big_int_list *l, int64_t i, int64_t *out);" in h
    assert "BIG_API int big_int_list_set(big_int_list *l, int64_t i, int64_t v);" in h
    assert "big_int_list_data" not in h and "big_int_list_data" not in biglib.c
    assert "Lists that may hold big ints (big_int_list)" in h
    assert "the element of the list does not fit in 64 bits" in h and "no counterpart in the source language" in h
    assert "BIG_API int big_total(big_int_list *p_w, int64_t *out);" in h
    assert "BIG_API int big_copy_with(big_i64_list *p_w, int64_t p_v, big_int_list **out);" in h
    assert chr(0x2014) not in h
    assert not cpython_api_uses(biglib.c) and not cpython_api_uses(h)
    assert re.findall(r"#include\s*[<\"]([^>\"]+)", biglib.c) == ["tp_aot.h", "tp_aot_int.h"]
    syms = [f["symbol"] for f in d["functions"]] + [v for k, v in d["lists"]["int_list"].items() if k != "element"]
    for sym in syms:
        assert re.search(rf"^BIG_API [^\n;]*\b{sym}\(", h, re.M), sym
        assert re.search(rf"^TPA_EXPORT [^\n]*\b{sym}\(", biglib.c, re.M), sym


def test_a_new_int_list_has_zeros_a_count_of_one_and_negative_indices_count_from_the_end(biglib):
    lib = biglib.lib
    h = lib.il.new(3)
    assert h is not None and lib.rc(h) == 1 and lib.il.len(h) == 3
    assert [lib.get(h, i) for i in (0, 1, 2, -1, -3)] == [(0, 0)] * 5
    assert lib.il.set(h, 1, 7) == 0 and lib.il.set(h, -1, I64_MIN) == 0
    assert [lib.get(h, i)[1] for i in (0, 1, 2)] == [0, 7, I64_MIN] and lib.get(h, -2) == (0, 7)
    lib.il.retain(h)
    assert lib.rc(h) == 2
    lib.il.release(h)
    assert lib.rc(h) == 1 and lib.il.len(None) == 0
    lib.il.retain(None)
    lib.il.release(None)                                 # NULL is a no-op
    lib.il.release(h)
    empty = lib.il.new(-5)                               # n < 0 is an empty list, as `[0] * -5` is
    assert lib.il.len(empty) == 0 and lib.get(empty, 0)[0] == -1
    lib.il.release(empty)
    balanced(biglib)


@pytest.mark.parametrize("index", [3, 4, -4, 100, I64_MAX, I64_MIN])
def test_get_and_set_out_of_range_raise_the_index_errors_cpython_raises(biglib, index):
    lib = biglib.lib
    h = lib.new_int_list([1, 2, 3])
    mirror = [1, 2, 3]
    with pytest.raises(IndexError) as read:
        mirror[index]
    with pytest.raises(IndexError) as write:
        mirror[index] = 0
    status, value = lib.get(h, index)
    assert status == -1 and value == -12345, "nothing is written to *out"
    assert lib.error() == error_of(read.value) == ("IndexError", "list index out of range")
    assert lib.il.set(h, index, 9) == -1
    assert lib.error() == error_of(write.value) == ("IndexError", "list assignment index out of range")
    assert [lib.get(h, i)[1] for i in range(3)] == [1, 2, 3], "a failed set changes nothing"
    lib.il.release(h)
    balanced(biglib)


def test_a_callee_stores_a_big_value_and_get_of_that_element_is_the_documented_overflow(biglib):
    lib, ref = biglib.lib, biglib.ref
    h = lib.il.new(3)
    mirror = [0, 0, 0]
    assert lib.raw("store_product", h, 1, 2**40, 2**40) == (0, [])
    ref.store_product(mirror, 1, 2**40, 2**40)
    assert mirror[1] == 2**80, "CPython holds the exact product"
    assert biglib.live() == 1
    assert lib.get(h, 1) == (-1, -12345) and lib.error() == ELEMENT_OVERFLOW      # no value is written
    assert lib.get(h, 0) == (0, 0) and lib.get(h, 2) == (0, 0)                     # the others read fine
    assert lib.il.set(h, 1, 5) == 0 and lib.get(h, 1) == (0, 5), "the same element reads after a small value is stored"
    assert biglib.live() == 0, "the store gave the big element back"
    assert lib.raw("store_product", h, 1, 3, 4) == (0, []) and lib.get(h, 1) == (0, 12), "a product that fits is a small"
    assert lib.raw("store_product", h, -1, -(2**40), 2**41) == (0, []) and lib.get(h, 2) == (-1, -12345)
    assert lib.error() == ELEMENT_OVERFLOW and lib.rc(h) == 1, "the argument was borrowed"
    lib.il.release(h)
    balanced(biglib)


def test_a_value_that_reaches_int64_max_and_one_more_is_big_and_back(biglib):
    lib, ref = biglib.lib, biglib.ref
    h = lib.new_int_list([I64_MAX, I64_MIN, 5])
    mirror = [I64_MAX, I64_MIN, 5]
    for i in (0, 1, 2, 0):
        assert lib.raw("bump", h, i) == (0, [])
        ref.bump(mirror, i)
    assert mirror == [2**63 + 1, I64_MIN + 1, 6]
    # the small form has 63 bits, so I64_MIN + 1 in slot 1 is a big object too
    assert lib.get(h, 0)[0] == -1 and lib.error() == ELEMENT_OVERFLOW and biglib.live() == 2
    assert lib.get(h, 1) == (0, I64_MIN + 1) and lib.get(h, 2) == (0, 6)
    assert lib.il.set(h, 0, 1) == 0 and lib.get(h, 0) == (0, 1) and biglib.live() == 1
    for index in (3, -4):                                    # `w[i] + 1` fails on the read, with the list's own message
        with pytest.raises(IndexError) as e:
            ref.bump([0, 0, 0], index)
        assert lib.raw("bump", h, index)[0] == -1 and lib.error() == error_of(e.value)
    lib.il.release(h)
    balanced(biglib)


def test_a_returned_big_capable_list_is_owned_by_the_caller_alone(biglib):
    lib, ref = biglib.lib, biglib.ref
    status, [h] = lib.raw("make_big", 3, 2**20)
    assert status == 0 and lib.rc(h) == 1 and lib.il.len(h) == 3
    assert ref.make_big(3, 2**20)[0] == 2**40 and [lib.get(h, i) for i in range(3)] == [(0, 2**40), (0, 0), (0, 0)]
    assert biglib.live() == 0, "2**40 is a small int: nothing was allocated"
    lib.il.release(h)
    status, [h] = lib.raw("make_big", 3, 2**40)
    assert status == 0 and ref.make_big(3, 2**40)[0] == 2**80
    assert lib.get(h, 0)[0] == -1 and lib.error() == ELEMENT_OVERFLOW and lib.get(h, -1) == (0, 0)
    assert biglib.live() == 1
    lib.il.release(h)
    balanced(biglib)
    status, [h] = lib.raw("repeat_big", 4, 2**40)                 # one big object, held by four elements
    assert status == 0 and lib.il.len(h) == 4 and biglib.live() == 1
    assert all(lib.get(h, i)[0] == -1 for i in range(4)) and ref.repeat_big(4, 2**40) == [2**80] * 4
    lib.il.release(h)
    balanced(biglib)
    status, [h] = lib.raw("repeat_big", -2, 2**40)
    assert status == 0 and lib.il.len(h) == 0 and ref.repeat_big(-2, 2**40) == []
    lib.il.release(h)
    balanced(biglib)


def test_a_big_capable_copy_keeps_the_elements_of_its_source_and_gives_them_back(biglib):
    lib, ref = biglib.lib, biglib.ref
    status, [h] = lib.raw("twice", 3, 2**40)            # a = [v * v] * 3; b = a[:]; b[0] = b[0] + 1; return b
    want = ref.twice(3, 2**40)
    assert status == 0 and want == [2**80 + 1, 2**80, 2**80] and biglib.live() == 2
    assert lib.raw("diff", h, 0, 1) == (0, [1]) and lib.raw("diff", h, 1, 2) == (0, [0]), "exact beyond 64 bits"
    assert lib.raw("diff", h, -3, -1) == (0, [1])
    lib.il.release(h)
    balanced(biglib)
    w = lib.new_list("i64", [7, 8, 9])                  # a raw list copied into a big-capable one
    status, [c] = lib.raw("copy_with", w, 2**40)
    assert status == 0 and ref.copy_with([7, 8, 9], 2**40) == [2**80, 8, 9]
    assert lib.get(c, 0)[0] == -1 and lib.get(c, 1) == (0, 8) and lib.get(c, 2) == (0, 9)
    assert lib.read_list("i64", w) == [7, 8, 9] and lib.rc(w) == 1 and lib.rc(c) == 1 and c != w
    lib.il.release(c)
    status, [c] = lib.raw("copy_with", w, 3)
    assert status == 0 and [lib.get(c, i)[1] for i in range(3)] == [9, 8, 9] and biglib.live() == 0
    lib.il.release(c)
    big_source = lib.new_list("i64", [I64_MAX, I64_MIN, 0])
    status, [c] = lib.raw("copy_with", big_source, 2)         # the extremes survive the conversion
    assert status == 0 and lib.get(c, 1) == (0, I64_MIN) and lib.get(c, 2) == (0, 0) and lib.get(c, 0) == (0, 4)
    lib.il.release(c)
    lib.release("i64", big_source)
    lib.release("i64", w)
    balanced(biglib)


def test_total_and_diff_are_exact_over_elements_that_do_not_fit(biglib):
    lib, ref = biglib.lib, biglib.ref
    h = lib.new_int_list([5, 6, 7])
    assert lib.raw("total", h) == (0, [18]) and ref.total([5, 6, 7]) == 18
    assert lib.raw("store_product", h, 0, 2**40, 2**40)[0] == 0 and lib.raw("store_product", h, 1, -(2**40), 2**40)[0] == 0
    assert ref.total([2**80, -(2**80), 7]) == 7
    assert lib.raw("total", h) == (0, [7]), "2**80 - 2**80 + 7, exact"
    assert lib.raw("diff", h, 0, 1)[0] == -1 and lib.error() == ("OverflowError", "the int result of `diff` does not fit in 64 bits")
    assert ref.diff([2**80, -(2**80), 7], 0, 1) == 2**81
    lib.il.release(h)
    h = lib.new_int_list([2**62, 2**62, 0])
    status, _ = lib.raw("total", h)
    assert status == -1 and lib.error() == ("OverflowError", "the int result of `total` does not fit in 64 bits")
    assert ref.total([2**62, 2**62, 0]) == 2**63
    lib.il.release(h)
    assert lib.raw("grow_and_total", 3, 2**20) == (0, [2**41]) and ref.grow_and_total(3, 2**20) == 2**41
    status, _ = lib.raw("grow_and_total", 3, 2**40)
    assert status == -1 and lib.error() == ("OverflowError", "the int result of `grow_and_total` does not fit in 64 bits")
    assert ref.grow_and_total(3, 2**40) == 2**81
    balanced(biglib)


def test_the_raw_i64_list_functions_of_the_same_library_still_work(biglib):
    lib, ref = biglib.lib, biglib.ref
    w = lib.new_list("i64", [1, 2, 3])
    assert lib.raw("raw_get", w, 2) == (0, [3]) and lib.raw("raw_get", w, -3) == (0, [1])
    assert lib.raw("raw_set", w, 0, I64_MIN) == (0, []) and lib.raw("raw_set", w, -1, I64_MAX) == (0, [])
    assert lib.read_list("i64", w) == [I64_MIN, 2, I64_MAX] and lib.rc(w) == 1
    with pytest.raises(IndexError) as e:
        ref.raw_get([1, 2, 3], 3)
    assert lib.raw("raw_get", w, 3)[0] == -1 and lib.error() == error_of(e.value)
    with pytest.raises(IndexError) as e:
        ref.raw_set([1, 2, 3], -4, 0)
    assert lib.raw("raw_set", w, -4, 0)[0] == -1 and lib.error() == error_of(e.value)
    lib.release("i64", w)
    balanced(biglib)


def test_a_null_int_list_handle_is_a_type_error_not_a_crash(biglib):
    lib = biglib.lib
    assert lib.get(None, 0)[0] == -1 and lib.error() == ("TypeError", "argument 'l' is a null list handle")
    assert lib.il.set(None, 0, 1) == -1 and lib.error() == ("TypeError", "argument 'l' is a null list handle")
    assert lib.raw("total", None)[0] == -1 and lib.error() == ("TypeError", "argument 'w' is a null list handle")
    assert lib.raw("store_product", None, 0, 1, 1)[0] == -1
    status, outs = lib.raw("copy_with", None, 1)
    assert status == -1 and outs == [None], "a list out handle is NULL on failure"


def test_several_threads_share_one_int_list_and_the_race_is_memory_safe(biglib):
    """Issue #465 (the lock inside the list is an agent proposal, not owner-confirmed; design note section 7). Writers
    store small values (`set`) and a big one (`store_product`, 2**40 * 2**40) into slot 0 of the SAME handle while
    readers call `get` and the compiled `diff` (slot 0 minus slot 1; slot 1 is never written, so it is 0). Every
    observed value must be one a writer stored (or 0 from the start), or the documented overflow error of the call. A
    reader that retained an object the writer had just freed (or two writers freeing the same old element) would
    crash or leave the live counter of big objects away from 0 once everything is released. Fixed seeds."""
    lib = biglib.lib
    h = lib.new_int_list([0, 0, 0])
    stored = {0, 1, 2, 3, 4}
    diff_error = ("OverflowError", "the int result of `diff` does not fit in 64 bits")
    writes_each, writers_done = 12000, threading.Barrier(2 + 3, timeout=120)
    problems, stop = [], threading.Event()
    seen = {"value": 0, "overflow": 0}

    def writer(seed):
        try:
            rng = random.Random(seed)
            writers_done.wait()
            for _ in range(writes_each):
                if rng.random() < 0.5:
                    assert lib.il.set(h, 0, rng.randint(1, 4)) == 0
                else:
                    assert lib.raw("store_product", h, 0, 2**40, 2**40) == (0, [])
        except BaseException as e:          # reported below, in the test's own thread
            problems.append(f"writer {seed}: {e!r}")

    def reader(seed):
        try:
            rng = random.Random(seed)
            writers_done.wait()
            while not stop.is_set():
                if rng.random() < 0.5:
                    status, v = lib.get(h, 0)
                    if status == 0:
                        assert v in stored, f"get read {v}, which no writer stored"
                        seen["value"] += 1
                    else:
                        assert (status, v) == (-1, -12345) and lib.error() == ELEMENT_OVERFLOW, (status, v, lib.error())
                        seen["overflow"] += 1
                else:
                    status, [v] = lib.raw("diff", h, 0, 1)
                    if status == 0:
                        assert v in stored, f"diff read {v}, which no writer stored"
                    else:
                        assert lib.error() == diff_error, lib.error()
        except BaseException as e:
            problems.append(f"reader {seed}: {e!r}")

    writers = [threading.Thread(target=writer, args=(1000 + k,)) for k in range(2)]
    readers = [threading.Thread(target=reader, args=(2000 + k,)) for k in range(3)]
    for t in writers + readers:
        t.start()
    for t in writers:
        t.join(timeout=120)
    stop.set()
    for t in readers:
        t.join(timeout=120)
    assert not any(t.is_alive() for t in writers + readers), "a thread did not finish"
    assert problems == [], problems
    assert seen["value"] > 0, "no read ever saw a small value, so the test proves nothing"
    assert lib.il.set(h, 0, 0) == 0                                    # the last big element is given back by the store
    lib.il.release(h)
    balanced(biglib)


def test_the_big_list_elements_are_read_and_written_only_through_the_locking_helpers(biglib):
    """Text only: the generated C of a library with a big-capable list reads and stores elements through
    `tpa_int_list_load` / `tpa_int_list_store` and never touches `->d[` of that kind itself; the exported `_get` and `_set`
    go through the runtime's `_get_i64` / `_set_i64`, which use them; and the runtime header selects the plain versions
    when TPA_THREADS is not defined (an executable) and the locking ones when it is (every library)."""
    c = biglib.c
    assert "tpa_int_list_load(" in c and "tpa_int_list_store(" in c
    bodies = re.findall(r"^(?:static|TPA_EXPORT)[^\n]*\)\n\{.*?^\}", c, flags=re.S | re.M)
    checked = 0
    for body in bodies:                      # per function: a name such as `v_w` is a raw list in one and a big one in another
        for name in set(re.findall(r"tpa_int_list \*(\w+)", body)):
            checked += 1
            assert not re.search(rf"\b{name}->d\[", body), f"direct element access of the big-capable list {name}:\n{body}"
    assert checked >= 5, "the library has big-capable lists"
    assert "tpa_int_list_get_i64(l, i, out)" in c and "tpa_int_list_set_i64(l, i, v)" in c
    runtime = (Path(aot.__file__).parent.parent / "runtime" / "tp_aot_int.h").read_text(encoding="utf-8")
    load = runtime[runtime.index("static inline void tpa_int_list_load("):]
    load = load[:load.index("\n}\n")]
    assert "#if defined(TPA_THREADS)" in load and "tpa_int_list__lock(l);" in load and "#else\n    tpa_int_set(out, l->d[slot]);" in load
    store = runtime[runtime.index("static inline void tpa_int_list_store("):]
    store = store[:store.index("\n}\n")]
    assert store.index("tpa_int_list__lock(l);") < store.index("tpa_int_list__unlock(l);") < store.index("tpa_int_release(old);")
    assert store.index("tpa_int_retain(v);") < store.index("tpa_int_list__lock(l);"), "the new reference is taken before the lock"
    assert "#else\n    tpa_int_set(&l->d[slot], v);" in store
    # (tier S2a: the first layout is the growable one, only for a program that resizes a big-capable list and never in
    # a library, where TPA_THREADS stops the build; the layout a library uses is the fixed one after it)
    grow = runtime[runtime.index("#if defined(TPA_GROW_INT)"):]
    grow = grow[:grow.index("typedef struct tpa_int_list {")]
    assert "#if defined(TPA_THREADS)\n#error" in grow, "a growable big-capable list never reaches a library build"
    first = runtime.index("typedef struct tpa_int_list {")
    layout = runtime[runtime.index("typedef struct tpa_int_list {", first + 1):]
    layout = layout[:layout.index("} tpa_int_list;")]
    assert "#if defined(TPA_THREADS)\n    tpa_int_list_lock_t lock;" in layout, "the lock field exists only with TPA_THREADS"
    assert "memory-safe for every list kind" in biglib.header
    assert "as with a C or Kotlin array" not in biglib.header
    assert chr(0x2014) not in runtime


def test_every_big_integer_of_the_big_list_tests_was_given_back(biglib):
    balanced(biglib)


def test_a_library_with_no_big_capable_list_has_no_int_list_kind(generated):
    """The first library (`kit`) has lists only of the raw kind: no `int_list` in its C text, header or description, whose
    `lists` are what they were, and no `dicts` entry (the one thing that differs from ABI version 1 is `abi_version`)."""
    assert "int_list" not in generated.header and "int_list" not in generated.c
    assert set(generated.description["lists"]) == {"f64_list", "i64_list"}
    assert "int_list" not in json.dumps(generated.description) and generated.description["abi_version"] == 3
    assert "dicts" not in generated.description and "_dict_" not in generated.header


def test_explain_lists_names_the_groups_and_the_boundary_of_a_library_and_builds_nothing(tmp_path, capsys):
    src = write(tmp_path, BIGLIB, "explained")
    status = main(["aot-lib", str(src), "--export", ",".join(BIG_EXPORTS), "--prefix", BIG_PREFIX, "--explain-lists"])
    shown = capsys.readouterr()
    assert status == 0 and shown.err == ""
    assert "library boundary (int_list): the parameter `w` of the export `store_product`" in shown.out
    assert "library boundary (i64_list): the parameter `w` of the export `raw_set`" in shown.out
    assert "line 2, in store_product: `w[i] = a * b`: stores a TAGGED value into `w`" in shown.out
    assert "list group 1: big-capable" in shown.out and "raw: no TAGGED value" in shown.out
    assert sorted(p.name for p in tmp_path.iterdir()) == ["explained.py"]


# --- the generated text (no compiler needed) --------------------------------------------------------

@pytest.fixture(scope="module")
def generated(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("gen")
    src = tmp / "kit_mod.py"
    src.write_text(SOURCE, encoding="utf-8")
    c, header, description = aot.generate_library(src, exports=EXPORTS, prefix=PREFIX)
    return SimpleNamespace(c=c, header=header, description=description)


def all_symbols(description):
    syms = [f["symbol"] for f in description["functions"]]
    syms += list(description["runtime"].values())
    for entry in description["lists"].values():
        syms += [v for k, v in entry.items() if k != "element"]
    return syms


def test_generated_text_contains_no_cpython(generated):
    for what, body in (("library C", generated.c), ("header", generated.header), ("tp_aot.h", HEADER.read_text())):
        assert not cpython_api_uses(body), (what, cpython_api_uses(body))
    assert re.findall(r"#include\s*[<\"]([^>\"]+)", generated.c) == ["tp_aot.h"]
    assert re.findall(r"#include\s*[<\"]([^>\"]+)", generated.header) == ["stdint.h"]


def test_every_symbol_of_the_description_is_a_prototype_and_an_exported_definition(generated):
    syms = all_symbols(generated.description)
    assert len(syms) == len(set(syms)) == len(EXPORTS) + 3 + 10
    for s in syms:
        assert re.search(rf"^{re.escape(PREFIX.upper())}_API [^\n;]*\b{s}\(", generated.header, re.M), s
        assert re.search(rf"^TPA_EXPORT [^\n]*\b{s}\(", generated.c, re.M), s
    exported = re.findall(r"^TPA_EXPORT [^\n]*?\b(\w+)\(", generated.c, re.M)
    assert sorted(exported) == sorted(syms), "nothing but the described symbols is exported"
    assert all(s.startswith(PREFIX + "_") for s in exported)


def test_no_main_is_defined_and_only_the_reachable_functions_are_compiled(generated):
    assert not re.search(r"\bmain\s*\(", generated.c)
    assert "tpa_argc" not in generated.c and "tpa_report_uncaught" not in generated.c
    for name in ("cube", "uses_cube", "make_x", "main"):
        assert f"tpf_{name}(" not in generated.c, name
    for name in ("norm", "scale_in_place", *EXPORTS):
        assert f"static int tpf_{name}(" in generated.c, name
    assert "#define TPA_LIBRARY" in generated.c


def test_the_main_block_with_an_unsupported_construct_is_not_compiled(generated):
    assert "nothing" not in generated.c and "comprehension" not in generated.c


def test_the_description_is_complete_and_in_the_vocabulary(generated):
    d = generated.description
    assert d["prefix"] == PREFIX and d["abi_version"] == 3
    assert d["library"] == library.library_file_name(PREFIX)
    assert d["runtime"] == {"init": "kit_init", "shutdown": "kit_shutdown", "last_error": "kit_last_error"}
    assert [f["name"] for f in d["functions"]] == EXPORTS
    for f in d["functions"]:
        assert set(f) == {"name", "symbol", "line", "params", "returns"}
        assert f["symbol"] == f"{PREFIX}_{f['name']}" and isinstance(f["line"], int) and f["line"] > 0
        assert all(set(p) == {"name", "type"} and p["type"] in library.VOCABULARY for p in f["params"])
        assert all(t in library.VOCABULARY for t in f["returns"])
    by = {f["name"]: f for f in d["functions"]}
    assert by["rem"]["params"] == [{"name": "a", "type": "i64"}, {"name": "b", "type": "i64"}]
    assert by["mix"]["params"][2] == {"name": "flag", "type": "bool"} and by["mix"]["returns"] == ["f64"]
    assert by["stats"]["returns"] == ["f64", "f64", "i64"] and by["stats"]["params"][0]["type"] == "f64_list"
    assert by["scale_in_place"]["returns"] == [] and by["squares"]["returns"] == ["f64_list"]
    assert by["count_up"]["returns"] == ["i64_list"] and by["positive"]["returns"] == ["bool"]
    assert set(d["lists"]) == {"f64_list", "i64_list"}
    assert d["lists"]["f64_list"]["element"] == "f64" and d["lists"]["i64_list"]["element"] == "i64"
    json.loads(json.dumps(d))                                                     # plain JSON


def test_the_header_states_the_threading_and_ownership_rules_and_has_guards(generated):
    h = generated.header
    assert h.count("#ifndef TP_AOT_LIB_KIT_H") == 1 and "#endif /* TP_AOT_LIB_KIT_H */" in h
    assert '#ifdef __cplusplus\nextern "C" {' in h
    for phrase in ("no library-wide lock", "reentrant", "atomic", "responsibility", "borrowed", "must call _release",
                   "any\n *   other value is read as true", "NULL list handle", "standard output", "per thread"):
        assert phrase in h, phrase
    assert chr(0x2014) not in h
    assert "/* rem(a: int, b: int) -> int   (kit_mod.py, line" in h


def test_generated_library_c_switches_fp_contraction_off_in_source(generated):
    """#464: the generated library C carries the floating-point contraction-off pragmas before any function."""
    for pragma in ("#pragma STDC FP_CONTRACT OFF", '#pragma GCC optimize("fp-contract=off")',
                   "#pragma float_control(precise, on)", "#pragma fp_contract(off)"):
        assert pragma in generated.c, pragma
    assert generated.c.index("#pragma STDC FP_CONTRACT OFF") < generated.c.index("static int tpf_")
    assert library.static_data_findings(generated.c, "kit_mod.py") == []


# --- the executable build is unchanged --------------------------------------------------------------

def test_the_executable_command_line_is_what_it_was(monkeypatch, tmp_path):
    if cbuild._is_msvc():
        pytest.skip("compared as a list of GCC and Clang tokens")
    monkeypatch.delenv("TP_SANITIZE", raising=False)
    cmd, env = cc.command(tmp_path / "a.c", tmp_path / "a")
    expected = (cbuild.compiler() + list(cbuild.DEFAULT_FLAGS)
                + [f"-fno-builtin-{n}" for n in ("sin", "cos", "tan", "exp", "log", "atan2")]
                + [f"-I{cc.RUNTIME_DIR}", str(tmp_path / "a.c"), "-o", str(tmp_path / "a"), "-lm"])
    assert cmd == expected and env is None
    assert not any("TPA_LIBRARY" in t or "-shared" in t or "-fPIC" in t or "visibility" in t or "dynamiclib" in t
                   for t in cmd)


def test_the_library_command_line_defines_the_library_macro_and_hides_everything_else(monkeypatch, tmp_path):
    monkeypatch.delenv("TP_SANITIZE", raising=False)
    cmd, _ = cc.library_command(tmp_path / "a.c", tmp_path / "liba.so")
    if cbuild._is_msvc():
        assert "/DTPA_LIBRARY" in cmd and "/LD" in cmd and "/fp:precise" in cmd
        return
    assert "-DTPA_LIBRARY" in cmd and "-fPIC" in cmd and "-fvisibility=hidden" in cmd
    assert ("-dynamiclib" in cmd) if sys.platform == "darwin" else ("-shared" in cmd)
    assert "-ffp-contract=off" in cmd and "-fno-fast-math" in cmd and "-O2" in cmd and "-lm" in cmd
    if sys.platform == "darwin":
        assert cmd[cmd.index("-install_name") + 1] == "@rpath/liba.so"


def test_the_library_command_honours_the_sanitizer_switch(monkeypatch, tmp_path):
    if cbuild._is_msvc():
        pytest.skip("the sanitizer flags are GCC and Clang flags")
    monkeypatch.setenv("TP_SANITIZE", "1")
    cmd, _ = cc.library_command(tmp_path / "a.c", tmp_path / "liba.so")
    for flag in cc.SANITIZE_FLAGS:
        assert cmd.count(flag) == 1, (flag, cmd)
    assert cmd.index("-fsanitize=address,undefined") < cmd.index(str(tmp_path / "a.c"))


def test_an_executables_c_has_no_library_code_path(tmp_path):
    """The executable's generated C does not mention the library macro, and defines no export: its list counts
    stay plain (`tpa_rc_t` is `int64_t` there, tp_aot.h) and `main` is the only entry."""
    c = aot.generate(write(tmp_path, '''
        def f(v: list[float], n: int) -> float:
            s = 0.0
            for i in range(n):
                s += v[i]
            return s

        if __name__ == "__main__":
            v = [1.0, 2.0]
            print(f(v, 2))
        ''', "exe"))
    assert "TPA_LIBRARY" not in c and "TPA_EXPORT" not in c and "int main(" in c
    header = HEADER.read_text()
    assert re.search(r"#else\ntypedef int64_t tpa_rc_t;\n#define TPA_RC_INC\(l\) \(\(l\)->rc \+= 1\)", header)
    assert "#if !defined(TPA_LIBRARY)" in header and "static int tpa_argc" in header
