package co.pletor.nodemetrics.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.pletor.nodemetrics.metrics.RefreshManagedMetric;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

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
    engine.setTasks(
        List.of(
            new MetricsRefreshEngine.RefreshTask("failing", failing, false),
            new MetricsRefreshEngine.RefreshTask("healthy", healthy, false)));

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

    MetricsRefreshEngine engine = new MetricsRefreshEngine(5L, 2, lastMode::set);
    engine.setTasks(
        List.of(
            new MetricsRefreshEngine.RefreshTask("slow-high", slow, false),
            new MetricsRefreshEngine.RefreshTask("low", lowPriority, true)));

    try {
      engine.start();
      waitUntil(() -> engine.currentMode() != TelemetryMode.NORMAL, 3_000L);
      assertNotEquals(
          TelemetryMode.NORMAL, engine.currentMode(), "Overload should leave NORMAL mode");
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
    engine.setTasks(List.of(new MetricsRefreshEngine.RefreshTask("slow", slow, false)));

    try {
      engine.start();
      waitUntil(() -> engine.droppedCount() > 0L, 3_000L);
      long droppedBefore = engine.droppedCount();
      waitUntil(() -> engine.droppedCount() > droppedBefore, 2_000L);

      assertTrue(droppedBefore > 0L, "Saturation should produce dropped tasks");
      assertTrue(
          engine.droppedCount() > droppedBefore,
          "Dispatcher should continue to drop while saturated");
      assertTrue(
          engine.queueFillRatio() >= 0.0 && engine.queueFillRatio() <= 1.0,
          "Queue fill ratio must stay bounded");
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

      assertTrue(
          engine.maxTaskStalenessMs() >= 0L,
          "Staleness should be non-negative after a successful poll");
      assertTrue(
          engine.maxTaskStalenessMs() < 5_000L,
          "Staleness should be small while engine is running");
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
      assertTrue(
          engine.maxTaskStalenessMs() >= stalenessBefore,
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
    engine.setTasks(
        List.of(
            new MetricsRefreshEngine.RefreshTask("slow", slow, false),
            new MetricsRefreshEngine.RefreshTask("low", fast, true)));

    try {
      engine.start();
      waitUntil(() -> engine.currentMode() != TelemetryMode.NORMAL, 3_000L);
      assertNotEquals(
          TelemetryMode.NORMAL,
          engine.currentMode(),
          "Engine should leave NORMAL under saturation");

      engine.setTasks(List.of(new MetricsRefreshEngine.RefreshTask("fast-only", fast, false)));

      waitUntil(() -> engine.currentMode() == TelemetryMode.NORMAL, 3_000L);
      assertEquals(
          TelemetryMode.NORMAL,
          engine.currentMode(),
          "Engine should recover to NORMAL after pressure drops");
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

  @Test
  void engine_shouldRecoverToNormalWhileLowPriorityTasksRemainRegistered() {
    java.util.concurrent.atomic.AtomicBoolean blocked =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    RefreshManagedMetric blocking =
        new RefreshManagedMetric() {
          @Override
          public void poll() {
            while (blocked.get()) {
              LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5L));
            }
          }

          @Override
          public void setReadRefreshEnabled(boolean enabled) {
            // no-op for test double
          }
        };
    CountingMetric fast = new CountingMetric();
    CountingMetric fs = new CountingMetric();
    MetricsRefreshEngine engine = new MetricsRefreshEngine(5L, 4);
    engine.setTasks(
        List.of(
            new MetricsRefreshEngine.RefreshTask("blocking", blocking, false),
            new MetricsRefreshEngine.RefreshTask("fast", fast, false),
            new MetricsRefreshEngine.RefreshTask("fs:/", fs, true)));

    try {
      engine.start();
      waitUntil(() -> engine.currentMode() != TelemetryMode.NORMAL, 3_000L);
      assertNotEquals(
          TelemetryMode.NORMAL,
          engine.currentMode(),
          "Engine should leave NORMAL under saturation");

      blocked.set(false);

      waitUntil(() -> engine.currentMode() == TelemetryMode.NORMAL, 3_000L);
      assertEquals(
          TelemetryMode.NORMAL,
          engine.currentMode(),
          "Intentional low-priority drops must not keep the engine in DEGRADED");
      int before = fs.pollCount.get();
      waitUntil(() -> fs.pollCount.get() > before, 3_000L);
      assertTrue(
          fs.pollCount.get() > before, "Low-priority task must be polled again after recovery");
    } finally {
      blocked.set(false);
      engine.stop();
    }
  }

  /**
   * Metric that absorbs failures like the real beans: poll() never throws, the error is reported.
   */
  static class AbsorbingMetric implements RefreshManagedMetric {
    final AtomicInteger pollCount = new AtomicInteger();
    final AtomicReference<Throwable> error = new AtomicReference<>();

    @Override
    public void poll() {
      pollCount.incrementAndGet();
    }

    @Override
    public void setReadRefreshEnabled(boolean enabled) {
      // no-op for test double
    }

    @Override
    public Throwable lastRefreshError() {
      return error.get();
    }
  }

  @Test
  void engine_shouldCountAbsorbedRefreshFailuresAndRecover() {
    AbsorbingMetric metric = new AbsorbingMetric();
    metric.error.set(new java.io.IOException("cannot read /proc/stat"));
    MetricsRefreshEngine engine = new MetricsRefreshEngine(5L, 64);
    engine.setTasks(List.of(new MetricsRefreshEngine.RefreshTask("cpu", metric, false)));

    try {
      engine.start();
      waitUntil(() -> engine.sinkFailureCount() >= 3, 2_000L);
      assertTrue(engine.sinkFailureCount() >= 3L, "Absorbed failures must be counted");
      assertEquals(0L, engine.sinkSuccessCount(), "A failed refresh is not a success");
      assertEquals(List.of("cpu"), engine.failingTaskNames());

      metric.error.set(null);
      waitUntil(() -> engine.failingTaskNames().isEmpty(), 2_000L);
      assertTrue(
          engine.failingTaskNames().isEmpty(), "Task should be healthy again after a clean poll");
      assertTrue(engine.sinkSuccessCount() > 0L);
    } finally {
      engine.stop();
    }
  }

  @Test
  void engine_shouldHonourPerTaskRefreshInterval() {
    CountingMetric fast = new CountingMetric();
    CountingMetric slow = new CountingMetric();
    MetricsRefreshEngine engine = new MetricsRefreshEngine(5L, 256);
    engine.setTasks(
        List.of(
            new MetricsRefreshEngine.RefreshTask("fast", fast, false),
            new MetricsRefreshEngine.RefreshTask("slow", slow, false, 60_000L)));

    try {
      engine.start();
      waitUntil(() -> fast.pollCount.get() >= 20, 3_000L);
      assertTrue(fast.pollCount.get() >= 20, "Interval-less task runs every dispatch cycle");
      assertEquals(1, slow.pollCount.get(), "Task with a long interval runs once, immediately");
    } finally {
      engine.stop();
    }
  }

  @Test
  void engine_stalenessShouldBeRelativeToTaskInterval() {
    CountingMetric slow = new CountingMetric();
    MetricsRefreshEngine engine = new MetricsRefreshEngine(5L, 64);
    engine.setTasks(List.of(new MetricsRefreshEngine.RefreshTask("slow", slow, false, 60_000L)));

    try {
      engine.start();
      waitUntil(() -> engine.sinkSuccessCount() >= 1, 2_000L);
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100L));
      assertEquals(
          0L,
          engine.maxTaskStalenessMs(),
          "A task refreshed within its own interval is not stale, however long the interval");
    } finally {
      engine.stop();
    }
  }

  @Test
  void engine_stalenessShouldRiseForTaskThatNeverSucceeds() {
    AbsorbingMetric metric = new AbsorbingMetric();
    metric.error.set(new java.io.IOException("boom"));
    MetricsRefreshEngine engine = new MetricsRefreshEngine(5L, 64);
    engine.setTasks(List.of(new MetricsRefreshEngine.RefreshTask("broken", metric, false)));

    try {
      engine.start();
      waitUntil(() -> engine.sinkFailureCount() >= 1, 2_000L);
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(150L));
      assertTrue(
          engine.maxTaskStalenessMs() >= 100L,
          "A task that keeps failing from the start must show up as stale");
    } finally {
      engine.stop();
    }
  }

  @Test
  void engine_setTasksShouldKeepStateOfUnchangedTasks() {
    AbsorbingMetric metric = new AbsorbingMetric();
    metric.error.set(new java.io.IOException("boom"));
    MetricsRefreshEngine engine = new MetricsRefreshEngine(5L, 64);
    engine.setTasks(List.of(new MetricsRefreshEngine.RefreshTask("broken", metric, false)));

    try {
      engine.start();
      waitUntil(() -> !engine.failingTaskNames().isEmpty(), 2_000L);

      // Same task re-registered (as on every config reload) keeps its failure history.
      engine.setTasks(List.of(new MetricsRefreshEngine.RefreshTask("broken", metric, false)));
      assertEquals(List.of("broken"), engine.failingTaskNames());
    } finally {
      engine.stop();
    }
  }

  /** Metric whose poll() blocks like a statvfs on a dead NFS mount: it ignores interrupts. */
  static class HangingMetric implements RefreshManagedMetric {
    final AtomicInteger pollCount = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean released =
        new java.util.concurrent.atomic.AtomicBoolean();

    @Override
    public void poll() {
      pollCount.incrementAndGet();
      while (!released.get()) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5L));
        Thread.interrupted(); // uninterruptible I/O does not react to interrupts
      }
    }

    void release() {
      released.set(true);
    }

    @Override
    public void setReadRefreshEnabled(boolean enabled) {
      // no-op for test double
    }
  }

  @Test
  void engine_hungBackgroundTaskShouldNotBlockCriticalOrOtherBackgroundTasks() {
    HangingMetric hung = new HangingMetric();
    CountingMetric otherFs = new CountingMetric();
    CountingMetric critical = new CountingMetric();
    MetricsRefreshEngine engine = new MetricsRefreshEngine(5L, 64);
    engine.setStuckThresholdMs(100L);
    engine.setTasks(
        List.of(
            new MetricsRefreshEngine.RefreshTask("cpu", critical, false),
            new MetricsRefreshEngine.RefreshTask("fs:/dead-nfs", hung, true),
            new MetricsRefreshEngine.RefreshTask("fs:/data", otherFs, true)));

    try {
      engine.start();
      waitUntil(() -> hung.pollCount.get() >= 1, 2_000L);
      int criticalBefore = critical.pollCount.get();
      int otherFsBefore = otherFs.pollCount.get();

      waitUntil(
          () ->
              critical.pollCount.get() >= criticalBefore + 20
                  && otherFs.pollCount.get() >= otherFsBefore + 20,
          3_000L);
      assertTrue(
          critical.pollCount.get() >= criticalBefore + 20, "Critical metrics must keep refreshing");
      assertTrue(
          otherFs.pollCount.get() >= otherFsBefore + 20, "Other filesystems must keep refreshing");
      assertEquals(1, hung.pollCount.get(), "A hung task must not be enqueued on top of itself");
      assertEquals(
          TelemetryMode.NORMAL,
          engine.currentMode(),
          "A hung filesystem must not trigger overload modes");
      assertTrue(engine.queueSize() <= 3, "Queues must not fill up behind a hung task");

      waitUntil(() -> !engine.stuckTaskNames().isEmpty(), 2_000L);
      assertEquals(List.of("fs:/dead-nfs"), engine.stuckTaskNames());
    } finally {
      hung.release();
      engine.stop();
    }
  }

  @Test
  void engine_hungTaskShouldRecoverOnceTheCallReturns() {
    HangingMetric hung = new HangingMetric();
    MetricsRefreshEngine engine = new MetricsRefreshEngine(5L, 64);
    engine.setStuckThresholdMs(50L);
    engine.setTasks(List.of(new MetricsRefreshEngine.RefreshTask("fs:/dead-nfs", hung, true, 20L)));

    try {
      engine.start();
      waitUntil(() -> !engine.stuckTaskNames().isEmpty(), 2_000L);
      assertEquals(List.of("fs:/dead-nfs"), engine.stuckTaskNames());

      hung.release();
      waitUntil(() -> hung.pollCount.get() >= 3 && engine.stuckTaskNames().isEmpty(), 3_000L);
      assertTrue(hung.pollCount.get() >= 3, "Task must be scheduled again after the hang ends");
      assertTrue(engine.stuckTaskNames().isEmpty(), "Stuck flag must clear");
    } finally {
      hung.release();
      engine.stop();
    }
  }

  @Test
  void engine_shouldCountSkippedRunsOfAHungTaskAsDropped() {
    HangingMetric hung = new HangingMetric();
    MetricsRefreshEngine engine = new MetricsRefreshEngine(5L, 64);
    engine.setTasks(List.of(new MetricsRefreshEngine.RefreshTask("fs:/dead-nfs", hung, true, 20L)));

    try {
      engine.start();
      waitUntil(() -> engine.droppedCount() >= 3, 3_000L);
      assertTrue(engine.droppedCount() >= 3L, "Skipped runs must be visible in DroppedCount");
      assertEquals(1, hung.pollCount.get());
    } finally {
      hung.release();
      engine.stop();
    }
  }
}
