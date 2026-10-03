"""Source → gate → frontend → verify → cgen → cbuild (design §4.3, SPEC N-7/N-8, #41).

`compile_module` is the one entry point the CLI, the demo and the build slot use:

1. The gate in `compiled` mode: a module with type errors is not compiled at all.
2. `frontend.lower`: functions outside the IR are left interpreted (`skipped`, with reasons).
3. `verify.verify`: functions the verifier cannot prove are left interpreted too (SPEC N-8 —
   unsafe code is never generated silently).
4. If nothing is left to compile, no C is produced (`extension is None`): the module ships as
   ordinary Python.
5. `cgen.generate` + `cbuild.build`, against the host's CPython or a target's headers.
"""
from dataclasses import dataclass, field
from pathlib import Path
from types import ModuleType

from typedpython import cbuild, cgen, frontend, gate, ir, verify


class CompileError(RuntimeError):
    """The module cannot be compiled at all (it fails the gate)."""


@dataclass
class Result:
    source: Path
    module: ir.Module                       # what was compiled (verified functions only)
    extension: Path | None                  # None: nothing to compile, ship the .py as is
    skipped: dict[str, str] = field(default_factory=dict)   # function -> why it stays interpreted
    diagnostics: list[verify.Diagnostic] = field(default_factory=list)


def compile_module(
    path: Path,
    out_dir: Path,
    *,
    python_include: Path | None = None,
    ext_suffix: str | None = None,
    runtime_dir: Path | None = None,
) -> Result:
    path = Path(path).resolve()
    errors = [d for d in gate.check([path], mode="compiled") if d.severity == "error"]
    if errors:
        listing = "\n".join(f"{d.path}:{d.line}:{d.column}: {d.message} [{d.rule}]" for d in errors)
        raise CompileError(f"{path.name} does not pass the gate in compiled mode:\n{listing}")

    lowered = frontend.lower(path)
    proved, diagnostics = verify.verify(lowered)
    skipped = dict(proved.skipped)
    if not proved.functions:
        return Result(path, proved, None, skipped, diagnostics)

    c_source = cgen.generate(proved, path)
    extension = cbuild.build(
        c_source, path.stem, Path(out_dir),
        runtime_dir=runtime_dir, python_include=python_include, ext_suffix=ext_suffix,
    )
    return Result(path, proved, extension, skipped, diagnostics)


def load(result: Result) -> ModuleType:
    """Import the built extension (or, with nothing compiled, the source) from its path."""
    if result.extension is None:
        import importlib.util
        spec = importlib.util.spec_from_file_location(result.source.stem, result.source)
        assert spec is not None and spec.loader is not None
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    return cbuild.load(result.source.stem, result.extension)
