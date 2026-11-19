"""`python -m typedpython check [--mode checked|compiled] [--format text|json] PATHS...`
`python -m typedpython emit-c PATHS... --root DIR [--cache DIR] --manifest FILE`
`python -m typedpython aot-build ENTRY.py -o EXECUTABLE [--emit-c FILE.c] [--strict-int]` (SPEC N-17, #448: exit 1 on a finding)
`python -m typedpython aot-lib MODULE.py --export f,g --prefix P -o DIR [--emit-c FILE.c] [--strict-int]` (#455: a C-ABI library)
`python -m typedpython aot-build ENTRY.py --explain-lists` (also `aot-lib MODULE.py --export f --prefix P --explain-lists`;
#465, agent proposal, not owner-confirmed): builds nothing and prints, for each group of `list[int]` names, its members,
why they were merged, its class (raw, or big-capable) and why; no `-o` is needed.

`--strict-int` (#462; design decision D1 (a), leader 2026-11-16, not owner-confirmed): an int not proved inside 64 bits is a
compile error (`aot/int-unproven`) instead of a big-integer-capable value.

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
    aot = commands.add_parser(
        "aot-check", help="report what an AOT build would refuse in a program (builds nothing, SPEC N-17)")
    aot.add_argument("entry", type=Path, help="the entry module; the sibling modules it imports are checked too")
    aot.add_argument("--strict-int", action="store_true",
                     help="also report an int operation that is not proved to stay inside i64")
    aot.add_argument("--json", action="store_true", help="print the report as JSON")
    aot_build = commands.add_parser(
        "aot-build", help="compile a single-module program of scalar functions to a standalone executable "
                          "with no CPython (SPEC N-17, first step)")
    aot_build.add_argument("entry", type=Path, help="the entry module (it has an `if __name__ == \"__main__\":` block)")
    aot_build.add_argument("-o", "--output", type=Path, default=None, help="the executable to write")
    aot_build.add_argument("--explain-lists", action="store_true",
                           help="build nothing; print the groups of list[int] names, their class and why")
    aot_build.add_argument("--emit-c", type=Path, default=None, help="also keep the generated C here")
    aot_build.add_argument("--strict-int", action="store_true",
                           help="an int operation not proved to stay inside 64 bits is a compile error (no big integers)")
    aot_lib = commands.add_parser(
        "aot-lib", help="compile a single-module program to a C-ABI shared library with a header and a JSON "
                        "description, with no CPython (SPEC N-17, #455)")
    aot_lib.add_argument("module", type=Path, help="the module (an `if __name__ == \"__main__\":` block is not compiled)")
    aot_lib.add_argument("--export", required=True, help="the functions to export, separated by commas")
    aot_lib.add_argument("--prefix", required=True, help="the C identifier every exported symbol starts with")
    aot_lib.add_argument("-o", "--output", type=Path, default=None, help="the directory for the library, "
                         "<prefix>.h and <prefix>.json")
    aot_lib.add_argument("--explain-lists", action="store_true",
                         help="build nothing; print the groups of list[int] names, their class and why")
    aot_lib.add_argument("--emit-c", type=Path, default=None, help="also keep the generated C here")
    aot_lib.add_argument("--strict-int", action="store_true",
                         help="an int operation not proved to stay inside 64 bits is a compile error (no big integers)")
    args = parser.parse_args(argv)

    if args.command in ("aot-lib", "aot-build") and args.output is None and not args.explain_lists:
        parser.error("the following arguments are required: -o/--output")
    if args.command == "aot-lib" and args.explain_lists:
        return _aot_explain(args.module, [n.strip() for n in args.export.split(",") if n.strip()], args.prefix,
                            args.strict_int)
    if args.command == "aot-build" and args.explain_lists:
        return _aot_explain(args.entry, None, None, args.strict_int)
    if args.command == "aot-lib":
        return _aot_lib(args.module, [n.strip() for n in args.export.split(",") if n.strip()], args.prefix,
                        args.output, args.emit_c, args.strict_int)
    if args.command == "aot-build":
        return _aot_build(args.entry, args.output, args.emit_c, args.strict_int)
    if args.command == "aot-check":
        return _aot_check(args.entry, args.strict_int, args.json)
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


def _aot_check(entry: Path, strict_int: bool, as_json: bool) -> int:
    """SPEC N-17 / #446: exit 0 when no finding is an error or pending, 1 otherwise, 2 if it could not run."""
    from typedpython import aotcheck

    if not entry.is_file():
        print(f"typedpython: {entry} is not a file", file=sys.stderr)
        return EXIT_FAILURE
    report = aotcheck.check(entry, strict_int=strict_int)
    if as_json:
        print(json.dumps(report.to_json(), indent=2))
    else:
        for line in report.lines():
            print(line)
    return EXIT_ERRORS if report.blocking else EXIT_CLEAN


def _aot_explain(module: Path, exports: list[str] | None, prefix: str | None, strict_int: bool) -> int:
    """`--explain-lists`: the groups of `list[int]` names on standard output, nothing built (exit 0; 1 with the findings
    on standard error when the program cannot be analysed; 2 when it could not run)."""
    from typedpython import aot

    if not module.is_file():
        print(f"typedpython: {module} is not a file", file=sys.stderr)
        return EXIT_FAILURE
    try:
        text = aot.explain_lists(module, exports=exports, prefix=prefix, strict_int=strict_int)
    except aot.AotError as e:
        for f in e.findings:
            print(aot.format_finding(f), file=sys.stderr)
        return EXIT_ERRORS
    except PyreflyError as e:
        print(f"typedpython: {e}", file=sys.stderr)
        return EXIT_FAILURE
    sys.stdout.write(text)
    return EXIT_CLEAN


def _aot_build(entry: Path, output: Path, emit_c: Path | None, strict_int: bool = False) -> int:
    """SPEC N-17 / #448: exit 0 when the executable was built; 1 with one line per finding on standard error
    (`path:line: error rule: message`) when the program is outside what the mode compiles exactly; 2 when
    the build itself could not run (no such file, no C compiler, the compiler failed)."""
    from typedpython import aot, cbuild

    if not entry.is_file():
        print(f"typedpython: {entry} is not a file", file=sys.stderr)
        return EXIT_FAILURE
    try:
        exe = aot.build(entry, output, emit_c=emit_c, strict_int=strict_int)
    except aot.AotError as e:
        for f in e.findings:
            print(aot.format_finding(f), file=sys.stderr)
        return EXIT_ERRORS
    except (cbuild.CBuildError, PyreflyError) as e:
        print(f"typedpython: {e}", file=sys.stderr)
        return EXIT_FAILURE
    print(exe)
    return EXIT_CLEAN


def _aot_lib(module: Path, exports: list[str], prefix: str, out_dir: Path, emit_c: Path | None,
             strict_int: bool = False) -> int:
    """SPEC N-17 / #455: exit 0 when the library, its header and its description were written (their paths are
    printed, one per line); 1 with one line per finding on standard error, as `aot-build`; 2 when the build
    itself could not run."""
    from typedpython import aot, cbuild

    if not module.is_file():
        print(f"typedpython: {module} is not a file", file=sys.stderr)
        return EXIT_FAILURE
    try:
        files = aot.build_library(module, out_dir, exports=exports, prefix=prefix, emit_c=emit_c,
                                  strict_int=strict_int)
    except aot.AotError as e:
        for f in e.findings:
            print(aot.format_finding(f), file=sys.stderr)
        return EXIT_ERRORS
    except (cbuild.CBuildError, PyreflyError) as e:
        print(f"typedpython: {e}", file=sys.stderr)
        return EXIT_FAILURE
    for path in (files.library, files.header, files.description):
        print(path)
    return EXIT_CLEAN


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
