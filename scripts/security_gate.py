#!/usr/bin/env python3
"""Apply a runtime/tooling security gate to the current candidate commit."""

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

import security_monitor as policy_tools
import security_scan as scanner


POLICY_PATH = Path(".github/security-monitoring.json")
REPORTS = Path("build/reports/security")
BOM_PATH = Path("build/reports/bom.json")


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def evaluate(summary, inventory, policy, context, expected_commit, now):
    scanner.require(context in {"pr", "release"}, "Invalid security gate context")
    scanner.validate_inventory(inventory)
    scanner.require(re.fullmatch(r"[0-9a-f]{40}", expected_commit)
                    and inventory["commit"] == expected_commit, "Candidate inventory commit mismatch")
    scanner.require(isinstance(summary, dict) and summary.get("scanStatus") == "complete",
                    "Dependency security scan is incomplete")
    scanner.require(summary.get("commit") == expected_commit
                    and set(summary.get("scopes", [])) == scanner.SCOPES
                    and summary.get("coordinates") == len(inventory["components"])
                    and summary.get("gradleAdvisoriesChecked", 0) > 0, "Incomplete candidate scan coverage")
    finished = scanner.timestamp(summary.get("finishedAt"))
    scanner.require(dt.timedelta(minutes=-5) <= now - finished <= dt.timedelta(hours=2), "Candidate scan is stale")
    scanner.validate_db({"Version": summary.get("trivyVersion"), "VulnerabilityDB": summary.get("db")}, now, 48)
    gates = policy.get("gates", {})
    scanner.require(set(gates) == {"runtime", "tooling"} and all(
        isinstance(values, list) and {"UNKNOWN", "HIGH", "CRITICAL"} <= set(values) <= scanner.SEVERITIES
        for values in gates.values()),
        "Missing/invalid runtime and tooling gate thresholds")
    findings = policy_tools.normalize_findings(summary.get("findings"), "main" if context == "pr" else "release",
                                               policy, now)
    coordinates = {(c["group"] + ":" + c["name"], c["version"]): c["scopes"] for c in inventory["components"]}
    groups = {"runtime": [], "tooling": []}
    for item in findings.values():
        key = (item["package"], item["version"])
        if "gradle" in item["scopes"]:
            scanner.require(key == ("org.gradle:gradle-core", inventory["gradleVersion"]), "Invalid Gradle finding")
            category = "tooling"
        else:
            scanner.require(key in coordinates and set(item["scopes"]) <= {"runtime", "tooling"},
                            "Finding references unknown dependency/scope")
            category = "runtime" if "runtimeClasspath" in coordinates[key] else "tooling"
        item["blocking"] = item["severity"] in gates[category] and "exception" not in item
        groups[category].append(item)
    blocking = sum(item["blocking"] for items in groups.values() for item in items)
    return {"schemaVersion": 1, "gateStatus": "blocked" if blocking else "passed", "context": context,
            "commit": expected_commit, "finishedAt": now.isoformat(), "blockingFindings": blocking,
            "thresholds": gates, "findings": groups, "trivyVersion": summary["trivyVersion"], "db": summary["db"]}


def execute(context, expected_commit):
    REPORTS.mkdir(parents=True, exist_ok=True)
    result = {"schemaVersion": 1, "gateStatus": "incomplete", "context": context,
              "commit": expected_commit, "startedAt": dt.datetime.now(dt.timezone.utc).isoformat()}
    scanner.write_json(REPORTS / "gate.json", result)
    scanner.write_json(REPORTS / "summary.json", {"scanStatus": "incomplete", "phase": "gate-inventory"})
    try:
        commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
        scanner.require(commit == expected_commit, "Checkout differs from the candidate commit")
        # Gradle executes candidate code. It needs no GitHub token to resolve public dependencies.
        environment = {k: v for k, v in os.environ.items() if k not in {"GH_TOKEN", "GITHUB_TOKEN"}}
        subprocess.run(["./gradlew", "--no-daemon", "securityInventory", "cyclonedxBom"],
                       env=environment, check=True, timeout=900)
        code = scanner.scan(argparse.Namespace(inventory=REPORTS / "inventory.json", runtime_bom=BOM_PATH,
                            output_dir=REPORTS, severities=scanner.SEVERITIES, max_db_age_hours=48))
        scanner.require(code in (0, 1), "Dependency security scan did not complete")
        now = dt.datetime.now(dt.timezone.utc)
        policy = policy_tools.load_policy(POLICY_PATH, now)
        result.update(evaluate(scanner.load_json(REPORTS / "summary.json"), scanner.load_json(REPORTS / "inventory.json"),
                               policy, context, expected_commit, now))
        result.update(policySha256=digest(POLICY_PATH), runtimeBomSha256=digest(BOM_PATH),
                      inventorySha256=digest(REPORTS / "inventory.json"))
        return 1 if result["blockingFindings"] else 0
    except (scanner.ScanError, OSError, ValueError, subprocess.SubprocessError) as exc:
        result.update(gateStatus="incomplete", error=str(exc))
        return 2
    finally:
        result["finishedAt"] = dt.datetime.now(dt.timezone.utc).isoformat()
        scanner.write_json(REPORTS / "gate.json", result)
        print(json.dumps(result, indent=2))
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as output:
                output.write(f"## Dependency security: {result['gateStatus']}\n\n")
                for category, findings in result.get("findings", {}).items():
                    output.write(f"- {category}: {len(findings)} findings, "
                                 f"{sum(f['blocking'] for f in findings)} blocking\n")
                if result.get("error"):
                    output.write(f"\nIncomplete: {result['error']}\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--context", choices=["pr", "release"], required=True)
    parser.add_argument("--expected-commit", required=True)
    args = parser.parse_args()
    return execute(args.context, args.expected_commit)


if __name__ == "__main__":
    sys.exit(main())
