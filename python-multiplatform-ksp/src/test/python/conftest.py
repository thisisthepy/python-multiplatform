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
