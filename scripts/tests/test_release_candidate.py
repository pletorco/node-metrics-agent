import copy
import datetime as dt
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release_candidate as candidate
import security_scan as scanner
from security_gate import digest
from test_security_gate import evaluate, summary, finding
from test_security_monitor import NOW


class ReleasePublicationBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.TemporaryDirectory()
        self.addCleanup(self.root.cleanup)
        previous = Path.cwd()
        os.chdir(self.root.name)
        self.addCleanup(os.chdir, previous)
        for directory in ("build/reports/security", "build/libs", ".github"):
            Path(directory).mkdir(parents=True)
        self.bom = Path("build/reports/bom.json")
        self.bom.write_text('{"bomFormat":"CycloneDX"}\n')
        self.policy = Path(".github/security-monitoring.json")
        self.policy.write_text("{}\n")
        self.jar = Path("build/libs/node-metrics-agent-0.10.1-all.jar")
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("META-INF/cyclonedx/bom.json", self.bom.read_bytes())
        Path("notes.md").write_text("Reviewed release notes\n")
        self.gate = evaluate(summary())
        self.gate.update(runtimeBomSha256=digest(self.bom), policySha256=digest(self.policy))
        scanner.write_json("build/reports/security/gate.json", self.gate)
        self.directory = Path("build/release-candidate")

    def stage(self):
        candidate.stage(self.directory, "v0.10.1", "a" * 40, "notes.md", self.policy, NOW)

    def verify(self, now=NOW):
        candidate.verify(self.directory, "v0.10.1", "a" * 40, self.policy, now)

    def test_exact_scanned_jar_bom_notes_and_checksums_reach_candidate(self):
        self.stage()
        self.verify()
        self.assertEqual(self.jar.read_bytes(), (self.directory / self.jar.name).read_bytes())
        self.assertEqual(self.bom.read_bytes(), (self.directory / "bom.json").read_bytes())

    def test_vulnerable_or_incomplete_gate_cannot_produce_publishable_assets(self):
        for status in ("blocked", "incomplete"):
            self.stage()
            broken = dict(evaluate(summary([finding()])), gateStatus=status)
            broken.update(runtimeBomSha256=digest(self.bom), policySha256=digest(self.policy))
            scanner.write_json("build/reports/security/gate.json", broken)
            with self.subTest(status=status), self.assertRaises(scanner.ScanError):
                self.stage()
            self.assertFalse(self.directory.exists())
            scanner.write_json("build/reports/security/gate.json", self.gate)

    def test_each_artifact_modification_fails_before_publication(self):
        self.stage()
        for name in candidate.candidate_files("v0.10.1"):
            self.stage()
            with (self.directory / name).open("ab") as output:
                output.write(b"changed")
            with self.subTest(file=name), self.assertRaises(scanner.ScanError):
                self.verify()

    def test_old_db_stale_scan_wrong_commit_or_policy_blocks_publication(self):
        self.stage()
        with self.assertRaises(scanner.ScanError):
            self.verify(NOW + dt.timedelta(hours=3))
        with self.assertRaises(scanner.ScanError):
            candidate.verify(self.directory, "v0.10.1", "b" * 40, self.policy, NOW)
        self.policy.write_text("changed policy\n")
        with self.assertRaises(scanner.ScanError):
            self.verify()

    def test_packaged_sbom_must_match_scanned_published_sbom(self):
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("META-INF/cyclonedx/bom.json", "different runtime dependencies")
        with self.assertRaises(scanner.ScanError):
            self.stage()

    def test_extra_files_path_traversal_and_symlinks_are_rejected(self):
        self.stage()
        (self.directory / "unexpected.jar").write_text("not scanned")
        with self.assertRaises(scanner.ScanError):
            self.verify()
        self.stage()
        manifest = scanner.load_json(self.directory / "manifest.json")
        manifest["files"]["../elsewhere"] = "a" * 64
        scanner.write_json(self.directory / "manifest.json", manifest)
        with self.assertRaises(scanner.ScanError):
            self.verify()
        self.stage()
        (self.directory / "bom.json").unlink()
        (self.directory / "bom.json").symlink_to(self.bom.resolve())
        with self.assertRaises(scanner.ScanError):
            self.verify()


if __name__ == "__main__":
    unittest.main()
