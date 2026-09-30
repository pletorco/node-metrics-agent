package kr.pletor.nodemetrics.metrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Process (PID) count and limit of the JVM's cgroup, from the {@code pids} controller.
 *
 * <p>cgroup v2 reads {@code pids.current} and {@code pids.max} in the cgroup directory; cgroup v1
 * reads the same files in the {@code pids} controller hierarchy, which sits next to the {@code
 * memory} one. Like the memory and CPU limits, the effective limit is the tightest finite one of
 * the cgroup and its ancestors, because the kernel enforces every level; the count reported is the
 * one of the level that holds that limit.
 *
 * <p>Missing files (no cgroup, controller not enabled) are unavailable, not a failure.
 */
public class PidsMetrics extends AbstractRefreshingMetric implements PidsMetricsMBean {

  private final LinuxProcFs.CgroupInfo cg;

  private volatile long current = -1L;
  private volatile long limit = -1L;

  /** Creates a new instance and detects the cgroup; values appear after the first refresh. */
  public PidsMetrics() {
    this.cg = LinuxProcFs.detectCgroup();
  }

  @Override
  protected void doRefresh() {
    long[] values = {-1L, -1L};
    try {
      if (LinuxProcFs.isLinux()) {
        Path dir = pidsDirectory();
        if (dir != null) {
          values = read(dir, pidsRoot());
        }
      }
    } catch (RuntimeException e) {
      recordRefreshFailure(e);
    }
    current = values[0];
    limit = values[1];
  }

  /** The cgroup directory holding the pid files, or {@code null} when it cannot be determined. */
  private Path pidsDirectory() {
    if ("v2".equals(cg.version)) {
      return cg.resolved != null && Files.isDirectory(cg.resolved)
          ? cg.resolved
          : Path.of("/sys/fs/cgroup");
    }
    if ("v1".equals(cg.version)) {
      Path root = pidsRoot();
      if (root == null || cg.resolved == null || !cg.resolved.startsWith(cg.baseDir)) {
        return null;
      }
      Path dir = root.resolve(cg.baseDir.relativize(cg.resolved).toString());
      return Files.isDirectory(dir) ? dir : null;
    }
    return null;
  }

  /** The mount root of the hierarchy: ancestors are read only up to and including it. */
  private Path pidsRoot() {
    if ("v1".equals(cg.version)) {
      return cg.baseDir == null ? null : cg.baseDir.resolveSibling("pids");
    }
    return cg.baseDir;
  }

  /**
   * Walks from {@code dir} up to {@code root}, returning {@code {current, limit}} of the level with
   * the tightest finite limit, or of {@code dir} itself when no level has one.
   */
  private static long[] read(Path dir, Path root) {
    long ownCurrent = number(dir.resolve("pids.current"));
    if (ownCurrent < 0L) {
      return new long[] {-1L, -1L};
    }
    long bestLimit = -1L;
    long bestCurrent = ownCurrent;
    Path level = dir;
    while (true) {
      long max = number(level.resolve("pids.max"));
      if (max > 0L && (bestLimit < 0L || max < bestLimit)) {
        long levelCurrent = level.equals(dir) ? ownCurrent : number(level.resolve("pids.current"));
        if (levelCurrent >= 0L) {
          bestLimit = max;
          bestCurrent = levelCurrent;
        }
      }
      if (root == null
          || level.equals(root)
          || !level.startsWith(root)
          || level.getParent() == null) {
        break;
      }
      level = level.getParent();
    }
    return new long[] {bestCurrent, bestLimit};
  }

  /** A file holding one number; {@code "max"}, a missing file and parse errors give -1. */
  private static long number(Path file) {
    try {
      String text = Files.readString(file).trim();
      return "max".equals(text) ? -1L : Long.parseLong(text);
    } catch (IOException | RuntimeException e) {
      return -1L;
    }
  }

  @Override
  public long getPidsCurrent() {
    refreshOnRead();
    return current;
  }

  @Override
  public long getPidsLimit() {
    refreshOnRead();
    return limit;
  }
}
