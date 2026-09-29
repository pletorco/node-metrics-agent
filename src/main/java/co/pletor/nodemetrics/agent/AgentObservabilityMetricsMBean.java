package co.pletor.nodemetrics.agent;

import co.pletor.nodemetrics.metrics.JmxMetricHint;

/** Baseline self-observability metrics for the telemetry module. */
public interface AgentObservabilityMetricsMBean {
  @JmxMetricHint("counter")
  long getProcessedCount();

  @JmxMetricHint("counter")
  long getEnqueueCount();

  @JmxMetricHint("counter")
  long getDequeueCount();

  @JmxMetricHint("counter")
  long getDroppedCount();

  @JmxMetricHint("counter")
  long getErrorCount();

  @JmxMetricHint("counter")
  long getSinkSuccessCount();

  @JmxMetricHint("counter")
  long getSinkFailureCount();

  @JmxMetricHint("counter")
  long getSinkRetryCount();

  @JmxMetricHint("gauge")
  String getMode();

  @JmxMetricHint("gauge")
  int getQueueDepth();

  @JmxMetricHint("gauge")
  double getQueueFillRatio();

  @JmxMetricHint("gauge")
  double getEndToEndLatencyMillis();

  /**
   * How far behind schedule the most overdue refresh task is, in milliseconds: time since its last
   * successful poll minus its refresh interval (never negative).
   *
   * <p>Rises when the engine is in {@code BYPASS} mode or when a task consistently fails, and stays
   * near {@code 0} for healthy tasks regardless of their individual refresh intervals.
   */
  @JmxMetricHint("gauge")
  long getMaxTaskStalenessMs();

  /**
   * Number of refresh tasks whose most recent poll failed (for example an unreadable {@code /proc}
   * file or an unavailable filesystem). {@code 0} means every task is healthy.
   */
  @JmxMetricHint("gauge")
  int getFailingTaskCount();

  /**
   * Comma-separated names of the tasks counted by {@link #getFailingTaskCount()}, or an empty
   * string when all tasks are healthy.
   */
  @JmxMetricHint("gauge")
  String getFailingTasks();

  /**
   * Number of refresh tasks whose current poll has been running for more than 30 seconds, typically
   * a filesystem call blocked on an unresponsive mount. Such tasks stop updating (their MBeans keep
   * the last values) while all other metrics continue to refresh.
   */
  @JmxMetricHint("gauge")
  int getStuckTaskCount();

  /**
   * Comma-separated names of the tasks counted by {@link #getStuckTaskCount()}, or an empty string.
   */
  @JmxMetricHint("gauge")
  String getStuckTasks();

  /**
   * Number of log attempts dropped because the {@link ThrottledLogger} key cap was exceeded.
   *
   * <p>A non-zero value indicates that a caller is using dynamic (unbounded) keys instead of
   * bounded constants, which would cause the internal key map to grow without limit if the cap were
   * not enforced. Operators should treat a rising value as a misconfiguration signal.
   */
  @JmxMetricHint("counter")
  long getThrottledLoggerOverflowCount();
}
