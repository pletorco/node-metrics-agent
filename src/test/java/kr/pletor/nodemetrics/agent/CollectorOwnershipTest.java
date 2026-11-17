package kr.pletor.nodemetrics.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kr.pletor.nodemetrics.metrics.RefreshManagedMetric;
import org.junit.jupiter.api.Test;

class CollectorOwnershipTest {
  static class Metric implements RefreshManagedMetric {
    final AtomicInteger polls = new AtomicInteger();

    @Override
    public void poll() {
      polls.incrementAndGet();
    }

    @Override
    public void setReadRefreshEnabled(boolean enabled) {}
  }

  static final class Hung extends Metric {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    @Override
    public void poll() {
      super.poll();
      entered.countDown();
      while (release.getCount() > 0) {
        try {
          release.await();
        } catch (InterruptedException ignored) {
          // Model a kernel read that cannot be cancelled. Every test releases it in finally.
        }
      }
    }
  }

  private static MetricsRefreshEngine.RefreshTask task(String name, Metric metric, boolean bg) {
    return new MetricsRefreshEngine.RefreshTask(name, metric, bg, 5L);
  }

  private static void await(java.util.function.BooleanSupplier condition, String message) {
    AsyncTestSupport.await(condition, 5_000L, message);
  }

  @Test
  void repeatedRemovalAndNewBeansCannotExhaustTheLaneAndRecoverWhenOldCallReturns() {
    MetricsRefreshEngine engine = new MetricsRefreshEngine(5L, 64);
    engine.setStuckThresholdMs(20L);
    List<Hung> generations = new ArrayList<>();
    Hung original = new Hung();
    generations.add(original);
    MetricsRefreshEngine.RefreshTask old = task("fs:/same", original, true);
    engine.setTasks(List.of(old));
    Metric healthy = new Metric();
    Metric memoryMap = new Metric();
    Metric critical = new Metric();
    try {
      engine.start();
      await(() -> original.polls.get() == 1, "original poll entered");
      for (int i = 0; i < 100; i++) {
        engine.setTasks(List.of());
        Hung replacement = new Hung();
        generations.add(replacement);
        engine.setTasks(
            List.of(
                task("fs:/same", replacement, true),
                task("fs:/healthy", healthy, true),
                task("memory-map", memoryMap, true),
                task("cpu", critical, false)));
      }
      await(
          () ->
              healthy.polls.get() >= 15
                  && memoryMap.polls.get() >= 15
                  && critical.polls.get() >= 15,
          "unrelated collectors progress");
      assertEquals(0, generations.stream().skip(1).mapToInt(m -> m.polls.get()).sum());
      assertEquals(List.of("fs:/same"), engine.stuckTaskNames());
      assertTrue(engine.maxTaskStalenessMs() > 0L);
      assertEquals(TelemetryMode.NORMAL, engine.currentMode());

      Hung latest = generations.get(generations.size() - 1);
      latest.release.countDown();
      original.release.countDown();
      await(() -> latest.polls.get() >= 2, "replacement resumes after retired call returns");
      await(() -> engine.stuckTaskNames().isEmpty(), "retired stuck diagnosis clears");
      engine.setTasks(List.of());
      await(() -> engine.runningTaskCount() == 0 && !old.pending.get(), "retired owner reclaimed");
    } finally {
      generations.forEach(m -> m.release.countDown());
      engine.close();
    }
  }

  @Test
  void removedQueuedCollectorIsSkippedAndItsPendingFlagIsCleared() {
    MetricsRefreshEngine engine = new MetricsRefreshEngine(5L, 64);
    List<Hung> blockers = new ArrayList<>();
    List<MetricsRefreshEngine.RefreshTask> tasks = new ArrayList<>();
    for (int i = 0; i < MetricsRefreshEngine.BACKGROUND_WORKERS; i++) {
      Hung hung = new Hung();
      blockers.add(hung);
      tasks.add(task("fs:/blocked-" + i, hung, true));
    }
    engine.setTasks(tasks);
    Metric removed = new Metric();
    Metric replacement = new Metric();
    MetricsRefreshEngine.RefreshTask queued = task("fs:/queued", removed, true);
    try {
      engine.start();
      await(() -> blockers.stream().allMatch(m -> m.polls.get() == 1), "background lane occupied");
      tasks.add(queued);
      engine.setTasks(tasks);
      await(() -> queued.pending.get() && engine.queueSize() > 0, "old task queued");
      engine.setTasks(List.of(task("fs:/queued", replacement, true)));
      blockers.forEach(m -> m.release.countDown());
      await(() -> replacement.polls.get() >= 2, "replacement polls after queue drains");
      await(() -> !queued.pending.get(), "removed queued task pending cleared");
      assertEquals(0, removed.polls.get(), "retired queued work must never run");
      assertTrue(engine.runningTaskCount() <= MetricsRefreshEngine.BACKGROUND_WORKERS);
    } finally {
      blockers.forEach(m -> m.release.countDown());
      engine.close();
    }
  }

  @Test
  void resourceOwnershipSurvivesLaneReplacement() {
    MetricsRefreshEngine engine = new MetricsRefreshEngine(5L, 64);
    Hung original = new Hung();
    Metric replacement = new Metric();
    Metric healthy = new Metric();
    engine.setTasks(List.of(task("same-resource", original, false)));
    try {
      engine.start();
      await(() -> original.polls.get() == 1, "critical poll entered");
      engine.setTasks(
          List.of(task("same-resource", replacement, true), task("healthy", healthy, false)));
      await(() -> healthy.polls.get() >= 15, "healthy critical work progresses");
      assertEquals(0, replacement.polls.get());
      original.release.countDown();
      await(() -> replacement.polls.get() >= 2, "ownership transfers across lanes");
    } finally {
      original.release.countDown();
      engine.close();
    }
  }

  @Test
  void distinctResourceChurnRetainsOnlyTheFixedNumberOfRunningOwners() {
    MetricsRefreshEngine engine = new MetricsRefreshEngine(5L, 16);
    engine.setStuckThresholdMs(20L);
    List<Hung> blockers = new ArrayList<>();
    List<MetricsRefreshEngine.RefreshTask> tasks = new ArrayList<>();
    int workers = MetricsRefreshEngine.CRITICAL_WORKERS + MetricsRefreshEngine.BACKGROUND_WORKERS;
    for (int i = 0; i < workers; i++) {
      Hung hung = new Hung();
      blockers.add(hung);
      tasks.add(task("blocked-" + i, hung, i >= MetricsRefreshEngine.CRITICAL_WORKERS));
    }
    engine.setTasks(tasks);
    try {
      engine.start();
      await(() -> blockers.stream().allMatch(m -> m.polls.get() == 1), "all workers entered");
      for (int i = 0; i < 2_000; i++) {
        engine.setTasks(List.of(task("new-resource-" + i, new Metric(), i % 2 == 0)));
      }
      assertEquals(workers, engine.runningTaskCount());
      await(() -> engine.stuckTaskNames().size() == workers, "all retired hangs remain visible");
      assertTrue(engine.queueSize() <= 32, "existing queue bounds include retired queued work");
      assertTrue(engine.maxTaskStalenessMs() > 0L);
      engine.setTasks(List.of());
      blockers.forEach(m -> m.release.countDown());
      await(
          () -> engine.runningTaskCount() == 0 && engine.queueSize() == 0, "all owners reclaimed");
      await(() -> tasks.stream().noneMatch(t -> t.pending.get()), "old pending flags clear");
      assertTrue(engine.stuckTaskNames().isEmpty());
      assertEquals(0L, engine.maxTaskStalenessMs());
    } finally {
      blockers.forEach(m -> m.release.countDown());
      engine.close();
    }
  }

  @Test
  void retiredFailureHistoryStaysVisibleDuringHungRetry() {
    AtomicReference<Throwable> error = new AtomicReference<>(new IOException("injected"));
    Hung gate = new Hung();
    Metric failing =
        new Metric() {
          @Override
          public void poll() {
            if (error.get() == null) {
              gate.poll();
            }
          }

          @Override
          public Throwable lastRefreshError() {
            return error.get();
          }
        };
    MetricsRefreshEngine engine = new MetricsRefreshEngine(5L, 16);
    engine.setTasks(List.of(task("retrying", failing, true)));
    try {
      engine.start();
      await(() -> engine.errorCount() > 0, "initial failure recorded");
      error.set(null);
      await(() -> gate.polls.get() == 1, "retry entered blocking call");
      engine.setTasks(List.of());
      assertEquals(List.of("retrying"), engine.failingTaskNames());
      gate.release.countDown();
      await(() -> engine.runningTaskCount() == 0, "retired retry completed");
      assertTrue(engine.failingTaskNames().isEmpty());
    } finally {
      gate.release.countDown();
      engine.close();
    }
  }
}
