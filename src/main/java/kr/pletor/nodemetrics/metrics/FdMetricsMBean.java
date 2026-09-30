package kr.pletor.nodemetrics.metrics;

/** JMX MBean interface for file descriptor metrics. */
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

  /**
   * Returns the number of file handles allocated system-wide ({@code allocated - unused} from
   * {@code /proc/sys/fs/file-nr}), across all processes on the host.
   *
   * <p>A host running many processes can exhaust the kernel-wide table even when every process is
   * under its own limit. Mostly relevant to VMs and bare metal.
   *
   * @return the handle count, or {@code -1} when unavailable
   */
  @JmxMetricHint("gauge")
  long getSystemOpenFileHandles();

  /**
   * Returns the system-wide limit of file handles ({@code fs.file-max}, the third field of {@code
   * /proc/sys/fs/file-nr}).
   *
   * @return the limit, or {@code -1} when unavailable
   */
  @JmxMetricHint("gauge")
  long getSystemMaxFileHandles();
}
