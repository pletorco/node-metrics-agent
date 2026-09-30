package kr.pletor.nodemetrics.metrics;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Map;

/**
 * Network error, drop and TCP health metrics.
 *
 * <p>Interface counters come from {@code /proc/net/dev} through the same interface selection as the
 * byte counters of {@link IoRates}; TCP counters come from {@code /proc/net/snmp} ({@code Tcp:})
 * and {@code /proc/net/netstat} ({@code TcpExt:}). Each source is read independently: a missing
 * file makes only its attributes {@code -1} and is not a failure; any other read error keeps the
 * previous values and is reported through {@link #lastRefreshError()}.
 */
public class NetworkMetrics extends AbstractRefreshingMetric implements NetworkMetricsMBean {

  private volatile long rxErrors = -1L;
  private volatile long rxDropped = -1L;
  private volatile long txErrors = -1L;
  private volatile long txDropped = -1L;

  private volatile long tcpOutSegs = -1L;
  private volatile long tcpRetransSegs = -1L;
  private volatile long tcpInErrs = -1L;
  private volatile long tcpAttemptFails = -1L;
  private volatile long tcpEstabResets = -1L;
  private volatile long tcpCurrEstab = -1L;

  private volatile long listenOverflows = -1L;
  private volatile long listenDrops = -1L;
  private volatile long tcpTimeouts = -1L;

  /** Creates a new instance; values appear after the first refresh. */
  public NetworkMetrics() {
    // default constructor for MBean registration and frameworks
  }

  @Override
  protected void doRefresh() {
    if (!LinuxProcFs.isLinux()) {
      markInterfacesUnavailable();
      markTcpUnavailable();
      markTcpExtUnavailable();
      return;
    }
    refreshInterfaces();
    refreshTcp();
    refreshTcpExt();
  }

  private void refreshInterfaces() {
    try {
      if (!java.nio.file.Files.isRegularFile(LinuxProcFs.procPath("net/dev"))) {
        markInterfacesUnavailable();
        return;
      }
      LinuxProcFs.NetTotals t = LinuxProcFs.readNetTotals();
      rxErrors = t.rxErrors;
      rxDropped = t.rxDropped;
      txErrors = t.txErrors;
      txDropped = t.txDropped;
    } catch (IOException | RuntimeException e) {
      recordRefreshFailure(e);
    }
  }

  private void refreshTcp() {
    Map<String, Long> tcp = readTable("net/snmp", "Tcp");
    if (tcp == null) {
      return;
    }
    tcpOutSegs = value(tcp, "OutSegs");
    tcpRetransSegs = value(tcp, "RetransSegs");
    tcpInErrs = value(tcp, "InErrs");
    tcpAttemptFails = value(tcp, "AttemptFails");
    tcpEstabResets = value(tcp, "EstabResets");
    tcpCurrEstab = value(tcp, "CurrEstab");
  }

  private void refreshTcpExt() {
    Map<String, Long> ext = readTable("net/netstat", "TcpExt");
    if (ext == null) {
      return;
    }
    listenOverflows = value(ext, "ListenOverflows");
    listenDrops = value(ext, "ListenDrops");
    tcpTimeouts = value(ext, "TCPTimeouts");
  }

  /**
   * Reads one table of a {@code /proc/net} file.
   *
   * @return the table, an empty map when the file is missing (its attributes become -1), or {@code
   *     null} when the read failed and the previous values should be kept
   */
  private Map<String, Long> readTable(String file, String prefix) {
    try {
      List<String> lines = LinuxProcFs.readLines(LinuxProcFs.procPath(file));
      return ProcNetTable.parse(lines, prefix);
    } catch (NoSuchFileException e) {
      return Map.of();
    } catch (IOException | RuntimeException e) {
      recordRefreshFailure(e);
      return null;
    }
  }

  private static long value(Map<String, Long> table, String column) {
    Long v = table.get(column);
    return v == null ? -1L : v;
  }

  private void markInterfacesUnavailable() {
    rxErrors = -1L;
    rxDropped = -1L;
    txErrors = -1L;
    txDropped = -1L;
  }

  private void markTcpUnavailable() {
    tcpOutSegs = -1L;
    tcpRetransSegs = -1L;
    tcpInErrs = -1L;
    tcpAttemptFails = -1L;
    tcpEstabResets = -1L;
    tcpCurrEstab = -1L;
  }

  private void markTcpExtUnavailable() {
    listenOverflows = -1L;
    listenDrops = -1L;
    tcpTimeouts = -1L;
  }

  @Override
  public Long getNetRxErrorsTotal() {
    refreshOnRead();
    return counterOrNull(rxErrors);
  }

  @Override
  public Long getNetRxDroppedTotal() {
    refreshOnRead();
    return counterOrNull(rxDropped);
  }

  @Override
  public Long getNetTxErrorsTotal() {
    refreshOnRead();
    return counterOrNull(txErrors);
  }

  @Override
  public Long getNetTxDroppedTotal() {
    refreshOnRead();
    return counterOrNull(txDropped);
  }

  @Override
  public Long getTcpOutSegsTotal() {
    refreshOnRead();
    return counterOrNull(tcpOutSegs);
  }

  @Override
  public Long getTcpRetransSegsTotal() {
    refreshOnRead();
    return counterOrNull(tcpRetransSegs);
  }

  @Override
  public Long getTcpInErrsTotal() {
    refreshOnRead();
    return counterOrNull(tcpInErrs);
  }

  @Override
  public Long getTcpAttemptFailsTotal() {
    refreshOnRead();
    return counterOrNull(tcpAttemptFails);
  }

  @Override
  public Long getTcpEstabResetsTotal() {
    refreshOnRead();
    return counterOrNull(tcpEstabResets);
  }

  @Override
  public long getTcpCurrEstab() {
    refreshOnRead();
    return tcpCurrEstab;
  }

  @Override
  public Long getTcpListenOverflowsTotal() {
    refreshOnRead();
    return counterOrNull(listenOverflows);
  }

  @Override
  public Long getTcpListenDropsTotal() {
    refreshOnRead();
    return counterOrNull(listenDrops);
  }

  @Override
  public Long getTcpTimeoutsTotal() {
    refreshOnRead();
    return counterOrNull(tcpTimeouts);
  }
}
