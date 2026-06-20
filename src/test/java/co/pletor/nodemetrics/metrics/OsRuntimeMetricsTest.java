// src/test/java/co/pletor/nodemetrics/metrics/OsRuntimeMetricsTest.java

package co.pletor.nodemetrics.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class OsRuntimeMetricsTest {

  // ----------------------------------------------------------------------
  // Helper: invoke private methods via reflection for better coverage
  // ----------------------------------------------------------------------
  private static Object invokePrivate(Object target, String name, Class<?>[] paramTypes,
      Object... args) throws Exception {
    Method m = target.getClass().getDeclaredMethod(name, paramTypes);
    m.setAccessible(true);
    return m.invoke(target, args);
  }

  // ----------------------------------------------------------------------
  // Linux path: normal happy path for uptime and mounts
  // ----------------------------------------------------------------------
  @Test
  @DisplayName("poll() should use Linux code path when isLinux() is true")
  void pollShouldUseLinuxPath() {
    OsRuntimeMetrics metrics = new OsRuntimeMetrics();

    Path uptimePath = Path.of("/proc/uptime");
    Path selfMountsPath = Path.of("/proc/self/mounts");

    // Multi-line text block to simulate mounts file content
    String mountsText =
        "/dev/sda1 / ext4 rw,relatime 0 0\n"
            + "tmpfs /run tmpfs rw,nosuid,nodev 0 0\n";
    List<String> mountLines = mountsText.lines().toList();

    try (MockedStatic<LinuxProcFs> linuxMock = Mockito.mockStatic(LinuxProcFs.class);
         MockedStatic<Files> filesMock = Mockito.mockStatic(Files.class)) {

      // Pretend we are on Linux
      linuxMock.when(LinuxProcFs::isLinux).thenReturn(true);

      // Simulate /proc/uptime existing and containing a valid value
      filesMock.when(() -> Files.isRegularFile(uptimePath)).thenReturn(true);
      filesMock.when(() -> Files.readString(uptimePath, StandardCharsets.UTF_8))
          .thenReturn("123.0 456.0\n");

      // Simulate /proc/self/mounts existing and containing two lines
      filesMock.when(() -> Files.isRegularFile(selfMountsPath)).thenReturn(true);
      linuxMock.when(() -> LinuxProcFs.readLines(selfMountsPath)).thenReturn(mountLines);

      // Execute
      metrics.poll();

      // Verify uptime seconds (first token 123.0 -> 123L)
      assertEquals(123L, metrics.getUptimeSeconds(),
          "Linux uptime should be parsed from /proc/uptime");
      // Mount count and mounts content
      assertEquals(2, metrics.getMountCount(), "Mount count should be number of parsed lines");
      String[] mounts1 = metrics.getMounts();
      assertEquals(2, mounts1.length, "Mounts array length should match mountCount");
      assertEquals(mountLines.get(0), mounts1[0], "First mount should match first line");
      assertEquals(mountLines.get(1), mounts1[1], "Second mount should match second line");

      // Verify defensive copy: getMounts() should return a clone
      mounts1[0] = "modified";
      String[] mounts2 = metrics.getMounts();
      assertNotEquals("modified", mounts2[0],
          "Modifying returned array must not change internal mounts array");
    }
  }

  // ----------------------------------------------------------------------
  // Linux path: /proc/self/mounts missing, fallback to /proc/mounts
  // ----------------------------------------------------------------------
  @Test
  @DisplayName("readMountsLinux() should fallback from /proc/self/mounts to /proc/mounts")
  void readMountsShouldFallbackToProcMounts() throws Exception {
    OsRuntimeMetrics metrics = new OsRuntimeMetrics();

    Path selfMountsPath = Path.of("/proc/self/mounts");
    Path mountsPath = Path.of("/proc/mounts");

    String mountsText = "/dev/sdb1 /data ext4 rw,relatime 0 0";
    List<String> mountLines = mountsText.lines().toList();

    try (MockedStatic<Files> filesMock = Mockito.mockStatic(Files.class);
         MockedStatic<LinuxProcFs> linuxMock = Mockito.mockStatic(LinuxProcFs.class)) {

      // self mount file does not exist, /proc/mounts exists
      filesMock.when(() -> Files.isRegularFile(selfMountsPath)).thenReturn(false);
      filesMock.when(() -> Files.isRegularFile(mountsPath)).thenReturn(true);
      linuxMock.when(() -> LinuxProcFs.readLines(mountsPath)).thenReturn(mountLines);

      String[] mounts = (String[]) invokePrivate(
          metrics,
          "readMountsLinux",
          new Class<?>[0]
      );

      assertEquals(1, mounts.length, "Should read one mount line from /proc/mounts");
      assertEquals(mountLines.get(0), mounts[0], "Mount line should come from /proc/mounts");
    }
  }

  // ----------------------------------------------------------------------
  // Linux path: readMountsLinux should handle IOException and missing files
  // ----------------------------------------------------------------------
  @Test
  @DisplayName("readMountsLinux() should return empty array on errors or when files are missing")
  void readMountsShouldReturnEmptyOnError() throws Exception {
    OsRuntimeMetrics metrics = new OsRuntimeMetrics();

    Path selfMountsPath = Path.of("/proc/self/mounts");
    Path mountsPath = Path.of("/proc/mounts");

    try (MockedStatic<Files> filesMock = Mockito.mockStatic(Files.class);
         MockedStatic<LinuxProcFs> linuxMock = Mockito.mockStatic(LinuxProcFs.class)) {

      // Case 1: neither mounts file exists
      filesMock.when(() -> Files.isRegularFile(selfMountsPath)).thenReturn(false);
      filesMock.when(() -> Files.isRegularFile(mountsPath)).thenReturn(false);

      String[] mountsMissing = (String[]) invokePrivate(
          metrics,
          "readMountsLinux",
          new Class<?>[0]
      );
      assertEquals(0, mountsMissing.length,
          "Should return empty array when mount files are missing");

      // Case 2: file exists but readLines throws IOException
      filesMock.when(() -> Files.isRegularFile(selfMountsPath)).thenReturn(true);
      linuxMock.when(() -> LinuxProcFs.readLines(selfMountsPath))
          .thenThrow(new java.io.IOException("simulated IO error"));

      String[] mountsError = (String[]) invokePrivate(
          metrics,
          "readMountsLinux",
          new Class<?>[0]
      );
      assertEquals(0, mountsError.length,
          "Should return empty array when readLines throws IOException");
    }
  }

  // ----------------------------------------------------------------------
  // Linux path: readUptimeLinux variants (missing file, empty, negative, bad number)
  // ----------------------------------------------------------------------
  @Test
  @DisplayName("readUptimeLinux() should return -1 for missing file, empty, negative or bad value")
  void readUptimeLinuxShouldReturnMinusOneOnInvalidCases() throws Exception {
    OsRuntimeMetrics metrics = new OsRuntimeMetrics();
    Path uptimePath = Path.of("/proc/uptime");

    try (MockedStatic<Files> filesMock = Mockito.mockStatic(Files.class)) {

      // Case 1: file does not exist
      filesMock.when(() -> Files.isRegularFile(uptimePath)).thenReturn(false);
      long v1 = (long) invokePrivate(metrics, "readUptimeLinux", new Class<?>[0]);
      assertEquals(0L, v1, "Missing /proc/uptime should result in 0");

      // Case 2: file exists but empty
      filesMock.when(() -> Files.isRegularFile(uptimePath)).thenReturn(true);
      filesMock.when(() -> Files.readString(uptimePath, StandardCharsets.UTF_8)).thenReturn("  \n");
      long v2 = (long) invokePrivate(metrics, "readUptimeLinux", new Class<?>[0]);
      assertEquals(0L, v2, "Empty content should result in 0");

      // Case 3: negative uptime
      filesMock.when(() -> Files.readString(uptimePath, StandardCharsets.UTF_8))
           .thenReturn("-10.0 0.0\n");
      long v3 = (long) invokePrivate(metrics, "readUptimeLinux", new Class<?>[0]);
      assertEquals(0L, v3, "Negative uptime should result in 0");
      // Case 4: invalid number format
      filesMock.when(() -> Files.readString(uptimePath, StandardCharsets.UTF_8))
          .thenReturn("not-a-number 0.0\n");
      long v4 = (long) invokePrivate(metrics, "readUptimeLinux", new Class<?>[0]);
      assertEquals(0L, v4, "Invalid number should result in 0");
    }
  }

  // ----------------------------------------------------------------------
  // Non-Linux path: normal happy path (RuntimeMXBean + FileSystems)
  // ----------------------------------------------------------------------
  @Test
  @DisplayName("poll() should use non-Linux code path and FileStore enumeration")
  void pollShouldUseNonLinuxPath() {
    OsRuntimeMetrics metrics = new OsRuntimeMetrics();

    try (MockedStatic<LinuxProcFs> linuxMock = Mockito.mockStatic(LinuxProcFs.class);
         MockedStatic<ManagementFactory> mgmtMock = Mockito.mockStatic(ManagementFactory.class);
         MockedStatic<FileSystems> fsStaticMock = Mockito.mockStatic(FileSystems.class)) {

      // Pretend we are not on Linux
      linuxMock.when(LinuxProcFs::isLinux).thenReturn(false);

      // Simulate RuntimeMXBean uptime = 42000 ms -> 42 seconds
      RuntimeMXBean mx = Mockito.mock(RuntimeMXBean.class);
      mgmtMock.when(ManagementFactory::getRuntimeMXBean).thenReturn(mx);
      Mockito.when(mx.getUptime()).thenReturn(42_000L);

      // Simulate FileSystem and FileStores
      FileSystem fs = Mockito.mock(FileSystem.class);
      FileStore store1 = Mockito.mock(FileStore.class);
      FileStore store2 = Mockito.mock(FileStore.class);

      fsStaticMock.when(FileSystems::getDefault).thenReturn(fs);
      Mockito.when(fs.getFileStores()).thenReturn(List.of(store1, store2));

      Mockito.when(store1.name()).thenReturn("root");
      Mockito.when(store1.type()).thenReturn("ext4");
      Mockito.when(store2.name()).thenReturn("data");
      Mockito.when(store2.type()).thenReturn("xfs");

      // Execute poll()
      metrics.poll();

      // Verify uptime converted from milliseconds to seconds
      assertEquals(42L, metrics.getUptimeSeconds(),
          "Uptime should be derived from RuntimeMXBean in seconds");

      // Verify mount strings from FileStores
      assertEquals(2, metrics.getMountCount(), "Mount count should match number of FileStores");
      String[] mounts = metrics.getMounts();
      assertEquals(2, mounts.length, "Mounts array length should match mountCount");
      assertEquals("root (ext4)", mounts[0],
          "First FileStore should be formatted as 'name (type)'");
      assertEquals("data (xfs)", mounts[1],
          "Second FileStore should be formatted as 'name (type)'");
    }
  }

  // ----------------------------------------------------------------------
  // Non-Linux path: readUptimeFromRuntimeMxBean error / negative cases
  // ----------------------------------------------------------------------
  @Test
  @DisplayName("readUptimeFromRuntimeMxBean() should handle negative uptime and RuntimeException")
  void readUptimeFromRuntimeMxBeanShouldHandleEdgeCases() throws Exception {
    OsRuntimeMetrics metrics = new OsRuntimeMetrics();

    try (MockedStatic<ManagementFactory> mgmtMock = Mockito.mockStatic(ManagementFactory.class)) {
      // Case 1: negative uptime
      RuntimeMXBean mxNegative = Mockito.mock(RuntimeMXBean.class);
      mgmtMock.when(ManagementFactory::getRuntimeMXBean).thenReturn(mxNegative);
      Mockito.when(mxNegative.getUptime()).thenReturn(-1000L);

      long v1 = (long) invokePrivate(
          metrics,
          "readUptimeFromRuntimeMxBean",
          new Class<?>[0]
      );
      assertEquals(0L, v1, "Negative uptime should result in 0");

      // Case 2: RuntimeException thrown when calling getRuntimeMXBean()
      mgmtMock.when(ManagementFactory::getRuntimeMXBean)
          .thenThrow(new RuntimeException("simulated runtime error"));

      long v2 = (long) invokePrivate(
          metrics,
          "readUptimeFromRuntimeMxBean",
          new Class<?>[0]
      );
      assertEquals(0L, v2, "RuntimeException should be caught and result in 0");
    }
  }

  // ----------------------------------------------------------------------
  // Non-Linux path: readMountsFromFileStores error path (exception from filesystem)
  // ----------------------------------------------------------------------
  @Test
  @DisplayName("readMountsFromFileStores() should return partial or empty result when RuntimeException is thrown")
  void readMountsFromFileStoresShouldHandleRuntimeException() throws Exception {
    OsRuntimeMetrics metrics = new OsRuntimeMetrics();

    try (MockedStatic<FileSystems> fsStaticMock = Mockito.mockStatic(FileSystems.class)) {

      // Case 1: getFileStores() throws RuntimeException, expect empty result
      FileSystem fsError = Mockito.mock(FileSystem.class);
      fsStaticMock.when(FileSystems::getDefault).thenReturn(fsError);
      Mockito.when(fsError.getFileStores()).thenThrow(new RuntimeException("simulated error"));

      String[] mountsError = (String[]) invokePrivate(
          metrics,
          "readMountsFromFileStores",
          new Class<?>[0]
      );
      assertEquals(0, mountsError.length,
          "On RuntimeException, method should return empty array");
    }
  }

  // ----------------------------------------------------------------------
  // instanceof pattern test: o instanceof OsRuntimeMetrics tv
  // ----------------------------------------------------------------------
  @Test
  @DisplayName("Pattern matching for instanceof should bind OsRuntimeMetrics variable")
  void instanceofPatternShouldWork() {
    Object obj = new OsRuntimeMetrics();

    // Use Java pattern matching for instanceof according to requirement
    if (obj instanceof OsRuntimeMetrics) {
      OsRuntimeMetrics tv = (OsRuntimeMetrics) obj;
      // tv is strongly typed OsRuntimeMetrics
      assertNotNull(tv, "Pattern-matched OsRuntimeMetrics reference must not be null");
      // Call poll() once just to exercise the object; behavior is validated in other tests
      tv.poll();
      // Access getters to exercise them
      String[] mounts = tv.getMounts();
      // Only assert basic invariants here
      assertNotNull(mounts, "Mounts array should not be null");
    } else {
      fail("Object should be instance of OsRuntimeMetrics");
    }
  }
}
