package kr.pletor.nodemetrics.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import kr.pletor.nodemetrics.metrics.RefreshManagedMetric;

/** Exercises packaged scheduler code with a synthetic uninterruptible read in a disposable JVM. */
public final class CollectorOwnershipProbe {
  private CollectorOwnershipProbe() {}

  private static final class Metric implements RefreshManagedMetric {
    final AtomicInteger polls = new AtomicInteger();
    final CountDownLatch release;

    Metric(boolean block) {
      release = new CountDownLatch(block ? 1 : 0);
    }

    @Override
    public void poll() {
      polls.incrementAndGet();
      while (release.getCount() > 0) {
        try {
          release.await();
        } catch (InterruptedException ignored) {
          // Fault injection only. The parent process enforces the final timeout.
        }
      }
    }

    @Override
    public void setReadRefreshEnabled(boolean enabled) {}
  }

  public static void main(String[] args) {
    MetricsRefreshEngine engine = new MetricsRefreshEngine(5L, 64);
    engine.setStuckThresholdMs(20L);
    List<Metric> generations = new ArrayList<>();
    try {
      Metric first = new Metric(true);
      generations.add(first);
      engine.setTasks(List.of(task("fs:/same", first, true)));
      engine.start();
      await(() -> first.polls.get() == 1, "first collector did not enter");
      Metric healthy = new Metric(false);
      Metric memoryMap = new Metric(false);
      Metric critical = new Metric(false);
      for (int i = 0; i < 8; i++) {
        engine.setTasks(List.of());
        // Removing the MBean must not remove its outstanding call from diagnostics.
        await(() -> engine.stuckTaskNames().contains("fs:/same"), "retired hang missing");
        Metric replacement = new Metric(true);
        generations.add(replacement);
        engine.setTasks(
            List.of(
                task("fs:/same", replacement, true),
                task("fs:/healthy", healthy, true),
                task("memory-map", memoryMap, true),
                task("cpu", critical, false)));
        final int target = healthy.polls.get() + 2;
        await(() -> healthy.polls.get() >= target, "healthy background stopped");
      }
      require(
          generations.stream().skip(1).noneMatch(m -> m.polls.get() > 0),
          "overlapping generations");
      await(
          () -> memoryMap.polls.get() >= 2 && critical.polls.get() >= 2, "isolated lanes stopped");
      require(engine.maxTaskStalenessMs() > 0, "retired staleness hidden");
      Metric latest = generations.get(generations.size() - 1);
      latest.release.countDown();
      first.release.countDown();
      await(() -> latest.polls.get() >= 2 && engine.stuckTaskNames().isEmpty(), "recovery failed");
      System.out.println("OWNERSHIP SAFE: churn and recovery; host work completed");
    } catch (Throwable failure) {
      System.out.println("OWNERSHIP FAILURE: " + failure.getMessage());
      System.exit(1);
    } finally {
      generations.forEach(m -> m.release.countDown());
      engine.close();
    }
  }

  private static MetricsRefreshEngine.RefreshTask task(String name, Metric metric, boolean bg) {
    return new MetricsRefreshEngine.RefreshTask(name, metric, bg, 5L);
  }

  private static void await(BooleanSupplier condition, String message) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean()) {
      require(System.nanoTime() < deadline, message);
      Thread.sleep(10L);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
