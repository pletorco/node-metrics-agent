package kr.pletor.nodemetrics.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CgroupMigrationTest {
  @TempDir Path root;
  private Path mount;
  private Path membership;
  private final AtomicLong clock = new AtomicLong();

  @BeforeEach
  void setup() throws Exception {
    Assumptions.assumeTrue(LinuxProcFs.isLinux());
    LinuxProcFs.setProcRoot(root.resolve("proc"));
    LinuxProcFs.setSysRoot(root.resolve("sys"));
    membership = root.resolve("proc/self/cgroup");
    Files.createDirectories(membership.getParent());
    mount = Files.createDirectories(root.resolve("sys/fs/cgroup"));
  }

  @AfterEach
  void restore() {
    LinuxProcFs.setProcRoot(Path.of("/proc"));
    LinuxProcFs.setSysRoot(Path.of("/sys"));
  }

  private void v2() throws Exception {
    Files.writeString(mount.resolve("cgroup.controllers"), "memory pids cpu\n");
  }

  private void group(Path dir, boolean legacy, int value) throws Exception {
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(legacy ? "memory.limit_in_bytes" : "memory.max"), "9000");
    Files.writeString(
        dir.resolve(legacy ? "memory.usage_in_bytes" : "memory.current"), "" + value * 100);
    Files.writeString(dir.resolve("memory.stat"), "inactive_file 10\ntotal_inactive_file 10\n");
    Files.writeString(
        dir.resolve("memory.events"), "oom_kill " + value + "\nmax " + value + "\nhigh 0\n");
    Files.writeString(dir.resolve("memory.oom_control"), "oom_kill " + value + "\n");
    Files.writeString(dir.resolve("pids.current"), "" + value);
    Files.writeString(dir.resolve("pids.max"), "90");
    Files.writeString(
        dir.resolve("memory.pressure"),
        "some avg10=" + value + " avg60=0 avg300=0 total=" + value * 100 + "\n");
  }

  private final class Beans {
    final CgroupMemMetrics memory =
        new CgroupMemMetrics(new CgroupBinding(LinuxProcFs::detectCgroup, clock::get));
    final PidsMetrics pids =
        new PidsMetrics(new CgroupBinding(() -> LinuxProcFs.detectCgroup("pids"), clock::get));
    final PressureMetrics psi =
        new PressureMetrics(new CgroupBinding(LinuxProcFs::detectCgroup, clock::get));

    Beans() {
      for (RefreshManagedMetric metric : List.of(memory, pids, psi)) {
        metric.setReadRefreshEnabled(false);
      }
    }

    void poll() {
      memory.poll();
      pids.poll();
      psi.poll();
    }

    void expire() {
      clock.addAndGet(CgroupBinding.TTL_NANOS);
      poll();
    }

    void unavailable() {
      assertEquals(-1, memory.getMemoryUsageBytes());
      assertEquals(-1, pids.getPidsCurrent());
      assertEquals(-1, psi.getMemorySomeAvg10());
      assertNull(memory.getMemoryOomKillTotal());
      assertNull(psi.getMemorySomeTotalMicros());
    }
  }

  @Test
  void v2MigrationRebindsAllCollectorsAndTheirAncestorsWhileOldGroupRemainsReadable()
      throws Exception {
    v2();
    Path a = mount.resolve("a/leaf");
    Path b = mount.resolve("b/leaf");
    group(a, false, 1);
    group(b, false, 2);
    Files.writeString(a.getParent().resolve("memory.max"), "1000");
    Files.writeString(b.getParent().resolve("memory.max"), "2000");
    Files.writeString(a.getParent().resolve("pids.max"), "10");
    Files.writeString(a.getParent().resolve("pids.current"), "5");
    Files.writeString(b.getParent().resolve("pids.max"), "20");
    Files.writeString(b.getParent().resolve("pids.current"), "6");
    Files.writeString(mount.getParent().resolve("memory.max"), "1");
    Files.writeString(membership, "0::/a/leaf\n");
    Beans beans = new Beans();
    beans.poll();
    assertEquals(1000, beans.memory.getMemoryLimitBytes());
    assertEquals(10, beans.pids.getPidsLimit());
    Files.writeString(membership, "0::/b/leaf\n");
    beans.poll();
    assertEquals(
        "/a/leaf", beans.memory.getCgroupPath(), "Bindings have an explicit sixty-second TTL");
    beans.expire();
    assertEquals(2000, beans.memory.getMemoryLimitBytes());
    assertEquals(200, beans.memory.getMemoryUsageBytes());
    assertEquals(20, beans.pids.getPidsLimit());
    assertEquals(6, beans.pids.getPidsCurrent());
    assertEquals(2, beans.psi.getMemorySomeAvg10());
    assertEquals(2L, beans.memory.getMemoryOomKillTotal());
    assertEquals(200L, beans.psi.getMemorySomeTotalMicros());
    assertEquals("/b/leaf", beans.memory.getCgroupPath());
    assertEquals("/b/leaf", beans.pids.getCgroupPath());
    assertEquals("/b/leaf", beans.psi.getCgroupPath());
    assertTrue(Files.isRegularFile(a.resolve("memory.current")));
  }

  @Test
  void v1UsesIndependentMemoryAndPidsMembershipsAfterMigration() throws Exception {
    for (String suffix : List.of("a", "b")) {
      int value = suffix.equals("a") ? 1 : 2;
      Path memory = mount.resolve("memory/m" + suffix + "/leaf");
      Path pids = mount.resolve("pids/p" + suffix + "/leaf");
      group(memory, true, value);
      group(pids, true, value);
      Files.writeString(memory.getParent().resolve("memory.limit_in_bytes"), "" + value * 1000);
      Files.writeString(pids.getParent().resolve("pids.max"), "" + value * 10);
      Files.writeString(pids.getParent().resolve("pids.current"), "" + value * 3);
    }
    Files.writeString(membership, "4:memory:/ma/leaf\n5:pids:/pa/leaf\n");
    Beans beans = new Beans();
    beans.poll();
    assertEquals(1000, beans.memory.getMemoryLimitBytes());
    assertEquals(10, beans.pids.getPidsLimit());
    Files.writeString(membership, "4:memory:/mb/leaf\n5:pids:/pb/leaf\n");
    beans.expire();
    assertEquals(2000, beans.memory.getMemoryLimitBytes());
    assertEquals(20, beans.pids.getPidsLimit());
    assertEquals(6, beans.pids.getPidsCurrent());
    assertEquals("/mb/leaf", beans.memory.getCgroupPath());
    assertEquals("/pb/leaf", beans.pids.getCgroupPath());
    assertEquals("v1", beans.pids.getCgroupVersion());
    assertNull(beans.psi.getMemorySomeTotalMicros(), "v1 has no cgroup PSI");
    group(mount.resolve("memory"), true, 9);
    group(mount.resolve("pids"), true, 9);
    Files.writeString(membership, "4:memory:\n5:pids:relative\n");
    beans.expire();
    beans.unavailable();
  }

  @Test
  void absentDeletedMalformedUnreadableAndOversizedMembershipNeverSelectsTheRoot()
      throws Exception {
    v2();
    group(mount, false, 9); // Root is readable and must never substitute for missing membership.
    group(mount.resolve("a"), false, 1);
    Beans beans = new Beans();
    beans.poll();
    beans.unavailable();
    Files.writeString(membership, "0::/a\n");
    beans.expire();
    assertEquals(100, beans.memory.getMemoryUsageBytes());
    for (String rejected :
        List.of(
            "malformed\n",
            "0::relative\n",
            "0::/../../elsewhere\n",
            "x".repeat(CollectorInput.SMALL.lineBytes + 1))) {
      Files.writeString(membership, rejected);
      beans.expire();
      beans.unavailable();
    }
    Files.delete(membership);
    Files.createDirectory(membership); // Deterministic read failure even under a privileged runner.
    beans.expire();
    beans.unavailable();
    Files.delete(membership);
    Files.writeString(membership, "0::/a\n");
    beans.expire();
    assertEquals(100, beans.memory.getMemoryUsageBytes());
    Files.delete(mount.resolve("a/memory.current"));
    Files.delete(mount.resolve("a/pids.current"));
    Files.delete(mount.resolve("a/memory.pressure"));
    beans.poll();
    assertEquals(-1, beans.memory.getMemoryUsageBytes());
    assertEquals(-1, beans.pids.getPidsCurrent());
    assertNull(beans.psi.getMemorySomeTotalMicros());
    assertEquals(
        1L, beans.memory.getMemoryOomKillTotal(), "An independent readable file remains valid");
    Files.writeString(membership, "0::/\n");
    beans.expire();
    assertEquals(900, beans.memory.getMemoryUsageBytes(), "Explicit root membership is valid");
    assertEquals("/", beans.memory.getCgroupPath());
  }

  @Test
  void repeatedPollsAndGetterReadsDoNotRepeatMembershipIoBeforeTtl() {
    AtomicInteger checks = new AtomicInteger();
    CgroupBinding binding =
        new CgroupBinding(
            () -> {
              checks.incrementAndGet();
              return new LinuxProcFs.CgroupInfo();
            },
            clock::get);
    CgroupMemMetrics memory = new CgroupMemMetrics(binding);
    memory.setReadRefreshEnabled(false);
    for (int i = 0; i < 100; i++) {
      memory.getCgroupPath();
      memory.getMemoryUsageBytes();
    }
    assertEquals(0, checks.get(), "Construction and managed getters perform no membership I/O");
    for (int i = 0; i < 100; i++) {
      memory.poll();
    }
    assertEquals(1, checks.get());
    clock.addAndGet(CgroupBinding.TTL_NANOS);
    memory.poll();
    assertEquals(2, checks.get());
  }

  @Test
  void concurrentReadersObserveCompleteSnapshotsAcrossRepeatedMigrations() throws Exception {
    v2();
    group(mount.resolve("a"), false, 1);
    group(mount.resolve("b"), false, 2);
    Files.writeString(membership, "0::/a\n");
    Beans beans = new Beans();
    beans.poll();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread writer =
        new Thread(
            () -> {
              try {
                for (int i = 0; i < 50; i++) {
                  Files.writeString(membership, i % 2 == 0 ? "0::/b\n" : "0::/a\n");
                  beans.expire();
                }
              } catch (Throwable error) {
                failure.set(error);
              }
            });
    writer.setDaemon(true);
    writer.start();
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      do {
        for (Object bean : List.of(beans.memory, beans.pids, beans.psi)) {
          var field = bean.getClass().getDeclaredField("snapshot");
          field.setAccessible(true);
          Object snapshot = field.get(bean);
          var path = snapshot.getClass().getDeclaredField("path");
          path.setAccessible(true);
          String group = (String) path.get(snapshot);
          if (group.isEmpty()) {
            continue;
          }
          long expected = group.equals("/a") ? 1 : 2;
          if (bean == beans.memory) {
            var values = snapshot.getClass().getDeclaredField("values");
            values.setAccessible(true);
            assertEquals(expected * 100, ((long[]) values.get(snapshot))[1]);
          } else if (bean == beans.pids) {
            var current = snapshot.getClass().getDeclaredField("current");
            current.setAccessible(true);
            assertEquals(expected, current.getLong(snapshot));
          } else {
            var avg = snapshot.getClass().getDeclaredField("avg10");
            avg.setAccessible(true);
            assertEquals((double) expected, ((double[]) avg.get(snapshot))[2]);
          }
        }
        assertTrue(System.nanoTime() < deadline, "Writer must make bounded progress");
      } while (writer.isAlive());
      assertNull(failure.get());
    } finally {
      writer.join(5_000L);
      assertFalse(writer.isAlive());
    }
  }

  @Test
  void detectorFailureIsCachedAndConcurrentStalledResolverNeverBlocksAnotherCaller()
      throws Exception {
    AtomicInteger checks = new AtomicInteger();
    CgroupBinding broken =
        new CgroupBinding(
            () -> {
              checks.incrementAndGet();
              throw new LinkageError("synthetic resolver failure");
            },
            clock::get);
    assertThrows(LinkageError.class, broken::current);
    assertEquals("none", broken.current().version);
    assertEquals(1, checks.get());
    clock.addAndGet(CgroupBinding.TTL_NANOS);
    assertThrows(LinkageError.class, broken::current);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CgroupBinding stalled =
        new CgroupBinding(
            () -> {
              entered.countDown();
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return new LinuxProcFs.CgroupInfo();
            },
            clock::get);
    Thread resolver = new Thread(stalled::current);
    resolver.setDaemon(true);
    resolver.start();
    try {
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      assertEquals("none", stalled.current().version);
    } finally {
      release.countDown();
      resolver.join(3_000L);
      assertFalse(resolver.isAlive());
    }
  }
}
