package co.pletor.nodemetrics.metrics;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free refresh cadence guard.
 */
final class RefreshCadence {
  private final long intervalMs;
  private final AtomicLong nextAllowedRefreshMs = new AtomicLong(0L);

  RefreshCadence(long intervalMs) {
    this.intervalMs = intervalMs;
  }

  boolean tryAcquire() {
    long now = System.currentTimeMillis();
    while (true) {
      long nextAllowed = nextAllowedRefreshMs.get();
      if (now < nextAllowed) {
        return false;
      }
      long next = now + intervalMs;
      if (nextAllowedRefreshMs.compareAndSet(nextAllowed, next)) {
        return true;
      }
    }
  }

  void force() {
    nextAllowedRefreshMs.set(0L);
  }
}
