from pathlib import Path
import textwrap

import pytest

FIXTURES = Path(__file__).parent / "fixtures"


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
# sanitizer report fails the run (-fno-sanitize-recover=undefined; ASan halts). Nothing else changes.
import os  # noqa: E402

if os.environ.get("TP_SANITIZE") == "1":
    import sys as _sys
    from typedpython import cbuild as _cbuild

    _SAN = ["-fsanitize=address,undefined", "-fno-sanitize-recover=undefined", "-g",
            "-fno-omit-frame-pointer"]
    _orig_compiler = _cbuild.compiler

    def _sanitized_compiler():
        cmd = _orig_compiler() + _SAN
        if _sys.platform.startswith("linux"):       # keep the ASan preload out of the compiler
            cmd = ["env", "-u", "LD_PRELOAD"] + cmd
        return cmd

    _cbuild.compiler = _sanitized_compiler
