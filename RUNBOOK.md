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

## Upgrading From 0.9.0 Or Earlier

The JMX domains changed from `co.pletor.*` to `kr.pletor.*` (`node`, `cgroup`, `proc`, `agent`).
Prometheus series names (`pletor_*`) and the `-javaagent` argument are unchanged, but an exporter
configuration copied from an earlier version matches no MBean, so the agent's metrics silently
disappear from the scrape. Update the copy:

```bash
sed -i 's/co\.pletor\./kr.pletor./g' /opt/jmx-exporter/pletor-node-metrics.yml
```

Do the same for any JMX client, dashboard or script that names the MBeans directly.

## Validation

Confirm these MBeans exist:

- `kr.pletor.node:type=CpuMetrics`
- `kr.pletor.node:type=MemMetrics`
- `kr.pletor.cgroup:type=MemMetrics`
- `kr.pletor.node:type=PressureMetrics`
- `kr.pletor.cgroup:type=PressureMetrics`
- `kr.pletor.proc:type=FdMetrics`
- `kr.pletor.proc:type=ProcessMetrics`
- `kr.pletor.proc:type=MemoryMapMetrics`
- `kr.pletor.node:type=DiskIoMetrics`
- `kr.pletor.node:type=NetworkMetrics`
- `kr.pletor.node:type=IoRates`
- `kr.pletor.node:type=OsInfoMetrics`
- `kr.pletor.node:type=OsRuntimeMetrics`
- `kr.pletor.agent:type=TelemetryMode`
- `kr.pletor.agent:type=Observability`
- `kr.pletor.node:type=FsMetrics,path=<configured path>`

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

`src/main/resources/prometheus_alerts_example.yml` implements the list below (and more) as
Prometheus alerting rules; check it with `promtool check rules` and tune the thresholds.

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

Unavailable values and the exporter:

- An attribute whose source is unavailable on this host (no PSI, no cgroup, an old kernel) reports
  `-1`, and a cumulative counter that has not been read yet does too.
- The Prometheus JMX exporter 1.x rejects a negative `COUNTER` and then fails the **whole scrape**
  (HTTP 500, "counters cannot have a negative value"), so one unavailable counter would hide every
  metric of the target. The example rules therefore export the agent's cumulative counters as
  `UNTYPED`: the value and the name are kept (with `COUNTER` the exporter 1.x would also append
  `_total`), and `rate()` and `increase()` work on them as on counters. If you write your own rules,
  do not type these attributes `COUNTER`. The agent's own `kr.pletor.agent` counters are never
  negative and stay `COUNTER`.
- Queries should ignore `-1`: `rate()`/`increase()` of a constant `-1` is `0`, and for gauges use a
  filter such as `x >= 0` or `limit > 0` before dividing. A counter that becomes available later
  (or is unavailable at the first scrape) jumps from `-1` to its full value once, which
  `increase()` counts as a single large increase; ignore the first window after a restart when
  alerting on absolute increases.
- `src/main/resources/prometheus_alerts_example.yml` has example alerts that follow these rules;
  the build checks that every metric name in it is one the example exporter rules produce.

Stuck reads:

- Regular metrics (CPU, memory, I/O, network, process, ...) run on 3 worker threads and each
  filesystem metric runs in a separate background lane of 3 more, so a read that blocks (a
  `statvfs` on an unresponsive NFS mount, or a `/proc` or cgroup read stuck in the kernel) freezes
  only that one MBean, which keeps its last values. A task is never queued again while its previous
  run is still queued or running, so a hung task occupies one worker at most and the others keep
  refreshing.
- `StuckTaskCount > 0` / `StuckTasks` names the tasks whose current refresh has been running for
  more than 30 seconds. `MaxTaskStalenessMs` rises for the same task.
- A blocked call cannot be interrupted from Java; it clears when the mount or kernel path
  recovers. Each stuck task holds one worker of its lane, and while it is stuck it is skipped
  (counted in `DroppedCount`, once per refresh interval) instead of being queued again. It does not
  change the overload mode: only a full queue does.

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

Disk I/O (`DiskIoMetrics`):

- Operation counts and I/O time from `/proc/diskstats`, summed over the same physical (leaf) block
  devices as the disk byte counters, so there is no per-device breakdown. All are cumulative
  counters (`rate()`); the `-1` sentinel means `/proc/diskstats` or a countable device is missing.
- Average latency: `rate(diskreadtimemillistotal[5m]) / rate(diskreadscompletedtotal[5m])` (and the
  write equivalent), in milliseconds per operation.
- Average device utilization: `rate(diskiotimemillistotal[5m]) / 1000 / diskdevicecount`. It is an
  average over devices: one saturated disk among several idle ones will not show as high.
- Average queue length: `rate(diskweightediotimemillistotal[5m]) / 1000`.

Network errors and TCP (`NetworkMetrics`):

- Interface error and drop counters use the same interfaces as the network byte counters.
- TCP counters come from `/proc/net/snmp` and `/proc/net/netstat`; inside a container they describe
  the container's network namespace, so on Kubernetes they cover that pod.
- Retransmit ratio: `rate(tcpretranssegstotal[5m]) / rate(tcpoutsegstotal[5m])`. A sustained rise
  is an early sign of a network problem. `tcplistenoverflowstotal` / `tcplistendropstotal` rising
  means the application accepts connections too slowly.
- Each source is independent: a missing file only makes its own attributes `-1` (not a failure).

JVM process (`ProcessMetrics`):

- `ResidentSetBytes` is the memory the JVM process really holds (`VmRSS`): heap, metaspace, direct
  buffers, mapped files and native libraries. Compare it with the JVM's own accounting
  (`java.lang` memory MBeans) to find memory the JVM does not track, and with
  `MemoryWorkingSetBytes / MemoryLimitBytes` of the container.
- `ResidentAnonBytes` (heap and private memory) and `ResidentFileBytes` (mapped files) split the
  resident set. `ThreadCount` counts native threads, including JVM-internal ones.
- `IoReadBytesTotal` / `IoWriteBytesTotal` are the bytes this process caused to hit storage
  (`/proc/self/io`, excluding page-cache hits); they are `-1` when the kernel has no task I/O
  accounting or access is denied.
- Context switches are not exposed: `/proc/self/status` reports them for the main thread only.

CPU time per mode:

- `CpuMetrics` exposes the cumulative ticks of every state of the aggregate `/proc/stat` line:
  `SystemCpuUserTicks`, `SystemCpuNiceTicks`, `SystemCpuSystemTicks`, `SystemCpuIdleTicks`,
  `SystemCpuIrqTicks` and `SystemCpuSoftIrqTicks`, next to the existing total, I/O wait and steal
  ticks. Ticks are in `USER_HZ` (normally 100 per second).
- Share of CPU time in a state over any window: `rate(pletor_node_cpumetrics_systemcpusystemticks[5m])
  / rate(pletor_node_cpumetrics_systemcputotalticks[5m])`. A high `system` or `softirq` share points
  at system-call or network-processing load, a high `nice` share at background jobs.

OOM kills:

- `MemMetrics.SystemOomKillTotal` counts processes killed by the kernel's OOM killer on the whole
  host (`/proc/vmstat`); inside a container it still counts kills of any process on the node, so a
  rise means the node is short of memory even when this container was not the victim.
- `kr.pletor.cgroup:type=MemMetrics` `MemoryOomKillTotal` counts kills in the container's own cgroup
  (`memory.events` on v2, `memory.oom_control` on v1).
- **A kill of the JVM itself is not reliably visible.** The agent dies with the process and, on
  Kubernetes, a restarted container gets a new cgroup that starts at 0. Use the orchestrator's
  termination reason (`kube_pod_container_status_last_terminated_reason`, the restart count) for
  that. The cgroup counter is reliable for other processes in the container and for services whose
  cgroup outlives the JVM (a systemd service).
- Both are `-1` on kernels older than 4.13.
- `MemoryMaxEventsTotal` counts the times the container's memory use reached its limit and the
  kernel had to reclaim or kill (`max` in `memory.events` on v2, `memory.failcnt` on v1). It rises
  before the first OOM kill, so `rate(...memorymaxeventstotal[5m]) > 0` for several minutes is the
  earlier warning. `MemoryHighEventsTotal` counts crossings of `memory.high` (v2 only, `-1` on v1);
  it stays `0` unless `memory.high` is set, which Kubernetes does only with the memory QoS feature.

Process limit (PIDs):

- `kr.pletor.cgroup:type=PidsMetrics` reports `PidsCurrent` and `PidsLimit` from the `pids`
  controller. Every thread counts, so a JVM with many threads can reach `pids.max` and then fails
  with `OutOfMemoryError: unable to create native thread` although heap and RAM are fine.
- The limit is the tightest finite `pids.max` of the container's cgroup and its ancestors, and
  `PidsCurrent` is the count of the level that holds it. On Kubernetes `podPidsLimit` applies to the
  whole pod, so with sidecars the container's own count would understate how close the pod is.
  Alert on `pidscurrent / pidslimit` (for example above `0.85`) where the limit is not `-1`.
- cgroup v1 reads the `pids` hierarchy that sits next to the `memory` one; `-1` when it is not
  mounted or the controller is not enabled.

Memory mappings:

- `MemoryMapMetrics` reports `MemoryMapCount` (lines of `/proc/self/maps`) and `MaxMemoryMapCount`
  (`vm.max_map_count`). A process that maps many files, such as a Kafka broker with the index files
  of every log segment, fails with "Map failed" or an `OutOfMemoryError` when it reaches the limit
  although heap and RAM are fine. Alert on `memorymapcount / maxmemorymapcount` (for example above
  `0.8`); the count grows with partitions and segments.
- It is the most expensive read the agent does, so it is refreshed once a minute in the
  low-priority lane (dropped first when the engine is overloaded). Measured with the agent's
  streaming byte count: 0.7 ms at 1,000 mappings, 12.8 ms at 20,000 and 32 ms at 60,000 (79 ms when
  reading line by line). While another thread read the file in a tight loop, `mmap` calls from a
  second thread showed no measurable extra latency (median 3 us either way; p99 52 versus 62 us).
  On a host with several very large processes the count is only for the JVM itself.

System-wide file handles:

- `FdMetrics.SystemOpenFileHandles` and `SystemMaxFileHandles` are the kernel-wide count and limit
  (`/proc/sys/fs/file-nr`). A host running many processes can exhaust the kernel-wide table even
  when each process is under its own limit; mostly relevant to VMs and bare metal.

CPU limit:

- `kr.pletor.node:type=CpuMetrics` `CgroupCpuLimitCores` is the effective CPU limit of the
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
- cgroup (`kr.pletor.cgroup:type=MemMetrics`): `SwapUsageBytes` and `SwapLimitBytes` of the
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
- Two MBeans with the same attributes: `kr.pletor.node:type=PressureMetrics` reads
  `/proc/pressure` and describes the whole host (also inside a container), and
  `kr.pletor.cgroup:type=PressureMetrics` reads the container's own `*.pressure` files (cgroup v2).
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
- The example rules export counters as `UNTYPED`, so the names above are used as written, with no
  `_total` suffix (see "Unavailable values and the exporter" below).
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
