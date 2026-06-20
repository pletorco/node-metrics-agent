package co.pletor.nodemetrics.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LinuxProcFsTest {

  @TempDir
  Path tempDir;

  @Test
  void privateConstructor_shouldBeInvokableViaReflection() throws Exception {
    // Cover the private constructor to increase line coverage.
    Constructor<LinuxProcFs> ctor = LinuxProcFs.class.getDeclaredConstructor();
    ctor.setAccessible(true);
    LinuxProcFs instance = ctor.newInstance();
    assertNotNull(instance, "Instance created via reflection should not be null");

    // Demonstrate pattern matching: o instanceof [type] tv
    Object o = instance;
    if (o instanceof LinuxProcFs) {
      LinuxProcFs tv = (LinuxProcFs) o;
      assertNotNull(tv, "Pattern matched variable should not be null");
    } else {
      fail("Object was expected to be instance of LinuxProcFs");
    }
  }

  @Test
  void isLinux_shouldReactToOsNameProperty() {
    // Save original value to restore later
    String original = System.getProperty("os.name");

    try {
      System.setProperty("os.name", "Linux");
      assertTrue(LinuxProcFs.isLinux(), "Linux string should be detected as Linux");

      System.setProperty("os.name", "LINUX something");
      assertTrue(LinuxProcFs.isLinux(), "Case-insensitive Linux detection should work");

      System.setProperty("os.name", "Windows 10");
      assertFalse(LinuxProcFs.isLinux(), "Non-Linux OS name should not be detected as Linux");
    } finally {
      // Restore original property
      if (original == null) {
        System.clearProperty("os.name");
      } else {
        System.setProperty("os.name", original);
      }
    }
  }

  @Test
  void readLines_shouldReadAllLinesInOrder() {
    Path file = tempDir.resolve("lines.txt");
    String content =
        "first\n"
            + "second\n"
            + "third\n";
    try {
      Files.writeString(file, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    try {
      List<String> lines = LinuxProcFs.readLines(file);
      assertEquals(3, lines.size(), "File should contain three lines");
      assertEquals("first", lines.get(0));
      assertEquals("second", lines.get(1));
      assertEquals("third", lines.get(2));
    } catch (IOException e) {
      fail("IOException was not expected while reading temporary test file");
    }
  }

  @Test
  void parseLongSafe_shouldHandleValidAndInvalidInputs() {
    assertEquals(123L, LinuxProcFs.parseLongSafe("123"), "Valid number should parse correctly");
    assertEquals(123L, LinuxProcFs.parseLongSafe("  123  "),
        "Trimmed number should parse correctly");
    assertEquals(0L, LinuxProcFs.parseLongSafe("not-a-number"),
        "Invalid number should return 0");
    assertEquals(0L, LinuxProcFs.parseLongSafe(""), "Empty string should return 0");
    assertEquals(0L, LinuxProcFs.parseLongSafe("   "), "Whitespace-only string should return 0");
  }

  @Test
  void readFirstNumber_shouldReturnParsedNumber() {
    Path file = tempDir.resolve("number.txt");
    String content = "\n42\n\n";
    try {
      Files.writeString(file, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    long value = LinuxProcFs.readFirstNumber(file);
    assertEquals(42L, value, "File containing numeric content should return that number");
  }

  @Test
  void readFirstNumber_shouldReturnMinusOneForMaxAndErrors() {
    // "max" special case
    Path maxFile = tempDir.resolve("max.txt");
    String maxContent = "\nmax\n";
    try {
      Files.writeString(maxFile, maxContent, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    assertEquals(-1L, LinuxProcFs.readFirstNumber(maxFile), "\"max\" should be mapped to -1");

    // Invalid number
    Path invalidFile = tempDir.resolve("invalid.txt");
    String invalidContent = "\nnot-a-number\n";
    try {
      Files.writeString(invalidFile, invalidContent, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    assertEquals(-1L, LinuxProcFs.readFirstNumber(invalidFile),
        "Invalid numeric content should return -1");

    // Missing file (should trigger exception and return -1)
    Path missingFile = tempDir.resolve("missing.txt");
    assertFalse(Files.exists(missingFile), "Test file should not exist");
    assertEquals(-1L, LinuxProcFs.readFirstNumber(missingFile), "Missing file should return -1");
  }

  @Test
  void resolveCgroupPath_shouldHandleNullAndRootAndRelativePaths() throws Exception {
    // Use reflection to access private static method resolveCgroupPath
    Method method =
        LinuxProcFs.class.getDeclaredMethod("resolveCgroupPath", java.nio.file.Path.class,
            String.class);
    method.setAccessible(true);

    Path base = tempDir.resolve("cgroup-base");

    // baseDir == null => null
    Object r1 = method.invoke(null, null, "/anything");
    assertNull(r1, "Null baseDir should result in null resolved path");

    // root path => baseDir
    Object r2 = method.invoke(null, base, "/");
    assertEquals(base, r2, "Root cgroup path should resolve to base directory");

    // empty path => baseDir
    Object r3 = method.invoke(null, base, "");
    assertEquals(base, r3, "Empty cgroup path should resolve to base directory");

    // null path => baseDir
    Object r4 = method.invoke(null, base, null);
    assertEquals(base, r4, "Null cgroup path should resolve to base directory");

    // Normal path with leading slash => baseDir.resolve(relative)
    Object r5 = method.invoke(null, base, "/foo/bar");
    assertTrue(r5 instanceof Path, "Resolved cgroup path should be a Path");
    Path resolved = (Path) r5;
    assertEquals(base.resolve("foo/bar"), resolved,
        "Leading slash should be stripped when resolving path");
  }

  @Test
  void findMemoryControllerPath_shouldReturnMemoryPathWhenPresent() throws Exception {
    // Build a fake cgroup file content that includes a memory controller line.
    String content =
        "2:cpuset:/\n"
            + "3:cpu,cpuacct:/some/other\n"
            + "4:memory:/kubepods/besteffort/pod123\n";

    Path cgroupFile = tempDir.resolve("cgroup");
    try {
      Files.writeString(cgroupFile, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    // Access private static findMemoryControllerPath(Path) via reflection
    Method method =
        LinuxProcFs.class.getDeclaredMethod("findMemoryControllerPath", java.nio.file.Path.class);
    method.setAccessible(true);

    Object ret = method.invoke(null, cgroupFile);
    assertTrue(ret instanceof String, "Return type should be String");
    String path = (String) ret;
    assertEquals("/kubepods/besteffort/pod123", path,
        "Memory controller path should be parsed correctly");
  }

  @Test
  void findMemoryControllerPath_shouldReturnNullWhenNoMemoryController() throws Exception {
    // File without any memory controller entry.
    String content =
        "2:cpuset:/\n"
            + "3:cpu,cpuacct:/some/other\n";

    Path cgroupFile = tempDir.resolve("cgroup-nomemory");
    try {
      Files.writeString(cgroupFile, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    Method method =
        LinuxProcFs.class.getDeclaredMethod("findMemoryControllerPath", java.nio.file.Path.class);
    method.setAccessible(true);

    Object ret = method.invoke(null, cgroupFile);
    assertNull(ret, "When no memory controller is present, the method should return null");
  }

  @Test
  void readDiskTotals_shouldNotThrowAndReturnNonNullObject() {
    try {
      LinuxProcFs.DiskTotals totals = LinuxProcFs.readDiskTotals();
      assertNotNull(totals, "DiskTotals object should never be null");

      // Basic invariants: counters should not be negative
      assertTrue(totals.readSectors >= 0, "Read sectors should not be negative");
      assertTrue(totals.writtenSectors >= 0, "Written sectors should not be negative");
    } catch (IOException e) {
      // The method declares IOException; if it happens in this environment, the test should fail.
      fail("IOException was not expected when reading /proc/diskstats");
    }
  }

  @Test
  void readNetTotals_shouldNotThrowAndReturnNonNullObject() {
    try {
      LinuxProcFs.NetTotals totals = LinuxProcFs.readNetTotals();
      assertNotNull(totals, "NetTotals object should never be null");

      // Basic invariants: counters should not be negative
      assertTrue(totals.rxBytes >= 0, "RX bytes should not be negative");
      assertTrue(totals.txBytes >= 0, "TX bytes should not be negative");
    } catch (IOException e) {
      fail("IOException was not expected when reading /proc/net/dev");
    }
  }

  @Test
  void detectCgroup_shouldReturnNonNullInfoWithValidVersion() {
    LinuxProcFs.CgroupInfo info = LinuxProcFs.detectCgroup();
    assertNotNull(info, "CgroupInfo should never be null");

    String version = info.version;
    assertNotNull(version, "Cgroup version string should not be null");

    // Version should be one of the documented values: v1, v2, or none.
    assertTrue(
        "v1".equals(version) || "v2".equals(version) || "none".equals(version),
        "Version should be v1, v2, or none"
    );

    // Use pattern matching again for the nested type
    Object o = info;
    if (o instanceof LinuxProcFs.CgroupInfo) {
      LinuxProcFs.CgroupInfo tv = (LinuxProcFs.CgroupInfo) o;
      assertEquals(version, tv.version, "Pattern-matched instance should expose same version");
    } else {
      fail("Expected CgroupInfo instance");
    }
  }

  @Test
  void cgroupInfo_defaultValuesShouldBeAsDocumented() {
    LinuxProcFs.CgroupInfo info = new LinuxProcFs.CgroupInfo();
    assertEquals("none", info.version, "Default version should be 'none'");
    assertEquals("", info.path, "Default path should be empty string");
    assertNull(info.baseDir, "Default baseDir should be null");
    assertNull(info.resolved, "Default resolved path should be null");
  }

  @Test
  void internalCgroupV2AndV1Detectors_shouldBeCallableViaReflection() throws Exception {
    // This test mainly exists to exercise tryDetectCgroupV2 and tryDetectCgroupV1
    // without asserting environment-specific behavior.

    // tryDetectCgroupV2()
    Method v2Method = LinuxProcFs.class.getDeclaredMethod("tryDetectCgroupV2");
    v2Method.setAccessible(true);
    Object v2 = null;
    try {
      v2 = v2Method.invoke(null);
    } catch (InvocationTargetException e) {
      fail("tryDetectCgroupV2 should not throw: " + e.getTargetException());
    }

    if (v2 != null) {
      // Use pattern matching: o instanceof [type] tv
      Object o = v2;
      if (o instanceof LinuxProcFs.CgroupInfo) {
        LinuxProcFs.CgroupInfo tv = (LinuxProcFs.CgroupInfo) o;
        assertEquals("v2", tv.version, "Detected cgroup v2 should have version 'v2'");
      } else {
        fail("V2 detection returned unexpected type");
      }
    }

    // tryDetectCgroupV1()
    Method v1Method = LinuxProcFs.class.getDeclaredMethod("tryDetectCgroupV1");
    v1Method.setAccessible(true);
    Object v1 = null;
    try {
      v1 = v1Method.invoke(null);
    } catch (InvocationTargetException e) {
      fail("tryDetectCgroupV1 should not throw: " + e.getTargetException());
    }

    if (v1 != null) {
      // Again use pattern matching: o instanceof [type] tv
      Object o = v1;
      if (o instanceof LinuxProcFs.CgroupInfo) {
        LinuxProcFs.CgroupInfo tv = (LinuxProcFs.CgroupInfo) o;
        assertEquals("v1", tv.version, "Detected cgroup v1 should have version 'v1'");
      } else {
        fail("V1 detection returned unexpected type");
      }
    }
  }

  @Test
  void readV2SelfCgroupPath_shouldBeCallableViaReflection() throws Exception {
    // This test only verifies that the method can be invoked without throwing.
    Method m = LinuxProcFs.class.getDeclaredMethod("readV2SelfCgroupPath");
    m.setAccessible(true);

    String result;
    try {
      Object ret = m.invoke(null);
      assertTrue(ret instanceof String, "Return value should be a String");
      result = (String) ret;
    } catch (InvocationTargetException e) {
      fail("readV2SelfCgroupPath should not throw: " + e.getTargetException());
      return; // unreachable, but keeps compiler happy
    }

    // result may be empty string or a valid cgroup path; just assert non-null
    assertNotNull(result, "Result from readV2SelfCgroupPath should not be null");
  }

  @Test
  void collectLeafBlockDevices_shouldBeCallableViaReflection() throws Exception {
    // New helper introduced to avoid double-counting stacked devices (dm-0, md0 etc).
    Method m = LinuxProcFs.class.getDeclaredMethod("collectLeafBlockDevices");
    m.setAccessible(true);

    Object ret;
    try {
      ret = m.invoke(null);
    } catch (InvocationTargetException e) {
      fail("collectLeafBlockDevices should not throw: " + e.getTargetException());
      return;
    }

    assertNotNull(ret, "Return value from collectLeafBlockDevices should not be null");
    assertTrue(ret instanceof Set, "Return value from collectLeafBlockDevices should be a Set");

    // Use pattern matching for the Set type
    Object o = ret;
    if (o instanceof Set<?>) {
      Set<?> tv = (Set<?>) o;
      // We cannot assert content (it depends on the host system),
      // but we can at least iterate safely.
      for (Object dev : tv) {
        assertNotNull(dev, "Device name in leaf set should not be null");
      }
    } else {
      fail("Result from collectLeafBlockDevices was not a Set as expected");
    }
  }

  @Test
  void leafDeviceCache_shouldBeInvalidatedWhenSysRootChanges() throws IOException {
    LinuxProcFs.setProcRoot(tempDir);
    Path diskstats = tempDir.resolve("diskstats");
    String sdaLine = "8 0 sda 1 1 100 1 1 1 100 1 0 0 0\n";
    String sdbLine = "8 16 sdb 1 1 200 1 1 1 200 1 0 0 0\n";
    Files.writeString(diskstats, sdaLine + sdbLine, StandardCharsets.UTF_8);

    Path sys1 = tempDir.resolve("sys1");
    Files.createDirectories(sys1.resolve("block/sda"));
    LinuxProcFs.setSysRoot(sys1);

    LinuxProcFs.DiskTotals first = LinuxProcFs.readDiskTotals();
    assertEquals(100L, first.readSectors);

    Path sys2 = tempDir.resolve("sys2");
    Files.createDirectories(sys2.resolve("block/sdb"));
    LinuxProcFs.setSysRoot(sys2);

    LinuxProcFs.DiskTotals second = LinuxProcFs.readDiskTotals();
    assertEquals(200L, second.readSectors);
  }
  @org.junit.jupiter.api.AfterEach
  void resetRoots() {
    LinuxProcFs.setProcRoot(java.nio.file.Paths.get("/proc"));
    LinuxProcFs.setSysRoot(java.nio.file.Paths.get("/sys"));
  }

  @Test
  void readDiskTotals_continueConditions() throws IOException {
    LinuxProcFs.setProcRoot(tempDir);
    Path diskstats = tempDir.resolve("diskstats");
    String validLine = "   8       0 sda 11832 6835 777266 12224 8769 13322 1084272 173660 0 106090 185880\n";
    String emptyLine = "\n";
    String shortLine = "   8       0 sda short\n";

    // Creating files structure
    Files.writeString(diskstats, emptyLine + shortLine + validLine);

    // Create sys/block/sda directory so it's counted
    Path sysBlockSda = tempDir.resolve("sys/block/sda");
    Files.createDirectories(sysBlockSda);
    LinuxProcFs.setSysRoot(tempDir.resolve("sys"));

    LinuxProcFs.DiskTotals totals = LinuxProcFs.readDiskTotals();

    // Only valid line should be processed
    assertEquals(777266, totals.readSectors);
  }

  @Test
  void readDiskTotals_exceptionHandling() {
    LinuxProcFs.setProcRoot(tempDir);
    // diskstats does not exist, should return empty totals
    try {
      LinuxProcFs.DiskTotals totals = LinuxProcFs.readDiskTotals();
      assertEquals(0, totals.readSectors);
    } catch (IOException e) {
      fail("Should not throw even if file is missing (checked inside)");
    }
  }

  @Test
  void readDiskTotals_whenSysBlockUnavailable_shouldStillUseDiskstats() throws IOException {
    Path procRoot = tempDir.resolve("proc");
    Files.createDirectories(procRoot);
    LinuxProcFs.setProcRoot(procRoot);
    LinuxProcFs.setSysRoot(tempDir.resolve("sys-unavailable"));

    Path diskstats = procRoot.resolve("diskstats");
    String sdaLine = "8 0 sda 1 1 100 1 1 1 200 1 0 0 0\n";
    String loopLine = "7 0 loop0 1 1 999 1 1 1 999 1 0 0 0\n";
    Files.writeString(diskstats, sdaLine + loopLine, StandardCharsets.UTF_8);

    LinuxProcFs.DiskTotals totals = LinuxProcFs.readDiskTotals();

    assertEquals(100L, totals.readSectors,
        "When /sys/block is unavailable, diskstats data should still be used");
    assertEquals(200L, totals.writtenSectors,
        "Pseudo devices like loop should still be excluded");
  }

  @Test
  void tryDetectCgroupV1_logic() throws Exception {
    LinuxProcFs.setProcRoot(tempDir);
    LinuxProcFs.setSysRoot(tempDir);

    // Create /proc/self/cgroup
    Path selfCgroup = tempDir.resolve("self/cgroup");
    Files.createDirectories(selfCgroup.getParent());
    Files.writeString(selfCgroup, "4:memory:/kubepods/besteffort/pod123\n");

    // Invoke private method via reflection
    Method v1Method = LinuxProcFs.class.getDeclaredMethod("tryDetectCgroupV1");
    v1Method.setAccessible(true);
    Object result = v1Method.invoke(null);

    assertNotNull(result);
    // Use reflection or pattern matching to check fields, or just cast if class was public (it's package private)
    // Since we are in same package (test package matches source package), we can access CgroupInfo
    LinuxProcFs.CgroupInfo info = (LinuxProcFs.CgroupInfo) result;
    assertEquals("v1", info.version);
    assertEquals("/kubepods/besteffort/pod123", info.path);
    assertEquals(tempDir.resolve("fs/cgroup/memory"), info.baseDir);
  }

  @Test
  void detectCgroup_forceV1() {
    LinuxProcFs.setProcRoot(tempDir);
    LinuxProcFs.setSysRoot(tempDir);
    // Ensure v2 controllers file does NOT exist
    // Ensure /proc/self/cgroup exists with memory
    try {
      Path selfCgroup = tempDir.resolve("self/cgroup");
      Files.createDirectories(selfCgroup.getParent());
      Files.writeString(selfCgroup, "4:memory:/my/cgroup/path\n");

      // We need to simulate Linux OS check? LinuxProcFs.isLinux() reads system property.
      // Already covered by separate test, but detectCgroup calls it.
      String originalOs = System.getProperty("os.name");
      System.setProperty("os.name", "Linux");

      try {
        LinuxProcFs.CgroupInfo info = LinuxProcFs.detectCgroup();
        assertEquals("v1", info.version);
        assertEquals("/my/cgroup/path", info.path);
      } finally {
         if (originalOs != null) System.setProperty("os.name", originalOs);
         else System.clearProperty("os.name");
      }
    } catch (IOException e) {
       fail(e);
    }
  }
}
