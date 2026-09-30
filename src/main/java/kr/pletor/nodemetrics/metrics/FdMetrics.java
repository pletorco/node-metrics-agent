package kr.pletor.nodemetrics.metrics;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.NoSuchFileException;
import java.util.List;

/**
 * Implementation of {@link FdMetricsMBean} backed by the platform {@link
 * java.lang.management.OperatingSystemMXBean}.
 *
 * <p>On Unix-like systems, this class uses {@link com.sun.management.UnixOperatingSystemMXBean} to
 * expose:
 *
 * <ul>
 *   <li>Current open file descriptor count
 *   <li>Maximum file descriptor limit
 * </ul>
 *
 * <p>On unsupported platforms, both values are reported as {@code -1}.
 */
public class FdMetrics extends AbstractRefreshingMetric implements FdMetricsMBean {

  /** Base operating system MXBean used to obtain FD metrics. */
  private final java.lang.management.OperatingSystemMXBean base =
      ManagementFactory.getOperatingSystemMXBean();

  /** Last observed number of open file descriptors, or -1 when unsupported. */
  private volatile long open = -1L;

  /** Last observed maximum file descriptor limit, or -1 when unsupported. */
  private volatile long max = -1L;

  private volatile long systemOpen = -1L;

  private volatile long systemMax = -1L;

  /**
   * Creates a new {@code FdMetrics} instance with zeroed counters.
   *
   * <p>Metric values will be populated on the first JMX query.
   */
  public FdMetrics() {
    // default constructor for MBean registration and frameworks
  }

  // ------------------------------------------------------------------------
  // Polling / Refreshing
  // ------------------------------------------------------------------------

  /** Refresh the metric values. */
  @Override
  protected void doRefresh() {
    refreshProcessDescriptors();
    refreshSystemHandles();
  }

  private void refreshProcessDescriptors() {
    try {
      if (base instanceof com.sun.management.UnixOperatingSystemMXBean) {
        com.sun.management.UnixOperatingSystemMXBean u =
            (com.sun.management.UnixOperatingSystemMXBean) base;
        open = u.getOpenFileDescriptorCount();
        max = u.getMaxFileDescriptorCount();
      } else {
        // Non-Unix or implementation does not support descriptor counts.
        open = -1L;
        max = -1L;
      }
    } catch (Throwable t) {
      // On any failure, expose metrics as unsupported.
      recordRefreshFailure(t);
      open = -1L;
      max = -1L;
    }
  }

  /**
   * Reads {@code /proc/sys/fs/file-nr} ({@code allocated unused max}). A missing file is
   * unavailable, not a failure; any other read problem keeps the previous values and is reported.
   */
  private void refreshSystemHandles() {
    if (!LinuxProcFs.isLinux()) {
      systemOpen = -1L;
      systemMax = -1L;
      return;
    }
    try {
      // Read line-wise: Files.readString/readAllBytes return only the first character of files
      // under /proc/sys on some kernels (size reported as 0), which would silently give 6 instead
      // of a real count.
      List<String> lines = LinuxProcFs.readLines(LinuxProcFs.procPath("sys/fs/file-nr"));
      String[] fields = (lines.isEmpty() ? "" : lines.get(0)).trim().split("\\s+");
      long allocated = fields.length > 0 ? parse(fields[0]) : -1L;
      long unused = fields.length > 1 ? parse(fields[1]) : 0L;
      long limit = fields.length > 2 ? parse(fields[2]) : -1L;
      systemOpen = allocated < 0L ? -1L : Math.max(0L, allocated - Math.max(0L, unused));
      systemMax = limit;
    } catch (NoSuchFileException e) {
      systemOpen = -1L;
      systemMax = -1L;
    } catch (IOException | RuntimeException e) {
      recordRefreshFailure(e);
    }
  }

  private static long parse(String text) {
    try {
      long v = Long.parseLong(text);
      return v < 0L ? -1L : v;
    } catch (NumberFormatException e) {
      return -1L;
    }
  }

  @Override
  public long getOpenFileDescriptorCount() {
    refreshOnRead();
    return open;
  }

  @Override
  public long getMaxFileDescriptorCount() {
    refreshOnRead();
    return max;
  }

  @Override
  public long getSystemOpenFileHandles() {
    refreshOnRead();
    return systemOpen;
  }

  @Override
  public long getSystemMaxFileHandles() {
    refreshOnRead();
    return systemMax;
  }
}
