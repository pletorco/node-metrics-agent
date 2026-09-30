package kr.pletor.nodemetrics.metrics;

import java.io.IOException;

/**
 * Disk operation and I/O time metrics from {@code /proc/diskstats}.
 *
 * <p>Uses the same leaf-device selection as the disk byte counters of {@link IoRates}. A read
 * failure keeps the last values (a counter that dropped to {@code -1} and came back would look like
 * a reset) and is reported through {@link #lastRefreshError()}.
 */
public class DiskIoMetrics extends AbstractRefreshingMetric implements DiskIoMetricsMBean {

  private volatile long readsCompleted = -1L;
  private volatile long writesCompleted = -1L;
  private volatile long readTimeMillis = -1L;
  private volatile long writeTimeMillis = -1L;
  private volatile long ioTimeMillis = -1L;
  private volatile long weightedIoTimeMillis = -1L;
  private volatile long ioInProgress = -1L;
  private volatile long deviceCount = -1L;

  /** Creates a new instance; values appear after the first refresh. */
  public DiskIoMetrics() {
    // default constructor for MBean registration and frameworks
  }

  @Override
  protected void doRefresh() {
    if (!LinuxProcFs.isLinux()) {
      markUnavailable();
      return;
    }
    try {
      LinuxProcFs.DiskTotals t = LinuxProcFs.readDiskTotals();
      if (t.deviceCount == 0) {
        markUnavailable();
        return;
      }
      readsCompleted = t.readsCompleted;
      writesCompleted = t.writesCompleted;
      readTimeMillis = t.readTimeMillis;
      writeTimeMillis = t.writeTimeMillis;
      ioTimeMillis = t.ioTimeMillis;
      weightedIoTimeMillis = t.weightedIoTimeMillis;
      ioInProgress = t.ioInProgress;
      deviceCount = t.deviceCount;
    } catch (IOException | RuntimeException e) {
      recordRefreshFailure(e);
    }
  }

  private void markUnavailable() {
    readsCompleted = -1L;
    writesCompleted = -1L;
    readTimeMillis = -1L;
    writeTimeMillis = -1L;
    ioTimeMillis = -1L;
    weightedIoTimeMillis = -1L;
    ioInProgress = -1L;
    deviceCount = -1L;
  }

  @Override
  public long getDiskReadsCompletedTotal() {
    refreshOnRead();
    return readsCompleted;
  }

  @Override
  public long getDiskWritesCompletedTotal() {
    refreshOnRead();
    return writesCompleted;
  }

  @Override
  public long getDiskReadTimeMillisTotal() {
    refreshOnRead();
    return readTimeMillis;
  }

  @Override
  public long getDiskWriteTimeMillisTotal() {
    refreshOnRead();
    return writeTimeMillis;
  }

  @Override
  public long getDiskIoTimeMillisTotal() {
    refreshOnRead();
    return ioTimeMillis;
  }

  @Override
  public long getDiskWeightedIoTimeMillisTotal() {
    refreshOnRead();
    return weightedIoTimeMillis;
  }

  @Override
  public long getDiskIoInProgress() {
    refreshOnRead();
    return ioInProgress;
  }

  @Override
  public long getDiskDeviceCount() {
    refreshOnRead();
    return deviceCount;
  }
}
