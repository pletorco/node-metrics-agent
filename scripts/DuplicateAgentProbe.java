package kr.pletor.nodemetrics.agent;

import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.management.MBeanServer;
import javax.management.MBeanServerDelegate;
import javax.management.MBeanServerNotification;
import javax.management.ObjectName;
import javax.management.StandardMBean;

/** Separate disposable host for duplicate-entry, ownership and partial-startup regressions. */
public final class DuplicateAgentProbe {
  private static final AtomicInteger HOST_ERRORS = new AtomicInteger();
  private static final AtomicInteger HOST_UNREGISTRATIONS = new AtomicInteger();
  private static final String CPU = "kr.pletor.node:type=CpuMetrics";
  private static final String OBS = "kr.pletor.agent:type=Observability";

  private DuplicateAgentProbe() {}

  public interface HostMarkerMBean {
    int getMarker();
  }

  public static final class HostMarker implements HostMarkerMBean {
    @Override
    public int getMarker() {
      return 42;
    }
  }

  /** Host-owned registration deliberately precedes the candidate's premain. */
  public static void premain(String args, Instrumentation instrumentation) throws Exception {
    MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    ObjectName cpu = new ObjectName(CPU);
    server.registerMBean(new StandardMBean(new HostMarker(), HostMarkerMBean.class), cpu);
    server.addNotificationListener(
        MBeanServerDelegate.DELEGATE_NAME,
        (notification, handback) -> {
          if (notification instanceof MBeanServerNotification) {
            MBeanServerNotification event = (MBeanServerNotification) notification;
            if (cpu.equals(event.getMBeanName())
                && MBeanServerNotification.UNREGISTRATION_NOTIFICATION.equals(event.getType())) {
              HOST_UNREGISTRATIONS.incrementAndGet();
            }
          }
        },
        null,
        null);
  }

  public static void main(String[] args) throws Exception {
    Thread.setDefaultUncaughtExceptionHandler((thread, error) -> HOST_ERRORS.incrementAndGet());
    try {
      run(args[0], Path.of(args[1]), Path.of(args[2]));
      require(HOST_ERRORS.get() == 0, "host error handler invoked");
      System.out.println("DUPLICATE SAFE: " + args[0]);
    } catch (Throwable failure) {
      System.out.println("DUPLICATE FAILURE: " + failure);
      System.exit(1);
    }
  }

  private static void run(String mode, Path first, Path second) throws Exception {
    if (mode.equals("interrupted")) {
      // No platform server exists yet. Abort the first direct initialization at discovery.
      Thread.currentThread().interrupt();
      MetricsAgent.premain(first.toString(), null);
      require(Thread.interrupted(), "interrupt was not preserved");
      ManagementFactory.getPlatformMBeanServer();
      for (int i = 0; i < 8; i++) {
        MetricsAgent.premain(second.toString(), null);
      }
      require(get("refreshEngine") == null, "aborted initialization was retried");
      require(countThreads("node-metrics-config-watcher") == 0, "watcher after aborted start");
      require(((Config) get("current")).refreshIntervalSeconds == 1, "arguments replaced on retry");
      return;
    }

    if (mode.equals("waiting")) {
      // Actual repeated -javaagent options must create only one waiter before host JMX starts.
      await(() -> countThreads("node-metrics-agent-init") > 0, "initializer did not start");
      concurrent(false, first, second, false);
      require(countThreads("node-metrics-agent-init") == 1, "duplicate initializer waiters");
    }
    MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    if (mode.startsWith("concurrent")) {
      concurrent(mode.equals("concurrent-direct"), first, second, mode.equals("concurrent-args"));
    }
    ObjectName obs = new ObjectName(OBS);
    await(
        () -> {
          try {
            return server.isRegistered(obs)
                && ((Number) server.getAttribute(obs, "ProcessedCount")).longValue() >= 15
                && countThreads("node-metrics-config-watcher") > 0;
          } catch (Exception e) {
            return false;
          }
        },
        "refresh pipeline did not start");

    Object engine = get("refreshEngine");
    Object cpu = get("cpuBean");
    int interval = ((Config) get("current")).refreshIntervalSeconds;
    require(
        interval == 1 || (mode.equals("concurrent-args") && interval == 9), "wrong startup args");
    checkPipeline();

    if (mode.equals("repeat")) {
      for (int i = 0; i < 8; i++) {
        AgentLauncher.premain(second.toString(), null);
        MetricsAgent.premain(second.toString(), null);
      }
      concurrent(false, first, second, true);
      require(get("refreshEngine") == engine, "engine replaced");
      require(get("cpuBean") == cpu, "collector replaced");
      require(((Config) get("current")).refreshIntervalSeconds == 1, "duplicate args applied");
      checkPipeline();
    }

    if (mode.equals("collision")) {
      require(cpu == null, "an unregistered CPU collector was retained");
      require(server.getAttribute(new ObjectName(CPU), "Marker").equals(42), "host bean replaced");
      @SuppressWarnings("unchecked")
      AtomicReference<List<MetricsRefreshEngine.RefreshTask>> tasks =
          (AtomicReference<List<MetricsRefreshEngine.RefreshTask>>) field(engine, "tasksRef");
      require(tasks.get().stream().noneMatch(task -> task.name.equals("cpu")), "hidden CPU task");
      require(HOST_UNREGISTRATIONS.get() == 0, "host bean was unregistered");
    } else {
      verifyPublishedCpuAdvances(server);
    }

    if (mode.equals("repeat") || mode.equals("collision")) {
      long previousMtime = Files.getLastModifiedTime(first).toMillis();
      Files.writeString(first, "refresh_interval_seconds: 2\nfsmetrics_paths: []\n");
      Files.setLastModifiedTime(first, FileTime.fromMillis(previousMtime + 2_000L));
      await(
          () -> {
            try {
              return ((Config) get("current")).refreshIntervalSeconds == 2;
            } catch (Exception e) {
              return false;
            }
          },
          "first config's watcher did not apply reload");
      require(get("refreshEngine") == engine && get("cpuBean") == cpu, "reload replaced ownership");
      checkPipeline();
      if (mode.equals("collision")) {
        require(server.getAttribute(new ObjectName(CPU), "Marker").equals(42), "host bean lost");
        require(HOST_UNREGISTRATIONS.get() == 0, "reload unregistered host bean");
      }
    }
  }

  private static void concurrent(boolean direct, Path first, Path second, boolean mixed)
      throws Exception {
    CountDownLatch ready = new CountDownLatch(8);
    CountDownLatch go = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    List<Thread> callers = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      String config = (mixed && i % 2 == 1 ? second : first).toString();
      Thread caller =
          new Thread(
              () -> {
                ready.countDown();
                try {
                  go.await();
                  if (direct) {
                    MetricsAgent.premain(config, null);
                  } else {
                    AgentLauncher.premain(config, null);
                  }
                } catch (Throwable error) {
                  failure.set(error);
                }
              },
              "host-agent-entry-" + i);
      callers.add(caller);
      caller.start();
    }
    require(ready.await(5, TimeUnit.SECONDS), "callers did not become ready");
    go.countDown();
    for (Thread caller : callers) {
      caller.join(5_000L);
      require(!caller.isAlive(), "entry point did not return");
    }
    require(failure.get() == null, "concurrent entry failed: " + failure.get());
  }

  private static void checkPipeline() {
    require(countThreads("node-metrics-config-watcher") == 1, "duplicate watchers");
    require(countThreads("node-metrics-refresh-dispatcher") == 1, "duplicate dispatchers");
    for (int i = 1; i <= 3; i++) {
      require(countThreads("node-metrics-refresh-worker-" + i) == 1, "critical worker count");
      require(countThreads("node-metrics-refresh-bg-worker-" + i) == 1, "background worker count");
    }
  }

  private static void verifyPublishedCpuAdvances(MBeanServer server) throws Exception {
    ObjectName name = new ObjectName(CPU);
    await(
        () -> {
          try {
            return server.getAttribute(name, "SystemCpuTotalTicks") != null;
          } catch (Exception e) {
            return false;
          }
        },
        "published CPU never refreshed");
    long before = ((Number) server.getAttribute(name, "SystemCpuTotalTicks")).longValue();
    await(
        () -> {
          try {
            long exported = ((Number) server.getAttribute(name, "SystemCpuTotalTicks")).longValue();
            Object bean = get("cpuBean");
            Object scheduled = bean.getClass().getMethod("getSystemCpuTotalTicks").invoke(bean);
            return exported > before && Long.valueOf(exported).equals(scheduled);
          } catch (Exception e) {
            return false;
          }
        },
        "published and scheduled CPU did not advance together");
  }

  private static long countThreads(String name) {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(thread -> thread.getName().equals(name))
        .count();
  }

  private static Object get(String name) throws Exception {
    Field field = MetricsAgent.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(null);
  }

  private static Object field(Object owner, String name) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static void await(BooleanSupplier condition, String message) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
    while (!condition.getAsBoolean()) {
      require(System.nanoTime() < deadline, message);
      Thread.sleep(20L);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
