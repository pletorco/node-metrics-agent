package co.pletor.nodemetrics.agent;

import co.pletor.nodemetrics.metrics.RefreshManagedMetric;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Asynchronous metric refresh engine.
 * <p>
 * Dispatcher thread enqueues poll tasks periodically, and worker thread executes them off the JMX read path.
 */
final class MetricsRefreshEngine implements AutoCloseable {
  private final long dispatchIntervalMs;
  private final ArrayBlockingQueue<QueuedTask> queue;
  private final Consumer<TelemetryMode> modeListener;
  private final AtomicReference<List<RefreshTask>> tasksRef = new AtomicReference<>(List.of());
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final LongAdder droppedCount = new LongAdder();
  private final LongAdder enqueuedCount = new LongAdder();
  private final LongAdder dequeuedCount = new LongAdder();
  private final LongAdder sinkSuccessCount = new LongAdder();
  private final LongAdder sinkFailureCount = new LongAdder();
  private final LongAdder sinkRetryCount = new LongAdder();
  private final LongAdder endToEndLatencyNanos = new LongAdder();
  private final LongAdder endToEndLatencySamples = new LongAdder();
  private volatile TelemetryMode mode = TelemetryMode.NORMAL;
  private volatile long lastCycleDropped = 0L;
  private Thread dispatcherThread;
  private Thread workerThread;

  static final class RefreshTask {
    final String name;
    final RefreshManagedMetric metric;
    final boolean lowPriority;
    /** Epoch-millisecond timestamp of the last successful poll; 0 if never polled. */
    final AtomicLong lastSuccessEpochMs = new AtomicLong(0L);

    RefreshTask(String name, RefreshManagedMetric metric, boolean lowPriority) {
      this.name = name;
      this.metric = metric;
      this.lowPriority = lowPriority;
    }
  }

  private static final class QueuedTask {
    private final RefreshTask task;
    private final long enqueuedAtNanos;

    QueuedTask(RefreshTask task, long enqueuedAtNanos) {
      this.task = task;
      this.enqueuedAtNanos = enqueuedAtNanos;
    }
  }

  MetricsRefreshEngine(long dispatchIntervalMs, int queueCapacity) {
    this(dispatchIntervalMs, queueCapacity, ignored -> {
    });
  }

  MetricsRefreshEngine(long dispatchIntervalMs, int queueCapacity, Consumer<TelemetryMode> modeListener) {
    this.dispatchIntervalMs = dispatchIntervalMs;
    this.queue = new ArrayBlockingQueue<>(queueCapacity);
    this.modeListener = modeListener;
  }

  void setTasks(List<RefreshTask> tasks) {
    tasksRef.set(List.copyOf(tasks));
  }

  int queueSize() {
    return queue.size();
  }

  double queueFillRatio() {
    int used = queue.size();
    int capacity = used + queue.remainingCapacity();
    return capacity == 0 ? 0.0 : ((double) used / capacity);
  }

  long droppedCount() {
    return droppedCount.sum();
  }

  long processedCount() {
    return sinkSuccessCount.sum();
  }

  long errorCount() {
    return sinkFailureCount.sum();
  }

  long enqueueCount() {
    return enqueuedCount.sum();
  }

  long dequeueCount() {
    return dequeuedCount.sum();
  }

  long sinkSuccessCount() {
    return sinkSuccessCount.sum();
  }

  long sinkFailureCount() {
    return sinkFailureCount.sum();
  }

  long sinkRetryCount() {
    return sinkRetryCount.sum();
  }

  double endToEndLatencyMillis() {
    long samples = endToEndLatencySamples.sum();
    if (samples <= 0L) {
      return 0.0;
    }
    return (endToEndLatencyNanos.sum() / 1_000_000.0) / samples;
  }

  TelemetryMode currentMode() {
    return mode;
  }

  /**
   * Returns the age in milliseconds of the task that has gone the longest without a successful poll.
   * <p>
   * Returns {@code 0} if no tasks are registered or none has been polled yet.
   * This value rises during {@code BYPASS} mode (when tasks are dropped) and
   * can be used to detect stale metric data in monitoring dashboards.
   */
  long maxTaskStalenessMs() {
    List<RefreshTask> tasks = tasksRef.get();
    if (tasks.isEmpty()) {
      return 0L;
    }
    long now = System.currentTimeMillis();
    long maxAge = 0L;
    for (RefreshTask t : tasks) {
      long last = t.lastSuccessEpochMs.get();
      if (last > 0L) {
        maxAge = Math.max(maxAge, now - last);
      }
    }
    return maxAge;
  }

  void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    dispatcherThread = new Thread(this::dispatchLoop, "node-metrics-refresh-dispatcher");
    dispatcherThread.setDaemon(true);
    workerThread = new Thread(this::workerLoop, "node-metrics-refresh-worker");
    workerThread.setDaemon(true);
    dispatcherThread.start();
    workerThread.start();
  }

  void stop() {
    if (!running.compareAndSet(true, false)) {
      return;
    }
    Thread dispatcher = dispatcherThread;
    if (dispatcher != null) {
      dispatcher.interrupt();
    }
    Thread worker = workerThread;
    if (worker != null) {
      worker.interrupt();
    }
  }

  @Override
  public void close() {
    stop();
  }

  private void dispatchLoop() {
    while (running.get()) {
      updateMode();
      lastCycleDropped = dispatchCycle(tasksRef.get());
      if (sleepDispatchInterval()) {
        return;
      }
    }
  }

  private void workerLoop() {
    while (running.get() || !queue.isEmpty()) {
      try {
        QueuedTask queuedTask = queue.poll(500L, TimeUnit.MILLISECONDS);
        if (queuedTask != null) {
          processQueuedTask(queuedTask);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private long dispatchCycle(List<RefreshTask> tasks) {
    long droppedThisCycle = 0L;
    for (RefreshTask task : tasks) {
      if (shouldDropTask(task) || !enqueue(task)) {
        droppedThisCycle++;
      }
    }
    if (droppedThisCycle > 0L) {
      droppedCount.add(droppedThisCycle);
    }
    return droppedThisCycle;
  }

  private boolean shouldDropTask(RefreshTask task) {
    if (mode == TelemetryMode.BYPASS) {
      return true;
    }
    return mode == TelemetryMode.DEGRADED && task.lowPriority;
  }

  private boolean enqueue(RefreshTask task) {
    boolean accepted = queue.offer(new QueuedTask(task, System.nanoTime()));
    if (accepted) {
      enqueuedCount.increment();
    }
    return accepted;
  }

  private boolean sleepDispatchInterval() {
    try {
      Thread.sleep(dispatchIntervalMs);
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return true;
    }
  }

  private void processQueuedTask(QueuedTask queuedTask) {
    dequeuedCount.increment();
    try {
      queuedTask.task.metric.poll();
      queuedTask.task.lastSuccessEpochMs.set(System.currentTimeMillis());
      sinkSuccessCount.increment();
    } catch (Exception | Error ignored) { // NOSONAR - worker must stay alive on metric failures
      sinkFailureCount.increment();
    } finally {
      recordEndToEndLatency(queuedTask.enqueuedAtNanos);
    }
  }

  private void recordEndToEndLatency(long enqueuedAtNanos) {
    long elapsed = System.nanoTime() - enqueuedAtNanos;
    if (elapsed < 0L) {
      return;
    }
    endToEndLatencyNanos.add(elapsed);
    endToEndLatencySamples.increment();
  }

  private void updateMode() {
    int used = queue.size();
    int capacity = used + queue.remainingCapacity();
    double fillRatio = capacity == 0 ? 0.0 : ((double) used / capacity);

    TelemetryMode next = TelemetryMode.NORMAL;
    if (fillRatio >= 0.95) {
      next = TelemetryMode.BYPASS;
    } else if (fillRatio >= 0.80 || lastCycleDropped > 0L) {
      next = TelemetryMode.DEGRADED;
    }

    if (next != mode) {
      mode = next;
      try {
        modeListener.accept(next);
      } catch (Exception ignored) {
        // Keep refresh engine running even if listener fails.
      }
    }
  }
}
