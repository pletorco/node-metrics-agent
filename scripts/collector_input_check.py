#!/usr/bin/env python3
"""Packaged collector boundaries in 32 MiB host JVMs, with optional before/after read evidence."""

import argparse
import hashlib
import platform
import json
import os
from pathlib import Path
import subprocess
import tempfile


def run(command, timeout=30):
    return subprocess.run(command, capture_output=True, text=True, timeout=timeout)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--agent-jar", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--javac", default="javac")
    parser.add_argument("--baseline-jar", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    jar = args.agent_jar.resolve(strict=True)
    baseline = args.baseline_jar.resolve(strict=True) if args.baseline_jar else None
    evidence = {"agentJar": str(jar), "agentSha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
                "baselineSha256": hashlib.sha256(baseline.read_bytes()).hexdigest() if baseline else None,
                "platform": platform.platform(), "java": run([args.java, "-version"]).stderr.strip(),
                "boundaryCases": [], "benchmarks": []}
    with tempfile.TemporaryDirectory(prefix="collector-input-") as temporary:
        work = Path(temporary)
        classes = work / "classes"
        classes.mkdir()
        sources = Path(__file__).with_name("inputs")
        compile_result = run([args.javac, "--release", "11", "-cp", str(jar), "-d", str(classes),
                              *[str(p) for p in sorted(sources.glob("*.java"))]])
        require(compile_result.returncode == 0, compile_result.stdout + compile_result.stderr)
        long_line, total_bytes, many_lines = [work / p for p in ("long.txt", "bytes.txt", "rows.txt")]
        with long_line.open("wb") as output:
            for _ in range(64):
                output.write(b"x" * 1048576)
        total_bytes.write_bytes((b"x" * 255 + b"\n") * 8192)
        many_lines.write_bytes(b"\n" * 65536)
        cases = [("long-line", long_line), ("total-bytes", total_bytes), ("many-lines", many_lines),
                 ("scalar", long_line), ("small-string", long_line), ("mounts", long_line),
                 ("maps", long_line), ("interface-cache", long_line)]

        def command(candidate, mode, source):
            root = work / (mode + "-host")
            root.mkdir(exist_ok=True)
            return [args.java, "-Xmx32m", "-XX:+ExitOnOutOfMemoryError", f"-javaagent:{candidate}",
                    "-cp", str(classes) + os.pathsep + str(candidate),
                    "kr.pletor.nodemetrics.metrics.InputBoundaryProbe", mode, str(root), str(source)]

        for mode, source in cases:
            result = run(command(jar, mode, source))
            require(result.returncode == 0 and f"INPUT BOUNDARY OK {mode}" in result.stdout.splitlines(),
                    f"FAIL {mode}: exit={result.returncode}\n{result.stdout}{result.stderr}")
            evidence["boundaryCases"].append(mode)
            print(f"PASS {mode}: 32 MiB host continued and exited normally", flush=True)
        if baseline:
            result = run(command(baseline, "long-line", long_line))
            require(result.returncode == 3 and "OutOfMemoryError" in result.stdout + result.stderr,
                    f"Negative control did not reproduce the original OOM: {result.returncode}\n"
                    + result.stdout + result.stderr)
            evidence["negativeControl"] = {"exitCode": result.returncode, "result": "original OOM reproduced"}
            print("PASS negative control: unchanged main JAR exited 3 on the same 64 MiB line", flush=True)
            for mode in ("mounts", "interfaces"):
                for rows in (64, 4096):
                    root = work / f"bench-{mode}-{rows}"
                    (root / "net").mkdir(parents=True)
                    (root / "mounts").write_text("".join(
                        f"/dev/disk{i} /data/mount{i} ext4 rw,nosuid,nodev,relatime 0 0\n" for i in range(rows)))
                    (root / "net/dev").write_text("".join(
                        f"eth{i}: 1 0 0 0 0 0 0 0 1 0 0 0 0 0 0 0\n" for i in range(rows)))
                    for label, candidate in (("baseline", baseline), ("bounded", jar)):
                        result = run([args.java, "-Xms128m", "-Xmx128m", "-cp",
                                      str(classes) + os.pathsep + str(candidate),
                                      "kr.pletor.nodemetrics.metrics.InputReadBenchmark",
                                      str(root), mode, str(rows)])
                        require(result.returncode == 0, result.stdout + result.stderr)
                        measurement = json.loads(result.stdout)
                        measurement["version"] = label
                        evidence["benchmarks"].append(measurement)
                        print(json.dumps(measurement), flush=True)
    evidence["status"] = "passed"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(evidence, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
