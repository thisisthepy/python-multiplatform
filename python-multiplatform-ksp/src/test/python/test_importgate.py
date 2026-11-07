"""Import-time type gate (SPEC N-7b, #183): development-mode import hook."""
from __future__ import annotations

import importlib
import sys

import pytest

from typedpython import gate, importgate

BAD = """\
from pathlib import Path
Path(__file__).with_name("marker.txt").write_text("ran")

def f(x: int) -> int:
    return x

f("not an int")
"""

GOOD = "VALUE: int = 1\n"


@pytest.fixture
def project(tmp_path, monkeypatch):
    """A fresh source root on sys.path; the hook and imported modules are cleaned up afterwards."""
    root = tmp_path / "src"
    root.mkdir()
    monkeypatch.syspath_prepend(str(root))
    before = set(sys.modules)
    yield root
    importgate.uninstall()
    for name in set(sys.modules) - before:
        del sys.modules[name]
    importlib.invalidate_caches()


@pytest.fixture
def calls(monkeypatch):
    """Paths handed to the checker, one list per checker invocation (the seam)."""
    seen: list[list[str]] = []
    real = importgate._run_gate

    def counting(paths, search_paths):
        seen.append([str(p) for p in paths])
        return real(paths, search_paths)

    monkeypatch.setattr(importgate, "_run_gate", counting)
    return seen


def test_type_error_stops_import_before_module_code_runs(project):
    (project / "bad_mod.py").write_text(BAD)
    importgate.install([project])
    with pytest.raises(ImportError) as info:
        importlib.import_module("bad_mod")
    assert isinstance(info.value, importgate.ImportGateError)
    assert info.value.diagnostics
    assert all(d.severity == "error" for d in info.value.diagnostics)
    assert "bad_mod.py" in str(info.value)
    assert not (project / "marker.txt").exists()
    assert "bad_mod" not in sys.modules


def test_same_module_imports_without_the_hook(project):
    (project / "bad_mod2.py").write_text(BAD)
    importlib.import_module("bad_mod2")
    assert (project / "marker.txt").exists()


def test_uninstall_restores_normal_import(project):
    (project / "bad_mod3.py").write_text(BAD)
    importgate.install([project])
    importgate.uninstall()
    importlib.import_module("bad_mod3")
    assert (project / "marker.txt").exists()


def test_clean_module_imports_and_is_cached_by_content(project, calls):
    path = project / "good_mod.py"
    path.write_text(GOOD)
    importgate.install([project])
    mod = importlib.import_module("good_mod")
    assert mod.VALUE == 1
    assert len(calls) == 1

    del sys.modules["good_mod"]
    importlib.import_module("good_mod")
    assert len(calls) == 1, "an unchanged module must not be checked again"

    del sys.modules["good_mod"]
    # a different length: CPython's .pyc check compares mtime in whole seconds and the size, so a
    # same-size rewrite within one second would run the stale bytecode (CPython's own behaviour)
    path.write_text("VALUE: int = 22\n")
    assert importlib.import_module("good_mod").VALUE == 22
    assert len(calls) == 2, "changed content must be re-checked"


def test_failing_result_is_cached_too(project, calls):
    (project / "bad_mod4.py").write_text(BAD)
    importgate.install([project])
    for _ in range(2):
        with pytest.raises(importgate.ImportGateError):
            importlib.import_module("bad_mod4")
    assert len(calls) == 1


def test_modules_outside_source_roots_and_stdlib_are_not_checked(project, tmp_path, monkeypatch, calls):
    other = tmp_path / "deps"
    other.mkdir()
    (other / "dep_mod.py").write_text(BAD.replace("marker.txt", "dep_marker.txt"))
    monkeypatch.syspath_prepend(str(other))
    importgate.install([project])
    importlib.import_module("dep_mod")  # a type error, but outside the roots
    importlib.import_module("colorsys")  # stdlib
    assert calls == []
    assert (other / "dep_marker.txt").exists()


def test_package_submodule_under_root_is_checked(project, calls):
    pkg = project / "pkg"
    pkg.mkdir()
    (pkg / "__init__.py").write_text("")
    (pkg / "sub.py").write_text("def f(x):\n    return x\n")
    importgate.install([project])
    with pytest.raises(importgate.ImportGateError):
        importlib.import_module("pkg.sub")
    assert any(p.endswith("sub.py") for c in calls for p in c)


def test_install_twice_installs_one_finder(project):
    importgate.install([project])
    importgate.install([project])
    assert sum(isinstance(f, importgate.ImportGateFinder) for f in sys.meta_path) == 1
    importgate.uninstall()
    assert not any(isinstance(f, importgate.ImportGateFinder) for f in sys.meta_path)


def test_install_from_env(project, monkeypatch):
    monkeypatch.delenv(importgate.ENV_VAR, raising=False)
    assert importgate.install_from_env() is False
    monkeypatch.setenv(importgate.ENV_VAR, str(project))
    assert importgate.install_from_env() is True
    (project / "bad_mod5.py").write_text(BAD)
    with pytest.raises(importgate.ImportGateError):
        importlib.import_module("bad_mod5")
