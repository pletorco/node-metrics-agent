package kr.pletor.nodemetrics.metrics;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.OperatingSystemMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CpuProcRecoveryTest {
  @TempDir Path root;

  @AfterEach
  void restoreRoots() {
    CpuMetrics.procRoot = Path.of("/proc");
    CpuMetrics.sysRoot = Path.of("/sys");
    LinuxProcFs.setProcRoot(Path.of("/proc"));
    LinuxProcFs.setSysRoot(Path.of("/sys"));
  }

  private Path setupCpu() throws Exception {
    Assumptions.assumeTrue(LinuxProcFs.isLinux());
    CpuMetrics.procRoot = Files.createDirectories(root.resolve("proc"));
    CpuMetrics.sysRoot = Files.createDirectories(root.resolve("sys"));
    Files.createDirectories(CpuMetrics.procRoot.resolve("self"));
    Files.writeString(CpuMetrics.procRoot.resolve("stat"), "cpu 100 0 50 500 10 0 0 5\n");
    Files.writeString(CpuMetrics.procRoot.resolve("loadavg"), "1.0 2.0 3.0 1/2 100\n");
    return Files.createDirectories(CpuMetrics.sysRoot.resolve("fs/cgroup"));
  }

  @Test
  void missingProcPreservesHostSnapshotButClearsMissingCgroupBeforeRecovery() throws Exception {
    Path base = setupCpu();
    Files.writeString(base.resolve("cgroup.controllers"), "cpu memory");
    Path first = Files.createDirectories(base.resolve("pod/first"));
    Files.writeString(CpuMetrics.procRoot.resolve("self/cgroup"), "0::/pod/first\n");
    Files.writeString(
        first.resolve("cpu.stat"), "usage_usec 1000\nnr_throttled 2\nthrottled_usec 100\n");
    Files.writeString(first.resolve("cpu.max"), "100000 100000\n");
    CpuMetrics cpu = new CpuMetrics((OperatingSystemMXBean) null);
    cpu.setReadRefreshEnabled(false);
    cpu.poll();
    assertEquals(1_000_000L, cpu.getCgroupCpuUsageNanosTotal());
    final Long ticks = cpu.getSystemCpuTotalTicks();

    Files.delete(CpuMetrics.procRoot.resolve("stat"));
    Files.delete(CpuMetrics.procRoot.resolve("loadavg"));
    Files.delete(first.resolve("cpu.stat"));
    assertDoesNotThrow(cpu::poll);
    assertNotNull(cpu.lastRefreshError());
    assertEquals(ticks, cpu.getSystemCpuTotalTicks());
    assertEquals(1.0, cpu.getSystemLoadAverage1m());
    assertNull(cpu.getCgroupCpuUsageNanosTotal());
    assertNull(cpu.getCgroupCpuThrottledTimeNanosTotal());
    assertNull(cpu.getCgroupCpuThrottledPeriodsTotal());
    assertEquals(-1.0, cpu.getCgroupCpuLimitCores());

    Path moved = Files.createDirectories(base.resolve("pod/moved"));
    Files.writeString(CpuMetrics.procRoot.resolve("self/cgroup"), "malformed\n0::/pod/moved\n");
    Files.writeString(
        moved.resolve("cpu.stat"), "usage_usec 3000\nnr_throttled 3\nthrottled_usec 300\n");
    Files.writeString(moved.resolve("cpu.max"), "50000 100000\n");
    Files.writeString(CpuMetrics.procRoot.resolve("stat"), "cpu 200 0 50 500 20 0 0 10\n");
    Files.writeString(CpuMetrics.procRoot.resolve("loadavg"), "4.0 5.0 6.0 1/2 100\n");
    cpu.poll();
    assertNull(cpu.lastRefreshError());
    assertEquals(3_000_000L, cpu.getCgroupCpuUsageNanosTotal());
    assertEquals(0L, cpu.getCgroupCpuThrottledCount());
    assertEquals(-1.0, cpu.getCgroupCpuThrottledRatio());
    assertEquals(0.5, cpu.getCgroupCpuLimitCores());
    assertEquals(4.0, cpu.getSystemLoadAverage1m());
    assertTrue(cpu.getSystemCpuTotalTicks() > ticks);
  }

  @Test
  void cgroupCounterResetAndMalformedLinesDoNotProduceNegativeRates() throws Exception {
    Path base = setupCpu();
    Files.createFile(base.resolve("cgroup.controllers"));
    Files.writeString(CpuMetrics.procRoot.resolve("self/cgroup"), "0::/\n");
    Path stat = base.resolve("cpu.stat");
    CpuMetrics cpu = new CpuMetrics((OperatingSystemMXBean) null);
    cpu.setReadRefreshEnabled(false);
    Files.writeString(stat, "usage_usec 1000\nnr_throttled 10\nthrottled_usec 200\n");
    cpu.poll();
    Files.writeString(
        stat,
        "\nunknown 20\nusage_usec nope\nnr_throttled -1\ninvalid row extra\n"
            + "usage_usec 5\nnr_throttled 1\nthrottled_usec 2\n");
    cpu.poll();
    assertEquals(5_000L, cpu.getCgroupCpuUsageNanosTotal());
    assertEquals(0L, cpu.getCgroupCpuThrottledCount());
    assertEquals(0.0, cpu.getCgroupCpuThrottledRatio());
    Files.writeString(stat, "usage_usec 15\nnr_throttled 3\nthrottled_usec 12\n");
    cpu.poll();
    assertEquals(2L, cpu.getCgroupCpuThrottledCount());
    assertEquals(0.5, cpu.getCgroupCpuThrottledRatio());
  }

  @Test
  void v1CpuAccountingFallbackSurvivesBadDataAndRecoversThroughPublicSnapshots() throws Exception {
    Path base = setupCpu();
    Path cpuDir = Files.createDirectories(base.resolve("cpu/pod"));
    Path acct = Files.createDirectories(base.resolve("cpuacct/pod"));
    Files.writeString(CpuMetrics.procRoot.resolve("self/cgroup"), "bad line\n2:cpu,cpuacct:/pod\n");
    Files.writeString(cpuDir.resolve("cpu.stat"), "nr_throttled 1\nthrottled_time 100\n");
    Files.writeString(acct.resolve("cpuacct.usage"), "invalid\n");
    CpuMetrics cpu = new CpuMetrics((OperatingSystemMXBean) null);
    cpu.setReadRefreshEnabled(false);
    cpu.poll();
    assertNull(cpu.getCgroupCpuUsageNanosTotal());
    assertEquals(1L, cpu.getCgroupCpuThrottledPeriodsTotal());
    Files.writeString(acct.resolve("cpuacct.usage"), "12345\n");
    cpu.poll();
    assertEquals(12345L, cpu.getCgroupCpuUsageNanosTotal());
    assertEquals(-1.0, cpu.getCgroupCpuThrottledRatio(), "Wait for a valid usage baseline");
  }

  @Test
  void v2MissingMembershipOrChildStatsNeverUseRootCounters() throws Exception {
    Path base = setupCpu();
    Files.createFile(base.resolve("cgroup.controllers"));
    Files.writeString(base.resolve("cpu.stat"), "usage_usec 12345\nnr_throttled 7\n");
    Files.writeString(base.resolve("cpu.max"), "100000 100000\n");
    final Path membership = CpuMetrics.procRoot.resolve("self/cgroup");
    CpuMetrics noMembership = new CpuMetrics((OperatingSystemMXBean) null);
    noMembership.setReadRefreshEnabled(false);
    noMembership.poll();
    assertNull(noMembership.getCgroupCpuUsageNanosTotal());
    for (String value :
        new String[] {"0::/workload\n", "0::\n", "0::/workload/..\n", "0:cpu:/\n"}) {
      Files.writeString(membership, value);
      CpuMetrics cpu = new CpuMetrics((OperatingSystemMXBean) null);
      cpu.setReadRefreshEnabled(false);
      cpu.poll();
      assertNull(cpu.getCgroupCpuUsageNanosTotal(), value);
      assertNull(cpu.getCgroupCpuThrottledPeriodsTotal(), value);
      assertEquals(-1.0, cpu.getCgroupCpuLimitCores(), value);
    }
  }

  @Test
  void v1UsesScopedCombinedMountInsteadOfRootAccounting() throws Exception {
    Path base = setupCpu();
    Path group = Files.createDirectories(base.resolve("cpu,cpuacct/workload"));
    Path acctRoot = Files.createDirectories(base.resolve("cpuacct"));
    Files.writeString(CpuMetrics.procRoot.resolve("self/cgroup"), "3:cpu,cpuacct:/workload\n");
    Files.writeString(group.resolve("cpu.stat"), "nr_throttled 2\nthrottled_time 100\n");
    Files.writeString(acctRoot.resolve("cpuacct.usage"), "999999\n");
    CpuMetrics withoutAccounting = new CpuMetrics((OperatingSystemMXBean) null);
    withoutAccounting.setReadRefreshEnabled(false);
    withoutAccounting.poll();
    assertNull(withoutAccounting.getCgroupCpuUsageNanosTotal());
    assertEquals(2L, withoutAccounting.getCgroupCpuThrottledPeriodsTotal());

    Files.writeString(group.resolve("cpuacct.usage"), "12345\n");
    CpuMetrics withAccounting = new CpuMetrics((OperatingSystemMXBean) null);
    withAccounting.setReadRefreshEnabled(false);
    withAccounting.poll();
    assertEquals(12345L, withAccounting.getCgroupCpuUsageNanosTotal());
  }

  @Test
  void cgroupDiscoveryRejectsMalformedMembershipAndRecoversAtNextLookup() throws Exception {
    Assumptions.assumeTrue(LinuxProcFs.isLinux());
    Path proc = Files.createDirectories(root.resolve("proc"));
    Path sys = Files.createDirectories(root.resolve("sys"));
    LinuxProcFs.setProcRoot(proc);
    LinuxProcFs.setSysRoot(sys);
    Files.createDirectories(proc.resolve("self"));
    Path base = Files.createDirectories(sys.resolve("fs/cgroup"));
    Path membership = proc.resolve("self/cgroup");
    Files.writeString(base.resolve("cgroup.controllers"), "cpu memory\n");
    Files.writeString(membership, "malformed\n7:cpu:/irrelevant\n");
    assertNull(LinuxProcFs.detectCgroup().resolved, "Malformed membership cannot select the root");
    Files.writeString(membership, "malformed\n0::/pod/restored\n");
    assertEquals(base.resolve("pod/restored"), LinuxProcFs.detectCgroup().resolved);
    Files.delete(base.resolve("cgroup.controllers"));
    Files.writeString(membership, "2:cpu:/irrelevant\n5:memory:/legacy\n");
    assertEquals(base.resolve("memory/legacy"), LinuxProcFs.detectCgroup().resolved);
    Files.delete(membership);
    assertEquals("none", LinuxProcFs.detectCgroup().version);
  }

  @Test
  void malformedQuotaAndZeroPeriodCanRecoverWithoutRecreatingTheCollector() throws Exception {
    Path group = Files.createDirectories(root.resolve("group"));
    Path limit = group.resolve("cpu.max");
    for (String invalid : new String[] {"100000 0", "100000 -1", "bad 100000", "100000 bad"}) {
      Files.writeString(limit, invalid);
      assertEquals(-1.0, CgroupCpuLimit.effectiveCores(group, group));
    }
    Files.writeString(limit, "25000 100000");
    assertEquals(0.25, CgroupCpuLimit.effectiveCores(group, group));
    Files.delete(limit);
    assertEquals(-1.0, CgroupCpuLimit.effectiveCores(group, group));
  }
}
