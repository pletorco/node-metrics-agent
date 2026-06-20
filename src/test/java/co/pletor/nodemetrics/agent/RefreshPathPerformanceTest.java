package co.pletor.nodemetrics.agent;

import co.pletor.nodemetrics.metrics.RefreshManagedMetric;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RefreshPathPerformanceTest {

  @Test
  void asyncRefresh_shouldReduceHotPathP99CpuAndAllocation() {
    ProbeMetric metric = new ProbeMetric(250_000, 8_192);

    Measurement baseline = measure(metric::readSnapshot, 200, 30);

    metric.setReadRefreshEnabled(false);
    MetricsRefreshEngine engine = new MetricsRefreshEngine(5L, 64);
    engine.setTasks(List.of(
        new MetricsRefreshEngine.RefreshTask("probe", metric, false)
    ));

    try {
      long baselinePolls = metric.pollCount();
      engine.start();
      waitUntil(() -> metric.pollCount() > baselinePolls + 5L, 2_000L);

      Measurement async = measure(metric::readSnapshot, 200, 30);

      assertTrue(
          async.p99Nanos < baseline.p99Nanos,
          "Async path should improve p99. baseline=" + baseline.p99Nanos + "ns, async=" + async.p99Nanos + "ns"
      );

      if (baseline.cpuNanosPerCall >= 0L && async.cpuNanosPerCall >= 0L) {
        assertTrue(
            async.cpuNanosPerCall < baseline.cpuNanosPerCall,
            "Async path should reduce caller CPU cost. baseline=" + baseline.cpuNanosPerCall
                + "ns, async=" + async.cpuNanosPerCall + "ns"
        );
      }

      if (baseline.allocatedBytesPerCall >= 0L && async.allocatedBytesPerCall >= 0L) {
        assertTrue(
            async.allocatedBytesPerCall < baseline.allocatedBytesPerCall,
            "Async path should reduce caller allocation. baseline=" + baseline.allocatedBytesPerCall
                + "B, async=" + async.allocatedBytesPerCall + "B"
        );
      }
    } finally {
      engine.stop();
    }
  }

  private static Measurement measure(Supplier<Long> readOp, int iterations, int warmupIterations) {
    for (int i = 0; i < warmupIterations; i++) {
      readOp.get();
    }

    long[] latencies = new long[iterations];
    long allocBefore = currentThreadAllocatedBytes();
    long cpuBefore = currentThreadCpuNanos();

    for (int i = 0; i < iterations; i++) {
      long start = System.nanoTime();
      readOp.get();
      latencies[i] = System.nanoTime() - start;
    }

    long allocAfter = currentThreadAllocatedBytes();
    long cpuAfter = currentThreadCpuNanos();

    Arrays.sort(latencies);
    int p99Index = Math.max(0, (int) Math.ceil(iterations * 0.99) - 1);
    long p99 = latencies[p99Index];

    long allocatedBytesPerCall = -1L;
    if (allocBefore >= 0L && allocAfter >= allocBefore) {
      allocatedBytesPerCall = (allocAfter - allocBefore) / iterations;
    }

    long cpuNanosPerCall = -1L;
    if (cpuBefore >= 0L && cpuAfter >= cpuBefore) {
      cpuNanosPerCall = (cpuAfter - cpuBefore) / iterations;
    }

    return new Measurement(p99, cpuNanosPerCall, allocatedBytesPerCall);
  }

  private static long currentThreadCpuNanos() {
    java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
    if (!bean.isCurrentThreadCpuTimeSupported()) {
      return -1L;
    }
    try {
      if (!bean.isThreadCpuTimeEnabled()) {
        bean.setThreadCpuTimeEnabled(true);
      }
      return bean.getCurrentThreadCpuTime();
    } catch (RuntimeException ignored) {
      return -1L;
    }
  }

  @SuppressWarnings("deprecation")
  private static long currentThreadAllocatedBytes() {
    java.lang.management.ThreadMXBean base = ManagementFactory.getThreadMXBean();
    if (!(base instanceof com.sun.management.ThreadMXBean)) {
      return -1L;
    }
    com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) base;
    try {
      if (!bean.isThreadAllocatedMemorySupported()) {
        return -1L;
      }
      if (!bean.isThreadAllocatedMemoryEnabled()) {
        bean.setThreadAllocatedMemoryEnabled(true);
      }
      return bean.getThreadAllocatedBytes(Thread.currentThread().getId());
    } catch (RuntimeException ignored) {
      return -1L;
    }
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

  private static final class Measurement {
    private final long p99Nanos;
    private final long cpuNanosPerCall;
    private final long allocatedBytesPerCall;

    private Measurement(long p99Nanos, long cpuNanosPerCall, long allocatedBytesPerCall) {
      this.p99Nanos = p99Nanos;
      this.cpuNanosPerCall = cpuNanosPerCall;
      this.allocatedBytesPerCall = allocatedBytesPerCall;
    }
  }

  private static final class ProbeMetric implements RefreshManagedMetric {
    private final int workIterations;
    private final int allocationBytes;
    private final AtomicLong pollCount = new AtomicLong(0L);
    private volatile boolean readRefreshEnabled = true;
    private volatile long snapshot = 0L;

    private ProbeMetric(int workIterations, int allocationBytes) {
      this.workIterations = workIterations;
      this.allocationBytes = allocationBytes;
    }

    @Override
    public void poll() {
      long acc = 0L;
      for (int i = 0; i < workIterations; i++) {
        acc += i;
      }
      byte[] scratch = new byte[allocationBytes];
      scratch[0] = (byte) (acc & 0xFF);
      snapshot = acc + scratch[0];
      pollCount.incrementAndGet();
    }

    @Override
    public void setReadRefreshEnabled(boolean enabled) {
      readRefreshEnabled = enabled;
    }

    private long readSnapshot() {
      if (readRefreshEnabled) {
        poll();
      }
      return snapshot;
    }

    private long pollCount() {
      return pollCount.get();
    }
  }
}
