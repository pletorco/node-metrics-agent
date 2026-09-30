package kr.pletor.nodemetrics.metrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the CPU limit that applies to a cgroup, in cores.
 *
 * <p>The limit is the CFS bandwidth quota divided by its period: {@code cpu.max} on cgroup v2
 * ({@code "<quota> <period>"} or {@code "max <period>"}), {@code cpu.cfs_quota_us} and {@code
 * cpu.cfs_period_us} on cgroup v1 (quota {@code -1} means unlimited).
 *
 * <p>Like the memory limit, the effective CPU limit is the tightest finite limit of the cgroup and
 * its ancestors: the kernel enforces every level, so a container without its own quota is still
 * bounded by its pod, and a container quota larger than the pod's is never reachable.
 */
final class CgroupCpuLimit {

  private CgroupCpuLimit() {
    // Utility class; no instances.
  }

  /**
   * Returns the effective CPU limit of a cgroup.
   *
   * @param dir the cgroup directory (the one holding {@code cpu.max} or the v1 {@code cpu.cfs_*}
   *     files)
   * @param root the cgroup mount root; ancestors are read only up to and including it. With {@code
   *     null}, or a {@code dir} outside it, only {@code dir} itself is read. Inside a cgroup
   *     namespace the mount root is already the container's cgroup, so nothing above it is read.
   * @return the limit in cores (for example {@code 0.5} or {@code 2.0}), or {@code -1} when no
   *     level has a finite limit or the files are unavailable
   */
  static double effectiveCores(Path dir, Path root) {
    if (dir == null) {
      return -1.0;
    }
    double best = levelCores(dir);
    if (root == null || !dir.startsWith(root)) {
      return best;
    }
    for (Path d = dir; !d.equals(root) && d.getParent() != null; ) {
      d = d.getParent();
      double ancestor = levelCores(d);
      if (ancestor > 0.0 && (best < 0.0 || ancestor < best)) {
        best = ancestor;
      }
    }
    return best;
  }

  /** The limit set at exactly this level, or -1 when it has none or it cannot be read. */
  private static double levelCores(Path dir) {
    String v2 = read(dir.resolve("cpu.max"));
    if (v2 != null) {
      return parseCpuMax(v2);
    }
    String quota = read(dir.resolve("cpu.cfs_quota_us"));
    String period = read(dir.resolve("cpu.cfs_period_us"));
    if (quota == null || period == null) {
      return -1.0;
    }
    return cores(parseLong(quota), parseLong(period));
  }

  /**
   * Parses {@code cpu.max}: {@code "max 100000"} (unlimited) or {@code "200000 100000"}.
   *
   * @return the limit in cores, or -1 when unlimited or malformed
   */
  static double parseCpuMax(String content) {
    String[] parts = content.trim().split("\\s+");
    if (parts.length < 2 || "max".equals(parts[0])) {
      return -1.0;
    }
    return cores(parseLong(parts[0]), parseLong(parts[1]));
  }

  private static double cores(long quotaMicros, long periodMicros) {
    if (quotaMicros <= 0L || periodMicros <= 0L) {
      return -1.0;
    }
    return (double) quotaMicros / (double) periodMicros;
  }

  private static long parseLong(String text) {
    try {
      return Long.parseLong(text.trim());
    } catch (NumberFormatException e) {
      return -1L;
    }
  }

  /** File content, or {@code null} when the file is absent or cannot be read. */
  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }
}
