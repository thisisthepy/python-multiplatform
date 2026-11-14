"""`python -m typedpython emit-c` (#25 step 1, SPEC N-10): generate_project plus a JSON manifest that
maps onto pypackpack's BundleRequest.nativeModules / notNativeModules. Format: docs/design/typedpython.md
section 4.3.2a.

Real Pyrefly; the C compiler is never run here.
"""
import json
import textwrap
from pathlib import Path

import pytest

from conftest import decorate_compiled
from typedpython import cbuild
from typedpython.__main__ import main

A = decorate_compiled(textwrap.dedent("""\
    def inc(n: int) -> int:
        return n + 1
    """))
PLAIN = "def hello() -> str:\n    return 'hi'\n"
NOTHING = "@compiled\ndef f(s: str) -> str:\n    return s[1:]\n"
BROKEN = "@compiled\ndef f(n: int) -> int:\n    return n + 'x'\n"


@pytest.fixture
def project(tmp_path):
    root = tmp_path / "proj"
    (root / "app").mkdir(parents=True)
    (root / "app" / "__init__.py").write_text("")
    (root / "app" / "fast.py").write_text(A)
    (root / "app" / "plain.py").write_text(PLAIN)
    (root / "app" / "text.py").write_text(NOTHING)
    return root


@pytest.fixture(autouse=True)
def no_c_compiler(monkeypatch):
    def build(*a, **k):
        raise AssertionError("emit-c must not run the C compiler")
    monkeypatch.setattr(cbuild, "build", build)


def emit(project, tmp_path, *paths):
    manifest = tmp_path / "out" / "manifest.json"
    rc = main(["emit-c", *(str(p) for p in (paths or [project])), "--root", str(project),
               "--cache", str(tmp_path / "cache"), "--manifest", str(manifest)])
    return rc, manifest


def test_manifest_lists_every_module_once_in_the_slot_shape(project, tmp_path):
    rc, manifest = emit(project, tmp_path)
    assert rc == 0
    m = json.loads(manifest.read_text())
    assert m["format"] == "typedpython-emit-c" and m["version"] == 1
    assert m["projectRoot"] == project.resolve().as_posix()
    runtime = Path(m["runtimeInclude"])
    assert runtime.is_absolute() and (runtime / "tp_runtime.h").is_file()
    assert m["compilerVersion"]

    native = {n["moduleName"]: n for n in m["nativeModules"]}
    assert list(native) == ["app.fast"]
    fast = native["app.fast"]
    assert set(fast) >= {"moduleName", "sources", "includeDirs", "defines", "file", "status", "skippedItems"}
    assert fast["file"] == "app/fast.py" and fast["defines"] == {}
    assert fast["includeDirs"] == [m["runtimeInclude"]]
    assert len(fast["sources"]) == 1
    c_file = Path(fast["sources"][0])
    assert c_file.is_absolute() and c_file.name == "fast.c" and c_file.is_file()
    assert "PyInit_fast" in c_file.read_text()

    other = {n["module"]: n for n in m["notNativeModules"]}
    assert list(other) == ["app.plain", "app.text"]
    assert other["app.plain"] == {"module": "app.plain", "file": "app/plain.py", "kind": "NOT_MARKED", "items": []}
    text = other["app.text"]
    assert text["kind"] == "NOTHING_COMPILED" and text["items"]
    item = text["items"][0]
    assert set(item) == {"name", "file", "line", "message"}
    assert item["name"] == "f" and item["file"] == "app/text.py" and isinstance(item["line"], int)

    every = [n["moduleName"] for n in m["nativeModules"]] + [n["module"] for n in m["notNativeModules"]]
    assert "app" not in every and "app.__init__" not in every          # a package __init__ is in neither


def test_a_second_run_reuses_the_same_c_files(project, tmp_path):
    _, manifest = emit(project, tmp_path)
    first = json.loads(manifest.read_text())
    c_file = Path(first["nativeModules"][0]["sources"][0])
    before = c_file.read_bytes()
    assert first["nativeModules"][0]["status"] == "rebuilt"
    rc, manifest = emit(project, tmp_path)
    second = json.loads(manifest.read_text())
    assert rc == 0
    assert second["nativeModules"][0]["status"] == "reused"
    assert second["nativeModules"][0]["sources"] == first["nativeModules"][0]["sources"]
    assert c_file.read_bytes() == before
    strip = lambda d: {**d, "nativeModules": [{**n, "status": None} for n in d["nativeModules"]]}
    assert strip(second) == strip(first)


def test_files_given_one_by_one_keep_their_order(project, tmp_path):
    rc, manifest = emit(project, tmp_path, project / "app" / "text.py", project / "app" / "plain.py")
    assert rc == 0
    m = json.loads(manifest.read_text())
    assert m["nativeModules"] == []
    assert [n["module"] for n in m["notNativeModules"]] == ["app.text", "app.plain"]


def test_a_gate_error_exits_1_and_writes_no_manifest(project, tmp_path, capsys):
    (project / "app" / "broken.py").write_text(BROKEN)
    rc, manifest = emit(project, tmp_path)
    assert rc == 1
    assert not manifest.exists()
    assert "typedpython:" in capsys.readouterr().err


def test_no_python_files_exits_2(tmp_path):
    empty = tmp_path / "empty"
    empty.mkdir()
    rc, manifest = emit(empty, tmp_path)
    assert rc == 2 and not manifest.exists()
