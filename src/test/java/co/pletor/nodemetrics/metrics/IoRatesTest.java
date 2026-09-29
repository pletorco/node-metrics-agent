package co.pletor.nodemetrics.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic sanity tests for {@link IoRates}.
 *
 * <p>These tests focus on:</p>
 * <ul>
 *   <li>Default (initial) values</li>
 *   <li>poll() not throwing exceptions</li>
 *   <li>Throughput values always being non-negative and finite</li>
 *   <li>Behavior on both Linux and non-Linux environments</li>
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
        double netTx = ioRates.getNetTxBytesPerSec();

        // Each value must be >= 0 (on non-Linux it will always be 0.0)
        assertTrue(diskRead >= 0.0, "diskReadBytesPerSec should be >= 0");
        assertTrue(diskWrite >= 0.0, "diskWriteBytesPerSec should be >= 0");
        assertTrue(netRx >= 0.0, "netRxBytesPerSec should be >= 0");
        assertTrue(netTx >= 0.0, "netTxBytesPerSec should be >= 0");

        // Guard against NaN/Infinity
        assertFalse(Double.isNaN(diskRead) || Double.isInfinite(diskRead),
                "diskReadBytesPerSec must not be NaN/Infinity");
        assertFalse(Double.isNaN(diskWrite) || Double.isInfinite(diskWrite),
                "diskWriteBytesPerSec must not be NaN/Infinity");
        assertFalse(Double.isNaN(netRx) || Double.isInfinite(netRx),
                "netRxBytesPerSec must not be NaN/Infinity");
        assertFalse(Double.isNaN(netTx) || Double.isInfinite(netTx),
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

        assertTrue(ioRates.getDiskReadBytesPerSec() >= 0.0,
                "diskReadBytesPerSec should be >= 0 on any platform");
        assertTrue(ioRates.getDiskWriteBytesPerSec() >= 0.0,
                "diskWriteBytesPerSec should be >= 0 on any platform");
        assertTrue(ioRates.getNetRxBytesPerSec() >= 0.0,
                "netRxBytesPerSec should be >= 0 on any platform");
        assertTrue(ioRates.getNetTxBytesPerSec() >= 0.0,
                "netTxBytesPerSec should be >= 0 on any platform");
    }
    @Test
    @DisplayName("On non-Linux systems refresh() should reset rates to 0.0")
    void refreshShouldResetMetricsToZeroOnNonLinux() throws Exception {
        String originalOs = System.getProperty("os.name");
        try {
            IoRates ioRates = new IoRates();
            
            // 1. Inject non-zero values via reflection to simulate "stale" rates
            setPrivateField(ioRates, "diskReadBps", 123.0);
            setPrivateField(ioRates, "diskWriteBps", 456.0);
            setPrivateField(ioRates, "netRxBps", 789.0);
            setPrivateField(ioRates, "netTxBps", 321.0);
            
            // Verify injection worked
            assertEquals(123.0, ioRates.getDiskReadBytesPerSec(), 0.0001);
            
            // 2. Switch to non-Linux
            System.setProperty("os.name", "Windows 10");
            
            // 3. Trigger refresh (force via poll to bypass time check if needed, 
            //    although poll() forces cadence override for refresh)
            ioRates.poll();
            
            // 4. Verify all reset to 0.0
            assertEquals(0.0, ioRates.getDiskReadBytesPerSec(), 0.0001, "Should be 0.0 on non-Linux");
            assertEquals(0.0, ioRates.getDiskWriteBytesPerSec(), 0.0001, "Should be 0.0 on non-Linux");
            assertEquals(0.0, ioRates.getNetRxBytesPerSec(), 0.0001, "Should be 0.0 on non-Linux");
            assertEquals(0.0, ioRates.getNetTxBytesPerSec(), 0.0001, "Should be 0.0 on non-Linux");
            
        } finally {
            if (originalOs != null) {
                System.setProperty("os.name", originalOs);
            } else {
                System.clearProperty("os.name");
            }
        }
    }

    private void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
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

    private static void writeCounters(java.nio.file.Path proc, long readSectors, long writtenSectors,
                                      long rxBytes, long txBytes) throws java.io.IOException {
        java.nio.file.Files.writeString(proc.resolve("diskstats"),
                String.format("   8       0 sda 1 0 %d 0 1 0 %d 0 0 0 0%n", readSectors, writtenSectors));
        java.nio.file.Files.writeString(proc.resolve("net/dev"),
                "Inter-|   Receive |  Transmit\n face |bytes packets errs drop fifo frame compressed multicast"
                        + "|bytes packets errs drop fifo colls carrier compressed\n"
                        + String.format("  eth0: %d 1 0 0 0 0 0 0 %d 1 0 0 0 0 0 0%n", rxBytes, txBytes));
    }

    @Test
    @DisplayName("Cumulative byte counters are exposed and monotonic across polls")
    void cumulativeTotalsAreExposed(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
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
            assertEquals(-1L, ioRates.getDiskReadBytesTotal(), "Unavailable before the first read");

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
