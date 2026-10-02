"""Running Pyrefly and reading what it reports (docs/design/typedpython.md §4.1).

Pyrefly is used as a CLI, not embedded. Two outputs are read:

- its JSON error list, with the Any-leak error kinds raised to `error` by a generated config;
- its per-expression type report (`--report-pysa`), which says which expressions in user code
  have type `Any`. That report is a Pyrefly-versioned format, which is why pyproject.toml pins
  Pyrefly exactly.
"""
import json
import os
import re
import subprocess
import sys
import tempfile
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path

# Pyrefly error kinds that mean "an Any got in". Pyrefly leaves several of them off by default.
ANY_LEAK_KINDS = (
    "implicit-any-parameter",
    "unannotated-return",
    "unknown-variable-type",
    "no-any-return-implicit",
    "no-any-return-explicit",
    "explicit-any",
)

# How Pyrefly spells an Any in the type report: `typing.Any` for an explicit one and `Unknown`
# for an implicit one, alone or inside a larger type such as `list[typing.Any]`.
ANY_IN_TYPE = re.compile(r"(?<![\w.])(?:typing\.Any|Any|Unknown)(?![\w])")


# Where the `typedpython` package itself lives, so `import typedpython` / `@typedpython.compiled`
# in user code always resolves, whatever environment Pyrefly reads site-packages from.
OWN_ROOT = Path(__file__).resolve().parent.parent


class PyreflyError(RuntimeError):
    """Pyrefly could not be run, or its output could not be read."""


@dataclass(frozen=True)
class Error:
    path: str
    line: int
    column: int
    kind: str
    message: str


@dataclass(frozen=True)
class AnyExpression:
    path: str
    line: int
    column: int
    stop_line: int
    stop_column: int
    type: str


@dataclass(frozen=True)
class Report:
    errors: list[Error]
    any_expressions: list[AnyExpression]
    # file -> (line, column) -> type, for every expression the type report records.
    expression_types: dict[str, dict[tuple[int, int], str]]


def command() -> list[str]:
    """The Pyrefly command line: `TYPEDPYTHON_PYREFLY`, or the copy installed next to this Python."""
    override = os.environ.get("TYPEDPYTHON_PYREFLY")
    return [override] if override else [sys.executable, "-m", "pyrefly"]


def run(paths: Sequence[Path], search_paths: Sequence[Path] = ()) -> Report:
    files = [str(Path(p).resolve()) for p in paths]
    with tempfile.TemporaryDirectory(prefix="typedpython-") as work:
        config = Path(work) / "pyrefly.toml"
        config.write_text(_config([*search_paths, *import_roots(files), OWN_ROOT]))
        report_dir = Path(work) / "pysa"
        argv = [
            *command(), "check", *files,
            "--config", str(config),
            "--output-format", "json",
            "--summary=none",
            "--report-pysa", str(report_dir),
            "--report-pysa-format", "json",
        ]
        try:
            result = subprocess.run(argv, capture_output=True, text=True, check=False)
        except OSError as e:
            raise PyreflyError(f"cannot run {argv[0]}: {e}") from e

        # Exit code 1 only means "type errors found"; anything else is a failure of the tool.
        if result.returncode not in (0, 1):
            raise PyreflyError(f"pyrefly exited {result.returncode}: {result.stderr.strip()}")
        try:
            raw_errors = json.loads(result.stdout)["errors"]
        except (json.JSONDecodeError, KeyError) as e:
            raise PyreflyError(f"unreadable pyrefly output: {result.stdout[:200]!r}") from e

        errors = [
            Error(_resolve(e["path"]), e["line"], e["column"], e["name"], e["description"])
            for e in raw_errors
        ]
        types = _expression_types(report_dir, set(files))
        return Report(errors, _any_expressions(types), _types_by_start(types))


def import_roots(files: Sequence[str]) -> list[Path]:
    """The directory each file is imported from: above its outermost package, else its own.

    The config lives in a temporary directory, and Pyrefly would otherwise take that as the import
    root, so a package's own imports (relative or absolute) would not resolve.
    """
    roots: list[Path] = []
    for file in files:
        root = Path(file).parent
        while (root / "__init__.py").exists():
            root = root.parent
        if root not in roots:
            roots.append(root)
    return roots


def _config(search_paths: Sequence[Path]) -> str:
    lines = []
    if search_paths:
        quoted = ", ".join(json.dumps(str(Path(p).resolve())) for p in search_paths)
        lines.append(f"search-path = [{quoted}]")
    lines.append("[errors]")
    lines += [f'{kind} = "error"' for kind in ANY_LEAK_KINDS]
    return "\n".join(lines) + "\n"


def _expression_types(report_dir: Path, files: set[str]) -> list[AnyExpression]:
    """Every typed expression the report records in the given files (typeshed and deps skipped)."""
    try:
        index = json.loads((report_dir / "pyrefly.pysa.json").read_text())
    except (OSError, json.JSONDecodeError) as e:
        raise PyreflyError(f"pyrefly wrote no readable type report: {e}") from e

    found: list[AnyExpression] = []
    for module in index["modules"].values():
        source = module.get("source_path", {}).get("FileSystem")
        if source is None or _resolve(source) not in files:
            continue  # typeshed and dependencies: not user code
        table_file = report_dir / "type_of_expressions" / module["info_filename"]
        if not table_file.exists():
            continue
        table = json.loads(table_file.read_text())
        for function in table["functions"].values():
            types = function["type_table"]
            for location, type_index in function["locations"].items():
                found.append(_expression(_resolve(source), location, types[type_index]["string"]))
    return found


def _any_expressions(expressions: list[AnyExpression]) -> list[AnyExpression]:
    return [e for e in expressions if ANY_IN_TYPE.search(e.type)]


def _types_by_start(expressions: list[AnyExpression]) -> dict[str, dict[tuple[int, int], str]]:
    table: dict[str, dict[tuple[int, int], str]] = {}
    for e in expressions:
        table.setdefault(e.path, {})[(e.line, e.column)] = e.type
    return table


def _expression(path: str, location: str, spelled: str) -> AnyExpression:
    # "6:9-6:15" -> start line/column, stop line/column (1-based, as in Pyrefly's errors).
    start, stop = location.split("-")
    line, column = map(int, start.split(":"))
    stop_line, stop_column = map(int, stop.split(":"))
    return AnyExpression(path, line, column, stop_line, stop_column, spelled)


def _resolve(path: str) -> str:
    return str(Path(path).resolve())
