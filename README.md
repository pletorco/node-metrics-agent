# node-metrics-agent

`node-metrics-agent` is a lightweight JVM agent from Pletor Co., Ltd. that registers host and
container node metrics as JMX MBeans.

The application, JVM management options or a JMX exporter must create the platform MBeanServer.
The agent waits for an existing discoverable server without creating this JVM-wide singleton;
without one, metrics collection remains dormant. Agent diagnostics use bounded asynchronous stderr
output and do not use the application's JUL handlers. See [startup behavior](RUNBOOK.md#isolation-from-the-application)
for details.

The agent does not open an HTTP metrics endpoint by itself. You can inspect metrics through a JMX
connection, and the recommended Prometheus integration is the
[Prometheus JMX exporter](https://github.com/prometheus/jmx_exporter).

The agent is designed to run inside production JVMs with a fail-open posture:

- bounded asynchronous refresh pipeline; filesystem refresh runs on separate threads so a hung mount cannot stall other metrics
- overload modes: `NORMAL`, `DEGRADED`, `BYPASS`
- runtime configuration reload for filesystem metrics
- self-observability MBeans for queue pressure, drops, latency, staleness, and failing metric refreshes
- Prometheus JMX exporter example rules using the `pletor_*` metric prefix

Current version: `0.10.1`

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
  -javaagent:/path/to/node-metrics-agent-0.10.1-all.jar=/path/to/node-metrics.yml \
  -jar your-app.jar
```

Initialization is attempted once per loaded agent class. With repeated `-javaagent` options, the
first option selects the startup configuration; later options return without starting another
initializer, collector pipeline or watcher. A failed or partial attempt is not retried by repeating
the option or calling the same `premain` entry point again. Runtime changes use the first
configuration file's existing watcher. Fixed MBean names already owned by another component are
left in place. Collectors whose registration fails are excluded from refresh scheduling.

Refresh dispatch begins only after the dispatcher and all six workers have started. If any thread
cannot be created or started, partial startup is rolled back and refresh stays disabled until the
host JVM restarts. `RefreshEngineState`, `RefreshEngineRunning`, `RefreshWorkerCount` and
`RefreshEngineStartFailureCount` expose this outcome; both mode MBeans report `BYPASS` while
the engine is inactive. See [refresh engine startup troubleshooting](RUNBOOK.md#refresh-engine-startup).

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

Configuration reads are capped at **64 KiB of bytes**, including comments, before YAML parsing.
YAML nesting is limited to 20 levels; collection aliases (including recursive aliases) and custom
object tags are rejected. Invalid startup input uses defaults. A rejected reload keeps the last
working configuration and can recover after the file is corrected.

## CLI

The JAR can also generate config files:

```bash
java -jar node-metrics-agent-0.10.1-all.jar init-config --fs-path /data,/var
java -jar node-metrics-agent-0.10.1-all.jar init-kafka-config \
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
  -javaagent:/opt/pletor/node-metrics-agent-0.10.1-all.jar=/opt/pletor/node-metrics.yml \
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

After `./gradlew shadowJar`, `scripts/smoke-test.sh build/libs/node-metrics-agent-0.10.1-all.jar`
checks the packaged agent. Its launcher checks run in disposable JVMs with external time limits,
including a preceding agent whose inherited thread-local callbacks block, throw, or copy 8 MiB.
Agent threads must neither invoke those callbacks nor inherit host thread-local values. The same
checks run in smoke CI on Java 11, 17 and 21; the fixture classes are not packaged in the agent.
The same smoke suite checks repeated/concurrent agent entry, first-argument selection, config reload,
host-owned MBean collisions and interrupted initialization in bounded subprocesses.
It checks same-size/same-mtime edits, atomic replacement, duplicate suppression and rejected-edit
recovery through the real packaged agent watcher. To reproduce the unchanged-main control and
compare watcher CPU/allocation/p99 cost at 512 bytes and 64 KiB, run
`python3 scripts/config_reload_check.py --agent-jar <candidate.jar> --baseline-jar <main.jar> --output /tmp/reload-evidence.json`.
It also checks collector removal/re-registration during an uninterruptible synthetic read. For
before/after execution and diagnostic-read measurements, run
`python3 scripts/collector_ownership_check.py --agent-jar <candidate.jar> --baseline-jar <main.jar> --output /tmp/ownership-evidence.json`.

Cgroup migration checks use synthetic v1/v2 files and the packaged collectors; they do not move the
host process. Run `python3 scripts/cgroup_migration_check.py --agent-jar <candidate.jar> --baseline-jar <main.jar> --output /tmp/migration-evidence.json`
for the unchanged-main control and worker/getter CPU/allocation/p99 measurements. Add
`--exporter-jar <jmx-exporter-1.6.0.jar>` to verify HTTP scrapes and changing memory/PID/PSI identity
labels. See [membership and counter semantics](RUNBOOK.md#cgroup-membership-changes).

`trivyScan` requires Python 3.9+ and Trivy. It checks runtime, test and build-tool dependencies
and Gradle vendor advisories; `trivyScanAll` blocks all severities. See
[Dependency security checks](docs/SECURITY-CHECKS.md) for reports, failure handling and coverage
boundaries. Security scanning is not required for normal `test`, `build`, or `shadowJar` runs.
Daily monitoring scans main and the latest published release SBOM, with deduplicated issue alerts
and a separate freshness watchdog. See the same guide for ownership and scheduling limitations.
PR CI also gates runtime/tooling HIGH, CRITICAL and UNKNOWN findings, and releases publish only
the checked build. Scanner/advisory/DB failures block these gates.

Weekly and opt-in [soak checks](docs/SOAK-CHECKS.md) measure resource stability and host overhead
with real HTTP scrapes, config reloads and controlled collector failures. They run separately
from ordinary PR tests.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
