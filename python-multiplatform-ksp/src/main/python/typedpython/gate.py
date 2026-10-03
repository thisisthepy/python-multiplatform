"""The soundness gate (docs/design/typedpython.md §4.2).

A type checker may fall back to `Any` when it is unsure; a compiler cannot. The gate closes that
gap for the files it is given:

    Pyrefly errors, Any-leak kinds included          -> error
    an expression of type Any in user code            -> error (`any-flow`), unless inside cast()
    the §3.1 forbidden list                           -> warning (`checked`) / error (`compiled`)

Only the given files are user code. Their dependencies are type-checked by Pyrefly only as far
as their stubs or sources say; nothing inside them is reported.
"""
import ast
import shutil
import tempfile
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

from typedpython import forbidden, pyrefly, rebinding
from typedpython.diagnostic import Diagnostic, Severity

Mode = Literal["checked", "compiled"]

Position = tuple[int, int]


@dataclass(frozen=True)
class _Run:
    """One user file as Pyrefly sees it: the text it was given and where that text differs."""

    original: str
    text: str  # the source Pyrefly reads: the original, or the probed copy of it
    probes: list[rebinding.Probe]
    remap: "_Remap"


class _Remap:
    """Maps positions in probed text back to the original source.

    A probe inserts `; name` on the line of its assignment, so a column to the right of an
    insertion is `len("; name")` further right than in the original; line numbers never change.
    """

    def __init__(self, probes: Sequence[rebinding.Probe]) -> None:
        self._inserted: dict[int, list[tuple[int, int]]] = {}
        for p in probes:
            self._inserted.setdefault(p.line, []).append((p.column - 2, len(p.name) + 2))

    def to_original(self, line: int, column: int) -> Position | None:
        """The original position, or None when it lies inside an inserted probe load."""
        shift = 0
        for start, length in self._inserted.get(line, ()):
            if column >= start + length:
                shift += length
            elif column >= start:
                return None
        return line, column - shift


def check(
    paths: Sequence[Path],
    mode: Mode = "checked",
    search_paths: Sequence[Path] = (),
) -> list[Diagnostic]:
    runs = _plan([str(Path(p).resolve()) for p in paths])
    forbidden_severity: Severity = "error" if mode == "compiled" else "warning"
    diagnostics: list[Diagnostic] = []

    # One Pyrefly run for everything. When any file has something to probe, it runs on a mirrored
    # package tree holding the probed copies (and the other files unchanged); otherwise on the
    # files themselves.
    with tempfile.TemporaryDirectory(prefix="typedpython-probe-") as work:
        probing = any(r.probes for r in runs.values())
        if probing:
            copies = _mirror(Path(work), {o: r.text for o, r in runs.items()})
            roots = pyrefly.import_roots(list(runs))
            mirror = _Mirror(copies, [((Path(work) / str(i)).resolve(), r)
                                      for i, r in enumerate(roots)])
            report = pyrefly.run(
                [Path(c) for c in copies],
                [*[Path(work) / str(i) for i in range(len(roots))],
                 *sorted({Path(c).parent for c in copies}),
                 *search_paths, *roots],
            )
        else:
            mirror = _Mirror({o: o for o in runs}, [])
            report = pyrefly.run([Path(p) for p in runs], search_paths)
    original_of = mirror.copies
    run_of = {c: runs[o] for c, o in original_of.items()}

    reported: set[tuple[str, int, int]] = set()
    for e in report.errors:
        run = run_of.get(e.path)
        if run is None:
            diagnostics.append(Diagnostic(
                mirror.original(e.path), e.line, e.column, f"pyrefly/{e.kind}", "error", e.message,
            ))
            continue
        position = run.remap.to_original(e.line, e.column)
        if position is None:
            # An error inside an inserted `; name` means the probe itself is wrong.
            diagnostics.append(Diagnostic(
                run.original, e.line, e.column, "internal/probe-error", "error",
                f"pyrefly/{e.kind} inside a probe load (`{e.message}`); this is a gate bug",
            ))
            continue
        diagnostics.append(Diagnostic(
            run.original, *position, f"pyrefly/{e.kind}", "error", e.message,
        ))
        reported.add((run.original, *position))

    for copy, run in run_of.items():
        path = run.original
        try:
            tree = ast.parse(run.text, filename=path)
        except SyntaxError:
            continue  # Pyrefly has already reported the parse error

        # Judged on the probed text: the probe loads are expression statements, so they are
        # discarded values and never become any-flow findings.
        cast_spans = _cast_argument_spans(tree)
        discarded = _discarded_values(tree)
        for expr in report.any_expressions:
            if expr.path != copy:
                continue
            start = run.remap.to_original(expr.line, expr.column)
            if start is None or (path, *start) in reported:
                continue
            if _is_signature(expr.type):
                continue  # a callable whose signature mentions Any; its result is judged on its own
            if ((expr.line, expr.column), (expr.stop_line, expr.stop_column)) in discarded:
                continue  # an expression statement's value goes nowhere
            if any(_inside((expr.line, expr.column), span) for span in cast_spans):
                continue
            diagnostics.append(Diagnostic(
                path, *start, "any-flow", "error",
                f"this expression has type `{expr.type}`; give it a type, or cast() it "
                "where it leaves untyped code",
            ))
            reported.add((path, *start))

        for finding in forbidden.scan(Path(path).read_text(), path):
            diagnostics.append(Diagnostic(
                path, finding.line, finding.column, finding.rule, forbidden_severity,
                finding.message,
            ))

        types = report.expression_types.get(copy, {})
        for f in rebinding.judge(run.probes, types):
            diagnostics.append(Diagnostic(path, f.line, f.column, f.rule, forbidden_severity,
                                          f.message))

    return sorted(diagnostics)


def _plan(paths: Sequence[str]) -> dict[str, _Run]:
    """Each file's text for Pyrefly: probed when it has rebinding/mixed-container candidates."""
    runs: dict[str, _Run] = {}
    for path in paths:
        source = Path(path).read_text()
        probed, probes = source, []
        try:
            tree = ast.parse(source, filename=path)
        except SyntaxError:
            tree = None
        if tree is not None and (wanted := rebinding.candidates(rebinding.assignments(tree))):
            probed, probes = rebinding.probe(source, wanted)
        runs[path] = _Run(path, probed, probes, _Remap(probes))
    return runs


@dataclass(frozen=True)
class _Mirror:
    copies: dict[str, str]  # copy -> original
    bases: list[tuple[Path, Path]]  # (mirrored root, original root)

    def original(self, path: str) -> str:
        """The original path of a file in the mirrored tree (a dependency's, for instance)."""
        for base, root in self.bases:
            try:
                return str(root / Path(path).relative_to(base))
            except ValueError:
                continue
        return path


def _mirror(work: Path, probed: dict[str, str]) -> dict[str, str]:
    """Copy each file's package tree under `work`, with the probed text in place of the file.

    The copy keeps the file's path relative to its import root, so relative imports and imports
    of sibling modules inside the package resolve to the copied tree. Returns copy -> original.
    """
    roots = pyrefly.import_roots(list(probed))
    copies: dict[str, str] = {}
    copied: set[Path] = set()
    for original, text in probed.items():
        root = pyrefly.import_roots([original])[0]
        base = work / str(roots.index(root))
        relative = Path(original).relative_to(root)
        if len(relative.parts) > 1:
            package = root / relative.parts[0]
            if package not in copied:
                shutil.copytree(
                    package, base / relative.parts[0], dirs_exist_ok=True,
                    ignore=shutil.ignore_patterns("__pycache__", ".*"),
                )
                copied.add(package)
        target = base / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
        copies[str(target.resolve())] = original
    return copies


def _cast_argument_spans(tree: ast.Module) -> list[tuple[Position, Position]]:
    """Source spans of the value argument of every `cast(T, value)` / `typing.cast(T, value)`."""
    spans = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call) or len(node.args) != 2:
            continue
        func = node.func
        is_cast = (isinstance(func, ast.Name) and func.id == "cast") or (
            isinstance(func, ast.Attribute) and func.attr == "cast"
            and isinstance(func.value, ast.Name) and func.value.id == "typing"
        )
        value = node.args[1]
        if is_cast and value.end_lineno is not None and value.end_col_offset is not None:
            spans.append((
                (value.lineno, value.col_offset + 1),
                (value.end_lineno, value.end_col_offset + 1),
            ))
    return spans


def _discarded_values(tree: ast.Module) -> set[tuple[Position, Position]]:
    """Spans of values computed and thrown away by an expression statement (`eval(s)` alone)."""
    spans = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Expr):
            value = node.value
            if value.end_lineno is not None and value.end_col_offset is not None:
                spans.add((
                    (value.lineno, value.col_offset + 1),
                    (value.end_lineno, value.end_col_offset + 1),
                ))
    return spans


def _is_signature(spelled: str) -> bool:
    # Pyrefly spells callables as `(x: int) -> str` and overload sets as `Overload[...]`.
    return "->" in spelled or spelled.startswith("Overload[")


def _inside(position: Position, span: tuple[Position, Position]) -> bool:
    start, stop = span
    return start <= position <= stop
