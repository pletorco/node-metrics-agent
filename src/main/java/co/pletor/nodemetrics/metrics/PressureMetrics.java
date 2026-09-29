package co.pletor.nodemetrics.metrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * Pressure stall information (PSI) metrics MBean implementation.
 *
 * <p>Reads {@code cpu}, {@code memory} and {@code io} files that contain lines such as {@code some
 * avg10=0.12 avg60=0.05 avg300=0.01 total=123456}. One instance reads the node-wide files under
 * {@code /proc/pressure}, another the {@code cpu.pressure}, {@code memory.pressure} and {@code
 * io.pressure} files of the process's own cgroup (cgroup v2 only).
 *
 * <p>Missing or unreadable files are not failures: PSI is simply not available on many kernels, so
 * the attributes report {@code -1} and nothing is recorded as a refresh error.
 */
public class PressureMetrics extends AbstractRefreshingMetric implements PressureMetricsMBean {

  private static final int CPU_SOME = 0;
  private static final int CPU_FULL = 1;
  private static final int MEMORY_SOME = 2;
  private static final int MEMORY_FULL = 3;
  private static final int IO_SOME = 4;
  private static final int IO_FULL = 5;
  private static final int SLOTS = 6;

  private static final String[] RESOURCE_FILES = {"cpu", "memory", "io"};
  private static final String[] CGROUP_FILES = {"cpu.pressure", "memory.pressure", "io.pressure"};

  /** Parsed values of one pressure line. */
  static final class Stall {
    final double avg10;
    final long totalMicros;

    Stall(double avg10, long totalMicros) {
      this.avg10 = avg10;
      this.totalMicros = totalMicros;
    }
  }

  /** Immutable set of values from one refresh, so readers never see a half-updated mix. */
  private static final class Snapshot {
    static final Snapshot UNAVAILABLE = new Snapshot(unavailableAverages(), unavailableTotals());

    final double[] avg10;
    final long[] totals;

    Snapshot(double[] avg10, long[] totals) {
      this.avg10 = avg10;
      this.totals = totals;
    }

    private static double[] unavailableAverages() {
      double[] a = new double[SLOTS];
      Arrays.fill(a, -1.0);
      return a;
    }

    private static long[] unavailableTotals() {
      long[] t = new long[SLOTS];
      Arrays.fill(t, -1L);
      return t;
    }
  }

  /** Directory containing the pressure files, or {@code null} when none applies. */
  private final Path dir;

  private final String[] files;

  @SuppressWarnings("java:S3077")
  private volatile Snapshot snapshot = Snapshot.UNAVAILABLE;

  /**
   * Creates the node-wide instance, reading {@code /proc/pressure}.
   *
   * @return a new instance
   */
  public static PressureMetrics forNode() {
    return new PressureMetrics(Paths.get("/proc/pressure"), RESOURCE_FILES);
  }

  /**
   * Creates the instance for the process's own cgroup. Pressure files exist only on cgroup v2;
   * otherwise every attribute reports {@code -1}.
   *
   * @return a new instance
   */
  public static PressureMetrics forCgroup() {
    LinuxProcFs.CgroupInfo cg = LinuxProcFs.detectCgroup();
    Path cgroupDir = "v2".equals(cg.version) ? cg.resolved : null;
    return new PressureMetrics(cgroupDir, CGROUP_FILES);
  }

  // Visible for testing
  PressureMetrics(Path dir, String[] files) {
    this.dir = dir;
    this.files = files.clone();
  }

  // ------------------------------------------------------------------------
  // Polling / Refreshing
  // ------------------------------------------------------------------------

  /** Refresh the metric values. */
  @Override
  protected void doRefresh() {
    if (dir == null || !LinuxProcFs.isLinux()) {
      snapshot = Snapshot.UNAVAILABLE;
      return;
    }
    double[] avg = new double[SLOTS];
    long[] totals = new long[SLOTS];
    Arrays.fill(avg, -1.0);
    Arrays.fill(totals, -1L);
    for (int resource = 0; resource < files.length; resource++) {
      List<String> lines = readLinesOrNull(dir.resolve(files[resource]));
      if (lines == null) {
        continue;
      }
      for (String line : lines) {
        String kind = kindOf(line);
        Stall stall = parseLine(line);
        if (kind == null || stall == null) {
          continue;
        }
        int slot = resource * 2 + ("full".equals(kind) ? 1 : 0);
        avg[slot] = stall.avg10;
        totals[slot] = stall.totalMicros;
      }
    }
    snapshot = new Snapshot(avg, totals);
  }

  /** Returns the lines of a file, or {@code null} when PSI is not available through it. */
  private static List<String> readLinesOrNull(Path file) {
    if (!Files.isRegularFile(file)) {
      return null;
    }
    try {
      return LinuxProcFs.readLines(file);
    } catch (IOException e) {
      // PSI compiled in but disabled ("psi=0") makes reads fail; that is "unavailable".
      return null;
    }
  }

  private static String kindOf(String line) {
    String trimmed = line.trim();
    if (trimmed.startsWith("some ")) {
      return "some";
    }
    if (trimmed.startsWith("full ")) {
      return "full";
    }
    return null;
  }

  /**
   * Parses one pressure line such as {@code some avg10=0.12 avg60=0.05 avg300=0.01 total=123}.
   * Unknown keys are ignored.
   *
   * @return the parsed values, or {@code null} when the line has no usable {@code avg10} and {@code
   *     total}
   */
  static Stall parseLine(String line) {
    if (line == null) {
      return null;
    }
    double avg10 = -1.0;
    long total = -1L;
    for (String token : line.trim().split("\\s+")) {
      int eq = token.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String key = token.substring(0, eq);
      String value = token.substring(eq + 1);
      try {
        if ("avg10".equals(key)) {
          avg10 = Double.parseDouble(value);
        } else if ("total".equals(key)) {
          total = Long.parseLong(value);
        }
      } catch (NumberFormatException e) {
        return null;
      }
    }
    if (avg10 < 0.0 || total < 0L) {
      return null;
    }
    return new Stall(avg10, total);
  }

  // ----- MBean getters -----

  @Override
  public double getCpuSomeAvg10() {
    refreshOnRead();
    return snapshot.avg10[CPU_SOME];
  }

  @Override
  public long getCpuSomeTotalMicros() {
    refreshOnRead();
    return snapshot.totals[CPU_SOME];
  }

  @Override
  public double getCpuFullAvg10() {
    refreshOnRead();
    return snapshot.avg10[CPU_FULL];
  }

  @Override
  public long getCpuFullTotalMicros() {
    refreshOnRead();
    return snapshot.totals[CPU_FULL];
  }

  @Override
  public double getMemorySomeAvg10() {
    refreshOnRead();
    return snapshot.avg10[MEMORY_SOME];
  }

  @Override
  public long getMemorySomeTotalMicros() {
    refreshOnRead();
    return snapshot.totals[MEMORY_SOME];
  }

  @Override
  public double getMemoryFullAvg10() {
    refreshOnRead();
    return snapshot.avg10[MEMORY_FULL];
  }

  @Override
  public long getMemoryFullTotalMicros() {
    refreshOnRead();
    return snapshot.totals[MEMORY_FULL];
  }

  @Override
  public double getIoSomeAvg10() {
    refreshOnRead();
    return snapshot.avg10[IO_SOME];
  }

  @Override
  public long getIoSomeTotalMicros() {
    refreshOnRead();
    return snapshot.totals[IO_SOME];
  }

  @Override
  public double getIoFullAvg10() {
    refreshOnRead();
    return snapshot.avg10[IO_FULL];
  }

  @Override
  public long getIoFullTotalMicros() {
    refreshOnRead();
    return snapshot.totals[IO_FULL];
  }
}
