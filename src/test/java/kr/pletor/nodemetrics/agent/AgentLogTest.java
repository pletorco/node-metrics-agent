package kr.pletor.nodemetrics.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

class AgentLogTest {
  private final String name = "test.agentlog." + UUID.randomUUID();
  private final List<LogRecord> published = new CopyOnWriteArrayList<>();

  @Test
  void warningAndInformationArePublishedInOrderWithoutDelay() {
    try (var capture = new AgentLogCapture(published::add)) {
      AgentLog log = AgentLog.getLogger(name);
      log.log(Level.INFO, "first");
      log.log(Level.WARNING, "second");
    }
    assertEquals(List.of("first", "second"), messages());
  }

  @Test
  void fineDiagnosticsAreFilteredWithoutQueueing() {
    try (var capture = new AgentLogCapture(published::add)) {
      AgentLog.getLogger(name).log(Level.FINE, "debug");
    }
    assertTrue(published.isEmpty());
  }

  @Test
  void recordsKeepParametersExceptionTimestampAndCallerMethod() {
    IllegalStateException boom = new IllegalStateException("boom");
    long after;
    try (var capture = new AgentLogCapture(published::add)) {
      AgentLog log = AgentLog.getLogger(name);
      log.log(Level.WARNING, "value {0}", "x");
      log.log(Level.WARNING, "failed", boom);
      log.log(Level.WARNING, boom, () -> "lazy");
      after = System.currentTimeMillis();
    }
    assertEquals("x", published.get(0).getParameters()[0]);
    assertEquals(boom, published.get(1).getThrown());
    assertEquals("lazy", published.get(2).getMessage());
    assertTrue(published.get(0).getMillis() <= after);
    assertEquals(AgentLogTest.class.getName(), published.get(0).getSourceClassName());
    assertEquals(
        "recordsKeepParametersExceptionTimestampAndCallerMethod",
        published.get(0).getSourceMethodName());
  }

  @Test
  void throttledLoggerReportsTheActualCaller() {
    try (var capture = new AgentLogCapture(published::add)) {
      new ThrottledLogger(AgentLog.getLogger(name), 1_000L)
          .log(Level.WARNING, "key", () -> "from a test");
    }
    assertEquals(AgentLogTest.class.getName(), published.get(0).getSourceClassName());
  }

  @Test
  void blockedOutputCannotBlockProducersOrGrowTheQueue() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (var capture =
        new AgentLogCapture(
            record -> {
              entered.countDown();
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            })) {
      try {
        AgentLog log = AgentLog.getLogger(name);
        log.log(Level.WARNING, "block the writer");
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        final long dropped = AgentLog.droppedCount();
        long before = System.nanoTime();
        for (int i = 0; i < 1_000; i++) {
          log.log(Level.WARNING, "spam");
        }
        assertTrue(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(2));
        assertEquals(256, AgentLog.queuedCount());
        assertTrue(AgentLog.droppedCount() - dropped >= 744);
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void failingOutputIsDroppedWithoutStoppingTheWriter() {
    long before = AgentLog.droppedCount();
    try (var capture =
        new AgentLogCapture(
            record -> {
              if (record.getMessage().equals("bad")) {
                throw new LinkageError("output failed");
              }
              published.add(record);
            })) {
      AgentLog log = AgentLog.getLogger(name);
      assertDoesNotThrow(() -> log.log(Level.WARNING, "bad"));
      log.log(Level.INFO, "recovered");
    }
    assertTrue(AgentLog.droppedCount() > before);
    assertEquals(List.of("recovered"), messages());
  }

  @Test
  void outputRunsOnlyOnThePrivateDaemon() {
    List<Thread> writers = new CopyOnWriteArrayList<>();
    try (var capture = new AgentLogCapture(record -> writers.add(Thread.currentThread()))) {
      AgentLog.getLogger(name).log(Level.WARNING, "daemon check");
    }
    assertEquals(1, writers.size());
    assertTrue(writers.get(0).isDaemon());
    assertEquals("node-metrics-agent-log", writers.get(0).getName());
  }

  @Test
  void supplierFailureDoesNotEscape() {
    try (var capture = new AgentLogCapture(published::add)) {
      assertDoesNotThrow(
          () ->
              AgentLog.getLogger(name)
                  .log(
                      Level.WARNING,
                      new RuntimeException(),
                      () -> {
                        throw new IllegalStateException("supplier failed");
                      }));
    }
    assertEquals(List.of("<message unavailable>"), messages());
  }

  private List<String> messages() {
    return published.stream()
        .map(LogRecord::getMessage)
        .collect(java.util.stream.Collectors.toList());
  }
}
