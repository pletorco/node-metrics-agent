package co.pletor.nodemetrics.agent;

import co.pletor.nodemetrics.metrics.RefreshManagedMetric;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Asynchronous metric refresh engine.
 *
 * <p>A dispatcher thread enqueues poll tasks when they are due, and worker threads execute them off
 * the JMX read path. Work is split into two isolated lanes:
 *
 * <ul>
 *   <li><b>critical</b> lane (one worker): regular tasks. Overload modes are derived from this
 *       lane.
 *   <li><b>background</b> lane ({@value #BACKGROUND_WORKERS} workers): low-priority tasks, i.e.
 *       filesystem metrics. These touch mounted filesystems, where a dead NFS mount can block a
 *       {@code statvfs} call indefinitely; running them on their own threads keeps such a hang from
 *       stalling CPU, memory and I/O metrics. A task never has more than one queued or running
 *       instance in this lane, so a hung task cannot pile up work or exhaust threads.
 * </ul>
 */
final class MetricsRefreshEngine implements AutoCloseable {
  private static final Logger LOGGER = Logger.getLogger(MetricsRefreshEngine.class.getName());
  private static final ThrottledLogger THROTTLED_LOGGER = new ThrottledLogger(LOGGER, 60_000L);
  private static final String LOG_KEY_REFRESH_FAILED = "metric-refresh-failed";
  static final int BACKGROUND_WORKERS = 3;
  static final long DEFAULT_STUCK_THRESHOLD_MS = 30_000L;

  private final long dispatchIntervalMs;
  private final ArrayBlockingQueue<QueuedTask> queue;
  private final ArrayBlockingQueue<QueuedTask> backgroundQueue;
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

  /** Tasks rejected by a full queue in the last cycle (excludes intentional mode-based drops). */
  private volatile long lastCycleEnqueueFailures = 0L;

  private volatile long stuckThresholdMs = DEFAULT_STUCK_THRESHOLD_MS;
  private Thread dispatcherThread;
  private Thread workerThread;
  private final Thread[] backgroundThreads = new Thread[BACKGROUND_WORKERS];

  static final class RefreshTask {
    final String name;
    final RefreshManagedMetric metric;
    final boolean lowPriority;

    /** Minimum time between two refreshes of this task; {@code 0} means every dispatch cycle. */
    final long intervalMs;

    final long intervalNanos;
    final long registeredAtEpochMs = System.currentTimeMillis();

    /** Epoch-millisecond timestamp of the last successful poll; 0 if never polled. */
    final AtomicLong lastSuccessEpochMs = new AtomicLong(0L);

    /**
     * Background lane only: set while an instance of this task is queued or running, so a task that
     * hangs is not enqueued again on top of itself.
     */
    final AtomicBoolean pending = new AtomicBoolean(false);

    /** {@code System.nanoTime()} at which the current poll started; 0 while not polling. */
    private volatile long runningSinceNanos = 0L;

    /** Consecutive failed polls; reset to 0 by the next successful poll. */
    final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    /** {@code System.nanoTime()} at which the dispatcher may enqueue this task next. */
    private volatile long nextDueNanos = System.nanoTime();

    RefreshTask(String name, RefreshManagedMetric metric, boolean lowPriority) {
      this(name, metric, lowPriority, 0L);
    }

    RefreshTask(String name, RefreshManagedMetric metric, boolean lowPriority, long intervalMs) {
      this.name = name;
      this.metric = metric;
      this.lowPriority = lowPriority;
      this.intervalMs = Math.max(0L, intervalMs);
      this.intervalNanos = TimeUnit.MILLISECONDS.toNanos(this.intervalMs);
    }

    void markPollStarted() {
      long now = System.nanoTime();
      runningSinceNanos = now == 0L ? 1L : now;
    }

    void markPollFinished() {
      runningSinceNanos = 0L;
    }

    boolean isStuck(long nowNanos, long thresholdMs) {
      long since = runningSinceNanos;
      return since != 0L && nowNanos - since >= TimeUnit.MILLISECONDS.toNanos(thresholdMs);
    }

    boolean isDue(long nowNanos) {
      return nowNanos - nextDueNanos >= 0L;
    }

    void scheduleNext(long nowNanos) {
      nextDueNanos = nowNanos + intervalNanos;
    }

    boolean isSameSchedule(RefreshTask other) {
      return metric == other.metric
          && lowPriority == other.lowPriority
          && intervalMs == other.intervalMs;
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
    this(dispatchIntervalMs, queueCapacity, ignored -> {});
  }

  MetricsRefreshEngine(
      long dispatchIntervalMs, int queueCapacity, Consumer<TelemetryMode> modeListener) {
    this.dispatchIntervalMs = dispatchIntervalMs;
    this.queue = new ArrayBlockingQueue<>(queueCapacity);
    this.backgroundQueue = new ArrayBlockingQueue<>(queueCapacity);
    this.modeListener = modeListener;
  }

  /**
   * Replace the task set. A task that is unchanged (same name, metric, priority and interval) keeps
   * its existing instance, so success/failure history and staleness survive config reloads.
   */
  void setTasks(List<RefreshTask> tasks) {
    Map<String, RefreshTask> existing = new HashMap<>();
    for (RefreshTask old : tasksRef.get()) {
      existing.put(old.name, old);
    }
    List<RefreshTask> merged = new ArrayList<>(tasks.size());
    for (RefreshTask task : tasks) {
      RefreshTask old = existing.get(task.name);
      merged.add(old != null && old.isSameSchedule(task) ? old : task);
    }
    tasksRef.set(List.copyOf(merged));
  }

  /** Names of tasks whose most recent poll failed, in registration order. */
  List<String> failingTaskNames() {
    List<String> names = new ArrayList<>();
    for (RefreshTask t : tasksRef.get()) {
      if (t.consecutiveFailures.get() > 0) {
        names.add(t.name);
      }
    }
    return names;
  }

  int queueSize() {
    return queue.size() + backgroundQueue.size();
  }

  /** Highest fill ratio of the two lanes. */
  double queueFillRatio() {
    return Math.max(fillRatio(queue), fillRatio(backgroundQueue));
  }

  private static double fillRatio(ArrayBlockingQueue<QueuedTask> q) {
    int used = q.size();
    int capacity = used + q.remainingCapacity();
    return capacity == 0 ? 0.0 : ((double) used / capacity);
  }

  // Visible for testing
  void setStuckThresholdMs(long thresholdMs) {
    this.stuckThresholdMs = thresholdMs;
  }

  /**
   * Names of tasks whose current poll has been running longer than the stuck threshold, typically a
   * filesystem call blocked on an unresponsive mount. The blocked thread cannot be interrupted, so
   * the task simply stays stale until the call returns.
   */
  List<String> stuckTaskNames() {
    long now = System.nanoTime();
    List<String> names = new ArrayList<>();
    for (RefreshTask t : tasksRef.get()) {
      if (t.isStuck(now, stuckThresholdMs)) {
        names.add(t.name);
      }
    }
    return names;
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
   * Returns how far behind schedule the most overdue task is, in milliseconds: the time since its
   * last successful poll minus its refresh interval (never negative).
   *
   * <p>A healthy task is always close to {@code 0}, whatever its interval. The value rises during
   * {@code BYPASS} mode (when tasks are dropped) and when a task keeps failing. A task that has
   * never succeeded counts from its registration time once it has failed at least once. Returns
   * {@code 0} if no tasks are registered or none has been polled yet.
   */
  long maxTaskStalenessMs() {
    List<RefreshTask> tasks = tasksRef.get();
    if (tasks.isEmpty()) {
      return 0L;
    }
    long now = System.currentTimeMillis();
    long maxOverdue = 0L;
    for (RefreshTask t : tasks) {
      long baseline = t.lastSuccessEpochMs.get();
      if (baseline <= 0L && t.consecutiveFailures.get() > 0) {
        baseline = t.registeredAtEpochMs;
      }
      if (baseline > 0L) {
        maxOverdue = Math.max(maxOverdue, now - baseline - t.intervalMs);
      }
    }
    return maxOverdue;
  }

  void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    dispatcherThread = new Thread(this::dispatchLoop, "node-metrics-refresh-dispatcher");
    dispatcherThread.setDaemon(true);
    workerThread = new Thread(() -> workerLoop(queue), "node-metrics-refresh-worker");
    workerThread.setDaemon(true);
    dispatcherThread.start();
    workerThread.start();
    for (int i = 0; i < backgroundThreads.length; i++) {
      Thread t =
          new Thread(
              () -> workerLoop(backgroundQueue), "node-metrics-refresh-bg-worker-" + (i + 1));
      t.setDaemon(true);
      backgroundThreads[i] = t;
      t.start();
    }
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
    for (Thread t : backgroundThreads) {
      if (t != null) {
        t.interrupt();
      }
    }
  }

  @Override
  public void close() {
    stop();
  }

  private void dispatchLoop() {
    while (running.get()) {
      updateMode();
      dispatchCycle(tasksRef.get());
      if (sleepDispatchInterval()) {
        return;
      }
    }
  }

  private void workerLoop(ArrayBlockingQueue<QueuedTask> source) {
    while (running.get() || !source.isEmpty()) {
      try {
        QueuedTask queuedTask = source.poll(500L, TimeUnit.MILLISECONDS);
        if (queuedTask != null) {
          processQueuedTask(queuedTask);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void dispatchCycle(List<RefreshTask> tasks) {
    long droppedThisCycle = 0L;
    long enqueueFailures = 0L;
    long now = System.nanoTime();
    for (RefreshTask task : tasks) {
      if (!task.isDue(now)) {
        continue;
      }
      if (shouldDropTask(task)) {
        droppedThisCycle++;
      } else if (task.lowPriority) {
        if (!dispatchToBackground(task, now)) {
          droppedThisCycle++;
        }
      } else if (enqueue(queue, task)) {
        task.scheduleNext(now);
      } else {
        droppedThisCycle++;
        enqueueFailures++;
      }
    }
    if (droppedThisCycle > 0L) {
      droppedCount.add(droppedThisCycle);
    }
    // Only real queue rejections signal overload. Intentional drops in DEGRADED/BYPASS must not
    // count, otherwise those modes would sustain themselves and never recover to NORMAL.
    lastCycleEnqueueFailures = enqueueFailures;
  }

  private boolean shouldDropTask(RefreshTask task) {
    if (mode == TelemetryMode.BYPASS) {
      return true;
    }
    return mode == TelemetryMode.DEGRADED && task.lowPriority;
  }

  /**
   * Hand a task to the background lane. Returns {@code false} if the work was skipped: the previous
   * run of this task is still queued or running (for example blocked on a dead mount), or the lane
   * is full. Skipping does not affect the overload mode, which is derived from the critical lane.
   */
  private boolean dispatchToBackground(RefreshTask task, long now) {
    if (!task.pending.compareAndSet(false, true)) {
      // Previous run has not finished: try again after the task's normal interval.
      task.scheduleNext(now);
      return false;
    }
    if (enqueue(backgroundQueue, task)) {
      task.scheduleNext(now);
      return true;
    }
    task.pending.set(false);
    return false;
  }

  private boolean enqueue(ArrayBlockingQueue<QueuedTask> target, RefreshTask task) {
    boolean accepted = target.offer(new QueuedTask(task, System.nanoTime()));
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
    RefreshTask task = queuedTask.task;
    task.markPollStarted();
    try {
      task.metric.poll();
      // Metrics absorb read failures to keep JMX reads exception-free; they report them here.
      Throwable absorbed = task.metric.lastRefreshError();
      if (absorbed == null) {
        recordSuccess(task);
      } else {
        recordFailure(task, absorbed);
      }
    } catch (Exception | Error e) { // NOSONAR - worker must stay alive on metric failures
      recordFailure(task, e);
    } finally {
      task.markPollFinished();
      task.pending.set(false);
      recordEndToEndLatency(queuedTask.enqueuedAtNanos);
    }
  }

  private void recordSuccess(RefreshTask task) {
    task.lastSuccessEpochMs.set(System.currentTimeMillis());
    task.consecutiveFailures.set(0);
    sinkSuccessCount.increment();
  }

  private void recordFailure(RefreshTask task, Throwable error) {
    task.consecutiveFailures.incrementAndGet();
    sinkFailureCount.increment();
    THROTTLED_LOGGER.log(
        Level.WARNING,
        LOG_KEY_REFRESH_FAILED,
        error,
        () -> "[node-metrics-agent] metric refresh failed: task=" + task.name);
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
    } else if (fillRatio >= 0.80 || lastCycleEnqueueFailures > 0L) {
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
