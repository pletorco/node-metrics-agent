#!/usr/bin/env bash
# Smoke test for the packaged (shaded) agent jar.
#
# Verifies what unit tests cannot: that the jar built by `shadowJar` (relocated and minimized
# dependencies) works as a CLI and as a -javaagent on the JVM it will really run on.
#
# Usage: scripts/smoke-test.sh build/libs/node-metrics-agent-<version>-all.jar
# Env:   JAVA_BIN  java executable to use (default: java), e.g. a Java 11 runtime.
set -euo pipefail

JAR="${1:?usage: smoke-test.sh <node-metrics-agent-all.jar>}"
JAR="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"
JAVA_BIN="${JAVA_BIN:-java}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "== using $("$JAVA_BIN" -version 2>&1 | head -1)"

echo "== manifest: the entry point must be the tiny launcher, not the class with static state"
unzip -p "$JAR" META-INF/MANIFEST.MF | tr -d '\r' | grep -q '^Premain-Class: co.pletor.nodemetrics.agent.AgentLauncher$'

echo "== CLI: help"
"$JAVA_BIN" -jar "$JAR" help > /dev/null

echo "== CLI: init-config"
"$JAVA_BIN" -jar "$JAR" init-config --fs-path "/,/tmp" --output "$WORK/cfg/node-metrics.yml" > /dev/null
grep -q '^fsmetrics_paths:' "$WORK/cfg/node-metrics.yml"
grep -q '^  - /tmp$' "$WORK/cfg/node-metrics.yml"

echo "== agent with config file"
mkdir -p "$WORK/app-with-config"
(cd "$WORK/app-with-config" && "$JAVA_BIN" "-javaagent:$JAR=$WORK/cfg/node-metrics.yml" "$HERE/SmokeProbe.java")

echo "== agent without config file must not create files in the working directory"
mkdir -p "$WORK/app-no-config"
(cd "$WORK/app-no-config" && "$JAVA_BIN" "-javaagent:$JAR" "$HERE/SmokeProbe.java")
if [ -n "$(ls -A "$WORK/app-no-config")" ]; then
  echo "SMOKE FAILURE: agent created files in the application's working directory:" >&2
  ls -la "$WORK/app-no-config" >&2
  exit 1
fi

echo "== a broken agent jar must not stop the application from starting"
# Remove one class the agent needs, as happens when a jar is replaced or truncated while deploying.
BROKEN="$WORK/broken-agent.jar"
python3 - "$JAR" "$BROKEN" <<'PY'
import sys, zipfile
src = zipfile.ZipFile(sys.argv[1])
dst = zipfile.ZipFile(sys.argv[2], "w", zipfile.ZIP_DEFLATED)
for item in src.infolist():
    if item.filename != "co/pletor/nodemetrics/agent/ThrottledLogger.class":
        dst.writestr(item, src.read(item.filename))
dst.close()
PY
mkdir -p "$WORK/app-broken-agent"
OUT="$(cd "$WORK/app-broken-agent" && "$JAVA_BIN" "-javaagent:$BROKEN" "$HERE/AppStarted.java" 2>&1)" || {
  echo "SMOKE FAILURE: the JVM did not start with a broken agent jar:" >&2
  echo "$OUT" >&2
  exit 1
}
echo "$OUT" | grep -q '^APPLICATION STARTED$' || {
  echo "SMOKE FAILURE: the application did not run with a broken agent jar:" >&2
  echo "$OUT" >&2
  exit 1
}

echo "== the agent must not initialize JVM-wide singletons before the application configures them"
JAVAC_BIN="$(dirname "$JAVA_BIN")/javac"
[ -x "$JAVAC_BIN" ] || JAVAC_BIN=javac
mkdir -p "$WORK/late-classes" "$WORK/app-late"
"$JAVAC_BIN" -d "$WORK/late-classes" "$HERE/LateGlobalSetup.java"
OUT="$(cd "$WORK/app-late" && "$JAVA_BIN" "-javaagent:$JAR" -cp "$WORK/late-classes" LateGlobalSetup 2>&1)" || {
  echo "SMOKE FAILURE: the agent interfered with the application's own setup:" >&2
  echo "$OUT" >&2
  exit 1
}
echo "$OUT" | grep -q '^LATE SETUP OK$' || {
  echo "SMOKE FAILURE: unexpected output:" >&2
  echo "$OUT" >&2
  exit 1
}

echo "smoke test passed"
