package kr.pletor.nodemetrics.agent;

import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.security.Permission;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/** JVM-wide fault injection is confined to this disposable host, before the real agent starts. */
@SuppressWarnings("removal")
public final class EngineStartupProbe {
  private static final AtomicBoolean DENIED = new AtomicBoolean();

  private EngineStartupProbe() {}

  public static void premain(String target, Instrumentation instrumentation) {
    System.setSecurityManager(
        new SecurityManager() {
          @Override
          public void checkPermission(Permission permission) {}

          @Override
          public void checkPermission(Permission permission, Object context) {}

          @Override
          public void checkAccess(Thread thread) {
            if (thread.getName().equals(target)) {
              DENIED.set(true);
              throw new SecurityException("synthetic agent thread creation denial");
            }
          }
        });
  }

  public static void main(String[] args) {
    try {
      run();
      System.out.println("STARTUP SAFE: disabled, visible, no retry; host main completed");
    } catch (Throwable error) {
      System.out.println("STARTUP FAILURE: " + error.getMessage());
      System.exit(1);
    } finally {
      System.setSecurityManager(null);
    }
  }

  private static void run() throws Exception {
    MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    ObjectName obs = new ObjectName("kr.pletor.agent:type=Observability");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!DENIED.get() || !server.isRegistered(obs)) {
      require(
          System.nanoTime() < deadline,
          "startup probe timed out: denied="
              + DENIED.get()
              + ", registered="
              + server.isRegistered(obs));
      Thread.sleep(10L);
    }
    System.setSecurityManager(null);
    boolean hasState = false;
    for (var attribute : server.getMBeanInfo(obs).getAttributes()) {
      hasState |= attribute.getName().equals("RefreshEngineState");
    }
    if (!hasState) {
      // Baseline's first-worker fault leaves a live dispatcher with no workers and NORMAL mode.
      Thread.sleep(100L);
      require(server.getAttribute(obs, "Mode").equals("NORMAL"), "baseline mode was not NORMAL");
      require(
          ((Number) server.getAttribute(obs, "ProcessedCount")).longValue() == 0L,
          "baseline was not idle");
      throw new AssertionError("baseline NORMAL while startup failed");
    }
    while (!server.getAttribute(obs, "RefreshEngineState").equals("FAILED")) {
      require(System.nanoTime() < deadline, "failure state was not published");
      Thread.sleep(10L);
    }
    require(server.getAttribute(obs, "Mode").equals("BYPASS"), "false healthy mode");
    require(
        server
            .getAttribute(new ObjectName("kr.pletor.agent:type=TelemetryMode"), "Mode")
            .equals("BYPASS"),
        "telemetry mode disagrees with lifecycle");
    require(
        ((Number) server.getAttribute(obs, "RefreshEngineRunning")).intValue() == 0,
        "engine reports running");
    require(
        ((Number) server.getAttribute(obs, "RefreshWorkerCount")).intValue() == 0,
        "engine reports active workers");
    require(
        ((Number) server.getAttribute(obs, "RefreshEngineStartFailureCount")).longValue() == 1,
        "failure count mismatch");
    var field = MetricsAgent.class.getDeclaredField("refreshEngine");
    field.setAccessible(true);
    MetricsRefreshEngine engine = (MetricsRefreshEngine) field.get(null);
    for (int i = 0; i < 20; i++) {
      engine.start();
      AgentLauncher.premain(null, null);
      MetricsAgent.premain(null, null);
    }
    require(field.get(null) == engine, "failed engine was replaced");
    require(
        ((Number) server.getAttribute(obs, "ProcessedCount")).longValue() == 0L,
        "a collector ran during partial startup");
    require(engine.queueSize() == 0, "stranded queued tasks");
    while (Thread.getAllStackTraces().keySet().stream()
        .anyMatch(thread -> thread.getName().startsWith("node-metrics-refresh-"))) {
      require(System.nanoTime() < deadline, "orphan refresh thread");
      Thread.sleep(10L);
    }
    // Test only: accelerate the fixed production grace so the subprocess checks aging quickly.
    engine.setStartupGraceMs(0L);
    deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (((Number) server.getAttribute(obs, "MaxTaskStalenessMs")).longValue() == 0L) {
      require(System.nanoTime() < deadline, "never-polled tasks report no staleness");
      Thread.sleep(10L);
    }
    require(engine.startFailureCount() == 1L, "entry retry attempted new threads");
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
