#!/usr/bin/env python3
"""
Cuts the cost tables in `docs/investigations/cost-table.md` for every target, in one command.

Why this exists
---------------
`docs/design/upcall.md` and `docs/design/downcall-design.md` both carry cost tables, and both have been
invalidated at least once by numbers that were transcribed by hand from a run whose conditions were
not written down next to them. The two failures were:

  1. a number copied into a table with no record of the warmup it was taken at (the 3 000-warmup
     round -- every figure in it turned out to be a measure of how warm the host JIT happened to be),
  2. a number taken on a machine that had other work on it (the same commit read 672 and 1076 ns in
     one bisect, and the round had to be thrown away).

So this script does two things a person doing it by hand does not reliably do: it records the
conditions in the same artefact as the numbers, and it refuses to measure a busy machine.

It is *not* a Gradle task on purpose
------------------------------------
The obvious shape is `./gradlew costTable`, and it does not work. The task would have to run
`./gradlew :python-multiplatform:desktopTest` for each target, and a nested Gradle invocation on the
same project directory blocks on the outer build's file-hash and execution-history locks -- it
deadlocks rather than failing. So the entry point is `benchmarks/cost-table.sh`, which drives Gradle
from outside any build.

Usage
-----
    ./benchmarks/cost-table.sh                       # collect every default target, then render
    ./benchmarks/cost-table.sh --targets desktop     # one target
    ./benchmarks/cost-table.sh --runs 3              # three runs, rendered as min-max
    python3 benchmarks/cost_table.py render          # re-render from the newest JSON, no runs

Run `--help` for the rest.
"""

from __future__ import annotations

import argparse
import datetime
import glob
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from typing import Any

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESULTS_DIR = os.path.join(REPO, "benchmarks", "results")
DOC = os.path.join(REPO, "docs", "investigations", "cost-table.md")
MARK_BEGIN = "<!-- COST-TABLE:GENERATED BEGIN -- everything between these markers is written by benchmarks/cost_table.py; edit the script, not this -->"
MARK_END = "<!-- COST-TABLE:GENERATED END -->"

MODULE_BUILD = os.path.join(REPO, "python-multiplatform", "build")
BENCHMARK_TEST_SRC = os.path.join(
    REPO, "python-multiplatform", "src", "commonTest", "kotlin",
    "python", "multiplatform", "overhead", "BenchmarkTest.kt",
)

# The 1-minute load average above which collection refuses to run. Eight cores here; anything at or
# above 4 means at least half the machine belongs to someone else, and the numbers below it are
# already the noisiest thing in this repo's history.
LOAD_CEILING = 4.0


# =====================================================================================
# Targets
# =====================================================================================

@dataclass
class Target:
    name: str
    task: str
    #: "gradle-xml"  -- JUnit XML under build/test-results/<task>, benchmark output in <system-out>
    #: "agp-logcat"  -- AGP's androidTest-results; counts in its XML, benchmark output only in the
    #:                  per-test logcat files it writes beside it (its XML has no <system-out> --
    #:                  checked, 251 testcases and 0 system-out nodes)
    kind: str
    results: str
    #: AVD this target must run on, or None for a host target.
    avd: str | None = None
    #: Included in the default set. The 7th target below is real but off by default.
    default: bool = True
    notes: str = ""


TARGETS: list[Target] = [
    Target(
        "desktop", ":python-multiplatform:desktopTest", "gradle-xml",
        "python-multiplatform/build/test-results/desktopTest",
    ),
    Target(
        "iosSimulatorArm64", ":python-multiplatform:iosSimulatorArm64Test", "gradle-xml",
        "python-multiplatform/build/test-results/iosSimulatorArm64Test",
        notes="boots a simulator through simctl; needs Xcode licence acceptance",
    ),
    Target(
        "wasmJs", ":python-multiplatform:wasmJsNodeTest", "gradle-xml",
        "python-multiplatform/build/test-results/wasmJsNodeTest",
        notes="runs under Node",
    ),
    Target(
        "androidNativeArm64", ":python-multiplatform:androidNativeArm64Test", "gradle-xml",
        "python-multiplatform/build/test-results/androidNativeArm64Test",
        avd="pmp_api36",
        notes="pushes the test binary to the device; the task writes the JUnit XML itself",
    ),
    Target(
        "artApi26", ":python-multiplatform:connectedDebugAndroidTest", "agp-logcat",
        "python-multiplatform/build/outputs/androidTest-results/connected/debug",
        avd="pmp_api26",
    ),
    Target(
        "artApi36", ":python-multiplatform:connectedDebugAndroidTest", "agp-logcat",
        "python-multiplatform/build/outputs/androidTest-results/connected/debug",
        avd="pmp_api36",
    ),
    # Not in the default six, but the docs' upcall table has an androidNative row per API level, so
    # the second one has to be reachable by name.
    Target(
        "androidNativeArm64Api26", ":python-multiplatform:androidNativeArm64Test", "gradle-xml",
        "python-multiplatform/build/test-results/androidNativeArm64Test",
        avd="pmp_api26", default=False,
    ),
]

BY_NAME = {t.name: t for t in TARGETS}
DEFAULT_TARGETS = [t.name for t in TARGETS if t.default]


# =====================================================================================
# Parsing the benchmark output
# =====================================================================================

# `Benchmark.printReport()`:  "PyLong_FromLongLong            |     100000 |          124.70"
REPORT_ROW = re.compile(r"^(?P<name>\S.*?)\s+\|\s+(?P<iters>\d+)\s+\|\s+(?P<ns>-?[\d.]+)\s*$")
REPORT_HEAD = re.compile(r"^Operation\s+\|\s+Iterations\s+\|\s+ns / op\s*$")

# The block reports: "--- Upcall boundary cost: MacOS 15.5 (arm64) / JVM 21.0.8 ---"
BLOCK_TITLE = re.compile(r"^-{3} (?P<title>.+?) -{3}\s*$")
RULE = re.compile(r"^-{10,}\s*$")
# "iterations per loop: 10000, warmup: 100000"  /  "iterations per row: 10000 x 3 (best taken), warmup: 5000; ..."
CONDITION_LINE = re.compile(r"^\s*iterations\b.*warmup\s*[:=]", re.IGNORECASE)
# "  upcall to Kotlin                      510.31 ns"   |   "  ...ratio   3.71x"
#
# The separator is *not* required, because in the output as it stands there frequently is none:
# `UpcallBoundaryCostTest.report()` pads its labels to 54 columns and five of its labels are longer
# than that, so the value is printed hard against the label --
#
#     "  PyObject_CallObject on a Python def (downcall, same shape)137.06 ns"
#
# and a parser that insists on whitespace silently drops exactly the five downcall rows the
# downcall table is made of. (That is a defect in the test's formatting, reported rather than fixed
# here -- fixing it would edit a `commonTest` file, and this has to read output that already
# exists in any case.) The value is therefore anchored on the trailing unit and the lookbehind
# keeps a lazy label from eating the value's leading digits: without it, "...each) 110.61 ns"
# parses as label "...each) 1", value 10.61.
_VALUE = r"(?<![\d.])(?P<value>-?\d+(?:\.\d+)?)"
METRIC_NS = re.compile(rf"^\s{{2,}}(?P<label>.*?){_VALUE}\s*(?P<unit>ns|us|ms)\s*$")
METRIC_X = re.compile(rf"^\s{{2,}}(?P<label>.*?){_VALUE}x\s*$")

# logcat:  "08-12 22:33:41.484  5710  5724 I System.out: PyLong_FromLongLong | 100000 | 1250.80"
LOGCAT = re.compile(
    r"^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+\s+[VDIWEFS]\s+System\.out:\s?(?P<rest>.*)$"
)


def normalise(label: str) -> str:
    """A metric key that survives the padding and the leading '...' continuation markers."""
    return re.sub(r"\s+", " ", label.strip().lstrip(".").strip()).lower()


def parse_benchmark_reports(lines: list[str]) -> list[dict[str, Any]]:
    """Rows printed by `Benchmark.printReport()`. One dict per row."""
    rows: list[dict[str, Any]] = []
    inside = False
    for line in lines:
        if REPORT_HEAD.match(line):
            inside = True
            continue
        if not inside:
            continue
        if RULE.match(line):
            continue
        m = REPORT_ROW.match(line)
        if m:
            rows.append({
                "source": "Benchmark.printReport",
                "label": m.group("name").strip(),
                "key": normalise(m.group("name")),
                "iterations": int(m.group("iters")),
                "value": float(m.group("ns")),
                "unit": "ns",
            })
        elif line.strip() == "":
            inside = False
    return rows


def parse_blocks(lines: list[str]) -> list[dict[str, Any]]:
    """
    The `--- Title: platform ---` reports (`UpcallBoundaryCostTest`, `GeneratedProxyCostTest`).

    Deliberately generic rather than a list of known labels: a test that adds a row should show up
    in the JSON without this script being edited, and the renderer -- not the parser -- is where the
    doc tables choose which labels they want.
    """
    blocks: list[dict[str, Any]] = []
    current: dict[str, Any] | None = None
    section = ""
    for line in lines:
        m = BLOCK_TITLE.match(line)
        if m:
            title = m.group("title")
            # "Upcall boundary cost: MacOS 15.5 (arm64) / JVM 21.0.8" -- the tail is the platform's
            # own account of itself, which is the most trustworthy device identifier available.
            name, _, plat = title.partition(": ")
            current = {
                "block": name.strip(),
                "platform": plat.strip() or None,
                "conditions": {},
                "metrics": [],
            }
            blocks.append(current)
            section = ""
            continue
        if current is None:
            continue
        if RULE.match(line):
            current = None
            continue
        if CONDITION_LINE.match(line):
            for key, value in re.findall(r"([A-Za-z][A-Za-z ]*?)\s*:\s*([^,;]+)", line):
                current["conditions"][key.strip()] = value.strip()
            continue
        for pattern, unit in ((METRIC_NS, None), (METRIC_X, "x")):
            m2 = pattern.match(line)
            if m2:
                current["metrics"].append({
                    "source": current["block"],
                    "section": section,
                    "label": m2.group("label").strip(),
                    "key": normalise(m2.group("label")),
                    "value": float(m2.group("value")),
                    "unit": unit or m2.groupdict().get("unit", "ns"),
                })
                break
        else:
            stripped = line.strip()
            # An un-indented, un-numbered line inside a block is that block's section heading.
            if stripped and not line.startswith("  "):
                section = stripped
    return blocks


def read_benchmark_test_warmups() -> dict[str, int]:
    """
    `BenchmarkTest` does not print the warmup it used, so it is read out of the source at the commit
    being measured. Quoting a benchmark without its warmup is exactly what invalidated the last two
    tables, so this is not optional -- if it cannot be read, that fact is recorded rather than
    guessed.
    """
    out: dict[str, int] = {}
    try:
        text = open(BENCHMARK_TEST_SRC, encoding="utf-8").read()
    except OSError as exc:
        return {"error": str(exc)}  # type: ignore[dict-item]
    for name in ("WARMUP", "WARMUP_BULK"):
        m = re.search(rf"const val {name}\s*=\s*([\d_]+)", text)
        if m:
            out[name] = int(m.group(1).replace("_", ""))
    return out


# =====================================================================================
# Reading a target's results off disk
# =====================================================================================

def _count(xml_files: list[str]) -> dict[str, int]:
    tests = failures = skipped = errors = 0
    for f in xml_files:
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError:
            continue
        roots = [root] if root.tag == "testsuite" else root.findall(".//testsuite")
        for suite in roots:
            tests += int(suite.attrib.get("tests", 0))
            failures += int(suite.attrib.get("failures", 0))
            skipped += int(suite.attrib.get("skipped", 0))
            errors += int(suite.attrib.get("errors", 0))
    return {"tests": tests, "failures": failures, "skipped": skipped, "errors": errors}


def _stdout_from_xml(xml_files: list[str]) -> list[tuple[str, list[str]]]:
    """
    (origin test class, its captured stdout), one entry per suite.

    Grouped by origin rather than concatenated because **the warmup is a property of the test class,
    not of the target**: `BenchmarkTest` warms 100 000, `reflection/UpcallOverheadTest` takes
    `Benchmark.run`'s 1 000 default, `GeneratedProxyCostTest` warms 5 000. All three print through
    the same `Benchmark.printReport`, so a flat list of rows would put three different warmups in
    one column under one warmup caption -- which is the exact error that invalidated this table
    once already.
    """
    groups: list[tuple[str, list[str]]] = []
    for f in sorted(xml_files):
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError:
            continue
        default = os.path.basename(f).removeprefix("TEST-").removesuffix(".xml")
        for suite in ([root] if root.tag == "testsuite" else root.findall(".//testsuite")):
            lines: list[str] = []
            for node in suite.iter():
                if node.tag in ("system-out", "system-err") and node.text:
                    lines.extend(node.text.splitlines())
            if lines:
                groups.append((suite.attrib.get("name") or default, lines))
    return groups


def harvest(target: Target) -> dict[str, Any]:
    """Everything readable out of one finished run of one target."""
    root = os.path.join(REPO, target.results)
    if target.kind == "gradle-xml":
        xml_files = sorted(glob.glob(os.path.join(root, "**", "*.xml"), recursive=True))
        groups = _stdout_from_xml(xml_files)
        devices = []
    else:  # agp-logcat
        # AGP names both its XML and its per-device directory after the *device*
        # ("pmp_api26(AVD) - 8.0.0"), and it writes one of each per attached device. ANDROID_SERIAL
        # is supposed to leave exactly one, but if it ever does not, reading both would silently
        # mix two emulators' numbers into one target's row -- so the AVD is matched explicitly.
        def mine(path: str) -> bool:
            return target.avd is None or os.path.basename(path).startswith(
                ("TEST-" + target.avd, target.avd))

        xml_files = sorted(f for f in glob.glob(os.path.join(root, "TEST-*.xml")) if mine(f))
        devices = sorted(
            d for d in glob.glob(os.path.join(root, "*")) if os.path.isdir(d) and mine(d)
        )
        groups = []
        for device in devices:
            for f in sorted(glob.glob(os.path.join(device, "logcat-*.txt"))):
                # "logcat-<fully.qualified.Class>-<method>.txt"; neither part may contain a dash.
                stem = os.path.basename(f).removeprefix("logcat-").removesuffix(".txt")
                origin = stem.rsplit("-", 1)[0]
                lines = []
                with open(f, encoding="utf-8", errors="replace") as fh:
                    for raw in fh:
                        m = LOGCAT.match(raw.rstrip("\n"))
                        if m:
                            lines.append(m.group("rest"))
                if lines:
                    # One file is one test; terminate it so a block cannot run past its own test.
                    groups.append((origin, lines + [""]))

    rows: list[dict[str, Any]] = []
    blocks: list[dict[str, Any]] = []
    for origin, lines in groups:
        for row in parse_benchmark_reports(lines):
            row["origin"] = origin
            rows.append(row)
        for block in parse_blocks(lines):
            block["origin"] = origin
            blocks.append(block)
    return {
        "suite": _count(xml_files),
        "resultFiles": len(xml_files),
        "deviceDirs": [os.path.basename(d) for d in devices],
        "rows": rows,
        "blocks": blocks,
    }


# =====================================================================================
# Devices
# =====================================================================================

def adb_path() -> str | None:
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if home:
        candidate = os.path.join(home, "platform-tools", "adb")
        if os.path.isfile(candidate):
            return candidate
    return shutil.which("adb")


def attached_devices() -> dict[str, str]:
    """serial -> AVD name (or the serial again, for a physical device)."""
    adb = adb_path()
    if not adb:
        return {}
    try:
        out = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=30).stdout
    except (OSError, subprocess.SubprocessError):
        return {}
    found: dict[str, str] = {}
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serial = parts[0]
            name = serial
            try:
                r = subprocess.run([adb, "-s", serial, "emu", "avd", "name"],
                                   capture_output=True, text=True, timeout=30)
                first = r.stdout.strip().splitlines()
                if first and first[0].strip() and first[0].strip() != "KO":
                    name = first[0].strip()
            except (OSError, subprocess.SubprocessError):
                pass
            found[serial] = name
    return found


def device_props(serial: str) -> dict[str, str]:
    adb = adb_path()
    props = {}
    if not adb:
        return props
    for prop in ("ro.build.version.sdk", "ro.build.version.release",
                 "ro.product.cpu.abi", "ro.product.model"):
        try:
            r = subprocess.run([adb, "-s", serial, "shell", "getprop", prop],
                               capture_output=True, text=True, timeout=30)
            props[prop] = r.stdout.strip()
        except (OSError, subprocess.SubprocessError):
            pass
    return props


def booted_simulators() -> list[str]:
    if not shutil.which("xcrun"):
        return []
    try:
        r = subprocess.run(["xcrun", "simctl", "list", "devices", "booted"],
                           capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.SubprocessError):
        return []
    return [ln.strip() for ln in r.stdout.splitlines() if ln.strip().startswith(("iPhone", "iPad"))]


# =====================================================================================
# Host conditions
# =====================================================================================

def loadavg() -> list[float]:
    try:
        return [round(v, 2) for v in os.getloadavg()]
    except OSError:
        return []


def git(*args: str) -> str:
    try:
        return subprocess.run(["git", "-C", REPO, *args], capture_output=True,
                              text=True, timeout=60).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def host_facts() -> dict[str, Any]:
    def sysctl(key: str) -> str:
        try:
            return subprocess.run(["sysctl", "-n", key], capture_output=True,
                                  text=True, timeout=30).stdout.strip()
        except (OSError, subprocess.SubprocessError):
            return ""

    return {
        "os": f"{platform.system()} {platform.release()}",
        "machine": platform.machine(),
        "cpu": sysctl("machdep.cpu.brand_string") or platform.processor(),
        "cores": os.cpu_count(),
        "cpuBrandCores": sysctl("hw.ncpu"),
    }


# =====================================================================================
# Collect
# =====================================================================================

def clean_results(target: Target) -> None:
    """
    CLAUDE.md: a crashed run leaves the previous run's XML behind and it gets counted as this run's.
    The same trap applies to the parsed numbers, and worse -- a stale XML yields a *plausible*
    benchmark row from a different commit.
    """
    root = os.path.join(REPO, target.results)
    if os.path.isdir(root):
        shutil.rmtree(root)


def run_target(target: Target, run_index: int, args: argparse.Namespace) -> dict[str, Any]:
    stamp = time.strftime("%Y%m%d-%H%M%S")
    log = os.path.join(RESULTS_DIR, "logs", f"{target.name}-run{run_index}-{stamp}.log")
    os.makedirs(os.path.dirname(log), exist_ok=True)

    record: dict[str, Any] = {
        "target": target.name,
        "task": target.task,
        "run": run_index,
        "requiresDevice": target.avd is not None,
        "notes": target.notes,
        "log": os.path.relpath(log, REPO),
    }

    env = dict(os.environ)
    if target.avd:
        devices = attached_devices()
        serial = next((s for s, name in devices.items() if name == target.avd), None)
        if serial is None:
            record.update({
                "status": "skipped",
                "reason": (
                    f"AVD '{target.avd}' is not attached. "
                    f"Attached now: {', '.join(f'{s}={n}' for s, n in devices.items()) or 'none'}."
                ),
                "hint": (
                    f"$ANDROID_HOME/emulator/emulator -avd {target.avd} "
                    f"-no-snapshot-save -no-boot-anim &   # then re-run this target"
                ),
            })
            # Loud, not silent: an absent emulator has to be visible in the run's own output, or
            # the rendered table looks like the target was measured and came out blank.
            print(f"  !! SKIPPED {target.name}: {record['reason']}", flush=True)
            print(f"     to fix: {record['hint']}", flush=True)
            return record
        record["device"] = {"serial": serial, "avd": target.avd, "props": device_props(serial)}
        env["ANDROID_SERIAL"] = serial

    if target.kind == "agp-logcat":
        # AGP writes one subdirectory per attached device. Pinning ANDROID_SERIAL is what makes
        # "one emulator at a time" true, and clearing the parent is what stops the other emulator's
        # directory from a previous run being read as this one's.
        clean_results(target)
    else:
        clean_results(target)

    command = [
        os.path.join(REPO, "gradlew"), target.task,
        "--console=plain",
        # `--rerun` and not `--tests`: narrowing the suite changes what is being measured (this
        # repo lost a bisect round to exactly that), and without `--rerun` Gradle reports the
        # previous run's XML as UP-TO-DATE.
        "--rerun",
    ]
    if target.avd and target.task.endswith("androidNativeArm64Test"):
        command.append(f"-PandroidNativeTestSerial={record['device']['serial']}")

    record["command"] = " ".join(command)
    record["loadBefore"] = loadavg()
    simulators_before = set(booted_simulators()) if "ios" in target.name.lower() else set()

    print(f"  -> {target.name}: {' '.join(command)}", flush=True)
    started = time.time()
    with open(log, "w", encoding="utf-8") as fh:
        proc = subprocess.run(command, cwd=REPO, env=env, stdout=fh,
                              stderr=subprocess.STDOUT, text=True)
    record["wallSeconds"] = round(time.time() - started, 1)
    record["exitCode"] = proc.returncode
    record["loadAfter"] = loadavg()

    if simulators_before is not None and "ios" in target.name.lower():
        after = booted_simulators()
        record["simulators"] = {
            "bootedAfter": after,
            "newlyBooted": [s for s in after if s not in simulators_before],
        }

    record.update(harvest(target))
    suite = record.get("suite", {})

    # `ANDROID_SERIAL` is what pins `connectedDebugAndroidTest` to one emulator, and if AGP ever
    # stops honouring it the failure is silent: a second device's directory appears and half this
    # target's rows come from the wrong API level. Checked rather than trusted.
    if target.kind == "agp-logcat":
        found = record.get("deviceDirs", [])
        if len(found) != 1:
            record["deviceDirWarning"] = (
                f"expected exactly one result directory for AVD '{target.avd}', found "
                f"{found or 'none'} -- ANDROID_SERIAL may not have pinned the run"
            )
            print(f"     !! {record['deviceDirWarning']}", flush=True)
    record["status"] = "ok" if proc.returncode == 0 else "failed"
    if not record["rows"] and not record["blocks"]:
        record["status"] = "no-benchmark-output"
        record["reason"] = (
            "the task finished but no benchmark output was found in its results; "
            f"see {record['log']}"
        )
    print(
        f"     exit={proc.returncode} {record['wallSeconds']}s "
        f"tests={suite.get('tests')} failures={suite.get('failures')} "
        f"skipped={suite.get('skipped')} rows={len(record['rows'])} blocks={len(record['blocks'])}",
        flush=True,
    )
    return record


def collect(args: argparse.Namespace) -> str:
    names = args.targets or DEFAULT_TARGETS
    unknown = [n for n in names if n not in BY_NAME]
    if unknown:
        sys.exit(f"unknown target(s): {', '.join(unknown)}\nknown: {', '.join(BY_NAME)}")

    load = loadavg()
    guard = "ok"
    if load and load[0] > args.load_ceiling:
        if not args.allow_busy:
            sys.exit(
                f"1-minute load average is {load[0]}, ceiling is {args.load_ceiling}.\n"
                "This machine has other work on it and the numbers would be a measure of that "
                "work. In this repo the same commit has read 672 and 1076 ns under load and a\n"
                "whole bisect round had to be discarded.\n"
                "Wait for the machine to go quiet, or pass --allow-busy to record the run as "
                "contaminated."
            )
        guard = "overridden"
        print(f"!! collecting at load {load[0]} (> {args.load_ceiling}) -- "
              "every number in this run is marked contaminated", flush=True)

    os.makedirs(RESULTS_DIR, exist_ok=True)
    report: dict[str, Any] = {
        "schema": 2,
        "capturedAt": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
        "commit": git("rev-parse", "HEAD"),
        "commitSubject": git("log", "-1", "--pretty=%s"),
        "branch": git("rev-parse", "--abbrev-ref", "HEAD"),
        "workingTreeDirty": bool(git("status", "--porcelain")),
        "worktree": REPO,
        "host": host_facts(),
        "loadAtStart": load,
        "loadGuard": guard,
        "loadCeiling": args.load_ceiling,
        "contaminated": guard == "overridden",
        "runsRequested": args.runs,
        "targetsRequested": names,
        "benchmarkTestWarmup": read_benchmark_test_warmups(),
        "runs": [],
    }

    # Sequential, always, and device targets one emulator at a time. Two targets measured at once
    # measure each other; there are two emulators attached and only one may be driven at a time.
    for run_index in range(1, args.runs + 1):
        for name in names:
            print(f"[{run_index}/{args.runs}] {name}", flush=True)
            report["runs"].append(run_target(BY_NAME[name], run_index, args))

    report["loadAtEnd"] = loadavg()
    out = args.out or os.path.join(
        RESULTS_DIR, f"cost-table-{time.strftime('%Y%m%d-%H%M%S')}.json")
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=2, sort_keys=False)
        fh.write("\n")
    write_tsv(report, os.path.splitext(out)[0] + ".tsv")
    print(f"\nwrote {os.path.relpath(out, REPO)}", flush=True)
    return out


def write_tsv(report: dict[str, Any], path: str) -> None:
    """One row per measured quantity. Long format, so `sort`/`join`/`awk` all work on it."""
    columns = ["target", "run", "status", "origin", "source", "section", "label",
               "value", "unit", "iterations", "platform", "commit", "contaminated"]
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\t".join(columns) + "\n")
        for run in report["runs"]:
            base = {
                "target": run["target"],
                "run": run["run"],
                "status": run.get("status", ""),
                "commit": report["commit"][:12],
                "contaminated": str(report["contaminated"]).lower(),
            }
            for row in run.get("rows", []):
                fh.write("\t".join(str(x) for x in [
                    base["target"], base["run"], base["status"], row.get("origin", ""),
                    row["source"], "",
                    row["label"], row["value"], row["unit"], row.get("iterations", ""),
                    "", base["commit"], base["contaminated"],
                ]) + "\n")
            for block in run.get("blocks", []):
                for metric in block["metrics"]:
                    fh.write("\t".join(str(x) for x in [
                        base["target"], base["run"], base["status"], block.get("origin", ""),
                        metric["source"],
                        metric["section"], metric["label"], metric["value"], metric["unit"],
                        block["conditions"].get("iterations per loop", ""),
                        block.get("platform") or "", base["commit"], base["contaminated"],
                    ]) + "\n")


# =====================================================================================
# Render
# =====================================================================================

UPCALL_BLOCK = "Upcall boundary cost"
REPORT = "Benchmark.printReport"

#: The test class each doc table is entitled to read. Rows printed through the same
#: `Benchmark.printReport` by a *different* class were warmed differently and are not comparable
#: with these, so they are shown separately with their origin named.
ORIGIN_BENCHMARK_TEST = "overhead.BenchmarkTest"

UPCALL_COLUMNS = [
    ("upcall", "upcall to kotlin"),
    ("downcall, same shape", "pyobject_callobject on a python def (downcall, same shape)"),
    ("trampoline alone", "upcalltrampoline.invoke from kotlin (no shim, no boundary)"),
    ("upcall / downcall", "upcall / downcall of the same shape"),
    ("upcall / trampoline", "upcall / trampoline alone, gil held (what the shim adds)"),
]

DOWNCALL_COLUMNS = [
    ("empty `withPython` scope", UPCALL_BLOCK, "python3.withpython { } with nothing in it, for scale"),
    ("`Py_IncRef` + `Py_DecRef`", UPCALL_BLOCK, "py_incref + py_decref (2 downcalls, a gil scope each)"),
    ("`PyObject_CallObject`", UPCALL_BLOCK, "pyobject_callobject on a python def (downcall, same shape)"),
    ("`PyUnicode_FromString`, 8 chars", REPORT, "pyunicode_fromstring (8 chars)"),
]

CONTROL_COLUMNS = [
    ("empty Python loop", "empty python loop"),
    ("pure-Python callee", "pure-python callee, same shape"),
    ("GIL-held trampoline", "the same, with the gil already held by the caller"),
]


def _samples(runs: list[dict[str, Any]], target: str, source: str, key: str,
             origin: str | None = None) -> list[float]:
    values: list[float] = []
    for run in runs:
        if run["target"] != target:
            continue
        if source == REPORT:
            values += [r["value"] for r in run.get("rows", [])
                       if r["key"] == key and (origin is None or origin in r.get("origin", ""))]
        else:
            for block in run.get("blocks", []):
                if block["block"] == source and (origin is None or origin in block.get("origin", "")):
                    values += [m["value"] for m in block["metrics"] if m["key"] == key]
    return values


def _fmt_range(values: list[float], unit: str) -> str:
    if not values:
        return "—"
    lo, hi = min(values), max(values)
    suffix = "x" if unit == "x" else " ns"
    if abs(hi - lo) < 0.005:
        return f"{lo:.2f}{suffix}"
    return f"{lo:.2f}–{hi:.2f}{suffix}"


def _status_of(runs: list[dict[str, Any]], target: str) -> tuple[str, str]:
    mine = [r for r in runs if r["target"] == target]
    if not mine:
        return "not requested", ""
    statuses = {r.get("status", "?") for r in mine}
    reason = next((r.get("reason", "") for r in mine if r.get("reason")), "")
    if statuses == {"ok"}:
        return "ok", ""
    return "/".join(sorted(statuses)), reason


def _table(header: list[str], rows: list[list[str]]) -> list[str]:
    out = ["| " + " | ".join(header) + " |",
           "|" + "|".join("---" for _ in header) + "|"]
    out += ["| " + " | ".join(r) + " |" for r in rows]
    return out


def render_markdown(report: dict[str, Any]) -> str:
    runs = report["runs"]
    targets = report.get("targetsRequested") or DEFAULT_TARGETS
    lines: list[str] = []

    dirty = " **(working tree dirty — these numbers are not reproducible from the commit alone)**" \
        if report.get("workingTreeDirty") else ""
    lines.append(f"_Generated by `benchmarks/cost_table.py` from "
                 f"`{report.get('sourceFile', 'the newest JSON')}`._")
    lines.append("")

    if report.get("mixedCommits"):
        lines += [
            "> ## ⚠ Rows in this table come from different commits",
            ">",
            "> Merged with `--allow-mixed` across "
            + ", ".join(f"`{c[:12]}`" for c in report["mixedCommits"])
            + ". Rows from different trees are not comparable with each other; see the source list "
              "below for which cut each target came from.",
            "",
        ]

    if report.get("contaminated"):
        lines += [
            "> ## ⚠ These numbers are contaminated and must not be quoted",
            ">",
            f"> Collected at a 1-minute load average of **{report['loadAtStart'][0]}** with "
            f"`--allow-busy`, against a ceiling of {report['loadCeiling']}. Under load this repo "
            "has read 672 and 1076 ns for the same commit. Re-cut on a quiet machine before any "
            "figure below is used for anything.",
            "",
        ]

    # ---- conditions ----------------------------------------------------------------
    lines += ["### Conditions", "",
              "Every number below is a function of these. A cost table without them is a record of "
              "whatever else was running.", ""]
    host = report["host"]
    warm = report.get("benchmarkTestWarmup", {})
    lines += [
        f"- **commit** `{report['commit'][:12]}` — {report.get('commitSubject', '')}{dirty}",
        f"- **branch** `{report.get('branch', '?')}`, worktree `{report.get('worktree', '?')}`",
        f"- **captured** {report['capturedAt']}"
        + (" — the first of several cuts; each one's own time is in the source table below"
           if len(report.get("sources") or []) > 1 else ""),
        f"- **host** {host.get('cpu', '?')}, {host.get('cores', '?')} cores, "
        f"{host.get('os', '?')} {host.get('machine', '')}",
        f"- **load average** {report.get('loadAtStart')} at start, {report.get('loadAtEnd')} at end "
        f"(ceiling {report['loadCeiling']}, guard `{report.get('loadGuard')}`)"
        + (" — of the first cut only; per-cut figures below"
           if len(report.get("sources") or []) > 1 else ""),
        f"- **runs per target** {report['runsRequested']} — ranges below are min–max over them",
        f"- **`UpcallBoundaryCostTest`** warmup and iteration counts are self-reported per target "
        "in the table below (the test prints them)",
        f"- **`BenchmarkTest`** warmup, read from the source at this commit: "
        f"`WARMUP` = {warm.get('WARMUP', '?')}, `WARMUP_BULK` = {warm.get('WARMUP_BULK', '?')} "
        "(the ≥4096-char string rows use the second — see that file's own caveat)",
        "- **suite scope** full task, never `--tests`-filtered: narrowing the suite changes what is "
        "being measured",
        "",
    ]

    sources = report.get("sources") or []
    if len(sources) > 1:
        lines += ["This table is merged from several cuts — which target came from which:", ""]
        lines += _table(
            ["cut", "commit", "captured", "load at start", "contaminated", "targets"],
            [[f"`{s['file']}`", f"`{s['commit']}`", str(s.get("capturedAt")),
              str(s.get("loadAtStart")), "yes" if s.get("contaminated") else "no",
              ", ".join(f"`{t}`" for t in (s.get("targets") or []))] for s in sources])
        lines.append("")

    rows = []
    for name in targets:
        target = BY_NAME.get(name)
        status, reason = _status_of(runs, name)
        mine = [r for r in runs if r["target"] == name]
        suite = mine[0].get("suite", {}) if mine else {}
        platform_name = ""
        conditions = ""
        for run in mine:
            for block in run.get("blocks", []):
                # Every block that carries one reports the same thing -- the platform's own account
                # of itself -- so any of them will do if the upcall block is missing, which is what
                # a target that did not reach `UpcallBoundaryCostTest` looks like.
                platform_name = platform_name or (block.get("platform") or "")
                if block["block"] == UPCALL_BLOCK:
                    platform_name = block.get("platform") or platform_name
                    conditions = ", ".join(f"{k}: {v}" for k, v in block["conditions"].items())
        device = ""
        for run in mine:
            if run.get("device"):
                props = run["device"].get("props", {})
                device = (f"`{run['device']['avd']}` ({run['device']['serial']}, "
                          f"API {props.get('ro.build.version.sdk', '?')}, "
                          f"{props.get('ro.product.cpu.abi', '?')})")
            for sim in run.get("simulators", {}).get("bootedAfter", [])[:1]:
                device = sim
        wall = "/".join(str(r.get("wallSeconds", "?")) for r in mine)
        counts = (f"{suite.get('tests', '?')}/{suite.get('failures', '?')}/{suite.get('skipped', '?')}"
                  if suite else "—")
        rows.append([
            f"`{name}`",
            f"`{target.task}`" if target else "?",
            status if not reason else f"**{status}**",
            device or ("—" if not (target and target.avd) else "—"),
            platform_name or "—",
            counts,
            wall or "—",
            conditions or "—",
        ])
    lines += _table(
        ["target", "task", "status", "device / simulator", "platform, self-reported",
         "tests/fail/skip", "wall s", "loop counts"], rows)
    lines.append("")

    notes = [(n, _status_of(runs, n)[1]) for n in targets]
    notes = [(n, r) for n, r in notes if r]
    if notes:
        lines += ["**Targets that did not produce numbers, and why:**", ""]
        lines += [f"- `{n}` — {r}" for n, r in notes]
        lines.append("")

    # ---- upcall --------------------------------------------------------------------
    lines += ["### One upcall, per target", "",
              "`UpcallBoundaryCostTest` (`commonTest`), all baselines measured in the same run as "
              "the upcall they price.", ""]
    rows = []
    for name in targets:
        cells = [f"`{name}`"]
        for _, key in UPCALL_COLUMNS:
            values = _samples(runs, name, UPCALL_BLOCK, key)
            unit = "x" if "/" in key else "ns"
            cells.append(_fmt_range(values, unit))
        rows.append(cells)
    lines += _table(["target"] + [c for c, _ in UPCALL_COLUMNS], rows)
    lines.append("")

    lines += ["Controls, from the same block — these are what say whether a row moved because the "
              "boundary moved or because the whole host did:", ""]
    rows = []
    for name in targets:
        cells = [f"`{name}`"]
        for _, key in CONTROL_COLUMNS:
            cells.append(_fmt_range(_samples(runs, name, UPCALL_BLOCK, key), "ns"))
        rows.append(cells)
    lines += _table(["target"] + [c for c, _ in CONTROL_COLUMNS], rows)
    lines.append("")

    # ---- downcall ------------------------------------------------------------------
    lines += ["### One downcall, per target", "",
              "The first three columns are `UpcallBoundaryCostTest`'s own comparison basis read on "
              "its own; the fourth is `BenchmarkTest`'s 8-character string row from the same suite "
              "execution.", ""]
    rows = []
    for name in targets:
        cells = [f"`{name}`"]
        for _, source, key in DOWNCALL_COLUMNS:
            origin = ORIGIN_BENCHMARK_TEST if source == REPORT else None
            cells.append(_fmt_range(_samples(runs, name, source, key, origin), "ns"))
        rows.append(cells)
    lines += _table(["target"] + [c for c, _, _ in DOWNCALL_COLUMNS], rows)
    lines.append("")

    # ---- every BenchmarkTest row ---------------------------------------------------
    def row_labels(match: bool) -> list[tuple[str, str, int, str]]:
        out: list[tuple[str, str, int, str]] = []
        seen: set[tuple[str, str]] = set()
        for run in runs:
            for row in run.get("rows", []):
                origin = row.get("origin", "")
                if (ORIGIN_BENCHMARK_TEST in origin) != match:
                    continue
                ident = (origin, row["key"])
                if ident not in seen:
                    seen.add(ident)
                    out.append((row["label"], row["key"], row.get("iterations", 0), origin))
        return out

    lines += ["### `BenchmarkTest`, every row", "",
              "The whole of `overhead/BenchmarkTest`, one column per target. Iteration counts are "
              "the test's own; the warmup is the `WARMUP` above for every row except the "
              "≥4096-char string ones, which use `WARMUP_BULK`.", ""]
    rows = []
    for label, key, iterations, _ in row_labels(True):
        cells = [f"`{label}`", f"{iterations:,}"]
        for name in targets:
            cells.append(_fmt_range(
                _samples(runs, name, REPORT, key, ORIGIN_BENCHMARK_TEST), "ns"))
        rows.append(cells)
    if rows:
        lines += _table(["row", "iterations"] + [f"`{n}`" for n in targets], rows)
    else:
        lines.append("_No `BenchmarkTest` rows were captured in this cut._")
    lines.append("")

    # ---- everything else that printed through the same harness ---------------------
    others = row_labels(False)
    if others:
        lines += [
            "### Other benchmark rows, by the test that printed them", "",
            "These also come through `Benchmark.printReport`, and they are **not comparable with "
            "the table above or with each other**: each of these classes chooses its own warmup "
            "(`Benchmark.run`'s default is 1 000; `GeneratedProxyCostTest` uses 5 000), and some "
            "run on one target only. They are listed with their origin so a figure cannot be "
            "lifted out of here as if it shared the conditions above.", "",
        ]
        rows = []
        for label, key, iterations, origin in others:
            cells = [f"`{origin.rsplit('.', 1)[-1]}`", f"`{label}`", f"{iterations:,}"]
            for name in targets:
                cells.append(_fmt_range(_samples(runs, name, REPORT, key, origin), "ns"))
            rows.append(cells)
        lines += _table(["printed by", "row", "iterations"] + [f"`{n}`" for n in targets], rows)
        lines.append("")

    return "\n".join(lines)


def merge(paths: list[str], allow_mixed: bool) -> dict[str, Any]:
    """
    Several cuts into one table.

    This exists because of how the machine actually is: there are two emulators and only one may be
    driven at a time, so a full six-target table is often assembled from a host-target cut and one
    or two device cuts taken minutes or hours apart. Merging them is fine *if they are the same
    commit* and a lie if they are not, so that is checked rather than assumed -- a table whose rows
    come from different trees is precisely the failure `downcall-design.md` spent a section
    (`Closed: repository drift, not run-to-run noise`) tracking down.
    """
    reports = []
    for path in paths:
        with open(path, encoding="utf-8") as fh:
            report = json.load(fh)
        report["_path"] = os.path.relpath(path, REPO)
        reports.append(report)

    commits = {r.get("commit", "?") for r in reports}
    if len(commits) > 1 and not allow_mixed:
        listing = "\n".join(f"  {r['_path']}  {r.get('commit', '?')[:12]}  {r.get('capturedAt')}"
                            for r in reports)
        sys.exit("refusing to merge cuts from different commits into one table:\n" + listing +
                 "\nre-cut them at one commit, or pass --allow-mixed to render them with the "
                 "mismatch stated in the table.")

    base = dict(reports[0])
    base.pop("_path", None)
    base["runs"] = [run for r in reports for run in r["runs"]]
    order = {name: i for i, name in enumerate(t.name for t in TARGETS)}
    requested = {name for r in reports for name in (r.get("targetsRequested") or [])}
    base["targetsRequested"] = sorted(requested, key=lambda n: order.get(n, 99))
    base["contaminated"] = any(r.get("contaminated") for r in reports)
    base["runsRequested"] = max(r.get("runsRequested", 1) for r in reports)
    base["mixedCommits"] = sorted(commits) if len(commits) > 1 else None
    base["sources"] = [
        {"file": r["_path"], "commit": r.get("commit", "?")[:12], "capturedAt": r.get("capturedAt"),
         "loadAtStart": r.get("loadAtStart"), "contaminated": r.get("contaminated"),
         "targets": r.get("targetsRequested")}
        for r in reports
    ]
    base["sourceFile"] = ", ".join(os.path.basename(r["_path"]) for r in reports)
    return base


def render(args: argparse.Namespace) -> None:
    paths: list[str] = []
    for entry in (args.input or []):
        paths += [p for p in entry.split(",") if p]
    if not paths:
        candidates = sorted(glob.glob(os.path.join(RESULTS_DIR, "cost-table-*.json")))
        if not candidates:
            sys.exit(f"no collected results in {RESULTS_DIR}; run `collect` first")
        paths = [candidates[-1]]
    report = merge(paths, getattr(args, "allow_mixed", False))
    body = render_markdown(report)

    doc = args.doc
    if not os.path.isfile(doc):
        sys.exit(f"{doc} does not exist; it is the file this renders into")
    text = open(doc, encoding="utf-8").read()
    if MARK_BEGIN not in text or MARK_END not in text:
        sys.exit(f"{doc} has no COST-TABLE:GENERATED markers; refusing to guess where to write")
    head, _, rest = text.partition(MARK_BEGIN)
    _, _, tail = rest.partition(MARK_END)
    updated = f"{head}{MARK_BEGIN}\n\n{body}\n{MARK_END}{tail}"
    if args.check:
        if updated != text:
            sys.exit(f"{os.path.relpath(doc, REPO)} is out of date with "
                     f"{report['sourceFile']}; re-run the renderer")
        print(f"{os.path.relpath(doc, REPO)} is up to date with {report['sourceFile']}")
        return
    with open(doc, "w", encoding="utf-8") as fh:
        fh.write(updated)
    print(f"rendered {report['sourceFile']} -> {os.path.relpath(doc, REPO)}")


# =====================================================================================

def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command")

    def add_collect_args(p: argparse.ArgumentParser) -> None:
        p.add_argument("--targets", type=lambda s: [x for x in s.split(",") if x],
                       help=f"comma-separated; default {','.join(DEFAULT_TARGETS)}. "
                            f"also available: androidNativeArm64Api26")
        p.add_argument("--runs", type=int, default=1,
                       help="runs per target; ranges are rendered min–max over them (default 1)")
        p.add_argument("--out", help="JSON output path (default benchmarks/results/cost-table-<ts>.json)")
        p.add_argument("--allow-busy", action="store_true",
                       help="collect even above the load ceiling, marking the run contaminated")
        p.add_argument("--load-ceiling", type=float, default=LOAD_CEILING,
                       help=f"1-minute load average above which collection refuses (default {LOAD_CEILING})")

    def add_render_args(p: argparse.ArgumentParser) -> None:
        p.add_argument("--in", dest="input", action="append",
                       help="JSON to render; repeatable or comma-separated, and several cuts of "
                            "the same commit are merged (default: newest collected)")
        p.add_argument("--allow-mixed", action="store_true",
                       help="merge cuts taken at different commits, stating the mismatch in the table")
        p.add_argument("--doc", default=DOC, help=f"file to write into (default {os.path.relpath(DOC, REPO)})")
        p.add_argument("--check", action="store_true",
                       help="exit non-zero if the doc is out of date instead of writing it")

    p_collect = sub.add_parser("collect", help="run the suites and record the numbers")
    add_collect_args(p_collect)

    p_render = sub.add_parser("render", help="turn a collected JSON into the doc tables")
    add_render_args(p_render)

    p_all = sub.add_parser("all", help="collect, then render (the default)")
    add_collect_args(p_all)
    add_render_args(p_all)

    # `all` is the default, so `--targets desktop` works without naming a subcommand.
    argv = sys.argv[1:]
    if argv and argv[0] in ("-h", "--help"):
        parser.print_help()
        return
    if not argv or argv[0].startswith("-"):
        argv = ["all"] + argv
    args = parser.parse_args(argv)

    if args.command == "collect":
        collect(args)
    elif args.command == "render":
        render(args)
    else:
        args.input = [collect(args)]
        render(args)


if __name__ == "__main__":
    main()
