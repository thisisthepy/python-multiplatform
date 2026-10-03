"""ASan + UBSan setup for the TypedPython tests (SPEC N-8, issue #41).

    python tools/sanitizers.py env        print KEY=VALUE lines (append to $GITHUB_ENV)
    python tools/sanitizers.py selfcheck  build a deliberately faulty extension and REQUIRE that
                                          ASan and UBSan each report it; exit 1 otherwise

Linux: the sanitizer runtime is LD_PRELOADed into the (unsanitized) interpreter.
macOS refuses to load the runtime into the framework Python, so selfcheck runs through a small
sanitized launcher (Py_BytesMain) instead -- the same recipe as test_cgen.py. CI uses Linux only;
the macOS path exists so the self-check logic can be exercised on a developer machine.

TP_SANITIZE=1 makes conftest.py add the sanitizer flags to every extension TypedPython builds
(cbuild.compiler) and makes test_runtime.py build its shim sanitized.
"""
from __future__ import annotations

import os
import shlex
import subprocess
import sys
import sysconfig
import tempfile
import textwrap
from pathlib import Path

SAN_FLAGS = ["-fsanitize=address,undefined", "-fno-sanitize-recover=undefined", "-g", "-O1",
             "-fno-omit-frame-pointer"]
# detect_leaks=0: CPython itself leaks at exit. abort_on_error=0 keeps a plain nonzero exit code.
RUN_ENV = {
    "ASAN_OPTIONS": "detect_leaks=0:abort_on_error=0:halt_on_error=1:allocator_may_return_null=1",
    "UBSAN_OPTIONS": "halt_on_error=1:print_stacktrace=1",
}


def cc() -> list[str]:
    return shlex.split(sysconfig.get_config_var("CC") or "cc")


def asan_runtime() -> str:
    """Path of the shared ASan runtime belonging to the compiler that builds the extensions."""
    compiler = cc()
    is_clang = "clang" in Path(compiler[0]).name
    if sys.platform == "darwin":
        names = ["libclang_rt.asan_osx_dynamic.dylib"]
    elif is_clang:
        import platform
        names = [f"libclang_rt.asan-{platform.machine()}.so"]
    else:
        names = ["libasan.so"]
    for name in names:
        out = subprocess.run(compiler + [f"-print-file-name={name}"], capture_output=True,
                             text=True).stdout.strip()
        if out and Path(out).is_file():
            return str(Path(out).resolve())
    raise SystemExit(f"no ASan runtime {names} found for compiler {compiler}")


def run_env() -> dict[str, str]:
    env = dict(RUN_ENV)
    if sys.platform.startswith("linux"):
        env["LD_PRELOAD"] = asan_runtime()
    return env


def pyrefly_wrapper() -> str:
    """Pyrefly is a prebuilt Rust binary: keep the ASan preload out of it (and out of the launcher
    case, where sys.executable is not the venv python). The gate honours TYPEDPYTHON_PYREFLY."""
    binary = Path(sys.prefix) / "bin" / "pyrefly"
    if not binary.is_file():
        raise SystemExit(f"{binary} not found: install the test environment first")
    wrapper = Path(tempfile.mkdtemp(prefix="tp-san-")) / "pyrefly"
    wrapper.write_text(f'#!/bin/sh\nunset LD_PRELOAD DYLD_INSERT_LIBRARIES\nexec "{binary}" "$@"\n')
    wrapper.chmod(0o755)
    return str(wrapper)


def cmd_env() -> None:
    env = {"TP_SANITIZE": "1", "TYPEDPYTHON_PYREFLY": pyrefly_wrapper(), **run_env()}
    for key, value in env.items():
        print(f"{key}={value}")


FAULTY_C = textwrap.dedent('''\
    #include <Python.h>
    #include <limits.h>
    #include <stdlib.h>

    static PyObject *oob_read(PyObject *self, PyObject *arg) {
        volatile long i = PyLong_AsLong(arg);          /* runtime index: the read cannot be folded away */
        char *p = (char *)malloc(8);
        p[0] = 1;
        volatile char c = p[i];                        /* heap-buffer-overflow when i >= 8 */
        free(p);
        return PyLong_FromLong(c);
    }

    static PyObject *overflow(PyObject *self, PyObject *arg) {
        volatile int a = INT_MAX, b = (int)PyLong_AsLong(arg);
        int r = a + b;                                 /* signed-integer-overflow when b >= 1 */
        return PyLong_FromLong(r);
    }

    static PyMethodDef methods[] = {
        {"oob_read", oob_read, METH_O, ""}, {"overflow", overflow, METH_O, ""}, {NULL, NULL, 0, NULL}};
    static struct PyModuleDef moddef = {PyModuleDef_HEAD_INIT, "tp_faulty", NULL, -1, methods};
    PyMODINIT_FUNC PyInit_tp_faulty(void) { return PyModule_Create(&moddef); }
    ''')

DRIVER = textwrap.dedent('''\
    import importlib.machinery, importlib.util, sys
    loader = importlib.machinery.ExtensionFileLoader("tp_faulty", {so!r})
    spec = importlib.util.spec_from_file_location("tp_faulty", {so!r}, loader=loader)
    m = importlib.util.module_from_spec(spec); loader.exec_module(m)
    print("LOADED", flush=True)
    {call}
    print("RETURNED-WITHOUT-REPORT", flush=True)
    ''')


def python_command(workdir: Path) -> list[str]:
    if sys.platform != "darwin":
        return [sys.executable]
    launcher_c = workdir / "asan_python.c"
    launcher_c.write_text("#include <Python.h>\nint main(int argc, char **argv) "
                          "{ return Py_BytesMain(argc, argv); }\n")
    launcher = workdir / "asan_python"
    cmd = cc() + SAN_FLAGS + [f"-I{sysconfig.get_paths()['include']}", str(launcher_c),
                              f"-L{sysconfig.get_config_var('LIBDIR')}",
                              f"-lpython{sysconfig.get_config_var('LDVERSION')}", "-o", str(launcher)]
    subprocess.run(cmd, check=True)
    return [str(launcher)]


def cmd_selfcheck() -> int:
    work = Path(tempfile.mkdtemp(prefix="tp-san-selfcheck-"))
    src = work / "tp_faulty.c"
    src.write_text(FAULTY_C)
    so = work / ("tp_faulty" + sysconfig.get_config_var("EXT_SUFFIX"))
    build = cc() + SAN_FLAGS + ["-fPIC", "-shared", f"-I{sysconfig.get_paths()['include']}", str(src),
                                "-o", str(so)]
    if sys.platform == "darwin":
        build += ["-undefined", "dynamic_lookup"]
    subprocess.run(build, check=True)
    py = python_command(work)
    env = dict(os.environ, **run_env())
    if sys.platform == "darwin":
        env["PYTHONHOME"] = sys.base_prefix
        env.pop("DYLD_INSERT_LIBRARIES", None)
    failures = []
    cases = [
        ("clean call is silent", "m.oob_read(0); m.overflow(0)", None, 0),
        ("ASan reports heap overflow", "m.oob_read(8)",   # first byte past the end: always in the redzone
         "AddressSanitizer: heap-buffer-overflow", None),
        ("UBSan reports signed overflow", "m.overflow(1)", "runtime error: signed integer overflow", None),
    ]
    for name, call, needle, want_rc in cases:
        run = subprocess.run(py + ["-c", DRIVER.format(so=str(so), call=call)], capture_output=True,
                             text=True, env=env)
        out = run.stdout + run.stderr
        if "violates platform policy" in out or "Interceptors are not working" in out:
            failures.append(f"{name}: sanitizer runtime could not be loaded:\n{out[-1500:]}")
            continue
        if needle is None:
            ok = run.returncode == 0 and "RETURNED-WITHOUT-REPORT" in out and "Sanitizer" not in out \
                and "runtime error" not in out
        else:
            ok = needle in out and run.returncode != 0 and "RETURNED-WITHOUT-REPORT" not in out
        print(f"{'ok  ' if ok else 'FAIL'} {name} (rc={run.returncode})")
        if not ok:
            failures.append(f"{name}:\n--- head ---\n{out[:2500]}\n--- tail ---\n{out[-800:]}")
    if failures:
        print("\n".join(failures), file=sys.stderr)
        print("SELF-CHECK FAILED: the sanitizer setup does not detect a known fault", file=sys.stderr)
        return 1
    print("SELF-CHECK OK: ASan and UBSan both report the planted faults")
    return 0


if __name__ == "__main__":
    if sys.argv[1:] == ["env"]:
        cmd_env()
    elif sys.argv[1:] == ["selfcheck"]:
        sys.exit(cmd_selfcheck())
    else:
        sys.exit(__doc__)
