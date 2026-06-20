package co.pletor.nodemetrics.metrics;

import com.sun.management.OperatingSystemMXBean;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

/**
 * Node-level memory metrics MBean implementation.
 * <p>
 * On Linux, values are derived from {@code /proc/meminfo}.
 * On non-Linux platforms, total/used/free are populated from
 * {@link OperatingSystemMXBean}, using container-aware APIs when available,
 * while other fields are reported as {@code -1}.
 */
public class NodeMemMetrics implements NodeMemMetricsMBean, RefreshManagedMetric {

  // ----- Core memory usage -----

  private volatile long totalBytes = -1L;
  private volatile long usedBytes = -1L;
  private volatile long freeBytes = -1L;
  private volatile long availableBytes = -1L;

  // ----- Additional memory breakdown (Linux only) -----

  private volatile long dirtyBytes = -1L;
  private volatile long writebackBytes = -1L;

  private volatile long cachedBytes = -1L;
  private volatile long buffersBytes = -1L;
  private volatile long shmemBytes = -1L;

  private volatile long filePageCacheBytes = -1L;

  /**
   * Creates a new {@code NodeMemMetrics} instance with all metrics
   * initialized to {@code -1}.
   * <p>
   * Values will be populated on the first JMX query.
   */
  public NodeMemMetrics() {
    // Default constructor for MBean registration and frameworks.
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

    if (LinuxProcFs.isLinux()) {
      pollFromProcMeminfo();
    } else {
      pollFromOsMxBean();
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

  // =========================
  // Linux: /proc/meminfo
  // =========================

  /**
   * Refresh metrics from {@code /proc/meminfo} on Linux.
   * <p>
   * Any parsing or I/O error will reset all fields to {@code -1}.
   */
  private void pollFromProcMeminfo() {
    try {
      List<String> lines = readProcMemInfoLines();

      MemInfoSnapshot snapshot = parseMemInfo(lines);
      applyMemInfoSnapshot(snapshot);

    } catch (IOException | RuntimeException e) {
      resetAll();
    }
  }

  /**
   * Small holder for parsed {@code /proc/meminfo} values (in kB).
   */
  private static final class MemInfoSnapshot {
    long memTotalKb = -1L;
    long memAvailableKb = -1L;
    long memFreeKb = -1L;

    long buffersKb = -1L;
    long cachedKb = -1L;
    long shmemKb = -1L;

    long dirtyKb = -1L;
    long writebackKb = -1L;
    long sReclaimableKb = -1L;
  }

  /**
   * Parse relevant {@code /proc/meminfo} lines into a structured snapshot.
   *
   * @param lines raw lines from {@code /proc/meminfo}
   * @return parsed snapshot containing kB values for selected fields
   */
  private MemInfoSnapshot parseMemInfo(List<String> lines) {
    MemInfoSnapshot s = new MemInfoSnapshot();

    for (String line : lines) {
      if (line.startsWith("MemTotal:")) {
        s.memTotalKb = MemStatsUtil.parseKbLine(line);
      } else if (line.startsWith("MemAvailable:")) {
        s.memAvailableKb = MemStatsUtil.parseKbLine(line);
      } else if (line.startsWith("MemFree:")) {
        s.memFreeKb = MemStatsUtil.parseKbLine(line);
      } else if (line.startsWith("Buffers:")) {
        s.buffersKb = MemStatsUtil.parseKbLine(line);
      } else if (line.startsWith("Cached:")) {
        s.cachedKb = MemStatsUtil.parseKbLine(line);
      } else if (line.startsWith("Shmem:")) {
        s.shmemKb = MemStatsUtil.parseKbLine(line);
      } else if (line.startsWith("Dirty:")) {
        s.dirtyKb = MemStatsUtil.parseKbLine(line);
      } else if (line.startsWith("Writeback:")) {
        s.writebackKb = MemStatsUtil.parseKbLine(line);
      } else if (line.startsWith("SReclaimable:")) {
        s.sReclaimableKb = MemStatsUtil.parseKbLine(line);
      }
    }

    return s;
  }

  /**
   * Convert a parsed snapshot into byte metrics and store them in fields.
   *
   * @param s parsed {@link MemInfoSnapshot} with kB values
   */
  private void applyMemInfoSnapshot(MemInfoSnapshot s) {
    applyCoreMetrics(s);
    applyDiskMetrics(s);
    applyCacheMetrics(s);
  }

  /**
   * Calculate and apply core memory metrics (Total, Free, Available, Used).
   */
  private void applyCoreMetrics(MemInfoSnapshot s) {
    long total = kbToBytes(s.memTotalKb);
    long free = computeFreeBytes(s);
    long available = computeAvailableBytes(s);
    long used = (total >= 0 && available >= 0) ? Math.max(0L, total - available) : -1L;

    totalBytes = total;
    usedBytes = used;
    freeBytes = free;
    availableBytes = available;
  }

  /**
   * Calculate and apply disk write-related metrics (Dirty, Writeback).
   */
  private void applyDiskMetrics(MemInfoSnapshot s) {
    dirtyBytes = (s.dirtyKb >= 0) ? s.dirtyKb * 1024L : -1L;
    writebackBytes = (s.writebackKb >= 0) ? s.writebackKb * 1024L : -1L;
  }

  /**
   * Calculate and apply cache and buffer related metrics.
   */
  private void applyCacheMetrics(MemInfoSnapshot s) {
    buffersBytes = (s.buffersKb >= 0) ? s.buffersKb * 1024L : -1L;
    shmemBytes = (s.shmemKb >= 0) ? s.shmemKb * 1024L : -1L;

    applyCachedBytes(s);
    applyFilePageCacheBytes(s);
  }

  /**
   * Calculate and apply the 'Cached' metric, including SReclaimable.
   */
  private void applyCachedBytes(MemInfoSnapshot s) {
    long cached = (s.cachedKb >= 0) ? s.cachedKb : 0L;
    if (s.sReclaimableKb >= 0) {
      cached += s.sReclaimableKb;
    }

    if (s.cachedKb < 0 && s.sReclaimableKb < 0) {
      cachedBytes = -1L;
    } else {
      cachedBytes = cached * 1024L;
    }
  }

  /**
   * Calculate and apply the 'FilePageCache' metric.
   */
  private void applyFilePageCacheBytes(MemInfoSnapshot s) {
    if (s.cachedKb >= 0 && s.shmemKb >= 0) {
      long fileKb = s.cachedKb - s.shmemKb;
      if (fileKb < 0L) {
        fileKb = 0L;
      }
      filePageCacheBytes = fileKb * 1024L;
    } else {
      filePageCacheBytes = -1L;
    }
  }

  // No changes to computeFreeBytes logic needed if it strictly returns MemFree?
  // User asked for "Free와 available을 둘다 정확한 원래 값을 반환하도록".
  // So computeFreeBytes should return MemFree.
  // computeAvailableBytes should return MemAvailable (or estimate).

  /**
   * Return strict {@code MemFree} in bytes.
   */
  private long computeFreeBytes(MemInfoSnapshot s) {
    if (s.memFreeKb >= 0) {
      return kbToBytes(s.memFreeKb);
    }
    return -1L;
  }

  /**
   * Return {@code MemAvailable} in bytes, or calculate a fallback estimate.
   */
  private long computeAvailableBytes(MemInfoSnapshot s) {
    if (s.memAvailableKb >= 0) {
      return kbToBytes(s.memAvailableKb);
    }
    // Fallback estimate: Free + Buffers + Cached (partially reclaimable)
    // This is a rough approximation if MemAvailable is missing (kernels < 3.14)
    long sumKb = 0L;
    if (s.memFreeKb > 0) sumKb += s.memFreeKb;
    if (s.buffersKb > 0) sumKb += s.buffersKb;
    if (s.cachedKb > 0) sumKb += s.cachedKb;
    // Note: SReclaimable is also part of available, but we might double count if we aren't careful.
    // Basic fallback usually suffices for very old kernels.
    return kbToBytes(sumKb);
  }

  /**
   * Convert a value in kilobytes to bytes.
   *
   * @param kb value in kilobytes, or negative on error
   * @return value in bytes, or -1 when the input was negative
   */
  private long kbToBytes(long kb) {
    return kb < 0L ? -1L : kb * 1024L;
  }

  /**
   * Reset all metrics to {@code -1}, indicating that values are unavailable.
   */
  private void resetAll() {
    totalBytes = -1L;
    usedBytes = -1L;
    freeBytes = -1L;
    availableBytes = -1L;

    dirtyBytes = -1L;
    writebackBytes = -1L;

    cachedBytes = -1L;
    buffersBytes = -1L;
    shmemBytes = -1L;
    filePageCacheBytes = -1L;
  }

  // =========================
  // Non-Linux: OS MXBean
  // =========================

  /**
   * Non-Linux implementation using {@link OperatingSystemMXBean}.
   * <p>
   * Uses container-aware APIs when available:
   * <ul>
   *   <li>JDK 14+: {@code getTotalMemorySize()} / {@code getFreeMemorySize()}</li>
   *   <li>JDK 11–13: fallback to {@code getTotalPhysicalMemorySize()} /
   *       {@code getFreePhysicalMemorySize()}</li>
   * </ul>
   * Reflection is used so that the code can run on JDK 11 while still
   * taking advantage of the newer methods on JDK 14+ without causing
   * linkage errors.
   */
  private void pollFromOsMxBean() {
    try {
      java.lang.management.OperatingSystemMXBean base = getOsMxBean();

      if (base instanceof OperatingSystemMXBean) {
        OperatingSystemMXBean os = (OperatingSystemMXBean) base;
        long total = getTotalMemoryPortable(os);
        long free = getFreeMemoryPortable(os);

        if (total <= 0L || free < 0L) {
          totalBytes = usedBytes = freeBytes = -1L;
        } else {
          long used = Math.max(0L, total - free);
          totalBytes = total;
          usedBytes = used;

          freeBytes = free;
          availableBytes = -1L; // Not easily available portably across all non-Linux OSs in same semantics
        }
      } else {
        totalBytes = usedBytes = freeBytes = availableBytes = -1L;
      }
    } catch (Throwable t) {
      totalBytes = usedBytes = freeBytes = availableBytes = -1L;
    }

    // Non-Linux: cache-related fields are not available.
    dirtyBytes = -1L;
    writebackBytes = -1L;
    cachedBytes = -1L;
    buffersBytes = -1L;
    shmemBytes = -1L;
    filePageCacheBytes = -1L;
  }

  /**
   * Try to call {@code OperatingSystemMXBean.getTotalMemorySize()} (JDK 14+),
   * and fall back to {@code getTotalPhysicalMemorySize()} (JDK 11–13).
   *
   * @param os concrete {@link OperatingSystemMXBean} implementation
   * @return total memory size in bytes, or a non-positive value on error
   */
  @SuppressWarnings("deprecation")
  private long getTotalMemoryPortable(OperatingSystemMXBean os) {
    // Try new API via reflection first (JDK 14+)
    try {
      Method m = os.getClass().getMethod("getTotalMemorySize");
      Object v = m.invoke(os);
      if (v instanceof Long) {
        Long value = (Long) v;
        if (value > 0L) {
          return value;
        }
      }
    } catch (NoSuchMethodException ignore) {
      // Old JDK (11–13): method does not exist
    } catch (Exception ignore) {
      // Any reflection issue: fall back to old API
    }

    // Fallback for JDK 11–13 and error cases
    return os.getTotalPhysicalMemorySize();
  }

  /**
   * Try to call {@code OperatingSystemMXBean.getFreeMemorySize()} (JDK 14+),
   * and fall back to {@code getFreePhysicalMemorySize()} (JDK 11–13).
   *
   * @param os concrete {@link OperatingSystemMXBean} implementation
   * @return free memory size in bytes, or a non-positive value on error
   */
  @SuppressWarnings("deprecation")
  private long getFreeMemoryPortable(OperatingSystemMXBean os) {
    // Try new API via reflection first (JDK 14+)
    try {
      Method m = os.getClass().getMethod("getFreeMemorySize");
      Object v = m.invoke(os);
      if (v instanceof Long) {
        Long value = (Long) v;
        if (value >= 0L) {
          return value;
        }
      }
    } catch (NoSuchMethodException ignore) {
      // Old JDK (11–13): method does not exist
    } catch (Exception ignore) {
      // Any reflection issue: fall back to old API
    }
    // Fallback for JDK 11–13 and error cases
    return os.getFreePhysicalMemorySize();
  }

  // ----- Test hooks -----

  /**
   * Overridden in tests to simulate different operating system beans.
   */
  java.lang.management.OperatingSystemMXBean getOsMxBean() {
    return ManagementFactory.getOperatingSystemMXBean();
  }

  /**
   * Overridden in tests to provide mock /proc/meminfo lines or throw exceptions.
   */
  List<String> readProcMemInfoLines() throws IOException {
    return LinuxProcFs.readLines(Path.of("/proc/meminfo"));
  }

  // ----- MBean getters -----

  @Override
  public long getTotalMemoryBytes() {
    refreshOnRead();
    return totalBytes;
  }

  @Override
  public long getUsedMemoryBytes() {
    refreshOnRead();
    return usedBytes;
  }

  @Override
  public long getFreeMemoryBytes() {
    refreshOnRead();
    return freeBytes;
  }

  @Override
  public long getAvailableMemoryBytes() {
    refreshOnRead();
    return availableBytes;
  }

  @Override
  public double getUsedMemoryPercent() {
    refreshOnRead();
    long total = totalBytes;
    long used = usedBytes;
    if (total <= 0L || used < 0L) {
      return -1.0;
    }
    return (used * 100.0) / total;
  }

  @Override
  public long getDirtyBytes() {
    refreshOnRead();
    return dirtyBytes;
  }

  @Override
  public long getWritebackBytes() {
    refreshOnRead();
    return writebackBytes;
  }

  @Override
  public long getCachedBytes() {
    refreshOnRead();
    return cachedBytes;
  }

  @Override
  public long getBuffersBytes() {
    refreshOnRead();
    return buffersBytes;
  }

  @Override
  public long getShmemBytes() {
    refreshOnRead();
    return shmemBytes;
  }

  @Override
  public long getFilePageCacheBytes() {
    refreshOnRead();
    return filePageCacheBytes;
  }
}
