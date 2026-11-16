"""Compile the generated C of an AOT build into an executable (SPEC N-17, #448; agent proposal, not
owner-confirmed). The compiler is found by `cbuild` (the one that builds the default mode's
extensions; on Windows MSVC through vswhere), and the floating-point flags are the default mode's:
`-ffp-contract=off -fno-fast-math` for GCC and Clang, `/fp:precise` for MSVC. Nothing here needs a
Python include directory or library: the executable links no CPython."""
from __future__ import annotations

import shlex
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
        cmd = ([cbuild._msvc_cl(env), "/nologo"] + cbuild._msvc_flags(list(FLAGS))
               + [f"/I{RUNTIME_DIR}", str(c_file), f"/Fo{obj}", f"/Fe{out}"])
        return cmd, env
    return (cbuild.compiler() + list(FLAGS) + [f"-I{RUNTIME_DIR}", str(c_file), "-o", str(out), "-lm"]), None


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
