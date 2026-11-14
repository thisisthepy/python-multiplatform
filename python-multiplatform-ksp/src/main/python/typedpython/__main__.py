"""`python -m typedpython check [--mode checked|compiled] [--format text|json] PATHS...`
`python -m typedpython emit-c PATHS... --root DIR [--cache DIR] --manifest FILE`

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
    build = commands.add_parser("build", help="compile modules incrementally (cached per module)")
    build.add_argument("paths", nargs="+", type=Path, help="files, or directories to build recursively")
    build.add_argument("--cache", type=Path, default=Path("build/typedpython-cache"),
                       help="incremental cache directory (default: build/typedpython-cache)")
    build.add_argument("--out", type=Path, default=None,
                       help="copy the built extensions here (default: leave them in the cache)")
    emit = commands.add_parser(
        "emit-c", help="generate C per module (cached) and a manifest for pypackpack's compile slot")
    emit.add_argument("paths", nargs="+", type=Path, help="files, or directories to scan recursively")
    emit.add_argument("--root", type=Path, required=True, help="directory module names are relative to")
    emit.add_argument("--cache", type=Path, default=Path("build/typedpython-c-cache"),
                      help="source -> C cache directory (default: build/typedpython-c-cache)")
    emit.add_argument("--manifest", type=Path, required=True, help="the JSON manifest to write")
    args = parser.parse_args(argv)

    if args.command == "emit-c":
        return _emit_c(args.paths, args.root, args.cache, args.manifest)
    if args.command == "build":
        return _build(args.paths, args.cache, args.out)
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


def _build(paths: list[Path], cache: Path, out: Path | None) -> int:
    import os
    import shutil

    from typedpython import pipeline

    sources = python_files(paths)
    if not sources:
        print("typedpython: no Python files to build", file=sys.stderr)
        return EXIT_FAILURE
    dirs = [p.resolve() for p in paths if p.is_dir()] or [p.resolve().parent for p in paths]
    root = Path(os.path.commonpath([str(d) for d in dirs]))
    try:
        result = pipeline.compile_project(sources, root, cache)
    except pipeline.CompileError as e:
        print(f"typedpython: {e}", file=sys.stderr)
        return EXIT_ERRORS
    except PyreflyError as e:
        print(f"typedpython: {e}", file=sys.stderr)
        return EXIT_FAILURE
    for m in result.modules:
        why = f" ({', '.join(m.reasons)})" if m.reasons else ""
        what = m.extension.name if m.extension else "no extension (interpreted)"
        print(f"{m.status:7} {m.name}: {what}{why}")
        rel = m.path.relative_to(root).as_posix()
        if m.kind == "not_marked":
            print(f"          {rel}: not marked for compilation (no @compiled)")
        for item in m.skipped_items:
            where = f"{item.file}:{item.line}" if item.line is not None else item.file
            print(f"          {where}: {item.name}: {item.message}")
        if out is not None and m.extension is not None:
            out.mkdir(parents=True, exist_ok=True)
            shutil.copy2(m.extension, out / m.extension.name)
    return EXIT_CLEAN


MANIFEST_FORMAT, MANIFEST_VERSION = "typedpython-emit-c", 1


def _emit_c(paths: list[Path], root: Path, cache: Path, manifest: Path) -> int:
    """SPEC N-10 / #25 step 1; the manifest format is docs/design/typedpython.md section 4.3.2a."""
    from typedpython import pipeline

    sources = python_files(paths)
    if not sources:
        print("typedpython: no Python files to emit", file=sys.stderr)
        return EXIT_FAILURE
    try:
        result = pipeline.generate_project(sources, root, cache)
    except pipeline.CompileError as e:
        print(f"typedpython: {e}", file=sys.stderr)
        return EXIT_ERRORS
    except PyreflyError as e:
        print(f"typedpython: {e}", file=sys.stderr)
        return EXIT_FAILURE
    root = root.resolve()
    runtime = Path(result.runtime_include).resolve().as_posix()

    def items(m: "pipeline.ModuleResult") -> list[dict]:
        return [{"name": i.name, "file": i.file, "line": i.line, "message": i.message}
                for i in m.skipped_items]

    native, other = [], []
    for m in result.modules:
        if m.path.name == "__init__.py":
            continue                        # never listed (pypackpack compile-slot.md)
        rel = m.path.resolve().relative_to(root).as_posix()
        if m.kind == "compiled":
            assert m.c_source is not None
            native.append({"moduleName": m.name, "file": rel,
                           "sources": [Path(m.c_source).resolve().as_posix()],
                           "includeDirs": [runtime], "defines": {}, "status": m.status,
                           "skippedItems": items(m)})
        else:
            other.append({"module": m.name, "file": rel,
                          "kind": "NOT_MARKED" if m.kind == "not_marked" else "NOTHING_COMPILED",
                          "items": items(m)})
    doc = {"format": MANIFEST_FORMAT, "version": MANIFEST_VERSION,
           "compilerVersion": pipeline.COMPILER_VERSION, "projectRoot": root.as_posix(),
           "runtimeInclude": runtime, "nativeModules": native, "notNativeModules": other}
    manifest.parent.mkdir(parents=True, exist_ok=True)
    tmp = manifest.with_name(manifest.name + ".tmp")
    tmp.write_text(json.dumps(doc, indent=2) + "\n")
    tmp.replace(manifest)
    return EXIT_CLEAN


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
        import builtins  # the module's `@compiled` is a builtin name; typedpython_builtins.pth installs it
        builtins.compiled = getattr(builtins, "compiled", lambda f=None, /, **_k: (lambda g: g) if f is None else f)
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
        print(f"  interpreted: {name}, {reason}")
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
