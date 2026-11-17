#!/usr/bin/env python3
"""Inject packaged engine thread creation failures without affecting the parent JVM/host."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile


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
    version = subprocess.run([args.java, "-version"], capture_output=True, text=True,
                             check=True, timeout=10)
    major = int(re.search(r'version "(\d+)', version.stderr + version.stdout).group(1))
    security_flags = ["-Djava.security.manager=allow"] if major >= 17 else []
    evidence = {"failuresInjected": [], "measurements": []}
    with tempfile.TemporaryDirectory(prefix="engine-startup-") as temporary:
        work = Path(temporary)
        classes = work / "classes"
        classes.mkdir()
        sources = Path(__file__).with_name("startup")
        subprocess.run([args.javac, "--release", "11", "-cp", str(agent), "-d", str(classes),
                        *[str(p) for p in sorted(sources.glob("*.java"))]], check=True, timeout=30)
        probe = "kr.pletor.nodemetrics.agent.EngineStartupProbe"
        guard_agent = work / "host-fault.jar"
        with zipfile.ZipFile(guard_agent, "w", zipfile.ZIP_DEFLATED) as guard:
            guard.writestr("META-INF/MANIFEST.MF", f"Manifest-Version: 1.0\nPremain-Class: {probe}\n\n")
            for compiled in sorted(classes.rglob("*.class")):
                guard.write(compiled, compiled.relative_to(classes).as_posix())
        config = work / "agent.yml"
        config.write_text("refresh_interval_seconds: 1\nfsmetrics_paths: []\n", encoding="utf-8")

        def command(jar, target):
            return [args.java, "-Xmx64m", *security_flags,
                    f"-javaagent:{guard_agent}={target}", f"-javaagent:{jar}={config}",
                    "-cp", str(classes) + os.pathsep + str(jar), probe]

        targets = ["node-metrics-refresh-dispatcher"] + [
            f"node-metrics-refresh-{lane}worker-{index}"
            for lane in ("", "bg-") for index in range(1, 4)]
        for target in targets:
            result = subprocess.run(command(agent, target), cwd=work, capture_output=True,
                                    text=True, timeout=20)
            if result.returncode or "STARTUP SAFE:" not in result.stdout:
                raise RuntimeError(f"FAIL {target}: {result.stdout}{result.stderr}")
            evidence["failuresInjected"].append(target)
            print(f"PASS {target}: disabled, no orphan threads or retries; host completed", flush=True)
        if baseline:
            result = subprocess.run(command(baseline, "node-metrics-refresh-worker-1"), cwd=work,
                                    capture_output=True, text=True, timeout=20)
            if result.returncode != 1 or "baseline NORMAL while startup failed" not in result.stdout:
                raise RuntimeError(f"Baseline did not reproduce false health: {result.stdout}{result.stderr}")
            evidence["negativeControl"] = result.stdout.strip()
            print("PASS baseline: NORMAL reported after injected first-worker failure", flush=True)
            for fork in range(3):
                for variant, jar in (("baseline", baseline), ("candidate", agent)):
                    result = subprocess.run([args.java, "-Xms64m", "-Xmx64m", "-cp",
                                             str(classes) + os.pathsep + str(jar),
                                             "kr.pletor.nodemetrics.agent.StartupBenchmark"],
                                            capture_output=True, text=True, check=True, timeout=45)
                    evidence["measurements"].append(dict(json.loads(result.stdout), variant=variant, fork=fork))
            print("PASS startup CPU/allocation/p99 comparison: three alternating forks", flush=True)
    evidence["status"] = "passed"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(evidence, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
