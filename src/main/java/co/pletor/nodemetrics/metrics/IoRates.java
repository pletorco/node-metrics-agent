package co.pletor.nodemetrics.metrics;

import java.io.IOException;

/**
 * Implementation of {@link IoRatesMBean} that computes simple I/O throughput rates
 * based on Linux {@code /proc} and {@code /sys} counters.
 * <p>
 * This class:
 * <ul>
 *   <li>Aggregates disk read/write bytes across block devices</li>
 *   <li>Aggregates network RX/TX bytes across interfaces (typically excluding loopback)</li>
 *   <li>Computes bytes-per-second using a previous snapshot and {@link System#nanoTime()}</li>
 * </ul>
 * On non-Linux platforms, all metrics are reported as {@code 0.0}.
 */
public class IoRates implements IoRatesMBean, RefreshManagedMetric {

  /**
   * Last computed disk read throughput (bytes per second).
   */
  private volatile double diskReadBps = 0.0;

  /**
   * Last computed disk write throughput (bytes per second).
   */
  private volatile double diskWriteBps = 0.0;

  /**
   * Last computed network RX throughput (bytes per second).
   */
  private volatile double netRxBps = 0.0;

  /**
   * Last computed network TX throughput (bytes per second).
   */
  private volatile double netTxBps = 0.0;

  /**
   * Previous cumulative disk/network counters and timestamp used to compute deltas.
   */
  private long prevReadBytes = -1L;
  private long prevWriteBytes = -1L;
  private long prevRxBytes = -1L;
  private long prevTxBytes = -1L;
  private long prevTimeNanos = -1L;

  /**
   * Default sector size used to convert disk sectors to bytes.
   */
  private static final long SECTOR_SIZE_BYTES = 512L;

  /**
   * Constant for converting nanoseconds to seconds.
   */
  private static final double NANOS_PER_SECOND = 1_000_000_000.0;

  /**
   * Creates a new {@code IoRates} instance with zeroed counters.
   * <p>
   * Metric values will be populated on the first JMX query (if you have one).
   */
  public IoRates() {
    // default constructor for MBean registration and frameworks
  }

  // ------------------------------------------------------------------------
  // Polling / Refeshing
  // ------------------------------------------------------------------------

  private static final long REFRESH_INTERVAL_MS = 500L;
  private final RefreshCadence refreshCadence = new RefreshCadence(REFRESH_INTERVAL_MS);
  private volatile boolean readRefreshEnabled = true;

  /**
   * Refresh I/O rates if the cache is stale.
   */
  private void refresh() {
    if (!refreshCadence.tryAcquire()) {
      return;
    }

    // If the current OS is not Linux, we cannot read /proc or /sys,
    // so simply reset the rates and return.
    if (!LinuxProcFs.isLinux()) {
      resetRates();
      return;
    }

    try {
      refreshLinuxSnapshot();
    } catch (SnapshotReadException e) {
      // On snapshot read failure, keep the last known rates.
    } catch (Exception e) {
      // Never propagate metric refresh failures to JMX callers.
      resetRates();
    }
  }

  private void refreshLinuxSnapshot() throws SnapshotReadException {
    // Read current snapshot, compute new rates, and store snapshot for the next poll.
    Snapshot snapshot = readSnapshot();
    updateRates(snapshot);
    storeSnapshot(snapshot);
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
   * Reset all rate metrics to zero.
   */
  private void resetRates() {
    diskReadBps = 0.0;
    diskWriteBps = 0.0;
    netRxBps = 0.0;
    netTxBps = 0.0;
  }

  /**
   * Read the current cumulative byte counters and time as a single snapshot.
   *
   * @return a {@link Snapshot} containing disk and network byte counters and current time
   * @throws SnapshotReadException when disk or network counters cannot be read
   */
  private Snapshot readSnapshot() throws SnapshotReadException {
    try {
      // Disk totals (sectors -> bytes)
      LinuxProcFs.DiskTotals d = LinuxProcFs.readDiskTotals();
      long readBytes = d.readSectors * SECTOR_SIZE_BYTES;
      long writeBytes = d.writtenSectors * SECTOR_SIZE_BYTES;

      // Network totals (already in bytes)
      LinuxProcFs.NetTotals n = LinuxProcFs.readNetTotals();
      long rxBytes = n.rxBytes;
      long txBytes = n.txBytes;

      long nowNanos = System.nanoTime();
      return new Snapshot(readBytes, writeBytes, rxBytes, txBytes, nowNanos);
    } catch (IOException e) {
      // If more specific error context is needed, this message can be adjusted.
      throw new SnapshotReadException("Failed to read I/O counters from /proc or /sys", e);
    }
  }

  /**
   * Update rate metrics based on the difference between the previous snapshot
   * and the newly read snapshot.
   *
   * @param s current snapshot containing cumulative counters and timestamp
   */
  private void updateRates(Snapshot s) {
    // If there is no previous timestamp, this is the first invocation.
    // In this case, we only record the baseline snapshot and skip rate calculation.
    if (prevTimeNanos <= 0) {
      return;
    }

    double dtSeconds = (s.timeNanos - prevTimeNanos) / NANOS_PER_SECOND;
    if (dtSeconds <= 0.0) {
      // If time did not move forward, do not update rates.
      return;
    }

    if (prevReadBytes >= 0) {
      diskReadBps = computeRate(prevReadBytes, s.readBytes, dtSeconds);
    }
    if (prevWriteBytes >= 0) {
      diskWriteBps = computeRate(prevWriteBytes, s.writeBytes, dtSeconds);
    }
    if (prevRxBytes >= 0) {
      netRxBps = computeRate(prevRxBytes, s.rxBytes, dtSeconds);
    }
    if (prevTxBytes >= 0) {
      netTxBps = computeRate(prevTxBytes, s.txBytes, dtSeconds);
    }
  }

  /**
   * Compute a non-negative rate from two cumulative counters and an elapsed time.
   *
   * @param prev      previous cumulative value
   * @param current   current cumulative value
   * @param dtSeconds elapsed time in seconds
   * @return computed rate in units per second, clamped to a minimum of 0.0
   */
  private static double computeRate(long prev, long current, double dtSeconds) {
    return Math.max(0.0, (current - prev) / dtSeconds);
  }

  /**
   * Store the current snapshot as the baseline for the next poll.
   *
   * @param s snapshot to store
   */
  private void storeSnapshot(Snapshot s) {
    prevReadBytes = s.readBytes;
    prevWriteBytes = s.writeBytes;
    prevRxBytes = s.rxBytes;
    prevTxBytes = s.txBytes;
    prevTimeNanos = s.timeNanos;
  }

  /**
   * Immutable container for disk/network counters and timestamp.
   */
  private static final class Snapshot {
    final long readBytes;
    final long writeBytes;
    final long rxBytes;
    final long txBytes;
    final long timeNanos;

    Snapshot(long readBytes, long writeBytes, long rxBytes, long txBytes, long timeNanos) {
      this.readBytes = readBytes;
      this.writeBytes = writeBytes;
      this.rxBytes = rxBytes;
      this.txBytes = txBytes;
      this.timeNanos = timeNanos;
    }
  }

  /**
   * Custom checked exception used when snapshot reading fails.
   */
  private static class SnapshotReadException extends Exception {
    SnapshotReadException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  @Override
  public double getDiskReadBytesPerSec() {
    refreshOnRead();
    return diskReadBps;
  }

  @Override
  public double getDiskWriteBytesPerSec() {
    refreshOnRead();
    return diskWriteBps;
  }

  @Override
  public double getNetRxBytesPerSec() {
    refreshOnRead();
    return netRxBps;
  }

  @Override
  public double getNetTxBytesPerSec() {
    refreshOnRead();
    return netTxBps;
  }
}
