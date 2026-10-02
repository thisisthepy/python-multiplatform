"""Turning the paths a caller gives into the Python files to check."""
from collections.abc import Sequence
from pathlib import Path

SKIPPED_DIRS = {"__pycache__", "build"}


def python_files(paths: Sequence[Path]) -> list[Path]:
    """Files as given; directories expanded to their `.py` files, skipping hidden and cache dirs."""
    found: list[Path] = []
    for path in paths:
        path = Path(path)
        if not path.is_dir():
            found.append(path)
            continue
        for file in sorted(path.rglob("*.py")):
            parts = file.relative_to(path).parts[:-1]
            if any(p.startswith(".") or p in SKIPPED_DIRS for p in parts):
                continue
            found.append(file)
    return found
