package co.pletor.nodemetrics.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * High-coverage tests for {@link CgroupMemMetrics}.
 *
 * <p>Test strategy:
 *
 * <ul>
 *   <li>Control {@code LinuxProcFs.isLinux()} and {@code LinuxProcFs.readFirstNumber(...)} via
 *       static mocking.
 *   <li>Use reflection to force internal {@code CgroupInfo cg} fields ({@code version}, {@code
 *       path}, {@code resolved}).
 * </ul>
 */
class CgroupMemMetricsTest {

  // ------- Utility: mutate private cg field via reflection -------

  /**
   * Helper that updates the internal {@code cg} field on {@link CgroupMemMetrics} to simulate
   * different cgroup environments.
   */
  private void setCgroupInfo(CgroupMemMetrics metrics, String version, Path resolved, String path)
      throws Exception {
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
      mocked.when(() -> LinuxProcFs.readFirstNumber(any(Path.class))).thenReturn(1024L, 2048L);

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
      mocked
          .when(() -> LinuxProcFs.readFirstNumber(any(Path.class)))
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
      mocked
          .when(() -> LinuxProcFs.readFirstNumber(any(Path.class)))
          .thenThrow(new RuntimeException("boom"));

      metrics.poll();

      // On any exception, poll() should reset metrics to -1.
      assertEquals(-1L, metrics.getMemoryLimitBytes());
      assertEquals(-1L, metrics.getMemoryUsageBytes());
    }
  }

  // ------- working set (usage minus inactive file cache) -------

  private CgroupMemMetrics pollRealFiles(String version, Path dir, String... files)
      throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(LinuxProcFs.isLinux());
    for (int i = 0; i < files.length; i += 2) {
      Files.writeString(dir.resolve(files[i]), files[i + 1]);
    }
    CgroupMemMetrics metrics = new CgroupMemMetrics();
    setCgroupInfo(metrics, version, dir, "/pod/container");
    metrics.poll();
    metrics.setReadRefreshEnabled(false);
    return metrics;
  }

  @Test
  void workingSet_v2_subtractsInactiveFile(@org.junit.jupiter.api.io.TempDir Path dir)
      throws Exception {
    CgroupMemMetrics m =
        pollRealFiles(
            "v2",
            dir,
            "memory.max",
            "1000000\n",
            "memory.current",
            "600000\n",
            "memory.stat",
            "anon 200000\nfile 350000\ninactive_file 100000\nactive_file 250000\n");

    assertEquals(600000L, m.getMemoryUsageBytes());
    assertEquals(500000L, m.getMemoryWorkingSetBytes());
    assertNull(m.lastRefreshError());
  }

  @Test
  void workingSet_v1_prefersTotalInactiveFileAndFallsBackToInactiveFile(
      @org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
    CgroupMemMetrics total =
        pollRealFiles(
            "v1",
            dir,
            "memory.limit_in_bytes",
            "1000000\n",
            "memory.usage_in_bytes",
            "400000\n",
            "memory.stat",
            "inactive_file 10\ntotal_inactive_file 150000\n");
    assertEquals(250000L, total.getMemoryWorkingSetBytes(), "total_inactive_file wins on v1");

    Files.writeString(dir.resolve("memory.stat"), "inactive_file 50000\n");
    total.poll();
    assertEquals(350000L, total.getMemoryWorkingSetBytes(), "falls back to inactive_file");
  }

  @Test
  void workingSet_isClampedAtZeroAndUnavailableWithoutMemoryStat(
      @org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
    CgroupMemMetrics clamped =
        pollRealFiles(
            "v2",
            dir,
            "memory.max",
            "max\n",
            "memory.current",
            "1000\n",
            "memory.stat",
            "inactive_file 5000\n");
    assertEquals(
        0L, clamped.getMemoryWorkingSetBytes(), "Racy stats must never yield a negative value");
    assertEquals(-1L, clamped.getMemoryLimitBytes());

    Files.delete(dir.resolve("memory.stat"));
    clamped.poll();
    assertEquals(-1L, clamped.getMemoryWorkingSetBytes());
    assertEquals(
        1000L, clamped.getMemoryUsageBytes(), "Usage is still reported without memory.stat");
    assertNull(clamped.lastRefreshError(), "A missing memory.stat is not a refresh failure");
  }

  @Test
  void workingSet_isUnavailableForNonCgroupHosts() {
    CgroupMemMetrics metrics = new CgroupMemMetrics();
    try (MockedStatic<LinuxProcFs> mocked = mockStatic(LinuxProcFs.class)) {
      mocked.when(LinuxProcFs::isLinux).thenReturn(false);
      metrics.poll();
      metrics.setReadRefreshEnabled(false);
      assertEquals(-1L, metrics.getMemoryWorkingSetBytes());
    }
  }

  // ------- effective limit: tightest limit of the cgroup and its ancestors -------

  private CgroupMemMetrics metricsInHierarchy(String version, Path root, Path leaf)
      throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(LinuxProcFs.isLinux());
    CgroupMemMetrics metrics = new CgroupMemMetrics();
    setCgroupInfo(metrics, version, leaf, "/pod/container");
    Field cgField = CgroupMemMetrics.class.getDeclaredField("cg");
    cgField.setAccessible(true);
    Object cg = cgField.get(metrics);
    Field baseDir = cg.getClass().getDeclaredField("baseDir");
    baseDir.setAccessible(true);
    baseDir.set(cg, root);
    return metrics;
  }

  @Test
  void limit_v2_usesTheAncestorLimitWhenTheContainerHasNone(
      @org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
    Path root = Files.createDirectories(tmp.resolve("cgroup"));
    Path pod = Files.createDirectories(root.resolve("kubepods/pod1"));
    Path leaf = Files.createDirectories(pod.resolve("container"));
    Files.writeString(root.resolve("memory.max"), "max\n");
    Files.writeString(pod.resolve("memory.max"), "2000\n");
    Files.writeString(leaf.resolve("memory.max"), "max\n");
    Files.writeString(leaf.resolve("memory.current"), "500\n");
    // Above the mount root: must be ignored.
    Files.writeString(tmp.resolve("memory.max"), "1\n");

    CgroupMemMetrics m = metricsInHierarchy("v2", root, leaf);
    m.poll();
    m.setReadRefreshEnabled(false);

    assertEquals(2000L, m.getMemoryLimitBytes());
  }

  @Test
  void limit_v2_usesTheTightestLimitOfAllLevels(@org.junit.jupiter.api.io.TempDir Path tmp)
      throws Exception {
    Path root = Files.createDirectories(tmp.resolve("cgroup"));
    Path pod = Files.createDirectories(root.resolve("pod"));
    Path leaf = Files.createDirectories(pod.resolve("container"));
    Files.writeString(pod.resolve("memory.max"), "2000\n");
    Files.writeString(leaf.resolve("memory.max"), "5000\n");
    Files.writeString(leaf.resolve("memory.current"), "500\n");

    CgroupMemMetrics m = metricsInHierarchy("v2", root, leaf);
    m.poll();
    m.setReadRefreshEnabled(false);
    assertEquals(
        2000L, m.getMemoryLimitBytes(), "A container limit above its pod's is unreachable");

    Files.writeString(leaf.resolve("memory.max"), "1000\n");
    m.poll();
    assertEquals(
        1000L, m.getMemoryLimitBytes(), "The container's own limit wins when it is tighter");
  }

  @Test
  void limit_v2_isUnlimitedWhenNoLevelHasALimit(@org.junit.jupiter.api.io.TempDir Path tmp)
      throws Exception {
    Path root = Files.createDirectories(tmp.resolve("cgroup"));
    Path leaf = Files.createDirectories(root.resolve("pod/container"));
    Files.writeString(root.resolve("pod/memory.max"), "max\n");
    Files.writeString(leaf.resolve("memory.max"), "max\n");
    Files.writeString(leaf.resolve("memory.current"), "500\n");

    CgroupMemMetrics m = metricsInHierarchy("v2", root, leaf);
    m.poll();
    m.setReadRefreshEnabled(false);

    assertEquals(-1L, m.getMemoryLimitBytes());
  }

  @Test
  void limit_v1_ignoresUnlimitedSentinelAtEveryLevel(@org.junit.jupiter.api.io.TempDir Path tmp)
      throws Exception {
    Path root = Files.createDirectories(tmp.resolve("memory"));
    Path pod = Files.createDirectories(root.resolve("pod"));
    Path leaf = Files.createDirectories(pod.resolve("container"));
    Files.writeString(root.resolve("memory.limit_in_bytes"), "9223372036854771712\n");
    Files.writeString(pod.resolve("memory.limit_in_bytes"), "4000\n");
    Files.writeString(leaf.resolve("memory.limit_in_bytes"), "9223372036854771712\n");
    Files.writeString(leaf.resolve("memory.usage_in_bytes"), "500\n");

    CgroupMemMetrics m = metricsInHierarchy("v1", root, leaf);
    m.poll();
    m.setReadRefreshEnabled(false);

    assertEquals(4000L, m.getMemoryLimitBytes());
  }
}
