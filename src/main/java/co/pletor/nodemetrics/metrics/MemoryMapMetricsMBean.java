package co.pletor.nodemetrics.metrics;

/**
 * JMX MBean interface for the number of memory mappings of the JVM process.
 *
 * <p>A process that maps many files (a Kafka broker maps the index files of every log segment) can
 * reach the kernel limit {@code vm.max_map_count}; further mappings then fail with "Map failed" or
 * an {@code OutOfMemoryError} although heap and RAM are fine. Counting the mappings is more
 * expensive than the other process figures, so this MBean is refreshed on a slow schedule and in
 * the low-priority lane. Both attributes are {@code -1} when unavailable.
 */
public interface MemoryMapMetricsMBean {

  /**
   * Returns the number of memory mappings of this process: the lines of {@code /proc/self/maps}.
   *
   * @return the count, or {@code -1} when unavailable
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("mappings")
  long getMemoryMapCount();

  /**
   * Returns the kernel limit on mappings per process ({@code vm.max_map_count}). The ratio of
   * {@code MemoryMapCount} to this value is how close the process is to the limit.
   *
   * @return the limit, or {@code -1} when unavailable
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("mappings")
  long getMaxMemoryMapCount();
}
