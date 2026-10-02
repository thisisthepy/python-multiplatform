"""`python -m typedpython check [--mode checked|compiled] [--format text|json] PATHS...`

Exit status: 0 no errors (warnings allowed), 1 errors, 2 the gate itself could not run.
"""
import argparse
import json
import sys
from pathlib import Path

from typedpython import gate
from typedpython.pyrefly import PyreflyError

EXIT_CLEAN, EXIT_ERRORS, EXIT_FAILURE = 0, 1, 2


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(prog="typedpython")
    commands = parser.add_subparsers(dest="command", required=True)
    check = commands.add_parser("check", help="type-check user Python code")
    check.add_argument("paths", nargs="+", type=Path)
    check.add_argument("--mode", choices=["checked", "compiled"], default="checked")
    check.add_argument("--format", choices=["text", "json"], default="text")
    check.add_argument("--search-path", action="append", type=Path, default=[],
                       help="directory to resolve imports from (repeatable)")
    args = parser.parse_args(argv)

    try:
        found = gate.check(args.paths, mode=args.mode, search_paths=args.search_path)
    except PyreflyError as e:
        print(f"typedpython: {e}", file=sys.stderr)
        return EXIT_FAILURE

    if args.format == "json":
        print(json.dumps([d.to_json() for d in found], indent=2))
    else:
        for d in found:
            print(f"{d.severity.upper()} {d.path}:{d.line}:{d.column}: {d.message} [{d.rule}]")

    return EXIT_ERRORS if any(d.severity == "error" for d in found) else EXIT_CLEAN


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
