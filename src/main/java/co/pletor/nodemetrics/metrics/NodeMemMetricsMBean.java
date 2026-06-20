package co.pletor.nodemetrics.metrics;

/**
 * JMX MBean interface for exposing node-level physical memory metrics.
 */
public interface NodeMemMetricsMBean {

  /**
   * Returns the total physical memory in bytes.
   *
   * @return the total memory in bytes
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getTotalMemoryBytes();

  /**
   * Returns the used physical memory in bytes.
   *
   * @return the used memory in bytes
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getUsedMemoryBytes();

  /**
   * Returns the free physical memory in bytes.
   * <p>
   * This strictly corresponds to unused memory (e.g. {@code MemFree} on Linux).
   *
   * @return the free memory in bytes
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getFreeMemoryBytes();

  /**
   * Returns the available physical memory in bytes.
   * <p>
   * This corresponds to memory available for starting new applications without swapping
   * (e.g. {@code MemAvailable} on Linux).
   *
   * @return the available memory in bytes
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getAvailableMemoryBytes();

  /**
   * Returns the percentage of used memory.
   *
   * @return the used memory percentage
   */
  @JmxMetricHint("gauge")
  double getUsedMemoryPercent();

  /**
   * Returns the amount of memory waiting to be written specific to the disk.
   *
   * @return the dirty bytes
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getDirtyBytes();

  /**
   * Returns the amount of memory actively being written back to the disk.
   *
   * @return the writeback bytes
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getWritebackBytes();

  /**
   * Returns the memory used by the page cache and slabs (Cached + SReclaimable).
   *
   * @return the cached bytes
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getCachedBytes();

  /**
   * Returns the memory used by kernel buffers.
   *
   * @return the buffer bytes
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getBuffersBytes();

  /**
   * Returns the shared memory used (tmpfs, shm).
   *
   * @return the shared memory bytes
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getShmemBytes();

  /**
   * Returns the active file page cache memory.
   *
   * @return the file page cache bytes
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getFilePageCacheBytes();
}
