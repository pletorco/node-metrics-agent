#!/usr/bin/env python3
"""Stage and verify the exact build that passed the release security gate."""

import argparse
import datetime as dt
import json
from pathlib import Path
import re
import shutil
import sys
import zipfile

import security_scan as scanner
from security_gate import digest


def check_gate(gate, commit, bom, policy, now):
    scanner.require(isinstance(gate, dict) and gate.get("schemaVersion") == 1
                    and gate.get("gateStatus") == "passed" and gate.get("context") == "release"
                    and gate.get("blockingFindings") == 0, "Release security gate did not pass")
    findings = gate.get("findings", {})
    scanner.require(isinstance(findings, dict) and set(findings) == {"runtime", "tooling"}
                    and all(isinstance(items, list) and all(isinstance(item, dict) and item.get("blocking") is False
                            for item in items) for items in findings.values()), "Release gate findings are inconsistent")
    scanner.require(gate.get("commit") == commit and re.fullmatch(r"[0-9a-f]{40}", commit),
                    "Release gate commit mismatch")
    age = now - scanner.timestamp(gate.get("finishedAt"))
    scanner.require(dt.timedelta(minutes=-5) <= age <= dt.timedelta(hours=2), "Release gate evidence is stale")
    scanner.validate_db({"Version": gate.get("trivyVersion"), "VulnerabilityDB": gate.get("db")}, now, 48)
    scanner.require(gate.get("runtimeBomSha256") == digest(bom), "Release SBOM differs from scanned SBOM")
    scanner.require(gate.get("policySha256") == digest(policy), "Release gate policy differs from reviewed policy")


def check_jar(jar, bom):
    with zipfile.ZipFile(jar) as archive:
        scanner.require(archive.read("META-INF/cyclonedx/bom.json") == Path(bom).read_bytes(),
                        "Packaged JAR SBOM differs from the scanned/published SBOM")


def candidate_files(tag):
    scanner.require(re.fullmatch(r"v\d+\.\d+\.\d+", tag), "Invalid stable release tag")
    return {f"node-metrics-agent-{tag[1:]}-all.jar", "bom.json", "SHA256SUMS", "release-notes.md", "gate.json"}


def stage(directory, tag, commit, notes, policy, now):
    directory = Path(directory)
    shutil.rmtree(directory, ignore_errors=True)
    files = candidate_files(tag)
    jar_name = next(name for name in files if name.endswith(".jar"))
    jar = Path("build/libs") / jar_name
    bom = Path("build/reports/bom.json")
    gate_path = Path("build/reports/security/gate.json")
    check_gate(scanner.load_json(gate_path), commit, bom, policy, now)
    check_jar(jar, bom)
    scanner.require(Path(notes).read_text().strip(), "Missing release notes")
    directory.mkdir(parents=True)
    for source, name in ((jar, jar_name), (bom, "bom.json"), (gate_path, "gate.json"), (Path(notes), "release-notes.md")):
        shutil.copy2(source, directory / name)
    (directory / "SHA256SUMS").write_text(f"{digest(jar)}  {jar_name}\n")
    manifest = {"schemaVersion": 1, "tag": tag, "commit": commit,
                "files": {name: digest(directory / name) for name in sorted(files)}}
    scanner.write_json(directory / "manifest.json", manifest)
    verify(directory, tag, commit, policy, now)


def verify(directory, tag, commit, policy, now):
    directory = Path(directory)
    files = candidate_files(tag)
    manifest = scanner.load_json(directory / "manifest.json")
    scanner.require(isinstance(manifest, dict) and manifest.get("schemaVersion") == 1
                    and manifest.get("tag") == tag and manifest.get("commit") == commit,
                    "Release candidate identity mismatch")
    scanner.require(isinstance(manifest.get("files"), dict) and set(manifest["files"]) == files,
                    "Incomplete/unexpected release candidate manifest")
    scanner.require({path.name for path in directory.iterdir()} == files | {"manifest.json"},
                    "Unexpected release candidate files")
    for name, expected in manifest["files"].items():
        path = directory / name
        scanner.require(path.is_file() and not path.is_symlink() and digest(path) == expected,
                        f"Release candidate checksum mismatch: {name}")
    jar_name = next(name for name in files if name.endswith(".jar"))
    scanner.require((directory / "SHA256SUMS").read_text() == f"{digest(directory / jar_name)}  {jar_name}\n",
                    "Release JAR checksum file mismatch")
    check_gate(scanner.load_json(directory / "gate.json"), commit, directory / "bom.json", policy, now)
    check_jar(directory / jar_name, directory / "bom.json")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["stage", "verify"])
    parser.add_argument("--directory", default="build/release-candidate")
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--notes", default="build/release-notes.md")
    parser.add_argument("--policy", default=".github/security-monitoring.json")
    args = parser.parse_args()
    try:
        now = dt.datetime.now(dt.timezone.utc)
        if args.mode == "stage":
            stage(args.directory, args.tag, args.commit, args.notes, args.policy, now)
        else:
            verify(args.directory, args.tag, args.commit, args.policy, now)
        print(f"Release candidate {args.tag} at {args.commit}: {args.mode} passed")
        return 0
    except (scanner.ScanError, OSError, ValueError, KeyError, zipfile.BadZipFile) as exc:
        print(f"RELEASE CANDIDATE REJECTED: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
