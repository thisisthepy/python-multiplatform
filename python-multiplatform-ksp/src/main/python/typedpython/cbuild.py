"""Build a generated C file (cgen) into a CPython extension with the platform C compiler.

No setuptools, no Cython: one compiler invocation, `-shared -fPIC`, on macOS
`-undefined dynamic_lookup` (the extension resolves the C API from the process that loads it).
On Windows the compiler is MSVC (`cl /LD`, linked against the interpreter's import library); see
`msvc_command` and `msvc_env`.
The headers and the extension suffix default to the host interpreter's (`sysconfig`), and can be
pointed at the CPython the app embeds (`python_include`, `ext_suffix`).
"""
from __future__ import annotations

import importlib.machinery
import importlib.util
import os
import platform
import re
import shlex
import shutil
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


# The same intent for cl.exe: /O2; /fp:precise (no fast-math; contraction is off by default);
# /we4013 makes an implicit function declaration an error; _CRT_SECURE_NO_WARNINGS silences the
# C4996 deprecation of getenv and friends; /std:c11 for the C11 the generated code may use.
MSVC_DEFAULT_FLAGS: tuple[str, ...] = (
    "/O2",
    "/fp:precise",
    "/we4013",
    "/std:c11",
    "/D_CRT_SECURE_NO_WARNINGS",
)

# Components cl.exe needs from a Visual Studio installation (vswhere -requires).
MSVC_COMPONENT = "Microsoft.VisualStudio.Component.VC.Tools.x86.x64"


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
    cc = sysconfig.get_config_var("CC")
    if not cc and sys.platform == "win32":
        return ["cl"]
    return shlex.split(cc or "cc")


def _is_msvc() -> bool:
    """MSVC drives the build on Windows, or wherever the configured compiler is cl.exe."""
    if sys.platform == "win32":
        return True
    cc = compiler()
    return bool(cc) and Path(cc[0]).stem.lower() == "cl"


def _msvc_flags(flags: list[str] | tuple[str, ...]) -> list[str]:
    """Translate a GCC-style flag list for cl.exe: `/flag` passes through, `-D`, `-U` and `-I`
    become `/D`, `/U` and `/I`, and every other `-flag` is GCC/Clang-only and dropped. The GCC
    default list maps to MSVC_DEFAULT_FLAGS."""
    if tuple(flags) == DEFAULT_FLAGS:
        return list(MSVC_DEFAULT_FLAGS)
    out: list[str] = []
    rest = list(flags)
    if tuple(rest[:len(DEFAULT_FLAGS)]) == DEFAULT_FLAGS:   # the defaults plus extra flags
        out = list(MSVC_DEFAULT_FLAGS)
        rest = rest[len(DEFAULT_FLAGS):]
    for f in rest:
        if f.startswith("/"):
            out.append(f)
        elif f[:2] in ("-D", "-U", "-I"):
            out.append("/" + f[1:])
    return out


def msvc_libs_dir(include: Path) -> Path:
    """The directory with the interpreter's import library (python3NN.lib, python3NNt.lib for the
    free-threaded build): `<prefix>\\libs`. For an include directory of another CPython
    (`<root>\\include`) its sibling `libs` wins."""
    sibling = Path(include).parent / "libs"
    if sibling.is_dir():
        return sibling
    return Path(sys.base_prefix) / "libs"


def free_threaded(ext_suffix: str | None = None) -> bool:
    """Whether the target CPython is the free-threaded build: the `t` ABI tag of an explicit
    extension suffix (`.cp315t-win_amd64.pyd`), else the host interpreter's `Py_GIL_DISABLED`."""
    if ext_suffix:
        return re.search(r"\.cp\d+t-", ext_suffix) is not None
    return bool(sysconfig.get_config_var("Py_GIL_DISABLED"))


def msvc_command(cl: str, c_file: Path, so: Path, include: Path, runtime: Path,
                 flags: list[str] | tuple[str, ...] = DEFAULT_FLAGS,
                 libs_dir: Path | None = None, gil_disabled: bool | None = None) -> list[str]:
    """`cl /nologo <flags> /LD /I<include> /I<runtime> <c_file> /Fo<obj> /link /LIBPATH:<libs> /OUT:<so>`.

    The object file goes next to the source; `/OUT` names the extension (`<name><EXT_SUFFIX>`,
    .pyd on Windows). Pure: no filesystem or process access beyond `libs_dir` when it is None.

    On a free-threaded CPython `/DPy_GIL_DISABLED=1` is added: the Windows pyconfig.h does not
    define it, and without it `#pragma comment(lib, ...)` auto-links python3NN.lib instead of
    python3NNt.lib (LNK1104 on a free-threaded install, which has only the latter).
    """
    libs = libs_dir if libs_dir is not None else msvc_libs_dir(include)
    obj = Path(c_file).with_suffix(".obj")
    ft = free_threaded() if gil_disabled is None else gil_disabled
    gil = ["/DPy_GIL_DISABLED=1"] if ft else []
    return ([cl, "/nologo"] + _msvc_flags(flags) + gil
            + ["/LD", f"/I{include}", f"/I{runtime}", str(c_file), f"/Fo{obj}",
               "/link", f"/LIBPATH:{libs}", f"/OUT:{so}"])


_UNSET = object()
_msvc_env_cache: object = _UNSET


def _vswhere_path() -> Path:
    base = os.environ.get("ProgramFiles(x86)") or os.environ.get("ProgramFiles") or r"C:\Program Files (x86)"
    return Path(base) / "Microsoft Visual Studio" / "Installer" / "vswhere.exe"


def parse_env_dump(text: str) -> dict[str, str]:
    """Parse the `set` output of a vcvars run (`NAME=value` per line; banner lines are skipped)."""
    env: dict[str, str] = {}
    for line in text.splitlines():
        name, eq, value = line.partition("=")
        if eq and name and not name.startswith(("[", " ")):
            env[name] = value
    return env


def _find_vcvars() -> Path:
    vswhere = _vswhere_path()
    if not vswhere.is_file():
        raise CBuildError(
            "MSVC not found: cl.exe is not on PATH and vswhere.exe is missing "
            f"({vswhere}). Install Visual Studio or the Build Tools with the 'Desktop development "
            f"with C++' workload ({MSVC_COMPONENT}), or run from a 'x64 Native Tools' prompt.")
    run = subprocess.run(
        [str(vswhere), "-latest", "-products", "*", "-requires", MSVC_COMPONENT,
         "-property", "installationPath"], capture_output=True, text=True)
    root = run.stdout.strip().splitlines()[0].strip() if run.returncode == 0 and run.stdout.strip() else ""
    if not root:
        raise CBuildError(
            f"MSVC not found: vswhere.exe found no Visual Studio installation with {MSVC_COMPONENT} "
            "(install the 'Desktop development with C++' workload).")
    script = "vcvarsarm64.bat" if platform.machine().lower() in ("arm64", "aarch64") else "vcvars64.bat"
    vcvars = Path(root) / "VC" / "Auxiliary" / "Build" / script
    if not vcvars.is_file():
        raise CBuildError(f"MSVC not found: {vcvars} does not exist (Visual Studio at {root}).")
    return vcvars


def msvc_env() -> dict[str, str] | None:
    """The environment to run cl.exe in; None when cl.exe is already on PATH (use the current one).

    Otherwise: vswhere.exe locates the newest Visual Studio with the C++ tools, vcvars64.bat is run
    and the environment it leaves is captured. Cached per process.
    """
    global _msvc_env_cache
    if _msvc_env_cache is not _UNSET:
        return _msvc_env_cache  # type: ignore[return-value]
    if shutil.which("cl"):
        _msvc_env_cache = None
        return None
    vcvars = _find_vcvars()
    env = dict(os.environ, VSCMD_SKIP_SENDTELEMETRY="1")
    run = subprocess.run(f'cmd /u /c "{vcvars}" && set', capture_output=True, env=env)
    out = run.stdout.decode("utf-16-le", errors="replace")
    captured = parse_env_dump(out)
    if run.returncode != 0 or not any(k.upper() == "INCLUDE" for k in captured):
        raise CBuildError(f"{vcvars} failed ({run.returncode}) or did not set INCLUDE:\n{out[-2000:]}")
    _msvc_env_cache = captured
    return captured


def _msvc_cl(env: dict[str, str] | None) -> str:
    cl = shutil.which("cl", path=None if env is None else next(
        (v for k, v in env.items() if k.upper() == "PATH"), None))
    if not cl:
        raise CBuildError("MSVC not found: cl.exe is not on PATH (after vcvars)")
    return cl


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
    if _is_msvc():
        env = msvc_env()
        cmd = msvc_command(_msvc_cl(env), c_file, so, include, runtime, flags,
                           gil_disabled=free_threaded(ext_suffix))
        run = subprocess.run(cmd, capture_output=True, text=True, env=env)
        if run.returncode != 0 or not so.is_file():
            # cl.exe reports its errors on stdout
            raise CBuildError(f"C compiler failed ({run.returncode}): {subprocess.list2cmdline(cmd)}\n"
                              f"{run.stdout}{run.stderr}")
        return so
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
