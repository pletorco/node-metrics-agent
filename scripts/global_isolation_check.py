#!/usr/bin/env python3
"""Compare the host's singleton choices and blocked logging in isolated JVMs."""

import argparse
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--agent-jar", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--javac", default="javac")
    args = parser.parse_args()
    jar = args.agent_jar.resolve()
    failures = []
    with tempfile.TemporaryDirectory(prefix="global-isolation-") as temporary:
        work = Path(temporary)
        classes = work / "classes"
        classes.mkdir()
        source = Path(__file__).with_name("LateGlobalSetup.java").resolve()
        subprocess.run([args.javac, "--release", "11", "-cp", str(jar), "-d", str(classes), str(source)],
                       check=True, timeout=30)
        valid, invalid = work / "valid.yml", work / "invalid.yml"
        valid.write_text("fsmetrics_paths: [/]\n")
        invalid.write_text("fsmetrics_paths: [\n")
        cases = [("control", "late", 12000, None), ("late-valid", "late", 12000, valid),
                 ("early-warning", "late", 1000, invalid), ("no-jmx", "no-jmx", 12000, invalid),
                 ("blocked-output", "blocked-output", 0, valid),
                 ("throwing-output", "throwing-output", 0, valid)]
        for name, mode, delay, config in cases:
            command = [args.java]
            if config:
                command.append(f"-javaagent:{jar}={config}")
            command.extend(["-cp", str(classes) + os.pathsep + str(jar), "LateGlobalSetup", str(delay),
                            mode, str(config is not None).lower()])
            try:
                result = subprocess.run(command, cwd=work, capture_output=True, text=True, timeout=45)
                if result.returncode == 0 and "LATE SETUP OK" in result.stdout.splitlines():
                    print(f"PASS {name}: application singleton choices and execution preserved", flush=True)
                else:
                    failures.append(name)
                    print(f"FAIL {name}: exit={result.returncode}\n{result.stdout}{result.stderr}", flush=True)
            except subprocess.TimeoutExpired:
                failures.append(name)
                print(f"FAIL {name}: host JVM exceeded 45 seconds", flush=True)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
