package co.pletor.nodemetrics.agent;

import java.util.function.Supplier;

/**
 * JMX adapter exposing refresh engine baseline counters and mode.
 */
public final class AgentObservabilityMetrics implements AgentObservabilityMetricsMBean {
  private final Supplier<MetricsRefreshEngine> engineSupplier;
  private final Supplier<TelemetryMode> modeSupplier;
  private final Supplier<Long> throttledLoggerOverflowSupplier;

  AgentObservabilityMetrics(
      Supplier<MetricsRefreshEngine> engineSupplier,
      Supplier<TelemetryMode> modeSupplier,
      Supplier<Long> throttledLoggerOverflowSupplier
  ) {
    this.engineSupplier = engineSupplier;
    this.modeSupplier = modeSupplier;
    this.throttledLoggerOverflowSupplier = throttledLoggerOverflowSupplier;
  }

  @Override
  public long getProcessedCount() {
    MetricsRefreshEngine engine = engineSupplier.get();
    return engine == null ? 0L : engine.processedCount();
  }

  @Override
  public long getEnqueueCount() {
    MetricsRefreshEngine engine = engineSupplier.get();
    return engine == null ? 0L : engine.enqueueCount();
  }

  @Override
  public long getDequeueCount() {
    MetricsRefreshEngine engine = engineSupplier.get();
    return engine == null ? 0L : engine.dequeueCount();
  }

  @Override
  public long getDroppedCount() {
    MetricsRefreshEngine engine = engineSupplier.get();
    return engine == null ? 0L : engine.droppedCount();
  }

  @Override
  public long getErrorCount() {
    MetricsRefreshEngine engine = engineSupplier.get();
    return engine == null ? 0L : engine.errorCount();
  }

  @Override
  public long getSinkSuccessCount() {
    MetricsRefreshEngine engine = engineSupplier.get();
    return engine == null ? 0L : engine.sinkSuccessCount();
  }

  @Override
  public long getSinkFailureCount() {
    MetricsRefreshEngine engine = engineSupplier.get();
    return engine == null ? 0L : engine.sinkFailureCount();
  }

  @Override
  public long getSinkRetryCount() {
    MetricsRefreshEngine engine = engineSupplier.get();
    return engine == null ? 0L : engine.sinkRetryCount();
  }

  @Override
  public String getMode() {
    TelemetryMode mode = modeSupplier.get();
    return mode == null ? TelemetryMode.NORMAL.name() : mode.name();
  }

  @Override
  public int getQueueDepth() {
    MetricsRefreshEngine engine = engineSupplier.get();
    return engine == null ? 0 : engine.queueSize();
  }

  @Override
  public double getQueueFillRatio() {
    MetricsRefreshEngine engine = engineSupplier.get();
    return engine == null ? 0.0 : engine.queueFillRatio();
  }

  @Override
  public double getEndToEndLatencyMillis() {
    MetricsRefreshEngine engine = engineSupplier.get();
    return engine == null ? 0.0 : engine.endToEndLatencyMillis();
  }

  @Override
  public long getMaxTaskStalenessMs() {
    MetricsRefreshEngine engine = engineSupplier.get();
    return engine == null ? 0L : engine.maxTaskStalenessMs();
  }

  @Override
  public long getThrottledLoggerOverflowCount() {
    Long count = throttledLoggerOverflowSupplier.get();
    return count == null ? 0L : count;
  }
}
