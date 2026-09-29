import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * Smoke probe run inside a JVM that has the agent attached with -javaagent. Started by
 * scripts/smoke-test.sh as a single-file source program (works on Java 11+), it verifies that
 * the packaged agent registered its MBeans and that the refresh pipeline is healthy.
 */
public class SmokeProbe {
  private static final String[] REQUIRED = {
      "co.pletor.node:type=CpuMetrics",
      "co.pletor.node:type=MemMetrics",
      "co.pletor.node:type=IoRates",
      "co.pletor.node:type=OsInfoMetrics",
      "co.pletor.node:type=OsRuntimeMetrics",
      "co.pletor.cgroup:type=MemMetrics",
      "co.pletor.proc:type=FdMetrics",
      "co.pletor.agent:type=TelemetryMode",
      "co.pletor.agent:type=Observability",
  };

  public static void main(String[] args) throws Exception {
    MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    List<String> problems = new ArrayList<>();

    for (String name : REQUIRED) {
      if (!server.isRegistered(new ObjectName(name))) {
        problems.add("MBean not registered: " + name);
      }
    }
    if (server.queryNames(new ObjectName("co.pletor.node:type=FsMetrics,*"), null).isEmpty()) {
      problems.add("No FsMetrics MBean registered");
    }
    if (!problems.isEmpty()) {
      fail(problems);
    }

    ObjectName obs = new ObjectName("co.pletor.agent:type=Observability");
    long deadline = System.nanoTime() + 20_000_000_000L;
    while (((Long) server.getAttribute(obs, "ProcessedCount")) < 1L) {
      if (System.nanoTime() > deadline) {
        problems.add("Refresh engine did not complete any refresh within 20 s");
        fail(problems);
      }
      Thread.sleep(100L);
    }

    String mode = String.valueOf(server.getAttribute(obs, "Mode"));
    if (!"NORMAL".equals(mode)) {
      problems.add("Telemetry mode is " + mode + ", expected NORMAL");
    }
    String version = String.valueOf(
        server.getAttribute(new ObjectName("co.pletor.node:type=OsInfoMetrics"), "AgentVersion"));
    if ("unknown".equals(version)) {
      problems.add("AgentVersion is unknown: version.properties missing from the jar");
    }
    long cpus = ((Number) server.getAttribute(
        new ObjectName("co.pletor.node:type=CpuMetrics"), "AvailableProcessors")).longValue();
    if (cpus < 1L) {
      problems.add("CpuMetrics.AvailableProcessors = " + cpus);
    }
    if (System.getProperty("os.name", "").toLowerCase().contains("linux")) {
      long memTotal = ((Number) server.getAttribute(
          new ObjectName("co.pletor.node:type=MemMetrics"), "TotalMemoryBytes")).longValue();
      if (memTotal <= 0L) {
        problems.add("MemMetrics.TotalMemoryBytes = " + memTotal);
      }
    }
    if (!problems.isEmpty()) {
      fail(problems);
    }

    System.out.println("smoke probe OK: version=" + version + " mode=" + mode
        + " processed=" + server.getAttribute(obs, "ProcessedCount")
        + " failingTasks='" + optionalAttribute(server, obs, "FailingTasks") + "'");
  }

  /** Informational attributes may be absent in older agent builds. */
  private static Object optionalAttribute(MBeanServer server, ObjectName name, String attribute) {
    try {
      return server.getAttribute(name, attribute);
    } catch (Exception e) {
      return "n/a";
    }
  }

  private static void fail(List<String> problems) {
    for (String p : problems) {
      System.err.println("SMOKE FAILURE: " + p);
    }
    System.exit(1);
  }
}
