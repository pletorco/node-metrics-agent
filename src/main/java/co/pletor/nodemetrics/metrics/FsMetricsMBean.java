package co.pletor.nodemetrics.metrics;

/**
 * Lightweight JMX MBean interface for basic filesystem metrics.
 */
public interface FsMetricsMBean {

  /**
   * Returns the file system path.
   *
   * @return the file system path
   */
  @JmxMetricHint("gauge")
  String getPath();

  /**
   * Returns the file store name.
   *
   * @return the file store name
   */
  @JmxMetricHint("gauge")
  String getFileStoreName();

  /**
   * Returns the file system type.
   *
   * @return the file system type
   */
  @JmxMetricHint("gauge")
  String getFileSystemType();

  /**
   * Returns the total bytes in the file system.
   *
   * @return the total bytes
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getTotalBytes();

  /**
   * Returns the usable bytes in the file system for this JVM.
   *
   * @return the usable bytes
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getUsableBytes();

  /**
   * Returns the unallocated bytes in the file system.
   *
   * @return the unallocated bytes
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getUnallocatedBytes();
}
