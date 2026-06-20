package co.pletor.nodemetrics.metrics;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Implementation of {@link CpuMetricsMBean} backed by the platform
 * {@link java.lang.management.OperatingSystemMXBean} and Linux
 * {@code /proc} and {@code /sys/fs/cgroup} files.
 * <p>
 * This class:
 * <ul>
 *   <li>Polls system and process CPU usage from the platform MXBean</li>
 *   <li>Computes extended CPU state ratios (I/O wait, steal) from
 *       {@code /proc/stat} on Linux</li>
 *   <li>Reads 1m/5m/15m load averages from {@code /proc/loadavg} on Linux</li>
 *   <li>Reads cgroup CPU throttling counters from {@code cpu.stat} when available</li>
 *   <li>Falls back to sentinel values (e.g. -1) when metrics are not supported</li>
 * </ul>
 * The refresh mechanism is invoked on-demand when JMX attributes are queried.
 */
public class CpuMetrics implements CpuMetricsMBean, RefreshManagedMetric {

  // ------------------------------------------------------------------------
  // MXBean-backed metrics
  // ------------------------------------------------------------------------

  // Visible for testing
  static Path procRoot = Paths.get("/proc");
  static Path sysRoot = Paths.get("/sys");
  private static final String CPU_STAT_FILE = "cpu.stat";
  private static final String CPU_CONTROLLER = "cpu";
  private static final String CPUACCT_CONTROLLER = "cpuacct";
  private static final String CPU_CPUACCT_CONTROLLER = "cpu,cpuacct";

  /**
   * Base operating system MXBean from the Java platform.
   */
  private final OperatingSystemMXBean osBean;

  /**
   * Last computed system CPU load in the range {@code 0.0}–{@code 1.0},
   * or {@code -1.0} when unavailable.
   */
  private volatile double systemCpuLoad = -1.0;

  /**
   * Last computed JVM process CPU load in the range {@code 0.0}–{@code 1.0},
   * or {@code -1.0} when unavailable.
   */
  private volatile double processCpuLoad = -1.0;

  /**
   * Last reported system-wide 1-minute load average, or {@code -1.0} when unsupported.
   */
  private volatile double systemLoadAverage = -1.0;

  /**
   * Number of available logical processors as reported by the OS.
   */
  private volatile int availableProcessors =
      Runtime.getRuntime().availableProcessors();

  /**
   * JVM process CPU time in nanoseconds, or {@code 0L} when unsupported.
   */
  private volatile long processCpuTimeNanos = 0L;

  // ------------------------------------------------------------------------
  // Extended CPU state metrics (Linux-only; -1.0 / -1L when unsupported)
  // ------------------------------------------------------------------------

  /**
   * Fraction of time CPUs spent in I/O wait state since the last successful poll.
   */
  private volatile double ioWaitRatio = -1.0;

  /**
   * Fraction of time CPUs spent in steal state since the last successful poll.
   */
  private volatile double stealRatio = -1.0;

  /**
   * 1, 5 and 15 minute load averages.
   * The 1-minute value typically matches {@link #systemLoadAverage}.
   */
  private volatile double loadAvg1m = -1.0;
  private volatile double loadAvg5m = -1.0;
  private volatile double loadAvg15m = -1.0;

  /**
   * Fraction of cgroup CPU time that was throttled during the last polling window.
   */
  private volatile double cgroupThrottledRatio = -1.0;

  /**
   * Number of cgroup CPU throttling events during the last polling window.
   */
  private volatile long cgroupThrottledCount = 0L;

  // Snapshot state for delta-based CPU time calculations
  private long prevCpuUser;
  private long prevCpuNice;
  private long prevCpuSystem;
  private long prevCpuIdle;
  private long prevCpuIoWait;
  private long prevCpuIrq;
  private long prevCpuSoftIrq;
  private long prevCpuSteal;
  private boolean cpuTimesInitialized = false;

  // Snapshot state for cgroup throttling calculations
  private long prevCgroupUsageNs;
  private long prevCgroupThrottledNs;
  private long prevCgroupThrottledPeriods;
  private boolean cgroupCpuInitialized = false;

  // ------------------------------------------------------------------------
  // Construction
  // ------------------------------------------------------------------------

  /**
   * Create a new CpuMetrics instance backed by the platform MXBean.
   */
  public CpuMetrics() {
    this(resolveOsBean());
  }

  /**
   * Create a new CpuMetrics instance with a specific MXBean.
   * Primarily intended for tests.
   */
  CpuMetrics(OperatingSystemMXBean osBean) {
    this.osBean = osBean;
    if (osBean != null) {
      this.availableProcessors = osBean.getAvailableProcessors();
    }
  }

  /**
   * Resolve the platform OperatingSystemMXBean and cast to the
   * {@link com.sun.management.OperatingSystemMXBean} extension if possible.
   * <p>
   * This method uses Java 17 pattern matching for {@code instanceof}.
   */
  private static OperatingSystemMXBean resolveOsBean() {
    var base = ManagementFactory.getOperatingSystemMXBean();
    if (base instanceof OperatingSystemMXBean) {
      return (OperatingSystemMXBean) base;
    }
    // When the extended MXBean is not available, metrics remain at sentinel values.
    return null;
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

    // Update common MXBean-backed metrics
    updateMxBeanMetrics();

    // Update Linux-specific extended metrics
    updateExtendedLinuxMetrics();
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

  /**
   * Update metrics that are backed by the OperatingSystemMXBean.
   */
  @SuppressWarnings("deprecation")
  private void updateMxBeanMetrics() {
    if (osBean == null) {
      return;
    }

    try {
      // System CPU load in [0.0, 1.0] or -1.0.
      systemCpuLoad = normalizeRatio(osBean.getSystemCpuLoad());

      // Process CPU load in [0.0, 1.0] or -1.0.
      processCpuLoad = normalizeRatio(osBean.getProcessCpuLoad());

      double la = osBean.getSystemLoadAverage();
      systemLoadAverage = (la >= 0.0) ? la : -1.0;

      availableProcessors = osBean.getAvailableProcessors();

      long cpuTime = osBean.getProcessCpuTime();
      processCpuTimeNanos = (cpuTime >= 0L) ? cpuTime : 0L;
    } catch (RuntimeException e) {
      // On any unexpected failure we keep previously computed values.
    }
  }

  /**
   * Normalize MXBean ratio values to {@code -1.0} when outside [0.0, 1.0].
   */
  private static double normalizeRatio(double value) {
    if (value < 0.0 || value > 1.0 || Double.isNaN(value)) {
      return -1.0;
    }
    return value;
  }

  /**
   * Update Linux-specific extended CPU metrics.
   * On non-Linux platforms this method is a no-op.
   */
  private void updateExtendedLinuxMetrics() {
    if (!isLinux()) {
      // Keep default sentinel values (-1.0 / -1L) on non-Linux systems.
      return;
    }

    // Update CPU state ratios (iowait / steal) from /proc/stat
    try {
      CpuTimes times = readCpuTimes();
      if (times != null) {
        computeCpuStateRatios(times);
      }
    } catch (Exception ignored) {
      // On failure we keep the last successfully computed values.
    }

    // Update load averages (1m / 5m / 15m) from /proc/loadavg
    try {
      LoadAverages la = readLoadAverages();
      if (la != null) {
        loadAvg1m = la.load1;
        loadAvg5m = la.load5;
        loadAvg15m = la.load15;
      }
    } catch (Exception ignored) {
      // Keep previous values.
    }

    // Update cgroup CPU throttling metrics, if available
    try {
      CgroupCpuStats cg = readCgroupCpuStats();
      if (cg != null) {
        computeCgroupRatios(cg);
      }
    } catch (Exception ignored) {
      // Keep previous values.
    }
  }

  /**
   * Lightweight OS check so we do not attempt to read /proc on non-Linux systems.
   */
  private static boolean isLinux() {
    try {
      String osName = System.getProperty("os.name", "");
      return osName != null && osName.toLowerCase(Locale.ROOT).contains("linux");
    } catch (RuntimeException e) {
      return false;
    }
  }

  // ------------------------------------------------------------------------
  // /proc/stat helpers (CPU times, iowait / steal ratios)
  // ------------------------------------------------------------------------

  /**
   * Simple snapshot of aggregated CPU times from /proc/stat.
   */
  static final class CpuTimes {
    final long user;
    final long nice;
    final long system;
    final long idle;
    final long iowait;
    final long irq;
    final long softirq;
    final long steal;

    CpuTimes(long user, long nice, long system, long idle,
             long iowait, long irq, long softirq, long steal) {
      this.user = user;
      this.nice = nice;
      this.system = system;
      this.idle = idle;
      this.iowait = iowait;
      this.irq = irq;
      this.softirq = softirq;
      this.steal = steal;
    }
  }

  /**
   * Read the first "cpu" line from /proc/stat.
   */
  private static CpuTimes readCpuTimes() throws java.io.IOException {
    Path path = procRoot.resolve("stat");
    List<String> lines = Files.readAllLines(path, StandardCharsets.US_ASCII);

    if (lines.isEmpty()) {
      return null;
    }

    String first = lines.get(0);
    // Expected format: cpu  user nice system idle iowait irq softirq steal ...
    if (!first.startsWith("cpu ")) {
      return null;
    }

    String[] tokens = first.trim().split("\\s+");
    if (tokens.length < 8) {
      return null;
    }

    long user = parseLongQuiet(tokens[1]);
    long nice = parseLongQuiet(tokens[2]);
    long system = parseLongQuiet(tokens[3]);
    long idle = parseLongQuiet(tokens[4]);
    long iowait = parseLongQuiet(tokens[5]);
    long irq = parseLongQuiet(tokens[6]);
    long softirq = parseLongQuiet(tokens[7]);
    long steal = tokens.length > 8 ? parseLongQuiet(tokens[8]) : 0L;

    return new CpuTimes(user, nice, system, idle, iowait, irq, softirq, steal);
  }

  private static long parseLongQuiet(String s) {
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  /**
   * Compute iowait / steal ratios from two consecutive CpuTimes snapshots.
   */
  private void computeCpuStateRatios(CpuTimes current) {
    long totalCurrent =
        current.user + current.nice + current.system + current.idle +
            current.iowait + current.irq + current.softirq + current.steal;

    if (!cpuTimesInitialized) {
      // First snapshot: just initialize and return without computing ratios.
      prevCpuUser = current.user;
      prevCpuNice = current.nice;
      prevCpuSystem = current.system;
      prevCpuIdle = current.idle;
      prevCpuIoWait = current.iowait;
      prevCpuIrq = current.irq;
      prevCpuSoftIrq = current.softirq;
      prevCpuSteal = current.steal;
      cpuTimesInitialized = true;
      return;
    }

    long totalPrev =
        prevCpuUser + prevCpuNice + prevCpuSystem + prevCpuIdle +
            prevCpuIoWait + prevCpuIrq + prevCpuSoftIrq + prevCpuSteal;

    long totalDelta = totalCurrent - totalPrev;
    if (totalDelta <= 0L) {
      // Avoid division by zero or negative deltas.
      return;
    }

    long iowaitDelta = current.iowait - prevCpuIoWait;
    long stealDelta = current.steal - prevCpuSteal;

    // Clamp to [0, totalDelta]
    if (iowaitDelta < 0L) iowaitDelta = 0L;
    if (stealDelta < 0L) stealDelta = 0L;
    if (iowaitDelta > totalDelta) iowaitDelta = totalDelta;
    if (stealDelta > totalDelta) stealDelta = totalDelta;

    ioWaitRatio = (double) iowaitDelta / (double) totalDelta;
    stealRatio = (double) stealDelta / (double) totalDelta;

    // Update previous snapshot
    prevCpuUser = current.user;
    prevCpuNice = current.nice;
    prevCpuSystem = current.system;
    prevCpuIdle = current.idle;
    prevCpuIoWait = current.iowait;
    prevCpuIrq = current.irq;
    prevCpuSoftIrq = current.softirq;
    prevCpuSteal = current.steal;
  }

  // ------------------------------------------------------------------------
  // /proc/loadavg helpers (1m / 5m / 15m load averages)
  // ------------------------------------------------------------------------

  /**
   * Simple holder for system load averages.
   */
  private static final class LoadAverages {
    final double load1;
    final double load5;
    final double load15;

    LoadAverages(double load1, double load5, double load15) {
      this.load1 = load1;
      this.load5 = load5;
      this.load15 = load15;
    }
  }

  /**
   * Read load averages from /proc/loadavg.
   */
  private static LoadAverages readLoadAverages() throws java.io.IOException {
    Path path = procRoot.resolve("loadavg");
    String line = Files.readString(path, StandardCharsets.US_ASCII).trim();

    String[] tokens = line.split("\\s+");
    if (tokens.length < 3) {
      return null;
    }

    double l1 = parseDoubleQuiet(tokens[0]);
    double l5 = parseDoubleQuiet(tokens[1]);
    double l15 = parseDoubleQuiet(tokens[2]);

    return new LoadAverages(l1, l5, l15);
  }

  private static double parseDoubleQuiet(String s) {
    try {
      return Double.parseDouble(s);
    } catch (NumberFormatException e) {
      return -1.0;
    }
  }

  // ------------------------------------------------------------------------
  // Cgroup CPU throttling helpers
  // ------------------------------------------------------------------------

  /**
   * Snapshot of cgroup CPU usage and throttling counters.
   */
  static final class CgroupCpuStats {
    final long usageNs;            // total CPU usage (best-effort)
    final long throttledNs;        // total throttled time
    final long throttledPeriods;   // number of throttling periods

    CgroupCpuStats(long usageNs, long throttledNs, long throttledPeriods) {
      this.usageNs = usageNs;
      this.throttledNs = throttledNs;
      this.throttledPeriods = throttledPeriods;
    }
  }

  /**
   * Read cgroup CPU statistics from common v2/v1 locations.
   * <p>
   * This method delegates to small helper methods to keep complexity low:
   * <ul>
   *   <li>{@link #findCgroupCpuStatPath(Path)} locates the cpu.stat file</li>
   *   <li>{@link #parseCgroupCpuStats(Path)} parses the counters</li>
   *   <li>{@link #readCpuAcctUsageIfPresent(Path)} optionally fills usage</li>
   * </ul>
   * If no meaningful counters are found, this method returns {@code null}.
   */
  private static CgroupCpuStats readCgroupCpuStats() throws java.io.IOException {
    Path baseDir = sysRoot.resolve("fs/cgroup");

    Path statPath = findProcessScopedCgroupCpuStatPath(baseDir);
    if (statPath == null) {
      statPath = findCgroupCpuStatPath(baseDir);
    }
    if (statPath == null) {
      return null;
    }

    CgroupCpuStats rawStats = parseCgroupCpuStats(statPath);
    if (rawStats == null) {
      return null;
    }

    long usageNs = rawStats.usageNs;
    if (usageNs < 0L) {
      // Try to fill usage from cpuacct.usage if available
      long acctUsage = readCpuAcctUsageIfPresent(baseDir);
      if (acctUsage >= 0L) {
        usageNs = acctUsage;
      }
    }

    // If we still have no useful values, return null to signal "unsupported"
    if (usageNs < 0L &&
        rawStats.throttledNs < 0L &&
        rawStats.throttledPeriods < 0L) {
      return null;
    }

    return new CgroupCpuStats(
        usageNs,
        rawStats.throttledNs,
        rawStats.throttledPeriods
    );
  }

  /**
   * Try to locate cpu.stat under the current process cgroup path first.
   * This avoids reporting host-level values inside containers.
   */
  private static Path findProcessScopedCgroupCpuStatPath(Path baseDir) {
    Path selfCgroup = procRoot.resolve("self/cgroup");
    if (!Files.isRegularFile(selfCgroup)) {
      return null;
    }

    Path v2Controllers = baseDir.resolve("cgroup.controllers");
    if (Files.isRegularFile(v2Controllers)) {
      return findV2ProcessScopedCpuStatPath(baseDir, selfCgroup);
    }

    List<Path> candidates = collectV1ProcessScopedCpuStatCandidates(baseDir, selfCgroup);
    for (Path p : candidates) {
      if (Files.isRegularFile(p)) {
        return p;
      }
    }
    return null;
  }

  private static Path findV2ProcessScopedCpuStatPath(Path baseDir, Path selfCgroup) {
    // cgroup v2: look up "0::<path>" in /proc/self/cgroup.
    String rel = readUnifiedCgroupPath(selfCgroup);
    Path scopedDir = resolveCgroupSubdir(baseDir, rel);
    Path scopedStat = scopedDir.resolve(CPU_STAT_FILE);
    return Files.isRegularFile(scopedStat) ? scopedStat : null;
  }

  private static List<Path> collectV1ProcessScopedCpuStatCandidates(Path baseDir, Path selfCgroup) {
    // cgroup v1: collect cpu/cpuacct controller paths and probe common mount layouts.
    List<Path> candidates = new ArrayList<>();
    try {
      for (String ln : Files.readAllLines(selfCgroup, StandardCharsets.US_ASCII)) {
        appendV1CpuStatCandidates(baseDir, candidates, ln);
      }
    } catch (Exception ignored) {
      return List.of();
    }
    return candidates;
  }

  private static void appendV1CpuStatCandidates(Path baseDir, List<Path> candidates, String cgroupLine) {
    String[] parts = cgroupLine.split(":", 3);
    if (parts.length != 3) {
      return;
    }
    String controllers = parts[1];
    String rel = parts[2];

    if (hasController(controllers, CPU_CONTROLLER)) {
      Path cpuDir = resolveCgroupSubdir(baseDir.resolve(CPU_CONTROLLER), rel);
      candidates.add(cpuDir.resolve(CPU_STAT_FILE));
    }
    if (hasController(controllers, CPUACCT_CONTROLLER)) {
      Path cpuAcctDir = resolveCgroupSubdir(baseDir.resolve(CPUACCT_CONTROLLER), rel);
      candidates.add(cpuAcctDir.resolve(CPU_STAT_FILE));
    }
    if (hasController(controllers, CPU_CONTROLLER) && hasController(controllers, CPUACCT_CONTROLLER)) {
      Path combined = resolveCgroupSubdir(baseDir.resolve(CPU_CPUACCT_CONTROLLER), rel);
      candidates.add(combined.resolve(CPU_STAT_FILE));
    }
  }

  private static String readUnifiedCgroupPath(Path selfCgroup) {
    try {
      for (String ln : Files.readAllLines(selfCgroup, StandardCharsets.US_ASCII)) {
        String[] parts = ln.split(":", 3);
        if (parts.length == 3 && "0".equals(parts[0])) {
          return parts[2];
        }
      }
    } catch (Exception ignored) {
      return "/";
    }
    return "/";
  }

  private static boolean hasController(String controllers, String target) {
    String[] parts = controllers.split(",");
    for (String p : parts) {
      if (target.equals(p.trim())) {
        return true;
      }
    }
    return false;
  }

  private static Path resolveCgroupSubdir(Path baseDir, String cgroupPath) {
    if (cgroupPath == null || cgroupPath.isEmpty() || "/".equals(cgroupPath)) {
      return baseDir;
    }
    String relative = cgroupPath.replaceFirst("^/", "");
    return baseDir.resolve(relative);
  }

  /**
   * Find a cpu.stat file for cgroup v2 or common v1 layouts.
   * <p>
   * The search order is:
   * <ol>
   *   <li>/sys/fs/cgroup/cpu.stat (cgroup v2 unified hierarchy)</li>
   *   <li>/sys/fs/cgroup/cpu/cpu.stat (cgroup v1 cpu controller)</li>
   *   <li>/sys/fs/cgroup/cpuacct/cpu.stat (cgroup v1 cpuacct controller)</li>
   * </ol>
   *
   * @param baseDir base cgroup directory, typically {@code /sys/fs/cgroup}
   * @return existing cpu.stat path, or {@code null} when none is found
   */
  private static Path findCgroupCpuStatPath(Path baseDir) {
    Path v2Stat = baseDir.resolve(CPU_STAT_FILE);
    if (Files.isRegularFile(v2Stat)) {
      return v2Stat;
    }

    Path v1Cpu = baseDir.resolve(CPU_CONTROLLER + "/" + CPU_STAT_FILE);
    if (Files.isRegularFile(v1Cpu)) {
      return v1Cpu;
    }

    Path v1CpuAcct = baseDir.resolve(CPUACCT_CONTROLLER + "/" + CPU_STAT_FILE);
    if (Files.isRegularFile(v1CpuAcct)) {
      return v1CpuAcct;
    }

    return null;
  }

  /**
   * Parse a cpu.stat file into {@link CgroupCpuStats}.
   * <p>
   * This method understands both cgroup v2 and common cgroup v1 keys:
   * <ul>
   *   <li>v2: usage_usec, nr_periods, nr_throttled, throttled_usec</li>
   *   <li>v1: nr_periods, nr_throttled, throttled_time (ns)</li>
   * </ul>
   *
   * @param statPath path to cpu.stat
   * @return parsed stats, or {@code null} when no useful data is found
   */
  private static CgroupCpuStats parseCgroupCpuStats(Path statPath) throws java.io.IOException {
    List<String> lines = Files.readAllLines(statPath, StandardCharsets.US_ASCII);

    long usageNs = -1L;
    long throttledNs = -1L;
    long throttledPeriods = -1L;

    for (String line : lines) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      String[] parts = trimmed.split("\\s+");
      if (parts.length != 2) {
        continue;
      }

      String key = parts[0];
      long value = parseLongStrict(parts[1]);
      if (value < 0L) {
        // Malformed lines should be ignored, not converted to a valid zero value.
        continue;
      }

      switch (key) {
        case "usage_usec":
          usageNs = value * 1_000L;
          break;
        case "nr_throttled":
          throttledPeriods = value;
          break;
        case "throttled_usec":
          throttledNs = value * 1_000L;
          break;
        case "throttled_time":
          throttledNs = value; // v1 (already in ns)
          break;
        default:
          // ignore unknown keys
      }
    }

    if (usageNs < 0L && throttledNs < 0L && throttledPeriods < 0L) {
      return null;
    }
    return new CgroupCpuStats(usageNs, throttledNs, throttledPeriods);
  }

  /**
   * Attempt to read CPU usage from {@code cpuacct.usage} when cpu.stat
   * does not provide a usage counter.
   *
   * @param baseDir base cgroup directory
   * @return usage in nanoseconds, or {@code -1L} when not available
   */
  private static long readCpuAcctUsageIfPresent(Path baseDir) {
    Path scopedPath = findProcessScopedCpuAcctUsagePath(baseDir);
    if (scopedPath != null && Files.isRegularFile(scopedPath)) {
      try {
        String txt = Files.readString(scopedPath, StandardCharsets.US_ASCII).trim();
        return parseLongStrict(txt);
      } catch (Exception ignored) {
        return -1L;
      }
    }

    Path usagePath = baseDir.resolve("cpuacct/cpuacct.usage");
    if (Files.isRegularFile(usagePath)) {
      try {
        String txt = Files.readString(usagePath, StandardCharsets.US_ASCII).trim();
        return parseLongStrict(txt);
      } catch (Exception ignored) {
        return -1L;
      }
    }

    return -1L;
  }

  private static Path findProcessScopedCpuAcctUsagePath(Path baseDir) {
    Path selfCgroup = procRoot.resolve("self/cgroup");
    if (!Files.isRegularFile(selfCgroup)) {
      return null;
    }

    try {
      for (String ln : Files.readAllLines(selfCgroup, StandardCharsets.US_ASCII)) {
        String[] parts = ln.split(":", 3);
        if (parts.length != 3) {
          continue;
        }
        String controllers = parts[1];
        String rel = parts[2];

        if (hasController(controllers, CPUACCT_CONTROLLER)) {
          Path cpuAcctDir = resolveCgroupSubdir(baseDir.resolve(CPUACCT_CONTROLLER), rel);
          return cpuAcctDir.resolve("cpuacct.usage");
        }
        if (hasController(controllers, CPU_CONTROLLER) && hasController(controllers, CPUACCT_CONTROLLER)) {
          Path combined = resolveCgroupSubdir(baseDir.resolve(CPU_CPUACCT_CONTROLLER), rel);
          return combined.resolve("cpuacct.usage");
        }
      }
    } catch (Exception ignored) {
      return null;
    }
    return null;
  }

  private static long parseLongStrict(String s) {
    try {
      return Long.parseLong(s.trim());
    } catch (NumberFormatException e) {
      return -1L;
    }
  }

  /**
   * Compute throttling ratio and count for the last polling window.
   */
  private void computeCgroupRatios(CgroupCpuStats current) {
    if (!cgroupCpuInitialized) {
      prevCgroupUsageNs = current.usageNs;
      prevCgroupThrottledNs = current.throttledNs;
      prevCgroupThrottledPeriods = current.throttledPeriods;
      cgroupCpuInitialized = true;
      return;
    }

    long usageDelta = current.usageNs - prevCgroupUsageNs;
    long throttledDelta = current.throttledNs - prevCgroupThrottledNs;
    long periodsDelta = current.throttledPeriods - prevCgroupThrottledPeriods;

    if (usageDelta < 0L) usageDelta = 0L;
    if (throttledDelta < 0L) throttledDelta = 0L;
    if (periodsDelta < 0L) periodsDelta = 0L;

    long denom = usageDelta + throttledDelta;
    if (denom > 0L) {
      double ratio = (double) throttledDelta / (double) denom;
      if (ratio < 0.0) ratio = 0.0;
      if (ratio > 1.0) ratio = 1.0;
      cgroupThrottledRatio = ratio;
    }

    cgroupThrottledCount = periodsDelta;

    prevCgroupUsageNs = current.usageNs;
    prevCgroupThrottledNs = current.throttledNs;
    prevCgroupThrottledPeriods = current.throttledPeriods;
  }

  // ------------------------------------------------------------------------
  // MBean getters
  // ------------------------------------------------------------------------

  @Override
  public double getSystemCpuLoad() {
    refreshOnRead();
    return systemCpuLoad;
  }

  @Override
  public double getProcessCpuLoad() {
    refreshOnRead();
    return processCpuLoad;
  }

  @Override
  public double getSystemLoadAverage() {
    refreshOnRead();
    return systemLoadAverage;
  }

  @Override
  public int getAvailableProcessors() {
    refreshOnRead();
    return availableProcessors;
  }

  @Override
  public long getProcessCpuTimeNanos() {
    refreshOnRead();
    return processCpuTimeNanos;
  }

  @Override
  public double getSystemCpuIoWaitRatio() {
    refreshOnRead();
    return ioWaitRatio;
  }

  @Override
  public double getSystemCpuStealRatio() {
    refreshOnRead();
    return stealRatio;
  }

  @Override
  public double getSystemLoadAverage1m() {
    refreshOnRead();

    // If we have successfully read /proc/loadavg, prefer that.
    if (loadAvg1m >= 0.0) {
      return loadAvg1m;
    }
    // Fallback to the original 1-minute load average field.
    return systemLoadAverage;
  }

  @Override
  public double getSystemLoadAverage5m() {
    refreshOnRead();
    return loadAvg5m;
  }

  @Override
  public double getSystemLoadAverage15m() {
    refreshOnRead();
    return loadAvg15m;
  }

  @Override
  public double getCgroupCpuThrottledRatio() {
    refreshOnRead();
    return cgroupThrottledRatio;
  }

  @Override
  public long getCgroupCpuThrottledCount() {
    refreshOnRead();
    return cgroupThrottledCount;
  }
}
