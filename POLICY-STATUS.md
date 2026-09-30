# Policy Status

Date: 2026-10-01
Target: `node-metrics-agent` `0.10.0`

## Module Classification

- Class: Telemetry module
- Company namespace: Pletor Co., Ltd. / `kr.pletor.nodemetrics`
- Host entry points:
  - `kr.pletor.nodemetrics.agent.AgentLauncher.premain(...)`: no static state; starts one daemon
    thread and returns. The actual initialization (`MetricsAgent.premain(...)`) runs on that thread.
  - JMX getter paths in metric MBeans (return cached values; no I/O when the refresh engine runs)
  - Agent daemon threads: initialization, refresh dispatcher and workers, filesystem probes, and the
    `ConfigReloader` watcher

## Current Status

- Fail-open startup and metric refresh behavior is preserved.
- Nothing the agent needs to load or run can stop the application: the entry class has no static
  state, initialization is asynchronous and split into independent steps, and every agent thread
  has its own uncaught-exception handler, so errors never reach the application's default handler.
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

- `./gradlew test`
- `./gradlew checkstyleMain checkstyleTest`
- `./gradlew shadowJar`
- optional: `./gradlew trivyScanAll`

Security scan tasks require a local `trivy` binary or the GitHub security workflow.
