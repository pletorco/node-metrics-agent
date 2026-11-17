#!/usr/bin/env python3
"""Verify retired collector ownership; optionally compare execution/diagnostic costs to main."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--agent-jar", type=Path, required=True)
    parser.add_argument("--baseline-jar", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--java", default="java")
    parser.add_argument("--javac", default="javac")
    args = parser.parse_args()
    agent = args.agent_jar.resolve(strict=True)
    baseline = args.baseline_jar.resolve(strict=True) if args.baseline_jar else None
    evidence = {"measurements": []}
    with tempfile.TemporaryDirectory(prefix="collector-ownership-") as temporary:
        classes = Path(temporary) / "classes"
        classes.mkdir()
        sources = Path(__file__).with_name("ownership")
        subprocess.run([args.javac, "--release", "11", "-cp", str(agent), "-d", str(classes),
                        *[str(p) for p in sorted(sources.glob("*.java"))]], check=True, timeout=30)

        def command(jar, name, *extra):
            return [args.java, "-Xms64m", "-Xmx64m", "-cp", str(classes) + os.pathsep + str(jar),
                    "kr.pletor.nodemetrics.agent." + name, *extra]

        result = subprocess.run(command(agent, "CollectorOwnershipProbe"), capture_output=True,
                                text=True, timeout=20)
        if result.returncode or "OWNERSHIP SAFE:" not in result.stdout:
            raise RuntimeError(f"Ownership regression failed: {result.stdout}{result.stderr}")
        print(result.stdout.strip(), flush=True)
        if baseline:
            result = subprocess.run(command(baseline, "CollectorOwnershipProbe"), capture_output=True,
                                    text=True, timeout=20)
            if result.returncode != 1 or "OWNERSHIP FAILURE: retired hang missing" not in result.stdout:
                raise RuntimeError(f"Baseline did not reproduce retired ownership loss: {result.stdout}{result.stderr}")
            evidence["negativeControl"] = result.stdout.strip()
            print("PASS baseline: removal hides the original running collector", flush=True)
            for mode in ("poll", "diagnostics"):
                for tasks in (16, 270):
                    for fork in range(3):
                        for label, jar in (("baseline", baseline), ("candidate", agent)):
                            result = subprocess.run(command(jar, "OwnershipBenchmark", mode, str(tasks)),
                                                    capture_output=True, text=True, check=True, timeout=30)
                            measurement = dict(json.loads(result.stdout), variant=label, fork=fork)
                            evidence["measurements"].append(measurement)
                    print(f"PASS measurements: {mode}, {tasks} tasks, three alternating forks", flush=True)
    evidence["status"] = "passed"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(evidence, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
