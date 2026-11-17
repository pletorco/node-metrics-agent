# Policy Status

Date: 2026-11-17
Target: `node-metrics-agent` `0.11.0`

## Module Classification

- Class: Telemetry module
- Company namespace: Pletor Co., Ltd. / `kr.pletor.nodemetrics`
- Host entry points:
  - `kr.pletor.nodemetrics.agent.AgentLauncher.premain(...)`: no static state; starts one daemon
    thread and returns. The actual initialization (`MetricsAgent.premain(...)`) runs on that thread.
  - JMX getter paths in metric MBeans (return cached values; read refresh disabled before publication)
  - Agent daemon threads: initialization, refresh dispatcher and workers, filesystem probes, and the
    `ConfigReloader` watcher

## Current Status

- Fail-open startup and metric refresh behavior is preserved.
- After the JVM enters the launcher, guarded task construction/start failures are contained.
  Initialization is asynchronous and attempted once; partial refresh startup rolls back and remains
  explicitly disabled. Agent threads have private error handlers and do not inherit host thread
  locals. JVM rejection of the manifest/entry class before entry is outside these guards.
- The agent does not initialize JVM-wide singletons (`java.util.logging` `LogManager`, platform
  MBeanServer) ahead of the application; see `RUNBOOK.md`, "Isolation From The Application".
- Metrics are refreshed by background threads at a configurable interval
  (`refresh_interval_seconds`); a scrape only reads stored values.
- `fsmetrics_max_partitions` has a hard upper limit (256).
- The refresh pipeline uses a bounded queue and daemon worker threads.
- Runtime modes are exposed through `kr.pletor.agent:type=TelemetryMode`.
- Pipeline counters and staleness signals are exposed through `kr.pletor.agent:type=Observability`.
- Prometheus JMX exporter example rules use the `pletor_*` metric prefix.
- Config reload keeps the previous working configuration on failure.
- Repeated internal logs use throttling with a bounded key space.
- Public repository metadata now includes Apache-2.0 license, contributor guide, security policy,
  issue templates, pull request template, and GitHub Actions workflows.

## Verification Expectations

Before release:

- `./gradlew spotlessCheck check shadowJar cyclonedxBom`
- `python3 -m unittest discover -s scripts/tests -v`
- Packaged smoke on Java 11/17/21 and exporter integration checks
- Mandatory PR and release-context dependency security gates through GitHub Actions
- Release candidate commit/policy/checksum and packaged/published SBOM verification

Security scans require the reviewed Trivy binary and fresh databases. The release workflow publishes
only the exact checked candidate after its release-context gate passes; missing/incomplete evidence
blocks publication. See `docs/SECURITY-CHECKS.md`.
