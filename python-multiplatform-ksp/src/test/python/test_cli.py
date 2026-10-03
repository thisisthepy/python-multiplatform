"""`python -m typedpython check` exit codes: 0 clean, 1 errors, 2 tool failure."""
import json
import os
import subprocess
import sys
from pathlib import Path

SRC = Path(__file__).parents[2] / "main" / "python"


def run(*args, env_extra=None):
    env = dict(os.environ, PYTHONPATH=str(SRC), **(env_extra or {}))
    return subprocess.run(
        [sys.executable, "-m", "typedpython", "check", *map(str, args)],
        capture_output=True, text=True, env=env,
    )


def test_clean_exits_0(write):
    path = write("user.py", "def f(x: int) -> int:\n    return x\n")
    result = run(path)
    assert result.returncode == 0, result.stdout + result.stderr


def test_error_exits_1(write):
    path = write("user.py", "def f(x):\n    return x\n")
    result = run(path)
    assert result.returncode == 1, result.stdout + result.stderr
    assert "implicit-any-parameter" in result.stdout


def test_warnings_alone_exit_0(write):
    path = write("user.py", "def f(s: str) -> None:\n    eval(s)\n")
    assert run(path).returncode == 0
    assert run("--mode", "compiled", path).returncode == 1


def test_json_output(write):
    path = write("user.py", "def f(x):\n    return x\n")
    result = run("--format", "json", path)
    data = json.loads(result.stdout)
    assert {"path", "line", "column", "rule", "severity", "message"} <= set(data[0])


def test_missing_pyrefly_exits_2(write):
    path = write("user.py", "def f(x: int) -> int:\n    return x\n")
    result = run(path, env_extra={"TYPEDPYTHON_PYREFLY": "/nonexistent/pyrefly"})
    assert result.returncode == 2, result.stdout + result.stderr


def test_a_directory_is_checked_file_by_file(write, tmp_path):
    write("proj/pkg/__init__.py", "")
    write("proj/pkg/ok.py", "def f(x: int) -> int:\n    return x\n")
    write("proj/pkg/bad.py", "def g(x):\n    return x\n")
    write("proj/.venv/lib/junk.py", "def h(x):\n    return x\n")      # hidden: skipped
    write("proj/pkg/__pycache__/stale.py", "def k(x):\n    return x\n")  # cache: skipped
    result = run(tmp_path / "proj")
    assert result.returncode == 1, result.stdout + result.stderr
    assert "bad.py" in result.stdout
    assert "junk.py" not in result.stdout and "stale.py" not in result.stdout
