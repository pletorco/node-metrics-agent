# Contributing

Thanks for helping improve `node-metrics-agent`.

This project runs inside host JVM processes, so contributions should preserve host safety first:

- metric collection must be fail-open
- host/JMX read paths must stay bounded and non-blocking
- queues, maps, caches, and thread pools must have hard limits
- repeated internal logs must be throttled
- new metrics should avoid high-cardinality labels
- config parsing must be defensive and keep the last known-good runtime state on reload failure

## Development Setup

Use JDK 21 for builds. The produced bytecode targets Java 11.

```bash
./gradlew test
./gradlew checkstyleMain checkstyleTest
./gradlew jacocoTestReport
```

Security checks:

```bash
./gradlew cyclonedxBom
./gradlew trivyScan
```

`trivyScan` requires a local `trivy` installation.

## Pull Requests

Please include:

- summary of the change
- affected module/path type
- failure behavior
- tests run
- rollback notes for operationally sensitive changes

For telemetry pipeline, config reload, filesystem discovery, or JMX surface changes, include focused
tests for failure isolation and saturation behavior.
