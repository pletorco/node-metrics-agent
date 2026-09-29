package co.pletor.nodemetrics.metrics;

import java.lang.management.ManagementFactory;

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
 * On unsupported platforms, both values are reported as {@code -1}.
 */
public class FdMetrics extends AbstractRefreshingMetric implements FdMetricsMBean {

  /** Base operating system MXBean used to obtain FD metrics. */
  private final java.lang.management.OperatingSystemMXBean base =
      ManagementFactory.getOperatingSystemMXBean();

  /** Last observed number of open file descriptors, or -1 when unsupported. */
  private volatile long open = -1L;

  /** Last observed maximum file descriptor limit, or -1 when unsupported. */
  private volatile long max = -1L;

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
}
