package kr.pletor.nodemetrics.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PressureMetricsTest {

  private static final String[] NODE_FILES = {"cpu", "memory", "io"};
  private static final String[] CGROUP_FILES = {"cpu.pressure", "memory.pressure", "io.pressure"};

  @TempDir Path dir;

  private void write(String name, String content) throws IOException {
    Files.writeString(dir.resolve(name), content);
  }

  private PressureMetrics nodeMetrics() {
    PressureMetrics m = new PressureMetrics(dir, NODE_FILES);
    m.poll();
    return m;
  }

  @Test
  void parsesAllSixLines() throws Exception {
    write(
        "cpu",
        "some avg10=1.50 avg60=0.50 avg300=0.10 total=1000\n"
            + "full avg10=0.25 avg60=0.00 avg300=0.00 total=200\n");
    write(
        "memory",
        "some avg10=2.00 avg60=1.00 avg300=0.50 total=3000\n"
            + "full avg10=0.75 avg60=0.10 avg300=0.00 total=400\n");
    write(
        "io",
        "some avg10=10.00 avg60=5.00 avg300=1.00 total=5000\n"
            + "full avg10=3.50 avg60=1.00 avg300=0.20 total=600\n");

    PressureMetrics m = nodeMetrics();

    assertEquals(1.5, m.getCpuSomeAvg10());
    assertEquals(1000L, m.getCpuSomeTotalMicros());
    assertEquals(0.25, m.getCpuFullAvg10());
    assertEquals(200L, m.getCpuFullTotalMicros());
    assertEquals(2.0, m.getMemorySomeAvg10());
    assertEquals(3000L, m.getMemorySomeTotalMicros());
    assertEquals(0.75, m.getMemoryFullAvg10());
    assertEquals(400L, m.getMemoryFullTotalMicros());
    assertEquals(10.0, m.getIoSomeAvg10());
    assertEquals(5000L, m.getIoSomeTotalMicros());
    assertEquals(3.5, m.getIoFullAvg10());
    assertEquals(600L, m.getIoFullTotalMicros());
    assertNull(m.lastRefreshError());
  }

  @Test
  void cpuFullLineIsAbsentOnOlderKernels() throws Exception {
    write("cpu", "some avg10=1.00 avg60=0.00 avg300=0.00 total=10\n");
    write("memory", "some avg10=0.00 avg60=0.00 avg300=0.00 total=0\n");
    write("io", "some avg10=0.00 avg60=0.00 avg300=0.00 total=0\n");

    PressureMetrics m = nodeMetrics();

    assertEquals(10L, m.getCpuSomeTotalMicros());
    assertEquals(-1.0, m.getCpuFullAvg10());
    assertEquals(-1L, m.getCpuFullTotalMicros());
    assertNull(m.lastRefreshError());
  }

  @Test
  void missingFilesMeanUnavailableNotFailure() {
    PressureMetrics m = nodeMetrics();

    assertEquals(-1.0, m.getMemorySomeAvg10());
    assertEquals(-1L, m.getMemorySomeTotalMicros());
    assertEquals(-1L, m.getIoFullTotalMicros());
    assertNull(m.lastRefreshError(), "PSI being absent is not a refresh failure");
  }

  @Test
  void unreadableFileMeansUnavailableNotFailure() throws Exception {
    // A directory where a file is expected: not a regular file, so treated as absent.
    Files.createDirectory(dir.resolve("memory"));

    PressureMetrics m = nodeMetrics();

    assertEquals(-1L, m.getMemorySomeTotalMicros());
    assertNull(m.lastRefreshError());
  }

  @Test
  void nullDirectoryIsUnavailable() {
    PressureMetrics m = new PressureMetrics(null, CGROUP_FILES);
    m.poll();

    assertEquals(-1.0, m.getCpuSomeAvg10());
    assertEquals(-1L, m.getMemoryFullTotalMicros());
    assertNull(m.lastRefreshError());
  }

  @Test
  void readsCgroupFileNames() throws Exception {
    write("memory.pressure", "some avg10=4.00 avg60=0.00 avg300=0.00 total=77\n");

    PressureMetrics m = new PressureMetrics(dir, CGROUP_FILES);
    m.poll();

    assertEquals(4.0, m.getMemorySomeAvg10());
    assertEquals(77L, m.getMemorySomeTotalMicros());
    assertEquals(-1L, m.getCpuSomeTotalMicros());
  }

  @Test
  void malformedLinesAreIgnoredAndOtherLinesStillRead() throws Exception {
    write(
        "memory",
        "garbage\n"
            + "some avg10=abc avg60=0 avg300=0 total=5\n"
            + "full avg10=1.25 avg60=0 avg300=0 total=99\n");

    PressureMetrics m = nodeMetrics();

    assertEquals(-1L, m.getMemorySomeTotalMicros());
    assertEquals(1.25, m.getMemoryFullAvg10());
    assertEquals(99L, m.getMemoryFullTotalMicros());
    assertNull(m.lastRefreshError());
  }

  @Test
  void valuesFollowTheFileOnTheNextRefresh() throws Exception {
    write("io", "some avg10=1.00 avg60=0 avg300=0 total=100\n");
    PressureMetrics m = nodeMetrics();
    assertEquals(100L, m.getIoSomeTotalMicros());

    write("io", "some avg10=2.00 avg60=0 avg300=0 total=250\n");
    m.poll();

    assertEquals(250L, m.getIoSomeTotalMicros());
    assertEquals(2.0, m.getIoSomeAvg10());
  }

  @Test
  void parseLineHandlesUnknownKeysAndRejectsIncompleteLines() {
    PressureMetrics.Stall stall =
        PressureMetrics.parseLine("some avg10=0.50 avg60=1 avg300=2 foo=bar total=42");
    assertNotNull(stall);
    assertEquals(0.5, stall.avg10);
    assertEquals(42L, stall.totalMicros);

    assertNull(PressureMetrics.parseLine("some avg60=1 avg300=2 total=42"));
    assertNull(PressureMetrics.parseLine("some avg10=1"));
    assertNull(PressureMetrics.parseLine("some avg10=1 total=-5"));
    assertNull(PressureMetrics.parseLine(null));
    assertNull(PressureMetrics.parseLine(""));
  }

  @Test
  void factoriesNeverThrow() {
    PressureMetrics node = PressureMetrics.forNode();
    PressureMetrics cgroup = PressureMetrics.forCgroup();
    node.poll();
    cgroup.poll();

    assertNull(node.lastRefreshError());
    assertNull(cgroup.lastRefreshError());
  }
}
