"""TypedPython AOT mode, first build step (SPEC N-17, #448, #451, #462; agent proposal, not owner-confirmed).

`generate(entry)` returns the C text of a single-module program (scalar functions, native lists of floats
and ints, fixed tuples of scalars, `print` with f-strings, `sys.argv` through `int()`) with an
`if __name__ == "__main__":` entry; `build(entry, out)` compiles it with the platform C compiler into
a standalone executable that contains and loads no CPython. A program the mode cannot compile exactly
as CPython computes it raises `AotError` with EVERY finding of the program, one per construct, in every
function (design: docs/design/typedpython-aot.md).

Ints that are not proved inside 64 bits are compiled (#462, design decision D1 (a), leader 2026-11-16, not
owner-confirmed) as small-int / big-int values of runtime/tp_aot_int.h; `aot/rep.py` says which. `strict_int=True`
(`--strict-int`) keeps the earlier rule: an int operation not proved inside 64 bits is `aot/int-unproven`.
"""
from __future__ import annotations

import ast
import re
import tempfile
from dataclasses import dataclass
from pathlib import Path

from typedpython.aotcheck import Finding


class AotError(Exception):
    """The program is outside what this build step can compile exactly. `findings` is the list of
    `aotcheck.Finding` (path, line, rule, name, message)."""

    def __init__(self, findings: list[Finding]) -> None:
        unique = {(f.path, f.line, f.rule, f.name, f.message): f for f in findings}     # the same finding once
        self.findings = sorted(unique.values(), key=lambda f: (f.line, f.rule, f.name))
        super().__init__("\n".join(format_finding(f) for f in self.findings))


def format_finding(f: Finding) -> str:
    return f"{f.path}:{f.line}: error {f.rule}: {f.message}"


_LINE = re.compile(r"\bline (\d+)")


def _lower(program, library=None, strict_int: bool = False, explain: list | None = None,
           explain_alloc_out: list | None = None
           ) -> tuple[str | None, list[Finding]]:
    """Run the front end and the verifier in their AOT mode on the program's file; emit C or return
    the findings. Every line number is the file's own. `explain` (a list, #465): instead of emitting C, the text of
    `Representation.explain` is appended to it (the groups of `list[int]` names; nothing is generated). `library` (`aot.library`, #455) is None for a program
    with an entry: what `aot-build` has always done. For a library it names the functions to compile (the
    exports and what they reach) and the emitter of the library's C; the entry is then not expected."""
    from typedpython import frontend, verify
    from typedpython.aot import emit as emit_mod
    from typedpython.aot.program import MAIN

    rel = program.rel

    def finding(line: int, rule: str, name: str, message: str, function: str | None) -> Finding:
        return Finding(rel, line, "error", None, rule, name, message, function)

    with tempfile.TemporaryDirectory(prefix="typedpython-aot-") as work:
        copy = Path(work) / program.path.name
        copy.write_text(program.source, encoding="utf-8")
        try:
            lowered = frontend.lower(copy, aot=True)
            proved, diagnostics = verify.verify(lowered, aot=True)
        except Exception as e:      # noqa: BLE001 - reported as a finding, not a crash
            first = (str(e).strip().splitlines() or [""])[0]
            return None, [finding(1, "aot/front-end", type(e).__name__,
                                  f"the front end failed on this program ({type(e).__name__}: {first})", None)]

    if library is None:
        method_names = [
            f"{c.name}.{st.name}"
            for c in program.classes
            for st in c.body
            if isinstance(st, ast.FunctionDef)
        ]
        expected = [f.name for f in program.functions] + method_names + [MAIN]

        def emit_fn(proved, program):
            return emit_mod.emit(proved, program, strict_int=strict_int)
    else:
        expected = [f.name for f in program.functions if f.name in library.reachable]
        emit_fn = library.emit
    if explain is not None:
        import dataclasses

        from typedpython.aot import rep as rep_mod

        keep = None if library is None else library.reachable
        shown = list(getattr(library, "nodes", ()) or ())

        def emit_fn(proved, program):
            module = proved if keep is None else dataclasses.replace(
                proved, functions=tuple(f for f in proved.functions if f.name in keep))
            explain.append(rep_mod.infer(module, strict=strict_int).explain(program, exports=shown))
            return "", []
    elif explain_alloc_out is not None:
        from typedpython.aot import alloc as alloc_mod
        from typedpython.aot import classes as classes_mod
        from typedpython.aot import rep as rep_mod
        from typedpython.aot.rep import TAGGED

        def emit_fn(proved, program):
            reps = rep_mod.infer(proved, strict=strict_int)
            tagged_fields = {k for k, v in reps.field_reps.items() if v == TAGGED}
            class_lines = {c.name: c.lineno for c in program.classes}
            cls_analysis = classes_mod.analyze_classes(proved, program.rel, class_lines, tagged_fields)
            if cls_analysis.findings:
                return None, cls_analysis.findings
            plan = alloc_mod.analyze_alloc(proved, cls_analysis)
            explain_alloc_out.append(plan.explain())
            return "", []
    proved_names = {f.name for f in proved.functions}
    found: list[Finding] = []
    for name in expected:
        if name in proved_names:
            continue
        shown = "__main__" if name == MAIN else name
        mine = [d for d in diagnostics if d.function == name]
        if mine:
            for d in mine:
                found.append(finding(d.source_line, "aot/verify", shown, f"{d.rule}: {d.message}", shown))
            continue
        why = lowered.skipped.get(name) or proved.skipped.get(name) or "the front end did not lower it"
        m = _LINE.search(why)
        line = int(m.group(1)) if m else program.bodies()[name].lineno if name in program.bodies() else 1
        rule = "aot/optional" if "(aot/optional)" in why else "aot/front-end"         # tier S2a (design 4.3.2)
        found.append(finding(line, rule, shown, f"the front end left `{shown}` uncompiled: {why}", shown))
    if found:
        # The functions that did pass are still emitted, to report what the back end finds in them too: a
        # program's findings are all reported at once, not one function's at a time. (A function that calls
        # one of the rejected is rejected with it, so what is emitted here is closed.)
        _, more = emit_fn(proved, program)
        entry_missing = [f for f in more if f.rule == "aot/entry"]
        seen = {(f.line, f.rule, f.name) for f in found}
        found += [f for f in more if f not in entry_missing and (f.line, f.rule, f.name) not in seen]
        return None, found
    return emit_fn(proved, program)


def generate(entry: Path, *, strict_int: bool = False) -> str:
    """The C text for the program whose entry module is `entry`; raises AotError. `strict_int`: an int
    operation that is not proved inside 64 bits is the finding `aot/int-unproven` (no big integers)."""
    from typedpython.aot import program as program_mod

    program = program_mod.analyze(Path(entry))
    if program.findings:
        raise AotError(program.findings)
    c_text, findings = _lower(program, strict_int=strict_int)
    if findings or c_text is None:
        raise AotError(findings)
    return c_text


def explain_lists(entry: Path, *, exports: list[str] | None = None, prefix: str | None = None,
                  strict_int: bool = False) -> str:
    """The diagnostic of `--explain-lists` (#465; agent proposal, not owner-confirmed): the groups of `list[int]` names
    of the program (`exports` and `prefix` given: of the library) with their members, merges, class and reasons, as
    `aot/rep.py` `Representation.explain` writes them. Nothing is generated; raises AotError as `generate` does."""
    from typedpython.aot import program as program_mod

    if exports is not None:
        from typedpython.aot import library

        return library.explain_library(Path(entry), exports=exports, prefix=prefix or "p", strict_int=strict_int)
    program = program_mod.analyze(Path(entry))
    if program.findings:
        raise AotError(program.findings)
    out: list[str] = []
    _, findings = _lower(program, strict_int=strict_int, explain=out)
    if findings or not out:
        raise AotError(findings)
    return out[0]


def explain_alloc(entry: Path, *, strict_int: bool = False) -> str:
    """The diagnostic of `--explain-alloc` (SPEC N-17, #493): every class allocation site in the
    program with its chosen strategy (`virtual`, `region`, `heap-rc` or `heap-gc`) and reason. Nothing is
    generated; raises AotError as `generate` does."""
    from typedpython.aot import program as program_mod

    program = program_mod.analyze(Path(entry))
    if program.findings:
        raise AotError(program.findings)
    out: list[str] = []
    _, findings = _lower(program, strict_int=strict_int, explain_alloc_out=out)
    if findings or not out:
        raise AotError(findings)
    return out[0]


def build(entry: Path, out: Path, *, emit_c: Path | None = None, strict_int: bool = False,
          defines: tuple[str, ...] = ()) -> Path:
    """Compile `entry` to the executable `out` (on Windows `.exe` is added when missing); returns its
    path. `emit_c` also keeps the generated C there. `defines` (PR-O2c): preprocessor symbols for the runtime
    (`TPA_GC_STRESS`, `TPA_GC_STATS`, `TPA_GC_THRESHOLD=n`: the cycle collector's test switches); none by default."""
    from typedpython.aot import cc

    c_text = generate(entry, strict_int=strict_int)
    if emit_c is not None:
        emit_c = Path(emit_c)
        emit_c.parent.mkdir(parents=True, exist_ok=True)
        emit_c.write_text(c_text, encoding="utf-8")
    return cc.compile_executable(c_text, Path(out), keep=emit_c, defines=tuple(defines))


@dataclass(frozen=True)
class LibraryFiles:
    """What `build_library` wrote: the shared library, the C header `<prefix>.h` and the description
    `<prefix>.json` (all in the output directory)."""

    library: Path
    header: Path
    description: Path


def generate_library(module: Path, *, exports: list[str], prefix: str,
                     strict_int: bool = False) -> tuple[str, str, dict]:
    """The C text of the library, its header and its description for `module` (a module with no entry; an
    `if __name__ == "__main__":` block is not compiled) exporting the functions `exports` under `prefix`;
    raises AotError. See `typedpython.aot.library`."""
    from typedpython.aot import library

    return library.generate_library(Path(module), exports=exports, prefix=prefix, strict_int=strict_int)


def build_library(module: Path, out_dir: Path, *, exports: list[str], prefix: str,
                  emit_c: Path | None = None, strict_int: bool = False) -> LibraryFiles:
    """Compile `module` to the shared library `lib<prefix>.so` (`.dylib` on macOS, `<prefix>.dll` on Windows)
    and write `<prefix>.h` and `<prefix>.json` beside it in `out_dir`. `emit_c` also keeps the generated C
    there. See `typedpython.aot.library`."""
    from typedpython.aot import library

    return library.build_library(Path(module), Path(out_dir), exports=exports, prefix=prefix, emit_c=emit_c,
                                 strict_int=strict_int)
