package co.pletor.nodemetrics.metrics;

/**
 * JMX MBean interface for disk operation counts and I/O time from {@code /proc/diskstats}.
 *
 * <p>Complements the byte counters of {@code IoRates}: operations, latency and utilization are what
 * show a saturated or slow disk. All counters are cumulative (use {@code rate()}) and summed over
 * the physical (leaf) block devices, so a per-device breakdown is not available. Every attribute is
 * {@code -1} when {@code /proc/diskstats} is unavailable or has no countable device.
 */
public interface DiskIoMetricsMBean {

  /**
   * Returns the cumulative number of completed read operations, summed over the counted block
   * devices.
   *
   * @return the count, or {@code -1} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("operations")
  long getDiskReadsCompletedTotal();

  /**
   * Returns the cumulative number of completed write operations, summed over the counted block
   * devices.
   *
   * @return the count, or {@code -1} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("operations")
  long getDiskWritesCompletedTotal();

  /**
   * Returns the cumulative time spent reading, in milliseconds, summed over operations (so it can
   * exceed wall-clock time). {@code rate(read_time) / rate(reads_completed)} is the average read
   * latency.
   *
   * @return the time, or {@code -1} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("milliseconds")
  long getDiskReadTimeMillisTotal();

  /**
   * Returns the cumulative time spent writing, in milliseconds, summed over operations. {@code
   * rate(write_time) / rate(writes_completed)} is the average write latency.
   *
   * @return the time, or {@code -1} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("milliseconds")
  long getDiskWriteTimeMillisTotal();

  /**
   * Returns the cumulative time, in milliseconds, that each counted device spent with at least one
   * I/O in flight ({@code io_ticks}), summed over devices. Divided by {@code 1000 *
   * DiskDeviceCount}, its rate is the average device utilization.
   *
   * @return the time, or {@code -1} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("milliseconds")
  long getDiskIoTimeMillisTotal();

  /**
   * Returns the cumulative weighted I/O time in milliseconds: busy time multiplied by the number of
   * requests in flight. Its rate is the average queue length (times 1000).
   *
   * @return the time, or {@code -1} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("milliseconds")
  long getDiskWeightedIoTimeMillisTotal();

  /**
   * Returns the number of I/O operations currently in flight, summed over the counted devices.
   *
   * @return the count, or {@code -1} when unavailable
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("operations")
  long getDiskIoInProgress();

  /**
   * Returns the number of block devices counted, using the same leaf-device selection as the disk
   * byte counters (stacked devices such as dm or md on top of a disk are not counted twice).
   *
   * @return the count, or {@code -1} when unavailable
   */
  @JmxMetricHint("gauge")
  long getDiskDeviceCount();
}
