#!/usr/bin/env python3
"""Persist daily scan health and notify only on meaningful security state changes."""

import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import sys

import security_scan as scanner
from security_tool_updates import GitHub, UpdateCheckError


MARKER = "<!-- node-metrics-agent:daily-security-monitor:v1 -->"
STATE_PREFIX = "<!-- security-monitor-state:"
RANK = {"UNKNOWN": 0, "LOW": 1, "MEDIUM": 2, "HIGH": 3, "CRITICAL": 4}
TARGETS = ("main", "release")


def stamp(now):
    return now.isoformat()


def find_monitor_issue(api, repository):
    # Public contributors can create issues too; only trust state owned by the Actions bot
    # or a repository collaborator. An unrelated issue must not forge scan freshness.
    for page in range(1, 101):
        issues = api.request("GET", f"/repos/{repository}/issues?state=all&per_page=100&page={page}")
        scanner.require(isinstance(issues, list), "Invalid monitoring issue-list response")
        for issue in issues:
            trusted = issue.get("user", {}).get("login") == "github-actions[bot]" or \
                issue.get("author_association") in {"OWNER", "MEMBER", "COLLABORATOR"}
            if trusted and "pull_request" not in issue and MARKER in (issue.get("body") or ""):
                return issue
        if len(issues) < 100:
            return None
    raise scanner.ScanError("Cannot safely locate durable monitoring state")


def load_policy(path, now):
    policy = scanner.load_json(path)
    scanner.require(isinstance(policy.get("owner"), str) and policy["owner"], "Missing monitoring owner")
    scanner.require(set(policy.get("triageHours", {})) == set(RANK)
                    and all(isinstance(v, int) and 0 < v <= 336 for v in policy["triageHours"].values()),
                    "Invalid severity triage policy")
    scanner.require(24 < policy.get("maxScanAgeHours", 0) <= 48, "Invalid scan freshness limit")
    scanner.require(isinstance(policy.get("exceptions"), list), "Missing exceptions policy")
    for exception in policy["exceptions"]:
        scanner.require(isinstance(exception, dict) and exception.get("target") in TARGETS
                        and exception.get("severity") in RANK
                        and all(isinstance(exception.get(k), str) and exception[k]
                                for k in ("advisory", "package", "version", "owner", "reason", "issue")),
                        "Incomplete security exception")
        scanner.require(re.fullmatch(r"https://github.com/[^/]+/[^/]+/issues/\d+", exception["issue"]),
                        "Exception must reference a tracking issue")
        created = scanner.timestamp(exception.get("createdAt"))
        expires = scanner.timestamp(exception.get("expiresAt"))
        scanner.require(created <= now + dt.timedelta(minutes=5) and
                        dt.timedelta(0) < expires - created <= dt.timedelta(days=30),
                        "Exception duration must be at most 30 days")
    return policy


def normalize_findings(findings, target, policy, now):
    scanner.require(isinstance(findings, list), "Missing findings array")
    result = {}
    for finding in findings:
        scanner.require(isinstance(finding, dict) and finding.get("Severity") in RANK
                        and all(isinstance(finding.get(k), str) and finding[k]
                                for k in ("VulnerabilityID", "PkgName", "InstalledVersion", "scope")),
                        "Invalid monitoring finding")
        key = hashlib.sha256(json.dumps([finding[k] for k in
                             ("VulnerabilityID", "PkgName", "InstalledVersion")]).encode()).hexdigest()
        item = {"advisory": finding["VulnerabilityID"], "package": finding["PkgName"],
                "version": finding["InstalledVersion"], "severity": finding["Severity"],
                "fixedVersion": finding.get("FixedVersion"), "scopes": [finding["scope"]]}
        if key in result:
            item["scopes"] = sorted(set(result[key]["scopes"] + item["scopes"]))
            if RANK[result[key]["severity"]] > RANK[item["severity"]]:
                item["severity"] = result[key]["severity"]
        result[key] = item
    scanner.require(len(result) <= 150, "Too many findings for durable issue state; review reports directly")
    for item in result.values():
        for exception in policy["exceptions"]:
            if exception["target"] == target and all(exception[k] == item[k]
                    for k in ("advisory", "package", "version")) and RANK[item["severity"]] <= RANK[exception["severity"]] \
                    and now < scanner.timestamp(exception["expiresAt"]):
                item["exception"] = {k: exception[k] for k in ("owner", "reason", "issue", "expiresAt")}
    return result


def validate_report(report, target, run_id, now):
    scanner.require(isinstance(report, dict) and report.get("schemaVersion") == 1
                    and report.get("target") == target and report.get("runId") == run_id,
                    "Missing/mismatched monitoring artifact")
    finished = scanner.timestamp(report.get("finishedAt"))
    scanner.require(dt.timedelta(minutes=-5) <= now - finished <= dt.timedelta(hours=2),
                    "Monitoring artifact is stale or dated in the future")
    scanner.require(report.get("scanStatus") == "complete", report.get("error", "Scan did not complete"))
    identity, scan = report.get("identity", {}), report.get("scan", {})
    scanner.require(isinstance(identity, dict) and re.fullmatch(r"[0-9a-f]{40}", identity.get("commit", ""))
                    and isinstance(identity.get("ref"), str) and identity["ref"], "Missing scan identity")
    scanner.require(isinstance(scan, dict) and scan.get("scanStatus") == "complete"
                    and scan.get("commit") == identity["commit"] and scan.get("coordinates", 0) > 0,
                    "Incomplete dependency scan evidence")
    expected_scopes = scanner.SCOPES if target == "main" else {"publishedRuntime"}
    scanner.require(set(scan.get("scopes", [])) == expected_scopes, "Scan scope coverage is incomplete")
    if target == "release":
        scanner.require(re.fullmatch(r"sha256:[0-9a-f]{64}", identity.get("sbomDigest", "")),
                        "Published release has no verified SBOM digest")
    scanner.validate_db({"Version": scan.get("trivyVersion"), "VulnerabilityDB": scan.get("db")}, now, 48)
    return scan, identity, finished


def finding_line(item, policy):
    return (f"{item['severity']} {item['advisory']} — {item['package']}@{item['version']} "
            f"({', '.join(item['scopes'])}; triage within {policy['triageHours'][item['severity']]}h)")


def apply_report(previous, report, target, run_id, run_url, policy, now):
    state = dict(previous or {})
    state.update(lastAttemptAt=stamp(now), runUrl=run_url)
    try:
        scan, identity, finished = validate_report(report, target, run_id, now)
        findings = normalize_findings(scan.get("findings"), target, policy, now)
    except scanner.ScanError as exc:
        error = str(exc)
        changes = [] if previous.get("error") == error else [f"{target}: scan incomplete — {error}"]
        # Failed evidence cannot resolve findings or move the last-success timestamp forward.
        state.update(error=error)
        return state, changes
    old = previous.get("findings", {})
    changes = []
    if previous.get("error"):
        changes.append(f"{target}: scan recovered")
    old_active = {k: v for k, v in old.items() if "exception" not in v}
    active = {k: v for k, v in findings.items() if "exception" not in v}
    for key, item in active.items():
        if key not in old_active:
            changes.append(f"{target}: new finding — {finding_line(item, policy)}")
        elif RANK[item["severity"]] > RANK[old_active[key]["severity"]]:
            changes.append(f"{target}: severity increased from {old_active[key]['severity']} — {finding_line(item, policy)}")
        elif RANK[item["severity"]] < RANK[old_active[key]["severity"]]:
            changes.append(f"{target}: severity decreased — {finding_line(item, policy)}")
    for key, item in old_active.items():
        if key not in findings:
            changes.append(f"{target}: resolved — {item['advisory']} {item['package']}@{item['version']}")
        elif key not in active:
            changes.append(f"{target}: time-limited exception accepted — {item['advisory']}")
    state.update(lastSuccessAt=stamp(finished), identity=identity, findings=findings, error=None,
                 scannerVersion=scan["trivyVersion"], dbUpdatedAt=scan["db"]["UpdatedAt"])
    return state, changes


def freshness_problems(state, workflow_state, policy, now):
    problems = []
    if workflow_state != "active":
        problems.append(f"Security workflow is {workflow_state}")
    for target in TARGETS:
        success = state.get("targets", {}).get(target, {}).get("lastSuccessAt")
        if not success:
            problems.append(f"{target}: no successful complete scan recorded")
        else:
            age = now - scanner.timestamp(success)
            if age < dt.timedelta(minutes=-5) or age > dt.timedelta(hours=policy["maxScanAgeHours"]):
                problems.append(f"{target}: last successful complete scan is stale")
    return problems


def apply_watchdog(state, workflow_state, policy, now):
    problems = freshness_problems(state, workflow_state, policy, now)
    changes = []
    if problems != state.get("freshnessProblems", []):
        changes.extend(problems or ["Daily scan freshness recovered"])
    state.update(freshnessProblems=problems, watchdogCheckedAt=stamp(now))
    return changes


def decode_state(issue):
    if issue is None:
        return {"schemaVersion": 1, "targets": {}}
    body = issue.get("body", "")
    match = re.search(re.escape(STATE_PREFIX) + r"(.*?) -->", body, re.DOTALL)
    scanner.require(match is not None, "Monitoring issue state is missing; do not reset it silently")
    try:
        state = json.loads(match[1])
    except ValueError as exc:
        raise scanner.ScanError("Monitoring issue state is invalid") from exc
    scanner.require(isinstance(state, dict) and state.get("schemaVersion") == 1
                    and isinstance(state.get("targets"), dict), "Unsupported monitoring state")
    for target, current in state["targets"].items():
        scanner.require(target in TARGETS and isinstance(current, dict)
                        and isinstance(current.get("findings", {}), dict), "Invalid monitoring target state")
        for finding in current.get("findings", {}).values():
            scanner.require(isinstance(finding, dict) and finding.get("severity") in RANK
                            and isinstance(finding.get("scopes"), list)
                            and all(isinstance(finding.get(k), str) for k in ("advisory", "package", "version")),
                            "Invalid stored monitoring finding")
        if current.get("lastSuccessAt"):
            scanner.timestamp(current["lastSuccessAt"])
    return state


def issue_body(state, policy):
    lines = [MARKER, "## Daily dependency security monitoring", "",
             f"Owner: **{policy['owner']}**. Triage CRITICAL within 24h; HIGH within 72h.", "",
             "This issue retains scan state. Updates are quiet; comments notify state changes.",
             "Do not remove its machine state. Closed issues are reused and reopened on alerts.", ""]
    for target in TARGETS:
        current = state.get("targets", {}).get(target, {})
        identity = current.get("identity", {})
        lines.extend([f"### {target}", "", f"Last complete scan: {current.get('lastSuccessAt', 'never')}",
                      f"Identity: {identity.get('ref', '?')} / {identity.get('commit', '?')}",
                      f"Evidence: {current.get('runUrl', 'not available')}",
                      f"Scan error: {current.get('error') or 'none'}", ""])
        for finding in current.get("findings", {}).values():
            line = finding_line(finding, policy)
            if "exception" in finding:
                line += f" — excepted until {finding['exception']['expiresAt']} ({finding['exception']['issue']})"
            lines.append(f"- {line}")
        lines.append("")
    if state.get("freshnessProblems"):
        lines.extend(["### Freshness alerts", ""] + [f"- {p}" for p in state["freshnessProblems"]] + [""])
    encoded = json.dumps(state, separators=(",", ":")).replace("<", "\\u003c").replace(">", "\\u003e")
    lines.append(STATE_PREFIX + encoded + " -->")
    body = "\n".join(lines)
    scanner.require(len(body) < 60000, "Monitoring issue state exceeds safe size; inspect artifacts")
    return body


def persist(api, repository, issue, state, changes, policy, dry_run):
    body = issue_body(state, policy)
    print(json.dumps({"changes": changes, "state": state, "dryRun": dry_run}, indent=2))
    if dry_run:
        return
    if issue:
        # Post before saving state. If posting fails, the transition is not silently acknowledged.
        # A failure after posting can repeat a comment on retry, but cannot lose the alert.
        if changes:
            api.request("POST", f"/repos/{repository}/issues/{issue['number']}/comments", {
                "body": "## Security monitoring changes\n\n" + "\n".join(f"- {c}" for c in changes)
                        + "\n\nEvidence and current identities are recorded in the issue body."
            })
        update = {"body": body}
        if changes:
            update["state"] = "open"
        api.request("PATCH", f"/repos/{repository}/issues/{issue['number']}", update)
    else:
        api.request("POST", f"/repos/{repository}/issues", {
            "title": "security: daily dependency monitoring", "labels": ["dependencies"],
            "body": body + ("\n\n## Initial alerts\n\n" + "\n".join(f"- {c}" for c in changes) if changes else "")})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["report", "watchdog"])
    parser.add_argument("--repository", required=True)
    parser.add_argument("--policy", default=".github/security-monitoring.json")
    parser.add_argument("--reports", default="monitor-reports")
    parser.add_argument("--run-id", default="external")
    parser.add_argument("--run-url", default="external watchdog")
    parser.add_argument("--tool-update-result", default="success")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    try:
        scanner.require(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository), "Invalid repository")
        now = dt.datetime.now(dt.timezone.utc)
        policy = load_policy(args.policy, now)
        api = GitHub()
        issue = find_monitor_issue(api, args.repository)
        state = decode_state(issue)
        changes = []
        if args.mode == "report":
            for target in TARGETS:
                try:
                    report = scanner.load_json(Path(args.reports) / target / "summary.json")
                except scanner.ScanError:
                    report = {}
                state["targets"][target], alerts = apply_report(state["targets"].get(target, {}), report,
                        target, args.run_id, args.run_url, policy, now)
                changes.extend(alerts)
            error = None if args.tool_update_result == "success" else f"Scanner version check {args.tool_update_result}"
            if error != state.get("toolUpdateError"):
                changes.append(error or "Scanner version check recovered")
            state["toolUpdateError"] = error
        else:
            workflow = api.request("GET", f"/repos/{args.repository}/actions/workflows/security.yml")
            changes.extend(apply_watchdog(state, workflow.get("state"), policy, now))
        persist(api, args.repository, issue, state, changes, policy, args.dry_run)
        if args.mode == "watchdog":
            return 1 if state.get("freshnessProblems") else 0
        unhealthy = state.get("toolUpdateError") or any(
            current.get("error") or any("exception" not in finding for finding in current.get("findings", {}).values())
            for current in state["targets"].values())
        return 1 if unhealthy else 0
    except (scanner.ScanError, UpdateCheckError, OSError, ValueError) as exc:
        print(f"SECURITY MONITOR INCOMPLETE: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
