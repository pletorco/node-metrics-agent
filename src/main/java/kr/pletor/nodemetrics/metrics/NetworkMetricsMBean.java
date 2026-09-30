package kr.pletor.nodemetrics.metrics;

/**
 * JMX MBean interface for network errors, drops and TCP health counters.
 *
 * <p>The interface counters use the same interface selection as the byte counters of {@code
 * IoRates}. The TCP counters come from {@code /proc/net/snmp} and {@code /proc/net/netstat}, which
 * inside a container describe the container's network namespace. All counters are cumulative (use
 * {@code rate()}); every attribute is {@code -1} when its source is unavailable.
 */
public interface NetworkMetricsMBean {

  /**
   * Returns the cumulative number of receive errors, summed over the interfaces the network byte
   * counters use.
   *
   * @return the count, or {@code -1} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("packets")
  long getNetRxErrorsTotal();

  /**
   * Returns the cumulative number of packets dropped on receive, summed over the counted
   * interfaces.
   *
   * @return the count, or {@code -1} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("packets")
  long getNetRxDroppedTotal();

  /**
   * Returns the cumulative number of transmit errors, summed over the counted interfaces.
   *
   * @return the count, or {@code -1} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("packets")
  long getNetTxErrorsTotal();

  /**
   * Returns the cumulative number of packets dropped on transmit, summed over the counted
   * interfaces.
   *
   * @return the count, or {@code -1} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("packets")
  long getNetTxDroppedTotal();

  /**
   * Returns the cumulative number of TCP segments sent ({@code Tcp: OutSegs}), the denominator of
   * the retransmit ratio.
   *
   * @return the count, or {@code -1} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("segments")
  long getTcpOutSegsTotal();

  /**
   * Returns the cumulative number of TCP segments retransmitted ({@code Tcp: RetransSegs}). {@code
   * rate(retrans) / rate(out_segs)} is the retransmit ratio.
   *
   * @return the count, or {@code -1} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("segments")
  long getTcpRetransSegsTotal();

  /**
   * Returns the cumulative number of TCP segments received in error ({@code Tcp: InErrs}).
   *
   * @return the count, or {@code -1} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("segments")
  long getTcpInErrsTotal();

  /**
   * Returns the cumulative number of failed TCP connection attempts ({@code Tcp: AttemptFails}).
   *
   * @return the count, or {@code -1} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("connections")
  long getTcpAttemptFailsTotal();

  /**
   * Returns the cumulative number of established TCP connections that were reset ({@code Tcp:
   * EstabResets}).
   *
   * @return the count, or {@code -1} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("connections")
  long getTcpEstabResetsTotal();

  /**
   * Returns the number of TCP connections currently established or in close-wait ({@code Tcp:
   * CurrEstab}).
   *
   * @return the count, or {@code -1} when unavailable
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("connections")
  long getTcpCurrEstab();

  /**
   * Returns the cumulative number of times a listen queue overflowed ({@code TcpExt:
   * ListenOverflows}): the server was too slow to accept connections.
   *
   * @return the count, or {@code -1} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("connections")
  long getTcpListenOverflowsTotal();

  /**
   * Returns the cumulative number of connections dropped while listening ({@code TcpExt:
   * ListenDrops}).
   *
   * @return the count, or {@code -1} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("connections")
  long getTcpListenDropsTotal();

  /**
   * Returns the cumulative number of TCP retransmission timeouts ({@code TcpExt: TCPTimeouts}).
   *
   * @return the count, or {@code -1} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("events")
  long getTcpTimeoutsTotal();
}
