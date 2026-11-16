# Dependency security checks

Run with Python 3.9+, JDK 21 and Trivy installed:

```bash
./gradlew trivyScanAll
```

`trivyScan` uses the same complete coverage and blocks HIGH/CRITICAL findings;
`trivyScanAll` blocks every severity. Both execute on every invocation. They update the Trivy
vulnerability DB, require its advertised next update not to be overdue, and reject a DB older
than 48 hours. They also fetch published Gradle vendor advisories on each run. An unavailable
scanner, unavailable source, missing package, unsupported advisory range or malformed report
makes the scan incomplete and fails the task.

`GH_TOKEN` (or `GITHUB_TOKEN`) is optional locally and avoids GitHub's unauthenticated API rate
limit. CI supplies its read-only token. `TRIVY_CACHE_DIR` selects the DB cache directory; the
default is `.trivycache`. `TRIVY_BIN` selects the Trivy executable. The supported metadata/report
formats are vulnerability DB schema 2 and Trivy JSON report schema 2.

## Inventory and evidence

`./gradlew securityInventory` writes `build/reports/security/inventory.json`. It records the
commit, Gradle version, formatter version, resolved Maven coordinates and their scopes:

- `runtimeClasspath`: libraries packaged with the agent
- `testRuntimeClasspath`: JUnit, Mockito and their runtime dependencies
- `buildscript`: plugin implementations and their transitive libraries
- `checkstyle`, `jacocoAgent`, `jacocoAnt`: lint and coverage tools
- `formatter`: the configured google-java-format version and its transitive libraries

The formatter inventory resolves the same module version and component metadata rules as
Spotless's detached configuration. A shared version declaration prevents the two configurations
from silently selecting different formatter versions. This is a separate inventory configuration;
it does not add test or tool libraries to the shaded JAR.

The existing `build/reports/bom.json` stays restricted to packaged runtime dependencies, and is
still embedded in the shaded JAR. A mismatch with the resolved runtime inventory fails the scan.
The full Maven inventory becomes a separate `all-dependencies.cdx.json` for scanning. The scanner
checks that every expected coordinate appears in Trivy's scanned packages, including packages
without findings.

`build/reports/security/` also contains the raw runtime/tooling Trivy reports, the Gradle vendor
advisory snapshot and `summary.json`. The summary records commit, start/finish timestamps,
scanner version, DB update timestamps, scopes, findings and completion status. The Security
workflow uploads this directory even on failure. Old success reports are removed before a new
scan; exit 0 means completed below the configured threshold, 1 means completed with blocking
findings, and 2 means incomplete. Gradle converts any nonzero scanner exit into task failure.
Inventory generation first invalidates a previous clean summary as well, so dependency resolution
failure before the scanner starts is recorded as incomplete.

Gradle is checked separately against its repository security advisories, including GHSA records
without CVE IDs. The range parser supports stable numeric versions, comma-separated comparator
intersections, inclusive bracket ranges, `to`/`through` ranges and the legacy alternative-range
formats published by Gradle. Multiple upper bounds for legacy maintenance lines are interpreted
as a conservative union. Unknown formats require review and fail the scan.

## Scanner update notifications

PR CI, release checks and daily scans pin Trivy through `.github/trivy-version` (currently
`v0.76.2`, verified by the full scanner workflow). Each CVE workflow run also checks its latest stable release
in a separate job. A newer version creates a GitHub issue with the installed/configured version,
new version and release notes. The check lists both open and closed issues and uses a version
marker to notify only once per release. Notification jobs are serialized to avoid duplicate
check/create races; an unchanged or already acknowledged version produces no new notification.

Only notification/reporting jobs have `issues: write`; CVE scanner jobs keep read-only permissions.
An update-check failure is visible as a failed job and an incomplete update-check artifact; it
does not prevent the independent CVE job from running. Findings and scanner updates therefore
cannot mask each other's failures. `security-tool-updates.json` records the version comparison
and issue URL. GitHub issue subscriptions/watch settings determine email and app delivery.

Review a new version in a PR that updates `.github/trivy-version`, run regression tests and the Security
workflow, then close its notification issue. The vulnerability database continues to update on
every scan; routine DB refreshes do not create tool-update issues. Prereleases are not notified.

To inspect a version comparison locally without creating an issue:

```bash
python3 scripts/security_tool_updates.py --repository pletorco/node-metrics-agent \
  --current-version 0.72.0 --output /tmp/trivy-update-check.json --dry-run
```

## Regression checks

```bash
python3 -m unittest discover -s scripts/tests -v
```

The tests cover omitted inventory scopes and packages, formatter version mismatch, vulnerable
fixtures, GHSA-only Gradle advisories, stale/missing DB data, scanner/source failures, malformed
reports and repeated scans in one working directory. The normal PR CI runs these offline tests.
CI also runs the separate network-backed dependency gate described below.

## Coverage boundaries

This inventories resolved Maven artifacts, not arbitrary cached JARs. It does not claim coverage
of the host OS/JDK, all libraries bundled within the Gradle distribution, lazily downloaded Sonar
analyzers, shaded libraries hidden inside plugins, or vendored JavaScript inside GitHub Actions.
Gradle distribution vulnerabilities are covered by vendor advisories rather than by pretending
the resolved Maven classpath contains Gradle itself. A clean report means no matching known
advisories at the recorded time; it is not proof that code is unexploitable.

## PR and release security gates

The CI job **`dependency-security`** runs on every PR, including Dependabot/forks and docs-only
changes, and on pushes to main. An unconditional, stable check name avoids skipped path-filter
checks leaving a required status pending. On PRs it scans the candidate merge commit; it never
substitutes the current main inventory for the candidate's dependencies.

`python3 scripts/security_gate.py --context pr --expected-commit "$(git rev-parse HEAD)"`
resolves the complete inventory and runtime SBOM, invokes the same scanner as daily monitoring,
and applies `.github/security-monitoring.json`. `gate.json` records the commit, runtime/tooling
results separately, DB/scanner metadata and policy/inventory/SBOM hashes. A dependency present in
both deployment and tooling scopes is classified as runtime and deduplicated. Gradle vendor
advisories are tooling findings. HIGH, CRITICAL and UNKNOWN severities block both categories;
LOW/MEDIUM remain visible in reports and daily monitoring. The policy can add blocking levels
but cannot remove this baseline. Unknown severity requires review, rather than silently passing.

Reviewed exceptions use the existing exact advisory/package/installed-version policy and its
30-day maximum duration. `target: main` applies to candidate PRs and main monitoring;
`target: release` applies to release candidates and published release monitoring. Exceptions remain
visible in `gate.json`; expiry and severity escalation reactivate blocking. Changing a threshold,
exception, scanner, workflow or verification script requires maintainer review before merge.
The gate cannot override an unavailable source/scanner, missing scope/package or stale DB.

PR jobs use `pull_request`, never `pull_request_target`. Their only repository permission is
`contents: read`; no repository secrets are passed. Checkout does not persist credentials, and
the inventory subprocess receives no `GH_TOKEN` or `GITHUB_TOKEN`. The scanner uses the read-only
token solely for public advisory API requests. Diagnostic artifacts explicitly include inventory,
SBOMs, raw reports and decisions, not caches, environment dumps or credential files. They are
uploaded on failure and retained 30 days. Fork workflows may still require the normal maintainer
approval to execute.

### Release publication boundary

The Release workflow separates **prepare** (contents read) from **release** (contents write).
Prepare verifies the version/release notes, runs tests/lint/build, scans that commit, runs the
Java agent smoke test and stages a `release-candidate` artifact only after a passing gate.
`release` has `needs: prepare`; failure or cancellation cannot reach publication. It downloads
that same immutable run artifact and verifies:

- a passing release-context decision for the exact tag commit;
- a scan no older than two hours and a still-fresh DB;
- the policy hash, candidate file allowlist and every SHA-256 checksum;
- identical SBOM bytes inside the built JAR, the scanned runtime BOM and the published BOM.

Only then can the publishing action upload JAR/checksums/SBOM. It does not rebuild the artifact.
The publication job never executes Gradle and is the only normal job with release write access.
Optional provenance uses the same checked candidate. An old artifact or a retried publication
with stale evidence must rerun prepare to obtain a fresh scan.

Manual Release dispatch accepts `candidate_tag` to exercise preparation and staging without
creating a tag or release. Publication is restricted to actual tag push events, so a successful
manual validation never publishes. Release tags must be created from commits containing this
workflow; tagging historical commits uses their historical workflow and is outside this control.

### Required merge check

The active main-branch security ruleset requires GitHub Actions' `dependency-security` check and
an up-to-date candidate. It has no bypass actors and is separate from the existing deletion and
force-push protection. The deployment of that ruleset is recorded in the implementation PR.
Keep the job name stable. If it changes, first run the new check, update the required context and
then remove the old context. No path filters or `continue-on-error` belong on this gate.

Maintainers can inspect enforcement with:

```bash
gh api repos/pletorco/node-metrics-agent/rules/branches/main
```

To reproduce after repository migration, add an active branch ruleset for `refs/heads/main`,
choose required status `dependency-security` from the GitHub Actions app, enable strict/up-to-date
checks and leave bypass actors empty. Keep any other protections. Rulesets constrain status
results, not the meaning of repository code: maintainers must review changes to security policy,
workflow and scanner scripts, and repository admins can change rulesets. For incident rollback,
remove only the dedicated security ruleset before removing/renaming its required job.

## Daily monitoring and incident ownership

`Security` runs every day at **02:23 UTC / 11:23 KST**, and supports manual dispatch.
Two independent jobs scan:

- **main**: a fresh checkout of main's complete resolved inventory, using the scanner above.
- **release**: the latest published stable release's actual `bom.json` asset. Its GitHub asset
  SHA-256 digest must match the downloaded bytes. The tag is resolved to a commit; tag, commit,
  asset ID, digest and publication time remain separate from main's identity. No old release
  source is built or executed. The security policy currently supports the latest released minor
  line, so the latest stable patch release represents that line's supported deployment baseline.

Both validate DB freshness and package coverage. A release without a published SBOM, or an API,
scanner, build or download failure, is **incomplete**. Failure in either target does not skip the
other. Evidence is retained for 30 days in separate `security-monitor-main` and
`security-monitor-release` artifacts, even on failure. Monitoring does not modify releases.

The reporting job always runs, including when a scanner fails before producing an artifact.
It maintains one `security: daily dependency monitoring` issue with separate target state and
last successful complete scan timestamps. A complete scan can contain vulnerabilities: freshness
means the check completed, not that the dependency is clean. Incomplete evidence preserves the
last findings and successful timestamp; it cannot mark a finding resolved.

The first run creates the tracking issue. Later healthy, unchanged runs only update its body.
Comments notify new findings, severity changes, resolutions, new/repeated-error changes, recovery,
and scanner-version-check failures. Duplicate runtime/tooling occurrences are combined and their
scopes retained. Open and closed tracking issues are reused; a new alert reopens a closed issue.
Only bot or repository collaborator issue state is trusted. Reporting and watchdog writers share
a concurrency group. If posting fails, state is not acknowledged; a failure after a successful
comment but before state persistence may repeat that comment on retry rather than lose an alert.

The **pletorco repository maintainers** own triage. `.github/security-monitoring.json` defines:

| Severity | Triage within |
| --- | --- |
| CRITICAL | 24 hours |
| HIGH / UNKNOWN | 72 hours |
| MEDIUM | 7 days |
| LOW | 14 days |

Triage means verify applicability, exposure and fix availability, assign an owner, and record the
fix or mitigation in a linked issue. These are response targets, not promises that a patch exists.
GitHub Watch/issue subscription settings determine email and app delivery. Dependency updates are
reviewed in PRs and never merged automatically by this monitor.

### Temporary exceptions

Exceptions are reviewed changes to the policy file's `exceptions` array. Every entry must specify
`target` (`main` or `release`), exact `advisory`, `package`, installed `version`, maximum accepted
`severity`, `owner`, `reason`, tracking `issue` URL, and UTC `createdAt` / `expiresAt`. The duration
must be positive and no more than 30 days. No wildcard exceptions are accepted. Excepted findings
remain visible in state/reports. Expiration or a severity increase beyond the accepted level
reactivates the alert. Exceptions cannot suppress scanner failures or stale scans. Raw scanner
jobs remain red for vulnerable reports, even if the reporting policy temporarily accepts them.

### Missed scans and independent watchdog

`Security watchdog` runs every six hours at minute 41. It checks both last-success timestamps
against a **36-hour** limit and reads the Security workflow's enablement state. Missing initial
evidence, delayed/missed runs, repeated scan failures and disabled Security workflows create a
deduplicated comment in the same issue. Recovery is also notified. A stale condition becomes
visible on the next watchdog run (normally within six hours of crossing the limit); neither
schedule is an exact delivery guarantee.

GitHub can [delay scheduled workflows](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule)
and [disable inactive public repository schedules](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/disable-and-enable-workflows).
A watchdog hosted on GitHub Actions cannot detect its own disablement, an Actions-wide shutdown
or a GitHub outage while it is unable to execute. For coverage of those conditions, an external
scheduler can run the same watchdog using a fine-grained token with repository contents/actions
read and issues write permissions:

```bash
python3 scripts/security_monitor.py watchdog --repository pletorco/node-metrics-agent
```

The external scheduler must alert on nonzero exit, including API failures, using its own delivery
channel. It must also monitor its own execution and avoid overlapping issue writers. No external
service is configured by this repository change; maintainers must provision it if they require
coverage when all GitHub schedules are disabled.

### Validation without notifications

Manually dispatch Security with `notify=false` to scan real main/release evidence and calculate
transitions without changing issues. The watchdog has the same dispatch input. Locally:

```bash
python3 scripts/security_release_scan.py --target release \
  --repository pletorco/node-metrics-agent --run-id local --output-dir monitor-reports/release
python3 scripts/security_monitor.py report --repository pletorco/node-metrics-agent \
  --run-id local --reports monitor-reports --dry-run
python3 scripts/security_monitor.py watchdog --repository pletorco/node-metrics-agent --dry-run
```

A missing main report in that partial local example is correctly incomplete. All commands return
nonzero for findings or incomplete evidence; dry run only disables writes. Offline regression tests
cover notification transitions, failures preserving prior findings, stale/missing/disabled runs,
recovery, closed-issue reuse, exception expiry and published-asset identity/digest validation.
