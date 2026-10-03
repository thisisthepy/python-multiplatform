"""Source → gate → frontend → verify → cgen → cbuild (design §4.3, SPEC N-8/N-9, #41).

`compile_module` is the one entry point the CLI, the demo and the build slot use:

1. The gate in `compiled` mode: a module with type errors is not compiled at all.
2. `frontend.lower`: functions outside the IR are left interpreted (`skipped`, with reasons).
3. `verify.verify`: functions the verifier cannot prove are left interpreted too (SPEC N-9 —
   unsafe code is never generated silently).
4. If nothing is left to compile, no C is produced (`extension is None`): the module ships as
   ordinary Python.
5. `cgen.generate` + `cbuild.build`, against the host's CPython or a target's headers.
"""
import hashlib
import json
import sysconfig
from dataclasses import dataclass, field
from pathlib import Path
from types import ModuleType

from typedpython import cbuild, cgen, frontend, gate, incremental, ir, verify


class CompileError(RuntimeError):
    """The module cannot be compiled at all (it fails the gate)."""


@dataclass
class Result:
    source: Path
    module: ir.Module                       # what was compiled (verified functions only)
    extension: Path | None                  # None: nothing to compile, ship the .py as is
    skipped: dict[str, str] = field(default_factory=dict)   # function -> why it stays interpreted
    diagnostics: list[verify.Diagnostic] = field(default_factory=list)


def _gate_or_raise(path: Path) -> None:
    errors = [d for d in gate.check([path], mode="compiled") if d.severity == "error"]
    if errors:
        listing = "\n".join(f"{d.path}:{d.line}:{d.column}: {d.message} [{d.rule}]" for d in errors)
        raise CompileError(f"{path.name} does not pass the gate in compiled mode:\n{listing}")


def compile_module(
    path: Path,
    out_dir: Path,
    *,
    python_include: Path | None = None,
    ext_suffix: str | None = None,
    runtime_dir: Path | None = None,
) -> Result:
    path = Path(path).resolve()
    _gate_or_raise(path)

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


# --- incremental project build (SPEC N-10) ----------------------------------------------------------

def _tree_hash(*dirs: Path) -> str:
    h = hashlib.sha256()
    for d in dirs:
        for f in sorted(p for p in Path(d).rglob("*") if p.is_file() and "__pycache__" not in p.parts):
            h.update(f.relative_to(d).as_posix().encode() + b"\0" + f.read_bytes() + b"\0")
    return h.hexdigest()


_PACKAGE = Path(__file__).resolve().parent

# What the compiler is: every file of this package (front end, verifier, C back end, runtime header,
# builtin stubs). Any change to any of them can change the C produced for an unchanged source, so it
# invalidates the whole cache. Content-based, not a release number: it needs no manual bump and is
# right for a working tree; the cost is a rebuild after any edit to the compiler, which is correct.
COMPILER_VERSION = _tree_hash(_PACKAGE)

_NO_EXTENSION = ".noext"        # cache artefact of a module with nothing compiled


@dataclass
class ModuleResult:
    path: Path
    name: str
    extension: Path | None                  # None: nothing compiled, ship the .py as is
    status: str                             # "rebuilt" | "reused"
    reasons: list[str] = field(default_factory=list)       # why it was rebuilt (empty if reused)
    skipped: dict[str, str] = field(default_factory=dict)  # function -> why it stays interpreted
    interface: str = ""


@dataclass
class ProjectResult:
    modules: list[ModuleResult]

    @property
    def rebuilt(self) -> list[ModuleResult]:
        return [m for m in self.modules if m.status == "rebuilt"]


def compile_project(
    sources: list[Path],
    project_root: Path,
    cache_dir: Path,
    *,
    python_include: Path | None = None,
    ext_suffix: str | None = None,
    runtime_dir: Path | None = None,
) -> ProjectResult:
    sources = [Path(s).resolve() for s in sources]
    cache_dir = Path(cache_dir)
    verified: dict[Path, ir.Module] = {}

    def lower(path: Path) -> ir.Module:
        _gate_or_raise(path)
        proved, _ = verify.verify(frontend.lower(path))
        verified[path] = proved          # the VERIFIED module: its signatures are what gets compiled
        return proved

    def generate(module: ir.Module, path: Path) -> str:
        return cgen.generate(module, path) if module.functions else ""

    def compile_c(c_source: str, name: str, out_dir: Path) -> Path:
        if not c_source:
            marker = Path(out_dir) / (name.rpartition(".")[2] + _NO_EXTENSION)
            marker.write_text("nothing compiled\n")
            return marker
        return cbuild.build(c_source, name, out_dir, runtime_dir=runtime_dir, flags=cbuild.DEFAULT_FLAGS,
                            python_include=python_include, ext_suffix=ext_suffix)

    version = COMPILER_VERSION
    if runtime_dir is not None:
        version += ":" + _tree_hash(Path(runtime_dir))
    suffix = ext_suffix if ext_suffix is not None else sysconfig.get_config_var("EXT_SUFFIX")
    include = cbuild._python_include(python_include)
    flags = [*cbuild.DEFAULT_FLAGS, f"-I{include}"]

    cache = incremental.BuildCache(cache_dir)
    report = cache.build_project(
        sources, project_root, lower=lower, generate=generate, compile_c=compile_c,
        compiler_version=version, platform=f"{sysconfig.get_platform()} {suffix}", flags=flags)

    side = cache_dir / "skipped"
    side.mkdir(exist_ok=True)
    out: list[ModuleResult] = []
    for m in report.modules:
        record = side / f"{m.key}.json"
        if m.status == "rebuilt":
            record.write_text(json.dumps(dict(verified[m.path].skipped)))
        try:
            skipped = json.loads(record.read_text())
        except (OSError, ValueError):
            skipped = {}
        ext = None if m.artifact is None or m.artifact.suffix == _NO_EXTENSION else m.artifact
        out.append(ModuleResult(m.path, m.name, ext, m.status, list(m.reasons), skipped, m.interface))
    return ProjectResult(out)
