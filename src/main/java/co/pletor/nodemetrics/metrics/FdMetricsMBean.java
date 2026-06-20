package co.pletor.nodemetrics.metrics;

/**
 * JMX MBean interface for file descriptor metrics.
 */
public interface FdMetricsMBean {

  /**
   * Returns the currently open file descriptor count.
   *
   * @return the open file descriptor count
   */
  @JmxMetricHint("gauge")
  long getOpenFileDescriptorCount();

  /**
   * Returns the maximum file descriptor count.
   *
   * @return the max file descriptor count
   */
  @JmxMetricHint("gauge")
  long getMaxFileDescriptorCount();
}
