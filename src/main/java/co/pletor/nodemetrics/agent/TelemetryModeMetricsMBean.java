package co.pletor.nodemetrics.agent;

import co.pletor.nodemetrics.metrics.JmxMetricHint;

/**
 * JMX view for telemetry runtime mode.
 */
public interface TelemetryModeMetricsMBean {
  @JmxMetricHint("gauge")
  String getMode();

  @JmxMetricHint("counter")
  long getModeTransitionCount();
}

