package kr.pletor.nodemetrics.metrics;

/** JMX MBean interface for exposing high-level I/O throughput metrics. */
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

  /**
   * Returns the cumulative bytes read from the counted block devices. Monotonic; use {@code rate()}
   * over any time range instead of the point-in-time per-second gauges above. {@code null} until
   * the first successful read.
   *
   * @return total disk bytes read
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("bytes")
  Long getDiskReadBytesTotal();

  /**
   * Returns the cumulative bytes written to the counted block devices. {@code null} until the first
   * successful read.
   *
   * @return total disk bytes written
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("bytes")
  Long getDiskWriteBytesTotal();

  /**
   * Returns the cumulative bytes received on the counted network interfaces. {@code null} until the
   * first successful read.
   *
   * @return total network bytes received
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("bytes")
  Long getNetRxBytesTotal();

  /**
   * Returns the cumulative bytes sent on the counted network interfaces. {@code null} until the
   * first successful read.
   *
   * @return total network bytes sent
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("bytes")
  Long getNetTxBytesTotal();
}
