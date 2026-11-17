#!/usr/bin/env python3
"""Exercise packaged launcher failures in bounded, disposable host JVMs."""

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
    agent = args.agent_jar.resolve()
    script_dir = Path(__file__).resolve().parent
    probe = "kr.pletor.nodemetrics.agent.LauncherFailureProbe"
    failures = []
    with tempfile.TemporaryDirectory(prefix="launcher-isolation-") as temporary:
        work = Path(temporary)
        classes = work / "classes"
        classes.mkdir()
        subprocess.run([args.javac, "--release", "11", "-cp", str(agent), "-d", str(classes),
                        str(script_dir / "LauncherFailureProbe.java")], check=True, timeout=30)
        guard_agent = work / "host-guards.jar"
        with zipfile.ZipFile(guard_agent, "w", zipfile.ZIP_DEFLATED) as guard:
            guard.writestr("META-INF/MANIFEST.MF", f"Manifest-Version: 1.0\nPremain-Class: {probe}\n\n")
            for compiled in sorted(classes.rglob("*.class")):
                guard.write(compiled, compiled.relative_to(classes).as_posix())
        cases = [("thread-creation", agent, False), ("background-error", agent, False)]
        for missing in ("ThrottledLogger", "AgentLauncher$InitTask", "AgentLauncher$SwallowingHandler"):
            broken = work / f"missing-{missing}.jar"
            omitted = f"kr/pletor/nodemetrics/agent/{missing}.class"
            with zipfile.ZipFile(agent) as source, zipfile.ZipFile(broken, "w", zipfile.ZIP_DEFLATED) as target:
                if omitted not in source.namelist():
                    raise ValueError(f"Negative-control class is already absent: {omitted}")
                for entry in source.infolist():
                    if entry.filename != omitted:
                        target.writestr(entry, source.read(entry.filename))
            cases.append((missing, broken, True))

        for name, candidate, attach in cases:
            scenario = "application" if attach else name
            command = [args.java, "-XX:-CreateCoredumpOnCrash",
                       f"-Djava.util.logging.manager={probe}$UnexpectedLogManager"]
            if attach:
                # Install blocked stderr/default-handler/JUL guards before the candidate's premain.
                command.append(f"-javaagent:{guard_agent}")
                command.append(f"-javaagent:{candidate}")
            command.extend(["-cp", str(classes) + os.pathsep + str(candidate), probe, scenario])
            try:
                result = subprocess.run(command, cwd=work, capture_output=True, text=True, timeout=10)
                marker = f"APPLICATION STARTED: {scenario}"
                if result.returncode != 0 or marker not in result.stdout.splitlines():
                    failures.append(name)
                    print(f"FAIL {name}: exit={result.returncode}\n{result.stdout}{result.stderr}", flush=True)
                else:
                    print(f"PASS {name}: host main completed; no application error handler or JUL initialization",
                          flush=True)
            except subprocess.TimeoutExpired:
                failures.append(name)
                print(f"FAIL {name}: host JVM did not exit within 10 seconds", flush=True)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
