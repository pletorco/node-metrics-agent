package co.pletor.nodemetrics.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The effective CPU limit of a cgroup, on its own and as exposed by {@link CpuMetrics}. */
class CgroupCpuLimitTest {

  private static final double DELTA = 0.000001;

  @TempDir Path root;

  @AfterEach
  void restoreRoots() {
    CpuMetrics.procRoot = Path.of("/proc");
    CpuMetrics.sysRoot = Path.of("/sys");
  }

  private Path dir(String relative) throws IOException {
    return Files.createDirectories(root.resolve(relative));
  }

  private static void write(Path dir, String name, String content) throws IOException {
    Files.writeString(dir.resolve(name), content);
  }

  // ------------------------------------------------------------------ v2

  @Test
  void v2ContainerQuotaOverPeriod() throws Exception {
    Path ctr = dir("ctr");
    write(ctr, "cpu.max", "200000 100000\n");

    assertEquals(2.0, CgroupCpuLimit.effectiveCores(ctr, root), DELTA);
  }

  @Test
  void v2FractionalLimit() throws Exception {
    Path ctr = dir("ctr");
    write(ctr, "cpu.max", "50000 100000\n");

    assertEquals(0.5, CgroupCpuLimit.effectiveCores(ctr, root), DELTA);
  }

  @Test
  void v2UnlimitedIsMinusOne() throws Exception {
    Path ctr = dir("ctr");
    write(ctr, "cpu.max", "max 100000\n");

    assertEquals(-1.0, CgroupCpuLimit.effectiveCores(ctr, root), DELTA);
  }

  @Test
  void v2ContainerWithoutQuotaIsBoundedByItsPod() throws Exception {
    Path pod = dir("kubepods/pod1");
    Path ctr = dir("kubepods/pod1/ctr");
    write(pod, "cpu.max", "400000 100000\n");
    write(ctr, "cpu.max", "max 100000\n");

    assertEquals(4.0, CgroupCpuLimit.effectiveCores(ctr, root), DELTA);
  }

  @Test
  void v2ContainerQuotaLargerThanThePodsIsNeverReachable() throws Exception {
    Path pod = dir("kubepods/pod1");
    Path ctr = dir("kubepods/pod1/ctr");
    write(pod, "cpu.max", "200000 100000\n");
    write(ctr, "cpu.max", "800000 100000\n");

    assertEquals(2.0, CgroupCpuLimit.effectiveCores(ctr, root), DELTA);
  }

  @Test
  void v2TighterContainerQuotaWins() throws Exception {
    Path pod = dir("kubepods/pod1");
    Path ctr = dir("kubepods/pod1/ctr");
    write(pod, "cpu.max", "400000 100000\n");
    write(ctr, "cpu.max", "100000 100000\n");

    assertEquals(1.0, CgroupCpuLimit.effectiveCores(ctr, root), DELTA);
  }

  @Test
  void ancestorsAboveTheMountRootAreNotRead() throws Exception {
    // Inside a cgroup namespace the mount root is the container's own cgroup.
    Path mountRoot = dir("ns");
    write(root, "cpu.max", "100000 100000\n"); // above the mount root: must be ignored
    write(mountRoot, "cpu.max", "max 100000\n");

    assertEquals(-1.0, CgroupCpuLimit.effectiveCores(mountRoot, mountRoot), DELTA);
  }

  @Test
  void directoryOutsideTheRootIsReadOnItsOwn() throws Exception {
    Path elsewhere = dir("elsewhere");
    Path unrelatedRoot = dir("unrelated");
    write(elsewhere, "cpu.max", "300000 100000\n");
    write(root, "cpu.max", "100000 100000\n");

    assertEquals(3.0, CgroupCpuLimit.effectiveCores(elsewhere, unrelatedRoot), DELTA);
    assertEquals(3.0, CgroupCpuLimit.effectiveCores(elsewhere, null), DELTA);
  }

  // ------------------------------------------------------------------ v1

  @Test
  void v1QuotaOverPeriod() throws Exception {
    Path cpu = dir("cpu/pod/ctr");
    write(cpu, "cpu.cfs_quota_us", "150000\n");
    write(cpu, "cpu.cfs_period_us", "100000\n");

    assertEquals(1.5, CgroupCpuLimit.effectiveCores(cpu, root), DELTA);
  }

  @Test
  void v1MinusOneQuotaIsUnlimited() throws Exception {
    Path cpu = dir("cpu/ctr");
    write(cpu, "cpu.cfs_quota_us", "-1\n");
    write(cpu, "cpu.cfs_period_us", "100000\n");

    assertEquals(-1.0, CgroupCpuLimit.effectiveCores(cpu, root), DELTA);
  }

  @Test
  void v1PodQuotaBoundsContainerWithoutOne() throws Exception {
    Path pod = dir("cpu/pod");
    Path ctr = dir("cpu/pod/ctr");
    write(pod, "cpu.cfs_quota_us", "100000\n");
    write(pod, "cpu.cfs_period_us", "100000\n");
    write(ctr, "cpu.cfs_quota_us", "-1\n");
    write(ctr, "cpu.cfs_period_us", "100000\n");

    assertEquals(1.0, CgroupCpuLimit.effectiveCores(ctr, root), DELTA);
  }

  // ------------------------------------------------------------ bad input

  @Test
  void missingOrMalformedFilesAreMinusOne() throws Exception {
    Path none = dir("none");
    assertEquals(-1.0, CgroupCpuLimit.effectiveCores(none, root), DELTA);

    Path bad = dir("bad");
    write(bad, "cpu.max", "garbage\n");
    assertEquals(-1.0, CgroupCpuLimit.effectiveCores(bad, root), DELTA);

    Path zero = dir("zero");
    write(zero, "cpu.max", "0 100000\n");
    assertEquals(-1.0, CgroupCpuLimit.effectiveCores(zero, root), DELTA);

    Path halfV1 = dir("halfv1");
    write(halfV1, "cpu.cfs_quota_us", "100000\n");
    assertEquals(-1.0, CgroupCpuLimit.effectiveCores(halfV1, root), DELTA);

    assertEquals(-1.0, CgroupCpuLimit.effectiveCores(null, root), DELTA);
  }

  @Test
  void parseCpuMaxHandlesTheDocumentedForms() {
    assertEquals(2.0, CgroupCpuLimit.parseCpuMax("200000 100000"), DELTA);
    assertEquals(-1.0, CgroupCpuLimit.parseCpuMax("max 100000"), DELTA);
    assertEquals(-1.0, CgroupCpuLimit.parseCpuMax("200000"), DELTA);
    assertEquals(-1.0, CgroupCpuLimit.parseCpuMax(""), DELTA);
  }

  // -------------------------------------------------- through CpuMetrics

  @Test
  void cpuMetricsExposesTheLimitNextToCpuStat() throws Exception {
    Assumptions.assumeTrue(LinuxProcFs.isLinux());
    CpuMetrics.procRoot = root.resolve("proc");
    CpuMetrics.sysRoot = root.resolve("sys");
    Files.createDirectories(CpuMetrics.procRoot);
    Path cgroupDir = Files.createDirectories(CpuMetrics.sysRoot.resolve("fs/cgroup"));
    write(
        cgroupDir,
        "cpu.stat",
        "usage_usec 1000\nnr_periods 10\nnr_throttled 2\nthrottled_usec 5\n");
    write(cgroupDir, "cpu.max", "250000 100000\n");

    CpuMetrics metrics = new CpuMetrics((OperatingSystemMXBean) null);
    metrics.setReadRefreshEnabled(false);
    metrics.poll();
    assertEquals(2.5, metrics.getCgroupCpuLimitCores(), DELTA);

    // A changed limit (for example an in-place pod resize) is picked up on the next poll.
    write(cgroupDir, "cpu.max", "max 100000\n");
    metrics.poll();
    assertEquals(-1.0, metrics.getCgroupCpuLimitCores(), DELTA);
  }

  @Test
  void cpuMetricsReportsMinusOneWithoutAnyCgroupFiles() throws Exception {
    Assumptions.assumeTrue(LinuxProcFs.isLinux());
    CpuMetrics.procRoot = root.resolve("proc");
    CpuMetrics.sysRoot = root.resolve("sys");
    Files.createDirectories(CpuMetrics.procRoot);
    Files.createDirectories(CpuMetrics.sysRoot.resolve("fs/cgroup"));

    CpuMetrics metrics = new CpuMetrics((OperatingSystemMXBean) null);
    metrics.setReadRefreshEnabled(false);
    metrics.poll();

    assertEquals(-1.0, metrics.getCgroupCpuLimitCores(), DELTA);
  }
}
