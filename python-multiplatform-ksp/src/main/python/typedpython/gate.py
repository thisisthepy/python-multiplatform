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
from collections.abc import Sequence
from pathlib import Path
from typing import Literal

from typedpython import forbidden, pyrefly
from typedpython.diagnostic import Diagnostic, Severity

Mode = Literal["checked", "compiled"]

Position = tuple[int, int]


def check(
    paths: Sequence[Path],
    mode: Mode = "checked",
    search_paths: Sequence[Path] = (),
) -> list[Diagnostic]:
    report = pyrefly.run(paths, search_paths)
    diagnostics = [
        Diagnostic(e.path, e.line, e.column, f"pyrefly/{e.kind}", "error", e.message)
        for e in report.errors
    ]
    reported = {(d.path, d.line, d.column) for d in diagnostics}

    forbidden_severity: Severity = "error" if mode == "compiled" else "warning"
    for path in (str(Path(p).resolve()) for p in paths):
        source = Path(path).read_text()
        try:
            tree = ast.parse(source, filename=path)
        except SyntaxError:
            continue  # Pyrefly has already reported the parse error

        cast_spans = _cast_argument_spans(tree)
        discarded = _discarded_values(tree)
        for expr in report.any_expressions:
            if expr.path != path or (expr.path, expr.line, expr.column) in reported:
                continue
            if _is_signature(expr.type):
                continue  # a callable whose signature mentions Any; its result is judged on its own
            if ((expr.line, expr.column), (expr.stop_line, expr.stop_column)) in discarded:
                continue  # an expression statement's value goes nowhere
            if any(_inside((expr.line, expr.column), span) for span in cast_spans):
                continue
            diagnostics.append(Diagnostic(
                path, expr.line, expr.column, "any-flow", "error",
                f"this expression has type `{expr.type}`; give it a type, or cast() it "
                "where it leaves untyped code",
            ))
            reported.add((expr.path, expr.line, expr.column))

        for finding in forbidden.scan(source, path):
            diagnostics.append(Diagnostic(
                path, finding.line, finding.column, finding.rule, forbidden_severity,
                finding.message,
            ))

    return sorted(diagnostics)


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
