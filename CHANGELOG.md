# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added

- Priority 3 metrics of the metrics roadmap:
  - `SystemCpuUserTicks`, `SystemCpuNiceTicks`, `SystemCpuSystemTicks`, `SystemCpuIdleTicks`,
    `SystemCpuIrqTicks`, `SystemCpuSoftIrqTicks` on `CpuMetrics`: the remaining cumulative modes of
    the aggregate `/proc/stat` line, read at no extra cost.
  - `SystemOomKillTotal` on `kr.pletor.node:type=MemMetrics` (`/proc/vmstat`, host-wide) and
    `MemoryOomKillTotal` on `kr.pletor.cgroup:type=MemMetrics` (`memory.events` / `memory.oom_control`).
    A kill of the JVM itself is not reliably visible on Kubernetes; see `RUNBOOK.md`.
  - `SystemOpenFileHandles` and `SystemMaxFileHandles` on `FdMetrics` (`/proc/sys/fs/file-nr`).
  - `kr.pletor.proc:type=MemoryMapMetrics`: `MemoryMapCount` (lines of `/proc/self/maps`, counted as
    raw bytes: about 32 ms at 60,000 mappings) and `MaxMemoryMapCount` (`vm.max_map_count`),
    refreshed once a minute in the low-priority lane.
- Disk operation and I/O time counters, `kr.pletor.node:type=DiskIoMetrics`, from
  `/proc/diskstats` and summed over the same leaf devices as the disk byte counters: completed
  reads and writes, time spent reading and writing, busy time (`io_ticks`), weighted busy time, I/O
  in flight and the number of devices. Rates give IOPS, latency, utilization and queue length.
- Network error and TCP health counters, `kr.pletor.node:type=NetworkMetrics`: receive/transmit
  errors and drops (same interfaces as the byte counters) and, from `/proc/net/snmp` and
  `/proc/net/netstat`, TCP segments sent, retransmitted and in error, failed connection attempts,
  established-connection resets, current connections, listen overflows and drops, and
  retransmission timeouts. Each source is read independently.
- JVM process figures, `kr.pletor.proc:type=ProcessMetrics`, from `/proc/self`: resident set size
  and its peak, anonymous, file and shared parts, swap, native thread count, and the bytes read
  from and written to storage. Context switches are not exposed because `/proc/self/status`
  reports them for the main thread only.
- `CgroupCpuLimitCores` on `kr.pletor.node:type=CpuMetrics`: the effective CPU limit of the
  container in cores (CFS quota over period; the tightest finite limit of the cgroup and its
  ancestors, like `MemoryLimitBytes`; `-1` when unlimited or unavailable). With
  `CgroupCpuUsageNanosTotal` it gives CPU use as a share of the limit. Read from `cpu.max` (v2) or
  `cpu.cfs_quota_us` / `cpu.cfs_period_us` (v1).
- Swap metrics. `kr.pletor.node:type=MemMetrics`: `SwapTotalBytes` (`0` without swap), `SwapUsedBytes`
  and the cumulative counters `SwapInPagesTotal` / `SwapOutPagesTotal` (from `/proc/vmstat`
  `pswpin` / `pswpout`; a missing `/proc/vmstat` is `-1`, not a failure).
  `kr.pletor.cgroup:type=MemMetrics`: `SwapUsageBytes` and `SwapLimitBytes` for the container's own
  cgroup (v2 `memory.swap.current` / `memory.swap.max`; v1 derived from `memsw`; `-1` when swap is
  not accounted). Exporter rules updated for the page counters.
- Pressure stall information (PSI) metrics: `kr.pletor.node:type=PressureMetrics` (from
  `/proc/pressure`, host-wide) and `kr.pletor.cgroup:type=PressureMetrics` (the container's own
  `cpu.pressure`, `memory.pressure` and `io.pressure`, cgroup v2). For CPU, memory and I/O, each
  with `some` and `full`: the kernel's 10-second average in percent (`*Avg10`) and the cumulative
  stalled time in microseconds (`*TotalMicros`, a counter for `rate()`). `-1` when PSI is
  unavailable. Exporter rules updated for the totals.
- `kr.pletor.cgroup:type=PidsMetrics`: `PidsCurrent` and `PidsLimit` from the `pids` controller
  (the tightest finite `pids.max` of the cgroup and its ancestors, with the count of that level;
  cgroup v1 reads the `pids` hierarchy next to `memory`). At the limit the JVM fails with "unable to
  create native thread".
- `MemoryMaxEventsTotal` (`max` in `memory.events`, `memory.failcnt` on v1) and
  `MemoryHighEventsTotal` (`high`, v2 only) on `kr.pletor.cgroup:type=MemMetrics`: the container
  hitting its memory limit, which precedes an OOM kill. Read from the file already used for
  `MemoryOomKillTotal`, so no extra read.
- `src/main/resources/prometheus_alerts_example.yml`: example Prometheus alerting rules for the
  agent's health, memory, CPU and I/O, network and resource limits. A test checks that every metric
  name in it is one the example exporter rules produce, and that every MBean attribute has a
  `@JmxMetricHint` and an exporter rule of the matching type.

### Changed

- **Breaking:** the JMX domains are now `kr.pletor.node`, `kr.pletor.cgroup`, `kr.pletor.proc` and
  `kr.pletor.agent` (were `co.pletor.*`), and the Java package and Maven group are `kr.pletor`
  (`kr.pletor.nodemetrics.agent.AgentLauncher` is the `Premain-Class`). The `-javaagent` jar path
  and the Prometheus series names (`pletor_*`) do not change, but an exporter configuration copied
  from an earlier version matches nothing and exports no agent metrics until its `co.pletor.`
  patterns become `kr.pletor.`: `sed -i 's/co\.pletor\./kr.pletor./g' pletor-node-metrics.yml`.
  Update any JMX client, dashboard or script that reads the MBeans directly in the same way.
- The regular metrics now refresh on 3 worker threads (was 1), and, like the filesystem lane, a
  task is never queued again while its previous run is still queued or running. Before, one hung
  `/proc` or cgroup read blocked every regular metric, and its queued copies then filled the queue
  until the engine dropped everything (`BYPASS`). Now a hung task occupies one worker, is skipped
  (`DroppedCount`) and shows in `StuckTasks`, and the overload mode is left to a genuinely full
  queue.

### Fixed

- With the Prometheus JMX exporter 1.x, a cumulative counter that reads `-1` (source unavailable:
  no PSI, no cgroup, an old kernel) typed as `COUNTER` makes the **whole scrape fail** (HTTP 500,
  "counters cannot have a negative value"; checked with 1.0.1 and 1.6.0), hiding every metric of the
  target. The agent now reports an unavailable counter as **absent** (`null` over JMX, skipped by
  the exporter; it appears once the source becomes available) instead of `-1`. This affects the 50
  cumulative counters of `CpuMetrics`, `IoRates`, `DiskIoMetrics`, `NetworkMetrics`,
  `PressureMetrics`, `ProcessMetrics`, `OsRuntimeMetrics` and the memory `MemMetrics` beans; gauges
  keep `-1`, and the agent's own `kr.pletor.agent` counters are unchanged. The example rules keep
  the counters typed `COUNTER`, so exporter 1.x exposes them as `<attribute>_total` exactly as
  before (series names do not change). An attribute whose value is `null` is also left out of the
  MBean's attribute list until it has a value, because exporter 1.6.0 fails the whole scrape with a
  `NullPointerException` on a `null` value in an MBean that a rule customizes with
  `attributesAsLabels`. **Note for JMX clients:** a counter attribute can be `null` or not listed.
  See "Unavailable values and the exporter" in `RUNBOOK.md`; a test rejects a counter getter that
  is not a boxed `Long`.
- CI scrapes the shaded jar through real JMX exporters (1.0.1 and 1.6.0, jars pinned by SHA-256)
  with `scripts/exporter-scrape-test.sh`, which requires HTTP 200, the agent's series, `_total`
  counters and no negative counter.

## [0.9.0] - 2026-09-29

Behavior changes to know before upgrading: MBeans appear about 0.5 s after `main()` starts (up to
5 s when no MBeanServer exists yet), `INFO` log lines appear about 10 s after startup, the ratio
gauges describe a 2 s window instead of 500 ms, and file descriptors are counted every 30 s.

### Added

- Setting `refresh_interval_seconds` (1-60, default 2): how often CPU, memory, cgroup memory and
  I/O rates are refreshed in the background.
- Cumulative counters so rates can be computed over any time range with Prometheus `rate()`:
  `SystemCpuTotalTicks`, `SystemCpuIoWaitTicks`, `SystemCpuStealTicks`,
  `CgroupCpuThrottledPeriodsTotal`, `CgroupCpuThrottledTimeNanosTotal`, `CgroupCpuUsageNanosTotal`
  on `co.pletor.node:type=CpuMetrics`, and `DiskReadBytesTotal`, `DiskWriteBytesTotal`,
  `NetRxBytesTotal`, `NetTxBytesTotal` on `co.pletor.node:type=IoRates`. The existing ratio and
  per-second gauges are unchanged, but they only describe the last refresh window (2 s by default).
- `MemoryWorkingSetBytes` on `co.pletor.cgroup:type=MemMetrics` (exported as
  `pletor_cgroup_memmetrics_memoryworkingsetbytes`): cgroup usage minus inactive file cache, from
  `memory.stat` (`inactive_file` on v2, `total_inactive_file` on v1). `MemoryUsageBytes` includes
  reclaimable page cache and overstates memory pressure for page-cache-heavy workloads.
- Filesystem metrics now refresh on a separate background lane (3 worker threads) with at most one
  queued or running instance per task. Previously a single worker served every metric, so one
  blocked `statvfs` (dead NFS mount) froze all metrics and pushed the engine into `BYPASS`.
- `StuckTaskCount` and `StuckTasks` on `co.pletor.agent:type=Observability` report tasks whose
  refresh has been running for more than 30 seconds.
- `FailingTaskCount` and `FailingTasks` on `co.pletor.agent:type=Observability`, and a throttled
  WARNING log, so failed metric refreshes are visible. Previously beans absorbed read failures
  silently, so `ErrorCount`/`SinkFailureCount` stayed at 0 and staleness was reset by failed polls.

### Changed

- Metrics are refreshed by background threads, never by a scrape. CPU, memory, cgroup memory and
  I/O rates were refreshed every 500 ms, about 30 times per 15 s scrape; they now follow
  `refresh_interval_seconds` (values are at most 2 s old by default). The ratio gauges (I/O wait,
  steal, cgroup throttling) and `*BytesPerSec` now describe the last 2 s window instead of 500 ms,
  which is less noisy; the counters are unchanged. Measured engine work over 20 s: 168 refreshes
  before, 44 with the default.
- Refresh tasks have individual intervals: file descriptors every 30 s (or `refresh_interval_seconds`
  if larger), filesystem and OS runtime every 10 s, OS info every 5 min. This reduces the cost of
  counting file descriptors on brokers with very many open files (about 1.3 ms at 5,000 and 9 ms at
  15,000 open descriptors per count).
- Overload modes are derived from the critical lane only. Skipped runs of a hung background task
  are counted in `DroppedCount` (once per refresh interval) but do not change the mode.
- `MaxTaskStalenessMs` now means "milliseconds behind schedule" (time since last success minus the
  task's interval), so healthy tasks stay near 0 regardless of interval. A task that never
  succeeds is now counted from its registration time instead of being ignored.
- Task success/failure history is preserved across configuration reloads.
- Metric beans share a common `AbstractRefreshingMetric` base class instead of eight copies of the
  same refresh/poll boilerplate.
- `CpuMetrics` reads only the aggregate line of `/proc/stat` instead of every line, and resolves the
  location of the cgroup `cpu.stat` once a minute (or after a read failure) instead of on every
  poll. About 16% less time per poll on a 4-core host; the saving grows with the CPU count.
- `fsmetrics_max_partitions` is capped at 256; larger values were accepted up to `Integer.MAX_VALUE`,
  so a typo could register an unbounded number of MBeans and refresh tasks. A larger value is now
  lowered to 256 with a warning.

### Fixed

- The agent can no longer take the application down or stall it at startup:
  - The `Premain-Class` is now `AgentLauncher`, a tiny class without static state that starts one
    daemon thread and returns. Previously a failure while initializing `MetricsAgent` (e.g. a
    missing class after the agent jar was replaced during a deployment) aborted the whole JVM
    (exit 134) before the application started, and the initialization ran on the application's main
    thread: about 370 ms versus about 57 ms now (10 ms without the agent). The MBeans now appear
    shortly after `main()` starts.
  - Agent threads had no uncaught-exception handler, so an `Error` escaping one of them was passed
    to the application's default handler, which many applications use to exit the JVM. Every agent
    thread now has its own handler, and the dispatcher, workers and config watcher survive any
    `Throwable` (the watcher restarts itself with backoff).
  - Startup is split into independent steps; one metric that fails to initialize is skipped and the
    rest still start.
  - The agent no longer creates the JVM-wide `java.util.logging` `LogManager` or the platform
    MBeanServer ahead of the application. An application that sets `java.util.logging.manager` or
    `javax.management.builder.initial` from `main()` had those settings silently ignored when the
    agent got there first (reproduced with a custom `LogManager`). Records below `WARNING` are now
    held back for 10 s (keeping their time) and `WARNING`+ are published at once; when no
    MBeanServer exists yet the agent waits up to 5 s for the application to create it.
- `MemoryLimitBytes` is now the effective cgroup limit: the tightest finite limit of the cgroup and
  its ancestors. Previously a container without its own limit inside a limited Kubernetes pod (or
  with a limit larger than the pod's) reported `-1` / an unreachable value, so limit-based alerts
  and ratios were wrong.
- Applying a configuration no longer blocks on an unresponsive filesystem. The `stat` calls made
  for configured paths run on bounded probe threads with a 2 s per-call and 5 s per-apply budget;
  a path that does not answer is still registered (and then reported as a stuck task) instead of
  freezing the caller. At startup the caller is the application's main thread, so a dead NFS mount
  listed in `fsmetrics_paths` could previously delay JVM startup indefinitely.
- `CgroupCpuThrottledCount` is a per-window delta but was declared and exported as a counter. It is
  now a gauge (metric type only; the value is unchanged).
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

### Build and CI

- Upgraded `org.cyclonedx.bom` from 1.10.0 to 3.4.1 and moved the SBOM configuration to its new API:
  `includeConfigs` is now set on `cyclonedxDirectBom`, `cyclonedxBom` aggregates it and writes
  `build/reports/bom.json` through `jsonOutput`, and `schemaVersion` uses `org.cyclonedx.Version`.
  Left at the plugin defaults, the SBOM would have listed 56 components (test and build tooling)
  instead of the one runtime dependency that ships in the jar. The SBOM is otherwise equivalent
  (same component, CycloneDX 1.5, same dependency graph); the root component's purl and `bom-ref`
  now use the plugin's `?project_path=` form, and license texts are no longer embedded (SPDX ids
  are kept), which shrinks it from 16.8 KB to 3.3 KB.
- Source is formatted with google-java-format through Spotless (`spotlessCheck` runs in `check` and
  CI, `spotlessApply` fixes). The one-off reformatting commit is listed in `.git-blame-ignore-revs`.
- Checkstyle warnings reduced from 321 (main) / 602 (test) to 0 and capped by a ratchet
  (`maxWarnings = 0`), so any new warning fails the build. `MBean` is an allowed abbreviation in
  names (required by the JMX Standard MBean convention); the fixes are Javadoc, blank lines,
  `final` locals and a few private or test-only renames. No public API changed.
- Optional signed build provenance for release jars, in a separate job that only runs when the
  repository variable `ATTEST_RELEASE_ARTIFACTS` is `true` (see `CONTRIBUTING.md`).
- Compile with `--release 11` so the Java 11 API is enforced, not just Java 11 bytecode. This
  immediately caught `Stream.toList()` (Java 16) in tests.
- CI runs the tests on Java 11 and 17 (`-PtestJavaVersion`) in addition to the Java 21 build, and
  smoke-tests the shaded jar as a `-javaagent` on Java 11, 17 and 21 (`scripts/smoke-test.sh`). The
  smoke test covers the CLI, MBean registration, refresh pipeline health, that the agent creates
  no files in the application's working directory, that a broken agent jar cannot stop the
  application from starting, and that the agent does not initialize the `LogManager` or the
  platform MBeanServer before the application does.
- `shadowJar` no longer depends on `test`; CI and the release workflow run `check` explicitly.
- Release workflow verifies the tag before building, runs `check` and the smoke test, and publishes
  a `SHA256SUMS` file next to the jar and SBOM.
- CI validates the Gradle wrapper, cancels superseded runs, and jobs have timeouts.
- Added Dependabot for GitHub Actions and Gradle.

### Documentation

- Clarified that the agent registers JMX MBeans and does not expose an HTTP metrics endpoint by
  itself.
- Added Prometheus JMX exporter usage guidance.
- `RUNBOOK.md` documents how the agent is isolated from the application, the refresh intervals and
  the startup timing of MBeans and log lines.

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
