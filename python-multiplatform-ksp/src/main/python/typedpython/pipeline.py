"""Source → gate → frontend → verify → cgen → cbuild (design §4.3, SPEC N-8/N-9, #41).

`compile_module` is the one entry point the CLI, the demo and the build slot use:

1. The gate in `compiled` mode: a module with type errors is not compiled at all.
2. `frontend.lower`: functions outside the IR are left interpreted (`skipped`, with reasons).
3. `verify.verify`: functions the verifier cannot prove are left interpreted too (SPEC N-9,
   unsafe code is never generated silently).
4. If nothing is left to compile, no C is produced (`extension is None`): the module ships as
   ordinary Python.
5. `cgen.generate` + `cbuild.build`, against the host's CPython or a target's headers.
"""
import hashlib
import json
import re
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


def _relative(path: Path, root: Path) -> str:
    """The module's path relative to the project root, posix separators (what the C embeds)."""
    return Path(path).resolve().relative_to(Path(root).resolve()).as_posix()


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
    project_root: Path | None = None,
) -> Result:
    """`project_root`: the root the embedded source path is relative to (#112); default: the source's
    own directory, so the file name alone is embedded."""
    path = Path(path).resolve()
    root = path.parent if project_root is None else Path(project_root).resolve()
    _gate_or_raise(path)

    lowered = frontend.lower(path)
    proved, diagnostics = verify.verify(lowered)
    skipped = dict(proved.skipped)
    if not proved.functions:
        return Result(path, proved, None, skipped, diagnostics)

    c_source = cgen.generate(proved, path, _relative(path, root))
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


@dataclass(frozen=True)
class SkippedItem:
    """A function or class that stays interpreted, and why (pypackpack's native-level report)."""
    name: str
    file: str                               # package-relative, posix
    line: int | None
    message: str                            # without the "line N: " prefix


_LINE_PREFIX = re.compile(r"line (\d+): ")


def skipped_items(skipped: dict[str, str], diagnostics: list[verify.Diagnostic], file: str) -> list[SkippedItem]:
    """Structure `ir.Module.skipped` (name -> text, which stays as it is: the incremental interface
    hash reads its keys only). The front end writes `line N: reason`; the verifier writes `rule:
    message` and records the line in its diagnostic for the function."""
    lines: dict[str, int] = {}
    for d in diagnostics:
        lines.setdefault(d.function, d.source_line)
    out = []
    for name, text in sorted(skipped.items()):
        m = _LINE_PREFIX.match(text)
        if m:
            line, message = int(m.group(1)), text[m.end():]
        else:
            line, message = lines.get(name), text
        out.append(SkippedItem(name, file, line, message))
    return out


@dataclass
class ModuleResult:
    path: Path
    name: str
    extension: Path | None                  # None: nothing compiled, ship the .py as is
    status: str                             # "rebuilt" | "reused"
    reasons: list[str] = field(default_factory=list)       # why it was rebuilt (empty if reused)
    skipped: dict[str, str] = field(default_factory=dict)  # function -> why it stays interpreted
    interface: str = ""
    kind: str = "compiled"                  # "compiled" | "nothing_compiled" | "not_marked"
    skipped_items: list[SkippedItem] = field(default_factory=list)   # `skipped`, structured


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
    verified: dict[Path, list[SkippedItem]] = {}
    verified_module_skipped: dict[Path, dict[str, str]] = {}
    root = Path(project_root).resolve()

    def lower(path: Path) -> ir.Module:
        _gate_or_raise(path)
        proved, diagnostics = verify.verify(frontend.lower(path))
        verified_module_skipped[path] = dict(proved.skipped)
        verified[path] = skipped_items(proved.skipped, diagnostics, _relative(path, root))         # the VERIFIED module: its signatures are what gets compiled
        return proved

    def generate(module: ir.Module, path: Path) -> str:
        return cgen.generate(module, path, _relative(path, root)) if module.functions else ""

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
            record.write_text(json.dumps({
                "skipped": dict(verified_module_skipped[m.path]),
                "items": [vars(i) for i in verified[m.path]]}))
        try:
            data = json.loads(record.read_text())
            skipped = dict(data["skipped"])
            items = [SkippedItem(**i) for i in data["items"]]
        except (OSError, ValueError, KeyError, TypeError):
            skipped, items = {}, []
        ext = None if m.artifact is None or m.artifact.suffix == _NO_EXTENSION else m.artifact
        kind = ("not_marked" if not frontend.is_opted_in(m.path)
                else "compiled" if ext is not None else "nothing_compiled")
        out.append(ModuleResult(m.path, m.name, ext, m.status, list(m.reasons), skipped, m.interface,
                                kind, items))
    return ProjectResult(out)
