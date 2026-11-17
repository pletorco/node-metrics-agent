package kr.pletor.nodemetrics.agent;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import kr.pletor.nodemetrics.metrics.RefreshManagedMetric;

/** Synthetic worker execution and diagnostic-read costs; never shipped in the agent. */
public final class OwnershipBenchmark {
  private OwnershipBenchmark() {}

  public static void main(String[] args) throws Exception {
    String mode = args[0];
    int count = Integer.parseInt(args[1]);
    AtomicLong polls = new AtomicLong();
    RefreshManagedMetric metric =
        new RefreshManagedMetric() {
          @Override
          public void poll() {
            polls.incrementAndGet();
          }

          @Override
          public void setReadRefreshEnabled(boolean enabled) {}
        };
    MetricsRefreshEngine engine = new MetricsRefreshEngine(5L, 1024);
    List<MetricsRefreshEngine.RefreshTask> tasks = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      tasks.add(new MetricsRefreshEngine.RefreshTask("resource-" + i, metric, i % 2 == 0));
    }
    engine.setTasks(tasks);
    Class<?> queued = Class.forName(MetricsRefreshEngine.class.getName() + "$QueuedTask");
    Constructor<?> constructor =
        queued.getDeclaredConstructor(MetricsRefreshEngine.RefreshTask.class, long.class);
    constructor.setAccessible(true);
    Method process = MetricsRefreshEngine.class.getDeclaredMethod("processQueuedTask", queued);
    process.setAccessible(true);
    Object[] work = new Object[count];
    for (int i = 0; i < count; i++) {
      work[i] = constructor.newInstance(tasks.get(i), System.nanoTime());
    }
    AgentObservabilityMetrics obs =
        new AgentObservabilityMetrics(() -> engine, engine::currentMode, () -> 0L);
    int warmup = 20_000;
    int samples = 50_000;
    com.sun.management.ThreadMXBean bean =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    bean.setThreadAllocatedMemoryEnabled(true);
    long[] times = new long[samples];
    long id = Thread.currentThread().getId();
    long cpu = 0L;
    long bytes = 0L;
    long checksum = 0L;
    for (int i = -warmup; i < samples; i++) {
      if (i == 0) {
        cpu = bean.getCurrentThreadCpuTime();
        bytes = bean.getThreadAllocatedBytes(id);
      }
      long start = System.nanoTime();
      if (mode.equals("poll")) {
        process.invoke(engine, work[Math.floorMod(i, count)]);
      } else {
        checksum +=
            obs.getStuckTaskCount() + obs.getMaxTaskStalenessMs() + obs.getFailingTaskCount();
      }
      if (i >= 0) {
        times[i] = System.nanoTime() - start;
      }
    }
    cpu = bean.getCurrentThreadCpuTime() - cpu;
    bytes = bean.getThreadAllocatedBytes(id) - bytes;
    Arrays.sort(times);
    System.out.printf(
        "{\"mode\":\"%s\",\"tasks\":%d,\"cpuNs\":%.1f,\"bytes\":%.1f,\"p99Ns\":%d}%n",
        mode, count, cpu / (double) samples, bytes / (double) samples, times[samples * 99 / 100]);
    if ((mode.equals("poll") && polls.get() != warmup + samples) || checksum < 0) {
      throw new AssertionError("benchmark did not exercise expected work");
    }
  }
}
