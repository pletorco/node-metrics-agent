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
}
