package co.pletor.nodemetrics.metrics;

/** Metric component that supports explicit polling and optional read-triggered refresh control. */
public interface RefreshManagedMetric {
  /**
   * Refresh the metric now. Implementations must not throw for expected failures; they report them
   * through {@link #lastRefreshError()} instead.
   */
  void poll();

  void setReadRefreshEnabled(boolean enabled);

  /**
   * Error from the most recent refresh attempt, or {@code null} if it succeeded.
   *
   * <p>Metrics absorb read failures (keeping last values or exposing sentinels) so JMX callers
   * never see exceptions; this is how such failures are surfaced to the refresh engine.
   */
  default Throwable lastRefreshError() {
    return null;
  }
}
