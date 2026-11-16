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

Gradle is checked separately against its repository security advisories, including GHSA records
without CVE IDs. The range parser supports stable numeric versions, comma-separated comparator
intersections, inclusive bracket ranges, `to`/`through` ranges and the legacy alternative-range
formats published by Gradle. Multiple upper bounds for legacy maintenance lines are interpreted
as a conservative union. Unknown formats require review and fail the scan.

## Regression checks

```bash
python3 -m unittest discover -s scripts/tests -v
```

The tests cover omitted inventory scopes and packages, formatter version mismatch, vulnerable
fixtures, GHSA-only Gradle advisories, stale/missing DB data, scanner/source failures, malformed
reports and repeated scans in one working directory. The normal PR CI runs these offline tests.
It does not introduce the separate network-backed PR/release gate tracked in issue #58.

## Coverage boundaries

This inventories resolved Maven artifacts, not arbitrary cached JARs. It does not claim coverage
of the host OS/JDK, all libraries bundled within the Gradle distribution, lazily downloaded Sonar
analyzers, shaded libraries hidden inside plugins, or vendored JavaScript inside GitHub Actions.
Gradle distribution vulnerabilities are covered by vendor advisories rather than by pretending
the resolved Maven classpath contains Gradle itself. A clean report means no matching known
advisories at the recorded time; it is not proof that code is unexploitable.

The existing weekly/manual schedule is retained here. Daily release monitoring and notification
state are tracked in #57, and dependency PR/release blocking policy in #58.
