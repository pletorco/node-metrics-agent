package kr.pletor.nodemetrics.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import kr.pletor.nodemetrics.metrics.RefreshManagedMetric;
import org.junit.jupiter.api.Test;

class EngineStartupTest {
  enum Fault {
    CREATE,
    START,
    AFTER_START
  }

  private static void await(BooleanSupplier ready, String message) {
    AsyncTestSupport.await(ready, 5_000L, message);
  }

  private static MetricsRefreshEngine.RefreshTask task(AtomicInteger polls) {
    return new MetricsRefreshEngine.RefreshTask(
        "probe",
        new RefreshManagedMetric() {
          @Override
          public void poll() {
            polls.incrementAndGet();
          }

          @Override
          public void setReadRefreshEnabled(boolean enabled) {}
        },
        false,
        5L);
  }

  private static AgentObservabilityMetrics observability(MetricsRefreshEngine engine) {
    // An obsolete NORMAL notification must not hide the actual failed/stopped lifecycle.
    return new AgentObservabilityMetrics(() -> engine, () -> TelemetryMode.NORMAL, () -> 0L);
  }

  @Test
  void eachCreationAndStartFailureRollsBackWithoutPollingOrRetrying() {
    for (Fault fault : Fault.values()) {
      for (int index = 0; index < 7; index++) {
        final int failing = index;
        List<Thread> owned = new CopyOnWriteArrayList<>();
        AtomicInteger creates = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        BiFunction<String, Runnable, Thread> factory =
            (name, work) -> {
              int position = creates.getAndIncrement();
              if (fault == Fault.CREATE && position == failing) {
                throw new OutOfMemoryError("synthetic native-thread creation failure");
              }
              Thread thread =
                  new Thread(null, work, name, 0L, false) {
                    @Override
                    public synchronized void start() {
                      if (fault == Fault.START && position == failing) {
                        throw new OutOfMemoryError("synthetic native-thread start failure");
                      }
                      super.start();
                      if (fault == Fault.AFTER_START && position == failing) {
                        throw new OutOfMemoryError("synthetic failure after native start");
                      }
                    }

                    @Override
                    public void interrupt() {
                      // Startup-gate release must suffice even when interruption is denied.
                      throw new SecurityException("synthetic denied interruption");
                    }
                  };
              thread.setDaemon(true);
              thread.setUncaughtExceptionHandler((t, e) -> errors.incrementAndGet());
              owned.add(thread);
              return thread;
            };
        MetricsRefreshEngine engine =
            new MetricsRefreshEngine(
                5L,
                16,
                mode -> {
                  throw new LinkageError("synthetic broken diagnostics");
                },
                factory);
        AtomicInteger polls = new AtomicInteger();
        MetricsRefreshEngine.RefreshTask task = task(polls);
        engine.setTasks(List.of(task));
        try {
          assertThrows(OutOfMemoryError.class, engine::start);
          await(() -> owned.stream().noneMatch(Thread::isAlive), "partial threads exited via gate");
          AgentObservabilityMetrics obs = observability(engine);
          assertEquals("FAILED", obs.getRefreshEngineState());
          assertEquals("BYPASS", obs.getMode());
          assertEquals(0, obs.getRefreshEngineRunning());
          assertEquals(0, obs.getRefreshWorkerCount());
          assertEquals(1, obs.getRefreshEngineStartFailureCount());
          assertEquals(0, polls.get());
          assertEquals(0, engine.queueSize());
          assertFalse(task.pending.get());
          assertEquals(0, errors.get());
          int attempted = creates.get();
          for (int i = 0; i < 20; i++) {
            engine.start();
            engine.stop();
          }
          assertEquals(attempted, creates.get(), "failed startup must remain a single attempt");
          assertEquals(MetricsRefreshEngine.Lifecycle.FAILED, engine.lifecycle());
        } finally {
          engine.close();
        }
      }
    }
  }

  @Test
  void stopDuringCreationPreventsActivationAndCannotBeReversedByStart() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    List<Thread> owned = new CopyOnWriteArrayList<>();
    MetricsRefreshEngine engine =
        new MetricsRefreshEngine(
            5L,
            16,
            ignored -> {},
            (name, work) -> {
              if (name.equals("node-metrics-refresh-bg-worker-3")) {
                entered.countDown();
                try {
                  release.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
              Thread thread = AgentThreads.daemon(name, work);
              owned.add(thread);
              return thread;
            });
    AtomicInteger polls = new AtomicInteger();
    engine.setTasks(List.of(task(polls)));
    engine.setStartupGraceMs(0L);
    Thread starter = new Thread(engine::start);
    try {
      starter.start();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertEquals("STARTING", observability(engine).getRefreshEngineState());
      assertEquals("BYPASS", observability(engine).getMode());
      await(() -> engine.maxTaskStalenessMs() > 0, "not-yet-polled task becomes stale");
      engine.stop();
      release.countDown();
      starter.join(5_000L);
      assertFalse(starter.isAlive());
      assertEquals(0, polls.get());
      assertTrue(owned.stream().noneMatch(Thread::isAlive));
      engine.start();
      assertEquals(MetricsRefreshEngine.Lifecycle.STOPPED, engine.lifecycle());
      assertEquals(7, owned.size());
    } finally {
      release.countDown();
      engine.close();
      starter.join(5_000L);
    }
  }

  @Test
  void concurrentStartsActivateOneThreadSetAndStopIsTerminal() throws Exception {
    AtomicInteger creates = new AtomicInteger();
    List<Thread> owned = new CopyOnWriteArrayList<>();
    MetricsRefreshEngine engine =
        new MetricsRefreshEngine(
            5L,
            16,
            ignored -> {},
            (name, work) -> {
              creates.incrementAndGet();
              Thread thread = AgentThreads.daemon(name, work);
              owned.add(thread);
              return thread;
            });
    AtomicInteger polls = new AtomicInteger();
    engine.setTasks(List.of(task(polls)));
    CountDownLatch go = new CountDownLatch(1);
    List<Thread> callers = new ArrayList<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    try {
      for (int i = 0; i < 8; i++) {
        Thread caller =
            new Thread(() -> {
              try {
                go.await();
                engine.start();
              } catch (Throwable error) {
                failure.set(error);
              }
            });
        callers.add(caller);
        caller.start();
      }
      go.countDown();
      for (Thread caller : callers) {
        caller.join(5_000L);
        assertFalse(caller.isAlive());
      }
      assertNull(failure.get());
      await(() -> polls.get() >= 2, "successful engine collects");
      assertEquals(7, creates.get());
      assertEquals(6, observability(engine).getRefreshWorkerCount());
      assertEquals(1, observability(engine).getRefreshEngineRunning());
      engine.stop();
      await(() -> owned.stream().noneMatch(Thread::isAlive), "all engine threads exit");
      for (int i = 0; i < 20; i++) {
        engine.start();
        engine.stop();
      }
      assertEquals(7, creates.get());
      assertEquals("STOPPED", observability(engine).getRefreshEngineState());
      assertEquals("BYPASS", observability(engine).getMode());
    } finally {
      go.countDown();
      engine.close();
    }
  }

  @Test
  void failedStartupAgesNeverPolledTasksOnlyAfterGraceAndTheirInterval() {
    MetricsRefreshEngine engine =
        new MetricsRefreshEngine(
            5L,
            16,
            ignored -> {},
            (name, work) -> {
              throw new SecurityException("synthetic thread denial");
            });
    engine.setTasks(List.of(task(new AtomicInteger())));
    engine.setStartupGraceMs(60_000L);
    assertThrows(SecurityException.class, engine::start);
    assertEquals(0L, engine.maxTaskStalenessMs());
    engine.setStartupGraceMs(0L);
    await(() -> engine.maxTaskStalenessMs() > 0, "failed engine does not hide staleness");
    engine.close();
  }

  @Test
  void stoppingCancelsQueuedWorkButRetainsOwnershipOfRunningCallsUntilTheyReturn() {
    final MetricsRefreshEngine engine = new MetricsRefreshEngine(5L, 16);
    List<MetricsRefreshEngineTest.HangingMetric> blocked = new ArrayList<>();
    List<MetricsRefreshEngine.RefreshTask> running = new ArrayList<>();
    List<MetricsRefreshEngine.RefreshTask> queued = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      MetricsRefreshEngineTest.HangingMetric metric = new MetricsRefreshEngineTest.HangingMetric();
      blocked.add(metric);
      running.add(new MetricsRefreshEngine.RefreshTask("blocked-" + i, metric, true));
    }
    List<MetricsRefreshEngine.RefreshTask> all = new ArrayList<>(running);
    for (int i = 0; i < 4; i++) {
      queued.add(
          new MetricsRefreshEngine.RefreshTask(
              "queued-" + i, new MetricsRefreshEngineTest.CountingMetric(), true));
    }
    all.addAll(queued);
    engine.setTasks(all);
    try {
      engine.start();
      await(
          () ->
              blocked.stream().allMatch(metric -> metric.pollCount.get() == 1)
                  && queued.stream().allMatch(task -> task.pending.get()),
          "background workers block with work queued");
      engine.stop();
      assertEquals(0, engine.queueSize());
      assertTrue(queued.stream().noneMatch(task -> task.pending.get()));
      assertTrue(running.stream().allMatch(task -> task.pending.get()));
      assertEquals(3, engine.runningTaskCount());
      blocked.forEach(MetricsRefreshEngineTest.HangingMetric::release);
      await(() -> engine.runningTaskCount() == 0, "running ownership released on return");
      await(() -> running.stream().noneMatch(task -> task.pending.get()), "pending flags clear");
      assertEquals(MetricsRefreshEngine.Lifecycle.STOPPED, engine.lifecycle());
    } finally {
      blocked.forEach(MetricsRefreshEngineTest.HangingMetric::release);
      engine.close();
    }
  }
}
