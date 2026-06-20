package co.pletor.nodemetrics.metrics;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Cgroup (v1/v2) memory metrics MBean implementation.
 * <p>
 * Exposes the following attributes:
 * <ul>
 *   <li><b>MemoryLimitBytes</b> – container (cgroup) memory limit in bytes, or -1 when unlimited/unsupported</li>
 *   <li><b>MemoryUsageBytes</b> – container (cgroup) current memory usage in bytes, or -1 when unsupported</li>
 *   <li><b>CgroupVersion</b> – {@code "v1"}, {@code "v2"}, or {@code "none"}</li>
 *   <li><b>CgroupPath</b> – detected cgroup path (best-effort)</li>
 * </ul>
 * <p>
 * Notes:
 * <ul>
 *   <li>Linux only. On non-Linux OS, values are -1 and version is {@code "none"}.</li>
 *   <li>cgroup v2: reads {@code memory.max} and {@code memory.current}. If {@code memory.max} is {@code "max"},
 *       the limit is mapped to -1 (unlimited).</li>
 *   <li>cgroup v1: reads {@code memory.limit_in_bytes} and {@code memory.usage_in_bytes}. Some distros use
 *       very large values to represent "unlimited"; those are converted to -1 using a simple heuristic.</li>
 * </ul>
 */
public class CgroupMemMetrics implements CgroupMemMetricsMBean, RefreshManagedMetric {

  /**
   * Last observed memory limit in bytes.
   * <p>
   * -1 means “unlimited or unavailable”.
   */
  private volatile long limit = -1L;

  /**
   * Last observed memory usage in bytes.
   * <p>
   * -1 means “unavailable”.
   */
  private volatile long usage = -1L;

  /**
   * Captured cgroup metadata (version, path, resolved base directory, etc.).
   * <p>
   * This is detected once in the constructor and reused on each poll.
   */
  private final LinuxProcFs.CgroupInfo cg;

  /**
   * Construct a new metrics instance and detect cgroup information.
   */
  public CgroupMemMetrics() {
    this.cg = LinuxProcFs.detectCgroup();
  }

  // ------------------------------------------------------------------------
  // Polling / Refeshing
  // ------------------------------------------------------------------------

  private static final long REFRESH_INTERVAL_MS = 500L;
  private final RefreshCadence refreshCadence = new RefreshCadence(REFRESH_INTERVAL_MS);
  private volatile boolean readRefreshEnabled = true;

  /**
   * Refresh metrics if the cache is stale.
   */
  private void refresh() {
    if (!refreshCadence.tryAcquire()) {
      return;
    }

    // Non-Linux environments: expose no values.
    if (!LinuxProcFs.isLinux()) {
      limit = usage = -1L;
      return;
    }

    try {
      if ("v2".equals(cg.version)) {
        // ----- cgroup v2 -----
        // Prefer the detected/resolved cgroup directory, fall back to the default.
        Path base = (cg.resolved != null && Files.isDirectory(cg.resolved))
            ? cg.resolved
            : Path.of("/sys/fs/cgroup");

        // Helper handles "max" -> -1 internally.
        long lim = LinuxProcFs.readFirstNumber(base.resolve("memory.max"));
        long cur = LinuxProcFs.readFirstNumber(base.resolve("memory.current"));

        limit = lim;
        usage = cur;

      } else if ("v1".equals(cg.version)) {
        // ----- cgroup v1 -----
        // memory controller mount (or resolved path when available)
        Path base = (cg.resolved != null && Files.isDirectory(cg.resolved))
            ? cg.resolved
            : Path.of("/sys/fs/cgroup/memory");

        long lim = LinuxProcFs.readFirstNumber(base.resolve("memory.limit_in_bytes"));
        long cur = LinuxProcFs.readFirstNumber(base.resolve("memory.usage_in_bytes"));

        // Some v1 setups use extremely large numbers to represent "unlimited".
        // Heuristic: treat values close to Long.MAX_VALUE as unlimited (-1).
        if (lim >= Long.MAX_VALUE / 2) {
          lim = -1L;
        }

        limit = lim;
        usage = cur;

      } else {
        // Unknown or unsupported cgroup version.
        limit = usage = -1L;
      }
    } catch (Throwable t) {
      // On any read/parse error keep metrics safe and clearly unavailable.
      limit = usage = -1L;
    }
  }

  /**
   * Force refresh of metrics (for testing).
   */
  public void poll() {
    refreshCadence.force();
    refresh();
  }

  @Override
  public void setReadRefreshEnabled(boolean enabled) {
    readRefreshEnabled = enabled;
  }

  private void refreshOnRead() {
    if (readRefreshEnabled) {
      refresh();
    }
  }

  // ----- MBean getters -----

  /**
   * @return cgroup memory limit in bytes, or -1 when unlimited/unsupported
   */
  @Override
  public long getMemoryLimitBytes() {
    refreshOnRead();
    return limit;
  }

  /**
   * @return cgroup memory usage in bytes, or -1 when unavailable
   */
  @Override
  public long getMemoryUsageBytes() {
    refreshOnRead();
    return usage;
  }

  /**
   * @return detected cgroup version: {@code "v1"}, {@code "v2"}, or {@code "none"}
   */
  @Override
  public String getCgroupVersion() {
    return cg.version;
  }

  /**
   * @return detected cgroup path, or empty string if not available
   */
  @Override
  public String getCgroupPath() {
    return cg.path == null ? "" : cg.path;
  }
}
