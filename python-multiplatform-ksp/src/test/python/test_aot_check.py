"""Check-only mode of the TypedPython AOT design (SPEC N-17, #446): `python -m typedpython aot-check`.

Pure Python, no C compiler. The table tests run the AST pass alone (`front_end=False`): every row names
the exact line in the ORIGINAL source. The kernel, line-mapping, two-module and CLI tests run the front
end and the verifier too (they need Pyrefly, like test_frontend.py).
"""
import json
import textwrap
from pathlib import Path

import pytest

from typedpython import aotcheck
from typedpython.__main__ import main

BENCH = Path(__file__).resolve().parents[4] / "benchmarks" / "typedpython" / "py"


def write(tmp_path, source, name="mod.py"):
    path = tmp_path / name
    path.write_text(textwrap.dedent(source).lstrip("\n"))
    return path


def found(report, rule):
    return [(f.kind, f.tier, f.rule, f.name, f.line) for f in report.findings if f.rule == rule]


# rule, source (line 1 is its first line), expected (kind, tier, name, line)
ERR = "error"
PEND = "pending"
TABLE = [
    # aot/dynamic-code
    ("aot/dynamic-code", "def f(s: str) -> int:\n    return eval(s)\n", (ERR, None, "eval", 2)),
    ("aot/dynamic-code", "def f(s: str) -> None:\n    exec(s)\n", (ERR, None, "exec", 2)),
    ("aot/dynamic-code", "import importlib\n", (ERR, None, "importlib", 1)),
    # aot/import
    ("aot/import", "import json\n", (ERR, None, "json", 1)),
    ("aot/import", "def f() -> None:\n    import math\n", (ERR, None, "math", 2)),
    ("aot/import", "from . import x\n", (ERR, None, ".", 1)),
    ("aot/import", "from math import *\n", (ERR, None, "math", 1)),
    # aot/identity
    ("aot/identity", "def f(x: int) -> int:\n    return id(x)\n", (ERR, None, "id", 2)),
    ("aot/identity", "def f(x: int) -> int:\n    return hash(x)\n", (ERR, None, "hash", 2)),
    ("aot/identity", "def f(x: int) -> bool:\n    return x is 1000\n", (ERR, None, "is", 2)),
    ("aot/identity", "def f(x: str) -> bool:\n    return x is not 'a'\n", (ERR, None, "is not", 2)),
    # aot/introspection
    ("aot/introspection", "def f(x: int) -> int:\n    return getattr(x, 'a')\n", (ERR, None, "getattr", 2)),
    ("aot/introspection", "def f(x: int) -> bool:\n    return hasattr(x, 'a')\n", (ERR, None, "hasattr", 2)),
    ("aot/introspection", "def f(x: int) -> int:\n    return x.__dict__\n", (ERR, None, "__dict__", 2)),
    ("aot/introspection", "def f(x: int) -> int:\n    return x.__class__\n", (ERR, None, "__class__", 2)),
    ("aot/introspection", "def f() -> int:\n    return type('C', (), {})\n", (ERR, None, "type", 2)),
    # aot/generator
    ("aot/generator", "def f(n: int):\n    yield n\n", (ERR, None, "yield", 2)),
    ("aot/generator", "def f(n: int):\n    yield from n\n", (ERR, None, "yield from", 2)),
    ("aot/generator", "def f(n: int) -> int:\n    return sum(i for i in range(n))\n", (ERR, None, "generator expression", 2)),
    # aot/async
    ("aot/async", "async def f() -> None:\n    pass\n", (ERR, None, "async def", 1)),
    ("aot/async", "async def f(x: int) -> int:\n    return await x\n", (ERR, None, "await", 2)),
    ("aot/async", "async def f(x: int) -> None:\n    async for i in x:\n        pass\n", (ERR, None, "async for", 2)),
    ("aot/async", "async def f(x: int) -> None:\n    async with x:\n        pass\n", (ERR, None, "async with", 2)),
    # aot/closure
    ("aot/closure", "def f(x: int) -> int:\n    g = lambda y: y + x\n    return g(1)\n", (ERR, None, "lambda", 2)),
    ("aot/closure", "def f(x: int) -> int:\n    def g() -> int:\n        return x\n    return g()\n", (ERR, None, "def", 2)),
    ("aot/closure", "def f() -> int:\n    n = 0\n    def g() -> None:\n        nonlocal n\n        n = 1\n    return n\n",
     (ERR, None, "nonlocal", 4)),
    # aot/global
    ("aot/global", "n = 0\n\ndef f() -> None:\n    global n\n    n = 1\n", (ERR, None, "n", 4)),
    # aot/decorator
    ("aot/decorator", "@property\ndef f() -> int:\n    return 1\n", (ERR, None, "property", 1)),
    ("aot/decorator", "import functools\n\n@functools.lru_cache(maxsize=None)\ndef f(n: int) -> int:\n    return n\n",
     (ERR, None, "functools.lru_cache(maxsize=None)", 3)),
    ("aot/decorator", "@dataclass\nclass C:\n    x: int\n", (ERR, None, "dataclass", 1)),
    # aot/with, aot/match
    ("aot/with", "def f(x: int) -> None:\n    with x:\n        pass\n", (ERR, None, "with", 2)),
    ("aot/match", "def f(x: int) -> int:\n    match x:\n        case 1:\n            return 1\n    return 0\n",
     (ERR, None, "match", 2)),
    # aot/class-shape
    ("aot/class-shape", "class C(metaclass=M):\n    pass\n", (ERR, None, "metaclass", 1)),
    ("aot/class-shape", "class C:\n    x: int\n\ndef f() -> None:\n    C.y = 1\n", (ERR, None, "C.y", 5)),
    # aot/inheritance (pending S)
    ("aot/inheritance", "class B:\n    pass\n\nclass D(B):\n    pass\n", (PEND, "S", "B", 4)),
    # aot/signal
    ("aot/signal", "def f() -> None:\n    try:\n        pass\n    except KeyboardInterrupt:\n        pass\n",
     (ERR, None, "KeyboardInterrupt", 4)),
    ("aot/signal", "def f() -> None:\n    try:\n        pass\n    except (ValueError, RecursionError):\n        pass\n",
     (ERR, None, "RecursionError", 4)),
    # aot/set-order
    ("aot/set-order", "def f() -> None:\n    for x in {1, 2}:\n        pass\n", (ERR, None, "set", 2)),
    ("aot/set-order", "def f(a: list[int]) -> None:\n    for x in set(a):\n        pass\n", (ERR, None, "set", 2)),
    ("aot/set-order", "def f(s: set[int]) -> None:\n    for x in s:\n        pass\n", (ERR, None, "set", 2)),
    # aot/star-args
    ("aot/star-args", "def f(*args) -> None:\n    pass\n", (ERR, None, "*args", 1)),
    ("aot/star-args", "def f(**kw) -> None:\n    pass\n", (ERR, None, "**kw", 1)),
    ("aot/star-args", "def g(a: int) -> None:\n    pass\n\ndef f(a: list[int]) -> None:\n    g(*a)\n", (ERR, None, "*", 5)),
    ("aot/star-args", "def g(a: int) -> None:\n    pass\n\ndef f(a: dict[str, int]) -> None:\n    g(**a)\n", (ERR, None, "**", 5)),
    # aot/numpy
    ("aot/numpy", "import numpy as np\n", (ERR, None, "numpy", 1)),
    ("aot/numpy", "import array\n", (ERR, None, "array", 1)),
    # aot/builtin-unavailable
    ("aot/builtin-unavailable", "def f() -> None:\n    open('x')\n", (ERR, None, "open", 2)),
    ("aot/builtin-unavailable", "def f(x: int) -> None:\n    map(None, x)\n", (ERR, None, "map", 2)),
    # aot/builtin (pending K: non-constant module-level statement)
    ("aot/builtin", "x = 1\nfor i in range(3):\n    pass\n", (PEND, "K", "For", 2)),
    # aot/class-shape (kept above)
    # aot/str, aot/container, aot/exception, aot/builtin (pending S)
    ("aot/str", "def f() -> str:\n    return 'a b'.upper()\n", (PEND, "S", "upper", 2)),
    ("aot/str", "def f(s: str) -> int:\n    return s.find('a')\n", (PEND, "S", "find", 2)),
    ("aot/str", "def f(x: int) -> str:\n    return f'{x}'\n", (PEND, "S", "f-string", 2)),
    ("aot/container", "def f() -> int:\n    d = {1: 2}\n    return 0\n", (PEND, "S", "dict display", 2)),
    ("aot/container", "def f(n: int) -> int:\n    return len([i for i in range(n)])\n", (PEND, "S", "list comprehension", 2)),
    ("aot/exception", "def f() -> None:\n    raise ValueError('x')\n", (PEND, "S", "raise", 2)),
    ("aot/exception", "def f() -> None:\n    try:\n        pass\n    finally:\n        pass\n", (PEND, "S", "try", 2)),
    ("aot/exception", "def f(x: int) -> None:\n    assert x\n", (PEND, "S", "assert", 2)),
    ("aot/builtin", "def f(a: list[int]) -> list[int]:\n    return sorted(a)\n", (PEND, "S", "sorted", 2)),
    ("aot/builtin", "def f(x: int) -> str:\n    return str(x)\n", (PEND, "S", "str", 2)),
    # aot/threading (pending T)
    ("aot/threading", "import threading\n", (PEND, "T", "threading", 1)),
]


@pytest.mark.parametrize("rule,source,expected", TABLE, ids=[f"{r}-{i}" for i, (r, _, _) in enumerate(TABLE)])
def test_rule_table(tmp_path, rule, source, expected):
    report = aotcheck.check(write(tmp_path, source), front_end=False)
    kind, tier, name, line = expected
    assert (kind, tier, rule, name, line) in found(report, rule), report.lines()


def test_every_rule_of_the_table_has_a_row():
    rules = {r for r, _, _ in TABLE}
    assert rules >= {
        "aot/dynamic-code", "aot/import", "aot/identity", "aot/introspection", "aot/generator", "aot/async",
        "aot/closure", "aot/global", "aot/decorator", "aot/with", "aot/match", "aot/class-shape",
        "aot/inheritance", "aot/signal", "aot/set-order", "aot/star-args", "aot/numpy",
        "aot/builtin-unavailable", "aot/builtin", "aot/str", "aot/container", "aot/exception",
        "aot/threading",
    }


def test_things_inside_the_subset_are_not_reported(tmp_path):
    path = write(tmp_path, """
        import math
        import sys
        import time

        PI: float = 3.14
        TWO_PI: float = 2 * PI
        LIMIT = 10

        def f(n: int, a: list[float]) -> float:
            b: list[float] = [0.0] * n
            c: list[int] = list(range(n))
            d: list[float] = [1.0, 2.0]
            t: float = math.sqrt(float(len(a)))
            print(f"{t:.3f}")
            return t + b[0] + c[0] + d[0] + time.perf_counter() if False else t

        def g(x: int) -> bool:
            return isinstance(x, int) and type(x) is int and bool(abs(min(x, max(x, 0))))

        if __name__ == "__main__":
            n: int = int(sys.argv[1]) if len(sys.argv) > 1 else 1
            print(n)
    """)
    report = aotcheck.check(path, front_end=False)
    assert report.findings == [], report.lines()


# --- several findings in one function ---------------------------------------------------------------

def test_every_finding_of_a_function_is_reported(tmp_path):
    report = aotcheck.check(write(tmp_path, """
        def f(s: str) -> int:
            a = eval(s)
            b = id(a)
            sorted(b)
            return hash(a)
    """), front_end=False)
    got = sorted((f.line, f.rule, f.name, f.function) for f in report.findings)
    assert got == [
        (2, "aot/dynamic-code", "eval", "f"),
        (3, "aot/identity", "id", "f"),
        (4, "aot/builtin", "sorted", "f"),
        (5, "aot/identity", "hash", "f"),
    ]


def test_a_method_finding_names_its_class(tmp_path):
    report = aotcheck.check(write(tmp_path, """
        class C:
            def m(self) -> int:
                return id(self)
    """), front_end=False)
    assert [(f.rule, f.function, f.line) for f in report.findings if f.rule == "aot/identity"] == [
        ("aot/identity", "C", 3)
    ]


# --- the benchmark kernels --------------------------------------------------------------------------

TIER_K_BENCHMARKS = {
    "nbody": ["advance", "energy", "offset_momentum", "main", "__main__"],
    "spectral_norm": ["eval_a", "mul_av", "mul_atv", "mul_atav", "main", "__main__"],
    "fannkuch": ["fannkuch", "main", "__main__"],
}


@pytest.mark.parametrize("benchmark", sorted(TIER_K_BENCHMARKS))
def test_tier_k_benchmarks_are_ready_and_free_of_findings(benchmark):
    report = aotcheck.check(BENCH / f"{benchmark}.py")
    assert not report.blocking, report.lines()
    assert report.findings == [], report.lines()
    status = {s.name: s for s in report.functions}
    for name in TIER_K_BENCHMARKS[benchmark]:
        assert status[name].ready, (name, status[name].reasons)
    assert all(s.ready for s in report.functions), [(s.name, s.reasons) for s in report.functions if not s.ready]


def test_tier_o_and_s_benchmarks_still_report_pending_findings():
    bt = aotcheck.check(BENCH / "binary_trees.py")
    assert not bt.blocking, bt.lines()
    assert not bt.findings, bt.lines()
    assert all(s.ready for s in bt.functions), [(s.name, s.reasons) for s in bt.functions if not s.ready]

    wf = aotcheck.check(BENCH / "wordfreq.py")
    assert wf.blocking
    assert any(f.kind == "pending" and f.tier == "S" for f in wf.findings), wf.lines()


# --- a program of several modules -------------------------------------------------------------------

def test_findings_carry_the_file_they_are_in(tmp_path):
    write(tmp_path, "def twice(n: int) -> int:\n    sorted([n])\n    return n * 2\n", "helper.py")
    write(tmp_path, "X = eval('1')\n", "other.py")
    entry = write(tmp_path, """
        import helper
        import json
        from other import X

        def run(n: int) -> int:
            return helper.twice(n)
    """)
    report = aotcheck.check(entry, front_end=False)
    got = sorted((f.path, f.line, f.rule, f.name) for f in report.findings)
    assert got == [
        ("helper.py", 2, "aot/builtin", "sorted"),
        ("mod.py", 2, "aot/import", "json"),
        ("other.py", 1, "aot/builtin", "Assign"),        # a module-level call is not a constant assignment
        ("other.py", 1, "aot/dynamic-code", "eval"),
    ]


def test_a_sibling_module_that_is_not_imported_is_not_part_of_the_program(tmp_path):
    write(tmp_path, "X = eval('1')\n", "unused.py")
    report = aotcheck.check(write(tmp_path, "def f() -> int:\n    return 1\n"), front_end=False)
    assert report.findings == []


def test_function_statuses_are_listed_per_module(tmp_path):
    write(tmp_path, "def twice(n: int) -> int:\n    return n + n\n", "helper.py")
    entry = write(tmp_path, "import helper\n\ndef run(n: int) -> int:\n    return n\n")
    report = aotcheck.check(entry)
    assert {(s.path, s.name, s.line) for s in report.functions} == {("helper.py", "twice", 1), ("mod.py", "run", 3)}


# --- line mapping -----------------------------------------------------------------------------------

def test_lines_are_those_of_the_original_file(tmp_path):
    report = aotcheck.check(write(tmp_path, """
        x: int = 1

        def a(n: int) -> int:
            return eval(n)

        class C:
            y: int

            def m(self) -> int:
                return exec("1")

        @staticmethod
        def b(n: int) -> int:
            return id(n)

        def c(n: int) -> int:
            return n
    """), front_end=False)
    assert sorted((f.line, f.rule, f.name) for f in report.findings) == [
        (4, "aot/dynamic-code", "eval"),
        (10, "aot/dynamic-code", "exec"),
        (12, "aot/decorator", "staticmethod"),
        (14, "aot/identity", "id"),
    ]


def test_the_mapping_of_the_decorated_copy_is_exact():
    firsts = [3, 7, 13]          # `@compiled` goes above original lines 3, 7 and 13
    # the k-th `@compiled` sits at firsts[k] + k: copy lines 3, 8 and 15 are the inserted ones
    decorated = {1: 1, 2: 2, 4: 3, 5: 4, 6: 5, 7: 6, 9: 7, 10: 8, 14: 12, 16: 13, 17: 14}
    for d, original in decorated.items():
        assert aotcheck._original_line(d, firsts) == original, d


def test_front_end_reasons_use_original_lines(tmp_path):
    report = aotcheck.check(write(tmp_path, """
        def first(n: int) -> int:
            return n

        class C:
            y: int

        def b(n: int) -> int:
            return id(n)
    """))
    status = {s.name: s for s in report.functions}
    assert not status["b"].ready
    assert any("line 8" in r for r in status["b"].reasons), status["b"].reasons


# --- the command line -------------------------------------------------------------------------------

def test_cli_exit_status_and_text(tmp_path, capsys):
    path = write(tmp_path, "def f(s: str) -> int:\n    return eval(s)\n")
    assert main(["aot-check", str(path)]) == 1
    out = capsys.readouterr().out
    assert "mod.py:2: error aot/dynamic-code:" in out
    assert "function f: not ready" in out


def test_cli_clean_kernel_only_module(tmp_path, capsys):
    path = write(tmp_path, "def add(a: float, b: float) -> float:\n    return a + b\n")
    assert main(["aot-check", str(path)]) == 0
    assert "function add: ready" in capsys.readouterr().out


def test_cli_json_matches_the_report(tmp_path, capsys):
    path = write(tmp_path, "def f(s: str) -> int:\n    sorted(s)\n    return eval(s)\n")
    assert main(["aot-check", str(path), "--json"]) == 1
    doc = json.loads(capsys.readouterr().out)
    report = aotcheck.check(path)
    assert doc["findings"] == [
        {"path": f.path, "line": f.line, "kind": f.kind, "tier": f.tier, "rule": f.rule, "name": f.name,
         "message": f.message, "function": f.function} for f in report.findings]
    assert [(f["rule"], f["line"]) for f in doc["findings"]] == [("aot/builtin", 2), ("aot/dynamic-code", 3)]
    assert [s["name"] for s in doc["functions"]] == ["f"]


def test_strict_int_reports_unproven_int_operations(tmp_path):
    path = write(tmp_path, "def f(a: int, b: int) -> int:\n    return a * b\n")
    assert [f for f in aotcheck.check(path).findings if f.rule == "aot/int-unproven"] == []
    strict = [f for f in aotcheck.check(path, strict_int=True).findings if f.rule == "aot/int-unproven"]
    assert [(f.kind, f.line, f.name, f.function) for f in strict] == [("error", 2, "*", "f")]


def test_a_module_that_does_not_parse_is_one_finding(tmp_path):
    report = aotcheck.check(write(tmp_path, "def f(:\n"))
    assert [(f.rule, f.kind) for f in report.findings] == [("aot/front-end", "error")]
