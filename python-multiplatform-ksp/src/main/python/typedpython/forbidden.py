"""The forbidden list (docs/design/typedpython.md §3.1), found by walking the AST.

These are the dynamic features that make a static type unreliable at compile time. The gate
reports them as warnings for `checked` code and as errors for `compiled` code; this module only
finds them.

Rebinding a name to a different type and containers that mix element types need inferred types
for assignment targets; they live in typedpython.rebinding.
"""
import ast
from dataclasses import dataclass

DYNAMIC_ATTRIBUTE_CALLS = {"getattr", "setattr", "delattr"}
# Index of the attribute-name argument in each of the calls above.
NAME_ARGUMENT = 1


@dataclass(frozen=True)
class Finding:
    rule: str
    line: int
    column: int
    message: str


def scan(source: str, path: str) -> list[Finding]:
    tree = ast.parse(source, filename=path)
    patchable = _module_level_modules_and_classes(tree)
    findings: list[Finding] = []

    for node in ast.walk(tree):
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
            findings += _check_call(node, node.func.id, patchable)
        elif isinstance(node, (ast.Assign, ast.AugAssign, ast.AnnAssign, ast.Delete)):
            for target in _targets(node):
                if _is_patch_target(target, patchable):
                    assert isinstance(target, ast.Attribute) and isinstance(target.value, ast.Name)
                    findings.append(_finding(
                        "forbidden/monkeypatch", target,
                        f"`{target.value.id}.{target.attr}` changes a module or class from outside it",
                    ))

    return sorted(findings, key=lambda f: (f.line, f.column, f.rule))


def _check_call(call: ast.Call, name: str, patchable: set[str]) -> list[Finding]:
    if name in ("eval", "exec"):
        return [_finding(f"forbidden/{name}", call, f"`{name}` runs code the checker cannot see")]
    if name not in DYNAMIC_ATTRIBUTE_CALLS or len(call.args) <= NAME_ARGUMENT:
        return []

    attribute = call.args[NAME_ARGUMENT]
    if not (isinstance(attribute, ast.Constant) and isinstance(attribute.value, str)):
        return [_finding(
            "forbidden/dynamic-attr", call,
            f"`{name}` with a computed attribute name hides the attribute from the checker",
        )]

    # A literal name is static, but setattr/delattr on a module or class is still a patch.
    receiver = call.args[0]
    if name != "getattr" and isinstance(receiver, ast.Name) and receiver.id in patchable:
        return [_finding(
            "forbidden/monkeypatch", call,
            f"`{name}` on `{receiver.id}` changes a module or class from outside it",
        )]
    return []


def _module_level_modules_and_classes(tree: ast.Module) -> set[str]:
    """Names bound at module scope to an imported module/name or to a class definition."""
    names: set[str] = set()
    for node in tree.body:
        if isinstance(node, ast.Import):
            names |= {(alias.asname or alias.name).split(".")[0] for alias in node.names}
        elif isinstance(node, ast.ImportFrom):
            names |= {alias.asname or alias.name for alias in node.names if alias.name != "*"}
        elif isinstance(node, ast.ClassDef):
            names.add(node.name)
    return names


def _targets(node: ast.stmt) -> list[ast.expr]:
    if isinstance(node, ast.Assign):
        return [t for target in node.targets for t in _flatten(target)]
    if isinstance(node, ast.Delete):
        return [t for target in node.targets for t in _flatten(target)]
    if isinstance(node, (ast.AugAssign, ast.AnnAssign)):
        return [node.target]
    return []


def _flatten(target: ast.expr) -> list[ast.expr]:
    if isinstance(target, (ast.Tuple, ast.List)):
        return [t for element in target.elts for t in _flatten(element)]
    if isinstance(target, ast.Starred):
        return _flatten(target.value)
    return [target]


def _is_patch_target(target: ast.expr, patchable: set[str]) -> bool:
    return (
        isinstance(target, ast.Attribute)
        and isinstance(target.value, ast.Name)
        and target.value.id in patchable
    )


def _finding(rule: str, node: ast.expr, message: str) -> Finding:
    # ast columns are 0-based; Pyrefly's (and every editor's) are 1-based.
    return Finding(rule, node.lineno, node.col_offset + 1, message)
