"""Package-relative embedded source path (#112) and the structured per-module report (#128).

Real Pyrefly, real C builds. SPEC N-8 (embedded path), N-10 (cache), pypackpack's native level.
"""
import re
import shutil
import traceback
import textwrap
from pathlib import Path

from typedpython import cbuild, cgen, frontend, pipeline, verify

SRC = "# typedpython: compiled\n" + textwrap.dedent("""\
    def twice(n: int) -> int:
        return n * 2


    def boom(s: str) -> str:
        raise ValueError("boom " + s)
    """)


def make_project(root: Path) -> Path:
    mod = root / "app" / "physics" / "nbody.py"
    mod.parent.mkdir(parents=True)
    (root / "app" / "__init__.py").write_text("")
    (mod.parent / "__init__.py").write_text("")
    mod.write_text(SRC)
    return mod


# --- #112 ----------------------------------------------------------------------------------------

def test_generated_c_is_identical_from_two_absolute_roots_and_embeds_the_relative_path(
        tmp_path, monkeypatch):
    seen = []
    real = cbuild.build

    def build(c_source, *a, **k):
        seen.append(c_source)
        return real(c_source, *a, **k)

    monkeypatch.setattr(cbuild, "build", build)
    roots = [tmp_path / "one" / "rootA", tmp_path / "two" / "deeper" / "rootB"]
    for i, root in enumerate(roots):
        root.mkdir(parents=True)
        mod = make_project(root)
        pipeline.compile_project([mod], root, tmp_path / f"cache{i}")
    assert len(seen) == 2 and seen[0] == seen[1]
    assert 'tp_source_path[] = "app/physics/nbody.py"' in seen[0]
    assert str(tmp_path) not in seen[0]         # nothing else embeds a host path


def test_compile_module_embeds_the_path_relative_to_project_root(tmp_path):
    mod = make_project(tmp_path / "r")
    r = pipeline.compile_module(mod, tmp_path / "o2", project_root=tmp_path / "r")
    ext = cbuild.load("nbody", r.extension)
    try:
        ext.boom("x")
    except ValueError as e:
        assert traceback.extract_tb(e.__traceback__)[-1].filename == "app/physics/nbody.py"
    else:
        raise AssertionError("boom did not raise")
    d = pipeline.compile_module(mod, tmp_path / "o3")       # default root: the file's directory
    try:
        cbuild.load("nbody", d.extension).boom("x")
    except ValueError as e:
        assert traceback.extract_tb(e.__traceback__)[-1].filename == "nbody.py"


def test_default_embedded_path_is_the_file_name(tmp_path):
    mod = make_project(tmp_path / "r")
    lowered, _ = verify.verify(frontend.lower(mod))
    assert 'tp_source_path[] = "nbody.py"' in cgen.generate(lowered, mod)
    assert str(tmp_path) not in cgen.generate(lowered, mod)


def test_a_moved_file_with_the_same_content_does_not_reuse_the_extension(tmp_path):
    root = tmp_path / "p"
    (root / "a").mkdir(parents=True)
    (root / "b").mkdir()
    (root / "a" / "m.py").write_text(SRC)
    cache = tmp_path / "cache"
    first = pipeline.compile_project([root / "a" / "m.py"], root, cache)
    assert first.modules[0].status == "rebuilt"
    shutil.move(root / "a" / "m.py", root / "b" / "m.py")
    second = pipeline.compile_project([root / "b" / "m.py"], root, cache)
    assert second.modules[0].status == "rebuilt"
    ext = cbuild.load("m", second.modules[0].extension)
    try:
        ext.boom("x")
    except ValueError as e:
        assert traceback.extract_tb(e.__traceback__)[-1].filename == "b/m.py"


# --- #128 ----------------------------------------------------------------------------------------

MIXED = "# typedpython: compiled\n" + textwrap.dedent("""\
    def ok(n: int) -> int:
        return n + 1


    def greet(name: str) -> str:
        return "hi " + name
    """)


def build_one(tmp_path, rel, source, cache="cache"):
    root = tmp_path / "proj"
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source)
    r = pipeline.compile_project([path], root, tmp_path / cache)
    return root, path, r.modules[0]


def test_kind_compiled(tmp_path):
    _, _, m = build_one(tmp_path, "pkg/mixed.py", MIXED)
    assert m.kind == "compiled" and m.extension is not None
    [item] = m.skipped_items
    assert item.name == "greet" and item.file == "pkg/mixed.py"
    assert item.line == 7 and not item.message.startswith("line ")
    assert m.skipped["greet"].startswith("line 7: ")        # ModuleResult.skipped unchanged


def test_kind_nothing_compiled_with_structured_items(tmp_path):
    _, _, m = build_one(tmp_path, "pkg/plain.py",
                        "# typedpython: compiled\n\ndef f(s: str) -> str:\n    return s + 'x'\n")
    assert m.kind == "nothing_compiled" and m.extension is None
    [item] = m.skipped_items
    assert (item.name, item.file, item.line) == ("f", "pkg/plain.py", 4)
    assert item.message and not re.match(r"line \d+", item.message)


def test_kind_not_marked_when_there_is_neither_marker_nor_decorator(tmp_path):
    _, _, m = build_one(tmp_path, "pkg/unmarked.py", "def f(x: int) -> int:\n    return x\n")
    assert m.kind == "not_marked" and m.extension is None and m.skipped_items == []


def test_a_compiled_decorator_alone_opts_in(tmp_path):
    src = "@compiled\ndef f(s: str) -> str:\n    return s + 'x'\n"
    _, _, m = build_one(tmp_path, "pkg/deco.py", src)
    assert m.kind == "nothing_compiled"
    assert [(i.name, i.line) for i in m.skipped_items] == [("f", 3)]


def test_class_items_carry_name_and_line(tmp_path):
    src = ("# typedpython: compiled\n\n\nclass Node:\n    __slots__ = ('a',)\n"
           "    def __init__(self, a: int) -> None:\n        self.a = a\n")
    _, _, m = build_one(tmp_path, "pkg/cls.py", src)
    cls = [i for i in m.skipped_items if i.name == "Node"]
    assert cls and cls[0].line == 4 and cls[0].file == "pkg/cls.py"


def test_verifier_skips_carry_a_line_and_no_prefix(tmp_path):
    src = "# typedpython: compiled\n\n\ndef f(x: int) -> int:\n    return g(x)\n\n\ndef g(x: int) -> int:\n    return x\n"
    _, _, m = build_one(tmp_path, "pkg/v.py", src)
    for i in m.skipped_items:
        assert isinstance(i.line, int) and not i.message.startswith("line ")


def test_structure_round_trips_through_the_cache_for_reused_modules(tmp_path):
    root, path, first = build_one(tmp_path, "pkg/mixed.py", MIXED)
    second = pipeline.compile_project([path], root, tmp_path / "cache").modules[0]
    assert second.status == "reused"
    assert second.kind == first.kind == "compiled"
    assert second.skipped_items == first.skipped_items and second.skipped_items
    assert second.skipped == first.skipped


def test_not_marked_and_nothing_compiled_survive_reuse(tmp_path):
    root, path, _ = build_one(tmp_path, "pkg/plain.py",
                              "# typedpython: compiled\n\ndef f(s: str) -> str:\n    return s + 'x'\n")
    again = pipeline.compile_project([path], root, tmp_path / "cache").modules[0]
    assert again.status == "reused" and again.kind == "nothing_compiled" and again.skipped_items
    root2, path2, _ = build_one(tmp_path, "pkg/un.py", "def f(x: int) -> int:\n    return x\n")
    again2 = pipeline.compile_project([path2], root2, tmp_path / "cache").modules[0]
    assert again2.status == "reused" and again2.kind == "not_marked"


def test_cli_build_prints_file_line_name_message_and_not_marked(tmp_path):
    import os
    import subprocess
    import sys
    root = tmp_path / "proj"
    (root / "pkg").mkdir(parents=True)
    (root / "pkg" / "mixed.py").write_text(MIXED)
    (root / "pkg" / "un.py").write_text("def f(x: int) -> int:\n    return x\n")
    src = Path(__file__).parents[2] / "main" / "python"
    out = subprocess.run(
        [sys.executable, "-m", "typedpython", "build", str(root), "--cache", str(tmp_path / "c")],
        capture_output=True, text=True, env=dict(os.environ, PYTHONPATH=str(src)))
    assert out.returncode == 0, out.stdout + out.stderr
    assert re.search(r"^\s*pkg/mixed\.py:7: greet: \S", out.stdout, re.M), out.stdout
    assert re.search(r"pkg/un\.py.*not marked for compilation", out.stdout), out.stdout
