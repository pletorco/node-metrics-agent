#!/usr/bin/env python3
"""Verify duplicate agent entry, argument selection and MBean ownership in disposable JVMs."""

import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--agent-jar", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--javac", default="javac")
    args = parser.parse_args()
    jar = args.agent_jar.resolve()
    probe = "kr.pletor.nodemetrics.agent.DuplicateAgentProbe"
    failures = []
    with tempfile.TemporaryDirectory(prefix="duplicate-agent-") as temporary:
        work = Path(temporary)
        classes = work / "classes"
        classes.mkdir()
        subprocess.run([args.javac, "--release", "11", "-cp", str(jar), "-d", str(classes),
                        str(Path(__file__).with_name("DuplicateAgentProbe.java"))], check=True, timeout=30)
        host_agent = work / "host-owner.jar"
        with zipfile.ZipFile(host_agent, "w", zipfile.ZIP_DEFLATED) as host:
            host.writestr("META-INF/MANIFEST.MF", f"Manifest-Version: 1.0\nPremain-Class: {probe}\n\n")
            for compiled in sorted(classes.rglob("*.class")):
                host.write(compiled, compiled.relative_to(classes).as_posix())
        cases = [("single", 1), ("double", 2), ("many", 8), ("different-args", 2),
                 ("waiting", 8), ("concurrent-launcher", 0), ("concurrent-direct", 0),
                 ("concurrent-args", 0), ("repeat", 1), ("collision", 2), ("interrupted", 0)]
        for name, copies in cases:
            first, second = work / "first.yml", work / "second.yml"
            first.write_text("refresh_interval_seconds: 1\nfsmetrics_paths: []\n", encoding="utf-8")
            second.write_text("refresh_interval_seconds: 9\nfsmetrics_paths: []\n", encoding="utf-8")
            command = [args.java, "-Xmx64m", "-XX:-CreateCoredumpOnCrash"]
            if name == "collision":
                command.append(f"-javaagent:{host_agent}")
            for index in range(copies):
                config = second if name == "different-args" and index > 0 else first
                command.append(f"-javaagent:{jar}={config}")
            command.extend(["-cp", str(classes) + os.pathsep + str(jar), probe, name,
                            str(first), str(second)])
            try:
                result = subprocess.run(command, cwd=work, capture_output=True, text=True, timeout=45)
                if result.returncode == 0 and f"DUPLICATE SAFE: {name}" in result.stdout.splitlines():
                    print(f"PASS {name}: host main completed; initialization and ownership preserved", flush=True)
                else:
                    failures.append(name)
                    print(f"FAIL {name}: exit={result.returncode}\n{result.stdout}{result.stderr}", flush=True)
            except subprocess.TimeoutExpired:
                failures.append(name)
                print(f"FAIL {name}: host JVM exceeded 45 seconds", flush=True)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
