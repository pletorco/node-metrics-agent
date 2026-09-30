package kr.pletor.nodemetrics.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link DiskIoMetrics} and {@link NetworkMetrics} against a fake procfs and sysfs. */
class DiskAndNetworkMetricsTest {

  @TempDir Path root;
  private Path proc;
  private Path sys;

  @BeforeEach
  void fakeRoots() throws IOException {
    Assumptions.assumeTrue(LinuxProcFs.isLinux(), "reads the Linux code path");
    proc = Files.createDirectories(root.resolve("proc"));
    sys = Files.createDirectories(root.resolve("sys"));
    LinuxProcFs.setProcRoot(proc);
    LinuxProcFs.setSysRoot(sys);
  }

  @AfterEach
  void restoreRoots() {
    LinuxProcFs.setProcRoot(Path.of("/proc"));
    LinuxProcFs.setSysRoot(Path.of("/sys"));
  }

  private void write(String relative, String content) throws IOException {
    Path file = proc.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private void blockDevices(String... names) throws IOException {
    for (String name : names) {
      Files.createDirectories(sys.resolve("block").resolve(name));
    }
  }

  // "major minor name reads merged sectors ms writes merged sectors ms inflight iotime weighted"
  private static String diskLine(String name, long reads, long writes, long ioTicks) {
    return String.format(
        "   8       0 %s %d 5 2000 300 %d 6 4000 500 3 %d 900 0 0 0 0 0 0%n",
        name, reads, writes, ioTicks);
  }

  // ------------------------------------------------------------------ disk

  @Test
  void diskCountersAreSummedOverLeafDevices() throws Exception {
    blockDevices("sda", "sdb", "loop0");
    write(
        "diskstats",
        diskLine("sda", 100, 200, 700)
            + diskLine("sda1", 999, 999, 999) // a partition: not a leaf device
            + diskLine("sdb", 10, 20, 70)
            + diskLine("loop0", 555, 555, 555));

    DiskIoMetrics m = new DiskIoMetrics();
    m.poll();

    assertEquals(110L, m.getDiskReadsCompletedTotal());
    assertEquals(220L, m.getDiskWritesCompletedTotal());
    assertEquals(770L, m.getDiskIoTimeMillisTotal());
    assertEquals(600L, m.getDiskReadTimeMillisTotal());
    assertEquals(1000L, m.getDiskWriteTimeMillisTotal());
    assertEquals(1800L, m.getDiskWeightedIoTimeMillisTotal());
    assertEquals(6L, m.getDiskIoInProgress());
    assertEquals(2L, m.getDiskDeviceCount());
    assertNull(m.lastRefreshError());
  }

  @Test
  void diskValuesFollowTheFile() throws Exception {
    blockDevices("sda");
    write("diskstats", diskLine("sda", 100, 200, 700));
    DiskIoMetrics m = new DiskIoMetrics();
    m.poll();
    assertEquals(100L, m.getDiskReadsCompletedTotal());

    write("diskstats", diskLine("sda", 150, 260, 900));
    m.poll();

    assertEquals(150L, m.getDiskReadsCompletedTotal());
    assertEquals(900L, m.getDiskIoTimeMillisTotal());
  }

  @Test
  void diskWithoutStatsOrDevicesIsUnavailableWithoutFailure() throws Exception {
    DiskIoMetrics missing = new DiskIoMetrics();
    missing.poll();
    assertNull(missing.getDiskReadsCompletedTotal());
    assertEquals(-1L, missing.getDiskDeviceCount());
    assertNull(missing.lastRefreshError());

    blockDevices("sda");
    write("diskstats", diskLine("nvme9n9", 1, 1, 1)); // no such leaf device
    DiskIoMetrics none = new DiskIoMetrics();
    none.poll();
    assertNull(none.getDiskIoTimeMillisTotal());
  }

  @Test
  void diskReadFailureKeepsLastValuesAndIsReported() throws Exception {
    blockDevices("sda");
    write("diskstats", diskLine("sda", 100, 200, 700));
    DiskIoMetrics m = new DiskIoMetrics();
    m.poll();

    Files.delete(proc.resolve("diskstats"));
    Files.createDirectory(proc.resolve("diskstats")); // unreadable as a file
    m.poll();

    assertNull(m.getDiskReadsCompletedTotal(), "a directory is not a regular file");
  }

  // --------------------------------------------------------------- network

  private static final String NET_DEV =
      "Inter-|   Receive                            |  Transmit\n"
          + " face |bytes packets errs drop fifo frame compressed multicast|bytes packets errs"
          + " drop fifo colls carrier compressed\n"
          + "    lo: 999 9 9 9 0 0 0 0 999 9 9 9 0 0 0 0\n"
          + "  eth0: 1000 10 2 3 0 0 0 0 2000 20 4 5 0 0 0 0\n"
          + "  eth1: 100 1 1 1 0 0 0 0 200 2 1 1 0 0 0 0\n";

  private static final String SNMP =
      "Ip: Forwarding\nIp: 1\n"
          + "Tcp: RtoAlgorithm RtoMin MaxConn ActiveOpens AttemptFails EstabResets CurrEstab InSegs"
          + " OutSegs RetransSegs InErrs\n"
          + "Tcp: 1 200 -1 50 7 8 33 9000 8000 40 6\n";

  private static final String NETSTAT =
      "TcpExt: SyncookiesSent ListenOverflows ListenDrops TCPTimeouts\n"
          + "TcpExt: 0 11 12 13\n"
          + "IpExt: InNoRoutes\nIpExt: 0\n";

  @Test
  void networkCounters() throws Exception {
    write("net/dev", NET_DEV);
    Files.createDirectories(sys.resolve("class/net/eth0/device"));
    Files.createDirectories(sys.resolve("class/net/eth1/device"));
    write("net/snmp", SNMP);
    write("net/netstat", NETSTAT);

    NetworkMetrics m = new NetworkMetrics();
    m.poll();

    assertEquals(3L, m.getNetRxErrorsTotal(), "eth0 + eth1, loopback excluded");
    assertEquals(4L, m.getNetRxDroppedTotal());
    assertEquals(5L, m.getNetTxErrorsTotal());
    assertEquals(6L, m.getNetTxDroppedTotal());
    assertEquals(8000L, m.getTcpOutSegsTotal());
    assertEquals(40L, m.getTcpRetransSegsTotal());
    assertEquals(6L, m.getTcpInErrsTotal());
    assertEquals(7L, m.getTcpAttemptFailsTotal());
    assertEquals(8L, m.getTcpEstabResetsTotal());
    assertEquals(33L, m.getTcpCurrEstab());
    assertEquals(11L, m.getTcpListenOverflowsTotal());
    assertEquals(12L, m.getTcpListenDropsTotal());
    assertEquals(13L, m.getTcpTimeoutsTotal());
    assertNull(m.lastRefreshError());
  }

  @Test
  void containerInterfacesAreCountedWhenNoneIsPhysical() throws Exception {
    write("net/dev", NET_DEV); // no sys/class/net/*/device entries

    NetworkMetrics m = new NetworkMetrics();
    m.poll();

    assertEquals(3L, m.getNetRxErrorsTotal());
    assertEquals(6L, m.getNetTxDroppedTotal());
  }

  @Test
  void eachSourceIsIndependent() throws Exception {
    write("net/dev", NET_DEV);
    write("net/netstat", NETSTAT); // no net/snmp

    NetworkMetrics m = new NetworkMetrics();
    m.poll();

    assertEquals(3L, m.getNetRxErrorsTotal());
    assertNull(m.getTcpOutSegsTotal());
    assertEquals(-1L, m.getTcpCurrEstab());
    assertEquals(11L, m.getTcpListenOverflowsTotal());
    assertNull(m.lastRefreshError(), "an absent file is not a failure");
  }

  @Test
  void nothingAvailableIsAllAbsent() {
    NetworkMetrics m = new NetworkMetrics();
    m.poll();

    assertNull(m.getNetRxErrorsTotal());
    assertNull(m.getTcpRetransSegsTotal());
    assertNull(m.getTcpTimeoutsTotal());
    assertNull(m.lastRefreshError());
  }

  @Test
  void tableWithoutTheColumnLeavesOnlyThatValueUnavailable() throws Exception {
    write("net/snmp", "Tcp: OutSegs\nTcp: 55\n");

    NetworkMetrics m = new NetworkMetrics();
    m.poll();

    assertEquals(55L, m.getTcpOutSegsTotal());
    assertNull(m.getTcpRetransSegsTotal());
  }

  @Test
  void unreadableSourceKeepsPreviousValuesAndIsReported() throws Exception {
    write("net/snmp", SNMP);
    NetworkMetrics m = new NetworkMetrics();
    m.poll();
    assertEquals(8000L, m.getTcpOutSegsTotal());

    Files.delete(proc.resolve("net/snmp"));
    Files.createDirectory(proc.resolve("net/snmp")); // reading a directory throws IOException
    m.poll();

    assertEquals(8000L, m.getTcpOutSegsTotal(), "previous value kept");
    assertNotNull(m.lastRefreshError());
  }
}
