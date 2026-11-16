import copy
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import security_release_scan as release_scan
import security_scan as scanner
from test_security_monitor import NOW


class PublishedReleaseTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.output = Path(directory.name)
        self.bom = {"bomFormat": "CycloneDX", "components": [{"group": "example", "name": "runtime",
                    "version": "1.0", "purl": "pkg:maven/example/runtime@1.0"}]}
        self.data = json.dumps(self.bom).encode()
        self.release = {"draft": False, "prerelease": False, "tag_name": "v0.10.1", "assets": [{
            "id": 5, "name": "bom.json", "digest": "sha256:" + hashlib.sha256(self.data).hexdigest(),
            "browser_download_url": "https://github.com/org/repo/releases/download/v0.10.1/bom.json"}]}
        self.api = mock.Mock()
        self.api.request.side_effect = lambda method, path: {"sha": "a" * 40} if "/commits/" in path else self.release

    def fake_run(self, command, capture=False):
        if command[1] == "version":
            return {"Version": "0.76.2", "VulnerabilityDB": {"Version": 2,
                    "UpdatedAt": NOW.isoformat(), "NextUpdate": "2099-01-01T00:00:00Z"}}
        if command[1] == "sbom":
            scanner.write_json(command[command.index("--output") + 1], {
                "SchemaVersion": 2, "ArtifactType": "cyclonedx", "Results": [{
                    "Packages": [{"Name": "example:runtime", "Version": "1.0"}],
                    "Vulnerabilities": [{"VulnerabilityID": "CVE-release", "Severity": "HIGH",
                        "PkgName": "example:runtime", "InstalledVersion": "1.0"}]}]})

    def collect(self):
        with mock.patch.object(release_scan.urllib.request, "urlopen", return_value=io.BytesIO(self.data)), \
             mock.patch.object(scanner, "run", side_effect=self.fake_run), \
             mock.patch.object(scanner, "validate_db", return_value={"Version": 2}):
            return release_scan.collect_release(self.api, "org/repo", self.output)

    def test_scan_published_asset_keeps_tag_commit_digest_and_runtime_findings(self):
        result = self.collect()
        self.assertEqual("v0.10.1", result["identity"]["ref"])
        self.assertEqual("a" * 40, result["identity"]["commit"])
        self.assertEqual(self.release["assets"][0]["digest"], result["identity"]["sbomDigest"])
        self.assertEqual("CVE-release", result["scan"]["findings"][0]["VulnerabilityID"])
        self.assertEqual(["publishedRuntime"], result["scan"]["scopes"])
        self.assertEqual(self.data, (self.output / "published-bom.json").read_bytes())

    def test_absent_duplicate_or_modified_published_asset_fails(self):
        original = copy.deepcopy(self.release)
        for mutation in ("missing", "duplicate", "digest", "url", "prerelease"):
            self.release = copy.deepcopy(original)
            if mutation == "missing":
                self.release["assets"] = []
            elif mutation == "duplicate":
                self.release["assets"] *= 2
            elif mutation == "digest":
                self.release["assets"][0]["digest"] = "sha256:" + "f" * 64
            elif mutation == "url":
                self.release["assets"][0]["browser_download_url"] = "https://example.invalid/bom.json"
            else:
                self.release["prerelease"] = True
            with self.subTest(mutation=mutation), self.assertRaises(scanner.ScanError):
                self.collect()

    def test_release_does_not_rebuild_or_execute_old_tag_code(self):
        with mock.patch.object(release_scan.subprocess, "run", side_effect=AssertionError("build not allowed")):
            self.collect()


class MainCollectionTest(unittest.TestCase):
    def test_existing_success_is_removed_before_failed_build(self):
        with tempfile.TemporaryDirectory() as root:
            checkout = Path(root) / "source"
            reports = checkout / "build/reports/security"
            reports.mkdir(parents=True)
            scanner.write_json(reports / "summary.json", {"scanStatus": "complete", "commit": "a" * 40})
            with mock.patch.object(subprocess, "check_output", return_value="a" * 40), \
                 mock.patch.object(subprocess, "run", return_value=subprocess.CompletedProcess([], 1)):
                with self.assertRaises(scanner.ScanError):
                    release_scan.collect_main(checkout, Path(root) / "output")
            self.assertFalse((reports / "summary.json").exists())


if __name__ == "__main__":
    unittest.main()
