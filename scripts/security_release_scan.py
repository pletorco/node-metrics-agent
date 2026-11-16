#!/usr/bin/env python3
"""Collect separate main and published-release evidence for daily monitoring."""

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.parse
import urllib.request

import security_scan as scanner
from security_tool_updates import GitHub, UpdateCheckError


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def collect_main(checkout, output):
    checkout = Path(checkout)
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=checkout, text=True).strip()
    result = {"identity": {"ref": "main", "commit": commit}, "scanStatus": "incomplete"}
    # An old report cannot turn a failed invocation into a successful scan.
    reports = checkout / "build/reports/security"
    shutil.rmtree(reports, ignore_errors=True)
    completed = subprocess.run(["./gradlew", "--no-daemon", "trivyScanAll"], cwd=checkout, timeout=1500)
    if reports.exists():
        shutil.copytree(reports, output / "evidence", dirs_exist_ok=True)
    runtime_bom = checkout / "build/reports/bom.json"
    if runtime_bom.exists():
        shutil.copy2(runtime_bom, output / "main-runtime-bom.json")
    scan = scanner.load_json(reports / "summary.json")
    scanner.require(scan.get("commit") == commit, "Main scan commit does not match checkout")
    scanner.require(scan.get("scanStatus") == "complete", scan.get("error", "Main scan is incomplete"))
    scanner.require(completed.returncode == 0 or scan.get("blockingFindings", 0) > 0,
                    "Main build failed without a completed vulnerable scan")
    result.update(scan=scan, scanStatus=scan.get("scanStatus", "incomplete"))
    return result


def collect_release(api, repository, output):
    release = api.request("GET", f"/repos/{repository}/releases/latest")
    scanner.require(isinstance(release, dict) and release.get("draft") is False
                    and release.get("prerelease") is False, "No supported stable release")
    tag = release.get("tag_name")
    scanner.require(isinstance(tag, str) and tag.startswith("v"), "Invalid release tag")
    commit = api.request("GET", f"/repos/{repository}/commits/{urllib.parse.quote(tag, safe='')}").get("sha")
    scanner.require(isinstance(commit, str) and len(commit) == 40, "Cannot resolve release tag commit")
    assets = [asset for asset in release.get("assets", []) if asset.get("name") == "bom.json"]
    scanner.require(len(assets) == 1, "Published release must have exactly one bom.json asset")
    asset = assets[0]
    url = asset.get("browser_download_url")
    expected_url = f"https://github.com/{repository}/releases/download/{urllib.parse.quote(tag, safe='')}/bom.json"
    scanner.require(url == expected_url, "Unexpected release SBOM URL")
    with urllib.request.urlopen(url, timeout=60) as response:
        data = response.read(10 * 1024 * 1024 + 1)
    scanner.require(len(data) <= 10 * 1024 * 1024, "Release SBOM exceeds size limit")
    digest = "sha256:" + hashlib.sha256(data).hexdigest()
    scanner.require(asset.get("digest") == digest, "Published SBOM SHA-256 digest is missing or mismatched")
    bom_path = output / "published-bom.json"
    bom_path.write_bytes(data)
    bom = scanner.load_json(bom_path)
    expected = scanner.maven_coordinates(bom)
    trivy = os.environ.get("TRIVY_BIN", "trivy")
    cache = os.environ.get("TRIVY_CACHE_DIR", ".trivycache")
    scanner.run([trivy, "image", "--download-db-only", "--cache-dir", cache])
    metadata = scanner.run([trivy, "version", "--format", "json", "--cache-dir", cache], capture=True)
    db = scanner.validate_db(metadata, dt.datetime.now(dt.timezone.utc), 48)
    report_path = output / "release-trivy.json"
    scanner.run([trivy, "sbom", "--cache-dir", cache, "--skip-db-update", "--scanners", "vuln",
                 "--list-all-pkgs", "--exit-code", "0", "--format", "json", "--output",
                 str(report_path), str(bom_path)])
    findings = scanner.validate_trivy(scanner.load_json(report_path), expected)
    return {"scanStatus": "complete", "identity": {"ref": tag, "commit": commit,
            "releaseUrl": release.get("html_url"), "publishedAt": release.get("published_at"),
            "sbomDigest": digest, "assetId": asset.get("id")},
            "scan": {"scanStatus": "complete", "finishedAt": now(), "commit": commit,
                     "trivyVersion": metadata["Version"], "db": db, "coordinates": len(expected),
                     "scopes": ["publishedRuntime"],
                     "findings": [dict(finding, scope="publishedRuntime") for finding in findings]}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=["main", "release"], required=True)
    parser.add_argument("--checkout", default="scan-source")
    parser.add_argument("--repository", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()
    output = Path(args.output_dir)
    output.mkdir(parents=True, exist_ok=True)
    summary = {"schemaVersion": 1, "target": args.target, "runId": args.run_id,
               "startedAt": now(), "scanStatus": "incomplete"}
    scanner.write_json(output / "summary.json", summary)
    try:
        if args.target == "main":
            summary.update(collect_main(args.checkout, output))
        else:
            summary.update(collect_release(GitHub(), args.repository, output))
    except (scanner.ScanError, UpdateCheckError, OSError, ValueError, subprocess.SubprocessError) as exc:
        summary.update(scanStatus="incomplete", error=str(exc))
    summary["finishedAt"] = now()
    scanner.write_json(output / "summary.json", summary)
    print(json.dumps(summary, indent=2))
    if summary["scanStatus"] != "complete":
        return 2
    return 1 if summary["scan"]["findings"] else 0


if __name__ == "__main__":
    sys.exit(main())
