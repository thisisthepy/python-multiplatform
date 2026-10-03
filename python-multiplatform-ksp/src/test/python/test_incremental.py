"""Incremental compilation (SPEC N-10, #41): module cache keyed by source + dependency interface hashes."""
from __future__ import annotations

import dataclasses
import re
import shutil
import time
import uuid
from pathlib import Path

import pytest

from typedpython import ir
from typedpython.incremental import BuildCache, dependencies, interface_hash

TMP_ROOT = Path(__file__).resolve().parents[4] / ".tmp"
TYPES = {"int": ir.Type.I64, "float": ir.Type.F64, "bool": ir.Type.BOOL, "None": ir.Type.NONE}
DEF = re.compile(r"^def (\w+)\((.*?)\) -> (\w+):", re.M)


@pytest.fixture
def work():
    root = TMP_ROOT / f"incr-{uuid.uuid4().hex[:8]}"
    root.mkdir(parents=True)
    yield root
    shutil.rmtree(root, ignore_errors=True)


class Fakes:
    """Counting stand-ins for the front end, the C back end and the C compiler."""

    def __init__(self):
        self.lowered, self.generated, self.compiled = [], [], []

    def lower(self, path: Path) -> ir.Module:
        self.lowered.append(path.name)
        params_of = lambda text: tuple(
            ir.Param(n.strip().split(":")[0], TYPES[n.strip().split(":")[1].strip()])
            for n in text.split(",") if n.strip())
        funcs = tuple(
            ir.Function(m[1], params_of(m[2]), TYPES[m[3]], {}, (), True, False, 1)
            for m in DEF.finditer(path.read_text()))
        return ir.Module(path.stem, funcs)

    def generate(self, module: ir.Module, path: Path) -> str:
        self.generated.append(path.name)
        return f"/* {module.name} */ {path.read_text()!r}"

    def compile_c(self, c_source: str, name: str, out_dir: Path) -> Path:
        self.compiled.append(name)
        out = Path(out_dir) / f"{name}.so"
        out.write_text("OBJ:" + c_source)
        return out

    def counts(self):
        return (len(self.lowered), len(self.generated), len(self.compiled))

    def reset(self):
        self.lowered.clear(); self.generated.clear(); self.compiled.clear()


def build(cache, fakes, root, files, **over):
    args = dict(lower=fakes.lower, generate=fakes.generate, compile_c=fakes.compile_c,
                compiler_version="1", platform="linux-x86_64", flags=["-O2"])
    args.update(over)
    return cache.build_project([root / f for f in files], root, **args)


def project(root: Path):
    (root / "a.py").write_text("def f(x: int) -> int:\n    return x + 1\n")
    (root / "b.py").write_text("from a import f\ndef g(y: int) -> int:\n    return f(y)\n")
    (root / "c.py").write_text("def h(z: float) -> float:\n    return z\n")
    return ["a.py", "b.py", "c.py"]


def status(report):
    return {Path(m.path).name: m.status for m in report.modules}


def test_1_first_builds_all_second_builds_nothing(work):
    files, fakes, cache = project(work), Fakes(), BuildCache(work / "cache")
    r1 = build(cache, fakes, work, files)
    assert fakes.counts() == (3, 3, 3)
    assert set(status(r1).values()) == {"rebuilt"}
    fakes.reset()
    t = time.perf_counter()
    r2 = build(cache, fakes, work, files)
    elapsed = time.perf_counter() - t
    assert fakes.counts() == (0, 0, 0)
    assert set(status(r2).values()) == {"reused"}
    print(f"unchanged rebuild wall time: {elapsed * 1000:.2f} ms")
    assert elapsed < 0.5
    assert all(m.artifact.exists() for m in r2.modules)


def test_2_body_change_rebuilds_only_that_module(work):
    files, fakes, cache = project(work), Fakes(), BuildCache(work / "cache")
    build(cache, fakes, work, files)
    fakes.reset()
    (work / "a.py").write_text("def f(x: int) -> int:\n    return x + 2\n")
    r = build(cache, fakes, work, files)
    assert fakes.compiled == ["a"]
    assert status(r) == {"a.py": "rebuilt", "b.py": "reused", "c.py": "reused"}
    assert "source" in next(m for m in r.modules if m.path.name == "a.py").reasons


def test_3_signature_change_rebuilds_dependents_not_independents(work):
    files, fakes, cache = project(work), Fakes(), BuildCache(work / "cache")
    build(cache, fakes, work, files)
    fakes.reset()
    (work / "a.py").write_text("def f(x: float) -> float:\n    return x + 1\n")
    r = build(cache, fakes, work, files)
    assert sorted(fakes.compiled) == ["a", "b"]
    assert status(r) == {"a.py": "rebuilt", "b.py": "rebuilt", "c.py": "reused"}
    b = next(m for m in r.modules if m.path.name == "b.py")
    assert any("dependency interface" in x and "a.py" in x for x in b.reasons), b.reasons


@pytest.mark.parametrize("over,component", [
    ({"compiler_version": "2"}, "compiler_version"),
    ({"platform": "darwin-arm64"}, "platform"),
    ({"flags": ["-O0"]}, "flags"),
])
def test_4_toolchain_change_rebuilds_everything(work, over, component):
    files, fakes, cache = project(work), Fakes(), BuildCache(work / "cache")
    build(cache, fakes, work, files)
    fakes.reset()
    r = build(cache, fakes, work, files, **over)
    assert fakes.counts() == (3, 3, 3)
    assert set(status(r).values()) == {"rebuilt"}
    assert all(component in m.reasons for m in r.modules), [m.reasons for m in r.modules]


def test_5_corrupt_entry_is_a_miss_others_reused(work):
    files, fakes, cache = project(work), Fakes(), BuildCache(work / "cache")
    r1 = build(cache, fakes, work, files)
    victim = next(m for m in r1.modules if m.path.name == "c.py").artifact
    victim.write_text("OBJ:trunc")  # damaged contents
    fakes.reset()
    r2 = build(cache, fakes, work, files)
    assert fakes.compiled == ["c"]
    assert status(r2) == {"a.py": "reused", "b.py": "reused", "c.py": "rebuilt"}
    assert "corrupt" in next(m for m in r2.modules if m.path.name == "c.py").reasons
    # and a deleted artefact, and garbage metadata
    fakes.reset()
    for m in r2.modules:
        if m.path.name == "a.py":
            m.artifact.unlink()
        if m.path.name == "b.py":
            (m.artifact.parent / "meta.json").write_text("{not json")
    r3 = build(cache, fakes, work, files)
    assert sorted(fakes.compiled) == ["a", "b"]
    assert status(r3)["c.py"] == "reused"


def test_6_relative_import_in_package_detected(work):
    pkg = work / "pkg"
    pkg.mkdir()
    (pkg / "__init__.py").write_text("")
    (pkg / "base.py").write_text("def f(x: int) -> int:\n    return x\n")
    (pkg / "sub").mkdir()
    (pkg / "sub" / "__init__.py").write_text("")
    (pkg / "sub" / "leaf.py").write_text("")
    (pkg / "user.py").write_text("from .base import f\nfrom .sub import leaf\nimport os, json\n")
    (pkg / "deep.py").write_text("from ..pkg import base\nfrom . import base as b2\nimport pkg.sub.leaf\n")
    d = dependencies(pkg / "user.py", work)
    assert pkg / "base.py" in d and pkg / "sub" / "leaf.py" in d
    assert not any(p.name in ("os.py", "json.py") for p in d)
    d2 = dependencies(pkg / "deep.py", work)
    assert pkg / "base.py" in d2 and pkg / "sub" / "leaf.py" in d2
    assert pkg / "deep.py" not in d2


def test_6b_relative_dependency_drives_rebuild(work):
    pkg = work / "pkg"
    pkg.mkdir()
    (pkg / "__init__.py").write_text("")
    (pkg / "base.py").write_text("def f(x: int) -> int:\n    return x\n")
    (pkg / "user.py").write_text("from .base import f\ndef g(x: int) -> int:\n    return f(x)\n")
    files, fakes, cache = ["pkg/base.py", "pkg/user.py"], Fakes(), BuildCache(work / "cache")
    build(cache, fakes, work, files)
    fakes.reset()
    (pkg / "base.py").write_text("def f(x: float) -> int:\n    return 1\n")
    build(cache, fakes, work, files)
    assert sorted(fakes.compiled) == ["pkg.base", "pkg.user"]


def fn(name="f", params=(ir.Param("x", ir.Type.I64),), ret=ir.Type.I64, body=(), locals_=None, line=1):
    return ir.Function(name, tuple(params), ret, locals_ or {}, tuple(body), True, False, line)


def test_7_interface_hash():
    base = ir.Module("m", (fn(),))
    h = interface_hash(base)
    body = (ir.Return(ir.Const(ir.Type.I64, 1)),)
    assert interface_hash(ir.Module("m", (fn(body=body, locals_={"t": ir.Type.F64}, line=99),))) == h
    assert interface_hash(ir.Module("m", (dataclasses.replace(fn(), pure=False, may_deopt=True),))) == h
    assert interface_hash(ir.Module("m", (fn(params=(ir.Param("x", ir.Type.F64),)),))) != h
    assert interface_hash(ir.Module("m", (fn(params=(ir.Param("y", ir.Type.I64),)),))) != h
    assert interface_hash(ir.Module("m", (fn(ret=ir.Type.F64),))) != h
    assert interface_hash(ir.Module("m", (fn(name="g"),))) != h
    arr = lambda t, s: fn(params=(ir.ArrayParam("x", t, s),))
    assert interface_hash(ir.Module("m", (arr(ir.Type.F64_ARRAY, False),))) != \
        interface_hash(ir.Module("m", (arr(ir.Type.I64_ARRAY, False),)))
    assert interface_hash(ir.Module("m", (fn(),), {"z": "why"})) != h  # skipped names count
    assert interface_hash(ir.Module("m", (fn(),), {"z": "why"})) == \
        interface_hash(ir.Module("m", (fn(),), {"z": "other reason"}))
    # order of params matters, order of functions does too (it is the module's order)
    p2 = (ir.Param("a", ir.Type.I64), ir.Param("b", ir.Type.F64))
    assert interface_hash(ir.Module("m", (fn(params=p2),))) != \
        interface_hash(ir.Module("m", (fn(params=p2[::-1]),)))
