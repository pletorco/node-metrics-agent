package co.pletor.nodemetrics.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** System-wide file handles ({@link FdMetrics}) and OOM kill counters. */
class SystemHandlesAndOomTest {

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

  // ---------------------------------------------------------- file handles

  @Test
  void systemHandlesFromFileNr() throws Exception {
    write("sys/fs/file-nr", "9216\t0\t1645334\n");
    FdMetrics m = new FdMetrics();
    m.poll();

    assertEquals(9216L, m.getSystemOpenFileHandles());
    assertEquals(1645334L, m.getSystemMaxFileHandles());
    assertNull(m.lastRefreshError());
  }

  @Test
  void unusedHandlesAreSubtracted() throws Exception {
    write("sys/fs/file-nr", "1000 40 5000\n");
    FdMetrics m = new FdMetrics();
    m.poll();

    assertEquals(960L, m.getSystemOpenFileHandles());
    assertEquals(5000L, m.getSystemMaxFileHandles());
  }

  @Test
  void missingFileNrIsUnavailableWithoutFailure() {
    FdMetrics m = new FdMetrics();
    m.poll();

    assertEquals(-1L, m.getSystemOpenFileHandles());
    assertEquals(-1L, m.getSystemMaxFileHandles());
    assertNull(m.lastRefreshError());
  }

  @Test
  void malformedFileNrIsUnavailable() throws Exception {
    write("sys/fs/file-nr", "garbage\n");
    FdMetrics m = new FdMetrics();
    m.poll();

    assertEquals(-1L, m.getSystemOpenFileHandles());
    assertEquals(-1L, m.getSystemMaxFileHandles());
  }

  @Test
  void unreadableFileNrKeepsPreviousValuesAndIsReported() throws Exception {
    write("sys/fs/file-nr", "500 0 1000\n");
    FdMetrics m = new FdMetrics();
    m.poll();
    assertEquals(500L, m.getSystemOpenFileHandles());

    Files.delete(proc.resolve("sys/fs/file-nr"));
    Files.createDirectory(proc.resolve("sys/fs/file-nr"));
    m.poll();

    assertEquals(500L, m.getSystemOpenFileHandles());
    assertNotNull(m.lastRefreshError());
  }

  @Test
  void processDescriptorsAreUnaffected() throws Exception {
    write("sys/fs/file-nr", "500 0 1000\n");
    FdMetrics m = new FdMetrics();
    m.poll();

    // The JVM's own descriptor count comes from the OS MXBean, not from the fake proc.
    org.junit.jupiter.api.Assertions.assertTrue(m.getOpenFileDescriptorCount() > 0L);
  }

  // ------------------------------------------------------------- node OOM

  private static final class FakeVmstat extends NodeMemMetrics {
    private final List<String> vmstat;

    FakeVmstat(List<String> vmstat) {
      this.vmstat = vmstat;
    }

    @Override
    List<String> readProcMemInfoLines() {
      return List.of("MemTotal: 1000 kB");
    }

    @Override
    List<String> readProcVmstatLines() {
      return vmstat;
    }
  }

  @Test
  void nodeOomKillTotalFromVmstat() {
    NodeMemMetrics m = new FakeVmstat(List.of("pswpin 1", "oom_kill 7", "pswpout 2"));
    m.poll();

    assertEquals(7L, m.getSystemOomKillTotal());
    assertEquals(1L, m.getSwapInPagesTotal());
  }

  @Test
  void nodeOomKillIsMinusOneOnKernelsWithoutTheCounter() {
    NodeMemMetrics m = new FakeVmstat(List.of("pswpin 1", "pswpout 2"));
    m.poll();

    assertEquals(-1L, m.getSystemOomKillTotal());
    assertEquals(2L, m.getSwapOutPagesTotal());
  }

  // ------------------------------------------------------------ cgroup OOM

  private Path cgroupDir;

  private CgroupMemMetrics cgroup(String version) throws Exception {
    cgroupDir = Files.createDirectories(root.resolve("cg-" + version));
    CgroupMemMetrics metrics = new CgroupMemMetrics();
    Field cgField = CgroupMemMetrics.class.getDeclaredField("cg");
    cgField.setAccessible(true);
    Object cg = cgField.get(metrics);
    set(cg, "version", version);
    set(cg, "resolved", cgroupDir);
    set(cg, "path", "/test");
    set(cg, "baseDir", null);
    return metrics;
  }

  private static void set(Object target, String field, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(field);
    f.setAccessible(true);
    f.set(target, value);
  }

  @Test
  void cgroupV2OomKillFromMemoryEvents() throws Exception {
    CgroupMemMetrics m = cgroup("v2");
    Files.writeString(
        cgroupDir.resolve("memory.events"), "low 0\nhigh 0\nmax 4\noom 3\noom_kill 2\n");
    m.poll();

    assertEquals(2L, m.getMemoryOomKillTotal());
  }

  @Test
  void cgroupV1OomKillFromOomControl() throws Exception {
    CgroupMemMetrics m = cgroup("v1");
    Files.writeString(
        cgroupDir.resolve("memory.oom_control"), "oom_kill_disable 0\nunder_oom 0\noom_kill 5\n");
    m.poll();

    assertEquals(5L, m.getMemoryOomKillTotal());
  }

  @Test
  void cgroupOomKillIsMinusOneWithoutTheFileOrTheLine() throws Exception {
    CgroupMemMetrics none = cgroup("v2");
    none.poll();
    assertEquals(-1L, none.getMemoryOomKillTotal());

    CgroupMemMetrics old = cgroup("v1");
    Files.writeString(cgroupDir.resolve("memory.oom_control"), "oom_kill_disable 0\nunder_oom 0\n");
    old.poll();
    assertEquals(-1L, old.getMemoryOomKillTotal(), "kernels before 4.13 have no oom_kill line");
    assertNull(old.lastRefreshError());
  }
}
