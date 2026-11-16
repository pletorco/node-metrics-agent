import argparse
import contextlib
import copy
import datetime as dt
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import security_scan as security


def inventory():
    components = [{"group": "example", "name": "runtime", "version": "1.0", "scopes": ["runtimeClasspath"]}]
    for scope in sorted(security.SCOPES - {"runtimeClasspath", "formatter"}):
        components.append({"group": "example", "name": scope, "version": "1.0", "scopes": [scope]})
    components.append({"group": "com.google.googlejavaformat", "name": "google-java-format",
                       "version": "1.25.2", "scopes": ["formatter"]})
    return {"schemaVersion": 1, "commit": "a" * 40, "gradleVersion": "9.9.1",
            "formatterVersion": "1.25.2", "components": components}


def advisory(expression=">= 9.0.0, <= 9.9.0"):
    return {"ghsa_id": "GHSA-example", "cve_id": None, "severity": "critical",
            "html_url": "https://github.com/gradle/gradle/security/advisories/GHSA-example",
            "vulnerabilities": [{"package": {"name": "org.gradle:gradle-core"},
                                 "vulnerable_version_range": expression, "patched_versions": "9.9.1"}]}


def trivy_report(bom):
    return {"SchemaVersion": 2, "ArtifactType": "cyclonedx", "Results": [{
        "Packages": [{"Name": name, "Version": version}
                     for name, version in security.maven_coordinates(bom)]}]}


class ValidationTest(unittest.TestCase):
    def test_cyclonedx_maven_jar_qualifier_preserves_package_identity(self):
        bom = security.tooling_bom(inventory())
        bom["components"][0]["purl"] += "?type=jar"
        self.assertIn(("example:runtime", "1.0"), security.maven_coordinates(bom))

    def test_missing_each_inventory_scope_is_rejected(self):
        for scope in security.SCOPES:
            with self.subTest(scope=scope):
                data = inventory()
                data["components"] = [c for c in data["components"] if scope not in c["scopes"]]
                with self.assertRaises(security.ScanError):
                    security.validate_inventory(data)

    def test_wrong_formatter_version_is_rejected(self):
        data = inventory()
        data["formatterVersion"] = "2.0.0"
        with self.assertRaises(security.ScanError):
            security.validate_inventory(data)

    def test_expected_packages_cannot_disappear_from_a_clean_report(self):
        bom = security.tooling_bom(inventory())
        report = trivy_report(bom)
        report["Results"][0]["Packages"].pop()
        with self.assertRaises(security.ScanError):
            security.validate_trivy(report, security.maven_coordinates(bom))

    def test_malformed_clean_and_vulnerable_reports_are_rejected(self):
        expected = {("example:runtime", "1.0")}
        for report in ({}, {"SchemaVersion": 2, "ArtifactType": "cyclonedx", "Results": []},
                       {"SchemaVersion": 2, "ArtifactType": "cyclonedx", "Results": [{
                           "Packages": [{"Name": "example:runtime", "Version": "1.0"}],
                           "Vulnerabilities": [{}]}]}):
            with self.subTest(report=report), self.assertRaises(security.ScanError):
                security.validate_trivy(report, expected)

    def test_db_missing_stale_overdue_and_future_are_rejected(self):
        now = dt.datetime(2026, 11, 16, 12, tzinfo=dt.timezone.utc)
        valid = {"Version": "0.72.0", "VulnerabilityDB": {"Version": 2,
                 "UpdatedAt": "2026-11-16T11:00:00Z", "NextUpdate": "2026-11-17T11:00:00Z"}}
        security.validate_db(valid, now, 48)
        for metadata in ({}, {"Version": "0.72.0"}):
            with self.assertRaises(security.ScanError):
                security.validate_db(metadata, now, 48)
        for key, value in (("UpdatedAt", "2026-11-13T12:00:00Z"),
                           ("UpdatedAt", "2026-11-17T12:00:00Z"),
                           ("NextUpdate", "2026-11-16T11:00:00Z"),
                           ("UpdatedAt", "invalid")):
            data = copy.deepcopy(valid)
            data["VulnerabilityDB"][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(security.ScanError):
                security.validate_db(data, now, 48)

    def test_gradle_ghsa_without_cve_is_detected_at_affected_version(self):
        findings = security.gradle_findings([advisory()], "9.9.0")
        self.assertEqual("GHSA-example", findings[0]["VulnerabilityID"])
        self.assertIsNone(findings[0]["CVE"])
        self.assertEqual([], security.gradle_findings([advisory()], "9.9.1"))

    def test_vendor_ranges_used_by_old_and_current_gradle_advisories(self):
        cases = [("9.9.0", ">= 9.0.0, <= 9.9.0", True),
                 ("9.9.1", ">= 9.0.0, <= 9.9.0", False),
                 ("9.2.1", "< 8.14.4, 9.0.0 to 9.2.1", True),
                 ("9.9.1", "< 8.14.4, 9.0.0 to 9.2.1", False),
                 ("8.1.1", "<7.6.1, [8.0,8.1.1]", True),
                 ("8.0", "<7.6.3, <8.4", True),
                 ("9.9.1", "<7.6.3, <8.4", False),
                 ("6.4.8", "4.5 through 6.4.x", True),
                 ("8.12", "8.12", True), ("9.9.1", "<= 8.14.6", False)]
        for current, expression, expected in cases:
            with self.subTest(expression=expression, current=current):
                self.assertEqual(expected, security.affected(current, expression))

    def test_unknown_vendor_ranges_and_empty_source_fail(self):
        for expression in ("", "unparseable", ">= 9.0.0 || custom syntax"):
            with self.assertRaises(security.ScanError):
                security.affected("9.9.1", expression)
        with self.assertRaises(security.ScanError):
            security.gradle_findings([], "9.9.1")


class PipelineTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.data = inventory()
        security.write_json(self.root / "inventory.json", self.data)
        bom = security.tooling_bom(self.data)
        bom["components"] = [c for c in bom["components"] if c["name"] == "runtime"]
        security.write_json(self.root / "runtime.json", bom)
        self.args = argparse.Namespace(inventory=self.root / "inventory.json",
                                      runtime_bom=self.root / "runtime.json", output_dir=self.root / "reports",
                                      severities={"HIGH", "CRITICAL"}, max_db_age_hours=48)
        self.scanner_calls = 0
        self.vulnerable = False

    def fake_run(self, command, capture=False):
        if command[1] == "version":
            now = dt.datetime.now(dt.timezone.utc)
            return {"Version": "fixture", "VulnerabilityDB": {"Version": 2,
                    "UpdatedAt": (now - dt.timedelta(hours=1)).isoformat(),
                    "NextUpdate": (now + dt.timedelta(hours=4)).isoformat()}}
        if command[1] == "sbom":
            self.scanner_calls += 1
            report = trivy_report(security.load_json(command[-1]))
            if self.vulnerable:
                report["Results"][0]["Vulnerabilities"] = [{"VulnerabilityID": "CVE-fixture",
                    "PkgName": "example:runtime", "InstalledVersion": "1.0", "Severity": "HIGH"}]
            security.write_json(command[command.index("--output") + 1], report)

    def scan(self, run=None, advisories=None):
        with mock.patch.object(security, "run", side_effect=run or self.fake_run), \
             mock.patch.object(security, "fetch_gradle_advisories", return_value=advisories or [advisory()]), \
             contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return security.scan(self.args)

    def summary(self):
        return security.load_json(self.args.output_dir / "summary.json")

    def test_repeated_invocation_rescans_unchanged_inventory(self):
        self.assertEqual(0, self.scan())
        self.assertEqual(0, self.scan())
        self.assertEqual(4, self.scanner_calls)
        self.assertEqual("complete", self.summary()["scanStatus"])

    def test_vulnerable_fixture_blocks_but_completed_scan_is_distinct_from_error(self):
        self.vulnerable = True
        self.assertEqual(1, self.scan())
        self.assertEqual("complete", self.summary()["scanStatus"])
        self.assertGreater(self.summary()["blockingFindings"], 0)

    def test_scanner_failure_removes_previous_clean_success(self):
        self.assertEqual(0, self.scan())
        def fail(command, capture=False):
            raise security.ScanError("fixture scanner failed")
        self.assertEqual(2, self.scan(run=fail))
        self.assertEqual("incomplete", self.summary()["scanStatus"])
        self.assertNotIn("blockingFindings", self.summary())
        self.assertFalse((self.args.output_dir / "runtime-trivy.json").exists())

    def test_missing_report_cannot_reuse_an_earlier_clean_report(self):
        self.assertEqual(0, self.scan())
        original = self.fake_run
        def omit_report(command, capture=False):
            if command[1] != "sbom":
                return original(command, capture)
        self.assertEqual(2, self.scan(run=omit_report))
        self.assertEqual("incomplete", self.summary()["scanStatus"])

    def test_runtime_bom_must_match_actual_runtime_inventory(self):
        bom = security.load_json(self.args.runtime_bom)
        bom["components"][0]["version"] = "0.9"
        bom["components"][0]["purl"] = "pkg:maven/example/runtime@0.9"
        security.write_json(self.args.runtime_bom, bom)
        self.assertEqual(2, self.scan())
        self.assertIn("differs from resolved runtime", self.summary()["error"])

    def test_advisory_source_failure_cannot_produce_clean_status(self):
        with mock.patch.object(security, "fetch_gradle_advisories", side_effect=security.ScanError("API unavailable")), \
             mock.patch.object(security, "run", side_effect=self.fake_run), \
             contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(2, security.scan(self.args))
        self.assertEqual("incomplete", self.summary()["scanStatus"])


if __name__ == "__main__":
    unittest.main()
