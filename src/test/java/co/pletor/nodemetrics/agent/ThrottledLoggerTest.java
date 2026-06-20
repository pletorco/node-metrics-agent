package co.pletor.nodemetrics.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThrottledLoggerTest {

  @Test
  void repeatedSameKey_shouldBeSuppressedWithinInterval() {
    CapturingHandler handler = new CapturingHandler();
    Logger logger = testLogger(handler);
    ThrottledLogger throttled = new ThrottledLogger(logger, 80L);

    throttled.log(Level.WARNING, "key", () -> "first");
    throttled.log(Level.WARNING, "key", () -> "second");

    assertEquals(1, handler.records.size(), "Second message should be suppressed within interval");
    assertEquals("first", handler.records.get(0).getMessage(), "First message should be logged as-is");

    waitUntilNanos(TimeUnit.MILLISECONDS.toNanos(90L));
    throttled.log(Level.WARNING, "key", () -> "third");

    assertEquals(2, handler.records.size(), "Message should be emitted after interval");
    assertTrue(
        handler.records.get(1).getMessage().contains("suppressed 1 similar log(s)"),
        "Post-interval message should include suppressed count"
    );
  }

  @Test
  void differentKeys_shouldNotShareSuppressionState() {
    CapturingHandler handler = new CapturingHandler();
    Logger logger = testLogger(handler);
    ThrottledLogger throttled = new ThrottledLogger(logger, 1_000L);

    throttled.log(Level.WARNING, "key-a", () -> "alpha");
    throttled.log(Level.WARNING, "key-b", () -> "beta");

    assertEquals(2, handler.records.size(), "Different keys should be logged independently");
    assertEquals("alpha", handler.records.get(0).getMessage());
    assertEquals("beta", handler.records.get(1).getMessage());
  }

  @Test
  void overflowBeyondMaxKeys_shouldDropLogsAndCountOverflow() {
    CapturingHandler handler = new CapturingHandler();
    Logger logger = testLogger(handler);
    ThrottledLogger throttled = new ThrottledLogger(logger, 1_000L, 2); // cap at 2 keys

    throttled.log(Level.WARNING, "key-1", () -> "first");
    throttled.log(Level.WARNING, "key-2", () -> "second");
    throttled.log(Level.WARNING, "key-3", () -> "third-should-be-dropped");

    assertEquals(2, handler.records.size(), "Third key beyond maxKeys should be dropped");
    assertEquals(1L, throttled.overflowCount(), "Overflow counter should be incremented for dropped key");
  }

  @Test
  void knownKeyBeyondCap_shouldStillBeThrottled() {
    CapturingHandler handler = new CapturingHandler();
    Logger logger = testLogger(handler);
    ThrottledLogger throttled = new ThrottledLogger(logger, 1_000L, 1); // cap at 1 key

    throttled.log(Level.WARNING, "key-1", () -> "first");
    throttled.log(Level.WARNING, "key-1", () -> "second-suppressed");

    assertEquals(1, handler.records.size(), "Second log for existing key should be suppressed");
    assertEquals(0L, throttled.overflowCount(), "No overflow for already-known key");
  }

  @Test
  void concurrentNewKeys_shouldNeverExceedMaxKeys() throws InterruptedException {
    // Verifies the TOCTOU fix: concurrent threads registering different new keys
    // must never push the internal map beyond maxKeys.
    CapturingHandler handler = new CapturingHandler();
    Logger logger = testLogger(handler);
    int maxKeys = 4;
    ThrottledLogger throttled = new ThrottledLogger(logger, 1_000L, maxKeys);

    int threads = 16;
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);

    for (int i = 0; i < threads; i++) {
      final String key = "concurrent-key-" + i;
      pool.submit(() -> {
        ready.countDown();
        try {
          start.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        throttled.log(Level.WARNING, key, () -> "msg");
      });
    }

    ready.await();
    start.countDown();
    pool.shutdown();
    pool.awaitTermination(5, TimeUnit.SECONDS);

    // At most maxKeys distinct keys should have been accepted.
    long logged = handler.records.size();
    long overflow = throttled.overflowCount();
    assertEquals(threads, logged + overflow,
        "Every call must either be logged or counted as overflow");
    assertTrue(logged <= maxKeys,
        "Logged key count (" + logged + ") must not exceed maxKeys (" + maxKeys + ")");
  }

  @Test
  void throwableLog_shouldKeepThrowableInRecord() {
    CapturingHandler handler = new CapturingHandler();
    Logger logger = testLogger(handler);
    ThrottledLogger throttled = new ThrottledLogger(logger, 1_000L);
    RuntimeException ex = new RuntimeException("boom");

    throttled.log(Level.SEVERE, "error-key", ex, () -> "error happened");

    assertEquals(1, handler.records.size());
    LogRecord rec = handler.records.get(0);
    assertEquals("error happened", rec.getMessage());
    assertNotNull(rec.getThrown(), "Thrown exception should be attached to log record");
    assertSame(ex, rec.getThrown(), "Same throwable instance should be used");
  }

  private static void waitUntilNanos(long durationNanos) {
    long deadline = System.nanoTime() + durationNanos;
    while (System.nanoTime() < deadline) {
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
    }
  }

  private static Logger testLogger(Handler handler) {
    Logger logger = Logger.getLogger("co.pletor.nodemetrics.agent.ThrottledLoggerTest." + UUID.randomUUID());
    logger.setUseParentHandlers(false);
    logger.setLevel(Level.ALL);
    logger.addHandler(handler);
    return logger;
  }

  static class CapturingHandler extends Handler {
    final List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void publish(LogRecord logRecord) {
      records.add(logRecord);
    }

    @Override
    public void flush() {
      // no-op
    }

    @Override
    public void close() {
      // no-op
    }
  }
}
