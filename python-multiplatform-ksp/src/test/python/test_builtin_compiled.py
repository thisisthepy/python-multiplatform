"""`compiled` is a builtin name (design §2.3): no import, on plain CPython and for the checker."""
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

import pytest

from conftest import errors, rules
from typedpython import gate

PROJECT = Path(__file__).resolve().parents[3]
TMP = PROJECT / ".tmp"
PTH = "typedpython_builtins.pth"


def test_gate_accepts_compiled_without_an_import(write):
    path = write("m.py", """
        @compiled
        def f(x: int) -> int:
            return x + 1

        y: int = f(1)
    """)
    assert errors(gate.check([path])) == []


def test_gate_still_rejects_an_unknown_builtin(write):
    path = write("m.py", """
        @not_a_builtin
        def f(x: int) -> int:
            return x
    """)
    found = errors(gate.check([path]))
    assert "pyrefly/unknown-name" in rules(found)
    assert any("not_a_builtin" in d.message for d in found)


def test_compiled_keeps_the_decorated_type(write):
    path = write("m.py", """
        @compiled
        def f(x: int) -> int:
            return x

        s: str = f(1)
    """)
    assert "pyrefly/bad-assignment" in rules(errors(gate.check([path])))


@pytest.fixture(scope="module")
def installed():
    """The wheel, built and installed into a fresh venv; returns (python, wheel path)."""
    root = TMP / "builtin-venv"
    wheels = TMP / "builtin-wheel"
    for d in (root, wheels):
        shutil.rmtree(d, ignore_errors=True)
    subprocess.run(["uv", "build", "--wheel", "--out-dir", str(wheels), str(PROJECT)],
                   check=True, capture_output=True)
    wheel = next(wheels.glob("typedpython-*.whl"))
    subprocess.run(["uv", "venv", "--python", "3.13", str(root)], check=True, capture_output=True)
    python = root / "bin" / "python"
    subprocess.run(["uv", "pip", "install", "--python", str(python), "--no-deps", str(wheel)],
                   check=True, capture_output=True)
    return python, wheel


def py(python, *args):
    return subprocess.run([str(python), *args], capture_output=True, text=True)


def test_wheel_puts_the_pth_at_its_root(installed):
    _, wheel = installed
    assert PTH in zipfile.ZipFile(wheel).namelist()


def test_decorator_works_with_no_import(installed):
    python, _ = installed
    r = py(python, "-c", "@compiled\ndef f(): return 1\nprint(f())")
    assert (r.returncode, r.stdout.strip()) == (0, "1"), r.stderr


def test_compiled_is_the_identity(installed):
    python, _ = installed
    r = py(python, "-c", "def f(): pass\nprint(compiled(f) is f)")
    assert r.stdout.strip() == "True", r.stderr


def test_the_pth_does_not_overwrite_the_runtime(installed):
    python, _ = installed
    site = next((python.parent.parent / "lib").glob("python3*/site-packages"))
    code = (
        "import builtins, site\n"
        "sentinel = object()\n"
        "builtins.compiled = sentinel\n"
        f"site.addsitedir({str(site)!r})\n"
        "print(builtins.compiled is sentinel)\n"
    )
    r = py(python, "-S", "-c", code)
    assert r.stdout.strip() == "True", r.stderr
