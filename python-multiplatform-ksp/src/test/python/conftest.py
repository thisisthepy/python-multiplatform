import ast
import builtins
import sys
from pathlib import Path
import textwrap

import pytest

# The `typedpython_builtins.pth` of an installed wheel; the test interpreter has no such install.
builtins.compiled = getattr(builtins, "compiled", lambda f, /: f)

# Prepended to every child-process script that loads a module whose source uses `@compiled`: a plain
# CPython has the builtin only when `typedpython_builtins.pth` is installed, so the child installs it.
COMPILED_SHIM = "import builtins\nif not hasattr(builtins, 'compiled'):\n    builtins.compiled = lambda f: f\n"

FIXTURES = Path(__file__).parent / "fixtures"


def decorate_compiled(source: str) -> str:
    """`source` with `@compiled` put above every module-level def / class (above its other decorators),
    the set a whole-module mark used to cover. A source that does not parse is returned unchanged."""
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return source
    lines = source.split("\n")
    firsts = [min([n.lineno] + [d.lineno for d in n.decorator_list]) for n in tree.body
              if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef))]
    for first in reversed(firsts):
        lines.insert(first - 1, "@compiled")
    return "\n".join(lines)


@pytest.fixture
def write(tmp_path):
    """Write a dedented source file under tmp_path and return its path."""

    def _write(name: str, source: str) -> Path:
        path = tmp_path / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(textwrap.dedent(source))
        return path

    return _write


def rules(diagnostics):
    return sorted({d.rule for d in diagnostics})


def errors(diagnostics):
    return [d for d in diagnostics if d.severity == "error"]


# TP_SANITIZE=1 (tools/sanitizers.py): every extension TypedPython builds gets ASan + UBSan, and a
# sanitizer report fails the run (-fno-sanitize-recover=undefined; ASan halts). It also defines
# TP_CHECK_PROVEN: an op the verifier proved runs the checked helper and raises SystemError if the
# proof was wrong (#148), so this run catches a wrong proof that the default build would let wrap.
import os  # noqa: E402

if os.environ.get("TP_SANITIZE") == "1":
    import sys as _sys
    from typedpython import cbuild as _cbuild

    _SAN = ["-fsanitize=address,undefined", "-fno-sanitize-recover=undefined", "-g",
            "-fno-omit-frame-pointer", "-DTP_CHECK_PROVEN"]
    _orig_compiler = _cbuild.compiler

    def _sanitized_compiler():
        cmd = _orig_compiler() + _SAN
        if _sys.platform.startswith("linux"):       # keep the ASan preload out of the compiler
            cmd = ["env", "-u", "LD_PRELOAD"] + cmd
        return cmd

    _cbuild.compiler = _sanitized_compiler


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_makereport(item, call):
    """Windows only: Pyrefly's pysa type report panics at `pyrefly\\lib\\report\\pysa.rs:300:22`
    ("Failed to create definitions file ... The system cannot find the path specified", seen on
    windows-latest with Pyrefly's `--report-pysa`). That is Pyrefly crashing, not TypedPython
    misbehaving, so a test that reaches it is skipped with that reason instead of failed. No other
    platform and no other PyreflyError is touched."""
    outcome = yield
    report = outcome.get_result()
    if sys.platform == "win32" and call.when == "call" and call.excinfo is not None:
        text = str(call.excinfo.value)
        if "pysa.rs:300" in text and "Failed to create definitions file" in text:
            report.outcome = "skipped"
            report.longrepr = (str(item.path), item.location[1] or 0,
                               "Skipped: Pyrefly panics in pysa.rs:300:22 on Windows (Failed to create definitions file)")
