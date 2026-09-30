package kr.pletor.nodemetrics.metrics;

/** JMX MBean interface for exposing node-level physical memory metrics. */
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
   *
   * <p>This strictly corresponds to unused memory (e.g. {@code MemFree} on Linux).
   *
   * @return the free memory in bytes
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getFreeMemoryBytes();

  /**
   * Returns the available physical memory in bytes.
   *
   * <p>This corresponds to memory available for starting new applications without swapping (e.g.
   * {@code MemAvailable} on Linux).
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

  /**
   * Returns the total swap space (swap devices and files) of the host.
   *
   * @return the swap size in bytes, {@code 0} when the host has no swap, or {@code -1} when
   *     unavailable
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getSwapTotalBytes();

  /**
   * Returns the swap space currently in use ({@code SwapTotal - SwapFree}).
   *
   * @return the used swap in bytes, or {@code -1} when unavailable
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getSwapUsedBytes();

  /**
   * Returns the cumulative number of pages swapped in from disk since boot ({@code pswpin} in
   * {@code /proc/vmstat}).
   *
   * <p>Swap activity matters more than swap usage: a steady value with a high {@code SwapUsedBytes}
   * is idle swap, while a rising rate means the host is actively swapping.
   *
   * @return the page count, or {@code null} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("pages")
  Long getSwapInPagesTotal();

  /**
   * Returns the cumulative number of pages swapped out to disk since boot ({@code pswpout} in
   * {@code /proc/vmstat}).
   *
   * @return the page count, or {@code null} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("pages")
  Long getSwapOutPagesTotal();

  /**
   * Returns the cumulative number of processes killed by the kernel's out-of-memory killer on this
   * host since boot ({@code oom_kill} in {@code /proc/vmstat}, kernel 4.13+).
   *
   * <p>Host-wide: inside a container it counts kills of any process on the node, so a rise means
   * the node is under memory pressure even when this container was not the victim. A kill of the
   * JVM itself is not visible to the agent (it dies with the process); use the orchestrator's
   * termination reason for that.
   *
   * @return the count, or {@code null} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("kills")
  Long getSystemOomKillTotal();
}
