# Contributing

Thanks for helping improve `node-metrics-agent`.

This project runs inside host JVM processes, so contributions should preserve host safety first:

- metric collection must be fail-open
- host/JMX read paths must stay bounded and non-blocking
- queues, maps, caches, and thread pools must have hard limits
- repeated internal logs must be throttled
- new metrics should avoid high-cardinality labels
- config parsing must be defensive and keep the last known-good runtime state on reload failure

## Development Setup

Use JDK 21 for builds. Code is compiled with `--release 11`, so it can only use the Java 11 API and
the produced bytecode targets Java 11 (the agent runs inside Kafka brokers and other Java 11+ JVMs).

```bash
./gradlew test
./gradlew checkstyleMain checkstyleTest
./gradlew jacocoTestReport
```

Run the tests on an older runtime (the JDK must be installed or discoverable by Gradle):

```bash
./gradlew test -PtestJavaVersion=11
```

Smoke-test the packaged jar as a real `-javaagent` (CLI, MBean registration, refresh pipeline, no
files created in the application's working directory):

```bash
./gradlew shadowJar
scripts/smoke-test.sh build/libs/node-metrics-agent-*-all.jar
JAVA_BIN=/path/to/jdk11/bin/java scripts/smoke-test.sh build/libs/node-metrics-agent-*-all.jar
```

To scrape the agent through a real Prometheus JMX exporter (download the exporter jar from its
[releases](https://github.com/prometheus/jmx_exporter/releases); CI runs 1.0.1 and 1.6.0):

```bash
scripts/exporter-scrape-test.sh build/libs/node-metrics-agent-*-all.jar /path/to/jmx_prometheus_javaagent.jar
```

`shadowJar` does not run the tests; run `./gradlew check` (tests, style and critical coverage) before
releasing. The release workflow does both.

### Failure and coverage regressions

Use latches or observable conditions to establish async readiness. `AsyncTestSupport.await` fails
on timeout/interruption; timing out must never allow a benchmark or assertion to proceed. Timeout
limits are deadlock guards, not host-performance budgets. The combined failure test exercises real
config parsing/reload and concurrent single/bulk JMX reads with blocked critical/background
collectors, bounded worker/queue counts, host progress, later recovery and joined worker cleanup.
Bulk requests use the same JMX API as an exporter; CI separately exercises real exporter versions.

`jacocoTestCoverageVerification` runs in `check`, PR CI and release preparation. It checks per-class
line/branch floors for critical code:

| Class | Lines | Branches |
| --- | --- | --- |
| ConfigLoader | 95% | 90% |
| ConfigReloader | 80% | 75% |
| MetricsRefreshEngine | 90% | 80% |
| CpuMetrics | 85% | 75% |
| LinuxProcFs | 85% | 75% |
| CgroupCpuLimit | 90% | 90% |

These floors protect the observed Linux/JDK 21 baseline with margin for platform branches. They
complement behavioral tests; they are not a target to reach by invoking private constructors or
mirroring implementation. Review changes to the floors alongside evidence for the affected
behavior. CI uploads XML/HTML coverage reports even on failure. The required `test` status on main
enforces this check alongside `dependency-security`; the older JDK jobs run the complete suite
on Java 11 and 17.

For a focused local run (full coverage verification requires the complete suite):

```bash
./gradlew test --tests '*ConfigBoundaryTest' --tests '*CombinedFailureRecoveryTest' \
  --tests '*CpuProcRecoveryTest' --tests '*AsyncTestSupportTest'
./gradlew check
```

### Formatting

Java sources are formatted with google-java-format through Spotless. `./gradlew check` and CI run
`spotlessCheck`; fix formatting with:

```bash
./gradlew spotlessApply
```

`.git-blame-ignore-revs` lists the one-off reformatting commit; use
`git config blame.ignoreRevsFile .git-blame-ignore-revs` to skip it in `git blame`.

### Checkstyle

The code base is checkstyle-clean (Google style, `config/checkstyle/checkstyle.xml`) and any warning
fails the build. `MBean` in names such as `CpuMetricsMBean` is an allowed abbreviation because the
JMX Standard MBean convention requires it.

## Releasing

Before tagging, bump the version (`build.gradle.kts`, the version examples in the docs and
`AgentMetadataTest`) and turn the `[Unreleased]` entries of `CHANGELOG.md` into a `## [X.Y.Z] - date`
section: it is published as the GitHub release notes (`scripts/release-notes.sh X.Y.Z` prints it, and
CI fails if the project version has no section).

Push a tag `vX.Y.Z` that matches the project version. The release workflow verifies the tag, takes
the release notes from `CHANGELOG.md`, runs `check`, builds the shaded jar, smoke-tests it and
publishes the jar, `SHA256SUMS` and the SBOM.

Optional signed build provenance: set the repository variable `ATTEST_RELEASE_ARTIFACTS` to `true`
(requires repository support for attestations). Consumers can then verify a download with
`gh attestation verify <jar> --repo <owner>/<repo>`.

## Dependency Security Checks

Run `./gradlew trivyScanAll` before dependency changes are proposed. This resolves the full runtime,
test and build-tool inventory and checks Gradle vendor advisories. Run the offline scanner regression
tests with `python3 -m unittest discover -s scripts/tests -v`. See
[Dependency security checks](docs/SECURITY-CHECKS.md) for requirements, output and limitations.

Every PR runs the required `dependency-security` check against its candidate merge commit.
Dependency, wrapper, plugin and tooling changes must pass it before merging; source-only changes
receive the same stable check. Runtime/tooling HIGH, CRITICAL and UNKNOWN findings block, as do
incomplete checks. Request a reviewed, exact, expiring exception in `.github/security-monitoring.json`
only with an owner, mitigation and tracking issue. Review security policy/workflow/scanner changes
explicitly; passing CI does not replace that review. Trivy's shared pin is `.github/trivy-version`.

## Pull Requests

Please include:

- summary of the change
- affected module/path type
- failure behavior
- tests run
- rollback notes for operationally sensitive changes

For telemetry pipeline, config reload, filesystem discovery, or JMX surface changes, include focused
tests for failure isolation and saturation behavior.
