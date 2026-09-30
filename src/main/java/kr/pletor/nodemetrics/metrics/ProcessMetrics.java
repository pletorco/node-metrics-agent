package kr.pletor.nodemetrics.metrics;

import java.io.IOException;
import java.nio.file.NoSuchFileException;

/**
 * Resource figures of the JVM process itself, from {@code /proc/self/status} and {@code
 * /proc/self/io}.
 *
 * <p>{@code /proc/self/status} is required on Linux: a missing file makes everything {@code -1}
 * without being a failure (non-procfs sandboxes), any other read error keeps the previous values
 * and is reported through {@link #lastRefreshError()}. {@code /proc/self/io} is optional: it needs
 * task I/O accounting in the kernel and can be denied, and then only its two counters are {@code
 * -1}.
 */
public class ProcessMetrics extends AbstractRefreshingMetric implements ProcessMetricsMBean {

  private volatile long rss = -1L;
  private volatile long rssPeak = -1L;
  private volatile long rssAnon = -1L;
  private volatile long rssFile = -1L;
  private volatile long rssShmem = -1L;
  private volatile long swap = -1L;
  private volatile long threads = -1L;
  private volatile long ioReadBytes = -1L;
  private volatile long ioWriteBytes = -1L;

  /** Creates a new instance; values appear after the first refresh. */
  public ProcessMetrics() {
    // default constructor for MBean registration and frameworks
  }

  @Override
  protected void doRefresh() {
    if (!LinuxProcFs.isLinux()) {
      markStatusUnavailable();
      markIoUnavailable();
      return;
    }
    refreshStatus();
    refreshIo();
  }

  private void refreshStatus() {
    long newRss = -1L;
    long newPeak = -1L;
    long newAnon = -1L;
    long newFile = -1L;
    long newShmem = -1L;
    long newSwap = -1L;
    long newThreads = -1L;
    try {
      for (String line : LinuxProcFs.readLines(LinuxProcFs.procPath("self/status"))) {
        if (line.startsWith("VmRSS:")) {
          newRss = kbToBytes(MemStatsUtil.parseKbLine(line));
        } else if (line.startsWith("VmHWM:")) {
          newPeak = kbToBytes(MemStatsUtil.parseKbLine(line));
        } else if (line.startsWith("RssAnon:")) {
          newAnon = kbToBytes(MemStatsUtil.parseKbLine(line));
        } else if (line.startsWith("RssFile:")) {
          newFile = kbToBytes(MemStatsUtil.parseKbLine(line));
        } else if (line.startsWith("RssShmem:")) {
          newShmem = kbToBytes(MemStatsUtil.parseKbLine(line));
        } else if (line.startsWith("VmSwap:")) {
          newSwap = kbToBytes(MemStatsUtil.parseKbLine(line));
        } else if (line.startsWith("Threads:")) {
          newThreads = MemStatsUtil.parseCounterLine(line);
        }
      }
    } catch (NoSuchFileException e) {
      markStatusUnavailable();
      return;
    } catch (IOException | RuntimeException e) {
      recordRefreshFailure(e);
      return;
    }
    rss = newRss;
    rssPeak = newPeak;
    rssAnon = newAnon;
    rssFile = newFile;
    rssShmem = newShmem;
    swap = newSwap;
    threads = newThreads;
  }

  private void refreshIo() {
    long read = -1L;
    long write = -1L;
    try {
      for (String line : LinuxProcFs.readLines(LinuxProcFs.procPath("self/io"))) {
        if (line.startsWith("read_bytes:")) {
          read = MemStatsUtil.parseCounterLine(line);
        } else if (line.startsWith("write_bytes:")) {
          write = MemStatsUtil.parseCounterLine(line);
        }
      }
    } catch (IOException | RuntimeException e) {
      // Optional source (kernel without task I/O accounting, or access denied): unavailable.
      markIoUnavailable();
      return;
    }
    ioReadBytes = read;
    ioWriteBytes = write;
  }

  private static long kbToBytes(long kb) {
    return kb < 0L ? -1L : kb * 1024L;
  }

  private void markStatusUnavailable() {
    rss = -1L;
    rssPeak = -1L;
    rssAnon = -1L;
    rssFile = -1L;
    rssShmem = -1L;
    swap = -1L;
    threads = -1L;
  }

  private void markIoUnavailable() {
    ioReadBytes = -1L;
    ioWriteBytes = -1L;
  }

  @Override
  public long getResidentSetBytes() {
    refreshOnRead();
    return rss;
  }

  @Override
  public long getResidentSetPeakBytes() {
    refreshOnRead();
    return rssPeak;
  }

  @Override
  public long getResidentAnonBytes() {
    refreshOnRead();
    return rssAnon;
  }

  @Override
  public long getResidentFileBytes() {
    refreshOnRead();
    return rssFile;
  }

  @Override
  public long getResidentShmemBytes() {
    refreshOnRead();
    return rssShmem;
  }

  @Override
  public long getSwapBytes() {
    refreshOnRead();
    return swap;
  }

  @Override
  public long getThreadCount() {
    refreshOnRead();
    return threads;
  }

  @Override
  public Long getIoReadBytesTotal() {
    refreshOnRead();
    return counterOrNull(ioReadBytes);
  }

  @Override
  public Long getIoWriteBytesTotal() {
    refreshOnRead();
    return counterOrNull(ioWriteBytes);
  }
}
