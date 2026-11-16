# Soak and host-overhead checks

`Soak and overhead` runs weekly on Sunday at **03:17 UTC / 12:17 KST**, on Java 11 and 21.
It is separate from normal PR tests. Manual dispatch accepts control/enabled durations; a
maintainer can add `run-soak` to a PR for the full default scenario. Healthy runs retain artifacts;
failures use the normal GitHub Actions notification settings.

## Scenario and controls

Each runner builds the shaded candidate and compiles a separate Java 11-compatible probe against
it. The probe is never packaged into the deployed JAR. Two fresh JVMs run sequentially:

1. **Control:** no production `-javaagent`, 60-second warmup and 300 measured seconds.
2. **Enabled:** the actual packaged `-javaagent`, 60-second warmup and 1,800 measured seconds.

Both use the same checksum-pinned JMX exporter 1.0.1, example rules, loopback HTTP endpoint,
synthetic host operation, fault fixture, GC schedule and JVM flags: G1, two active processors,
128 MiB initial / 256 MiB maximum heap. The comparison includes the real agent's additional
exported series, config reloads and background collection. It does not include an exporter in
only one side of the comparison.

Each second the probe measures 4,096 identical host operations: arithmetic and an escaping
256-byte allocation. It records per-operation p99, current-thread CPU/allocation, live threads,
queue depths and cumulative process CPU. Independent HTTP scrapes verify HTTP 200, exporter
success, expected fixture/agent series and nonnegative counters.

In both JVMs a separate production refresh engine repeatedly cycles through blocked
critical/background collectors (with interval changes while hung), a throwing collector and
rejected oversized configuration, then accepted configuration and recovery. The fixture uses
the production config loader/reloader and JMX adapter. Healthy collection must keep progressing;
a collector may have only one concurrent poll. Refresh threads are bounded at 7 control / 14
enabled, fixture queues at 128 total entries, and actual agent queues at 2,048. Enabled mode also
edits the actual agent file and verifies the real watcher's applied checksum.

## Retention and measurement limits

Every ten seconds the probe forces GC **outside the host batch**, verifies a collection occurred
and samples retained heap. Missing accounting or missing/stalled windows fails as incomplete.
The analyzer compares first/last quarter medians and a robust median pairwise slope, using at
most 256 points for the pairwise estimator. Run length, sample count and HTTP response size are
bounded. RSS/raw heap are retained as diagnostics rather than treated alone as proof of a leak.

Explicit GC and measurement affect the JVM; both modes receive that work. These controlled
regression budgets are not Kafka latency promises or production sizing recommendations.
Sequential control cannot eliminate unrelated runner drift. An unstable control fails instead
of relaxing enabled thresholds. Java 17 remains covered by normal CI; longer tests use the
supported lower runtime and the build/runtime endpoint.

## Budgets and noise tolerance

The reviewed [budgets.json](../scripts/soak/budgets.json) is applied separately to each JDK and
never silently learned from the candidate being checked.

| Measurement | Enabled limit |
| --- | --- |
| Host batch p99 | 2 × control + 5,000 ns |
| Caller CPU per operation | 1.5 × control + 1,000 ns |
| Caller allocation per operation | control + 64 bytes |
| Process CPU | control + 0.10 cores (CPU seconds / wall second) |
| Median live threads | control + 20 |
| Live thread growth | at most 2 between first/last quarters |
| HTTP scrape p99 | 3 × control + 25 ms |
| Retained heap growth | 8 MiB between first/last quarters |
| Retained heap slope | 512 KiB per minute |

Host p99 is the 95th percentile of per-window p99 values; caller CPU/allocation use window
medians. Process CPU uses cumulative counters across the measured duration. HTTP p99 excludes
startup/warmup. A control with host p99 above 1 ms or excessive retained growth is unstable.
Missing measurements are never converted to zero. These starting budgets allow scheduler/JIT/GC
noise while retaining hard resource invariants. Calibration values and environments belong in
the implementation PR/artifacts. Review budget changes against repeated evidence on each JDK;
do not tune a threshold merely to turn a failure green. This costly, environment-sensitive
workflow is intentionally not a required PR merge check.

## Evidence and coverage

Artifacts `soak-java-11` / `soak-java-21` are retained for 30 days, including on failure:

- `summary.json`: pass/fail/incomplete, observed comparisons and budgets;
- `environment.json`: commit, agent/exporter hashes, kernel/cgroup availability and coverage;
- per-JVM properties/commands: actual JDK, VM, detected cgroup version and flags;
- `samples.csv` / `scrapes.csv`: full performance, resource, scenario and RSS time series;
- JVM logs, first/last scrapes and bounded thread dumps on failures/timeouts when obtainable.

Java subprocesses receive no token/secret/password environment variables. No complete environment
dump is retained. HTTP listens on loopback; processes are terminated on failure/timeout.

Real coverage includes the packaged agent, HTTP/JMX exporter, file watcher and available Linux
procfs/sysfs/cgroup files. Actual detected cgroup version is recorded. Faulted collectors are
synthetic: no real kernel/NFS hang, kernel limit change or cgroup namespace migration is injected.
A VM exposing cgroup files is not proof of all container runtime/limit enforcement behavior.

## Reproduction and failure handling

Build with JDK 21 and supply the checksum-verified exporter from the workflow. Choose an installed
supported HotSpot runtime with `--java`; `--javac` can remain the build JDK.

```bash
./gradlew spotlessCheck shadowJar
python3 scripts/soak_check.py \
  --agent-jar build/libs/node-metrics-agent-0.10.1-all.jar \
  --exporter-jar /path/to/jmx_prometheus_javaagent-1.0.1.jar
```

For a quick harness check, add `--baseline-seconds 60 --duration-seconds 60 --warmup-seconds 30
--gc-interval 5`. It does not replace the scheduled 30-minute enabled run. Offline analyzer
regressions run with `python3 -m unittest discover -s scripts/tests -v` and cover growth signals,
host-cost regressions, unstable controls and incomplete evidence.
Combined control/enabled measurement and warmup are limited to 80 minutes, leaving time for
build/diagnostics inside the workflow's 90-minute timeout.

As a negative control, a sandbox JAR containing the pre-#59 engine makes this probe detect
duplicate hung polls after a config reload and exit nonzero. No fault hook enters production.
Inspect the summary and JVM log first, then reproduce with the same JDK/flags/hashes on an idle
runner. Fix isolation/growth failures; rerun unstable controls before changing budgets. Disable
only this workflow to roll back scheduling. Ordinary CI, security gates and runtime behavior
remain independent.
