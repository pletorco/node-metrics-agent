package kr.pletor.nodemetrics.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import kr.pletor.nodemetrics.metrics.RefreshManagedMetric;
import org.junit.jupiter.api.Test;

class AgentObservabilityMetricsTest {

  @Test
  void observabilityMetrics_shouldReturnDefaultsWhenEngineIsNotInitialized() {
    AgentObservabilityMetrics metrics =
        new AgentObservabilityMetrics(() -> null, () -> TelemetryMode.NORMAL, () -> 0L);

    assertEquals(0L, metrics.getProcessedCount());
    assertEquals(0L, metrics.getEnqueueCount());
    assertEquals(0L, metrics.getDequeueCount());
    assertEquals(0L, metrics.getDroppedCount());
    assertEquals(0L, metrics.getErrorCount());
    assertEquals(0L, metrics.getSinkSuccessCount());
    assertEquals(0L, metrics.getSinkFailureCount());
    assertEquals(0L, metrics.getSinkRetryCount());
    assertEquals("NORMAL", metrics.getMode());
    assertEquals(0, metrics.getQueueDepth());
    assertEquals(0.0, metrics.getQueueFillRatio());
    assertEquals(0.0, metrics.getEndToEndLatencyMillis());
    assertEquals(0L, metrics.getMaxTaskStalenessMs(), "Staleness should be 0 when engine is null");
    assertEquals(
        0L,
        metrics.getThrottledLoggerOverflowCount(),
        "Overflow count should be 0 when no overflow");
  }

  @Test
  void observabilityMetrics_shouldExposeLiveEngineValues() {
    MetricsRefreshEngine engine = new MetricsRefreshEngine(10L, 16, mode -> {});
    engine.setTasks(List.of(new MetricsRefreshEngine.RefreshTask("ok", new NoopMetric(), false)));
    AgentObservabilityMetrics metrics =
        new AgentObservabilityMetrics(() -> engine, engine::currentMode, () -> 0L);

    try {
      engine.start();
      waitUntil(() -> metrics.getProcessedCount() > 0L, 2_000L);

      assertTrue(
          metrics.getProcessedCount() > 0L, "Processed count should increase once engine runs");
      assertTrue(metrics.getEnqueueCount() > 0L, "Enqueue count should increase once engine runs");
      assertTrue(metrics.getDequeueCount() > 0L, "Dequeue count should increase once engine runs");
      assertTrue(
          metrics.getSinkSuccessCount() > 0L,
          "Sink success count should increase once engine runs");
      assertEquals(0L, metrics.getSinkFailureCount(), "No sink failure expected for noop metric");
      assertEquals(0L, metrics.getSinkRetryCount(), "Retry strategy is disabled by default");
      assertEquals("NORMAL", metrics.getMode());
      assertTrue(metrics.getQueueDepth() >= 0);
      assertTrue(metrics.getQueueFillRatio() >= 0.0);
      assertTrue(metrics.getEndToEndLatencyMillis() >= 0.0);
      assertTrue(
          metrics.getMaxTaskStalenessMs() >= 0L,
          "Staleness should be non-negative while engine runs");
    } finally {
      engine.stop();
    }
  }

  @Test
  void observabilityMetrics_shouldExposeThrottledLoggerOverflowCount() {
    long[] overflowHolder = {0L};
    AgentObservabilityMetrics metrics =
        new AgentObservabilityMetrics(
            () -> null, () -> TelemetryMode.NORMAL, () -> overflowHolder[0]);

    assertEquals(
        0L, metrics.getThrottledLoggerOverflowCount(), "Initial overflow count should be zero");

    overflowHolder[0] = 5L;
    assertEquals(
        5L,
        metrics.getThrottledLoggerOverflowCount(),
        "Overflow count should reflect supplier value");
  }

  private static void waitUntil(Check check, long timeoutMs) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    while (System.nanoTime() < deadline) {
      if (check.ok()) {
        return;
      }
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20L));
    }
  }

  @FunctionalInterface
  interface Check {
    boolean ok();
  }

  static class NoopMetric implements RefreshManagedMetric {
    @Override
    public void poll() {
      // no-op
    }

    @Override
    public void setReadRefreshEnabled(boolean enabled) {
      // no-op
    }
  }

  @Test
  void observabilityMetrics_shouldReportFailingTasks() {
    MetricsRefreshEngineTest.AbsorbingMetric broken =
        new MetricsRefreshEngineTest.AbsorbingMetric();
    broken.error.set(new java.io.IOException("boom"));
    MetricsRefreshEngine engine = new MetricsRefreshEngine(5L, 64);
    engine.setTasks(
        List.of(
            new MetricsRefreshEngine.RefreshTask(
                "cpu", new MetricsRefreshEngineTest.AbsorbingMetric(), false),
            new MetricsRefreshEngine.RefreshTask("fs:/data", broken, true)));
    AgentObservabilityMetrics metrics =
        new AgentObservabilityMetrics(() -> engine, () -> TelemetryMode.NORMAL, () -> 0L);

    assertEquals(0, metrics.getFailingTaskCount());
    assertEquals("", metrics.getFailingTasks());

    try {
      engine.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (metrics.getFailingTaskCount() == 0 && System.nanoTime() < deadline) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10L));
      }
      assertEquals(1, metrics.getFailingTaskCount());
      assertEquals("fs:/data", metrics.getFailingTasks());
    } finally {
      engine.stop();
    }
  }

  @Test
  void observabilityMetrics_shouldReportStuckTasks() {
    MetricsRefreshEngineTest.HangingMetric hung = new MetricsRefreshEngineTest.HangingMetric();
    MetricsRefreshEngine engine = new MetricsRefreshEngine(5L, 64);
    engine.setStuckThresholdMs(50L);
    engine.setTasks(List.of(new MetricsRefreshEngine.RefreshTask("fs:/dead-nfs", hung, true)));
    AgentObservabilityMetrics metrics =
        new AgentObservabilityMetrics(() -> engine, () -> TelemetryMode.NORMAL, () -> 0L);
    assertEquals(0, metrics.getStuckTaskCount());
    assertEquals("", metrics.getStuckTasks());

    try {
      engine.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (metrics.getStuckTaskCount() == 0 && System.nanoTime() < deadline) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10L));
      }
      assertEquals(1, metrics.getStuckTaskCount());
      assertEquals("fs:/dead-nfs", metrics.getStuckTasks());
    } finally {
      hung.release();
      engine.stop();
    }
  }
}
