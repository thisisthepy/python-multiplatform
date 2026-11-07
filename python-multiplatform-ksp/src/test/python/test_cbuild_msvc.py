"""cbuild on Windows: the MSVC command line and environment discovery (SPEC: TypedPython builds with
cl.exe on Windows). Nothing here runs a compiler: the platform, `subprocess.run` and `shutil.which`
are replaced, so the tests run on every host. That the generated C compiles under cl.exe is checked
by the windows-latest job of typedpython.yml."""
from __future__ import annotations

import subprocess
from pathlib import Path

import pytest

from typedpython import cbuild, cgen

SRC = "#include <Python.h>\nint x;\n"


class FakeCompleted:
    def __init__(self, returncode=0, stdout="", stderr=""):
        self.returncode, self.stdout, self.stderr = returncode, stdout, stderr


@pytest.fixture(autouse=True)
def fresh_env_cache(monkeypatch):
    monkeypatch.setattr(cbuild, "_msvc_env_cache", cbuild._UNSET)


@pytest.fixture
def include(tmp_path):
    inc = tmp_path / "py" / "include"
    inc.mkdir(parents=True)
    (inc / "Python.h").write_text("")
    return inc


def test_msvc_command_shape(tmp_path):
    libs = tmp_path / "libs"
    cmd = cbuild.msvc_command("cl", tmp_path / "m.c", tmp_path / "m.cp315t-win_amd64.pyd",
                              tmp_path / "inc", tmp_path / "rt", libs_dir=libs)
    assert cmd[:2] == ["cl", "/nologo"]
    assert "/O2" in cmd and "/LD" in cmd
    assert f"/I{tmp_path / 'inc'}" in cmd and f"/I{tmp_path / 'rt'}" in cmd
    link = cmd.index("/link")
    assert str(tmp_path / "m.c") in cmd[:link]
    assert cmd[link + 1:] == [f"/LIBPATH:{libs}", f"/OUT:{tmp_path / 'm.cp315t-win_amd64.pyd'}"]
    assert not [a for a in cmd if a.startswith(("-f", "-W", "-O", "-shared"))]   # no GCC flag leaks


def test_gcc_flags_translate():
    assert cbuild._msvc_flags(cbuild.DEFAULT_FLAGS) == list(cbuild.MSVC_DEFAULT_FLAGS)
    got = cbuild._msvc_flags(list(cbuild.DEFAULT_FLAGS) + ["-DTP_BUFFER_PLAIN", "-Ifoo", "/W4", "-fsanitize=address"])
    assert got == list(cbuild.MSVC_DEFAULT_FLAGS) + ["/DTP_BUFFER_PLAIN", "/Ifoo", "/W4"]


def test_build_on_windows_runs_cl_and_returns_the_pyd(tmp_path, include, monkeypatch):
    monkeypatch.setattr(cbuild.sys, "platform", "win32")
    monkeypatch.setattr(cbuild.shutil, "which", lambda name, path=None: r"C:\VS\cl.exe" if name == "cl" else None)
    seen = {}

    def fake_run(cmd, **kw):
        seen["cmd"], seen["kw"] = cmd, kw
        Path(next(a for a in cmd if a.startswith("/OUT:"))[5:]).write_text("pyd")
        return FakeCompleted()

    monkeypatch.setattr(cbuild.subprocess, "run", fake_run)
    so = cbuild.build(SRC, "pkg.mod", tmp_path / "out", python_include=include, ext_suffix=".cp315t-win_amd64.pyd")
    assert so == tmp_path / "out" / "mod.cp315t-win_amd64.pyd"
    assert seen["cmd"][0] == r"C:\VS\cl.exe" and "/LD" in seen["cmd"] and "-fPIC" not in seen["cmd"]
    assert seen["kw"]["env"] is None            # cl.exe already on PATH: the current environment
    assert (tmp_path / "out" / "mod.c").read_text() == SRC


def test_build_on_windows_reports_cl_stdout(tmp_path, include, monkeypatch):
    monkeypatch.setattr(cbuild.sys, "platform", "win32")
    monkeypatch.setattr(cbuild.shutil, "which", lambda name, path=None: "cl.exe")
    monkeypatch.setattr(cbuild.subprocess, "run",
                        lambda cmd, **kw: FakeCompleted(2, stdout="mod.c(3): error C2065: 'y': undeclared"))
    with pytest.raises(cbuild.CBuildError, match="C2065"):
        cbuild.build(SRC, "mod", tmp_path, python_include=include, ext_suffix=".pyd")


def test_posix_command_is_unchanged(tmp_path, include, monkeypatch):
    monkeypatch.setattr(cbuild.sys, "platform", "linux")
    monkeypatch.setattr(cbuild.sysconfig, "get_config_var", lambda n: "gcc -pthread" if n == "CC" else None)
    seen = {}

    def fake_run(cmd, **kw):
        seen["cmd"], seen["kw"] = cmd, kw
        return FakeCompleted()

    monkeypatch.setattr(cbuild.subprocess, "run", fake_run)
    runtime = cbuild.RUNTIME_DIR
    so = cbuild.build(SRC, "mod", tmp_path, python_include=include, ext_suffix=".so")
    assert seen["cmd"] == (["gcc", "-pthread"] + list(cbuild.DEFAULT_FLAGS)
                           + ["-fPIC", "-shared", f"-I{include}", f"-I{runtime}", str(tmp_path / "mod.c"), "-o", str(so)])
    assert seen["kw"] == {"capture_output": True, "text": True}


def test_darwin_adds_dynamic_lookup(tmp_path, include, monkeypatch):
    monkeypatch.setattr(cbuild.sys, "platform", "darwin")
    monkeypatch.setattr(cbuild.sysconfig, "get_config_var", lambda n: "cc" if n == "CC" else None)
    seen = {}
    monkeypatch.setattr(cbuild.subprocess, "run", lambda cmd, **kw: seen.setdefault("cmd", cmd) and FakeCompleted())
    cbuild.build(SRC, "mod", tmp_path, python_include=include, ext_suffix=".so")
    assert seen["cmd"][-5:-2] == ["-undefined", "dynamic_lookup", str(tmp_path / "mod.c")]


def test_a_configured_cl_selects_msvc_off_windows(monkeypatch):
    monkeypatch.setattr(cbuild.sys, "platform", "linux")
    monkeypatch.setattr(cbuild.sysconfig, "get_config_var", lambda n: "/opt/msvc/cl.exe" if n == "CC" else None)
    assert cbuild._is_msvc()


def test_parse_env_dump():
    text = "**********\n[vcvarsall.bat] Environment initialized\nINCLUDE=C:\\inc\nPath=C:\\a;C:\\b\nEMPTY=\n"
    assert cbuild.parse_env_dump(text) == {"INCLUDE": "C:\\inc", "Path": "C:\\a;C:\\b", "EMPTY": ""}


def test_no_vswhere_names_what_is_missing(monkeypatch):
    class Missing:
        def is_file(self):
            return False

        def __str__(self):
            return r"C:\VS\Installer\vswhere.exe"

    monkeypatch.setattr(cbuild.shutil, "which", lambda name, path=None: None)
    monkeypatch.setattr(cbuild, "_vswhere_path", lambda: Missing())
    with pytest.raises(cbuild.CBuildError) as e:
        cbuild.msvc_env()
    assert "cl.exe" in str(e.value) and "vswhere.exe" in str(e.value) and cbuild.MSVC_COMPONENT in str(e.value)


def test_vswhere_without_the_cpp_component(monkeypatch):
    class Exists:
        def is_file(self):
            return True

        def __str__(self):
            return "vswhere.exe"

    monkeypatch.setattr(cbuild.shutil, "which", lambda name, path=None: None)
    monkeypatch.setattr(cbuild, "_vswhere_path", lambda: Exists())
    monkeypatch.setattr(cbuild.subprocess, "run", lambda cmd, **kw: FakeCompleted(0, stdout="\n"))
    with pytest.raises(cbuild.CBuildError, match="Microsoft.VisualStudio.Component.VC.Tools.x86.x64"):
        cbuild.msvc_env()


def test_vcvars_environment_is_captured_once(tmp_path, monkeypatch):
    root = tmp_path / "VS"
    vcvars = root / "VC" / "Auxiliary" / "Build" / "vcvars64.bat"
    vcvars.parent.mkdir(parents=True)
    vcvars.write_text("")
    vswhere = tmp_path / "vswhere.exe"
    vswhere.write_text("")
    monkeypatch.setattr(cbuild.shutil, "which", lambda name, path=None: None)
    monkeypatch.setattr(cbuild, "_vswhere_path", lambda: vswhere)
    monkeypatch.setattr(cbuild.platform, "machine", lambda: "AMD64")
    calls = []

    def fake_run(cmd, **kw):
        calls.append(cmd)
        if isinstance(cmd, list):       # vswhere
            assert cmd[0] == str(vswhere) and "-requires" in cmd and cbuild.MSVC_COMPONENT in cmd
            assert cmd[cmd.index("-property") + 1] == "installationPath"
            return FakeCompleted(0, stdout=str(root) + "\n")
        assert str(vcvars) in cmd and cmd.startswith("cmd /u /c") and cmd.endswith("&& set")
        return FakeCompleted(0, stdout="INCLUDE=C:\\inc\nPATH=C:\\VS\\bin\n".encode("utf-16-le"))

    monkeypatch.setattr(cbuild.subprocess, "run", fake_run)
    env = cbuild.msvc_env()
    assert env == {"INCLUDE": "C:\\inc", "PATH": "C:\\VS\\bin"}
    assert cbuild.msvc_env() is env and len(calls) == 2      # cached: no second vswhere or vcvars run


def test_msvc_is_not_selected_by_default_on_this_host():
    if cbuild.sys.platform != "win32":
        assert not cbuild._is_msvc() or Path(cbuild.compiler()[0]).stem.lower() == "cl"


def test_a_long_source_becomes_a_byte_list_not_a_literal():
    short = cgen.c_source_init("x = 1\n")
    assert short.startswith('"') and "{" not in short
    long = cgen.c_source_init("a" * (cgen.MSVC_LITERAL_LIMIT + 1))
    assert long.startswith("{") and long.rstrip().endswith("0\n}") and "97" in long


def test_the_runtime_has_an_msvc_path():
    text = (cbuild.RUNTIME_DIR / "tp_runtime.h").read_text()
    for needle in ("TP_MSVC_ATOMICS", "_InterlockedExchangeAdd64", "__iso_volatile_load64",
                   "__declspec(noinline)", "tp_add_i64((a), (b), (r))"):
        assert needle in text, needle


def test_free_threaded_build_defines_py_gil_disabled(tmp_path):
    # the Windows pyconfig.h of a free-threaded CPython does not define Py_GIL_DISABLED, so the
    # auto-link pragma would name python315.lib (absent there) instead of python315t.lib
    ft = cbuild.msvc_command("cl", tmp_path / "m.c", tmp_path / "m.pyd", tmp_path / "inc", tmp_path / "rt",
                             libs_dir=tmp_path / "libs", gil_disabled=True)
    gil = cbuild.msvc_command("cl", tmp_path / "m.c", tmp_path / "m.pyd", tmp_path / "inc", tmp_path / "rt",
                              libs_dir=tmp_path / "libs", gil_disabled=False)
    assert "/DPy_GIL_DISABLED=1" in ft and ft.index("/DPy_GIL_DISABLED=1") < ft.index("/LD")
    assert not [a for a in gil if "Py_GIL_DISABLED" in a]


def test_free_threaded_follows_the_suffix_else_sysconfig(monkeypatch):
    assert cbuild.free_threaded(".cp315t-win_amd64.pyd")
    assert not cbuild.free_threaded(".cp315-win_amd64.pyd")
    monkeypatch.setattr(cbuild.sysconfig, "get_config_var", lambda n: 1 if n == "Py_GIL_DISABLED" else None)
    assert cbuild.free_threaded()
    monkeypatch.setattr(cbuild.sysconfig, "get_config_var", lambda n: 0 if n == "Py_GIL_DISABLED" else None)
    assert not cbuild.free_threaded()


def test_build_passes_py_gil_disabled_for_a_free_threaded_suffix(tmp_path, include, monkeypatch):
    monkeypatch.setattr(cbuild.sys, "platform", "win32")
    monkeypatch.setattr(cbuild.shutil, "which", lambda name, path=None: "cl.exe")
    seen = {}

    def fake_run(cmd, **kw):
        seen["cmd"] = cmd
        Path(next(a for a in cmd if a.startswith("/OUT:"))[5:]).write_text("pyd")
        return FakeCompleted()

    monkeypatch.setattr(cbuild.subprocess, "run", fake_run)
    cbuild.build(SRC, "mod", tmp_path / "a", python_include=include, ext_suffix=".cp315t-win_amd64.pyd")
    assert "/DPy_GIL_DISABLED=1" in seen["cmd"]
    cbuild.build(SRC, "mod", tmp_path / "b", python_include=include, ext_suffix=".cp315-win_amd64.pyd")
    assert not [a for a in seen["cmd"] if "Py_GIL_DISABLED" in a]


def test_the_libs_dir_is_the_include_siblings_else_the_base_prefix(tmp_path, monkeypatch):
    root = tmp_path / "cpython-3.15+freethreaded"
    (root / "Include").mkdir(parents=True)
    (root / "libs").mkdir()
    assert cbuild.msvc_libs_dir(root / "Include") == root / "libs"
    monkeypatch.setattr(cbuild.sys, "base_prefix", str(tmp_path / "base"))
    assert cbuild.msvc_libs_dir(tmp_path / "elsewhere" / "include") == tmp_path / "base" / "libs"


def test_default_flags_with_extras_keep_the_msvc_defaults():
    got = cbuild._msvc_flags(list(cbuild.DEFAULT_FLAGS) + ["-DTP_SHIM_NAME=x", "-Wall"])
    assert got == list(cbuild.MSVC_DEFAULT_FLAGS) + ["/DTP_SHIM_NAME=x"]
