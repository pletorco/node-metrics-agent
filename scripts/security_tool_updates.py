#!/usr/bin/env python3
"""Notify once per new stable Trivy release, without changing the installed scanner."""

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import sys
import urllib.request


UPSTREAM = "aquasecurity/trivy"


class UpdateCheckError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise UpdateCheckError(message)


def stable_version(value):
    require(isinstance(value, str) and re.fullmatch(r"v?\d+\.\d+\.\d+", value),
            "Expected a stable Trivy version (vMAJOR.MINOR.PATCH)")
    return tuple(int(part) for part in value.removeprefix("v").split("."))


class GitHub:
    def __init__(self):
        self.token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")

    def request(self, method, path, body=None):
        headers = {"Accept": "application/vnd.github+json", "User-Agent": "node-metrics-agent-security"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        require(method == "GET" or self.token, "GH_TOKEN is required to create an update notification")
        payload = None if body is None else json.dumps(body).encode()
        if payload is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request("https://api.github.com" + path, data=payload,
                                         headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response)
        except (OSError, ValueError) as exc:
            raise UpdateCheckError(f"GitHub update check failed: {method} {path}") from exc


def find_notification(api, repository, marker):
    # List both open and closed issues: dismissing a release must not notify about it again.
    # The workflow serializes notification jobs to prevent check/create races.
    for page in range(1, 101):
        issues = api.request("GET", f"/repos/{repository}/issues?state=all&per_page=100&page={page}")
        require(isinstance(issues, list), "Invalid GitHub issue-list response")
        for issue in issues:
            require(isinstance(issue, dict), "Invalid GitHub issue entry")
            if "pull_request" not in issue and marker in (issue.get("body") or ""):
                require(isinstance(issue.get("html_url"), str), "Update issue has no URL")
                return issue
        if len(issues) < 100:
            return None
    raise UpdateCheckError("Issue pagination exceeded safety limit; cannot safely deduplicate alerts")


def check_updates(api, repository, current, dry_run=False):
    require(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository), "Invalid repository name")
    current_version = stable_version(current)
    release = api.request("GET", f"/repos/{UPSTREAM}/releases/latest")
    require(isinstance(release, dict) and release.get("draft") is False
            and release.get("prerelease") is False, "Latest Trivy release is not a published stable release")
    latest = release.get("tag_name")
    latest_version = stable_version(latest)
    require(release.get("html_url") == f"https://github.com/{UPSTREAM}/releases/tag/{latest}",
            "Unexpected Trivy release URL")
    require(isinstance(release.get("published_at"), str) and release["published_at"],
            "Latest Trivy release has no publication timestamp")
    result = {"tool": "Trivy", "currentVersion": current.lstrip("v"),
              "latestVersion": latest.lstrip("v"), "releaseUrl": release["html_url"],
              "publishedAt": release["published_at"], "notification": "none"}
    if latest_version <= current_version:
        result["status"] = "up-to-date"
        return result
    result["status"] = "update-available"
    marker = f"<!-- node-metrics-agent:security-tool-update:trivy:{result['latestVersion']} -->"
    existing = find_notification(api, repository, marker)
    if existing:
        result.update(notification="already-notified", issueUrl=existing["html_url"])
    elif dry_run:
        result["notification"] = "would-create"
    else:
        issue = api.request("POST", f"/repos/{repository}/issues", {
            "title": f"security: update Trivy to {result['latestVersion']}",
            "labels": ["dependencies"],
            "body": f"{marker}\n## Scanner update available\n\n"
                    f"The CVE workflow is pinned to Trivy **{result['currentVersion']}**. "
                    f"A new stable release **{result['latestVersion']}** is available "
                    f"(published {result['publishedAt']}).\n\n"
                    f"[Official release notes]({result['releaseUrl']})\n\n"
                    "- [ ] Review compatibility and security changes in the release notes.\n"
                    "- [ ] Update `TRIVY_VERSION` in `.github/workflows/security.yml`.\n"
                    "- [ ] Run scanner regression tests and the full Security workflow.\n\n"
                    "This notification does not change the scanner or vulnerability DB update policy. "
                    "The same release is notified only once, even if this issue is closed.\n"
        })
        require(isinstance(issue, dict) and isinstance(issue.get("html_url"), str),
                "GitHub did not return the created notification issue")
        result.update(notification="created", issueUrl=issue["html_url"])
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--current-version", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    result = {"status": "incomplete", "checkedAt": dt.datetime.now(dt.timezone.utc).isoformat()}
    try:
        result.update(check_updates(GitHub(), args.repository, args.current_version, args.dry_run))
        code = 0
    except UpdateCheckError as exc:
        result["error"] = str(exc)
        print(f"TOOL UPDATE CHECK INCOMPLETE: {exc}", file=sys.stderr)
        code = 1
    output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))
    return code


if __name__ == "__main__":
    sys.exit(main())
