package kr.pletor.nodemetrics.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentLogTest {

  private final String name = "test.agentlog." + UUID.randomUUID();
  private final Logger jul = Logger.getLogger(name);
  private final List<LogRecord> published = new CopyOnWriteArrayList<>();
  private final Handler handler =
      new Handler() {
        @Override
        public void publish(LogRecord record) {
          published.add(record);
        }

        @Override
        public void flush() {
          // Nothing to flush.
        }

        @Override
        public void close() {
          // Nothing to close.
        }
      };

  @BeforeEach
  void attach() {
    jul.setUseParentHandlers(false);
    jul.setLevel(Level.ALL);
    jul.addHandler(handler);
    AgentLog.resetForTest(60_000L);
  }

  @AfterEach
  void detach() {
    jul.removeHandler(handler);
    AgentLog.resetForTest(AgentLog.DEFAULT_DEFER_MS);
  }

  @Test
  void warningIsPublishedAtOnce() {
    AgentLog.getLogger(name).log(Level.WARNING, "now");

    assertEquals(1, published.size());
    assertEquals("now", published.get(0).getMessage());
  }

  @Test
  void informationIsHeldBackDuringTheGracePeriod() {
    AgentLog.getLogger(name).log(Level.INFO, "later");

    assertEquals(0, published.size());
    assertEquals(1, AgentLog.pendingCount());
  }

  @Test
  void warningPublishesHeldRecordsFirstAndInOrder() {
    AgentLog log = AgentLog.getLogger(name);
    log.log(Level.INFO, "first");
    log.log(Level.FINE, "second");
    log.log(Level.WARNING, "third");

    assertEquals(List.of("first", "second", "third"), messages());
    assertEquals(0, AgentLog.pendingCount());
  }

  @Test
  void heldRecordsKeepTheirOriginalTime() throws Exception {
    AgentLog.getLogger(name).log(Level.INFO, "early");
    long loggedAt = System.currentTimeMillis();
    Thread.sleep(60L);
    AgentLog.getLogger(name).log(Level.WARNING, "flush");

    LogRecord early = published.get(0);
    assertTrue(early.getMillis() <= loggedAt, "timestamp must be from when it was logged");
  }

  @Test
  void heldRecordsAreFlushedOnceTheGracePeriodIsOver() throws Exception {
    AgentLog.resetForTest(40L);
    AgentLog.getLogger(name).log(Level.INFO, "held");
    assertEquals(0, published.size());

    long deadline = System.nanoTime() + 5_000_000_000L;
    while (published.isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(20L);
      AgentLog.flushIfDue();
    }

    assertEquals(List.of("held"), messages());
  }

  @Test
  void afterTheGracePeriodRecordsAreNotHeld() throws Exception {
    AgentLog.resetForTest(0L);
    Thread.sleep(5L);
    AgentLog.getLogger(name).log(Level.INFO, "direct");

    assertEquals(List.of("direct"), messages());
  }

  @Test
  void recordsKeepParametersExceptionAndCallerMethod() {
    AgentLog log = AgentLog.getLogger(name);
    IllegalStateException boom = new IllegalStateException("boom");

    log.log(Level.WARNING, "value {0}", "x");
    log.log(Level.WARNING, "failed", boom);
    log.log(Level.WARNING, boom, () -> "lazy");

    assertEquals("x", published.get(0).getParameters()[0]);
    assertEquals(boom, published.get(1).getThrown());
    assertEquals("lazy", published.get(2).getMessage());
    assertEquals(boom, published.get(2).getThrown());
    assertEquals(
        "recordsKeepParametersExceptionAndCallerMethod", published.get(0).getSourceMethodName());
    assertEquals(AgentLogTest.class.getName(), published.get(0).getSourceClassName());
  }

  @Test
  void throttledLoggerReportsTheRealCaller() {
    ThrottledLogger throttled = new ThrottledLogger(AgentLog.getLogger(name), 1_000L);

    throttled.log(Level.WARNING, "key", () -> "from a test");

    assertEquals(AgentLogTest.class.getName(), published.get(0).getSourceClassName());
  }

  @Test
  void heldRecordsAreBounded() {
    AgentLog log = AgentLog.getLogger(name);
    for (int i = 0; i < 1_000; i++) {
      log.log(Level.INFO, "spam");
    }

    assertTrue(AgentLog.pendingCount() <= 256);
  }

  @Test
  void loggingNeverThrows() {
    AgentLog log = AgentLog.getLogger(name);

    assertDoesNotThrow(
        () ->
            log.log(
                Level.WARNING,
                new RuntimeException(),
                () -> {
                  throw new IllegalStateException("supplier failed");
                }));
    assertDoesNotThrow(() -> log.log(Level.INFO, "{0} {1}", new Object[] {"only one"}));
    assertNotNull(published);
  }

  private List<String> messages() {
    return published.stream()
        .map(LogRecord::getMessage)
        .collect(java.util.stream.Collectors.toList());
  }
}
