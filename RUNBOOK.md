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
- filesystem usable bytes drops below service thresholds
- FD usage approaches max FD limit
- cgroup memory usage/limit ratio stays above `0.90`

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

Refresh intervals:

- CPU, memory, cgroup memory and I/O rates refresh every dispatch cycle (`500 ms`).
- File descriptors refresh every `5 s`, filesystem and OS runtime (uptime, mounts) every `10 s`,
  and static OS info every `5 min`.

Filesystem MBeans:

- Missing paths are logged but do not crash the agent.
- Paths on the same partition are deduplicated.
- `fsmetrics_max_partitions` caps unique filesystem partitions.

Cgroup metrics:

- `MemoryLimitBytes = -1` means unlimited or unavailable.
- `CgroupVersion` is `v1`, `v2`, or `none`.

Rollback:

1. Remove the `-javaagent` JVM option.
2. Restart the JVM.
3. Keep the last known-good config and JAR for redeployment.
