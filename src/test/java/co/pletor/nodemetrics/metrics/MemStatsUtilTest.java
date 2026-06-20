// src/test/java/co/pletor/nodemetrics/metrics/MemStatsUtilTest.java
package co.pletor.nodemetrics.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link MemStatsUtil}.
 * <p>
 * Covers:
 * <ul>
 *   <li>parseKbLine: parsing various /proc/meminfo-style lines</li>
 *   <li>readKeyValues: parsing simple key/value files</li>
 *   <li>readCgroupMemoryStat: behavior for different CgroupInfo configurations</li>
 * </ul>
 */
class MemStatsUtilTest {

  @TempDir
  Path tempDir;

  // ----------------------------------------------------
  // parseKbLine tests
  // ----------------------------------------------------

  @Test
  void parseKbLine_shouldReturnNumberForValidMeminfoLine() {
    long v = MemStatsUtil.parseKbLine("Dirty:        1234 kB");
    assertEquals(1234L, v);
  }

  @Test
  void parseKbLine_shouldHandleMultipleSpacesAndTabs() {
    long v = MemStatsUtil.parseKbLine("Cached:\t   987654   kB");
    assertEquals(987654L, v);
  }

  @Test
  void parseKbLine_shouldReturnMinusOneWhenNoNumberPresent() {
    assertEquals(-1L, MemStatsUtil.parseKbLine("Dirty: kB"));
    assertEquals(-1L, MemStatsUtil.parseKbLine("Something totally different"));
    assertEquals(-1L, MemStatsUtil.parseKbLine(""));
  }

  @Test
  void parseKbLine_shouldReturnMinusOneWhenNumberIsNotParsable() {
    long v = MemStatsUtil.parseKbLine("Dirty:    12x34 kB");
    assertEquals(-1L, v);
  }

  // ----------------------------------------------------
  // readKeyValues tests
  // ----------------------------------------------------

  @Test
  void readKeyValues_shouldParseValidLinesAndIgnoreInvalidOnes() throws IOException {
    Path file = tempDir.resolve("stat.txt");
    String content =
        "file_dirty 100\n"
            + "file_writeback 200 extraToken\n"
            + "\n"
            + "invalid_line\n"
            + "broken 3x";
    Files.writeString(file, content, StandardCharsets.UTF_8);

    // When reading a well-formed key/value file with some invalid lines,
    // only valid lines should be parsed into the map.
    Map<String, Long> map = MemStatsUtil.readKeyValues(file);

    assertEquals(100L, map.get("file_dirty"));
    assertEquals(200L, map.get("file_writeback"));
    assertFalse(map.containsKey("invalid_line"));
    assertFalse(map.containsKey("broken"));
  }

  @Test
  void readKeyValues_emptyFileShouldReturnEmptyMap() throws IOException {
    Path file = tempDir.resolve("empty.txt");
    Files.writeString(file, "", StandardCharsets.UTF_8);

    Map<String, Long> map = MemStatsUtil.readKeyValues(file);
    assertTrue(map.isEmpty());
  }

  // ----------------------------------------------------
  // readCgroupMemoryStat tests
  //  - Also covers internal resolveCgroupMemoryStatPath branches
  //  - CgroupInfo structure:
  //    static class CgroupInfo {
  //      String version = "none";
  //      String path = "";
  //      Path baseDir = null;
  //      Path resolved = null;
  //    }
  // ----------------------------------------------------

  @Test
  void readCgroupMemoryStat_nullInfoShouldReturnEmptyMap() throws IOException {
    Map<String, Long> map = MemStatsUtil.readCgroupMemoryStat(null);
    assertNotNull(map);
    assertTrue(map.isEmpty());
  }

  @Test
  void readCgroupMemoryStat_unsupportedVersionShouldReturnEmptyMap() throws IOException {
    LinuxProcFs.CgroupInfo info = new LinuxProcFs.CgroupInfo();
    info.version = "none"; // default value, not v1/v2

    Map<String, Long> map = MemStatsUtil.readCgroupMemoryStat(info);
    assertNotNull(map);
    assertTrue(map.isEmpty());
  }

  @Test
  void readCgroupMemoryStat_v1WithResolvedDirShouldUseThatDirectory() throws IOException {
    // given: v1 cgroup directory with a memory.stat file
    Path cgDir = tempDir.resolve("cgv1");
    Files.createDirectories(cgDir);
    Path stat = cgDir.resolve("memory.stat");
    Files.writeString(stat, "cache 12345\n", StandardCharsets.UTF_8);

    LinuxProcFs.CgroupInfo info = new LinuxProcFs.CgroupInfo();
    info.version = "v1";
    // resolveCgroupMemoryStatPath should use this resolved directory
    info.resolved = cgDir;

    Map<String, Long> map = MemStatsUtil.readCgroupMemoryStat(info);

    assertNotNull(map);
    assertFalse(map.isEmpty());
    assertEquals(12345L, map.get("cache"));
  }

  @Test
  void readCgroupMemoryStat_v2WithResolvedDirShouldUseThatDirectory() throws IOException {
    // given: v2 cgroup directory with a memory.stat file
    Path cgDir = tempDir.resolve("cgv2");
    Files.createDirectories(cgDir);
    Path stat = cgDir.resolve("memory.stat");
    String content =
        "file 111\n"
            + "anon 222";
    Files.writeString(stat, content, StandardCharsets.UTF_8);

    LinuxProcFs.CgroupInfo info = new LinuxProcFs.CgroupInfo();
    info.version = "v2";
    info.resolved = cgDir;

    Map<String, Long> map = MemStatsUtil.readCgroupMemoryStat(info);

    assertNotNull(map);
    assertFalse(map.isEmpty());
    assertEquals(111L, map.get("file"));
    assertEquals(222L, map.get("anon"));
  }

  @Test
  void readCgroupMemoryStat_v1WithoutMemoryStatShouldReturnEmptyMap() throws IOException {
    // given: v1 cgroup directory without a memory.stat file
    Path cgDir = tempDir.resolve("cgv1_empty");
    Files.createDirectories(cgDir);
    // Intentionally do not create memory.stat

    LinuxProcFs.CgroupInfo info = new LinuxProcFs.CgroupInfo();
    info.version = "v1";
    info.resolved = cgDir;

    Map<String, Long> map = MemStatsUtil.readCgroupMemoryStat(info);

    assertNotNull(map);
    assertTrue(map.isEmpty());
  }
}
