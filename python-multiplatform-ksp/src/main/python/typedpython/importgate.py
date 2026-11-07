"""Development-mode import gate (SPEC N-7b, #183).

`install(source_roots)` puts a meta path finder first on `sys.meta_path`. When a module whose source
file lies under one of the roots is imported, the finder runs the N-7 gate (`gate.check`, the checker
behind `python -m typedpython check`) on that file before the import machinery executes it. A type
error raises `ImportGateError`, an `ImportError` carrying the diagnostics, and none of the module's
code runs. Modules outside the roots (the stdlib, dependencies) are never checked.

Results are cached in memory by the SHA-256 of the file content, so an unchanged module is not
checked again (a failing result is cached too). Release builds never call `install`.
"""
from __future__ import annotations

import hashlib
import os
import sys
from collections.abc import Sequence
from importlib import machinery
from importlib.abc import MetaPathFinder
from pathlib import Path
from types import ModuleType

from typedpython import gate
from typedpython.diagnostic import Diagnostic

ENV_VAR = "TYPEDPYTHON_IMPORT_GATE"  # source roots separated by os.pathsep


class ImportGateError(ImportError):
    """A project module failed the type gate; `diagnostics` holds the gate's errors."""

    def __init__(self, path: str, diagnostics: Sequence[Diagnostic], name: str | None = None) -> None:
        self.diagnostics = list(diagnostics)
        lines = [f"{d.path}:{d.line}:{d.column}: {d.message} [{d.rule}]" for d in self.diagnostics]
        super().__init__(
            f"type gate rejected {path}; the module was not run:\n" + "\n".join(lines),
            name=name, path=path,
        )


def _run_gate(paths: Sequence[Path], search_paths: Sequence[Path]) -> list[Diagnostic]:
    """The checker seam: tests count its invocations."""
    return gate.check(paths, mode="checked", search_paths=search_paths)


class ImportGateFinder(MetaPathFinder):
    def __init__(self, source_roots: Sequence[Path]) -> None:
        self.roots = [Path(r).resolve() for r in source_roots]
        self._cache: dict[str, tuple[str, list[Diagnostic]]] = {}

    def find_spec(
        self, fullname: str, path: Sequence[str] | None, target: ModuleType | None = None,
    ) -> machinery.ModuleSpec | None:
        spec = machinery.PathFinder.find_spec(fullname, path)
        origin = spec.origin if spec is not None else None
        if spec is None or origin is None or not origin.endswith(".py"):
            return None  # not project source: let the normal finders handle it
        file = Path(origin).resolve()
        if not any(file.is_relative_to(root) for root in self.roots):
            return None
        errors = self._errors(file)
        if errors:
            raise ImportGateError(str(file), errors, name=fullname)
        return spec

    def _errors(self, file: Path) -> list[Diagnostic]:
        digest = hashlib.sha256(file.read_bytes()).hexdigest()
        key = str(file)
        cached = self._cache.get(key)
        if cached is not None and cached[0] == digest:
            return cached[1]
        errors = [d for d in _run_gate([file], self.roots) if d.severity == "error"]
        self._cache[key] = (digest, errors)
        return errors


def install(source_roots: Sequence[str | Path]) -> ImportGateFinder:
    """Install the gate for modules under `source_roots`, replacing an installed one."""
    uninstall()
    finder = ImportGateFinder([Path(r) for r in source_roots])
    sys.meta_path.insert(0, finder)
    return finder


def uninstall() -> None:
    """Remove the gate; imports are then ungated."""
    sys.meta_path[:] = [f for f in sys.meta_path if not isinstance(f, ImportGateFinder)]


def install_from_env() -> bool:
    """Development-mode switch: install when `TYPEDPYTHON_IMPORT_GATE` lists source roots."""
    roots = [r for r in os.environ.get(ENV_VAR, "").split(os.pathsep) if r]
    if not roots:
        return False
    install(roots)
    return True
