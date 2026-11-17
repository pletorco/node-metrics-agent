package kr.pletor.nodemetrics.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.logging.Level;
import kr.pletor.nodemetrics.metrics.RefreshManagedMetric;

/**
 * Asynchronous metric refresh engine.
 *
 * <p>A dispatcher thread enqueues poll tasks when they are due, and worker threads execute them off
 * the JMX read path. Work is split into two isolated lanes:
 *
 * <ul>
 *   <li><b>critical</b> lane ({@value #CRITICAL_WORKERS} workers): regular tasks. Overload modes
 *       are derived from this lane. A task never has more than one queued or running instance, so a
 *       read that hangs (for example a {@code /proc} or cgroup file on a stalled kernel path)
 *       occupies one worker at most; the other workers keep the remaining metrics fresh.
 *   <li><b>background</b> lane ({@value #BACKGROUND_WORKERS} workers): low-priority tasks, i.e.
 *       filesystem metrics. These touch mounted filesystems, where a dead NFS mount can block a
 *       {@code statvfs} call indefinitely; running them on their own threads keeps such a hang from
 *       stalling CPU, memory and I/O metrics. A task never has more than one queued or running
 *       instance in this lane, so a hung task cannot pile up work or exhaust threads.
 * </ul>
 */
final class MetricsRefreshEngine implements AutoCloseable {
  private static final AgentLog LOGGER = AgentLog.getLogger(MetricsRefreshEngine.class.getName());
  private static final ThrottledLogger THROTTLED_LOGGER = new ThrottledLogger(LOGGER, 60_000L);
  private static final String LOG_KEY_REFRESH_FAILED = "metric-refresh-failed";
  private static final String LOG_KEY_LOOP_FAILED = "engine-loop-failed";
  static final int CRITICAL_WORKERS = 3;
  static final int BACKGROUND_WORKERS = 3;
  static final long DEFAULT_STUCK_THRESHOLD_MS = 30_000L;

  private final long dispatchIntervalMs;
  private final ArrayBlockingQueue<QueuedTask> queue;
  private final ArrayBlockingQueue<QueuedTask> backgroundQueue;
  private final Consumer<TelemetryMode> modeListener;
  private final AtomicReference<List<RefreshTask>> tasksRef = new AtomicReference<>(List.of());
  // Execution ownership survives task removal/replacement. Only workers insert, after taking a
  // lane permit: at most three owners per lane, six in total, including retired collectors.
  private final Map<String, RefreshTask> runningTasks =
      new ConcurrentHashMap<>(CRITICAL_WORKERS + BACKGROUND_WORKERS);
  private final Semaphore criticalPolls = new Semaphore(CRITICAL_WORKERS);
  private final Semaphore backgroundPolls = new Semaphore(BACKGROUND_WORKERS);
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
  private final Thread[] criticalThreads = new Thread[CRITICAL_WORKERS];
  private final Thread[] backgroundThreads = new Thread[BACKGROUND_WORKERS];

  static final class RefreshTask {
    final String name;
    final RefreshManagedMetric metric;
    final boolean lowPriority;

    /** Minimum time between two refreshes of this task; {@code 0} means every dispatch cycle. */
    volatile long intervalMs;

    volatile long intervalNanos;
    final long registeredAtEpochMs = System.currentTimeMillis();

    /** Epoch-millisecond timestamp of the last successful poll; 0 if never polled. */
    final AtomicLong lastSuccessEpochMs = new AtomicLong(0L);

    /**
     * Set while an instance of this task is queued or running, so a task that hangs is not enqueued
     * again on top of itself (which would let it take over every worker).
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

    boolean isSameCollector(RefreshTask other) {
      return metric == other.metric && lowPriority == other.lowPriority;
    }

    void updateInterval(long nextIntervalMs) {
      if (intervalMs != nextIntervalMs) {
        intervalMs = nextIntervalMs;
        intervalNanos = TimeUnit.MILLISECONDS.toNanos(nextIntervalMs);
        nextDueNanos = System.nanoTime();
      }
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
   * Replace the task set. A task with the same name, metric and lane keeps its in-flight state,
   * success/failure history and staleness even when a config reload changes its interval. Removed
   * queued tasks are discarded by workers; removed running tasks retain resource ownership until
   * poll returns. A replacement never polls concurrently with its predecessor.
   */
  void setTasks(List<RefreshTask> tasks) {
    Map<String, RefreshTask> existing = new HashMap<>();
    for (RefreshTask old : tasksRef.get()) {
      existing.put(old.name, old);
    }
    List<RefreshTask> merged = new ArrayList<>(tasks.size());
    for (RefreshTask task : tasks) {
      RefreshTask old = existing.get(task.name);
      if (old != null && old.isSameCollector(task)) {
        old.updateInterval(task.intervalMs);
        merged.add(old);
      } else {
        merged.add(task);
      }
    }
    tasksRef.set(List.copyOf(merged));
  }

  /** Active failures in registration order, followed by failed collectors with retired polls. */
  List<String> failingTaskNames() {
    List<String> names = new ArrayList<>();
    for (RefreshTask t : tasksRef.get()) {
      if (t.consecutiveFailures.get() > 0) {
        names.add(t.name);
      }
    }
    for (RefreshTask t : runningTasks.values()) {
      if (t.consecutiveFailures.get() > 0 && !names.contains(t.name)) {
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
    for (RefreshTask t : runningTasks.values()) {
      if (t.isStuck(now, stuckThresholdMs) && !names.contains(t.name)) {
        names.add(t.name);
      }
    }
    return names;
  }

  // Visible for testing. Queued tasks stay bounded by their lane's existing queue capacity.
  int runningTaskCount() {
    return runningTasks.size();
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
   * never succeeded counts from its registration time once it is running or has failed at least
   * once. Removed running tasks remain included until their calls return. Returns {@code 0} if
   * nothing is registered or running, or none has started polling yet.
   */
  long maxTaskStalenessMs() {
    long now = System.currentTimeMillis();
    long maxOverdue = 0L;
    for (RefreshTask t : tasksRef.get()) {
      maxOverdue = Math.max(maxOverdue, taskStalenessMs(t, now));
    }
    for (RefreshTask t : runningTasks.values()) {
      maxOverdue = Math.max(maxOverdue, taskStalenessMs(t, now));
    }
    return maxOverdue;
  }

  private static long taskStalenessMs(RefreshTask task, long now) {
    long baseline = task.lastSuccessEpochMs.get();
    if (baseline <= 0L && (task.consecutiveFailures.get() > 0 || task.runningSinceNanos != 0L)) {
      baseline = task.registeredAtEpochMs;
    }
    return baseline > 0L ? Math.max(0L, now - baseline - task.intervalMs) : 0L;
  }

  void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    dispatcherThread = AgentThreads.daemon("node-metrics-refresh-dispatcher", this::dispatchLoop);
    dispatcherThread.start();
    for (int i = 0; i < criticalThreads.length; i++) {
      Thread t =
          AgentThreads.daemon("node-metrics-refresh-worker-" + (i + 1), () -> workerLoop(queue));
      criticalThreads[i] = t;
      t.start();
    }
    for (int i = 0; i < backgroundThreads.length; i++) {
      Thread t =
          AgentThreads.daemon(
              "node-metrics-refresh-bg-worker-" + (i + 1), () -> workerLoop(backgroundQueue));
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
    for (Thread t : criticalThreads) {
      if (t != null) {
        t.interrupt();
      }
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
      try {
        updateMode();
        dispatchCycle(tasksRef.get());
      } catch (Throwable t) { // NOSONAR - the dispatcher must survive anything and keep going
        logLoopFailure("dispatcher", t);
      }
      if (sleepDispatchInterval()) {
        return;
      }
    }
  }

  private void logLoopFailure(String loop, Throwable error) {
    THROTTLED_LOGGER.log(
        Level.WARNING,
        LOG_KEY_LOOP_FAILED,
        error,
        () -> "[node-metrics-agent] refresh " + loop + " hit an error and continues");
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
      } catch (Throwable t) { // NOSONAR - a worker must survive anything and keep going
        logLoopFailure("worker", t);
        LockSupport.parkNanos(
            TimeUnit.MILLISECONDS.toNanos(100L)); // never spin on a persistent error
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
      RefreshTask owner = runningTasks.get(task.name);
      if (!isRegistered(task) || (owner != null && owner != task)) {
        // A fresh bean for the same logical resource must wait for the retired poll to finish.
        task.scheduleNext(now);
        droppedThisCycle++;
      } else if (shouldDropTask(task)) {
        droppedThisCycle++;
      } else if (task.lowPriority) {
        if (!dispatchToBackground(task, now)) {
          droppedThisCycle++;
        }
      } else if (!task.pending.compareAndSet(false, true)) {
        // The previous run is still queued or running (slow or hung): skip this one. This is not
        // an overload signal; a single hung task must not push the whole engine into BYPASS.
        task.scheduleNext(now);
        droppedThisCycle++;
      } else if (enqueue(queue, task)) {
        task.scheduleNext(now);
      } else {
        task.pending.set(false);
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
    boolean claimed = false;
    try {
      if (!isRegistered(task) || !claimPoll(task)) {
        droppedCount.increment();
        return;
      }
      claimed = true;
      // setTasks may have replaced the collector between the first check and ownership claim.
      if (!isRegistered(task)) {
        droppedCount.increment();
        return;
      }
      task.markPollStarted();
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
      if (claimed) {
        task.markPollFinished();
        runningTasks.remove(task.name, task);
        pollPermits(task).release();
      }
      task.pending.set(false);
      recordEndToEndLatency(queuedTask.enqueuedAtNanos);
    }
  }

  private boolean isRegistered(RefreshTask task) {
    return tasksRef.get().contains(task);
  }

  private Semaphore pollPermits(RefreshTask task) {
    return task.lowPriority ? backgroundPolls : criticalPolls;
  }

  private boolean claimPoll(RefreshTask task) {
    Semaphore permits = pollPermits(task);
    if (!permits.tryAcquire()) {
      return false;
    }
    boolean claimed = false;
    try {
      claimed = runningTasks.putIfAbsent(task.name, task) == null;
      return claimed;
    } finally {
      if (!claimed) {
        permits.release();
      }
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
