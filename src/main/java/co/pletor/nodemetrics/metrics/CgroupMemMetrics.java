package co.pletor.nodemetrics.metrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Cgroup (v1/v2) memory metrics MBean implementation.
 *
 * <p>Exposes the following attributes:
 *
 * <ul>
 *   <li><b>MemoryLimitBytes</b> – effective container (cgroup) memory limit in bytes, i.e. the
 *       tightest finite limit of the cgroup and its ancestors, or -1 when unlimited/unsupported
 *   <li><b>MemoryUsageBytes</b> – container (cgroup) current memory usage in bytes, or -1 when
 *       unsupported. Includes reclaimable page cache.
 *   <li><b>MemoryWorkingSetBytes</b> – usage minus inactive file cache (the figure
 *       Kubernetes/cAdvisor and the OOM killer effectively care about), or -1 when {@code
 *       memory.stat} is unavailable
 *   <li><b>CgroupVersion</b> – {@code "v1"}, {@code "v2"}, or {@code "none"}
 *   <li><b>CgroupPath</b> – detected cgroup path (best-effort)
 * </ul>
 *
 * <p>Notes:
 *
 * <ul>
 *   <li>Linux only. On non-Linux OS, values are -1 and version is {@code "none"}.
 *   <li>cgroup v2: reads {@code memory.max} and {@code memory.current}. If {@code memory.max} is
 *       {@code "max"}, the limit is mapped to -1 (unlimited).
 *   <li>cgroup v1: reads {@code memory.limit_in_bytes} and {@code memory.usage_in_bytes}. Some
 *       distros use very large values to represent "unlimited"; those are converted to -1 using a
 *       simple heuristic.
 * </ul>
 */
public class CgroupMemMetrics extends AbstractRefreshingMetric implements CgroupMemMetricsMBean {

  /**
   * Last observed memory limit in bytes.
   *
   * <p>-1 means “unlimited or unavailable”.
   */
  private volatile long limit = -1L;

  /**
   * Last observed memory usage in bytes.
   *
   * <p>-1 means “unavailable”.
   */
  private volatile long usage = -1L;

  /**
   * Last observed working set in bytes: usage minus inactive file cache.
   *
   * <p>-1 means “unavailable”.
   */
  private volatile long workingSet = -1L;

  /**
   * Captured cgroup metadata (version, path, resolved base directory, etc.).
   *
   * <p>This is detected once in the constructor and reused on each poll.
   */
  private final LinuxProcFs.CgroupInfo cg;

  /** Construct a new metrics instance and detect cgroup information. */
  public CgroupMemMetrics() {
    this.cg = LinuxProcFs.detectCgroup();
  }

  // ------------------------------------------------------------------------
  // Polling / Refreshing
  // ------------------------------------------------------------------------

  /** Refresh the metric values. */
  @Override
  protected void doRefresh() {
    // Non-Linux environments: expose no values.
    if (!LinuxProcFs.isLinux()) {
      limit = usage = workingSet = -1L;
      return;
    }

    try {
      if ("v2".equals(cg.version)) {
        // ----- cgroup v2 -----
        // Prefer the detected/resolved cgroup directory, fall back to the default.
        Path base =
            (cg.resolved != null && Files.isDirectory(cg.resolved))
                ? cg.resolved
                : Path.of("/sys/fs/cgroup");

        // Helper handles "max" -> -1 internally.
        long lim = effectiveLimit(base, "memory.max", false);
        long cur = LinuxProcFs.readFirstNumber(base.resolve("memory.current"));

        limit = lim;
        usage = cur;
        workingSet = computeWorkingSet(base, cur, "inactive_file");

      } else if ("v1".equals(cg.version)) {
        // ----- cgroup v1 -----
        // memory controller mount (or resolved path when available)
        Path base =
            (cg.resolved != null && Files.isDirectory(cg.resolved))
                ? cg.resolved
                : Path.of("/sys/fs/cgroup/memory");

        long lim = effectiveLimit(base, "memory.limit_in_bytes", true);
        long cur = LinuxProcFs.readFirstNumber(base.resolve("memory.usage_in_bytes"));

        limit = lim;
        usage = cur;
        // v1 exposes the hierarchical figure as total_inactive_file (what cAdvisor uses).
        workingSet = computeWorkingSet(base, cur, "total_inactive_file", "inactive_file");

      } else {
        // Unknown or unsupported cgroup version.
        limit = usage = workingSet = -1L;
      }
    } catch (Throwable t) {
      // On any read/parse error keep metrics safe and clearly unavailable.
      recordRefreshFailure(t);
      limit = usage = workingSet = -1L;
    }
  }

  /**
   * Effective memory limit: the tightest finite limit of this cgroup and its ancestors.
   *
   * <p>The kernel enforces every level, so a container without its own limit is still bounded by
   * its pod (or slice), and a container limit larger than the pod's is never reachable. Ancestors
   * are only considered up to the cgroup mount root; inside a cgroup namespace the mount root is
   * already this cgroup and nothing is walked.
   *
   * @param base this cgroup's directory
   * @param limitFile {@code memory.max} (v2) or {@code memory.limit_in_bytes} (v1)
   * @param v1 whether huge values mean "unlimited" (cgroup v1 convention)
   * @return the effective limit in bytes, or -1 if no level has a finite limit
   */
  private long effectiveLimit(Path base, String limitFile, boolean v1) {
    long best = readLimit(base, limitFile, v1);
    Path root = cg.baseDir;
    if (root == null || !base.startsWith(root)) {
      return best;
    }
    for (Path dir = base; !dir.equals(root) && dir.getParent() != null; ) {
      dir = dir.getParent();
      long ancestor = readLimit(dir, limitFile, v1);
      if (ancestor > 0L && (best < 0L || ancestor < best)) {
        best = ancestor;
      }
    }
    return best;
  }

  private static long readLimit(Path dir, String limitFile, boolean v1) {
    long value = LinuxProcFs.readFirstNumber(dir.resolve(limitFile));
    // Some v1 setups use extremely large numbers to represent "unlimited".
    // Heuristic: treat values close to Long.MAX_VALUE as unlimited (-1).
    if (v1 && value >= Long.MAX_VALUE / 2) {
      return -1L;
    }
    return value;
  }

  /**
   * Working set = usage minus inactive file cache, clamped to zero. Inactive file pages are the
   * first thing the kernel reclaims under pressure, so usage alone overstates how close a
   * page-cache-heavy workload (such as Kafka) is to its limit.
   *
   * @param base cgroup directory containing {@code memory.stat}
   * @param usageBytes current usage, or a negative value when unavailable
   * @param inactiveKeys {@code memory.stat} keys to try, in order of preference
   * @return working set in bytes, or -1 when it cannot be determined
   */
  private long computeWorkingSet(Path base, long usageBytes, String... inactiveKeys) {
    if (usageBytes < 0L) {
      return -1L;
    }
    Path stat = base.resolve("memory.stat");
    if (!Files.isRegularFile(stat)) {
      return -1L;
    }
    try {
      Map<String, Long> values = MemStatsUtil.readKeyValues(stat);
      for (String key : inactiveKeys) {
        Long inactive = values.get(key);
        if (inactive != null && inactive >= 0L) {
          return Math.max(0L, usageBytes - inactive);
        }
      }
      return -1L;
    } catch (IOException e) {
      recordRefreshFailure(e);
      return -1L;
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
   * @return cgroup working set (usage minus inactive file cache) in bytes, or -1 when unavailable
   */
  @Override
  public long getMemoryWorkingSetBytes() {
    refreshOnRead();
    return workingSet;
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
