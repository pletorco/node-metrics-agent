package co.pletor.nodemetrics.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.management.OperatingSystemMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The per-mode cumulative CPU ticks of {@link CpuMetrics}. */
class CpuModeTicksTest {

  @TempDir Path root;

  @AfterEach
  void restoreRoots() {
    CpuMetrics.procRoot = Path.of("/proc");
    CpuMetrics.sysRoot = Path.of("/sys");
  }

  private CpuMetrics metricsReading(String statFirstLine) throws Exception {
    Assumptions.assumeTrue(LinuxProcFs.isLinux(), "reads the Linux code path");
    CpuMetrics.procRoot = Files.createDirectories(root.resolve("proc"));
    CpuMetrics.sysRoot = Files.createDirectories(root.resolve("sys"));
    Files.writeString(
        CpuMetrics.procRoot.resolve("stat"),
        statFirstLine + "\ncpu0 1 1 1 1 1 1 1 1 0 0\nintr 12345 1 2 3\n");
    CpuMetrics metrics = new CpuMetrics((OperatingSystemMXBean) null);
    metrics.setReadRefreshEnabled(false);
    metrics.poll();
    return metrics;
  }

  @Test
  void exposesEveryModeOfTheAggregateLine() throws Exception {
    CpuMetrics m = metricsReading("cpu  100 20 300 4000 50 6 7 8 0 0");

    assertEquals(100L, m.getSystemCpuUserTicks());
    assertEquals(20L, m.getSystemCpuNiceTicks());
    assertEquals(300L, m.getSystemCpuSystemTicks());
    assertEquals(4000L, m.getSystemCpuIdleTicks());
    assertEquals(6L, m.getSystemCpuIrqTicks());
    assertEquals(7L, m.getSystemCpuSoftIrqTicks());
    assertEquals(50L, m.getSystemCpuIoWaitTicks());
    assertEquals(8L, m.getSystemCpuStealTicks());
    assertEquals(100L + 20 + 300 + 4000 + 50 + 6 + 7 + 8, m.getSystemCpuTotalTicks());
  }

  @Test
  void ticksFollowTheFileAndNeverUseThePerCpuLines() throws Exception {
    CpuMetrics m = metricsReading("cpu  100 20 300 4000 50 6 7 8 0 0");
    Files.writeString(
        CpuMetrics.procRoot.resolve("stat"),
        "cpu  150 20 340 4100 55 6 9 8 0 0\ncpu0 9 9 9 9 9 9 9 9\n");

    m.poll();

    assertEquals(150L, m.getSystemCpuUserTicks());
    assertEquals(340L, m.getSystemCpuSystemTicks());
    assertEquals(9L, m.getSystemCpuSoftIrqTicks());
  }

  @Test
  void ticksAreMinusOneBeforeTheFirstSuccessfulRead() {
    CpuMetrics m = new CpuMetrics((OperatingSystemMXBean) null);
    m.setReadRefreshEnabled(false);

    assertEquals(-1L, m.getSystemCpuUserTicks());
    assertEquals(-1L, m.getSystemCpuIdleTicks());
    assertEquals(-1L, m.getSystemCpuSoftIrqTicks());
  }
}
