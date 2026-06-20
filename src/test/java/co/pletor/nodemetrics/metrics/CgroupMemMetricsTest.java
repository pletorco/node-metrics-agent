package co.pletor.nodemetrics.metrics;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

/**
 * High-coverage tests for {@link CgroupMemMetrics}.
 *
 * <p>Test strategy:</p>
 * <ul>
 *   <li>Control {@code LinuxProcFs.isLinux()} and {@code LinuxProcFs.readFirstNumber(...)}
 *       via static mocking.</li>
 *   <li>Use reflection to force internal {@code CgroupInfo cg} fields
 *       ({@code version}, {@code path}, {@code resolved}).</li>
 * </ul>
 */
class CgroupMemMetricsTest {

    // ------- Utility: mutate private cg field via reflection -------

    /**
     * Helper that updates the internal {@code cg} field on {@link CgroupMemMetrics}
     * to simulate different cgroup environments.
     */
    private void setCgroupInfo(CgroupMemMetrics metrics,
                               String version,
                               Path resolved,
                               String path) throws Exception {
        Field cgField = CgroupMemMetrics.class.getDeclaredField("cg");
        cgField.setAccessible(true);
        Object cg = cgField.get(metrics);

        Field versionField = cg.getClass().getDeclaredField("version");
        versionField.setAccessible(true);
        versionField.set(cg, version);

        Field resolvedField = cg.getClass().getDeclaredField("resolved");
        resolvedField.setAccessible(true);
        resolvedField.set(cg, resolved);

        Field pathField = cg.getClass().getDeclaredField("path");
        pathField.setAccessible(true);
        pathField.set(cg, path);
    }

    // ------- 1. Non-Linux (isLinux == false) -------

    @Test
    void poll_setsMinusOneOnNonLinux() throws Exception {
        CgroupMemMetrics metrics = new CgroupMemMetrics();

        try (MockedStatic<LinuxProcFs> mocked = mockStatic(LinuxProcFs.class)) {
            // Simulate non-Linux environment
            mocked.when(LinuxProcFs::isLinux).thenReturn(false);

            metrics.poll();

            assertEquals(-1L, metrics.getMemoryLimitBytes());
            assertEquals(-1L, metrics.getMemoryUsageBytes());
        }
    }

    // ------- 2. cgroup v2 happy path -------

    @Test
    void poll_v2_readsLimitAndUsage() throws Exception {
        CgroupMemMetrics metrics = new CgroupMemMetrics();

        // Create a temporary directory and use it as cg.resolved
        Path tempDir = Files.createTempDirectory("cg-v2-test");

        // cg.version = "v2", cg.resolved = tempDir, cg.path = "/my/cg/path"
        setCgroupInfo(metrics, "v2", tempDir, "/my/cg/path");

        try (MockedStatic<LinuxProcFs> mocked = mockStatic(LinuxProcFs.class)) {
            mocked.when(LinuxProcFs::isLinux).thenReturn(true);

            // For v2, poll() calls readFirstNumber twice:
            //  1) memory.max     -> limit
            //  2) memory.current -> usage
            mocked.when(() -> LinuxProcFs.readFirstNumber(any(Path.class)))
                  .thenReturn(1024L, 2048L);

            metrics.poll();

            assertEquals(1024L, metrics.getMemoryLimitBytes());
            assertEquals(2048L, metrics.getMemoryUsageBytes());
            assertEquals("v2", metrics.getCgroupVersion());
            assertEquals("/my/cg/path", metrics.getCgroupPath());
        }
    }

    // ------- 3. cgroup v1 + "unlimited" limit heuristic -------

    @Test
    void poll_v1_treatsHugeLimitAsUnlimited() throws Exception {
        CgroupMemMetrics metrics = new CgroupMemMetrics();

        // cg.version = "v1", resolved = null (use fallback path), path = "/my/v1/path"
        setCgroupInfo(metrics, "v1", null, "/my/v1/path");

        try (MockedStatic<LinuxProcFs> mocked = mockStatic(LinuxProcFs.class)) {
            mocked.when(LinuxProcFs::isLinux).thenReturn(true);

            // For v1, poll() calls readFirstNumber in this order:
            //  1) memory.limit_in_bytes  -> lim
            //  2) memory.usage_in_bytes  -> cur
            // If lim >= Long.MAX_VALUE / 2, it is treated as "unlimited" and mapped to -1.
            mocked.when(() -> LinuxProcFs.readFirstNumber(any(Path.class)))
                  .thenReturn(Long.MAX_VALUE, 4096L);

            metrics.poll();

            // Because of the heuristic, huge limit is treated as unlimited -> -1
            assertEquals(-1L, metrics.getMemoryLimitBytes());
            assertEquals(4096L, metrics.getMemoryUsageBytes());
            assertEquals("v1", metrics.getCgroupVersion());
            assertEquals("/my/v1/path", metrics.getCgroupPath());
        }
    }

    // ------- 4. Unknown cgroup version (e.g. "none") -------

    @Test
    void poll_withUnknownCgroupVersion_setsMinusOne() throws Exception {
        CgroupMemMetrics metrics = new CgroupMemMetrics();

        // cg.version = "none"
        setCgroupInfo(metrics, "none", null, null);

        try (MockedStatic<LinuxProcFs> mocked = mockStatic(LinuxProcFs.class)) {
            mocked.when(LinuxProcFs::isLinux).thenReturn(true);

            metrics.poll();

            assertEquals(-1L, metrics.getMemoryLimitBytes());
            assertEquals(-1L, metrics.getMemoryUsageBytes());
            assertEquals("none", metrics.getCgroupVersion());
            // If path is null, the getter normalizes it to an empty string.
            assertEquals("", metrics.getCgroupPath());
        }
    }

    // ------- 5. Error path when readFirstNumber throws (catch(Throwable) branch) -------

    @Test
    void poll_setsMinusOneOnError() throws Exception {
        CgroupMemMetrics metrics = new CgroupMemMetrics();

        // Assume v2 (v1 would hit the same catch(Throwable) branch)
        setCgroupInfo(metrics, "v2", null, "/error/path");

        try (MockedStatic<LinuxProcFs> mocked = mockStatic(LinuxProcFs.class)) {
            mocked.when(LinuxProcFs::isLinux).thenReturn(true);
            mocked.when(() -> LinuxProcFs.readFirstNumber(any(Path.class)))
                  .thenThrow(new RuntimeException("boom"));

            metrics.poll();

            // On any exception, poll() should reset metrics to -1.
            assertEquals(-1L, metrics.getMemoryLimitBytes());
            assertEquals(-1L, metrics.getMemoryUsageBytes());
        }
    }
}
