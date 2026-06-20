package co.pletor.nodemetrics.agent;

import co.pletor.nodemetrics.metrics.JmxMetricHint;

/**
 * Baseline self-observability metrics for the telemetry module.
 */
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
   * Age in milliseconds of the task that has gone the longest without a successful poll.
   * <p>
   * Rises when the engine is in {@code BYPASS} mode or when a task consistently fails.
   * A value of {@code 0} means all tasks have been polled recently or none exist yet.
   */
  @JmxMetricHint("gauge")
  long getMaxTaskStalenessMs();

  /**
   * Number of log attempts dropped because the {@link ThrottledLogger} key cap was exceeded.
   * <p>
   * A non-zero value indicates that a caller is using dynamic (unbounded) keys instead of
   * bounded constants, which would cause the internal key map to grow without limit if the
   * cap were not enforced. Operators should treat a rising value as a misconfiguration signal.
   */
  @JmxMetricHint("counter")
  long getThrottledLoggerOverflowCount();
}
