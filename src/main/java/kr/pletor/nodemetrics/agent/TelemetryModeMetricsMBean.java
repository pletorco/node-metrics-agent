package kr.pletor.nodemetrics.agent;

import kr.pletor.nodemetrics.metrics.JmxMetricHint;

/** JMX view for telemetry runtime mode. */
public interface TelemetryModeMetricsMBean {
  @JmxMetricHint("gauge")
  String getMode();

  @JmxMetricHint("counter")
  long getModeTransitionCount();
}
