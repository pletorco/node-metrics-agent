package co.pletor.nodemetrics.metrics;

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

class MemoryMapMetricsTest {

  @TempDir Path root;
  private Path proc;

  @BeforeEach
  void fakeProc() throws IOException {
    Assumptions.assumeTrue(LinuxProcFs.isLinux(), "reads the Linux code path");
    proc = Files.createDirectories(root.resolve("proc"));
    LinuxProcFs.setProcRoot(proc);
  }

  @AfterEach
  void restoreProc() {
    LinuxProcFs.setProcRoot(Path.of("/proc"));
  }

  private void write(String relative, String content) throws IOException {
    Path file = proc.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private static String maps(int lines) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < lines; i++) {
      sb.append(String.format("%012x-%012x r--p 00000000 08:01 %d /lib/x%d.so%n", i, i + 1, i, i));
    }
    return sb.toString();
  }

  @Test
  void countsTheLinesOfSelfMaps() throws Exception {
    write("self/maps", maps(1234));
    write("sys/vm/max_map_count", "65530\n");

    MemoryMapMetrics m = new MemoryMapMetrics();
    m.poll();

    assertEquals(1234L, m.getMemoryMapCount());
    assertEquals(65530L, m.getMaxMemoryMapCount());
    assertNull(m.lastRefreshError());
  }

  @Test
  void countsMoreLinesThanTheReadBufferHolds() throws Exception {
    // Well over one 64 KiB buffer, so lines are counted across buffer boundaries.
    write("self/maps", maps(5000));

    MemoryMapMetrics m = new MemoryMapMetrics();
    m.poll();

    assertEquals(5000L, m.getMemoryMapCount());
  }

  @Test
  void emptyMapsCountsZero() throws Exception {
    write("self/maps", "");

    MemoryMapMetrics m = new MemoryMapMetrics();
    m.poll();

    assertEquals(0L, m.getMemoryMapCount());
  }

  @Test
  void limitIsMinusOneWhenItsFileIsMissingOrMalformed() throws Exception {
    write("self/maps", maps(3));
    MemoryMapMetrics m = new MemoryMapMetrics();
    m.poll();
    assertEquals(3L, m.getMemoryMapCount());
    assertEquals(-1L, m.getMaxMemoryMapCount());

    write("sys/vm/max_map_count", "not a number\n");
    m.poll();
    assertEquals(-1L, m.getMaxMemoryMapCount());
  }

  @Test
  void missingMapsIsUnavailableWithoutFailure() {
    MemoryMapMetrics m = new MemoryMapMetrics();
    m.poll();

    assertEquals(-1L, m.getMemoryMapCount());
    assertEquals(-1L, m.getMaxMemoryMapCount());
    assertNull(m.lastRefreshError());
  }

  @Test
  void unreadableMapsKeepsPreviousValuesAndIsReported() throws Exception {
    write("self/maps", maps(10));
    write("sys/vm/max_map_count", "100\n");
    MemoryMapMetrics m = new MemoryMapMetrics();
    m.poll();

    Files.delete(proc.resolve("self/maps"));
    Files.createDirectory(proc.resolve("self/maps"));
    m.poll();

    assertEquals(10L, m.getMemoryMapCount());
    assertNotNull(m.lastRefreshError());
  }

  @Test
  void realProcessHasMappings() {
    LinuxProcFs.setProcRoot(Path.of("/proc"));
    MemoryMapMetrics m = new MemoryMapMetrics();
    m.poll();

    org.junit.jupiter.api.Assertions.assertTrue(m.getMemoryMapCount() > 0L);
  }
}
