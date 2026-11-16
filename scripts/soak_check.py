#!/usr/bin/env python3
"""Compare controlled host JVMs with/without the packaged agent; retain soak evidence."""

import argparse
import csv
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import signal
import socket
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
MAX_RESPONSE = 4 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def write_json(path, data):
    Path(path).write_text(json.dumps(data, indent=2) + "\n")


def percentile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)]


def retention(points):
    require(len(points) >= 6, "At least six post-warmup full-GC samples are required")
    # Bound the pairwise estimator independently of the configured run/sample length.
    selected = points if len(points) <= 256 else [points[round(i * (len(points) - 1) / 255)] for i in range(256)]
    slopes = [(b[1] - a[1]) * 60 / (b[0] - a[0])
              for i, a in enumerate(selected) for b in selected[i + 1:] if b[0] > a[0]]
    quarter = max(1, len(points) // 4)
    return {"slopeBytesPerMinute": statistics.median(slopes),
            "growthBytes": statistics.median(p[1] for p in points[-quarter:])
                           - statistics.median(p[1] for p in points[:quarter]),
            "samples": len(points)}


def summarize_samples(path, expected_duration, enabled):
    with Path(path).open() as file:
        reader = csv.DictReader(file)
        rows = []
        for index, row in enumerate(reader):
            require(index < 7500, "Soak report exceeds bounded sample count")
            measured = row.pop("measured")
            require(measured in {"true", "false"}, "Invalid measurement phase")
            values = {key: float(value) for key, value in row.items()}
            require(all(math.isfinite(v) for v in values.values()), "Non-finite soak measurement")
            if measured == "true":
                rows.append(values)
    require(len(rows) >= expected_duration * .8, "Missing measurement windows")
    span = rows[-1]["seconds"] - rows[0]["seconds"]
    require(span >= expected_duration - 2, "Measured run ended prematurely")
    gaps = [b["seconds"] - a["seconds"] for a, b in zip(rows, rows[1:])]
    require(all(0 < gap <= 10 for gap in gaps), "Missing/stalled sample windows")
    for row in rows:
        require(all(row[key] >= 0 for key in ("p99Nanos", "callerCpuNanos", "callerAllocatedBytes",
                    "processCpuNanos", "threads", "refreshThreads", "fixtureQueue", "agentQueue")),
                "Unavailable/negative performance accounting")
        require(row["refreshThreads"] <= (14 if enabled else 7)
                and row["fixtureQueue"] <= 128 and row["agentQueue"] <= 2048,
                "Worker/queue bounds exceeded")
    for key in ("rejected", "recovered", "applied", "processed", "errors"):
        require(rows[-1][key] > rows[0][key], f"Post-warmup scenario did not exercise {key}")
    if enabled:
        require(rows[-1]["actualReloads"] > rows[0]["actualReloads"], "Actual config watcher was not exercised")
    points = [(row["seconds"], row["postGcHeapBytes"]) for row in rows if row["postGcHeapBytes"] >= 0]
    cpu = (rows[-1]["processCpuNanos"] - rows[0]["processCpuNanos"]) / (span * 1e9)
    require(cpu >= 0, "Process CPU counter reset")
    quarter = max(1, len(rows) // 4)
    return {"windows": len(rows), "durationSeconds": span, "retention": retention(points),
            "callerP99Nanos": percentile([r["p99Nanos"] for r in rows], .95),
            "callerCpuNanos": statistics.median(r["callerCpuNanos"] for r in rows),
            "callerAllocatedBytes": statistics.median(r["callerAllocatedBytes"] for r in rows),
            "processCpuCores": cpu, "medianThreads": statistics.median(r["threads"] for r in rows),
            "threadGrowth": statistics.median(r["threads"] for r in rows[-quarter:])
                            - statistics.median(r["threads"] for r in rows[:quarter]),
            "maxRefreshThreads": max(r["refreshThreads"] for r in rows),
            "maxFixtureQueue": max(r["fixtureQueue"] for r in rows),
            "maxAgentQueue": max(r["agentQueue"] for r in rows),
            "scenario": {key: rows[-1][key] - rows[0][key]
                         for key in ("rejected", "recovered", "applied", "processed", "errors", "actualReloads")}}


def evaluate(baseline, enabled, budgets):
    required = {"callerP99Ratio", "callerP99NoiseNanos", "callerCpuRatio", "callerCpuNoiseNanos",
                "callerAllocationExtraBytes", "processCpuExtraCores", "extraThreads", "heapGrowthBytes",
                "heapSlopeBytesPerMinute", "maxControlP99Nanos", "httpP99Ratio", "httpP99NoiseMillis", "threadGrowth"}
    require(isinstance(budgets, dict) and set(budgets) == required and all(
        isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v) and v > 0
        for v in budgets.values()), "Missing/invalid soak budgets")
    problems = []
    comparisons = {
        "callerP99Nanos": (enabled["callerP99Nanos"],
                           baseline["callerP99Nanos"] * budgets["callerP99Ratio"] + budgets["callerP99NoiseNanos"]),
        "callerCpuNanos": (enabled["callerCpuNanos"],
                           baseline["callerCpuNanos"] * budgets["callerCpuRatio"] + budgets["callerCpuNoiseNanos"]),
        "callerAllocatedBytes": (enabled["callerAllocatedBytes"],
                                 baseline["callerAllocatedBytes"] + budgets["callerAllocationExtraBytes"]),
        "processCpuExtraCores": (enabled["processCpuCores"] - baseline["processCpuCores"], budgets["processCpuExtraCores"]),
        "extraThreads": (enabled["medianThreads"] - baseline["medianThreads"], budgets["extraThreads"]),
        "threadGrowth": (enabled["threadGrowth"], budgets["threadGrowth"]),
        "heapGrowthBytes": (enabled["retention"]["growthBytes"], budgets["heapGrowthBytes"]),
        "heapSlopeBytesPerMinute": (enabled["retention"]["slopeBytesPerMinute"], budgets["heapSlopeBytesPerMinute"]),
        "httpP99Millis": (enabled["http"]["p99Millis"],
                          baseline["http"]["p99Millis"] * budgets["httpP99Ratio"] + budgets["httpP99NoiseMillis"]),
    }
    # A noisy/growing control is invalid evidence, not permission to relax enabled budgets.
    if baseline["retention"]["growthBytes"] > budgets["heapGrowthBytes"] or \
            baseline["retention"]["slopeBytesPerMinute"] > budgets["heapSlopeBytesPerMinute"] or \
            baseline["callerP99Nanos"] > budgets["maxControlP99Nanos"]:
        problems.append("Control is unstable; rerun on an idle runner")
    if baseline["threadGrowth"] > budgets["threadGrowth"]:
        problems.append("Control thread count is growing; rerun on an idle runner")
    for name, (observed, limit) in comparisons.items():
        if observed > limit:
            problems.append(f"{name}: {observed:.3f} exceeds {limit:.3f}")
    return problems, {name: {"observed": observed, "limit": limit} for name, (observed, limit) in comparisons.items()}


def fetch_metrics(port, enabled):
    with urllib.request.urlopen(f"http://127.0.0.1:{port}/metrics", timeout=5) as response:
        require(response.status == 200, "Exporter did not return HTTP 200")
        data = response.read(MAX_RESPONSE + 1)
    require(len(data) <= MAX_RESPONSE, "Scrape exceeds response bound")
    text = data.decode()
    require("pletor_agent_soakfixture_processedcount_total " in text, "Fixture exporter series missing")
    if enabled:
        require("pletor_node_cpumetrics_systemcpuload " in text
                and "pletor_agent_observability_processedcount_total " in text, "Actual agent series missing")
    error_metric = False
    for line in text.splitlines():
        if line.startswith("pletor_") and "_total" in line.split()[0]:
            require(float(line.split()[-1]) >= 0, "Negative counter in scrape")
        if line.startswith("jmx_scrape_error "):
            error_metric = True
            require(float(line.split()[-1]) == 0, "Exporter reported a scrape error")
    require(error_metric, "Exporter scrape status is missing")
    return text


def run_mode(args, mode, classes, environment):
    enabled = mode == "enabled"
    directory = args.output / mode
    directory.mkdir(parents=True, exist_ok=True)
    for name in ("complete.marker", "samples.csv", "summary.json", "threads.txt"):
        (directory / name).unlink(missing_ok=True)
    (directory / "agent.yml").write_text("refresh_interval_seconds: 1\nfsmetrics_paths: [/]\n")
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    duration = args.duration_seconds if enabled else args.baseline_seconds
    flags = ["-Xms128m", "-Xmx256m", "-XX:+UseG1GC", "-XX:ActiveProcessorCount=2"]
    command = [args.java, *flags]
    if enabled:
        command.append(f"-javaagent:{args.agent_jar}={directory / 'agent.yml'}")
    command.extend([f"-javaagent:{args.exporter_jar}=127.0.0.1:{port}:{ROOT / 'src/main/resources/jmx_exporter_rules_example.yml'}",
                    "-cp", str(classes) + os.pathsep + str(args.agent_jar),
                    "kr.pletor.nodemetrics.agent.SoakProbe", str(directory), str(enabled).lower(),
                    str(duration), str(args.warmup_seconds), str(args.gc_interval)])
    write_json(directory / "command.json", command)
    print(f"{mode}: warmup={args.warmup_seconds}s measured={duration}s", flush=True)
    started = time.monotonic()
    ready = False
    last_text = ""
    scrapes = []
    measured_scrapes = []
    with (directory / "jvm.log").open("w") as log, (directory / "scrapes.csv").open("w") as scrape_log:
        writer = csv.writer(scrape_log)
        writer.writerow(["elapsedSeconds", "latencyMillis", "rssKiB"])
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, env=environment,
                                   cwd=ROOT, start_new_session=True)
        try:
            while process.poll() is None:
                require(time.monotonic() - started < duration + args.warmup_seconds + 90, f"{mode} JVM stalled")
                before = time.monotonic()
                try:
                    last_text = fetch_metrics(port, enabled)
                    if not ready:
                        (directory / "first-metrics.txt").write_text(last_text)
                    ready = True
                except (OSError, ValueError, urllib.error.URLError):
                    if (directory / "complete.marker").is_file():
                        require(process.wait(timeout=5) == 0, f"{mode} failed during shutdown")
                        break
                    if process.poll() is not None:
                        break
                    if ready or time.monotonic() - started > 30:
                        raise
                    time.sleep(.25)
                    continue
                latency = (time.monotonic() - before) * 1000
                require(len(scrapes) < 7500, "Scrape count exceeds bound")
                scrapes.append(latency)
                if time.monotonic() - started >= args.warmup_seconds + 5:
                    measured_scrapes.append(latency)
                try:
                    rss = next((line.split()[1] for line in Path(f"/proc/{process.pid}/status").read_text().splitlines()
                                if line.startswith("VmRSS:")), "unknown")
                except FileNotFoundError:
                    require(process.poll() == 0 or (directory / "complete.marker").is_file(),
                            "JVM disappeared during measurement")
                    rss = "exited"
                writer.writerow([time.monotonic() - started, latency, rss])
                scrape_log.flush()
                if len(scrapes) % 60 == 0:
                    print(f"{mode}: {len(scrapes)} successful scrapes, elapsed={time.monotonic()-started:.0f}s", flush=True)
                time.sleep(max(0, 1 - (time.monotonic() - before)))
            require(process.returncode == 0, f"{mode} JVM failed with exit {process.returncode}; inspect jvm.log/threads.txt")
            require((directory / "complete.marker").is_file(), f"{mode} did not complete cleanly")
            require(ready and len(measured_scrapes) >= duration * .8, f"{mode} scrape evidence is incomplete")
        finally:
            if process.poll() is None:
                try:
                    subprocess.run([str(Path(args.javac).with_name("jcmd")), str(process.pid), "Thread.print"],
                                   stdout=log, stderr=subprocess.STDOUT, timeout=10, check=False, env=environment)
                except (OSError, subprocess.SubprocessError):
                    pass
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=5)
            (directory / "last-metrics.txt").write_text(last_text)
    result = summarize_samples(directory / "samples.csv", duration, enabled)
    result["http"] = {"successfulScrapes": len(scrapes), "measuredScrapes": len(measured_scrapes),
                      "p99Millis": percentile(measured_scrapes, .99)}
    write_json(directory / "summary.json", result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--agent-jar", type=Path, required=True)
    parser.add_argument("--exporter-jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "build/reports/soak")
    parser.add_argument("--java", default="java")
    parser.add_argument("--javac", default="javac")
    parser.add_argument("--duration-seconds", type=int, default=1800)
    parser.add_argument("--baseline-seconds", type=int, default=300)
    parser.add_argument("--warmup-seconds", type=int, default=60)
    parser.add_argument("--gc-interval", type=int, default=10)
    parser.add_argument("--budgets", type=Path, default=ROOT / "scripts/soak/budgets.json")
    args = parser.parse_args()
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=True)
    result = {"status": "incomplete", "startedAt": time.time()}
    write_json(args.output / "summary.json", result)
    try:
        require(60 <= args.duration_seconds <= 3600 and 60 <= args.baseline_seconds <= 3600,
                "Measured durations must be 60..3600 seconds")
        require(30 <= args.warmup_seconds <= 300 and 1 <= args.gc_interval <= 10,
                "Warmup must be 30..300 seconds and GC interval 1..10 seconds")
        require(args.duration_seconds + args.baseline_seconds + 2 * args.warmup_seconds <= 4800,
                "Combined measurement/warmup must fit the 80-minute process budget")
        require(min(args.duration_seconds, args.baseline_seconds) / args.gc_interval >= 6,
                "Insufficient retention measurement duration")
        args.agent_jar = args.agent_jar.resolve(strict=True)
        args.exporter_jar = args.exporter_jar.resolve(strict=True)
        require(platform.system() == "Linux", "Real proc/cgroup evidence requires Linux")
        environment = {k: v for k, v in os.environ.items() if not any(term in k.upper()
                       for term in ("TOKEN", "SECRET", "PASSWORD"))
                       and k not in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}}
        metadata = {"platform": platform.platform(), "cpuCount": os.cpu_count(),
                    "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                    "agentSha256": hashlib.sha256(args.agent_jar.read_bytes()).hexdigest(),
                    "probeSha256": hashlib.sha256((ROOT / "scripts/soak/SoakProbe.java").read_bytes()).hexdigest(),
                    "budgetsSha256": hashlib.sha256(args.budgets.read_bytes()).hexdigest(),
                    "exporterSha256": hashlib.sha256(args.exporter_jar.read_bytes()).hexdigest(),
                    "procMembership": Path("/proc/self/cgroup").read_text()[:8192],
                    "cgroupV2Available": Path("/sys/fs/cgroup/cgroup.controllers").is_file(),
                    "durationSeconds": args.duration_seconds, "baselineSeconds": args.baseline_seconds,
                    "warmupSeconds": args.warmup_seconds, "gcIntervalSeconds": args.gc_interval,
                    "coverage": {"real": "packaged agent, HTTP exporter, proc/cgroup reads, file watcher",
                                 "simulated": "hung/throwing collectors in a separate production engine fixture",
                                 "notExercised": "real kernel/NFS hangs, kernel limit changes, cgroup namespace migration"}}
        write_json(args.output / "environment.json", metadata)
        classes = args.output / "classes"
        classes.mkdir(exist_ok=True)
        subprocess.run([args.javac, "--release", "11", "-cp", str(args.agent_jar), "-d", str(classes),
                        str(ROOT / "scripts/soak/SoakProbe.java")], check=True, timeout=60, env=environment)
        baseline = run_mode(args, "baseline", classes, environment)
        enabled = run_mode(args, "enabled", classes, environment)
        budgets = json.loads(args.budgets.read_text())
        problems, comparisons = evaluate(baseline, enabled, budgets)
        result.update(status="failed" if problems else "passed", baseline=baseline, enabled=enabled,
                      budgets=budgets, problems=problems, comparisons=comparisons)
        return 1 if problems else 0
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as exc:
        result["error"] = str(exc)
        print(f"SOAK INCOMPLETE: {exc}", file=sys.stderr)
        return 2
    finally:
        result["finishedAt"] = time.time()
        write_json(args.output / "summary.json", result)
        print(json.dumps(result, indent=2), flush=True)


if __name__ == "__main__":
    sys.exit(main())
