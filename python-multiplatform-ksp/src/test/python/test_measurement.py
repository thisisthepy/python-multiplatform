"""What the gate costs on top of a plain `pyrefly check` (design §7, AGENTS.md §14).

The extra work is the per-expression type report (`--report-pysa`) and the AST pass. The numbers
are printed so a run records them; only a loose sanity bound is asserted.
"""
import subprocess
import sys
import time

import pytest

from typedpython import gate

FUNCTIONS = 300


def synthetic_module() -> str:
    lines = []
    for i in range(FUNCTIONS):
        lines += [
            f"def f{i}(a: int, b: int) -> int:",
            "    total = 0",
            "    for k in range(a):",
            "        total += k * b",
            "    return total",
            "",
        ]
    return "\n".join(lines)


@pytest.mark.measurement
def test_gate_cost_against_plain_pyrefly(write):
    path = write("big.py", synthetic_module())
    lines = synthetic_module().count("\n") + 1

    start = time.perf_counter()
    subprocess.run(
        [sys.executable, "-m", "pyrefly", "check", str(path), "--summary=none"],
        capture_output=True, check=False,
    )
    plain = time.perf_counter() - start

    start = time.perf_counter()
    found = gate.check([path])
    gated = time.perf_counter() - start

    print(f"\n[measure] {lines} lines: plain pyrefly {plain * 1000:.0f} ms, "
          f"gate {gated * 1000:.0f} ms, ratio {gated / plain:.2f}x")
    assert found == []
    assert gated < 60
