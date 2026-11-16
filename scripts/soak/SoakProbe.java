package kr.pletor.nodemetrics.agent;

import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import kr.pletor.nodemetrics.metrics.RefreshManagedMetric;

/** Separate test program; never packaged in the deployed agent. */
public final class SoakProbe {
  private static final int OPERATIONS = 4096;
  private static volatile Object allocationSink;
  private static volatile long hostSink;

  private static final class Collector implements RefreshManagedMetric {
    final AtomicBoolean blocked = new AtomicBoolean();
    final AtomicBoolean failing = new AtomicBoolean();
    final AtomicInteger active = new AtomicInteger();
    final AtomicInteger peak = new AtomicInteger();
    final AtomicLong polls = new AtomicLong();

    @Override
    public void poll() {
      peak.accumulateAndGet(active.incrementAndGet(), Math::max);
      try {
        while (blocked.get()) {
          Thread.interrupted(); // Simulate an uninterruptible read; released by the controller.
          LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        if (failing.get()) {
          throw new IllegalStateException("soak: injected collector failure");
        }
        polls.incrementAndGet();
      } finally {
        active.decrementAndGet();
      }
    }

    @Override
    public void setReadRefreshEnabled(boolean enabled) {}
  }

  private static Object field(String name) throws Exception {
    Field field = MetricsAgent.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(null);
  }

  private static String actualChecksum() throws Exception {
    synchronized (field("APPLY_LOCK")) {
      Config config = (Config) field("current");
      return config == null ? "" : config.checksum;
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }

  public static void main(String[] args) {
    Path output = Path.of(args[0]);
    try {
      run(
          output,
          Boolean.parseBoolean(args[1]),
          Integer.parseInt(args[2]),
          Integer.parseInt(args[3]),
          Integer.parseInt(args[4]));
      Files.writeString(output.resolve("complete.marker"), "complete\n");
      System.exit(0); // The exporter owns non-daemon HTTP threads.
    } catch (Throwable failure) {
      failure.printStackTrace();
      try (PrintWriter dump =
          new PrintWriter(Files.newBufferedWriter(output.resolve("threads.txt")))) {
        int count = 0;
        for (var entry : Thread.getAllStackTraces().entrySet()) {
          if (++count > 512) {
            break;
          }
          dump.println(entry.getKey());
          for (StackTraceElement frame :
              Arrays.asList(entry.getValue()).subList(0, Math.min(32, entry.getValue().length))) {
            dump.println("  " + frame);
          }
        }
      } catch (Exception ignored) {
        // The original failure is already on stderr.
      }
      System.exit(1);
    }
  }

  private static void run(Path output, boolean enabled, int duration, int warmup, int gcInterval)
      throws Exception {
    MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    ObjectName realHealth = new ObjectName("kr.pletor.agent:type=Observability");
    if (enabled) {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (!server.isRegistered(realHealth)) {
        require(System.nanoTime() < deadline, "Actual agent never registered");
        Thread.sleep(50);
      }
    }
    Properties environment = new Properties();
    environment.setProperty("java.version", System.getProperty("java.version"));
    environment.setProperty("java.vm.name", System.getProperty("java.vm.name"));
    environment.setProperty("os.name", System.getProperty("os.name"));
    environment.setProperty("os.arch", System.getProperty("os.arch"));
    environment.setProperty(
        "processors", String.valueOf(Runtime.getRuntime().availableProcessors()));
    environment.setProperty(
        "cgroup",
        enabled
            ? String.valueOf(
                server.getAttribute(
                    new ObjectName("kr.pletor.cgroup:type=MemMetrics"), "CgroupVersion"))
            : "control: no production agent");
    try (var writer = Files.newBufferedWriter(output.resolve("environment.properties"))) {
      environment.store(writer, "Soak JVM environment");
    }

    Collector healthy = new Collector();
    Collector critical = new Collector();
    Collector background = new Collector();
    Collector failing = new Collector();
    MetricsRefreshEngine fixture = new MetricsRefreshEngine(10L, 64);
    fixture.setStuckThresholdMs(100L);
    AtomicInteger applied = new AtomicInteger();
    Path configPath = output.resolve("fixture.yml");
    ConfigReloader reloader =
        new ConfigReloader(
            configPath,
            config -> {
              fixture.setTasks(
                  List.of(
                      new MetricsRefreshEngine.RefreshTask("healthy", healthy, false, 10L),
                      new MetricsRefreshEngine.RefreshTask(
                          "critical", critical, false, config.refreshIntervalSeconds * 10L),
                      new MetricsRefreshEngine.RefreshTask(
                          "filesystem", background, true, config.refreshIntervalSeconds * 10L),
                      new MetricsRefreshEngine.RefreshTask("failing", failing, false, 10L)));
              applied.incrementAndGet();
            });
    Method check = ConfigReloader.class.getDeclaredMethod("checkAndReloadIfChanged");
    check.setAccessible(true);
    server.registerMBean(
        new AgentObservabilityMetrics(() -> fixture, fixture::currentMode, () -> 0L),
        new ObjectName("kr.pletor.agent:type=SoakFixture"));
    com.sun.management.ThreadMXBean threads =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    require(
        threads.isThreadAllocatedMemorySupported() && threads.isCurrentThreadCpuTimeSupported(),
        "CPU/allocation accounting unavailable");
    threads.setThreadAllocatedMemoryEnabled(true);
    threads.setThreadCpuTimeEnabled(true);
    com.sun.management.OperatingSystemMXBean os =
        (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    long[] latencies = new long[OPERATIONS];
    int rejected = 0;
    int recovered = 0;
    int actualReloads = 0;
    long modified = System.currentTimeMillis();
    long previousPolls = -1;
    String expectedActualChecksum = "";
    int actualChangedAt = -100;
    fixture.start();
    try (PrintWriter csv =
        new PrintWriter(Files.newBufferedWriter(output.resolve("samples.csv")))) {
      csv.println(
          "seconds,measured,phase,p99Nanos,callerCpuNanos,callerAllocatedBytes,postGcHeapBytes,threads,refreshThreads,fixtureQueue,agentQueue,processed,errors,rejected,applied,recovered,actualReloads,processCpuNanos");
      long start = System.nanoTime();
      int count = duration + warmup;
      for (int tick = 0; tick <= count; tick++) {
        int phase = (tick / 10) % 3;
        int within = tick % 10;
        critical.blocked.set(phase == 0);
        background.blocked.set(phase == 0);
        failing.failing.set(phase == 1);
        if ((phase == 0 && (within == 0 || within == 5)) || (phase == 2 && within == 0)) {
          String yaml =
              "refresh_interval_seconds: " + (1 + (applied.get() % 3)) + "\nfsmetrics_paths: [/]\n";
          Files.writeString(configPath, yaml);
          Files.setLastModifiedTime(configPath, FileTime.fromMillis(++modified));
          check.invoke(reloader);
          if (enabled) {
            Path actual = output.resolve("agent.yml");
            Files.writeString(actual, yaml);
            Files.setLastModifiedTime(actual, FileTime.fromMillis(modified));
            expectedActualChecksum = ConfigLoader.load(actual).checksum;
            actualChangedAt = tick;
          }
        }
        if (phase == 1 && within == 0) {
          int before = applied.get();
          Files.writeString(configPath, "#" + "x".repeat(ConfigLoader.MAX_CONFIG_BYTES));
          Files.setLastModifiedTime(configPath, FileTime.fromMillis(++modified));
          check.invoke(reloader);
          require(applied.get() == before, "Rejected config was applied");
          rejected++;
        }
        if (enabled && tick == actualChangedAt + 3) {
          require(
              actualChecksum().equals(expectedActualChecksum),
              "Actual file watcher did not apply config");
          actualReloads++;
        }
        if (phase == 2 && within == 5) {
          require(
              fixture.failingTaskNames().isEmpty() && fixture.stuckTaskNames().isEmpty(),
              "Collectors did not recover");
          recovered++;
        }
        if (tick > 1) {
          require(healthy.polls.get() > previousPolls, "Host metric progress stopped");
        }
        previousPolls = healthy.polls.get();
        require(
            critical.peak.get() <= 1 && background.peak.get() <= 1,
            "A config reload duplicated a hung collector");
        require(fixture.queueSize() <= 128, "Fixture queue exceeded capacity");
        int refreshThreads =
            (int)
                Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> thread.getName().startsWith("node-metrics-refresh-"))
                    .count();
        require(refreshThreads <= (enabled ? 14 : 7), "Refresh worker count exceeded bound");
        int realQueue =
            enabled ? ((Number) server.getAttribute(realHealth, "QueueDepth")).intValue() : 0;
        require(realQueue <= 2048, "Actual agent queue exceeded capacity");

        long cpuBefore = threads.getCurrentThreadCpuTime();
        long allocatedBefore = threads.getThreadAllocatedBytes(Thread.currentThread().getId());
        require(cpuBefore >= 0 && allocatedBefore >= 0, "Caller accounting unavailable");
        for (int operation = 0; operation < OPERATIONS; operation++) {
          long before = System.nanoTime();
          long value = operation + hostSink;
          for (int step = 0; step < 64; step++) {
            value = value * 1664525L + 1013904223L;
          }
          allocationSink = new byte[256];
          hostSink = value;
          latencies[operation] = System.nanoTime() - before;
        }
        long allocated =
            threads.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocatedBefore;
        long cpu = threads.getCurrentThreadCpuTime() - cpuBefore;
        Arrays.sort(latencies);
        long heap = -1;
        if (tick % gcInterval == 0) {
          long collections =
              ManagementFactory.getGarbageCollectorMXBeans().stream()
                  .mapToLong(bean -> Math.max(0, bean.getCollectionCount()))
                  .sum();
          System.gc(); // Test-only retention measurement, outside the host batch.
          long after =
              ManagementFactory.getGarbageCollectorMXBeans().stream()
                  .mapToLong(bean -> Math.max(0, bean.getCollectionCount()))
                  .sum();
          require(after > collections, "Explicit GC did not run; retention evidence is incomplete");
          heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        }
        double elapsed = (System.nanoTime() - start) / 1_000_000_000.0;
        csv.printf(
            java.util.Locale.ROOT,
            "%.6f,%s,%d,%d,%.3f,%.3f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
            elapsed,
            tick >= warmup,
            phase,
            latencies[(int) (OPERATIONS * .99)],
            (double) cpu / OPERATIONS,
            (double) allocated / OPERATIONS,
            heap,
            threads.getThreadCount(),
            refreshThreads,
            fixture.queueSize(),
            realQueue,
            fixture.processedCount(),
            fixture.errorCount(),
            rejected,
            applied.get(),
            recovered,
            actualReloads,
            os.getProcessCpuTime());
        csv.flush();
        long remaining = start + TimeUnit.SECONDS.toNanos(tick + 1L) - System.nanoTime();
        if (remaining > 0) {
          TimeUnit.NANOSECONDS.sleep(remaining);
        }
      }
      require(
          rejected > 0 && recovered > 0 && applied.get() >= 3, "Fault scenario was not exercised");
    } finally {
      critical.blocked.set(false);
      background.blocked.set(false);
      fixture.stop();
      reloader.stop();
    }
  }
}
