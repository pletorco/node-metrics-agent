import argparse
import contextlib
import copy
import datetime as dt
import io
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import security_gate as gate
import security_scan as scanner
from test_security_scan import inventory
from test_security_monitor import NOW, POLICY


def policy():
    return dict(copy.deepcopy(POLICY), gates={"runtime": ["UNKNOWN", "HIGH", "CRITICAL"],
                                             "tooling": ["UNKNOWN", "HIGH", "CRITICAL"]})


def finding(package="example:runtime", severity="HIGH", scope="tooling", installed="1.0"):
    return {"VulnerabilityID": "CVE-gate-fixture", "PkgName": package, "InstalledVersion": installed,
            "Severity": severity, "scope": scope}


def summary(findings=None):
    return {"scanStatus": "complete", "commit": "a" * 40, "finishedAt": NOW.isoformat(),
            "coordinates": len(inventory()["components"]), "scopes": sorted(scanner.SCOPES),
            "gradleAdvisoriesChecked": 24, "findings": findings or [], "trivyVersion": "0.76.2",
            "db": {"Version": 2, "UpdatedAt": (NOW - dt.timedelta(hours=1)).isoformat(),
                   "NextUpdate": (NOW + dt.timedelta(hours=4)).isoformat()}}


def evaluate(data, chosen_policy=None, context="release", now=NOW):
    return gate.evaluate(data, inventory(), chosen_policy or policy(), context, "a" * 40, now)


class GatePolicyTest(unittest.TestCase):
    def test_vulnerable_runtime_fixture_blocks_and_deduplicates_runtime_tooling(self):
        result = evaluate(summary([finding(scope="runtime"), finding(scope="tooling")]))
        self.assertEqual("blocked", result["gateStatus"])
        self.assertEqual(1, result["blockingFindings"])
        self.assertEqual(1, len(result["findings"]["runtime"]))
        self.assertEqual([], result["findings"]["tooling"])

    def test_test_tools_and_gradle_are_separate_from_packaged_runtime(self):
        result = evaluate(summary([finding(package="example:checkstyle"),
                                   finding(package="org.gradle:gradle-core", installed="9.9.1", scope="gradle")]))
        self.assertEqual(2, result["blockingFindings"])
        self.assertEqual([], result["findings"]["runtime"])
        self.assertEqual(2, len(result["findings"]["tooling"]))

    def test_all_declared_blocking_levels_and_nonblocking_levels(self):
        for severity in scanner.SEVERITIES:
            result = evaluate(summary([finding(severity=severity)]))
            with self.subTest(severity=severity):
                self.assertEqual(severity in {"UNKNOWN", "HIGH", "CRITICAL"}, result["gateStatus"] == "blocked")
                self.assertEqual(1, len(result["findings"]["runtime"]))

    def test_incomplete_missing_stale_db_or_wrong_commit_can_never_pass(self):
        cases = [summary() for _ in range(6)]
        cases[0]["scanStatus"] = "incomplete"
        cases[1]["scopes"].pop()
        cases[2]["commit"] = "b" * 40
        cases[3]["gradleAdvisoriesChecked"] = 0
        cases[4]["db"]["NextUpdate"] = (NOW - dt.timedelta(minutes=1)).isoformat()
        cases[5]["findings"] = [finding(package="not:in-inventory")]
        for data in cases:
            with self.subTest(data=data), self.assertRaises(scanner.ScanError):
                evaluate(data)

    def test_policy_cannot_remove_baseline_blocking_severities(self):
        chosen = policy()
        chosen["gates"]["runtime"] = ["LOW"]
        with self.assertRaises(scanner.ScanError):
            evaluate(summary(), chosen)

    def test_exact_context_exception_expires_and_escalation_blocks(self):
        chosen = policy()
        chosen["exceptions"] = [{"target": "release", "advisory": "CVE-gate-fixture", "package": "example:runtime",
            "version": "1.0", "severity": "HIGH", "owner": "maintainer", "reason": "reviewed mitigation",
            "issue": "https://github.com/org/repo/issues/1", "createdAt": NOW.isoformat(),
            "expiresAt": (NOW + dt.timedelta(hours=1)).isoformat()}]
        self.assertEqual("passed", evaluate(summary([finding()]), chosen)["gateStatus"])
        self.assertEqual("blocked", evaluate(summary([finding()]), chosen, context="pr")["gateStatus"])
        self.assertEqual("blocked", evaluate(summary([finding(severity="CRITICAL")]), chosen)["gateStatus"])
        data = summary([finding()])
        data["finishedAt"] = (NOW + dt.timedelta(hours=2)).isoformat()
        self.assertEqual("blocked", evaluate(data, chosen, now=NOW + dt.timedelta(hours=2))["gateStatus"])


class GateExecutionFailureTest(unittest.TestCase):
    def test_build_failure_invalidates_previous_success_and_child_gets_no_token(self):
        with tempfile.TemporaryDirectory() as root:
            reports = Path(root)
            scanner.write_json(reports / "gate.json", {"gateStatus": "passed"})
            scanner.write_json(reports / "summary.json", {"scanStatus": "complete"})
            with mock.patch.object(gate, "REPORTS", reports), \
                 mock.patch.object(gate.subprocess, "check_output", return_value="a" * 40), \
                 mock.patch.object(gate.subprocess, "run", side_effect=OSError("inventory unavailable")) as run, \
                 mock.patch.dict(os.environ, {"GH_TOKEN": "sensitive", "GITHUB_TOKEN": "sensitive"}), \
                 contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(2, gate.execute("pr", "a" * 40))
            self.assertEqual("incomplete", scanner.load_json(reports / "gate.json")["gateStatus"])
            self.assertEqual("incomplete", scanner.load_json(reports / "summary.json")["scanStatus"])
            self.assertNotIn("GH_TOKEN", run.call_args.kwargs["env"])
            self.assertNotIn("GITHUB_TOKEN", run.call_args.kwargs["env"])
            self.assertNotIn("sensitive", (reports / "gate.json").read_text())


if __name__ == "__main__":
    unittest.main()
