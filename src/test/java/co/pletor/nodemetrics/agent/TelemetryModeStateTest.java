package co.pletor.nodemetrics.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TelemetryModeStateTest {

  @Test
  void modeState_shouldTrackTransitionsOnlyOnRealModeChange() {
    TelemetryModeState state = new TelemetryModeState();

    assertEquals(TelemetryMode.NORMAL, state.current());
    assertEquals(0L, state.transitions());

    state.transitionTo(TelemetryMode.NORMAL);
    assertEquals(0L, state.transitions(), "Same mode transition should not increase counter");

    state.transitionTo(TelemetryMode.DEGRADED);
    state.transitionTo(TelemetryMode.BYPASS);
    state.transitionTo(TelemetryMode.BYPASS);
    state.transitionTo(TelemetryMode.NORMAL);

    assertEquals(TelemetryMode.NORMAL, state.current());
    assertEquals(3L, state.transitions(), "Only real mode changes should be counted");
  }

  @Test
  void telemetryModeMetrics_shouldExposeModeAndTransitionCount() {
    TelemetryModeState state = new TelemetryModeState();
    TelemetryModeMetrics metrics = new TelemetryModeMetrics(state);

    assertEquals("NORMAL", metrics.getMode());
    assertEquals(0L, metrics.getModeTransitionCount());

    state.transitionTo(TelemetryMode.DEGRADED);
    assertEquals("DEGRADED", metrics.getMode());
    assertEquals(1L, metrics.getModeTransitionCount());
  }
}

