#!/usr/bin/env python3
"""Check preserved-metadata config edits in a real agent JVM; optionally measure polling cost."""

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
    with tempfile.TemporaryDirectory(prefix="config-reload-") as temporary:
        work = Path(temporary)
        classes = work / "classes"
        classes.mkdir()
        sources = Path(__file__).with_name("reload")
        subprocess.run([args.javac, "--release", "11", "-cp", str(agent), "-d", str(classes),
                        *[str(p) for p in sorted(sources.glob("*.java"))]], check=True, timeout=30)
        config = work / "agent.yml"

        def probe(jar):
            config.write_text("refresh_interval_seconds: 2\nfsmetrics_paths: []\n", encoding="utf-8")
            return subprocess.run([args.java, "-Xmx64m", f"-javaagent:{jar}={config}",
                                   "-cp", str(classes) + os.pathsep + str(jar),
                                   "kr.pletor.nodemetrics.agent.ConfigReloadProbe", str(config)],
                                  cwd=work, capture_output=True, text=True, timeout=40)

        result = probe(agent)
        if result.returncode or "RELOAD SAFE:" not in result.stdout:
            raise RuntimeError(f"Config reload regression: {result.stdout}{result.stderr}")
        print(result.stdout.strip(), flush=True)
        if baseline:
            result = probe(baseline)
            if result.returncode != 1 or "RELOAD FAILURE: same-mtime edit was not applied" not in result.stdout:
                raise RuntimeError(f"Baseline did not reproduce missed reload: {result.stdout}{result.stderr}")
            evidence["negativeControl"] = result.stdout.strip()
            print("PASS baseline: same-size/same-mtime edit remains unapplied", flush=True)
            for size in (512, 65_536):
                for fork in range(3):
                    for variant, jar in (("baseline", baseline), ("candidate", agent)):
                        result = subprocess.run([args.java, "-Xms64m", "-Xmx64m", "-cp",
                                                 str(classes) + os.pathsep + str(jar),
                                                 "kr.pletor.nodemetrics.agent.ConfigCheckBenchmark", str(size)],
                                                capture_output=True, text=True, check=True, timeout=30)
                        evidence["measurements"].append(dict(json.loads(result.stdout), variant=variant, fork=fork))
                print(f"PASS polling cost: {size} bytes, three alternating forks", flush=True)
    evidence["status"] = "passed"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(evidence, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
