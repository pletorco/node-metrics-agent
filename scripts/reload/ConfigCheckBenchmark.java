package kr.pletor.nodemetrics.agent;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** Unchanged-file check cost on the watcher thread, with a warm local filesystem cache. */
public final class ConfigCheckBenchmark {
  private ConfigCheckBenchmark() {}

  public static void main(String[] args) throws Exception {
    int size = Integer.parseInt(args[0]);
    Path path = Files.createTempFile("config-check-benchmark-", ".yml");
    try {
      String prefix = "refresh_interval_seconds: 2\nfsmetrics_paths: []\n#";
      Files.writeString(path, prefix + " ".repeat(size - prefix.length()));
      AtomicInteger applies = new AtomicInteger();
      ConfigReloader reloader = new ConfigReloader(path, config -> applies.incrementAndGet());
      Method check = ConfigReloader.class.getDeclaredMethod("checkAndReloadIfChanged");
      check.setAccessible(true);
      check.invoke(reloader);
      com.sun.management.ThreadMXBean bean =
          (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
      bean.setThreadAllocatedMemoryEnabled(true);
      for (int i = 0; i < 200; i++) {
        check.invoke(reloader);
      }
      long id = Thread.currentThread().getId();
      int samples = 1_000;
      long[] times = new long[samples];
      long beforeBytes = bean.getThreadAllocatedBytes(id);
      long beforeCpu = bean.getCurrentThreadCpuTime();
      for (int i = 0; i < samples; i++) {
        long before = System.nanoTime();
        check.invoke(reloader);
        times[i] = System.nanoTime() - before;
      }
      long cpu = bean.getCurrentThreadCpuTime() - beforeCpu;
      long bytes = bean.getThreadAllocatedBytes(id) - beforeBytes;
      Arrays.sort(times);
      if (applies.get() != 1) {
        throw new AssertionError("unchanged config applied repeatedly");
      }
      System.out.printf(
          "{\"fileBytes\":%d,\"cpuNs\":%.1f,\"bytes\":%.1f,\"p99Ns\":%d}%n",
          size, cpu / (double) samples, bytes / (double) samples, times[samples * 99 / 100 - 1]);
    } finally {
      Files.deleteIfExists(path);
    }
  }
}
