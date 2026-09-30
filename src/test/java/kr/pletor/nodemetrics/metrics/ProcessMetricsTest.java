package kr.pletor.nodemetrics.metrics;

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

class ProcessMetricsTest {

  private static final String STATUS =
      "Name:\tjava\n"
          + "VmPeak:\t 9999999 kB\n"
          + "VmHWM:\t  3000000 kB\n"
          + "VmRSS:\t  2500000 kB\n"
          + "RssAnon:\t  2000000 kB\n"
          + "RssFile:\t   400000 kB\n"
          + "RssShmem:\t  100000 kB\n"
          + "VmSwap:\t     1024 kB\n"
          + "Threads:\t123\n"
          + "voluntary_ctxt_switches:\t5\n";

  private static final String IO =
      "rchar: 1\nwchar: 2\nsyscr: 3\nsyscw: 4\nread_bytes: 4096\nwrite_bytes: 8192\n"
          + "cancelled_write_bytes: 0\n";

  @TempDir Path root;
  private Path self;

  @BeforeEach
  void fakeProc() throws IOException {
    Assumptions.assumeTrue(LinuxProcFs.isLinux(), "reads the Linux code path");
    Path proc = Files.createDirectories(root.resolve("proc"));
    self = Files.createDirectories(proc.resolve("self"));
    LinuxProcFs.setProcRoot(proc);
  }

  @AfterEach
  void restoreProc() {
    LinuxProcFs.setProcRoot(Path.of("/proc"));
  }

  private void write(String name, String content) throws IOException {
    Files.writeString(self.resolve(name), content);
  }

  @Test
  void parsesStatusAndIo() throws Exception {
    write("status", STATUS);
    write("io", IO);

    ProcessMetrics m = new ProcessMetrics();
    m.poll();

    assertEquals(2_500_000L * 1024L, m.getResidentSetBytes());
    assertEquals(3_000_000L * 1024L, m.getResidentSetPeakBytes());
    assertEquals(2_000_000L * 1024L, m.getResidentAnonBytes());
    assertEquals(400_000L * 1024L, m.getResidentFileBytes());
    assertEquals(100_000L * 1024L, m.getResidentShmemBytes());
    assertEquals(1024L * 1024L, m.getSwapBytes());
    assertEquals(123L, m.getThreadCount());
    assertEquals(4096L, m.getIoReadBytesTotal());
    assertEquals(8192L, m.getIoWriteBytesTotal());
    assertNull(m.lastRefreshError());
  }

  @Test
  void ioIsOptional() throws Exception {
    write("status", STATUS);

    ProcessMetrics m = new ProcessMetrics();
    m.poll();

    assertEquals(2_500_000L * 1024L, m.getResidentSetBytes());
    assertNull(m.getIoReadBytesTotal());
    assertNull(m.getIoWriteBytesTotal());
    assertNull(m.lastRefreshError(), "task I/O accounting is often absent");
  }

  @Test
  void unreadableIoDoesNotAffectStatus() throws Exception {
    write("status", STATUS);
    Files.createDirectory(self.resolve("io")); // reading it as a file fails

    ProcessMetrics m = new ProcessMetrics();
    m.poll();

    assertEquals(123L, m.getThreadCount());
    assertNull(m.getIoWriteBytesTotal());
    assertNull(m.lastRefreshError());
  }

  @Test
  void missingStatusIsUnavailableWithoutFailure() {
    ProcessMetrics m = new ProcessMetrics();
    m.poll();

    assertEquals(-1L, m.getResidentSetBytes());
    assertEquals(-1L, m.getThreadCount());
    assertNull(m.lastRefreshError());
  }

  @Test
  void fieldsAbsentFromStatusAreMinusOne() throws Exception {
    write("status", "VmRSS:\t 100 kB\nThreads:\t7\n"); // no VmSwap, RssAnon, ... on old kernels

    ProcessMetrics m = new ProcessMetrics();
    m.poll();

    assertEquals(100L * 1024L, m.getResidentSetBytes());
    assertEquals(7L, m.getThreadCount());
    assertEquals(-1L, m.getResidentAnonBytes());
    assertEquals(-1L, m.getSwapBytes());
  }

  @Test
  void unreadableStatusKeepsPreviousValuesAndIsReported() throws Exception {
    write("status", STATUS);
    ProcessMetrics m = new ProcessMetrics();
    m.poll();

    Files.delete(self.resolve("status"));
    Files.createDirectory(self.resolve("status"));
    m.poll();

    assertEquals(123L, m.getThreadCount(), "previous value kept");
    assertNotNull(m.lastRefreshError());
  }

  @Test
  void valuesFollowTheFile() throws Exception {
    write("status", STATUS);
    ProcessMetrics m = new ProcessMetrics();
    m.poll();

    write("status", STATUS.replace("Threads:\t123", "Threads:\t150"));
    m.poll();

    assertEquals(150L, m.getThreadCount());
  }
}
