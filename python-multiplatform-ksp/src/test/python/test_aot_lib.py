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
slow): TWO. One, module-scoped, with every exported function of the test source (`built`); one tiny one for the
command line (`test_command_line_*`). The paths the build function and the command line return are the paths
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
          "f64_list": ctypes.c_void_p, "i64_list": ctypes.c_void_p}
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
        for t, syms in self.desc["lists"].items():
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
'''


def test_command_line_writes_exactly_the_library_the_header_and_the_description(tmp_path):
    need_cc()
    src = write(tmp_path, TINY, "tiny")
    out = tmp_path / "out"
    c_file = tmp_path / "elsewhere" / "tiny.c"
    stdout = io.StringIO()
    with contextlib.redirect_stdout(stdout):
        status = main(["aot-lib", str(src), "--export", "add_one", "--prefix", "tiny", "-o", str(out),
                       "--emit-c", str(c_file)])
    assert status == 0
    paths = [Path(line) for line in stdout.getvalue().splitlines() if line.strip()]
    assert len(paths) == 3 and all(p.is_file() for p in paths), paths
    lib_path, header, description = paths
    assert lib_path.parent == out and header == out / "tiny.h" and description == out / "tiny.json"
    names = [n for n in os.listdir(out) if not n.endswith(".dSYM")]       # (a sanitized build on macOS: debug info)
    assert sorted(names) == sorted([lib_path.name, "tiny.h", "tiny.json"]), "no by-products in the output"
    assert c_file.is_file() and "TPA_EXPORT int tiny_add_one(" in c_file.read_text(encoding="utf-8")
    lib = Lib(aot.LibraryFiles(lib_path, header, description))
    assert lib.desc["library"] == lib_path.name
    assert lib.raw("add_one", 1.5) == (0, [2.5])
    assert lib.init() == 0


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


@pytest.mark.parametrize("name", ["takes_text", "gives_text", "takes_nested", "gives_tuple_with_list"])
def test_an_unsupported_parameter_or_return_type_is_refused_naming_the_export(tmp_path, name):
    found, err = findings_of(tmp_path, REJECT_BASE, exports=[name], prefix="p")
    assert found == {("aot/export", line_of(REJECT_BASE, f"def {name}("), name)}
    assert "outside the library boundary" in str(err)


def test_a_reserved_symbol_name_is_refused(tmp_path):
    found, _ = findings_of(tmp_path, REJECT_BASE, exports=["init"], prefix="p")
    assert {r for r, _, _ in found} == {"aot/export"}


def test_an_exported_function_with_a_finding_gives_the_finding_aot_build_gives(tmp_path):
    """`cube` exported: the unproved int operation, with the position and text `aot-build` reports for the same
    function in a program with an entry."""
    lib_found, _ = findings_of(tmp_path, REJECT_BASE, exports=["cube"], prefix="p")
    assert {r for r, _, _ in lib_found} == {"aot/int-unproven"}
    exe = write(tmp_path, 'def cube(a: int) -> int:\n    return a * a * a\n\n\nif __name__ == "__main__":\n    print(cube(2))\n',
                "exe")
    with pytest.raises(aot.AotError) as raised:
        aot.generate(exe)
    lib_src = write(tmp_path, 'def cube(a: int) -> int:\n    return a * a * a\n', "libver")
    with pytest.raises(aot.AotError) as lib_raised:
        aot.generate_library(lib_src, exports=["cube"], prefix="p")
    def unproven(e):
        return {(f.line, f.rule, f.name, f.message) for f in e.findings if f.rule == "aot/int-unproven"}
    assert unproven(lib_raised.value) == unproven(raised.value) and len(unproven(raised.value)) == 2


def test_a_function_reachable_from_an_export_fails_the_library_and_one_that_is_not_does_not(tmp_path):
    found, _ = findings_of(tmp_path, REJECT_BASE, exports=["calls_cube"], prefix="p")
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
        assert "Python.h" not in body, what
        assert not re.search(r"\b_?Py[A-Z_a-z]", body), (what, re.findall(r"\b_?Py[A-Z_a-z]\w*", body))
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
    assert d["prefix"] == PREFIX and d["abi_version"] == 1
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
