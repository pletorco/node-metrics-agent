package kr.pletor.nodemetrics.metrics;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/** Compare common production APIs in separate baseline/candidate JVMs; no new helper references. */
public final class InputReadBenchmark {
  private static volatile long consumed;

  private InputReadBenchmark() {}

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    String mode = args[1];
    int rows = Integer.parseInt(args[2]);
    LinuxProcFs.setProcRoot(root);
    LinuxProcFs.setSysRoot(root.resolve("sys"));
    ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    long id = Thread.currentThread().getId();
    int iterations = rows > 1000 ? 40 : 200;
    for (int i = 0; i < 40; i++) {
      read(root, mode, rows);
    }
    long[] nanos = new long[iterations];
    long allocated = threads.getThreadAllocatedBytes(id);
    long cpu = threads.getCurrentThreadCpuTime();
    for (int i = 0; i < iterations; i++) {
      long before = System.nanoTime();
      read(root, mode, rows);
      nanos[i] = System.nanoTime() - before;
    }
    cpu = threads.getCurrentThreadCpuTime() - cpu;
    allocated = threads.getThreadAllocatedBytes(id) - allocated;
    Arrays.sort(nanos);
    System.out.printf(
        Locale.ROOT,
        "{\"mode\":\"%s\",\"rows\":%d,\"iterations\":%d,\"p50Nanos\":%d,"
            + "\"p99Nanos\":%d,\"cpuNanosPerRead\":%.1f,\"allocatedBytesPerRead\":%.1f}%n",
        mode,
        rows,
        iterations,
        nanos[iterations / 2],
        nanos[(int) Math.ceil(iterations * .99) - 1],
        (double) cpu / iterations,
        (double) allocated / iterations);
  }

  private static void read(Path root, String mode, int rows) throws Exception {
    long count =
        "mounts".equals(mode)
            ? LinuxProcFs.readLines(root.resolve("mounts")).size()
            : LinuxProcFs.readNetTotals().rxBytes;
    if (count != rows) {
      throw new AssertionError("input was truncated: " + count + " != " + rows);
    }
    consumed = count;
  }
}
