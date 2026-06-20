package co.pletor.nodemetrics.metrics;

/**
 * JMX MBean interface for exposing cgroup memory metrics.
 */
public interface CgroupMemMetricsMBean {

  /**
   * Returns the memory limit in bytes for the cgroup.
   *
   * @return the memory limit in bytes
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getMemoryLimitBytes();

  /**
   * Returns the current memory usage in bytes for the cgroup.
   *
   * @return the memory usage in bytes
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getMemoryUsageBytes();

  /**
   * Returns the cgroup version (e.g. v1 or v2).
   *
   * @return the cgroup version string
   */
  @JmxMetricHint("gauge")
  String getCgroupVersion();

  /**
   * Returns the cgroup path.
   *
   * @return the cgroup path string
   */
  @JmxMetricHint("gauge")
  String getCgroupPath();
}
