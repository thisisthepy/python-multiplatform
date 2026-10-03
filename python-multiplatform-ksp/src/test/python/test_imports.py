"""User code inside a package must resolve its own imports (defect found 2026-10-03).

The gate writes its Pyrefly config into a temporary directory. Pyrefly infers the import root
from the config's location, so without help a package's relative and absolute self-imports did
not resolve: every value from them became `Unknown` and failed the gate as `any-flow`.
"""
from conftest import errors
from typedpython import gate


def package(write):
    write("src/pkg/__init__.py", "")
    write("src/pkg/b.py", "def make() -> int:\n    return 1\n")


def test_relative_import_inside_a_package(write):
    package(write)
    path = write("src/pkg/a.py", "from .b import make\n\ndef f() -> int:\n    x = make()\n    return x\n")
    assert errors(gate.check([path])) == []


def test_absolute_import_of_the_own_package(write):
    package(write)
    path = write("src/pkg/c.py", "from pkg.b import make\n\ndef f() -> int:\n    return make()\n")
    assert errors(gate.check([path])) == []


def test_sibling_module_without_a_package(write):
    write("flat/helper.py", "def make() -> int:\n    return 1\n")
    path = write("flat/main.py", "from helper import make\n\ndef f() -> int:\n    return make()\n")
    assert errors(gate.check([path])) == []
