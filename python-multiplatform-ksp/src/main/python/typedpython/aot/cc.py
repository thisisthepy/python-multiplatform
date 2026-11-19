"""Compile the generated C of an AOT build into an executable (SPEC N-17, #448; agent proposal, not
owner-confirmed). The compiler is found by `cbuild` (the one that builds the default mode's
extensions; on Windows MSVC through vswhere), and the floating-point flags are the default mode's:
`-ffp-contract=off -fno-fast-math` for GCC and Clang, `/fp:precise` for MSVC. Nothing here needs a
Python include directory or library: the executable links no CPython."""
from __future__ import annotations

import os
import shlex
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from typedpython import cbuild

RUNTIME_DIR = cbuild.RUNTIME_DIR

# GCC folds a libm call on a constant with an exactly rounded result at compile time, where the C
# library's function (which CPython calls) may differ in the last bit. Naming the functions keeps each
# call a call. (MSVC does not fold them; its flag translation drops these.)
_NO_FOLD = tuple(f"-fno-builtin-{n}" for n in ("sin", "cos", "tan", "exp", "log", "atan2"))
FLAGS: tuple[str, ...] = cbuild.DEFAULT_FLAGS + _NO_FOLD

# TP_SANITIZE=1 (tools/sanitizers.py env, set by the CI sanitizer job): the executable is built with the same
# flags conftest.py adds to every extension TypedPython builds, so its programs run under AddressSanitizer,
# UBSan (a report ends the program) and, where the platform has one, the leak checker. -DTP_CHECK_PROVEN
# makes tp_aot.h stop on a proven operation that overflows. conftest.py wraps `cbuild.compiler`, which
# `command` calls, with the same flags: they are added here only when the compiler command does not already
# carry them, so a run under conftest gets them once.
SANITIZE_FLAGS: tuple[str, ...] = ("-fsanitize=address,undefined", "-fno-sanitize-recover=undefined", "-g",
                                   "-fno-omit-frame-pointer", "-DTP_CHECK_PROVEN")


def sanitizing() -> bool:
    return os.environ.get("TP_SANITIZE") == "1"


def _sanitized(cmd: list[str]) -> list[str]:
    """`cmd` (a GCC or Clang command) with the sanitizer flags, in front of the compiler's own, once."""
    extra = [f for f in SANITIZE_FLAGS if f not in cmd]
    if not extra:
        return cmd
    if sys.platform.startswith("linux") and cmd[:1] != ["env"]:      # keep the ASan preload out of the compiler
        return ["env", "-u", "LD_PRELOAD", *cmd, *extra]
    return [*cmd, *extra]


def compile_executable(c_text: str, out: Path, keep: Path | None = None) -> Path:
    """Compile `c_text` to the executable `out` and return its path."""
    out = Path(out)
    if sys.platform == "win32" and out.suffix.lower() != ".exe":
        out = out.with_name(out.name + ".exe")
    out.parent.mkdir(parents=True, exist_ok=True)
    if keep is not None:
        return _compile(Path(keep), out)
    with tempfile.TemporaryDirectory(prefix="typedpython-aot-cc-") as work:
        c_file = Path(work) / "program.c"
        c_file.write_text(c_text, encoding="utf-8")
        return _compile(c_file, out, obj_dir=Path(work))


def command(c_file: Path, out: Path, obj_dir: Path | None = None) -> tuple[list[str], dict[str, str] | None]:
    """The compiler command line (and the environment to run it in)."""
    if cbuild._is_msvc():
        env = cbuild.msvc_env()
        obj = (obj_dir or c_file.parent) / (c_file.stem + ".obj")
        flags = list(FLAGS) + (["-DTP_CHECK_PROVEN"] if sanitizing() else [])       # (cl.exe has no -fsanitize here)
        cmd = ([cbuild._msvc_cl(env), "/nologo"] + cbuild._msvc_flags(flags)
               + [f"/I{RUNTIME_DIR}", str(c_file), f"/Fo{obj}", f"/Fe{out}"])
        return cmd, env
    cc = cbuild.compiler()
    if sanitizing():
        cc = _sanitized(cc)
    return (cc + list(FLAGS) + [f"-I{RUNTIME_DIR}", str(c_file), "-o", str(out), "-lm"]), None


def library_command(c_file: Path, out: Path, obj_dir: Path | None = None) -> tuple[list[str], dict[str, str] | None]:
    """The compiler command line (and environment) of a shared library (#455; agent proposal, not
    owner-confirmed): the executable's optimisation and floating-point flags, `-DTPA_LIBRARY` (atomic list
    counts, no program-argument state, the export macro), hidden visibility so only the exported symbols are
    visible, and `-shared -fPIC` (`-dynamiclib` with the install name `@rpath/<file name>` on macOS, `/LD`
    on MSVC). `command` is not touched: the executable's line is what it was."""
    flags = list(FLAGS) + ["-DTPA_LIBRARY"]
    if cbuild._is_msvc():
        env = cbuild.msvc_env()
        obj = (obj_dir or c_file.parent) / (c_file.stem + ".obj")
        implib = obj.with_suffix(".lib")            # (the import library and the .exp stay with the object)
        if sanitizing():
            flags.append("-DTP_CHECK_PROVEN")
        cmd = ([cbuild._msvc_cl(env), "/nologo"] + cbuild._msvc_flags(flags)
               + [f"/I{RUNTIME_DIR}", "/LD", str(c_file), f"/Fo{obj}", f"/Fe{out}", "/link", f"/IMPLIB:{implib}"])
        return cmd, env
    cc = cbuild.compiler()
    if sanitizing():
        cc = _sanitized(cc)
    shared = ["-dynamiclib", "-install_name", f"@rpath/{Path(out).name}"] if sys.platform == "darwin" else ["-shared"]
    return (cc + flags + ["-fPIC", "-fvisibility=hidden"] + shared
            + [f"-I{RUNTIME_DIR}", str(c_file), "-o", str(out), "-lm"]), None


def compile_library(c_text: str, out_dir: Path, file_name: str, keep: Path | None = None) -> Path:
    """Compile `c_text` to the shared library `out_dir/file_name` and return its path. With MSVC the compiler
    writes the object, the import library and the export file beside the library, so the build runs in a
    scratch directory and only the library is copied to `out_dir`; with GCC and Clang there is nothing else."""
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / file_name
    with tempfile.TemporaryDirectory(prefix="typedpython-aot-lib-") as work:
        work_dir = Path(work)
        if keep is not None:
            c_file = Path(keep)
        else:
            c_file = work_dir / "library.c"
            c_file.write_text(c_text, encoding="utf-8")
        msvc = cbuild._is_msvc()
        target = work_dir / file_name if msvc else out
        cmd, env = library_command(c_file, target, work_dir)
        run = subprocess.run(cmd, capture_output=True, encoding="oem" if sys.platform == "win32" else None,
                             text=sys.platform != "win32", errors="replace", env=env)
        if run.returncode != 0 or not target.is_file():
            raise cbuild.CBuildError(f"C compiler failed ({run.returncode}): "
                                     f"{subprocess.list2cmdline(cmd) if sys.platform == 'win32' else shlex.join(cmd)}\n"
                                     f"{run.stdout}{run.stderr}")
        if msvc:
            out.unlink(missing_ok=True)
            shutil.copy2(target, out)
    return out


def _compile(c_file: Path, out: Path, obj_dir: Path | None = None) -> Path:
    cmd, env = command(c_file, out, obj_dir)
    # cl.exe writes in the console code page (cbuild.build does the same)
    run = subprocess.run(cmd, capture_output=True, encoding="oem" if sys.platform == "win32" else None,
                         text=sys.platform != "win32", errors="replace", env=env)
    if run.returncode != 0 or not out.is_file():
        raise cbuild.CBuildError(f"C compiler failed ({run.returncode}): "
                                 f"{subprocess.list2cmdline(cmd) if sys.platform == 'win32' else shlex.join(cmd)}\n"
                                 f"{run.stdout}{run.stderr}")
    return out
