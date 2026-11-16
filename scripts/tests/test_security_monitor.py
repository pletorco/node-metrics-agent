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
import security_monitor as monitor
import security_scan as scanner


NOW = dt.datetime(2026, 11, 16, 12, tzinfo=dt.timezone.utc)
POLICY = {"owner": "repository maintainers", "triageHours": {"CRITICAL": 24, "HIGH": 72,
          "MEDIUM": 168, "LOW": 336, "UNKNOWN": 72}, "maxScanAgeHours": 36, "exceptions": []}


def finding(severity="HIGH", scope="runtime"):
    return {"VulnerabilityID": "CVE-fixture", "PkgName": "example:runtime",
            "InstalledVersion": "1.0", "Severity": severity, "scope": scope}


def report(findings=None, target="main"):
    return {"schemaVersion": 1, "target": target, "runId": "123", "finishedAt": NOW.isoformat(),
            "scanStatus": "complete", "identity": {"commit": "a" * 40, "ref": "main" if target == "main" else "v1.0.0",
            "sbomDigest": "sha256:" + "b" * 64}, "scan": {"scanStatus": "complete",
            "commit": "a" * 40, "coordinates": 10, "trivyVersion": "0.76.2",
            "scopes": sorted(scanner.SCOPES) if target == "main" else ["publishedRuntime"],
            "db": {"Version": 2, "UpdatedAt": (NOW - dt.timedelta(hours=1)).isoformat(),
                   "NextUpdate": (NOW + dt.timedelta(hours=4)).isoformat()},
            "findings": findings or []}}


def apply(previous, data, policy=None, now=NOW):
    return monitor.apply_report(previous, data, "main", "123", "https://github.com/org/repo/actions/runs/123",
                                policy or POLICY, now)


class StateTransitionsTest(unittest.TestCase):
    def test_new_repeat_escalation_resolution_and_reappearance(self):
        state, changes = apply({}, report([finding("MEDIUM")]))
        self.assertIn("new finding", changes[0])
        state, changes = apply(state, report([finding("MEDIUM")]))
        self.assertEqual([], changes)
        state, changes = apply(state, report([finding("CRITICAL")]))
        self.assertIn("severity increased", changes[0])
        state, changes = apply(state, report())
        self.assertIn("resolved", changes[0])
        state, changes = apply(state, report([finding()]))
        self.assertIn("new finding", changes[0])

    def test_clean_initialization_and_unchanged_runs_are_quiet(self):
        state, changes = apply({}, report())
        self.assertEqual([], changes)
        self.assertEqual([], apply(state, report())[1])

    def test_runtime_and_tooling_duplicates_have_one_alert_with_both_scopes(self):
        state, changes = apply({}, report([finding("HIGH"), finding("CRITICAL", "tooling")]))
        self.assertEqual(1, len(changes))
        item = next(iter(state["findings"].values()))
        self.assertEqual("CRITICAL", item["severity"])
        self.assertEqual(["runtime", "tooling"], item["scopes"])

    def test_failure_preserves_findings_and_last_success_then_recovers(self):
        state, _ = apply({}, report([finding()]))
        success = state["lastSuccessAt"]
        broken = report()
        broken.update(scanStatus="incomplete", error="DB unavailable")
        failed, changes = apply(state, broken, now=NOW + dt.timedelta(hours=1))
        self.assertIn("scan incomplete", changes[0])
        self.assertEqual(success, failed["lastSuccessAt"])
        self.assertEqual(state["findings"], failed["findings"])
        self.assertEqual([], apply(failed, broken)[1])
        recovered, changes = apply(failed, report([finding()]))
        self.assertEqual(["main: scan recovered"], changes)
        self.assertIsNone(recovered["error"])

    def test_missing_artifact_wrong_run_commit_or_scope_never_resolves(self):
        state, _ = apply({}, report([finding()]))
        cases = [{}, report(), report(), report(), report()]
        cases[1]["runId"] = "old-run"
        cases[2]["scan"]["commit"] = "b" * 40
        cases[3]["scan"]["scopes"].pop()
        cases[4]["finishedAt"] = (NOW - dt.timedelta(hours=3)).isoformat()
        for data in cases:
            with self.subTest(data=data):
                failed, changes = apply(state, data)
                self.assertTrue(failed["error"])
                self.assertEqual(state["findings"], failed["findings"])
                self.assertFalse(any("resolved" in change for change in changes))

    def test_stale_db_is_a_failure_even_if_report_claims_complete(self):
        data = report()
        data["scan"]["db"]["NextUpdate"] = (NOW - dt.timedelta(hours=1)).isoformat()
        state, _ = apply({}, data)
        self.assertIn("overdue", state["error"])
        self.assertNotIn("lastSuccessAt", state)

    def test_release_requires_its_own_scopes_and_verified_asset_digest(self):
        data = report(target="release")
        monitor.validate_report(data, "release", "123", NOW)
        data["identity"].pop("sbomDigest")
        with self.assertRaises(scanner.ScanError):
            monitor.validate_report(data, "release", "123", NOW)

    def test_freshness_detects_missing_missed_and_disabled_then_recovers(self):
        state = {"targets": {}}
        self.assertEqual(2, len(monitor.freshness_problems(state, "active", POLICY, NOW)))
        for target in monitor.TARGETS:
            state["targets"][target] = {"lastSuccessAt": NOW.isoformat()}
        self.assertEqual([], monitor.freshness_problems(state, "active", POLICY, NOW))
        self.assertEqual(2, len(monitor.freshness_problems(state, "active", POLICY, NOW + dt.timedelta(hours=37))))
        self.assertIn("disabled_inactivity", monitor.freshness_problems(state, "disabled_inactivity", POLICY, NOW)[0])

    def test_watchdog_suppresses_unchanged_failures_and_notifies_recovery(self):
        state = {"targets": {}}
        self.assertEqual(2, len(monitor.apply_watchdog(state, "active", POLICY, NOW)))
        self.assertEqual([], monitor.apply_watchdog(state, "active", POLICY, NOW))
        for target in monitor.TARGETS:
            state["targets"][target] = {"lastSuccessAt": NOW.isoformat()}
        self.assertEqual(["Daily scan freshness recovered"], monitor.apply_watchdog(state, "active", POLICY, NOW))
        self.assertEqual([], monitor.apply_watchdog(state, "active", POLICY, NOW))


class ExceptionsAndPersistenceTest(unittest.TestCase):
    def exception_policy(self):
        policy = copy.deepcopy(POLICY)
        policy["exceptions"] = [{"target": "main", "advisory": "CVE-fixture", "package": "example:runtime",
            "version": "1.0", "severity": "HIGH", "owner": "maintainer", "reason": "mitigation verified",
            "issue": "https://github.com/org/repo/issues/1", "createdAt": NOW.isoformat(),
            "expiresAt": (NOW + dt.timedelta(hours=2)).isoformat()}]
        return policy

    def test_exception_expires_and_cannot_hide_a_severity_increase(self):
        policy = self.exception_policy()
        state, changes = apply({}, report([finding()]), policy)
        self.assertEqual([], changes)
        self.assertIn("exception", next(iter(state["findings"].values())))
        _, changes = apply(state, report([finding("CRITICAL")]), policy)
        self.assertIn("new finding", changes[0])
        data = report([finding()])
        data["finishedAt"] = (NOW + dt.timedelta(hours=3)).isoformat()
        _, changes = apply(state, data, policy, NOW + dt.timedelta(hours=3))
        self.assertIn("new finding", changes[0])

    def test_policy_rejects_unowned_untracked_and_overlong_exceptions(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "policy.json"
            for key, value in (("owner", ""), ("issue", "untracked"),
                               ("expiresAt", (NOW + dt.timedelta(days=31)).isoformat())):
                policy = self.exception_policy()
                policy["exceptions"][0][key] = value
                path.write_text(json.dumps(policy))
                with self.subTest(key=key), self.assertRaises(scanner.ScanError):
                    monitor.load_policy(path, NOW)

    def test_state_round_trip_and_corruption_must_not_reset_history(self):
        current, _ = apply({}, report([finding()]))
        state = {"schemaVersion": 1, "targets": {"main": current}}
        body = monitor.issue_body(state, POLICY)
        self.assertEqual(state, monitor.decode_state({"body": body}))
        for broken in ("", monitor.STATE_PREFIX + "bad json -->"):
            with self.assertRaises(scanner.ScanError):
                monitor.decode_state({"body": broken})

    def test_public_issue_cannot_forge_state_and_closed_bot_issue_is_reused(self):
        public = {"body": monitor.MARKER, "author_association": "NONE", "user": {"login": "stranger"}}
        trusted = {"body": monitor.MARKER, "state": "closed", "user": {"login": "github-actions[bot]"}}
        api = mock.Mock()
        api.request.return_value = [public, trusted]
        self.assertEqual(trusted, monitor.find_monitor_issue(api, "org/repo"))

    def test_quiet_updates_closed_issue_reopening_and_dry_run(self):
        api = mock.Mock()
        issue = {"number": 1, "state": "closed"}
        state = {"schemaVersion": 1, "targets": {}}
        with contextlib.redirect_stdout(io.StringIO()):
            monitor.persist(api, "org/repo", issue, state, [], POLICY, False)
            self.assertEqual(1, api.request.call_count)
            self.assertNotIn("state", api.request.call_args.args[2])
            api.reset_mock()
            monitor.persist(api, "org/repo", issue, state, ["new finding"], POLICY, False)
            self.assertEqual(2, api.request.call_count)
            self.assertEqual("open", api.request.call_args.args[2]["state"])
            api.reset_mock()
            monitor.persist(api, "org/repo", issue, state, ["new finding"], POLICY, True)
            api.request.assert_not_called()

    def test_comment_failure_does_not_acknowledge_or_persist_transition(self):
        api = mock.Mock()
        api.request.side_effect = monitor.UpdateCheckError("write unavailable")
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(monitor.UpdateCheckError):
            monitor.persist(api, "org/repo", {"number": 1}, {"schemaVersion": 1, "targets": {}},
                            ["new finding"], POLICY, False)
        self.assertEqual(1, api.request.call_count)
        self.assertEqual("POST", api.request.call_args.args[0])


if __name__ == "__main__":
    unittest.main()
