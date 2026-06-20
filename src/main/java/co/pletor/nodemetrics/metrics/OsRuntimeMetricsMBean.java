package co.pletor.nodemetrics.metrics;

/**
 * MBean interface for runtime OS metrics (uptime and mounts).
 */
public interface OsRuntimeMetricsMBean {

  /**
   * Returns the system uptime in seconds.
   *
   * @return the uptime in seconds
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("seconds")
  long getUptimeSeconds();

  /**
   * Returns the count of file system mounts.
   *
   * @return the mount count
   */
  @JmxMetricHint("gauge")
  int getMountCount();

  /**
   * Returns the list of file system mounts.
   *
   * @return the mounts array
   */
  @JmxMetricHint("gauge")
  String[] getMounts();
}
