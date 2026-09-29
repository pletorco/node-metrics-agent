# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Changed

- `CpuMetrics` reads only the aggregate line of `/proc/stat` instead of every line, and resolves the
  location of the cgroup `cpu.stat` once a minute (or after a read failure) instead of on every
  poll. About 16% less time per poll on a 4-core host; the saving grows with the CPU count.

### Fixed

- `MemoryLimitBytes` is now the effective cgroup limit: the tightest finite limit of the cgroup and
  its ancestors. Previously a container without its own limit inside a limited Kubernetes pod (or
  with a limit larger than the pod's) reported `-1` / an unreachable value, so limit-based alerts
  and ratios were wrong.
- Applying a configuration no longer blocks on an unresponsive filesystem. The `stat` calls made
  for configured paths run on bounded probe threads with a 2 s per-call and 5 s per-apply budget;
  a path that does not answer is still registered (and then reported as a stuck task) instead of
  freezing the caller. At startup the caller is the application's main thread, so a dead NFS mount
  listed in `fsmetrics_paths` could previously delay JVM startup indefinitely.

### Build and CI

- Compile with `--release 11` so the Java 11 API is enforced, not just Java 11 bytecode. This
  immediately caught `Stream.toList()` (Java 16) in tests.
- CI runs the tests on Java 11 and 17 (`-PtestJavaVersion`) in addition to the Java 21 build, and
  smoke-tests the shaded jar as a `-javaagent` on Java 11, 17 and 21 (`scripts/smoke-test.sh`). The
  smoke test covers the CLI, MBean registration, refresh pipeline health and that the agent creates
  no files in the application's working directory.
- Checkstyle warnings are capped by a ratchet (`maxWarnings`): new warnings fail the build.
- `shadowJar` no longer depends on `test`; CI and the release workflow run `check` explicitly.
- Release workflow verifies the tag before building, runs `check` and the smoke test, and publishes
  a `SHA256SUMS` file next to the jar and SBOM.
- CI validates the Gradle wrapper, cancels superseded runs, and jobs have timeouts.
- Added Dependabot for GitHub Actions and Gradle.

### Added

- Cumulative counters so rates can be computed over any time range with Prometheus `rate()`:
  `SystemCpuTotalTicks`, `SystemCpuIoWaitTicks`, `SystemCpuStealTicks`,
  `CgroupCpuThrottledPeriodsTotal`, `CgroupCpuThrottledTimeNanosTotal`, `CgroupCpuUsageNanosTotal`
  on `co.pletor.node:type=CpuMetrics`, and `DiskReadBytesTotal`, `DiskWriteBytesTotal`,
  `NetRxBytesTotal`, `NetTxBytesTotal` on `co.pletor.node:type=IoRates`. The existing ratio and
  per-second gauges are unchanged, but they only describe the last ~500 ms window.

### Fixed

- `CgroupCpuThrottledCount` is a per-window delta but was declared and exported as a counter. It is
  now a gauge (metric type only; the value is unchanged).

### Added

- `MemoryWorkingSetBytes` on `co.pletor.cgroup:type=MemMetrics` (exported as
  `pletor_cgroup_memmetrics_memoryworkingsetbytes`): cgroup usage minus inactive file cache, from
  `memory.stat` (`inactive_file` on v2, `total_inactive_file` on v1). `MemoryUsageBytes` includes
  reclaimable page cache and overstates memory pressure for page-cache-heavy workloads.

### Added

- Filesystem metrics now refresh on a separate background lane (3 worker threads) with at most one
  queued or running instance per task. Previously a single worker served every metric, so one
  blocked `statvfs` (dead NFS mount) froze all metrics and pushed the engine into `BYPASS`.
- `StuckTaskCount` and `StuckTasks` on `co.pletor.agent:type=Observability` report tasks whose
  refresh has been running for more than 30 seconds.

### Changed

- Overload modes are derived from the critical lane only. Skipped runs of a hung background task
  are counted in `DroppedCount` (once per refresh interval) but do not change the mode.

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
