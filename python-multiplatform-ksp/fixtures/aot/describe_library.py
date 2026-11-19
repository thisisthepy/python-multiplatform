"""Writes `<prefix>.json`, the description of an AOT library, without compiling any C (SPEC N-17, #458).

Usage: describe_library.py COMPILER_DIR MODULE.py OUT_DIR PREFIX EXPORT,EXPORT,...

It runs `typedpython.aot.library.generate_library`, the front end and the generator that `python -m typedpython
aot-lib` runs first, and writes the same description that command writes beside the library (same call, same
`json.dumps`). The Gradle build uses it so that the Kotlin wrappers can be generated, and the Kotlin sources
compiled, before any C compiler runs; the library itself is built by `aot-lib` as a dependency of the test task.

Everything here is an agent proposal, not owner-confirmed.
"""
import json
import sys
from pathlib import Path


def main(argv: list[str]) -> int:
    compiler_dir, module, out_dir, prefix, exports = argv
    sys.path.insert(0, compiler_dir)
    from typedpython import aot
    from typedpython.aot import library

    try:
        _c_text, _header, description = library.generate_library(
            Path(module), exports=[n.strip() for n in exports.split(",") if n.strip()], prefix=prefix)
    except aot.AotError as e:
        for f in e.findings:
            print(aot.format_finding(f), file=sys.stderr)
        return 1
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    (out / f"{prefix}.json").write_text(json.dumps(description, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
