"""Build the TypedPython gate bundle that ships beside the KSP processor (#20, toolchain#90).

    python3 gate_bundle.py --out <path>/typedpython-gate.zip

The zip holds, at its root, the `typedpython` wheel under its real file name (so uv's
`--find-links <unzipped dir>` finds it) and `typedpython-gate.json`:

    {"format": 1,
     "typedpython": {"version": ..., "wheel": ..., "sha256": ...},
     "pyrefly": {"version": ..., "sha256": {<wheel file name>: <sha256>, ...}}}

The pyrefly hashes come from `typedpython-gate.lock.json` (PyPI's published digests for every pyrefly
wheel of the pinned version), so toolchain can install pyrefly from PyPI with `--require-hashes`.
The wheel is built with `uv build`; zip entries carry a fixed timestamp so the bundle depends only
on its inputs.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import tomllib
import zipfile
from pathlib import Path

MODULE = Path(__file__).resolve().parent
LOCK = MODULE / "typedpython-gate.lock.json"
MANIFEST = "typedpython-gate.json"
FIXED_TIME = (1980, 1, 1, 0, 0, 0)


def build(out: Path) -> Path:
    project = tomllib.loads((MODULE / "pyproject.toml").read_text())["project"]
    version = project["version"]
    lock = json.loads(LOCK.read_text())["pyrefly"]
    if f"pyrefly=={lock['version']}" not in project["dependencies"]:
        sys.exit(f"{LOCK.name} pins pyrefly {lock['version']}, pyproject.toml depends on {project['dependencies']}")
    out.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=out.parent) as tmp:
        run = subprocess.run(["uv", "build", "--wheel", "--out-dir", tmp, str(MODULE)],
                             capture_output=True, text=True)
        if run.returncode != 0:
            sys.exit(f"uv build failed ({run.returncode}):\n{run.stdout}{run.stderr}")
        wheel = Path(tmp) / f"typedpython-{version}-py3-none-any.whl"
        if not wheel.is_file():
            sys.exit(f"expected {wheel.name}, uv built {sorted(p.name for p in Path(tmp).iterdir())}")
        data = wheel.read_bytes()
        manifest = {
            "format": 1,
            "typedpython": {"version": version, "wheel": wheel.name, "sha256": hashlib.sha256(data).hexdigest()},
            "pyrefly": {"version": lock["version"], "sha256": lock["sha256"]},
        }
        partial = out.with_suffix(".partial")
        with zipfile.ZipFile(partial, "w", zipfile.ZIP_DEFLATED) as z:
            for name, payload in ((wheel.name, data),
                                  (MANIFEST, (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode())):
                info = zipfile.ZipInfo(name, FIXED_TIME)
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o644 << 16
                z.writestr(info, payload)
        shutil.move(partial, out)
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", required=True, type=Path)
    print(build(parser.parse_args().out.resolve()))


if __name__ == "__main__":
    main()
