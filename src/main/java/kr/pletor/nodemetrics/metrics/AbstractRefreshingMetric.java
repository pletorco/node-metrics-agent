package kr.pletor.nodemetrics.metrics;

/**
 * Shared refresh plumbing for the metric beans.
 *
 * <p>Subclasses implement {@link #doRefresh()} and read their attributes through {@link
 * #refreshOnRead()}. This class provides:
 *
 * <ul>
 *   <li>a cadence guard so JMX reads never refresh more often than every 500 ms
 *   <li>{@link #poll()} for the asynchronous refresh engine (always refreshes)
 *   <li>the read-refresh switch used to keep refresh work off the JMX read path
 *   <li>failure reporting via {@link #lastRefreshError()}, so failures that a bean absorbs (keeping
 *       the last values or exposing sentinels) stay visible to the engine
 * </ul>
 *
 * <p>Refresh failures never propagate to JMX callers or to {@link #poll()}.
 */
abstract class AbstractRefreshingMetric implements RefreshManagedMetric {

  private static final long READ_REFRESH_INTERVAL_MS = 500L;

  private final RefreshCadence refreshCadence = new RefreshCadence(READ_REFRESH_INTERVAL_MS);
  private volatile boolean readRefreshEnabled = true;
  private volatile Throwable lastRefreshError;

  /**
   * Perform one refresh. Implementations should absorb expected I/O failures (exposing sentinel or
   * last-known values) and report them with {@link #recordRefreshFailure(Throwable)}.
   */
  protected abstract void doRefresh();

  /**
   * Force a refresh, bypassing the cadence guard. The cadence window restarts, so an immediate
   * read-triggered refresh does not repeat the work.
   */
  @Override
  public final void poll() {
    refreshCadence.force();
    if (refreshCadence.tryAcquire()) {
      runRefresh();
    }
  }

  @Override
  public final void setReadRefreshEnabled(boolean enabled) {
    readRefreshEnabled = enabled;
  }

  @Override
  public final Throwable lastRefreshError() {
    return lastRefreshError;
  }

  /** Record that the current refresh failed. Cleared automatically at the start of the next one. */
  protected final void recordRefreshFailure(Throwable error) {
    lastRefreshError = error;
  }

  /**
   * Refresh from a JMX attribute read, if read-triggered refresh is enabled and the cadence guard
   * allows it.
   */
  protected final void refreshOnRead() {
    if (readRefreshEnabled && refreshCadence.tryAcquire()) {
      runRefresh();
    }
  }

  private void runRefresh() {
    lastRefreshError = null;
    try {
      doRefresh();
    } catch (RuntimeException | LinkageError e) {
      recordRefreshFailure(e);
    }
  }
}
