package kr.pletor.nodemetrics.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Swap metrics of {@link NodeMemMetrics} and {@link CgroupMemMetrics}. */
class SwapMetricsTest {

  @TempDir Path dir;

  // ---------------------------------------------------------------- node

  private static final class FakeProc extends NodeMemMetrics {
    private final List<String> meminfo;
    private final List<String> vmstat;
    private final IOException vmstatError;

    FakeProc(List<String> meminfo, List<String> vmstat, IOException vmstatError) {
      this.meminfo = meminfo;
      this.vmstat = vmstat;
      this.vmstatError = vmstatError;
    }

    @Override
    List<String> readProcMemInfoLines() {
      return meminfo;
    }

    @Override
    List<String> readProcVmstatLines() throws IOException {
      if (vmstatError != null) {
        throw vmstatError;
      }
      return vmstat;
    }
  }

  private static final List<String> MEMINFO_WITH_SWAP =
      List.of(
          "MemTotal:       16000000 kB",
          "MemFree:         8000000 kB",
          "MemAvailable:   12000000 kB",
          "SwapTotal:       2097152 kB",
          "SwapFree:        1048576 kB");

  private static final List<String> VMSTAT =
      List.of("nr_free_pages 1000", "pswpin 1234", "pswpout 5678", "pgmajfault 9");

  private static NodeMemMetrics polled(FakeProc metrics) {
    Assumptions.assumeTrue(LinuxProcFs.isLinux(), "reads the Linux code path");
    metrics.poll();
    return metrics;
  }

  @Test
  void nodeSwapUsageAndActivity() {
    NodeMemMetrics m = polled(new FakeProc(MEMINFO_WITH_SWAP, VMSTAT, null));

    assertEquals(2097152L * 1024L, m.getSwapTotalBytes());
    assertEquals(1048576L * 1024L, m.getSwapUsedBytes());
    assertEquals(1234L, m.getSwapInPagesTotal());
    assertEquals(5678L, m.getSwapOutPagesTotal());
    assertNull(m.lastRefreshError());
  }

  @Test
  void nodeWithoutSwapReportsZeroNotUnavailable() {
    NodeMemMetrics m =
        polled(
            new FakeProc(
                List.of("MemTotal: 1000 kB", "SwapTotal: 0 kB", "SwapFree: 0 kB"), VMSTAT, null));

    assertEquals(0L, m.getSwapTotalBytes());
    assertEquals(0L, m.getSwapUsedBytes());
  }

  @Test
  void nodeSwapIsUnavailableWhenMeminfoHasNoSwapLines() {
    NodeMemMetrics m = polled(new FakeProc(List.of("MemTotal: 1000 kB"), VMSTAT, null));

    assertEquals(-1L, m.getSwapTotalBytes());
    assertEquals(-1L, m.getSwapUsedBytes());
    assertEquals(1000L * 1024L, m.getTotalMemoryBytes());
  }

  @Test
  void missingVmstatIsUnavailableWithoutFailure() {
    NodeMemMetrics m =
        polled(new FakeProc(MEMINFO_WITH_SWAP, null, new NoSuchFileException("/proc/vmstat")));

    assertNull(m.getSwapInPagesTotal());
    assertNull(m.getSwapOutPagesTotal());
    assertEquals(2097152L * 1024L, m.getSwapTotalBytes(), "meminfo values are unaffected");
    assertNull(m.lastRefreshError());
  }

  @Test
  void unreadableVmstatIsFailureAndKeepsMemoryFigures() {
    NodeMemMetrics m = polled(new FakeProc(MEMINFO_WITH_SWAP, null, new IOException("boom")));

    assertNull(m.getSwapInPagesTotal());
    assertNotNull(m.lastRefreshError());
    assertEquals(16000000L * 1024L, m.getTotalMemoryBytes());
    assertEquals(1048576L * 1024L, m.getSwapUsedBytes());
  }

  @Test
  void parseCounterLineHandlesBadInput() {
    assertEquals(42L, MemStatsUtil.parseCounterLine("pswpin 42"));
    assertEquals(42L, MemStatsUtil.parseCounterLine("  pswpin   42  "));
    assertEquals(-1L, MemStatsUtil.parseCounterLine("pswpin"));
    assertEquals(-1L, MemStatsUtil.parseCounterLine("pswpin abc"));
    assertEquals(-1L, MemStatsUtil.parseCounterLine("pswpin -5"));
    assertEquals(-1L, MemStatsUtil.parseCounterLine(""));
  }

  // -------------------------------------------------------------- cgroup

  private void write(String name, String content) throws IOException {
    Files.writeString(dir.resolve(name), content);
  }

  private CgroupMemMetrics cgroup(String version) throws Exception {
    Assumptions.assumeTrue(LinuxProcFs.isLinux(), "reads the Linux code path");
    CgroupMemMetrics metrics = new CgroupMemMetrics();
    Field cgField = CgroupMemMetrics.class.getDeclaredField("cg");
    cgField.setAccessible(true);
    Object cg = cgField.get(metrics);
    set(cg, "version", version);
    set(cg, "resolved", dir);
    set(cg, "path", "/test");
    set(cg, "baseDir", null); // do not walk the host's real ancestors
    return metrics;
  }

  private static void set(Object target, String field, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(field);
    f.setAccessible(true);
    f.set(target, value);
  }

  @Test
  void cgroupV2ReadsSwapDirectly() throws Exception {
    write("memory.max", "max\n");
    write("memory.current", "1000\n");
    write("memory.swap.current", "300\n");
    write("memory.swap.max", "2048\n");
    CgroupMemMetrics m = cgroup("v2");

    m.poll();

    assertEquals(300L, m.getSwapUsageBytes());
    assertEquals(2048L, m.getSwapLimitBytes());
  }

  @Test
  void cgroupV2SwapLimitZeroMeansNoSwapAllowedAndMaxMeansUnlimited() throws Exception {
    write("memory.current", "1000\n");
    write("memory.swap.current", "0\n");
    write("memory.swap.max", "0\n");
    CgroupMemMetrics m = cgroup("v2");
    m.poll();
    assertEquals(0L, m.getSwapUsageBytes());
    assertEquals(0L, m.getSwapLimitBytes());

    write("memory.swap.max", "max\n");
    m.poll();
    assertEquals(-1L, m.getSwapLimitBytes());
  }

  @Test
  void cgroupV2WithoutSwapFilesIsUnavailable() throws Exception {
    write("memory.current", "1000\n");
    CgroupMemMetrics m = cgroup("v2");

    m.poll();

    assertEquals(-1L, m.getSwapUsageBytes());
    assertEquals(-1L, m.getSwapLimitBytes());
    assertNull(m.lastRefreshError());
  }

  @Test
  void cgroupV1SwapIsMemswMinusMemory() throws Exception {
    write("memory.limit_in_bytes", "1000\n");
    write("memory.usage_in_bytes", "400\n");
    write("memory.memsw.usage_in_bytes", "550\n");
    write("memory.memsw.limit_in_bytes", "1500\n");
    CgroupMemMetrics m = cgroup("v1");

    m.poll();

    assertEquals(150L, m.getSwapUsageBytes());
    assertEquals(500L, m.getSwapLimitBytes());
  }

  @Test
  void cgroupV1UnlimitedMemswAndMissingFilesAreUnavailable() throws Exception {
    write("memory.limit_in_bytes", "1000\n");
    write("memory.usage_in_bytes", "400\n");
    write("memory.memsw.limit_in_bytes", Long.MAX_VALUE + "\n");
    CgroupMemMetrics m = cgroup("v1");

    m.poll();

    assertEquals(-1L, m.getSwapUsageBytes(), "no memsw usage file: swap accounting is off");
    assertEquals(-1L, m.getSwapLimitBytes(), "unlimited memsw is reported as -1");
  }

  @Test
  void cgroupV1SwapNeverGoesNegative() throws Exception {
    write("memory.limit_in_bytes", "1000\n");
    write("memory.usage_in_bytes", "600\n");
    write("memory.memsw.usage_in_bytes", "500\n"); // racing reads can make memsw < usage
    write("memory.memsw.limit_in_bytes", "900\n");
    CgroupMemMetrics m = cgroup("v1");

    m.poll();

    assertEquals(0L, m.getSwapUsageBytes());
    assertEquals(0L, m.getSwapLimitBytes());
  }
}
