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

  private static final int LIMIT = 0;
  private static final int USAGE = 1;
  private static final int WORKING_SET = 2;
  private static final int SWAP_USAGE = 3;
  private static final int SWAP_LIMIT = 4;
  private static final int OOM_KILLS = 5;
  private static final int MAX_EVENTS = 6;
  private static final int HIGH_EVENTS = 7;

  private static final class Snapshot {
    static final Snapshot UNAVAILABLE =
        new Snapshot(new LinuxProcFs.CgroupInfo(), new long[] {-1, -1, -1, -1, -1, -1, -1, -1});
    final String version;
    final String path;
    final long[] values;

    Snapshot(LinuxProcFs.CgroupInfo info, long[] values) {
      this.version = info.version;
      this.path = info.path == null ? "" : info.path;
      this.values = values;
    }
  }

  private final CgroupBinding binding;

  @SuppressWarnings("java:S3077")
  private volatile Snapshot snapshot = Snapshot.UNAVAILABLE;

  /** Construct a collector; membership I/O occurs only when it is polled. */
  public CgroupMemMetrics() {
    this(new CgroupBinding(LinuxProcFs::detectCgroup));
  }

  CgroupMemMetrics(CgroupBinding binding) {
    this.binding = binding;
  }

  // ------------------------------------------------------------------------
  // Polling / Refreshing
  // ------------------------------------------------------------------------

  /** Refresh the metric values. */
  @Override
  protected void doRefresh() {
    if (!LinuxProcFs.isLinux()) {
      snapshot = Snapshot.UNAVAILABLE;
      return;
    }
    try {
      LinuxProcFs.CgroupInfo cg = binding.current();
      Path base = cg.resolved;
      if (base == null || !Files.isDirectory(base) || "none".equals(cg.version)) {
        snapshot = Snapshot.UNAVAILABLE;
        return;
      }
      long[] values = {-1, -1, -1, -1, -1, -1, -1, -1};
      if ("v2".equals(cg.version)) {
        values[LIMIT] = effectiveLimit(base, cg.baseDir, "memory.max", false);
        values[USAGE] = LinuxProcFs.readFirstNumber(base.resolve("memory.current"));
        values[WORKING_SET] = computeWorkingSet(base, values[USAGE], "inactive_file");
        values[SWAP_USAGE] = readNumber(base.resolve("memory.swap.current"));
        values[SWAP_LIMIT] = readNumber(base.resolve("memory.swap.max"));
        Map<String, Long> events = readKeyValuesOrEmpty(base.resolve("memory.events"));
        values[OOM_KILLS] = counter(events, "oom_kill");
        values[MAX_EVENTS] = counter(events, "max");
        values[HIGH_EVENTS] = counter(events, "high");
      } else if ("v1".equals(cg.version)) {
        values[LIMIT] = effectiveLimit(base, cg.baseDir, "memory.limit_in_bytes", true);
        values[USAGE] = LinuxProcFs.readFirstNumber(base.resolve("memory.usage_in_bytes"));
        values[WORKING_SET] =
            computeWorkingSet(base, values[USAGE], "total_inactive_file", "inactive_file");
        long[] swap = computeV1Swap(base, values[USAGE]);
        values[SWAP_USAGE] = swap[0];
        values[SWAP_LIMIT] = swap[1];
        values[OOM_KILLS] =
            counter(readKeyValuesOrEmpty(base.resolve("memory.oom_control")), "oom_kill");
        values[MAX_EVENTS] = readNumber(base.resolve("memory.failcnt"));
      }
      snapshot = new Snapshot(cg, values);
    } catch (Throwable failure) { // NOSONAR - never retain another group's values after failure
      recordRefreshFailure(failure);
      snapshot = Snapshot.UNAVAILABLE;
    }
  }

  /**
   * cgroup v1 accounts memory and swap together ({@code memsw}), so swap alone is the difference.
   * The files exist only when swap accounting is enabled; otherwise both values stay -1.
   */
  private static long[] computeV1Swap(Path base, long memoryUsage) {
    long memsw = readNumber(base.resolve("memory.memsw.usage_in_bytes"));
    long swapUsage = (memsw >= 0L && memoryUsage >= 0L) ? Math.max(0L, memsw - memoryUsage) : -1L;

    long memswLimit = unlessUnlimited(readNumber(base.resolve("memory.memsw.limit_in_bytes")));
    long ownMemoryLimit = unlessUnlimited(readNumber(base.resolve("memory.limit_in_bytes")));
    long swapLimit =
        (memswLimit >= 0L && ownMemoryLimit >= 0L)
            ? Math.max(0L, memswLimit - ownMemoryLimit)
            : -1L;
    return new long[] {swapUsage, swapLimit};
  }

  /** Key/value lines of a cgroup file, or an empty map when it is missing or unreadable. */
  private static Map<String, Long> readKeyValuesOrEmpty(Path file) {
    try {
      return MemStatsUtil.readKeyValues(file);
    } catch (CollectorInput.LimitExceededException e) {
      throw new java.io.UncheckedIOException(e);
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
      String text = CollectorInput.readString(file, CollectorInput.NUMBER).trim();
      return "max".equals(text) ? -1L : Long.parseLong(text);
    } catch (CollectorInput.LimitExceededException e) {
      throw new java.io.UncheckedIOException(e);
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
  private long effectiveLimit(Path base, Path root, String limitFile, boolean v1) {
    if (CollectorInput.excessiveAncestors(base, root)) {
      return -1L;
    }
    long best = readLimit(base, limitFile, v1);
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
    long value = readNumber(dir.resolve(limitFile));
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
    return snapshot.values[LIMIT];
  }

  /**
   * Returns the cgroup memory usage.
   *
   * @return the usage in bytes, or -1 when unavailable
   */
  @Override
  public long getMemoryUsageBytes() {
    refreshOnRead();
    return snapshot.values[USAGE];
  }

  /**
   * Returns the cgroup working set (usage minus inactive file cache).
   *
   * @return the working set in bytes, or -1 when unavailable
   */
  @Override
  public long getMemoryWorkingSetBytes() {
    refreshOnRead();
    return snapshot.values[WORKING_SET];
  }

  /**
   * Returns the detected cgroup version.
   *
   * @return {@code "v1"}, {@code "v2"}, or {@code "none"}
   */
  @Override
  public String getCgroupVersion() {
    return snapshot.version;
  }

  /**
   * Returns the detected cgroup path.
   *
   * @return the path, or an empty string if not available
   */
  @Override
  public String getCgroupPath() {
    return snapshot.path;
  }

  /**
   * Returns the swap used by the cgroup.
   *
   * @return the swap usage in bytes, or -1 when unavailable
   */
  @Override
  public long getSwapUsageBytes() {
    refreshOnRead();
    return snapshot.values[SWAP_USAGE];
  }

  /**
   * Returns the swap limit of the cgroup.
   *
   * @return the limit in bytes, 0 when swap is not allowed, or -1 when unlimited/unavailable
   */
  @Override
  public long getSwapLimitBytes() {
    refreshOnRead();
    return snapshot.values[SWAP_LIMIT];
  }

  /**
   * Returns the cumulative number of times the cgroup hit its memory limit.
   *
   * @return the count, or -1 when unavailable
   */
  @Override
  public Long getMemoryMaxEventsTotal() {
    refreshOnRead();
    return counterOrNull(snapshot.values[MAX_EVENTS]);
  }

  /**
   * Returns the cumulative number of times the cgroup went over {@code memory.high}.
   *
   * @return the count, or -1 when unavailable
   */
  @Override
  public Long getMemoryHighEventsTotal() {
    refreshOnRead();
    return counterOrNull(snapshot.values[HIGH_EVENTS]);
  }

  /**
   * Returns the cumulative OOM kills in this cgroup.
   *
   * @return the count, or -1 when unavailable
   */
  @Override
  public Long getMemoryOomKillTotal() {
    refreshOnRead();
    return counterOrNull(snapshot.values[OOM_KILLS]);
  }
}
