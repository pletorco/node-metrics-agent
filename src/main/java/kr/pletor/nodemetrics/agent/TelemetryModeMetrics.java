package kr.pletor.nodemetrics.agent;

import java.util.function.Supplier;

/** Default telemetry mode MBean implementation. */
public final class TelemetryModeMetrics implements TelemetryModeMetricsMBean {
  private final TelemetryModeState modeState;
  private final Supplier<TelemetryMode> modeSupplier;

  public TelemetryModeMetrics(TelemetryModeState modeState) {
    this(modeState, modeState::current);
  }

  TelemetryModeMetrics(TelemetryModeState modeState, Supplier<TelemetryMode> modeSupplier) {
    this.modeState = modeState;
    this.modeSupplier = modeSupplier;
  }

  @Override
  public String getMode() {
    return modeSupplier.get().name();
  }

  @Override
  public long getModeTransitionCount() {
    return modeState.transitions();
  }
}
