package kr.pletor.nodemetrics.agent;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kr.pletor.nodemetrics.metrics.RefreshManagedMetric;

/** Initializer-thread startup cost, with bounded teardown between fresh engine instances. */
public final class StartupBenchmark {
  private StartupBenchmark() {}

  public static void main(String[] args) throws Exception {
    com.sun.management.ThreadMXBean bean =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    bean.setThreadAllocatedMemoryEnabled(true);
    long id = Thread.currentThread().getId();
    int samples = 50;
    long[] times = new long[samples];
    long cpu = 0L;
    long bytes = 0L;
    for (int i = -10; i < samples; i++) {
      AtomicInteger polls = new AtomicInteger();
      RefreshManagedMetric metric =
          new RefreshManagedMetric() {
            @Override
            public void poll() {
              polls.incrementAndGet();
            }

            @Override
            public void setReadRefreshEnabled(boolean enabled) {}
          };
      long beforeBytes = bean.getThreadAllocatedBytes(id);
      long beforeCpu = bean.getCurrentThreadCpuTime();
      long before = System.nanoTime();
      MetricsRefreshEngine engine = new MetricsRefreshEngine(5L, 16);
      engine.setTasks(List.of(new MetricsRefreshEngine.RefreshTask("probe", metric, false)));
      engine.start();
      long elapsed = System.nanoTime() - before;
      long usedCpu = bean.getCurrentThreadCpuTime() - beforeCpu;
      long usedBytes = bean.getThreadAllocatedBytes(id) - beforeBytes;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (engine.processedCount() == 0L) {
        if (System.nanoTime() > deadline) {
          throw new AssertionError("successful startup did not poll");
        }
        Thread.sleep(1L);
      }
      engine.close();
      while (Thread.getAllStackTraces().keySet().stream()
          .anyMatch(thread -> thread.getName().startsWith("node-metrics-refresh-"))) {
        if (System.nanoTime() > deadline) {
          throw new AssertionError("engine did not stop");
        }
        Thread.sleep(1L);
      }
      if (i >= 0) {
        times[i] = elapsed;
        cpu += usedCpu;
        bytes += usedBytes;
      }
    }
    Arrays.sort(times);
    System.out.printf(
        "{\"cpuNs\":%.1f,\"bytes\":%.1f,\"p99Ns\":%d}%n",
        cpu / (double) samples, bytes / (double) samples, times[samples * 99 / 100]);
  }
}
