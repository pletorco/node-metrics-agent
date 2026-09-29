package co.pletor.nodemetrics.metrics;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Lock-free refresh cadence guard.
 *
 * <p>Uses a monotonic clock so wall-clock adjustments (NTP steps, manual changes) cannot suppress
 * refreshes or make them fire early.
 */
final class RefreshCadence {
  private final long intervalNanos;
  private final LongSupplier nanoClock;

  /** Earliest {@code nanoClock} value at which the next refresh is allowed. */
  private final AtomicLong nextAllowedNanos;

  RefreshCadence(long intervalMs) {
    this(intervalMs, System::nanoTime);
  }

  // Visible for testing
  RefreshCadence(long intervalMs, LongSupplier nanoClock) {
    this.intervalNanos = TimeUnit.MILLISECONDS.toNanos(intervalMs);
    this.nanoClock = nanoClock;
    this.nextAllowedNanos = new AtomicLong(nanoClock.getAsLong());
  }

  boolean tryAcquire() {
    long now = nanoClock.getAsLong();
    while (true) {
      long nextAllowed = nextAllowedNanos.get();
      // Subtraction keeps the comparison correct across nanoTime origin/overflow.
      if (now - nextAllowed < 0L) {
        return false;
      }
      if (nextAllowedNanos.compareAndSet(nextAllowed, now + intervalNanos)) {
        return true;
      }
    }
  }

  void force() {
    nextAllowedNanos.set(nanoClock.getAsLong());
  }
}
