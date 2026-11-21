"""TypedPython AOT: the release of a long chain of objects nests at most TPA_TRASH_LIMIT deep (review of #508, F1;
docs/design/typedpython-aot.md 6.3.1, "Deferred frees").

The generated release of a class loops over its tail field (the last field of its own type) and releases every
other field of a class type by a call. A chain of n objects through a field that is not the tail field, or through
fields of two classes that alternate, therefore recursed n deep and overflowed the C stack for n = 500000 (red on
develop: the program dies by a signal, or ASan reports a stack overflow in the sanitizer job). CPython frees the same
chains (its trashcan). Now a release past TPA_TRASH_LIMIT parks the object and the outermost release frees it.

  * the depth itself is checked, not a crash (the stack size differs between platforms: ubuntu's runner freed
    the unbounded chain in #513's first CI run): a build with `-DTPA_TRASH_STATS` prints the deepest release
    nesting at exit, which must stay at or below TPA_TRASH_LIMIT with objects parked; red before the fix (no
    such line, and the nesting was n);
  * mutant: the same program built with the limit out of reach (`-DTPA_TRASH_LIMIT=...` above n) nests n deep,
    so the bound is what keeps the depth down;
  * a class with no field of a class type generates the release it generated before (no `tpa_trash_`);
  * every program also runs with LeakSanitizer on in the sanitizer job: the parked objects are all freed.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
import textwrap
from pathlib import Path

from typedpython import aot
from typedpython.aot import cc as cc_mod

N = 500000
N_MUT = 5000                    # the mutant's chain: deep enough to pass the limit, shallow enough for any stack
LIMIT = 1000                    # TPA_TRASH_LIMIT's default
TRASH = re.compile(r"^tpa_trash: max_depth=(\d+) parked=(\d+)$", re.M)

CHAIN = """
import sys


class Chain:
    __slots__ = ("v", "l", "r")
    v: int
    l: "Chain | None"
    r: "Chain | None"

    def __init__(self, v: int, l: "Chain | None", r: "Chain | None") -> None:
        self.v = v
        self.l = l
        self.r = r


def chain(n: int) -> Chain:
    prev: Chain = Chain(0, None, None)
    for i in range(1, n):
        prev = Chain(i, prev, None)
    return prev


def top(n: int) -> int:
    c: Chain = chain(n)
    return c.v


if __name__ == "__main__":
    print(top(int(sys.argv[1])))
"""

ALTERNATING = """
import sys


class A:
    __slots__ = ("v", "b")
    v: int
    b: "B | None"

    def __init__(self, v: int, b: "B | None") -> None:
        self.v = v
        self.b = b


class B:
    __slots__ = ("v", "a")
    v: int
    a: A | None

    def __init__(self, v: int, a: A | None) -> None:
        self.v = v
        self.a = a


def build(n: int) -> A:
    head: A = A(0, None)
    for i in range(1, n):
        head = A(i, B(i, head))
    return head


def top(n: int) -> int:
    h: A = build(n)
    return h.v


if __name__ == "__main__":
    print(top(int(sys.argv[1])))
"""

LEAF = """
import sys


class P:
    __slots__ = ("x", "y")
    x: float
    y: float

    def __init__(self, x: float, y: float) -> None:
        self.x = x
        self.y = y


def norm(n: int) -> float:
    t: float = 0.0
    for i in range(n):
        p: P = P(i * 1.0, 2.0)
        t += p.x * p.y
    return t


if __name__ == "__main__":
    print(norm(int(sys.argv[1])))
"""


def _write(tmp_path: Path, src: str, name: str) -> Path:
    p = tmp_path / name
    p.write_text(textwrap.dedent(src).lstrip(), encoding="utf-8")
    return p


def _run(exe: Path, args: list[str]) -> subprocess.CompletedProcess[str]:
    env = dict(os.environ)
    if cc_mod.sanitizing() and sys.platform.startswith("linux"):
        env["ASAN_OPTIONS"] = env.get("ASAN_OPTIONS", "") + ":detect_leaks=1"
    return subprocess.run([str(exe), *args], capture_output=True, text=True, env=env)


def _trash_stats(r: subprocess.CompletedProcess[str]) -> tuple[int, int]:
    m = TRASH.search(r.stderr)
    assert m is not None, r.stderr[-2000:]
    return int(m.group(1)), int(m.group(2))


def _check_chain(tmp_path: Path, src: str, name: str, cls: str) -> Path:
    p = _write(tmp_path, src, name)
    report = aot.explain_alloc(p)
    assert "-> heap-rc" in report and "-> region" not in report, report   # the premise: heap objects, freed by release
    c_text = aot.generate(p)
    release = c_text[c_text.index(f"static void tpa_cls_{cls}_release(tpa_cls_{cls} *p)\n{{"):]
    release = release[: release.index("\n}\n")]
    assert f"tpa_trash_enter(p, tpa_cls_{cls}_drop)" in release and "tpa_trash_leave();" in release
    cp = subprocess.run([sys.executable, str(p), str(N)], capture_output=True, text=True)
    assert cp.returncode == 0, cp.stderr
    exe = aot.build(p, tmp_path / (p.stem + "_bin"))
    r = _run(exe, [str(N)])
    assert r.returncode == 0, (r.returncode, r.stderr[-2000:])
    assert r.stdout == cp.stdout
    assert r.stderr == ""
    stats = _run(aot.build(p, tmp_path / (p.stem + "_stats"), defines=("TPA_TRASH_STATS",)), [str(N)])
    assert stats.returncode == 0 and stats.stdout == cp.stdout, stats.stderr[-2000:]
    max_depth, parked = _trash_stats(stats)
    assert max_depth <= LIMIT, max_depth
    assert parked > 0, parked                   # the chain went past the limit: the deferral did the work
    return p


def test_a_long_chain_through_a_non_tail_field_is_freed(tmp_path: Path):
    p = _check_chain(tmp_path, CHAIN, "chain.py", "Chain")
    c_text = aot.generate(p)
    assert "tpa_cls_Chain *next = p->f_r;" in c_text          # the premise: the tail loop follows `r`, not `l`


def test_a_long_chain_alternating_between_two_classes_is_freed(tmp_path: Path):
    p = _check_chain(tmp_path, ALTERNATING, "alt.py", "A")
    assert "tpa_trash_enter(p, tpa_cls_B_drop)" in aot.generate(p)


def test_without_the_depth_bound_the_release_nests_once_per_object(tmp_path: Path):
    """Mutant: the limit above the chain's length, so every release nests as on develop: the depth reaches the
    chain's length and nothing is parked. Measured, not crashed, so the control is the same on every platform."""
    p = _write(tmp_path, CHAIN, "chain_mut.py")
    exe = aot.build(p, tmp_path / "chain_mut_bin", defines=("TPA_TRASH_STATS", f"TPA_TRASH_LIMIT={10 * N}"))
    r = _run(exe, [str(N_MUT)])
    assert r.returncode == 0, r.stderr[-2000:]
    max_depth, parked = _trash_stats(r)
    assert max_depth >= N_MUT - 1 and parked == 0, (max_depth, parked)
    ok = _run(aot.build(p, tmp_path / "chain_ok_bin", defines=("TPA_TRASH_STATS",)), [str(N_MUT)])
    assert _trash_stats(ok)[0] <= LIMIT                  # the same chain with the bound


def test_a_class_without_object_fields_keeps_its_release(tmp_path: Path):
    p = _write(tmp_path, LEAF, "leaf.py")
    c_text = aot.generate(p)
    release = c_text[c_text.index("static void tpa_cls_P_release(tpa_cls_P *p)\n{"):]
    release = release[: release.index("\n}\n")]
    assert "tpa_trash" not in release and "tpa_cls_P_drop" not in c_text
    cp = subprocess.run([sys.executable, str(p), "1000"], capture_output=True, text=True)
    r = _run(aot.build(p, tmp_path / "leaf_bin"), ["1000"])
    assert r.returncode == 0 and r.stdout == cp.stdout
