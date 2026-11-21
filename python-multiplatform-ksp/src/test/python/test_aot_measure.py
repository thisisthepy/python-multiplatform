"""Unit tests for the TypedPython AOT measurement helpers in benchmarks/typedpython/run.py (#479)."""
import importlib.util
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
RUN_PY = ROOT / "benchmarks" / "typedpython" / "run.py"


def _load_run_module():
    spec = importlib.util.spec_from_file_location("typedpython_bench_run", RUN_PY)
    assert spec is not None and spec.loader is not None
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_normalize_ru_maxrss():
    mod = _load_run_module()
    assert mod.normalize_ru_maxrss(4096, system="Linux") == 4096 * 1024
    assert mod.normalize_ru_maxrss(4096, system="Darwin") == 4096


def test_compute_aot_ratios():
    mod = _load_run_module()
    aot = {"stripped_bytes": 40000, "max_rss_bytes": 2000000, "cpu_s": 0.5, "wall_s": 0.6}
    rust = {"stripped_bytes": 200000, "max_rss_bytes": 1000000, "cpu_s": 0.25, "wall_s": 0.3}
    ratios = mod.compute_aot_ratios(aot, rust)
    assert ratios["stripped_bytes"] == 0.2
    assert ratios["max_rss_bytes"] == 2.0
    assert ratios["cpu_s"] == 2.0
    assert ratios["wall_s"] == 2.0

    zero_rust = {"stripped_bytes": 0, "max_rss_bytes": 0, "cpu_s": 0.0, "wall_s": 0.0}
    zero_ratios = mod.compute_aot_ratios(aot, zero_rust)
    assert all(v is None for v in zero_ratios.values())


def test_collect_aot_metadata_and_read_cpu_model(tmp_path):
    mod = _load_run_module()
    fake_cpuinfo = tmp_path / "cpuinfo"
    fake_cpuinfo.write_text("processor\t: 0\nmodel name\t: AMD EPYC 7763 64-Core Processor\n")
    assert mod.read_cpu_model(fake_cpuinfo) == "AMD EPYC 7763 64-Core Processor"

    meta = mod.collect_aot_metadata(sys.executable)
    for key in ("commit", "runner_image", "cpu_model", "python", "rustc", "shared_runner_note"):
        assert key in meta
        assert isinstance(meta[key], str) and len(meta[key]) > 0
    assert "shared runner" in meta["shared_runner_note"].lower()


def test_format_aot_report():
    mod = _load_run_module()
    metadata = {
        "commit": "abc1234",
        "runner_image": "ubuntu24 20261119",
        "cpu_model": "Test CPU",
        "python": "Python 3.15.0",
        "rustc": "rustc 1.85.0",
        "shared_runner_note": mod.AOT_SHARED_RUNNER_NOTE,
    }
    results = {
        "empty": {
            "size": None,
            "TypedPython AOT": {"stripped_bytes": 16384, "max_rss_bytes": 1048576, "cpu_s": 0.001, "wall_s": 0.002},
            "Rust": {"stripped_bytes": 327680, "max_rss_bytes": 2097152, "cpu_s": 0.001, "wall_s": 0.002},
            "CPython": {"stripped_bytes": None, "max_rss_bytes": 10485760, "cpu_s": 0.015, "wall_s": 0.020},
            "ratios_vs_rust": {"stripped_bytes": 0.05, "max_rss_bytes": 0.50, "cpu_s": 1.00, "wall_s": 1.00},
        }
    }
    report = mod.format_aot_report(metadata, results)
    assert "commit: abc1234" in report
    assert "runner_image: ubuntu24 20261119" in report
    assert "cpu_model: Test CPU" in report
    assert "| benchmark | size | AOT stripped | Rust stripped | size ratio | AOT RSS | Rust RSS | CPython RSS | RSS ratio | AOT CPU | Rust CPU | CPU ratio |" in report
    assert "| empty | - | 16.0 KiB | 320.0 KiB | 0.05x | 1.00 MiB | 2.00 MiB | 10.00 MiB | 0.50x | 0.0010s | 0.0010s | 1.00x |" in report


def test_run_once_with_usage_isolates_child_rss():
    """#488: a tiny POSIX child launched via posix_spawnp must not inherit the Python parent's RSS."""
    if sys.platform == "win32":
        return
    mod = _load_run_module()
    # Inflate parent RSS slightly so any fork-inherited RSS would be unmistakable.
    pad = bytearray(32 * 1024 * 1024)
    for i in range(0, len(pad), 4096):
        pad[i] = 1
    res = mod.run_once_with_usage(["/bin/echo", "ok"])
    assert res["returncode"] == 0
    assert res["stdout"] == "ok"
    assert isinstance(res["max_rss_bytes"], int)
    assert 0 < res["max_rss_bytes"] < 20 * 1024 * 1024, f"child RSS inherited parent padding: {res['max_rss_bytes']}"
    assert pad[0] == 1
