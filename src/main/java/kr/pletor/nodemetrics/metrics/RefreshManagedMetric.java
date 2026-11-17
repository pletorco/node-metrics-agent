package kr.pletor.nodemetrics.metrics;

/** Metric component that supports explicit polling and optional read-triggered refresh control. */
public interface RefreshManagedMetric {
  /**
   * Refresh the metric now. Implementations must not throw for expected failures; they report them
   * through {@link #lastRefreshError()} instead.
   */
  void poll();

  void setReadRefreshEnabled(boolean enabled);

  /**
   * Whether an attribute read may refresh the values itself. The agent sets this to {@code false}
   * before publishing an MBean, so reads return stored values even before the refresh engine starts
   * or if it fails. Standalone use may retain read-triggered refresh. The default is {@code true},
   * the cautious answer for a metric that does not say.
   *
   * @return {@code true} while reads may trigger a refresh
   */
  default boolean isReadRefreshEnabled() {
    return true;
  }

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
