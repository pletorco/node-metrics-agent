# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added

- `FailingTaskCount` and `FailingTasks` on `co.pletor.agent:type=Observability`, and a throttled
  WARNING log, so failed metric refreshes are visible. Previously beans absorbed read failures
  silently, so `ErrorCount`/`SinkFailureCount` stayed at 0 and staleness was reset by failed polls.

### Changed

- Refresh tasks have individual intervals: file descriptors every 5 s, filesystem and OS runtime
  every 10 s, OS info every 5 min; other metrics keep the 500 ms cadence. This reduces the cost of
  counting file descriptors on brokers with very many open files.
- `MaxTaskStalenessMs` now means "milliseconds behind schedule" (time since last success minus the
  task's interval), so healthy tasks stay near 0 regardless of interval. A task that never
  succeeds is now counted from its registration time instead of being ignored.
- Task success/failure history is preserved across configuration reloads.
- Metric beans share a common `AbstractRefreshingMetric` base class instead of eight copies of the
  same refresh/poll boilerplate.

### Fixed

- Network throughput is no longer inflated on container hosts: only interfaces backed by a real
  device are summed (bridges, veth pairs, VLANs and bond masters are skipped). Inside a container
  network namespace, where the only interface is virtual, all non-loopback interfaces are summed.
- Config reloader no longer re-parses and re-applies the configuration that was already applied at
  startup.
- Config file is read once per load, so the parsed content and its checksum always match.
- Unknown config keys (e.g. typos like `fsmetric_paths`) now log a warning instead of silently
  falling back to defaults.
- Timer-driven config reload checks back off exponentially after failures in watch mode too;
  file events still retry immediately.
- Refresh cadence uses a monotonic clock, so wall-clock jumps no longer block or hasten refreshes.
- `IoRates` no longer skips rate calculation forever when `System.nanoTime()` is zero or negative.
- `CgroupCpuThrottledRatio` returns 0 for idle windows instead of keeping the previous value.
- Refresh engine no longer stays in `DEGRADED` forever: intentional low-priority drops are not
  counted as overload, so the mode returns to `NORMAL` once the queue drains.
- Config watcher no longer creates the config directory in the host application's working
  directory; it polls until the directory appears.
- Filesystem MBeans are now registered for paths containing `, = : * ?` (ObjectName value quoting).
- `init-config` / `init-kafka-config` quote paths that YAML would otherwise misread (`#`, `: `, ...).

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
