# Collector input limits

The agent is a fail-open telemetry module. `CollectorInput` enforces limits while reading the
stream, before a decoder, `readLine()` builder or retained collection can grow without a bound.
It does not trust file size metadata: procfs/sysfs commonly report zero or otherwise misleading
sizes. These are fixed safety limits, not configuration options.

## Read inventory

| Production source/caller | Total bytes per file | Bytes per line | Lines per file |
| --- | ---: | ---: | ---: |
| Numeric cgroup memory/swap/PID files, CPU quota/period/usage, loadavg, uptime, file-nr, max_map_count | 4096 | 4096 | 16 |
| CPU cgroup membership/stat; memory key/value stats; `SystemAccess` strings/lines (OS release, kernel version, container membership hints); shared cgroup detection | 65,536 | 16,384 | 1024 |
| General `LinuxProcFs.readLines`: diskstats, net/dev, net/snmp, net/netstat, meminfo, vmstat, self/status, self/io, mounts, PSI | 1,048,576 | 16,384 | 8192 |
| self/maps, counted as bytes without retaining lines | 67,108,864 | 16,384 | 1,048,576 |
| Aggregate first line of CPU stat | 4096 | 4096 | 1 consumed line |

CPU stat deliberately reads only its aggregate first line. Per-CPU rows and the potentially long
interrupt list are neither decoded nor validated. Other table reads must finish successfully
before their contents are returned; a valid-looking prefix is never returned after overflow.
UTF-8 decoding rejects malformed input. LF, CR and CRLF are accepted, with CRLF counted as one
line; an unterminated final line is also bounded.

Total-byte and a single long-line overflow stop the underlying read at their budget plus at most
one detection byte. A row overflow stops processing immediately; an already requested buffer may
have read ahead by at most 8192 bytes on text paths (64 KiB for raw maps counting). No later read
is performed after rejection, and the stream is closed. A file exactly at a limit is accepted;
detecting EOF can require one further read. A single kernel/provider read can still block; the
existing bounded refresh workers, single-flight and stuck-task handling remain the isolation
mechanism for that case. A byte limit is not a kernel I/O timeout.

## Enumeration and retention

- `/sys/block`: inspect at most 8192 entries, including skipped pseudo devices. At entry 8193,
  reject the scan and close it. The cached leaf set therefore has at most 8192 device names.
  Each device's `slaves` directory is checked only for one entry. A limited scan cannot replace
  the cache with a partial set or produce partial aggregate disk counters.
- Physical-interface cache: at most 4096 keys, each at most 256 Java characters. Admission uses
  one short critical section without filesystem I/O. TTL expiry clears the cache; TTL is not its
  size bound. When full, determine the interface type without caching it. Every interface in an
  accepted net/dev table still contributes according to the existing selection rules.
- Linux mount snapshots: at most 8192 strings within the 1 MiB input budget. Getter array copies
  have the same entry bound. Key/value and TCP maps are bounded by their source byte/line limits;
  CPU v1 candidate lists have at most three paths per accepted membership row (3072 paths).
- Non-Linux `FileStore` enumeration: at most 8192 retained entries, 16,384 characters per formatted
  entry and 1,048,576 total characters. Check lengths before concatenation. Limits apply to values
  returned by the JDK provider; the agent cannot constrain that provider's internal allocations.
- CPU, memory and PID effective-limit traversal: at most 64 ancestors below the cgroup mount root.
  Deeper paths return unavailable rather than reporting a limit evaluated from only part of the
  hierarchy.
- Agent config remains separately limited to 64 KiB, bounded YAML depth and no collection aliases
  (#59). Config-derived filesystem beans remain capped at 256. Diagnostic records remain capped
  at 256 (#69). No queue, worker count or scheduling policy changes in this PR.

The standalone CLI's packaged help resource and application-selected server-properties file are
outside collector execution. Existing JVM property/environment strings and JMX/provider objects
are supplied by the host; the file-stream bounds do not claim to constrain all host allocations.

## Failure and recovery

Overflow raises an `IOException` containing only the limit dimension and number, without copying
input contents. `InputLimitExceededCount` counts rejected file reads, enumerations and excessive
ancestor traversals. The example exporter exposes it as
`pletor_agent_observability_inputlimitexceededcount_total`. Cache admission bypass is not a
rejected read and does not increment it. The limiter does not log; refresh-engine failure logs
retain their existing throttling and bounded diagnostic queue.

Collectors retain their existing failure policy: process status, network counters, maps and I/O
snapshots keep their last valid values on read failure; optional sources may instead return their
defined unavailable sentinel (`-1`, an absent cumulative counter, or an unknown info string).
An oversized mount list exposes count `-1` and an empty list, with a refresh failure recorded.
A rejected cgroup membership cannot fall back to root cgroup metrics. A rejected hierarchy level
cannot be treated as unlimited: memory/PID/CPU limits become unavailable for that evaluation. A later valid poll recovers
normally. See the rejection counter even when a source treats a read failure as optional and
does not mark the entire task as failing.

## Regression and measurement

`CollectorInputTest` verifies byte/line/row boundaries, actual bytes consumed, CRLF/UTF-8 behavior,
snapshot preservation and recovery, directory closure, cache churn and cgroup traversal limits.
Per-class coverage floors include both the helper and its stream implementation.

Normal smoke CI on Java 11/17/21 runs `scripts/collector_input_check.py`. Eight separate JVMs use
`-Xmx32m -XX:+ExitOnOutOfMemoryError` and the packaged `-javaagent`: a 64 MiB unterminated line,
total-byte overflow, many short rows, scalar/string sources, mount/map recovery and interface-cache
churn. Each must complete application work and exit normally. Fixtures compile separately and are
never packaged in the production agent.

For an unchanged-main negative control and normal/large mount/interface CPU, allocation and
latency comparison:

```sh
python3 scripts/collector_input_check.py \
  --agent-jar build/libs/node-metrics-agent-0.11.0-all.jar \
  --baseline-jar /path/to/unchanged-main-all.jar \
  --output /tmp/collector-input-evidence.json
```

The baseline must reproduce exit 3/OOM on the same 64 MiB line. Read benchmarks use common
production APIs in fresh JVMs, warm up and verify all 64/4096 mount/interface rows are counted.
They report per-read thread CPU, allocation, p50 and p99; they are synthetic input comparisons,
not a claim that every real procfs provider has the same cost. Keep the existing weekly soak for
long-term host/GC evidence. Roll back by reverting this change; doing so restores #70's unbounded
input risk. Removing the agent disables telemetry.
