package kr.pletor.nodemetrics.metrics;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Basic sanity tests for {@link IoRates}.
 *
 * <p>These tests focus on:
 *
 * <ul>
 *   <li>Default (initial) values
 *   <li>poll() not throwing exceptions
 *   <li>Throughput values always being non-negative and finite
 *   <li>Behavior on both Linux and non-Linux environments
 * </ul>
 */
class IoRatesTest {

  @Test
  @DisplayName("Initial values should all be 0.0")
  void initialValuesAreZero() {
    IoRates ioRates = new IoRates();

    assertEquals(0.0, ioRates.getDiskReadBytesPerSec(), 0.000001);
    assertEquals(0.0, ioRates.getDiskWriteBytesPerSec(), 0.000001);
    assertEquals(0.0, ioRates.getNetRxBytesPerSec(), 0.000001);
    assertEquals(0.0, ioRates.getNetTxBytesPerSec(), 0.000001);
  }

  @Test
  @DisplayName("poll() must not throw an exception")
  void pollDoesNotThrow() {
    IoRates ioRates = new IoRates();

    // poll() should be safe to call at any time
    assertDoesNotThrow(ioRates::poll, "poll() must not throw any exception");
  }

  @Test
  @DisplayName("Repeated poll() calls should keep rates non-negative and finite")
  void ratesAreAlwaysNonNegativeAndFinite() throws InterruptedException {
    IoRates ioRates = new IoRates();

    // Call poll() multiple times with a short delay to ensure nanoTime deltas
    for (int i = 0; i < 5; i++) {
      ioRates.poll();
      Thread.sleep(10L);
    }
    double diskRead = ioRates.getDiskReadBytesPerSec();
    double diskWrite = ioRates.getDiskWriteBytesPerSec();
    double netRx = ioRates.getNetRxBytesPerSec();
    final double netTx = ioRates.getNetTxBytesPerSec();

    // Each value must be >= 0 (on non-Linux it will always be 0.0)
    assertTrue(diskRead >= 0.0, "diskReadBytesPerSec should be >= 0");
    assertTrue(diskWrite >= 0.0, "diskWriteBytesPerSec should be >= 0");
    assertTrue(netRx >= 0.0, "netRxBytesPerSec should be >= 0");
    assertTrue(netTx >= 0.0, "netTxBytesPerSec should be >= 0");

    // Guard against NaN/Infinity
    assertFalse(
        Double.isNaN(diskRead) || Double.isInfinite(diskRead),
        "diskReadBytesPerSec must not be NaN/Infinity");
    assertFalse(
        Double.isNaN(diskWrite) || Double.isInfinite(diskWrite),
        "diskWriteBytesPerSec must not be NaN/Infinity");
    assertFalse(
        Double.isNaN(netRx) || Double.isInfinite(netRx),
        "netRxBytesPerSec must not be NaN/Infinity");
    assertFalse(
        Double.isNaN(netTx) || Double.isInfinite(netTx),
        "netTxBytesPerSec must not be NaN/Infinity");
  }

  @Test
  @DisplayName("On non-Linux systems values should still remain 0 or non-negative")
  void nonLinuxStillKeepsZeroOrNonNegative() {
    // We do not know whether the actual runtime is Linux or not.
    // If isLinux() returns false: IoRates should always report 0.0.
    // If isLinux() returns true: values must still be >= 0.
    IoRates ioRates = new IoRates();

    ioRates.poll();

    assertTrue(
        ioRates.getDiskReadBytesPerSec() >= 0.0,
        "diskReadBytesPerSec should be >= 0 on any platform");
    assertTrue(
        ioRates.getDiskWriteBytesPerSec() >= 0.0,
        "diskWriteBytesPerSec should be >= 0 on any platform");
    assertTrue(
        ioRates.getNetRxBytesPerSec() >= 0.0, "netRxBytesPerSec should be >= 0 on any platform");
    assertTrue(
        ioRates.getNetTxBytesPerSec() >= 0.0, "netTxBytesPerSec should be >= 0 on any platform");
  }

  @Test
  void nonLinuxPollingClearsPreviousLinuxSamples(@TempDir Path tmp) throws Exception {
    Assumptions.assumeTrue(LinuxProcFs.isLinux());
    final String originalOs = System.getProperty("os.name");
    Path proc = tmp.resolve("proc");
    Files.createDirectories(proc.resolve("net"));
    LinuxProcFs.setProcRoot(proc);
    LinuxProcFs.setSysRoot(tmp.resolve("sys"));
    try {
      AtomicLong clock = new AtomicLong();
      IoRates ioRates = new IoRates(clock::get);
      ioRates.setReadRefreshEnabled(false);
      writeCounters(proc, 10L, 5L, 100L, 200L);
      ioRates.poll();
      clock.addAndGet(1_000_000_000L);
      writeCounters(proc, 20L, 10L, 200L, 400L);
      ioRates.poll();
      assertEquals(5120.0, ioRates.getDiskReadBytesPerSec(), 0.001);
      assertEquals(100.0, ioRates.getNetRxBytesPerSec(), 0.001);

      System.setProperty("os.name", "Windows 10");
      ioRates.poll();

      assertEquals(0.0, ioRates.getDiskReadBytesPerSec());
      assertEquals(0.0, ioRates.getNetRxBytesPerSec());
      assertNull(ioRates.getDiskReadBytesTotal());
      assertNull(ioRates.getNetRxBytesTotal());
    } finally {
      System.setProperty("os.name", originalOs);
      LinuxProcFs.setProcRoot(Path.of("/proc"));
      LinuxProcFs.setSysRoot(Path.of("/sys"));
    }
  }

  @Test
  void missingDiskAndUnreadableNetworkRecoverUsingSeparateSampleTimes(@TempDir Path tmp)
      throws Exception {
    Assumptions.assumeTrue(LinuxProcFs.isLinux());
    Path proc = tmp.resolve("proc");
    Files.createDirectories(proc.resolve("net"));
    LinuxProcFs.setProcRoot(proc);
    LinuxProcFs.setSysRoot(tmp.resolve("sys"));
    try {
      AtomicLong clock = new AtomicLong();
      IoRates ioRates = new IoRates(clock::get);
      ioRates.setReadRefreshEnabled(false);
      writeCounters(proc, 1_000_000L, 50L, 10_000L, 20_000L);
      ioRates.poll();

      clock.addAndGet(1_000_000_000L);
      writeCounters(proc, 1_000_001L, 51L, 10_100L, 20_200L);
      Files.delete(proc.resolve("diskstats"));
      ioRates.poll();
      assertEquals(512_000_000L, ioRates.getDiskReadBytesTotal());
      assertEquals(100.0, ioRates.getNetRxBytesPerSec(), 0.001);
      assertNotNull(ioRates.lastRefreshError());

      clock.addAndGet(1_000_000_000L);
      writeCounters(proc, 1_000_002L, 52L, 10_200L, 20_400L);
      // Use a line past the collector limit to cause a deterministic read failure.
      Files.writeString(proc.resolve("net/dev"), "x".repeat(CollectorInput.TABLE.lineBytes + 1));
      ioRates.poll();
      assertEquals(512_001_024L, ioRates.getDiskReadBytesTotal());
      assertEquals(512.0, ioRates.getDiskReadBytesPerSec(), 0.001);
      assertEquals(10_100L, ioRates.getNetRxBytesTotal());
      assertNotNull(ioRates.lastRefreshError());

      clock.addAndGet(1_000_000_000L);
      writeCounters(proc, 1_000_003L, 53L, 10_300L, 20_600L);
      ioRates.poll();
      assertEquals(100.0, ioRates.getNetRxBytesPerSec(), 0.001);
      assertEquals(200.0, ioRates.getNetTxBytesPerSec(), 0.001);
      assertEquals(10_300L, ioRates.getNetRxBytesTotal());
      assertNull(ioRates.lastRefreshError());
    } finally {
      LinuxProcFs.setProcRoot(Path.of("/proc"));
      LinuxProcFs.setSysRoot(Path.of("/sys"));
    }
  }

  @Test
  @DisplayName("Rates are computed even when nanoTime is negative")
  void ratesComputedWhenNanoTimeIsNegative(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
      throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(LinuxProcFs.isLinux());
    java.nio.file.Path proc = tmp.resolve("proc");
    java.nio.file.Files.createDirectories(proc.resolve("net"));
    java.nio.file.Path sys = tmp.resolve("sys");
    java.nio.file.Files.createDirectories(sys);

    LinuxProcFs.setProcRoot(proc);
    LinuxProcFs.setSysRoot(sys);
    try {
      writeCounters(proc, 1000L, 500L, 10_000L, 20_000L);
      java.util.concurrent.atomic.AtomicLong clock =
          new java.util.concurrent.atomic.AtomicLong(-5_000_000_000L);
      IoRates ioRates = new IoRates(clock::get);

      ioRates.poll(); // baseline

      clock.addAndGet(1_000_000_000L);
      writeCounters(proc, 3048L, 500L, 12_000L, 26_000L);
      ioRates.poll();

      assertEquals(2048L * 512L, ioRates.getDiskReadBytesPerSec(), 0.001);
      assertEquals(0.0, ioRates.getDiskWriteBytesPerSec(), 0.001);
      assertEquals(2_000.0, ioRates.getNetRxBytesPerSec(), 0.001);
      assertEquals(6_000.0, ioRates.getNetTxBytesPerSec(), 0.001);
    } finally {
      LinuxProcFs.setProcRoot(java.nio.file.Paths.get("/proc"));
      LinuxProcFs.setSysRoot(java.nio.file.Paths.get("/sys"));
    }
  }

  private static void writeCounters(
      java.nio.file.Path proc, long readSectors, long writtenSectors, long rxBytes, long txBytes)
      throws java.io.IOException {
    java.nio.file.Files.writeString(
        proc.resolve("diskstats"),
        String.format("   8       0 sda 1 0 %d 0 1 0 %d 0 0 0 0%n", readSectors, writtenSectors));
    java.nio.file.Files.writeString(
        proc.resolve("net/dev"),
        "Inter-|   Receive |  Transmit\n"
            + " face |bytes packets errs drop fifo frame compressed multicast|bytes packets errs"
            + " drop fifo colls carrier compressed\n"
            + String.format("  eth0: %d 1 0 0 0 0 0 0 %d 1 0 0 0 0 0 0%n", rxBytes, txBytes));
  }

  @Test
  @DisplayName("Cumulative byte counters are exposed and monotonic across polls")
  void cumulativeTotalsAreExposed(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
      throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(LinuxProcFs.isLinux());
    java.nio.file.Path proc = tmp.resolve("proc");
    java.nio.file.Files.createDirectories(proc.resolve("net"));
    java.nio.file.Path sys = tmp.resolve("sys");
    java.nio.file.Files.createDirectories(sys);

    LinuxProcFs.setProcRoot(proc);
    LinuxProcFs.setSysRoot(sys);
    try {
      IoRates ioRates = new IoRates();
      ioRates.setReadRefreshEnabled(false);
      assertNull(ioRates.getDiskReadBytesTotal(), "Unavailable before the first read");

      writeCounters(proc, 1000L, 500L, 10_000L, 20_000L);
      ioRates.poll();
      assertEquals(1000L * 512L, ioRates.getDiskReadBytesTotal());
      assertEquals(500L * 512L, ioRates.getDiskWriteBytesTotal());
      assertEquals(10_000L, ioRates.getNetRxBytesTotal());
      assertEquals(20_000L, ioRates.getNetTxBytesTotal());

      writeCounters(proc, 3048L, 600L, 12_500L, 26_000L);
      ioRates.poll();
      assertEquals(3048L * 512L, ioRates.getDiskReadBytesTotal());
      assertEquals(600L * 512L, ioRates.getDiskWriteBytesTotal());
      assertEquals(12_500L, ioRates.getNetRxBytesTotal());
      assertEquals(26_000L, ioRates.getNetTxBytesTotal());
    } finally {
      LinuxProcFs.setProcRoot(java.nio.file.Paths.get("/proc"));
      LinuxProcFs.setSysRoot(java.nio.file.Paths.get("/sys"));
    }
  }
}
