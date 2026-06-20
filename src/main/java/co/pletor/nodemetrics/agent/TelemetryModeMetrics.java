package co.pletor.nodemetrics.agent;

/**
 * Default telemetry mode MBean implementation.
 */
public final class TelemetryModeMetrics implements TelemetryModeMetricsMBean {
  private final TelemetryModeState modeState;

  public TelemetryModeMetrics(TelemetryModeState modeState) {
    this.modeState = modeState;
  }

  @Override
  public String getMode() {
    return modeState.current().name();
  }

  @Override
  public long getModeTransitionCount() {
    return modeState.transitions();
  }
}

