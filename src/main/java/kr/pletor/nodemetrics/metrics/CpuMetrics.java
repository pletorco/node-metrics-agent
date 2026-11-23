package kr.pletor.nodemetrics.metrics;

import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Implementation of {@link CpuMetricsMBean} backed by the platform {@link
 * java.lang.management.OperatingSystemMXBean} and Linux {@code /proc} and {@code /sys/fs/cgroup}
 * files.
 *
 * <p>This class:
 *
 * <ul>
 *   <li>Polls system and process CPU usage from the platform MXBean
 *   <li>Computes extended CPU state ratios (I/O wait, steal) from {@code /proc/stat} on Linux
 *   <li>Reads 1m/5m/15m load averages from {@code /proc/loadavg} on Linux
 *   <li>Reads cgroup CPU throttling counters from {@code cpu.stat} when available
 *   <li>Falls back to sentinel values (e.g. -1) when metrics are not supported
 * </ul>
 *
 * <p>The refresh mechanism is invoked on-demand when JMX attributes are queried.
 */
public class CpuMetrics extends AbstractRefreshingMetric implements CpuMetricsMBean {

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

  /** Base operating system MXBean from the Java platform. */
  private final OperatingSystemMXBean osBean;

  /**
   * Last computed system CPU load in the range {@code 0.0}–{@code 1.0}, or {@code -1.0} when
   * unavailable.
   */
  private volatile double systemCpuLoad = -1.0;

  /**
   * Last computed JVM process CPU load in the range {@code 0.0}–{@code 1.0}, or {@code -1.0} when
   * unavailable.
   */
  private volatile double processCpuLoad = -1.0;

  /** Last reported system-wide 1-minute load average, or {@code -1.0} when unsupported. */
  private volatile double systemLoadAverage = -1.0;

  /** Number of available logical processors as reported by the OS. */
  private volatile int availableProcessors = Runtime.getRuntime().availableProcessors();

  /** JVM process CPU time in nanoseconds, or {@code 0L} when unsupported. */
  private volatile long processCpuTimeNanos = 0L;

  // ------------------------------------------------------------------------
  // Extended CPU state metrics (Linux-only; -1.0 / -1L when unsupported)
  // ------------------------------------------------------------------------

  /** Fraction of time CPUs spent in I/O wait state since the last successful poll. */
  private volatile double ioWaitRatio = -1.0;

  /** Fraction of time CPUs spent in steal state since the last successful poll. */
  private volatile double stealRatio = -1.0;

  /**
   * 1, 5 and 15 minute load averages. The 1-minute value typically matches {@link
   * #systemLoadAverage}.
   */
  private volatile double loadAvg1m = -1.0;

  private volatile double loadAvg5m = -1.0;
  private volatile double loadAvg15m = -1.0;

  /** Fraction of cgroup CPU time that was throttled during the last polling window. */
  private volatile double cgroupThrottledRatio = -1.0;

  /** Number of cgroup CPU throttling events during the last polling window. */
  private volatile long cgroupThrottledCount = 0L;

  /** Effective cgroup CPU limit in cores; -1.0 when unlimited or unavailable. */
  private volatile double cgroupCpuLimitCores = -1.0;

  // Cumulative counters (monotonic; -1 when unavailable)
  private volatile long cpuTotalTicks = -1L;
  private volatile long cpuIoWaitTicks = -1L;
  private volatile long cpuStealTicks = -1L;
  private volatile long cpuUserTicks = -1L;
  private volatile long cpuNiceTicks = -1L;
  private volatile long cpuSystemTicks = -1L;
  private volatile long cpuIdleTicks = -1L;
  private volatile long cpuIrqTicks = -1L;
  private volatile long cpuSoftIrqTicks = -1L;
  private volatile long cgroupThrottledPeriodsTotal = -1L;
  private volatile long cgroupThrottledTimeNanosTotal = -1L;
  private volatile long cgroupUsageNanosTotal = -1L;

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

  /** Create a new CpuMetrics instance backed by the platform MXBean. */
  public CpuMetrics() {
    this(resolveOsBean());
  }

  /** Create a new CpuMetrics instance with a specific MXBean. Primarily intended for tests. */
  CpuMetrics(OperatingSystemMXBean osBean) {
    this.osBean = osBean;
    if (osBean != null) {
      this.availableProcessors = osBean.getAvailableProcessors();
    }
  }

  /**
   * Resolve the platform OperatingSystemMXBean and cast to the {@link
   * com.sun.management.OperatingSystemMXBean} extension if possible.
   *
   * <p>Returns {@code null} when the platform bean is not the extended implementation.
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
  // Polling / Refreshing
  // ------------------------------------------------------------------------

  /** Refresh the metric values. */
  @Override
  protected void doRefresh() {
    // Update common MXBean-backed metrics
    updateMxBeanMetrics();

    // Update Linux-specific extended metrics
    updateExtendedLinuxMetrics();
  }

  /** Update metrics that are backed by the OperatingSystemMXBean. */
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
      recordRefreshFailure(e);
    }
  }

  /** Normalize MXBean ratio values to {@code -1.0} when outside [0.0, 1.0]. */
  private static double normalizeRatio(double value) {
    if (value < 0.0 || value > 1.0 || Double.isNaN(value)) {
      return -1.0;
    }
    return value;
  }

  /** Update Linux-specific extended CPU metrics. On non-Linux platforms this method is a no-op. */
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
    } catch (Exception e) {
      // On failure we keep the last successfully computed values.
      recordRefreshFailure(e);
    }

    // Update load averages (1m / 5m / 15m) from /proc/loadavg
    try {
      LoadAverages la = readLoadAverages();
      if (la != null) {
        loadAvg1m = la.load1;
        loadAvg5m = la.load5;
        loadAvg15m = la.load15;
      }
    } catch (Exception e) {
      // Keep previous values.
      recordRefreshFailure(e);
    }

    updateCgroupCpuMetrics();
  }

  private void updateCgroupCpuMetrics() {
    Path baseDir = sysRoot.resolve("fs/cgroup");
    try {
      CgroupCpuPaths paths = cgroupPaths(baseDir);
      CgroupCpuStats stats = paths.stat == null ? null : parseCgroupCpuStats(paths.stat);
      long usage = stats == null ? -1L : stats.usageNs;
      if (usage < 0L && paths.accounting != null) {
        usage = parseLongStrict(CollectorInput.readString(paths.accounting, CollectorInput.NUMBER));
      }
      if (stats == null && usage < 0L) {
        clearCgroupMetrics();
        return;
      }
      computeCgroupRatios(
          new CgroupCpuStats(
              usage,
              stats == null ? -1L : stats.throttledNs,
              stats == null ? -1L : stats.throttledPeriods));
      cgroupCpuLimitCores =
          stats == null ? -1.0 : CgroupCpuLimit.effectiveCores(paths.stat.getParent(), baseDir);
    } catch (IOException | RuntimeException e) {
      cachedCgroupBaseDir = null; // Re-resolve after an I/O failure, such as a removed group.
      clearCgroupMetrics();
      recordRefreshFailure(e);
    }
  }

  private void clearCgroupMetrics() {
    cgroupCpuInitialized = false;
    cgroupThrottledRatio = -1.0;
    cgroupThrottledCount = 0L;
    cgroupCpuLimitCores = -1.0;
    cgroupUsageNanosTotal = -1L;
    cgroupThrottledTimeNanosTotal = -1L;
    cgroupThrottledPeriodsTotal = -1L;
  }

  /** Lightweight OS check so we do not attempt to read /proc on non-Linux systems. */
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

  /** Simple snapshot of aggregated CPU times from /proc/stat. */
  static final class CpuTimes {
    final long user;
    final long nice;
    final long system;
    final long idle;
    final long iowait;
    final long irq;
    final long softirq;
    final long steal;

    CpuTimes(
        long user,
        long nice,
        long system,
        long idle,
        long iowait,
        long irq,
        long softirq,
        long steal) {
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

  /** Read the first "cpu" line from /proc/stat. */
  private static CpuTimes readCpuTimes() throws java.io.IOException {
    Path path = procRoot.resolve("stat");
    // Only the aggregate first line is needed. /proc/stat has one line per CPU plus a very long
    // "intr" line, so reading it all would allocate a String per line on every poll.
    String first = CollectorInput.readFirstLine(path);

    if (first == null) {
      return null;
    }

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

  /** Compute iowait / steal ratios from two consecutive CpuTimes snapshots. */
  private void computeCpuStateRatios(CpuTimes current) {
    long totalCurrent =
        current.user
            + current.nice
            + current.system
            + current.idle
            + current.iowait
            + current.irq
            + current.softirq
            + current.steal;

    cpuTotalTicks = totalCurrent;
    cpuIoWaitTicks = current.iowait;
    cpuStealTicks = current.steal;
    cpuUserTicks = current.user;
    cpuNiceTicks = current.nice;
    cpuSystemTicks = current.system;
    cpuIdleTicks = current.idle;
    cpuIrqTicks = current.irq;
    cpuSoftIrqTicks = current.softirq;

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
        prevCpuUser
            + prevCpuNice
            + prevCpuSystem
            + prevCpuIdle
            + prevCpuIoWait
            + prevCpuIrq
            + prevCpuSoftIrq
            + prevCpuSteal;

    long totalDelta = totalCurrent - totalPrev;
    if (totalDelta <= 0L) {
      // Avoid division by zero or negative deltas.
      return;
    }

    long iowaitDelta = current.iowait - prevCpuIoWait;
    long stealDelta = current.steal - prevCpuSteal;

    // Clamp to [0, totalDelta]
    if (iowaitDelta < 0L) {
      iowaitDelta = 0L;
    }
    if (stealDelta < 0L) {
      stealDelta = 0L;
    }
    if (iowaitDelta > totalDelta) {
      iowaitDelta = totalDelta;
    }
    if (stealDelta > totalDelta) {
      stealDelta = totalDelta;
    }

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

  /** Simple holder for system load averages. */
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

  /** Read load averages from /proc/loadavg. */
  private static LoadAverages readLoadAverages() throws java.io.IOException {
    Path path = procRoot.resolve("loadavg");
    String line = CollectorInput.readString(path, CollectorInput.NUMBER).trim();

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

  /** Snapshot of cgroup CPU usage and throttling counters. */
  static final class CgroupCpuStats {
    final long usageNs; // total CPU usage (best-effort)
    final long throttledNs; // total throttled time
    final long throttledPeriods; // number of throttling periods

    CgroupCpuStats(long usageNs, long throttledNs, long throttledPeriods) {
      this.usageNs = usageNs;
      this.throttledNs = throttledNs;
      this.throttledPeriods = throttledPeriods;
    }
  }

  private static final class CgroupCpuPaths {
    private final Path stat;
    private final Path accounting;

    private CgroupCpuPaths(Path stat, Path accounting) {
      this.stat = stat;
      this.accounting = accounting;
    }
  }

  // Membership is resolved at most once per TTL unless a read fails. Counters are read every poll.
  private static final CgroupCpuPaths NO_CGROUP_CPU_PATHS = new CgroupCpuPaths(null, null);
  private Path cachedCgroupBaseDir;
  private CgroupCpuPaths cachedCgroupPaths = NO_CGROUP_CPU_PATHS;
  private long cachedCgroupStatPathAtNanos;

  private CgroupCpuPaths cgroupPaths(Path baseDir) throws IOException {
    long now = System.nanoTime();
    if (!baseDir.equals(cachedCgroupBaseDir)
        || now - cachedCgroupStatPathAtNanos >= CgroupBinding.TTL_NANOS) {
      CgroupCpuPaths next = locateCgroupCpuPaths(baseDir);
      if (!Objects.equals(next.stat, cachedCgroupPaths.stat)
          || !Objects.equals(next.accounting, cachedCgroupPaths.accounting)) {
        clearCgroupMetrics();
      }
      cachedCgroupPaths = next;
      cachedCgroupBaseDir = baseDir;
      cachedCgroupStatPathAtNanos = now;
    }
    return cachedCgroupPaths;
  }

  private static CgroupCpuPaths locateCgroupCpuPaths(Path baseDir) throws IOException {
    List<String> lines;
    try {
      lines = CollectorInput.readLines(procRoot.resolve("self/cgroup"), CollectorInput.SMALL);
    } catch (NoSuchFileException missingMembership) {
      return NO_CGROUP_CPU_PATHS;
    }
    boolean unified = Files.isRegularFile(baseDir.resolve("cgroup.controllers"));
    Path stat = null;
    Path accounting = null;
    for (String line : lines) {
      String[] fields = line.split(":", 3);
      if (fields.length != 3 || !fields[2].startsWith("/")) {
        continue;
      }
      Path relative = Path.of(fields[2].substring(1));
      if (relative.isAbsolute()
          || !relative.equals(relative.normalize())
          || relative.startsWith("..")) {
        continue;
      }
      if (unified && "0".equals(fields[0]) && fields[1].isEmpty()) {
        return new CgroupCpuPaths(baseDir.resolve(relative).resolve(CPU_STAT_FILE), null);
      }
      if (unified || parseLongStrict(fields[0]) <= 0L) {
        continue;
      }
      List<String> controllers = List.of(fields[1].split(","));
      boolean cpu = controllers.contains(CPU_CONTROLLER);
      boolean cpuacct = controllers.contains(CPUACCT_CONTROLLER);
      boolean combined = cpu && cpuacct;
      if (cpu && stat == null) {
        stat = findV1CpuFile(baseDir, CPU_CONTROLLER, relative, CPU_STAT_FILE, combined);
      }
      if (cpuacct && accounting == null) {
        accounting =
            findV1CpuFile(baseDir, CPUACCT_CONTROLLER, relative, "cpuacct.usage", combined);
      }
    }
    return new CgroupCpuPaths(stat, accounting);
  }

  private static Path findV1CpuFile(
      Path baseDir, String controller, Path relative, String name, boolean combined) {
    Path candidate = baseDir.resolve(controller).resolve(relative).resolve(name);
    if (Files.isRegularFile(candidate)) {
      return candidate;
    }
    if (combined) {
      for (String mount : List.of(CPU_CPUACCT_CONTROLLER, "cpuacct,cpu")) {
        Path merged = baseDir.resolve(mount).resolve(relative).resolve(name);
        if (Files.isRegularFile(merged)) {
          return merged;
        }
      }
    }
    return null;
  }

  /**
   * Parse a cpu.stat file into {@link CgroupCpuStats}.
   *
   * <p>This method understands both cgroup v2 and common cgroup v1 keys:
   *
   * <ul>
   *   <li>v2: usage_usec, nr_periods, nr_throttled, throttled_usec
   *   <li>v1: nr_periods, nr_throttled, throttled_time (ns)
   * </ul>
   *
   * @param statPath path to cpu.stat
   * @return parsed stats, or {@code null} when no useful data is found
   */
  private static CgroupCpuStats parseCgroupCpuStats(Path statPath) throws java.io.IOException {
    List<String> lines = CollectorInput.readLines(statPath, CollectorInput.SMALL);

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

  private static long parseLongStrict(String s) {
    try {
      return Long.parseLong(s.trim());
    } catch (NumberFormatException e) {
      return -1L;
    }
  }

  /** Compute throttling ratio and count for the last polling window. */
  private void computeCgroupRatios(CgroupCpuStats current) {
    cgroupUsageNanosTotal = current.usageNs;
    cgroupThrottledTimeNanosTotal = current.throttledNs;
    cgroupThrottledPeriodsTotal = current.throttledPeriods;

    if (!cgroupCpuInitialized) {
      prevCgroupUsageNs = current.usageNs;
      prevCgroupThrottledNs = current.throttledNs;
      prevCgroupThrottledPeriods = current.throttledPeriods;
      cgroupCpuInitialized = true;
      return;
    }

    long usageDelta = cgroupDelta(prevCgroupUsageNs, current.usageNs);
    long throttledDelta = cgroupDelta(prevCgroupThrottledNs, current.throttledNs);
    double totalDelta = (double) usageDelta + throttledDelta;
    cgroupThrottledRatio =
        usageDelta < 0L || throttledDelta < 0L
            ? -1.0
            : totalDelta == 0.0 ? 0.0 : throttledDelta / totalDelta;
    cgroupThrottledCount =
        Math.max(0L, cgroupDelta(prevCgroupThrottledPeriods, current.throttledPeriods));

    prevCgroupUsageNs = current.usageNs;
    prevCgroupThrottledNs = current.throttledNs;
    prevCgroupThrottledPeriods = current.throttledPeriods;
  }

  private static long cgroupDelta(long previous, long current) {
    return previous < 0L || current < 0L ? -1L : Math.max(0L, current - previous);
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
  public Long getProcessCpuTimeNanos() {
    refreshOnRead();
    return counterOrNull(processCpuTimeNanos);
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

  @Override
  public Long getSystemCpuTotalTicks() {
    refreshOnRead();
    return counterOrNull(cpuTotalTicks);
  }

  @Override
  public Long getSystemCpuIoWaitTicks() {
    refreshOnRead();
    return counterOrNull(cpuIoWaitTicks);
  }

  @Override
  public Long getSystemCpuStealTicks() {
    refreshOnRead();
    return counterOrNull(cpuStealTicks);
  }

  @Override
  public Long getCgroupCpuThrottledPeriodsTotal() {
    refreshOnRead();
    return counterOrNull(cgroupThrottledPeriodsTotal);
  }

  @Override
  public Long getCgroupCpuThrottledTimeNanosTotal() {
    refreshOnRead();
    return counterOrNull(cgroupThrottledTimeNanosTotal);
  }

  @Override
  public Long getCgroupCpuUsageNanosTotal() {
    refreshOnRead();
    return counterOrNull(cgroupUsageNanosTotal);
  }

  @Override
  public double getCgroupCpuLimitCores() {
    refreshOnRead();
    return cgroupCpuLimitCores;
  }

  @Override
  public Long getSystemCpuUserTicks() {
    refreshOnRead();
    return counterOrNull(cpuUserTicks);
  }

  @Override
  public Long getSystemCpuNiceTicks() {
    refreshOnRead();
    return counterOrNull(cpuNiceTicks);
  }

  @Override
  public Long getSystemCpuSystemTicks() {
    refreshOnRead();
    return counterOrNull(cpuSystemTicks);
  }

  @Override
  public Long getSystemCpuIdleTicks() {
    refreshOnRead();
    return counterOrNull(cpuIdleTicks);
  }

  @Override
  public Long getSystemCpuIrqTicks() {
    refreshOnRead();
    return counterOrNull(cpuIrqTicks);
  }

  @Override
  public Long getSystemCpuSoftIrqTicks() {
    refreshOnRead();
    return counterOrNull(cpuSoftIrqTicks);
  }
}
