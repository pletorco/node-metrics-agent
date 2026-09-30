package kr.pletor.nodemetrics.metrics;

/**
 * JMX MBean interface for resource figures of the JVM process itself, from {@code /proc/self}.
 *
 * <p>The cgroup metrics describe the whole container and the {@code java.lang} MBeans describe what
 * the JVM tracks; the resident set shows what the process really holds. A size or count is {@code
 * -1} when unavailable (non-Linux, or a kernel without the field), and an I/O counter is {@code
 * null} (absent).
 *
 * <p>Context switches are deliberately not exposed: {@code /proc/self/status} reports them for the
 * main thread only, which says little about a multi-threaded JVM.
 */
public interface ProcessMetricsMBean {

  /**
   * Returns the resident set size of this JVM process ({@code VmRSS}): the physical memory it
   * really holds, including heap, metaspace, direct buffers, mapped files and native libraries.
   * Compare with the cgroup working set and the JVM's own memory accounting to find memory that the
   * JVM does not track.
   *
   * @return the size in bytes, or {@code -1} when unavailable
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getResidentSetBytes();

  /**
   * Returns the peak resident set size since the process started ({@code VmHWM}).
   *
   * @return the size in bytes, or {@code -1} when unavailable
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getResidentSetPeakBytes();

  /**
   * Returns the anonymous part of the resident set ({@code RssAnon}): heap and other private
   * memory.
   *
   * @return the size in bytes, or {@code -1} when unavailable
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getResidentAnonBytes();

  /**
   * Returns the file-backed part of the resident set ({@code RssFile}): mapped files such as
   * libraries and memory-mapped data.
   *
   * @return the size in bytes, or {@code -1} when unavailable
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getResidentFileBytes();

  /**
   * Returns the shared-memory part of the resident set ({@code RssShmem}).
   *
   * @return the size in bytes, or {@code -1} when unavailable
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getResidentShmemBytes();

  /**
   * Returns the swap used by this process ({@code VmSwap}).
   *
   * @return the size in bytes, or {@code -1} when unavailable
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getSwapBytes();

  /**
   * Returns the number of native threads in this process ({@code Threads}), which includes JVM
   * internal threads that the {@code java.lang} thread count does not.
   *
   * @return the count, or {@code -1} when unavailable
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("threads")
  long getThreadCount();

  /**
   * Returns the cumulative bytes this process caused to be read from storage ({@code read_bytes} in
   * {@code /proc/self/io}), excluding reads served from the page cache. Needs task I/O accounting
   * in the kernel.
   *
   * @return the count, or {@code null} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("bytes")
  Long getIoReadBytesTotal();

  /**
   * Returns the cumulative bytes this process caused to be sent to storage ({@code write_bytes} in
   * {@code /proc/self/io}).
   *
   * @return the count, or {@code null} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("bytes")
  Long getIoWriteBytesTotal();
}
