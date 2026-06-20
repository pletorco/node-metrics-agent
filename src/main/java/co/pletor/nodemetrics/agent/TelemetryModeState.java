package co.pletor.nodemetrics.agent;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe runtime holder for telemetry mode and transition count.
 */
final class TelemetryModeState {
  private final AtomicReference<TelemetryMode> mode = new AtomicReference<>(TelemetryMode.NORMAL);
  private final LongAdder transitionCount = new LongAdder();

  TelemetryMode current() {
    return mode.get();
  }

  void transitionTo(TelemetryMode nextMode) {
    TelemetryMode previous = mode.getAndSet(nextMode);
    if (previous != nextMode) {
      transitionCount.increment();
    }
  }

  long transitions() {
    return transitionCount.sum();
  }
}
