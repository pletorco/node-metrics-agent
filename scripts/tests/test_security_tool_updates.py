import copy
from pathlib import Path
import sys
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import security_tool_updates as updates


class ToolUpdateTest(unittest.TestCase):
    def setUp(self):
        self.release = {"tag_name": "v0.76.2", "draft": False, "prerelease": False,
                        "html_url": "https://github.com/aquasecurity/trivy/releases/tag/v0.76.2",
                        "published_at": "2026-11-05T12:50:25Z"}
        self.issues = []
        self.posts = []
        self.api = mock.Mock()
        self.api.request.side_effect = self.request

    def request(self, method, path, body=None):
        if path.endswith("/releases/latest"):
            return self.release
        if method == "POST":
            self.posts.append(body)
            issue = dict(body, html_url="https://github.com/owner/repo/issues/1", state="open")
            self.issues.append(issue)
            return issue
        return self.issues

    def test_current_or_newer_version_stays_quiet(self):
        for current in ["0.76.2", "v0.76.2", "0.77.0", "1.0.0"]:
            result = updates.check_updates(self.api, "owner/repo", current)
            self.assertEqual("up-to-date", result["status"])
        self.assertFalse(self.posts)
        self.assertTrue(all(call.args[1].endswith("/releases/latest")
                            for call in self.api.request.call_args_list))

    def test_update_creates_one_issue_across_repeated_checks(self):
        first = updates.check_updates(self.api, "owner/repo", "0.72.0")
        second = updates.check_updates(self.api, "owner/repo", "0.72.0")
        self.assertEqual("created", first["notification"])
        self.assertEqual("already-notified", second["notification"])
        self.assertEqual(1, len(self.posts))
        self.assertIn("0.76.2", self.posts[0]["title"])
        self.assertIn(self.release["html_url"], self.posts[0]["body"])

    def test_closed_notification_is_not_reopened_or_repeated(self):
        updates.check_updates(self.api, "owner/repo", "0.72.0")
        self.issues[0]["state"] = "closed"
        self.assertEqual("already-notified", updates.check_updates(self.api, "owner/repo", "0.72.0")["notification"])
        self.assertEqual(1, len(self.posts))

    def test_later_release_creates_a_distinct_notification(self):
        updates.check_updates(self.api, "owner/repo", "0.72.0")
        self.release.update(tag_name="v0.77.0", html_url="https://github.com/aquasecurity/trivy/releases/tag/v0.77.0")
        self.assertEqual("created", updates.check_updates(self.api, "owner/repo", "0.72.0")["notification"])
        self.assertEqual(2, len(self.posts))

    def test_prerelease_draft_and_invalid_release_metadata_fail_without_notifying(self):
        valid = copy.deepcopy(self.release)
        for key, value in [("prerelease", True), ("draft", True), ("tag_name", "v0.77.0-rc1"),
                           ("html_url", "https://example.com/"), ("published_at", None)]:
            self.release = dict(valid, **{key: value})
            with self.subTest(key=key), self.assertRaises(updates.UpdateCheckError):
                updates.check_updates(self.api, "owner/repo", "0.72.0")
        self.assertFalse(self.posts)

    def test_dry_run_shows_available_update_without_writes(self):
        result = updates.check_updates(self.api, "owner/repo", "0.72.0", dry_run=True)
        self.assertEqual("would-create", result["notification"])
        self.assertFalse(self.posts)

    def test_release_api_failure_is_not_up_to_date(self):
        self.api.request.side_effect = updates.UpdateCheckError("API unavailable")
        with self.assertRaises(updates.UpdateCheckError):
            updates.check_updates(self.api, "owner/repo", "0.72.0")
        self.assertFalse(self.posts)

    def test_bad_issue_response_does_not_risk_duplicate_notification(self):
        self.issues = {"message": "rate limited"}
        with self.assertRaises(updates.UpdateCheckError):
            updates.check_updates(self.api, "owner/repo", "0.72.0")
        self.assertFalse(self.posts)

    def test_versions_compare_numerically_and_reject_latest_or_prerelease(self):
        self.assertLess(updates.stable_version("0.9.0"), updates.stable_version("0.10.0"))
        for value in ["latest", "0.77.0-rc1", "0.76", None]:
            with self.assertRaises(updates.UpdateCheckError):
                updates.stable_version(value)


if __name__ == "__main__":
    unittest.main()
