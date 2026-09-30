# node-metrics-agent

`node-metrics-agent` is a lightweight JVM agent from Pletor Co., Ltd. that registers host and
container node metrics as JMX MBeans.

The agent does not open an HTTP metrics endpoint by itself. You can inspect metrics through a JMX
connection, and the recommended Prometheus integration is the
[Prometheus JMX exporter](https://github.com/prometheus/jmx_exporter).

The agent is designed to run inside production JVMs with a fail-open posture:

- bounded asynchronous refresh pipeline; filesystem refresh runs on separate threads so a hung mount cannot stall other metrics
- overload modes: `NORMAL`, `DEGRADED`, `BYPASS`
- runtime configuration reload for filesystem metrics
- self-observability MBeans for queue pressure, drops, latency, staleness, and failing metric refreshes
- Prometheus JMX exporter example rules using the `pletor_*` metric prefix

Current version: `0.9.0`

## MBeans

The agent registers these MBeans:

- `kr.pletor.node:type=CpuMetrics`
- `kr.pletor.node:type=MemMetrics`
- `kr.pletor.cgroup:type=MemMetrics`
- `kr.pletor.cgroup:type=PidsMetrics` (process limit of the JVM's cgroup)
- `kr.pletor.node:type=PressureMetrics` and `kr.pletor.cgroup:type=PressureMetrics` (pressure stall
  information, see `RUNBOOK.md`)
- `kr.pletor.proc:type=FdMetrics`
- `kr.pletor.proc:type=ProcessMetrics` (resident set, threads and I/O of the JVM process)
- `kr.pletor.proc:type=MemoryMapMetrics` (memory mappings versus `vm.max_map_count`)
- `kr.pletor.node:type=DiskIoMetrics` (disk operations, latency and utilization counters)
- `kr.pletor.node:type=NetworkMetrics` (network errors and drops, TCP retransmits)
- `kr.pletor.node:type=IoRates`
- `kr.pletor.node:type=OsInfoMetrics`
- `kr.pletor.node:type=OsRuntimeMetrics`
- `kr.pletor.node:type=FsMetrics,path=<configured path>`
- `kr.pletor.agent:type=TelemetryMode`
- `kr.pletor.agent:type=Observability`

## Quick Start

Build the shaded agent JAR:

```bash
./gradlew clean shadowJar
```

Attach it to a JVM:

```bash
java \
  -javaagent:/path/to/node-metrics-agent-0.9.0-all.jar=/path/to/node-metrics.yml \
  -jar your-app.jar
```

If no config path is provided, the agent resolves config in this order:

1. agent argument path
2. `/monitor/node-metrics.yml`
3. `./config/node-metrics.yml`
4. in-memory defaults

## Configuration

Supported keys:

| Key | Type | Default | Description |
|---|---:|---:|---|
| `fsmetrics_max_partitions` | integer | `32` | Maximum unique filesystem partitions to monitor (upper limit `256`; larger values are lowered to it) |
| `refresh_interval_seconds` | integer (1-60) | `2` | How often CPU, memory, cgroup memory and I/O rates are refreshed in the background (file descriptors: at most every 30 s) |
| `fsmetrics_paths` | list | `[/]` | Candidate paths for filesystem metrics |

Example:

```yaml
fsmetrics_max_partitions: 32
refresh_interval_seconds: 2
fsmetrics_paths:
  - /data/kafka-logs
  - /boot
```

## CLI

The JAR can also generate config files:

```bash
java -jar node-metrics-agent-0.9.0-all.jar init-config --fs-path /data,/var
java -jar node-metrics-agent-0.9.0-all.jar init-kafka-config \
  --server-properties /opt/kafka/config/server.properties
```

## Prometheus

The recommended way to scrape this agent with Prometheus is to run the
[Prometheus JMX exporter](https://github.com/prometheus/jmx_exporter) alongside your JVM process.
For Java agent usage details, see the
[JMX exporter Java agent documentation](https://prometheus.github.io/jmx_exporter/deployment/java-agent).

One common launch shape is:

```bash
java \
  -javaagent:/opt/jmx-exporter/jmx_prometheus_javaagent.jar=9404:/opt/jmx-exporter/pletor-node-metrics.yml \
  -javaagent:/opt/pletor/node-metrics-agent-0.9.0-all.jar=/opt/pletor/node-metrics.yml \
  -jar your-app.jar
```

Use `src/main/resources/jmx_exporter_rules_example.yml` as the exporter config starting point and
`src/main/resources/prometheus_alerts_example.yml` for example alerting rules.
Exported Prometheus metric names use the `pletor_*` prefix, for example:

- `pletor_node_cpumetrics_systemcpuload`
- `pletor_node_memmetrics_availablememorybytes`
- `pletor_cgroup_memmetrics_memoryusagebytes`
- `pletor_cgroup_memmetrics_memoryworkingsetbytes`
- `pletor_node_iorates_diskreadbytestotal_total` (cumulative counters: use `rate()`)
- `pletor_node_cpumetrics_systemcpuiowaitticks_total` (cumulative counter)
- `pletor_node_pressuremetrics_memoryfulltotalmicros_total` (cumulative counter: stalled microseconds)
- `pletor_node_memmetrics_swapoutpagestotal_total` (cumulative counter: pages swapped out)
- `pletor_node_networkmetrics_tcpretranssegstotal_total` (cumulative counter)
- `pletor_node_diskiometrics_diskiotimemillistotal_total` (cumulative counter)
- `pletor_proc_processmetrics_residentsetbytes`
- `pletor_proc_fdmetrics_openfiledescriptorcount`
- `pletor_agent_observability_queuefillratio`

## Build And Test

Requirements:

- JDK 21 for builds
- runtime target: Java 11+

Common commands:

```bash
./gradlew test
./gradlew jacocoTestReport
./gradlew checkstyleMain checkstyleTest
./gradlew spotlessCheck   # spotlessApply fixes formatting
./gradlew cyclonedxBom
./gradlew trivyScan
```

`trivyScan` requires the `trivy` binary to be installed locally. It is not required for normal
`test`, `build`, or `shadowJar` runs.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
