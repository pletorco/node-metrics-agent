package kr.pletor.nodemetrics.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import kr.pletor.nodemetrics.metrics.RefreshManagedMetric;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CombinedFailureRecoveryTest {
  @TempDir Path directory;

  public interface SnapshotMBean {
    long getValue();
  }

  static final class Snapshot implements SnapshotMBean, RefreshManagedMetric {
    final AtomicLong value = new AtomicLong();

    @Override
    public long getValue() {
      return value.get();
    }

    @Override
    public void poll() {
      value.incrementAndGet();
    }

    @Override
    public void setReadRefreshEnabled(boolean enabled) {}
  }

  static final class HungCollector implements RefreshManagedMetric {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicInteger concurrent = new AtomicInteger();
    final AtomicInteger maxConcurrent = new AtomicInteger();

    @Override
    public void poll() {
      maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
      entered.countDown();
      boolean interrupted = false;
      try {
        while (release.getCount() != 0L) {
          try {
            release.await();
          } catch (InterruptedException ignored) {
            interrupted = true; // Model a kernel call that cannot be cancelled.
          }
        }
      } finally {
        concurrent.decrementAndGet();
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }

    @Override
    public void setReadRefreshEnabled(boolean enabled) {}
  }

  @Test
  void hungCollectorsRejectedReloadsAndConcurrentJmxReadsStayBoundedAndRecover() throws Exception {
    HungCollector critical = new HungCollector();
    HungCollector background = new HungCollector();
    Snapshot snapshot = new Snapshot();
    AtomicBoolean fail = new AtomicBoolean(true);
    RefreshManagedMetric failing =
        new RefreshManagedMetric() {
          @Override
          public void poll() {
            if (fail.get()) {
              throw new IllegalStateException("collector temporarily unavailable");
            }
          }

          @Override
          public void setReadRefreshEnabled(boolean enabled) {}
        };
    int capacity = 4;
    MetricsRefreshEngine engine = new MetricsRefreshEngine(5L, capacity);
    engine.setStuckThresholdMs(1L);
    AtomicReference<Config> current = new AtomicReference<>();
    AtomicInteger applications = new AtomicInteger();
    Path config = directory.resolve("config.yml");
    ConfigReloader reloader =
        new ConfigReloader(
            config,
            next -> {
              // Short test cadences exercise the same schedule changes without multi-second sleeps.
              long cadence = next.refreshIntervalSeconds * 10L;
              engine.setTasks(
                  List.of(
                      new MetricsRefreshEngine.RefreshTask("hung-cpu", critical, false, cadence),
                      new MetricsRefreshEngine.RefreshTask("hung-fs", background, true, cadence),
                      new MetricsRefreshEngine.RefreshTask("snapshot", snapshot, false, 5L),
                      new MetricsRefreshEngine.RefreshTask("failing", failing, false, 5L)));
              current.set(next);
              applications.incrementAndGet();
            });
    Method check = ConfigReloader.class.getDeclaredMethod("checkAndReloadIfChanged");
    check.setAccessible(true);
    long mtime = System.currentTimeMillis();
    rewrite(config, "refresh_interval_seconds: 1\n", ++mtime);
    check.invoke(reloader);

    MBeanServer server = MBeanServerFactory.newMBeanServer();
    ObjectName snapshotName = new ObjectName("test:type=Snapshot");
    ObjectName healthName = new ObjectName("test:type=AgentHealth");
    server.registerMBean(new StandardMBean(snapshot, SnapshotMBean.class), snapshotName);
    server.registerMBean(
        new AgentObservabilityMetrics(() -> engine, engine::currentMode, () -> 0L), healthName);
    Set<Thread> before = engineThreads();
    ExecutorService callers = Executors.newFixedThreadPool(3);
    AtomicBoolean reading = new AtomicBoolean(true);
    AtomicLong reads = new AtomicLong();
    List<Future<?>> readers = new ArrayList<>();
    Set<Thread> owned = Set.of();
    try {
      engine.start();
      owned = engineThreads();
      owned.removeAll(before);
      assertTrue(
          owned.size()
              <= 1
                  + MetricsRefreshEngine.CRITICAL_WORKERS
                  + MetricsRefreshEngine.BACKGROUND_WORKERS);
      assertTrue(owned.stream().allMatch(Thread::isDaemon));
      assertTrue(critical.entered.await(5, TimeUnit.SECONDS));
      assertTrue(background.entered.await(5, TimeUnit.SECONDS));
      AsyncTestSupport.await(() -> engine.errorCount() > 0L, 5_000L, "collector failure recorded");
      for (int i = 0; i < 2; i++) {
        readers.add(
            callers.submit(
                () -> {
                  while (reading.get()) {
                    // Exporters use bulk getAttributes; ordinary JMX clients also use getAttribute.
                    assertEquals(
                        1, server.getAttributes(snapshotName, new String[] {"Value"}).size());
                    assertTrue((Long) server.getAttribute(snapshotName, "Value") >= 0L);
                    int queued = (Integer) server.getAttribute(healthName, "QueueDepth");
                    assertTrue(queued >= 0 && queued <= capacity * 2);
                    reads.incrementAndGet();
                  }
                  return null;
                }));
      }
      Future<Long> host = callers.submit(() -> java.util.stream.LongStream.range(0, 100_000).sum());
      assertEquals(4_999_950_000L, host.get(5, TimeUnit.SECONDS));

      for (int interval = 2; interval <= 4; interval++) {
        Config previous = current.get();
        rewrite(config, "#" + "x".repeat(ConfigLoader.MAX_CONFIG_BYTES), ++mtime);
        check.invoke(reloader);
        assertTrue(previous == current.get(), "Rejected reload keeps the exact last good config");
        long polls = snapshot.value.get();
        long scraped = reads.get();
        rewrite(config, "refresh_interval_seconds: " + interval + "\n", ++mtime);
        check.invoke(reloader);
        AsyncTestSupport.await(
            () -> snapshot.value.get() >= polls + 5 && reads.get() >= scraped + 10,
            5_000L,
            "host metrics and concurrent scrapes progress while collectors are hung");
        assertEquals(1, critical.maxConcurrent.get(), "Reload must not duplicate a hung task");
        assertEquals(1, background.maxConcurrent.get());
      }
      assertEquals(4, applications.get());
      assertEquals(4, current.get().refreshIntervalSeconds);
      critical.release.countDown();
      background.release.countDown();
      fail.set(false);
      long polls = snapshot.value.get();
      AsyncTestSupport.await(
          () ->
              engine.stuckTaskNames().isEmpty()
                  && engine.failingTaskNames().isEmpty()
                  && snapshot.value.get() >= polls + 5,
          5_000L,
          "collectors and failure state recover");
      assertEquals(TelemetryMode.NORMAL, engine.currentMode());
    } finally {
      reading.set(false);
      critical.release.countDown();
      background.release.countDown();
      reloader.stop();
      engine.stop();
      callers.shutdownNow();
      assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
      for (Thread thread : owned) {
        thread.join(5_000L);
        assertFalse(thread.isAlive(), "Test must not leak a worker: " + thread.getName());
      }
    }
    for (Future<?> reader : readers) {
      reader.get(5, TimeUnit.SECONDS); // Propagate failures from concurrent callers.
    }
  }

  private static void rewrite(Path path, String yaml, long mtime) throws Exception {
    Files.writeString(path, yaml);
    Files.setLastModifiedTime(path, FileTime.fromMillis(mtime));
  }

  private static Set<Thread> engineThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(thread -> thread.getName().startsWith("node-metrics-refresh-"))
        .collect(Collectors.toSet());
  }
}
