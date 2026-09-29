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
-javaagent:/opt/pletor/node-metrics-agent-0.8.0-all.jar=/opt/pletor/node-metrics.yml
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

- CPU, memory, cgroup memory and I/O rates refresh every dispatch cycle (`500 ms`).
- File descriptors refresh every `5 s`, filesystem and OS runtime (uptime, mounts) every `10 s`,
  and static OS info every `5 min`.

Filesystem MBeans:

- Missing paths are logged but do not crash the agent.
- Paths on the same partition are deduplicated.
- `fsmetrics_max_partitions` caps unique filesystem partitions.

Rates and ratios (prefer counters):

- `SystemCpuIoWaitRatio`, `SystemCpuStealRatio`, `CgroupCpuThrottledRatio` and the
  `*BytesPerSec` values describe only the last refresh window (about 500 ms), so a scrape every
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
