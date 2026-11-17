package kr.pletor.nodemetrics.metrics;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.management.ObjectName;

public final class MigrationProbe {
  private MigrationProbe() {}

  public static void main(String[] args) {
    try {
      run(Path.of(args[0]), args[1].equals("v1"), args.length > 2 ? Integer.parseInt(args[2]) : 0);
      System.out.println(
          "MIGRATION SAFE: current membership, ancestor limits, counters, recovery; host completed");
    } catch (Throwable failure) {
      System.out.println("MIGRATION FAILURE: " + failure.getMessage());
      System.exit(1);
    }
  }

  private static void run(Path root, boolean legacy, int port) throws Exception {
    MigrationFixture fixture = new MigrationFixture(root, legacy);
    require(fixture.memory.getMemoryLimitBytes() == 1000, "initial memory mismatch");
    require(fixture.pids.getPidsLimit() == 10, "initial PID mismatch");
    if (port != 0) {
      register(fixture.memory, CgroupMemMetricsMBean.class, "MemMetrics");
      register(fixture.pids, PidsMetricsMBean.class, "PidsMetrics");
      register(fixture.psi, PressureMetricsMBean.class, "PressureMetrics");
      scrape(port, root.resolve("before.txt"));
    }
    fixture.move(2);
    fixture.expire();
    fixture.poll();
    require(
        fixture.memory.getMemoryLimitBytes() == 2000
            && fixture.pids.getPidsLimit() == 20
            && (legacy || fixture.psi.getMemorySomeAvg10() == 2),
        "stale binding after membership change");
    require(fixture.memory.getMemoryUsageBytes() == 200, "wrong current memory");
    require(fixture.pids.getPidsCurrent() == 6, "wrong ancestor PID count");
    require(
        fixture.memory.getMemoryOomKillTotal() == 2L,
        "old memory counter was stitched into new group");
    if (!legacy) {
      require(fixture.psi.getMemorySomeTotalMicros() == 200L, "wrong PSI counter");
    }
    if (port != 0) {
      scrape(port, root.resolve("after.txt"));
    }
    Files.delete(fixture.membership);
    fixture.expire();
    fixture.poll();
    require(
        fixture.memory.getMemoryUsageBytes() == -1
            && fixture.pids.getPidsCurrent() == -1
            && fixture.psi.getMemorySomeTotalMicros() == null,
        "missing membership used an old or root group");
    if (port != 0) {
      scrape(port, root.resolve("unavailable.txt"));
    }
    fixture.move(1);
    fixture.expire();
    fixture.poll();
    require(fixture.memory.getMemoryUsageBytes() == 100, "membership did not recover");
  }

  private static void register(Object bean, Class<?> type, String name) throws Exception {
    // Exercise the real packaged registration wrapper, including omission of absent counters.
    var wrapper =
        Class.forName("kr.pletor.nodemetrics.agent.AbsentValueHider")
            .getDeclaredConstructor(Object.class, Class.class);
    wrapper.setAccessible(true);
    ManagementFactory.getPlatformMBeanServer()
        .registerMBean(
            wrapper.newInstance(bean, type), new ObjectName("kr.pletor.cgroup:type=" + name));
  }

  private static void scrape(int port, Path output) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (true) {
      var connection =
          (java.net.HttpURLConnection)
              new java.net.URL("http://127.0.0.1:" + port + "/metrics").openConnection();
      connection.setConnectTimeout(1000);
      connection.setReadTimeout(3000);
      try {
        require(connection.getResponseCode() == 200, "exporter scrape failed");
        try (var input = connection.getInputStream()) {
          String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
          if (text.contains("pletor_cgroup_memmetrics_memoryusagebytes")) {
            Files.writeString(output, text);
            return;
          }
        }
      } finally {
        connection.disconnect();
      }
      require(System.nanoTime() < deadline, "exporter series did not appear");
      Thread.sleep(50L);
    }
  }

  private static void require(boolean value, String message) {
    if (!value) {
      throw new AssertionError(message);
    }
  }
}
