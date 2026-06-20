package co.pletor.nodemetrics.metrics;

/**
 * JMX MBean interface for exposing high-level I/O throughput metrics.
 */
public interface IoRatesMBean {

  /**
   * Returns the disk read bytes per second.
   *
   * @return the disk read bytes/sec
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes/sec")
  double getDiskReadBytesPerSec();

  /**
   * Returns the disk write bytes per second.
   *
   * @return the disk write bytes/sec
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes/sec")
  double getDiskWriteBytesPerSec();

  /**
   * Returns the network received bytes per second.
   *
   * @return the net rx bytes/sec
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes/sec")
  double getNetRxBytesPerSec();

  /**
   * Returns the network transmitted bytes per second.
   *
   * @return the net tx bytes/sec
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes/sec")
  double getNetTxBytesPerSec();
}
