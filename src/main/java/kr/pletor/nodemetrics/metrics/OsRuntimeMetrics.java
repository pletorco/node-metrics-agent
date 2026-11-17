package kr.pletor.nodemetrics.metrics;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runtime OS metrics MBean implementation (uptime, mounts).
 *
 * <p>On Linux, uses /proc/uptime and /proc/self/mounts (or /proc/mounts). On non-Linux platforms,
 * falls back to RuntimeMXBean uptime and FileStore enumeration.
 */
public class OsRuntimeMetrics extends AbstractRefreshingMetric implements OsRuntimeMetricsMBean {

  private volatile long uptimeSeconds = 0L;
  private volatile int mountCount = -1;

  @SuppressWarnings("java:S3077")
  private volatile String[] mounts = new String[0];

  /**
   * Creates a new {@code OsRuntimeMetrics} instance with zeroed counters.
   *
   * <p>Metric values will be populated on the first JMX query (if you have one).
   */
  public OsRuntimeMetrics() {
    // default constructor for MBean registration and frameworks
  }

  // ------------------------------------------------------------------------
  // Polling / Refreshing
  // ------------------------------------------------------------------------

  /** Refresh the metric values. */
  @Override
  protected void doRefresh() {
    try {
      if (LinuxProcFs.isLinux()) {
        pollLinux();
      } else {
        pollNonLinux();
      }
    } catch (Exception e) {
      // Agent stability first: never let runtime metrics failures escape.
      recordRefreshFailure(e);
      uptimeSeconds = 0L;
      mountCount = -1;
      mounts = new String[0];
    }
  }

  // ----- Linux implementation -----

  private void pollLinux() throws IOException {
    uptimeSeconds = readUptimeLinux();

    String[] m = readMountsLinux();
    mounts = m;
    mountCount = (m == null) ? -1 : m.length;
  }

  private long readUptimeLinux() {
    Path p = LinuxProcFs.procPath("uptime");
    if (!Files.isRegularFile(p)) {
      return 0L;
    }
    try {
      String s = CollectorInput.readString(p, CollectorInput.NUMBER).trim();
      if (s.isEmpty()) {
        return 0L;
      }
      String[] parts = s.split("\\s+");
      if (parts.length == 0) {
        return 0L;
      }
      double sec = Double.parseDouble(parts[0]);
      if (sec < 0.0) {
        return 0L;
      }
      return (long) sec;
    } catch (IOException | NumberFormatException e) {
      recordRefreshFailure(e);
      return 0L;
    }
  }

  private String[] readMountsLinux() throws IOException {
    Path p = LinuxProcFs.procPath("self/mounts");
    if (!Files.isRegularFile(p)) {
      p = LinuxProcFs.procPath("mounts");
    }

    if (!Files.isRegularFile(p)) {
      return new String[0];
    }

    try {
      List<String> lines = LinuxProcFs.readLines(p);
      return lines.toArray(new String[0]);
    } catch (CollectorInput.LimitExceededException e) {
      throw e;
    } catch (IOException e) {
      recordRefreshFailure(e);
      return new String[0];
    }
  }

  // ----- Non-Linux implementation -----

  private void pollNonLinux() throws IOException {
    uptimeSeconds = readUptimeFromRuntimeMxBean();

    String[] m = readMountsFromFileStores();
    mounts = m;
    mountCount = (m == null) ? -1 : m.length;
  }

  private long readUptimeFromRuntimeMxBean() {
    try {
      RuntimeMXBean mx = ManagementFactory.getRuntimeMXBean();
      long ms = mx.getUptime();
      if (ms < 0L) {
        return 0L;
      }
      return ms / 1000L;
    } catch (RuntimeException e) {
      return 0L;
    }
  }

  private String[] readMountsFromFileStores() throws IOException {
    List<String> out = new ArrayList<>();
    int chars = 0;
    try {
      for (FileStore fs : FileSystems.getDefault().getFileStores()) {
        if (out.size() >= CollectorInput.TABLE.lines) {
          throw new CollectorInput.LimitExceededException(
              "mount entries", CollectorInput.TABLE.lines);
        }
        String name = fs.name();
        String type = fs.type();
        long length = (long) name.length() + type.length() + 3;
        if (length > CollectorInput.TABLE.lineBytes) {
          throw new CollectorInput.LimitExceededException(
              "mount characters", CollectorInput.TABLE.lineBytes);
        }
        if (chars + length > CollectorInput.TABLE.bytes) {
          throw new CollectorInput.LimitExceededException(
              "mount characters", CollectorInput.TABLE.bytes);
        }
        chars += (int) length;
        out.add(name + " (" + type + ")");
      }
    } catch (RuntimeException e) {
      // Best-effort only; return what we have so far.
    }
    return out.toArray(new String[0]);
  }

  // ----- Getters -----

  @Override
  public Long getUptimeSeconds() {
    refreshOnRead();
    return counterOrNull(uptimeSeconds);
  }

  @Override
  public int getMountCount() {
    refreshOnRead();
    return mountCount;
  }

  @Override
  public String[] getMounts() {
    refreshOnRead();
    return mounts.clone();
  }
}
