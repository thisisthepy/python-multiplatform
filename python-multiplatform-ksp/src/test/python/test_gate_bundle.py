"""The TypedPython gate bundle published beside the KSP processor (#20, toolchain#90).

toolchain's typedpythonCheck resolves `python-multiplatform-ksp:<version>:typedpython-gate@zip`,
unzips it and installs the gate from it with uv `--require-hashes`, so the zip must hold the wheel
under its real file name and a manifest with every hash the install needs.
"""
import hashlib
import json
import subprocess
import sys
import tomllib
import zipfile
from pathlib import Path

import pytest

MODULE = Path(__file__).resolve().parents[3]          # python-multiplatform-ksp
SCRIPT = MODULE / "gate_bundle.py"
LOCK = MODULE / "typedpython-gate.lock.json"


@pytest.fixture(scope="module")
def bundle(tmp_path_factory):
    out = tmp_path_factory.mktemp("gate") / "typedpython-gate.zip"
    run = subprocess.run([sys.executable, str(SCRIPT), "--out", str(out)], capture_output=True, text=True)
    assert run.returncode == 0, run.stdout + run.stderr
    return out


def _pyproject():
    return tomllib.loads((MODULE / "pyproject.toml").read_text())


def test_zip_holds_the_wheel_under_its_real_name_and_a_manifest(bundle):
    names = zipfile.ZipFile(bundle).namelist()
    version = _pyproject()["project"]["version"]
    assert sorted(names) == sorted([f"typedpython-{version}-py3-none-any.whl", "typedpython-gate.json"])


def test_manifest_matches_the_wheel_and_the_pins(bundle):
    z = zipfile.ZipFile(bundle)
    m = json.loads(z.read("typedpython-gate.json"))
    project = _pyproject()["project"]
    assert m["format"] == 1
    assert m["typedpython"]["version"] == project["version"]
    wheel = m["typedpython"]["wheel"]
    assert m["typedpython"]["sha256"] == hashlib.sha256(z.read(wheel)).hexdigest()
    # the pyrefly pin is the one the wheel itself depends on, with a hash for every published wheel
    assert f"pyrefly=={m['pyrefly']['version']}" in project["dependencies"]
    assert m["pyrefly"]["sha256"] == json.loads(LOCK.read_text())["pyrefly"]["sha256"]
    assert any("macosx_11_0_arm64" in f for f in m["pyrefly"]["sha256"])
    assert all(f.endswith(".whl") for f in m["pyrefly"]["sha256"])


def test_lock_pins_the_same_pyrefly_as_the_package():
    lock = json.loads(LOCK.read_text())["pyrefly"]
    assert f"pyrefly=={lock['version']}" in _pyproject()["project"]["dependencies"]
    assert all(f.startswith(f"pyrefly-{lock['version']}-") for f in lock["sha256"])


def test_the_wheel_installs_with_hashes_and_checks_code(bundle, tmp_path):
    # the install toolchain does, minus the network: typedpython from the unzipped dir with its hash
    # (--no-deps here: pyrefly comes from PyPI there, from this test's own interpreter here)
    d = tmp_path / "unzipped"
    zipfile.ZipFile(bundle).extractall(d)
    m = json.loads((d / "typedpython-gate.json").read_text())
    req = tmp_path / "req.txt"
    req.write_text(f"typedpython=={m['typedpython']['version']} --hash=sha256:{m['typedpython']['sha256']}\n")
    target = tmp_path / "site"
    run = subprocess.run(["uv", "pip", "install", "--python", sys.executable, "--target", str(target),
                          "--no-index", "--find-links", str(d), "--no-deps", "--require-hashes",
                          "-r", str(req)], capture_output=True, text=True)
    assert run.returncode == 0, run.stdout + run.stderr
    assert (target / "typedpython" / "__main__.py").is_file()
    clean = tmp_path / "clean.py"
    clean.write_text("def f(x: int) -> int:\n    return x + 1\n")
    env = {"PYTHONPATH": str(target), "PATH": __import__("os").environ["PATH"],
           "TMPDIR": str(tmp_path)}
    check = subprocess.run([sys.executable, "-m", "typedpython", "check", str(clean)],
                           capture_output=True, text=True, env=env)
    assert check.returncode == 0, check.stdout + check.stderr


def test_the_wheel_carries_the_c_runtime_and_the_builtin_stub(bundle):
    # compiling needs tp_runtime.h; checking needs the `compiled` builtin stub
    import io
    z = zipfile.ZipFile(bundle)
    wheel = json.loads(z.read("typedpython-gate.json"))["typedpython"]["wheel"]
    names = zipfile.ZipFile(io.BytesIO(z.read(wheel))).namelist()
    for needed in ("typedpython/runtime/tp_runtime.h", "typedpython/builtin_stubs/__builtins__.pyi",
                   "typedpython_builtins.pth", "typedpython/__main__.py"):
        assert needed in names, needed
