package co.pletor.nodemetrics.metrics;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runtime OS metrics MBean implementation (uptime, mounts).
 * <p>
 * On Linux, uses /proc/uptime and /proc/self/mounts (or /proc/mounts).
 * On non-Linux platforms, falls back to RuntimeMXBean uptime and
 * FileStore enumeration.
 */
public class OsRuntimeMetrics implements OsRuntimeMetricsMBean, RefreshManagedMetric {

  private volatile long uptimeSeconds = 0L;
  private volatile int mountCount = -1;
  @SuppressWarnings("java:S3077")
  private volatile String[] mounts = new String[0];

  /**
   * Creates a new {@code OsRuntimeMetrics} instance with zeroed counters.
   * <p>
   * Metric values will be populated on the first JMX query (if you have one).
   */
  public OsRuntimeMetrics() {
    // default constructor for MBean registration and frameworks
  }

  // ------------------------------------------------------------------------
  // Polling / Refeshing
  // ------------------------------------------------------------------------

  private static final long REFRESH_INTERVAL_MS = 500L;
  private final RefreshCadence refreshCadence = new RefreshCadence(REFRESH_INTERVAL_MS);
  private volatile boolean readRefreshEnabled = true;

  /**
   * Refresh metrics if the cache is stale.
   */
  private void refresh() {
    if (!refreshCadence.tryAcquire()) {
      return;
    }
    try {
      if (LinuxProcFs.isLinux()) {
        pollLinux();
      } else {
        pollNonLinux();
      }
    } catch (Exception e) {
      // Agent stability first: never let runtime metrics failures escape.
      uptimeSeconds = 0L;
      mountCount = -1;
      mounts = new String[0];
    }
  }

  /**
   * Force refresh of metrics (for testing).
   */
  public void poll() {
    refreshCadence.force();
    refresh();
  }

  @Override
  public void setReadRefreshEnabled(boolean enabled) {
    readRefreshEnabled = enabled;
  }

  private void refreshOnRead() {
    if (readRefreshEnabled) {
      refresh();
    }
  }

  // ----- Linux implementation -----

  private void pollLinux() {
    uptimeSeconds = readUptimeLinux();

    String[] m = readMountsLinux();
    mounts = m;
    mountCount = (m == null) ? -1 : m.length;
  }

  private long readUptimeLinux() {
    Path p = Path.of("/proc/uptime");
    if (!Files.isRegularFile(p)) {
      return 0L;
    }
    try {
      String s = Files.readString(p, StandardCharsets.UTF_8).trim();
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
      return 0L;
    }
  }

  private String[] readMountsLinux() {
    Path p = Path.of("/proc/self/mounts");
    if (!Files.isRegularFile(p)) {
      p = Path.of("/proc/mounts");
    }

    if (!Files.isRegularFile(p)) {
      return new String[0];
    }

    try {
      List<String> lines = LinuxProcFs.readLines(p);
      return lines.toArray(new String[0]);
    } catch (IOException e) {
      return new String[0];
    }
  }

  // ----- Non-Linux implementation -----

  private void pollNonLinux() {
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

  private String[] readMountsFromFileStores() {
    List<String> out = new ArrayList<>();
    try {
      for (FileStore fs : FileSystems.getDefault().getFileStores()) {
        String s = fs.name() + " (" + fs.type() + ")";
        out.add(s);
      }
    } catch (RuntimeException e) {
      // Best-effort only; return what we have so far.
    }
    return out.toArray(new String[0]);
  }

  // ----- Getters -----

  @Override
  public long getUptimeSeconds() {
    refreshOnRead();
    return uptimeSeconds;
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
