"""Build a generated C file (cgen) into a CPython extension with the platform C compiler.

No setuptools, no Cython: one compiler invocation, `-shared -fPIC`, on macOS
`-undefined dynamic_lookup` (the extension resolves the C API from the process that loads it).
The headers and the extension suffix default to the host interpreter's (`sysconfig`), and can be
pointed at the CPython the app embeds (`python_include`, `ext_suffix`).
"""
from __future__ import annotations

import importlib.machinery
import importlib.util
import shlex
import subprocess
import sys
import sysconfig
from pathlib import Path

RUNTIME_DIR = Path(__file__).resolve().parent / "runtime"

# -ffp-contract=off: a fused multiply-add rounds once where CPython rounds twice.
DEFAULT_FLAGS: tuple[str, ...] = (
    "-O2",
    "-ffp-contract=off",
    "-fno-fast-math",
    "-Werror=implicit-function-declaration",
)


class CBuildError(RuntimeError):
    """The C compiler rejected the generated source; the message carries its stderr."""


def _python_include(python_include: Path | None) -> Path:
    if python_include is None:
        return Path(sysconfig.get_paths()["include"])
    p = Path(python_include)
    if (p / "Python.h").is_file():
        return p
    found = sorted(d for d in p.glob("python3*") if (d / "Python.h").is_file())
    if len(found) == 1:
        return found[0]
    raise CBuildError(f"no Python.h in {p} (nor in exactly one python3.*/ below it: {found})")


def compiler() -> list[str]:
    return shlex.split(sysconfig.get_config_var("CC") or "cc")


def build(c_source: str, module_name: str, out_dir: Path, runtime_dir: Path | None = None,
          flags: list[str] | tuple[str, ...] = DEFAULT_FLAGS, python_include: Path | None = None,
          ext_suffix: str | None = None) -> Path:
    """Compile `c_source` to `<out_dir>/<last component of module_name><ext_suffix>`; return it."""
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    stem = module_name.rpartition(".")[2]
    suffix = ext_suffix if ext_suffix is not None else sysconfig.get_config_var("EXT_SUFFIX")
    if not suffix:
        raise CBuildError("no extension suffix (sysconfig EXT_SUFFIX is empty); pass ext_suffix")
    runtime = Path(runtime_dir) if runtime_dir is not None else RUNTIME_DIR
    if not (runtime / "tp_runtime.h").is_file():
        raise CBuildError(f"tp_runtime.h not found in {runtime}")
    include = _python_include(python_include)
    c_file = out_dir / f"{stem}.c"
    c_file.write_text(c_source, encoding="utf-8")
    so = out_dir / f"{stem}{suffix}"
    cmd = compiler() + list(flags) + ["-fPIC", "-shared", f"-I{include}", f"-I{runtime}"]
    if sys.platform == "darwin":
        cmd += ["-undefined", "dynamic_lookup"]
    cmd += [str(c_file), "-o", str(so)]
    run = subprocess.run(cmd, capture_output=True, text=True)
    if run.returncode != 0:
        raise CBuildError(f"C compiler failed ({run.returncode}): {shlex.join(cmd)}\n{run.stderr}")
    return so


def load(module_name: str, path: Path):
    """Import the built extension at `path` as `module_name` (not registered in sys.modules)."""
    loader = importlib.machinery.ExtensionFileLoader(module_name, str(path))
    spec = importlib.util.spec_from_file_location(module_name, str(path), loader=loader)
    if spec is None:
        raise ImportError(f"cannot make a spec for {path}")
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module
