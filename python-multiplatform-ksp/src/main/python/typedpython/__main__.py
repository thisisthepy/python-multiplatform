"""`python -m typedpython check [--mode checked|compiled] [--format text|json] PATHS...`

Exit status: 0 no errors (warnings allowed), 1 errors, 2 the gate itself could not run.
"""
import argparse
import json
import sys
from pathlib import Path

from typedpython import gate
from typedpython.paths import python_files
from typedpython.pyrefly import PyreflyError

EXIT_CLEAN, EXIT_ERRORS, EXIT_FAILURE = 0, 1, 2


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(prog="typedpython")
    commands = parser.add_subparsers(dest="command", required=True)
    check = commands.add_parser("check", help="type-check user Python code")
    check.add_argument("paths", nargs="+", type=Path, help="files, or directories to check recursively")
    check.add_argument("--mode", choices=["checked", "compiled"], default="checked")
    check.add_argument("--format", choices=["text", "json"], default="text")
    check.add_argument("--search-path", action="append", type=Path, default=[],
                       help="directory to resolve imports from (repeatable)")
    demo = commands.add_parser(
        "demo", help="compile a module, run its main() interpreted and compiled, and compare",
    )
    demo.add_argument("module", type=Path)
    demo.add_argument("argv", nargs="*", help="sys.argv[1:] for the module's main()")
    demo.add_argument("--out", type=Path, default=Path("build/typedpython"),
                      help="where the extension is built (default: build/typedpython)")
    args = parser.parse_args(argv)

    if args.command == "demo":
        return _demo(args.module, args.argv, args.out)

    try:
        found = gate.check(python_files(args.paths), mode=args.mode, search_paths=args.search_path)
    except PyreflyError as e:
        print(f"typedpython: {e}", file=sys.stderr)
        return EXIT_FAILURE

    if args.format == "json":
        print(json.dumps([d.to_json() for d in found], indent=2))
    else:
        for d in found:
            print(f"{d.severity.upper()} {d.path}:{d.line}:{d.column}: {d.message} [{d.rule}]")

    return EXIT_ERRORS if any(d.severity == "error" for d in found) else EXIT_CLEAN


def _demo(module: Path, module_argv: list[str], out: Path) -> int:
    import contextlib
    import importlib.util
    import io
    import time

    from typedpython import pipeline

    try:
        result = pipeline.compile_module(module, out / module.stem)
    except pipeline.CompileError as e:
        print(f"typedpython: {e}", file=sys.stderr)
        return EXIT_ERRORS
    except PyreflyError as e:
        print(f"typedpython: {e}", file=sys.stderr)
        return EXIT_FAILURE

    def interpreted() -> object:
        spec = importlib.util.spec_from_file_location(module.stem + "_interpreted", module)
        assert spec is not None and spec.loader is not None
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        return mod

    def run(load: object) -> tuple[str, float]:
        mod = load()  # type: ignore[operator]
        saved = sys.argv
        sys.argv = [str(module), *module_argv]
        captured = io.StringIO()
        try:
            with contextlib.redirect_stdout(captured):
                start = time.perf_counter()
                mod.main()
                elapsed = time.perf_counter() - start
        finally:
            sys.argv = saved
        return captured.getvalue(), elapsed

    compiled_names = [f.name for f in result.module.functions]
    print(f"compiled functions: {', '.join(compiled_names) or '(none)'}")
    for name, reason in sorted(result.skipped.items()):
        print(f"  interpreted: {name} — {reason}")
    out_i, t_i = run(interpreted)
    out_c, t_c = run(lambda: pipeline.load(result))
    print(f"interpreted: {t_i * 1000:.1f} ms   compiled: {t_c * 1000:.1f} ms   "
          f"speedup {t_i / t_c:.1f}x")
    if out_i != out_c:
        print("output differs:")
        print(f"  interpreted: {out_i!r}")
        print(f"  compiled:    {out_c!r}")
        return EXIT_ERRORS
    print("identical output:")
    print(out_c, end="")
    return EXIT_CLEAN


def cli() -> None:
    """Console-script entry point (`typedpython check ...`)."""
    sys.exit(main(sys.argv[1:]))


if __name__ == "__main__":
    cli()
