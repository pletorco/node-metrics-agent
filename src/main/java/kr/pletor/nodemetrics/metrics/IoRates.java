package kr.pletor.nodemetrics.metrics;

import java.io.IOException;
import java.util.function.LongSupplier;

/**
 * Disk and network throughput and cumulative byte counters. Each source keeps its own last valid
 * sample and monotonic timestamp, so one unavailable source cannot stall the other or reset its
 * baseline.
 */
public class IoRates extends AbstractRefreshingMetric implements IoRatesMBean {
  private static final long SECTOR_SIZE_BYTES = 512L;
  private static final double NANOS_PER_SECOND = 1_000_000_000.0;
  private static final Sample UNSAMPLED = new Sample(-1L, -1L, 0L, 0.0, 0.0);

  private volatile Sample disk = UNSAMPLED;
  private volatile Sample network = UNSAMPLED;
  private final LongSupplier nanoClock;

  public IoRates() {
    this(System::nanoTime);
  }

  // Visible for testing
  IoRates(LongSupplier nanoClock) {
    this.nanoClock = nanoClock;
  }

  @Override
  protected void doRefresh() {
    if (!LinuxProcFs.isLinux()) {
      disk = UNSAMPLED;
      network = UNSAMPLED;
      return;
    }
    refreshDisk();
    refreshNetwork();
  }

  private void refreshDisk() {
    try {
      LinuxProcFs.DiskTotals totals = LinuxProcFs.readDiskTotals();
      if (totals.deviceCount > 0) {
        disk =
            nextSample(
                disk,
                Math.multiplyExact(totals.readSectors, SECTOR_SIZE_BYTES),
                Math.multiplyExact(totals.writtenSectors, SECTOR_SIZE_BYTES));
      } else if (disk != UNSAMPLED) {
        recordRefreshFailure(new IOException("Disk counters unavailable"));
      }
    } catch (IOException | RuntimeException e) {
      recordRefreshFailure(e);
    }
  }

  private void refreshNetwork() {
    try {
      LinuxProcFs.NetTotals totals = LinuxProcFs.readNetTotals();
      if (totals.available) {
        network = nextSample(network, totals.rxBytes, totals.txBytes);
      } else if (network != UNSAMPLED) {
        recordRefreshFailure(new IOException("Network counters unavailable"));
      }
    } catch (IOException | RuntimeException e) {
      recordRefreshFailure(e);
    }
  }

  private Sample nextSample(Sample previous, long read, long written) throws IOException {
    if (read < 0L || written < 0L) {
      throw new IOException("I/O byte counter overflow");
    }
    long now = nanoClock.getAsLong();
    if (previous == UNSAMPLED) {
      return new Sample(read, written, now, 0.0, 0.0);
    }
    long elapsed = now - previous.timeNanos;
    if (elapsed <= 0L) {
      return previous;
    }
    double seconds = elapsed / NANOS_PER_SECOND;
    return new Sample(
        read,
        written,
        now,
        Math.max(0L, read - previous.readBytes) / seconds,
        Math.max(0L, written - previous.writtenBytes) / seconds);
  }

  private static final class Sample {
    final long readBytes;
    final long writtenBytes;
    final long timeNanos;
    final double readBytesPerSecond;
    final double writtenBytesPerSecond;

    Sample(
        long readBytes,
        long writtenBytes,
        long timeNanos,
        double readBytesPerSecond,
        double writtenBytesPerSecond) {
      this.readBytes = readBytes;
      this.writtenBytes = writtenBytes;
      this.timeNanos = timeNanos;
      this.readBytesPerSecond = readBytesPerSecond;
      this.writtenBytesPerSecond = writtenBytesPerSecond;
    }
  }

  @Override
  public double getDiskReadBytesPerSec() {
    refreshOnRead();
    return disk.readBytesPerSecond;
  }

  @Override
  public double getDiskWriteBytesPerSec() {
    refreshOnRead();
    return disk.writtenBytesPerSecond;
  }

  @Override
  public double getNetRxBytesPerSec() {
    refreshOnRead();
    return network.readBytesPerSecond;
  }

  @Override
  public double getNetTxBytesPerSec() {
    refreshOnRead();
    return network.writtenBytesPerSecond;
  }

  @Override
  public Long getDiskReadBytesTotal() {
    refreshOnRead();
    return counterOrNull(disk.readBytes);
  }

  @Override
  public Long getDiskWriteBytesTotal() {
    refreshOnRead();
    return counterOrNull(disk.writtenBytes);
  }

  @Override
  public Long getNetRxBytesTotal() {
    refreshOnRead();
    return counterOrNull(network.readBytes);
  }

  @Override
  public Long getNetTxBytesTotal() {
    refreshOnRead();
    return counterOrNull(network.writtenBytes);
  }
}
