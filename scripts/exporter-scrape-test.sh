#!/usr/bin/env bash
# Scrapes the agent through a real Prometheus JMX exporter.
#
# Unit tests cannot tell how an exporter version treats the agent's MBeans. This runs the shaded
# agent and the exporter in one JVM with the example rules and checks the /metrics response: it must
# be HTTP 200 with the agent's series (an exporter fails the whole scrape on a negative counter, or
# on a null value in an MBean customized with attributesAsLabels), counters must carry the _total
# suffix, and no counter may be negative.
#
# Usage: scripts/exporter-scrape-test.sh <node-metrics-agent-all.jar> <jmx_prometheus_javaagent.jar>
# Env:   JAVA_BIN    java executable to use (default: java).
#        RULES_FILE  exporter rules to use (default: the example rules).
set -euo pipefail

AGENT_JAR="${1:?usage: exporter-scrape-test.sh <agent-all.jar> <jmx_prometheus_javaagent.jar>}"
EXPORTER_JAR="${2:?usage: exporter-scrape-test.sh <agent-all.jar> <jmx_prometheus_javaagent.jar>}"
AGENT_JAR="$(cd "$(dirname "$AGENT_JAR")" && pwd)/$(basename "$AGENT_JAR")"
EXPORTER_JAR="$(cd "$(dirname "$EXPORTER_JAR")" && pwd)/$(basename "$EXPORTER_JAR")"
JAVA_BIN="${JAVA_BIN:-java}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RULES="${RULES_FILE:-$HERE/../src/main/resources/jmx_exporter_rules_example.yml}"
WORK="$(mktemp -d)"
APP_PID=""
cleanup() {
  [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null || true
  rm -rf "$WORK"
}
trap cleanup EXIT

fail() {
  echo "EXPORTER SCRAPE FAILURE: $1" >&2
  [ -f "$WORK/app.log" ] && tail -n 30 "$WORK/app.log" >&2
  [ -f "$WORK/metrics.txt" ] && head -c 1500 "$WORK/metrics.txt" >&2
  exit 1
}

PORT="$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"
echo "== exporter $(unzip -p "$EXPORTER_JAR" META-INF/MANIFEST.MF | tr -d '\r' | grep -i '^Implementation-Version' || echo '(version unknown)') on port $PORT, $("$JAVA_BIN" -version 2>&1 | head -1)"

mkdir -p "$WORK/app"
(cd "$WORK/app" && exec "$JAVA_BIN" "-javaagent:$AGENT_JAR" "-javaagent:$EXPORTER_JAR=$PORT:$RULES" \
  "$HERE/Sleep.java" 90000) > "$WORK/app.log" 2>&1 &
APP_PID=$!

# The agent registers its MBeans shortly after the JVM starts and fills them within a refresh
# interval, so retry until the series appear.
CODE=""
for _ in $(seq 1 40); do
  CODE="$(curl -s -m 5 -o "$WORK/metrics.txt" -w '%{http_code}' "http://127.0.0.1:$PORT/metrics" || true)"
  if [ "$CODE" = "200" ] && grep -q '^pletor_node_iorates_diskreadbytestotal_total ' "$WORK/metrics.txt" \
      && grep -Eq '^pletor_node_memmetrics_totalmemorybytes [1-9]' "$WORK/metrics.txt"; then
    break
  fi
  sleep 1
done

[ "$CODE" = "200" ] || fail "HTTP $CODE instead of 200"
grep -q '^pletor_' "$WORK/metrics.txt" || fail "no pletor_ series in the scrape"
grep -q '^# TYPE pletor_node_iorates_diskreadbytestotal_total counter$' "$WORK/metrics.txt" \
  || fail "a cumulative counter is not exposed as <attribute>_total with the counter type"
grep -q '^pletor_node_cpumetrics_systemcpuload ' "$WORK/metrics.txt" || fail "a gauge is missing"
grep -q '^pletor_agent_observability_processedcount_total ' "$WORK/metrics.txt" \
  || fail "an agent counter is missing"
if grep -Eq '^pletor_[a-z0-9_]+_total(\{[^}]*\})? -' "$WORK/metrics.txt"; then
  fail "a counter is negative: $(grep -E '^pletor_[a-z0-9_]+_total(\{[^}]*\})? -' "$WORK/metrics.txt" | head -3)"
fi
if grep -q "JMX scrape failed\|An Exception occurred while scraping" "$WORK/app.log" "$WORK/metrics.txt"; then
  fail "the exporter reported a failed scrape"
fi

echo "== $(grep -c '^pletor_' "$WORK/metrics.txt") pletor_ series, HTTP 200, no negative counters"
echo "exporter scrape test passed"
