package kr.pletor.nodemetrics.metrics;

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

  /** Last observed cgroup swap usage in bytes; -1 means "unavailable". */
  private volatile long swapUsage = -1L;

  /** Last observed cgroup swap limit in bytes; -1 means "unlimited or unavailable". */
  private volatile long swapLimit = -1L;

  /** Cumulative OOM kills in this cgroup; -1 means "unavailable". */
  private volatile long oomKills = -1L;

  /** Cumulative times the cgroup hit its memory limit; -1 means "unavailable". */
  private volatile long maxEvents = -1L;

  /** Cumulative times the cgroup went over {@code memory.high}; -1 means "unavailable". */
  private volatile long highEvents = -1L;

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
      limit = usage = workingSet = swapUsage = swapLimit = oomKills = maxEvents = highEvents = -1L;
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
        swapUsage = readNumber(base.resolve("memory.swap.current"));
        swapLimit = readNumber(base.resolve("memory.swap.max"));
        Map<String, Long> events = readKeyValuesOrEmpty(base.resolve("memory.events"));
        oomKills = counter(events, "oom_kill");
        maxEvents = counter(events, "max");
        highEvents = counter(events, "high");

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
        computeV1Swap(base, cur);
        oomKills = counter(readKeyValuesOrEmpty(base.resolve("memory.oom_control")), "oom_kill");
        // v1 counts the times the limit was hit in memory.failcnt and has no equivalent of high.
        maxEvents = readNumber(base.resolve("memory.failcnt"));
        highEvents = -1L;

      } else {
        // Unknown or unsupported cgroup version.
        limit =
            usage = workingSet = swapUsage = swapLimit = oomKills = maxEvents = highEvents = -1L;
      }
    } catch (Throwable t) {
      // On any read/parse error keep metrics safe and clearly unavailable.
      recordRefreshFailure(t);
      limit = usage = workingSet = swapUsage = swapLimit = oomKills = maxEvents = highEvents = -1L;
    }
  }

  /**
   * cgroup v1 accounts memory and swap together ({@code memsw}), so swap alone is the difference.
   * The files exist only when swap accounting is enabled; otherwise both values stay -1.
   */
  private void computeV1Swap(Path base, long memoryUsage) {
    long memsw = readNumber(base.resolve("memory.memsw.usage_in_bytes"));
    swapUsage = (memsw >= 0L && memoryUsage >= 0L) ? Math.max(0L, memsw - memoryUsage) : -1L;

    long memswLimit = unlessUnlimited(readNumber(base.resolve("memory.memsw.limit_in_bytes")));
    long ownMemoryLimit = unlessUnlimited(readNumber(base.resolve("memory.limit_in_bytes")));
    swapLimit =
        (memswLimit >= 0L && ownMemoryLimit >= 0L)
            ? Math.max(0L, memswLimit - ownMemoryLimit)
            : -1L;
  }

  /** Key/value lines of a cgroup file, or an empty map when it is missing or unreadable. */
  private static Map<String, Long> readKeyValuesOrEmpty(Path file) {
    try {
      return MemStatsUtil.readKeyValues(file);
    } catch (IOException | RuntimeException e) {
      return Map.of();
    }
  }

  /** A cumulative counter from a key/value file, or -1 when the key is absent (older kernels). */
  private static long counter(Map<String, Long> values, String key) {
    Long value = values.get(key);
    return value == null || value < 0L ? -1L : value;
  }

  private static long unlessUnlimited(long v1Value) {
    return v1Value >= Long.MAX_VALUE / 2 ? -1L : v1Value;
  }

  /**
   * Reads a file holding a single number. {@code "max"}, a missing file and parse errors all give
   * -1, the same convention as {@link LinuxProcFs#readFirstNumber(Path)}; kept separate so the swap
   * files are read independently of the memory ones.
   */
  private static long readNumber(Path file) {
    try {
      String text = Files.readString(file).trim();
      return "max".equals(text) ? -1L : Long.parseLong(text);
    } catch (IOException | RuntimeException e) {
      return -1L;
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
   * Returns the effective cgroup memory limit.
   *
   * @return the limit in bytes, or -1 when unlimited/unsupported
   */
  @Override
  public long getMemoryLimitBytes() {
    refreshOnRead();
    return limit;
  }

  /**
   * Returns the cgroup memory usage.
   *
   * @return the usage in bytes, or -1 when unavailable
   */
  @Override
  public long getMemoryUsageBytes() {
    refreshOnRead();
    return usage;
  }

  /**
   * Returns the cgroup working set (usage minus inactive file cache).
   *
   * @return the working set in bytes, or -1 when unavailable
   */
  @Override
  public long getMemoryWorkingSetBytes() {
    refreshOnRead();
    return workingSet;
  }

  /**
   * Returns the detected cgroup version.
   *
   * @return {@code "v1"}, {@code "v2"}, or {@code "none"}
   */
  @Override
  public String getCgroupVersion() {
    return cg.version;
  }

  /**
   * Returns the detected cgroup path.
   *
   * @return the path, or an empty string if not available
   */
  @Override
  public String getCgroupPath() {
    return cg.path == null ? "" : cg.path;
  }

  /**
   * Returns the swap used by the cgroup.
   *
   * @return the swap usage in bytes, or -1 when unavailable
   */
  @Override
  public long getSwapUsageBytes() {
    refreshOnRead();
    return swapUsage;
  }

  /**
   * Returns the swap limit of the cgroup.
   *
   * @return the limit in bytes, 0 when swap is not allowed, or -1 when unlimited/unavailable
   */
  @Override
  public long getSwapLimitBytes() {
    refreshOnRead();
    return swapLimit;
  }

  /**
   * Returns the cumulative number of times the cgroup hit its memory limit.
   *
   * @return the count, or -1 when unavailable
   */
  @Override
  public Long getMemoryMaxEventsTotal() {
    refreshOnRead();
    return counterOrNull(maxEvents);
  }

  /**
   * Returns the cumulative number of times the cgroup went over {@code memory.high}.
   *
   * @return the count, or -1 when unavailable
   */
  @Override
  public Long getMemoryHighEventsTotal() {
    refreshOnRead();
    return counterOrNull(highEvents);
  }

  /**
   * Returns the cumulative OOM kills in this cgroup.
   *
   * @return the count, or -1 when unavailable
   */
  @Override
  public Long getMemoryOomKillTotal() {
    refreshOnRead();
    return counterOrNull(oomKills);
  }
}
