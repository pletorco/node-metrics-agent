package kr.pletor.nodemetrics.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kr.pletor.nodemetrics.metrics.RefreshManagedMetric;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The agent must never affect the application it is attached to: nothing thrown on an agent thread
 * may reach the application's default uncaught-exception handler (many applications exit the JVM
 * from it), and starting the agent must not delay or break the application's startup.
 */
class AgentIsolationTest {

  private Thread.UncaughtExceptionHandler originalDefault;
  private final List<String> reachedApplicationHandler = new CopyOnWriteArrayList<>();

  @BeforeEach
  void installApplicationStyleDefaultHandler() {
    ThrottleTestSupport.resetAll();
    originalDefault = Thread.getDefaultUncaughtExceptionHandler();
    // What a fail-fast application would install.
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> reachedApplicationHandler.add(thread.getName() + ": " + error));
  }

  @AfterEach
  void restoreDefaultHandler() {
    Thread.setDefaultUncaughtExceptionHandler(originalDefault);
    ThrottleTestSupport.resetAll();
  }

  private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    while (System.nanoTime() < deadline && !condition.getAsBoolean()) {
      try {
        Thread.sleep(10L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  // ---------------------------------------------------------------- launcher

  @Test
  void launcher_hasNoStaticStateThatCouldFailBeforePremainRuns() {
    for (Field field : AgentLauncher.class.getDeclaredFields()) {
      assertFalse(
          Modifier.isStatic(field.getModifiers()),
          "A static field would be initialized outside premain's try/catch: " + field);
    }
  }

  @Test
  void launcher_premainDeclaresTheSignatureTheJvmExpects() throws Exception {
    Method premain =
        AgentLauncher.class.getMethod(
            "premain", String.class, java.lang.instrument.Instrumentation.class);
    assertTrue(Modifier.isStatic(premain.getModifiers()));
  }

  @Test
  void launcher_returnsImmediatelyWhileInitializationIsStillRunning() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch started = new CountDownLatch(1);
    AtomicInteger threadWasDaemon = new AtomicInteger(-1);
    try {
      long start = System.nanoTime();
      AgentLauncher.start(
          () -> {
            threadWasDaemon.set(Thread.currentThread().isDaemon() ? 1 : 0);
            started.countDown();
            try {
              release.await();
            } catch (InterruptedException ignored) {
              // test shutting down
            }
          });
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

      assertTrue(elapsedMs < 500L, "premain must not wait for initialization, took " + elapsedMs);
      assertTrue(started.await(2, TimeUnit.SECONDS), "Initialization must run on its own thread");
      assertEquals(1, threadWasDaemon.get(), "The init thread must not keep the JVM alive");
    } finally {
      release.countDown();
    }
  }

  @Test
  void launcher_confinesErrorsOfInitializationToItsThread() throws Exception {
    for (Throwable failure :
        new Throwable[] {
          new NoClassDefFoundError("agent jar replaced"),
          new ExceptionInInitializerError(new IllegalStateException("static init failed")),
          new OutOfMemoryError("simulated"),
          new RuntimeException("boom")
        }) {
      CountDownLatch ran = new CountDownLatch(1);
      AgentLauncher.start(
          () -> {
            ran.countDown();
            if (failure instanceof Error) {
              throw (Error) failure;
            }
            throw (RuntimeException) failure;
          });
      assertTrue(ran.await(2, TimeUnit.SECONDS));
    }
    Thread.sleep(200L);
    assertTrue(
        reachedApplicationHandler.isEmpty(),
        "The application's handler must never see agent errors: " + reachedApplicationHandler);
  }

  // ----------------------------------------------------------- thread helper

  @Test
  void agentThreads_areDaemonsAndKeepErrorsAwayFromTheApplicationHandler() throws Exception {
    CountDownLatch ran = new CountDownLatch(1);
    Thread thread =
        AgentThreads.daemon(
            "agent-test-thread",
            () -> {
              ran.countDown();
              throw new NoClassDefFoundError("boom");
            });
    assertTrue(thread.isDaemon());

    thread.start();
    assertTrue(ran.await(2, TimeUnit.SECONDS));
    thread.join(2_000L);

    assertTrue(reachedApplicationHandler.isEmpty(), reachedApplicationHandler.toString());
  }

  // ------------------------------------------------------------------ engine

  static final class NoopMetric implements RefreshManagedMetric {
    final AtomicInteger polls = new AtomicInteger();

    @Override
    public void poll() {
      polls.incrementAndGet();
    }

    @Override
    public void setReadRefreshEnabled(boolean enabled) {
      // no-op
    }
  }

  /**
   * Blocks its worker like a stalled read would, which saturates the queue and changes the mode.
   */
  static final class BlockingMetric implements RefreshManagedMetric {
    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    public void poll() {
      try {
        release.await();
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
    }

    void release() {
      release.countDown();
    }

    @Override
    public void setReadRefreshEnabled(boolean enabled) {
      // no-op
    }
  }

  @Test
  void engine_dispatcherSurvivesAnErrorFromTheModeListener() {
    BlockingMetric blocking = new BlockingMetric();
    AtomicInteger listenerCalls = new AtomicInteger();
    // The mode listener runs on the dispatcher thread. Make it throw an Error, which the engine
    // did not guard against before: the dispatcher thread used to die silently.
    MetricsRefreshEngine engine =
        new MetricsRefreshEngine(
            5L,
            2,
            mode -> {
              if (listenerCalls.incrementAndGet() == 1) {
                throw new NoClassDefFoundError("listener failed");
              }
            });
    // Distinct tasks: only more of them than the workers plus queue slots can fill the queue.
    List<MetricsRefreshEngine.RefreshTask> blocked = new ArrayList<>();
    for (int i = 0; i < MetricsRefreshEngine.CRITICAL_WORKERS + 6; i++) {
      blocked.add(new MetricsRefreshEngine.RefreshTask("blocked-" + i, blocking, false));
    }
    engine.setTasks(blocked);
    try {
      engine.start();
      waitUntil(() -> listenerCalls.get() > 0, 5_000L);
      assertTrue(listenerCalls.get() > 0, "The test must actually exercise the failing listener");

      // A dead dispatcher would stop counting; a live one keeps dropping while the queue is full.
      long dropped = engine.droppedCount();
      waitUntil(() -> engine.droppedCount() >= dropped + 5, 5_000L);
      assertTrue(
          engine.droppedCount() >= dropped + 5, "The dispatcher must keep running after the Error");
    } finally {
      blocking.release();
      engine.stop();
    }
    assertTrue(reachedApplicationHandler.isEmpty(), reachedApplicationHandler.toString());
  }

  // ---------------------------------------------------------- config watcher

  @TempDir Path tempDir;

  @Test
  void configWatcher_survivesAnErrorFromApplyAndRetries() throws Exception {
    Path cfg = tempDir.resolve("node-metrics.yml");
    Files.writeString(cfg, "fsmetrics_paths:\n  - /\n", StandardCharsets.UTF_8);
    AtomicInteger applyCalls = new AtomicInteger();
    ConfigReloader reloader =
        new ConfigReloader(
            cfg,
            config -> {
              if (applyCalls.incrementAndGet() == 1) {
                // e.g. the agent jar was replaced on disk and a class can no longer be loaded
                throw new NoClassDefFoundError("kr/pletor/nodemetrics/agent/Config");
              }
            });
    Thread thread = AgentThreads.daemon("test-config-watcher", reloader);
    thread.start();
    try {
      waitUntil(() -> applyCalls.get() >= 2, 8_000L);
      assertTrue(applyCalls.get() >= 2, "The watcher must retry after an Error instead of dying");
      assertTrue(thread.isAlive(), "The watcher thread must still be running");

      // ... and it still reacts to later changes.
      Files.writeString(cfg, "fsmetrics_paths:\n  - /tmp\n", StandardCharsets.UTF_8);
      Files.setLastModifiedTime(cfg, FileTime.fromMillis(System.currentTimeMillis() + 10_000L));
      int before = applyCalls.get();
      waitUntil(() -> applyCalls.get() > before, 8_000L);
      assertTrue(applyCalls.get() > before);
    } finally {
      reloader.stop();
      thread.interrupt();
      thread.join(2_000L);
    }
    assertTrue(reachedApplicationHandler.isEmpty(), reachedApplicationHandler.toString());
  }

  // ------------------------------------------------- startup step isolation

  @Test
  void startupSteps_aFailingStepDoesNotStopTheNextOne() throws Exception {
    Method runStep = MetricsAgent.class.getDeclaredMethod("runStep", String.class, Runnable.class);
    runStep.setAccessible(true);
    AtomicInteger ran = new AtomicInteger();

    runStep.invoke(
        null,
        "fails with an Error",
        (Runnable)
            () -> {
              throw new NoClassDefFoundError("com/sun/management/OperatingSystemMXBean");
            });
    runStep.invoke(
        null,
        "fails with an initializer error",
        (Runnable)
            () -> {
              throw new ExceptionInInitializerError(new IllegalStateException("x"));
            });
    runStep.invoke(null, "still runs", (Runnable) ran::incrementAndGet);

    assertEquals(1, ran.get());
  }

  @Test
  void startupSteps_aBeanThatCannotBeCreatedIsSkippedNotFatal() throws Exception {
    Method create =
        MetricsAgent.class.getDeclaredMethod(
            "createAndRegister",
            String.class,
            java.util.function.Supplier.class,
            Class.class,
            String.class);
    create.setAccessible(true);

    Object result =
        create.invoke(
            null,
            "cpu metrics",
            (java.util.function.Supplier<Object>)
                () -> {
                  throw new NoClassDefFoundError("com/sun/management/OperatingSystemMXBean");
                },
            Runnable.class,
            "kr.pletor.node:type=CpuMetrics");

    assertNull(result, "A bean that cannot be created is skipped so the other beans still start");
  }
}
