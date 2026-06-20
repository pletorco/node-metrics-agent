# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Documentation

- Clarified that the agent registers JMX MBeans and does not expose an HTTP metrics endpoint by
  itself.
- Added Prometheus JMX exporter usage guidance.

## [0.8.0] - 2026-06-20

### Changed

- Reintroduced the project as `node-metrics-agent` under Pletor Co., Ltd.
- Changed Java package namespace to `co.pletor.nodemetrics`.
- Changed Maven group to `co.pletor`.
- Changed JMX domains to `co.pletor.node`, `co.pletor.cgroup`, `co.pletor.proc`, and `co.pletor.agent`.
- Changed Prometheus JMX exporter example metric names to the `pletor_*` prefix.
- Reset the public project version to `0.8.0`.
- Replaced legacy CI/release configuration with GitHub Actions.
- Added Apache-2.0 licensing and public contributor/security documentation.

### Preserved

- Fail-open JVM agent startup behavior.
- Bounded async refresh engine with `NORMAL`, `DEGRADED`, and `BYPASS` modes.
- Runtime filesystem metric config reload.
- Agent self-observability MBeans.
- CLI config generation commands.
