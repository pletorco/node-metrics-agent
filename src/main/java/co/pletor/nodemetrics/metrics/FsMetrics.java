package co.pletor.nodemetrics.metrics;

import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Lightweight filesystem metrics implementation backed by {@link java.nio.file.FileStore}.
 *
 * <p>This MBean exposes:
 *
 * <ul>
 *   <li>Filesystem path (as configured)
 *   <li>File store name
 *   <li>Filesystem type (e.g., {@code "ext4"}, {@code "xfs"})
 *   <li>Total capacity in bytes
 *   <li>Usable space in bytes (for the current user)
 * </ul>
 *
 * <p>On failure, numeric values are set to {@code -1} and names to {@code "unknown"}.
 */
public class FsMetrics extends AbstractRefreshingMetric implements FsMetricsMBean {

  /** Path whose underlying filesystem is being monitored. */
  private final Path path;

  /** Last observed file store name. */
  private volatile String fsName = "";

  /** Last observed filesystem type. */
  private volatile String fsType = "";

  /**
   * Last observed total size of the filesystem in bytes.
   *
   * <p>{@code -1} indicates an error or unsupported value.
   */
  private volatile long total;

  /**
   * Last observed usable bytes on the filesystem.
   *
   * <p>{@code -1} indicates an error or unsupported value.
   */
  private volatile long usable;

  /**
   * Last observed unallocated bytes on the filesystem.
   *
   * <p>{@code -1} indicates an error or unsupported value.
   */
  private volatile long unallocated;

  /**
   * Create a new metrics instance for the given path.
   *
   * @param path filesystem path to monitor
   */
  public FsMetrics(Path path) {
    this.path = path;
  }

  // ------------------------------------------------------------------------
  // Polling / Refreshing
  // ------------------------------------------------------------------------

  /** Refresh the metric values. */
  @Override
  protected void doRefresh() {
    try {
      FileStore fs = Files.getFileStore(path);
      fsName = fs.name();
      fsType = fs.type();
      total = fs.getTotalSpace();
      usable = fs.getUsableSpace();
      unallocated = fs.getUnallocatedSpace();
    } catch (Exception e) {
      recordRefreshFailure(e);
      fsName = "unknown";
      fsType = "unknown";
      total = -1L;
      usable = -1L;
      unallocated = -1L;
    }
  }

  @Override
  public String getPath() {
    refreshOnRead();
    return path.toString();
  }

  @Override
  public String getFileStoreName() {
    refreshOnRead();
    return fsName;
  }

  @Override
  public String getFileSystemType() {
    refreshOnRead();
    return fsType;
  }

  @Override
  public long getTotalBytes() {
    refreshOnRead();
    return total;
  }

  @Override
  public long getUsableBytes() {
    refreshOnRead();
    return usable;
  }

  @Override
  public long getUnallocatedBytes() {
    refreshOnRead();
    return unallocated;
  }
}
