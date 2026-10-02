"""`python -m typedpython check` exit codes: 0 clean, 1 errors, 2 tool failure."""
import json
import os
import subprocess
import sys
from pathlib import Path

SRC = Path(__file__).parents[2] / "main" / "python"


def run(*args, env_extra=None):
    return run_command("check", *args, env_extra=env_extra)


def run_command(command, *args, env_extra=None):
    env = dict(os.environ, PYTHONPATH=str(SRC), **(env_extra or {}))
    return subprocess.run(
        [sys.executable, "-m", "typedpython", command, *map(str, args)],
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


DEMO = """\
# typedpython: compiled
import sys


def total(n: int) -> float:
    acc: float = 0.0
    for k in range(n):
        acc += k * 0.5
    return acc


def main() -> None:
    print(f"{total(int(sys.argv[1])):.3f}")
"""


def test_demo_runs_both_and_reports_identical_output(write, tmp_path):
    import pytest
    pytest.importorskip("Cython")
    path = write("demo_mod.py", DEMO)
    result = run_command("demo", "--out", tmp_path / "build", path, "1000")
    assert result.returncode == 0, result.stdout + result.stderr
    assert "identical output" in result.stdout
    assert "249750.000" in result.stdout
    assert "speedup" in result.stdout


def test_demo_reports_a_mismatch_as_failure(write, tmp_path, monkeypatch):
    import pytest
    pytest.importorskip("Cython")
    # A module whose output differs run to run cannot be shown identical.
    path = write("demo_bad.py", "# typedpython: compiled\nimport time\n\n\ndef main() -> None:\n    print(time.perf_counter_ns())\n")
    result = run_command("demo", "--out", tmp_path / "build", path)
    assert result.returncode == 1, result.stdout + result.stderr
    assert "differs" in result.stdout
