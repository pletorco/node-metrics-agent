package co.pletor.nodemetrics.metrics;

/**
 * Metric component that supports explicit polling and optional read-triggered refresh control.
 */
public interface RefreshManagedMetric {
  void poll();

  void setReadRefreshEnabled(boolean enabled);
}

