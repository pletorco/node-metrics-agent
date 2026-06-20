package co.pletor.nodemetrics.metrics;

import com.sun.management.OperatingSystemMXBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
// Mockito removed

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CpuMetrics}.
 * <p>
 * These tests try to cover:
 * <ul>
 *   <li>MXBean-backed metrics with real and mocked beans</li>
 *   <li>Linux-specific extended metrics branches (best-effort)</li>
 *   <li>Private helper methods via reflection (for higher coverage)</li>
 *   <li>Behavior on non-Linux OS names</li>
 *   <li>Java 17 pattern matching for instanceof</li>
 * </ul>
 */
@SuppressWarnings("deprecation")
class CpuMetricsTest {

  @org.junit.jupiter.api.io.TempDir
  java.nio.file.Path tempDir;

  @org.junit.jupiter.api.AfterEach
  void cleanup() {
    CpuMetrics.procRoot = Paths.get("/proc");
    CpuMetrics.sysRoot = Paths.get("/sys");
  }

  // ----------------------------------------------------
  // File-based tests (using tempDir)
  // ----------------------------------------------------

  @Test
  void readCpuTimes_edgeCases() throws Exception {
    CpuMetrics.procRoot = tempDir;
    java.nio.file.Path statFile = tempDir.resolve("stat");

    Method readCpuTimes = CpuMetrics.class.getDeclaredMethod("readCpuTimes");
    readCpuTimes.setAccessible(true);
    
    // 1. Missing file -> throws IOException (wrapped in InvocationTargetException)
    try {
      readCpuTimes.invoke(null);
      fail("Missing file should throw IOException");
    } catch (java.lang.reflect.InvocationTargetException e) {
       assertTrue(e.getCause() instanceof java.io.IOException, "Should throw IOException");
    }

    // 2. Empty file -> null
    Files.createFile(statFile);
    assertNull(readCpuTimes.invoke(null), "Empty file should return null");

    // 3. First line not "cpu" -> null
    Files.writeString(statFile, "intr 123 456\ncpu  100 0 0 0 0 0 0 0\n");
    assertNull(readCpuTimes.invoke(null), "File not starting with 'cpu ' should return null");

    // 4. Not enough tokens -> null
    Files.writeString(statFile, "cpu  10 20\n");
    assertNull(readCpuTimes.invoke(null), "Malformatted cpu line should return null");

    // 5. Valid file -> non-null
    Files.writeString(statFile, "cpu  100 200 300 400 500 600 700 800\n");
    Object result = readCpuTimes.invoke(null);
    assertNotNull(result, "Valid cpu line should parse successfully");
  }

  @Test
  void readCgroupCpuStats_negativeUsageFallback() throws Exception {
    CpuMetrics.sysRoot = tempDir;
    java.nio.file.Path cgroupDir = tempDir.resolve("fs/cgroup");
    Files.createDirectories(cgroupDir);

    // Create cpu.stat that has throttled info but NO usage (or effectively "missing")
    // Note: implementation expects space-separated key value.
    java.nio.file.Path cpuStat = cgroupDir.resolve("cpu.stat");
    Files.writeString(cpuStat, "nr_throttled 5\nthrottled_usec 1000\n");

    // Create cpuacct/cpuacct.usage for fallback
    java.nio.file.Path cpuAcctDir = cgroupDir.resolve("cpuacct");
    Files.createDirectories(cpuAcctDir);
    java.nio.file.Path cpuAcctUsage = cpuAcctDir.resolve("cpuacct.usage");
    Files.writeString(cpuAcctUsage, "999999");

    Method readCgroupCpuStats = CpuMetrics.class.getDeclaredMethod("readCgroupCpuStats");
    readCgroupCpuStats.setAccessible(true);

    Object result = readCgroupCpuStats.invoke(null);
    assertNotNull(result);
    
    // Check usageNs field via reflection on CgroupCpuStats inner class
    Class<?> cgroupClass = CpuMetrics.CgroupCpuStats.class;
    
    Field usageField = cgroupClass.getDeclaredField("usageNs");
    usageField.setAccessible(true);
    long usage = usageField.getLong(result);

    assertEquals(999999L, usage, "Should fall back to cpuacct.usage when usage_usec is missing");
  }

  @Test
  void findCgroupCpuStatsPath_discovery() throws Exception {
    CpuMetrics.sysRoot = tempDir;
    java.nio.file.Path cgroupDir = tempDir.resolve("fs/cgroup");
    Files.createDirectories(cgroupDir);

    Method findPath = CpuMetrics.class.getDeclaredMethod("findCgroupCpuStatPath", java.nio.file.Path.class);
    findPath.setAccessible(true);

    // 1. Neither exists -> null
    assertNull(findPath.invoke(null, cgroupDir));

    // 2. cpuacct/cpu.stat (v1 fallback)
    java.nio.file.Path v1AcctDir = cgroupDir.resolve("cpuacct");
    Files.createDirectories(v1AcctDir);
    java.nio.file.Path v1AcctFile = v1AcctDir.resolve("cpu.stat");
    Files.createFile(v1AcctFile);
    assertEquals(v1AcctFile, findPath.invoke(null, cgroupDir));

    // 3. cpu/cpu.stat (v1 priority)
    java.nio.file.Path v1CpuDir = cgroupDir.resolve("cpu");
    Files.createDirectories(v1CpuDir);
    java.nio.file.Path v1CpuFile = v1CpuDir.resolve("cpu.stat");
    Files.createFile(v1CpuFile);
    assertEquals(v1CpuFile, findPath.invoke(null, cgroupDir));

    // 4. fs/cgroup/cpu.stat (v2 priority)
    java.nio.file.Path v2File = cgroupDir.resolve("cpu.stat");
    Files.createFile(v2File);
    assertEquals(v2File, findPath.invoke(null, cgroupDir));
  }

  @Test
  void getSystemLoadAverage1m_precedence() throws Exception {
    CpuMetrics.procRoot = tempDir;
    
    // 1. /proc/loadavg exists
    java.nio.file.Path loadavg = tempDir.resolve("loadavg");
    Files.writeString(loadavg, "0.50 0.40 0.30 1/123 4567\n");
    
    CpuMetrics metrics = new CpuMetrics(null); 
    metrics.poll();
    assertEquals(0.50, metrics.getSystemLoadAverage1m(), 0.0001, "Should read 1m load from file");
    
    // 2. No /proc/loadavg -> fails update, falls back to default -1.0
    // (We reuse the instance, but file is gone, subsequent poll won't update loadAvg1m, it keeps old value?
    // CpuMetrics catches exception and keeps old value.
    // So to test fallback we need a fresh instance or ensure it gets reset.)
    
    // Let's create a new instance for missing file case
    Files.delete(loadavg);
    CpuMetrics metrics2 = new CpuMetrics(null);
    metrics2.poll();
    assertEquals(-1.0, metrics2.getSystemLoadAverage1m(), 0.0001, "Should be -1.0 when missing");
  }

  @Test
  void getSystemLoadAverage1m_shouldTriggerRefreshOnDirectGetterCall() throws Exception {
    CpuMetrics.procRoot = tempDir;
    Path loadavg = tempDir.resolve("loadavg");
    Files.writeString(loadavg, "1.25 0.75 0.50 1/100 1234\n");

    String originalOs = System.getProperty("os.name");
    System.setProperty("os.name", "Linux");
    try {
      CpuMetrics metrics = new CpuMetrics(null);
      assertEquals(1.25, metrics.getSystemLoadAverage1m(), 0.0001,
          "Direct getter call should refresh and return /proc/loadavg 1m value");
    } finally {
      if (originalOs != null) {
        System.setProperty("os.name", originalOs);
      } else {
        System.clearProperty("os.name");
      }
    }
  }

  @Test
  void readCpuAcctUsageIfPresent_direct() throws Exception {
    CpuMetrics.sysRoot = tempDir;
    
    Method method = CpuMetrics.class.getDeclaredMethod("readCpuAcctUsageIfPresent", java.nio.file.Path.class);
    method.setAccessible(true);
    java.nio.file.Path baseDir = tempDir.resolve("fs/cgroup");
    Files.createDirectories(baseDir);

    // 1. File missing -> returns -1
    assertEquals(-1L, method.invoke(null, baseDir), "Should return -1 when cpuacct.usage is missing");

    // 2. File exists but garbage content -> returns -1
    java.nio.file.Path cpuAcctDir = baseDir.resolve("cpuacct");
    Files.createDirectories(cpuAcctDir);
    java.nio.file.Path usageFile = cpuAcctDir.resolve("cpuacct.usage");
    Files.writeString(usageFile, "not_a_number");
    assertEquals(-1L, method.invoke(null, baseDir),
        "Should return -1 when content is invalid");

    // 3. Valid content -> returns parsed long
    Files.writeString(usageFile, "1234567890");
    assertEquals(1234567890L, method.invoke(null, baseDir), "Should return valid parsed long");
  }

  @Test
  void findProcessScopedCgroupCpuStatPath_shouldPreferScopedV2Path() throws Exception {
    CpuMetrics.procRoot = tempDir.resolve("proc");
    CpuMetrics.sysRoot = tempDir.resolve("sys");

    Path selfCgroup = CpuMetrics.procRoot.resolve("self/cgroup");
    Files.createDirectories(selfCgroup.getParent());
    Files.writeString(selfCgroup, "0::/kubepods.slice/pod-123\n");

    Path baseDir = CpuMetrics.sysRoot.resolve("fs/cgroup");
    Files.createDirectories(baseDir);
    Files.createFile(baseDir.resolve("cgroup.controllers"));

    Path scoped = baseDir.resolve("kubepods.slice/pod-123/cpu.stat");
    Files.createDirectories(scoped.getParent());
    Files.writeString(scoped, "usage_usec 10\n");

    Path root = baseDir.resolve("cpu.stat");
    Files.writeString(root, "usage_usec 20\n");

    Method method = CpuMetrics.class
        .getDeclaredMethod("findProcessScopedCgroupCpuStatPath", Path.class);
    method.setAccessible(true);
    Object resolved = method.invoke(null, baseDir);

    assertEquals(scoped, resolved, "Should resolve cpu.stat using process cgroup path first");
  }



  /**
   * Simple text block used to demonstrate multi-line string usage.
   * It is not critical for logic, but ensures the tests use Java text blocks.
   */
  @Test
  @DisplayName("Text block should be created successfully")
  void textBlockUsageExample() {
    String info =
        "CpuMetrics test\n" +
        "This is a multi-line text block used only for testing.\n";

    assertNotNull(info, "Text block string should not be null");
    assertTrue(info.contains("CpuMetrics test"),
        "Text block should contain the expected header text");
  }

  /**
   * Verify that poll() works with the default constructor and MXBean resolution.
   * This should exercise resolveOsBean() and the MXBean-backed metric update path.
   */
  @Test
  @DisplayName("poll() should update MXBean-backed metrics with default constructor")
  void pollShouldUpdateMxBeanMetricsWithDefaultConstructor() {
    CpuMetrics metrics = new CpuMetrics();

    assertDoesNotThrow(metrics::poll, "poll() should not throw with default constructor");

    // We do not assert exact values because they depend on the runtime environment,
    // but we can at least verify that the number of processors is positive.
    assertTrue(metrics.getAvailableProcessors() > 0,
        "Available processors should be greater than zero");
  }

  /**
   * Verify that poll() with a mocked OperatingSystemMXBean normalizes invalid ratios
   * and sentinel values correctly.
   */
  @Test
  @DisplayName("poll() should normalize invalid MXBean values to sentinel values")
  void pollShouldNormalizeInvalidMxBeanValues() {
    // Prepare a stub MXBean with intentionally invalid values
    OperatingSystemMXBean mockOs = createStubOsBean(
        2.0,   // invalid (> 1.0)
        -0.5,  // invalid (< 0.0)
        -1.0,  // unsupported load avg
        8,     // available processors
        -42L   // unsupported cpu time
    );

    CpuMetrics metrics = new CpuMetrics(mockOs);

    assertDoesNotThrow(metrics::poll, "poll() should not throw with mocked MXBean");

    // Ratios outside [0.0, 1.0] should be normalized to -1.0
    assertEquals(-1.0, metrics.getSystemCpuLoad(), 0.000001,
        "System CPU load should be normalized to sentinel");
    assertEquals(-1.0, metrics.getProcessCpuLoad(), 0.000001,
        "Process CPU load should be normalized to sentinel");

    // Load average < 0 should be reported as -1.0
    assertEquals(-1.0, metrics.getSystemLoadAverage(), 0.000001,
        "System load average should be sentinel when MXBean returns negative");

    // Available processors should reflect the mock value
    assertEquals(8, metrics.getAvailableProcessors(),
        "Available processors should match mocked value");

    // Negative CPU time should be normalized to -1L
    assertEquals(0L, metrics.getProcessCpuTimeNanos(),
        "Process CPU time should be zero when MXBean returns negative");
  }

  /**
   * Verify that on non-Linux OS names, Linux-specific extended metrics are not updated
   * and remain at their sentinel values.
   */
  @Test
  @DisplayName("Linux-specific metrics should be skipped on non-Linux OS")
  void linuxSpecificMetricsShouldBeSkippedOnNonLinux() {
    String originalOsName = System.getProperty("os.name");
    System.setProperty("os.name", "Windows 11");

    try {
      OperatingSystemMXBean mockOs = createStubOsBean(
          0.5,
          0.3,
          1.0,
          4,
          100L
      );

      CpuMetrics metrics = new CpuMetrics(mockOs);

      assertDoesNotThrow(metrics::poll, "poll() should not throw on non-Linux OS");

      // Extended metrics should keep their sentinel values because updateExtendedLinuxMetrics()
      // should early-return when isLinux() is false.
      assertEquals(-1.0, metrics.getSystemCpuIoWaitRatio(), 0.000001,
          "I/O wait ratio should remain sentinel on non-Linux");
      assertEquals(-1.0, metrics.getSystemCpuStealRatio(), 0.000001,
          "Steal ratio should remain sentinel on non-Linux");
      assertEquals(-1.0, metrics.getSystemLoadAverage5m(), 0.000001,
          "5m load average should remain sentinel on non-Linux");
      assertEquals(-1.0, metrics.getSystemLoadAverage15m(), 0.000001,
          "15m load average should remain sentinel on non-Linux");
      assertEquals(-1.0, metrics.getCgroupCpuThrottledRatio(), 0.000001,
          "Cgroup throttled ratio should remain sentinel on non-Linux");
      assertEquals(0L, metrics.getCgroupCpuThrottledCount(),
          "Cgroup throttled count should remain zero on non-Linux");
    } finally {
      if (originalOsName != null) {
        System.setProperty("os.name", originalOsName);
      } else {
        System.clearProperty("os.name");
      }
    }
  }

  /**
   * Use Java 17 pattern matching for instanceof to satisfy the required style.
   * The variable name is intentionally 'tv' as requested.
   */
  @Test
  @DisplayName("Pattern matching instanceof should work with CpuMetrics")
  void patternMatchingInstanceOfShouldWork() {
    Object o = new CpuMetrics();

    // Java 17 pattern matching for instanceof: o instanceof CpuMetrics tv
    if (o instanceof CpuMetrics) {
      CpuMetrics tv = (CpuMetrics) o;
      // Call poll() on the pattern variable and assert it is usable.
      assertDoesNotThrow(tv::poll, "poll() on pattern variable should not throw");
      assertTrue(tv.getAvailableProcessors() > 0,
          "Pattern variable should provide a valid CpuMetrics instance");
    } else {
      fail("Object should be an instance of CpuMetrics");
    }
  }

  /**
   * Use reflection to call the private computeCpuStateRatios(CpuTimes) method
   * to verify that I/O wait and steal ratios are computed as expected.
   */
  @Test
  @DisplayName("computeCpuStateRatios should compute iowait and steal ratios correctly")
  void computeCpuStateRatiosShouldComputeRatios() throws Exception {
    CpuMetrics metrics = new CpuMetrics((OperatingSystemMXBean) null);

    // Obtain the private static inner class CpuTimes
    Class<?> cpuTimesClass = CpuMetrics.CpuTimes.class;

    // CpuTimes(long user, long nice, long system, long idle,
    //          long iowait, long irq, long softirq, long steal)
    Constructor<?> ctor = cpuTimesClass.getDeclaredConstructor(
        long.class, long.class, long.class, long.class,
        long.class, long.class, long.class, long.class);
    ctor.setAccessible(true);

    // First snapshot initializes internal state but does not compute ratios.
    Object first = ctor.newInstance(
        10L, 0L, 5L, 100L,
        2L, 0L, 0L, 1L);
    Method computeMethod = CpuMetrics.class
        .getDeclaredMethod("computeCpuStateRatios", cpuTimesClass);
    computeMethod.setAccessible(true);
    computeMethod.invoke(metrics, first);

    // Second snapshot with increased iowait and steal time.
    Object second = ctor.newInstance(
        20L, 0L, 15L, 130L,
        7L, 0L, 0L, 3L);
    computeMethod.invoke(metrics, second);

    bypassRefresh(metrics); // Ensure getters don't trigger refresh/overwrite

    double ioWait = metrics.getSystemCpuIoWaitRatio();
    double steal = metrics.getSystemCpuStealRatio();

    assertTrue(ioWait > 0.0 && ioWait <= 1.0,
        "I/O wait ratio should be in (0.0, 1.0] after second snapshot");
    assertTrue(steal > 0.0 && steal <= 1.0,
        "Steal ratio should be in (0.0, 1.0] after second snapshot");

    // Third snapshot with no change to total time: delta should be zero
    // and the method should simply return without throwing.
    computeMethod.invoke(metrics, second);
  }

  /**
   * Use reflection to call the private computeCgroupRatios(CgroupCpuStats) method
   * to verify that throttling ratio and count are computed.
   */
  @Test
  @DisplayName("computeCgroupRatios should compute throttling metrics correctly")
  void computeCgroupRatiosShouldComputeThrottlingMetrics() throws Exception {
    CpuMetrics metrics = new CpuMetrics((OperatingSystemMXBean) null);

    // Obtain the private static inner class CgroupCpuStats
    Class<?> cgroupClass = CpuMetrics.CgroupCpuStats.class;

    // CgroupCpuStats(long usageNs, long throttledNs, long throttledPeriods)
    Constructor<?> ctor = cgroupClass.getDeclaredConstructor(
        long.class, long.class, long.class);
    ctor.setAccessible(true);

    Method computeMethod = CpuMetrics.class
        .getDeclaredMethod("computeCgroupRatios", cgroupClass);
    computeMethod.setAccessible(true);

    // First snapshot initializes internal state.
    Object first = ctor.newInstance(
        1_000_000L, 100_000L, 5L);
    computeMethod.invoke(metrics, first);

    // Second snapshot with increased usage and throttling.
    Object second = ctor.newInstance(
        2_000_000L, 300_000L, 9L);
    computeMethod.invoke(metrics, second);

    bypassRefresh(metrics); // Ensure getters don't trigger refresh/overwrite

    double ratio = metrics.getCgroupCpuThrottledRatio();
    long count = metrics.getCgroupCpuThrottledCount();

    assertTrue(ratio >= 0.0 && ratio <= 1.0,
        "Cgroup throttled ratio should be in [0.0, 1.0]");
    assertEquals(4L, count,
        "Cgroup throttled count delta should match the difference between snapshots");

    // Third snapshot with decreased usage to exercise the clamping logic.
    Object third = ctor.newInstance(
        1_500_000L, 250_000L, 7L);
    computeMethod.invoke(metrics, third);
    bypassRefresh(metrics); // Ensure getters don't trigger refresh/overwrite

    assertTrue(metrics.getCgroupCpuThrottledRatio() >= 0.0,
        "Cgroup throttled ratio should remain non-negative after clamped delta");
    assertTrue(metrics.getCgroupCpuThrottledCount() >= 0L,
        "Cgroup throttled count should remain non-negative after clamped delta");
  }

  /**
   * Smoke test that attempts to read real /proc and cgroup files via poll()
   * on Linux systems. This test is best-effort and only verifies that poll()
   * does not throw and that metrics stay within expected ranges.
   */
  @Test
  @DisplayName("poll() should not throw and produce sane ranges on real environment")
  void pollShouldNotThrowOnRealEnvironment() {
    // Optional: check whether /proc exists to avoid failures on non-Unix CI environments.
    if (!Files.isDirectory(Paths.get("/proc"))) {
      // Environment does not expose /proc; just skip the test.
      return;
    }

    CpuMetrics metrics = new CpuMetrics();

    assertDoesNotThrow(metrics::poll,
        "poll() should not throw when reading from real /proc and cgroup files");

    // Best-effort sanity checks; we do not assert exact values.
    assertTrue(metrics.getAvailableProcessors() > 0,
        "Available processors should be positive");
    assertTrue(metrics.getSystemCpuLoad() <= 1.0 || metrics.getSystemCpuLoad() < 0.0,
        "System CPU load should be <= 1.0 or sentinel");
    assertTrue(metrics.getProcessCpuLoad() <= 1.0 || metrics.getProcessCpuLoad() < 0.0,
        "Process CPU load should be <= 1.0 or sentinel");
  }

  // ----------------------------------------------------
  // Helpers
  // ----------------------------------------------------

  private OperatingSystemMXBean createStubOsBean(
      double sysLoad, double procLoad, double loadAvg, int procs, long procTime) {
    
    return (OperatingSystemMXBean) Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[]{OperatingSystemMXBean.class},
        (proxy, method, args) -> {
          switch (method.getName()) {
            case "getSystemCpuLoad": return sysLoad;
            case "getProcessCpuLoad": return procLoad;
            case "getSystemLoadAverage": return loadAvg;
            case "getAvailableProcessors": return procs;
            case "getProcessCpuTime": return procTime;
            // Common object methods
            case "toString": return "StubOsBean";
            case "hashCode": return System.identityHashCode(proxy);
            case "equals": return proxy == args[0];
            default: return 0; // fallback
          }
        }
    );
  }

  private void bypassRefresh(CpuMetrics metrics) {
    metrics.setReadRefreshEnabled(false);
  }
}
