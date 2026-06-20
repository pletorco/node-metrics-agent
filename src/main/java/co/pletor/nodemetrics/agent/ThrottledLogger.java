package co.pletor.nodemetrics.agent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Small utility for rate-limiting repeated log messages by key.
 * <p>
 * Keys should be bounded enums/constants, not unbounded user input.
 * The maximum number of distinct keys is capped at {@code maxKeys} to prevent
 * unbounded map growth if a caller accidentally passes dynamic values as keys.
 * Attempts to register a new key beyond the cap are silently dropped and counted
 * in {@link #overflowCount()} so operators can detect misuse.
 */
final class ThrottledLogger {
  private final Logger logger;
  private final long intervalMs;
  private final int maxKeys;
  private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();
  private final AtomicInteger keyCount = new AtomicInteger(0);
  private final LongAdder overflowCount = new LongAdder();

  private static final int DEFAULT_MAX_KEYS = 64;

  private static final class State {
    long nextAllowedAtMs;
    long suppressedCount;
  }

  ThrottledLogger(Logger logger, long intervalMs) {
    this(logger, intervalMs, DEFAULT_MAX_KEYS);
  }

  ThrottledLogger(Logger logger, long intervalMs, int maxKeys) {
    this.logger = logger;
    this.intervalMs = intervalMs;
    this.maxKeys = maxKeys;
  }

  /** Returns the number of log attempts dropped because the key cap was exceeded. */
  long overflowCount() {
    return overflowCount.sum();
  }

  void log(Level level, String key, Supplier<String> messageSupplier) {
    log(level, key, null, messageSupplier);
  }

  void log(Level level, String key, Throwable error, Supplier<String> messageSupplier) {
    String message;
    long suppressed;
    long now = System.currentTimeMillis();

    State state = states.get(key);
    if (state == null) {
      // Use compute() to atomically check the cap and insert.
      // keyCount is incremented speculatively and rolled back if the cap is exceeded,
      // ensuring the hard limit is never exceeded even under concurrent callers.
      state = states.compute(key, (k, existing) -> {
        if (existing != null) return existing;
        int n = keyCount.incrementAndGet();
        if (n > maxKeys) {
          keyCount.decrementAndGet();
          return null; // signal: cap exceeded, do not insert
        }
        return new State();
      });
      if (state == null) {
        overflowCount.increment();
        return;
      }
    }

    synchronized (state) {
      if (now < state.nextAllowedAtMs) {
        state.suppressedCount++;
        return;
      }
      suppressed = state.suppressedCount;
      state.suppressedCount = 0L;
      state.nextAllowedAtMs = now + intervalMs;
      message = messageSupplier.get();
    }

    if (suppressed > 0L) {
      message = message + " (suppressed " + suppressed + " similar log(s))";
    }

    if (error == null) {
      logger.log(level, message);
    } else {
      logger.log(level, message, error);
    }
  }
}
