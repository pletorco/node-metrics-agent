# node-metrics-agent Runbook

This runbook covers production deployment and first-response checks for `node-metrics-agent`.

The agent registers metrics as JMX MBeans. It does not expose an HTTP scrape endpoint by itself.
For Prometheus, run the
[Prometheus JMX exporter](https://github.com/prometheus/jmx_exporter) in the same JVM or use another
JMX-to-Prometheus bridge.

## Deployment

1. Build or download the shaded JAR.
2. Prepare `node-metrics.yml`.
3. Add the JVM option:

```bash
-javaagent:/opt/pletor/node-metrics-agent-0.9.0-all.jar=/opt/pletor/node-metrics.yml
```

4. For Prometheus scraping, also attach the
   [Prometheus JMX exporter Java agent](https://prometheus.github.io/jmx_exporter/deployment/java-agent):

```bash
-javaagent:/opt/jmx-exporter/jmx_prometheus_javaagent.jar=9404:/opt/jmx-exporter/pletor-node-metrics.yml
```

Use `src/main/resources/jmx_exporter_rules_example.yml` as the starting exporter configuration.
It maps JMX attributes to `pletor_*` Prometheus metrics.

5. Restart the JVM.
6. Check startup logs for:

```text
[node-metrics-agent] started
```

Agent startup is fail-open. If initialization fails, the host application continues without agent
metrics.

## Validation

Confirm these MBeans exist:

- `co.pletor.node:type=CpuMetrics`
- `co.pletor.node:type=MemMetrics`
- `co.pletor.cgroup:type=MemMetrics`
- `co.pletor.node:type=PressureMetrics`
- `co.pletor.cgroup:type=PressureMetrics`
- `co.pletor.proc:type=FdMetrics`
- `co.pletor.node:type=IoRates`
- `co.pletor.node:type=OsInfoMetrics`
- `co.pletor.node:type=OsRuntimeMetrics`
- `co.pletor.agent:type=TelemetryMode`
- `co.pletor.agent:type=Observability`
- `co.pletor.node:type=FsMetrics,path=<configured path>`

`TelemetryMode.Mode` should normally be `NORMAL`.

If the JMX exporter is attached, confirm the scrape endpoint responds:

```bash
curl -s http://localhost:9404/metrics | grep '^pletor_' | head
```

## Isolation From The Application

The agent is designed never to be on the application's hot path and never to let its own failures
reach the application:

- It does not instrument or transform application classes, and application threads never run agent
  code. JMX attribute reads return cached values.
- `-javaagent` startup is asynchronous. The entry point (`AgentLauncher`) only starts one daemon
  thread and returns, adding roughly 50 ms to JVM startup (measured: about 10 ms without the
  agent, about 57 ms with it). The MBeans therefore appear a short moment after `main()` starts, so
  do not treat their absence in the first second as a failure.
- The entry class has no static state. If anything the agent needs cannot be loaded (for example
  the agent jar was replaced or truncated during a deployment), one line is printed to stderr and
  the application starts normally.
- Every agent thread has its own uncaught-exception handler and its loops survive any `Throwable`,
  so errors on agent threads never reach the application's default uncaught-exception handler.
- Startup is split into independent steps: a metric that cannot start (for example a missing JDK
  class on an unusual JVM) is skipped and logged as `startup step failed`, and the other metrics
  still start.
- The agent does not create JVM-wide singletons ahead of the application. An application may set
  `java.util.logging.manager` or `javax.management.builder.initial` from `main()`; if the agent
  had initialized `java.util.logging` or the platform MBeanServer first, the JVM would silently
  ignore those settings.
  - Logging: records below `WARNING` (startup information) are held in a small in-memory queue
    with their original time and published 10 s after startup, or sooner when a `WARNING` occurs.
    `WARNING` and above are published immediately. So `INFO` lines such as `config applied` show
    up about 10 s late, and a JVM that exits within 10 s of starting does not print them.
  - JMX: if no MBeanServer exists yet (no JMX exporter agent, no `-Dcom.sun.management.jmxremote`),
    the agent waits up to 5 s for the application to create one before creating it itself, so the
    MBeans then appear up to 5 s after startup. When one already exists there is no wait.

## Configuration Reload

The agent watches the active config file and also polls as a fallback.

- watch debounce: `500 ms`
- fallback polling base interval: `1 s`
- reload failure backoff: exponential, capped at `60 s`

If reload fails, the previous working configuration remains active.

## Suggested Alerts

- telemetry mode is not `NORMAL` for a sustained period
- `pletor_agent_observability_droppedcount` increases above baseline
- `pletor_agent_observability_queuefillratio >= 0.80`
- `pletor_agent_observability_maxtaskstalenessms` rises for a sustained period
  (seconds behind schedule; healthy tasks stay near `0` whatever their refresh interval)
- `pletor_agent_observability_failingtaskcount > 0` for a sustained period
- `pletor_agent_observability_stucktaskcount > 0` (a filesystem call is blocked, usually a dead mount)
- filesystem usable bytes drops below service thresholds
- FD usage approaches max FD limit
- cgroup memory working set / limit ratio stays above `0.90`
  (`memoryworkingsetbytes / memorylimitbytes`, only when the limit is not `-1`)
- pressure: sustained `rate(pletor_cgroup_pressuremetrics_memoryfulltotalmicros[5m]) / 1e6` above
  zero means the container's tasks are stalled on memory (reclaim or swapping); the same for
  `iofull` and `cpusome` points at storage or CPU contention (see "Pressure stall information")

## Troubleshooting

Queue pressure:

- Check `QueueFillRatio`, `DroppedCount`, `EndToEndLatencyMillis`, and `MaxTaskStalenessMs`.
- `DEGRADED` drops low-priority filesystem refresh first.
- `BYPASS` drops all refresh work until pressure falls.

Failing metric refreshes:

- `FailingTaskCount > 0` means at least one metric could not be read on its last refresh (for
  example an unreadable `/proc` file or an unavailable filesystem). The affected MBean exposes
  sentinel values (`-1`) or its last known values.
- `FailingTasks` (JMX only, a string attribute) lists the task names, e.g. `fs:/data,cpu`.
- `ErrorCount` / `SinkFailureCount` count every failed refresh. The first failure of each
  minute is also logged at WARNING with a stack trace.

Stuck filesystem calls:

- Filesystem metrics run on their own worker threads, separate from CPU, memory and I/O metrics. A
  filesystem call that blocks (typically `statvfs` on an unresponsive NFS mount) therefore only
  freezes that one filesystem's MBean, which keeps its last values.
- `StuckTaskCount > 0` / `StuckTasks` names the tasks whose current refresh has been running for
  more than 30 seconds. `MaxTaskStalenessMs` rises for the same task.
- A blocked call cannot be interrupted from Java; it clears when the mount recovers. Each stuck
  task holds one of 3 background threads, and while it is stuck it is skipped (counted in
  `DroppedCount`, once per refresh interval) instead of being queued again.

Refresh intervals:

- Values are refreshed by background threads, never by a scrape: reading an MBean attribute only
  returns the last stored value, so scrape frequency (or the number of scrapers) does not change
  the agent's work.
- CPU, memory, cgroup memory and I/O rates refresh every `refresh_interval_seconds` (default `2 s`,
  range 1-60). Keep it well below your scrape interval; values are at most this old.
- File descriptors refresh every `30 s` (or `refresh_interval_seconds` if that is larger), filesystem
  and OS runtime (uptime, mounts) every `10 s`, and static OS info every `5 min`.
- The setting is reloaded with the rest of the configuration file.

Filesystem MBeans:

- Missing paths are logged but do not crash the agent.
- Paths on the same partition are deduplicated.
- `fsmetrics_max_partitions` caps unique filesystem partitions (default `32`, at most `256`; a
  larger value is lowered to 256 with a warning).

CPU limit:

- `co.pletor.node:type=CpuMetrics` `CgroupCpuLimitCores` is the effective CPU limit of the
  container in cores (for example `0.5` or `2.0`), or `-1` when unlimited or unavailable. Like
  `MemoryLimitBytes` it is the tightest finite CFS quota of the cgroup and its ancestors, so a
  container without a quota is still bounded by its pod.
- CPU use as a share of the limit, only where the limit is not `-1`:
  `rate(pletor_node_cpumetrics_cgroupcpuusagenanostotal[5m]) / 1e9 / pletor_node_cpumetrics_cgroupcpulimitcores`.
  A ratio near `1` together with a rising `CgroupCpuThrottledTimeNanosTotal` means the container is
  running into its limit.
- Read from `cpu.max` (cgroup v2) or `cpu.cfs_quota_us` / `cpu.cfs_period_us` (v1). Inside a
  container only the container's own cgroup is visible, so a limit set only on a parent that is
  not visible is not reflected.
- On cgroup v1 the quota files must be in the same controller directory the agent found `cpu.stat`
  in (the `cpu` controller, the usual layout); otherwise the value is `-1`.

Swap:

- Node (`MemMetrics`): `SwapTotalBytes` (`0` when the host has no swap), `SwapUsedBytes`, and the
  cumulative page counters `SwapInPagesTotal` / `SwapOutPagesTotal` (`pswpin` / `pswpout`). Alert on
  activity, not usage: `rate(pletor_node_memmetrics_swapoutpagestotal[5m]) > 0` for a sustained
  period means the host is actively swapping, while a high `SwapUsedBytes` with a flat counter is
  idle swap.
- cgroup (`co.pletor.cgroup:type=MemMetrics`): `SwapUsageBytes` and `SwapLimitBytes` of the
  container's own cgroup (ancestors are not considered). A limit of `0` means the container may not
  swap (the usual Kubernetes setting); `-1` means unlimited or unavailable. cgroup v1 reports swap
  as `memsw - memory` and needs swap accounting enabled (`swapaccount=1`), otherwise `-1`.
- Kubernetes nodes usually run without swap, so `SwapTotalBytes` is `0` and the counters stay flat.
- The counters reset at reboot; `rate()` and `increase()` handle it.

Pressure stall information (PSI):

- `PressureMetrics` reports how much of the time tasks were stalled waiting for CPU, memory or
  I/O. `some` means at least one task was stalled, `full` means all non-idle tasks were stalled at
  once (a stronger sign). It usually rises before latency or throughput visibly degrade, which
  load average and I/O wait do not.
- Two MBeans with the same attributes: `co.pletor.node:type=PressureMetrics` reads
  `/proc/pressure` and describes the whole host (also inside a container), and
  `co.pletor.cgroup:type=PressureMetrics` reads the container's own `*.pressure` files (cgroup v2).
- `*Avg10` is the kernel's 10-second average in percent (0-100). `*TotalMicros` is a cumulative
  counter of stalled microseconds: `rate(x[5m]) / 1e6` is the stalled share of any window, e.g.
  `rate(pletor_cgroup_pressuremetrics_memoryfulltotalmicros[5m]) / 1e6`. The 60 s and 300 s
  kernel averages are not exposed because the counter gives the same over any window.
- Every attribute is `-1` when PSI is unavailable: kernel older than 4.20, PSI disabled
  (`psi=0`), cgroup v1 (the cgroup MBean only), non-Linux, or the CPU `full` line on kernels older
  than 5.13. This is not reported as a failing task.

Rates and ratios (prefer counters):

- `SystemCpuIoWaitRatio`, `SystemCpuStealRatio`, `CgroupCpuThrottledRatio` and the
  `*BytesPerSec` values describe only the last refresh window (`refresh_interval_seconds`, 2 s by default), so a scrape every
  15-60 s samples one arbitrary window. The cumulative counters give exact averages over any range:
  - I/O wait ratio: `rate(pletor_node_cpumetrics_systemcpuiowaitticks[5m]) / rate(pletor_node_cpumetrics_systemcputotalticks[5m])`
  - steal ratio: same with `systemcpustealticks`
  - cgroup throttled share: `rate(..._cgroupcputhrottledtimenanostotal[5m]) / (rate(..._cgroupcputhrottledtimenanostotal[5m]) + rate(..._cgroupcpuusagenanostotal[5m]))`
  - disk/network throughput: `rate(pletor_node_iorates_diskreadbytestotal[5m])`, and likewise for
    `diskwritebytestotal`, `netrxbytestotal`, `nettxbytestotal`
- The exporter may append `_total` to counter names depending on its version.
- Disk and network totals sum the counted devices/interfaces; if one disappears (for example a
  veth pair on a container host) the sum drops once, which Prometheus treats as a counter reset.
- `CgroupCpuThrottledCount` is a per-window delta (a gauge), not a counter.

Cgroup metrics:

- `MemoryLimitBytes = -1` means unlimited or unavailable.
- `MemoryUsageBytes` includes reclaimable page cache, so page-cache-heavy workloads such as Kafka
  sit close to the limit by design. Alert on `MemoryWorkingSetBytes` (usage minus inactive file
  cache, the same figure Kubernetes/cAdvisor uses) instead. It is `-1` when `memory.stat` is
  unavailable.
- `CgroupVersion` is `v1`, `v2`, or `none`.

Rollback:

1. Remove the `-javaagent` JVM option.
2. Restart the JVM.
3. Keep the last known-good config and JAR for redeployment.
