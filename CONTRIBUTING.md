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

Use JDK 21 for builds. Code is compiled with `--release 11`, so it can only use the Java 11 API and
the produced bytecode targets Java 11 (the agent runs inside Kafka brokers and other Java 11+ JVMs).

```bash
./gradlew test
./gradlew checkstyleMain checkstyleTest
./gradlew jacocoTestReport
```

Run the tests on an older runtime (the JDK must be installed or discoverable by Gradle):

```bash
./gradlew test -PtestJavaVersion=11
```

Smoke-test the packaged jar as a real `-javaagent` (CLI, MBean registration, refresh pipeline, no
files created in the application's working directory):

```bash
./gradlew shadowJar
scripts/smoke-test.sh build/libs/node-metrics-agent-*-all.jar
JAVA_BIN=/path/to/jdk11/bin/java scripts/smoke-test.sh build/libs/node-metrics-agent-*-all.jar
```

`shadowJar` does not run the tests; run `./gradlew check` (tests and checkstyle) as well before
releasing. The release workflow does both.

### Formatting

Java sources are formatted with google-java-format through Spotless. `./gradlew check` and CI run
`spotlessCheck`; fix formatting with:

```bash
./gradlew spotlessApply
```

`.git-blame-ignore-revs` lists the one-off reformatting commit; use
`git config blame.ignoreRevsFile .git-blame-ignore-revs` to skip it in `git blame`.

### Checkstyle warning ratchet

The code base predates the Google-style rules, so checkstyle reports warnings that are not fixed yet.
`build.gradle.kts` caps them (`maxWarnings` for `checkstyleMain` and `checkstyleTest`): a change that
adds warnings fails the build. When you fix warnings, lower the numbers in the same change.

Security checks:

```bash
./gradlew cyclonedxBom
./gradlew trivyScan
```

`trivyScan` requires a local `trivy` installation.

## Releasing

Push a tag `vX.Y.Z` that matches the project version. The release workflow verifies the tag, runs
`check`, builds the shaded jar, smoke-tests it and publishes the jar, `SHA256SUMS` and the SBOM.

Optional signed build provenance: set the repository variable `ATTEST_RELEASE_ARTIFACTS` to `true`
(requires repository support for attestations). Consumers can then verify a download with
`gh attestation verify <jar> --repo <owner>/<repo>`.

## Pull Requests

Please include:

- summary of the change
- affected module/path type
- failure behavior
- tests run
- rollback notes for operationally sensitive changes

For telemetry pipeline, config reload, filesystem discovery, or JMX surface changes, include focused
tests for failure isolation and saturation behavior.
