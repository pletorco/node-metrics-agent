#!/usr/bin/env python3
"""Scan resolved Maven dependencies and Gradle vendor advisories. Standard library only."""

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.request


SCOPES = {"runtimeClasspath", "testRuntimeClasspath", "checkstyle", "jacocoAgent",
          "jacocoAnt", "formatter", "buildscript"}
SEVERITIES = {"UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL"}
GRADLE_ADVISORIES = "https://api.github.com/repos/gradle/gradle/security-advisories"


class ScanError(Exception):
    """A check did not finish reliably; this must never mean 'no vulnerabilities'."""


def require(condition, message):
    if not condition:
        raise ScanError(message)


def load_json(path):
    try:
        return json.loads(Path(path).read_text())
    except (OSError, ValueError) as exc:
        raise ScanError(f"Cannot read JSON report {path}: {exc}") from exc


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2) + "\n")


def validate_inventory(data):
    require(isinstance(data, dict) and data.get("schemaVersion") == 1, "Invalid inventory schema")
    require(re.fullmatch(r"[0-9a-f]{40}", data.get("commit", "")), "Missing inventory commit")
    version(data.get("gradleVersion", ""))
    require(isinstance(data.get("components"), list) and data["components"], "Empty inventory")
    observed, coordinates = set(), set()
    for item in data["components"]:
        require(isinstance(item, dict), "Invalid inventory component")
        require(all(isinstance(item.get(k), str) and item[k] for k in ("group", "name", "version")),
                "Incomplete Maven coordinate")
        require(isinstance(item.get("scopes"), list) and item["scopes"]
                and all(scope in SCOPES for scope in item["scopes"]), "Missing/unknown component scope")
        coordinate = (item["group"], item["name"], item["version"])
        require(coordinate not in coordinates, "Duplicate inventory coordinate")
        coordinates.add(coordinate)
        observed.update(item["scopes"])
    require(observed == SCOPES, f"Inventory omits scopes: {SCOPES - observed}")
    require(any(c["group"] == "com.google.googlejavaformat" and c["name"] == "google-java-format"
                and c["version"] == data.get("formatterVersion") and "formatter" in c["scopes"]
                for c in data["components"]), "Inventory omits the configured formatter")


def maven_coordinates(bom):
    require(isinstance(bom, dict) and bom.get("bomFormat") == "CycloneDX", "Invalid CycloneDX BOM")
    components = bom.get("components")
    require(isinstance(components, list) and components, "BOM has no components")
    result = set()
    for c in components:
        require(isinstance(c, dict) and all(isinstance(c.get(k), str) and c[k]
                for k in ("group", "name", "version")), "BOM has an incomplete Maven component")
        require(isinstance(c.get("purl"), str)
                and c["purl"].split("?", 1)[0] == f"pkg:maven/{c['group']}/{c['name']}@{c['version']}",
                "BOM Maven PURL does not match its coordinate")
        result.add((c["group"] + ":" + c["name"], c["version"]))
    return result


def tooling_bom(inventory):
    components = []
    for c in inventory["components"]:
        components.append({"type": "library", "group": c["group"], "name": c["name"],
                           "version": c["version"],
                           "purl": f"pkg:maven/{c['group']}/{c['name']}@{c['version']}",
                           "properties": [{"name": "node-metrics-agent:scopes",
                                           "value": ",".join(c["scopes"])}]})
    return {"bomFormat": "CycloneDX", "specVersion": "1.5", "version": 1,
            "components": components}


def timestamp(value):
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
        require(parsed.tzinfo is not None, "Timestamp has no timezone")
        return parsed
    except (ValueError, AttributeError, TypeError) as exc:
        raise ScanError("Missing/invalid DB timestamp") from exc


def validate_db(metadata, now, max_age_hours):
    require(isinstance(metadata, dict) and metadata.get("Version"), "Missing Trivy version")
    db = metadata.get("VulnerabilityDB")
    require(isinstance(db, dict) and db.get("Version") == 2, "Missing/unsupported vulnerability DB")
    require(0 < max_age_hours <= 48, "DB age limit must be greater than zero and at most 48 hours")
    updated = timestamp(db.get("UpdatedAt"))
    age = now - updated
    require(dt.timedelta(minutes=-5) <= age <= dt.timedelta(hours=max_age_hours),
            "Vulnerability DB is stale or dated in the future")
    require(timestamp(db.get("NextUpdate")) >= now, "Vulnerability DB update is overdue")
    return db


def validate_trivy(report, expected):
    require(isinstance(report, dict) and report.get("SchemaVersion") == 2, "Invalid Trivy report schema")
    require(report.get("ArtifactType") == "cyclonedx", "Unexpected Trivy artifact type")
    require(isinstance(report.get("Results"), list), "Trivy report omits Results")
    scanned, findings = set(), []
    for result in report["Results"]:
        require(isinstance(result, dict), "Invalid Trivy result")
        packages = result.get("Packages", [])
        require(isinstance(packages, list), "Invalid Trivy package list")
        for package in packages:
            require(isinstance(package, dict) and package.get("Name") and package.get("Version"),
                    "Incomplete scanned package")
            scanned.add((package["Name"], package["Version"]))
        vulnerabilities = result.get("Vulnerabilities", [])
        require(isinstance(vulnerabilities, list), "Invalid vulnerability list")
        for vulnerability in vulnerabilities:
            require(isinstance(vulnerability, dict)
                    and all(vulnerability.get(k) for k in ("VulnerabilityID", "PkgName", "InstalledVersion"))
                    and vulnerability.get("Severity") in SEVERITIES, "Malformed vulnerability")
            require((vulnerability["PkgName"], vulnerability["InstalledVersion"]) in expected,
                    "Vulnerability references a package outside the BOM")
            findings.append(vulnerability)
    require(expected <= scanned, f"Trivy did not scan {len(expected - scanned)} expected Maven packages")
    return findings


def version(text, upper=False):
    require(isinstance(text, str) and re.fullmatch(r"\d+(?:\.(?:\d+|x)){0,2}", text),
            f"Unsupported Gradle version: {text!r}")
    require(upper or "x" not in text, f"Unexpected version wildcard: {text!r}")
    return tuple(999999 if v == "x" and upper else int(v) for v in text.split(".")) + (0,) * (3 - len(text.split(".")))


def affected(current, expression):
    """Interpret vendor ranges conservatively; an unknown grammar makes the scan incomplete."""
    require(isinstance(expression, str) and expression.strip(), "Missing vendor version range")
    current = version(current)
    expression = expression.strip()
    interval = re.fullmatch(r"\[\s*([\d.]+)\s*,\s*([\d.]+)\s*\]", expression)
    if interval:
        return version(interval[1]) <= current <= version(interval[2])
    # Some older vendor entries use a comma before a bracketed interval as an alternative.
    alternatives = re.fullmatch(r"(.+),\s*(\[[^]]+\])", expression)
    if alternatives:
        return affected(".".join(map(str, current)), alternatives[1]) or affected(
            ".".join(map(str, current)), alternatives[2])
    # Old Gradle advisories sometimes combine a comparator and a 'to' interval as alternatives.
    clauses = [part.strip() for part in expression.split(",")]
    if len(clauses) > 1 and all(re.fullmatch(r"<=?\s*[\d.]+", part) for part in clauses):
        # Legacy advisories list upper bounds for separate maintenance lines without lower bounds.
        # Use their conservative union, never the intersection that would miss an affected line.
        return any(affected(".".join(map(str, current)), part) for part in clauses)
    if len(clauses) > 1 and any(re.search(r"\b(to|through)\b", part) for part in clauses):
        return any(affected(".".join(map(str, current)), part) for part in clauses)
    checks = []
    for clause in clauses:
        interval = re.fullmatch(r"([\d.]+)\s+(?:to|through)\s+([\d.x]+)", clause)
        if interval:
            checks.append(version(interval[1]) <= current <= version(interval[2], upper=True))
            continue
        match = re.fullmatch(r"(<=|>=|<|>|=)?\s*([\d.]+)", clause)
        require(match is not None, f"Unsupported vendor range: {expression!r}")
        operator, target = match[1] or "=", version(match[2])
        checks.append({"<=": current <= target, ">=": current >= target, "<": current < target,
                       ">": current > target, "=": current == target}[operator])
    return all(checks)


def gradle_findings(advisories, current):
    require(isinstance(advisories, list) and advisories, "Gradle advisory source is empty/invalid")
    findings = []
    for advisory in advisories:
        require(isinstance(advisory, dict) and advisory.get("ghsa_id")
                and isinstance(advisory.get("vulnerabilities"), list)
                and advisory["vulnerabilities"], "Incomplete Gradle advisory")
        if advisory.get("withdrawn_at"):
            continue
        for item in advisory["vulnerabilities"]:
            require(isinstance(item, dict) and isinstance(item.get("package"), dict),
                    "Incomplete Gradle affected package")
            require(item["package"].get("name") in {"Gradle", "org.gradle:gradle-core"},
                    "Unrecognized Gradle advisory package; review scanner coverage")
            if affected(current, item.get("vulnerable_version_range")):
                severity = str(advisory.get("severity", "")).upper()
                require(severity in SEVERITIES, "Missing Gradle advisory severity")
                findings.append({"VulnerabilityID": advisory["ghsa_id"], "Severity": severity,
                                 "PkgName": "org.gradle:gradle-core", "InstalledVersion": current,
                                 "FixedVersion": item.get("patched_versions"),
                                 "CVE": advisory.get("cve_id"), "PrimaryURL": advisory.get("html_url")})
                break
    return findings


def fetch_gradle_advisories():
    result = []
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "node-metrics-agent-security"}
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = "Bearer " + token
    for page in range(1, 101):
        request = urllib.request.Request(f"{GRADLE_ADVISORIES}?state=published&per_page=100&page={page}",
                                         headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                data = json.load(response)
        except (OSError, ValueError) as exc:
            raise ScanError("Cannot refresh Gradle vendor advisories") from exc
        require(isinstance(data, list), "Invalid Gradle advisory API response")
        result.extend(data)
        if len(data) < 100:
            return result
    raise ScanError("Gradle advisory pagination exceeded safety limit")


def run(command, capture=False):
    try:
        result = subprocess.run(command, check=True, text=True, capture_output=capture, timeout=900)
    except (OSError, subprocess.SubprocessError) as exc:
        raise ScanError(f"External check failed: {command[0]} {command[1]}") from exc
    if capture:
        try:
            return json.loads(result.stdout)
        except ValueError as exc:
            raise ScanError("Trivy returned invalid metadata") from exc


def scan(args):
    output = Path(args.output_dir)
    output.mkdir(parents=True, exist_ok=True)
    # Remove earlier outputs before any operation can fail; a failed run cannot leave a clean summary.
    for filename in ("summary.json", "runtime-trivy.json", "tooling-trivy.json", "gradle-advisories.json"):
        (output / filename).unlink(missing_ok=True)
    summary = {"scanStatus": "incomplete", "startedAt": dt.datetime.now(dt.timezone.utc).isoformat()}
    try:
        inventory = load_json(args.inventory)
        validate_inventory(inventory)
        summary.update({"commit": inventory["commit"], "gradleVersion": inventory["gradleVersion"]})
        runtime = load_json(args.runtime_bom)
        runtime_coordinates = maven_coordinates(runtime)
        expected_runtime = {(c["group"] + ":" + c["name"], c["version"])
                            for c in inventory["components"] if "runtimeClasspath" in c["scopes"]}
        require(runtime_coordinates == expected_runtime, "Deployment SBOM differs from resolved runtime")
        bom = tooling_bom(inventory)
        tooling_path = output / "all-dependencies.cdx.json"
        write_json(tooling_path, bom)
        trivy = os.environ.get("TRIVY_BIN", "trivy")
        cache = os.environ.get("TRIVY_CACHE_DIR", ".trivycache")
        run([trivy, "image", "--download-db-only", "--cache-dir", cache])
        metadata = run([trivy, "version", "--format", "json", "--cache-dir", cache], capture=True)
        summary["db"] = validate_db(metadata, dt.datetime.now(dt.timezone.utc), args.max_db_age_hours)
        summary["trivyVersion"] = metadata["Version"]
        findings = []
        for scope, path, expected in (("runtime", Path(args.runtime_bom), runtime_coordinates),
                                       ("tooling", tooling_path, maven_coordinates(bom))):
            report_path = output / f"{scope}-trivy.json"
            run([trivy, "sbom", "--cache-dir", cache, "--skip-db-update", "--scanners", "vuln",
                 "--list-all-pkgs", "--exit-code", "0", "--format", "json", "--output",
                 str(report_path), str(path)])
            findings.extend(dict(v, scope=scope) for v in validate_trivy(load_json(report_path), expected))
        advisories = fetch_gradle_advisories()
        write_json(output / "gradle-advisories.json", advisories)
        findings.extend(dict(v, scope="gradle") for v in gradle_findings(advisories, inventory["gradleVersion"]))
        summary.update({"scanStatus": "complete", "coordinates": len(inventory["components"]),
                        "scopes": sorted(SCOPES), "gradleAdvisoriesChecked": len(advisories),
                        "findings": findings, "blockingFindings": sum(v["Severity"] in args.severities for v in findings)})
        return 1 if summary["blockingFindings"] else 0
    except ScanError as exc:
        summary["error"] = str(exc)
        print(f"SECURITY SCAN INCOMPLETE: {exc}", file=sys.stderr)
        return 2
    finally:
        summary["finishedAt"] = dt.datetime.now(dt.timezone.utc).isoformat()
        write_json(output / "summary.json", summary)
        print(json.dumps(summary, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", required=True)
    parser.add_argument("--runtime-bom", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--severity", default="HIGH,CRITICAL")
    parser.add_argument("--max-db-age-hours", type=float, default=48)
    args = parser.parse_args()
    args.severities = set(args.severity.split(","))
    if not args.severities <= SEVERITIES:
        parser.error("Unknown/empty severity gate")
    return scan(args)


if __name__ == "__main__":
    sys.exit(main())
