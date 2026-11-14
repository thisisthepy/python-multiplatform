"""Build the Cython columns. Run with the harness venv's python (see ../README in run.py's docstring).

    .venv/bin/python cython/build.py <out_dir>

Writes <out_dir>/pure/<name>.so (the unmodified py/*.py compiled in pure Python mode, annotations
as written) and <out_dir>/typed/<name>_typed.so (cython/*.pyx). Not part of any timed run.
Flags: -O3 -ffp-contract=off (no fused multiply-add, so float results match CPython bit for bit).
"""
import shutil
import sys
from pathlib import Path

from Cython.Build import cythonize
from setuptools import Extension, setup

HERE = Path(__file__).resolve().parent
PY = HERE.parent / "py"
NAMES = {"nbody": "nbody", "spectral_norm": "spectral_norm", "fannkuch": "fannkuch",
         "binary_trees": "binary_trees", "wordfreq": "wordfreq"}
CFLAGS = ["-O3", "-ffp-contract=off"]


def build(out: Path, kind: str) -> None:
    work = out / f"src-{kind}"
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True)
    exts = []
    for name in NAMES:
        if kind == "pure":
            src = work / f"{name}.py"
            shutil.copy(PY / f"{name}.py", src)       # unmodified copy
            mod = name
        else:
            mod = f"{name}_typed"
            src = work / f"{mod}.pyx"
            shutil.copy(HERE / f"{name}.pyx", src)
        exts.append(Extension(mod, [str(src)], extra_compile_args=CFLAGS))
    ext_modules = cythonize(exts, language_level=3, quiet=True, build_dir=str(work / "c"))
    setup(name=f"tp-bench-{kind}", ext_modules=ext_modules, script_name="setup.py",
          script_args=["build_ext", "--build-lib", str(out / kind), "--build-temp", str(work / "obj"), "-q"])


if __name__ == "__main__":
    target = Path(sys.argv[1]).resolve()
    for k in ("pure", "typed"):
        build(target, k)
