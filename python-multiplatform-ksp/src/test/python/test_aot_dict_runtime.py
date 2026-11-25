"""AOT mode, tier S2b: the dict runtime (runtime/tp_aot_dict.h) against CPython's own dict (design 4.3.2, "dict and
set"; agent proposal, not owner-confirmed).

The shim (runtime_shim/tp_aot_dict_shim.c) is built once as a shared library (`aot/cc.py compile_library`) and runs
a script of operations on two dicts `d` and `b` and one iterator over `d`, writing one line per operation. The same
script runs here on CPython's dict and the lines must be equal. A script interleaves `next` of a live iterator with
inserts, deletes, `popitem`, `clear`, `copy` and `update`, so what it compares is not only the contents but what a
loop that changes its dict visits and where it raises (RuntimeError "dictionary changed size during iteration" and
"dictionary keys changed during iteration"): that depends on when CPython resizes and compacts the entry array,
which the runtime mirrors rule for rule. Keys are ints (with bool keys: `1 == True`, the first key's type is kept)
or strs (Latin-1 and 2-byte).

What this cannot find: slot positions (no program sees them, the hash function is not CPython's), and anything of
the front end or back end (test_aot_tier_s2b.py builds programs). Needs a C compiler (skipped without one).
"""

from __future__ import annotations

import ctypes
import random
import shutil
import sys
from pathlib import Path

import pytest

from typedpython import cbuild
from typedpython.aot import cc, library

HEADER = cc.RUNTIME_DIR / "tp_aot_dict.h"
SHIM = Path(__file__).resolve().parent / "runtime_shim" / "tp_aot_dict_shim.c"

(SET, GET, DEL, POP, POPD, SETDEFAULT, POPITEM, CLEAR, LEN, CONTAINS, REPR, COPY, ITER, NEXT, B_SET, UPDATE, EQ,
 B_CLEAR, COPY_TO_B, B_DEL, SETB, GETD, ITEMS, DISPLAY) = range(24)


def need_cc():
    if sys.version_info < (3, 14):
        pytest.skip("the runtime carries the behaviour of CPython 3.14 and later")
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


@pytest.fixture(scope="module")
def shim(tmp_path_factory):
    need_cc()
    out = tmp_path_factory.mktemp("dict_shim")
    path = cc.compile_library("", out, library.library_file_name("tp_aot_dict_shim"), keep=SHIM)
    dll = ctypes.CDLL(str(path))
    dll.shim_dict_run.restype = ctypes.c_int
    dll.shim_dict_run.argtypes = [ctypes.c_int, ctypes.POINTER(ctypes.c_int64), ctypes.c_int64, ctypes.c_char_p,
                                  ctypes.c_size_t]
    return dll


def run_runtime(dll, str_keys: bool, script: list[tuple[int, int, int]]) -> list[str]:
    flat = (ctypes.c_int64 * (3 * len(script)))(*[x for step in script for x in step])
    buf = ctypes.create_string_buffer(1 << 20)
    rc = dll.shim_dict_run(int(str_keys), flat, len(script), buf, len(buf))
    assert rc == 0, f"shim status {rc}"
    return buf.value.decode("utf-8").splitlines()


def key_of(str_keys: bool, k: int):
    if not str_keys:
        return k
    s = f"k{k}"
    if k & 1:
        s += "\u03b1" if k % 3 == 0 else "\u00e9"
    return s


def run_cpython(str_keys: bool, script: list[tuple[int, int, int]]) -> list[str]:
    out: list[str] = []
    d: dict = {}
    b: dict = {}
    it = None

    def err(e: BaseException) -> str:
        return f"{type(e).__name__}: {e}"

    for op, a, v in script:
        k = key_of(str_keys, a)
        try:
            if op == SET:
                d[k] = v
                out.append("ok")
            elif op == SETB:
                d[bool(a & 1)] = v
                out.append("ok")
            elif op == GET:
                out.append(str(d[k]))
            elif op == GETD:
                out.append(str(d.get(k, v)))
            elif op == DEL:
                del d[k]
                out.append("ok")
            elif op == POP:
                out.append(str(d.pop(k)))
            elif op == POPD:
                out.append(str(d.pop(k, v)))
            elif op == SETDEFAULT:
                out.append(str(d.setdefault(k, v)))
            elif op == POPITEM:
                pk, pv = d.popitem()
                out.append(repr(pk))
                out.append(f" {pv}")
            elif op == CLEAR:
                d.clear()
                out.append("ok")
            elif op == B_CLEAR:
                b.clear()
                out.append("ok")
            elif op == LEN:
                out.append(str(len(d)))
            elif op == CONTAINS:
                out.append(str(k in d))
            elif op == REPR:
                out.append(repr(d))
            elif op == ITEMS:
                for ik, iv in d.items():
                    out.append("item " + repr(ik))
                    out.append(f" -> {iv}")
                out.append("end")
            elif op == COPY:
                d = d.copy()
                out.append(repr(d))
            elif op == COPY_TO_B:
                b = d.copy()
                out.append("ok")
            elif op == ITER:
                it = iter(d)
                out.append("ok")
            elif op == NEXT:
                if it is None:
                    out.append("noiter")
                else:
                    try:
                        out.append(repr(next(it)))
                    except StopIteration:
                        out.append("stop")
            elif op == B_SET:
                b[k] = v
                out.append("ok")
            elif op == B_DEL:
                del b[k]
                out.append("ok")
            elif op == UPDATE:
                d.update(b)
                out.append("ok")
            elif op == EQ:
                out.append(str(d == b))
            elif op == DISPLAY:
                m = a % 16
                d = eval("{" + ", ".join(f"{key_of(str_keys, j)!r}: {j}" for j in range(m)) + "}")
                out.append("ok")
        except (KeyError, RuntimeError) as e:
            out.append(err(e))
    return out


def random_script(rng: random.Random, str_keys: bool, n: int, keyspace: int) -> list[tuple[int, int, int]]:
    ops = [(SET, 30), (DEL, 8), (POP, 4), (POPD, 3), (SETDEFAULT, 4), (POPITEM, 4), (CLEAR, 1), (LEN, 2),
           (CONTAINS, 2), (REPR, 3), (COPY, 2), (ITER, 4), (NEXT, 16), (B_SET, 4), (UPDATE, 2), (EQ, 2),
           (B_CLEAR, 1), (COPY_TO_B, 2), (B_DEL, 1), (GET, 4), (GETD, 2), (ITEMS, 1), (DISPLAY, 1)]
    if not str_keys:
        ops.append((SETB, 2))
    names, weights = zip(*ops)
    return [(rng.choices(names, weights)[0], rng.randrange(keyspace), rng.randrange(-5, 1000)) for _ in range(n)]


def check(shim, str_keys: bool, script: list[tuple[int, int, int]]) -> None:
    got, want = run_runtime(shim, str_keys, script), run_cpython(str_keys, script)
    if got != want:
        for i, (g, w) in enumerate(zip(got, want)):
            if g != w:
                pytest.fail(f"line {i}: runtime {g!r}, CPython {w!r}; script {script}")
        pytest.fail(f"{len(got)} lines from the runtime, {len(want)} from CPython; script {script}")


@pytest.mark.parametrize("str_keys", [False, True])
@pytest.mark.parametrize("keyspace", [6, 14, 40, 200])
def test_random_scripts_match_cpython(shim, str_keys: bool, keyspace: int):
    """400 random scripts per case: small key spaces delete and re-insert (holes, compaction), large ones grow
    through several resizes; iterators are interleaved with every kind of change."""
    rng = random.Random(1000 * keyspace + str_keys)
    for _ in range(400):
        check(shim, str_keys, random_script(rng, str_keys, rng.randrange(20, 300), keyspace))


def test_iteration_rules_by_hand(shim):
    """The cases the random scripts reach by chance, written out: a delete then an insert keeps the size (the loop
    goes on and sees the new key at the end); a delete alone changes it (RuntimeError, sticky); an insert past the
    usable entries compacts and the iterator produces more than it held ("keys changed"); popitem truncates the
    entry count, so the next insert reuses the entry; clear then re-fill."""
    scripts = [
        [(SET, i, i) for i in range(5)] + [(ITER, 0, 0), (NEXT, 0, 0), (DEL, 0, 0), (SET, 9, 9)]
        + [(NEXT, 0, 0)] * 7,
        [(SET, i, i) for i in range(5)] + [(ITER, 0, 0), (NEXT, 0, 0), (DEL, 3, 0), (NEXT, 0, 0), (NEXT, 0, 0)],
        [(SET, i, i) for i in range(5)] + [(ITER, 0, 0), (DEL, 1, 0), (SET, 7, 7), (DEL, 2, 0), (SET, 8, 8)]
        + [(NEXT, 0, 0)] * 8,
        [(SET, i, i) for i in range(5)] + [(POPITEM, 0, 0), (SET, 6, 6), (ITER, 0, 0)] + [(NEXT, 0, 0)] * 7,
        [(SET, i, i) for i in range(8)] + [(ITER, 0, 0), (NEXT, 0, 0), (CLEAR, 0, 0)]
        + [(SET, i, i) for i in range(8)] + [(NEXT, 0, 0)] * 3,
        [(SETB, 1, 5), (SET, 1, 6), (GET, 1, 0), (REPR, 0, 0), (SET, 0, 1), (SETB, 0, 2), (REPR, 0, 0)],
        [(POPITEM, 0, 0), (GET, 3, 0), (DEL, 3, 0), (POP, 3, 0), (POPD, 3, 7), (REPR, 0, 0)],
    ]
    for s in scripts:
        check(shim, False, s)
    check(shim, True, scripts[0] + [(GET, 99, 0), (REPR, 0, 0)])


def test_copy_and_update_keep_cpython_layouts(shim):
    """copy() keeps the holes when at most a third of the entries are deleted and compacts otherwise; update() into
    an empty dict clones a clean source; both show in what a later loop with inserts visits."""
    base = [(SET, i, i) for i in range(10)]
    for dels in (2, 4, 7):
        tail = [(COPY, 0, 0), (ITER, 0, 0), (NEXT, 0, 0), (DEL, 9, 0), (SET, 50, 0), (SET, 51, 0), (DEL, 50, 0)]
        tail += [(NEXT, 0, 0)] * 14
        check(shim, False, base + [(DEL, i, 0) for i in range(dels)] + tail)
    for n in (3, 6, 12):
        check(shim, False, [(B_SET, i, i) for i in range(n)] + [(UPDATE, 0, 0), (ITER, 0, 0)]
              + [(SET, 100 + i, 0) for i in range(4)] + [(NEXT, 0, 0)] * 20)
