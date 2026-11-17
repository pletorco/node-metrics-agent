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

  private final CgroupBinding binding;

  private static final class Snapshot {
    static final Snapshot UNAVAILABLE = new Snapshot(new LinuxProcFs.CgroupInfo(), -1, -1);
    final String version;
    final String path;
    final long current;
    final long limit;

    Snapshot(LinuxProcFs.CgroupInfo info, long current, long limit) {
      this.version = info.version;
      this.path = info.path == null ? "" : info.path;
      this.current = current;
      this.limit = limit;
    }
  }

  @SuppressWarnings("java:S3077")
  private volatile Snapshot snapshot = Snapshot.UNAVAILABLE;

  /** Membership is resolved on polls, including after process migration. */
  public PidsMetrics() {
    this(new CgroupBinding(() -> LinuxProcFs.detectCgroup("pids")));
  }

  PidsMetrics(CgroupBinding binding) {
    this.binding = binding;
  }

  @Override
  protected void doRefresh() {
    try {
      LinuxProcFs.CgroupInfo cg =
          LinuxProcFs.isLinux() ? binding.current() : new LinuxProcFs.CgroupInfo();
      Path dir = cg.resolved;
      if (dir == null || !Files.isDirectory(dir) || "none".equals(cg.version)) {
        snapshot = Snapshot.UNAVAILABLE;
        return;
      }
      long[] values = read(dir, cg.baseDir);
      snapshot = new Snapshot(cg, values[0], values[1]);
    } catch (Throwable failure) { // NOSONAR - publish unavailable on any failed poll
      recordRefreshFailure(failure);
      snapshot = Snapshot.UNAVAILABLE;
    }
  }

  /**
   * Walks from {@code dir} up to {@code root}, returning {@code {current, limit}} of the level with
   * the tightest finite limit, or of {@code dir} itself when no level has one.
   */
  private static long[] read(Path dir, Path root) {
    if (CollectorInput.excessiveAncestors(dir, root)) {
      return new long[] {-1L, -1L};
    }
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
      String text = CollectorInput.readString(file, CollectorInput.NUMBER).trim();
      return "max".equals(text) ? -1L : Long.parseLong(text);
    } catch (CollectorInput.LimitExceededException e) {
      throw new java.io.UncheckedIOException(e);
    } catch (IOException | RuntimeException e) {
      return -1L;
    }
  }

  @Override
  public String getCgroupVersion() {
    return snapshot.version;
  }

  @Override
  public String getCgroupPath() {
    return snapshot.path;
  }

  @Override
  public long getPidsCurrent() {
    refreshOnRead();
    return snapshot.current;
  }

  @Override
  public long getPidsLimit() {
    refreshOnRead();
    return snapshot.limit;
  }
}
