#!/usr/bin/env python3
"""Exercise migrated cgroup bindings in packaged code, with optional exporter/performance checks."""

import argparse
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--agent-jar", type=Path, required=True)
    parser.add_argument("--baseline-jar", type=Path)
    parser.add_argument("--exporter-jar", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--java", default="java")
    parser.add_argument("--javac", default="javac")
    args = parser.parse_args()
    candidate = args.agent_jar.resolve(strict=True)
    baseline = args.baseline_jar.resolve(strict=True) if args.baseline_jar else None
    exporter = args.exporter_jar.resolve(strict=True) if args.exporter_jar else None
    evidence = {"measurements": []}
    with tempfile.TemporaryDirectory(prefix="cgroup-migration-") as temporary:
        work = Path(temporary)
        classes = work / "classes"
        classes.mkdir()
        sources = Path(__file__).with_name("migration")
        subprocess.run([args.javac, "--release", "11", "-cp", str(candidate), "-d", str(classes),
                        *[str(p) for p in sorted(sources.glob("*.java"))]], check=True, timeout=30)

        def command(jar, name):
            return [args.java, "-Xms64m", "-Xmx64m", "-cp", str(classes) + os.pathsep + str(jar),
                    "kr.pletor.nodemetrics.metrics." + name]

        for version in ("v1", "v2"):
            result = subprocess.run(command(candidate, "MigrationProbe") + [str(work / version), version],
                                    capture_output=True, text=True, timeout=20)
            if result.returncode or "MIGRATION SAFE:" not in result.stdout:
                raise RuntimeError(f"FAIL {version}: {result.stdout}{result.stderr}")
            print(f"PASS {version}: migrated bindings, ancestors, unavailable/recovery; host completed", flush=True)
        if exporter:
            with socket.socket() as listener:
                listener.bind(("127.0.0.1", 0))
                port = listener.getsockname()[1]
            root = work / "exporter"
            rules = Path(__file__).resolve().parent.parent / "src/main/resources/jmx_exporter_rules_example.yml"
            launch = command(candidate, "MigrationProbe")
            launch.insert(1, f"-javaagent:{exporter}={port}:{rules}")
            result = subprocess.run(launch + [str(root), "v2", str(port)], capture_output=True, text=True, timeout=30)
            if result.returncode:
                raise RuntimeError(f"FAIL exporter: {result.stdout}{result.stderr}")
            for phase, group in (("before", "group1"), ("after", "group2")):
                text = (root / f"{phase}.txt").read_text()
                for metric in ("memmetrics_memoryoomkilltotal_total", "pressuremetrics_memorysometotalmicros_total",
                               "pidsmetrics_pidslimit"):
                    lines = [line for line in text.splitlines() if line.startswith(f"pletor_cgroup_{metric}{{")]
                    if len(lines) != 1 or not re.search(r'cgrouppath="/' + group + r'/leaf"', lines[0], re.I):
                        raise RuntimeError(f"Wrong {phase} identity for {metric}: {lines}")
            missing = (root / "unavailable.txt").read_text()
            if re.search(r"^pletor_cgroup_(?:memmetrics_memoryoomkilltotal|pressuremetrics_memorysometotalmicros)_total", missing, re.M):
                raise RuntimeError("Unavailable membership still exports a cgroup counter")
            evidence["exporter"] = "HTTP 200 in all phases; memory/PID/PSI labels followed migration; absent counters omitted"
            print("PASS exporter: new group labels replace old series, including increasing PSI totals", flush=True)
        if baseline:
            result = subprocess.run(command(baseline, "MigrationProbe") + [str(work / "baseline"), "v2"],
                                    capture_output=True, text=True, timeout=20)
            if result.returncode != 1 or "MIGRATION FAILURE: stale binding after membership change" not in result.stdout:
                raise RuntimeError(f"Baseline did not reproduce stale binding: {result.stdout}{result.stderr}")
            evidence["negativeControl"] = result.stdout.strip()
            print("PASS baseline: readable old group remains bound after migration", flush=True)
            for mode in ("poll", "resolve", "getters"):
                for fork in range(3):
                    for variant, jar in (("baseline", baseline), ("candidate", candidate)):
                        result = subprocess.run(command(jar, "MigrationBenchmark") + [mode, str(work / f"{mode}-{fork}-{variant}")],
                                                capture_output=True, text=True, check=True, timeout=30)
                        evidence["measurements"].append(dict(json.loads(result.stdout), variant=variant, fork=fork))
                print(f"PASS {mode} measurements: three alternating forks", flush=True)
    evidence["status"] = "passed"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(evidence, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
