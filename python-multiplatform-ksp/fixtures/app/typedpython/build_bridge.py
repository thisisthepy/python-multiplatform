"""Builds `tp_kotlin_bridge.py` into a TypedPython extension for the CPython this fixture embeds (#45).

Run by the `buildTypedPythonBridge` task in `python-multiplatform-ksp/fixtures/app/build.gradle.kts` with a *host*
interpreter that has Pyrefly 1.3.2 (the gate needs it; `-PtypedpythonPython`, default
`python-multiplatform-ksp/.venv/bin/python`). The host interpreter only runs the compiler; the
extension is built for the embedded one:

- headers: `include_dir`, from `python-multiplatform`'s `cpythonIncludeDirectories` provider
  (`docs/platforms/python-version-acquisition.md` §7) -- the directory holding `Python.h`;
- extension suffix and flavour: read from that same tree's `_sysconfigdata_*.py`, which sits at
  `<include_dir>/../../lib/<include_dir.name>/` in a python-build-standalone prefix
  (`include/python3.14[t]` beside `lib/python3.14[t]`). It is the file the embedded interpreter's own
  `sysconfig` reads, so `EXT_SUFFIX` there is the suffix its import system accepts. `Py_GIL_DISABLED`
  is cross-checked against `pyconfig.h` in `include_dir`, so headers and suffix cannot come from
  different flavours.

Writes `<out_dir>/<stem><EXT_SUFFIX>` and `<out_dir>/bridge.properties`, which the test reads to
check that the interpreter it runs in is the one the extension was built for.

Fails (exit 1) when the module does not compile in full: a function left interpreted would make
the test compare the interpreter with itself.

usage: build_bridge.py <typedpython package parent> <module.py> <include_dir> <out_dir>
"""
from __future__ import annotations

import re
import sys
from pathlib import Path


def sysconfig_vars(include_dir: Path) -> tuple[Path, dict[str, object]]:
    lib = include_dir.parent.parent / "lib" / include_dir.name
    found = sorted(lib.glob("_sysconfigdata_*.py"))
    if len(found) != 1:
        raise SystemExit(f"build_bridge: expected one _sysconfigdata_*.py in {lib}, found {found}")
    namespace: dict[str, object] = {}
    exec(compile(found[0].read_text(encoding="utf-8"), str(found[0]), "exec"), namespace)
    data = namespace.get("build_time_vars")
    if not isinstance(data, dict):
        raise SystemExit(f"build_bridge: {found[0]} has no build_time_vars dict")
    return found[0], data


def header_gil_disabled(include_dir: Path) -> bool:
    text = (include_dir / "pyconfig.h").read_text(encoding="utf-8", errors="replace")
    return re.search(r"^\s*#\s*define\s+Py_GIL_DISABLED\s+1\b", text, re.MULTILINE) is not None


def header_version(include_dir: Path) -> str:
    text = (include_dir / "patchlevel.h").read_text(encoding="utf-8", errors="replace")
    m = re.search(r'^\s*#\s*define\s+PY_VERSION\s+"([^"]+)"', text, re.MULTILINE)
    if m is None:
        raise SystemExit(f"build_bridge: no PY_VERSION in {include_dir / 'patchlevel.h'}")
    return m.group(1)


def main(argv: list[str]) -> int:
    if len(argv) != 5:
        print(__doc__, file=sys.stderr)
        return 2
    package_parent, module, include_dir, out_dir = (Path(a).resolve() for a in argv[1:])
    sys.path.insert(0, str(package_parent))
    from typedpython import pipeline  # noqa: E402  (needs the path above)

    if not (include_dir / "Python.h").is_file():
        raise SystemExit(f"build_bridge: no Python.h in {include_dir}")
    data_file, data = sysconfig_vars(include_dir)
    suffix = data.get("EXT_SUFFIX")
    if not isinstance(suffix, str) or not suffix:
        raise SystemExit(f"build_bridge: no EXT_SUFFIX in {data_file}")
    gil_disabled = bool(data.get("Py_GIL_DISABLED") or 0)
    if gil_disabled != header_gil_disabled(include_dir):
        raise SystemExit(f"build_bridge: Py_GIL_DISABLED differs between {data_file} ({gil_disabled}) "
                         f"and {include_dir / 'pyconfig.h'}")

    out_dir.mkdir(parents=True, exist_ok=True)
    for stale in out_dir.glob(module.stem + ".*"):
        stale.unlink()
    result = pipeline.compile_module(module, out_dir, python_include=include_dir, ext_suffix=suffix)
    if result.extension is None or result.skipped or result.diagnostics:
        raise SystemExit(f"build_bridge: {module.name} did not compile in full: extension="
                         f"{result.extension}, skipped={result.skipped}, diagnostics={result.diagnostics}")

    compiled = ",".join(f.name for f in result.module.functions)
    (out_dir / "bridge.properties").write_text(
        "\n".join([
            f"extension={result.extension}",
            f"module={module.stem}",
            f"source={module}",
            f"extSuffix={suffix}",
            f"gilDisabled={str(gil_disabled).lower()}",
            f"pythonVersion={header_version(include_dir)}",
            f"includeDir={include_dir}",
            f"compiledFunctions={compiled}",
        ]) + "\n",
        encoding="utf-8",
    )
    print(f"build_bridge: {result.extension} ({compiled}; {suffix}; gilDisabled={gil_disabled})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
