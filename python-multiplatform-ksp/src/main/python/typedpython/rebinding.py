"""Rebinding a name to a different type, and mixed-element containers (design §3.1).

Pyrefly 1.3.2 reports neither, and its per-expression type report has no entries for assignment
targets. It does record every *load* of a name, with the name's type at that point. So the gate
writes a probed copy of the file in which each assignment of interest is followed, on the same
line, by a bare load of each target (`x = 1` becomes `x = 1; x`), runs Pyrefly on the copy, and
reads the target types back at the probe positions. Line numbers do not change, and every column
before the insertion point stays where it was.
"""
import ast
import re
from collections.abc import Iterable
from dataclasses import dataclass, field

# Container types whose element type should be one type, not a union. Tuples are excluded: a
# fixed-length tuple is typed per slot, so `tuple[int, str]` is not "mixed".
MIXABLE = re.compile(r"^(list|set|frozenset|dict)\[(.*)\]$", re.DOTALL)
LITERAL = re.compile(r"^Literal\[(.*)\]$", re.DOTALL)


@dataclass(frozen=True)
class Assignment:
    """One assignment to a plain name, in the original source."""

    scope: int
    name: str
    line: int
    column: int
    end_line: int
    end_byte: int  # ast end_col_offset of the whole statement (UTF-8 bytes)
    augmented: bool
    display: bool  # the value is a list/set/dict display with more than one element


@dataclass(frozen=True)
class Probe:
    name: str
    line: int
    column: int
    assignment: Assignment | None = field(default=None, compare=False)


@dataclass(frozen=True)
class Finding:
    rule: str
    line: int
    column: int
    message: str


def assignments(tree: ast.Module) -> list[Assignment]:
    """Assignments to unannotated names, scope by scope (module, class bodies, functions)."""
    found: list[Assignment] = []
    for scope_id, scope in enumerate(_scopes(tree)):
        declared = _declared_names(scope)
        for stmt in _own_statements(scope):
            found += [a for a in _assignments_in(stmt, scope_id) if a.name not in declared]
    return found


def candidates(found: Iterable[Assignment]) -> list[Assignment]:
    """The assignments worth probing: names assigned more than once, and container displays."""
    found = list(found)
    counts: dict[tuple[int, str], int] = {}
    for a in found:
        counts[(a.scope, a.name)] = counts.get((a.scope, a.name), 0) + 1
    return [a for a in found if counts[(a.scope, a.name)] > 1 or a.display]


def probe(source: str, wanted: Iterable[Assignment] | None = None) -> tuple[str, list[Probe]]:
    """Insert a load of each target after its statement; `wanted=None` probes every assignment."""
    if wanted is None:
        wanted = assignments(ast.parse(source))
    lines = source.splitlines(keepends=True)
    # Insert right-to-left within a line so earlier offsets stay valid.
    by_position: dict[tuple[int, int], list[Assignment]] = {}
    for a in wanted:
        by_position.setdefault((a.end_line, a.end_byte), []).append(a)

    probes: list[Probe] = []
    for (line_no, end_byte) in sorted(by_position, reverse=True):
        encoded = lines[line_no - 1].encode()
        head, tail = encoded[:end_byte].decode(), encoded[end_byte:].decode()
        inserted = ""
        added: list[Probe] = []
        for a in by_position[(line_no, end_byte)]:
            inserted += "; "
            added.append(Probe(a.name, line_no, len(head) + len(inserted) + 1, a))
            inserted += a.name
        lines[line_no - 1] = head + inserted + tail
        # Probes already placed further right on this line moved by what was just inserted.
        probes = [
            Probe(q.name, q.line, q.column + len(inserted), q.assignment)
            if q.line == line_no and q.column > len(head) else q
            for q in probes
        ]
        probes += added

    return "".join(lines), probes


def judge(probes: list[Probe], types: dict[tuple[int, int], str]) -> list[Finding]:
    """Turn probed types into findings."""
    findings: list[Finding] = []
    history: dict[tuple[int, str], tuple[str, Assignment]] = {}

    for p in sorted(probes, key=lambda p: (p.line, p.column)):
        a = p.assignment
        spelled = types.get((p.line, p.column))
        if a is None or spelled is None or _is_unknown(spelled):
            continue

        if a.display and (mixed := _mixed_element(spelled)):
            findings.append(Finding(
                "forbidden/mixed-container", a.line, a.column,
                f"`{a.name}` is `{spelled}`: its elements mix `{mixed}`; annotate the union "
                "on purpose or keep one element type",
            ))

        key = (a.scope, a.name)
        current = _base(spelled)
        if key in history and history[key][0] != current:
            before = history[key][0]
            findings.append(Finding(
                "forbidden/rebinding", a.line, a.column,
                f"`{a.name}` was `{before}` and is rebound to `{current}`; annotate it "
                f"(`{a.name}: {before} | {current}`) or use another name",
            ))
        history[key] = (current, a)

    return findings


# --- AST helpers -------------------------------------------------------------------------------

Scope = ast.Module | ast.ClassDef | ast.FunctionDef | ast.AsyncFunctionDef


def _scopes(tree: ast.Module) -> list[Scope]:
    return [tree] + [
        n for n in ast.walk(tree)
        if isinstance(n, (ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef))
    ]


def _own_statements(scope: Scope) -> Iterable[ast.stmt]:
    """Statements of this scope, descending into blocks but not into nested scopes."""
    stack = list(reversed(scope.body))
    while stack:
        stmt = stack.pop()
        yield stmt
        if isinstance(stmt, (ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        for name in ("body", "orelse", "finalbody"):
            stack.extend(reversed(getattr(stmt, name, [])))
        for handler in getattr(stmt, "handlers", []):
            stack.extend(reversed(handler.body))
        for case in getattr(stmt, "cases", []):
            stack.extend(reversed(case.body))


def _declared_names(scope: Scope) -> set[str]:
    """Names whose type is declared (annotated, parameters) or owned elsewhere (global)."""
    names: set[str] = set()
    if isinstance(scope, (ast.FunctionDef, ast.AsyncFunctionDef)):
        args = scope.args
        for arg in [*args.posonlyargs, *args.args, *args.kwonlyargs]:
            names.add(arg.arg)
        names |= {a.arg for a in (args.vararg, args.kwarg) if a is not None}
    for stmt in _own_statements(scope):
        if isinstance(stmt, ast.AnnAssign) and isinstance(stmt.target, ast.Name):
            names.add(stmt.target.id)
        elif isinstance(stmt, (ast.Global, ast.Nonlocal)):
            names |= set(stmt.names)
    return names


def _assignments_in(stmt: ast.stmt, scope_id: int) -> list[Assignment]:
    if isinstance(stmt, ast.Assign):
        targets = [t for target in stmt.targets for t in _names(target)]
        display = len(stmt.targets) == 1 and isinstance(stmt.targets[0], ast.Name) \
            and _is_display(stmt.value)
        augmented = False
    elif isinstance(stmt, ast.AugAssign) and isinstance(stmt.target, ast.Name):
        targets, display, augmented = [stmt.target], False, True
    else:
        return []
    assert stmt.end_lineno is not None and stmt.end_col_offset is not None
    return [
        Assignment(scope_id, t.id, t.lineno, t.col_offset + 1,
                   stmt.end_lineno, stmt.end_col_offset, augmented, display)
        for t in targets
    ]


def _names(target: ast.expr) -> list[ast.Name]:
    if isinstance(target, ast.Name):
        return [target]
    if isinstance(target, (ast.Tuple, ast.List)):
        return [n for element in target.elts for n in _names(element)]
    if isinstance(target, ast.Starred):
        return _names(target.value)
    return []


def _is_display(value: ast.expr) -> bool:
    if isinstance(value, (ast.List, ast.Set)):
        return len(value.elts) > 1
    if isinstance(value, ast.Dict):
        return len(value.keys) > 1
    return False


# --- type spelling -----------------------------------------------------------------------------

def _is_unknown(spelled: str) -> bool:
    return spelled in ("Unknown", "typing.Any", "Any")


def _base(spelled: str) -> str:
    """`Literal[1]` -> `int`, `Literal['a']` -> `str`, `Literal[True]` -> `bool`."""
    m = LITERAL.match(spelled)
    if not m:
        return spelled
    value = m.group(1).strip()
    if value in ("True", "False"):
        return "bool"
    if value[:1] in ("'", '"'):
        return "str"
    if value[:1] in ("b",) and value[1:2] in ("'", '"'):
        return "bytes"
    return "int"


def _mixed_element(spelled: str) -> str | None:
    m = MIXABLE.match(spelled)
    if not m:
        return None
    for argument in _split_top_level(m.group(2)):
        if len(_split_top_level(argument, "|")) > 1:
            return argument.strip()
    return None


def _split_top_level(text: str, separator: str = ",") -> list[str]:
    parts, depth, current = [], 0, ""
    for ch in text:
        if ch in "[(":
            depth += 1
        elif ch in "])":
            depth -= 1
        if ch == separator and depth == 0:
            parts.append(current)
            current = ""
        else:
            current += ch
    parts.append(current)
    return parts
