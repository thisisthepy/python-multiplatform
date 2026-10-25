"""Incremental compilation (SPEC N-10, #41): a per-module build cache.

The unit is a module. Its cache key covers the source, the *interface* hashes of the project modules
it imports, the compiler version, the target platform and the build flags. A body-only change in a
dependency leaves that dependency's interface hash unchanged, so dependents stay cached.

The front end, the C back end and the C compiler are passed in as callables, so this file depends on
none of them (only on `ir`, the contract).

The module's relative path is in the key because the C embeds it (#112): the same content moved to
another path must not reuse an extension carrying the old path.

Key encoding (v1): sha256 of the compact, key-sorted JSON of
    {"v": 1, "path": <module path relative to project root, posix>, "source": sha256-hex(source bytes),
     "deps": [[<dep path relative to project root, posix>, <dep interface hash>], ...] sorted,
     "compiler_version": str, "platform": str, "flags": [str, ...] in the given order}
Interface hash (v1): sha256 of the compact, key-sorted JSON of
    {"v": 1, "functions": [[name, [[param name, type value, "array" | "scalar"], ...], return type value], ...]
     sorted by function name, "skipped": sorted skipped names}
Only signatures enter it: no bodies, locals, purity flags, line numbers or skip reasons.

Cache layout: <cache_dir>/entries/<key>/{meta.json, <artefact>} and <cache_dir>/index.json (the last
key components per module, used only to explain a rebuild). An entry is written to a temporary
directory inside cache_dir and renamed into place, so a crash never leaves a half entry under a key.
An entry whose metadata is unreadable or whose artefact is missing or has a different sha256 is a
miss and is rebuilt.
"""
from __future__ import annotations

import ast
import hashlib
import json
import os
import shutil
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from typedpython import ir
from typedpython.pyrefly import import_roots

_V = 1


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _canon(obj) -> bytes:
    return json.dumps(obj, sort_keys=True, separators=(",", ":")).encode()


def interface_hash(module: ir.Module) -> str:
    """sha256 over the compiled functions' signatures and the skipped names, nothing else."""
    functions = []
    for f in sorted(module.functions, key=lambda f: f.name):
        params = [[p.name, p.type.value, "array" if isinstance(p, ir.ArrayParam) else "scalar"]
                  for p in f.params]
        functions.append([f.name, params, f.returns.value])
    return _sha(_canon({"v": _V, "functions": functions, "skipped": sorted(module.skipped)}))


# --- dependencies --------------------------------------------------------------------------------

def _abs(p: Path) -> Path:
    return Path(os.path.abspath(p))


def _within(p: Path, root: Path) -> bool:
    try:
        p.relative_to(root)
        return True
    except ValueError:
        return False


def _find(base: Path, parts: list[str]) -> list[Path]:
    """Files executed by importing dotted `parts` under `base`: each package's __init__ on the way,
    then the module itself (module file or package __init__). Stops at the first missing part."""
    found: list[Path] = []
    cur = base
    for part in parts:
        pkg = cur / part / "__init__.py"
        mod = cur / f"{part}.py"
        if pkg.is_file():
            found.append(pkg)
            cur = cur / part
        elif mod.is_file():
            found.append(mod)
            break
        else:
            break
    return found


def dependencies(path: Path, project_root: Path) -> list[Path]:
    """Project-local modules `path` imports (stdlib and third-party are ignored), in first-seen order."""
    path, project_root = _abs(Path(path)), _abs(Path(project_root))
    try:
        tree = ast.parse(path.read_bytes(), filename=str(path))
    except (SyntaxError, ValueError, OSError):
        return []
    roots = [_abs(r) for r in import_roots([str(path)])]
    if project_root not in roots:
        roots.append(project_root)

    out: list[Path] = []

    def add(found: list[Path]) -> None:
        for p in found:
            p = _abs(p)
            if p != path and _within(p, project_root) and p not in out:
                out.append(p)

    def resolve(parts: list[str], bases: list[Path]) -> list[Path]:
        best: list[Path] = []
        for b in bases:
            got = _find(b, parts)
            if len(got) > len(best):
                best = got
        return best

    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                add(resolve(alias.name.split("."), roots))
        elif isinstance(node, ast.ImportFrom):
            parts = node.module.split(".") if node.module else []
            if node.level:
                base = path.parent
                for _ in range(node.level - 1):
                    base = base.parent
                bases = [base]
                if not parts:
                    add([base / "__init__.py"] if (base / "__init__.py").is_file() else [])
            else:
                bases = roots
            got = resolve(parts, bases) if parts else []
            add(got)
            # `from pkg import mod`: the imported name may itself be a submodule
            for alias in node.names:
                if alias.name == "*":
                    continue
                for b in bases:
                    if parts:
                        sub_base = b
                        for part in parts:
                            sub_base = sub_base / part
                    else:
                        sub_base = b
                    add(_find(sub_base, [alias.name]) if sub_base.is_dir() else [])
    return out


# --- report --------------------------------------------------------------------------------------

@dataclass
class ModuleReport:
    path: Path
    name: str
    status: str                       # "rebuilt" | "reused"
    reasons: list[str] = field(default_factory=list)
    artifact: Path | None = None
    interface: str = ""
    key: str = ""


@dataclass
class BuildReport:
    modules: list[ModuleReport] = field(default_factory=list)

    @property
    def rebuilt(self) -> list[ModuleReport]:
        return [m for m in self.modules if m.status == "rebuilt"]

    @property
    def reused(self) -> list[ModuleReport]:
        return [m for m in self.modules if m.status == "reused"]


# --- cache ---------------------------------------------------------------------------------------

class BuildCache:
    def __init__(self, cache_dir: Path):
        self.dir = Path(cache_dir)
        self.entries = self.dir / "entries"
        self.entries.mkdir(parents=True, exist_ok=True)

    # entries

    def _load(self, key: str) -> dict | None:
        """The entry's metadata plus its artefact path, or None if it is absent or damaged."""
        d = self.entries / key
        try:
            meta = json.loads((d / "meta.json").read_text())
            art = d / meta["artifact"]
            if meta["key"] != key or _sha(art.read_bytes()) != meta["artifact_sha256"]:
                return None
            meta["path"] = art
            return meta
        except (OSError, ValueError, KeyError, TypeError):
            return None

    def _store(self, key: str, name: str, interface: str, c_source: str,
               compile_c: Callable[[str, str, Path], Path]) -> Path:
        tmp = self.dir / f"tmp-{uuid.uuid4().hex}"
        tmp.mkdir()
        try:
            art = Path(compile_c(c_source, name, tmp))
            if art.parent != tmp:                       # the artefact must live in the entry
                shutil.copy2(art, tmp / art.name)
                art = tmp / art.name
            meta = {"key": key, "name": name, "interface": interface, "artifact": art.name,
                    "artifact_sha256": _sha(art.read_bytes())}
            (tmp / "meta.json").write_text(json.dumps(meta))
            final = self.entries / key
            if final.exists():
                shutil.rmtree(final, ignore_errors=True)   # a damaged entry under this key
            try:
                os.rename(tmp, final)
            except OSError:
                if self._load(key) is None:                # lost a race to a valid entry? else fail
                    raise
                shutil.rmtree(tmp, ignore_errors=True)
            return final / art.name
        except BaseException:
            shutil.rmtree(tmp, ignore_errors=True)
            raise

    # index (explains rebuilds only; never decides a hit)

    def _read_index(self) -> dict:
        try:
            data = json.loads((self.dir / "index.json").read_text())
            return data if isinstance(data, dict) else {}
        except (OSError, ValueError):
            return {}

    def _write_index(self, index: dict) -> None:
        tmp = self.dir / f"index-{uuid.uuid4().hex}.tmp"
        tmp.write_text(json.dumps(index, sort_keys=True))
        os.replace(tmp, self.dir / "index.json")

    # build

    def build_project(self, sources: list[Path], project_root: Path, *,
                      lower: Callable[[Path], ir.Module],
                      generate: Callable[[ir.Module, Path], str],
                      compile_c: Callable[[str, str, Path], Path],
                      compiler_version: str, platform: str, flags: list[str]) -> BuildReport:
        root = _abs(Path(project_root))
        paths = [_abs(Path(s)) for s in sources]
        known = set(paths)
        deps = {p: dependencies(p, root) for p in paths}
        order = _topological(paths, deps)
        rel = lambda p: p.relative_to(root).as_posix()

        index = self._read_index()
        interfaces: dict[Path, str] = {}
        by_path: dict[Path, ModuleReport] = {}

        for p in order:
            source_hash = _sha(p.read_bytes())
            dep_hashes: dict[str, str] = {}
            for d in deps[p]:
                if d in interfaces:
                    dep_hashes[rel(d)] = interfaces[d]
                else:      # not compiled here, or in a cycle: its whole source is its interface
                    dep_hashes[rel(d)] = "src:" + _sha(d.read_bytes())
            comps = {"source": source_hash, "deps": dep_hashes, "compiler_version": compiler_version,
                     "platform": platform, "flags": list(flags)}
            key = _sha(_canon({"v": _V, "path": rel(p), "source": source_hash, "deps": sorted(dep_hashes.items()),
                               "compiler_version": compiler_version, "platform": platform,
                               "flags": list(flags)}))
            name = _module_name(p, root)
            hit = self._load(key)
            if hit is not None:
                interfaces[p] = hit["interface"]
                by_path[p] = ModuleReport(p, name, "reused", [], hit["path"], hit["interface"], key)
            else:
                reasons = _why(index.get(rel(p)), comps, (self.entries / key).exists())
                module = lower(p)
                c_source = generate(module, p)
                iface = interface_hash(module)
                art = self._store(key, name, iface, c_source, compile_c)
                interfaces[p] = iface
                by_path[p] = ModuleReport(p, name, "rebuilt", reasons, art, iface, key)
            index[rel(p)] = comps

        self._write_index(index)
        return BuildReport([by_path[p] for p in paths])


def _module_name(p: Path, root: Path) -> str:
    parts = list(p.relative_to(root).with_suffix("").parts)
    if parts[-1] == "__init__" and len(parts) > 1:
        parts.pop()
    return ".".join(parts)


def _why(prev: dict | None, now: dict, entry_dir_exists: bool) -> list[str]:
    if not prev:
        return ["new"]
    reasons: list[str] = []
    if prev.get("source") != now["source"]:
        reasons.append("source")
    old_deps, new_deps = prev.get("deps", {}), now["deps"]
    for d in sorted(set(old_deps) | set(new_deps)):
        if old_deps.get(d) != new_deps.get(d):
            reasons.append(f"dependency interface: {d}")
    for k in ("compiler_version", "platform", "flags"):
        if prev.get(k) != now[k]:
            reasons.append(k)
    return reasons or ["corrupt" if entry_dir_exists else "missing"]


def _topological(paths: list[Path], deps: dict[Path, list[Path]]) -> list[Path]:
    """Dependencies first, input order otherwise; a cycle is broken where it is first re-entered."""
    known, order, state = set(paths), [], {}

    def visit(p: Path) -> None:
        if state.get(p):
            return
        state[p] = 1
        for d in deps[p]:
            if d in known:
                visit(d)
        order.append(p)

    for p in paths:
        visit(p)
    return order
