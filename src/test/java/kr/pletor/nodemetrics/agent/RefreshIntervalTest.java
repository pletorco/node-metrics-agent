package kr.pletor.nodemetrics.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.pletor.nodemetrics.metrics.CpuMetrics;
import kr.pletor.nodemetrics.metrics.FdMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for how {@code refresh_interval_seconds} reaches the refresh tasks. */
class RefreshIntervalTest {

  private long savedInterval;
  private Object savedCpu;
  private Object savedFd;

  @BeforeEach
  void saveState() throws Exception {
    savedInterval = (long) field("fastRefreshIntervalMs").get(null);
    savedCpu = field("cpuBean").get(null);
    savedFd = field("fdBean").get(null);
  }

  @AfterEach
  void restoreState() throws Exception {
    field("fastRefreshIntervalMs").set(null, savedInterval);
    field("cpuBean").set(null, savedCpu);
    field("fdBean").set(null, savedFd);
  }

  private static Field field(String name) throws Exception {
    Field f = MetricsAgent.class.getDeclaredField(name);
    f.setAccessible(true);
    return f;
  }

  private static long resolve(Config cfg) throws Exception {
    Method m = MetricsAgent.class.getDeclaredMethod("resolveFastRefreshIntervalMs", Config.class);
    m.setAccessible(true);
    return (long) m.invoke(null, cfg);
  }

  private static Map<String, Long> taskIntervals(long fastMs) throws Exception {
    field("fastRefreshIntervalMs").set(null, fastMs);
    field("cpuBean").set(null, new CpuMetrics());
    field("fdBean").set(null, new FdMetrics());
    Method m = MetricsAgent.class.getDeclaredMethod("buildRefreshTasksLocked");
    m.setAccessible(true);
    @SuppressWarnings("unchecked")
    List<MetricsRefreshEngine.RefreshTask> tasks =
        (List<MetricsRefreshEngine.RefreshTask>) m.invoke(null);
    Map<String, Long> out = new HashMap<>();
    for (MetricsRefreshEngine.RefreshTask t : tasks) {
      out.put(t.name, t.intervalMs);
    }
    return out;
  }

  @Test
  void resolve_usesConfiguredSeconds() throws Exception {
    Config c = Config.defaults();
    c.refreshIntervalSeconds = 7;

    assertEquals(7_000L, resolve(c));
  }

  @Test
  void resolve_fallsBackToDefaultForMissingOrInvalidValues() throws Exception {
    long expected = Config.DEFAULT_REFRESH_INTERVAL_SECONDS * 1_000L;
    Config c = Config.defaults();

    c.refreshIntervalSeconds = null;
    assertEquals(expected, resolve(c));
    c.refreshIntervalSeconds = 0;
    assertEquals(expected, resolve(c));
    c.refreshIntervalSeconds = Config.MAX_REFRESH_INTERVAL_SECONDS + 1;
    assertEquals(expected, resolve(c));
    assertEquals(expected, resolve(null));
  }

  @Test
  void fastMetricsUseTheConfiguredInterval() throws Exception {
    Map<String, Long> intervals = taskIntervals(3_000L);

    assertEquals(3_000L, intervals.get("cpu"));
  }

  @Test
  void fileDescriptorsAreNeverRefreshedMoreOftenThanEvery30Seconds() throws Exception {
    assertEquals(30_000L, taskIntervals(2_000L).get("fd"));
  }

  @Test
  void fileDescriptorsFollowSlowerConfiguredInterval() throws Exception {
    assertEquals(45_000L, taskIntervals(45_000L).get("fd"));
  }

  @Test
  void maxPartitionsIsCappedForConfigBuiltInCode() throws Exception {
    Config c = Config.defaults();
    c.fsmetricsMaxPartitions = Integer.MAX_VALUE;
    Method m = MetricsAgent.class.getDeclaredMethod("resolveMaxFsPartitions", Config.class);
    m.setAccessible(true);

    assertEquals(Config.MAX_FSMETRICS_MAX_PARTITIONS, m.invoke(null, c));
  }
}
