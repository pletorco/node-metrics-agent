package co.pletor.nodemetrics.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Process (PID) count and limit of the JVM's cgroup. */
class PidsMetricsTest {

  @TempDir Path root;

  @BeforeEach
  void linuxOnly() {
    Assumptions.assumeTrue(LinuxProcFs.isLinux(), "reads the Linux code path");
  }

  private static PidsMetrics metrics(String version, Path resolved, Path baseDir) throws Exception {
    PidsMetrics metrics = new PidsMetrics();
    Field cgField = PidsMetrics.class.getDeclaredField("cg");
    cgField.setAccessible(true);
    Object cg = cgField.get(metrics);
    set(cg, "version", version);
    set(cg, "resolved", resolved);
    set(cg, "path", "/test");
    set(cg, "baseDir", baseDir);
    return metrics;
  }

  private static void set(Object target, String field, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(field);
    f.setAccessible(true);
    f.set(target, value);
  }

  private static Path level(Path dir, String current, String max) throws IOException {
    Files.createDirectories(dir);
    if (current != null) {
      Files.writeString(dir.resolve("pids.current"), current + "\n");
    }
    if (max != null) {
      Files.writeString(dir.resolve("pids.max"), max + "\n");
    }
    return dir;
  }

  @Test
  void v2WithoutLimitReportsCountAndUnlimited() throws Exception {
    Path cg = level(root.resolve("cg"), "120", "max");
    PidsMetrics m = metrics("v2", cg, null);
    m.poll();

    assertEquals(120L, m.getPidsCurrent());
    assertEquals(-1L, m.getPidsLimit());
    assertNull(m.lastRefreshError());
  }

  @Test
  void v2OwnLimit() throws Exception {
    Path cg = level(root.resolve("cg"), "120", "200");
    PidsMetrics m = metrics("v2", cg, null);
    m.poll();

    assertEquals(120L, m.getPidsCurrent());
    assertEquals(200L, m.getPidsLimit());
  }

  @Test
  void v2PodLimitAppliesToTheContainerAndItsCountIsReported() throws Exception {
    Path base = level(root.resolve("fs"), "900", "max");
    Path pod = level(base.resolve("pod"), "70", "100");
    Path container = level(pod.resolve("ctr"), "30", "max");
    PidsMetrics m = metrics("v2", container, base);
    m.poll();

    assertEquals(100L, m.getPidsLimit(), "the pod limit binds the container");
    assertEquals(70L, m.getPidsCurrent(), "the count of the level that holds the limit");
  }

  @Test
  void v2TighterOwnLimitWinsOverLooserAncestor() throws Exception {
    Path base = level(root.resolve("fs"), "900", "max");
    Path pod = level(base.resolve("pod"), "70", "100");
    Path container = level(pod.resolve("ctr"), "30", "50");
    PidsMetrics m = metrics("v2", container, base);
    m.poll();

    assertEquals(50L, m.getPidsLimit());
    assertEquals(30L, m.getPidsCurrent());
  }

  @Test
  void v2MissingControllerIsUnavailableWithoutFailure() throws Exception {
    Path cg = Files.createDirectories(root.resolve("cg"));
    PidsMetrics m = metrics("v2", cg, null);
    m.poll();

    assertEquals(-1L, m.getPidsCurrent());
    assertEquals(-1L, m.getPidsLimit());
    assertNull(m.lastRefreshError());
  }

  @Test
  void v2MalformedFilesAreUnavailable() throws Exception {
    Path cg = level(root.resolve("cg"), "garbage", "200");
    PidsMetrics m = metrics("v2", cg, null);
    m.poll();

    assertEquals(-1L, m.getPidsCurrent());
    assertEquals(-1L, m.getPidsLimit());
  }

  @Test
  void v1ReadsThePidsHierarchyNextToTheMemoryOne() throws Exception {
    Path memoryRoot = Files.createDirectories(root.resolve("cgroup/memory"));
    Path memoryCg = Files.createDirectories(memoryRoot.resolve("kube/ctr"));
    Path pidsRoot = level(root.resolve("cgroup/pids"), "900", "max");
    level(pidsRoot.resolve("kube"), "70", "100");
    level(pidsRoot.resolve("kube/ctr"), "30", "max");
    PidsMetrics m = metrics("v1", memoryCg, memoryRoot);
    m.poll();

    assertEquals(100L, m.getPidsLimit());
    assertEquals(70L, m.getPidsCurrent());
  }

  @Test
  void v1WithoutPidsHierarchyIsUnavailable() throws Exception {
    Path memoryRoot = Files.createDirectories(root.resolve("cgroup/memory"));
    Path memoryCg = Files.createDirectories(memoryRoot.resolve("kube/ctr"));
    PidsMetrics m = metrics("v1", memoryCg, memoryRoot);
    m.poll();

    assertEquals(-1L, m.getPidsCurrent());
    assertEquals(-1L, m.getPidsLimit());
    assertNull(m.lastRefreshError());
  }

  @Test
  void noCgroupIsUnavailable() throws Exception {
    PidsMetrics m = metrics("none", null, null);
    m.poll();

    assertEquals(-1L, m.getPidsCurrent());
    assertEquals(-1L, m.getPidsLimit());
  }
}
