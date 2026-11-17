package kr.pletor.nodemetrics.metrics;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.Arrays;

/** Worker poll/resolution and cached getter costs, with a fixed synthetic cgroup hierarchy. */
public final class MigrationBenchmark {
  private MigrationBenchmark() {}

  public static void main(String[] args) throws Exception {
    String mode = args[0];
    Path root = Path.of(args[1]);
    MigrationFixture fixture = new MigrationFixture(root, false);
    var fields = fixture.cacheFields();
    var bindings = fixture.bindings();
    Runnable operation =
        () -> {
          try {
            if (mode.equals("resolve")) {
              for (int i = 0; i < fields.length; i++) {
                fields[i].set(bindings[i], null);
              }
            }
            if (mode.equals("getters")) {
              sink =
                  fixture.memory.getMemoryUsageBytes()
                      + fixture.pids.getPidsCurrent()
                      + fixture.psi.getMemorySomeAvg10();
            } else {
              fixture.poll();
            }
          } catch (Exception error) {
            throw new IllegalStateException(error);
          }
        };
    com.sun.management.ThreadMXBean bean =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    bean.setThreadAllocatedMemoryEnabled(true);
    int samples = mode.equals("getters") ? 50_000 : 500;
    for (int i = 0; i < (mode.equals("getters") ? 20_000 : 100); i++) {
      operation.run();
    }
    long[] times = new long[samples];
    long id = Thread.currentThread().getId();
    long beforeBytes = bean.getThreadAllocatedBytes(id);
    long beforeCpu = bean.getCurrentThreadCpuTime();
    for (int i = 0; i < samples; i++) {
      long before = System.nanoTime();
      operation.run();
      times[i] = System.nanoTime() - before;
    }
    long cpu = bean.getCurrentThreadCpuTime() - beforeCpu;
    long bytes = bean.getThreadAllocatedBytes(id) - beforeBytes;
    Arrays.sort(times);
    System.out.printf(
        "{\"mode\":\"%s\",\"cpuNs\":%.1f,\"bytes\":%.1f,\"p99Ns\":%d}%n",
        mode, cpu / (double) samples, bytes / (double) samples, times[samples * 99 / 100 - 1]);
  }

  private static volatile double sink;
}
