"""generate_project: TypedPython caches source -> C, the compile slot builds C -> extension (#113).

Real Pyrefly; the only C build here is the one that proves the emitted `.c` is the same program
compile_project builds.
"""
import textwrap
from pathlib import Path

import pytest

from conftest import decorate_compiled
from typedpython import cbuild, frontend, pipeline

B = decorate_compiled(textwrap.dedent("""\
    def twice(n: int) -> int:
        return n * 2
    """))
A = decorate_compiled(textwrap.dedent("""\
    import b


    def inc(n: int) -> int:
        return n + 1
    """))
PLAIN = "def hello() -> str:\n    return 'hi'\n"


@pytest.fixture
def project(tmp_path):
    root = tmp_path / "proj"
    (root / "pkg").mkdir(parents=True)
    (root / "a.py").write_text(A)
    (root / "b.py").write_text(B)
    (root / "pkg" / "__init__.py").write_text("")
    (root / "pkg" / "plain.py").write_text(PLAIN)
    return root


@pytest.fixture
def counts(monkeypatch):
    n = {"lower": 0, "build": 0}
    real_lower = frontend.lower

    def lower(*a, **k):
        n["lower"] += 1
        return real_lower(*a, **k)

    def build(*a, **k):
        n["build"] += 1
        raise AssertionError("generate_project must not run the C compiler")

    monkeypatch.setattr(frontend, "lower", lower)
    monkeypatch.setattr(cbuild, "build", build)
    return n


def gen(project, cache):
    return pipeline.generate_project(
        [project / "a.py", project / "b.py", project / "pkg" / "plain.py"], project, cache)


def by_stem(result):
    return {m.path.stem: m for m in result.modules}


def test_emits_one_c_per_compiled_module_and_the_runtime_dir(project, tmp_path, counts):
    r = gen(project, tmp_path / "cache")
    m = by_stem(r)
    assert m["a"].c_source.name == "a.c" and m["b"].c_source.name == "b.c"
    assert "PyInit_a" in m["a"].c_source.read_text()
    assert m["a"].extension is None and m["a"].kind == "compiled"
    assert m["plain"].c_source is None and m["plain"].kind == "not_marked"
    assert (r.runtime_include / "tp_runtime.h").is_file()
    assert counts == {"lower": 3, "build": 0}


def test_unchanged_module_hits_with_byte_identical_c(project, tmp_path, counts):
    first = by_stem(gen(project, tmp_path / "cache"))
    c_a = first["a"].c_source.read_bytes()
    second = gen(project, tmp_path / "cache")
    assert {m.path.stem: m.status for m in second.modules} == {"a": "reused", "b": "reused", "plain": "reused"}
    assert by_stem(second)["a"].c_source.read_bytes() == c_a
    assert counts["lower"] == 3                      # nothing lowered again


def test_fresh_cache_gives_byte_identical_c(project, tmp_path):
    one = by_stem(gen(project, tmp_path / "c1"))["a"].c_source.read_bytes()
    two = by_stem(gen(project, tmp_path / "c2"))["a"].c_source.read_bytes()
    assert one == two


def test_body_change_regenerates_only_that_module(project, tmp_path):
    gen(project, tmp_path / "cache")
    (project / "b.py").write_text(B.replace("n * 2", "n + n"))
    st = {m.path.stem: m.status for m in gen(project, tmp_path / "cache").modules}
    assert st == {"a": "reused", "b": "rebuilt", "plain": "reused"}


def test_key_ignores_toolchain(project, tmp_path, monkeypatch):
    gen(project, tmp_path / "cache")
    monkeypatch.setattr(cbuild, "DEFAULT_FLAGS", (*cbuild.DEFAULT_FLAGS, "-DDIFFERENT"))
    monkeypatch.setattr(pipeline.sysconfig, "get_platform", lambda: "other-platform")
    assert all(m.status == "reused" for m in gen(project, tmp_path / "cache").modules)


def test_emitted_c_is_what_compile_project_builds(project, tmp_path):
    c_mod = by_stem(gen(project, tmp_path / "ccache"))["b"]
    built = by_stem(pipeline.compile_project([project / "b.py"], project, tmp_path / "bcache"))["b"]
    so = cbuild.build(c_mod.c_source.read_text(), "b", tmp_path / "slot")
    assert cbuild.load("b", so).twice(21) == 42
    assert built.extension is not None and built.c_source is None
