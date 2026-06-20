# Policy Status

Date: 2026-06-20
Target: `node-metrics-agent` `0.8.0`

## Module Classification

- Class: Telemetry module
- Company namespace: Pletor Co., Ltd. / `co.pletor.nodemetrics`
- Host entry points:
  - `co.pletor.nodemetrics.agent.MetricsAgent.premain(...)`
  - JMX getter paths in metric MBeans
  - `ConfigReloader` watcher thread

## Current Status

- Fail-open startup and metric refresh behavior is preserved.
- The refresh pipeline uses a bounded queue and daemon worker threads.
- Runtime modes are exposed through `co.pletor.agent:type=TelemetryMode`.
- Pipeline counters and staleness signals are exposed through `co.pletor.agent:type=Observability`.
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
