"""TypedPython AOT mode, first build step (SPEC N-17, #448; agent proposal, not owner-confirmed).

`generate(entry)` returns the C text of a single-module program of scalar functions with an
`if __name__ == "__main__":` entry; `build(entry, out)` compiles it with the platform C compiler into
a standalone executable that contains and loads no CPython. A program the mode cannot compile exactly
as CPython computes it raises `AotError` with one finding per construct (design:
docs/design/typedpython-aot.md).
"""
from __future__ import annotations

import ast
import re
import tempfile
from pathlib import Path

from typedpython.aotcheck import Finding


class AotError(Exception):
    """The program is outside what this build step can compile exactly. `findings` is the list of
    `aotcheck.Finding` (path, line, rule, name, message)."""

    def __init__(self, findings: list[Finding]) -> None:
        self.findings = sorted(findings, key=lambda f: (f.line, f.rule, f.name))
        super().__init__("\n".join(format_finding(f) for f in self.findings))


def format_finding(f: Finding) -> str:
    return f"{f.path}:{f.line}: error {f.rule}: {f.message}"


_LINE = re.compile(r"\bline (\d+)")


def _lower(program) -> tuple[str | None, list[Finding]]:
    """Run the front end and the verifier on the program's text; emit C or return the findings."""
    from typedpython import frontend, verify
    from typedpython.aot import emit as emit_mod
    from typedpython.aot.program import MAIN, PRINT_PREFIX
    from typedpython.aotcheck import _decorate, _original_line, _remap_text

    text, firsts = _decorate(program.edited, ast.parse(program.edited))
    rel = program.rel

    def finding(line: int, rule: str, name: str, message: str, function: str | None) -> Finding:
        return Finding(rel, line, "error", None, rule, name, message, function)

    with tempfile.TemporaryDirectory(prefix="typedpython-aot-") as work:
        copy = Path(work) / program.path.name
        copy.write_text(text, encoding="utf-8")
        try:
            lowered = frontend.lower(copy)
            proved, diagnostics = verify.verify(lowered)
        except Exception as e:      # noqa: BLE001 - reported as a finding, not a crash
            first = (str(e).strip().splitlines() or [""])[0]
            return None, [finding(1, "aot/front-end", type(e).__name__,
                                  f"the front end failed on this program ({type(e).__name__}: {first})", None)]

    expected = [f.name for f in program.functions] + [MAIN] + list(program.prints)
    proved_names = {f.name for f in proved.functions}
    found: list[Finding] = []
    for name in expected:
        if name in proved_names:
            continue
        shown = "__main__" if name == MAIN else name
        mine = [d for d in diagnostics if d.function == name]
        if mine:
            for d in mine:
                found.append(finding(_original_line(d.source_line, firsts), "aot/verify", shown,
                                     _remap_text(f"{d.rule}: {d.message}", firsts), shown))
            continue
        why = lowered.skipped.get(name) or proved.skipped.get(name) or "the front end did not lower it"
        why = _remap_text(why, firsts)
        m = _LINE.search(why)
        line = int(m.group(1)) if m else program.bodies()[name].lineno if name in program.bodies() else 1
        what = "print" if name.startswith(PRINT_PREFIX) else shown
        found.append(finding(line, "aot/front-end", what, f"the front end left `{what}` uncompiled: {why}", shown))
    if found:
        return None, found
    return emit_mod.emit(proved, program, firsts)


def generate(entry: Path) -> str:
    """The C text for the program whose entry module is `entry`; raises AotError."""
    from typedpython.aot import program as program_mod

    program = program_mod.analyze(Path(entry))
    if program.findings:
        raise AotError(program.findings)
    c_text, findings = _lower(program)
    if findings or c_text is None:
        raise AotError(findings)
    return c_text


def build(entry: Path, out: Path, *, emit_c: Path | None = None) -> Path:
    """Compile `entry` to the executable `out` (on Windows `.exe` is added when missing); returns its
    path. `emit_c` also keeps the generated C there."""
    from typedpython.aot import cc

    c_text = generate(entry)
    if emit_c is not None:
        emit_c = Path(emit_c)
        emit_c.parent.mkdir(parents=True, exist_ok=True)
        emit_c.write_text(c_text, encoding="utf-8")
    return cc.compile_executable(c_text, Path(out), keep=emit_c)
