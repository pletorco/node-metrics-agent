# node-metrics-agent

`node-metrics-agent` is a lightweight JVM agent from Pletor Co., Ltd. that exposes host and
container node metrics through JMX.

The agent is designed to run inside production JVMs with a fail-open posture:

- bounded asynchronous refresh pipeline
- overload modes: `NORMAL`, `DEGRADED`, `BYPASS`
- runtime configuration reload for filesystem metrics
- self-observability MBeans for queue pressure, drops, latency, and staleness
- Prometheus JMX exporter example rules using the `pletor_*` metric prefix

Current version: `0.8.0`

## MBeans

The agent registers these MBeans:

- `co.pletor.node:type=CpuMetrics`
- `co.pletor.node:type=MemMetrics`
- `co.pletor.cgroup:type=MemMetrics`
- `co.pletor.proc:type=FdMetrics`
- `co.pletor.node:type=IoRates`
- `co.pletor.node:type=OsInfoMetrics`
- `co.pletor.node:type=OsRuntimeMetrics`
- `co.pletor.node:type=FsMetrics,path=<configured path>`
- `co.pletor.agent:type=TelemetryMode`
- `co.pletor.agent:type=Observability`

## Quick Start

Build the shaded agent JAR:

```bash
./gradlew clean shadowJar
```

Attach it to a JVM:

```bash
java \
  -javaagent:/path/to/node-metrics-agent-0.8.0-all.jar=/path/to/node-metrics.yml \
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
| `fsmetrics_max_partitions` | integer | `32` | Maximum unique filesystem partitions to monitor |
| `fsmetrics_paths` | list | `[/]` | Candidate paths for filesystem metrics |

Example:

```yaml
fsmetrics_max_partitions: 32
fsmetrics_paths:
  - /data/kafka-logs
  - /boot
```

## CLI

The JAR can also generate config files:

```bash
java -jar node-metrics-agent-0.8.0-all.jar init-config --fs-path /data,/var
java -jar node-metrics-agent-0.8.0-all.jar init-kafka-config \
  --server-properties /opt/kafka/config/server.properties
```

## Prometheus

Use `src/main/resources/jmx_exporter_rules_example.yml` as a starting point for the Prometheus
JMX exporter. Exported metric names use the `pletor_*` prefix, for example:

- `pletor_node_cpumetrics_systemcpuload`
- `pletor_node_memmetrics_availablememorybytes`
- `pletor_cgroup_memmetrics_memoryusagebytes`
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
./gradlew cyclonedxBom
./gradlew trivyScan
```

`trivyScan` requires the `trivy` binary to be installed locally. It is not required for normal
`test`, `build`, or `shadowJar` runs.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
