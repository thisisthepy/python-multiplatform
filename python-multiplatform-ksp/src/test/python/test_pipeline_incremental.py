"""compile_project: the incremental cache wired into the real pipeline (SPEC N-10, #41).

Real Pyrefly, real C builds. The three-module project: `a` imports `b`; `c` is independent.
"""
import importlib.util
import textwrap
import time
from pathlib import Path

import pytest

from typedpython import cbuild, frontend, pipeline

HEADER = "# typedpython: compiled\n"

B = HEADER + textwrap.dedent("""\
    def twice(n: int) -> int:
        return n * 2
    """)
A = HEADER + textwrap.dedent("""\
    import b


    def inc(n: int) -> int:
        return n + 1
    """)
C = HEADER + textwrap.dedent("""\
    def half(x: float) -> float:
        return x * 0.5
    """)


def interpreted(path: Path):
    spec = importlib.util.spec_from_file_location(path.stem + "_interp", path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


@pytest.fixture
def project(tmp_path):
    root = tmp_path / "proj"
    root.mkdir()
    for name, src in (("a", A), ("b", B), ("c", C)):
        (root / f"{name}.py").write_text(src)
    return root


@pytest.fixture
def counts(monkeypatch):
    """Count calls into the compile steps (the work an unchanged rebuild must not do)."""
    n = {"lower": 0, "build": 0}
    real_lower, real_build = frontend.lower, cbuild.build

    def lower(*a, **k):
        n["lower"] += 1
        return real_lower(*a, **k)

    def build(*a, **k):
        n["build"] += 1
        return real_build(*a, **k)

    monkeypatch.setattr(frontend, "lower", lower)
    monkeypatch.setattr(cbuild, "build", build)
    return n


def run(project, tmp_path):
    return pipeline.compile_project(
        [project / "a.py", project / "b.py", project / "c.py"], project, tmp_path / "cache")


def status(result):
    return {m.path.stem: m.status for m in result.modules}


def test_first_build_compiles_all_and_unchanged_rebuild_does_nothing(project, tmp_path, counts):
    first = run(project, tmp_path)
    assert status(first) == {"a": "rebuilt", "b": "rebuilt", "c": "rebuilt"}
    assert all(m.extension is not None for m in first.modules)
    assert counts == {"lower": 3, "build": 3}

    counts.update(lower=0, build=0)
    start = time.perf_counter()
    second = run(project, tmp_path)
    elapsed = time.perf_counter() - start
    print(f"\nunchanged rebuild of 3 modules: {elapsed:.3f} s (compile steps skipped)")
    assert status(second) == {"a": "reused", "b": "reused", "c": "reused"}
    assert counts == {"lower": 0, "build": 0}
    assert [m.extension for m in second.modules] == [m.extension for m in first.modules]


def test_body_change_rebuilds_that_module_only(project, tmp_path, counts):
    run(project, tmp_path)
    (project / "b.py").write_text(B.replace("n * 2", "n * 3"))
    r = run(project, tmp_path)
    assert status(r) == {"a": "reused", "b": "rebuilt", "c": "reused"}
    assert "source" in next(m for m in r.modules if m.path.stem == "b").reasons


def test_signature_change_rebuilds_dependents(project, tmp_path, counts):
    run(project, tmp_path)
    (project / "b.py").write_text(B.replace("n: int) -> int", "n: int, k: int) -> int")
                                  .replace("n * 2", "n * k"))
    r = run(project, tmp_path)
    assert status(r) == {"a": "rebuilt", "b": "rebuilt", "c": "reused"}
    a = next(m for m in r.modules if m.path.stem == "a")
    assert any("dependency interface" in x for x in a.reasons), a.reasons


def test_compiler_version_change_rebuilds_everything(project, tmp_path, counts, monkeypatch):
    run(project, tmp_path)
    monkeypatch.setattr(pipeline, "COMPILER_VERSION", "something-else")
    r = run(project, tmp_path)
    assert status(r) == {"a": "rebuilt", "b": "rebuilt", "c": "rebuilt"}
    assert all("compiler_version" in m.reasons for m in r.modules)


def test_rebuilt_and_reused_extensions_compute_the_interpreted_results(project, tmp_path, monkeypatch):
    monkeypatch.syspath_prepend(str(project))        # a's `import b` runs at load
    run(project, tmp_path)
    (project / "b.py").write_text(B.replace("n * 2", "n * 3"))
    r = run(project, tmp_path)
    mods = {m.path.stem: m for m in r.modules}
    b = cbuild.load("b", mods["b"].extension)
    assert b.twice(7) == interpreted(project / "b.py").twice(7) == 21
    a = cbuild.load("a", mods["a"].extension)          # reused
    assert a.inc(4) == 5
    c = cbuild.load("c", mods["c"].extension)
    assert c.half(5.0) == interpreted(project / "c.py").half(5.0)


def test_module_with_nothing_compiled_has_no_extension_and_is_reused(tmp_path):
    root = tmp_path / "proj"
    root.mkdir()
    (root / "plain.py").write_text(HEADER + "def f(s: str) -> str:\n    return s + 'x'\n")
    cache = tmp_path / "cache"
    first = pipeline.compile_project([root / "plain.py"], root, cache)
    m = first.modules[0]
    assert m.extension is None and m.status == "rebuilt" and m.skipped
    second = pipeline.compile_project([root / "plain.py"], root, cache)
    assert second.modules[0].status == "reused" and second.modules[0].reasons == []
    assert second.modules[0].extension is None


def test_interface_describes_the_verified_module_not_the_lowered_one(tmp_path, monkeypatch):
    """A function the verifier rejects stays interpreted, so it must not be in the interface."""
    import dataclasses

    from typedpython import incremental, ir

    root = tmp_path / "proj"
    root.mkdir()
    (root / "dep.py").write_text(HEADER + "def ok(n: int) -> int:\n    return n + 1\n")
    real_lower = frontend.lower
    seen = {}

    def lower_with_a_bad_function(path):
        m = real_lower(path)
        good = m.functions[0]
        bad = dataclasses.replace(good, name="bad", returns=ir.Type.F64)   # verify/type: wrong return
        seen["lowered"] = dataclasses.replace(m, functions=(*m.functions, bad))
        return seen["lowered"]

    monkeypatch.setattr(frontend, "lower", lower_with_a_bad_function)
    r = pipeline.compile_project([root / "dep.py"], root, tmp_path / "cache")
    dep = r.modules[0]
    assert "bad" in dep.skipped, dep.skipped
    assert [f.name for f in seen["lowered"].functions] == ["ok", "bad"]
    unverified = incremental.interface_hash(seen["lowered"])
    assert dep.interface != unverified
