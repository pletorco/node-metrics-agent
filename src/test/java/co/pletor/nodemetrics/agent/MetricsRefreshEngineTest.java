package co.pletor.nodemetrics.agent;

import co.pletor.nodemetrics.metrics.RefreshManagedMetric;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MetricsRefreshEngineTest {

  @Test
  void engine_shouldPollTasksAsynchronously() {
    CountingMetric metric = new CountingMetric();
    MetricsRefreshEngine engine = new MetricsRefreshEngine(20L, 64);
    engine.setTasks(List.of(new MetricsRefreshEngine.RefreshTask("metric-a", metric, false)));

    try {
      engine.start();
      waitUntil(() -> metric.pollCount.get() >= 3, 2_000L);
      assertTrue(metric.pollCount.get() >= 3, "Metric should be polled by worker thread");
      assertTrue(engine.enqueueCount() >= 3L, "Engine should track enqueue count");
      assertTrue(engine.dequeueCount() >= 3L, "Engine should track dequeue count");
      assertTrue(engine.sinkSuccessCount() >= 3L, "Engine should track sink success count");
      assertTrue(engine.endToEndLatencyMillis() >= 0.0, "Engine should expose end-to-end latency");
    } finally {
      engine.stop();
    }
  }

  @Test
  void engine_shouldContinueWhenTaskThrows() {
    ThrowingMetric failing = new ThrowingMetric();
    CountingMetric healthy = new CountingMetric();
    MetricsRefreshEngine engine = new MetricsRefreshEngine(20L, 64);
    engine.setTasks(List.of(
        new MetricsRefreshEngine.RefreshTask("failing", failing, false),
        new MetricsRefreshEngine.RefreshTask("healthy", healthy, false)
    ));

    try {
      engine.start();
      waitUntil(() -> healthy.pollCount.get() >= 2, 2_000L);
      assertTrue(healthy.pollCount.get() >= 2, "Worker must remain alive despite failing task");
      assertTrue(engine.errorCount() > 0L, "Failing task should increase error counter");
      assertTrue(engine.sinkFailureCount() > 0L, "Sink failure counter should increase");
      assertEquals(0L, engine.sinkRetryCount(), "Retry is disabled by default");
    } finally {
      engine.stop();
    }
  }

  @Test
  void engine_shouldTransitionModeWhenQueueIsOverloaded() {
    SlowMetric slow = new SlowMetric(120L);
    CountingMetric lowPriority = new CountingMetric();
    AtomicReference<TelemetryMode> lastMode = new AtomicReference<>(TelemetryMode.NORMAL);

    MetricsRefreshEngine engine = new MetricsRefreshEngine(
        5L,
        2,
        lastMode::set
    );
    engine.setTasks(List.of(
        new MetricsRefreshEngine.RefreshTask("slow-high", slow, false),
        new MetricsRefreshEngine.RefreshTask("low", lowPriority, true)
    ));

    try {
      engine.start();
      waitUntil(() -> engine.currentMode() != TelemetryMode.NORMAL, 3_000L);
      assertNotEquals(TelemetryMode.NORMAL, engine.currentMode(), "Overload should leave NORMAL mode");
      assertTrue(engine.droppedCount() > 0L, "Overload should produce dropped refresh tasks");
      assertEquals(engine.currentMode(), lastMode.get(), "Mode listener should track latest mode");
    } finally {
      engine.stop();
    }
  }

  @Test
  void engine_shouldKeepDispatchingAndDroppingWhenQueueIsSaturated() {
    SlowMetric slow = new SlowMetric(180L);
    MetricsRefreshEngine engine = new MetricsRefreshEngine(5L, 1);
    engine.setTasks(List.of(
        new MetricsRefreshEngine.RefreshTask("slow", slow, false)
    ));

    try {
      engine.start();
      waitUntil(() -> engine.droppedCount() > 0L, 3_000L);
      long droppedBefore = engine.droppedCount();
      waitUntil(() -> engine.droppedCount() > droppedBefore, 2_000L);

      assertTrue(droppedBefore > 0L, "Saturation should produce dropped tasks");
      assertTrue(engine.droppedCount() > droppedBefore, "Dispatcher should continue to drop while saturated");
      assertTrue(engine.queueFillRatio() >= 0.0 && engine.queueFillRatio() <= 1.0, "Queue fill ratio must stay bounded");
    } finally {
      engine.stop();
    }
  }

  @Test
  void engine_shouldTrackTaskStaleness() {
    CountingMetric metric = new CountingMetric();
    MetricsRefreshEngine engine = new MetricsRefreshEngine(20L, 64);
    engine.setTasks(List.of(new MetricsRefreshEngine.RefreshTask("probe", metric, false)));

    assertEquals(0L, engine.maxTaskStalenessMs(), "Staleness should be 0 before any poll");

    try {
      engine.start();
      waitUntil(() -> engine.sinkSuccessCount() >= 1, 2_000L);

      assertTrue(engine.maxTaskStalenessMs() >= 0L, "Staleness should be non-negative after a successful poll");
      assertTrue(engine.maxTaskStalenessMs() < 5_000L, "Staleness should be small while engine is running");
    } finally {
      engine.stop();
    }
  }

  @Test
  void engine_stalenessShouldRiseWhenBypassDropsAllTasks() {
    SlowMetric slow = new SlowMetric(200L);
    MetricsRefreshEngine engine = new MetricsRefreshEngine(5L, 1);
    engine.setTasks(List.of(new MetricsRefreshEngine.RefreshTask("slow", slow, false)));

    try {
      engine.start();
      waitUntil(() -> engine.sinkSuccessCount() >= 1, 2_000L);
      long stalenessBefore = engine.maxTaskStalenessMs();
      assertTrue(stalenessBefore >= 0L, "Staleness should be non-negative after first poll");

      waitUntil(() -> engine.droppedCount() > 5L, 3_000L);
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(300L));
      assertTrue(engine.maxTaskStalenessMs() >= stalenessBefore,
          "Staleness should not decrease while tasks are being dropped");
    } finally {
      engine.stop();
    }
  }

  @Test
  void engine_shouldRecoverBackToNormalAfterLoadDrops() {
    SlowMetric slow = new SlowMetric(120L);
    CountingMetric fast = new CountingMetric();
    MetricsRefreshEngine engine = new MetricsRefreshEngine(5L, 2);
    engine.setTasks(List.of(
        new MetricsRefreshEngine.RefreshTask("slow", slow, false),
        new MetricsRefreshEngine.RefreshTask("low", fast, true)
    ));

    try {
      engine.start();
      waitUntil(() -> engine.currentMode() != TelemetryMode.NORMAL, 3_000L);
      assertNotEquals(TelemetryMode.NORMAL, engine.currentMode(), "Engine should leave NORMAL under saturation");

      engine.setTasks(List.of(
          new MetricsRefreshEngine.RefreshTask("fast-only", fast, false)
      ));

      waitUntil(() -> engine.currentMode() == TelemetryMode.NORMAL, 3_000L);
      assertEquals(TelemetryMode.NORMAL, engine.currentMode(), "Engine should recover to NORMAL after pressure drops");
    } finally {
      engine.stop();
    }
  }

  private static void waitUntil(Check condition, long timeoutMs) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    while (System.nanoTime() < deadline) {
      if (condition.ok()) {
        return;
      }
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20L));
    }
  }

  @FunctionalInterface
  interface Check {
    boolean ok();
  }

  static class CountingMetric implements RefreshManagedMetric {
    final AtomicInteger pollCount = new AtomicInteger();

    @Override
    public void poll() {
      pollCount.incrementAndGet();
    }

    @Override
    public void setReadRefreshEnabled(boolean enabled) {
      // no-op for test double
    }
  }

  static class ThrowingMetric implements RefreshManagedMetric {
    @Override
    public void poll() {
      throw new RuntimeException("simulated failure");
    }

    @Override
    public void setReadRefreshEnabled(boolean enabled) {
      // no-op for test double
    }
  }

  static class SlowMetric implements RefreshManagedMetric {
    private final long sleepMs;

    SlowMetric(long sleepMs) {
      this.sleepMs = sleepMs;
    }

    @Override
    public void poll() {
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(sleepMs));
    }

    @Override
    public void setReadRefreshEnabled(boolean enabled) {
      // no-op for test double
    }
  }
}
